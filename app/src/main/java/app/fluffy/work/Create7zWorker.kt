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
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import androidx.core.net.toUri

class Create7zWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground("Creating 7z archive")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sources = inputData.getStringArray(KEY_SOURCES)?.map { it.toUri() } ?: return@withContext Result.failure()
        val targetDir = inputData.getString(KEY_TARGET_DIR)?.toUri() ?: return@withContext Result.failure()
        val outName = inputData.getString(KEY_OUT_NAME)?.ifBlank { "archive.7z" } ?: "archive.7z"
        val password = inputData.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }?.toCharArray()

        val outTmp = File.createTempFile("fluffy-", ".7z", applicationContext.cacheDir)
        val out = if (password != null) SevenZOutputFile(outTmp, password) else SevenZOutputFile(outTmp)
        try {
            val buf = ByteArray(128 * 1024)
            for (uri in sources) {
                val fileName = AppGraph.io.queryDisplayName(uri)
                val entry = SevenZArchiveEntry().apply { name = fileName }
                out.putArchiveEntry(entry)
                if (!fileName.endsWith("/")) {
                    AppGraph.io.openIn(uri).use { input ->
                        var r = input.read(buf)
                        while (r > 0) {
                            out.write(buf, 0, r)
                            r = input.read(buf)
                        }
                    }
                }
                out.closeArchiveEntry()
            }
        } catch (_: Throwable) {
            runCatching { out.close() }
            outTmp.delete()
            return@withContext Result.failure()
        }
        runCatching { out.close() }

        val outUri = AppGraph.io.createFile(targetDir, outName, "application/x-7z-compressed")
        AppGraph.io.openOut(outUri).use { sink ->
            outTmp.inputStream().use { it.copyTo(sink) }
        }
        runCatching { outTmp.delete() }

        setProgress(workDataOf("progress" to 1f))
        Result.success()
    }

    private fun createForeground(title: String): ForegroundInfo {
        val channelId = "fluffy.work"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(NotificationChannel(channelId, "Background tasks", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return ForegroundInfo((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    companion object {
        const val KEY_SOURCES = "sources"
        const val KEY_TARGET_DIR = "targetDir"
        const val KEY_OUT_NAME = "outName"
        const val KEY_PASSWORD = "password"
    }
}