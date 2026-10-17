package com.shilapi.xcertplay.airplay.mainbuffered

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBufferedPcm16OutputAdmissionTest {
    @Test
    fun offerAndCloseAreLinearizedSoNoFrameCanBeAcceptedAfterCloseClearsTheQueue() {
        val queuedWhileLocked = CountDownLatch(1)
        val releaseOffer = CountDownLatch(1)
        val admission = MainBufferedPcm16OutputAdmission(1, afterQueuedUnderLock = {
            queuedWhileLocked.countDown()
            assertTrue(releaseOffer.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        })
        val offered = AtomicReference<Boolean>()
        val producer = Thread {
            offered.set(admission.offer(byteArrayOf(1, 2, 3, 4)) { true })
        }
        producer.start()
        assertTrue(queuedWhileLocked.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val closeCompleted = CountDownLatch(1)
        val closer = Thread {
            admission.closeAdmission()
            closeCompleted.countDown()
        }
        closer.start()
        assertFalse(closeCompleted.await(50, TimeUnit.MILLISECONDS))

        releaseOffer.countDown()
        producer.join(TIMEOUT_MILLIS)
        closer.join(TIMEOUT_MILLIS)
        assertFalse(producer.isAlive)
        assertFalse(closer.isAlive)
        assertEquals(true, offered.get())
        assertTrue(admission.isClosing)
        assertEquals(0, admission.queuedPackets)
        assertFalse(admission.offer(byteArrayOf(5, 6, 7, 8)) { true })
    }

    @Test
    fun flushRetiresBothQueuedAndAlreadyPolledFramesBeforeNativeWrite() {
        val admission = MainBufferedPcm16OutputAdmission(2)
        assertTrue(admission.offer(byteArrayOf(1, 2, 3, 4)) { true })
        val polled = admission.poll(0L)!!
        assertTrue(admission.offer(byteArrayOf(5, 6, 7, 8)) { true })
        assertEquals(1, admission.queuedPackets)

        var nativeFlushes = 0
        admission.flush(ready = { true }) { nativeFlushes++ }

        assertEquals(1, nativeFlushes)
        assertEquals(0, admission.queuedPackets)
        assertNull(admission.writeIfCurrent(polled) { error("stale frame must never write") })
        admission.closeAdmission()
        assertFalse(admission.offer(byteArrayOf(9, 10, 11, 12)) { true })
    }

    @Test
    fun revocationAfterPlayReservationButBeforeSecondCheckNeverCallsNativePlay() {
        val reserved = CountDownLatch(1)
        val releaseReservation = CountDownLatch(1)
        val admission = MainBufferedPcm16OutputAdmission(2,
            afterPlayReservedOutsideLock = {
                reserved.countDown()
                assertTrue(releaseReservation.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            })
        val proof = verifiedAnchor(admission)
        assertTrue(admission.offer(byteArrayOf(1, 2, 3, 4), 1_000L) { true })
        assertTrue(admission.arm(proof) { true })
        val nativePlayCalled = AtomicBoolean()
        val result = AtomicReference<Boolean?>()
        val worker = Thread {
            result.set(admission.playIfCurrent(proof,
                play = { nativePlayCalled.set(true) },
                retireLatePlay = { error("no native call requires no compensation") },
            ))
        }
        worker.start()
        try {
            assertTrue(reserved.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            admission.closeAdmission()
        } finally {
            releaseReservation.countDown()
        }
        worker.join(TIMEOUT_MILLIS)
        assertFalse(worker.isAlive)
        assertEquals(false, result.get())
        assertFalse(nativePlayCalled.get())
        assertTrue(admission.isClosing)
        assertTrue(admission.withNativeGateIfIdle { })
    }

    @Test
    fun revocationAfterWriteReservationButBeforeSecondCheckNeverCallsNativeWrite() {
        val reserved = CountDownLatch(1)
        val releaseReservation = CountDownLatch(1)
        val admission = MainBufferedPcm16OutputAdmission(2,
            afterWriteReservedOutsideLock = {
                reserved.countDown()
                assertTrue(releaseReservation.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            })
        val proof = verifiedAnchor(admission)
        assertTrue(admission.offer(byteArrayOf(1, 2, 3, 4), 1_000L) { true })
        assertTrue(admission.arm(proof) { true })
        val (frame, currentProof) = checkNotNull(admission.awaitPlayable(0L))
        val nativeWriteCalled = AtomicBoolean()
        val result = AtomicReference<Int?>(Int.MIN_VALUE)
        val worker = Thread {
            result.set(admission.writeIfPlayable(frame, currentProof,
                write = { nativeWriteCalled.set(true); 4 },
                retireLateWrite = { error("no native call requires no compensation") },
            ))
        }
        worker.start()
        try {
            assertTrue(reserved.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            admission.closeAdmission()
        } finally {
            releaseReservation.countDown()
        }
        worker.join(TIMEOUT_MILLIS)
        assertFalse(worker.isAlive)
        assertNull(result.get())
        assertFalse(nativeWriteCalled.get())
        assertTrue(admission.isClosing)
        assertTrue(admission.withNativeGateIfIdle { })
    }

    private fun verifiedAnchor(
        admission: MainBufferedPcm16OutputAdmission,
    ): MainBufferedPcm16VerifiedAnchor {
        val networkTime = MainBufferedNetworkTime(1, 2L, 3L, 0L)
        val anchor = MainBufferedPlaybackAnchor(
            point = MainBufferedTimelinePoint(7L, 1_000L, 48_000L),
            networkTime = networkTime,
            firstAudiblePoint = MainBufferedTimelinePoint(7L, 1_000L, 48_000L),
        )
        return checkNotNull(MainBufferedPcm16VerifiedAnchor.verify(
            anchor, admission.outputInstance, admission.playbackEpoch,
            MainBufferedPcm16AudioClockMapping { instance, _ ->
                MainBufferedPcm16CalibratedPlayCall(instance, networkTime, 100L,
                    5_000_100L, 5_000_000L)
            }, 100L,
        ))
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
