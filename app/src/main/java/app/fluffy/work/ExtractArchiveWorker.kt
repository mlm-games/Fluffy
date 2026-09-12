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
import app.fluffy.io.FileSystemAccess
import app.fluffy.util.AppLog
import app.fluffy.util.ArchiveTypes.baseNameForExtraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.OutputStream

class ExtractArchiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params), KoinComponent {

    private val io: SafIo by inject()
    private val settings: SettingsRepository by inject()
    private val archiveE: ArchiveEngine by inject()

    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground("Extracting archive")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(getForegroundInfo())
        val archive = inputData.getString(KEY_ARCHIVE)?.toUri() ?: return@withContext Result.failure()
        val targetDir = inputData.getString(KEY_TARGET_DIR)?.toUri() ?: return@withContext Result.failure()
        val password = inputData.getString(KEY_PASSWORD)?.takeIf { it.isNotEmpty() }?.toCharArray()
        val include = inputData.getStringArray(KEY_INCLUDE_PATHS)
            ?.takeIf { it.isNotEmpty() }
            ?.map { normalize(it) }
            ?.toSet()

        val name = io.queryDisplayName(archive)
        val open = { io.openIn(archive) }

        val s = settings.settingsFlow.first()
        val actualTargetDir = if (s.extractIntoSubfolder) {
            val folder = baseNameForExtraction(name)
            io.ensureDir(targetDir, folder)
        } else {
            targetDir
        }

        // If writing to a file:// root, resolve its canonical path to enforce safety
        val fileRoot = when (actualTargetDir.scheme) {
            "file" -> runCatching { File(requireNotNull(actualTargetDir.path)).canonicalFile }.getOrNull()
            else -> null
        }

        var wroteAny = false

        try {
            fun shouldInclude(path: String): Boolean {
                val p = normalize(path)
                val inc = include ?: return true
                return inc.any { sel -> p == sel || p.startsWith("$sel/") }
            }

            val create: (String, Boolean) -> OutputStream = { path, isDir ->
                if (isStopped) throw kotlinx.coroutines.CancellationException("Stopped")
                val safe = normalizeSafe(path)
                if (safe == null) {
                    devNull()
                } else if (!shouldInclude(safe)) {
                    devNull()
                } else {
                    val parentRel = safe.substringBeforeLast('/', "")
                    val fileName = safe.substringAfterLast('/').ifEmpty { "item" }
                    if (isDir || safe.endsWith("/")) {
                        val probe = probeSafe(fileRoot, actualTargetDir, safe.removeSuffix("/"))
                        if (probe == null) {
                            devNull()
                        } else {
                            val ensured = io.ensureDir(actualTargetDir, safe.removeSuffix("/"))
                            if (!isSafeDestination(fileRoot, ensured, isDir = true)) {
                                devNull()
                            } else {
                                devNull()
                            }
                        }
                    } else {
                        val probe = probeSafe(fileRoot, actualTargetDir, parentRel.ifEmpty { null }, fileName)
                        if (probe == null) {
                            devNull()
                        } else {
                            val parentUri = if (parentRel.isNotEmpty()) {
                                io.ensureDir(actualTargetDir, parentRel)
                            } else actualTargetDir
                            if (!isSafeDestination(fileRoot, parentUri, isDir = true)) {
                                devNull()
                            } else {
                                if (!overwriteSafeCheck(parentUri, fileName)) {
                                    devNull()
                                } else {
                                    val mime = FileSystemAccess.getMimeType(fileName)
                                    val fileUri = io.createFile(parentUri, fileName, mime, overwrite = false)
                                    if (!isSafeDestination(fileRoot, fileUri, isDir = false)) {
                                        runCatching { io.delete(fileUri) }
                                        devNull()
                                    } else {
                                        wroteAny = true
                                        io.openOut(fileUri)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            setProgress(workDataOf("progress" to 0f))

            try {
                archiveE.extractAll(name, open, create, password) { done, total ->
                    val frac = if (total > 0) done.toFloat() / total else 0.0f
                    setProgressAsync(workDataOf("progress" to frac))
                }
            } catch (e: ZipException) {
                AppLog.w("ExtractArchiveWorker", "strict zip extract failed, trying fallback: $name", e)
                open().use { input ->
                    ZipArchiveInputStream(input).use { zin ->
                        var entry = zin.nextEntry
                        while (entry != null) {
                            if (isStopped) throw kotlinx.coroutines.CancellationException("Stopped")
                            val entryName = entry.name ?: ""
                            if (entryName.isNotBlank()) {
                                create(entryName, entry.isDirectory).use { out ->
                                    if (!entry.isDirectory) {
                                        zin.copyTo(out)
                                        out.flush()
                                    }
                                }
                            }
                            entry = zin.nextEntry
                        }
                    }
                }
            }

            if (!wroteAny) {
                Result.failure(workDataOf("error" to "Nothing extracted (0 files written)"))
            } else {
                setProgress(workDataOf("progress" to 1f))
                Result.success()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("ExtractArchiveWorker", "extract failed: $archive", e)
            Result.failure(workDataOf("error" to (e.message ?: e.toString())))
        }
    }

    private fun devNull() = object : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private fun normalize(path: String): String =
        path.trim().trimStart('/').replace('\\', '/').removeSuffix("/")

    private fun normalizeSafe(path: String): String? {
        val p = path.trim().replace('\\', '/')
        if (p.startsWith("/") || p.contains('\u0000')) return null
        // Windows drive or UNC
        if (Regex("^[A-Za-z]:").containsMatchIn(p) || p.startsWith("//")) return null
        val parts = p.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun isSafeDestination(root: File?, dest: Uri, isDir: Boolean): Boolean {
        if (dest.scheme != "file") {
            return true
        }
        if (root == null) return true
        return try {
            val f = File(requireNotNull(dest.path)).canonicalFile
            val rootPath = root.canonicalPath
            if (isDir) {
                f.path == rootPath || f.path.startsWith("$rootPath/")
            } else {
                val parent = f.parentFile?.canonicalPath ?: return false
                parent == rootPath || parent.startsWith("$rootPath/")
            }
        } catch (e: Exception) {

            AppLog.w("ExtractArchiveWorker", "isSafeDestination check failed: $dest", e)
            false
        }
    }

    /** Pre-check safety without creating anything. Returns null if unsafe. */
    private fun probeSafe(root: File?, base: Uri, relDir: String): Boolean? {
        if (base.scheme != "file" || root == null) return true
        return try {
            val basePath = File(requireNotNull(base.path)).canonicalFile
            val target = File(basePath, relDir).canonicalPath
            val rootPath = root.canonicalPath
            if (target == rootPath || target.startsWith("$rootPath/")) true else null
        } catch (_: Exception) { null }
    }

    private fun probeSafe(root: File?, base: Uri, relDir: String?, name: String): Boolean? {
        if (base.scheme != "file" || root == null) return true
        return try {
            val basePath = File(requireNotNull(base.path)).canonicalFile
            val dir = if (relDir.isNullOrEmpty()) basePath else File(basePath, relDir)
            val target = File(dir, name).canonicalPath
            val parent = File(target).parent ?: return null
            val rootPath = root.canonicalPath
            if (parent == rootPath || parent.startsWith("$rootPath/")) true else null
        } catch (_: Exception) { null }
    }

    private fun overwriteSafeCheck(parentUri: Uri, name: String): Boolean {
        return try {
            !io.childExists(parentUri, name)
        } catch (_: Exception) { false }
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
        const val KEY_ARCHIVE = "archive"
        const val KEY_TARGET_DIR = "targetDir"
        const val KEY_PASSWORD = "password"
        const val KEY_INCLUDE_PATHS = "includePaths"
    }
}
