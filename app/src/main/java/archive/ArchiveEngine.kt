package app.fluffy.archive

import java.io.InputStream
import java.io.OutputStream

interface ArchiveEngine {
    data class Entry(val path: String, val isDir: Boolean, val size: Long, val time: Long)
    data class ListResult(val entries: List<Entry>, val encrypted: Boolean)

    // Listing
    suspend fun list(archiveName: String, open: () -> InputStream, password: CharArray? = null): ListResult

    // Extract (all or subset)
    suspend fun extractAll(
        archiveName: String,
        open: () -> InputStream,
        create: (pathInArchive: String, isDir: Boolean) -> OutputStream,
        password: CharArray? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    )

    // Create zip (streaming)
    suspend fun createZip(
        sources: List<Pair<String, () -> InputStream>>, // name in archive + supplier
        writeTarget: () -> OutputStream,
        compressionLevel: Int = 5,
        password: CharArray? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    )
}