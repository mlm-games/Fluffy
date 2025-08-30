package app.fluffy.io

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

class FileSystemAccess(private val context: Context) {

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
                else -> "application/octet-stream"
            }
        }
    }

    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
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

    fun getInternalStorage(): File = Environment.getExternalStorageDirectory()

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