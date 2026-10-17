package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import java.io.Closeable

internal class IdentityAttachmentProof<H : Any, S : Any> private constructor(
    private val handle: H,
    private val surface: S,
) {
    fun matches(handle: H, surface: S): Boolean =
        this.handle === handle && this.surface === surface

    companion object {
        fun <H : Any, S : Any> issue(handle: H, surface: S): IdentityAttachmentProof<H, S> =
            IdentityAttachmentProof(handle, surface)
    }
}

/** A legacy registration is identified by its sink, type, exact Surface and private source. */
internal class LegacySurfaceBindingProof<S : Any> private constructor(
    private val sink: Any,
    private val type: Int,
    private val surface: S,
    private val source: Any,
    private val selectedHandle: Any?,
    private val routeGeneration: Long,
    private val surfaceRouteGeneration: Long,
    private val installedToken: Any?,
) {
    fun matches(sink: Any, type: Int, surface: S): Boolean =
        this.sink === sink && this.type == type && this.surface === surface

    fun isCurrent(
        sink: Any,
        type: Int,
        surface: S,
        source: Any,
        selectedHandle: Any?,
        routeGeneration: Long,
        surfaceRouteGeneration: Long,
        installedToken: Any?,
    ): Boolean = matches(sink, type, surface) &&
        this.source === source && this.selectedHandle === selectedHandle &&
        this.routeGeneration == routeGeneration &&
        this.surfaceRouteGeneration == surfaceRouteGeneration &&
        this.installedToken === installedToken

    companion object {
        fun <S : Any> issue(
            sink: Any,
            type: Int,
            surface: S,
            source: Any,
            selectedHandle: Any?,
            routeGeneration: Long,
            surfaceRouteGeneration: Long,
            installedToken: Any?,
        ): LegacySurfaceBindingProof<S> = LegacySurfaceBindingProof(
            sink, type, surface, source, selectedHandle, routeGeneration,
            surfaceRouteGeneration, installedToken,
        )
    }
}

/** Result of proving that a MediaCodec decoder no longer references a leased Surface. */
enum class AndroidVideoSurfaceRetirementResult {
    /** The decoder worker acknowledged a replacement/detach request. */
    RETIRED,

    /** The lease had already been retired by a newer acknowledged generation. */
    ALREADY_RETIRED,

    /** Normal acknowledgement timed out, so the decoder was closed and its worker exited. */
    FORCED_DECODER_CLOSE,

    /** The sink closed its decoder before this retirement request. */
    SINK_CLOSED,

    /** The decoder worker did not exit within the bounded forced-close timeout. */
    DECODER_CLOSE_TIMED_OUT,

    /** The worker exited, but MediaCodec.release() failed and remains retryable. */
    DECODER_RELEASE_FAILED,

    /** The waiting thread was interrupted before retirement could be proven. */
    INTERRUPTED,
    ;

    val isSafeToReleaseSurface: Boolean
        get() = when (this) {
            RETIRED,
            ALREADY_RETIRED,
            FORCED_DECODER_CLOSE,
            SINK_CLOSED,
            -> true
            DECODER_CLOSE_TIMED_OUT,
            DECODER_RELEASE_FAILED,
            INTERRUPTED,
            -> false
        }
}

/**
 * Generation-bound ownership of one exact AirPlay stream's decoder output binding.
 *
 * [retire] is the result-bearing API. [close] uses a bounded default timeout for compatibility
 * with resource owners that only understand [Closeable]. This object never releases the Android
 * Surface itself; the caller may do so only after a safe retirement result.
 */
class AndroidVideoSurfaceLease private constructor(
    private val attachmentProof: IdentityAttachmentProof<AirPlayVideoStreamHandle, Surface>,
    permit: AttachmentPermit,
    val generation: Long,
    val replacementRetirementResult: AndroidVideoSurfaceRetirementResult?,
    private val retireAction: (Long) -> AndroidVideoSurfaceRetirementResult,
) : Closeable {
    private class AttachmentPermit private constructor() {
        companion object {
            val INSTANCE = AttachmentPermit()
        }
    }

    init {
        require(permit === AttachmentPermit.INSTANCE) { "Invalid Surface lease issuer" }
        require(replacementRetirementResult?.isSafeToReleaseSurface != false) {
            "A new Surface lease cannot be published before replacement retirement is proven"
        }
    }

    /** Exact attachment proof without exposing the handle's private session epoch. */
    fun matches(handle: AirPlayVideoStreamHandle, surface: Surface): Boolean =
        attachmentProof.matches(handle, surface)

    fun retire(
        timeoutMillis: Long = DEFAULT_RETIREMENT_TIMEOUT_MILLIS,
    ): AndroidVideoSurfaceRetirementResult {
        require(timeoutMillis in 0L..BoundedOperationDeadline.MAX_TIMEOUT_MILLIS) {
            "timeoutMillis exceeds the supported bounded retirement window"
        }
        return retireAction(timeoutMillis)
    }

    override fun close() {
        val result = retire()
        check(result.isSafeToReleaseSurface) {
            "Surface retirement is not proven: $result"
        }
    }

    companion object {
        const val DEFAULT_RETIREMENT_TIMEOUT_MILLIS = 1_000L

        /** Hidden from Java source; Kotlin callers outside this module cannot access `internal`. */
        @JvmSynthetic
        internal fun issue(
            handle: AirPlayVideoStreamHandle,
            surface: Surface,
            generation: Long,
            replacementRetirementResult: AndroidVideoSurfaceRetirementResult?,
            retireAction: (Long) -> AndroidVideoSurfaceRetirementResult,
        ): AndroidVideoSurfaceLease = AndroidVideoSurfaceLease(
            attachmentProof = IdentityAttachmentProof.issue(handle, surface),
            permit = AttachmentPermit.INSTANCE,
            generation = generation,
            replacementRetirementResult = replacementRetirementResult,
            retireAction = retireAction,
        )
    }
}

/** A conservative process-wide snapshot, including pending attach, close, and handoff owners. */
enum class AndroidSurfaceReservationState {
    FREE,
    RESERVED,
}

/** Never records completion until the exact owner was removed from the identity registry. */
internal class RetryableSurfaceReservationRelease(
    private val releaseAction: () -> Boolean,
    private val heldAction: () -> Boolean = { true },
) : Closeable {
    @Volatile private var released = false

    val isHeld: Boolean get() = !released && heldAction()

    @Synchronized
    override fun close() {
        if (released) return
        check(heldAction()) { "The exact Surface handoff reservation was lost" }
        check(releaseAction()) { "The exact Surface handoff reservation was not released" }
        released = true
    }
}

/** Result of transferring a pre-EGL handoff reservation back to its exact legacy route. */
sealed interface AndroidLegacySurfaceRestoreResult {
    /** The exact legacy registration is restored; the token no longer owns its reservation. */
    data object Restored : AndroidLegacySurfaceRestoreResult

    /** The old sink, selected stream, or registration generation is no longer current. */
    data object StaleOrUnavailable : AndroidLegacySurfaceRestoreResult

    /** Ownership remains with the token because restoration could not be proven complete. */
    data class Uncertain(val cause: Throwable) : AndroidLegacySurfaceRestoreResult
}

/** Opaque generation witness; only its issuing reservation can confirm external-use retirement. */
class AndroidVideoSurfaceExternalUseToken private constructor(
    private val owner: Any,
) {
    internal fun belongsTo(owner: Any): Boolean = this.owner === owner

    companion object {
        @JvmSynthetic
        internal fun issue(owner: Any): AndroidVideoSurfaceExternalUseToken =
            AndroidVideoSurfaceExternalUseToken(owner)
    }
}

/** One-way pre-EGL versus external-use gate shared by all handoff reservation implementations. */
internal class SurfaceHandoffReservationLifecycle(
    private val release: RetryableSurfaceReservationRelease,
    private val restoreAction: ((Long) -> AndroidLegacySurfaceRestoreResult)?,
) : Closeable {
    private val externalUseOwner = Any()
    private var externalUseToken: AndroidVideoSurfaceExternalUseToken? = null
    private var externalUseRetired = false
    private var restored = false
    private var restoreUncertain = false

    val isHeld: Boolean
        @Synchronized get() = !restored && release.isHeld

    @Synchronized
    fun beginExternalUse(): AndroidVideoSurfaceExternalUseToken {
        check(!restored && !restoreUncertain && release.isHeld) {
            "Surface handoff reservation cannot begin external use"
        }
        check(!externalUseRetired) { "External use already retired" }
        return externalUseToken ?: AndroidVideoSurfaceExternalUseToken.issue(externalUseOwner).also {
            externalUseToken = it
        }
    }

    /** Call only after exact EGL/router retirement has independently succeeded. */
    @Synchronized
    fun confirmExternalUseRetired(token: AndroidVideoSurfaceExternalUseToken) {
        check(externalUseToken === token && token.belongsTo(externalUseOwner)) {
            "External-use token belongs to another Surface handoff generation"
        }
        check(!restored && release.isHeld) {
            "Surface handoff reservation is not held"
        }
        externalUseRetired = true
    }

    @Synchronized
    fun restoreBeforeExternalUse(timeoutMillis: Long): AndroidLegacySurfaceRestoreResult {
        if (restored) return AndroidLegacySurfaceRestoreResult.Restored
        if (externalUseToken != null || !release.isHeld) {
            return AndroidLegacySurfaceRestoreResult.StaleOrUnavailable
        }
        val action = restoreAction ?: return AndroidLegacySurfaceRestoreResult.StaleOrUnavailable
        val result = try {
            action(timeoutMillis)
        } catch (error: Throwable) {
            AndroidLegacySurfaceRestoreResult.Uncertain(error)
        }
        when (result) {
            AndroidLegacySurfaceRestoreResult.Restored -> {
                restored = true
                restoreUncertain = false
            }
            is AndroidLegacySurfaceRestoreResult.Uncertain -> restoreUncertain = true
            AndroidLegacySurfaceRestoreResult.StaleOrUnavailable -> Unit
        }
        return result
    }

    @Synchronized
    override fun close() {
        if (restored) return // The source is now owned by the restored legacy route.
        check(!restoreUncertain) {
            "Surface restoration is uncertain; reservation cannot be released"
        }
        check(externalUseToken == null || externalUseRetired) {
            "External EGL/router retirement is not confirmed; reservation cannot be released"
        }
        release.close()
    }
}

/**
 * Exact process-wide Surface reservation retained across a proven legacy-decoder handoff.
 * Closing releases only the issuing registration; a failed release remains retryable.
 */
class AndroidVideoSurfaceHandoffReservation private constructor(
    private val proof: IdentityAttachmentProof<Any, Surface>,
    private val type: Int,
    private val lifecycle: SurfaceHandoffReservationLifecycle,
) : Closeable {
    val isHeld: Boolean get() = lifecycle.isHeld

    fun matches(sink: AndroidMediaSink, type: Int, surface: Surface): Boolean =
        this.type == type && proof.matches(sink, surface)

    /** Permanently forbids pre-EGL rollback; call immediately before the first EGL submission. */
    fun beginExternalUse(): AndroidVideoSurfaceExternalUseToken = lifecycle.beginExternalUse()

    /** Caller must have proven exact EGL/router retirement before invoking this stage. */
    fun confirmExternalUseRetired(token: AndroidVideoSurfaceExternalUseToken) =
        lifecycle.confirmExternalUseRetired(token)

    /** Restores only the exact old sink/stream generation, without releasing the global slot. */
    fun restoreLegacyBindingIfSameGeneration(
        timeoutMillis: Long = AndroidVideoSurfaceLease.DEFAULT_RETIREMENT_TIMEOUT_MILLIS,
    ): AndroidLegacySurfaceRestoreResult {
        require(timeoutMillis in 0L..BoundedOperationDeadline.MAX_TIMEOUT_MILLIS)
        return lifecycle.restoreBeforeExternalUse(timeoutMillis)
    }

    override fun close() = lifecycle.close()

    companion object {
        @JvmSynthetic
        internal fun issue(
            sink: AndroidMediaSink,
            type: Int,
            surface: Surface,
            heldAction: () -> Boolean,
            releaseAction: () -> Boolean,
            restoreAction: ((Long) -> AndroidLegacySurfaceRestoreResult)? = null,
        ): AndroidVideoSurfaceHandoffReservation = AndroidVideoSurfaceHandoffReservation(
            IdentityAttachmentProof.issue(sink, surface),
            type,
            SurfaceHandoffReservationLifecycle(
                RetryableSurfaceReservationRelease(releaseAction, heldAction),
                restoreAction,
            ),
        )
    }
}

/** No-binding and uncertainty are deliberately not interchangeable with a retirement proof. */
sealed interface AndroidLegacySurfaceHandoffResult {
    data object NoMatchingBinding : AndroidLegacySurfaceHandoffResult

    /** The exact binding is retired and the process-wide reservation remains held by [reservation]. */
    data class Reserved(
        val reservation: AndroidVideoSurfaceHandoffReservation,
        val decoderRetirement: AndroidVideoSurfaceRetirementResult?,
    ) : AndroidLegacySurfaceHandoffResult

    data class Uncertain(
        val reason: AndroidVideoSurfaceRetirementResult,
    ) : AndroidLegacySurfaceHandoffResult {
        init {
            require(!reason.isSafeToReleaseSurface) { "A proven retirement is not uncertain" }
        }
    }

    data class Failed(val cause: Throwable) : AndroidLegacySurfaceHandoffResult
}

/** Opaque capture of one existing legacy Host registration, never just a stream type. */
class AndroidLegacyVideoSurfaceLease private constructor(
    private val proof: LegacySurfaceBindingProof<Surface>,
    private val retireAction: (Long) -> AndroidLegacySurfaceHandoffResult,
) {
    private var completed: AndroidLegacySurfaceHandoffResult.Reserved? = null

    fun matches(sink: AndroidMediaSink, type: Int, surface: Surface): Boolean =
        proof.matches(sink, type, surface)

    @Synchronized
    fun retireAndReserveForHandoff(
        timeoutMillis: Long = AndroidVideoSurfaceLease.DEFAULT_RETIREMENT_TIMEOUT_MILLIS,
    ): AndroidLegacySurfaceHandoffResult {
        require(timeoutMillis in 0L..BoundedOperationDeadline.MAX_TIMEOUT_MILLIS) {
            "timeoutMillis exceeds the supported bounded retirement window"
        }
        completed?.let { return if (it.reservation.isHeld) it else AndroidLegacySurfaceHandoffResult.NoMatchingBinding }
        return retireAction(timeoutMillis).also { result ->
            if (result is AndroidLegacySurfaceHandoffResult.Reserved) completed = result
        }
    }

    companion object {
        @JvmSynthetic
        internal fun issue(
            proof: LegacySurfaceBindingProof<Surface>,
            retireAction: (Long) -> AndroidLegacySurfaceHandoffResult,
        ): AndroidLegacyVideoSurfaceLease = AndroidLegacyVideoSurfaceLease(proof, retireAction)
    }
}
