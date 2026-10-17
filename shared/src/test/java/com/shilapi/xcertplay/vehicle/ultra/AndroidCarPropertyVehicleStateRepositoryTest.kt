package com.shilapi.xcertplay.vehicle.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import com.shilapi.xcertplay.airplay.rcs.caf.CafErrorPolicy
import com.shilapi.xcertplay.airplay.rcs.caf.SyntheticCafTestFixture
import com.shilapi.xcertplay.vehicle.CarPropertyValue
import com.shilapi.xcertplay.vehicle.ReadOnlyCarPropertyBackend
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCarPropertyVehicleStateRepositoryTest {
    private val errors = CafErrorPolicy(
        unknownIdentifier = -101L,
        invalidValue = -102L,
        operationFailed = -103L,
        generalFailure = -104L,
    )
    private val temperature = SyntheticCafTestFixture.TEMPERATURE_IID
    private val highPriority = SyntheticCafTestFixture.HIGH_PRIORITY_IID
    private val bindings = listOf(
        AndroidCarPropertyCafBinding(temperature, 0x11600703, areaId = 0),
        AndroidCarPropertyCafBinding(highPriority, 0x11400400, areaId = 0),
    )

    @Test
    fun authorizationRevokedDuringBlockingReadCannotPublishAndCannotRevive() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val granted = AtomicBoolean(true)
        val backend = FakeBackend().apply {
            isConnected = true
            sourceGeneration = Any()
            readBehavior = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                CarPropertyValue(21.5f, 950L)
            }
        }
        val repository = repository(backend, listOf(bindings.first()))
        repository.bindCurrentAuthorization(granted::get)
        val result = AtomicReference<VehicleStateResult>()
        val reader = Thread { result.set(repository.read(setOf(temperature))) }
        try {
            assertTrue(repository.start())
            reader.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            granted.set(false)
            release.countDown()
            reader.join(2_000)
            assertFalse(reader.isAlive)
            assertTrue(result.get().values.isEmpty())
            assertEquals(errors.operationFailed, result.get().errors[temperature])
            assertFalse(repository.isAuthorizationCurrent)
            granted.set(true)
            assertFalse(repository.isAuthorizationCurrent)
            assertEquals(errors.operationFailed,
                repository.read(setOf(temperature)).errors[temperature])
            assertEquals(1, backend.reads.get())
        } finally {
            release.countDown()
            reader.join(2_000)
            repository.close()
        }
    }

    @Test
    fun revokedAuthorizationInvalidatesSubscribedValueBeforeFurtherDelivery() {
        val granted = AtomicBoolean(true)
        val updated = CountDownLatch(1)
        val unavailable = CountDownLatch(1)
        val updates = AtomicInteger()
        val backend = FakeBackend().apply {
            isConnected = true
            sourceGeneration = Any()
            readBehavior = { CarPropertyValue(21.5f, 950L) }
        }
        val repository = repository(backend, listOf(bindings.first()))
        repository.bindCurrentAuthorization(granted::get)
        try {
            assertTrue(repository.start())
            val subscription = repository.subscribe(setOf(temperature),
                object : VehicleStateListener {
                    override fun onUpdate(update: VehicleStateUpdate) {
                        updates.incrementAndGet()
                        updated.countDown()
                    }

                    override fun onUnavailable(iids: Set<Long>) {
                        if (temperature in iids) unavailable.countDown()
                    }
                })
            try {
                assertTrue(updated.await(2, TimeUnit.SECONDS))
                granted.set(false)
                assertFalse(repository.isAuthorizationCurrent)
                assertTrue(unavailable.await(2, TimeUnit.SECONDS))
                val after = updates.get()
                Thread.sleep(60)
                assertEquals(after, updates.get())
            } finally {
                subscription.close()
            }
        } finally {
            repository.close()
        }
    }

    @Test
    fun readFailsClosedUntilConnectedAndCoversOnlyTheRequestedIids() {
        val backend = FakeBackend().apply {
            readBehavior = { id -> when (id) {
                0x11600703 -> CarPropertyValue(21.5f, 100L)
                0x11400400 -> CarPropertyValue(1, 100L)
                else -> null
            } }
        }
        val repository = repository(backend)
        try {
            val requested = linkedSetOf(
                temperature,
                highPriority,
                SyntheticCafTestFixture.ERROR_STATE_IID,
                999L,
            )
            assertEquals(requested, repository.read(requested).errors.keys)
            assertEquals(0, backend.reads.get())

            assertTrue(repository.start())
            assertEquals(errors.operationFailed, repository.read(setOf(temperature)).errors[temperature])
            assertEquals(0, backend.reads.get()) // Android Car binding is asynchronous.

            backend.isConnected = true
            val result = repository.read(requested)
            assertEquals(mapOf(temperature to 21.5, highPriority to 1L), result.values)
            assertEquals(
                mapOf(SyntheticCafTestFixture.ERROR_STATE_IID to errors.operationFailed,
                    999L to errors.unknownIdentifier),
                result.errors,
            )
            assertEquals(emptySet<Long>(), result.succeededIids)
            assertEquals(2, backend.reads.get())

            backend.isConnected = false
            val disconnected = repository.read(setOf(temperature))
            assertTrue(disconnected.values.isEmpty())
            assertEquals(errors.operationFailed, disconnected.errors[temperature])
        } finally {
            repository.close()
        }
    }

    @Test
    fun connectedManagerRebindRetiresAnInFlightOrdinarySample() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = FakeBackend().apply {
            isConnected = true
            sourceGeneration = Any()
            readBehavior = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                CarPropertyValue(21.5f, 950L)
            }
        }
        val repository = repository(backend, listOf(bindings.first()))
        val result = AtomicReference<VehicleStateResult>()
        val reader = Thread { result.set(repository.read(setOf(temperature))) }
        try {
            assertTrue(repository.start())
            reader.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            backend.sourceGeneration = Any()
            release.countDown()
            reader.join(2_000)
            assertFalse(reader.isAlive)
            assertTrue(result.get().values.isEmpty())
            assertTrue(result.get().sourceAgeMillisByIid.isEmpty())
            assertEquals(errors.operationFailed, result.get().errors[temperature])
        } finally {
            release.countDown()
            reader.join(2_000)
            repository.close()
        }
    }

    @Test
    fun sourceAcquisitionAgeUsesAndroidElapsedRealtimeAndNeverInventsUnknownAge() {
        val clockMillis = AtomicLong(1_000L)
        val backend = FakeBackend().apply {
            isConnected = true
            readBehavior = { id -> when (id) {
                0x11600703 -> CarPropertyValue(21.5f, 950L)
                0x11400400 -> CarPropertyValue(1, 1_001L)
                else -> null
            } }
        }
        val repository = repository(backend, elapsedRealtimeMillis = clockMillis::get)
        try {
            assertTrue(repository.start())
            val result = repository.read(setOf(temperature, highPriority))
            assertEquals(mapOf(temperature to 21.5, highPriority to 1L), result.values)
            assertEquals(mapOf(temperature to 50L, highPriority to -1L),
                result.sourceAgeMillisByIid)
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (result.sourceAgeMillisByIid as MutableMap<Long, Long>).clear()
            }

            backend.readBehavior = { id -> when (id) {
                0x11600703 -> CarPropertyValue(21.5f, null)
                0x11400400 -> CarPropertyValue(1, -1L)
                else -> null
            } }
            assertTrue(repository.read(setOf(temperature, highPriority))
                .sourceAgeMillisByIid.isEmpty())

            backend.readBehavior = { CarPropertyValue(21.5f, 1L) }
            clockMillis.set(Long.MAX_VALUE)
            assertEquals(Long.MAX_VALUE - 1,
                repository.read(setOf(temperature)).sourceAgeMillisByIid[temperature])
            clockMillis.set(-1L)
            assertTrue(repository.read(setOf(temperature)).sourceAgeMillisByIid.isEmpty())
        } finally {
            repository.close()
        }
    }

    @Test
    fun invalidNumbersExceptionsAndAbsentPropertyNeverBecomeSuccess() {
        val backend = FakeBackend().apply { isConnected = true }
        val repository = repository(backend)
        try {
            assertTrue(repository.start())
            backend.readBehavior = { id ->
                if (id == 0x11400400) CarPropertyValue(256, null) else CarPropertyValue(21.5f, null)
            }
            val invalidRange = repository.read(setOf(highPriority))
            assertEquals(errors.invalidValue, invalidRange.errors[highPriority])
            assertTrue(invalidRange.values.isEmpty())

            backend.readBehavior = { CarPropertyValue((1L shl 53) + 1L, null) }
            assertEquals(errors.invalidValue, repository.read(setOf(temperature)).errors[temperature])

            backend.readBehavior = { CarPropertyValue(Double.NaN, null) }
            assertEquals(errors.invalidValue, repository.read(setOf(temperature)).errors[temperature])

            backend.readBehavior = { null }
            assertEquals(errors.operationFailed, repository.read(setOf(temperature)).errors[temperature])

            backend.readBehavior = { throw SecurityException("car permission denied") }
            assertEquals(errors.operationFailed, repository.read(setOf(temperature)).errors[temperature])
            assertEquals(mapOf(temperature to errors.invalidValue),
                repository.write(mapOf(temperature to 2)).errors)
            assertEquals(mapOf(5L to errors.invalidValue),
                repository.control(mapOf(5L to emptyMap<String, Any>())).errors)
        } finally {
            repository.close()
        }
    }

    @Test
    fun duplicateOrUnknownDeploymentBindingsAreRejectedBeforeUse() {
        val backend = FakeBackend()
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, bindings + AndroidCarPropertyCafBinding(temperature, 123, areaId = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, bindings + AndroidCarPropertyCafBinding(4L, 0x11600703, areaId = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, bindings + AndroidCarPropertyCafBinding(999L, 123, areaId = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(backend, bindings + AndroidCarPropertyCafBinding(4L, 0, areaId = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository(
                backend,
                listOf(AndroidCarPropertyCafBinding(
                    SyntheticCafTestFixture.STRING_SETTING_IID, 123, areaId = 0)),
                SyntheticCafTestFixture.constrainedWritableTree(),
            )
        }
        assertEquals(0, backend.connects.get())
    }

    @Test
    fun positiveUnsigned64SourceKeepsExactWireBitsButNegativeSignedSourceIsAmbiguous() {
        val unsignedIid = 9L
        val backend = FakeBackend().apply { isConnected = true }
        val repository = repository(
            backend,
            listOf(AndroidCarPropertyCafBinding(unsignedIid, 123, areaId = 0)),
            treeWithUnsigned64(unsignedIid),
        )
        try {
            assertTrue(repository.start())
            backend.readBehavior = { CarPropertyValue(BigInteger.ONE.shiftLeft(63), null) }
            val result = repository.read(setOf(unsignedIid))
            assertEquals(Long.MIN_VALUE, result.values[unsignedIid])
            assertTrue(result.errors.isEmpty())

            backend.readBehavior = { CarPropertyValue(-1L, null) }
            assertEquals(errors.invalidValue, repository.read(setOf(unsignedIid)).errors[unsignedIid])
        } finally {
            repository.close()
        }
    }

    @Test
    fun signedNegativeAndroidValueIsPreservedWhenProfileDeclaresSignedCharacteristic() {
        val signedIid = SyntheticCafTestFixture.ERROR_STATE_IID // INT64 in the test profile.
        val backend = FakeBackend().apply {
            isConnected = true
            readBehavior = { CarPropertyValue(-30L, null) }
        }
        val repository = repository(
            backend,
            listOf(AndroidCarPropertyCafBinding(signedIid, 124, areaId = 0)),
        )
        try {
            assertTrue(repository.start())
            val result = repository.read(setOf(signedIid))
            assertEquals(mapOf(signedIid to -30L), result.values)
            assertTrue(result.errors.isEmpty())
        } finally {
            repository.close()
        }
    }

    @Test
    fun signedBigIntegerUsesExactApi28CompatibleLongBounds() {
        val signedIid = SyntheticCafTestFixture.ERROR_STATE_IID
        val backend = FakeBackend().apply { isConnected = true }
        val repository = repository(
            backend,
            listOf(AndroidCarPropertyCafBinding(signedIid, 124, areaId = 0)),
        )
        try {
            assertTrue(repository.start())
            listOf(Long.MIN_VALUE, Long.MAX_VALUE).forEach { expected ->
                backend.readBehavior = { CarPropertyValue(BigInteger.valueOf(expected), null) }
                assertEquals(expected, repository.read(setOf(signedIid)).values[signedIid])
            }
            listOf(
                BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE),
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
            ).forEach { invalid ->
                backend.readBehavior = { CarPropertyValue(invalid, null) }
                assertEquals(errors.invalidValue, repository.read(setOf(signedIid)).errors[signedIid])
            }
        } finally {
            repository.close()
        }
    }

    @Test
    fun samePropertyInTwoExplicitAreasReturnsTwoIndependentCafValues() {
        val secondIid = 10L
        val propertyId = 123
        val backend = ZonedFakeBackend().apply {
            values[propertyId to 1] = CarPropertyValue(21.5f, null)
            values[propertyId to 2] = CarPropertyValue(19.0f, null)
        }
        val repository = AndroidCarPropertyVehicleStateRepository(
            treeWithSecondFloat(secondIid),
            listOf(
                AndroidCarPropertyCafBinding(temperature, propertyId, areaId = 1),
                AndroidCarPropertyCafBinding(secondIid, propertyId, areaId = 2),
            ),
            backend,
            errors,
            AndroidCarPropertyCafPollingPolicy(intervalMillis = 20),
        )
        try {
            assertTrue(repository.start())
            val result = repository.read(setOf(temperature, secondIid))
            assertEquals(mapOf(temperature to 21.5, secondIid to 19.0), result.values)
            assertTrue(result.errors.isEmpty())
            assertEquals(listOf(propertyId to 1, propertyId to 2), backend.calls.toList())
        } finally {
            repository.close()
        }
    }

    @Test
    fun unsupportedAreaNeverFallsBackToLegacyAreaZero() {
        val backend = FakeBackend().apply {
            isConnected = true
            readBehavior = { CarPropertyValue(99.0f, null) }
        }
        val repository = repository(
            backend,
            listOf(AndroidCarPropertyCafBinding(temperature, 123, areaId = 2)),
        )
        try {
            assertTrue(repository.start())
            val result = repository.read(setOf(temperature))
            assertTrue(result.values.isEmpty())
            assertEquals(mapOf(temperature to errors.operationFailed), result.errors)
            assertEquals(0, backend.reads.get())
        } finally {
            repository.close()
        }
    }

    @Test
    fun zoneSubscriptionsStayIsolatedAndClosingOneLeavesOtherActive() {
        val secondIid = 10L
        val propertyId = 123
        val backend = ZonedFakeBackend().apply {
            values[propertyId to 1] = CarPropertyValue(21.5f, null)
            values[propertyId to 2] = CarPropertyValue(19.0f, null)
        }
        val repository = AndroidCarPropertyVehicleStateRepository(
            treeWithSecondFloat(secondIid),
            listOf(
                AndroidCarPropertyCafBinding(temperature, propertyId, areaId = 1),
                AndroidCarPropertyCafBinding(secondIid, propertyId, areaId = 2),
            ),
            backend,
            errors,
            AndroidCarPropertyCafPollingPolicy(intervalMillis = 20),
        )
        val leftInitial = CountDownLatch(1)
        val leftChanged = CountDownLatch(1)
        val rightInitial = CountDownLatch(1)
        val rightChanged = CountDownLatch(1)
        val leftValues = ConcurrentLinkedQueue<Any?>()
        val rightValues = ConcurrentLinkedQueue<Any?>()
        try {
            assertTrue(repository.start())
            val left = repository.subscribe(setOf(temperature)) { update ->
                leftValues += update.values.getValue(temperature)
                if (leftValues.size == 1) leftInitial.countDown() else leftChanged.countDown()
            }
            val right = repository.subscribe(setOf(secondIid)) { update ->
                rightValues += update.values.getValue(secondIid)
                if (rightValues.size == 1) rightInitial.countDown() else rightChanged.countDown()
            }
            assertTrue(leftInitial.await(2, TimeUnit.SECONDS))
            assertTrue(rightInitial.await(2, TimeUnit.SECONDS))

            backend.values[propertyId to 1] = CarPropertyValue(22.5f, null)
            assertTrue(leftChanged.await(2, TimeUnit.SECONDS))
            val rightReadsAtLeftChange = backend.calls.count { it == (propertyId to 2) }
            awaitAreaReads(backend, propertyId to 2, rightReadsAtLeftChange + 2)
            assertEquals(listOf(19.0), rightValues.toList())

            left.close()
            val leftCount = leftValues.size
            backend.values[propertyId to 1] = CarPropertyValue(23.5f, null)
            backend.values[propertyId to 2] = CarPropertyValue(20.0f, null)
            assertTrue(rightChanged.await(2, TimeUnit.SECONDS))
            assertEquals(leftCount, leftValues.size)
            assertEquals(listOf(19.0, 20.0), rightValues.toList())
            right.close()
        } finally {
            repository.close()
        }
    }

    @Test
    fun subscriptionPollsOffCallerThreadReportsChangedValidValuesAndStopsOnClose() {
        val backend = FakeBackend().apply {
            isConnected = true
            readBehavior = { CarPropertyValue(21.5f, null) }
        }
        val repository = repository(backend, listOf(bindings.first()))
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val third = CountDownLatch(1)
        val callbacks = ConcurrentLinkedQueue<Pair<String, Any?>>()
        try {
            assertTrue(repository.start())
            val callerThread = Thread.currentThread().name
            val subscription = repository.subscribe(setOf(temperature)) { update ->
                callbacks += Thread.currentThread().name to update.values[temperature]
                when (callbacks.size) {
                    1 -> first.countDown()
                    2 -> second.countDown()
                    3 -> third.countDown()
                }
            }
            assertTrue(first.await(2, TimeUnit.SECONDS))
            assertEquals(21.5, callbacks.first().second)
            assertFalse(callbacks.first().first == callerThread)

            // The unchanged state is coalesced; an invalid sample emits no false update.
            backend.readBehavior = { CarPropertyValue(Double.NaN, null) }
            awaitReads(backend, minimum = backend.reads.get() + 2)
            assertEquals(1, callbacks.size)
            backend.readBehavior = { CarPropertyValue(21.5f, null) }
            assertTrue(second.await(2, TimeUnit.SECONDS))
            assertEquals(21.5, callbacks.last().second) // recovery re-announces the value.

            backend.readBehavior = { CarPropertyValue(22.5f, null) }
            assertTrue(third.await(2, TimeUnit.SECONDS))
            assertEquals(22.5, callbacks.last().second)

            subscription.close()
            val atClose = callbacks.size
            backend.readBehavior = { CarPropertyValue(23.5f, null) }
            Thread.sleep(100)
            assertEquals(atClose, callbacks.size)
        } finally {
            repository.close()
        }
    }

    @Test
    fun closeRaceSuppressesPendingPollDeliveryAndBackendCloseCanBeRetried() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = FakeBackend().apply {
            isConnected = true
            readBehavior = {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                CarPropertyValue(21.5f, null)
            }
            closeBehavior = { if (closes.get() == 1) throw IOException("transient cleanup failure") }
        }
        val repository = repository(backend, listOf(bindings.first()))
        val callbacks = AtomicInteger()
        assertTrue(repository.start())
        val subscription = repository.subscribe(setOf(temperature)) { callbacks.incrementAndGet() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        subscription.close()
        assertThrows(IOException::class.java) { repository.close() }
        release.countDown()
        repository.close()
        Thread.sleep(60)
        assertEquals(0, callbacks.get())
        assertEquals(2, backend.closes.get())
        assertFalse(repository.start())
        assertEquals(errors.operationFailed, repository.read(setOf(temperature)).errors[temperature])
    }

    @Test
    fun subscriptionCountAndIidLimitsAreBounded() {
        val backend = FakeBackend()
        val repository = AndroidCarPropertyVehicleStateRepository(
            SyntheticCafTestFixture.tree(),
            bindings,
            backend,
            errors,
            AndroidCarPropertyCafPollingPolicy(
                intervalMillis = 20,
                maxSubscriptions = 1,
                maxIidsPerSubscription = 1,
                maxDistinctSubscribedIids = 1,
            ),
        )
        try {
            assertThrows(IllegalArgumentException::class.java) {
                repository.subscribe(setOf(temperature, highPriority)) { }
            }
            val subscription = repository.subscribe(setOf(temperature)) { }
            assertThrows(IllegalArgumentException::class.java) {
                repository.subscribe(setOf(highPriority)) { }
            }
            subscription.close()
            repository.subscribe(setOf(highPriority)) { }.close()
        } finally {
            repository.close()
        }
    }

    private fun repository(
        backend: FakeBackend,
        selectedBindings: List<AndroidCarPropertyCafBinding> = bindings,
        tree: CafConfigTree = SyntheticCafTestFixture.tree(),
        elapsedRealtimeMillis: () -> Long = { 1_000L },
    ) = AndroidCarPropertyVehicleStateRepository(
        tree,
        selectedBindings,
        backend,
        errors,
        AndroidCarPropertyCafPollingPolicy(intervalMillis = 20),
        elapsedRealtimeMillis,
    )

    @Suppress("UNCHECKED_CAST")
    private fun treeWithUnsigned64(iid: Long): CafConfigTree {
        val root = SyntheticCafTestFixture.mutableRaw()
        val accessories = root.getValue("accessories") as MutableList<MutableMap<String, Any?>>
        val services = accessories.single().getValue("services") as MutableList<MutableMap<String, Any?>>
        val characteristics = services.single().getValue("characteristics") as MutableList<MutableMap<String, Any?>>
        characteristics += linkedMapOf(
            "type" to 0x00000000FF000004L,
            "iid" to iid,
            "format" to 4L,
            "priority" to 0L,
            "initialValue" to 0L,
        )
        return CafConfigTree.decode(root)
    }

    @Suppress("UNCHECKED_CAST")
    private fun treeWithSecondFloat(iid: Long): CafConfigTree {
        val root = SyntheticCafTestFixture.mutableRaw()
        val accessories = root.getValue("accessories") as MutableList<MutableMap<String, Any?>>
        val services = accessories.single().getValue("services") as MutableList<MutableMap<String, Any?>>
        val characteristics = services.single().getValue("characteristics") as MutableList<MutableMap<String, Any?>>
        characteristics += linkedMapOf(
            "type" to 0x00000000FF000005L,
            "iid" to iid,
            "format" to 9L,
            "priority" to 0L,
            "initialValue" to 19.0,
        )
        return CafConfigTree.decode(root)
    }

    private fun awaitReads(backend: FakeBackend, minimum: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (backend.reads.get() < minimum && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Expected at least $minimum backend reads", backend.reads.get() >= minimum)
    }

    private fun awaitAreaReads(backend: ZonedFakeBackend, address: Pair<Int, Int>, minimum: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (backend.calls.count { it == address } < minimum && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertTrue("Expected at least $minimum reads of $address",
            backend.calls.count { it == address } >= minimum)
    }

    private class FakeBackend : ReadOnlyCarPropertyBackend {
        @Volatile override var isConnected = false
        @Volatile var sourceGeneration: Any? = null
        override val sourceIdentity: Any? get() = if (isConnected) sourceGeneration else null
        @Volatile var readBehavior: (Int) -> CarPropertyValue? = { null }
        @Volatile var closeBehavior: () -> Unit = { }
        val reads = AtomicInteger()
        val connects = AtomicInteger()
        val closes = AtomicInteger()

        override fun connect(): Boolean {
            connects.incrementAndGet()
            return true
        }

        override fun read(propertyId: Int): CarPropertyValue? {
            reads.incrementAndGet()
            return readBehavior(propertyId)
        }

        override fun close() {
            closes.incrementAndGet()
            closeBehavior()
        }
    }

    private class ZonedFakeBackend : ReadOnlyCarPropertyBackend {
        @Volatile override var isConnected = true
        val values = ConcurrentHashMap<Pair<Int, Int>, CarPropertyValue>()
        val calls = ConcurrentLinkedQueue<Pair<Int, Int>>()

        override fun connect() = true
        override fun read(propertyId: Int): CarPropertyValue? = read(propertyId, areaId = 0)
        override fun read(propertyId: Int, areaId: Int): CarPropertyValue? {
            calls += propertyId to areaId
            return values[propertyId to areaId]
        }
        override fun close() { isConnected = false }
    }
}
