package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalPoint
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalRect
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalSize
import com.shilapi.xcertplay.vehicle.ultra.theme.InputStreamTransformKind
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayLogicalPixelMapperTest {
    @Test
    fun appliesExplicitOtherOverSelfFactorsPerAxisWithoutDensity() {
        val mapper = mapper(
            affine = AxisAlignedPixelAffine(3.0, 4.0, 0.0, 0.0),
            frame = PixelEdgeQuantization.REQUIRE_INTEGRAL,
        )

        assertEquals(
            UltraPixelRect(30, 20, 60, 40),
            mapper.mapFrame(logicalRect(10.0, 5.0, 20.0, 10.0)),
        )
    }

    @Test
    fun eachOutputUsesItsOwnExplicitAffine() {
        val logical = logicalRect(10.0, 20.0, 30.0, 40.0)
        val first = mapper(AxisAlignedPixelAffine(2.0, 2.0, 0.0, 0.0))
        val second = mapper(AxisAlignedPixelAffine(3.0, 4.0, 100.0, -10.0))

        assertEquals(UltraPixelRect(20, 40, 60, 80), first.mapFrame(logical))
        assertEquals(UltraPixelRect(130, 70, 90, 160), second.mapFrame(logical))
    }

    @Test
    fun preservesNegativeOriginAndDoesNotClampFrameToAViewport() {
        val mapper = mapper(AxisAlignedPixelAffine(2.0, 3.0, -5.0, 7.0))

        assertEquals(
            UltraPixelRect(-25, -53, 4000, 6000),
            mapper.mapFrame(logicalRect(-10.0, -20.0, 2_000.0, 2_000.0)),
        )
    }

    @Test
    fun negativeExtentsRequireAnExplicitPolicy() {
        val logical = logicalRect(10.0, 20.0, -4.0, -6.0)
        val rejecting = mapper(
            AxisAlignedPixelAffine(2.0, 3.0, 1.0, -1.0),
            extent = NegativeExtentPolicy.REJECT,
        )
        val standardizing = mapper(
            AxisAlignedPixelAffine(2.0, 3.0, 1.0, -1.0),
            extent = NegativeExtentPolicy.STANDARDIZE,
        )

        assertThrows(IllegalArgumentException::class.java) { rejecting.mapFrame(logical) }
        assertEquals(UltraPixelRect(13, 41, 8, 18), standardizing.mapFrame(logical))
    }

    @Test
    fun affineRejectsNonFiniteOrNonPositiveValues() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                AxisAlignedPixelAffine(invalid, 1.0, 0.0, 0.0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AxisAlignedPixelAffine(1.0, invalid, 0.0, 0.0)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AxisAlignedPixelAffine(1.0, 1.0, Double.NaN, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AxisAlignedPixelAffine(1.0, 1.0, 0.0, Double.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun rejectsNonFiniteLogicalMathAndIntegerOverflow() {
        val mapper = mapper(AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0))
        assertThrows(IllegalArgumentException::class.java) {
            mapper.mapSubpixel(logicalRect(Double.NaN, 0.0, 1.0, 1.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapper.mapSubpixel(logicalRect(Double.MAX_VALUE, 0.0, Double.MAX_VALUE, 1.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapper.mapFrame(logicalRect(3_000_000_000.0, 0.0, 1.0, 1.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            mapper.mapFrame(
                logicalRect(
                    Int.MIN_VALUE.toDouble(),
                    0.0,
                    Int.MAX_VALUE.toDouble() - Int.MIN_VALUE.toDouble(),
                    1.0,
                ),
            )
        }
    }

    @Test
    fun requireIntegralRejectsFractionalEdges() {
        val mapper = mapper(
            AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
            frame = PixelEdgeQuantization.REQUIRE_INTEGRAL,
        )

        assertEquals(
            UltraPixelRect(-2, 3, 7, 8),
            mapper.quantizeFrame(UltraSubpixelRect(-2.0, 3.0, 5.0, 11.0)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            mapper.quantizeFrame(UltraSubpixelRect(-2.0, 3.0, 5.25, 11.0))
        }
    }

    @Test
    fun enclosingAndContainingQuantizationOperateOnSignedEdges() {
        val rect = UltraSubpixelRect(-1.2, -2.8, 3.2, 4.1)
        val enclosing = mapper(
            AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
            frame = PixelEdgeQuantization.ENCLOSE,
        )
        val containing = mapper(
            AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
            frame = PixelEdgeQuantization.CONTAIN,
        )

        assertEquals(UltraPixelRect(-2, -3, 6, 8), enclosing.quantizeFrame(rect))
        assertEquals(UltraPixelRect(-1, -2, 4, 6), containing.quantizeFrame(rect))
    }

    @Test
    fun containingQuantizationMayProduceAnEmptyRectangle() {
        val mapper = mapper(
            AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
            frame = PixelEdgeQuantization.CONTAIN,
        )

        assertEquals(
            UltraPixelRect(1, 3, 0, 0),
            mapper.quantizeFrame(UltraSubpixelRect(0.2, 2.2, 0.8, 2.8)),
        )
    }

    @Test
    fun enclosingQuantizationNeverExpandsFractionalEmptyAxesIntoVisiblePixels() {
        val mapper = CarPlayLogicalPixelMapper(
            affine = AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
            negativeExtentPolicy = NegativeExtentPolicy.REJECT,
            frameQuantization = PixelEdgeQuantization.ENCLOSE,
            clipQuantization = PixelEdgeQuantization.ENCLOSE,
        )

        assertEquals(
            UltraPixelRect(0, 1, 0, 5),
            mapper.quantizeFrame(UltraSubpixelRect(0.2, 1.2, 0.2, 5.8)),
        )
        assertEquals(
            UltraPixelRect(0, 1, 5, 0),
            mapper.quantizeFrame(UltraSubpixelRect(0.2, 1.2, 4.8, 1.2)),
        )
        assertEquals(
            UltraPixelRect(-1, -2, 0, 0),
            mapper.quantizeClip(UltraSubpixelRect(-0.2, -1.2, -0.2, -1.2)),
        )
        assertEquals(
            UltraPixelRect(0, 0, 0, 0),
            mapper.mapClip(logicalRect(0.2, 0.3, 0.0, 0.0)),
        )
    }

    @Test
    fun otherQuantizersRetainTheirEmptyAxisSemantics() {
        val fractionalEmpty = UltraSubpixelRect(-1.5, 2.5, -1.5, 2.5)
        val containing = mapper(
            AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
            frame = PixelEdgeQuantization.CONTAIN,
        )
        val nearest = mapper(
            AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
            frame = PixelEdgeQuantization.NEAREST_TIES_TO_EVEN,
        )
        val integral = mapper(
            AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
            frame = PixelEdgeQuantization.REQUIRE_INTEGRAL,
        )

        assertEquals(UltraPixelRect(-1, 3, 0, 0), containing.quantizeFrame(fractionalEmpty))
        assertEquals(UltraPixelRect(-2, 2, 0, 0), nearest.quantizeFrame(fractionalEmpty))
        assertThrows(IllegalArgumentException::class.java) {
            integral.quantizeFrame(fractionalEmpty)
        }
    }

    @Test
    fun nearestUsesMathRintTiesToEvenForNegativeAndPositiveEdges() {
        val mapper = mapper(
            AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
            frame = PixelEdgeQuantization.NEAREST_TIES_TO_EVEN,
        )

        assertEquals(
            UltraPixelRect(-2, -2, 4, 4),
            mapper.quantizeFrame(UltraSubpixelRect(-2.5, -1.5, 2.5, 1.5)),
        )
        assertEquals(-2.0, Math.rint(-1.5), 0.0)
        assertEquals(-2.0, Math.rint(-2.5), 0.0)
        assertEquals(2.0, Math.rint(1.5), 0.0)
        assertEquals(2.0, Math.rint(2.5), 0.0)
    }

    @Test
    fun quantizesEdgesBeforeDerivingPixelExtents() {
        val mapper = mapper(
            AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
            frame = PixelEdgeQuantization.NEAREST_TIES_TO_EVEN,
        )

        assertEquals(
            UltraPixelRect(-1, 0, 2, 2),
            mapper.quantizeFrame(UltraSubpixelRect(-0.6, 0.4, 0.6, 1.6)),
        )
    }

    @Test
    fun frameAndClipQuantizationAreIndependentAndNullClipStaysNull() {
        val mapper = CarPlayLogicalPixelMapper(
            affine = AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
            negativeExtentPolicy = NegativeExtentPolicy.REJECT,
            frameQuantization = PixelEdgeQuantization.ENCLOSE,
            clipQuantization = PixelEdgeQuantization.CONTAIN,
        )
        val rect = UltraSubpixelRect(0.2, 1.2, 4.8, 5.8)

        assertEquals(UltraPixelRect(0, 1, 5, 5), mapper.quantizeFrame(rect))
        assertEquals(UltraPixelRect(1, 2, 3, 3), mapper.quantizeClip(rect))
        assertNull(mapper.quantizeClip(null))
        assertNull(mapper.mapClip(null))
    }

    @Test
    fun resolvesScaleToFitWithAnExplicitCenterAnchor() {
        val container = UltraSubpixelRect(100.0, 50.0, 900.0, 850.0)

        val result = resolveInputStreamPlacement(
            InputStreamPlacementRequest(
                sourcePixels = PixelSize(1920, 1080),
                container = container,
                kind = InputStreamTransformKind.SCALE_TO_FIT,
                anchor = PixelAnchor(0.5, 0.5),
                clipBounds = null,
            ),
        )

        assertRect(100.0, 225.0, 900.0, 675.0, result.frame)
        assertNull(result.clip)
    }

    @Test
    fun resolvesScaleToFillAsAnOverflowingFrameWithIndependentClip() {
        val container = UltraSubpixelRect(100.0, 50.0, 900.0, 850.0)
        val clip = UltraSubpixelRect(120.0, 70.0, 880.0, 830.0)

        val result = resolveInputStreamPlacement(
            InputStreamPlacementRequest(
                sourcePixels = PixelSize(1920, 1080),
                container = container,
                kind = InputStreamTransformKind.SCALE_TO_FILL,
                anchor = PixelAnchor(0.5, 0.5),
                clipBounds = clip,
            ),
        )

        assertRect(-211.1111111111111, 50.0, 1211.111111111111, 850.0, result.frame)
        assertEquals(clip, result.clip)
        assertEquals(container, UltraSubpixelRect(100.0, 50.0, 900.0, 850.0))
    }

    @Test
    fun callerResolvedAnchorControlsBothAxesWithoutAlignmentInference() {
        val container = UltraSubpixelRect(10.0, 20.0, 410.0, 420.0)
        fun place(anchor: PixelAnchor) = resolveInputStreamPlacement(
            InputStreamPlacementRequest(
                sourcePixels = PixelSize(100, 200),
                container = container,
                kind = InputStreamTransformKind.SCALE_TO_FIT,
                anchor = anchor,
                clipBounds = null,
            ),
        ).frame

        assertRect(10.0, 20.0, 210.0, 420.0, place(PixelAnchor(0.0, 0.0)))
        assertRect(110.0, 20.0, 310.0, 420.0, place(PixelAnchor(0.5, 0.5)))
        assertRect(210.0, 20.0, 410.0, 420.0, place(PixelAnchor(1.0, 1.0)))
    }

    @Test
    fun anchorRequiresFiniteUnitCoordinates() {
        listOf(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { PixelAnchor(invalid, 0.5) }
            assertThrows(IllegalArgumentException::class.java) { PixelAnchor(0.5, invalid) }
        }
    }

    @Test
    fun emptyContainerAndClipRemainExplicitlyEmpty() {
        val empty = UltraSubpixelRect(-5.0, 8.0, -5.0, 8.0)
        val result = resolveInputStreamPlacement(
            InputStreamPlacementRequest(
                sourcePixels = PixelSize(1920, 1080),
                container = empty,
                kind = InputStreamTransformKind.SCALE_TO_FIT,
                anchor = PixelAnchor(0.5, 0.5),
                clipBounds = empty,
            ),
        )

        assertEquals(empty, result.frame)
        assertEquals(empty, result.clip)
    }

    @Test
    fun publicValueInputsAndResultsExposeOnlyFinalFields() {
        listOf(
            AxisAlignedPixelAffine::class.java,
            UltraSubpixelRect::class.java,
            PixelAnchor::class.java,
            InputStreamPlacementRequest::class.java,
            InputStreamPlacement::class.java,
        ).forEach { type ->
            assertTrue(
                "$type contains a mutable instance field",
                type.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .all { Modifier.isFinal(it.modifiers) },
            )
        }
    }

    private fun mapper(
        affine: AxisAlignedPixelAffine,
        extent: NegativeExtentPolicy = NegativeExtentPolicy.REJECT,
        frame: PixelEdgeQuantization = PixelEdgeQuantization.REQUIRE_INTEGRAL,
        clip: PixelEdgeQuantization = PixelEdgeQuantization.REQUIRE_INTEGRAL,
    ) = CarPlayLogicalPixelMapper(affine, extent, frame, clip)

    private fun logicalRect(x: Double, y: Double, width: Double, height: Double) =
        CarPlayLogicalRect(CarPlayLogicalPoint(x, y), CarPlayLogicalSize(width, height))

    private fun assertRect(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        actual: UltraSubpixelRect,
    ) {
        assertEquals(left, actual.left, 1e-9)
        assertEquals(top, actual.top, 1e-9)
        assertEquals(right, actual.right, 1e-9)
        assertEquals(bottom, actual.bottom, 1e-9)
    }
}
