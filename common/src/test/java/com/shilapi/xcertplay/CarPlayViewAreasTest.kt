package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.SafeAreaRect
import org.junit.Assert.*
import org.junit.Test

class CarPlayViewAreasTest {
    @Test
    fun maximumDisplayBoundsAreReorientedWithoutUsingHistoricalSquareEnvelope() {
        assertEquals(
            1200 to 2608,
            alignMaximumDisplaySize(
                currentWidth = 1200,
                currentHeight = 2464,
                reportedMaximumWidth = 2608,
                reportedMaximumHeight = 1200,
            ),
        )
        assertEquals(
            1200 to 2608,
            alignMaximumDisplaySize(
                currentWidth = 1200,
                currentHeight = 2464,
                reportedMaximumWidth = 1200,
                reportedMaximumHeight = 2608,
            ),
        )
    }

    @Test fun portraitKeepsOnlyFittingPortraitSizesWithCurrentAtZero() {
        val loadedSafeAreas = mutableListOf<Pair<Int, Int>>()
        val areas = CarPlayViewAreas.build(
            currentWidth = 1200,
            currentHeight = 2464,
            savedSizes = listOf(
                1080 to 2200,
                1200 to 2464,
                2464 to 1200,
                1200 to 1200,
                1300 to 2400,
                1000 to 2500,
                0 to 1000,
            ),
            scaleTenths = 10,
        ) { width, height ->
            loadedSafeAreas += width to height
            null
        }

        assertEquals(CarPlayDisplayOrientation.PORTRAIT, areas.orientation)
        assertEquals(2, areas.size)
        assertEquals(0, areas.indexFor(1200, 2464))
        assertEquals(1, areas.indexFor(1080, 2200))
        assertNull(areas.indexFor(2464, 1200))
        assertNull(areas.indexFor(1200, 1200))
        assertNull(areas.indexFor(1300, 2400))
        assertNull(areas.indexFor(1000, 2500))
        assertEquals(listOf(1200 to 2464, 1080 to 2200), loadedSafeAreas)
        assertEquals(1200, areas.activityWidthPixels)
        assertEquals(2464, areas.activityHeightPixels)
        assertEquals(1200, areas.widthPixels)
        assertEquals(2464, areas.heightPixels)
        assertFalse(areas.contains(2464, 1200))
        assertNull(areas.indexFor(777, 555, true, false))
        assertNotNull(areas.splitScreenRatio)
    }

    @Test fun landscapeDoesNotSynthesizePortraitAndScalesEachSafeArea() {
        val areas = CarPlayViewAreas.build(2464, 1200, listOf(1200 to 2464, 2200 to 1080), 5) { w, h ->
            SafeAreaRect(20, 20, w - 20, h - 20)
        }

        assertEquals(CarPlayDisplayOrientation.LANDSCAPE, areas.orientation)
        assertEquals(2, areas.size)
        assertEquals(0, areas.indexFor(2464, 1200))
        assertEquals(1, areas.indexFor(2200, 1080))
        assertNull(areas.indexFor(1200, 2464))
        assertEquals(1232, areas.widthPixels)
        assertEquals(600, areas.heightPixels)
        assertEquals(10, areas.areas[0].safeArea?.left)
        assertEquals(10, areas.areas[1].safeArea?.top)
    }

    @Test fun squareClassificationDoesNotAdmitPortraitOrLandscapeSizes() {
        val areas = CarPlayViewAreas.build(
            1000,
            1000,
            listOf(800 to 800, 900 to 800, 800 to 900),
            10,
        ) { _, _ -> null }

        assertEquals(CarPlayDisplayOrientation.SQUARE, areas.orientation)
        assertEquals(2, areas.size)
        assertEquals(0, areas.indexFor(1000, 1000))
        assertEquals(1, areas.indexFor(800, 800))
        assertNull(areas.indexFor(900, 800))
        assertNull(areas.indexFor(800, 900))
    }

    @Test fun currentGeometryFlowsUnchangedThroughConfigInfoAndTouchHid() {
        val areas = CarPlayViewAreas.build(
            1200,
            2464,
            listOf(2464 to 1200, 1000 to 2000),
            10,
        ) { _, _ -> null }
        val displayConfig = areas.toAirPlayDisplayConfig(
            physicalSize = AirPlayPhysicalSizeMm(widthMm = 92, heightMm = 189),
            fps = 60,
            safeAreaDrawOutside = true,
        )

        assertEquals(1200, displayConfig.widthPixels)
        assertEquals(2464, displayConfig.heightPixels)
        assertEquals(92, displayConfig.widthPhysicalMm)
        assertEquals(189, displayConfig.heightPhysicalMm)
        assertEquals(0, displayConfig.initialViewArea)

        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "950.7.1",
                main = displayConfig,
            ),
        )
        val display = (info["displays"] as List<*>).single() as Map<*, *>
        assertEquals(1200, display["widthPixels"])
        assertEquals(2464, display["heightPixels"])
        assertEquals(1200, display["widthPixelsMax"])
        assertEquals(2464, display["heightPixelsMax"])
        assertEquals(92, display["widthPhysical"])
        assertEquals(189, display["heightPhysical"])
        assertEquals(0, display["initialViewArea"])
        val advertisedAreas = display["viewAreas"] as List<*>
        assertEquals(2, advertisedAreas.size)
        assertEquals(1200, (advertisedAreas[0] as Map<*, *>)["widthPixels"])
        assertEquals(2464, (advertisedAreas[0] as Map<*, *>)["heightPixels"])

        val touch = (info["hidDevices"] as List<*>)
            .map { it as Map<*, *> }
            .single { it["name"] == "xcertplay Touchscreen" }
        val descriptor = touch["hidDescriptor"] as ByteArray
        assertEquals(1200, descriptorMaximum(descriptor, usage = 0x30))
        assertEquals(2464, descriptorMaximum(descriptor, usage = 0x31))
    }

    private fun descriptorMaximum(descriptor: ByteArray, usage: Int): Int {
        for (index in 0..descriptor.size - 5) {
            if (
                (descriptor[index].toInt() and 0xff) == 0x26 &&
                (descriptor[index + 3].toInt() and 0xff) == 0x09 &&
                (descriptor[index + 4].toInt() and 0xff) == usage
            ) {
                return (descriptor[index + 1].toInt() and 0xff) or
                    ((descriptor[index + 2].toInt() and 0xff) shl 8)
            }
        }
        fail("HID logical maximum for usage 0x${usage.toString(16)} is missing")
        return -1
    }
}
