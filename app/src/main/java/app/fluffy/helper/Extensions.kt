package app.fluffy.helper

import android.content.Context
import android.widget.Toast


/**
 * Show a toast message with flexible input types
 * @param message Can be a String, StringRes Int, or null
 * @param duration Toast duration (LENGTH_SHORT or LENGTH_LONG)
 */
fun Context.showToast(
    message: Any?,
    duration: Int = Toast.LENGTH_SHORT
) {
    when (message) {
        is String -> {
            if (message.isNotBlank()) {
                Toast.makeText(this, message, duration).show()
            }
        }
        is Int -> {
            try {
                Toast.makeText(this, getString(message), duration).show()
            } catch (_: Exception) {
                // Invalid resource ID, ignore
            }
        }
        else -> {
            // Null or unsupported type, do nothing
        }
    }
}