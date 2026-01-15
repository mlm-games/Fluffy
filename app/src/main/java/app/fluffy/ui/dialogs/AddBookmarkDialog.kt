package app.fluffy.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fluffy.data.repository.Bookmark
import app.fluffy.data.repository.BookmarksRepository

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddBookmarkDialog(
    currentPath: String = "",
    onDismiss: () -> Unit,
    onConfirm: (Bookmark) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf(currentPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Bookmark") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Bookmark Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Full Path") },
                    singleLine = true,
                    placeholder = { Text("/data/local/tmp") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Quick Add:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BookmarksRepository.SHELL_PATHS.forEach { bookmark ->
                        SuggestionChip(
                            onClick = {
                                name = bookmark.name
                                path = bookmark.path
                            },
                            label = { Text(bookmark.name, maxLines = 1) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && path.isNotBlank()) {
                        onConfirm(Bookmark(name = name.trim(), path = path.trim()))
                    }
                },
                enabled = name.isNotBlank() && path.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}