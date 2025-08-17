package app.fluffy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import app.fluffy.ui.components.TvNavItem
import app.fluffy.ui.components.TvNavigationRail

@Composable
fun TvMainScreen(
    onNavigate: (String) -> Unit,
    currentRoute: String? = null,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Row(Modifier.fillMaxSize()) {
        // Enhanced TV navigation rail
        TvNavigationRail(
            selectedRoute = currentRoute,
            items = listOf(
                TvNavItem(
                    route = "files",
                    label = "Files",
                    icon = Icons.Outlined.Folder,
                    selectedIcon = Icons.Filled.Folder
                ),
                TvNavItem(
                    route = "tasks",
                    label = "Tasks",
                    icon = Icons.Outlined.Archive,
                    selectedIcon = Icons.Filled.Archive
                ),
                TvNavItem(
                    route = "settings",
                    label = "Settings",
                    icon = Icons.Outlined.Settings,
                    selectedIcon = Icons.Filled.Settings
                )
            ),
            onNavigate = onNavigate
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            colors.outline.copy(alpha = 0.1f),
                            colors.outline.copy(alpha = 0.3f),
                            colors.outline.copy(alpha = 0.1f)
                        )
                    )
                )
        )

        Surface(
            modifier = Modifier.weight(1f),
            color = colors.background
        ) {
            content()
        }
    }
}