package com.shilapi.xcertplay

import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationId
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.PixelSize
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalClusterPresentationControllerTest {
    private val pixels = PixelSize(1280, 480)
    private val candidate = ExternalPhysicalDisplayCandidate(4, 101L, pixels)

    private class Lease(candidate: ExternalPhysicalDisplayCandidate) : ExternalPhysicalDisplayLease {
        override var stableConnectorIdentity = "bench-connector-7"
        override var hardwareIdentity = "pinned-panel-serial-7"
        override var displayId = candidate.displayId
        override var displayEpoch = candidate.displayEpoch
        override var pixels = candidate.pixels
        override var widthPhysicalMm = 221
        override var heightPhysicalMm = 83
        override var clusterInstanceId = DisplayDestinationId("cluster-bench-7")
        var current = true
        var throwOnCurrent = false
        var closeCount = 0
        var allowClose = true
        var revocationCloseCount = 0
        var allowRevocationClose = true
        private var revocation: (() -> Unit)? = null

        override fun isCurrent(): Boolean {
            if (throwOnCurrent) error("hardware probe failed")
            return current
        }
        override fun subscribeRevocation(onRevoked: () -> Unit): Closeable {
            revocation = onRevoked
            return Closeable {
                revocationCloseCount++
                if (!allowRevocationClose) error("subscription close failed")
                if (revocation === onRevoked) revocation = null
            }
        }
        fun revoke() {
            current = false
            revocation?.invoke()
        }
        override fun close() {
            closeCount++
            if (!allowClose) error("lease close failed")
        }
    }

    private class Window(
        val candidate: ExternalPhysicalDisplayCandidate,
        val callbacks: ExternalClusterPresentationCallbacks,
    ) : Closeable {
        var closeCount = 0
        var allowClose = true
        override fun close() {
            closeCount++
            if (!allowClose) error("Presentation close failed")
        }
    }

    private class Publication(override val texture: Any) : ExternalClusterPublication {
        val withdrawals = ArrayList<Boolean>()
        var resizeCount = 0
        var allowWithdraw = true
        override fun resize(pixels: PixelSize): Boolean {
            resizeCount++
            return true
        }
        override fun withdraw(textureDestroyed: Boolean): Boolean {
            withdrawals += textureDestroyed
            if (!allowWithdraw) error("publication withdrawal failed")
            return false
        }
    }

    private class Harness(
        private val provider: ExternalPhysicalDisplayTrustProvider,
    ) {
        private class Gate(
            override val calibration: ExternalPhysicalDisplayCalibration,
            private val onInvalidated: () -> Unit,
        ) : ExternalClusterTrustGate {
            private val current = AtomicBoolean(true)
            override val registryToken: ExternalPhysicalTargetTrustToken? = null
            override val isCurrent: Boolean get() = current.get()
            override fun invalidate() {
                if (current.getAndSet(false)) onInvalidated()
            }
        }
        val windows = ArrayList<Window>()
        val publications = ArrayList<Publication>()
        val destinations = ArrayList<DisplayDestination>()
        var candidateCurrent = true
        var candidateFailure = false
        var failShow = false
        var failPublication = false
        var gateCalibrationOverride: ExternalPhysicalDisplayCalibration? = null
        var beforePublication: (() -> Unit)? = null
        val gateInvalidated = CountDownLatch(1)
        val gates = ArrayList<ExternalClusterTrustGate>()
        val calibrations = ArrayList<ExternalPhysicalDisplayCalibration>()
        val controller = ExternalClusterPresentationController(
            provider,
            ExternalClusterPresentationPort { candidate, callbacks ->
                if (failShow) throw IllegalStateException("display removed during show")
                Window(candidate, callbacks).also(windows::add)
            },
            ExternalClusterPublicationPort { destination, _, texture, _, _, _ ->
                beforePublication?.invoke()
                if (failPublication) error("native publication failed")
                destinations += destination
                Publication(texture).also(publications::add)
            },
            { if (candidateFailure) error("DisplayManager probe failed") else candidateCurrent },
            { calibration ->
                calibrations += calibration
                Gate(gateCalibrationOverride ?: calibration) {
                    gateInvalidated.countDown()
                }.also(gates::add)
            },
        )

        fun available(window: Window, texture: Any = Any(), pixels: PixelSize = window.candidate.pixels): Any {
            val accepted = window.callbacks.onAvailable(Any(), texture, pixels)
            assertTrue(accepted)
            return texture
        }
    }

    @Test fun defaultDenyAllNeverShowsPresentation() {
        val harness = Harness(ExternalPhysicalDisplayTrustProvider.DENY_ALL)
        harness.controller.onCandidatesChanged(listOf(candidate))
        assertTrue(harness.windows.isEmpty())
        assertNull(harness.controller.activeDestination())
    }

    @Test fun twoIndependentlyTrustedCandidatesNeverChooseTheFirstConnector() {
        val other = ExternalPhysicalDisplayCandidate(5, 102L, pixels)
        val firstLease = Lease(candidate)
        val secondLease = Lease(other)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { seen ->
            if (seen == candidate) firstLease else secondLease
        })
        harness.controller.onCandidatesChanged(listOf(candidate, other))
        assertTrue(harness.windows.isEmpty())
        assertNull(harness.controller.activeDestination())
        assertEquals(1, firstLease.closeCount)
        assertEquals(1, secondLease.closeCount)
    }

    @Test fun secondTrustedConnectorRevokesAlreadyPublishedFirstConnector() {
        val other = ExternalPhysicalDisplayCandidate(5, 102L, pixels)
        val firstLease = Lease(candidate)
        val secondLease = Lease(other)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { seen ->
            if (seen == candidate) firstLease else secondLease
        })
        harness.controller.onCandidatesChanged(listOf(candidate))
        harness.available(harness.windows.single())
        assertEquals(harness.destinations.single(), harness.controller.activeDestination())
        harness.controller.onCandidatesChanged(listOf(candidate, other))
        assertNull(harness.controller.activeDestination())
        assertFalse(harness.gates.single().isCurrent)
        assertEquals(listOf(false), harness.publications.single().withdrawals)
        assertEquals(1, firstLease.closeCount)
        assertEquals(1, secondLease.closeCount)
    }

    @Test fun leaseMustBindExactDisplayEpochGeometryHardwareAndNondefaultCluster() {
        val badLeases = listOf<(Lease) -> Unit>(
            { it.displayId++ },
            { it.displayEpoch++ },
            { it.pixels = PixelSize(1281, 480) },
            { it.hardwareIdentity = "" },
            { it.stableConnectorIdentity = "" },
            { it.clusterInstanceId = DisplayDestinationId("default") },
            { it.widthPhysicalMm = 0 },
            { it.heightPhysicalMm = 2_001 },
            { it.current = false },
        )
        for (breakLease in badLeases) {
            val lease = Lease(candidate).also(breakLease)
            val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
            harness.controller.onCandidatesChanged(listOf(candidate))
            assertTrue(harness.windows.isEmpty())
            assertNull(harness.controller.activeDestination())
            assertEquals(1, lease.closeCount)
        }
    }

    @Test fun verifiedLeaseUsesNondefaultClusterDestinationAndExactPixels() {
        val lease = Lease(candidate)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
        harness.controller.onCandidatesChanged(listOf(candidate))
        val window = harness.windows.single()
        val texture = harness.available(window)
        assertEquals(
            DisplayDestination(DisplayDestinationRole.CLUSTER, DisplayDestinationId("cluster-bench-7")),
            harness.destinations.single(),
        )
        assertEquals(harness.destinations.single(), harness.controller.activeDestination())
        assertEquals(lease.widthPhysicalMm, harness.calibrations.single().widthPhysicalMm)
        assertEquals(lease.heightPhysicalMm, harness.calibrations.single().heightPhysicalMm)
        assertEquals(candidate.displayEpoch, harness.calibrations.single().displayEpoch)
        window.callbacks.onSizeChanged(texture, pixels)
        assertEquals(1, harness.publications.single().resizeCount)
        assertTrue(window.callbacks.onDestroyed(Any()))
        assertFalse(window.callbacks.onDestroyed(texture))
    }

    @Test fun changedCalibrationOrDisplayEpochCannotReuseTheFrozenLeaseGeneration() {
        for (change in listOf<(Lease) -> Unit>(
            { it.widthPhysicalMm++ },
            { it.heightPhysicalMm++ },
            { it.displayEpoch++ },
            { it.hardwareIdentity = "another-panel" },
        )) {
            val lease = Lease(candidate)
            val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
            harness.controller.onCandidatesChanged(listOf(candidate))
            harness.available(harness.windows.single())
            assertEquals(harness.destinations.single(), harness.controller.activeDestination())
            change(lease)
            assertNull(harness.controller.activeDestination())
            harness.controller.onCandidatesChanged(listOf(candidate))
            assertFalse(harness.gates.first().isCurrent)
            assertEquals(listOf(false), harness.publications.first().withdrawals)
            assertTrue(lease.closeCount >= 1)
        }
    }

    @Test fun gateForSamePixelsButDifferentPhysicalMillimetresCannotPublish() {
        val lease = Lease(candidate)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
        harness.gateCalibrationOverride = externalCalibrationForTest(
            destination = lease.clusterDestination(), pixels = pixels,
            widthMm = lease.widthPhysicalMm + 1,
            heightMm = lease.heightPhysicalMm,
        )
        harness.controller.onCandidatesChanged(listOf(candidate))
        val accepted = harness.windows.single().callbacks.onAvailable(Any(), Any(), pixels)
        assertFalse(accepted)
        assertTrue(harness.publications.isEmpty())
        assertNull(harness.controller.activeDestination())
        assertEquals(1, lease.closeCount)
    }

    @Test fun destroyedTextureCanBeRecreatedInSamePresentationGeneration() {
        val lease = Lease(candidate)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
        harness.controller.onCandidatesChanged(listOf(candidate))
        val window = harness.windows.single()
        val firstTexture = harness.available(window)
        assertFalse(window.callbacks.onDestroyed(firstTexture))
        assertFalse(harness.gates.single().isCurrent)
        assertNull(harness.controller.activeDestination())
        val secondTexture = harness.available(window)
        assertEquals(2, harness.publications.size)
        assertEquals(2, harness.destinations.size)
        assertTrue(harness.gates.last().isCurrent)
        assertEquals(harness.destinations.last(), harness.controller.activeDestination())
        assertFalse(window.callbacks.onDestroyed(firstTexture))
        assertEquals(emptyList<Boolean>(), harness.publications.last().withdrawals)
        assertFalse(window.callbacks.onDestroyed(secondTexture))
    }

    @Test fun manyDestroyedTexturesKeepOnlyExactWeakTombstonesAndNeverRetireReplacement() {
        val lease = Lease(candidate)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
        harness.controller.onCandidatesChanged(listOf(candidate))
        val window = harness.windows.single()
        val historicalTextures = ArrayList<Any>()
        repeat(64) {
            val texture = harness.available(window)
            historicalTextures += texture
            assertFalse(window.callbacks.onDestroyed(texture))
            assertFalse(window.callbacks.onDestroyed(texture))
        }
        val replacementTexture = harness.available(window)
        val replacementPublication = harness.publications.last()
        historicalTextures.forEach { assertFalse(window.callbacks.onDestroyed(it)) }
        assertEquals(emptyList<Boolean>(), replacementPublication.withdrawals)
        assertEquals(harness.destinations.last(), harness.controller.activeDestination())
        assertFalse(window.callbacks.onDestroyed(replacementTexture))
    }

    @Test fun synchronousRevocationInsidePublishRetiresReturnedSurfaceBeforeItCanBeUsed() {
        val lease = Lease(candidate)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
        harness.beforePublication = lease::revoke
        harness.controller.onCandidatesChanged(listOf(candidate))
        val window = harness.windows.single()
        val texture = harness.available(window)
        assertNull(harness.controller.activeDestination())
        assertEquals(listOf(false), harness.publications.single().withdrawals)
        assertEquals(1, window.closeCount)
        assertFalse(window.callbacks.onDestroyed(texture))
    }

    @Test fun crossThreadRevocationInvalidatesTrustBeforeControllerLockIsAvailable() {
        val lease = Lease(candidate)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
        lateinit var revoker: Thread
        harness.beforePublication = {
            revoker = Thread { lease.revoke() }.also(Thread::start)
            assertTrue(harness.gateInvalidated.await(2, TimeUnit.SECONDS))
            assertFalse(harness.gates.single().isCurrent)
        }
        harness.controller.onCandidatesChanged(listOf(candidate))
        val window = harness.windows.single()
        val texture = harness.available(window)
        revoker.join(2_000L)
        assertFalse(revoker.isAlive)
        assertNull(harness.controller.activeDestination())
        assertEquals(listOf(false), harness.publications.single().withdrawals)
        assertFalse(window.callbacks.onDestroyed(texture))
    }

    @Test fun synchronousDestroyedAndStoppedCallbacksDuringPublishPreserveTextureRetirement() {
        val lease = Lease(candidate)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
        harness.controller.onCandidatesChanged(listOf(candidate))
        val window = harness.windows.single()
        val firstTexture = Any()
        harness.beforePublication = {
            assertFalse(window.callbacks.onDestroyed(firstTexture))
        }
        assertTrue(window.callbacks.onAvailable(Any(), firstTexture, pixels))
        assertEquals(listOf(true), harness.publications.single().withdrawals)
        assertNull(harness.controller.activeDestination())
        assertFalse(harness.gates.single().isCurrent)

        val secondTexture = Any()
        harness.beforePublication = { window.callbacks.onStopped() }
        assertTrue(window.callbacks.onAvailable(Any(), secondTexture, pixels))
        assertEquals(listOf(false), harness.publications.last().withdrawals)
        assertNull(harness.controller.activeDestination())
    }

    @Test fun throwingLivenessProbeAndLeaseValidationFailClosed() {
        val rejectedLease = Lease(candidate).apply { throwOnCurrent = true }
        val rejected = Harness(ExternalPhysicalDisplayTrustProvider { rejectedLease })
        rejected.controller.onCandidatesChanged(listOf(candidate))
        assertEquals(1, rejectedLease.closeCount)
        assertTrue(rejected.windows.isEmpty())

        val activeLease = Lease(candidate)
        val active = Harness(ExternalPhysicalDisplayTrustProvider { activeLease })
        active.controller.onCandidatesChanged(listOf(candidate))
        val window = active.windows.single()
        active.available(window)
        active.candidateFailure = true
        active.controller.onCandidatesChanged(listOf(candidate))
        assertNull(active.controller.activeDestination())
        assertEquals(listOf(false), active.publications.single().withdrawals)
        assertEquals(1, activeLease.closeCount)
    }

    @Test fun failedPublicationClosesGenerationAndCanRetryOnNextSourceEvent() {
        val lease = Lease(candidate)
        val nextLease = Lease(candidate)
        var issued = 0
        val harness = Harness(ExternalPhysicalDisplayTrustProvider {
            if (issued++ == 0) lease else nextLease
        })
        harness.failPublication = true
        harness.controller.onCandidatesChanged(listOf(candidate))
        val firstWindow = harness.windows.single()
        assertFalse(firstWindow.callbacks.onAvailable(Any(), Any(), pixels))
        assertNull(harness.controller.activeDestination())
        assertEquals(1, firstWindow.closeCount)
        assertEquals(1, lease.closeCount)
        harness.failPublication = false
        harness.controller.onCandidatesChanged(listOf(candidate))
        val secondWindow = harness.windows.last()
        harness.available(secondWindow)
        assertEquals(2, harness.windows.size)
        assertEquals(harness.destinations.single(), harness.controller.activeDestination())
    }

    @Test fun candidateChangeOrRemovalRetiresExactOldGenerationBeforeNewPublication() {
        val first = Lease(candidate)
        val replacement = candidate.copy(displayEpoch = 102L)
        val second = Lease(replacement)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider {
            if (it == candidate) first else second
        })
        harness.controller.onCandidatesChanged(listOf(candidate))
        val oldWindow = harness.windows.single()
        val oldTexture = harness.available(oldWindow)
        harness.controller.onCandidatesChanged(listOf(replacement))
        val newWindow = harness.windows.last()
        val newTexture = harness.available(newWindow)
        assertEquals(listOf(false), harness.publications.first().withdrawals)
        assertEquals(1, oldWindow.closeCount)
        assertEquals(1, first.closeCount)
        // A late callback from the old Presentation cannot withdraw the replacement.
        assertFalse(oldWindow.callbacks.onDestroyed(oldTexture))
        assertFalse(oldWindow.callbacks.onAvailable(Any(), Any(), pixels))
        assertEquals(emptyList<Boolean>(), harness.publications.last().withdrawals)
        assertFalse(newWindow.callbacks.onDestroyed(newTexture))
        harness.controller.onCandidatesChanged(emptyList())
        assertNull(harness.controller.activeDestination())
        assertEquals(1, second.closeCount)
    }

    @Test fun leaseRevocationWithdrawsSurfaceAndClosesOnlyItsWindow() {
        val lease = Lease(candidate)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
        harness.controller.onCandidatesChanged(listOf(candidate))
        val window = harness.windows.single()
        val texture = harness.available(window)
        lease.revoke()
        assertEquals(listOf(false), harness.publications.single().withdrawals)
        assertEquals(1, window.closeCount)
        assertNull(harness.controller.activeDestination())
        assertEquals(1, lease.closeCount)
        window.callbacks.onStopped()
        assertEquals(listOf(false, false), harness.publications.single().withdrawals)
        assertFalse(window.callbacks.onDestroyed(texture))
        assertEquals(listOf(false, false, true), harness.publications.single().withdrawals)
    }

    @Test fun wrongPixelGeometryAndShowFailureFailClosed() {
        val lease = Lease(candidate)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
        harness.controller.onCandidatesChanged(listOf(candidate))
        val window = harness.windows.single()
        assertFalse(window.callbacks.onAvailable(Any(), Any(), PixelSize(100, 100)))
        assertTrue(harness.publications.isEmpty())
        assertNull(harness.controller.activeDestination())
        assertEquals(1, lease.closeCount)

        val resizeLease = Lease(candidate)
        val resizing = Harness(ExternalPhysicalDisplayTrustProvider { resizeLease })
        resizing.controller.onCandidatesChanged(listOf(candidate))
        val resizeWindow = resizing.windows.single()
        val texture = resizing.available(resizeWindow)
        resizeWindow.callbacks.onSizeChanged(texture, PixelSize(1279, 480))
        assertEquals(listOf(false), resizing.publications.single().withdrawals)
        assertNull(resizing.controller.activeDestination())

        val failureLease = Lease(candidate)
        val failing = Harness(ExternalPhysicalDisplayTrustProvider { failureLease })
        failing.failShow = true
        failing.controller.onCandidatesChanged(listOf(candidate))
        assertTrue(failing.windows.isEmpty())
        assertEquals(1, failureLease.closeCount)
        assertNull(failing.controller.activeDestination())
    }

    @Test fun closeForActivityRecreationCannotReviveOldCallbacks() {
        val oldLease = Lease(candidate)
        val old = Harness(ExternalPhysicalDisplayTrustProvider { oldLease })
        old.controller.onCandidatesChanged(listOf(candidate))
        val oldWindow = old.windows.single()
        val oldTexture = old.available(oldWindow)
        old.controller.close()
        assertNull(old.controller.activeDestination())
        assertFalse(oldWindow.callbacks.onAvailable(Any(), Any(), pixels))
        assertFalse(oldWindow.callbacks.onDestroyed(oldTexture))
        old.controller.onCandidatesChanged(listOf(candidate))
        assertEquals(1, old.windows.size)

        val newLease = Lease(candidate.copy(displayEpoch = 103L))
        val replacement = Harness(ExternalPhysicalDisplayTrustProvider { newLease })
        replacement.controller.onCandidatesChanged(listOf(candidate.copy(displayEpoch = 103L)))
        assertEquals(1, replacement.windows.size)
        assertEquals(emptyList<Boolean>(), replacement.publications.flatMap { it.withdrawals })
    }

    @Test fun failedWindowSubscriptionAndLeaseClosesRemainExactAndControllerCloseIsRetryable() {
        val lease = Lease(candidate)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
        harness.controller.onCandidatesChanged(listOf(candidate))
        val window = harness.windows.single()
        val texture = harness.available(window)
        window.allowClose = false
        lease.allowRevocationClose = false
        lease.allowClose = false

        harness.controller.onCandidatesChanged(emptyList())
        assertNull(harness.controller.activeDestination())
        assertFalse(harness.gates.single().isCurrent)
        assertEquals(listOf(false), harness.publications.single().withdrawals)
        assertTrue(runCatching { harness.controller.close() }.isFailure)
        assertTrue(window.closeCount > 0)
        assertTrue(lease.revocationCloseCount > 0)
        assertTrue(lease.closeCount > 0)

        window.allowClose = true
        lease.allowRevocationClose = true
        lease.allowClose = true
        harness.controller.close()
        val windowCloses = window.closeCount
        val subscriptionCloses = lease.revocationCloseCount
        val leaseCloses = lease.closeCount
        harness.controller.close()
        assertEquals(windowCloses, window.closeCount)
        assertEquals(subscriptionCloses, lease.revocationCloseCount)
        assertEquals(leaseCloses, lease.closeCount)
        assertFalse(window.callbacks.onDestroyed(texture))
    }

    @Test fun failedPublicationWithdrawalIsRetainedAndControllerCannotReportCleanClose() {
        val lease = Lease(candidate)
        val harness = Harness(ExternalPhysicalDisplayTrustProvider { lease })
        harness.controller.onCandidatesChanged(listOf(candidate))
        val window = harness.windows.single()
        harness.available(window)
        val publication = harness.publications.single()
        publication.allowWithdraw = false

        harness.controller.onCandidatesChanged(emptyList())
        assertNull(harness.controller.activeDestination())
        assertTrue(runCatching { harness.controller.close() }.isFailure)
        assertTrue(publication.withdrawals.isNotEmpty())

        publication.allowWithdraw = true
        harness.controller.close()
        assertTrue(publication.withdrawals.last() == false)
    }
}
