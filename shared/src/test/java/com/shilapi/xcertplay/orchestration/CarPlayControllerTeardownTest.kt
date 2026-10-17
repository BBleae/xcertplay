package com.shilapi.xcertplay.orchestration

import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayControllerTeardownTest {
    @Test
    fun preMuxOpenRequiresLiveDataPathAndBothExactGenerations() {
        assertTrue(
            canContinuePreMuxOpen(
                closed = false,
                dataPathPhaseMatches = true,
                authenticationGenerationMatches = true,
                mfiGenerationMatches = true,
            ),
        )
        assertFalse(
            canContinuePreMuxOpen(
                closed = true,
                dataPathPhaseMatches = true,
                authenticationGenerationMatches = true,
                mfiGenerationMatches = true,
            ),
        )
        assertFalse(
            canContinuePreMuxOpen(
                closed = false,
                dataPathPhaseMatches = false,
                authenticationGenerationMatches = true,
                mfiGenerationMatches = true,
            ),
        )
        assertFalse(
            canContinuePreMuxOpen(
                closed = false,
                dataPathPhaseMatches = true,
                authenticationGenerationMatches = false,
                mfiGenerationMatches = true,
            ),
        )
        assertFalse(
            canContinuePreMuxOpen(
                closed = false,
                dataPathPhaseMatches = true,
                authenticationGenerationMatches = true,
                mfiGenerationMatches = false,
            ),
        )
    }

    @Test
    fun wirelessHandoffCannotReportActiveBeforeBootstrapRetirementProof() {
        assertFalse(
            canReportWirelessHandoffActive(
                bootstrapRetired = false,
                closed = false,
                generationMatches = true,
            ),
        )
        assertTrue(
            canReportWirelessHandoffActive(
                bootstrapRetired = true,
                closed = false,
                generationMatches = true,
            ),
        )
        assertFalse(
            canReportWirelessHandoffActive(
                bootstrapRetired = true,
                closed = true,
                generationMatches = true,
            ),
        )
        assertFalse(
            canReportWirelessHandoffActive(
                bootstrapRetired = true,
                closed = false,
                generationMatches = false,
            ),
        )
    }

    @Test
    fun initialAuthorizationReceiptRequiresExactSessionAndCurrentOrConsumedLiveState() {
        assertTrue(
            canPublishInitialAuthorizationReceipt(
                closed = false,
                exactMfiSession = true,
                identityMatches = true,
                receiptIsCurrent = true,
                receiptIsLiveForAuthorization = false,
                receiptSlotIdle = true,
            ),
        )
        assertTrue(
            canPublishInitialAuthorizationReceipt(
                closed = false,
                exactMfiSession = true,
                identityMatches = true,
                receiptIsCurrent = false,
                receiptIsLiveForAuthorization = true,
                receiptSlotIdle = true,
            ),
        )
        assertFalse(
            canPublishInitialAuthorizationReceipt(
                closed = false,
                exactMfiSession = true,
                identityMatches = true,
                receiptIsCurrent = false,
                receiptIsLiveForAuthorization = false,
                receiptSlotIdle = true,
            ),
        )
        assertFalse(
            canPublishInitialAuthorizationReceipt(
                closed = false,
                exactMfiSession = false,
                identityMatches = true,
                receiptIsCurrent = true,
                receiptIsLiveForAuthorization = false,
                receiptSlotIdle = true,
            ),
        )
        assertFalse(
            canPublishInitialAuthorizationReceipt(
                closed = false,
                exactMfiSession = true,
                identityMatches = true,
                receiptIsCurrent = true,
                receiptIsLiveForAuthorization = false,
                receiptSlotIdle = false,
            ),
        )
    }

    @Test
    fun secondWirelessTunnelCandidateRetiresItselfWithoutReplacingExactOwner() {
        val gate = ControllerExactOwnerGate<Any>()
        val first = Any()
        val second = Any()
        val retired = mutableListOf<Any>()

        assertTrue(gate.publishOrRetire(first, { true }, retired::add))
        assertFalse(gate.publishOrRetire(second, { true }, retired::add))

        assertSame(first, gate.snapshot())
        assertEquals(1, retired.size)
        assertSame(second, retired.single())
    }

    @Test
    fun concurrentWirelessTunnelCandidatesPublishOnlyOneExactOwner() {
        val gate = ControllerExactOwnerGate<Any>()
        val candidates = List(16) { Any() }
        val ready = CountDownLatch(candidates.size)
        val release = CountDownLatch(1)
        val done = CountDownLatch(candidates.size)
        val winner = AtomicReference<Any?>()
        val retired = ConcurrentLinkedQueue<Any>()
        val failure = AtomicReference<Throwable?>()
        val workers = candidates.mapIndexed { index, candidate ->
            Thread(
                {
                    try {
                        ready.countDown()
                        check(release.await(2, TimeUnit.SECONDS))
                        if (gate.publishOrRetire(candidate, { true }, retired::add)) {
                            check(winner.compareAndSet(null, candidate))
                        }
                    } catch (thrown: Throwable) {
                        failure.compareAndSet(null, thrown)
                    } finally {
                        done.countDown()
                    }
                },
                "tunnel-candidate-$index",
            ).apply { start() }
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        workers.forEach { it.join(2_000) }

        assertEquals(null, failure.get())
        assertSame(winner.get(), gate.snapshot())
        assertEquals(candidates.size - 1, retired.size)
        assertTrue(retired.none { it === winner.get() })
    }

    @Test
    fun staleWirelessTunnelPublicationAndCloseCannotAffectSuccessor() {
        val gate = ControllerExactOwnerGate<Any>()
        val staleCandidate = Any()
        val first = Any()
        val successor = Any()
        val retired = mutableListOf<Any>()

        assertFalse(gate.publishOrRetire(staleCandidate, { false }, retired::add))
        assertEquals(null, gate.snapshot())
        assertSame(staleCandidate, retired.single())

        assertTrue(gate.publishOrRetire(first, { true }, retired::add))
        gate.retireIfOwned(first, retired::add)
        assertTrue(gate.publishOrRetire(successor, { true }, retired::add))

        // A finally block from the prior generation must be an identity-gated no-op.
        gate.retireIfOwned(first, retired::add)
        assertSame(successor, gate.snapshot())
        assertEquals(2, retired.size)
        assertSame(first, retired.last())
    }

    @Test
    fun failedWirelessTunnelRetirementRetainsExactOwnerAndRejectsSuccessorUntilRetry() {
        val gate = ControllerExactOwnerGate<Any>()
        val first = Any()
        val second = Any()
        val firstFailure = IOException("injected tunnel close failure")
        val retired = mutableListOf<Any>()
        var firstAttempts = 0
        assertTrue(gate.publishOrRetire(first, { true }, retired::add))

        val thrown = assertThrows(IOException::class.java) {
            gate.retireCurrent { exactOwner ->
                assertSame(first, exactOwner)
                firstAttempts++
                throw firstFailure
            }
        }

        assertSame(firstFailure, thrown)
        assertSame(first, gate.snapshot())
        assertFalse(gate.publishOrRetire(second, { true }, retired::add))
        assertSame(first, gate.snapshot())
        assertSame(second, retired.single())

        gate.retireCurrent { exactOwner ->
            assertSame(first, exactOwner)
            firstAttempts++
            retired += exactOwner
        }
        assertEquals(null, gate.snapshot())
        assertEquals(2, firstAttempts)
        assertSame(first, retired.last())
    }

    @Test
    fun mediaFailureRetainsTheExactStageAndBlocksLaterProvidersUntilRetry() {
        val operations = mutableListOf<String>()
        val exactMedia = Any()
        val observedMediaOwners = mutableListOf<Any>()
        var mediaAttempts = 0
        val barrier = ControllerTeardownBarrier(
            listOf(
                ControllerTeardownStage("transport") { operations += "transport" },
                ControllerTeardownStage("media runtime") {
                    observedMediaOwners += exactMedia
                    mediaAttempts++
                    operations += "media-$mediaAttempts"
                    if (mediaAttempts == 1) throw IOException("injected retirement failure")
                },
                ControllerTeardownStage("provider") { operations += "provider" },
            ),
        )

        assertTrue(barrier.tryBegin())
        val first = barrier.runAttempt() as ControllerTeardownAttempt.RetryableFailure

        assertEquals("media runtime", first.stageName)
        assertEquals("media runtime", barrier.pendingStageName())
        assertFalse(barrier.isInFlight())
        assertFalse(barrier.awaitClosed(0))
        assertEquals(listOf("transport", "media-1"), operations)

        assertTrue(barrier.tryBegin())
        assertSame(ControllerTeardownAttempt.Complete, barrier.runAttempt())
        assertTrue(barrier.awaitClosed(0))
        assertEquals(listOf("transport", "media-1", "media-2", "provider"), operations)
        assertEquals(2, observedMediaOwners.size)
        assertTrue(observedMediaOwners.all { it === exactMedia })
    }

    @Test
    fun providerFailureRetriesOnlyThatProviderAfterMediaHasRetired() {
        var mediaCalls = 0
        var providerCalls = 0
        val barrier = ControllerTeardownBarrier(
            listOf(
                ControllerTeardownStage("media runtime") { mediaCalls++ },
                ControllerTeardownStage("vehicle provider") {
                    providerCalls++
                    if (providerCalls == 1) throw IOException("injected provider failure")
                },
            ),
        )

        assertTrue(barrier.tryBegin())
        val first = barrier.runAttempt() as ControllerTeardownAttempt.RetryableFailure
        assertEquals("vehicle provider", first.stageName)
        assertEquals(1, mediaCalls)
        assertEquals(1, providerCalls)
        assertFalse(barrier.awaitClosed(0))

        assertTrue(barrier.tryBegin())
        assertSame(ControllerTeardownAttempt.Complete, barrier.runAttempt())
        assertEquals(1, mediaCalls)
        assertEquals(2, providerCalls)
        assertTrue(barrier.awaitClosed(0))
    }

    @Test
    fun authorizedControllerTeardownRunsRealCallbacksInOwnershipOrder() {
        val operations = mutableListOf<String>()
        val barrier = ControllerTeardownBarrier(
            controllerCoreTeardownStages(
                sealAuthorizationAdmission = { operations += "seal" },
                retireTransportsAndWorkers = { operations += "airplay-and-workers" },
                retireMedia = { operations += "media-children" },
                retireAuthorizationRoot = { operations += "authorization-root" },
                retireDeferredMfi = { operations += "mfi-authority" },
            ),
        )

        assertTrue(barrier.tryBegin())
        assertSame(ControllerTeardownAttempt.Complete, barrier.runAttempt())

        assertEquals(
            listOf(
                "seal",
                "airplay-and-workers",
                "media-children",
                "authorization-root",
                "mfi-authority",
            ),
            operations,
        )
    }

    @Test
    fun updateDataRootDrainsAfterMediaBeforeAuthorizationAndRetriesTheExactStage() {
        val operations = mutableListOf<String>()
        val exactRoot = Any()
        val seenRoots = mutableListOf<Any>()
        var closes = 0
        val barrier = ControllerTeardownBarrier(
            controllerCoreTeardownStages(
                sealAuthorizationAdmission = { operations += "seal" },
                retireTransportsAndWorkers = { operations += "airplay-and-workers" },
                retireMedia = { operations += "media-children" },
                retireUpdateDataQuarantineRoot = {
                    seenRoots += exactRoot
                    closeAndDrainUpdateDataQuarantineRoot(
                        closeRoot = {
                            closes++
                            operations += "quarantine-close-$closes"
                            if (closes == 1) throw IOException("injected close failure")
                        },
                        retryFailedCleanups = { operations += "quarantine-retry-$closes" },
                        awaitDrained = {
                            assertEquals(50L, it)
                            operations += "quarantine-drain-$closes"
                            true
                        },
                        timeoutMillis = 50,
                    )
                },
                retireAuthorizationRoot = { operations += "authorization-root" },
                retireDeferredMfi = { operations += "mfi-authority" },
            ),
        )

        assertTrue(barrier.tryBegin())
        val first = barrier.runAttempt() as ControllerTeardownAttempt.RetryableFailure
        assertEquals("UpdateData quarantine root", first.stageName)
        assertEquals(
            listOf(
                "seal", "airplay-and-workers", "media-children", "quarantine-close-1",
                "quarantine-retry-1", "quarantine-drain-1",
            ),
            operations,
        )
        assertFalse(barrier.awaitClosed(0))

        assertTrue(barrier.tryBegin())
        assertSame(ControllerTeardownAttempt.Complete, barrier.runAttempt())
        assertEquals(
            listOf(
                "seal", "airplay-and-workers", "media-children", "quarantine-close-1",
                "quarantine-retry-1", "quarantine-drain-1", "quarantine-close-2",
                "quarantine-retry-2", "quarantine-drain-2", "authorization-root",
                "mfi-authority",
            ),
            operations,
        )
        assertEquals(2, seenRoots.size)
        assertTrue(seenRoots.all { it === exactRoot })
    }

    @Test
    fun undrainedUpdateDataRootNeverAdvancesToAuthorizationRetirement() {
        val operations = mutableListOf<String>()
        val barrier = ControllerTeardownBarrier(
            controllerCoreTeardownStages(
                sealAuthorizationAdmission = { operations += "seal" },
                retireTransportsAndWorkers = { operations += "airplay" },
                retireMedia = { operations += "media" },
                retireUpdateDataQuarantineRoot = {
                    closeAndDrainUpdateDataQuarantineRoot(
                        closeRoot = { operations += "quarantine-close" },
                        retryFailedCleanups = { operations += "quarantine-retry" },
                        awaitDrained = { operations += "quarantine-await"; false },
                        timeoutMillis = 0,
                    )
                },
                retireAuthorizationRoot = { operations += "authorization" },
                retireDeferredMfi = { operations += "mfi" },
            ),
        )

        assertTrue(barrier.tryBegin())
        val result = barrier.runAttempt() as ControllerTeardownAttempt.RetryableFailure
        assertEquals("UpdateData quarantine root", result.stageName)
        assertEquals(
            listOf("seal", "airplay", "media", "quarantine-close", "quarantine-retry", "quarantine-await"),
            operations,
        )
        assertFalse(barrier.awaitClosed(0))
    }

    @Test
    fun authorizationCloseFailureRetriesSameRootBeforeMfiCanRetire() {
        val operations = mutableListOf<String>()
        val exactAuthorizationOwner = Any()
        var authorizationAttempts = 0
        val observedAuthorizationOwners = mutableListOf<Any>()
        val barrier = ControllerTeardownBarrier(
            controllerCoreTeardownStages(
                sealAuthorizationAdmission = { operations += "seal" },
                retireTransportsAndWorkers = { operations += "airplay-and-workers" },
                retireMedia = { operations += "media-children" },
                retireAuthorizationRoot = {
                    authorizationAttempts++
                    observedAuthorizationOwners += exactAuthorizationOwner
                    operations += "authorization-$authorizationAttempts"
                    if (authorizationAttempts == 1) {
                        throw IOException("injected authorization close failure")
                    }
                },
                retireDeferredMfi = { operations += "mfi-authority" },
            ),
        )

        assertTrue(barrier.tryBegin())
        val first = barrier.runAttempt() as ControllerTeardownAttempt.RetryableFailure
        assertEquals("CarPlay Ultra authorization root", first.stageName)
        assertEquals(
            listOf("seal", "airplay-and-workers", "media-children", "authorization-1"),
            operations,
        )

        assertTrue(barrier.tryBegin())
        assertSame(ControllerTeardownAttempt.Complete, barrier.runAttempt())
        assertEquals(
            listOf(
                "seal",
                "airplay-and-workers",
                "media-children",
                "authorization-1",
                "authorization-2",
                "mfi-authority",
            ),
            operations,
        )
        assertEquals(2, observedAuthorizationOwners.size)
        assertTrue(observedAuthorizationOwners.all { it === exactAuthorizationOwner })
    }

    @Test
    fun ambiguousChildNoOpAfterFailureCannotBecomeFalseSuccess() {
        val journal = ControllerAmbiguousRetirementJournal()
        val exactResource = Any()
        val firstFailure = IOException("close marked itself closed before failing")
        var childCalls = 0
        val barrier = ControllerTeardownBarrier(
            listOf(
                ControllerTeardownStage("ambiguous transport") {
                    journal.retire(exactResource) {
                        childCalls++
                        if (childCalls == 1) throw firstFailure
                        // Models a child whose second close is an immediate no-op.
                    }
                },
            ),
        )

        assertTrue(barrier.tryBegin())
        val first = barrier.runAttempt() as ControllerTeardownAttempt.RetryableFailure
        assertSame(firstFailure, first.cause)
        assertFalse(barrier.awaitClosed(0))

        assertTrue(barrier.tryBegin())
        val second = barrier.runAttempt() as ControllerTeardownAttempt.RetryableFailure
        assertSame(firstFailure, second.cause)
        assertEquals(2, childCalls)
        assertEquals("ambiguous transport", barrier.pendingStageName())
        assertFalse(barrier.awaitClosed(0))
    }

    @Test
    fun concurrentAmbiguousRetirementsCannotOvertakeFailurePublication() {
        val journal = ControllerAmbiguousRetirementJournal()
        val resource = Any()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstFailure = IOException("first close failed after marking closed")
        val calls = AtomicInteger()
        val firstResult = AtomicReference<Throwable?>()
        val secondResult = AtomicReference<Throwable?>()
        val closeAction: (Any) -> Unit = {
            val call = calls.incrementAndGet()
            if (call == 1) {
                firstEntered.countDown()
                check(releaseFirst.await(2, TimeUnit.SECONDS))
                throw firstFailure
            }
            // The raced retry observes the child's closed flag and returns without doing work.
        }
        val first = Thread {
            try {
                journal.retire(resource, closeAction)
            } catch (failure: Throwable) {
                firstResult.set(failure)
            }
        }
        val second = Thread {
            try {
                journal.retire(resource, closeAction)
            } catch (failure: Throwable) {
                secondResult.set(failure)
            }
        }

        first.start()
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
        second.start()
        releaseFirst.countDown()
        first.join(2_000)
        second.join(2_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertSame(firstFailure, firstResult.get())
        assertSame(firstFailure, secondResult.get())
        assertEquals(2, calls.get())
    }

    @Test
    fun concurrentCloseRequestCannotRunTheSameResourceTwice() {
        val attempts = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val barrier = ControllerTeardownBarrier(
            listOf(
                ControllerTeardownStage("resource") {
                    attempts.incrementAndGet()
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                },
            ),
        )
        assertTrue(barrier.tryBegin())
        val worker = Thread { barrier.runAttempt() }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        assertFalse(barrier.tryBegin())
        assertEquals(1, attempts.get())
        assertFalse(barrier.awaitClosed(1))

        release.countDown()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertTrue(barrier.awaitClosed(0))
        assertEquals(1, attempts.get())
    }

    @Test
    fun awaitClosedRequiresWorkersMediaAndProvidersToAllFinish() {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val operations = mutableListOf<String>()
        val barrier = ControllerTeardownBarrier(
            listOf(
                ControllerTeardownStage("workers") {
                    workerEntered.countDown()
                    check(releaseWorker.await(2, TimeUnit.SECONDS))
                    operations += "workers"
                },
                ControllerTeardownStage("media") { operations += "media" },
                ControllerTeardownStage("provider") { operations += "provider" },
            ),
        )
        assertTrue(barrier.tryBegin())
        val teardown = Thread { barrier.runAttempt() }.apply { start() }
        assertTrue(workerEntered.await(2, TimeUnit.SECONDS))

        assertFalse(barrier.awaitClosed(1))
        assertTrue(operations.isEmpty())

        releaseWorker.countDown()
        teardown.join(2_000)
        assertFalse(teardown.isAlive)
        assertTrue(barrier.awaitClosed(0))
        assertEquals(listOf("workers", "media", "provider"), operations)
    }

    @Test
    fun errorPropagatesAndLeavesTheClaimFailClosed() {
        val fatal = AssertionError("fatal teardown invariant")
        val barrier = ControllerTeardownBarrier(
            listOf(ControllerTeardownStage("fatal") { throw fatal }),
        )
        assertTrue(barrier.tryBegin())

        val thrown = assertThrows(AssertionError::class.java) { barrier.runAttempt() }

        assertSame(fatal, thrown)
        assertTrue(barrier.isInFlight())
        assertEquals("fatal", barrier.pendingStageName())
        assertFalse(barrier.tryBegin())
        assertFalse(barrier.awaitClosed(0))
    }

    @Test
    fun threadLaunchFailureReleasesOnlyTheAttemptForAnExactRetry() {
        var calls = 0
        val barrier = ControllerTeardownBarrier(
            listOf(ControllerTeardownStage("resource") { calls++ }),
        )
        assertTrue(barrier.tryBegin())

        barrier.launchFailed()

        assertFalse(barrier.awaitClosed(0))
        assertEquals("resource", barrier.pendingStageName())
        assertTrue(barrier.tryBegin())
        assertSame(ControllerTeardownAttempt.Complete, barrier.runAttempt())
        assertEquals(1, calls)
    }

    @Test
    fun preMuxSessionRemainsOwnedUntilMuxAcceptanceReturnsSuccessfully() {
        val session = Any()
        val mux = Any()
        val transferEntered = CountDownLatch(1)
        val releaseTransfer = CountDownLatch(1)
        val transferResult = AtomicReference<Any?>()
        val transferFailure = AtomicReference<Throwable?>()
        val retired = mutableListOf<Any>()
        val journal = ControllerExactHandoffJournal<Any>(1, "test session") { retired += it }
        val reservation = checkNotNull(journal.reserve())
        journal.bind(reservation, session)

        val transfer = Thread {
            try {
                transferResult.set(
                    journal.transfer(session) { exactSession ->
                        assertSame(session, exactSession)
                        transferEntered.countDown()
                        check(releaseTransfer.await(2, TimeUnit.SECONDS))
                        mux
                    },
                )
            } catch (failure: Throwable) {
                transferFailure.set(failure)
            }
        }
        transfer.start()
        assertTrue(transferEntered.await(2, TimeUnit.SECONDS))

        assertThrows(IOException::class.java) { journal.retireAll() }
        assertEquals(1, journal.pendingOwnerCount())
        assertTrue(retired.isEmpty())

        releaseTransfer.countDown()
        transfer.join(2_000)
        assertFalse(transfer.isAlive)
        assertEquals(null, transferFailure.get())
        assertSame(mux, transferResult.get())
        assertEquals(0, journal.pendingOwnerCount())
        journal.retireAll()
        assertTrue(retired.isEmpty())
    }

    @Test
    fun failedMuxAcceptanceRestoresExactSessionForFinalRetirement() {
        val session = Any()
        val muxFailure = IOException("injected USBMUX handshake failure")
        val retired = mutableListOf<Any>()
        val journal = ControllerExactHandoffJournal<Any>(1, "test session") { retired += it }
        journal.bind(checkNotNull(journal.reserve()), session)

        val thrown = assertThrows(IOException::class.java) {
            journal.transfer(session) { throw muxFailure }
        }

        assertSame(muxFailure, thrown)
        assertEquals(1, journal.pendingOwnerCount())
        journal.retireAll()
        assertEquals(0, journal.pendingOwnerCount())
        assertEquals(1, retired.size)
        assertSame(session, retired.single())
    }

    @Test
    fun directPreMuxCloseFailureCannotBeLostToALaterChildNoOp() {
        val session = Any()
        val firstFailure = IOException("native close failed after child marked itself closed")
        val exactCloseOwners = mutableListOf<Any>()
        var closeCalls = 0
        val ambiguous = ControllerAmbiguousRetirementJournal()
        val journal = ControllerExactHandoffJournal<Any>(1, "test session") { exactSession ->
            ambiguous.retire(exactSession) { observedSession ->
                exactCloseOwners += observedSession
                closeCalls++
                if (closeCalls == 1) throw firstFailure
                // Models Iap2UsbSession.close(): later calls can be a no-op after its closed flag.
            }
        }
        journal.bind(checkNotNull(journal.reserve()), session)

        assertThrows(IOException::class.java) { journal.retireAll() }
        assertEquals(1, journal.pendingOwnerCount())
        assertThrows(IOException::class.java) { journal.retireAll() }

        assertEquals(1, journal.pendingOwnerCount())
        assertEquals(2, closeCalls)
        assertEquals(2, exactCloseOwners.size)
        assertTrue(exactCloseOwners.all { it === session })
    }

    @Test
    fun nestedMuxCloseFailureIsRetainedBeforeControllerObservesAChildNoOp() {
        val session = Any()
        val nestedFailure = IOException("nested USBMUX close failed after marking the pipe closed")
        var controllerCloseCalls = 0
        val ambiguous = ControllerAmbiguousRetirementJournal()
        val journal = ControllerExactHandoffJournal<Any>(1, "test session") { exactSession ->
            ambiguous.retire(exactSession) {
                controllerCloseCalls++
                // The nested owner already set the child's closed flag, so this retry is a no-op.
            }
        }
        journal.bind(checkNotNull(journal.reserve()), session)
        ambiguous.retainUnprovenFailure(session, { controllerCloseCalls++ }, nestedFailure)

        val thrown = assertThrows(IOException::class.java) { journal.retireAll() }

        assertSame(nestedFailure, thrown.cause)
        assertEquals(1, controllerCloseCalls)
        assertEquals(1, journal.pendingOwnerCount())
    }

    @Test
    fun postHandshakeSlotConflictCannotOverwriteOrLoseMuxCandidate() {
        val usbSession = Any()
        val muxCandidate = Any()
        val existingMux = Any()
        var controllerMux: Any? = existingMux
        val retiredMuxes = mutableListOf<Any>()
        val sessions = ControllerExactHandoffJournal<Any>(1, "test session") { }
        val muxes = ControllerExactHandoffJournal<Any>(1, "test mux") { retiredMuxes += it }
        sessions.bind(checkNotNull(sessions.reserve()), usbSession)
        val muxReservation = checkNotNull(muxes.reserve())

        val opened = sessions.transfer(usbSession) {
            muxes.bind(muxReservation, muxCandidate)
            muxCandidate
        }
        val conflict = assertThrows(IOException::class.java) {
            muxes.transfer(opened) { exactMux ->
                if (controllerMux != null) throw IOException("slot already owns a mux")
                controllerMux = exactMux
            }
        }

        assertEquals("slot already owns a mux", conflict.message)
        assertSame(existingMux, controllerMux)
        assertEquals(0, sessions.pendingOwnerCount())
        assertEquals(1, muxes.pendingOwnerCount())
        muxes.retireAll()
        assertEquals(0, muxes.pendingOwnerCount())
        assertSame(muxCandidate, retiredMuxes.single())
    }

    @Test
    fun preMuxReservationsBoundAsyncOpensBeforeAnySessionExists() {
        val journal = ControllerExactHandoffJournal<Any>(1, "test session") { }
        val first = checkNotNull(journal.reserve())

        assertEquals(null, journal.reserve())
        assertEquals(1, journal.reservationCount())
        assertTrue(journal.cancel(first))
        assertEquals(0, journal.reservationCount())

        val second = checkNotNull(journal.reserve())
        val session = Any()
        journal.bind(second, session)
        assertEquals(0, journal.reservationCount())
        assertEquals(1, journal.pendingOwnerCount())
        journal.retire(session)
        assertEquals(0, journal.pendingOwnerCount())
    }

    @Test
    fun wiredRestartStartsSuccessorOnlyAfterPriorAttemptAndLateSweepFinish() {
        val priorAttemptEntered = CountDownLatch(1)
        val releasePriorAttempt = CountDownLatch(1)
        val operations = mutableListOf<String>()
        val failure = AtomicReference<Throwable?>()
        val restart = Thread {
            try {
                runWiredRestartBarrier(
                    retirePublished = { operations += "retire-published" },
                    awaitPriorAttempt = {
                        priorAttemptEntered.countDown()
                        check(releasePriorAttempt.await(2, TimeUnit.SECONDS))
                        operations += "prior-finished"
                    },
                    retireLateOwners = { operations += "retire-late" },
                    startSuccessor = { operations += "start-successor" },
                )
            } catch (thrown: Throwable) {
                failure.set(thrown)
            }
        }
        restart.start()
        assertTrue(priorAttemptEntered.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("retire-published"), operations)

        releasePriorAttempt.countDown()
        restart.join(2_000)

        assertFalse(restart.isAlive)
        assertEquals(null, failure.get())
        assertEquals(
            listOf("retire-published", "prior-finished", "retire-late", "start-successor"),
            operations,
        )
    }

    @Test
    fun wiredRestartCloseFailureStillDrainsAndSweepsButNeverStartsSuccessor() {
        val exactMux = Any()
        val closeFailure = IOException("injected exact mux close failure")
        val operations = mutableListOf<String>()
        var startCalls = 0
        val journal = ControllerExactHandoffJournal<Any>(1, "test mux") { owner ->
            assertSame(exactMux, owner)
            throw closeFailure
        }
        journal.bind(checkNotNull(journal.reserve()), exactMux)

        val thrown = assertThrows(IOException::class.java) {
            runWiredRestartBarrier(
                retirePublished = {
                    operations += "retire-published"
                    journal.retireAll()
                },
                awaitPriorAttempt = { operations += "prior-finished" },
                retireLateOwners = {
                    operations += "retire-late"
                    journal.retireAll()
                },
                startSuccessor = { startCalls++ },
            )
        }

        assertEquals("published wired stack retirement failed", thrown.message)
        assertEquals(listOf("retire-published", "prior-finished", "retire-late"), operations)
        assertEquals(0, startCalls)
        assertEquals(1, journal.pendingOwnerCount())
    }

    @Test
    fun concurrentWiredReconnectsSerializeAndOnlyNewestStarts() {
        val gate = ControllerWiredRestartGate()
        val firstGeneration = gate.nextGeneration()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val operations = mutableListOf<String>()
        val first = Thread {
            gate.runIfCurrent(firstGeneration) { isCurrent ->
                operations += "first-cleanup"
                firstEntered.countDown()
                check(releaseFirst.await(2, TimeUnit.SECONDS))
                if (isCurrent()) operations += "first-start"
            }
        }
        first.start()
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

        val secondGeneration = gate.nextGeneration()
        val second = Thread {
            gate.runIfCurrent(secondGeneration) { isCurrent ->
                operations += "second-cleanup"
                if (isCurrent()) operations += "second-start"
            }
        }
        second.start()
        releaseFirst.countDown()
        first.join(2_000)
        second.join(2_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals(listOf("first-cleanup", "second-cleanup", "second-start"), operations)
        assertFalse(
            gate.runIfCurrent(firstGeneration) {
                operations += "stale-cleanup"
            },
        )
        assertFalse("stale-cleanup" in operations)
    }
}
