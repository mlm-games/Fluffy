package app.fluffy.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.fluffy.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CreateArchiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground("Creating archive")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sources = inputData.getStringArray(KEY_SOURCES)?.map { Uri.parse(it) } ?: return@withContext Result.failure()
        val targetDir = inputData.getString(KEY_TARGET_DIR)?.let { Uri.parse(it) } ?: return@withContext Result.failure()
        val outName = inputData.getString(KEY_OUT_NAME)?.ifBlank { "archive.zip" } ?: "archive.zip"
        val password = inputData.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }?.toCharArray()

        val pairs = sources.map { uri ->
            val name = AppGraph.io.queryDisplayName(uri)
            name to { AppGraph.io.openIn(uri) }
        }
        val outUri = AppGraph.io.createFile(targetDir, outName, "application/zip")
        val write = { AppGraph.io.openOut(outUri) }

        setProgress(workDataOf("progress" to 0f))
        AppGraph.archive.createZip(pairs, write, compressionLevel = 5, password = password) { _, _ -> }
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