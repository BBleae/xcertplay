package com.shilapi.xcertplay.media

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RetryableResourceReleaseTest {
    @Test
    fun releaseFailureIsNotEvidenceAndASecondAttemptCanProveRelease() {
        val releases = AtomicInteger()
        val resource = Any()
        val release = RetryableResourceRelease(
            resource = resource,
            stopAction = {},
            releaseAction = {
                if (releases.incrementAndGet() == 1) throw IllegalStateException("release failed")
            },
        )

        val acknowledgements = AtomicInteger()
        assertEquals(
            ResourceReleaseResult.RELEASE_FAILED,
            releaseWithEvidence(release, acknowledgements::incrementAndGet),
        )
        assertFalse(release.isReleased)
        assertNotNull(release.lastFailure)
        assertEquals(0, acknowledgements.get())

        assertEquals(
            ResourceReleaseResult.RELEASED,
            releaseWithEvidence(release, acknowledgements::incrementAndGet),
        )
        assertTrue(release.isReleased)
        assertEquals(2, releases.get())
        assertEquals(1, acknowledgements.get())
    }

    @Test
    fun terminalStateCannotCommitUntilRetryProvesDecoderRelease() {
        val attempts = AtomicInteger()
        val commits = AtomicInteger()

        assertThrows(IllegalStateException::class.java) {
            commitAfterProvenRelease(
                release = { attempts.incrementAndGet() > 1 },
                commit = commits::incrementAndGet,
            )
        }
        assertEquals(0, commits.get())

        assertEquals(
            1,
            commitAfterProvenRelease(
                release = { attempts.incrementAndGet() > 1 },
                commit = commits::incrementAndGet,
            ),
        )
        assertEquals(1, commits.get())
    }

    @Test
    fun blockingNativeRetryUsesOneTrackedDaemonAndHonorsEveryWaitBudget() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val releases = AtomicInteger()
        val release = RetryableResourceRelease(
            resource = Any(),
            stopAction = {},
            releaseAction = {
                releases.incrementAndGet()
                entered.countDown()
                unblock.await()
            },
        )

        val started = System.nanoTime()
        assertEquals(null, release.awaitTrackedRelease(20))
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals(null, release.awaitTrackedRelease(20))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500)
        assertEquals(1, releases.get())

        unblock.countDown()
        assertEquals(ResourceReleaseResult.RELEASED, release.awaitTrackedRelease(1_000))
        assertEquals(1, releases.get())
    }
}
