package app.fluffy.io

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import app.fluffy.util.ArchiveTypes
import java.io.File

class FileSystemAccess(private val context: Context) {

    companion object {
        fun isArchiveFile(fileName: String): Boolean = ArchiveTypes.isArchive(fileName)
        fun getMimeType(fileName: String): String = ArchiveTypes.mimeFor(fileName)
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
}