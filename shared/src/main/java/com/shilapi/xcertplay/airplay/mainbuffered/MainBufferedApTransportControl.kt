package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackage
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

/** Exact field view of the 32-byte APTransport control header used by main-buffered commands. */
class MainBufferedApTransportControlHeader private constructor(
    val totalLength: Long,
    val packageType: Int,
    /** Unsigned UInt64 bits, retained in a Long. */
    val groupId: Long,
    val messageType: Int,
    /** Unsigned UInt64 bits, retained in a Long. */
    val replyToken: Long,
    val replyStatus: Int,
) {
    fun packageTypeFourCcOrNull(): String? = fourCcOrNull(packageType)

    fun messageTypeFourCcOrNull(): String? = fourCcOrNull(messageType)

    internal fun headerTemplate(
        replacementPackageType: Int = packageType,
        replacementReplyStatus: Int = replyStatus,
    ): ByteArray = ByteArray(HEADER_BYTES).also { header ->
        putU32Be(header, PACKAGE_TYPE_OFFSET, replacementPackageType.toLong() and UINT32_MAX)
        putU64Be(header, GROUP_ID_OFFSET, groupId)
        putU32Be(header, MESSAGE_TYPE_OFFSET, messageType.toLong() and UINT32_MAX)
        putU64Be(header, REPLY_TOKEN_OFFSET, replyToken)
        putI32Be(header, REPLY_STATUS_OFFSET, replacementReplyStatus)
    }

    companion object {
        const val HEADER_BYTES = 32
        const val TOTAL_LENGTH_OFFSET = 0
        const val PACKAGE_TYPE_OFFSET = 4
        const val GROUP_ID_OFFSET = 8
        const val MESSAGE_TYPE_OFFSET = 16
        const val REPLY_TOKEN_OFFSET = 20
        const val REPLY_STATUS_OFFSET = 28

        const val PACKAGE_TYPE_SYNC = 0x7379_6e63
        const val PACKAGE_TYPE_REPLY = 0x7270_6c79

        private const val UINT32_MAX = 0xffff_ffffL

        fun view(packageValue: ApTransportPackage): MainBufferedApTransportControlHeader =
            decodeHeader(packageValue.encoded())

        fun decodeHeader(encoded: ByteArray): MainBufferedApTransportControlHeader {
            if (encoded.size < HEADER_BYTES) {
                throw ApapProtocolException(
                    "APTransport control package is shorter than $HEADER_BYTES bytes",
                )
            }
            val totalLength = readU32Be(encoded, TOTAL_LENGTH_OFFSET)
            if (totalLength < HEADER_BYTES) {
                throw ApapProtocolException(
                    "APTransport control total length $totalLength is smaller than $HEADER_BYTES",
                )
            }
            if (totalLength != encoded.size.toLong()) {
                throw ApapProtocolException(
                    "APTransport control total length $totalLength does not match ${encoded.size} bytes",
                )
            }
            return MainBufferedApTransportControlHeader(
                totalLength = totalLength,
                packageType = readU32Be(encoded, PACKAGE_TYPE_OFFSET).toInt(),
                groupId = readU64Be(encoded, GROUP_ID_OFFSET),
                messageType = readU32Be(encoded, MESSAGE_TYPE_OFFSET).toInt(),
                replyToken = readU64Be(encoded, REPLY_TOKEN_OFFSET),
                replyStatus = readI32Be(encoded, REPLY_STATUS_OFFSET),
            )
        }

        private fun fourCcOrNull(value: Int): String? {
            val chars = CharArray(4)
            for (index in 0 until 4) {
                val byte = value ushr (24 - index * 8) and 0xff
                if (byte !in 0x20..0x7e) return null
                chars[index] = byte.toChar()
            }
            return String(chars)
        }

        private fun readU32Be(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xffL) shl 24) or
                ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
                ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
                (bytes[offset + 3].toLong() and 0xffL)

        private fun readI32Be(bytes: ByteArray, offset: Int): Int =
            readU32Be(bytes, offset).toInt()

        private fun readU64Be(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) {
                value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
            }
            return value
        }

        private fun putU32Be(target: ByteArray, offset: Int, value: Long) {
            for (index in 0 until 4) {
                target[offset + index] = (value ushr (24 - index * 8)).toByte()
            }
        }

        private fun putI32Be(target: ByteArray, offset: Int, value: Int) {
            putU32Be(target, offset, value.toLong() and UINT32_MAX)
        }

        private fun putU64Be(target: ByteArray, offset: Int, value: Long) {
            for (index in 0 until 8) {
                target[offset + index] = (value ushr (56 - index * 8)).toByte()
            }
        }
    }
}

/** Bounded inputs accepted by the main-buffered binary-plist reply encoder. */
data class MainBufferedReplyBplistLimits(
    val maxDepth: Int = 16,
    val maxNodes: Int = 4_096,
    val maxContainerItems: Int = 1_024,
    val maxDataBytes: Int = 1024 * 1024,
    val maxStringChars: Int = 64 * 1024,
    val maxAggregateValueBytes: Int = 2 * 1024 * 1024,
) {
    init {
        require(maxDepth >= 0)
        require(maxNodes > 0)
        require(maxContainerItems > 0)
        require(maxDataBytes >= 0)
        require(maxStringChars >= 0)
        require(maxAggregateValueBytes >= 0)
    }
}

/** Encodes a `sync` request's binary-plist response as an exact `rply` package. */
object MainBufferedApTransportReplyEncoder {
    fun encodeReply(
        request: ApTransportPackage,
        body: Map<String, Any?>,
        replyStatus: Int = 0,
        maxPackageBytes: Int = ApTransportPackageCodec.DEFAULT_MAX_PACKAGE_BYTES,
        bplistLimits: MainBufferedReplyBplistLimits = MainBufferedReplyBplistLimits(),
    ): ApTransportPackage {
        val requestHeader = MainBufferedApTransportControlHeader.view(request)
        require(requestHeader.packageType == MainBufferedApTransportControlHeader.PACKAGE_TYPE_SYNC) {
            "APTransport reply source must have packageType 'sync'"
        }
        val boundedBody = BoundedBplistSnapshot(bplistLimits).copyDictionary(body)
        val encodedBody = BplistCodec.encode(boundedBody)
        return ApTransportPackageCodec.create(
            messageType = requestHeader.messageType,
            body = encodedBody,
            uninterpretedHeader = requestHeader.headerTemplate(
                replacementPackageType =
                    MainBufferedApTransportControlHeader.PACKAGE_TYPE_REPLY,
                replacementReplyStatus = replyStatus,
            ),
            maxPackageBytes = maxPackageBytes,
        )
    }
}

/** Copies plist values without trusting caller-reported collection sizes. */
private class BoundedBplistSnapshot(
    private val limits: MainBufferedReplyBplistLimits,
) {
    private val active = IdentityHashMap<Any, Unit>()
    private var nodes = 0
    private var aggregateBytes = 0L

    fun copyDictionary(value: Map<String, Any?>): Map<String, Any> {
        consumeNode()
        return copyMap(value, depth = 0)
    }

    private fun copyValue(value: Any, depth: Int): Any {
        if (depth > limits.maxDepth) {
            throw IllegalArgumentException("main-buffered reply exceeds nesting depth ${limits.maxDepth}")
        }
        consumeNode()
        return when (value) {
            is Boolean, is Byte, is Short, is Int, is Long -> value
            is BigInteger -> {
                require(value.signum() >= 0 && value.bitLength() <= 64 ||
                    value >= SIGNED_INT64_MINIMUM && value <= SIGNED_INT64_MAXIMUM) {
                    "main-buffered reply integer exceeds bplist Int64/UInt64 bounds"
                }
                value
            }
            is Float -> {
                require(value.isFinite()) { "main-buffered reply real must be finite" }
                value
            }
            is Double -> {
                require(value.isFinite()) { "main-buffered reply real must be finite" }
                value
            }
            is String -> copyString(value)
            is ByteArray -> copyData(value)
            is List<*> -> copyList(value, depth)
            is Map<*, *> -> copyMap(value, depth)
            else -> throw IllegalArgumentException(
                "main-buffered reply contains unsupported ${value.javaClass.name}",
            )
        }
    }

    private fun copyString(value: String): String {
        require(value.length <= limits.maxStringChars) {
            "main-buffered reply string exceeds ${limits.maxStringChars} characters"
        }
        consumeBytes(value.length.toLong() * 2L)
        return value
    }

    private fun copyData(value: ByteArray): ByteArray {
        require(value.size <= limits.maxDataBytes) {
            "main-buffered reply data exceeds ${limits.maxDataBytes} bytes"
        }
        consumeBytes(value.size.toLong())
        return value.copyOf()
    }

    private fun copyList(source: List<*>, depth: Int): List<Any> = withActive(source) {
        val result = ArrayList<Any>()
        val iterator = source.iterator()
        while (iterator.hasNext()) {
            if (result.size >= limits.maxContainerItems) {
                throw IllegalArgumentException(
                    "main-buffered reply array exceeds ${limits.maxContainerItems} items",
                )
            }
            val value = iterator.next()
                ?: throw IllegalArgumentException("main-buffered reply does not support null")
            result += copyValue(value, depth + 1)
        }
        Collections.unmodifiableList(result)
    }

    private fun copyMap(source: Map<*, *>, depth: Int): Map<String, Any> = withActive(source) {
        val result = LinkedHashMap<String, Any>()
        val iterator = source.entries.iterator()
        var entries = 0
        while (iterator.hasNext()) {
            if (entries >= limits.maxContainerItems) {
                throw IllegalArgumentException(
                    "main-buffered reply dictionary exceeds ${limits.maxContainerItems} entries",
                )
            }
            entries++
            val entry = iterator.next()
            val key = entry.key as? String
                ?: throw IllegalArgumentException("main-buffered reply keys must be strings")
            consumeNode()
            copyString(key)
            val value = entry.value
                ?: throw IllegalArgumentException("main-buffered reply does not support null")
            require(!result.containsKey(key)) {
                "main-buffered reply contains duplicate key '$key'"
            }
            result[key] = copyValue(value, depth + 1)
        }
        Collections.unmodifiableMap(result)
    }

    private inline fun <T> withActive(value: Any, block: () -> T): T {
        require(active.put(value, Unit) == null) { "main-buffered reply contains a cycle" }
        return try {
            block()
        } finally {
            active.remove(value)
        }
    }

    private fun consumeNode() {
        nodes++
        require(nodes <= limits.maxNodes) {
            "main-buffered reply exceeds ${limits.maxNodes} nodes"
        }
    }

    private fun consumeBytes(amount: Long) {
        require(amount >= 0L && aggregateBytes <= limits.maxAggregateValueBytes - amount) {
            "main-buffered reply exceeds ${limits.maxAggregateValueBytes} aggregate value bytes"
        }
        aggregateBytes += amount
    }

    companion object {
        private val SIGNED_INT64_MINIMUM = BigInteger.valueOf(Long.MIN_VALUE)
        private val SIGNED_INT64_MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE)
    }
}
