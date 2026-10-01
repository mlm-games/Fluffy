package app.fluffy.io

import android.net.Uri

data class ChildRef(
    val name: String,
    val uri: Uri,
    val isDir: Boolean
)
