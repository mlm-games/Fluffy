package app.fluffy.io

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.fluffy.R
import app.fluffy.util.AppLog
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
                    if (size > maxSize) throw IOException(context.getString(R.string.file_too_large_preview))
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = readCapped(input, maxSize)
                        val name = queryName(context, uri) ?: context.getString(R.string.untitled)
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

    suspend fun listImageSiblings(context: Context, uri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        val imageExts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "avif")

        fun isImg(name: String) = name.substringAfterLast('.', "").lowercase() in imageExts

        val found = try {
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

                    val list = if (uri.scheme == "root") shellIo.listRoot(parent) else shellIo.listShizuku(parent)

                    list.filter { (name, isDir) -> !isDir && isImg(name) }
                        .sortedBy { it.first.lowercase() }
                        .map { (name, _) ->
                            Uri.Builder().scheme(uri.scheme).path("$parent/$name").build()
                        }
                        .ifEmpty { listOf(uri) }
                }
                "content" -> listContentImages(context, uri, ::isImg)
                else -> listOf(uri)
            }
        } catch (e: Exception) {
            AppLog.w("DocumentController", "sibling listing failed for $uri", e)
            listOf(uri)
        }

        if (found.contains(uri)) found else listOf(uri) + found.filterNot { it == uri }
    }

    private fun listContentImages(context: Context, uri: Uri, isImageName: (String) -> Boolean): List<Uri> {
        val children = queryDocumentChildren(context, uri)
        if (children != null) {
            val images = children.filter { isImageName(it.name) }.sortedBy { it.name.lowercase() }.map { it.uri }
            if (images.size > 1) return images
        }

        directSiblings(uri, isImageName)?.let { if (it.size > 1) return it }

        val media = queryMediaStoreBucket(context, uri)
        if (media.size > 1) return media

        return listOf(uri)
    }

    private data class ChildDoc(val uri: Uri, val name: String, val mime: String?)

    private fun directSiblings(uri: Uri, isImageName: (String) -> Boolean): List<Uri>? = try {
        val docId = DocumentsContract.getDocumentId(uri)
        val relative = docId.substringAfter(':', "")
        if (relative.isEmpty() || relative.startsWith("/")) {
            null
        } else {
            val externalRoot = Environment.getExternalStorageDirectory()
            val target = File(externalRoot, relative)
            val parent = target.parentFile
            val entries = if (parent?.canRead() == true) parent.listFiles() else null
            entries
                ?.takeIf { target.exists() }
                ?.filter { it.isFile && isImageName(it.name) }
                ?.sortedBy { it.name.lowercase() }
                ?.map { if (it == target) uri else Uri.fromFile(it) }
                ?: return null
        }
    } catch (e: Exception) {
        AppLog.d("DocumentController", "direct sibling lookup failed for $uri", e)
        null
    }

    private fun String.parentDocumentId(): String? {
        if (endsWith('/')) return null
        val lastSlash = lastIndexOf('/')
        if (lastSlash < 0) {
            val volume = substringBefore(':')
            return volume.takeIf { it.isNotEmpty() && it != this }
        }
        val parent = substring(0, lastSlash)
        return parent.takeIf { it.isNotEmpty() && it != this }
    }

    /**
     * Returns null when the provider exposes no children (ex: a bare FileProvider).
     */
    private fun queryDocumentChildren(context: Context, uri: Uri): List<ChildDoc>? = try {
        val authority = uri.authority ?: return null
        val docId = DocumentsContract.getDocumentId(uri)
        val parentId = docId.parentDocumentId()
        if (parentId == null) {
            null
        } else {
            val treeUri = DocumentsContract.buildTreeDocumentUri(authority, parentId)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            val docs = ArrayList<ChildDoc>()
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idIdx))
                    docs.add(ChildDoc(childUri, c.getString(nameIdx).orEmpty(), c.getString(mimeIdx)))
                }
            }
            docs
        }
    } catch (e: Exception) {
        AppLog.e("DocumentController", "child document query unavailable for $uri", e)
        null
    }

    private fun queryMediaStoreBucket(context: Context, uri: Uri): List<Uri> {
        if (!uri.authority.orEmpty().startsWith("media")) return emptyList()

        return try {
            val bucketId = context.contentResolver.query(
                uri, arrayOf(MediaStore.Images.Media.BUCKET_ID), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return emptyList()

            val images = mutableListOf<Uri>()
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.BUCKET_ID} = ?",
                arrayOf(bucketId),
                null
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (c.moveToNext()) {
                    images.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idx)))
                }
            }
            images
        } catch (e: Exception) {
            AppLog.d("DocumentController", "media store bucket query failed for $uri", e)
            emptyList()
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
        if (uri.authority == "${context.packageName}.fileprovider") {
            return true
        }
        val supportsWriteByFlags: Boolean? = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_FLAGS),
                null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) return@runCatching null
                val idx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_FLAGS)
                if (idx == -1) return@runCatching null
                val flags = c.getLong(idx)
                (flags and android.provider.DocumentsContract.Document.FLAG_SUPPORTS_WRITE.toLong()) != 0L
            }
        }.getOrNull()
        if (supportsWriteByFlags == false) return true
        return runCatching {
            context.contentResolver.openOutputStream(uri, "wa")?.use { }
                ?: throw IOException("OutputStream null")
            false
        }.getOrDefault(true)
    }

    fun sniffCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return Charsets.UTF_8
        return Charsets.UTF_8
    }
}
