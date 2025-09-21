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
        val SHOW_FILE_COUNT = booleanPreferencesKey("show_file_count")

        // Appearance
        val THEME_MODE = intPreferencesKey("theme_mode")
//        val USE_AURORA_THEME = booleanPreferencesKey("use_aurora_theme")

        // Archives
        val ZIP_LEVEL = intPreferencesKey("zip_level")
        val EXTRACT_INTO_SUBFOLDER = booleanPreferencesKey("extract_into_subfolder")

        val ENABLE_ROOT = booleanPreferencesKey("enable_root")
        val ENABLE_SHIZUKU = booleanPreferencesKey("enable_shizuku")

        val PREFER_CR_MIME = booleanPreferencesKey("prefer_cr_mime")
        val WARN_SHELL_WRITES = booleanPreferencesKey("warn_shell_writes")
    }

    private val definitions: Map<String, SettingDefinition<*>> = mapOf(
        "defaultSort" to SettingDefinition.IntSetting("defaultSort", DEFAULT_SORT) { it.defaultSort },
        "showHidden" to SettingDefinition.BooleanSetting("showHidden", SHOW_HIDDEN) { it.showHidden },
        "themeMode" to SettingDefinition.IntSetting("themeMode", THEME_MODE) { it.themeMode },
        "zipCompressionLevel" to SettingDefinition.IntSetting("zipCompressionLevel", ZIP_LEVEL) { it.zipCompressionLevel },
//        "useAuroraTheme" to SettingDefinition.BooleanSetting("useAuroraTheme", USE_AURORA_THEME) { it.useAuroraTheme },
        "extractIntoSubfolder" to SettingDefinition.BooleanSetting("extractIntoSubfolder", EXTRACT_INTO_SUBFOLDER) { it.extractIntoSubfolder },
        "enableRoot" to SettingDefinition.BooleanSetting(AppSettings::enableRoot.name, ENABLE_ROOT) { it.enableRoot },
        "enableShizuku" to SettingDefinition.BooleanSetting(AppSettings::enableShizuku.name, ENABLE_SHIZUKU) { it.enableShizuku },
        "preferContentResolverMime" to SettingDefinition.BooleanSetting("preferContentResolverMime", PREFER_CR_MIME) { it.preferContentResolverMime },
        "warnBeforeShellWrites" to SettingDefinition.BooleanSetting("warnBeforeShellWrites", WARN_SHELL_WRITES) { it.warnBeforeShellWrites },
        "showFileCount" to SettingDefinition.BooleanSetting("showFileCount", SHOW_FILE_COUNT) { it.showFileCount },
    )

    val settingsFlow: Flow<AppSettings> = context.ds.data.map { p ->
        AppSettings(
            defaultSort = p[DEFAULT_SORT] ?: 0,
            showHidden = p[SHOW_HIDDEN] ?: false,
            showFileCount = p[SHOW_FILE_COUNT] ?: true,
//            useAuroraTheme = p[USE_AURORA_THEME] ?: true,
            themeMode = p[THEME_MODE] ?: 2,
            zipCompressionLevel = p[ZIP_LEVEL] ?: 5,
            extractIntoSubfolder = p[EXTRACT_INTO_SUBFOLDER] ?: true,
            enableRoot = p[ENABLE_ROOT] ?: false,
            enableShizuku = p[ENABLE_SHIZUKU] ?: false,
            preferContentResolverMime = p[PREFER_CR_MIME] ?: true,
            warnBeforeShellWrites = p[WARN_SHELL_WRITES] ?: false,
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
}