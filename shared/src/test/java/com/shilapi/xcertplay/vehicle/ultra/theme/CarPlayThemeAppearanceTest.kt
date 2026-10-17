package com.shilapi.xcertplay.vehicle.ultra.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayThemeAppearanceTest {
    @Test
    fun dynamicColorStrictCasesResolveDarkOnlyAtInterfaceStyleTwo() {
        val single = CarPlayThemeAppearanceSchema.parseDynamicColor(
            mapOf("single" to mapOf("value" to "#f00")),
        )
        assertTrue(single is CarPlayThemeDynamic.Single)
        assertEquals(CarPlayThemeColor.parse("#f00"), single.resolve(2))
        assertEquals(single.resolve(2), single.resolve(-1))

        val dynamic = CarPlayThemeAppearanceSchema.parseDynamicColorJson(
            """{"dynamic":{"value":{"darkMode":"#000","lightMode":"#fff"}}}"""
                .encodeToByteArray(),
        )
        assertTrue(dynamic is CarPlayThemeDynamic.Appearance)
        for (style in listOf(Int.MIN_VALUE, -1, 0, 1, 3, Int.MAX_VALUE)) {
            assertEquals("style=$style", CarPlayThemeColor.parse("#fff"), dynamic.resolve(style))
        }
        assertEquals(CarPlayThemeColor.parse("#000"), dynamic.resolve(2))
    }

    @Test
    fun malformedDynamicWrappersAreRejectedWithoutGuessingDefaults() {
        val invalid = listOf<Any?>(
            emptyMap<String, Any?>(),
            mapOf("single" to mapOf("value" to "#fff"), "dynamic" to emptyMap<String, Any?>()),
            mapOf("single" to "#fff"),
            mapOf("single" to mapOf("color" to "#fff")),
            mapOf("single" to mapOf("value" to "#fff", "other" to true)),
            mapOf("single" to mapOf("value" to null)),
            mapOf("dynamic" to mapOf("value" to mapOf("lightMode" to "#fff"))),
            mapOf("dynamic" to mapOf("value" to mapOf(
                "lightMode" to "#fff", "darkMode" to "#000", "other" to 1,
            ))),
            mapOf("dynamic" to mapOf("value" to mapOf(
                "lightMode" to "#fff", "darkMode" to "unsupported",
            ))),
            mapOf("dynamicColor" to mapOf("value" to "#fff")),
        )
        invalid.forEach { value ->
            assertThrows("value=$value", CarPlayThemeSchemaException::class.java) {
                CarPlayThemeAppearanceSchema.parseDynamicColor(value)
            }
        }
    }

    @Test
    fun gradientStopsPreserveNamesAndSortDeterministicallyWithoutRangePolicy() {
        val input = linkedMapOf<String, Any?>(
            "1.5" to "#fff",
            "0.50" to "#888",
            "-0.25" to "#000",
            "0.5" to "#777",
            "1e0" to "#f00",
        )
        val first = CarPlayThemeAppearanceSchema.parseGradientColor(mapOf("stops" to input))
        val second = CarPlayThemeAppearanceSchema.parseGradientColor(
            mapOf("stops" to input.toList().reversed().toMap()),
        )
        val exactKeys = listOf("-0.25", "0.5", "0.50", "1e0", "1.5")
        assertEquals(exactKeys, first.stops.map(CarPlayThemeGradientStop::encodedPosition))
        assertEquals(exactKeys, second.stops.map(CarPlayThemeGradientStop::encodedPosition))
        assertEquals(first.stops, second.stops)
        assertEquals(-0.25, first.stops.first().position, 0.0)
        assertEquals(1.5, first.stops.last().position, 0.0)
        val roundedToSameDouble = CarPlayThemeAppearanceSchema.parseGradientColor(mapOf(
            "stops" to linkedMapOf(
                "1.00000000000000001" to "#fff",
                "1.0" to "#000",
            ),
        ))
        assertEquals(listOf("1.0", "1.00000000000000001"),
            roundedToSameDouble.stops.map(CarPlayThemeGradientStop::encodedPosition))
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (first.stops as MutableList<CarPlayThemeGradientStop>).clear()
        }
    }

    @Test
    fun rejectsMalformedGradientFieldsKeysColorsAndLocalStopLimit() {
        val invalid = listOf<Any?>(
            mapOf("stops" to emptyMap<String, Any?>(), "extra" to true),
            mapOf("stops" to emptyList<Any?>()),
            mapOf("stops" to mapOf(".5" to "#000")),
            mapOf("stops" to mapOf("+0.5" to "#000")),
            mapOf("stops" to mapOf("01" to "#000")),
            mapOf("stops" to mapOf("NaN" to "#000")),
            mapOf("stops" to mapOf("1e999" to "#000")),
            mapOf("stops" to mapOf("1e-999" to "#000")),
            mapOf("stops" to mapOf("0.5" to "not-a-color")),
        )
        invalid.forEach { value ->
            assertThrows("value=$value", CarPlayThemeSchemaException::class.java) {
                CarPlayThemeAppearanceSchema.parseGradientColor(value)
            }
        }
        val overLimit = (0..CarPlayThemeAppearanceSchema.MAXIMUM_GRADIENT_STOPS)
            .associate { it.toString() to "#000" }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeAppearanceSchema.parseGradientColor(mapOf("stops" to overLimit))
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeAppearanceSchema.parseGradientColor(
                mapOf("stops" to mapOf("0" to "#000")),
                CarPlayThemeSchemaLimits(maximumCollectionEntries = 1),
            )
        }
    }

    @Test
    fun paletteColorsAcceptDynamicAndLegacyGradientWithImmutableTypedView() {
        val white = gradient("#fff")
        val black = gradient("#000")
        val paletteColors = linkedMapOf<String, Any?>(
            "night" to mapOf("dynamic" to mapOf("value" to mapOf(
                "lightMode" to white, "darkMode" to black,
            ))),
            "legacy" to white,
            "day" to mapOf("single" to mapOf("value" to white)),
        )
        val parsed = CarPlayThemeAppearanceSchema.parsePaletteColors(paletteColors)
        assertEquals(listOf("day", "legacy", "night"), parsed.keys.toList())
        assertEquals(CarPlayThemeColor.parse("#fff"), parsed.getValue("legacy")
            .resolve(2).stops.single().color)
        assertEquals(CarPlayThemeColor.parse("#000"), parsed.getValue("night")
            .resolve(2).stops.single().color)
        assertEquals(CarPlayThemeColor.parse("#fff"), parsed.getValue("night")
            .resolve(1).stops.single().color)
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (parsed as MutableMap<String, CarPlayThemeDynamic<CarPlayThemeGradientColor>>).clear()
        }

        val resolved = CarPlayResolvedThemeSchema.parse(minimalResolvedTheme(paletteColors))
        val palette = resolved.palettes.getValue("base")
        assertEquals(parsed.keys, palette.gradientColors!!.keys)
        assertEquals(paletteColors, palette.colors.value)
        assertFalse(palette.gradientColors!!.isEmpty())
    }

    @Test
    fun opaqueFuturePaletteRemainsOpaqueButRecognizedMalformedCaseIsRejected() {
        val unresolved = CarPlayResolvedThemeSchema.parse(
            minimalResolvedTheme(mapOf("primary" to mapOf("future" to true))),
        ).palettes.getValue("base")
        assertNull(unresolved.gradientColors)
        assertEquals(mapOf("primary" to mapOf("future" to true)), unresolved.colors.value)

        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeAppearanceSchema.parsePaletteColors(
                mapOf("bad" to mapOf("future" to true)),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayResolvedThemeSchema.parse(minimalResolvedTheme(
                mapOf("bad" to mapOf("single" to mapOf("value" to mapOf(
                    "stops" to mapOf("nan" to "#000"),
                )))),
            ))
        }
        val tooManyColors = (0..CarPlayThemeAppearanceSchema.MAXIMUM_PALETTE_COLOR_IDS)
            .associate { "id-$it" to gradient("#000") }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeAppearanceSchema.parsePaletteColors(tooManyColors)
        }
    }

    private fun gradient(color: String): Map<String, Any?> =
        mapOf("stops" to mapOf("0" to color))

    private fun minimalResolvedTheme(colors: Any?): Map<String, Any?> = mapOf(
        "palettes" to mapOf("base" to mapOf(
            "id" to "palette-base",
            "displayName" to "Base",
            "colors" to colors,
            "representedColorScheme" to mapOf("single" to mapOf("color" to "#101820")),
            "sortIndex" to 0,
            "prominent" to false,
        )),
        "layoutStyles" to mapOf("cluster" to mapOf("palette-a" to emptyMap<String, Any?>())),
        "defaultPaletteIDs" to mapOf("cluster" to "palette-a"),
        "defaultWallpaperIDs" to mapOf("cluster" to "wallpaper-base"),
    )
}
