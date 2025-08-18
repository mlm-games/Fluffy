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

        val outTmp = File(applicationContext.cacheDir, "create_${System.currentTimeMillis()}.7z")
        
        try {
            val sevenZFile = if (password != null) {
                SevenZOutputFile(outTmp, password)
            } else {
                SevenZOutputFile(outTmp)
            }
            
            sevenZFile.use { archive ->
                val total = sources.size
                var done = 0
                
                for (uri in sources) {
                    val fileName = AppGraph.io.queryDisplayName(uri)
                    
                    // Create entry
                    val entry = archive.createArchiveEntry(File(fileName), fileName)
                    archive.putArchiveEntry(entry)
                    
                    // Write content
                    if (!fileName.endsWith("/")) {
                        AppGraph.io.openIn(uri).use { input ->
                            val buffer = ByteArray(8192)
                            var len = input.read(buffer)
                            while (len > 0) {
                                archive.write(buffer, 0, len)
                                len = input.read(buffer)
                            }
                        }
                    }
                    
                    archive.closeArchiveEntry()
                    done++
                    setProgress(workDataOf("progress" to (done.toFloat() / total)))
                }
            }
            
            // Now copy the temp file to the target location
            val outUri = AppGraph.io.createFile(targetDir, outName, "application/x-7z-compressed")
            AppGraph.io.openOut(outUri).use { out ->
                outTmp.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            
            setProgress(workDataOf("progress" to 1f))
            Result.success()
            
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        } finally {
            // Clean up temp file
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

    companion object {
        const val KEY_SOURCES = "sources"
        const val KEY_TARGET_DIR = "targetDir"
        const val KEY_OUT_NAME = "outName"
        const val KEY_PASSWORD = "password"
    }
}