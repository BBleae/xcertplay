package com.shilapi.xcertplay

import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class HostConstructionCleanupTest {
    private class RetryOwnerException : IllegalStateException("cleanup pending"), Closeable {
        var closeCalls = 0

        override fun close() {
            closeCalls++
        }
    }

    @Test
    fun failedHeadRetainsExactOwnerAndCompletedStagesNeverRunTwice() {
        val calls = mutableListOf<String>()
        var mediaAttempts = 0
        val cleanup = HostConstructionCleanup(
            listOf(
                HostConstructionCleanupStage("media") {
                    calls += "media"
                    mediaAttempts++
                    if (mediaAttempts == 1) throw IllegalStateException("synthetic failure")
                },
                HostConstructionCleanupStage("authorization") {
                    calls += "authorization"
                },
                HostConstructionCleanupStage("renderer") {
                    calls += "renderer"
                },
            ),
        )

        assertThrows(IllegalStateException::class.java) { cleanup.close() }
        assertEquals("media", cleanup.pendingStageName())
        assertEquals(listOf("media"), calls)

        cleanup.close()
        assertNull(cleanup.pendingStageName())
        assertEquals(
            listOf("media", "media", "authorization", "renderer"),
            calls,
        )

        cleanup.close()
        assertEquals(
            listOf("media", "media", "authorization", "renderer"),
            calls,
        )
    }

    @Test
    fun fatalErrorAlsoLeavesItsExactStageOwnedForAVisibleRetry() {
        var attempts = 0
        val cleanup = HostConstructionCleanup(
            listOf(
                HostConstructionCleanupStage("fatal") {
                    attempts++
                    if (attempts == 1) throw AssertionError("fatal")
                },
            ),
        )

        assertThrows(AssertionError::class.java) { cleanup.close() }
        assertEquals("fatal", cleanup.pendingStageName())

        cleanup.close()
        assertEquals(2, attempts)
        assertNull(cleanup.pendingStageName())
    }

    @Test
    fun directAndSuppressedConstructionOwnersAreRecoveredOnceByIdentity() {
        val direct = RetryOwnerException()
        val fatal = AssertionError("fatal")
        val suppressed = RetryOwnerException()
        fatal.addSuppressed(suppressed)
        // A cycle is legal in an arbitrary failure graph and must not loop owner discovery.
        suppressed.addSuppressed(fatal)

        assertEquals(listOf(direct), closeableHostConstructionFailures(direct))
        val fromFatal = closeableHostConstructionFailures(fatal)
        assertEquals(1, fromFatal.size)
        assertSame(suppressed, fromFatal.single())

        HostConstructionCleanup(
            fromFatal.mapIndexed { index, owner ->
                HostConstructionCleanupStage("failure[$index]", owner::close)
            },
        ).close()
        assertEquals(1, suppressed.closeCalls)
    }
}
