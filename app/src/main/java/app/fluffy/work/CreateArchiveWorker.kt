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
import app.fluffy.archive.ArchiveEngine
import app.fluffy.data.repository.SettingsRepository
import app.fluffy.io.SafIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt

class CreateArchiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params), KoinComponent {

    private val io: SafIo by inject()
    private val settings: SettingsRepository by inject()
    private val archive: ArchiveEngine by inject()

    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground("Creating archive")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(getForegroundInfo())
        try {
            if (isStopped) throw kotlinx.coroutines.CancellationException("Stopped")
            val sourcesIn = inputData.getStringArray(KEY_SOURCES)?.map { it.toUri() } ?: return@withContext Result.failure(workDataOf("error" to "No sources"))
            if (sourcesIn.isEmpty()) return@withContext Result.failure(workDataOf("error" to "No sources"))
            val targetDir = inputData.getString(KEY_TARGET_DIR)?.toUri() ?: return@withContext Result.failure(workDataOf("error" to "No target"))
            val outName = inputData.getString(KEY_OUT_NAME)?.ifBlank { "archive.zip" } ?: "archive.zip"
            if ('/' in outName || outName == "." || outName == "..") {
                return@withContext Result.failure(workDataOf("error" to "Invalid name"))
            }
            val password = inputData.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }?.toCharArray()
            val overwrite = inputData.getBoolean(KEY_OVERWRITE, false)

            val level = settings.settingsFlow.first().zipCompressionLevel.roundToInt().coerceIn(0, 9)

            if (!overwrite && io.childExists(targetDir, outName)) {
                return@withContext Result.failure(workDataOf("error" to "Exists: $outName"))
            }

            val pairs = mutableListOf<Pair<String, () -> InputStream>>()
            val seen = HashSet<String>()
            for (src in sourcesIn) {
                if (isStopped) throw kotlinx.coroutines.CancellationException("Stopped")
                collectFilesRec(src, sanitizeEntry(io.queryDisplayName(src)), pairs, seen, isTopLevel = true)
            }
            if (pairs.isEmpty()) return@withContext Result.failure(workDataOf("error" to "Nothing to archive"))

            val outUri = io.createFile(targetDir, outName, "application/zip", overwrite = overwrite)
            val writeTarget: () -> OutputStream = { io.openOut(outUri) }

            setProgress(workDataOf("progress" to 0f))

            try {
                archive.createZip(
                    sources = pairs,
                    writeTarget = writeTarget,
                    compressionLevel = level,
                    password = password
                ) { done, total ->
                    val frac = if (total > 0) done.toFloat() / total else 0f
                    setProgressAsync(workDataOf("progress" to frac))
                }
            } catch (e: Exception) {
                runCatching { io.delete(outUri) }
                throw e
            }

            setProgressAsync(workDataOf("progress" to 1f))
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            app.fluffy.util.AppLog.e("CreateArchiveWorker", "create zip failed", e)
            Result.failure(workDataOf("error" to (e.message ?: e.toString())))
        }
    }

    private fun sanitizeEntry(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "item" }
        if (base == "." || base == "..") return "item"
        return base.replace(Regex("[:\\\\]"), "_").trimStart('/').ifBlank { "item" }
    }

    private fun collectFilesRec(
        uri: Uri,
        relPath: String,
        out: MutableList<Pair<String, () -> InputStream>>,
        seen: MutableSet<String>,
        isTopLevel: Boolean = false,
        depth: Int = 0
    ) {
        if (isStopped) throw kotlinx.coroutines.CancellationException("Stopped")
        if (isStopped) throw kotlinx.coroutines.CancellationException("Stopped")
        if (depth > 64) throw java.io.IOException("Max depth exceeded")
        val safeRel = relPath.trim().replace('\\', '/').trimStart('/').ifBlank { "item" }
        if (safeRel.split('/').any { it == "." || it == ".." || it.isBlank() }) {
            throw java.io.IOException("Invalid entry: $relPath")
        }
        if (uri.scheme == "root" || uri.scheme == "shizuku") {
            val isFile = runCatching { io.openIn(uri).close() }.isSuccess
            if (isFile) {
                val key = if (seen.add(safeRel)) safeRel else disambiguate(safeRel, seen)
                out += key to { io.openIn(uri) }
            } else {
                val kids = runCatching { io.listShell(uri) }.getOrDefault(emptyList())
                if (kids.isEmpty()) {
                    val dirKey = "${safeRel.trimEnd('/')}/"
                    if (seen.add(dirKey)) out += dirKey to { java.io.ByteArrayInputStream(ByteArray(0)) }
                } else {
                    kids.forEach { child ->
                        collectFilesRec(child.uri, "$safeRel/${child.name}", out, seen, depth = depth + 1)
                    }
                }
            }
            return
        }
        val df = io.docFileFromUri(uri)
        if (uri.scheme == "content" && df != null) {
            if (df.isDirectory) {
                val kids = df.listFiles()
                if (kids.isEmpty()) {
                    val dirKey = "${safeRel.trimEnd('/')}/"
                    if (seen.add(dirKey)) out += dirKey to { java.io.ByteArrayInputStream(ByteArray(0)) }
                } else {
                    kids.forEach { child ->
                        val childName = "${safeRel.trimEnd('/')}/${child.name ?: "item"}"
                        collectFilesRec(child.uri, childName, out, seen, depth = depth + 1)
                    }
                }
            } else {
                val key = if (seen.add(safeRel)) safeRel else disambiguate(safeRel, seen)
                out += key to { io.openIn(uri) }
            }
        } else {
            val f = File(requireNotNull(uri.path))
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return
            if (f.isDirectory) {
                val kids = f.listFiles()
                if (kids.isNullOrEmpty()) {
                    val dirKey = "${safeRel.trimEnd('/')}/"
                    if (seen.add(dirKey)) out += dirKey to { java.io.ByteArrayInputStream(ByteArray(0)) }
                } else {
                    kids.forEach { child ->
                        collectFilesRec(Uri.fromFile(child), "${safeRel.trimEnd('/')}/${child.name}", out, seen, depth = depth + 1)
                    }
                }
            } else {
                val key = if (seen.add(safeRel)) safeRel else disambiguate(safeRel, seen)
                out += key to { f.inputStream() }
            }
        }
    }

    private fun disambiguate(base: String, seen: MutableSet<String>): String {
        var i = 2
        while (true) {
            val dot = base.lastIndexOf('.')
            val cand = if (dot > 0) "${base.substring(0, dot)}_$i${base.substring(dot)}" else "${base}_$i"
            if (seen.add(cand)) return cand
            i++
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

    companion object {
        const val KEY_SOURCES = "sources"
        const val KEY_TARGET_DIR = "targetDir"
        const val KEY_OUT_NAME = "outName"
        const val KEY_PASSWORD = "password"
        const val KEY_OVERWRITE = "overwrite"
    }
}
