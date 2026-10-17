package com.shilapi.xcertplay.ultra.egl

import com.shilapi.xcertplay.ultra.PixelSize
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class InstrumentRgbaUploadStagingTest {
    @Test
    fun repeatedCompleteFramesReuseExactlyOneDirectBufferAndPreserveRgbaOrder() {
        var allocations = 0
        val staging = InstrumentRgbaUploadStaging(PixelSize(2, 1)) { byteCount ->
            allocations++
            assertEquals(8, byteCount)
            ByteBuffer.allocateDirect(byteCount)
        }
        var firstBuffer: ByteBuffer? = null
        staging.upload(intArrayOf(0xff123456.toInt(), 0xffa0b0c0.toInt())) { bytes ->
            firstBuffer = bytes
            assertArrayEquals(
                byteArrayOf(0x12, 0x34, 0x56, -1, -96, -80, -64, -1),
                snapshot(bytes),
            )
        }
        staging.upload(intArrayOf(0xff010203.toInt(), 0xfffefdfc.toInt())) { bytes ->
            assertSame(firstBuffer, bytes)
            assertArrayEquals(
                byteArrayOf(1, 2, 3, -1, -2, -3, -4, -1),
                snapshot(bytes),
            )
        }
        assertEquals(1, allocations)
    }

    @Test
    fun invalidFrameCannotMutatePriorBufferOrAllocateAnother() {
        var allocations = 0
        val staging = InstrumentRgbaUploadStaging(PixelSize(1, 1)) {
            allocations++
            ByteBuffer.allocateDirect(it)
        }
        lateinit var bytes: ByteBuffer
        staging.upload(intArrayOf(0xff112233.toInt())) { bytes = it }
        assertThrows(IllegalArgumentException::class.java) {
            staging.upload(intArrayOf()) { throw AssertionError("Invalid frame consumed") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            staging.upload(intArrayOf(0x00112233)) {
                throw AssertionError("Transparent frame consumed")
            }
        }
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, -1), snapshot(bytes))
        assertEquals(1, allocations)
    }

    @Test
    fun inFlightUploadCannotBeReenteredOrRetiredBeforeNativeCompletion() {
        val staging = InstrumentRgbaUploadStaging(PixelSize(1, 1))
        staging.upload(intArrayOf(0xff112233.toInt())) {
            assertThrows(IllegalStateException::class.java) {
                staging.upload(intArrayOf(0xff445566.toInt())) { }
            }
            assertThrows(IllegalStateException::class.java) { staging.retire() }
        }
        staging.upload(intArrayOf(0xff445566.toInt())) { bytes ->
            assertArrayEquals(byteArrayOf(0x44, 0x55, 0x66, -1), snapshot(bytes))
        }
    }

    @Test
    fun retirementAndNewIngressNeverShareStagingOrAcceptStaleUpload() {
        var allocations = 0
        val allocate: (Int) -> ByteBuffer = {
            allocations++
            ByteBuffer.allocateDirect(it)
        }
        val old = InstrumentRgbaUploadStaging(PixelSize(1, 1), allocate)
        lateinit var oldBytes: ByteBuffer
        old.upload(intArrayOf(0xff010203.toInt())) { oldBytes = it }
        old.retire()
        assertThrows(IllegalStateException::class.java) {
            old.upload(intArrayOf(0xff040506.toInt())) { }
        }
        val replacement = InstrumentRgbaUploadStaging(PixelSize(1, 1), allocate)
        replacement.upload(intArrayOf(0xff040506.toInt())) { bytes ->
            assertNotSame(oldBytes, bytes)
            assertArrayEquals(byteArrayOf(4, 5, 6, -1), snapshot(bytes))
        }
        assertEquals(2, allocations)
    }

    @Test
    fun failedNativeUploadPoisonsBufferRatherThanRecyclingUncertainBytes() {
        val staging = InstrumentRgbaUploadStaging(PixelSize(1, 1))
        assertThrows(IllegalStateException::class.java) {
            staging.upload(intArrayOf(0xff112233.toInt())) {
                throw IllegalStateException("native transfer failed")
            }
        }
        assertThrows(IllegalStateException::class.java) {
            staging.upload(intArrayOf(0xff445566.toInt())) { }
        }
        staging.retire()
    }

    @Test
    fun oversizedViewportCannotAllocateOrOverflowByteCount() {
        assertThrows(IllegalArgumentException::class.java) {
            InstrumentRgbaUploadStaging(PixelSize(1601, 1000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstrumentRgbaUploadStaging(PixelSize(Int.MAX_VALUE, Int.MAX_VALUE))
        }
    }

    private fun snapshot(bytes: ByteBuffer): ByteArray =
        ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) }
}
