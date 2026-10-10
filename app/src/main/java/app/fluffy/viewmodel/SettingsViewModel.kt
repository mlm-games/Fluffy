package app.fluffy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluffy.cache.CacheManager
import app.fluffy.data.repository.AppSettings
import app.fluffy.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val cacheManager: CacheManager
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        repo.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events

    fun updateSetting(propertyName: String, value: Any) = viewModelScope.launch {
        repo.updateSetting(propertyName, value)
    }

    fun performAction(propertyName: String) = viewModelScope.launch {
        when (propertyName) {
            "supportDevelopment" -> _events.tryEmit(UiEvent.OpenUrl("https://github.com/sponsors/mlm-games"))
            "clearCache" -> {
                val freed = withContext(Dispatchers.IO) { cacheManager.clearAll() }
                _events.tryEmit(UiEvent.CacheCleared(freed))
            }
            else -> _events.tryEmit(UiEvent.Toast("No action attached"))
        }
    }

    sealed class UiEvent {
        data class Toast(val message: String) : UiEvent()
        data class OpenUrl(val url: String) : UiEvent()
        data class CacheCleared(val freedBytes: Long) : UiEvent()
    }
}
