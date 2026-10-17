package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.ServiceConnection
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class AndroidCarPropertyBackendTest {
    @Test
    fun bindingDeathRetiresManagerAndLetsNextConnectCleanUpExactCar() {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as Unsafe
        val context = unsafe.allocateInstance(BackendTestContext::class.java) as BackendTestContext
        val name = unsafe.allocateInstance(ComponentName::class.java) as ComponentName
        val backend = AndroidCarPropertyBackend(context)
        val publication = backend.privateField("managerPublication") as CarPropertyManagerPublication
        val originalCar = RetryableReflectiveCarOwner()
        val firstGeneration = Any()
        backend.setPrivateField("car", originalCar)
        backend.setPrivateField("connectionGeneration", firstGeneration)
        val oldConnection = backend.connectionForTest(firstGeneration)
        publication.publish(Any())

        oldConnection.onBindingDied(name)

        assertFalse(backend.isConnected)
        assertNull(backend.sourceIdentity)
        assertNull(backend.privateField("car"))
        assertSame(originalCar, backend.privateField("pendingDisconnect"))
        assertEquals("car service binding died", backend.availabilityReason)

        // A retry must disconnect the retired owner before attempting a fresh Car binding.
        try {
            backend.connect()
        } catch (error: RuntimeException) {
            // Plain JVM tests have no Android Car service; its failure path reaches unmocked Log.i.
            assertTrue(error.message?.contains("not mocked") == true)
        }
        assertEquals(1, originalCar.disconnectCalls.get())
        assertNull(backend.privateField("pendingDisconnect"))

        val nextCar = RetryableReflectiveCarOwner()
        val nextGeneration = Any()
        backend.setPrivateField("car", nextCar)
        backend.setPrivateField("connectionGeneration", nextGeneration)
        publication.publish(Any())
        val nextSource = backend.sourceIdentity
        oldConnection.onServiceDisconnected(name)
        oldConnection.onBindingDied(name)
        assertSame(nextSource, backend.sourceIdentity)
        assertSame(nextCar, backend.privateField("car"))
    }

    @Test
    fun managerAndGenerationArePublishedTogetherDuringConcurrentRebind() {
        val oldManager = Any()
        val nextManager = Any()
        val oldGeneration = Any()
        val nextGeneration = Any()
        val generationStarted = CountDownLatch(1)
        val allowPublication = CountDownLatch(1)
        val generationCalls = AtomicInteger()
        val writerFailure = AtomicReference<Throwable?>()
        val publication = CarPropertyManagerPublication {
            when (generationCalls.incrementAndGet()) {
                1 -> oldGeneration
                2 -> {
                    generationStarted.countDown()
                    check(allowPublication.await(5, TimeUnit.SECONDS))
                    nextGeneration
                }
                else -> error("unexpected rebind")
            }
        }
        publication.publish(oldManager)
        val writer = Thread {
            try {
                publication.publish(nextManager)
            } catch (error: Throwable) {
                writerFailure.set(error)
            }
        }.apply { isDaemon = true; start() }

        try {
            assertTrue("new manager generation was not started", generationStarted.await(5, TimeUnit.SECONDS))
            val duringRebind = publication.snapshot()
            assertSame(oldManager, duringRebind?.manager)
            assertSame(oldGeneration, duringRebind?.generation)
        } finally {
            allowPublication.countDown()
            writer.join(5_000)
        }

        assertFalse("rebind writer did not finish", writer.isAlive)
        writerFailure.get()?.let { throw AssertionError("rebind writer failed", it) }
        val afterRebind = publication.snapshot()
        assertSame(nextManager, afterRebind?.manager)
        assertSame(nextGeneration, afterRebind?.generation)
        publication.publish(null)
        assertNull(publication.snapshot())
    }

    @Test
    fun legacyBackendReadUsesAreaZeroAndRejectsUnsupportedArea() {
        val legacy = object : ReadOnlyCarPropertyBackend {
            override fun connect() = true
            override fun read(propertyId: Int) = CarPropertyValue(propertyId, null)
            override fun close() = Unit
        }

        assertEquals(123, legacy.read(123, areaId = 0)?.value)
        assertThrows(UnsupportedOperationException::class.java) {
            legacy.read(123, areaId = 2)
        }
    }

    @Test
    fun androidReflectionPassesExactAreaAndRejectsUnavailableZone() {
        val manager = AreaAwareReflectivePropertyManager()

        assertEquals(21.5f, readAndroidCarProperty(manager, 123, areaId = 1)?.value)
        assertEquals(19.0f, readAndroidCarProperty(manager, 123, areaId = 2)?.value)
        assertNull(readAndroidCarProperty(manager, 123, areaId = 3))
        assertEquals(listOf(123 to 1, 123 to 2, 123 to 3), manager.calls)
        assertEquals(100L, readAndroidCarProperty(manager, 123, areaId = 0)?.sourceEventMillis)
        assertEquals(123 to 0, manager.calls.last())
    }

    @Test
    fun genericNumericReadRejectsMismatchedPropertyAreaStatusAndValue() {
        val manager = BooleanReflectivePropertyManager(null)
        val invalid = listOf(
            BooleanReflectivePropertyValue(propertyId = 124, areaId = 2, value = 19.0f),
            BooleanReflectivePropertyValue(propertyId = 123, areaId = 1, value = 19.0f),
            BooleanReflectivePropertyValue(propertyId = 123, areaId = 2, status = 1, value = 19.0f),
            BooleanReflectivePropertyValue(propertyId = 123, areaId = 2, value = true),
            BooleanReflectivePropertyValue(propertyId = 123, areaId = 2, value = null),
        )
        invalid.forEach { reading ->
            manager.reading = reading
            assertNull("invalid numeric value: $reading", readAndroidCarProperty(manager, 123, 2))
        }
        manager.reading = BooleanReflectivePropertyValue(propertyId = 123, areaId = 2,
            value = 19.0f, timestampNanos = 2_500_000_000L)
        assertEquals(CarPropertyValue(19.0f, 2_500L), readAndroidCarProperty(manager, 123, 2))
        assertEquals(List(6) { 123 to 2 }, manager.calls)
    }

    @Test
    fun genericNumericReadFailsClosedForMissingReflectionButPreservesPermissionDenial() {
        assertNull(readAndroidCarProperty(OldReflectivePropertyManager(), 123, 2))
        val manager = BooleanReflectivePropertyManager(MissingIdentityReflectivePropertyValue())
        assertNull(readAndroidCarProperty(manager, 123, 2))
        manager.reading = MissingTimestampReflectivePropertyValue()
        assertNull(readAndroidCarProperty(manager, 123, 2))
        manager.reading = null
        assertNull(readAndroidCarProperty(manager, 123, 2))
        assertThrows(SecurityException::class.java) {
            readAndroidCarProperty(DeniedReflectivePropertyManager(), 123, 2)
        }
        manager.reading = DeniedIdentityReflectivePropertyValue()
        assertThrows(SecurityException::class.java) { readAndroidCarProperty(manager, 123, 2) }
    }

    @Test
    fun genericNumericReadDoesNotInventSourceTimeForInvalidTimestamp() {
        val manager = BooleanReflectivePropertyManager(null)
        listOf(0L, -1L, null).forEach { invalidNanos ->
            manager.reading = BooleanReflectivePropertyValue(propertyId = 123, areaId = 2,
                value = 19.0f, timestampNanos = invalidNanos)
            assertEquals(CarPropertyValue(19.0f, null), readAndroidCarProperty(manager, 123, 2))
        }
    }

    @Test
    fun parkingBrakeReadRequiresExactBooleanPropertyAndGlobalArea() {
        val manager = BooleanReflectivePropertyManager(
            BooleanReflectivePropertyValue(value = true, timestampNanos = 1_234_567_890L),
        )

        assertEquals(CarPropertyBooleanValue(true, 1_234L),
            readAndroidCarBooleanProperty(manager, ANDROID_PARKING_BRAKE_ON))
        manager.reading = BooleanReflectivePropertyValue(value = false, timestampNanos = 2_500_000_000L)
        assertEquals(CarPropertyBooleanValue(false, 2_500L),
            readAndroidCarBooleanProperty(manager, ANDROID_PARKING_BRAKE_ON))
        assertEquals(listOf(ANDROID_PARKING_BRAKE_ON to 0, ANDROID_PARKING_BRAKE_ON to 0),
            manager.calls)

        assertNull(readAndroidCarBooleanProperty(manager, 123))
        assertEquals(2, manager.calls.size)
    }

    @Test
    fun parkingBrakeReadRejectsUnavailableMismatchedUntypedAndUntimestampedValues() {
        val manager = BooleanReflectivePropertyManager(null)
        val invalid = listOf(
            BooleanReflectivePropertyValue(propertyId = 123, value = true),
            BooleanReflectivePropertyValue(areaId = 1, value = true),
            BooleanReflectivePropertyValue(status = 1, value = true),
            BooleanReflectivePropertyValue(value = 1), // Numeric coercion would be unsafe.
            BooleanReflectivePropertyValue(value = "true"),
            BooleanReflectivePropertyValue(value = true, timestampNanos = 0L),
            BooleanReflectivePropertyValue(value = true, timestampNanos = -1L),
            BooleanReflectivePropertyValue(value = true, timestampNanos = null),
        )
        invalid.forEach { reading ->
            manager.reading = reading
            assertNull("invalid parking brake value: $reading",
                readAndroidCarBooleanProperty(manager, ANDROID_PARKING_BRAKE_ON))
        }
        manager.reading = null
        assertNull(readAndroidCarBooleanProperty(manager, ANDROID_PARKING_BRAKE_ON))
    }

    @Test
    fun parkingBrakeReadFailsClosedForOldManagerButPreservesPermissionDenial() {
        assertNull(readAndroidCarBooleanProperty(OldReflectivePropertyManager(), ANDROID_PARKING_BRAKE_ON))
        assertNull(readAndroidCarBooleanProperty(
            BooleanReflectivePropertyManager(OldReflectivePropertyValue()), ANDROID_PARKING_BRAKE_ON))
        assertThrows(SecurityException::class.java) {
            readAndroidCarBooleanProperty(DeniedReflectivePropertyManager(), ANDROID_PARKING_BRAKE_ON)
        }
    }

    @Test
    fun videoParkNumberRequiresExactGlobalSpeedOrSelectedGear() {
        val speedId = VehicleTelemetrySnapshot.STANDARD_SPEED
        val selectionId = VehicleTelemetrySnapshot.STANDARD_GEAR_SELECTION
        val manager = BooleanReflectivePropertyManager(
            BooleanReflectivePropertyValue(propertyId = speedId, value = 0f,
                timestampNanos = 2_500_000_000L),
        )
        assertEquals(CarPropertyValue(0f, 2_500L), readAndroidCarVideoParkNumber(manager, speedId))
        manager.reading = BooleanReflectivePropertyValue(propertyId = selectionId, value = 4,
            timestampNanos = 2_600_000_000L)
        assertEquals(CarPropertyValue(4, 2_600L), readAndroidCarVideoParkNumber(manager, selectionId))
        assertNull(readAndroidCarVideoParkNumber(manager, VehicleTelemetrySnapshot.STANDARD_GEAR))
        assertEquals(listOf(speedId to 0, selectionId to 0), manager.calls)

        val bad = listOf(
            BooleanReflectivePropertyValue(propertyId = speedId, areaId = 1, value = 0f),
            BooleanReflectivePropertyValue(propertyId = 123, value = 0f),
            BooleanReflectivePropertyValue(propertyId = speedId, status = 1, value = 0f),
            BooleanReflectivePropertyValue(propertyId = speedId, value = 0),
            BooleanReflectivePropertyValue(propertyId = speedId, value = 0f, timestampNanos = 0L),
        )
        bad.forEach { manager.reading = it; assertNull(readAndroidCarVideoParkNumber(manager, speedId)) }
        manager.reading = BooleanReflectivePropertyValue(propertyId = selectionId, value = 4f)
        assertNull(readAndroidCarVideoParkNumber(manager, selectionId))
        assertNull(readAndroidCarVideoParkNumber(OldReflectivePropertyManager(), speedId))
    }

    @Test
    fun reflectiveDisconnectFailureIsVisibleAndTheExactOwnerCanBeRetried() {
        val calls = AtomicInteger()
        val owner = object {
            @Suppress("unused")
            fun disconnect() {
                if (calls.incrementAndGet() == 1) throw IOException("injected disconnect failure")
            }
        }

        assertThrows(IOException::class.java) { disconnectAndroidCar(owner) }
        disconnectAndroidCar(owner)

        assertEquals(2, calls.get())
    }

    @Test
    fun reflectiveDisconnectDoesNotDowngradeErrorToOperationalException() {
        val fatal = AssertionError("fatal disconnect")
        val owner = object {
            @Suppress("unused")
            fun disconnect() {
                throw fatal
            }
        }

        val thrown = assertThrows(AssertionError::class.java) { disconnectAndroidCar(owner) }

        assertSame(fatal, thrown)
    }
}

private class BackendTestContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
}

private fun AndroidCarPropertyBackend.privateField(name: String): Any? =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

private fun AndroidCarPropertyBackend.setPrivateField(name: String, value: Any?) {
    javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

private fun AndroidCarPropertyBackend.connectionForTest(generation: Any): ServiceConnection =
    javaClass.getDeclaredMethod("newConnection", Any::class.java)
        .apply { isAccessible = true }.invoke(this, generation) as ServiceConnection

class RetryableReflectiveCarOwner {
    val disconnectCalls = AtomicInteger()
    @Suppress("unused") fun disconnect() { disconnectCalls.incrementAndGet() }
}

/** Public JVM fixtures keep Java reflection access identical to android.car manager methods. */
class AreaAwareReflectivePropertyManager {
    val calls = mutableListOf<Pair<Int, Int>>()

    @Suppress("unused")
    fun getProperty(propertyId: Int, areaId: Int): AreaAwareReflectivePropertyValue {
        calls += propertyId to areaId
        return AreaAwareReflectivePropertyValue(
            propertyId = propertyId,
            areaId = areaId,
            status = if (areaId in 0..2) 0 else 1,
            value = when (areaId) {
                1 -> 21.5f
                2 -> 19.0f
                else -> 0.0f
            },
        )
    }
}

class AreaAwareReflectivePropertyValue(
    private val propertyId: Int,
    private val areaId: Int,
    private val status: Int,
    private val value: Number,
) {
    @Suppress("unused") fun getPropertyId(): Int = propertyId
    @Suppress("unused") fun getAreaId(): Int = areaId
    @Suppress("unused") fun getStatus(): Int = status
    @Suppress("unused") fun getValue(): Number = value
    @Suppress("unused") fun getTimestamp(): Long = 100_000_000L
}

class MissingIdentityReflectivePropertyValue {
    @Suppress("unused") fun getStatus(): Int = 0
    @Suppress("unused") fun getValue(): Number = 19.0f
    @Suppress("unused") fun getTimestamp(): Long = 1_000_000_000L
}

class MissingTimestampReflectivePropertyValue {
    @Suppress("unused") fun getPropertyId(): Int = 123
    @Suppress("unused") fun getAreaId(): Int = 2
    @Suppress("unused") fun getStatus(): Int = 0
    @Suppress("unused") fun getValue(): Number = 19.0f
}

class DeniedIdentityReflectivePropertyValue {
    @Suppress("unused") fun getPropertyId(): Int = throw SecurityException("identity inaccessible")
}

class BooleanReflectivePropertyManager(var reading: Any?) {
    val calls = mutableListOf<Pair<Int, Int>>()
    @Suppress("unused") fun getProperty(propertyId: Int, areaId: Int): Any? {
        calls += propertyId to areaId
        return reading
    }
}

class BooleanReflectivePropertyValue(
    private val propertyId: Int = ANDROID_PARKING_BRAKE_ON,
    private val areaId: Int = 0,
    private val status: Int = 0,
    private val value: Any?,
    private val timestampNanos: Long? = 1_000_000_000L,
) {
    @Suppress("unused") fun getPropertyId(): Int = propertyId
    @Suppress("unused") fun getAreaId(): Int = areaId
    @Suppress("unused") fun getStatus(): Int = status
    @Suppress("unused") fun getValue(): Any? = value
    @Suppress("unused") fun getTimestamp(): Long? = timestampNanos
}

class OldReflectivePropertyManager {
    @Suppress("unused") fun getProperty(propertyId: Int): Any = OldReflectivePropertyValue()
}

class OldReflectivePropertyValue {
    @Suppress("unused") fun getStatus(): Int = 0
    @Suppress("unused") fun getValue(): Boolean = true
    @Suppress("unused") fun getTimestamp(): Long = 1_000_000_000L
}

class DeniedReflectivePropertyManager {
    @Suppress("unused") fun getProperty(propertyId: Int, areaId: Int): Any {
        throw SecurityException("parking brake permission denied")
    }
}
