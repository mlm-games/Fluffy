package app.fluffy.helper

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties

object DeviceUtils {
    fun isTV(context: Context): Boolean {
        val pm = context.packageManager
        val featureHit = pm.hasSystemFeature("android.software.leanback") ||
                pm.hasSystemFeature("android.hardware.type.television")
        if (featureHit) return true
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}

fun Modifier.cardAsFocusGroup() = this.focusGroup().focusProperties { canFocus = false }