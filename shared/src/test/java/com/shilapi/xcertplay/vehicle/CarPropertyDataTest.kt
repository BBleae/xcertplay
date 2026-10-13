package com.shilapi.xcertplay.vehicle

import org.junit.Assert.*
import org.junit.Test

class CarPropertyDataTest {
    @Test fun platformTypesUnitsAndFreshnessAreExplicit() {
        val values = mapOf(
            VehicleTelemetrySnapshot.STANDARD_SPEED to 12.5f,
            VehicleTelemetrySnapshot.STANDARD_GEAR to 2,
        ).mapValues { CarPropertyReading(it.key, it.value, 100, "test") }
        val snapshot = VehicleTelemetrySnapshot(values, VehiclePlatform.STANDARD_ANDROID)
        assertNull(snapshot.batteryPercent(100))
        assertNull(snapshot.rangeKm(100))
        assertEquals(12.5, snapshot.speedMetersPerSecond(100))
        assertEquals(VehicleGear.REVERSE, snapshot.gear(100))
        assertNull(snapshot.speedMetersPerSecond(2_101))
        assertNull(snapshot.gear(30_101))
    }
    @Test fun permissionFailureDoesNotKeepPollingAndCloseClearsAllReadings() {
        var reads = 0
        var closed = false
        val backend = object : ReadOnlyCarPropertyBackend {
            override fun connect() = true
            override fun read(propertyId: Int): Number { reads++; throw SecurityException("denied") }
            override fun close() { closed = true }
        }
        val store = CarPropertyDataStore(backend, VehiclePlatform.STANDARD_ANDROID, { 100 })
        assertTrue(store.connect())
        store.refresh(listOf(VehicleTelemetrySnapshot.STANDARD_SPEED))
        store.refresh(listOf(VehicleTelemetrySnapshot.STANDARD_SPEED))
        assertEquals(1, reads)
        assertNull(store.snapshot().speedMetersPerSecond(100))
        store.close()
        assertTrue(closed)
        assertTrue(store.snapshot().readings.isEmpty())
    }
}
