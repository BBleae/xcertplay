package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayDisplaySettingsTest {
    @Test
    fun freshPortraitPreservesConfiguredWidthSemantics() {
        assertEquals(
            AirPlayPhysicalSizeBasis.WIDTH,
            AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS,
        )
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1200,
            currentHeightPixels = 2608,
            maximumWidthPixels = 1200,
            maximumHeightPixels = 2608,
            referenceMillimeters = 200,
            basis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS,
        )

        assertEquals(200, size.widthMm)
        assertEquals(435, size.heightMm)
    }

    @Test
    fun freshLandscapeKeepsOrdinaryCarPlayLegacyPhysicalScale() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1280,
            currentHeightPixels = 720,
            maximumWidthPixels = 1280,
            maximumHeightPixels = 720,
            referenceMillimeters = 200,
            basis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS,
        )

        assertEquals(AirPlayPhysicalSizeMm(200, 113), size)
    }

    @Test
    fun shortestEdgeKeepsPixelDensityStableAcrossRotation() {
        val portrait = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1200,
            currentHeightPixels = 2464,
            maximumWidthPixels = 1200,
            maximumHeightPixels = 2608,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.SHORTEST_EDGE,
        )
        val landscape = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 2464,
            currentHeightPixels = 1200,
            maximumWidthPixels = 2608,
            maximumHeightPixels = 1200,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.SHORTEST_EDGE,
        )

        assertEquals(AirPlayPhysicalSizeMm(200, 411), portrait)
        assertEquals(AirPlayPhysicalSizeMm(411, 200), landscape)
    }

    @Test
    fun longestEdgeRotatesWithTheSamePhysicalPanel() {
        val portrait = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1200,
            currentHeightPixels = 2464,
            maximumWidthPixels = 2608,
            maximumHeightPixels = 2608,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.LONGEST_EDGE,
        )
        val landscape = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 2464,
            currentHeightPixels = 1200,
            maximumWidthPixels = 2608,
            maximumHeightPixels = 2608,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.LONGEST_EDGE,
        )

        assertEquals(92, portrait.widthMm)
        assertEquals(189, portrait.heightMm)
        assertEquals(189, landscape.widthMm)
        assertEquals(92, landscape.heightMm)
    }

    @Test
    fun maximumObservedWidthScalesPhysicalSizeWithCurrentActivityWidth() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 768,
            currentHeightPixels = 800,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.WIDTH,
        )

        assertEquals(80, size.widthMm)
        assertEquals(83, size.heightMm)
    }

    @Test
    fun maximumObservedHeightScalesPhysicalSizeWithCurrentActivityHeight() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 978,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.HEIGHT,
        )

        assertEquals(356, size.widthMm)
        assertEquals(181, size.heightMm)
    }

    @Test
    fun maximumResolutionKeepsConfiguredReferenceLength() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 1080,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 250,
            basis = AirPlayPhysicalSizeBasis.WIDTH,
        )

        assertEquals(250, size.widthMm)
        assertEquals(141, size.heightMm)
    }
}
