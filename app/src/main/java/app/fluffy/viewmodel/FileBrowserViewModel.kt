package app.fluffy.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluffy.R
import app.fluffy.archive.ArchiveEngine
import app.fluffy.data.repository.Bookmark
import app.fluffy.data.repository.BookmarksRepository
import app.fluffy.data.repository.SettingsRepository
import app.fluffy.ui.components.snackbar.SnackbarManager
import app.fluffy.io.FileSystemAccess
import app.fluffy.io.SafIo
import app.fluffy.io.ShellEntry
import app.fluffy.shell.RootAccess
import app.fluffy.shell.ShizukuAccess
import app.fluffy.util.FileSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

sealed class BrowseLocation {
    data class SAF(val uri: Uri) : BrowseLocation()
    data class FileSystem(val file: File) : BrowseLocation()
    object QuickAccess : BrowseLocation()
}

data class QuickAccessItem(
    val name: String,
    val icon: String,
    val file: File?,
    val uri: Uri?,
    val enabled: Boolean = true
)

data class FileBrowserState(
    val currentLocation: BrowseLocation? = null,
    val currentDir: Uri? = null,
    val currentFile: File? = null,
    val stack: List<BrowseLocation> = emptyList(),
    val items: List<DocumentFile> = emptyList(),   // SAF/content items
    val shellItems: List<ShellEntry> = emptyList(),// root/shizuku items
    val fileItems: List<File> = emptyList(),       // file:// items
    val quickAccessItems: List<QuickAccessItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val canAccessFileSystem: Boolean = false,
    val pendingAction: PendingAction = PendingAction.None,
    val selectedItems: MutableList<Uri> = mutableStateListOf(),
    val isPickerMode: Boolean = false,
    val pickerMimeType: String? = null
)

class FileBrowserViewModel(
    private val appContext: Context,
    private val io: SafIo,
    private val fileSystemAccess: FileSystemAccess,
    @Suppress("unused") private val archive: ArchiveEngine,
    private val settings: SettingsRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val snackbarManager: SnackbarManager
) : ViewModel() {

    private val _state = MutableStateFlow(FileBrowserState())
    val state: StateFlow<FileBrowserState> = _state

    private var showHidden: Boolean = false
    private var showRoot: Boolean = false
    private var showShizuku: Boolean = false
    private var sortMode: Int = 0
    private var sortReverse: Boolean = false

    val customBookmarks: StateFlow<List<Bookmark>> = bookmarksRepository.bookmarks
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            settings.settingsFlow.collect { s ->
                val changedHidden = s.showHidden != showHidden
                val changedRoot = s.enableRoot != showRoot
                val changedShizuku = s.enableShizuku != showShizuku
                val changedSort = s.defaultSort != sortMode
                val changedReverse = s.sortReverse != sortReverse
                showHidden = s.showHidden
                showRoot = s.enableRoot
                showShizuku = s.enableShizuku
                sortMode = s.defaultSort
                sortReverse = s.sortReverse
                if (changedHidden || changedSort || changedReverse) refresh()
                if (changedRoot || changedShizuku) {
                    if (_state.value.currentLocation is BrowseLocation.QuickAccess) {
                        showQuickAccess()
                    }
                }
            }
        }
        initializeFileAccess()
    }

    private fun getString(id: Int, vararg args: Any): String = appContext.getString(id, *args)

    private fun cannotCreate(@StringRes format: Int, e: Exception): String =
        getString(format, e.message ?: getString(R.string.create_failed))

    private fun initializeFileAccess() {
        viewModelScope.launch {
            val hasAccess = fileSystemAccess.hasStoragePermission()
            _state.value = _state.value.copy(canAccessFileSystem = hasAccess)
            if (hasAccess) showQuickAccess() else {
                _state.value = _state.value.copy(
                    quickAccessItems = getQuickAccessItems(),
                    currentLocation = BrowseLocation.QuickAccess
                )
            }
        }
    }

    fun addBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            bookmarksRepository.addBookmark(bookmark)
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            bookmarksRepository.removeBookmark(bookmark)
        }
    }

    fun buildBookmarkFromCurrentLocation(name: String): Bookmark? {
        val st = _state.value
        val location = st.currentLocation ?: return null
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return null

        return when (location) {
            is BrowseLocation.FileSystem -> {
                val path = location.file.absolutePath
                Bookmark(name = trimmedName, path = path, access = "file")
            }
            is BrowseLocation.SAF -> {
                val uri = st.currentDir ?: location.uri
                val path = uri.toString()
                val access = when (uri.scheme) {
                    "root" -> "root"
                    "shizuku" -> "shizuku"
                    "content" -> "content"
                    "file" -> "file"
                    else -> uri.scheme
                }
                Bookmark(name = trimmedName, path = path, access = access)
            }
            is BrowseLocation.QuickAccess -> null
        }
    }

    fun navigateToBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            val access = bookmark.access
            val rawPath = bookmark.path

            when (access) {
                "root", "shizuku" -> {
                    val normalized = rawPath.removePrefix("root://").removePrefix("shizuku://")
                    val path = if (normalized.startsWith("/")) normalized else "/$normalized"
                    val scheme = if (access == "root") "root" else "shizuku"
                    if (scheme == "shizuku" && !ShizukuAccess.isAvailable()) {
                        snackbarManager.show(
                            getString(
                                R.string.cannot_access,
                                bookmark.name,
                                getString(R.string.shizuku_not_running)
                            )
                        )
                        return@launch
                    }
                    if (scheme == "root" && !RootAccess.isAvailable()) {
                        snackbarManager.show(
                            getString(
                                R.string.cannot_access,
                                bookmark.name,
                                getString(R.string.root_access_not_available)
                            )
                        )
                        return@launch
                    }
                    val uri = Uri.Builder().scheme(scheme).path(path).build()
                    openDir(uri)
                    return@launch
                }
                "content", "file" -> {
                    val uri = runCatching { rawPath.toUri() }.getOrNull()
                    if (uri != null) {
                        if (uri.scheme == "content") {
                            openDir(uri)
                            return@launch
                        }
                        if (uri.scheme == "file") {
                            val file = File(uri.path ?: "")
                            openFileSystemPath(file)
                            return@launch
                        }
                    }
                }
            }

            if (rawPath.startsWith("content://")) {
                val uri = rawPath.toUri()
                openDir(uri)
                return@launch
            }

            if (rawPath.startsWith("file://")) {
                val uri = rawPath.toUri()
                val file = File(uri.path ?: "")
                openFileSystemPath(file)
                return@launch
            }

            val file = File(rawPath)
            if (file.exists() && file.canRead()) {
                openFileSystemPath(file)
                return@launch
            }

            if (showShizuku && ShizukuAccess.isAvailable()) {
                val uri = Uri.Builder().scheme("shizuku").path(file.path).build()
                openDir(uri)
                return@launch
            }

            if (showRoot && RootAccess.isAvailable()) {
                val uri = Uri.Builder().scheme("root").path(file.path).build()
                openDir(uri)
                return@launch
            }

            if (showShizuku || showRoot) {
                val missing = if (!file.exists()) {
                    getString(R.string.path_does_not_exist)
                } else {
                    getString(R.string.path_is_not_readable)
                }
                snackbarManager.show(getString(R.string.cannot_access, bookmark.name, missing))
                return@launch
            }

            val reason = when {
                !showRoot && !showShizuku -> getString(R.string.enable_root_or_shizuku)
                showShizuku && !ShizukuAccess.isAvailable() -> getString(R.string.shizuku_not_running)
                showRoot && !RootAccess.isAvailable() -> getString(R.string.root_access_not_available)
                else -> getString(R.string.path_not_accessible)
            }

            snackbarManager.show(
                message = getString(R.string.cannot_access, bookmark.name, reason),
                actionLabel = if (!showRoot && !showShizuku) getString(R.string.settings) else null,
            )
        }
    }

    private fun getQuickAccessItems(): List<QuickAccessItem> {
        val items = mutableListOf<QuickAccessItem>()

        val storageRoots = fileSystemAccess.getAllStorageRoots()

        val seenFilePaths = mutableSetOf<String>()
        storageRoots.forEachIndexed { idx, root ->
            if (seenFilePaths.add(root.absolutePath)) {
                val label = if (idx == 0) getString(R.string.internal_storage)
                else getString(R.string.external_storage)
                items.add(
                    QuickAccessItem(
                        name = label,
                        icon = if (idx == 0) "storage" else "sd",
                        file = root,
                        uri = null
                    )
                )
            }
        }

        data class FolderEntry(val label: String, val icon: String, val dir: String)

        // Common public folders (unchanged)
        val folders = listOf(
            FolderEntry(getString(R.string.downloads), "downloads", Environment.DIRECTORY_DOWNLOADS),
            FolderEntry(getString(R.string.documents), "documents", Environment.DIRECTORY_DOCUMENTS),
            FolderEntry(getString(R.string.pictures), "pictures", Environment.DIRECTORY_PICTURES),
            FolderEntry(getString(R.string.music), "music", Environment.DIRECTORY_MUSIC),
            FolderEntry(getString(R.string.movies), "movies", Environment.DIRECTORY_MOVIES),
            FolderEntry("DCIM", "dcim", Environment.DIRECTORY_DCIM)
        )
        folders.forEach { entry ->
            val file = Environment.getExternalStoragePublicDirectory(entry.dir)
            if (file.exists()) {
                items.add(QuickAccessItem(entry.label, entry.icon, file, null))
            }
        }

        if (showRoot) {
            val available = RootAccess.isAvailable()
            items.add(
                QuickAccessItem(
                    if (available) getString(R.string.root_slash)
                    else getString(R.string.root_slash_unavailable),
                    "root",
                    null,
                    Uri.Builder().scheme("root").path("/").build(),
                    enabled = available
                )
            )

            val rootSeenPaths = mutableSetOf<String>()
            val internalPath = "/storage/emulated/0"
            rootSeenPaths.add(internalPath)
            items.add(
                QuickAccessItem(
                    getString(R.string.internal_storage_root),
                    "root",
                    null,
                    Uri.Builder().scheme("root").path(internalPath).build(),
                    enabled = available
                )
            )

            storageRoots.forEachIndexed { idx, root ->
                val path = root.absolutePath
                if (rootSeenPaths.add(path)) {
                    val name = if (idx == 0) getString(R.string.internal_root)
                    else getString(R.string.external_root)
                    items.add(
                        QuickAccessItem(
                            name = "$name ($path)",
                            icon = if (idx == 0) "root" else "sd",
                            file = null,
                            uri = Uri.Builder().scheme("root").path(path).build(),
                            enabled = available
                        )
                    )
                }
            }

            items.add(
                QuickAccessItem(
                    getString(R.string.termux_home_root),
                    "terminal",
                    null,
                    Uri.Builder().scheme("root")
                        .path("/data/data/com.termux/files/home")
                        .build(),
                    enabled = available
                )
            )
            items.add(
                QuickAccessItem(
                    getString(R.string.termux_storage_root),
                    "terminal",
                    null,
                    Uri.Builder().scheme("root")
                        .path("/data/data/com.termux/files/home/storage")
                        .build(),
                    enabled = available
                )
            )
        }

        if (showShizuku) {
            val available = ShizukuAccess.isAvailable()
            items.add(
                QuickAccessItem(
                    if (available) getString(R.string.shizuku_slash)
                    else getString(R.string.shizuku_slash_not_running),
                    "shizuku",
                    null,
                    Uri.Builder().scheme("shizuku").path("/").build(),
                    enabled = available
                )
            )

            val shizukuSeenPaths = mutableSetOf<String>()
            val internalPath = "/storage/emulated/0"
            shizukuSeenPaths.add(internalPath)
            items.add(
                QuickAccessItem(
                    getString(R.string.internal_storage_shizuku),
                    "shizuku",
                    null,
                    Uri.Builder().scheme("shizuku").path(internalPath).build(),
                    enabled = available
                )
            )

            storageRoots.forEachIndexed { idx, root ->
                val path = root.absolutePath
                if (shizukuSeenPaths.add(path)) {
                    val name = if (idx == 0) getString(R.string.internal_shizuku)
                    else getString(R.string.external_shizuku)
                    items.add(
                        QuickAccessItem(
                            name = "$name ($path)",
                            icon = if (idx == 0) "shizuku" else "sd",
                            file = null,
                            uri = Uri.Builder().scheme("shizuku").path(path).build(),
                            enabled = available
                        )
                    )
                }
            }

            items.add(
                QuickAccessItem(
                    getString(R.string.termux_home_shizuku),
                    "terminal",
                    null,
                    Uri.Builder().scheme("shizuku")
                        .path("/data/data/com.termux/files/home")
                        .build(),
                    enabled = available
                )
            )
            items.add(
                QuickAccessItem(
                    getString(R.string.termux_storage_shizuku),
                    "terminal",
                    null,
                    Uri.Builder().scheme("shizuku")
                        .path("/data/data/com.termux/files/home/storage")
                        .build(),
                    enabled = available
                )
            )
        }

        return items
    }

    fun showQuickAccess() {
        updatePermissionFlag()
        _state.value = _state.value.copy(
            currentLocation = BrowseLocation.QuickAccess,
            currentDir = null,
            currentFile = null,
            items = emptyList(),
            shellItems = emptyList(),
            fileItems = emptyList(),
            quickAccessItems = getQuickAccessItems(),
            stack = listOf(BrowseLocation.QuickAccess)
        )
    }

    fun openFileSystemPath(file: File) {
        viewModelScope.launch {
            if (!file.exists()) {
                _state.value = _state.value.copy(error = getString(R.string.path_does_not_exist))
                return@launch
            }
            val location = BrowseLocation.FileSystem(file)
            val base = listOf(BrowseLocation.QuickAccess)
            val newStack = base + location
            val items = withContext(Dispatchers.IO) { loadFileSystemItems(file) }
            _state.value.selectedItems.clear()
            _state.value = _state.value.copy(
                currentLocation = location,
                currentFile = file,
                currentDir = null,
                stack = newStack,
                fileItems = items,
                items = emptyList(),
                shellItems = emptyList(),
                quickAccessItems = emptyList(),
                isLoading = false,
                error = null
            )
        }
    }

    private fun loadFileSystemItems(directory: File): List<File> {
        if (!fileSystemAccess.hasStoragePermission()) return emptyList()
        val files = directory.listFiles()?.toList() ?: emptyList()
        val filtered = files.filter { if (showHidden) true else !it.name.startsWith(".") }
        return FileSort.sortFiles(filtered, sortMode, sortReverse)
    }

    fun openRoot(uri: Uri) { // for picked SAF trees; not used for root/shizuku
        viewModelScope.launch {
            val location = BrowseLocation.SAF(uri)
            val st = _state.value
            val items = withContext(Dispatchers.IO) {
                runCatching { io.listChildren(uri) }.getOrDefault(emptyList())
            }
            _state.value.selectedItems.clear()
            _state.value = st.copy(
                currentLocation = location,
                currentDir = uri,
                stack = listOf(BrowseLocation.QuickAccess, location),
                items = filtered(items),
                shellItems = emptyList(),
                fileItems = emptyList(),
                quickAccessItems = emptyList(),
                error = null
            )
        }
    }

    fun openDir(uri: Uri, fromHistory: Boolean = false) {
        viewModelScope.launch {
            val location = BrowseLocation.SAF(uri)
            val st = _state.value
            if (st.currentDir == uri && st.currentLocation == location) {
                refresh()
                return@launch
            }
            val anchored = if (st.stack.isNotEmpty() && st.stack.first() is BrowseLocation.QuickAccess)
                st.stack else listOf(BrowseLocation.QuickAccess)
            val newStack = if (fromHistory) {
                anchored + location
            } else {
                val idx = anchored.indexOf(location)
                if (idx >= 0) anchored.take(idx + 1) else anchored + location
            }

            if (uri.scheme == "root" || uri.scheme == "shizuku") {
                val shellList = withContext(Dispatchers.IO) {
                    runCatching { io.listShell(uri) }.getOrDefault(emptyList())
                }
                _state.value.selectedItems.clear()
                _state.value = st.copy(
                    currentLocation = BrowseLocation.SAF(uri),
                    currentDir = uri,
                    stack = newStack,
                    shellItems = filteredShell(shellList),
                    items = emptyList(),
                    fileItems = emptyList(),
                    quickAccessItems = emptyList(),
                    error = null
                )
            } else {
                val safList = withContext(Dispatchers.IO) {
                    runCatching { io.listChildren(uri) }.getOrDefault(emptyList())
                }
                _state.value.selectedItems.clear()
                _state.value = st.copy(
                    currentLocation = BrowseLocation.SAF(uri),
                    currentDir = uri,
                    stack = newStack,
                    items = filtered(safList),
                    shellItems = emptyList(),
                    fileItems = emptyList(),
                    quickAccessItems = emptyList(),
                    error = null
                )
            }
        }
    }

    fun goUp(): Boolean {
        val st = _state.value
        when (val current = st.currentLocation) {
            is BrowseLocation.FileSystem -> {
                val parent = current.file.parentFile
                if (parent != null && parent.exists()) {
                    _state.value = st.copy(stack = if (st.stack.size > 1) st.stack.dropLast(1) else st.stack)
                    openFileSystemPath(parent)
                    return true
                } else if (st.stack.size > 1) {
                    val previous = st.stack.dropLast(1).last()
                    navigateToLocation(previous)
                    return true
                } else {
                    showQuickAccess()
                    return false
                }
            }
            is BrowseLocation.SAF -> {
                val cur = st.currentDir
                if (cur != null && (cur.scheme == "root" || cur.scheme == "shizuku")) {
                    val parent = upOfShell(cur)
                    if (parent != null) {
                        _state.value = _state.value.copy(
                            stack = if (_state.value.stack.size > 1) _state.value.stack.dropLast(1) else _state.value.stack
                        )
                        openDir(parent, fromHistory = true)
                        return true
                    } else {
                        showQuickAccess()
                        return false
                    }
                } else {

                    if (st.stack.size > 1) {
                        val previous = st.stack.dropLast(1).last()
                        navigateToLocation(previous)
                        return true
                    } else {
                        showQuickAccess()
                        return false
                    }
                }
            }
            is BrowseLocation.QuickAccess, null -> { return false }
        }
    }


    private fun upOfShell(uri: Uri): Uri? {
        val scheme = uri.scheme ?: return null
        val raw = uri.path ?: "/"
        val path = raw.trimEnd('/')
        if (path.isEmpty() || path == "/") return null
        val idx = path.lastIndexOf('/')
        val parentPath = if (idx <= 0) "/" else path.take(idx)
        return Uri.Builder().scheme(scheme).path(parentPath).build()
    }

    private fun navigateToLocation(location: BrowseLocation) {
        val st = _state.value
        val popped = if (st.stack.size > 1) st.stack.dropLast(1) else st.stack
        _state.value = st.copy(stack = popped)
        when (location) {
            is BrowseLocation.FileSystem -> openFileSystemPath(location.file)
            is BrowseLocation.SAF -> openDir(location.uri, fromHistory = true)
            is BrowseLocation.QuickAccess -> showQuickAccess()
        }
    }


    fun refresh() {
        viewModelScope.launch {
            updatePermissionFlag()
            val st = _state.value
            when (val location = st.currentLocation) {
                is BrowseLocation.FileSystem -> {
                    val items = withContext(Dispatchers.IO) { loadFileSystemItems(location.file) }
                    _state.value = _state.value.copy(fileItems = items)
                }
                is BrowseLocation.SAF -> {
                    val uri = location.uri
                    if (uri.scheme == "root" || uri.scheme == "shizuku") {
                        val items = withContext(Dispatchers.IO) {
                            runCatching { io.listShell(uri) }.getOrDefault(emptyList())
                        }
                        _state.value = _state.value.copy(shellItems = filteredShell(items))
                    } else {
                        val items = withContext(Dispatchers.IO) {
                            runCatching { io.listChildren(uri) }.getOrDefault(emptyList())
                        }
                        _state.value = _state.value.copy(items = filtered(items))
                    }
                }
                is BrowseLocation.QuickAccess -> {
                    val items = withContext(Dispatchers.IO) { getQuickAccessItems() }
                    _state.value = _state.value.copy(quickAccessItems = items)
                }
                null -> {}
            }
        }
    }

    fun refreshCurrentDir() = refresh()

    private fun filtered(list: List<DocumentFile>): List<DocumentFile> {
        val base = if (showHidden) list else list.filter { f -> !(f.name ?: "").startsWith(".") }
        return FileSort.sortDocuments(base, sortMode, sortReverse)
    }

    private fun filteredShell(list: List<ShellEntry>): List<ShellEntry> {
        val base = if (showHidden) list else list.filter { !it.name.startsWith(".") }
        return FileSort.sortShell(base, sortMode, sortReverse)
    }

    fun openFile(file: File) {
        if (file.isDirectory) {
            openFileSystemPath(file)
        } else {
            viewModelScope.launch {
                handleFileOpen(Uri.fromFile(file))
            }
        }
    }

    fun openQuickAccessItem(item: QuickAccessItem) {
        if (!item.enabled) return
        item.file?.let { file ->
            if (file.exists()) {
                openFileSystemPath(file)
            }
        }
        item.uri?.let { uri ->
            openDir(uri)
        }
    }

    fun openDefaultPickerDir(): Boolean {
        val dirs = fileSystemAccess.getAllStorageRoots()
        val downloads = runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
        val candidates = listOfNotNull(
            downloads?.takeIf { it.exists() && it.isDirectory },
            dirs.firstOrNull { it.exists() && it.isDirectory }
        ).distinctBy { it.absolutePath }
        val target = candidates.firstOrNull() ?: return false
        openFileSystemPath(target)
        return true
    }

    private suspend fun handleFileOpen(uri: Uri) {
        _state.value = _state.value.copy(pendingAction = PendingAction.OpenFile(uri))
    }

    fun clearPendingAction() {
        _state.value = _state.value.copy(pendingAction = PendingAction.None)
    }

    fun toggleSelection(uri: Uri) {
        val list = _state.value.selectedItems
        if (list.contains(uri)) list.remove(uri) else list.add(uri)
        _state.value = _state.value.copy()
    }

    fun setSelected(uris: Collection<Uri>) {
        val list = _state.value.selectedItems
        list.clear()
        list.addAll(uris)
        _state.value = _state.value.copy()
    }

    private fun validateNewName(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") {
            viewModelScope.launch { snackbarManager.show(getString(R.string.invalid_name)) }
            return false
        }
        if ('/' in name || '\\' in name || '\u0000' in name) {
            viewModelScope.launch { snackbarManager.show(getString(R.string.name_no_slash)) }
            return false
        }
        return true
    }

    fun createNewFolder(name: String) {
        if (!validateNewName(name)) return
        viewModelScope.launch {
            val st = _state.value
            when (val location = st.currentLocation) {
                is BrowseLocation.FileSystem -> {
                    try {
                        val newFolder = withContext(Dispatchers.IO) {
                            val f = File(location.file, name)
                            if (f.exists()) throw IllegalStateException(getString(R.string.already_exists))
                            if (!f.mkdirs()) throw java.io.IOException(getString(R.string.mkdir_failed))
                            f
                        }
                        refresh()
                    } catch (e: Exception) {
                        snackbarManager.show(cannotCreate(R.string.cannot_create_folder, e))
                    }
                }
                is BrowseLocation.SAF -> {
                    st.currentDir?.let { parent ->
                        try {
                            withContext(Dispatchers.IO) { io.createDir(parent, name) }
                            refresh()
                        } catch (e: Exception) {
                            snackbarManager.show(cannotCreate(R.string.cannot_create_folder, e))
                        }
                    }
                }
                else -> {}
            }
        }
    }

    fun createNewFile(name: String) {
        if (!validateNewName(name)) return
        viewModelScope.launch {
            val st = _state.value
            when (val location = st.currentLocation) {
                is BrowseLocation.FileSystem -> {
                    try {
                        withContext(Dispatchers.IO) {
                            val newFile = File(location.file, name)
                            if (newFile.exists()) throw IllegalStateException(getString(R.string.already_exists))
                            if (!newFile.createNewFile()) throw java.io.IOException(getString(R.string.create_failed))
                        }
                        refresh()
                    } catch (e: Exception) {
                        snackbarManager.show(cannotCreate(R.string.cannot_create_file, e))
                    }
                }
                is BrowseLocation.SAF -> {
                    st.currentDir?.let { parent ->
                        try {
                            withContext(Dispatchers.IO) { io.createFile(parent, name) }
                            refresh()
                        } catch (e: Exception) {
                            snackbarManager.show(cannotCreate(R.string.cannot_create_file, e))
                        }
                    }
                }
                else -> {}
            }
        }
    }

    fun createFileFromClipboard(name: String, content: String) {
        if (!validateNewName(name)) return
        viewModelScope.launch {
            val st = _state.value
            when (val location = st.currentLocation) {
                is BrowseLocation.FileSystem -> {
                    try {
                        withContext(Dispatchers.IO) {
                            val newFile = File(location.file, name)
                            if (newFile.exists()) throw IllegalStateException(getString(R.string.already_exists))
                            val tmp = File.createTempFile(".fluffy_", ".tmp", location.file)
                            try {
                                tmp.writeText(content)
                                if (!tmp.renameTo(newFile)) {
                                    if (!newFile.createNewFile()) throw java.io.IOException(getString(R.string.create_failed))
                                    newFile.writeText(content)
                                }
                            } finally {
                                runCatching { if (tmp.exists()) tmp.delete() }
                            }
                        }
                        refresh()
                    } catch (e: Exception) {
                        snackbarManager.show(cannotCreate(R.string.cannot_create_file, e))
                    }
                }
                is BrowseLocation.SAF -> {
                    st.currentDir?.let { parent ->
                        try {
                            withContext(Dispatchers.IO) {
                                val uri = io.createFile(parent, name)
                                io.writeText(uri, content)
                            }
                            refresh()
                        } catch (e: Exception) {
                            snackbarManager.show(cannotCreate(R.string.cannot_create_file, e))
                        }
                    }
                }
                else -> {}
            }
        }
    }

    fun getPath(): String {
        val st = _state.value
        return when (val location = st.currentLocation) {
            is BrowseLocation.FileSystem -> location.file.absolutePath
            is BrowseLocation.SAF -> st.currentDir?.path ?: ""
            is BrowseLocation.QuickAccess -> getString(R.string.quick_access)
            null -> ""
        }
    }

    private fun updatePermissionFlag() {
        val has = fileSystemAccess.hasStoragePermission()
        _state.value = _state.value.copy(canAccessFileSystem = has)
    }

    fun onPermissionsChanged() {
        updatePermissionFlag()
        if (_state.value.currentLocation is BrowseLocation.QuickAccess) {
            showQuickAccess()
        } else {
            refresh()
        }
    }

    fun setPendingArchiveOpen(uri: Uri) {
        _state.value = _state.value.copy(pendingAction = PendingAction.OpenArchive(uri))
    }

    fun clearSelection() {
        _state.value.selectedItems.clear()
        _state.value = _state.value.copy()
    }

    fun setPickerMode(enabled: Boolean, mimeType: String?) {
        _state.update { it.copy(
            isPickerMode = enabled,
            pickerMimeType = mimeType
        )}
    }
}
