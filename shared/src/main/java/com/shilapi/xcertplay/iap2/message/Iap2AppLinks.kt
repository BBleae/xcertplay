package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import com.shilapi.xcertplay.iap2.wire.Iap2WireCodec

enum class Iap2AppLinksCategory(val id: Int) {
    MESSAGING(1),
    CALLING(2),
    NAVIGATION(3),
    AUDIO(4),
    AUTOMAKER(5),
    QUICK_ORDERING(7),
    CHARGING(8),
    PARKING(9),
    PRODUCTIVITY(10),
    FUELING(11),
    DRIVING_TASK(12),
}

/** Passive metadata from 0xAD01. It is not an authorization to launch the named app. */
data class Iap2AppLinkEntry(
    val index: Int,
    val bundleIdentifier: String,
    val localizedName: String,
    /** Raw u16: future phone categories are preserved but are never claimed as supported. */
    val categoryId: Int,
    /** Optional 16-byte icon fingerprint. This is metadata, not image bytes or a transfer grant. */
    val iconHash: Iap2AppLinksIconHash? = null,
)

/** Opaque SHA256_16 value; copies bytes and never prints a fingerprint into diagnostics. */
class Iap2AppLinksIconHash private constructor(private val digest: ByteArray) {
    fun bytes(): ByteArray = digest.copyOf()

    override fun equals(other: Any?): Boolean =
        other is Iap2AppLinksIconHash && digest.contentEquals(other.digest)

    override fun hashCode(): Int = digest.contentHashCode()

    override fun toString(): String = "Iap2AppLinksIconHash(<redacted>)"

    companion object {
        const val SIZE_BYTES = 16

        fun of(bytes: ByteArray): Iap2AppLinksIconHash {
            require(bytes.size == SIZE_BYTES) { "AppLinks icon hash must be $SIZE_BYTES bytes" }
            return Iap2AppLinksIconHash(bytes.copyOf())
        }
    }
}

/** A bounded, passive AppLinks update. [reportedCount] has no inferred relationship to [entries]. */
data class Iap2AppLinksUpdate(
    val state: Int,
    val entries: List<Iap2AppLinkEntry>,
    val reportedCount: Int?,
)

/** Parsed AD03 request; CarPlay and ExternalAccessory bundle IDs remain separate subfeatures. */
data class Iap2AppLinksIconRequest(
    val carPlayBundleIdentifiers: List<String>,
    val externalAccessoryBundleIdentifiers: List<String>,
) {
    override fun toString(): String =
        "Iap2AppLinksIconRequest(carPlay=${carPlayBundleIdentifiers.size}, " +
            "externalAccessory=${externalAccessoryBundleIdentifiers.size})"
}

/** AD04 metadata only. Image bytes, if any, arrive later over the separate iAP2 FileTransfer. */
data class Iap2AppLinksIconResponse(
    val bundleIdentifier: String,
    /** 0 = no image, 1 = image available. */
    val status: Int,
    val fileTransferBufferId: Int?,
    val iconHash: Iap2AppLinksIconHash?,
) {
    override fun toString(): String =
        "Iap2AppLinksIconResponse(status=$status, " +
            "hasTransfer=${fileTransferBufferId != null}, hasHash=${iconHash != null})"
}

/** The confirmed iOS 27 AppLinks metadata and passive icon-message shapes; no app launch. */
object Iap2AppLinksMessages {
    const val START_ID = 0xad00
    const val UPDATE_ID = 0xad01
    const val STOP_ID = 0xad02
    const val ICON_REQUEST_ID = 0xad03
    const val ICON_RESPONSE_ID = 0xad04

    /** IDs identified in the phone's category filter; category 6 is not in that filter. */
    val SUPPORTED_CATEGORY_IDS: Set<Int> = Iap2AppLinksCategory.values().map { it.id }.toSet()

    const val MAX_UPDATE_BODY_BYTES = 32 * 1024
    const val MAX_APP_ENTRIES = 128
    const val MAX_ICON_REQUEST_BODY_BYTES = 24 * 1024
    const val MAX_ICON_REQUEST_ENTRIES = 64
    const val MAX_ICON_RESPONSE_BODY_BYTES = 2 * 1024
    private const val MAX_ROOT_PARAMETERS = 160
    private const val MAX_UNKNOWN_ROOT_PARAMETERS = 16
    private const val MAX_UNKNOWN_FIELD_BYTES = 1_024
    private const val MAX_ENTRY_BYTES = 2_048
    private const val MAX_ENTRY_PARAMETERS = 20
    private const val MAX_UNKNOWN_ENTRY_PARAMETERS = 12
    private const val MAX_TEXT_BYTES = 256

    /** Explicit opt-in only. Root 1 is omitted because its semantics are not yet verified. */
    fun start(categoryIds: Set<Int>, preferredIconSize: Int? = null): Iap2Frame {
        require(categoryIds.isNotEmpty()) { "AppLinks start requires at least one category" }
        require(categoryIds.all { it in SUPPORTED_CATEGORY_IDS }) {
            "AppLinks start contains an unverified category"
        }
        require(preferredIconSize == null || preferredIconSize in 1..1_024) {
            "AppLinks preferred icon size must be in 1..1024 pixels"
        }
        return Iap2Messages.build(Iap2Endpoints.APP_LINKS_START) {
            group(0) { categoryIds.sorted().forEach { void(it) } }
            optionalU16(2, preferredIconSize)
        }
    }

    fun stop(): Iap2Frame = Iap2Messages.build(Iap2Endpoints.APP_LINKS_STOP) {}

    /** Validates wire shape and local resource budgets before any metadata reaches a Host UI. */
    fun update(frame: Iap2Frame): Iap2AppLinksUpdate {
        require(frame.messageId == Iap2Endpoints.APP_LINKS_UPDATE.id) {
            "Expected AppLinksUpdate, got 0x${frame.messageId.toString(16)}"
        }
        val bytes = frame.payload
        if (bytes.size > MAX_UPDATE_BODY_BYTES) {
            throw Iap2ProtocolException("AppLinksUpdate exceeds local body limit")
        }
        val fields = Iap2ParameterList.parse(bytes).asList()
        if (fields.size > MAX_ROOT_PARAMETERS || fields.count { it.id !in 0..2 } > MAX_UNKNOWN_ROOT_PARAMETERS) {
            throw Iap2ProtocolException("AppLinksUpdate has too many parameters")
        }
        rejectOversizeUnknown(fields, known = 0..2)
        val state = scalarU8(exactlyOne(fields, 0), "state")
        if (state !in 0..2) throw Iap2ProtocolException("AppLinksUpdate state is not recognized: $state")
        val count = atMostOne(fields, 2)?.let { scalarU16(it, "reported count") }
        if (state != 2 && count != null) {
            throw Iap2ProtocolException("AppLinksUpdate reported count is only valid for state 2")
        }
        val encodedEntries = fields.filter { it.id == 1 }
        if (encodedEntries.size > MAX_APP_ENTRIES) {
            throw Iap2ProtocolException("AppLinksUpdate exceeds local app entry limit")
        }
        val entries = encodedEntries.map(::decodeEntry)
        if (entries.map(Iap2AppLinkEntry::index).toSet().size != entries.size) {
            throw Iap2ProtocolException("AppLinksUpdate repeats an app index")
        }
        return Iap2AppLinksUpdate(state, entries, count)
    }

    private fun decodeEntry(parameter: Iap2Parameter): Iap2AppLinkEntry {
        val bytes = parameter.payload
        if (bytes.size > MAX_ENTRY_BYTES) throw Iap2ProtocolException("AppLinks entry exceeds local size limit")
        val fields = Iap2ParameterList.parse(bytes).asList()
        if (fields.size > MAX_ENTRY_PARAMETERS || fields.count { it.id !in 0..4 } > MAX_UNKNOWN_ENTRY_PARAMETERS) {
            throw Iap2ProtocolException("AppLinks entry has too many parameters")
        }
        rejectOversizeUnknown(fields, known = 0..4)
        return Iap2AppLinkEntry(
            index = scalarU16(exactlyOne(fields, 0), "app index"),
            bundleIdentifier = boundedString(exactlyOne(fields, 1), "bundle identifier"),
            localizedName = boundedString(exactlyOne(fields, 2), "localized name"),
            categoryId = scalarU16(exactlyOne(fields, 3), "category"),
            iconHash = atMostOne(fields, 4)?.let { decodeIconHash(it, "app entry icon hash") },
        )
    }

    /**
     * An explicit, offline AD03 builder. Nothing calls this from Identification or automatically
     * sends it: an owner must separately negotiate AD03/AD04 and bind FileTransfer to its session.
     */
    fun requestIcons(
        carPlayBundleIdentifiers: Collection<String>,
        externalAccessoryBundleIdentifiers: Collection<String> = emptyList(),
    ): Iap2Frame {
        val carPlay = validatedRequestBundles(carPlayBundleIdentifiers)
        val externalAccessory = validatedRequestBundles(externalAccessoryBundleIdentifiers)
        require(carPlay.isNotEmpty() || externalAccessory.isNotEmpty()) {
            "AppLinks icon request requires at least one bundle identifier"
        }
        require(carPlay.size + externalAccessory.size <= MAX_ICON_REQUEST_ENTRIES) {
            "AppLinks icon request exceeds local entry limit"
        }
        val frame = Iap2Messages.buildRaw(ICON_REQUEST_ID) {
            carPlay.forEach { string(0, it) }
            externalAccessory.forEach { string(1, it) }
        }
        require(frame.payload.size <= MAX_ICON_REQUEST_BODY_BYTES) {
            "AppLinks icon request exceeds local body limit"
        }
        return frame
    }

    /** Strict AD03 parser for fixture inspection; the live endpoint does not receive AD03. */
    fun iconRequest(frame: Iap2Frame): Iap2AppLinksIconRequest {
        require(frame.messageId == ICON_REQUEST_ID) { "Expected AppLinks icon request" }
        val fields = boundedIconFields(frame, MAX_ICON_REQUEST_BODY_BYTES, MAX_ICON_REQUEST_ENTRIES)
        if (fields.isEmpty()) throw Iap2ProtocolException("AppLinks icon request is empty")
        val carPlay = ArrayList<String>()
        val externalAccessory = ArrayList<String>()
        fields.forEach { field ->
            val destination = when (field.id) {
                0 -> carPlay
                1 -> externalAccessory
                else -> throw Iap2ProtocolException("AppLinks icon request has an unknown subfeature")
            }
            destination += boundedString(field, "icon request bundle identifier")
        }
        if (carPlay.size != carPlay.toSet().size ||
            externalAccessory.size != externalAccessory.toSet().size
        ) {
            throw Iap2ProtocolException("AppLinks icon request repeats a bundle identifier")
        }
        return Iap2AppLinksIconRequest(carPlay, externalAccessory)
    }

    /** AD04 announces transfer metadata; the image is not present in this CSM body. */
    fun iconResponse(frame: Iap2Frame): Iap2AppLinksIconResponse {
        require(frame.messageId == ICON_RESPONSE_ID) { "Expected AppLinks icon response" }
        val fields = boundedIconFields(frame, MAX_ICON_RESPONSE_BODY_BYTES, 4)
        if (fields.any { it.id !in 0..3 }) {
            throw Iap2ProtocolException("AppLinks icon response has an unknown field")
        }
        val bundleIdentifier = boundedString(exactlyOne(fields, 0), "icon response bundle identifier")
        val status = scalarU8(exactlyOne(fields, 1), "icon response status")
        if (status !in 0..1) throw Iap2ProtocolException("AppLinks icon response status is not recognized")
        val transfer = atMostOne(fields, 2)?.let { scalarU8(it, "icon transfer buffer ID") }
        val hash = atMostOne(fields, 3)?.let { decodeIconHash(it, "icon response hash") }
        if ((status == 0 && (transfer != null || hash != null)) ||
            (status == 1 && (transfer == null || hash == null))
        ) {
            throw Iap2ProtocolException("AppLinks icon response fields do not match status")
        }
        return Iap2AppLinksIconResponse(bundleIdentifier, status, transfer, hash)
    }

    private fun validatedRequestBundles(bundleIdentifiers: Collection<String>): List<String> {
        require(bundleIdentifiers.size <= MAX_ICON_REQUEST_ENTRIES) {
            "AppLinks icon request exceeds local entry limit"
        }
        val values = bundleIdentifiers.toList()
        require(values.size == values.toSet().size) {
            "AppLinks icon request repeats a bundle identifier"
        }
        values.forEach { value ->
            val encoded = Iap2WireCodec.string(value)
            try {
                boundedString(Iap2Parameter(0, encoded), "icon request bundle identifier")
            } catch (error: Iap2ProtocolException) {
                throw IllegalArgumentException(error.message, error)
            }
        }
        return values
    }

    private fun boundedIconFields(frame: Iap2Frame, maxBodyBytes: Int, maxFields: Int): List<Iap2Parameter> {
        val body = frame.payload
        if (body.size > maxBodyBytes) throw Iap2ProtocolException("AppLinks icon body exceeds local limit")
        val fields = Iap2ParameterList.parse(body).asList()
        if (fields.size > maxFields) throw Iap2ProtocolException("AppLinks icon body has too many fields")
        return fields
    }

    private fun decodeIconHash(parameter: Iap2Parameter, label: String): Iap2AppLinksIconHash {
        val bytes = parameter.payload
        if (bytes.size != Iap2AppLinksIconHash.SIZE_BYTES) {
            throw Iap2ProtocolException("AppLinks $label must be SHA256_16")
        }
        return Iap2AppLinksIconHash.of(bytes)
    }

    private fun exactlyOne(fields: List<Iap2Parameter>, id: Int): Iap2Parameter =
        atMostOne(fields, id)
            ?: throw Iap2ProtocolException("AppLinks field $id is missing")

    private fun atMostOne(fields: List<Iap2Parameter>, id: Int): Iap2Parameter? {
        val matches = fields.filter { it.id == id }
        if (matches.size > 1) throw Iap2ProtocolException("AppLinks field $id is duplicated")
        return matches.singleOrNull()
    }

    private fun scalarU8(parameter: Iap2Parameter, label: String): Int {
        val payload = parameter.payload
        if (payload.size != 1) throw Iap2ProtocolException("AppLinks $label must be u8")
        return Iap2WireCodec.readU8(payload)
    }

    private fun scalarU16(parameter: Iap2Parameter, label: String): Int {
        val payload = parameter.payload
        if (payload.size != 2) throw Iap2ProtocolException("AppLinks $label must be u16")
        return Iap2WireCodec.readU16(payload)
    }

    private fun boundedString(parameter: Iap2Parameter, label: String): String {
        val payload = parameter.payload
        if (payload.size !in 2..MAX_TEXT_BYTES + 1 || payload.last() != 0.toByte() ||
            payload.dropLast(1).any { it == 0.toByte() }
        ) {
            throw Iap2ProtocolException("AppLinks $label is not a bounded NUL-terminated string")
        }
        val textBytes = payload.copyOf(payload.size - 1)
        val value = textBytes.decodeToString()
        if (value.isBlank() || !value.encodeToByteArray().contentEquals(textBytes) ||
            value.any { Character.isISOControl(it) ||
                it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
        ) {
            throw Iap2ProtocolException("AppLinks $label is not valid display text")
        }
        return value
    }

    private fun rejectOversizeUnknown(fields: List<Iap2Parameter>, known: IntRange) {
        if (fields.any { it.id !in known && it.payload.size > MAX_UNKNOWN_FIELD_BYTES }) {
            throw Iap2ProtocolException("AppLinks unknown field exceeds local size limit")
        }
    }
}

/** One explicitly controlled AppLinks subscription; the supplied auth check binds it to its MFi session. */
internal class Iap2AppLinksLifecycle(
    private val send: (Iap2Frame) -> Unit,
    private val isAuthorized: () -> Boolean,
) {
    private var started = false
    /** A Stop was attempted but its wire outcome cannot be known after a send failure. */
    private var stopUncertain = false
    private var closed = false
    private var generation = 0L

    val isStarted: Boolean get() = synchronized(this) { started && !closed }
    val isStopUncertain: Boolean get() = synchronized(this) { stopUncertain && !closed }

    /** Captured before parsing an update, so Stop→Start cannot revive an old frame. */
    @Synchronized
    fun activeGeneration(): Long? = generation.takeIf { started && !closed }

    @Synchronized
    fun start(categoryIds: Set<Int>, preferredIconSize: Int? = null): Boolean {
        val frame = Iap2AppLinksMessages.start(categoryIds, preferredIconSize)
        if (closed || started || stopUncertain || !isAuthorized()) return false
        send(frame)
        generation = Math.addExact(generation, 1L)
        started = true
        return true
    }

    @Synchronized
    fun stop(): Boolean {
        if (!started || closed) return false
        started = false
        generation = Math.addExact(generation, 1L)
        // The send can throw after bytes have left this process. Do not retry on this CSM, and
        // do not let a later AD01 or Start imply that the subscription is known to be stopped.
        stopUncertain = true
        if (!isAuthorized()) {
            return false
        }
        send(Iap2AppLinksMessages.stop())
        stopUncertain = false
        return true
    }

    /**
     * Serializes delivery with Stop/close. Parsing may finish after an old subscription stops;
     * that update must not reach a newly connected Host UI. A callback can begin only while this
     * exact subscription is active, and Stop/close cannot complete until it returns.
     */
    @Synchronized
    fun deliverIfStarted(
        update: Iap2AppLinksUpdate,
        expectedGeneration: Long,
        callback: (Iap2AppLinksUpdate) -> Unit,
    ): Boolean {
        if (!started || closed || generation != expectedGeneration || !isAuthorized()) return false
        callback(update)
        return true
    }

    /** Best effort on teardown; Stop is never sent unless this exact session sent Start. */
    @Synchronized
    fun close() {
        if (closed) return
        val shouldStop = started && isAuthorized()
        started = false
        closed = true
        generation = Math.addExact(generation, 1L)
        if (shouldStop) {
            try {
                send(Iap2AppLinksMessages.stop())
            } catch (_: Exception) {
                // The owned CSM session may already be closing.
            }
        }
    }
}
