package com.shilapi.xcertplay.ultra.egl

import com.shilapi.xcertplay.ultra.DisplayDestination
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraEglClusterReplayOutboxTest {
    @Test
    fun successfulReceiptsAreSerializedOffThePublisherThread() {
        val outbox = UltraEglClusterReplayOutbox(capacity = 3)
        val delivered = CountDownLatch(2)
        val sequences = mutableListOf<Long>()
        val caller = Thread.currentThread()
        val subscription = outbox.subscribe({ receipt ->
            assertNotEquals(caller, Thread.currentThread())
            sequences += receipt.replaySequence
            delivered.countDown()
        }, { error("Unexpected replay revocation") })
        outbox.publish(receipt(1L))
        outbox.publish(receipt(2L))
        assertTrue(delivered.await(1, TimeUnit.SECONDS))
        assertEquals(listOf(1L, 2L), sequences)
        assertTrue(subscription.retire(1_000L))
        outbox.publish(receipt(3L))
        assertEquals(listOf(1L, 2L), sequences)
        assertTrue(outbox.retire(1_000L))
    }

    @Test
    fun deferredContinuationUsesSameBoundedWorkerAndDeduplicatesPendingSequence() {
        val outbox = UltraEglClusterReplayOutbox(capacity = 1)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val deliveredTwice = CountDownLatch(2)
        val calls = AtomicInteger()
        val caller = Thread.currentThread()
        val subscription = outbox.subscribe({
            assertNotEquals(caller, Thread.currentThread())
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown()
                releaseFirst.await(1, TimeUnit.SECONDS)
            }
            deliveredTwice.countDown()
        }, { error("Unexpected overflow") })
        try {
            val exact = receipt(1L)
            outbox.publish(exact)
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
            assertTrue(subscription.requeue(exact))
            assertTrue(subscription.requeue(exact)) // one pending sequence, not two queued frames
            releaseFirst.countDown()
            assertTrue(deliveredTwice.await(1, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            assertTrue(subscription.retire(1_000L))
        } finally {
            releaseFirst.countDown()
            outbox.retire(1_000L)
        }
    }

    @Test
    fun boundedOverflowRevokesInsteadOfSilentlyDroppingAnOldPermit() {
        val outbox = UltraEglClusterReplayOutbox(capacity = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val delivered = AtomicInteger()
        val subscription = outbox.subscribe({
            delivered.incrementAndGet()
            entered.countDown()
            release.await(1, TimeUnit.SECONDS)
        }, { revoked.countDown() })
        try {
            outbox.publish(receipt(1L))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            outbox.publish(receipt(2L)) // one queued receipt
            outbox.publish(receipt(3L)) // overflow: all pending receipts are revoked
            release.countDown()
            assertTrue(revoked.await(1, TimeUnit.SECONDS))
            assertEquals(1, delivered.get())
            assertTrue(subscription.retire(1_000L))
        } finally {
            release.countDown()
            outbox.retire(1_000L)
        }
    }

    @Test
    fun stuckCallbackPreventsRetirementProofAndNoCallbackStartsAfterSuccessfulRetry() {
        val outbox = UltraEglClusterReplayOutbox(capacity = 2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = AtomicInteger()
        val subscription = outbox.subscribe({
            delivered.incrementAndGet()
            entered.countDown()
            release.await(1, TimeUnit.SECONDS)
        }, {})
        outbox.publish(receipt(1L))
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertFalse(subscription.retire(0L))
        release.countDown()
        assertTrue(subscription.retire(1_000L))
        outbox.publish(receipt(2L))
        assertEquals(1, delivered.get())
        assertTrue(outbox.retire(1_000L))
    }

    @Test
    fun delayedOutOfOrderControlCompletionIsSupersededWithoutKillingNewerFrames() {
        val outbox = UltraEglClusterReplayOutbox(capacity = 2)
        val delivered = CountDownLatch(2)
        val revoked = CountDownLatch(1)
        val superseded = CountDownLatch(1)
        val subscription = outbox.subscribe({ delivered.countDown() },
            { revoked.countDown() }, { superseded.countDown() })
        outbox.publish(receipt(2L))
        outbox.publish(receipt(1L))
        assertTrue(superseded.await(1, TimeUnit.SECONDS))
        outbox.publish(receipt(3L))
        assertTrue(delivered.await(1, TimeUnit.SECONDS))
        assertEquals(1L, revoked.count)
        assertTrue(subscription.retire(1_000L))
        assertTrue(outbox.retire(1_000L))
    }

    @Test
    fun trueGenerationRevocationNotifiesOwnerAndStopsFurtherReceipts() {
        val outbox = UltraEglClusterReplayOutbox(capacity = 2)
        val revoked = CountDownLatch(1)
        val delivered = AtomicInteger()
        val subscription = outbox.subscribe({ delivered.incrementAndGet() },
            { revoked.countDown() })
        outbox.revoke()
        assertTrue(revoked.await(1, TimeUnit.SECONDS))
        outbox.publish(receipt(1L))
        assertEquals(0, delivered.get())
        assertTrue(subscription.retire(1_000L))
        assertTrue(outbox.retire(1_000L))
    }

    private fun receipt(sequence: Long) = UltraEglClusterReplayReceipt(
        destination = DisplayDestination.CLUSTER,
        sceneRevision = 7L,
        graphRevision = 9L,
        nativeOutputBatchGeneration = 3L,
        nativeOutputGeneration = 3L,
        instrumentIngressGeneration = 5L,
        instrumentFrameSequence = 1L,
        producerTimestampNanos = 100L + sequence,
        replaySequence = sequence,
        cause = UltraEglClusterReplayCause.INGRESS_FRAME_REPLAY,
        instrumentOnlyClusterScene = true,
    )
}
