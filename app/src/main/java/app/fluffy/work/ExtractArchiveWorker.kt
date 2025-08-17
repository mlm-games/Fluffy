package app.fluffy.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.fluffy.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExtractArchiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground("Extracting archive")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val archive = inputData.getString(KEY_ARCHIVE)?.let { Uri.parse(it) } ?: return@withContext Result.failure()
        val targetDir = inputData.getString(KEY_TARGET_DIR)?.let { Uri.parse(it) } ?: return@withContext Result.failure()
        val password = inputData.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }?.toCharArray()

        val name = AppGraph.io.queryDisplayName(archive)
        val open = { AppGraph.io.openIn(archive) }

        // Create resolver that creates directories/files under targetDir
        val create: (String, Boolean) -> java.io.OutputStream = { path, isDir ->
            val clean = path.trimStart('/').replace('\\', '/')
            val parent = clean.substringBeforeLast('/', "")
            val fileName = clean.substringAfterLast('/').ifEmpty { "item" }

            val parentUri = if (parent.isNotEmpty()) AppGraph.io.ensureDir(targetDir, parent) else targetDir
            if (isDir || clean.endsWith("/")) {
                // Create dir placeholder
                AppGraph.io.ensureDir(targetDir, clean)
                // Return a sink to a throwaway null device (we just needed to ensure dir)
                object : java.io.OutputStream() { override fun write(b: Int) {} }
            } else {
                val fileUri = AppGraph.io.createFile(parentUri, fileName)
                AppGraph.io.openOut(fileUri)
            }
        }

        setProgress(workDataOf("progress" to 0f))
        AppGraph.archive.extractAll(name, open, create, password) { _, _ -> /* could set progress */ }
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
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
        return ForegroundInfo((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    companion object {
        const val KEY_ARCHIVE = "archive"
        const val KEY_TARGET_DIR = "targetDir"
        const val KEY_PASSWORD = "password"
    }
}