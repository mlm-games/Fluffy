package app.fluffy.helper

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import androidx.core.content.FileProvider
import app.fluffy.archive.ArchiveEngine
import app.fluffy.io.FileSystemAccess
import app.fluffy.io.SafIo
import app.fluffy.ui.viewers.ImageViewerActivity
import app.fluffy.ui.viewers.MediaPlayerActivity
import app.fluffy.ui.viewers.PdfViewerActivity
import app.fluffy.ui.viewers.TextViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.ArrayList


sealed class OpenTarget {
    data class Images(val uris: List<Uri>, val title: String? = null) : OpenTarget()
    data class Archive(val uri: Uri) : OpenTarget()
    data class Shared(val uris: List<Uri>, val mime: String? = null) : OpenTarget()
    data object None : OpenTarget()
}

object AppUtilsHelper : KoinComponent {
    val io: SafIo by inject()
    val archive: ArchiveEngine by inject()
}

@Suppress("DEPRECATION")
inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(key, T::class.java)
    else -> getParcelableExtra(key) as? T
}

@Suppress("DEPRECATION")
inline fun <reified T : Parcelable> Intent.parcelableArrayList(key: String): ArrayList<T>? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableArrayListExtra(key, T::class.java)
    else -> getParcelableArrayListExtra(key)
}

fun Intent.detectTarget(): OpenTarget {
    val action = this.action ?: return OpenTarget.None
    val mime = this.type

    fun isImage(m: String?) = m?.startsWith("image/") == true

    fun extractUris(): List<Uri> {
        val list = mutableListOf<Uri>()
        data?.let { list += it }

        // SEND
        runCatching { parcelable<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.let { list += it }

        // SEND_MULTIPLE
        runCatching { parcelableArrayList<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.let { list += it }

        clipData?.let { cd ->
            for (i in 0 until cd.itemCount) {
                cd.getItemAt(i)?.uri?.let { list += it }
            }
        }
        return list.distinct()
    }

    return when (action) {
        Intent.ACTION_VIEW -> {
            val u = data ?: return OpenTarget.None
            if (isImage(mime)) {
                OpenTarget.Images(listOf(u), AppUtilsHelper.io.queryDisplayName(u))
            } else {
                val name = AppUtilsHelper.io.queryDisplayName(u).lowercase()
                if (FileSystemAccess.isArchiveFile(name)) OpenTarget.Archive(u) else OpenTarget.None
            }
        }

        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
            val uris = extractUris()
            if (uris.isEmpty()) return OpenTarget.None

            if (isImage(mime)) {
                return OpenTarget.Images(uris, uris.firstOrNull()?.let { AppUtilsHelper.io.queryDisplayName(it) })
            }

            if (uris.size == 1) {
                val name = AppUtilsHelper.io.queryDisplayName(uris[0])
                if (FileSystemAccess.isArchiveFile(name)) return OpenTarget.Archive(uris[0])
            }

            OpenTarget.Shared(uris, mime)
        }

        else -> OpenTarget.None
    }
}

fun sanitizeCacheName(name: String, fallback: String = "file"): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { fallback }
    return base.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(64).ifBlank { fallback }
}

suspend fun Context.toViewableUri(uri: Uri, displayName: String = "image"): Uri = withContext(Dispatchers.IO) {
    when (uri.scheme) {
        "root", "shizuku" -> {
            val safe = sanitizeCacheName(displayName, "image")
            val out = File.createTempFile("img_${System.currentTimeMillis()}_", "_$safe", cacheDir)
            AppUtilsHelper.io.openIn(uri).use { input -> out.outputStream().use { input.copyTo(it) } }
            FileProvider.getUriForFile(this@toViewableUri, "$packageName.fileprovider", out)
        }
        else -> uri
    }
}

suspend fun Context.toViewableUris(items: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
    items.map { u -> toViewableUri(u, AppUtilsHelper.io.queryDisplayName(u)) }
}

private fun ContentResolver.clipForAll(label: String, uris: List<Uri>): ClipData? {
    if (uris.isEmpty()) return null
    val first = uris.first()
    val clip = ClipData.newUri(this, label, first)
    for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
    return clip
}

fun Context.launchImageViewer(uris: List<Uri>, startIndex: Int = 0, title: String? = null) {
    if (uris.isEmpty()) return
    val intent = Intent(this, ImageViewerActivity::class.java).apply {
        putStringArrayListExtra(ImageViewerActivity.EXTRA_IMAGES, ArrayList(uris.map { it.toString() }))
        putExtra(ImageViewerActivity.EXTRA_INITIAL_INDEX, startIndex)
        title?.let { putExtra(ImageViewerActivity.EXTRA_TITLE, it) }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        contentResolver.clipForAll("images", uris)?.let { clipData = it }
        if (this@launchImageViewer !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}




fun Context.purgeOldViewerCache(maxAgeMs: Long = 48L * 3600_000L) {
    val now = System.currentTimeMillis()
    cacheDir.listFiles()?.forEach { f ->
        if (f.name.startsWith("img_") && (now - f.lastModified()) > maxAgeMs) runCatching { f.delete() }
    }
}

fun Context.purgeOldExports(maxAgeMs: Long = 72L * 3600_000) { val now = System.currentTimeMillis()
    cacheDir.listFiles()?.forEach { f -> if (f.name.startsWith("export_") && now - f.lastModified() > maxAgeMs)
        runCatching { f.delete() } }
}


// Need to copy root:// and shizuku:// to cache and wrap with FileProvider.
suspend fun Context.exportForOpenWith(src: Uri, displayName: String): Uri = withContext(Dispatchers.IO) {
    when (src.scheme) {
        "content" -> src
        "file" -> {
            val f = File(requireNotNull(src.path))
            FileProvider.getUriForFile(this@exportForOpenWith, "$packageName.fileprovider", f)
        }
        "root", "shizuku" -> {
            val safe = sanitizeCacheName(displayName)
            val out = File.createTempFile("export_${System.currentTimeMillis()}_", "_$safe", cacheDir)
            AppUtilsHelper.io.openIn(src).use { `in` -> out.outputStream().use { `in`.copyTo(it) } }
            FileProvider.getUriForFile(this@exportForOpenWith, "$packageName.fileprovider", out)
        }
        else -> src
    }
}

suspend fun Context.exportAllForOpenWith(items: List<Pair<Uri, String>>): List<Uri> =
    items.map { (u, name) -> exportForOpenWith(u, name) }

suspend fun Context.openWithExport(
    src: Uri,
    displayName: String,
    preferMime: Boolean
) {
    val exported = exportForOpenWith(src, displayName)
    val mime = if (preferMime) {
        contentResolver.getType(exported) ?: FileSystemAccess.getMimeType(displayName)
    } else FileSystemAccess.getMimeType(displayName)

    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(exported, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(contentResolver, "file", exported)
        if (this@openWithExport !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(Intent.createChooser(view, "Open with"))
}

// Multiple items (future multi-select “Open with…”)
suspend fun Context.openWithExportMultiple(
    sources: List<Pair<Uri, String>>,
    commonMime: String = "*/*"
) {
    val exported = exportAllForOpenWith(sources)
    if (exported.isEmpty()) return

    val send = if (exported.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, exported.first())
            type = commonMime
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(exported))
            type = commonMime
        }
    }.apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val first = exported.first()
        val clip = ClipData.newUri(contentResolver, "files", first)
        exported.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        clipData = clip
        if (this@openWithExportMultiple !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    startActivity(Intent.createChooser(send, "Open with"))
}

suspend fun Context.shareExported(
    sources: List<Pair<Uri, String>>
) {
    val exported = exportAllForOpenWith(sources)
    if (exported.isEmpty()) return

    val mime = if (exported.size == 1) {
        val displayName = sources.firstOrNull()?.second.orEmpty()
        contentResolver.getType(exported.first())
            ?: FileSystemAccess.getMimeType(displayName)
    } else {
        val resolved = exported.mapIndexed { i, uri ->
            runCatching { contentResolver.getType(uri) }.getOrNull()
                ?: FileSystemAccess.getMimeType(sources.getOrNull(i)?.second.orEmpty())
        }
        val distinct = resolved.distinct()
        if (distinct.size == 1) distinct.first()
        else {
            val top = resolved.map { it.substringBefore('/') }.distinct()
            if (top.size == 1) "${top.first()}/*" else "*/*"
        }
    }

    val send = if (exported.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, exported.first())
            type = mime
            sources.firstOrNull()?.second?.takeIf { it.isNotBlank() }?.let {
                putExtra(Intent.EXTRA_SUBJECT, it)
            }
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(exported))
            type = mime
        }
    }.apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val first = exported.first()
        val clip = ClipData.newUri(contentResolver, "files", first)
        exported.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        clipData = clip
        if (this@shareExported !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    startActivity(Intent.createChooser(send, "Share"))
}

suspend fun Context.shareWithFolders(
    sources: List<Pair<Uri, String>>,
    onStatus: suspend (String) -> Unit = {}
): Boolean = withContext(Dispatchers.IO) {
    if (sources.isEmpty()) return@withContext false
    val io = AppUtilsHelper.io
    val hasDir = sources.any { (uri, _) -> runCatching { io.isDirectory(uri) }.getOrDefault(false) }
    if (!hasDir) {
        shareExported(sources)
        return@withContext true
    }
    onStatus("Compressing for share…")
    val zip = runCatching { createTempZipForShare(sources) }.getOrNull()
    if (zip == null) {
        onStatus("Couldn't compress folders for share")
        return@withContext false
    }
    val zipUri = FileProvider.getUriForFile(this@shareWithFolders, "$packageName.fileprovider", zip)
    shareExported(listOf(zipUri to zip.name))
    true
}

suspend fun Context.createTempZipForShare(sources: List<Pair<Uri, String>>): File =
    withContext(Dispatchers.IO) {
        val io = AppUtilsHelper.io
        val archive = AppUtilsHelper.archive
        val pairs = mutableListOf<Pair<String, () -> InputStream>>()
        val seen = HashSet<String>()
        for ((uri, name) in sources) {
            collectShareEntries(uri, sanitizeCacheName(name, "item"), pairs, seen, depth = 0)
        }
        if (pairs.isEmpty()) throw IOException("Nothing to share")
        val base = if (sources.size == 1) {
            sources.first().second.substringBeforeLast('.').ifBlank { "shared" }
        } else "shared_files"
        val safeBase = sanitizeCacheName(base, "shared").take(32)
        val out = File.createTempFile("share_${System.currentTimeMillis()}_${safeBase}_", ".zip", cacheDir)
        try {
            archive.createZip(pairs, { out.outputStream() }, compressionLevel = 5)
        } catch (e: Exception) {
            runCatching { out.delete() }
            throw e
        }
        out
    }

private fun collectShareEntries(
    uri: Uri,
    relPath: String,
    out: MutableList<Pair<String, () -> InputStream>>,
    seen: MutableSet<String>,
    depth: Int
) {
    if (depth > 64) throw IOException("Max depth exceeded")
    val io = AppUtilsHelper.io
    val safeRel = relPath.replace('\\', '/').trim().trimStart('/').ifBlank { "item" }
    if (safeRel.split('/').any { it == "." || it == ".." || it.isBlank() }) {
        throw IOException("Invalid entry: $relPath")
    }
    if (uri.scheme == "root" || uri.scheme == "shizuku") {
        val isFile = runCatching { io.openIn(uri).close() }.isSuccess
        if (isFile) {
            val key = if (seen.add(safeRel)) safeRel else disambiguateShareName(safeRel, seen)
            out += key to { io.openIn(uri) }
        } else {
            val kids = runCatching { io.listShell(uri) }.getOrDefault(emptyList())
            if (kids.isEmpty()) {
                val dirKey = "${safeRel.trimEnd('/')}/"
                if (seen.add(dirKey)) out += dirKey to { ByteArrayInputStream(ByteArray(0)) }
            } else {
                kids.forEach { child ->
                    collectShareEntries(child.uri, "$safeRel/${child.name}", out, seen, depth + 1)
                }
            }
        }
        return
    }
    val df = runCatching { io.docFileFromUri(uri) }.getOrNull()
    if (uri.scheme == "content" && df != null) {
        if (df.isDirectory) {
            val kids = df.listFiles()
            if (kids.isEmpty()) {
                val dirKey = "${safeRel.trimEnd('/')}/"
                if (seen.add(dirKey)) out += dirKey to { ByteArrayInputStream(ByteArray(0)) }
            } else {
                kids.forEach { child ->
                    collectShareEntries(child.uri, "${safeRel.trimEnd('/')}/${child.name ?: "item"}", out, seen, depth + 1)
                }
            }
        } else {
            val key = if (seen.add(safeRel)) safeRel else disambiguateShareName(safeRel, seen)
            out += key to { io.openIn(uri) }
        }
    } else {
        val f = File(requireNotNull(uri.path))
        if (f.isDirectory) {
            val kids = f.listFiles()
            if (kids.isNullOrEmpty()) {
                val dirKey = "${safeRel.trimEnd('/')}/"
                if (seen.add(dirKey)) out += dirKey to { ByteArrayInputStream(ByteArray(0)) }
            } else {
                kids.forEach { child ->
                    collectShareEntries(
                        Uri.fromFile(child),
                        "${safeRel.trimEnd('/')}/${child.name}",
                        out,
                        seen,
                        depth + 1
                    )
                }
            }
        } else {
            val key = if (seen.add(safeRel)) safeRel else disambiguateShareName(safeRel, seen)
            out += key to { f.inputStream() }
        }
    }
}

private fun disambiguateShareName(base: String, seen: MutableSet<String>): String {
    var i = 2
    while (true) {
        val dot = base.lastIndexOf('.')
        val cand = if (dot > 0) "${base.substring(0, dot)}_$i${base.substring(dot)}" else "${base}_$i"
        if (seen.add(cand)) return cand
        i++
    }
}

fun Context.purgeOldShareZips(maxAgeMs: Long = 72L * 3600_000L) {
    val now = System.currentTimeMillis()
    cacheDir.listFiles()?.forEach { f ->
        if (f.name.startsWith("share_") && f.name.endsWith(".zip") && now - f.lastModified() > maxAgeMs) {
            runCatching { f.delete() }
        }
    }
}

fun Context.launchMediaPlayer(uri: Uri, title: String? = null) {
    val intent = Intent(this, MediaPlayerActivity::class.java).apply {
        data = uri
        title?.let { putExtra(MediaPlayerActivity.EXTRA_TITLE, it) }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(contentResolver, "media", uri)
        if (this@launchMediaPlayer !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

fun Context.launchTextViewer(uri: Uri, title: String? = null) {
    val intent = Intent(this, TextViewerActivity::class.java).apply {
        data = uri
        title?.let { putExtra(TextViewerActivity.EXTRA_TITLE, it) }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(contentResolver, "text", uri)
        if (this@launchTextViewer !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

fun Context.launchPdfViewer(uri: Uri) {
    val intent = Intent(this, PdfViewerActivity::class.java).apply {
        data = uri
        putExtra(PdfViewerActivity.EXTRA_URI, uri.toString())
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(contentResolver, "pdf", uri)
        if (this@launchPdfViewer !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

private fun isProbablyText(name: String, mime: String): Boolean {
    if (mime.startsWith("text/")) return true
    val lower = name.lowercase()
    val textExt = setOf(
        "txt", "md", "markdown", "json", "xml", "html", "htm", "css", "js", "ts",
        "kt", "kts", "java", "py", "c", "cpp", "h", "hpp", "cs", "go", "rs",
        "sh", "bat", "cmd", "yml", "yaml", "toml", "ini", "cfg", "conf", "log",
        "csv", "svg", "properties", "gradle", "sql"
    )
    val ext = lower.substringAfterLast('.', "")
    return ext in textExt
}

/**
 * Opens with built-in viewer when possible; otherwise fall back to system chooser.
 * @return true if a built-in viewer was used
 */
suspend fun Context.openWithBuiltInViewer(
    src: Uri,
    displayName: String,
    preferMime: Boolean
): Boolean {
    val exported = exportForOpenWith(src, displayName)
    val mime = if (preferMime) {
        contentResolver.getType(exported) ?: FileSystemAccess.getMimeType(displayName)
    } else {
        FileSystemAccess.getMimeType(displayName)
    }

    return when {
        mime.startsWith("image/") ||
            FileSystemAccess.getMimeType(displayName).startsWith("image/") -> {
            val viewable = toViewableUri(exported, displayName)
            launchImageViewer(listOf(viewable), startIndex = 0, title = displayName)
            true
        }
        mime.startsWith("video/") || mime.startsWith("audio/") -> {
            launchMediaPlayer(exported, displayName)
            true
        }
        mime == "application/pdf" || displayName.lowercase().endsWith(".pdf") -> {
            launchPdfViewer(exported)
            true
        }
        isProbablyText(displayName, mime) -> {
            launchTextViewer(exported, displayName)
            true
        }
        else -> false
    }
}

suspend fun Context.openContent(
    src: Uri,
    displayName: String,
    preferBuiltInViewers: Boolean,
    preferMime: Boolean,
    forceChooser: Boolean = false
) {
    if (!forceChooser && preferBuiltInViewers) {
        val used = openWithBuiltInViewer(src, displayName, preferMime)
        if (used) return
    }
    openWithExport(src, displayName, preferMime)
}
