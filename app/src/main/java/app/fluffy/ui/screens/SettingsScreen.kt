package app.fluffy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.fluffy.data.repository.AppSettings
import app.fluffy.data.repository.Setting
import app.fluffy.data.repository.SettingCategory
import app.fluffy.data.repository.SettingType
import app.fluffy.data.repository.SettingsManager
import app.fluffy.ui.components.MyScreenScaffold
import app.fluffy.ui.components.SettingsAction
import app.fluffy.ui.components.SettingsItem
import app.fluffy.ui.components.SettingsToggle
import app.fluffy.ui.dialogs.DropdownSettingDialog
import app.fluffy.ui.dialogs.SliderSettingDialog
import app.fluffy.shell.RootAccess
import app.fluffy.shell.ShizukuAccess
import app.fluffy.viewmodel.SettingsViewModel
import java.util.Locale
import kotlin.reflect.KProperty1

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsState()
    val manager = remember { SettingsManager() }

    var showDropdown by remember { mutableStateOf(false) }
    var showSlider by remember { mutableStateOf(false) }
    var currentProp by remember { mutableStateOf<KProperty1<AppSettings, *>?>(null) }
    var currentAnn by remember { mutableStateOf<Setting?>(null) }

    val grouped = remember { manager.getByCategory() }
    val cfg = LocalConfiguration.current
    val gridCells = remember(cfg.screenWidthDp) { GridCells.Adaptive(minSize = 420.dp) }

    val rootAvail by remember { mutableStateOf(RootAccess.isAvailable()) }
    val shizukuAvail by remember { mutableStateOf(ShizukuAccess.isAvailable()) }

    MyScreenScaffold(title = "Settings") { _ ->
        LazyVerticalGrid(
            columns = gridCells,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
//            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            for (category in SettingCategory.entries) {
                val itemsForCat = grouped[category] ?: emptyList()
                if (itemsForCat.isEmpty()) continue

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = category.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(itemsForCat, key = { it.first.name }) { (prop, ann) ->
                    val enabled = manager.isEnabled(settings, prop, ann)

                    val descriptionOverride = when (prop.name) {
                        "enableRoot" -> {
                            val suffix = if (rootAvail) "Available" else "Not available"
                            listOf(ann.description, suffix).filter { it.isNotBlank() }.joinToString(" • ")
                        }
                        "enableShizuku" -> {
                            val suffix = if (shizukuAvail) "Running" else "Not running"
                            listOf(ann.description, suffix).filter { it.isNotBlank() }.joinToString(" • ")
                        }
                        else -> ann.description
                    }.takeIf { it.isNotBlank() }

                    when (ann.type) {
                        SettingType.TOGGLE -> {
                            val value = prop.get(settings) as? Boolean ?: false
                            SettingsToggle(
                                title = ann.title,
                                description = descriptionOverride,
                                isChecked = value,
                                enabled = enabled,
                                onCheckedChange = { vm.updateSetting(prop.name, it) }
                            )
                        }
                        SettingType.DROPDOWN -> {
                            val idx = prop.get(settings) as? Int ?: 0
                            val options = ann.options.toList()
                            SettingsItem(
                                title = ann.title,
                                subtitle = options.getOrNull(idx) ?: "Unknown",
                                description = ann.description.takeIf { it.isNotBlank() },
                                enabled = enabled
                            ) {
                                currentProp = prop
                                currentAnn = ann
                                showDropdown = true
                            }
                        }
                        SettingType.SLIDER -> {
                            val valueText = when (val v = prop.get(settings)) {
                                is Int -> v.toString()
                                is Float -> String.format(Locale.getDefault(),"%.1f", v)
                                else -> ""
                            }
                            SettingsItem(
                                title = ann.title,
                                subtitle = valueText,
                                description = ann.description.takeIf { it.isNotBlank() },
                                enabled = enabled
                            ) {
                                currentProp = prop
                                currentAnn = ann
                                showSlider = true
                            }
                        }
                        SettingType.BUTTON -> {
                            SettingsAction(
                                title = ann.title,
                                description = ann.description.takeIf { it.isNotBlank() },
                                buttonText = "Run",
                                enabled = enabled,
                                onClick = { vm.performAction(prop.name) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDropdown && currentProp != null && currentAnn != null) {
        val prop = currentProp!!
        val ann = currentAnn!!
        val idx = prop.get(settings) as? Int ?: 0
        DropdownSettingDialog(
            title = ann.title,
            options = ann.options.toList(),
            selectedIndex = idx,
            onDismiss = { showDropdown = false },
            onOptionSelected = { i ->
                vm.updateSetting(prop.name, i)
                showDropdown = false
            }
        )
    }

    if (showSlider && currentProp != null && currentAnn != null) {
        val prop = currentProp!!
        val ann = currentAnn!!
        val cur = when (val v = prop.get(settings)) {
            is Int -> v.toFloat()
            is Float -> v
            else -> 0f
        }
        SliderSettingDialog(
            title = ann.title,
            currentValue = cur,
            min = ann.min,
            max = ann.max,
            step = ann.step,
            onDismiss = { showSlider = false },
            onValueSelected = { value ->
                when (prop.returnType.classifier) {
                    Int::class -> vm.updateSetting(prop.name, value.toInt())
                    Float::class -> vm.updateSetting(prop.name, value)
                }
                showSlider = false
            }
        )
    }
}
