package app.fluffy.ui.viewers

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fluffy.AppGraph
import app.fluffy.data.repository.AppSettings
import app.fluffy.ui.components.ConfirmationDialog
import app.fluffy.ui.theme.FluffyTheme
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import io.github.mlmgames.settings.ui.dialogs.ConfirmationDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

class RichTextEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)

        val uri = intent?.data ?: run { finish(); return }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: AppGraph.io.queryDisplayName(uri)

        setContent {
            val settings = AppGraph.settings.settingsFlow.collectAsState(initial = AppSettings()).value
            val dark = when (settings.themeMode) {
                0 -> isSystemInDarkTheme()
                1 -> false
                else -> true
            }
            FluffyTheme(darkTheme = dark, useAuroraTheme = settings.useAuroraTheme) {
                RichTextEditorScreen(uri, title) { finish() }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            if (imm.isActive && currentFocus != null) {
                imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
                // Don't return true here immediately, allow the Compose BackHandler to run
                // unless you want to consume the event just for hiding the keyboard.
                // Usually returning super.onKeyDown allows standard propagation.
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object { const val EXTRA_TITLE = "title" }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun RichTextEditorScreen(uri: Uri, title: String, onClose: () -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val richTextState = rememberRichTextState()

    // State
    var originalContent by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var hasChanges by remember { mutableStateOf(false) }
    var isReadOnly by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // Load Content
    LaunchedEffect(uri) {
        val max = 2 * 1024 * 1024 // 2 MB guard
        runCatching {
            withContext(Dispatchers.IO) {
                // Check Writability
                val canWrite = try {
                    if (uri.scheme == "file") File(uri.path!!).canWrite()
                    else {
                        AppGraph.io.openOut(uri).close()
                        true
                    }
                } catch (e: Exception) { false }

                isReadOnly = !canWrite

                // Read File
                AppGraph.io.openIn(uri).use { input ->
                    if (input.available() > max) throw IllegalStateException("File too large to edit")
                    val bytes = input.readBytes()
                    val charset = sniffCharset(bytes) ?: Charsets.UTF_8
                    String(bytes, charset)
                }
            }
        }.onSuccess {
            originalContent = it
            richTextState.setHtml(it)
            isLoading = false
        }.onFailure {
            error = it.message ?: "Failed to open"
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        if (!isReadOnly) {
            snapshotFlow { richTextState.annotatedString }
                .debounce(1800) // Wait
                .distinctUntilChanged()
                .onEach {
                    if (!isLoading && originalContent != null) {
                        val currentHtml = richTextState.toHtml()
                        hasChanges = currentHtml != originalContent
                    }
                }
                .launchIn(this)
        }
    }

    // Back Handler
    BackHandler(enabled = true) {
        if (hasChanges) showUnsavedDialog = true
        else onClose()
    }

    // Dialogs
    if (showUnsavedDialog) {
        ConfirmationDialog(
            title = "Unsaved Changes",
            message = "You have unsaved changes. Do you want to discard them?",
            confirmText = "Discard",
            dismissText = "Cancel",
            onConfirm = {
                showUnsavedDialog = false
                onClose()
            },
            onDismiss = { showUnsavedDialog = false }
        )
    }

    if (showLinkDialog) {
        LinkDialog(
            onDismiss = { showLinkDialog = false },
            onAddLink = { text, url ->
                richTextState.addLink(text = text, url = url)
                showLinkDialog = false
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

    // UI
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
                    IconButton(onClick = {
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
                                    try {
                                        val html = richTextState.toHtml()
                                        saveContent(uri, html)
                                        originalContent = html
                                        hasChanges = false
                                    } catch (e: Exception) {
                                        saveError = "Failed to save: ${e.message}"
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "Save",
                                tint = if (hasChanges) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
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
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                else -> {
                    Column(Modifier.fillMaxSize()) {
                        // Toolbar
                        if (!isReadOnly) {
                            RichTextToolbar(
                                state = richTextState,
                                onAddLink = { showLinkDialog = true }
                            )
                            HorizontalDivider()
                        }

                        RichTextEditor(
                            state = richTextState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            enabled = !isReadOnly,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RichTextToolbar(state: RichTextState, onAddLink: () -> Unit) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Helper to DRY up icon buttons
        @Composable
        fun FormatButton(
            isActive: Boolean,
            icon: androidx.compose.ui.graphics.vector.ImageVector,
            desc: String,
            onClick: () -> Unit
        ) {
            IconButton(
                onClick = onClick,
                colors = if (isActive) {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else IconButtonDefaults.iconButtonColors()
            ) {
                Icon(icon, contentDescription = desc)
            }
        }

        FormatButton(
            isActive = state.currentSpanStyle.fontWeight == FontWeight.Bold,
            icon = Icons.Default.FormatBold,
            desc = "Bold"
        ) { state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }

        FormatButton(
            isActive = state.currentSpanStyle.fontStyle == FontStyle.Italic,
            icon = Icons.Default.FormatItalic,
            desc = "Italic"
        ) { state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }

        FormatButton(
            isActive = state.currentSpanStyle.textDecoration == TextDecoration.Underline,
            icon = Icons.Default.FormatUnderlined,
            desc = "Underline"
        ) { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }

        FormatButton(
            isActive = state.currentSpanStyle.textDecoration == TextDecoration.LineThrough,
            icon = Icons.Default.FormatStrikethrough,
            desc = "Strikethrough"
        ) { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) }

        VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))

        FormatButton(
            isActive = state.isUnorderedList,
            icon = Icons.AutoMirrored.Filled.FormatListBulleted,
            desc = "Bullet List"
        ) { state.toggleUnorderedList() }

        FormatButton(
            isActive = state.isOrderedList,
            icon = Icons.Default.FormatListNumbered,
            desc = "Numbered List"
        ) { state.toggleOrderedList() }

        VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))

        FormatButton(
            isActive = state.isCodeSpan,
            icon = Icons.Default.Code,
            desc = "Code"
        ) { state.toggleCodeSpan() }

        IconButton(onClick = onAddLink) {
            Icon(Icons.Default.Link, contentDescription = "Link")
        }
    }
}

@Composable
private fun LinkDialog(
    onDismiss: () -> Unit,
    onAddLink: (String, String) -> Unit
) {
    var linkText by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    label = { Text("Link Text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (linkText.isNotBlank() && url.isNotBlank()) {
                                onAddLink(linkText, url)
                            }
                        }
                    )
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (linkText.isNotBlank() && url.isNotBlank()) {
                        onAddLink(linkText, url)
                    }
                },
                enabled = linkText.isNotBlank() && url.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private suspend fun saveContent(uri: Uri, htmlContent: String) {
    withContext(Dispatchers.IO) {
        AppGraph.io.openOut(uri).use { output ->
            output.write(htmlContent.toByteArray(Charsets.UTF_8))
        }
    }
}

private fun sniffCharset(bytes: ByteArray): Charset? {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return Charsets.UTF_8
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
    return null
}