package app.fluffy.ui.screens

import android.content.Intent
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
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.fluffy.R
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
import io.github.mlmgames.settings.core.resources.StringResourceProvider
import io.github.mlmgames.settings.core.types.Button
import io.github.mlmgames.settings.core.types.Dropdown
import io.github.mlmgames.settings.core.types.Slider
import io.github.mlmgames.settings.core.types.Toggle
import kotlin.reflect.KClass

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    val stringProvider: StringResourceProvider = org.koin.compose.koinInject()

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is SettingsViewModel.UiEvent.OpenUrl -> {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, event.url.toUri())
                        context.startActivity(intent)
                    }.onFailure {
                    }
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

    var refreshTick by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val rootAvail = remember(refreshTick) { RootAccess.isAvailable() }
    val shizukuAvail = remember(refreshTick) { ShizukuAccess.isAvailable() }
    val locale = LocalLocale.current.platformLocale

    val settingsTitle = stringResource(R.string.settings)
    val categoryTitles: Map<KClass<*>, String> =
        AppSettingsSchema.orderedCategories().associateWith { cat ->
            val annotation = cat.java.getAnnotation(
                io.github.mlmgames.settings.core.annotations.CategoryDefinition::class.java
            )
            if (annotation != null && annotation.titleRes != 0) {
                stringResource(annotation.titleRes)
            } else {
                cat.simpleName?.lowercase()?.replaceFirstChar { it.titlecase(locale) }
                    ?: settingsTitle
            }
        }

    MyScreenScaffold(title = settingsTitle) { _ ->
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
                        text = categoryTitles[category] ?: settingsTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                fields.forEach { field ->
                    item(key = field.name) {
                        val meta = field.meta ?: return@item
                        val enabledBySchema = schema.isEnabled(settings, field)

                        val resolvedDescription = meta.resolvedDescription(stringProvider)
                        val descriptionOverride = when (field.name) {
                            "enableRoot" -> {
                                val suffix = if (rootAvail) {
                                    stringResource(R.string.available)
                                } else {
                                    stringResource(R.string.not_available)
                                }
                                listOf(resolvedDescription, suffix)
                                    .filter { it.isNotBlank() }.joinToString(" • ")
                            }
                            "enableShizuku" -> {
                                val suffix = if (shizukuAvail) {
                                    stringResource(R.string.running)
                                } else {
                                    stringResource(R.string.not_running)
                                }
                                listOf(resolvedDescription, suffix)
                                    .filter { it.isNotBlank() }.joinToString(" • ")
                            }
                            else -> resolvedDescription
                        }.takeIf { it.isNotBlank() }

                        when (meta.type) {
                            Toggle::class -> {
                                val value = (field.get(settings) as? Boolean) ?: false
                                SettingsToggle(
                                    title = meta.resolvedTitle(stringProvider),
                                    description = descriptionOverride,
                                    isChecked = value,
                                    enabled = enabledBySchema,
                                    onCheckedChange = { vm.updateSetting(field.name, it) }
                                )
                            }

                            Dropdown::class -> {
                                val idx = field.toUiDropdownIndex(settings)
                                    ?: (field.get(settings) as? Int) ?: 0
                                val options = meta.dropdownLabels(field, stringProvider)
                                SettingsItem(
                                    title = meta.resolvedTitle(stringProvider),
                                    subtitle = options.getOrNull(idx) ?: stringResource(R.string.unknown),
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
                                    is Float -> String.format(LocalLocale.current.platformLocale, "%.1f", v)
                                    else -> ""
                                }
                                SettingsItem(
                                    title = meta.resolvedTitle(stringProvider),
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
                                    title = meta.resolvedTitle(stringProvider),
                                    description = descriptionOverride,
                                    buttonText = stringResource(R.string.run_action),
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
            val idx = field.toUiDropdownIndex(settings)
                ?: (field.get(settings) as? Int) ?: 0
            DropdownSettingDialog(
                title = meta.resolvedTitle(stringProvider),
                options = meta.dropdownLabels(field, stringProvider),
                selectedIndex = idx,
                onDismiss = { showDropdown = false },
                onOptionSelected = { i ->
                    val current = field.get(settings)
                    if (current is Int) {
                        vm.updateSetting(field.name, i)
                    } else {
                        field.fromUiDropdownIndex(i)?.let { vm.updateSetting(field.name, it) }
                    }
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
                title = meta.resolvedTitle(stringProvider),
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
