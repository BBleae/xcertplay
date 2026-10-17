package com.shilapi.xcertplay.vehicle

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CarPropertyVehicleProviderTest {
    @Test fun statusAndWheelSubscriptionsStopTheirOwnBackgroundReads() {
        val statusReads = java.util.concurrent.atomic.AtomicInteger()
        val speedReads = java.util.concurrent.atomic.AtomicInteger()
        val now = { System.nanoTime() / 1_000_000 }
        val backend = object : ReadOnlyCarPropertyBackend {
            override fun connect() = true
            override fun read(propertyId: Int): CarPropertyValue? = when (propertyId) {
                VehicleTelemetrySnapshot.STANDARD_OUTSIDE_TEMPERATURE -> { statusReads.incrementAndGet(); CarPropertyValue(-1.5f, now()) }
                VehicleTelemetrySnapshot.STANDARD_SPEED -> { speedReads.incrementAndGet(); CarPropertyValue(0f, now()) }
                VehicleTelemetrySnapshot.STANDARD_GEAR -> CarPropertyValue(4, now())
                else -> null
            }
            override fun close() = Unit
        }
        val provider = CarPropertyVehicleProvider(CarPropertyDataStore(backend,
            VehiclePlatform.STANDARD_ANDROID, now), VehicleDataConfig(VehiclePlatform.STANDARD_ANDROID,
            setOf(VehicleDataFeature.OUTSIDE_TEMPERATURE, VehicleDataFeature.WHEEL_SPEED)), now)
        try {
            provider.start()
            assertNotNull(provider.awaitSnapshot(1_000))
            provider.onSubscriptionChanged(true)
            provider.requestSpeed(true)
            Thread.sleep(1_200)
            assertTrue(statusReads.get() >= 2)
            assertTrue(speedReads.get() >= 2)
            provider.onSubscriptionChanged(false)
            Thread.sleep(250) // allow any already-started query to finish
            val stoppedStatus = statusReads.get()
            val ongoingSpeed = speedReads.get()
            Thread.sleep(1_100)
            assertEquals(stoppedStatus, statusReads.get())
            assertTrue(speedReads.get() > ongoingSpeed)
            provider.requestSpeed(false)
            Thread.sleep(250)
            val stoppedSpeed = speedReads.get()
            Thread.sleep(400)
            assertEquals(stoppedSpeed, speedReads.get())
        } finally { provider.close() }
    }
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

    @Test fun closeFailsUntilTheExactWorkerHasActuallyStopped() {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        val backend = object : ReadOnlyCarPropertyBackend {
            override fun connect() = true
            override fun read(propertyId: Int): CarPropertyValue? {
                readEntered.countDown()
                while (true) {
                    try {
                        if (releaseRead.await(10, TimeUnit.MILLISECONDS)) break
                    } catch (_: InterruptedException) {
                        // Deliberately ignore interruption to inject an unretired worker.
                    }
                }
                return null
            }
            override fun close() { closeCalls.incrementAndGet() }
        }
        val provider = CarPropertyVehicleProvider(
            CarPropertyDataStore(backend, VehiclePlatform.STANDARD_ANDROID) { 100 },
            VehicleDataConfig(
                VehiclePlatform.STANDARD_ANDROID,
                setOf(VehicleDataFeature.OUTSIDE_TEMPERATURE),
            ),
            now = { 100 },
            workerCloseTimeoutMillis = 20,
        )
        assertTrue(provider.start())
        assertTrue(readEntered.await(2, TimeUnit.SECONDS))

        assertThrows(IOException::class.java) { provider.close() }
        assertEquals(1, closeCalls.get())

        releaseRead.countDown()
        provider.close()
        assertEquals(2, closeCalls.get())
    }

    @Test fun backendCloseFailureKeepsTheProviderRetryable() {
        val closeCalls = AtomicInteger()
        val backend = object : ReadOnlyCarPropertyBackend {
            override fun connect() = true
            override fun read(propertyId: Int): CarPropertyValue? = null
            override fun close() {
                if (closeCalls.incrementAndGet() == 1) throw IOException("injected disconnect failure")
            }
        }
        val provider = CarPropertyVehicleProvider(
            CarPropertyDataStore(backend, VehiclePlatform.STANDARD_ANDROID) { 100 },
            VehicleDataConfig(VehiclePlatform.STANDARD_ANDROID, emptySet()),
            now = { 100 },
            workerCloseTimeoutMillis = 100,
        )
        assertTrue(provider.start())

        assertThrows(IOException::class.java) { provider.close() }
        provider.close()

        assertEquals(2, closeCalls.get())
    }
}
