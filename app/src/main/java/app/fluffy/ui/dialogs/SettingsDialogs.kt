package app.fluffy.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.fluffy.ui.theme.ThemeDefaults
import java.util.Locale

/**
 * Dialog for slider-based settings
 */
@Composable
fun SliderSettingDialog(
    title: String,
    currentValue: Float,
    min: Float,
    max: Float,
    step: Float,
    onDismiss: () -> Unit,
    onValueSelected: (Float) -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(currentValue) }

    FluffyDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = {
            TextButton(
                onClick = { onValueSelected(sliderValue) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text("Cancel")
            }
        }
    ) {
        Column {
            // Value display
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format(Locale.getDefault(), "%.1f", sliderValue),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Slider(
                value = sliderValue,
                onValueChange = {
                    val steps = ((it - min) / step).toInt()
                    sliderValue = min + (steps * step)
                },
                valueRange = min..max,
                steps = ((max - min) / step).toInt() - 1,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )

            // Min/Max labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = min.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = max.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Updated DropdownSettingDialog using SelectionDialog
@Composable
fun DropdownSettingDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onOptionSelected: (Int) -> Unit
) {
    SelectionDialog(
        title = title,
        items = options.indices.toList(),
        selectedItem = selectedIndex,
        onItemSelected = onOptionSelected,
        onDismiss = onDismiss,
        itemLabel = { index -> options[index] }
    )
}

/**
 * Base themed dialog wrapper for consistent styling across the app
 */
@Composable
fun FluffyDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    icon: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        icon = icon,
        title = title?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    content = content
                )
            }
        },
        properties = properties,
        containerColor = MaterialTheme.colorScheme.surface,
        iconContentColor = MaterialTheme.colorScheme.secondary,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 6.dp
    )
}

/**
 * Input dialog for text entry
 */
@Composable
fun InputDialog(
    title: String,
    label: String,
    value: String,
    placeholder: String = "",
    confirmText: String = "OK",
    dismissText: String = "Cancel",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    validator: (String) -> Boolean = { true }
) {
    var inputValue by remember { mutableStateOf(value) }
    val isValid = validator(inputValue)

    FluffyDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(inputValue) },
                enabled = isValid,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(dismissText)
            }
        }
    ) {
        OutlinedTextField(
            value = inputValue,
            onValueChange = { inputValue = it },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            isError = !isValid && inputValue.isNotEmpty(),
            singleLine = true,
            colors = ThemeDefaults.outlinedTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Selection dialog for choosing from a list
 */
@Composable
fun <T> SelectionDialog(
    title: String,
    items: List<T>,
    selectedItem: T? = null,
    onItemSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    itemLabel: (T) -> String
) {
    var selected by remember { mutableStateOf(selectedItem) }

    FluffyDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = {
            TextButton(
                onClick = {
                    selected?.let { onItemSelected(it) }
                },
                enabled = selected != null,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Select")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text("Cancel")
            }
        }
    ) {
        Column {
            items.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = (selected == item),
                            onClick = { selected = item }
                        )
                        .padding(vertical = 12.dp, horizontal = 16.dp)
//                        .background(
//                            shape = RoundedCornerShape(12.dp),
//                            color = MaterialTheme.colorScheme.surface
//                        ) The focus overlay looks odd with it
                ) {
                    RadioButton(
                        selected = (selected == item),
                        onClick = { selected = item },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = itemLabel(item),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}