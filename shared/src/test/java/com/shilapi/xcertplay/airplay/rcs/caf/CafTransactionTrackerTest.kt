package com.shilapi.xcertplay.airplay.rcs.caf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CafTransactionTrackerTest {
    @Test
    fun correlatesByPluginCommandAndTransaction() {
        val tracker = CafTransactionTracker()
        val pending = tracker.begin(
            7,
            CafCommand.READ_REQUEST,
            setOf(CafCommand.READ_RESPONSE, CafCommand.GENERAL_ERROR),
        )
        val response = CarAccessoryMessages.reader(
            CarAccessoryMessages.readResponse(7, pending.transactionId, emptyMap(), emptyMap()).rcsBody,
        )

        assertEquals(pending, tracker.accept(response))
        assertEquals(0, tracker.size())
    }

    @Test
    fun pendingStateIsBoundedAndExpires() {
        var now = 1_000_000L
        val tracker = CafTransactionTracker(
            maxPending = 1,
            timeoutMillis = 10,
            nowNanos = { now },
        )
        tracker.begin(1, CafCommand.CONFIG_REQUEST, setOf(CafCommand.CONFIG_RESPONSE))
        assertThrows(CafProtocolException::class.java) {
            tracker.begin(1, CafCommand.CONFIG_REQUEST, setOf(CafCommand.CONFIG_RESPONSE))
        }

        now += 10_000_000L
        assertEquals(1, tracker.expire().size)
        assertEquals(0, tracker.size())
        tracker.begin(1, CafCommand.CONFIG_REQUEST, setOf(CafCommand.CONFIG_RESPONSE))
    }

    @Test
    fun unknownOrMismatchedResponsesCannotCompleteTransactions() {
        val tracker = CafTransactionTracker()
        val pending = tracker.begin(7, CafCommand.READ_REQUEST, setOf(CafCommand.READ_RESPONSE))
        val wrongPlugin = CarAccessoryMessages.reader(
            CarAccessoryMessages.readResponse(8, pending.transactionId, emptyMap(), emptyMap()).rcsBody,
        )
        assertThrows(CafProtocolException::class.java) { tracker.accept(wrongPlugin) }
        assertEquals(1, tracker.size())

        val unknown = CarAccessoryMessages.reader(
            CarAccessoryMessages.readResponse(7, 999, emptyMap(), emptyMap()).rcsBody,
        )
        assertThrows(CafProtocolException::class.java) { tracker.accept(unknown) }
        assertTrue(tracker.cancel(pending))
    }
}
