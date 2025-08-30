package app.fluffy.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun TvNavigationRail(
    selectedRoute: String?,
    items: List<TvNavItem>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme

    NavigationRail(
        modifier = modifier
            .fillMaxHeight()
            .width(100.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colors.surface,
                        colors.surface.copy(alpha = 0.95f)
                    )
                )
            ),
        containerColor = Color.Transparent
    ) {
        Spacer(Modifier.height(24.dp))

        items.forEach { item ->
            val selected = selectedRoute == item.route
            var focused by remember { mutableStateOf(false) }

            val scale by animateFloatAsState(
                targetValue = when {
                    focused -> 1.15f
                    selected -> 1.05f
                    else -> 1f
                },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy
                ),
                label = "nav_item_scale"
            )

            NavigationRailItem(
                selected = selected,
                onClick = { onNavigate(item.route) },
                icon = {
                    Box(
                        modifier = Modifier
                            .scale(scale)
                            .onFocusChanged { focused = it.isFocused }
                    ) {
                        if (focused) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                colors.primary.copy(alpha = 0.3f),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )
                        }
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.icon,
                            contentDescription = item.label,
                            tint = when {
                                focused -> colors.primary
                                selected -> colors.primary
                                else -> colors.onSurfaceVariant
                            }
                        )
                    }
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            focused -> colors.primary
                            selected -> colors.primary
                            else -> colors.onSurfaceVariant
                        }
                    )
                },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = colors.primary,
                    selectedTextColor = colors.primary,
                    indicatorColor = colors.primaryContainer.copy(alpha = 0.24f),
                    unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurfaceVariant
                ),
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .onFocusChanged { focused = it.isFocused }
            )
        }
    }
}

data class TvNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon
)