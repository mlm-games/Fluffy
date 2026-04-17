package app.fluffy.viewmodel

import android.net.Uri

sealed interface PendingAction {
    data object None : PendingAction

    data class OpenFile(val uri: Uri) : PendingAction

    data class OpenArchive(val uri: Uri) : PendingAction

    data class Copy(val uris: List<Uri>) : PendingAction

    data class Move(val uris: List<Uri>) : PendingAction

    data class Extract(
        val archive: Uri,
        val password: String?,
        val includePaths: List<String>?
    ) : PendingAction
}
