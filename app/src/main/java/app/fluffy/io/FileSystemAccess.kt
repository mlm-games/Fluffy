package app.fluffy.io

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.io.File

class FileSystemAccess(private val context: Context) {

    private fun hasReadStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun canRequestManageStorage(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        val packageManager = context.packageManager

        val specificIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = "package:${context.packageName}".toUri()
        }
        if (specificIntent.resolveActivity(packageManager) != null) return true

        val generalIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return generalIntent.resolveActivity(packageManager) != null
    }

    companion object {
        fun isArchiveFile(fileName: String): Boolean {
            val lower = fileName.lowercase()
            return lower.endsWith(".zip") ||
                    lower.endsWith(".7z") ||
                    lower.endsWith(".tar") ||
                    lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ||
                    lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") ||
                    lower.endsWith(".tar.xz") || lower.endsWith(".txz") ||
                    lower.endsWith(".jar") ||
                    lower.endsWith(".apk")
        }

        fun getMimeType(fileName: String): String {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".zip") || lower.endsWith(".jar") -> "application/zip"
                lower.endsWith(".7z") -> "application/x-7z-compressed"
                lower.endsWith(".tar") -> "application/x-tar"
                lower.endsWith(".apk") -> "application/vnd.android.package-archive"
                lower.endsWith(".pdf") -> "application/pdf"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".mp4") -> "video/mp4"
                lower.endsWith(".mp3") -> "audio/mpeg"
                lower.endsWith(".svg") -> "image/svg"
                else -> "application/octet-stream"
            }
        }
    }

    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (canRequestManageStorage()) {
                Environment.isExternalStorageManager()
            } else {
                hasReadStoragePermission()
            }
        } else
            hasReadStoragePermission()
    }

    fun getExternalStorageRoot(): File? {
        return if (hasStoragePermission()) {
            Environment.getExternalStorageDirectory()
        } else null
    }

    fun listFiles(directory: File): List<File> {
        if (!hasStoragePermission()) return emptyList()
        return directory.listFiles()?.toList() ?: emptyList()
    }


    fun getDownloadsFolder(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun getDocumentsFolder(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

    fun getPicturesFolder(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

    fun getMusicFolder(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

    fun getVideosFolder(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)

    /**
     * Detects all user storage roots visible to the app (primary + removable SDs).
     */
    fun getAllStorageRoots(): List<File> {
        val roots = linkedSetOf<File>()

        getExternalStorageRoot()?.let { if (it.exists()) roots += it }

        // Additional storages (SD card, USB OTG) via app-specific dirs
        try {
            val dirs = context.getExternalFilesDirs(null)
            dirs?.forEach { dir ->
                if (dir == null) return@forEach
                var cur: File? = dir
                // Ascend until we hit ".../Android"
                while (cur != null && cur.name != "Android") {
                    cur = cur.parentFile
                }
                val root = cur?.parentFile
                if (root != null && root.exists()) {
                    // Avoid duplicating primary
                    if (!roots.any { it.absolutePath == root.absolutePath }) {
                        roots += root
                    }
                }
            }
        } catch (_: Exception) { /* ignore */ }

        return roots.toList()
    }
}
