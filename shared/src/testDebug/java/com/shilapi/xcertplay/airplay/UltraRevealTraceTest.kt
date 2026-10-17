package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraRevealTraceTest {
    @Test
    fun debugFormatKeepsOnlyFixedKeysAndValueFreeTokens() {
        assertTrue(UltraRevealTrace.enabled)
        val line = UltraRevealTrace.format(
            UltraRevealEvent.REVEAL_ECHO_AUTHORITY,
            "decision" to "ACCEPTED",
            "layout" to "main-local-canvas",
            "revision" to 7L,
            "fresh" to true,
            "role" to UltraRevealEvent.REVEAL_MAIN_GATE,
            "payload" to "phone value with spaces/and=separators",
            "Bad Key" to "dropped",
            "error" to IllegalStateException("Cover \"x\"=y did not ACK\nnext"),
        )
        assertEquals(
            "ULTRA_STATUS event=REVEAL_ECHO_AUTHORITY decision=ACCEPTED " +
                "layout=main-local-canvas revision=7 fresh=true role=REVEAL_MAIN_GATE " +
                "payload=<redacted> " +
                "error=IllegalStateException:Cover__x__y_did_not_ACK_next",
            line,
        )
    }

    @Test
    fun formatCapsFieldsAndMessageLength() {
        val fields = (0 until 12).map { "k$it" to it }.toTypedArray()
        val line = UltraRevealTrace.format(UltraRevealEvent.REVEAL_SWALLOWED, *fields)
        assertEquals(8, line.split(' ').count { it.startsWith("k") })
        val long = UltraRevealTrace.format(UltraRevealEvent.REVEAL_SWALLOWED,
            "error" to RuntimeException("a".repeat(500)))
        assertTrue(long.length < 160)
        assertEquals("ULTRA_STATUS event=REVEAL_SWALLOWED cause=none",
            UltraRevealTrace.format(UltraRevealEvent.REVEAL_SWALLOWED, "cause" to null))
    }
}
