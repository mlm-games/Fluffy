package app.fluffy.shell

import java.io.BufferedReader
import java.io.InputStreamReader

object RootAccess {
    fun isAvailable(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val ok = BufferedReader(InputStreamReader(p.inputStream)).readText().contains("uid=0")
        p.destroy()
        ok
    } catch (_: Throwable) { false }
}