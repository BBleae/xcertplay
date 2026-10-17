package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEvent
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchTimingSnapshotMailboxTest {
    @Test
    fun blockedDiagnosticProcessorCannotDelayRendererOnTheSameHubDispatcher() {
        val mailbox = BenchTimingSnapshotMailbox(capacity = 1)
        val hub = UltraPresentationHub(Executor { it.run() })
        val firstOffer = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val rendererSawSecond = CountDownLatch(1)
        val displayToken = Any()
        val worker = Thread {
            assertTrue(firstOffer.await(2, TimeUnit.SECONDS))
            val first = mailbox.drain()
            assertEquals(1L, first.single().snapshot.revision)
            workerEntered.countDown()
            assertTrue(releaseWorker.await(2, TimeUnit.SECONDS))
        }.apply { isDaemon = true; start() }
        val diagnostic = hub.observe("cluster") { snapshot ->
            if (snapshot.revision > 0L) {
                assertTrue(mailbox.offer("cluster", displayToken, snapshot))
                if (snapshot.revision == 1L) firstOffer.countDown()
            }
        }
        val renderer = hub.observe("cluster") { snapshot ->
            if (snapshot.revision >= 2L) rendererSawSecond.countDown()
        }
        try {
            hub.sink("cluster").onPresentationEvent(
                CarPlayUiSyncPresentationEvent.UnknownCommand(99, emptyMap<String, Any>()),
            )
            assertTrue(workerEntered.await(2, TimeUnit.SECONDS))
            val publisher = Thread {
                hub.sink("cluster").onPresentationEvent(
                    CarPlayUiSyncPresentationEvent.UnknownCommand(99, emptyMap<String, Any>()),
                )
            }.apply { isDaemon = true; start() }
            assertTrue(rendererSawSecond.await(2, TimeUnit.SECONDS))
            publisher.join(2_000)
            assertFalse(publisher.isAlive)
            assertEquals(2L, mailbox.drain().single().snapshot.revision)
            assertEquals(0L, mailbox.droppedCount)
        } finally {
            releaseWorker.countDown()
            worker.join(2_000)
            renderer.close()
            diagnostic.close()
            hub.close()
            mailbox.close()
        }
    }

    @Test
    fun boundedOffersDropOldestAndCloseErasesReferences() {
        val hub = UltraPresentationHub(Executor { it.run() })
        val mailbox = BenchTimingSnapshotMailbox(capacity = 1)
        val token = Any()
        val first = hub.snapshot("cluster")
        assertTrue(mailbox.offer("cluster", token, first))
        assertTrue(mailbox.offer("cluster", token, first))
        assertEquals(1L, mailbox.droppedCount)
        assertEquals(1, mailbox.drain().size)
        assertTrue(mailbox.offer("cluster", token, first))
        mailbox.close()
        assertTrue(mailbox.drain().isEmpty())
        assertFalse(mailbox.offer("cluster", token, first))
        hub.close()
    }
}
