package app.fluffy.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.fluffy.AppGraph
import app.fluffy.archive.ArchiveEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveViewerScreen(
    archiveUri: Uri,
    onBack: () -> Unit,
    onExtractTo: (Uri, String?) -> Unit,
    onExtractSelected: (Uri, List<String>, String?) -> Unit = { _, _, _ -> },
    onOpenAsFolder: (Uri) -> Unit
) {
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

    val isZipLike = fileName.endsWith(".zip") ||
            fileName.endsWith(".apk") ||
            fileName.endsWith(".jar")

    // Check if it's actually an archive file
    val isArchive = mimeType.contains("zip") ||
            mimeType.contains("x-7z") ||
            mimeType.contains("x-tar") ||
            fileName.endsWith(".zip") ||
            fileName.endsWith(".7z") ||
            fileName.endsWith(".tar") ||
            fileName.endsWith(".tar.gz") ||
            fileName.endsWith(".tar.bz2") ||
            fileName.endsWith(".tar.xz")

    // If DocumentFile reports it as directory but it has archive extension,
    // it might be a mounted archive by the system
    if (doc?.isDirectory == true && !isArchive) {
        loading = false
        error = "Selected item is a folder."
        canOpenAsFolder = true
        listing = emptyList()
        return
    }

    // Fallback lister for ZIP/APK using Apache Commons Compress (more tolerant than zip4j)
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
        // Handle case where archives with only folders appear empty due to strict parser
        if (listing.isEmpty() && isZipLike) {
            val fb = withContext(Dispatchers.IO) { listWithCommonsZip() }
            if (fb.isNotEmpty()) listing = fb
        }
    }.onFailure { ex ->
        loading = false
        listing = emptyList()

        // Fallback to Commons Compress for ZIP/APK on failure (e.g., zip4j ZipException)
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
            msg.contains("password", ignoreCase = true) -> {
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
            TopAppBar(
                title = { Text(title.ifBlank { "Archive" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    if (listing.isNotEmpty()) {
                        IconButton(onClick = { onExtractTo(archiveUri, password.ifBlank { null }) }) {
                            Icon(Icons.Default.FileOpen, contentDescription = "Extract all…")
                        }
                        IconButton(onClick = { selectionMode = !selectionMode }) {
                            Icon(Icons.Default.Checklist, contentDescription = "Select entries")
                        }
                        if (selectionMode && selected.values.any { it }) {
                            IconButton(onClick = {
                                val paths = selected.entries.filter { it.value }.map { it.key }
                                onExtractSelected(archiveUri, paths, password.ifBlank { null })
                            }) {
                                Icon(Icons.Default.DoneAll, contentDescription = "Extract selected")
                            }
                        }
                    }
                }
            )
        }
    ) { pv ->
        Column(Modifier.fillMaxSize().padding(pv)) {
            when {
                loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Column(Modifier.padding(16.dp)) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    error!!,
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
                listing.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(listing, key = { it.path }) { e ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                ListItem(
                                    headlineContent = { Text(e.path) },
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
                                            val checked = selected[e.path] == true
                                            Checkbox(
                                                checked = checked,
                                                onCheckedChange = { selected[e.path] = it }
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
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
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

// Helper function to format file sizes
private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}