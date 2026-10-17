package com.shilapi.xcertplay.mfi

import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MfiAuthenticationReceiptTest {
    @Test
    fun receiptIsConsumedExactlyOnceAcrossAllCallers() {
        val identity = identity(1)
        val receipt = receipt(identity, 7)

        assertSame(identity, receipt.consumeForUltraActivation { })
        assertNull(receipt.consumeForUltraActivation { })
        receipt.revoke()
        assertNull(receipt.consumeForUltraActivation { })
    }

    @Test
    fun revokedReceiptCanNeverBeConsumed() {
        val receipt = receipt(identity(2), 8)

        receipt.revoke()

        assertNull(receipt.consumeForUltraActivation { })
    }

    @Test
    fun newControlAttemptRevokesThePreviousGeneration() {
        val identity = identity(4)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val firstChannel = Any()
        val firstAttempt = authority.begin(firstChannel, 10)
        val firstReceipt = firstAttempt.issue(firstChannel, identity)

        val secondChannel = Any()
        val secondAttempt = authority.begin(secondChannel, 11)
        val secondReceipt = secondAttempt.issue(secondChannel, identity)

        assertFalse(firstReceipt.isCurrentAuthenticationAttempt())
        assertTrue(secondReceipt.isCurrentAuthenticationAttempt())
        assertNull(firstReceipt.consumeForUltraActivation { })
        assertSame(identity, secondReceipt.consumeForUltraActivation { })
    }

    @Test
    fun closingAttemptRevokesAnUnconsumedReceipt() {
        val identity = identity(5)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val channel = Any()
        val attempt = authority.begin(channel, 12)
        val receipt = attempt.issue(channel, identity)

        attempt.close()

        assertNull(receipt.consumeForUltraActivation { })
    }

    @Test
    fun closingConsumedAttemptRevokesLivenessAndNotifiesOwnerOnce() {
        val identity = identity(6)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val channel = Any()
        val attempt = authority.begin(channel, 13)
        val receipt = attempt.issue(channel, identity)
        var revocations = 0
        assertSame(identity, receipt.consumeForUltraActivation { revocations++ })
        assertTrue(receipt.isLiveForUltraActivation())

        attempt.close()
        attempt.close()

        assertFalse(receipt.isLiveForUltraActivation())
        assertEquals(1, revocations)
    }

    @Test
    fun failedSecondConsumeCannotReplaceTheSuccessfulOwnersRevocationCallback() {
        val identity = identity(7)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val channel = Any()
        val attempt = authority.begin(channel, 14)
        val receipt = attempt.issue(channel, identity)
        var firstRevocations = 0
        var secondRevocations = 0

        assertSame(identity, receipt.consumeForUltraActivation { firstRevocations++ })
        assertNull(receipt.consumeForUltraActivation { secondRevocations++ })
        attempt.close()

        assertEquals(1, firstRevocations)
        assertEquals(0, secondRevocations)
    }

    @Test
    fun lateOlderBeginOrInvalidationCannotReplaceTheCurrentControlGeneration() {
        val identity = identity(8)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val currentChannel = Any()
        val currentAttempt = authority.begin(currentChannel, 11)
        val currentReceipt = currentAttempt.issue(currentChannel, identity)

        authority.invalidate(10)
        assertTrue(currentReceipt.isCurrentAuthenticationAttempt())
        assertThrows(IllegalStateException::class.java) {
            authority.begin(Any(), 10)
        }
        assertTrue(currentReceipt.isCurrentAuthenticationAttempt())
    }

    @Test
    fun pendingHandoffDoesNotRevokeOrReplaceItsConsumedPredecessor() {
        val identity = identity(20)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 20)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        var predecessorRevocations = 0
        assertSame(identity, predecessor.consumeForUltraActivation { predecessorRevocations++ })

        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 21)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)

        assertTrue(predecessor.isLiveForUltraActivation())
        assertNull(successor.consumeForUltraActivation { })
        assertEquals(0, predecessorRevocations)

        handoff.cancel()
        handoff.close()

        assertTrue(predecessor.isLiveForUltraActivation())
        assertNull(successor.consumeForUltraActivation { })
        assertEquals(0, predecessorRevocations)
    }

    @Test
    fun commitRegistersConsumedSuccessorBeforePredecessorRevocationCallback() {
        val identity = identity(21)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 30)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        val currentRegistration = AtomicReference<MfiAuthenticationReceipt>(predecessor)
        var predecessorRevocations = 0
        lateinit var successor: MfiAuthenticationReceipt
        assertSame(
            identity,
            predecessor.consumeForUltraActivation {
                predecessorRevocations++
                assertSame(successor, currentRegistration.get())
                assertTrue(successor.isLiveForUltraActivation())
                authority.invalidate(30)
            },
        )
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 31)
        successor = handoff.attemptForExchange().issue(successorChannel, identity)
        var successorRevocations = 0

        val committed = handoff.commit(successor) { promoted ->
            assertSame(identity, promoted.consumeForUltraHandoffRenewal(predecessor) { successorRevocations++ })
            currentRegistration.set(promoted)
            assertEquals("old callback must still be deferred", 0, predecessorRevocations)
        }

        assertSame(successor, committed)
        assertEquals(1, predecessorRevocations)
        assertFalse(predecessor.isLiveForUltraActivation())
        assertTrue(successor.isLiveForUltraActivation())
        assertEquals(0, successorRevocations)
    }

    @Test
    fun handoffRenewalConsumptionRequiresExactAuthorityLineageAndPromotionWindow() {
        val identity = identity(248)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 35)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        assertSame(identity, predecessor.consumeForUltraActivation { })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 36)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)

        val foreignAuthority = MfiAuthenticationReceiptAuthority.create(identity)
        val foreignChannel = Any()
        val foreignAttempt = foreignAuthority.begin(foreignChannel, 1)
        val foreign = foreignAttempt.issue(foreignChannel, identity)

        handoff.commit(successor) { promoted ->
            assertNull(promoted.consumeForUltraActivation { })
            assertNull(
                foreign.consumeForUltraHandoffRenewal(predecessor) { },
            )
            assertSame(
                identity,
                promoted.consumeForUltraHandoffRenewal(predecessor) { },
            )
        }

        assertTrue(successor.isLiveForUltraActivation())
        assertNull(
            foreign.consumeForUltraHandoffRenewal(predecessor) { },
        )
    }

    @Test
    fun failedPromotionRollsBackToPredecessorAndRevokesConsumedSuccessor() {
        val identity = identity(22)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 40)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        var predecessorRevocations = 0
        assertSame(identity, predecessor.consumeForUltraActivation { predecessorRevocations++ })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 41)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)
        var successorRevocations = 0

        val failure = assertThrows(IllegalArgumentException::class.java) {
            handoff.commit(successor) { promoted ->
                assertSame(identity, promoted.consumeForUltraHandoffRenewal(predecessor) { successorRevocations++ })
                throw IllegalArgumentException("registration failed")
            }
        }

        assertEquals("registration failed", failure.message)
        assertTrue(predecessor.isLiveForUltraActivation())
        assertEquals(0, predecessorRevocations)
        assertFalse(successor.isLiveForUltraActivation())
        assertEquals(1, successorRevocations)
        assertNull(successor.consumeForUltraActivation { })
    }

    @Test
    fun failedSuccessorRollbackCleanupAlsoFailsClosedThePredecessor() {
        val identity = identity(247)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 45)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        val predecessorCallbacks = AtomicInteger()
        assertSame(
            identity,
            predecessor.consumeForUltraActivation {
                predecessorCallbacks.incrementAndGet()
                throw IllegalStateException("predecessor cleanup failed")
            },
        )
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 46)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)
        val successorCallbacks = AtomicInteger()
        val successorPublished = AtomicReference(false)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            handoff.commit(successor) { promoted ->
                checkNotNull(
                    promoted.consumeForUltraHandoffRenewal(predecessor) {
                        successorCallbacks.incrementAndGet()
                        throw IllegalStateException("successor cleanup failed")
                    },
                )
                successorPublished.set(true)
                throw IllegalArgumentException("promotion publication failed")
            }
        }

        assertEquals("promotion publication failed", failure.message)
        assertFalse(predecessor.isLiveForUltraActivation())
        assertFalse(successor.isLiveForUltraActivation())
        assertTrue(successorPublished.get())
        assertEquals(1, predecessorCallbacks.get())
        assertEquals(1, successorCallbacks.get())
        assertThrows(IllegalStateException::class.java) { authority.close() }
        assertEquals(2, predecessorCallbacks.get())
        assertEquals(2, successorCallbacks.get())
    }

    @Test
    fun promotionMustConsumeSuccessorBeforeItCanRevokePredecessor() {
        val identity = identity(23)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 50)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        assertSame(identity, predecessor.consumeForUltraActivation { })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 51)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)

        assertThrows(IllegalStateException::class.java) {
            handoff.commit(successor) { }
        }

        assertTrue(predecessor.isLiveForUltraActivation())
        assertNull(successor.consumeForUltraActivation { })
    }

    @Test
    fun onlyOnePendingHandoffCanExistAndClosingItLeavesPredecessorLive() {
        val identity = identity(24)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 60)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        assertSame(identity, predecessor.consumeForUltraActivation { })
        val first = authority.beginHandoff(predecessor, Any(), 61)

        assertThrows(IllegalStateException::class.java) {
            authority.beginHandoff(predecessor, Any(), 62)
        }

        first.close()
        assertTrue(predecessor.isLiveForUltraActivation())
        val replacement = authority.beginHandoff(predecessor, Any(), 63)
        replacement.close()
        assertTrue(predecessor.isLiveForUltraActivation())
    }

    @Test
    fun handoffRejectsForeignOrStalePredecessorsEvenWithSameCertificateIdentity() {
        val identity = identity(25)
        val firstAuthority = MfiAuthenticationReceiptAuthority.create(identity)
        val firstChannel = Any()
        val firstAttempt = firstAuthority.begin(firstChannel, 70)
        val firstReceipt = firstAttempt.issue(firstChannel, identity)
        val secondAuthority = MfiAuthenticationReceiptAuthority.create(identity)

        assertThrows(IllegalStateException::class.java) {
            secondAuthority.beginHandoff(firstReceipt, Any(), 70)
        }

        val replacementChannel = Any()
        val replacementAttempt = firstAuthority.begin(replacementChannel, 71)
        replacementAttempt.issue(replacementChannel, identity)
        assertThrows(IllegalStateException::class.java) {
            firstAuthority.beginHandoff(firstReceipt, Any(), 72)
        }
    }

    @Test
    fun pendingSuccessorCanIssueOnlyForItsExactChannelAndCertificateIdentity() {
        val identity = identity(26)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 80)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 81)
        val successorAttempt = handoff.attemptForExchange()

        assertThrows(IllegalStateException::class.java) {
            successorAttempt.issue(Any(), identity)
        }
        assertThrows(IllegalStateException::class.java) {
            successorAttempt.issue(successorChannel, identity(126))
        }

        val successor = successorAttempt.issue(successorChannel, identity)
        assertNull(successor.consumeForUltraActivation { })
        handoff.close()
        assertTrue(predecessor.isCurrentAuthenticationAttempt())
    }

    @Test
    fun sourceBoundAttemptRejectsForeignAuthenticatorWithTheSameCertificate() {
        val exactSource = authenticator(260)
        val foreignSource = authenticator(260)
        val identity = MfiAuthenticationIdentity.capture(exactSource)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        authority.bindAuthenticationSource(exactSource)
        val channel = Any()
        val attempt = authority.begin(channel, 85)

        assertThrows(IllegalStateException::class.java) {
            attempt.claimExchange(channel, foreignSource)
        }

        attempt.claimExchange(channel, exactSource)
        assertThrows(IllegalStateException::class.java) {
            attempt.claimExchange(channel, exactSource)
        }
        assertThrows(IllegalStateException::class.java) {
            attempt.issue(channel, identity)
        }
        assertThrows(IllegalStateException::class.java) {
            attempt.issue(channel, identity, exactSource, Any())
        }
        attempt.requireLiveExchange(channel, exactSource)
    }

    @Test
    fun claimedExchangeLivenessTracksExactAttemptChannelAndAuthorityGeneration() {
        val source = authenticator(261)
        val identity = MfiAuthenticationIdentity.capture(source)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        authority.bindAuthenticationSource(source)
        val channel = Any()
        val attempt = authority.begin(channel, 86)
        attempt.claimExchange(channel, source)

        attempt.requireLiveExchange(channel, source)
        assertThrows(IllegalStateException::class.java) {
            attempt.requireLiveExchange(Any(), source)
        }

        authority.begin(Any(), 87)

        assertThrows(IllegalStateException::class.java) {
            attempt.requireLiveExchange(channel, source)
        }
    }

    @Test
    fun staleHandoffCancelAndPredecessorCloseCannotRevokeCommittedSuccessor() {
        val identity = identity(27)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 90)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        assertSame(identity, predecessor.consumeForUltraActivation { })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 91)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)
        handoff.commit(successor) { promoted ->
            assertSame(identity, promoted.consumeForUltraHandoffRenewal(predecessor) { })
        }

        handoff.cancel()
        handoff.close()
        predecessorAttempt.close()
        predecessor.revoke()

        assertTrue(successor.isLiveForUltraActivation())
    }

    @Test
    fun revokingPendingHandoffPredecessorAlsoRevokesAndReleasesItsSuccessor() {
        val identity = identity(38)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 95)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        assertSame(identity, predecessor.consumeForUltraActivation { })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 96)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)

        predecessor.revoke()

        assertFalse(predecessor.isLiveForUltraActivation())
        assertNull(successor.consumeForUltraActivation { })
        assertThrows(IllegalStateException::class.java) {
            handoff.commit(successor) { promoted ->
                promoted.consumeForUltraHandoffRenewal(predecessor) { }
            }
        }
        val replacementChannel = Any()
        val replacement = authority.begin(replacementChannel, 97)
        replacement.issue(replacementChannel, identity)
    }

    @Test
    fun ordinaryBeginDuringPendingHandoffStillRevokesThePredecessorAndCandidate() {
        val identity = identity(28)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 100)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        var predecessorRevocations = 0
        assertSame(identity, predecessor.consumeForUltraActivation { predecessorRevocations++ })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 101)
        val candidate = handoff.attemptForExchange().issue(successorChannel, identity)

        val ordinaryChannel = Any()
        val ordinaryAttempt = authority.begin(ordinaryChannel, 102)
        val ordinaryReceipt = ordinaryAttempt.issue(ordinaryChannel, identity)

        assertEquals(1, predecessorRevocations)
        assertFalse(predecessor.isLiveForUltraActivation())
        assertNull(candidate.consumeForUltraActivation { })
        assertSame(identity, ordinaryReceipt.consumeForUltraActivation { })
    }

    @Test
    fun authorityCloseRevokesBothLivePredecessorAndPendingSuccessor() {
        val identity = identity(29)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 110)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        var predecessorRevocations = 0
        assertSame(identity, predecessor.consumeForUltraActivation { predecessorRevocations++ })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 111)
        val candidate = handoff.attemptForExchange().issue(successorChannel, identity)

        authority.close()

        assertEquals(1, predecessorRevocations)
        assertFalse(predecessor.isLiveForUltraActivation())
        assertNull(candidate.consumeForUltraActivation { })
        assertThrows(IllegalStateException::class.java) {
            handoff.commit(candidate) { promoted -> promoted.consumeForUltraHandoffRenewal(predecessor) { } }
        }
    }

    @Test
    fun exactPendingSuccessorInvalidationPreservesPredecessorUntilItsOwnGenerationEnds() {
        val identity = identity(34)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 160)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        assertSame(identity, predecessor.consumeForUltraActivation { })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 161)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)

        authority.invalidate(161)
        authority.invalidate(161)

        assertTrue(predecessor.isLiveForUltraActivation())
        assertNull(successor.consumeForUltraActivation { })

        authority.invalidate(160)
        assertFalse(predecessor.isLiveForUltraActivation())
    }

    @Test
    fun generationBeyondTheHandoffHighWaterIsAnAuthorityWideBarrier() {
        val identity = identity(35)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 170)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        assertSame(identity, predecessor.consumeForUltraActivation { })
        val handoff = authority.beginHandoff(predecessor, Any(), 171)
        handoff.cancel()

        authority.invalidate(172)

        assertFalse(predecessor.isLiveForUltraActivation())
    }

    @Test
    fun concurrentHandoffStartsAdmitExactlyOnePendingSuccessor() {
        val identity = identity(30)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 120)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        assertSame(identity, predecessor.consumeForUltraActivation { })
        val start = CountDownLatch(1)
        val admitted = AtomicInteger()
        val winner = AtomicReference<MfiAuthenticationHandoff?>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(121L, 122L).map { generation ->
                executor.submit {
                    start.await()
                    runCatching {
                        authority.beginHandoff(predecessor, Any(), generation)
                    }.onSuccess { handoff ->
                        admitted.incrementAndGet()
                        winner.set(handoff)
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, admitted.get())
        checkNotNull(winner.get()).close()
        assertTrue(predecessor.isLiveForUltraActivation())
    }

    @Test
    fun ordinaryBeginIsRejectedWhilePromotionCallbackRunsWithoutHoldingAuthorityLock() {
        val identity = identity(32)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 140)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        assertSame(identity, predecessor.consumeForUltraActivation { })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 141)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)
        val promotionEntered = CountDownLatch(1)
        val releasePromotion = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val commit = executor.submit {
                runCatching {
                    handoff.commit(successor) { promoted ->
                        checkNotNull(promoted.consumeForUltraHandoffRenewal(predecessor) { })
                        promotionEntered.countDown()
                        check(releasePromotion.await(5, TimeUnit.SECONDS))
                    }
                }.onFailure(failure::set)
            }
            assertTrue(promotionEntered.await(5, TimeUnit.SECONDS))

            assertThrows(IllegalStateException::class.java) {
                authority.begin(Any(), 142)
            }

            releasePromotion.countDown()
            commit.get(5, TimeUnit.SECONDS)
        } finally {
            releasePromotion.countDown()
            executor.shutdownNow()
        }

        assertNull(failure.get())
        assertTrue(successor.isLiveForUltraActivation())
        assertFalse(predecessor.isLiveForUltraActivation())
    }

    @Test
    fun authorityCloseDoesNotWaitForPromotionCallbackAndRevokesBothGenerations() {
        val identity = identity(33)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 150)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        val predecessorRevocations = AtomicInteger()
        assertSame(identity, predecessor.consumeForUltraActivation { predecessorRevocations.incrementAndGet() })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 151)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)
        val successorRevocations = AtomicInteger()
        val promotionEntered = CountDownLatch(1)
        val releasePromotion = CountDownLatch(1)
        val commitFailure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val commit = executor.submit {
                runCatching {
                    handoff.commit(successor) { promoted ->
                        checkNotNull(
                            promoted.consumeForUltraHandoffRenewal(predecessor) {
                                successorRevocations.incrementAndGet()
                            },
                        )
                        promotionEntered.countDown()
                        check(releasePromotion.await(5, TimeUnit.SECONDS))
                    }
                }.onFailure(commitFailure::set)
            }
            assertTrue(promotionEntered.await(5, TimeUnit.SECONDS))

            assertThrows(IllegalStateException::class.java) { authority.close() }

            assertEquals(0, predecessorRevocations.get())
            assertEquals(0, successorRevocations.get())
            releasePromotion.countDown()
            commit.get(5, TimeUnit.SECONDS)
        } finally {
            releasePromotion.countDown()
            executor.shutdownNow()
        }

        assertTrue(commitFailure.get() is IllegalStateException)
        assertEquals(1, predecessorRevocations.get())
        assertEquals(1, successorRevocations.get())
        assertFalse(predecessor.isLiveForUltraActivation())
        assertFalse(successor.isLiveForUltraActivation())
        authority.close()
    }

    @Test
    fun cancellationDefersSuccessorCleanupUntilPromotionCallbackFinishesPublishing() {
        val identity = identity(39)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 155)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        assertSame(identity, predecessor.consumeForUltraActivation { })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 156)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)
        val consumed = CountDownLatch(1)
        val publish = CountDownLatch(1)
        val registered = AtomicReference(false)
        val cleanups = AtomicInteger()
        val commitFailure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val commit = executor.submit {
                runCatching {
                    handoff.commit(successor) { promoted ->
                        checkNotNull(
                            promoted.consumeForUltraHandoffRenewal(predecessor) {
                                registered.set(false)
                                cleanups.incrementAndGet()
                            },
                        )
                        consumed.countDown()
                        check(publish.await(5, TimeUnit.SECONDS))
                        registered.set(true)
                    }
                }.onFailure(commitFailure::set)
            }
            assertTrue(consumed.await(5, TimeUnit.SECONDS))

            handoff.cancel()

            assertEquals(0, cleanups.get())
            publish.countDown()
            commit.get(5, TimeUnit.SECONDS)
        } finally {
            publish.countDown()
            executor.shutdownNow()
        }

        assertTrue(commitFailure.get() is IllegalStateException)
        assertFalse(registered.get())
        assertEquals(1, cleanups.get())
        assertTrue(predecessor.isLiveForUltraActivation())
    }

    @Test
    fun predecessorGenerationEndingDuringPromotionRevokesBothGenerations() {
        val identity = identity(36)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 180)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        val predecessorRevocations = AtomicInteger()
        assertSame(identity, predecessor.consumeForUltraActivation { predecessorRevocations.incrementAndGet() })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 181)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)
        val successorRevocations = AtomicInteger()
        val promotionEntered = CountDownLatch(1)
        val releasePromotion = CountDownLatch(1)
        val commitFailure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val commit = executor.submit {
                runCatching {
                    handoff.commit(successor) { promoted ->
                        checkNotNull(
                            promoted.consumeForUltraHandoffRenewal(predecessor) {
                                successorRevocations.incrementAndGet()
                            },
                        )
                        promotionEntered.countDown()
                        check(releasePromotion.await(5, TimeUnit.SECONDS))
                    }
                }.onFailure(commitFailure::set)
            }
            assertTrue(promotionEntered.await(5, TimeUnit.SECONDS))

            authority.invalidate(180)

            releasePromotion.countDown()
            commit.get(5, TimeUnit.SECONDS)
        } finally {
            releasePromotion.countDown()
            executor.shutdownNow()
        }

        assertTrue(commitFailure.get() is IllegalStateException)
        assertEquals(1, predecessorRevocations.get())
        assertEquals(1, successorRevocations.get())
        assertFalse(predecessor.isLiveForUltraActivation())
        assertFalse(successor.isLiveForUltraActivation())
    }

    @Test
    fun successorGenerationEndingDuringPromotionRollsBackToPredecessor() {
        val identity = identity(37)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 190)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        assertSame(identity, predecessor.consumeForUltraActivation { })
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 191)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)

        assertThrows(IllegalStateException::class.java) {
            handoff.commit(successor) { promoted ->
                checkNotNull(promoted.consumeForUltraHandoffRenewal(predecessor) { })
                authority.invalidate(191)
            }
        }

        assertTrue(predecessor.isLiveForUltraActivation())
        assertFalse(successor.isLiveForUltraActivation())
    }

    @Test
    fun concurrentCommitAndCancelCannotLeaveBothReceiptsLive() {
        repeat(64) { iteration ->
            val identity = identity(40 + iteration)
            val authority = MfiAuthenticationReceiptAuthority.create(identity)
            val predecessorChannel = Any()
            val predecessorAttempt = authority.begin(predecessorChannel, 1)
            val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
            assertSame(identity, predecessor.consumeForUltraActivation { })
            val successorChannel = Any()
            val handoff = authority.beginHandoff(predecessor, successorChannel, 2)
            val successor = handoff.attemptForExchange().issue(successorChannel, identity)
            val start = CountDownLatch(1)
            val committed = AtomicInteger()
            val executor = Executors.newFixedThreadPool(2)
            try {
                val commit = executor.submit {
                    start.await()
                    runCatching {
                        handoff.commit(successor) { promoted ->
                            checkNotNull(promoted.consumeForUltraHandoffRenewal(predecessor) { })
                        }
                    }.onSuccess { committed.incrementAndGet() }
                }
                val cancel = executor.submit {
                    start.await()
                    handoff.cancel()
                }
                start.countDown()
                commit.get(5, TimeUnit.SECONDS)
                cancel.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }

            if (committed.get() == 1) {
                assertFalse(predecessor.isLiveForUltraActivation())
                assertTrue(successor.isLiveForUltraActivation())
            } else {
                assertTrue(predecessor.isLiveForUltraActivation())
                assertFalse(successor.isLiveForUltraActivation())
            }
            authority.close()
        }
    }

    @Test
    fun failedRevocationCallbackIsRetainedAndRetriedBeforeAuthorityCloseCompletes() {
        val identity = identity(240)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val channel = Any()
        val attempt = authority.begin(channel, 210)
        val receipt = attempt.issue(channel, identity)
        val callbackAttempts = AtomicInteger()
        assertSame(
            identity,
            receipt.consumeForUltraActivation {
                if (callbackAttempts.incrementAndGet() == 1) {
                    throw IllegalStateException("first cleanup failed")
                }
            },
        )

        attempt.close()

        assertEquals(1, callbackAttempts.get())
        assertThrows(IllegalStateException::class.java) {
            authority.begin(Any(), 211)
        }

        authority.close()
        assertEquals(2, callbackAttempts.get())
    }

    @Test
    fun failedOrdinaryBeginDoesNotPublishReplacementAndExactRetryRestoresAdmission() {
        val identity = identity(252)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 300)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        val callbackAttempts = AtomicInteger()
        val originalFailure = IllegalStateException("retirement failed")
        val replacementChannel = Any()
        val activeField = MfiAuthenticationReceiptAuthority::class.java
            .getDeclaredField("active")
            .apply { isAccessible = true }
        assertSame(
            identity,
            predecessor.consumeForUltraActivation {
                if (callbackAttempts.incrementAndGet() == 1) {
                    val unpublishedReplacement =
                        activeField.get(authority) as MfiAuthenticationAttempt
                    assertThrows(IllegalStateException::class.java) {
                        unpublishedReplacement.issue(replacementChannel, identity)
                    }
                    throw originalFailure
                }
            },
        )

        val failedBegin = assertThrows(IllegalStateException::class.java) {
            authority.begin(replacementChannel, 301)
        }

        assertSame(originalFailure, failedBegin.cause)
        assertEquals(1, callbackAttempts.get())
        assertNull("failed begin must not retain its unpublished active attempt", activeField.get(authority))
        assertFalse(predecessor.isLiveForUltraActivation())
        assertThrows(IllegalStateException::class.java) {
            authority.begin(Any(), 302)
        }
        assertEquals("admission must not silently replay retirement", 1, callbackAttempts.get())

        authority.retryRevocationCleanup()
        assertEquals("the exact retained callback is retried once", 2, callbackAttempts.get())
        val replacement = authority.begin(replacementChannel, 302)
        val replacementReceipt = replacement.issue(replacementChannel, identity)
        assertSame(identity, replacementReceipt.consumeForUltraActivation { })
        authority.close()
    }

    @Test
    fun retryKeepsAdmissionClosedWhileTheExactCallbackIsRunning() {
        val identity = identity(253)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val channel = Any()
        val attempt = authority.begin(channel, 310)
        val receipt = attempt.issue(channel, identity)
        val callbackAttempts = AtomicInteger()
        val retryEntered = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        assertSame(
            identity,
            receipt.consumeForUltraActivation {
                if (callbackAttempts.incrementAndGet() == 1) {
                    throw IllegalStateException("initial retirement failed")
                }
                retryEntered.countDown()
                check(releaseRetry.await(5, TimeUnit.SECONDS))
            },
        )
        attempt.close()
        val retryFailure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val retry = executor.submit {
                runCatching { authority.retryRevocationCleanup() }.onFailure(retryFailure::set)
            }
            assertTrue(retryEntered.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) {
                authority.begin(Any(), 311)
            }
            releaseRetry.countDown()
            retry.get(5, TimeUnit.SECONDS)
        } finally {
            releaseRetry.countDown()
            executor.shutdownNow()
        }

        assertNull(retryFailure.get())
        assertEquals(2, callbackAttempts.get())
        val replacementChannel = Any()
        val replacement = authority.begin(replacementChannel, 311)
        assertSame(identity, replacement.issue(replacementChannel, identity).consumeForUltraActivation { })
        authority.close()
    }

    @Test
    fun closeDuringOrdinaryBeginRetirementCannotReturnAnApparentlyLiveAttempt() {
        val identity = identity(255)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val channel = Any()
        val attempt = authority.begin(channel, 315)
        val receipt = attempt.issue(channel, identity)
        val retirementEntered = CountDownLatch(1)
        val releaseRetirement = CountDownLatch(1)
        assertSame(
            identity,
            receipt.consumeForUltraActivation {
                retirementEntered.countDown()
                check(releaseRetirement.await(5, TimeUnit.SECONDS))
            },
        )
        val beginFailure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val begin = executor.submit {
                runCatching { authority.begin(Any(), 316) }.onFailure(beginFailure::set)
            }
            assertTrue(retirementEntered.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { authority.close() }
            releaseRetirement.countDown()
            begin.get(5, TimeUnit.SECONDS)
        } finally {
            releaseRetirement.countDown()
            executor.shutdownNow()
        }

        assertTrue(beginFailure.get() is IllegalStateException)
        assertFalse(receipt.isLiveForUltraActivation())
        authority.close()
    }

    @Test
    fun failedRetryRetainsTheSameOwnerAndDoesNotAdmitAnotherGeneration() {
        val identity = identity(254)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val channel = Any()
        val attempt = authority.begin(channel, 320)
        val receipt = attempt.issue(channel, identity)
        val callbackAttempts = AtomicInteger()
        assertSame(
            identity,
            receipt.consumeForUltraActivation {
                callbackAttempts.incrementAndGet()
                throw IllegalStateException("still owned")
            },
        )
        attempt.close()

        assertThrows(IllegalStateException::class.java) { authority.retryRevocationCleanup() }
        assertThrows(IllegalStateException::class.java) { authority.begin(Any(), 321) }
        assertEquals(2, callbackAttempts.get())
        assertThrows(IllegalStateException::class.java) { authority.close() }
        assertEquals("close must retain and retry the same callback", 3, callbackAttempts.get())
    }

    @Test
    fun persistentRevocationFailureRemainsOwnedAcrossAuthorityCloseRetries() {
        val identity = identity(241)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val channel = Any()
        val attempt = authority.begin(channel, 220)
        val receipt = attempt.issue(channel, identity)
        val callbackAttempts = AtomicInteger()
        assertSame(
            identity,
            receipt.consumeForUltraActivation {
                callbackAttempts.incrementAndGet()
                throw IllegalStateException("cleanup still failing")
            },
        )
        attempt.close()

        assertThrows(IllegalStateException::class.java) { authority.close() }
        assertThrows(IllegalStateException::class.java) { authority.close() }

        assertEquals(3, callbackAttempts.get())
    }

    @Test
    fun revocationErrorIsRetainedButStillPropagatesImmediately() {
        val identity = identity(243)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val channel = Any()
        val attempt = authority.begin(channel, 225)
        val receipt = attempt.issue(channel, identity)
        val callbackAttempts = AtomicInteger()
        assertSame(
            identity,
            receipt.consumeForUltraActivation {
                callbackAttempts.incrementAndGet()
                throw AssertionError("fatal cleanup failure")
            },
        )

        assertThrows(AssertionError::class.java) { attempt.close() }
        assertThrows(AssertionError::class.java) { authority.close() }

        assertEquals(2, callbackAttempts.get())
    }

    @Test
    fun closeMarksAuthorityClosedBeforeBlockingRevocationRetryRuns() {
        val identity = identity(244)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val channel = Any()
        val attempt = authority.begin(channel, 226)
        val receipt = attempt.issue(channel, identity)
        val callbackAttempts = AtomicInteger()
        val retryEntered = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        assertSame(
            identity,
            receipt.consumeForUltraActivation {
                if (callbackAttempts.incrementAndGet() == 1) {
                    throw IllegalStateException("initial cleanup failed")
                }
                retryEntered.countDown()
                check(releaseRetry.await(5, TimeUnit.SECONDS))
            },
        )
        attempt.close()
        val closeFailure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val close = executor.submit {
                runCatching { authority.close() }.onFailure(closeFailure::set)
            }
            assertTrue(retryEntered.await(5, TimeUnit.SECONDS))

            assertThrows(IllegalStateException::class.java) {
                authority.begin(Any(), 227)
            }

            releaseRetry.countDown()
            close.get(5, TimeUnit.SECONDS)
        } finally {
            releaseRetry.countDown()
            executor.shutdownNow()
        }

        assertNull(closeFailure.get())
        assertEquals(2, callbackAttempts.get())
    }

    @Test
    fun predecessorCleanupFailureAfterPromotionFailsClosedAndRevokesSuccessor() {
        val identity = identity(242)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 230)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        val predecessorCallbacks = AtomicInteger()
        assertSame(
            identity,
            predecessor.consumeForUltraActivation {
                predecessorCallbacks.incrementAndGet()
                throw IllegalStateException("old owner cleanup failed")
            },
        )
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 231)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)
        val successorCallbacks = AtomicInteger()

        assertThrows(IllegalStateException::class.java) {
            handoff.commit(successor) { promoted ->
                checkNotNull(
                    promoted.consumeForUltraHandoffRenewal(predecessor) {
                        successorCallbacks.incrementAndGet()
                    },
                )
            }
        }

        assertFalse(predecessor.isLiveForUltraActivation())
        assertFalse(successor.isLiveForUltraActivation())
        assertEquals(1, predecessorCallbacks.get())
        assertEquals(1, successorCallbacks.get())
        assertThrows(IllegalStateException::class.java) { authority.close() }
        assertEquals(2, predecessorCallbacks.get())
    }

    @Test
    fun predecessorCleanupErrorStillRevokesSuccessorBeforeItPropagates() {
        val identity = identity(245)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 240)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        assertSame(
            identity,
            predecessor.consumeForUltraActivation {
                throw AssertionError("fatal predecessor cleanup")
            },
        )
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 241)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)
        val successorCallbacks = AtomicInteger()

        assertThrows(AssertionError::class.java) {
            handoff.commit(successor) { promoted ->
                checkNotNull(
                    promoted.consumeForUltraHandoffRenewal(predecessor) {
                        successorCallbacks.incrementAndGet()
                    },
                )
            }
        }

        assertFalse(predecessor.isLiveForUltraActivation())
        assertFalse(successor.isLiveForUltraActivation())
        assertEquals(1, successorCallbacks.get())
    }

    @Test
    fun closeCannotDropRetryOwnershipWhileCommitRevocationCallbackIsInFlight() {
        val identity = identity(246)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val predecessorChannel = Any()
        val predecessorAttempt = authority.begin(predecessorChannel, 250)
        val predecessor = predecessorAttempt.issue(predecessorChannel, identity)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val predecessorCallbacks = AtomicInteger()
        assertSame(
            identity,
            predecessor.consumeForUltraActivation {
                if (predecessorCallbacks.incrementAndGet() == 1) {
                    callbackEntered.countDown()
                    check(releaseCallback.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("delayed predecessor cleanup failed")
                }
            },
        )
        val successorChannel = Any()
        val handoff = authority.beginHandoff(predecessor, successorChannel, 251)
        val successor = handoff.attemptForExchange().issue(successorChannel, identity)
        val successorCallbacks = AtomicInteger()
        val commitFailure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val commit = executor.submit {
                runCatching {
                    handoff.commit(successor) { promoted ->
                        checkNotNull(
                            promoted.consumeForUltraHandoffRenewal(predecessor) {
                                successorCallbacks.incrementAndGet()
                            },
                        )
                    }
                }.onFailure(commitFailure::set)
            }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))

            assertThrows(IllegalStateException::class.java) { authority.close() }

            releaseCallback.countDown()
            commit.get(5, TimeUnit.SECONDS)
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
        }

        assertTrue(commitFailure.get() is IllegalStateException)
        assertEquals(1, predecessorCallbacks.get())
        assertEquals(1, successorCallbacks.get())

        authority.close()
        assertEquals(2, predecessorCallbacks.get())
    }

    @Test
    fun normalJvmApiHasNoBareIdentityReceiptConstructor() {
        val constructors = MfiAuthenticationReceipt::class.java.declaredConstructors

        assertTrue(constructors.filterNot { it.isSynthetic }.all { Modifier.isPrivate(it.modifiers) })
        assertFalse(
            constructors.any { constructor ->
                !constructor.isSynthetic &&
                    constructor.parameterTypes.contentEquals(
                        arrayOf(MfiAuthenticationIdentity::class.java),
                    )
            },
        )
        assertEquals("MfiAuthenticationReceipt(<redacted>)", receiptForRendering().toString())
        assertFalse(
            MfiAuthenticationReceipt::class.java.declaredMethods.any { method ->
                method.name == "consumeForUltraActivation" && method.parameterCount == 0
            },
        )
    }

    @Test
    fun handoffConstructionAndDiagnosticsDoNotExposeCertificateMaterial() {
        val identity = identity(31)
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val channel = Any()
        val attempt = authority.begin(channel, 130)
        val predecessor = attempt.issue(channel, identity)
        val handoff = authority.beginHandoff(predecessor, Any(), 131)

        assertTrue(
            MfiAuthenticationHandoff::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .all { Modifier.isPrivate(it.modifiers) },
        )
        assertEquals("MfiAuthenticationHandoff(<redacted>)", handoff.toString())
    }

    @Test
    fun aa05IsRejectedUntilTheSameExchangeSentAa03() {
        val exchange = Iap2MfiAuthenticationExchangeState()

        assertThrows(Iap2MfiAuthenticationException::class.java) {
            exchange.requireAuthenticationSucceeded()
        }
        exchange.requireCertificateRequest()
        exchange.certificateSent()
        assertThrows(Iap2MfiAuthenticationException::class.java) {
            exchange.requireAuthenticationSucceeded()
        }
        exchange.requireChallengeRequest()
        exchange.challengeResponseSent()
        exchange.requireAuthenticationSucceeded()
    }

    @Test
    fun duplicateCertificateOrChallengeRequestsAreRejected() {
        val exchange = Iap2MfiAuthenticationExchangeState()
        exchange.requireCertificateRequest()
        exchange.certificateSent()

        assertThrows(Iap2MfiAuthenticationException::class.java) {
            exchange.requireCertificateRequest()
        }
        exchange.requireChallengeRequest()
        exchange.challengeResponseSent()
        assertThrows(Iap2MfiAuthenticationException::class.java) {
            exchange.requireChallengeRequest()
        }
    }

    private fun receiptForRendering() =
        receipt(identity(3), 9)

    private fun receipt(
        identity: MfiAuthenticationIdentity,
        generation: Long,
    ): MfiAuthenticationReceipt {
        val authority = MfiAuthenticationReceiptAuthority.create(identity)
        val channelIdentity = Any()
        val attempt = authority.begin(channelIdentity, generation)
        return attempt.issue(channelIdentity, identity)
    }

    private fun identity(seed: Int): MfiAuthenticationIdentity =
        MfiAuthenticationIdentity.capture(authenticator(seed))

    private fun authenticator(seed: Int): MfiAuthenticator = object : MfiAuthenticator {
        override val certificateType = MfiCertificateType.MFI
        override fun protocolMajor(): Int = 1
        override fun readCertificate(maximumOutputLength: Int) =
            byteArrayOf(seed.toByte(), (seed + 1).toByte())

        override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
    }
}

