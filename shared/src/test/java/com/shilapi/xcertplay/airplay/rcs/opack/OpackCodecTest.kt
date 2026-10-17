package com.shilapi.xcertplay.airplay.rcs.opack

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpackCodecTest {
    @Test
    fun encodesKnownBaaIntermediateVector() {
        val intermediate = ByteArray(16) { it.toByte() }
        val expected = byteArrayOf(
            0xe1.toByte(),
            0x44,
            'b'.code.toByte(),
            'a'.code.toByte(),
            'I'.code.toByte(),
            'C'.code.toByte(),
            0x80.toByte(),
        ) + intermediate

        assertArrayEquals(expected, OpackCodec.encode(linkedMapOf("baIC" to intermediate)))
        @Suppress("UNCHECKED_CAST")
        val decoded = OpackCodec.decode(expected) as Map<Any?, Any?>
        assertArrayEquals(intermediate, decoded["baIC"] as ByteArray)
    }

    @Test
    fun roundTripsScalarsCollectionsAndReferences() {
        val encoded = OpackCodec.encode(
            linkedMapOf<String, Any?>(
                "null" to null,
                "true" to true,
                "small" to 12L,
                "negative" to -7L,
                "float" to 1.5f,
                "double" to -2.25,
                "string" to "CarPlay Ultra",
                "data" to byteArrayOf(1, 2, 3),
                "array" to listOf(4L, false, null),
                "dictionary" to linkedMapOf("nested" to 9L),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val decoded = OpackCodec.decode(encoded) as Map<Any?, Any?>
        assertEquals(null, decoded["null"])
        assertEquals(true, decoded["true"])
        assertEquals(12L, decoded["small"])
        assertEquals(-7L, decoded["negative"])
        assertEquals(1.5f, decoded["float"])
        assertEquals(-2.25, decoded["double"])
        assertEquals("CarPlay Ultra", decoded["string"])
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded["data"] as ByteArray)
        assertEquals(listOf(4L, false, null), decoded["array"])
        assertEquals(linkedMapOf<Any?, Any?>("nested" to 9L), decoded["dictionary"])
        assertEquals(
            listOf("a", "a"),
            OpackCodec.decode(byteArrayOf(0xd2.toByte(), 0x41, 0x61, 0xa0.toByte())),
        )
    }

    @Test
    fun roundTripsEveryExtendedLengthBoundary() {
        val sizes = intArrayOf(32, 255, 256, 65_535, 65_536, 70_000)
        val expectedDataTags = intArrayOf(0x90, 0x91, 0x92, 0x92, 0x93, 0x93)
        val expectedStringTags = intArrayOf(0x60, 0x61, 0x62, 0x62, 0x63, 0x63)

        sizes.forEachIndexed { index, size ->
            val data = ByteArray(size) { (it * 31).toByte() }
            val encodedData = OpackCodec.encode(data)
            assertEquals("data tag at $size", expectedDataTags[index], encodedData[0].u8())
            assertArrayEquals("data round trip at $size", data, OpackCodec.decode(encodedData) as ByteArray)

            val string = "x".repeat(size)
            val encodedString = OpackCodec.encode(string)
            assertEquals("string tag at $size", expectedStringTags[index], encodedString[0].u8())
            assertEquals("string round trip at $size", string, OpackCodec.decode(encodedString))
        }
    }

    @Test
    fun fourByteDataLengthUsesTag93RatherThanEightByteTag94() {
        val data = ByteArray(70_000) { it.toByte() }
        val encoded = OpackCodec.encode(data)

        assertEquals(0x93, encoded[0].u8())
        assertArrayEquals(byteArrayOf(0x70, 0x11, 0x01, 0x00), encoded.copyOfRange(1, 5))
        assertArrayEquals(data, OpackCodec.decode(encoded) as ByteArray)
    }

    @Test
    fun fourByteStringLengthUsesTag63AndAppleCompatibleWidth() {
        val value = "x".repeat(70_000)
        val encoded = OpackCodec.encode(value)

        assertEquals(0x63, encoded[0].u8())
        assertArrayEquals(byteArrayOf(0x70, 0x11, 0x01, 0x00), encoded.copyOfRange(1, 5))
        assertEquals(value, OpackCodec.decode(encoded))
    }

    @Test
    fun canonicalInlineValuesDoNotShiftObjectReferenceTable() {
        assertEquals(-1L, OpackCodec.decode(byteArrayOf(0x07)))
        assertArrayEquals(byteArrayOf(0x07), OpackCodec.encode(-1L))

        assertEquals(
            listOf(-1L, "a", "a"),
            OpackCodec.decode(
                byteArrayOf(
                    0xd3.toByte(),
                    0x30,
                    0xff.toByte(),
                    0x41,
                    'a'.code.toByte(),
                    0xa0.toByte(),
                ),
            ),
        )
        assertEquals(
            listOf("", "a", "a"),
            OpackCodec.decode(
                byteArrayOf(
                    0xd3.toByte(),
                    0x40,
                    0x41,
                    'a'.code.toByte(),
                    0xa0.toByte(),
                ),
            ),
        )
    }

    @Test
    fun rejectsLengthsBeforeReadingOrAllocatingValues() {
        val limits = OpackLimits(maxEncodedBytes = 128, maxValueBytes = 64)
        val oversizedData = byteArrayOf(0x92.toByte(), 0x41, 0x00)
        val truncatedData = byteArrayOf(0x92.toByte(), 0x10, 0x00, 0x7f)

        val oversizedFailure = assertThrows(OpackException::class.java) {
            OpackCodec.decode(oversizedData, limits)
        }
        assertTrue(oversizedFailure.message!!.contains("length 65 exceeds 64"))

        val truncatedFailure = assertThrows(OpackException::class.java) {
            OpackCodec.decode(truncatedData)
        }
        assertTrue(truncatedFailure.message!!.contains("expects 16 bytes"))
    }

    @Test
    fun enforcesPayloadCollectionObjectAndDepthLimits() {
        val tooManyEntries = byteArrayOf(
            0xdf.toByte(),
            0x08,
            0x09,
            0x0a,
            0x03,
        )
        assertThrows(OpackException::class.java) {
            OpackCodec.decode(
                tooManyEntries,
                OpackLimits(maxCollectionEntries = 2),
            )
        }

        val tooManyObjects = byteArrayOf(
            0xd2.toByte(),
            0x41,
            'a'.code.toByte(),
            0x41,
            'b'.code.toByte(),
        )
        assertThrows(OpackException::class.java) {
            OpackCodec.decode(
                tooManyObjects,
                OpackLimits(maxObjectTableEntries = 1),
            )
        }

        val tooDeep = byteArrayOf(
            0xd1.toByte(),
            0xd1.toByte(),
            0xd1.toByte(),
            0x08,
        )
        assertThrows(OpackException::class.java) {
            OpackCodec.decode(tooDeep, OpackLimits(maxDepth = 2))
        }

        assertThrows(OpackException::class.java) {
            OpackCodec.decode(
                ByteArray(5) { 0x08 },
                OpackLimits(maxEncodedBytes = 4, maxValueBytes = 4),
            )
        }
    }

    @Test
    fun rejectsCyclicCollectionsAndInvalidUtf8() {
        val cyclic = mutableListOf<Any?>()
        cyclic.add(cyclic)

        val cycleFailure = assertThrows(OpackException::class.java) {
            OpackCodec.encode(cyclic)
        }
        assertTrue(cycleFailure.message!!.contains("cyclic collection"))

        val utf8Failure = assertThrows(OpackException::class.java) {
            OpackCodec.decode(byteArrayOf(0x41, 0xc0.toByte()))
        }
        assertTrue(utf8Failure.message!!.contains("valid UTF-8"))

        val surrogateFailure = assertThrows(OpackException::class.java) {
            OpackCodec.encode("\ud800")
        }
        assertTrue(surrogateFailure.message!!.contains("unpaired UTF-16 surrogate"))
    }

    @Test
    fun rejectsUnknownTagsExplicitly() {
        val failure = assertThrows(OpackException::class.java) {
            OpackCodec.decode(byteArrayOf(0x00))
        }

        assertTrue(failure.message!!.contains("Unknown OPACK tag 0x00"))
    }

    @Test
    fun rejectsDuplicateKeysInOuterInnerAndValuesDictionaries() {
        val duplicateOuter = rawDictionary(
            encoded("command") to encoded(1L),
            encoded("command") to encoded(2L),
        )
        assertDuplicateKeyRejected(duplicateOuter)

        val duplicateInner = rawDictionary(
            encoded("payload") to rawDictionary(
                encoded("type") to encoded("first"),
                encoded("type") to encoded("second"),
            ),
        )
        assertDuplicateKeyRejected(duplicateInner)

        val duplicateValues = rawDictionary(
            encoded("values") to rawDictionary(
                encoded(42L) to encoded("first"),
                encoded(42L) to encoded("second"),
            ),
        )
        assertDuplicateKeyRejected(duplicateValues)
    }

    @Test
    fun rejectsDuplicateBinaryCompositeAndEquivalentFloatingKeys() {
        assertDuplicateKeyRejected(
            rawDictionary(
                encoded(byteArrayOf(1, 2, 3)) to encoded(1L),
                encoded(byteArrayOf(1, 2, 3)) to encoded(2L),
            ),
        )
        assertDuplicateKeyRejected(
            rawDictionary(
                encoded(listOf("key", byteArrayOf(4, 5))) to encoded(1L),
                encoded(listOf("key", byteArrayOf(4, 5))) to encoded(2L),
            ),
        )
        assertDuplicateKeyRejected(
            rawDictionary(
                encoded(1.5f) to encoded(1L),
                encoded(1.5) to encoded(2L),
            ),
        )
    }

    @Test
    fun sharedReferenceDictionaryStillDecodesNormally() {
        val payload = rawDictionary(
            encoded("first") to encoded("shared"),
            encoded("second") to byteArrayOf(0xa1.toByte()),
        )

        @Suppress("UNCHECKED_CAST")
        val decoded = OpackCodec.decode(payload) as Map<Any?, Any?>
        assertEquals("shared", decoded["first"])
        assertEquals("shared", decoded["second"])
        assertSame(decoded["first"], decoded["second"])
    }

    private fun assertDuplicateKeyRejected(payload: ByteArray) {
        val failure = assertThrows(OpackException::class.java) {
            OpackCodec.decode(payload)
        }
        assertTrue(failure.message!!.contains("duplicate key"))
    }

    private fun encoded(value: Any?): ByteArray = OpackCodec.encode(value)

    private fun rawDictionary(vararg entries: Pair<ByteArray, ByteArray>): ByteArray {
        require(entries.size < 15)
        var output = byteArrayOf((0xe0 or entries.size).toByte())
        entries.forEach { (key, value) ->
            output += key
            output += value
        }
        return output
    }

    private fun Byte.u8(): Int = toInt() and 0xff
}
