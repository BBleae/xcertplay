package com.shilapi.xcertplay.ultra

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSource
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSubscription
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.media.AndroidVideoSurfaceRetirementResult
import com.shilapi.xcertplay.media.AndroidVideoFrameReleaseReceipt
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendPhase
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendRetirementResult
import com.shilapi.xcertplay.ultra.egl.UltraEglIngressKey
import com.shilapi.xcertplay.ultra.egl.RemoteFrameIdentityDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglInstrumentUploadReceipt
import com.shilapi.xcertplay.ultra.egl.UltraEglClusterReplayCause
import com.shilapi.xcertplay.ultra.egl.UltraEglClusterReplayReceipt
import com.shilapi.xcertplay.ultra.egl.UltraEglClusterReplaySubscription
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import com.shilapi.xcertplay.ultra.egl.UltraEglResolvedSubmission
import com.shilapi.xcertplay.ultra.instrument.OwnedOpaqueArgbFrame
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import java.io.Closeable
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class AndroidUltraRendererSessionCoordinatorTest {
    @Test
    fun frameReleaseOnlyForExactPublishedCurrentRemoteAndNeverGrantsReadiness() {
        val handle = syntheticHandle(AirPlayStreamIdentity(111, "cluster-video", null))
        val ingressId = UltraIngressId("cluster-video")
        val backend = FakeBackend()
        val coordinator = coordinator(backend, listOf(handle to ingressId))
        val candidate = checkNotNull(coordinator.prepareScreen(screenContext(handle, ingressId)))
        val exact = frameReleaseReceipt(handle, backend.remoteSurface)
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            coordinator.acceptVideoFrameReleaseReceipt(exact))
        assertEquals(0, backend.frameReleaseCalls)

        assertTrue(candidate.publish())
        assertEquals(RemoteFrameIdentityDisposition.PENDING,
            coordinator.acceptVideoFrameReleaseReceipt(exact))
        assertEquals(1, backend.frameReleaseCalls)
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            coordinator.acceptVideoFrameReleaseReceipt(frameReleaseReceipt(
                syntheticHandle(AirPlayStreamIdentity(111, "cluster-video", null)),
                backend.remoteSurface,
            )))
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            coordinator.acceptVideoFrameReleaseReceipt(frameReleaseReceipt(
                handle, allocateWithoutConstructor(Surface::class.java),
            )))
        assertEquals(1, backend.frameReleaseCalls)
        assertTrue(coordinator.drainMatchedVideoFrames().isEmpty())

        candidate.close()
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            coordinator.acceptVideoFrameReleaseReceipt(exact))
        assertEquals(1, backend.frameReleaseCalls)
        coordinator.close()
    }

    @Test
    fun earlyEglClusterCompletionWaitsForExactCoordinatorSceneCommit() {
        val output = output("early-replay-cluster", "cluster-A", 93)
        val backend = FakeBackend()
        val coordinator = batchCoordinator(backend, FakeBatchBinder(), listOf(output))
        val batch = coordinator.replaceOutputBatch(listOf(
            AndroidUltraOutputAttachmentRequest(DisplayDestination.CLUSTER, output,
                validTarget("early-replay-cluster")),
        ))
        val viewport = PixelSize(800, 480)
        val raster = coordinator.openInstrumentRaster(DisplayDestination.CLUSTER, viewport)
        assertEquals(UltraEglInstrumentUploadReceipt.COMMITTED,
            raster.replaceCompleteOpaqueFrame(IntArray(800 * 480) { 0xff101112.toInt() }))
        val delivered = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val subscription = coordinator.subscribeClusterReplay(raster,
            { delivered.countDown() }, { revoked.countDown() })
        val callbackStarted = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        backend.onSubmit = { resolved ->
            Thread {
                callbackStarted.countDown()
                backend.clusterReplayListener?.invoke(UltraEglClusterReplayReceipt(
                    DisplayDestination.CLUSTER, resolved.revision, resolved.graphRevision,
                    7L, 7L, backend.instrumentGeneration, 1L, 101L, 1L,
                    UltraEglClusterReplayCause.SCENE_SUBMISSION, true,
                ))
                callbackFinished.countDown()
            }.start()
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS))
            assertTrue(callbackFinished.await(1, TimeUnit.SECONDS))
            assertEquals(1L, delivered.count) // queued, not delivered before scene commit
        }
        val scene = UltraMultiOutputRenderSnapshot(1L, listOf(UltraOutputScene(
            DisplayDestination.CLUSTER, viewport, listOf(UltraSceneLayer(
                UltraLayerId("instrument"), UltraSceneSource.Instrument(DisplayDestination.CLUSTER),
                UltraPixelRect(0, 0, 800, 480),
            )),
        )))
        coordinator.resolveAndSubmit(scene, mapOf(DisplayDestination.CLUSTER to output.outputId))
        assertTrue(callbackFinished.await(1, TimeUnit.SECONDS))
        assertTrue(delivered.await(1, TimeUnit.SECONDS))
        assertEquals(1L, revoked.count)
        subscription.close()
        raster.close()
        batch.close()
        coordinator.close()
    }

    @Test
    fun supersededClusterReplayDropsButLaterValidContentStillUsesSameObserver() {
        val output = output("content-race-cluster", "cluster-A", 94)
        val backend = FakeBackend()
        val coordinator = batchCoordinator(backend, FakeBatchBinder(), listOf(output))
        val batch = coordinator.replaceOutputBatch(listOf(
            AndroidUltraOutputAttachmentRequest(DisplayDestination.CLUSTER, output,
                validTarget("content-race-cluster")),
        ))
        val viewport = PixelSize(800, 480)
        val raster = coordinator.openInstrumentRaster(DisplayDestination.CLUSTER, viewport)
        assertEquals(UltraEglInstrumentUploadReceipt.COMMITTED,
            raster.replaceCompleteOpaqueFrame(IntArray(800 * 480) { 0xff101112.toInt() }))
        val delivered = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val superseded = CountDownLatch(1)
        val subscription = coordinator.subscribeClusterReplay(raster,
            { delivered.countDown() }, { revoked.countDown() }, { superseded.countDown() })
        val scene = UltraMultiOutputRenderSnapshot(1L, listOf(UltraOutputScene(
            DisplayDestination.CLUSTER, viewport, listOf(UltraSceneLayer(
                UltraLayerId("instrument"), UltraSceneSource.Instrument(DisplayDestination.CLUSTER),
                UltraPixelRect(0, 0, 800, 480),
            )),
        )))
        coordinator.resolveAndSubmit(scene, mapOf(DisplayDestination.CLUSTER to output.outputId))
        val graphRevision = coordinator.graphSnapshot().revision
        val callbackStarted = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        backend.onInstrumentReplace = {
            Thread {
                callbackStarted.countDown()
                backend.clusterReplayListener?.invoke(UltraEglClusterReplayReceipt(
                    DisplayDestination.CLUSTER, 1L, graphRevision,
                    7L, 7L, backend.instrumentGeneration, 1L, 101L, 2L,
                    UltraEglClusterReplayCause.INGRESS_FRAME_REPLAY, true,
                ))
                callbackFinished.countDown()
            }.start()
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS))
            assertTrue(callbackFinished.await(1, TimeUnit.SECONDS))
            assertEquals(1L, delivered.count) // queued, not delivered before upload commit
        }
        assertEquals(UltraEglInstrumentUploadReceipt.COMMITTED,
            raster.replaceCompleteOpaqueFrame(IntArray(800 * 480) { 0xff405060.toInt() }))
        assertTrue(callbackFinished.await(1, TimeUnit.SECONDS))
        assertTrue(superseded.await(1, TimeUnit.SECONDS))
        assertEquals(1L, delivered.count)
        assertEquals(1L, revoked.count)
        backend.clusterReplayListener?.invoke(UltraEglClusterReplayReceipt(
            DisplayDestination.CLUSTER, 1L, graphRevision,
            7L, 7L, backend.instrumentGeneration, 2L, 102L, 3L,
            UltraEglClusterReplayCause.INSTRUMENT_UPLOAD, true,
        ))
        assertTrue(delivered.await(1, TimeUnit.SECONDS))
        assertEquals(1L, revoked.count)
        subscription.close()
        raster.close()
        batch.close()
        coordinator.close()
    }

    @Test
    fun uploadLongerThanTwoSecondsRequeuesLatestReceiptWithoutTerminalRevocation() {
        val output = output("slow-upload-cluster", "cluster-A", 95)
        val backend = FakeBackend()
        val coordinator = batchCoordinator(backend, FakeBatchBinder(), listOf(output))
        val batch = coordinator.replaceOutputBatch(listOf(
            AndroidUltraOutputAttachmentRequest(DisplayDestination.CLUSTER, output,
                validTarget("slow-upload-cluster")),
        ))
        val viewport = PixelSize(800, 480)
        val raster = coordinator.openInstrumentRaster(DisplayDestination.CLUSTER, viewport)
        assertEquals(UltraEglInstrumentUploadReceipt.COMMITTED,
            raster.replaceCompleteOpaqueFrame(IntArray(800 * 480) { 0xff101112.toInt() }))
        val delivered = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val subscription = coordinator.subscribeClusterReplay(raster,
            { delivered.countDown() }, { revoked.countDown() })
        coordinator.resolveAndSubmit(UltraMultiOutputRenderSnapshot(1L, listOf(UltraOutputScene(
            DisplayDestination.CLUSTER, viewport, listOf(UltraSceneLayer(
                UltraLayerId("instrument"), UltraSceneSource.Instrument(DisplayDestination.CLUSTER),
                UltraPixelRect(0, 0, 800, 480),
            )),
        ))), mapOf(DisplayDestination.CLUSTER to output.outputId))
        val graphRevision = coordinator.graphSnapshot().revision
        val uploadEntered = CountDownLatch(1)
        val releaseUpload = CountDownLatch(1)
        backend.onInstrumentReplace = {
            uploadEntered.countDown()
            releaseUpload.await(5, TimeUnit.SECONDS)
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val upload = pool.submit<UltraEglInstrumentUploadReceipt> {
                raster.replaceCompleteOpaqueFrame(IntArray(800 * 480) { 0xff405060.toInt() })
            }
            assertTrue(uploadEntered.await(1, TimeUnit.SECONDS))
            backend.clusterReplayListener?.invoke(UltraEglClusterReplayReceipt(
                DisplayDestination.CLUSTER, 1L, graphRevision,
                7L, 7L, backend.instrumentGeneration, 2L, 102L, 2L,
                UltraEglClusterReplayCause.INSTRUMENT_UPLOAD, true,
            ))
            assertEquals(1L, delivered.count)
            Thread.sleep(2_150L) // exceed the former terminal BUSY timeout deterministically
            assertEquals(1L, revoked.count)
            assertEquals(1L, delivered.count)
            releaseUpload.countDown()
            assertEquals(UltraEglInstrumentUploadReceipt.COMMITTED,
                upload.get(1, TimeUnit.SECONDS))
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
            assertEquals(1L, revoked.count)
        } finally {
            releaseUpload.countDown()
            pool.shutdownNow()
            subscription.close()
            raster.close()
            batch.close()
            coordinator.close()
        }
    }

    @Test
    fun neverCompletingUploadCannotRevealAndRetirementStaysPendingUntilItSettles() {
        val output = output("pending-upload-cluster", "cluster-A", 96)
        val backend = FakeBackend()
        val coordinator = batchCoordinator(backend, FakeBatchBinder(), listOf(output))
        val batch = coordinator.replaceOutputBatch(listOf(
            AndroidUltraOutputAttachmentRequest(DisplayDestination.CLUSTER, output,
                validTarget("pending-upload-cluster")),
        ))
        val viewport = PixelSize(800, 480)
        val raster = coordinator.openInstrumentRaster(DisplayDestination.CLUSTER, viewport)
        assertEquals(UltraEglInstrumentUploadReceipt.COMMITTED,
            raster.replaceCompleteOpaqueFrame(IntArray(800 * 480) { 0xff101112.toInt() }))
        val delivered = CountDownLatch(1)
        val subscription = coordinator.subscribeClusterReplay(raster,
            { delivered.countDown() }, {})
        coordinator.resolveAndSubmit(UltraMultiOutputRenderSnapshot(1L, listOf(UltraOutputScene(
            DisplayDestination.CLUSTER, viewport, listOf(UltraSceneLayer(
                UltraLayerId("instrument"), UltraSceneSource.Instrument(DisplayDestination.CLUSTER),
                UltraPixelRect(0, 0, 800, 480),
            )),
        ))), mapOf(DisplayDestination.CLUSTER to output.outputId))
        val graphRevision = coordinator.graphSnapshot().revision
        val uploadEntered = CountDownLatch(1)
        val releaseUpload = CountDownLatch(1)
        backend.onInstrumentReplace = {
            uploadEntered.countDown()
            releaseUpload.await(5, TimeUnit.SECONDS)
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val upload = pool.submit<UltraEglInstrumentUploadReceipt> {
                raster.replaceCompleteOpaqueFrame(IntArray(800 * 480) { 0xff405060.toInt() })
            }
            assertTrue(uploadEntered.await(1, TimeUnit.SECONDS))
            backend.clusterReplayListener?.invoke(UltraEglClusterReplayReceipt(
                DisplayDestination.CLUSTER, 1L, graphRevision,
                7L, 7L, backend.instrumentGeneration, 2L, 102L, 2L,
                UltraEglClusterReplayCause.INSTRUMENT_UPLOAD, true,
            ))
            assertEquals(1L, delivered.count)
            assertFalse(coordinator.retireScreens())
            assertEquals(1L, delivered.count)
            subscription.close() // drains observer and discards the deferred permit candidate
            releaseUpload.countDown()
            assertEquals(UltraEglInstrumentUploadReceipt.COMMITTED,
                upload.get(1, TimeUnit.SECONDS))
            assertEquals(1L, delivered.count)
        } finally {
            releaseUpload.countDown()
            pool.shutdownNow()
            raster.close()
            batch.close()
            coordinator.close()
        }
    }

    @Test
    fun ownedInstrumentFrameTransfersWithoutCallerAliasAndStaleGenerationCannotUpload() {
        val backend = FakeBackend()
        val coordinator = coordinator(backend, emptyList(), emptyList())
        val size = PixelSize(320, 200)
        val first = coordinator.openInstrumentRaster(DisplayDestination.CLUSTER, size)
        val mutable = IntArray(320 * 200) { 0xff102030.toInt() }
        val owned = requireNotNull(OwnedOpaqueArgbFrame.capture(mutable))
        mutable.fill(0xffeeeeee.toInt())

        assertEquals(UltraEglInstrumentUploadReceipt.COMMITTED,
            first.replaceOwnedCompleteOpaqueFrame(owned))
        assertEquals(UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT,
            first.replaceOwnedCompleteOpaqueFrame(owned))
        assertEquals(1, backend.instrumentFrameInputs.size)
        assertNotSame(mutable, backend.instrumentFrameInputs.single())
        assertEquals(0xff102030.toInt(), backend.instrumentFrameInputs.single()[0])

        first.close()
        val replacement = coordinator.openInstrumentRaster(DisplayDestination.CLUSTER, size)
        val stale = requireNotNull(OwnedOpaqueArgbFrame.capture(
            IntArray(320 * 200) { 0xff405060.toInt() }))
        assertEquals(UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT,
            first.replaceOwnedCompleteOpaqueFrame(stale))
        assertEquals(1, backend.instrumentFrameInputs.size)
        assertEquals(UltraEglInstrumentUploadReceipt.COMMITTED,
            replacement.replaceOwnedCompleteOpaqueFrame(requireNotNull(
                OwnedOpaqueArgbFrame.capture(IntArray(320 * 200) { -1 }))))
        assertEquals(2, backend.instrumentFrameInputs.size)
        replacement.close()
        coordinator.close()
        assertEquals(2, backend.instrumentRetireCalls)
    }

    @Test
    fun dedicatedInstrumentNeedsCompleteOpaqueUploadAndRetiresWithoutCanvasSurface() {
        val output = output("reference-cluster", "cluster-A", 90)
        val backend = FakeBackend()
        val coordinator = coordinator(backend, emptyList(), listOf(output))
        val viewport = PixelSize(800, 480)
        val raster = coordinator.openInstrumentRaster(DisplayDestination.CLUSTER, viewport)
        val display = coordinator.attachOutput(DisplayDestination.CLUSTER, output,
            validTarget("reference-cluster"))
        fun scene(revision: Long) = UltraMultiOutputRenderSnapshot(revision, listOf(
            UltraOutputScene(DisplayDestination.CLUSTER, viewport, listOf(
                UltraSceneLayer(UltraLayerId("signed-instrument"),
                    UltraSceneSource.Instrument(DisplayDestination.CLUSTER),
                    UltraPixelRect(0, 0, 800, 480)),
            )),
        ))
        val mapping = mapOf(DisplayDestination.CLUSTER to output.outputId)
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.resolveAndSubmit(scene(1L), mapping)
        }
        assertEquals(UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT,
            raster.replaceCompleteOpaqueFrame(IntArray(800 * 480) { 0x00203040 }))
        assertTrue(backend.instrumentFrames.isEmpty())
        val unavailable = IntArray(800 * 480) { 0xff101112.toInt() }
        assertEquals(UltraEglInstrumentUploadReceipt.COMMITTED,
            raster.replaceCompleteOpaqueFrame(unavailable))
        unavailable.fill(0xfffefefe.toInt())
        assertEquals(0xff101112.toInt(), backend.instrumentFrames.single()[0])
        coordinator.resolveAndSubmit(scene(1L), mapping)
        assertEquals(1L, backend.lastPlan?.renderRevision)

        raster.close()
        assertEquals(UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT,
            raster.replaceCompleteOpaqueFrame(IntArray(800 * 480) { -1 }))
        val replacement = coordinator.openInstrumentRaster(DisplayDestination.CLUSTER, viewport)
        raster.close() // stale close cannot detach the replacement generation
        assertTrue(coordinator.isCurrentInstrumentRaster(replacement,
            DisplayDestination.CLUSTER, viewport))
        replacement.close()
        display.close()
        coordinator.close()
        assertEquals(2, backend.instrumentRetireCalls)
        assertEquals(0, backend.ingressRetireCalls)
    }

    @Test
    fun v2OutputBatchPublishesOneGraphGenerationAndStaleLeaseCannotDetachReplacement() {
        val main = output("main-batch", "cluster-A", 21)
        val localOnly = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID.fromString("00000000-0000-0000-0000-000000000022")),
            UltraDisplayOutputId.Custom("local-batch"),
        )
        val binder = FakeBatchBinder()
        val backend = FakeBackend()
        val coordinator = batchCoordinator(backend, binder, listOf(main, localOnly))
        val first = coordinator.replaceOutputBatch(
            listOf(
                AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, validTarget("first-main")),
                AndroidUltraOutputAttachmentRequest(DisplayDestination.CLUSTER, localOnly, validTarget("first-local")),
            ),
        )
        val firstGraph = coordinator.graphSnapshot()
        assertEquals(setOf(main.outputId, localOnly.outputId), firstGraph.outputs.map { it.outputId }.toSet())
        assertTrue(coordinator.stateSnapshot().crossOutputAttachmentIsAtomic)
        assertEquals(first.generation, coordinator.stateSnapshot().outputBatchGeneration)

        binder.onCommit = {
            assertSame(firstGraph, coordinator.graphSnapshot())
            assertThrows(IllegalArgumentException::class.java) {
                coordinator.resolveAndSubmit(
                    localOnlyRender(1L),
                    mapOf(DisplayDestination.MAIN to main.outputId),
                )
            }
        }
        val replacement = coordinator.replaceOutputBatch(
            listOf(
                AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, validTarget("new-main")),
                AndroidUltraOutputAttachmentRequest(DisplayDestination.CLUSTER, localOnly, validTarget("new-local")),
            ),
        )
        assertFalse(first.isCurrent)
        assertTrue(replacement.isCurrent)
        assertTrue(coordinator.graphSnapshot().revision > firstGraph.revision)
        first.close()
        assertTrue(replacement.isCurrent)
        assertEquals(2, coordinator.graphSnapshot().outputs.size)

        replacement.close()
        assertTrue(coordinator.graphSnapshot().outputs.isEmpty())
        coordinator.close()
        assertEquals(1, binder.closeCalls)
        assertEquals(1, backend.backendRetireCalls)
    }

    @Test
    fun cleanV2BatchFailureRetainsPriorGraphAndGeneration() {
        val main = output("main-batch", "cluster-A", 23)
        val binder = FakeBatchBinder()
        val coordinator = batchCoordinator(FakeBackend(), binder, listOf(main))
        val first = coordinator.replaceOutputBatch(
            listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, validTarget("first"))),
        )
        val graph = coordinator.graphSnapshot()
        binder.failNextCommit = true
        assertThrows(IllegalStateException::class.java) {
            coordinator.replaceOutputBatch(
                listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, validTarget("second"))),
            )
        }
        assertSame(graph, coordinator.graphSnapshot())
        assertTrue(first.isCurrent)
        assertEquals(first.generation, coordinator.stateSnapshot().outputBatchGeneration)
        assertTrue(coordinator.isLive)
        coordinator.close()
    }

    @Test
    fun targetInvalidatedDuringNativeCommitCannotPublishRendererGraph() {
        val main = output("main-batch", "cluster-A", 26)
        val binder = FakeBatchBinder()
        val coordinator = batchCoordinator(FakeBackend(), binder, listOf(main))
        val target = validTarget("disappearing")
        binder.onCommit = { target.close() }

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.replaceOutputBatch(
                listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, target)),
            )
        }
        assertTrue(coordinator.graphSnapshot().outputs.isEmpty())
        assertFalse(coordinator.isLive)
        assertFalse(coordinator.stateSnapshot().crossOutputAttachmentIsAtomic)
        coordinator.close()
    }

    @Test
    fun batchRejectsEqualButNotExactStaticOutputBeforeBinderCreation() {
        val main = output("main-batch", "cluster-A", 27)
        val backend = FakeBackend()
        var binderCreations = 0
        val coordinator = AndroidUltraRendererSessionCoordinator(
            sessionContext(
                RecordingPresentationSource(),
                UiSyncRendererStaticPlan(listOf(main), emptyList()),
                setOf("cluster-A"),
            ),
            backend,
            {
                binderCreations++
                FakeBatchBinder().let { AndroidUltraBatchBinderResource(it, it) }
            },
            Unit,
        )
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.replaceOutputBatch(
                listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main.copy(), validTarget("clone"))),
            )
        }
        assertEquals(0, binderCreations)
        assertTrue(coordinator.graphSnapshot().outputs.isEmpty())
        coordinator.close()
    }

    @Test
    fun surfaceValidityCallbackCannotReenterAnUnreservedBatch() {
        val main = output("main-batch", "cluster-A", 28)
        val surface = allocateWithoutConstructor(ReentrantSurface::class.java)
        val target = AndroidDisplaySurfaceTarget(
            surface,
            DisplaySurfaceDescriptor(DisplaySurfaceId("reentrant"), PixelSize(800, 480)),
        )
        val binder = FakeBatchBinder()
        val coordinator = batchCoordinator(FakeBackend(), binder, listOf(main))
        surface.onProbe = {
            assertThrows(IllegalStateException::class.java) {
                coordinator.replaceOutputBatch(
                    listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, target)),
                )
            }
        }

        val lease = coordinator.replaceOutputBatch(
            listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, target)),
        )
        assertTrue(lease.isCurrent)
        assertEquals(1, coordinator.graphSnapshot().outputs.size)
        coordinator.close()
    }

    @Test
    fun backendFailureInvalidatesPublishedBatchGraphBeforeAnyReadinessClaim() {
        val main = output("main-batch", "cluster-A", 29)
        val backend = FakeBackend()
        val coordinator = batchCoordinator(backend, FakeBatchBinder(), listOf(main))
        val lease = coordinator.replaceOutputBatch(
            listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, validTarget("failed"))),
        )
        assertEquals(1, coordinator.graphSnapshot().outputs.size)

        backend.backendPhase = UltraEglBackendPhase.FAILED
        assertTrue(coordinator.graphSnapshot().outputs.isEmpty())
        assertFalse(coordinator.isLive)
        assertFalse(lease.isCurrent)
        assertFalse(coordinator.stateSnapshot().crossOutputAttachmentIsAtomic)
        coordinator.close()
    }

    @Test
    fun lostNativeBatchLeaseInvalidatesGraphEvenIfBackendPhaseStillLooksOpen() {
        val main = output("main-batch", "cluster-A", 30)
        val binder = FakeBatchBinder()
        val coordinator = batchCoordinator(FakeBackend(), binder, listOf(main))
        val lease = coordinator.replaceOutputBatch(
            listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, validTarget("lost"))),
        )
        binder.operational = false

        assertTrue(coordinator.graphSnapshot().outputs.isEmpty())
        assertFalse(coordinator.stateSnapshot().crossOutputAttachmentIsAtomic)
        assertFalse(coordinator.isLive)
        assertFalse(lease.isCurrent)
        coordinator.close()
    }

    @Test
    fun physicalTargetInvalidatedAfterCommitRevokesBatchLeaseAndGraph() {
        val main = output("main-batch", "cluster-A", 32)
        val coordinator = batchCoordinator(FakeBackend(), FakeBatchBinder(), listOf(main))
        val target = validTarget("destroyed-after-commit")
        val lease = coordinator.replaceOutputBatch(
            listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, target)),
        )
        target.close()

        assertFalse(lease.isCurrent)
        assertTrue(coordinator.graphSnapshot().outputs.isEmpty())
        assertFalse(coordinator.isLive)
        coordinator.close()
    }

    @Test
    fun uncertainBatchCommitRetainsExactTargetsUntilRetryableSessionRetirement() {
        val main = output("main-batch", "cluster-A", 31)
        val binder = FakeBatchBinder().apply { failNextCommitUncertain = true }
        val backend = FakeBackend()
        val coordinator = batchCoordinator(backend, binder, listOf(main))
        val target = validTarget("uncertain")

        val failure = assertThrows(AndroidUltraOutputBatchCommitFailure::class.java) {
            coordinator.replaceOutputBatch(
                listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, target)),
            )
        }
        assertSame(target, failure.targets.single())
        assertFalse(failure.isRetired)
        assertTrue(coordinator.graphSnapshot().outputs.isEmpty())
        assertFalse(coordinator.isLive)
        failure.close()
        assertTrue(failure.isRetired)
        assertEquals(1, backend.backendRetireCalls)
        coordinator.close()
    }

    @Test
    fun batchRetirementMustFinishBeforeBinderAndBackendCloseAndCanRetry() {
        val main = output("main-batch", "cluster-A", 24)
        val binder = FakeBatchBinder()
        val backend = FakeBackend()
        val coordinator = batchCoordinator(backend, binder, listOf(main))
        coordinator.replaceOutputBatch(
            listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, validTarget("first"))),
        )
        binder.failNextRetirement = true
        assertThrows(IllegalStateException::class.java) { coordinator.close() }
        assertEquals(0, binder.closeCalls)
        assertEquals(0, backend.backendRetireCalls)
        assertFalse(coordinator.isLive)
        coordinator.close()
        assertEquals(1, binder.closeCalls)
        assertEquals(1, backend.backendRetireCalls)
        assertEquals(AndroidUltraRendererSessionPhase.CLOSED, coordinator.stateSnapshot().phase)
    }

    @Test
    fun batchAndSequentialOutputAttachmentCannotMix() {
        val main = output("main-batch", "cluster-A", 25)
        val first = batchCoordinator(FakeBackend(), FakeBatchBinder(), listOf(main))
        first.replaceOutputBatch(
            listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, validTarget("batch"))),
        )
        assertThrows(IllegalStateException::class.java) {
            first.attachOutput(DisplayDestination.CLUSTER, main, validTarget("single"))
        }
        first.close()

        val second = coordinator(FakeBackend(), emptyList(), listOf(main))
        second.attachOutput(DisplayDestination.MAIN, main, target("single", 800, 480))
        assertThrows(IllegalStateException::class.java) {
            second.replaceOutputBatch(
                listOf(AndroidUltraOutputAttachmentRequest(DisplayDestination.MAIN, main, validTarget("batch"))),
            )
        }
        second.close()
    }

    @Test
    fun earlyRemoteCandidateStaysPrivateUntilPublishAndPrePublishCloseRollsBack() {
        val handle = syntheticHandle(AirPlayStreamIdentity(110, "screen-a", "client-a"))
        val ingressId = UltraIngressId("remote-a")
        val backend = FakeBackend()
        val coordinator = coordinator(backend, listOf(handle to ingressId))
        val screen = screenContext(handle, ingressId)

        val unpublished = checkNotNull(coordinator.prepareScreen(screen))
        assertTrue(coordinator.graphSnapshot().ingresses.isEmpty())
        assertEquals(false, coordinator.stateSnapshot().remoteIngresses.single().published)
        assertEquals(1, backend.remoteOpenCount)

        unpublished.close()
        assertTrue(coordinator.graphSnapshot().ingresses.isEmpty())
        assertEquals(1, backend.ingressRetireCalls)

        val published = checkNotNull(coordinator.prepareScreen(screen))
        assertTrue(published.publish())
        val graph = coordinator.graphSnapshot()
        assertSame(handle, graph.ingress(ingressId)?.stream)
        assertEquals(true, coordinator.stateSnapshot().remoteIngresses.single().published)

        unpublished.close()
        assertSame(handle, coordinator.graphSnapshot().ingress(ingressId)?.stream)

        published.close()
        assertTrue(coordinator.graphSnapshot().ingresses.isEmpty())
        coordinator.close()
        assertEquals(AndroidUltraRendererSessionPhase.CLOSED, coordinator.stateSnapshot().phase)
    }

    @Test
    fun concurrentDuplicateHandleAndIngressReservationsAreRejectedBeforeBackendAllocation() {
        val handle = syntheticHandle(AirPlayStreamIdentity(110, "screen-a", null))
        val ingressId = UltraIngressId("remote-a")
        val backend = FakeBackend()
        lateinit var coordinator: AndroidUltraRendererSessionCoordinator
        val screen = screenContext(handle, ingressId)
        var duplicateFailure: Throwable? = null
        backend.onOpenRemote = {
            duplicateFailure = runCatching { coordinator.prepareScreen(screen) }.exceptionOrNull()
        }
        coordinator = coordinator(backend, listOf(handle to ingressId))

        val candidate = checkNotNull(coordinator.prepareScreen(screen))

        assertTrue(duplicateFailure is IllegalArgumentException)
        assertEquals(1, backend.remoteOpenCount)
        candidate.close()
        coordinator.close()
    }

    @Test
    fun equalWireIdentitiesInTwoSessionsRemainReferentiallyIsolated() {
        val identity = AirPlayStreamIdentity(110, "reused-wire-id", "same-client")
        val firstHandle = syntheticHandle(identity)
        val secondHandle = syntheticHandle(identity)
        assertNotSame(firstHandle, secondHandle)
        val ingressId = UltraIngressId("remote")
        val first = coordinator(FakeBackend(), listOf(firstHandle to ingressId))
        val second = coordinator(FakeBackend(), listOf(secondHandle to ingressId))

        val firstCandidate = checkNotNull(first.prepareScreen(screenContext(firstHandle, ingressId)))
        val secondCandidate = checkNotNull(second.prepareScreen(screenContext(secondHandle, ingressId)))
        assertTrue(firstCandidate.publish())
        assertTrue(secondCandidate.publish())

        assertSame(firstHandle, first.graphSnapshot().ingress(ingressId)?.stream)
        assertSame(secondHandle, second.graphSnapshot().ingress(ingressId)?.stream)
        firstCandidate.close()
        assertTrue(first.graphSnapshot().ingresses.isEmpty())
        assertSame(secondHandle, second.graphSnapshot().ingress(ingressId)?.stream)

        first.close()
        secondCandidate.close()
        second.close()
    }

    @Test
    fun fanoutMultiIngressAndCustomLayersResolveAgainstOneImmutableGraph() {
        val remoteA = syntheticHandle(AirPlayStreamIdentity(110, "a", null))
        val remoteB = syntheticHandle(AirPlayStreamIdentity(110, "b", null))
        val ingressA = UltraIngressId("remote-a")
        val ingressB = UltraIngressId("remote-b")
        val mainOutput = output("main", "cluster-A", 1)
        val clusterOutput = output("cluster", "cluster-B", 2)
        val backend = FakeBackend()
        val coordinator = coordinator(
            backend,
            listOf(remoteA to ingressA, remoteB to ingressB),
            listOf(mainOutput, clusterOutput),
            setOf("cluster-A", "cluster-B"),
        )
        val first = checkNotNull(coordinator.prepareScreen(screenContext(remoteA, ingressA)))
        val second = checkNotNull(coordinator.prepareScreen(screenContext(remoteB, ingressB)))
        assertTrue(first.publish())
        assertTrue(second.publish())
        val local = coordinator.openLocalIngress(UltraContentId("oem-local"), PixelSize(800, 480))
        val overlay = coordinator.openOverlayIngress(UltraContentId("oem-overlay"), PixelSize(600, 240))
        assertEquals(
            listOf(PixelSize(800, 480), PixelSize(600, 240)),
            backend.ownedIngressSizes,
        )
        val mainLease = coordinator.attachOutput(
            DisplayDestination.MAIN,
            mainOutput,
            target("main-surface", 800, 480),
        )
        val clusterLease = coordinator.attachOutput(
            DisplayDestination.CLUSTER,
            clusterOutput,
            target("cluster-surface", 600, 240),
        )

        val render = UltraMultiOutputRenderSnapshot(
            1L,
            listOf(
                UltraOutputScene(
                    DisplayDestination.MAIN,
                    PixelSize(800, 480),
                    listOf(
                        layer("a-main", UltraSceneSource.Remote(ingressA)),
                        layer("b-main", UltraSceneSource.Remote(ingressB), z = 1),
                        layer("local-main", UltraSceneSource.Local(UltraContentId("oem-local")), z = 2),
                    ),
                ),
                UltraOutputScene(
                    DisplayDestination.CLUSTER,
                    PixelSize(600, 240),
                    listOf(
                        layer("a-cluster", UltraSceneSource.Remote(ingressA)),
                        layer(
                            "overlay-cluster",
                            UltraSceneSource.Overlay(UltraContentId("oem-overlay")),
                            z = 1,
                        ),
                    ),
                ),
            ),
        )
        val ticket = coordinator.resolveAndSubmit(
            render,
            mapOf(
                DisplayDestination.MAIN to mainOutput.outputId,
                DisplayDestination.CLUSTER to clusterOutput.outputId,
            ),
        )

        assertEquals(1L, ticket.revision)
        assertEquals(coordinator.graphSnapshot().revision, backend.lastPlan?.graphRevision)
        val resolved = checkNotNull(backend.lastPlan)
        assertSame(
            remoteA,
            resolved.scene(DisplayDestination.MAIN)?.layers?.first()?.remoteIngress?.stream,
        )
        assertSame(
            remoteA,
            resolved.scene(DisplayDestination.CLUSTER)?.layers?.first()?.remoteIngress?.stream,
        )
        assertEquals(2, coordinator.graphSnapshot().outputs.size)
        assertEquals(2, coordinator.graphSnapshot().ingresses.size)

        first.close()
        second.close()
        local.close()
        overlay.close()
        mainLease.close()
        clusterLease.close()
        coordinator.close()
    }

    @Test
    fun localOnlySceneDoesNotRequireAnyRemoteIngress() {
        val output = output("main", "cluster-A", 1)
        val backend = FakeBackend()
        val coordinator = coordinator(
            backend,
            streams = emptyList(),
            outputs = listOf(output),
        )
        val local = coordinator.openLocalIngress(UltraContentId("custom-ui"), PixelSize(800, 480))
        val display = coordinator.attachOutput(
            DisplayDestination.MAIN,
            output,
            target("main", 800, 480),
        )
        val render = UltraMultiOutputRenderSnapshot(
            4L,
            listOf(
                UltraOutputScene(
                    DisplayDestination.MAIN,
                    PixelSize(800, 480),
                    listOf(layer("custom", UltraSceneSource.Local(UltraContentId("custom-ui")))),
                ),
            ),
        )

        coordinator.resolveAndSubmit(
            render,
            mapOf(DisplayDestination.MAIN to output.outputId),
        )

        assertTrue(coordinator.graphSnapshot().ingresses.isEmpty())
        assertEquals(UltraEglIngressKey.Local(UltraContentId("custom-ui")), local.key)
        local.close()
        display.close()
        coordinator.close()
    }

    @Test
    fun staleRenderRevisionAndStaleOutputCallbackAreRejectedWithoutAbaDetach() {
        val output = output("main", "cluster-A", 1)
        val backend = FakeBackend()
        val coordinator = coordinator(backend, emptyList(), listOf(output))
        val old = coordinator.attachOutput(
            DisplayDestination.MAIN,
            output,
            target("old", 800, 480),
        )
        val render = localOnlyRender(1L)
        coordinator.resolveAndSubmit(render, mapOf(DisplayDestination.MAIN to output.outputId))
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.resolveAndSubmit(render, mapOf(DisplayDestination.MAIN to output.outputId))
        }

        old.close()
        val replacement = coordinator.attachOutput(
            DisplayDestination.MAIN,
            output,
            target("new", 800, 480),
        )
        old.close()
        assertEquals(1, coordinator.graphSnapshot().outputs.size)
        assertSame(output, coordinator.graphSnapshot().outputs.single())

        replacement.close()
        coordinator.close()
    }

    @Test
    fun graphMutationDuringResolveFailsClosedBeforeSubmission() {
        val handle = syntheticHandle(AirPlayStreamIdentity(110, "screen", null))
        val ingressId = UltraIngressId("remote")
        val output = output("main", "cluster-A", 1)
        val backend = FakeBackend()
        val coordinator = coordinator(backend, listOf(handle to ingressId), listOf(output))
        val candidate = checkNotNull(coordinator.prepareScreen(screenContext(handle, ingressId)))
        candidate.publish()
        val display = coordinator.attachOutput(
            DisplayDestination.MAIN,
            output,
            target("main", 800, 480),
        )
        backend.onResolve = {
            assertThrows(IllegalStateException::class.java) { coordinator.close() }
        }
        val render = UltraMultiOutputRenderSnapshot(
            1L,
            listOf(
                UltraOutputScene(
                    DisplayDestination.MAIN,
                    PixelSize(800, 480),
                    listOf(layer("remote", UltraSceneSource.Remote(ingressId))),
                ),
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            coordinator.resolveAndSubmit(render, mapOf(DisplayDestination.MAIN to output.outputId))
        }
        assertTrue(coordinator.graphSnapshot().ingresses.isEmpty())
        candidate.close()
        display.close()
        coordinator.close()
    }

    @Test
    fun reentrantCloseDuringRemoteOpenDoesNotDeadlockAndLaterRetryCompletes() {
        val handle = syntheticHandle(AirPlayStreamIdentity(110, "screen", null))
        val ingressId = UltraIngressId("remote")
        val backend = FakeBackend()
        lateinit var coordinator: AndroidUltraRendererSessionCoordinator
        var reentrantFailure: Throwable? = null
        backend.onOpenRemote = {
            reentrantFailure = runCatching { coordinator.close() }.exceptionOrNull()
        }
        coordinator = coordinator(backend, listOf(handle to ingressId))

        assertThrows(IllegalStateException::class.java) {
            coordinator.prepareScreen(screenContext(handle, ingressId))
        }
        assertTrue(reentrantFailure is IllegalStateException)
        coordinator.close()
        assertEquals(AndroidUltraRendererSessionPhase.CLOSED, coordinator.stateSnapshot().phase)
    }

    @Test
    fun uncertainRemoteRetirementRetainsExactRecordForRetry() {
        val handle = syntheticHandle(AirPlayStreamIdentity(110, "screen", null))
        val ingressId = UltraIngressId("remote")
        val backend = FakeBackend().apply {
            ingressRetireResults += AndroidVideoSurfaceRetirementResult.DECODER_CLOSE_TIMED_OUT
            ingressRetireResults += AndroidVideoSurfaceRetirementResult.RETIRED
        }
        val coordinator = coordinator(backend, listOf(handle to ingressId))
        val candidate = checkNotNull(coordinator.prepareScreen(screenContext(handle, ingressId)))
        candidate.publish()

        assertThrows(IllegalStateException::class.java) { candidate.close() }
        val uncertain = coordinator.stateSnapshot()
        assertEquals(AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN, uncertain.phase)
        assertEquals(1, uncertain.remoteIngresses.size)
        assertFalse(uncertain.remoteIngresses.single().published)

        candidate.close()
        assertTrue(coordinator.stateSnapshot().remoteIngresses.isEmpty())
        assertEquals(2, backend.ingressRetireCalls)
        coordinator.close()
    }

    @Test
    fun partialOutputBatchReportsActivePrefixAndUncertainFailure() {
        val firstOutput = output("main", "cluster-A", 1)
        val secondOutput = output("cluster", "cluster-B", 2)
        val backend = FakeBackend().apply { failOutputAttachCall = 2 }
        val coordinator = coordinator(
            backend,
            streams = emptyList(),
            outputs = listOf(firstOutput, secondOutput),
            displayIds = setOf("cluster-A", "cluster-B"),
        )

        val result = coordinator.attachOutputs(
            listOf(
                AndroidUltraOutputAttachmentRequest(
                    DisplayDestination.MAIN,
                    firstOutput,
                    target("main", 800, 480),
                ),
                AndroidUltraOutputAttachmentRequest(
                    DisplayDestination.CLUSTER,
                    secondOutput,
                    target("cluster", 600, 240),
                ),
            ),
        )

        assertFalse(result.complete)
        assertEquals(listOf(DisplayDestination.MAIN), result.attached.map { it.destination })
        assertEquals(DisplayDestination.CLUSTER, result.failedRequest?.destination)
        assertEquals(AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN, result.state.phase)
        assertEquals(AndroidUltraRendererSessionPhase.ACTIVE, result.state.outputs[0].phase)
        assertEquals(
            AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN,
            result.state.outputs[1].phase,
        )
        assertEquals(listOf(firstOutput), coordinator.graphSnapshot().outputs)

        coordinator.close()
        assertEquals(AndroidUltraRendererSessionPhase.CLOSED, coordinator.stateSnapshot().phase)
    }

    @Test
    fun ambiguousOutputAttachFailureRetainsTargetUntilGlobalRetirementProof() {
        val output = output("main", "cluster-A", 1)
        val backend = FakeBackend().apply { claimOutputThenFailCall = 1 }
        val coordinator = coordinator(backend, streams = emptyList(), outputs = listOf(output))
        val target = target("owned-by-ambiguous-attach", 800, 480)

        val failure = assertThrows(AndroidUltraOutputAttachmentFailure::class.java) {
            coordinator.attachOutput(DisplayDestination.MAIN, output, target)
        }

        assertSame(target, failure.target)
        assertFalse(failure.isRetired)
        val competingOwner = Any()
        assertThrows(IllegalStateException::class.java) {
            target.ownership.claim(competingOwner)
        }

        failure.close()
        assertTrue(failure.isRetired)
        assertTrue(target.ownership.claim(competingOwner))
        target.ownership.release(competingOwner)
        assertEquals(1, backend.backendRetireCalls)

        coordinator.close()
        assertEquals(AndroidUltraRendererSessionPhase.CLOSED, coordinator.stateSnapshot().phase)
    }

    @Test
    fun boundedInputsNeverReadHostileOrLyingSizeGetters() {
        val output = output("main", "cluster-A", 1)
        val backend = FakeBackend()
        val coordinator = coordinator(backend, streams = emptyList(), outputs = listOf(output))
        val request = AndroidUltraOutputAttachmentRequest(
            DisplayDestination.MAIN,
            output,
            target("main", 800, 480),
        )
        val hostileRequests = HostileSizeCollection(listOf(request)) {
            throw AssertionError("Collection.size must not be read")
        }

        val attachments = coordinator.attachOutputs(hostileRequests)
        assertTrue(attachments.complete)
        val lyingMap = HostileSizeMap(
            mapOf(DisplayDestination.MAIN to output.outputId),
        ) { Int.MAX_VALUE }
        assertEquals(1L, coordinator.resolveAndSubmit(localOnlyRender(1L), lyingMap).revision)

        attachments.attached.single().close()
        coordinator.close()
    }

    @Test
    fun endlessOutputRequestIteratorIsRejectedBeforeAnyBackendMutation() {
        val output = output("main", "cluster-A", 1)
        val backend = FakeBackend()
        val coordinator = coordinator(backend, streams = emptyList(), outputs = listOf(output))
        val repeated = AndroidUltraOutputAttachmentRequest(
            DisplayDestination.MAIN,
            output,
            target("main", 800, 480),
        )
        val endless = object : AbstractCollection<AndroidUltraOutputAttachmentRequest>() {
            override val size: Int
                get() = throw AssertionError("Collection.size must not be read")

            override fun iterator(): Iterator<AndroidUltraOutputAttachmentRequest> =
                object : Iterator<AndroidUltraOutputAttachmentRequest> {
                    override fun hasNext(): Boolean = true
                    override fun next(): AndroidUltraOutputAttachmentRequest = repeated
                }
        }

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.attachOutputs(endless)
        }
        assertEquals(0, backend.outputAttachCalls)
        coordinator.close()
    }

    @Test
    fun endlessDestinationMapIteratorIsBoundedBeforeSceneResolution() {
        val backend = FakeBackend()
        val coordinator = coordinator(backend, streams = emptyList())
        val endlessEntries = object : AbstractSet<Map.Entry<DisplayDestination, UltraDisplayOutputId>>() {
            override val size: Int
                get() = throw AssertionError("Entry set size must not be read")

            override fun iterator(): Iterator<Map.Entry<DisplayDestination, UltraDisplayOutputId>> =
                object : Iterator<Map.Entry<DisplayDestination, UltraDisplayOutputId>> {
                    private var index = 0
                    override fun hasNext(): Boolean = true
                    override fun next(): Map.Entry<DisplayDestination, UltraDisplayOutputId> {
                        val id = index++
                        return object : Map.Entry<DisplayDestination, UltraDisplayOutputId> {
                            override val key = DisplayDestination(
                                DisplayDestinationRole.AUXILIARY,
                                DisplayDestinationId("endless-$id"),
                            )
                            override val value = UltraDisplayOutputId.Custom("endless-$id")
                        }
                    }
                }
        }
        val endlessMap = object : Map<DisplayDestination, UltraDisplayOutputId> by emptyMap() {
            override val size: Int
                get() = throw AssertionError("Map.size must not be read")
            override val entries = endlessEntries
        }

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.resolveAndSubmit(
                UltraMultiOutputRenderSnapshot(1L, emptyList()),
                endlessMap,
            )
        }
        assertEquals(null, backend.lastPlan)
        coordinator.close()
    }

    @Test
    fun presentationSubscriptionIsPassThroughAndSessionOwned() {
        val backend = FakeBackend()
        val source = RecordingPresentationSource()
        val coordinator = coordinator(
            backend,
            streams = emptyList(),
            source = source,
            displayIds = setOf("cluster-A"),
        )
        val observed = ArrayList<UltraPresentationSnapshot>()

        val subscription = coordinator.subscribePresentation("cluster-A") { observed += it }

        assertEquals("cluster-A", subscription.initialSnapshot.displayId.value)
        assertEquals(1, coordinator.stateSnapshot().presentationSubscriptionCount)
        assertEquals(source.snapshot("cluster-A").revision, coordinator.presentationSnapshot("cluster-A").revision)
        coordinator.close()
        assertEquals(1, source.closeCalls)
        assertEquals(AndroidUltraRendererSessionPhase.CLOSED, coordinator.stateSnapshot().phase)
        subscription.close()
    }

    @Test
    fun presentationCallbacksUsePreparingAdmissionAndDropQueuedWorkAfterDetach() {
        val backend = FakeBackend()
        val source = ManualPresentationSource(emitBeforeReturn = true)
        val coordinator = coordinator(
            backend,
            streams = emptyList(),
            source = source,
            displayIds = setOf("cluster-A"),
        )
        var callbackCount = 0

        val subscription = coordinator.subscribePresentation("cluster-A") { callbackCount++ }
        val queuedBeforeDetach = source.captureCallback()
        assertEquals(1, callbackCount)

        subscription.close()
        queuedBeforeDetach()
        assertEquals(1, callbackCount)
        coordinator.close()
    }

    @Test
    fun sourceRejectingSixtyFifthObserverLeavesNoUncloseableReservation() {
        val backend = FakeBackend()
        val source = RecordingPresentationSource()
        val coordinator = coordinator(
            backend,
            streams = emptyList(),
            source = source,
            displayIds = setOf("cluster-A"),
        )
        repeat(64) {
            coordinator.subscribePresentation("cluster-A") { }
        }
        assertEquals(64, coordinator.stateSnapshot().presentationSubscriptionCount)

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.subscribePresentation("cluster-A") { }
        }
        assertEquals(64, coordinator.stateSnapshot().presentationSubscriptionCount)
        assertTrue(coordinator.isLive)

        coordinator.close()
        assertEquals(64, source.closeCalls)
        assertEquals(AndroidUltraRendererSessionPhase.CLOSED, coordinator.stateSnapshot().phase)
    }

    @Test
    fun sessionCloseDoesNotCrossAnAlreadyAdmittedPresentationCallback() {
        val backend = FakeBackend()
        val source = ManualPresentationSource()
        val coordinator = coordinator(
            backend,
            streams = emptyList(),
            source = source,
            displayIds = setOf("cluster-A"),
        )
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        coordinator.subscribePresentation("cluster-A") {
            callbackStarted.countDown()
            releaseCallback.await(2, TimeUnit.SECONDS)
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val delivery = executor.submit { source.emit() }
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS))

            assertThrows(IllegalStateException::class.java) { coordinator.close() }
            assertEquals(AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN, coordinator.stateSnapshot().phase)

            releaseCallback.countDown()
            delivery.get(1, TimeUnit.SECONDS)
            coordinator.close()
            assertEquals(AndroidUltraRendererSessionPhase.CLOSED, coordinator.stateSnapshot().phase)
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun explicitSubscriptionCloseIsAlsoAnObserverCallbackBarrier() {
        val backend = FakeBackend()
        val source = ManualPresentationSource()
        val coordinator = coordinator(
            backend,
            streams = emptyList(),
            source = source,
            displayIds = setOf("cluster-A"),
        )
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val subscription = coordinator.subscribePresentation("cluster-A") {
            callbackStarted.countDown()
            releaseCallback.await(2, TimeUnit.SECONDS)
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val delivery = executor.submit { source.emit() }
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS))

            assertThrows(IllegalStateException::class.java) { subscription.close() }
            assertEquals(1, coordinator.stateSnapshot().presentationSubscriptionCount)

            releaseCallback.countDown()
            delivery.get(1, TimeUnit.SECONDS)
            subscription.close()
            assertEquals(0, coordinator.stateSnapshot().presentationSubscriptionCount)
            coordinator.close()
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun terminalBackendDiagnosticsMakeSessionNonLiveAndRejectNewWork() {
        val backend = FakeBackend()
        val coordinator = coordinator(backend, streams = emptyList())
        backend.backendPhase = UltraEglBackendPhase.FAILED

        assertFalse(coordinator.isLive)
        assertEquals(AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN, coordinator.stateSnapshot().phase)
        assertThrows(IllegalStateException::class.java) {
            coordinator.openLocalIngress(UltraContentId("late"), PixelSize(800, 480))
        }

        coordinator.close()
        assertEquals(AndroidUltraRendererSessionPhase.CLOSED, coordinator.stateSnapshot().phase)
    }

    @Test
    fun backendRetirementProofIsRetriedWithoutDroppingTrackedResources() {
        val output = output("main", "cluster-A", 1)
        val backend = FakeBackend().apply {
            backendRetireResults += UltraEglBackendRetirementResult.CONTROL_IN_FLIGHT
            backendRetireResults += UltraEglBackendRetirementResult.RETIRED
        }
        val coordinator = coordinator(backend, emptyList(), listOf(output))
        coordinator.attachOutput(
            DisplayDestination.MAIN,
            output,
            target("main", 800, 480),
        )

        assertThrows(IllegalStateException::class.java) { coordinator.close() }
        assertEquals(1, coordinator.stateSnapshot().outputs.size)
        assertEquals(AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN, coordinator.stateSnapshot().phase)

        coordinator.close()
        assertEquals(2, backend.backendRetireCalls)
        assertEquals(AndroidUltraRendererSessionPhase.CLOSED, coordinator.stateSnapshot().phase)
        assertTrue(coordinator.stateSnapshot().outputs.isEmpty())
    }

    @Test
    fun retireScreensUsesOneBackendWideProofAndMakesEveryResourceLeaseStale() {
        val handle = syntheticHandle(AirPlayStreamIdentity(110, "screen", null))
        val ingressId = UltraIngressId("remote")
        val output = output("main", "cluster-A", 1)
        val backend = FakeBackend()
        val coordinator = coordinator(backend, listOf(handle to ingressId), listOf(output))
        val candidate = checkNotNull(coordinator.prepareScreen(screenContext(handle, ingressId)))
        candidate.publish()
        val local = coordinator.openLocalIngress(UltraContentId("local"), PixelSize(800, 480))
        val display = coordinator.attachOutput(
            DisplayDestination.MAIN,
            output,
            target("main", 800, 480),
        )

        assertTrue(coordinator.retireScreens())
        assertEquals(1, backend.backendRetireCalls)
        assertEquals(0, backend.ingressRetireCalls)
        assertTrue(coordinator.graphSnapshot().outputs.isEmpty())
        assertTrue(coordinator.graphSnapshot().ingresses.isEmpty())
        assertTrue(coordinator.stateSnapshot().remoteIngresses.isEmpty())
        assertTrue(coordinator.stateSnapshot().ownedIngresses.isEmpty())
        assertTrue(coordinator.stateSnapshot().outputs.isEmpty())

        candidate.close()
        local.close()
        display.close()
        coordinator.close()
        assertEquals(1, backend.backendRetireCalls)
        assertEquals(AndroidUltraRendererSessionPhase.CLOSED, coordinator.stateSnapshot().phase)
    }

    @Test
    fun retireScreensFalseKeepsExactCandidateForOneWholeBackendRetry() {
        val handle = syntheticHandle(AirPlayStreamIdentity(110, "screen", null))
        val ingressId = UltraIngressId("remote")
        val backend = FakeBackend().apply {
            backendRetireResults += UltraEglBackendRetirementResult.CONTROL_IN_FLIGHT
            backendRetireResults += UltraEglBackendRetirementResult.RETIRED
        }
        val coordinator = coordinator(backend, listOf(handle to ingressId))
        val candidate = checkNotNull(coordinator.prepareScreen(screenContext(handle, ingressId)))
        candidate.publish()

        assertFalse(coordinator.retireScreens())
        assertEquals(1, coordinator.stateSnapshot().remoteIngresses.size)
        assertTrue(coordinator.retireScreens())
        assertTrue(coordinator.stateSnapshot().remoteIngresses.isEmpty())
        assertEquals(2, backend.backendRetireCalls)
        assertEquals(0, backend.ingressRetireCalls)

        candidate.close()
        coordinator.close()
    }

    @Test
    fun wrongBackendIngressKeyWithUnsafeCleanupRetainsRecordUntilGlobalProof() {
        val handle = syntheticHandle(AirPlayStreamIdentity(110, "screen", null))
        val expectedId = UltraIngressId("expected")
        val backend = FakeBackend().apply {
            remoteKeyOverride = UltraEglIngressKey.Remote(UltraIngressId("wrong"))
            ingressRetireResults += AndroidVideoSurfaceRetirementResult.DECODER_CLOSE_TIMED_OUT
        }
        val coordinator = coordinator(backend, listOf(handle to expectedId))

        assertThrows(IllegalStateException::class.java) {
            coordinator.prepareScreen(screenContext(handle, expectedId))
        }
        assertEquals(1, coordinator.stateSnapshot().remoteIngresses.size)
        assertEquals(
            AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN,
            coordinator.stateSnapshot().remoteIngresses.single().phase,
        )

        coordinator.close()
        assertTrue(coordinator.stateSnapshot().remoteIngresses.isEmpty())
        assertEquals(AndroidUltraRendererSessionPhase.CLOSED, coordinator.stateSnapshot().phase)
    }

    @Test
    fun staleLocalLeaseCannotRetireAReopenedGenerationAndNamespacesStayIndependent() {
        val backend = FakeBackend()
        val coordinator = coordinator(backend, streams = emptyList())
        val content = UltraContentId("custom")
        val oldLocal = coordinator.openLocalIngress(content, PixelSize(800, 480))
        oldLocal.close()
        val currentLocal = coordinator.openLocalIngress(content, PixelSize(800, 480))
        val overlay = coordinator.openOverlayIngress(content, PixelSize(600, 240))

        oldLocal.close()
        assertEquals(
            setOf(UltraEglIngressKey.Local(content), UltraEglIngressKey.Overlay(content)),
            coordinator.stateSnapshot().ownedIngresses.map { it.key }.toSet(),
        )

        currentLocal.close()
        overlay.close()
        coordinator.close()
    }

    @Test
    fun localThemeIngressRequiresExactOwnerGenerationSizeAndPreQueueFence() {
        val backend = FakeBackend()
        val output = output("canvas-output", "cluster-A", 81)
        val coordinator = coordinator(backend, emptyList(), listOf(output))
        val foreign = coordinator(FakeBackend(), emptyList())
        val content = UltraContentId("signed.canvas")
        val size = PixelSize(100, 60)
        val local = coordinator.openLocalIngress(content, size)
        val foreignLocal = foreign.openLocalIngress(content, size)
        val outputLease = coordinator.attachOutput(DisplayDestination.MAIN, output,
            validTarget("canvas-output"))
        try {
            assertTrue(coordinator.isCurrentLocalIngress(local, content, size))
            assertFalse(coordinator.isCurrentLocalIngress(local, content, PixelSize(99, 60)))
            assertFalse(coordinator.isCurrentLocalIngress(local, UltraContentId("other"), size))
            assertFalse(coordinator.isCurrentLocalIngress(foreignLocal, content, size))

            fun scene(revision: Long) = UltraMultiOutputRenderSnapshot(revision, listOf(
                UltraOutputScene(DisplayDestination.MAIN, PixelSize(800, 480), listOf(
                    UltraSceneLayer(UltraLayerId("canvas"), UltraSceneSource.Local(content),
                        UltraPixelRect(10, 10, 100, 60)),
                )),
            ))
            val mapping = mapOf(DisplayDestination.MAIN to output.outputId)
            val accepted = AndroidUltraLocalIngressExpectation(local, content, size)
            coordinator.resolveAndSubmitWithLocalIngresses(scene(1L), mapping,
                listOf(accepted))
            assertEquals(1L, backend.lastPlan?.renderRevision)

            local.close()
            val reopened = coordinator.openLocalIngress(content, size)
            assertFalse(coordinator.isCurrentLocalIngress(local, content, size))
            assertTrue(coordinator.isCurrentLocalIngress(reopened, content, size))
            assertThrows(IllegalArgumentException::class.java) {
                coordinator.resolveAndSubmitWithLocalIngresses(scene(2L), mapping,
                    listOf(accepted))
            }
            assertEquals(1L, backend.lastPlan?.renderRevision)
            coordinator.resolveAndSubmitWithLocalIngresses(scene(2L), mapping,
                listOf(AndroidUltraLocalIngressExpectation(reopened, content, size)))
            assertEquals(2L, backend.lastPlan?.renderRevision)
            reopened.close()
        } finally {
            foreignLocal.close()
            outputLease.close()
            coordinator.close()
            foreign.close()
        }
    }

    private class FakeBatchBinder : UltraDisplayBatchBinder, Closeable {
        var failNextCommit = false
        var failNextCommitUncertain = false
        var failNextRetirement = false
        var operational = true
        var onCommit: (() -> Unit)? = null
        var closeCalls = 0
        private val live = mutableSetOf<FakeBatchLease>()

        override fun prepare(plan: UltraDisplayBatchPlan): PreparedUltraDisplayBatch {
            require(plan.outputRoutes.isNotEmpty())
            return object : PreparedUltraDisplayBatch {
                override val generation = plan.generation

                override fun commit(): UltraDisplayBatchLease {
                    if (failNextCommitUncertain) {
                        failNextCommitUncertain = false
                        throw UltraDisplayBatchCommitUncertainException(
                            IllegalStateException("synthetic native commit timeout"),
                        )
                    }
                    if (failNextCommit) {
                        failNextCommit = false
                        throw IllegalStateException("clean synthetic batch commit failure")
                    }
                    onCommit?.invoke()
                    return FakeBatchLease().also { live += it }
                }

                override fun close() = Unit
            }
        }

        override fun close() {
            check(live.isEmpty()) { "Batch lease still owns a route" }
            closeCalls++
        }

        private inner class FakeBatchLease : UltraDisplayBatchLease {
            override val isOperational: Boolean
                get() = operational

            private val fence = object : UltraDisplayRetirementFence {
                var retired = false
                var closed = false

                override fun awaitRetirement() {
                    if (failNextRetirement) {
                        failNextRetirement = false
                        throw IllegalStateException("synthetic GPU retirement timeout")
                    }
                    retired = true
                }

                override fun close() {
                    check(retired)
                    closed = true
                }
            }

            override fun beginRetirement(): UltraDisplayRetirementFence = fence

            override fun close() {
                check(fence.closed)
                live.remove(this)
            }
        }
    }

    private class FakeBackend : AndroidUltraRendererBackendPort {
        private val surface = allocateWithoutConstructor(Surface::class.java)
        val remoteSurface: Surface get() = surface
        var frameReleaseCalls = 0
        val ingressRetireResults = ArrayDeque<AndroidVideoSurfaceRetirementResult>()
        var remoteOpenCount = 0
        val ownedIngressSizes = mutableListOf<PixelSize>()
        var ingressRetireCalls = 0
        val instrumentFrames = mutableListOf<IntArray>()
        val instrumentFrameInputs = mutableListOf<IntArray>()
        var instrumentRetireCalls = 0
        var outputAttachCalls = 0
        var outputRetireCalls = 0
        var failOutputAttachCall: Int? = null
        var claimOutputThenFailCall: Int? = null
        var onOpenRemote: (() -> Unit)? = null
        var onResolve: (() -> Unit)? = null
        var onSubmit: ((UltraEglResolvedSubmission) -> Unit)? = null
        var onInstrumentReplace: (() -> Unit)? = null
        var clusterReplayListener: ((UltraEglClusterReplayReceipt) -> Unit)? = null
        val instrumentGeneration = 5L
        var lastPlan: UltraResolvedMultiOutputRenderPlan? = null
        var backendRetireResult = UltraEglBackendRetirementResult.RETIRED
        var backendPhase = UltraEglBackendPhase.OPEN
        var remoteKeyOverride: UltraEglIngressKey? = null
        val backendRetireResults = ArrayDeque<UltraEglBackendRetirementResult>()
        var backendRetireCalls = 0
        private val outputOwnershipIdentity = Any()
        private var ambiguouslyClaimedTarget: AndroidDisplaySurfaceTarget? = null

        override fun openRemoteIngress(
            ingress: UltraDisplayGraphIngress,
        ): AndroidUltraBackendIngress {
            remoteOpenCount++
            onOpenRemote?.invoke()
            return ingress(remoteKeyOverride ?: UltraEglIngressKey.Remote(ingress.id))
        }

        override fun acceptVideoFrameReleaseReceipt(
            receipt: AndroidVideoFrameReleaseReceipt,
        ): RemoteFrameIdentityDisposition {
            frameReleaseCalls++
            return RemoteFrameIdentityDisposition.PENDING
        }

        override fun openLocalIngress(contentId: UltraContentId, sizePixels: PixelSize): AndroidUltraBackendIngress =
            ingress(UltraEglIngressKey.Local(contentId)).also { ownedIngressSizes += sizePixels }

        override fun openOverlayIngress(contentId: UltraContentId, sizePixels: PixelSize): AndroidUltraBackendIngress =
            ingress(UltraEglIngressKey.Overlay(contentId)).also { ownedIngressSizes += sizePixels }

        override fun openInstrumentRaster(
            destination: DisplayDestination,
            sizePixels: PixelSize,
        ): AndroidUltraBackendInstrument = AndroidUltraBackendInstrument(
            UltraEglIngressKey.Instrument(destination), sizePixels,
            { pixels ->
                instrumentFrameInputs += pixels
                instrumentFrames += pixels.copyOf()
                onInstrumentReplace?.invoke()
                UltraEglInstrumentUploadReceipt.COMMITTED
            },
            { instrumentRetireCalls++ },
            generation = instrumentGeneration,
        )

        override fun subscribeClusterReplay(
            instrument: AndroidUltraBackendInstrument,
            onReceipt: (UltraEglClusterReplayReceipt) -> Unit,
            onRevoked: () -> Unit,
        ): UltraEglClusterReplaySubscription {
            require(instrument.generation == instrumentGeneration)
            clusterReplayListener = onReceipt
            return UltraEglClusterReplaySubscription(
                retirement = {
                    clusterReplayListener = null
                    true
                },
                requeueAction = { receipt ->
                    Thread { onReceipt(receipt) }.start()
                    true
                },
            )
        }

        private fun ingress(key: UltraEglIngressKey) = AndroidUltraBackendIngress(
            key,
            surface,
        ) {
            ingressRetireCalls++
            if (ingressRetireResults.isEmpty()) {
                AndroidVideoSurfaceRetirementResult.RETIRED
            } else {
                ingressRetireResults.removeFirst()
            }
        }

        override fun attachOutput(
            destination: DisplayDestination,
            output: UltraDisplayGraphOutput,
            target: AndroidDisplaySurfaceTarget,
        ): AndroidUltraBackendOutput {
            outputAttachCalls++
            if (claimOutputThenFailCall == outputAttachCalls) {
                check(target.ownership.claim(outputOwnershipIdentity))
                ambiguouslyClaimedTarget = target
                throw IllegalStateException("synthetic running-control timeout")
            }
            if (failOutputAttachCall == outputAttachCalls) {
                throw IllegalStateException("synthetic output attach failure")
            }
            return AndroidUltraBackendOutput(destination) { outputRetireCalls++ }
        }

        override fun resolve(
            plan: UltraResolvedMultiOutputRenderPlan,
        ): UltraEglResolvedSubmission {
            onResolve?.invoke()
            lastPlan = plan
            return UltraEglResolvedSubmission(
                scene = UltraMultiOutputRenderSnapshot(
                    plan.renderRevision,
                    plan.scenes.map { it.scene },
                ),
                graphRevision = plan.graphRevision,
                ingressGenerations = emptyMap(),
                outputGenerations = emptyMap(),
            )
        }

        override fun submit(resolved: UltraEglResolvedSubmission): UltraEglRenderTicket {
            onSubmit?.invoke(resolved)
            return UltraEglRenderTicket(resolved.revision)
        }

        override fun phase(): UltraEglBackendPhase = backendPhase

        override fun retire(timeoutMillis: Long): UltraEglBackendRetirementResult {
            backendRetireCalls++
            val result = if (backendRetireResults.isEmpty()) {
                backendRetireResult
            } else {
                backendRetireResults.removeFirst()
            }
            if (result.isRetired) {
                ambiguouslyClaimedTarget?.ownership?.release(outputOwnershipIdentity)
                ambiguouslyClaimedTarget = null
            }
            return result
        }
    }

    private class RecordingPresentationSource : UiSyncPresentationSource {
        private val hub = UltraPresentationHub(Runnable::run)
        var closeCalls = 0

        override fun subscribeWithReplay(
            displayId: String,
            observer: UltraPresentationObserver,
        ): UiSyncPresentationSubscription {
            val lease = hub.observe(displayId, observer)
            return UiSyncPresentationSubscription(hub.snapshot(displayId), Closeable {
                closeCalls++
                lease.close()
            })
        }

        override fun snapshot(displayId: String): UltraPresentationSnapshot = hub.snapshot(displayId)
    }

    private class ManualPresentationSource(
        private val emitBeforeReturn: Boolean = false,
    ) : UiSyncPresentationSource {
        private val hub = UltraPresentationHub(Runnable::run)
        private var observer: UltraPresentationObserver? = null
        private var live = false

        override fun subscribeWithReplay(
            displayId: String,
            observer: UltraPresentationObserver,
        ): UiSyncPresentationSubscription {
            this.observer = observer
            live = true
            if (emitBeforeReturn) observer.onPresentationSnapshot(hub.snapshot(displayId))
            return UiSyncPresentationSubscription(hub.snapshot(displayId), Closeable { live = false })
        }

        override fun snapshot(displayId: String): UltraPresentationSnapshot = hub.snapshot(displayId)

        fun captureCallback(): () -> Unit {
            val captured = checkNotNull(observer)
            val snapshot = hub.snapshot("cluster-A")
            return { captured.onPresentationSnapshot(snapshot) }
        }

        fun emit() {
            if (live) checkNotNull(observer).onPresentationSnapshot(hub.snapshot("cluster-A"))
        }
    }

    private class HostileSizeCollection<T>(
        private val delegate: Collection<T>,
        private val reportedSize: () -> Int,
    ) : Collection<T> by delegate {
        override val size: Int
            get() = reportedSize()
    }

    private class HostileSizeMap<K, V>(
        private val delegate: Map<K, V>,
        private val reportedSize: () -> Int,
    ) : Map<K, V> by delegate {
        override val size: Int
            get() = reportedSize()
    }

    private fun coordinator(
        backend: FakeBackend,
        streams: List<Pair<AirPlayVideoStreamHandle, UltraIngressId>>,
        outputs: List<UltraDisplayGraphOutput> = emptyList(),
        displayIds: Set<String> = setOf("cluster-A"),
        source: UiSyncPresentationSource = RecordingPresentationSource(),
    ): AndroidUltraRendererSessionCoordinator {
        val plan = UiSyncRendererStaticPlan(
            outputs,
            streams.map { (handle, id) ->
                UiSyncRendererIngressSpec(
                    id,
                    UltraAirPlayStreamIdentity(
                        handle.identity.type,
                        handle.identity.streamConnectionId,
                        handle.identity.clientTypeUuid,
                    ),
                )
            },
        )
        return AndroidUltraRendererSessionCoordinator(
            sessionContext(source, plan, displayIds),
            backend,
        )
    }

    private fun batchCoordinator(
        backend: FakeBackend,
        binder: FakeBatchBinder,
        outputs: List<UltraDisplayGraphOutput>,
    ): AndroidUltraRendererSessionCoordinator {
        val plan = UiSyncRendererStaticPlan(outputs, emptyList())
        return AndroidUltraRendererSessionCoordinator(
            sessionContext(RecordingPresentationSource(), plan, setOf("cluster-A")),
            backend,
            { AndroidUltraBatchBinderResource(binder, binder) },
            Unit,
        )
    }

    private fun sessionContext(
        source: UiSyncPresentationSource,
        plan: UiSyncRendererStaticPlan,
        displayIds: Set<String>,
    ): UiSyncRendererSessionBindingContext {
        val constructor = UiSyncRendererSessionBindingContext::class.java.declaredConstructors
            .single { it.parameterCount == 5 }
            .apply { isAccessible = true }
        return constructor.newInstance(
            source,
            plan,
            fingerprint<UiSyncSchemaFingerprint>(1),
            fingerprint<UiSyncBindingFingerprint>(2),
            displayIds,
        ) as UiSyncRendererSessionBindingContext
    }

    private inline fun <reified T : Any> fingerprint(fill: Int): T {
        val constructor = T::class.java.declaredConstructors.single { it.parameterCount == 1 }
            .apply { isAccessible = true }
        return constructor.newInstance(ByteArray(32) { fill.toByte() }) as T
    }

    private fun screenContext(
        handle: AirPlayVideoStreamHandle,
        ingressId: UltraIngressId,
    ): UiSyncRendererScreenContext {
        val constructor = UiSyncRendererScreenContext::class.java.declaredConstructors
            .single { it.parameterCount == 2 }
            .apply { isAccessible = true }
        return constructor.newInstance(
            handle,
            UltraDisplayGraphIngress(ingressId, handle),
        ) as UiSyncRendererScreenContext
    }

    private fun output(
        outputId: String,
        displayId: String,
        uuidSuffix: Int,
    ) = UltraDisplayGraphOutput(
        topologyDisplayId = UltraTopologyDisplayId(
            UUID.fromString("00000000-0000-0000-0000-${uuidSuffix.toString().padStart(12, '0')}"),
        ),
        outputId = UltraDisplayOutputId.Android(outputId),
        uiSyncDisplayId = UltraUiSyncDisplayId(displayId),
    )

    private fun target(id: String, width: Int, height: Int) = AndroidDisplaySurfaceTarget(
        allocateWithoutConstructor(Surface::class.java),
        DisplaySurfaceDescriptor(DisplaySurfaceId(id), PixelSize(width, height)),
    )

    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid() = true
    }

    private class ReentrantSurface : Surface(null as SurfaceTexture?) {
        var onProbe: (() -> Unit)? = null

        override fun isValid(): Boolean {
            val callback = onProbe
            onProbe = null
            callback?.invoke()
            return true
        }
    }

    private fun validTarget(id: String) = AndroidDisplaySurfaceTarget(
        allocateWithoutConstructor(ValidSurface::class.java),
        DisplaySurfaceDescriptor(DisplaySurfaceId(id), PixelSize(800, 480)),
    )

    private fun layer(
        id: String,
        source: UltraSceneSource,
        z: Int = 0,
    ) = UltraSceneLayer(
        UltraLayerId(id),
        source,
        UltraPixelRect(0, 0, 100, 100),
        zIndex = z,
    )

    private fun localOnlyRender(revision: Long) = UltraMultiOutputRenderSnapshot(
        revision,
        listOf(UltraOutputScene(DisplayDestination.MAIN, PixelSize(800, 480), emptyList())),
    )

    private fun syntheticHandle(identity: AirPlayStreamIdentity): AirPlayVideoStreamHandle {
        val epochClass = Class.forName("com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch")
        val epoch = epochClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val constructor = AirPlayVideoStreamHandle::class.java.declaredConstructors
            .single { it.parameterCount == 3 }
            .apply { isAccessible = true }
        return constructor.newInstance(identity, epoch, null) as AirPlayVideoStreamHandle
    }

    private fun frameReleaseReceipt(
        handle: AirPlayVideoStreamHandle,
        surface: Surface,
    ): AndroidVideoFrameReleaseReceipt {
        val constructor = AndroidVideoFrameReleaseReceipt::class.java.declaredConstructors
            .single { it.parameterCount == 12 }.apply { isAccessible = true }
        return constructor.newInstance(handle, surface, 1L, 0L, 0L, null,
            100L, 100L, 1_000L, 1_001L, null, 100_000L) as
            AndroidVideoFrameReleaseReceipt
    }

    private companion object {
        fun <T> allocateWithoutConstructor(type: Class<T>): T {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null) as Unsafe
            @Suppress("UNCHECKED_CAST")
            return unsafe.allocateInstance(type) as T
        }
    }
}
