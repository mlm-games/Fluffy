package app.fluffy.ui.screens

import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.fluffy.AppGraph
import app.fluffy.archive.ArchiveEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

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

        // Quick pre-check: if the provider reports directory, offer folder open
        val doc = AppGraph.io.docFileFromUri(archiveUri)
        if (doc?.isDirectory == true) {
            loading = false
            error = "Selected item is a folder (mounted by the system)."
            canOpenAsFolder = true
            listing = emptyList()
            return
        }

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
            if (encrypted) {
                askPassword = true
                if (password.isNotBlank()) {
                    error = "Incorrect password. Please try again."
                }
            }
        }.onFailure { ex ->
            loading = false
            listing = emptyList()
            val msg = ex.localizedMessage ?: "Unknown error"
            error = "Failed to open the archive: $msg"

            // If it looks like the provider exposed a directory, offer open-as-folder
            val looksLikeDir =
                msg.contains("EISDIR", ignoreCase = true) ||
                        msg.contains("is a directory", ignoreCase = true)
            canOpenAsFolder = looksLikeDir
        }

        selected.clear()
    }

    LaunchedEffect(archiveUri) { load() }
    LaunchedEffect(password) { if (password.isNotEmpty()) load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title.ifBlank { "Archive" }) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
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
            )
        }
    ) { pv ->
        Column(Modifier.fillMaxSize().padding(pv)) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                if (error != null) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (canOpenAsFolder) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { onOpenAsFolder(archiveUri) }) {
                                Text("Open as folder")
                            }
                        }
                    }
                }
                if (listing.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(listing, key = { it.path }) { e ->
                            ListItem(
                                headlineContent = { Text(e.path) },
                                supportingContent = {
                                    val meta = buildString {
                                        append(if (e.isDir) "Folder" else "File")
                                        if (!e.isDir) append(" • ${e.size} B")
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
                            Divider()
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
            title = { Text("Password required") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This archive is encrypted. Enter the password to list its contents.")
                    OutlinedTextField(
                        value = local,
                        onValueChange = { local = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Password") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askPassword = false
                    password = local
                }) { Text("Unlock") }
            },
            dismissButton = {
                TextButton(onClick = { askPassword = false }) { Text("Cancel") }
            }
        )
    }
}