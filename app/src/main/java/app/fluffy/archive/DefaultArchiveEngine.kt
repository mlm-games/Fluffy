package app.fluffy.archive

import android.content.Context
import app.fluffy.io.SafIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

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
            Kind.ZIP -> listZip(open)
            Kind.SEVENZ -> listSevenZ(archiveName, open, password)
            Kind.TAR -> listTar(open)
            Kind.TARGZ -> listTar { GzipCompressorInputStream(open()) }
            Kind.TARBZ2 -> listTar { BZip2CompressorInputStream(open()) }
            Kind.TARXZ -> listTar { XZCompressorInputStream(open()) }
        }
    }

    override suspend fun extractAll(
        archiveName: String,
        open: () -> InputStream,
        create: (String, Boolean) -> OutputStream,
        password: CharArray?,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        when (infer(archiveName)) {
            Kind.ZIP -> extractZip(open, create, password, onProgress)
            Kind.SEVENZ -> extractSevenZ(archiveName, open, create, password, onProgress)
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
        val out = if (password != null) {
            ZipOutputStream(writeTarget(), password)
        } else {
            ZipOutputStream(writeTarget())
        }

        out.use { zout ->
            val lvl = when (compressionLevel.coerceIn(0, 9)) {
                0 -> CompressionLevel.NO_COMPRESSION
                in 1..3 -> CompressionLevel.FASTEST
                in 4..6 -> CompressionLevel.NORMAL
                in 7..8 -> CompressionLevel.MAXIMUM
                else -> CompressionLevel.ULTRA
            }

            var totalWritten = 0L
            val buf = ByteArray(128 * 1024)

            for ((name, supplier) in sources) {
                val params = ZipParameters().apply {
                    compressionMethod = CompressionMethod.DEFLATE
                    setCompressionLevel(lvl)
                    fileNameInZip = name
                }

                zout.putNextEntry(params)
                if (!name.endsWith("/")) {
                    supplier().use { input ->
                        var read = input.read(buf)
                        while (read > 0) {
                            zout.write(buf, 0, read)
                            totalWritten += read
                            onProgress(totalWritten, -1L)
                            read = input.read(buf)
                        }
                    }
                }
                zout.closeEntry()
            }
        }
    }

    // ZIP

    private fun listZip(open: () -> InputStream): ArchiveEngine.ListResult {
        ZipArchiveInputStream(open()).use { zis ->
            val entries = mutableListOf<ArchiveEngine.Entry>()
            var e: ZipArchiveEntry? = zis.nextEntry
            while (e != null) {
                entries += ArchiveEngine.Entry(
                    path = e.name,
                    isDir = e.isDirectory,
                    size = e.size,
                    time = e.lastModifiedDate?.time ?: 0L
                )
                e = zis.nextEntry
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
            val buf = ByteArray(128 * 1024)
            var entry = zin.nextEntry
            while (entry != null) {
                val name = entry.fileName
                if (entry.isDirectory || name.endsWith("/")) {
                    create("$name/", true).use { /* dir ensure */ }
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

    // 7Z

    private fun listSevenZ(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray?
    ): ArchiveEngine.ListResult {
        val tmp = stageSevenZTemp(archiveName, open)
        var encrypted = false
        val list = mutableListOf<ArchiveEngine.Entry>()
        try {
            SevenZFile(tmp, password).use { z ->
                var e: SevenZArchiveEntry? = z.nextEntry
                while (e != null) {
                    list += ArchiveEngine.Entry(
                        path = e.name,
                        isDir = e.isDirectory,
                        size = e.size,
                        time = e.lastModifiedDate?.time ?: 0L
                    )
                    e = z.nextEntry
                }
            }
        } catch (_: Throwable) {
            encrypted = true
        } finally {
            tmp.delete()
        }
        return ArchiveEngine.ListResult(list, encrypted = encrypted)
    }

    private fun extractSevenZ(
        archiveName: String,
        open: () -> InputStream,
        create: (String, Boolean) -> OutputStream,
        password: CharArray?,
        onProgress: (Long, Long) -> Unit
    ) {
        val tmp = stageSevenZTemp(archiveName, open)
        try {
            SevenZFile(tmp, password).use { z ->
                val buf = ByteArray(128 * 1024)
                var e: SevenZArchiveEntry? = z.nextEntry
                while (e != null) {
                    val name = e.name
                    if (e.isDirectory || name.endsWith("/")) {
                        create("$name/", true).use { /* ensure dir */ }
                    } else {
                        create(name, false).use { out ->
                            var r = z.read(buf)
                            while (r > 0) {
                                out.write(buf, 0, r)
                                r = z.read(buf)
                            }
                        }
                    }
                    e = z.nextEntry
                }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun stageSevenZTemp(archiveName: String, open: () -> InputStream): File {
        val safe = if (archiveName.lowercase(Locale.ROOT).endsWith(".7z")) archiveName else "in.7z"
        return io.stageToTemp(safe) { open() }
    }

    // TAR related

    private fun listTar(open: () -> InputStream): ArchiveEngine.ListResult {
        TarArchiveInputStream(open()).use { tin ->
            val entries = mutableListOf<ArchiveEngine.Entry>()
            var e: TarArchiveEntry? = tin.nextEntry
            while (e != null) {
                entries += ArchiveEngine.Entry(e.name, e.isDirectory, e.size, e.modTime?.time ?: 0L)
                e = tin.nextEntry
            }
            return ArchiveEngine.ListResult(entries, encrypted = false)
        }
    }

    private fun extractTar(
        open: () -> InputStream,
        create: (String, Boolean) -> OutputStream,
        onProgress: (Long, Long) -> Unit
    ) {
        TarArchiveInputStream(open()).use { tin ->
            val buf = ByteArray(128 * 1024)
            var e = tin.nextEntry
            var totalWritten = 0L
            while (e != null) {
                val name = e.name
                if (e.isDirectory || name.endsWith("/")) {
                    create("$name/", true).use { /* dir ensure */ }
                } else {
                    create(name, false).use { out ->
                        var r = tin.read(buf)
                        while (r > 0) {
                            out.write(buf, 0, r)
                            totalWritten += r
                            onProgress(totalWritten, -1L)
                            r = tin.read(buf)
                        }
                    }
                }
                e = tin.nextEntry
            }
        }
    }

    private enum class Kind { ZIP, SEVENZ, TAR, TARGZ, TARBZ2, TARXZ }

    private fun infer(name: String): Kind {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".7z") -> Kind.SEVENZ
            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") -> Kind.ZIP
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> Kind.TARGZ
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> Kind.TARBZ2
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> Kind.TARXZ
            n.endsWith(".tar") -> Kind.TAR
            else -> Kind.ZIP
        }
    }
}