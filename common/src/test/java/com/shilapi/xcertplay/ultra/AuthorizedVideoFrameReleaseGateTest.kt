package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.ExactOwnerDiagnosticRelay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedVideoFrameReleaseGateTest {
    @Test fun busyAtInitialBindKeepsLatestReceiptUntilCurrent() {
        val batch = Any()
        val status = AtomicReference(VideoFrameIdentityAdmission.BUSY_RETRY)
        val busySeen = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val seen = CopyOnWriteArrayList<Int>()
        val gate = AuthorizedVideoFrameReleaseGate<Any, Int>(
            generationStatus = {
                status.get().also {
                    if (it == VideoFrameIdentityAdmission.BUSY_RETRY) busySeen.countDown()
                }
            },
            forward = { seen += it; delivered.countDown() },
        )
        gate.bind(batch) // BUSY is not a permanently failed activation.
        gate.offer(1)
        assertTrue(busySeen.await(2, TimeUnit.SECONDS))
        gate.offer(2)
        gate.offer(3)
        status.set(VideoFrameIdentityAdmission.CURRENT)
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(3), seen)
        gate.close()
        assertTrue(gate.awaitWorkerRetired(2_000))
    }

    @Test fun revokedGenerationRetiresQueueAndNeverAdoptsAnotherBatch() {
        val first = Any()
        val foreign = Any()
        val status = AtomicReference(VideoFrameIdentityAdmission.BUSY_RETRY)
        val busySeen = CountDownLatch(1)
        val seen = CopyOnWriteArrayList<Int>()
        val gate = AuthorizedVideoFrameReleaseGate<Any, Int>(
            generationStatus = {
                status.get().also {
                    if (it == VideoFrameIdentityAdmission.BUSY_RETRY) busySeen.countDown()
                }
            },
            forward = seen::add,
        )
        gate.bind(first)
        gate.offer(1)
        assertTrue(busySeen.await(2, TimeUnit.SECONDS))
        assertThrows(IllegalStateException::class.java) { gate.bind(foreign) }
        gate.offer(2)
        status.set(VideoFrameIdentityAdmission.REVOKED)
        assertTrue(gate.awaitWorkerRetired(2_000))
        gate.bind(foreign)
        gate.offer(3)
        assertEquals(emptyList<Int>(), seen)
    }

    @Test fun closedRendererCannotForwardOrBeRebound() {
        val batch = Any()
        val seen = CopyOnWriteArrayList<Int>()
        val delivered = CountDownLatch(1)
        val gate = AuthorizedVideoFrameReleaseGate<Any, Int>(
            generationStatus = { VideoFrameIdentityAdmission.CURRENT },
            forward = { seen += it; delivered.countDown() },
        )
        gate.bind(batch)
        gate.offer(1)
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        gate.close()
        gate.offer(2)
        gate.bind(batch)
        gate.offer(3)
        assertTrue(gate.awaitWorkerRetired(2_000))
        assertEquals(listOf(1), seen)
    }

    @Test fun blockedAuthorityNeverStallsDecoderAndOldSinkCannotLeakToNewSession() {
        val oldSink = Any()
        val oldBatch = Any()
        val enteredBlockedAuthority = CountDownLatch(1)
        val unblockAuthority = CountDownLatch(1)
        val staleFrames = CopyOnWriteArrayList<Int>()
        val oldGate = AuthorizedVideoFrameReleaseGate<Any, Int>(
            generationStatus = {
                enteredBlockedAuthority.countDown()
                unblockAuthority.await(5, TimeUnit.SECONDS)
                VideoFrameIdentityAdmission.CURRENT
            },
            forward = staleFrames::add,
        )
        oldGate.bind(oldBatch)
        val oldRelay = ExactOwnerDiagnosticRelay<Any, Int>()
        oldRelay.bindOwner(oldSink)
        oldRelay.attach(oldSink, Any(), oldGate::offer)

        val decoderReturned = CountDownLatch(1)
        Thread {
            oldRelay.emit(1)
            decoderReturned.countDown()
        }.start()
        assertTrue("Decoder callback must return before authority unblocks",
            decoderReturned.await(2, TimeUnit.SECONDS))
        assertTrue(enteredBlockedAuthority.await(2, TimeUnit.SECONDS))
        oldRelay.close()
        oldGate.close()

        val newSink = Any()
        val newBatch = Any()
        val newFrames = CopyOnWriteArrayList<Int>()
        val newDelivered = CountDownLatch(1)
        val newGate = AuthorizedVideoFrameReleaseGate<Any, Int>(
            generationStatus = { VideoFrameIdentityAdmission.CURRENT },
            forward = { newFrames += it; newDelivered.countDown() },
        )
        newGate.bind(newBatch)
        val newRelay = ExactOwnerDiagnosticRelay<Any, Int>()
        newRelay.bindOwner(newSink)
        newRelay.attach(newSink, Any(), newGate::offer)
        oldRelay.emit(2)
        newRelay.emit(3)
        assertTrue(newDelivered.await(2, TimeUnit.SECONDS))
        unblockAuthority.countDown()
        assertTrue(oldGate.awaitWorkerRetired(2_000))
        assertEquals(emptyList<Int>(), staleFrames)
        assertEquals(listOf(3), newFrames)
        newRelay.close()
        newGate.close()
        assertTrue(newGate.awaitWorkerRetired(2_000))
    }

    @Test fun overloadedDecoderDropsOldestWithoutGrowingTheAuthorityQueue() {
        val batch = Any()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val delivered = CountDownLatch(3)
        val seen = CopyOnWriteArrayList<Int>()
        val calls = AtomicInteger()
        val gate = AuthorizedVideoFrameReleaseGate<Any, Int>(
            generationStatus = {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    unblock.await(5, TimeUnit.SECONDS)
                }
                VideoFrameIdentityAdmission.CURRENT
            },
            forward = { seen += it; delivered.countDown() },
            queueCapacity = 2,
        )
        gate.bind(batch)
        gate.offer(0)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        repeat(100) { gate.offer(it + 1) }
        unblock.countDown()
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(0, 99, 100), seen)
        gate.close()
        assertTrue(gate.awaitWorkerRetired(2_000))
    }

    @Test fun diagnosticFailureCannotPoisonTheDecoderCallback() {
        val batch = Any()
        val attempted = CountDownLatch(1)
        val gate = AuthorizedVideoFrameReleaseGate<Any, Int>(
            generationStatus = { VideoFrameIdentityAdmission.CURRENT },
            forward = { attempted.countDown(); throw IllegalStateException("EGL retired") },
        )
        gate.bind(batch)
        gate.offer(1)
        assertTrue(attempted.await(2, TimeUnit.SECONDS))
        gate.close()
        assertTrue(gate.awaitWorkerRetired(2_000))
    }
}
