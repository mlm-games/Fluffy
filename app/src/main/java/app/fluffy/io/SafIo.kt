package app.fluffy.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SafIo(val context: Context) {

    private val cr: ContentResolver get() = context.contentResolver
    private val fileSystemAccess = FileSystemAccess(context)

    private fun isRoot(uri: Uri) = uri.scheme == "root"
    private fun isShizuku(uri: Uri) = uri.scheme == "shizuku"
    private fun path(uri: Uri): String = uri.path ?: "/"

    // SAF (content tree) and local file listing only. Root/Shizuku are listed via listShell().
    fun listChildren(dir: Uri): List<DocumentFile> {
        return when {
            dir.scheme == "content" -> {
                val doc = DocumentFile.fromTreeUri(context, dir) ?: return emptyList()
                val children = doc.listFiles().toList()
                children.sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() ?: "" })
            }
            dir.scheme == "file" -> {
                val file = File(dir.path!!)
                val df = DocumentFile.fromFile(file)
                df.listFiles().sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() ?: "" })
            }
            else -> emptyList()
        }
    }

    fun listShell(dir: Uri): List<ShellEntry> {
        val base = path(dir).ifBlank { "/" }
        val pairs = when {
            isRoot(dir) -> ShellIo.listRoot(base)
            isShizuku(dir) -> ShellIo.listShizuku(base)
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
            isRoot(uri) -> ShellIo.openInRoot(path(uri))
            isShizuku(uri) -> ShellIo.openInShizuku(path(uri))
            uri.scheme == "file" -> {
                val file = File(uri.path!!)
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
            isRoot(uri) -> ShellIo.openOutRoot(path(uri))
            isShizuku(uri) -> ShellIo.openOutShizuku(path(uri))
            uri.scheme == "file" -> {
                val file = File(uri.path!!)
                FileOutputStream(file)
            }
            uri.scheme == "content" -> {
                requireNotNull(cr.openOutputStream(uri, "w")) { "openOutputStream null $uri" }
            }
            else -> throw IOException("Unsupported URI scheme: ${uri.scheme}")
        }
    }

    fun createDir(parent: Uri, name: String): Uri {
        return when {
            isRoot(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val p = if (base == "/") "/$name" else if (base.endsWith("/")) base + name else "$base/$name"
                ShellIo.mkdirsRoot(p)
                Uri.Builder().scheme("root").path(p).build()
            }
            isShizuku(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val p = if (base == "/") "/$name" else if (base.endsWith("/")) base + name else "$base/$name"
                ShellIo.mkdirsShizuku(p)
                Uri.Builder().scheme("shizuku").path(p).build()
            }
            parent.scheme == "file" -> {
                val parentFile = File(parent.path!!)
                val newDir = File(parentFile, name)
                newDir.mkdirs()
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
        return when {
            isRoot(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val p = if (base == "/") "/$name" else if (base.endsWith("/")) base + name else "$base/$name"
                if (overwrite) runCatching { ShellIo.deleteRoot(p) }
                ShellIo.openOutRoot(p).use { /* create */ }
                Uri.Builder().scheme("root").path(p).build()
            }
            isShizuku(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val p = if (base == "/") "/$name" else if (base.endsWith("/")) base + name else "$base/$name"
                if (overwrite) runCatching { ShellIo.deleteShizuku(p) }
                ShellIo.openOutShizuku(p).use { /* create */ }
                Uri.Builder().scheme("shizuku").path(p).build()
            }
            parent.scheme == "file" -> {
                val parentFile = File(parent.path!!)
                val newFile = File(parentFile, name)
                if (overwrite && newFile.exists()) {
                    runCatching { newFile.delete() }
                }
                if (!newFile.exists()) {
                    newFile.createNewFile()
                }
                Uri.fromFile(newFile)
            }
            parent.scheme == "content" -> {
                val p = DocumentFile.fromTreeUri(context, parent)
                    ?: DocumentFile.fromSingleUri(context, parent)
                    ?: error("Invalid parent")
                val existing = p.findFile(name)
                if (existing != null) {
                    if (overwrite) {
                        runCatching { existing.delete() }
                    } else {
                        return requireNotNull(p.createFile(mime, name)?.uri) { "Failed to create file" }
                    }
                }
                requireNotNull(p.createFile(mime, name)?.uri) { "Failed to create file" }
            }
            else -> throw IOException("Unsupported URI scheme: ${parent.scheme}")
        }
    }

    fun ensureDir(parent: Uri, relativePath: String): Uri {
        return when {
            isRoot(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val clean = relativePath.trim('/').replace("//", "/")
                val p = if (clean.isBlank()) base
                else if (base == "/") "/$clean"
                else if (base.endsWith("/")) base + clean
                else "$base/$clean"
                ShellIo.mkdirsRoot(p)
                Uri.Builder().scheme("root").path(p).build()
            }
            isShizuku(parent) -> {
                val base = path(parent).ifBlank { "/" }
                val clean = relativePath.trim('/').replace("//", "/")
                val p = if (clean.isBlank()) base
                else if (base == "/") "/$clean"
                else if (base.endsWith("/")) base + clean
                else "$base/$clean"
                ShellIo.mkdirsShizuku(p)
                Uri.Builder().scheme("shizuku").path(p).build()
            }
            parent.scheme == "file" -> {
                val parentFile = File(parent.path!!)
                val targetDir = File(parentFile, relativePath)
                targetDir.mkdirs()
                Uri.fromFile(targetDir)
            }
            parent.scheme == "content" -> {
                var current = DocumentFile.fromTreeUri(context, parent)
                    ?: DocumentFile.fromSingleUri(context, parent)
                    ?: error("Invalid parent")
                for (seg in relativePath.split('/').filter { it.isNotBlank() }) {
                    current = current.findFile(seg) ?: current.createDirectory(seg)!!
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
        val target = createFile(parent, name, overwrite = overwrite)
        when (target.scheme) {
            "file" -> {
                val file = File(target.path!!)
                FileOutputStream(file).use { out ->
                    input().use { `in` -> copyWithProgress(`in`, out, onProgress) }
                }
            }
            "content", "root", "shizuku" -> {
                openOut(target).use { out ->
                    input().use { `in` -> copyWithProgress(`in`, out, onProgress) }
                }
            }
            else -> throw IOException("Unsupported URI scheme")
        }
        target
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
        val f = File(base, name)
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
            isRoot(uri) -> ShellIo.deleteRoot(path(uri))
            isShizuku(uri) -> ShellIo.deleteShizuku(path(uri))
            uri.scheme == "file" -> File(uri.path!!).deleteRecursively()
            uri.scheme == "content" -> docFileFromUri(uri)?.delete() == true
            else -> false
        }
    }

    fun rename(uri: Uri, newName: String): Boolean {
        return when {
            isRoot(uri) -> {
                val f = File(path(uri))
                val newPath = (f.parent ?: "/") + "/" + newName
                ShellIo.renameRoot(f.path, newPath)
            }
            isShizuku(uri) -> {
                val f = File(path(uri))
                val newPath = (f.parent ?: "/") + "/" + newName
                ShellIo.renameShizuku(f.path, newPath)
            }
            uri.scheme == "file" -> {
                val file = File(uri.path!!)
                val newFile = File(file.parentFile, newName)
                file.renameTo(newFile)
            }
            uri.scheme == "content" -> {
                try {
                    DocumentsContract.renameDocument(cr, uri, newName) != null
                } catch (_: Exception) {
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
                    val srcFile = File(srcUri.path!!)
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

    private fun copyFileToTargetRecursive(src: File, targetParent: Uri, overwrite: Boolean) {
        val name = src.name
        if (src.isDirectory) {
            val newParent = ensureDir(targetParent, name)
            src.listFiles()?.forEach { child ->
                copyFileToTargetRecursive(child, newParent, overwrite)
            }
        } else {
            val mime = FileSystemAccess.getMimeType(name)
            if (overwrite) deleteChildIfExists(targetParent, name)
            val target = createFile(targetParent, name, mime, overwrite = overwrite)
            FileInputStream(src).use { input ->
                openOut(target).use { out -> input.copyTo(out) }
            }
        }
    }

    private fun copyDocRecursive(src: DocumentFile, targetParent: Uri, overwrite: Boolean) {
        val name = src.name ?: "item"
        if (src.isDirectory) {
            val newDir = ensureDir(targetParent, name)
            src.listFiles().forEach { child ->
                copyDocRecursive(child, newDir, overwrite)
            }
        } else {
            val mime = src.type ?: "application/octet-stream"
            if (overwrite) deleteChildIfExists(targetParent, name)
            val target = createFile(targetParent, name, mime, overwrite = overwrite)
            openIn(src.uri).use { input ->
                openOut(target).use { out -> input.copyTo(out) }
            }
        }
    }

    private fun copyAnyRecursive(srcUri: Uri, targetParent: Uri, overwrite: Boolean) {
        val srcPath = path(srcUri)
        val isDir = when {
            isRoot(srcUri) -> ShellIo.listRoot(srcPath).isNotEmpty() && !File(srcPath).isFile
            isShizuku(srcUri) -> ShellIo.listShizuku(srcPath).isNotEmpty() && !File(srcPath).isFile
            srcUri.scheme == "file" -> File(srcUri.path!!).isDirectory
            srcUri.scheme == "content" -> docFileFromUri(srcUri)?.isDirectory == true
            else -> false
        }

        fun join(base: String, name: String) =
            if (base == "/") "/$name" else if (base.endsWith("/")) base + name else "$base/$name"

        if (isDir) {
            val name = queryDisplayName(srcUri)
            val newParent = ensureDir(targetParent, name)
            val children: List<Pair<Uri, Boolean>> = when {
                isRoot(srcUri) -> ShellIo.listRoot(srcPath).map { (n, d) ->
                    val child = Uri.Builder().scheme("root").path(join(srcPath, n)).build()
                    child to d
                }
                isShizuku(srcUri) -> ShellIo.listShizuku(srcPath).map { (n, d) ->
                    val child = Uri.Builder().scheme("shizuku").path(join(srcPath, n)).build()
                    child to d
                }
                srcUri.scheme == "file" -> File(srcUri.path!!).listFiles().orEmpty().map { Uri.fromFile(it) to it.isDirectory }
                else -> docFileFromUri(srcUri)?.listFiles().orEmpty().map { it.uri to it.isDirectory }
            }
            children.forEach { (child, _) -> copyAnyRecursive(child, newParent, overwrite) }
        } else {
            val name = queryDisplayName(srcUri)
            if (overwrite) deleteChildIfExists(targetParent, name)
            val target = createFile(targetParent, name, overwrite = overwrite)
            openIn(srcUri).use { `in` -> openOut(target).use { out -> `in`.copyTo(out) } }
        }
    }

    suspend fun moveIntoDir(srcUri: Uri, targetParent: Uri, overwrite: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val ok = copyIntoDir(srcUri, targetParent, overwrite)
            if (ok) deleteTree(srcUri) else false
        }

    suspend fun deleteTree(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        when {
            isRoot(uri) -> ShellIo.deleteRoot(path(uri))
            isShizuku(uri) -> ShellIo.deleteShizuku(path(uri))
            uri.scheme == "file" -> File(uri.path!!).deleteRecursively()
            uri.scheme == "content" -> {
                val doc = docFileFromUri(uri) ?: return@withContext false
                deleteDocRecursive(doc)
                true
            }
            else -> false
        }
    }

    private fun deleteDocRecursive(doc: DocumentFile) {
        if (doc.isDirectory) {
            doc.listFiles().forEach { child -> deleteDocRecursive(child) }
        }
        doc.delete()
    }

    private fun deleteChildIfExists(parent: Uri, name: String) {
        when {
            isRoot(parent) -> {
                val p = (if (path(parent).endsWith("/")) path(parent) else path(parent) + "/") + name
                runCatching { ShellIo.deleteRoot(p) }
            }
            isShizuku(parent) -> {
                val p = (if (path(parent).endsWith("/")) path(parent) else path(parent) + "/") + name
                runCatching { ShellIo.deleteShizuku(p) }
            }
            parent.scheme == "file" -> {
                val pf = File(parent.path!!)
                val f = File(pf, name)
                if (f.exists()) runCatching { f.deleteRecursively() }
            }
            parent.scheme == "content" -> {
                val p = DocumentFile.fromTreeUri(context, parent)
                    ?: DocumentFile.fromSingleUri(context, parent)
                    ?: return
                val existing = p.findFile(name)
                if (existing != null) {
                    runCatching { existing.delete() }
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_BUF = 128 * 1024
    }
}