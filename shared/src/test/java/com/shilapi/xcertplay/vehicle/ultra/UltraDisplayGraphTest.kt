package com.shilapi.xcertplay.vehicle.ultra

import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class UltraDisplayGraphTest {
    @Test
    fun independentResourcesAllowLocalOnlyOutputAndMultiOutputIngressUse() {
        val first = output("cluster", withUiSync = true)
        val localOnly = output("hud", withUiSync = false)
        val ingress = ingress("navigation", handle(type = 111))
        val registry = UltraDisplayGraphRegistry()

        val snapshot = registry.replace(listOf(first, localOnly), listOf(ingress))

        assertEquals(1L, snapshot.revision)
        assertSame(first, snapshot.output(first.outputId))
        assertSame(first, snapshot.output(checkNotNull(first.uiSyncDisplayId)))
        assertSame(localOnly, snapshot.output(localOnly.topologyDisplayId))
        assertNull(localOnly.uiSyncDisplayId)
        assertSame(ingress, snapshot.ingress(ingress.id))
        // The graph intentionally has no one-to-one output↔ingress assignment.
        assertEquals(2, snapshot.outputs.size)
        assertEquals(1, snapshot.ingresses.size)
    }

    @Test
    fun protocolIdentityReuseAcrossSessionsRemainsTwoDistinctIngresses() {
        val identity = AirPlayStreamIdentity(111, "7", null)
        val firstHandle = AirPlayVideoStreamHandle(identity, AirPlayVideoSessionEpoch())
        val secondHandle = AirPlayVideoStreamHandle(identity, AirPlayVideoSessionEpoch())
        assertNotSame(firstHandle, secondHandle)

        val snapshot = UltraDisplayGraphRegistry().replace(
            listOf(output("cluster"), output("secondary")),
            listOf(
                ingress("old-session", firstHandle),
                ingress("new-session", secondHandle),
            ),
        )

        assertSame(firstHandle, snapshot.ingress(UltraIngressId("old-session"))?.stream)
        assertSame(secondHandle, snapshot.ingress(UltraIngressId("new-session"))?.stream)
    }

    @Test
    fun duplicateNamespacesOrHandleAliasesFailBeforePublishing() {
        val registry = UltraDisplayGraphRegistry()
        val first = output("cluster")
        val handle = handle(111)

        assertThrows(IllegalArgumentException::class.java) {
            registry.replace(listOf(first, first.copy()), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.replace(
                listOf(first),
                listOf(ingress("one", handle), ingress("two", handle)),
            )
        }
        assertEquals(0L, registry.snapshot().revision)
    }

    @Test
    fun boundedCopyDoesNotTrustCollectionSize() {
        var reads = 0
        val endless = object : AbstractCollection<UltraDisplayGraphIngress>() {
            override val size: Int get() = 0
            override fun iterator(): Iterator<UltraDisplayGraphIngress> = object : Iterator<UltraDisplayGraphIngress> {
                override fun hasNext() = true
                override fun next(): UltraDisplayGraphIngress =
                    ingress("ingress-${reads++}", handle(111))
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            UltraDisplayGraphRegistry().replace(emptyList(), endless)
        }
        assertEquals(129, reads)
    }

    @Test
    fun concurrentCompleteReplacementsPublishWholeSnapshots() {
        val registry = UltraDisplayGraphRegistry()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (0..1).map { index ->
                executor.submit<UltraDisplayGraphSnapshot> {
                    start.await()
                    registry.replace(
                        listOf(output("display-$index")),
                        listOf(ingress("ingress-$index", handle(110 + index))),
                    )
                }
            }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val final = registry.snapshot()
        assertEquals(2L, final.revision)
        assertEquals(1, final.outputs.size)
        assertEquals(1, final.ingresses.size)
        assertEquals(
            final.outputs.single().outputId.value.substringAfter("display-"),
            final.ingresses.single().id.value.substringAfter("ingress-"),
        )
    }

    private fun output(id: String, withUiSync: Boolean = true) = UltraDisplayGraphOutput(
        topologyDisplayId = UltraTopologyDisplayId(UUID.nameUUIDFromBytes(id.toByteArray())),
        outputId = UltraDisplayOutputId.Custom("output-$id"),
        uiSyncDisplayId = UltraUiSyncDisplayId("ui-$id").takeIf { withUiSync },
    )

    private fun ingress(id: String, handle: AirPlayVideoStreamHandle) =
        UltraDisplayGraphIngress(UltraIngressId(id), handle)

    private fun handle(type: Int) = AirPlayVideoStreamHandle(
        AirPlayStreamIdentity(type, null, null),
        AirPlayVideoSessionEpoch(),
    )
}
