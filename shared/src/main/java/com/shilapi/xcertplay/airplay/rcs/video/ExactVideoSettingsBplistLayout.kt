package com.shilapi.xcertplay.airplay.rcs.video

/** BplistCodec validates references and the trailer, but does not consume every object slice. */
internal object ExactVideoSettingsBplistLayout {
    private const val HEADER_BYTES = 8
    private const val TRAILER_BYTES = 32
    private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)
    private val SIZES = setOf(1, 2, 4, 8)

    fun requireComplete(bytes: ByteArray, limits: CarPlayVideoSettingsLimits) {
        if (bytes.size > limits.maxPlistBytes || bytes.size < HEADER_BYTES + TRAILER_BYTES ||
            !bytes.copyOfRange(0, HEADER_BYTES).contentEquals(MAGIC)
        ) invalid()
        val trailer = bytes.size - TRAILER_BYTES
        val offsetSize = bytes[trailer + 6].toInt() and 0xff
        val referenceSize = bytes[trailer + 7].toInt() and 0xff
        if (offsetSize !in SIZES || referenceSize !in SIZES) invalid()
        val count = readUnsigned(bytes, trailer + 8, 8, limits.maxObjects.toLong()).toInt()
        if (count == 0) invalid()
        val table = readUnsigned(bytes, trailer + 24, 8, trailer.toLong()).toInt()
        if (table < HEADER_BYTES || table.toLong() + count.toLong() * offsetSize != trailer.toLong()) {
            invalid()
        }
        val offsets = IntArray(count) { index ->
            readUnsigned(bytes, table + index * offsetSize, offsetSize, (table - 1).toLong()).toInt()
        }.also { it.sort() }
        if (offsets.first() != HEADER_BYTES) invalid()
        offsets.forEachIndexed { index, start ->
            val end = if (index + 1 < offsets.size) offsets[index + 1] else table
            if (start >= end || serializedLength(bytes, start, end, referenceSize) != (end - start).toLong()) {
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
        throw CarPlayVideoSettingsProtocolException("Invalid VideoSettings binary plist layout")
}
