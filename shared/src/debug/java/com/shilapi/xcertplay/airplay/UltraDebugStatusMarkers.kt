package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCarrierShape

/** Never accepts a protocol identifier, plist, vehicle value, or free-form message. */
internal object UltraDebugStatusMarkers {
    const val enabled = true

    fun emit(sink: (String) -> Unit, event: UltraDebugStatusEvent, displayAlias: String? = null) {
        val alias = displayAlias?.takeIf { DISPLAY_ALIAS.matches(it) } ?: "none"
        runCatching { sink("ULTRA_STATUS event=${event.name} display=$alias") }
    }

    /**
     * Emits the redacted, value-free structure of a rejected UI Sync carrier. The only accepted
     * input is a [UiSyncCarrierShape], whose renderer emits key names, type tokens, sizes, and a
     * redacted exception class/message only.
     */
    fun emitCarrierShape(sink: (String) -> Unit, shape: UiSyncCarrierShape) {
        runCatching {
            sink("ULTRA_STATUS event=${UltraDebugStatusEvent.UI_CARRIER_SHAPE.name} ${shape.render()}")
        }
    }

    /** Fixed fields and bounded counts only; never includes a socket address or media content. */
    fun emitScreenCandidate(sink: (String) -> Unit, diagnostic: ScreenCandidateDiagnostic) {
        runCatching {
            sink(
                "ULTRA_STATUS event=SCREEN_CANDIDATE " +
                    "type=${diagnostic.screenType} stage=${diagnostic.stage.name} " +
                    "accepted=${diagnostic.acceptedCandidates} " +
                    "wrongPeer=${diagnostic.wrongPeers} exactPeer=${diagnostic.exactPeers} " +
                    "preAuthConfig=${diagnostic.preAuthConfigs} " +
                    "preAuthTimeout=${diagnostic.preAuthTimeouts} " +
                    "preAuthFailure=${diagnostic.preAuthFailures} " +
                    "authenticated=${diagnostic.authenticatedCandidates}",
            )
        }
    }

    private val DISPLAY_ALIAS = Regex("d(?:[0-9]|[1-5][0-9]|6[0-3])")

    /** Command wire type and its enum name only; never the command data. */
    fun emitCommand(sink: (String) -> Unit, type: Int, knownName: String?, shape: String) {
        val name = knownName?.takeIf { COMMAND_NAME.matches(it) } ?: "unknown"
        runCatching { sink("ULTRA_STATUS event=UI_COMMAND type=$type name=$name data=${shape.take(400)}") }
    }

    private val COMMAND_NAME = Regex("[A-Z][A-Z0-9_]{0,63}")

    /** Reveal-pipeline markers, already reduced to a fixed grammar by [UltraRevealTrace]. */
    fun emitLine(line: String) {
        runCatching { Log.i(TAG, line) }
    }

    private const val TAG = "xcertplay-usb"
}
