package app.fluffy.shell

import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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

    fun readBytes(path: String): ByteArray = openInput(path).use { it.readBytes() }

    fun writeBytes(path: String, bytes: ByteArray): Boolean {
        return runCatching {
            openOutput(path).use { it.write(bytes) }
        }.isSuccess
    }
}

abstract class BasePrivilegedBackend : PrivilegedBackend {
    protected companion object {
        const val BUF = 128 * 1024

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

    override fun isFile(path: String): Boolean = runCatching {
        if (!isAvailable() || path.isBlank()) false
        else runTest("test -f ${q(path)}")
    }.getOrDefault(false)

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

    override fun mkdirs(path: String): Boolean =
        (spawnShellCommand("mkdir -p ${q(path)}")?.waitFor() ?: 1) == 0

    override fun delete(path: String): Boolean {
        if (isDangerousTarget(path)) return false
        return (spawnShellCommand("rm -rf ${q(path)}")?.waitFor() ?: 1) == 0
    }

    override fun rename(oldPath: String, newPath: String): Boolean {
        if (oldPath.isBlank() || newPath.isBlank()) return false
        if (isDangerousTarget(oldPath)) return false
        return (spawnShellCommand("mv -n ${q(oldPath)} ${q(newPath)}")?.waitFor() ?: 1) == 0
    }
}

class RootBackend(
    private val rootAccess: RootAccess,
) : BasePrivilegedBackend() {
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
