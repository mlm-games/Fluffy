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

    fun listChildren(dir: Uri): List<DocumentFile> {
        val doc = DocumentFile.fromTreeUri(context, dir) ?: return emptyList()
        val children = doc.listFiles().toList()
        return children.sortedWith(
            compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() ?: "" }
        )
    }

    fun listFiles(dir: File): List<File> {
        if (!fileSystemAccess.hasStoragePermission()) return emptyList()
        val files = dir.listFiles()?.toList() ?: emptyList()
        return files.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
        )
    }

    fun openIn(uri: Uri): InputStream {
        return when (uri.scheme) {
            "file" -> {
                val file = File(uri.path!!)
                FileInputStream(file)
            }
            "content" -> {
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
        return when (uri.scheme) {
            "file" -> {
                val file = File(uri.path!!)
                FileOutputStream(file)
            }
            "content" -> {
                requireNotNull(cr.openOutputStream(uri, "w")) { "openOutputStream null $uri" }
            }
            else -> throw IOException("Unsupported URI scheme: ${uri.scheme}")
        }
    }

    fun createDir(parent: Uri, name: String): Uri {
        return when (parent.scheme) {
            "file" -> {
                val parentFile = File(parent.path!!)
                val newDir = File(parentFile, name)
                newDir.mkdirs()
                Uri.fromFile(newDir)
            }
            "content" -> {
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

    /**
     * Create a file under [parent] with [name] and [mime].
     * If [overwrite] is true and a file with the same name exists, it will be deleted first.
     */
    fun createFile(
        parent: Uri,
        name: String,
        mime: String = "application/octet-stream",
        overwrite: Boolean = false
    ): Uri {
        return when (parent.scheme) {
            "file" -> {
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
            "content" -> {
                val p = DocumentFile.fromTreeUri(context, parent)
                    ?: DocumentFile.fromSingleUri(context, parent)
                    ?: error("Invalid parent")
                val existing = p.findFile(name)
                if (existing != null) {
                    if (overwrite) {
                        runCatching { existing.delete() }
                    } else {
                        // If not overwriting, let provider create a sibling (or fail)
                        return requireNotNull(p.createFile(mime, name)?.uri) { "Failed to create file" }
                    }
                }
                requireNotNull(p.createFile(mime, name)?.uri) { "Failed to create file" }
            }
            else -> throw IOException("Unsupported URI scheme: ${parent.scheme}")
        }
    }

    fun ensureDir(parent: Uri, relativePath: String): Uri {
        return when (parent.scheme) {
            "file" -> {
                val parentFile = File(parent.path!!)
                val targetDir = File(parentFile, relativePath)
                targetDir.mkdirs()
                Uri.fromFile(targetDir)
            }
            "content" -> {
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
                    input().use { `in` ->
                        copyWithProgress(`in`, out, onProgress)
                    }
                }
            }
            "content" -> {
                cr.openOutputStream(target, "w").use { out ->
                    input().use { `in` ->
                        copyWithProgress(`in`, out!!, onProgress)
                    }
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
        return when (uri.scheme) {
            "file" -> File(uri.path!!).name
            "content" -> {
                val doc = DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)
                doc?.name ?: "item"
            }
            else -> "item"
        }
    }

    fun docFileFromUri(uri: Uri): DocumentFile? {
        return when (uri.scheme) {
            "file" -> null
            "content" -> DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)
            else -> null
        }
    }

    fun delete(uri: Uri): Boolean {
        return when (uri.scheme) {
            "file" -> File(uri.path!!).deleteRecursively()
            "content" -> docFileFromUri(uri)?.delete() == true
            else -> false
        }
    }

    fun rename(uri: Uri, newName: String): Boolean {
        return when (uri.scheme) {
            "file" -> {
                val file = File(uri.path!!)
                val newFile = File(file.parentFile, newName)
                file.renameTo(newFile)
            }
            "content" -> {
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
            when (srcUri.scheme) {
                "file" -> {
                    val srcFile = File(srcUri.path!!)
                    copyFileToTargetRecursive(srcFile, targetParent, overwrite)
                    true
                }
                "content" -> {
                    val src = docFileFromUri(srcUri) ?: return@withContext false
                    copyDocRecursive(src, targetParent, overwrite)
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
            // If overwriting, delete existing before creating
            if (overwrite) {
                deleteChildIfExists(targetParent, name)
            }
            val target = createFile(targetParent, name, mime, overwrite = overwrite)
            FileInputStream(src).use { input ->
                openOut(target).use { out -> input.copyTo(out) }
            }
        }
    }

    suspend fun moveIntoDir(srcUri: Uri, targetParent: Uri, overwrite: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val ok = copyIntoDir(srcUri, targetParent, overwrite)
            if (ok) deleteTree(srcUri) else false
        }

    suspend fun deleteTree(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        when (uri.scheme) {
            "file" -> File(uri.path!!).deleteRecursively()
            "content" -> {
                val doc = docFileFromUri(uri) ?: return@withContext false
                deleteDocRecursive(doc)
                true
            }
            else -> false
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
            if (overwrite) {
                deleteChildIfExists(targetParent, name)
            }
            val target = createFile(targetParent, name, mime, overwrite = overwrite)
            openIn(src.uri).use { input ->
                openOut(target).use { out -> input.copyTo(out) }
            }
        }
    }

    private fun deleteDocRecursive(doc: DocumentFile) {
        if (doc.isDirectory) {
            doc.listFiles().forEach { child -> deleteDocRecursive(child) }
        }
        doc.delete()
    }

    private fun deleteChildIfExists(parent: Uri, name: String) {
        when (parent.scheme) {
            "file" -> {
                val pf = File(parent.path!!)
                val f = File(pf, name)
                if (f.exists()) runCatching { f.deleteRecursively() }
            }
            "content" -> {
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