package com.shilapi.xcertplay.ultra.egl

import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraContentId
import com.shilapi.xcertplay.ultra.UltraLayerId
import com.shilapi.xcertplay.ultra.UltraMultiOutputRenderSnapshot
import com.shilapi.xcertplay.ultra.UltraOutputScene
import com.shilapi.xcertplay.ultra.UltraPixelAffineTransform
import com.shilapi.xcertplay.ultra.UltraPixelRect
import com.shilapi.xcertplay.ultra.UltraSceneLayer
import com.shilapi.xcertplay.ultra.UltraSceneBackground
import com.shilapi.xcertplay.ultra.UltraSceneSource
import com.shilapi.xcertplay.ultra.selectUltraOpaqueThemeBackground
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramInstruction
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UltraEglRenderCoreTest {
    private val main = DisplayDestination.MAIN
    private val cluster = DisplayDestination.CLUSTER
    private val size = PixelSize(800, 480)
    private val remoteKey = UltraEglIngressKey.Remote(UltraIngressId("remote-primary"))
    private val localKey = UltraEglIngressKey.Local(UltraContentId("custom-ui"))
    private val clusterAKey = UltraEglIngressKey.Local(UltraContentId("cluster-a"))
    private val clusterBKey = UltraEglIngressKey.Local(UltraContentId("cluster-b"))
    private val overlayKey = UltraEglIngressKey.Overlay(UltraContentId("overlay-ui"))

    @Test
    fun actualRemoteAcquisitionIsObservedWithoutGatingOrdinarySceneReplay() {
        val driver = FakeDriver().apply { acquiredTimestampForTest = 123_456_789L }
        val tokens = mutableListOf<UltraEglFrameToken>()
        val acquired = mutableListOf<UltraEglAcquiredRemoteFrame>()
        val core = UltraEglRenderCore(driver, tokens::add, acquired::add)
        core.initialize()
        core.openIngress(remoteKey, 3L, null)
        core.replaceOutputBatch(7L, listOf(
            UltraEglOutputBatchCandidate(main, 7L, Any(), size) { true },
        ))
        core.submit(UltraEglResolvedSubmission(
            UltraMultiOutputRenderSnapshot(1L, listOf(UltraOutputScene(main, size, listOf(
                UltraSceneLayer(UltraLayerId("remote"),
                    UltraSceneSource.Remote((remoteKey as UltraEglIngressKey.Remote).ingressId),
                    UltraPixelRect(0, 0, 800, 480)),
            )))), 8L, mapOf(remoteKey to 3L), mapOf(main to 7L),
        ))
        val priorRenders = driver.renders.size
        driver.signalFrame(remoteKey)
        core.frameAvailable(tokens.single())
        assertEquals(priorRenders + 1, driver.renders.size)
        assertEquals(1, acquired.size)
        assertEquals(remoteKey, acquired.single().key)
        assertEquals(3L, acquired.single().ingressGeneration)
        assertEquals(1L, acquired.single().updateSequence)
        assertEquals(123_456_789L, acquired.single().surfaceTextureTimestampNanos)

        val throwingDriver = FakeDriver().apply { acquiredTimestampForTest = 111L }
        val throwingTokens = mutableListOf<UltraEglFrameToken>()
        val throwingCore = UltraEglRenderCore(throwingDriver, throwingTokens::add,
            { throw IllegalStateException("diagnostic observer failed") })
        throwingCore.initialize()
        throwingCore.openIngress(remoteKey, 4L, null)
        // An observer failure is swallowed by the auxiliary path, not promoted to EGL failure.
        throwingDriver.signalFrame(remoteKey)
        throwingCore.frameAvailable(throwingTokens.single())
        assertEquals(UltraEglBackendPhase.OPEN, throwingCore.snapshot().phase)
        assertEquals(1, throwingDriver.updatedIngressCount)
    }

    @Test
    fun clusterReplayReceiptsTrackInstrumentContentAndUnrelatedMainFramesExactly() {
        val driver = FakeDriver().apply { providePresentationTimestamps = true }
        val tokens = mutableListOf<UltraEglFrameToken>()
        val core = UltraEglRenderCore(driver, tokens::add)
        core.initialize()
        core.openIngress(localKey, 2L, size)
        core.openInstrumentIngress(cluster, 5L, size)
        val initialPixels = IntArray(size.widthPixels * size.heightPixels) { 0xff102030.toInt() }
        assertNull(core.replaceInstrumentFrame(cluster, 5L, size, initialPixels))
        core.replaceOutputBatch(7L, listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, 7L, Any(), size) { true }
        })
        val scenes = listOf(
            UltraOutputScene(main, size, listOf(UltraSceneLayer(
                UltraLayerId("main-local"), UltraSceneSource.Local(localKey.contentId),
                UltraPixelRect(0, 0, 800, 480),
            ))),
            UltraOutputScene(cluster, size, listOf(UltraSceneLayer(
                UltraLayerId("instrument"), UltraSceneSource.Instrument(cluster),
                UltraPixelRect(0, 0, 800, 480),
            ))),
        )
        val submission = UltraEglResolvedSubmission(
            UltraMultiOutputRenderSnapshot(1L, scenes), 9L,
            mapOf(localKey to 2L, UltraEglIngressKey.Instrument(cluster) to 5L),
            mapOf(main to 7L, cluster to 7L),
        )
        val sceneReceipt = checkNotNull(core.submit(submission).clusterReplay)
        assertEquals(UltraEglClusterReplayCause.SCENE_SUBMISSION, sceneReceipt.cause)
        assertEquals(1L, sceneReceipt.sceneRevision)
        assertEquals(9L, sceneReceipt.graphRevision)
        assertEquals(7L, sceneReceipt.nativeOutputBatchGeneration)
        assertEquals(7L, sceneReceipt.nativeOutputGeneration)
        assertEquals(5L, sceneReceipt.instrumentIngressGeneration)
        assertEquals(1L, sceneReceipt.instrumentFrameSequence)
        assertEquals(102L, sceneReceipt.producerTimestampNanos)
        assertEquals(1L, sceneReceipt.replaySequence)
        assertTrue(sceneReceipt.instrumentOnlyClusterScene)

        driver.signalFrame(localKey)
        val mainReplay = checkNotNull(core.frameAvailable(tokens.single()))
        assertEquals(UltraEglClusterReplayCause.INGRESS_FRAME_REPLAY, mainReplay.cause)
        assertEquals(1L, mainReplay.instrumentFrameSequence)
        assertEquals(104L, mainReplay.producerTimestampNanos)
        assertEquals(2L, mainReplay.replaySequence)
        assertNull(core.frameAvailable(tokens.single())) // duplicate queued callback

        val updatedPixels = IntArray(initialPixels.size) { 0xff405060.toInt() }
        val uploadReplay = checkNotNull(core.replaceInstrumentFrame(cluster, 5L,
            size, updatedPixels))
        assertEquals(UltraEglClusterReplayCause.INSTRUMENT_UPLOAD, uploadReplay.cause)
        assertEquals(2L, uploadReplay.instrumentFrameSequence)
        assertEquals(106L, uploadReplay.producerTimestampNanos)
        assertEquals(3L, uploadReplay.replaySequence)

        core.replaceOutputBatch(8L, listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, 8L, Any(), size) { true }
        })
        val next = core.submit(UltraEglResolvedSubmission(
            UltraMultiOutputRenderSnapshot(2L, scenes), 10L,
            mapOf(localKey to 2L, UltraEglIngressKey.Instrument(cluster) to 5L),
            mapOf(main to 8L, cluster to 8L),
        )).clusterReplay
        assertEquals(8L, checkNotNull(next).nativeOutputBatchGeneration)
        assertEquals(4L, next.replaySequence)
        assertEquals(7L, sceneReceipt.nativeOutputBatchGeneration)
    }

    @Test
    fun mixedClusterSceneCannotMintInstrumentOnlyReplayReceipt() {
        assertThrows(IllegalArgumentException::class.java) {
            UltraOutputScene(cluster, size, listOf(
                UltraSceneLayer(UltraLayerId("instrument"),
                    UltraSceneSource.Instrument(cluster), UltraPixelRect(0, 0, 800, 480)),
                UltraSceneLayer(UltraLayerId("untrusted-overlay"),
                    UltraSceneSource.Overlay(UltraContentId("untrusted")),
                    UltraPixelRect(0, 0, 10, 10)),
            ))
        }
        val driver = FakeDriver().apply { providePresentationTimestamps = true }
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        core.replaceOutputBatch(7L, listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, 7L, Any(), size) { true }
        })
        val generic = core.submit(UltraEglResolvedSubmission(
            UltraMultiOutputRenderSnapshot(1L, listOf(
                UltraOutputScene(main, size, emptyList()),
                UltraOutputScene(cluster, size, emptyList()),
            )), 9L, emptyMap(), mapOf(main to 7L, cluster to 7L),
        ))
        assertNull(generic.clusterReplay)
        assertEquals(102L, generic.producerTimestampNanosByDestination[cluster])
    }

    @Test
    fun eglProducerTimestampsStayStrictlyIncreasingAcrossClockStallsAndFailedSwaps() {
        val values = ArrayDeque(listOf(100L, 100L, 99L, 103L))
        val allocator = EglProducerTimestampAllocator { values.removeFirst() }
        assertEquals(100L, allocator.next())
        // A failed EGL swap still consumes its assigned value; the next frame cannot alias it.
        assertEquals(101L, allocator.next())
        assertEquals(102L, allocator.next())
        assertEquals(103L, allocator.next())
    }

    @Test
    fun successfulMultiOutputReceiptSealsExactSwapTimestampsAndGenerations() {
        val driver = FakeDriver().apply { providePresentationTimestamps = true }
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        core.replaceOutputBatch(7L, listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, 7L, Any(), size) { true }
        })
        val scenes = listOf(
            UltraOutputScene(main, size, emptyList()),
            UltraOutputScene(cluster, size, emptyList()),
        )
        fun submit(revision: Long, generation: Long) = core.submit(
            UltraEglResolvedSubmission(
                UltraMultiOutputRenderSnapshot(revision, scenes), 9L, emptyMap(),
                mapOf(main to generation, cluster to generation),
            ),
        )

        val first = submit(1L, 7L)
        assertEquals(1L, first.revision)
        assertEquals(9L, first.graphRevision)
        assertEquals(7L, first.outputBatchGeneration)
        assertEquals(mapOf(main to 7L, cluster to 7L), first.outputGenerationsByDestination)
        assertEquals(mapOf(main to 101L, cluster to 102L),
            first.producerTimestampNanosByDestination)
        assertThrows(UnsupportedOperationException::class.java) {
            (first.producerTimestampNanosByDestination as MutableMap)[main] = 999L
        }

        val second = submit(2L, 7L)
        assertEquals(mapOf(main to 103L, cluster to 104L),
            second.producerTimestampNanosByDestination)
        core.replaceOutputBatch(8L, listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, 8L, Any(), size) { true }
        })
        val third = submit(3L, 8L)
        assertEquals(mapOf(main to 105L, cluster to 106L),
            third.producerTimestampNanosByDestination)
        assertEquals(mapOf(main to 8L, cluster to 8L), third.outputGenerationsByDestination)
        assertThrows(IllegalArgumentException::class.java) { submit(4L, 7L) }
        assertEquals(mapOf(main to 101L, cluster to 102L),
            first.producerTimestampNanosByDestination)
    }

    @Test
    fun secondOutputSwapOrFinishFailureNeverReturnsTimestampReceipt() {
        val secondSwap = FakeDriver().apply {
            providePresentationTimestamps = true
            failRenderDestination = cluster
        }
        val firstCore = UltraEglRenderCore(secondSwap) {}
        firstCore.initialize()
        firstCore.replaceOutputBatch(7L, listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, 7L, Any(), size) { true }
        })
        val submission = UltraEglResolvedSubmission(
            UltraMultiOutputRenderSnapshot(1L, listOf(
                UltraOutputScene(main, size, emptyList()),
                UltraOutputScene(cluster, size, emptyList()),
            )), null, emptyMap(), mapOf(main to 7L, cluster to 7L),
        )
        assertThrows(UltraEglSurfaceLostException::class.java) { firstCore.submit(submission) }
        assertEquals(-1L, firstCore.snapshot().lastPresentedRevision)

        val failedFinish = FakeDriver().apply { providePresentationTimestamps = true }
        val secondCore = UltraEglRenderCore(failedFinish) {}
        secondCore.initialize()
        secondCore.replaceOutputBatch(7L, listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, 7L, Any(), size) { true }
        })
        failedFinish.failNextFinish = true
        assertThrows(UltraEglDriverException::class.java) { secondCore.submit(submission) }
        assertEquals(-1L, secondCore.snapshot().lastPresentedRevision)
    }

    @Test
    fun localFirstFrameReceiptUsesItsOwnLaterSwapTimestampNotInitialSceneTimestamp() {
        val fixture = localFenceFixture()
        fixture.driver.providePresentationTimestamps = true
        val prepared = checkNotNull(fixture.core.prepareLocalFirstFrame(
            1L, 99L, setOf(localKey),
        ))
        assertNull(fixture.core.tryPresentLocalFirstFrame(prepared, mapOf(localKey to 1L)))
        fixture.driver.signalFrame(localKey)
        val receipt = checkNotNull(fixture.core.tryPresentLocalFirstFrame(
            prepared, mapOf(localKey to 1L),
        ))
        assertEquals(mapOf(main to 101L, cluster to 102L),
            receipt.producerTimestampNanosByDestination)
        assertEquals(mapOf(main to 7L, cluster to 7L),
            receipt.outputGenerationsByDestination)
        assertEquals(1L, receipt.revision)
        assertEquals(7L, receipt.outputBatchGeneration)
    }

    @Test
    fun dedicatedInstrumentUploadReplaysBothOutputsAndSurvivesMainRedraw() {
        val driver = FakeDriver()
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        core.openInstrumentIngress(cluster, 5L, size)
        val unavailable = IntArray(size.widthPixels * size.heightPixels) { 0xff101112.toInt() }
        core.replaceInstrumentFrame(cluster, 5L, size, unavailable)
        assertEquals(emptyList<UltraEglResolvedScene>(), driver.renders)
        core.replaceOutputBatch(7L, listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, 7L, Any(), size) { true }
        })
        val scenes = listOf(
            UltraOutputScene(main, size, emptyList()),
            UltraOutputScene(cluster, size, listOf(UltraSceneLayer(
                UltraLayerId("signed-cluster-reference"), UltraSceneSource.Instrument(cluster),
                UltraPixelRect(0, 0, size.widthPixels, size.heightPixels),
            ))),
        )
        fun submit(revision: Long) = core.submit(UltraEglResolvedSubmission(
            UltraMultiOutputRenderSnapshot(revision, scenes), null,
            mapOf(UltraEglIngressKey.Instrument(cluster) to 5L),
            mapOf(main to 7L, cluster to 7L),
        ))
        submit(1L)
        assertEquals(listOf(unavailable[0]), driver.instrumentDrawnFirstPixels)
        val fresh = IntArray(unavailable.size) { 0xff203040.toInt() }
        core.replaceInstrumentFrame(cluster, 5L, size, fresh)
        assertEquals(listOf(unavailable[0], fresh[0]), driver.instrumentDrawnFirstPixels)
        assertEquals(listOf(main, cluster, main, cluster), driver.renders.map { it.destination })
        assertEquals("finish", driver.events.last())
        submit(2L)
        assertEquals(listOf(unavailable[0], fresh[0], fresh[0]),
            driver.instrumentDrawnFirstPixels)
        assertEquals(2, driver.instrumentUploads.size)
        assertThrows(IllegalArgumentException::class.java) {
            core.replaceInstrumentFrame(cluster, 5L, size, IntArray(fresh.size) { 0x10203040 })
        }
        core.quiesceIngress(UltraEglIngressKey.Instrument(cluster), 5L)
        assertThrows(IllegalArgumentException::class.java) {
            core.replaceInstrumentFrame(cluster, 5L, size, fresh)
        }
        core.destroyIngress(UltraEglIngressKey.Instrument(cluster), 5L)
    }

    @Test
    fun instrumentSourceCannotBePartialOrMixedWithOtherClusterLayers() {
        val bounded = UltraEglRenderCore(FakeDriver()) {}
        bounded.initialize()
        assertThrows(IllegalArgumentException::class.java) {
            bounded.openInstrumentIngress(cluster, 1L, PixelSize(1601, 1000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraOutputScene(cluster, size, listOf(UltraSceneLayer(
                UltraLayerId("partial"), UltraSceneSource.Instrument(cluster),
                UltraPixelRect(1, 0, size.widthPixels - 1, size.heightPixels),
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraOutputScene(cluster, size, listOf(
                UltraSceneLayer(UltraLayerId("instrument"), UltraSceneSource.Instrument(cluster),
                    UltraPixelRect(0, 0, size.widthPixels, size.heightPixels)),
                UltraSceneLayer(UltraLayerId("overlay"), UltraSceneSource.Overlay(
                    UltraContentId("unsafe-overlay")), UltraPixelRect(0, 0, 20, 20)),
            ))
        }
    }

    @Test
    fun sceneTicketBeforeCanvasPostIsNotALocalFirstFrameReceipt() {
        val fixture = localFenceFixture()
        val prepared = checkNotNull(fixture.core.prepareLocalFirstFrame(
            1L, 99L, setOf(localKey),
        ))
        assertEquals(1L, fixture.core.snapshot().lastPresentedRevision)
        assertNull(fixture.core.tryPresentLocalFirstFrame(prepared, mapOf(localKey to 1L)))
        assertEquals(0, fixture.driver.updatedIngressCount)

        fixture.driver.signalFrame(localKey)
        val receipt = checkNotNull(fixture.core.tryPresentLocalFirstFrame(
            prepared, mapOf(localKey to 1L),
        ))
        assertEquals(1L, receipt.revision)
        assertEquals(7L, receipt.outputBatchGeneration)
        assertEquals(99L, receipt.physicalBatchGeneration)
        assertEquals(mapOf(localKey to 1L), receipt.postSequences)
        assertEquals(setOf(
            UltraEglLocalLayerDraw(main, localKey, 0, fixture.scenes[0].layers[0]),
            UltraEglLocalLayerDraw(cluster, localKey, 0, fixture.scenes[1].layers[0]),
        ), receipt.requiredDraws)
        assertEquals(1, fixture.driver.updatedIngressCount)
        assertEquals(listOf(main, cluster), fixture.driver.proofSwaps)
        assertEquals("finish", fixture.driver.events.last())
    }

    @Test
    fun clusterOnlyLayoutSwitchCannotReuseTheMainFirstPostProof() {
        val fixture = localFenceFixture()
        val first = checkNotNull(fixture.core.prepareLocalFirstFrame(1L, 7L,
            setOf(localKey)))
        fixture.driver.signalFrame(localKey)
        checkNotNull(fixture.core.tryPresentLocalFirstFrame(first, mapOf(localKey to 1L)))

        // MAIN's exact scene is unchanged. CLUSTER changes its layer placement while both
        // outputs still use this Local ingress. A prior post/callback cannot be repurposed as
        // a first-frame proof for revision 2; the controller must terminate if no new trusted
        // ingress/carry-forward contract exists.
        val changedCluster = UltraOutputScene(cluster, size, listOf(
            UltraSceneLayer(UltraLayerId("local-cluster"),
                UltraSceneSource.Local(localKey.contentId), UltraPixelRect(0, 0, 800, 480),
                zIndex = 1),
        ))
        fixture.core.submit(UltraEglResolvedSubmission(
            UltraMultiOutputRenderSnapshot(2L, listOf(fixture.scenes.first(), changedCluster)),
            null,
            mapOf(localKey to 1L),
            mapOf(main to 7L, cluster to 7L),
        ))
        assertNull(fixture.core.prepareLocalFirstFrame(2L, 7L, setOf(localKey)))
    }

    @Test
    fun aQueuedCallbackAlreadyConsumedByProofIsNotUpdatedAgain() {
        val fixture = localFenceFixture()
        val first = checkNotNull(fixture.core.prepareLocalFirstFrame(1L, 99L,
            setOf(localKey)))
        fixture.driver.signalFrame(localKey)
        checkNotNull(fixture.core.tryPresentLocalFirstFrame(first, mapOf(localKey to 1L)))
        fixture.driver.deliverFrame(fixture.core, localKey, 1L)
        assertEquals(1, fixture.driver.updatedIngressCount)
    }

    @Test
    fun clusterFreshPostCarriesAlreadyPresentedMainWithoutAnotherMainPost() {
        val fixture = mixedCarryFixture()
        val prepared = checkNotNull(fixture.core.prepareLocalFirstFrame(
            2L, 99L, setOf(localKey, clusterBKey), fixture.firstReceipt, setOf(localKey),
        ))
        assertNull(fixture.core.tryPresentLocalFirstFrame(prepared,
            mapOf(localKey to 1L, clusterBKey to 3L)))
        fixture.driver.signalFrame(clusterBKey)
        val receipt = checkNotNull(fixture.core.tryPresentLocalFirstFrame(prepared,
            mapOf(localKey to 1L, clusterBKey to 3L)))
        assertEquals(2L, receipt.revision)
        assertEquals(mapOf(localKey to 1L, clusterBKey to 3L), receipt.postSequences)
        assertEquals(3, fixture.driver.updatedIngressCount)
        assertEquals(1, fixture.driver.updatedIngressKeys.count { it == localKey })
        assertEquals(1, fixture.driver.updatedIngressKeys.count { it == clusterAKey })
        assertEquals(1, fixture.driver.updatedIngressKeys.count { it == clusterBKey })
        assertEquals(listOf(main, cluster, main, cluster), fixture.driver.proofSwaps)
        assertEquals(2, receipt.requiredDraws.size)
        assertEquals(fixture.secondScenes[1].layers[0], receipt.requiredDraws.single {
            it.destination == cluster
        }.layer)
    }

    @Test
    fun carryRejectsOldReceiptExtraCallbackAndChangedPhysicalBatch() {
        val extra = mixedCarryFixture()
        extra.driver.signalFrame(localKey)
        assertNull(extra.core.prepareLocalFirstFrame(2L, 99L,
            setOf(localKey, clusterBKey), extra.firstReceipt, setOf(localKey)))

        val stale = mixedCarryFixture()
        val prepared = checkNotNull(stale.core.prepareLocalFirstFrame(2L, 99L,
            setOf(localKey, clusterBKey), stale.firstReceipt, setOf(localKey)))
        stale.driver.signalFrame(clusterBKey)
        val second = checkNotNull(stale.core.tryPresentLocalFirstFrame(prepared,
            mapOf(localKey to 1L, clusterBKey to 3L)))
        stale.core.submit(UltraEglResolvedSubmission(
            UltraMultiOutputRenderSnapshot(3L, stale.firstScenes), null,
            mapOf(localKey to 1L, clusterAKey to 2L),
            mapOf(main to 7L, cluster to 7L),
        ))
        assertNull(stale.core.prepareLocalFirstFrame(3L, 99L,
            setOf(localKey, clusterAKey), stale.firstReceipt, setOf(localKey, clusterAKey)))
        assertNull(stale.core.prepareLocalFirstFrame(3L, 99L,
            setOf(localKey, clusterAKey), second, setOf(localKey, clusterAKey)))

        val changed = mixedCarryFixture()
        changed.core.replaceOutputBatch(8L, listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, 8L, Any(), size) { true }
        })
        changed.core.submit(UltraEglResolvedSubmission(
            UltraMultiOutputRenderSnapshot(3L, changed.secondScenes), null,
            mapOf(localKey to 1L, clusterBKey to 3L),
            mapOf(main to 8L, cluster to 8L),
        ))
        assertNull(changed.core.prepareLocalFirstFrame(3L, 99L,
            setOf(localKey, clusterBKey), changed.firstReceipt, setOf(localKey)))
    }

    @Test
    fun carryRejectsHiddenLayerAndSwapFailure() {
        val hidden = mixedCarryFixture(hiddenCluster = true)
        val prepared = checkNotNull(hidden.core.prepareLocalFirstFrame(2L, 99L,
            setOf(localKey, clusterBKey), hidden.firstReceipt, setOf(localKey)))
        hidden.driver.signalFrame(clusterBKey)
        assertNull(hidden.core.tryPresentLocalFirstFrame(prepared,
            mapOf(localKey to 1L, clusterBKey to 3L)))

        val failed = mixedCarryFixture()
        val failedPrepared = checkNotNull(failed.core.prepareLocalFirstFrame(2L, 99L,
            setOf(localKey, clusterBKey), failed.firstReceipt, setOf(localKey)))
        failed.driver.signalFrame(clusterBKey)
        failed.driver.failProofSwapDestination = cluster
        assertThrows(UltraEglSurfaceLostException::class.java) {
            failed.core.tryPresentLocalFirstFrame(failedPrepared,
                mapOf(localKey to 1L, clusterBKey to 3L))
        }
        assertEquals(UltraEglBackendPhase.FAILED, failed.core.snapshot().phase)
    }

    private data class MixedCarryFixture(
        val core: UltraEglRenderCore,
        val driver: FakeDriver,
        val firstScenes: List<UltraOutputScene>,
        val secondScenes: List<UltraOutputScene>,
        val firstReceipt: UltraEglLocalFirstFrameReceipt,
    )

    private fun mixedCarryFixture(hiddenCluster: Boolean = false): MixedCarryFixture {
        val driver = FakeDriver()
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        core.openIngress(localKey, 1L, size)
        core.openIngress(clusterAKey, 2L, size)
        core.replaceOutputBatch(7L, listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, 7L, Any(), size) { true }
        })
        val mainScene = UltraOutputScene(main, size, listOf(UltraSceneLayer(
            UltraLayerId("main-asset"), UltraSceneSource.Local(localKey.contentId),
            UltraPixelRect(0, 0, 800, 480),
        )))
        val firstScenes = listOf(mainScene, UltraOutputScene(cluster, size, listOf(
            UltraSceneLayer(UltraLayerId("cluster-a"), UltraSceneSource.Local(clusterAKey.contentId),
                UltraPixelRect(0, 0, 800, 480)),
        )))
        core.submit(UltraEglResolvedSubmission(
            UltraMultiOutputRenderSnapshot(1L, firstScenes), null,
            mapOf(localKey to 1L, clusterAKey to 2L),
            mapOf(main to 7L, cluster to 7L),
        ))
        val first = checkNotNull(core.prepareLocalFirstFrame(1L, 99L,
            setOf(localKey, clusterAKey)))
        driver.signalFrame(localKey)
        driver.signalFrame(clusterAKey)
        val firstReceipt = checkNotNull(core.tryPresentLocalFirstFrame(first,
            mapOf(localKey to 1L, clusterAKey to 2L)))
        core.openIngress(clusterBKey, 3L, size)
        val secondScenes = listOf(mainScene, UltraOutputScene(cluster, size, listOf(
            UltraSceneLayer(UltraLayerId("cluster-b"), UltraSceneSource.Local(clusterBKey.contentId),
                UltraPixelRect(20, 10, 760, 450), opacity = if (hiddenCluster) 0.0 else 1.0),
        )))
        core.submit(UltraEglResolvedSubmission(
            UltraMultiOutputRenderSnapshot(2L, secondScenes), null,
            mapOf(localKey to 1L, clusterBKey to 3L),
            mapOf(main to 7L, cluster to 7L),
        ))
        return MixedCarryFixture(core, driver, firstScenes, secondScenes, firstReceipt)
    }

    @Test
    fun missingCallbackStaleSceneAndReplacedOutputNeverIssueReceipt() {
        val queuedOldCallback = localFenceFixture()
        // The old callback has not been consumed by the render queue yet. Its eventual arrival
        // after a new scene must not be mistaken for the new scene's first Canvas post.
        queuedOldCallback.driver.signalFrame(localKey)
        queuedOldCallback.core.submit(queuedOldCallback.submission(2L, 7L))
        assertNull(queuedOldCallback.core.prepareLocalFirstFrame(2L, 7L, setOf(localKey)))

        val missing = localFenceFixture()
        val missingPrepared = checkNotNull(missing.core.prepareLocalFirstFrame(
            1L, 7L, setOf(localKey),
        ))
        assertNull(missing.core.tryPresentLocalFirstFrame(
            missingPrepared, mapOf(localKey to 1L),
        ))
        assertTrue(missing.driver.proofSwaps.isEmpty())

        val ambiguousPosts = localFenceFixture()
        val ambiguousPrepared = checkNotNull(ambiguousPosts.core.prepareLocalFirstFrame(
            1L, 7L, setOf(localKey),
        ))
        ambiguousPosts.driver.signalFrame(localKey)
        ambiguousPosts.driver.signalFrame(localKey)
        assertNull(ambiguousPosts.core.tryPresentLocalFirstFrame(
            ambiguousPrepared, mapOf(localKey to 1L),
        ))

        val staleScene = localFenceFixture()
        val stalePrepared = checkNotNull(staleScene.core.prepareLocalFirstFrame(
            1L, 7L, setOf(localKey),
        ))
        staleScene.core.submit(staleScene.submission(2L, 7L))
        staleScene.driver.signalFrame(localKey)
        assertNull(staleScene.core.tryPresentLocalFirstFrame(
            stalePrepared, mapOf(localKey to 1L),
        ))

        val staleOutput = localFenceFixture()
        val outputPrepared = checkNotNull(staleOutput.core.prepareLocalFirstFrame(
            1L, 7L, setOf(localKey),
        ))
        staleOutput.core.replaceOutputBatch(8L, listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, 8L, Any(), size) { true }
        })
        staleOutput.driver.signalFrame(localKey)
        assertNull(staleOutput.core.tryPresentLocalFirstFrame(
            outputPrepared, mapOf(localKey to 1L),
        ))

        val staleIngress = localFenceFixture()
        val ingressPrepared = checkNotNull(staleIngress.core.prepareLocalFirstFrame(
            1L, 7L, setOf(localKey),
        ))
        staleIngress.core.quiesceIngress(localKey, 1L)
        staleIngress.driver.signalFrame(localKey)
        assertNull(staleIngress.core.tryPresentLocalFirstFrame(
            ingressPrepared, mapOf(localKey to 1L),
        ))
    }

    @Test
    fun missingOutputHiddenLayerAndSwapFailureDoNotIssueReceipt() {
        val missingOutput = localFenceFixture(includeClusterScene = false)
        assertNull(missingOutput.core.prepareLocalFirstFrame(1L, 7L, setOf(localKey)))

        for (hidden in listOf(
            UltraSceneLayer(UltraLayerId("invisible-opacity"),
                UltraSceneSource.Local(localKey.contentId), UltraPixelRect(0, 0, 800, 480),
                opacity = 0.0),
            UltraSceneLayer(UltraLayerId("invisible-clip"),
                UltraSceneSource.Local(localKey.contentId), UltraPixelRect(0, 0, 800, 480),
                clipPixels = UltraPixelRect(900, 0, 20, 20)),
        )) {
            val fixture = localFenceFixture(mainLayer = hidden)
            val prepared = checkNotNull(fixture.core.prepareLocalFirstFrame(
                1L, 7L, setOf(localKey),
            ))
            fixture.driver.signalFrame(localKey)
            assertNull(fixture.core.tryPresentLocalFirstFrame(
                prepared, mapOf(localKey to 1L),
            ))
            assertTrue(fixture.driver.proofSwaps.isEmpty())
        }

        val swapFailure = localFenceFixture()
        val prepared = checkNotNull(swapFailure.core.prepareLocalFirstFrame(
            1L, 7L, setOf(localKey),
        ))
        swapFailure.driver.signalFrame(localKey)
        swapFailure.driver.failProofSwapDestination = cluster
        assertThrows(UltraEglSurfaceLostException::class.java) {
            swapFailure.core.tryPresentLocalFirstFrame(prepared, mapOf(localKey to 1L))
        }
        assertEquals(UltraEglBackendPhase.FAILED, swapFailure.core.snapshot().phase)
    }

    @Test
    fun localFirstFramePreflightUsesTransformedOutputPixelVisibility() {
        val movedIn = localFenceFixture(mainLayer = UltraSceneLayer(
            UltraLayerId("moved-in"), UltraSceneSource.Local(localKey.contentId),
            UltraPixelRect(-100, 10, 20, 20),
            transformPixels = UltraPixelAffineTransform(translateXPixels = 110.0),
        ))
        val preparedIn = checkNotNull(movedIn.core.prepareLocalFirstFrame(
            1L, 7L, setOf(localKey)))
        movedIn.driver.signalFrame(localKey)
        assertTrue(movedIn.core.tryPresentLocalFirstFrame(
            preparedIn, mapOf(localKey to 1L)) != null)
        assertEquals(2, movedIn.driver.proofSwaps.size)

        val movedOut = localFenceFixture(mainLayer = UltraSceneLayer(
            UltraLayerId("moved-out"), UltraSceneSource.Local(localKey.contentId),
            UltraPixelRect(10, 10, 20, 20),
            transformPixels = UltraPixelAffineTransform(translateXPixels = 1_000.0),
        ))
        val preparedOut = checkNotNull(movedOut.core.prepareLocalFirstFrame(
            1L, 7L, setOf(localKey)))
        movedOut.driver.signalFrame(localKey)
        assertNull(movedOut.core.tryPresentLocalFirstFrame(
            preparedOut, mapOf(localKey to 1L)))
        assertTrue(movedOut.driver.proofSwaps.isEmpty())
    }

    private data class LocalFenceFixture(
        val core: UltraEglRenderCore,
        val driver: FakeDriver,
        val scenes: List<UltraOutputScene>,
    ) {
        fun submission(revision: Long, outputGeneration: Long) = UltraEglResolvedSubmission(
            UltraMultiOutputRenderSnapshot(revision, scenes), null,
            mapOf(UltraEglIngressKey.Local(UltraContentId("custom-ui")) to 1L),
            scenes.associate { it.destination to outputGeneration },
        )
    }

    private fun localFenceFixture(
        mainLayer: UltraSceneLayer = UltraSceneLayer(
            UltraLayerId("local-main"), UltraSceneSource.Local(localKey.contentId),
            UltraPixelRect(0, 0, 800, 480),
        ),
        includeClusterScene: Boolean = true,
    ): LocalFenceFixture {
        val driver = FakeDriver()
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        core.openIngress(localKey, 1L, size)
        core.replaceOutputBatch(7L, listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, 7L, Any(), size) { true }
        })
        val scenes = listOf(UltraOutputScene(main, size, listOf(mainLayer))) +
            if (includeClusterScene) listOf(UltraOutputScene(cluster, size, listOf(
                UltraSceneLayer(UltraLayerId("local-cluster"),
                    UltraSceneSource.Local(localKey.contentId), UltraPixelRect(0, 0, 800, 480)),
            ))) else emptyList()
        return LocalFenceFixture(core, driver, scenes).also {
            core.submit(it.submission(1L, 7L))
        }
    }

    @Test
    fun failedSecondBatchCandidatePreservesOldGenerationAndSceneReplay() {
        val driver = FakeDriver()
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        fun candidates(generation: Long) = listOf(main, cluster).map { destination ->
            UltraEglOutputBatchCandidate(destination, generation, Any(), size) { true }
        }
        fun scene(revision: Long) = UltraEglResolvedSubmission(
            scene = UltraMultiOutputRenderSnapshot(revision, listOf(
                UltraOutputScene(main, size, emptyList()),
                UltraOutputScene(cluster, size, emptyList()),
            )),
            graphRevision = null,
            ingressGenerations = emptyMap(),
            outputGenerations = mapOf(main to 1L, cluster to 1L),
        )
        core.replaceOutputBatch(1L, candidates(1L))
        core.submit(scene(1L))
        driver.failCreateAt = 4

        assertThrows(IllegalStateException::class.java) {
            core.replaceOutputBatch(2L, candidates(2L))
        }

        assertEquals(UltraEglBackendPhase.OPEN, core.snapshot().phase)
        assertEquals(mapOf(main to size, cluster to size), core.snapshot().outputSizes)
        assertEquals(2, driver.liveOutputs.size)
        core.submit(scene(2L))
        assertEquals(2L, core.snapshot().lastPresentedRevision)
        core.retireOutputBatch(1L)
        assertTrue(driver.liveOutputs.isEmpty())
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
    }

    @Test
    fun targetInvalidatedAfterGpuFinishRollsBackCandidateWithoutLosingOldBatch() {
        val driver = FakeDriver()
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        core.replaceOutputBatch(1L, listOf(
            UltraEglOutputBatchCandidate(main, 1L, Any(), size) { true },
        ))
        var validityReads = 0

        assertThrows(IllegalStateException::class.java) {
            core.replaceOutputBatch(2L, listOf(
                UltraEglOutputBatchCandidate(main, 2L, Any(), size) {
                    ++validityReads == 1
                },
            ))
        }

        assertEquals(2, validityReads)
        assertEquals(UltraEglBackendPhase.OPEN, core.snapshot().phase)
        assertEquals(mapOf(main to size), core.snapshot().outputSizes)
        assertEquals(1, driver.liveOutputs.size)
        core.retireOutputBatch(1L)
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
    }

    @Test
    fun customIngressesPassExactPhysicalBufferSizeWhileRemoteRemainsDecoderOwned() {
        val driver = FakeDriver()
        val core = UltraEglRenderCore(driver) {}
        val localPixels = PixelSize(1919, 719)
        val overlayPixels = PixelSize(603, 211)
        core.initialize()

        assertThrows(IllegalArgumentException::class.java) { PixelSize(0, 719) }
        assertThrows(IllegalArgumentException::class.java) { PixelSize(603, -1) }
        assertThrows(IllegalArgumentException::class.java) {
            core.openIngress(localKey, 1L, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            core.openIngress(overlayKey, 2L, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            core.openIngress(remoteKey, 3L, localPixels)
        }
        assertTrue(driver.createdIngresses.isEmpty())

        core.openIngress(localKey, 1L, localPixels)
        core.openIngress(overlayKey, 2L, overlayPixels)
        core.openIngress(remoteKey, 3L, null)
        assertEquals(
            listOf(localKey to localPixels, overlayKey to overlayPixels, remoteKey to null),
            driver.createdIngresses,
        )

        for ((key, generation) in listOf(localKey to 1L, overlayKey to 2L, remoteKey to 3L)) {
            core.quiesceIngress(key, generation)
            core.destroyIngress(key, generation)
        }
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
        assertTrue(driver.closed)
    }

    @Test
    fun fanoutAndPerOutputScenesUseExactFrozenGenerations() {
        val driver = FakeDriver()
        val signalled = mutableListOf<UltraEglFrameToken>()
        val core = UltraEglRenderCore(driver, signalled::add)
        core.initialize()
        core.openIngress(remoteKey, 1L, null)
        core.openIngress(localKey, 2L, size)
        core.attachOutput(main, 3L, Any(), size) { true }
        core.attachOutput(cluster, 4L, Any(), size) { true }
        val submission = resolvedSubmission(revision = 1L)

        core.submit(submission)

        assertEquals(listOf(main, cluster), driver.renders.map { it.destination })
        assertTrue(driver.renders.all { it.background === UltraSceneBackground.Transparent })
        assertTrue(
            driver.renders.flatMap { it.layers }.filter { it.ingress != null }
                .all { it.ingress?.hasUsableFrame == false },
        )
        assertEquals(3, driver.finishCount) // one per output attach, then one complete scene batch
        assertEquals(1L, core.snapshot().lastPresentedRevision)

        driver.signalFrame(remoteKey)
        assertEquals(remoteKey, signalled.single().key)
        core.frameAvailable(signalled.single())
        assertEquals(1, driver.updatedIngressCount)
        assertEquals(4, driver.renders.size)

        core.quiesceIngress(remoteKey, 1L)
        core.destroyIngress(remoteKey, 1L)
        core.quiesceIngress(localKey, 2L)
        core.destroyIngress(localKey, 2L)
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
        assertTrue(driver.closed)
    }

    @Test
    fun retiringOldLocalIngressDoesNotDiscardNewRemoteOnlySceneReplay() {
        val driver = FakeDriver()
        val signalled = mutableListOf<UltraEglFrameToken>()
        val core = UltraEglRenderCore(driver, signalled::add)
        core.initialize()
        core.openIngress(remoteKey, 1L, null)
        core.openIngress(localKey, 2L, size)
        core.attachOutput(main, 3L, Any(), size) { true }
        core.attachOutput(cluster, 4L, Any(), size) { true }
        core.submit(resolvedSubmission(1L))
        val replacement = UltraEglResolvedSubmission(
            scene = UltraMultiOutputRenderSnapshot(2L, listOf(
                UltraOutputScene(main, size, listOf(UltraSceneLayer(
                    UltraLayerId("replacement-main-video"),
                    UltraSceneSource.Remote((remoteKey as UltraEglIngressKey.Remote).ingressId),
                    UltraPixelRect(0, 0, 800, 480),
                ))),
                UltraOutputScene(cluster, size, listOf(UltraSceneLayer(
                    UltraLayerId("replacement-cluster-video"),
                    UltraSceneSource.Remote((remoteKey as UltraEglIngressKey.Remote).ingressId),
                    UltraPixelRect(0, 0, 800, 480),
                ))),
            )),
            graphRevision = 9L,
            ingressGenerations = mapOf(remoteKey to 1L),
            outputGenerations = mapOf(main to 3L, cluster to 4L),
        )
        core.submit(replacement)
        val presentedBeforeRetirement = driver.renders.size

        core.quiesceIngress(localKey, 2L)
        core.destroyIngress(localKey, 2L)
        driver.signalFrame(remoteKey)
        core.frameAvailable(signalled.single())

        assertEquals(presentedBeforeRetirement + 2, driver.renders.size)
        assertTrue(driver.renders.takeLast(2).flatMap { it.layers }
            .all { it.layer.source is UltraSceneSource.Remote && it.ingress != null })
        core.quiesceIngress(remoteKey, 1L)
        core.destroyIngress(remoteKey, 1L)
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
    }

    @Test
    fun selectedOpaqueThemeBackgroundReachesExactFakeDriverOutputWithoutScaling() {
        val program = CarPlayThemeProgramCompiler().compile(
            CarPlayThemeSchema.parseDisplay(mapOf(
                "id" to "center-console",
                "type" to "centerConsole",
                "size" to listOf(400, 200),
                "density" to 3,
                "allowManufacturerAppIcon" to false,
                "requiresDarkAppearance" to false,
                "layouts" to listOf(mapOf(
                    "id" to "selected-layout",
                    "zones" to listOf(mapOf(
                        "id" to "zone",
                        "frame" to listOf(listOf(0, 0), listOf(400, 200)),
                        "components" to emptyList<Any>(),
                        "type" to mapOf("single" to emptyMap<String, Any?>()),
                    )),
                    "layoutType" to "primary",
                    "backgroundColor" to "#123456",
                    "safeAreaInsets" to listOf(0, 0, 0, 0),
                    "driveModeThemeConfigurations" to emptyMap<String, Any?>(),
                )),
            )),
        ).structuralProgram
        val address = program.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>()
            .single().address
        val graphOutput = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID.fromString("00000000-0000-0000-0000-000000000005")),
            UltraDisplayOutputId.Android("main-render-core"),
        )
        val graph = UltraDisplayGraphRegistry().replace(listOf(graphOutput), emptyList())
        val background = selectUltraOpaqueThemeBackground(
            program, address, main, graph, graphOutput.outputId, size,
            fullViewportMappingConfirmed = true,
        )
        val scene = UltraMultiOutputRenderSnapshot(51L, listOf(
            UltraOutputScene(main, size, emptyList(), background),
        ))
        val plan = scene.resolveAgainst(graph, mapOf(main to graphOutput.outputId))
        val submission = resolveUltraEglPlan(plan, mapOf(main to UltraEglBoundOutput(
            generation = 3L, graphOutput = graphOutput, sizePixels = size, isValid = true,
        )), emptyMap())
        val driver = FakeDriver()
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        core.attachOutput(main, 3L, Any(), size) { true }
        core.submit(submission)

        assertEquals(51L, core.snapshot().lastPresentedRevision)
        assertEquals(main, driver.renders.single().destination)
        assertSame(background, driver.renders.single().background)
        assertEquals(size, driver.renders.single().viewportPixels)
        assertEquals(0x12 / 255f, eglClearColorFor(background).red)
        assertEquals(0x34 / 255f, eglClearColorFor(background).green)
        assertEquals(0x56 / 255f, eglClearColorFor(background).blue)
        assertEquals(1f, eglClearColorFor(background).alpha)
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
    }

    @Test
    fun staleFrameTokenCannotUpdateReopenedIngressWithSameKey() {
        val driver = FakeDriver()
        val tokens = mutableListOf<UltraEglFrameToken>()
        val core = UltraEglRenderCore(driver, tokens::add)
        core.initialize()
        core.openIngress(remoteKey, 1L, null)
        driver.signalFrame(remoteKey)
        val stale = tokens.single()
        core.quiesceIngress(remoteKey, 1L)
        core.destroyIngress(remoteKey, 1L)

        core.openIngress(remoteKey, 2L, null)
        driver.signalFrame(remoteKey)
        val current = tokens.last()
        core.frameAvailable(stale)
        assertEquals(0, driver.updatedIngressCount)
        core.frameAvailable(current)
        assertEquals(1, driver.updatedIngressCount)

        core.quiesceIngress(remoteKey, 2L)
        core.destroyIngress(remoteKey, 2L)
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
    }

    @Test
    fun closeKeepsResourcesUntilDriverReturnsExplicitProof() {
        val driver = FakeDriver().apply { closeProofs += false; closeProofs += true }
        val core = UltraEglRenderCore(driver) {}
        core.initialize()

        val first = core.closeDriver(Long.MAX_VALUE)
        assertTrue(!first.retired)
        assertEquals(UltraEglBackendPhase.RETIREMENT_UNCERTAIN, core.snapshot().phase)
        val second = core.closeDriver(Long.MAX_VALUE)
        assertTrue(second.retired)
        assertEquals(UltraEglBackendPhase.CLOSED, core.snapshot().phase)
        assertEquals(2, driver.closeProofCallCount)
    }

    @Test
    fun quiesceFinishesGpuBeforeProducerEndpointDestruction() {
        val driver = FakeDriver()
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        core.openIngress(remoteKey, 7L, null)

        expectFailure<IllegalStateException> { core.destroyIngress(remoteKey, 7L) }
        core.quiesceIngress(remoteKey, 7L)
        core.destroyIngress(remoteKey, 7L)

        assertEquals(listOf("finish", "destroyIngress"), driver.events.takeLast(2))
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
    }

    @Test
    fun staleResolvedLeaseIsRejectedBeforeAnyRenderCall() {
        val driver = FakeDriver()
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        core.openIngress(remoteKey, 1L, null)
        core.openIngress(localKey, 2L, size)
        core.attachOutput(main, 3L, Any(), size) { true }
        core.attachOutput(cluster, 4L, Any(), size) { true }
        val stale = UltraEglResolvedSubmission(
            scene = renderSnapshot(1L),
            graphRevision = 9L,
            ingressGenerations = mapOf(remoteKey to 99L, localKey to 2L),
            outputGenerations = mapOf(main to 3L, cluster to 4L),
        )

        expectFailure<IllegalArgumentException> { core.submit(stale) }

        assertTrue(driver.renders.isEmpty())
        assertEquals(UltraEglBackendPhase.OPEN, core.snapshot().phase)
        core.quiesceIngress(remoteKey, 1L)
        core.destroyIngress(remoteKey, 1L)
        core.quiesceIngress(localKey, 2L)
        core.destroyIngress(localKey, 2L)
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
    }

    @Test
    fun invalidSecondOutputRejectsWholeSceneBeforeAnyOutputIsTouched() {
        val driver = FakeDriver()
        val clusterValid = AtomicBoolean(true)
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        core.openIngress(remoteKey, 1L, null)
        core.openIngress(localKey, 2L, size)
        core.attachOutput(main, 3L, Any(), size) { true }
        core.attachOutput(cluster, 4L, Any(), size) { clusterValid.get() }
        val finishBeforeScene = driver.finishCount

        clusterValid.set(false)
        val failure = expectFailure<UltraEglOutputTargetUnavailableException> {
            core.submit(resolvedSubmission(1L))
        }

        assertEquals(cluster, failure.destination)
        assertTrue(driver.renders.isEmpty())
        assertTrue(driver.events.none { it == "clear" })
        assertEquals(finishBeforeScene, driver.finishCount)
        assertEquals(UltraEglBackendPhase.OPEN, core.snapshot().phase)
        assertEquals(-1L, core.snapshot().lastPresentedRevision)

        clusterValid.set(true)
        core.submit(resolvedSubmission(1L))
        assertEquals(2, driver.renders.size)
        assertEquals(1L, core.snapshot().lastPresentedRevision)
        core.quiesceIngress(remoteKey, 1L)
        core.destroyIngress(remoteKey, 1L)
        core.quiesceIngress(localKey, 2L)
        core.destroyIngress(localKey, 2L)
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
    }

    @Test
    fun resizedSecondOutputRejectsWholeSceneBeforeFirstOutputPresentation() {
        val driver = FakeDriver()
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        core.openIngress(remoteKey, 1L, null)
        core.openIngress(localKey, 2L, size)
        core.attachOutput(main, 3L, Any(), size) { true }
        core.attachOutput(cluster, 4L, Any(), size) { true }
        val finishBeforeScene = driver.finishCount

        driver.actualOutputSizes[cluster] = PixelSize(1200, 480)
        val failure = expectFailure<UltraEglOutputSizeChangedException> {
            core.submit(resolvedSubmission(1L))
        }

        assertEquals(cluster, failure.destination)
        assertEquals(size, failure.expectedSize)
        assertEquals(PixelSize(1200, 480), failure.actualSize)
        assertEquals(listOf("validate:$main", "validate:$cluster"), driver.events.takeLast(2))
        assertTrue(driver.renders.isEmpty())
        assertEquals(finishBeforeScene, driver.finishCount)
        assertEquals(-1L, core.snapshot().lastPresentedRevision)
        assertEquals(UltraEglBackendPhase.FAILED, core.snapshot().phase)
        core.quiesceIngress(remoteKey, 1L)
        core.destroyIngress(remoteKey, 1L)
        core.quiesceIngress(localKey, 2L)
        core.destroyIngress(localKey, 2L)
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
    }

    @Test
    fun frameReplayAfterTargetClosureDropsCurrentSceneWithoutDriverPresentation() {
        val driver = FakeDriver()
        val tokens = mutableListOf<UltraEglFrameToken>()
        val clusterValid = AtomicBoolean(true)
        val core = UltraEglRenderCore(driver, tokens::add)
        core.initialize()
        core.openIngress(remoteKey, 1L, null)
        core.openIngress(localKey, 2L, size)
        core.attachOutput(main, 3L, Any(), size) { true }
        core.attachOutput(cluster, 4L, Any(), size) { clusterValid.get() }
        core.submit(resolvedSubmission(1L))
        val finishAfterInitialScene = driver.finishCount

        clusterValid.set(false)
        driver.signalFrame(remoteKey)
        val failure = expectFailure<UltraEglOutputTargetUnavailableException> {
            core.frameAvailable(tokens.single())
        }

        assertEquals(cluster, failure.destination)
        assertEquals(0, driver.updatedIngressCount)
        assertEquals(2, driver.renders.size)
        assertEquals(finishAfterInitialScene, driver.finishCount)
        assertEquals(UltraEglBackendPhase.OPEN, core.snapshot().phase)

        clusterValid.set(true)
        driver.signalFrame(remoteKey)
        core.frameAvailable(tokens.last())
        assertEquals(1, driver.updatedIngressCount)
        assertEquals(2, driver.renders.size) // Closed target cleared the replayable scene.
        core.submit(resolvedSubmission(2L))
        assertEquals(4, driver.renders.size)
        core.quiesceIngress(remoteKey, 1L)
        core.destroyIngress(remoteKey, 1L)
        core.quiesceIngress(localKey, 2L)
        core.destroyIngress(localKey, 2L)
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
    }

    @Test
    fun outputSurfaceLossMakesBackendTerminalButStillAllowsRetirementCleanup() {
        val driver = FakeDriver().apply { failRenderDestination = cluster }
        val core = UltraEglRenderCore(driver) {}
        core.initialize()
        core.openIngress(remoteKey, 1L, null)
        core.openIngress(localKey, 2L, size)
        core.attachOutput(main, 3L, Any(), size) { true }
        core.attachOutput(cluster, 4L, Any(), size) { true }

        expectFailure<UltraEglSurfaceLostException> {
            core.submit(resolvedSubmission(1L))
        }

        assertEquals(UltraEglBackendPhase.FAILED, core.snapshot().phase)
        expectFailure<IllegalStateException> { core.submit(resolvedSubmission(2L)) }
        core.quiesceIngress(remoteKey, 1L)
        core.destroyIngress(remoteKey, 1L)
        core.quiesceIngress(localKey, 2L)
        core.destroyIngress(localKey, 2L)
        assertTrue(core.closeDriver(Long.MAX_VALUE).retired)
        assertTrue(driver.closed)
    }

    private fun resolvedSubmission(revision: Long) = UltraEglResolvedSubmission(
        scene = renderSnapshot(revision),
        graphRevision = 9L,
        ingressGenerations = mapOf(remoteKey to 1L, localKey to 2L),
        outputGenerations = mapOf(main to 3L, cluster to 4L),
    )

    private fun renderSnapshot(revision: Long) = UltraMultiOutputRenderSnapshot(
        revision,
        listOf(
            UltraOutputScene(
                main,
                size,
                listOf(
                    UltraSceneLayer(
                        UltraLayerId("main-video"),
                        UltraSceneSource.Remote((remoteKey as UltraEglIngressKey.Remote).ingressId),
                        UltraPixelRect(0, 0, 800, 480),
                    ),
                    UltraSceneLayer(
                        UltraLayerId("main-custom-ui"),
                        UltraSceneSource.Local((localKey as UltraEglIngressKey.Local).contentId),
                        UltraPixelRect(600, 0, 200, 100),
                        zIndex = 1,
                    ),
                ),
            ),
            UltraOutputScene(
                cluster,
                size,
                listOf(
                    UltraSceneLayer(
                        UltraLayerId("cluster-video"),
                        UltraSceneSource.Remote((remoteKey as UltraEglIngressKey.Remote).ingressId),
                        UltraPixelRect(100, 100, 600, 280),
                    ),
                ),
            ),
        ),
    )

    private class FakeDriver : UltraEglDriver {
        private data class Ingress(
            val key: UltraEglIngressKey,
            val signal: (UltraEglDriverIngress) -> Unit,
        ) : UltraEglDriverIngress {
            override val producerEndpoint = Any()
            override var hasUsableFrame = false
            override var lastAcquiredTimestampNanos: Long? = null
        }

        private data class Output(
            val destination: DisplayDestination,
            val size: PixelSize,
        ) : UltraEglDriverOutput

        val events = mutableListOf<String>()
        val renders = mutableListOf<UltraEglResolvedScene>()
        val ingressByKey = mutableMapOf<UltraEglIngressKey, Ingress>()
        val createdIngresses = mutableListOf<Pair<UltraEglIngressKey, PixelSize?>>()
        val actualOutputSizes = mutableMapOf<DisplayDestination, PixelSize>()
        val liveOutputs = mutableListOf<UltraEglDriverOutput>()
        var createdOutputCount = 0
        var failCreateAt: Int? = null
        var finishCount = 0
        var updatedIngressCount = 0
        val updatedIngressKeys = mutableListOf<UltraEglIngressKey>()
        val instrumentUploads = mutableListOf<IntArray>()
        val instrumentDrawnFirstPixels = mutableListOf<Int>()
        var failRenderDestination: DisplayDestination? = null
        var failProofSwapDestination: DisplayDestination? = null
        var providePresentationTimestamps = false
        var failNextFinish = false
        var acquiredTimestampForTest: Long? = null
        private var nextPresentationTimestamp = 100L
        val proofSwaps = mutableListOf<DisplayDestination>()
        var closed = false
        val closeProofs = ArrayDeque<Boolean>()
        var closeProofCallCount = 0

        override fun initialize() {
            events += "initialize"
        }

        override fun createExternalIngress(
            key: UltraEglIngressKey,
            sizePixels: PixelSize?,
            onFrameAvailable: (UltraEglDriverIngress) -> Unit,
        ): UltraEglDriverIngress = Ingress(key, onFrameAvailable).also {
            createdIngresses += key to sizePixels
            ingressByKey[key] = it
            events += "createIngress"
        }

        override fun updateIngressFrame(ingress: UltraEglDriverIngress) {
            require(ingress is Ingress)
            ingress.hasUsableFrame = true
            ingress.lastAcquiredTimestampNanos = acquiredTimestampForTest
            updatedIngressCount++
            updatedIngressKeys += ingress.key
            events += "updateIngress"
        }

        override fun createInstrumentIngress(
            destination: DisplayDestination,
            sizePixels: PixelSize,
        ): UltraEglDriverIngress = Ingress(UltraEglIngressKey.Instrument(destination)) {}.also {
            ingressByKey[it.key] = it
            events += "createInstrument"
        }

        override fun uploadInstrumentFrame(ingress: UltraEglDriverIngress, argbPixels: IntArray) {
            require(ingress is Ingress && ingress.key is UltraEglIngressKey.Instrument)
            ingress.hasUsableFrame = true
            instrumentUploads += argbPixels.copyOf()
            events += "uploadInstrument"
        }

        override fun createWindowOutput(
            destination: DisplayDestination,
            nativeWindow: Any,
            sizePixels: PixelSize,
        ): UltraEglDriverOutput {
            createdOutputCount++
            if (createdOutputCount == failCreateAt) {
                throw IllegalStateException("synthetic second output failure")
            }
            return Output(destination, sizePixels).also {
            events += "createOutput"
                liveOutputs += it
            }
        }

        override fun validateOutputSize(output: UltraEglDriverOutput, expectedSize: PixelSize) {
            require(output is Output)
            events += "validate:${output.destination}"
            val actual = actualOutputSizes[output.destination] ?: output.size
            if (actual != expectedSize) {
                throw UltraEglOutputSizeChangedException(output.destination, expectedSize, actual)
            }
        }

        override fun render(output: UltraEglDriverOutput, scene: UltraEglResolvedScene) {
            require(output is Output)
            if (scene.destination == failRenderDestination) {
                throw UltraEglSurfaceLostException(scene.destination, "fake surface lost")
            }
            renders += scene
            if (scene.layers.any { it.layer.source is UltraSceneSource.Instrument }) {
                instrumentDrawnFirstPixels += instrumentUploads.last()[0]
            }
            events += "render"
        }

        override fun renderWithLocalDrawReceipt(
            output: UltraEglDriverOutput,
            scene: UltraEglResolvedScene,
        ): Set<UltraEglLocalLayerDraw> {
            if (scene.destination == failProofSwapDestination) {
                throw UltraEglSurfaceLostException(scene.destination, "synthetic swap failure")
            }
            render(output, scene)
            proofSwaps += scene.destination
            return scene.layers.mapIndexedNotNull { index, resolved ->
                val local = resolved.layer.source as? UltraSceneSource.Local
                    ?: return@mapIndexedNotNull null
                if (resolved.ingress?.hasUsableFrame != true) return@mapIndexedNotNull null
                UltraEglLocalLayerDraw(scene.destination, UltraEglIngressKey.Local(local.contentId),
                    index, resolved.layer)
            }.toSet()
        }

        override fun renderWithPresentationReceipt(
            output: UltraEglDriverOutput,
            scene: UltraEglResolvedScene,
        ): Long? {
            render(output, scene)
            return if (providePresentationTimestamps) ++nextPresentationTimestamp else null
        }

        override fun renderWithLocalDrawPresentationReceipt(
            output: UltraEglDriverOutput,
            scene: UltraEglResolvedScene,
        ): UltraEglDriverRenderedFrame {
            val draws = renderWithLocalDrawReceipt(output, scene)
            return UltraEglDriverRenderedFrame(draws,
                if (providePresentationTimestamps) ++nextPresentationTimestamp else null)
        }

        override fun clear(
            output: UltraEglDriverOutput,
            destination: DisplayDestination,
            sizePixels: PixelSize,
        ) {
            require(output is Output)
            events += "clear"
        }

        override fun finish() {
            if (failNextFinish) {
                failNextFinish = false
                throw UltraEglDriverException("synthetic final GPU finish failure")
            }
            finishCount++
            events += "finish"
        }

        override fun destroyIngress(ingress: UltraEglDriverIngress) {
            require(ingress is Ingress)
            ingressByKey.remove(ingress.key)
            events += "destroyIngress"
        }

        override fun destroyOutput(output: UltraEglDriverOutput) {
            require(output is Output)
            events += "destroyOutput"
            liveOutputs.removeAll { it === output }
        }

        private fun markClosed() {
            closed = true
            events += "close"
        }

        override fun closeWithProof(deadlineNanos: Long): UltraEglDriverRetirementProof {
            closeProofCallCount++
            val retired = closeProofs.removeFirstOrNull() ?: true
            if (retired) markClosed()
            return UltraEglDriverRetirementProof(retired = retired)
        }

        fun signalFrame(key: UltraEglIngressKey) {
            val ingress = checkNotNull(ingressByKey[key])
            ingress.signal(ingress)
        }

        fun deliverFrame(core: UltraEglRenderCore, key: UltraEglIngressKey,
            generation: Long,
        ) {
            core.frameAvailable(UltraEglFrameToken(key, generation,
                checkNotNull(ingressByKey[key])))
        }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (problem: Throwable) {
            if (problem !is T) throw problem
            return problem
        }
        error("unreachable")
    }
}
