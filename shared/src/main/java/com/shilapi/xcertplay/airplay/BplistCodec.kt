package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, signed Int64/unsigned UInt64 and signed Int128 integers, 32/64-bit reals,
 * and booleans.
 * Dictionary keys are serialized in insertion order, matching the reference implementation this
 * stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    fun decode(bytes: ByteArray): Any? = decode(bytes, BplistDecodeLimits())

    internal fun decode(bytes: ByteArray, limits: BplistDecodeLimits): Any? =
        Decoder(bytes, limits, magic).decode()

    /**
     * Decode a SETUP body while retaining ownership of every decoded Data copy. A failed parse
     * clears copies already made, including objects later referenced by another plist node.
     * Callers must terminate a successful scope with [OwnedDecodedPlist.release] or
     * [OwnedDecodedPlist.detachWithoutWipe] after dispatching the decoded root.
     */
    internal fun decodeOwned(bytes: ByteArray): OwnedDecodedPlist =
        decodeOwned(bytes, BplistDecodeLimits())

    internal fun decodeOwned(
        bytes: ByteArray,
        limits: BplistDecodeLimits,
        onDecodedData: ((ByteArray) -> Unit)? = null,
    ): OwnedDecodedPlist {
        val owned = OwnedDecodedPlist()
        try {
            owned.setRoot(
                Decoder(bytes, limits, magic) { data ->
                    owned.ownData(data)
                    onDecodedData?.invoke(data)
                }.decode(),
            )
            return owned
        } catch (failure: Throwable) {
            owned.release()
            throw failure
        }
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    nodes[index] = Leaf(encodeBigInteger(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = Leaf(encodeSignedInt(number))
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { add(it.key.toString()) }.toIntArray()
                    val valueRefs = entries.map { add(it.value) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    /** Foundation encodes every negative integer as one 8-byte two's-complement integer. */
    private fun encodeSignedInt(number: Long): ByteArray = if (number >= 0L) {
        encodeInt(number)
    } else {
        byteArrayOf(0x13) + bigEndian(number, 8)
    }

    private fun encodeBigInteger(number: java.math.BigInteger): ByteArray {
        if (number.signum() < 0 && number >= SIGNED_INT64_MINIMUM) {
            return encodeSignedInt(number.toLong())
        }
        if (number.signum() >= 0 && number.bitLength() <= 64) {
            return encodeUnsignedInt(number)
        }
        require(number >= SIGNED_INT128_MINIMUM && number <= SIGNED_INT128_MAXIMUM) {
            "bplist: integer is outside signed 128-bit range"
        }
        val raw = number.toByteArray()
        val padded = ByteArray(16) { if (number.signum() < 0) 0xff.toByte() else 0 }
        raw.copyInto(padded, destinationOffset = padded.size - raw.size)
        return byteArrayOf(0x14) + padded
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        val raw = number.toByteArray()
        val padded = ByteArray(size)
        val copyLength = minOf(raw.size, size)
        raw.copyInto(
            destination = padded,
            destinationOffset = size - copyLength,
            startIndex = raw.size - copyLength,
        )
        return byteArrayOf((0x10 or log).toByte()) + padded
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()

    private val SIGNED_INT64_MINIMUM: java.math.BigInteger =
        java.math.BigInteger.valueOf(Long.MIN_VALUE)
    private val SIGNED_INT64_MAXIMUM: java.math.BigInteger =
        java.math.BigInteger.valueOf(Long.MAX_VALUE)
    private val SIGNED_INT128_MINIMUM: java.math.BigInteger =
        java.math.BigInteger.ONE.shiftLeft(127).negate()
    private val SIGNED_INT128_MAXIMUM: java.math.BigInteger =
        java.math.BigInteger.ONE.shiftLeft(127).subtract(java.math.BigInteger.ONE)

    private class Decoder(
        private val bytes: ByteArray,
        private val limits: BplistDecodeLimits,
        private val magic: ByteArray,
        private val onDecodedData: ((ByteArray) -> Unit)? = null,
    ) {
        private lateinit var offsets: IntArray
        private lateinit var sortedOffsets: IntArray
        private lateinit var states: ByteArray
        private lateinit var decodedObjects: Array<Any?>
        private var offsetTable = 0
        private var referenceSize = 0
        private var decodedBytes = 0L

        fun decode(): Any? {
            if (bytes.size > limits.maxInputBytes) malformed("input exceeds ${limits.maxInputBytes} bytes")
            if (bytes.size < MINIMUM_FILE_BYTES) malformed("too short")
            for (index in magic.indices) {
                if (bytes[index] != magic[index]) malformed("bad magic")
            }

            val trailer = bytes.size - TRAILER_BYTES
            val offsetSize = bytes[trailer + 6].toInt() and 0xff
            referenceSize = bytes[trailer + 7].toInt() and 0xff
            validateIntegerSize(offsetSize, limits.maxOffsetSize, "offset")
            validateIntegerSize(referenceSize, limits.maxReferenceSize, "reference")

            val objectCount = readUnsignedBounded(
                trailer + 8,
                8,
                limits.maxObjects.toLong(),
                "object count",
            ).toInt()
            if (objectCount == 0) malformed("object count must be positive")
            val topObject = readUnsignedBounded(
                trailer + 16,
                8,
                (objectCount - 1).toLong(),
                "top object index",
            ).toInt()
            offsetTable = readUnsignedBounded(
                trailer + 24,
                8,
                trailer.toLong(),
                "offset table position",
            ).toInt()
            if (offsetTable < magic.size) malformed("offset table overlaps the header")

            val tableBytes = checkedMultiply(objectCount, offsetSize, "offset table length")
            val tableEnd = checkedAdd(offsetTable, tableBytes, "offset table end")
            if (tableEnd != trailer) malformed("offset table does not end at the trailer")

            offsets = IntArray(objectCount)
            for (index in offsets.indices) {
                val entry = checkedAdd(
                    offsetTable,
                    checkedMultiply(index, offsetSize, "offset entry position"),
                    "offset entry position",
                )
                val offset = readUnsignedBounded(
                    entry,
                    offsetSize,
                    (offsetTable - 1).toLong(),
                    "object offset",
                ).toInt()
                if (offset < magic.size) malformed("object offset $offset overlaps the header")
                offsets[index] = offset
            }

            sortedOffsets = offsets.copyOf().also { it.sort() }
            for (index in 1 until sortedOffsets.size) {
                if (sortedOffsets[index] == sortedOffsets[index - 1]) {
                    malformed("duplicate object offset ${sortedOffsets[index]}")
                }
            }
            states = ByteArray(objectCount)
            decodedObjects = arrayOfNulls(objectCount)
            return readObject(topObject, depth = 0)
        }

        private fun readObject(index: Int, depth: Int): Any? {
            if (index !in offsets.indices) malformed("object reference $index is out of range")
            if (depth > limits.maxNestingDepth) {
                malformed("nesting depth exceeds ${limits.maxNestingDepth}")
            }
            when (states[index]) {
                STATE_VISITING -> malformed("cyclic object reference at index $index")
                STATE_DECODED -> return decodedObjects[index]
            }

            val start = offsets[index]
            val sortedIndex = sortedOffsets.binarySearch(start)
            if (sortedIndex < 0) malformed("missing object boundary for index $index")
            val end = if (sortedIndex + 1 < sortedOffsets.size) {
                sortedOffsets[sortedIndex + 1]
            } else {
                offsetTable
            }
            val objectLength = end - start
            if (objectLength <= 0) malformed("object $index has an empty slice")
            if (objectLength > limits.maxObjectBytes) {
                malformed("object $index exceeds ${limits.maxObjectBytes} bytes")
            }

            states[index] = STATE_VISITING
            try {
                val cursor = ObjectCursor(start, end)
                val marker = cursor.readByte("object marker")
                val type = marker ushr 4
                val nibble = marker and 0x0f
                val value = when (type) {
                    0x0 -> when (nibble) {
                        0x08 -> false.also { consumeDecodedBytes(1, "boolean") }
                        0x09 -> true.also { consumeDecodedBytes(1, "boolean") }
                        else -> malformed("unsupported primitive 0x0${nibble.toString(16)}")
                    }
                    0x1 -> {
                        val size = markerSize(nibble, "integer", maxExponent = 4)
                        consumeDecodedBytes(size.toLong(), "integer")
                        if (size == 16) cursor.readSignedInt128() else cursor.readRawLong(size, "integer")
                    }
                    0x2 -> {
                        val size = markerSize(nibble, "real")
                        consumeDecodedBytes(size.toLong(), "real")
                        when (size) {
                            4 -> Float.fromBits(cursor.readRawLong(size, "real").toInt())
                            8 -> Double.fromBits(cursor.readRawLong(size, "real"))
                            else -> malformed("unsupported real size $size")
                        }
                    }
                    0x4 -> {
                        val count = cursor.readCount(nibble)
                        checkLimit(count, limits.maxDataBytes, "data length")
                        cursor.requireBytes(count, "data payload")
                        consumeDecodedBytes(count.toLong(), "data")
                        bytes.copyOfRange(cursor.position, cursor.position + count).also { data ->
                            try {
                                onDecodedData?.invoke(data)
                            } catch (failure: Throwable) {
                                data.fill(0)
                                throw failure
                            }
                        }
                    }
                    0x5 -> {
                        val count = cursor.readCount(nibble)
                        checkLimit(count, limits.maxStringBytes, "ASCII string length")
                        cursor.requireBytes(count, "ASCII string")
                        consumeDecodedBytes(count.toLong(), "ASCII string")
                        String(bytes, cursor.position, count, Charsets.US_ASCII)
                    }
                    0x6 -> {
                        val codeUnits = cursor.readCount(nibble)
                        val byteCount = checkedMultiply(codeUnits, 2, "UTF-16 string length")
                        checkLimit(byteCount, limits.maxStringBytes, "UTF-16 string length")
                        cursor.requireBytes(byteCount, "UTF-16 string")
                        consumeDecodedBytes(byteCount.toLong(), "UTF-16 string")
                        String(bytes, cursor.position, byteCount, Charsets.UTF_16BE)
                    }
                    0xa -> decodeArray(cursor, nibble, depth)
                    0xd -> decodeDictionary(cursor, nibble, depth)
                    else -> malformed("unsupported object type 0x${type.toString(16)}")
                }
                decodedObjects[index] = value
                states[index] = STATE_DECODED
                return value
            } finally {
                if (states[index] == STATE_VISITING) states[index] = STATE_UNSEEN
            }
        }

        private fun decodeArray(cursor: ObjectCursor, nibble: Int, depth: Int): List<Any?> {
            val count = cursor.readCount(nibble)
            checkLimit(count, limits.maxContainerItems, "array item count")
            val referenceBytes = checkedMultiply(count, referenceSize, "array reference bytes")
            cursor.requireBytes(referenceBytes, "array references")
            consumeDecodedBytes(checkedMultiplyLong(count.toLong(), 8L, "array allocation"), "array")

            val array = ArrayList<Any?>(count)
            for (item in 0 until count) {
                val reference = cursor.readReferenceAt(item, count)
                array.add(readObject(reference, depth + 1))
            }
            return array
        }

        private fun decodeDictionary(
            cursor: ObjectCursor,
            nibble: Int,
            depth: Int,
        ): Map<String, Any?> {
            val count = cursor.readCount(nibble)
            checkLimit(count, limits.maxContainerItems, "dictionary item count")
            val referenceCount = checkedMultiply(count, 2, "dictionary reference count")
            val referenceBytes = checkedMultiply(referenceCount, referenceSize, "dictionary reference bytes")
            cursor.requireBytes(referenceBytes, "dictionary references")
            consumeDecodedBytes(
                checkedMultiplyLong(count.toLong(), 16L, "dictionary allocation"),
                "dictionary",
            )

            val dictionary = LinkedHashMap<String, Any?>(count)
            for (item in 0 until count) {
                val keyReference = cursor.readReferenceAt(item, referenceCount)
                val valueReference = cursor.readReferenceAt(count + item, referenceCount)
                val key = readObject(keyReference, depth + 1) as? String
                    ?: malformed("dictionary key is not a string")
                if (dictionary.containsKey(key)) malformed("duplicate dictionary key")
                dictionary[key] = readObject(valueReference, depth + 1)
            }
            return dictionary
        }

        private fun markerSize(exponent: Int, kind: String, maxExponent: Int = 3): Int {
            if (exponent !in 0..maxExponent) malformed("unsupported $kind size exponent $exponent")
            return 1 shl exponent
        }

        private fun validateIntegerSize(size: Int, maximum: Int, kind: String) {
            if (size !in SUPPORTED_INTEGER_SIZES || size > maximum) {
                malformed("invalid $kind size $size")
            }
        }

        private fun readUnsignedBounded(
            position: Int,
            size: Int,
            maximum: Long,
            field: String,
        ): Long {
            requireFileRange(position, size, field)
            var value = 0L
            for (index in 0 until size) {
                val next = bytes[position + index].toLong() and 0xffL
                if (next > maximum || value > (maximum - next) / 256L) {
                    malformed("$field is out of range")
                }
                value = value * 256L + next
            }
            return value
        }

        private fun requireFileRange(position: Int, length: Int, field: String) {
            if (position < 0 || length < 0 || position > bytes.size || length > bytes.size - position) {
                malformed("truncated $field")
            }
        }

        private fun checkLimit(value: Int, maximum: Int, field: String) {
            if (value > maximum) malformed("$field exceeds $maximum")
        }

        private fun consumeDecodedBytes(amount: Long, field: String) {
            if (amount < 0 || amount > limits.maxDecodedBytes - decodedBytes) {
                malformed("decoded byte budget exceeded while reading $field")
            }
            decodedBytes += amount
        }

        private fun checkedMultiply(left: Int, right: Int, field: String): Int {
            if (left < 0 || right < 0 || (left != 0 && right > Int.MAX_VALUE / left)) {
                malformed("$field overflows")
            }
            return left * right
        }

        private fun checkedAdd(left: Int, right: Int, field: String): Int {
            if (left < 0 || right < 0 || left > Int.MAX_VALUE - right) malformed("$field overflows")
            return left + right
        }

        private fun checkedMultiplyLong(left: Long, right: Long, field: String): Long {
            if (left < 0 || right < 0 || (left != 0L && right > Long.MAX_VALUE / left)) {
                malformed("$field overflows")
            }
            return left * right
        }

        private inner class ObjectCursor(
            var position: Int,
            private val end: Int,
        ) {
            fun readByte(field: String): Int {
                requireBytes(1, field)
                return bytes[position++].toInt() and 0xff
            }

            fun readCount(nibble: Int): Int {
                if (nibble != 0x0f) return nibble
                val countMarker = readByte("extended count marker")
                if (countMarker ushr 4 != 0x1) malformed("extended count is not an integer")
                val size = markerSize(countMarker and 0x0f, "extended count")
                val count = readUnsignedBoundedInSlice(size, Int.MAX_VALUE.toLong(), "extended count")
                return count.toInt()
            }

            fun readRawLong(size: Int, field: String): Long {
                requireBytes(size, field)
                var value = 0L
                repeat(size) {
                    value = (value shl 8) or (bytes[position++].toLong() and 0xffL)
                }
                return value
            }

            fun readSignedInt128(): Any {
                requireBytes(16, "integer")
                val value = java.math.BigInteger(bytes.copyOfRange(position, position + 16))
                position += 16
                return if (value >= SIGNED_INT64_MINIMUM && value <= SIGNED_INT64_MAXIMUM) {
                    value.toLong()
                } else {
                    value
                }
            }

            fun readReferenceAt(referenceIndex: Int, totalReferences: Int): Int {
                if (referenceIndex !in 0 until totalReferences) malformed("invalid reference position")
                val relative = checkedMultiply(referenceIndex, referenceSize, "reference position")
                val referencePosition = checkedAdd(position, relative, "reference position")
                requireRange(referencePosition, referenceSize, "object reference")
                return readUnsignedBounded(
                    referencePosition,
                    referenceSize,
                    (offsets.size - 1).toLong(),
                    "object reference",
                ).toInt()
            }

            fun requireBytes(length: Int, field: String) = requireRange(position, length, field)

            private fun readUnsignedBoundedInSlice(size: Int, maximum: Long, field: String): Long {
                requireBytes(size, field)
                var value = 0L
                repeat(size) {
                    val next = bytes[position++].toLong() and 0xffL
                    if (next > maximum || value > (maximum - next) / 256L) {
                        malformed("$field is out of range")
                    }
                    value = value * 256L + next
                }
                return value
            }

            private fun requireRange(start: Int, length: Int, field: String) {
                if (start < position || length < 0 || start > end || length > end - start) {
                    malformed("$field exceeds its object slice")
                }
            }
        }

        private fun malformed(message: String): Nothing =
            throw IllegalArgumentException("bplist: $message")

        private companion object {
            const val TRAILER_BYTES = 32
            const val MINIMUM_FILE_BYTES = 40
            const val STATE_UNSEEN: Byte = 0
            const val STATE_VISITING: Byte = 1
            const val STATE_DECODED: Byte = 2
            val SUPPORTED_INTEGER_SIZES = intArrayOf(1, 2, 4, 8)
        }
    }
}

/** One-shot owner for decoded bplist Data copies; the source wire body is owned separately. */
internal class OwnedDecodedPlist internal constructor() {
    private val dataCopies = ArrayList<ByteArray>()
    private var terminal = false
    private var decodedRoot: Any? = null

    val root: Any?
        get() = decodedRoot

    internal fun setRoot(root: Any?) {
        check(!terminal) { "bplist: decoded Data ownership already terminated" }
        decodedRoot = root
    }

    internal fun ownData(data: ByteArray) {
        check(!terminal) { "bplist: decoded Data ownership already terminated" }
        try {
            dataCopies.add(data)
        } catch (failure: Throwable) {
            data.fill(0)
            throw failure
        }
    }

    /** Wipe only Data identities decoded by this scope and selected by the caller. */
    fun wipeSelectedByIdentity(selected: Collection<ByteArray>) {
        if (terminal || selected.isEmpty()) return
        val identities = java.util.IdentityHashMap<ByteArray, Boolean>(selected.size)
        selected.forEach { identities[it] = true }
        dataCopies.forEach { data ->
            if (identities.containsKey(data)) data.fill(0)
        }
    }

    /** Wipe all remaining decoded Data copies. Repeated calls are harmless. */
    fun release() {
        if (terminal) return
        terminal = true
        dataCopies.forEach { it.fill(0) }
        dataCopies.clear()
    }

    /** Relinquish ownership without wiping data that normal SETUP dispatch still needs. */
    fun detachWithoutWipe() {
        if (terminal) return
        terminal = true
        dataCopies.clear()
    }
}

/** Resource limits for decoding untrusted binary property lists. */
internal data class BplistDecodeLimits(
    val maxInputBytes: Int = 16 * 1024 * 1024,
    val maxObjects: Int = 200_000,
    val maxOffsetSize: Int = 8,
    val maxReferenceSize: Int = 8,
    val maxObjectBytes: Int = 8 * 1024 * 1024 + 32,
    val maxNestingDepth: Int = 64,
    val maxContainerItems: Int = 100_000,
    val maxDataBytes: Int = 8 * 1024 * 1024,
    val maxStringBytes: Int = 8 * 1024 * 1024,
    val maxDecodedBytes: Long = 32L * 1024L * 1024L,
) {
    init {
        require(maxInputBytes >= 0) { "bplist: maxInputBytes must not be negative" }
        require(maxObjects > 0) { "bplist: maxObjects must be positive" }
        require(maxOffsetSize in 1..8) { "bplist: maxOffsetSize must be between 1 and 8" }
        require(maxReferenceSize in 1..8) { "bplist: maxReferenceSize must be between 1 and 8" }
        require(maxObjectBytes > 0) { "bplist: maxObjectBytes must be positive" }
        require(maxNestingDepth >= 0) { "bplist: maxNestingDepth must not be negative" }
        require(maxContainerItems >= 0) { "bplist: maxContainerItems must not be negative" }
        require(maxDataBytes >= 0) { "bplist: maxDataBytes must not be negative" }
        require(maxStringBytes >= 0) { "bplist: maxStringBytes must not be negative" }
        require(maxDecodedBytes >= 0) { "bplist: maxDecodedBytes must not be negative" }
    }
}
