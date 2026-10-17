package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import sun.misc.Unsafe

class ExternalClusterUiCoverGateTest {
    private class ManualWatchdog : ExternalClusterUiCoverWatchdogScheduler {
        var tick: (() -> Unit)? = null
        override fun schedule(recheck: () -> Unit): Closeable {
            tick = recheck
            return Closeable { if (tick === recheck) tick = null }
        }
        fun advance() = requireNotNull(tick).invoke()
    }
    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid() = true
    }

    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
        .apply { isAccessible = true }.get(null) as Unsafe
    private fun surface(): Surface = unsafe.allocateInstance(ValidSurface::class.java) as Surface
    private fun texture(): SurfaceTexture =
        unsafe.allocateInstance(SurfaceTexture::class.java) as SurfaceTexture

    private class UiProjection {
        private val delayed = ArrayDeque<() -> Unit>()
        var active: ExternalClusterUiCoverGate? = null
        var covered = true

        fun gate(): ExternalClusterUiCoverGate {
            lateinit var candidate: ExternalClusterUiCoverGate
            candidate = ExternalClusterUiCoverGate { sequence, state ->
                delayed.addLast {
                    if (active === candidate && candidate.currentSequence == sequence) {
                        covered = state
                    }
                }
                true
            }
            active = candidate
            covered = true
            return candidate
        }

        fun deliverNewestFirst() {
            while (delayed.isNotEmpty()) delayed.removeLast().invoke()
        }
    }

    @Test fun legacyFrameMayRevealOnlyBeforeUltraClaimAndOldCallbacksCannotRevealReplacement() {
        val ui = UiProjection()
        val old = ui.gate()
        assertTrue(old.isCovered)
        assertFalse(old.onTextureUpdated(1L, exactCurrent = { false }, legacyRouteCurrent = { true }))
        val legacy = ui.gate()
        assertTrue(legacy.onTextureUpdated(2L, exactCurrent = { true }, legacyRouteCurrent = { true }))
        ui.deliverNewestFirst()
        assertFalse(ui.covered)

        assertTrue(legacy.claimUltra())
        assertFalse(legacy.onTextureUpdated(2L, exactCurrent = { true }, legacyRouteCurrent = { true }))
        ui.deliverNewestFirst()
        assertTrue(ui.covered)

        assertTrue(legacy.armUltra(3L) { true })
        assertTrue(legacy.onTextureUpdated(3L, exactCurrent = { true }, legacyRouteCurrent = { false }))
        assertTrue(legacy.cover(retire = true))
        ui.deliverNewestFirst() // queued stale reveal must lose to the later cover
        assertTrue(ui.covered)
        assertFalse(legacy.onTextureUpdated(3L, exactCurrent = { true }, legacyRouteCurrent = { false }))
        assertTrue(old.isCovered)
    }

    @Test fun exactRegistryRequiresPhysicalRouteAndNewTextureCallbackAfterScenePermit() {
        val registry = HostPhysicalDisplaySurfaceRegistry(releaseSurface = {}, releaseTexture = {})
        val previewSurface = surface()
        val previewTexture = texture()
        registry.publish(DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PREVIEW_ONLY,
            previewSurface, previewTexture, 800, 480)
        assertNull(registry.bindExternalClusterUiCover(previewSurface, previewTexture, 4, 101L,
            ExternalClusterUiCoverGate { _, _ -> true }))

        val trustedSurface = surface()
        val trustedTexture = texture()
        val trust = registry.newExternalPhysicalTargetTrustToken(externalCalibrationForTest(
            pixels = PixelSize(800, 480),
        )).also {
            it.bindRouteProof { true }
            it.bindLegacyDecoderProof { true }
        }
        val version = requireNotNull(registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            trustedSurface, trustedTexture, 800, 480, trust,
        ))
        var covered = true
        val gate = ExternalClusterUiCoverGate { _, state -> covered = state; true }
        val attached = requireNotNull(registry.bindExternalClusterUiCover(
            trustedSurface, trustedTexture, 4, 101L, gate,
        ))
        assertNull(registry.bindExternalClusterUiCover(trustedSurface, trustedTexture, 4, 102L,
            ExternalClusterUiCoverGate { _, _ -> true }))
        assertNotNull(attached)
        assertFalse(attached.onTextureUpdated(previewTexture, 6L))
        assertTrue(attached.onTextureUpdated(trustedTexture, 7L)) // exact legacy type-111 frame
        assertFalse(covered)
        assertFalse(gate.armUltra(8L) { true })

        assertTrue(registry.coverExternalForUltraHandoff(version, trustedSurface))
        assertTrue(covered)
        assertFalse(attached.onTextureUpdated(trustedTexture, 7L)) // old decoder frame
        val borrowed = requireNotNull(registry.acquirePhysicalTarget(version))
        assertTrue(gate.armUltra(8L) { true })
        assertTrue(covered) // a permit is not a TextureView update
        assertTrue(attached.onTextureUpdated(trustedTexture, 8L))
        assertFalse(covered)

        trust.invalidate() // physical lease revokes before EGL retirement can finish
        assertTrue(covered)
        assertFalse(attached.onTextureUpdated(trustedTexture, 8L))
        borrowed.retireAfterEglProof()
        attached.close()
    }

    @Test fun failedUiCoverCannotAuthorizeUltraBorrowOrRetirement() {
        val registry = HostPhysicalDisplaySurfaceRegistry(releaseSurface = {}, releaseTexture = {})
        val trustedSurface = surface()
        val trustedTexture = texture()
        val trust = registry.newExternalPhysicalTargetTrustToken(externalCalibrationForTest(
            pixels = PixelSize(800, 480),
        )).also { it.bindRouteProof { true } }
        val version = requireNotNull(registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            trustedSurface, trustedTexture, 800, 480, trust,
        ))
        val gate = ExternalClusterUiCoverGate { _, covered -> !covered }
        val attached = requireNotNull(registry.bindExternalClusterUiCover(
            trustedSurface, trustedTexture, 4, 101L, gate,
        ))
        assertFalse(registry.coverExternalForUltraHandoff(version, trustedSurface))
        assertNull(registry.acquirePhysicalTarget(version))
        assertFalse(registry.coverExactExternalPhysicalTarget(version, trustedSurface))
        attached.close()
    }

    @Test fun oldGenerationTextureUpdateCannotUncoverReassignedExternalPresentation() {
        val registry = HostPhysicalDisplaySurfaceRegistry(releaseSurface = {}, releaseTexture = {})
        fun publish(surface: Surface, texture: SurfaceTexture) = requireNotNull(registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            surface, texture, 800, 480,
            registry.newExternalPhysicalTargetTrustToken(externalCalibrationForTest(
                pixels = PixelSize(800, 480),
            )).also {
                it.bindRouteProof { true }
                it.bindLegacyDecoderProof { true }
            },
        ))
        val oldSurface = surface()
        val oldTexture = texture()
        val oldVersion = publish(oldSurface, oldTexture)
        var oldCovered = true
        val old = requireNotNull(registry.bindExternalClusterUiCover(
            oldSurface, oldTexture, 4, 101L,
            ExternalClusterUiCoverGate { _, state -> oldCovered = state; true },
        ))
        assertTrue(old.onTextureUpdated(oldTexture, 9L))
        assertFalse(oldCovered)

        val newerSurface = surface()
        val newerTexture = texture()
        val newerVersion = publish(newerSurface, newerTexture)
        var newerCovered = true
        val newer = requireNotNull(registry.bindExternalClusterUiCover(
            newerSurface, newerTexture, 4, 101L,
            ExternalClusterUiCoverGate { _, state -> newerCovered = state; true },
        ))
        assertTrue(oldCovered)
        assertFalse(old.onTextureUpdated(oldTexture, 9L))
        assertTrue(newerCovered)
        assertFalse(registry.coverExternalForUltraHandoff(oldVersion, oldSurface))
        assertTrue(registry.coverExternalForUltraHandoff(newerVersion, newerSurface))
        assertTrue(newerCovered)
        old.close()
        newer.close()
    }

    @Test fun failedPresentationDismissRetryCannotReuseAnOldRevealPermit() {
        var covered = true
        val gate = ExternalClusterUiCoverGate { _, state -> covered = state; true }
        assertTrue(gate.onTextureUpdated(4L, exactCurrent = { true }, legacyRouteCurrent = { true }))
        assertFalse(covered)
        assertTrue(gate.cover(retire = true))
        var fails = true
        val dismissal = ExactExternalClusterClose(Closeable {
            if (fails) error("synthetic Presentation dismissal failure")
        })
        assertThrows(IllegalStateException::class.java) { dismissal.close() }
        assertTrue(covered)
        assertFalse(dismissal.isClosed)
        assertFalse(gate.onTextureUpdated(4L, exactCurrent = { true }, legacyRouteCurrent = { true }))
        fails = false
        dismissal.close()
        assertTrue(dismissal.isClosed)
        assertTrue(covered)
    }

    @Test fun legacyTrustRevocationBetweenInitialProbeAndGateCannotReveal() {
        var covered = true
        var current = true
        var legacyProbes = 0
        val gate = ExternalClusterUiCoverGate { _, state -> covered = state; true }
        val updated = gate.onTextureUpdated(
            timestampNanos = 5L,
            exactCurrent = { current },
            legacyRouteCurrent = {
                legacyProbes++
                if (legacyProbes == 1) {
                    current = false
                    true // the previously sampled route result races revocation
                } else current
            },
        )
        assertFalse(updated)
        assertTrue(covered)
    }

    @Test fun unacknowledgedMainThreadCoverRetainsBorrowedTargetUntilExactRetry() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {}, requestRetirement = {},
        )
        val externalSurface = surface()
        val externalTexture = texture()
        val trust = registry.newExternalPhysicalTargetTrustToken(externalCalibrationForTest(
            pixels = PixelSize(800, 480),
        )).also { it.bindRouteProof { true } }
        val version = requireNotNull(registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            externalSurface, externalTexture, 800, 480, trust,
        ))
        var coverAck = true
        var uiCovered = true
        val gate = ExternalClusterUiCoverGate { _, covered ->
            if (covered && !coverAck) false else {
                uiCovered = covered
                true
            }
        }
        val registration = requireNotNull(registry.bindExternalClusterUiCover(
            externalSurface, externalTexture, 4, 101L, gate,
        ))
        assertTrue(registry.coverExternalForUltraHandoff(version, externalSurface))
        val borrowed = requireNotNull(registry.acquirePhysicalTarget(version))
        assertTrue(gate.armUltra(42L) { true })
        assertTrue(registration.onTextureUpdated(externalTexture, 42L))
        assertFalse(uiCovered)

        coverAck = false
        trust.invalidate()
        assertFalse(gate.hasAppliedCover)
        assertTrue(borrowed.target.isValid) // no native target retirement before cover ACK
        assertTrue(registry.hasPendingRetirement())
        assertThrows(IllegalStateException::class.java) {
            registry.retryPendingRetirements()
        }
        assertTrue(borrowed.target.isValid)
        coverAck = true
        registry.retryPendingRetirements()
        assertTrue(uiCovered)
        assertFalse(borrowed.target.isValid)
        borrowed.retireAfterEglProof()
        registration.close()
    }

    /** A trusted CLUSTER after Ultra handoff, with HWUI-ordered latched timestamps. */
    private class LatchedCluster(owner: ExternalClusterUiCoverGateTest) {
        val latched = java.util.concurrent.atomic.AtomicLong(0L)
        val redraws = java.util.concurrent.atomic.AtomicInteger(0)
        val watchdog = ManualWatchdog()
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {}, coverWatchdogScheduler = watchdog,
            readLatchedTimestamp = { latched.get() },
        )
        val surface = owner.surface()
        val texture = owner.texture()
        var covered = true
        val gate = ExternalClusterUiCoverGate { _, state -> covered = state; true }
        val registration: HostPhysicalDisplaySurfaceRegistry.ExternalClusterUiCoverRegistration
        init {
            val trust = registry.newExternalPhysicalTargetTrustToken(externalCalibrationForTest(
                pixels = PixelSize(800, 480),
            )).also {
                it.bindRouteProof { true }
                it.bindLegacyDecoderProof { true }
            }
            val version = requireNotNull(registry.publish(
                DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                surface, texture, 800, 480, trust,
            ))
            registration = requireNotNull(registry.bindExternalClusterUiCover(
                surface, texture, 4, 101L, gate, requestRedraw = { redraws.incrementAndGet() },
            ))
            assertTrue(registry.coverExternalForUltraHandoff(version, surface))
            requireNotNull(registry.acquirePhysicalTarget(version))
        }
    }

    @Test fun clusterStaleCallbackTimestampCannotRevealANewerLatchedFrame() {
        val cluster = LatchedCluster(this)
        assertTrue(cluster.gate.armUltra(42L) { true })
        // Draw latching T=42: its callback still reads the previous image S=41.
        assertFalse(cluster.registration.onTextureCallback(cluster.texture, 41L))
        assertTrue(cluster.covered)
        cluster.latched.set(42L)
        assertTrue(cluster.registration.observeLatchedFrame(cluster.texture))
        assertFalse(cluster.covered)
        // Producer queues U=43; that draw's callback reads T=42, which must not keep MAIN open.
        assertFalse(cluster.registration.onTextureCallback(cluster.texture, 42L))
        cluster.latched.set(43L)
        assertFalse(cluster.registration.observeLatchedFrame(cluster.texture))
        assertTrue("Unproven instrument content was left revealed", cluster.covered)
    }

    @Test fun clusterStaticPermittedFrameLatchedWithoutLaterCallbackRevealsByWatchdog() {
        val cluster = LatchedCluster(this)
        cluster.latched.set(41L) // an older image is latched; the permit frame is queued
        assertTrue(cluster.gate.armUltra(42L) { true })
        repeat(20) { cluster.watchdog.advance() }
        assertTrue(cluster.covered)
        assertEquals(5, cluster.redraws.get())
        cluster.latched.set(42L) // no further onSurfaceTextureUpdated ever arrives
        cluster.watchdog.advance()
        assertFalse(cluster.covered)
    }

    @Test fun newerPermitArmedDuringRejectedFrameProbeSurvivesItsCover() {
        var uiCovered = true
        lateinit var gate: ExternalClusterUiCoverGate
        gate = ExternalClusterUiCoverGate { _, covered -> uiCovered = covered; true }
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltra(42L) { true })
        var probes = 0
        // A newer latched frame (43) rejects P1; the producer arms P2 while exactCurrent runs.
        assertFalse(gate.onTextureUpdated(43L, {
            if (probes++ == 0) assertTrue(gate.armUltra(44L) { true })
            true
        }, { false }))
        assertTrue(uiCovered)
        assertEquals(44L, gate.coveredPermitTimestampNanos)
        assertTrue(gate.onTextureUpdated(44L, { true }, { false }))
        assertFalse(uiCovered)
    }

    @Test fun newerPermitArmedDuringFinalRevealCheckSurvivesItsCover() {
        var uiCovered = true
        lateinit var gate: ExternalClusterUiCoverGate
        gate = ExternalClusterUiCoverGate { _, covered -> uiCovered = covered; true }
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltra(42L) { true })
        var probes = 0
        // P1's frame passes the first check; P2 is armed before the final, locked re-check.
        assertFalse(gate.onTextureUpdated(42L, {
            if (probes++ == 0) assertTrue(gate.armUltra(44L) { true })
            true
        }, { false }))
        assertTrue(uiCovered)
        assertEquals(44L, gate.coveredPermitTimestampNanos)
        assertTrue(gate.onTextureUpdated(44L, { true }, { false }))
        assertFalse(uiCovered)
    }

    @Test fun delayedLegacyCallbackAfterUltraTicketCannotMatchExactEglProducerTimestamp() {
        var uiCovered = true
        val gate = ExternalClusterUiCoverGate { _, covered ->
            uiCovered = covered
            true
        }
        assertTrue(gate.onTextureUpdated(31L, { true }, { true }))
        assertFalse(uiCovered)
        assertTrue(gate.claimUltra())
        assertTrue(uiCovered)
        assertTrue(gate.armUltra(42L) { true })
        assertFalse(gate.onTextureUpdated(31L, { true }, { false }))
        assertTrue(uiCovered)
        // The newer exact permit survives an older queued callback, but the old pixels do not.
        assertTrue(gate.onTextureUpdated(42L, { true }, { false }))
        assertFalse(uiCovered)
    }

    @Test fun exactLeaseWatchdogCoversWithoutAnotherTextureCallbackButAllowsFreshRecovery() {
        val watchdog = ManualWatchdog()
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {},
            coverWatchdogScheduler = watchdog,
        )
        val externalSurface = surface()
        val externalTexture = texture()
        var decoderAttached = true
        val trust = registry.newExternalPhysicalTargetTrustToken(externalCalibrationForTest(
            pixels = PixelSize(800, 480),
        )).also {
            it.bindRouteProof { true }
            it.bindLegacyDecoderProof { decoderAttached }
        }
        registry.publish(DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            externalSurface, externalTexture, 800, 480, trust)
        var covered = true
        val registration = requireNotNull(registry.bindExternalClusterUiCover(
            externalSurface, externalTexture, 4, 101L,
            ExternalClusterUiCoverGate { _, state -> covered = state; true },
        ))
        assertTrue(registration.onTextureUpdated(externalTexture, 70L))
        assertFalse(covered)
        watchdog.advance() // valid static frame is not blanked by arbitrary elapsed time
        assertFalse(covered)
        decoderAttached = false
        watchdog.advance() // no TextureView callback needed to observe lost decoder lease
        assertTrue(covered)
        decoderAttached = true
        assertTrue(registration.onTextureUpdated(externalTexture, 71L))
        assertFalse(covered)
        registration.close()
        registry.onTextureDestroyed(externalTexture)
        assertNull(watchdog.tick)
    }

    @Test fun dynamicSourceFreshnessExpiryCoversAndNewExactFramePermitRecovers() {
        var covered = true
        var sourceFresh = true
        val gate = ExternalClusterUiCoverGate { _, state -> covered = state; true }
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltra(90L) { sourceFresh })
        assertTrue(gate.onTextureUpdated(90L, { true }, { false }))
        assertFalse(covered)
        sourceFresh = false // signed owner's source-age deadline elapsed without callbacks
        assertFalse(gate.recheckCurrent({ true }, { false }))
        assertTrue(covered)
        sourceFresh = true
        assertTrue(gate.armUltra(91L) { sourceFresh })
        assertTrue(gate.onTextureUpdated(91L, { true }, { false }))
        assertFalse(covered)
    }

    @Test fun orderedNewFramePermitCanAdvanceVisibleInstrumentWithoutOldCallbackReveal() {
        var covered = true
        val gate = ExternalClusterUiCoverGate { _, state -> covered = state; true }
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltra(100L) { true })
        assertTrue(gate.onTextureUpdated(100L, { true }, { false }))
        assertFalse(covered)

        assertTrue(gate.armUltra(101L) { true }) // preserve the proven old frame until update
        assertFalse(covered)
        assertFalse(gate.onTextureUpdated(100L, { true }, { false }))
        assertTrue(covered) // old callback cannot reveal while newer permit is pending
        assertTrue(gate.onTextureUpdated(101L, { true }, { false }))
        assertFalse(covered)
        assertFalse(gate.armUltra(100L) { true }) // no replay of older producer timestamp

        assertTrue(gate.armUltra(102L, clearBeforeReveal = true) { true })
        assertTrue(covered) // safety-clear transition hides numeric pixels immediately
        assertFalse(gate.onTextureUpdated(103L, { true }, { false }))
        assertTrue(covered) // unproved newer buffer invalidates the pending timestamp
        assertTrue(gate.armUltra(104L) { true })
        assertTrue(gate.onTextureUpdated(104L, { true }, { false }))
        assertFalse(covered)
    }

    @Test fun failedUiCoverAckDoesNotConsumeExactFrameTimestampOnRetry() {
        var acknowledgeCover = true
        var covered = true
        val gate = ExternalClusterUiCoverGate { _, state ->
            if (state && !acknowledgeCover) false else {
                covered = state
                true
            }
        }
        assertTrue(gate.claimUltra())
        acknowledgeCover = false
        assertFalse(gate.cover()) // main-thread application timed out
        assertFalse(gate.hasAppliedCover)
        assertFalse(gate.armUltra(200L) { true })
        acknowledgeCover = true
        assertTrue(gate.cover()) // exact same UI owner acknowledges the retry
        assertTrue(gate.armUltra(200L) { true }) // no second EGL frame required
        assertTrue(gate.onTextureUpdated(200L, { true }, { false }))
        assertFalse(covered)
    }

    @Test fun exactBorrowedExternalCoverIsNonterminalAndAllowsOnlyANewFramePermit() {
        val registry = HostPhysicalDisplaySurfaceRegistry(releaseSurface = {}, releaseTexture = {})
        val externalSurface = surface()
        val externalTexture = texture()
        val trust = registry.newExternalPhysicalTargetTrustToken(externalCalibrationForTest(
            pixels = PixelSize(800, 480),
        )).also { it.bindRouteProof { true } }
        val version = requireNotNull(registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            externalSurface, externalTexture, 800, 480, trust,
        ))
        var uiCovered = true
        val gate = ExternalClusterUiCoverGate { _, covered ->
            uiCovered = covered
            true
        }
        val registration = requireNotNull(registry.bindExternalClusterUiCover(
            externalSurface, externalTexture, 4, 101L, gate,
        ))
        assertFalse(registry.coverExactBorrowedExternalPhysicalTarget(version, externalSurface))
        val borrowed = requireNotNull(registry.acquirePhysicalTarget(version))
        assertTrue(gate.armUltra(201L) { true })
        assertTrue(registration.onTextureUpdated(externalTexture, 201L))
        assertFalse(uiCovered)

        assertTrue(registry.coverExactBorrowedExternalPhysicalTarget(version, externalSurface))
        assertTrue(uiCovered)
        assertTrue(borrowed.target.isValid) // no EGL retirement or native Surface release
        assertFalse(registration.onTextureUpdated(externalTexture, 201L))
        assertTrue(uiCovered) // the pre-RESET frame may not reveal again
        assertTrue(gate.armUltra(202L) { true })
        assertTrue(registration.onTextureUpdated(externalTexture, 202L))
        assertFalse(uiCovered) // the same exact target can recover with a new permit

        borrowed.retireAfterEglProof()
        registration.close()
    }

    @Test fun exactBorrowedExternalCoverDeniesPreviewStaleAndUntrustedTargets() {
        val registry = HostPhysicalDisplaySurfaceRegistry(releaseSurface = {}, releaseTexture = {})
        val previewSurface = surface()
        val preview = requireNotNull(registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PREVIEW_ONLY,
            previewSurface, texture(), 800, 480,
        ))
        assertFalse(registry.coverExactBorrowedExternalPhysicalTarget(preview, previewSurface))

        data class Published(
            val version: HostDisplaySurfaceVersion,
            val surface: Surface,
            val texture: SurfaceTexture,
            val trust: ExternalPhysicalTargetTrustToken,
        )
        fun publishExternal(): Published {
            val externalSurface = surface()
            val externalTexture = texture()
            val trust = registry.newExternalPhysicalTargetTrustToken(externalCalibrationForTest(
                pixels = PixelSize(800, 480),
            )).also { it.bindRouteProof { true } }
            val version = requireNotNull(registry.publish(
                DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                externalSurface, externalTexture, 800, 480, trust,
            ))
            return Published(version, externalSurface, externalTexture, trust)
        }
        val old = publishExternal()
        val oldRegistration = requireNotNull(registry.bindExternalClusterUiCover(
            old.surface, old.texture, 4, 101L, ExternalClusterUiCoverGate { _, _ -> true },
        ))
        assertFalse(registry.coverExactBorrowedExternalPhysicalTarget(old.version, old.surface))
        val oldBorrow = requireNotNull(registry.acquirePhysicalTarget(old.version))
        assertTrue(registry.coverExactBorrowedExternalPhysicalTarget(old.version, old.surface))
        val newer = publishExternal()
        val newRegistration = requireNotNull(registry.bindExternalClusterUiCover(
            newer.surface, newer.texture, 4, 101L, ExternalClusterUiCoverGate { _, _ -> true },
        ))
        assertFalse(registry.coverExactBorrowedExternalPhysicalTarget(old.version, old.surface))
        assertFalse(registry.coverExactBorrowedExternalPhysicalTarget(old.version, newer.surface))
        assertFalse(registry.coverExactBorrowedExternalPhysicalTarget(newer.version, newer.surface))
        val newBorrow = requireNotNull(registry.acquirePhysicalTarget(newer.version))
        assertTrue(registry.coverExactBorrowedExternalPhysicalTarget(newer.version, newer.surface))
        newer.trust.invalidate()
        assertFalse(registry.coverExactBorrowedExternalPhysicalTarget(newer.version, newer.surface))
        oldBorrow.retireAfterEglProof()
        newBorrow.retireAfterEglProof()
        oldRegistration.close()
        newRegistration.close()
    }

    @Test fun failedExactBorrowedExternalCoverAckLeavesGateCoveredAndRetryable() {
        val registry = HostPhysicalDisplaySurfaceRegistry(releaseSurface = {}, releaseTexture = {})
        val externalSurface = surface()
        val externalTexture = texture()
        val trust = registry.newExternalPhysicalTargetTrustToken(externalCalibrationForTest(
            pixels = PixelSize(800, 480),
        )).also { it.bindRouteProof { true } }
        val version = requireNotNull(registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            externalSurface, externalTexture, 800, 480, trust,
        ))
        var acknowledgeCover = true
        var uiCovered = true
        val gate = ExternalClusterUiCoverGate { _, covered ->
            if (covered && !acknowledgeCover) false else {
                uiCovered = covered
                true
            }
        }
        val registration = requireNotNull(registry.bindExternalClusterUiCover(
            externalSurface, externalTexture, 4, 101L, gate,
        ))
        val borrowed = requireNotNull(registry.acquirePhysicalTarget(version))
        assertTrue(gate.armUltra(301L) { true })
        assertTrue(registration.onTextureUpdated(externalTexture, 301L))
        assertFalse(uiCovered)

        acknowledgeCover = false
        assertFalse(registry.coverExactBorrowedExternalPhysicalTarget(version, externalSurface))
        assertTrue(gate.isCovered)
        assertFalse(gate.hasAppliedCover)
        assertFalse(gate.armUltra(302L) { true })
        acknowledgeCover = true
        assertTrue(registry.coverExactBorrowedExternalPhysicalTarget(version, externalSurface))
        assertTrue(uiCovered)
        assertTrue(gate.armUltra(302L) { true })
        assertTrue(registration.onTextureUpdated(externalTexture, 302L))
        assertFalse(uiCovered)

        borrowed.retireAfterEglProof()
        registration.close()
    }
}
