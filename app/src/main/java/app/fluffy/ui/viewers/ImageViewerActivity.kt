package app.fluffy.ui.viewers

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.fluffy.AppGraph
import app.fluffy.data.repository.AppSettings
import app.fluffy.io.ShellIo
import app.fluffy.ui.theme.FluffyTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.io.File

class ImageViewerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_IMAGES = "images"
        const val EXTRA_INITIAL_INDEX = "initial"
        const val EXTRA_TITLE = "title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)

        val fromExtras = intent.getStringArrayListExtra(EXTRA_IMAGES)?.mapNotNull { runCatching { it.toUri() }.getOrNull() }.orEmpty()
        val fromData = intent.data?.let { listOf(it) }.orEmpty()
        val fromClip = buildList {
            intent.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) {
                    cd.getItemAt(i)?.uri?.let { add(it) }
                }
            }
        }

        var allUris = (fromExtras + fromData + fromClip).distinct()
        var startIndex = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0)

        // If we only have one image, try to find siblings in the directory
        if (allUris.size == 1) {
            val singleUri = allUris.first()
            val originalName = getFileName(singleUri)
            val discovered = discoverSiblingImages(singleUri)
            if (discovered.size > 1) {
                allUris = discovered
                startIndex = startIndexFor(discovered, singleUri, originalName)
            }
        }

        if (allUris.isEmpty()) {
            finish()
            return
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: deriveTitle(allUris, startIndex)

        setContent {
            val settings = AppGraph.settings.settingsFlow.collectAsState(initial = AppSettings()).value
            val dark = when (settings.themeMode) {
                0 -> isSystemInDarkTheme()
                1 -> false
                else -> true
            }
            FluffyTheme(darkTheme = dark, useAuroraTheme = settings.useAuroraTheme) {
                FullscreenImageViewer(
                    images = allUris.map { it.toString() },
                    initialPage = startIndex,
                    onClose = { finish() }
                )
            }
        }
    }

    private fun discoverSiblingImages(uri: Uri): List<Uri> {
        val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif")

        fun isImage(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in imageExtensions
        }

        return when (uri.scheme) {
            "file" -> {
                // For file:// URIs, we can directly access the directory
                val file = File(uri.path ?: return listOf(uri))
                val parent = file.parentFile ?: return listOf(uri)

                if (!parent.canRead()) return listOf(uri)

                parent.listFiles()
                    ?.filter { it.isFile && isImage(it.name) }
                    ?.sortedBy { it.name.lowercase() }
                    ?.map { Uri.fromFile(it) }
                    ?: listOf(uri)
            }

            "content" -> {

                // Approach 1: Try to get the actual file path and use file system access
                val filePath = getFilePathFromContentUri(uri)
                if (filePath != null) {
                    val file = File(filePath)
                    val parent = file.parentFile
                    if (parent?.canRead() == true) {
                        val images = parent.listFiles()
                            ?.filter { it.isFile && isImage(it.name) }
                            ?.sortedBy { it.name.lowercase() }
                            ?.map {
                                // Try to get content URI for each file, fallback to file URI
                                tryGetContentUri(it) ?: Uri.fromFile(it)
                            }
                            ?: listOf(uri)

                        if (images.size > 1) return images
                    }
                }

                // Approach 2: Check if this is a media content URI and query MediaStore
                val mediaImages = tryQueryMediaStore(uri)
                if (mediaImages.size > 1) return mediaImages

                // Fallback
                listOf(uri)
            }

            "root", "shizuku" -> {
                // For root/shizuku URIs, use ShellIo to list directory
                try {
                    val path = uri.path ?: return listOf(uri)
                    val parentPath = File(path).parent ?: return listOf(uri)

                    val siblings = when (uri.scheme) {
                        "root" -> ShellIo.listRoot(parentPath)
                        "shizuku" -> ShellIo.listShizuku(parentPath)
                        else -> emptyList()
                    }

                    siblings
                        .filter { (name, isDir) -> !isDir && isImage(name) }
                        .sortedBy { (name, _) -> name.lowercase() }
                        .map { (name, _) ->
                            Uri.Builder()
                                .scheme(uri.scheme)
                                .path("$parentPath/$name")
                                .build()
                        }
                        .takeIf { it.isNotEmpty() }
                        ?: listOf(uri)
                } catch (_: Exception) {
                    listOf(uri)
                }
            }

            else -> listOf(uri)
        }
    }

    private fun getFilePathFromContentUri(uri: Uri): String? {
        return try {
            when {
                // Check if this is a media URI
                uri.authority == "media" -> {
                    contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(0)
                        } else null
                    }
                }
                // Check for file path in the URI itself (some file managers include this)
                uri.path?.contains("/storage/") == true -> {
                    val path = uri.path ?: return null
                    val startIndex = path.indexOf("/storage/")
                    if (startIndex >= 0) {
                        path.substring(startIndex)
                    } else null
                }
                // Try to extract from common content provider patterns
                else -> {
                    val projection = arrayOf("_data", MediaStore.MediaColumns.DATA)
                    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        val columnIndex = cursor.getColumnIndexOrThrow(projection[0])
                        if (cursor.moveToFirst()) {
                            cursor.getString(columnIndex)
                        } else null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryGetContentUri(file: File): Uri? {
        return try {
            // Try to get a content:// URI using MediaScannerConnection or FileProvider
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA
                )
                val selection = "${MediaStore.Images.Media.DATA} = ?"
                val selectionArgs = arrayOf(file.absolutePath)

                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                    } else null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryQueryMediaStore(uri: Uri): List<Uri> {
        val images = mutableListOf<Uri>()

        try {
            // Extract bucket/folder information from the current image
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )

            var bucketId: String? = null
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    bucketId = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID))
                }
            }

            // If we found a bucket ID, query all images in the same bucket
            if (bucketId != null) {
                val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
                val selectionArgs = arrayOf(bucketId)
                val sortOrder = "${MediaStore.Images.Media.DISPLAY_NAME} ASC"

                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME
                    ),
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        images.add(contentUri)
                    }
                }
            }
        } catch (_: Exception) {
            // ignore and return what we have
        }

        return if (images.size > 1) images else listOf(uri)
    }

    private fun startIndexFor(images: List<Uri>, original: Uri, originalName: String): Int {
        val origId = mediaIdOf(original)
        if (origId != null) {
            val idxById = images.indexOfFirst { mediaIdOf(it) == origId }
            if (idxById >= 0) return idxById
        }

        // Fallback: filename match (case-insensitive)
        val idxByName = images.indexOfFirst { getFileName(it).equals(originalName, ignoreCase = true) }
        if (idxByName >= 0) return idxByName

        // else
        return 0
    }
    private fun getFileName(uri: Uri): String {
        return when (uri.scheme) {
            "file" -> File(uri.path ?: "").name
            "content" -> {
                // Try to get display name from content resolver
                try {
                    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(0)
                        } else null
                    }
                } catch (_: Exception) {
                    null
                } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "image"
            }
            "root", "shizuku" -> File(uri.path ?: "").name
            else -> uri.lastPathSegment?.substringAfterLast('/') ?: "image"
        }
    }




    private fun mediaIdOf(uri: Uri): Long? = try {
        // Works for content://media/external/images/media/<id> and similar
        if (uri.scheme == "content" && uri.authority?.contains("media") == true) {
            ContentUris.parseId(uri)
        } else null
    } catch (_: Exception) { null }

    private fun deriveTitle(uris: List<Uri>, currentIndex: Int): String {
        return if (uris.size > 1) {
            val currentUri = uris.getOrNull(currentIndex)
            val name = currentUri?.lastPathSegment?.substringAfterLast('/') ?: "Image"
            "$name (${currentIndex + 1}/${uris.size})"
        } else {
            uris.firstOrNull()?.lastPathSegment?.substringAfterLast('/') ?: "Image Viewer"
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FullscreenImageViewer(
    images: List<String>,
    initialPage: Int,
    onClose: () -> Unit
) {
    if (images.isEmpty()) return

    val safeInitial = initialPage.coerceIn(0, images.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { images.size })

    BackHandler { onClose() }

    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { cfg.screenWidthDp.dp.roundToPx() }
    val heightPx = with(density) { cfg.screenHeightDp.dp.roundToPx() }
    val targetW = widthPx.coerceIn(720, 2160)
    val targetH = heightPx.coerceIn(480, 1440)

    val viewerScope = rememberCoroutineScope()
    var currentPageScale by remember { mutableFloatStateOf(1f) }
    var isNavigating by remember { mutableStateOf(false) }

    // Store current page's control functions
    var zoomIn: (() -> Unit)? by remember { mutableStateOf(null) }
    var zoomOut: (() -> Unit)? by remember { mutableStateOf(null) }
    var toggleZoom: (() -> Unit)? by remember { mutableStateOf(null) }
    var panLeft: (() -> Unit)? by remember { mutableStateOf(null) }
    var panRight: (() -> Unit)? by remember { mutableStateOf(null) }
    var panUp: (() -> Unit)? by remember { mutableStateOf(null) }
    var panDown: (() -> Unit)? by remember { mutableStateOf(null) }

    val contentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        contentFocus.requestFocus()
    }

    val displayIndex by remember { derivedStateOf { (pagerState.currentPage + 1).coerceIn(1, images.size) } }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Box(modifier = Modifier.focusable(false)) {
                TopAppBar(
                    title = { Text("$displayIndex / ${images.size}") },
                    actions = {
                        TextButton(
                            onClick = onClose,
                            modifier = Modifier.focusable(false)
                        ) { Text("Close") }
                    }
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .focusRequester(contentFocus)
                .focusable()
                .focusProperties {
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                    when (ev.key) {
                        Key.Back, Key.Escape -> {
                            onClose()
                            true
                        }
                        // Zoom controls
                        Key.Plus, Key.NumPadAdd, Key.Equals -> {
                            zoomIn?.invoke()
                            true
                        }
                        Key.Minus, Key.NumPadSubtract -> {
                            zoomOut?.invoke()
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            toggleZoom?.invoke()
                            true
                        }
                        // Directional keys
                        Key.DirectionLeft -> {
                            if (currentPageScale <= 1.01f) {
                                // Navigate pages at 1x
                                if (!isNavigating && pagerState.currentPage > 0) {
                                    isNavigating = true
                                    viewerScope.launch {
                                        try {
                                            pagerState.scrollToPage(pagerState.currentPage - 1)
                                        } finally {
                                            isNavigating = false
                                        }
                                    }
                                }
                            } else {
                                // Pan when zoomed
                                panLeft?.invoke()
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            if (currentPageScale <= 1.01f) {
                                // Navigate pages at 1x
                                if (!isNavigating && pagerState.currentPage < images.lastIndex) {
                                    isNavigating = true
                                    viewerScope.launch {
                                        try {
                                            pagerState.scrollToPage(pagerState.currentPage + 1)
                                        } finally {
                                            isNavigating = false
                                        }
                                    }
                                }
                            } else {
                                // Pan when zoomed
                                panRight?.invoke()
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (currentPageScale > 1.01f) {
                                panUp?.invoke()
                            }
                            true
                        }
                        Key.DirectionDown -> {
                            if (currentPageScale > 1.01f) {
                                panDown?.invoke()
                            }
                            true
                        }
                        else -> {
                            // Check for DPAD_CENTER
                            val isDpadCenter = ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                            if (isDpadCenter) {
                                toggleZoom?.invoke()
                                true
                            } else false
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 0
            ) { page ->
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val pageScope = rememberCoroutineScope()
                    val viewportW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                    val viewportH = constraints.maxHeight.toFloat().coerceAtLeast(1f)

                    val minScale = 1f
                    val maxScale = 5f
                    val scaleAnim = remember(page) { Animatable(1f) }
                    val offsetAnim = remember(page) { Animatable(Offset.Zero, Offset.VectorConverter) }

                    LaunchedEffect(page, scaleAnim.value) {
                        if (page == pagerState.currentPage) {
                            currentPageScale = scaleAnim.value
                        }
                    }

                    fun maxBoundsX(s: Float) = ((s * viewportW - viewportW) / 2f).coerceAtLeast(0f)
                    fun maxBoundsY(s: Float) = ((s * viewportH - viewportH) / 2f).coerceAtLeast(0f)
                    fun clampOffset(o: Offset, s: Float): Offset {
                        val mx = maxBoundsX(s)
                        val my = maxBoundsY(s)
                        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
                    }

                    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                        val newScale = (scaleAnim.value * zoomChange).coerceIn(minScale, maxScale)
                        pageScope.launch { scaleAnim.snapTo(newScale) }
                        val newOffset = clampOffset(offsetAnim.value + panChange, newScale)
                        pageScope.launch { offsetAnim.snapTo(newOffset) }
                    }

                    fun animateZoomTo(target: Float, tap: Offset? = null) {
                        val startScale = scaleAnim.value
                        val factor = (target / startScale).coerceIn(0.01f, 100f)
                        val startOffset = offsetAnim.value
                        val targetOffset = if (tap != null) {
                            clampOffset((startOffset - tap) * factor + tap, target)
                        } else {
                            if (target == 1f) Offset.Zero else clampOffset(startOffset, target)
                        }
                        val spec = tween<Float>(durationMillis = 180)
                        pageScope.launch {
                            launch { scaleAnim.animateTo(target, spec) }
                            launch { offsetAnim.animateTo(targetOffset, tween(180)) }
                        }
                    }

                    // Register control functions for current page
                    LaunchedEffect(page) {
                        if (page == pagerState.currentPage) {
                            val panStepX = viewportW * 0.25f
                            val panStepY = viewportH * 0.25f

                            zoomIn = {
                                val target = (scaleAnim.value + 0.25f).coerceIn(minScale, maxScale)
                                animateZoomTo(target)
                            }
                            zoomOut = {
                                val target = (scaleAnim.value - 0.25f).coerceIn(minScale, maxScale)
                                animateZoomTo(if (target == minScale) 1f else target)
                            }
                            toggleZoom = {
                                animateZoomTo(if (scaleAnim.value > 1f) 1f else 2f)
                            }

                            panLeft = {
                                val scale = scaleAnim.value
                                val offset = offsetAnim.value
                                val new = clampOffset(offset + Offset(panStepX, 0f), scale)
                                if (new == offset && offset.x >= -4f && maxBoundsX(scale) > 0f && !isNavigating && pagerState.currentPage > 0) {
                                    // At edge, navigate
                                    isNavigating = true
                                    viewerScope.launch {
                                        try {
                                            pagerState.scrollToPage(pagerState.currentPage - 1)
                                        } finally {
                                            isNavigating = false
                                        }
                                    }
                                } else {
                                    pageScope.launch { offsetAnim.animateTo(new, tween(120)) }
                                }
                            }

                            panRight = {
                                val scale = scaleAnim.value
                                val offset = offsetAnim.value
                                val new = clampOffset(offset + Offset(-panStepX, 0f), scale)
                                if (new == offset && (maxBoundsX(scale) - offset.x) <= 4f && maxBoundsX(scale) > 0f && !isNavigating && pagerState.currentPage < images.lastIndex) {
                                    // At edge, navigate
                                    isNavigating = true
                                    viewerScope.launch {
                                        try {
                                            pagerState.scrollToPage(pagerState.currentPage + 1)
                                        } finally {
                                            isNavigating = false
                                        }
                                    }
                                } else {
                                    pageScope.launch { offsetAnim.animateTo(new, tween(120)) }
                                }
                            }

                            panUp = {
                                val scale = scaleAnim.value
                                val offset = offsetAnim.value
                                val new = clampOffset(offset + Offset(0f, panStepY), scale)
                                pageScope.launch { offsetAnim.animateTo(new, tween(120)) }
                            }

                            panDown = {
                                val scale = scaleAnim.value
                                val offset = offsetAnim.value
                                val new = clampOffset(offset + Offset(0f, -panStepY), scale)
                                pageScope.launch { offsetAnim.animateTo(new, tween(120)) }
                            }
                        }
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(page) {
                                detectTapGestures(
                                    onDoubleTap = { pos ->
                                        val s = scaleAnim.value
                                        val next = when {
                                            s < 1.75f -> 2f
                                            s < 2.75f -> 3f
                                            else -> 1f
                                        }
                                        animateZoomTo(next, tap = pos)
                                    }
                                )
                            }
                            .transformable(
                                state = transformState,
                                enabled = scaleAnim.value > 1.01f
                            )
                            .graphicsLayer {
                                translationX = offsetAnim.value.x
                                translationY = offsetAnim.value.y
                                scaleX = scaleAnim.value
                                scaleY = scaleAnim.value
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val req = ImageRequest.Builder(LocalContext.current)
                            .data(images[page])
                            .size(targetW, targetH)
                            .crossfade(true)
                            .build()
                        AsyncImage(
                            model = req,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}