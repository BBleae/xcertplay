package com.shilapi.xcertplay.ultra.egl

import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraMultiOutputRenderSnapshot
import com.shilapi.xcertplay.ultra.UltraOutputScene
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class UltraEglPlanResolverTest {
    @Test
    fun graphRevisionReplacementWithStructurallyEqualOutputRequiresExplicitRebind() {
        val registry = UltraDisplayGraphRegistry()
        val output1 = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            UltraDisplayOutputId.Android("main-output"),
        )
        val graph1 = registry.replace(listOf(output1), emptyList())
        val render1 = render(1L)
        val plan1 = render1.resolveAgainst(
            graph1,
            mapOf(DisplayDestination.MAIN to output1.outputId),
        )
        val bindings = mapOf(
            DisplayDestination.MAIN to UltraEglBoundOutput(
                generation = 7L,
                graphOutput = output1,
                sizePixels = PixelSize(800, 480),
                isValid = true,
            ),
        )

        val resolved1 = resolveUltraEglPlan(plan1, bindings, emptyMap())
        assertEquals(graph1.revision, resolved1.graphRevision)

        val output2 = output1.copy()
        val graph2 = registry.replace(listOf(output2), emptyList())
        val plan2 = render(2L).resolveAgainst(
            graph2,
            mapOf(DisplayDestination.MAIN to output2.outputId),
        )

        expectFailure<IllegalArgumentException> {
            resolveUltraEglPlan(plan2, bindings, emptyMap())
        }
    }

    private fun render(revision: Long) = UltraMultiOutputRenderSnapshot(
        revision,
        listOf(
            UltraOutputScene(
                DisplayDestination.MAIN,
                PixelSize(800, 480),
                emptyList(),
            ),
        ),
    )

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
