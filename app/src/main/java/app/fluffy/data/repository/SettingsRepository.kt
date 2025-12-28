package app.fluffy.data.repository

import android.content.Context
import io.github.mlmgames.settings.core.SettingsRepository as KmpSettingsRepository
import io.github.mlmgames.settings.core.datastore.createSettingsDataStore
import kotlinx.coroutines.flow.Flow

class SettingsRepository(context: Context) {

    private val dataStore = createSettingsDataStore(context, name = "fluffy.settings")
    private val repo = KmpSettingsRepository(dataStore, AppSettingsSchema)

    val settingsFlow: Flow<AppSettings> = repo.flow

    suspend fun updateSetting(propertyName: String, value: Any) {
        repo.set(propertyName, value)
    }

    suspend fun updateSettings(update: (AppSettings) -> AppSettings) {
        repo.update(update)
    }
}