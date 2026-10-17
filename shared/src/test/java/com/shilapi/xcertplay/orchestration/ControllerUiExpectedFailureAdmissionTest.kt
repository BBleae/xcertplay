package com.shilapi.xcertplay.orchestration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerUiExpectedFailureAdmissionTest {
    @Test
    fun blockedOldViewAreaFailureCannotOverwriteTheNewActiveSession() {
        val lock = Any()
        val oldSession = Any()
        val newSession = Any()
        var closed = false
        var activeSession: Any? = oldSession
        var token = 1
        val enteredShowViewArea = CountDownLatch(1)
        val finishOldShowViewArea = CountDownLatch(1)
        val oldPublished = AtomicReference<Boolean>()

        val oldTask = Thread {
            enteredShowViewArea.countDown()
            check(finishOldShowViewArea.await(2, TimeUnit.SECONDS))
            // The old session's blocked showViewArea call has now returned false.
            oldPublished.set(
                recordExpectedSessionFailure(
                    lock = lock,
                    expectedSession = oldSession,
                    expectedViewAreaToken = 1,
                    readState = {
                        ControllerUiExpectedSessionState(closed, activeSession, token)
                    },
                    recordFailure = { error("stale failure must not publish") },
                ),
            )
        }
        oldTask.start()
        assertTrue(enteredShowViewArea.await(2, TimeUnit.SECONDS))
        synchronized(lock) {
            activeSession = newSession
            token = 2
        }
        finishOldShowViewArea.countDown()
        oldTask.join(2_000)

        assertFalse(oldTask.isAlive)
        assertFalse(checkNotNull(oldPublished.get()))

        var currentFailurePublished = false
        assertTrue(
            recordExpectedSessionFailure(
                lock = lock,
                expectedSession = newSession,
                expectedViewAreaToken = 2,
                readState = {
                    ControllerUiExpectedSessionState(closed, activeSession, token)
                },
                recordFailure = { currentFailurePublished = true },
            ),
        )
        assertTrue(currentFailurePublished)
    }
}
