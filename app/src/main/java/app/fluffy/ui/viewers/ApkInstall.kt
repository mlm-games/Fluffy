package app.fluffy.ui.viewers

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.fluffy.R
import java.io.File

fun Context.installApk(uri: Uri, direct: File?) {
    val installUri = when {
        direct != null -> FileProvider.getUriForFile(this, "$packageName.fileprovider", direct)
        uri.scheme == "file" -> FileProvider.getUriForFile(this, "$packageName.fileprovider", File(uri.path!!))
        else -> uri
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(installUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (intent.resolveActivity(packageManager) == null) {
        throw IllegalStateException(getString(R.string.no_installer_found))
    }
    startActivity(intent)
}