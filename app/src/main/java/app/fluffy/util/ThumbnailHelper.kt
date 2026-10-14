package app.fluffy.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.fluffy.cache.CacheManager
import app.fluffy.helper.AppUtilsHelper
import java.io.File
import kotlin.math.min

object ThumbnailHelper {

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun scaleBitmap(bitmap: Bitmap, size: Int): Bitmap {
        val scale = min(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val width = (bitmap.width * scale).toInt()
        val height = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    fun scaleBitmapTo(bitmap: Bitmap, reqWidth: Int, reqHeight: Int): Bitmap {
        if (bitmap.width <= reqWidth && bitmap.height <= reqHeight) return bitmap
        return Bitmap.createScaledBitmap(bitmap, reqWidth, reqHeight, true)
    }

    fun decodeImageFile(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = calculateInSampleSize(bounds, reqWidth, reqHeight)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    fun loadVideoThumbnail(file: File, size: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            bitmap?.let { scaleBitmap(it, size) }
        } catch (e: Exception) {
            AppLog.d("ThumbnailHelper", "loadVideoThumbnail(file) failed: ${file.path}", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun loadPdfThumbnail(ctx: Context, uri: Uri, size: Int): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        return try {
            pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            if (pfd.statSize == 0L) return null
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= 0) return null
            page = renderer.openPage(0)
            val scale = min(size.toFloat() / page.width, size.toFloat() / page.height)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (e: Exception) {
            AppLog.d("ThumbnailHelper", "loadPdfThumbnail failed: $uri", e)
            null
        }
        finally {
            // Best-effort close: intentionally silent.
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    fun loadAudioThumbnail(ctx: Context, uri: Uri, size: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ctx, uri)
            val art = retriever.getEmbeddedPicture() ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(art, 0, art.size, bounds)
            var sample = 1
            while ((bounds.outWidth / sample) > size * 2 || (bounds.outHeight / sample) > size * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
            BitmapFactory.decodeByteArray(art, 0, art.size, opts)?.let { scaleBitmap(it, size) }
        } catch (e: Exception) {
            AppLog.d("ThumbnailHelper", "loadAudioThumbnail failed: $uri", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun loadApkIcon(ctx: Context, uri: Uri, size: Int): Bitmap? {
        return try {
            val apkFile: File = when (uri.scheme) {
                "file" -> uri.path?.let { File(it) }?.takeIf { it.exists() }
                "content" -> runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val tmp = AppUtilsHelper.cache.tempFile(CacheManager.Area.Stage, "apk_", ".apk")
                        tmp.outputStream().use { input.copyTo(it) }
                        tmp
                    }
                }.getOrNull()?.takeIf { it.exists() }
                else -> null
            } ?: return null
            try {
                val info = ctx.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES)
                    ?: return null
                val appInfo = info.applicationInfo ?: return null
                appInfo.sourceDir = apkFile.absolutePath
                appInfo.publicSourceDir = apkFile.absolutePath
                val drawable = appInfo.loadIcon(ctx.packageManager) ?: return null
                val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return null
                scaleBitmap(bmp, size)
            } finally {
                if (uri.scheme == "content") runCatching { apkFile.delete() }
            }
        } catch (e: Exception) {
            AppLog.d("ThumbnailHelper", "loadApkIcon failed: $uri", e)
            null
        }
    }
}
