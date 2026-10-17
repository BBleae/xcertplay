package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBatch
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus
import com.shilapi.xcertplay.ultra.egl.UltraEglInstrumentUploadReceipt
import com.shilapi.xcertplay.ultra.instrument.InstrumentDedicatedRasterPort
import com.shilapi.xcertplay.ultra.instrument.InstrumentFrameCommitResult
import com.shilapi.xcertplay.ultra.instrument.InstrumentFrameIntent
import com.shilapi.xcertplay.ultra.instrument.InstrumentFramePreflightResult
import com.shilapi.xcertplay.ultra.instrument.OwnedOpaqueArgbFrame
import java.io.Closeable

/**
 * Exact physical CLUSTER texture adapter. The EGL child owns the texture and the existing
 * MAIN+CLUSTER output batch; this port never borrows a window Surface or exposes a Canvas.
 * A successful replace includes the EGL-thread upload and, if a scene is current, its
 * full multi-output replay/swap/finish. It is not optical scan-out or OEM gauge readiness.
 */
internal class InstrumentEglRasterPort(
    private val operations: InstrumentRasterEglOperations,
) : InstrumentDedicatedRasterPort, Closeable {
    constructor(
        coordinator: AndroidUltraRendererSessionCoordinator,
        lease: AndroidUltraInstrumentRasterLease,
        batch: UiSyncPhysicalOutputBatch,
    ) : this(object : InstrumentRasterEglOperations {
        override val destination: DisplayDestination = lease.destination
        override val viewportPixels: PixelSize = lease.sizePixels
        override fun generationStatus(): UiSyncPhysicalGenerationStatus {
            val physical = batch.generationStatus
            val raster = coordinator.instrumentRasterGenerationStatus(
                lease, destination, viewportPixels)
            return when {
                physical == UiSyncPhysicalGenerationStatus.REVOKED ||
                    raster == UiSyncPhysicalGenerationStatus.REVOKED ->
                        UiSyncPhysicalGenerationStatus.REVOKED
                physical == UiSyncPhysicalGenerationStatus.BUSY ||
                    raster == UiSyncPhysicalGenerationStatus.BUSY ->
                        UiSyncPhysicalGenerationStatus.BUSY
                else -> UiSyncPhysicalGenerationStatus.CURRENT
            }
        }
        override fun upload(frame: IntArray): UltraEglInstrumentUploadReceipt =
            lease.replaceCompleteOpaqueFrame(frame)
        override fun uploadOwned(frame: OwnedOpaqueArgbFrame): UltraEglInstrumentUploadReceipt =
            lease.replaceOwnedCompleteOpaqueFrame(frame)
        override fun sealFailure(reason: Throwable) =
            coordinator.sealSemanticRendererFailure(reason)
        override fun retireOutput(): Boolean = coordinator.retireScreens()
        override fun releaseIngress() = lease.close()
    })

    @Volatile private var retired = false
    @Volatile private var retirementPending = false
    @Volatile private var outputRetired = false
    @Volatile private var safetyCleared = false
    @Volatile private var lastResult = InstrumentFrameCommitResult.REJECTED_NO_COMMIT
    @Volatile private var committedFrames = 0L

    override val destination: DisplayDestination get() = operations.destination
    override val viewportPixels: PixelSize get() = operations.viewportPixels
    override val isCurrent: Boolean get() = !retired && !retirementPending &&
        operations.generationStatus() == UiSyncPhysicalGenerationStatus.CURRENT

    internal val lastCommitResult: InstrumentFrameCommitResult get() = lastResult
    internal val committedFrameCount: Long get() = committedFrames
    override val committedFrameSequence: Long? get() = committedFrames.takeIf { it > 0L }
    internal val hasSafetyClear: Boolean get() = safetyCleared

    @Synchronized
    override fun preflightSubmission(intent: InstrumentFrameIntent):
        InstrumentFramePreflightResult {
        if (retired) return InstrumentFramePreflightResult.REJECTED_NO_COMMIT
        if (retirementPending) return requestPhysicalRetirement().toPreflight()
        val status = runCatching { operations.generationStatus() }.getOrElse {
            return requestPhysicalRetirement().toPreflight()
        }
        return when (status) {
            UiSyncPhysicalGenerationStatus.CURRENT -> InstrumentFramePreflightResult.READY
            UiSyncPhysicalGenerationStatus.BUSY -> if (intent == InstrumentFrameIntent.SAFETY_CLEAR &&
                !safetyCleared) requestPhysicalRetirement().toPreflight()
                else InstrumentFramePreflightResult.BUSY_UNCONFIRMED
            UiSyncPhysicalGenerationStatus.REVOKED ->
                requestPhysicalRetirement().toPreflight()
        }
    }

    /** Called only after the owner observes a complete unavailable/revoked raster commit. */
    @Synchronized internal fun noteSafetyClear() { safetyCleared = true }
    @Synchronized internal fun noteAvailable() { safetyCleared = false }

    @Synchronized
    override fun replaceCompleteOpaqueFrame(argbPixels: IntArray,
        safetyMarker: String): InstrumentFrameCommitResult {
        if (retired || safetyMarker != SAFETY_MARKER) return record(
            InstrumentFrameCommitResult.REJECTED_NO_COMMIT)
        if (retirementPending) return requestPhysicalRetirement()
        val pixelCount = viewportPixels.widthPixels.toLong() * viewportPixels.heightPixels
        if (pixelCount > MAXIMUM_PIXELS || argbPixels.size.toLong() != pixelCount) {
            return record(InstrumentFrameCommitResult.REJECTED_NO_COMMIT)
        }
        val owned = OwnedOpaqueArgbFrame.capture(argbPixels)
            ?: return record(InstrumentFrameCommitResult.REJECTED_NO_COMMIT)
        return replaceOwnedCompleteOpaqueFrame(owned, safetyMarker)
    }

    @Synchronized
    override fun replaceOwnedCompleteOpaqueFrame(frame: OwnedOpaqueArgbFrame,
        safetyMarker: String): InstrumentFrameCommitResult =
        replaceOwnedCompleteOpaqueFrame(frame, safetyMarker,
            InstrumentFrameIntent.NUMERIC_REFERENCE)

    @Synchronized
    override fun replaceOwnedCompleteOpaqueFrame(frame: OwnedOpaqueArgbFrame,
        safetyMarker: String, intent: InstrumentFrameIntent): InstrumentFrameCommitResult {
        val owned = frame.transfer() ?: return record(
            InstrumentFrameCommitResult.REJECTED_NO_COMMIT)
        if (retired || safetyMarker != SAFETY_MARKER) return record(
            InstrumentFrameCommitResult.REJECTED_NO_COMMIT)
        if (retirementPending) return requestPhysicalRetirement()
        val pixelCount = viewportPixels.widthPixels.toLong() * viewportPixels.heightPixels
        if (pixelCount > MAXIMUM_PIXELS || !owned.isCompleteFor(pixelCount)
        ) return record(InstrumentFrameCommitResult.REJECTED_NO_COMMIT)
        // A numeric attempt may cross an uncertain EGL completion before the caller can update
        // its bookkeeping. An earlier clear can no longer certify the physical output.
        if (intent == InstrumentFrameIntent.NUMERIC_REFERENCE) safetyCleared = false
        when (runCatching { operations.generationStatus() }.getOrNull()
            ?: return requestPhysicalRetirement()) {
            UiSyncPhysicalGenerationStatus.BUSY -> return busyOrRetire(intent)
            UiSyncPhysicalGenerationStatus.REVOKED -> return requestPhysicalRetirement()
            UiSyncPhysicalGenerationStatus.CURRENT -> Unit
        }
        // Only the frame token changes hands. Its private array is never recycled after a
        // render-thread timeout, because the queued/running EGL command may still read it.
        val upload = runCatching { operations.uploadOwned(owned) }
            .getOrDefault(UltraEglInstrumentUploadReceipt.COMPLETION_UNCERTAIN)
        if (upload == UltraEglInstrumentUploadReceipt.COMPLETION_UNCERTAIN) {
            return requestPhysicalRetirement()
        }
        if (upload == UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT) {
            return when (runCatching { operations.generationStatus() }.getOrNull()
                ?: return requestPhysicalRetirement()) {
                UiSyncPhysicalGenerationStatus.CURRENT -> record(
                    InstrumentFrameCommitResult.REJECTED_NO_COMMIT)
                UiSyncPhysicalGenerationStatus.BUSY -> busyOrRetire(intent)
                UiSyncPhysicalGenerationStatus.REVOKED -> requestPhysicalRetirement()
            }
        }
        committedFrames += 1L
        return when (runCatching { operations.generationStatus() }.getOrNull()
            ?: return requestPhysicalRetirement()) {
            UiSyncPhysicalGenerationStatus.CURRENT -> record(
                InstrumentFrameCommitResult.COMMITTED_CURRENT)
            UiSyncPhysicalGenerationStatus.BUSY -> busyOrRetire(intent)
            UiSyncPhysicalGenerationStatus.REVOKED -> requestPhysicalRetirement()
        }
    }

    private fun busyOrRetire(intent: InstrumentFrameIntent): InstrumentFrameCommitResult =
        if (intent == InstrumentFrameIntent.SAFETY_CLEAR && !safetyCleared)
            requestPhysicalRetirement()
        else record(InstrumentFrameCommitResult.BUSY_UNCONFIRMED)

    private fun InstrumentFrameCommitResult.toPreflight(): InstrumentFramePreflightResult =
        when (this) {
            InstrumentFrameCommitResult.COMMITTED_CURRENT ->
                error("Retirement cannot produce a frame commit")
            InstrumentFrameCommitResult.BUSY_UNCONFIRMED ->
                InstrumentFramePreflightResult.BUSY_UNCONFIRMED
            InstrumentFrameCommitResult.RETIREMENT_PENDING ->
                InstrumentFramePreflightResult.RETIREMENT_PENDING
            InstrumentFrameCommitResult.OUTPUT_RETIRED ->
                InstrumentFramePreflightResult.OUTPUT_RETIRED
            InstrumentFrameCommitResult.REJECTED_NO_COMMIT ->
                InstrumentFramePreflightResult.REJECTED_NO_COMMIT
        }

    /** Never writes into a truly revoked or replaced target; retire the old output instead. */
    @Synchronized
    internal fun requestPhysicalRetirement(): InstrumentFrameCommitResult {
        if (outputRetired) return record(InstrumentFrameCommitResult.OUTPUT_RETIRED)
        retirementPending = true
        runCatching { operations.sealFailure(IllegalStateException(
            "Reference instrument physical output requires retirement before reuse",
        )) }
        val proof = runCatching { operations.retireOutput() }.getOrDefault(false)
        if (proof) outputRetired = true
        return record(if (proof) InstrumentFrameCommitResult.OUTPUT_RETIRED
            else InstrumentFrameCommitResult.RETIREMENT_PENDING)
    }

    private fun record(result: InstrumentFrameCommitResult): InstrumentFrameCommitResult {
        lastResult = result
        return result
    }

    @Synchronized
    override fun close() {
        if (retired) return
        if (!safetyCleared || retirementPending ||
            operations.generationStatus() == UiSyncPhysicalGenerationStatus.REVOKED
        ) {
            val result = requestPhysicalRetirement()
            check(result == InstrumentFrameCommitResult.OUTPUT_RETIRED) {
                "Reference instrument output retirement remains pending"
            }
        }
        operations.releaseIngress()
        retired = true
    }

    private companion object {
        const val MAXIMUM_PIXELS = 1_600_000L
        const val SAFETY_MARKER = "BENCH / NOT VEHICLE SAFE"
    }
}

/** Pure seam for committed/revoked timing tests; production captures one exact child lease. */
internal interface InstrumentRasterEglOperations {
    val destination: DisplayDestination
    val viewportPixels: PixelSize
    fun generationStatus(): UiSyncPhysicalGenerationStatus
    fun upload(frame: IntArray): UltraEglInstrumentUploadReceipt
    fun uploadOwned(frame: OwnedOpaqueArgbFrame): UltraEglInstrumentUploadReceipt =
        frame.take()?.let(::upload) ?: UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
    fun sealFailure(reason: Throwable)
    fun retireOutput(): Boolean
    fun releaseIngress()
}
