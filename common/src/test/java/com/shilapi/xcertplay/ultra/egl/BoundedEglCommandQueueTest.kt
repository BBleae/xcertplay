package com.shilapi.xcertplay.ultra.egl

import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedEglCommandQueueTest {
    @Test
    fun frameSignalsCoalesceByExactIngressWithinBound() {
        val queue = BoundedEglCommandQueue(capacity = 2)
        val key = UltraEglIngressKey.Remote(UltraIngressId("screen-1"))
        val token = UltraEglFrameToken(key, 1L, fakeIngress())

        assertTrue(queue.offerFrame(token))
        assertTrue(queue.offerFrame(token))
        assertEquals(1, queue.snapshot().size)
        assertEquals(1L, queue.snapshot().coalescedFrameCount)
        assertTrue(queue.take() is UltraEglQueuedCommand.Frame)

        queue.close()
    }

    @Test
    fun sceneNeverCoalescesAcrossControlBarrier() {
        val queue = BoundedEglCommandQueue(capacity = 3)
        var firstCoalesced = 0
        var secondCoalesced = 0
        val first = UltraEglQueuedCommand.Scene(1L, {}, { firstCoalesced++ })
        val second = UltraEglQueuedCommand.Scene(2L, {}, { secondCoalesced++ })

        assertTrue(queue.offerScene(first))
        assertTrue(queue.offerControl(UltraEglQueuedCommand.Control({}, {})))
        assertTrue(queue.offerScene(second))
        assertTrue(
            queue.offerFrame(
                UltraEglFrameToken(
                    UltraEglIngressKey.Remote(UltraIngressId("overflow")),
                    1L,
                    fakeIngress(),
                ),
            ),
        )
        assertEquals(0, firstCoalesced)
        assertEquals(0, secondCoalesced)
        assertEquals(first, queue.take())
        assertTrue(queue.take() is UltraEglQueuedCommand.Control)
        assertEquals(second, queue.take())
        assertTrue(queue.take() is UltraEglQueuedCommand.Frame)

        queue.close()
    }

    @Test
    fun oneFrameSignalledWhileFullIsRetriedAfterCapacityFrees() {
        val queue = BoundedEglCommandQueue(capacity = 1)
        val blocker = UltraEglQueuedCommand.Control({}, {})
        val token = UltraEglFrameToken(
            UltraEglIngressKey.Remote(UltraIngressId("one-shot-cluster")),
            7L, fakeIngress(),
        )
        assertTrue(queue.offerControl(blocker))
        assertTrue(queue.offerFrame(token))
        assertEquals(1, queue.snapshot().size)
        assertEquals(1, queue.snapshot().deferredFrameCount)
        assertEquals(blocker, queue.take())
        val retried = queue.take() as UltraEglQueuedCommand.Frame
        assertEquals(token, retried.token)
        assertEquals(0, queue.snapshot().deferredFrameCount)
        queue.close()
    }

    @Test
    fun deferredIngressKeepsNewestGenerationAndRemainsBounded() {
        val queue = BoundedEglCommandQueue(capacity = 1)
        val blocker = UltraEglQueuedCommand.Control({}, {})
        val key = UltraEglIngressKey.Remote(UltraIngressId("reopened"))
        val old = UltraEglFrameToken(key, 1L, fakeIngress())
        val current = UltraEglFrameToken(key, 2L, fakeIngress())
        assertTrue(queue.offerControl(blocker))
        assertTrue(queue.offerFrame(old))
        assertTrue(queue.offerFrame(current))
        assertTrue(queue.offerFrame(old)) // late stale callback must not displace generation 2
        assertEquals(1, queue.snapshot().deferredFrameCount)
        assertEquals(2L, queue.snapshot().coalescedFrameCount)
        assertEquals(blocker, queue.take())
        assertEquals(current, (queue.take() as UltraEglQueuedCommand.Frame).token)
        queue.close()
    }

    @Test
    fun closeAndRetirementUncertaintyDiscardDeferredCallbacks() {
        val key = UltraEglIngressKey.Remote(UltraIngressId("closed"))
        val first = BoundedEglCommandQueue(capacity = 1)
        assertTrue(first.offerControl(UltraEglQueuedCommand.Control({}, {})))
        assertTrue(first.offerFrame(UltraEglFrameToken(key, 1L, fakeIngress())))
        first.abandonQueuedAfterUncertainty()
        assertEquals(0, first.snapshot().deferredFrameCount)
        assertEquals(0, first.snapshot().size)
        first.close()

        val second = BoundedEglCommandQueue(capacity = 1)
        assertTrue(second.offerControl(UltraEglQueuedCommand.Control({}, {})))
        assertTrue(second.offerFrame(UltraEglFrameToken(key, 1L, fakeIngress())))
        second.close()
        assertEquals(0, second.snapshot().deferredFrameCount)
        assertEquals(0, second.snapshot().size)
        assertFalse(second.offerFrame(UltraEglFrameToken(key, 2L, fakeIngress())))
    }

    @Test
    fun dirtyBacklogIsBoundedByMaximumLiveIngresses() {
        val queue = BoundedEglCommandQueue(capacity = 1)
        assertTrue(queue.offerControl(UltraEglQueuedCommand.Control({}, {})))
        repeat(BoundedEglCommandQueue.MAXIMUM_DEFERRED_INGRESSES) { index ->
            assertTrue(queue.offerFrame(UltraEglFrameToken(
                UltraEglIngressKey.Remote(UltraIngressId("deferred-$index")),
                1L, fakeIngress(),
            )))
        }
        assertEquals(BoundedEglCommandQueue.MAXIMUM_DEFERRED_INGRESSES,
            queue.snapshot().deferredFrameCount)
        assertFalse(queue.offerFrame(UltraEglFrameToken(
            UltraEglIngressKey.Remote(UltraIngressId("over-backlog-bound")),
            1L, fakeIngress(),
        )))
        assertEquals(1, queue.snapshot().size)
        queue.close()
    }

    @Test
    fun latestSceneReplacesOnlyTailSceneAndCompletesOldTicket() {
        val queue = BoundedEglCommandQueue(capacity = 1)
        var firstCoalesced = 0
        val first = UltraEglQueuedCommand.Scene(1L, {}, { firstCoalesced++ })
        val second = UltraEglQueuedCommand.Scene(2L, {}, {})

        assertTrue(queue.offerScene(first))
        assertTrue(queue.offerScene(second))

        assertEquals(1, firstCoalesced)
        assertEquals(1L, queue.snapshot().coalescedSceneCount)
        assertEquals(second, queue.take())
        queue.close()
    }

    private fun fakeIngress() = object : UltraEglDriverIngress {
        override val producerEndpoint = Any()
        override val hasUsableFrame = false
    }
}
