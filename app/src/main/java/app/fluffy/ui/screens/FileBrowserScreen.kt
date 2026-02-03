package app.fluffy.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.fluffy.helper.cardAsFocusGroup
import app.fluffy.ui.components.ConfirmationDialog
import app.fluffy.ui.components.FileListRow
import app.fluffy.ui.components.toRowModel
import app.fluffy.viewmodel.BrowseLocation
import app.fluffy.viewmodel.FileBrowserState
import app.fluffy.viewmodel.QuickAccessItem
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    state: FileBrowserState,
    isPickerMode: Boolean = false,
    onPickFile: (Uri) -> Unit = {},
    onPickRoot: () -> Unit,
    onOpenDir: (Uri) -> Unit,
    onBack: () -> Unit,
    onExtractArchive: (Uri, Uri) -> Unit,
    onCreateZip: (List<Uri>, String, Uri, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenWith: (Uri, String) -> Unit = { _, _ -> },
    onOpenArchive: (Uri) -> Unit,
    onCopySelected: (List<Uri>) -> Unit = {},
    onMoveSelected: (List<Uri>) -> Unit = {},
    onDeleteSelected: (List<Uri>) -> Unit = {},
    onShareSelected: (List<Uri>) -> Unit = {},
    onPasteClipboard: (String) -> Unit = {},
    onRenameOne: (Uri, String) -> Unit = { _, _ -> },
    onCreate7z: (List<Uri>, String, String?, Uri, Boolean) -> Unit = { _, _, _, _, _ -> },
    onOpenFile: (File) -> Unit = {},
    onQuickAccessClick: (QuickAccessItem) -> Unit = {},
    onRequestPermission: () -> Unit = {},
    onShowQuickAccess: () -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onCreateFile: (String) -> Unit = {},
    showFileCount: Boolean = true,

    pickFolderMode: Boolean = false,
    pickFolderTitle: String = "Choose destination folder",
    onPickFolder: (Uri) -> Unit = {},
    onCancelPickFolder: () -> Unit = {},
) {
    val currentLocation = state.currentLocation
    val canUp = state.stack.size > 1
    val configuration = LocalConfiguration.current
    val isCompactScreen = configuration.screenWidthDp < 600
    val context = LocalContext.current

    val selected = state.selectedItems
    val selectedFiles = remember { mutableStateListOf<File>() }
    var showZipNameDialog by remember { mutableStateOf(false) }
    var show7zDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Uri?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameNewName by remember { mutableStateOf("") }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showPasteClipboardDialog by remember { mutableStateOf(false) }
    var createMenuExpanded by remember { mutableStateOf(false) }

    // Overwrite confirmations (ZIP / 7z created into currentDir)
    var pendingZipName by remember { mutableStateOf<String?>(null) }
    var pending7zName by remember { mutableStateOf<String?>(null) }
    var pending7zPwd by remember { mutableStateOf<String?>(null) }
    var showOverwriteConfirm by remember { mutableStateOf(false) }
    var overwriteMessage by remember { mutableStateOf("") }

    val currentDirUri: Uri? = when (currentLocation) {
        is BrowseLocation.FileSystem -> Uri.fromFile(currentLocation.file)
        is BrowseLocation.SAF -> state.currentDir
        else -> null
    }

    val anySelected = selected.isNotEmpty() || selectedFiles.isNotEmpty()

    val totalItems = when (state.currentLocation) {
        is BrowseLocation.FileSystem -> state.fileItems.size
        is BrowseLocation.SAF -> if (state.currentDir?.scheme in listOf("root", "shizuku"))
            state.shellItems.size else state.items.size
        else -> 0
    }
    val allSelected = (selected.size + selectedFiles.size) == totalItems && totalItems > 0

    fun uriChildExists(parent: Uri, name: String): Boolean {
        return when (parent.scheme) {
            "file" -> {
                val pf = File(parent.path!!)
                File(pf, name).exists()
            }
            "content" -> {
                val p = DocumentFile.fromTreeUri(context, parent)
                    ?: DocumentFile.fromSingleUri(context, parent)
                p?.findFile(name) != null
            }
            "root", "shizuku" -> {
                val base = parent.path ?: "/"
                when (parent.scheme) {
                    "root" -> app.fluffy.io.ShellIo.listRoot(base).any { it.first == name }
                    else -> app.fluffy.io.ShellIo.listShizuku(base).any { it.first == name }
                }
            }
            else -> false
        }
    }

    fun confirmOrCreateZip(name: String) {
        val dir = currentDirUri ?: return
        val sources = selected + selectedFiles.map { Uri.fromFile(it) }
        if (uriChildExists(dir, name)) {
            pendingZipName = name
            overwriteMessage = "A file named \"$name\" already exists here. Overwrite it?"
            showOverwriteConfirm = true
        } else {
            onCreateZip(sources, name, dir, false)
            selected.clear(); selectedFiles.clear()
        }
    }

    fun confirmOrCreate7z(name: String, pwd: String?) {
        val dir = currentDirUri ?: return
        val sources = selected + selectedFiles.map { Uri.fromFile(it) }
        if (uriChildExists(dir, name)) {
            pending7zName = name
            pending7zPwd = pwd
            overwriteMessage = "A file named \"$name\" already exists here. Overwrite it?"
            showOverwriteConfirm = true
        } else {
            onCreate7z(sources, name, pwd?.ifBlank { null }, dir, false)
            selected.clear(); selectedFiles.clear()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = when (currentLocation) {
                                is BrowseLocation.FileSystem -> currentLocation.file.absolutePath
                                is BrowseLocation.SAF -> state.currentDir?.toString() ?: "SAF Location"
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
                            if (totalItems > 0) {
                                IconButton(
                                    onClick = {
                                        if (allSelected) {
                                            selected.clear(); selectedFiles.clear()
                                        } else when (state.currentLocation) {
                                            is BrowseLocation.FileSystem -> {
                                                selectedFiles.clear(); selectedFiles.addAll(state.fileItems)
                                            }
                                            is BrowseLocation.SAF -> {
                                                selected.clear()
                                                selected.addAll(
                                                    if (state.currentDir?.scheme in listOf("root", "shizuku"))
                                                        state.shellItems.map { it.uri }
                                                    else state.items.map { it.uri }
                                                )
                                            }
                                            else -> {}
                                        }
                                    },
                                    enabled = !pickFolderMode
                                ) {
                                    Icon(
                                        Icons.Default.Checklist,
                                        contentDescription = if (allSelected) "Deselect All" else "Select All"
                                    )
                                }
                            }
                            // Create dropdown menu (New File / New Folder)
                            if (!pickFolderMode) {
                                Box {
                                    IconButton(onClick = { createMenuExpanded = true }) {
                                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Create")
                                    }
                                    DropdownMenu(
                                        expanded = createMenuExpanded,
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        onDismissRequest = { createMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("New Folder") },
                                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                            onClick = {
                                                createMenuExpanded = false
                                                showNewFolderDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("New File") },
                                            leadingIcon = { Icon(Icons.Default.Description, null) },
                                            onClick = {
                                                createMenuExpanded = false
                                                showNewFileDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Paste from Clipboard") },
                                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                                            onClick = {
                                                createMenuExpanded = false
                                                showPasteClipboardDialog = true
                                            }
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = onShowQuickAccess) {
                                Icon(Icons.Default.Home, contentDescription = "Home")
                            }
                        }
                        IconButton(onClick = onOpenTasks) {
                            Icon(Icons.Default.Archive, contentDescription = "Tasks")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }

                        // Avoid confusing UX while in folder-pick mode
                        if (!pickFolderMode) {
                            IconButton(onClick = onPickRoot) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "Pick SAF Folder")
                            }
                        }
                    }
                )

                // In-app picker banner
                if (pickFolderMode) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pickFolderTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = onCancelPickFolder) { Text("Cancel") }
                                Button(
                                    onClick = { currentDirUri?.let(onPickFolder) },
                                    enabled = currentDirUri != null
                                ) { Text("Use this folder") }
                            }
                        }
                    }
                } else if (isPickerMode && currentLocation !is BrowseLocation.QuickAccess) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Select a file",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Animated selection action bar (disabled during picker/folder-pick mode)
                AnimatedVisibility(
                    visible = (selected.isNotEmpty() || selectedFiles.isNotEmpty()) &&
                            currentLocation !is BrowseLocation.QuickAccess &&
                            !isPickerMode &&
                            !pickFolderMode,
                    enter = fadeIn(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(200))
                ) {
                    val count = selected.size + selectedFiles.size
                    val allSelectedUris = selected + selectedFiles.map { Uri.fromFile(it) }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    ) {
                        if (isCompactScreen) {
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
                                    ) { Text("Clear") }
                                }

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
                                            Icon(Icons.Default.FolderZip, null, Modifier.size(18.dp))
                                        }
                                    )
                                    AssistChip(
                                        onClick = { show7zDialog = true },
                                        label = { Text("7z") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Archive, null, Modifier.size(18.dp))
                                        }
                                    )

                                    AssistChip(
                                        onClick = { onShareSelected(allSelectedUris) },
                                        label = { Text("Share") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                                        }
                                    )
                                    AssistChip(
                                        onClick = { onCopySelected(allSelectedUris) },
                                        label = { Text("Copy…") },
                                        leadingIcon = {
                                            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                                        }
                                    )
                                    AssistChip(
                                        onClick = { onMoveSelected(allSelectedUris); selected.clear(); selectedFiles.clear() },
                                        label = { Text("Move…") },
                                        leadingIcon = {
                                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, Modifier.size(18.dp))
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
                                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
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
                                                Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                                            }
                                        )
                                        AssistChip(
                                            onClick = {
                                                onOpenWith(allSelectedUris.first(), "")
                                            },
                                            label = { Text("Open With") },
                                            leadingIcon = {
                                                Icon(Icons.Default.OpenWith, null, Modifier.size(18.dp))
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
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

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { showZipNameDialog = true }) { Text("Zip") }
                                    TextButton(onClick = { show7zDialog = true }) { Text("7z") }
                                    TextButton(onClick = { onShareSelected(allSelectedUris) }) { Text("Share") }
                                    TextButton(onClick = { onCopySelected(allSelectedUris) }) { Text("Copy…") }
                                    TextButton(onClick = { onMoveSelected(allSelectedUris) }) { Text("Move…") }
                                    TextButton(onClick = {
                                        onDeleteSelected(allSelectedUris)
                                        selected.clear(); selectedFiles.clear()
                                    }) { Text("Delete") }

                                    if (count == 1) {
                                        TextButton(onClick = {
                                            renameTarget = allSelectedUris.first()
                                            renameNewName = ""
                                            showRenameDialog = true
                                        }) { Text("Rename") }
                                        TextButton(onClick = {
                                            onOpenWith(allSelectedUris.first(), "")
                                        }) { Text("Open With") }
                                    }
                                }

                                Spacer(Modifier.weight(1f))

                                TextButton(onClick = {
                                    selected.clear()
                                    selectedFiles.clear()
                                }) { Text("Clear") }
                            }
                        }
                    }
                }
            }
        }
    ) { it ->
        when (currentLocation) {
            is BrowseLocation.QuickAccess -> {
                QuickAccessView(
                    items = state.quickAccessItems,
                    onItemClick = onQuickAccessClick,
                    onRequestPermission = onRequestPermission,
                    hasPermission = state.canAccessFileSystem,
                    modifier = Modifier.padding(it)
                )
            }

            is BrowseLocation.FileSystem -> {
                if (state.fileItems.isEmpty()) {
                    EmptyFolderView(
                        pv = it,
                        onBack = onBack,
                        canUp = canUp,
                        onShowQuickAccess = onShowQuickAccess
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(it),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(state.fileItems, key = { f -> f.absolutePath }) { file ->
                            val isSelected = !pickFolderMode && selectedFiles.contains(file)
                            val model = remember(file) { file.toRowModel() }

                            FileListRow(
                                model = model,
                                selected = isSelected,
                                hasSelection = !pickFolderMode && anySelected,
                                showFileCount = showFileCount,
                                onToggleSelect = { toggled ->
                                    if (pickFolderMode) return@FileListRow
                                    if (toggled) selectedFiles.add(file) else selectedFiles.remove(file)
                                },
                                onOpenDir = { onOpenFile(file) },
                                onOpenArchive = { onOpenArchive(Uri.fromFile(file)) },
                                onOpenWith = { _, _ -> onOpenWith(Uri.fromFile(file), file.name) },
                                onExtractHere = {
                                    currentDirUri?.let { targetDir -> onExtractArchive(Uri.fromFile(file), targetDir) }
                                },
                                onClick = when {
                                    pickFolderMode -> {
                                        if (file.isDirectory) {
                                            { onOpenFile(file) }
                                        } else null
                                    }
                                    isPickerMode -> {
                                        {
                                            if (file.isDirectory) {
                                                onOpenFile(file)
                                            } else {
                                                onPickFile(Uri.fromFile(file))
                                            }
                                        }
                                    }
                                    else -> null
                                }
                            )
                        }
                    }
                }
            }

            is BrowseLocation.SAF -> {
                val scheme = state.currentDir?.scheme
                val isShell = scheme == "root" || scheme == "shizuku"
                if (isShell) {
                    if (state.shellItems.isEmpty()) {
                        EmptyFolderView(
                            pv = it,
                            onBack = onBack,
                            canUp = canUp,
                            onShowQuickAccess = onShowQuickAccess
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(it),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(state.shellItems, key = { e -> e.uri.toString() }) { entry ->
                                val isSelected = !pickFolderMode && selected.contains(entry.uri)
                                val model = remember(entry.uri) { entry.toRowModel() }

                                FileListRow(
                                    model = model,
                                    selected = isSelected,
                                    hasSelection = !pickFolderMode && anySelected,
                                    showFileCount = showFileCount,
                                    onToggleSelect = { toggled ->
                                        if (pickFolderMode) return@FileListRow
                                        if (toggled) selected.add(entry.uri) else selected.remove(entry.uri)
                                    },
                                    onOpenDir = onOpenDir,
                                    onOpenArchive = onOpenArchive,
                                    onOpenWith = onOpenWith,
                                    onExtractHere = {
                                        currentDirUri?.let { targetDir -> onExtractArchive(entry.uri, targetDir) }
                                    },
                                    onClick = when {
                                        pickFolderMode -> {
                                            if (entry.isDir) {
                                                { onOpenDir(entry.uri) }
                                            } else null
                                        }
                                        isPickerMode -> {
                                            {
                                                if (entry.isDir) {
                                                    onOpenDir(entry.uri)
                                                } else {
                                                    onPickFile(entry.uri)
                                                }
                                            }
                                        }
                                        else -> null
                                    }
                                )
                            }
                        }
                    }
                } else {
                    if (state.items.isEmpty()) {
                        EmptyFolderView(
                            pv = it,
                            onBack = onBack,
                            canUp = canUp,
                            onShowQuickAccess = onShowQuickAccess
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(it),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(state.items, key = { df -> df.uri.toString() }) { df ->
                                val isSelected = !pickFolderMode && selected.contains(df.uri)
                                val model = remember(df.uri) { df.toRowModel() }

                                FileListRow(
                                    model = model,
                                    selected = isSelected,
                                    hasSelection = !pickFolderMode && anySelected,
                                    showFileCount = showFileCount,
                                    onToggleSelect = { toggled ->
                                        if (pickFolderMode) return@FileListRow
                                        if (toggled) selected.add(df.uri) else selected.remove(df.uri)
                                    },
                                    onOpenDir = onOpenDir,
                                    onOpenArchive = onOpenArchive,
                                    onOpenWith = onOpenWith,
                                    onExtractHere = {
                                        currentDirUri?.let { targetDir -> onExtractArchive(df.uri, targetDir) }
                                    },
                                    onClick = when {
                                        pickFolderMode -> {
                                            if (df.isDirectory) {
                                                { onOpenDir(df.uri) }
                                            } else null
                                        }
                                        isPickerMode -> {
                                            {
                                                if (df.isDirectory) {
                                                    onOpenDir(df.uri)
                                                } else {
                                                    onPickFile(df.uri)
                                                }
                                            }
                                        }
                                        else -> null
                                    }
                                )
                            }
                        }
                    }
                }
            }

            null -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(it),
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
                        Text("No location selected", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onShowQuickAccess) { Text("Browse Files") }
                            OutlinedButton(onClick = onPickRoot) { Text("Pick Folder") }
                        }
                    }
                }
            }
        }
    }

    // Create ZIP dialog
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
                    confirmOrCreateZip(name)
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showZipNameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Create 7z dialog
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
                    confirmOrCreate7z(name, pwd.ifBlank { null })
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { show7zDialog = false }) { Text("Cancel") }
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
                            onCreateFolder(folderName)
                            showNewFolderDialog = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showNewFileDialog) {
        var fileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Create New File") },
            text = {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    singleLine = true,
                    label = { Text("File name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (fileName.isNotBlank()) {
                            onCreateFile(fileName)
                            showNewFileDialog = false
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showPasteClipboardDialog) {
        var clipboardFileName by remember { mutableStateOf("clipboard.txt") }
        AlertDialog(
            onDismissRequest = { showPasteClipboardDialog = false },
            title = { Text("Paste from Clipboard") },
            text = {
                OutlinedTextField(
                    value = clipboardFileName,
                    onValueChange = { clipboardFileName = it },
                    singleLine = true,
                    label = { Text("File name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (clipboardFileName.isNotBlank()) {
                            onPasteClipboard(clipboardFileName)
                            showPasteClipboardDialog = false
                        }
                    }
                ) { Text("Paste") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteClipboardDialog = false }) { Text("Cancel") }
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
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Overwrite confirmation (ZIP / 7z)
    if (showOverwriteConfirm) {
        ConfirmationDialog(
            title = "Overwrite file?",
            message = overwriteMessage,
            onConfirm = {
                showOverwriteConfirm = false
                val dir = currentDirUri
                if (dir != null) {
                    val sources = selected + selectedFiles.map { Uri.fromFile(it) }
                    pendingZipName?.let { n ->
                        onCreateZip(sources, n, dir, true)
                        pendingZipName = null
                    }
                    pending7zName?.let { n ->
                        onCreate7z(sources, n, pending7zPwd, dir, true)
                        pending7zName = null
                        pending7zPwd = null
                    }
                    selected.clear(); selectedFiles.clear()
                }
            },
            onDismiss = {
                showOverwriteConfirm = false
                pendingZipName = null
                pending7zName = null
                pending7zPwd = null
            }
        )
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
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Text("Storage Permission Required", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Grant permission to browse files (reopen on granting)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onRequestPermission) { Text("Grant Permission") }
            }
        }
    } else {
        val uniqueItems = remember(items) {
            items.distinctBy { it.file?.absolutePath ?: it.uri?.toString() ?: it.name }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                uniqueItems,
                key = { item ->
                    item.file?.absolutePath
                        ?: item.uri?.toString()
                        ?: item.name
                }
            ) { item ->
                QuickAccessCard(item = item, onClick = { onItemClick(item) })
            }
        }
    }
}

@Composable
private fun QuickAccessCard(item: QuickAccessItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun getIconForQuickAccess(icon: String) = when (icon.lowercase()) {
    "storage" -> Icons.Default.Storage
    "downloads" -> Icons.Default.Download
    "documents" -> Icons.Default.Description
    "pictures" -> Icons.Default.Image
    "music" -> Icons.Default.MusicNote
    "movies" -> Icons.Default.Movie
    "dcim" -> Icons.Default.CameraAlt
    "root" -> Icons.Default.Security
    "shizuku" -> Icons.Default.Settings
    "sd" -> Icons.Filled.SdCard
    "terminal" -> Icons.Default.Settings
    else -> Icons.Default.Folder
}

@Composable
private fun EmptyFolderView(
    pv: PaddingValues,
    onBack: () -> Unit,
    canUp: Boolean,
    onShowQuickAccess: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(pv).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "This folder is empty or inaccessible.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, enabled = canUp) { Text("Go up") }
                Button(onClick = onShowQuickAccess) { Text("Open Quick Access") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnimatedListCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val appeared = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared.value = true }

    AnimatedVisibility(
        visible = appeared.value,
        enter = fadeIn(tween(140)) + slideInVertically(initialOffsetY = { it / 8 }, animationSpec = tween(140))
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .cardAsFocusGroup()
                .animateContentSize(animationSpec = tween(180)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) { content() }
    }
}