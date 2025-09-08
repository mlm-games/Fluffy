@file:OptIn(ExperimentalFoundationApi::class)

package app.fluffy.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.fluffy.io.FileSystemAccess
import app.fluffy.io.ShellEntry
import app.fluffy.io.ShellIo
import app.fluffy.ui.screens.AnimatedListCard
import app.fluffy.util.UiFormat.formatDate
import app.fluffy.util.UiFormat.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class RowModel(
    val name: String,
    val uri: Uri,
    val isDir: Boolean,
    val isArchive: Boolean,
    val isImage: Boolean,
    val subtitle: String
)

fun File.toRowModel(): RowModel = RowModel(
    name = name,
    uri = Uri.fromFile(this),
    isDir = isDirectory,
    isArchive = if (isDirectory) false else FileSystemAccess.isArchiveFile(name),
    isImage = if (isDirectory) false else FileSystemAccess.getMimeType(name).startsWith("image/"),
    subtitle = if (isDirectory) {
        "Folder • ${formatDate(lastModified())}" // Dupli code to remove
    } else {
        "${formatSize(length())} • ${formatDate(lastModified())}"
    }
)

fun DocumentFile.toRowModel(): RowModel {
    val n = name ?: "item"
    val dir = isDirectory
    val mime = type ?: "application/octet-stream"
    return RowModel(
        name = n,
        uri = uri,
        isDir = dir,
        isArchive = if (dir) false else FileSystemAccess.isArchiveFile(n),
        isImage = if (dir) false else mime.startsWith("image/") || FileSystemAccess.getMimeType(n).startsWith("image/"),
        subtitle = if (dir) "Folder" else (type ?: "file")
    )
}

fun ShellEntry.toRowModel(): RowModel = RowModel(
    name = name,
    uri = uri,
    isDir = isDir,
    isArchive = if (isDir) false else FileSystemAccess.isArchiveFile(name),
    isImage = if (isDir) false else FileSystemAccess.getMimeType(name).startsWith("image/"),
    subtitle = if (isDir) "Folder" else uri.toString()
)

@Composable
fun FileListRow(
    model: RowModel,
    selected: Boolean,
    hasSelection: Boolean,
    showFileCount: Boolean,
    onToggleSelect: (Boolean) -> Unit,
    onOpenDir: (Uri) -> Unit,
    onOpenArchive: (Uri) -> Unit,
    onOpenWith: (Uri, String) -> Unit,
    onExtractHere: (() -> Unit)? = null // only shown when archive
) {
    val ctx = LocalContext.current

    val dirCount by produceState<Int?>(initialValue = null, model.uri, showFileCount) {
        value = if (showFileCount && model.isDir) DirectoryCounter.count(ctx, model.uri) else null
    }


    val mainFR = remember { FocusRequester() }
    val rightFR = remember { FocusRequester() }
    val cbFR = remember { FocusRequester() }

    AnimatedListCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .focusProperties { canFocus = false }
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(mainFR)
                    .focusable()
                    .semantics { role = Role.Button }
                    .clickable {
                        when {
                            model.isDir -> onOpenDir(model.uri)
                            hasSelection -> onToggleSelect(!selected)
                            model.isArchive -> onOpenArchive(model.uri)
                            else -> onOpenWith(model.uri, model.name)
                        }
                    }
                    .focusProperties { right = if (model.isArchive && onExtractHere != null) rightFR else cbFR },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        model.isDir -> Icons.Filled.Folder
                        model.isArchive -> Icons.Filled.FolderZip
                        model.isImage -> Icons.Filled.Image
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = when {
                        model.isDir -> colorScheme.primary
                        model.isArchive -> colorScheme.secondary
                        else -> colorScheme.onSurfaceVariant
                    }
                )
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        model.name,
                        style = typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitleText =
                        if (model.isDir) {
                            if (!showFileCount) {
                                "Folder"
                            } else {
                                val c = dirCount
                                when (c) {
                                    null -> "…"
                                    1 -> "1 item"
                                    else -> "$c items"
                                }
                            }
                        } else {
                            model.subtitle
                        }

                    Text(
                        subtitleText,
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (model.isArchive && onExtractHere != null) {
                IconButton(
                    onClick = onExtractHere,
                    modifier = Modifier
                        .focusRequester(rightFR)
                        .focusable()
                        .focusProperties { left = mainFR; right = cbFR }
                ) {
                    Icons.Filled.Unarchive
                    Icon(
                        imageVector = Icons.Filled.Unarchive,
                        contentDescription = "Extract here",
                        tint = colorScheme.primary
                    )
                }
            }

            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect(it) },
                modifier = Modifier
                    .focusRequester(cbFR)
                    .focusable()
                    .semantics { role = Role.Checkbox }
                    .focusProperties {
                        left = if (model.isArchive && onExtractHere != null) rightFR else mainFR
                    }
            )
        }
    }
}

private object DirectoryCounter {
    private val cache = ConcurrentHashMap<String, Int>()

    suspend fun count(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val key = uri.toString()
        cache[key]?.let { return@withContext it }

        val n = when (uri.scheme) {
            "file" -> {
                val f = File(requireNotNull(uri.path))
                f.listFiles()?.size ?: 0
            }
            "content" -> {
                val doc = DocumentFile.fromSingleUri(context, uri)
                    ?: DocumentFile.fromTreeUri(context, uri)
                doc?.listFiles()?.size ?: 0
            }
            "root" -> {
                val p = uri.path ?: "/"
                ShellIo.listRoot(p).size
            }
            "shizuku" -> {
                val p = uri.path ?: "/"
                ShellIo.listShizuku(p).size
            }
            else -> 0
        }

        cache[key] = n
        n
    }
}
