package com.shilapi.xcertplay.airplay.rcs.logging

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.opack.OpackException
import com.shilapi.xcertplay.airplay.rcs.opack.OpackLimits
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.nio.charset.StandardCharsets

class CarPlayLoggingDataProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Resource bounds for one authenticated LoggingData stream, not an archive format declaration. */
data class CarPlayLoggingDataLimits(
    val maxRcsBodyBytes: Int = 512 * 1024,
    val maxChunkBytes: Int = 256 * 1024,
    val maxStatisticsDataBytes: Int = 64 * 1024,
    val maxSessionIdBytes: Int = 128,
    val maxArchiveBytes: Long = 64L * 1024 * 1024,
    val maxChunkCount: Int = 1024,
    val maxCollectionEntries: Int = 128,
    val maxDepth: Int = 12,
) {
    init {
        require(maxRcsBodyBytes >= 256)
        require(maxChunkBytes in 1..(maxRcsBodyBytes - 256))
        require(maxStatisticsDataBytes in 1..(maxRcsBodyBytes - 128))
        require(maxSessionIdBytes in 1..128)
        require(maxArchiveBytes > 0)
        require(maxChunkCount in 1..65_536)
        require(maxCollectionEntries in 5..4096)
        require(maxDepth in 2..32)
    }
}

/** The application body is exactly one OPACK dictionary inside an RCS COMM message. */
sealed interface CarPlayLoggingDataMessage {
    sealed interface FromPhone : CarPlayLoggingDataMessage
    sealed interface ToPhone : CarPlayLoggingDataMessage

    data object RequestArchive : FromPhone
    /** Raw arm64 NSUInteger bits; no duration unit or scheduling policy is inferred. */
    data class RequestStatistics(val interval: ULong) : FromPhone

    /** ACK 0 confirms completion; 5 reports a phone-side save failure. No sessionID is present. */
    data class Ack(val accepted: Int) : FromPhone

    data class ArchiveHeader(val sessionId: String, val chunkCount: Int) : ToPhone

    class ArchiveChunk(val sessionId: String, val chunkIndex: Int, data: ByteArray) : ToPhone {
        private val ownedData = data.copyOf()
        val data: ByteArray get() = ownedData.copyOf()
    }

    /** Payload schema is intentionally opaque; only its bounded OPACK dictionary carrier is known. */
    class Statistics(val data: Map<*, *>) : ToPhone
}

/**
 * iOS 27 carkitd LoggingData message subset. Unknown envelope keys, types, directions and
 * payload kinds fail closed. The statistics dictionary is opaque: no metric names are invented.
 * This codec does not constitute a ready LOG_TRANSFER handler or a trusted archive source.
 */
class CarPlayLoggingDataCodec(
    val limits: CarPlayLoggingDataLimits = CarPlayLoggingDataLimits(),
) {
    private val envelopeLimits = OpackLimits(
        maxEncodedBytes = limits.maxRcsBodyBytes,
        maxValueBytes = maxOf(
            limits.maxChunkBytes,
            limits.maxStatisticsDataBytes,
            limits.maxSessionIdBytes,
        ),
        maxCollectionEntries = limits.maxCollectionEntries,
        maxObjectTableEntries = limits.maxCollectionEntries * 4,
        maxDepth = limits.maxDepth,
    )
    private val statisticsLimits = envelopeLimits.copy(
        maxEncodedBytes = limits.maxStatisticsDataBytes,
        maxValueBytes = limits.maxStatisticsDataBytes,
    )

    fun encodeToPhone(message: CarPlayLoggingDataMessage.ToPhone): RcsMessage {
        val fields: Map<String, Any?> = when (message) {
            is CarPlayLoggingDataMessage.ArchiveHeader -> {
                checkSessionId(message.sessionId)
                checkChunkCount(message.chunkCount)
                linkedMapOf(
                    "messageType" to HEADER_TYPE,
                    "payloadType" to ARCHIVE_PAYLOAD_TYPE,
                    "sessionID" to message.sessionId,
                    "chunkCount" to message.chunkCount,
                )
            }

            is CarPlayLoggingDataMessage.ArchiveChunk -> {
                checkSessionId(message.sessionId)
                checkChunkIndex(message.chunkIndex)
                val data = message.data
                checkChunkData(data)
                linkedMapOf(
                    "messageType" to CHUNK_TYPE,
                    "sessionID" to message.sessionId,
                    "chunkIndex" to message.chunkIndex,
                    "data" to data,
                )
            }

            is CarPlayLoggingDataMessage.Statistics -> {
                val data = checkedStatisticsSnapshot(message.data)
                linkedMapOf(
                    "messageType" to STATISTICS_TYPE,
                    "payloadType" to STATISTICS_PAYLOAD_TYPE,
                    "data" to data,
                )
            }
        }
        return encodeFields(fields)
    }

    /**
     * For protocol fixtures and a future phone-side test peer; never send this direction on the
     * accessory's RCS stream. The generic OPACK encoder preserves all 64 interval bits via Long,
     * but its signed shortest-form choice (for example UInt64.max as inline -1) is not evidence of
     * Apple's canonical unsigned NSNumber encoding.
     */
    fun encodeFromPhone(message: CarPlayLoggingDataMessage.FromPhone): RcsMessage = encodeFields(
        when (message) {
            CarPlayLoggingDataMessage.RequestArchive -> linkedMapOf(
                "messageType" to REQUEST_TYPE,
                "request" to ARCHIVE_REQUEST,
            )

            is CarPlayLoggingDataMessage.RequestStatistics -> {
                linkedMapOf(
                    "messageType" to REQUEST_TYPE,
                    "request" to STATISTICS_REQUEST,
                    // OPACK's Long carrier preserves all 64 bits, including the high bit.
                    "interval" to message.interval.toLong(),
                )
            }

            is CarPlayLoggingDataMessage.Ack -> {
                checkAck(message.accepted.toLong())
                linkedMapOf(
                    "messageType" to ACK_TYPE,
                    "accepted" to message.accepted,
                )
            }
        },
    )

    fun decodeFromPhone(message: RcsMessage): CarPlayLoggingDataMessage.FromPhone =
        when (val decoded = decode(message)) {
            is CarPlayLoggingDataMessage.FromPhone -> decoded
            else -> throw CarPlayLoggingDataProtocolException(
                "Accessory-originated LoggingData message received from phone",
            )
        }

    fun decodeToPhone(message: RcsMessage): CarPlayLoggingDataMessage.ToPhone =
        when (val decoded = decode(message)) {
            is CarPlayLoggingDataMessage.ToPhone -> decoded
            else -> throw CarPlayLoggingDataProtocolException(
                "Phone-originated LoggingData message received from accessory",
            )
        }

    private fun decode(message: RcsMessage): CarPlayLoggingDataMessage {
        if (message.messageType != ApTransportPackageCodec.MESSAGE_TYPE_COMM) {
            throw CarPlayLoggingDataProtocolException("LoggingData requires RCS COMM")
        }
        if (message.body.size > limits.maxRcsBodyBytes) {
            throw CarPlayLoggingDataProtocolException("LoggingData body exceeds configured limit")
        }
        val decoded = try {
            OpackCodec.decode(message.body, envelopeLimits)
        } catch (error: OpackException) {
            throw CarPlayLoggingDataProtocolException("Invalid LoggingData OPACK", error)
        }
        val source = decoded as? Map<*, *>
            ?: throw CarPlayLoggingDataProtocolException("LoggingData body must be a dictionary")
        val fields = LinkedHashMap<String, Any?>(source.size)
        source.forEach { (key, value) ->
            val stringKey = key as? String
                ?: throw CarPlayLoggingDataProtocolException("LoggingData envelope keys must be strings")
            fields[stringKey] = value
        }
        return when (requiredNonnegativeLong(fields, "messageType")) {
            HEADER_TYPE.toLong() -> {
                requireKeys(fields, HEADER_KEYS)
                requireValue(fields, "payloadType", ARCHIVE_PAYLOAD_TYPE)
                val sessionId = requiredSessionId(fields)
                val count = requiredNonnegativeLong(fields, "chunkCount")
                if (count > limits.maxChunkCount) {
                    throw CarPlayLoggingDataProtocolException("LoggingData chunk count is out of bounds")
                }
                CarPlayLoggingDataMessage.ArchiveHeader(sessionId, count.toInt())
            }

            CHUNK_TYPE.toLong() -> {
                requireKeys(fields, CHUNK_KEYS)
                val sessionId = requiredSessionId(fields)
                val index = requiredNonnegativeLong(fields, "chunkIndex")
                if (index >= limits.maxChunkCount) {
                    throw CarPlayLoggingDataProtocolException("LoggingData chunk index is out of bounds")
                }
                val data = fields["data"] as? ByteArray
                    ?: throw CarPlayLoggingDataProtocolException("LoggingData chunk must contain Data")
                checkChunkData(data)
                CarPlayLoggingDataMessage.ArchiveChunk(sessionId, index.toInt(), data)
            }

            REQUEST_TYPE.toLong() -> when (requiredNonnegativeLong(fields, "request")) {
                ARCHIVE_REQUEST.toLong() -> {
                    requireKeys(fields, ARCHIVE_REQUEST_KEYS)
                    CarPlayLoggingDataMessage.RequestArchive
                }

                STATISTICS_REQUEST.toLong() -> {
                    requireKeys(fields, STATISTICS_REQUEST_KEYS)
                    val bits = fields["interval"] as? Long
                        ?: throw CarPlayLoggingDataProtocolException(
                            "LoggingData interval must be an integer",
                        )
                    CarPlayLoggingDataMessage.RequestStatistics(bits.toULong())
                }

                else -> throw CarPlayLoggingDataProtocolException("Unknown LoggingData request")
            }

            STATISTICS_TYPE.toLong() -> {
                requireKeys(fields, STATISTICS_KEYS)
                requireValue(fields, "payloadType", STATISTICS_PAYLOAD_TYPE)
                val data = fields["data"] as? Map<*, *>
                    ?: throw CarPlayLoggingDataProtocolException("LoggingData statistics must be a dictionary")
                CarPlayLoggingDataMessage.Statistics(checkedStatisticsSnapshot(data))
            }

            ACK_TYPE.toLong() -> {
                requireKeys(fields, ACK_KEYS)
                val accepted = requiredNonnegativeLong(fields, "accepted")
                checkAck(accepted)
                CarPlayLoggingDataMessage.Ack(accepted.toInt())
            }

            else -> throw CarPlayLoggingDataProtocolException("Unknown LoggingData message type")
        }
    }

    private fun encodeFields(fields: Map<String, Any?>): RcsMessage = try {
        RcsMessage.comm(OpackCodec.encode(fields, envelopeLimits))
    } catch (error: OpackException) {
        throw CarPlayLoggingDataProtocolException("Cannot encode LoggingData OPACK", error)
    }

    /** Deeply separates the bounded opaque dictionary from caller-owned mutable collections. */
    private fun checkedStatisticsSnapshot(data: Map<*, *>): Map<*, *> {
        val encoded = try {
            OpackCodec.encode(data, statisticsLimits)
        } catch (error: OpackException) {
            throw CarPlayLoggingDataProtocolException(
                "LoggingData statistics exceed bounds or contain unsupported values",
                error,
            )
        }
        return OpackCodec.decode(encoded, statisticsLimits) as Map<*, *>
    }

    private fun requiredSessionId(fields: Map<String, Any?>): String {
        val sessionId = fields["sessionID"] as? String
            ?: throw CarPlayLoggingDataProtocolException("LoggingData sessionID must be a string")
        checkSessionId(sessionId)
        return sessionId
    }

    fun checkSessionId(sessionId: String) {
        if (sessionId.isBlank() || sessionId.length > limits.maxSessionIdBytes ||
            sessionId.any(Char::isISOControl) ||
            sessionId.toByteArray(StandardCharsets.UTF_8).size > limits.maxSessionIdBytes
        ) {
            throw CarPlayLoggingDataProtocolException("LoggingData sessionID is invalid")
        }
    }

    private fun checkChunkCount(count: Int) {
        if (count !in 0..limits.maxChunkCount) {
            throw CarPlayLoggingDataProtocolException("LoggingData chunk count is out of bounds")
        }
    }

    private fun checkChunkIndex(index: Int) {
        if (index !in 0 until limits.maxChunkCount) {
            throw CarPlayLoggingDataProtocolException("LoggingData chunk index is out of bounds")
        }
    }

    private fun checkChunkData(data: ByteArray) {
        if (data.isEmpty() || data.size > limits.maxChunkBytes) {
            throw CarPlayLoggingDataProtocolException("LoggingData chunk size is out of bounds")
        }
    }

    private fun requiredNonnegativeLong(fields: Map<String, Any?>, key: String): Long {
        val number = fields[key] as? Long
            ?: throw CarPlayLoggingDataProtocolException("LoggingData $key must be an integer")
        if (number < 0) {
            throw CarPlayLoggingDataProtocolException("LoggingData $key must be nonnegative")
        }
        return number
    }

    private fun checkAck(accepted: Long) {
        if (accepted != ACK_COMPLETE.toLong() && accepted != ACK_SAVE_FAILED.toLong()) {
            throw CarPlayLoggingDataProtocolException("Unsupported LoggingData ACK status")
        }
    }

    private fun requireValue(fields: Map<String, Any?>, key: String, expected: Int) {
        if (requiredNonnegativeLong(fields, key) != expected.toLong()) {
            throw CarPlayLoggingDataProtocolException("Unsupported LoggingData $key")
        }
    }

    private fun requireKeys(fields: Map<String, Any?>, exact: Set<String>) {
        if (fields.keys != exact) {
            throw CarPlayLoggingDataProtocolException("LoggingData has missing or unknown fields")
        }
    }

    companion object {
        private const val HEADER_TYPE = 1
        private const val CHUNK_TYPE = 2
        private const val REQUEST_TYPE = 3
        private const val STATISTICS_TYPE = 4
        private const val ACK_TYPE = 5
        private const val ARCHIVE_REQUEST = 1
        private const val STATISTICS_REQUEST = 2
        private const val ARCHIVE_PAYLOAD_TYPE = 2
        private const val STATISTICS_PAYLOAD_TYPE = 3
        private const val ACK_COMPLETE = 0
        private const val ACK_SAVE_FAILED = 5
        private val HEADER_KEYS = setOf("messageType", "payloadType", "sessionID", "chunkCount")
        private val CHUNK_KEYS = setOf("messageType", "sessionID", "chunkIndex", "data")
        private val ARCHIVE_REQUEST_KEYS = setOf("messageType", "request")
        private val STATISTICS_REQUEST_KEYS = ARCHIVE_REQUEST_KEYS + "interval"
        private val STATISTICS_KEYS = setOf("messageType", "payloadType", "data")
        private val ACK_KEYS = setOf("messageType", "accepted")
    }
}
