package com.shilapi.xcertplay.airplay.rcs.opack

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.UUID

class OpackException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Resource limits applied before OPACK allocates or descends into untrusted values. */
data class OpackLimits(
    val maxEncodedBytes: Int = 4 * 1024 * 1024,
    val maxValueBytes: Int = 1024 * 1024,
    val maxCollectionEntries: Int = 16 * 1024,
    val maxObjectTableEntries: Int = 64 * 1024,
    val maxDepth: Int = 64,
) {
    init {
        require(maxEncodedBytes > 0) { "maxEncodedBytes must be positive" }
        require(maxValueBytes >= 0) { "maxValueBytes must not be negative" }
        require(maxValueBytes <= maxEncodedBytes) {
            "maxValueBytes must not exceed maxEncodedBytes"
        }
        require(maxCollectionEntries >= 0) { "maxCollectionEntries must not be negative" }
        require(maxObjectTableEntries >= 0) { "maxObjectTableEntries must not be negative" }
        require(maxDepth >= 0) { "maxDepth must not be negative" }
    }
}

/**
 * Apple CoreUtils OPACK codec used by CAF.
 *
 * This is not a property list. The implementation covers the scalar and collection tags needed by
 * CAF: null, booleans, signed integers, float32/float64, strings, data, arrays, dictionaries, and
 * object-reference tags emitted by Apple encoders. Unknown or unsupported tags fail explicitly.
 */
object OpackCodec {
    val DEFAULT_LIMITS = OpackLimits()

    data class DecodeResult(
        val value: Any?,
        val remaining: ByteArray,
    )

    fun encode(value: Any?): ByteArray = encode(value, DEFAULT_LIMITS)

    fun encode(value: Any?, limits: OpackLimits): ByteArray =
        Encoder(limits).encode(value)

    fun decode(bytes: ByteArray): Any? = decode(bytes, DEFAULT_LIMITS)

    fun decode(bytes: ByteArray, limits: OpackLimits): Any? {
        val decoded = decodePrefix(bytes, limits)
        if (decoded.remaining.isNotEmpty()) {
            throw OpackException("OPACK has ${decoded.remaining.size} trailing bytes")
        }
        return decoded.value
    }

    fun decodePrefix(bytes: ByteArray): DecodeResult = decodePrefix(bytes, DEFAULT_LIMITS)

    fun decodePrefix(bytes: ByteArray, limits: OpackLimits): DecodeResult {
        if (bytes.isEmpty()) throw OpackException("OPACK payload is empty")
        if (bytes.size > limits.maxEncodedBytes) {
            throw OpackException(
                "OPACK payload size ${bytes.size} exceeds ${limits.maxEncodedBytes}",
            )
        }
        val cursor = Cursor(bytes, limits)
        val objectTable = ArrayList<Any?>()
        val value = readValue(cursor, objectTable, limits, depth = 0)
        return DecodeResult(value, bytes.copyOfRange(cursor.position, bytes.size))
    }

    private fun readValue(
        cursor: Cursor,
        objectTable: MutableList<Any?>,
        limits: OpackLimits,
        depth: Int,
    ): Any? {
        checkDepth(depth, limits)
        val tag = cursor.readU8()
        return when (tag) {
            0x01 -> true
            0x02 -> false
            0x04 -> null
            0x05 -> {
                val bytes = cursor.readBytes(16)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                UUID(buffer.long, buffer.long).also { addObject(objectTable, it, limits) }
            }
            0x06 -> throw OpackException(
                "Unsupported OPACK absolute-time tag 0x06; use a typed CAF value instead",
            )
            in 0x07..0x2f -> (tag - 0x08).toLong()
            0x30, 0x31, 0x32, 0x33 -> {
                val width = 1 shl (tag and 0x0f)
                readSignedInteger(cursor, width).also {
                    if (it !in INLINE_INTEGER_MIN..INLINE_INTEGER_MAX) {
                        addObject(objectTable, it, limits)
                    }
                }
            }
            0x35 -> {
                val bits = readUnsignedInteger(cursor, 4).toInt()
                Float.fromBits(bits).also { addObject(objectTable, it, limits) }
            }
            0x36 -> {
                val bits = readUnsignedInteger(cursor, 8)
                Double.fromBits(bits).also { addObject(objectTable, it, limits) }
            }
            in 0x40..0x60 -> {
                readString(cursor, tag - 0x40).also {
                    if (it.isNotEmpty()) addObject(objectTable, it, limits)
                }
            }
            0x61, 0x62, 0x63, 0x64 -> {
                val width = 1 shl (tag - 0x61)
                readString(cursor, cursor.readLength(width)).also {
                    if (it.isNotEmpty()) addObject(objectTable, it, limits)
                }
            }
            in 0x70..0x90 -> {
                cursor.readBytes(tag - 0x70).also {
                    if (it.size >= MIN_REFERENCE_DATA_BYTES) addObject(objectTable, it, limits)
                }
            }
            0x91, 0x92, 0x93, 0x94 -> {
                val width = 1 shl (tag - 0x91)
                cursor.readBytes(cursor.readLength(width)).also {
                    if (it.size >= MIN_REFERENCE_DATA_BYTES) addObject(objectTable, it, limits)
                }
            }
            in 0xa0..0xc0 -> {
                val index = tag - 0xa0
                objectTable.getOrNull(index)
                    ?: throw OpackException("OPACK object reference $index is out of range")
            }
            0xc1, 0xc2, 0xc3, 0xc4 -> {
                val index = cursor.readLength(tag - 0xc0, enforceValueLimit = false)
                objectTable.getOrNull(index)
                    ?: throw OpackException("OPACK object reference $index is out of range")
            }
            in 0xd0..0xdf -> readArray(
                cursor,
                tag and 0x0f,
                objectTable,
                limits,
                depth + 1,
            )
            in 0xe0..0xef -> readDictionary(
                cursor,
                tag and 0x0f,
                objectTable,
                limits,
                depth + 1,
            )
            else -> throw OpackException("Unknown OPACK tag 0x${tag.toString(16).padStart(2, '0')}")
        }
    }

    private fun readArray(
        cursor: Cursor,
        count: Int,
        objectTable: MutableList<Any?>,
        limits: OpackLimits,
        depth: Int,
    ): List<Any?> {
        checkDepth(depth, limits)
        if (count != INDEFINITE_COUNT && count > limits.maxCollectionEntries) {
            throw OpackException(
                "OPACK array entry count $count exceeds ${limits.maxCollectionEntries}",
            )
        }
        val values = ArrayList<Any?>(
            if (count == INDEFINITE_COUNT) minOf(16, limits.maxCollectionEntries) else count,
        )
        if (count == INDEFINITE_COUNT) {
            while (true) {
                if (cursor.peekU8() == END_COLLECTION_TAG) {
                    cursor.readU8()
                    break
                }
                if (values.size >= limits.maxCollectionEntries) {
                    throw OpackException(
                        "OPACK array entry count exceeds ${limits.maxCollectionEntries}",
                    )
                }
                values += readValue(cursor, objectTable, limits, depth)
            }
        } else {
            repeat(count) { values += readValue(cursor, objectTable, limits, depth) }
        }
        return values
    }

    private fun readDictionary(
        cursor: Cursor,
        count: Int,
        objectTable: MutableList<Any?>,
        limits: OpackLimits,
        depth: Int,
    ): Map<Any?, Any?> {
        checkDepth(depth, limits)
        if (count != INDEFINITE_COUNT && count > limits.maxCollectionEntries) {
            throw OpackException(
                "OPACK dictionary entry count $count exceeds ${limits.maxCollectionEntries}",
            )
        }
        val values = LinkedHashMap<Any?, Any?>(
            if (count == INDEFINITE_COUNT) minOf(16, limits.maxCollectionEntries) else count,
        )
        val decodedKeys = HashSet<SemanticKey>(
            if (count == INDEFINITE_COUNT) minOf(16, limits.maxCollectionEntries) else count,
        )
        if (count == INDEFINITE_COUNT) {
            var entries = 0
            while (true) {
                if (cursor.peekU8() == END_COLLECTION_TAG) {
                    cursor.readU8()
                    break
                }
                if (entries >= limits.maxCollectionEntries) {
                    throw OpackException(
                        "OPACK dictionary entry count exceeds ${limits.maxCollectionEntries}",
                    )
                }
                val key = readValue(cursor, objectTable, limits, depth)
                rejectDuplicateKey(decodedKeys, key)
                if (cursor.peekU8() == END_COLLECTION_TAG) {
                    throw OpackException("OPACK dictionary entry is missing a value")
                }
                val value = readValue(cursor, objectTable, limits, depth)
                values[key] = value
                entries++
            }
        } else {
            repeat(count) {
                val key = readValue(cursor, objectTable, limits, depth)
                rejectDuplicateKey(decodedKeys, key)
                val value = readValue(cursor, objectTable, limits, depth)
                values[key] = value
            }
        }
        return values
    }

    private fun rejectDuplicateKey(decodedKeys: MutableSet<SemanticKey>, key: Any?) {
        if (!decodedKeys.add(SemanticKey(key))) {
            throw OpackException("OPACK dictionary contains a duplicate key")
        }
    }

    /** Kotlin arrays use identity equality; OPACK/CF collections use value equality. */
    private class SemanticKey(private val value: Any?) {
        private val semanticHash = semanticHash(value)

        override fun hashCode(): Int = semanticHash

        override fun equals(other: Any?): Boolean =
            other is SemanticKey && semanticEquals(value, other.value)
    }

    private fun semanticHash(value: Any?): Int = when (value) {
        null -> 0
        is ByteArray -> value.contentHashCode()
        is Float -> floatingHash(value.toDouble())
        is Double -> floatingHash(value)
        is List<*> -> value.fold(1) { hash, element -> 31 * hash + semanticHash(element) }
        is Map<*, *> -> value.entries.sumOf { entry ->
            semanticHash(entry.key) xor semanticHash(entry.value)
        }
        else -> value.hashCode()
    }

    private fun semanticEquals(first: Any?, second: Any?): Boolean {
        if (first === second) return true
        if (first == null || second == null) return false
        if (first is ByteArray && second is ByteArray) return first.contentEquals(second)
        if (first is Float || first is Double) {
            if (second !is Float && second !is Double) return false
            val firstValue = (first as Number).toDouble()
            val secondValue = (second as Number).toDouble()
            return firstValue == secondValue || (firstValue.isNaN() && secondValue.isNaN())
        }
        if (first is List<*> && second is List<*>) {
            return first.size == second.size && first.indices.all { index ->
                semanticEquals(first[index], second[index])
            }
        }
        if (first is Map<*, *> && second is Map<*, *>) {
            if (first.size != second.size) return false
            val unmatched = second.entries.toMutableList()
            return first.entries.all { left ->
                val matchIndex = unmatched.indexOfFirst { right ->
                    semanticEquals(left.key, right.key) &&
                        semanticEquals(left.value, right.value)
                }
                if (matchIndex < 0) {
                    false
                } else {
                    unmatched.removeAt(matchIndex)
                    true
                }
            }
        }
        return first == second
    }

    private fun floatingHash(value: Double): Int = when {
        value.isNaN() -> Double.NaN.hashCode()
        value == 0.0 -> 0.0.hashCode()
        else -> value.hashCode()
    }

    private fun readString(cursor: Cursor, length: Int): String {
        val bytes = cursor.readBytes(length)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw OpackException("OPACK string is not valid UTF-8", error)
        }
    }

    private fun readSignedInteger(cursor: Cursor, width: Int): Long {
        var value = readUnsignedInteger(cursor, width)
        if (width < 8 && (cursor.bytes[cursor.position - 1].toInt() and 0x80) != 0) {
            value = value or (-1L shl (width * 8))
        }
        return value
    }

    private fun readUnsignedInteger(cursor: Cursor, width: Int): Long {
        if (width !in 1..8) throw OpackException("Invalid OPACK integer width $width")
        var value = 0L
        repeat(width) { index ->
            value = value or ((cursor.readU8().toLong() and 0xff) shl (index * 8))
        }
        return value
    }

    private fun addObject(
        objectTable: MutableList<Any?>,
        value: Any?,
        limits: OpackLimits,
    ) {
        if (objectTable.size >= limits.maxObjectTableEntries) {
            throw OpackException(
                "OPACK object table exceeds ${limits.maxObjectTableEntries} entries",
            )
        }
        objectTable += value
    }

    private fun checkDepth(depth: Int, limits: OpackLimits) {
        if (depth > limits.maxDepth) {
            throw OpackException("OPACK nesting depth $depth exceeds ${limits.maxDepth}")
        }
    }

    private class Encoder(private val limits: OpackLimits) {
        private val output = BoundedOutput(limits.maxEncodedBytes)
        private val activeCollections = IdentityHashMap<Any, Unit>()

        fun encode(value: Any?): ByteArray {
            writeValue(value, depth = 0)
            return output.toByteArray()
        }

        private fun writeValue(value: Any?, depth: Int) {
            checkDepth(depth, limits)
            when (value) {
                null -> output.writeByte(0x04)
                is Boolean -> output.writeByte(if (value) 0x01 else 0x02)
                is Byte -> writeInteger(value.toLong())
                is Short -> writeInteger(value.toLong())
                is Int -> writeInteger(value.toLong())
                is Long -> writeInteger(value)
                is BigInteger -> writeInteger(value)
                is Float -> {
                    output.writeByte(0x35)
                    output.write(littleEndian(value.toRawBits().toLong(), 4))
                }
                is Double -> {
                    output.writeByte(0x36)
                    output.write(littleEndian(value.toRawBits(), 8))
                }
                is String -> writeString(value)
                is ByteArray -> writeData(value)
                is UUID -> {
                    output.writeByte(0x05)
                    output.write(uuidBytes(value))
                }
                is List<*> -> writeCollection(value, depth) {
                    writeArray(value, depth + 1)
                }
                is Array<*> -> writeCollection(value, depth) {
                    writeArray(value.asList(), depth + 1)
                }
                is Map<*, *> -> writeCollection(value, depth) {
                    writeDictionary(value, depth + 1)
                }
                else -> throw OpackException(
                    "Unsupported OPACK value type ${value::class.java.name}",
                )
            }
        }

        private fun writeInteger(value: BigInteger) {
            val longValue = value.toLong()
            if (BigInteger.valueOf(longValue) != value) {
                throw OpackException("OPACK integer is outside the signed 64-bit range")
            }
            writeInteger(longValue)
        }

        private fun writeInteger(value: Long) {
            if (value in INLINE_INTEGER_MIN..INLINE_INTEGER_MAX) {
                output.writeByte((0x08 + value).toInt())
                return
            }
            val width = when (value) {
                in Byte.MIN_VALUE..Byte.MAX_VALUE -> 1
                in Short.MIN_VALUE..Short.MAX_VALUE -> 2
                in Int.MIN_VALUE..Int.MAX_VALUE -> 4
                else -> 8
            }
            val tag = when (width) {
                1 -> 0x30
                2 -> 0x31
                4 -> 0x32
                else -> 0x33
            }
            output.writeByte(tag)
            output.write(littleEndian(value, width))
        }

        private fun writeString(value: String) {
            requireValueLength(utf8Length(value), "string")
            val utf8 = value.toByteArray(StandardCharsets.UTF_8)
            if (utf8.size <= INLINE_VALUE_MAX) {
                output.writeByte(0x40 + utf8.size)
            } else {
                val width = powerOfTwoLengthWidth(utf8.size)
                val widthTag = when (width) {
                    1 -> 0x61
                    2 -> 0x62
                    4 -> 0x63
                    else -> 0x64
                }
                output.writeByte(widthTag)
                output.write(littleEndian(utf8.size.toLong(), width))
            }
            output.write(utf8)
        }

        private fun writeData(value: ByteArray) {
            requireValueLength(value.size, "data")
            if (value.size <= INLINE_VALUE_MAX) {
                output.writeByte(0x70 + value.size)
            } else {
                val width = powerOfTwoLengthWidth(value.size)
                val widthTag = when (width) {
                    1 -> 0x91
                    2 -> 0x92
                    4 -> 0x93
                    else -> 0x94
                }
                output.writeByte(widthTag)
                output.write(littleEndian(value.size.toLong(), width))
            }
            output.write(value)
        }

        private fun writeArray(values: List<*>, depth: Int) {
            requireCollectionSize(values.size, "array")
            writeCollectionMarker(0xd0, values.size)
            values.forEach { writeValue(it, depth) }
            if (values.size >= INDEFINITE_COUNT) output.writeByte(END_COLLECTION_TAG)
        }

        private fun writeDictionary(values: Map<*, *>, depth: Int) {
            requireCollectionSize(values.size, "dictionary")
            writeCollectionMarker(0xe0, values.size)
            values.forEach { (key, value) ->
                writeValue(key, depth)
                writeValue(value, depth)
            }
            if (values.size >= INDEFINITE_COUNT) output.writeByte(END_COLLECTION_TAG)
        }

        private inline fun writeCollection(
            collection: Any,
            depth: Int,
            write: () -> Unit,
        ) {
            checkDepth(depth + 1, limits)
            if (activeCollections.put(collection, Unit) != null) {
                throw OpackException("OPACK cannot encode a cyclic collection")
            }
            try {
                write()
            } finally {
                activeCollections.remove(collection)
            }
        }

        private fun writeCollectionMarker(high: Int, count: Int) {
            output.writeByte(high or if (count < INDEFINITE_COUNT) count else INDEFINITE_COUNT)
        }

        private fun requireValueLength(size: Int, kind: String) {
            if (size > limits.maxValueBytes) {
                throw OpackException(
                    "OPACK $kind length $size exceeds ${limits.maxValueBytes}",
                )
            }
        }

        private fun requireCollectionSize(size: Int, kind: String) {
            if (size > limits.maxCollectionEntries) {
                throw OpackException(
                    "OPACK $kind entry count $size exceeds ${limits.maxCollectionEntries}",
                )
            }
        }
    }

    private class BoundedOutput(private val maxBytes: Int) {
        private val delegate = ByteArrayOutputStream(minOf(maxBytes, 1024))

        fun writeByte(value: Int) {
            reserve(1)
            delegate.write(value)
        }

        fun write(bytes: ByteArray) {
            reserve(bytes.size)
            delegate.write(bytes, 0, bytes.size)
        }

        fun toByteArray(): ByteArray = delegate.toByteArray()

        private fun reserve(additionalBytes: Int) {
            if (additionalBytes < 0 || delegate.size() > maxBytes - additionalBytes) {
                throw OpackException("OPACK encoding exceeds $maxBytes bytes")
            }
        }
    }

    private class Cursor(
        val bytes: ByteArray,
        private val limits: OpackLimits,
    ) {
        var position: Int = 0
            private set

        fun readU8(): Int {
            if (position >= bytes.size) {
                throw OpackException("Unexpected end of OPACK at byte $position")
            }
            return bytes[position++].toInt() and 0xff
        }

        fun peekU8(): Int {
            if (position >= bytes.size) {
                throw OpackException("Unexpected end of OPACK at byte $position")
            }
            return bytes[position].toInt() and 0xff
        }

        fun readBytes(length: Int): ByteArray {
            if (length < 0 || length > limits.maxValueBytes) {
                throw OpackException(
                    "OPACK value length $length exceeds ${limits.maxValueBytes}",
                )
            }
            if (position > bytes.size - length) {
                throw OpackException(
                    "OPACK expects $length bytes at $position but only ${bytes.size - position} remain",
                )
            }
            return bytes.copyOfRange(position, position + length).also {
                position += length
            }
        }

        fun readLength(width: Int, enforceValueLimit: Boolean = true): Int {
            val value = readUnsignedInteger(this, width)
            if (value < 0 || value > Int.MAX_VALUE) {
                throw OpackException("OPACK length $value exceeds the JVM byte-array limit")
            }
            if (enforceValueLimit && value > limits.maxValueBytes) {
                throw OpackException(
                    "OPACK value length $value exceeds ${limits.maxValueBytes}",
                )
            }
            return value.toInt()
        }
    }

    private fun powerOfTwoLengthWidth(size: Int): Int = when {
        size <= 0xff -> 1
        size <= 0xffff -> 2
        else -> 4
    }

    /** Computes and validates UTF-8 size without first allocating an attacker-sized byte array. */
    private fun utf8Length(value: String): Int {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index]
            bytes += when {
                char.code <= 0x7f -> 1
                char.code <= 0x7ff -> 2
                Character.isHighSurrogate(char) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        throw OpackException("OPACK string contains an unpaired UTF-16 surrogate")
                    }
                    index++
                    4
                }
                Character.isLowSurrogate(char) -> {
                    throw OpackException("OPACK string contains an unpaired UTF-16 surrogate")
                }
                else -> 3
            }
            if (bytes > Int.MAX_VALUE) {
                throw OpackException("OPACK string length exceeds the JVM byte-array limit")
            }
            index++
        }
        return bytes.toInt()
    }

    private fun littleEndian(value: Long, width: Int): ByteArray {
        val output = ByteArray(width)
        var remaining = value
        for (index in 0 until width) {
            output[index] = remaining.toByte()
            remaining = remaining ushr 8
        }
        return output
    }

    private fun uuidBytes(value: UUID): ByteArray =
        ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(value.mostSignificantBits)
            .putLong(value.leastSignificantBits)
            .array()

    private const val INLINE_VALUE_MAX = 0x20
    private const val INLINE_INTEGER_MIN = -1L
    private const val INLINE_INTEGER_MAX = 0x27L
    private const val MIN_REFERENCE_DATA_BYTES = 2
    private const val INDEFINITE_COUNT = 0x0f
    private const val END_COLLECTION_TAG = 0x03
}
