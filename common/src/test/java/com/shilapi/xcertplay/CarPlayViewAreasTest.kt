package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayViewAreasTest {
    @Test fun declaresAllSavedSizesAndBothOrientations() {
        val saved = (1..10).map { (800 + it * 20) to 600 }
        val areas = CarPlayViewAreas.build(1280, 720, saved, 10) { _, _ -> null }
        assertEquals(22, areas.size)
        assertEquals(0, areas.indexFor(1280, 720))
        assertNotNull(areas.indexFor(720, 1280))
        saved.forEach { (w, h) ->
            assertNotNull(areas.indexFor(w, h))
            assertNotNull(areas.indexFor(h, w))
        }
        assertNull(areas.indexFor(777, 555, true, false))
        assertNotNull(areas.splitScreenRatio)
    }

    @Test fun deduplicatesSizesAndScalesSafeAreaForEachIndex() {
        val areas = CarPlayViewAreas.build(1280, 720, listOf(720 to 1280, 1280 to 720), 5) { w, h ->
            com.shilapi.xcertplay.airplay.SafeAreaRect(20, 20, w - 20, h - 20)
        }
        assertEquals(2, areas.size)
        assertEquals(640, areas.areas[0].widthPixels)
        assertEquals(360, areas.areas[0].heightPixels)
        assertEquals(10, areas.areas[0].safeArea?.left)
        assertEquals(10, areas.areas[1].safeArea?.top)
        assertTrue(areas.turnsWithScreen)
    }
}
