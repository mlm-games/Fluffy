@file:OptIn(UnstableApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class)

package app.fluffy.ui.viewers


import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import app.fluffy.AppGraph
import app.fluffy.data.repository.AppSettings
import app.fluffy.ui.theme.FluffyTheme
import kotlinx.coroutines.delay
import kotlin.math.max

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

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaPlayerScreen(
    url: String,
    title: String,
    onClose: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    // Player
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Aspect ratio from Player.videoSize (fallback 16:9)
    val aspect by rememberAspectRatio(player)

    // Progress state
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var seeking by remember { mutableStateOf(false) }
    var seekTo by remember { mutableLongStateOf(0L) }

    // Keep UI in sync with player
    LaunchedEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) { duration = max(0L, player.duration) }
        }
        player.addListener(listener)
        try {
            while (true) {
                duration = max(duration, player.duration)
                position = if (seeking) seekTo else player.currentPosition
                buffered = player.bufferedPosition
                delay(200)
            }
        } finally {
            player.removeListener(listener)
        }
    }

    // DPAD: only consume when controls row is not focused (so you can move between buttons)
    var controlsFocused by remember { mutableStateOf(false) }
    val handleKeys: (KeyEvent) -> Boolean = { ev ->
        if (ev.type != KeyEventType.KeyDown) false else when (ev.key) {
            Key.DirectionLeft  -> if (!controlsFocused) { player.seekBack(); true } else false
            Key.DirectionRight -> if (!controlsFocused) { player.seekForward(); true } else false
            Key.Enter, Key.NumPadEnter, Key.DirectionCenter ->
                if (!controlsFocused) { if (player.isPlaying) player.pause() else player.play(); true } else false
            else -> false
        }
    }

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
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .then(
                        if (aspect > 0f) {
                            Modifier.aspectRatio(aspect)
                        } else {
                            Modifier.aspectRatio(16f / 9f)
                        }
                    )
            )

            // Bottom overlay: buffer bar, slider, times, controls
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
                val total = if (duration > 0) duration else 1L
                val sliderPos = (if (seeking) seekTo else position).coerceIn(0, total)
                val bufferedFrac = (buffered.toFloat() / total.toFloat()).coerceIn(0f, 1f)

                LinearWavyProgressIndicator(
                    progress = { bufferedFrac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = colors.primary.copy(alpha = 0.35f),
                    trackColor = colors.onSurface.copy(alpha = 0.18f)
                )
                Spacer(Modifier.height(8.dp))

                // Position slider (seek on release)
                Slider(
                    value = sliderPos.toFloat(),
                    onValueChange = {
                        seeking = true
                        seekTo = it.toLong()
                    },
                    onValueChangeFinished = {
                        seeking = false
                        player.seekTo(seekTo)
                    },
                    valueRange = 0f..total.toFloat(),
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
                    Text(formatTime(sliderPos), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(formatTime(total), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }

                Spacer(Modifier.height(8.dp))

                // Controls row — TV friendly with explicit focus wiring
                val rewindFR = remember { FocusRequester() }
                val playFR   = remember { FocusRequester() }
                val fwdFR    = remember { FocusRequester() }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val canBack = player.isCommandAvailable(Player.COMMAND_SEEK_BACK)
                    val canFwd  = player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)

                    FilledTonalIconButton(
                        onClick = { if (canBack) player.seekBack() },
                        modifier = Modifier
                            .focusRequester(rewindFR)
                            .onFocusChanged { controlsFocused = it.hasFocus }
                            .focusProperties { right = playFR }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FastRewind,
                            contentDescription = "Rewind",
                            tint = if (canBack) colors.onSecondaryContainer else colors.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    FilledIconButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        modifier = Modifier
                            .focusRequester(playFR)
                            .onFocusChanged { controlsFocused = it.hasFocus }
                            .focusProperties { left = rewindFR; right = fwdFR }
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play"
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    FilledTonalIconButton(
                        onClick = { if (canFwd) player.seekForward() },
                        modifier = Modifier
                            .focusRequester(fwdFR)
                            .onFocusChanged { controlsFocused = it.hasFocus }
                            .focusProperties { left = playFR }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FastForward,
                            contentDescription = "Forward",
                            tint = if (canFwd) colors.onSecondaryContainer else colors.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }

                    LaunchedEffect(Unit) { playFR.requestFocus() }
                }
            }
        }
    }
}

@Composable
private fun rememberAspectRatio(player: Player): State<Float> {
    val ratio = remember { mutableFloatStateOf(16f / 9f) }
    LaunchedEffect(player) {
        fun calc(vs: VideoSize): Float {
            if (vs.width <= 0 || vs.height <= 0) return 16f / 9f  // Default aspect ratio
            val h = vs.height
            val pwph = if (vs.pixelWidthHeightRatio > 0f) vs.pixelWidthHeightRatio else 1f
            return (vs.width * pwph) / h.toFloat()
        }

        ratio.floatValue = calc(player.videoSize)
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                ratio.floatValue = calc(videoSize)
            }
        }
        player.addListener(listener)
        try {
            while (true) {
                ratio.floatValue = calc(player.videoSize)
                delay(500)
            }
        } finally {
            player.removeListener(listener)
        }
    }
    return ratio
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val s = (totalSec % 60).toInt()
    val m = ((totalSec / 60) % 60).toInt()
    val h = (totalSec / 3600).toInt()
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}