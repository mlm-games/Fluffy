package app.fluffy.ui.viewers

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import app.fluffy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import app.fluffy.data.repository.AppSettings
import app.fluffy.data.repository.SettingsRepository
import app.fluffy.io.DocumentController
import app.fluffy.ui.theme.FluffyTheme
import app.fluffy.util.AppLog
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

class ImageViewerActivity : ComponentActivity(), KoinComponent {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ContextCompat.getContextForLanguage(newBase))
    }

    private val settings: SettingsRepository by inject()

    companion object {
        const val EXTRA_IMAGES = "images"
        const val EXTRA_INITIAL_INDEX = "initial"
        const val EXTRA_TITLE = "title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fromExtras = intent.getStringArrayListExtra(EXTRA_IMAGES)?.mapNotNull { runCatching { it.toUri() }.getOrNull() }.orEmpty()
        val fromData = intent.data?.let { listOf(it) }.orEmpty()
        val fromClip = buildList {
            intent.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) {
                    cd.getItemAt(i)?.uri?.let { add(it) }
                }
            }
        }

        val initialUris = (fromExtras + fromData + fromClip).distinct()
        val initialIndex = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0)

        if (initialUris.isEmpty()) {
            finish()
            return
        }

        runCatching {
            val flags = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (flags != 0) {
                for (u in initialUris) runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }

        val titleExtra = intent.getStringExtra(EXTRA_TITLE)

        setContent {
            val s = settings.settingsFlow.collectAsState(initial = AppSettings()).value
            val dark = when (s.themeMode) {
                0 -> isSystemInDarkTheme()
                1 -> false
                else -> true
            }
            FluffyTheme(darkTheme = dark, useAuroraTheme = s.useAuroraTheme) {
                var images by remember { mutableStateOf<List<Uri>?>(if (initialUris.size == 1) null else initialUris) }
                var startIndex by remember { mutableIntStateOf(initialIndex.coerceIn(0, (images?.size ?: 1) - 1)) }
                LaunchedEffect(initialUris) {
                    if (images == null) {
                        images = withContext(Dispatchers.IO) {
                            val singleUri = initialUris.first()
                            val discovered = DocumentController.listImageSiblings(this@ImageViewerActivity, singleUri)
                            if (discovered.size > 1) discovered else initialUris
                        }
                        val finalList = images ?: initialUris
                        startIndex = if (finalList.size > 1 && initialUris.size == 1) {
                            startIndexFor(finalList, initialUris.first(), getFileName(initialUris.first()))
                        } else initialIndex.coerceIn(0, finalList.size - 1)
                    }
                }
                if (images == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val title = titleExtra ?: deriveTitle(images!!, startIndex)
                    FullscreenImageViewer(
                        images = images!!.map { it.toString() },
                        initialPage = startIndex,
                        onClose = { finish() }
                    )
                }
            }
        }
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
                } catch (e: Exception) {
                    AppLog.d("ImageViewer", "getFileName query failed: $uri", e)
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
    } catch (e: Exception) {
        AppLog.d("ImageViewer", "mediaIdOf failed: $uri", e)
        null
    }

    private fun deriveTitle(uris: List<Uri>, currentIndex: Int): String {
        return if (uris.size > 1) {
            val currentUri = uris.getOrNull(currentIndex)
            val name = currentUri?.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.image)
            "$name (${currentIndex + 1}/${uris.size})"
        } else {
            uris.firstOrNull()?.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.image_viewer)
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
    var pages by remember { mutableStateOf(images) }
    val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { pages.size })

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

    val displayIndex by remember { derivedStateOf { (pagerState.currentPage + 1).coerceIn(1, pages.size) } }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Box(modifier = Modifier.focusable(false)) {
                TopAppBar(
                    title = {
                        EditablePageIndicator(
                            currentPage = pagerState.currentPage,
                            pageCount = pages.size,
                            onNavigate = { viewerScope.launch { pagerState.scrollToPage(it) } },
                            onEditingFinished = { contentFocus.requestFocus() }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        val current = pages.getOrNull(pagerState.currentPage)?.let(Uri::parse)
                        if (current != null) {
                            ViewerActionBar(
                                uri = current,
                                onDeleted = {
                                    val index = pagerState.currentPage
                                    pages = pages.filterIndexed { i, _ -> i != index }
                                    if (pages.isEmpty()) onClose()
                                }
                            )
                        }
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
                                if (!isNavigating && pagerState.currentPage < pages.lastIndex) {
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

                    @Suppress("DEPRECATION")
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
                                if (new == offset && (maxBoundsX(scale) - offset.x) <= 4f && maxBoundsX(scale) > 0f && !isNavigating && pagerState.currentPage < pages.lastIndex) {
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
                        val ctx = LocalContext.current
                        val req = ImageRequest.Builder(ctx)
                            .data(images[page])
                            .size(targetW, targetH)
                            .crossfade(true)
                            .build()
                        AsyncImage(
                            model = req,
                            contentDescription = null,
                            imageLoader = ctx.imageLoader,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}
