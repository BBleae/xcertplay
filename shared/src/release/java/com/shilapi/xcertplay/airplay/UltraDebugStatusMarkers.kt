package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCarrierShape

/** Release builds do not construct or deliver bench status messages. */
internal object UltraDebugStatusMarkers {
    const val enabled = false

    @Suppress("UNUSED_PARAMETER")
    fun emit(sink: (String) -> Unit, event: UltraDebugStatusEvent, displayAlias: String? = null) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun emitCarrierShape(sink: (String) -> Unit, shape: UiSyncCarrierShape) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun emitScreenCandidate(sink: (String) -> Unit, diagnostic: ScreenCandidateDiagnostic) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun emitCommand(sink: (String) -> Unit, type: Int, knownName: String?, shape: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun emitLine(line: String) = Unit
}
