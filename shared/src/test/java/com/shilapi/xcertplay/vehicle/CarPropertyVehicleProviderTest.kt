package com.shilapi.xcertplay.vehicle

import org.junit.Assert.*
import org.junit.Test

class CarPropertyVehicleProviderTest {
    @Test fun unavailableBackendDoesNotStartOrWait() {
        val backend = object : ReadOnlyCarPropertyBackend {
            override fun connect() = false
            override fun read(propertyId: Int): CarPropertyValue? = error("must not query")
            override fun close() = Unit
        }
        val provider = CarPropertyVehicleProvider(CarPropertyDataStore(backend,
            VehiclePlatform.STANDARD_ANDROID, { 100 }), VehicleDataConfig(VehiclePlatform.STANDARD_ANDROID,
            setOf(VehicleDataFeature.EV_STATUS)), { 100 })
        assertFalse(provider.start())
        assertNull(provider.awaitSnapshot(5))
        provider.close()
    }
}
