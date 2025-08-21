package app.fluffy.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.fluffy.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.OutputStream
import androidx.core.net.toUri
import app.fluffy.io.FileSystemAccess
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream

class ExtractArchiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground("Extracting archive")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val archive = inputData.getString(KEY_ARCHIVE)?.toUri() ?: return@withContext Result.failure()
        val targetDir = inputData.getString(KEY_TARGET_DIR)?.toUri() ?: return@withContext Result.failure()
        val password = inputData.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }?.toCharArray()
        val include = inputData.getStringArray(KEY_INCLUDE_PATHS)
            ?.takeIf { it.isNotEmpty() }
            ?.map { normalize(it) }
            ?.toSet()

        val name = AppGraph.io.queryDisplayName(archive)
        val open = { AppGraph.io.openIn(archive) }

        val settings = AppGraph.settings.settingsFlow.first()
        val actualTargetDir = if (settings.extractIntoSubfolder) {
            val folder = baseNameForExtraction(name)
            AppGraph.io.ensureDir(targetDir, folder)
        } else {
            targetDir
        }

        var wroteAny = false

        try {
            fun shouldInclude(path: String): Boolean {
                val p = normalize(path)
                val inc = include ?: return true
                return inc.any { sel -> p == sel || p.startsWith("$sel/") }
            }

            val create: (String, Boolean) -> OutputStream = { path, isDir ->
                val clean = path.trimStart('/').replace('\\', '/')
                if (!shouldInclude(clean)) {
                    object : OutputStream() {
                        override fun write(b: Int) {}
                        override fun write(b: ByteArray, off: Int, len: Int) {}
                    }
                } else {
                    val parentRel = clean.substringBeforeLast('/', "")
                    val fileName = clean.substringAfterLast('/').ifEmpty { "item" }
                    if (isDir || clean.endsWith("/")) {
                        AppGraph.io.ensureDir(actualTargetDir, clean.removeSuffix("/"))
                        wroteAny = true
                        object : OutputStream() { override fun write(b: Int) {} }
                    } else {
                        val parentUri = if (parentRel.isNotEmpty()) {
                            AppGraph.io.ensureDir(actualTargetDir, parentRel)
                        } else actualTargetDir
                        val mime = FileSystemAccess.getMimeType(fileName)
                        val fileUri = AppGraph.io.createFile(parentUri, fileName, mime)
                        wroteAny = true
                        AppGraph.io.openOut(fileUri)
                    }
                }
            }

            setProgress(workDataOf("progress" to 0f))

            try {
                AppGraph.archive.extractAll(name, open, create, password) { done, total ->
                    val frac = if (total > 0) done.toFloat() / total else 0.0f
                    setProgressAsync(workDataOf("progress" to frac))
                }
            } catch (e: ZipException) {
                e.printStackTrace()
                // Fallback for strict/invalid zips (e.g., some APKs)
                open().use { input ->
                    ZipArchiveInputStream(input).use { zin ->
                        var entry = zin.nextZipEntry
                        while (entry != null) {
                            val entryName = entry.name ?: ""
                            if (entryName.isNotBlank()) {
                                val out = create(entryName, entry.isDirectory)
                                if (!entry.isDirectory) {
                                    zin.copyTo(out)
                                    out.flush()
                                }
                                out.close()
                            }
                            entry = zin.nextZipEntry
                        }
                    }
                }
            }

            if (!wroteAny) {
                Result.failure(workDataOf("error" to "Nothing extracted (0 files written)"))
            } else {
                setProgress(workDataOf("progress" to 1f))
                Result.success()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(workDataOf("error" to (e.message ?: e.toString())))
        }
    }

    private fun normalize(path: String): String =
        path.trim().trimStart('/').replace('\\', '/').removeSuffix("/")

    private fun baseNameForExtraction(fileName: String): String {
        val n = fileName.lowercase()
        val candidates = listOf(
            ".tar.gz", ".tar.bz2", ".tar.xz",
            ".tgz", ".tbz2", ".txz",
            ".zip", ".jar", ".apk", ".7z", ".tar"
        )
        val hit = candidates.firstOrNull { n.endsWith(it) }
        return if (hit != null) {
            fileName.dropLast(hit.length)
        } else {
            fileName.substringBeforeLast('.', fileName)
        }.ifBlank { "extracted" }
    }

    private fun createForeground(title: String): ForegroundInfo {
        val channelId = "fluffy.work"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Background tasks", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        return ForegroundInfo((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    companion object {
        const val KEY_ARCHIVE = "archive"
        const val KEY_TARGET_DIR = "targetDir"
        const val KEY_PASSWORD = "password"
        const val KEY_INCLUDE_PATHS = "includePaths"
    }
}