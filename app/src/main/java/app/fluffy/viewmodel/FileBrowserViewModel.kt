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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

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
    val items: List<DocumentFile> = emptyList(),
    val fileItems: List<File> = emptyList(),
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

    init {
        viewModelScope.launch {
            settings.settingsFlow.collect { s ->
                val newVal = s.showHidden
                if (newVal != showHidden) {
                    showHidden = newVal
                    refresh()
                }
            }
        }

        initializeFileAccess()
    }

    private fun initializeFileAccess() {
        viewModelScope.launch {
            val hasAccess = fileSystemAccess.hasStoragePermission()
            _state.value = _state.value.copy(canAccessFileSystem = hasAccess)

            if (hasAccess) {
                showQuickAccess()
            } else {
                // Show empty state prompting for permission
                _state.value = _state.value.copy(
                    quickAccessItems = getQuickAccessItems(),
                    currentLocation = BrowseLocation.QuickAccess
                )
            }
        }
    }

    private fun getQuickAccessItems(): List<QuickAccessItem> {
        val items = mutableListOf<QuickAccessItem>()

        items.add(
            QuickAccessItem(
                name = "Internal Storage",
                icon = "storage",
                file = Environment.getExternalStorageDirectory(),
                uri = null
            )
        )

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
                items.add(
                    QuickAccessItem(
                        name = name,
                        icon = name.lowercase(),
                        file = file,
                        uri = null
                    )
                )
            }
        }

        // TODO: Load from preferences -> Recent SAF locations

        return items
    }

    fun showQuickAccess() {
        _state.value = _state.value.copy(
            currentLocation = BrowseLocation.QuickAccess,
            currentDir = null,
            currentFile = null,
            items = emptyList(),
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
            val newStack = if (_state.value.currentLocation is BrowseLocation.QuickAccess) {
                listOf(BrowseLocation.QuickAccess, location)
            } else {
                _state.value.stack + location
            }

            _state.value = _state.value.copy(
                currentLocation = location,
                currentFile = file,
                currentDir = null,
                stack = newStack,
                fileItems = loadFileSystemItems(file),
                items = emptyList(),
                quickAccessItems = emptyList(),
                isLoading = false
            )
        }
    }

    private fun loadFileSystemItems(directory: File): List<File> {
        if (!fileSystemAccess.hasStoragePermission()) return emptyList()

        val files = directory.listFiles()?.toList() ?: emptyList()

        return files
            .filter { if (showHidden) true else !it.name.startsWith(".") }
            .sortedWith(
                compareBy<File> { !it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
    }

    fun openRoot(uri: Uri) {
        val location = BrowseLocation.SAF(uri)
        _state.value = FileBrowserState(
            currentLocation = location,
            currentDir = uri,
            stack = listOf(location),
            items = filtered(io.listChildren(uri))
        )
    }

    fun openDir(uri: Uri) {
        viewModelScope.launch {
            val location = BrowseLocation.SAF(uri)
            _state.value = _state.value.copy(
                currentLocation = location,
                currentDir = uri,
                stack = _state.value.stack + location,
                items = filtered(io.listChildren(uri)),
                fileItems = emptyList(),
                quickAccessItems = emptyList()
            )
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
                    val newStack = st.stack.dropLast(1)
                    val previous = newStack.last()
                    navigateToLocation(previous)
                }
            }
            is BrowseLocation.SAF -> {
                if (st.stack.size <= 1) return
                val newStack = st.stack.dropLast(1)
                val previous = newStack.last()
                navigateToLocation(previous)
            }
            is BrowseLocation.QuickAccess -> {
                // Can't go up from quick access
            }
            null -> {}
        }
    }

    private fun navigateToLocation(location: BrowseLocation) {
        when (location) {
            is BrowseLocation.FileSystem -> openFileSystemPath(location.file)
            is BrowseLocation.SAF -> openRoot(location.uri)
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
                location.uri.let { uri ->
                    _state.value = st.copy(items = filtered(io.listChildren(uri)))
                }
            }
            is BrowseLocation.QuickAccess -> {
                _state.value = st.copy(quickAccessItems = getQuickAccessItems())
            }
            null -> {}
        }
    }

    fun refreshCurrentDir() {
        refresh()
    }

    private fun filtered(list: List<DocumentFile>): List<DocumentFile> {
        return if (showHidden) list
        else list.filter { f -> !(f.name ?: "").startsWith(".") }
    }

    fun openFile(file: File) {
        if (file.isDirectory) {
            openFileSystemPath(file)
        } else {
            // Handle file opening
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
        // Trigger navigation to archive viewer or other file viewer
        _state.value = _state.value.copy(
            pendingFileOpen = uri
        )
    }

    fun clearPendingFileOpen() {
        _state.value = _state.value.copy(
            pendingFileOpen = null
        )
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