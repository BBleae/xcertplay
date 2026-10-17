package com.shilapi.xcertplay.vehicle.ultra.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayThemeColorTest {
    @Test
    fun parsesConfirmedHexFormsWithOptionalHashAndTrailingAlpha() {
        assertEquals(
            CarPlayThemeColor.parse(listOf(1.0, 0.0, 136.0 / 255.0, 1.0)),
            CarPlayThemeColor.parse("#F08"),
        )
        assertEquals(
            CarPlayThemeColor.parse(listOf(0.0, 1.0, 128.0 / 255.0, 1.0)),
            CarPlayThemeColor.parse("00ff80"),
        )
        assertEquals(
            CarPlayThemeColor.parse(listOf(1.0, 0.0, 128.0 / 255.0, 64.0 / 255.0)),
            CarPlayThemeColor.parse("#FF008040"),
        )
        assertEquals(64.0 / 255.0, CarPlayThemeColor.parse("#FF008040")!!.alpha, 0.0)
    }

    @Test
    fun acceptsOnlyFourFiniteUnitRangeNumericChannels() {
        assertEquals(0.0, CarPlayThemeColor.parse(listOf(-0.0, 0, 0.5f, 1))!!.red, 0.0)
        assertNull(CarPlayThemeColor.parse(listOf(0, 0, 0)))
        assertNull(CarPlayThemeColor.parse(listOf(0, 0, 0, 1, 1)))
        assertNull(CarPlayThemeColor.parse(listOf(0, 0, 0, "1")))
        assertNull(CarPlayThemeColor.parse(listOf(0, 0, 0, Double.NaN)))
        assertNull(CarPlayThemeColor.parse(listOf(0, 0, 0, Double.POSITIVE_INFINITY)))
        assertNull(CarPlayThemeColor.parse(listOf(0, 0, 0, -0.01)))
        assertNull(CarPlayThemeColor.parse(listOf(0, 0, 0, 1.01)))
    }

    @Test
    fun rejectsUnconfirmedOrMalformedStringsWithoutNormalizing() {
        listOf("#abcd", "#1234", "#12", "#12345", "#1234567", "#123456789", "##123456",
            "#12ggff", "#123456 ", " #123456", "red", "transparent", "0x123456", "")
            .forEach { assertNull("Expected unsupported: $it", CarPlayThemeColor.parse(it)) }
    }
}
