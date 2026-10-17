package com.shilapi.xcertplay.airplay.rcs.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApTransportPackageCodecTest {
    @Test
    fun outboundCommPackageCarriesTheObservedAsyncDeliveryKind() {
        // Live iOS 27.0.1 capture: every phone `comm` header is
        // [size]['asyn'][0][0]['comm'][0][0][0]; a zero kind was silently ignored by the phone.
        val encoded = ApTransportPackageCodec.comm(byteArrayOf(1, 2, 3)).encoded()
        assertEquals(35, readU32(encoded, 0))
        assertEquals(0x6173796e, readU32(encoded, 4))
        assertEquals(0, readU32(encoded, 8))
        assertEquals(0, readU32(encoded, 12))
        assertEquals(0x636f6d6d, readU32(encoded, 16))
        for (offset in listOf(20, 24, 28)) assertEquals(0, readU32(encoded, offset))
        // Round-trip keeps the kind and the body.
        val decoded = ApTransportPackageCodec.decodeAvailable(encoded).packages.single()
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.body)
        assertEquals("6173796e.00000000.00000000.type.00000000.00000000.00000000",
            decoded.metadataHex)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)

    @Test
    fun decodesFragmentedAndConcatenatedPackages() {
        val first = ApTransportPackageCodec.comm(byteArrayOf(1, 2, 3))
        val second = ApTransportPackageCodec.comm(byteArrayOf(4, 5))
        val encoded = first.encoded() + second.encoded()

        val fragmented = ApTransportPackageCodec.decodeAvailable(encoded.copyOfRange(0, 20))
        assertTrue(fragmented.packages.isEmpty())
        assertEquals(20, fragmented.remainder.size)

        val complete = ApTransportPackageCodec.decodeAvailable(encoded)
        assertEquals(2, complete.packages.size)
        assertEquals(ApTransportPackageCodec.MESSAGE_TYPE_COMM, complete.packages[0].messageType)
        assertArrayEquals(byteArrayOf(1, 2, 3), complete.packages[0].body)
        assertArrayEquals(byteArrayOf(4, 5), complete.packages[1].body)
        assertEquals(0, complete.remainder.size)
    }

    @Test
    fun rejectsDeclaredSizeSmallerThanHeader() {
        val header = ByteArray(ApTransportPackageCodec.HEADER_SIZE)
        header[3] = 31

        val failure = assertThrows(ApTransportProtocolException::class.java) {
            ApTransportPackageCodec.decodeAvailable(header)
        }

        assertTrue(failure.message!!.contains("smaller than header"))
    }

    @Test
    fun rejectsUnsignedAndConfiguredOversizedDeclarationsBeforeAllocation() {
        val unsignedMaximum = ByteArray(ApTransportPackageCodec.HEADER_SIZE)
        unsignedMaximum.fill(0xff.toByte(), 0, 4)

        val unsignedFailure = assertThrows(ApTransportProtocolException::class.java) {
            ApTransportPackageCodec.decodeAvailable(unsignedMaximum)
        }
        assertTrue(unsignedFailure.message!!.contains("4294967295"))
        assertTrue(unsignedFailure.message!!.contains("exceeds"))

        val configuredOversize = ByteArray(ApTransportPackageCodec.HEADER_SIZE)
        configuredOversize[3] = 65
        val configuredFailure = assertThrows(ApTransportProtocolException::class.java) {
            ApTransportPackageCodec.decodeAvailable(configuredOversize, maxPackageBytes = 64)
        }
        assertTrue(configuredFailure.message!!.contains("size 65 exceeds 64"))
    }

    @Test
    fun strictDecodeRejectsTruncatedAndConcatenatedInput() {
        val encoded = ApTransportPackageCodec.comm(byteArrayOf(1, 2, 3)).encoded()
        val truncated = encoded.copyOf(encoded.lastIndex)

        val truncatedFailure = assertThrows(ApTransportProtocolException::class.java) {
            ApTransportPackageCodec.decode(truncated)
        }
        assertTrue(truncatedFailure.message!!.contains("Expected one complete APTransport package"))
        assertTrue(truncatedFailure.message!!.contains("got 0"))

        assertThrows(ApTransportProtocolException::class.java) {
            ApTransportPackageCodec.decode(encoded + encoded)
        }
    }

    @Test
    fun boundsNumberOfPackagesDecodedFromOneBuffer() {
        val encoded = ApTransportPackageCodec.comm(ByteArray(0)).encoded()
        val failure = assertThrows(ApTransportProtocolException::class.java) {
            ApTransportPackageCodec.decodeAvailable(encoded + encoded, maxPackages = 1)
        }

        assertTrue(failure.message!!.contains("package count exceeds 1"))
    }

    @Test
    fun preservesUninterpretedHeaderMetadataAndDefensivelyCopiesInputs() {
        val template = ByteArray(ApTransportPackageCodec.HEADER_SIZE) { it.toByte() }
        val body = byteArrayOf(9)
        val packageValue = ApTransportPackageCodec.comm(body, template)
        body[0] = 99
        template[4] = 99
        val encoded = packageValue.encoded()

        assertEquals(33, encoded.size)
        assertEquals(4, encoded[4].toInt())
        assertEquals(ApTransportPackageCodec.MESSAGE_TYPE_COMM, packageValue.messageType)
        assertArrayEquals(byteArrayOf(9), ApTransportPackageCodec.decode(encoded).body)

        val exposedBody = packageValue.body
        exposedBody[0] = 77
        assertArrayEquals(byteArrayOf(9), packageValue.body)
    }

    @Test
    fun rejectsCreationBeyondConfiguredLimit() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ApTransportPackageCodec.comm(ByteArray(33), maxPackageBytes = 64)
        }

        assertTrue(failure.message!!.contains("size 65 exceeds 64"))
    }
}
