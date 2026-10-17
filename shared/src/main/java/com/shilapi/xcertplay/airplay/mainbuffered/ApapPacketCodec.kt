package com.shilapi.xcertplay.airplay.mainbuffered

import java.math.BigInteger
import java.util.Collections

/** A malformed or resource-exhausting APAP wire value. */
class ApapProtocolException(message: String) : IllegalArgumentException(message)

/** Canonical unsigned, big-endian base-128 variable integers used by APAP extensions. */
object ApapUIntVCodec {
    const val MAX_ENCODED_BYTES = 10

    private val MAX_VALUE = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    private val SEVEN_BITS = BigInteger.valueOf(0x7fL)

    data class Decoded(
        val value: BigInteger,
        val nextOffset: Int,
    )

    fun encode(value: Long): ByteArray {
        require(value >= 0L) { "UIntV value must be unsigned" }
        return encode(BigInteger.valueOf(value))
    }

    fun encode(value: BigInteger): ByteArray {
        require(value.signum() >= 0 && value <= MAX_VALUE) {
            "UIntV value must fit in UInt64"
        }
        if (value.signum() == 0) return byteArrayOf(0)

        val groupCount = (value.bitLength() + 6) / 7
        val output = ByteArray(groupCount)
        var remaining = value
        for (index in groupCount - 1 downTo 0) {
            output[index] = remaining.and(SEVEN_BITS).toByte()
            remaining = remaining.shiftRight(7)
        }
        for (index in 0 until output.lastIndex) {
            output[index] = (output[index].toInt() or 0x80).toByte()
        }
        return output
    }

    /**
     * Decodes one UIntV from [offset] up to, but not including, [limit].
     *
     * Encodings with a redundant leading zero group are rejected rather than normalized. This
     * makes every UInt64 have exactly one accepted wire representation.
     */
    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        limit: Int = bytes.size,
    ): Decoded {
        if (offset < 0 || limit < offset || limit > bytes.size) {
            throw ApapProtocolException("UIntV range $offset..$limit is outside ${bytes.size} bytes")
        }
        if (offset == limit) throw ApapProtocolException("truncated UIntV")

        var cursor = offset
        var value = BigInteger.ZERO
        var encodedBytes = 0
        while (cursor < limit) {
            if (encodedBytes == MAX_ENCODED_BYTES) {
                throw ApapProtocolException("UIntV exceeds UInt64")
            }
            val byte = bytes[cursor++].toInt() and 0xff
            val payload = byte and 0x7f
            if (encodedBytes == 0 && payload == 0 && byte and 0x80 != 0) {
                throw ApapProtocolException("UIntV is not shortest-form canonical")
            }
            value = value.shiftLeft(7).or(BigInteger.valueOf(payload.toLong()))
            if (value > MAX_VALUE) throw ApapProtocolException("UIntV exceeds UInt64")
            encodedBytes++
            if (byte and 0x80 == 0) return Decoded(value, cursor)
        }
        throw ApapProtocolException("truncated UIntV")
    }
}

/** The exact 15-byte clear APAP media header. */
data class ApapMediaHeader(
    val mediaTimeValue: Long,
    /** Unsigned 32-bit wire value retained in a Long. */
    val mediaTimeScale: Long,
    /** Unsigned 24-bit APAP sequence value. */
    val sequence: Int,
) {
    init {
        require(mediaTimeScale in 0L..UINT32_MAX) { "mediaTimeScale must be a UInt32" }
        require(sequence in 0..UINT24_MAX) { "sequence must be a UInt24" }
    }

    companion object {
        private const val UINT24_MAX = 0x00ff_ffff
        private const val UINT32_MAX = 0xffff_ffffL
    }
}

/** One opaque APAP extension. No meaning is assigned to unverified extension identifiers. */
class ApapExtension(
    val id: BigInteger,
    data: ByteArray,
) {
    private val storedData = data.copyOf()

    constructor(id: Long, data: ByteArray) : this(BigInteger.valueOf(id), data)

    val data: ByteArray
        get() = storedData.copyOf()

    val dataSize: Int
        get() = storedData.size

    init {
        require(id.signum() > 0 && id.bitLength() <= 64) {
            "APAP extension id must be a non-zero UInt64"
        }
    }

    internal fun copyDataInto(target: ByteArray, offset: Int) {
        storedData.copyInto(target, offset)
    }

    internal fun storedDataCopy(): ByteArray = storedData.copyOf()
}

/** A detached APAP packet. Construction itself is bounded even for a hostile Iterable. */
class ApapPacket(
    val header: ApapMediaHeader,
    extensions: Iterable<ApapExtension>,
    mediaPayload: ByteArray,
) {
    private val storedExtensions: List<ApapExtension> = snapshotExtensions(extensions)
    private val storedMediaPayload = mediaPayload.copyOf()

    val extensions: List<ApapExtension>
        get() = storedExtensions

    val mediaPayload: ByteArray
        get() = storedMediaPayload.copyOf()

    init {
        require(mediaPayload.size <= HARD_MAX_PACKET_BYTES) {
            "APAP media payload exceeds $HARD_MAX_PACKET_BYTES bytes"
        }
    }

    internal fun mediaPayloadSize(): Int = storedMediaPayload.size

    internal fun copyMediaPayloadInto(target: ByteArray, offset: Int) {
        storedMediaPayload.copyInto(target, offset)
    }

    private fun snapshotExtensions(source: Iterable<ApapExtension>): List<ApapExtension> {
        val result = ArrayList<ApapExtension>()
        var totalBytes = 0L
        val iterator = source.iterator()
        while (iterator.hasNext()) {
            if (result.size >= HARD_MAX_EXTENSIONS) {
                throw IllegalArgumentException(
                    "APAP extension count exceeds hard limit $HARD_MAX_EXTENSIONS",
                )
            }
            val extension = iterator.next()
            totalBytes += extension.dataSize.toLong()
            require(totalBytes <= HARD_MAX_PACKET_BYTES) {
                "APAP extension data exceeds hard limit $HARD_MAX_PACKET_BYTES bytes"
            }
            result += ApapExtension(extension.id, extension.storedDataCopy())
        }
        return Collections.unmodifiableList(result)
    }

    companion object {
        internal const val HARD_MAX_EXTENSIONS = 1_024
        internal const val HARD_MAX_PACKET_BYTES = 16 * 1024 * 1024
    }
}

data class ApapCodecLimits(
    val maxPacketBytes: Int = 4 * 1024 * 1024,
    val maxExtensions: Int = 64,
    val maxExtensionBytes: Int = 256 * 1024,
    val maxTotalExtensionBytes: Int = 1024 * 1024,
) {
    init {
        require(maxPacketBytes >= ApapPacketCodec.MIN_PACKET_BYTES)
        require(maxPacketBytes <= ApapPacket.HARD_MAX_PACKET_BYTES)
        require(maxExtensions in 0..ApapPacket.HARD_MAX_EXTENSIONS)
        require(maxExtensionBytes >= 0 && maxExtensionBytes <= maxPacketBytes)
        require(maxTotalExtensionBytes >= 0 && maxTotalExtensionBytes <= maxPacketBytes)
    }
}

/**
 * Strict detached APAP payload codec: 15-byte header, extensions, one id-zero terminator, then
 * media. This wire codec does not advertise or establish main-buffered runtime readiness.
 */
object ApapPacketCodec {
    const val HEADER_BYTES = 15
    const val MIN_PACKET_BYTES = HEADER_BYTES + 1

    fun encode(
        packet: ApapPacket,
        limits: ApapCodecLimits = ApapCodecLimits(),
    ): ByteArray {
        var extensionBytes = 0L
        var encodedBytes = MIN_PACKET_BYTES.toLong() + packet.mediaPayloadSize()
        var extensionCount = 0
        for (extension in packet.extensions) {
            extensionCount++
            if (extensionCount > limits.maxExtensions) {
                throw ApapProtocolException(
                    "APAP extension count exceeds ${limits.maxExtensions}",
                )
            }
            if (extension.dataSize > limits.maxExtensionBytes) {
                throw ApapProtocolException(
                    "APAP extension data exceeds ${limits.maxExtensionBytes} bytes",
                )
            }
            extensionBytes += extension.dataSize.toLong()
            if (extensionBytes > limits.maxTotalExtensionBytes) {
                throw ApapProtocolException(
                    "APAP total extension data exceeds ${limits.maxTotalExtensionBytes} bytes",
                )
            }
            encodedBytes += ApapUIntVCodec.encode(extension.id).size.toLong()
            encodedBytes += ApapUIntVCodec.encode(extension.dataSize.toLong()).size.toLong()
            encodedBytes += extension.dataSize.toLong()
            if (encodedBytes > limits.maxPacketBytes) {
                throw ApapProtocolException("APAP packet exceeds ${limits.maxPacketBytes} bytes")
            }
        }
        if (encodedBytes > limits.maxPacketBytes) {
            throw ApapProtocolException("APAP packet exceeds ${limits.maxPacketBytes} bytes")
        }

        val output = ByteArray(encodedBytes.toInt())
        putI64Be(output, 0, packet.header.mediaTimeValue)
        putU32Be(output, 8, packet.header.mediaTimeScale)
        putU24Be(output, 12, packet.header.sequence)
        var cursor = HEADER_BYTES
        for (extension in packet.extensions) {
            val id = ApapUIntVCodec.encode(extension.id)
            val length = ApapUIntVCodec.encode(extension.dataSize.toLong())
            id.copyInto(output, cursor)
            cursor += id.size
            length.copyInto(output, cursor)
            cursor += length.size
            extension.copyDataInto(output, cursor)
            cursor += extension.dataSize
        }
        output[cursor++] = 0 // The single extension-list terminator.
        packet.copyMediaPayloadInto(output, cursor)
        return output
    }

    fun decode(
        bytes: ByteArray,
        limits: ApapCodecLimits = ApapCodecLimits(),
    ): ApapPacket {
        if (bytes.size < MIN_PACKET_BYTES) {
            throw ApapProtocolException("APAP packet is shorter than $MIN_PACKET_BYTES bytes")
        }
        if (bytes.size > limits.maxPacketBytes) {
            throw ApapProtocolException("APAP packet exceeds ${limits.maxPacketBytes} bytes")
        }

        val header = ApapMediaHeader(
            mediaTimeValue = readI64Be(bytes, 0),
            mediaTimeScale = readU32Be(bytes, 8),
            sequence = readU24Be(bytes, 12),
        )
        val extensions = ArrayList<ApapExtension>(minOf(limits.maxExtensions, 8))
        var cursor = HEADER_BYTES
        var totalExtensionBytes = 0L
        while (true) {
            val id = ApapUIntVCodec.decode(bytes, cursor)
            cursor = id.nextOffset
            if (id.value.signum() == 0) break
            if (extensions.size >= limits.maxExtensions) {
                throw ApapProtocolException(
                    "APAP extension count exceeds ${limits.maxExtensions}",
                )
            }
            val length = ApapUIntVCodec.decode(bytes, cursor)
            cursor = length.nextOffset
            if (length.value > BigInteger.valueOf(limits.maxExtensionBytes.toLong())) {
                throw ApapProtocolException(
                    "APAP extension data exceeds ${limits.maxExtensionBytes} bytes",
                )
            }
            val dataBytes = length.value.toInt()
            totalExtensionBytes += dataBytes.toLong()
            if (totalExtensionBytes > limits.maxTotalExtensionBytes) {
                throw ApapProtocolException(
                    "APAP total extension data exceeds ${limits.maxTotalExtensionBytes} bytes",
                )
            }
            if (cursor > bytes.size - dataBytes) {
                throw ApapProtocolException("truncated APAP extension data")
            }
            extensions += ApapExtension(id.value, bytes.copyOfRange(cursor, cursor + dataBytes))
            cursor += dataBytes
        }
        return ApapPacket(header, extensions, bytes.copyOfRange(cursor, bytes.size))
    }

    private fun readI64Be(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
        }
        return value
    }

    private fun readU32Be(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    private fun readU24Be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 16) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            (bytes[offset + 2].toInt() and 0xff)

    private fun putI64Be(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) {
            target[offset + index] = (value ushr (56 - index * 8)).toByte()
        }
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) {
            target[offset + index] = (value ushr (24 - index * 8)).toByte()
        }
    }

    private fun putU24Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 16).toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = value.toByte()
    }
}

/** Exact one-record BufferedAPAP TCP framing; the BE u32 length includes its four-byte prefix. */
object BufferedApapTcpCodec {
    const val PREFIX_BYTES = 4
    const val DEFAULT_MAX_FRAME_BYTES = 4 * 1024 * 1024

    fun encode(
        payload: ByteArray,
        maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    ): ByteArray {
        requireValidMaximum(maxFrameBytes)
        val totalBytes = payload.size.toLong() + PREFIX_BYTES
        require(totalBytes <= maxFrameBytes) {
            "BufferedAPAP frame size $totalBytes exceeds $maxFrameBytes"
        }
        val output = ByteArray(totalBytes.toInt())
        putU32Be(output, totalBytes)
        payload.copyInto(output, PREFIX_BYTES)
        return output
    }

    fun decodeExact(
        frame: ByteArray,
        maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    ): ByteArray {
        requireValidMaximum(maxFrameBytes)
        if (frame.size < PREFIX_BYTES) {
            throw ApapProtocolException("truncated BufferedAPAP length prefix")
        }
        val declared = readU32Be(frame)
        if (declared < PREFIX_BYTES) {
            throw ApapProtocolException(
                "BufferedAPAP total length $declared is smaller than $PREFIX_BYTES",
            )
        }
        if (declared > maxFrameBytes) {
            throw ApapProtocolException(
                "BufferedAPAP total length $declared exceeds $maxFrameBytes",
            )
        }
        if (declared != frame.size.toLong()) {
            throw ApapProtocolException(
                "BufferedAPAP total length $declared does not exactly match ${frame.size} bytes",
            )
        }
        return frame.copyOfRange(PREFIX_BYTES, frame.size)
    }

    private fun readU32Be(bytes: ByteArray): Long =
        ((bytes[0].toLong() and 0xffL) shl 24) or
            ((bytes[1].toLong() and 0xffL) shl 16) or
            ((bytes[2].toLong() and 0xffL) shl 8) or
            (bytes[3].toLong() and 0xffL)

    private fun putU32Be(target: ByteArray, value: Long) {
        target[0] = (value ushr 24).toByte()
        target[1] = (value ushr 16).toByte()
        target[2] = (value ushr 8).toByte()
        target[3] = value.toByte()
    }

    private fun requireValidMaximum(maxFrameBytes: Int) {
        require(maxFrameBytes >= PREFIX_BYTES) {
            "maxFrameBytes must be at least $PREFIX_BYTES"
        }
    }
}
