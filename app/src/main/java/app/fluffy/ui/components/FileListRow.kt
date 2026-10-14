@file:OptIn(ExperimentalFoundationApi::class)

package app.fluffy.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.fluffy.R
import app.fluffy.image.GeneratedThumbnail
import app.fluffy.image.GeneratedThumbnailKind
import app.fluffy.io.FileSystemAccess
import app.fluffy.io.ShellEntry
import app.fluffy.io.ShellIo
import app.fluffy.ui.screens.AnimatedListCard
import app.fluffy.util.AppLog
import app.fluffy.util.UiFormat.formatDate
import app.fluffy.util.UiFormat.formatSize
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private val pdfDarkModeMatrix = ColorMatrix(
    floatArrayOf(
        -0.299f, -0.587f, -0.114f, 0f, 255f,
        -0.299f, -0.587f, -0.114f, 0f, 232f,
        -0.299f, -0.587f, -0.114f, 0f, 184f,
        0f, 0f, 0f, 1f, 0f
    )
)

data class RowModel(
    val name: String,
    val uri: Uri,
    val isDir: Boolean,
    val isArchive: Boolean,
    val isImage: Boolean,
    val isVideo: Boolean,
    val isPdf: Boolean,
    val isAudio: Boolean,
    val isApk: Boolean,
    val subtitle: String
)

fun rowModelForSearchHit(hit: app.fluffy.search.SearchHit): RowModel = RowModel(
    name = hit.name,
    uri = hit.uri,
    isDir = hit.isDir,
    isArchive = !hit.isDir && FileSystemAccess.isArchiveFile(hit.name),
    isImage = !hit.isDir && FileSystemAccess.getMimeType(hit.name).startsWith("image/"),
    isVideo = !hit.isDir && FileSystemAccess.getMimeType(hit.name).startsWith("video/"),
    isPdf = !hit.isDir && FileSystemAccess.getMimeType(hit.name).startsWith("application/pdf"),
    isAudio = !hit.isDir && FileSystemAccess.getMimeType(hit.name).startsWith("audio/"),
    isApk = !hit.isDir && hit.name.lowercase().endsWith(".apk"),
    subtitle = hit.parentPath
)

fun File.toRowModel(): RowModel = RowModel(
    name = name,
    uri = Uri.fromFile(this),
    isDir = isDirectory,
    isArchive = if (isDirectory) false else FileSystemAccess.isArchiveFile(name),
    isImage = if (isDirectory) false else FileSystemAccess.getMimeType(name).startsWith("image/"),
    isVideo = if (isDirectory) false else FileSystemAccess.getMimeType(name).startsWith("video/"),
    isPdf = if (isDirectory) false else FileSystemAccess.getMimeType(name).startsWith("application/pdf"),
    isAudio = if (isDirectory) false else FileSystemAccess.getMimeType(name).startsWith("audio/"),
    isApk = if (isDirectory) false else name.lowercase().endsWith(".apk"),
    subtitle = if (isDirectory) {
        "Folder • ${formatDate(lastModified())}"
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
        isImage = if (dir) false else mime.startsWith("image/") ||
            FileSystemAccess.getMimeType(n).startsWith("image/"),
        isVideo = if (dir) false else mime.startsWith("video/") ||
            FileSystemAccess.getMimeType(n).startsWith("video/"),
        isPdf = if (dir) false else mime.startsWith("application/pdf") ||
            FileSystemAccess.getMimeType(n).startsWith("application/pdf"),
        isAudio = if (dir) false else mime.startsWith("audio/") ||
            FileSystemAccess.getMimeType(n).startsWith("audio/"),
        isApk = if (dir) false else n.lowercase().endsWith(".apk"),
        subtitle = if (dir) "Folder" else (type ?: "file")
    )
}

fun ShellEntry.toRowModel(): RowModel = RowModel(
    name = name,
    uri = uri,
    isDir = isDir,
    isArchive = if (isDir) false else FileSystemAccess.isArchiveFile(name),
    isImage = if (isDir) false else FileSystemAccess.getMimeType(name).startsWith("image/"),
    isVideo = if (isDir) false else FileSystemAccess.getMimeType(name).startsWith("video/"),
    isPdf = if (isDir) false else FileSystemAccess.getMimeType(name).startsWith("application/pdf"),
    isAudio = if (isDir) false else FileSystemAccess.getMimeType(name).startsWith("audio/"),
    isApk = if (isDir) false else name.lowercase().endsWith(".apk"),
    subtitle = if (isDir) "Folder" else uri.toString()
)

fun RowModel.canShowThumbnail(): Boolean =
    (isImage || isVideo || isPdf || isAudio || isApk) && (uri.scheme == "file" || uri.scheme == "content")

private fun RowModel.thumbnailData(): Any = when {
    isPdf -> GeneratedThumbnail(uri, GeneratedThumbnailKind.PDF_PAGE)
    isAudio -> GeneratedThumbnail(uri, GeneratedThumbnailKind.AUDIO_ART)
    isApk -> GeneratedThumbnail(uri, GeneratedThumbnailKind.APK_ICON)
    else -> uri
}

private fun RowModel.fallbackVector(): ImageVector = when {
    isDir -> Icons.Filled.Folder
    isArchive -> Icons.Filled.FolderZip
    isImage -> Icons.Filled.Image
    isVideo -> Icons.Filled.Movie
    isPdf -> Icons.Filled.PictureAsPdf
    isAudio -> Icons.Filled.MusicNote
    isApk -> Icons.Filled.FolderZip
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun RowModel.fallbackTint(): Color = when {
    isDir -> colorScheme.primary
    isArchive -> colorScheme.secondary
    isVideo -> colorScheme.tertiary
    isPdf -> colorScheme.error
    isAudio -> colorScheme.tertiary
    isApk -> colorScheme.primary
    else -> colorScheme.onSurfaceVariant
}

@Composable
fun FileTypeIcon(
    model: RowModel,
    modifier: Modifier = Modifier,
    showThumbnail: Boolean = true,
) {
    if (!showThumbnail || !model.canShowThumbnail()) {
        fallbackIcon(model, modifier)
        return
    }

    val ctx = LocalContext.current
    val request = remember(model, ctx) {
        ImageRequest.Builder(ctx)
            .data(model.thumbnailData())
            .precision(Precision.INEXACT)
            .crossfade(true)
            .build()
    }

    Box(modifier = modifier) {
        Icon(
            imageVector = model.fallbackVector(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            tint = model.fallbackTint(),
        )
        AsyncImage(
            model = request,
            contentDescription = model.name,
            imageLoader = ctx.imageLoader,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            colorFilter = if (model.isPdf && isSystemInDarkTheme()) {
                ColorFilter.colorMatrix(pdfDarkModeMatrix)
            } else {
                null
            },
        )
    }
}

@Composable
private fun fallbackIcon(model: RowModel, modifier: Modifier) {
    Icon(
        imageVector = model.fallbackVector(),
        contentDescription = null,
        modifier = modifier,
        tint = model.fallbackTint(),
    )
}

@Composable
fun FileListRow(
    model: RowModel,
    selected: Boolean,
    hasSelection: Boolean,
    showFileCount: Boolean,
    showThumbnail: Boolean = true,
    onToggleSelect: (Boolean) -> Unit,
    onOpenDir: (Uri) -> Unit,
    onOpenArchive: (Uri) -> Unit,
    onOpenContent: (Uri, String) -> Unit,
    onOpenWith: (Uri, String) -> Unit,
    onClick: (() -> Unit)? = null,
    onExtractHere: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val generation by DirectoryCounter.generation.collectAsState()

    val dirCount by produceState<Int?>(initialValue = null, model.uri, showFileCount, generation) {
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
                        if (onClick != null) {
                            onClick()
                        } else {
                            when {
                                model.isDir -> onOpenDir(model.uri)
                                hasSelection -> onToggleSelect(!selected)
                                model.isArchive -> onOpenArchive(model.uri)
                                else -> onOpenContent(model.uri, model.name)
                            }
                        }
                    }
                    .focusProperties {
                        right = if (model.isArchive && onExtractHere != null) rightFR else cbFR
                    },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FileTypeIcon(
                    model = model,
                    showThumbnail = showThumbnail,
                    modifier = Modifier.size(40.dp)
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
                                stringResource(R.string.folder)
                            } else {
                                when (val c = dirCount) {
                                    null -> "…"
                                    else -> pluralStringResource(R.plurals.item_count, c, c)
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
                    Icon(
                        imageVector = Icons.Filled.Unarchive,
                        contentDescription = stringResource(R.string.extract_here),
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

@Composable
fun FileGridItem(
    model: RowModel,
    selected: Boolean,
    hasSelection: Boolean,
    showFileCount: Boolean,
    showThumbnail: Boolean = true,
    onToggleSelect: (Boolean) -> Unit,
    onOpenDir: (Uri) -> Unit,
    onOpenArchive: (Uri) -> Unit,
    onOpenContent: (Uri, String) -> Unit,
    onOpenWith: (Uri, String) -> Unit,
    onClick: (() -> Unit)? = null,
    onExtractHere: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val generation by DirectoryCounter.generation.collectAsState()
    val dirCount by produceState<Int?>(initialValue = null, model.uri, showFileCount, generation) {
        value = if (showFileCount && model.isDir) DirectoryCounter.count(ctx, model.uri) else null
    }

    val mainFR = remember { FocusRequester() }
    val cbFR = remember { FocusRequester() }
    val extractFR = remember { FocusRequester() }

    AnimatedListCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(mainFR)
                .focusable()
                .focusProperties {
                    down = if (model.isArchive && onExtractHere != null) extractFR else cbFR
                }
                .semantics { role = Role.Button }
                .clickable {
                    if (onClick != null) {
                        onClick()
                    } else {
                        when {
                            model.isDir -> onOpenDir(model.uri)
                            hasSelection -> onToggleSelect(!selected)
                            model.isArchive -> onOpenArchive(model.uri)
                            else -> onOpenContent(model.uri, model.name)
                        }
                    }
                }
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                FileTypeIcon(
                    model = model,
                    showThumbnail = showThumbnail,
                    modifier = if (showThumbnail && model.canShowThumbnail()) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.size(48.dp)
                    }
                )

                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect(it) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .focusRequester(cbFR)
                        .focusable()
                        .focusProperties {
                            up = mainFR
                            down = if (model.isArchive && onExtractHere != null) extractFR else mainFR
                        }
                )

                if (model.isArchive && onExtractHere != null) {
                    IconButton(
                        onClick = onExtractHere,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .focusRequester(extractFR)
                            .focusable()
                            .focusProperties { up = cbFR }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Unarchive,
                            contentDescription = stringResource(R.string.extract_here),
                            tint = colorScheme.primary
                        )
                    }
                }
            }

            Text(
                model.name,
                style = typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )

            val subtitleText =
                if (model.isDir) {
                    if (!showFileCount) stringResource(R.string.folder)
                    else when (val c = dirCount) {
                        null -> "…"
                        else -> pluralStringResource(R.plurals.item_count, c, c)
                    }
                } else model.subtitle

            Text(
                subtitleText,
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

object DirectoryCounter : KoinComponent {
    private val cache = object : LinkedHashMap<String, Int>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean = size > 500
    }
    private val lock = Any()
    private val shellIo: ShellIo by inject()
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation

    suspend fun count(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val key = uri.toString()
        synchronized(lock) { cache[key] }?.let { return@withContext it }

        val n = when (uri.scheme) {
            "file" -> {
                val f = File(requireNotNull(uri.path))
                f.listFiles()?.size ?: 0
            }
            "content" -> {
                val doc = DocumentFile.fromTreeUri(context, uri)
                    ?: DocumentFile.fromSingleUri(context, uri)

                if (doc == null || !doc.isDirectory) {
                    0
                } else {
                    try {
                        doc.listFiles().size
                    } catch (e: UnsupportedOperationException) {
                        AppLog.d("FileListRow", "doc.listFiles unsupported: $uri", e)
                        0
                    }
                }
            }
            "root" -> {
                val p = uri.path ?: "/"
                shellIo.listRoot(p).size
            }
            "shizuku" -> {
                val p = uri.path ?: "/"
                shellIo.listShizuku(p).size
            }
            else -> 0
        }

        synchronized(lock) { cache[key] = n }
        n
    }

    fun invalidate(uri: Uri) {
        synchronized(lock) { cache.remove(uri.toString()) }
        _generation.value++
    }

    fun invalidateAll() {
        synchronized(lock) { cache.clear() }
        _generation.value++
    }

    fun invalidateParent(uri: Uri) {
        when (uri.scheme) {
            "file" -> {
                val file = File(uri.path!!)
                val parent = file.parentFile
                parent?.let { invalidate(Uri.fromFile(it)) }
            }
            "content" -> {
                invalidateAll()
            }
            "root", "shizuku" -> {
                val path = uri.path ?: "/"
                val parentPath = File(path).parent ?: "/"
                invalidate("${uri.scheme}://$parentPath".toUri())
            }
        }
    }
}
