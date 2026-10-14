package app.fluffy.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.fluffy.util.AppLog
import app.fluffy.util.ThumbnailHelper
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import java.util.concurrent.ConcurrentHashMap

enum class GeneratedThumbnailKind { PDF_PAGE, AUDIO_ART, APK_ICON }

data class GeneratedThumbnail(val uri: Uri, val kind: GeneratedThumbnailKind)

class GeneratedThumbnailKeyer : Keyer<GeneratedThumbnail> {
    override fun key(data: GeneratedThumbnail, options: Options): String =
        "fluffy/generated/${data.kind}/${data.uri}"
}

class GeneratedThumbnailFetcherFactory(context: Context) : Fetcher.Factory<GeneratedThumbnail> {

    private val appContext = context.applicationContext

    override fun create(data: GeneratedThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
        GeneratedThumbnailFetcher(appContext, data, options)
}

private class GeneratedThumbnailFetcher(
    private val context: Context,
    private val data: GeneratedThumbnail,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val size = maxOf(
            options.size.width.pxOrElse { FALLBACK_PX },
            options.size.height.pxOrElse { FALLBACK_PX },
        )

        val bitmap = when (data.kind) {
            GeneratedThumbnailKind.PDF_PAGE -> renderPdfPage(data.uri, size)
            GeneratedThumbnailKind.AUDIO_ART -> ThumbnailHelper.loadAudioThumbnail(context, data.uri, size)
            GeneratedThumbnailKind.APK_ICON -> ThumbnailHelper.loadApkIcon(context, data.uri, size)
        } ?: throw IllegalStateException("Cannot render ${data.kind} for ${data.uri}")

        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun renderPdfPage(uri: Uri, size: Int): Bitmap? {
        val key = uri.toString()
        if (unrenderablePdfs[key] == true) return null
        if (!hasPdfHeader(uri)) {
            unrenderablePdfs[key] = true
            return null
        }
        val bitmap = ThumbnailHelper.loadPdfThumbnail(context, uri, size)
        if (bitmap == null) unrenderablePdfs[key] = true
        return bitmap
    }

    private fun hasPdfHeader(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(5)
            var read = 0
            while (read < 5) {
                val count = input.read(header, read, 5 - read)
                if (count <= 0) break
                read += count
            }
            if (read < 5) {
                false
            } else {
                header[0] == '%'.code.toByte() &&
                    header[1] == 'P'.code.toByte() &&
                    header[2] == 'D'.code.toByte() &&
                    header[3] == 'F'.code.toByte() &&
                    header[4] == '-'.code.toByte()
            }
        } ?: false
    } catch (e: Exception) {
        AppLog.d("GeneratedThumbnail", "hasPdfHeader failed: $uri", e)
        false
    }

    private companion object {
        const val FALLBACK_PX = 256
        val unrenderablePdfs = ConcurrentHashMap<String, Boolean>()
    }
}