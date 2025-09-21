package app.fluffy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluffy.data.repository.AppSettings
import app.fluffy.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        repo.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events

    fun updateSetting(propertyName: String, value: Any) = viewModelScope.launch {
        repo.updateSetting(propertyName, value)
    }

    fun performAction(propertyName: String) = viewModelScope.launch {
        when (propertyName) {
            else -> _events.emit(UiEvent.Toast("No action attached"))
        }
    }

    sealed class UiEvent {
        data class Toast(val message: String) : UiEvent()
    }
}
