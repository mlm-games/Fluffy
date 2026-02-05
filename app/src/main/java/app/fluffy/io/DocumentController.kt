package app.fluffy.io

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

object DocumentController {

    data class DocInfo(
        val name: String,
        val content: ByteArray,
        val isReadOnly: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DocInfo
            return name == other.name && isReadOnly == other.isReadOnly && content.contentEquals(other.content)
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + content.contentHashCode()
            result = 31 * result + isReadOnly.hashCode()
            return result
        }
    }

    // Unified Read (File, Content, Root, Shizuku)
    suspend fun read(context: Context, uri: Uri, maxSize: Int = 10 * 1024 * 1024): Result<DocInfo> = withContext(Dispatchers.IO) {
        runCatching {
            when (uri.scheme) {
                "file" -> {
                    val f = File(uri.path!!)
                    DocInfo(f.name, f.readBytes(), !f.canWrite())
                }
                "content" -> {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        if (input.available() > maxSize) throw IOException("File too large")
                        val bytes = input.readBytes()
                        val name = queryName(context, uri) ?: "Untitled"
                        val readOnly = isContentReadOnly(context, uri)
                        DocInfo(name, bytes, readOnly)
                    } ?: throw IOException("Stream null")
                }
                "root" -> {
                    val path = uri.path ?: ""
                    val bytes = ShellIo.readBytesRoot(path)
                    DocInfo(File(path).name, bytes, false)
                }
                "shizuku" -> {
                    val path = uri.path ?: ""
                    val bytes = ShellIo.readBytesShizuku(path)
                    DocInfo(File(path).name, bytes, false)
                }
                else -> throw IOException("Unknown scheme: ${uri.scheme}")
            }
        }
    }

    // Unified Write
    suspend fun save(context: Context, uri: Uri, content: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            when (uri.scheme) {
                "file" -> File(uri.path!!).writeBytes(content)
                "content" -> {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content) }
                        ?: throw IOException("OutputStream null")
                }
                "root" -> {
                    if (!ShellIo.writeBytesRoot(uri.path ?: "", content)) throw IOException("Root write failed")
                }
                "shizuku" -> {
                    if (!ShellIo.writeBytesShizuku(uri.path ?: "", content)) throw IOException("Shizuku write failed")
                }
                else -> throw IOException("Unknown scheme")
            }
        }
    }

    // Image Sibling Scanning (Left/Right swipe support)
    suspend fun listImageSiblings(context: Context, uri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val imageExts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")

        fun isImg(name: String) = name.substringAfterLast('.', "").lowercase() in imageExts

        try {
            when (uri.scheme) {
                "file" -> {
                    val f = File(uri.path ?: return@withContext listOf(uri))
                    f.parentFile?.listFiles()
                        ?.filter { isImg(it.name) }
                        ?.sortedBy { it.name.lowercase() }
                        ?.map { Uri.fromFile(it) }
                        ?: listOf(uri)
                }
                "root", "shizuku" -> {
                    val path = uri.path ?: return@withContext listOf(uri)
                    val parent = File(path).parent ?: return@withContext listOf(uri)

                    val list = if(uri.scheme == "root") ShellIo.listRoot(parent) else ShellIo.listShizuku(parent)

                    list.filter { (name, isDir) -> !isDir && isImg(name) }
                        .sortedBy { it.first.lowercase() }
                        .map { (name, _) ->
                            Uri.Builder().scheme(uri.scheme).path("$parent/$name").build()
                        }
                        .ifEmpty { listOf(uri) }
                }
                "content" -> {
                    listOf(uri)
                }
                else -> listOf(uri)
            }
        } catch (e: Exception) {
            listOf(uri)
        }
    }

    private fun queryName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    private fun isContentReadOnly(context: Context, uri: Uri): Boolean {
        // Check if we have write permission on the URI without truncating the file
        // We check the URI mode flags or try to open in append mode (which doesn't truncate)
        return runCatching {
            // Try to open in append mode - this won't truncate if file exists
            context.contentResolver.openOutputStream(uri, "wa")?.use {}
            false
        }.getOrDefault(true)
    }

    fun sniffCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return Charsets.UTF_8
        return Charsets.UTF_8
    }
}
