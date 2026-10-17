package com.shilapi.xcertplay.airplay.video

import com.shilapi.xcertplay.vehicle.CarPropertyBooleanValue
import com.shilapi.xcertplay.vehicle.CarPropertyDataStore
import com.shilapi.xcertplay.vehicle.CarPropertySupport
import com.shilapi.xcertplay.vehicle.CarPropertyValue
import com.shilapi.xcertplay.vehicle.CarPropertyVehicleProvider
import com.shilapi.xcertplay.vehicle.ReadOnlyCarPropertyBackend
import com.shilapi.xcertplay.vehicle.VehicleDataConfig
import com.shilapi.xcertplay.vehicle.VehiclePlatform
import com.shilapi.xcertplay.vehicle.VehicleTelemetrySnapshot
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class VehicleVideoParkAuthorityTest {
    @Test fun parkToDriveRevokesTheExactSessionLeaseAndStopsBeforePhoneDisallow() {
        val fixture = Fixture()
        try {
            val sample = fixture.awaitFirstSample()
            awaitCondition { fixture.backend.parkSpeedReads.get() >= 3 }
            val session = VehicleVideoSessionIdentity(1)
            val events = CopyOnWriteArrayList<String>()
            val revoked = CountDownLatch(1)
            val authority = fixture.authority(session, sample.sourceIdentity, sample.sourceGeneration,
                sink = object : VehicleVideoParkRevocationSink {
                    override fun stopVideoPlayer() { events += "stop" }
                    override fun setVideoPlaybackAllowedFalse() { events += "disallow"; revoked.countDown() }
                })
            try {
                val lease = requireNotNull(authority.tryAcquireLease())
                assertTrue(lease.isCurrent(session))
                assertFalse(lease.isCurrent(VehicleVideoSessionIdentity(1)))

                fixture.backend.speed = 0.2f
                assertTrue("moving sample must revoke", revoked.await(2, TimeUnit.SECONDS))
                assertFalse(lease.isCurrent(session))
                assertEquals(listOf("stop", "disallow"), events.toList())
                assertNull(authority.tryAcquireLease())
            } finally { authority.close() }
        } finally { fixture.close() }
    }

    @Test fun staleEventsAndStalledAcquisitionFailClosedWithoutWaitingForIap2() {
        val fixture = Fixture()
        try {
            val sample = fixture.awaitFirstSample()
            val session = VehicleVideoSessionIdentity(2)
            val revoked = CountDownLatch(1)
            val authority = fixture.authority(session, sample.sourceIdentity, sample.sourceGeneration,
                sink = latchSink(revoked))
            try {
                val lease = requireNotNull(authority.tryAcquireLease())
                // A fresh Binder read of an old VHAL event is not fresh motion evidence.
                fixture.clock.addAndGet(2_000)
                assertFalse(lease.isCurrent(session))
                assertTrue(revoked.await(1, TimeUnit.SECONDS))
                assertNull(authority.tryAcquireLease())
            } finally { authority.close() }
        } finally { fixture.close() }
    }

    @Test fun disconnectAndSourceSwapRetireOldCertificateEvenIfNewManagerReportsPark() {
        val fixture = Fixture()
        try {
            val sample = fixture.awaitFirstSample()
            val session = VehicleVideoSessionIdentity(3)
            val revoked = CountDownLatch(1)
            val authority = fixture.authority(session, sample.sourceIdentity, sample.sourceGeneration,
                sink = latchSink(revoked))
            try {
                val lease = requireNotNull(authority.tryAcquireLease())
                fixture.backend.isConnected = false
                assertFalse(lease.isCurrent(session))
                assertTrue(revoked.await(1, TimeUnit.SECONDS))
                fixture.backend.sourceIdentity = Any()
                fixture.backend.isConnected = true
                awaitCondition { fixture.provider.latestVideoParkEvidence()?.sourceIdentity === fixture.backend.sourceIdentity }
                assertNull(authority.tryAcquireLease()) // new manager needs a new OEM certificate
                assertFalse(lease.isCurrent(session))
            } finally { authority.close() }
        } finally { fixture.close() }
    }

    @Test fun uncertifiedVhalUnknownBrakeAndForgedNumericBrakeNeverGrant() {
        val fixture = Fixture()
        try {
            val sample = fixture.awaitFirstSample()
            val session = VehicleVideoSessionIdentity(4)
            val uncertified = fixture.authority(session, null, null)
            try { assertNull(uncertified.tryAcquireLease()) } finally { uncertified.close() }

            fixture.backend.brake = null
            fixture.backend.parkingBrakeSupport = CarPropertySupport.UNKNOWN
            awaitCondition { fixture.provider.latestVideoParkEvidence()?.parkingBrake == null }
            val certified = fixture.authority(session, sample.sourceIdentity, sample.sourceGeneration)
            try {
                assertEquals(1, fixture.backend.read(0x11200402)?.value)
                assertNull(certified.tryAcquireLease())
            } finally { certified.close() }

            // A numeric 1 from the generic property reader cannot impersonate native Boolean true.
            assertEquals(1, fixture.backend.numericBrakeReads.get())
        } finally { fixture.close() }
    }

    @Test fun verifiedAbsenceOfBrakeCanUseSpeedAndGearButNotMovingOrReverse() {
        val fixture = Fixture()
        try {
            val sample = fixture.awaitFirstSample()
            fixture.backend.brake = null
            fixture.backend.parkingBrakeSupport = CarPropertySupport.UNSUPPORTED
            awaitCondition { fixture.provider.latestVideoParkEvidence()?.parkingBrakeSupport == CarPropertySupport.UNSUPPORTED }
            val session = VehicleVideoSessionIdentity(5)
            val authority = fixture.authority(session, sample.sourceIdentity, sample.sourceGeneration)
            try {
                assertNotNull(authority.tryAcquireLease())
                fixture.backend.gear = 2
                awaitCondition { fixture.provider.latestVideoParkEvidence()?.selectedGear?.value == 2 }
                assertNull(authority.tryAcquireLease())
            } finally { authority.close() }
        } finally { fixture.close() }
    }

    @Test fun failingStopStillAttemptsPhoneDisallowAndNeverKeepsLease() {
        val fixture = Fixture()
        try {
            val sample = fixture.awaitFirstSample()
            val session = VehicleVideoSessionIdentity(6)
            val disallow = CountDownLatch(1)
            val authority = fixture.authority(session, sample.sourceIdentity, sample.sourceGeneration,
                sink = object : VehicleVideoParkRevocationSink {
                    override fun stopVideoPlayer(): Unit = throw IllegalStateException("injected stop failure")
                    override fun setVideoPlaybackAllowedFalse() { disallow.countDown() }
                })
            try {
                val lease = requireNotNull(authority.tryAcquireLease())
                fixture.backend.speed = -0.5f
                assertTrue(disallow.await(2, TimeUnit.SECONDS))
                assertFalse(lease.isCurrent(session))
                assertEquals(1, authority.callbackFailureCount())
            } finally { authority.close() }
        } finally { fixture.close() }
    }

    @Test fun delayedOldCallbackCannotResurrectAClosedSessionAfterSourceSwap() {
        val fixture = Fixture()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val block = java.util.concurrent.atomic.AtomicBoolean(false)
        val blocker = fixture.provider.observeVideoParkEvidence { sample ->
            if (sample != null && block.compareAndSet(true, false)) {
                callbackEntered.countDown()
                releaseCallback.await(2, TimeUnit.SECONDS)
            }
        }
        try {
            val sample = fixture.awaitFirstSample()
            val session = VehicleVideoSessionIdentity(7)
            val revoked = CountDownLatch(1)
            val authority = fixture.authority(session, sample.sourceIdentity, sample.sourceGeneration,
                sink = latchSink(revoked))
            val lease = requireNotNull(authority.tryAcquireLease())
            block.set(true)
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
            fixture.backend.sourceIdentity = Any()
            authority.close()
            assertTrue(revoked.await(1, TimeUnit.SECONDS))
            releaseCallback.countDown()
            awaitCondition { fixture.provider.latestVideoParkEvidence()?.sourceIdentity === fixture.backend.sourceIdentity }
            assertFalse(lease.isCurrent(session))
            assertNull(authority.tryAcquireLease())
        } finally {
            releaseCallback.countDown()
            blocker.close()
            fixture.close()
        }
    }

    private class Fixture : AutoCloseable {
        val clock = AtomicLong(100_000)
        val backend = FakeBackend(clock)
        val provider = CarPropertyVehicleProvider(
            CarPropertyDataStore(backend, VehiclePlatform.STANDARD_ANDROID, clock::get),
            VehicleDataConfig(VehiclePlatform.STANDARD_ANDROID, emptySet()), // no iAP2 speed feature
            clock::get,
        )
        private val firstSample = CountDownLatch(1)
        private val monitor: AutoCloseable

        init {
            assertTrue(provider.start())
            monitor = provider.observeVideoParkEvidence { if (it != null) firstSample.countDown() }
        }

        fun awaitFirstSample() = run {
            assertTrue("continuous park sample", firstSample.await(2, TimeUnit.SECONDS))
            requireNotNull(provider.latestVideoParkEvidence())
        }

        fun authority(
            session: VehicleVideoSessionIdentity,
            source: Any?,
            generation: Long?,
            sink: VehicleVideoParkRevocationSink = object : VehicleVideoParkRevocationSink {
                override fun stopVideoPlayer() = Unit
                override fun setVideoPlaybackAllowedFalse() = Unit
            },
        ) = VehicleVideoParkAuthority(provider, session,
            if (source != null && generation != null)
                VehicleVideoParkSourceCertificate(session, source, generation) else null,
            sink, clock::get)

        override fun close() {
            monitor.close()
            provider.close()
        }
    }

    private class FakeBackend(private val clock: AtomicLong) : ReadOnlyCarPropertyBackend {
        @Volatile override var isConnected = true
        @Volatile override var sourceIdentity: Any? = Any()
        @Volatile override var parkingBrakeSupport = CarPropertySupport.SUPPORTED
        @Volatile var speed = 0f
        @Volatile var gear = 4
        @Volatile var brake: Boolean? = true
        val numericBrakeReads = AtomicInteger()
        val parkSpeedReads = AtomicInteger()

        override fun connect() = true
        override fun read(propertyId: Int): CarPropertyValue? = when (propertyId) {
            0x11200402 -> { numericBrakeReads.incrementAndGet(); CarPropertyValue(1, clock.get()) }
            else -> null
        }
        override fun readVideoParkNumber(propertyId: Int): CarPropertyValue? = when (propertyId) {
            VehicleTelemetrySnapshot.STANDARD_SPEED -> {
                parkSpeedReads.incrementAndGet()
                CarPropertyValue(speed, clock.get())
            }
            VehicleTelemetrySnapshot.STANDARD_GEAR_SELECTION -> CarPropertyValue(gear, clock.get())
            else -> null
        }
        override fun readBoolean(propertyId: Int): CarPropertyBooleanValue? =
            if (propertyId == 0x11200402) brake?.let { CarPropertyBooleanValue(it, clock.get()) } else null
        override fun close() { isConnected = false; sourceIdentity = null }
    }

    private fun latchSink(latch: CountDownLatch) = object : VehicleVideoParkRevocationSink {
        override fun stopVideoPlayer() = Unit
        override fun setVideoPlaybackAllowedFalse() { latch.countDown() }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("timed out waiting for safety sample", condition())
    }
}
