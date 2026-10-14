package app.fluffy.ui.viewers

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.fluffy.R
import app.fluffy.helper.shareWithFolders
import app.fluffy.io.DocumentController
import app.fluffy.io.SafIo
import app.fluffy.ui.components.ConfirmationDialog
import app.fluffy.ui.components.DirectoryCounter
import app.fluffy.ui.components.ItemPropertiesDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun ViewerActionBar(
    uri: Uri,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = LocalContentColor.current,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val io: SafIo = koinInject()
    val direct = remember(uri) { DocumentController.directFile(uri) }
    val displayName = remember(uri, direct) { direct?.name ?: io.queryDisplayName(uri) }
    val propsUri = remember(uri, direct) { direct?.let(Uri::fromFile) ?: uri }

    var menuOpen by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(modifier) {
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more), tint = iconTint)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.info)) },
                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    showInfo = true
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share)) },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    scope.launch {
                        runCatching { context.shareWithFolders(listOf(propsUri to displayName)) }
                            .onFailure { error = it.message ?: it.javaClass.simpleName }
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    showDeleteConfirm = true
                }
            )
        }
    }

    if (showInfo) {
        ItemPropertiesDialog(uris = listOf(propsUri), onDismiss = { showInfo = false })
    }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.delete),
            message = context.getString(R.string.delete_confirm, displayName),
            onConfirm = {
                showDeleteConfirm = false
                scope.launch {
                    val removed = withContext(Dispatchers.IO) {
                        val gone = direct?.let { !it.isDirectory && it.delete() } ?: false
                        gone || io.deleteTree(uri)
                    }
                    DirectoryCounter.invalidateParent(uri)
                    if (removed) onDeleted() else error = context.getString(R.string.delete_failed)
                }
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    error?.let { message ->
        ConfirmationDialog(
            title = stringResource(R.string.error),
            message = message,
            onConfirm = { error = null },
            onDismiss = { error = null }
        )
    }
}