package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SurfaceLeaseStateMachineTest {
    @Test
    fun replacementRetiresOldLeaseOnlyAfterWorkerAcknowledgesReplacement() {
        val state = SurfaceLeaseStateMachine<Any>()
        val firstSurface = Any()
        val secondSurface = Any()
        val first = state.attach(firstSurface)
        state.acknowledge(first.request)

        val retirement = state.retire(first.token)
        val detach = checkNotNull(retirement.request)
        assertThrows(IllegalStateException::class.java) { state.attach(secondSurface) }
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.TIMED_OUT,
            state.awaitRetirement(first.token, 0),
        )

        state.acknowledge(detach)
        val second = state.attach(secondSurface)
        state.acknowledge(second.request)

        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.RETIRED,
            state.awaitRetirement(first.token, 0),
        )
        assertTrue(state.isCurrent(second.token))
    }

    @Test
    fun sameSurfaceInstanceCannotCrossGenerations() {
        val state = SurfaceLeaseStateMachine<Any>()
        val sharedSurface = Any()
        val first = state.attach(sharedSurface)
        state.acknowledge(first.request)

        assertThrows(IllegalArgumentException::class.java) { state.attach(sharedSurface) }
        assertTrue(state.isCurrent(first.token))
        assertSame(sharedSurface, state.currentRequest().surface)
    }

    @Test
    fun timeoutNeverAdvancesRetirementWithoutDecoderEvidence() {
        val state = SurfaceLeaseStateMachine<Any>()
        val lease = state.attach(Any())
        state.acknowledge(lease.request)
        val detach = checkNotNull(state.retire(lease.token).request)

        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.TIMED_OUT,
            state.awaitRetirement(lease.token, 1),
        )

        // A decoder release while processing the detach is valid retirement evidence.
        state.acknowledge(detach)
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.RETIRED,
            state.awaitRetirement(lease.token, 0),
        )
    }

    @Test
    fun concurrentCloseEmitsOneDetachAndWakesEveryWaiter() {
        val state = SurfaceLeaseStateMachine<Any>()
        val lease = state.attach(Any())
        state.acknowledge(lease.request)
        val start = CountDownLatch(1)
        val retired = CountDownLatch(8)
        val executor = Executors.newFixedThreadPool(8)
        val requests = java.util.concurrent.ConcurrentLinkedQueue<SurfaceLeaseStateMachine.ApplyRequest<Any>>()
        val results = java.util.concurrent.ConcurrentLinkedQueue<SurfaceLeaseStateMachine.AwaitResult>()

        repeat(8) {
            executor.execute {
                start.await()
                state.retire(lease.token).request?.let(requests::add)
                results += state.awaitRetirement(lease.token, 2_000)
                retired.countDown()
            }
        }
        start.countDown()
        while (requests.isEmpty()) Thread.yield()
        state.acknowledge(requests.single())

        assertTrue(retired.await(2, TimeUnit.SECONDS))
        assertEquals(1, requests.size)
        assertEquals(List(8) { SurfaceLeaseStateMachine.AwaitResult.RETIRED }, results.toList())
        executor.shutdownNow()
    }

    @Test
    fun shutdownRejectsNewLeasesAndTerminationReleasesAllWaiters() {
        val state = SurfaceLeaseStateMachine<Any>()
        val lease = state.attach(Any())
        state.beginShutdown()

        assertFalse(state.isCurrent(lease.token))
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.TIMED_OUT,
            state.awaitRetirement(lease.token, 0),
        )
        state.acknowledgeTermination()
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.RETIRED,
            state.awaitRetirement(lease.token, 0),
        )
        assertThrows(IllegalStateException::class.java) { state.attach(Any()) }
    }

    @Test
    fun unsafeRetirementResultsAreNeverReleaseEvidence() {
        assertFalse(AndroidVideoSurfaceRetirementResult.INTERRUPTED.isSafeToReleaseSurface)
        assertFalse(AndroidVideoSurfaceRetirementResult.DECODER_CLOSE_TIMED_OUT.isSafeToReleaseSurface)
        assertFalse(AndroidVideoSurfaceRetirementResult.DECODER_RELEASE_FAILED.isSafeToReleaseSurface)
        assertTrue(AndroidVideoSurfaceRetirementResult.RETIRED.isSafeToReleaseSurface)
        assertTrue(AndroidVideoSurfaceRetirementResult.FORCED_DECODER_CLOSE.isSafeToReleaseSurface)
    }

    @Test
    fun leaseCannotBeConstructedThroughTheJvmPublicApi() {
        assertTrue(
            AndroidVideoSurfaceLease::class.java.declaredConstructors.none {
                Modifier.isPublic(it.modifiers) && !it.isSynthetic
            },
        )
        val issue = AndroidVideoSurfaceLease.Companion::class.java.declaredMethods.single {
            it.name.startsWith("issue")
        }
        assertTrue(issue.isSynthetic)
    }

    @Test
    fun attachmentProofMatchesOnlyExactHandleAndSurfaceReferences() {
        val handle = Any()
        val surface = Any()
        val proof = IdentityAttachmentProof.issue(handle, surface)

        assertTrue(proof.matches(handle, surface))
        assertFalse(proof.matches(Any(), surface))
        assertFalse(proof.matches(handle, Any()))
    }

    @Test
    fun equalProtocolIdentityInDifferentSessionsHasIndependentSurfaceOutput() {
        val identity = AirPlayStreamIdentity(110, "same-wire-id", null)
        val oldHandle = AirPlayVideoStreamHandle(identity, AirPlayVideoSessionEpoch())
        val newHandle = AirPlayVideoStreamHandle(identity, AirPlayVideoSessionEpoch())
        val registry = SurfaceLeaseOutputRegistry<AirPlayVideoStreamHandle, Any>()
        val reusedSurface = Any()
        val oldOutput = registry.getOrCreate(oldHandle)
        val newOutput = registry.getOrCreate(newHandle)
        val oldLease = oldOutput.state.attach(reusedSurface)
        val newLease = newOutput.state.attach(reusedSurface)
        oldOutput.state.acknowledge(oldLease.request)
        newOutput.state.acknowledge(newLease.request)

        val oldDetach = checkNotNull(oldOutput.state.retire(oldLease.token).request)
        oldOutput.state.acknowledge(oldDetach)
        assertTrue(registry.remove(oldHandle, oldOutput))
        oldOutput.state.acknowledgeTermination()

        assertNotSame(oldOutput, newOutput)
        assertNull(registry[oldHandle])
        assertSame(newOutput, registry[newHandle])
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.RETIRED,
            oldOutput.state.awaitRetirement(oldLease.token, 0),
        )
        assertTrue(newOutput.state.isCurrent(newLease.token))
        assertSame(reusedSurface, newOutput.state.currentRequest().surface)
    }

    @Test
    fun legacySurfaceMovesBetweenSessionOutputsOnlyAfterOldOutputRetires() {
        val registry = IdentityInstanceRegistry<Any>()
        val sharedSurface = Any()
        val owner = registry.reserve(sharedSurface)
        val oldOutput = SurfaceLeaseStateMachine<Any>()
        val newOutput = SurfaceLeaseStateMachine<Any>()
        val oldLease = oldOutput.attach(sharedSurface)
        oldOutput.acknowledge(oldLease.request)

        val detach = checkNotNull(oldOutput.retire(oldLease.token).request)
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.TIMED_OUT,
            oldOutput.awaitRetirement(oldLease.token, 0),
        )
        oldOutput.acknowledge(detach)
        assertEquals(
            SurfaceLeaseStateMachine.AwaitResult.RETIRED,
            oldOutput.awaitRetirement(oldLease.token, 0),
        )

        val newLease = newOutput.attach(sharedSurface)
        newOutput.acknowledge(newLease.request)
        assertSame(owner, registry.owner(sharedSurface))
        assertTrue(newOutput.isCurrent(newLease.token))
    }
}
