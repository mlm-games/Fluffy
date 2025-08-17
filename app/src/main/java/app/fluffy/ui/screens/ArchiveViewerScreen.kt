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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.fluffy.AppGraph
import app.fluffy.archive.ArchiveEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveViewerScreen(
    archiveUri: Uri,
    onBack: () -> Unit,
    onExtractTo: (Uri, String?) -> Unit,                  // full extract
    onExtractSelected: (Uri, List<String>, String?) -> Unit = { _, _, _ -> } // partial extract
) {
    var listing by remember { mutableStateOf<List<ArchiveEngine.Entry>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var encrypted by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Boolean>() } // path -> selected

    suspend fun load() {
        loading = true
        error = null
        val name = AppGraph.io.queryDisplayName(archiveUri)
        title = name

        val doc = AppGraph.io.docFileFromUri(archiveUri)
        if (doc?.isDirectory == true) {
            loading = false
            error = "Selected item is a folder, not an archive."
            listing = emptyList()
            return
        }

        // show error instead of crashing
        val res = runCatching {
            withContext(Dispatchers.IO) {
                AppGraph.archive.list(
                    name,
                    { AppGraph.io.openIn(archiveUri) },
                    password = password.ifBlank { null }?.toCharArray()
                )
            }
        }

        res.onSuccess { result ->
            encrypted = result.encrypted
            listing = result.entries
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
            error = when (ex) {
                is java.io.FileNotFoundException -> "Could not open the archive."
                else -> "Failed to open the archive: ${ex.localizedMessage ?: "Unknown error"}"
            }
        }
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
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
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
