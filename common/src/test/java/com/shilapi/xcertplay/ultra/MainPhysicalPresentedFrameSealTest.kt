package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.ultra.egl.UltraEglIngressKey
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalFirstFrameReceipt
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalLayerDraw
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainPhysicalPresentedFrameSealTest {
    private val destination = DisplayDestination.MAIN

    private fun result(
        revision: Long = 17L,
        graphRevision: Long = 4L,
        batchGeneration: Long = 9L,
        outputGeneration: Long = 11L,
        timestampNanos: Long = 71L,
        disposition: UltraEglRenderDisposition = UltraEglRenderDisposition.PRESENTED,
    ) = UltraEglRenderResult(
        revision, disposition,
        producerTimestampNanosByDestination = mapOf(destination to timestampNanos),
        outputGenerationsByDestination = mapOf(destination to outputGeneration),
        outputBatchGeneration = batchGeneration,
        graphRevision = graphRevision,
    )

    private fun seal(
        result: UltraEglRenderResult,
        draws: Set<UltraEglLocalLayerDraw> = emptySet(),
        local: UltraEglLocalFirstFrameReceipt? = null,
    ) = mainPhysicalPresentedFrameSealOrNull(
        destination, 17L, 4L, 9L, result, draws, local,
    )

    @Test fun mainNeedsPresentedExactRevisionBatchOutputAndProducerTimestamp() {
        assertEquals(MainPhysicalPresentedFrameSeal(17L, 71L, 9L, 11L), seal(result()))
        assertNull(seal(result(revision = 16L)))
        assertNull(seal(result(graphRevision = 3L)))
        assertNull(seal(result(batchGeneration = 8L)))
        assertNull(seal(result(outputGeneration = 0L)))
        assertNull(seal(result(timestampNanos = 0L)))
        assertNull(seal(result(disposition = UltraEglRenderDisposition.COALESCED)))
    }

    @Test fun localMainUsesOnlyMatchingPostDrawAndLaterSealedSwap() {
        val content = UltraContentId("main-local")
        val key = UltraEglIngressKey.Local(content)
        val layer = UltraSceneLayer(
            UltraLayerId("main-layer"), UltraSceneSource.Local(content),
            UltraPixelRect(0, 0, 20, 20),
        )
        val draw = UltraEglLocalLayerDraw(destination, key, 0, layer)
        fun receipt(
            revision: Long = 17L,
            physicalBatchGeneration: Long = 9L,
            outputBatchGeneration: Long = 9L,
            outputGeneration: Long = 11L,
            timestampNanos: Long = 73L,
            requiredDraws: Set<UltraEglLocalLayerDraw> = setOf(draw),
        ) = UltraEglLocalFirstFrameReceipt(
            revision, 4L, physicalBatchGeneration, outputBatchGeneration,
            requiredDraws, emptyMap(), emptyMap(), emptyMap(), emptyMap(),
            producerTimestampNanosByDestination = mapOf(destination to timestampNanos),
            outputGenerationsByDestination = mapOf(destination to outputGeneration),
        )
        assertNull(seal(result(), setOf(draw)))
        assertEquals(MainPhysicalPresentedFrameSeal(17L, 73L, 9L, 11L),
            seal(result(), setOf(draw), receipt()))
        assertNull(seal(result(), setOf(draw), receipt(revision = 16L)))
        assertNull(seal(result(), setOf(draw), receipt(physicalBatchGeneration = 8L)))
        assertNull(seal(result(), setOf(draw), receipt(outputBatchGeneration = 8L)))
        assertNull(seal(result(), setOf(draw), receipt(outputGeneration = 10L)))
        assertNull(seal(result(), setOf(draw), receipt(timestampNanos = 0L)))
        assertNull(seal(result(), setOf(draw), receipt(requiredDraws = emptySet())))
    }
}
