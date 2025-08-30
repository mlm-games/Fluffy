package app.fluffy.viewmodel

import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluffy.archive.ArchiveEngine
import app.fluffy.data.repository.SettingsRepository
import app.fluffy.io.FileSystemAccess
import app.fluffy.io.SafIo
import app.fluffy.io.ShellEntry
import app.fluffy.shell.RootAccess
import app.fluffy.shell.ShizukuAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
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
    val uri: Uri?
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
    val pendingFileOpen: Uri? = null
)

class FileBrowserViewModel(
    private val io: SafIo,
    private val fileSystemAccess: FileSystemAccess,
    @Suppress("unused") private val archive: ArchiveEngine,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(FileBrowserState())
    val state: StateFlow<FileBrowserState> = _state

    private var showHidden: Boolean = false
    private var showRoot: Boolean = false
    private var showShizuku: Boolean = false

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

    private fun getQuickAccessItems(): List<QuickAccessItem> {
        val items = mutableListOf<QuickAccessItem>()

        val storageRoots = fileSystemAccess.getAllStorageRoots()
        val seenFilePaths = mutableSetOf<String>()
        storageRoots.forEachIndexed { idx, root ->
            val label = if (idx == 0) "Internal Storage" else "External Storage"
            items.add(
                QuickAccessItem(
                    name = label,
                    icon = if (idx == 0) "storage" else "sd",
                    file = root,
                    uri = null
                )
            )
            seenFilePaths += root.absolutePath
        }

        // Common public folders (still useful shortcuts)
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
            items.add(QuickAccessItem(if (available) "Root /" else "Root / (not available)", "root", null, Uri.Builder().scheme("root").path("/").build()))

            items.add(QuickAccessItem("Internal Storage (root)", "root", null, Uri.Builder().scheme("root").path("/storage/emulated/0").build()))

            // any detected storage roots (including removable SDs)
            val seenRootPaths = mutableSetOf<String>()
            storageRoots.forEachIndexed { idx, root ->
                val path = root.absolutePath
                if (seenRootPaths.add(path)) {
                    val name = if (idx == 0) "Internal Root" else "External Root"
                    items.add(
                        QuickAccessItem(
                            name = "$name (${path})",
                            icon = if (idx == 0) "root" else "sd",
                            file = null,
                            uri = Uri.Builder().scheme("root").path(path).build()
                        )
                    )
                }
            }

            items.add(QuickAccessItem("Termux Home (root)", "terminal", null, Uri.Builder().scheme("root").path("/data/data/com.termux/files/home").build()))
            items.add(QuickAccessItem("Termux Storage (root)", "terminal", null, Uri.Builder().scheme("root").path("/data/data/com.termux/files/home/storage").build()))
        }

        if (showShizuku) {
            val available = ShizukuAccess.isAvailable()
            items.add(QuickAccessItem(if (available) "Shizuku /" else "Shizuku / (not running)", "shizuku", null, Uri.Builder().scheme("shizuku").path("/").build()))
            // Internal storage via shizuku
            items.add(QuickAccessItem("Internal Storage (shizuku)", "shizuku", null, Uri.Builder().scheme("shizuku").path("/storage/emulated/0").build()))

            val seenShizukuPaths = mutableSetOf<String>()
            storageRoots.forEachIndexed { idx, root ->
                val path = root.absolutePath
                if (seenShizukuPaths.add(path)) {
                    val name = if (idx == 0) "Internal (shizuku)" else "External (shizuku)"
                    items.add(
                        QuickAccessItem(
                            name = "$name (${path})",
                            icon = if (idx == 0) "shizuku" else "sd",
                            file = null,
                            uri = Uri.Builder().scheme("shizuku").path(path).build()
                        )
                    )
                }
            }

            items.add(QuickAccessItem("Termux Home (shizuku)", "terminal", null, Uri.Builder().scheme("shizuku").path("/data/data/com.termux/files/home").build()))
            items.add(QuickAccessItem("Termux Storage (shizuku)", "terminal", null, Uri.Builder().scheme("shizuku").path("/data/data/com.termux/files/home/storage").build()))
        }

        return items
    }

    fun showQuickAccess() {
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
                _state.value = st.copy(
                    currentLocation = location,
                    currentDir = uri,
                    stack = anchored + location,
                    shellItems = io.listShell(uri).filter { showHidden || !it.name.startsWith(".") },
                    items = emptyList(),
                    fileItems = emptyList(),
                    quickAccessItems = emptyList(),
                    error = null
                )
            } else {
                _state.value = st.copy(
                    currentLocation = location,
                    currentDir = uri,
                    stack = anchored + location,
                    items = filtered(io.listChildren(uri)),
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
        val parentPath = if (idx <= 0) "/" else path.substring(0, idx)
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

    fun getPath(): String {
        val st = _state.value
        return when (val location = st.currentLocation) {
            is BrowseLocation.FileSystem -> location.file.absolutePath
            is BrowseLocation.SAF -> st.currentDir?.path ?: ""
            is BrowseLocation.QuickAccess -> "Quick Access"
            null -> ""
        }
    }
}
