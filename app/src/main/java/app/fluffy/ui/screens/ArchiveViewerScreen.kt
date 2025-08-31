package app.fluffy.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.fluffy.AppGraph
import app.fluffy.archive.ArchiveEngine
import app.fluffy.data.repository.AppSettings
import app.fluffy.helper.showToast
import app.fluffy.io.FileSystemAccess
import app.fluffy.ui.components.AppTopBar
import app.fluffy.ui.theme.ThemeDefaults
import app.fluffy.util.ArchiveTypes
import app.fluffy.util.UiFormat.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun ArchiveViewerScreen(
    archiveUri: Uri,
    onBack: () -> Unit,
    onExtractTo: (Uri, String?) -> Unit,
    onExtractSelected: (Uri, List<String>, String?) -> Unit = { _, _, _ -> },
    onOpenAsFolder: (Uri) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var listing by remember { mutableStateOf<List<ArchiveEngine.Entry>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var encrypted by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var canOpenAsFolder by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var currentPath by remember { mutableStateOf("") }

    val settings = AppGraph.settings.settingsFlow.collectAsState(initial = AppSettings()).value


    // Visible (top-level directory within currentPath)
    val visible = remember(listing, currentPath) {
        val dirs = mutableMapOf<String, Int>()   // name -> child count
        val files = mutableListOf<ArchiveEngine.Entry>()

        val prefix = currentPath
        listing.forEach { e ->
            val p = e.path.replace('\\', '/')
            if (!p.startsWith(prefix)) return@forEach
            val rest = p.removePrefix(prefix)
            val firstSeg = rest.substringBefore('/', missingDelimiterValue = rest)
            val isDirectChildDir = rest.contains('/')
            if (isDirectChildDir) {
                if (firstSeg.isNotBlank()) dirs[firstSeg] = (dirs[firstSeg] ?: 0) + 1
            } else if (rest.isNotBlank()) {
                files += e.copy(path = rest) // leaf name
            }
        }

        val dirEntries = dirs.keys.sorted().map { name ->
            ArchiveEngine.Entry(path = ensureDirSuffix(name), isDir = true, size = 0, time = 0L)
        }
        dirEntries + files.sortedBy { it.path.lowercase() }
    }

    suspend fun load() {
        loading = true
        error = null
        canOpenAsFolder = false
        val name = AppGraph.io.queryDisplayName(archiveUri)
        title = name

        // Inspect mime and extension
        val doc = AppGraph.io.docFileFromUri(archiveUri)
        val mimeType = doc?.type ?: ""
        val fileName = name.lowercase()

        val isZipLike = ArchiveTypes.infer(fileName) == ArchiveTypes.Kind.ZIP

        // If DocumentFile reports it as directory but it has no archive traits, allow open-as-folder
        if (doc?.isDirectory == true && mimeType.startsWith("vnd.android.document") ) {
            loading = false
            error = "Selected item is a folder."
            canOpenAsFolder = true
            listing = emptyList()
            return
        }

        // Fallback lister for ZIP/APK using Apache Commons Compress
        @Suppress("DEPRECATION")
        fun listWithCommonsZip(): List<ArchiveEngine.Entry> = runCatching {
            AppGraph.io.openIn(archiveUri).use { input ->
                ZipArchiveInputStream(input).use { zin ->
                    val out = mutableListOf<ArchiveEngine.Entry>()
                    var e = zin.nextZipEntry
                    while (e != null) {
                        val n = e.name ?: ""
                        if (n.isNotBlank()) {
                            out.add(
                                ArchiveEngine.Entry(
                                    path = n,
                                    isDir = e.isDirectory,
                                    size = if (e.size >= 0) e.size else 0L,
                                    time = e.time
                                )
                            )
                        }
                        e = zin.nextZipEntry
                    }
                    out
                }
            }
        }.getOrElse { emptyList() }

        val result = runCatching {
            withContext(Dispatchers.IO) {
                AppGraph.archive.list(
                    name,
                    { AppGraph.io.openIn(archiveUri) },
                    password = password.ifBlank { null }?.toCharArray()
                )
            }
        }

        result.onSuccess { res ->
            encrypted = res.encrypted
            listing = res.entries
            loading = false
            if (encrypted && password.isBlank()) {
                askPassword = true
            }
            // Handle parser edge cases
            if (listing.isEmpty() && isZipLike) {
                val fb = withContext(Dispatchers.IO) { listWithCommonsZip() }
                if (fb.isNotEmpty()) listing = fb
            }
        }.onFailure { ex ->
            loading = false
            listing = emptyList()

            if (isZipLike) {
                val fb = withContext(Dispatchers.IO) { listWithCommonsZip() }
                if (fb.isNotEmpty()) {
                    error = null
                    listing = fb
                    return@onFailure
                }
            }

            val msg = ex.localizedMessage ?: "Unknown error"
            error = when {
                msg.contains("EISDIR", ignoreCase = true) ||
                        msg.contains("is a directory", ignoreCase = true) -> {
                    canOpenAsFolder = true
                    "This appears to be a folder, not an archive."
                }
                msg.contains("password", ignoreCase = true) ||
                        msg.contains("Wrong Password", ignoreCase = true) -> {
                    askPassword = true
                    "This archive is password protected."
                }
                msg.contains("not supported", ignoreCase = true) -> {
                    "Archive format not supported."
                }
                else -> "Failed to open archive: $msg"
            }
        }

        selected.clear()
    }

    LaunchedEffect(archiveUri) { load() }
    LaunchedEffect(password) { if (password.isNotEmpty()) load() }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(title.ifBlank { "Archive" }) },
                navigationIcon = {
                    val canGoUp = currentPath.isNotBlank()
                    IconButton(onClick = {
                        if (canGoUp) {
                            val parent = currentPath.trimEnd('/').substringBeforeLast('/', "")
                            currentPath = if (parent.isNotBlank()) "$parent/" else ""
                        } else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    if (listing.isNotEmpty()) {
                        IconButton(onClick = { onExtractTo(archiveUri, password.ifBlank { null }) }) {
                            Icon(Icons.Filled.FileOpen, contentDescription = "Extract all…")
                        }
                        IconButton(onClick = { selectionMode = !selectionMode }) {
                            Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = "Select entries")
                        }
                        if (selectionMode && selected.values.any { it }) {
                            IconButton(onClick = {
                                val paths = selected.entries.filter { it.value }.map { it.key }
                                onExtractSelected(archiveUri, paths, password.ifBlank { null })
                            }) {
                                Icon(Icons.Filled.DoneAll, contentDescription = "Extract selected")
                            }
                        }
                    }
                }
            )
        }
    ) { pv ->
        Column(Modifier.fillMaxSize().padding(pv)) {
            AnimatedContent(
                targetState = Triple(loading, error, listing.isNotEmpty()),
                label = "archive_content_xfade",
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }
            ) { (isLoading, err, hasList) ->
                when {
                    isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    err != null -> {
                        Column(Modifier.padding(16.dp)) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        err,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (canOpenAsFolder) {
                                        Spacer(Modifier.height(12.dp))
                                        Button(
                                            onClick = { onOpenAsFolder(archiveUri) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary
                                            )
                                        ) {
                                            Text("Open as folder")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    hasList -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(visible, key = { it.path }) { e ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    onClick = {
                                        if (e.isDir) {
                                            currentPath = ensureDirSuffix(
                                                if (currentPath.isBlank()) e.path
                                                else currentPath + e.path
                                            )
                                        } else {
                                            val pathInArchive = currentPath + e.path
                                            scope.launch {
                                                val uri = extractEntryToCache(
                                                    pathInArchive = pathInArchive,
                                                    ctx = ctx,
                                                    archiveName = title,
                                                    archive = archiveUri,
                                                    pwd = password.ifBlank { null }
                                                )
                                                if (uri != null) openPreview(uri, e.path, ctx, settings.preferContentResolverMime)
                                            }
                                        }
                                    }
                                ) {
                                    ListItem(
                                        headlineContent = { Text(e.path.trimEnd('/')) },
                                        supportingContent = {
                                            val meta = buildString {
                                                append(if (e.isDir) "Folder" else "File")
                                                if (!e.isDir && e.size > 0) {
                                                    append(" • ${formatSize(e.size)}")
                                                }
                                            }
                                            Text(meta)
                                        },
                                        trailingContent = {
                                            if (selectionMode) {
                                                val fullKey = currentPath + e.path.trimEnd('/')
                                                val checked = selected[fullKey] == true
                                                Checkbox(
                                                    checked = checked,
                                                    onCheckedChange = { selected[fullKey] = it }
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No entries found in this archive.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (askPassword) {
        var local by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text("Password Required") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This archive is encrypted. Enter the password to unlock it.")
                    OutlinedTextField(
                        value = local,
                        onValueChange = { local = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Password") },
                        colors = ThemeDefaults.outlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askPassword = false
                        password = local
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { askPassword = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Helpers

private suspend fun extractEntryToCache(
    pathInArchive: String,
    ctx: Context,
    archiveName: String,
    archive: Uri,
    pwd: String?
): Uri? = withContext(Dispatchers.IO) {
    try {
        fun norm(p: String) = p.trim().trimStart('/').replace('\\', '/')
        val cleanTarget = norm(pathInArchive)
        val outFileName = cleanTarget.substringAfterLast('/').ifEmpty { "item" }
        val out = File(ctx.cacheDir, "preview_${System.currentTimeMillis()}_$outFileName")

        AppGraph.archive.extractAll(
            archiveName.ifBlank { "archive" },
            { AppGraph.io.openIn(archive) },
            create = { p, isDir ->
                val candidate = norm(p)
                if (candidate != cleanTarget || isDir) {
                    object : OutputStream() {
                        override fun write(b: Int) {}
                        override fun write(b: ByteArray, off: Int, len: Int) {}
                    }
                } else {
                    FileOutputStream(out)
                }
            },
            password = pwd?.toCharArray(),
            onProgress = { _, _ -> }
        )
        if (out.exists() && out.length() > 0) Uri.fromFile(out) else null
    } catch (_: Exception) {
        null
    }
}

private fun openPreview(uri: Uri, name: String, ctx: Context, preferMime: Boolean) {
    val finalUri = if (uri.scheme == "file") {
        try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(requireNotNull(uri.path)))
        } catch (_: Exception) {
            null
        }
    } else uri
    if (finalUri == null) {
        ctx.showToast("No app available to open this file")
        return
    }
    val mime = if (preferMime) {
        ctx.contentResolver.getType(finalUri) ?: FileSystemAccess.getMimeType(name)
    } else {
        FileSystemAccess.getMimeType(name)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(finalUri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val pm = ctx.packageManager
    if (intent.resolveActivity(pm) != null) {
        ctx.startActivity(Intent.createChooser(intent, "Open with"))
    } else {
        ctx.showToast("No app available to open this file")
    }
}

private fun ensureDirSuffix(p: String) = if (p.endsWith("/")) p else "$p/"