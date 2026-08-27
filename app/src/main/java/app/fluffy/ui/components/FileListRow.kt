@file:OptIn(ExperimentalFoundationApi::class)

package app.fluffy.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.fluffy.io.FileSystemAccess
import app.fluffy.io.ShellEntry
import app.fluffy.io.ShellIo
import app.fluffy.ui.screens.AnimatedListCard
import app.fluffy.util.UiFormat.formatDate
import app.fluffy.util.UiFormat.formatSize
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as AndroidIcon
import androidx.core.net.toUri
import android.graphics.pdf.PdfRenderer
import kotlin.math.min

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

@Composable
fun FileTypeIcon(
    model: RowModel,
    modifier: Modifier = Modifier,
    showThumbnail: Boolean = true,
    thumbnailSizePx: Int = 128,
) {
    val ctx = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, model.uri, thumbnailSizePx) {
        value = withContext(Dispatchers.IO) {
            if (model.isImage) {
                loadImageThumbnail(ctx, model.uri, thumbnailSizePx)
            } else if (model.isVideo) {
                loadVideoThumbnail(ctx, model.uri, thumbnailSizePx)
            } else if (model.isPdf) {
                loadPdfThumbnail(ctx, model.uri, thumbnailSizePx)
            } else if (model.isAudio) {
                loadAudioThumbnail(ctx, model.uri, thumbnailSizePx)
            } else if (model.isApk) {
                loadApkIcon(ctx, model.uri, thumbnailSizePx)
            } else null
        }
    }

    if (showThumbnail && model.canShowThumbnail() && bitmap != null) {
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(bitmap)
                .crossfade(true)
                .build(),
            contentDescription = model.name,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        fallbackIcon(model, modifier)
    }
}

@Composable
private fun fallbackIcon(model: RowModel, modifier: Modifier) {
    Icon(
        imageVector = when {
            model.isDir -> Icons.Filled.Folder
            model.isArchive -> Icons.Filled.FolderZip
            model.isImage -> Icons.Filled.Image
            model.isVideo -> Icons.Filled.Movie
            model.isPdf -> Icons.Filled.PictureAsPdf
            model.isAudio -> Icons.Filled.MusicNote
            model.isApk -> Icons.Filled.FolderZip
            else -> Icons.AutoMirrored.Filled.InsertDriveFile
        },
        contentDescription = null,
        modifier = modifier,
        tint = when {
            model.isDir -> colorScheme.primary
            model.isArchive -> colorScheme.secondary
            model.isVideo -> colorScheme.tertiary
            model.isPdf -> colorScheme.error
            model.isAudio -> colorScheme.tertiary
            model.isApk -> colorScheme.primary
            else -> colorScheme.onSurfaceVariant
        }
    )
}

private fun loadImageThumbnail(ctx: Context, uri: Uri, size: Int): Bitmap? {
    return try {
        val inputStream = ctx.contentResolver.openInputStream(uri)
        inputStream?.use { stream ->
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeStream(stream)
            options.inJustDecodeBounds = false
            options.inSampleSize = calculateInSampleSize(options, size, size)
            options.inPreferredConfig = Bitmap.Config.RGB_565
            ctx.contentResolver.openInputStream(uri)?.use { stream2 ->
                BitmapFactory.decodeStream(stream2, null, options)
            }
        }
    } catch (e: Exception) {
        null
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    var inSampleSize = 1
    if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
        val halfHeight = options.outHeight / 2
        val halfWidth = options.outWidth / 2
        while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun scaleBitmap(bitmap: Bitmap, size: Int): Bitmap {
    val scale = min(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
    val width = (bitmap.width * scale).toInt()
    val height = (bitmap.height * scale).toInt()
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

private fun loadVideoThumbnail(ctx: Context, uri: Uri, size: Int): Bitmap? {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(ctx, uri)
        val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        retriever.release()
        bitmap?.let { scaleBitmap(it, size) }
    } catch (e: Exception) {
        null
    }
}

private fun loadPdfThumbnail(ctx: Context, uri: Uri, size: Int): Bitmap? {
    return try {
        val parcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: return null
        val renderer = PdfRenderer(parcelFileDescriptor)
        if (renderer.pageCount > 0) {
            val page = renderer.openPage(0)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val scale = min(size.toFloat() / page.width, size.toFloat() / page.height)
            val width = (page.width * scale).toInt()
            val height = (page.height * scale).toInt()
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
            page.render(scaledBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            parcelFileDescriptor.close()
            scaledBitmap
        } else {
            renderer.close()
            parcelFileDescriptor.close()
            null
        }
    } catch (e: Exception) {
        null
    }
}

private fun loadAudioThumbnail(ctx: Context, uri: Uri, size: Int): Bitmap? {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(ctx, uri)
        val art = retriever.getEmbeddedPicture()
        retriever.release()
        art?.let {
            BitmapFactory.decodeByteArray(it, 0, it.size)
                ?.let { scaleBitmap(it, size) }
        }
    } catch (e: Exception) {
        null
    }
}

private fun loadApkIcon(ctx: Context, uri: Uri, size: Int): Bitmap? {
    return try {
        val packageManager = ctx.packageManager
        val path = if (uri.scheme == "file") uri.path else uri.toString()
        path?.let { path ->
            val packageArchiveInfo = packageManager.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES)
            packageArchiveInfo?.applicationInfo?.loadIcon(packageManager)
                ?.let { (it as android.graphics.drawable.BitmapDrawable).bitmap }
                ?.let { scaleBitmap(it, size) }
        }
    } catch (e: Exception) {
        null
    }
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
    onOpenWith: (Uri, String) -> Unit,
    onClick: (() -> Unit)? = null,
    onExtractHere: (() -> Unit)? = null
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
                        if (onClick != null) {
                            onClick()
                        } else {
                            when {
                                model.isDir -> onOpenDir(model.uri)
                                hasSelection -> onToggleSelect(!selected)
                                model.isArchive -> onOpenArchive(model.uri)
                                else -> onOpenWith(model.uri, model.name)
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
                    thumbnailSizePx = 96,
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
                                "Folder"
                            } else {
                                when (val c = dirCount) {
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
    onOpenWith: (Uri, String) -> Unit,
    onClick: (() -> Unit)? = null,
    onExtractHere: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val dirCount by produceState<Int?>(initialValue = null, model.uri, showFileCount) {
        value = if (showFileCount && model.isDir) DirectoryCounter.count(ctx, model.uri) else null
    }

    val mainFR = remember { FocusRequester() }

    AnimatedListCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                            else -> onOpenWith(model.uri, model.name)
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
                    thumbnailSizePx = 320,
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
                )

                if (model.isArchive && onExtractHere != null) {
                    IconButton(
                        onClick = onExtractHere,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Unarchive,
                            contentDescription = "Extract here",
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
                    if (!showFileCount) "Folder"
                    else when (val c = dirCount) {
                        null -> "…"
                        1 -> "1 item"
                        else -> "$c items"
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
    private val cache = ConcurrentHashMap<String, Int>()
    private val shellIo: ShellIo by inject()

    suspend fun count(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val key = uri.toString()
        cache[key]?.let { return@withContext it }

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
                    } catch (_: UnsupportedOperationException) {
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

        cache[key] = n
        n
    }

    fun invalidate(uri: Uri) {
        cache.remove(uri.toString())
    }

    fun invalidateAll() {
        cache.clear()
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