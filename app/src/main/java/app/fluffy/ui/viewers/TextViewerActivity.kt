package app.fluffy.ui.viewers

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.fluffy.AppGraph
import app.fluffy.data.repository.AppSettings
import app.fluffy.ui.theme.FluffyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

class TextViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)
        val uri = intent?.data ?: run { finish(); return }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: AppGraph.io.queryDisplayName(uri)

        setContent {
            val settings = AppGraph.settings.settingsFlow.collectAsState(initial = AppSettings()).value
            val dark = when (settings.themeMode) {
                0 -> androidx.compose.foundation.isSystemInDarkTheme()
                1 -> false
                else -> true
            }
            FluffyTheme(darkTheme = dark, useAuroraTheme = settings.useAuroraTheme) {
                TextViewerScreen(uri, title) { finish() }
            }
        }
    }
    companion object { const val EXTRA_TITLE = "title" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextViewerScreen(uri: Uri, title: String, onClose: () -> Unit) {
    BackHandler { onClose() }
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(uri) {
        val max = 2 * 1024 * 1024 // 2 MB guard
        runCatching {
            withContext(Dispatchers.IO) {
                AppGraph.io.openIn(uri).use { input ->
                    if (input.available() > max) throw IllegalStateException("File too large to preview")
                    val bytes = input.readBytes()
                    val charset = sniffCharset(bytes) ?: Charsets.UTF_8
                    String(bytes, charset)
                }
            }
        }.onSuccess { content = it }.onFailure { error = it.message ?: "Failed to open" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = { TextButton(onClick = onClose) { Text("Close") } }
            )
        }
    ) { pv ->
        when {
            error != null -> Box(Modifier.fillMaxSize().padding(pv), contentAlignment = Alignment.Center) {
                Text(error ?: "Error", color = MaterialTheme.colorScheme.error)
            }
            content == null -> Box(Modifier.fillMaxSize().padding(pv), contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator()
            }
            else -> {
                val scroll = rememberScrollState()
                val focusRequester = remember { FocusRequester() }

                val smallScrollAmount = 200
                val largeScrollAmount = 800

                val keyHandler: (KeyEvent) -> Boolean = { ev ->
                    if (ev.type != KeyEventType.KeyDown) false
                    else when (ev.key) {
                        Key.DirectionDown -> {
                            coroutineScope.launch {
                                scroll.animateScrollTo((scroll.value + smallScrollAmount).coerceAtMost(scroll.maxValue))
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            coroutineScope.launch {
                                scroll.animateScrollTo((scroll.value - smallScrollAmount).coerceAtLeast(0))
                            }
                            true
                        }
                        Key.MediaFastForward, Key.PageDown -> {
                            coroutineScope.launch {
                                scroll.animateScrollTo((scroll.value + largeScrollAmount).coerceAtMost(scroll.maxValue))
                            }
                            true
                        }
                        Key.MediaRewind, Key.PageUp -> {
                            coroutineScope.launch {
                                scroll.animateScrollTo((scroll.value - largeScrollAmount).coerceAtLeast(0))
                            }
                            true
                        }
                        Key.ChannelUp -> {
                            coroutineScope.launch {
                                scroll.animateScrollTo((scroll.value - largeScrollAmount).coerceAtLeast(0))
                            }
                            true
                        }
                        Key.ChannelDown -> {
                            coroutineScope.launch {
                                scroll.animateScrollTo((scroll.value + largeScrollAmount).coerceAtMost(scroll.maxValue))
                            }
                            true
                        }
                        Key.MoveHome -> {
                            coroutineScope.launch {
                                scroll.animateScrollTo(0)
                            }
                            true
                        }
                        Key.MoveEnd -> {
                            coroutineScope.launch {
                                scroll.animateScrollTo(scroll.maxValue)
                            }
                            true
                        }
                        Key.Back, Key.Escape -> {
                            onClose()
                            true
                        }
                        else -> false
                    }
                }

                LaunchedEffect(content) {
                    focusRequester.requestFocus()
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(pv)
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent(keyHandler)
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)) {
                        Text(
                            text = content!!,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

private fun sniffCharset(bytes: ByteArray): Charset? {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return Charsets.UTF_8
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
    return null
}