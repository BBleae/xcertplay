package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.BplistDecodeLimits
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import java.math.BigInteger
import java.util.UUID

/**
 * Value-free structural description of a UI Sync carrier the router rejected.
 *
 * It exists for Debug bench markers only. The rendered text contains the container format, the
 * total byte size, dictionary key names with value *type* tokens (at most
 * [UiSyncCarrierShapeDescriber.MAXIMUM_LEVELS] dictionary levels), and the class plus a reduced
 * message of the rejection. Key names are allow-listed: only the fixed UI Sync schema keys and the
 * command-data keys this codebase encodes or decodes appear verbatim; every other key is rendered
 * as `<key#N>` (N numbers distinct unknown keys within one description). The message keeps only
 * words from a fixed vocabulary of this codebase's own error texts and digit runs of at most three
 * digits; longer digit runs become `<n>`, local identifiers `<id>`, and anything else `<?>`. String,
 * number and data values are never rendered.
 */
internal class UiSyncCarrierShape internal constructor(
    val kind: CarPlayUiSyncRoutingDiagnosticKind,
    private val rendered: String,
) {
    /** Fixed `key=value` grammar without the `ULTRA_STATUS event=` prefix. */
    fun render(): String = rendered

    override fun toString(): String = rendered
}

internal object UiSyncCarrierShapeDescriber {
    const val MAXIMUM_LEVELS = 3
    const val MAXIMUM_RENDERED_CHARS = 720
    private const val MAXIMUM_KEYS_PER_DICTIONARY = 16
    private const val MAXIMUM_MESSAGE_CHARS = 120
    private const val MAXIMUM_CAUSE_DEPTH = 4

    fun describe(
        kind: CarPlayUiSyncRoutingDiagnosticKind,
        carrier: ByteArray,
        rejection: Throwable?,
        localDisplayIds: Collection<String> = emptyList(),
        localVehicleIds: Collection<String> = emptyList(),
        limits: CarPlayUiSyncLimits = CarPlayUiSyncLimits(),
    ): UiSyncCarrierShape {
        val identifiers = Identifiers(localDisplayIds, localVehicleIds)
        val format = format(carrier)
        val structure = runCatching { decodeTop(format, carrier, limits) }.getOrNull()
        val tree = structure?.let { (value, trailing) ->
            val described = runCatching { describeValue(value, 1, identifiers, limits) }
                .getOrElse { "undescribable" }
            if (trailing > 0) "$described+trailing" else described
        } ?: "undecodable"
        val rendered = buildString {
            append("kind=").append(kind.name)
            append(" fmt=").append(format.token)
            append(" size=").append(carrier.size)
            append(" shape=").append(tree)
            append(" err=").append(renderFailure(rejection, identifiers))
        }
        val bounded = if (rendered.length <= MAXIMUM_RENDERED_CHARS) rendered
            else rendered.take(MAXIMUM_RENDERED_CHARS - 1) + "~"
        return UiSyncCarrierShape(kind, bounded)
    }

    private enum class Format(val token: String) {
        EMPTY("empty"),
        BPLIST00("bplist00"),
        BPLIST_OTHER("bplist-other"),
        XML("xml"),
        JSON("json"),
        OPACK_DICTIONARY("opack-dict"),
        OPACK_ARRAY("opack-array"),
        OPACK_OTHER("opack-scalar"),
        OTHER("other"),
    }

    private fun format(bytes: ByteArray): Format {
        if (bytes.isEmpty()) return Format.EMPTY
        if (bytes.startsWithAscii("bplist00")) return Format.BPLIST00
        if (bytes.startsWithAscii("bplist")) return Format.BPLIST_OTHER
        if (bytes.startsWithAscii("<?xml") || bytes.startsWithAscii("<plist")) return Format.XML
        val first = bytes[0].toInt() and 0xff
        return when (first) {
            in 0xe0..0xef -> Format.OPACK_DICTIONARY
            in 0xd0..0xdf -> Format.OPACK_ARRAY
            '{'.code, '['.code -> Format.JSON
            else -> Format.OTHER
        }
    }

    private fun decodeTop(
        format: Format,
        bytes: ByteArray,
        limits: CarPlayUiSyncLimits,
    ): Pair<Any?, Int>? = when (format) {
        Format.BPLIST00 -> BplistCodec.decode(bytes, bplistLimits(limits, bytes.size)) to 0
        Format.OPACK_DICTIONARY, Format.OPACK_ARRAY ->
            OpackCodec.decodePrefix(bytes, limits.carrierOpackLimits()).let {
                it.value to it.remaining.size
            }
        else -> null
    }

    private fun describeValue(
        value: Any?,
        level: Int,
        identifiers: Identifiers,
        limits: CarPlayUiSyncLimits,
    ): String = when (value) {
        null -> "null"
        is Boolean -> "bool"
        is Byte, is Short, is Int, is Long, is BigInteger -> "int"
        is Float, is Double -> "real"
        is UUID -> "uuid"
        is String -> identifiers.stringToken(value)
        is ByteArray -> describeData(value, level, identifiers, limits)
        is List<*> -> "arr(${value.size})"
        is Map<*, *> -> describeMap(value, level, identifiers, limits)
        else -> "other"
    }

    private fun describeMap(
        map: Map<*, *>,
        level: Int,
        identifiers: Identifiers,
        limits: CarPlayUiSyncLimits,
    ): String {
        if (level > MAXIMUM_LEVELS) return "dict(${map.size})"
        val entries = map.entries
            .map { (key, value) ->
                val name = (key as? String)?.let(identifiers::sanitizeKey) ?: "<nonstring>"
                name to describeValue(value, level + 1, identifiers, limits)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
        val shown = entries.take(MAXIMUM_KEYS_PER_DICTIONARY)
            .joinToString(",") { (name, type) -> "$name:$type" }
        val more = entries.size - MAXIMUM_KEYS_PER_DICTIONARY
        return if (more > 0) "{$shown,+$more}" else "{$shown}"
    }

    private fun describeData(
        data: ByteArray,
        level: Int,
        identifiers: Identifiers,
        limits: CarPlayUiSyncLimits,
    ): String {
        val format = format(data)
        val nested = when (format) {
            Format.BPLIST00, Format.OPACK_DICTIONARY, Format.OPACK_ARRAY -> runCatching {
                decodeTop(format, data, limits)
            }.getOrNull()
            else -> null
        }
        val token = when (format) {
            Format.BPLIST00, Format.BPLIST_OTHER, Format.XML,
            Format.OPACK_DICTIONARY, Format.OPACK_ARRAY,
            -> "data(${format.token})"
            else -> "data"
        }
        if (nested == null) {
            return if (format == Format.BPLIST00 || format == Format.OPACK_DICTIONARY) {
                "$token!undecodable"
            } else token
        }
        val (value, trailing) = nested
        val described = describeValue(value, level, identifiers, limits)
        return token + described + if (trailing > 0) "+trailing" else ""
    }

    private fun renderFailure(rejection: Throwable?, identifiers: Identifiers): String {
        rejection ?: return "none"
        val chain = ArrayList<Throwable>(MAXIMUM_CAUSE_DEPTH)
        var current: Throwable? = rejection
        while (current != null && chain.size < MAXIMUM_CAUSE_DEPTH && current !in chain) {
            chain += current
            current = current.cause
        }
        val outer = chain.first()
        val root = chain.last()
        val rendered = StringBuilder(renderThrowable(outer, identifiers))
        if (root !== outer) rendered.append(" cause=").append(renderThrowable(root, identifiers))
        return rendered.toString()
    }

    private fun renderThrowable(failure: Throwable, identifiers: Identifiers): String {
        val name = failure.javaClass.simpleName.ifEmpty { "Throwable" }
            .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
        val message = failure.message?.let(identifiers::sanitizeMessage).orEmpty()
        return if (message.isEmpty()) name else "$name:\"$message\""
    }

    private fun bplistLimits(limits: CarPlayUiSyncLimits, size: Int) = BplistDecodeLimits(
        maxInputBytes = maxOf(size, 1).coerceAtMost(limits.maximumCarrierBytes),
        maxObjects = limits.maximumCollectionEntries * 4 + 32,
        maxObjectBytes = limits.maximumCarrierBytes,
        maxNestingDepth = limits.maximumNestingDepth,
        maxContainerItems = limits.maximumCollectionEntries,
        maxDataBytes = limits.maximumInnerMessageBytes,
        maxStringBytes = limits.maximumStringChars * 4,
        maxDecodedBytes = limits.maximumCarrierBytes.toLong() * 2L,
    )

    private fun ByteArray.startsWithAscii(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it].toInt() == prefix[it].code }

    private class Identifiers(
        localDisplayIds: Collection<String>,
        localVehicleIds: Collection<String>,
    ) {
        private val displayIds = localDisplayIds.filter { it.isNotEmpty() }.toSet()
        private val vehicleIds = localVehicleIds.filter { it.isNotEmpty() }.toSet()
        private val longestFirst = (displayIds + vehicleIds).sortedByDescending { it.length }
        private val unknownKeys = HashMap<String, Int>()

        /** A type token for a string value. The value itself is never returned. */
        fun stringToken(value: String): String = when {
            value in displayIds -> "str(local-display)"
            value in vehicleIds -> "str(local-vehicle)"
            UUID_PATTERN.matches(value) -> "str(uuid)"
            else -> "str"
        }

        /** Allow-listed schema key, or a per-description ordinal for any other key. */
        fun sanitizeKey(key: String): String {
            if (key in ALLOWED_KEYS) return key
            val ordinal = unknownKeys.getOrPut(key) { unknownKeys.size + 1 }
            return "<key#$ordinal>"
        }

        fun sanitizeMessage(message: String): String {
            var redacted = message
            longestFirst.forEach { redacted = redacted.replace(it, LOCAL_ID_TOKEN) }
            // Identifier-shaped runs are named by kind before tokenizing, so not even their short
            // digit groups survive.
            redacted = redacted
                .replace(UUID_SEARCH, UUID_TOKEN)
                .replace(PREFIXED_HEX, HEX_TOKEN)
                .replace(LONG_HEX, HEX_TOKEN)
            val reduced = StringBuilder(minOf(redacted.length, MAXIMUM_MESSAGE_CHARS * 2))
            MESSAGE_TOKEN.findAll(redacted).forEach { match ->
                val token = match.value
                val first = token[0]
                reduced.append(
                    when {
                        token == LOCAL_ID_TOKEN || token == UUID_TOKEN || token == HEX_TOKEN ->
                            token
                        first.isDigitAscii() -> if (token.length <= MAXIMUM_DIGIT_RUN) token
                            else "<n>"
                        first.isLetterAscii() || first == '_' -> if (token in MESSAGE_VOCABULARY) {
                            token
                        } else {
                            UNKNOWN_TOKEN
                        }
                        first.isWhitespace() -> " "
                        first in MESSAGE_PUNCTUATION -> first.toString()
                        else -> UNKNOWN_TOKEN
                    },
                )
            }
            val printable = reduced.toString()
                .replace(REPEATED_UNKNOWN, UNKNOWN_TOKEN)
                .replace(WHITESPACE, " ")
                .trim()
            return if (printable.length <= MAXIMUM_MESSAGE_CHARS) printable
                else printable.take(MAXIMUM_MESSAGE_CHARS - 1) + "~"
        }

        private fun Char.isDigitAscii() = this in '0'..'9'
        private fun Char.isLetterAscii() = this in 'a'..'z' || this in 'A'..'Z'
    }

    private const val LOCAL_ID_TOKEN = "<id>"
    private const val UUID_TOKEN = "<uuid>"
    private const val HEX_TOKEN = "<hex>"
    private const val UNKNOWN_TOKEN = "<?>"
    private const val MAXIMUM_DIGIT_RUN = 3

    /** A placeholder, an ASCII word, an ASCII digit run, whitespace, or any other single char. */
    private val MESSAGE_TOKEN = Regex(
        "<id>|<uuid>|<hex>|[A-Za-z_][A-Za-z0-9_]*|[0-9]+|\\s+|.",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val REPEATED_UNKNOWN = Regex("<\\?>(?:[\\s'.,:;=()\\[\\]/+#-]*<\\?>)+")
    private val MESSAGE_PUNCTUATION: Set<Char> = ".,:;=()[]/+#-'".toSet()

    /**
     * Key names this codebase itself encodes or decodes: the OPACK outer carrier, the inner message,
     * the ResetComplete payload, the command envelope, and the command `data` keys of the typed
     * accessory builders and semantic decoder.
     */
    private val ALLOWED_KEYS: Set<String> = setOf(
        // Outer carrier, inner message, ResetComplete payload, command envelope.
        "displayUUID", "data", "type", "ssn", "psn", "asn", "id", "version", "noAck", "config",
        // Command data keys (CarPlayUiSyncAccessoryCommands, CarPlayUiSyncSemanticState).
        "activeComponents", "allow", "appearanceMode", "colorPaletteId", "configurations",
        "current_ntp_time", "driveMode", "driveModeToLayoutIdAssignments", "fadeInNewLayout",
        "fadeOutOldLayout", "isTransitioning", "layoutID", "layoutId", "layoutIdentifier",
        "linked", "override", "paletteID", "paletteIdentifier", "preference",
        "presentation_host_time", "presentation_ntp_time", "presentation_ntp_time_original",
        "regions", "requestData", "targetAppearance", "targetComponents", "tokenID",
        "uiConfiguration", "wallpaperId",
    )

    /**
     * Words occurring in this codebase's own UI Sync, OPACK and binary-plist error texts. A peer can
     * influence which of these words appear, but never introduce a word outside the set.
     */
    private val MESSAGE_VOCABULARY: Set<String> = setOf(
        "a", "absolute", "accepted", "acknowledge", "after", "already", "ambiguous", "an", "and",
        "array", "arrays", "asn", "at", "bad", "be", "before", "between", "binary", "bit",
        "boolean", "bound", "boundary", "bplist", "budget", "but", "by", "byte", "bytes",
        "cannot", "carrier", "carriers", "changed", "channel", "characters", "clock", "closed",
        "collection", "command", "commandAck", "commands", "complete", "config", "conflicting",
        "contain", "contains", "content", "count", "current", "cyclic", "data", "decoded",
        "depth", "dictionaries", "dictionary", "did", "display", "displayId", "displayUUID",
        "does", "dropped", "duplicate", "empty", "encode", "encoded", "encoding", "end",
        "entries", "entry", "exceeded", "exceeds", "exhausted", "expected", "expects",
        "exponent", "extended", "extension", "extensions", "field", "first", "for", "has",
        "header", "here", "ID", "id", "identifier", "in", "index", "inner", "input", "instead",
        "integer", "invalid", "Invalid", "is", "its", "JVM", "key", "keys", "length", "limit",
        "magic", "match", "message", "missing", "monotonic", "must", "negative", "negotiated",
        "nesting", "noAck", "non", "not", "null", "object", "of", "offset", "only", "OPACK",
        "open", "or", "out", "outside", "overflows", "overlaps", "payload", "pending", "phone",
        "plist", "position", "positive", "primitive", "printable", "probe", "protocol", "psn",
        "range", "reach", "readiness", "reading", "ready", "real", "received", "reference",
        "regressed", "repeat", "representation", "require", "required", "requires", "reserved",
        "reset", "resetComplete", "router", "sequence", "serialized", "set", "short", "signed",
        "size", "slice", "spoke", "ssn", "state", "string", "strings", "surrogate", "Sync",
        "table", "tag", "the", "time", "too", "trailer", "trailing", "transmit", "truncated",
        "type", "typed", "UI", "unacknowledged", "Unexpected", "Unknown", "unknown", "unpaired",
        "unsupported", "Unsupported", "until", "use", "UTF", "UUID", "valid", "value", "values",
        "vehicle", "version", "was", "while", "width", "with", "without",
        // Enum names that appear in session errors.
        "RESET", "RESET_COMPLETE", "COMMAND", "COMMAND_ACK", "SERIALIZED_PLIST", "DICTIONARY",
        "INITIALIZED", "AWAITING_RESET_SELF_INITIATED",
        "AWAITING_RESET_COMPLETE_REMOTE_INITIATED", "READY",
    )

    private val UUID_PATTERN = Regex(
        "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}",
    )
    private val UUID_SEARCH = Regex(
        "[0-9A-Fa-f]{8}-?[0-9A-Fa-f]{4}-?[0-9A-Fa-f]{4}-?[0-9A-Fa-f]{4}-?[0-9A-Fa-f]{12}",
    )
    private val PREFIXED_HEX = Regex("0[xX][0-9A-Fa-f]+")
    /** At least eight hex digits including a letter; a pure decimal run becomes `<n>` instead. */
    private val LONG_HEX = Regex("(?=[0-9A-Fa-f]*[A-Fa-f])[0-9A-Fa-f]{8,}")
    private val WHITESPACE = Regex("\\s+")
}
