package com.shilapi.xcertplay.vehicle.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import com.shilapi.xcertplay.airplay.rcs.caf.CafErrorPolicy
import com.shilapi.xcertplay.airplay.rcs.caf.SyntheticCafTestFixture
import com.shilapi.xcertplay.vehicle.CarPropertyValue
import com.shilapi.xcertplay.vehicle.ReadOnlyCarPropertyBackend
import com.shilapi.xcertplay.vehicle.VehicleTelemetrySnapshot
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedUnitProjectionTest {
    private val errors = CafErrorPolicy(-101L, -102L, -103L, -104L)

    @Test
    fun oneRawVhalFloatFansOutToThreeUnitsWithOneReadAndOneSourceAge() {
        val backend = SpeedBackend(CarPropertyValue(10.0f, 995L))
        val repository = repository(backend)
        try {
            assertTrue(repository.start())
            val result = repository.read(linkedSetOf(MPS_IID, KMH_IID, MPH_IID))
            assertEquals(10.0, result.values[MPS_IID])
            assertEquals(36.0, result.values[KMH_IID])
            assertEquals(22.369362920544, result.values[MPH_IID] as Double, 0.00000001)
            assertEquals(mapOf(MPS_IID to 5L, KMH_IID to 5L, MPH_IID to 5L),
                result.sourceAgeMillisByIid)
            assertTrue(result.errors.isEmpty())
            assertEquals(1, backend.reads.get())
            assertEquals(listOf(SPEED_PROPERTY to 0), backend.addresses)
        } finally {
            repository.close()
        }
    }

    @Test
    fun aSinglePollSharesTheSourceReadAcrossSubscribedUnitsAndStopsOnClose() {
        val backend = SpeedBackend(CarPropertyValue(10.0f, 995L))
        val repository = repository(backend, intervalMillis = 1_000)
        val first = CountDownLatch(1)
        val updates = mutableListOf<Map<Long, Any?>>()
        try {
            assertTrue(repository.start())
            val subscription = repository.subscribe(setOf(MPS_IID, KMH_IID, MPH_IID)) { update ->
                synchronized(updates) { updates += update.values }
                first.countDown()
            }
            assertTrue(first.await(3, TimeUnit.SECONDS))
            assertEquals(1, backend.reads.get())
            val firstUpdate = synchronized(updates) { updates.single() }
            assertEquals(setOf(MPS_IID, KMH_IID, MPH_IID), firstUpdate.keys)
            assertEquals(10.0, firstUpdate[MPS_IID])
            assertEquals(36.0, firstUpdate[KMH_IID])
            assertEquals(22.369362920544, firstUpdate[MPH_IID] as Double, 0.00000001)
            subscription.close()
            repository.close()
            assertEquals(1, backend.closes.get())
            assertFalse(repository.start())
            assertEquals(mapOf(MPS_IID to errors.operationFailed),
                repository.read(setOf(MPS_IID)).errors)
        } finally {
            repository.close()
        }
    }

    @Test
    fun missingStaleOrFutureVhalTimestampNeverBecomesInstrumentSpeed() {
        val backend = SpeedBackend()
        val repository = repository(backend, clockMillis = 5_000L)
        try {
            assertTrue(repository.start())
            listOf(null, 0L, 1L, 5_001L).forEach { eventMillis ->
                backend.reading = CarPropertyValue(10.0f, eventMillis)
                val result = repository.read(setOf(MPS_IID, KMH_IID, MPH_IID))
                assertTrue(result.values.isEmpty())
                assertTrue(result.sourceAgeMillisByIid.isEmpty())
                assertEquals(listOf(MPS_IID, KMH_IID, MPH_IID).associateWith { errors.invalidValue },
                    result.errors)
            }
            backend.reading = CarPropertyValue(10.0f, 1L)
            val boundary = repository(backend, clockMillis = 2_001L)
            try {
                assertTrue(boundary.start())
                assertEquals(10.0, boundary.read(setOf(MPS_IID)).values[MPS_IID])
            } finally {
                boundary.close()
            }
        } finally {
            repository.close()
        }
    }

    @Test
    fun invalidFloatTypeNonfiniteNegativeAndOutOfRangeAreNotClampedOrConverted() {
        val backend = SpeedBackend()
        val repository = repository(backend)
        try {
            assertTrue(repository.start())
            listOf(10.0, 10, Float.NaN, Float.POSITIVE_INFINITY, -0.5f, 150.1f).forEach { raw ->
                backend.reading = CarPropertyValue(raw, 995L)
                val result = repository.read(setOf(MPS_IID, KMH_IID, MPH_IID))
                assertTrue(result.values.isEmpty())
                assertEquals(listOf(MPS_IID, KMH_IID, MPH_IID).associateWith { errors.invalidValue },
                    result.errors)
            }
            backend.reading = CarPropertyValue(150.0f, 995L)
            val boundary = repository.read(setOf(MPS_IID, KMH_IID))
            assertEquals(150.0, boundary.values[MPS_IID])
            assertEquals(540.0, boundary.values[KMH_IID])
        } finally {
            repository.close()
        }
    }

    @Test
    fun absentSourceOrDisconnectedBackendReportsOperationFailureForEveryUnit() {
        val backend = SpeedBackend()
        val repository = repository(backend)
        try {
            assertTrue(repository.start())
            val missing = repository.read(setOf(MPS_IID, KMH_IID, MPH_IID))
            assertTrue(missing.values.isEmpty())
            assertEquals(listOf(MPS_IID, KMH_IID, MPH_IID).associateWith { errors.operationFailed },
                missing.errors)
            assertEquals(1, backend.reads.get())
            backend.connected = false
            assertEquals(mapOf(KMH_IID to errors.operationFailed),
                repository.read(setOf(KMH_IID)).errors)
            assertEquals(1, backend.reads.get())
        } finally {
            repository.close()
        }
    }

    @Test
    fun projectedSpeedRequiresAnExactManagerGeneration() {
        val backend = SpeedBackend(CarPropertyValue(10.0f, 995L)).apply { sourceGeneration = null }
        val repository = repository(backend)
        try {
            assertTrue(repository.start())
            val missing = repository.read(setOf(MPS_IID, KMH_IID, MPH_IID))
            assertTrue(missing.values.isEmpty())
            assertEquals(listOf(MPS_IID, KMH_IID, MPH_IID).associateWith { errors.operationFailed },
                missing.errors)
            assertEquals(0, backend.reads.get())

            backend.sourceGeneration = Any()
            assertEquals(10.0, repository.read(setOf(MPS_IID)).values[MPS_IID])
        } finally {
            repository.close()
        }
    }

    @Test
    fun retiredManagerReadCannotPublishAnyProjectedUnitAfterConnectedRebind() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readReleased = AtomicBoolean()
        val backend = SpeedBackend(CarPropertyValue(10.0f, 995L)).apply {
            beforeReadReturn = {
                entered.countDown()
                readReleased.set(release.await(5, TimeUnit.SECONDS))
            }
        }
        val repository = repository(backend)
        val result = AtomicReference<VehicleStateResult>()
        val reader = Thread { result.set(repository.read(linkedSetOf(MPS_IID, KMH_IID, MPH_IID))) }
        try {
            assertTrue(repository.start())
            reader.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            backend.sourceGeneration = Any() // isConnected remains true across the rebind.
            release.countDown()
            reader.join(2_000)
            assertFalse(reader.isAlive)
            assertTrue(readReleased.get())
            assertTrue(result.get().values.isEmpty())
            assertTrue(result.get().sourceAgeMillisByIid.isEmpty())
            assertEquals(listOf(MPS_IID, KMH_IID, MPH_IID).associateWith { errors.operationFailed },
                result.get().errors)
            assertEquals(1, backend.reads.get())

            backend.beforeReadReturn = null
            assertEquals(36.0, repository.read(setOf(KMH_IID)).values[KMH_IID])
        } finally {
            release.countDown()
            reader.join(2_000)
            repository.close()
        }
    }

    @Test
    fun rebindAfterReadButBeforePublicationInvalidatesWholeProjectedGroup() {
        val enteredClock = CountDownLatch(1)
        val releaseClock = CountDownLatch(1)
        val backend = SpeedBackend(CarPropertyValue(10.0f, 995L))
        val repository = AndroidCarPropertyVehicleStateRepository(
            speedTree(), speedBindings(), backend, errors,
            elapsedRealtimeMillis = {
                enteredClock.countDown()
                assertTrue(releaseClock.await(2, TimeUnit.SECONDS))
                1_000L
            },
        )
        val result = AtomicReference<VehicleStateResult>()
        val reader = Thread { result.set(repository.read(linkedSetOf(MPS_IID, KMH_IID, MPH_IID))) }
        try {
            assertTrue(repository.start())
            reader.start()
            assertTrue(enteredClock.await(2, TimeUnit.SECONDS))
            assertEquals(1, backend.reads.get())
            backend.sourceGeneration = Any()
            releaseClock.countDown()
            reader.join(2_000)
            assertFalse(reader.isAlive)
            assertTrue(result.get().values.isEmpty())
            assertTrue(result.get().sourceAgeMillisByIid.isEmpty())
            assertEquals(listOf(MPS_IID, KMH_IID, MPH_IID).associateWith { errors.operationFailed },
                result.get().errors)
        } finally {
            releaseClock.countDown()
            reader.join(2_000)
            repository.close()
        }
    }

    @Test
    fun projectedSubscriptionRetiresUnavailableSpeedAndReannouncesAfterReconnect() {
        val backend = SpeedBackend(CarPropertyValue(10.0f, 995L))
        val repository = repository(backend)
        val initial = CountDownLatch(1)
        val unavailable = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val observed = ConcurrentLinkedQueue<Map<Long, Any?>>()
        try {
            assertTrue(repository.start())
            val subscription = repository.subscribe(setOf(KMH_IID, MPH_IID),
                object : VehicleStateListener {
                    override fun onUpdate(update: VehicleStateUpdate) {
                        observed += update.values
                        if (observed.size == 1) initial.countDown() else recovered.countDown()
                    }

                    override fun onUnavailable(iids: Set<Long>) {
                        if (iids == setOf(KMH_IID, MPH_IID)) unavailable.countDown()
                    }
                })
            assertTrue(initial.await(2, TimeUnit.SECONDS))
            backend.connected = false
            assertTrue(unavailable.await(2, TimeUnit.SECONDS))
            assertEquals(1, observed.size)
            backend.connected = true
            backend.reading = CarPropertyValue(12.0f, 995L)
            assertTrue(recovered.await(2, TimeUnit.SECONDS))
            assertEquals(43.2, observed.last()[KMH_IID] as Double, 0.00000001)
            assertEquals(2, observed.size)
            subscription.close()
            repository.close()
            assertEquals(1, backend.closes.get())
        } finally {
            repository.close()
        }
    }

    @Test
    fun legacyAndMixedBindingsStillRejectDuplicatePropertyArea() {
        val backend = SpeedBackend()
        val legacy = AndroidCarPropertyCafBinding(MPS_IID, SPEED_PROPERTY, 0)
        val projected = speedBinding(KMH_IID, SpeedUnit.KILOMETERS_PER_HOUR)
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(legacy, AndroidCarPropertyCafBinding(KMH_IID, SPEED_PROPERTY, 0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(legacy, projected))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(projected, legacy))
        }
        assertEquals(0, backend.connects.get())
    }

    @Test
    fun projectionRejectsWrongVhalSourceAreaSourceUnitFormatAndUnknownCafUnit() {
        val backend = SpeedBackend()
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(speedBinding(MPS_IID, SpeedUnit.METERS_PER_SECOND)
                .copy(propertyId = SPEED_PROPERTY + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(speedBinding(MPS_IID, SpeedUnit.METERS_PER_SECOND)
                .copy(areaId = 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(speedBinding(MPS_IID, SpeedUnit.METERS_PER_SECOND)
                .copy(speedProjection = SpeedUnitProjection(
                    SpeedProvenance.STANDARD_ANDROID_VHAL_FLOAT,
                    SpeedUnit.KILOMETERS_PER_HOUR,
                    SpeedUnit.METERS_PER_SECOND))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(speedBinding(KMH_IID, SpeedUnit.KILOMETERS_PER_HOUR)),
                speedTree(kmhUnit = 0x4ffL))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(speedBinding(KMH_IID, SpeedUnit.KILOMETERS_PER_HOUR)),
                speedTree(kmhUnit = null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(speedBinding(KMH_IID, SpeedUnit.KILOMETERS_PER_HOUR)),
                speedTree(kmhUnit = 0x408.toDouble()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(speedBinding(KMH_IID, SpeedUnit.KILOMETERS_PER_HOUR)),
                speedTree(kmhFormat = 7L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(speedBinding(MPS_IID, SpeedUnit.METERS_PER_SECOND)),
                speedTree(mpsType = 0x30000045L)) // Older synthetic fixture is not iOS speed.
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(speedBinding(KMH_IID, SpeedUnit.KILOMETERS_PER_HOUR)),
                speedTree(kmhType = 0x30000021L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, listOf(speedBinding(MPH_IID, SpeedUnit.MILES_PER_HOUR)),
                speedTree(mphType = 0x30000046L))
        }
        assertEquals(0, backend.connects.get())
    }

    private fun repository(
        backend: SpeedBackend,
        bindings: List<AndroidCarPropertyCafBinding> = speedBindings(),
        tree: CafConfigTree = speedTree(),
        clockMillis: Long = 1_000L,
        intervalMillis: Long = 20L,
    ) = AndroidCarPropertyVehicleStateRepository(tree, bindings, backend, errors,
        AndroidCarPropertyCafPollingPolicy(intervalMillis = intervalMillis),
        elapsedRealtimeMillis = { clockMillis })

    private fun speedBindings() = listOf(
        speedBinding(MPS_IID, SpeedUnit.METERS_PER_SECOND),
        speedBinding(KMH_IID, SpeedUnit.KILOMETERS_PER_HOUR),
        speedBinding(MPH_IID, SpeedUnit.MILES_PER_HOUR),
    )

    private fun speedBinding(iid: Long, target: SpeedUnit) = AndroidCarPropertyCafBinding(
        iid, SPEED_PROPERTY, areaId = 0,
        speedProjection = SpeedUnitProjection(SpeedProvenance.STANDARD_ANDROID_VHAL_FLOAT,
            SpeedUnit.METERS_PER_SECOND, target),
    )

    @Suppress("UNCHECKED_CAST")
    private fun speedTree(
        kmhUnit: Any? = 0x408L,
        kmhFormat: Long = 9L,
        mpsType: Long = 0x30000021L,
        kmhType: Long = 0x30000046L,
        mphType: Long = 0x30000047L,
    ): CafConfigTree {
        val root = SyntheticCafTestFixture.mutableRaw()
        val accessories = root.getValue("accessories") as MutableList<MutableMap<String, Any?>>
        val services = accessories.single().getValue("services") as MutableList<MutableMap<String, Any?>>
        val characteristics = services.single().getValue("characteristics") as MutableList<MutableMap<String, Any?>>
        characteristics += linkedMapOf("type" to mpsType, "iid" to MPS_IID,
            "format" to 9L, "priority" to 0L, "units" to 0x407L, "initialValue" to 0.0)
        characteristics += linkedMapOf("type" to kmhType, "iid" to KMH_IID,
            "format" to kmhFormat, "priority" to 0L, "units" to kmhUnit,
            "initialValue" to if (kmhFormat == 9L) 0.0 else 0L)
        characteristics += linkedMapOf("type" to mphType, "iid" to MPH_IID,
            "format" to 9L, "priority" to 0L, "units" to 0x409L, "initialValue" to 0.0)
        return CafConfigTree.decode(root)
    }

    private class SpeedBackend(@Volatile var reading: CarPropertyValue? = null) : ReadOnlyCarPropertyBackend {
        @Volatile var connected = true
        override val isConnected: Boolean get() = connected
        @Volatile var sourceGeneration: Any? = Any()
        override val sourceIdentity: Any? get() = if (connected) sourceGeneration else null
        @Volatile var beforeReadReturn: (() -> Unit)? = null
        val reads = AtomicInteger()
        val connects = AtomicInteger()
        val closes = AtomicInteger()
        val addresses = mutableListOf<Pair<Int, Int>>()

        override fun connect(): Boolean { connects.incrementAndGet(); return true }
        override fun read(propertyId: Int): CarPropertyValue? = read(propertyId, 0)
        override fun read(propertyId: Int, areaId: Int): CarPropertyValue? {
            reads.incrementAndGet()
            synchronized(addresses) { addresses += propertyId to areaId }
            beforeReadReturn?.invoke()
            return reading
        }
        override fun close() { closes.incrementAndGet(); connected = false }
    }

    private companion object {
        const val SPEED_PROPERTY = VehicleTelemetrySnapshot.STANDARD_SPEED
        const val MPS_IID = 30L
        const val KMH_IID = 31L
        const val MPH_IID = 32L
    }
}
