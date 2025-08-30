package app.fluffy.io

import android.net.Uri

data class ShellEntry(
    val name: String,
    val isDir: Boolean,
    val uri: Uri
)
