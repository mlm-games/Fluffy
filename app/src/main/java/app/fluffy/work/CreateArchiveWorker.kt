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
import androidx.core.net.toUri

class CreateArchiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground("Creating archive")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourcesIn = inputData.getStringArray(KEY_SOURCES)?.map { it.toUri() } ?: return@withContext Result.failure()
        val targetDir = inputData.getString(KEY_TARGET_DIR)?.toUri() ?: return@withContext Result.failure()
        val outName = inputData.getString(KEY_OUT_NAME)?.ifBlank { "archive.zip" } ?: "archive.zip"
        val password = inputData.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }?.toCharArray()

        val level = AppGraph.settings.settingsFlow.first().zipCompressionLevel.coerceIn(0, 9)

        val pairs: List<Pair<String, () -> java.io.InputStream>> = sourcesIn.map { uri ->
            val name = AppGraph.io.queryDisplayName(uri)
            name to { AppGraph.io.openIn(uri) }
        }
        val outUri = AppGraph.io.createFile(targetDir, outName, "application/zip")
        val writeTarget: () -> java.io.OutputStream = { AppGraph.io.openOut(outUri) }

        setProgress(workDataOf("progress" to 0f))

        // Correct parameter names or use positional args
        AppGraph.archive.createZip(
            sources = pairs,
            writeTarget = writeTarget,
            compressionLevel = level,
            password = password
        ) { done, total ->
            val frac = if (total > 0) done.toFloat() / total else 0f
            setProgressAsync(workDataOf("progress" to frac))
        }

        setProgressAsync(workDataOf("progress" to 1f))
        Result.success()
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