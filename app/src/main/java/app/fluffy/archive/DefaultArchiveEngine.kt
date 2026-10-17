@file:Suppress("DEPRECATION") // Using legacy SevenZFile for compatibility

package app.fluffy.archive

import android.content.Context
import app.fluffy.io.SafIo
import app.fluffy.util.AppLog
import app.fluffy.util.ArchiveTypes
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
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

class DefaultArchiveEngine(
    @Suppress("unused") // kept for possible future needs
    private val context: Context,
    private val io: SafIo
) : ArchiveEngine {

    override suspend fun list(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray?
    ): ArchiveEngine.ListResult = withContext(Dispatchers.IO) {
        when (ArchiveTypes.infer(archiveName) ?: return@withContext ArchiveEngine.ListResult(
            emptyList(), encrypted = false, error = "Unsupported archive type: $archiveName"
        )) {
            ArchiveTypes.Kind.ZIP   -> listZip(archiveName, open, password)
            ArchiveTypes.Kind.SEVENZ -> listSevenZ(archiveName, open, password)
            ArchiveTypes.Kind.TAR   -> listTar(open)
            ArchiveTypes.Kind.TARGZ -> listTar { GzipCompressorInputStream(open()) }
            ArchiveTypes.Kind.TARBZ2 -> listTar { BZip2CompressorInputStream(open()) }
            ArchiveTypes.Kind.TARXZ -> listTar { XZCompressorInputStream(open()) }
        }
    }

    override suspend fun extractAll(
        archiveName: String,
        open: () -> InputStream,
        create: (String, Boolean) -> OutputStream,
        password: CharArray?,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        when (ArchiveTypes.infer(archiveName)
            ?: throw IllegalArgumentException("Unsupported archive type: $archiveName")) {
            ArchiveTypes.Kind.ZIP   -> extractZip(open, create, password, onProgress)
            ArchiveTypes.Kind.SEVENZ -> extractSevenZ(archiveName, open, create, password, onProgress)
            ArchiveTypes.Kind.TAR   -> extractTar(open, create, onProgress)
            ArchiveTypes.Kind.TARGZ -> extractTar({ GzipCompressorInputStream(open()) }, create, onProgress)
            ArchiveTypes.Kind.TARBZ2 -> extractTar({ BZip2CompressorInputStream(open()) }, create, onProgress)
            ArchiveTypes.Kind.TARXZ -> extractTar({ XZCompressorInputStream(open()) }, create, onProgress)
        }
    }

    override suspend fun verify(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray?,
        onProgress: (Int, Int) -> Unit
    ): ArchiveEngine.VerifyResult = withContext(Dispatchers.IO) {
        val listed = list(archiveName, open, password)
        if (listed.error != null) {
            return@withContext ArchiveEngine.VerifyResult(false, 0, error = listed.error)
        }
        val totalFiles = listed.entries.count { !it.isDir }
        var checked = 0
        var current: String? = null
        try {
            extractAll(
                archiveName,
                open,
                create = { path, isDir ->
                    if (!isDir) {
                        current = path
                        checked++
                        onProgress(checked, totalFiles)
                    }
                    DiscardOutputStream
                },
                password = password
            )
            ArchiveEngine.VerifyResult(true, checked)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.w("ArchiveEngine", "verify failed: $archiveName", e)
            ArchiveEngine.VerifyResult(false, checked, current, e.message ?: e.toString())
        }
    }

    private object DiscardOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    override suspend fun createZip(
        sources: List<Pair<String, () -> InputStream>>,
        writeTarget: () -> OutputStream,
        options: ArchiveEngine.ZipCreateOptions,
        password: CharArray?,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val needsEncryption = password?.isNotEmpty() == true &&
            options.encryption != ArchiveEngine.ZipEncryption.NONE
        require(options.splitSizeBytes == null) {
            "Split creation must go through stageZipParts"
        }
        if (!needsEncryption && options.comment.isNullOrEmpty()) {
            streamZip(sources, writeTarget, options.compressionLevel, password, onProgress)
            Unit
        } else {
            val stage = stageSingleZip(sources, options, password, onProgress)
            try {
                stage.inputStream().use { input ->
                    writeTarget().use { output -> input.copyTo(output) }
                }
            } finally {
                runCatching { stage.delete() }
            }
            Unit
        }
    }

    override suspend fun stageZipParts(
        sources: List<Pair<String, () -> InputStream>>,
        options: ArchiveEngine.ZipCreateOptions,
        password: CharArray?,
        onProgress: (Long, Long) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        val splitLength = options.splitSizeBytes
            ?: throw IllegalArgumentException("splitSizeBytes required")
        require(splitLength >= 65536) { "Split size too small" }
        val tree = File(context.cacheDir, "fluffy_stage_${System.nanoTime()}").apply { mkdirs() }
        try {
            var staged = 0L
            val buf = ByteArray(128 * 1024)
            for ((rawName, supplier) in sources) {
                val name = sanitizeZipName(rawName)
                val target = File(tree, name)
                if (name.endsWith("/")) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    supplier().use { input ->
                        target.outputStream().use { output ->
                            var read = input.read(buf)
                            while (read != -1) {
                                if (read > 0) {
                                    output.write(buf, 0, read)
                                    staged += read
                                    onProgress(staged, -1L)
                                }
                                read = input.read(buf)
                            }
                        }
                    }
                }
            }
            val zipTarget = File(context.cacheDir, "fluffy_split_${System.nanoTime()}.zip")
            val zipFile = if (password?.isNotEmpty() == true &&
                options.encryption != ArchiveEngine.ZipEncryption.NONE
            ) {
                net.lingala.zip4j.ZipFile(zipTarget, password)
            } else {
                net.lingala.zip4j.ZipFile(zipTarget)
            }
            val params = baseZipParams(options, password)
            try {
                zipFile.createSplitZipFileFromFolder(tree, params, true, splitLength)
            } catch (e: Throwable) {
                runCatching { collectSplitParts(zipTarget).forEach { it.delete() } }
                throw e
            }
            if (!options.comment.isNullOrEmpty()) {
                runCatching {
                    val lastPart = collectSplitParts(zipTarget).lastOrNull()
                    if (lastPart != null) {
                        net.lingala.zip4j.ZipFile(lastPart).comment = options.comment
                    }
                }
            }
            collectSplitParts(zipTarget)
        } finally {
            runCatching { tree.deleteRecursively() }
        }
    }

    override suspend fun readComment(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray?
    ): String? = withContext(Dispatchers.IO) {
        val tmp = io.stageToTemp(archiveName) { open() }
        try {
            runCatching { net.lingala.zip4j.ZipFile(tmp).comment }.getOrNull()
        } finally {
            runCatching { tmp.delete() }
        }
    }

    override suspend fun updateZip(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray?,
        remove: List<String>,
        add: List<Pair<String, () -> InputStream>>,
        comment: String?,
        replaceComment: Boolean,
        writeBack: () -> OutputStream,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        updateZipImpl(archiveName, open, password, remove, add, comment, replaceComment, writeBack, onProgress)
    }

    private fun updateZipImpl(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray?,
        remove: List<String>,
        add: List<Pair<String, () -> InputStream>>,
        comment: String?,
        replaceComment: Boolean,
        writeBack: () -> OutputStream,
        onProgress: (Long, Long) -> Unit
    ) {
        val tmp = io.stageToTemp(archiveName) { open() }
        try {
            val zipFile = if (password?.isNotEmpty() == true) {
                net.lingala.zip4j.ZipFile(tmp, password)
            } else {
                net.lingala.zip4j.ZipFile(tmp)
            }
            if (remove.isNotEmpty()) {
                val names = remove.map { sanitizeZipName(it) }
                try {
                    zipFile.removeFiles(names)
                } catch (e: net.lingala.zip4j.exception.ZipException) {
                    AppLog.w("ArchiveEngine", "removeFiles raised for $names", e)
                }
                val remaining = zipFile.fileHeaders.map { it.fileName }.toSet()
                val stuck = names.filter { it in remaining }
                if (stuck.isNotEmpty()) {
                    throw java.io.IOException("Could not remove: ${stuck.joinToString()}")
                }
            }
            val existingEncryption: ZipParameters? =
                if (password?.isNotEmpty() == true && add.any { !sanitizeZipName(it.first).endsWith("/") }) {
                    runCatching {
                        zipFile.fileHeaders.firstOrNull { it.isEncrypted }?.let { header ->
                            ZipParameters().apply {
                                isEncryptFiles = true
                                encryptionMethod = header.encryptionMethod
                                if (header.encryptionMethod ==
                                    net.lingala.zip4j.model.enums.EncryptionMethod.AES
                                ) {
                                    aesKeyStrength = header.aesExtraDataRecord?.aesKeyStrength
                                        ?: net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                                }
                            }
                        }
                    }.getOrNull()
                } else {
                    null
                }
            var added = 0L
            val lvl = mapLevel(ArchiveEngine.ZipCreateOptions().compressionLevel)
            for ((rawName, supplier) in add) {
                val name = sanitizeZipName(rawName)
                val params = ZipParameters().apply {
                    compressionMethod = CompressionMethod.DEFLATE
                    setCompressionLevel(lvl)
                    fileNameInZip = name
                    existingEncryption?.let {
                        isEncryptFiles = true
                        encryptionMethod = it.encryptionMethod
                        if (it.encryptionMethod ==
                            net.lingala.zip4j.model.enums.EncryptionMethod.AES
                        ) {
                            aesKeyStrength = it.aesKeyStrength
                        }
                    }
                }
                if (name.endsWith("/")) {
                    zipFile.addStream(ByteArray(0).inputStream(), params)
                } else {
                    supplier().use { input ->
                        zipFile.addStream(progressStream(input) {
                            added += it
                            onProgress(added, -1L)
                        }, params)
                    }
                }
            }
            if (replaceComment) {
                zipFile.comment = comment.orEmpty()
            }
            tmp.inputStream().use { input ->
                writeBack().use { output -> input.copyTo(output) }
            }
            runCatching { tmp.delete() }
        } catch (e: Throwable) {
            AppLog.w(
                "ArchiveEngine",
                "update failed; original may be partially written, staged copy kept at $tmp",
                e
            )
            throw e
        }
    }

    private fun streamZip(
        sources: List<Pair<String, () -> InputStream>>,
        writeTarget: () -> OutputStream,
        compressionLevel: Int,
        password: CharArray?,
        onProgress: (Long, Long) -> Unit
    ) {
        val out = if (password?.isNotEmpty() == true) {
            ZipOutputStream(writeTarget(), password)
        } else {
            ZipOutputStream(writeTarget())
        }

        out.use { zout ->
            val lvl = mapLevel(compressionLevel)

            var totalWritten = 0L
            val buf = ByteArray(128 * 1024)

            for ((rawName, supplier) in sources) {
                val name = sanitizeZipName(rawName)
                val params = ZipParameters().apply {
                    compressionMethod = CompressionMethod.DEFLATE
                    setCompressionLevel(lvl)
                    fileNameInZip = name
                }

                zout.putNextEntry(params)
                if (!name.endsWith("/")) {
                    supplier().use { input ->
                        var read = input.read(buf)
                        while (read != -1) {
                            if (read > 0) {
                                zout.write(buf, 0, read)
                                totalWritten += read
                                onProgress(totalWritten, -1L)
                            }
                            read = input.read(buf)
                        }
                    }
                }
                zout.closeEntry()
            }
        }
    }

    private fun stageSingleZip(
        sources: List<Pair<String, () -> InputStream>>,
        options: ArchiveEngine.ZipCreateOptions,
        password: CharArray?,
        onProgress: (Long, Long) -> Unit
    ): File {
        val stage = File(context.cacheDir, "fluffy_zip_${System.nanoTime()}.zip")
        val zipFile = if (password?.isNotEmpty() == true &&
            options.encryption != ArchiveEngine.ZipEncryption.NONE
        ) {
            net.lingala.zip4j.ZipFile(stage, password)
        } else {
            net.lingala.zip4j.ZipFile(stage)
        }
        try {
            var totalWritten = 0L
            val buf = ByteArray(128 * 1024)
            for ((rawName, supplier) in sources) {
                val name = sanitizeZipName(rawName)
                val params = baseZipParams(options, password).apply { fileNameInZip = name }
                if (name.endsWith("/")) {
                    zipFile.addStream(ByteArray(0).inputStream(), params)
                } else {
                    supplier().use { input ->
                        zipFile.addStream(progressStream(input) {
                            totalWritten += it
                            onProgress(totalWritten, -1L)
                        }, params)
                    }
                }
            }
            if (!options.comment.isNullOrEmpty()) {
                zipFile.comment = options.comment
            }
        } catch (e: Throwable) {
            runCatching { stage.delete() }
            throw e
        }
        return stage
    }

    private fun progressStream(input: InputStream, onBytes: (Long) -> Unit): InputStream =
        object : InputStream() {
            override fun read(): Int {
                val b = input.read()
                if (b != -1) onBytes(1)
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val r = input.read(b, off, len)
                if (r > 0) onBytes(r.toLong())
                return r
            }
            override fun close() = input.close()
        }

    private fun mapLevel(compressionLevel: Int): CompressionLevel =
        when (compressionLevel.coerceIn(0, 9)) {
            0 -> CompressionLevel.NO_COMPRESSION
            in 1..3 -> CompressionLevel.FASTEST
            in 4..6 -> CompressionLevel.NORMAL
            in 7..8 -> CompressionLevel.MAXIMUM
            else -> CompressionLevel.ULTRA
        }

    private fun baseZipParams(
        options: ArchiveEngine.ZipCreateOptions,
        password: CharArray?
    ): ZipParameters = ZipParameters().apply {
        compressionMethod = CompressionMethod.DEFLATE
        setCompressionLevel(mapLevel(options.compressionLevel))
        setIncludeRootFolder(false)
        if (password?.isNotEmpty() == true &&
            options.encryption != ArchiveEngine.ZipEncryption.NONE
        ) {
            isEncryptFiles = true
            when (options.encryption) {
                ArchiveEngine.ZipEncryption.ZIP_CRYPTO -> {
                    encryptionMethod =
                        net.lingala.zip4j.model.enums.EncryptionMethod.ZIP_STANDARD
                }
                else -> {
                    encryptionMethod =
                        net.lingala.zip4j.model.enums.EncryptionMethod.AES
                    aesKeyStrength = when (options.encryption) {
                        ArchiveEngine.ZipEncryption.AES_128 ->
                            net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_128
                        ArchiveEngine.ZipEncryption.AES_192 ->
                            net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_192
                        else ->
                            net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                    }
                }
            }
        }
    }

    private fun collectSplitParts(zipTarget: File): List<File> {
        val dir = zipTarget.parentFile ?: return listOf(zipTarget)
        val stem = zipTarget.nameWithoutExtension
        return dir.listFiles { f ->
            f.isFile && (f.name == zipTarget.name ||
                f.name.matches(Regex("${Regex.escape(stem)}\\.z\\d{2,3}")))
        }?.sortedWith(compareBy(
            { if (it.name == zipTarget.name) 1 else 0 },
            { it.name }
        )) ?: listOf(zipTarget)
    }

    private fun sanitizeZipName(name: String): String {
        var n = name.replace('\\', '/').trim().trimStart('/')
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        n = parts.joinToString("/")
        if (n.isBlank()) return "item"
        if (name.endsWith("/") && !n.endsWith("/")) n += "/"
        return n
    }

    private fun listZip(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray?
    ): ArchiveEngine.ListResult {
        return try {
            val entries = mutableListOf<ArchiveEngine.Entry>()
            var anyEncrypted = false
            ZipInputStream(open(), password).use { zin ->
                var header = zin.nextEntry
                while (header != null) {
                    entries += ArchiveEngine.Entry(
                        path = header.fileName,
                        isDir = header.isDirectory,
                        size = header.uncompressedSize,
                        time = header.lastModifiedTime
                    )
                    anyEncrypted = anyEncrypted || header.isEncrypted
                    header = zin.nextEntry
                }
            }
            ArchiveEngine.ListResult(entries, encrypted = anyEncrypted)
        } catch (e: Throwable) {
            AppLog.w("ArchiveEngine", "listZip failed: $archiveName", e)
            val msg = (e.message ?: e.toString())
            val lower = msg.lowercase(Locale.ROOT)
            val isEncryptedHint = lower.contains("password") || lower.contains("encrypt") ||
                e::class.java.simpleName.lowercase(Locale.ROOT).contains("password")
            val needsPassword = isEncryptedHint && (password == null || password.isEmpty())
            ArchiveEngine.ListResult(
                emptyList(),
                encrypted = isEncryptedHint,
                error = if (needsPassword) null else msg
            )
        }
    }


    // ZIP
    private fun extractZip(
        open: () -> InputStream,
        create: (String, Boolean) -> OutputStream,
        password: CharArray?,
        onProgress: (Long, Long) -> Unit
    ) {
        ZipInputStream(open(), password).use { zin ->
            val buf = ByteArray(128 * 1024)
            var done = 0L
            var entry = zin.nextEntry
            while (entry != null) {
                val name = entry.fileName
                if (entry.isDirectory || name.endsWith("/")) {
                    create("$name/", true).use { /* ensure dir */ }
                } else {
                    create(name, false).use { out ->
                        var r = zin.read(buf)
                        while (r != -1) {
                            if (r > 0) {
                                out.write(buf, 0, r)
                                done += r
                                onProgress(done, -1L)
                            }
                            r = zin.read(buf)
                        }
                    }
                }
                entry = zin.nextEntry
            }
        }
    }

    // 7Z
    // 7Z
    private fun listSevenZ(
        archiveName: String,
        open: () -> InputStream,
        password: CharArray?
    ): ArchiveEngine.ListResult {
        val tmp = stageSevenZTemp(archiveName, open)
        val list = mutableListOf<ArchiveEngine.Entry>()
        var encrypted = false

        try {
            val sevenZ = if (password?.isNotEmpty() == true) SevenZFile(tmp, password) else SevenZFile(tmp)
            sevenZ.use { z ->
                var e: SevenZArchiveEntry? = z.nextEntry
                while (e != null) {
                    // Collect metadata only; DO NOT read entry data here.
                    list += ArchiveEngine.Entry(
                        path = e.name,
                        isDir = e.isDirectory,
                        size = if (e.size >= 0) e.size else 0L,
                        time = e.lastModifiedDate?.time ?: 0L
                    )

                    // Best-effort encrypted flag: mark true if any entry uses AES
                    val methods = e.contentMethods
                    if (methods != null) {
                        // Avoid extra imports; check method name
                        if (methods.any { cfg -> cfg.method.name.contains("AES", ignoreCase = true) }) {
                            encrypted = true
                        }
                    }

                    e = z.nextEntry
                }
            }
        } catch (t: Throwable) {
            // If opening/listing failed with a password/encryption hint, reflect that
            val msg = (t.message ?: "").lowercase(Locale.ROOT)
            val type = t::class.java.simpleName.lowercase(Locale.ROOT)
            encrypted = encrypted || type.contains("password") ||
                    type.contains("crypt") ||
                    msg.contains("password") ||
                    msg.contains("encrypted") ||
                    msg.contains("wrong pass")
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
            val sevenZ = if (password?.isNotEmpty() == true) SevenZFile(tmp, password) else SevenZFile(tmp)
            sevenZ.use { z ->
                val buf = ByteArray(128 * 1024)
                var done = 0L
                var e: SevenZArchiveEntry? = z.nextEntry
                while (e != null) {
                    val name = e.name
                    if (e.isDirectory || name.endsWith("/")) {
                        create("$name/", true).use { /* ensure dir */ }
                    } else {
                        create(name, false).use { out ->
                            var r = z.read(buf)
                            while (r != -1) {
                                if (r > 0) {
                                    out.write(buf, 0, r)
                                    done += r
                                    onProgress(done, -1L)
                                }
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
            var skippedLinks = 0
            while (e != null) {
                val name = e.name
                if (e.isSymbolicLink || e.isLink) {
                    skippedLinks++
                    AppLog.w("ArchiveEngine", "Skipping link entry: $name -> ${e.linkName}")
                } else if (e.isDirectory || name.endsWith("/")) {
                    create("$name/", true).use { /* dir ensure */ }
                } else {
                    create(name, false).use { out ->
                        var r = tin.read(buf)
                        while (r != -1) {
                            if (r > 0) {
                                out.write(buf, 0, r)
                                totalWritten += r
                                onProgress(totalWritten, -1L)
                            }
                            r = tin.read(buf)
                        }
                    }
                }
                e = tin.nextEntry
            }
        }
    }
}
