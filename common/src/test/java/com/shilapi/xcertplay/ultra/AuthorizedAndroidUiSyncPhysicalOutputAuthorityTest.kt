package com.shilapi.xcertplay.ultra

import android.content.ContextWrapper
import com.shilapi.xcertplay.AuthorizedUltraDisplayLeaseTestFixture
import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifestIssuer
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifest
import com.shilapi.xcertplay.CompiledUiSyncDisplayAssignment
import com.shilapi.xcertplay.CompiledUiSyncRuntimeContract
import com.shilapi.xcertplay.CompiledUiSyncThemeProgramSelection
import com.shilapi.xcertplay.CarPlayWebDisplayConfig
import com.shilapi.xcertplay.CarPlayWebDisplayFactory
import com.shilapi.xcertplay.ExternalPhysicalTargetTrustToken
import com.shilapi.xcertplay.ExternalClusterUiCoverGate
import com.shilapi.xcertplay.ExternalClusterUiCoverWatchdogScheduler
import com.shilapi.xcertplay.MainPhysicalUiCoverGate
import com.shilapi.xcertplay.MainPhysicalUiPermitStatus
import com.shilapi.xcertplay.externalCalibrationForTest
import com.shilapi.xcertplay.HostDisplaySurfaceKind
import com.shilapi.xcertplay.HostPhysicalDisplaySurfaceRegistry
import com.shilapi.xcertplay.UltraProfileActivationRequest
import com.shilapi.xcertplay.UltraProfileActivationCoordinator
import com.shilapi.xcertplay.UltraProfileSessionStartResult
import com.shilapi.xcertplay.UltraProfileSessionToken
import com.shilapi.xcertplay.UltraProfilePhysicalGenerationStatus
import com.shilapi.xcertplay.UltraRuntimeIdentity
import com.shilapi.xcertplay.WebDisplayChannel
import com.shilapi.xcertplay.deriveVerifiedUiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.RendererReadinessReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncClusterPhoneEpochCoverResult
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestination
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestinationRole
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAssignment
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBackendLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputPreflightContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRequirement
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRoute
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputSessionContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalTargetLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalTargetVersion
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSource
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSubscription
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenCandidate
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneState
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.DisplayType
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.media.AndroidVideoSurfaceRetirementResult
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.AndroidSurfaceReservationState
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendPhase
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendRetirementResult
import com.shilapi.xcertplay.ultra.egl.UltraEglResolvedSubmission
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class AuthorizedAndroidUiSyncPhysicalOutputAuthorityTest {
    @Test fun clusterPhoneEpochGateRequiresAckAndStrictlyNewPresentedSceneAndProducer() {
        val gate = ExternalClusterPhoneEpochGate()
        val old = requireNotNull(gate.admissionEpoch(17L, 19L))
        assertTrue(gate.commitArmed(old, 17L, 19L))
        val reset = gate.beginCover()
        assertFalse(gate.isCurrent(old))
        assertNull(gate.admissionEpoch(18L, 20L))
        assertTrue(gate.acknowledge(reset))
        assertNull(gate.admissionEpoch(17L, 20L))
        assertNull(gate.admissionEpoch(18L, 19L))
        val fresh = requireNotNull(gate.admissionEpoch(18L, 20L))
        assertTrue(gate.commitArmed(fresh, 18L, 20L))

        val secondReset = gate.beginCover()
        assertTrue(gate.isSuperseded(reset))
        assertFalse(gate.acknowledge(reset))
        assertNull(gate.admissionEpoch(19L, 21L))
        assertTrue(gate.acknowledge(secondReset))
        assertNull(gate.admissionEpoch(18L, 21L))
        assertNull(gate.admissionEpoch(19L, 20L))
        assertNotNull(gate.admissionEpoch(19L, 21L))
    }

    @Test fun clusterRecoveryBeforeHostAckIsTemporaryAndParkedSceneIsTakenOnlyByThatAck() {
        val gate = ExternalClusterPhoneEpochGate()
        val initial = gate.recoveryAdmission(17L, 19L) as ClusterRecoveryAdmission.Admitted
        assertTrue(gate.commitArmed(initial.epoch, 17L, 19L))
        val reset = gate.beginCover()
        assertEquals(ClusterRecoveryAdmission.AwaitingAck(reset), gate.recoveryAdmission(18L, 20L))
        assertEquals(ClusterRecoveryAdmission.Refused, gate.recoveryAdmission(17L, 20L))
        assertEquals(ClusterRecoveryAdmission.Refused, gate.recoveryAdmission(18L, 19L))
        // Pending ACK + fresh type-3 witness: retry the same sealed replay, never drop it.
        assertEquals(InstrumentReplayPermitDecision.BUSY_RETRY, clusterRecoveryReplayEpochDecision(
            gate.recoveryAdmission(18L, 20L)) { true })
        // Without the fresh witness, or below the floors, it stays final.
        assertEquals(InstrumentReplayPermitDecision.REVOKED, clusterRecoveryReplayEpochDecision(
            gate.recoveryAdmission(18L, 20L)) { false })
        assertEquals(InstrumentReplayPermitDecision.REVOKED, clusterRecoveryReplayEpochDecision(
            gate.recoveryAdmission(17L, 20L)) { true })

        val stale = Any()
        assertFalse(gate.parkUntilAcknowledged(17L, 20L, stale))
        assertTrue(gate.parkUntilAcknowledged(18L, 20L, stale))
        val superseding = gate.beginCover() // A newer RESET drops the older nomination.
        assertFalse(gate.hasParkedPresentationForTest())
        val fresh = Any()
        assertTrue(gate.parkUntilAcknowledged(18L, 20L, fresh))
        assertFalse(gate.acknowledge(reset))
        assertEquals(null, gate.takeParkedPresentation(reset))
        assertTrue(gate.hasParkedPresentationForTest())
        assertTrue(gate.acknowledge(superseding))
        assertSame(fresh, gate.takeParkedPresentation(superseding))
        assertFalse(gate.hasParkedPresentationForTest())
        // After the ACK, admission is direct; nothing more can be parked for this epoch.
        assertFalse(gate.parkUntilAcknowledged(18L, 20L, Any()))
        assertEquals(null, clusterRecoveryReplayEpochDecision(
            gate.recoveryAdmission(18L, 20L)) { false })
    }

    @Test fun clusterCoverTicketAckDrainsSceneParkedBeforeIt() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        try {
            val clusterId = requireNotNull(fixture.outputs[1].uiSyncDisplayId).value
            val ticket = requireNotNull(fixture.authority.beginClusterPhoneEpochCover(
                context, batch, clusterId,
            ))
            val gate = fixture.clusterPhoneEpochGateForTest()
            assertTrue(gate.parkUntilAcknowledged(1L, 1L, Any()))
            fixture.failNextUiCover = true
            assertEquals(UiSyncClusterPhoneEpochCoverResult.RETRY, ticket.coverExactClusterUi())
            assertTrue(gate.hasParkedPresentationForTest()) // A failed Host ACK keeps it parked.
            assertEquals(UiSyncClusterPhoneEpochCoverResult.ACKNOWLEDGED,
                ticket.coverExactClusterUi())
            assertFalse(gate.hasParkedPresentationForTest())
            assertTrue(batch.isCurrent)
            assertEquals(0, fixture.binder.retirements)
        } finally {
            batch.close()
        }
    }

    @Test fun clusterResetRevokesOldExternalPermitBeforeRetryableHostAckWithoutCoveringMain() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        try {
            fixture.simulateMainPostSwapTextureUpdate()
            val gate = fixture.clusterPhoneEpochGateForTest()
            val oldEpoch = requireNotNull(gate.admissionEpoch(17L, 19L))
            val oldPermit = fixture.externalPermitForTest(gate, oldEpoch, 19L)
            assertTrue(fixture.presentExternalPermit(oldPermit, 19L))
            assertTrue(gate.commitArmed(oldEpoch, 17L, 19L))
            assertFalse(fixture.firstUiCovered)
            assertFalse(fixture.secondUiCovered)
            val mainSequence = fixture.firstUiGate.currentSequence
            val externalSequence = fixture.secondCoverSequence

            val clusterId = requireNotNull(fixture.outputs[1].uiSyncDisplayId).value
            val ticket = requireNotNull(fixture.authority.beginClusterPhoneEpochCover(
                context, batch, clusterId,
            ))
            assertFalse(oldPermit.isCurrentFor(fixture.secondVersion, fixture.secondSurface))
            assertEquals(externalSequence, fixture.secondCoverSequence)
            assertEquals(mainSequence, fixture.firstUiGate.currentSequence)
            fixture.failNextUiCover = true
            assertEquals(UiSyncClusterPhoneEpochCoverResult.RETRY,
                ticket.coverExactClusterUi())
            assertFalse(fixture.secondUiCovered) // failed Host ACK is not a fake cover
            assertEquals(UiSyncClusterPhoneEpochCoverResult.ACKNOWLEDGED,
                ticket.coverExactClusterUi())
            assertTrue(fixture.secondUiCovered)
            assertFalse(fixture.firstUiCovered)
            assertTrue(batch.isCurrent)
            assertEquals(0, fixture.binder.retirements)

            assertNull(gate.admissionEpoch(17L, 20L))
            val freshEpoch = requireNotNull(gate.admissionEpoch(18L, 20L))
            val freshPermit = fixture.externalPermitForTest(gate, freshEpoch, 20L)
            assertTrue(fixture.presentExternalPermit(freshPermit, 20L))
            assertTrue(gate.commitArmed(freshEpoch, 18L, 20L))
            assertFalse(fixture.secondUiCovered)
            val second = requireNotNull(fixture.authority.beginClusterPhoneEpochCover(
                context, batch, clusterId,
            ))
            assertFalse(freshPermit.isCurrentFor(fixture.secondVersion, fixture.secondSurface))
            assertEquals(UiSyncClusterPhoneEpochCoverResult.SUPERSEDED,
                ticket.coverExactClusterUi())
            assertEquals(UiSyncClusterPhoneEpochCoverResult.ACKNOWLEDGED,
                second.coverExactClusterUi())
            assertTrue(fixture.secondUiCovered)
            assertFalse(fixture.firstUiCovered)
            assertEquals(0, fixture.binder.retirements)
        } finally {
            batch.close()
        }
    }

    @Test fun clusterEpochCoverRejectsForeignIdentityAndCannotCoverReplacementOrClosedBatch() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        val clusterId = requireNotNull(fixture.outputs[1].uiSyncDisplayId).value
        val sequence = fixture.secondCoverSequence
        assertNull(fixture.authority.beginClusterPhoneEpochCover(
            fixture.sessionContext(), batch, clusterId,
        ))
        assertNull(fixture.authority.beginClusterPhoneEpochCover(
            context, batch, requireNotNull(fixture.outputs[0].uiSyncDisplayId).value,
        ))
        assertNull(fixture.authority.beginClusterPhoneEpochCover(
            context, batch, "foreign-cluster",
        ))
        assertEquals(sequence, fixture.secondCoverSequence)

        val ticket = requireNotNull(fixture.authority.beginClusterPhoneEpochCover(
            context, batch, clusterId,
        ))
        fixture.publishSecond(HostDisplaySurfaceKind.PHYSICAL_EXTERNAL)
        val successorSequence = fixture.secondCoverSequence
        assertEquals(UiSyncClusterPhoneEpochCoverResult.REVOKED,
            ticket.coverExactClusterUi())
        assertEquals(successorSequence, fixture.secondCoverSequence)
        batch.close()
        assertEquals(UiSyncClusterPhoneEpochCoverResult.REVOKED,
            ticket.coverExactClusterUi())
    }

    @Test fun terminalCloseAfterAcknowledgedClusterResetRetiresExactBorrowedGate() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        val clusterId = requireNotNull(fixture.outputs[1].uiSyncDisplayId).value
        val ticket = requireNotNull(fixture.authority.beginClusterPhoneEpochCover(
            context, batch, clusterId,
        ))
        assertEquals(UiSyncClusterPhoneEpochCoverResult.ACKNOWLEDGED,
            ticket.coverExactClusterUi())
        assertTrue(batch.isCurrent)
        assertEquals(0, fixture.binder.retirements)

        assertTrue(fixture.authority.revokeBoundSessionForStop(context, batch))
        assertEquals(UiSyncClusterPhoneEpochCoverResult.REVOKED,
            ticket.coverExactClusterUi())
        assertTrue(fixture.authority.coverBoundSessionForClose(context, batch))
        batch.close()
        assertEquals(1, fixture.binder.retirements)
        assertTrue(fixture.firstUiCovered)
        assertTrue(fixture.secondUiCovered)
    }

    @Test fun mainRecoverRequiresNewSignedSceneRevisionAndProducerTimestamp() {
        val gate = MainNonterminalCoverGeneration()
        val oldPermitEpoch = requireNotNull(gate.admitPresented(17L, 19L))
        assertTrue(gate.isCurrent(oldPermitEpoch))

        gate.coverAfterSubmittedScene(17L)
        assertFalse(gate.isCurrent(oldPermitEpoch))
        assertNull(gate.admitPresented(17L, 20L))
        assertNull(gate.admitPresented(18L, 19L))
        val freshEpoch = requireNotNull(gate.admitPresented(18L, 20L))
        assertTrue(gate.isCurrent(freshEpoch))

        gate.coverAfterSubmittedScene(19L)
        assertFalse(gate.isCurrent(freshEpoch))
        assertNull(gate.admitPresented(19L, 21L))
        assertNull(gate.admitPresented(20L, 20L))
        assertNotNull(gate.admitPresented(20L, 21L))
    }

    @Test fun unchangedMainSceneAllowsOnlyTwoMissedFrameReproofReservations() {
        val budget = MainSignedSceneReproofBudget()
        val reservations = ArrayList<Boolean>()
        var covered = false
        val gate = MainPhysicalUiCoverGate { _, state -> covered = state; true }
        assertTrue(gate.claimUltra())

        listOf(10L, 20L, 30L).forEach { expected ->
            assertTrue(gate.armUltraWithStatus(
                expected,
                onMissedFrame = { reservations += budget.reserve() },
                authorization = { MainPhysicalUiPermitStatus.CURRENT },
            ))
            assertTrue(gate.onTextureUpdated(expected) { true })
            assertFalse(gate.onTextureUpdated(expected + 1L) { true })
            assertTrue("A missed frame must leave MAIN covered", covered)
            assertTrue(gate.hasAppliedCover)
        }
        assertEquals(listOf(true, true, false), reservations)
        assertTrue(budget.isExhausted())
        assertFalse(budget.reserve())
        assertTrue(covered)
    }

    @Test fun exactPresentedMainPermitCanWaitCoveredThroughBusyAndRevealOnCurrent() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        val nonce = requireNotNull(AuthorizedAndroidUiSyncPhysicalOutputAuthority::class.java
            .getDeclaredField("mainCoverIssuerNonce").apply { isAccessible = true }
            .get(fixture.authority))
        val status = AtomicReference(MainPhysicalUiPermitStatus.BUSY)
        val permit = AuthorizedMainPhysicalUiPermit(
            fixture.authority, nonce, fixture.firstVersion, fixture.firstSurface,
            fixture.mainActivityGeneration, 21L, batch.generation, 1L, 17L,
            authorizationStatusProbe = { status.get() },
            stillAuthorized = { status.get() == MainPhysicalUiPermitStatus.CURRENT },
        )
        assertEquals(MainPhysicalUiPermitStatus.BUSY, permit.authorizationFor(
            fixture.firstVersion, fixture.firstSurface, fixture.mainActivityGeneration,
        ))
        assertFalse(fixture.registry.armMainAfterPresentedScene(permit))
        assertTrue(fixture.firstUiGate.hasPendingUltraPermit)
        assertFalse(fixture.firstUiRegistration.onTextureUpdated(fixture.firstTexture, 21L))
        assertTrue(fixture.firstUiCovered)

        status.set(MainPhysicalUiPermitStatus.CURRENT)
        assertTrue(fixture.firstUiRegistration.onTextureUpdated(fixture.firstTexture, 21L))
        assertFalse(fixture.firstUiCovered)
        batch.close()
    }

    @Test fun mainRegistrationReplaysAlreadyObservedNewerFrameAsMissedPermit() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        val nonce = requireNotNull(AuthorizedAndroidUiSyncPhysicalOutputAuthority::class.java
            .getDeclaredField("mainCoverIssuerNonce").apply { isAccessible = true }
            .get(fixture.authority))
        val misses = ArrayList<Long>()

        assertFalse(fixture.firstUiRegistration.onTextureUpdated(fixture.firstTexture, 22L))
        assertTrue(fixture.firstUiCovered)
        val permit = AuthorizedMainPhysicalUiPermit(
            fixture.authority, nonce, fixture.firstVersion, fixture.firstSurface,
            fixture.mainActivityGeneration, 21L, batch.generation, 1L, 17L,
            onMissedFrame = { observed -> misses += observed },
            stillAuthorized = { batch.isCurrent },
        )
        assertTrue(fixture.registry.armMainAfterPresentedScene(permit))
        assertTrue(fixture.firstUiCovered)
        assertTrue(fixture.firstUiGate.hasAppliedCover)
        assertEquals(listOf(22L), misses)

        assertFalse(fixture.firstUiRegistration.onTextureUpdated(fixture.firstTexture, 23L))
        assertEquals(listOf(22L), misses)
        batch.close()
    }

    @Test fun busyMainPermitReplaysAlreadyObservedNewerFrameWhenWatchdogPromotesIt() {
        val watchdog = ManualCoverWatchdog()
        val fixture = Fixture(watchdog)
        requireNotNull(fixture.preflight())
        fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        val nonce = requireNotNull(AuthorizedAndroidUiSyncPhysicalOutputAuthority::class.java
            .getDeclaredField("mainCoverIssuerNonce").apply { isAccessible = true }
            .get(fixture.authority))
        val status = AtomicReference(MainPhysicalUiPermitStatus.BUSY)
        val misses = ArrayList<Long>()

        assertFalse(fixture.firstUiRegistration.onTextureUpdated(fixture.firstTexture, 22L))
        val permit = AuthorizedMainPhysicalUiPermit(
            fixture.authority, nonce, fixture.firstVersion, fixture.firstSurface,
            fixture.mainActivityGeneration, 21L, batch.generation, 1L, 17L,
            authorizationStatusProbe = { status.get() },
            onMissedFrame = { observed -> misses += observed },
            stillAuthorized = { status.get() == MainPhysicalUiPermitStatus.CURRENT },
        )
        assertFalse(fixture.registry.armMainAfterPresentedScene(permit))
        assertTrue(fixture.firstUiGate.hasPendingUltraPermit)
        assertTrue(misses.isEmpty())

        status.set(MainPhysicalUiPermitStatus.CURRENT)
        watchdog.tick()
        assertFalse(fixture.firstUiGate.hasPendingUltraPermit)
        assertTrue(fixture.firstUiCovered)
        assertTrue(fixture.firstUiGate.hasAppliedCover)
        assertEquals(listOf(22L), misses)
        watchdog.tick()
        assertEquals(listOf(22L), misses)
        batch.close()
    }

    private class ManualCoverWatchdog : ExternalClusterUiCoverWatchdogScheduler {
        private val rechecks = ArrayList<() -> Unit>()
        override fun schedule(recheck: () -> Unit): Closeable {
            rechecks += recheck
            return Closeable { rechecks.remove(recheck) }
        }
        fun tick() = rechecks.toList().forEach { it() }
    }

    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid() = true
    }

    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
        .apply { isAccessible = true }.get(null) as Unsafe
    private fun surface(): Surface = unsafe.allocateInstance(ValidSurface::class.java) as Surface
    private fun texture(): SurfaceTexture = unsafe.allocateInstance(SurfaceTexture::class.java) as SurfaceTexture

    @Test fun preflightIsReadOnlyAndRejectsPreviewAndChangedPhysicalVersion() {
        val fixture = Fixture()
        fixture.publishSecond(HostDisplaySurfaceKind.PREVIEW_ONLY)
        assertNull(fixture.preflight())
        assertEquals(0, fixture.handoff.calls)
        fixture.publishSecond(HostDisplaySurfaceKind.PHYSICAL_EXTERNAL)
        val available = requireNotNull(fixture.preflight())
        assertTrue(available.isCurrent)
        assertEquals(0, fixture.handoff.calls)
        fixture.publishSecond(HostDisplaySurfaceKind.PHYSICAL_EXTERNAL)
        assertFalse(available.isCurrent)
        assertNull(fixture.preflight()) // one provisioning cannot silently switch target versions
        assertEquals(0, fixture.handoff.calls)
    }

    @Test fun constructionProbeBeforeManifestSealNeverRegistersOrBindsPhysicalOutput() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        val probeCoordinator = fixture.newProbeCoordinator()
        val probe = FakeChild(fixture.schema, fixture.binding,
            fixture.child.readinessReceipt, probeCoordinator)
        assertNull(fixture.authority.registerRendererSessionIfSealed(probe, probeCoordinator))
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext(probe))
        }
        assertEquals(0, fixture.handoff.calls)
        probe.close()
        val realRegistration = fixture.register()
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.registerRendererSessionIfSealed(
                FakeChild(fixture.schema, fixture.binding,
                    fixture.child.readinessReceipt, probeCoordinator),
                probeCoordinator,
            )
        }
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        assertTrue(batch.isCurrent)
        batch.close()
        realRegistration.close()
    }

    @Test fun exactSessionBatchRetiresLegacyFirstAndReleasesHostOnlyAfterEglFence() {
        val fixture = Fixture()
        val available = requireNotNull(fixture.preflight())
        assertTrue(available.isCurrent)
        val registration = fixture.register()
        fixture.binder.onPrepare = { assertEquals(0, fixture.handoff.externalStarts) }
        fixture.binder.onCommit = { assertEquals(2, fixture.handoff.externalStarts) }
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        assertTrue(batch.isCurrent)
        assertEquals(2, fixture.handoff.calls)
        assertEquals(1, fixture.binder.commits)
        assertEquals(0, fixture.handoff.closes)
        assertNull(fixture.registry.acquirePhysicalTarget(fixture.firstVersion))
        batch.close()
        assertFalse(batch.isCurrent)
        assertEquals(1, fixture.binder.retirements)
        assertEquals(2, fixture.handoff.closes)
        assertTrue(fixture.firstUiCovered)
        assertNull(fixture.registry.acquirePhysicalTarget(fixture.firstVersion))
        registration.close()
        fixture.coordinator.close()
    }

    @Test fun busyAa05ProbeKeepsPhysicalBatchRecoverableUntilCurrentOrRevoked() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        val registration = fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        try {
            assertEquals(UiSyncPhysicalGenerationStatus.CURRENT, batch.generationStatus)
            fixture.generationStatusOverride = UltraProfilePhysicalGenerationStatus.Busy
            assertEquals(UiSyncPhysicalGenerationStatus.BUSY, batch.generationStatus)
            assertFalse(batch.isCurrent) // compatibility boolean remains conservative
            assertEquals(0, fixture.binder.retirements)
            fixture.generationStatusOverride = null
            assertEquals(UiSyncPhysicalGenerationStatus.CURRENT, batch.generationStatus)
            assertTrue(batch.isCurrent)
            assertEquals(0, fixture.binder.retirements)

            fixture.generation.coordinator.close()
            assertEquals(UiSyncPhysicalGenerationStatus.REVOKED, batch.generationStatus)
            assertFalse(batch.isCurrent)
        } finally {
            fixture.generationStatusOverride = null
            batch.close()
            registration.close()
            fixture.coordinator.close()
        }
    }

    @Test fun localPhysicalRevocationTakesPrecedenceOverBusyAa05Generation() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        val registration = fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        try {
            fixture.generationStatusOverride = UltraProfilePhysicalGenerationStatus.Busy
            assertEquals(UiSyncPhysicalGenerationStatus.BUSY, batch.generationStatus)
            fixture.authority.revoke()
            assertEquals(UiSyncPhysicalGenerationStatus.REVOKED, batch.generationStatus)
        } finally {
            batch.close()
            registration.close()
            fixture.coordinator.close()
            fixture.generation.coordinator.close()
        }
    }

    @Test fun sceneActivationLineageRequiresExactRegisteredChildAndIssuedLiveBatch() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        val registration = fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        assertTrue(fixture.authority.ownsBoundBatch(fixture.child, batch))
        assertFalse(fixture.authority.ownsBoundBatch(FakeChild(
            fixture.schema, fixture.binding, fixture.child.readinessReceipt, fixture.coordinator,
        ), batch))
        batch.close()
        assertFalse(fixture.authority.ownsBoundBatch(fixture.child, batch))
        registration.close()
        fixture.coordinator.close()
    }

    @Test fun transferredExternalRouteIsVisibleOnlyToItsExactBoundCoordinator() {
        val fixture = Fixture()
        val context = unsafe.allocateInstance(ContextWrapper::class.java) as ContextWrapper
        val sink = AndroidMediaSink(context, videoSizes = mapOf(111 to (800 to 480)))
        val route = requireNotNull(sink.acquirePhysicalExternalLegacyRouteLease(
            111, fixture.secondSurface,
        ))
        fixture.handoff.routeFor111 = route // deterministic seam; product port proves typed transfer
        val foreign = fixture.newProbeCoordinator()
        try {
            requireNotNull(fixture.preflight())
            val registration = fixture.register()
            assertNull(fixture.authority.currentTransferredExternalRouteLease(fixture.coordinator))
            val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
            try {
                assertSame(route, fixture.authority.currentTransferredExternalRouteLease(
                    fixture.coordinator,
                ))
                assertNull(fixture.authority.currentTransferredExternalRouteLease(foreign))
            } finally {
                batch.close()
                registration.close()
            }
            assertNull(fixture.authority.currentTransferredExternalRouteLease(
                fixture.coordinator,
            ))
        } finally {
            fixture.coordinator.close()
            foreign.close()
            route.close()
            sink.close()
        }
    }

    @Test fun postBindHookRejectsARecreatedSessionContextWithoutClosingItsIssuedBatch() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        val registration = fixture.register()
        val originalContext = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(originalContext))
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.activateBoundSession(fixture.sessionContext(), batch)
        }
        assertTrue(batch.isCurrent)
        assertTrue(fixture.authority.ownsBoundBatch(fixture.child, batch))
        batch.close()
        registration.close()
        fixture.coordinator.close()
    }

    @Test fun postBindHookRejectsAnUnsealedProvisioningWithoutClaimingThemeSubmission() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        val registration = fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        val denial = assertThrows(IllegalStateException::class.java) {
            fixture.authority.activateBoundSession(context, batch)
        }
        assertTrue(denial.message.orEmpty().contains("published AA05 child"))
        assertTrue(batch.isCurrent) // callback must leave retirement to shared child owner
        batch.close()
        registration.close()
        fixture.coordinator.close()
    }

    @Test fun postBindHookRejectsAa05RevocationBeforeSceneConstruction() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        val registration = fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        fixture.generation.coordinator.close()
        assertFalse(fixture.authority.ownsBoundBatch(fixture.child, batch))
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.activateBoundSession(context, batch)
        }
        batch.close()
        registration.close()
        fixture.coordinator.close()
    }

    @Test fun postBindHookRejectsChangedHostPixelSizeAndDoesNotReuseOldMapper() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        val registration = fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        requireNotNull(fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            fixture.firstSurface, fixture.firstTexture, 801, 480,
        ))
        assertFalse(batch.isCurrent)
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.activateBoundSession(context, batch)
        }
        batch.close()
        registration.close()
        fixture.coordinator.close()
    }

    @Test fun signedSceneBindingsUseExactHostPixelsNotProfileDensityOrAndroidDpi() {
        val fixture = Fixture()
        val bindings = buildAuthorizedSignedSceneBindings(
            fixture.profile, fixture.rows,
            listOf(DisplayDestination.MAIN to PixelSize(1600, 960),
                DisplayDestination.CLUSTER to PixelSize(800, 480)),
        )
        assertEquals(listOf(PixelSize(1600, 960), PixelSize(800, 480)),
            bindings.map { it.viewportPixels })
        assertEquals(AxisAlignedPixelAffine(2.0, 2.0, 0.0, 0.0),
            bindings[0].pixelMapper.affine)
        assertEquals(AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
            bindings[1].pixelMapper.affine)
        assertSame(fixture.profile, bindings[0].source.profile)
        assertThrows(IllegalArgumentException::class.java) {
            buildAuthorizedSignedSceneBindings(fixture.profile, fixture.rows,
                listOf(DisplayDestination.MAIN to PixelSize(1600, 960),
                    DisplayDestination.HUD to PixelSize(800, 480)))
        }
        fixture.coordinator.close()
    }

    @Test fun incompleteRendererReceiptCannotRegisterOrTouchPhysicalOutput() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        val manifest = fixture.manifest()
        fixture.authority.seal(manifest, fixture.issuer, fixture.token)
        val incomplete = FakeChild(fixture.schema, fixture.binding,
            fixture.sessionReceipt(themeCompilerActive = false), fixture.coordinator)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.authority.registerRendererSessionIfSealed(incomplete, fixture.coordinator)
        }
        assertEquals(0, fixture.handoff.calls)
        assertEquals(0, fixture.binder.commits)
        fixture.coordinator.close()
    }

    @Test fun claimedChildCannotRegisterAgainstAnotherLiveEglCoordinator() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.authority.seal(fixture.manifest(), fixture.issuer, fixture.token)
        val foreignCoordinator = fixture.newProbeCoordinator()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.authority.registerRendererSessionIfSealed(
                fixture.child, foreignCoordinator,
            )
        }
        assertEquals(0, fixture.handoff.calls)
        assertEquals(0, fixture.binder.commits)
        foreignCoordinator.close()
        fixture.coordinator.close()
    }

    @Test fun sameProfileRowsFromForeignAa05ProvisioningCannotActivateIssuedBatch() {
        val fixture = Fixture()
        val foreign = AuthorizedUltraDisplayLeaseTestFixture.open(
            fixture.profile, identity, fixture.outputs, fixture.rows,
        )
        try {
            requireNotNull(fixture.preflight())
            val registration = fixture.register()
            val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
            assertTrue(fixture.authority.ownsBoundBatch(fixture.child, batch))
            assertFalse(fixture.authority.authorizesSceneActivation(
                fixture.child, batch, foreign.runtimeLease, fixture.profile,
            ))
            fixture.generation.coordinator.close()
            assertFalse(fixture.authority.ownsBoundBatch(fixture.child, batch))
            batch.close()
            registration.close()
            fixture.coordinator.close()
        } finally {
            foreign.close()
        }
    }

    @Test fun sameProvisioningCannotRaceNewControlSessionAfterBackendFence() {
        val fixture = Fixture()
        val offer = requireNotNull(fixture.preflight())
        fixture.register()
        val first = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        first.close()
        assertFalse(offer.isCurrent)
        val denial = assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertTrue(denial.message.orEmpty().contains("busy or revoked"))

        val successor = Fixture() // reconnect receives a new per-AA05 authority/provisioning
        assertNotNull(successor.preflight())
        successor.register()
        requireNotNull(successor.authority.bindSession(successor.sessionContext())).close()
    }

    @Test fun foreignSessionAndSwappedTargetCannotTakeEitherLegacyRoute() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        val foreign = fixture.sessionContext(FakeChild(
            fixture.schema, fixture.binding, fixture.child.readinessReceipt,
            fixture.coordinator,
        ))
        assertThrows(IllegalStateException::class.java) { fixture.authority.bindSession(foreign) }
        assertEquals(0, fixture.handoff.calls)
        fixture.publishSecond(HostDisplaySurfaceKind.PHYSICAL_EXTERNAL)
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertEquals(0, fixture.handoff.calls)
        assertEquals(0, fixture.binder.commits)
    }

    @Test fun secondSurfaceHandoffFailureSafelyReleasesRetiredFirstRouteBeforeReturning() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        fixture.handoff.denyAt = 2
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertEquals(2, fixture.handoff.calls)
        assertEquals(1, fixture.handoff.closes)
        assertFalse(fixture.handoff.visibleTypes.contains(110))
        assertTrue(fixture.handoff.visibleTypes.contains(111))
        assertEquals(0, fixture.binder.commits)
        assertFalse(fixture.authority.hasPendingRetirement())
    }

    @Test fun cleanPreEglReservationReleaseFailureRetainsExactOwnerForRetry() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        fixture.handoff.denyAt = 2
        fixture.handoff.failCloseOnceForType = 110
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertTrue(fixture.authority.hasPendingRetirement())
        assertFalse(fixture.handoff.visibleTypes.contains(110))
        assertEquals(0, fixture.handoff.closes)
        assertEquals(0, fixture.binder.commits)
        assertTrue(fixture.authority.retryPendingRetirement())
        assertFalse(fixture.authority.hasPendingRetirement())
        assertEquals(1, fixture.handoff.closes)
    }

    @Test fun secondOutputBatchPreparationFailureReleasesBothLegacyRoutesBeforeExternalUse() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        fixture.binder.failOnSecondOutput = true
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertEquals(0, fixture.binder.commits)
        assertEquals(0, fixture.handoff.externalStarts)
        assertEquals(2, fixture.handoff.closes)
        assertTrue(fixture.handoff.visibleTypes.isEmpty())
        assertTrue(fixture.firstUiCovered)
        assertNull(fixture.registry.acquirePhysicalTarget(fixture.firstVersion))
        // The failed Ultra handoff cannot reborrow this still-attached Presentation generation:
        // its one-way UI cover stays retired until a fresh independently trusted publication.
        assertNull(fixture.registry.acquirePhysicalTarget(fixture.secondVersion))
    }

    @Test fun uncertainPreparedCleanupBeforeCommitRetainsOwnersUntilExactRetirementProof() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        fixture.binder.wrongPreparedGeneration = true
        fixture.binder.failPreparationCloseCount = 10
        assertThrows(AndroidUltraOutputBatchCommitFailure::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertEquals(0, fixture.handoff.externalStarts)
        assertEquals(0, fixture.binder.commits)
        assertEquals(0, fixture.handoff.closes)
        assertTrue(fixture.authority.hasPendingRetirement())
        assertNull(fixture.registry.acquirePhysicalTarget(fixture.firstVersion))
        fixture.binder.failPreparationCloseCount = 0
        assertTrue(fixture.authority.retryPendingRetirement())
        assertEquals(2, fixture.handoff.closes)
        assertFalse(fixture.authority.hasPendingRetirement())
    }

    @Test fun aa05RevocationDuringPrepareCannotEnterNativeCommit() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        fixture.binder.onPrepare = { fixture.generation.coordinator.close() }
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertEquals(0, fixture.handoff.externalStarts)
        assertEquals(0, fixture.binder.commits)
        assertEquals(2, fixture.handoff.closes) // revoked AA05 cannot resume legacy
        assertTrue(fixture.handoff.visibleTypes.isEmpty())
    }

    @Test fun hostRepublishDuringPrepareRejectsCommitAndNeverRestoresOldTarget() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        fixture.binder.onPrepare = {
            fixture.publishSecond(HostDisplaySurfaceKind.PHYSICAL_EXTERNAL)
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertEquals(0, fixture.handoff.externalStarts)
        assertEquals(0, fixture.binder.commits)
        assertEquals(2, fixture.handoff.closes)
        assertTrue(fixture.handoff.visibleTypes.isEmpty())
    }

    @Test fun rendererRegistrationRetirementDuringPrepareRejectsNativeCommit() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        val registration = fixture.register()
        fixture.binder.onPrepare = registration::close
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertEquals(0, fixture.handoff.externalStarts)
        assertEquals(0, fixture.binder.commits)
        assertEquals(2, fixture.handoff.closes)
        assertTrue(fixture.handoff.visibleTypes.isEmpty())
    }

    @Test fun rendererChildDyingDuringExternalUseGateCannotReachNativeCommit() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        fixture.handoff.onBegin = { type -> if (type == 110) fixture.child.close() }
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertFalse(fixture.child.isLive)
        assertEquals(2, fixture.handoff.externalStarts)
        assertEquals(0, fixture.binder.commits)
        assertEquals(2, fixture.handoff.closes)
    }

    @Test fun eglCoordinatorDyingDuringExternalUseGateCannotReachNativeCommit() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        fixture.handoff.onBegin = { type ->
            if (type == 110) fixture.backend.backendPhase = UltraEglBackendPhase.FAILED
        }
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertEquals(2, fixture.handoff.externalStarts)
        assertEquals(0, fixture.binder.commits)
        assertEquals(2, fixture.handoff.closes)
    }

    @Test fun aa05RevocationInvalidatesPreflightAndAlreadyIssuedBatch() {
        val fixture = Fixture()
        val offered = requireNotNull(fixture.preflight())
        fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        assertTrue(offered.isCurrent)
        assertTrue(batch.isCurrent)
        fixture.generation.coordinator.close()
        assertFalse(offered.isCurrent)
        assertFalse(batch.isCurrent)
        batch.close()
        assertEquals(2, fixture.handoff.closes)
    }

    @Test fun liveAa05TokenBRejectsManifestSignedForAuthenticationIdentityA() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        val manifestA = fixture.manifest()
        val identityB = MfiAuthenticationIdentity.capture(object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor() = 1
            override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(9, 8, 7)
            override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
        })
        val generationB = Generation(identityB)
        fixture.currentGeneration = generationB
        assertThrows(IllegalArgumentException::class.java) {
            fixture.authority.seal(manifestA, fixture.issuer, generationB.token)
        }
        assertEquals(0, fixture.handoff.calls)
    }

    @Test fun uncertainEglRetirementKeepsBorrowAndReservationsUntilExactRetrySucceeds() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        fixture.simulateExactPostSwapTextureUpdate()
        assertFalse(fixture.secondUiCovered)
        fixture.binder.failNextRetirement = true
        assertThrows(IllegalStateException::class.java) { batch.close() }
        assertTrue(fixture.secondUiCovered)
        assertEquals(0, fixture.handoff.closes)
        assertNull(fixture.registry.acquirePhysicalTarget(fixture.firstVersion))
        batch.close()
        assertEquals(2, fixture.handoff.closes)
        assertTrue(fixture.firstUiCovered)
        assertNull(fixture.registry.acquirePhysicalTarget(fixture.firstVersion))
    }

    @Test fun aa05AuthorityRevocationCoversMainAndExternalBeforeBatchRetirement() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        fixture.simulateMainPostSwapTextureUpdate()
        fixture.simulateExactPostSwapTextureUpdate()
        assertFalse(fixture.firstUiCovered)
        assertFalse(fixture.secondUiCovered)

        fixture.authority.revoke()
        assertTrue(fixture.firstUiCovered)
        assertTrue(fixture.secondUiCovered)
        assertFalse(batch.isCurrent)
        batch.close()
        assertTrue(fixture.secondUiCovered)
    }

    @Test fun policyEpochRevocationBeforeBindHasNoPhysicalOutputToCover() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        assertTrue(fixture.authority.revokeForPolicyEpoch())
        assertTrue(fixture.authority.revokeForPolicyEpoch())
        assertEquals(0, fixture.handoff.calls)
        assertEquals(0, fixture.binder.commits)
        assertNull(fixture.preflight())
    }

    @Test fun policyEpochRevocationCoversExactBoundMainAndClusterBeforeRetirement() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        fixture.simulateMainPostSwapTextureUpdate()
        fixture.simulateExactPostSwapTextureUpdate()
        assertFalse(fixture.firstUiCovered)
        assertFalse(fixture.secondUiCovered)

        fixture.authority.beginPolicyEpochRevocation()
        assertFalse("Permit authority must revoke before Host ACK", batch.isCurrent)
        assertFalse(fixture.firstUiCovered)
        assertTrue(fixture.authority.revokeForPolicyEpoch())
        assertTrue(fixture.firstUiCovered)
        assertTrue(fixture.secondUiCovered)
        assertFalse(batch.isCurrent)
        assertFalse(fixture.firstUiRegistration.onTextureUpdated(fixture.firstTexture, 20L))
        assertTrue(fixture.authority.revokeForPolicyEpoch())
        batch.close()
    }

    @Test fun policyEpochCoverFailureRetriesTheSameOldMainOwnerAfterHostReplacement() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        fixture.simulateMainPostSwapTextureUpdate()
        fixture.simulateExactPostSwapTextureUpdate()
        fixture.failAllMainUiCover = true

        assertFalse(fixture.authority.revokeForPolicyEpoch())
        assertFalse(fixture.firstUiCovered)
        assertTrue(fixture.secondUiCovered)
        assertFalse(batch.isCurrent)
        val successorSurface = surface()
        val successorTexture = texture()
        val successorVersion = requireNotNull(fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            successorSurface, successorTexture, 800, 480,
        ))
        var successorCovered = false
        val successorRegistration = requireNotNull(fixture.registry.bindMainPhysicalUiCover(
            successorSurface, successorTexture, successorVersion, Any(),
            MainPhysicalUiCoverGate { _, covered -> successorCovered = covered; true },
        ))
        try {
            assertFalse(fixture.authority.revokeForPolicyEpoch())
            assertFalse("An old policy ticket must not cover the successor", successorCovered)
            fixture.failAllMainUiCover = false
            assertTrue(fixture.authority.revokeForPolicyEpoch())
            assertTrue(fixture.firstUiCovered)
            assertFalse("An old policy ticket must not cover the successor", successorCovered)
        } finally {
            fixture.failAllMainUiCover = false
            batch.close()
            successorRegistration.close()
        }
    }

    @Test fun policyEpochRevocationDuringBatchPreparationAbortsBeforeNativeUse() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        fixture.binder.onPrepare = {
            assertTrue(fixture.authority.revokeForPolicyEpoch())
            assertTrue(fixture.firstUiCovered)
            assertTrue(fixture.secondUiCovered)
        }

        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertEquals(0, fixture.binder.commits)
        assertEquals(0, fixture.handoff.externalStarts)
        assertTrue(fixture.authority.revokeForPolicyEpoch())
    }

    @Test fun policyEpochRevocationDuringFirstBorrowCannotCrossNativeUseGate() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        var revokedDuringBorrow = false
        fixture.onMainUiCover = {
            if (fixture.handoff.calls == 2 && !revokedDuringBorrow) {
                revokedDuringBorrow = true
                fixture.authority.beginPolicyEpochRevocation()
            }
        }

        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertTrue(revokedDuringBorrow)
        assertEquals(0, fixture.handoff.externalStarts)
        assertEquals(0, fixture.binder.commits)
        assertTrue(fixture.authority.revokeForPolicyEpoch())
    }

    @Test fun policyEpochRevocationDuringSecondBorrowRetainsFirstExactBorrow() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        var revokedDuringSecondBorrow = false
        fixture.onExternalUiCover = {
            if (fixture.handoff.calls == 2 && !revokedDuringSecondBorrow) {
                revokedDuringSecondBorrow = true
                fixture.authority.beginPolicyEpochRevocation()
            }
        }

        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertTrue(revokedDuringSecondBorrow)
        assertEquals(0, fixture.handoff.externalStarts)
        assertEquals(0, fixture.binder.commits)
        assertTrue(fixture.authority.revokeForPolicyEpoch())
    }

    @Test fun partialPolicyTicketDoesNotRequireUnborrowedDetachedClusterOwner() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        var revokedDuringSecondBorrow = false
        fixture.onExternalUiCover = {
            if (fixture.handoff.calls == 2 && !revokedDuringSecondBorrow) {
                revokedDuringSecondBorrow = true
                fixture.simulateMainPostSwapTextureUpdate()
                fixture.failAllMainUiCover = true
                fixture.authority.beginPolicyEpochRevocation()
            }
        }

        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertTrue(revokedDuringSecondBorrow)
        assertTrue(fixture.authority.hasPendingRetirement())
        // The CLUSTER was not in the policy ticket's frozen borrow set. Its Host UI owner may
        // disappear before the policy cover retry without making that old ticket impossible.
        fixture.closeSecondUiCover()
        requireNotNull(fixture.registry.withdraw(
            DisplayDestination.CLUSTER, fixture.secondSurface, fixture.secondTexture,
            textureDestroyed = false,
        ))
        assertFalse(fixture.registry.coverExactExternalPhysicalTarget(
            fixture.secondVersion, fixture.secondSurface,
        ))
        assertFalse(fixture.authority.revokeForPolicyEpoch())
        fixture.failAllMainUiCover = false
        assertTrue(fixture.authority.revokeForPolicyEpoch())
        assertTrue(fixture.authority.retryPendingRetirement())
        assertFalse(fixture.authority.hasPendingRetirement())
        assertEquals(0, fixture.binder.commits)
    }

    @Test fun preEglFailedBindKeepsFrozenPolicyBorrowUntilOldGateAckRetry() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        var revokedDuringSecondBorrow = false
        fixture.onExternalUiCover = {
            if (fixture.handoff.calls == 2 && !revokedDuringSecondBorrow) {
                revokedDuringSecondBorrow = true
                // Adversarial UI reentry: a gate became visible without a valid signed producer
                // frame. Policy revocation must still retain that exact borrow on failed ACK.
                fixture.simulateMainPostSwapTextureUpdate()
                fixture.failAllMainUiCover = true
                fixture.authority.beginPolicyEpochRevocation()
            }
        }

        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertTrue(revokedDuringSecondBorrow)
        assertTrue(fixture.authority.hasPendingRetirement())
        assertFalse(fixture.firstUiCovered)
        assertEquals(0, fixture.handoff.closes)
        assertFalse(fixture.authority.revokeForPolicyEpoch())
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.retryPendingRetirement()
        }
        assertEquals(0, fixture.handoff.closes)

        fixture.failAllMainUiCover = false
        assertTrue(fixture.authority.revokeForPolicyEpoch())
        assertTrue(fixture.firstUiCovered)
        assertTrue(fixture.authority.retryPendingRetirement())
        assertFalse(fixture.authority.hasPendingRetirement())
        assertEquals(2, fixture.handoff.closes)
        assertEquals(0, fixture.binder.commits)
    }

    @Test fun failedBindCoverCallbackCanRevokePolicyWithoutReleasingFailedOldGate() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        var failingBindCover = false
        var phaseOneEntered = false
        fixture.binder.failOnSecondOutput = true
        fixture.binder.onPrepare = {
            fixture.simulateMainPostSwapTextureUpdate()
            fixture.failAllMainUiCover = true
            failingBindCover = true
        }
        fixture.onMainUiCover = {
            if (failingBindCover && !phaseOneEntered) {
                phaseOneEntered = true
                fixture.authority.beginPolicyEpochRevocation()
            }
        }

        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertTrue(phaseOneEntered)
        assertTrue(fixture.authority.hasPendingRetirement())
        assertEquals(0, fixture.handoff.closes)
        assertFalse(fixture.authority.revokeForPolicyEpoch())
        fixture.failAllMainUiCover = false
        assertTrue(fixture.authority.revokeForPolicyEpoch())
        assertTrue(fixture.authority.retryPendingRetirement())
        assertEquals(2, fixture.handoff.closes)
        assertEquals(0, fixture.binder.commits)
    }

    @Test fun policyPhaseOneAfterFailedBindCoverDecisionStillKeepsOldBorrow() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        fixture.binder.failOnSecondOutput = true
        fixture.binder.onPrepare = {
            fixture.simulateMainPostSwapTextureUpdate()
            fixture.failAllMainUiCover = true
        }
        var phaseOneEntered = false
        fixture.afterFailedBindCoverDecision {
            phaseOneEntered = true
            fixture.authority.beginPolicyEpochRevocation()
        }

        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertTrue(phaseOneEntered)
        assertTrue(fixture.authority.hasPendingRetirement())
        assertEquals(0, fixture.handoff.closes)
        assertFalse(fixture.authority.revokeForPolicyEpoch())
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.retryPendingRetirement()
        }
        assertEquals(0, fixture.handoff.closes)
        fixture.failAllMainUiCover = false
        assertTrue(fixture.authority.revokeForPolicyEpoch())
        assertTrue(fixture.authority.retryPendingRetirement())
        assertEquals(2, fixture.handoff.closes)
    }

    @Test fun policyPhaseOneAfterSuccessfulFailedBindCoverUsesCachedOldAck() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        fixture.binder.failOnSecondOutput = true
        fixture.binder.onPrepare = fixture::simulateMainPostSwapTextureUpdate
        var phaseOneEntered = false
        fixture.afterFailedBindCoverDecision {
            phaseOneEntered = true
            fixture.authority.beginPolicyEpochRevocation()
        }

        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertTrue(phaseOneEntered)
        assertFalse(fixture.authority.hasPendingRetirement())
        assertEquals(2, fixture.handoff.closes)
        // The exact old gate ACK was cached before borrow retirement. Policy phase two must
        // consume that old receipt, not locate a replacement Surface or Activity.
        assertTrue(fixture.authority.revokeForPolicyEpoch())
        assertEquals(0, fixture.binder.commits)
    }

    @Test fun policyEpochClusterCoverFailureAlsoRetainsItsExactBorrowForRetry() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        fixture.simulateExactPostSwapTextureUpdate()
        fixture.failNextUiCover = true

        assertFalse(fixture.authority.revokeForPolicyEpoch())
        assertFalse(fixture.secondUiCovered)
        assertTrue(fixture.firstUiCovered)
        assertTrue(fixture.authority.revokeForPolicyEpoch())
        assertTrue(fixture.secondUiCovered)
        batch.close()
    }

    @Test fun policyEpochRevocationInsideFirstExternalUseCapturesEveryBorrow() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        var policyAck: Boolean? = null
        fixture.handoff.onBegin = { type ->
            if (type == 110) {
                policyAck = fixture.authority.revokeForPolicyEpoch()
            }
        }

        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        // Pre-scene MAIN and CLUSTER are already black; the exact old gates can ACK without
        // another UI dispatch even though an external-use token is in flight.
        assertEquals(true, policyAck)
        assertEquals(0, fixture.binder.commits)
        assertTrue(fixture.authority.revokeForPolicyEpoch())
        assertTrue(fixture.firstUiCovered)
        assertTrue(fixture.secondUiCovered)
    }

    @Test fun callerChosenTruePredicateCannotForgeExternalFramePermit() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        val forged = AuthorizedExternalClusterUiPermit(
            fixture.authority, Any(), fixture.secondVersion, fixture.secondSurface,
            19L, 1L, 1L, false,
        ) { true }
        assertFalse(fixture.registry.armExternalAfterPresentedScene(forged))
        assertTrue(fixture.secondUiCovered)
        batch.close()
    }

    @Test fun mainCoverIsAppliedBeforeHandoffAndAcknowledgedAgainBeforeClose() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        assertFalse(fixture.firstUiCovered)
        fixture.binder.onPrepare = { assertTrue(fixture.firstUiCovered) }
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        assertTrue(fixture.firstUiCovered)
        fixture.simulateMainPostSwapTextureUpdate()
        fixture.simulateExactPostSwapTextureUpdate()
        assertFalse(fixture.firstUiCovered)
        assertFalse(fixture.secondUiCovered)
        assertTrue(fixture.authority.coverBoundSessionForClose(context, batch))
        assertTrue(fixture.firstUiCovered)
        assertTrue(fixture.secondUiCovered)
        batch.close()
    }

    @Test fun terminalClusterStopRevokesExternalPermitBeforeBlockedHostCover() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        fixture.simulateMainPostSwapTextureUpdate()
        fixture.simulateExactPostSwapTextureUpdate()
        assertFalse(fixture.secondUiCovered)
        assertTrue(fixture.authority.ownsBoundBatch(fixture.child, batch))
        val coverEntered = CountDownLatch(1)
        val releaseCover = CountDownLatch(1)
        fixture.onExternalUiCover = {
            coverEntered.countDown()
            check(releaseCover.await(3L, TimeUnit.SECONDS))
        }
        val completed = AtomicReference<Boolean>()
        try {
            assertTrue(fixture.authority.revokeBoundSessionForStop(context, batch))
            // Every old external permit's stillAuthorized predicate calls ownsBoundBatch.
            assertFalse(fixture.authority.ownsBoundBatch(fixture.child, batch))
            assertFalse(fixture.secondUiCovered)
            val worker = Thread {
                completed.set(fixture.authority.coverBoundSessionForClose(context, batch))
            }
            worker.start()
            assertTrue(coverEntered.await(2L, TimeUnit.SECONDS))
            assertFalse(fixture.authority.ownsBoundBatch(fixture.child, batch))
            assertFalse(fixture.secondUiCovered)
            releaseCover.countDown()
            worker.join(5_000L)
            assertFalse(worker.isAlive)
            assertEquals(true, completed.get())
            assertTrue(fixture.firstUiCovered)
            assertTrue(fixture.secondUiCovered)
        } finally {
            releaseCover.countDown()
            batch.close()
        }
    }

    @Test fun exactMainScreenTeardownRecoversMainWithoutRetiringClusterOrBatch() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        fixture.simulateMainPostSwapTextureUpdate()
        fixture.simulateExactPostSwapTextureUpdate()
        assertFalse(fixture.firstUiCovered)
        assertFalse(fixture.secondUiCovered)
        val handle = mainScreenHandle()

        assertTrue(fixture.authority.coverMainForExactScreenTeardown(context, batch, handle))
        assertTrue(fixture.firstUiCovered)
        assertFalse(fixture.secondUiCovered)
        assertTrue(batch.isCurrent)
        assertEquals(0, fixture.binder.retirements)
        assertFalse(fixture.authority.coverMainForExactScreenTeardown(
            fixture.sessionContext(), batch, handle,
        ))

        batch.close()
    }

    @Test fun semanticMainCoverRevokesOldEpochBeforeUiAckAndRequiresNewerSignedRevision() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        val child = fixture.openSignedStaticChild()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext(child)))
        fixture.installSignedSceneBindingsForCoverUnitTest(child)
        try {
            fixture.simulateMainPostSwapTextureUpdate()
            fixture.simulateExactPostSwapTextureUpdate()
            assertFalse(fixture.firstUiCovered)
            assertFalse(fixture.secondUiCovered)

            // This white-box epoch probe isolates Authority's phase-1 invalidation. The fake
            // backend cannot create a real signed PRESENTED/EGL producer-frame receipt.
            val epochGate = fixture.mainCoverGenerationForTest()
            val oldEpoch = requireNotNull(epochGate.admitPresented(17L, 19L))
            val before = fixture.firstUiGate.currentSequence
            val displayId = requireNotNull(fixture.outputs[0].uiSyncDisplayId)
            val ticket = requireNotNull(fixture.authority.beginMainSemanticCover(
                child, batch, displayId, 17L,
            ))
            assertFalse(epochGate.isCurrent(oldEpoch))
            assertEquals(before, fixture.firstUiGate.currentSequence)
            assertFalse(fixture.authority.resumeMainAfterSignedPhoneVisualChoice(
                child, batch, displayId, 18L,
            )) // phase 1 cannot stand in for the exact Host UI ACK
            assertTrue(ticket.coverExactMainUi())
            assertTrue(fixture.firstUiCovered)
            assertTrue(fixture.firstUiGate.hasAppliedCover)
            assertFalse(fixture.secondUiCovered)
            assertTrue(batch.isCurrent)
            assertEquals(0, fixture.binder.retirements)
            assertFalse(fixture.authority.resumeMainAfterSignedPhoneVisualChoice(
                child, batch, displayId, 17L,
            ))
            assertFalse(fixture.authority.resumeMainAfterSignedPhoneVisualChoice(
                child, batch, requireNotNull(fixture.outputs[1].uiSyncDisplayId), 18L,
            ))
            assertTrue(fixture.authority.resumeMainAfterSignedPhoneVisualChoice(
                child, batch, displayId, 18L,
            ))
            assertTrue(fixture.firstUiCovered) // resume is not a reveal permit
            assertFalse(epochGate.isCurrent(oldEpoch))
        } finally {
            batch.close()
            child.close()
        }
    }

    @Test fun semanticMainCoverRejectsClusterForeignChildAndForeignBatchWithoutEpochChange() {
        val fixture = Fixture()
        val foreign = Fixture()
        requireNotNull(fixture.preflight())
        requireNotNull(foreign.preflight())
        val child = fixture.openSignedStaticChild()
        val foreignChild = foreign.openSignedStaticChild()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext(child)))
        fixture.installSignedSceneBindingsForCoverUnitTest(child)
        val context = fixture.sessionContext(child)
        val foreignRoutes = fixture.requirement.assignments.mapIndexed { index, assignment ->
            UiSyncPhysicalOutputRoute(
                assignment, UiSyncPhysicalTargetVersion("foreign-semantic-cover-$index", 777L),
            )
        }
        val foreignBatch = context.issueActiveBatch(
            object : UiSyncPhysicalOutputBackendLease {
                override val generation = 777L
                override val isCurrent = true
                override fun close() = Unit
            },
            foreignRoutes,
            foreignRoutes.map { route -> object : UiSyncPhysicalTargetLease {
                override val version = route.target
                override val isCurrent = true
            } },
        )
        try {
            val epochGate = fixture.mainCoverGenerationForTest()
            val epoch = requireNotNull(epochGate.admitPresented(17L, 19L))
            val clusterId = requireNotNull(fixture.outputs[1].uiSyncDisplayId)
            val mainId = requireNotNull(fixture.outputs[0].uiSyncDisplayId)
            assertNull(fixture.authority.beginMainSemanticCover(
                child, batch, clusterId, 17L,
            ))
            assertNull(fixture.authority.beginMainSemanticCover(
                foreignChild, batch, mainId, 17L,
            ))
            assertNull(fixture.authority.beginMainSemanticCover(
                child, foreignBatch, mainId, 17L,
            ))
            assertNull(fixture.authority.beginMainSemanticCover(
                child, batch, mainId, -1L,
            ))
            assertTrue(epochGate.isCurrent(epoch))
            assertTrue(fixture.firstUiCovered)

            val ticket = requireNotNull(fixture.authority.beginMainSemanticCover(
                child, batch, mainId, 17L,
            ))
            assertFalse(fixture.authority.resumeMainAfterSignedPhoneVisualChoice(
                foreignChild, batch, mainId, 18L,
            ))
            assertFalse(fixture.authority.resumeMainAfterSignedPhoneVisualChoice(
                child, foreignBatch, mainId, 18L,
            ))
            assertTrue(ticket.coverExactMainUi())
            assertFalse(fixture.authority.resumeMainAfterSignedPhoneVisualChoice(
                foreignChild, batch, mainId, 18L,
            ))
            assertFalse(fixture.authority.resumeMainAfterSignedPhoneVisualChoice(
                child, foreignBatch, mainId, 18L,
            ))
            assertTrue(fixture.authority.resumeMainAfterSignedPhoneVisualChoice(
                child, batch, mainId, 18L,
            ))
        } finally {
            foreignBatch.close()
            batch.close()
            child.close()
            foreignChild.close()
        }
    }

    @Test fun semanticMainCoverFailureRequiresExactRetryAndNeverAcknowledgesStaleHost() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        val child = fixture.openSignedStaticChild()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext(child)))
        fixture.installSignedSceneBindingsForCoverUnitTest(child)
        try {
            fixture.simulateMainPostSwapTextureUpdate()
            val mainId = requireNotNull(fixture.outputs[0].uiSyncDisplayId)
            val ticket = requireNotNull(fixture.authority.beginMainSemanticCover(
                child, batch, mainId, 17L,
            ))
            fixture.failNextMainUiCover = true
            assertFalse(ticket.coverExactMainUi())
            assertFalse(fixture.firstUiCovered)
            assertFalse(fixture.authority.resumeMainAfterSignedPhoneVisualChoice(
                child, batch, mainId, 18L,
            ))
            assertTrue(ticket.coverExactMainUi())
            assertTrue(fixture.firstUiCovered)

            // A detached Activity registration cannot lend its ACK to a later owner.
            fixture.firstUiRegistration.close()
            assertFalse(ticket.coverExactMainUi())
            assertFalse(fixture.authority.resumeMainAfterSignedPhoneVisualChoice(
                child, batch, mainId, 18L,
            ))
            assertEquals(0, fixture.binder.retirements)
        } finally {
            batch.close()
            child.close()
        }
    }

    @Test fun semanticMainCoverOldTicketCannotCoverOrResumeAfterSurfaceReplacement() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        val child = fixture.openSignedStaticChild()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext(child)))
        fixture.installSignedSceneBindingsForCoverUnitTest(child)
        try {
            val mainId = requireNotNull(fixture.outputs[0].uiSyncDisplayId)
            val ticket = requireNotNull(fixture.authority.beginMainSemanticCover(
                child, batch, mainId, 17L,
            ))
            requireNotNull(fixture.registry.publish(
                DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
                surface(), texture(), 800, 480,
            ))
            assertFalse(ticket.coverExactMainUi())
            assertFalse(fixture.authority.resumeMainAfterSignedPhoneVisualChoice(
                child, batch, mainId, 18L,
            ))
            assertEquals(0, fixture.binder.retirements)
        } finally {
            batch.close()
            child.close()
        }
    }

    @Test fun failedNonterminalMainCoverRetainsBatchForExactRetry() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        fixture.simulateMainPostSwapTextureUpdate()
        fixture.failNextMainUiCover = true
        val handle = mainScreenHandle()

        assertFalse(fixture.authority.coverMainForExactScreenTeardown(context, batch, handle))
        assertFalse(fixture.firstUiCovered)
        assertTrue(batch.isCurrent)
        assertEquals(0, fixture.binder.retirements)
        assertTrue(fixture.authority.coverMainForExactScreenTeardown(context, batch, handle))
        assertTrue(fixture.firstUiCovered)
        batch.close()
    }

    @Test fun blockedBackendPhaseInScreenCoverCannotDelayTerminalPhysicalCover() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        fixture.backend.phaseEntered = entered
        fixture.backend.phaseRelease = resume
        val nonterminalResult = AtomicReference<Boolean>()
        val nonterminal = Thread {
            nonterminalResult.set(fixture.authority.coverMainForExactScreenTeardown(
                context, batch, mainScreenHandle(),
            ))
        }
        nonterminal.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val terminalResult = AtomicReference<Boolean>()
        val terminal = Thread {
            terminalResult.set(fixture.authority.coverBoundSessionForClose(context, batch))
        }
        terminal.start()
        terminal.join(1_000)
        assertFalse("STOPPING cover waited for EGL phase", terminal.isAlive)
        assertTrue(terminalResult.get())
        resume.countDown()
        nonterminal.join(5_000)
        assertFalse(nonterminal.isAlive)
        assertFalse(nonterminalResult.get())
        batch.close()
    }

    private fun mainScreenHandle(): AirPlayVideoStreamHandle {
        val epochType = Class.forName("com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch")
        val epoch = epochType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance()
        return AirPlayVideoStreamHandle::class.java.declaredConstructors
            .single { it.parameterCount == 3 }.apply { isAccessible = true }
            .newInstance(AirPlayStreamIdentity(110, "1", null), epoch, null) as
            AirPlayVideoStreamHandle
    }

    @Test fun missingMainCoverCannotReachLegacyOrEglHandoff() {
        val fixture = Fixture()
        fixture.firstUiRegistration.close()
        requireNotNull(fixture.preflight())
        fixture.register()
        assertThrows(IllegalStateException::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertEquals(0, fixture.handoff.calls)
        assertEquals(0, fixture.binder.commits)
    }

    @Test fun failedMainCoverCannotRetirePhysicalBatch() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        fixture.simulateMainPostSwapTextureUpdate()
        fixture.failNextMainUiCover = true
        assertThrows(IllegalStateException::class.java) { batch.close() }
        assertFalse(fixture.firstUiCovered)
        assertEquals(0, fixture.binder.retirements)
        batch.close()
        assertTrue(fixture.firstUiCovered)
        assertEquals(1, fixture.binder.retirements)
    }

    @Test fun callerChosenPredicateAndOldActivityCannotForgeMainPermit() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        val forged = AuthorizedMainPhysicalUiPermit(
            fixture.authority, Any(), fixture.firstVersion, fixture.firstSurface,
            fixture.mainActivityGeneration, 19L, batch.generation, 1L, 17L,
        ) { true }
        assertFalse(fixture.registry.armMainAfterPresentedScene(forged))
        assertTrue(fixture.firstUiCovered)
        val nonce = requireNotNull(AuthorizedAndroidUiSyncPhysicalOutputAuthority::class.java
            .getDeclaredField("mainCoverIssuerNonce").apply { isAccessible = true }
            .get(fixture.authority))
        val oldActivity = AuthorizedMainPhysicalUiPermit(
            fixture.authority, nonce, fixture.firstVersion, fixture.firstSurface,
            Any(), 19L, batch.generation, 1L, 17L,
        ) { true }
        assertFalse(fixture.registry.armMainAfterPresentedScene(oldActivity))
        assertTrue(fixture.firstUiCovered)
        val onceCurrent = AuthorizedMainPhysicalUiPermit(
            fixture.authority, nonce, fixture.firstVersion, fixture.firstSurface,
            fixture.mainActivityGeneration, 20L, batch.generation, 1L, 17L,
        ) { batch.isCurrent }
        assertTrue(onceCurrent.isCurrentFor(
            fixture.firstVersion, fixture.firstSurface, fixture.mainActivityGeneration,
        ))
        batch.close()
        assertFalse(onceCurrent.isCurrentFor(
            fixture.firstVersion, fixture.firstSurface, fixture.mainActivityGeneration,
        ))
        assertFalse(fixture.registry.armMainAfterPresentedScene(onceCurrent))
        assertFalse(oldActivity.isCurrentFor(
            fixture.firstVersion, fixture.firstSurface, fixture.mainActivityGeneration,
        ))
    }

    @Test fun sharedSessionClosesRendererRegistrationBeforePhysicalBatchAndRetriesFence() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        val registration = fixture.register()
        fixture.child.onClose = registration::close
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        val sharedSession = sharedProvisionedSession(fixture, context, batch)
        fixture.simulateExactPostSwapTextureUpdate()
        assertFalse(fixture.secondUiCovered)
        fixture.binder.failNextRetirement = true
        assertThrows(IllegalStateException::class.java) { sharedSession.close() }
        assertTrue(fixture.secondUiCovered)
        assertFalse(fixture.child.isLive)
        assertFalse(batch.isCurrent)
        assertEquals(0, fixture.handoff.closes)
        assertNull(fixture.registry.acquirePhysicalTarget(fixture.firstVersion))
        sharedSession.close()
        assertEquals(2, fixture.handoff.closes)
        assertTrue(fixture.firstUiCovered)
        assertNull(fixture.registry.acquirePhysicalTarget(fixture.firstVersion))
        registration.close() // exact already-retired registration is idempotent
    }

    @Test fun closeCoverRejectsForeignContextAndBatchAndNeverCoversSuccessor() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        fixture.simulateExactPostSwapTextureUpdate()
        assertFalse(fixture.secondUiCovered)

        val foreignContext = fixture.sessionContext()
        val oldCoverSequence = fixture.secondCoverSequence
        assertFalse(fixture.authority.coverBoundSessionForClose(foreignContext, batch))
        assertEquals(oldCoverSequence, fixture.secondCoverSequence)
        assertFalse(fixture.secondUiCovered)

        val foreignRoutes = fixture.requirement.assignments.mapIndexed { index, assignment ->
            UiSyncPhysicalOutputRoute(
                assignment, UiSyncPhysicalTargetVersion("foreign-cover-batch-$index", 123L),
            )
        }
        val foreignBatch = context.issueActiveBatch(
            object : UiSyncPhysicalOutputBackendLease {
                override val generation = 123L
                override val isCurrent = true
                override fun close() = Unit
            },
            foreignRoutes,
            foreignRoutes.map { route -> object : UiSyncPhysicalTargetLease {
                override val version = route.target
                override val isCurrent = true
            } },
        )
        assertFalse(fixture.authority.coverBoundSessionForClose(context, foreignBatch))
        assertEquals(oldCoverSequence, fixture.secondCoverSequence)
        assertFalse(fixture.secondUiCovered)
        foreignBatch.close()

        fixture.publishSecond(HostDisplaySurfaceKind.PHYSICAL_EXTERNAL)
        val successorCoverSequence = fixture.secondCoverSequence
        assertTrue(fixture.authority.coverBoundSessionForClose(context, batch))
        assertEquals(successorCoverSequence, fixture.secondCoverSequence)
        assertTrue(fixture.secondUiCovered)
        batch.close()
    }

    @Test fun sharedCloseRetriesOldTargetCoverAndFenceAfterHostReplacement() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        val registration = fixture.register()
        fixture.child.onClose = registration::close
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        val sharedSession = sharedProvisionedSession(fixture, context, batch)
        fixture.simulateExactPostSwapTextureUpdate()
        assertFalse(fixture.secondUiCovered)

        fixture.publishSecond(HostDisplaySurfaceKind.PHYSICAL_EXTERNAL)
        val successorCoverSequence = fixture.secondCoverSequence
        fixture.binder.failNextRetirement = true
        assertThrows(IllegalStateException::class.java) { sharedSession.close() }
        assertEquals(successorCoverSequence, fixture.secondCoverSequence)
        assertEquals(0, fixture.handoff.closes)
        sharedSession.close()
        assertEquals(successorCoverSequence, fixture.secondCoverSequence)
        assertEquals(2, fixture.handoff.closes)
        registration.close()
    }

    @Test fun sharedCloseRetiresExactBatchAfterHostTrustRevocation() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        val registration = fixture.register()
        fixture.child.onClose = registration::close
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        val sharedSession = sharedProvisionedSession(fixture, context, batch)
        fixture.simulateExactPostSwapTextureUpdate()
        assertFalse(fixture.secondUiCovered)

        fixture.secondTrust.invalidate()
        assertTrue(fixture.secondUiCovered)
        sharedSession.close()
        assertEquals(2, fixture.handoff.closes)
        registration.close()
    }

    @Test fun sharedCloseUsesFrozenOldCoverAfterSameSurfaceGenerationChange() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        val registration = fixture.register()
        fixture.child.onClose = registration::close
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        val sharedSession = sharedProvisionedSession(fixture, context, batch)
        fixture.simulateExactPostSwapTextureUpdate()
        val oldVersion = fixture.secondVersion

        fixture.replaceSecondTrustOnSameSurface()
        assertFalse(fixture.registry.isSameLivePhysicalTarget(oldVersion, fixture.secondSurface))
        assertTrue(fixture.secondUiCovered)
        sharedSession.close()
        assertEquals(2, fixture.handoff.closes)
        registration.close()
    }

    @Test fun failedUiCoverDoesNotAckAndSameChildRetriesOldGate() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        val registration = fixture.register()
        fixture.child.onClose = registration::close
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        val sharedSession = sharedProvisionedSession(fixture, context, batch)
        fixture.simulateExactPostSwapTextureUpdate()

        fixture.failNextUiCover = true
        assertThrows(IllegalStateException::class.java) { sharedSession.close() }
        assertFalse(fixture.secondUiCovered)
        assertTrue(fixture.child.isLive)
        assertEquals(0, fixture.handoff.closes)
        sharedSession.close()
        assertTrue(fixture.secondUiCovered)
        assertEquals(2, fixture.handoff.closes)
        registration.close()
    }

    @Test fun detachedExactTextureAllowsChildCloseWithoutUiDispatchAck() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        val registration = fixture.register()
        fixture.child.onClose = registration::close
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        val sharedSession = sharedProvisionedSession(fixture, context, batch)
        fixture.simulateExactPostSwapTextureUpdate()

        fixture.failNextUiCover = true
        assertFalse(fixture.registry.onTextureDestroyed(fixture.secondTexture))
        // The UI callback failed, but the exact TextureView has detached and cannot reveal.
        assertFalse(fixture.secondUiCovered)
        sharedSession.close()
        assertEquals(2, fixture.handoff.closes)
        registration.close()
    }

    @Test fun priorExactCoverAckSurvivesOldRecordRemovalAfterPartialBatchClose() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        val registration = fixture.register()
        fixture.child.onClose = registration::close
        val context = fixture.sessionContext()
        val batch = requireNotNull(fixture.authority.bindSession(context))
        val sharedSession = sharedProvisionedSession(fixture, context, batch)
        fixture.simulateExactPostSwapTextureUpdate()
        val oldVersion = fixture.secondVersion

        fixture.handoff.failCloseOnceForType = 111
        assertThrows(IllegalStateException::class.java) { sharedSession.close() }
        assertTrue(fixture.secondUiCovered)
        assertEquals(1, fixture.handoff.closes)
        val retirement = requireNotNull(fixture.registry.withdraw(
            DisplayDestination.CLUSTER, fixture.secondSurface, fixture.secondTexture,
            textureDestroyed = true,
        ))
        retirement.completeAfterLegacyOwnerRetired()
        fixture.registry.retryPendingRetirements()
        assertFalse(fixture.registry.coverExactExternalPhysicalTarget(
            oldVersion, fixture.secondSurface,
        ))

        sharedSession.close()
        assertEquals(2, fixture.handoff.closes)
        registration.close()
    }

    @Test fun secondReservationReleaseFailureRetriesWithoutReconfirmingReleasedFirstSurface() {
        val fixture = Fixture()
        requireNotNull(fixture.preflight())
        val registration = fixture.register()
        val batch = requireNotNull(fixture.authority.bindSession(fixture.sessionContext()))
        fixture.handoff.failCloseOnceForType = 111
        assertThrows(IllegalStateException::class.java) { batch.close() }
        assertEquals(1, fixture.handoff.closes)
        assertEquals(1, fixture.handoff.confirmations[110])
        assertEquals(1, fixture.handoff.confirmations[111])
        assertEquals(1, fixture.binder.retirements)
        batch.close()
        assertEquals(2, fixture.handoff.closes)
        assertEquals(1, fixture.handoff.confirmations[110])
        assertEquals(1, fixture.handoff.confirmations[111])
        assertEquals(1, fixture.binder.retirements)
        registration.close()
        fixture.coordinator.close()
    }

    @Test fun closedMainWebPreviewDuringCleanBatchPrepareFailureCannotReviveDecoderRoute() {
        val fixture = Fixture()
        val context = unsafe.allocateInstance(ContextWrapper::class.java) as ContextWrapper
        val sink = AndroidMediaSink(context)
        var failWebCloseOnce = true
        val web = CarPlayWebDisplayFactory.Session(
            configs = mapOf(110 to CarPlayWebDisplayConfig(true, 800, 480)),
            displays = mapOf(110 to WebDisplayChannel(110, 800, 480)),
            stopServer = {
                if (failWebCloseOnce) {
                    failWebCloseOnce = false
                    error("synthetic WebDisplay close failure")
                }
            },
        )
        web.bind(sink)
        web.preview(110, fixture.firstSurface)
        // The external route is separately covered by the typed type-111 transfer test. This
        // fixture tests the legacy MAIN WebDisplay retirement while a second output exists.
        val mainHandoff = AndroidMediaSinkPhysicalLegacyHandoffPort(sink)
        val mixedHandoff = PhysicalLegacySurfaceHandoffPort { type, surface ->
            if (type == 110) mainHandoff.retireAndReserve(type, surface)
            else fixture.handoff.retireAndReserve(type, surface)
        }
        val authority = AuthorizedAndroidUiSyncPhysicalOutputAuthority(
            fixture.profile, fixture.requirement, fixture.plan, fixture.schema, fixture.binding,
            fixture.registry, mixedHandoff,
            { token -> fixture.currentGeneration.coordinator.currentPhysicalGenerationProof(token) },
            mapOf(DisplayDestination.MAIN to 110, DisplayDestination.CLUSTER to 111),
        )
        requireNotNull(authority.preflight(physicalPreflight(
            fixture.requirement, fixture.plan, fixture.schema, fixture.binding,
        )))
        authority.seal(fixture.manifest(), fixture.issuer, fixture.token)
        val registration = requireNotNull(authority.registerRendererSessionIfSealed(
            fixture.child, fixture.coordinator,
        ))
        fixture.binder.onPrepare = {
            assertThrows(IllegalStateException::class.java) { web.close() }
        }
        fixture.binder.failOnSecondOutput = true
        assertThrows(IllegalStateException::class.java) {
            authority.bindSession(fixture.sessionContext())
        }
        assertEquals(0, fixture.binder.commits)
        assertFalse(authority.hasPendingRetirement())
        assertEquals(AndroidSurfaceReservationState.FREE,
            AndroidMediaSink.surfaceReservationState(fixture.firstSurface))
        assertEquals(AndroidSurfaceReservationState.FREE,
            AndroidMediaSink.surfaceReservationState(fixture.secondSurface))
        web.close()
        assertFalse(authority.hasPendingRetirement())
        assertEquals(AndroidSurfaceReservationState.FREE,
            AndroidMediaSink.surfaceReservationState(fixture.firstSurface))
        assertEquals(AndroidSurfaceReservationState.FREE,
            AndroidMediaSink.surfaceReservationState(fixture.secondSurface))
        registration.close()
        fixture.coordinator.close()
    }

    @Test fun uncertainBatchCommitRetainsEverySurfaceUntilCoordinatorProofIsRetried() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        fixture.binder.failNextCommitUncertain = true
        fixture.backend.retireResults += UltraEglBackendRetirementResult.CONTROL_IN_FLIGHT
        assertThrows(AndroidUltraOutputBatchCommitFailure::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertTrue(fixture.authority.hasPendingRetirement())
        assertEquals(0, fixture.handoff.closes)
        assertNull(fixture.registry.acquirePhysicalTarget(fixture.firstVersion))
        assertTrue(fixture.authority.retryPendingRetirement())
        assertFalse(fixture.authority.hasPendingRetirement())
        assertEquals(2, fixture.handoff.closes)
        assertTrue(fixture.firstUiCovered)
        assertNull(fixture.registry.acquirePhysicalTarget(fixture.firstVersion))
    }

    @Test fun partialExternalUseGateFailureRetainsOwnersUntilPreparedCleanupIsProven() {
        val fixture = Fixture()
        assertNotNull(fixture.preflight())
        fixture.register()
        fixture.handoff.failBeginAt = 2
        fixture.binder.failPreparationCloseCount = 10
        assertThrows(AndroidUltraOutputBatchCommitFailure::class.java) {
            fixture.authority.bindSession(fixture.sessionContext())
        }
        assertEquals(1, fixture.handoff.externalStarts)
        assertEquals(0, fixture.binder.commits)
        assertEquals(0, fixture.handoff.closes)
        assertTrue(fixture.authority.hasPendingRetirement())
        assertNull(fixture.registry.acquirePhysicalTarget(fixture.firstVersion))
        fixture.binder.failPreparationCloseCount = 0
        assertTrue(fixture.authority.retryPendingRetirement())
        assertEquals(2, fixture.handoff.closes)
        assertFalse(fixture.authority.hasPendingRetirement())
    }

    private inner class Fixture(
        coverWatchdogScheduler: ExternalClusterUiCoverWatchdogScheduler? = null,
    ) {
        val profile = profile()
        val outputs = listOf(
            UltraDisplayGraphOutput(UltraTopologyDisplayId(UUID(0, 1)),
                UltraDisplayOutputId.Android("main"), UltraUiSyncDisplayId("display-main")),
            UltraDisplayGraphOutput(UltraTopologyDisplayId(UUID(0, 2)),
                UltraDisplayOutputId.Custom("cluster"), UltraUiSyncDisplayId("display-cluster")),
        )
        val plan = UiSyncRendererStaticPlan(outputs, emptyList())
        val requirement = UiSyncPhysicalOutputRequirement(profile.candidate.contentDigest, listOf(
            UiSyncPhysicalOutputAssignment(outputs[0],
                UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN, "default")),
            UiSyncPhysicalOutputAssignment(outputs[1],
                UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.CLUSTER, "default")),
        ))
        val schema = fingerprint<UiSyncSchemaFingerprint>(1)
        val binding = fingerprint<UiSyncBindingFingerprint>(2)
        val rows by lazy {
            val packageDigest = profile.candidate.entries.single {
                it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
            }.digest
            val displays = CarPlayThemeSchema.parseArcPackageJson(requireNotNull(
                profile.candidate.content(UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON),
            )).displays
            outputs.mapIndexed { index, output ->
                val state = CarPlayThemeSceneState()
                CompiledUiSyncDisplayAssignment(
                    packageDigest, index, "profile-$index", DisplayType.CENTER_CONSOLE,
                    if (index == 0) DisplayDestination.MAIN else DisplayDestination.CLUSTER,
                    output.topologyDisplayId, output.outputId, requireNotNull(output.uiSyncDisplayId),
                    CompiledUiSyncThemeProgramSelection(0, state,
                        CarPlayThemeProgramCompiler().compile(displays[index], state)
                            .structuralProgram.structuralFingerprint),
                    emptyList(),
                )
            }
        }
        val program = deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, rows)
        val handoff = FakeHandoff()
        val binder = FakeBinder()
        val backend = FakeBackend()
        val coordinator = makeCoordinator(backend, binder)
        val child = FakeChild(schema, binding, sessionReceipt(), coordinator)
        val registry = if (coverWatchdogScheduler == null) HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {}, requestRetirement = {},
        ) else HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {}, requestRetirement = {},
            coverWatchdogScheduler = coverWatchdogScheduler,
        )
        val firstSurface = surface()
        val firstTexture = texture()
        val secondSurface = surface()
        val secondTexture = texture()
        val firstVersion = requireNotNull(registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            firstSurface, firstTexture, 800, 480,
        ))
        val mainActivityGeneration = Any()
        var firstUiCovered = false
        var failNextMainUiCover = false
        var failAllMainUiCover = false
        var onMainUiCover: (() -> Unit)? = null
        val firstUiGate = MainPhysicalUiCoverGate { _, covered ->
            if (covered) onMainUiCover?.invoke()
            if (covered && (failAllMainUiCover || failNextMainUiCover)) {
                failNextMainUiCover = false
                false
            } else {
                firstUiCovered = covered
                true
            }
        }
        val firstUiRegistration = requireNotNull(registry.bindMainPhysicalUiCover(
            firstSurface, firstTexture, firstVersion, mainActivityGeneration, firstUiGate,
        ))
        val secondTrust = ExternalPhysicalTargetTrustToken.create(
            registry, externalCalibrationForTest(pixels = PixelSize(800, 480)),
        ).also { it.bindRouteProof { true } }
        var secondVersion = requireNotNull(registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            secondSurface, secondTexture, 800, 480,
            externalTrustToken = secondTrust,
        ))
        private val uiCovers = ArrayList<HostPhysicalDisplaySurfaceRegistry.ExternalClusterUiCoverRegistration>()
        var secondUiCovered = true
        var failNextUiCover = false
        var onExternalUiCover: (() -> Unit)? = null
        private var secondUiGate: ExternalClusterUiCoverGate? = null
        private var secondUiRegistration: HostPhysicalDisplaySurfaceRegistry.ExternalClusterUiCoverRegistration? = null
        init { bindUiCover(secondSurface, secondTexture) }

        private fun bindUiCover(surface: Surface, texture: SurfaceTexture) {
            val gate = ExternalClusterUiCoverGate { _, covered ->
                if (covered) onExternalUiCover?.invoke()
                if (covered && failNextUiCover) {
                    failNextUiCover = false
                    false
                } else {
                    secondUiCovered = covered
                    true
                }
            }
            val registration = requireNotNull(registry.bindExternalClusterUiCover(
                surface, texture, 4, 101L, gate,
            ))
            uiCovers += registration
            secondUiRegistration = registration
            secondUiGate = gate
        }

        fun simulateExactPostSwapTextureUpdate() {
            assertTrue(requireNotNull(secondUiGate).armUltra(18L) { true })
            assertTrue(requireNotNull(secondUiRegistration).onTextureUpdated(secondTexture, 18L))
        }
        fun simulateMainPostSwapTextureUpdate() {
            assertTrue(firstUiGate.armUltra(19L) { true })
            assertTrue(firstUiRegistration.onTextureUpdated(firstTexture, 19L))
        }
        fun presentExternalPermit(
            permit: AuthorizedExternalClusterUiPermit,
            timestampNanos: Long,
        ): Boolean = registry.armExternalAfterPresentedScene(permit) &&
            requireNotNull(secondUiRegistration).onTextureUpdated(secondTexture, timestampNanos)

        @Suppress("UNCHECKED_CAST")
        fun clusterPhoneEpochGateForTest(): ExternalClusterPhoneEpochGate {
            val target = (AuthorizedAndroidUiSyncPhysicalOutputAuthority::class.java
                .getDeclaredField("activeBoundTargets").apply { isAccessible = true }
                .get(authority) as List<*>)[1]!!
            val gates = AuthorizedAndroidUiSyncPhysicalOutputAuthority::class.java
                .getDeclaredField("clusterPhoneEpochs").apply { isAccessible = true }
                .get(authority) as MutableMap<Any, ExternalClusterPhoneEpochGate>
            return gates.getOrPut(target) { ExternalClusterPhoneEpochGate() }
        }

        fun externalPermitForTest(
            gate: ExternalClusterPhoneEpochGate,
            epoch: Long,
            timestampNanos: Long,
        ): AuthorizedExternalClusterUiPermit {
            val nonce = requireNotNull(AuthorizedAndroidUiSyncPhysicalOutputAuthority::class.java
                .getDeclaredField("externalCoverIssuerNonce").apply { isAccessible = true }
                .get(authority))
            return AuthorizedExternalClusterUiPermit(
                authority, nonce, secondVersion, secondSurface,
                timestampNanos, 1L, 1L, clearBeforeReveal = false,
            ) { gate.isCurrent(epoch) }
        }
        val secondCoverSequence: Long get() = requireNotNull(secondUiGate).currentSequence
        val generation = Generation(identity)
        val token = generation.token
        var currentGeneration = generation
        var generationStatusOverride: UltraProfilePhysicalGenerationStatus? = null
        val authority = AuthorizedAndroidUiSyncPhysicalOutputAuthority(
            profile, requirement, plan, schema, binding, registry, handoff,
            { candidate -> currentGeneration.coordinator.currentPhysicalGenerationProof(candidate) },
            mapOf(DisplayDestination.MAIN to 110, DisplayDestination.CLUSTER to 111),
            generationStatusSource = { candidate ->
                generationStatusOverride ?:
                    currentGeneration.coordinator.physicalGenerationStatus(candidate)
            },
        )
        val issuer = AuthorizedUltraCompiledRuntimeManifestIssuer(ByteArray(32) { 7 })

        fun publishSecond(kind: HostDisplaySurfaceKind) {
            val replacementSurface = surface()
            val replacementTexture = texture()
            secondVersion = requireNotNull(registry.publish(
                DisplayDestination.CLUSTER, kind, replacementSurface, replacementTexture, 800, 480,
                externalTrustToken = if (kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL) {
                    ExternalPhysicalTargetTrustToken.create(
                        registry, externalCalibrationForTest(pixels = PixelSize(800, 480)),
                    ).also { it.bindRouteProof { true } }
                } else null,
            ))
            if (kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL) {
                bindUiCover(replacementSurface, replacementTexture)
            }
        }

        fun closeSecondUiCover() {
            secondUiRegistration?.close()
            secondUiRegistration = null
        }

        fun replaceSecondTrustOnSameSurface() {
            val replacementTrust = ExternalPhysicalTargetTrustToken.create(
                registry, externalCalibrationForTest(pixels = PixelSize(800, 480)),
            ).also { it.bindRouteProof { true } }
            secondVersion = requireNotNull(registry.publish(
                DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                secondSurface, secondTexture, 800, 480, replacementTrust,
            ))
        }

        fun preflight() = authority.preflight(physicalPreflight(requirement, plan, schema, binding))

        fun afterFailedBindCoverDecision(callback: () -> Unit) {
            AuthorizedAndroidUiSyncPhysicalOutputAuthority::class.java.getDeclaredField(
                "afterFailedBindCoverDecisionForTest",
            ).apply { isAccessible = true }.set(authority, callback)
        }

        fun manifest(): AuthorizedUltraCompiledRuntimeManifest {
            val contract = CompiledUiSyncRuntimeContract(
                schema, binding, program,
                "android-ultra-egl-session-coordinator-v1",
                outputs.mapNotNull { it.uiSyncDisplayId?.value },
                outputs, emptyList(), rows, requirement,
            )
            val request = UltraProfileActivationRequest(
                UltraRuntimeIdentity.from("vehicle", identity), identity,
            )
            return issuer.issue(request, profile, contract, null)
        }

        fun register(): Closeable {
            val manifest = manifest()
            authority.seal(manifest, issuer, token)
            return requireNotNull(authority.registerRendererSessionIfSealed(child, coordinator))
        }

        fun openSignedStaticChild(): StaticThemeRendererSession {
            authority.seal(manifest(), issuer, token)
            val completeContext = RendererReadinessReceipt.CompleteSessionBindingContext::class.java
                .declaredConstructors.single { it.parameterCount == 1 }
                .apply { isAccessible = true }
                .newInstance(rendererContext()) as UiSyncCompleteRendererSessionBindingContext
            val contract = StaticThemeRendererContract(
                plan, schema, binding, program,
                outputs.mapNotNull { it.uiSyncDisplayId?.value }.toSet(),
                outputs, emptyList(), rows,
            )
            return StaticThemeRendererSession(
                completeContext, coordinator, program,
                AndroidUltraRendererSessionCoordinator.IMPLEMENTATION_ID,
                authority, contract, false, null, {},
            ).also { it.armAndRegister() }
        }

        fun installSignedSceneBindingsForCoverUnitTest(child: StaticThemeRendererSession) {
            // This suite's FakeBackend deliberately has no EGL submit/PRESENTED implementation.
            // Populate only the immutable Profile-to-pixels bindings so the independently
            // tested Authority cover state machine can be exercised at its exact-child entry.
            val registrationField = AuthorizedAndroidUiSyncPhysicalOutputAuthority::class.java
                .getDeclaredField("registrations").apply { isAccessible = true }
            val registrations = registrationField.get(authority) as Map<*, *>
            val record = requireNotNull(registrations[child])
            record.javaClass.getDeclaredField("sceneBindings")
                .apply { isAccessible = true }
                .set(record, buildAuthorizedSignedSceneBindings(
                    profile, rows,
                    listOf(DisplayDestination.MAIN to PixelSize(800, 480),
                        DisplayDestination.CLUSTER to PixelSize(800, 480)),
                ))
        }

        fun mainCoverGenerationForTest(): MainNonterminalCoverGeneration =
            AuthorizedAndroidUiSyncPhysicalOutputAuthority::class.java
                .getDeclaredField("mainCoverGeneration").apply { isAccessible = true }
                .get(authority) as MainNonterminalCoverGeneration

        fun sessionContext(session: UiSyncCompleteRendererSessionLease = child) =
            physicalSession(requirement, plan, schema, binding, session)

        fun newProbeCoordinator() = makeCoordinator(FakeBackend(), FakeBinder())

        private fun makeCoordinator(
            backendPort: FakeBackend,
            binderPort: FakeBinder,
        ) = AndroidUltraRendererSessionCoordinator(
            rendererContext(), backendPort,
            { AndroidUltraBatchBinderResource(binderPort, binderPort) }, Unit,
        )

        fun sessionReceipt(themeCompilerActive: Boolean = true): RendererReadinessReceipt {
            val constructor = RendererReadinessReceipt.CompleteSessionBindingContext::class.java
                .declaredConstructors.single { it.parameterCount == 1 }
                .apply { isAccessible = true }
            val complete = constructor.newInstance(rendererContext()) as
                RendererReadinessReceipt.CompleteSessionBindingContext
            return complete.issueReadinessReceipt(
                program, emptySet(), emptySet(),
                semanticCompilerActive = true, themeCompilerActive = themeCompilerActive,
                backendLive = true,
            )
        }

        private fun rendererContext(): UiSyncRendererSessionBindingContext {
            val source = object : UiSyncPresentationSource {
                private val hub = UltraPresentationHub(Runnable::run)
                override fun subscribeWithReplay(displayId: String, observer: UltraPresentationObserver):
                    UiSyncPresentationSubscription = UiSyncPresentationSubscription(
                    hub.snapshot(displayId), Closeable {},
                )
                override fun snapshot(displayId: String): UltraPresentationSnapshot = hub.snapshot(displayId)
            }
            val constructor = UiSyncRendererSessionBindingContext::class.java.declaredConstructors
                .single { it.parameterCount == 5 }.apply { isAccessible = true }
            return constructor.newInstance(source, plan, schema, binding,
                outputs.mapNotNull { it.uiSyncDisplayId?.value }.toSet(),
            ) as UiSyncRendererSessionBindingContext
        }
    }

    private class FakeChild(
        override val schemaFingerprint: UiSyncSchemaFingerprint,
        override val bindingFingerprint: UiSyncBindingFingerprint,
        override val readinessReceipt: RendererReadinessReceipt,
        override val exactCoordinator: AndroidUltraRendererSessionCoordinator,
    ) : AuthorizedCoordinatorBackedRendererChild {
        private var live = true
        var onClose: (() -> Unit)? = null
        override val isLive: Boolean get() = live
        override val displayIds = setOf("display-main", "display-cluster")
        override val implementationId = "android-ultra-egl-session-coordinator-v1"
        override fun prepareScreen(context: UiSyncRendererScreenContext): UiSyncRendererScreenCandidate? = null
        override fun retireScreens(): Boolean = true
        override fun close() {
            if (!live) return
            live = false
            onClose?.invoke()
        }
    }

    private class FakeHandoff : PhysicalLegacySurfaceHandoffPort {
        var routeFor111: AndroidMediaSink.PhysicalExternalLegacyRouteLease? = null
        var calls = 0
        var closes = 0
        var externalStarts = 0
        var failBeginAt = -1
        var failCloseOnceForType = -1
        val confirmations = mutableMapOf<Int, Int>()
        var onBegin: ((Int) -> Unit)? = null
        var denyAt = -1
        val visibleTypes = mutableSetOf(110, 111)
        override fun retireAndReserve(type: Int, surface: Surface): PhysicalLegacyHandoffResult {
            calls++
            if (calls == denyAt) return PhysicalLegacyHandoffResult.Denied
            visibleTypes.remove(type)
            return PhysicalLegacyHandoffResult.Reserved(object : PhysicalLegacySurfaceReservation {
                private var held = true
                private var externalUse: Any? = null
                private var externalRetired = false
                override val isHeld: Boolean get() = held
                override val transferredExternalRoute: AndroidMediaSink.PhysicalExternalLegacyRouteLease?
                    get() = if (type == 111) routeFor111 else null
                override fun beginExternalUse(): Any {
                    check(held)
                    if (externalStarts + 1 == failBeginAt) error("partial external-use gate failure")
                    externalStarts++
                    return Any().also { token ->
                        externalUse = token
                        onBegin?.invoke(type)
                    }
                }
                override fun confirmExternalUseRetired(token: Any) {
                    check(held && externalUse === token)
                    confirmations[type] = (confirmations[type] ?: 0) + 1
                    externalRetired = true
                }
                override fun close() {
                    if (held) {
                        check(externalUse == null || externalRetired)
                        if (type == failCloseOnceForType) {
                            failCloseOnceForType = -1
                            error("synthetic exact reservation release failure")
                        }
                        held = false
                        closes++
                    }
                }
            })
        }
    }

    private class FakeBinder : UltraDisplayBatchBinder, Closeable {
        var commits = 0
        var retirements = 0
        var failNextRetirement = false
        var failOnSecondOutput = false
        var failNextCommitUncertain = false
        var failPreparationCloseCount = 0
        var wrongPreparedGeneration = false
        var onPrepare: (() -> Unit)? = null
        var onCommit: (() -> Unit)? = null
        override fun prepare(plan: UltraDisplayBatchPlan): PreparedUltraDisplayBatch {
            assertEquals(2, plan.outputRoutes.size)
            onPrepare?.invoke()
            if (failOnSecondOutput) error("second output cannot be prepared")
            return object : PreparedUltraDisplayBatch {
                override val generation = if (wrongPreparedGeneration) {
                    UltraDisplayBridgeGeneration(plan.generation.value)
                } else plan.generation
                override fun commit(): UltraDisplayBatchLease {
                    if (failNextCommitUncertain) {
                        failNextCommitUncertain = false
                        throw UltraDisplayBatchCommitUncertainException(
                            IllegalStateException("synthetic native commit timeout"),
                        )
                    }
                    onCommit?.invoke()
                    commits++
                    return object : UltraDisplayBatchLease {
                        override val isOperational: Boolean get() = true
                        private val fence = object : UltraDisplayRetirementFence {
                            var retired = false
                            override fun awaitRetirement() {
                                if (failNextRetirement) {
                                    failNextRetirement = false
                                    error("GPU fence is uncertain")
                                }
                                retirements++
                                retired = true
                            }
                            override fun close() { check(retired) }
                        }
                        override fun beginRetirement(): UltraDisplayRetirementFence = fence
                        override fun close() = Unit
                    }
                }
                override fun close() {
                    if (failPreparationCloseCount > 0) {
                        failPreparationCloseCount--
                        error("prepared batch cleanup remains uncertain")
                    }
                }
            }
        }
        override fun close() = Unit
    }

    private class FakeBackend : AndroidUltraRendererBackendPort {
        val retireResults = ArrayDeque<UltraEglBackendRetirementResult>()
        var backendPhase = UltraEglBackendPhase.OPEN
        @Volatile var phaseEntered: CountDownLatch? = null
        @Volatile var phaseRelease: CountDownLatch? = null
        override fun openRemoteIngress(ingress: UltraDisplayGraphIngress): AndroidUltraBackendIngress = error("unused")
        override fun openLocalIngress(contentId: UltraContentId, sizePixels: PixelSize):
            AndroidUltraBackendIngress = error("unused")
        override fun openOverlayIngress(contentId: UltraContentId, sizePixels: PixelSize):
            AndroidUltraBackendIngress = error("unused")
        override fun attachOutput(destination: DisplayDestination, output: UltraDisplayGraphOutput,
            target: AndroidDisplaySurfaceTarget): AndroidUltraBackendOutput = error("unused")
        override fun resolve(plan: UltraResolvedMultiOutputRenderPlan): UltraEglResolvedSubmission = error("unused")
        override fun submit(resolved: UltraEglResolvedSubmission): UltraEglRenderTicket = error("unused")
        override fun phase(): UltraEglBackendPhase {
            phaseEntered?.countDown()
            phaseRelease?.await(5, TimeUnit.SECONDS)
            return backendPhase
        }
        override fun retire(timeoutMillis: Long): UltraEglBackendRetirementResult =
            if (retireResults.isEmpty()) UltraEglBackendRetirementResult.RETIRED
            else retireResults.removeFirst()
    }

    private fun physicalPreflight(
        requirement: UiSyncPhysicalOutputRequirement, plan: UiSyncRendererStaticPlan,
        schema: UiSyncSchemaFingerprint, binding: UiSyncBindingFingerprint,
    ): UiSyncPhysicalOutputPreflightContext =
        UiSyncPhysicalOutputPreflightContext::class.java.declaredConstructors
            .single { it.parameterCount == 4 }.apply { isAccessible = true }
            .newInstance(requirement, plan, schema, binding) as UiSyncPhysicalOutputPreflightContext

    private fun physicalSession(
        requirement: UiSyncPhysicalOutputRequirement, plan: UiSyncRendererStaticPlan,
        schema: UiSyncSchemaFingerprint, binding: UiSyncBindingFingerprint,
        session: UiSyncCompleteRendererSessionLease,
    ): UiSyncPhysicalOutputSessionContext =
        UiSyncPhysicalOutputSessionContext::class.java.declaredConstructors
            .single { it.parameterCount == 5 }.apply { isAccessible = true }
            .newInstance(requirement, plan, schema, binding, session) as UiSyncPhysicalOutputSessionContext

    private fun sharedProvisionedSession(
        fixture: Fixture,
        context: UiSyncPhysicalOutputSessionContext,
        batch: com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBatch,
    ): Closeable {
        val type = Class.forName(
            "com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncProvisionedSession",
        )
        val outboxType = Class.forName(
            "com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestOutbox",
        )
        val outbox = outboxType.declaredConstructors.single { it.parameterCount == 1 }
            .apply { isAccessible = true }.newInstance(setOf("fixture-main")) as Closeable
        val constructor = type.declaredConstructors.single { it.parameterCount == 12 }
            .apply { isAccessible = true }
        val session = try {
            constructor.newInstance(
                UltraPresentationHub(Runnable::run),
                com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpointRegistry(emptyList()),
                outbox, fixture.plan, fixture.schema, fixture.binding, fixture.child,
                fixture.child.readinessReceipt, null, true, { true }, { _: Any -> },
            ) as Closeable
        } catch (failure: Throwable) {
            outbox.close()
            throw failure
        }
        type.declaredMethods.single { it.name.startsWith("armPhysicalBatch") && it.parameterCount == 3 }
            .apply { isAccessible = true }.invoke(session, batch, fixture.authority, context)
        return session
    }

    private inline fun <reified T : Any> fingerprint(fill: Int): T =
        T::class.java.declaredConstructors.single { it.parameterCount == 1 }
            .apply { isAccessible = true }.newInstance(ByteArray(32) { fill.toByte() }) as T

    private fun profile(): VerifiedUltraProfile {
        val packageJson = """{
          "identifier":"physical-test","version":1,"minimumSDKVersion":"1",
          "buildType":"debug","partnerID":"test","driveModeLinkedByDefault":false,
          "displays":[
            {"id":"profile-0","type":"centerConsole","size":[800,480],"density":1,
             "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
             "layouts":[{"id":"one","layoutType":"primary","zones":[],
               "backgroundColor":"#111111","safeAreaInsets":[0,0,0,0],
               "driveModeThemeConfigurations":{}}]},
            {"id":"profile-1","type":"centerConsole","size":[800,480],"density":1,
             "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
             "layouts":[{"id":"one","layoutType":"primary","zones":[],
               "backgroundColor":"#222222","safeAreaInsets":[0,0,0,0],
               "driveModeThemeConfigurations":{}}]}
          ]
        }""".trimIndent().encodeToByteArray()
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                listOf(
                    UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to ByteArray(0),
                    UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to packageJson,
                    UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".encodeToByteArray(),
                    UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to BplistCodec.encode(mapOf("Bundle" to "Phone")),
                    "CarPlayPhone.assets/layout.bin" to byteArrayOf(1),
                    UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to byteArrayOf(2),
                    UltraProfilePackageFiles.CERTIFICATE_CHAIN to byteArrayOf(3),
                    UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to byteArrayOf(4),
                    UltraProfilePackageFiles.ACCESSORY_ADDITIONS to byteArrayOf(5),
                    UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(mapOf("Version" to "1")),
                ).forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path)); zip.write(content); zip.closeEntry()
                }
            }
        }.toByteArray()
        val inspector = UltraProfilePackageInspector()
        val candidate = (inspector.inspect(ByteArrayInputStream(archive)) as
            UltraProfileInspectionResult.Candidate).profile
        val binding = UltraProfileTrustBinding("vehicle",
            "xcertplay-mfi-authentication-v1:${identity.certificateType.name}:${identity.sha256Hex()}",
            "asset", "ios", "accessory", candidate.contentDigest)
        return (inspector.verify(candidate, binding) {
            UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(binding, byteArrayOf(1)))
        } as UltraProfileVerificationResult.Verified).profile
    }

    private val identity: MfiAuthenticationIdentity = MfiAuthenticationIdentity.capture(
        object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor() = 1
            override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(1, 2, 3)
            override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
        },
    )

    private class Generation(identity: MfiAuthenticationIdentity) {
        val coordinator = UltraProfileActivationCoordinator()
        val token: UltraProfileSessionToken

        init {
            val receiptAuthority = Class.forName(
                "com.shilapi.xcertplay.mfi.MfiAuthenticationReceiptAuthority",
            )
            val receiptIssuer = receiptAuthority.declaredConstructors.single { it.parameterCount == 1 }
                .apply { isAccessible = true }.newInstance(identity)
            val begin = receiptAuthority.declaredMethods.single {
                it.name.startsWith("begin") && !it.name.startsWith("beginHandoff") &&
                    it.parameterCount == 2
            }.apply { isAccessible = true }
            val channel = Any()
            val attempt = begin.invoke(receiptIssuer, channel, 1L)
            val issue = attempt.javaClass.declaredMethods.single {
                it.name.startsWith("issue") && it.parameterCount == 2
            }.apply { isAccessible = true }
            val receipt = issue.invoke(attempt, channel, identity) as MfiAuthenticationReceipt
            token = (coordinator.beginSession("vehicle", receipt) as
                UltraProfileSessionStartResult.Started).token
        }
    }

}
