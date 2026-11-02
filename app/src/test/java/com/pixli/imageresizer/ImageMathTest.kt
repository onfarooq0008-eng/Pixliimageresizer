package com.pixli.imageresizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMathTest {

    @Test fun presetNeverUpscalesSmallImages() {
        assertEquals(500 to 400, ImageMath.fitWithin(500, 400, 1280, 720))
    }

    @Test fun presetFitsLandscapeInsideBox() {
        // 4032x3024 into 1280x720 -> limited by height: 960x720
        assertEquals(960 to 720, ImageMath.fitWithin(4032, 3024, 1280, 720))
    }

    @Test fun presetBoxIsRotatedForPortraitImages() {
        // 3024x4032 portrait uses a 720x1280 box -> 720x960
        assertEquals(720 to 960, ImageMath.fitWithin(3024, 4032, 1280, 720))
    }

    @Test fun presetKeepsAspectRatioWithinOnePixel() {
        val (w, h) = ImageMath.fitWithin(4000, 3000, 1920, 1080)
        assertEquals(4000.0 / 3000.0, w.toDouble() / h, 0.01)
    }

    @Test fun aspectLockedWidthAndHeight() {
        assertEquals(1440, ImageMath.heightForWidth(3024, 4032, 1080))
        assertEquals(1080, ImageMath.widthForHeight(3024, 4032, 1440))
        assertEquals(1, ImageMath.heightForWidth(1000, 1, 1))
    }

    @Test fun sampleSizeNeverDropsBelowTarget() {
        assertEquals(1, ImageMath.sampleSizeFor(4000, 3000, 4000, 3000))
        assertEquals(2, ImageMath.sampleSizeFor(4000, 3000, 1500, 1100))
        assertEquals(4, ImageMath.sampleSizeFor(4000, 3000, 900, 700))
        // upscaling request must not sample down
        assertEquals(1, ImageMath.sampleSizeFor(1000, 1000, 2000, 2000))
    }

    @Test fun exifOrientationsThatSwapAxes() {
        assertFalse(ImageMath.swapsAxes(1))
        assertFalse(ImageMath.swapsAxes(3))
        assertTrue(ImageMath.swapsAxes(5))
        assertTrue(ImageMath.swapsAxes(6))
        assertTrue(ImageMath.swapsAxes(8))
    }

    @Test fun sizeLimits() {
        assertTrue(ImageMath.isWithinLimits(4000, 3000))
        assertFalse(ImageMath.isWithinLimits(0, 100))
        assertFalse(ImageMath.isWithinLimits(12_001, 10))
        assertFalse(ImageMath.isWithinLimits(10_000, 10_000)) // 100 MP > 30 MP
    }

    @Test fun fileNamesAreSanitised() {
        assertEquals("IMG_2026", ImageMath.sanitizeBaseName("IMG_2026.jpg"))
        assertEquals("a_b_c", ImageMath.sanitizeBaseName("a/b\\c.png"))
        assertEquals("image", ImageMath.sanitizeBaseName("...jpg"))
        assertEquals(60, ImageMath.sanitizeBaseName("x".repeat(200) + ".jpg").length)
    }

    @Test fun humanReadableSizes() {
        assertEquals("999 B", 999L.fmtSize())
        assertEquals("1 KB", 1024L.fmtSize())
        assertEquals("1.5 MB", (1536L * 1024).fmtSize())
    }
}
