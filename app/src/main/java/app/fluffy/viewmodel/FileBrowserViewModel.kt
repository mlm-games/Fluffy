package app.fluffy.viewmodel

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluffy.archive.ArchiveEngine
import app.fluffy.data.repository.SettingsRepository
import app.fluffy.io.SafIo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FileBrowserState(
    val currentDir: Uri? = null,
    val stack: List<Uri> = emptyList(),
    val items: List<DocumentFile> = emptyList()
)

class FileBrowserViewModel(
    private val io: SafIo,
    @Suppress("unused") private val archive: ArchiveEngine,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(FileBrowserState())
    val state: StateFlow<FileBrowserState> = _state

    private var showHidden: Boolean = false

    init {
        // Observe settings and refresh listing when "showHidden" changes
        viewModelScope.launch {
            settings.settingsFlow.collect { s ->
                val newVal = s.showHidden
                if (newVal != showHidden) {
                    showHidden = newVal
                    refresh()
                }
            }
        }
    }

    fun openRoot(uri: Uri) {
        _state.value = FileBrowserState(
            currentDir = uri,
            stack = listOf(uri),
            items = filtered(io.listChildren(uri))
        )
    }

    fun openDir(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                currentDir = uri,
                stack = _state.value.stack + uri,
                items = filtered(io.listChildren(uri))
            )
        }
    }

    fun goUp() {
        val st = _state.value
        if (st.stack.size <= 1) return
        val newStack = st.stack.dropLast(1)
        val newTop = newStack.last()
        _state.value = st.copy(currentDir = newTop, stack = newStack, items = filtered(io.listChildren(newTop)))
    }

    private fun refresh() {
        val st = _state.value
        val dir = st.currentDir ?: return
        _state.value = st.copy(items = filtered(io.listChildren(dir)))
    }

    private fun filtered(list: List<DocumentFile>): List<DocumentFile> {
        return if (showHidden) list
        else list.filter { f -> !(f.name ?: "").startsWith(".") }
    }
}
