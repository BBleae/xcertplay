package com.shilapi.xcertplay.airplay.rcs.caf

sealed interface CafDirectionalMessage {
    val envelope: CafEnvelope

    fun asFrame(codec: CafRcsCodec = CafRcsCodec()): CafRcsFrame = codec.encodeEnvelope(envelope)

    data class Request(override val envelope: CafEnvelope) : CafDirectionalMessage {
        init {
            require(envelope.message.command in CafCommand.REQUESTS)
        }
    }

    data class Response(override val envelope: CafEnvelope) : CafDirectionalMessage {
        init {
            require(envelope.message.command in CafCommand.RESPONSES)
        }
    }

    data class Notification(override val envelope: CafEnvelope) : CafDirectionalMessage {
        init {
            require(envelope.message.command in CafCommand.NOTIFICATIONS)
        }
    }

    data class Error(override val envelope: CafEnvelope) : CafDirectionalMessage {
        init {
            require(envelope.message.command == CafCommand.GENERAL_ERROR)
        }
    }

    companion object {
        fun from(frame: CafRcsFrame): CafDirectionalMessage = when (frame.envelope.message.command) {
            in CafCommand.REQUESTS -> Request(frame.envelope)
            in CafCommand.RESPONSES -> Response(frame.envelope)
            in CafCommand.NOTIFICATIONS -> Notification(frame.envelope)
            CafCommand.GENERAL_ERROR -> Error(frame.envelope)
            else -> throw CafProtocolException("Unknown CAF message direction")
        }
    }
}
