package app.fluffy.shell

import java.io.BufferedReader
import java.io.InputStreamReader

class RootAccess {
    @Volatile
    private var cachedAvailability: Boolean? = null

    fun isAvailable(forceRefresh: Boolean = false): Boolean {
        if (forceRefresh) cachedAvailability = null
        cachedAvailability?.let { return it }

        return synchronized(this) {
            cachedAvailability?.let { return@synchronized it }
            probeAvailability().also { cachedAvailability = it }
        }
    }

    fun newProcess(command: String): Process {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "--mount-master", "-c", command))
        } catch (_: Exception) {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", command))
            } catch (_: Exception) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            }
        }
    }

    private fun probeAvailability(): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val ok = BufferedReader(InputStreamReader(process.inputStream))
            .use { it.readText().contains("uid=0") }
        process.destroy()
        ok
    } catch (_: Throwable) {
        false
    }

    companion object {
        @Volatile
        private var instance: RootAccess? = null

        fun getInstance(): RootAccess {
            return instance ?: synchronized(this) {
                instance ?: RootAccess().also { instance = it }
            }
        }

        fun isAvailable(forceRefresh: Boolean = false): Boolean = getInstance().isAvailable(forceRefresh)
    }
}
