package app.fluffy.viewmodel

import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val pendingFileOpen: Uri? = null,
    val pendingArchiveOpen: Uri? = null,
    val selectedItems: MutableList<Uri> = mutableListOf(),
    val isPickerMode: Boolean = false,
    val pickerMimeType: String? = null
)

class FileBrowserViewModel(
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

    val customBookmarks: StateFlow<List<Bookmark>> = bookmarksRepository.bookmarks
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            settings.settingsFlow.collect { s ->
                val changedHidden = s.showHidden != showHidden
                val changedRoot = s.enableRoot != showRoot
                val changedShizuku = s.enableShizuku != showShizuku
                showHidden = s.showHidden
                showRoot = s.enableRoot
                showShizuku = s.enableShizuku
                if (changedHidden) refresh()
                if (changedRoot || changedShizuku) {
                    if (_state.value.currentLocation is BrowseLocation.QuickAccess) {
                        showQuickAccess()
                    }
                }
            }
        }
        initializeFileAccess()
    }

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
                        snackbarManager.show("Cannot access ${bookmark.name}: Shizuku is not running")
                        return@launch
                    }
                    if (scheme == "root" && !RootAccess.isAvailable()) {
                        snackbarManager.show("Cannot access ${bookmark.name}: Root access not available")
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
                val missing = if (!file.exists()) "Path does not exist" else "Path is not readable"
                snackbarManager.show("Cannot access ${bookmark.name}: $missing")
                return@launch
            }

            val reason = when {
                !showRoot && !showShizuku -> "Enable Root or Shizuku in Settings"
                showShizuku && !ShizukuAccess.isAvailable() -> "Shizuku is not running"
                showRoot && !RootAccess.isAvailable() -> "Root access not available"
                else -> "Path does not exist or is not accessible"
            }

            snackbarManager.show(
                message = "Cannot access ${bookmark.name}: $reason",
                actionLabel = if (!showRoot && !showShizuku) "Settings" else null,
            )
        }
    }

    private fun getQuickAccessItems(): List<QuickAccessItem> {
        val items = mutableListOf<QuickAccessItem>()

        val storageRoots = fileSystemAccess.getAllStorageRoots()

        val seenFilePaths = mutableSetOf<String>()
        storageRoots.forEachIndexed { idx, root ->
            if (seenFilePaths.add(root.absolutePath)) {
                val label = if (idx == 0) "Internal Storage" else "External Storage"
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

        // Common public folders (unchanged)
        val folders = listOf(
            "Downloads" to Environment.DIRECTORY_DOWNLOADS,
            "Documents" to Environment.DIRECTORY_DOCUMENTS,
            "Pictures" to Environment.DIRECTORY_PICTURES,
            "Music" to Environment.DIRECTORY_MUSIC,
            "Movies" to Environment.DIRECTORY_MOVIES,
            "DCIM" to Environment.DIRECTORY_DCIM
        )
        folders.forEach { (name, dir) ->
            val file = Environment.getExternalStoragePublicDirectory(dir)
            if (file.exists()) {
                items.add(QuickAccessItem(name, name.lowercase(), file, null))
            }
        }

        if (showRoot) {
            val available = RootAccess.isAvailable()
            items.add(
                QuickAccessItem(
                    if (available) "Root /" else "Root / (not available)",
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
                    "Internal Storage (root)",
                    "root",
                    null,
                    Uri.Builder().scheme("root").path(internalPath).build(),
                    enabled = available
                )
            )

            storageRoots.forEachIndexed { idx, root ->
                val path = root.absolutePath
                if (rootSeenPaths.add(path)) {
                    val name = if (idx == 0) "Internal Root" else "External Root"
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
                    "Termux Home (root)",
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
                    "Termux Storage (root)",
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
                    if (available) "Shizuku /" else "Shizuku / (not running)",
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
                    "Internal Storage (shizuku)",
                    "shizuku",
                    null,
                    Uri.Builder().scheme("shizuku").path(internalPath).build(),
                    enabled = available
                )
            )

            storageRoots.forEachIndexed { idx, root ->
                val path = root.absolutePath
                if (shizukuSeenPaths.add(path)) {
                    val name = if (idx == 0) "Internal (shizuku)" else "External (shizuku)"
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
                    "Termux Home (shizuku)",
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
                    "Termux Storage (shizuku)",
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
                _state.value = _state.value.copy(error = "Path does not exist")
                return@launch
            }
            val location = BrowseLocation.FileSystem(file)
            val base = listOf(BrowseLocation.QuickAccess)
            val newStack = base + location
            _state.value = _state.value.copy(
                currentLocation = location,
                currentFile = file,
                currentDir = null,
                stack = newStack,
                fileItems = loadFileSystemItems(file),
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
        return files
            .filter { if (showHidden) true else !it.name.startsWith(".") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun openRoot(uri: Uri) { // for picked SAF trees; not used for root/shizuku
        val location = BrowseLocation.SAF(uri)
        _state.value = FileBrowserState(
            currentLocation = location,
            currentDir = uri,
            stack = listOf(BrowseLocation.QuickAccess, location),
            items = io.listChildren(uri)
        )
    }

    fun openDir(uri: Uri) {
        viewModelScope.launch {
            val location = BrowseLocation.SAF(uri)
            val st = _state.value
            val anchored = if (st.stack.isNotEmpty() && st.stack.first() is BrowseLocation.QuickAccess)
                st.stack else listOf(BrowseLocation.QuickAccess)

            if (uri.scheme == "root" || uri.scheme == "shizuku") {
                val shellList = withContext(Dispatchers.IO) { io.listShell(uri) }
                _state.value = st.copy(
                    currentLocation = BrowseLocation.SAF(uri),
                    currentDir = uri,
                    stack = anchored + BrowseLocation.SAF(uri),
                    shellItems = shellList.filter { showHidden || !it.name.startsWith(".") },
                    items = emptyList(),
                    fileItems = emptyList(),
                    quickAccessItems = emptyList(),
                    error = null
                )
            } else {
                val safList = withContext(Dispatchers.IO) { io.listChildren(uri) }
                _state.value = st.copy(
                    currentLocation = BrowseLocation.SAF(uri),
                    currentDir = uri,
                    stack = anchored + BrowseLocation.SAF(uri),
                    items = filtered(safList),
                    shellItems = emptyList(),
                    fileItems = emptyList(),
                    quickAccessItems = emptyList(),
                    error = null
                )
            }
        }
    }

    fun goUp() {
        val st = _state.value
        when (val current = st.currentLocation) {
            is BrowseLocation.FileSystem -> {
                val parent = current.file.parentFile
                if (parent != null && parent.exists()) {
                    openFileSystemPath(parent)
                } else if (st.stack.size > 1) {
                    val previous = st.stack.dropLast(1).last()
                    navigateToLocation(previous)
                } else {
                    showQuickAccess()
                }
            }
            is BrowseLocation.SAF -> {
                val cur = st.currentDir
                if (cur != null && (cur.scheme == "root" || cur.scheme == "shizuku")) {
                    val parent = upOfShell(cur)
                    if (parent != null) {
                        openDir(parent)
                    } else {
                        // We are at "/" for this scheme — go back to Quick Access
                        showQuickAccess()
                    }
                } else {
                    // Non-shell (content:// picked tree or similar) — fallback to previous in stack
                    if (st.stack.size > 1) {
                        val previous = st.stack.dropLast(1).last()
                        navigateToLocation(previous)
                    } else {
                        showQuickAccess()
                    }
                }
            }
            is BrowseLocation.QuickAccess, null -> { /* nowhere to go */ }
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
        when (location) {
            is BrowseLocation.FileSystem -> openFileSystemPath(location.file)
            is BrowseLocation.SAF -> openDir(location.uri)
            is BrowseLocation.QuickAccess -> showQuickAccess()
        }
    }


    fun refresh() {
        updatePermissionFlag()
        val st = _state.value
        when (val location = st.currentLocation) {
            is BrowseLocation.FileSystem -> {
                _state.value = st.copy(fileItems = loadFileSystemItems(location.file))
            }
            is BrowseLocation.SAF -> {
                val uri = location.uri
                if (uri.scheme == "root" || uri.scheme == "shizuku") {
                    _state.value = st.copy(shellItems = io.listShell(uri).filter { showHidden || !it.name.startsWith(".") })
                } else {
                    _state.value = st.copy(items = filtered(io.listChildren(uri)))
                }
            }
            is BrowseLocation.QuickAccess -> {
                _state.value = st.copy(quickAccessItems = getQuickAccessItems())
            }
            null -> {}
        }
    }

    fun refreshCurrentDir() = refresh()

    private fun filtered(list: List<DocumentFile>): List<DocumentFile> {
        return if (showHidden) list else list.filter { f -> !(f.name ?: "").startsWith(".") }
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

    private suspend fun handleFileOpen(uri: Uri) {
        _state.value = _state.value.copy(pendingFileOpen = uri)
    }

    fun clearPendingFileOpen() {
        _state.value = _state.value.copy(pendingFileOpen = null)
    }

    fun createNewFolder(name: String) {
        viewModelScope.launch {
            val st = _state.value
            when (val location = st.currentLocation) {
                is BrowseLocation.FileSystem -> {
                    val newFolder = File(location.file, name)
                    if (!newFolder.exists()) {
                        newFolder.mkdirs()
                        refresh()
                    }
                }
                is BrowseLocation.SAF -> {
                    st.currentDir?.let { parent ->
                        io.createDir(parent, name)
                        refresh()
                    }
                }
                else -> {}
            }
        }
    }

    fun createNewFile(name: String) {
        viewModelScope.launch {
            val st = _state.value
            when (val location = st.currentLocation) {
                is BrowseLocation.FileSystem -> {
                    val newFile = File(location.file, name)
                    if (!newFile.exists()) {
                        newFile.createNewFile()
                        refresh()
                    }
                }
                is BrowseLocation.SAF -> {
                    st.currentDir?.let { parent ->
                        io.createFile(parent, name)
                        refresh()
                    }
                }
                else -> {}
            }
        }
    }

    fun createFileFromClipboard(name: String, content: String) {
        viewModelScope.launch {
            val st = _state.value
            when (val location = st.currentLocation) {
                is BrowseLocation.FileSystem -> {
                    val newFile = File(location.file, name)
                    if (!newFile.exists()) {
                        newFile.createNewFile()
                        newFile.writeText(content)
                        refresh()
                    }
                }
                is BrowseLocation.SAF -> {
                    st.currentDir?.let { parent ->
                        val uri = io.createFile(parent, name)
                        io.writeText(uri, content)
                        refresh()
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
            is BrowseLocation.QuickAccess -> "Quick Access"
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
        _state.value = _state.value.copy(pendingArchiveOpen = uri)
    }
    fun clearPendingArchiveOpen() {
        _state.value = _state.value.copy(pendingArchiveOpen = null)
    }

    fun clearSelection() {
        _state.update { it.copy(selectedItems = mutableListOf()) }
    }

    fun setPickerMode(enabled: Boolean, mimeType: String?) {
        _state.update { it.copy(
            isPickerMode = enabled,
            pickerMimeType = mimeType
        )}
    }
}
