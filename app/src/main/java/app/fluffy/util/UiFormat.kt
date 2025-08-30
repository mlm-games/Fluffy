package app.fluffy.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object UiFormat {
    // 1024-based (KB, MB, GB)
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(3)
        val pre = arrayOf("KB", "MB", "GB")[exp - 1]
        val value = bytes / 1024.0.pow(exp.toDouble())
        return String.format(Locale.getDefault(), "%.1f %s", value, pre)
    }

    fun formatDate(timestamp: Long, pattern: String = "MMM dd, yyyy"): String {
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}