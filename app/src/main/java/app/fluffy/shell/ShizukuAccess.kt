package app.fluffy.shell

import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

object ShizukuAccess {
    fun isAvailable(): Boolean = Shizuku.pingBinder()

    suspend fun ensurePermission(timeoutMs: Long = 15_000): Boolean = withContext(Dispatchers.Main) {
        if (!isAvailable()) return@withContext false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return@withContext true
        val deferred = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { _, res ->
            deferred.complete(res == PackageManager.PERMISSION_GRANTED)
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(0)
        val granted = runCatching { withTimeout(timeoutMs) { deferred.await() } }.getOrDefault(false)
        Shizuku.removeRequestPermissionResultListener(listener)
        granted
    }

    fun newProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process? = try {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        m.invoke(null, cmd, env, dir) as Process
    } catch (_: Throwable) { null }
}