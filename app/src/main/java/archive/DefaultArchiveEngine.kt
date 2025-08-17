package archive

import android.content.Context
import app.fluffy.archive.ArchiveEngine
import app.fluffy.io.SafIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.collections.plusAssign

class DefaultArchiveEngine(
    private val context: Context,
    private val io: SafIo
) : ArchiveEngine {

    override suspend fun list(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray?
    ): ArchiveEngine.ListResult = withContext(Dispatchers.IO) {
        when (infer(archiveName)) {
            Kind.ZIP -> listZip(open, password)
            Kind.TAR -> listTar(open)
            Kind.TARGZ -> listTar { GzipCompressorInputStream(open()) }
            Kind.TARBZ2 -> listTar { BZip2CompressorInputStream(open()) }
            Kind.TARXZ -> listTar { XZCompressorInputStream(open()) }
        }
    }

    override suspend fun extractAll(
        archiveName: String,
        open: () -> InputStream,
        create: (pathInArchive: String, isDir: Boolean) -> OutputStream,
        password: CharArray?,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        when (infer(archiveName)) {
            Kind.ZIP -> extractZip(open, create, password, onProgress)
            Kind.TAR -> extractTar(open, create, onProgress)
            Kind.TARGZ -> extractTar({ GzipCompressorInputStream(open()) }, create, onProgress)
            Kind.TARBZ2 -> extractTar({ BZip2CompressorInputStream(open()) }, create, onProgress)
            Kind.TARXZ -> extractTar({ XZCompressorInputStream(open()) }, create, onProgress)
        }
    }

    override suspend fun createZip(
        sources: List<Pair<String, () -> InputStream>>,
        writeTarget: () -> OutputStream,
        compressionLevel: Int,
        password: CharArray?,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        ZipOutputStream(writeTarget()).use { zout ->
            val params = ZipParameters().apply {
                compressionMethod = CompressionMethod.DEFLATE
                this.compressionLevel = when (compressionLevel.coerceIn(0, 9)) {
                    0 -> CompressionLevel.NO_COMPRESSION
                    in 1..3 -> CompressionLevel.FASTEST
                    in 4..6 -> CompressionLevel.NORMAL
                    in 7..8 -> CompressionLevel.MAXIMUM
                    else -> CompressionLevel.ULTRA
                }
                if (password != null) {
                    isEncryptFiles = true
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
            }
            for ((name, open) in sources) {
                val isDir = name.endsWith("/")
                val p = params.clone() as ZipParameters
                p.fileNameInZip = name
                zout.putNextEntry(p)
                if (!isDir) {
                    open().use { it.copyTo(zout) }
                }
                zout.closeEntry()
            }
        }
    }

    // --- Internals ---

    private fun listZip(open: () -> InputStream, password: CharArray?): ArchiveEngine.ListResult {
        // Use commons-compress for plain listing (works even for many zips; encrypted zips will still show names)
        val zis = ZipArchiveInputStream(open())
        val entries = mutableListOf<ArchiveEngine.Entry>()
        var e: ZipArchiveEntry? = zis.nextZipEntry
        while (e != null) {
            entries plusAssign ArchiveEngine.Entry(
                path = e.name,
                isDir = e.isDirectory,
                size = e.size,
                time = e.lastModifiedDate?.time ?: 0L
            )
            e = zis.nextZipEntry
        }
        zis.close()
        // Encrypted detection is best-effort; ZipArchiveInputStream doesn't expose it. Mark false.
        return ArchiveEngine.ListResult(entries, encrypted = false)
    }

    private fun listTar(open: () -> InputStream): ArchiveEngine.ListResult {
        TarArchiveInputStream(open()).use { tin ->
            val entries = mutableListOf<ArchiveEngine.Entry>()
            var e: TarArchiveEntry? = tin.nextTarEntry
            while (e != null) {
                entries plusAssign ArchiveEngine.Entry(e.name, e.isDirectory, e.size, e.modTime?.time ?: 0L)
                e = tin.nextTarEntry
            }
            return ArchiveEngine.ListResult(entries, encrypted = false)
        }
    }

    private fun extractZip(
        open: () -> InputStream,
        create: (String, Boolean) -> OutputStream,
        password: CharArray?,
        onProgress: (Long, Long) -> Unit
    ) {
        ZipInputStream(open(), password).use { zin ->
            var entry = zin.nextEntry
            val buf = ByteArray(128 * 1024)
            while (entry != null) {
                val name = entry.fileName
                if (entry.isDirectory) {
                    // cause directory to be created by opening and closing a dummy stream
                    create("$name/", true).use { /* no-op */ }
                } else {
                    create(name, false).use { out ->
                        var r = zin.read(buf)
                        while (r > 0) {
                            out.write(buf, 0, r)
                            r = zin.read(buf)
                        }
                    }
                }
                entry = zin.nextEntry
            }
        }
    }

    private fun extractTar(
        open: () -> InputStream,
        create: (String, Boolean) -> OutputStream,
        onProgress: (Long, Long) -> Unit
    ) {
        TarArchiveInputStream(open()).use { tin ->
            val buf = ByteArray(128 * 1024)
            var e = tin.nextTarEntry
            while (e != null) {
                val name = e.name
                if (e.isDirectory) {
                    create("$name/", true).use { /* no-op */ }
                } else {
                    create(name, false).use { out ->
                        var r = tin.read(buf)
                        while (r > 0) {
                            out.write(buf, 0, r)
                            r = tin.read(buf)
                        }
                    }
                }
                e = tin.nextTarEntry
            }
        }
    }

    private enum class Kind { ZIP, TAR, TARGZ, TARBZ2, TARXZ }

    private fun infer(name: String): Kind {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".zip") -> Kind.ZIP
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> Kind.TARGZ
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> Kind.TARBZ2
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> Kind.TARXZ
            n.endsWith(".tar") -> Kind.TAR
            else -> Kind.ZIP // default; many cases are zip
        }
    }
}