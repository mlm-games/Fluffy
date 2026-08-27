package app.fluffy.ui.screens

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.ViewList
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import app.fluffy.R
import app.fluffy.data.repository.Bookmark
import app.fluffy.io.FileSystemAccess
import app.fluffy.io.ShellIo
import app.fluffy.helper.DeviceUtils
import app.fluffy.helper.cardAsFocusGroup
import app.fluffy.ui.components.AlertBanner
import app.fluffy.ui.components.AlertBannerManager
import app.fluffy.ui.components.ConfirmationDialog
import app.fluffy.ui.components.FileGridItem
import app.fluffy.ui.components.FileListRow
import app.fluffy.ui.components.RowModel
import app.fluffy.ui.components.toRowModel
import app.fluffy.ui.dialogs.AddBookmarkDialog
import app.fluffy.viewmodel.BrowseLocation
import app.fluffy.viewmodel.FileBrowserState
import app.fluffy.util.UiFormat.formatDate
import app.fluffy.util.UiFormat.formatSize
import app.fluffy.viewmodel.QuickAccessItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
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
    onAddBookmark: (String) -> Unit = {},
    onOpenContent: (Uri, String) -> Unit = { _, _ -> },
    onOpenWith: (Uri, String) -> Unit = { _, _ -> },
    onOpenArchive: (Uri) -> Unit,
    onCopySelected: (List<Uri>) -> Unit = {},
    onMoveSelected: (List<Uri>) -> Unit = {},
    onDeleteSelected: (List<Uri>) -> Unit = {},
    onShareSelected: (List<Uri>) -> Unit = {},
    onPasteClipboard: (String) -> Unit = {},
    onRenameOne: (Uri, String, String) -> Unit = { _, _, _ -> },
    onCreate7z: (List<Uri>, String, String?, Uri, Boolean) -> Unit = { _, _, _, _, _ -> },
    onOpenFile: (File) -> Unit = {},
    onQuickAccessClick: (QuickAccessItem) -> Unit = {},
    onBookmarkClick: (Bookmark) -> Unit = {},
    onRemoveBookmark: (Bookmark) -> Unit = {},
    customBookmarks: List<Bookmark> = emptyList(),
    onRequestPermission: () -> Unit = {},
    onShowQuickAccess: () -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onCreateFile: (String) -> Unit = {},
    showFileCount: Boolean = true,
    showStorageInfo: Boolean = true,
    viewMode: Int = 0,
    showThumbnails: Boolean = true,
    onViewModeChange: (Int) -> Unit = {},

    pickFolderMode: Boolean = false,
    pickFolderTitle: String = "Choose destination folder",
    onPickFolder: (Uri) -> Unit = {},
    onCancelPickFolder: () -> Unit = {},
) {
    val shellIo: ShellIo = koinInject()

    val currentLocation = state.currentLocation
    val canUp = state.stack.size > 1
    val canGoBack = currentLocation != null && currentLocation !is BrowseLocation.QuickAccess

    BackHandler(enabled = canGoBack) {
        onBack()
    }

    val context = LocalContext.current

    val selected = state.selectedItems
    val selectedFiles = remember { mutableStateListOf<File>() }
    var showZipNameDialog by remember { mutableStateOf(false) }
    var show7zDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Uri?>(null) }
    var renameOriginalName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTextFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showPasteClipboardDialog by remember { mutableStateOf(false) }
    var createMenuExpanded by remember { mutableStateOf(false) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }
    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var pendingBookmarkName by remember { mutableStateOf("") }
    var showStorageInfoDialog by remember { mutableStateOf(false) }
    var showPropertiesDialog by remember { mutableStateOf(false) }
    var propertiesUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val scope = rememberCoroutineScope()
    var showCtaBanner by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showCtaBanner = AlertBannerManager.shouldShowBanner(context)
    }

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

    fun toggleSelectAll() {
        if (allSelected) {
            selected.clear()
            selectedFiles.clear()
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
    }

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
                    "root" -> shellIo.listRoot(base).any { it.first == name }
                    else -> shellIo.listShizuku(base).any { it.first == name }
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
                if (showCtaBanner) {
                    AlertBanner(
                        text = "F-Droid is under threat. Google is changing the way you install apps on your phone. We need your help.",
                        linkText = "Learn more",
                        url = "https://keepandroidopen.org",
                        onDismiss = {
                            showCtaBanner = false
                            scope.launch { AlertBannerManager.dismissBanner() }
                        }
                    )
                }
                TopAppBar(
                    title = {
                        Text(
                            text = when (currentLocation) {
                                is BrowseLocation.FileSystem -> currentLocation.file.absolutePath
                                is BrowseLocation.SAF -> state.currentDir?.toString() ?: stringResource(R.string.saf_location)
                                is BrowseLocation.QuickAccess -> stringResource(R.string.quick_access)
                                null -> stringResource(R.string.select_a_location)
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
                            // Create dropdown menu (New File / New Folder)
                            if (!pickFolderMode) {
                                Box {
                                    IconButton(onClick = { createMenuExpanded = true }) {
                                        Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.create))
                                    }
                                    DropdownMenu(
                                        expanded = createMenuExpanded,
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        onDismissRequest = { createMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.new_folder)) },
                                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                            onClick = {
                                                createMenuExpanded = false
                                                showNewFolderDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.new_file)) },
                                            leadingIcon = { Icon(Icons.Default.Description, null) },
                                            onClick = {
                                                createMenuExpanded = false
                                                showNewFileDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.paste_from_clipboard)) },
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
                            IconButton(
                                onClick = { onViewModeChange(if (viewMode == 0) 1 else 0) }
                            ) {
                                Icon(
                                    imageVector = if (viewMode == 0) Icons.Filled.GridView
                                                  else Icons.AutoMirrored.Filled.ViewList,
                                    contentDescription = if (viewMode == 0) "Grid view" else "List view"
                                )
                            }
                        }
                        if (showStorageInfo &&
                            currentLocation is BrowseLocation.QuickAccess &&
                            state.canAccessFileSystem
                        ) {
                            IconButton(onClick = { showStorageInfoDialog = true }) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = stringResource(R.string.storage_info)
                                )
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                        }
                        if (!pickFolderMode) {
                            IconButton(onClick = onPickRoot) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "Pick SAF Folder")
                            }
                        }
                        Box {
                            IconButton(onClick = { overflowMenuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = overflowMenuExpanded,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                onDismissRequest = { overflowMenuExpanded = false }
                            ) {
                                if (currentLocation !is BrowseLocation.QuickAccess && totalItems > 0 && !pickFolderMode) {
                                    DropdownMenuItem(
                                        text = { Text(if (allSelected) "Deselect All" else "Select All") },
                                        leadingIcon = { Icon(Icons.Default.Checklist, null) },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            toggleSelectAll()
                                        }
                                    )
                                }
                                if (!pickFolderMode && currentLocation !is BrowseLocation.QuickAccess) {
                                    DropdownMenuItem(
                                        text = { Text("Add Bookmark") },
                                        leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            pendingBookmarkName = when (currentLocation) {
                                                is BrowseLocation.FileSystem -> currentLocation.file.name.ifBlank { currentLocation.file.absolutePath }
                                                is BrowseLocation.SAF -> {
                                                    val path = state.currentDir?.path
                                                    path?.trimEnd('/')?.substringAfterLast('/') ?: ""
                                                }
                                                else -> ""
                                            }
                                            showAddBookmarkDialog = true
                                        }
                                    )
                                }
                                if (!pickFolderMode && currentLocation !is BrowseLocation.QuickAccess && currentDirUri != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.properties)) },
                                        leadingIcon = { Icon(Icons.Default.Info, null) },
                                        onClick = {
                                            overflowMenuExpanded = false
                                            propertiesUris = listOf(currentDirUri)
                                            showPropertiesDialog = true
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Tasks") },
                                    leadingIcon = { Icon(Icons.Default.Archive, null) },
                                    onClick = {
                                        overflowMenuExpanded = false
                                        onOpenTasks()
                                    }
                                )
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
                                    AssistChip(
                                        onClick = {
                                            propertiesUris = allSelectedUris
                                            showPropertiesDialog = true
                                        },
                                        label = { Text(stringResource(R.string.info)) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Info, null, Modifier.size(18.dp))
                                        }
                                    )
                                    if (count == 1) {
                                        AssistChip(
                                            onClick = {
                                                val target = allSelectedUris.first()
                                                val name = getNameForUri(target, currentLocation, state) ?: ""
                                                val ext = name.substringAfterLast('.', "")
                                                val baseLen = if (ext.isNotEmpty()) name.length - ext.length - 1 else name.length
                                                renameTarget = target
                                                renameOriginalName = name
                                                renameTextFieldValue = TextFieldValue(text = name, selection = TextRange(0, baseLen))
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
                    }
                }
            }
        }
    ) {
        val isGrid = viewMode == 1

        @Composable
        fun FileBrowserEntry(
            model: RowModel,
            selected: Boolean,
            hasSelection: Boolean,
            onToggleSelect: (Boolean) -> Unit,
            onOpenDir: (Uri) -> Unit,
            onOpenArchive: (Uri) -> Unit,
            onOpenContent: (Uri, String) -> Unit,
            onOpenWith: (Uri, String) -> Unit,
            onClick: (() -> Unit)?,
            onExtractHere: (() -> Unit)?,
        ) {
            if (isGrid) {
                FileGridItem(
                    model = model,
                    selected = selected,
                    hasSelection = hasSelection,
                    showFileCount = showFileCount,
                    showThumbnail = showThumbnails,
                    onToggleSelect = onToggleSelect,
                    onOpenDir = onOpenDir,
                    onOpenArchive = onOpenArchive,
                    onOpenContent = onOpenContent,
                    onOpenWith = onOpenWith,
                    onClick = onClick,
                    onExtractHere = onExtractHere,
                )
            } else {
                FileListRow(
                    model = model,
                    selected = selected,
                    hasSelection = hasSelection,
                    showFileCount = showFileCount,
                    showThumbnail = showThumbnails,
                    onToggleSelect = onToggleSelect,
                    onOpenDir = onOpenDir,
                    onOpenArchive = onOpenArchive,
                    onOpenContent = onOpenContent,
                    onOpenWith = onOpenWith,
                    onClick = onClick,
                    onExtractHere = onExtractHere,
                )
            }
        }

        when (currentLocation) {
            is BrowseLocation.QuickAccess -> {
                QuickAccessView(
                    items = state.quickAccessItems,
                    customBookmarks = customBookmarks,
                    onItemClick = onQuickAccessClick,
                    onBookmarkClick = onBookmarkClick,
                    onRemoveBookmark = onRemoveBookmark,
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
                    val listMod = Modifier
                        .fillMaxSize()
                        .padding(it)

                    if (isGrid) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 140.dp),
                            modifier = listMod,
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.fileItems, key = { f -> f.absolutePath }) { file ->
                                val isSelected = !pickFolderMode && selectedFiles.contains(file)
                                val model = remember(file) { file.toRowModel() }
                                FileBrowserEntry(
                                    model = model,
                                    selected = isSelected,
                                    hasSelection = !pickFolderMode && anySelected,
                                    onToggleSelect = { toggled ->
                                        if (pickFolderMode) return@FileBrowserEntry
                                        if (toggled) selectedFiles.add(file) else selectedFiles.remove(file)
                                    },
                                    onOpenDir = { onOpenFile(file) },
                                    onOpenArchive = { onOpenArchive(Uri.fromFile(file)) },
                                    onOpenContent = { _, _ -> onOpenContent(Uri.fromFile(file), file.name) },
                                    onOpenWith = { _, _ -> onOpenWith(Uri.fromFile(file), file.name) },
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
                                    },
                                    onExtractHere = {
                                        currentDirUri?.let { targetDir -> onExtractArchive(Uri.fromFile(file), targetDir) }
                                    },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = listMod,
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(state.fileItems, key = { f -> f.absolutePath }) { file ->
                                val isSelected = !pickFolderMode && selectedFiles.contains(file)
                                val model = remember(file) { file.toRowModel() }
                                FileBrowserEntry(
                                    model = model,
                                    selected = isSelected,
                                    hasSelection = !pickFolderMode && anySelected,
                                    onToggleSelect = { toggled ->
                                        if (pickFolderMode) return@FileBrowserEntry
                                        if (toggled) selectedFiles.add(file) else selectedFiles.remove(file)
                                    },
                                    onOpenDir = { onOpenFile(file) },
                                    onOpenArchive = { onOpenArchive(Uri.fromFile(file)) },
                                    onOpenContent = { _, _ -> onOpenContent(Uri.fromFile(file), file.name) },
                                    onOpenWith = { _, _ -> onOpenWith(Uri.fromFile(file), file.name) },
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
                                    },
                                    onExtractHere = {
                                        currentDirUri?.let { targetDir -> onExtractArchive(Uri.fromFile(file), targetDir) }
                                    },
                                )
                            }
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
                        val listMod = Modifier
                            .fillMaxSize()
                            .padding(it)

                        if (isGrid) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 140.dp),
                                modifier = listMod,
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(state.shellItems, key = { e -> e.uri.toString() }) { entry ->
                                    val isSelected = !pickFolderMode && selected.contains(entry.uri)
                                    val model = remember(entry.uri) { entry.toRowModel() }
                                    FileBrowserEntry(
                                        model = model,
                                        selected = isSelected,
                                        hasSelection = !pickFolderMode && anySelected,
                                        onToggleSelect = { toggled ->
                                            if (pickFolderMode) return@FileBrowserEntry
                                            if (toggled) selected.add(entry.uri) else selected.remove(entry.uri)
                                        },
                                        onOpenDir = onOpenDir,
                                        onOpenArchive = onOpenArchive,
                                        onOpenContent = onOpenContent,
                                        onOpenWith = onOpenWith,
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
                                        },
                                        onExtractHere = {
                                            currentDirUri?.let { targetDir -> onExtractArchive(entry.uri, targetDir) }
                                        },
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = listMod,
                                contentPadding = PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(state.shellItems, key = { e -> e.uri.toString() }) { entry ->
                                    val isSelected = !pickFolderMode && selected.contains(entry.uri)
                                    val model = remember(entry.uri) { entry.toRowModel() }
                                    FileBrowserEntry(
                                        model = model,
                                        selected = isSelected,
                                        hasSelection = !pickFolderMode && anySelected,
                                        onToggleSelect = { toggled ->
                                            if (pickFolderMode) return@FileBrowserEntry
                                            if (toggled) selected.add(entry.uri) else selected.remove(entry.uri)
                                        },
                                        onOpenDir = onOpenDir,
                                        onOpenArchive = onOpenArchive,
                                        onOpenContent = onOpenContent,
                                        onOpenWith = onOpenWith,
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
                                        },
                                        onExtractHere = {
                                            currentDirUri?.let { targetDir -> onExtractArchive(entry.uri, targetDir) }
                                        },
                                    )
                                }
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
                        val listMod = Modifier
                            .fillMaxSize()
                            .padding(it)

                        if (isGrid) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 140.dp),
                                modifier = listMod,
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(state.items, key = { df -> df.uri.toString() }) { df ->
                                    val isSelected = !pickFolderMode && selected.contains(df.uri)
                                    val model = remember(df.uri) { df.toRowModel() }
                                    FileBrowserEntry(
                                        model = model,
                                        selected = isSelected,
                                        hasSelection = !pickFolderMode && anySelected,
                                        onToggleSelect = { toggled ->
                                            if (pickFolderMode) return@FileBrowserEntry
                                            if (toggled) selected.add(df.uri) else selected.remove(df.uri)
                                        },
                                        onOpenDir = onOpenDir,
                                        onOpenArchive = onOpenArchive,
                                        onOpenContent = onOpenContent,
                                        onOpenWith = onOpenWith,
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
                                        },
                                        onExtractHere = {
                                            currentDirUri?.let { targetDir -> onExtractArchive(df.uri, targetDir) }
                                        },
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = listMod,
                                contentPadding = PaddingValues(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(state.items, key = { df -> df.uri.toString() }) { df ->
                                    val isSelected = !pickFolderMode && selected.contains(df.uri)
                                    val model = remember(df.uri) { df.toRowModel() }
                                    FileBrowserEntry(
                                        model = model,
                                        selected = isSelected,
                                        hasSelection = !pickFolderMode && anySelected,
                                        onToggleSelect = { toggled ->
                                            if (pickFolderMode) return@FileBrowserEntry
                                            if (toggled) selected.add(df.uri) else selected.remove(df.uri)
                                        },
                                        onOpenDir = onOpenDir,
                                        onOpenArchive = onOpenArchive,
                                        onOpenContent = onOpenContent,
                                        onOpenWith = onOpenWith,
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
                                        },
                                        onExtractHere = {
                                            currentDirUri?.let { targetDir -> onExtractArchive(df.uri, targetDir) }
                                        },
                                    )
                                }
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
            title = { Text(stringResource(R.string.paste_from_clipboard)) },
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

    if (showAddBookmarkDialog) {
        AddBookmarkDialog(
            initialName = pendingBookmarkName,
            onDismiss = { showAddBookmarkDialog = false },
            onConfirm = { name ->
                onAddBookmark(name)
                showAddBookmarkDialog = false
            }
        )
    }

    if (showRenameDialog && renameTarget != null) {
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameTextFieldValue,
                    onValueChange = { renameTextFieldValue = it },
                    singleLine = true,
                    label = { Text("New name") },
                    modifier = Modifier.focusRequester(focusRequester)
                )
            },
            confirmButton = {
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
                TextButton(onClick = {
                    val t = renameTarget!!
                    onRenameOne(t, renameTextFieldValue.text, renameOriginalName)
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
                if (currentDirUri != null) {
                    val sources = selected + selectedFiles.map { Uri.fromFile(it) }
                    pendingZipName?.let { n ->
                        onCreateZip(sources, n, currentDirUri, true)
                        pendingZipName = null
                    }
                    pending7zName?.let { n ->
                        onCreate7z(sources, n, pending7zPwd, currentDirUri, true)
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

    if (showStorageInfoDialog) {
        StorageInfoDialog(onDismiss = { showStorageInfoDialog = false })
    }

    if (showPropertiesDialog && propertiesUris.isNotEmpty()) {
        ItemPropertiesDialog(
            uris = propertiesUris,
            state = state,
            onDismiss = {
                showPropertiesDialog = false
                propertiesUris = emptyList()
            }
        )
    }
}

@Composable
private fun QuickAccessView(
    items: List<QuickAccessItem>,
    customBookmarks: List<Bookmark>,
    onItemClick: (QuickAccessItem) -> Unit,
    onBookmarkClick: (Bookmark) -> Unit,
    onRemoveBookmark: (Bookmark) -> Unit,
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
        var isEditMode by remember { mutableStateOf(false) }
        val hasBookmarks = customBookmarks.isNotEmpty()

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
                QuickAccessCard(item = item, onClick = { if (item.enabled) onItemClick(item) })
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Bookmarks",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (hasBookmarks) {
                        TextButton(onClick = { isEditMode = !isEditMode }) {
                            Text(if (isEditMode) "Done" else "Edit")
                        }
                    }
                }
            }

            items(customBookmarks, key = { "bm_${it.path}_${it.access}" }) { bookmark ->
                BookmarkCard(
                    bookmark = bookmark,
                    isEditMode = isEditMode,
                    onClick = {
                        if (isEditMode) onRemoveBookmark(bookmark) else onBookmarkClick(bookmark)
                    }
                )
            }
        }
    }
}

private data class DeviceStorage(val totalBytes: Long, val freeBytes: Long)

private fun loadDeviceStorage(): DeviceStorage = runCatching {
    val path = Environment.getExternalStorageDirectory().path
    val stat = StatFs(path)
    DeviceStorage(
        totalBytes = stat.totalBytes,
        freeBytes = stat.availableBytes
    )
}.getOrElse { DeviceStorage(0L, 0L) }

@Composable
private fun StorageInfoDialog(onDismiss: () -> Unit) {
    val storage = remember { loadDeviceStorage() }
    val scroll = rememberScrollState()
    val contentFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_info)) },
        text = {
            TvDialogScrollBox(
                scroll = scroll,
                contentFocus = contentFocus,
                confirmFocus = confirmFocus,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StorageInfoCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.total_storage),
                        value = formatSize(storage.totalBytes)
                    )
                    StorageInfoCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.remaining_storage),
                        value = formatSize(storage.freeBytes)
                    )
                }
                StorageUsageBar(
                    usedBytes = (storage.totalBytes - storage.freeBytes).coerceAtLeast(0L),
                    totalBytes = storage.totalBytes
                )
            }
        },
        confirmButton = {
            DialogCloseButton(onDismiss = onDismiss, contentFocus = contentFocus, confirmFocus = confirmFocus)
        }
    )
}

@Composable
private fun StorageInfoCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
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
                imageVector = Icons.Filled.Storage,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StorageUsageBar(usedBytes: Long, totalBytes: Long) {
    val usedRatio = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes) else 0f
    val used = formatSize(usedBytes)
    val total = formatSize(totalBytes)
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { usedRatio.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.used_of_total, used, total),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class ItemProperties(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
    val mimeType: String? = null,
    val childCount: Int? = null,
    val canRead: Boolean? = null,
    val canWrite: Boolean? = null,
)

private fun resolveItemProperties(context: Context, uri: Uri): ItemProperties {
    return when (uri.scheme) {
        "file" -> {
            val path = uri.path
            if (path.isNullOrBlank()) {
                ItemProperties(name = uri.toString(), path = uri.toString(), isDir = false)
            } else {
                val f = File(path)
                val dir = f.isDirectory
                ItemProperties(
                    name = f.name.ifBlank { f.absolutePath },
                    path = f.absolutePath,
                    isDir = dir,
                    sizeBytes = if (dir) null else runCatching { f.length() }.getOrNull(),
                    lastModified = f.lastModified().takeIf { it > 0L },
                    mimeType = if (dir) null else FileSystemAccess.getMimeType(f.name),
                    childCount = if (dir) runCatching { f.listFiles()?.size }.getOrNull() else null,
                    canRead = runCatching { f.canRead() }.getOrNull(),
                    canWrite = runCatching { f.canWrite() }.getOrNull(),
                )
            }
        }
        "content" -> {
            val doc = DocumentFile.fromSingleUri(context, uri)
                ?: DocumentFile.fromTreeUri(context, uri)
            if (doc == null) {
                ItemProperties(name = uri.lastPathSegment ?: "item", path = uri.toString(), isDir = false)
            } else {
                val dir = doc.isDirectory
                val name = doc.name ?: (uri.lastPathSegment ?: "item")
                val childCount = if (dir) {
                    runCatching { doc.listFiles().size }.getOrNull()
                } else null
                ItemProperties(
                    name = name,
                    path = uri.toString(),
                    isDir = dir,
                    sizeBytes = if (dir) null else doc.length().takeIf { it >= 0 },
                    lastModified = doc.lastModified().takeIf { it > 0L },
                    mimeType = if (dir) null else (doc.type ?: FileSystemAccess.getMimeType(name)),
                    childCount = childCount,
                    canRead = doc.canRead(),
                    canWrite = doc.canWrite(),
                )
            }
        }
        "root", "shizuku" -> {
            val p = uri.path ?: "/"
            val name = p.trimEnd('/').substringAfterLast('/').ifBlank { p }
            ItemProperties(
                name = name,
                path = uri.toString(),
                isDir = true,
                sizeBytes = null,
                lastModified = null,
                mimeType = null,
                childCount = null,
                canRead = null,
                canWrite = null,
            )
        }
        else -> ItemProperties(
            name = uri.lastPathSegment ?: uri.toString(),
            path = uri.toString(),
            isDir = false
        )
    }
}

private fun resolveItemProperties(
    context: Context,
    uri: Uri,
    knownIsDir: Boolean?
): ItemProperties {
    val base = resolveItemProperties(context, uri)
    return if (knownIsDir != null && (uri.scheme == "root" || uri.scheme == "shizuku")) {
        base.copy(isDir = knownIsDir)
    } else base
}

@Composable
private fun ItemPropertiesDialog(
    uris: List<Uri>,
    state: FileBrowserState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val contentFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val propsList by produceState<List<ItemProperties>?>(initialValue = null, uris, state.currentLocation) {
        value = withContext(Dispatchers.IO) {
            uris.map { uri ->
                val knownIsDir = when (val loc = state.currentLocation) {
                    is BrowseLocation.FileSystem ->
                        state.fileItems.find { Uri.fromFile(it) == uri }?.isDirectory
                    is BrowseLocation.SAF -> {
                        if (state.currentDir?.scheme in listOf("root", "shizuku")) {
                            state.shellItems.find { it.uri == uri }?.isDir
                        } else {
                            state.items.find { it.uri == uri }?.isDirectory
                        }
                    }
                    else -> null
                }
                resolveItemProperties(context, uri, knownIsDir)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (uris.size == 1) stringResource(R.string.properties)
                else stringResource(R.string.selection_summary)
            )
        },
        text = {
            when (val list = propsList) {
                null -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> {
                    TvDialogScrollBox(
                        scroll = scroll,
                        contentFocus = contentFocus,
                        confirmFocus = confirmFocus,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (list.size == 1) {
                            val p = list.first()
                            PropertyRow(stringResource(R.string.name), p.name)
                            PropertyRow(
                                stringResource(R.string.type),
                                if (p.isDir) stringResource(R.string.folder) else stringResource(R.string.file)
                            )
                            p.sizeBytes?.let {
                                PropertyRow(stringResource(R.string.size), formatSize(it))
                            }
                            p.childCount?.let {
                                PropertyRow(stringResource(R.string.items), it.toString())
                            }
                            p.mimeType?.let {
                                PropertyRow(stringResource(R.string.mime_type), it)
                            }
                            p.lastModified?.let {
                                PropertyRow(
                                    stringResource(R.string.modified),
                                    formatDate(it, "MMM dd, yyyy HH:mm")
                                )
                            }
                            PropertyRow(stringResource(R.string.path), p.path)
                            p.canRead?.let {
                                PropertyRow(
                                    stringResource(R.string.readable),
                                    if (it) stringResource(R.string.yes) else stringResource(R.string.no)
                                )
                            }
                            p.canWrite?.let {
                                PropertyRow(
                                    stringResource(R.string.writable),
                                    if (it) stringResource(R.string.yes) else stringResource(R.string.no)
                                )
                            }
                        } else {
                            val folders = list.count { it.isDir }
                            val files = list.size - folders
                            val totalSize = list.mapNotNull { it.sizeBytes }.sum()
                            Text(
                                stringResource(R.string.folders_count, folders),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                stringResource(R.string.files_count, files),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (totalSize > 0L) {
                                Text(
                                    stringResource(R.string.total_size, formatSize(totalSize)),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            list.take(20).forEach { p ->
                                Text(
                                    "• ${p.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (list.size > 20) {
                                Text(
                                    "… +${list.size - 20}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            DialogCloseButton(onDismiss = onDismiss, contentFocus = contentFocus, confirmFocus = confirmFocus)
        }
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    if (value.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TvDialogScrollBox(
    scroll: ScrollState,
    contentFocus: FocusRequester,
    confirmFocus: FocusRequester,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val ctx = LocalContext.current
    val isTv = remember { DeviceUtils.isTV(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .then(
                    if (isTv) Modifier
                        .focusRequester(contentFocus)
                        .focusable()
                        .onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.DirectionUp -> {
                                    if (scroll.value > 0) {
                                        scope.launch {
                                            scroll.animateScrollTo((scroll.value - 200).coerceAtLeast(0))
                                        }
                                        true
                                    } else false
                                }
                                Key.DirectionDown -> {
                                    if (scroll.value < scroll.maxValue) {
                                        scope.launch {
                                            scroll.animateScrollTo((scroll.value + 200).coerceAtMost(scroll.maxValue))
                                        }
                                        true
                                    } else {
                                        scope.launch { confirmFocus.requestFocus() }
                                        true
                                    }
                                }
                                else -> false
                            }
                        }
                    else Modifier
                ),
            verticalArrangement = verticalArrangement
        ) {
            content()
        }
        if (isTv && focused) {
            if (scroll.canScrollBackward) {
                Text(
                    "↑",
                    modifier = Modifier.align(Alignment.TopCenter),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (scroll.canScrollForward) {
                Text(
                    "↓",
                    modifier = Modifier.align(Alignment.BottomCenter),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun DialogCloseButton(
    onDismiss: () -> Unit,
    contentFocus: FocusRequester,
    confirmFocus: FocusRequester
) {
    val scope = rememberCoroutineScope()
    TextButton(
        onClick = onDismiss,
        modifier = Modifier
            .focusRequester(confirmFocus)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                    scope.launch { contentFocus.requestFocus() }
                    true
                } else false
            }
    ) { Text(stringResource(R.string.close)) }
}

@Composable
private fun QuickAccessCard(item: QuickAccessItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.enabled) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
            }
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
                tint = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BookmarkCard(
    bookmark: Bookmark,
    isEditMode: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEditMode) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
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
                imageVector = if (isEditMode) Icons.Default.Delete else Icons.Default.Bookmark,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (isEditMode) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (isEditMode) "Tap to remove" else bookmark.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (isEditMode) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

private fun getNameForUri(uri: Uri, location: BrowseLocation?, state: FileBrowserState): String? {
    return when (location) {
        is BrowseLocation.FileSystem -> state.fileItems.find { Uri.fromFile(it) == uri }?.name
        is BrowseLocation.SAF -> {
            if (state.currentDir?.scheme in listOf("root", "shizuku")) {
                state.shellItems.find { it.uri == uri }?.name
            } else {
                state.items.find { it.uri == uri }?.name
            }
        }
        else -> null
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
