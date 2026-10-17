package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus
import com.shilapi.xcertplay.ultra.egl.UltraEglInstrumentUploadReceipt
import com.shilapi.xcertplay.ultra.instrument.InstrumentFrameCommitResult
import com.shilapi.xcertplay.ultra.instrument.InstrumentFrameIntent
import com.shilapi.xcertplay.ultra.instrument.InstrumentFramePreflightResult
import com.shilapi.xcertplay.ultra.instrument.OwnedOpaqueArgbFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentEglRasterPortTest {
    private val frame = IntArray(320 * 200) { 0xff202428.toInt() }
    private val marker = "BENCH / NOT VEHICLE SAFE"

    @Test fun busySafetyClearOnlyRetiresWhenOldNumericMayRemainVisible() {
        val backend = FakeOperations().apply { status = UiSyncPhysicalGenerationStatus.BUSY }
        val port = InstrumentEglRasterPort(backend)
        port.noteSafetyClear()
        assertEquals(InstrumentFramePreflightResult.BUSY_UNCONFIRMED,
            port.preflightSubmission(InstrumentFrameIntent.SAFETY_CLEAR))
        assertEquals(0, backend.retireCalls)
        port.noteAvailable()
        assertEquals(InstrumentFramePreflightResult.OUTPUT_RETIRED,
            port.preflightSubmission(InstrumentFrameIntent.SAFETY_CLEAR))
        assertEquals(1, backend.retireCalls)
        port.close()
    }

    @Test fun rawCallerMutationDuringUploadCannotChangePrivateSnapshot() {
        val callerFrame = frame.copyOf()
        val backend = FakeOperations().apply {
            beforeUpload = { callerFrame.fill(0xffeeeeee.toInt()) }
        }
        val port = InstrumentEglRasterPort(backend)
        assertEquals(InstrumentFrameCommitResult.COMMITTED_CURRENT,
            port.replaceCompleteOpaqueFrame(callerFrame, marker))
        assertEquals(0xff202428.toInt(), backend.uploaded.single()[0])
        port.noteSafetyClear()
        port.close()
    }

    @Test fun ownedFrameCanOnlyBeSubmittedOnceEvenAfterCurrentCommit() {
        val backend = FakeOperations()
        val port = InstrumentEglRasterPort(backend)
        val owned = requireNotNull(OwnedOpaqueArgbFrame.capture(frame))
        assertEquals(InstrumentFrameCommitResult.COMMITTED_CURRENT,
            port.replaceOwnedCompleteOpaqueFrame(owned, marker))
        assertEquals(InstrumentFrameCommitResult.REJECTED_NO_COMMIT,
            port.replaceOwnedCompleteOpaqueFrame(owned, marker))
        assertEquals(1, backend.uploaded.size)
        port.noteSafetyClear()
        port.close()
    }

    @Test fun wrongSizeRawFrameIsRejectedBeforeBackendUpload() {
        val backend = FakeOperations()
        val port = InstrumentEglRasterPort(backend)
        assertEquals(InstrumentFrameCommitResult.REJECTED_NO_COMMIT,
            port.replaceCompleteOpaqueFrame(intArrayOf(-1), marker))
        assertTrue(backend.uploaded.isEmpty())
        assertEquals(InstrumentFrameCommitResult.OUTPUT_RETIRED,
            port.requestPhysicalRetirement())
        port.close()
    }

    @Test fun busyThenCurrentDoesNotConfuseTransientProbeWithRevocation() {
        val backend = FakeOperations().apply { status = UiSyncPhysicalGenerationStatus.BUSY }
        val port = InstrumentEglRasterPort(backend)
        assertEquals(InstrumentFrameCommitResult.BUSY_UNCONFIRMED,
            port.replaceCompleteOpaqueFrame(frame, marker))
        assertTrue(backend.uploaded.isEmpty())
        assertEquals(0, backend.retireCalls)
        backend.status = UiSyncPhysicalGenerationStatus.CURRENT
        assertEquals(InstrumentFrameCommitResult.COMMITTED_CURRENT,
            port.replaceCompleteOpaqueFrame(frame, marker))
        port.noteSafetyClear()
        port.close()
        assertEquals(1, backend.releaseCalls)
        assertEquals(0, backend.retireCalls)
    }

    @Test fun committedThenRevokedRetiresExactOldOwnerWithoutStaleFallbackUpload() {
        val old = FakeOperations().apply {
            onUpload = { status = UiSyncPhysicalGenerationStatus.REVOKED }
            canRetire = false
        }
        val port = InstrumentEglRasterPort(old)
        assertEquals(InstrumentFrameCommitResult.RETIREMENT_PENDING,
            port.replaceCompleteOpaqueFrame(frame, marker))
        assertEquals(1, old.uploaded.size)
        assertEquals(1, old.retireCalls)
        assertEquals(1, old.sealCalls)
        assertFalse(port.isCurrent)
        assertThrows(IllegalStateException::class.java) { port.close() }
        assertEquals(0, old.releaseCalls)

        // A replacement CLUSTER owner is wholly separate; retrying the old retirement must not
        // write any pixel to it or to the revoked old ingress.
        val newOwner = FakeOperations()
        val replacement = InstrumentEglRasterPort(newOwner)
        assertEquals(InstrumentFrameCommitResult.COMMITTED_CURRENT,
            replacement.replaceCompleteOpaqueFrame(frame, marker))
        old.canRetire = true
        assertEquals(InstrumentFrameCommitResult.OUTPUT_RETIRED,
            port.requestPhysicalRetirement())
        port.close()
        assertEquals(1, old.uploaded.size)
        assertEquals(1, newOwner.uploaded.size)
        assertEquals(1, old.releaseCalls)
        replacement.noteSafetyClear()
        replacement.close()
    }

    @Test fun uncertainCompletionAndFailedReleaseKeepRetirementRetryable() {
        val backend = FakeOperations().apply {
            uploadResult = UltraEglInstrumentUploadReceipt.COMPLETION_UNCERTAIN
            canRetire = false
        }
        val port = InstrumentEglRasterPort(backend)
        assertEquals(InstrumentFrameCommitResult.RETIREMENT_PENDING,
            port.replaceCompleteOpaqueFrame(frame, marker))
        assertThrows(IllegalStateException::class.java) { port.close() }
        assertEquals(0, backend.releaseCalls)
        backend.canRetire = true
        backend.failFirstRelease = true
        assertThrows(IllegalStateException::class.java) { port.close() }
        assertEquals(1, backend.releaseCalls)
        port.close()
        assertEquals(2, backend.releaseCalls)
        assertEquals(1, backend.uploaded.size)
    }

    @Test fun possiblyCommittedAvailableFrameInvalidatesEarlierSafetyClear() {
        val backend = FakeOperations()
        val port = InstrumentEglRasterPort(backend)
        assertEquals(InstrumentFrameCommitResult.COMMITTED_CURRENT,
            port.replaceCompleteOpaqueFrame(frame, marker))
        port.noteSafetyClear() // initial BENCH DATA UNAVAILABLE
        assertTrue(port.hasSafetyClear)
        port.noteAvailable() // source may now commit numeric pixels
        backend.onUpload = { backend.status = UiSyncPhysicalGenerationStatus.BUSY }
        assertEquals(InstrumentFrameCommitResult.BUSY_UNCONFIRMED,
            port.replaceCompleteOpaqueFrame(frame, marker))
        assertFalse(port.hasSafetyClear)
        assertEquals(InstrumentFrameCommitResult.OUTPUT_RETIRED,
            port.requestPhysicalRetirement())
        port.close()
        assertEquals(2, backend.uploaded.size)
        assertEquals(1, backend.retireCalls)
    }

    private class FakeOperations : InstrumentRasterEglOperations {
        override val destination = DisplayDestination.CLUSTER
        override val viewportPixels = PixelSize(320, 200)
        var status = UiSyncPhysicalGenerationStatus.CURRENT
        var uploadResult = UltraEglInstrumentUploadReceipt.COMMITTED
        var onUpload: () -> Unit = {}
        var beforeUpload: () -> Unit = {}
        val uploaded = mutableListOf<IntArray>()
        var canRetire = true
        var retireCalls = 0
        var sealCalls = 0
        var releaseCalls = 0
        var failFirstRelease = false

        override fun generationStatus() = status
        override fun upload(frame: IntArray): UltraEglInstrumentUploadReceipt {
            beforeUpload()
            uploaded += frame.copyOf()
            onUpload()
            return uploadResult
        }
        override fun sealFailure(reason: Throwable) { sealCalls++ }
        override fun retireOutput(): Boolean { retireCalls++; return canRetire }
        override fun releaseIngress() {
            releaseCalls++
            if (failFirstRelease) {
                failFirstRelease = false
                throw IllegalStateException("synthetic exact ingress release failure")
            }
        }
    }
}
