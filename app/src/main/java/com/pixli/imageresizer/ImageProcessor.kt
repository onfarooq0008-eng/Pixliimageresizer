package com.pixli.imageresizer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageProcessor {
    const val FOLDER = "Pixli"

    private fun exifOrientation(ctx: Context, uri: Uri): Int = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    private fun swapsAxes(o: Int) = o in 5..8

    private fun matrixFor(o: Int): Matrix = Matrix().apply {
        when (o) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
        }
    }

    /** Reads name, size and (EXIF-corrected) dimensions without decoding the full bitmap. */
    fun queryInfo(ctx: Context, uri: Uri): ImageInfo {
        var name = "image_${System.currentTimeMillis()}.jpg"
        var size = 0L
        runCatching {
            ctx.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size <= 0) {
            size = runCatching {
                ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: 0L
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        require(opts.outWidth > 0 && opts.outHeight > 0) { "Unsupported image" }
        val swap = swapsAxes(exifOrientation(ctx, uri))
        return ImageInfo(
            uri, name,
            if (swap) opts.outHeight else opts.outWidth,
            if (swap) opts.outWidth else opts.outHeight,
            size
        )
    }

    /** Largest size that fits inside the preset box (landscape/portrait aware), never upscaling. */
    fun fitDims(src: ImageInfo, p: SizePreset): Pair<Int, Int> {
        val (bw, bh) = if (src.height > src.width) p.h to p.w else p.w to p.h
        val s = minOf(bw.toFloat() / src.width, bh.toFloat() / src.height, 1f)
        return max(1, (src.width * s).roundToInt()) to max(1, (src.height * s).roundToInt())
    }

    private fun decode(ctx: Context, src: ImageInfo, tw: Int, th: Int): Bitmap {
        val o = exifOrientation(ctx, src.uri)
        val swap = swapsAxes(o)
        val rawTw = if (swap) th else tw
        val rawTh = if (swap) tw else th
        val rawW = if (swap) src.height else src.width
        val rawH = if (swap) src.width else src.height
        var sample = 1
        while (rawW / (sample * 2) >= rawTw && rawH / (sample * 2) >= rawTh) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = ctx.contentResolver.openInputStream(src.uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("Cannot decode image")

        if (o != ExifInterface.ORIENTATION_NORMAL && o != ExifInterface.ORIENTATION_UNDEFINED) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrixFor(o), true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        if (bmp.width != tw || bmp.height != th) {
            val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return bmp
    }

    @Suppress("DEPRECATION")
    private fun compressFormat(f: OutFormat): Bitmap.CompressFormat = when (f) {
        OutFormat.JPG -> Bitmap.CompressFormat.JPEG
        OutFormat.PNG -> Bitmap.CompressFormat.PNG
        OutFormat.WEBP ->
            if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    }

    fun process(
        ctx: Context, src: ImageInfo, quality: Int, format: OutFormat,
        targetW: Int?, targetH: Int?, tag: String
    ): ProcessedImage {
        val tw = (targetW ?: src.width).coerceAtLeast(1)
        val th = (targetH ?: src.height).coerceAtLeast(1)
        var bmp = decode(ctx, src, tw, th)

        if (format == OutFormat.JPG && bmp.hasAlpha()) {
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bmp.recycle()
            bmp = flat
        }

        val dir = File(File(ctx.cacheDir, "out"), System.nanoTime().toString()).apply { mkdirs() }
        val base = src.name.substringBeforeLast('.').take(60)
        val displayName = "${base}_$tag.${format.ext}"
        val file = File(dir, displayName)
        FileOutputStream(file).use { bmp.compress(compressFormat(format), quality.coerceIn(1, 100), it) }
        val result = ProcessedImage(file, displayName, bmp.width, bmp.height, file.length(), src.sizeBytes, format, src)
        bmp.recycle()
        return result
    }

    private fun originalDate(ctx: Context, uri: Uri): Long? {
        val cols = listOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED, "last_modified")
        for (col in cols) {
            val v = runCatching {
                ctx.contentResolver.query(uri, arrayOf(col), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
            }.getOrNull()
            if (v != null && v > 0) return if (col == MediaStore.MediaColumns.DATE_MODIFIED) v * 1000 else v
        }
        return null
    }

    /** Saves to Pictures/ImageCompressor via MediaStore (no storage permission needed on Android 10+). */
    fun saveToGallery(ctx: Context, r: ProcessedImage, keepDate: Boolean): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, r.displayName)
            put(MediaStore.Images.Media.MIME_TYPE, r.format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (keepDate) originalDate(ctx, r.source.uri)?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> r.file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun loadGallery(ctx: Context, limit: Int = 600): List<Uri> {
        val list = mutableListOf<Uri>()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(
            base, arrayOf(MediaStore.Images.Media._ID), null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext() && list.size < limit) list += ContentUris.withAppendedId(base, c.getLong(idx))
        }
        return list
    }

    fun loadSaved(ctx: Context): List<SavedItem> {
        val list = mutableListOf<SavedItem>()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(
            base,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_PICTURES}/$FOLDER%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                list += SavedItem(ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1) ?: "image", c.getLong(2))
            }
        }
        return list
    }
}
