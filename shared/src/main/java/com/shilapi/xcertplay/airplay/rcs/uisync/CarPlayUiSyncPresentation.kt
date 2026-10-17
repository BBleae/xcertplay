package com.shilapi.xcertplay.airplay.rcs.uisync

/**
 * Presentation event delivered after a command has passed carrier, identity, sequence, and size
 * validation. Unknown command codes remain observable without being treated as a supported UI
 * capability.
 */
sealed interface CarPlayUiSyncPresentationEvent {
    val type: Int
    val data: Any?
    /** Receiver-local monotonic sample at command acceptance; unrelated to peer NTP fields. */
    val acceptedAtMonotonicNanos: Long?

    data class KnownCommand(
        val command: CarPlayUiSyncCommandType,
        override val data: Any?,
        override val acceptedAtMonotonicNanos: Long? = null,
        /**
         * Minted only by the receiving [CarPlayUiSyncChannel] (its constructor is internal to
         * this module): this type-6 command arrived, in the same channel sequence space and with
         * no Reset in between, after the phone had already acknowledged one of this accessory's
         * outstanding RequestLayouts, and its `data` equals that request's wire dictionary
         * exactly. It is a protocol-order attestation only: it neither names the request's
         * signed intent nor proves that anything was presented.
         */
        val acknowledgedRequestEcho: CarPlayUiSyncAcknowledgedRequestEcho? = null,
    ) : CarPlayUiSyncPresentationEvent {
        init {
            require(acknowledgedRequestEcho == null ||
                command == CarPlayUiSyncCommandType.END_LAYOUT_CHANGE
            ) { "Only a type-6 command can echo an acknowledged RequestLayout" }
        }

        /** True only for the token minted for this exact [data] object; `copy(data = …)` drops it. */
        val echoesAcknowledgedRequestLayout: Boolean
            get() = acknowledgedRequestEcho?.isFor(data) == true

        override val type: Int
            get() = command.wireValue
    }

    data class UnknownCommand(
        override val type: Int,
        override val data: Any?,
        override val acceptedAtMonotonicNanos: Long? = null,
    ) : CarPlayUiSyncPresentationEvent
}

/**
 * Unforgeable outside this module: only the receiving channel mints one, bound to the identity
 * of the exact command `data` it attested, so it cannot be transferred to other data.
 */
class CarPlayUiSyncAcknowledgedRequestEcho internal constructor(private val data: Any?) {
    internal fun isFor(candidate: Any?): Boolean = data === candidate
    override fun toString(): String = "CarPlayUiSyncAcknowledgedRequestEcho"
}

fun interface CarPlayUiSyncPresentationEventSink {
    fun onPresentationEvent(event: CarPlayUiSyncPresentationEvent)
}

internal fun CarPlayUiSyncCommandEnvelope.toPresentationEvent(
    acceptedAtMonotonicNanos: Long,
    echoesAcknowledgedRequestLayout: Boolean = false,
):
    CarPlayUiSyncPresentationEvent = knownType?.let { known ->
        CarPlayUiSyncPresentationEvent.KnownCommand(
            known, data, acceptedAtMonotonicNanos,
            CarPlayUiSyncAcknowledgedRequestEcho(data).takeIf {
                echoesAcknowledgedRequestLayout &&
                    known == CarPlayUiSyncCommandType.END_LAYOUT_CHANGE
            },
        )
    } ?: CarPlayUiSyncPresentationEvent.UnknownCommand(type, data, acceptedAtMonotonicNanos)
