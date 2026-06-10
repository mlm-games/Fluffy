package app.fluffy.ui.viewers

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import app.fluffy.data.repository.AppSettings
import app.fluffy.data.repository.SettingsRepository
import app.fluffy.io.DocumentController
import app.fluffy.io.SafIo
import app.fluffy.ui.theme.FluffyTheme
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class TextEditorActivity : ComponentActivity(), KoinComponent {
    private val io: SafIo by inject()
    private val settings: SettingsRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data ?: run { finish(); return }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: io.queryDisplayName(uri)

        setContent {
            val s = settings.settingsFlow.collectAsState(initial = AppSettings()).value
            val dark = when (s.themeMode) {
                0 -> isSystemInDarkTheme()
                1 -> false
                else -> true
            }
            FluffyTheme(darkTheme = dark, useAuroraTheme = s.useAuroraTheme) {
                TextEditorScreen(uri, title) { finish() }
            }
        }
    }

    companion object { const val EXTRA_TITLE = "title" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextEditorScreen(uri: Uri, title: String, onClose: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()

    var originalContent by remember { mutableStateOf<String?>(null) }
    var currentContent by remember { mutableStateOf(TextFieldValue("")) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isReadOnly by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val hasChanges = currentContent.text != originalContent && !isLoading && originalContent != null

    LaunchedEffect(uri) {
        DocumentController.read(context, uri, maxSize = 2 * 1024 * 1024)
            .onSuccess { docInfo ->
                val text = String(docInfo.content, DocumentController.sniffCharset(docInfo.content))
                originalContent = text
                currentContent = TextFieldValue(text, selection = TextRange(0))
                isReadOnly = docInfo.isReadOnly
                isLoading = false
            }
            .onFailure { e ->
                error = e.message ?: "Failed to open"
                isLoading = false
            }
    }

    val focusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    LaunchedEffect(isLoading) {
        if (!isLoading) focusRequester.requestFocus()
    }

    val keyHandler: (KeyEvent) -> Boolean = { ev ->
        if (ev.type != KeyEventType.KeyDown) false
        else when (ev.key) {
            Key.Back, Key.Escape -> {
                if (hasChanges) showUnsavedDialog = true else onClose()
                true
            }
            Key.DirectionUp -> {
                val pos = currentContent.selection.start
                if (pos == 0 && currentContent.selection.end == 0) {
                    backButtonFocusRequester.requestFocus()
                    true
                } else {
                    val layout = textLayoutResult
                    if (layout != null) {
                        val currentLine = layout.getLineForOffset(pos)
                        if (currentLine > 0) {
                            val currentLineStart = layout.getLineStart(currentLine)
                            val column = pos - currentLineStart
                            val prevLine = currentLine - 1
                            val prevLineStart = layout.getLineStart(prevLine)
                            val prevLineEnd = layout.getLineEnd(prevLine)
                            val prevLineLen = prevLineEnd - prevLineStart
                            val newPos = prevLineStart + minOf(column, prevLineLen)
                            currentContent = currentContent.copy(selection = TextRange(newPos))
                            true
                        } else {
                            true
                        }
                    } else {
                        true
                    }
                }
            }
            else -> false
        }
    }

    BackHandler(enabled = true) {
        if (hasChanges) showUnsavedDialog = true
        else onClose()
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved changes. Do you want to discard them?") },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false }) { Text("Cancel") }
            }
        )
    }

    saveError?.let { msg ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text("Save Error") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { saveError = null }) { Text("OK") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isReadOnly) {
                            Text(
                                "Read Only",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (hasChanges) {
                            Text(
                                "Unsaved changes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        modifier = Modifier.focusRequester(backButtonFocusRequester),
                        onClick = {
                        if (hasChanges) showUnsavedDialog = true else onClose()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isReadOnly) {
                        IconButton(
                            enabled = hasChanges,
                            onClick = {
                                coroutineScope.launch {
                                    DocumentController.save(context, uri, currentContent.text.toByteArray(Charsets.UTF_8))
                                        .onSuccess { originalContent = currentContent.text }
                                        .onFailure { e -> saveError = "Failed to save: ${e.message}" }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "Save",
                                tint = if (hasChanges) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
            )
        }
    ) { pv ->
        Box(Modifier.fillMaxSize().padding(pv)) {
            when {
                error != null -> {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    BasicTextField(
                        value = currentContent,
                        onValueChange = { newValue ->
                            if (newValue.text == currentContent.text &&
                                newValue.selection.start == newValue.text.length &&
                                newValue.selection.end == newValue.text.length &&
                                currentContent.selection.start < currentContent.text.length
                            ) {
                                currentContent = newValue.copy(selection = currentContent.selection)
                            } else {
                                currentContent = newValue
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent(keyHandler),
                        enabled = !isReadOnly,
                        onTextLayout = { textLayoutResult = it },
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace
                        ),
                    )
                }
            }
        }
    }
}