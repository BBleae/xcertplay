package com.shilapi.xcertplay.airplay.mainbuffered

import java.math.BigInteger
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Evidence-only model of AirPlay's type-103 (main-buffered media audio) protocol.
 *
 * This file deliberately has no dependency on AirPlay session setup, feature negotiation, socket
 * handling, or the media engine. In particular, parsing these structures or the detached APAP
 * codecs is not runtime-readiness evidence for `mainBuffered`: product key derivation, socket
 * ownership, buffering, decoding, and render-clock integration remain deliberately unwired.
 */

/** Confirmed single-bit selectors used by `audioOutputFormats` and `audioFormat`. */
object MainBufferedAudioFormatBits {
    const val PCM_8000_16_MONO = 0x4L
    const val PCM_8000_16_STEREO = 0x8L
    const val PCM_16000_16_MONO = 0x10L
    const val PCM_16000_16_STEREO = 0x20L
    const val PCM_24000_16_MONO = 0x40L
    const val PCM_24000_16_STEREO = 0x80L
    const val PCM_32000_16_MONO = 0x100L
    const val PCM_32000_16_STEREO = 0x200L
    const val PCM_44100_16_MONO = 0x400L
    const val PCM_44100_16_STEREO = 0x800L
    const val PCM_44100_24_MONO = 0x1000L
    const val PCM_44100_24_STEREO = 0x2000L
    const val PCM_48000_16_MONO = 0x4000L
    const val PCM_48000_16_STEREO = 0x8000L

    const val ALAC_44100_16_STEREO = 0x40000L
    const val ALAC_44100_24_STEREO = 0x80000L
    const val ALAC_48000_16_STEREO = 0x100000L
    const val ALAC_48000_24_STEREO = 0x200000L

    const val AAC_LC_44100_STEREO = 0x400000L
    const val AAC_LC_48000_STEREO = 0x800000L
    const val AAC_ELD_44100_STEREO = 0x1000000L
    const val AAC_ELD_48000_STEREO = 0x2000000L
    const val AAC_ELD_16000_MONO = 0x4000000L
    const val AAC_ELD_24000_MONO = 0x8000000L
    const val AAC_ELD_44100_MONO = 0x80000000L
    const val AAC_ELD_48000_MONO = 0x100000000L
    const val AAC_ELD_32000_MONO = 0x80000000000L

    const val OPUS_16000_MONO = 0x10000000L
    const val OPUS_24000_MONO = 0x20000000L
    const val OPUS_48000_MONO = 0x40000000L

    fun isSingleBit(value: Long): Boolean = value != 0L && value and (value - 1L) == 0L

    fun requireSingleBit(value: Long, label: String = "audioFormat"): Long {
        require(isSingleBit(value)) { "$label must contain exactly one UInt64 format bit" }
        return value
    }

    fun indexOfSingleBit(value: Long): Int {
        requireSingleBit(value)
        return java.lang.Long.numberOfTrailingZeros(value)
    }
}

/**
 * One confirmed type-103 entry in the top-level AirPlay `/info.audioFormats` array.
 *
 * `audioOutputFormatsExtended` is intentionally retained as a bounded opaque array. Its element
 * schema has not been recovered, and it is not a field of `mainBufferedInfo`.
 */
class MainBufferedAudioFormatAdvertisement(
    val audioOutputFormats: Long,
    audioOutputFormatsExtended: List<Any?>? = null,
) {
    private val storedExtended: List<Any?>? = audioOutputFormatsExtended?.let {
        MainBufferedWireSnapshot.copyList(it, "audioOutputFormatsExtended")
    }

    val audioOutputFormatsExtended: List<Any?>?
        get() = storedExtended?.let {
            MainBufferedWireSnapshot.copyList(it, "audioOutputFormatsExtended")
        }

    init {
        require(audioOutputFormats != 0L) { "audioOutputFormats must contain at least one bit" }
    }

    fun toWireMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        TYPE to STREAM_TYPE,
        AUDIO_TYPE to MEDIA,
        AUDIO_OUTPUT_FORMATS to MainBufferedNumbers.toWireUInt64(audioOutputFormats),
    ).apply {
        storedExtended?.let {
            put(
                AUDIO_OUTPUT_FORMATS_EXTENDED,
                MainBufferedWireSnapshot.copyList(it, "audioOutputFormatsExtended"),
            )
        }
    }

    companion object {
        const val STREAM_TYPE = 103L
        const val MEDIA = "media"

        private const val TYPE = "type"
        private const val AUDIO_TYPE = "audioType"
        private const val AUDIO_OUTPUT_FORMATS = "audioOutputFormats"
        private const val AUDIO_OUTPUT_FORMATS_EXTENDED = "audioOutputFormatsExtended"
        private val KEYS = setOf(
            TYPE,
            AUDIO_TYPE,
            AUDIO_OUTPUT_FORMATS,
            AUDIO_OUTPUT_FORMATS_EXTENDED,
        )

        fun fromWireMap(raw: Map<String, Any?>): MainBufferedAudioFormatAdvertisement {
            val values = MainBufferedWireSnapshot.copyMap(raw, "main-buffered audio format")
            MainBufferedParsing.requireOnly(values, KEYS, "main-buffered audio format")
            require(
                MainBufferedNumbers.u32(
                    MainBufferedParsing.required(values, TYPE, "main-buffered audio format"),
                    "main-buffered audio format.type",
                ) == STREAM_TYPE,
            ) { "main-buffered audio format.type must be 103" }
            require(values[AUDIO_TYPE] == MEDIA) {
                "main-buffered audio format.audioType must be 'media'"
            }
            val outputFormats = MainBufferedNumbers.u64(
                MainBufferedParsing.required(
                    values,
                    AUDIO_OUTPUT_FORMATS,
                    "main-buffered audio format",
                ),
                "main-buffered audio format.audioOutputFormats",
            )
            val extended = if (values.containsKey(AUDIO_OUTPUT_FORMATS_EXTENDED)) {
                values[AUDIO_OUTPUT_FORMATS_EXTENDED] as? List<*>
                    ?: throw IllegalArgumentException(
                        "main-buffered audio format.audioOutputFormatsExtended must be an array",
                    )
            } else {
                null
            }
            return MainBufferedAudioFormatAdvertisement(outputFormats, extended?.map { it })
        }
    }
}

/** Strict, detached model of a type-103 stream entry in SETUP. */
class MainBufferedSetupRequest private constructor(
    val streamConnectionId: Long,
    val supportsDynamicStreamId: Boolean,
    /** UInt32 bits. This may encode a four-character code; unknown values stay opaque. */
    val contentType: Long,
    val samplesPerFrame: Long,
    val audioFormat: Long,
    val audioFormatIndex: Long,
    val isMedia: Boolean,
    val packetFormatApap: Boolean,
    optionalValues: Map<String, Any?>,
    private val onCopiedData: ((ByteArray) -> Unit)? = null,
) {
    private val storedOptionalValues = MainBufferedWireSnapshot.copyMap(
        optionalValues,
        "type-103 SETUP optional values",
        onCopiedData,
    )

    val optionalValues: Map<String, Any?>
        get() = MainBufferedWireSnapshot.copyMap(
            storedOptionalValues,
            "type-103 SETUP optional values",
            onCopiedData,
        )

    fun contentTypeFourCcOrNull(): String? {
        val bytes = byteArrayOf(
            (contentType ushr 24).toByte(),
            (contentType ushr 16).toByte(),
            (contentType ushr 8).toByte(),
            contentType.toByte(),
        )
        return if (bytes.all { (it.toInt() and 0xff) in 0x20..0x7e }) {
            String(bytes, StandardCharsets.US_ASCII)
        } else {
            null
        }
    }

    fun toWireMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        TYPE to STREAM_TYPE,
        STREAM_CONNECTION_ID to MainBufferedNumbers.toWireUInt64(streamConnectionId),
        SUPPORTS_DYNAMIC_STREAM_ID to supportsDynamicStreamId,
        CONTENT_TYPE to contentType,
        SAMPLES_PER_FRAME to samplesPerFrame,
        AUDIO_FORMAT to MainBufferedNumbers.toWireUInt64(audioFormat),
        AUDIO_FORMAT_INDEX to audioFormatIndex,
        IS_MEDIA to isMedia,
        AUDIO_TYPE to MEDIA,
        PACKET_FORMAT_APAP to packetFormatApap,
    ).apply {
        storedOptionalValues.forEach { (key, value) ->
            put(key, MainBufferedWireSnapshot.copyValue(
                value, "type-103 SETUP.$key", onCopiedData,
            ))
        }
    }

    companion object {
        const val STREAM_TYPE = 103L
        const val MEDIA = "media"

        private const val TYPE = "type"
        private const val STREAM_CONNECTION_ID = "streamConnectionID"
        private const val SUPPORTS_DYNAMIC_STREAM_ID = "supportsDynamicStreamID"
        private const val CONTENT_TYPE = "ct"
        private const val SAMPLES_PER_FRAME = "spf"
        private const val AUDIO_FORMAT = "audioFormat"
        private const val AUDIO_FORMAT_INDEX = "audioFormatIndex"
        private const val IS_MEDIA = "isMedia"
        private const val AUDIO_TYPE = "audioType"
        private const val PACKET_FORMAT_APAP = "packetFormatAPAP"

        private const val AUDIO_SESSION_ID = "audioSessionID"
        private const val AUDIO_SESSION_CATEGORY = "audioSessionCategory"
        private const val AUDIO_SESSION_MODE = "audioSessionMode"
        private const val SHARED_KEY = "shk"
        private const val SOUND_CHECK_MEDIA_KIND = "soundCheckMediaKind"
        private const val CLIENT_ID = "clientID"
        private const val MULTICAST_GROUP_INFO = "multicastGroupInfo"
        private const val STREAM_CONNECTIONS = "streamConnections"

        private val REQUIRED_KEYS = setOf(
            TYPE,
            STREAM_CONNECTION_ID,
            SUPPORTS_DYNAMIC_STREAM_ID,
            CONTENT_TYPE,
            SAMPLES_PER_FRAME,
            AUDIO_FORMAT,
            AUDIO_FORMAT_INDEX,
            IS_MEDIA,
            AUDIO_TYPE,
            PACKET_FORMAT_APAP,
        )
        private val OPTIONAL_KEYS = setOf(
            AUDIO_SESSION_ID,
            AUDIO_SESSION_CATEGORY,
            AUDIO_SESSION_MODE,
            SHARED_KEY,
            SOUND_CHECK_MEDIA_KIND,
            CLIENT_ID,
            MULTICAST_GROUP_INFO,
            STREAM_CONNECTIONS,
        )

        fun parse(
            raw: Any?,
            onCopiedData: ((ByteArray) -> Unit)? = null,
        ): MainBufferedSetupRequest {
            val values = MainBufferedWireSnapshot.requireMap(
                raw, "type-103 SETUP", onCopiedData,
            )
            MainBufferedParsing.requireOnly(
                values,
                REQUIRED_KEYS + OPTIONAL_KEYS,
                "type-103 SETUP",
            )
            MainBufferedParsing.requireEvery(values, REQUIRED_KEYS, "type-103 SETUP")

            require(MainBufferedNumbers.u32(values.getValue(TYPE), "type-103 SETUP.type") == STREAM_TYPE) {
                "type-103 SETUP.type must be 103"
            }
            val connectionId = MainBufferedNumbers.u64(
                values.getValue(STREAM_CONNECTION_ID),
                "type-103 SETUP.streamConnectionID",
            )
            val dynamic = MainBufferedParsing.boolean(
                values.getValue(SUPPORTS_DYNAMIC_STREAM_ID),
                "type-103 SETUP.supportsDynamicStreamID",
            )
            val contentType = MainBufferedNumbers.fourCcOrU32(
                values.getValue(CONTENT_TYPE),
                "type-103 SETUP.ct",
            )
            val samplesPerFrame = MainBufferedNumbers.u32(
                values.getValue(SAMPLES_PER_FRAME),
                "type-103 SETUP.spf",
            )
            require(samplesPerFrame > 0L) { "type-103 SETUP.spf must be positive" }
            val format = MainBufferedNumbers.u64(
                values.getValue(AUDIO_FORMAT),
                "type-103 SETUP.audioFormat",
            )
            MainBufferedAudioFormatBits.requireSingleBit(format, "type-103 SETUP.audioFormat")
            val formatIndex = MainBufferedNumbers.u32(
                values.getValue(AUDIO_FORMAT_INDEX),
                "type-103 SETUP.audioFormatIndex",
            )
            require(formatIndex <= 63L) { "type-103 SETUP.audioFormatIndex must be in 0..63" }
            require(formatIndex.toInt() == MainBufferedAudioFormatBits.indexOfSingleBit(format)) {
                "type-103 SETUP.audioFormatIndex contradicts audioFormat"
            }
            val media = MainBufferedParsing.boolean(
                values.getValue(IS_MEDIA),
                "type-103 SETUP.isMedia",
            )
            require(media) { "type-103 SETUP.isMedia must be true" }
            require(values[AUDIO_TYPE] == MEDIA) { "type-103 SETUP.audioType must be 'media'" }
            val apap = MainBufferedParsing.boolean(
                values.getValue(PACKET_FORMAT_APAP),
                "type-103 SETUP.packetFormatAPAP",
            )

            val optional = LinkedHashMap<String, Any?>()
            values.forEach { (key, value) ->
                if (key !in OPTIONAL_KEYS) return@forEach
                optional[key] = when (key) {
                    AUDIO_SESSION_ID -> MainBufferedNumbers.boundedInteger(
                        value,
                        "type-103 SETUP.audioSessionID",
                    )
                    AUDIO_SESSION_CATEGORY,
                    AUDIO_SESSION_MODE,
                    -> MainBufferedParsing.text(value, "type-103 SETUP.$key")
                    SHARED_KEY -> MainBufferedParsing.data(
                        value, "type-103 SETUP.shk", onCopiedData,
                    )
                    MULTICAST_GROUP_INFO,
                    STREAM_CONNECTIONS,
                    -> MainBufferedWireSnapshot.requireMap(
                        value, "type-103 SETUP.$key", onCopiedData,
                    )
                    SOUND_CHECK_MEDIA_KIND,
                    CLIENT_ID,
                    -> MainBufferedWireSnapshot.copyValue(
                        value, "type-103 SETUP.$key", onCopiedData,
                    )
                    else -> error("unreachable")
                }
            }
            return MainBufferedSetupRequest(
                streamConnectionId = connectionId,
                supportsDynamicStreamId = dynamic,
                contentType = contentType,
                samplesPerFrame = samplesPerFrame,
                audioFormat = format,
                audioFormatIndex = formatIndex,
                isMedia = media,
                packetFormatApap = apap,
                optionalValues = optional,
                onCopiedData = onCopiedData,
            )
        }

        /**
         * Parse one v27 APAP request without retaining its raw `shk` in the typed request.
         * The bounded snapshot's private key copy is wiped after [action] returns; callers must
         * hand it directly to an owner that copies it before returning from [action].
         */
        internal fun <T> withV27RawApapKey(
            raw: Any?,
            action: (MainBufferedSetupRequest, ByteArray) -> T,
        ): T = MainBufferedV27RequestTicket.freeze(raw).use { ticket ->
            ticket.withRawKey(action)
        }
    }
}

/** Credential-free, canonical replay key for one exact v27 request. */
internal data class MainBufferedV27RequestKey(
    val connectionIdBits: Long,
    val mctlSeedBits: Long?,
    val shkSha256: String,
) {
    override fun toString(): String =
        "MainBufferedV27RequestKey(connectionIdBits=$connectionIdBits, " +
            "mctlSeedBits=$mctlSeedBits, shkSha256=<redacted>)"
}

/** Every Data copy made while freezing/parsing a product ticket has one erasing owner. */
private class MainBufferedV27TicketDataOwner(
    private val onCopy: ((ByteArray) -> Unit)?,
) : Closeable {
    private val lock = Any()
    private var copies: ArrayList<ByteArray>? = ArrayList()
    private var closed = false

    fun own(data: ByteArray) {
        synchronized(lock) {
            if (closed) {
                data.fill(0)
                throw IllegalStateException("type-103 request Data owner is closed")
            }
            try {
                checkNotNull(copies).add(data)
            } catch (failure: Throwable) {
                data.fill(0)
                throw failure
            }
        }
        onCopy?.invoke(data)
    }

    override fun close() {
        val retiring = synchronized(lock) {
            if (closed) return
            closed = true
            copies.also { copies = null }
        }
        retiring?.forEach { it.fill(0) }
        retiring?.clear()
    }
}

/**
 * One bounded request snapshot, made before claiming an M4 child lease. The raw APAP key is
 * scoped to this object and erased on close; the M4 replay ledger stores only [key].
 */
internal class MainBufferedV27RequestTicket private constructor(
    val request: MainBufferedSetupRequest,
    val key: MainBufferedV27RequestKey,
    private val rawKey: ByteArray,
    private val ownedData: MainBufferedV27TicketDataOwner,
) : Closeable {
    private var closed = false
    private var consumed = false
    private var ownerMarker: Any? = null

    @Synchronized
    fun bindClaim(marker: Any): Boolean {
        if (closed || consumed || ownerMarker != null) return false
        ownerMarker = marker
        return true
    }

    @Synchronized
    fun isBoundTo(marker: Any): Boolean = !closed && ownerMarker === marker

    fun <T> withRawKey(action: (MainBufferedSetupRequest, ByteArray) -> T): T {
        synchronized(this) {
            check(!closed && !consumed) { "type-103 request key scope is no longer current" }
            consumed = true
        }
        return action(request, rawKey)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        ownedData.close()
        ownerMarker = null
        closed = true
    }

    companion object {
        fun freeze(
            raw: Any?,
            onSnapshotDataCopy: ((ByteArray) -> Unit)? = null,
        ): MainBufferedV27RequestTicket {
            val ownedData = MainBufferedV27TicketDataOwner(onSnapshotDataCopy)
            try {
                val snapshot = MainBufferedWireSnapshot.requireMap(
                    raw, "type-103 SETUP", ownedData::own,
                )
                val rawKey = snapshot["shk"] as? ByteArray
                    ?: throw IllegalArgumentException("type-103 SETUP.shk must be raw Data")
                require(rawKey.size == EncryptedApapPacketCodec.KEY_BYTES) {
                    "type-103 SETUP.shk must be ${EncryptedApapPacketCodec.KEY_BYTES} bytes"
                }
                val withoutKey = LinkedHashMap(snapshot)
                withoutKey.remove("shk")
                val parsed = MainBufferedSetupRequest.parse(withoutKey, ownedData::own)
                val seed = parseMctlSeed(parsed.optionalValues["streamConnections"])
                val digest = MessageDigest.getInstance("SHA-256").digest(rawKey)
                val fingerprint = try {
                    digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                } finally {
                    digest.fill(0)
                }
                return MainBufferedV27RequestTicket(
                    parsed,
                    MainBufferedV27RequestKey(parsed.streamConnectionId, seed, fingerprint),
                    rawKey,
                    ownedData,
                )
            } catch (failure: Throwable) {
                ownedData.close()
                throw failure
            }
        }

        private fun parseMctlSeed(connections: Any?): Long? {
            if (connections == null) return null
            val entries = connections as? Map<*, *>
                ?: throw IllegalArgumentException("type-103 streamConnections must be a dictionary")
            if (!entries.containsKey("streamConnectionTypeMediaDataControl")) return null
            val mctl = entries["streamConnectionTypeMediaDataControl"] as? Map<*, *>
                ?: throw IllegalArgumentException("type-103 MediaDataControl must be a dictionary")
            require(mctl.keys == setOf("streamConnectionKeyEncryptionSeed")) {
                "type-103 MediaDataControl must provide only its UInt64 seed"
            }
            return MainBufferedNumbers.u64(
                mctl["streamConnectionKeyEncryptionSeed"],
                "type-103 MediaDataControl encryption seed",
            )
        }
    }
}

/**
 * Response shape observed in a legacy receiver implementation.
 *
 * The current type-103 lifecycle and carrier have not been confirmed. This class is therefore
 * explicitly experimental and cannot be supplied to feature-readiness or runtime APIs.
 */
class ExperimentalLegacyMainBufferedSetupResponse(
    val streamId: Long,
    val audioBufferSize: Long,
    val dataPort: Int,
) {
    init {
        require(streamId in 0..UINT32_MAX) { "streamID must be a UInt32" }
        require(audioBufferSize in 0..UINT32_MAX) { "audioBufferSize must be a UInt32" }
        require(dataPort in 1..UINT16_MAX) { "dataPort must be in 1..65535" }
    }

    fun toWireMap(): Map<String, Any?> = linkedMapOf(
        "type" to MainBufferedSetupRequest.STREAM_TYPE,
        "streamID" to streamId,
        "audioBufferSize" to audioBufferSize,
        "dataPort" to dataPort.toLong(),
    )

    companion object {
        fun parse(raw: Any?): ExperimentalLegacyMainBufferedSetupResponse {
            val values = MainBufferedWireSnapshot.requireMap(raw, "legacy type-103 SETUP response")
            val keys = setOf("type", "streamID", "audioBufferSize", "dataPort")
            MainBufferedParsing.requireOnly(values, keys, "legacy type-103 SETUP response")
            MainBufferedParsing.requireEvery(values, keys, "legacy type-103 SETUP response")
            require(
                MainBufferedNumbers.u32(values.getValue("type"), "legacy response.type") ==
                    MainBufferedSetupRequest.STREAM_TYPE,
            ) { "legacy response.type must be 103" }
            return ExperimentalLegacyMainBufferedSetupResponse(
                streamId = MainBufferedNumbers.u32(values.getValue("streamID"), "legacy response.streamID"),
                audioBufferSize = MainBufferedNumbers.u32(
                    values.getValue("audioBufferSize"),
                    "legacy response.audioBufferSize",
                ),
                dataPort = MainBufferedNumbers.u16(
                    values.getValue("dataPort"),
                    "legacy response.dataPort",
                ),
            )
        }
    }
}

enum class MainBufferedControlTag(val wireValue: String) {
    SET_RATE_AND_ANCHOR("srat"),
    SET_RATE_RETURN_ANCHOR("strt"),
    GET_ANCHOR("anch"),
    FLUSH_BUFFERED("fshb"),
    AUDIO_PLAYBACK_VOLUME_FADE("apvf"),
    LOUDNESS_NORMALIZATION("loud"),
    MAT_ATMOS_PLAYBACK("mata"),
    MAGIC_COOKIE("magc"),
    UPDATE_CRYPTOR_KEY("upck"),
    UPDATE_PROTOCOL("uppr"),
    ;

    companion object {
        fun fromWireValue(value: String): MainBufferedControlTag = entries.firstOrNull {
            it.wireValue == value
        } ?: throw IllegalArgumentException("unknown main-buffered control tag '$value'")
    }
}

data class MainBufferedTimelinePoint(
    val rtpTime: Long,
    val mediaTimeValue: Long,
    val mediaTimeScale: Long,
) {
    init {
        require(rtpTime in 0..UINT32_MAX) { "rtpTime must be a UInt32" }
        require(mediaTimeScale in 1..INT32_MAX) { "mediaTimeScale must be in 1..Int32.MAX_VALUE" }
    }
}

data class MainBufferedNetworkTime(
    val seconds: Int,
    /** Raw UInt64 fraction bits. */
    val fraction: Long,
    /** Raw UInt64 timeline identifier bits. */
    val timelineId: Long,
    val flags: Long,
) {
    init {
        require(flags in 0..UINT32_MAX) { "networkTimeFlags must be a UInt32" }
    }
}

data class MainBufferedPlaybackAnchor(
    val point: MainBufferedTimelinePoint,
    val networkTime: MainBufferedNetworkTime? = null,
    val firstAudiblePoint: MainBufferedTimelinePoint? = null,
)

data class MainBufferedFlushPoint(
    val sequence: Long,
    val timestamp: Long,
    val mediaTimeValue: Long,
    val mediaTimeScale: Long,
) {
    init {
        require(sequence in 0..UINT32_MAX) { "flush sequence must be a UInt32" }
        require(timestamp in 0..UINT32_MAX) { "flush timestamp must be a UInt32" }
        require(mediaTimeScale in 1..INT32_MAX) {
            "flush media-time scale must be in 1..Int32.MAX_VALUE"
        }
    }
}

sealed interface MainBufferedControlCommand {
    val tag: MainBufferedControlTag
    val streamId: Long?
}

class MainBufferedSetRateAndAnchor internal constructor(
    override val streamId: Long?,
    val rate: Int,
    val anchor: MainBufferedPlaybackAnchor?,
) : MainBufferedControlCommand {
    override val tag = MainBufferedControlTag.SET_RATE_AND_ANCHOR
}

class MainBufferedSetRateReturnAnchor internal constructor(
    override val streamId: Long?,
    val rate: Int,
    val point: MainBufferedTimelinePoint?,
) : MainBufferedControlCommand {
    override val tag = MainBufferedControlTag.SET_RATE_RETURN_ANCHOR
}

class MainBufferedGetAnchor internal constructor(
    override val streamId: Long?,
) : MainBufferedControlCommand {
    override val tag = MainBufferedControlTag.GET_ANCHOR
}

class MainBufferedFlush internal constructor(
    override val streamId: Long?,
    val from: MainBufferedFlushPoint?,
    val until: MainBufferedFlushPoint,
) : MainBufferedControlCommand {
    override val tag = MainBufferedControlTag.FLUSH_BUFFERED
}

class MainBufferedAudioPlaybackVolumeFade internal constructor(
    val fadeType: Long,
    val fadeDuration: Double,
) : MainBufferedControlCommand {
    override val tag = MainBufferedControlTag.AUDIO_PLAYBACK_VOLUME_FADE
    override val streamId: Long? = null
}

class MainBufferedLoudnessNormalization internal constructor(
    val enabled: Boolean,
) : MainBufferedControlCommand {
    override val tag = MainBufferedControlTag.LOUDNESS_NORMALIZATION
    override val streamId: Long? = null
}

class MainBufferedMatAtmosPlayback internal constructor(
    val enabled: Boolean,
) : MainBufferedControlCommand {
    override val tag = MainBufferedControlTag.MAT_ATMOS_PLAYBACK
    override val streamId: Long? = null
}

class MainBufferedMagicCookie internal constructor(
    val magicCookieId: Long,
    magicCookie: ByteArray,
) : MainBufferedControlCommand {
    private val storedMagicCookie = magicCookie.copyOf()
    val magicCookie: ByteArray get() = storedMagicCookie.copyOf()
    override val tag = MainBufferedControlTag.MAGIC_COOKIE
    override val streamId: Long? = null
}

class MainBufferedCryptorKey internal constructor(
    val cryptorId: Long,
    pkdEncryptionKey: ByteArray,
) : MainBufferedControlCommand {
    private val storedPkdEncryptionKey = pkdEncryptionKey.copyOf()
    val pkdEncryptionKey: ByteArray get() = storedPkdEncryptionKey.copyOf()
    override val tag = MainBufferedControlTag.UPDATE_CRYPTOR_KEY
    override val streamId: Long? = null
}

class MainBufferedProtocolUpdate internal constructor(
    appCertificate: ByteArray,
    protocolVersionList: List<Long>,
) : MainBufferedControlCommand {
    private val storedAppCertificate = appCertificate.copyOf()
    val appCertificate: ByteArray get() = storedAppCertificate.copyOf()
    val protocolVersionList: List<Long> = Collections.unmodifiableList(protocolVersionList.toList())
    override val tag = MainBufferedControlTag.UPDATE_PROTOCOL
    override val streamId: Long? = null
}

/** Strict command-body parser; the detached APTransport envelope is modeled separately. */
object MainBufferedControlParser {
    private const val STREAM_ID = "streamID"
    private const val RATE = "rate"

    fun parse(tag: String, params: Any?): MainBufferedControlCommand {
        val parsedTag = MainBufferedControlTag.fromWireValue(tag)
        val values = MainBufferedWireSnapshot.requireMap(params, "${parsedTag.wireValue} params")
        return when (parsedTag) {
            MainBufferedControlTag.SET_RATE_AND_ANCHOR -> parseSetRateAndAnchor(values)
            MainBufferedControlTag.SET_RATE_RETURN_ANCHOR -> parseSetRateReturnAnchor(values)
            MainBufferedControlTag.GET_ANCHOR -> parseGetAnchor(values)
            MainBufferedControlTag.FLUSH_BUFFERED -> parseFlush(values)
            MainBufferedControlTag.AUDIO_PLAYBACK_VOLUME_FADE -> parseVolumeFade(values)
            MainBufferedControlTag.LOUDNESS_NORMALIZATION -> parseLoudness(values)
            MainBufferedControlTag.MAT_ATMOS_PLAYBACK -> parseMatAtmos(values)
            MainBufferedControlTag.MAGIC_COOKIE -> parseMagicCookie(values)
            MainBufferedControlTag.UPDATE_CRYPTOR_KEY -> parseCryptor(values)
            MainBufferedControlTag.UPDATE_PROTOCOL -> parseProtocolUpdate(values)
        }
    }

    private fun parseSetRateAndAnchor(values: Map<String, Any?>): MainBufferedSetRateAndAnchor {
        val anchorKeys = setOf(
            "rtpTime",
            "mediaTimeValue",
            "mediaTimeScale",
            "firstAudibleRTPTime",
            "firstAudibleMediaTimeValue",
            "firstAudibleMediaTimeScale",
            "networkTimeSecs",
            "networkTimeFrac",
            "networkTimeTimelineID",
            "networkTimeFlags",
        )
        val keys = anchorKeys + RATE + STREAM_ID
        MainBufferedParsing.requireOnly(values, keys, "srat params")
        val rate = MainBufferedParsing.rate(values[RATE], "srat.rate")
        val streamId = optionalStreamId(values, "srat")
        if (rate == 0) {
            require(values.keys.intersect(anchorKeys).isEmpty()) {
                "srat rate=0 must not carry anchor fields"
            }
            return MainBufferedSetRateAndAnchor(streamId, rate, null)
        }
        MainBufferedParsing.requireEvery(values, anchorKeys, "srat rate=1 params")
        return MainBufferedSetRateAndAnchor(
            streamId,
            rate,
            MainBufferedPlaybackAnchor(
                point = MainBufferedParsing.timelinePoint(values, prefix = "", label = "srat"),
                firstAudiblePoint = MainBufferedParsing.timelinePoint(
                    values,
                    prefix = "firstAudible",
                    label = "srat",
                ),
                networkTime = MainBufferedParsing.networkTime(
                    values,
                    prefix = "networkTime",
                    label = "srat",
                ),
            ),
        )
    }

    private fun parseSetRateReturnAnchor(
        values: Map<String, Any?>,
    ): MainBufferedSetRateReturnAnchor {
        val pointKeys = setOf("rtpTime", "mediaTimeValue", "mediaTimeScale")
        MainBufferedParsing.requireOnly(values, pointKeys + RATE + STREAM_ID, "strt params")
        val rate = MainBufferedParsing.rate(values[RATE], "strt.rate")
        val streamId = optionalStreamId(values, "strt")
        if (rate == 0) {
            require(values.keys.intersect(pointKeys).isEmpty()) {
                "strt rate=0 must not carry RTP/media-time fields"
            }
            return MainBufferedSetRateReturnAnchor(streamId, rate, null)
        }
        MainBufferedParsing.requireEvery(values, pointKeys, "strt rate=1 params")
        return MainBufferedSetRateReturnAnchor(
            streamId,
            rate,
            MainBufferedParsing.timelinePoint(values, prefix = "", label = "strt"),
        )
    }

    private fun parseGetAnchor(values: Map<String, Any?>): MainBufferedGetAnchor {
        MainBufferedParsing.requireOnly(values, setOf(STREAM_ID), "anch params")
        return MainBufferedGetAnchor(optionalStreamId(values, "anch"))
    }

    private fun parseFlush(values: Map<String, Any?>): MainBufferedFlush {
        val fromKeys = setOf(
            "flushFromSeq",
            "flushFromTS",
            "flushFromMediaTimeValue",
            "flushFromMediaTimeScale",
        )
        val untilKeys = setOf(
            "flushUntilSeq",
            "flushUntilTS",
            "flushUntilMediaTimeValue",
            "flushUntilMediaTimeScale",
        )
        MainBufferedParsing.requireOnly(values, fromKeys + untilKeys + STREAM_ID, "fshb params")
        MainBufferedParsing.requireEvery(values, untilKeys, "fshb params")
        val presentFrom = values.keys.intersect(fromKeys)
        require(presentFrom.isEmpty() || presentFrom == fromKeys) {
            "fshb flushFrom fields must be all present or all absent"
        }
        return MainBufferedFlush(
            streamId = optionalStreamId(values, "fshb"),
            from = if (presentFrom.isEmpty()) null else MainBufferedParsing.flushPoint(
                values,
                "flushFrom",
                "fshb",
            ),
            until = MainBufferedParsing.flushPoint(values, "flushUntil", "fshb"),
        )
    }

    private fun parseVolumeFade(
        values: Map<String, Any?>,
    ): MainBufferedAudioPlaybackVolumeFade {
        val keys = setOf("fadeType", "fadeDuration")
        MainBufferedParsing.requireOnly(values, keys, "apvf params")
        MainBufferedParsing.requireEvery(values, keys, "apvf params")
        val duration = MainBufferedNumbers.finiteNumber(values.getValue("fadeDuration"), "apvf.fadeDuration")
        require(duration in 0.0..MAX_FADE_DURATION_SECONDS) {
            "apvf.fadeDuration must be in 0..$MAX_FADE_DURATION_SECONDS seconds"
        }
        return MainBufferedAudioPlaybackVolumeFade(
            fadeType = MainBufferedNumbers.u32(values.getValue("fadeType"), "apvf.fadeType"),
            fadeDuration = duration,
        )
    }

    private fun parseLoudness(values: Map<String, Any?>): MainBufferedLoudnessNormalization {
        val key = "loudnessNormalizationEnabled"
        MainBufferedParsing.requireOnly(values, setOf(key), "loud params")
        MainBufferedParsing.requireEvery(values, setOf(key), "loud params")
        return MainBufferedLoudnessNormalization(
            MainBufferedParsing.boolean(values.getValue(key), "loud.$key"),
        )
    }

    private fun parseMatAtmos(values: Map<String, Any?>): MainBufferedMatAtmosPlayback {
        val key = "matAtmosPlaybackEnabled"
        MainBufferedParsing.requireOnly(values, setOf(key), "mata params")
        MainBufferedParsing.requireEvery(values, setOf(key), "mata params")
        return MainBufferedMatAtmosPlayback(
            MainBufferedParsing.boolean(values.getValue(key), "mata.$key"),
        )
    }

    private fun parseMagicCookie(values: Map<String, Any?>): MainBufferedMagicCookie {
        val keys = setOf("magicCookieID", "magicCookie")
        MainBufferedParsing.requireOnly(values, keys, "magc params")
        MainBufferedParsing.requireEvery(values, keys, "magc params")
        return MainBufferedMagicCookie(
            magicCookieId = MainBufferedNumbers.u64(values.getValue("magicCookieID"), "magc.magicCookieID"),
            magicCookie = MainBufferedParsing.data(values.getValue("magicCookie"), "magc.magicCookie"),
        )
    }

    private fun parseCryptor(values: Map<String, Any?>): MainBufferedCryptorKey {
        val keys = setOf("cryptorID", "pkdEncryptionKey")
        MainBufferedParsing.requireOnly(values, keys, "upck params")
        MainBufferedParsing.requireEvery(values, keys, "upck params")
        return MainBufferedCryptorKey(
            cryptorId = MainBufferedNumbers.u64(values.getValue("cryptorID"), "upck.cryptorID"),
            pkdEncryptionKey = MainBufferedParsing.data(
                values.getValue("pkdEncryptionKey"),
                "upck.pkdEncryptionKey",
            ),
        )
    }

    private fun parseProtocolUpdate(values: Map<String, Any?>): MainBufferedProtocolUpdate {
        val keys = setOf("appCertificate", "protocolVersionList")
        MainBufferedParsing.requireOnly(values, keys, "uppr params")
        MainBufferedParsing.requireEvery(values, keys, "uppr params")
        val versions = values.getValue("protocolVersionList") as? List<*>
            ?: throw IllegalArgumentException("uppr.protocolVersionList must be an array")
        require(versions.isNotEmpty() && versions.size <= MAX_PROTOCOL_VERSIONS) {
            "uppr.protocolVersionList must contain 1..$MAX_PROTOCOL_VERSIONS versions"
        }
        return MainBufferedProtocolUpdate(
            appCertificate = MainBufferedParsing.data(
                values.getValue("appCertificate"),
                "uppr.appCertificate",
            ),
            protocolVersionList = versions.mapIndexed { index, value ->
                MainBufferedNumbers.u32(value, "uppr.protocolVersionList[$index]")
            },
        )
    }

    private fun optionalStreamId(values: Map<String, Any?>, label: String): Long? =
        if (values.containsKey(STREAM_ID)) {
            MainBufferedNumbers.u32(values[STREAM_ID], "$label.streamID")
        } else {
            null
        }

    private const val MAX_FADE_DURATION_SECONDS = 3_600.0
    private const val MAX_PROTOCOL_VERSIONS = 64
}

sealed interface MainBufferedControlResponse {
    val tag: MainBufferedControlTag
    val status: Int
}

class MainBufferedSetRateResponse internal constructor(
    val firstAvailable: MainBufferedTimelinePoint?,
    val renderStartNetworkTime: MainBufferedNetworkTime?,
    override val status: Int,
) : MainBufferedControlResponse {
    override val tag = MainBufferedControlTag.SET_RATE_AND_ANCHOR
}

class MainBufferedCurrentAnchorResponse internal constructor(
    override val tag: MainBufferedControlTag,
    val anchor: MainBufferedPlaybackAnchor?,
    override val status: Int,
) : MainBufferedControlResponse

class MainBufferedEmptyResponse internal constructor(
    override val tag: MainBufferedControlTag,
) : MainBufferedControlResponse {
    override val status: Int = 0
}

class MainBufferedStatusResponse internal constructor(
    override val tag: MainBufferedControlTag,
    override val status: Int,
) : MainBufferedControlResponse

/** Strict response parser, kept separate from request parsing to prevent direction confusion. */
object MainBufferedControlResponseParser {
    fun parse(tag: String, params: Any?): MainBufferedControlResponse {
        val parsedTag = MainBufferedControlTag.fromWireValue(tag)
        val values = MainBufferedWireSnapshot.requireMap(params, "${parsedTag.wireValue} response")
        return when (parsedTag) {
            MainBufferedControlTag.SET_RATE_AND_ANCHOR -> parseSetRate(values)
            MainBufferedControlTag.SET_RATE_RETURN_ANCHOR,
            MainBufferedControlTag.GET_ANCHOR,
            -> parseCurrentAnchor(parsedTag, values)
            MainBufferedControlTag.FLUSH_BUFFERED,
            MainBufferedControlTag.AUDIO_PLAYBACK_VOLUME_FADE,
            MainBufferedControlTag.LOUDNESS_NORMALIZATION,
            MainBufferedControlTag.MAT_ATMOS_PLAYBACK,
            -> {
                MainBufferedParsing.requireOnly(values, emptySet(), "${parsedTag.wireValue} response")
                MainBufferedEmptyResponse(parsedTag)
            }
            MainBufferedControlTag.MAGIC_COOKIE,
            MainBufferedControlTag.UPDATE_CRYPTOR_KEY,
            MainBufferedControlTag.UPDATE_PROTOCOL,
            -> {
                MainBufferedParsing.requireOnly(values, setOf("status"), "${parsedTag.wireValue} response")
                MainBufferedParsing.requireEvery(values, setOf("status"), "${parsedTag.wireValue} response")
                MainBufferedStatusResponse(
                    parsedTag,
                    MainBufferedNumbers.i32(values.getValue("status"), "${parsedTag.wireValue}.status"),
                )
            }
        }
    }

    private fun parseSetRate(values: Map<String, Any?>): MainBufferedSetRateResponse {
        val firstKeys = setOf(
            "firstAvailableSampleTS",
            "firstAvailableMediaTimeValue",
            "firstAvailableMediaTimeScale",
        )
        val networkKeys = setOf(
            "renderStartNetworkTimeSecs",
            "renderStartNetworkTimeFrac",
            "renderStartNetworkTimeTimelineID",
            "renderStartNetworkTimeFlags",
        )
        MainBufferedParsing.requireOnly(values, firstKeys + networkKeys + "status", "srat response")
        MainBufferedParsing.requireEvery(values, setOf("status"), "srat response")
        val status = MainBufferedNumbers.i32(values.getValue("status"), "srat.status")
        val firstPresent = values.keys.intersect(firstKeys)
        val networkPresent = values.keys.intersect(networkKeys)
        require(firstPresent.isEmpty() || firstPresent == firstKeys) {
            "srat firstAvailable fields must be all present or all absent"
        }
        require(networkPresent.isEmpty() || networkPresent == networkKeys) {
            "srat renderStartNetworkTime fields must be all present or all absent"
        }
        if (status != 0) {
            require(firstPresent.isEmpty() && networkPresent.isEmpty()) {
                "failed srat response must not carry success timing fields"
            }
        }
        val first = if (firstPresent.isEmpty()) null else MainBufferedTimelinePoint(
            rtpTime = MainBufferedNumbers.u32(
                values.getValue("firstAvailableSampleTS"),
                "srat.firstAvailableSampleTS",
            ),
            mediaTimeValue = MainBufferedNumbers.i64(
                values.getValue("firstAvailableMediaTimeValue"),
                "srat.firstAvailableMediaTimeValue",
            ),
            mediaTimeScale = MainBufferedNumbers.positiveScale(
                values.getValue("firstAvailableMediaTimeScale"),
                "srat.firstAvailableMediaTimeScale",
            ),
        )
        val network = if (networkPresent.isEmpty()) null else MainBufferedParsing.networkTime(
            values,
            prefix = "renderStartNetworkTime",
            label = "srat response",
        )
        return MainBufferedSetRateResponse(first, network, status)
    }

    private fun parseCurrentAnchor(
        tag: MainBufferedControlTag,
        values: Map<String, Any?>,
    ): MainBufferedCurrentAnchorResponse {
        val pointKeys = setOf("rtpTime", "mediaTimeValue", "mediaTimeScale")
        val networkKeys = setOf(
            "networkTimeSecs",
            "networkTimeFrac",
            "networkTimeTimelineID",
            "networkTimeFlags",
        )
        val businessKeys = pointKeys + networkKeys
        MainBufferedParsing.requireOnly(values, businessKeys + "status", "${tag.wireValue} response")
        MainBufferedParsing.requireEvery(values, setOf("status"), "${tag.wireValue} response")
        val status = MainBufferedNumbers.i32(values.getValue("status"), "${tag.wireValue}.status")
        val present = values.keys.intersect(businessKeys)
        require(present.isEmpty() || present == businessKeys) {
            "${tag.wireValue} anchor fields must be all present or all absent"
        }
        require(status != 0 || present == businessKeys) {
            "successful ${tag.wireValue} response requires a complete anchor"
        }
        require(status == 0 || present.isEmpty()) {
            "failed ${tag.wireValue} response must not carry an anchor"
        }
        val anchor = if (present.isEmpty()) null else MainBufferedPlaybackAnchor(
            point = MainBufferedParsing.timelinePoint(values, prefix = "", label = tag.wireValue),
            networkTime = MainBufferedParsing.networkTime(
                values,
                prefix = "networkTime",
                label = tag.wireValue,
            ),
        )
        return MainBufferedCurrentAnchorResponse(tag, anchor, status)
    }
}

enum class MainBufferedPlaybackState {
    STOPPED,
    PLAYING,
}

/** Immutable defensive state returned by [MainBufferedControlStateReducer]. */
class MainBufferedControlSnapshot internal constructor(
    val streamId: Long?,
    val playbackState: MainBufferedPlaybackState,
    val anchor: MainBufferedPlaybackAnchor?,
    val flushGeneration: Long,
    val magicCookieGeneration: Long,
    val cryptorGeneration: Long,
    val magicCookieId: Long?,
    magicCookie: ByteArray?,
    val cryptorId: Long?,
    pkdEncryptionKey: ByteArray?,
) {
    private val storedMagicCookie = magicCookie?.copyOf()
    private val storedPkdEncryptionKey = pkdEncryptionKey?.copyOf()
    val magicCookie: ByteArray? get() = storedMagicCookie?.copyOf()
    val pkdEncryptionKey: ByteArray? get() = storedPkdEncryptionKey?.copyOf()

    internal fun internalMagicCookie(): ByteArray? = storedMagicCookie?.copyOf()
    internal fun internalPkdEncryptionKey(): ByteArray? = storedPkdEncryptionKey?.copyOf()
}

/**
 * Transactional reducer for confirmed type-103 control state.
 *
 * A command is committed only alongside its matching parsed response. A non-zero status, stream-ID
 * contradiction, generation overflow, or malformed command/response throws before [snapshot]
 * changes. No KDF, APTransport, socket, or decoder behavior is implied.
 */
class MainBufferedControlStateReducer(initialStreamId: Long? = null) {
    private val lock = Any()
    private var current = MainBufferedControlSnapshot(
        streamId = initialStreamId,
        playbackState = MainBufferedPlaybackState.STOPPED,
        anchor = null,
        flushGeneration = 0L,
        magicCookieGeneration = 0L,
        cryptorGeneration = 0L,
        magicCookieId = null,
        magicCookie = null,
        cryptorId = null,
        pkdEncryptionKey = null,
    )

    init {
        initialStreamId?.let {
            require(it in 0..UINT32_MAX) { "initial streamID must be a UInt32" }
        }
    }

    fun snapshot(): MainBufferedControlSnapshot = synchronized(lock) { copySnapshot(current) }

    fun reduce(
        command: MainBufferedControlCommand,
        response: MainBufferedControlResponse,
    ): MainBufferedControlSnapshot = synchronized(lock) {
        require(command.tag == response.tag) {
            "response tag ${response.tag.wireValue} does not match ${command.tag.wireValue}"
        }
        check(response.status == 0) {
            "${response.tag.wireValue} failed with status ${response.status}"
        }

        val prior = current
        val nextStreamId = reconcileStreamId(prior.streamId, command.streamId)
        var playback = prior.playbackState
        var anchor = prior.anchor
        var flushGeneration = prior.flushGeneration
        var cookieGeneration = prior.magicCookieGeneration
        var cryptorGeneration = prior.cryptorGeneration
        var cookieId = prior.magicCookieId
        var cookie = prior.internalMagicCookie()
        var cryptorId = prior.cryptorId
        var cryptorKey = prior.internalPkdEncryptionKey()

        when (command) {
            is MainBufferedSetRateAndAnchor -> {
                playback = if (command.rate == 0) {
                    MainBufferedPlaybackState.STOPPED
                } else {
                    MainBufferedPlaybackState.PLAYING
                }
                anchor = command.anchor
            }
            is MainBufferedSetRateReturnAnchor -> {
                playback = if (command.rate == 0) {
                    MainBufferedPlaybackState.STOPPED
                } else {
                    MainBufferedPlaybackState.PLAYING
                }
                anchor = (response as MainBufferedCurrentAnchorResponse).anchor
            }
            is MainBufferedGetAnchor -> {
                anchor = (response as MainBufferedCurrentAnchorResponse).anchor
            }
            is MainBufferedFlush -> {
                flushGeneration = Math.addExact(flushGeneration, 1L)
            }
            is MainBufferedMagicCookie -> {
                cookieGeneration = Math.addExact(cookieGeneration, 1L)
                cookieId = command.magicCookieId
                cookie = command.magicCookie
            }
            is MainBufferedCryptorKey -> {
                cryptorGeneration = Math.addExact(cryptorGeneration, 1L)
                cryptorId = command.cryptorId
                cryptorKey = command.pkdEncryptionKey
            }
            is MainBufferedAudioPlaybackVolumeFade,
            is MainBufferedLoudnessNormalization,
            is MainBufferedMatAtmosPlayback,
            is MainBufferedProtocolUpdate,
            -> Unit
        }

        MainBufferedControlSnapshot(
            streamId = nextStreamId,
            playbackState = playback,
            anchor = anchor,
            flushGeneration = flushGeneration,
            magicCookieGeneration = cookieGeneration,
            cryptorGeneration = cryptorGeneration,
            magicCookieId = cookieId,
            magicCookie = cookie,
            cryptorId = cryptorId,
            pkdEncryptionKey = cryptorKey,
        ).also {
            current = it
            return@synchronized copySnapshot(it)
        }
    }

    private fun reconcileStreamId(currentId: Long?, suppliedId: Long?): Long? {
        if (currentId != null && suppliedId != null && currentId != suppliedId) {
            throw IllegalArgumentException("control streamID does not match the active stream")
        }
        return currentId ?: suppliedId
    }

    private fun copySnapshot(value: MainBufferedControlSnapshot): MainBufferedControlSnapshot =
        MainBufferedControlSnapshot(
            streamId = value.streamId,
            playbackState = value.playbackState,
            anchor = value.anchor,
            flushGeneration = value.flushGeneration,
            magicCookieGeneration = value.magicCookieGeneration,
            cryptorGeneration = value.cryptorGeneration,
            magicCookieId = value.magicCookieId,
            magicCookie = value.internalMagicCookie(),
            cryptorId = value.cryptorId,
            pkdEncryptionKey = value.internalPkdEncryptionKey(),
        )
}

private object MainBufferedParsing {
    fun required(values: Map<String, Any?>, key: String, label: String): Any =
        values[key] ?: throw IllegalArgumentException("$label.$key is required")

    fun requireOnly(values: Map<String, Any?>, allowed: Set<String>, label: String) {
        val unknown = values.keys - allowed
        require(unknown.isEmpty()) { "$label contains unknown fields: ${unknown.sorted()}" }
    }

    fun requireEvery(values: Map<String, Any?>, required: Set<String>, label: String) {
        val missing = required - values.keys
        require(missing.isEmpty()) { "$label is missing fields: ${missing.sorted()}" }
    }

    fun boolean(value: Any?, label: String): Boolean = value as? Boolean
        ?: throw IllegalArgumentException("$label must be a Boolean")

    fun rate(value: Any?, label: String): Int {
        val parsed = MainBufferedNumbers.u32(
            value ?: throw IllegalArgumentException("$label is required"),
            label,
        )
        require(parsed == 0L || parsed == 1L) { "$label must be 0 or 1" }
        return parsed.toInt()
    }

    fun text(value: Any?, label: String): String {
        val text = value as? String ?: throw IllegalArgumentException("$label must be a string")
        require(text.isNotEmpty() && text.length <= MAX_TEXT_CHARS) {
            "$label must contain 1..$MAX_TEXT_CHARS characters"
        }
        require(Normalizer.isNormalized(text, Normalizer.Form.NFC)) { "$label must be NFC text" }
        require(text.none { it == '\u0000' || it.isISOControl() }) {
            "$label must not contain control characters"
        }
        return text
    }

    fun data(
        value: Any?,
        label: String,
        onCopiedData: ((ByteArray) -> Unit)? = null,
    ): ByteArray {
        val data = value as? ByteArray ?: throw IllegalArgumentException("$label must be Data")
        require(data.isNotEmpty() && data.size <= MAX_DATA_BYTES) {
            "$label must contain 1..$MAX_DATA_BYTES bytes"
        }
        return data.copyOf().also { onCopiedData?.invoke(it) }
    }

    fun timelinePoint(
        values: Map<String, Any?>,
        prefix: String,
        label: String,
    ): MainBufferedTimelinePoint {
        val rtpKey = if (prefix.isEmpty()) "rtpTime" else "${prefix}RTPTime"
        val valueKey = if (prefix.isEmpty()) "mediaTimeValue" else "${prefix}MediaTimeValue"
        val scaleKey = if (prefix.isEmpty()) "mediaTimeScale" else "${prefix}MediaTimeScale"
        return MainBufferedTimelinePoint(
            rtpTime = MainBufferedNumbers.u32(values.getValue(rtpKey), "$label.$rtpKey"),
            mediaTimeValue = MainBufferedNumbers.i64(values.getValue(valueKey), "$label.$valueKey"),
            mediaTimeScale = MainBufferedNumbers.positiveScale(
                values.getValue(scaleKey),
                "$label.$scaleKey",
            ),
        )
    }

    fun networkTime(
        values: Map<String, Any?>,
        prefix: String,
        label: String,
    ): MainBufferedNetworkTime = MainBufferedNetworkTime(
        seconds = MainBufferedNumbers.i32(values.getValue("${prefix}Secs"), "$label.${prefix}Secs"),
        fraction = MainBufferedNumbers.u64(
            values.getValue("${prefix}Frac"),
            "$label.${prefix}Frac",
        ),
        timelineId = MainBufferedNumbers.u64(
            values.getValue("${prefix}TimelineID"),
            "$label.${prefix}TimelineID",
        ),
        flags = MainBufferedNumbers.u32(
            values.getValue("${prefix}Flags"),
            "$label.${prefix}Flags",
        ),
    )

    fun flushPoint(
        values: Map<String, Any?>,
        prefix: String,
        label: String,
    ): MainBufferedFlushPoint = MainBufferedFlushPoint(
        sequence = MainBufferedNumbers.u32(values.getValue("${prefix}Seq"), "$label.${prefix}Seq"),
        timestamp = MainBufferedNumbers.u32(values.getValue("${prefix}TS"), "$label.${prefix}TS"),
        mediaTimeValue = MainBufferedNumbers.i64(
            values.getValue("${prefix}MediaTimeValue"),
            "$label.${prefix}MediaTimeValue",
        ),
        mediaTimeScale = MainBufferedNumbers.positiveScale(
            values.getValue("${prefix}MediaTimeScale"),
            "$label.${prefix}MediaTimeScale",
        ),
    )

    private const val MAX_TEXT_CHARS = 1_024
    private const val MAX_DATA_BYTES = 256 * 1_024
}

private object MainBufferedNumbers {
    private val UINT64_MAX_VALUE = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    private val LONG_MIN_VALUE = BigInteger.valueOf(Long.MIN_VALUE)
    private val LONG_MAX_VALUE = BigInteger.valueOf(Long.MAX_VALUE)

    fun u64(value: Any?, label: String): Long = when (value) {
        is Byte -> requireNonnegative(value.toLong(), label)
        is Short -> requireNonnegative(value.toLong(), label)
        is Int -> requireNonnegative(value.toLong(), label)
        is Long -> value
        is BigInteger -> {
            require(value.signum() >= 0 && value <= UINT64_MAX_VALUE) { "$label must be a UInt64" }
            value.toLong()
        }
        else -> throw IllegalArgumentException("$label must be a UInt64 integer")
    }

    fun u32(value: Any?, label: String): Long {
        val integer = nonnegativeBigInteger(value, label)
        require(integer <= BigInteger.valueOf(UINT32_MAX)) { "$label must be a UInt32" }
        return integer.toLong()
    }

    fun u16(value: Any?, label: String): Int {
        val integer = nonnegativeBigInteger(value, label)
        require(integer <= BigInteger.valueOf(UINT16_MAX.toLong())) { "$label must be a UInt16" }
        return integer.toInt()
    }

    fun i64(value: Any?, label: String): Long {
        val integer = signedBigInteger(value, label)
        require(integer in LONG_MIN_VALUE..LONG_MAX_VALUE) { "$label must be an Int64" }
        return integer.toLong()
    }

    fun i32(value: Any?, label: String): Int {
        val integer = signedBigInteger(value, label)
        require(integer in BigInteger.valueOf(Int.MIN_VALUE.toLong())..BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
            "$label must be an Int32"
        }
        return integer.toInt()
    }

    fun positiveScale(value: Any?, label: String): Long {
        val parsed = u32(value, label)
        require(parsed in 1..INT32_MAX) { "$label must be in 1..Int32.MAX_VALUE" }
        return parsed
    }

    fun boundedInteger(value: Any?, label: String): Any = when (value) {
        is Byte, is Short, is Int, is Long -> value
        is BigInteger -> {
            require(value.bitLength() <= 64) { "$label exceeds 64-bit integer bounds" }
            value
        }
        else -> throw IllegalArgumentException("$label must be a bounded integer")
    }

    fun finiteNumber(value: Any?, label: String): Double {
        val number = value as? Number ?: throw IllegalArgumentException("$label must be numeric")
        val result = number.toDouble()
        require(result.isFinite()) { "$label must be finite" }
        return result
    }

    fun fourCcOrU32(value: Any?, label: String): Long {
        if (value !is String) return u32(value, label)
        require(value.length == 4 && value.all { it.code in 0x20..0x7e }) {
            "$label FourCC must contain exactly four printable ASCII characters"
        }
        return value.toByteArray(StandardCharsets.US_ASCII).fold(0L) { result, byte ->
            result shl 8 or (byte.toInt() and 0xff).toLong()
        }
    }

    fun toWireUInt64(bits: Long): Number = if (bits >= 0) {
        bits
    } else {
        BigInteger.valueOf(bits).add(BigInteger.ONE.shiftLeft(64))
    }

    private fun requireNonnegative(value: Long, label: String): Long {
        require(value >= 0) { "$label must be a UInt64" }
        return value
    }

    private fun nonnegativeBigInteger(value: Any?, label: String): BigInteger {
        val integer = signedBigInteger(value, label)
        require(integer.signum() >= 0) { "$label must be unsigned" }
        return integer
    }

    private fun signedBigInteger(value: Any?, label: String): BigInteger = when (value) {
        is Byte -> BigInteger.valueOf(value.toLong())
        is Short -> BigInteger.valueOf(value.toLong())
        is Int -> BigInteger.valueOf(value.toLong())
        is Long -> BigInteger.valueOf(value)
        is BigInteger -> value
        else -> throw IllegalArgumentException("$label must be an integer")
    }
}

/** Bounded, detached copy of opaque plist-compatible values. */
private object MainBufferedWireSnapshot {
    fun requireMap(
        value: Any?,
        label: String,
        onCopiedData: ((ByteArray) -> Unit)? = null,
    ): Map<String, Any?> = Copier(onCopiedData).copyMapRoot(value, label)

    fun copyMap(
        value: Map<String, Any?>,
        label: String,
        onCopiedData: ((ByteArray) -> Unit)? = null,
    ): Map<String, Any?> = Copier(onCopiedData).copyMapRoot(value, label)

    fun copyList(
        value: List<*>,
        label: String,
        onCopiedData: ((ByteArray) -> Unit)? = null,
    ): List<Any?> = Copier(onCopiedData).copyListRoot(value, label)

    fun copyValue(
        value: Any?,
        label: String,
        onCopiedData: ((ByteArray) -> Unit)? = null,
    ): Any? = Copier(onCopiedData).copyRoot(value, label)

    private class Copier(private val onCopiedData: ((ByteArray) -> Unit)? = null) {
        private var nodes = 0
        private var entries = 0
        private var stringChars = 0L
        private var dataBytes = 0L
        private val active = IdentityHashMap<Any, Unit>()
        private val copiedData = ArrayList<ByteArray>()

        fun copyMapRoot(value: Any?, label: String): Map<String, Any?> = wipeOnFailure {
            val result = copy(value, 0, label)
            @Suppress("UNCHECKED_CAST")
            result as? Map<String, Any?>
                ?: throw IllegalArgumentException("$label must be a dictionary")
        }

        fun copyListRoot(value: List<*>, label: String): List<Any?> = wipeOnFailure {
            val result = copy(value, 0, label)
            @Suppress("UNCHECKED_CAST")
            result as List<Any?>
        }

        fun copyRoot(value: Any?, label: String): Any? = wipeOnFailure {
            copy(value, 0, label)
        }

        private inline fun <T> wipeOnFailure(action: () -> T): T = try {
            action()
        } catch (failure: Throwable) {
            // A later malformed plist entry must not strand an earlier copied raw shk/Data
            // buffer until GC. Only successful root copies transfer ownership to their caller.
            copiedData.forEach { it.fill(0) }
            throw failure
        }

        private fun copy(value: Any?, depth: Int, label: String): Any? {
            require(depth <= MAX_DEPTH) { "$label exceeds nesting depth $MAX_DEPTH" }
            require(++nodes <= MAX_NODES) { "$label exceeds $MAX_NODES values" }
            return when (value) {
                null,
                is Boolean,
                is Byte,
                is Short,
                is Int,
                is Long,
                is Float,
                is Double,
                -> value.also {
                    if (value is Float) require(value.isFinite()) { "$label contains non-finite Float" }
                    if (value is Double) require(value.isFinite()) { "$label contains non-finite Double" }
                }
                is BigInteger -> value.also {
                    require(value.bitLength() <= 64) { "$label contains an oversized integer" }
                }
                is String -> value.also {
                    stringChars = Math.addExact(stringChars, value.length.toLong())
                    require(stringChars <= MAX_STRING_CHARS) { "$label contains too much text" }
                }
                is ByteArray -> {
                    dataBytes = Math.addExact(dataBytes, value.size.toLong())
                    require(dataBytes <= MAX_DATA_BYTES) { "$label contains too much Data" }
                    val data = value.copyOf()
                    try {
                        copiedData.add(data)
                        onCopiedData?.invoke(data)
                        data
                    } catch (failure: Throwable) {
                        // Registration itself may fail (including OOME) after copyOf has
                        // succeeded, before the outer root-failure handler can see this Data.
                        // A registered copy may be wiped again there; the caller's input is
                        // never touched.
                        data.fill(0)
                        throw failure
                    }
                }
                is List<*> -> copyList(value, depth, label)
                is Map<*, *> -> copyMap(value, depth, label)
                else -> throw IllegalArgumentException(
                    "$label contains unsupported value ${value::class.java.simpleName}",
                )
            }
        }

        private fun copyList(source: List<*>, depth: Int, label: String): List<Any?> =
            guardCollection(source, label) {
                val result = ArrayList<Any?>()
                val iterator = source.iterator()
                var index = 0
                while (iterator.hasNext()) {
                    require(index < MAX_COLLECTION_SIZE) {
                        "$label contains too many array entries"
                    }
                    entries = Math.addExact(entries, 1)
                    require(entries <= MAX_ENTRIES) {
                        "$label contains too many aggregate collection entries"
                    }
                    result += copy(iterator.next(), depth + 1, "$label[$index]")
                    index++
                }
                Collections.unmodifiableList(result)
            }

        private fun copyMap(source: Map<*, *>, depth: Int, label: String): Map<String, Any?> =
            guardCollection(source, label) {
                val result = LinkedHashMap<String, Any?>()
                val iterator = source.entries.iterator()
                var containerEntries = 0
                while (iterator.hasNext()) {
                    require(containerEntries < MAX_COLLECTION_SIZE) {
                        "$label contains too many dictionary entries"
                    }
                    entries = Math.addExact(entries, 1)
                    require(entries <= MAX_ENTRIES) {
                        "$label contains too many aggregate collection entries"
                    }
                    val entry = iterator.next()
                    val name = entry.key as? String
                        ?: throw IllegalArgumentException("$label dictionary keys must be strings")
                    require(!result.containsKey(name)) { "$label contains duplicate key '$name'" }
                    stringChars = Math.addExact(stringChars, name.length.toLong())
                    require(stringChars <= MAX_STRING_CHARS) { "$label contains too much text" }
                    result[name] = copy(entry.value, depth + 1, "$label.$name")
                    containerEntries++
                }
                Collections.unmodifiableMap(result)
            }

        private inline fun <T> guardCollection(source: Any, label: String, action: () -> T): T {
            require(active.put(source, Unit) == null) { "$label contains a collection cycle" }
            return try {
                action()
            } finally {
                active.remove(source)
            }
        }
    }

    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 1_024
    private const val MAX_ENTRIES = 1_024
    private const val MAX_COLLECTION_SIZE = 256
    private const val MAX_STRING_CHARS = 64 * 1_024L
    private const val MAX_DATA_BYTES = 512 * 1_024L
}

private const val UINT16_MAX = 0xffff
private const val UINT32_MAX = 0xffff_ffffL
private const val INT32_MAX = Int.MAX_VALUE.toLong()
