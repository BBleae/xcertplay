package com.shilapi.xcertplay.media

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidMediaSinkCloseProgressTest {
    @Test
    fun externalOwnerFailureRetriesOnlyThatExactOwner() {
        val progress = AndroidMediaSinkCloseProgress()
        val owner = RetryingOwner(IOException("injected WebDisplay close failure"))
        var sinkRetirements = 0

        val first = assertThrows(IOException::class.java) {
            progress.close(
                retireSinkResources = { sinkRetirements += 1; null },
                onClosed = owner::close,
            )
        }

        assertSame(owner.failure, first)
        assertTrue(progress.isAdmissionClosed)
        assertFalse(progress.isComplete)
        assertEquals(1, sinkRetirements)
        assertEquals(listOf(owner), owner.observedOwners)

        progress.close(
            retireSinkResources = { sinkRetirements += 1; null },
            onClosed = owner::close,
        )
        progress.close(
            retireSinkResources = { sinkRetirements += 1; null },
            onClosed = owner::close,
        )

        assertTrue(progress.isComplete)
        assertEquals("successful sink retirement must not repeat", 1, sinkRetirements)
        assertEquals("the same callback owner is retried once", 2, owner.closeAttempts)
        assertEquals(listOf(owner, owner), owner.observedOwners)
    }

    @Test
    fun sinkRetirementFailureDoesNotRunExternalOwnerEarly() {
        val progress = AndroidMediaSinkCloseProgress()
        var sinkRetirements = 0
        var ownerCloses = 0

        val first = assertThrows(IllegalStateException::class.java) {
            progress.close(
                retireSinkResources = {
                    sinkRetirements += 1
                    if (sinkRetirements == 1) "injected decoder retirement failure" else null
                },
                onClosed = { ownerCloses += 1 },
            )
        }

        assertTrue(first.message.orEmpty().contains("decoder retirement"))
        assertTrue(progress.isAdmissionClosed)
        assertFalse(progress.isComplete)
        assertEquals(0, ownerCloses)

        progress.close(
            retireSinkResources = { sinkRetirements += 1; null },
            onClosed = { ownerCloses += 1 },
        )

        assertTrue(progress.isComplete)
        assertEquals(2, sinkRetirements)
        assertEquals(1, ownerCloses)
    }

    @Test
    fun fatalCallbackErrorIsRetainedAndPropagatedByIdentity() {
        val progress = AndroidMediaSinkCloseProgress()
        val fatal = AssertionError("injected fatal WebDisplay close failure")
        var sinkRetirements = 0
        var callbackAttempts = 0

        val observed = assertThrows(AssertionError::class.java) {
            progress.close(
                retireSinkResources = { sinkRetirements += 1; null },
                onClosed = {
                    callbackAttempts += 1
                    if (callbackAttempts == 1) throw fatal
                },
            )
        }

        assertSame(fatal, observed)
        assertFalse(progress.isComplete)
        progress.close(
            retireSinkResources = { sinkRetirements += 1; null },
            onClosed = { callbackAttempts += 1 },
        )
        assertTrue(progress.isComplete)
        assertEquals(1, sinkRetirements)
        assertEquals(2, callbackAttempts)
    }

    @Test
    fun sameThreadReentrantCloseCannotReportFalseSuccess() {
        val progress = AndroidMediaSinkCloseProgress()
        var sinkRetirements = 0
        var callbackAttempts = 0
        var nestedFailure: IllegalStateException? = null

        progress.close(
            retireSinkResources = { sinkRetirements += 1; null },
            onClosed = {
                callbackAttempts += 1
                nestedFailure = assertThrows(IllegalStateException::class.java) {
                    progress.close(
                        retireSinkResources = { fail("reentrant close repeated sink retirement"); null },
                        onClosed = { fail("reentrant close repeated callback owner") },
                    )
                }
            },
        )

        assertTrue(progress.isComplete)
        assertEquals(1, sinkRetirements)
        assertEquals(1, callbackAttempts)
        assertTrue(nestedFailure?.message.orEmpty().contains("already in flight"))
    }

    private class RetryingOwner(val failure: IOException) {
        var closeAttempts = 0
        val observedOwners = mutableListOf<RetryingOwner>()

        fun close() {
            observedOwners += this
            closeAttempts += 1
            if (closeAttempts == 1) throw failure
        }
    }
}
