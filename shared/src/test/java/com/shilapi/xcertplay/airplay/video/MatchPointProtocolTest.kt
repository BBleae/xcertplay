package com.shilapi.xcertplay.airplay.video

import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsCatalogReadiness
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsPayloadStyle
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPointProtocolTest {
    @Test
    fun roundTripsEveryConfirmedMessageTypeAndDirection() {
        val reference = MatchPointItemReference(ITEM_ID)
        val queueItem = MatchPointQueueItem(
            uuid = ITEM_ID,
            startPosition = 2.5,
            contentLocation = "http://192.168.42.1:7001/item.m3u8",
            offlineHlsContentLocation = "https://media.example.test/offline.m3u8",
            isTlsEnabled = false,
            audioMode = "stereo",
        )
        val time = MatchPointCmTime(value = 90_000, timescale = 90_000)
        val messages = listOf(
            MatchPointMessage.request(
                1,
                MatchPointAuthorizeItem(reference, byteArrayOf(1, 2, 3)),
            ),
            MatchPointMessage.request(2, MatchPointPlaybackInfoRequest),
            MatchPointMessage.request(
                3,
                MatchPointPropertyRequest("duration", reference, "main", true),
            ),
            MatchPointMessage.request(
                4,
                MatchPointStreamingKeyRequest(mapOf("purpose" to "playback")),
            ),
            MatchPointMessage.oneWay(
                MatchPointInsertPlayQueueItem(
                    item = queueItem,
                    itemAfter = MatchPointItemReference(AFTER_ID),
                    metadata = MatchPointMetadata(
                        mapOf(
                            "title" to "Track",
                            "artist" to "Artist",
                            "album" to "Album",
                            "duration" to 12.5,
                            "artwork" to byteArrayOf(4, 5),
                            "oem" to mapOf("flag" to true),
                        ),
                    ),
                    forInterstitial = false,
                ),
            ),
            MatchPointMessage.oneWay(MatchPointRemovePlayQueueItem(reference)),
            MatchPointMessage.oneWay(MatchPointStop),
            MatchPointMessage.oneWay(
                MatchPointSeek(reference, MatchPointSeekPosition.Time(time), true),
            ),
            MatchPointMessage.oneWay(
                MatchPointSeek(
                    reference,
                    MatchPointSeekPosition.Date(MatchPointDate(1234.5)),
                ),
            ),
            MatchPointMessage.oneWay(MatchPointSetRate(1.25, time, -0.25, false)),
            MatchPointMessage.oneWay(
                MatchPointSetProperty("muted", true, reference, "main", false),
            ),
            MatchPointMessage.oneWay(MatchPointPlaybackCoordinationMedium("group", "active")),
            MatchPointMessage.oneWay(
                MatchPointPlaybackState(MatchPointPlaybackStateName.PLAYING, item = reference),
            ),
            MatchPointMessage.oneWay(MatchPointNotification),
            MatchPointMessage.response(
                2,
                MatchPointPlaybackInfoResponse(mapOf("rate" to 1.0, "item" to ITEM_ID)),
            ),
            MatchPointMessage.response(3, MatchPointPropertyResponse(byteArrayOf(8, 9))),
            MatchPointMessage.response(
                4,
                MatchPointStreamingKeyResponse(byteArrayOf(10)),
            ),
            MatchPointMessage.response(5, MatchPointUnhandledUrlResponse),
            MatchPointMessage.response(
                1,
                MatchPointAuthorizeItemResponse(
                    item = mapOf("future" to true),
                    picData = byteArrayOf(11, 12),
                    playerGuid = mapOf("future-guid-shape" to true),
                ),
            ),
            MatchPointMessage.failure(6, MatchPointTypes.PLAYBACK_INFO, -1L),
        )

        messages.forEach { original ->
            val decoded = MatchPointCodec.decode(MatchPointCodec.encode(original))
            assertEquals(original.kind, decoded.kind)
            assertEquals(original.type, decoded.type)
            assertEquals(original.messageId, decoded.messageId)
            assertTrue(decoded.hasKnownSchema)
            assertFalse(decoded.isRuntimeReady)
        }

        MatchPointPlaybackStateName.entries.forEach { state ->
            val decoded = roundTrip(MatchPointMessage.oneWay(MatchPointPlaybackState(state)))
            assertEquals(state, (decoded.payload as MatchPointPlaybackState).name)
        }
    }

    @Test
    fun requestResponseAndOneWayHeadersUseTheConfirmedShapesAndUInt64() {
        val requestBytes = MatchPointCodec.encode(
            MatchPointMessage.request(-1L, MatchPointPlaybackInfoRequest),
        )
        val request = BplistCodec.decode(requestBytes) as Map<*, *>
        assertEquals(setOf("kind", "messageID", "type"), request.keys)
        assertEquals("request", request["kind"])
        assertEquals(-1L, request["messageID"])

        val responseBytes = MatchPointCodec.encode(
            MatchPointMessage.failure(-1L, MatchPointTypes.PLAYBACK_INFO, 127L),
        )
        val response = BplistCodec.decode(responseBytes) as Map<*, *>
        assertEquals(setOf("kind", "type", "messageID", "error"), response.keys)
        assertEquals("response", response["kind"])
        assertEquals(-1L, response["messageID"])
        assertEquals(127L, response["error"])

        val oneWay = BplistCodec.decode(
            MatchPointCodec.encode(MatchPointMessage.oneWay(MatchPointStop)),
        ) as Map<*, *>
        assertEquals(mapOf("type" to "stop"), oneWay)
    }

    @Test
    fun streamingKeySeparatesRequestParamsFromTopLevelResponseKey() {
        val request = BplistCodec.decode(
            MatchPointCodec.encode(
                MatchPointMessage.request(
                    7,
                    MatchPointStreamingKeyRequest(mapOf("purpose" to "playback")),
                ),
            ),
        ) as Map<*, *>
        assertEquals(setOf("kind", "messageID", "type", "params"), request.keys)
        assertEquals(mapOf("purpose" to "playback"), request["params"])

        val response = BplistCodec.decode(
            MatchPointCodec.encode(
                MatchPointMessage.response(7, MatchPointStreamingKeyResponse(byteArrayOf(1, 2, 3))),
            ),
        ) as Map<*, *>
        assertEquals(setOf("kind", "type", "messageID", "key"), response.keys)
        assertArrayEquals(byteArrayOf(1, 2, 3), response["key"] as ByteArray)

        assertMalformed(
            mapOf(
                "kind" to "request",
                "messageID" to 7,
                "type" to MatchPointTypes.STREAMING_KEY,
                "key" to byteArrayOf(1),
            ),
        )
        assertMalformed(
            mapOf(
                "kind" to "response",
                "messageID" to 7,
                "type" to MatchPointTypes.STREAMING_KEY,
                "params" to mapOf("key" to byteArrayOf(1)),
            ),
        )
    }

    @Test
    fun streamingKeyResponsePreservesBase64AndOpaqueWireStrings() {
        val base64Message = linkedMapOf<String, Any>(
            "kind" to "response",
            "type" to MatchPointTypes.STREAMING_KEY,
            "messageID" to 8,
            "key" to "AQID",
        )
        val base64Decoded = MatchPointCodec.decode(BplistCodec.encode(base64Message))
        val base64Value =
            (base64Decoded.payload as MatchPointStreamingKeyResponse).key as
                MatchPointStreamingKeyValue.Text
        assertEquals("AQID", base64Value.text)
        assertArrayEquals(byteArrayOf(1, 2, 3), base64Value.decodedData)
        val base64RoundTrip = BplistCodec.decode(MatchPointCodec.encode(base64Decoded)) as Map<*, *>
        assertEquals("AQID", base64RoundTrip["key"])

        val opaqueMessage = linkedMapOf<String, Any>(
            "kind" to "response",
            "type" to MatchPointTypes.STREAMING_KEY,
            "messageID" to 9,
            "key" to "not+base64?",
        )
        val opaqueDecoded = MatchPointCodec.decode(BplistCodec.encode(opaqueMessage))
        val opaqueValue =
            (opaqueDecoded.payload as MatchPointStreamingKeyResponse).key as
                MatchPointStreamingKeyValue.Text
        assertEquals("not+base64?", opaqueValue.text)
        assertEquals(null, opaqueValue.decodedData)
        val opaqueRoundTrip = BplistCodec.decode(MatchPointCodec.encode(opaqueDecoded)) as Map<*, *>
        assertEquals("not+base64?", opaqueRoundTrip["key"])

        assertMalformed(
            mapOf(
                "kind" to "response",
                "type" to MatchPointTypes.STREAMING_KEY,
                "messageID" to 10,
                "key" to 123,
            ),
        )
    }

    @Test
    fun authorizeItemResponseFieldsAreOptionalAndItemRemainsOpaque() {
        val sourceItem = linkedMapOf<String, Any>("future-item-shape" to byteArrayOf(4, 5))
        val sourcePic = byteArrayOf(6, 7)
        val message = MatchPointMessage.response(
            11,
            MatchPointAuthorizeItemResponse(
                item = sourceItem,
                picData = sourcePic,
                playerGuid = mapOf("future-guid-shape" to true),
            ),
        )
        (sourceItem["future-item-shape"] as ByteArray).fill(0)
        sourcePic.fill(0)

        val encoded = BplistCodec.decode(MatchPointCodec.encode(message)) as Map<*, *>
        assertEquals(
            setOf("kind", "type", "messageID", "item", "PIC-Data", "playerGUID"),
            encoded.keys,
        )
        val encodedItem = encoded["item"] as Map<*, *>
        assertArrayEquals(byteArrayOf(4, 5), encodedItem["future-item-shape"] as ByteArray)
        assertArrayEquals(byteArrayOf(6, 7), encoded["PIC-Data"] as ByteArray)
        assertEquals(mapOf("future-guid-shape" to true), encoded["playerGUID"])

        val decoded = MatchPointCodec.decode(MatchPointCodec.encode(message))
        val payload = decoded.payload as MatchPointAuthorizeItemResponse
        assertArrayEquals(byteArrayOf(6, 7), payload.picData)
        val firstItem = payload.item as Map<*, *>
        val firstBytes = firstItem["future-item-shape"] as ByteArray
        assertArrayEquals(byteArrayOf(4, 5), firstBytes)
        firstBytes.fill(9)
        assertArrayEquals(
            byteArrayOf(4, 5),
            (payload.item as Map<*, *>)["future-item-shape"] as ByteArray,
        )

        val empty = roundTrip(
            MatchPointMessage.response(12, MatchPointAuthorizeItemResponse()),
        ).payload as MatchPointAuthorizeItemResponse
        assertEquals(null, empty.item)
        assertEquals(null, empty.picData)
        assertEquals(null, empty.playerGuid)

        assertThrows(IllegalArgumentException::class.java) {
            MatchPointEmptyResponse(MatchPointTypes.AUTHORIZE_ITEM)
        }
        assertMalformed(
            mapOf(
                "kind" to "response",
                "type" to MatchPointTypes.AUTHORIZE_ITEM,
                "messageID" to 13,
                "PIC-Data" to "not-data",
            ),
        )
        assertMalformed(
            mapOf(
                "kind" to "response",
                "type" to MatchPointTypes.AUTHORIZE_ITEM,
                "messageID" to 14,
                "future" to true,
            ),
        )
    }

    @Test
    fun carrierWrapsInnerBplistAtParamsDataAndOnlyBuildsCommValue() {
        val message = MatchPointMessage.request(7, MatchPointPlaybackInfoRequest)
        val carrier = MatchPointCarrierCodec.encode(message)
        val wrapper = BplistCodec.decode(carrier.rcsBody) as Map<*, *>
        val params = wrapper["params"] as Map<*, *>

        assertArrayEquals(carrier.innerData, params["data"] as ByteArray)
        val rcs = carrier.asRcsMessage()
        assertEquals(ApTransportPackageCodec.MESSAGE_TYPE_COMM, rcs.messageType)
        assertEquals(MatchPointTypes.PLAYBACK_INFO, MatchPointCarrierCodec.decode(rcs).message.type)

        val exposed = carrier.rcsBody
        exposed.fill(0)
        assertFalse(carrier.rcsBody.all { it == 0.toByte() })
        assertThrows(MatchPointProtocolException::class.java) {
            MatchPointCarrierCodec.decode(RcsMessage(123, carrier.rcsBody))
        }
        assertThrows(MatchPointProtocolException::class.java) {
            MatchPointCarrierCodec.decode(
                RcsMessage.comm(BplistCodec.encode(mapOf("params" to mapOf("data" to carrier.innerData), "extra" to 1))),
            )
        }
    }

    @Test
    fun knownMessagesRejectMalformedFieldsWrongDirectionsAndInexactCmTime() {
        assertMalformed(
            mapOf(
                "kind" to "request",
                "messageID" to 1,
                "type" to "playbackInfo",
                "future" to true,
            ),
        )
        assertMalformed(mapOf("type" to "playbackInfo"))
        assertMalformed(
            mapOf(
                "type" to "seek",
                "item" to mapOf("uuid" to ITEM_ID),
                "time" to mapOf(
                    "value" to 1,
                    "timescale" to 1000.0,
                    "flags" to 1,
                    "epoch" to 0,
                ),
            ),
        )
        assertMalformed(
            mapOf(
                "type" to "seek",
                "item" to mapOf("uuid" to ITEM_ID),
                "time" to mapOf("value" to 1, "timescale" to 1000, "flags" to 4, "epoch" to 0),
            ),
        )
        assertMalformed(
            mapOf(
                "type" to "seek",
                "item" to mapOf("uuid" to ITEM_ID),
                "time" to mapOf("value" to 1, "timescale" to 1000, "flags" to 1),
            ),
        )
        assertMalformed(
            mapOf(
                "type" to "seek",
                "item" to mapOf("uuid" to ITEM_ID),
                "time" to mapOf("value" to 1, "timescale" to 1000, "flags" to 1, "epoch" to 0),
                "date" to 1.0,
            ),
        )
        assertMalformed(
            mapOf(
                "kind" to "request",
                "messageID" to 0,
                "type" to "property",
                "property" to "rate",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            MatchPointCmTime(1, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MatchPointCmTime(-1, 1000)
        }
    }

    @Test
    fun unknownTypeIsOpaqueBoundedDefensivelyCopiedAndNeverReady() {
        val mutable = byteArrayOf(1, 2, 3)
        val decoded = MatchPointCodec.decode(
            BplistCodec.encode(
                linkedMapOf(
                    "kind" to "request",
                    "messageID" to BigInteger("18446744073709551615"),
                    "type" to "futurePlaybackCommand",
                    "future" to linkedMapOf("bytes" to mutable, "empty" to ""),
                ),
            ),
        )
        mutable.fill(99)

        assertEquals(-1L, decoded.messageId)
        assertFalse(decoded.hasKnownSchema)
        assertFalse(decoded.isRuntimeReady)
        val payload = decoded.payload as MatchPointUnknownPayload
        val first = ((payload.fields["future"] as Map<*, *>)["bytes"] as ByteArray)
        assertArrayEquals(byteArrayOf(1, 2, 3), first)
        first.fill(42)
        val second = ((payload.fields["future"] as Map<*, *>)["bytes"] as ByteArray)
        assertArrayEquals(byteArrayOf(1, 2, 3), second)
        assertNotSame(first, second)

        val roundTrip = MatchPointCodec.decode(MatchPointCodec.encode(decoded))
        assertFalse(roundTrip.hasKnownSchema)

        val unknownError = MatchPointCodec.decode(
            BplistCodec.encode(
                mapOf(
                    "kind" to "response",
                    "type" to "futurePlaybackCommand",
                    "messageID" to 9,
                    "error" to mapOf("code" to 1L),
                ),
            ),
        )
        assertFalse(unknownError.hasKnownSchema)
        assertEquals(1L, (unknownError.error as Map<*, *>)["code"])
    }

    @Test
    fun queueItemTlsReadinessRequiresPairingPskRegardlessOfHostname() {
        val localTls = MatchPointQueueItem(
            uuid = ITEM_ID,
            contentLocation = "https://192.168.44.1:8443/item.m3u8",
            isTlsEnabled = true,
        )
        assertEquals(
            MatchPointItemReadiness.PAIRING_PSK_TLS_REQUIRED,
            localTls.playbackReadiness(
                MatchPointPlaybackCapabilities(
                    supportsHttp = true,
                    supportsHttps = true,
                    supportsExternalPairingPskTls = false,
                ),
            ),
        )
        assertEquals(
            MatchPointItemReadiness.READY,
            localTls.playbackReadiness(
                MatchPointPlaybackCapabilities(
                    supportsHttp = false,
                    supportsHttps = false,
                    supportsExternalPairingPskTls = true,
                ),
            ),
        )

        val domainTls = MatchPointQueueItem(
            uuid = ITEM_ID,
            contentLocation = "https://media.example.test/item.m3u8",
            isTlsEnabled = true,
        )
        val availableTransport = MatchPointPlaybackCapabilities(
            supportsHttp = true,
            supportsHttps = false,
            supportsExternalPairingPskTls = true,
        )
        assertEquals(
            MatchPointItemReadiness.PAIRING_PSK_TLS_REQUIRED,
            domainTls.playbackReadiness(
                MatchPointPlaybackCapabilities(false, true, false),
            ),
        )
        assertEquals(
            MatchPointItemReadiness.READY,
            domainTls.playbackReadiness(availableTransport),
        )
        // The owner must refresh this snapshot after transport retirement. This model cannot
        // prove lease liveness itself, but HTTPS capability must never mask PSK unavailability.
        val retiredTransport = availableTransport.copy(
            supportsHttps = true,
            supportsExternalPairingPskTls = false,
        )
        assertEquals(
            MatchPointItemReadiness.PAIRING_PSK_TLS_REQUIRED,
            domainTls.playbackReadiness(retiredTransport),
        )
        assertEquals(
            MatchPointItemReadiness.UNSUPPORTED_TRANSPORT,
            MatchPointQueueItem(
                uuid = ITEM_ID,
                contentLocation = "http://media.example.test/item.m3u8",
                isTlsEnabled = true,
            ).playbackReadiness(MatchPointPlaybackCapabilities(true, true, true)),
        )
        assertEquals(
            MatchPointItemReadiness.READY,
            MatchPointQueueItem(
                uuid = ITEM_ID,
                contentLocation = "https://media.example.test/item.m3u8",
                isTlsEnabled = false,
            ).playbackReadiness(MatchPointPlaybackCapabilities(false, true, false)),
        )
        assertEquals(
            MatchPointItemReadiness.MISSING_LOCATION,
            MatchPointQueueItem(ITEM_ID).playbackReadiness(
                MatchPointPlaybackCapabilities(true, true, true),
            ),
        )
    }

    @Test
    fun queueItemPreservesBoundedUnknownFieldsWithoutInterpretingOrAliasingThem() {
        val extensionBytes = byteArrayOf(1, 2, 3)
        val item = MatchPointQueueItem(
            uuid = ITEM_ID,
            contentLocation = "https://media.example.test/item.m3u8",
            isTlsEnabled = true,
            extensionFields = linkedMapOf(
                "futureTransportHint" to mapOf(
                    "scheme" to "ordinary-https",
                    "payload" to extensionBytes,
                ),
            ),
        )
        extensionBytes[0] = 99
        val encoded = MatchPointCodec.encode(
            MatchPointMessage.oneWay(MatchPointInsertPlayQueueItem(item)),
        )
        val decoded = MatchPointCodec.decode(encoded)
        val decodedItem = (decoded.payload as MatchPointInsertPlayQueueItem).item
        val first = decodedItem.extensionFields["futureTransportHint"] as Map<*, *>
        assertArrayEquals(byteArrayOf(1, 2, 3), first["payload"] as ByteArray)
        (first["payload"] as ByteArray)[0] = 88
        val second = decodedItem.extensionFields["futureTransportHint"] as Map<*, *>
        assertArrayEquals(byteArrayOf(1, 2, 3), second["payload"] as ByteArray)
        assertEquals("ordinary-https", second["scheme"])
        assertEquals(
            MatchPointItemReadiness.PAIRING_PSK_TLS_REQUIRED,
            decodedItem.playbackReadiness(MatchPointPlaybackCapabilities(false, true, false)),
        )
        val roundTrip = MatchPointCodec.decode(MatchPointCodec.encode(decoded))
        val roundTripItem = (roundTrip.payload as MatchPointInsertPlayQueueItem).item
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            (roundTripItem.extensionFields["futureTransportHint"] as Map<*, *>)["payload"] as ByteArray,
        )

        assertThrows(MatchPointProtocolException::class.java) {
            MatchPointQueueItem(ITEM_ID, extensionFields = mapOf("IsTLSEnabled" to true))
        }
        assertThrows(MatchPointProtocolException::class.java) {
            MatchPointQueueItem(
                ITEM_ID,
                extensionFields = mapOf("tooLarge" to ByteArray(33)),
                limits = MatchPointLimits(maximumValueBytes = 32, maximumArtworkBytes = 16),
            )
        }
        assertThrows(MatchPointProtocolException::class.java) {
            MatchPointQueueItem(ITEM_ID, extensionFields = mapOf("unsupported" to Any()))
        }
        assertMalformed(
            mapOf(
                "type" to MatchPointTypes.INSERT_PLAY_QUEUE_ITEM,
                "item" to mapOf("uuid" to ITEM_ID, "IsTLSEnabled" to "true"),
            ),
        )
    }

    @Test
    fun requestTrackerCorrelatesTypeAndIdExpiresBoundsAndWrapsUInt64() {
        var now = 1_000L
        val tracker = MatchPointRequestTracker(
            firstMessageId = -2L,
            maximumPending = 3,
            timeoutMillis = 1,
            nowNanos = { now },
        )
        val (first, firstPending) = tracker.begin(MatchPointPlaybackInfoRequest)
        val (second, _) = tracker.begin(MatchPointPropertyRequest("rate"))
        val (third, _) = tracker.begin(MatchPointStreamingKeyRequest())
        assertEquals(listOf(-2L, -1L, 1L), listOf(first.messageId, second.messageId, third.messageId))
        assertEquals(3, tracker.size())
        assertThrows(MatchPointProtocolException::class.java) {
            tracker.begin(MatchPointPlaybackInfoRequest)
        }

        assertThrows(MatchPointProtocolException::class.java) {
            tracker.accept(
                MatchPointMessage.response(
                    firstPending.messageId,
                    MatchPointPropertyResponse(1L),
                ),
            )
        }
        assertEquals(3, tracker.size())
        assertEquals(
            firstPending,
            tracker.accept(
                MatchPointMessage.response(
                    firstPending.messageId,
                    MatchPointPlaybackInfoResponse(mapOf("rate" to 1.0)),
                ),
            ),
        )
        assertEquals(2, tracker.size())

        now += 1_000_001L
        val expired = tracker.expire()
        assertEquals(setOf(-1L, 1L), expired.mapTo(linkedSetOf()) { it.messageId })
        assertEquals(0, tracker.size())
        assertThrows(MatchPointProtocolException::class.java) {
            tracker.accept(
                MatchPointMessage.response(
                    second.messageId!!,
                    MatchPointPropertyResponse(1L),
                ),
            )
        }
    }

    @Test
    fun mutableInputsCyclesAndAggregateBudgetsFailClosed() {
        val pic = byteArrayOf(1, 2, 3)
        val message = MatchPointMessage.request(
            1,
            MatchPointAuthorizeItem(MatchPointItemReference(ITEM_ID), pic),
        )
        pic.fill(9)
        val decodedPic = (roundTrip(message).payload as MatchPointAuthorizeItem).picRequest
        assertArrayEquals(byteArrayOf(1, 2, 3), decodedPic)

        val artwork = byteArrayOf(4, 5, 6)
        val metadata = MatchPointMetadata(mapOf("title" to "x", "artwork" to artwork))
        artwork.fill(9)
        val exposed = metadata.artwork as ByteArray
        assertArrayEquals(byteArrayOf(4, 5, 6), exposed)
        exposed.fill(8)
        assertArrayEquals(byteArrayOf(4, 5, 6), metadata.artwork as ByteArray)

        val cycle = linkedMapOf<String, Any>()
        cycle["self"] = cycle
        assertThrows(MatchPointProtocolException::class.java) {
            MatchPointOpaqueValue(cycle)
        }

        val tight = MatchPointLimits(
            maximumMessageBytes = 256,
            maximumCarrierBytes = 512,
            maximumValueBytes = 32,
            maximumArtworkBytes = 4,
            maximumStringChars = 16,
            maximumIdentifierChars = 16,
            maximumCollectionEntries = 2,
            maximumValueNodes = 3,
            maximumNestingDepth = 2,
        )
        assertThrows(MatchPointProtocolException::class.java) {
            MatchPointMetadata(mapOf("artwork" to byteArrayOf(1, 2, 3, 4, 5)), tight)
        }
        assertThrows(MatchPointProtocolException::class.java) {
            MatchPointOpaqueValue(listOf(1, 2, 3), tight)
        }
    }

    @Test
    fun modelDoesNotPromoteMatchPointCatalogReadiness() {
        assertEquals(AirPlayFeature.VIDEO_PLAYBACK, RcsClientTypes.CAR_PLAY_MATCH_POINT.feature)
        assertEquals(
            RcsCatalogReadiness.METADATA_ONLY,
            RcsClientTypes.CAR_PLAY_MATCH_POINT.catalogReadiness,
        )
        assertEquals(
            RcsPayloadStyle.MATCH_POINT_BINARY_PLIST,
            RcsClientTypes.CAR_PLAY_MATCH_POINT.payloadStyle,
        )
        assertFalse(RcsClientTypes.CAR_PLAY_MATCH_POINT.hasConfirmedSchema)
    }

    private fun roundTrip(message: MatchPointMessage): MatchPointMessage =
        MatchPointCodec.decode(MatchPointCodec.encode(message))

    private fun assertMalformed(value: Map<String, Any>) {
        assertThrows(MatchPointProtocolException::class.java) {
            MatchPointCodec.decode(BplistCodec.encode(value))
        }
    }

    private companion object {
        const val ITEM_ID = "11111111-2222-3333-4444-555555555555"
        const val AFTER_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    }
}
