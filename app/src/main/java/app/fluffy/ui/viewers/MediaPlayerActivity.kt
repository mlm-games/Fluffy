@file:OptIn(ExperimentalMaterial3Api::class)

package app.fluffy.ui.viewers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fluffy.AppGraph
import app.fluffy.data.repository.AppSettings
import app.fluffy.ui.theme.FluffyTheme
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState

class MediaPlayerActivity : ComponentActivity() {
    companion object { const val EXTRA_TITLE = "title" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)

        val uri = intent?.data ?: run { finish(); return }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: AppGraph.io.queryDisplayName(uri)

        setContent {
            val settings = AppGraph.settings.settingsFlow.collectAsState(initial = AppSettings()).value
            val dark = when (settings.themeMode) { 0 -> isSystemInDarkTheme(); 1 -> false; else -> true }
            FluffyTheme(darkTheme = dark, useAuroraTheme = settings.useAuroraTheme) {
                MediaPlayerScreen(
                    url = uri.toString(), title = title,
                    onClose = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaPlayerScreen(
    url: String,
    title: String,
    onClose: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val playerState = rememberVideoPlayerState()

    LaunchedEffect(url) {
        playerState.openUri(url)
    }

    var controlsFocused by remember { mutableStateOf(false) }

    val handleKeys: (KeyEvent) -> Boolean = { ev ->
        if (ev.type != KeyEventType.KeyDown) false else when (ev.key) {
            Key.DirectionLeft -> if (!controlsFocused) {
                playerState.seekTo((playerState.sliderPos - 50f).coerceAtLeast(0f))
                true
            } else false
            Key.DirectionRight -> if (!controlsFocused) {
                playerState.seekTo((playerState.sliderPos + 50f).coerceAtMost(1000f))
                true
            } else false
            Key.Enter, Key.NumPadEnter, Key.DirectionCenter ->
                if (!controlsFocused) {
                    if (playerState.isPlaying) playerState.pause() else playerState.play()
                    true
                } else false
            else -> false
        }
    }

    BackHandler { onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { TextButton(onClick = onClose) { Text("Close") } }
            )
        }
    ) { pv ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pv)
                .background(Color.Black)
                .onPreviewKeyEvent(handleKeys)
        ) {
            VideoPlayerSurface(
                playerState = playerState,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .aspectRatio(if (playerState.aspectRatio > 0) playerState.aspectRatio else 16f / 9f)
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, colors.surface.copy(alpha = 0.86f))
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Slider(
                    value = playerState.sliderPos,
                    onValueChange = {
                        playerState.sliderPos = it
                        playerState.userDragging = true
                    },
                    onValueChangeFinished = {
                        playerState.userDragging = false
                        playerState.seekTo(playerState.sliderPos)
                    },
                    valueRange = 0f..1000f,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primary,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.onSurface.copy(alpha = 0.24f)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(playerState.positionText, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(playerState.durationText, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }

                Spacer(Modifier.height(8.dp))

                // Controls row — TV friendly
                val rewindFR = remember { FocusRequester() }
                val playFR = remember { FocusRequester() }
                val fwdFR = remember { FocusRequester() }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { playerState.seekTo((playerState.sliderPos - 50f).coerceAtLeast(0f)) },
                        modifier = Modifier
                            .focusRequester(rewindFR)
                            .onFocusChanged { controlsFocused = it.hasFocus }
                            .focusProperties { right = playFR }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FastRewind,
                            contentDescription = "Rewind",
                            tint = colors.onSecondaryContainer
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    FilledIconButton(
                        onClick = { if (playerState.isPlaying) playerState.pause() else playerState.play() },
                        modifier = Modifier
                            .focusRequester(playFR)
                            .onFocusChanged { controlsFocused = it.hasFocus }
                            .focusProperties { left = rewindFR; right = fwdFR }
                    ) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play"
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    FilledTonalIconButton(
                        onClick = { playerState.seekTo((playerState.sliderPos + 50f).coerceAtMost(1000f)) },
                        modifier = Modifier
                            .focusRequester(fwdFR)
                            .onFocusChanged { controlsFocused = it.hasFocus }
                            .focusProperties { left = playFR }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FastForward,
                            contentDescription = "Forward",
                            tint = colors.onSecondaryContainer
                        )
                    }

                    LaunchedEffect(Unit) { playFR.requestFocus() }
                }
            }
        }
    }
}
