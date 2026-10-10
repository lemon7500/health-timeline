package com.healthtimeline.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfBitmapBoundsTest {
    @Test fun ordinaryPortraitPageKeepsAspectRatio() {
        val size = boundedPdfBitmapSize(600, 800)
        assertEquals(PdfBitmapSize(1500, 2000), size)
    }

    @Test fun enormousPageCannotAllocateAnEnormousBitmap() {
        assertEquals(PdfBitmapSize(1600, 1600), boundedPdfBitmapSize(100_000, 100_000))
        listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (width, height) ->
            val size = boundedPdfBitmapSize(width, height)
            assertTrue(size.width in 1..1600)
            assertTrue(size.height in 1..2000)
            assertTrue(size.width.toLong() * size.height <= 3_200_000L)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun invalidDimensionsAreRejected() {
        boundedPdfBitmapSize(0, 100)
    }
}
