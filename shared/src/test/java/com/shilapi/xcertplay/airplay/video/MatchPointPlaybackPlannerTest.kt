package com.shilapi.xcertplay.airplay.video

import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPointPlaybackPlannerTest {
    @Test
    fun serializesTypedCommandsAndMaintainsExplicitQueueOrder() {
        val identity = MatchPointSessionIdentity.create(11)
        val port = RecordingPort()
        val planner = MatchPointPlaybackPlanner(identity, port)

        val inserts = listOf(
            planner.apply(identity, insert("a")),
            planner.apply(identity, insert("b")),
            planner.apply(identity, insert("c", after = "a")),
        )
        inserts.forEach { it.await() }
        assertEquals(listOf("a", "c", "b"), planner.queueOrder().map { it.uuid })

        planner.apply(identity, MatchPointMessage.oneWay(MatchPointSeek(
            MatchPointItemReference("c"),
            MatchPointSeekPosition.Time(MatchPointCmTime(9, 3)),
            forInterstitial = true,
        ))).await()
        planner.apply(identity, MatchPointMessage.oneWay(MatchPointSetRate(
            1.25,
            networkClockTime = MatchPointCmTime(3, 1),
            wallClockTimeDiff = 0.5,
            forInterstitial = true,
        ))).await()
        planner.apply(identity, MatchPointMessage.oneWay(MatchPointStop)).await()
        assertEquals(listOf("a", "c", "b"), planner.queueOrder().map { it.uuid })
        planner.apply(identity, MatchPointMessage.oneWay(
            MatchPointRemovePlayQueueItem(MatchPointItemReference("c")),
        )).await()

        assertEquals(listOf("a", "b"), planner.queueOrder().map { it.uuid })
        assertEquals(
            listOf("Insert", "Insert", "Insert", "Seek", "SetRate", "Stop", "Remove"),
            port.actions.map { it.javaClass.simpleName },
        )
        assertEquals(7L, planner.snapshot().dispatchedActions)
        planner.close().await()
        assertEquals(1, port.closes)
    }

    @Test
    fun rejectsDuplicateUnknownAndBoundedItemsWithoutPlayerSideEffects() {
        val identity = MatchPointSessionIdentity.create(12)
        val port = RecordingPort()
        val planner = MatchPointPlaybackPlanner(
            identity,
            port,
            MatchPointPlaybackPlannerLimits(maximumQueuedItems = 2, maximumObservedItemIds = 2),
        )

        assertRejected(MatchPointPlaybackPlanFailure.UNKNOWN_ITEM) {
            planner.apply(identity, insert("b", after = "missing"))
        }
        assertRejected(MatchPointPlaybackPlanFailure.UNKNOWN_ITEM) {
            planner.apply(identity, MatchPointMessage.oneWay(
                MatchPointSeek(MatchPointItemReference("missing"),
                    MatchPointSeekPosition.Date(MatchPointDate(42.0))),
            ))
        }
        planner.apply(identity, insert("a")).await()
        assertRejected(MatchPointPlaybackPlanFailure.DUPLICATE_ITEM) {
            planner.apply(identity, insert("a"))
        }
        planner.apply(identity, insert("b")).await()
        assertRejected(MatchPointPlaybackPlanFailure.QUEUE_LIMIT) {
            planner.apply(identity, insert("c"))
        }
        planner.apply(identity, MatchPointMessage.oneWay(
            MatchPointRemovePlayQueueItem(MatchPointItemReference("a")),
        )).await()
        assertRejected(MatchPointPlaybackPlanFailure.DUPLICATE_ITEM) {
            planner.apply(identity, insert("a"))
        }
        assertRejected(MatchPointPlaybackPlanFailure.OBSERVED_ID_LIMIT) {
            planner.apply(identity, insert("c"))
        }
        assertRejected(MatchPointPlaybackPlanFailure.UNKNOWN_ITEM) {
            planner.apply(identity, MatchPointMessage.oneWay(
                MatchPointRemovePlayQueueItem(MatchPointItemReference("a")),
            ))
        }
        assertEquals(3, port.actions.size)
        assertEquals(2, planner.snapshot().observedItemIds)
        planner.close().await()
    }

    @Test
    fun rejectsOtherTypedCommandsAndDifferentGenerationEvenWithSameNumber() {
        val identity = MatchPointSessionIdentity.create(13)
        val wrong = MatchPointSessionIdentity.create(13)
        val port = RecordingPort()
        val planner = MatchPointPlaybackPlanner(identity, port)

        assertRejected(MatchPointPlaybackPlanFailure.WRONG_SESSION) {
            planner.apply(wrong, MatchPointMessage.oneWay(MatchPointStop))
        }
        assertRejected(MatchPointPlaybackPlanFailure.UNSUPPORTED_COMMAND) {
            planner.apply(identity, MatchPointMessage.request(1, MatchPointPlaybackInfoRequest))
        }
        assertRejected(MatchPointPlaybackPlanFailure.UNSUPPORTED_COMMAND) {
            planner.apply(identity, MatchPointMessage.oneWay(MatchPointSetProperty("muted", true)))
        }
        planner.apply(identity, MatchPointMessage.request(44, MatchPointSetRate(1.0))).await()
        assertRejected(MatchPointPlaybackPlanFailure.DUPLICATE_REQUEST) {
            planner.apply(identity, MatchPointMessage.request(44, MatchPointSetRate(0.0)))
        }
        assertEquals(1, planner.snapshot().observedRequestIds)
        assertEquals(1, port.actions.size)
        assertEquals(MatchPointPlaybackPlannerState.OPEN, planner.snapshot().state)
        planner.close().await()
        assertRejected(MatchPointPlaybackPlanFailure.TERMINAL) {
            planner.apply(identity, MatchPointMessage.oneWay(MatchPointStop))
        }
        planner.close().await()
        assertEquals(1, port.closes)
    }

    @Test
    fun requestIdsAndPortCloseAreBoundedAndCloseCanBeRetried() {
        val identity = MatchPointSessionIdentity.create(17)
        var closeAttempts = 0
        val port = object : MatchPointPlaybackPort {
            override fun dispatch(action: MatchPointPlaybackAction) {}
            override fun close() {
                closeAttempts++
                if (closeAttempts == 1) error("transient player close failure")
            }
        }
        val planner = MatchPointPlaybackPlanner(
            identity,
            port,
            MatchPointPlaybackPlannerLimits(maximumObservedRequestIds = 1),
        )
        planner.apply(identity, MatchPointMessage.request(10, MatchPointStop)).await()
        assertRejected(MatchPointPlaybackPlanFailure.REQUEST_ID_LIMIT) {
            planner.apply(identity, MatchPointMessage.request(11, MatchPointStop))
        }
        assertFutureRejected(MatchPointPlaybackPlanFailure.PLAYER_PORT_FAILED, planner.close())
        assertEquals(MatchPointPlaybackPlannerState.FAILED, planner.snapshot().state)
        assertFalse(planner.snapshot().playerPortCloseComplete)
        assertRejected(MatchPointPlaybackPlanFailure.TERMINAL) {
            planner.apply(identity, MatchPointMessage.oneWay(MatchPointStop))
        }
        planner.close().await()
        assertTrue(planner.snapshot().playerPortCloseComplete)
        assertEquals(2, closeAttempts)
    }

    @Test
    fun playerFailureIsTerminalAndCannotCommitAnUncertainAction() {
        val identity = MatchPointSessionIdentity.create(14)
        val port = RecordingPort(failAction = true)
        val planner = MatchPointPlaybackPlanner(identity, port)

        assertFutureRejected(MatchPointPlaybackPlanFailure.PLAYER_PORT_FAILED,
            planner.apply(identity, insert("a")))
        assertEquals(MatchPointPlaybackPlannerState.FAILED, planner.snapshot().state)
        assertEquals(0, planner.snapshot().queuedItems)
        assertRejected(MatchPointPlaybackPlanFailure.TERMINAL) {
            planner.apply(identity, insert("b"))
        }
        planner.close().await()
        assertEquals(1, port.closes)
    }

    @Test
    fun concurrentCommandsSerializeAndCloseWaitsForInFlightDispatch() {
        val identity = MatchPointSessionIdentity.create(15)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val port = RecordingPort(onDispatch = {
            if (it is MatchPointPlaybackAction.Insert && it.item.uuid == "hold") {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
        })
        val planner = MatchPointPlaybackPlanner(
            identity,
            port,
            MatchPointPlaybackPlannerLimits(maximumPendingActions = 1),
        )
        try {
            val first = planner.apply(identity, insert("hold"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(first.isDone)
            assertFalse(first is Future<*>)
            assertFalse(first is CompletionStage<*>)
            val queued = planner.apply(identity, insert("queued"))
            assertEquals(1, planner.snapshot().pendingActions)
            assertEquals(2, planner.snapshot().plannedItems)
            assertRejected(MatchPointPlaybackPlanFailure.PENDING_ACTION_LIMIT) {
                planner.apply(identity, insert("overflow"))
            }
            val closing = planner.close()
            assertEquals(MatchPointPlaybackPlannerState.CLOSING, planner.snapshot().state)
            assertFalse(closing.isDone)
            assertFalse(closing is Future<*>)
            assertFutureRejected(MatchPointPlaybackPlanFailure.TERMINAL, queued)
            release.countDown()
            first.await()
            closing.await()
            assertRejected(MatchPointPlaybackPlanFailure.TERMINAL) {
                planner.apply(identity, insert("late"))
            }
            assertEquals(listOf("hold"), port.actions.filterIsInstance<MatchPointPlaybackAction.Insert>()
                .map { it.item.uuid })
            assertEquals(1, port.closes)
        } finally {
            release.countDown()
        }

        val manyPort = RecordingPort()
        val many = MatchPointPlaybackPlanner(MatchPointSessionIdentity.create(16), manyPort)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 32).map { id ->
                workers.submit(Callable { many.apply(many.identity, insert("item-$id")) })
            }
            futures.forEach { it.get(5, TimeUnit.SECONDS).await() }
            assertEquals(32, many.snapshot().queuedItems)
            assertEquals(1, manyPort.maximumConcurrentDispatches.get())
            assertEquals(
                manyPort.actions.filterIsInstance<MatchPointPlaybackAction.Insert>().map { it.item.uuid },
                many.queueOrder().map { it.uuid },
            )
        } finally {
            many.close().await()
            workers.shutdownNow()
        }
    }

    @Test
    fun crossThreadPlayerCallbacksCanQueryAndSubmitWithoutPlannerLockCycle() {
        val identity = MatchPointSessionIdentity.create(18)
        lateinit var planner: MatchPointPlaybackPlanner
        val followup = AtomicReference<MatchPointPlaybackResult<MatchPointPlaybackAction>>()
        val callbackError = AtomicReference<Throwable>()
        val port = object : MatchPointPlaybackPort {
            val actions = mutableListOf<MatchPointPlaybackAction>()

            override fun dispatch(action: MatchPointPlaybackAction) {
                if (action is MatchPointPlaybackAction.Insert) {
                    val done = CountDownLatch(1)
                    Thread {
                        try {
                            assertEquals(MatchPointPlaybackPlannerState.OPEN, planner.snapshot().state)
                            assertEquals(emptyList<String>(), planner.queueOrder().map { it.uuid })
                            followup.set(planner.apply(identity, MatchPointMessage.oneWay(MatchPointStop)))
                        } catch (failure: Throwable) {
                            callbackError.set(failure)
                        } finally {
                            done.countDown()
                        }
                    }.start()
                    assertTrue(done.await(5, TimeUnit.SECONDS))
                    callbackError.get()?.let { throw it }
                }
                actions += action
            }

            override fun close() {
                val done = CountDownLatch(1)
                Thread {
                    try {
                        assertEquals(MatchPointPlaybackPlannerState.CLOSING, planner.snapshot().state)
                        planner.queueOrder()
                        assertFalse(planner.close().isDone)
                    } catch (failure: Throwable) {
                        callbackError.set(failure)
                    } finally {
                        done.countDown()
                    }
                }.start()
                assertTrue(done.await(5, TimeUnit.SECONDS))
                callbackError.get()?.let { throw it }
            }
        }
        planner = MatchPointPlaybackPlanner(identity, port)
        planner.apply(identity, insert("a")).await()
        followup.get().await()
        assertEquals(listOf("Insert", "Stop"), port.actions.map { it.javaClass.simpleName })
        planner.close().await()
        assertEquals(MatchPointPlaybackPlannerState.CLOSED, planner.snapshot().state)
    }

    @Test
    fun resultIsReadOnlyAndAnAccidentalCallbackWaitHasAFiniteLimit() {
        val identity = MatchPointSessionIdentity.create(19)
        lateinit var planner: MatchPointPlaybackPlanner
        val callbackError = AtomicReference<Throwable>()
        val followup = AtomicReference<MatchPointPlaybackResult<MatchPointPlaybackAction>>()
        val port = object : MatchPointPlaybackPort {
            override fun dispatch(action: MatchPointPlaybackAction) {
                if (action is MatchPointPlaybackAction.Insert) {
                    val done = CountDownLatch(1)
                    Thread {
                        try {
                            val queued = planner.apply(identity, MatchPointMessage.oneWay(MatchPointStop))
                            followup.set(queued)
                            assertThrows(TimeoutException::class.java) { queued.await(25) }
                        } catch (failure: Throwable) {
                            callbackError.set(failure)
                        } finally {
                            done.countDown()
                        }
                    }.start()
                    assertTrue(done.await(5, TimeUnit.SECONDS))
                    callbackError.get()?.let { throw it }
                }
            }
        }
        planner = MatchPointPlaybackPlanner(identity, port)
        val result = planner.apply(identity, insert("a"))
        assertFalse(result is Future<*>)
        assertFalse(result is CompletionStage<*>)
        assertFalse(result.javaClass.methods.any {
            it.name in setOf("complete", "cancel", "join", "thenApply", "whenComplete")
        })
        result.await()
        followup.get().await()
        planner.close().await()
    }

    @Test
    fun playerCallbackWaitingForItsOwnCloseGetsBoundedTimeoutThenCloses() {
        val identity = MatchPointSessionIdentity.create(20)
        lateinit var planner: MatchPointPlaybackPlanner
        val closing = AtomicReference<MatchPointPlaybackResult<Unit>>()
        val callbackError = AtomicReference<Throwable>()
        val port = object : MatchPointPlaybackPort {
            override fun dispatch(action: MatchPointPlaybackAction) {
                val done = CountDownLatch(1)
                Thread {
                    try {
                        val result = planner.close()
                        closing.set(result)
                        assertThrows(TimeoutException::class.java) { result.await(25) }
                    } catch (failure: Throwable) {
                        callbackError.set(failure)
                    } finally {
                        done.countDown()
                    }
                }.start()
                assertTrue(done.await(5, TimeUnit.SECONDS))
                callbackError.get()?.let { throw it }
            }
        }
        planner = MatchPointPlaybackPlanner(identity, port)
        planner.apply(identity, insert("a")).await()
        closing.get().await()
        assertEquals(MatchPointPlaybackPlannerState.CLOSED, planner.snapshot().state)
    }

    private fun insert(id: String, after: String? = null) = MatchPointMessage.oneWay(
        MatchPointInsertPlayQueueItem(
            item = MatchPointQueueItem(id, contentLocation = "https://example.test/$id.m3u8"),
            itemAfter = after?.let(::MatchPointItemReference),
        ),
    )

    private fun assertRejected(reason: MatchPointPlaybackPlanFailure, action: () -> Unit) {
        assertEquals(reason, assertThrows(MatchPointPlaybackPlanException::class.java, action).reason)
    }

    private fun assertFutureRejected(
        reason: MatchPointPlaybackPlanFailure,
        result: MatchPointPlaybackResult<*>,
    ) {
        val failure = assertThrows(MatchPointPlaybackPlanException::class.java) {
            result.await(5_000)
        }
        assertEquals(reason, failure.reason)
    }

    private fun <T> MatchPointPlaybackResult<T>.await(): T = await(5_000)

    private class RecordingPort(
        private val failAction: Boolean = false,
        private val onDispatch: (MatchPointPlaybackAction) -> Unit = {},
    ) : MatchPointPlaybackPort {
        val actions: MutableList<MatchPointPlaybackAction> = Collections.synchronizedList(mutableListOf())
        val maximumConcurrentDispatches = AtomicInteger(0)
        private val concurrentDispatches = AtomicInteger(0)
        var closes = 0
            private set

        override fun dispatch(action: MatchPointPlaybackAction) {
            val concurrent = concurrentDispatches.incrementAndGet()
            maximumConcurrentDispatches.accumulateAndGet(concurrent, ::maxOf)
            try {
                onDispatch(action)
                if (failAction) error("local player failed")
                actions += action
            } finally {
                concurrentDispatches.decrementAndGet()
            }
        }

        override fun close() {
            closes++
        }
    }
}
