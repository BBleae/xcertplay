package com.shilapi.xcertplay.vehicle.essential

import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Canonical semantics only. No caller can submit a Canvas, bitmap, arbitrary text, or pixels. */
internal enum class VehicleEssentialTelltale {
    BRAKE_SYSTEM,
    SEAT_BELT,
    AIRBAG,
    ABS,
    OIL_PRESSURE,
    COOLANT_TEMPERATURE,
    CHARGING_SYSTEM,
    CHECK_ENGINE,
    TIRE_PRESSURE,
    LOW_FUEL,
}

internal data class VehicleOverlaySignalKey(
    val telltale: VehicleEssentialTelltale,
    /** Android vehicle-property area; zero is the global area. */
    val areaId: Int,
) {
    init { require(areaId >= 0) }
}

internal enum class VehicleOverlaySourceStatus { AVAILABLE, UNAVAILABLE, ERROR }
internal enum class VehicleOverlaySampleOrigin { REAL_VEHICLE, SIMULATED, UNKNOWN }

/**
 * A raw local vehicle-property result. A value of false is never sufficient to assert KnownOff:
 * the source, origin, status, area, acquisition timestamp, and freshness must all validate.
 */
internal data class VehicleOverlaySignalSample(
    val key: VehicleOverlaySignalKey,
    val status: VehicleOverlaySourceStatus,
    val active: Boolean?,
    val eventElapsedRealtimeMillis: Long?,
    val origin: VehicleOverlaySampleOrigin,
    /** Exact real-source generation that produced this sample; never reused across rebinds. */
    val sourceEpoch: Long,
    /** Monotonic per-key revision; increments on every value/status change in one source epoch. */
    val sampleRevision: Long,
)

/**
 * Must be backed by a separately owned real vehicle source, not CAF, CarPlay, a test Profile,
 * the UI, or a synthetic gauge value. isCurrent must revoke immediately on source loss.
 */
internal interface VehicleOverlaySignalSource {
    fun isCurrent(): Boolean
    fun currentEpoch(): Long?
    fun read(key: VehicleOverlaySignalKey): VehicleOverlaySignalSample?
    /** Atomically verifies the exact revision, value, status, and source epoch still match. */
    fun isCurrentSample(sample: VehicleOverlaySignalSample): Boolean
    /**
     * Atomic check-and-subscribe: invoke immediately if already changed, otherwise invoke on
     * any change/revocation. A gap between checking and registering is forbidden.
     */
    fun subscribeInvalidation(
        sample: VehicleOverlaySignalSample,
        onInvalidated: () -> Unit,
    ): Closeable

    companion object {
        val DENY_ALL: VehicleOverlaySignalSource = object : VehicleOverlaySignalSource {
            override fun isCurrent() = false
            override fun currentEpoch(): Long? = null
            override fun read(key: VehicleOverlaySignalKey): VehicleOverlaySignalSample? = null
            override fun isCurrentSample(sample: VehicleOverlaySignalSample) = false
            override fun subscribeInvalidation(
                sample: VehicleOverlaySignalSample,
                onInvalidated: () -> Unit,
            ) = Closeable { }.also { onInvalidated() }
        }
    }
}

internal fun interface VehicleOverlayElapsedRealtimeClock {
    fun nowMillis(): Long

    companion object {
        val ANDROID = VehicleOverlayElapsedRealtimeClock { SystemClock.elapsedRealtime() }
    }
}

/** Scheduling is monotonic, independent of any CarPlay or Activity lifecycle. */
internal fun interface VehicleOverlayWatchdogScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): Closeable

    companion object {
        private val executor = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "vehicle-essential-watchdog").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        val PROCESS_OWNED = VehicleOverlayWatchdogScheduler { delayMillis, action ->
            val task = executor.schedule({ action() }, delayMillis.coerceAtLeast(0L),
                TimeUnit.MILLISECONDS)
            Closeable { task.cancel(false) }
        }
    }
}

internal enum class VehicleOverlayUnknownReason {
    SOURCE_REVOKED,
    SOURCE_ERROR,
    SAMPLE_REVISED,
    NO_SAMPLE,
    WRONG_SIGNAL_OR_AREA,
    UNAVAILABLE,
    ERROR_STATUS,
    NON_REAL_ORIGIN,
    MISSING_VALUE,
    MISSING_TIMESTAMP,
    CLOCK_ERROR,
    FUTURE_TIMESTAMP,
    STALE_TIMESTAMP,
}

internal sealed interface VehicleOverlaySignalState {
    val key: VehicleOverlaySignalKey

    data class KnownOn(
        override val key: VehicleOverlaySignalKey,
        val ageMillis: Long,
        val sample: VehicleOverlaySignalSample,
    ) : VehicleOverlaySignalState

    data class KnownOff(
        override val key: VehicleOverlaySignalKey,
        val ageMillis: Long,
        val sample: VehicleOverlaySignalSample,
    ) : VehicleOverlaySignalState

    data class Unknown(
        override val key: VehicleOverlaySignalKey,
        val reason: VehicleOverlayUnknownReason,
    ) : VehicleOverlaySignalState
}

/** The only fallback modes accepted for an Unknown state; neither is an Off command. */
internal enum class VehicleOverlayUnknownFallback { DISTINCT_UNKNOWN, ASSERTED_ON_AS_UNKNOWN }

internal data class VehicleOverlayReviewedFallback(
    val telltale: VehicleEssentialTelltale,
    val mode: VehicleOverlayUnknownFallback,
    /** Identifies the OEM's per-telltale review; this string is not itself an approval proof. */
    val reviewReference: String,
) {
    init {
        require(reviewReference.isNotBlank() && reviewReference.length <= 128)
        require(reviewReference.none(Char::isISOControl))
    }
}

/** No generic warning-light fallback is presumed safe. OEM review must supply each mapping. */
internal fun interface VehicleOverlayReviewedFallbackPolicy {
    fun fallbackFor(telltale: VehicleEssentialTelltale): VehicleOverlayReviewedFallback?

    companion object {
        val DENY_ALL = VehicleOverlayReviewedFallbackPolicy { null }
    }
}

internal enum class VehicleOverlayPresentation {
    KNOWN_ON,
    KNOWN_OFF,
    DISTINCT_UNKNOWN,
    ASSERTED_ON_AS_UNKNOWN,
}

/** Independent physical connector evidence; Android display type/name/density is not evidence. */
internal interface VehicleOverlayPhysicalConnectorEvidence {
    val stableConnectorIdentity: String
    val hardwareIdentity: String
    val displayId: Int
    val displayEpoch: Long
    fun isCurrent(): Boolean
}

/**
 * Independent hardware composition/occlusion-order evidence for the essential overlay plane.
 * A software zIndex or a CarPlay/UI Sync session token must never implement this contract.
 */
internal interface VehicleOverlayOcclusionOrderEvidence {
    val stableConnectorIdentity: String
    val displayId: Int
    val displayEpoch: Long
    val hardwareStackEpoch: Long
    val safetyPlaneIdentity: String
    fun isProvenAboveCarPlayPlanes(): Boolean
    fun isCurrent(): Boolean
}

/**
 * Hardware-enforced command expiry is mandatory because Java close/revoke can fail or be delayed.
 * On expiry the independent safety plane must enter its reviewed hardware fail-safe, never keep
 * a previously accepted KNOWN_OFF. No implementation is supplied by this project.
 */
internal interface VehicleOverlayIndependentExpiryEvidence {
    val key: VehicleOverlaySignalKey
    val stableConnectorIdentity: String
    val displayEpoch: Long
    val maxCommandTtlMillis: Long
    fun isAutonomousHardwareExpiryProven(): Boolean
    fun isCurrent(): Boolean
}

/** Exact read-only authorization for one known sample, checked inside hardware dispatch. */
internal class VehicleOverlayKnownSampleGuard(
    val sample: VehicleOverlaySignalSample,
    val expiresElapsedRealtimeMillis: Long,
    private val currentCheck: () -> Boolean,
) {
    fun isCurrent(): Boolean = try { currentCheck() } catch (_: Exception) { false }
}

/** Immutable identity captured from both independently issued proofs. */
internal data class VehicleOverlayDisplayEpoch(
    val stableConnectorIdentity: String,
    val hardwareIdentity: String,
    val displayId: Int,
    val displayEpoch: Long,
    val hardwareStackEpoch: Long,
    val safetyPlaneIdentity: String,
)

internal enum class VehicleOverlayWriteResult {
    /** Port accepted the semantic command. This does not establish optical scanout. */
    ACCEPTED_UNCONFIRMED,
    REJECTED,
    UNCERTAIN,
}

/**
 * Exact display-epoch lease owned by the vehicle-essential domain. publish must revalidate the
 * expected epoch, physical connector, and hardware ordering *inside* its dispatch operation;
 * supervisor pre/post checks alone cannot close a revocation race. Closing must revoke all
 * commands from this lease or replace them with an independent hardware fail-safe. A provider
 * unable to meet those obligations must not issue a lease.
 */
internal interface VehicleOverlayOutputLease : Closeable {
    val key: VehicleOverlaySignalKey
    val connectorEvidence: VehicleOverlayPhysicalConnectorEvidence
    val occlusionEvidence: VehicleOverlayOcclusionOrderEvidence
    val independentExpiryEvidence: VehicleOverlayIndependentExpiryEvidence
    fun isCurrent(): Boolean
    /**
     * Atomic check-and-subscribe for connector, display epoch, occlusion, or expiry-proof loss.
     * Invoke immediately if already revoked. The hardware must independently invalidate the
     * old command on such loss; this callback only retires the Java owner promptly.
     */
    fun subscribeRevocation(onRevoked: () -> Unit): Closeable
    /** Must revoke this key's prior command, or independently expire it by its accepted TTL. */
    fun revoke(expectedDisplayEpoch: VehicleOverlayDisplayEpoch): VehicleOverlayWriteResult
    fun publish(
        expectedDisplayEpoch: VehicleOverlayDisplayEpoch,
        key: VehicleOverlaySignalKey,
        presentation: VehicleOverlayPresentation,
        /** Non-null for KNOWN_ON/KNOWN_OFF; validate inside dispatch, not only before it. */
        knownSampleGuard: VehicleOverlayKnownSampleGuard?,
        /** Hardware deadline; no command may survive this elapsedRealtime instant. */
        expiresElapsedRealtimeMillis: Long,
    ): VehicleOverlayWriteResult
}

/** No product output provider exists until the OEM physical/occlusion authority is supplied. */
internal fun interface VehicleOverlayOutputPort {
    /** One independent command lease per signal key; retiring one cannot erase another key. */
    fun acquire(key: VehicleOverlaySignalKey): VehicleOverlayOutputLease?

    companion object {
        val DENY_ALL = VehicleOverlayOutputPort { null }
    }
}

/**
 * Identity-only lifetime capability retained by the independent vehicle-essential owner. A
 * CarPlay/Activity lifecycle may observe the supervisor's result, but cannot retire its lease
 * merely by holding a supervisor reference or making a different owner token.
 */
internal class VehicleEssentialDomainOwner private constructor() {
    companion object {
        fun establishIndependentOwner() = VehicleEssentialDomainOwner()
    }
}
