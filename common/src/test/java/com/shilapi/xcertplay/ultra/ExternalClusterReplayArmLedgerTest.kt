package com.shilapi.xcertplay.ultra

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalClusterReplayArmLedgerTest {
    @Test fun phoneResetInvalidatesArmedReplayIdentityButPreservesProducerTimestampFloor() {
        val ledger = ExternalClusterReplayArmLedger({ 100L }, retryWindowNanos = 1_000L)
        val oldProof = Any()
        val first = ledger.begin(oldProof, 42L)
            as ExternalClusterReplayArmLedger.Begin.Ready
        assertEquals(InstrumentReplayPermitDecision.ARMED,
            ledger.finish(first.attempt, armed = true))
        ledger.invalidate()
        assertTrue(ledger.begin(oldProof, 42L) is ExternalClusterReplayArmLedger.Begin.Rejected)
        assertTrue(ledger.begin(Any(), 41L) is ExternalClusterReplayArmLedger.Begin.Rejected)
        val fresh = ledger.begin(Any(), 43L)
            as ExternalClusterReplayArmLedger.Begin.Ready
        assertEquals(InstrumentReplayPermitDecision.ARMED,
            ledger.finish(fresh.attempt, armed = true))
    }

    @Test fun oneSealedReceiptCanRetryAfterFirstHostArmFailsWithoutConsumingTimestamp() {
        var now = 100L
        val ledger = ExternalClusterReplayArmLedger({ now }, retryWindowNanos = 1_000L)
        val sealedReceipt = Any()
        val first = ledger.begin(sealedReceipt, 42L)
            as ExternalClusterReplayArmLedger.Begin.Ready
        assertEquals(InstrumentReplayPermitDecision.BUSY_RETRY,
            ledger.finish(first.attempt, armed = false))
        now += 10L // no new EGL frame or timestamp
        val retry = ledger.begin(sealedReceipt, 42L)
            as ExternalClusterReplayArmLedger.Begin.Ready
        assertSame(first.attempt, retry.attempt)
        assertEquals(InstrumentReplayPermitDecision.ARMED,
            ledger.finish(retry.attempt, armed = true))
        assertTrue(ledger.begin(sealedReceipt, 42L) is
            ExternalClusterReplayArmLedger.Begin.AlreadyArmed)
        assertTrue(ledger.begin(Any(), 42L) is ExternalClusterReplayArmLedger.Begin.Rejected)
    }

    @Test fun concurrentArmIsBusyAndNewerReceiptSupersedesOnlyAfterFailedAttempt() {
        val ledger = ExternalClusterReplayArmLedger({ 100L }, retryWindowNanos = 1_000L)
        val first = ledger.begin(Any(), 42L)
            as ExternalClusterReplayArmLedger.Begin.Ready
        assertTrue(ledger.begin(Any(), 43L) is ExternalClusterReplayArmLedger.Begin.Busy)
        assertEquals(InstrumentReplayPermitDecision.BUSY_RETRY,
            ledger.finish(first.attempt, armed = false))
        val newer = ledger.begin(Any(), 43L)
            as ExternalClusterReplayArmLedger.Begin.Ready
        assertEquals(InstrumentReplayPermitDecision.ARMED,
            ledger.finish(newer.attempt, armed = true))
        assertTrue(ledger.begin(first.attempt.receipt, 42L) is
            ExternalClusterReplayArmLedger.Begin.Rejected)
    }

    @Test fun retryDeadlineDoesNotAllowAnUnboundedStalePermit() {
        var now = 100L
        val ledger = ExternalClusterReplayArmLedger({ now }, retryWindowNanos = 50L)
        val receipt = Any()
        val first = ledger.begin(receipt, 42L)
            as ExternalClusterReplayArmLedger.Begin.Ready
        assertEquals(InstrumentReplayPermitDecision.BUSY_RETRY,
            ledger.finish(first.attempt, armed = false))
        now = 151L
        assertTrue(ledger.begin(receipt, 42L) is ExternalClusterReplayArmLedger.Begin.Rejected)
    }
}
