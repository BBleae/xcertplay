package com.shilapi.xcertplay.airplay.video

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPointPlannerPlayerBackendTest {
    @Test
    fun repliesOnlyAfterPlayerSuccessAndPreservesRequestCorrelationAndActionOrder() {
        val identity = MatchPointSessionIdentity.create(41)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val actions = Collections.synchronizedList(mutableListOf<MatchPointPlaybackAction>())
        val port = object : MatchPointPlaybackPort {
            override fun dispatch(action: MatchPointPlaybackAction) {
                if (action is MatchPointPlaybackAction.Insert) {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                }
                actions += action
            }
        }
        val session = RecordingSession(identity, expectedResponses = 2)
        val backend = MatchPointPlannerPlayerBackend(identity, port)
        backend.onOpened(session)
        try {
            backend.onRequest(session, request(identity, 101, MatchPointInsertPlayQueueItem(
                MatchPointQueueItem("first"),
            )))
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            backend.onCommand(session, MatchPointMessage.oneWay(MatchPointSetRate(1.25)))
            backend.onRequest(session, request(identity, 102, MatchPointStop))
            assertTrue(session.responses.isEmpty())
            releaseFirst.countDown()
            assertTrue(session.responseLatch.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("Insert", "SetRate", "Stop"),
                actions.map { it.javaClass.simpleName })
            assertEquals(listOf(101L, 102L), session.responses.map { it.first })
            assertEquals(listOf(MatchPointTypes.INSERT_PLAY_QUEUE_ITEM, MatchPointTypes.STOP),
                session.responses.map { (it.second as MatchPointEmptyResponse).requestType })
            assertEquals(3L, backend.snapshot().completedActions)
            assertEquals(2L, backend.snapshot().successfulResponses)
        } finally {
            releaseFirst.countDown()
            backend.close().await(5_000)
        }
    }

    @Test
    fun rejectsForeignSessionUnsupportedRequestsAndClosedGeneration() {
        val identity = MatchPointSessionIdentity.create(42)
        val backend = MatchPointPlannerPlayerBackend(identity, object : MatchPointPlaybackPort {
            override fun dispatch(action: MatchPointPlaybackAction) {}
        })
        val session = RecordingSession(identity)
        val sameNumberDifferentIdentity = MatchPointSessionIdentity.create(42)
        val foreign = RecordingSession(sameNumberDifferentIdentity)

        backend.onOpened(session)
        assertRejected(MatchPointPlannerPlayerBackendFailure.WRONG_SESSION) {
            backend.onCommand(foreign, MatchPointMessage.oneWay(MatchPointStop))
        }
        assertRejected(MatchPointPlannerPlayerBackendFailure.WRONG_SESSION) {
            backend.onRequest(session, request(sameNumberDifferentIdentity, 1, MatchPointStop))
        }
        assertRejected(MatchPointPlannerPlayerBackendFailure.UNSUPPORTED_COMMAND) {
            backend.onRequest(session, request(identity, 2, MatchPointPlaybackInfoRequest))
        }
        assertRejected(MatchPointPlannerPlayerBackendFailure.UNSUPPORTED_COMMAND) {
            backend.onCommand(session, MatchPointMessage.oneWay(MatchPointSetProperty("muted", true)))
        }
        assertTrue(session.responses.isEmpty())
        assertEquals(0L, backend.snapshot().planner.dispatchedActions)
        backend.close().await(5_000)
        assertRejected(MatchPointPlannerPlayerBackendFailure.TERMINAL) {
            backend.onCommand(session, MatchPointMessage.oneWay(MatchPointStop))
        }
        assertEquals(MatchPointPlannerPlayerBackendState.CLOSED, backend.snapshot().state)
    }

    @Test
    fun failedPlayerActionNeverAcknowledgesRequestAndPortCloseCanBeRetried() {
        val identity = MatchPointSessionIdentity.create(43)
        val closeAttempts = AtomicInteger()
        val firstCloseAttempt = CountDownLatch(1)
        val port = object : MatchPointPlaybackPort {
            override fun dispatch(action: MatchPointPlaybackAction) {
                error("player rejected the action")
            }

            override fun close() {
                if (closeAttempts.incrementAndGet() == 1) {
                    firstCloseAttempt.countDown()
                    error("transient close error")
                }
            }
        }
        val backend = MatchPointPlannerPlayerBackend(identity, port)
        val session = RecordingSession(identity)
        backend.onOpened(session)
        backend.onRequest(session, request(identity, 201, MatchPointStop))

        assertTrue(firstCloseAttempt.await(5, TimeUnit.SECONDS))
        assertTrue(eventually {
            backend.snapshot().planner.state == MatchPointPlaybackPlannerState.FAILED
        })
        assertEquals(1, closeAttempts.get())
        assertTrue(session.responses.isEmpty())
        assertEquals(MatchPointPlannerPlayerBackendState.FAILED, backend.snapshot().state)
        backend.close().await(5_000)
        assertEquals(2, closeAttempts.get())
        assertTrue(backend.snapshot().planner.playerPortCloseComplete)
        assertEquals(MatchPointPlannerPlayerBackendState.CLOSED, backend.snapshot().state)
    }

    @Test
    fun responseCallbackMayReenterBackendWithoutAStateLockCycle() {
        val identity = MatchPointSessionIdentity.create(44)
        val actions = Collections.synchronizedList(mutableListOf<MatchPointPlaybackAction>())
        val port = object : MatchPointPlaybackPort {
            override fun dispatch(action: MatchPointPlaybackAction) {
                actions += action
            }
        }
        val backend = MatchPointPlannerPlayerBackend(identity, port)
        val reentered = CountDownLatch(1)
        val session = RecordingSession(identity, expectedResponses = 1) { self ->
            assertEquals(MatchPointPlannerPlayerBackendState.OPEN, backend.snapshot().state)
            backend.onCommand(self, MatchPointMessage.oneWay(MatchPointStop))
            reentered.countDown()
        }
        backend.onOpened(session)
        try {
            backend.onRequest(session, request(identity, 301, MatchPointInsertPlayQueueItem(
                MatchPointQueueItem("reentrant"),
            )))
            assertTrue(session.responseLatch.await(5, TimeUnit.SECONDS))
            assertTrue(reentered.await(5, TimeUnit.SECONDS))
            assertTrue(eventually { actions.size == 2 && backend.snapshot().completedActions == 2L })
            assertEquals(listOf("Insert", "Stop"), actions.map { it.javaClass.simpleName })
        } finally {
            backend.close().await(5_000)
        }
    }

    @Test
    fun aNewRequestRestartsTheOutcomeDrainAfterItBecameIdle() {
        val identity = MatchPointSessionIdentity.create(47)
        val backend = MatchPointPlannerPlayerBackend(identity, object : MatchPointPlaybackPort {
            override fun dispatch(action: MatchPointPlaybackAction) {}
        })
        val session = RecordingSession(identity, expectedResponses = 2)
        backend.onOpened(session)
        try {
            backend.onRequest(session, request(identity, 601, MatchPointStop))
            assertTrue(eventually {
                session.responses.size == 1 && !backend.snapshot().outcomeWorkerRunning
            })
            backend.onRequest(session, request(identity, 602, MatchPointStop))
            assertTrue(session.responseLatch.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(601L, 602L), session.responses.map { it.first })
            assertEquals(2L, backend.snapshot().planner.dispatchedActions)
        } finally {
            backend.close().await(5_000)
        }
    }

    @Test
    fun closeRetiresQueuedRepliesAndWaitsForInFlightPlayerDispatch() {
        val identity = MatchPointSessionIdentity.create(45)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        val port = object : MatchPointPlaybackPort {
            override fun dispatch(action: MatchPointPlaybackAction) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }

            override fun close() {
                closeCalls.incrementAndGet()
            }
        }
        val backend = MatchPointPlannerPlayerBackend(identity, port)
        val session = RecordingSession(identity)
        backend.onOpened(session)
        try {
            backend.onRequest(session, request(identity, 401, MatchPointStop))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = backend.close()
            assertFalse(closing.isDone)
            assertRejected(MatchPointPlannerPlayerBackendFailure.TERMINAL) {
                backend.onCommand(session, MatchPointMessage.oneWay(MatchPointStop))
            }
            release.countDown()
            closing.await(5_000)
            assertTrue(session.responses.isEmpty())
            assertEquals(1, closeCalls.get())
            assertEquals(MatchPointPlannerPlayerBackendState.CLOSED, backend.snapshot().state)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun stalledPlayerActionTimesOutWithoutSpeculativeSuccessOrPrematurePortClose() {
        val identity = MatchPointSessionIdentity.create(46)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val port = object : MatchPointPlaybackPort {
            override fun dispatch(action: MatchPointPlaybackAction) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }

            override fun close() {
                closes.incrementAndGet()
            }
        }
        val backend = MatchPointPlannerPlayerBackend(
            identity,
            port,
            limits = MatchPointPlannerPlayerBackendLimits(
                actionCompletionTimeoutMillis = 80,
                completionPollMillis = 20,
            ),
        )
        val session = RecordingSession(identity)
        backend.onOpened(session)
        try {
            backend.onRequest(session, request(identity, 501, MatchPointStop))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(eventually {
                backend.snapshot().state == MatchPointPlannerPlayerBackendState.FAILED
            })
            assertTrue(session.responses.isEmpty())
            assertEquals(0, closes.get())
            val closing = backend.close()
            assertFalse(closing.isDone)
            release.countDown()
            closing.await(5_000)
            assertEquals(1, closes.get())
            assertTrue(session.responses.isEmpty())
        } finally {
            release.countDown()
        }
    }

    private class RecordingSession(
        override val identity: MatchPointSessionIdentity,
        expectedResponses: Int = 0,
        private val onSuccess: ((RecordingSession) -> Unit)? = null,
    ) : MatchPointPlayerSession {
        val responses = Collections.synchronizedList(mutableListOf<Pair<Long, MatchPointPayload>>())
        val responseLatch = CountDownLatch(expectedResponses)

        override fun respondSuccess(request: MatchPointInboundRequest, payload: MatchPointPayload) {
            responses += requireNotNull(request.message.messageId) to payload
            onSuccess?.invoke(this)
            responseLatch.countDown()
        }

        override fun respondFailure(request: MatchPointInboundRequest, error: Any) {
            error("The adapter must not fabricate a failure response")
        }

        override fun publishPlaybackState(state: MatchPointPlaybackState) {
            error("The adapter has no truthful playback state")
        }

        override fun publishNotification() {
            error("The adapter has no truthful notification state")
        }

        override fun snapshot(): MatchPointRuntimeSnapshot = error("Not queried by adapter")
    }

    private fun request(
        identity: MatchPointSessionIdentity,
        id: Long,
        payload: MatchPointPayload,
    ): MatchPointInboundRequest = MatchPointInboundRequest(
        identity,
        MatchPointMessage.request(id, payload),
        System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
        Any(),
    )

    private fun assertRejected(
        reason: MatchPointPlannerPlayerBackendFailure,
        block: () -> Unit,
    ) {
        val failure = assertThrows(MatchPointPlannerPlayerBackendException::class.java, block)
        assertEquals(reason, failure.reason)
    }

    private fun eventually(condition: () -> Boolean): Boolean {
        repeat(250) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
