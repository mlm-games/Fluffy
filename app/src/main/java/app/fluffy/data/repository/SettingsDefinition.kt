package app.fluffy.data.repository

import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.PROPERTY
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

@Target(PROPERTY)
@Retention(RUNTIME)
annotation class Setting(
    val title: String,
    val description: String = "",
    val category: SettingCategory,
    val type: SettingType,
    val dependsOn: String = "",
    val min: Float = 0f,
    val max: Float = 100f,
    val step: Float = 1f,
    val options: Array<String> = []
)

enum class SettingCategory { GENERAL, APPEARANCE, ARCHIVES, SYSTEM }
enum class SettingType { TOGGLE, DROPDOWN, SLIDER, BUTTON }

data class AppSettings(

    @Setting(
        title = "Default Sort",
        description = "Default sorting for lists",
        category = SettingCategory.GENERAL,
        type = SettingType.DROPDOWN,
        options = ["Name", "Recently Updated", "Size", "Recently Added"]
    )
    val defaultSort: Int = 0,

    @Setting(
        title = "Show hidden files",
        description = "Show files and folders starting with a dot (.)",
        category = SettingCategory.GENERAL,
        type = SettingType.TOGGLE
    )
    val showHidden: Boolean = false,

    @Setting(
        title = "Theme",
        category = SettingCategory.APPEARANCE,
        type = SettingType.DROPDOWN,
        options = ["System", "Light", "Dark"]
    )
    val themeMode: Int = 2,

//    @Setting(
//        title = "Use Aurora Theme",
//        description = "Enable the Aurora color theme with gradients",
//        category = SettingCategory.APPEARANCE,
//        type = SettingType.TOGGLE
//    )
    val useAuroraTheme: Boolean = true,

    @Setting(
        title = "ZIP compression level",
        description = "0 = no compression, 9 = maximum compression",
        category = SettingCategory.ARCHIVES,
        type = SettingType.SLIDER,
        min = 0f, max = 9f, step = 1f
    )
    val zipCompressionLevel: Int = 5,
    @Setting(
        title = "Enable Root access (beta)",
        description = "Browse and write to system folders using root shell",
        category = SettingCategory.SYSTEM,
        type = SettingType.TOGGLE
    )
    val enableRoot: Boolean = false,

    @Setting(
        title = "Enable Shizuku (beta)",
        description = "Use Shizuku for shell commands and APK install",
        category = SettingCategory.SYSTEM,
        type = SettingType.TOGGLE
    )
    val enableShizuku: Boolean = false,


    @Setting(
        title = "Extract into subfolder",
        description = "Create a folder named after the archive when extracting",
        category = SettingCategory.ARCHIVES,
        type = SettingType.TOGGLE
    )
    val extractIntoSubfolder: Boolean = true,

    @Setting(
        title = "Prefer ContentResolver MIME",
        description = "Get type based on file and not from extension for 'Open with' and previews when available",
        category = SettingCategory.GENERAL,
        type = SettingType.TOGGLE
    )
    val preferContentResolverMime: Boolean = true,

    @Setting(
        title = "Warn before elevated writes",
        description = "Show a confirmation before writing/deleting via root or Shizuku",
        category = SettingCategory.SYSTEM,
        type = SettingType.TOGGLE
    )
    val warnBeforeShellWrites: Boolean = false,

)

class SettingsManager {
    fun getAll(): List<Pair<KProperty1<AppSettings, *>, Setting>> {
        return AppSettings::class.memberProperties.mapNotNull { p ->
            val ann = p.findAnnotation<Setting>()
            if (ann != null) p to ann else null
        }
    }
    fun getByCategory(): Map<SettingCategory, List<Pair<KProperty1<AppSettings, *>, Setting>>> {
        return getAll().groupBy { it.second.category }
    }
    fun isEnabled(settings: AppSettings, property: KProperty1<AppSettings, *>, annotation: Setting): Boolean {
        val depends = annotation.dependsOn
        if (depends.isBlank()) return true
        val depProp = AppSettings::class.memberProperties.find { it.name == depends }
        return if (depProp != null) {
            val v = depProp.get(settings)
            (v as? Boolean) ?: true
        } else true
    }
}