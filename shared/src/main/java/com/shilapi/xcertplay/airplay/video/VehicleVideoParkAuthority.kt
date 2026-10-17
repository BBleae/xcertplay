package com.shilapi.xcertplay.airplay.video

import com.shilapi.xcertplay.vehicle.CarPropertySupport
import com.shilapi.xcertplay.vehicle.CarPropertyVehicleProvider
import com.shilapi.xcertplay.vehicle.VehiclePlatform
import com.shilapi.xcertplay.vehicle.VideoParkSensorSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opaque identity for one video-capable transport session; equality is intentionally referential. */
internal class VehicleVideoSessionIdentity(val generation: Long) {
    init { require(generation > 0L) }
}

/**
 * An independently verified OEM source binding, deliberately unavailable from the current Android
 * Car provider. Reading a VHAL property, including `PARKING_BRAKE_ON`, does not issue this binding.
 * The eventual OEM verifier must bind the exact manager generation and transport session.
 */
internal class VehicleVideoParkSourceCertificate(
    val session: VehicleVideoSessionIdentity,
    val sourceIdentity: Any,
    val sourceGeneration: Long,
) {
    init { require(sourceGeneration > 0L) }
}

/** A future player must check this lease before each video presentation, not only at startup. */
internal class VehicleVideoParkLease private constructor(
    private val owner: VehicleVideoParkAuthority,
    val session: VehicleVideoSessionIdentity,
    internal val sourceIdentity: Any,
    internal val sourceGeneration: Long,
) {
    fun isCurrent(session: VehicleVideoSessionIdentity): Boolean = owner.isCurrent(this, session)

    companion object {
        internal fun issue(
            owner: VehicleVideoParkAuthority,
            session: VehicleVideoSessionIdentity,
            sample: VideoParkSensorSnapshot,
        ) = VehicleVideoParkLease(owner, session, sample.sourceIdentity, sample.sourceGeneration)
    }
}

/** Stop local video first, then tell the phone that video playback is disallowed. */
internal interface VehicleVideoParkRevocationSink {
    fun stopVideoPlayer()
    fun setVideoPlaybackAllowedFalse()
}

/**
 * Fail-closed parking authority for a future VideoPlayback integration. Construction does not
 * negotiate VideoPlayback, start a player, or send `setVideoPlaybackAllowed(true)`.
 *
 * The 200-ms provider acquisition is independent of iAP2 speed requests. An independent watchdog
 * retires a lease on stale/disconnected evidence even if the sampling worker stalls. Neither this
 * software poll nor a generic Android VHAL is an OEM-certified driving-safety signal: without an
 * exact [VehicleVideoParkSourceCertificate], [tryAcquireLease] always returns null.
 */
internal class VehicleVideoParkAuthority(
    private val provider: CarPropertyVehicleProvider,
    private val session: VehicleVideoSessionIdentity,
    private val certificate: VehicleVideoParkSourceCertificate?,
    private val revocationSink: VehicleVideoParkRevocationSink,
    private val now: () -> Long,
    private val maxSampleAgeMillis: Long = 450,
    private val maxEventAgeMillis: Long = 1_000,
    watchdogPeriodMillis: Long = 50,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var activeLease: VehicleVideoParkLease? = null
    private var revocationInProgress = false
    private var callbackFailures = 0
    private val subscription = provider.observeVideoParkEvidence(::onEvidence)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "video-park-watchdog").apply { isDaemon = true }
    }

    init {
        require(maxSampleAgeMillis in 1..2_000)
        require(maxEventAgeMillis in 1..5_000)
        require(watchdogPeriodMillis in 1..500)
        watchdog.scheduleWithFixedDelay(::checkAndRevoke, watchdogPeriodMillis,
            watchdogPeriodMillis, TimeUnit.MILLISECONDS)
    }

    /** No Boolean parameter exists: all park decisions derive from exact raw property readings. */
    fun tryAcquireLease(): VehicleVideoParkLease? {
        val evidence = provider.latestVideoParkEvidence()
        val checkedAt = now()
        var revoked = false
        val lease = synchronized(lock) {
            if (closed || revocationInProgress) return null
            if (!isEligible(evidence, checkedAt)) {
                revoked = retireLeaseLocked()
                null
            } else {
                activeLease ?: VehicleVideoParkLease.issue(this, session, evidence!!)
                    .also { activeLease = it }
            }
        }
        if (revoked) notifyRevocation()
        return lease
    }

    internal fun isCurrent(lease: VehicleVideoParkLease, requestedSession: VehicleVideoSessionIdentity): Boolean {
        val evidence = provider.latestVideoParkEvidence()
        val checkedAt = now()
        var revoked = false
        val current = synchronized(lock) {
            if (closed || activeLease !== lease || requestedSession !== session) return false
            if (!isEligible(evidence, checkedAt) || evidence!!.sourceIdentity !== lease.sourceIdentity ||
                evidence.sourceGeneration != lease.sourceGeneration
            ) {
                revoked = retireLeaseLocked()
                false
            } else true
        }
        if (revoked) notifyRevocation()
        return current
    }

    fun callbackFailureCount(): Int = synchronized(lock) { callbackFailures }

    private fun onEvidence(received: VideoParkSensorSnapshot?) {
        // An old callback may finish after a newer sample, unsubscribe, or session teardown.
        val current = provider.latestVideoParkEvidence()
        if (received !== current) return
        checkAndRevoke(current)
    }

    private fun checkAndRevoke() = checkAndRevoke(provider.latestVideoParkEvidence())

    private fun checkAndRevoke(evidence: VideoParkSensorSnapshot?) {
        val checkedAt = now()
        val revoked = synchronized(lock) {
            if (closed || activeLease == null) false
            else if (!isEligible(evidence, checkedAt) ||
                evidence!!.sourceIdentity !== activeLease!!.sourceIdentity ||
                evidence.sourceGeneration != activeLease!!.sourceGeneration
            ) retireLeaseLocked() else false
        }
        if (revoked) notifyRevocation()
    }

    private fun retireLeaseLocked(): Boolean {
        if (activeLease == null) return false
        activeLease = null
        revocationInProgress = true
        return true
    }

    private fun isEligible(evidence: VideoParkSensorSnapshot?, checkedAt: Long): Boolean {
        if (evidence == null || provider.platform != VehiclePlatform.STANDARD_ANDROID ||
            certificate == null || certificate.session !== session ||
            certificate.sourceIdentity !== evidence.sourceIdentity ||
            certificate.sourceGeneration != evidence.sourceGeneration ||
            checkedAt - evidence.sampledAtMillis !in 0..maxSampleAgeMillis
        ) return false
        val speed = evidence.speed.value as? Float ?: return false
        val gear = evidence.selectedGear.value as? Int ?: return false
        if (!speed.isFinite() || speed != 0f || gear != 4 ||
            !isFreshEvent(evidence.speed.sourceEventMillis, checkedAt) ||
            !isFreshEvent(evidence.selectedGear.sourceEventMillis, checkedAt)
        ) return false
        val brake = evidence.parkingBrake
        return if (brake != null) {
            brake.value && isFreshEvent(brake.sourceEventMillis, checkedAt)
        } else evidence.parkingBrakeSupport == CarPropertySupport.UNSUPPORTED
    }

    private fun isFreshEvent(eventAt: Long?, checkedAt: Long): Boolean =
        eventAt != null && eventAt > 0L && checkedAt - eventAt in 0..maxEventAgeMillis

    private fun notifyRevocation() {
        // Both callbacks are attempted outside the lock, even if the first callback fails.
        try {
            try { revocationSink.stopVideoPlayer() } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                synchronized(lock) { callbackFailures++ }
            }
            try { revocationSink.setVideoPlaybackAllowedFalse() } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                synchronized(lock) { callbackFailures++ }
            }
        } finally {
            synchronized(lock) { revocationInProgress = false }
        }
    }

    override fun close() {
        val revoked = synchronized(lock) {
            if (closed) return
            closed = true
            retireLeaseLocked()
        }
        subscription.close()
        watchdog.shutdownNow()
        if (revoked) notifyRevocation()
    }
}
