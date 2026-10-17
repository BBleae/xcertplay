package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class MainTexturePixelDiagnosticTest {
    @Test
    fun countsOnlyPixelsBrighterThanNearBlackThreshold() {
        assertEquals(
            500,
            mainTextureNonBlackPercentTenths(
                intArrayOf(0xff000000.toInt(), 0xff0c0c0c.toInt(),
                    0xff000d00.toInt(), 0xffffffff.toInt()),
            ),
        )
    }

    @Test
    fun treatsEmptyAndFullyBlackSamplesAsZero() {
        assertEquals(0, mainTextureNonBlackPercentTenths(intArrayOf()))
        assertEquals(0, mainTextureNonBlackPercentTenths(intArrayOf(0xff000000.toInt())))
    }

    @Test
    fun preservesFractionalPercentageAtTenthsPrecision() {
        val pixels = IntArray(64 * 64)
        pixels[0] = 0xff0000ff.toInt()
        assertEquals(0, mainTextureNonBlackPercentTenths(pixels))
        pixels[1] = 0xff0000ff.toInt()
        assertEquals(0, mainTextureNonBlackPercentTenths(pixels))
        pixels[2] = 0xff0000ff.toInt()
        assertEquals(0, mainTextureNonBlackPercentTenths(pixels))
        pixels[3] = 0xff0000ff.toInt()
        pixels[4] = 0xff0000ff.toInt()
        assertEquals(1, mainTextureNonBlackPercentTenths(pixels))
    }
}
