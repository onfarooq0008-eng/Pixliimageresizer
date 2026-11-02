package com.pixli.imageresizer

import kotlin.math.max
import kotlin.math.roundToInt

/** Pure geometry helpers (no Android classes) so they can be unit-tested on the JVM. */
object ImageMath {
    const val MAX_SIDE = 12_000
    const val MAX_PIXELS = 30_000_000L

    /** EXIF orientations 5..8 swap width and height. */
    fun swapsAxes(exifOrientation: Int): Boolean = exifOrientation in 5..8

    fun isWithinLimits(w: Int, h: Int): Boolean =
        w in 1..MAX_SIDE && h in 1..MAX_SIDE && w.toLong() * h <= MAX_PIXELS

    /**
     * Largest size that fits inside the preset box, never upscaling.
     * The box is rotated for portrait images so a 3024x4032 photo uses 720x1280, not 1280x720.
     */
    fun fitWithin(srcW: Int, srcH: Int, boxW: Int, boxH: Int): Pair<Int, Int> {
        require(srcW > 0 && srcH > 0 && boxW > 0 && boxH > 0) { "Dimensions must be positive" }
        val (bw, bh) = if (srcH > srcW) boxH to boxW else boxW to boxH
        val scale = minOf(bw.toDouble() / srcW, bh.toDouble() / srcH, 1.0)
        return max(1, (srcW * scale).roundToInt()) to max(1, (srcH * scale).roundToInt())
    }

    fun heightForWidth(srcW: Int, srcH: Int, newW: Int): Int =
        max(1, (newW * srcH.toDouble() / srcW).roundToInt())

    fun widthForHeight(srcW: Int, srcH: Int, newH: Int): Int =
        max(1, (newH * srcW.toDouble() / srcH).roundToInt())

    /** Biggest power-of-two sample size that still leaves the decoded image >= the target size. */
    fun sampleSizeFor(rawW: Int, rawH: Int, targetRawW: Int, targetRawH: Int): Int {
        var sample = 1
        while (rawW / (sample * 2) >= targetRawW && rawH / (sample * 2) >= targetRawH) sample *= 2
        return sample
    }

    /** Safe file name stem (no path separators or odd characters), max 60 chars. */
    fun sanitizeBaseName(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName)
        val cleaned = base.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").trim().trim('.').take(60)
        return cleaned.ifEmpty { "image" }
    }
}
