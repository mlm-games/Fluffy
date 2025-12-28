package app.fluffy.ui.util

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface ScreenKey : NavKey {
    @Serializable data object Files : ScreenKey
    @Serializable data class Archive(val uri: String) : ScreenKey
    @Serializable data object Tasks : ScreenKey
    @Serializable data object Settings : ScreenKey
}