package com.shilapi.xcertplay.ultra.egl

import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class BoundedRemoteFrameIdentityMatcherTest {
    private val key = UltraEglIngressKey.Remote(UltraIngressId("remote-identity"))
    private val handle = syntheticHandle("session-a")
    private val surface = allocateSurface()
    private val ingress = FakeIngress(surface)

    @Test
    fun releaseBeforeAcquisitionMatchesOnlyActualTimestampAndExactLease() {
        val join = BoundedRemoteFrameIdentityMatcher()
        join.bind(key, handle, surface, 17L, 23L)
        assertEquals(RemoteFrameIdentityDisposition.PENDING,
            join.acceptRelease(release(counter = 0L, ptsUs = 123_456L)))
        assertTrue(join.drainMatched().isEmpty())
        assertEquals(RemoteFrameIdentityDisposition.MATCHED,
            join.observeAcquired(acquired(1L, 123_456_000L)))
        val matched = join.drainMatched().single()
        assertSame(handle, matched.handle)
        assertSame(surface, matched.surface)
        assertEquals(17L, matched.surfaceLeaseGeneration)
        assertEquals(23L, matched.eglIngressGeneration)
        assertEquals(1L, matched.eglUpdateSequence)
        assertEquals(123_456_000L, matched.surfaceTextureTimestampNanos)
        assertEquals(RemoteFrameTimestampBasis.CODEC_OUTPUT_PTS, matched.timestampBasis)
        assertEquals(0L, matched.authenticatedFrameCounter)
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            join.acceptRelease(release(counter = 0L, ptsUs = 123_456L)))
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            join.observeAcquired(acquired(1L, 123_456_000L)))
        assertTrue(join.drainMatched().isEmpty())
    }

    @Test
    fun acquisitionBeforeReleaseMatchesTimedTargetWithoutClockNearnessGuess() {
        val join = BoundedRemoteFrameIdentityMatcher()
        join.bind(key, handle, surface, 17L, 23L)
        assertEquals(RemoteFrameIdentityDisposition.PENDING,
            join.observeAcquired(acquired(1L, 500_000_000L)))
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            join.acceptRelease(release(counter = 1L, ptsUs = 499_999L,
                timedTargetNanos = 499_999_999L)))
        assertTrue(join.drainMatched().isEmpty())
        assertEquals(RemoteFrameIdentityDisposition.MATCHED,
            join.acceptRelease(release(counter = 2L, ptsUs = 500_000L,
                timedTargetNanos = 500_000_000L)))
        val matched = join.drainMatched().single()
        assertEquals(RemoteFrameTimestampBasis.TIMED_CODEC_RELEASE, matched.timestampBasis)
        assertEquals(2L, matched.authenticatedFrameCounter)
    }

    @Test
    fun crossSessionSurfaceLeaseAndEglGenerationNeverJoin() {
        val join = BoundedRemoteFrameIdentityMatcher()
        join.bind(key, handle, surface, 17L, 23L)
        val otherHandle = syntheticHandle("session-a")
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            join.acceptRelease(release(counter = 1L, ptsUs = 100L, owner = otherHandle)))
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            join.acceptRelease(release(counter = 1L, ptsUs = 100L, output = allocateSurface())))
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            join.acceptRelease(release(counter = 1L, ptsUs = 100L, leaseGeneration = 18L)))
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            join.acceptRelease(RemoteVideoReleaseSignal(handle, surface, 17L, 1L,
                0x1234L, 100L, null, 100_001L))) // expected timestamp not exact PTS*1000
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            join.observeAcquired(acquired(1L, 100_000L, eglGeneration = 24L)))
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            join.observeAcquired(UltraEglAcquiredRemoteFrame(key, 23L,
                FakeIngress(allocateSurface()), 1L, 100_000L)))
        assertTrue(join.drainMatched().isEmpty())

        assertEquals(RemoteFrameIdentityDisposition.PENDING,
            join.acceptRelease(release(counter = 1L, ptsUs = 100L)))
        join.revoke(key, 23L)
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            join.observeAcquired(acquired(1L, 100_000L)))
        assertTrue(join.drainMatched().isEmpty())
        join.bind(key, handle, surface, 19L, 25L)
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            join.acceptRelease(release(counter = 2L, ptsUs = 200L)))
    }

    @Test
    fun pendingQueuesAndMatchedOutputRemainBounded() {
        val join = BoundedRemoteFrameIdentityMatcher(
            maximumPendingPerIngress = 2, maximumMatchedReceipts = 1,
        )
        join.bind(key, handle, surface, 17L, 23L)
        (1L..3L).forEach { value ->
            assertEquals(RemoteFrameIdentityDisposition.PENDING,
                join.acceptRelease(release(counter = value, ptsUs = value)))
        }
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            join.observeAcquired(acquired(1L, 1_000L))) // first release was evicted
        assertEquals(RemoteFrameIdentityDisposition.MATCHED,
            join.observeAcquired(acquired(2L, 2_000L)))
        assertEquals(RemoteFrameIdentityDisposition.MATCHED,
            join.observeAcquired(acquired(3L, 3_000L)))
        assertEquals(3L, join.drainMatched().single().authenticatedFrameCounter)
        assertTrue(join.drainMatched().isEmpty())
    }

    private fun release(
        counter: Long,
        ptsUs: Long,
        timedTargetNanos: Long? = null,
        owner: AirPlayVideoStreamHandle = handle,
        output: Surface = surface,
        leaseGeneration: Long = 17L,
    ) = RemoteVideoReleaseSignal(owner, output, leaseGeneration, counter,
        0x1234L, ptsUs, timedTargetNanos, timedTargetNanos ?: ptsUs * 1_000L)

    private fun acquired(
        sequence: Long,
        timestampNanos: Long,
        eglGeneration: Long = 23L,
    ) = UltraEglAcquiredRemoteFrame(key, eglGeneration, ingress, sequence, timestampNanos)

    private class FakeIngress(override val producerEndpoint: Any) : UltraEglDriverIngress {
        override val hasUsableFrame: Boolean = true
    }

    private fun syntheticHandle(name: String): AirPlayVideoStreamHandle {
        val epochClass = Class.forName(
            "com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch",
        )
        val epoch = epochClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val constructor = AirPlayVideoStreamHandle::class.java.declaredConstructors
            .single { it.parameterCount == 2 }.apply { isAccessible = true }
        return constructor.newInstance(AirPlayStreamIdentity(111, name, null), epoch) as
            AirPlayVideoStreamHandle
    }

    private fun allocateSurface(): Surface {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (field.get(null) as Unsafe).allocateInstance(Surface::class.java) as Surface
    }
}
