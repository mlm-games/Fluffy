package app.fluffy.shell

import android.os.Build
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

interface PrivilegedBackend {
    val scheme: String

    fun isAvailable(): Boolean
    fun list(path: String): List<Pair<String, Boolean>>
    fun openInput(path: String): InputStream
    fun openOutput(path: String): OutputStream
    fun mkdirs(path: String): Boolean
    fun delete(path: String): Boolean
    fun rename(oldPath: String, newPath: String): Boolean
    fun isDirectory(path: String): Boolean
    fun isFile(path: String): Boolean
    fun exists(path: String): Boolean
    fun isWritable(path: String): Boolean

    fun readBytes(path: String): ByteArray = openInput(path).use { it.readBytes() }

    fun execRead(command: String): String? = null

    fun writeBytes(path: String, bytes: ByteArray): Boolean {
        return runCatching {
            openOutput(path).use { it.write(bytes) }
        }.isSuccess
    }
}

abstract class BasePrivilegedBackend(
    protected val remountApprover: RemountApprover? = null,
) : PrivilegedBackend {
    private val remountedMounts = ConcurrentHashMap.newKeySet<String>()
    private val mountLocks = ConcurrentHashMap<String, Any>()

    protected companion object {
        const val BUF = 128 * 1024
        const val REMOUNT_TIMEOUT_MS = 10_000L
        const val PROCESS_POLL_MS = 50L

        fun q(path: String): String = "'${path.replace("'", "'\"'\"'")}'"

        fun listCommand(path: String): String =
            "cd ${q(path)} 2>/dev/null && " +
                "(toybox ls -1Ap 2>/dev/null || ls -1Ap 2>/dev/null || busybox ls -1Ap 2>/dev/null)"

        fun isDangerousTarget(path: String): Boolean {
            if (path.isBlank()) return true
            val canon = runCatching { File(path).canonicalPath }.getOrNull() ?: return true
            return canon == "/" || canon == "/system" || canon == "/vendor"
        }
    }

    protected abstract fun spawnShellCommand(command: String): Process?
    protected abstract fun unavailableMessage(): String
    protected abstract fun runTest(command: String): Boolean

    override fun isDirectory(path: String): Boolean = runCatching {
        if (!isAvailable() || path.isBlank()) false
        else runTest("test -d ${q(path)}")
    }.getOrDefault(false)

    override fun execRead(command: String): String? = runCatching {
        if (!isAvailable()) return null
        val proc = spawnShellCommand(command) ?: return null
        proc.inputStream.bufferedReader().use { it.readText() }.trim().ifBlank { null }
    }.getOrNull()

    override fun isFile(path: String): Boolean = runCatching {
        if (!isAvailable() || path.isBlank()) false
        else runTest("test -f ${q(path)}")
    }.getOrDefault(false)

    override fun isWritable(path: String): Boolean = runCatching {
        if (!isAvailable() || path.isBlank()) false
        else runTest("test -w ${q(path)}") && !isReadOnlyMount(path)
    }.getOrDefault(false)

    private data class Mount(val point: String, val readOnly: Boolean)

    private fun findMount(path: String): Mount? {
        val mounts = execRead("cat /proc/mounts") ?: return null
        val canon = runCatching { File(path).canonicalPath }.getOrNull() ?: path
        var best: Mount? = null
        for (line in mounts.lines()) {
            val parts = line.split(" ")
            if (parts.size < 4) continue
            val point = parts[1].replace("\\040", " ")
            val covers = canon == point || canon.startsWith(point.trimEnd('/') + "/")
            if (covers && (best == null || point.length > best.point.length)) {
                best = Mount(point, "ro" in parts[3].split(","))
            }
        }
        return best
    }

    private fun isReadOnlyMount(path: String): Boolean = findMount(path)?.readOnly == true

    private fun prepareWrite(path: String): Boolean {
        val approver = remountApprover ?: return true
        val mount = findMount(path)?.takeIf { it.readOnly }?.point ?: return true
        if (mount in remountedMounts) return true
        val lock = mountLocks.computeIfAbsent(mount) { Any() }
        return synchronized(lock) {
            if (mount in remountedMounts) return@synchronized true
            val approval = runBlocking { approver.approve(mount) }
            if (!approval.granted) return@synchronized false
            if (!remount(mount, path)) return@synchronized false
            if (approval.remember) runBlocking { approver.remember() }
            remountedMounts.add(mount)
            true
        }
    }

    private fun awaitExit(process: Process, timeoutMs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val exited = runCatching {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!exited) runCatching { process.destroyForcibly() }
            return exited
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            runCatching { process.exitValue() }.getOrNull()?.let { return true }
            if (System.currentTimeMillis() >= deadline) {
                runCatching { process.destroy() }
                return false
            }
            try {
                Thread.sleep(PROCESS_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                runCatching { process.destroy() }
                return false
            }
        }
    }

    private fun remount(mount: String, path: String): Boolean {
        val points = listOf(mount, "/", "/system_root").distinct()
        for (point in points) {
            for (command in listOf(
                "mount -o rw,remount ${q(point)}",
                "mount -o remount,rw ${q(point)}",
            )) {
                val process = spawnShellCommand(command) ?: continue
                awaitExit(process, REMOUNT_TIMEOUT_MS)
                if (!isReadOnlyMount(path)) return true
            }
        }
        return false
    }

    override fun exists(path: String): Boolean = runCatching {
        if (!isAvailable() || path.isBlank()) false
        else runTest("test -e ${q(path)}")
    }.getOrDefault(false)

    override fun list(path: String): List<Pair<String, Boolean>> {
        if (!isAvailable()) return emptyList()

        val process = spawnShellCommand(listCommand(path)) ?: return emptyList()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        runCatching { process.errorStream.bufferedReader().use { it.readText() } }
        val exit = runCatching { process.waitFor() }.getOrDefault(1)
        process.destroy()
        if (exit != 0) return emptyList()

        return lines
            .filter { it.isNotBlank() }
            .map { raw ->
                val name = raw.removeSuffix("/")
                val isDir = raw.endsWith("/")
                name to isDir
            }
    }

    override fun openInput(path: String): InputStream {
        if (!isFile(path)) throw IOException("Not a regular file: $path")
        val process = spawnShellCommand("cat ${q(path)}")
            ?: throw IOException(unavailableMessage())
        val errDrain = Thread({ runCatching { process.errorStream.bufferedReader().use { it.readText() } } }, "cat-stderr")
        errDrain.isDaemon = true
        errDrain.start()

        return object : FilterInputStream(process.inputStream) {
            private var bytesRead = 0L
            override fun read(): Int {
                val b = super.read()
                if (b != -1) bytesRead++
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) bytesRead += n
                return n
            }
            override fun close() {
                try {
                    super.close()
                } finally {
                    val exit = runCatching { process.waitFor() }.getOrDefault(0)
                    runCatching { errDrain.join(1000) }
                    process.destroy()
                    if (exit != 0 && bytesRead == 0L) {
                        throw IOException("Remote read failed (exit=$exit): $path")
                    }
                }
            }
        }
    }

    override fun openOutput(path: String): OutputStream {
        if (path.isBlank()) throw IOException("Empty path")
        if (isDangerousTarget(path)) throw IOException("Refusing to write to $path")
        if (!prepareWrite(path)) throw IOException("Read-only partition: $path")
        val parent = File(path).parent ?: "/"
        val mkExit = spawnShellCommand("mkdir -p ${q(parent)}")
            ?.waitFor() ?: 1
        if (mkExit != 0) throw IOException("Cannot create parent dir: $parent")
        val tmp = "$path.tmp.${android.os.Process.myPid()}.${System.nanoTime()}"
        val process = spawnShellCommand("cat > ${q(tmp)}")
            ?: throw IOException(unavailableMessage())

        val output = BufferedOutputStream(process.outputStream, BUF)

        return object : OutputStream() {
            private var closed = false
            override fun write(b: Int) = output.write(b)

            override fun write(b: ByteArray, off: Int, len: Int) = output.write(b, off, len)

            override fun flush() = output.flush()

            override fun close() {
                if (closed) return
                closed = true
                try {
                    output.flush()
                    output.close()
                } finally {
                    val exit = runCatching { process.waitFor() }.getOrDefault(1)
                    process.destroy()
                    if (exit != 0) {
                        runCatching { spawnShellCommand("rm -f ${q(tmp)}")?.waitFor() }
                        throw IOException("Remote write failed (exit=$exit): $path")
                    }
                    val mvExit = spawnShellCommand("mv -f ${q(tmp)} ${q(path)}")?.waitFor() ?: 1
                    if (mvExit != 0) {
                        runCatching { spawnShellCommand("rm -f ${q(tmp)}")?.waitFor() }
                        throw IOException("Remote commit failed: $path")
                    }
                }
            }
        }
    }

    override fun mkdirs(path: String): Boolean {
        if (isDangerousTarget(path)) return false
        if (!prepareWrite(path)) return false
        return (spawnShellCommand("mkdir -p ${q(path)}")?.waitFor() ?: 1) == 0
    }

    override fun delete(path: String): Boolean {
        if (isDangerousTarget(path)) return false
        if (!prepareWrite(path)) return false
        return (spawnShellCommand("rm -rf ${q(path)}")?.waitFor() ?: 1) == 0
    }

    override fun rename(oldPath: String, newPath: String): Boolean {
        if (oldPath.isBlank() || newPath.isBlank()) return false
        if (isDangerousTarget(oldPath) || isDangerousTarget(newPath)) return false
        if (!prepareWrite(oldPath) || !prepareWrite(newPath)) return false
        return (spawnShellCommand("mv -n ${q(oldPath)} ${q(newPath)}")?.waitFor() ?: 1) == 0
    }
}

class RootBackend(
    private val rootAccess: RootAccess,
    remountApprover: RemountApprover,
) : BasePrivilegedBackend(remountApprover) {
    override val scheme: String = "root"

    override fun isAvailable(): Boolean = rootAccess.isAvailable()

    override fun spawnShellCommand(command: String): Process? {
        if (!isAvailable()) return null
        return rootAccess.newProcess(command)
    }

    override fun runTest(command: String): Boolean {
        return (spawnShellCommand(command)?.waitFor() ?: 1) == 0
    }

    override fun unavailableMessage(): String = "Root not available"
}

class ShizukuBackend(
    private val shizukuAccess: ShizukuAccess,
) : BasePrivilegedBackend() {
    override val scheme: String = "shizuku"

    override fun isAvailable(): Boolean = shizukuAccess.isAvailable()

    override fun spawnShellCommand(command: String): Process? {
        if (!isAvailable()) return null
        return shizukuAccess.newProcess(arrayOf("sh", "-c", command))
    }

    override fun runTest(command: String): Boolean {
        return (spawnShellCommand(command)?.waitFor() ?: 1) == 0
    }

    override fun unavailableMessage(): String = "Shizuku not available"
}
