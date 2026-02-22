package app.fluffy.io

import app.fluffy.shell.RootAccess
import app.fluffy.shell.ShizukuAccess
import java.io.*

object ShellIo {
    private const val BUF = 128 * 1024

    private fun q(path: String) = "'${path.replace("'", "'\"'\"'")}'"

    private fun suProc(cmd: String): Process {
        // Prefer global namespace (for viewing /data/data folders for other apps)
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "--mount-master", "-c", cmd))
        } catch (_: Exception) {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", cmd))
            } catch (_: Exception) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            }
        }
    }

    private fun shizukuProc(vararg args: String): Process? =
        ShizukuAccess.newProcess(args.toList().toTypedArray())

    // ls by cd into directory (follows symlinks), try toybox/ls/busybox, silence stderr.
    private fun listCmd(path: String): String =
        "cd ${q(path)} 2>/dev/null && (toybox ls -1Ap 2>/dev/null || ls -1Ap 2>/dev/null || busybox ls -1Ap 2>/dev/null) || true"

    private fun parseLsLines(lines: List<String>): List<Pair<String, Boolean>> =
        lines.filter { it.isNotBlank() }.map { raw ->
            val name = raw.removeSuffix("/")
            val isDir = raw.endsWith("/")
            name to isDir
        }

    fun listRoot(path: String): List<Pair<String, Boolean>> {
        if (!RootAccess.isAvailable()) return emptyList()
        val p = suProc(listCmd(path))
        val lines = p.inputStream.bufferedReader().readLines()
        p.destroy()
        return parseLsLines(lines)
    }

    fun listShizuku(path: String): List<Pair<String, Boolean>> {
        if (!ShizukuAccess.isAvailable()) return emptyList()
        val p = shizukuProc("sh", "-c", listCmd(path)) ?: return emptyList()
        val lines = p.inputStream.bufferedReader().readLines()
        p.destroy()
        return parseLsLines(lines)
    }

    fun openInRoot(path: String): InputStream {
        val p = suProc("cat ${q(path)}")
        return object : FilterInputStream(p.inputStream) {
            override fun close() { try { super.close() } finally { p.destroy() } }
        }
    }

    fun openInShizuku(path: String): InputStream {
        val p = shizukuProc("sh", "-c", "cat ${q(path)}") ?: throw IOException("Shizuku not available")
        return object : FilterInputStream(p.inputStream) {
            override fun close() { try { super.close() } finally { p.destroy() } }
        }
    }

    fun openOutRoot(path: String): OutputStream {
        val parent = File(path).parent ?: "/"
        suProc("mkdir -p ${q(parent)} && rm -f ${q(path)} && touch ${q(path)}").waitFor()
        val p = suProc("sh -c 'cat > ${q(path)}'")
        val os = BufferedOutputStream(p.outputStream, BUF)
        return object : OutputStream() {
            override fun write(b: Int) = os.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = os.write(b, off, len)
            override fun flush() = os.flush()
            override fun close() {
                try { os.flush(); os.close() } finally {
                    runCatching { p.waitFor() }; p.destroy()
                }
            }
        }
    }

    fun openOutShizuku(path: String): OutputStream {
        val parent = File(path).parent ?: "/"
        shizukuProc("sh", "-c", "mkdir -p ${q(parent)} && rm -f ${q(path)} && touch ${q(path)}")?.waitFor()
        val p = shizukuProc("sh", "-c", "cat > ${q(path)}") ?: throw IOException("Shizuku not available")
        val os = BufferedOutputStream(p.outputStream, BUF)
        return object : OutputStream() {
            override fun write(b: Int) = os.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = os.write(b, off, len)
            override fun flush() = os.flush()
            override fun close() {
                try { os.flush(); os.close() } finally {
                    runCatching { p.waitFor() }; p.destroy()
                }
            }
        }
    }

    fun mkdirsRoot(path: String): Boolean = suProc("mkdir -p ${q(path)}").waitFor() == 0
    fun mkdirsShizuku(path: String): Boolean = (shizukuProc("sh", "-c", "mkdir -p ${q(path)}")?.waitFor() ?: 1) == 0

    fun deleteRoot(path: String): Boolean = suProc("rm -rf ${q(path)}").waitFor() == 0
    fun deleteShizuku(path: String): Boolean = (shizukuProc("sh", "-c", "rm -rf ${q(path)}")?.waitFor() ?: 1) == 0

    fun renameRoot(oldPath: String, newPath: String): Boolean = suProc("mv ${q(oldPath)} ${q(newPath)}").waitFor() == 0
    fun renameShizuku(oldPath: String, newPath: String): Boolean =
        (shizukuProc("sh", "-c", "mv ${q(oldPath)} ${q(newPath)}")?.waitFor() ?: 1) == 0

    // for byte (text editor) operations
    fun readBytesRoot(path: String): ByteArray = openInRoot(path).use { it.readBytes() }

    fun readBytesShizuku(path: String): ByteArray = openInShizuku(path).use { it.readBytes() }

    fun writeBytesRoot(path: String, bytes: ByteArray): Boolean {
        return try {
            openOutRoot(path).use { it.write(bytes) }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false }
    }

    fun writeBytesShizuku(path: String, bytes: ByteArray): Boolean {
        return try {
            openOutShizuku(path).use { it.write(bytes) }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}