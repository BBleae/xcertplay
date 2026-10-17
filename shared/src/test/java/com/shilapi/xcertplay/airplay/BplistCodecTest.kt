package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCarrier
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCodec
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandEnvelope
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessage
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessageType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncProtocolVersion
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BplistCodecTest {
    @Test
    fun supportedValuesRoundTrip() {
        val root = linkedMapOf<String, Any?>(
            "enabled" to true,
            "integer" to 42,
            "large" to BigInteger("4294967296"),
            "real" to 1.5,
            "ascii" to "CarPlay",
            "unicode" to "车载",
            "data" to byteArrayOf(0x00, 0x7f, 0xff.toByte()),
            "array" to listOf(false, 7),
        )

        val decoded = BplistCodec.decode(BplistCodec.encode(root)) as Map<*, *>

        assertEquals(root.keys.toList(), decoded.keys.toList())
        assertEquals(true, decoded["enabled"])
        assertEquals(42L, decoded["integer"])
        assertEquals(4_294_967_296L, decoded["large"])
        assertEquals(1.5, decoded["real"])
        assertEquals("CarPlay", decoded["ascii"])
        assertEquals("车载", decoded["unicode"])
        assertTrue((decoded["data"] as ByteArray).contentEquals(root["data"] as ByteArray))
        assertEquals(listOf(false, 7L), decoded["array"])
    }

    @Test
    fun simpleDictionaryWireEncodingIsStable() {
        val encoded = BplistCodec.encode(linkedMapOf("a" to 1))

        assertTrue(
            encoded.contentEquals(
                hex(
                    "62706c6973743030" +
                        "d1010251611001" +
                        "080b0d" +
                        "0000000000000101" +
                        "0000000000000003" +
                        "0000000000000000" +
                        "000000000000000f",
                ),
            ),
        )
    }

    @Test
    fun appleSignedIntegerGoldenVectorsRemainByteExactAndTyped() {
        // Generated on macOS with:
        // printf '<plist ...><integer>VALUE</integer></plist>' |
        //   plutil -convert binary1 -o - -- - | xxd -p
        val vectors = listOf(
            IntegerGoldenVector(
                -1L,
                "62706c6973743030" +
                    "13ffffffffffffffff" +
                    "08" +
                    "0000000000000101" +
                    "0000000000000001" +
                    "0000000000000000" +
                    "0000000000000011",
            ),
            IntegerGoldenVector(
                Long.MIN_VALUE,
                "62706c6973743030" +
                    "138000000000000000" +
                    "08" +
                    "0000000000000101" +
                    "0000000000000001" +
                    "0000000000000000" +
                    "0000000000000011",
            ),
            IntegerGoldenVector(
                0L,
                "62706c6973743030" +
                    "1000" +
                    "08" +
                    "0000000000000101" +
                    "0000000000000001" +
                    "0000000000000000" +
                    "000000000000000a",
            ),
            IntegerGoldenVector(
                127L,
                "62706c6973743030" +
                    "107f" +
                    "08" +
                    "0000000000000101" +
                    "0000000000000001" +
                    "0000000000000000" +
                    "000000000000000a",
            ),
            IntegerGoldenVector(
                128L,
                "62706c6973743030" +
                    "1080" +
                    "08" +
                    "0000000000000101" +
                    "0000000000000001" +
                    "0000000000000000" +
                    "000000000000000a",
            ),
        )

        vectors.forEach { vector ->
            val golden = hex(vector.hex)
            val encoded = BplistCodec.encode(vector.value)
            assertArrayEquals("wire mismatch for ${vector.value}", golden, encoded)
            val decoded = BplistCodec.decode(golden)
            assertTrue("decoded ${vector.value} must remain an integer", decoded is Long)
            assertEquals(vector.value, decoded)
            if (vector.value >= 0L) {
                assertArrayEquals(golden, BplistCodec.encode(BigInteger.valueOf(vector.value)))
            }
        }

        listOf<Any>(-1.toByte(), (-1).toShort(), -1, -1L, BigInteger.valueOf(-1L)).forEach { value ->
            assertArrayEquals(hex(vectors.first().hex), BplistCodec.encode(value))
        }
        assertArrayEquals(hex(vectors[1].hex), BplistCodec.encode(BigInteger.valueOf(Long.MIN_VALUE)))

        listOf<Any>(Byte.MIN_VALUE, Short.MIN_VALUE, Int.MIN_VALUE, Long.MIN_VALUE).forEach { value ->
            val encoded = BplistCodec.encode(value)
            assertEquals(0x13, encoded[MAGIC.size].toInt() and 0xff)
            assertEquals((value as Number).toLong(), BplistCodec.decode(encoded))
        }
    }

    @Test
    fun bigIntegerKeepsSignedInt64UnsignedUInt64AndSignedInt128BoundariesDistinct() {
        val belowSignedInt64 = BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)
        val unsignedInt64Maximum = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        val aboveUnsignedInt64 = unsignedInt64Maximum.add(BigInteger.ONE)
        val signedInt128Minimum = BigInteger.ONE.shiftLeft(127).negate()
        val signedInt128Maximum = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE)

        listOf(belowSignedInt64, aboveUnsignedInt64, signedInt128Minimum, signedInt128Maximum).forEach { value ->
            val encoded = BplistCodec.encode(value)
            assertEquals(0x14, encoded[MAGIC.size].toInt() and 0xff)
            assertEquals(value, BplistCodec.decode(encoded))
        }
        assertMalformed { BplistCodec.encode(signedInt128Minimum.subtract(BigInteger.ONE)) }
        assertMalformed { BplistCodec.encode(signedInt128Maximum.add(BigInteger.ONE)) }

        val encoded = BplistCodec.encode(unsignedInt64Maximum)
        assertEquals(0x13, encoded[MAGIC.size].toInt() and 0xff)
        assertTrue(encoded.copyOfRange(MAGIC.size + 1, MAGIC.size + 9).all { it == 0xff.toByte() })
        // UInt64 values retain their existing raw-Long decode semantics.
        assertEquals(-1L, BplistCodec.decode(encoded))
    }

    @Test
    fun signedInt128DecodeRespectsObjectSliceAndByteBudget() {
        val negativeOne = rawPlist(listOf(byteArrayOf(0x14) + ByteArray(16) { 0xff.toByte() }))
        assertEquals(-1L, BplistCodec.decode(negativeOne))
        assertMalformed {
            BplistCodec.decode(rawPlist(listOf(byteArrayOf(0x14) + ByteArray(15))))
        }
        assertMalformed {
            BplistCodec.decode(negativeOne, BplistDecodeLimits(maxDecodedBytes = 15))
        }
        assertMalformed {
            BplistCodec.decode(rawPlist(listOf(byteArrayOf(0x15) + ByteArray(32))))
        }
    }

    @Test
    fun uiSyncAppearanceModeNegativeOneRemainsAnIntegerAcrossTheCarrier() {
        val carrier = CarPlayUiSyncCarrier(
            displayId = "cluster-display",
            serializationVersion = CarPlayUiSyncProtocolVersion.V1,
            message = CarPlayUiSyncMessage(
                type = CarPlayUiSyncMessageType.COMMAND,
                sessionSequenceNumber = 1L,
                packetSequenceNumber = 1L,
                acknowledgementSequenceNumber = 0L,
                payload = CarPlayUiSyncCommandEnvelope(
                    type = CarPlayUiSyncCommandType.TARGET_APPEARANCE_CHANGE.wireValue,
                    data = linkedMapOf("appearanceMode" to -1),
                ),
                version = CarPlayUiSyncProtocolVersion.V1,
            ),
        )

        val decoded = CarPlayUiSyncCodec.decode(
            CarPlayUiSyncCodec.encode(carrier),
            expectedVersion = CarPlayUiSyncProtocolVersion.V1,
        )
        val command = decoded.message.payload as CarPlayUiSyncCommandEnvelope
        val appearanceMode = (command.data as Map<*, *>)["appearanceMode"]

        assertTrue(appearanceMode is Long)
        assertEquals(-1L, appearanceMode)
    }

    @Test
    fun rejectsTruncatedAndOversizedInput() {
        val valid = BplistCodec.encode(linkedMapOf("name" to "CarPlay", "items" to listOf(1, 2, 3)))

        listOf(0, 7, 8, 39, valid.size - 1).forEach { length ->
            assertMalformed { BplistCodec.decode(valid.copyOf(length)) }
        }
        assertMalformed {
            BplistCodec.decode(valid, BplistDecodeLimits(maxInputBytes = valid.size - 1))
        }
    }

    @Test
    fun validatesTrailerSizesCountsAndIndicesBeforeAllocation() {
        val valid = BplistCodec.encode(listOf(true))
        val trailer = valid.size - TRAILER_BYTES

        assertMalformed {
            BplistCodec.decode(valid.copyOf().also { it[trailer + 6] = 0 })
        }
        assertMalformed {
            BplistCodec.decode(valid.copyOf().also { it[trailer + 7] = 3 })
        }
        assertMalformed {
            BplistCodec.decode(valid.copyOf().also { writeUnsigned(it, trailer + 8, 0, 8) })
        }
        assertMalformed {
            BplistCodec.decode(valid.copyOf().also { writeUnsigned(it, trailer + 8, 201_000, 8) })
        }
        assertMalformed {
            BplistCodec.decode(valid.copyOf().also { writeUnsigned(it, trailer + 16, 2, 8) })
        }
        assertMalformed {
            BplistCodec.decode(
                valid.copyOf().also { writeUnsigned(it, trailer + 24, Long.MAX_VALUE, 8) },
            )
        }
    }

    @Test
    fun validatesOffsetTableArithmeticAndObjectSlices() {
        val valid = BplistCodec.encode(listOf(true, false))
        val trailer = valid.size - TRAILER_BYTES
        val table = readUnsigned(valid, trailer + 24, 8).toInt()

        assertMalformed {
            BplistCodec.decode(valid.copyOf().also { writeUnsigned(it, trailer + 24, table - 1L, 8) })
        }
        assertMalformed {
            BplistCodec.decode(valid.copyOf().also { it[table] = 0x07 })
        }
        assertMalformed {
            BplistCodec.decode(
                rawPlist(listOf(byteArrayOf(0x08), byteArrayOf(0x09))).also {
                    val rawTrailer = it.size - TRAILER_BYTES
                    val rawTable = readUnsigned(it, rawTrailer + 24, 8).toInt()
                    it[rawTable + 1] = it[rawTable]
                },
            )
        }
        assertMalformed {
            BplistCodec.decode(
                BplistCodec.encode("longer than one byte"),
                BplistDecodeLimits(maxObjectBytes = 1),
            )
        }
    }

    @Test
    fun rejectsInvalidReferencesAndTruncatedContainers() {
        assertMalformed {
            BplistCodec.decode(rawPlist(listOf(byteArrayOf(0xa1.toByte(), 0x01))))
        }
        assertMalformed {
            BplistCodec.decode(rawPlist(listOf(byteArrayOf(0xd1.toByte(), 0x00))))
        }
        assertMalformed {
            BplistCodec.decode(rawPlist(listOf(byteArrayOf(0x4f, 0x20))))
        }
        assertMalformed {
            BplistCodec.decode(rawPlist(listOf(byteArrayOf(0x1f))))
        }
        assertMalformed {
            BplistCodec.decode(
                rawPlist(
                    listOf(
                        byteArrayOf(
                            0x4f,
                            0x13,
                            0x7f,
                            0xff.toByte(),
                            0xff.toByte(),
                            0xff.toByte(),
                            0xff.toByte(),
                            0xff.toByte(),
                            0xff.toByte(),
                            0xff.toByte(),
                            0xff.toByte(),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun enforcesContainerPayloadAndDecodedByteBudgets() {
        assertMalformed {
            BplistCodec.decode(
                BplistCodec.encode(listOf(true, false)),
                BplistDecodeLimits(maxContainerItems = 1),
            )
        }
        assertMalformed {
            BplistCodec.decode(
                BplistCodec.encode(byteArrayOf(1, 2)),
                BplistDecodeLimits(maxDataBytes = 1),
            )
        }
        assertMalformed {
            BplistCodec.decode(
                BplistCodec.encode("ab"),
                BplistDecodeLimits(maxStringBytes = 1),
            )
        }
        assertMalformed {
            BplistCodec.decode(
                BplistCodec.encode("车"),
                BplistDecodeLimits(maxStringBytes = 1),
            )
        }
        assertMalformed {
            BplistCodec.decode(
                BplistCodec.encode(listOf(byteArrayOf(1, 2), byteArrayOf(3, 4))),
                BplistDecodeLimits(maxDecodedBytes = 19),
            )
        }
    }

    @Test
    fun enforcesNestingAndIntegerSizeBudgets() {
        val nested = rawPlist(
            listOf(
                byteArrayOf(0xa1.toByte(), 0x01),
                byteArrayOf(0xa1.toByte(), 0x02),
                byteArrayOf(0xa1.toByte(), 0x03),
                byteArrayOf(0x09),
            ),
        )

        assertMalformed {
            BplistCodec.decode(nested, BplistDecodeLimits(maxNestingDepth = 2))
        }
        assertEquals(listOf(listOf(listOf(true))), BplistCodec.decode(nested))

        val twoByteOffsets = rawPlist(listOf(byteArrayOf(0x09)), offsetSize = 2)
        assertMalformed {
            BplistCodec.decode(twoByteOffsets, BplistDecodeLimits(maxOffsetSize = 1))
        }
        val twoByteReferences = rawPlist(listOf(byteArrayOf(0xa0.toByte())), referenceSize = 2)
        assertMalformed {
            BplistCodec.decode(twoByteReferences, BplistDecodeLimits(maxReferenceSize = 1))
        }
    }

    @Test
    fun rejectsCyclesButAllowsSharedReferences() {
        val selfCycle = rawPlist(listOf(byteArrayOf(0xa1.toByte(), 0x00)))
        val indirectCycle = rawPlist(
            listOf(
                byteArrayOf(0xa1.toByte(), 0x01),
                byteArrayOf(0xa1.toByte(), 0x00),
            ),
        )
        assertMalformed { BplistCodec.decode(selfCycle) }
        assertMalformed { BplistCodec.decode(indirectCycle) }

        val shared = BplistCodec.decode(
            rawPlist(
                listOf(
                    byteArrayOf(0xa2.toByte(), 0x01, 0x01),
                    byteArrayOf(0xa0.toByte()),
                ),
            ),
        ) as List<*>
        assertEquals(2, shared.size)
        assertSame(shared[0], shared[1])
    }

    @Test
    fun ownedDecodeWipesOnlySelectedDataIdentityAndItsAliases() {
        val source = rawPlist(
            listOf(
                byteArrayOf(0xa3.toByte(), 0x01, 0x01, 0x02),
                byteArrayOf(0x42, 0x11, 0x22),
                byteArrayOf(0x42, 0x33, 0x44),
            ),
        )
        val owned = BplistCodec.decodeOwned(source)
        val decoded = owned.root as List<*>
        val selected = decoded[0] as ByteArray
        val alias = decoded[1] as ByteArray
        val unrelated = decoded[2] as ByteArray
        assertSame(selected, alias)

        // An equal-valued caller array is not a decoded Data object owned by this scope.
        owned.wipeSelectedByIdentity(listOf(selected.copyOf()))
        assertArrayEquals(byteArrayOf(0x11, 0x22), selected)
        owned.wipeSelectedByIdentity(listOf(selected))
        assertArrayEquals(byteArrayOf(0, 0), selected)
        assertArrayEquals(byteArrayOf(0, 0), alias)
        assertArrayEquals(byteArrayOf(0x33, 0x44), unrelated)
        assertArrayEquals(byteArrayOf(0x11, 0x22), source.copyOfRange(13, 15))

        owned.detachWithoutWipe()
        owned.release()
        owned.wipeSelectedByIdentity(listOf(unrelated))
        assertArrayEquals(byteArrayOf(0x33, 0x44), unrelated)
    }

    @Test
    fun ownedDecodeReleaseWipesEveryRemainingDataCopyAndIsIdempotent() {
        val source = BplistCodec.encode(listOf(byteArrayOf(1, 2), byteArrayOf(3, 4)))
        val owned = BplistCodec.decodeOwned(source)
        val decoded = owned.root as List<*>
        val first = decoded[0] as ByteArray
        val second = decoded[1] as ByteArray

        owned.wipeSelectedByIdentity(listOf(first))
        assertArrayEquals(byteArrayOf(0, 0), first)
        assertArrayEquals(byteArrayOf(3, 4), second)
        owned.release()
        owned.release()
        owned.detachWithoutWipe()
        assertArrayEquals(byteArrayOf(0, 0), second)
        assertArrayEquals(byteArrayOf(1, 2), BplistCodec.decode(source).let { (it as List<*>)[0] as ByteArray })
    }

    @Test
    fun ownedDecodeFailureWipesEveryPartiallyDecodedDataCopyIncludingSharedReferences() {
        val source = rawPlist(
            listOf(
                byteArrayOf(0xa4.toByte(), 0x01, 0x01, 0x02, 0x03),
                byteArrayOf(0x42, 0x51, 0x52),
                byteArrayOf(0x42, 0x61, 0x62),
                byteArrayOf(0x30),
            ),
        )
        val observed = ArrayList<ByteArray>()
        assertMalformed {
            BplistCodec.decodeOwned(source, BplistDecodeLimits()) { observed.add(it) }
        }
        assertEquals(2, observed.size)
        assertTrue(observed[0] !== observed[1])
        observed.forEach { assertArrayEquals(byteArrayOf(0, 0), it) }
        assertArrayEquals(byteArrayOf(0x51, 0x52), source.copyOfRange(14, 16))
        assertArrayEquals(byteArrayOf(0x61, 0x62), source.copyOfRange(17, 19))
    }

    @Test
    fun rejectsNonStringAndDuplicateDictionaryKeys() {
        val nonStringKey = rawPlist(
            listOf(
                byteArrayOf(0xd1.toByte(), 0x01, 0x02),
                byteArrayOf(0x10, 0x01),
                byteArrayOf(0x09),
            ),
        )
        val duplicateKey = rawPlist(
            listOf(
                byteArrayOf(0xd2.toByte(), 0x01, 0x01, 0x02, 0x03),
                byteArrayOf(0x51, 0x61),
                byteArrayOf(0x10, 0x01),
                byteArrayOf(0x10, 0x02),
            ),
        )

        assertMalformed { BplistCodec.decode(nonStringKey) }
        assertMalformed { BplistCodec.decode(duplicateKey) }
    }

    @Test
    fun deterministicMalformedCorpusFailsCleanly() {
        val corpus = listOf(
            rawPlist(listOf(byteArrayOf(0x00))),
            rawPlist(listOf(byteArrayOf(0x30))),
            rawPlist(listOf(byteArrayOf(0x4f))),
            rawPlist(listOf(byteArrayOf(0x5f))),
            rawPlist(listOf(byteArrayOf(0x6f))),
            rawPlist(listOf(byteArrayOf(0xa1.toByte()))),
            rawPlist(listOf(byteArrayOf(0xd1.toByte(), 0x00))),
        )

        corpus.forEach { malformed -> assertMalformed { BplistCodec.decode(malformed) } }
    }

    private fun assertMalformed(block: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java, block)
        assertTrue(failure.message.orEmpty().startsWith("bplist:"))
    }

    private fun rawPlist(
        objects: List<ByteArray>,
        topObject: Int = 0,
        offsetSize: Int = 1,
        referenceSize: Int = 1,
    ): ByteArray {
        require(objects.isNotEmpty())
        val offsets = IntArray(objects.size)
        var cursor = MAGIC.size
        objects.forEachIndexed { index, body ->
            offsets[index] = cursor
            cursor += body.size
        }
        val offsetTable = cursor
        val totalSize = offsetTable + objects.size * offsetSize + TRAILER_BYTES
        val result = ByteArray(totalSize)
        MAGIC.copyInto(result)
        cursor = MAGIC.size
        objects.forEach { body ->
            body.copyInto(result, cursor)
            cursor += body.size
        }
        offsets.forEachIndexed { index, offset ->
            writeUnsigned(result, offsetTable + index * offsetSize, offset.toLong(), offsetSize)
        }
        val trailer = totalSize - TRAILER_BYTES
        result[trailer + 6] = offsetSize.toByte()
        result[trailer + 7] = referenceSize.toByte()
        writeUnsigned(result, trailer + 8, objects.size.toLong(), 8)
        writeUnsigned(result, trailer + 16, topObject.toLong(), 8)
        writeUnsigned(result, trailer + 24, offsetTable.toLong(), 8)
        return result
    }

    private fun writeUnsigned(target: ByteArray, offset: Int, value: Long, size: Int) {
        var remaining = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (remaining and 0xff).toByte()
            remaining = remaining ushr 8
        }
    }

    private fun readUnsigned(source: ByteArray, offset: Int, size: Int): Long {
        var value = 0L
        repeat(size) { index ->
            value = (value shl 8) or (source[offset + index].toLong() and 0xffL)
        }
        return value
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    private data class IntegerGoldenVector(val value: Long, val hex: String)

    private companion object {
        val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)
        const val TRAILER_BYTES = 32
    }
}
