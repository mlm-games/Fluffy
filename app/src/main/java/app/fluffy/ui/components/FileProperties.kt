package app.fluffy.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.fluffy.R
import app.fluffy.helper.DeviceUtils
import app.fluffy.io.FileSystemAccess
import app.fluffy.util.FileMetadata
import app.fluffy.util.MetaKey
import app.fluffy.util.MetaRow
import app.fluffy.util.UiFormat.formatDate
import app.fluffy.util.UiFormat.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ItemProperties(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
    val mimeType: String? = null,
    val childCount: Int? = null,
    val canRead: Boolean? = null,
    val canWrite: Boolean? = null,
)

fun resolveItemProperties(context: Context, uri: Uri): ItemProperties {
    return when (uri.scheme) {
        "file" -> {
            val path = uri.path
            if (path.isNullOrBlank()) {
                ItemProperties(name = uri.toString(), path = uri.toString(), isDir = false)
            } else {
                val f = File(path)
                val dir = f.isDirectory
                ItemProperties(
                    name = f.name.ifBlank { f.absolutePath },
                    path = f.absolutePath,
                    isDir = dir,
                    sizeBytes = if (dir) null else runCatching { f.length() }.getOrNull(),
                    lastModified = f.lastModified().takeIf { it > 0L },
                    mimeType = if (dir) null else FileSystemAccess.getMimeType(f.name),
                    childCount = if (dir) runCatching { f.listFiles()?.size }.getOrNull() else null,
                    canRead = runCatching { f.canRead() }.getOrNull(),
                    canWrite = runCatching { f.canWrite() }.getOrNull(),
                )
            }
        }
        "content" -> {
            val doc = DocumentFile.fromSingleUri(context, uri)
                ?: DocumentFile.fromTreeUri(context, uri)
            if (doc == null) {
                ItemProperties(name = uri.lastPathSegment ?: "item", path = uri.toString(), isDir = false)
            } else {
                val dir = doc.isDirectory
                val name = doc.name ?: (uri.lastPathSegment ?: "item")
                val childCount = if (dir) {
                    runCatching { doc.listFiles().size }.getOrNull()
                } else null
                ItemProperties(
                    name = name,
                    path = uri.toString(),
                    isDir = dir,
                    sizeBytes = if (dir) null else doc.length().takeIf { it >= 0 },
                    lastModified = doc.lastModified().takeIf { it > 0L },
                    mimeType = if (dir) null else (doc.type ?: FileSystemAccess.getMimeType(name)),
                    childCount = childCount,
                    canRead = doc.canRead(),
                    canWrite = doc.canWrite(),
                )
            }
        }
        "root", "shizuku" -> {
            val p = uri.path ?: "/"
            val name = p.trimEnd('/').substringAfterLast('/').ifBlank { p }
            ItemProperties(
                name = name,
                path = uri.toString(),
                isDir = true,
                sizeBytes = null,
                lastModified = null,
                mimeType = null,
                childCount = null,
                canRead = null,
                canWrite = null,
            )
        }
        else -> ItemProperties(
            name = uri.lastPathSegment ?: uri.toString(),
            path = uri.toString(),
            isDir = false
        )
    }
}

private fun resolveItemProperties(context: Context, uri: Uri, knownIsDir: Boolean?): ItemProperties {
    val base = resolveItemProperties(context, uri)
    return if (knownIsDir != null && (uri.scheme == "root" || uri.scheme == "shizuku")) {
        base.copy(isDir = knownIsDir)
    } else base
}

internal data class ItemDetails(val props: ItemProperties, val extra: List<MetaRow>)

private suspend fun resolveDetails(
    context: Context,
    uri: Uri,
    knownIsDir: Boolean?,
    withMetadata: Boolean
): ItemDetails {
    val props = resolveItemProperties(context, uri, knownIsDir)
    if (!withMetadata || props.isDir) return ItemDetails(props, emptyList())
    val extra = FileMetadata.collect(context, uri, props.name, props.mimeType)
    return ItemDetails(props, extra)
}

private fun labelFor(key: MetaKey): Int = when (key) {
    MetaKey.DIMENSIONS -> R.string.meta_dimensions
    MetaKey.CAMERA_MAKE -> R.string.meta_camera_make
    MetaKey.CAMERA_MODEL -> R.string.meta_camera_model
    MetaKey.EXPOSURE_TIME -> R.string.meta_exposure
    MetaKey.F_NUMBER -> R.string.meta_fnumber
    MetaKey.ISO -> R.string.meta_iso
    MetaKey.FOCAL_LENGTH -> R.string.meta_focal_length
    MetaKey.DATE_TAKEN -> R.string.meta_date_taken
    MetaKey.GPS -> R.string.meta_gps
    MetaKey.DURATION -> R.string.meta_duration
    MetaKey.BITRATE -> R.string.meta_bitrate
    MetaKey.RESOLUTION -> R.string.meta_resolution
    MetaKey.FRAME_RATE -> R.string.meta_frame_rate
    MetaKey.ARTIST -> R.string.meta_artist
    MetaKey.TITLE -> R.string.meta_title
    MetaKey.ALBUM -> R.string.meta_album
    MetaKey.GENRE -> R.string.meta_genre
    MetaKey.YEAR -> R.string.meta_year
    MetaKey.APP_LABEL -> R.string.meta_app_label
    MetaKey.APP_VERSION -> R.string.meta_app_version
    MetaKey.ENTRIES -> R.string.meta_entries
    MetaKey.SIZE_UNCOMPRESSED -> R.string.meta_size_uncompressed
    MetaKey.PERMISSIONS -> R.string.meta_permissions
    MetaKey.OWNER -> R.string.meta_owner
    MetaKey.GROUP -> R.string.meta_group
}

@Composable
fun ItemPropertiesDialog(
    uris: List<Uri>,
    onDismiss: () -> Unit,
    knownIsDir: (Uri) -> Boolean? = { null },
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val contentFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val details by produceState<List<ItemDetails>?>(initialValue = null, uris) {
        val single = uris.size == 1
        value = withContext(Dispatchers.IO) {
            uris.map { uri -> resolveDetails(context, uri, knownIsDir(uri), single) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (uris.size == 1) stringResource(R.string.properties)
                else stringResource(R.string.selection_summary)
            )
        },
        text = {
            when (val list = details) {
                null -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> {
                    TvDialogScrollBox(
                        scroll = scroll,
                        contentFocus = contentFocus,
                        confirmFocus = confirmFocus,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (list.size == 1) {
                            val p = list.first().props
                            PropertyRow(stringResource(R.string.name), p.name)
                            PropertyRow(
                                stringResource(R.string.type),
                                if (p.isDir) stringResource(R.string.folder) else stringResource(R.string.file)
                            )
                            p.sizeBytes?.let {
                                PropertyRow(stringResource(R.string.size), formatSize(it))
                            }
                            p.childCount?.let {
                                PropertyRow(stringResource(R.string.items), it.toString())
                            }
                            p.mimeType?.let {
                                PropertyRow(stringResource(R.string.mime_type), it)
                            }
                            p.lastModified?.let {
                                PropertyRow(
                                    stringResource(R.string.modified),
                                    formatDate(it, "MMM dd, yyyy HH:mm")
                                )
                            }
                            PropertyRow(stringResource(R.string.path), p.path)
                            p.canRead?.let {
                                PropertyRow(
                                    stringResource(R.string.readable),
                                    if (it) stringResource(R.string.yes) else stringResource(R.string.no)
                                )
                            }
                            p.canWrite?.let {
                                PropertyRow(
                                    stringResource(R.string.writable),
                                    if (it) stringResource(R.string.yes) else stringResource(R.string.no)
                                )
                            }
                            val extra = list.first().extra
                            if (extra.isNotEmpty()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                                extra.forEach { row ->
                                    PropertyRow(stringResource(labelFor(row.key)), row.value)
                                }
                            }
                        } else {
                            val props = list.map { it.props }
                            val folders = props.count { it.isDir }
                            val files = props.size - folders
                            val totalSize = props.mapNotNull { it.sizeBytes }.sum()
                            Text(
                                stringResource(R.string.folders_count, folders),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                stringResource(R.string.files_count, files),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (totalSize > 0L) {
                                Text(
                                    stringResource(R.string.total_size, formatSize(totalSize)),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            props.take(20).forEach { p ->
                                Text(
                                    "• ${p.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (props.size > 20) {
                                Text(
                                    "… +${props.size - 20}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            DialogCloseButton(onDismiss = onDismiss, contentFocus = contentFocus, confirmFocus = confirmFocus)
        }
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    if (value.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun TvDialogScrollBox(
    scroll: ScrollState,
    contentFocus: FocusRequester,
    confirmFocus: FocusRequester,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val ctx = LocalContext.current
    val isTv = remember { DeviceUtils.isTV(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .then(
                    if (isTv) Modifier
                        .focusRequester(contentFocus)
                        .focusable()
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.DirectionUp -> {
                                    if (scroll.value > 0) {
                                        scope.launch {
                                            scroll.animateScrollTo((scroll.value - 200).coerceAtLeast(0))
                                        }
                                        true
                                    } else false
                                }
                                Key.DirectionDown -> {
                                    if (scroll.value < scroll.maxValue) {
                                        scope.launch {
                                            scroll.animateScrollTo((scroll.value + 200).coerceAtMost(scroll.maxValue))
                                        }
                                        true
                                    } else {
                                        scope.launch { confirmFocus.requestFocus() }
                                        true
                                    }
                                }
                                else -> false
                            }
                        }
                    else Modifier
                )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = verticalArrangement
            ) {
                content()
            }
        }
        if (isTv && focused) {
            if (scroll.canScrollBackward) {
                Text(
                    "↑",
                    modifier = Modifier.align(Alignment.TopCenter),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (scroll.canScrollForward) {
                Text(
                    "↓",
                    modifier = Modifier.align(Alignment.BottomCenter),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun DialogCloseButton(
    onDismiss: () -> Unit,
    contentFocus: FocusRequester,
    confirmFocus: FocusRequester
) {
    val scope = rememberCoroutineScope()
    TextButton(
        onClick = onDismiss,
        modifier = Modifier
            .focusRequester(confirmFocus)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                    scope.launch { contentFocus.requestFocus() }
                    true
                } else false
            }
    ) { Text(stringResource(R.string.close)) }
}