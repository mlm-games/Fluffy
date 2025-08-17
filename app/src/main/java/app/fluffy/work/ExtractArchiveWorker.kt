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
import java.io.OutputStream
import androidx.core.net.toUri

class ExtractArchiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground("Extracting archive")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val archive = inputData.getString(KEY_ARCHIVE)?.toUri() ?: return@withContext Result.failure()
        val targetDir = inputData.getString(KEY_TARGET_DIR)?.toUri() ?: return@withContext Result.failure()
        val password = inputData.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }?.toCharArray()
        val include = inputData.getStringArray(KEY_INCLUDE_PATHS)?.toSet()?.map { normalize(it) }?.toSet()

        val name = AppGraph.io.queryDisplayName(archive)
        val open = { AppGraph.io.openIn(archive) }

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
                val parent = clean.substringBeforeLast('/', "")
                val fileName = clean.substringAfterLast('/').ifEmpty { "item" }
                val parentUri = if (parent.isNotEmpty()) AppGraph.io.ensureDir(targetDir, parent) else targetDir
                if (isDir || clean.endsWith("/")) {
                    AppGraph.io.ensureDir(targetDir, clean)
                    object : OutputStream() { override fun write(b: Int) {} }
                } else {
                    val fileUri = AppGraph.io.createFile(parentUri, fileName)
                    AppGraph.io.openOut(fileUri)
                }
            }
        }

        setProgress(workDataOf("progress" to 0f))

        AppGraph.archive.extractAll(name, open, create, password) { done, total ->
            val frac = if (total > 0) done.toFloat() / total else 0.0f
            // Use non-suspending variant inside the callback
            setProgressAsync(workDataOf("progress" to frac))
            if (isStopped) return@extractAll
        }

        setProgressAsync(workDataOf("progress" to 1f))
        Result.success()
    }

    private fun normalize(path: String): String =
        path.trim().trimStart('/').replace('\\', '/').removeSuffix("/")

    private fun createForeground(title: String): ForegroundInfo {
        val channelId = "fluffy.work"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(NotificationChannel(channelId, "Background tasks", NotificationManager.IMPORTANCE_LOW))
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