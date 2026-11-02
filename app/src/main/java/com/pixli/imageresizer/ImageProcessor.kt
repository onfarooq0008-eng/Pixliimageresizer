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
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object ImageProcessor {
    private const val TAG = "PixliImage"
    const val FOLDER = "Pixli"

    /** Progressive size factors tried when the device runs out of memory. 1.0 = exactly what was asked for. */
    private val MEMORY_FALLBACK_FACTORS = doubleArrayOf(1.0, 0.75, 0.5, 0.35, 0.25)

    // ───────────────────────── reading ─────────────────────────

    private fun matrixFor(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
        }
    }

    private fun parseExifDate(value: String): Long? = try {
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { isLenient = false }.parse(value)?.time
    } catch (e: ParseException) {
        Log.w(TAG, "Unparseable EXIF date: $value")
        null
    }

    /** Reads name, size, EXIF-corrected dimensions, orientation and capture date without decoding pixels. */
    fun queryInfo(ctx: Context, uri: Uri): ImageInfo {
        val resolver = ctx.contentResolver
        var name = "image_${System.currentTimeMillis()}.jpg"
        var size = 0L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read metadata for $uri", e)
        }
        if (size <= 0) {
            size = try {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0L) } ?: 0L
            } catch (e: Exception) {
                Log.w(TAG, "Could not read size for $uri", e)
                0L
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: throw ImageProcessingException("Cannot open this image")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageProcessingException("Unsupported or corrupted image")
        }

        var orientation = ExifInterface.ORIENTATION_NORMAL
        var taken: Long? = null
        try {
            resolver.openInputStream(uri)?.use { s ->
                val exif = ExifInterface(s)
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { taken = parseExifDate(it) }
            }
        } catch (e: IOException) {
            Log.w(TAG, "No readable EXIF for $uri: ${e.message}")
        }

        val swap = ImageMath.swapsAxes(orientation)
        return ImageInfo(
            uri = uri,
            name = name,
            width = if (swap) bounds.outHeight else bounds.outWidth,
            height = if (swap) bounds.outWidth else bounds.outHeight,
            sizeBytes = size,
            orientation = orientation,
            takenAtMillis = taken
        )
    }

    fun fitDims(src: ImageInfo, p: SizePreset): Pair<Int, Int> = ImageMath.fitWithin(src.width, src.height, p.w, p.h)

    // ───────────────────────── processing ─────────────────────────

    /**
     * Decodes with the largest safe sample size, then applies EXIF rotation and the final scale in ONE
     * bitmap allocation. Every intermediate bitmap is recycled, also when an error happens.
     */
    private fun decode(ctx: Context, src: ImageInfo, tw: Int, th: Int): Bitmap {
        val swap = ImageMath.swapsAxes(src.orientation)
        val rawTw = if (swap) th else tw
        val rawTh = if (swap) tw else th
        val rawW = if (swap) src.height else src.width
        val rawH = if (swap) src.width else src.height

        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImageMath.sampleSizeFor(rawW, rawH, rawTw, rawTh)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val stream = ctx.contentResolver.openInputStream(src.uri) ?: throw ImageProcessingException("Cannot open this image")
        val decoded = stream.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw ImageProcessingException("Unsupported or corrupted image")

        val scaleX = rawTw.toFloat() / decoded.width
        val scaleY = rawTh.toFloat() / decoded.height
        val needsScale = abs(scaleX - 1f) > 0.001f || abs(scaleY - 1f) > 0.001f
        val needsRotation = src.orientation != ExifInterface.ORIENTATION_NORMAL &&
            src.orientation != ExifInterface.ORIENTATION_UNDEFINED
        if (!needsScale && !needsRotation) return decoded

        val matrix = matrixFor(src.orientation).apply { preScale(scaleX, scaleY) }
        val transformed = try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            decoded.recycle()
            throw e
        }
        if (transformed !== decoded) decoded.recycle()
        if (transformed.width == tw && transformed.height == th) return transformed

        // Rounding can leave us 1 px off; make the dimensions exact.
        val exact = try {
            Bitmap.createScaledBitmap(transformed, tw, th, true)
        } catch (e: OutOfMemoryError) {
            transformed.recycle()
            throw e
        }
        if (exact !== transformed) transformed.recycle()
        return exact
    }

    @Suppress("DEPRECATION")
    private fun compressFormat(f: OutFormat): Bitmap.CompressFormat = when (f) {
        OutFormat.JPG -> Bitmap.CompressFormat.JPEG
        OutFormat.PNG -> Bitmap.CompressFormat.PNG
        OutFormat.WEBP ->
            if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    }

    /**
     * Compress and/or resize. With targetW/targetH == null the original resolution is kept.
     * Transparency is kept for PNG/WEBP; JPG has no alpha so transparent areas are flattened onto white.
     * Throws [ImageProcessingException] with a user-presentable message on failure.
     */
    fun process(
        ctx: Context, src: ImageInfo, quality: Int, format: OutFormat,
        targetW: Int?, targetH: Int?, tag: String
    ): ProcessedImage {
        val tw = targetW ?: src.width
        val th = targetH ?: src.height
        if (tw < 1 || th < 1) throw ImageProcessingException("Invalid output size")

        var work: Bitmap? = null
        var outDir: File? = null
        var downscaled = false
        try {
            var lastOom: OutOfMemoryError? = null
            for ((attempt, factor) in MEMORY_FALLBACK_FACTORS.withIndex()) {
                val w = max(1, (tw * factor).roundToInt())
                val h = max(1, (th * factor).roundToInt())
                try {
                    work = decode(ctx, src, w, h)
                    downscaled = attempt > 0
                    break
                } catch (e: OutOfMemoryError) {
                    lastOom = e
                    Log.w(TAG, "Out of memory decoding ${src.name} at ${w}x$h", e)
                }
            }
            var bmp = work ?: throw ImageProcessingException("Not enough memory to process this image", lastOom)

            if (format == OutFormat.JPG && bmp.hasAlpha()) {
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                bmp.recycle()
                bmp = flat
                work = flat
            }

            val dir = File(File(ctx.cacheDir, "out"), System.nanoTime().toString())
            if (!dir.mkdirs() && !dir.isDirectory) throw ImageProcessingException("Cannot create a temporary folder")
            outDir = dir
            val displayName = "${ImageMath.sanitizeBaseName(src.name)}_$tag.${format.ext}"
            val file = File(dir, displayName)

            val written = FileOutputStream(file).use { out ->
                val ok = bmp.compress(compressFormat(format), quality.coerceIn(1, 100), out)
                out.flush()
                ok
            }
            if (!written || file.length() <= 0L) throw ImageProcessingException("Could not encode the image as ${format.label}")

            return ProcessedImage(file, displayName, bmp.width, bmp.height, file.length(), src.sizeBytes, format, src, downscaled)
        } catch (e: ImageProcessingException) {
            outDir?.deleteRecursively()
            throw e
        } catch (e: IOException) {
            outDir?.deleteRecursively()
            throw ImageProcessingException("Could not write the processed image: ${e.message}", e)
        } catch (e: OutOfMemoryError) {
            outDir?.deleteRecursively()
            throw ImageProcessingException("Not enough memory to process this image", e)
        } catch (e: RuntimeException) {
            outDir?.deleteRecursively()
            Log.w(TAG, "Unexpected failure while processing ${src.name}", e)
            throw ImageProcessingException("Could not process this image: ${e.message}", e)
        } finally {
            work?.recycle()
        }
    }

    // ───────────────────────── saving / exporting ─────────────────────────

    private fun originalDate(ctx: Context, src: ImageInfo): Long? {
        src.takenAtMillis?.let { return it }
        val columns = listOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED, "last_modified")
        for (col in columns) {
            val v = try {
                ctx.contentResolver.query(src.uri, arrayOf(col), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
            } catch (e: Exception) {
                null // provider does not expose this column; try the next one
            }
            if (v != null && v > 0) return if (col == MediaStore.MediaColumns.DATE_MODIFIED) v * 1000 else v
        }
        return null
    }

    /**
     * Saves to Pictures/Pixli through MediaStore (no storage permission needed on Android 10+).
     * Never reports success unless every byte was written; a failed save leaves no MediaStore entry behind.
     */
    fun saveToGallery(ctx: Context, r: ProcessedImage, keepDate: Boolean): SaveResult {
        val expected = r.file.length()
        if (!r.file.isFile || expected <= 0L) return SaveResult.Failed("The processed file is missing")

        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, r.displayName)
            put(MediaStore.Images.Media.MIME_TYPE, r.format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (keepDate) originalDate(ctx, r.source)?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        }
        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insert failed", e)
            return SaveResult.Failed("The gallery refused the file: ${e.message}")
        } ?: return SaveResult.Failed("The gallery could not create the file")

        return try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open the destination")
            val copied = out.use { o -> r.file.inputStream().use { it.copyTo(o) } }
            if (copied != expected) throw IOException("Only $copied of $expected bytes were written")
            val rows = resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            if (rows <= 0) throw IOException("Could not finish the gallery entry")
            SaveResult.Ok(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Saving to gallery failed", e)
            deleteQuietly(ctx, uri)
            SaveResult.Failed(e.message ?: "Saving failed")
        }
    }

    /** Writes the processed file to a user-chosen document (Storage Access Framework). */
    fun exportTo(ctx: Context, r: ProcessedImage, target: Uri): SaveResult {
        val expected = r.file.length()
        if (!r.file.isFile || expected <= 0L) return SaveResult.Failed("The processed file is missing")
        return try {
            val out = ctx.contentResolver.openOutputStream(target) ?: throw IOException("Could not open the destination")
            val copied = out.use { o -> r.file.inputStream().use { it.copyTo(o) } }
            if (copied != expected) throw IOException("Only $copied of $expected bytes were written")
            SaveResult.Ok(target)
        } catch (e: Exception) {
            Log.w(TAG, "Export failed", e)
            try {
                DocumentsContract.deleteDocument(ctx.contentResolver, target)
            } catch (cleanup: Exception) {
                Log.w(TAG, "Could not remove the incomplete export", cleanup)
            }
            SaveResult.Failed(e.message ?: "Export failed")
        }
    }

    private fun deleteQuietly(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove incomplete entry $uri", e)
        }
    }

    // ───────────────────────── listing ─────────────────────────

    fun loadGallery(ctx: Context, limit: Int = 600): List<Uri> {
        val list = ArrayList<Uri>(limit.coerceAtMost(600))
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        try {
            ctx.contentResolver.query(
                base, arrayOf(MediaStore.Images.Media._ID), null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (c.moveToNext() && list.size < limit) list += ContentUris.withAppendedId(base, c.getLong(idx))
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Gallery access denied", e)
        }
        return list
    }

    fun loadSaved(ctx: Context): List<SavedItem> {
        val list = ArrayList<SavedItem>()
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        try {
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
        } catch (e: SecurityException) {
            Log.w(TAG, "Saved list not readable", e)
        }
        return list
    }
}
