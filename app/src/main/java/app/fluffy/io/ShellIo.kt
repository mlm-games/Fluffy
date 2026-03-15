package app.fluffy.io

import app.fluffy.shell.RootBackend
import app.fluffy.shell.ShizukuBackend
import java.io.InputStream
import java.io.OutputStream

class ShellIo(
    private val rootBackend: RootBackend,
    private val shizukuBackend: ShizukuBackend,
) {
    fun listRoot(path: String): List<Pair<String, Boolean>> = rootBackend.list(path)
    fun listShizuku(path: String): List<Pair<String, Boolean>> = shizukuBackend.list(path)

    fun openInRoot(path: String): InputStream = rootBackend.openInput(path)
    fun openInShizuku(path: String): InputStream = shizukuBackend.openInput(path)

    fun openOutRoot(path: String): OutputStream = rootBackend.openOutput(path)
    fun openOutShizuku(path: String): OutputStream = shizukuBackend.openOutput(path)

    fun mkdirsRoot(path: String): Boolean = rootBackend.mkdirs(path)
    fun mkdirsShizuku(path: String): Boolean = shizukuBackend.mkdirs(path)

    fun deleteRoot(path: String): Boolean = rootBackend.delete(path)
    fun deleteShizuku(path: String): Boolean = shizukuBackend.delete(path)

    fun renameRoot(oldPath: String, newPath: String): Boolean = rootBackend.rename(oldPath, newPath)
    fun renameShizuku(oldPath: String, newPath: String): Boolean =
        shizukuBackend.rename(oldPath, newPath)

    fun readBytesRoot(path: String): ByteArray = rootBackend.readBytes(path)
    fun readBytesShizuku(path: String): ByteArray = shizukuBackend.readBytes(path)

    fun writeBytesRoot(path: String, bytes: ByteArray): Boolean = rootBackend.writeBytes(path, bytes)
    fun writeBytesShizuku(path: String, bytes: ByteArray): Boolean =
        shizukuBackend.writeBytes(path, bytes)
}
