package com.shilapi.xcertplay.vehicle.ultra.theme

import java.math.BigDecimal
import java.math.BigInteger

/**
 * A fixed, non-dynamic sRGB color decoded from the confirmed CarPlayAssetUI `Color` forms.
 * The Apple four-CGFloat decoder does not itself clamp or require unit-range finite values;
 * rejecting those values here is a local rendering-safety policy. This does not model Display's
 * light/dark dynamic color or resolved palette gradients.
 */
class CarPlayThemeColor private constructor(
    val red: Double,
    val green: Double,
    val blue: Double,
    val alpha: Double,
) {
    init {
        require(listOf(red, green, blue, alpha).all { it.isFinite() && it in 0.0..1.0 })
    }

    override fun equals(other: Any?): Boolean = other is CarPlayThemeColor &&
        red == other.red && green == other.green && blue == other.blue && alpha == other.alpha

    override fun hashCode(): Int {
        var result = red.hashCode()
        result = 31 * result + green.hashCode()
        result = 31 * result + blue.hashCode()
        result = 31 * result + alpha.hashCode()
        return result
    }

    override fun toString(): String = "CarPlayThemeColor($red, $green, $blue, $alpha)"

    companion object {
        /**
         * The custom decoder first accepts exactly four unkeyed CGFloat channels, then a string
         * color. Only the confirmed 3-, 6-, and 8-digit hex string forms are interpreted here.
         * Unknown and locally unsafe values deliberately remain opaque to the compiler.
         */
        fun parse(value: Any?): CarPlayThemeColor? = when (value) {
            is List<*> -> parseChannels(value)
            is String -> parseHex(value)
            else -> null
        }

        private fun parseChannels(value: List<*>): CarPlayThemeColor? {
            if (value.size != 4) return null
            val channels = DoubleArray(4)
            for (index in channels.indices) {
                val number = value[index]
                if (number !is Byte && number !is Short && number !is Int && number !is Long &&
                    number !is Float && number !is Double && number !is BigInteger &&
                    number !is BigDecimal
                ) return null
                val channel = (number as Number).toDouble()
                if (!channel.isFinite() || channel !in 0.0..1.0) return null
                channels[index] = canonicalZero(channel)
            }
            return CarPlayThemeColor(channels[0], channels[1], channels[2], channels[3])
        }

        private fun parseHex(value: String): CarPlayThemeColor? {
            val start = if (value.startsWith('#')) 1 else 0
            val digits = value.length - start
            if (digits != 3 && digits != 6 && digits != 8) return null
            fun nibble(index: Int): Int = when (val char = value[start + index]) {
                in '0'..'9' -> char.code - '0'.code
                in 'a'..'f' -> char.code - 'a'.code + 10
                in 'A'..'F' -> char.code - 'A'.code + 10
                else -> -1
            }
            fun byte(index: Int): Int {
                val high = nibble(index)
                val low = nibble(index + 1)
                return if (high < 0 || low < 0) -1 else high * 16 + low
            }
            val components = if (digits == 3) {
                IntArray(4) { index -> if (index == 3) 255 else nibble(index) * 17 }
            } else {
                IntArray(4) { index ->
                    if (index == 3 && digits == 6) 255 else byte(index * 2)
                }
            }
            if (components.any { it < 0 }) return null
            return CarPlayThemeColor(
                components[0] / 255.0,
                components[1] / 255.0,
                components[2] / 255.0,
                components[3] / 255.0,
            )
        }

        private fun canonicalZero(value: Double): Double = if (value == 0.0) 0.0 else value
    }
}
