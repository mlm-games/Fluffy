package app.fluffy.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import coil.request.ImageRequest
import app.fluffy.viewmodel.FileBrowserState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    state: FileBrowserState,
    onPickRoot: () -> Unit,
    onOpenDir: (Uri) -> Unit,
    onBack: () -> Unit,
    onExtractArchive: (Uri, Uri) -> Unit,
    onCreateZip: (List<Uri>, String, Uri) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenArchive: (Uri) -> Unit,
    onCopySelected: (List<Uri>) -> Unit = {},
    onMoveSelected: (List<Uri>) -> Unit = {},
    onDeleteSelected: (List<Uri>) -> Unit = {},
    onRenameOne: (Uri, String) -> Unit = { _, _ -> },
    onCreate7z: (List<Uri>, String, String?, Uri) -> Unit = { _, _, _, _ -> }
) {
    val currentDir = state.currentDir
    val canUp = (state.stack.size > 1)

    val selected = remember { mutableStateListOf<Uri>() }
    var showZipNameDialog by remember { mutableStateOf(false) }
    var show7zDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Uri?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameNewName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(state.currentDir?.toString() ?: "Pick a folder", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { if (canUp) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                    actions = {
                        IconButton(onClick = onOpenTasks) { Icon(Icons.Default.Archive, null) }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, null) }
                        IconButton(onClick = onPickRoot) { Icon(Icons.Default.FolderOpen, null) }
                    }
                )

                if (selected.isNotEmpty() && currentDir != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("${selected.size} selected", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { showZipNameDialog = true }) { Text("Zip") }
                        TextButton(onClick = { show7zDialog = true }) { Text("7z") }
                        TextButton(onClick = { onCopySelected(selected.toList()) }) { Text("Copy to…") }
                        TextButton(onClick = { onMoveSelected(selected.toList()) }) { Text("Move to…") }
                        TextButton(onClick = { onDeleteSelected(selected.toList()); selected.clear() }) { Text("Delete") }
                        if (selected.size == 1) {
                            TextButton(onClick = {
                                renameTarget = selected.first()
                                renameNewName = ""
                                showRenameDialog = true
                            }) { Text("Rename") }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { selected.clear() }) { Text("Clear") }
                    }
                }
            }
        }
    ) { pv ->
        if (currentDir == null) {
            Box(Modifier.fillMaxSize().padding(pv), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Button(onClick = onPickRoot) { Text("Pick a folder") }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pv),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.items, key = { it.uri.toString() }) { df ->
                val isSelected = selected.contains(df.uri)
                FileRow(
                    df = df,
                    selected = isSelected,
                    onToggleSelect = { toggled -> if (toggled) selected.add(df.uri) else selected.remove(df.uri) },
                    onOpenDir = onOpenDir,
                    onOpenArchive = onOpenArchive,
                    onExtractHere = { onExtractArchive(df.uri, currentDir) }
                )
            }
        }
    }

    // ZIP dialog
    if (showZipNameDialog && currentDir != null) {
        var name by remember { mutableStateOf("archive.zip") }
        AlertDialog(
            onDismissRequest = { showZipNameDialog = false },
            title = { Text("Create ZIP") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Filename") })
            },
            confirmButton = {
                TextButton(onClick = {
                    showZipNameDialog = false
                    onCreateZip(selected.toList(), name, currentDir)
                    selected.clear()
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showZipNameDialog = false }) { Text("Cancel") } }
        )
    }

    // 7z dialog with optional password
    if (show7zDialog && currentDir != null) {
        var name by remember { mutableStateOf("archive.7z") }
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { show7zDialog = false },
            title = { Text("Create 7z") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Filename") })
                    OutlinedTextField(
                        value = pwd,
                        onValueChange = { pwd = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Password (optional)") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    show7zDialog = false
                    onCreate7z(selected.toList(), name, pwd.ifBlank { null }, currentDir)
                    selected.clear()
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { show7zDialog = false }) { Text("Cancel") } }
        )
    }

    // Rename dialog
    if (showRenameDialog && renameTarget != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameNewName,
                    onValueChange = { renameNewName = it },
                    singleLine = true,
                    label = { Text("New name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = renameTarget!!
                    onRenameOne(t, renameNewName)
                    showRenameDialog = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FileRow(
    df: DocumentFile,
    selected: Boolean,
    onToggleSelect: (Boolean) -> Unit,
    onOpenDir: (Uri) -> Unit,
    onOpenArchive: (Uri) -> Unit,
    onExtractHere: () -> Unit
) {
    val isArchive = remember(df.name) {
        val n = (df.name ?: "").lowercase()
        n.endsWith(".zip") || n.endsWith(".7z") ||
                n.endsWith(".tar") || n.endsWith(".tgz") || n.endsWith(".tar.gz") ||
                n.endsWith(".tbz2") || n.endsWith(".tar.bz2") ||
                n.endsWith(".txz") || n.endsWith(".tar.xz")
    }

    val mime = df.type ?: ""
    val previewable = remember(mime, df.name) {
        mime.startsWith("image/") || mime.startsWith("video/") || (df.name ?: "").endsWith(".pdf", true)
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (df.isDirectory) onOpenDir(df.uri)
            else if (isArchive) onOpenArchive(df.uri)
            else onToggleSelect(!selected)
        }
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (previewable && !df.isDirectory) {
                AsyncImage(
                    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(df.uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = df.name,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                Icon(
                    when {
                        df.isDirectory -> Icons.Filled.Folder
                        isArchive -> Icons.Filled.FolderZip
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = null
                )
            }

            Column(Modifier.weight(1f)) {
                Text(df.name ?: "(no name)", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                val sub = if (df.isDirectory) "Folder" else (df.type ?: "file")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect(it) })
        }
    }
}
