package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalRect
import com.shilapi.xcertplay.vehicle.ultra.theme.InputStreamTransformKind
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Explicit axis-aligned transform from an already-resolved CarPlay logical coordinate space to
 * physical output pixels.
 *
 * This type intentionally has no density field and no constructor that accepts an Android
 * [android.util.DisplayMetrics]. A profile-aware caller must supply the exact scale and translation
 * for one output. Rotation and mirroring are outside this axis-aligned contract.
 */
data class AxisAlignedPixelAffine(
    val scaleX: Double,
    val scaleY: Double,
    val translateXPixels: Double,
    val translateYPixels: Double,
) {
    init {
        require(scaleX.isFinite() && scaleX > 0.0) {
            "Pixel affine scaleX must be finite and positive"
        }
        require(scaleY.isFinite() && scaleY > 0.0) {
            "Pixel affine scaleY must be finite and positive"
        }
        require(translateXPixels.isFinite()) {
            "Pixel affine translateXPixels must be finite"
        }
        require(translateYPixels.isFinite()) {
            "Pixel affine translateYPixels must be finite"
        }
    }
}

/**
 * Immutable, unquantized rectangle in physical-pixel coordinates.
 *
 * Origins may be negative and edges may lie outside an output viewport. This type never clips or
 * clamps them. Extents are normalized and non-negative; an empty rectangle has equal horizontal
 * or vertical edges.
 */
data class UltraSubpixelRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Subpixel rectangle edges must be finite"
        }
        require(right >= left) { "Subpixel rectangle width must not be negative" }
        require(bottom >= top) { "Subpixel rectangle height must not be negative" }
        require((right - left).isFinite()) { "Subpixel rectangle width overflowed" }
        require((bottom - top).isFinite()) { "Subpixel rectangle height overflowed" }
    }

    val width: Double
        get() = right - left

    val height: Double
        get() = bottom - top
}

/** Explicit handling for a logical CGRect whose encoded width or height is negative. */
enum class NegativeExtentPolicy {
    /** Reject the rectangle instead of assigning unproven CGRect-standardization semantics. */
    REJECT,

    /** Standardize each pair of edges before applying the pixel affine. */
    STANDARDIZE,
}

/**
 * Explicit conversion from subpixel edges to integer pixel edges.
 *
 * No case is a protocol default. In particular, the presence of a generic rounding helper in an
 * Apple binary is not evidence that a theme-to-output path uses it. Callers must select a policy
 * for frames and clips independently.
 */
enum class PixelEdgeQuantization {
    /** Every edge must already be an exact integer. */
    REQUIRE_INTEGRAL,

    /** Round outward: floor left/top and ceil right/bottom. */
    ENCLOSE,

    /** Round inward: ceil left/top and floor right/bottom; a collapsed result is empty. */
    CONTAIN,

    /** Round every edge to nearest with ties to even, using [Math.rint]. */
    NEAREST_TIES_TO_EVEN,
}

/**
 * Pure logical-to-pixel mapper with no implicit density, clipping, extent, or rounding policy.
 *
 * Input rectangles must already be expressed in the logical coordinate space selected by the
 * caller. This class does not infer whether a Slot frame is display-, zone-, or component-relative
 * and does not interpret `InputStreamTransform.frame`.
 */
class CarPlayLogicalPixelMapper(
    val affine: AxisAlignedPixelAffine,
    val negativeExtentPolicy: NegativeExtentPolicy,
    val frameQuantization: PixelEdgeQuantization,
    val clipQuantization: PixelEdgeQuantization,
) {
    fun mapSubpixel(rectInDisplayLogicalSpace: CarPlayLogicalRect): UltraSubpixelRect {
        val originX = finite(rectInDisplayLogicalSpace.origin.x, "Logical rectangle x")
        val originY = finite(rectInDisplayLogicalSpace.origin.y, "Logical rectangle y")
        val width = finite(rectInDisplayLogicalSpace.size.width, "Logical rectangle width")
        val height = finite(rectInDisplayLogicalSpace.size.height, "Logical rectangle height")
        if (negativeExtentPolicy == NegativeExtentPolicy.REJECT) {
            require(width >= 0.0) { "Logical rectangle width must not be negative" }
            require(height >= 0.0) { "Logical rectangle height must not be negative" }
        }

        val otherX = finiteSum(originX, width, "Logical rectangle horizontal edge")
        val otherY = finiteSum(originY, height, "Logical rectangle vertical edge")
        val logicalLeft = min(originX, otherX)
        val logicalTop = min(originY, otherY)
        val logicalRight = max(originX, otherX)
        val logicalBottom = max(originY, otherY)

        return UltraSubpixelRect(
            left = mapAxis(logicalLeft, affine.scaleX, affine.translateXPixels, "left"),
            top = mapAxis(logicalTop, affine.scaleY, affine.translateYPixels, "top"),
            right = mapAxis(logicalRight, affine.scaleX, affine.translateXPixels, "right"),
            bottom = mapAxis(
                logicalBottom,
                affine.scaleY,
                affine.translateYPixels,
                "bottom",
            ),
        )
    }

    /** Maps and quantizes a frame without clipping it to any viewport. */
    fun mapFrame(rectInDisplayLogicalSpace: CarPlayLogicalRect): UltraPixelRect =
        quantizeFrame(mapSubpixel(rectInDisplayLogicalSpace))

    /** Maps an independently selected clip, preserving null when the caller selected no clip. */
    fun mapClip(rectInDisplayLogicalSpace: CarPlayLogicalRect?): UltraPixelRect? =
        rectInDisplayLogicalSpace?.let { quantizeClip(mapSubpixel(it)) }

    fun quantizeFrame(rect: UltraSubpixelRect): UltraPixelRect =
        quantize(rect, frameQuantization)

    /** Preserves null; a non-null clip may quantize to a zero-width or zero-height rectangle. */
    fun quantizeClip(rect: UltraSubpixelRect?): UltraPixelRect? =
        rect?.let { quantize(it, clipQuantization) }

    private fun quantize(
        rect: UltraSubpixelRect,
        policy: PixelEdgeQuantization,
    ): UltraPixelRect {
        val wasEmptyHorizontally = rect.width == 0.0
        val wasEmptyVertically = rect.height == 0.0
        val left = quantizeEdge(rect.left, policy, minimumEdge = true)
        val top = quantizeEdge(rect.top, policy, minimumEdge = true)
        var right = quantizeEdge(rect.right, policy, minimumEdge = false)
        var bottom = quantizeEdge(rect.bottom, policy, minimumEdge = false)

        // Outward rounding must never turn a subpixel empty axis into visible output. Both original
        // edges were still quantized above so an out-of-range empty rectangle cannot bypass range
        // validation.
        if (wasEmptyHorizontally) right = left
        if (wasEmptyVertically) bottom = top

        // Inward rounding of a subpixel-sized rectangle may cross its opposite edges. Preserve a
        // deterministic empty rectangle at the rounded minimum edge instead of creating a negative
        // pixel extent.
        if (right < left) right = left
        if (bottom < top) bottom = top

        val width = right - left
        val height = bottom - top
        require(width in 0L..Int.MAX_VALUE.toLong()) { "Quantized pixel width overflowed" }
        require(height in 0L..Int.MAX_VALUE.toLong()) { "Quantized pixel height overflowed" }
        return UltraPixelRect(
            xPixels = left.toInt(),
            yPixels = top.toInt(),
            widthPixels = width.toInt(),
            heightPixels = height.toInt(),
        )
    }

    private fun quantizeEdge(
        value: Double,
        policy: PixelEdgeQuantization,
        minimumEdge: Boolean,
    ): Long {
        val quantized = when (policy) {
            PixelEdgeQuantization.REQUIRE_INTEGRAL -> {
                require(value == Math.rint(value)) { "Pixel edge must be an exact integer" }
                value
            }
            PixelEdgeQuantization.ENCLOSE -> if (minimumEdge) floor(value) else ceil(value)
            PixelEdgeQuantization.CONTAIN -> if (minimumEdge) ceil(value) else floor(value)
            PixelEdgeQuantization.NEAREST_TIES_TO_EVEN -> Math.rint(value)
        }
        require(quantized >= Int.MIN_VALUE.toDouble() && quantized <= Int.MAX_VALUE.toDouble()) {
            "Quantized pixel edge is outside the Int range"
        }
        return quantized.toLong()
    }

    private fun mapAxis(value: Double, scale: Double, translation: Double, label: String): Double {
        val mapped = value * scale + translation
        require(mapped.isFinite()) { "Mapped $label pixel edge is not finite" }
        return mapped
    }

    private fun finite(value: Double, label: String): Double {
        require(value.isFinite()) { "$label must be finite" }
        return value
    }

    private fun finiteSum(first: Double, second: Double, label: String): Double {
        val sum = first + second
        require(sum.isFinite()) { "$label is not finite" }
        return sum
    }
}

/**
 * A fully resolved two-dimensional alignment anchor.
 *
 * The mapper deliberately does not accept `InputStreamAlignment`: resolving leading/trailing for
 * layout direction and selecting the other axis are profile/UI responsibilities.
 */
data class PixelAnchor(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && x in 0.0..1.0) { "Pixel anchor x must be finite and in 0..1" }
        require(y.isFinite() && y in 0.0..1.0) { "Pixel anchor y must be finite and in 0..1" }
    }
}

/**
 * Fully explicit input to aspect-fit/fill placement.
 *
 * [container] and [clipBounds] must already have been resolved to output subpixels. The caller is
 * responsible for deciding what an optional theme transform frame means and which bounds, if any,
 * `clipToBounds` selects; neither semantic is inferred here.
 */
data class InputStreamPlacementRequest(
    val sourcePixels: PixelSize,
    val container: UltraSubpixelRect,
    val kind: InputStreamTransformKind,
    val anchor: PixelAnchor,
    val clipBounds: UltraSubpixelRect?,
)

/** Unquantized placement; frame and clip remain independent and immutable. */
data class InputStreamPlacement(
    val frame: UltraSubpixelRect,
    val clip: UltraSubpixelRect?,
)

/**
 * Resolves conventional aspect fit/fill only after every ambiguous input has been made explicit.
 * The returned frame is never clamped to [InputStreamPlacementRequest.container] or to an output
 * viewport. In particular, fill normally returns an overflowing frame plus the caller's separate
 * clip.
 */
fun resolveInputStreamPlacement(request: InputStreamPlacementRequest): InputStreamPlacement {
    val horizontalFactor = request.container.width / request.sourcePixels.widthPixels.toDouble()
    val verticalFactor = request.container.height / request.sourcePixels.heightPixels.toDouble()
    require(horizontalFactor.isFinite() && verticalFactor.isFinite()) {
        "Input-stream placement factors must be finite"
    }
    val factor = when (request.kind) {
        InputStreamTransformKind.SCALE_TO_FIT -> min(horizontalFactor, verticalFactor)
        InputStreamTransformKind.SCALE_TO_FILL -> max(horizontalFactor, verticalFactor)
    }
    require(factor.isFinite() && factor >= 0.0) {
        "Input-stream placement factor must be finite and non-negative"
    }
    val contentWidth = request.sourcePixels.widthPixels * factor
    val contentHeight = request.sourcePixels.heightPixels * factor
    require(contentWidth.isFinite() && contentHeight.isFinite()) {
        "Input-stream placement size is not finite"
    }
    val left = request.container.left +
        (request.container.width - contentWidth) * request.anchor.x
    val top = request.container.top +
        (request.container.height - contentHeight) * request.anchor.y
    val right = left + contentWidth
    val bottom = top + contentHeight
    require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
        "Input-stream placement edges are not finite"
    }
    return InputStreamPlacement(
        frame = UltraSubpixelRect(left, top, right, bottom),
        clip = request.clipBounds,
    )
}
