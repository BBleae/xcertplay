package com.shilapi.xcertplay.ultra.egl

import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraContentId
import com.shilapi.xcertplay.ultra.UltraMultiOutputRenderSnapshot
import com.shilapi.xcertplay.ultra.UltraOutputScene
import com.shilapi.xcertplay.ultra.UltraSceneBackground
import com.shilapi.xcertplay.ultra.UltraSceneLayer
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.media.AndroidVideoSurfaceRetirementResult
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exact producer namespace used by the EGL compositor. No source is inferred from opaque text. */
sealed interface UltraEglIngressKey {
    data class Remote(val ingressId: UltraIngressId) : UltraEglIngressKey
    data class Local(val contentId: UltraContentId) : UltraEglIngressKey
    data class Overlay(val contentId: UltraContentId) : UltraEglIngressKey
    data class Instrument(val destination: DisplayDestination) : UltraEglIngressKey
}

/** Generation and referential resource identity make late SurfaceTexture callbacks ABA-safe. */
internal class UltraEglFrameToken(
    val key: UltraEglIngressKey,
    val generation: Long,
    val ingress: UltraEglDriverIngress,
) {
    override fun equals(other: Any?): Boolean =
        other is UltraEglFrameToken &&
            key == other.key &&
            generation == other.generation &&
            ingress === other.ingress

    override fun hashCode(): Int =
        31 * (31 * key.hashCode() + generation.hashCode()) + System.identityHashCode(ingress)
}

/**
 * One buffer actually acquired by updateTexImage on the EGL owner thread. The timestamp comes
 * from that SurfaceTexture, not a decoder callback count or an inferred presentation time. This
 * is only a consumer observation; it is not a scene, display, or optical scan-out receipt.
 */
internal class UltraEglAcquiredRemoteFrame internal constructor(
    val key: UltraEglIngressKey.Remote,
    val ingressGeneration: Long,
    val ingress: UltraEglDriverIngress,
    val updateSequence: Long,
    val surfaceTextureTimestampNanos: Long,
)

/** Terminal state is fail-closed: rendering and topology mutations are rejected after a loss. */
enum class UltraEglBackendPhase {
    STARTING,
    OPEN,
    FAILED,
    RETIREMENT_UNCERTAIN,
    CLOSED,
}

enum class UltraEglRenderDisposition {
    PRESENTED,
    COALESCED,
    FAILED,
}

data class UltraEglRenderResult(
    val revision: Long,
    val disposition: UltraEglRenderDisposition,
    val failure: Throwable? = null,
    /** Exact EGL producer timestamps, sealed only after every swap and the final GPU finish. */
    val producerTimestampNanosByDestination: Map<DisplayDestination, Long> = emptyMap(),
    /** Native output lease generations for this scene, not physical scan-out evidence. */
    val outputGenerationsByDestination: Map<DisplayDestination, Long> = emptyMap(),
    val outputBatchGeneration: Long? = null,
    val graphRevision: Long? = null,
)

/** Render-thread-only result; a timestamp is absent when the driver cannot prove its EGL value. */
internal class UltraEglSealedPresentationReceipt internal constructor(
    val revision: Long,
    val graphRevision: Long?,
    val outputBatchGeneration: Long?,
    val outputGenerationsByDestination: Map<DisplayDestination, Long>,
    val producerTimestampNanosByDestination: Map<DisplayDestination, Long>,
    val clusterReplay: UltraEglClusterReplayReceipt?,
)

internal enum class UltraEglClusterReplayCause {
    SCENE_SUBMISSION,
    INSTRUMENT_UPLOAD,
    INGRESS_FRAME_REPLAY,
    LOCAL_FIRST_FRAME,
}

/**
 * One exact CLUSTER producer swap after all outputs' swaps and GPU finish. The instrument content
 * sequence increments on successful GL texture uploads; a MAIN ingress replay retains that
 * sequence but increments [replaySequence]. This is not an optical scan-out or safety permit.
 */
internal class UltraEglClusterReplayReceipt internal constructor(
    val destination: DisplayDestination,
    val sceneRevision: Long,
    val graphRevision: Long?,
    val nativeOutputBatchGeneration: Long,
    val nativeOutputGeneration: Long,
    val instrumentIngressGeneration: Long,
    val instrumentFrameSequence: Long,
    val producerTimestampNanos: Long,
    val replaySequence: Long,
    val cause: UltraEglClusterReplayCause,
    /** Sealed from the exact submitted scene, not inferred from a signed profile or caller flag. */
    val instrumentOnlyClusterScene: Boolean,
)

/** Completion for an asynchronously submitted complete multi-output scene. */
class UltraEglRenderTicket internal constructor(val revision: Long) {
    private val completed = AtomicBoolean(false)
    private val result = AtomicReference<UltraEglRenderResult?>()
    private val latch = CountDownLatch(1)

    internal fun complete(value: UltraEglRenderResult) {
        if (completed.compareAndSet(false, true)) {
            result.set(value)
            latch.countDown()
        }
    }

    fun await(timeoutMillis: Long = DEFAULT_RENDER_TIMEOUT_MILLIS): UltraEglRenderResult {
        require(timeoutMillis >= 0L) { "timeoutMillis must be non-negative" }
        check(latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            "Timed out waiting for EGL render revision $revision"
        }
        return checkNotNull(result.get())
    }

    companion object {
        const val DEFAULT_RENDER_TIMEOUT_MILLIS = 5_000L
    }
}

data class UltraEglBackendDiagnostics(
    val phase: UltraEglBackendPhase,
    val ingressCount: Int,
    val outputCount: Int,
    val lastPresentedRevision: Long,
    val coalescedSceneCount: Long,
    val coalescedFrameCount: Long,
    val failure: Throwable?,
)

enum class UltraEglBackendRetirementResult {
    RETIRED,
    STARTUP_IN_FLIGHT,
    CONTROL_IN_FLIGHT,
    EXTERNAL_ATTACHMENT_IN_FLIGHT,
    DECODER_RETIREMENT_UNCERTAIN,
    DRIVER_RETIREMENT_UNCERTAIN,
    RENDER_THREAD_STOP_UNCERTAIN,
    ;

    val isRetired: Boolean
        get() = this == RETIRED
}

/** A driver error whose meaning is stable across the real EGL14 and JVM fake drivers. */
internal open class UltraEglDriverException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class UltraEglContextLostException(message: String, cause: Throwable? = null) :
    UltraEglDriverException(message, cause)

internal class UltraEglSurfaceLostException(
    val destination: DisplayDestination,
    message: String,
    cause: Throwable? = null,
) : UltraEglDriverException(message, cause)

/** A live EGLSurface no longer matches the physical pixels of its frozen output lease. */
internal class UltraEglOutputSizeChangedException(
    val destination: DisplayDestination,
    val expectedSize: PixelSize,
    val actualSize: PixelSize,
) : UltraEglDriverException(
    "EGL output $destination resized from " +
        "${expectedSize.widthPixels}x${expectedSize.heightPixels} to " +
        "${actualSize.widthPixels}x${actualSize.heightPixels}; rebind is required",
)

/** Preflight target loss is a rejected scene, not an EGL driver or context failure. */
internal class UltraEglOutputTargetUnavailableException(
    val destination: DisplayDestination,
    cause: Throwable? = null,
) : IllegalStateException("Android output target is no longer valid for $destination", cause)

/** A failed topology change whose candidate native resource could not be proven retired. */
internal class UltraEglCandidateResourceRetainedException(
    cause: Throwable,
    cleanupFailure: Throwable,
) : UltraEglDriverException(
    "Failed EGL output candidate is retained because retirement could not be proven",
    cause,
) {
    init {
        addSuppressed(cleanupFailure)
    }
}

internal interface UltraEglDriverIngress {
    /** Android's implementation returns a borrowed android.view.Surface. */
    val producerEndpoint: Any

    /** False until updateTexImage and getTransformMatrix have both succeeded at least once. */
    val hasUsableFrame: Boolean

    /** Android's exact post-update SurfaceTexture timestamp; fake drivers supply no evidence. */
    val lastAcquiredTimestampNanos: Long?
        get() = null
}

internal interface UltraEglDriverOutput

internal data class UltraEglResolvedLayer(
    val layer: UltraSceneLayer,
    val ingress: UltraEglDriverIngress?,
)

internal data class UltraEglResolvedScene(
    val destination: DisplayDestination,
    val viewportPixels: PixelSize,
    val background: UltraSceneBackground,
    val layers: List<UltraEglResolvedLayer>,
)

/** An exact scene layer actually drawn into one output; not a claim about visible pixels. */
internal data class UltraEglLocalLayerDraw(
    val destination: DisplayDestination,
    val key: UltraEglIngressKey.Local,
    val layerIndex: Int,
    val layer: UltraSceneLayer,
)

/**
 * Render-thread snapshot taken after the scene enqueue and before fresh Canvas posts. An already
 * proven source may be carried only from the immediately preceding receipt, with its callback and
 * update counts unchanged. This is not a readiness or presentation receipt.
 */
internal class UltraEglLocalFirstFrameExpectation internal constructor(
    internal val submission: UltraEglResolvedSubmission,
    /** Bridge/router generation, independently checked by the session coordinator. */
    internal val physicalBatchGeneration: Long,
    /** Native EGL output batch generation, independently checked on the render thread. */
    internal val outputBatchGeneration: Long,
    internal val requiredDraws: Set<UltraEglLocalLayerDraw>,
    internal val frameSignalsBeforePost: Map<UltraEglIngressKey.Local, Long>,
    internal val carriedFrom: UltraEglLocalFirstFrameReceipt?,
    internal val carriedKeys: Set<UltraEglIngressKey.Local>,
    internal val carriedSignals: Map<UltraEglIngressKey.Local, Long>,
    internal val carriedUpdates: Map<UltraEglIngressKey.Local, Long>,
)

/**
 * Issued only after every fresh ingress's first successful producer post and one corresponding
 * SurfaceTexture callback/update, while each carried ingress remains frozen at its prior proven
 * callback/update counts, followed by each exact required local draw, all scene-output swaps,
 * and GPU finish.
 * A callback is not an exact producer-buffer ID: this does not prove pixel identity, optical
 * visibility, panel scan-out, phone acceptance, or UI Sync protocol readiness.
 */
internal class UltraEglLocalFirstFrameReceipt internal constructor(
    val revision: Long,
    val graphRevision: Long?,
    val physicalBatchGeneration: Long,
    val outputBatchGeneration: Long,
    val requiredDraws: Set<UltraEglLocalLayerDraw>,
    val postSequences: Map<UltraEglIngressKey.Local, Long>,
    /** Exact ingress generations and consumed callback counts, not producer-buffer identities. */
    internal val ingressGenerations: Map<UltraEglIngressKey.Local, Long>,
    internal val frameSignals: Map<UltraEglIngressKey.Local, Long>,
    internal val frameUpdates: Map<UltraEglIngressKey.Local, Long>,
    /** Exact producer timestamps of this extra swap, not the earlier scene-ticket swap. */
    val producerTimestampNanosByDestination: Map<DisplayDestination, Long> = emptyMap(),
    val outputGenerationsByDestination: Map<DisplayDestination, Long> = emptyMap(),
    internal val clusterReplay: UltraEglClusterReplayReceipt? = null,
)

/**
 * Thread-confined graphics operations. Every method is invoked on one render thread. The driver
 * owns the producer endpoint returned by [createExternalIngress] and releases it only from
 * [destroyIngress]. Native output windows are borrowed and never released by the driver.
 */
internal data class UltraEglDriverRetirementProof(
    val retired: Boolean,
    val failure: Throwable? = null,
)

/** The timestamp set on this output's successful EGL swap, never a wall-clock observation. */
internal data class UltraEglDriverRenderedFrame(
    val localDraws: Set<UltraEglLocalLayerDraw>,
    val producerTimestampNanos: Long?,
)

internal interface UltraEglDriver {
    fun initialize()

    fun createExternalIngress(
        key: UltraEglIngressKey,
        /** Explicit physical producer size for local/overlay; decoder-owned remote size is null. */
        sizePixels: PixelSize?,
        onFrameAvailable: (UltraEglDriverIngress) -> Unit,
    ): UltraEglDriverIngress

    /** GL_TEXTURE_2D ingress, no producer Surface or SurfaceTexture is ever created. */
    fun createInstrumentIngress(
        destination: DisplayDestination,
        sizePixels: PixelSize,
    ): UltraEglDriverIngress = throw UnsupportedOperationException(
        "This EGL driver has no dedicated instrument raster ingress",
    )

    /** Caller supplies one complete, copied opaque ARGB frame on the owner render thread. */
    fun uploadInstrumentFrame(ingress: UltraEglDriverIngress, argbPixels: IntArray) {
        throw UnsupportedOperationException("This EGL driver cannot upload an instrument frame")
    }

    fun updateIngressFrame(ingress: UltraEglDriverIngress)

    fun createWindowOutput(
        destination: DisplayDestination,
        nativeWindow: Any,
        sizePixels: PixelSize,
    ): UltraEglDriverOutput

    /** Recheck actual EGLSurface pixels before a multi-output batch presents any output. */
    fun validateOutputSize(output: UltraEglDriverOutput, expectedSize: PixelSize)

    fun render(output: UltraEglDriverOutput, scene: UltraEglResolvedScene)
    /** Default drivers provide no local-draw proof, even if their ordinary render swaps. */
    fun renderWithLocalDrawReceipt(
        output: UltraEglDriverOutput,
        scene: UltraEglResolvedScene,
    ): Set<UltraEglLocalLayerDraw> {
        render(output, scene)
        return emptySet()
    }
    /** Fake/legacy drivers cannot manufacture Android producer-timestamp evidence. */
    fun renderWithPresentationReceipt(
        output: UltraEglDriverOutput,
        scene: UltraEglResolvedScene,
    ): Long? {
        render(output, scene)
        return null
    }
    fun renderWithLocalDrawPresentationReceipt(
        output: UltraEglDriverOutput,
        scene: UltraEglResolvedScene,
    ): UltraEglDriverRenderedFrame = UltraEglDriverRenderedFrame(
        renderWithLocalDrawReceipt(output, scene), null,
    )
    fun clear(output: UltraEglDriverOutput, destination: DisplayDestination, sizePixels: PixelSize)
    fun clearWithPresentationReceipt(
        output: UltraEglDriverOutput,
        destination: DisplayDestination,
        sizePixels: PixelSize,
    ): Long? {
        clear(output, destination, sizePixels)
        return null
    }

    /** GPU retirement baseline used before any input/output resource is released. */
    fun finish()

    fun destroyIngress(ingress: UltraEglDriverIngress)
    fun destroyOutput(output: UltraEglDriverOutput)

    /** Retryable full teardown. retired=true is the only proof that native references are gone. */
    fun closeWithProof(deadlineNanos: Long): UltraEglDriverRetirementProof
}

internal class UltraEglPendingControlTimeoutException(message: String) :
    IllegalStateException(message)

internal class UltraEglRunningControlTimeoutException(message: String) :
    IllegalStateException(message)

internal class UltraEglStartupTimeoutException(message: String) :
    IllegalStateException(message)

/** Non-Android view of the render core, useful for deterministic state-machine tests. */
internal data class UltraEglCoreSnapshot(
    val phase: UltraEglBackendPhase,
    val ingressStates: Map<UltraEglIngressKey, String>,
    val outputSizes: Map<DisplayDestination, PixelSize>,
    val lastPresentedRevision: Long,
    val failure: Throwable?,
)

/**
 * Immutable result of binding a scene to the backend's exact output and ingress lease generations.
 * The render thread consumes this object directly and never re-queries a mutable graph registry.
 */
class UltraEglResolvedSubmission internal constructor(
    internal val scene: UltraMultiOutputRenderSnapshot,
    val graphRevision: Long?,
    internal val ingressGenerations: Map<UltraEglIngressKey, Long>,
    internal val outputGenerations: Map<DisplayDestination, Long>,
) {
    val revision: Long
        get() = scene.revision
}

/** Internal topology token. Identity plus generation prevents stale lease callbacks. */
internal class UltraEglLeaseToken(
    val key: Any,
    val generation: Long,
)

/** Public closeable input endpoint. The Surface is borrowed and must not be released by callers. */
abstract class UltraEglIngressLease internal constructor() : Closeable {
    abstract val key: UltraEglIngressKey
    abstract val producerSurface: android.view.Surface

    /** Remote inputs return decoder retirement evidence; local inputs return null. */
    abstract fun retire(
        timeoutMillis: Long = com.shilapi.xcertplay.media.AndroidVideoSurfaceLease
            .DEFAULT_RETIREMENT_TIMEOUT_MILLIS,
    ): AndroidVideoSurfaceRetirementResult?

    final override fun close() {
        val result = retire()
        check(result == null || result.isSafeToReleaseSurface) {
            "Surface retirement is not proven: $result"
        }
    }
}

/** Dedicated texture-upload lease. It has no Android Surface or Canvas producer endpoint. */
internal abstract class UltraEglInstrumentRasterLease : Closeable {
    abstract val key: UltraEglIngressKey.Instrument
    abstract val generation: Long
    abstract val sizePixels: PixelSize
    abstract fun replaceCompleteOpaqueFrame(argbPixels: IntArray): UltraEglInstrumentUploadReceipt
    /** Production leases transfer this token to the EGL queue without another array copy. */
    internal open fun replaceOwnedCompleteOpaqueFrame(
        frame: com.shilapi.xcertplay.ultra.instrument.OwnedOpaqueArgbFrame,
    ): UltraEglInstrumentUploadReceipt = frame.take()?.let(::replaceCompleteOpaqueFrame)
        ?: UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
}

/** A completed EGL control command may still race AA05 or physical-generation retirement. */
internal enum class UltraEglInstrumentUploadReceipt {
    COMMITTED,
    REJECTED_NO_COMMIT,
    COMPLETION_UNCERTAIN,
}

/**
 * Public output lease. The owner must keep a borrowed target Surface alive until [close] returns;
 * then EGL and the GPU no longer reference it. A preflight validity check is not a native pin.
 */
abstract class UltraEglOutputLease internal constructor() : Closeable {
    abstract val destination: DisplayDestination
}
