package com.shilapi.xcertplay.vehicle.ultra.theme

import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CarPlayThemeGeometryTest {
    @Test
    fun decodesConfirmedCoreGraphicsJsonShapesWithoutDensityScaling() {
        assertEquals(
            CarPlayLogicalPoint(1.25, -2.5),
            CarPlayThemeGeometry.point(opaque(listOf(1.25, -2.5))),
        )
        assertEquals(
            CarPlayLogicalSize(1920.0, 720.0),
            CarPlayThemeGeometry.size(opaque(listOf(1920, 720))),
        )
        assertEquals(
            CarPlayLogicalRect(
                CarPlayLogicalPoint(10.0, 20.0),
                CarPlayLogicalSize(300.0, 400.0),
            ),
            CarPlayThemeGeometry.rect(opaque(listOf(listOf(10, 20), listOf(300, 400)))),
        )
    }

    @Test
    fun decodesCarPlayAssetUiEdgeInsetsInSwiftRegisterOrder() {
        assertEquals(
            CarPlayLogicalEdgeInsets(1.0, 2.0, 3.0, 4.0),
            CarPlayThemeGeometry.edgeInsets(opaque(listOf(1, 2, 3, 4))),
        )
    }

    @Test
    fun acceptsBoundedArbitraryPrecisionNumbersFromStrictJsonDecoder() {
        assertEquals(
            CarPlayLogicalPoint(42.0, 0.125),
            CarPlayThemeGeometry.point(
                opaque(listOf(BigInteger("42"), BigDecimal("0.125"))),
            ),
        )
    }

    @Test
    fun rejectsWrongShapesAndNonFiniteNumbers() {
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeGeometry.point(opaque(mapOf("x" to 1, "y" to 2)))
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeGeometry.rect(opaque(listOf(1, 2, 3, 4)))
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeGeometry.size(opaque(listOf(Double.POSITIVE_INFINITY, 1)))
        }
    }

    @Test
    fun geometryDoesNotInventPositiveOrPixelConstraints() {
        assertEquals(
            CarPlayLogicalRect(
                CarPlayLogicalPoint(-100.0, -50.0),
                CarPlayLogicalSize(-1.0, 0.0),
            ),
            CarPlayThemeGeometry.rect(opaque(listOf(listOf(-100, -50), listOf(-1, 0)))),
        )
    }

    @Test
    fun diagnosticSceneReferenceOffersTypedLogicalViewsWithoutChangingStoredValue() {
        val source = opaque(listOf(listOf(1, 2), listOf(3, 4)))
        val reference = CarPlayThemeSceneLogicalGeometryReference(source)

        assertEquals(
            CarPlayLogicalRect(CarPlayLogicalPoint(1.0, 2.0), CarPlayLogicalSize(3.0, 4.0)),
            reference.asRect("Slot.frame"),
        )
        assertEquals(source.value, reference.value)
    }

    private fun opaque(value: Any?): CarPlayThemeOpaqueValue = CarPlayThemeOpaqueValue(value)
}
