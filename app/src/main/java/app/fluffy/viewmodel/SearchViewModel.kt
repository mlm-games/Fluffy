package app.fluffy.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluffy.data.repository.SettingsRepository
import app.fluffy.search.FileSearchEngine
import app.fluffy.search.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SEARCH_DEBOUNCE_MS = 300L

data class SearchState(
    val query: String = "",
    val scope: Uri? = null,
    val hits: List<SearchHit> = emptyList(),
    val isSearching: Boolean = false,
    val truncated: Boolean = false,
    val visitedDirs: Int = 0
) {
    val hasQuery: Boolean get() = query.trim().isNotEmpty()
}

class SearchViewModel(
    private val engine: FileSearchEngine,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var job: Job? = null
    private var debounceJob: Job? = null

    fun setScope(uri: Uri) {
        if (_state.value.scope == uri) return
        _state.update { it.copy(scope = uri) }
        if (_state.value.hasQuery) {
            debounceJob?.cancel()
            run()
        }
    }

    fun setQuery(query: String) {
        if (query.trim().isEmpty()) {
            cancel()
            _state.update { it.copy(query = query, hits = emptyList(), truncated = false, visitedDirs = 0) }
            return
        }
        job?.cancel()
        job = null
        _state.update {
            it.copy(query = query, isSearching = true)
        }
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            run()
        }
    }

    fun cancel() {
        debounceJob?.cancel()
        debounceJob = null
        job?.cancel()
        job = null
        _state.update { it.copy(isSearching = false) }
    }

    fun clear() {
        cancel()
        _state.value = SearchState(scope = _state.value.scope)
    }

    private fun run() {
        val scope = _state.value.scope
        val query = _state.value.query
        if (scope == null || query.trim().isEmpty()) {
            _state.update { it.copy(isSearching = false) }
            return
        }
        job?.cancel()
        _state.update { it.copy(isSearching = true) }
        job = viewModelScope.launch(Dispatchers.IO) {
            val showHidden = runCatching { settingsRepository.settingsFlow.first().showHidden }
                .getOrDefault(false)
            val outcome = runCatching {
                engine.search(
                    scope,
                    query,
                    showHidden = showHidden,
                    shouldStop = { !isActive }
                ).firstOrNull()
            }.getOrNull()
            if (!isActive) return@launch
            _state.update { s ->
                s.copy(
                    hits = outcome?.hits ?: emptyList(),
                    truncated = outcome?.truncated ?: false,
                    visitedDirs = outcome?.visitedDirs ?: 0,
                    isSearching = false
                )
            }
        }
    }
}
