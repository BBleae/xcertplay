package com.shilapi.xcertplay.airplay.rcs.caf

import java.math.BigInteger
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CafConfigTreeTest {
    @Test
    fun exactSchemaRetainsExtensionsAndStateChannels() {
        val tree = SyntheticCafTestFixture.tree()
        val temperature = tree.characteristicsByIid.getValue(SyntheticCafTestFixture.TEMPERATURE_IID)

        assertEquals(CafCharacteristicFormat.FLOAT, temperature.format)
        assertEquals(SyntheticCafTestFixture.ERROR_STATE_IID, temperature.iidError?.value)
        assertEquals("preserved", temperature.extensions["oemFixtureField"])
        assertEquals(42L, tree.extensions["oemRootField"])
        assertEquals(
            setOf(SyntheticCafTestFixture.TEMPERATURE_IID, SyntheticCafTestFixture.ERROR_STATE_IID),
            tree.valueIidsForPriority(0),
        )
        assertEquals(setOf(SyntheticCafTestFixture.HIGH_PRIORITY_IID), tree.valueIidsForPriority(1))
    }

    @Test
    fun formatsAreExactlyZeroThroughThirteenAndUint64KeepsAllBits() {
        CafCharacteristicFormat.entries.forEachIndexed { index, expected ->
            assertEquals(expected, CafCharacteristicFormat.fromWireValue(index.toLong()))
        }
        assertThrows(CafProtocolException::class.java) {
            CafCharacteristicFormat.fromWireValue(14L)
        }
        assertThrows(CafProtocolException::class.java) {
            CafCharacteristicFormat.fromWireValue(-1L)
        }
        assertEquals(
            CafFormatValue.Unsigned(BigInteger("18446744073709551615"), 64),
            CafFormatValue.decode(-1L, CafCharacteristicFormat.UINT64, "max"),
        )
    }

    @Test
    fun duplicateIdentifiersAndDanglingStateChannelsAreRejected() {
        val duplicate = SyntheticCafTestFixture.mutableRaw().also { root ->
            val accessory = (root["accessories"] as MutableList<MutableMap<String, Any?>>).single()
            accessory["iid"] = SyntheticCafTestFixture.SERVICE_IID
        }
        assertThrows(CafProtocolException::class.java) { CafConfigTree.decode(duplicate) }

        val dangling = SyntheticCafTestFixture.mutableRaw().also { root ->
            val characteristic = (((root["accessories"] as MutableList<MutableMap<String, Any?>>)
                .single()["services"] as MutableList<MutableMap<String, Any?>>)
                .single()["characteristics"] as MutableList<MutableMap<String, Any?>>).first()
            characteristic["iidError"] = 999L
        }
        assertThrows(CafProtocolException::class.java) { CafConfigTree.decode(dangling) }
    }

    @Test
    fun entityUuidUsesBigEndianPluginAndInstanceBits() {
        assertEquals(
            UUID.fromString("00000000-0000-0007-0000-000000000003"),
            CafEntityIdentifier.uuid(7L, 3L),
        )
        assertEquals(
            UUID.fromString("ffffffff-ffff-ffff-8000-000000000000"),
            CafEntityIdentifier.uuid(-1L, Long.MIN_VALUE),
        )
    }

    @Test
    fun controlParametersEnforceNamesCountsFormatsAndSupportsInvalid() {
        val control = SyntheticCafTestFixture.tree().controlsByIid
            .getValue(SyntheticCafTestFixture.CONTROL_IID)

        control.validateRequestPayload(mapOf("reason" to "driver"))
        assertThrows(CafProtocolException::class.java) {
            control.validateRequestPayload(mapOf("reason" to 7L))
        }
        assertThrows(CafProtocolException::class.java) {
            control.validateRequestPayload(mapOf("reason" to "driver", "extra" to true))
        }
        assertThrows(CafProtocolException::class.java) {
            control.validateRequestPayload(mapOf("reason" to null))
        }

        val nullable = control.copy(
            requestParameters = control.requestParameters.map { it.copy(supportsInvalid = true) },
        )
        nullable.validateRequestPayload(mapOf("reason" to null))
    }

    @Test
    fun characteristicConstraintsUseInclusiveBoundsExactIntegersAndSafeFloatSteps() {
        val tree = SyntheticCafTestFixture.constrainedWritableTree()
        val temperature = tree.characteristicsByIid
            .getValue(SyntheticCafTestFixture.TEMPERATURE_IID)

        assertEquals(CafFormatValue.Floating(10.0), temperature.validateValue(10.0))
        assertEquals(CafFormatValue.Floating(30.0), temperature.validateValue(30.0))
        assertThrows(CafProtocolException::class.java) { temperature.validateValue(9.5) }
        assertThrows(CafProtocolException::class.java) { temperature.validateValue(30.5) }
        assertThrows(CafProtocolException::class.java) { temperature.validateValue(20.25) }
        // 20.5 is on the 0.5 step, but is intentionally outside the declared enum.
        assertThrows(CafProtocolException::class.java) { temperature.validateValue(20.5) }

        val base = SyntheticCafTestFixture.tree().characteristicsByIid
            .getValue(SyntheticCafTestFixture.HIGH_PRIORITY_IID)
        val uint64Maximum = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        val uint64 = base.copy(
            format = CafCharacteristicFormat.UINT64,
            initialValue = null,
            minimumValue = CafFormatValue.Unsigned(uint64Maximum.subtract(BigInteger.TWO), 64),
            maximumValue = CafFormatValue.Unsigned(uint64Maximum, 64),
            stepValue = CafFormatValue.Unsigned(BigInteger.ONE, 64),
            validValues = emptyList(),
        )
        assertEquals(
            CafFormatValue.Unsigned(uint64Maximum, 64),
            uint64.validateValue(-1L),
        )
        assertThrows(CafProtocolException::class.java) {
            uint64.validateValue(uint64Maximum.subtract(BigInteger.valueOf(3)))
        }

        val signed = base.copy(
            format = CafCharacteristicFormat.INT64,
            initialValue = null,
            minimumValue = CafFormatValue.Signed(BigInteger.valueOf(-2), 64),
            maximumValue = CafFormatValue.Signed(BigInteger.valueOf(2), 64),
            stepValue = CafFormatValue.Signed(BigInteger.TWO, 64),
            validValues = emptyList(),
        )
        signed.validateValue(-2L)
        signed.validateValue(2L)
        assertThrows(CafProtocolException::class.java) { signed.validateValue(1L) }
    }

    @Test
    fun lengthAndEnumsCoverStringDataArrayAndDictionaryValues() {
        val tree = SyntheticCafTestFixture.constrainedWritableTree()
        val string = tree.characteristicsByIid
            .getValue(SyntheticCafTestFixture.STRING_SETTING_IID)
        string.validateValue("AUTO")
        assertThrows(CafProtocolException::class.java) { string.validateValue("SPORT") }
        assertThrows(CafProtocolException::class.java) { string.validateValue("MAN") }

        val base = SyntheticCafTestFixture.tree().characteristicsByIid
            .getValue(SyntheticCafTestFixture.HIGH_PRIORITY_IID)
        val data = base.copy(
            format = CafCharacteristicFormat.DATA,
            initialValue = null,
            maximumLength = 2,
            validValues = listOf(CafFormatValue.Binary(byteArrayOf(1, 2))),
        )
        data.validateValue(byteArrayOf(1, 2))
        assertThrows(CafProtocolException::class.java) { data.validateValue(byteArrayOf(1, 2, 3)) }
        assertThrows(CafProtocolException::class.java) { data.validateValue(byteArrayOf(2, 1)) }

        val array = base.copy(
            format = CafCharacteristicFormat.ARRAY,
            initialValue = null,
            maximumLength = 2,
            validValues = listOf(CafFormatValue.ArrayValue(listOf(1L, 2L))),
        )
        array.validateValue(listOf(1L, 2L))
        assertThrows(CafProtocolException::class.java) { array.validateValue(listOf(1L, 2L, 3L)) }
        assertThrows(CafProtocolException::class.java) { array.validateValue(listOf(2L, 1L)) }

        val dictionary = base.copy(
            format = CafCharacteristicFormat.DICTIONARY,
            initialValue = null,
            maximumLength = 1,
            validValues = listOf(CafFormatValue.Dictionary(mapOf("mode" to 1L))),
        )
        dictionary.validateValue(mapOf("mode" to 1L))
        assertThrows(CafProtocolException::class.java) {
            dictionary.validateValue(mapOf("mode" to 1L, "extra" to 2L))
        }
        assertThrows(CafProtocolException::class.java) {
            dictionary.validateValue(mapOf("mode" to 2L))
        }

        val nullable = string.copy(
            supportsInvalid = true,
            initialValue = CafFormatValue.Invalid,
        )
        assertEquals(CafFormatValue.Invalid, nullable.validateValue(null))
    }

    @Test
    fun decoderTakesADeepFrozenSnapshotBeforeValidation() {
        val raw = SyntheticCafTestFixture.mutableRaw()
        val nestedList = mutableListOf<Any?>("before")
        val binary = byteArrayOf(1, 2, 3)
        val rootExtension = linkedMapOf<String, Any?>(
            "nested" to nestedList,
            "binary" to binary,
        )
        raw["mutableExtension"] = rootExtension
        val characteristic = characteristics(raw).first()
        val characteristicExtension = mutableListOf<Any?>("kept")
        characteristic["mutableCharacteristicExtension"] = characteristicExtension

        val tree = CafConfigTree.decode(raw)

        nestedList[0] = "changed"
        nestedList += "added"
        binary[0] = 99
        rootExtension["new"] = true
        characteristicExtension.clear()
        raw.clear()

        @Suppress("UNCHECKED_CAST")
        val snapshottedExtension = tree.root["mutableExtension"] as Map<String, Any?>
        assertEquals(listOf("before"), snapshottedExtension["nested"])
        assertArrayEquals(byteArrayOf(1, 2, 3), snapshottedExtension["binary"] as ByteArray)
        assertEquals(null, snapshottedExtension["new"])
        assertEquals(
            listOf("kept"),
            tree.characteristicsByIid.getValue(SyntheticCafTestFixture.TEMPERATURE_IID)
                .extensions["mutableCharacteristicExtension"],
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (tree.root as MutableMap<String, Any?>)["late"] = true
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshottedExtension["nested"] as MutableList<Any?>).add("late")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (tree.extensions as MutableMap<String, Any?>)["late"] = true
        }
    }

    @Test
    fun decoderRejectsCyclesAndBoundedResourceOverruns() {
        val cyclic = SyntheticCafTestFixture.mutableRaw()
        val cycle = mutableListOf<Any?>()
        cycle.add(cycle)
        cyclic["cycle"] = cycle
        assertThrows(CafProtocolException::class.java) { CafConfigTree.decode(cyclic) }

        val deep = SyntheticCafTestFixture.mutableRaw()
        var nested: Any? = "leaf"
        repeat(12) { nested = listOf(nested) }
        deep["deep"] = nested
        assertThrows(CafProtocolException::class.java) {
            CafFirmwareConfigTreeDecoder.decode(
                deep,
                CafConfigLimits.DEFAULT.copy(maxDepth = 8),
            )
        }

        val oversizedString = SyntheticCafTestFixture.mutableRaw().apply {
            put("largeText", "x".repeat(65))
        }
        assertThrows(CafProtocolException::class.java) {
            CafFirmwareConfigTreeDecoder.decode(
                oversizedString,
                CafConfigLimits.DEFAULT.copy(maxStringChars = 64),
            )
        }

        val oversizedData = SyntheticCafTestFixture.mutableRaw().apply {
            put("largeData", ByteArray(3))
        }
        assertThrows(CafProtocolException::class.java) {
            CafFirmwareConfigTreeDecoder.decode(
                oversizedData,
                CafConfigLimits.DEFAULT.copy(maxDataBytes = 2),
            )
        }

        val oversizedCollection = SyntheticCafTestFixture.mutableRaw().apply {
            put("largeCollection", List(512) { it.toLong() })
        }
        assertThrows(CafProtocolException::class.java) {
            CafFirmwareConfigTreeDecoder.decode(
                oversizedCollection,
                CafConfigLimits.DEFAULT.copy(maxCollectionEntries = 256),
            )
        }
        assertThrows(CafProtocolException::class.java) {
            CafFirmwareConfigTreeDecoder.decode(
                oversizedCollection,
                CafConfigLimits.DEFAULT.copy(maxNodes = 256),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun characteristics(
        root: MutableMap<String, Any?>,
    ): MutableList<MutableMap<String, Any?>> =
        (((root["accessories"] as MutableList<MutableMap<String, Any?>>)
            .single()["services"] as MutableList<MutableMap<String, Any?>>)
            .single()["characteristics"] as MutableList<MutableMap<String, Any?>>)
}
