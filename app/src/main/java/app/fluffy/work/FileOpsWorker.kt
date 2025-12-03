package app.fluffy.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.fluffy.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileOpsWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val op = inputData.getString(KEY_OP) ?: OP_COPY
        val title = if (op == OP_MOVE) "Moving files" else "Copying files"
        return createForeground(title)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(getForegroundInfo())
        val sources = inputData.getStringArray(KEY_SOURCES)?.map { it.toUri() } ?: return@withContext Result.failure()
        val target = inputData.getString(KEY_TARGET_DIR)?.toUri() ?: return@withContext Result.failure()
        val op = inputData.getString(KEY_OP) ?: OP_COPY
        val overwrite = inputData.getBoolean(KEY_OVERWRITE, false)

        val total = sources.size.coerceAtLeast(1)
        sources.forEachIndexed { index, uri ->
            if (isStopped) return@withContext Result.failure()
            val ok = try {
                when (op) {
                    OP_MOVE -> AppGraph.io.moveIntoDir(uri, target, overwrite)
                    else -> AppGraph.io.copyIntoDir(uri, target, overwrite)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            if (!ok) {
                return@withContext Result.failure(
                    workDataOf("error" to "Failed to $op: $uri")
                )
            }
            val progress = (index + 1).toFloat() / total
            setProgress(workDataOf("progress" to progress))
        }
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
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        return ForegroundInfo((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }

    companion object {
        const val KEY_SOURCES = "sources"
        const val KEY_TARGET_DIR = "targetDir"
        const val KEY_OP = "op"
        const val KEY_OVERWRITE = "overwrite"
        const val OP_COPY = "copy"
        const val OP_MOVE = "move"
    }
}