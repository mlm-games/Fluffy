package app.fluffy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.fluffy.data.repository.AppSettings
import app.fluffy.data.repository.AppSettingsSchema
import app.fluffy.ui.components.MyScreenScaffold
import app.fluffy.ui.components.SettingsAction
import app.fluffy.ui.components.SettingsItem
import app.fluffy.ui.components.SettingsToggle
import app.fluffy.ui.dialogs.DropdownSettingDialog
import app.fluffy.ui.dialogs.SliderSettingDialog
import app.fluffy.shell.RootAccess
import app.fluffy.shell.ShizukuAccess
import app.fluffy.viewmodel.SettingsViewModel
import io.github.mlmgames.settings.core.SettingField
import io.github.mlmgames.settings.core.types.Button
import io.github.mlmgames.settings.core.types.Dropdown
import io.github.mlmgames.settings.core.types.Slider
import io.github.mlmgames.settings.core.types.Toggle
import java.util.Locale
import kotlin.reflect.KClass

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is SettingsViewModel.UiEvent.OpenUrl -> {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, event.url.toUri())
                    context.startActivity(intent)
                }
                is SettingsViewModel.UiEvent.Toast -> {}
            }
        }
    }

    val schema = remember { AppSettingsSchema }

    var showDropdown by remember { mutableStateOf(false) }
    var showSlider by remember { mutableStateOf(false) }
    var currentField by remember { mutableStateOf<SettingField<AppSettings, *>?>(null) }

    val cfg = LocalConfiguration.current
    val gridCells = remember(cfg.screenWidthDp) { GridCells.Adaptive(minSize = 420.dp) }

    val rootAvail by remember { mutableStateOf(RootAccess.isAvailable()) }
    val shizukuAvail by remember { mutableStateOf(ShizukuAccess.isAvailable()) }

    fun categoryTitle(cat: KClass<*>): String =
        cat.simpleName?.lowercase()?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: "Settings"

    MyScreenScaffold(title = "Settings") { _ ->
        LazyVerticalGrid(
            columns = gridCells,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            val categories = schema.orderedCategories()
            val grouped = schema.groupedByCategory()

            for (category in categories) {
                val fields = grouped[category].orEmpty()
                if (fields.isEmpty()) continue

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = categoryTitle(category),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                fields.forEach { field ->
                    item(key = field.name) {
                        val meta = field.meta ?: return@item
                        val enabledBySchema = schema.isEnabled(settings, field)

                        val descriptionOverride = when (field.name) {
                            "enableRoot" -> {
                                val suffix = if (rootAvail) "Available" else "Not available"
                                listOf(meta.description, suffix).filter { it.isNotBlank() }.joinToString(" • ")
                            }
                            "enableShizuku" -> {
                                val suffix = if (shizukuAvail) "Running" else "Not running"
                                listOf(meta.description, suffix).filter { it.isNotBlank() }.joinToString(" • ")
                            }
                            else -> meta.description
                        }.takeIf { it.isNotBlank() }

                        when (meta.type) {
                            Toggle::class -> {
                                val value = (field.get(settings) as? Boolean) ?: false
                                SettingsToggle(
                                    title = meta.title,
                                    description = descriptionOverride,
                                    isChecked = value,
                                    enabled = enabledBySchema,
                                    onCheckedChange = { vm.updateSetting(field.name, it) }
                                )
                            }

                            Dropdown::class -> {
                                val idx = (field.get(settings) as? Int) ?: 0
                                val options = meta.options
                                SettingsItem(
                                    title = meta.title,
                                    subtitle = options.getOrNull(idx) ?: "Unknown",
                                    description = descriptionOverride,
                                    enabled = enabledBySchema
                                ) {
                                    currentField = field
                                    showDropdown = true
                                }
                            }

                            Slider::class -> {
                                val subtitle = when (val v = field.get(settings)) {
                                    is Int -> v.toString()
                                    is Float -> String.format(Locale.getDefault(), "%.1f", v)
                                    else -> ""
                                }
                                SettingsItem(
                                    title = meta.title,
                                    subtitle = subtitle,
                                    description = descriptionOverride,
                                    enabled = enabledBySchema
                                ) {
                                    currentField = field
                                    showSlider = true
                                }
                            }

                            Button::class -> {
                                SettingsAction(
                                    title = meta.title,
                                    description = descriptionOverride,
                                    buttonText = "Run",
                                    enabled = enabledBySchema,
                                    onClick = { vm.performAction(field.name) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDropdown) {
        val field = currentField
        val meta = field?.meta
        if (field != null && meta != null) {
            val idx = (field.get(settings) as? Int) ?: 0
            DropdownSettingDialog(
                title = meta.title,
                options = meta.options,
                selectedIndex = idx,
                onDismiss = { showDropdown = false },
                onOptionSelected = { i ->
                    vm.updateSetting(field.name, i)
                    showDropdown = false
                }
            )
        }
    }

    if (showSlider) {
        val field = currentField
        val meta = field?.meta
        if (field != null && meta != null) {
            val cur = when (val v = field.get(settings)) {
                is Int -> v.toFloat()
                is Float -> v
                else -> 0f
            }
            SliderSettingDialog(
                title = meta.title,
                currentValue = cur,
                min = meta.min,
                max = meta.max,
                step = meta.step,
                onDismiss = { showSlider = false },
                onValueSelected = { value ->
                    when (field.get(settings)) {
                        is Int -> vm.updateSetting(field.name, value.toInt())
                        is Float -> vm.updateSetting(field.name, value)
                    }
                    showSlider = false
                }
            )
        }
    }
}