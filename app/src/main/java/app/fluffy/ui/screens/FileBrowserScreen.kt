package app.fluffy.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.fluffy.helper.DeviceUtils
import app.fluffy.io.FileSystemAccess
import app.fluffy.viewmodel.BrowseLocation
import app.fluffy.viewmodel.FileBrowserState
import app.fluffy.viewmodel.QuickAccessItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
    onCreate7z: (List<Uri>, String, String?, Uri) -> Unit = { _, _, _, _ -> },
    onOpenFile: (File) -> Unit = {},
    onQuickAccessClick: (QuickAccessItem) -> Unit = {},
    onRequestPermission: () -> Unit = {},
    onShowQuickAccess: () -> Unit = {}
) {
    val currentLocation = state.currentLocation
    val canUp = state.stack.size > 1
    val isTV = DeviceUtils.isTV(LocalContext.current)
    val configuration = LocalConfiguration.current
    val isCompactScreen = configuration.screenWidthDp < 600

    val selected = remember { mutableStateListOf<Uri>() }
    val selectedFiles = remember { mutableStateListOf<File>() }
    var showZipNameDialog by remember { mutableStateOf(false) }
    var show7zDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Uri?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameNewName by remember { mutableStateOf("") }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showExtractDialog by remember { mutableStateOf(false) }
    var extractToCurrentDir by remember { mutableStateOf(true) }

    // Get current directory Uri for operations
    val currentDirUri: Uri? = when (currentLocation) {
        is BrowseLocation.FileSystem -> Uri.fromFile(currentLocation.file)
        is BrowseLocation.SAF -> state.currentDir
        else -> null
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = when (currentLocation) {
                                is BrowseLocation.FileSystem -> currentLocation.file.absolutePath
                                is BrowseLocation.SAF -> state.currentDir?.path ?: "SAF Location"
                                is BrowseLocation.QuickAccess -> "Quick Access"
                                null -> "Select a location"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        if (canUp) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            }
                        } else {
                            IconButton(onClick = onShowQuickAccess) {
                                Icon(Icons.Default.Home, contentDescription = "Home")
                            }
                        }
                    },
                    actions = {
                        if (currentLocation !is BrowseLocation.QuickAccess) {
                            IconButton(onClick = { showNewFolderDialog = true }) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                            }
                        }
                        IconButton(onClick = onOpenTasks) {
                            Icon(Icons.Default.Archive, contentDescription = "Tasks")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                        IconButton(onClick = onPickRoot) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Pick SAF Folder")
                        }
                    }
                )

                if ((selected.isNotEmpty() || selectedFiles.isNotEmpty()) &&
                    currentLocation !is BrowseLocation.QuickAccess) {

                    val count = selected.size + selectedFiles.size
                    val allSelectedUris = selected + selectedFiles.map { Uri.fromFile(it) }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ) {
                        if (isCompactScreen && !isTV) {
                            // Mobile layout - vertical with wrapping
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "$count selected",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    TextButton(
                                        onClick = {
                                            selected.clear()
                                            selectedFiles.clear()
                                        }
                                    ) {
                                        Text("Clear")
                                    }
                                }

                                // Scrollable row of action buttons
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    AssistChip(
                                        onClick = { showZipNameDialog = true },
                                        label = { Text("Zip") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.FolderZip,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    )
                                    AssistChip(
                                        onClick = { show7zDialog = true },
                                        label = { Text("7z") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Archive,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    )
                                    AssistChip(
                                        onClick = { onCopySelected(allSelectedUris) },
                                        label = { Text("Copy") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    )
                                    AssistChip(
                                        onClick = { onMoveSelected(allSelectedUris) },
                                        label = { Text("Move") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.DriveFileMove,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    )
                                    AssistChip(
                                        onClick = {
                                            onDeleteSelected(allSelectedUris)
                                            selected.clear()
                                            selectedFiles.clear()
                                        },
                                        label = { Text("Delete") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    )
                                    if (count == 1) {
                                        AssistChip(
                                            onClick = {
                                                renameTarget = allSelectedUris.first()
                                                renameNewName = ""
                                                showRenameDialog = true
                                            },
                                            label = { Text("Rename") },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            // TV/Tablet layout - horizontal
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "$count selected",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    TextButton(onClick = { showZipNameDialog = true }) {
                                        Text("Zip")
                                    }
                                    TextButton(onClick = { show7zDialog = true }) {
                                        Text("7z")
                                    }
                                    TextButton(onClick = { onCopySelected(allSelectedUris) }) {
                                        Text("Copy")
                                    }
                                    TextButton(onClick = { onMoveSelected(allSelectedUris) }) {
                                        Text("Move")
                                    }
                                    TextButton(onClick = {
                                        onDeleteSelected(allSelectedUris)
                                        selected.clear()
                                        selectedFiles.clear()
                                    }) {
                                        Text("Delete")
                                    }
                                    if (count == 1) {
                                        TextButton(onClick = {
                                            renameTarget = allSelectedUris.first()
                                            renameNewName = ""
                                            showRenameDialog = true
                                        }) {
                                            Text("Rename")
                                        }
                                    }
                                }

                                Spacer(Modifier.weight(1f))

                                TextButton(onClick = {
                                    selected.clear()
                                    selectedFiles.clear()
                                }) {
                                    Text("Clear")
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { pv ->
        when (currentLocation) {
            is BrowseLocation.QuickAccess -> {
                QuickAccessView(
                    items = state.quickAccessItems,
                    onItemClick = onQuickAccessClick,
                    onRequestPermission = onRequestPermission,
                    hasPermission = state.canAccessFileSystem,
                    modifier = Modifier.padding(pv)
                )
            }
            is BrowseLocation.FileSystem -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(pv),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.fileItems, key = { it.absolutePath }) { file ->
                        val isSelected = selectedFiles.contains(file)
                        FileSystemRow(
                            file = file,
                            selected = isSelected,
                            onToggleSelect = { toggled ->
                                if (toggled) selectedFiles.add(file) else selectedFiles.remove(file)
                            },
                            onOpenFile = onOpenFile,
                            onOpenArchive = { onOpenArchive(Uri.fromFile(file)) },
                            onExtractHere = {
                                // Extract directly to current directory
                                currentDirUri?.let { targetDir ->
                                    onExtractArchive(Uri.fromFile(file), targetDir)
                                }
                            }
                        )
                    }
                }
            }
            is BrowseLocation.SAF -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(pv),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.items, key = { it.uri.toString() }) { df ->
                        val isSelected = selected.contains(df.uri)
                        FileRow(
                            df = df,
                            selected = isSelected,
                            onToggleSelect = { toggled ->
                                if (toggled) selected.add(df.uri) else selected.remove(df.uri)
                            },
                            onOpenDir = onOpenDir,
                            onOpenArchive = onOpenArchive,
                            onExtractHere = {
                                // Extract directly to current directory
                                currentDirUri?.let { targetDir ->
                                    onExtractArchive(df.uri, targetDir)
                                }
                            }
                        )
                    }
                }
            }
            null -> {
                Box(
                    Modifier.fillMaxSize().padding(pv),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "No location selected",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(onClick = onShowQuickAccess) {
                                Text("Browse Files")
                            }
                            OutlinedButton(onClick = onPickRoot) {
                                Text("Pick Folder")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showZipNameDialog && currentDirUri != null) {
        var name by remember { mutableStateOf("archive.zip") }
        AlertDialog(
            onDismissRequest = { showZipNameDialog = false },
            title = { Text("Create ZIP") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Filename") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showZipNameDialog = false
                    val sources = selected + selectedFiles.map { Uri.fromFile(it) }
                    onCreateZip(sources, name, currentDirUri)
                    selected.clear()
                    selectedFiles.clear()
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showZipNameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (show7zDialog && currentDirUri != null) {
        var name by remember { mutableStateOf("archive.7z") }
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { show7zDialog = false },
            title = { Text("Create 7z") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Filename") }
                    )
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
                    val sources = selected + selectedFiles.map { Uri.fromFile(it) }
                    onCreate7z(sources, name, pwd.ifBlank { null }, currentDirUri)
                    selected.clear()
                    selectedFiles.clear()
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { show7zDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("Create New Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    singleLine = true,
                    label = { Text("Folder name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (folderName.isNotBlank()) {
                            // TODO:, onCreateFolder: (String) -> Unit to FileBrowserScreen and wire it to filesVM.createNewFolder(name)
                            showNewFolderDialog = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

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
                    selected.clear()
                    selectedFiles.clear()
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FileSystemRow(
    file: File,
    selected: Boolean,
    onToggleSelect: (Boolean) -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenArchive: () -> Unit,
    onExtractHere: () -> Unit = {}
) {
    val isArchive = remember(file.name) {
        FileSystemAccess.isArchiveFile(file.name)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            when {
                file.isDirectory -> onOpenFile(file)
                isArchive -> onOpenArchive()
                else -> onToggleSelect(!selected)
            }
        }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when {
                    file.isDirectory -> Icons.Filled.Folder
                    isArchive -> Icons.Filled.FolderZip
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = when {
                    file.isDirectory -> MaterialTheme.colorScheme.primary
                    isArchive -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (file.isDirectory) "Folder" else formatFileSize(file.length()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "• ${formatDate(file.lastModified())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick extract button for archives
            if (isArchive) {
                IconButton(
                    onClick = onExtractHere,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Unarchive,
                        contentDescription = "Extract here",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect(it) }
            )
        }
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
    val isArchive = remember(df.name, df.isDirectory) {
        if (df.isDirectory) false else {
            FileSystemAccess.isArchiveFile(df.name ?: "")
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (df.isDirectory) onOpenDir(df.uri)
            else if (isArchive) onOpenArchive(df.uri)
            else onToggleSelect(!selected)
        }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when {
                    df.isDirectory -> Icons.Filled.Folder
                    isArchive -> Icons.Filled.FolderZip
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = when {
                    df.isDirectory -> MaterialTheme.colorScheme.primary
                    isArchive -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Column(Modifier.weight(1f)) {
                Text(
                    df.name ?: "(no name)",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = if (df.isDirectory) "Folder" else (df.type ?: "file")
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Quick extract button for archives
            if (isArchive) {
                IconButton(
                    onClick = onExtractHere,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Unarchive,
                        contentDescription = "Extract here",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Checkbox(checked = selected, onCheckedChange = { onToggleSelect(it) })
        }
    }
}

@Composable
private fun QuickAccessView(
    items: List<QuickAccessItem>,
    onItemClick: (QuickAccessItem) -> Unit,
    onRequestPermission: () -> Unit,
    hasPermission: Boolean,
    modifier: Modifier = Modifier
) {
    if (!hasPermission) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    "Storage Permission Required",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Grant permission to browse files",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onRequestPermission) {
                    Text("Grant Permission")
                }
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.name }) { item ->
                QuickAccessCard(
                    item = item,
                    onClick = { onItemClick(item) }
                )
            }
        }
    }
}

@Composable
private fun QuickAccessCard(
    item: QuickAccessItem,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = getIconForQuickAccess(item.icon),
                contentDescription = item.name,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun getIconForQuickAccess(icon: String): ImageVector {
    return when (icon.lowercase()) {
        "storage" -> Icons.Default.Storage
        "downloads" -> Icons.Default.Download
        "documents" -> Icons.Default.Description
        "pictures" -> Icons.Default.Image
        "music" -> Icons.Default.MusicNote
        "movies" -> Icons.Default.Movie
        "dcim" -> Icons.Default.CameraAlt
        else -> Icons.Default.Folder
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}