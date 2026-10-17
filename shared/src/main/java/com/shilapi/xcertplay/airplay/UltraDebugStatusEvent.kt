package com.shilapi.xcertplay.airplay

/** Fixed, payload-free events for a parked Debug build's exact Ultra session. */
internal enum class UltraDebugStatusEvent {
    CLUSTER_LISTENING,
    CLUSTER_AUTHENTICATED,
    CLUSTER_CLOSED,
    UI_RESET_SENT,
    UI_RESET_RECEIVED,
    UI_RESET_COMPLETE_SENT,
    UI_RESET_COMPLETE_RECEIVED,
    UI_READY,
    UI_PROTOCOL_REJECTED,
    UI_MALFORMED_CARRIER,
    UI_UNKNOWN_DISPLAY,
    /** Value-free structure of a rejected carrier; see UiSyncCarrierShapeDescriber. */
    UI_CARRIER_SHAPE,
    RAW5_QUEUED,
    RAW5_WRITE_RETURNED,
    RAW5_ACK,
    RAW5_REJECTED,
    RAW5_WRITE_UNCERTAIN,
    SESSION_STOPPING,
    SESSION_CLOSED,
}
