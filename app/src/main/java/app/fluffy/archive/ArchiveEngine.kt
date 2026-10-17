package app.fluffy.archive

import java.io.File
import java.io.InputStream
import java.io.OutputStream

interface ArchiveEngine {
    data class Entry(val path: String, val isDir: Boolean, val size: Long, val time: Long)
    data class ListResult(val entries: List<Entry>, val encrypted: Boolean, val error: String? = null)
    data class VerifyResult(
        val ok: Boolean,
        val checked: Int,
        val failedPath: String? = null,
        val error: String? = null
    )

    enum class ZipEncryption { NONE, ZIP_CRYPTO, AES_128, AES_192, AES_256 }

    data class ZipCreateOptions(
        val compressionLevel: Int = 5,
        val encryption: ZipEncryption = ZipEncryption.NONE,
        val comment: String? = null,
        val splitSizeBytes: Long? = null,
    )

    suspend fun list(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray? = null,
        stagedSplit: File? = null
    ): ListResult

    suspend fun verify(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): VerifyResult

    suspend fun extractAll(
        archiveName: String,
        open: () -> InputStream,
        create: (pathInArchive: String, isDir: Boolean) -> OutputStream,
        password: CharArray? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        stagedSplit: File? = null
    )

    suspend fun createZip(
        sources: List<Pair<String, () -> InputStream>>,
        writeTarget: () -> OutputStream,
        options: ZipCreateOptions = ZipCreateOptions(),
        password: CharArray? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    )

    suspend fun stageZipParts(
        sources: List<Pair<String, () -> InputStream>>,
        options: ZipCreateOptions,
        password: CharArray? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): List<File>

    suspend fun readComment(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray? = null
    ): String?

    suspend fun updateZip(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray? = null,
        remove: List<String> = emptyList(),
        add: List<Pair<String, () -> InputStream>> = emptyList(),
        comment: String? = null,
        replaceComment: Boolean = false,
        writeBack: () -> OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    )
}