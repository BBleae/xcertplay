package com.shilapi.xcertplay

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPhysicalRetirementPollerTest {
    @Test fun pendingToClearWakesAdoptionOrStartWithoutAnyExternalEvent() {
        var generation = 7
        var pending = true
        var cleanupRequests = 0
        var wakeups = 0
        val scheduled = ArrayDeque<Runnable>()
        val poller = HostPhysicalRetirementPoller(
            currentGeneration = { generation },
            hasPendingRetirement = { pending },
            requestRetirement = { cleanupRequests++ },
            postDelayed = { callback, delay ->
                assertEquals(250L, delay)
                scheduled.addLast(callback)
            },
            removeCallbacks = { callback -> scheduled.removeAll { it === callback } },
            onClear = { wakeups++ },
        )

        // The first onCreate adoption attempt arms the poll; no other lifecycle callback occurs.
        poller.arm(generation)
        assertEquals(1, scheduled.size)
        scheduled.removeFirst().run()
        assertEquals(1, cleanupRequests)
        assertEquals(0, wakeups)
        assertEquals(1, scheduled.size)

        // The process worker completes independently, and the bounded Activity poll resumes
        // maybeStartCarPlay, which can now adopt or create the session.
        pending = false
        scheduled.removeFirst().run()
        assertEquals(1, wakeups)
        assertTrue(scheduled.isEmpty())

        generation++
        poller.arm(generation)
        poller.close()
        assertTrue(scheduled.isEmpty())
    }

    @Test fun restartAndDestroyCancelTheOldGeneration() {
        var generation = 11
        var wakeups = 0
        val scheduled = ArrayDeque<Runnable>()
        val poller = HostPhysicalRetirementPoller(
            currentGeneration = { generation },
            hasPendingRetirement = { false },
            requestRetirement = {},
            postDelayed = { callback, _ -> scheduled.addLast(callback) },
            removeCallbacks = { callback -> scheduled.removeAll { it === callback } },
            onClear = { wakeups++ },
        )
        poller.arm(generation)
        val staleCallback = scheduled.first()
        generation++
        poller.invalidate()
        assertTrue(scheduled.isEmpty())
        staleCallback.run()
        assertEquals(0, wakeups)
        poller.arm(generation)
        scheduled.removeFirst().run()
        assertEquals(1, wakeups)
        poller.arm(generation)
        poller.close()
        assertTrue(scheduled.isEmpty())
    }
}
