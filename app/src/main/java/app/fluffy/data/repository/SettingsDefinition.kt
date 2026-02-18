package app.fluffy.data.repository

import io.github.mlmgames.settings.core.annotations.CategoryDefinition
import io.github.mlmgames.settings.core.annotations.Persisted
import io.github.mlmgames.settings.core.annotations.SchemaVersion
import io.github.mlmgames.settings.core.annotations.Setting
import io.github.mlmgames.settings.core.types.Dropdown
import io.github.mlmgames.settings.core.types.Slider
import io.github.mlmgames.settings.core.types.Toggle

@SchemaVersion(1)
data class AppSettings(

    @Setting(
        title = "Default Sort",
        description = "Default sorting for lists",
        category = General::class,
        type = Dropdown::class,
        options = ["Name", "Recently Updated", "Size", "Recently Added"],
        key = "default_sort"
    )
    val defaultSort: Int = 0,

    @Setting(
        title = "Show hidden files",
        description = "Show files and folders starting with a dot (.)",
        category = General::class,
        type = Toggle::class,
        key = "show_hidden"
    )
    val showHidden: Boolean = false,

    @Setting(
        title = "Show file count in dirs",
        description = "Show count of files in directories",
        category = General::class,
        type = Toggle::class,
        key = "show_file_count"
    )
    val showFileCount: Boolean = true,

    @Setting(
        title = "Show In-App Folder Picker Everywhere",
        description = "Helps prevent stub issues (if not handled), and also for root ops",
        category = System::class,
        type = Toggle::class,
        key = "always_inapp_folder_picker"
    )
    val alwaysUseInAppFolderPicker: Boolean = true,

    @Setting(
        title = "Theme",
        category = Appearance::class,
        type = Dropdown::class,
        options = ["System", "Light", "Dark"],
        key = "theme_mode"
    )
    val themeMode: Int = 2,

    // Not currently shown in settings UI, but used by FluffyTheme
    @Persisted(key = "use_aurora_theme")
    val useAuroraTheme: Boolean = true,

    @Persisted(key = "cta_banner_dismissed_2026")
    val ctaBannerDismissed2026: Boolean = false,

    @Setting(
        title = "ZIP compression level",
        description = "0 = no compression, 9 = maximum compression",
        category = Archives::class,
        type = Slider::class,
        min = 0f, max = 9f, step = 1f,
        key = "zip_level"
    )
    val zipCompressionLevel: Int = 5,

    @Setting(
        title = "Enable Root access",
        description = "Browse and write to system folders using root shell",
        category = System::class,
        type = Toggle::class,
        key = "enable_root"
    )
    val enableRoot: Boolean = false,

    @Setting(
        title = "Enable Shizuku",
        description = "Use Shizuku for shell commands and APK install",
        category = System::class,
        type = Toggle::class,
        key = "enable_shizuku"
    )
    val enableShizuku: Boolean = false,

    @Setting(
        title = "Extract into subfolder",
        description = "Create a folder named after the archive when extracting",
        category = Archives::class,
        type = Toggle::class,
        key = "extract_into_subfolder"
    )
    val extractIntoSubfolder: Boolean = true,

    @Setting(
        title = "Prefer ContentResolver MIME",
        description = "Get type based on file and not from extension for 'Open with' and previews when available",
        category = General::class,
        type = Toggle::class,
        key = "prefer_cr_mime"
    )
    val preferContentResolverMime: Boolean = true,

    @Setting(
        title = "Warn before elevated writes",
        description = "Show a confirmation before writing/deleting via root or Shizuku",
        category = System::class,
        type = Toggle::class,
        key = "warn_shell_writes"
    )
    val warnBeforeShellWrites: Boolean = false,
)



@CategoryDefinition(order = 0) object General
@CategoryDefinition(order = 1) object Appearance
@CategoryDefinition(order = 2) object Archives
@CategoryDefinition(order = 3) object System