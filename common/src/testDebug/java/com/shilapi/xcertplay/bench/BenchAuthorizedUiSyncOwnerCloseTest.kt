package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.airplay.rcs.uisync.BoundedUiSyncPresentationExecutor
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resource sequencing only; the Authority suite separately proves exact Host gate identity. */
class BenchAuthorizedUiSyncOwnerCloseTest {
    @Test fun missingMainOrClusterAckRetainsTheSameBoundOwnerUntilRetry() {
        for (missing in listOf(Display.MAIN, Display.CLUSTER)) {
            val physicalCover = BoundTwoDisplayCover(missing)
            val resources = BenchAuthorizedUiSyncOwner.Resources(physicalCover)
            val closed = ArrayList<String>()
            resources.provisionerCleanup = Closeable { closed += "provisioner" }
            resources.provisioning = Closeable { closed += "provisioning" }
            resources.benchPainter = Closeable { closed += "painter" }
            val callbacks = BoundedUiSyncPresentationExecutor(1, 64)
            resources.callbacks = callbacks

            assertThrows(IllegalStateException::class.java) { resources.close() }
            assertEquals(emptyList<String>(), closed)
            assertEquals(0, physicalCover.retirementAttempts)
            assertFalse(physicalCover.isFullyCovered)
            // The same callback worker remains usable while the old exact gate has not ACKed.
            val delivered = CountDownLatch(1)
            callbacks.execute(Runnable { delivered.countDown() })
            assertTrue(delivered.await(2, TimeUnit.SECONDS))

            physicalCover.allowMissingAck = true
            resources.close()
            assertTrue(physicalCover.isFullyCovered)
            assertEquals(1, physicalCover.retirementAttempts)
            assertEquals(listOf("provisioner", "provisioning", "painter"), closed)
            assertThrows(RejectedExecutionException::class.java) {
                callbacks.execute(Runnable {})
            }
            resources.close()
            assertEquals(listOf("provisioner", "provisioning", "painter"), closed)
            assertEquals(1, physicalCover.retirementAttempts)
        }
    }

    @Test fun throwingOldCoverKeepsWorkersForRetry() {
        val physicalCover = BoundTwoDisplayCover(Display.CLUSTER)
        val resources = BenchAuthorizedUiSyncOwner.Resources(physicalCover)
        var provisioningCloses = 0
        resources.provisioning = Closeable { provisioningCloses++ }
        val callbacks = BoundedUiSyncPresentationExecutor(1, 64)
        resources.callbacks = callbacks
        physicalCover.throwOnNextCover = true

        assertThrows(IllegalStateException::class.java) { resources.close() }
        assertEquals(0, provisioningCloses)
        val delivered = CountDownLatch(1)
        callbacks.execute(Runnable { delivered.countDown() })
        assertTrue(delivered.await(2, TimeUnit.SECONDS))

        physicalCover.allowMissingAck = true
        resources.close()
        assertEquals(1, provisioningCloses)
        assertEquals(1, physicalCover.retirementAttempts)
    }

    @Test fun noPhysicalAuthorityStillClosesOrdinaryResourcesOnce() {
        val resources = BenchAuthorizedUiSyncOwner.Resources()
        var provisioningCloses = 0
        resources.provisioning = Closeable { provisioningCloses++ }
        resources.close()
        resources.close()
        assertEquals(1, provisioningCloses)
    }

    private enum class Display { MAIN, CLUSTER }

    /** Models an already bound two-display owner with independently ACKing old UI gates. */
    private class BoundTwoDisplayCover(private val missing: Display) :
        BenchAuthorizedUiSyncOwner.PhysicalCoverRetirement {
        private var mainCovered = false
        private var clusterCovered = false
        var allowMissingAck = false
        var throwOnNextCover = false
        var retirementAttempts = 0
            private set

        val isFullyCovered: Boolean get() = mainCovered && clusterCovered

        override fun revokeForPolicyEpoch(): Boolean {
            if (throwOnNextCover) {
                throwOnNextCover = false
                throw IllegalStateException("Synthetic old Host cover dispatch failure")
            }
            if (missing != Display.MAIN || allowMissingAck) mainCovered = true
            if (missing != Display.CLUSTER || allowMissingAck) clusterCovered = true
            return isFullyCovered
        }

        override fun retryPendingRetirement(): Boolean {
            check(isFullyCovered)
            retirementAttempts++
            return true
        }
    }
}
