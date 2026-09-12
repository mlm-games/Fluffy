package app.fluffy.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.fluffy.io.SafIo
import app.fluffy.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.InputStream
import java.util.Date

class Create7zWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params), KoinComponent {

    private val io: SafIo by inject()

    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground("Creating 7z archive")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(getForegroundInfo())
        try {
            if (isStopped) throw kotlinx.coroutines.CancellationException("Stopped")
            val sources = inputData.getStringArray(KEY_SOURCES)?.map { it.toUri() } ?: return@withContext Result.failure(workDataOf("error" to "No sources"))
            if (sources.isEmpty()) return@withContext Result.failure(workDataOf("error" to "No sources"))
            val targetDir = inputData.getString(KEY_TARGET_DIR)?.toUri() ?: return@withContext Result.failure(workDataOf("error" to "No target"))
            val outName = inputData.getString(KEY_OUT_NAME)?.ifBlank { "archive.7z" } ?: "archive.7z"
            if ('/' in outName || outName == "." || outName == "..") {
                return@withContext Result.failure(workDataOf("error" to "Invalid name"))
            }
            val password = inputData.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }?.toCharArray()
            val overwrite = inputData.getBoolean(KEY_OVERWRITE, false)
            if (!overwrite && io.childExists(targetDir, outName)) {
                return@withContext Result.failure(workDataOf("error" to "Exists: $outName"))
            }

            val outTmp = File.createTempFile("create_", ".7z", applicationContext.cacheDir)

            try {
                val sevenZ = if (password != null) SevenZOutputFile(outTmp, password) else SevenZOutputFile(outTmp)
                sevenZ.use { archive ->
                    var done = 0
                    val total = sources.size.coerceAtLeast(1)
                    for (uri in sources) {
                        if (isStopped) throw kotlinx.coroutines.CancellationException("Stopped")
                        val baseName = sanitizeEntry(io.queryDisplayName(uri))
                        addTo7z(archive, uri, baseName)
                        done++
                        setProgress(workDataOf("progress" to (done.toFloat() / total)))
                    }
                }

                if (isStopped) throw kotlinx.coroutines.CancellationException("Stopped")
                val outUri = io.createFile(targetDir, outName, "application/x-7z-compressed", overwrite = overwrite)
                try {
                    io.openOut(outUri).use { out ->
                        outTmp.inputStream().use { input -> input.copyTo(out) }
                    }
                } catch (e: Exception) {
                    runCatching { io.delete(outUri) }
                    throw e
                }

                setProgress(workDataOf("progress" to 1f))
                Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Create7zWorker", "create 7z failed", e)
                Result.failure(workDataOf("error" to (e.message ?: e.toString())))
            } finally {
                runCatching { outTmp.delete() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Create7zWorker", "create 7z failed", e)
            Result.failure(workDataOf("error" to (e.message ?: e.toString())))
        }
    }

    private fun createForeground(title: String): ForegroundInfo {
        val channelId = "fluffy.work"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(channelId) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Background tasks",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val n: Notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, n)
        }
    }

    private fun addTo7z(archive: SevenZOutputFile, uri: Uri, relPath: String, depth: Int = 0) {
        if (isStopped) throw kotlinx.coroutines.CancellationException("Stopped")
        if (depth > 64) throw java.io.IOException("Max depth")
        val safeRel = relPath.trim().replace('\\', '/').trimStart('/').ifBlank { "item" }
        if (uri.scheme == "root" || uri.scheme == "shizuku") {
            val isFile = runCatching { io.openIn(uri).close() }.isSuccess
            if (isFile) {
                val entry = SevenZArchiveEntry().apply {
                    name = safeRel
                    size = -1L // unknown via shell; commons-compress handles streaming
                }
                archive.putArchiveEntry(entry)
                io.openIn(uri).use { copyToSevenZ(it, archive) }
                archive.closeArchiveEntry()
            } else {
                val dirEntry = SevenZArchiveEntry().apply {
                    name = ensureDirSuffix(safeRel)
                    isDirectory = true
                }
                archive.putArchiveEntry(dirEntry)
                archive.closeArchiveEntry()
                io.listShell(uri).forEach { child ->
                    addTo7z(archive, child.uri, "${safeRel.trimEnd('/')}/${child.name}", depth + 1)
                }
            }
            return
        }
        val df = io.docFileFromUri(uri)
        if (uri.scheme == "content" && df != null) {
            if (df.isDirectory) {
                val dirEntry = SevenZArchiveEntry().apply {
                    name = ensureDirSuffix(safeRel)
                    isDirectory = true
                    runCatching { lastModifiedDate = Date(df.lastModified()) }
                }
                archive.putArchiveEntry(dirEntry)
                archive.closeArchiveEntry()
                df.listFiles().forEach { child ->
                    val childName = "${safeRel.trimEnd('/')}/${child.name ?: "item"}"
                    addTo7z(archive, child.uri, childName, depth + 1)
                }
            } else {
                val entry = SevenZArchiveEntry().apply {
                    name = safeRel
                    size = runCatching { df.length() }.getOrElse { -1L }.coerceAtLeast(0L)
                    runCatching { lastModifiedDate = Date(df.lastModified()) }
                }
                archive.putArchiveEntry(entry)
                io.openIn(uri).use { copyToSevenZ(it, archive) }
                archive.closeArchiveEntry()
            }
        } else {
            val f = File(requireNotNull(uri.path))
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return
            if (f.isDirectory) {
                val dirEntry = SevenZArchiveEntry().apply {
                    name = ensureDirSuffix(safeRel)
                    isDirectory = true
                    runCatching { lastModifiedDate = Date(f.lastModified()) }
                }
                archive.putArchiveEntry(dirEntry)
                archive.closeArchiveEntry()
                f.listFiles()?.forEach { child ->
                    addTo7z(archive, Uri.fromFile(child), "${safeRel.trimEnd('/')}/${child.name}", depth + 1)
                }
            } else {
                val entry = SevenZArchiveEntry().apply {
                    name = safeRel
                    size = f.length().coerceAtLeast(0L)
                    runCatching { lastModifiedDate = Date(f.lastModified()) }
                }
                archive.putArchiveEntry(entry)
                f.inputStream().use { copyToSevenZ(it, archive) }
                archive.closeArchiveEntry()
            }
        }
    }

    private fun sanitizeEntry(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "item" }
        if (base == "." || base == "..") return "item"
        return base.replace(Regex("[:\\\\]"), "_").trimStart('/').ifBlank { "item" }
    }

    private fun copyToSevenZ(input: InputStream, archive: SevenZOutputFile) {
        val buffer = ByteArray(8192)
        var len = input.read(buffer)
        while (len != -1) {
            if (len > 0) archive.write(buffer, 0, len)
            len = input.read(buffer)
        }
    }

    private fun ensureDirSuffix(name: String) = if (name.endsWith("/")) name else "$name/"

    companion object {
        const val KEY_SOURCES = "sources"
        const val KEY_TARGET_DIR = "targetDir"
        const val KEY_OUT_NAME = "outName"
        const val KEY_PASSWORD = "password"
        const val KEY_OVERWRITE = "overwrite"
    }
}
