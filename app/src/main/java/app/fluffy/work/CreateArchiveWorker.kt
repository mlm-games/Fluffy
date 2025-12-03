package app.fluffy.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.fluffy.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class CreateArchiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground("Creating archive")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(getForegroundInfo())
        val sourcesIn = inputData.getStringArray(KEY_SOURCES)?.map { it.toUri() } ?: return@withContext Result.failure()
        val targetDir = inputData.getString(KEY_TARGET_DIR)?.toUri() ?: return@withContext Result.failure()
        val outName = inputData.getString(KEY_OUT_NAME)?.ifBlank { "archive.zip" } ?: "archive.zip"
        val password = inputData.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }?.toCharArray()
        val overwrite = inputData.getBoolean(KEY_OVERWRITE, false)

        val level = AppGraph.settings.settingsFlow.first().zipCompressionLevel.coerceIn(0, 9)

        val pairs = mutableListOf<Pair<String, () -> java.io.InputStream>>()
        for (src in sourcesIn) {
            collectFilesRec(src, AppGraph.io.queryDisplayName(src), pairs)
        }

        val outUri = AppGraph.io.createFile(targetDir, outName, "application/zip", overwrite = overwrite)
        val writeTarget: () -> java.io.OutputStream = { AppGraph.io.openOut(outUri) }

        setProgress(workDataOf("progress" to 0f))

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

    private fun collectFilesRec(
        uri: Uri,
        relPath: String,
        out: MutableList<Pair<String, () -> java.io.InputStream>>
    ) {
        val df = AppGraph.io.docFileFromUri(uri)
        if (uri.scheme == "content" && df != null) {
            if (df.isDirectory) {
                df.listFiles().forEach { child ->
                    val childName = "${relPath.trimEnd('/')}/${child.name ?: "item"}"
                    collectFilesRec(child.uri, childName, out)
                }
            } else {
                out += relPath to { AppGraph.io.openIn(uri) }
            }
        } else {
            val f = File(requireNotNull(uri.path))
            if (f.isDirectory) {
                f.listFiles()?.forEach { child ->
                    collectFilesRec(Uri.fromFile(child), "${relPath.trimEnd('/')}/${child.name}", out)
                }
            } else {
                out += relPath to { f.inputStream() }
            }
        }
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
        const val KEY_OVERWRITE = "overwrite"
    }
}

