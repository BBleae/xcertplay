package com.shilapi.xcertplay.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySurfaceHandoffProofTest {
    @Test
    fun legacySurfaceRegistrationAbaRejectsRollbackEvenWithNullSelectedHandle() {
        val generations = SurfaceRouteGenerationTracker()
        val type = 111
        val oldSurface = Any()
        val oldSource = Any()
        val selected: Any? = null
        generations.advance(type) // A installed.
        val handoffGeneration = generations.advance(type) // A retired and reserved.

        assertTrue(matchesLegacyHandoffRestoreRoute<Any, Any>(
            currentLegacySurface = null,
            currentLegacySource = null,
            currentSelectedHandle = selected,
            capturedSelectedHandle = selected,
            selectedExactSurface = null,
            expectedExactSurface = null,
            processOwner = oldSource,
            expectedSource = oldSource,
            currentSurfaceGeneration = generations.current(type),
            handoffSurfaceGeneration = handoffGeneration,
        ))

        generations.advance(type) // B installed on this type.
        generations.advance(type) // B cleared; the visible state is empty again.
        assertFalse(matchesLegacyHandoffRestoreRoute<Any, Any>(
            currentLegacySurface = null,
            currentLegacySource = null,
            currentSelectedHandle = selected,
            capturedSelectedHandle = selected,
            selectedExactSurface = null,
            expectedExactSurface = null,
            processOwner = oldSource,
            expectedSource = oldSource,
            currentSurfaceGeneration = generations.current(type),
            handoffSurfaceGeneration = handoffGeneration,
        ))
        assertFalse(matchesLegacySurfaceRegistration(null, null, oldSurface to oldSource))
    }

    @Test
    fun unchangedExactOverrideBAllowsFallbackARestoreButChangedBDoesNot() {
        val handle = Any()
        val exactSurfaceB = Any()
        val sourceA = Any()
        val sourceB = Any()
        val outputB = Any()
        val tokenB = Any()

        assertTrue(matchesLegacyHandoffRestoreRoute(
            currentLegacySurface = null,
            currentLegacySource = null,
            currentSelectedHandle = handle,
            capturedSelectedHandle = handle,
            selectedExactSurface = exactSurfaceB,
            expectedExactSurface = exactSurfaceB,
            processOwner = sourceA,
            expectedSource = sourceA,
            currentSurfaceGeneration = 3L,
            handoffSurfaceGeneration = 3L,
        ))
        assertTrue(matchesExactOverrideAttachment(
            exactSurfaceB, sourceB, outputB, sourceB, tokenB,
            exactSurfaceB, sourceB, outputB, tokenB,
        ))
        assertFalse(matchesExactOverrideAttachment(
            exactSurfaceB, Any(), outputB, sourceB, tokenB,
            exactSurfaceB, sourceB, outputB, tokenB,
        ))
        assertFalse(matchesExactOverrideAttachment(
            exactSurfaceB, sourceB, outputB, sourceB, Any(),
            exactSurfaceB, sourceB, outputB, tokenB,
        ))
        assertFalse(matchesLegacyHandoffRestoreRoute(
            currentLegacySurface = null,
            currentLegacySource = null,
            currentSelectedHandle = handle,
            capturedSelectedHandle = handle,
            selectedExactSurface = Any(),
            expectedExactSurface = exactSurfaceB,
            processOwner = sourceA,
            expectedSource = sourceA,
            currentSurfaceGeneration = 3L,
            handoffSurfaceGeneration = 3L,
        ))
    }

    @Test
    fun decoderPublishFailureAfterSinkTransferDoesNotStrandExternalReservation() {
        val registry = IdentityInstanceRegistry<Any>()
        val surface = Any()
        val source = registry.reserve(surface)
        val publicationFailure = IllegalStateException("injected decoder publication failure")
        var observedFailure: Throwable? = null
        val handoff = SurfaceHandoffReservationLifecycle(
            RetryableSurfaceReservationRelease(
                releaseAction = { registry.release(surface, source) },
                heldAction = { registry.owner(surface) === source },
            ),
            restoreAction = {
                completeCommittedLegacyHandoffRestore(
                    publishDecoder = { throw publicationFailure },
                    onPublicationFailure = { observedFailure = it },
                )
            },
        )

        assertEquals(AndroidLegacySurfaceRestoreResult.Restored, handoff.restoreBeforeExternalUse(1_000))
        assertSame(publicationFailure, observedFailure)
        assertFalse(handoff.isHeld)
        handoff.close() // No-op: sink has the registration, even if its decoder is not ready.
        assertSame(source, registry.owner(surface))
        // A later route change/close can perform the sink-owned cleanup without a stuck token.
        assertTrue(registry.release(surface, source))
        assertEquals(null, registry.owner(surface))
    }

    @Test
    fun pendingLegacyAttachCannotReleaseAHandedOffOrReplacedGeneration() {
        val oldSurface = Any()
        val oldSource = Any()
        val oldRegistration = oldSurface to oldSource
        val newSourceForSameSurface = Any()

        assertTrue(matchesLegacySurfaceRegistration(oldSurface, oldSource, oldRegistration))
        assertFalse(matchesLegacySurfaceRegistration(null, null, oldRegistration))
        assertFalse(matchesLegacySurfaceRegistration(oldSurface, newSourceForSameSurface, oldRegistration))
        assertFalse(matchesLegacySurfaceRegistration(Any(), oldSource, oldRegistration))
        assertFalse(matchesLegacySurfaceRegistration(oldSurface, oldSource, null))
        assertTrue(matchesLegacySurfaceRegistration<Any>(null, null, null))
    }

    @Test
    fun captureRejectsOtherSinkSurfaceSourceSessionAndDecoderGeneration() {
        val sink = Any()
        val surface = Any()
        val source = Any()
        val selected = Any()
        val decoderGeneration = Any()
        val proof = LegacySurfaceBindingProof.issue(
            sink, 110, surface, source, selected, 7L, 11L, decoderGeneration,
        )

        assertTrue(proof.matches(sink, 110, surface))
        assertTrue(proof.isCurrent(sink, 110, surface, source, selected, 7L, 11L, decoderGeneration))
        assertFalse(proof.matches(Any(), 110, surface))
        assertFalse(proof.matches(sink, 111, surface))
        assertFalse(proof.matches(sink, 110, Any()))
        assertFalse(proof.isCurrent(sink, 110, surface, Any(), selected, 7L, 11L, decoderGeneration))
        assertFalse(proof.isCurrent(sink, 110, surface, source, Any(), 7L, 11L, decoderGeneration))
        assertFalse(proof.isCurrent(sink, 110, surface, source, selected, 8L, 11L, decoderGeneration))
        assertFalse(proof.isCurrent(sink, 110, surface, source, selected, 7L, 12L, decoderGeneration))
        assertFalse(proof.isCurrent(sink, 110, surface, source, selected, 7L, 11L, Any()))
    }

    @Test
    fun routeGenerationRejectsSelectedHandleAbaAcrossSessions() {
        val routes = LegacyVideoHandleRoutes()
        val initial = routes.generation(110)
        val first = com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle(
            com.shilapi.xcertplay.airplay.AirPlayStreamIdentity(110, "first", null),
            com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch(),
        )

        routes.observe(first)
        routes.setActive(first, true)
        routes.setActive(first, false)

        assertTrue(routes.generation(110) > initial)
        assertEquals(null, routes.selected(110))
        // Even though selected is null again, a capture from before this session is stale.
        assertFalse(routes.generation(110) == initial)
    }

    @Test
    fun timeoutCannotBecomeAReservationUntilDecoderAcknowledgesDetach() {
        val registry = IdentityInstanceRegistry<Any>()
        val surface = Any()
        val source = registry.reserve(surface)
        val state = SurfaceLeaseStateMachine<Any>()
        val installed = state.attach(surface)
        state.acknowledge(installed.request)

        val detach = checkNotNull(state.retire(installed.token).request)
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.TIMED_OUT,
            state.awaitRetirement(installed.token, 0),
        )
        assertSame(source, registry.owner(surface))
        assertThrows(IllegalArgumentException::class.java) { registry.reserve(surface) }

        state.acknowledge(detach)
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.RETIRED,
            state.awaitRetirement(installed.token, 0),
        )
        // The exact registry owner remains held while the decoder binding is removed. No other
        // sink can attach the Surface in the gap before the external renderer commits.
        assertSame(source, registry.owner(surface))
        assertThrows(IllegalArgumentException::class.java) { registry.reserve(surface) }
        val handoff = RetryableSurfaceReservationRelease(
            releaseAction = { registry.release(surface, source) },
        )
        handoff.close()
        assertFalse(handoff.isHeld)
        registry.reserve(surface)
    }

    @Test
    fun concurrentReattachCannotWinTheRetirementToHandoffGap() {
        val registry = IdentityInstanceRegistry<Any>()
        val surface = Any()
        val source = registry.reserve(surface)
        val decoder = SurfaceLeaseStateMachine<Any>()
        val installed = decoder.attach(surface)
        decoder.acknowledge(installed.request)
        val detach = checkNotNull(decoder.retire(installed.token).request)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(16)
        val reattachSucceeded = AtomicInteger()
        val executor = Executors.newFixedThreadPool(16)
        repeat(16) {
            executor.execute {
                start.await()
                runCatching { registry.reserve(surface) }
                    .onSuccess { reattachSucceeded.incrementAndGet() }
                finished.countDown()
            }
        }

        decoder.acknowledge(detach)
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.RETIRED,
            decoder.awaitRetirement(installed.token, 0),
        )
        val handoff = RetryableSurfaceReservationRelease(
            releaseAction = { registry.release(surface, source) },
        )
        start.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(0, reattachSucceeded.get())
        assertSame(source, registry.owner(surface))
        handoff.close()
        assertFalse(handoff.isHeld)
        executor.shutdownNow()
    }

    @Test
    fun failedExactReservationReleaseCanBeRetriedWithoutUnlockingAnotherOwner() {
        val registry = IdentityInstanceRegistry<Any>()
        val surface = Any()
        val originalOwner = registry.reserve(surface)
        var attempts = 0
        val handoff = RetryableSurfaceReservationRelease(
            releaseAction = {
                attempts++
                attempts > 1 && registry.release(surface, originalOwner)
            },
        )

        assertThrows(IllegalStateException::class.java) { handoff.close() }
        assertTrue(handoff.isHeld)
        assertSame(originalOwner, registry.owner(surface))
        assertThrows(IllegalArgumentException::class.java) { registry.reserve(surface) }

        handoff.close()
        handoff.close()
        assertEquals(2, attempts)
        assertFalse(handoff.isHeld)
        val newOwner = registry.reserve(surface)
        assertFalse(registry.release(surface, originalOwner))
        assertSame(newOwner, registry.owner(surface))
    }

    @Test
    fun lostReservationCannotClaimToBeHeldOrReleaseItsNewerOwner() {
        val registry = IdentityInstanceRegistry<Any>()
        val surface = Any()
        val originalOwner = registry.reserve(surface)
        val handoff = RetryableSurfaceReservationRelease(
            releaseAction = { registry.release(surface, originalOwner) },
            heldAction = { registry.owner(surface) === originalOwner },
        )
        assertTrue(handoff.isHeld)

        assertTrue(registry.release(surface, originalOwner))
        val newerOwner = registry.reserve(surface)
        assertFalse(handoff.isHeld)
        assertThrows(IllegalStateException::class.java) { handoff.close() }
        assertSame(newerOwner, registry.owner(surface))
    }

    @Test
    fun cleanSecondOutputDenialRestoresFirstWithoutReleasingItsProcessReservation() {
        val registry = IdentityInstanceRegistry<Any>()
        val firstSurface = Any()
        val secondSurface = Any()
        val firstSource = registry.reserve(firstSurface)
        val secondSource = registry.reserve(secondSurface)
        val output = SurfaceLeaseStateMachine<Any>()
        val firstAttachment = output.attach(firstSurface)
        output.acknowledge(firstAttachment.request)
        val detach = checkNotNull(output.retire(firstAttachment.token).request)
        output.acknowledge(detach)
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.RETIRED,
            output.awaitRetirement(firstAttachment.token, 0),
        )

        val firstHandoff = SurfaceHandoffReservationLifecycle(
            RetryableSurfaceReservationRelease(
                releaseAction = { registry.release(firstSurface, firstSource) },
                heldAction = { registry.owner(firstSurface) === firstSource },
            ),
            restoreAction = {
                val restored = output.attach(firstSurface)
                output.acknowledge(restored.request)
                AndroidLegacySurfaceRestoreResult.Restored
            },
        )
        // The second output denies handoff. No EGL use has begun on the first output.
        assertSame(secondSource, registry.owner(secondSurface))
        assertEquals(
            AndroidLegacySurfaceRestoreResult.Restored,
            firstHandoff.restoreBeforeExternalUse(1_000),
        )
        assertFalse(firstHandoff.isHeld)
        firstHandoff.close() // Transferred to the old sink, not globally unlocked.
        assertSame(firstSource, registry.owner(firstSurface))
        assertThrows(IllegalArgumentException::class.java) { registry.reserve(firstSurface) }
        assertSame(firstSurface, output.currentRequest().surface)
    }

    @Test
    fun uncertainRestoreRetainsTokenAndRetriesWithoutUnlockingSurface() {
        val registry = IdentityInstanceRegistry<Any>()
        val surface = Any()
        val source = registry.reserve(surface)
        var restoreAttempts = 0
        val handoff = SurfaceHandoffReservationLifecycle(
            RetryableSurfaceReservationRelease(
                releaseAction = { registry.release(surface, source) },
                heldAction = { registry.owner(surface) === source },
            ),
            restoreAction = {
                restoreAttempts++
                if (restoreAttempts == 1) {
                    AndroidLegacySurfaceRestoreResult.Uncertain(IllegalStateException("attach pending"))
                } else {
                    AndroidLegacySurfaceRestoreResult.Restored
                }
            },
        )

        assertTrue(handoff.restoreBeforeExternalUse(1_000) is AndroidLegacySurfaceRestoreResult.Uncertain)
        assertTrue(handoff.isHeld)
        assertThrows(IllegalStateException::class.java) { handoff.close() }
        assertThrows(IllegalStateException::class.java) { handoff.beginExternalUse() }
        assertSame(source, registry.owner(surface))

        assertEquals(AndroidLegacySurfaceRestoreResult.Restored, handoff.restoreBeforeExternalUse(1_000))
        handoff.close()
        assertSame(source, registry.owner(surface))
        assertEquals(2, restoreAttempts)
    }

    @Test
    fun externalUseGateIrreversiblyForbidsLegacyRollback() {
        val registry = IdentityInstanceRegistry<Any>()
        val surface = Any()
        val source = registry.reserve(surface)
        var restoreAttempts = 0
        val handoff = SurfaceHandoffReservationLifecycle(
            RetryableSurfaceReservationRelease(
                releaseAction = { registry.release(surface, source) },
                heldAction = { registry.owner(surface) === source },
            ),
            restoreAction = {
                restoreAttempts++
                AndroidLegacySurfaceRestoreResult.Restored
            },
        )

        val externalUse = handoff.beginExternalUse()
        assertEquals(
            AndroidLegacySurfaceRestoreResult.StaleOrUnavailable,
            handoff.restoreBeforeExternalUse(1_000),
        )
        assertEquals(0, restoreAttempts)
        assertTrue(handoff.isHeld)
        assertThrows(IllegalStateException::class.java) { handoff.close() }
        assertSame(source, registry.owner(surface))
        // In production, the caller confirms only after exact EGL/router retirement succeeded.
        handoff.confirmExternalUseRetired(externalUse)
        handoff.close()
        assertFalse(handoff.isHeld)
    }

    @Test
    fun wrongExternalUseGenerationCannotConfirmOrUnlockAReservation() {
        val registry = IdentityInstanceRegistry<Any>()
        val firstSurface = Any()
        val secondSurface = Any()
        val firstSource = registry.reserve(firstSurface)
        val secondSource = registry.reserve(secondSurface)
        val first = SurfaceHandoffReservationLifecycle(
            RetryableSurfaceReservationRelease(
                releaseAction = { registry.release(firstSurface, firstSource) },
                heldAction = { registry.owner(firstSurface) === firstSource },
            ),
            restoreAction = null,
        )
        val second = SurfaceHandoffReservationLifecycle(
            RetryableSurfaceReservationRelease(
                releaseAction = { registry.release(secondSurface, secondSource) },
                heldAction = { registry.owner(secondSurface) === secondSource },
            ),
            restoreAction = null,
        )
        val firstUse = first.beginExternalUse()
        val secondUse = second.beginExternalUse()

        assertThrows(IllegalStateException::class.java) { first.confirmExternalUseRetired(secondUse) }
        assertThrows(IllegalStateException::class.java) { first.close() }
        assertSame(firstSource, registry.owner(firstSurface))
        second.confirmExternalUseRetired(secondUse)
        second.close()
        assertSame(firstSource, registry.owner(firstSurface))

        first.confirmExternalUseRetired(firstUse)
        first.close()
        assertFalse(first.isHeld)
    }

    @Test
    fun confirmedExternalRetirementStillRetriesExactRegistryReleaseFailure() {
        val registry = IdentityInstanceRegistry<Any>()
        val surface = Any()
        val source = registry.reserve(surface)
        var attempts = 0
        val handoff = SurfaceHandoffReservationLifecycle(
            RetryableSurfaceReservationRelease(
                releaseAction = {
                    attempts++
                    attempts > 1 && registry.release(surface, source)
                },
                heldAction = { registry.owner(surface) === source },
            ),
            restoreAction = null,
        )
        val externalUse = handoff.beginExternalUse()

        assertThrows(IllegalStateException::class.java) { handoff.close() }
        assertEquals(0, attempts)
        handoff.confirmExternalUseRetired(externalUse)
        assertThrows(IllegalStateException::class.java) { handoff.close() }
        assertTrue(handoff.isHeld)
        assertSame(source, registry.owner(surface))
        handoff.close()
        assertEquals(2, attempts)
        assertFalse(handoff.isHeld)
    }

    @Test
    fun staleOrNewerRouteDoesNotRestoreAndLeavesReservationWithCaller() {
        val registry = IdentityInstanceRegistry<Any>()
        val surface = Any()
        val source = registry.reserve(surface)
        val handoff = SurfaceHandoffReservationLifecycle(
            RetryableSurfaceReservationRelease(
                releaseAction = { registry.release(surface, source) },
                heldAction = { registry.owner(surface) === source },
            ),
            restoreAction = { AndroidLegacySurfaceRestoreResult.StaleOrUnavailable },
        )

        assertEquals(
            AndroidLegacySurfaceRestoreResult.StaleOrUnavailable,
            handoff.restoreBeforeExternalUse(1_000),
        )
        assertTrue(handoff.isHeld)
        assertSame(source, registry.owner(surface))
        handoff.close()
        assertFalse(handoff.isHeld)
    }

    @Test
    fun interruptedDecoderWaitDoesNotBecomeRetirementEvidence() {
        val state = SurfaceLeaseStateMachine<Any>()
        val attached = state.attach(Any())
        state.acknowledge(attached.request)
        val detach = checkNotNull(state.retire(attached.token).request)
        val observed = arrayOfNulls<SurfaceLeaseStateMachine.AwaitResult>(1)
        val waiting = Thread {
            Thread.currentThread().interrupt()
            observed[0] = state.awaitRetirement(attached.token, 1_000)
        }

        waiting.start()
        waiting.join(2_000)
        assertFalse(waiting.isAlive)
        assertEquals(SurfaceLeaseStateMachine.AwaitResult.INTERRUPTED, observed[0])
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.TIMED_OUT,
            state.awaitRetirement(attached.token, 0),
        )
        state.acknowledge(detach)
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.RETIRED,
            state.awaitRetirement(attached.token, 0),
        )
    }
}
