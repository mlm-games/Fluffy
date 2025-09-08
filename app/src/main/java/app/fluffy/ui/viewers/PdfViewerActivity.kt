package app.fluffy.ui.viewers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.core.graphics.createBitmap
import app.fluffy.AppGraph
import app.fluffy.data.repository.AppSettings
import app.fluffy.ui.theme.FluffyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfViewerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URI = "uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val inputUri = (intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)) ?: intent.data
        if (inputUri == null) {
            finish(); return
        }
        // Grant read (if caller didn’t set)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        setContent {
            val settings = AppGraph.settings.settingsFlow.collectAsState(initial = AppSettings()).value
            val dark = when (settings.themeMode) {
                0 -> isSystemInDarkTheme()
                1 -> false
                else -> true
            }
            FluffyTheme(darkTheme = dark, useAuroraTheme = settings.useAuroraTheme) {
                FullscreenPdfViewer(
                    uri = inputUri,
                    onClose = { finish() }
                )
            }
        }
    }
}

private class PdfDoc(
    private val pfd: ParcelFileDescriptor
) : AutoCloseable {
    private val renderer = PdfRenderer(pfd)
    val pageCount: Int get() = renderer.pageCount

    // in‑memory cache (~16 MB)
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
//            if (evicted) oldValue.recycle()
        }
    }

    suspend fun render(pageIndex: Int, viewportW: Int, viewportH: Int, scaleHint: Float): Bitmap =
        withContext(Dispatchers.IO) {
            val page = renderer.openPage(pageIndex)
            val baseW = page.width
            val baseH = page.height
            val bucket = bucket(scaleHint)

            val fitScale = minOf(
                viewportW.toFloat() / baseW.toFloat(),
                viewportH.toFloat() / baseH.toFloat()
            ).coerceAtLeast(0.5f)

            val targetW = (baseW * fitScale * bucket).toInt().coerceAtLeast(64)
            val targetH = (baseH * fitScale * bucket).toInt().coerceAtLeast(64)
            val key = "$pageIndex@$bucket@${targetW}x${targetH}"

            cache.get(key)?.also { page.close(); return@withContext it }

            val bmp = createBitmap(targetW, targetH)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            cache.put(key, bmp)
            bmp
        }

    override fun close() {
        try { renderer.close() } catch (_: Exception) {}
        try { pfd.close() } catch (_: Exception) {}
        cache.evictAll()
    }

    companion object {
        suspend fun open(context: Context, uri: Uri): PdfDoc? =
            withContext(Dispatchers.IO) {
                val pfd = runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")
                }.getOrNull() ?: run {
                    val tmp = File.createTempFile("doc_", ".pdf", context.cacheDir)
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { src ->
                            FileOutputStream(tmp).use { dst -> src.copyTo(dst) }
                        }
                    }
                    ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                pfd?.let { PdfDoc(it) }
            }
    }
}

@Composable
private fun PdfPageImage(
    doc: PdfDoc,
    pageIndex: Int,
    viewportW: Int,
    viewportH: Int,
    scaleForQuality: Float,
    nightInvertEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    var bmp by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pageIndex, viewportW, viewportH) {
        snapshotFlow { bucket(scaleForQuality) }
            .distinctUntilChanged()
            .collectLatest { b ->
                val newBmp = runCatching {
                    withContext(Dispatchers.IO) {
                        doc.render(pageIndex, viewportW, viewportH, b)
                    }
                }.getOrNull()
                if (newBmp != null) {
                    bmp = newBmp // to prevent redraws during zoom
                }
            }
    }

    LaunchedEffect(pageIndex, viewportW, viewportH) {
        if (bmp == null) {
            val firstBmp = runCatching {
                withContext(Dispatchers.IO) {
                    doc.render(pageIndex, viewportW, viewportH, bucket(1f))
                }
            }.getOrNull()
            if (firstBmp != null) bmp = firstBmp
        }
    }

    val invert = remember {
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    bmp?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            colorFilter = if (nightInvertEnabled) ColorFilter.colorMatrix(invert) else null
        )
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenPdfViewer(
    uri: Uri,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val doc by produceState<PdfDoc?>(initialValue = null, uri) {
        value = PdfDoc.open(context, uri)
    }
    if (doc == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        BackHandler { onClose() }
        return
    }

    val pageCount = doc!!.pageCount
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pageCount })
    BackHandler { onClose() }

    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val viewportW = with(density) { cfg.screenWidthDp.dp.roundToPx() }
    val viewportH = with(density) { cfg.screenHeightDp.dp.roundToPx() }

    // Viewer-level input (same pattern you confirmed working)
    val viewerScope = rememberCoroutineScope()
    var currentPageScale by remember { mutableFloatStateOf(1f) }
    var isNavigating by remember { mutableStateOf(false) }

    var zoomIn: (() -> Unit)? by remember { mutableStateOf(null) }
    var zoomOut: (() -> Unit)? by remember { mutableStateOf(null) }
    var toggleZoom: (() -> Unit)? by remember { mutableStateOf(null) }
    var panLeft: (() -> Unit)? by remember { mutableStateOf(null) }
    var panRight: (() -> Unit)? by remember { mutableStateOf(null) }
    var panUp: (() -> Unit)? by remember { mutableStateOf(null) }
    var panDown: (() -> Unit)? by remember { mutableStateOf(null) }

    val contentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { contentFocus.requestFocus() }

    val title = "${pagerState.currentPage + 1} / $pageCount"

    val settings = AppGraph.settings.settingsFlow.collectAsState(initial = AppSettings()).value
    val dark = when (settings.themeMode) {
        0 -> isSystemInDarkTheme()
        1 -> false
        else -> true
    }

    var invertColorScheme by remember { mutableStateOf(dark) }

    Scaffold(
        topBar = {
            Box(modifier = Modifier.focusable(false)) {
                TopAppBar(
                    title = { Text(title) },
                    actions = {
//                        ToggleButton( checked = dark, onCheckedChange = { invertColorScheme = !invertColorScheme } ) {
//                            Icon(
//                                imageVector =
//                                     Icons.Outlined.DarkMode,
//                                contentDescription = null,
//                                tint = colorScheme.background.copy(alpha = 0.3f),
//                            )
//                        }
                        TextButton(onClick = onClose, modifier = Modifier.focusable(false)) {
                            Text("Close")
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
                .background(MaterialTheme.colorScheme.background)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Back, Key.Escape -> {
                            onClose(); true
                        }

                        Key.Plus, Key.NumPadAdd, Key.Equals -> {
                            zoomIn?.invoke(); true
                        }

                        Key.Minus, Key.NumPadSubtract -> {
                            zoomOut?.invoke(); true
                        }

                        Key.Enter, Key.NumPadEnter -> {
                            toggleZoom?.invoke(); true
                        }

                        Key.DirectionLeft -> {
                            if (currentPageScale <= 1.01f) {
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
                            } else panLeft?.invoke()
                            true
                        }

                        Key.DirectionRight -> {
                            if (currentPageScale <= 1.01f) {
                                if (!isNavigating && pagerState.currentPage < pageCount - 1) {
                                    isNavigating = true
                                    viewerScope.launch {
                                        try {
                                            pagerState.scrollToPage(pagerState.currentPage + 1)
                                        } finally {
                                            isNavigating = false
                                        }
                                    }
                                }
                            } else panRight?.invoke()
                            true
                        }

                        Key.DirectionUp -> {
                            if (currentPageScale > 1.01f) panUp?.invoke(); true
                        }

                        Key.DirectionDown -> {
                            if (currentPageScale > 1.01f) panDown?.invoke(); true
                        }

                        else -> {
                            val isCenter = ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            if (isCenter) {
                                toggleZoom?.invoke(); true
                            } else false
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val scope = rememberCoroutineScope()
                    val minScale = 1f
                    val maxScale = 5f
                    val scaleAnim = remember(page) { Animatable(1f) }
                    val offsetAnim = remember(page) { Animatable(Offset.Zero, Offset.VectorConverter) }

                    LaunchedEffect(page, scaleAnim.value) {
                        if (page == pagerState.currentPage) currentPageScale = scaleAnim.value
                    }

                    fun clampOffset(o: Offset, s: Float): Offset {
                        val mx = ((s * viewportW - viewportW) / 2f).coerceAtLeast(0f)
                        val my = ((s * viewportH - viewportH) / 2f).coerceAtLeast(0f)
                        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
                    }

                    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                        val newScale = (scaleAnim.value * zoomChange).coerceIn(minScale, maxScale)
                        scope.launch { scaleAnim.snapTo(newScale) }
                        val newOffset = clampOffset(offsetAnim.value + panChange, newScale)
                        scope.launch { offsetAnim.snapTo(newOffset) }
                    }

                    fun animateZoomTo(target: Float, tap: Offset? = null) {
                        val startScale = scaleAnim.value
                        val factor = (target / startScale).coerceIn(0.01f, 100f)
                        val startOffset = offsetAnim.value
                        val targetOffset = if (tap != null) (startOffset - tap) * factor + tap else startOffset
                        val spec = tween<Float>(durationMillis = 180)
                        scope.launch {
                            launch { scaleAnim.animateTo(target, spec) }
                            launch { offsetAnim.animateTo(clampOffset(targetOffset, target), tween(180)) }
                        }
                    }

                    LaunchedEffect(page) {
                        if (page == pagerState.currentPage) {
                            val panStepX = viewportW * 0.25f
                            val panStepY = viewportH * 0.25f
                            zoomIn = { animateZoomTo((scaleAnim.value + 0.25f).coerceIn(minScale, maxScale)) }
                            zoomOut = {
                                val t = (scaleAnim.value - 0.25f).coerceIn(minScale, maxScale)
                                animateZoomTo(if (t == minScale) 1f else t)
                            }
                            toggleZoom = { animateZoomTo(if (scaleAnim.value > 1f) 1f else 2f) }
                            panLeft  = { scope.launch { offsetAnim.animateTo(clampOffset(offsetAnim.value + Offset(panStepX, 0f), scaleAnim.value), tween(120)) } }
                            panRight = { scope.launch { offsetAnim.animateTo(clampOffset(offsetAnim.value + Offset(-panStepX, 0f), scaleAnim.value), tween(120)) } }
                            panUp    = { scope.launch { offsetAnim.animateTo(clampOffset(offsetAnim.value + Offset(0f, panStepY), scaleAnim.value), tween(120)) } }
                            panDown  = { scope.launch { offsetAnim.animateTo(clampOffset(offsetAnim.value + Offset(0f, -panStepY), scaleAnim.value), tween(120)) } }
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
                        PdfPageImage(
                            doc = doc!!,
                            pageIndex = page,
                            viewportW = viewportW,
                            viewportH = viewportH,
                            scaleForQuality = scaleAnim.value,
                            nightInvertEnabled = invertColorScheme,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { doc?.close() }
    }
}

private fun bucket(scale: Float): Float = when {
    scale < 1.25f -> 1f
    scale < 1.75f -> 1.5f
    scale < 2.25f -> 2f
    scale < 2.75f -> 2.5f
    scale < 3.25f -> 3f
    scale < 3.75f -> 3.5f
    else -> 4f
}