package com.shilapi.xcertplay.airplay.rcs.video

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayVideoSettingsCodecTest {
    private val limits = CarPlayVideoSettingsLimits(
        maxRcsBodyBytes = 4096,
        maxPlistBytes = 3072,
        maxValueBytes = 512,
        maxCollectionEntries = 16,
        maxObjects = 64,
        maxDepth = 6,
    )
    private val codec = CarPlayVideoSettingsCodec(limits)

    @Test
    fun phoneMessageTypesHaveOnlyTheirObservedFields() {
        val subtitles = CarPlayVideoSettingsMessage.SubtitleSettings(
            linkedMapOf("language" to "ja", "enabled" to true),
        )
        val subtitleBody = opack(codec.encodeFromPhone(subtitles))
        assertEquals(setOf("messageType", "data"), subtitleBody.keys)
        assertEquals(1L, subtitleBody["messageType"])
        assertEquals(linkedMapOf("language" to "ja", "enabled" to true), subtitleBody["data"])
        assertEquals(
            "ja",
            (codec.decodeFromPhone(codec.encodeFromPhone(subtitles)) as
                CarPlayVideoSettingsMessage.SubtitleSettings).settings["language"],
        )

        val diagnostics = codec.encodeFromPhone(CarPlayVideoSettingsMessage.Diagnostics(false))
        assertEquals(mapOf("messageType" to 2L, "data" to false), opack(diagnostics))
        assertEquals(
            CarPlayVideoSettingsMessage.Diagnostics(false),
            codec.decodeFromPhone(diagnostics),
        )
        assertEquals(
            mapOf("messageType" to 3L),
            opack(codec.encodeFromPhone(CarPlayVideoSettingsMessage.LicensesRequest)),
        )
        assertEquals(
            mapOf("messageType" to 4L),
            opack(codec.encodeFromPhone(CarPlayVideoSettingsMessage.AnalyticsRequest)),
        )
    }

    @Test
    fun accessoryRequestsUseParamsDataAndNoNumericMessageType() {
        val properties = codec.encodeToPhone(
            CarPlayVideoSettingsMessage.PropertiesRequest(43L, listOf("subtitleLanguage", "captions")),
        )
        assertEquals(setOf("params"), opack(properties).keys)
        assertEquals(
            linkedMapOf(
                "controlMessage" to "properties",
                "messageID" to 43L,
                "properties" to listOf("subtitleLanguage", "captions"),
            ),
            accessoryPlist(properties),
        )
        val decoded = codec.decodeToPhone(properties) as
            CarPlayVideoSettingsMessage.PropertiesRequest
        assertEquals(43L, decoded.messageId)
        assertEquals(listOf("subtitleLanguage", "captions"), decoded.properties)

        val setProperty = codec.encodeToPhone(
            CarPlayVideoSettingsMessage.SetProperty("captions", byteArrayOf(1, 2)),
        )
        assertEquals("setProperty", accessoryPlist(setProperty)["controlMessage"])
        val decodedSet = codec.decodeToPhone(setProperty) as CarPlayVideoSettingsMessage.SetProperty
        assertEquals("captions", decodedSet.property)
        assertArrayEquals(byteArrayOf(1, 2), decodedSet.value as ByteArray)

        val show = codec.encodeToPhone(CarPlayVideoSettingsMessage.ShowSettings("subtitles"))
        assertEquals(
            linkedMapOf("controlMessage" to "showSettings", "params" to mapOf("page" to "subtitles")),
            accessoryPlist(show),
        )
        assertEquals(
            CarPlayVideoSettingsMessage.ShowSettings("subtitles"),
            codec.decodeToPhone(show),
        )
    }

    @Test
    fun propertiesResponseEchoesRequestMessageIdAndKeepsOpaqueValues() {
        val request = codec.decodeToPhone(
            codec.encodeToPhone(CarPlayVideoSettingsMessage.PropertiesRequest(77L, listOf("captions"))),
        ) as CarPlayVideoSettingsMessage.PropertiesRequest
        val sourceBytes = byteArrayOf(3, 4)
        val sourceValues: Map<String, Any?> = linkedMapOf(
            "captions" to linkedMapOf("enabled" to true, "token" to sourceBytes),
        )
        val response = CarPlayVideoSettingsMessage.PropertiesResponse(request.messageId, sourceValues)
        sourceBytes[0] = 9
        val encoded = codec.encodeFromPhone(response)
        val envelope = opack(encoded)
        assertEquals(5L, envelope["messageType"])
        val plist = BplistCodec.decode(envelope["data"] as ByteArray) as Map<*, *>
        assertEquals(setOf("controlMessage", "kind", "values", "messageID"), plist.keys)
        assertEquals("properties", plist["controlMessage"])
        assertEquals("response", plist["kind"])
        assertEquals(request.messageId, plist["messageID"])

        val decoded = codec.decodeFromPhone(encoded) as
            CarPlayVideoSettingsMessage.PropertiesResponse
        assertEquals(77L, decoded.messageId)
        val decodedToken = ((decoded.values["captions"] as Map<*, *>)["token"] as ByteArray)
        assertArrayEquals(byteArrayOf(3, 4), decodedToken)
        decodedToken[0] = 8
        assertArrayEquals(
            byteArrayOf(3, 4),
            (decoded.values["captions"] as Map<*, *>)["token"] as ByteArray,
        )
    }

    @Test
    fun identifiedButUnspecifiedPhoneControlsStayOpaqueAndBounded() {
        val response = codec.decodeFromPhone(phonePropertyPlist(
            linkedMapOf(
                "controlMessage" to "setProperty",
                "kind" to "response",
                "unknownResult" to listOf(1L, byteArrayOf(7)),
            ),
        )) as CarPlayVideoSettingsMessage.OpaquePropertyControl
        assertEquals("setProperty", response.controlMessage)
        assertEquals(1L, (response.fields["unknownResult"] as List<*>)[0])
        assertEquals("setProperty", (BplistCodec.decode(
            opack(codec.encodeFromPhone(response))["data"] as ByteArray,
        ) as Map<*, *>)["controlMessage"])

        val update = codec.decodeFromPhone(phonePropertyPlist(
            linkedMapOf("controlMessage" to "propertyChanged", "opaque" to mapOf("x" to true)),
        )) as CarPlayVideoSettingsMessage.OpaquePropertyControl
        assertEquals("propertyChanged", update.controlMessage)
        assertEquals(mapOf("x" to true), update.fields["opaque"])
        assertRejects {
            CarPlayVideoSettingsMessage.OpaquePropertyControl(
                "propertyChanged",
                mapOf("controlMessage" to "setProperty", "kind" to "response"),
            )
        }
    }

    @Test
    fun rejectsWrongDirectionAndMalformedOrExtraEnvelopeFields() {
        val phone = codec.encodeFromPhone(CarPlayVideoSettingsMessage.Diagnostics(true))
        val accessory = codec.encodeToPhone(CarPlayVideoSettingsMessage.ShowSettings("subtitles"))
        assertRejects { codec.decodeToPhone(phone) }
        assertRejects { codec.decodeFromPhone(accessory) }
        assertRejects { codec.decodeFromPhone(RcsMessage(999, phone.body)) }
        assertRejects { codec.decodeToPhone(RcsMessage(999, accessory.body)) }
        assertRejects { codec.decodeFromPhone(RcsMessage.comm(phone.body + 0x08)) }
        assertRejects { codec.decodeToPhone(RcsMessage.comm(accessory.body + 0x08)) }
        assertRejects { codec.decodeFromPhone(RcsMessage.comm(byteArrayOf())) }
        assertRejects { codec.decodeFromPhone(rawOpack(mapOf("messageType" to 2, "data" to 1))) }
        assertRejects { codec.decodeFromPhone(rawOpack(mapOf("messageType" to 3, "extra" to 1))) }
        assertRejects { codec.decodeFromPhone(rawOpack(mapOf("messageType" to 4, "data" to true))) }
        assertRejects { codec.decodeFromPhone(rawOpack(mapOf("messageType" to 1, "data" to "ja"))) }
        assertRejects { codec.decodeFromPhone(rawOpack(mapOf("messageType" to "2", "data" to true))) }
        assertRejects { codec.decodeToPhone(rawOpack(mapOf("params" to mapOf("data" to ByteArray(0)), "messageType" to 5))) }
        assertRejects { codec.decodeToPhone(rawOpack(mapOf("params" to mapOf("data" to ByteArray(0), "extra" to 1)))) }
    }

    @Test
    fun rejectsMalformedPlistAndExactInnerShapeViolations() {
        val request = linkedMapOf<String, Any?>(
            "controlMessage" to "properties",
            "messageID" to 1L,
            "properties" to listOf("captions"),
        )
        assertRejects { codec.decodeToPhone(accessoryPlist(request + ("extra" to true))) }
        assertRejects { codec.decodeToPhone(accessoryPlist(request - "messageID")) }
        assertRejects { codec.decodeToPhone(accessoryPlist(request + ("messageID" to "1"))) }
        assertRejects { codec.decodeToPhone(accessoryPlist(request + ("properties" to listOf(3L)))) }
        assertRejects { codec.decodeToPhone(accessoryPlist(mapOf(
            "controlMessage" to "showSettings", "params" to mapOf("page" to "video", "extra" to true),
        ))) }
        assertRejects { codec.decodeToPhone(accessoryPlist(mapOf(
            "controlMessage" to "setProperty", "property" to "captions",
        ))) }
        val plistBytes = BplistCodec.encode(request)
        assertRejects { codec.decodeToPhone(rawOpack(mapOf("params" to mapOf("data" to plistBytes + 0)))) }
        assertRejects { codec.decodeToPhone(rawOpack(mapOf("params" to mapOf("data" to byteArrayOf(1, 2))))) }
        assertRejects { codec.decodeFromPhone(phonePropertyPlist(mapOf(
            "controlMessage" to "properties", "kind" to "response",
            "values" to mapOf("captions" to true), "messageID" to 1L, "extra" to true,
        ))) }
        assertRejects { codec.decodeFromPhone(phonePropertyPlist(mapOf(
            "controlMessage" to "setProperty", "kind" to "request",
        ))) }
    }

    @Test
    fun rejectsBytesHiddenInsideTheFinalBplistObjectSlice() {
        val request = linkedMapOf<String, Any?>(
            "controlMessage" to "properties",
            "messageID" to 1L,
            "properties" to listOf("captions"),
        )
        val paddedRequest = padLastObjectSlice(BplistCodec.encode(request))
        assertEquals(request, BplistCodec.decode(paddedRequest))
        assertRejects {
            codec.decodeToPhone(rawOpack(mapOf("params" to mapOf("data" to paddedRequest))))
        }

        val response = linkedMapOf<String, Any?>(
            "controlMessage" to "properties",
            "kind" to "response",
            "values" to mapOf("captions" to true),
            "messageID" to 1L,
        )
        val paddedResponse = padLastObjectSlice(BplistCodec.encode(response))
        assertEquals(response, BplistCodec.decode(paddedResponse))
        assertRejects {
            codec.decodeFromPhone(rawOpack(mapOf("messageType" to 5, "data" to paddedResponse)))
        }
    }

    @Test
    fun propertiesListCannotBeChangedAfterConstructionOrThroughItsAccessor() {
        val original = mutableListOf("captions")
        val request = CarPlayVideoSettingsMessage.PropertiesRequest(11L, original)
        original[0] = "language"
        assertEquals(listOf("captions"), request.properties)
        assertThrows(UnsupportedOperationException::class.java) {
            (request.properties as MutableList<String>).add("language")
        }
        assertEquals(listOf("captions"), accessoryPlist(codec.encodeToPhone(request))["properties"])
    }

    @Test
    fun enforcesDecodeAndEncodeBudgetsBeforeUnboundedBplistEncoding() {
        assertRejects { codec.decodeFromPhone(RcsMessage.comm(ByteArray(limits.maxRcsBodyBytes + 1))) }
        assertRejects { codec.decodeToPhone(rawOpack(
            mapOf("params" to mapOf("data" to ByteArray(limits.maxPlistBytes + 1))),
        )) }
        assertRejects { codec.encodeToPhone(
            CarPlayVideoSettingsMessage.SetProperty("captions", ByteArray(limits.maxValueBytes + 1)),
        ) }
        assertRejects { codec.encodeFromPhone(
            CarPlayVideoSettingsMessage.PropertiesResponse(1L, mapOf("huge" to ByteArray(limits.maxValueBytes + 1))),
        ) }
        val cyclic = mutableMapOf<String, Any?>()
        cyclic["self"] = cyclic
        assertRejects { CarPlayVideoSettingsMessage.SubtitleSettings(cyclic) }
        var deep: Any = 1L
        repeat(8) { deep = listOf(deep) }
        assertRejects { codec.encodeToPhone(CarPlayVideoSettingsMessage.SetProperty("x", deep)) }
        val source = byteArrayOf(1, 2)
        val message = CarPlayVideoSettingsMessage.SetProperty("x", source)
        source[0] = 9
        assertFalse((message.value as ByteArray).contentEquals(source))
        assertTrue((message.value as ByteArray).contentEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun rejectsUnsignedIntegerRangeThatSharedBplistWouldDecodeAsSigned() {
        val firstUnsignedHighBit = BigInteger.ONE.shiftLeft(63)
        assertRejects {
            CarPlayVideoSettingsMessage.PropertiesResponse(firstUnsignedHighBit, emptyMap())
        }
        assertRejects {
            CarPlayVideoSettingsMessage.SetProperty("counter", firstUnsignedHighBit)
        }
    }

    @Test
    fun customBoundsApplyThroughDecodedMessageSnapshots() {
        val expanded = CarPlayVideoSettingsLimits(
            maxValueBytes = 20 * 1024,
            maxCollectionEntries = 129,
        )
        val expandedCodec = CarPlayVideoSettingsCodec(expanded)
        val settings = (0 until 129).associate { "key$it" to it }
        val decodedSettings = expandedCodec.decodeFromPhone(
            expandedCodec.encodeFromPhone(CarPlayVideoSettingsMessage.SubtitleSettings(settings, expanded)),
        ) as CarPlayVideoSettingsMessage.SubtitleSettings
        assertEquals(129, decodedSettings.settings.size)

        val largeValue = ByteArray(16 * 1024 + 1) { 7 }
        val decodedProperty = expandedCodec.decodeToPhone(
            expandedCodec.encodeToPhone(
                CarPlayVideoSettingsMessage.SetProperty("opaque", largeValue, expanded),
            ),
        ) as CarPlayVideoSettingsMessage.SetProperty
        assertArrayEquals(largeValue, decodedProperty.value as ByteArray)

        val names = (0 until 129).map { "property$it" }
        val decodedRequest = expandedCodec.decodeToPhone(
            expandedCodec.encodeToPhone(
                CarPlayVideoSettingsMessage.PropertiesRequest(9L, names, expanded),
            ),
        ) as CarPlayVideoSettingsMessage.PropertiesRequest
        assertEquals(names, decodedRequest.properties)

        val hugeList = object : AbstractList<String>() {
            override val size: Int get() = Int.MAX_VALUE
            override fun get(index: Int): String = throw AssertionError("List must not be copied")
        }
        assertRejects { CarPlayVideoSettingsMessage.PropertiesRequest(1L, hugeList) }
    }

    private fun assertRejects(block: () -> Unit) {
        assertThrows(CarPlayVideoSettingsProtocolException::class.java) { block() }
    }

    private fun rawOpack(fields: Map<String, Any?>): RcsMessage =
        RcsMessage.comm(OpackCodec.encode(fields))

    private fun opack(message: RcsMessage): Map<*, *> = OpackCodec.decode(message.body) as Map<*, *>

    private fun accessoryPlist(fields: Map<String, Any?>): RcsMessage = rawOpack(
        mapOf("params" to mapOf("data" to BplistCodec.encode(fields))),
    )

    private fun accessoryPlist(message: RcsMessage): Map<*, *> = BplistCodec.decode(
        (opack(message)["params"] as Map<*, *>)["data"] as ByteArray,
    ) as Map<*, *>

    private fun phonePropertyPlist(fields: Map<String, Any?>): RcsMessage = rawOpack(
        mapOf("messageType" to 5, "data" to BplistCodec.encode(fields)),
    )

    private fun padLastObjectSlice(original: ByteArray): ByteArray {
        val trailer = original.size - 32
        var table = 0L
        for (index in trailer + 24 until trailer + 32) {
            table = table * 256L + (original[index].toLong() and 0xffL)
        }
        val offset = table.toInt()
        val padded = ByteArray(original.size + 1)
        original.copyInto(padded, endIndex = offset)
        original.copyInto(padded, destinationOffset = offset + 1, startIndex = offset)
        var shiftedTable = (offset + 1).toLong()
        for (index in (trailer + 1 + 24 until trailer + 1 + 32).reversed()) {
            padded[index] = (shiftedTable and 0xffL).toByte()
            shiftedTable = shiftedTable ushr 8
        }
        return padded
    }
}
