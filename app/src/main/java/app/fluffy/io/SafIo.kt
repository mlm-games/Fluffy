package app.fluffy.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.fluffy.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SafIo(
    val context: Context,
    private val fileSystemAccess: FileSystemAccess,
    private val shellIo: ShellIo,
) {

    private val cr: ContentResolver get() = context.contentResolver

    private fun isRoot(uri: Uri) = uri.scheme == "root"
    private fun isShizuku(uri: Uri) = uri.scheme == "shizuku"
    private fun path(uri: Uri): String = uri.path ?: "/"

    private fun requirePath(uri: Uri): String =
        requireNotNull(uri.path) { "Missing path for URI: $uri" }

    private fun validateSingleName(name: String) {
        require(name.isNotBlank()) { "Empty name" }
        require(!name.contains('/')) { "Name must not contain '/': $name" }
        require(name != "." && name != "..") { "Invalid name: $name" }
        require('\u0000' !in name) { "Invalid name" }
    }

    private fun resolveSafe(base: String, relativePath: String): String {
        val parts = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty() || relativePath.isBlank()) { "Invalid path" }
        require(parts.none { it == "." || it == ".." }) { "Path traversal rejected: $relativePath" }
        require(parts.all { '\u0000' !in it }) { "Invalid path" }
        val clean = parts.joinToString("/")
        if (clean.isBlank()) return base
        return if (base == "/") "/$clean" else if (base.endsWith("/")) base + clean else "$base/$clean"
    }

    private fun isSymlink(f: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(f.toPath())
    } catch (_: Exception) { false }

    private fun isRemoteDir(uri: Uri, srcPath: String): Boolean = when {
        isRoot(uri) -> shellIo.isDirRoot(srcPath)
        isShizuku(uri) -> shellIo.isDirShizuku(srcPath)
        uri.scheme == "file" -> File(requirePath(uri)).isDirectory
        uri.scheme == "content" -> docFileFromUri(uri)?.isDirectory == true
        else -> false
    }

    fun listChildren(dir: Uri): List<DocumentFile> {
        return when (dir.scheme) {
            "content" -> {
                val doc = DocumentFile.fromTreeUri(context, dir) ?: return emptyList()
                val children = doc.listFiles().toList()
                children.sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() ?: "" })
            }
            "file" -> {
                val file = File(requirePath(dir))
                val df = DocumentFile.fromFile(file)
                df.listFiles().sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() ?: "" })
            }
            else -> emptyList()
        }
    }

    fun listShell(dir: Uri): List<ShellEntry> {
        val base = path(dir).ifBlank { "/" }
        val pairs = when {
            isRoot(dir) -> shellIo.listRoot(base)
            isShizuku(dir) -> shellIo.listShizuku(base)
            else -> emptyList()
        }

        fun join(base: String, name: String): String =
            if (base == "/") "/$name" else if (base.endsWith("/")) base + name else "$base/$name"

        return pairs.map { (name, isDir) ->
            val full = join(base, name)
            val scheme = if (isRoot(dir)) "root" else "shizuku"
            val childUri = Uri.Builder().scheme(scheme).path(full).build()
            ShellEntry(name = name, isDir = isDir, uri = childUri)
        }.sortedWith(compareBy<ShellEntry> { !it.isDir }.thenBy { it.name.lowercase() })
    }

    fun listFiles(dir: File): List<File> {
        if (!fileSystemAccess.hasStoragePermission()) return emptyList()
        val files = dir.listFiles()?.toList() ?: emptyList()
        return files.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun openIn(uri: Uri): InputStream {
        return when {
            isRoot(uri) -> shellIo.openInRoot(path(uri))
            isShizuku(uri) -> shellIo.openInShizuku(path(uri))
            uri.scheme == "file" -> {
                val file = File(requirePath(uri))
                FileInputStream(file)
            }
            uri.scheme == "content" -> {
                val df = docFileFromUri(uri)
                if (df?.isDirectory == true) {
                    throw IOException("Cannot open directory as InputStream: $uri")
                }
                requireNotNull(cr.openInputStream(uri)) { "openInputStream null $uri" }
            }
            else -> throw IOException("Unsupported URI scheme: ${uri.scheme}")
        }
    }

    fun openOut(uri: Uri): OutputStream {
        return when {
            isRoot(uri) -> shellIo.openOutRoot(path(uri))
            isShizuku(uri) -> shellIo.openOutShizuku(path(uri))
            uri.scheme == "file" -> {
                val file = File(requirePath(uri))
                FileOutputStream(file)
            }
            uri.scheme == "content" -> {
                requireNotNull(cr.openOutputStream(uri, "w")) { "openOutputStream null $uri" }
            }
            else -> throw IOException("Unsupported URI scheme: ${uri.scheme}")
        }
    }

    fun writeText(uri: Uri, text: String) {
        openOut(uri).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    fun createDir(parent: Uri, name: String): Uri {
        validateSingleName(name)
        return when {
            isRoot(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val p = resolveSafe(base, name)
                if (!shellIo.mkdirsRoot(p)) throw IOException("mkdir failed: $p")
                Uri.Builder().scheme("root").path(p).build()
            }
            isShizuku(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val p = resolveSafe(base, name)
                if (!shellIo.mkdirsShizuku(p)) throw IOException("mkdir failed: $p")
                Uri.Builder().scheme("shizuku").path(p).build()
            }
            parent.scheme == "file" -> {
                val parentFile = File(requirePath(parent))
                val newDir = File(parentFile, name)
                if (!newDir.exists() && !newDir.mkdirs()) throw IOException("mkdir failed: $newDir")
                Uri.fromFile(newDir)
            }
            parent.scheme == "content" -> {
                val p = DocumentFile.fromTreeUri(context, parent)
                    ?: DocumentFile.fromSingleUri(context, parent)
                    ?: error("Invalid parent")
                val existing = p.findFile(name)
                if (existing != null && existing.isDirectory) {
                    existing.uri
                } else {
                    requireNotNull(p.createDirectory(name)?.uri) { "Failed to create dir" }
                }
            }
            else -> throw IOException("Unsupported URI scheme: ${parent.scheme}")
        }
    }

    fun createFile(
        parent: Uri,
        name: String,
        mime: String = "application/octet-stream",
        overwrite: Boolean = false
    ): Uri {
        validateSingleName(name)
        return when {
            isRoot(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val p = resolveSafe(base, name)
                if (overwrite) runCatching { shellIo.deleteRoot(p) }
                shellIo.openOutRoot(p).use { /* create */ }
                Uri.Builder().scheme("root").path(p).build()
            }
            isShizuku(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val p = resolveSafe(base, name)
                if (overwrite) runCatching { shellIo.deleteShizuku(p) }
                shellIo.openOutShizuku(p).use { /* create */ }
                Uri.Builder().scheme("shizuku").path(p).build()
            }
            parent.scheme == "file" -> {
                val parentFile = File(requirePath(parent))
                val newFile = File(parentFile, name)
                if (newFile.exists() && !overwrite) {
                    return Uri.fromFile(newFile)
                }
                if (overwrite && newFile.exists()) {
                    if (!newFile.delete()) throw IOException("Cannot overwrite: $newFile")
                }
                if (!newFile.exists()) {
                    if (!newFile.createNewFile()) throw IOException("Create failed: $newFile")
                }
                Uri.fromFile(newFile)
            }
            parent.scheme == "content" -> {
                val p = DocumentFile.fromTreeUri(context, parent)
                    ?: DocumentFile.fromSingleUri(context, parent)
                    ?: error("Invalid parent")
                val existing = p.findFile(name)
                if (existing != null) {
                    return if (overwrite) {
                        if (!existing.delete()) throw IOException("Cannot overwrite $name")
                        requireNotNull(p.createFile(mime, name)?.uri) { "Failed to recreate file" }
                    } else {
                        existing.uri
                    }
                }
                requireNotNull(p.createFile(mime, name)?.uri) { "Failed to create file" }
            }
            else -> throw IOException("Unsupported URI scheme: ${parent.scheme}")
        }
    }

    fun exists(uri: Uri): Boolean = when {
        isRoot(uri) -> runCatching { shellIo.isDirRoot(path(uri)) || shellIo.isFileRoot(path(uri)) }.getOrDefault(false)
        isShizuku(uri) -> runCatching { shellIo.isDirShizuku(path(uri)) || shellIo.isFileShizuku(path(uri)) }.getOrDefault(false)
        uri.scheme == "file" -> runCatching { File(requirePath(uri)).exists() }.getOrDefault(false)
        uri.scheme == "content" -> docFileFromUri(uri)?.exists() == true
        else -> false
    }

    fun ensureDir(parent: Uri, relativePath: String): Uri {
        return when {
            isRoot(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val p = resolveSafe(base, relativePath)
                if (!shellIo.mkdirsRoot(p)) throw IOException("mkdir failed: $p")
                Uri.Builder().scheme("root").path(p).build()
            }
            isShizuku(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val p = resolveSafe(base, relativePath)
                if (!shellIo.mkdirsShizuku(p)) throw IOException("mkdir failed: $p")
                Uri.Builder().scheme("shizuku").path(p).build()
            }
            parent.scheme == "file" -> {
                val parentFile = File(requirePath(parent))
                val baseCanon = runCatching { parentFile.canonicalPath }.getOrNull() ?: parentFile.absolutePath
                val targetDir = File(parentFile, relativePath)
                val targetCanon = runCatching { targetDir.canonicalPath }.getOrNull()
                    ?: throw IOException("Invalid path: $relativePath")
                if (targetCanon != baseCanon && !targetCanon.startsWith("$baseCanon/")) {
                    throw IOException("Path traversal rejected: $relativePath")
                }
                if (!targetDir.exists() && !targetDir.mkdirs()) throw IOException("mkdir failed")
                Uri.fromFile(targetDir)
            }
            parent.scheme == "content" -> {
                var current = DocumentFile.fromTreeUri(context, parent)
                    ?: DocumentFile.fromSingleUri(context, parent)
                    ?: error("Invalid parent")
                for (seg in relativePath.split('/').filter { it.isNotBlank() }) {
                    if (seg == "." ) continue
                    if (seg == ".." || '\u0000' in seg || '/' in seg) throw IOException("Invalid segment: $seg")
                    val found = current.findFile(seg)
                    current = when {
                        found == null -> requireNotNull(current.createDirectory(seg)) { "mkdir failed: $seg" }
                        found.isDirectory -> found
                        else -> throw IOException("Not a directory: $seg")
                    }
                }
                current.uri
            }
            else -> throw IOException("Unsupported URI scheme: ${parent.scheme}")
        }
    }

    suspend fun copyTo(
        parent: Uri,
        name: String,
        input: () -> InputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        overwrite: Boolean = false
    ): Uri = withContext(Dispatchers.IO) {
        validateSingleName(name)
        if (!overwrite && childExists(parent, name)) throw IOException("Exists: $name")
        val target = createFile(parent, name, overwrite = overwrite)
        when (target.scheme) {
            "file" -> {
                val file = File(requirePath(target))
                val tmp = File(file.parentFile, "${file.name}.tmp.${System.nanoTime()}")
                try {
                    tmp.outputStream().use { out ->
                        input().use { `in` -> copyWithProgress(`in`, out, onProgress) }
                    }
                    if (file.exists() && !file.delete()) throw IOException("Cannot replace $file")
                    if (!tmp.renameTo(file)) {
                        tmp.inputStream().use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                        tmp.delete()
                    }
                } catch (e: Exception) {
                    runCatching { tmp.delete() }
                    throw e
                }
            }
            "content", "root", "shizuku" -> {
                try {
                    openOut(target).use { out ->
                        input().use { `in` -> copyWithProgress(`in`, out, onProgress) }
                    }
                } catch (e: Exception) {
                    runCatching { delete(target) }
                    throw e
                }
            }
            else -> throw IOException("Unsupported URI scheme")
        }
        target
    }

    fun childExists(parent: Uri, name: String): Boolean {
        return when {
            isRoot(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val p = runCatching { resolveSafe(base, name) }.getOrNull() ?: return false
                shellIo.isDirRoot(p) || shellIo.isFileRoot(p)
            }
            isShizuku(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val p = runCatching { resolveSafe(base, name) }.getOrNull() ?: return false
                shellIo.isDirShizuku(p) || shellIo.isFileShizuku(p)
            }
            parent.scheme == "file" -> runCatching {
                File(File(requirePath(parent)), name).exists()
            }.getOrDefault(false)
            parent.scheme == "content" -> {
                val dir = docFileFromUri(parent)
                    ?: DocumentFile.fromTreeUri(context, parent)
                    ?: return false
                if (dir.isDirectory) dir.findFile(name) != null
                else {
                    val tree = DocumentFile.fromTreeUri(context, parent) ?: return false
                    tree.findFile(name) != null
                }
            }
            else -> false
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        onProgress: (Long, Long) -> Unit
    ) {
        val buf = ByteArray(DEFAULT_BUF)
        var total = 0L
        var read = input.read(buf)
        while (read != -1) {
            output.write(buf, 0, read)
            total += read
            onProgress(total, -1L)
            read = input.read(buf)
        }
    }

    fun stageToTemp(name: String, input: () -> InputStream): File {
        val base = File(context.cacheDir, "stage").apply { mkdirs() }
        val safeName = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(64).ifBlank { "file" }
        val f = File.createTempFile("stage_${System.currentTimeMillis()}_", "_$safeName", base)
        input().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    fun queryDisplayName(uri: Uri): String {
        return when {
            isRoot(uri) || isShizuku(uri) -> File(path(uri)).name.ifBlank { "item" }
            uri.scheme == "file" -> File(uri.path!!).name
            uri.scheme == "content" -> {
                val doc = DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)
                doc?.name ?: "item"
            }
            else -> "item"
        }
    }

    fun docFileFromUri(uri: Uri): DocumentFile? {
        return when (uri.scheme) {
            "file" -> DocumentFile.fromFile(File(requireNotNull(uri.path)))
            "content" -> DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)
            else -> null // root/shizuku: no DocumentFile wrapper
        }
    }

    fun delete(uri: Uri): Boolean {
        return when {
            isRoot(uri) -> shellIo.deleteRoot(path(uri))
            isShizuku(uri) -> shellIo.deleteShizuku(path(uri))
            uri.scheme == "file" -> {
                val f = File(requirePath(uri))
                if (isSymlink(f)) f.delete()
                else f.deleteRecursively()
            }
            uri.scheme == "content" -> docFileFromUri(uri)?.delete() == true
            else -> false
        }
    }

    fun rename(uri: Uri, newName: String): Boolean {
        validateSingleName(newName)
        return when {
            isRoot(uri) -> {
                val f = File(path(uri))
                val parent = f.parent ?: "/"
                val newPath = if (parent == "/") "/$newName" else "$parent/$newName"
                shellIo.renameRoot(f.path, newPath)
            }
            isShizuku(uri) -> {
                val f = File(path(uri))
                val parent = f.parent ?: "/"
                val newPath = if (parent == "/") "/$newName" else "$parent/$newName"
                shellIo.renameShizuku(f.path, newPath)
            }
            uri.scheme == "file" -> {
                val file = File(requirePath(uri))
                val newFile = File(file.parentFile, newName)
                if (newFile.exists()) return false
                file.renameTo(newFile)
            }
            uri.scheme == "content" -> {
                try {
                    DocumentsContract.renameDocument(cr, uri, newName) != null
                } catch (e: Exception) {
                    AppLog.w("SafIo", "renameDocument failed: $uri", e)
                    false
                }
            }
            else -> false
        }
    }

    suspend fun copyIntoDir(srcUri: Uri, targetParent: Uri, overwrite: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            when {
                isRoot(targetParent) || isShizuku(targetParent) -> {
                    copyAnyRecursive(srcUri, targetParent, overwrite)
                    true
                }
                srcUri.scheme == "file" -> {
                    val srcFile = File(requirePath(srcUri))
                    if (isSymlink(srcFile) && srcFile.isDirectory) throw IOException("Refusing symlink dir")
                    copyFileToTargetRecursive(srcFile, targetParent, overwrite)
                    true
                }
                srcUri.scheme == "content" -> {
                    val src = docFileFromUri(srcUri) ?: return@withContext false
                    copyDocRecursive(src, targetParent, overwrite)
                    true
                }
                isRoot(srcUri) || isShizuku(srcUri) -> {
                    copyAnyRecursive(srcUri, targetParent, overwrite)
                    true
                }
                else -> false
            }
        }

    private fun copyFileToTargetRecursive(src: File, targetParent: Uri, overwrite: Boolean, depth: Int = 0) {
        if (depth > 64) throw IOException("Max depth exceeded (symlink cycle?): $src")
        val name = src.name
        if (isSymlink(src)) return // skip symlinks; never follow
        if (src.isDirectory) {
            val newParent = ensureDir(targetParent, name)
            src.listFiles()?.forEach { child ->
                copyFileToTargetRecursive(child, newParent, overwrite, depth + 1)
            }
        } else {
            if (isSymlink(src)) return
            try { if (src.length() == 0L && !src.isFile) return } catch (_: Exception) {}
            val mime = FileSystemAccess.getMimeType(name)
            FileInputStream(src).use { input ->
                if (overwrite) deleteChildIfExists(targetParent, name)
                else if (childExists(targetParent, name)) throw IOException("Exists: $name")
                val target = createFile(targetParent, name, mime, overwrite = overwrite)
                try {
                    openOut(target).use { out -> input.copyTo(out) }
                } catch (e: Exception) {
                    runCatching { delete(target) }
                    throw e
                }
            }
        }
    }

    private fun copyDocRecursive(src: DocumentFile, targetParent: Uri, overwrite: Boolean, depth: Int = 0) {
        if (depth > 64) throw IOException("Max depth exceeded")
        val name = src.name ?: "item"
        if (src.isDirectory) {
            val newDir = ensureDir(targetParent, name)
            src.listFiles().forEach { child ->
                copyDocRecursive(child, newDir, overwrite, depth + 1)
            }
        } else {
            val mime = src.type ?: "application/octet-stream"
            openIn(src.uri).use { input ->
                if (overwrite) deleteChildIfExists(targetParent, name)
                else if (childExists(targetParent, name)) throw IOException("Exists: $name")
                val target = createFile(targetParent, name, mime, overwrite = overwrite)
                try {
                    openOut(target).use { out -> input.copyTo(out) }
                } catch (e: Exception) {
                    runCatching { delete(target) }
                    throw e
                }
            }
        }
    }

    private fun copyAnyRecursive(srcUri: Uri, targetParent: Uri, overwrite: Boolean, depth: Int = 0) {
        if (depth > 64) throw IOException("Max depth exceeded (symlink cycle?)")
        val srcPath = path(srcUri)
        val isDir = isRemoteDir(srcUri, srcPath)

        fun join(base: String, name: String) =
            if (base == "/") "/$name" else if (base.endsWith("/")) base + name else "$base/$name"

        if (isDir) {
            val name = queryDisplayName(srcUri)
            val newParent = ensureDir(targetParent, name)
            val children: List<Pair<Uri, Boolean>> = when {
                isRoot(srcUri) -> shellIo.listRoot(srcPath).map { (n, d) ->
                    val child = Uri.Builder().scheme("root").path(join(srcPath, n)).build()
                    child to d
                }
                isShizuku(srcUri) -> shellIo.listShizuku(srcPath).map { (n, d) ->
                    val child = Uri.Builder().scheme("shizuku").path(join(srcPath, n)).build()
                    child to d
                }
                srcUri.scheme == "file" -> File(requirePath(srcUri)).listFiles().orEmpty()
                    .filter { !isSymlink(it) }
                    .map { Uri.fromFile(it) to it.isDirectory }
                else -> docFileFromUri(srcUri)?.listFiles().orEmpty().map { it.uri to it.isDirectory }
            }
            children.forEach { (child, _) -> copyAnyRecursive(child, newParent, overwrite, depth + 1) }
        } else {
            val name = queryDisplayName(srcUri)
            openIn(srcUri).use { input ->
                if (overwrite) deleteChildIfExists(targetParent, name)
                else if (childExists(targetParent, name)) throw IOException("Exists: $name")
                val target = createFile(targetParent, name, overwrite = overwrite)
                try {
                    openOut(target).use { out -> input.copyTo(out) }
                } catch (e: Exception) {
                    runCatching { delete(target) }
                    throw e
                }
            }
        }
    }

    suspend fun moveIntoDir(srcUri: Uri, targetParent: Uri, overwrite: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val srcPath = path(srcUri)
            val targetPath = path(targetParent)
            val sameBackend = (isRoot(srcUri) && isRoot(targetParent)) ||
                (isShizuku(srcUri) && isShizuku(targetParent)) ||
                (srcUri.scheme == "file" && targetParent.scheme == "file") ||
                (srcUri.scheme == "content" && targetParent.scheme == "content")
            val isDir = isRemoteDir(srcUri, srcPath)
            val name = queryDisplayName(srcUri)
            if (sameBackend) {
                val normSrc = srcPath.removeSuffix("/")
                val normTarget = targetPath.removeSuffix("/")
                if (normSrc == normTarget || normTarget == "$normSrc/$name") return@withContext true
                if (isDir && (normTarget == normSrc || normTarget.startsWith("$normSrc/"))) {
                    return@withContext false
                }
            }

            val ok = copyIntoDir(srcUri, targetParent, overwrite)
            if (ok) deleteTree(srcUri) else false
        }

    suspend fun deleteTree(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        when {
            isRoot(uri) -> shellIo.deleteRoot(path(uri))
            isShizuku(uri) -> shellIo.deleteShizuku(path(uri))
            uri.scheme == "file" -> {
                val f = File(requirePath(uri))
                if (isSymlink(f)) f.delete() else f.deleteRecursively()
            }
            uri.scheme == "content" -> {
                val doc = docFileFromUri(uri) ?: return@withContext false
                deleteDocRecursive(doc)
            }
            else -> false
        }
    }

    private fun deleteDocRecursive(doc: DocumentFile): Boolean {
        var ok = true
        if (doc.isDirectory) {
            doc.listFiles().forEach { child -> if (!deleteDocRecursive(child)) ok = false }
        }
        if (!doc.delete()) ok = false
        return ok
    }

    private fun deleteChildIfExists(parent: Uri, name: String): Boolean {
        return try {
            when {
                isRoot(parent) -> {
                    val base = path(parent).ifBlank { "/" }
                    val p = resolveSafe(base, name)
                    shellIo.deleteRoot(p)
                }
                isShizuku(parent) -> {
                    val base = path(parent).ifBlank { "/" }
                    val p = resolveSafe(base, name)
                    shellIo.deleteShizuku(p)
                }
                parent.scheme == "file" -> {
                    val pf = File(requirePath(parent))
                    val f = File(pf, name)
                    if (!f.exists()) true
                    else if (isSymlink(f)) f.delete()
                    else f.deleteRecursively()
                }
                parent.scheme == "content" -> {
                    val p = DocumentFile.fromTreeUri(context, parent)
                        ?: DocumentFile.fromSingleUri(context, parent)
                        ?: return false
                    val existing = p.findFile(name) ?: return true
                    existing.delete()
                }
                else -> false
            }
        } catch (_: Exception) { false }
    }

    companion object {
        private const val DEFAULT_BUF = 128 * 1024
    }
}
