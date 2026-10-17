package com.shilapi.xcertplay

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProcessRetirementRunnerTest {
    private data class Scheduled(val delayMillis: Long, val action: () -> Unit)

    @Test
    fun cancelledConstructionBeforeDestroyRetriesWithoutAnotherActivity() {
        val barrier = HostTeardownCoordinator()
        val work = ArrayDeque<Scheduled>()
        val externalRetirementPending = AtomicBoolean(true)
        var sinkCloseCalls = 0
        val cleanup = HostConstructionCleanup(listOf(
            HostConstructionCleanupStage("external output") {
                if (externalRetirementPending.get()) error("Presentation still retiring")
            },
            HostConstructionCleanupStage("exact sink") { sinkCloseCalls++ },
        ))
        // The cancellation happened before onDestroy; its Activity-local pending field is gone.
        barrier.submit(HostTeardownActions(
            controllerIdentity = null,
            sinkIdentity = cleanup,
            requestControllerClose = null,
            awaitControllerClosed = null,
            closeMediaSink = cleanup::close,
        ))
        val runner = HostProcessRetirementRunner(
            barrier,
            scheduleWork = { delay, action -> work.addLast(Scheduled(delay, action)) },
        )

        runner.request() // onDestroy sees a non-CLEAR process barrier, not a pending field.
        assertEquals(0L, work.removeFirst().also { it.action() }.delayMillis)
        assertEquals("external output", cleanup.pendingStageName())
        assertEquals(0, sinkCloseCalls)
        externalRetirementPending.set(false)
        assertEquals(2_000L, work.removeFirst().also { it.action() }.delayMillis)
        assertEquals(1, sinkCloseCalls)
        assertEquals(HostTeardownBarrierState.CLEAR, barrier.state())
        assertTrue(work.isEmpty())
    }

    @Test
    fun inFlightExactTeardownIsPolledThenStopsAtClear() {
        val barrier = HostTeardownCoordinator()
        val work = ArrayDeque<Scheduled>()
        var closes = 0
        val retiredIdentity = Any()
        barrier.submit(HostTeardownActions(
            controllerIdentity = null,
            sinkIdentity = retiredIdentity,
            requestControllerClose = null,
            awaitControllerClosed = null,
            closeMediaSink = { closes++ },
        ))
        val existingClaim = barrier.claim() as HostTeardownClaimResult.Claimed
        val runner = HostProcessRetirementRunner(
            barrier,
            scheduleWork = { delay, action -> work.addLast(Scheduled(delay, action)) },
        )

        runner.request()
        work.removeFirst().action()
        assertEquals(0, closes)
        assertEquals(HostTeardownOutcome.Clean, barrier.execute(existingClaim.claim))
        assertEquals(500L, work.removeFirst().also { it.action() }.delayMillis)
        assertEquals(1, closes)
        assertEquals(HostTeardownBarrierState.CLEAR, barrier.state())
        assertTrue(work.isEmpty())
    }
}
