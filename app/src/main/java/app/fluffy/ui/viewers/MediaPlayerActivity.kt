@file:OptIn(ExperimentalMaterial3Api::class)

package app.fluffy.ui.viewers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
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
import app.fluffy.ui.dialogs.FluffyDialog
import app.fluffy.ui.theme.FluffyTheme
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.delay

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

private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaPlayerScreen(
    url: String,
    title: String,
    onClose: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val playerState = rememberVideoPlayerState()

    var controlsVisible by remember { mutableStateOf(true) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showVolumeDialog by remember { mutableStateOf(false) }
    var controlsFocused by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        playerState.openUri(url)
    }

    LaunchedEffect(controlsVisible, controlsFocused) {
        if (controlsVisible && !controlsFocused) {
            delay(4000)
            controlsVisible = false
        }
    }

    val showControls: () -> Unit = {
        if (!controlsVisible) {
            controlsVisible = true
        }
    }

    val handleKeys: (KeyEvent) -> Boolean = { ev ->
        if (ev.type != KeyEventType.KeyDown) false else when (ev.key) {
            Key.DirectionLeft -> if (!controlsFocused) {
                showControls()
                playerState.seekTo((playerState.sliderPos - 50f).coerceAtLeast(0f))
                true
            } else false
            Key.DirectionRight -> if (!controlsFocused) {
                showControls()
                playerState.seekTo((playerState.sliderPos + 50f).coerceAtMost(1000f))
                true
            } else false
            Key.Enter, Key.NumPadEnter, Key.DirectionCenter ->
                if (!controlsFocused) {
                    showControls()
                    if (playerState.isPlaying) playerState.pause() else playerState.play()
                    true
                } else false
            Key.Spacebar -> {
                showControls()
                if (playerState.isPlaying) playerState.pause() else playerState.play()
                true
            }
            Key.M -> {
                playerState.volume = if (playerState.volume > 0f) 0f else 1f
                true
            }
            Key.VolumeUp -> {
                showControls()
                playerState.volume = (playerState.volume + 0.1f).coerceAtMost(1f)
                true
            }
            Key.VolumeDown -> {
                showControls()
                playerState.volume = (playerState.volume - 0.1f).coerceAtLeast(0f)
                true
            }
            else -> false
        }
    }

    BackHandler { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent(handleKeys)
    ) {
        VideoPlayerSurface(
            playerState = playerState,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .clickable { showControls() }
        )

        if (playerState.isLoading) {
            CircularWavyProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = colors.primary
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding()
            ) {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    actions = { TextButton(onClick = onClose) { Text("Close", color = Color.White) } }
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(playerState.positionText, style = MaterialTheme.typography.labelMedium, color = Color.White)
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
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = colors.primary,
                            activeTrackColor = colors.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    Text(playerState.durationText, style = MaterialTheme.typography.labelMedium, color = Color.White)
                }

                Spacer(Modifier.height(8.dp))

                val rewindFR = remember { FocusRequester() }
                val playFR = remember { FocusRequester() }
                val fwdFR = remember { FocusRequester() }
                val volumeFR = remember { FocusRequester() }
                val loopFR = remember { FocusRequester() }
                val speedFR = remember { FocusRequester() }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(
                            onClick = { playerState.seekTo((playerState.sliderPos - 50f).coerceAtLeast(0f)) },
                            modifier = Modifier
                                .focusRequester(rewindFR)
                                .onFocusChanged { controlsFocused = it.hasFocus }
                                .focusProperties { right = playFR }
                        ) {
                            Icon(Icons.Outlined.FastRewind, "Rewind", tint = Color.White)
                        }

                        Spacer(Modifier.width(8.dp))

                        FilledIconButton(
                            onClick = { if (playerState.isPlaying) playerState.pause() else playerState.play() },
                            modifier = Modifier
                                .focusRequester(playFR)
                                .onFocusChanged { controlsFocused = it.hasFocus }
                                .focusProperties { left = rewindFR; right = fwdFR }
                        ) {
                            Icon(
                                if (playerState.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                if (playerState.isPlaying) "Pause" else "Play"
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        FilledTonalIconButton(
                            onClick = { playerState.seekTo((playerState.sliderPos + 50f).coerceAtMost(1000f)) },
                            modifier = Modifier
                                .focusRequester(fwdFR)
                                .onFocusChanged { controlsFocused = it.hasFocus }
                                .focusProperties { left = playFR; right = volumeFR }
                        ) {
                            Icon(Icons.Outlined.FastForward, "Forward", tint = Color.White)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(
                            onClick = { showVolumeDialog = true },
                            modifier = Modifier
                                .focusRequester(volumeFR)
                                .onFocusChanged { controlsFocused = it.hasFocus }
                                .focusProperties { left = fwdFR; right = loopFR }
                        ) {
                            Icon(
                                when {
                                    playerState.volume == 0f -> Icons.AutoMirrored.Filled.VolumeOff
                                    playerState.volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                                    else -> Icons.AutoMirrored.Filled.VolumeUp
                                },
                                "Volume",
                                tint = Color.White
                            )
                        }

                        Spacer(Modifier.width(4.dp))

                        FilledTonalIconButton(
                            onClick = { playerState.loop = !playerState.loop },
                            modifier = Modifier
                                .focusRequester(loopFR)
                                .onFocusChanged { controlsFocused = it.hasFocus }
                                .focusProperties { left = volumeFR; right = speedFR }
                        ) {
                            Icon(
                                Icons.Filled.Loop,
                                "Loop",
                                tint = if (playerState.loop) colors.primary else Color.White.copy(alpha = 0.6f)
                            )
                        }

                        Spacer(Modifier.width(4.dp))

                        FilledTonalIconButton(
                            onClick = { showSpeedDialog = true },
                            modifier = Modifier
                                .focusRequester(speedFR)
                                .onFocusChanged { controlsFocused = it.hasFocus }
                                .focusProperties { left = loopFR }
                        ) {
                            Text(
                                "${playerState.playbackSpeed}x",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }

                    LaunchedEffect(Unit) { playFR.requestFocus() }
                }
            }
        }
    }

    if (showSpeedDialog) {
        PlaybackSpeedDialog(
            currentSpeed = playerState.playbackSpeed,
            onDismiss = { showSpeedDialog = false },
            onSelect = { playerState.playbackSpeed = it }
        )
    }

    if (showVolumeDialog) {
        VolumeDialog(
            currentVolume = playerState.volume,
            onDismiss = { showVolumeDialog = false },
            onVolumeChange = { playerState.volume = it }
        )
    }
}

@Composable
private fun PlaybackSpeedDialog(
    currentSpeed: Float,
    onDismiss: () -> Unit,
    onSelect: (Float) -> Unit
) {
    var selected by remember { mutableFloatStateOf(currentSpeed) }

    FluffyDialog(
        onDismissRequest = onDismiss,
        title = "Playback Speed",
        confirmButton = {
            TextButton(onClick = { onSelect(selected); onDismiss() }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        Column {
            PLAYBACK_SPEEDS.forEach { speed ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected == speed,
                            onClick = { selected = speed }
                        )
                        .padding(vertical = 12.dp, horizontal = 8.dp)
                ) {
                    RadioButton(
                        selected = selected == speed,
                        onClick = { selected = speed },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (speed == 1f) "Normal" else "${speed}x",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeDialog(
    currentVolume: Float,
    onDismiss: () -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    var volume by remember { mutableFloatStateOf(currentVolume) }

    FluffyDialog(
        onDismissRequest = onDismiss,
        title = "Volume",
        confirmButton = {
            TextButton(onClick = { onVolumeChange(volume); onDismiss() }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "${(volume * 100).toInt()}%",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(16.dp))
            Slider(
                value = volume,
                onValueChange = { volume = it },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
