package com.shilapi.xcertplay.airplay.video

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.BplistDecodeLimits
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

class CarPlayVideoOverlayProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Bounds for one VideoOverlayUI RCS application body. These are local safety limits, not wire facts. */
data class CarPlayVideoOverlayLimits(
    val maximumMessageBytes: Int = 1024 * 1024,
    val maximumObjects: Int = 4096,
    val maximumNestingDepth: Int = 24,
    val maximumContainerItems: Int = 1024,
    val maximumDataBytes: Int = 512 * 1024,
    val maximumStringBytes: Int = 64 * 1024,
    val maximumDecodedBytes: Long = maximumMessageBytes.toLong() * 2L,
) {
    init {
        require(maximumMessageBytes in 40..(4 * 1024 * 1024))
        require(maximumObjects in 1..16_384)
        require(maximumNestingDepth in 1..64)
        require(maximumContainerItems in 1..4096)
        require(maximumDataBytes in 1..maximumMessageBytes)
        require(maximumStringBytes in 1..maximumMessageBytes)
        require(maximumDecodedBytes in 1L..(8L * 1024L * 1024L))
    }

    internal fun decodeLimits(): BplistDecodeLimits = BplistDecodeLimits(
        maxInputBytes = maximumMessageBytes,
        maxObjects = maximumObjects,
        maxObjectBytes = maximumMessageBytes,
        maxNestingDepth = maximumNestingDepth,
        maxContainerItems = maximumContainerItems,
        maxDataBytes = maximumDataBytes,
        maxStringBytes = maximumStringBytes,
        maxDecodedBytes = maximumDecodedBytes,
    )
}

/**
 * A received opaque dictionary. Each read returns a deep defensive copy, including nested NSData.
 * Deliberately no data-class `toString`: identifiers and payloads must not enter logs by accident.
 */
class CarPlayVideoOverlayMessage private constructor(
    private val ownedFields: Map<String, Any?>,
) {
    val fields: Map<String, Any?>
        get() = OverlayValueSnapshot.copyOwned(ownedFields)

    fun field(name: String): Any? = ownedFields[name]?.let(OverlayValueSnapshot::copyOwnedValue)

    override fun toString(): String = "CarPlayVideoOverlayMessage(fieldCount=${ownedFields.size})"

    companion object {
        internal fun fromDecoded(fields: Map<*, *>, limits: CarPlayVideoOverlayLimits) =
            CarPlayVideoOverlayMessage(OverlayValueSnapshot.copy(fields, limits))
    }
}

/**
 * Detached iOS 27 FitnessUIOverlay/CarPlayVideoOverlayUI application codec. Evidence establishes
 * one `bplist00` dictionary per RCS message and the send directions only. Action numbers, required
 * fields, reply sequencing and activation are not known, so fields remain opaque and no capability
 * or handler is registered here.
 */
class CarPlayVideoOverlayCodec(
    val limits: CarPlayVideoOverlayLimits = CarPlayVideoOverlayLimits(),
) {
    /** iPhone to accessory: AirPlayOverlaysServer serializes RenderActionProtocol as one NSData. */
    fun decodeFromPhone(rcsBody: ByteArray): CarPlayVideoOverlayMessage {
        if (rcsBody.size > limits.maximumMessageBytes) {
            throw CarPlayVideoOverlayProtocolException("Video overlay body exceeds byte limit")
        }
        val ownedBody = rcsBody.copyOf()
        try {
            ExactBplistLayout.requireComplete(ownedBody, limits)
            val decoded = BplistCodec.decode(ownedBody, limits.decodeLimits())
            val fields = decoded as? Map<*, *>
                ?: throw CarPlayVideoOverlayProtocolException(
                    "Video overlay body must be a dictionary",
                )
            return CarPlayVideoOverlayMessage.fromDecoded(fields, limits)
        } catch (error: CarPlayVideoOverlayProtocolException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw CarPlayVideoOverlayProtocolException("Invalid video overlay binary plist", error)
        }
    }

    /** Accessory to iPhone: the generic AirPlayKit receiver parses one NSData as a plist. */
    fun encodeToPhone(fields: Map<*, *>): ByteArray {
        val ownedFields = OverlayValueSnapshot.copy(fields, limits, rejectAmbiguousUnsigned = true)
        val encoded = try {
            BplistCodec.encode(ownedFields)
        } catch (error: IllegalArgumentException) {
            throw CarPlayVideoOverlayProtocolException("Invalid video overlay dictionary", error)
        }
        if (encoded.size > limits.maximumMessageBytes) {
            throw CarPlayVideoOverlayProtocolException("Video overlay body exceeds byte limit")
        }
        return encoded
    }
}

private object OverlayValueSnapshot {
    fun copy(
        fields: Map<*, *>,
        limits: CarPlayVideoOverlayLimits,
        rejectAmbiguousUnsigned: Boolean = false,
    ): Map<String, Any?> = Copier(limits, rejectAmbiguousUnsigned).copyMap(fields, depth = 0)

    // These copies are from an already bounded, acyclic, private tree. Returned byte arrays are
    // never the arrays retained inside CarPlayVideoOverlayMessage.
    fun copyOwned(fields: Map<String, Any?>): Map<String, Any?> = Collections.unmodifiableMap(
        LinkedHashMap<String, Any?>(fields.size).apply {
            fields.forEach { (key, child) -> put(key, copyOwnedValue(requireNotNull(child))) }
        },
    )

    fun copyOwnedValue(value: Any): Any = when (value) {
        is ByteArray -> value.copyOf()
        is List<*> -> Collections.unmodifiableList(value.map { copyOwnedValue(requireNotNull(it)) })
        is Map<*, *> -> Collections.unmodifiableMap(
            LinkedHashMap<String, Any?>().apply {
                value.forEach { (key, child) ->
                    put(key as String, copyOwnedValue(requireNotNull(child)))
                }
            },
        )
        else -> value
    }

    private class Copier(
        private val limits: CarPlayVideoOverlayLimits,
        private val rejectAmbiguousUnsigned: Boolean,
    ) {
        private val ancestors = IdentityHashMap<Any, Boolean>()
        private var objects = 0
        private var decodedBytes = 0L

        fun copyMap(fields: Map<*, *>, depth: Int): Map<String, Any?> {
            node(depth)
            return container(fields) { copyEntries(fields, depth) }
        }

        private fun copyValue(value: Any?, depth: Int): Any {
            node(depth)
            return when (value) {
                is Map<*, *> -> container(value) { copyEntries(value, depth) }
                is List<*> -> container(value) {
                    budget(value.size.toLong() * 8L)
                    Collections.unmodifiableList(value.map { copyValue(it, depth + 1) })
                }
                is ByteArray -> {
                    if (value.size > limits.maximumDataBytes) invalid("Video overlay data exceeds byte limit")
                    budget(value.size.toLong())
                    value.copyOf()
                }
                is String -> value.also(::string)
                is Boolean -> value.also { budget(1) }
                is Byte, is Short, is Int, is Long -> value.also { budget(8) }
                is Float, is Double -> value.also { budget(8) }
                is BigInteger -> {
                    if (value < SIGNED_INT128_MIN || value > SIGNED_INT128_MAX) {
                        invalid("Video overlay integer exceeds plist range")
                    }
                    // BplistCodec emits this positive range as an 8-byte integer, but its
                    // decoder reads the high bit as a negative Long. Reject it on send so an
                    // opaque value cannot silently change meaning across a round trip.
                    if (rejectAmbiguousUnsigned && value > SIGNED_INT64_MAX && value <= UINT64_MAX) {
                        invalid("Video overlay integer has ambiguous UInt64 encoding")
                    }
                    value.also { budget(16) }
                }
                else -> invalid("Video overlay contains an unsupported plist value")
            }
        }

        private fun copyEntries(fields: Map<*, *>, depth: Int): Map<String, Any?> {
            budget(fields.size.toLong() * 16L)
            val copy = LinkedHashMap<String, Any?>(fields.size)
            fields.forEach { (key, value) ->
                val stringKey = key as? String
                    ?: invalid("Video overlay dictionary keys must be strings")
                node(depth + 1)
                string(stringKey)
                copy[stringKey] = copyValue(value, depth + 1)
            }
            return Collections.unmodifiableMap(copy)
        }

        private fun string(value: String) {
            val byteCount = if (value.all { it.code <= 0x7f }) {
                value.length.toLong()
            } else {
                value.length.toLong() * 2L
            }
            if (byteCount > limits.maximumStringBytes) invalid("Video overlay string exceeds byte limit")
            budget(byteCount)
        }

        private fun node(depth: Int) {
            if (depth > limits.maximumNestingDepth) invalid("Video overlay nesting depth exceeded")
            objects++
            if (objects > limits.maximumObjects) invalid("Video overlay object count exceeded")
        }

        private fun <T> container(value: Any, block: () -> T): T {
            val size = when (value) {
                is Map<*, *> -> value.size
                is List<*> -> value.size
                else -> invalid("Video overlay contains an unsupported container")
            }
            if (size > limits.maximumContainerItems) {
                invalid("Video overlay collection size exceeded")
            }
            if (ancestors.put(value, true) != null) invalid("Video overlay contains a cycle")
            return try {
                block()
            } finally {
                ancestors.remove(value)
            }
        }

        private fun budget(amount: Long) {
            if (amount < 0 || amount > limits.maximumDecodedBytes - decodedBytes) {
                invalid("Video overlay decoded byte budget exceeded")
            }
            decodedBytes += amount
        }

        private fun invalid(message: String): Nothing =
            throw CarPlayVideoOverlayProtocolException(message)
    }

    private val SIGNED_INT128_MIN = BigInteger.ONE.shiftLeft(127).negate()
    private val SIGNED_INT128_MAX = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE)
    private val SIGNED_INT64_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    private val UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
}

/** BplistCodec checks the trailer and object references; this closes gaps within object slices. */
private object ExactBplistLayout {
    private const val HEADER_BYTES = 8
    private const val TRAILER_BYTES = 32

    fun requireComplete(bytes: ByteArray, limits: CarPlayVideoOverlayLimits) {
        if (bytes.size < HEADER_BYTES + TRAILER_BYTES ||
            !bytes.copyOfRange(0, HEADER_BYTES).contentEquals("bplist00".toByteArray(Charsets.US_ASCII))
        ) invalid()
        val trailer = bytes.size - TRAILER_BYTES
        val offsetSize = bytes[trailer + 6].toInt() and 0xff
        val referenceSize = bytes[trailer + 7].toInt() and 0xff
        if (offsetSize !in SIZES || referenceSize !in SIZES) invalid()
        val count = readUnsigned(bytes, trailer + 8, 8, limits.maximumObjects.toLong()).toInt()
        if (count == 0) invalid()
        val table = readUnsigned(bytes, trailer + 24, 8, trailer.toLong()).toInt()
        if (table < HEADER_BYTES || table.toLong() + count.toLong() * offsetSize != trailer.toLong()) {
            invalid()
        }
        val offsets = IntArray(count) { index ->
            readUnsigned(
                bytes,
                table + index * offsetSize,
                offsetSize,
                (table - 1).toLong(),
            ).toInt()
        }.also { it.sort() }
        if (offsets.first() != HEADER_BYTES) invalid()
        offsets.forEachIndexed { index, start ->
            val end = if (index + 1 < offsets.size) offsets[index + 1] else table
            if (start >= end ||
                serializedLength(bytes, start, end, referenceSize) != (end - start).toLong()
            ) {
                invalid()
            }
        }
    }

    private fun serializedLength(bytes: ByteArray, start: Int, end: Int, referenceSize: Int): Long {
        val marker = bytes[start].toInt() and 0xff
        val type = marker ushr 4
        val nibble = marker and 0x0f
        return when (type) {
            0x0 -> if (nibble == 8 || nibble == 9) 1L else invalid()
            0x1 -> if (nibble in 0..4) 1L + (1L shl nibble) else invalid()
            0x2 -> if (nibble == 2 || nibble == 3) 1L + (1L shl nibble) else invalid()
            0x4, 0x5, 0x6, 0xa, 0xd -> {
                val (count, headerBytes) = readCount(bytes, start, end, nibble)
                val itemBytes = when (type) {
                    0x6 -> 2L
                    0xa -> referenceSize.toLong()
                    0xd -> 2L * referenceSize
                    else -> 1L
                }
                headerBytes.toLong() + count * itemBytes
            }
            else -> invalid()
        }
    }

    private fun readCount(bytes: ByteArray, start: Int, end: Int, nibble: Int): Pair<Long, Int> {
        if (nibble != 0x0f) return nibble.toLong() to 1
        if (end - start < 2) invalid()
        val marker = bytes[start + 1].toInt() and 0xff
        if (marker ushr 4 != 0x1 || (marker and 0x0f) !in 0..3) invalid()
        val size = 1 shl (marker and 0x0f)
        if (end - start < 2 + size) invalid()
        return readUnsigned(bytes, start + 2, size, Int.MAX_VALUE.toLong()) to (2 + size)
    }

    private fun readUnsigned(bytes: ByteArray, start: Int, size: Int, maximum: Long): Long {
        if (start < 0 || size <= 0 || start > bytes.size || size > bytes.size - start) invalid()
        var result = 0L
        repeat(size) { index ->
            val next = bytes[start + index].toLong() and 0xffL
            if (next > maximum || result > (maximum - next) / 256L) invalid()
            result = result * 256L + next
        }
        return result
    }

    private fun invalid(): Nothing =
        throw CarPlayVideoOverlayProtocolException("Invalid video overlay binary plist layout")

    private val SIZES = setOf(1, 2, 4, 8)
}
