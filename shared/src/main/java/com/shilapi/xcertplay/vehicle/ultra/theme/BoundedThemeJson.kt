package com.shilapi.xcertplay.vehicle.ultra.theme

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

/**
 * Decodes one RFC 8259 JSON value under the same resource limits used by the theme schemas.
 *
 * The input must be strict UTF-8. A UTF-8 BOM is rejected rather than silently ignored, malformed
 * byte sequences are reported, duplicate object names are rejected, and no comments, trailing
 * commas, non-finite numbers, extra roots, or trailing non-whitespace bytes are accepted. The
 * returned maps and lists are deeply immutable. The caller's [ByteArray] is copied before it is
 * decoded and is never retained.
 *
 * [CarPlayThemeSchemaLimits.maximumDataBytes] is also the encoded-input byte budget. Numeric token
 * bytes are charged against [CarPlayThemeSchemaLimits.maximumNumberBytes], in addition to the
 * bounded coefficient and exponent checks below.
 */
fun decodeThemeJson(
    bytes: ByteArray,
    limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
): Any? {
    if (bytes.size.toLong() > limits.maximumDataBytes) {
        throw BoundedThemeJsonException("Theme JSON exceeds its encoded-input byte budget")
    }
    if (bytes.size >= UTF8_BOM.size &&
        bytes[0] == UTF8_BOM[0] &&
        bytes[1] == UTF8_BOM[1] &&
        bytes[2] == UTF8_BOM[2]
    ) {
        throw BoundedThemeJsonException("Theme JSON must not contain a UTF-8 BOM")
    }

    // Snapshot first so a concurrently mutable caller buffer cannot change what is decoded.
    val snapshot = bytes.copyOf()
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(snapshot))
            .toString()
    } catch (_: CharacterCodingException) {
        throw BoundedThemeJsonException("Theme JSON is not valid UTF-8")
    }
    return BoundedThemeJsonParser(text, limits).decode()
}

class BoundedThemeJsonException(message: String) : CarPlayThemeSchemaException(message)

private class BoundedThemeJsonParser(
    private val source: String,
    private val limits: CarPlayThemeSchemaLimits,
) {
    private var cursor = 0
    private var nodes = 0
    private var collectionEntries = 0
    private var stringBytes = 0L
    private var numberTokenBytes = 0L

    fun decode(): Any? {
        skipWhitespace()
        val result = parseValue(depth = 0)
        skipWhitespace()
        if (cursor != source.length) fail("Theme JSON has trailing content")
        return result
    }

    private fun parseValue(depth: Int): Any? {
        consumeNode(depth)
        if (cursor >= source.length) fail("Theme JSON ended before a value")
        return when (source[cursor]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> fail("Theme JSON contains an invalid value")
        }
    }

    private fun parseObject(depth: Int): Map<String, Any?> {
        cursor += 1 // {
        skipWhitespace()
        val result = LinkedHashMap<String, Any?>()
        if (takeIfPresent('}')) return Collections.unmodifiableMap(result)

        var entries = 0
        while (true) {
            if (cursor >= source.length || source[cursor] != '"') {
                fail("Theme JSON object names must be strings")
            }
            val key = parseString()
            if (result.containsKey(key)) {
                fail("Theme JSON contains a duplicate object name")
            }
            skipWhitespace()
            requireCharacter(':', "Theme JSON object is missing a name separator")
            skipWhitespace()
            consumeCollectionEntry()
            entries += 1
            if (entries > limits.maximumCollectionEntries) {
                fail("Theme JSON object exceeds its collection-entry budget")
            }
            result[key] = parseValue(depth + 1)
            skipWhitespace()
            when {
                takeIfPresent('}') -> return Collections.unmodifiableMap(result)
                takeIfPresent(',') -> {
                    skipWhitespace()
                    if (cursor < source.length && source[cursor] == '}') {
                        fail("Theme JSON contains a trailing comma")
                    }
                }
                else -> fail("Theme JSON object is missing an entry separator")
            }
        }
    }

    private fun parseArray(depth: Int): List<Any?> {
        cursor += 1 // [
        skipWhitespace()
        val result = ArrayList<Any?>()
        if (takeIfPresent(']')) return Collections.unmodifiableList(result)

        var entries = 0
        while (true) {
            consumeCollectionEntry()
            entries += 1
            if (entries > limits.maximumCollectionEntries) {
                fail("Theme JSON array exceeds its collection-entry budget")
            }
            result += parseValue(depth + 1)
            skipWhitespace()
            when {
                takeIfPresent(']') -> return Collections.unmodifiableList(result)
                takeIfPresent(',') -> {
                    skipWhitespace()
                    if (cursor < source.length && source[cursor] == ']') {
                        fail("Theme JSON contains a trailing comma")
                    }
                }
                else -> fail("Theme JSON array is missing an entry separator")
            }
        }
    }

    private fun parseString(): String {
        requireCharacter('"', "Theme JSON string is malformed")
        val result = StringBuilder()
        while (cursor < source.length) {
            val current = source[cursor++]
            when {
                current == '"' -> return result.toString()
                current == '\\' -> parseEscape(result)
                current.code < 0x20 -> fail("Theme JSON string contains an unescaped control")
                Character.isHighSurrogate(current) -> {
                    if (cursor >= source.length || !Character.isLowSurrogate(source[cursor])) {
                        fail("Theme JSON string contains an unpaired surrogate")
                    }
                    val low = source[cursor++]
                    consumeStringBytes(4)
                    result.append(current).append(low)
                }
                Character.isLowSurrogate(current) -> {
                    fail("Theme JSON string contains an unpaired surrogate")
                }
                else -> {
                    consumeStringBytes(utf8Bytes(current))
                    result.append(current)
                }
            }
        }
        fail("Theme JSON string is unterminated")
    }

    private fun parseEscape(result: StringBuilder) {
        if (cursor >= source.length) fail("Theme JSON escape is incomplete")
        when (val escaped = source[cursor++]) {
            '"', '\\', '/' -> {
                consumeStringBytes(1)
                result.append(escaped)
            }
            'b' -> appendEscapedAscii(result, '\b')
            'f' -> appendEscapedAscii(result, '\u000c')
            'n' -> appendEscapedAscii(result, '\n')
            'r' -> appendEscapedAscii(result, '\r')
            't' -> appendEscapedAscii(result, '\t')
            'u' -> parseUnicodeEscape(result)
            else -> fail("Theme JSON contains an invalid escape")
        }
    }

    private fun appendEscapedAscii(result: StringBuilder, value: Char) {
        consumeStringBytes(1)
        result.append(value)
    }

    private fun parseUnicodeEscape(result: StringBuilder) {
        val first = parseHexCodeUnit()
        when {
            Character.isHighSurrogate(first) -> {
                if (cursor > source.length - 6 ||
                    source[cursor] != '\\' ||
                    source[cursor + 1] != 'u'
                ) {
                    fail("Theme JSON string contains an unpaired surrogate escape")
                }
                cursor += 2
                val second = parseHexCodeUnit()
                if (!Character.isLowSurrogate(second)) {
                    fail("Theme JSON string contains an unpaired surrogate escape")
                }
                consumeStringBytes(4)
                result.append(first).append(second)
            }
            Character.isLowSurrogate(first) -> {
                fail("Theme JSON string contains an unpaired surrogate escape")
            }
            else -> {
                consumeStringBytes(utf8Bytes(first))
                result.append(first)
            }
        }
    }

    private fun parseHexCodeUnit(): Char {
        if (cursor > source.length - 4) fail("Theme JSON unicode escape is incomplete")
        var value = 0
        repeat(4) {
            val digit = source[cursor++].digitToIntOrNull(radix = 16)
                ?: fail("Theme JSON unicode escape is malformed")
            value = (value shl 4) or digit
        }
        return value.toChar()
    }

    private fun parseNumber(): Number {
        val start = cursor
        if (takeIfPresent('-') && cursor >= source.length) {
            fail("Theme JSON number is incomplete")
        }

        when {
            takeIfPresent('0') -> {
                if (cursor < source.length && source[cursor] in '0'..'9') {
                    fail("Theme JSON number has a leading zero")
                }
            }
            cursor < source.length && source[cursor] in '1'..'9' -> {
                cursor += 1
                while (cursor < source.length && source[cursor] in '0'..'9') cursor += 1
            }
            else -> fail("Theme JSON number has no integer part")
        }

        var decimal = false
        if (takeIfPresent('.')) {
            decimal = true
            val fractionStart = cursor
            while (cursor < source.length && source[cursor] in '0'..'9') cursor += 1
            if (cursor == fractionStart) fail("Theme JSON number has no fractional digits")
        }

        var exponentMagnitude = 0
        if (cursor < source.length && (source[cursor] == 'e' || source[cursor] == 'E')) {
            decimal = true
            cursor += 1
            takeIfPresent('+') || takeIfPresent('-')
            val exponentStart = cursor
            while (cursor < source.length && source[cursor] in '0'..'9') {
                val digit = source[cursor] - '0'
                if (exponentMagnitude <= MAXIMUM_DECIMAL_EXPONENT) {
                    exponentMagnitude = exponentMagnitude * 10 + digit
                }
                cursor += 1
            }
            if (cursor == exponentStart) fail("Theme JSON number has no exponent digits")
            if (exponentMagnitude > MAXIMUM_DECIMAL_EXPONENT) {
                fail("Theme JSON decimal exponent exceeds its limit")
            }
        }

        val tokenLength = cursor - start
        if (tokenLength > MAXIMUM_NUMBER_TOKEN_BYTES) {
            fail("Theme JSON number token exceeds its limit")
        }
        consumeNumberTokenBytes(tokenLength)
        val token = source.substring(start, cursor)
        return try {
            if (decimal) parseDecimal(token) else parseInteger(token)
        } catch (_: NumberFormatException) {
            fail("Theme JSON number is malformed")
        }
    }

    private fun parseInteger(token: String): Number {
        token.toLongOrNull()?.let { return it }
        val value = BigInteger(token)
        if (value.bitLength() > MAXIMUM_INTEGER_BITS) {
            fail("Theme JSON integer magnitude exceeds its limit")
        }
        return value
    }

    private fun parseDecimal(token: String): BigDecimal {
        val value = BigDecimal(token)
        if (value.scale() !in -MAXIMUM_DECIMAL_SCALE..MAXIMUM_DECIMAL_SCALE ||
            value.unscaledValue().bitLength() > MAXIMUM_DECIMAL_COEFFICIENT_BITS
        ) {
            fail("Theme JSON decimal magnitude exceeds its limit")
        }
        return value
    }

    private fun <T> parseLiteral(literal: String, value: T): T {
        if (!source.regionMatches(cursor, literal, 0, literal.length)) {
            fail("Theme JSON contains an invalid literal")
        }
        cursor += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (cursor < source.length) {
            when (source[cursor]) {
                ' ', '\t', '\n', '\r' -> cursor += 1
                else -> return
            }
        }
    }

    private fun takeIfPresent(expected: Char): Boolean {
        if (cursor >= source.length || source[cursor] != expected) return false
        cursor += 1
        return true
    }

    private fun requireCharacter(expected: Char, message: String) {
        if (!takeIfPresent(expected)) fail(message)
    }

    private fun consumeNode(depth: Int) {
        if (depth > limits.maximumNestingDepth) {
            fail("Theme JSON exceeds its nesting-depth budget")
        }
        nodes += 1
        if (nodes > limits.maximumNodeCount) {
            fail("Theme JSON exceeds its node budget")
        }
    }

    private fun consumeCollectionEntry() {
        collectionEntries += 1
        if (collectionEntries > limits.maximumCollectionEntries) {
            fail("Theme JSON exceeds its collection-entry budget")
        }
    }

    private fun consumeStringBytes(count: Int) {
        if (count < 0 || count.toLong() > limits.maximumStringBytes - stringBytes) {
            fail("Theme JSON exceeds its string-byte budget")
        }
        stringBytes += count
    }

    private fun consumeNumberTokenBytes(count: Int) {
        if (count < 0 || count.toLong() > limits.maximumNumberBytes - numberTokenBytes) {
            fail("Theme JSON exceeds its numeric-token budget")
        }
        numberTokenBytes += count
    }

    private fun utf8Bytes(value: Char): Int = when {
        value.code < 0x80 -> 1
        value.code < 0x800 -> 2
        else -> 3
    }

    private fun fail(message: String): Nothing = throw BoundedThemeJsonException(message)

    private companion object {
        // These hard caps apply even when the aggregate schema budget is configured more loosely.
        const val MAXIMUM_NUMBER_TOKEN_BYTES = 4_096
        const val MAXIMUM_INTEGER_BITS = 4_096
        const val MAXIMUM_DECIMAL_COEFFICIENT_BITS = 4_096
        const val MAXIMUM_DECIMAL_EXPONENT = 10_000
        const val MAXIMUM_DECIMAL_SCALE = 10_000
    }
}

private val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
