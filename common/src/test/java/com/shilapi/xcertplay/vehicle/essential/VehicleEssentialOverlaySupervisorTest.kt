package com.shilapi.xcertplay.vehicle.essential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.IdentityHashMap

class VehicleEssentialOverlaySupervisorTest {
    private val brake = VehicleOverlaySignalKey(VehicleEssentialTelltale.BRAKE_SYSTEM, 0)
    private val belt = VehicleOverlaySignalKey(VehicleEssentialTelltale.SEAT_BELT, 1)
    private val owners = IdentityHashMap<VehicleEssentialOverlaySupervisor, VehicleEssentialDomainOwner>()

    @Test fun onlyCurrentAvailableRealExactFreshSampleCanBeKnownOff() {
        val source = FakeSource(realOff(brake, 900L))
        val lease = FakeLease(brake, 7L)
        val boundary = supervisor(source, FakePort(lease))
        val accepted = boundary.refresh(brake)
        assertTrue(accepted.signalState is VehicleOverlaySignalState.KnownOff)
        assertEquals(100L, (accepted.signalState as VehicleOverlaySignalState.KnownOff).ageMillis)
        assertEquals(VehicleOverlayPresentation.KNOWN_OFF, accepted.presentation)
        assertEquals(VehicleOverlayRefreshDisposition.ACCEPTED_UNCONFIRMED, accepted.disposition)

        val invalid = listOf(
            realOff(brake, 900L).copy(status = VehicleOverlaySourceStatus.UNAVAILABLE) to
                VehicleOverlayUnknownReason.UNAVAILABLE,
            realOff(brake, 900L).copy(status = VehicleOverlaySourceStatus.ERROR) to
                VehicleOverlayUnknownReason.ERROR_STATUS,
            realOff(brake, 900L).copy(origin = VehicleOverlaySampleOrigin.SIMULATED) to
                VehicleOverlayUnknownReason.NON_REAL_ORIGIN,
            realOff(brake, 900L).copy(active = null) to
                VehicleOverlayUnknownReason.MISSING_VALUE,
            realOff(brake, 900L).copy(eventElapsedRealtimeMillis = null) to
                VehicleOverlayUnknownReason.MISSING_TIMESTAMP,
            realOff(brake, 1001L) to VehicleOverlayUnknownReason.FUTURE_TIMESTAMP,
            realOff(brake, 499L) to VehicleOverlayUnknownReason.STALE_TIMESTAMP,
            realOff(belt, 900L) to VehicleOverlayUnknownReason.WRONG_SIGNAL_OR_AREA,
        )
        invalid.forEach { (sample, reason) ->
            source.sample = sample
            val result = boundary.refresh(brake)
            assertEquals(reason, (result.signalState as VehicleOverlaySignalState.Unknown).reason)
            assertNull(result.presentation)
            assertEquals(VehicleOverlayRefreshDisposition.NO_REVIEWED_FALLBACK,
                result.disposition)
        }
        assertEquals(1, lease.writes.size)
        assertEquals(1, lease.closeCount)
    }

    @Test fun sourceRevocationReadErrorAndEpochRebindNeverBecomeOff() {
        val source = FakeSource(realOff(brake, 1000L))
        val lease = FakeLease(brake, 1L)
        val boundary = supervisor(source, FakePort(lease))
        source.current = false
        assertUnknown(boundary.refresh(brake), VehicleOverlayUnknownReason.SOURCE_REVOKED)
        source.current = true
        source.throwRead = true
        assertUnknown(boundary.refresh(brake), VehicleOverlayUnknownReason.SOURCE_ERROR)
        source.throwRead = false
        source.epoch = 2L
        assertUnknown(boundary.refresh(brake), VehicleOverlayUnknownReason.SOURCE_REVOKED)
        assertTrue(lease.writes.isEmpty())
    }

    @Test fun sameEpochRevisionFlipDuringPublishCannotReportOldOff() {
        val source = FakeSource(realOff(brake, 1000L))
        val lease = FakeLease(brake, 2L)
        lease.onPublish = { source.sample = realOn(brake, 1000L, revision = 2L) }
        val result = supervisor(source, FakePort(lease)).refresh(brake)
        assertEquals(VehicleOverlayRefreshDisposition.SOURCE_CHANGED_DURING_WRITE,
            result.disposition)
        assertTrue(result.signalState is VehicleOverlaySignalState.Unknown)
        assertNull(result.acceptedDisplayEpoch)
        assertEquals(1, lease.closeCount)
    }

    @Test fun sourceRevisionAndSourceEpochMustMatchExactSample() {
        val source = FakeSource(realOff(brake, 1000L).copy(sampleRevision = 0L))
        val lease = FakeLease(brake, 3L)
        assertUnknown(supervisor(source, FakePort(lease)).refresh(brake),
            VehicleOverlayUnknownReason.SAMPLE_REVISED)
        assertTrue(lease.writes.isEmpty())
    }

    @Test fun sourceInvalidationCallbackRetiresAcceptedOffWithoutRefresh() {
        val scheduler = FakeScheduler()
        val source = FakeSource(realOff(brake, 1000L))
        val lease = FakeLease(brake, 4L)
        val boundary = supervisor(source, FakePort(lease), scheduler = scheduler)
        assertEquals(VehicleOverlayRefreshDisposition.ACCEPTED_UNCONFIRMED,
            boundary.refresh(brake).disposition)
        source.sample = realOn(brake, 1000L, revision = 2L)
        scheduler.runDue()
        assertEquals(1, lease.closeCount)
        assertFalse(lease.hasKnownOffAt(1000L))
    }

    @Test fun monotonicWatchdogRetiresAgingOffWithoutRefresh() {
        val scheduler = FakeScheduler()
        val source = FakeSource(realOff(brake, 1000L))
        val lease = FakeLease(brake, 5L)
        val boundary = supervisor(source, FakePort(lease), scheduler = scheduler)
        assertEquals(VehicleOverlayRefreshDisposition.ACCEPTED_UNCONFIRMED,
            boundary.refresh(brake).disposition)
        scheduler.advanceTo(1501L)
        assertEquals(1, lease.closeCount)
        assertFalse(lease.hasKnownOffAt(1501L))
    }

    @Test fun closeFailureCannotKeepOffBeyondIndependentHardwareTtl() {
        val scheduler = FakeScheduler()
        val source = FakeSource(realOff(brake, 1000L))
        val lease = FakeLease(brake, 6L)
        supervisor(source, FakePort(lease), scheduler = scheduler).refresh(brake)
        lease.failCloseOnce = true
        lease.failRevoke = true
        source.sample = realOn(brake, 1000L, revision = 2L)
        scheduler.runDue() // First close fails; the exact pending lease is retained.
        assertEquals(1, lease.closeCount)
        assertTrue(lease.hasKnownOffAt(1099L)) // Bounded uncertainty before hard expiry.
        assertFalse(lease.hasKnownOffAt(1101L)) // Hardware TTL is independent of Java close.
        scheduler.advanceTo(1050L) // Retry then succeeds; monotonic scheduler time can only rise.
        assertEquals(2, lease.closeCount)
    }

    @Test fun unknownFallbackIsReviewedPerTelltaleAndNeverOff() {
        val source = FakeSource(realOff(brake, 0L)) // stale
        val lease = FakeLease(brake, 7L)
        val boundary = supervisor(source, FakePort(lease), reviewedBrake())
        val warning = boundary.refresh(brake)
        assertTrue(warning.signalState is VehicleOverlaySignalState.Unknown)
        assertEquals(VehicleOverlayPresentation.ASSERTED_ON_AS_UNKNOWN, warning.presentation)
        assertEquals(VehicleOverlayPresentation.ASSERTED_ON_AS_UNKNOWN,
            lease.writes.single().presentation)
        source.sample = realOff(belt, 0L)
        val other = boundary.refresh(belt)
        assertEquals(VehicleOverlayRefreshDisposition.NO_REVIEWED_FALLBACK,
            other.disposition)
        assertNull(other.presentation)
        assertEquals(0, lease.closeCount) // Different key cannot close brake lease.
    }

    @Test fun unknownKeyCannotEraseAnotherKeysKnownWarning() {
        val source = FakeSource(realOn(brake, 1000L))
        source.setForKey(belt, realOff(belt, 1000L))
        val brakeLease = FakeLease(brake, 8L)
        val beltLease = FakeLease(belt, 9L)
        val boundary = supervisor(source, FakePort(brakeLease, beltLease))
        assertNotNull(boundary.refresh(brake).acceptedDisplayEpoch)
        assertNotNull(boundary.refresh(belt).acceptedDisplayEpoch)
        source.setForKey(belt, realOff(belt, 1000L).copy(
            status = VehicleOverlaySourceStatus.ERROR, sampleRevision = 2L))
        assertUnknown(boundary.refresh(belt), VehicleOverlayUnknownReason.ERROR_STATUS)
        assertEquals(1, beltLease.closeCount)
        assertEquals(0, brakeLease.closeCount)
        assertTrue(brakeLease.hasKnownOnAt(1000L))
    }

    @Test fun physicalOcclusionAndAutonomousExpiryProofsMustAllMatch() {
        val source = FakeSource(realOff(brake, 1000L))
        listOf<FakeLease.() -> Unit>(
            { occlusion.displayEpoch = 5L },
            { occlusion.aboveCarPlay = false },
            { expiry.autonomous = false },
            { expiry.maxCommandTtlMillis = 101L },
            { expiry.displayEpoch = 5L },
        ).forEach { corrupt ->
            val lease = FakeLease(brake, 4L).apply(corrupt)
            val result = supervisor(source, FakePort(lease)).refresh(brake)
            assertEquals(VehicleOverlayRefreshDisposition.UNTRUSTED_DISPLAY_ROUTE,
                result.disposition)
            assertTrue(lease.writes.isEmpty())
            assertEquals(1, lease.closeCount)
        }
    }

    @Test fun hotplugRetiresExactOldEpochBeforeFreshKeyLease() {
        val source = FakeSource(realOff(brake, 1000L))
        val old = FakeLease(brake, 10L)
        val fresh = FakeLease(brake, 11L)
        val port = FakePort(old, fresh)
        val boundary = supervisor(source, port)
        assertEquals(10L, boundary.refresh(brake).acceptedDisplayEpoch?.displayEpoch)
        old.connector.displayEpoch = 11L
        old.occlusion.displayEpoch = 11L
        old.expiry.displayEpoch = 11L
        assertEquals(11L, boundary.refresh(brake).acceptedDisplayEpoch?.displayEpoch)
        assertEquals(1, old.closeCount)
        assertEquals(1, fresh.writes.size)
    }

    @Test fun outputEpochRevocationCallbackRetiresWithoutAnotherRefresh() {
        val scheduler = FakeScheduler()
        val source = FakeSource(realOff(brake, 1000L))
        val lease = FakeLease(brake, 15L)
        val boundary = supervisor(source, FakePort(lease), scheduler = scheduler)
        assertNotNull(boundary.refresh(brake).acceptedDisplayEpoch)
        lease.signalDisplayRevoked()
        scheduler.runDue()
        assertEquals(1, lease.closeCount)
        assertFalse(lease.hasKnownOffAt(1000L))
    }

    @Test fun outputRevokedInsideAtomicSubscriptionCannotPublish() {
        val source = FakeSource(realOff(brake, 1000L))
        val lease = FakeLease(brake, 16L).apply { revokeOnSubscribe = true }
        val result = supervisor(source, FakePort(lease)).refresh(brake)
        assertEquals(VehicleOverlayRefreshDisposition.UNTRUSTED_DISPLAY_ROUTE,
            result.disposition)
        assertTrue(lease.writes.isEmpty())
        assertEquals(1, lease.closeCount)
    }

    @Test fun failedRetirementBlocksNewEpochUntilExactCloseRetry() {
        val source = FakeSource(realOff(brake, 1000L))
        val old = FakeLease(brake, 1L)
        val fresh = FakeLease(brake, 2L)
        val port = FakePort(old, fresh)
        val boundary = supervisor(source, port)
        assertNotNull(boundary.refresh(brake).acceptedDisplayEpoch)
        old.failCloseOnce = true
        old.connector.current = false
        assertEquals(VehicleOverlayRefreshDisposition.RETIREMENT_RETRY_REQUIRED,
            boundary.refresh(brake).disposition)
        assertEquals(1, port.acquireCount)
        assertEquals(VehicleOverlayRefreshDisposition.ACCEPTED_UNCONFIRMED,
            boundary.refresh(brake).disposition)
        assertEquals(2, port.acquireCount)
        assertEquals(2, old.closeCount)
    }

    @Test fun retryRetirementCannotCloseActiveLeaseAndWrongOwnerCannotRetire() {
        val source = FakeSource(realOff(brake, 1000L))
        val lease = FakeLease(brake, 12L)
        val boundary = supervisor(source, FakePort(lease))
        boundary.refresh(brake)
        val owner = owners.getValue(boundary)
        assertTrue(boundary.retryRetirement(owner))
        assertEquals(0, lease.closeCount)
        assertFalse(boundary.retireFromVehicleDomain(
            VehicleEssentialDomainOwner.establishIndependentOwner()))
        assertEquals(0, lease.closeCount)
        lease.failCloseOnce = true
        assertFalse(boundary.retireFromVehicleDomain(owner))
        assertEquals(VehicleOverlayRefreshDisposition.RETIRED, boundary.refresh(brake).disposition)
        assertTrue(boundary.retryRetirement(owner))
        assertEquals(2, lease.closeCount)
    }

    @Test fun noCarPlayLifecycleOrCanvasCapabilityAndDefaultDenies() {
        val owner = VehicleEssentialDomainOwner.establishIndependentOwner()
        val default = VehicleEssentialOverlaySupervisor(owner,
            clock = VehicleOverlayElapsedRealtimeClock { 1000L },
            watchdog = FakeScheduler())
        assertEquals(VehicleOverlayRefreshDisposition.NO_REVIEWED_FALLBACK,
            default.refresh(brake).disposition)
        val type = default.javaClass
        assertFalse(Closeable::class.java.isAssignableFrom(type))
        assertFalse(AutoCloseable::class.java.isAssignableFrom(type))
        assertTrue(type.declaredFields.none { it.type.name.contains("CarPlay") ||
            it.type.name.contains("Canvas") || it.type.name.contains("UiSync") })
        assertTrue(type.declaredConstructors.flatMap { it.parameterTypes.toList() }
            .none { it.name.contains("CarPlay") || it.name.contains("Canvas") ||
                it.name.contains("UiSync") })
        assertTrue(type.methods.none { it.name == "close" ||
            (it.name.startsWith("retire") && it.parameterTypes.isEmpty()) })
    }

    private fun supervisor(
        source: VehicleOverlaySignalSource,
        port: VehicleOverlayOutputPort,
        policy: VehicleOverlayReviewedFallbackPolicy = VehicleOverlayReviewedFallbackPolicy.DENY_ALL,
        scheduler: FakeScheduler = FakeScheduler(),
    ): VehicleEssentialOverlaySupervisor {
        val owner = VehicleEssentialDomainOwner.establishIndependentOwner()
        return VehicleEssentialOverlaySupervisor(owner, source, port, policy,
            VehicleOverlayElapsedRealtimeClock { scheduler.nowMillis }, scheduler,
            maxSampleAgeMillis = 500L).also { owners[it] = owner }
    }

    private fun reviewedBrake() = VehicleOverlayReviewedFallbackPolicy { telltale ->
        if (telltale == VehicleEssentialTelltale.BRAKE_SYSTEM)
            VehicleOverlayReviewedFallback(telltale,
                VehicleOverlayUnknownFallback.ASSERTED_ON_AS_UNKNOWN, "OEM-BRAKE-REVIEW")
        else null
    }

    private fun assertUnknown(result: VehicleOverlayRefresh, reason: VehicleOverlayUnknownReason) {
        assertEquals(reason, (result.signalState as VehicleOverlaySignalState.Unknown).reason)
        assertNull(result.presentation)
    }

    private fun realOff(key: VehicleOverlaySignalKey, timestamp: Long, revision: Long = 1L) =
        VehicleOverlaySignalSample(key, VehicleOverlaySourceStatus.AVAILABLE, false,
            timestamp, VehicleOverlaySampleOrigin.REAL_VEHICLE, 1L, revision)

    private fun realOn(key: VehicleOverlaySignalKey, timestamp: Long, revision: Long = 1L) =
        realOff(key, timestamp, revision).copy(active = true)

    private class FakeSource(initial: VehicleOverlaySignalSample?) : VehicleOverlaySignalSource {
        private data class Listener(
            val sample: VehicleOverlaySignalSample,
            val callback: () -> Unit,
        )
        private val listeners = mutableListOf<Listener>()
        private val additional = mutableMapOf<VehicleOverlaySignalKey, VehicleOverlaySignalSample>()
        var sample = initial
            set(value) { field = value; notifyInvalid() }
        var current = true
            set(value) { field = value; notifyInvalid() }
        var epoch = 1L
            set(value) { field = value; notifyInvalid() }
        var throwRead = false
        fun setForKey(key: VehicleOverlaySignalKey, value: VehicleOverlaySignalSample) {
            additional[key] = value
            notifyInvalid()
        }
        override fun isCurrent() = current
        override fun currentEpoch(): Long? = epoch.takeIf { current }
        override fun read(key: VehicleOverlaySignalKey): VehicleOverlaySignalSample? {
            if (throwRead) error("vehicle source failed")
            return additional[key] ?: sample
        }
        override fun isCurrentSample(sample: VehicleOverlaySignalSample): Boolean =
            current && epoch == sample.sourceEpoch &&
                (additional[sample.key] ?: this.sample) == sample
        override fun subscribeInvalidation(
            sample: VehicleOverlaySignalSample,
            onInvalidated: () -> Unit,
        ): Closeable {
            val listener = Listener(sample, onInvalidated)
            listeners += listener
            if (!isCurrentSample(sample)) onInvalidated()
            return Closeable { listeners.remove(listener) }
        }
        private fun notifyInvalid() {
            listeners.toList().forEach { if (!isCurrentSample(it.sample)) it.callback() }
        }
    }

    private class FakeScheduler : VehicleOverlayWatchdogScheduler {
        private class Task(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        var nowMillis = 1000L
            private set
        override fun schedule(delayMillis: Long, action: () -> Unit): Closeable {
            val task = Task(nowMillis + delayMillis, action)
            tasks += task
            return Closeable { task.cancelled = true }
        }
        fun runDue() {
            while (true) {
                val task = tasks.filter { !it.cancelled && it.due <= nowMillis }
                    .minByOrNull { it.due } ?: return
                task.cancelled = true
                task.action()
            }
        }
        fun advanceTo(millis: Long) {
            require(millis >= nowMillis)
            nowMillis = millis
            runDue()
        }
    }

    private class FakePort(vararg leases: FakeLease) : VehicleOverlayOutputPort {
        private val available = leases.toMutableList()
        var acquireCount = 0
        override fun acquire(key: VehicleOverlaySignalKey): VehicleOverlayOutputLease? {
            acquireCount++
            val index = available.indexOfFirst { it.key == key }
            return if (index < 0) null else available.removeAt(index)
        }
    }

    private class FakeConnector(epoch: Long) : VehicleOverlayPhysicalConnectorEvidence {
        override val stableConnectorIdentity = "bench-physical-connector"
        override val hardwareIdentity = "bench-hardware"
        override val displayId = 42
        override var displayEpoch = epoch
        var current = true
        override fun isCurrent() = current
    }

    private class FakeOcclusion(epoch: Long) : VehicleOverlayOcclusionOrderEvidence {
        override val stableConnectorIdentity = "bench-physical-connector"
        override val displayId = 42
        override var displayEpoch = epoch
        override val hardwareStackEpoch = 99L
        override val safetyPlaneIdentity = "bench-vehicle-plane"
        var aboveCarPlay = true
        var current = true
        override fun isProvenAboveCarPlayPlanes() = aboveCarPlay
        override fun isCurrent() = current
    }

    private class FakeExpiry(
        override val key: VehicleOverlaySignalKey,
        epoch: Long,
    ) : VehicleOverlayIndependentExpiryEvidence {
        override val stableConnectorIdentity = "bench-physical-connector"
        override var displayEpoch = epoch
        override var maxCommandTtlMillis = 100L
        var autonomous = true
        var current = true
        override fun isAutonomousHardwareExpiryProven() = autonomous
        override fun isCurrent() = current
    }

    private data class Write(
        val presentation: VehicleOverlayPresentation,
        val deadline: Long,
    )

    private class FakeLease(
        override val key: VehicleOverlaySignalKey,
        epoch: Long,
    ) : VehicleOverlayOutputLease {
        val connector = FakeConnector(epoch)
        val occlusion = FakeOcclusion(epoch)
        val expiry = FakeExpiry(key, epoch)
        override val connectorEvidence: VehicleOverlayPhysicalConnectorEvidence = connector
        override val occlusionEvidence: VehicleOverlayOcclusionOrderEvidence = occlusion
        override val independentExpiryEvidence: VehicleOverlayIndependentExpiryEvidence = expiry
        val writes = mutableListOf<Write>()
        var closeCount = 0
        var failCloseOnce = false
        var failRevoke = false
        var revokeOnSubscribe = false
        var onPublish: (() -> Unit)? = null
        private val routeCallbacks = mutableListOf<() -> Unit>()
        private var closed = false
        private var revoked = false
        override fun isCurrent() = !closed
        override fun subscribeRevocation(onRevoked: () -> Unit): Closeable {
            routeCallbacks += onRevoked
            if (revokeOnSubscribe) signalDisplayRevoked()
            return Closeable { routeCallbacks.remove(onRevoked) }
        }
        fun signalDisplayRevoked() {
            connector.current = false
            routeCallbacks.toList().forEach { it() }
        }
        override fun publish(
            expectedDisplayEpoch: VehicleOverlayDisplayEpoch,
            key: VehicleOverlaySignalKey,
            presentation: VehicleOverlayPresentation,
            knownSampleGuard: VehicleOverlayKnownSampleGuard?,
            expiresElapsedRealtimeMillis: Long,
        ): VehicleOverlayWriteResult {
            if (!isCurrent() || this.key != key || !connector.isCurrent() ||
                !occlusion.isCurrent() || !expiry.isCurrent() ||
                expectedDisplayEpoch.displayEpoch != connector.displayEpoch ||
                expectedDisplayEpoch.displayEpoch != occlusion.displayEpoch ||
                expectedDisplayEpoch.displayEpoch != expiry.displayEpoch ||
                !occlusion.isProvenAboveCarPlayPlanes() ||
                !expiry.isAutonomousHardwareExpiryProven()) return VehicleOverlayWriteResult.REJECTED
            if (presentation == VehicleOverlayPresentation.KNOWN_OFF ||
                presentation == VehicleOverlayPresentation.KNOWN_ON) {
                if (knownSampleGuard == null || !knownSampleGuard.isCurrent() ||
                    knownSampleGuard.sample.key != key ||
                    knownSampleGuard.sample.active !=
                        (presentation == VehicleOverlayPresentation.KNOWN_ON))
                    return VehicleOverlayWriteResult.REJECTED
            } else if (knownSampleGuard != null) return VehicleOverlayWriteResult.REJECTED
            writes += Write(presentation, expiresElapsedRealtimeMillis)
            revoked = false
            onPublish?.invoke()
            return VehicleOverlayWriteResult.ACCEPTED_UNCONFIRMED
        }
        override fun revoke(expectedDisplayEpoch: VehicleOverlayDisplayEpoch): VehicleOverlayWriteResult {
            if (failRevoke) return VehicleOverlayWriteResult.UNCERTAIN
            revoked = true
            return VehicleOverlayWriteResult.ACCEPTED_UNCONFIRMED
        }
        fun hasKnownOffAt(now: Long) = !closed && !revoked &&
            writes.lastOrNull()?.let { it.presentation == VehicleOverlayPresentation.KNOWN_OFF &&
                now <= it.deadline } == true
        fun hasKnownOnAt(now: Long) = !closed && !revoked &&
            writes.lastOrNull()?.let { it.presentation == VehicleOverlayPresentation.KNOWN_ON &&
                now <= it.deadline } == true
        override fun close() {
            closeCount++
            if (failCloseOnce) {
                failCloseOnce = false
                error("hardware teardown busy")
            }
            closed = true
            revoked = true
        }
    }
}
