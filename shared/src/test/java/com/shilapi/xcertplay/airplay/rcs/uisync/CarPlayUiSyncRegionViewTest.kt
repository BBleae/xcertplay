package com.shilapi.xcertplay.airplay.rcs.uisync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayUiSyncRegionViewTest {
    @Test
    fun knownFieldsAreReadOnlyOrderedAndDoNotExposeOpaqueUnknowns() {
        val inputTransform = List(16) { it.toDouble() }
        val outputTransform = listOf(1.0, 0.0, 0.0, 1.0, 25.0, 50.0)
        val command = decodedTransitionData(
            linkedMapOf(
                "identifier" to "private-layout-id",
                "opacity" to 0.25,
                "cumulative_opacity" to 0.75,
                "transform" to inputTransform,
                "transform2D" to outputTransform,
                "futureMask" to mapOf("unknown" to true),
            ),
            mapOf(
                "identifier" to "second",
                "cumulative_opacity" to 0.6,
            ),
        )

        val views = command.regionDiagnosticViews()
        assertEquals(listOf(0, 1), views.map { it.sourceIndex })
        assertEquals("private-layout-id", available(views[0].identifier))
        assertEquals(0.25, available(views[0].opacity), 0.0)
        assertEquals(CarPlayUiSyncRegionOpacitySource.EXPLICIT, views[0].opacitySource)
        assertEquals(inputTransform, available(views[0].sourceTransform3D))
        assertEquals(outputTransform, available(views[0].transform2D))
        assertEquals(0.6, available(views[1].opacity), 0.0)
        assertEquals(
            CarPlayUiSyncRegionOpacitySource.CUMULATIVE_FALLBACK,
            views[1].opacitySource,
        )
        assertSame(CarPlayUiSyncRegionDiagnosticField.Missing, views[1].sourceTransform3D)
        assertSame(CarPlayUiSyncRegionDiagnosticField.Missing, views[1].transform2D)
        assertFalse(views[0].toString().contains("private-layout-id"))
        assertFalse(views[0].toString().contains("futureMask"))
        assertFalse(views[0].identifier.toString().contains("private-layout-id"))
        assertTrue((command.regions[0].value as Map<*, *>).containsKey("futureMask"))

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (views as MutableList<CarPlayUiSyncRegionDiagnosticView>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (available(views[0].transform2D) as MutableList<Double>).clear()
        }
    }

    @Test
    fun malformedKnownFieldsBecomeTypedDiagnosticsWithoutRejectingTypeTwo() {
        val command = decodedTransitionData(
            mapOf(
                "identifier" to 7,
                "opacity" to "0.5",
                "cumulative_opacity" to 0.9,
                "transform" to List(15) { 0.0 },
                "transform2D" to List(6) { 1 },
            ),
            mapOf(
                "opacity" to null,
                "cumulative_opacity" to 0.7,
                "transform" to List(16) { 1 },
                "transform2D" to List(5) { 0.0 },
            ),
            emptyMap<String, Any?>(),
        )

        val views = command.regionDiagnosticViews()
        assertEquals(3, views.size)
        assertInvalid(views[0].identifier, CarPlayUiSyncRegionDiagnosticIssue.WRONG_TYPE)
        assertInvalid(views[0].opacity, CarPlayUiSyncRegionDiagnosticIssue.WRONG_TYPE)
        assertEquals(CarPlayUiSyncRegionOpacitySource.EXPLICIT, views[0].opacitySource)
        assertInvalid(views[0].sourceTransform3D, CarPlayUiSyncRegionDiagnosticIssue.WRONG_LENGTH)
        assertInvalid(views[0].transform2D, CarPlayUiSyncRegionDiagnosticIssue.WRONG_TYPE)
        assertInvalid(views[1].opacity, CarPlayUiSyncRegionDiagnosticIssue.WRONG_TYPE)
        assertInvalid(views[1].sourceTransform3D, CarPlayUiSyncRegionDiagnosticIssue.WRONG_TYPE)
        assertInvalid(views[1].transform2D, CarPlayUiSyncRegionDiagnosticIssue.WRONG_LENGTH)
        assertSame(CarPlayUiSyncRegionDiagnosticField.Missing, views[2].identifier)
        assertSame(CarPlayUiSyncRegionDiagnosticField.Missing, views[2].opacity)
        assertEquals(null, views[2].opacitySource)
    }

    @Test
    fun nonFiniteWireValuesRemainRejectedButViewIsDefensiveForSyntheticSnapshots() {
        val decoded = CarPlayUiSyncSemanticDecoder().decode(
            CarPlayUiSyncCommandType.TRANSITION_DATA.wireValue,
            mapOf(
                "regions" to listOf(mapOf("opacity" to Double.NaN)),
                "presentation_host_time" to 1L,
                "presentation_ntp_time" to 2L,
                "current_ntp_time" to 3L,
            ),
        )
        assertEquals(
            CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
            (decoded as CarPlayUiSyncSemanticDecodeResult.Rejected).failure,
        )

        // Internal callers may construct a semantic snapshot directly; it still cannot promote
        // non-finite values into a usable diagnostic field.
        val synthetic = CarPlayUiSyncSemanticCommand.TransitionData(
            regions = listOf(
                CarPlayUiSyncSemanticOpaqueValue(
                    mapOf(
                        "opacity" to Double.POSITIVE_INFINITY,
                        "transform" to List(16) { index ->
                            if (index == 7) Double.NaN else index.toDouble()
                        },
                        "transform2D" to List(6) { index ->
                            if (index == 5) Double.NEGATIVE_INFINITY else index.toDouble()
                        },
                    ),
                ),
            ),
            presentationHostTimeBits = 1L,
            presentationNtpTimeBits = 2L,
            presentationNtpTimeOriginalBits = null,
            currentNtpTimeBits = 3L,
        )
        val view = synthetic.regionDiagnosticViews().single()
        assertInvalid(view.opacity, CarPlayUiSyncRegionDiagnosticIssue.NON_FINITE)
        assertInvalid(view.sourceTransform3D, CarPlayUiSyncRegionDiagnosticIssue.NON_FINITE)
        assertInvalid(view.transform2D, CarPlayUiSyncRegionDiagnosticIssue.NON_FINITE)
    }

    private fun decodedTransitionData(vararg regions: Map<String, Any?>):
        CarPlayUiSyncSemanticCommand.TransitionData {
        val result = CarPlayUiSyncSemanticDecoder().decode(
            CarPlayUiSyncCommandType.TRANSITION_DATA.wireValue,
            mapOf(
                "regions" to regions.toList(),
                "presentation_host_time" to 1L,
                "presentation_ntp_time" to 2L,
                "current_ntp_time" to 3L,
            ),
        )
        assertTrue(result is CarPlayUiSyncSemanticDecodeResult.Decoded)
        return (result as CarPlayUiSyncSemanticDecodeResult.Decoded).command
            as CarPlayUiSyncSemanticCommand.TransitionData
    }

    private fun <T> available(field: CarPlayUiSyncRegionDiagnosticField<T>): T =
        (field as CarPlayUiSyncRegionDiagnosticField.Available<T>).value

    private fun assertInvalid(
        field: CarPlayUiSyncRegionDiagnosticField<*>,
        expectedReason: CarPlayUiSyncRegionDiagnosticIssue,
    ) {
        assertEquals(expectedReason, (field as CarPlayUiSyncRegionDiagnosticField.Invalid).reason)
    }
}
