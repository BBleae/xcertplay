package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class UltraMultiOutputSceneTest {
    @Test
    fun oneIngressCanFanOutAndOneOutputCanComposeSeveralIngresses() {
        val firstIngress = ingress("navigation", 111)
        val secondIngress = ingress("camera", 110)
        val clusterOutput = output("cluster")
        val passengerOutput = output("passenger")
        val graph = UltraDisplayGraphRegistry().replace(
            listOf(clusterOutput, passengerOutput),
            listOf(firstIngress, secondIngress),
        )
        val cluster = scene(
            DisplayDestination.CLUSTER,
            remoteLayer("nav-cluster", firstIngress.id, z = 0),
            remoteLayer("camera-cluster", secondIngress.id, z = 1),
        )
        val passenger = scene(
            DisplayDestination(DisplayDestinationRole.AUXILIARY, DisplayDestinationId("passenger")),
            remoteLayer("nav-passenger", firstIngress.id, z = 0),
        )
        val snapshot = UltraMultiOutputRenderSnapshot(1L, listOf(cluster, passenger))

        val plan = snapshot.resolveAgainst(
            graph,
            mapOf(
                cluster.destination to clusterOutput.outputId,
                passenger.destination to passengerOutput.outputId,
            ),
        )

        assertSame(cluster, snapshot.scene(DisplayDestination.CLUSTER))
        assertSame(clusterOutput, plan.outputByDestination[cluster.destination])
        assertSame(
            firstIngress,
            plan.scene(passenger.destination)?.layers?.single()?.remoteIngress,
        )
        assertEquals(2, cluster.layers.size)
        assertEquals(firstIngress.id, (passenger.layers.single().source as UltraSceneSource.Remote).ingressId)
    }

    @Test
    fun outputScenesHaveIndependentPixelCoordinatesAndStableZOrder() {
        val low = UltraSceneLayer(
            UltraLayerId("low"),
            UltraSceneSource.PunchThrough,
            UltraPixelRect(0, 0, 800, 480),
            zIndex = -1,
        )
        val high = UltraSceneLayer(
            UltraLayerId("high"),
            UltraSceneSource.Local(UltraContentId("gauge")),
            UltraPixelRect(100, 100, 200, 200),
            zIndex = 3,
        )
        val main = UltraOutputScene(DisplayDestination.MAIN, PixelSize(1200, 2608), listOf(high, low))
        val cluster = UltraOutputScene(DisplayDestination.CLUSTER, PixelSize(1920, 720), listOf(high))
        val snapshot = UltraMultiOutputRenderSnapshot(2L, listOf(main, cluster))

        assertEquals(listOf("low", "high"), main.layers.map { it.id.value })
        assertEquals(PixelSize(1200, 2608), snapshot.scene(DisplayDestination.MAIN)?.viewportPixels)
        assertEquals(PixelSize(1920, 720), snapshot.scene(DisplayDestination.CLUSTER)?.viewportPixels)
    }

    @Test
    fun affineLayerValidationKeepsSafetyAndRectangularClearPathsFixed() {
        val translation = UltraPixelAffineTransform(translateXPixels = 8.0)
        assertEquals(UltraPixelAffineTransform.IDENTITY,
            remoteLayer("default", UltraIngressId("remote"), 0).transformPixels)
        assertThrows(IllegalArgumentException::class.java) {
            UltraPixelAffineTransform(xx = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraPixelAffineTransform(translateXPixels = Double.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraPixelAffineTransform(translateXPixels = 16_777_217.0)
        }
        for (source in listOf(
            UltraSceneSource.Instrument(DisplayDestination.CLUSTER),
            UltraSceneSource.Overlay(UltraContentId("safety-overlay")),
            UltraSceneSource.PunchThrough,
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                UltraSceneLayer(UltraLayerId("restricted"), source,
                    UltraPixelRect(0, 0, 10, 10), transformPixels = translation)
            }
        }
    }

    @Test
    fun missingTypedResourceFailsWithoutStringGuessing() {
        val graph = UltraDisplayGraphRegistry().replace(
            listOf(output("cluster")),
            listOf(ingress("known", 111)),
        )
        val scene = scene(DisplayDestination.CLUSTER, remoteLayer("unknown", UltraIngressId("unknown"), 0))
        val snapshot = UltraMultiOutputRenderSnapshot(1L, listOf(scene))

        assertThrows(IllegalArgumentException::class.java) {
            snapshot.resolveAgainst(
                graph,
                mapOf(scene.destination to UltraDisplayOutputId.Custom("output-cluster")),
            )
        }
    }

    @Test
    fun resolvedPlanKeepsExactSessionResourcesAcrossGraphIdReuseAndMapMutation() {
        val registry = UltraDisplayGraphRegistry()
        val oldOutput = output("cluster")
        val oldIngress = ingress("navigation", 111)
        val graph = registry.replace(listOf(oldOutput), listOf(oldIngress))
        val cluster = scene(
            DisplayDestination.CLUSTER,
            remoteLayer("navigation", oldIngress.id, 0),
        )
        val snapshot = UltraMultiOutputRenderSnapshot(7L, listOf(cluster))
        val mapping = linkedMapOf(cluster.destination to oldOutput.outputId)

        val plan = snapshot.resolveAgainst(graph, mapping)
        mapping.clear()
        val newOutput = output("cluster")
        val newIngress = ingress("navigation", 111)
        val replacement = registry.replace(listOf(newOutput), listOf(newIngress))

        assertEquals(1L, plan.graphRevision)
        assertEquals(7L, plan.renderRevision)
        assertEquals(1, plan.outputByDestination.size)
        assertSame(oldOutput, plan.outputByDestination[cluster.destination])
        val resolvedIngress = plan.scene(cluster.destination)?.layers?.single()?.remoteIngress
        assertSame(oldIngress, resolvedIngress)
        assertSame(oldIngress.stream, resolvedIngress?.stream)
        assertNotSame(replacement.output(oldOutput.outputId), plan.outputByDestination[cluster.destination])
        assertNotSame(replacement.ingress(oldIngress.id)?.stream, resolvedIngress?.stream)
    }

    @Test
    fun destinationMappingIsBoundedBeforeResourceResolution() {
        val scene = scene(DisplayDestination.CLUSTER)
        val snapshot = UltraMultiOutputRenderSnapshot(1L, listOf(scene))
        val oversized = (0..64).associate { index ->
            DisplayDestination(
                DisplayDestinationRole.AUXILIARY,
                DisplayDestinationId("aux-$index"),
            ) to UltraDisplayOutputId.Custom("output-$index")
        }

        assertThrows(IllegalArgumentException::class.java) {
            snapshot.resolveAgainst(UltraDisplayGraphRegistry().snapshot(), oversized)
        }
    }

    private fun scene(destination: DisplayDestination, vararg layers: UltraSceneLayer) =
        UltraOutputScene(destination, PixelSize(1920, 720), layers.toList())

    private fun remoteLayer(id: String, ingress: UltraIngressId, z: Int) = UltraSceneLayer(
        UltraLayerId(id),
        UltraSceneSource.Remote(ingress),
        UltraPixelRect(0, 0, 1920, 720),
        zIndex = z,
    )

    private fun ingress(id: String, type: Int) = UltraDisplayGraphIngress(
        UltraIngressId(id),
        syntheticHandle(AirPlayStreamIdentity(type, id, null)),
    )

    /** The opaque session epoch is intentionally unavailable outside shared; tests use reflection. */
    private fun syntheticHandle(identity: AirPlayStreamIdentity): AirPlayVideoStreamHandle {
        val epochClass = Class.forName("com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch")
        val epoch = epochClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val constructor = AirPlayVideoStreamHandle::class.java.declaredConstructors
            .single { it.parameterCount == 2 }
            .apply { isAccessible = true }
        return constructor.newInstance(identity, epoch) as AirPlayVideoStreamHandle
    }

    private fun output(id: String) = UltraDisplayGraphOutput(
        UltraTopologyDisplayId(UUID.nameUUIDFromBytes(id.toByteArray())),
        UltraDisplayOutputId.Custom("output-$id"),
    )
}
