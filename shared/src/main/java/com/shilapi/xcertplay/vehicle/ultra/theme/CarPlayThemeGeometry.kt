package com.shilapi.xcertplay.vehicle.ultra.theme

/** Logical CarPlay point. This is neither an Android pixel nor a dp coordinate. */
data class CarPlayLogicalPoint(val x: Double, val y: Double)

/** Logical CarPlay size. Scaling into a physical output belongs to a profile-aware renderer. */
data class CarPlayLogicalSize(val width: Double, val height: Double)

data class CarPlayLogicalRect(
    val origin: CarPlayLogicalPoint,
    val size: CarPlayLogicalSize,
)

/** SwiftUI EdgeInsets wire order: top, leading, bottom, trailing. */
data class CarPlayLogicalEdgeInsets(
    val top: Double,
    val leading: Double,
    val bottom: Double,
    val trailing: Double,
)

/**
 * Strict decoders for the confirmed CoreGraphics/SwiftUI Codable shapes used by CarPlayAssetUI.
 *
 * CGPoint and CGSize are unkeyed pairs; CGRect is `[point, size]`. CarPlayAssetUI's EdgeInsets
 * conformance encodes four unkeyed CGFloat values. These functions deliberately preserve logical
 * values and never multiply by Android density, display density, or a raster asset scale factor.
 */
object CarPlayThemeGeometry {
    fun point(value: CarPlayThemeOpaqueValue, label: String = "CGPoint"): CarPlayLogicalPoint {
        val values = exactList(value.value, 2, label)
        return CarPlayLogicalPoint(
            finite(values[0], "$label.x"),
            finite(values[1], "$label.y"),
        )
    }

    fun size(value: CarPlayThemeOpaqueValue, label: String = "CGSize"): CarPlayLogicalSize {
        val values = exactList(value.value, 2, label)
        return CarPlayLogicalSize(
            finite(values[0], "$label.width"),
            finite(values[1], "$label.height"),
        )
    }

    fun rect(value: CarPlayThemeOpaqueValue, label: String = "CGRect"): CarPlayLogicalRect {
        val values = exactList(value.value, 2, label)
        return CarPlayLogicalRect(
            point(CarPlayThemeOpaqueValue(values[0]), "$label.origin"),
            size(CarPlayThemeOpaqueValue(values[1]), "$label.size"),
        )
    }

    fun edgeInsets(
        value: CarPlayThemeOpaqueValue,
        label: String = "EdgeInsets",
    ): CarPlayLogicalEdgeInsets {
        val values = exactList(value.value, 4, label)
        return CarPlayLogicalEdgeInsets(
            top = finite(values[0], "$label.top"),
            leading = finite(values[1], "$label.leading"),
            bottom = finite(values[2], "$label.bottom"),
            trailing = finite(values[3], "$label.trailing"),
        )
    }

    private fun exactList(value: Any?, expected: Int, label: String): List<*> {
        val values = value as? List<*>
            ?: throw CarPlayThemeSchemaException("$label must be an unkeyed array")
        if (values.size != expected) {
            throw CarPlayThemeSchemaException("$label must contain exactly $expected values")
        }
        return values
    }

    private fun finite(value: Any?, label: String): Double {
        val number = value as? Number
            ?: throw CarPlayThemeSchemaException("$label must be numeric")
        val decoded = number.toDouble()
        if (!decoded.isFinite()) throw CarPlayThemeSchemaException("$label must be finite")
        return decoded
    }
}

fun Display.logicalSize(): CarPlayLogicalSize =
    CarPlayThemeGeometry.size(size, "Display.size")

fun Layout.logicalSafeAreaInsets(): CarPlayLogicalEdgeInsets =
    CarPlayThemeGeometry.edgeInsets(safeAreaInsets, "Layout.safeAreaInsets")

fun Zone.logicalFrame(): CarPlayLogicalRect =
    CarPlayThemeGeometry.rect(frame, "Zone.frame")

fun Component.logicalSize(): CarPlayLogicalSize =
    CarPlayThemeGeometry.size(size, "Component.size")

fun Slot.logicalFrame(): CarPlayLogicalRect =
    CarPlayThemeGeometry.rect(frame, "Slot.frame")

fun Slot.logicalSafeAreaInsets(): CarPlayLogicalEdgeInsets? = safeAreaInsets?.let {
    CarPlayThemeGeometry.edgeInsets(it, "Slot.safeAreaInsets")
}

fun CornerSlotStyle.logicalRingCenterPoint(): CarPlayLogicalPoint =
    CarPlayThemeGeometry.point(ringCenterPoint, "CornerSlotStyle.ringCenterPoint")

fun InputStreamTransform.logicalFrame(): CarPlayLogicalRect? = frame?.let {
    CarPlayThemeGeometry.rect(it, "InputStreamTransform.frame")
}

fun CustomMaskImage.logicalStartingFrame(): CarPlayLogicalRect =
    CarPlayThemeGeometry.rect(startingFrame, "CustomMaskImage.startingFrame")

fun CustomMaskImage.logicalEndingFrame(): CarPlayLogicalRect =
    CarPlayThemeGeometry.rect(endingFrame, "CustomMaskImage.endingFrame")
