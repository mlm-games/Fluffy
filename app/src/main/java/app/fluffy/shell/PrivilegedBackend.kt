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
                "(toybox ls -1Ap 2>/dev/null || ls -1Ap 2>/dev/null || busybox ls -1Ap 2>/dev/null) || true"
    }

    protected abstract fun spawnShellCommand(command: String): Process?
    protected abstract fun unavailableMessage(): String

    override fun list(path: String): List<Pair<String, Boolean>> {
        if (!isAvailable()) return emptyList()

        val process = spawnShellCommand(listCommand(path)) ?: return emptyList()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        process.destroy()

        return lines
            .filter { it.isNotBlank() }
            .map { raw ->
                val name = raw.removeSuffix("/")
                val isDir = raw.endsWith("/")
                name to isDir
            }
    }

    override fun openInput(path: String): InputStream {
        val process = spawnShellCommand("cat ${q(path)}")
            ?: throw IOException(unavailableMessage())

        return object : FilterInputStream(process.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    process.destroy()
                }
            }
        }
    }

    override fun openOutput(path: String): OutputStream {
        val parent = File(path).parent ?: "/"
        spawnShellCommand("mkdir -p ${q(parent)} && rm -f ${q(path)} && touch ${q(path)}")
            ?.waitFor()

        val process = spawnShellCommand("cat > ${q(path)}")
            ?: throw IOException(unavailableMessage())

        val output = BufferedOutputStream(process.outputStream, BUF)

        return object : OutputStream() {
            override fun write(b: Int) = output.write(b)

            override fun write(b: ByteArray, off: Int, len: Int) = output.write(b, off, len)

            override fun flush() = output.flush()

            override fun close() {
                try {
                    output.flush()
                    output.close()
                } finally {
                    runCatching { process.waitFor() }
                    process.destroy()
                }
            }
        }
    }

    override fun mkdirs(path: String): Boolean =
        (spawnShellCommand("mkdir -p ${q(path)}")?.waitFor() ?: 1) == 0

    override fun delete(path: String): Boolean =
        (spawnShellCommand("rm -rf ${q(path)}")?.waitFor() ?: 1) == 0

    override fun rename(oldPath: String, newPath: String): Boolean =
        (spawnShellCommand("mv ${q(oldPath)} ${q(newPath)}")?.waitFor() ?: 1) == 0
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

    override fun unavailableMessage(): String = "Shizuku not available"
}
