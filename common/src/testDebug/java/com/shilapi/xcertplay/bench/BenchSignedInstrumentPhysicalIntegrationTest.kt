package com.shilapi.xcertplay.bench

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifest
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifestIssuer
import com.shilapi.xcertplay.AuthorizedUltraProfileBackendLease
import com.shilapi.xcertplay.AuthorizedUltraProfileBackendProvisioning
import com.shilapi.xcertplay.AuthorizedUltraProfilePreparation
import com.shilapi.xcertplay.CompiledCafRuntimeContract
import com.shilapi.xcertplay.CompiledInstrumentSignalDisplayAssignment
import com.shilapi.xcertplay.CompiledInstrumentSignalRuntimeContract
import com.shilapi.xcertplay.CompiledUiSyncRuntimeContract
import com.shilapi.xcertplay.UltraProfileActivationBackend
import com.shilapi.xcertplay.UltraProfileActivationCoordinator
import com.shilapi.xcertplay.UltraProfileActivationRequest
import com.shilapi.xcertplay.UltraProfileBackendPrepareResult
import com.shilapi.xcertplay.UltraProfileCommitResult
import com.shilapi.xcertplay.UltraProfilePrepareResult
import com.shilapi.xcertplay.UltraProfileProvisioningOpenResult
import com.shilapi.xcertplay.UltraProfileSessionStartResult
import com.shilapi.xcertplay.UltraProfileSceneUseAttempt
import com.shilapi.xcertplay.UltraProfileVerificationIdentity
import com.shilapi.xcertplay.UltraRuntimeIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.caf.AndroidCarPropertyCafProfileBridge
import com.shilapi.xcertplay.airplay.rcs.uisync.BoundedUiSyncPresentationExecutor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncChannelDescriptor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEvent
import com.shilapi.xcertplay.airplay.rcs.uisync.RendererReadinessReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererCapability
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererFactoryLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncDisplayRuntimeSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestination
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestinationRole
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAssignment
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBackendLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBatch
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
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeAuthorizationExpectation
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeProvisioning
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.media.AndroidVideoSurfaceRetirementResult
import com.shilapi.xcertplay.ultra.AndroidDisplaySurfaceTarget
import com.shilapi.xcertplay.ultra.AndroidUltraBackendIngress
import com.shilapi.xcertplay.ultra.AndroidUltraBackendInstrument
import com.shilapi.xcertplay.ultra.AndroidUltraBackendOutput
import com.shilapi.xcertplay.ultra.AndroidUltraBatchBinderResource
import com.shilapi.xcertplay.ultra.AndroidUltraOutputAttachmentRequest
import com.shilapi.xcertplay.ultra.AndroidUltraOutputBatchLease
import com.shilapi.xcertplay.ultra.AndroidUltraRendererBackendPort
import com.shilapi.xcertplay.ultra.AndroidUltraRendererSessionCoordinator
import com.shilapi.xcertplay.ultra.AxisAlignedPixelAffine
import com.shilapi.xcertplay.ultra.CarPlayLogicalPixelMapper
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplaySurfaceDescriptor
import com.shilapi.xcertplay.ultra.DisplaySurfaceId
import com.shilapi.xcertplay.ultra.NegativeExtentPolicy
import com.shilapi.xcertplay.ultra.PixelEdgeQuantization
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.PreparedUltraDisplayBatch
import com.shilapi.xcertplay.ultra.UltraDisplayBatchBinder
import com.shilapi.xcertplay.ultra.UltraDisplayBatchLease
import com.shilapi.xcertplay.ultra.UltraDisplayBatchPlan
import com.shilapi.xcertplay.ultra.UltraDisplayRetirementFence
import com.shilapi.xcertplay.ultra.UltraMultiOutputRenderSnapshot
import com.shilapi.xcertplay.ultra.UltraResolvedMultiOutputRenderPlan
import com.shilapi.xcertplay.ultra.UltraSceneSource
import com.shilapi.xcertplay.ultra.SignedInstrumentContentKind
import com.shilapi.xcertplay.ultra.SignedInstrumentContentProof
import com.shilapi.xcertplay.ultra.InstrumentReplayPermitDecision
import com.shilapi.xcertplay.ultra.ClusterRecoveryAdmission
import com.shilapi.xcertplay.ultra.ExternalClusterPhoneEpochGate
import com.shilapi.xcertplay.ultra.clusterRecoveryReplayEpochDecision
import com.shilapi.xcertplay.AuthorizedUltraPhysicalOutputSealer
import com.shilapi.xcertplay.AuthorizedUltraProfileProvisioning
import com.shilapi.xcertplay.UltraProfilePhysicalGenerationProof
import com.shilapi.xcertplay.UltraProfileSessionToken
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAuthority
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAvailability
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputPreflightContext
import com.shilapi.xcertplay.ultra.egl.UltraEglInstrumentUploadReceipt as UploadReceipt
import com.shilapi.xcertplay.ultra.UltraThemeLayoutActivationResult
import com.shilapi.xcertplay.ultra.UltraThemeLayoutIntentController
import com.shilapi.xcertplay.ultra.UltraThemeOutputSceneBinding
import com.shilapi.xcertplay.ultra.UltraThemeVerifiedProgramSource
import com.shilapi.xcertplay.ultra.UltraInstrumentReferenceSlotOwner
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendPhase
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendRetirementResult
import com.shilapi.xcertplay.ultra.egl.UltraEglClusterReplayReceipt
import com.shilapi.xcertplay.ultra.egl.UltraEglClusterReplaySubscription
import com.shilapi.xcertplay.ultra.egl.UltraEglClusterReplayOutbox
import com.shilapi.xcertplay.ultra.egl.UltraEglDriver
import com.shilapi.xcertplay.ultra.egl.UltraEglDriverIngress
import com.shilapi.xcertplay.ultra.egl.UltraEglDriverOutput
import com.shilapi.xcertplay.ultra.egl.UltraEglDriverRetirementProof
import com.shilapi.xcertplay.ultra.egl.UltraEglOutputBatchCandidate
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderCore
import com.shilapi.xcertplay.ultra.egl.UltraEglIngressKey
import com.shilapi.xcertplay.ultra.egl.UltraEglInstrumentUploadReceipt
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderResult
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import com.shilapi.xcertplay.ultra.egl.UltraEglResolvedScene
import com.shilapi.xcertplay.ultra.egl.UltraEglResolvedSubmission
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateListener
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateReadSource
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateSubscription
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/** Integration through signed Debug declarations and the coordinator's real issuer/AA05 child.
 * The output batch and EGL are explicit JVM fakes, not a Host panel or an OEM authorization.
 */
class BenchSignedInstrumentPhysicalIntegrationTest {
    private fun awaitTrue(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test fun oneCoreSealedClearReplaySurvivesTransientPhysicalBusyWithoutAnotherFrame() {
        Fixture(busyOnFirstReplay = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED,
                fixture.controller.activate(fixture.child, fixture.profile,
                    fixture.renderer.graphSnapshot(), listOf(fixture.binding), fixture.batch))
            assertTrue(fixture.replaySealed.await(2, TimeUnit.SECONDS))
            assertTrue(fixture.busyProbeSeen.await(2, TimeUnit.SECONDS))
            assertEquals("busy=${fixture.physicalBusy.get()}, " +
                    "outputBatchCurrent=${fixture.outputBatchCurrent}, " +
                    "childLive=${fixture.child.isLive}, " +
                    "renderer=${fixture.renderer.stateSnapshot()}",
                UiSyncPhysicalGenerationStatus.BUSY,
                fixture.batch.generationStatus)
            assertFalse("A BUSY physical batch cannot issue a content proof",
                fixture.proofLatch.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1, fixture.backend.sealedCoreReceipts.size)
            assertEquals(1, fixture.backend.uploads.size)

            fixture.physicalBusy.set(false)
            assertTrue("The sole Core receipt was dropped instead of retried after BUSY",
                fixture.proofLatch.await(3, TimeUnit.SECONDS))
            val proof = requireNotNull(fixture.latestProof.get())
            val replay = requireNotNull(fixture.latestReplay.get())
            assertEquals(SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR,
                proof.contentKind)
            assertTrue(proof.isCurrentFor(replay))
            assertTrue("The signed source opener did not proceed after Clear proof",
                awaitTrue(2_000) { proof.handle.sourceOpenStarted.get() })
            assertEquals(1, fixture.proofCallbackCount.get())
            assertEquals(1, fixture.backend.sealedCoreReceipts.size)
            assertEquals(1, fixture.backend.uploads.size)
            assertEquals(0, fixture.source.readCalls.get()) // No product sealer in this JVM fixture.
        }
    }

    @Test fun issuerValidatedClusterReferenceUploadsUnavailableFrameWithoutCanvas() {
        Fixture().use { fixture ->
            val batch = fixture.batch
            assertTrue(batch.isCurrent)
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED,
                fixture.controller.activate(fixture.child, fixture.profile,
                    fixture.renderer.graphSnapshot(), listOf(fixture.binding), batch))
            assertTrue("controller=${fixture.controller.stateSnapshot()}, " +
                    "renderer=${fixture.renderer.stateSnapshot()}, " +
                    "sealed=${fixture.backend.sealedCoreReceipts.size}",
                fixture.controller.stateSnapshot().activated)
            assertEquals(1, fixture.backend.uploads.size)
            val first = fixture.backend.uploads.single()
            assertEquals(800 * 480, first.size)
            assertTrue(first.all { it ushr 24 == 0xff })
            assertTrue(first.toSet().size > 1) // BENCH marker and DATA UNAVAILABLE are rasterized.
            val scene = fixture.backend.submissions.single().scenes.single()
            assertEquals(DisplayDestination.CLUSTER, scene.destination)
            assertEquals(1, scene.layers.size)
            assertTrue(scene.layers.single().source is UltraSceneSource.Instrument)
            assertEquals(null, fixture.controller.stateSnapshot().localFirstFrameReceipt)
            assertTrue("Core-sealed receipts=${fixture.backend.sealedCoreReceipts.size}, " +
                    "renderer=${fixture.renderer.stateSnapshot()}, owner callback absent",
                fixture.proofLatch.await(3, TimeUnit.SECONDS))
            val proof = requireNotNull(fixture.latestProof.get())
            val replay = requireNotNull(fixture.latestReplay.get())
            assertEquals(SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR,
                proof.contentKind)
            assertTrue(proof.initialSafetyClearEstablished)
            assertTrue(proof.isCurrentFor(replay))
            assertEquals(1L, replay.instrumentFrameSequence)
            assertTrue(fixture.renderer.stateSnapshot().ownedIngresses.single {
                it.key == UltraEglIngressKey.Instrument(DisplayDestination.CLUSTER)
            }.generation != replay.instrumentIngressGeneration)
            assertEquals(batch.generation, replay.nativeOutputBatchGeneration)
            assertEquals(fixture.renderer.graphSnapshot().revision, replay.graphRevision)
            assertEquals(0, fixture.source.readCalls.get())

            fixture.revoke()
            assertFalse(fixture.child.isLive)
            assertFalse(batch.isCurrent)
            assertFalse(proof.isCurrentFor(replay))
            // The owner attempts a SOURCE REVOKED full-frame clear on retirement. Depending on
            // AA05/physical-batch ordering, the port may commit that clear or refuse any upload
            // to the already-retired target. Either way, a post-revoke upload must be safety-only.
            assertTrue(fixture.backend.uploads.size in 1..2)
            val markerPixels = 800 * 30 // 7-pixel font at 2x scale, plus 16-pixel padding.
            fixture.backend.uploads.drop(1).forEach { safetyClear ->
                assertEquals(first.size, safetyClear.size)
                assertTrue(safetyClear.all { it ushr 24 == 0xff })
                assertTrue(first.copyOfRange(0, markerPixels).contentEquals(
                    safetyClear.copyOfRange(0, markerPixels)))
                assertEquals(-0x8d2e2e, safetyClear[0]) // Opaque BENCH warning bar.
                assertTrue(safetyClear.copyOfRange(0, markerPixels).any { it == -1 })
                assertTrue(safetyClear.copyOfRange(markerPixels, safetyClear.size)
                    .any { it == -0x1a66 }) // Unavailable/revoked warning text.
                assertFalse(safetyClear.any { it == -0xd9d6d0 }) // No numeric card.
            }
            assertEquals(0, fixture.source.readCalls.get())
        }
    }

    @Test fun clusterResetRequiresFreshSignedSceneAndNewArmedSafetyClear() {
        Fixture().use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED,
                fixture.activate())
            assertTrue(fixture.proofLatch.await(3, TimeUnit.SECONDS))
            val oldProof = requireNotNull(fixture.latestProof.get())
            val oldReplay = requireNotNull(fixture.latestReplay.get())
            val oldSceneRevision = oldProof.sceneRevision
            val oldFrameSequence = oldProof.instrumentFrameSequence
            val oldUploads = fixture.backend.uploads.size
            assertTrue(oldProof.isCurrentFor(oldReplay))

            assertTrue(fixture.resetClusterPhoneEpoch())
            assertFalse(oldProof.isCurrentFor(oldReplay))
            assertFalse(fixture.acceptsInstrumentProof(oldProof))
            assertTrue(awaitTrue(3_000) { fixture.backend.uploads.size > oldUploads })
            assertSafetyOnly(fixture.backend.uploads.first(), fixture.backend.uploads.last())
            assertEquals(1, fixture.backend.submissions.size)
            assertEquals(1, fixture.proofCallbackCount.get())

            fixture.sendClusterLayout("main", "palette-a")
            assertTrue("A post-reset type-3 did not submit a new signed scene",
                awaitTrue(3_000) { fixture.backend.submissions.size == 2 })
            assertTrue("A new complete safety clear did not arm",
                awaitTrue(3_000) { fixture.proofCallbackCount.get() == 2 })
            val freshProof = requireNotNull(fixture.latestProof.get())
            val freshReplay = requireNotNull(fixture.latestReplay.get())
            assertEquals(SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR,
                freshProof.contentKind)
            assertTrue(freshProof.sceneRevision > oldSceneRevision)
            assertTrue(freshProof.instrumentFrameSequence > oldFrameSequence)
            assertTrue(freshProof.isCurrentFor(freshReplay))
            assertFalse(oldProof.isCurrentFor(oldReplay))
            assertTrue(fixture.acceptsInstrumentProof(freshProof))
            assertTrue(fixture.batch.isCurrent)

            val secondClearFloor = fixture.backend.uploads.size
            assertTrue(fixture.resetClusterPhoneEpoch())
            assertFalse(freshProof.isCurrentFor(freshReplay))
            assertFalse(fixture.acceptsInstrumentProof(freshProof))
            assertTrue(awaitTrue(3_000) {
                fixture.backend.uploads.size > secondClearFloor
            })
            assertSafetyOnly(fixture.backend.uploads.first(), fixture.backend.uploads.last())
            fixture.sendClusterLayout("main", "palette-a")
            assertTrue(awaitTrue(3_000) { fixture.proofCallbackCount.get() == 3 })
            val thirdProof = requireNotNull(fixture.latestProof.get())
            assertEquals(SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR,
                thirdProof.contentKind)
            assertTrue(thirdProof.instrumentFrameSequence > freshProof.instrumentFrameSequence)
            assertTrue(thirdProof.sceneRevision > freshProof.sceneRevision)
            assertTrue(thirdProof.isCurrentFor(requireNotNull(fixture.latestReplay.get())))
        }
    }

    /**
     * Production-ordered RESET (Authority phase 1 before the controller) with the real
     * [ExternalClusterPhoneEpochGate] and the controller recovery witness. The fresh type-3 scene
     * and its post-RESET safety clear arrive before the exact Host cover ACK; that replay must be
     * held (BUSY_RETRY), not dropped, and arm only after the ACK. Numeric values resume only
     * after that armed clear, with fresh pixels on a new sequence.
     */
    @Test fun clusterResetBeforeHostAckArmsFreshClearAfterAckThenNumericOnly() {
        Fixture(gated = true, numericSource = true).use { fixture ->
            fixture.source.setSpeed(1.0)
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activate())
            assertTrue("No initial armed clear: ${fixture.decisions}", awaitTrue(3_000) {
                fixture.armed.any { it.contentKind == SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR }
            })
            assertTrue("No armed numeric value: ${fixture.decisions}, " +
                    "reads=${fixture.source.readCalls.get()}",
                awaitTrue(5_000) { fixture.armed.any {
                    it.contentKind == SignedInstrumentContentKind.NUMERIC_REFERENCE
                } })
            val first = fixture.backend.uploads.first()
            val numericA = fixture.armed.last {
                it.contentKind == SignedInstrumentContentKind.NUMERIC_REFERENCE
            }
            assertTrue(fixture.backend.uploads.any { upload -> upload.any { it == CARD } })
            val uploadsBeforeReset = fixture.backend.uploads.size

            assertTrue(fixture.resetClusterPhoneEpoch())
            assertFalse(fixture.acceptsInstrumentProof(numericA))
            assertTrue(awaitTrue(3_000) { fixture.backend.uploads.size > uploadsBeforeReset })
            // The ingress is immediately overwritten by a complete opaque safety clear.
            val resetClear = fixture.backend.uploads.last()
            assertSafetyOnly(first, resetClear)
            val armedAtReset = fixture.armed.size
            val decisionsAtReset = fixture.decisions.size
            val uploadsAtReset = fixture.backend.uploads.size

            // A dynamic value change cannot repaint numeric pixels before the new armed clear.
            fixture.source.setSpeed(2.0)
            Thread.sleep(300)
            assertEquals(uploadsAtReset, fixture.backend.uploads.size)
            assertEquals(armedAtReset, fixture.armed.size)

            // Fresh signed type-3 and its scene arrive while the Host cover ACK is pending.
            fixture.sendClusterLayout("main", "palette-a")
            assertTrue(awaitTrue(3_000) { fixture.backend.submissions.size == 2 })
            assertTrue("Pre-ACK replay was not held as temporary: ${fixture.decisions}",
                awaitTrue(3_000) {
                    fixture.decisions.contains(InstrumentReplayPermitDecision.BUSY_RETRY)
                })
            Thread.sleep(100)
            assertEquals(armedAtReset, fixture.armed.size)
            assertFalse(fixture.decisions.drop(decisionsAtReset).contains(
                InstrumentReplayPermitDecision.REVOKED))
            fixture.backend.uploads.drop(uploadsBeforeReset).forEach { assertSafetyOnly(first, it) }

            // Exact Host ACK: the same sealed clear replay must now arm without any phone event.
            val uploadsBeforeAck = fixture.backend.uploads.size
            assertTrue(fixture.ackClusterCover())
            assertTrue("Fresh safety clear did not arm after the ACK: ${fixture.decisions}",
                awaitTrue(3_000) { fixture.armed.size > armedAtReset })
            val clear = fixture.armed[armedAtReset]
            assertEquals(SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR, clear.contentKind)
            assertTrue(clear.clearBeforeReveal)
            assertTrue(clear.sceneRevision > numericA.sceneRevision)
            assertTrue(clear.instrumentFrameSequence > numericA.instrumentFrameSequence)
            assertTrue("Numeric value did not resume after the armed clear: ${fixture.decisions}",
                awaitTrue(5_000) { fixture.armed.drop(armedAtReset + 1).any {
                    it.contentKind == SignedInstrumentContentKind.NUMERIC_REFERENCE
                } })
            val numericB = fixture.armed.drop(armedAtReset + 1).first {
                it.contentKind == SignedInstrumentContentKind.NUMERIC_REFERENCE
            }
            assertTrue(numericB.instrumentFrameSequence > clear.instrumentFrameSequence)
            assertTrue(numericB.sceneRevision > numericA.sceneRevision)
            assertTrue(fixture.backend.uploads.drop(uploadsBeforeAck).any { upload ->
                upload.any { it == CARD }
            })
            assertFalse(fixture.acceptsInstrumentProof(numericA))
            assertTrue(fixture.batch.isCurrent)
        }
    }

    /** Without a fresh signed type-3, an ACKed RESET never re-arms the post-RESET clear. */
    @Test fun clusterResetWithoutFreshTypeThreeNeverArmsEvenAfterHostAck() {
        Fixture(gated = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activate())
            assertTrue(fixture.proofLatch.await(3, TimeUnit.SECONDS))
            val armedBefore = fixture.armed.size
            val uploads = fixture.backend.uploads.size
            assertTrue(fixture.resetClusterPhoneEpoch())
            assertTrue(fixture.ackClusterCover())
            assertTrue(awaitTrue(3_000) { fixture.backend.uploads.size > uploads })
            Thread.sleep(300)
            assertEquals(armedBefore, fixture.armed.size)
            assertEquals(1, fixture.backend.submissions.size)
            assertTrue(fixture.batch.isCurrent)
        }
    }

    /** A persistently rejected post-RESET clear is bounded and then takes the terminal path. */
    @Test fun postResetSafetyClearThatNeverCommitsRetiresPhysicalOutputAfterDeadline() {
        Fixture().use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activate())
            assertTrue(fixture.proofLatch.await(3, TimeUnit.SECONDS))
            val uploads = fixture.backend.uploads.size
            fixture.backend.rejectUploads.set(true)
            assertTrue(fixture.resetClusterPhoneEpoch())
            Thread.sleep(450) // At least two bounded 200 ms retries inside the window.
            assertTrue("Retired before the bounded recovery window", fixture.batch.isCurrent)
            fixture.ownerClockSkewNanos.set(TimeUnit.SECONDS.toNanos(6))
            assertTrue("Post-RESET clear retried forever instead of going terminal",
                awaitTrue(3_000) { !fixture.batch.isCurrent })
            assertEquals(uploads, fixture.backend.uploads.size)
            assertEquals(1, fixture.proofCallbackCount.get())
        }
    }

    private fun assertSafetyOnly(first: IntArray, upload: IntArray) {
        val markerPixels = 800 * 30
        assertEquals(first.size, upload.size)
        assertTrue(upload.all { it ushr 24 == 0xff })
        assertTrue(first.copyOfRange(0, markerPixels).contentEquals(
            upload.copyOfRange(0, markerPixels)))
        assertEquals(-0x8d2e2e, upload[0]) // Opaque BENCH warning bar.
        assertTrue(upload.copyOfRange(0, markerPixels).any { it == -1 })
        assertTrue(upload.copyOfRange(markerPixels, upload.size).any { it == -0x1a66 })
        assertFalse(upload.any { it == CARD }) // No numeric card.
    }

    private class Fixture(busyOnFirstReplay: Boolean = false,
        private val gated: Boolean = false,
        numericSource: Boolean = false,
    ) : Closeable {
        val identity = MfiAuthenticationIdentity.capture(object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor() = 1
            override fun readCertificate(maximumOutputLength: Int) =
                "signed-instrument-physical-test".encodeToByteArray()
            override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
        })
        val profile = profile(identity)
        private val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID.fromString(AirPlayInfoPlist.ALT_UUID)),
            UltraDisplayOutputId.Android("host.cluster"), UltraUiSyncDisplayId("ui.cluster"),
        )
        private val declared = BenchUiSyncProfileCompiler().compile(profile,
            listOf(BenchUiSyncHostOutput(0, AirPlayInfoPlist.ALT_UUID,
                DisplayDestination.CLUSTER, output)), emptyList())
        private val cafCompiled = BenchCafProfileCompiler().compile(profile)
        private val bridge = AndroidCarPropertyCafProfileBridge.fromBackend(
            cafCompiled.profile, cafCompiled.syntheticBackend())
        val source = UnavailableSource(cafCompiled.profile.configTree)
        private val batchRef = AtomicReference<UiSyncPhysicalOutputBatch?>()
        /** Debug-only source join; there is no Host authority behind this JVM fixture. */
        private val sealer: AuthorizedUltraPhysicalOutputSealer? =
            if (!numericSource) null else object : AuthorizedUltraPhysicalOutputSealer {
            override val authority = object : UiSyncPhysicalOutputAuthority {
                override fun preflight(context: UiSyncPhysicalOutputPreflightContext):
                    UiSyncPhysicalOutputAvailability? = null
                override fun bindSession(context: UiSyncPhysicalOutputSessionContext):
                    UiSyncPhysicalOutputBatch? = null
            }
            override fun authorizesInstrumentSource(batch: UiSyncPhysicalOutputBatch,
                provisioning: AuthorizedUltraProfileProvisioning,
                profile: VerifiedUltraProfile) =
                batch === batchRef.get() && provisioning === child && batch.isCurrent
            override fun seal(manifest: AuthorizedUltraCompiledRuntimeManifest,
                issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
                token: UltraProfileSessionToken, proof: UltraProfilePhysicalGenerationProof,
                provisioning: AuthorizedUltraProfileProvisioning) = error("No product seal")
        }
        private val callbacks = BoundedUiSyncPresentationExecutor(1, 64)
        private val ui = authorizedUi(profile, declared.outputs,
            declared.semanticThemeProgramFingerprint, callbacks)
        private val snapshot = requireNotNull(ui.authorizationSnapshot())
        private val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from(profile.binding.vehicleId, identity), identity)
        private val issuer = AuthorizedUltraCompiledRuntimeManifestIssuer(ByteArray(32) { 0x33 })
        private val signedCrosswalk = requireNotNull(
            BenchInstrumentSignalProfileCompiler().compile(profile))
        private val manifest = issuer.issue(request, profile,
            CompiledUiSyncRuntimeContract.from(snapshot, declared.assignments),
            CompiledCafRuntimeContract.from(requireNotNull(
                bridge.provisioning.authorizationSnapshot())),
            CompiledInstrumentSignalRuntimeContract(profile,
                profile.candidate.entries.single {
                    it.name == BenchInstrumentSignalProfileCompiler.SPEC_PATH
                }.digest, signedCrosswalk.phonePackageDigest,
                requireNotNull(bridge.provisioning.authorizationSnapshot()).schemaFingerprint,
                signedCrosswalk.bindings.map {
                    CompiledInstrumentSignalDisplayAssignment(
                        it.profileDisplayIndex, it.profileDisplayId, it.spec)
                }),
        )
        private val resources = BackendResources(ui, callbacks, bridge, source, sealer)
        private val activation = UltraProfileActivationCoordinator::class.java.getDeclaredConstructor(
            UltraProfileActivationBackend::class.java,
            AuthorizedUltraCompiledRuntimeManifestIssuer::class.java,
        ).apply { isAccessible = true }.newInstance(
            UltraProfileActivationBackend { current ->
                check(current.runtimeIdentity == request.runtimeIdentity)
                UltraProfileBackendPrepareResult.Prepared(
                    Preparation(request, profile, manifest, resources),
                )
            }, issuer,
        )
        private val receipt = receipt(identity)
        private val token = (activation.beginSession(profile.binding.vehicleId,
            receipt.first) as UltraProfileSessionStartResult.Started).token
        private val prepared = (activation.prepare(token,
            receipt.first) as UltraProfilePrepareResult.Prepared).activation
        private val lease = (activation.commit(prepared,
            receipt.first) as UltraProfileCommitResult.Activated).lease
        val child = (lease.openRuntime() as UltraProfileProvisioningOpenResult.Opened).runtimeLease

        private val presentation = HubPresentationSource()
        val backend = RecordingBackend()
        val physicalBusy = AtomicBoolean(false)
        val replaySealed = CountDownLatch(1)
        val busyProbeSeen = CountDownLatch(1)
        init {
            if (busyOnFirstReplay) {
                val first = AtomicBoolean(true)
                backend.onCoreReplaySealed = {
                    if (first.compareAndSet(true, false)) {
                        physicalBusy.set(true)
                        replaySealed.countDown()
                    }
                }
            }
        }
        val renderer = AndroidUltraRendererSessionCoordinator(
            sessionContext(presentation, output, snapshot), backend,
            { AndroidUltraBatchBinderResource(OneOutputBatchBinder(backend), Closeable {}) }, Unit,
        )
        init { backend.attachRenderer(renderer) }
        val ownerClockSkewNanos = AtomicLong()
        // The JVM bundle uses System.nanoTime when Android's boot clock is a stub; the raster must
        // read that same clock for its numeric freshness checks.
        private val instrumentOwner = UltraInstrumentReferenceSlotOwner(renderer,
            { System.nanoTime() + ownerClockSkewNanos.get() }, { System.nanoTime() })
        val proofLatch = CountDownLatch(1)
        val proofCallbackCount = AtomicInteger()
        val latestProof = AtomicReference<SignedInstrumentContentProof?>()
        val latestReplay = AtomicReference<UltraEglClusterReplayReceipt?>()
        /** Same production epoch gate/witness halves the Authority composes for this replay. */
        private val epochGate = ExternalClusterPhoneEpochGate()
        private val pendingCoverEpoch = AtomicLong()
        val decisions = CopyOnWriteArrayList<InstrumentReplayPermitDecision>()
        val armed = CopyOnWriteArrayList<SignedInstrumentContentProof>()
        init {
            instrumentOwner.bindPrivateReplayCallback { proof, replay ->
                proofCallbackCount.incrementAndGet()
                latestProof.set(proof)
                latestReplay.set(replay)
                val decision = if (!gated) InstrumentReplayPermitDecision.ARMED else {
                    val witness = {
                        controller.clusterRecoveryWitness(UltraUiSyncDisplayId("ui.cluster"),
                            replay.sceneRevision)
                    }
                    val admission = epochGate.recoveryAdmission(replay.sceneRevision,
                        replay.producerTimestampNanos)
                    clusterRecoveryReplayEpochDecision(admission, witness) ?: if (witness() &&
                        epochGate.commitArmed((admission as ClusterRecoveryAdmission.Admitted)
                            .epoch, replay.sceneRevision, replay.producerTimestampNanos)
                    ) InstrumentReplayPermitDecision.ARMED
                    else InstrumentReplayPermitDecision.REVOKED
                }
                decisions += decision
                if (decision == InstrumentReplayPermitDecision.ARMED) {
                    armed += proof
                    proofLatch.countDown()
                }
                decision
            }
        }
        val controller = UltraThemeLayoutIntentController.arm(renderer,
            listOf(UltraUiSyncDisplayId("ui.cluster")), instrumentOwner = instrumentOwner)
        fun activate(): UltraThemeLayoutActivationResult = controller.activate(
            child, profile, renderer.graphSnapshot(), listOf(binding), batch)
        fun resetClusterPhoneEpoch(): Boolean {
            // Production order: Authority phase 1 revokes before the controller/owner observer.
            if (gated) {
                pendingCoverEpoch.set(epochGate.beginCover())
                epochGate.replayLedger.invalidate()
            }
            return controller.invalidatePhoneEpoch(
                UltraUiSyncDisplayId("ui.cluster"), presentation.snapshot("ui.cluster").revision)
        }
        fun ackClusterCover(): Boolean = epochGate.acknowledge(pendingCoverEpoch.get())
        fun sendClusterLayout(layoutId: String, paletteId: String) =
            presentation.sendLayout(layoutId, paletteId)
        fun acceptsInstrumentProof(proof: SignedInstrumentContentProof): Boolean =
            instrumentOwner.issuedContentProof(proof)
        private val outputBatchLease = renderer.replaceOutputBatch(listOf(
            AndroidUltraOutputAttachmentRequest(DisplayDestination.CLUSTER, output,
                target()),
        ))
        val outputBatchCurrent get() = outputBatchLease.isCurrent
        val batch = physicalBatch(profile, output, snapshot, renderer, outputBatchLease,
            { child.tryWithSceneSubmissionUse { Unit } }, physicalBusy, busyProbeSeen)
            .also(batchRef::set)
        val binding = UltraThemeOutputSceneBinding(
            UltraThemeVerifiedProgramSource(profile,
                UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
                declared.assignments.single().phonePackageDigest,
                declared.assignments.single().programSelection.structuralFingerprint,
                0, 0, declared.assignments.single().programSelection.stateSnapshot()),
            DisplayDestination.CLUSTER, output.outputId, PixelSize(800, 480),
            CarPlayLogicalPixelMapper(AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
                NegativeExtentPolicy.REJECT,
                PixelEdgeQuantization.REQUIRE_INTEGRAL,
                PixelEdgeQuantization.REQUIRE_INTEGRAL),
            emptyList(),
        )

        fun revoke() = receipt.second()

        override fun close() {
            controller.close()
            instrumentOwner.close()
            batch.close()
            outputBatchLease.close()
            renderer.close()
            presentation.close()
            activation.close()
            receipt.third.close()
        }
    }

    private class BackendResources(
        val ui: UiSyncRuntimeProvisioning,
        val callbacks: BoundedUiSyncPresentationExecutor,
        val bridge: AndroidCarPropertyCafProfileBridge,
        val source: UnavailableSource,
        val sealer: AuthorizedUltraPhysicalOutputSealer?,
    ) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                source.close()
                ui.close()
                callbacks.close()
                bridge.close()
            }
        }
    }

    private class Preparation(
        private val request: UltraProfileActivationRequest,
        override val verifiedProfile: VerifiedUltraProfile,
        override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
        private val resources: BackendResources,
    ) : AuthorizedUltraProfilePreparation {
        override val requestIdentity = request.runtimeIdentity
        override val authenticationIdentity = request.authenticationIdentity
        private var transferred = false
        override fun commit(verificationIdentity: UltraProfileVerificationIdentity):
            AuthorizedUltraProfileBackendLease {
            transferred = true
            return BackendLease(verificationIdentity, compiledManifest, resources)
        }
        override fun close() { if (!transferred) resources.close() }
    }

    private class BackendLease(
        override val verificationIdentity: UltraProfileVerificationIdentity,
        override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
        private val resources: BackendResources,
    ) : AuthorizedUltraProfileBackendLease {
        private val live = AtomicBoolean(true)
        override val isLive get() = live.get()
        override fun openProvisioning(): AuthorizedUltraProfileBackendProvisioning {
            check(live.get())
            return object : AuthorizedUltraProfileBackendProvisioning {
                private val childLive = AtomicBoolean(true)
                override val verificationIdentity = this@BackendLease.verificationIdentity
                override val compiledManifest = this@BackendLease.compiledManifest
                override val isLive get() = childLive.get() && live.get()
                override val uiSyncProvisioning get() = resources.ui
                override val cafProvisioning get() = resources.bridge.provisioning
                override val instrumentReadSource: VehicleStateReadSource get() = resources.source
                // No product physical sealer unless a numeric test supplies a batch-exact JVM
                // join. Without it, the async source must fail closed.
                override val physicalOutputSealer get() = resources.sealer
                override fun close() { childLive.set(false); resources.close() }
            }
        }
        override fun close() { live.set(false); resources.close() }
    }

    /** Unavailable by default; a numeric test sets a consistent typed speed triple. */
    private class UnavailableSource(
        override val configTree: com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree,
    ) : VehicleStateReadSource, Closeable {
        private val live = AtomicBoolean(true)
        val readCalls = AtomicInteger()
        @Volatile private var metresPerSecond: Double? = null
        private val listeners = CopyOnWriteArrayList<VehicleStateListener>()
        override val isCurrent get() = live.get()
        fun setSpeed(value: Double) {
            metresPerSecond = value
            listeners.forEach { it.onUpdate(com.shilapi.xcertplay.vehicle.ultra
                .VehicleStateUpdate(emptyMap())) }
        }
        override fun read(iids: Set<Long>): VehicleStateResult {
            readCalls.incrementAndGet()
            val speed = metresPerSecond ?: return VehicleStateResult(emptyMap())
            return VehicleStateResult(
                values = mapOf(100L to speed, 101L to speed * 3.6, 102L to speed * 2.24)
                    .filterKeys { it in iids },
                sourceAgeMillisByIid = iids.associateWith { 0L },
            )
        }
        override fun subscribe(iids: Set<Long>, listener: VehicleStateListener):
            VehicleStateSubscription {
            listeners += listener
            return VehicleStateSubscription { listeners -= listener }
        }
        override fun close() { live.set(false) }
    }

    private class HubPresentationSource : UiSyncPresentationSource, Closeable {
        private val hub = UltraPresentationHub(Runnable::run)
        private var observer: UltraPresentationObserver? = null
        override fun subscribeWithReplay(displayId: String,
            observer: UltraPresentationObserver): UiSyncPresentationSubscription {
            this.observer = observer
            return UiSyncPresentationSubscription(hub.snapshot(UltraUiSyncDisplayId(displayId)),
                Closeable { if (this.observer === observer) this.observer = null })
        }
        override fun snapshot(displayId: String): UltraPresentationSnapshot =
            hub.snapshot(UltraUiSyncDisplayId(displayId))
        fun sendLayout(layoutId: String, paletteId: String) {
            val id = UltraUiSyncDisplayId("ui.cluster")
            hub.sink(id).onPresentationEvent(CarPlayUiSyncPresentationEvent.KnownCommand(
                CarPlayUiSyncCommandType.LAYOUT_CHANGE,
                mapOf("layoutID" to layoutId, "activeComponents" to emptyMap<String, String>(),
                    "paletteID" to paletteId),
            ))
            observer?.onPresentationSnapshot(hub.snapshot(id))
        }
        override fun close() = hub.close()
    }

    private class OneOutputBatchBinder(private val backend: RecordingBackend) :
        UltraDisplayBatchBinder {
        private var live = false
        override fun prepare(plan: UltraDisplayBatchPlan): PreparedUltraDisplayBatch {
            check(plan.outputRoutes.size == 1 && !live)
            return object : PreparedUltraDisplayBatch {
                override val generation = plan.generation
                override fun commit(): UltraDisplayBatchLease {
                    backend.installNativeBatch(plan.generation.value)
                    live = true
                    return object : UltraDisplayBatchLease {
                        private var retired = false
                        override fun beginRetirement() = object : UltraDisplayRetirementFence {
                            override fun awaitRetirement() {
                                backend.retireNativeBatch(plan.generation.value)
                                retired = true
                            }
                            override fun close() { check(retired) }
                        }
                        override fun close() { check(retired); live = false }
                    }
                }
                override fun close() = Unit
            }
        }
    }

    private class RecordingBackend : AndroidUltraRendererBackendPort {
        val uploads = CopyOnWriteArrayList<IntArray>()
        val submissions = CopyOnWriteArrayList<UltraMultiOutputRenderSnapshot>()
        val sealedCoreReceipts = CopyOnWriteArrayList<UltraEglClusterReplayReceipt>()
        val rejectUploads = AtomicBoolean(false)
        var onCoreReplaySealed: (() -> Unit)? = null
        private val driver = RecordingDriver()
        private val core = UltraEglRenderCore(driver) {}
        private val replayOutbox = UltraEglClusterReplayOutbox()
        private lateinit var renderer: AndroidUltraRendererSessionCoordinator
        private var nativeBatchGeneration: Long? = null
        private var instrumentGeneration: Long? = null
        private var closed = false
        init { core.initialize() }

        fun attachRenderer(renderer: AndroidUltraRendererSessionCoordinator) {
            check(!::renderer.isInitialized)
            this.renderer = renderer
        }

        @Synchronized fun installNativeBatch(generation: Long) {
            check(nativeBatchGeneration == null)
            core.replaceOutputBatch(generation, listOf(UltraEglOutputBatchCandidate(
                DisplayDestination.CLUSTER, generation, Any(), PixelSize(800, 480), { true },
            )))
            nativeBatchGeneration = generation
        }

        @Synchronized fun retireNativeBatch(generation: Long) {
            replayOutbox.revoke()
            core.retireOutputBatch(generation)
            if (nativeBatchGeneration == generation) nativeBatchGeneration = null
        }

        private fun dispatchCoreReceipt(receipt: UltraEglClusterReplayReceipt) {
            sealedCoreReceipts += receipt
            onCoreReplaySealed?.invoke()
            // Production always publishes onto this separate non-EGL delivery thread.
            replayOutbox.publish(receipt)
        }
        override fun openRemoteIngress(ingress: com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress):
            AndroidUltraBackendIngress = error("No remote video in signed instrument-only scene")
        override fun openLocalIngress(contentId: com.shilapi.xcertplay.ultra.UltraContentId,
            sizePixels: PixelSize): AndroidUltraBackendIngress =
            error("No Local Canvas in signed instrument-only scene")
        override fun openOverlayIngress(contentId: com.shilapi.xcertplay.ultra.UltraContentId,
            sizePixels: PixelSize): AndroidUltraBackendIngress = error("No overlay")
        @Synchronized override fun openInstrumentRaster(destination: DisplayDestination,
            sizePixels: PixelSize): AndroidUltraBackendInstrument {
            val key = UltraEglIngressKey.Instrument(destination)
            val reservationGeneration = renderer.stateSnapshot().ownedIngresses.single {
                it.key == key
            }.generation
            // Coordinator resource generations and EGL texture generations are independent.
            val generation = reservationGeneration + 100L
            check(instrumentGeneration == null)
            core.openInstrumentIngress(destination, generation, sizePixels)
            instrumentGeneration = generation
            return AndroidUltraBackendInstrument(key, sizePixels, { pixels ->
                if (rejectUploads.get()) UploadReceipt.REJECTED_NO_COMMIT
                else synchronized(this) {
                    uploads += pixels.copyOf()
                    core.replaceInstrumentFrame(destination, generation, sizePixels, pixels)
                        ?.let(::dispatchCoreReceipt)
                    UltraEglInstrumentUploadReceipt.COMMITTED
                }
            }, {
                synchronized(this) {
                    replayOutbox.revoke()
                    core.quiesceIngress(key, generation)
                    core.destroyIngress(key, generation)
                    if (instrumentGeneration == generation) instrumentGeneration = null
                }
            }, generation = generation)
        }
        override fun subscribeClusterReplay(
            instrument: AndroidUltraBackendInstrument,
            onReceipt: (UltraEglClusterReplayReceipt) -> Unit,
            onRevoked: () -> Unit,
        ): UltraEglClusterReplaySubscription {
            check(instrument.key == UltraEglIngressKey.Instrument(DisplayDestination.CLUSTER))
            // Neither test code nor a caller supplies a receipt; Core seals, and the same
            // production outbox as Android EGL dispatches it off the submit stack.
            return replayOutbox.subscribe(onReceipt, onRevoked)
        }
        override fun attachOutput(destination: DisplayDestination,
            output: UltraDisplayGraphOutput,
            target: AndroidDisplaySurfaceTarget) = AndroidUltraBackendOutput(destination) {}
        @Synchronized override fun resolve(plan: UltraResolvedMultiOutputRenderPlan) =
            UltraEglResolvedSubmission(
                UltraMultiOutputRenderSnapshot(plan.renderRevision,
                    plan.scenes.map { it.scene }), plan.graphRevision,
                mapOf(UltraEglIngressKey.Instrument(DisplayDestination.CLUSTER) to
                    checkNotNull(instrumentGeneration)),
                mapOf(DisplayDestination.CLUSTER to checkNotNull(nativeBatchGeneration)),
            )
        @Synchronized override fun submit(resolved: UltraEglResolvedSubmission): UltraEglRenderTicket {
            submissions += resolved.scene
            val sealed = core.submit(resolved)
            return UltraEglRenderTicket(resolved.revision).also { ticket ->
                ticket.complete(UltraEglRenderResult(resolved.revision,
                    UltraEglRenderDisposition.PRESENTED,
                    producerTimestampNanosByDestination =
                        sealed.producerTimestampNanosByDestination,
                    outputGenerationsByDestination = sealed.outputGenerationsByDestination,
                    outputBatchGeneration = sealed.outputBatchGeneration,
                    graphRevision = sealed.graphRevision))
                sealed.clusterReplay?.let(::dispatchCoreReceipt)
            }
        }
        override fun phase() = if (closed) UltraEglBackendPhase.CLOSED else UltraEglBackendPhase.OPEN
        @Synchronized override fun retire(timeoutMillis: Long): UltraEglBackendRetirementResult {
            closed = true
            check(replayOutbox.retire(timeoutMillis))
            instrumentGeneration?.let { generation ->
                val key = UltraEglIngressKey.Instrument(DisplayDestination.CLUSTER)
                core.quiesceIngress(key, generation)
                core.destroyIngress(key, generation)
                instrumentGeneration = null
            }
            return if (core.closeDriver(System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(timeoutMillis)).retired
            ) UltraEglBackendRetirementResult.RETIRED
            else UltraEglBackendRetirementResult.DRIVER_RETIREMENT_UNCERTAIN
        }
    }

    /** Real Core state machine, inert GL driver: only output timestamps are synthetic. */
    private class RecordingDriver : UltraEglDriver {
        private class Ingress : UltraEglDriverIngress {
            override val producerEndpoint = Any()
            override var hasUsableFrame = false
        }
        private class Output(val destination: DisplayDestination,
            val size: PixelSize) : UltraEglDriverOutput
        private var nextTimestamp = 100L

        override fun initialize() = Unit
        override fun createExternalIngress(key: UltraEglIngressKey, sizePixels: PixelSize?,
            onFrameAvailable: (UltraEglDriverIngress) -> Unit): UltraEglDriverIngress =
            error("Instrument-only test has no external ingress")
        override fun createInstrumentIngress(destination: DisplayDestination,
            sizePixels: PixelSize): UltraEglDriverIngress = Ingress()
        override fun uploadInstrumentFrame(ingress: UltraEglDriverIngress,
            argbPixels: IntArray) {
            check(ingress is Ingress && argbPixels.isNotEmpty())
            ingress.hasUsableFrame = true
        }
        override fun updateIngressFrame(ingress: UltraEglDriverIngress) =
            error("Instrument-only test has no SurfaceTexture callback")
        override fun createWindowOutput(destination: DisplayDestination, nativeWindow: Any,
            sizePixels: PixelSize): UltraEglDriverOutput = Output(destination, sizePixels)
        override fun validateOutputSize(output: UltraEglDriverOutput,
            expectedSize: PixelSize) {
            check(output is Output && output.size == expectedSize)
        }
        override fun render(output: UltraEglDriverOutput, scene: UltraEglResolvedScene) {
            check(output is Output && output.destination == scene.destination &&
                scene.layers.all { it.ingress?.hasUsableFrame == true })
        }
        override fun renderWithPresentationReceipt(output: UltraEglDriverOutput,
            scene: UltraEglResolvedScene): Long {
            render(output, scene)
            return ++nextTimestamp
        }
        override fun clear(output: UltraEglDriverOutput, destination: DisplayDestination,
            sizePixels: PixelSize) = Unit
        override fun finish() = Unit
        override fun destroyIngress(ingress: UltraEglDriverIngress) = Unit
        override fun destroyOutput(output: UltraEglDriverOutput) = Unit
        override fun closeWithProof(deadlineNanos: Long) = UltraEglDriverRetirementProof(true)
    }

    private companion object {
        const val CARD = -0xd9d6d0

        fun profile(identity: MfiAuthenticationIdentity): VerifiedUltraProfile {
            val instrument = BenchInstrumentSignalProfileCompilerTest
            val packageJson = instrument.THEME_PACKAGE.replace(
                "\"safeAreaInsets\":{}", "\"safeAreaInsets\":[0,0,0,0]",
            ).replace("\"backgroundColor\":\"black\"",
                "\"backgroundColor\":\"#000000\"").replace(
                "\"backgroundColor\":\"#000000\"}}",
                "\"backgroundColor\":\"#000000\",\"clipsToShape\":false}}",
            )
            val catalog = """{"format":"xcertplay-debug-uisync-v1","displays":[{
                "profileDisplayIndex":0,"profileDisplayId":"cluster-display",
                "advertisedScreenId":"${AirPlayInfoPlist.ALT_UUID}",
                "destinationRole":"CLUSTER","destinationInstanceId":"default",
                "topologyDisplayId":"${AirPlayInfoPlist.ALT_UUID}",
                "outputId":{"kind":"android","value":"host.cluster"},
                "uiSyncDisplayId":"ui.cluster","layouts":[{
                "layoutIndex":0,"layoutID":"main","paletteID":"palette-a",
                "activeComponents":{},"activePrimarySelections":[{"zoneIndex":0,"componentIndex":0}],
                "slotIngresses":[]}]}
              ]}""".trimIndent()
            val files = listOf(
                UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to byteArrayOf(),
                UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to packageJson.encodeToByteArray(),
                UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".encodeToByteArray(),
                UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                    BplistCodec.encode(mapOf("Bundle" to "DEBUG.BENCH")),
                BenchUiSyncProfileCompiler.SPEC_PATH to catalog.encodeToByteArray(),
                BenchCafProfileCompiler.SPEC_PATH to instrument.CAF_SPEC.encodeToByteArray(),
                BenchInstrumentSignalProfileCompiler.SPEC_PATH to
                    instrument.SPEED_SPEC.encodeToByteArray(),
                UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to byteArrayOf(2),
                UltraProfilePackageFiles.CERTIFICATE_CHAIN to byteArrayOf(3),
                UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to byteArrayOf(4),
                UltraProfilePackageFiles.ACCESSORY_ADDITIONS to byteArrayOf(5),
                UltraProfilePackageFiles.INFO_PLIST to
                    BplistCodec.encode(mapOf("Version" to "1")),
            )
            val bytes = ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { zip ->
                    files.forEach { (name, content) ->
                        zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
                    }
                }
            }.toByteArray()
            val inspector = UltraProfilePackageInspector()
            val candidate = (inspector.inspect(ByteArrayInputStream(bytes)) as
                UltraProfileInspectionResult.Candidate).profile
            val binding = UltraProfileTrustBinding("vehicle",
                "xcertplay-mfi-authentication-v1:${identity.certificateType.name}:${identity.sha256Hex()}",
                "asset", "ios", "accessory", candidate.contentDigest)
            return (inspector.verify(candidate, binding) {
                UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(binding,
                    byteArrayOf(1)))
            } as UltraProfileVerificationResult.Verified).profile
        }

        fun authorizedUi(profile: VerifiedUltraProfile,
            outputs: List<UltraDisplayGraphOutput>,
            fingerprint: com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSemanticThemeProgramFingerprint,
            callbacks: BoundedUiSyncPresentationExecutor): UiSyncRuntimeProvisioning {
            val implementation = "test.signed.instrument.renderer"
            val capability = object : UiSyncCompleteRendererCapability {
                override fun acquire(context: UiSyncCompleteRendererBindingContext):
                    UiSyncCompleteRendererFactoryLease = object : UiSyncCompleteRendererFactoryLease {
                    private var live = true
                    override val isLive get() = live
                    override val implementationId = implementation
                    override val schemaFingerprint = context.schemaFingerprint
                    override val bindingFingerprint = context.bindingFingerprint
                    override val displayIds = context.displayIds
                    override val readinessReceipt: RendererReadinessReceipt =
                        context.issueReadinessReceipt(fingerprint, emptySet(), emptySet(),
                            semanticCompilerActive = true, themeCompilerActive = true,
                            backendLive = true)
                    override fun openSession(context: UiSyncCompleteRendererSessionBindingContext):
                        UiSyncCompleteRendererSessionLease = object : UiSyncCompleteRendererSessionLease {
                        private var live = true
                        override val isLive get() = live
                        override val implementationId = implementation
                        override val schemaFingerprint = context.schemaFingerprint
                        override val bindingFingerprint = context.bindingFingerprint
                        override val displayIds = context.displayIds
                        override val readinessReceipt = context.issueReadinessReceipt(
                            fingerprint, emptySet(), emptySet(),
                            semanticCompilerActive = true, themeCompilerActive = true,
                            backendLive = true)
                        override fun prepareScreen(context: UiSyncRendererScreenContext):
                            UiSyncRendererScreenCandidate? = null
                        override fun retireScreens() = true
                        override fun close() { live = false }
                    }
                    override fun close() { live = false }
                }
            }
            return UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
                AirPlayUiSyncInfo(), listOf(UiSyncDisplayRuntimeSpec(
                    CarPlayUiSyncChannelDescriptor("ui.cluster", profile.binding.vehicleId),
                    CarPlayUiSyncMode.PASSIVE,
                )), UiSyncRendererStaticPlan(outputs, emptyList()), capability, callbacks,
                UiSyncRuntimeAuthorizationExpectation(fingerprint, implementation),
            )
        }

        fun sessionContext(source: UiSyncPresentationSource, output: UltraDisplayGraphOutput,
            snapshot: com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeAuthorizationSnapshot):
            UiSyncRendererSessionBindingContext =
            UiSyncRendererSessionBindingContext::class.java.declaredConstructors.single {
                it.parameterCount == 5
            }.apply { isAccessible = true }.newInstance(source,
                UiSyncRendererStaticPlan(listOf(output), emptyList()),
                snapshot.schemaFingerprint, snapshot.bindingFingerprint,
                setOf("ui.cluster")) as UiSyncRendererSessionBindingContext

        fun physicalBatch(profile: VerifiedUltraProfile, output: UltraDisplayGraphOutput,
            snapshot: com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeAuthorizationSnapshot,
            renderer: AndroidUltraRendererSessionCoordinator,
            outputBatch: AndroidUltraOutputBatchLease,
            childStatus: () -> UltraProfileSceneUseAttempt<Unit>,
            busy: AtomicBoolean,
            busyProbeSeen: CountDownLatch): UiSyncPhysicalOutputBatch {
            val assignment = UiSyncPhysicalOutputAssignment(output,
                UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.CLUSTER))
            val requirement = UiSyncPhysicalOutputRequirement(profile.candidate.contentDigest,
                listOf(assignment))
            val child = object : UiSyncCompleteRendererSessionLease {
                override val isLive get() = renderer.isLive
                override val implementationId = "test.signed.instrument.renderer"
                override val schemaFingerprint = snapshot.schemaFingerprint
                override val bindingFingerprint = snapshot.bindingFingerprint
                override val displayIds = setOf("ui.cluster")
                override val readinessReceipt: RendererReadinessReceipt? = null
                override fun prepareScreen(context: UiSyncRendererScreenContext):
                    UiSyncRendererScreenCandidate? = null
                override fun retireScreens() = true
                override fun close() = Unit
            }
            val context = UiSyncPhysicalOutputSessionContext::class.java.declaredConstructors
                .single { it.parameterCount == 5 }.apply { isAccessible = true }
                .newInstance(requirement,
                    UiSyncRendererStaticPlan(listOf(output), emptyList()),
                    snapshot.schemaFingerprint, snapshot.bindingFingerprint, child) as
                UiSyncPhysicalOutputSessionContext
            val version = UiSyncPhysicalTargetVersion(UUID.randomUUID().toString(), 1L)
            val route = UiSyncPhysicalOutputRoute(assignment, version)
            val backend = object : UiSyncPhysicalOutputBackendLease {
                override val generation = outputBatch.generation
                private val exactOwnerCurrent get() = outputBatch.isCurrent &&
                    renderer.isLive
                override val generationStatus get() = when {
                    !exactOwnerCurrent -> UiSyncPhysicalGenerationStatus.REVOKED
                    else -> when (childStatus()) {
                        UltraProfileSceneUseAttempt.Revoked ->
                            UiSyncPhysicalGenerationStatus.REVOKED
                        UltraProfileSceneUseAttempt.Busy -> {
                            if (busy.get()) busyProbeSeen.countDown()
                            UiSyncPhysicalGenerationStatus.BUSY
                        }
                        is UltraProfileSceneUseAttempt.Admitted -> if (busy.get()) {
                            busyProbeSeen.countDown()
                            UiSyncPhysicalGenerationStatus.BUSY
                        } else UiSyncPhysicalGenerationStatus.CURRENT
                    }
                }
                override val isCurrent get() = generationStatus ==
                    UiSyncPhysicalGenerationStatus.CURRENT
                override fun close() = Unit
            }
            val target = object : UiSyncPhysicalTargetLease {
                override val version = version
                override val isCurrent get() = outputBatch.isCurrent
            }
            return context.issueActiveBatch(backend, listOf(route), listOf(target))
        }

        private class ValidSurface : Surface(null as SurfaceTexture?) {
            override fun isValid() = true
        }
        fun target(): AndroidDisplaySurfaceTarget {
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply {
                isAccessible = true
            }.get(null) as Unsafe
            return AndroidDisplaySurfaceTarget(
                unsafe.allocateInstance(ValidSurface::class.java) as Surface,
                DisplaySurfaceDescriptor(DisplaySurfaceId("cluster-test"), PixelSize(800, 480)),
            )
        }

        fun receipt(identity: MfiAuthenticationIdentity): Triple<MfiAuthenticationReceipt,
            () -> Unit, Closeable> {
            val klass = Class.forName("com.shilapi.xcertplay.mfi.MfiAuthenticationReceiptAuthority")
            val authority = klass.declaredConstructors.single { it.parameterCount == 1 }
                .apply { isAccessible = true }.newInstance(identity)
            val begin = klass.declaredMethods.single { it.name.startsWith("begin") &&
                !it.name.startsWith("beginHandoff") && it.parameterCount == 2
            }.apply { isAccessible = true }
            val channel = Any()
            val attempt = begin.invoke(authority, channel, 1L)
            val issue = attempt.javaClass.declaredMethods.single {
                it.name.startsWith("issue") && it.parameterCount == 2
            }.apply { isAccessible = true }
            return Triple(issue.invoke(attempt, channel, identity) as MfiAuthenticationReceipt,
                { begin.invoke(authority, Any(), 2L); Unit }, authority as Closeable)
        }
    }
}
