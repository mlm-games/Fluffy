package app.fluffy.ui.viewers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun EditablePageIndicator(
    currentPage: Int,
    pageCount: Int,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onEditingFinished: () -> Unit = {},
) {
    if (pageCount <= 0) {
        Text(text = "0 / 0", modifier = modifier, style = MaterialTheme.typography.titleMedium)
        return
    }

    val shown = (currentPage + 1).coerceIn(1, pageCount)
    var field by remember { mutableStateOf(TextFieldValue(shown.toString())) }
    var editing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(8.dp)
    val digits = maxOf(shown.toString().length, pageCount.toString().length, field.text.length)
    val fieldWidth = 20.dp + 9.dp * digits

    LaunchedEffect(shown) {
        if (!editing) field = TextFieldValue(shown.toString(), TextRange(shown.toString().length))
    }

    fun commit() {
        val requested = field.text.trim().toIntOrNull()
        keyboard?.hide()
        if (requested != null) onNavigate((requested - 1).coerceIn(0, pageCount - 1))
        focusManager.clearFocus()
        onEditingFinished()
    }

    fun revert() {
        field = TextFieldValue(shown.toString(), TextRange(shown.toString().length))
        keyboard?.hide()
        focusManager.clearFocus()
        onEditingFinished()
    }

    Row(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { editing = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        commit()
                        true
                    }
                    Key.Escape -> {
                        revert()
                        true
                    }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BasicTextField(
            value = field,
            onValueChange = { incoming ->
                val digits = incoming.text.filter { it.isDigit() }.take(6)
                field = TextFieldValue(digits, TextRange(digits.length))
            },
            modifier = Modifier
                .width(fieldWidth)
                .semantics { contentDescription = "Page $shown of $pageCount" }
                .background(
                    if (editing) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape
                )
                .border(
                    width = if (editing) 2.dp else 1.dp,
                    color = if (editing) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    shape = shape
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            textStyle = MaterialTheme.typography.labelLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            ),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(onGo = { commit() })
        )
        Text(
            text = "/ $pageCount",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
