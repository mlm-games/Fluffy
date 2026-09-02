package app.fluffy.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
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

    fun decodeImageUri(ctx: Context, uri: Uri, size: Int): Bitmap? {
        return try {
            val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, optsBounds)
            }
            if (optsBounds.outWidth <= 0 || optsBounds.outHeight <= 0) return null
            val sample = calculateInSampleSize(optsBounds, size, size)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            ctx.contentResolver.openInputStream(uri)?.use { stream2 ->
                BitmapFactory.decodeStream(stream2, null, opts)
            }
        } catch (_: Exception) { null }
    }

    fun loadVideoThumbnail(ctx: Context, uri: Uri, size: Int): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(ctx, uri)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            bitmap?.let { scaleBitmap(it, size) }
        } catch (_: Exception) { null }
    }

    fun loadVideoThumbnail(file: File, size: Int): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            bitmap?.let { scaleBitmap(it, size) }
        } catch (_: Exception) { null }
    }

    fun loadPdfThumbnail(ctx: Context, uri: Uri, size: Int): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        return try {
            pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            if (pfd.statSize in 1..256) return null
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= 0) return null
            page = renderer.openPage(0)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val scale = min(size.toFloat() / page.width, size.toFloat() / page.height)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (scaledBitmap != bitmap) bitmap.recycle()
            page.render(scaledBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            scaledBitmap
        } catch (_: Exception) { null }
        finally {
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    fun loadAudioThumbnail(ctx: Context, uri: Uri, size: Int): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(ctx, uri)
            val art = retriever.getEmbeddedPicture()
            retriever.release()
            art?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)?.let { scaleBitmap(it, size) }
            }
        } catch (_: Exception) { null }
    }
}
