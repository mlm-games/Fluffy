package app.fluffy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object ThemeColors {
    val AuroraTeal = Color(0xFF00D9B5)
    val AuroraPurple = Color(0xFF8B5CF6)
    val AuroraGreen = Color(0xFF34D399)
    val AuroraPink = Color(0xFFEC4899)
    val AuroraBlue = Color(0xFF3B82F6)

    val DarkSurface = Color(0xFF0A0E1A)
    val DarkBackground = Color(0xFF050810)
    val DarkSurfaceVariant = Color(0xFF1A1F36)
    val DarkContainer = Color(0xFF151B2E)

    val LightSurface = Color(0xFFF7F9FC)
    val LightBackground = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFE8ECF4)
    val LightContainer = Color(0xFFDDE3EE)
}

val AurDarkTheme = darkColorScheme(
    primary = ThemeColors.AuroraTeal,
    onPrimary = Color(0xFF003830),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF70F5D9),

    secondary = ThemeColors.AuroraPurple,
    onSecondary = Color(0xFF3B1F70),
    secondaryContainer = Color(0xFF523895),
    onSecondaryContainer = Color(0xFFE4D4FF),

    tertiary = ThemeColors.AuroraGreen,
    onTertiary = Color(0xFF003A2B),
    tertiaryContainer = Color(0xFF005141),
    onTertiaryContainer = Color(0xFF8CF5D3),

    error = Color(0xFFFF6B6B),
    onError = Color(0xFF690000),
    errorContainer = Color(0xFF930000),
    onErrorContainer = Color(0xFFFFDAD6),

    background = ThemeColors.DarkBackground,
    onBackground = Color(0xFFE1E3E8),

    surface = ThemeColors.DarkSurface,
    onSurface = Color(0xFFE1E3E8),
    surfaceVariant = ThemeColors.DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC4C7CE),

    outline = Color(0xFF3E4759),
    outlineVariant = Color(0xFF2B3142),

    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE1E3E8),
    inverseOnSurface = Color(0xFF2E3138),
    inversePrimary = Color(0xFF006B5A),

    surfaceTint = ThemeColors.AuroraTeal,

    // New Material3 Expressive surface colors
    surfaceBright = Color(0xFF2A3040),
    surfaceDim = Color(0xFF050810),
    surfaceContainer = Color(0xFF151B2E),
    surfaceContainerLow = Color(0xFF0F1422),
    surfaceContainerLowest = Color(0xFF050810),
    surfaceContainerHigh = Color(0xFF1E2435),
    surfaceContainerHighest = Color(0xFF2A3040),

)

val AurLightTheme = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF73F5DD),
    onPrimaryContainer = Color(0xFF00201A),

    secondary = Color(0xFF6B47DC),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DDFF),
    onSecondaryContainer = Color(0xFF22005D),

    tertiary = Color(0xFF047857),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA7F3D0),
    onTertiaryContainer = Color(0xFF002117),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = ThemeColors.LightBackground,
    onBackground = Color(0xFF1A1C1E),

    surface = ThemeColors.LightSurface,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = ThemeColors.LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF414750),

    outline = Color(0xFF717784),
    outlineVariant = Color(0xFFC1C6D4),

    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2E3138),
    inverseOnSurface = Color(0xFFF0F1F5),
    inversePrimary = Color(0xFF4FDDC4),

    surfaceTint = Color(0xFF00695C),

    // New Material3 Expressive surface colors
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDE3EE),
    surfaceContainer = Color(0xFFE8ECF4),
    surfaceContainerLow = Color(0xFFF0F3F8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFDDE3EE),
    surfaceContainerHighest = Color(0xFFD1D9E6)
)


object ThemeDefaults {
    @Composable
    fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        errorBorderColor = MaterialTheme.colorScheme.error,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        errorLabelColor = MaterialTheme.colorScheme.error
    )
}
