package app.fluffy.cache

import android.content.Context
import java.io.File

class CacheManager(context: Context) {

    enum class Area(val dirName: String) {
        Previews("previews"),
        Stage("stage"),
        Thumbnails("thumbnails"),
        Work("work"),
    }

    private val root: File = context.applicationContext.cacheDir

    fun dir(area: Area): File = areaDir(area).apply { mkdirs() }

    fun tempFile(area: Area, prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix, dir(area))

    fun managedFiles(): List<File> = Area.entries
        .flatMap { filesUnder(areaDir(it)) }
        .plus(legacyFiles())

    fun size(): Long = managedFiles().sumOf { fileLength(it) }

    fun clearAll(): Long = managedFiles().sumOf { release(it) }

    fun enforceBudget(maxBytes: Long = CACHE_BUDGET_BYTES): Long {
        val frozenBefore = System.currentTimeMillis() - IN_FLIGHT_GRACE_MS
        val files = managedFiles()
            .filter { fileLength(it) > 0L && it.lastModified() <= frozenBefore }
            .sortedBy { it.lastModified() }
        val total = files.sumOf { fileLength(it) }
        if (total <= maxBytes) return 0L
        var freed = 0L
        for (file in files) {
            if (total - freed <= maxBytes) break
            freed += release(file)
        }
        return freed
    }

    private fun areaDir(area: Area): File = File(root, area.dirName)

    private fun filesUnder(dir: File): List<File> =
        dir.walkTopDown().maxDepth(MAX_DEPTH).filter { it.isFile }.toList()

    private fun legacyFiles(): List<File> =
        root.listFiles()?.filter { file ->
            file.isFile && LEGACY_PREFIXES.any { prefix -> file.name.startsWith(prefix) }
        }?.toList() ?: emptyList()

    private fun fileLength(file: File): Long = runCatching { file.length() }.getOrDefault(0L)

    private fun release(file: File): Long {
        val size = fileLength(file)
        if (!runCatching { file.delete() }.getOrDefault(false)) return 0L
        return size
    }

    companion object {
        const val CACHE_BUDGET_BYTES = 512L * 1024L * 1024L
        private const val IN_FLIGHT_GRACE_MS = 15L * 60L * 1000L
        private const val MAX_DEPTH = 4
        private val LEGACY_PREFIXES = listOf(
            "img_", "export_", "share_", "incoming_", "preview_", "doc_", "apk_", "create_", "stage_"
        )
    }
}
