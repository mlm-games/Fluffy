package app.fluffy.data.repository

import app.fluffy.R
import io.github.mlmgames.settings.core.annotations.CategoryDefinition
import io.github.mlmgames.settings.core.annotations.Persisted
import io.github.mlmgames.settings.core.annotations.SchemaVersion
import io.github.mlmgames.settings.core.annotations.Setting
import io.github.mlmgames.settings.core.types.Button
import io.github.mlmgames.settings.core.types.Dropdown
import io.github.mlmgames.settings.core.types.Slider
import io.github.mlmgames.settings.core.types.Toggle

@SchemaVersion(1)
data class AppSettings(

    @Setting(
        titleRes = R.string.setting_default_sort,
        description = "Default sorting for lists",
        descriptionRes = R.string.setting_default_sort_desc,
        category = General::class,
        type = Dropdown::class,
        options = ["Name", "Recently Updated", "Size", "Recently Added", "Type"],
        optionsRes = R.array.setting_default_sort_options,
        key = "default_sort"
    )
    val defaultSort: Int = 0,

    @Setting(
        titleRes = R.string.setting_sort_reverse,
        description = "Reverse current sort order (e.g. largest first)",
        descriptionRes = R.string.setting_sort_reverse_desc,
        category = General::class,
        type = Toggle::class,
        key = "sort_reverse"
    )
    val sortReverse: Boolean = false,

    @Setting(
        titleRes = R.string.setting_show_hidden,
        description = "Show files and folders starting with a dot (.)",
        descriptionRes = R.string.setting_show_hidden_desc,
        category = General::class,
        type = Toggle::class,
        key = "show_hidden"
    )
    val showHidden: Boolean = false,

    @Setting(
        titleRes = R.string.setting_show_file_count,
        description = "Show count of files in directories",
        descriptionRes = R.string.setting_show_file_count_desc,
        category = General::class,
        type = Toggle::class,
        key = "show_file_count"
    )
    val showFileCount: Boolean = true,

    @Setting(
        titleRes = R.string.setting_show_storage_info,
        description = "Show an info button on Quick Access that opens device storage details",
        descriptionRes = R.string.setting_show_storage_info_desc,
        category = General::class,
        type = Toggle::class,
        key = "show_storage_info"
    )
    val showStorageInfo: Boolean = true,

    @Setting(
        titleRes = R.string.setting_always_inapp_picker,
        description = "Helps prevent stub issues (if not handled), and also for root ops",
        descriptionRes = R.string.setting_always_inapp_picker_desc,
        category = System::class,
        type = Toggle::class,
        key = "always_inapp_folder_picker"
    )
    val alwaysUseInAppFolderPicker: Boolean = true,

    @Setting(
        titleRes = R.string.setting_theme,
        category = Appearance::class,
        type = Dropdown::class,
        options = ["System", "Light", "Dark"],
        optionsRes = R.array.setting_theme_options,
        key = "theme_mode"
    )
    val themeMode: Int = 2,

    @Setting(
        titleRes = R.string.setting_view_mode,
        description = "Default layout for file lists",
        descriptionRes = R.string.setting_view_mode_desc,
        category = Appearance::class,
        type = Dropdown::class,
        options = ["List", "Grid"],
        optionsRes = R.array.setting_view_mode_options,
        key = "view_mode"
    )
    val viewMode: Int = 0,

    @Setting(
        titleRes = R.string.setting_show_thumbnails,
        description = "Load image previews in list and grid (could cause lag for old TVs)",
        descriptionRes = R.string.setting_show_thumbnails_desc,
        category = Appearance::class,
        type = Toggle::class,
        key = "show_thumbnails"
    )
    val showThumbnails: Boolean = false,

    @Setting(
        titleRes = R.string.setting_dynamic_color,
        description = "Android 12+",
        descriptionRes = R.string.setting_dynamic_color_desc,
        category = Appearance::class,
        type = Toggle::class,
        key = "dynamic_color"
    )
    val dynamicColor: Boolean = false,

    @Setting(
        titleRes = R.string.setting_oled_black,
        category = Appearance::class,
        type = Toggle::class,
        key = "oled_black"
    )
    val oledBlack: Boolean = false,

    // Not currently shown in settings UI, but used by FluffyTheme
    @Persisted(key = "use_aurora_theme")
    val useAuroraTheme: Boolean = true,

    @Persisted(key = "cta_banner_dismissed_2026")
    val ctaBannerDismissed2026: Boolean = false,

    @Setting(
        titleRes = R.string.setting_zip_level,
        description = "0 = no compression, 9 = maximum compression",
        descriptionRes = R.string.setting_zip_level_desc,
        category = Archives::class,
        type = Slider::class,
        min = 0f, max = 9f, step = 1f,
        key = "zip_level"
    )
    val zipCompressionLevel: Float = 5f,

    @Setting(
        titleRes = R.string.setting_enable_root,
        description = "Browse and write to system folders using root shell",
        descriptionRes = R.string.setting_enable_root_desc,
        category = System::class,
        type = Toggle::class,
        key = "enable_root"
    )
    val enableRoot: Boolean = false,

    @Setting(
        titleRes = R.string.setting_enable_shizuku,
        description = "Use Shizuku for shell commands and APK install",
        descriptionRes = R.string.setting_enable_shizuku_desc,
        category = System::class,
        type = Toggle::class,
        key = "enable_shizuku"
    )
    val enableShizuku: Boolean = false,

    @Setting(
        titleRes = R.string.setting_extract_into_subfolder,
        description = "Create a folder named after the archive when extracting",
        descriptionRes = R.string.setting_extract_into_subfolder_desc,
        category = Archives::class,
        type = Toggle::class,
        key = "extract_into_subfolder"
    )
    val extractIntoSubfolder: Boolean = true,

    @Setting(
        titleRes = R.string.setting_prefer_cr_mime,
        description = "Get type based on file and not from extension for 'Open with' and previews when available",
        descriptionRes = R.string.setting_prefer_cr_mime_desc,
        category = General::class,
        type = Toggle::class,
        key = "prefer_cr_mime"
    )
    val preferContentResolverMime: Boolean = true,

    @Setting(
        titleRes = R.string.setting_prefer_builtin_viewers,
        description = "Open images, audio/video, PDF and text in Fluffy instead of the system Open with dialog.",
        descriptionRes = R.string.setting_prefer_builtin_viewers_desc,
        category = General::class,
        type = Toggle::class,
        key = "prefer_builtin_viewers"
    )
    val preferBuiltInViewers: Boolean = false,

    @Setting(
        titleRes = R.string.setting_warn_shell_writes,
        description = "Show a confirmation before writing/deleting via root or Shizuku",
        descriptionRes = R.string.setting_warn_shell_writes_desc,
        category = System::class,
        type = Toggle::class,
        key = "warn_shell_writes"
    )
    val warnBeforeShellWrites: Boolean = false,

    @Setting(
        titleRes = R.string.setting_support_development,
        description = "If you find this app useful, consider supporting its continued development",
        descriptionRes = R.string.setting_support_development_desc,
        category = System::class,
        type = Button::class
    )
    val supportDevelopment: Long = 0L,
)



@CategoryDefinition(order = 0, titleRes = R.string.category_general_settings) object General
@CategoryDefinition(order = 1, titleRes = R.string.category_appearance_settings) object Appearance
@CategoryDefinition(order = 2, titleRes = R.string.category_archives) object Archives
@CategoryDefinition(order = 3, titleRes = R.string.category_system_settings) object System
