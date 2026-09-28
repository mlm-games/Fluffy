package app.fluffy.data.repository

import android.content.Context
import app.fluffy.R
import io.github.mlmgames.settings.core.resources.AndroidStringResourceProvider
import io.github.mlmgames.settings.core.resources.StringResourceProvider

/**
 * Maps [FluffySettingsKeys] to Android resources.
 *
 * `AndroidStringResourceProvider` consults its key resolver before falling back to
 * the built-in key set, so one table serves both the string and string-array paths
 * and the generated schema never has to carry a resource id.
 */
private val settingsResources: Map<String, Int> = mapOf(
    FluffySettingsKeys.CATEGORY_APPEARANCE_SETTINGS to R.string.category_appearance_settings,
    FluffySettingsKeys.CATEGORY_ARCHIVES to R.string.category_archives,
    FluffySettingsKeys.CATEGORY_GENERAL_SETTINGS to R.string.category_general_settings,
    FluffySettingsKeys.CATEGORY_SYSTEM_SETTINGS to R.string.category_system_settings,
    FluffySettingsKeys.SETTING_ALWAYS_INAPP_PICKER to R.string.setting_always_inapp_picker,
    FluffySettingsKeys.SETTING_DEFAULT_SORT to R.string.setting_default_sort,
    FluffySettingsKeys.SETTING_DYNAMIC_COLOR to R.string.setting_dynamic_color,
    FluffySettingsKeys.SETTING_ENABLE_ROOT to R.string.setting_enable_root,
    FluffySettingsKeys.SETTING_ENABLE_SHIZUKU to R.string.setting_enable_shizuku,
    FluffySettingsKeys.SETTING_EXTRACT_INTO_SUBFOLDER to R.string.setting_extract_into_subfolder,
    FluffySettingsKeys.SETTING_OLED_BLACK to R.string.setting_oled_black,
    FluffySettingsKeys.SETTING_PREFER_BUILTIN_VIEWERS to R.string.setting_prefer_builtin_viewers,
    FluffySettingsKeys.SETTING_PREFER_CR_MIME to R.string.setting_prefer_cr_mime,
    FluffySettingsKeys.SETTING_SHOW_FILE_COUNT to R.string.setting_show_file_count,
    FluffySettingsKeys.SETTING_SHOW_HIDDEN to R.string.setting_show_hidden,
    FluffySettingsKeys.SETTING_SHOW_STORAGE_INFO to R.string.setting_show_storage_info,
    FluffySettingsKeys.SETTING_SHOW_THUMBNAILS to R.string.setting_show_thumbnails,
    FluffySettingsKeys.SETTING_SORT_REVERSE to R.string.setting_sort_reverse,
    FluffySettingsKeys.SETTING_SUPPORT_DEVELOPMENT to R.string.setting_support_development,
    FluffySettingsKeys.SETTING_THEME to R.string.setting_theme,
    FluffySettingsKeys.SETTING_VIEW_MODE to R.string.setting_view_mode,
    FluffySettingsKeys.SETTING_WARN_SHELL_WRITES to R.string.setting_warn_shell_writes,
    FluffySettingsKeys.SETTING_ZIP_LEVEL to R.string.setting_zip_level,
    FluffySettingsKeys.SETTING_ALWAYS_INAPP_PICKER_DESCRIPTION to
        R.string.setting_always_inapp_picker_desc,
    FluffySettingsKeys.SETTING_DEFAULT_SORT_DESCRIPTION to R.string.setting_default_sort_desc,
    FluffySettingsKeys.SETTING_DYNAMIC_COLOR_DESCRIPTION to R.string.setting_dynamic_color_desc,
    FluffySettingsKeys.SETTING_ENABLE_ROOT_DESCRIPTION to R.string.setting_enable_root_desc,
    FluffySettingsKeys.SETTING_ENABLE_SHIZUKU_DESCRIPTION to R.string.setting_enable_shizuku_desc,
    FluffySettingsKeys.SETTING_EXTRACT_INTO_SUBFOLDER_DESCRIPTION to
        R.string.setting_extract_into_subfolder_desc,
    FluffySettingsKeys.SETTING_PREFER_BUILTIN_VIEWERS_DESCRIPTION to
        R.string.setting_prefer_builtin_viewers_desc,
    FluffySettingsKeys.SETTING_PREFER_CR_MIME_DESCRIPTION to R.string.setting_prefer_cr_mime_desc,
    FluffySettingsKeys.SETTING_SHOW_FILE_COUNT_DESCRIPTION to R.string.setting_show_file_count_desc,
    FluffySettingsKeys.SETTING_SHOW_HIDDEN_DESCRIPTION to R.string.setting_show_hidden_desc,
    FluffySettingsKeys.SETTING_SHOW_STORAGE_INFO_DESCRIPTION to R.string.setting_show_storage_info_desc,
    FluffySettingsKeys.SETTING_SHOW_THUMBNAILS_DESCRIPTION to R.string.setting_show_thumbnails_desc,
    FluffySettingsKeys.SETTING_SORT_REVERSE_DESCRIPTION to R.string.setting_sort_reverse_desc,
    FluffySettingsKeys.SETTING_SUPPORT_DEVELOPMENT_DESCRIPTION to
        R.string.setting_support_development_desc,
    FluffySettingsKeys.SETTING_VIEW_MODE_DESCRIPTION to R.string.setting_view_mode_desc,
    FluffySettingsKeys.SETTING_WARN_SHELL_WRITES_DESCRIPTION to R.string.setting_warn_shell_writes_desc,
    FluffySettingsKeys.SETTING_ZIP_LEVEL_DESCRIPTION to R.string.setting_zip_level_desc,
    FluffySettingsKeys.SETTING_DEFAULT_SORT_OPTIONS to R.array.setting_default_sort_options,
    FluffySettingsKeys.SETTING_THEME_OPTIONS to R.array.setting_theme_options,
    FluffySettingsKeys.SETTING_VIEW_MODE_OPTIONS to R.array.setting_view_mode_options,
)

fun fluffyStringResourceProvider(context: Context): StringResourceProvider =
    AndroidStringResourceProvider(context) { key -> settingsResources[key] ?: 0 }
