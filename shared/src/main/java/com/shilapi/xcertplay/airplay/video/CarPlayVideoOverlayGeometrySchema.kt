package com.shilapi.xcertplay.airplay.video

/**
 * Detached, strictly shaped AirPlayKit geometry values. These are source coordinates, **not**
 * Android pixels or dp: DeviceInfo scaleFactor/safeArea consumption is not established.
 */
object CarPlayVideoOverlayGeometrySchema {
    sealed interface AxisPosition {
        data class AbsolutePoint(val value: Double) : AxisPosition
        data class Fractional(val value: Double) : AxisPosition
        data class AnchorPoint(val edge: String, val offset: Double) : AxisPosition
    }

    data class Point(val x: AxisPosition, val y: AxisPosition)

    /** AirPlayKit's centerAnchor pair, distinct from Point's per-axis anchorPoint case. */
    data class CenterAnchor(val x: Double, val y: Double)

    sealed interface Dimension {
        data class Absolute(val value: Double) : Dimension
        data class Offset(val value: Double) : Dimension
        data class Fractional(val value: Double) : Dimension
        data class ThatFits(val value: Double) : Dimension
    }

    data class Size(val width: Dimension, val height: Dimension)

    data class Transform(
        val a: Double,
        val b: Double,
        val c: Double,
        val d: Double,
        val tx: Double,
        val ty: Double,
    )

    /** Confirmed inner asset identifier only; ImageContent's enclosing alternatives remain opaque. */
    data class AssetReference(val identifier: String)

    fun point(value: Any?): Point? {
        val fields = value as? Map<*, *> ?: return null
        if (!fields.hasExactly("x", "y")) return null
        return Point(
            x = axis(fields["x"], horizontal = true) ?: return null,
            y = axis(fields["y"], horizontal = false) ?: return null,
        )
    }

    fun centerAnchor(value: Any?): CenterAnchor? {
        val fields = value as? Map<*, *> ?: return null
        if (!fields.hasExactly("x", "y")) return null
        return CenterAnchor(
            x = boundedReal(fields["x"]) ?: return null,
            y = boundedReal(fields["y"]) ?: return null,
        )
    }

    fun size(value: Any?): Size? {
        val fields = value as? Map<*, *> ?: return null
        if (!fields.hasExactly("width", "height")) return null
        return Size(
            width = dimension(fields["width"]) ?: return null,
            height = dimension(fields["height"]) ?: return null,
        )
    }

    fun transform(value: Any?): Transform? {
        val fields = value as? Map<*, *> ?: return null
        if (!fields.hasExactly("a", "b", "c", "d", "tx", "ty")) return null
        return Transform(
            a = boundedReal(fields["a"]) ?: return null,
            b = boundedReal(fields["b"]) ?: return null,
            c = boundedReal(fields["c"]) ?: return null,
            d = boundedReal(fields["d"]) ?: return null,
            tx = boundedReal(fields["tx"]) ?: return null,
            ty = boundedReal(fields["ty"]) ?: return null,
        )
    }

    fun assetReference(value: Any?): AssetReference? {
        val fields = value as? Map<*, *> ?: return null
        if (!fields.hasExactly("identifier")) return null
        val identifier = fields["identifier"] as? String ?: return null
        if (identifier.isEmpty() || identifier.length > MAX_IDENTIFIER_LENGTH) return null
        return AssetReference(identifier)
    }

    private fun axis(value: Any?, horizontal: Boolean): AxisPosition? {
        val fields = value as? Map<*, *> ?: return null
        if (!fields.hasExactly("type", "value")) return null
        return when (fields["type"]) {
            "absolutePoint" -> AxisPosition.AbsolutePoint(boundedReal(fields["value"]) ?: return null)
            "fractional" -> AxisPosition.Fractional(boundedReal(fields["value"]) ?: return null)
            "anchorPoint" -> {
                val anchor = fields["value"] as? Map<*, *> ?: return null
                if (!anchor.hasExactly("edge", "offset")) return null
                val edge = anchor["edge"] as? String ?: return null
                if (edge !in (if (horizontal) X_EDGES else Y_EDGES)) return null
                AxisPosition.AnchorPoint(edge, boundedReal(anchor["offset"]) ?: return null)
            }
            else -> null
        }
    }

    private fun dimension(value: Any?): Dimension? {
        val fields = value as? Map<*, *> ?: return null
        if (!fields.hasExactly("type", "value")) return null
        val number = boundedReal(fields["value"]) ?: return null
        return when (fields["type"]) {
            // The enum's display name differs from its decoder's wire discriminator.
            "absolutePoint" -> Dimension.Absolute(number)
            "offset" -> Dimension.Offset(number)
            "fractional" -> Dimension.Fractional(number)
            "thatFits" -> Dimension.ThatFits(number)
            else -> null
        }
    }

    // Swift models these fields as Float, but a binary plist NSNumber may decode as Float32 or
    // Float64. Retain the exact finite decoded value; never silently narrow a Float64 to Float32.
    private fun boundedReal(value: Any?): Double? {
        val number = when (value) {
            is Float -> value.toDouble()
            is Double -> value
            else -> return null
        }
        return number.takeIf { it.isFinite() && it in -MAX_ABSOLUTE_VALUE..MAX_ABSOLUTE_VALUE }
    }

    private fun Map<*, *>.hasExactly(vararg keys: String): Boolean =
        size == keys.size && keys.all(::containsKey)

    private val X_EDGES = setOf("leading", "trailing", "left", "right")
    private val Y_EDGES = setOf("top", "bottom")
    // Conservative memory/arithmetic policy only; it does not define a physical display unit.
    private const val MAX_ABSOLUTE_VALUE = 1_000_000.0
    private const val MAX_IDENTIFIER_LENGTH = 256
}
