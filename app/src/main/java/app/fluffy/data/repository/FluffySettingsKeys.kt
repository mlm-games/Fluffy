package app.fluffy.data.repository

/**
 * Stable keys for the localised text of every setting and category.
 *
 * KSP cannot constant-fold an `R.string.*` reference written in an
 * annotation, so a `titleRes` there compiles to 0 and the UI falls back to
 * the English literal in every locale. [FluffyStringResourceProvider]
 * resolves these instead.
 */
object FluffySettingsKeys {
    const val CATEGORY_APPEARANCE_SETTINGS = "fluffy.settings.appearance_settings"
    const val CATEGORY_ARCHIVES = "fluffy.settings.archives"
    const val CATEGORY_GENERAL_SETTINGS = "fluffy.settings.general_settings"
    const val CATEGORY_SYSTEM_SETTINGS = "fluffy.settings.system_settings"
    const val SETTING_ALWAYS_INAPP_PICKER = "fluffy.settings.setting_always_inapp_picker"
    const val SETTING_DEFAULT_SORT = "fluffy.settings.setting_default_sort"
    const val SETTING_DYNAMIC_COLOR = "fluffy.settings.setting_dynamic_color"
    const val SETTING_ENABLE_ROOT = "fluffy.settings.setting_enable_root"
    const val SETTING_ENABLE_SHIZUKU = "fluffy.settings.setting_enable_shizuku"
    const val SETTING_EXTRACT_INTO_SUBFOLDER = "fluffy.settings.setting_extract_into_subfolder"
    const val SETTING_OLED_BLACK = "fluffy.settings.setting_oled_black"
    const val SETTING_PREFER_BUILTIN_VIEWERS = "fluffy.settings.setting_prefer_builtin_viewers"
    const val SETTING_PREFER_CR_MIME = "fluffy.settings.setting_prefer_cr_mime"
    const val SETTING_SHOW_FILE_COUNT = "fluffy.settings.setting_show_file_count"
    const val SETTING_SHOW_HIDDEN = "fluffy.settings.setting_show_hidden"
    const val SETTING_SHOW_STORAGE_INFO = "fluffy.settings.setting_show_storage_info"
    const val SETTING_SHOW_THUMBNAILS = "fluffy.settings.setting_show_thumbnails"
    const val SETTING_SORT_REVERSE = "fluffy.settings.setting_sort_reverse"
    const val SETTING_SUPPORT_DEVELOPMENT = "fluffy.settings.setting_support_development"
    const val SETTING_THEME = "fluffy.settings.setting_theme"
    const val SETTING_VIEW_MODE = "fluffy.settings.setting_view_mode"
    const val SETTING_WARN_SHELL_WRITES = "fluffy.settings.setting_warn_shell_writes"
    const val SETTING_ZIP_LEVEL = "fluffy.settings.setting_zip_level"
    const val SETTING_ALWAYS_INAPP_PICKER_DESCRIPTION = "fluffy.settings.setting_always_inapp_picker_desc"
    const val SETTING_DEFAULT_SORT_DESCRIPTION = "fluffy.settings.setting_default_sort_desc"
    const val SETTING_DYNAMIC_COLOR_DESCRIPTION = "fluffy.settings.setting_dynamic_color_desc"
    const val SETTING_ENABLE_ROOT_DESCRIPTION = "fluffy.settings.setting_enable_root_desc"
    const val SETTING_ENABLE_SHIZUKU_DESCRIPTION = "fluffy.settings.setting_enable_shizuku_desc"
    const val SETTING_EXTRACT_INTO_SUBFOLDER_DESCRIPTION = "fluffy.settings.setting_extract_into_subfolder_desc"
    const val SETTING_PREFER_BUILTIN_VIEWERS_DESCRIPTION = "fluffy.settings.setting_prefer_builtin_viewers_desc"
    const val SETTING_PREFER_CR_MIME_DESCRIPTION = "fluffy.settings.setting_prefer_cr_mime_desc"
    const val SETTING_SHOW_FILE_COUNT_DESCRIPTION = "fluffy.settings.setting_show_file_count_desc"
    const val SETTING_SHOW_HIDDEN_DESCRIPTION = "fluffy.settings.setting_show_hidden_desc"
    const val SETTING_SHOW_STORAGE_INFO_DESCRIPTION = "fluffy.settings.setting_show_storage_info_desc"
    const val SETTING_SHOW_THUMBNAILS_DESCRIPTION = "fluffy.settings.setting_show_thumbnails_desc"
    const val SETTING_SORT_REVERSE_DESCRIPTION = "fluffy.settings.setting_sort_reverse_desc"
    const val SETTING_SUPPORT_DEVELOPMENT_DESCRIPTION = "fluffy.settings.setting_support_development_desc"
    const val SETTING_VIEW_MODE_DESCRIPTION = "fluffy.settings.setting_view_mode_desc"
    const val SETTING_WARN_SHELL_WRITES_DESCRIPTION = "fluffy.settings.setting_warn_shell_writes_desc"
    const val SETTING_ZIP_LEVEL_DESCRIPTION = "fluffy.settings.setting_zip_level_desc"
    const val SETTING_DEFAULT_SORT_OPTIONS = "fluffy.settings.setting_default_sort_options"
    const val SETTING_THEME_OPTIONS = "fluffy.settings.setting_theme_options"
    const val SETTING_VIEW_MODE_OPTIONS = "fluffy.settings.setting_view_mode_options"
}
