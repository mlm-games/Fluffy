package app.fluffy.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class SafIo(private val context: Context) {

    private val cr: ContentResolver get() = context.contentResolver

    fun listChildren(dir: Uri): List<DocumentFile> {
        val doc = DocumentFile.fromTreeUri(context, dir) ?: return emptyList()
        return doc.listFiles().sortedWith(
            compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() ?: "" }
        )
    }

    fun openIn(uri: Uri): InputStream = requireNotNull(cr.openInputStream(uri)) { "openInputStream null $uri" }
    fun openOut(uri: Uri): OutputStream = requireNotNull(cr.openOutputStream(uri)) { "openOutputStream null $uri" }

    fun createDir(parent: Uri, name: String): Uri {
        val p = DocumentFile.fromTreeUri(context, parent) ?: error("Invalid parent")
        return requireNotNull(p.createDirectory(name)?.uri) { "Failed to create dir" }
    }

    fun createFile(parent: Uri, name: String, mime: String = "application/octet-stream"): Uri {
        val p = DocumentFile.fromTreeUri(context, parent) ?: error("Invalid parent")
        return requireNotNull(p.createFile(mime, name)?.uri) { "Failed to create file" }
    }

    fun ensureDir(parent: Uri, relativePath: String): Uri {
        var current = DocumentFile.fromTreeUri(context, parent) ?: error("Invalid parent")
        for (seg in relativePath.split('/').filter { it.isNotBlank() }) {
            current = current.findFile(seg) ?: current.createDirectory(seg)!!
        }
        return current.uri
    }

    suspend fun copyTo(parent: Uri, name: String, input: () -> InputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }): Uri =
        withContext(Dispatchers.IO) {
            val target = createFile(parent, name)
            cr.openOutputStream(target).use { out ->
                input().use { `in` ->
                    val buf = ByteArray(DEFAULT_BUF)
                    var total = 0L
                    var read = `in`.read(buf)
                    while (read != -1) {
                        out?.write(buf, 0, read)
                        total += read
                        onProgress(total, -1L)
                        read = `in`.read(buf)
                    }
                }
            }
            target
        }

    fun stageToTemp(name: String, input: () -> InputStream): File {
        val base = File(context.cacheDir, "stage").apply { mkdirs() }
        val f = File(base, name)
        input().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    fun queryDisplayName(uri: Uri): String {
        val doc = DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)
        return doc?.name ?: "item"
    }

    fun docFileFromUri(uri: Uri): DocumentFile? =
        DocumentFile.fromSingleUri(context, uri) ?: DocumentFile.fromTreeUri(context, uri)

    fun delete(uri: Uri): Boolean = docFileFromUri(uri)?.delete() == true

    fun rename(uri: Uri, newName: String): Boolean {
        return try {
            DocumentsContract.renameDocument(cr, uri, newName) != null
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val DEFAULT_BUF = 128 * 1024
    }
}