package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.ultra.egl.eglClearColorFor
import com.shilapi.xcertplay.ultra.egl.UltraEglBoundOutput
import com.shilapi.xcertplay.ultra.egl.resolveUltraEglPlan
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramInstruction
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneLayoutAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraThemeBackgroundSelectionTest {
    @Test
    fun exactLayoutAndExplicitViewportSelectionPreservePerOutputIdentityAndRevision() {
        val mainCompilation = compiled(listOf("#009900", "#ff0000"), "centerConsole")
        val clusterCompilation = compiled(listOf("#0000ff"), "cluster")
        val mainProgram = mainCompilation.structuralProgram
        val addresses = mainProgram.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>()
            .map { it.address }
        assertEquals(listOf(0, 1), addresses.map { it.layoutIndex })
        assertEquals(listOf("duplicate", "duplicate"), addresses.map { it.layoutId })
        val clusterProgram = clusterCompilation.structuralProgram
        val clusterAddress = clusterProgram.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>()
            .single().address
        val mainSize = PixelSize(800, 480)
        val clusterSize = PixelSize(1920, 720)
        val mainOutput = graphOutput("00000000-0000-0000-0000-000000000001", "main")
        val clusterOutput = graphOutput("00000000-0000-0000-0000-000000000002", "cluster")
        val graph = UltraDisplayGraphRegistry().replace(listOf(mainOutput, clusterOutput), emptyList())
        val mainBackground = selectUltraOpaqueThemeBackground(
            mainProgram, addresses[1], DisplayDestination.MAIN, graph, mainOutput.outputId,
            mainSize, fullViewportMappingConfirmed = true,
        )
        val clusterBackground = selectUltraOpaqueThemeBackground(
            clusterProgram, clusterAddress, DisplayDestination.CLUSTER, graph,
            clusterOutput.outputId, clusterSize, fullViewportMappingConfirmed = true,
        )
        val main = UltraOutputScene(DisplayDestination.MAIN, mainSize, emptyList(), mainBackground)
        val cluster = UltraOutputScene(
            DisplayDestination.CLUSTER, clusterSize, emptyList(), clusterBackground,
        )
        val snapshot = UltraMultiOutputRenderSnapshot(41L, listOf(main, cluster))
        val plan = snapshot.resolveAgainst(graph, mapOf(
            DisplayDestination.MAIN to mainOutput.outputId,
            DisplayDestination.CLUSTER to clusterOutput.outputId,
        ))

        assertEquals(41L, plan.renderRevision)
        assertEquals(graph.revision, plan.graphRevision)
        assertSame(main, plan.scene(DisplayDestination.MAIN)?.scene)
        assertSame(cluster, plan.scene(DisplayDestination.CLUSTER)?.scene)
        assertSame(mainOutput, plan.outputByDestination[DisplayDestination.MAIN])
        assertSame(clusterOutput, plan.outputByDestination[DisplayDestination.CLUSTER])
        assertSame(mainBackground, plan.scene(DisplayDestination.MAIN)?.scene?.background)
        assertSame(clusterBackground, plan.scene(DisplayDestination.CLUSTER)?.scene?.background)
        val eglSubmission = resolveUltraEglPlan(plan, mapOf(
            DisplayDestination.MAIN to UltraEglBoundOutput(
                generation = 7L, graphOutput = mainOutput, sizePixels = mainSize, isValid = true,
            ),
            DisplayDestination.CLUSTER to UltraEglBoundOutput(
                generation = 8L, graphOutput = clusterOutput, sizePixels = clusterSize,
                isValid = true,
            ),
        ), emptyMap())
        assertEquals(41L, eglSubmission.revision)
        assertEquals(graph.revision, eglSubmission.graphRevision)
        assertEquals(mapOf(DisplayDestination.MAIN to 7L, DisplayDestination.CLUSTER to 8L),
            eglSubmission.outputGenerations)
        assertSame(mainBackground, eglSubmission.scene.scene(DisplayDestination.MAIN)?.background)
        assertSame(clusterBackground, eglSubmission.scene.scene(DisplayDestination.CLUSTER)?.background)
        assertEquals(1f, eglClearColorFor(mainBackground).red)
        assertEquals(0f, eglClearColorFor(mainBackground).blue)
        assertEquals(0f, eglClearColorFor(clusterBackground).red)
        assertEquals(1f, eglClearColorFor(clusterBackground).blue)
        assertEquals(1f, eglClearColorFor(clusterBackground).alpha)
        // Asset density is metadata, not a multiplier applied to the chosen physical viewport.
        assertEquals(3.0, mainProgram.assetDensity, 0.0)
        assertEquals(mainSize, mainBackground.viewportPixels)
        assertTrue(!mainCompilation.rendererReady)
        assertTrue(!clusterCompilation.rendererReady)
    }

    @Test
    fun defaultRemainsTransparentAndNoSceneClearDoesNotGainThemeColor() {
        val scene = UltraOutputScene(DisplayDestination.MAIN, PixelSize(800, 480), emptyList())
        assertSame(UltraSceneBackground.Transparent, scene.background)
        val clear = eglClearColorFor(scene.background)
        assertEquals(0f, clear.red)
        assertEquals(0f, clear.green)
        assertEquals(0f, clear.blue)
        assertEquals(0f, clear.alpha)
    }

    @Test
    fun rejectsMissingAmbiguousOrTranslucentColorAndUnconfirmedViewport() {
        val size = PixelSize(800, 480)
        val output = graphOutput("00000000-0000-0000-0000-000000000003", "main")
        val graph = UltraDisplayGraphRegistry().replace(listOf(output), emptyList())
        val opaque = compiled(listOf("#123456")).structuralProgram
        val address = opaque.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>()
            .single().address
        assertThrows(IllegalArgumentException::class.java) {
            selectUltraOpaqueThemeBackground(opaque, address, DisplayDestination.MAIN, graph,
                output.outputId, size, false)
        }
        val clusterProgram = compiled(listOf("#123456"), "cluster").structuralProgram
        val clusterAddress = clusterProgram.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>()
            .single().address
        assertThrows(IllegalArgumentException::class.java) {
            selectUltraOpaqueThemeBackground(clusterProgram, clusterAddress,
                DisplayDestination.MAIN, graph, output.outputId, size, true)
        }
        val passengerProgram = compiled(listOf("#123456"), "passenger").structuralProgram
        val passengerAddress = passengerProgram.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>()
            .single().address
        assertThrows(IllegalArgumentException::class.java) {
            selectUltraOpaqueThemeBackground(passengerProgram, passengerAddress,
                DisplayDestination(DisplayDestinationRole.AUXILIARY), graph, output.outputId,
                size, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            selectUltraOpaqueThemeBackground(
                opaque, CarPlayThemeSceneLayoutAddress(9, address.layoutId),
                DisplayDestination.MAIN, graph, output.outputId, size, true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraOutputScene(DisplayDestination.MAIN, PixelSize(801, 480), emptyList(),
                selectUltraOpaqueThemeBackground(opaque, address, DisplayDestination.MAIN, graph,
                    output.outputId, size, true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraOutputScene(DisplayDestination.CLUSTER, size, emptyList(),
                selectUltraOpaqueThemeBackground(opaque, address, DisplayDestination.MAIN, graph,
                    output.outputId, size, true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PixelSize(0, 480)
        }

        val translucent = compiled(listOf("#12345680")).structuralProgram
        val translucentAddress = translucent.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>()
            .single().address
        assertThrows(IllegalArgumentException::class.java) {
            selectUltraOpaqueThemeBackground(translucent, translucentAddress,
                DisplayDestination.MAIN, graph, output.outputId, size, true)
        }
        val unrecognized = compiled(listOf(mapOf("unresolved" to true))).structuralProgram
        assertThrows(IllegalArgumentException::class.java) {
            selectUltraOpaqueThemeBackground(unrecognized, address, DisplayDestination.MAIN,
                graph, output.outputId, size, true)
        }
    }

    @Test
    fun selectedBackgroundRejectsDifferentOutputObjectAndGraphRevision() {
        val program = compiled(listOf("#123456")).structuralProgram
        val address = program.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>()
            .single().address
        val output = graphOutput("00000000-0000-0000-0000-000000000004", "main")
        val registry = UltraDisplayGraphRegistry()
        val graph1 = registry.replace(listOf(output), emptyList())
        val size = PixelSize(800, 480)
        val background = selectUltraOpaqueThemeBackground(
            program, address, DisplayDestination.MAIN, graph1, output.outputId, size, true,
        )
        val snapshot = UltraMultiOutputRenderSnapshot(42L, listOf(
            UltraOutputScene(DisplayDestination.MAIN, size, emptyList(), background),
        ))
        val differentObject = output.copy()
        val graph2 = UltraDisplayGraphRegistry().replace(listOf(differentObject), emptyList())
        assertEquals(graph1.revision, graph2.revision)
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.resolveAgainst(graph2, mapOf(DisplayDestination.MAIN to output.outputId))
        }
        val sameObjectOtherSnapshot = UltraDisplayGraphRegistry().replace(listOf(output), emptyList())
        assertEquals(graph1.revision, sameObjectOtherSnapshot.revision)
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.resolveAgainst(sameObjectOtherSnapshot,
                mapOf(DisplayDestination.MAIN to output.outputId))
        }
        val graph3 = registry.replace(listOf(output), emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.resolveAgainst(graph3, mapOf(DisplayDestination.MAIN to output.outputId))
        }
        assertThrows(IllegalArgumentException::class.java) {
            selectUltraOpaqueThemeBackground(program, address, DisplayDestination.MAIN, graph1,
                UltraDisplayOutputId.Android("missing"), size, true)
        }
    }

    private fun compiled(backgrounds: List<Any?>, displayType: String = "centerConsole") =
        CarPlayThemeProgramCompiler().compile(
        CarPlayThemeSchema.parseDisplay(mapOf(
            "id" to "$displayType-display",
            "type" to displayType,
            "size" to listOf(400, 200),
            "density" to 3,
            "allowManufacturerAppIcon" to false,
            "requiresDarkAppearance" to false,
            "layouts" to backgrounds.map { background ->
                mapOf(
                    "id" to "duplicate",
                    "zones" to listOf(mapOf(
                        "id" to "zone",
                        "frame" to listOf(listOf(0, 0), listOf(400, 200)),
                        "components" to emptyList<Any>(),
                        "type" to mapOf("single" to emptyMap<String, Any?>()),
                    )),
                    "layoutType" to "primary",
                    "backgroundColor" to background,
                    "safeAreaInsets" to listOf(0, 0, 0, 0),
                    "driveModeThemeConfigurations" to emptyMap<String, Any?>(),
                )
            },
        )),
    )

    private fun graphOutput(uuid: String, outputId: String) = UltraDisplayGraphOutput(
        UltraTopologyDisplayId(UUID.fromString(uuid)),
        UltraDisplayOutputId.Android(outputId),
    )
}
