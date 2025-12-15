package app.fluffy.helper

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.fluffy.AppGraph
import app.fluffy.io.FileSystemAccess
import app.fluffy.ui.viewers.ImageViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


sealed class OpenTarget {
    data class Images(val uris: List<Uri>, val title: String? = null) : OpenTarget()
    data class Archive(val uri: Uri) : OpenTarget()
    data class Shared(val uris: List<Uri>, val mime: String? = null) : OpenTarget()
    data object None : OpenTarget()
}

fun Intent.detectTarget(): OpenTarget {
    val action = this.action ?: return OpenTarget.None
    val mime = this.type

    fun isImage(m: String?) = m?.startsWith("image/") == true

    fun extractUris(): List<Uri> {
        val list = mutableListOf<Uri>()
        data?.let { list += it }

        // SEND
        runCatching { getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.let { list += it }

        // SEND_MULTIPLE
        runCatching { getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()?.let { list += it }

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
                OpenTarget.Images(listOf(u), AppGraph.io.queryDisplayName(u))
            } else {
                val name = AppGraph.io.queryDisplayName(u).lowercase()
                if (FileSystemAccess.isArchiveFile(name)) OpenTarget.Archive(u) else OpenTarget.None
            }
        }

        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
            val uris = extractUris()
            if (uris.isEmpty()) return OpenTarget.None

            if (isImage(mime)) {
                return OpenTarget.Images(uris, uris.firstOrNull()?.let { AppGraph.io.queryDisplayName(it) })
            }

            if (uris.size == 1) {
                val name = AppGraph.io.queryDisplayName(uris[0])
                if (FileSystemAccess.isArchiveFile(name)) return OpenTarget.Archive(uris[0])
            }

            OpenTarget.Shared(uris, mime)
        }

        else -> OpenTarget.None
    }
}

suspend fun Context.toViewableUri(uri: Uri, displayName: String = "image"): Uri = withContext(Dispatchers.IO) {
    when (uri.scheme) {
        "root", "shizuku" -> {
            val out = File(
                cacheDir,
                "img_${System.currentTimeMillis()}_${displayName.ifBlank { "image" }}"
            )
            AppGraph.io.openIn(uri).use { input -> out.outputStream().use { input.copyTo(it) } }
            runCatching { FileProvider.getUriForFile(this@toViewableUri, "$packageName.fileprovider", out) }
                .getOrElse { Uri.fromFile(out) }
        }
        else -> uri
    }
}

suspend fun Context.toViewableUris(items: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
    items.map { u -> toViewableUri(u, AppGraph.io.queryDisplayName(u)) }
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
            runCatching { FileProvider.getUriForFile(this@exportForOpenWith, "$packageName.fileprovider", f) }
                .getOrElse { Uri.fromFile(f) }
        }
        "root", "shizuku" -> {
            val safeName = displayName.ifBlank { "item" }
            val out = File(cacheDir, "export_${System.currentTimeMillis()}_$safeName")
            AppGraph.io.openIn(src).use { `in` -> out.outputStream().use { `in`.copyTo(it) } }
            runCatching { FileProvider.getUriForFile(this@exportForOpenWith, "$packageName.fileprovider", out) }
                .getOrElse { Uri.fromFile(out) }
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