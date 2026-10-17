package com.shilapi.xcertplay.airplay

import java.util.concurrent.atomic.AtomicIntegerArray

/** Fixed, payload-free stages of the signed MAIN reveal pipeline (Debug bench only). */
enum class UltraRevealEvent {
    /** Controller activation: phase=START|RESULT|FAIL. */
    REVEAL_CTRL_ACTIVATE,
    /** Terminal controller failure with exception class and a short sanitized message. */
    REVEAL_CTRL_FAILED,
    /** Assetless request-echo authority: decision=ACCEPTED|DENIED reason=... */
    REVEAL_ECHO_AUTHORITY,
    /** Standalone type-19 recovery: decision=ACCEPTED|DENIED reason=... */
    REVEAL_APPEARANCE,
    /** Phone Reset: covered re-request scheduling. */
    REVEAL_RESET_REREQUEST,
    /** One signed multi-output scene handed to EGL (revision, MAIN layout, phone choice). */
    REVEAL_SCENE_SUBMITTED,
    /** Exact MAIN semantic Host cover: phase=BEGIN|ACK|NACK. */
    REVEAL_MAIN_COVER,
    /** Fresh signed phone choice lifting the Authority's MAIN semantic cover. */
    REVEAL_MAIN_RESUME,
    /** Local Canvas: phase=POST|POST_FAILED|FIRST_FRAME|FIRST_FRAME_TIMEOUT painter=... */
    REVEAL_LOCAL_CANVAS,
    /** EGL scene ticket outcome per role: disposition=PRESENTED|FAILED|TIMEOUT. */
    REVEAL_SCENE_PRESENTED,
    /** raw5 foreground scene: phase=PRESENTED|DISPATCH|SUPERSEDED|RECEIPT|NOT_INSTALLED. */
    REVEAL_RAW5_SCENE,
    /** Authority MAIN presented-scene permit: decision=ARMED|DENIED reason=... */
    REVEAL_AUTHORITY_MAIN,
    /** Host MAIN UI cover gate state changes: state=COVERED|REVEALED|ARMED|ARM_REJECTED. */
    REVEAL_MAIN_GATE,
    /** An exception swallowed on this pipeline: where=... error=Class:message. */
    REVEAL_SWALLOWED,
    /** Host MAIN texture frame observation (timestamps and exact-current components only). */
    REVEAL_MAIN_TEXTURE,
}

/**
 * Debug-only, value-free reveal pipeline markers on logcat tag `xcertplay-usb`, in the existing
 * `ULTRA_STATUS event=...` grammar. Release builds format nothing ([enabled] is false there).
 *
 * Keys are fixed identifiers. Values are restricted to enum names, numbers, booleans, our own
 * short ASCII identifiers (such as a signed layout ID), and Throwable class + a sanitized,
 * truncated message. Anything else becomes `<redacted>`. Each event type is capped per process.
 */
object UltraRevealTrace {
    /** Compile-time constant: Release call sites guarded by it fold away with their arguments. */
    const val enabled: Boolean = UltraDebugStatusMarkers.enabled

    private val counts = AtomicIntegerArray(UltraRevealEvent.entries.size)

    /** Test-only additional observer of already formatted lines; never set by product code. */
    @Volatile var testObserver: ((String) -> Unit)? = null

    fun mark(event: UltraRevealEvent, vararg fields: Pair<String, Any?>) {
        if (!UltraDebugStatusMarkers.enabled) return
        val count = counts.incrementAndGet(event.ordinal)
        if (count > MAXIMUM_PER_EVENT + 1) return
        val line = if (count == MAXIMUM_PER_EVENT + 1) {
            "ULTRA_STATUS event=${event.name} suppressed=true"
        } else runCatching { format(event, *fields) }.getOrNull() ?: return
        UltraDebugStatusMarkers.emitLine(line)
        testObserver?.let { observer -> runCatching { observer(line) } }
    }

    internal fun format(event: UltraRevealEvent, vararg fields: Pair<String, Any?>): String =
        buildString {
            append("ULTRA_STATUS event=").append(event.name)
            fields.take(MAXIMUM_FIELDS).forEach { (key, value) ->
                if (!KEY.matches(key)) return@forEach
                append(' ').append(key).append('=').append(render(value))
            }
        }

    private fun render(value: Any?): String = when (value) {
        null -> "none"
        is Enum<*> -> value.name
        is Boolean, is Int, is Long, is Short, is Byte -> value.toString()
        is Throwable -> value.javaClass.simpleName.takeIf { SIMPLE.matches(it) }.orEmpty()
            .ifEmpty { "Throwable" } + ":" + (value.message.orEmpty()
                .map { if (it in MESSAGE_CHARS) it else '_' }.joinToString("")
                .take(MAXIMUM_MESSAGE_CHARS).replace(' ', '_'))
        is String -> value.takeIf { SAFE_VALUE.matches(it) } ?: "<redacted>"
        else -> "<redacted>"
    }

    private const val MAXIMUM_PER_EVENT = 400
    private const val MAXIMUM_FIELDS = 8
    private const val MAXIMUM_MESSAGE_CHARS = 96
    private val KEY = Regex("[a-z][A-Za-z0-9]{0,23}")
    private val SIMPLE = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val SAFE_VALUE = Regex("[A-Za-z0-9_.-]{1,64}")
    private val MESSAGE_CHARS: Set<Char> =
        (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(' ', '-', '_', '.', ',', ':')).toSet()
}
