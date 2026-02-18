package app.fluffy.ui.components

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.fluffy.AppGraph
import kotlinx.coroutines.flow.first
import java.time.LocalDate

object AlertBannerManager {
    private val EXPIRY_DATE = LocalDate.of(2026, 9, 1)

    suspend fun shouldShowBanner(context: Context): Boolean {
        if (LocalDate.now().isAfter(EXPIRY_DATE)) return false
        val settings = AppGraph.settings.settingsFlow.first()
        if (settings.ctaBannerDismissed2026) return false

        val pm = context.packageManager
        val isTv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val hasTouch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return !isTv && hasTouch
    }

    suspend fun dismissBanner() {
        AppGraph.settings.updateSetting("ctaBannerDismissed2026", true)
    }
}

@Composable
fun AlertBanner(
    text: String,
    linkText: String,
    url: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val annotatedText = buildAnnotatedString {
        append(text)
        append(" ")
        pushStyle(SpanStyle(color = Color(0xFF1565C0), textDecoration = TextDecoration.Underline))
        append(linkText)
        pop()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(Color(0x33F44336))
            .clickable {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = annotatedText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.padding(start = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
