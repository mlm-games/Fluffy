package app.fluffy.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun TvMainScreen(
    onNavigate: (String) -> Unit,
    currentRoute: String? = null,
    content: @Composable () -> Unit
) {
    Row(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxHeight().width(280.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Fluffy", style = MaterialTheme.typography.titleLarge)
                Text("File Manager", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                TvNavItem("Files", Icons.Default.Folder, selected = currentRoute == "files") { onNavigate("files") }
                TvNavItem("Tasks", Icons.Default.Archive, selected = currentRoute == "tasks") { onNavigate("tasks") }
                TvNavItem("Settings", Icons.Default.Settings, selected = currentRoute == "settings") { onNavigate("settings") }
                Spacer(Modifier.weight(1f))
            }
        }
        Divider(modifier = Modifier.width(1.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun TvNavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}