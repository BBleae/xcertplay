package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.transport.CarPlayLocationFix
import org.junit.Assert.*
import org.junit.Test

class VehicleDataTest {
    @Test fun filtersOptionalGpsFieldsWithoutInventingZeroValues() {
        val fix = CarPlayLocationFix(31.0, 121.0, 50.0, 80.0, 20.0, 5.0, 1_000)
        val provider = VehicleLocationProvider(Source(VehicleLocationSample(fix, 100)),
            VehicleDataConfig(enabled = emptySet()), { 100 })
        provider.start()
        val sentences = provider.latestNmea()!!.split("\r\n")
        assertTrue(sentences[0].contains(",,,M,"))
        assertTrue(sentences[1].contains(",E,,,"))
    }
    @Test fun subscriptionFreshnessAndCleanup() {
        var now = 100L
        val source = Source(VehicleLocationSample(CarPlayLocationFix(31.0, 121.0), 100))
        val provider = VehicleLocationProvider(source, VehicleDataConfig(), { now }, 20)
        assertNull(provider.latestNmea())
        assertEquals(VehicleDataState.NO_PHONE_SUBSCRIPTION, provider.state())
        assertTrue(provider.start())
        provider.start()
        assertEquals(1, source.starts)
        assertNotNull(provider.latestNmea())
        now = 121
        assertEquals(VehicleDataState.STALE, provider.state())
        assertNull(provider.latestNmea())
        now = 99
        assertNull(provider.latestNmea())
        source.sample = null
        assertEquals(VehicleDataState.UNAVAILABLE, provider.state())
        provider.stop()
        provider.stop()
        assertEquals(1, source.stops)
        assertNull(provider.latestNmea())
    }
    private class Source(var sample: VehicleLocationSample?) : VehicleLocationSource {
        var starts = 0
        var stops = 0
        override fun start(): Boolean { starts++; return true }
        override fun stop() { stops++ }
        override fun location() = sample
    }
}
