
package app.fluffy.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FullscreenImageViewer(
    images: List<String>,
    initialPage: Int,
    onClose: () -> Unit
) {
    if (images.isEmpty()) return

    val scope = rememberCoroutineScope()
    val safeInitial = initialPage.coerceIn(0, images.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { images.size })

    BackHandler { onClose() }

    val handleKey: (KeyEvent) -> Boolean = { ev ->
        if (ev.type != KeyEventType.KeyDown) false else when (ev.key) {
            Key.DirectionLeft -> {
                val prev = (pagerState.currentPage - 1).coerceAtLeast(0)
                if (prev != pagerState.currentPage) { scope.launch { pagerState.animateScrollToPage(prev) }; true } else false
            }
            Key.DirectionRight -> {
                val next = (pagerState.currentPage + 1).coerceAtMost(images.lastIndex)
                if (next != pagerState.currentPage) { scope.launch { pagerState.animateScrollToPage(next) }; true } else false
            }
            Key.Back, Key.Escape -> { onClose(); true }
            else -> false
        }
    }

    val contentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { contentFocus.requestFocus() }

    val displayIndex by remember { derivedStateOf { (pagerState.currentPage + 1).coerceIn(1, images.size) } }

    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { cfg.screenWidthDp.dp.roundToPx() }
    val heightPx = with(density) { cfg.screenHeightDp.dp.roundToPx() }
    val targetW = widthPx.coerceIn(720, 2160)
    val targetH = heightPx.coerceIn(480, 1440)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent(handleKey),
        topBar = {
            TopAppBar(
                title = { Text("$displayIndex / ${images.size}") },
                actions = { TextButton(onClick = onClose) { Text("Close") } }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .focusRequester(contentFocus)
                .focusable(),
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(state = pagerState) { page ->
                var scale by remember { mutableFloatStateOf(1f) }
                val transformState = rememberTransformableState { zoomChange, _, _ ->
                    scale = (scale * zoomChange).coerceIn(1f, 4f)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .transformable(transformState)
                        .graphicsLayer { scaleX = scale; scaleY = scale },
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