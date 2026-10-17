package com.shilapi.xcertplay.vehicle.ultra.instrument

import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import com.shilapi.xcertplay.airplay.rcs.caf.CafFormatValue
import com.shilapi.xcertplay.vehicle.ultra.VehicleControlResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateListener
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateRepository
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateReadSource
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateSubscription
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateUpdate
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneComponentAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneLayoutAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneSlotAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneZoneAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.Display
import com.shilapi.xcertplay.vehicle.ultra.theme.ZoneRegion
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentSignalBundleTest {
    @Test
    fun readOnlySourceRevocationCannotLeaveLastAvailableSampleVisible() {
        val repository = FakeRepository(cafTree())
        repository.readResult = freshResult(values = mapOf(
            100L to 12.5, 101L to 45.0, 102L to 27.9,
        ))
        var current = true
        val source = object : VehicleStateReadSource {
            override val configTree get() = repository.configTree
            override val isCurrent get() = current
            override fun read(iids: Set<Long>) = repository.read(iids)
            override fun subscribe(iids: Set<Long>, listener: VehicleStateListener) =
                repository.subscribe(iids, listener)
        }
        val bundle = InstrumentSignalBundle.bind(themeDisplay(), speedSpec(), source)
        try {
            assertTrue(bundle.refreshNow() is InstrumentSignalBundleState.Available)
            current = false
            assertEquals(InstrumentSignalUnavailableReason.CONFIG_CHANGED,
                (bundle.snapshot() as InstrumentSignalBundleState.Unavailable).reason)
            assertEquals(InstrumentSignalUnavailableReason.CONFIG_CHANGED,
                (bundle.refreshNow() as InstrumentSignalBundleState.Unavailable).reason)
            assertEquals(0, repository.writeCalls + repository.controlCalls)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun speedTripletUsesThreeExplicitCharacteristicsWithoutConversion() {
        val repository = FakeRepository(cafTree())
        repository.readResult = freshResult(values = mapOf(
            100L to 12.5, 101L to 45.0, 102L to 27.9,
        ))
        val clock = MutableClock()
        val bundle = InstrumentSignalBundle.bind(
            themeDisplay(), speedSpec(), repository, clock,
        )
        try {
            val state = bundle.refreshNow() as InstrumentSignalBundleState.Available
            assertEquals(setOf(100L, 101L, 102L), repository.lastReadIids)
            assertEquals(CafFormatValue.Floating(12.5),
                (state.values.getValue(InstrumentSignalRole.SPEED) as InstrumentSignalReading.Numeric).value)
            assertEquals(CafFormatValue.Floating(45.0),
                (state.values.getValue(InstrumentSignalRole.SPEED_KMH) as InstrumentSignalReading.Numeric).value)
            assertEquals(CafFormatValue.Floating(27.9),
                (state.values.getValue(InstrumentSignalRole.SPEED_MPH) as InstrumentSignalReading.Numeric).value)
            assertEquals(InstrumentNumericMeaning.PROFILE_SPEED,
                (state.values.getValue(InstrumentSignalRole.SPEED) as InstrumentSignalReading.Numeric).meaning)
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (state.values as MutableMap<InstrumentSignalRole, InstrumentSignalReading>).clear()
            }
            assertEquals(0, repository.writeCalls + repository.controlCalls)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun rpmValueAndStateAreSeparateAndInvalidCafStateFailsClosed() {
        val repository = FakeRepository(cafTree())
        repository.readResult = freshResult(values = mapOf(103L to 2400.0, 104L to 1L))
        val bundle = InstrumentSignalBundle.bind(themeDisplay("rpm"), rpmSpec(), repository)
        try {
            val available = bundle.refreshNow() as InstrumentSignalBundleState.Available
            assertEquals("running", (available.values.getValue(InstrumentSignalRole.RPM_STATE)
                as InstrumentSignalReading.State).name)
            repository.readResult = freshResult(values = mapOf(103L to 2400.0, 104L to 9L))
            assertEquals(InstrumentSignalUnavailableReason.INVALID_VALUE,
                (bundle.refreshNow() as InstrumentSignalBundleState.Unavailable).reason)
            assertEquals(0, repository.writeCalls + repository.controlCalls)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun profileUnmappedStateFailsClosedEvenWithoutCafValidValues() {
        val repository = FakeRepository(cafTree(stateHasValidValues = false))
        val bundle = InstrumentSignalBundle.bind(themeDisplay("rpm"), rpmSpec(), repository)
        try {
            repository.readResult = freshResult(values = mapOf(103L to 2400.0, 104L to 9L))
            assertEquals(InstrumentSignalUnavailableReason.UNKNOWN_STATE,
                (bundle.refreshNow() as InstrumentSignalBundleState.Unavailable).reason)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun nativeIndicatorUsesAnExplicitStateAndRejectsPhoneMessageStyle() {
        val repository = FakeRepository(cafTree())
        repository.readResult = freshResult(values = mapOf(104L to 1L))
        val spec = InstrumentSignalBundleSpec(
            address("warning", ZoneRegion.ZONE_1),
            InstrumentSignalBundleShape.INDICATOR_STATE,
            listOf(InstrumentSignalSource(
                InstrumentSignalRole.INDICATOR_STATE,
                InstrumentCafCharacteristicPin(2, 200, 104, 1004, CafCharacteristicFormat.UINT8),
                InstrumentSignalSemantics.State(listOf(
                    InstrumentStateCode(0L, "off"),
                    InstrumentStateCode(1L, "on"),
                )),
            )),
            1_000,
        )
        val bundle = InstrumentSignalBundle.bind(
            themeDisplay("warning", instrumentKind = "indicator"), spec, repository,
        )
        try {
            assertEquals("on", ((bundle.refreshNow() as InstrumentSignalBundleState.Available)
                .values.getValue(InstrumentSignalRole.INDICATOR_STATE) as
                    InstrumentSignalReading.State).name)
        } finally {
            bundle.close()
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstrumentSignalBundle.bind(
                themeDisplay("warning", instrumentKind = "indicator", style = "message"),
                spec, repository,
            )
        }
    }

    @Test
    fun partialOrErroneousReadNeverPublishesPartialGaugeData() {
        val repository = FakeRepository(cafTree())
        val bundle = InstrumentSignalBundle.bind(themeDisplay(), speedSpec(), repository)
        try {
            repository.readResult = freshResult(values = mapOf(100L to 2.0, 101L to 7.2))
            assertEquals(InstrumentSignalUnavailableReason.INCOMPLETE_READ,
                (bundle.refreshNow() as InstrumentSignalBundleState.Unavailable).reason)
            repository.readResult = freshResult(
                values = mapOf(100L to 2.0, 101L to 7.2), errors = mapOf(102L to -1L),
            )
            assertEquals(InstrumentSignalUnavailableReason.INCOMPLETE_READ,
                (bundle.refreshNow() as InstrumentSignalBundleState.Unavailable).reason)
            repository.readResult = freshResult(
                values = mapOf(100L to Double.NaN, 101L to 7.2, 102L to 4.5),
            )
            assertEquals(InstrumentSignalUnavailableReason.INVALID_VALUE,
                (bundle.refreshNow() as InstrumentSignalBundleState.Unavailable).reason)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun slowCompleteReadCannotBeRestampedAsFresh() {
        val repository = FakeRepository(cafTree())
        repository.readResult = freshResult(values = mapOf(
            100L to 12.0, 101L to 43.2, 102L to 26.8,
        ))
        val clock = MutableClock()
        repository.readHook = { clock.nanos += TimeUnit.MILLISECONDS.toNanos(1_001) }
        val bundle = InstrumentSignalBundle.bind(themeDisplay(), speedSpec(), repository, clock)
        try {
            assertEquals(InstrumentSignalUnavailableReason.STALE,
                (bundle.refreshNow() as InstrumentSignalBundleState.Unavailable).reason)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun everyGaugeRoleNeedsKnownNonFutureSourceAcquisitionAge() {
        val repository = FakeRepository(cafTree())
        val values = mapOf(100L to 12.0, 101L to 43.2, 102L to 26.8)
        val bundle = InstrumentSignalBundle.bind(themeDisplay(), speedSpec(), repository, MutableClock())
        try {
            val invalidAges = listOf(
                emptyMap(), // A quick read of a cached value does not establish acquisition.
                mapOf(100L to 0L, 101L to 0L), // One missing role invalidates the whole bundle.
                mapOf(100L to -1L, 101L to 0L, 102L to 0L), // Future source event.
                mapOf(100L to 0L, 101L to 1_001L, 102L to 0L), // Mixed fresh and stale.
            )
            invalidAges.forEach { ages ->
                repository.readResult = VehicleStateResult(values, sourceAgeMillisByIid = ages)
                assertEquals(InstrumentSignalUnavailableReason.STALE,
                    (bundle.refreshNow() as InstrumentSignalBundleState.Unavailable).reason)
                assertTrue(bundle.snapshot() is InstrumentSignalBundleState.Unavailable)
            }
            repository.readResult = VehicleStateResult(
                values,
                sourceAgeMillisByIid = mapOf(100L to 999L, 101L to 250L, 102L to 0L),
            )
            val available = bundle.refreshNow() as InstrumentSignalBundleState.Available
            assertEquals(999L, available.sourceAgeMillisByRole.getValue(InstrumentSignalRole.SPEED))
        } finally {
            bundle.close()
        }
    }

    @Test
    fun snapshotAddsElapsedBundleClockTimeToTheOldestSensorAcquisitionAge() {
        val repository = FakeRepository(cafTree())
        repository.readResult = VehicleStateResult(
            mapOf(100L to 12.0, 101L to 43.2, 102L to 26.8),
            sourceAgeMillisByIid = mapOf(100L to 0L, 101L to 650L, 102L to 400L),
        )
        val clock = MutableClock()
        val bundle = InstrumentSignalBundle.bind(themeDisplay(), speedSpec(), repository, clock)
        try {
            assertTrue(bundle.refreshNow() is InstrumentSignalBundleState.Available)
            clock.nanos += TimeUnit.MILLISECONDS.toNanos(350)
            assertTrue(bundle.snapshot() is InstrumentSignalBundleState.Available)
            clock.nanos += 1
            assertEquals(InstrumentSignalUnavailableReason.STALE,
                (bundle.snapshot() as InstrumentSignalBundleState.Unavailable).reason)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun refreshReturnRechecksFreshnessAfterBlockedAvailableListener() {
        val repository = FakeRepository(cafTree())
        val clock = MutableClock()
        val initialUnavailable = CountDownLatch(1)
        val availableEntered = CountDownLatch(1)
        val releaseListener = CountDownLatch(1)
        val returned = AtomicReference<InstrumentSignalBundleState>()
        val bundle = InstrumentSignalBundle.bind(
            themeDisplay(), speedSpec(maxAge = 10_000), repository, clock,
        )
        var readThread: Thread? = null
        try {
            bundle.subscribe { state ->
                if (state is InstrumentSignalBundleState.Unavailable &&
                    state.reason == InstrumentSignalUnavailableReason.INCOMPLETE_READ
                ) initialUnavailable.countDown()
                if (state is InstrumentSignalBundleState.Available) {
                    availableEntered.countDown()
                    releaseListener.await(10, TimeUnit.SECONDS)
                }
            }
            assertTrue(initialUnavailable.await(2, TimeUnit.SECONDS))
            repository.readResult = freshResult(values = mapOf(
                100L to 12.0, 101L to 43.2, 102L to 26.8,
            ))
            readThread = Thread { returned.set(bundle.refreshNow()) }.apply { start() }
            assertTrue(availableEntered.await(2, TimeUnit.SECONDS))
            clock.nanos += TimeUnit.MILLISECONDS.toNanos(10_001)
            assertEquals(InstrumentSignalUnavailableReason.STALE,
                (bundle.snapshot() as InstrumentSignalBundleState.Unavailable).reason)
            releaseListener.countDown()
            readThread?.join(2_000)
            assertEquals(InstrumentSignalUnavailableReason.STALE,
                (returned.get() as InstrumentSignalBundleState.Unavailable).reason)
        } finally {
            releaseListener.countDown()
            readThread?.join(2_000)
            bundle.close()
        }
    }

    @Test
    fun refreshReturnRechecksProfileTreeAfterAvailableListenerReplacesIt() {
        val repository = FakeRepository(cafTree())
        val initialUnavailable = CountDownLatch(1)
        val bundle = InstrumentSignalBundle.bind(
            themeDisplay(), speedSpec(maxAge = 10_000), repository,
        )
        try {
            bundle.subscribe { state ->
                if (state is InstrumentSignalBundleState.Unavailable &&
                    state.reason == InstrumentSignalUnavailableReason.INCOMPLETE_READ
                ) initialUnavailable.countDown()
                if (state is InstrumentSignalBundleState.Available) repository.configTree = cafTree()
            }
            assertTrue(initialUnavailable.await(2, TimeUnit.SECONDS))
            repository.readResult = freshResult(values = mapOf(
                100L to 12.0, 101L to 43.2, 102L to 26.8,
            ))
            assertEquals(InstrumentSignalUnavailableReason.CONFIG_CHANGED,
                (bundle.refreshNow() as InstrumentSignalBundleState.Unavailable).reason)
            assertEquals(InstrumentSignalUnavailableReason.CONFIG_CHANGED,
                (bundle.snapshot() as InstrumentSignalBundleState.Unavailable).reason)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun staleInitialSnapshotRecoversToSameValueFreshAcquisitionNotification() {
        val repository = FakeRepository(cafTree())
        repository.readResult = freshResult(values = mapOf(
            100L to 12.0, 101L to 43.2, 102L to 26.8,
        ))
        val clock = MutableClock()
        val bundle = InstrumentSignalBundle.bind(
            themeDisplay(), speedSpec(maxAge = 10_000), repository, clock,
        )
        val stale = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        try {
            assertTrue(bundle.refreshNow() is InstrumentSignalBundleState.Available)
            clock.nanos += TimeUnit.MILLISECONDS.toNanos(10_001)
            bundle.subscribe { state ->
                if (state is InstrumentSignalBundleState.Unavailable &&
                    state.reason == InstrumentSignalUnavailableReason.STALE
                ) stale.countDown()
                if (state is InstrumentSignalBundleState.Available) recovered.countDown()
            }
            assertTrue(stale.await(2, TimeUnit.SECONDS))
            assertTrue(recovered.await(2, TimeUnit.SECONDS))
            assertTrue(bundle.snapshot() is InstrumentSignalBundleState.Available)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun sameValueFreshReadRenewsAvailableObserverLease() {
        val repository = FakeRepository(cafTree())
        val clock = MutableClock()
        val initialUnavailable = CountDownLatch(1)
        val renewals = mutableListOf<Long>()
        val bundle = InstrumentSignalBundle.bind(
            themeDisplay(), speedSpec(maxAge = 10_000), repository, clock,
        )
        try {
            bundle.subscribe { state ->
                if (state is InstrumentSignalBundleState.Unavailable &&
                    state.reason == InstrumentSignalUnavailableReason.INCOMPLETE_READ
                ) initialUnavailable.countDown()
                if (state is InstrumentSignalBundleState.Available) {
                    synchronized(renewals) { renewals += state.readAtNanos }
                }
            }
            assertTrue(initialUnavailable.await(2, TimeUnit.SECONDS))
            repository.readResult = freshResult(values = mapOf(
                100L to 12.0, 101L to 43.2, 102L to 26.8,
            ))
            assertTrue(bundle.refreshNow() is InstrumentSignalBundleState.Available)
            clock.nanos += TimeUnit.MILLISECONDS.toNanos(500)
            assertTrue(bundle.refreshNow() is InstrumentSignalBundleState.Available)
            assertEquals(listOf(1_000_000_000L, 1_500_000_000L),
                synchronized(renewals) { renewals.toList() })
        } finally {
            bundle.close()
        }
    }

    @Test
    fun repositoryTreeReplacementCannotReuseTheOldCrosswalk() {
        val repository = FakeRepository(cafTree())
        repository.readResult = freshResult(values = mapOf(
            100L to 12.0, 101L to 43.2, 102L to 26.8,
        ))
        val bundle = InstrumentSignalBundle.bind(themeDisplay(), speedSpec(), repository)
        try {
            assertTrue(bundle.refreshNow() is InstrumentSignalBundleState.Available)
            repository.configTree = cafTree()
            assertEquals(InstrumentSignalUnavailableReason.CONFIG_CHANGED,
                (bundle.refreshNow() as InstrumentSignalBundleState.Unavailable).reason)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun treeReplacementInsideRepositoryReadNeverPublishesAvailable() {
        val repository = FakeRepository(cafTree())
        repository.readResult = freshResult(values = mapOf(
            100L to 12.0, 101L to 43.2, 102L to 26.8,
        ))
        repository.readHook = { repository.configTree = cafTree() }
        val availableCallbacks = AtomicInteger(0)
        val configChanged = CountDownLatch(1)
        val bundle = InstrumentSignalBundle.bind(themeDisplay(), speedSpec(), repository)
        try {
            bundle.subscribe { state ->
                if (state is InstrumentSignalBundleState.Available) {
                    availableCallbacks.incrementAndGet()
                }
                if (state is InstrumentSignalBundleState.Unavailable &&
                    state.reason == InstrumentSignalUnavailableReason.CONFIG_CHANGED
                ) configChanged.countDown()
            }
            assertTrue(configChanged.await(2, TimeUnit.SECONDS))
            assertEquals(0, availableCallbacks.get())
            assertEquals(InstrumentSignalUnavailableReason.CONFIG_CHANGED,
                (bundle.snapshot() as InstrumentSignalBundleState.Unavailable).reason)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun newBlockedReadCannotSuppressAlreadyPublishedUnavailableCallback() {
        val repository = FakeRepository(cafTree())
        repository.readResult = freshResult(values = mapOf(
            100L to 12.0, 101L to 43.2, 102L to 26.8,
        ))
        val initiallyAvailable = CountDownLatch(1)
        val firstUnavailableEntered = CountDownLatch(1)
        val releaseFirstCallback = CountDownLatch(1)
        val secondUnavailableDelivered = CountDownLatch(1)
        val blockedReadEntered = CountDownLatch(1)
        val releaseBlockedRead = CountDownLatch(1)
        val unavailableCallbacks = AtomicInteger(0)
        val bundle = InstrumentSignalBundle.bind(
            themeDisplay(), speedSpec(maxAge = 10_000), repository,
        )
        var recoveryThread: Thread? = null
        var blockedReadThread: Thread? = null
        try {
            bundle.subscribe { state ->
                when (state) {
                    is InstrumentSignalBundleState.Available -> initiallyAvailable.countDown()
                    is InstrumentSignalBundleState.Unavailable -> {
                        if (state.reason == InstrumentSignalUnavailableReason.REPOSITORY_UNAVAILABLE) {
                            when (unavailableCallbacks.incrementAndGet()) {
                                1 -> {
                                    firstUnavailableEntered.countDown()
                                    releaseFirstCallback.await(10, TimeUnit.SECONDS)
                                }
                                2 -> secondUnavailableDelivered.countDown()
                            }
                        }
                    }
                }
            }
            assertTrue(initiallyAvailable.await(2, TimeUnit.SECONDS))
            repository.listener!!.onUnavailable(setOf(100L))
            assertTrue(firstUnavailableEntered.await(2, TimeUnit.SECONDS))

            recoveryThread = Thread { bundle.refreshNow() }.apply { start() }
            assertTrue(waitForState(bundle) { it is InstrumentSignalBundleState.Available })
            repository.listener!!.onUnavailable(setOf(101L))
            assertEquals(InstrumentSignalUnavailableReason.REPOSITORY_UNAVAILABLE,
                (bundle.snapshot() as InstrumentSignalBundleState.Unavailable).reason)

            repository.readHook = {
                blockedReadEntered.countDown()
                releaseBlockedRead.await(10, TimeUnit.SECONDS)
            }
            blockedReadThread = Thread { bundle.refreshNow() }.apply { start() }
            assertTrue(blockedReadEntered.await(2, TimeUnit.SECONDS))

            releaseFirstCallback.countDown()
            assertTrue(secondUnavailableDelivered.await(2, TimeUnit.SECONDS))
            assertEquals(2, unavailableCallbacks.get())
        } finally {
            releaseFirstCallback.countDown()
            releaseBlockedRead.countDown()
            recoveryThread?.join(2_000)
            blockedReadThread?.join(2_000)
            bundle.close()
        }
    }

    private fun waitForState(
        bundle: InstrumentSignalBundle,
        condition: (InstrumentSignalBundleState) -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition(bundle.snapshot())) return true
            Thread.sleep(1)
        }
        return false
    }

    @Test
    fun staleAndUnavailableSubscriptionInvalidateWholeBundleAndClose() {
        val repository = FakeRepository(cafTree())
        repository.readResult = freshResult(values = mapOf(
            100L to 12.0, 101L to 43.2, 102L to 26.8,
        ))
        val clock = MutableClock()
        val states = mutableListOf<InstrumentSignalBundleState>()
        val initiallyAvailable = CountDownLatch(1)
        val unavailableNotified = CountDownLatch(1)
        val bundle = InstrumentSignalBundle.bind(
            themeDisplay(), speedSpec(maxAge = 10_000), repository, clock,
        )
        try {
            bundle.subscribe { state ->
                synchronized(states) { states += state }
                if (state is InstrumentSignalBundleState.Available) {
                    initiallyAvailable.countDown()
                }
                if (state is InstrumentSignalBundleState.Unavailable &&
                    state.reason == InstrumentSignalUnavailableReason.REPOSITORY_UNAVAILABLE
                ) unavailableNotified.countDown()
            }
            assertTrue(initiallyAvailable.await(2, TimeUnit.SECONDS))
            assertTrue(bundle.snapshot() is InstrumentSignalBundleState.Available)
            clock.nanos += TimeUnit.MILLISECONDS.toNanos(10_001)
            assertEquals(InstrumentSignalUnavailableReason.STALE,
                (bundle.snapshot() as InstrumentSignalBundleState.Unavailable).reason)
            repository.readResult = freshResult(errors = mapOf(
                100L to -1L, 101L to -1L, 102L to -1L,
            ))
            repository.listener!!.onUnavailable(setOf(101L))
            assertEquals(InstrumentSignalUnavailableReason.REPOSITORY_UNAVAILABLE,
                (bundle.snapshot() as InstrumentSignalBundleState.Unavailable).reason)
            assertTrue(unavailableNotified.await(2, TimeUnit.SECONDS))
            assertTrue(synchronized(states) { states.any {
                it is InstrumentSignalBundleState.Unavailable &&
                    it.reason == InstrumentSignalUnavailableReason.REPOSITORY_UNAVAILABLE
            } })
            repository.readResult = freshResult(values = mapOf(
                100L to 13.0, 101L to 46.8, 102L to 29.1,
            ))
            assertTrue(bundle.refreshNow() is InstrumentSignalBundleState.Available)
            assertEquals(setOf(100L, 101L, 102L), repository.subscribedIids)
        } finally {
            bundle.close()
        }
        assertTrue(repository.subscriptionClosed)
        assertEquals(InstrumentSignalUnavailableReason.CLOSED,
            (bundle.snapshot() as InstrumentSignalBundleState.Unavailable).reason)
    }

    @Test
    fun partialUpdateTriggersOneFullBundleRead() {
        val repository = FakeRepository(cafTree())
        repository.readResult = freshResult(values = mapOf(
            100L to 12.0, 101L to 43.2, 102L to 26.8,
        ))
        val bundle = InstrumentSignalBundle.bind(themeDisplay(), speedSpec(maxAge = 10_000), repository)
        val initial = CountDownLatch(1)
        val updated = CountDownLatch(1)
        try {
            bundle.subscribe { state ->
                if (state is InstrumentSignalBundleState.Available) {
                    val speed = (state.values.getValue(InstrumentSignalRole.SPEED) as
                        InstrumentSignalReading.Numeric).value
                    if (speed == CafFormatValue.Floating(12.0)) initial.countDown()
                    if (speed == CafFormatValue.Floating(13.0)) updated.countDown()
                }
            }
            assertTrue(initial.await(2, TimeUnit.SECONDS))
            repository.readResult = freshResult(values = mapOf(
                100L to 13.0, 101L to 46.8, 102L to 29.1,
            ))
            repository.listener!!.onUpdate(VehicleStateUpdate(mapOf(100L to 13.0)))
            assertTrue(updated.await(2, TimeUnit.SECONDS))
            assertEquals(setOf(100L, 101L, 102L), repository.lastReadIids)
            assertFalse(repository.subscriptionClosed)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun blockedRepositoryReadCannotPreventStaleNotification() {
        val repository = FakeRepository(cafTree())
        repository.readResult = freshResult(values = mapOf(
            100L to 12.0, 101L to 43.2, 102L to 26.8,
        ))
        val clock = MutableClock()
        val initial = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stale = CountDownLatch(1)
        val bundle = InstrumentSignalBundle.bind(
            themeDisplay(), speedSpec(maxAge = 100), repository, clock,
        )
        try {
            bundle.subscribe { state ->
                if (state is InstrumentSignalBundleState.Available) initial.countDown()
                if (state is InstrumentSignalBundleState.Unavailable &&
                    state.reason == InstrumentSignalUnavailableReason.STALE
                ) stale.countDown()
            }
            assertTrue(initial.await(2, TimeUnit.SECONDS))
            repository.readHook = {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            repository.listener!!.onUpdate(VehicleStateUpdate(mapOf(100L to 12.0)))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            clock.nanos += TimeUnit.MILLISECONDS.toNanos(101)
            assertTrue(stale.await(2, TimeUnit.SECONDS))
            assertEquals(InstrumentSignalUnavailableReason.STALE,
                (bundle.snapshot() as InstrumentSignalBundleState.Unavailable).reason)
        } finally {
            release.countDown()
            bundle.close()
        }
    }

    @Test
    fun failedSubscriptionCloseRetainsExactOwnerUntilRetrySucceeds() {
        val repository = FakeRepository(cafTree())
        repository.subscriptionCloseFailures = 1
        repository.readResult = freshResult(values = mapOf(
            100L to 12.0, 101L to 43.2, 102L to 26.8,
        ))
        val bundle = InstrumentSignalBundle.bind(themeDisplay(), speedSpec(), repository)
        bundle.subscribe { }

        assertThrows(IllegalStateException::class.java) { bundle.close() }
        assertFalse(bundle.isFullyRetired)
        assertFalse(repository.subscriptionClosed)
        assertEquals(1, repository.subscriptionCloseAttempts)
        assertEquals(InstrumentSignalUnavailableReason.CLOSED,
            (bundle.snapshot() as InstrumentSignalBundleState.Unavailable).reason)

        bundle.close()
        assertTrue(bundle.isFullyRetired)
        assertTrue(repository.subscriptionClosed)
        assertEquals(2, repository.subscriptionCloseAttempts)
    }

    @Test
    fun synchronousCloseDuringSubscriptionRegistrationStillRetiresReturnedLease() {
        val repository = FakeRepository(cafTree())
        val bundle = InstrumentSignalBundle.bind(themeDisplay(), speedSpec(), repository)
        repository.subscribeHook = { bundle.close() }

        bundle.subscribe { }

        assertTrue(bundle.isFullyRetired)
        assertTrue(repository.subscriptionClosed)
        assertEquals(1, repository.subscriptionCloseAttempts)
    }

    @Test
    fun observerCloseBeforeRepositoryRegistrationLeavesNoPhantomLease() {
        val repository = FakeRepository(cafTree())
        val bundle = InstrumentSignalBundle.bind(themeDisplay(), speedSpec(), repository)

        bundle.subscribe { bundle.close() }

        assertTrue(bundle.isFullyRetired)
        assertEquals(0, repository.subscribeCalls)
    }

    @Test
    fun exactThemeIdentifierZoneAndSlotAreRequired() {
        val repository = FakeRepository(cafTree())
        fun rejected(spec: InstrumentSignalBundleSpec, display: Display = themeDisplay()) =
            assertThrows(IllegalArgumentException::class.java) {
                InstrumentSignalBundle.bind(display, spec, repository)
            }
        rejected(speedSpec(address = address("speed", ZoneRegion.ZONE_2)))
        rejected(speedSpec(address = address("not-speed", ZoneRegion.ZONE_1)))
        rejected(speedSpec(address = address("speed", ZoneRegion.ZONE_1, slotIndex = 1)))
        rejected(speedSpec(), themeDisplay(local = false))
        rejected(speedSpec(), themeDisplay(style = "spinner"))
        assertEquals(0, repository.subscribeCalls)
    }

    @Test
    fun cafParentCharacteristicFormatUnitsAndRoleCoverageArePinned() {
        val repository = FakeRepository(cafTree())
        val valid = speedSources()
        fun rejected(sources: List<InstrumentSignalSource>) =
            assertThrows(IllegalArgumentException::class.java) {
                InstrumentSignalBundle.bind(
                    themeDisplay(), speedSpec(sources = sources), repository,
                )
            }
        rejected(valid.toMutableList().also { it[0] = it[0].copy(
            pin = it[0].pin.copy(serviceType = 999),
        ) })
        rejected(valid.toMutableList().also { it[1] = it[1].copy(
            pin = it[1].pin.copy(characteristicType = 999),
        ) })
        rejected(valid.toMutableList().also { it[1] = it[1].copy(
            pin = it[1].pin.copy(characteristicFormat = CafCharacteristicFormat.INT32),
        ) })
        rejected(valid.toMutableList().also { it[1] = it[1].copy(
            semantics = InstrumentSignalSemantics.Numeric(
                InstrumentNumericMeaning.KILOMETRES_PER_HOUR, "mph",
            ),
        ) })
        rejected(valid.toMutableList().also { it[1] = it[1].copy(
            semantics = InstrumentSignalSemantics.Numeric(
                InstrumentNumericMeaning.MILES_PER_HOUR, "km/h",
            ),
        ) })
        assertThrows(IllegalArgumentException::class.java) {
            speedSpec(sources = valid.dropLast(1))
        }
        assertEquals(0, repository.subscribeCalls)
    }

    @Test
    fun exactNumericCafSpeedUnitsAndLegacyAliasesBindToTheirOwnRoles() {
        val display = themeDisplay()
        val spec = speedSpec()
        InstrumentSignalBundle.validateDeclaration(display, spec, cafTree())
        val repository = FakeRepository(cafTree(speedUnits = listOf(
            0x407L, 0x408, BigInteger.valueOf(0x409L),
        )))
        InstrumentSignalBundle.validateDeclaration(display, spec, repository.configTree)
        repository.readResult = freshResult(values = mapOf(
            100L to 12.5, 101L to 45.0, 102L to 27.9,
        ))
        val bundle = InstrumentSignalBundle.bind(display, spec, repository)
        try {
            assertTrue(bundle.refreshNow() is InstrumentSignalBundleState.Available)
        } finally {
            bundle.close()
        }
    }

    @Test
    fun numericCafSpeedUnitsRejectWrongIdsTypesAndUnboundAliases() {
        val valid = listOf<Any?>(0x407L, 0x408L, 0x409L)
        val rejectedUnits = listOf(
            valid.toMutableList().also { it[0] = 0x408L },
            valid.toMutableList().also { it[1] = 0x409L },
            valid.toMutableList().also { it[2] = 0x407L },
            valid.toMutableList().also { it[0] = 0x40aL },
            valid.toMutableList().also { it[0] = "0x407" },
            valid.toMutableList().also { it[0] = "1031" },
            valid.toMutableList().also { it[0] = 0x407.toDouble() },
            valid.toMutableList().also { it[0] = null },
        )
        rejectedUnits.forEach { units ->
            assertThrows(IllegalArgumentException::class.java) {
                InstrumentSignalBundle.validateDeclaration(
                    themeDisplay(), speedSpec(), cafTree(speedUnits = units),
                )
            }
        }
        val wrongAlias = speedSources().toMutableList().also { it[0] = it[0].copy(
            semantics = InstrumentSignalSemantics.Numeric(
                InstrumentNumericMeaning.PROFILE_SPEED, "km/h",
            ),
        ) }
        assertThrows(IllegalArgumentException::class.java) {
            InstrumentSignalBundle.validateDeclaration(
                themeDisplay(), speedSpec(sources = wrongAlias), cafTree(speedUnits = valid),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstrumentSignalBundle.validateDeclaration(
                themeDisplay("rpm"), rpmSpec(), cafTree(rpmUnit = 0x407L),
            )
        }
    }

    @Test
    fun unmodeledCafStateChannelRejectsSourceBeforeSubscription() {
        val repository = FakeRepository(cafTree(withSpeedErrorChannel = true))
        assertThrows(IllegalArgumentException::class.java) {
            InstrumentSignalBundle.bind(themeDisplay(), speedSpec(), repository)
        }
        assertEquals(0, repository.subscribeCalls)
    }

    private fun speedSpec(
        address: InstrumentThemeSignalAddress = address("speed", ZoneRegion.ZONE_1),
        sources: List<InstrumentSignalSource> = speedSources(),
        maxAge: Long = 1_000,
    ) = InstrumentSignalBundleSpec(
        address, InstrumentSignalBundleShape.SPEED_TRIPLET, sources, maxAge,
    )

    private fun rpmSpec() = InstrumentSignalBundleSpec(
        address("rpm", ZoneRegion.ZONE_1),
        InstrumentSignalBundleShape.RPM_VALUE_AND_STATE,
        listOf(
            numeric(InstrumentSignalRole.RPM_VALUE, 103, 1003, "rpm",
                InstrumentNumericMeaning.REVOLUTIONS_PER_MINUTE),
            InstrumentSignalSource(
                InstrumentSignalRole.RPM_STATE,
                InstrumentCafCharacteristicPin(2, 200, 104, 1004, CafCharacteristicFormat.UINT8),
                InstrumentSignalSemantics.State(listOf(
                    InstrumentStateCode(0L, "stopped"),
                    InstrumentStateCode(1L, "running"),
                )),
            ),
        ),
        1_000,
    )

    private fun speedSources() = listOf(
        numeric(InstrumentSignalRole.SPEED, 100, 1000, "m/s",
            InstrumentNumericMeaning.PROFILE_SPEED),
        numeric(InstrumentSignalRole.SPEED_KMH, 101, 1001, "km/h",
            InstrumentNumericMeaning.KILOMETRES_PER_HOUR),
        numeric(InstrumentSignalRole.SPEED_MPH, 102, 1002, "mph",
            InstrumentNumericMeaning.MILES_PER_HOUR),
    )

    private fun numeric(
        role: InstrumentSignalRole, iid: Long, type: Long, unit: String,
        meaning: InstrumentNumericMeaning,
    ) = InstrumentSignalSource(
        role,
        InstrumentCafCharacteristicPin(2, 200, iid, type, CafCharacteristicFormat.FLOAT),
        InstrumentSignalSemantics.Numeric(meaning, unit),
    )

    private fun address(
        dataIdentifier: String, region: ZoneRegion?, slotIndex: Int = 0,
    ): InstrumentThemeSignalAddress {
        val layout = CarPlayThemeSceneLayoutAddress(0, "main")
        val zone = CarPlayThemeSceneZoneAddress(layout, 0, "cluster-zone")
        val component = CarPlayThemeSceneComponentAddress(zone, 0, "primary")
        return InstrumentThemeSignalAddress(
            dataIdentifier, region,
            CarPlayThemeSceneSlotAddress(component, slotIndex, "dial"),
        )
    }

    private fun themeDisplay(
        identifier: String = "speed",
        local: Boolean = true,
        style: String = "circular",
        instrumentKind: String = "gauge",
    ): Display {
        val config = mapOf(
            "dataIdentifier" to identifier,
            "range" to listOf(0.0, 300.0),
            "iconName" to "dial",
            "minLabel" to "0",
            "minLabelColor" to "white",
            "maxLabel" to "300",
            "maxLabelColor" to "white",
            "tintColors" to emptyList<Any>(),
            "backgroundTintColors" to emptyList<Any>(),
        )
        val kindPayload: Map<String, Any?> = if (instrumentKind == "gauge") {
            mapOf("configuration" to mapOf(
                "style" to if (style == "circular") mapOf(
                    "circular" to mapOf("config" to config),
                ) else mapOf("spinner" to emptyMap<String, Any>()),
                "rendersLocally" to local,
                "visibilityConfiguration" to emptyMap<String, Any>(),
            ))
        } else {
            require(instrumentKind == "indicator")
            mapOf("configuration" to mapOf(
                "dataIdentifier" to identifier,
                "style" to if (style == "message") mapOf(
                    "message" to emptyMap<String, Any>(),
                ) else mapOf("critical" to mapOf("configuration" to emptyMap<String, Any>())),
                "presentationEdge" to "bottom",
                "containerInsets" to emptyMap<String, Any>(),
            ))
        }
        return CarPlayThemeSchema.parseDisplay(mapOf(
            "id" to "cluster-display", "type" to "cluster",
            "size" to listOf(800, 480), "density" to 1.0,
            "allowManufacturerAppIcon" to false,
            "requiresDarkAppearance" to false,
            "layouts" to listOf(mapOf(
                "id" to "main", "layoutType" to "primary",
                "backgroundColor" to "black", "safeAreaInsets" to emptyMap<String, Any>(),
                "driveModeThemeConfigurations" to emptyMap<String, Any>(),
                "zones" to listOf(mapOf(
                    "id" to "cluster-zone", "frame" to listOf(listOf(0, 0), listOf(800, 480)),
                    "type" to mapOf("single" to emptyMap<String, Any>()),
                    "zoneRegion" to "zone1",
                    "components" to listOf(mapOf(
                        "id" to "primary", "type" to "primary", "size" to listOf(800, 480),
                        "slots" to listOf(mapOf(
                            "style" to mapOf("circular" to emptyMap<String, Any>()),
                            "frame" to listOf(listOf(0, 0), listOf(200, 200)),
                            "zIndex" to 0, "positionId" to "dial",
                            "instrument" to mapOf(
                                "kind" to mapOf(instrumentKind to kindPayload),
                                "backgroundColor" to "black",
                            ),
                        )),
                    )),
                )),
            )),
        ))
    }

    private fun cafTree(
        stateHasValidValues: Boolean = true,
        withSpeedErrorChannel: Boolean = false,
        speedUnits: List<Any?> = listOf("m/s", "km/h", "mph"),
        rpmUnit: Any? = "rpm",
    ): CafConfigTree = CafConfigTree.decode(mapOf(
        "accessories" to listOf(mapOf(
            "type" to 10L, "iid" to 1L,
            "services" to listOf(mapOf(
                "type" to 200L, "iid" to 2L,
                "characteristics" to listOf(
                    characteristic(100, 1000, 9, speedUnits[0]).let {
                        if (withSpeedErrorChannel) it.plus("iidError" to 105L) else it
                    },
                    characteristic(101, 1001, 9, speedUnits[1]),
                    characteristic(102, 1002, 9, speedUnits[2]),
                    characteristic(103, 1003, 9, rpmUnit),
                    characteristic(104, 1004, 1, null).let {
                        if (stateHasValidValues) it.plus("validValues" to listOf(0L, 1L)) else it
                    },
                ) + if (withSpeedErrorChannel) listOf(characteristic(105, 1005, 8, null))
                else emptyList(),
            )),
        )),
    ))

    private fun characteristic(iid: Long, type: Long, format: Long, units: Any?) =
        linkedMapOf<String, Any?>("type" to type, "iid" to iid, "format" to format).also {
            if (units != null) it["units"] = units
        }

    private class MutableClock(@Volatile var nanos: Long = 1_000_000_000L) : InstrumentSignalClock {
        override fun nowNanos(): Long = nanos
    }

    private fun freshResult(
        values: Map<Long, Any?> = emptyMap(),
        errors: Map<Long, Long> = emptyMap(),
    ) = VehicleStateResult(
        values = values,
        errors = errors,
        sourceAgeMillisByIid = values.keys.associateWith { 0L },
    )

    private class FakeRepository(override var configTree: CafConfigTree) : VehicleStateRepository {
        @Volatile var readResult = VehicleStateResult()
        @Volatile var readHook: (() -> Unit)? = null
        @Volatile var lastReadIids = emptySet<Long>()
        @Volatile var subscribedIids = emptySet<Long>()
        @Volatile var listener: VehicleStateListener? = null
        var subscribeCalls = 0
        var writeCalls = 0
        var controlCalls = 0
        var subscriptionClosed = false
        var subscriptionCloseAttempts = 0
        var subscriptionCloseFailures = 0
        var subscribeHook: (() -> Unit)? = null

        override fun read(iids: Set<Long>): VehicleStateResult {
            lastReadIids = iids.toSet()
            readHook?.invoke()
            return readResult
        }

        override fun subscribe(iids: Set<Long>, listener: VehicleStateListener): VehicleStateSubscription {
            subscribeCalls++
            subscribedIids = iids.toSet()
            this.listener = listener
            subscribeHook?.invoke()
            return VehicleStateSubscription {
                subscriptionCloseAttempts++
                if (subscriptionCloseFailures > 0) {
                    subscriptionCloseFailures--
                    throw IllegalStateException("Synthetic close failure")
                }
                subscriptionClosed = true
            }
        }

        override fun write(values: Map<Long, Any?>): VehicleStateResult {
            writeCalls++
            throw AssertionError("Instrument binder must not write")
        }

        override fun control(values: Map<Long, Any?>): VehicleControlResult {
            controlCalls++
            throw AssertionError("Instrument binder must not control")
        }
    }
}
