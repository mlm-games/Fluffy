package app.fluffy.util

import android.webkit.MimeTypeMap
import java.util.Locale

object ArchiveTypes {
    enum class Kind { ZIP, SEVENZ, TAR, TARGZ, TARBZ2, TARXZ }

    private val zip = listOf(".zip", ".jar", ".apk")
    private val sevenZ = listOf(".7z")
    private val tar = listOf(".tar")
    private val targz = listOf(".tar.gz", ".tgz")
    private val tarbz2 = listOf(".tar.bz2", ".tbz2")
    private val tarxz = listOf(".tar.xz", ".txz")

    private val all = zip + sevenZ + tar + targz + tarbz2 + tarxz

    fun isArchive(fileName: String): Boolean {
        val n = fileName.lowercase(Locale.ROOT)
        return all.any { n.endsWith(it) }
    }

    fun infer(name: String): Kind {
        val n = name.lowercase(Locale.ROOT)
        return when {
            targz.any { n.endsWith(it) } -> Kind.TARGZ
            tarbz2.any { n.endsWith(it) } -> Kind.TARBZ2
            tarxz.any { n.endsWith(it) } -> Kind.TARXZ
            tar.any { n.endsWith(it) } -> Kind.TAR
            zip.any { n.endsWith(it) } -> Kind.ZIP
            sevenZ.any { n.endsWith(it) } -> Kind.SEVENZ
            else -> Kind.ZIP
        }
    }

    fun baseNameForExtraction(fileName: String): String {
        val n = fileName.lowercase(Locale.ROOT)
        val hit = (targz + tarbz2 + tarxz + zip + sevenZ + tar)
            .firstOrNull { n.endsWith(it) }
        val trimmed = when {
            hit == null -> fileName.substringBeforeLast('.', fileName)
            else -> fileName.dropLast(hit.length)
        }
        return trimmed.ifBlank { "extracted" }
    }

    fun mimeFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!fromMap.isNullOrBlank()) return fromMap
        return when (infer(fileName)) {
            Kind.ZIP -> "application/zip"
            Kind.SEVENZ -> "application/x-7z-compressed"
            Kind.TAR -> "application/x-tar"
            Kind.TARGZ -> "application/gzip"
            Kind.TARBZ2 -> "application/x-bzip2"
            Kind.TARXZ -> "application/x-xz"
        }
    }
}