package app.fluffy.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

class StorageAccessPolicy(
    private val context: Context,
) {
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && canRequestManageStorage()) {
            Environment.isExternalStorageManager()
        } else {
            missingRegularPermissions().isEmpty()
        }
    }

    fun canRequestManageStorage(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        val specificIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = "package:${context.packageName}".toUri()
        }
        if (specificIntent.resolveActivity(context.packageManager) != null) return true

        val generalIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return generalIntent.resolveActivity(context.packageManager) != null
    }

    fun createManageStorageIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val specificIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = "package:${context.packageName}".toUri()
        }
        if (specificIntent.resolveActivity(context.packageManager) != null) return specificIntent

        val generalIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return generalIntent.takeIf {
            it.resolveActivity(context.packageManager) != null
        }
    }

    fun missingRegularPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }

        return permissions
    }

    fun shouldUseManageStorageFlow(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && canRequestManageStorage()
    }
}
