package com.shilapi.xcertplay.ultra.instrument

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedOpaqueArgbFrameTest {
    @Test fun callerMutationCannotAlterCapturedSnapshot() {
        val mutableRaster = intArrayOf(0xff102030.toInt(), 0xff405060.toInt())
        val captured = requireNotNull(OwnedOpaqueArgbFrame.capture(mutableRaster))
        mutableRaster.fill(0xffeeeeee.toInt())

        val transferred = requireNotNull(captured.transfer())
        val uploaded = requireNotNull(transferred.take())
        assertNotSame(mutableRaster, uploaded)
        assertEquals(0xff102030.toInt(), uploaded[0])
        assertEquals(0xff405060.toInt(), uploaded[1])
    }

    @Test fun transferAndTakeAreOneShotAcrossEveryHandoff() {
        val first = requireNotNull(OwnedOpaqueArgbFrame.capture(intArrayOf(-1)))
        assertTrue(first.isCompleteFor(1L))
        val second = requireNotNull(first.transfer())
        assertFalse(first.isCompleteFor(1L))
        assertNull(first.transfer())
        assertNull(first.take())
        val third = requireNotNull(second.transfer())
        assertNull(second.transfer())
        assertEquals(-1, requireNotNull(third.take()).single())
        assertFalse(third.isCompleteFor(1L))
        assertNull(third.take())
        assertNull(third.transfer())
    }

    @Test fun transparencyIsRejectedAfterPrivateCopyAndCannotBeTransferred() {
        assertNull(OwnedOpaqueArgbFrame.capture(intArrayOf(0x00203040)))
        assertNull(OwnedOpaqueArgbFrame.capture(IntArray(1_600_001) { -1 }))
    }
}
