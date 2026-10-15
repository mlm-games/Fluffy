package app.fluffy.ui.viewers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.charset.Charset
import androidx.core.content.ContextCompat
import app.fluffy.R
import app.fluffy.data.repository.AppSettings
import app.fluffy.data.repository.SettingsRepository
import app.fluffy.io.DocumentController
import app.fluffy.io.SafIo
import app.fluffy.ui.theme.FluffyTheme
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class TextEditorActivity : ComponentActivity(), KoinComponent {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ContextCompat.getContextForLanguage(newBase))
    }

    private val io: SafIo by inject()
    private val settings: SettingsRepository by inject()

    private var editTarget by mutableStateOf<Pair<Uri, String?>?>(null)

    private fun acceptIntent(intent: Intent?) {
        val uri = intent?.targetUri() ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        editTarget = uri to (intent.getStringExtra(EXTRA_TITLE) ?: io.queryDisplayName(uri))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    private fun Intent.targetUri(): Uri? =
        data ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

    private fun intentTargetUri(): Uri? = intent?.targetUri()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intentTargetUri() == null) { finish(); return }
        acceptIntent(intent)

        setContent {
            val coroutineScope = rememberCoroutineScope()
            val s = settings.settingsFlow.collectAsState(initial = AppSettings()).value
            val dark = when (s.themeMode) {
                0 -> isSystemInDarkTheme()
                1 -> false
                else -> true
            }
            FluffyTheme(darkTheme = dark, useAuroraTheme = s.useAuroraTheme) {
                val target = editTarget ?: return@FluffyTheme
                key(target.first) {
                    TextEditorScreen(
                        uri = target.first,
                        title = target.second ?: "",
                        settings = s,
                        onSettingsChange = { update -> coroutineScope.launch { settings.updateSettings(update) } },
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    companion object { const val EXTRA_TITLE = "title" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextEditorScreen(
    uri: Uri,
    title: String,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onClose: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    var originalContent by remember { mutableStateOf<String?>(null) }
    var currentContent by remember { mutableStateOf(TextFieldValue("")) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saveErrorOffersSaveAs by remember { mutableStateOf(false) }
    var loadedCharset by remember { mutableStateOf(Charsets.UTF_8) }
    var showCharsetWarn by remember { mutableStateOf(false) }
    var rawBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showCharsetPicker by remember { mutableStateOf(false) }
    var showFontSizePicker by remember { mutableStateOf(false) }
    var pendingCharset by remember { mutableStateOf<Charset?>(null) }
    var isTemporary by remember { mutableStateOf(false) }
    var saveTarget by remember { mutableStateOf<Uri?>(null) }

    val wordWrap = settings.editorWordWrap
    val lineNumbers = settings.editorLineNumbers
    val syntaxOn = settings.editorSyntax
    val fontSize = settings.editorFontSize
    val language = EditorLanguage.forName(title)

    val context = LocalContext.current
    val hasChanges = currentContent.text != originalContent && !isLoading && originalContent != null
    val failedToOpen = stringResource(R.string.failed_to_open)
    val failedToSave = stringResource(R.string.failed_to_save)

    fun applyCharset(bytes: ByteArray, cs: Charset, caret: Int) {
        val text = String(bytes, cs)
        loadedCharset = cs
        showCharsetWarn = cs != Charsets.UTF_8
        originalContent = text
        currentContent = TextFieldValue(text, selection = TextRange(caret.coerceIn(0, text.length)))
    }

    LaunchedEffect(uri) {
        isLoading = true
        error = null
        saveError = null
        showCharsetWarn = false
        rawBytes = null
        DocumentController.read(context, uri, maxSize = 2 * 1024 * 1024)
            .onSuccess { docInfo ->
                rawBytes = docInfo.content
                applyCharset(docInfo.content, DocumentController.sniffCharset(docInfo.content), 0)
                isTemporary = docInfo.isReadOnly
                saveTarget = uri
                isLoading = false
            }
            .onFailure { e ->
                error = e.message ?: failedToOpen
                isLoading = false
            }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { target ->
        if (target != null) {
            val bytes = currentContent.text.toByteArray(loadedCharset)
            coroutineScope.launch {
                DocumentController.save(context, target, bytes)
                    .onSuccess {
                        saveTarget = target
                        isTemporary = false
                        originalContent = currentContent.text
                    }
                    .onFailure { e ->
                        saveError = "$failedToSave: ${e.message}"
                        saveErrorOffersSaveAs = true
                    }
            }
        }
    }

    fun commitSave() {
        val bytes = currentContent.text.toByteArray(loadedCharset)
        coroutineScope.launch {
            val result = saveTarget?.let { DocumentController.save(context, it, bytes) }
            if (result?.isSuccess == true) {
                originalContent = currentContent.text
            } else {
                saveError = "$failedToSave: ${result?.exceptionOrNull()?.message}"
                saveErrorOffersSaveAs = true
            }
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
            title = { Text(stringResource(R.string.unsaved_changes)) },
            text = { Text(stringResource(R.string.unsaved_changes_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    onClose()
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    saveError?.let { msg ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.save_error)) },
            text = { Text(msg) },
            confirmButton = {
                if (saveErrorOffersSaveAs) {
                    TextButton(onClick = {
                        saveError = null
                        saveErrorOffersSaveAs = false
                        saveAsLauncher.launch(title)
                    }) { Text(stringResource(R.string.save_as)) }
                } else {
                    TextButton(onClick = { saveError = null }) { Text(stringResource(R.string.ok)) }
                }
            },
            dismissButton = if (saveErrorOffersSaveAs) {
                {
                    TextButton(onClick = { saveError = null }) {
                        Text(stringResource(R.string.close))
                    }
                }
            } else {
                null
            }
        )
    }

    if (showCharsetPicker) {
        EditorChoiceDialog(
            title = stringResource(R.string.editor_charset),
            options = CHARSET_NAMES,
            selected = loadedCharset.name(),
            onSelect = { name ->
                showCharsetPicker = false
                val cs = runCatching { Charset.forName(name) }.getOrNull()
                val bytes = rawBytes
                if (cs != null && bytes != null && cs != loadedCharset) {
                    if (hasChanges) {
                        pendingCharset = cs
                    } else {
                        applyCharset(bytes, cs, currentContent.selection.start)
                    }
                }
            },
            onDismiss = { showCharsetPicker = false }
        )
    }

    if (showFontSizePicker) {
        EditorChoiceDialog(
            title = stringResource(R.string.editor_font_size),
            options = FONT_SIZES.map(Int::toString),
            selected = fontSize.toString(),
            onSelect = { value ->
                showFontSizePicker = false
                value.toIntOrNull()?.let { size ->
                    onSettingsChange { settings -> settings.copy(editorFontSize = size) }
                }
            },
            onDismiss = { showFontSizePicker = false }
        )
    }

    pendingCharset?.let { cs ->
        AlertDialog(
            onDismissRequest = { pendingCharset = null },
            title = { Text(stringResource(R.string.editor_charset)) },
            text = { Text(stringResource(R.string.charset_discards_changes)) },
            confirmButton = {
                TextButton(onClick = {
                    rawBytes?.let { applyCharset(it, cs, 0) }
                    pendingCharset = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCharset = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
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
                        if (isTemporary) {
                            Text(
                                stringResource(R.string.editing_temporary_copy),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        } else if (hasChanges) {
                            Text(
                                stringResource(R.string.unsaved_changes),
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        enabled = hasChanges,
                        onClick = { commitSave() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = stringResource(R.string.save),
                            tint = if (hasChanges) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.editor_charset) + ": " + loadedCharset.name()
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Translate, null) },
                                onClick = { showMenu = false; showCharsetPicker = true }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (language == EditorLanguage.PLAIN) stringResource(R.string.editor_syntax)
                                        else stringResource(R.string.editor_syntax) + " · " + language.label
                                    )
                                },
                                trailingIcon = { if (syntaxOn) Icon(Icons.Default.Check, null) },
                                onClick = {
                                    showMenu = false
                                    onSettingsChange { it.copy(editorSyntax = !it.editorSyntax) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_line_numbers)) },
                                trailingIcon = { if (lineNumbers) Icon(Icons.Default.Check, null) },
                                onClick = {
                                    showMenu = false
                                    onSettingsChange { it.copy(editorLineNumbers = !it.editorLineNumbers) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_word_wrap)) },
                                trailingIcon = { if (wordWrap) Icon(Icons.Default.Check, null) },
                                onClick = {
                                    showMenu = false
                                    onSettingsChange { it.copy(editorWordWrap = !it.editorWordWrap) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_font_size) + "  $fontSize") },
                                leadingIcon = { Icon(Icons.Default.FormatSize, null) },
                                onClick = { showMenu = false; showFontSizePicker = true }
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
                    val scroll = rememberScrollState()
                    val hScroll = rememberScrollState()
                    val textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.45f).sp
                    )
                    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    val activeLineColor = MaterialTheme.colorScheme.primary
                    val primaryColor = MaterialTheme.colorScheme.primary
                    val secondaryColor = MaterialTheme.colorScheme.secondary
                    val tertiaryColor = MaterialTheme.colorScheme.tertiary
                    val transform = if (syntaxOn) {
                        remember(language, primaryColor, tertiaryColor, secondaryColor, gutterColor) {
                            EditorHighlighting(
                                language = language,
                                keyword = primaryColor,
                                string = tertiaryColor,
                                comment = gutterColor,
                                number = secondaryColor,
                                heading = primaryColor
                            )
                        }
                    } else {
                        null
                    }
                    val lineCount = remember(currentContent.text) {
                        currentContent.text.count { it == '\n' } + 1
                    }
                    val activeLine = textLayoutResult
                        ?.getLineForOffset(currentContent.selection.start.coerceAtMost(currentContent.text.length))
                        ?: 0
                    val gutterWidth = remember(lineCount, fontSize) {
                        (lineCount.toString().length * fontSize * 0.62f + 22f).dp
                    }
                    val gutter = remember(currentContent.text, activeLine, fontSize) {
                        buildAnnotatedString {
                            for (i in 1..lineCount) {
                                if (i > 1) append('\n')
                                val start = length
                                append(i.toString())
                                if (i - 1 == activeLine) {
                                    addStyle(
                                        SpanStyle(activeLineColor, fontWeight = FontWeight.Bold),
                                        start,
                                        length
                                    )
                                }
                            }
                        }
                    }
                    val contentWidth = remember(currentContent.text, fontSize) {
                        if (wordWrap) {
                            Dp.Unspecified
                        } else {
                            val longest = currentContent.text.split('\n')
                                .maxOfOrNull { it.length } ?: 0
                            ((longest + 2) * fontSize * 0.7f + 28f).dp.coerceIn(320.dp, 8000.dp)
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                        if (showCharsetWarn) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(R.string.loaded_charset_notice),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                        if (isTemporary) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResource(R.string.temporary_copy_notice),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { saveAsLauncher.launch(title) }) {
                                        Text(stringResource(R.string.save_as))
                                    }
                                }
                            }
                        }
                        Box(Modifier.fillMaxSize()) {
                            BasicTextField(
                                value = currentContent,
                                onValueChange = { newValue ->
                                    if (newValue.text == currentContent.text &&
                                        newValue.selection.start == newValue.text.length &&
                                        newValue.selection.end == newValue.text.length &&
                                        currentContent.selection.start < currentContent.text.length
                                    ) {
                                        currentContent =
                                            newValue.copy(selection = currentContent.selection)
                                    } else {
                                        currentContent = newValue
                                    }
                                },
                                modifier = (if (wordWrap) Modifier.fillMaxWidth() else Modifier.width(contentWidth))
                                    .fillMaxHeight()
                                    .verticalScroll(scroll)
                                    .then(if (wordWrap) Modifier else Modifier.horizontalScroll(hScroll))
                                    .padding(start = if (lineNumbers) gutterWidth else 0.dp)
                                    .focusRequester(focusRequester)
                                    .onPreviewKeyEvent(keyHandler),
                                enabled = true,
                                onTextLayout = { textLayoutResult = it },
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                                visualTransformation = transform
                                    ?: VisualTransformation.None,
                                textStyle = textStyle,
                            )
                            if (lineNumbers) {
                                Text(
                                    text = gutter,
                                    style = textStyle,
                                    color = gutterColor,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .offset { IntOffset(0, -scroll.value) }
                                        .width(gutterWidth)
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(end = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val CHARSET_NAMES = listOf(
    "UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE", "US-ASCII",
    "ISO-8859-1", "ISO-8859-2", "ISO-8859-3", "ISO-8859-4", "ISO-8859-5",
    "ISO-8859-6", "ISO-8859-7", "ISO-8859-8", "ISO-8859-9", "ISO-8859-10",
    "ISO-8859-11", "ISO-8859-13", "ISO-8859-14", "ISO-8859-15",
    "windows-1250", "windows-1251", "windows-1252", "windows-1253",
    "windows-1254", "windows-1255", "windows-1256", "windows-1257",
    "windows-1258", "Shift_JIS", "EUC-JP", "ISO-2022-JP", "GBK", "GB18030",
    "Big5", "EUC-KR", "KOI8-R", "KOI8-U", "MacRoman", "IBM437"
)

private val FONT_SIZES = listOf(10, 12, 14, 16, 18, 20, 24, 28)

@Composable
private fun EditorChoiceDialog(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = option,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (option == selected) {
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}
