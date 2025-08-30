package app.fluffy.helper

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.lang.reflect.Method


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

private const val STREAM_BUF = 64 * 1024

suspend fun installShizukuStream(file: File, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
    if (!Shizuku.pingBinder()) return@withContext false
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        if (!requestShizukuPermission()) return@withContext false
    }
    try {
        val size = file.length().coerceAtLeast(1L)
        val proc = shizukuNewProcess(arrayOf("cmd", "package", "install", "-r", "-S", size.toString()))
            ?: return@withContext false
        val ok = try {
            FileInputStream(file).use { fis -> proc.outputStream.use { os -> pipeWithProgress(fis, os, size, onProgress) } }
            withTimeoutOrNull(300_000) { proc.waitFor() } == 0
        } finally { runCatching { proc.destroy() } }
        ok
    } catch (_: Exception) { false }
}

suspend fun requestShizukuPermission(timeoutMs: Long = 15_000): Boolean {
    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
    val deferred = CompletableDeferred<Boolean>()
    val listener = Shizuku.OnRequestPermissionResultListener { _, res ->
        deferred.complete(res == PackageManager.PERMISSION_GRANTED)
    }
    Shizuku.addRequestPermissionResultListener(listener)
    Shizuku.requestPermission(0)
    val granted = runCatching { withTimeout(timeoutMs) { deferred.await() } }.getOrDefault(false)
    Shizuku.removeRequestPermissionResultListener(listener)
    return granted
}

@Suppress("UNCHECKED_CAST")
fun shizukuNewProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process? = try {
    val m: Method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
    m.isAccessible = true
    m.invoke(null, cmd, env, dir) as Process
} catch (_: Exception) { null }


private fun pipeWithProgress(src: FileInputStream, dst: OutputStream, total: Long, onProgress: (Float) -> Unit) {
    val buf = ByteArray(STREAM_BUF)
    var written = 0L
    var r = src.read(buf)
    while (r != -1) {
        dst.write(buf, 0, r)
        written += r
        onProgress((written.toFloat() / total.toFloat()).coerceIn(0f, 1f))
        r = src.read(buf)
    }
    dst.flush()
}

private suspend fun installRootStream(file: File, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
    try {
        val size = file.length().coerceAtLeast(1L)
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd package install -r -S $size"))
        FileInputStream(file).use { fis -> proc.outputStream.use { os -> pipeWithProgress(fis, os, size, onProgress) } }
        withTimeoutOrNull(300_000) { proc.waitFor() } == 0
    } catch (_: Exception) { false }
}