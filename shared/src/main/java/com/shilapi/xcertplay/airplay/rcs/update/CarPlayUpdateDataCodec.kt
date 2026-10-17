package com.shilapi.xcertplay.airplay.rcs.update

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.opack.OpackException
import com.shilapi.xcertplay.airplay.rcs.opack.OpackLimits
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec

class CarPlayUpdateDataProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Limits for one message and one archive. No asset bytes are retained between chunks. */
data class CarPlayUpdateDataLimits(
    val maxRcsBodyBytes: Int = 2 * 1024 * 1024,
    val maxChunkBytes: Int = 1024 * 1024,
    val maxMetadataBytes: Int = 256 * 1024,
    val maxArchiveBytes: Long = 512L * 1024 * 1024,
    val maxChunkCount: Int = 16_384,
    val maxVersion: Long = Long.MAX_VALUE,
) {
    init {
        require(maxRcsBodyBytes >= 128) { "maxRcsBodyBytes must fit fixed UpdateData fields" }
        require(maxChunkBytes > 0 && maxChunkBytes <= maxRcsBodyBytes)
        require(maxMetadataBytes >= 0 && maxMetadataBytes <= maxRcsBodyBytes)
        require(maxArchiveBytes > 0)
        require(maxChunkCount > 0)
        require(maxVersion >= 0)
    }
}

/** The RCS COMM body is one OPACK dictionary, not a plist or a nested CAF envelope. */
sealed interface CarPlayUpdateDataMessage {
    class Header(
        val sessionId: String,
        val version: Long,
        val chunkCount: Int,
        certificate: ByteArray? = null,
        signature: ByteArray? = null,
        additions: ByteArray? = null,
    ) : CarPlayUpdateDataMessage {
        private val certificateValue = certificate?.copyOf()
        private val signatureValue = signature?.copyOf()
        private val additionsValue = additions?.copyOf()

        val certificate: ByteArray? get() = certificateValue?.copyOf()
        val signature: ByteArray? get() = signatureValue?.copyOf()
        val additions: ByteArray? get() = additionsValue?.copyOf()
    }

    class Chunk(
        val sessionId: String,
        val chunkIndex: Int,
        data: ByteArray,
        val checksum: String? = null,
    ) : CarPlayUpdateDataMessage {
        private val dataValue = data.copyOf()
        val data: ByteArray get() = dataValue.copyOf()
    }

    /** Status zero means accepted; nonzero means rejected. No sessionID is evidenced in ACK. */
    data class Ack(val accepted: Int) : CarPlayUpdateDataMessage
}

/**
 * iOS 27 carkitd file receiver's confirmed application messages. Unknown keys fail closed until
 * a phone trace establishes extension semantics. Header `version` is NSNumber, not a string.
 */
class CarPlayUpdateDataCodec(
    private val limits: CarPlayUpdateDataLimits = CarPlayUpdateDataLimits(),
) {
    private val opackLimits = OpackLimits(
        maxEncodedBytes = limits.maxRcsBodyBytes,
        maxValueBytes = maxOf(limits.maxChunkBytes, limits.maxMetadataBytes, 64),
        maxCollectionEntries = HEADER_KEYS.size,
        maxObjectTableEntries = 32,
        maxDepth = 3,
    )

    fun encode(message: CarPlayUpdateDataMessage): RcsMessage {
        val fields: Map<String, Any> = when (message) {
            is CarPlayUpdateDataMessage.Header -> {
                validateSessionId(message.sessionId)
                checkVersion(message.version)
                checkChunkCount(message.chunkCount)
                linkedMapOf<String, Any>(
                    "messageType" to HEADER_TYPE,
                    "sessionID" to message.sessionId,
                    "payloadType" to THEME_ASSET_PAYLOAD_TYPE,
                    "version" to message.version,
                    "chunkCount" to message.chunkCount,
                ).apply {
                    message.certificate?.let { put("certificate", checkedMetadata(it, "certificate")) }
                    message.signature?.let { put("signature", checkedMetadata(it, "signature")) }
                    message.additions?.let { put("additions", checkedMetadata(it, "additions")) }
                }
            }

            is CarPlayUpdateDataMessage.Chunk -> {
                validateSessionId(message.sessionId)
                checkChunkIndex(message.chunkIndex)
                val data = message.data
                checkChunkData(data)
                message.checksum?.let(::checkChecksum)
                linkedMapOf<String, Any>(
                    "messageType" to CHUNK_TYPE,
                    "sessionID" to message.sessionId,
                    "chunkIndex" to message.chunkIndex,
                    "data" to data,
                ).apply { message.checksum?.let { put("checksum", it) } }
            }

            is CarPlayUpdateDataMessage.Ack -> linkedMapOf(
                "messageType" to ACK_TYPE,
                "accepted" to message.accepted,
            )
        }
        return try {
            RcsMessage.comm(OpackCodec.encode(fields, opackLimits))
        } catch (error: OpackException) {
            throw CarPlayUpdateDataProtocolException("Cannot encode UpdateData OPACK", error)
        }
    }

    fun decode(message: RcsMessage): CarPlayUpdateDataMessage {
        if (message.messageType != ApTransportPackageCodec.MESSAGE_TYPE_COMM) {
            throw CarPlayUpdateDataProtocolException("UpdateData must use RCS COMM")
        }
        return decodeBody(message.body)
    }

    fun decodeBody(body: ByteArray): CarPlayUpdateDataMessage {
        if (body.size > limits.maxRcsBodyBytes) {
            throw CarPlayUpdateDataProtocolException("UpdateData body exceeds configured limit")
        }
        val decoded = try {
            OpackCodec.decode(body, opackLimits)
        } catch (error: OpackException) {
            throw CarPlayUpdateDataProtocolException("Invalid UpdateData OPACK", error)
        }
        val raw = decoded as? Map<*, *>
            ?: throw CarPlayUpdateDataProtocolException("UpdateData body must be a dictionary")
        val fields = LinkedHashMap<String, Any?>(raw.size)
        raw.forEach { (key, value) ->
            val textKey = key as? String
                ?: throw CarPlayUpdateDataProtocolException("UpdateData keys must be strings")
            fields[textKey] = value
        }
        return when (requiredLong(fields, "messageType")) {
            HEADER_TYPE.toLong() -> decodeHeader(fields)
            CHUNK_TYPE.toLong() -> decodeChunk(fields)
            ACK_TYPE.toLong() -> decodeAck(fields)
            else -> throw CarPlayUpdateDataProtocolException("Unknown UpdateData message type")
        }
    }

    private fun decodeHeader(fields: Map<String, Any?>): CarPlayUpdateDataMessage.Header {
        requireKeys(fields, HEADER_KEYS, HEADER_REQUIRED_KEYS)
        val sessionId = requiredSessionId(fields)
        if (requiredLong(fields, "payloadType") != THEME_ASSET_PAYLOAD_TYPE.toLong()) {
            throw CarPlayUpdateDataProtocolException("Unsupported UpdateData payload type")
        }
        val version = requiredLong(fields, "version")
        checkVersion(version)
        val chunkCount = requiredLong(fields, "chunkCount")
        if (chunkCount !in 1..limits.maxChunkCount.toLong()) {
            throw CarPlayUpdateDataProtocolException("UpdateData chunk count is out of bounds")
        }
        return CarPlayUpdateDataMessage.Header(
            sessionId = sessionId,
            version = version,
            chunkCount = chunkCount.toInt(),
            certificate = optionalMetadata(fields, "certificate"),
            signature = optionalMetadata(fields, "signature"),
            additions = optionalMetadata(fields, "additions"),
        )
    }

    private fun decodeChunk(fields: Map<String, Any?>): CarPlayUpdateDataMessage.Chunk {
        requireKeys(fields, CHUNK_KEYS, CHUNK_REQUIRED_KEYS)
        val sessionId = requiredSessionId(fields)
        val chunkIndex = requiredLong(fields, "chunkIndex")
        if (chunkIndex < 0 || chunkIndex >= limits.maxChunkCount.toLong()) {
            throw CarPlayUpdateDataProtocolException("UpdateData chunk index is out of bounds")
        }
        val data = fields["data"] as? ByteArray
            ?: throw CarPlayUpdateDataProtocolException("UpdateData chunk data must be Data")
        checkChunkData(data)
        val checksum = if (fields.containsKey("checksum")) {
            (fields["checksum"] as? String)?.also(::checkChecksum)
                ?: throw CarPlayUpdateDataProtocolException("UpdateData checksum must be a string")
        } else {
            null
        }
        return CarPlayUpdateDataMessage.Chunk(sessionId, chunkIndex.toInt(), data, checksum)
    }

    private fun decodeAck(fields: Map<String, Any?>): CarPlayUpdateDataMessage.Ack {
        requireKeys(fields, ACK_KEYS, ACK_KEYS)
        val accepted = requiredLong(fields, "accepted")
        if (accepted !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw CarPlayUpdateDataProtocolException("UpdateData ACK status is out of bounds")
        }
        return CarPlayUpdateDataMessage.Ack(accepted.toInt())
    }

    private fun optionalMetadata(fields: Map<String, Any?>, key: String): ByteArray? {
        if (!fields.containsKey(key)) return null
        val data = fields[key] as? ByteArray
            ?: throw CarPlayUpdateDataProtocolException("UpdateData $key must be Data")
        return checkedMetadata(data, key)
    }

    private fun checkedMetadata(data: ByteArray, key: String): ByteArray {
        if (data.isEmpty() || data.size > limits.maxMetadataBytes) {
            throw CarPlayUpdateDataProtocolException("UpdateData $key size is out of bounds")
        }
        return data
    }

    private fun checkChunkData(data: ByteArray) {
        if (data.isEmpty() || data.size > limits.maxChunkBytes) {
            throw CarPlayUpdateDataProtocolException("UpdateData chunk size is out of bounds")
        }
    }

    private fun checkVersion(version: Long) {
        if (version < 0 || version > limits.maxVersion) {
            throw CarPlayUpdateDataProtocolException("UpdateData version is out of bounds")
        }
    }

    private fun checkChunkCount(count: Int) {
        if (count !in 1..limits.maxChunkCount) {
            throw CarPlayUpdateDataProtocolException("UpdateData chunk count is out of bounds")
        }
    }

    private fun checkChunkIndex(index: Int) {
        if (index !in 0 until limits.maxChunkCount) {
            throw CarPlayUpdateDataProtocolException("UpdateData chunk index is out of bounds")
        }
    }

    private fun requiredSessionId(fields: Map<String, Any?>): String {
        val sessionId = fields["sessionID"] as? String
            ?: throw CarPlayUpdateDataProtocolException("UpdateData sessionID must be a string")
        validateSessionId(sessionId)
        return sessionId
    }

    private fun validateSessionId(sessionId: String) {
        if (!UUID_PATTERN.matches(sessionId)) {
            throw CarPlayUpdateDataProtocolException("UpdateData sessionID must be a UUID string")
        }
    }

    private fun checkChecksum(checksum: String) {
        if (!SHA256_PATTERN.matches(checksum)) {
            throw CarPlayUpdateDataProtocolException("UpdateData checksum must be lowercase SHA-256 hex")
        }
    }

    private fun requiredLong(fields: Map<String, Any?>, key: String): Long =
        fields[key] as? Long
            ?: throw CarPlayUpdateDataProtocolException("UpdateData $key must be an integer")

    private fun requireKeys(
        fields: Map<String, Any?>,
        allowed: Set<String>,
        required: Set<String>,
    ) {
        if (!fields.keys.containsAll(required) || !allowed.containsAll(fields.keys)) {
            throw CarPlayUpdateDataProtocolException("UpdateData has missing or unknown fields")
        }
    }

    companion object {
        private const val HEADER_TYPE = 1
        private const val CHUNK_TYPE = 2
        private const val ACK_TYPE = 5
        private const val THEME_ASSET_PAYLOAD_TYPE = 1
        private val HEADER_REQUIRED_KEYS = setOf(
            "messageType", "sessionID", "payloadType", "version", "chunkCount",
        )
        private val HEADER_KEYS = HEADER_REQUIRED_KEYS + setOf(
            "certificate", "signature", "additions",
        )
        private val CHUNK_REQUIRED_KEYS = setOf("messageType", "sessionID", "chunkIndex", "data")
        private val CHUNK_KEYS = CHUNK_REQUIRED_KEYS + "checksum"
        private val ACK_KEYS = setOf("messageType", "accepted")
        private val UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
