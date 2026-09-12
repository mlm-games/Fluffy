package app.fluffy.io

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

object DocumentController : KoinComponent {
    private val shellIo: ShellIo by inject()

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
                    val f = File(requireNotNull(uri.path) { "Missing path" })
                    if (f.length() > maxSize) throw IOException("File too large (${f.length()} > $maxSize)")
                    DocInfo(f.name, readCapped(f.inputStream(), maxSize), !f.canWrite())
                }
                "content" -> {
                    val size = runCatching {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                            if (it.moveToFirst()) it.getLong(0) else -1L
                        } ?: -1L
                    }.getOrDefault(-1L)
                    if (size > maxSize) throw IOException("File too large")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = readCapped(input, maxSize)
                        val name = queryName(context, uri) ?: "Untitled"
                        val readOnly = isContentReadOnly(context, uri)
                        DocInfo(name, bytes, readOnly)
                    } ?: throw IOException("Stream null")
                }
                "root" -> {
                    val path = uri.path ?: ""
                    val bytes = shellIo.readBytesRoot(path)
                    if (bytes.size > maxSize) throw IOException("File too large")
                    DocInfo(File(path).name, bytes, false)
                }
                "shizuku" -> {
                    val path = uri.path ?: ""
                    val bytes = shellIo.readBytesShizuku(path)
                    if (bytes.size > maxSize) throw IOException("File too large")
                    DocInfo(File(path).name, bytes, false)
                }
                else -> throw IOException("Unknown scheme: ${uri.scheme}")
            }
        }
    }

    fun readCapped(input: java.io.InputStream, maxSize: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            total += n
            if (total > maxSize + 1L) throw IOException("File too large")
            out.write(buf, 0, n)
        }
        if (total > maxSize) throw IOException("File too large")
        return out.toByteArray()
    }

    suspend fun save(context: Context, uri: Uri, content: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            when (uri.scheme) {
                "file" -> {
                    val f = File(requireNotNull(uri.path) { "Missing path" })
                    val parent = f.parentFile ?: throw IOException("No parent")
                    val tmp = File.createTempFile(".fluffy_", ".tmp", parent)
                    try {
                        java.io.FileOutputStream(tmp).use { fos ->
                            fos.write(content)
                            fos.fd.sync()
                        }
                        if (f.exists() && !f.delete()) throw IOException("Cannot replace file")
                        if (!tmp.renameTo(f)) {
                            f.writeBytes(content)
                        }
                    } finally {
                        runCatching { if (tmp.exists()) tmp.delete() }
                    }
                }
                "content" -> {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content) }
                        ?: throw IOException("OutputStream null")
                }
                "root" -> {
                    if (!shellIo.writeBytesRoot(uri.path ?: "", content)) throw IOException("Root write failed")
                }
                "shizuku" -> {
                    if (!shellIo.writeBytesShizuku(uri.path ?: "", content)) throw IOException("Shizuku write failed")
                }
                else -> throw IOException("Unknown scheme")
            }
        }
    }

    // Image Sibling Scanning (Left/Right swipe support)
    suspend fun listImageSiblings(uri: Uri): List<Uri> = withContext(Dispatchers.IO) {
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

                    val list = if(uri.scheme == "root") shellIo.listRoot(parent) else shellIo.listShizuku(parent)

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
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_FLAGS),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val flags = c.getLong(0)
                    (flags and android.provider.DocumentsContract.Document.FLAG_SUPPORTS_WRITE.toLong()) == 0L
                } else true
            } ?: true
        }.getOrDefault(true)
    }

    fun sniffCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return Charsets.UTF_8
        return Charsets.UTF_8
    }
}
