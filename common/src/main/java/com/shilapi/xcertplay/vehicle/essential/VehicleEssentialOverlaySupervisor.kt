package com.shilapi.xcertplay.vehicle.essential

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vehicle-owned semantic boundary. It has no CarPlay, AA05, UI Sync, Profile, CAF, renderer, or
 * Activity dependency. No real OEM source/connector/occlusion/expiry issuer ships here: defaults
 * deny everything, and even an accepted port command is not optical or safety certification.
 */
internal class VehicleEssentialOverlaySupervisor(
    private val owner: VehicleEssentialDomainOwner,
    private val signalSource: VehicleOverlaySignalSource = VehicleOverlaySignalSource.DENY_ALL,
    private val outputPort: VehicleOverlayOutputPort = VehicleOverlayOutputPort.DENY_ALL,
    private val fallbackPolicy: VehicleOverlayReviewedFallbackPolicy =
        VehicleOverlayReviewedFallbackPolicy.DENY_ALL,
    private val clock: VehicleOverlayElapsedRealtimeClock = VehicleOverlayElapsedRealtimeClock.ANDROID,
    private val watchdog: VehicleOverlayWatchdogScheduler =
        VehicleOverlayWatchdogScheduler.PROCESS_OWNED,
    private val maxSampleAgeMillis: Long = 500L,
) {
    init { require(maxSampleAgeMillis in 0L..10_000L) }

    private class Publication(
        val id: Long,
        val guard: VehicleOverlayKnownSampleGuard,
        val invalidated: AtomicBoolean,
        val sourceSubscription: Closeable,
        val expiryTask: Closeable,
    )

    private class Slot(
        val lease: VehicleOverlayOutputLease,
        val epoch: VehicleOverlayDisplayEpoch,
        val maxHardwareTtlMillis: Long,
    ) {
        val routeRevoked = AtomicBoolean(false)
        var routeSubscription: Closeable? = null
        var publication: Publication? = null
        var publishAttempted = false
        var retirementPending = false
        var retryScheduled = false
    }

    private data class Route(val epoch: VehicleOverlayDisplayEpoch, val maxHardwareTtlMillis: Long)

    private val lock = Any()
    private val slots = LinkedHashMap<VehicleOverlaySignalKey, Slot>()
    private val failedCallbackClosures = ArrayList<Closeable>()
    private var nextPublicationId = 1L
    private var retired = false

    /** One key owns one output lease; a fault in one telltale cannot close another's warning. */
    fun refresh(key: VehicleOverlaySignalKey): VehicleOverlayRefresh = synchronized(lock) {
        retryCallbackClosuresLocked()
        if (retired) return@synchronized result(key,
            VehicleOverlayUnknownReason.SOURCE_REVOKED, VehicleOverlayRefreshDisposition.RETIRED)
        var slot = slots[key]
        if (slot?.retirementPending == true && !retireSlotLocked(key, slot))
            return@synchronized result(key, VehicleOverlayUnknownReason.SOURCE_REVOKED,
                VehicleOverlayRefreshDisposition.RETIREMENT_RETRY_REQUIRED)
        slot = slots[key]
        if (slot != null && !slotMatches(key, slot)) {
            if (!retireSlotLocked(key, slot)) return@synchronized result(key,
                VehicleOverlayUnknownReason.SOURCE_REVOKED,
                VehicleOverlayRefreshDisposition.RETIREMENT_RETRY_REQUIRED)
            slot = null
        }

        val state = readSignal(key)
        val presentation = presentationFor(state)
        if (presentation == null) {
            val released = slot == null || retireSlotLocked(key, slot)
            return@synchronized VehicleOverlayRefresh(state, null,
                if (released) VehicleOverlayRefreshDisposition.NO_REVIEWED_FALLBACK
                else VehicleOverlayRefreshDisposition.RETIREMENT_RETRY_REQUIRED, null)
        }
        if (slot == null) {
            val lease = try { outputPort.acquire(key) } catch (_: Exception) { null }
                ?: return@synchronized VehicleOverlayRefresh(state, presentation,
                    VehicleOverlayRefreshDisposition.NO_PHYSICAL_OUTPUT_LEASE, null)
            val route = snapshotRoute(key, lease)
            if (route == null) {
                val closed = try { lease.close(); true } catch (_: Exception) { false }
                if (!closed) {
                    // An invalid lease was never permitted to publish. It is retained for retry.
                    slots[key] = Slot(lease, INVALID_EPOCH, 0L).apply { retirementPending = true }
                    scheduleRetryLocked(key, slots.getValue(key))
                }
                return@synchronized VehicleOverlayRefresh(state, presentation,
                    if (closed) VehicleOverlayRefreshDisposition.UNTRUSTED_DISPLAY_ROUTE
                    else VehicleOverlayRefreshDisposition.RETIREMENT_RETRY_REQUIRED, null)
            }
            val admitted = Slot(lease, route.epoch, route.maxHardwareTtlMillis)
            val routeSubscription = try {
                lease.subscribeRevocation {
                    admitted.routeRevoked.set(true)
                    scheduleRouteRevocation(key, admitted)
                }
            } catch (_: Exception) { null }
            if (routeSubscription == null) {
                val closed = try { lease.close(); true } catch (_: Exception) { false }
                if (!closed) {
                    slots[key] = admitted.apply { retirementPending = true }
                    scheduleRetryLocked(key, admitted)
                }
                return@synchronized VehicleOverlayRefresh(state, presentation,
                    if (closed) VehicleOverlayRefreshDisposition.UNTRUSTED_DISPLAY_ROUTE
                    else VehicleOverlayRefreshDisposition.RETIREMENT_RETRY_REQUIRED, null)
            }
            admitted.routeSubscription = routeSubscription
            slots[key] = admitted
            if (!slotMatches(key, admitted)) {
                val closed = retireSlotLocked(key, admitted)
                return@synchronized VehicleOverlayRefresh(state, presentation,
                    if (closed) VehicleOverlayRefreshDisposition.UNTRUSTED_DISPLAY_ROUTE
                    else VehicleOverlayRefreshDisposition.RETIREMENT_RETRY_REQUIRED, null)
            }
            slot = admitted
        }
        val activeSlot = slot
        val sample = knownSample(state)
        val candidatePublication = if (sample != null) preparePublication(key, sample)
            ?: run {
                val closed = retireSlotLocked(key, activeSlot)
                return@synchronized VehicleOverlayRefresh(
                    VehicleOverlaySignalState.Unknown(key,
                        VehicleOverlayUnknownReason.SAMPLE_REVISED), null,
                    if (closed) VehicleOverlayRefreshDisposition.SOURCE_CHANGED_DURING_WRITE
                    else VehicleOverlayRefreshDisposition.RETIREMENT_RETRY_REQUIRED, null)
            }
        else null

        val commandDeadline = commandDeadline(activeSlot, candidatePublication?.guard)
        if (commandDeadline == null || !slotMatches(key, activeSlot) ||
            candidatePublication?.guard?.isCurrent() == false) {
            closePublicationLocked(candidatePublication)
            val closed = retireSlotLocked(key, activeSlot)
            return@synchronized VehicleOverlayRefresh(
                VehicleOverlaySignalState.Unknown(key,
                    VehicleOverlayUnknownReason.SAMPLE_REVISED), null,
                if (closed) VehicleOverlayRefreshDisposition.SOURCE_CHANGED_DURING_WRITE
                else VehicleOverlayRefreshDisposition.RETIREMENT_RETRY_REQUIRED, null)
        }

        // The lease must enforce this exact sample guard and hardware TTL inside dispatch.
        activeSlot.publishAttempted = true
        val write = try {
            activeSlot.lease.publish(activeSlot.epoch, key, presentation,
                candidatePublication?.guard, commandDeadline)
        } catch (_: Exception) { VehicleOverlayWriteResult.UNCERTAIN }
        if (!slotMatches(key, activeSlot) ||
            candidatePublication?.guard?.isCurrent() == false) {
            closePublicationLocked(candidatePublication)
            val closed = retireSlotLocked(key, activeSlot)
            return@synchronized VehicleOverlayRefresh(
                VehicleOverlaySignalState.Unknown(key,
                    VehicleOverlayUnknownReason.SAMPLE_REVISED), null,
                if (closed) VehicleOverlayRefreshDisposition.SOURCE_CHANGED_DURING_WRITE
                else VehicleOverlayRefreshDisposition.RETIREMENT_RETRY_REQUIRED, null)
        }
        if (write != VehicleOverlayWriteResult.ACCEPTED_UNCONFIRMED) {
            closePublicationLocked(candidatePublication)
            val closed = retireSlotLocked(key, activeSlot)
            return@synchronized VehicleOverlayRefresh(state, presentation,
                if (!closed) VehicleOverlayRefreshDisposition.RETIREMENT_RETRY_REQUIRED
                else if (write == VehicleOverlayWriteResult.REJECTED)
                    VehicleOverlayRefreshDisposition.WRITE_REJECTED
                else VehicleOverlayRefreshDisposition.WRITE_UNCERTAIN, null)
        }
        val old = activeSlot.publication
        activeSlot.publication = candidatePublication
        closePublicationLocked(old)
        if (candidatePublication?.invalidated?.get() == true) {
            val closed = retireSlotLocked(key, activeSlot)
            return@synchronized VehicleOverlayRefresh(
                VehicleOverlaySignalState.Unknown(key,
                    VehicleOverlayUnknownReason.SAMPLE_REVISED), null,
                if (closed) VehicleOverlayRefreshDisposition.SOURCE_CHANGED_DURING_WRITE
                else VehicleOverlayRefreshDisposition.RETIREMENT_RETRY_REQUIRED, null)
        }
        VehicleOverlayRefresh(state, presentation,
            VehicleOverlayRefreshDisposition.ACCEPTED_UNCONFIRMED, activeSlot.epoch)
    }

    /** Exact owner token is required; logical retirement precedes fallible hardware closure. */
    fun retireFromVehicleDomain(requestor: VehicleEssentialDomainOwner): Boolean = synchronized(lock) {
        if (requestor !== owner) return@synchronized false
        retired = true
        var allRetired = true
        slots.toList().forEach { (key, slot) ->
            if (!retireSlotLocked(key, slot)) allRetired = false
        }
        allRetired
    }

    /** Never closes an active lease. Only a previously failed retirement may be retried. */
    fun retryRetirement(requestor: VehicleEssentialDomainOwner): Boolean = synchronized(lock) {
        if (requestor !== owner) return@synchronized false
        retryCallbackClosuresLocked()
        var allRetired = true
        slots.toList().filter { it.second.retirementPending }.forEach { (key, slot) ->
            if (!retireSlotLocked(key, slot)) allRetired = false
        }
        allRetired
    }

    private fun readSignal(key: VehicleOverlaySignalKey): VehicleOverlaySignalState {
        val epoch = sourceEpoch() ?: return unknown(key, VehicleOverlayUnknownReason.SOURCE_REVOKED)
        val sample = try { signalSource.read(key) }
        catch (_: Exception) { return unknown(key, VehicleOverlayUnknownReason.SOURCE_ERROR) }
        if (sourceEpoch() != epoch) return unknown(key, VehicleOverlayUnknownReason.SOURCE_REVOKED)
        if (sample == null) return unknown(key, VehicleOverlayUnknownReason.NO_SAMPLE)
        if (sample.key != key) return unknown(key, VehicleOverlayUnknownReason.WRONG_SIGNAL_OR_AREA)
        if (sample.sourceEpoch != epoch)
            return unknown(key, VehicleOverlayUnknownReason.SOURCE_REVOKED)
        if (sample.sampleRevision <= 0L)
            return unknown(key, VehicleOverlayUnknownReason.SAMPLE_REVISED)
        when (sample.status) {
            VehicleOverlaySourceStatus.UNAVAILABLE ->
                return unknown(key, VehicleOverlayUnknownReason.UNAVAILABLE)
            VehicleOverlaySourceStatus.ERROR ->
                return unknown(key, VehicleOverlayUnknownReason.ERROR_STATUS)
            VehicleOverlaySourceStatus.AVAILABLE -> Unit
        }
        if (sample.origin != VehicleOverlaySampleOrigin.REAL_VEHICLE)
            return unknown(key, VehicleOverlayUnknownReason.NON_REAL_ORIGIN)
        val active = sample.active ?: return unknown(key, VehicleOverlayUnknownReason.MISSING_VALUE)
        val timestamp = sample.eventElapsedRealtimeMillis
            ?: return unknown(key, VehicleOverlayUnknownReason.MISSING_TIMESTAMP)
        val now = nowMillis() ?: return unknown(key, VehicleOverlayUnknownReason.CLOCK_ERROR)
        if (timestamp < 0L || timestamp > now)
            return unknown(key, VehicleOverlayUnknownReason.FUTURE_TIMESTAMP)
        val age = now - timestamp
        if (age > maxSampleAgeMillis)
            return unknown(key, VehicleOverlayUnknownReason.STALE_TIMESTAMP)
        if (!currentSample(sample)) return unknown(key, VehicleOverlayUnknownReason.SAMPLE_REVISED)
        return if (active) VehicleOverlaySignalState.KnownOn(key, age, sample)
        else VehicleOverlaySignalState.KnownOff(key, age, sample)
    }

    private fun presentationFor(state: VehicleOverlaySignalState): VehicleOverlayPresentation? =
        when (state) {
            is VehicleOverlaySignalState.KnownOn -> VehicleOverlayPresentation.KNOWN_ON
            is VehicleOverlaySignalState.KnownOff -> VehicleOverlayPresentation.KNOWN_OFF
            is VehicleOverlaySignalState.Unknown -> {
                val fallback = try { fallbackPolicy.fallbackFor(state.key.telltale) }
                catch (_: Exception) { null }
                if (fallback == null || fallback.telltale != state.key.telltale) null
                else when (fallback.mode) {
                    VehicleOverlayUnknownFallback.DISTINCT_UNKNOWN ->
                        VehicleOverlayPresentation.DISTINCT_UNKNOWN
                    VehicleOverlayUnknownFallback.ASSERTED_ON_AS_UNKNOWN ->
                        VehicleOverlayPresentation.ASSERTED_ON_AS_UNKNOWN
                }
            }
        }

    private fun preparePublication(
        key: VehicleOverlaySignalKey,
        sample: VehicleOverlaySignalSample,
    ): Publication? {
        val timestamp = sample.eventElapsedRealtimeMillis ?: return null
        val expires = try { Math.addExact(timestamp, maxSampleAgeMillis) }
            catch (_: ArithmeticException) { return null }
        val invalidated = AtomicBoolean(false)
        val guard = VehicleOverlayKnownSampleGuard(sample, expires) {
            !invalidated.get() && currentSample(sample) &&
                nowMillis()?.let { it >= timestamp && it <= expires } == true
        }
        val id = nextPublicationId++
        val subscription = try {
            signalSource.subscribeInvalidation(sample) {
                invalidated.set(true)
                scheduleInvalidation(key, id)
            }
        } catch (_: Exception) { return null }
        if (!guard.isCurrent()) {
            closeCallbackLocked(subscription)
            return null
        }
        val now = nowMillis()
        if (now == null || now > expires) {
            closeCallbackLocked(subscription)
            return null
        }
        val task = try {
            watchdog.schedule(expires - now + 1L) {
                invalidated.set(true)
                retireInvalidated(key, id)
            }
        } catch (_: Exception) {
            closeCallbackLocked(subscription)
            return null
        }
        return Publication(id, guard, invalidated, subscription, task)
    }

    private fun scheduleInvalidation(key: VehicleOverlaySignalKey, id: Long) {
        // The callback may run under a vehicle-source lock. Dispatch outside it.
        try { watchdog.schedule(0L) { retireInvalidated(key, id) } }
        catch (_: Exception) { /* Independent hardware TTL still bounds this command. */ }
    }

    private fun scheduleRouteRevocation(key: VehicleOverlaySignalKey, slot: Slot) {
        try {
            watchdog.schedule(0L) {
                synchronized(lock) {
                    if (slots[key] === slot && slot.routeRevoked.get())
                        retireSlotLocked(key, slot)
                }
            }
        } catch (_: Exception) { /* Independent hardware TTL remains mandatory. */ }
    }

    private fun retireInvalidated(key: VehicleOverlaySignalKey, id: Long) {
        synchronized(lock) {
            val slot = slots[key] ?: return
            val publication = slot.publication ?: return
            if (publication.id != id ||
                (!publication.invalidated.get() && publication.guard.isCurrent())) return
            retireSlotLocked(key, slot)
        }
    }

    private fun commandDeadline(
        slot: Slot,
        guard: VehicleOverlayKnownSampleGuard?,
    ): Long? {
        val now = nowMillis() ?: return null
        val hardwareDeadline = try { Math.addExact(now, slot.maxHardwareTtlMillis) }
            catch (_: ArithmeticException) { return null }
        return minOf(hardwareDeadline, guard?.expiresElapsedRealtimeMillis ?: hardwareDeadline)
            .takeIf { it >= now }
    }

    private fun retireSlotLocked(key: VehicleOverlaySignalKey, slot: Slot): Boolean {
        if (slots[key] !== slot) return true
        slot.retirementPending = true
        val publication = slot.publication
        slot.publication = null
        closePublicationLocked(publication)
        slot.routeSubscription?.let { closeCallbackLocked(it) }
        slot.routeSubscription = null
        // Revoke only if a publish may have reached hardware. TTL bounds uncertain writes.
        if (slot.publishAttempted) {
            try { slot.lease.revoke(slot.epoch) } catch (_: Exception) { Unit }
        }
        return try {
            slot.lease.close()
            slots.remove(key)
            true
        } catch (_: Exception) {
            scheduleRetryLocked(key, slot)
            false
        }
    }

    private fun scheduleRetryLocked(key: VehicleOverlaySignalKey, slot: Slot) {
        if (slot.retryScheduled) return
        slot.retryScheduled = true
        try {
            watchdog.schedule(50L) {
                synchronized(lock) {
                    slot.retryScheduled = false
                    if (slots[key] === slot && slot.retirementPending) retireSlotLocked(key, slot)
                }
            }
        } catch (_: Exception) { slot.retryScheduled = false }
    }

    private fun closePublicationLocked(publication: Publication?) {
        if (publication == null) return
        closeCallbackLocked(publication.expiryTask)
        closeCallbackLocked(publication.sourceSubscription)
    }

    private fun closeCallbackLocked(handle: Closeable) {
        try { handle.close() } catch (_: Exception) { failedCallbackClosures += handle }
    }

    private fun retryCallbackClosuresLocked() {
        val iterator = failedCallbackClosures.iterator()
        while (iterator.hasNext()) {
            val handle = iterator.next()
            try { handle.close(); iterator.remove() } catch (_: Exception) { Unit }
        }
    }

    private fun currentSample(sample: VehicleOverlaySignalSample): Boolean = try {
        sourceEpoch() == sample.sourceEpoch && signalSource.isCurrentSample(sample) &&
            sourceEpoch() == sample.sourceEpoch
    } catch (_: Exception) { false }

    private fun sourceEpoch(): Long? = try {
        if (signalSource.isCurrent()) signalSource.currentEpoch()?.takeIf { it > 0L } else null
    } catch (_: Exception) { null }

    private fun nowMillis(): Long? = try { clock.nowMillis().takeIf { it >= 0L } }
    catch (_: Exception) { null }

    private fun knownSample(state: VehicleOverlaySignalState): VehicleOverlaySignalSample? =
        when (state) {
            is VehicleOverlaySignalState.KnownOn -> state.sample
            is VehicleOverlaySignalState.KnownOff -> state.sample
            is VehicleOverlaySignalState.Unknown -> null
        }

    private fun slotMatches(key: VehicleOverlaySignalKey, slot: Slot): Boolean =
        !slot.retirementPending && !slot.routeRevoked.get() &&
            snapshotRoute(key, slot.lease) == Route(slot.epoch, slot.maxHardwareTtlMillis)

    private fun snapshotRoute(
        key: VehicleOverlaySignalKey,
        lease: VehicleOverlayOutputLease,
    ): Route? {
        return try {
        if (lease.key != key || !lease.isCurrent()) return null
        val connector = lease.connectorEvidence
        val occlusion = lease.occlusionEvidence
        val expiry = lease.independentExpiryEvidence
        if (!connector.isCurrent() || !occlusion.isCurrent() || !expiry.isCurrent() ||
            !occlusion.isProvenAboveCarPlayPlanes() ||
            !expiry.isAutonomousHardwareExpiryProven()) return null
        val connectorId = connector.stableConnectorIdentity
        val hardwareId = connector.hardwareIdentity
        val planeId = occlusion.safetyPlaneIdentity
        if (!validIdentity(connectorId) || !validIdentity(hardwareId) ||
            !validIdentity(planeId)) return null
        val displayId = connector.displayId
        val displayEpoch = connector.displayEpoch
        val stackEpoch = occlusion.hardwareStackEpoch
        val ttl = expiry.maxCommandTtlMillis
        if (displayId < 0 || displayEpoch <= 0L || stackEpoch <= 0L ||
            ttl !in 1L..MAX_HARDWARE_COMMAND_TTL_MILLIS ||
            occlusion.stableConnectorIdentity != connectorId ||
            occlusion.displayId != displayId || occlusion.displayEpoch != displayEpoch ||
            expiry.key != key || expiry.stableConnectorIdentity != connectorId ||
            expiry.displayEpoch != displayEpoch) return null
        if (!lease.isCurrent() || !connector.isCurrent() || !occlusion.isCurrent() ||
            !expiry.isCurrent()) return null
        Route(VehicleOverlayDisplayEpoch(connectorId, hardwareId, displayId, displayEpoch,
            stackEpoch, planeId), ttl)
        } catch (_: Exception) { null }
    }

    private fun validIdentity(value: String): Boolean =
        value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)

    private fun result(
        key: VehicleOverlaySignalKey,
        reason: VehicleOverlayUnknownReason,
        disposition: VehicleOverlayRefreshDisposition,
    ) = VehicleOverlayRefresh(unknown(key, reason), null, disposition, null)

    private fun unknown(key: VehicleOverlaySignalKey, reason: VehicleOverlayUnknownReason) =
        VehicleOverlaySignalState.Unknown(key, reason)

    private companion object {
        const val MAX_HARDWARE_COMMAND_TTL_MILLIS = 100L
        val INVALID_EPOCH = VehicleOverlayDisplayEpoch("invalid", "invalid", -1, -1, -1,
            "invalid")
    }
}

internal enum class VehicleOverlayRefreshDisposition {
    ACCEPTED_UNCONFIRMED,
    NO_REVIEWED_FALLBACK,
    NO_PHYSICAL_OUTPUT_LEASE,
    UNTRUSTED_DISPLAY_ROUTE,
    WRITE_REJECTED,
    WRITE_UNCERTAIN,
    SOURCE_CHANGED_DURING_WRITE,
    RETIREMENT_RETRY_REQUIRED,
    RETIRED,
}

internal data class VehicleOverlayRefresh(
    val signalState: VehicleOverlaySignalState,
    val presentation: VehicleOverlayPresentation?,
    val disposition: VehicleOverlayRefreshDisposition,
    /** Port acceptance of one exact command; never optical or safety proof. */
    val acceptedDisplayEpoch: VehicleOverlayDisplayEpoch?,
)
