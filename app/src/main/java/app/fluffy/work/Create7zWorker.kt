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
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import java.io.File
import java.io.InputStream

class Create7zWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground("Creating 7z archive")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sources = inputData.getStringArray(KEY_SOURCES)?.map { it.toUri() } ?: return@withContext Result.failure()
        val targetDir = inputData.getString(KEY_TARGET_DIR)?.toUri() ?: return@withContext Result.failure()
        val outName = inputData.getString(KEY_OUT_NAME)?.ifBlank { "archive.7z" } ?: "archive.7z"
        val password = inputData.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }?.toCharArray()

        val outTmp = File(applicationContext.cacheDir, "create_${System.currentTimeMillis()}.7z")

        try {
            val sevenZ = if (password != null) SevenZOutputFile(outTmp, password) else SevenZOutputFile(outTmp)
            sevenZ.use { archive ->
                var done = 0
                val total = sources.size.coerceAtLeast(1)
                for (uri in sources) {
                    val baseName = AppGraph.io.queryDisplayName(uri)
                    addTo7z(archive, uri, baseName)
                    done++
                    setProgress(workDataOf("progress" to (done.toFloat() / total)))
                }
            }

            val outUri = AppGraph.io.createFile(targetDir, outName, "application/x-7z-compressed", overwrite = true)
            AppGraph.io.openOut(outUri).use { out ->
                outTmp.inputStream().use { input -> input.copyTo(out) }
            }

            setProgress(workDataOf("progress" to 1f))
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(workDataOf("error" to (e.message ?: e.toString())))
        } finally {
            outTmp.delete()
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

    private fun addTo7z(archive: SevenZOutputFile, uri: Uri, relPath: String) {
        val df = AppGraph.io.docFileFromUri(uri)
        if (uri.scheme == "content" && df != null) {
            if (df.isDirectory) {
                // Directory entry
                val dirEntry = SevenZArchiveEntry().apply {
                    name = ensureDirSuffix(relPath)
                    isDirectory = true
                }
                archive.putArchiveEntry(dirEntry)
                archive.closeArchiveEntry()
                df.listFiles().forEach { child ->
                    val childName = "${relPath.trimEnd('/')}/${child.name ?: "item"}"
                    addTo7z(archive, child.uri, childName)
                }
            } else {
                val entry = SevenZArchiveEntry().apply { name = relPath }
                archive.putArchiveEntry(entry)
                AppGraph.io.openIn(uri).use { copyToSevenZ(it, archive) }
                archive.closeArchiveEntry()
            }
        } else {
            val f = File(requireNotNull(uri.path))
            if (f.isDirectory) {
                val dirEntry = SevenZArchiveEntry().apply {
                    name = ensureDirSuffix(relPath)
                    isDirectory = true
                }
                archive.putArchiveEntry(dirEntry)
                archive.closeArchiveEntry()
                f.listFiles()?.forEach { child ->
                    addTo7z(archive, Uri.fromFile(child), "${relPath.trimEnd('/')}/${child.name}")
                }
            } else {
                val entry = SevenZArchiveEntry().apply { name = relPath }
                archive.putArchiveEntry(entry)
                f.inputStream().use { copyToSevenZ(it, archive) }
                archive.closeArchiveEntry()
            }
        }
    }

    private fun copyToSevenZ(input: InputStream, archive: SevenZOutputFile) {
        val buffer = ByteArray(8192)
        var len = input.read(buffer)
        while (len > 0) {
            archive.write(buffer, 0, len)
            len = input.read(buffer)
        }
    }

    private fun ensureDirSuffix(name: String) = if (name.endsWith("/")) name else "$name/"

    companion object {
        const val KEY_SOURCES = "sources"
        const val KEY_TARGET_DIR = "targetDir"
        const val KEY_OUT_NAME = "outName"
        const val KEY_PASSWORD = "password"
    }
}