package app.fluffy.data.repository

import io.github.mlmgames.settings.core.annotations.ActionHandler
import io.github.mlmgames.settings.core.annotations.CategoryDefinition
import io.github.mlmgames.settings.core.annotations.Persisted
import io.github.mlmgames.settings.core.annotations.SchemaVersion
import io.github.mlmgames.settings.core.annotations.Setting
import io.github.mlmgames.settings.core.annotations.SettingAction
import io.github.mlmgames.settings.core.types.Button
import io.github.mlmgames.settings.core.types.Dropdown
import io.github.mlmgames.settings.core.types.Slider
import io.github.mlmgames.settings.core.types.Toggle

@SchemaVersion(1)
data class AppSettings(

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_DEFAULT_SORT,
        description = "Default sorting for lists",
        descriptionKey = FluffySettingsKeys.SETTING_DEFAULT_SORT_DESCRIPTION,
        category = General::class,
        type = Dropdown::class,
        options = ["Name", "Recently Updated", "Size", "Recently Added", "Type"],
        optionsKey = FluffySettingsKeys.SETTING_DEFAULT_SORT_OPTIONS,
        key = "default_sort"
    )
    val defaultSort: Int = 0,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_SORT_REVERSE,
        description = "Reverse current sort order (e.g. largest first)",
        descriptionKey = FluffySettingsKeys.SETTING_SORT_REVERSE_DESCRIPTION,
        category = General::class,
        type = Toggle::class,
        key = "sort_reverse"
    )
    val sortReverse: Boolean = false,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_SHOW_HIDDEN,
        description = "Show files and folders starting with a dot (.)",
        descriptionKey = FluffySettingsKeys.SETTING_SHOW_HIDDEN_DESCRIPTION,
        category = General::class,
        type = Toggle::class,
        key = "show_hidden"
    )
    val showHidden: Boolean = false,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_SHOW_FILE_COUNT,
        description = "Show count of files in directories",
        descriptionKey = FluffySettingsKeys.SETTING_SHOW_FILE_COUNT_DESCRIPTION,
        category = General::class,
        type = Toggle::class,
        key = "show_file_count"
    )
    val showFileCount: Boolean = true,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_SHOW_STORAGE_INFO,
        description = "Show an info button on Quick Access that opens device storage details",
        descriptionKey = FluffySettingsKeys.SETTING_SHOW_STORAGE_INFO_DESCRIPTION,
        category = General::class,
        type = Toggle::class,
        key = "show_storage_info"
    )
    val showStorageInfo: Boolean = true,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_ALWAYS_INAPP_PICKER,
        description = "Helps prevent stub issues (if not handled), and also for root ops",
        descriptionKey = FluffySettingsKeys.SETTING_ALWAYS_INAPP_PICKER_DESCRIPTION,
        category = System::class,
        type = Toggle::class,
        key = "always_inapp_folder_picker"
    )
    val alwaysUseInAppFolderPicker: Boolean = true,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_THEME,
        category = Appearance::class,
        type = Dropdown::class,
        options = ["System", "Light", "Dark"],
        optionsKey = FluffySettingsKeys.SETTING_THEME_OPTIONS,
        key = "theme_mode"
    )
    val themeMode: Int = 2,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_VIEW_MODE,
        description = "Default layout for file lists",
        descriptionKey = FluffySettingsKeys.SETTING_VIEW_MODE_DESCRIPTION,
        category = Appearance::class,
        type = Dropdown::class,
        options = ["List", "Grid"],
        optionsKey = FluffySettingsKeys.SETTING_VIEW_MODE_OPTIONS,
        key = "view_mode"
    )
    val viewMode: Int = 0,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_SHOW_THUMBNAILS,
        description = "Load image previews in list and grid (could cause lag for old TVs)",
        descriptionKey = FluffySettingsKeys.SETTING_SHOW_THUMBNAILS_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
        key = "show_thumbnails"
    )
    val showThumbnails: Boolean = false,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_DYNAMIC_COLOR,
        description = "Android 12+",
        descriptionKey = FluffySettingsKeys.SETTING_DYNAMIC_COLOR_DESCRIPTION,
        category = Appearance::class,
        type = Toggle::class,
        key = "dynamic_color"
    )
    val dynamicColor: Boolean = false,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_OLED_BLACK,
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
        titleKey = FluffySettingsKeys.SETTING_ZIP_LEVEL,
        description = "0 = no compression, 9 = maximum compression",
        descriptionKey = FluffySettingsKeys.SETTING_ZIP_LEVEL_DESCRIPTION,
        category = Archives::class,
        type = Slider::class,
        min = 0f, max = 9f, step = 1f,
        key = "zip_level"
    )
    val zipCompressionLevel: Float = 5f,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_ENABLE_ROOT,
        description = "Browse and write to system folders using root shell",
        descriptionKey = FluffySettingsKeys.SETTING_ENABLE_ROOT_DESCRIPTION,
        category = System::class,
        type = Toggle::class,
        key = "enable_root"
    )
    val enableRoot: Boolean = false,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_ENABLE_SHIZUKU,
        description = "Use Shizuku for shell commands and APK install",
        descriptionKey = FluffySettingsKeys.SETTING_ENABLE_SHIZUKU_DESCRIPTION,
        category = System::class,
        type = Toggle::class,
        key = "enable_shizuku"
    )
    val enableShizuku: Boolean = false,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_EXTRACT_INTO_SUBFOLDER,
        description = "Create a folder named after the archive when extracting",
        descriptionKey = FluffySettingsKeys.SETTING_EXTRACT_INTO_SUBFOLDER_DESCRIPTION,
        category = Archives::class,
        type = Toggle::class,
        key = "extract_into_subfolder"
    )
    val extractIntoSubfolder: Boolean = true,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_PREFER_CR_MIME,
        description = "Get type based on file and not from extension for 'Open with' and previews when available",
        descriptionKey = FluffySettingsKeys.SETTING_PREFER_CR_MIME_DESCRIPTION,
        category = General::class,
        type = Toggle::class,
        key = "prefer_cr_mime"
    )
    val preferContentResolverMime: Boolean = true,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_PREFER_BUILTIN_VIEWERS,
        description = "Open images, audio/video, PDF and text in Fluffy instead of the system Open with dialog.",
        descriptionKey = FluffySettingsKeys.SETTING_PREFER_BUILTIN_VIEWERS_DESCRIPTION,
        category = General::class,
        type = Toggle::class,
        key = "prefer_builtin_viewers"
    )
    val preferBuiltInViewers: Boolean = false,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_WARN_SHELL_WRITES,
        description = "Show a confirmation before writing/deleting via root or Shizuku",
        descriptionKey = FluffySettingsKeys.SETTING_WARN_SHELL_WRITES_DESCRIPTION,
        category = System::class,
        type = Toggle::class,
        key = "warn_shell_writes"
    )
    val warnBeforeShellWrites: Boolean = false,

    @Setting(
        titleKey = FluffySettingsKeys.SETTING_SUPPORT_DEVELOPMENT,
        description = "If you find this app useful, consider supporting its continued development",
        descriptionKey = FluffySettingsKeys.SETTING_SUPPORT_DEVELOPMENT_DESCRIPTION,
        category = System::class,
        type = Button::class
    )
    @ActionHandler(SupportDevelopmentAction::class)
    val supportDevelopment: Unit = Unit,
)

object SupportDevelopmentAction : SettingAction {
    override val id: String = "supportDevelopment"
}



@CategoryDefinition(order = 0, titleKey = FluffySettingsKeys.CATEGORY_GENERAL_SETTINGS) object General
@CategoryDefinition(order = 1, titleKey = FluffySettingsKeys.CATEGORY_APPEARANCE_SETTINGS) object Appearance
@CategoryDefinition(order = 2, titleKey = FluffySettingsKeys.CATEGORY_ARCHIVES) object Archives
@CategoryDefinition(order = 3, titleKey = FluffySettingsKeys.CATEGORY_SYSTEM_SETTINGS) object System
