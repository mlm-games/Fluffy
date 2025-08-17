package app.fluffy.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore("fluffy.settings")

sealed class SettingDefinition<T> {
    abstract val key: Preferences.Key<T>
    abstract val getValue: (AppSettings) -> T
    abstract val propertyName: String

    data class BooleanSetting(
        override val propertyName: String,
        override val key: Preferences.Key<Boolean>,
        override val getValue: (AppSettings) -> Boolean
    ) : SettingDefinition<Boolean>()

    data class IntSetting(
        override val propertyName: String,
        override val key: Preferences.Key<Int>,
        override val getValue: (AppSettings) -> Int
    ) : SettingDefinition<Int>()
}

class SettingsRepository(private val context: Context) {

    companion object {
        // General
        val DEFAULT_SORT = intPreferencesKey("default_sort")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")

        // Appearance
        val THEME_MODE = intPreferencesKey("theme_mode")

        // Archives
        val ZIP_LEVEL = intPreferencesKey("zip_level")
    }

    private val definitions: Map<String, SettingDefinition<*>> = mapOf(
        "defaultSort" to SettingDefinition.IntSetting("defaultSort", DEFAULT_SORT) { it.defaultSort },
        "showHidden" to SettingDefinition.BooleanSetting("showHidden", SHOW_HIDDEN) { it.showHidden },
        "themeMode" to SettingDefinition.IntSetting("themeMode", THEME_MODE) { it.themeMode },
        "zipCompressionLevel" to SettingDefinition.IntSetting("zipCompressionLevel", ZIP_LEVEL) { it.zipCompressionLevel }
    )

    val settingsFlow: Flow<AppSettings> = context.ds.data.map { p ->
        AppSettings(
            defaultSort = p[DEFAULT_SORT] ?: 0,
            showHidden = p[SHOW_HIDDEN] ?: false,
            themeMode = p[THEME_MODE] ?: 0,
            zipCompressionLevel = p[ZIP_LEVEL] ?: 5
        )
    }.distinctUntilChanged()

    suspend fun updateSetting(propertyName: String, value: Any) {
        val def = definitions[propertyName] ?: return
        context.ds.edit { prefs ->
            when (def) {
                is SettingDefinition.BooleanSetting -> prefs[def.key] = (value as? Boolean) ?: return@edit
                is SettingDefinition.IntSetting -> prefs[def.key] = (value as? Int) ?: return@edit
            }
        }
    }

    suspend fun updateSettings(update: (AppSettings) -> AppSettings) {
        val current = settingsFlow.first()
        val updated = update(current)
        context.ds.edit { prefs ->
            definitions.values.forEach { def ->
                when (def) {
                    is SettingDefinition.BooleanSetting -> {
                        val old = def.getValue(current)
                        val new = def.getValue(updated)
                        if (old != new) prefs[def.key] = new
                    }
                    is SettingDefinition.IntSetting -> {
                        val old = def.getValue(current)
                        val new = def.getValue(updated)
                        if (old != new) prefs[def.key] = new
                    }
                }
            }
        }
    }

    // Generic cache clear for the file manager (no repo metadata anymore)
    suspend fun clearCache() {
        runCatching { context.cacheDir.deleteRecursively() }
    }
}
