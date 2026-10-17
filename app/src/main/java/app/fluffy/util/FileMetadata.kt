package app.fluffy.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.system.Os
import androidx.exifinterface.media.ExifInterface
import app.fluffy.archive.ArchiveEngine
import app.fluffy.io.DocumentController
import app.fluffy.io.SafIo
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MetaKey {
    DIMENSIONS, CAMERA_MAKE, CAMERA_MODEL, EXPOSURE_TIME, F_NUMBER, ISO, FOCAL_LENGTH,
    DATE_TAKEN, GPS, DURATION, BITRATE, RESOLUTION, FRAME_RATE,
    ARTIST, TITLE, ALBUM, GENRE, YEAR,
    APP_LABEL, APP_VERSION, ENTRIES, SIZE_UNCOMPRESSED,
    PERMISSIONS, OWNER, GROUP
}

data class MetaRow(val key: MetaKey, val value: String)

object FileMetadata {
    private const val TAG_KEYS_INT_API = 37

    private val imageExts = listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp", ".gif", ".dng")
    private val audioExts = listOf(".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav", ".wma", ".amr")
    private val videoExts = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".3gp", ".flv", ".ts", ".m4v")

    suspend fun collect(
        context: Context,
        uri: Uri,
        displayName: String,
        mime: String?,
        archive: ArchiveEngine,
        safIo: SafIo
    ): List<MetaRow> = withContext(Dispatchers.IO) {
        val name = displayName
        val lower = name.lowercase(Locale.ROOT)
        val type = mime?.lowercase(Locale.ROOT).orEmpty()

        val rows = mutableListOf<MetaRow>()
        when {
            lower.endsWith(".apk") -> rows += apkRows(context, uri)
            ArchiveTypes.isArchive(name) -> rows += archiveRows(uri, name, archive)
            type.startsWith("image/") || imageExts.any { lower.endsWith(it) } ->
                rows += imageRows(context, uri)
            type.startsWith("audio/") || type.startsWith("video/") ||
                audioExts.any { lower.endsWith(it) } || videoExts.any { lower.endsWith(it) } ->
                rows += mediaRows(context, uri)
        }
        rows + runCatching { permissionRows(uri, safIo) }
            .onFailure { AppLog.d("FileMetadata", "permission rows failed for $uri", it) }
            .getOrDefault(emptyList())
    }

    private suspend fun permissionRows(uri: Uri, safIo: SafIo): List<MetaRow> {
        val shell = runCatching { safIo.statPermissions(uri) }.getOrNull()
        if (shell != null) {
            return buildList {
                add(MetaRow(MetaKey.PERMISSIONS, shell.mode))
                add(MetaRow(MetaKey.OWNER, shell.owner))
                add(MetaRow(MetaKey.GROUP, shell.group))
            }
        }
        val file = DocumentController.directFile(uri) ?: return emptyList()
        return statRows(file)
    }

    private fun statRows(file: File): List<MetaRow> {
        val st = runCatching { Os.stat(file.absolutePath) }.getOrNull() ?: return emptyList()
        val bits = st.st_mode and 0x1FF
        val rwx = buildString {
            val chars = charArrayOf('r', 'w', 'x')
            for (i in 0 until 9) {
                append(if (bits and (1 shl (8 - i)) != 0) chars[i % 3] else '-')
            }
        }
        val owner = userName(st.st_uid) ?: st.st_uid.toString()
        val group = groupName(st.st_gid) ?: st.st_gid.toString()
        return listOf(
            MetaRow(MetaKey.PERMISSIONS, "$rwx (${Integer.toOctalString(bits)})"),
            MetaRow(MetaKey.OWNER, owner),
            MetaRow(MetaKey.GROUP, group)
        )
    }

    private val userNames: Map<Int, String> by lazy { parseIdFile("/system/etc/passwd", 2) }
    private val groupNames: Map<Int, String> by lazy { parseIdFile("/system/etc/group", 2) }

    private fun userName(uid: Int): String? = userNames[uid]

    private fun groupName(gid: Int): String? = groupNames[gid]

    private fun parseIdFile(path: String, idIndex: Int): Map<Int, String> = runCatching {
        val out = mutableMapOf<Int, String>()
        File(path).forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine
            val parts = line.split(':')
            if (parts.size <= idIndex) return@forEachLine
            val id = parts[idIndex].trim().toIntOrNull() ?: return@forEachLine
            out.putIfAbsent(id, parts[0])
        }
        out
    }.getOrDefault(emptyMap())

    private fun imageRows(context: Context, uri: Uri): List<MetaRow> {
        val file = DocumentController.directFile(uri) ?: return emptyList()
        return runCatching {
            val exif = ExifInterface(file)
            buildList {
                val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                if (w > 0 && h > 0) add(MetaRow(MetaKey.DIMENSIONS, "$w × $h"))
                exif.getAttribute(ExifInterface.TAG_MAKE)?.takeIf { it.isNotBlank() }
                    ?.let { add(MetaRow(MetaKey.CAMERA_MAKE, it)) }
                exif.getAttribute(ExifInterface.TAG_MODEL)?.takeIf { it.isNotBlank() }
                    ?.let { add(MetaRow(MetaKey.CAMERA_MODEL, it)) }
                exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.takeIf { it.isNotBlank() }
                    ?.let { add(MetaRow(MetaKey.F_NUMBER, "f/$it")) }
                exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0).takeIf { it > 0.0 }
                    ?.let { add(MetaRow(MetaKey.EXPOSURE_TIME, formatExposure(it))) }
                exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }
                    ?.let { add(MetaRow(MetaKey.ISO, "ISO $it")) }
                exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0.0 }
                    ?.let { add(MetaRow(MetaKey.FOCAL_LENGTH, "${trimZeros(it)} mm")) }
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.takeIf { it.isNotBlank() }
                    ?.let { add(MetaRow(MetaKey.DATE_TAKEN, it.trim())) }
                gpsOf(exif)?.let { add(MetaRow(MetaKey.GPS, it)) }
            }
        }.getOrDefault(emptyList())
    }

    private fun gpsOf(exif: ExifInterface): String? {
        val lat = exif.getLatLong()
        return lat?.let { String.format(Locale.ROOT, "%.5f, %.5f", it[0], it[1]) }
    }

    private fun trimZeros(value: Double): String {
        val rounded = Math.round(value * 100.0) / 100.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString()
        else String.format(Locale.ROOT, "%.2f", rounded)
    }

    private fun formatExposure(seconds: Double): String = when {
        seconds >= 1.0 -> String.format(Locale.ROOT, "%.1f s", seconds)
        seconds >= 0.1 -> String.format(Locale.ROOT, "1/%.0f s", 1.0 / seconds)
        else -> String.format(Locale.ROOT, "1/%d s", Math.round(1.0 / seconds))
    }

    private fun mediaRows(context: Context, uri: Uri): List<MetaRow> {
        val file = DocumentController.directFile(uri) ?: return emptyList()
        return formatRows(file) + tagRows(file)
    }

    private fun formatRows(file: File): List<MetaRow> = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var durationUs = 0L
            var bitrate = 0
            var width = 0
            var height = 0
            var frameRate = 0
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    width = format.intOrZero(MediaFormat.KEY_WIDTH)
                    height = format.intOrZero(MediaFormat.KEY_HEIGHT)
                    frameRate = format.intOrZero(MediaFormat.KEY_FRAME_RATE)
                    if (bitrate == 0) bitrate = format.intOrZero(MediaFormat.KEY_BIT_RATE)
                } else if (bitrate == 0) {
                    bitrate = format.intOrZero(MediaFormat.KEY_BIT_RATE)
                }
                if (durationUs == 0L) durationUs = format.longOrZero(MediaFormat.KEY_DURATION)
            }
            buildList {
                if (durationUs > 0) add(MetaRow(MetaKey.DURATION, formatDuration(durationUs / 1000)))
                if (bitrate > 0) add(MetaRow(MetaKey.BITRATE, "${bitrate / 1000} kbps"))
                if (width > 0 && height > 0) add(MetaRow(MetaKey.RESOLUTION, "$width × $height"))
                if (frameRate > 0) add(MetaRow(MetaKey.FRAME_RATE, "$frameRate fps"))
            }
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrElse { e ->
        AppLog.d("FileMetadata", "media format unavailable for $file", e)
        emptyList()
    }

    private fun tagRows(file: File): List<MetaRow> {
        if (Build.VERSION.SDK_INT < TAG_KEYS_INT_API) return emptyList()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            buildList {
                retriever.meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { add(MetaRow(MetaKey.ARTIST, it)) }
                retriever.meta(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { add(MetaRow(MetaKey.TITLE, it)) }
                retriever.meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { add(MetaRow(MetaKey.ALBUM, it)) }
                retriever.meta(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { add(MetaRow(MetaKey.GENRE, it)) }
                retriever.meta(MediaMetadataRetriever.METADATA_KEY_YEAR)?.let { add(MetaRow(MetaKey.YEAR, it)) }
            }
        } catch (e: Exception) {
            AppLog.d("FileMetadata", "media tags unavailable for $file", e)
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.meta(key: Int): String? =
        runCatching { extractMetadata(key) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    private fun MediaFormat.longOrZero(key: String): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(0L) else 0L

    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    private fun apkRows(context: Context, uri: Uri): List<MetaRow> {
        val file = DocumentController.directFile(uri) ?: return emptyList()
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return emptyList()
        return runCatching {
            buildList {
                info.applicationInfo?.let {
                    add(MetaRow(MetaKey.APP_LABEL, context.packageManager.getApplicationLabel(it).toString()))
                }
                info.versionName?.takeIf { it.isNotBlank() }?.let { add(MetaRow(MetaKey.APP_VERSION, it)) }
                val code = if (Build.VERSION.SDK_INT >= 33) info.longVersionCode else info.versionCode.toLong()
                if (code > 0) add(MetaRow(MetaKey.APP_VERSION, "versionCode $code"))
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun archiveRows(
        uri: Uri,
        name: String,
        archive: ArchiveEngine
    ): List<MetaRow> {
        val file = DocumentController.directFile(uri) ?: return emptyList()
        val opened = runCatching {
            archive.list(name, { file.inputStream() })
        }.getOrNull() ?: return emptyList()
        if (opened.error != null || opened.entries.isEmpty()) return emptyList()
        val files = opened.entries.filter { !it.isDir }
        return buildList {
            add(MetaRow(MetaKey.ENTRIES, opened.entries.size.toString()))
            val total = files.sumOf { it.size }
            if (total > 0) add(MetaRow(MetaKey.SIZE_UNCOMPRESSED, UiFormat.formatSize(total)))
        }
    }
}