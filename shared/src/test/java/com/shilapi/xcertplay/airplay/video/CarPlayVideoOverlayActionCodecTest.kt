package com.shilapi.xcertplay.airplay.video

import com.shilapi.xcertplay.airplay.BplistCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayVideoOverlayActionCodecTest {
    private val codec = CarPlayVideoOverlayActionCodec()
    private val layerUuid = "01234567-89ab-cdef-0123-456789abcdef"
    private val childUuid = "FEDCBA98-7654-3210-FEDC-BA9876543210"

    @Test
    fun allTwelveStringActionsAreCataloguedWithoutAssumingOtherSchemas() {
        assertEquals(
            listOf(
                "createLayer", "updateLayer", "insertLayer", "removeLayer", "userAction",
                "createAsset", "removeAsset", "transaction", "style", "createAnimation",
                "animate", "info",
            ),
            CarPlayVideoOverlayWireAction.entries.map { it.wireValue },
        )
        assertNull(CarPlayVideoOverlayWireAction.fromWireValue("futureAction"))

        listOf("userAction", "transaction", "createAnimation", "animate", "info").forEach { name ->
            val action = decode(function(name, arguments = mapOf("future" to byteArrayOf(1))))
            assertTrue(action is CarPlayVideoOverlayAction.Opaque)
            action as CarPlayVideoOverlayAction.Opaque
            assertEquals(name, action.knownAction?.wireValue)
            assertEquals(name, action.rawMessage.field("action"))
        }
    }

    @Test
    fun createsRingAndTextOnlyFromExactLayerEnvelopes() {
        val ring = decode(
            function(
                "createLayer", layerUuid,
                mapOf("type" to "ring", "createArguments" to mapOf("radius" to 12.5f)),
            ),
        ) as CarPlayVideoOverlayAction.CreateRing
        assertEquals(layerUuid, ring.objectUuid)
        assertEquals(12.5, ring.radius, 0.0)
        assertEquals("createLayer", ring.rawMessage.field("action"))

        val text = decode(
            function(
                "createLayer", layerUuid,
                mapOf("type" to "text", "createArguments" to mapOf("text" to "pace")),
            ),
        ) as CarPlayVideoOverlayAction.CreateText
        assertEquals(layerUuid, text.objectUuid)
        assertEquals("pace", text.text)
        assertFalse(text.toString().contains("pace"))
        assertFalse(ring.toString().contains(layerUuid))
    }

    @Test
    fun updateShapesRemainCandidatesWithoutClaimingLayerSubtype() {
        val radius = decode(function("updateLayer", layerUuid, mapOf("radius" to 4.25)))
            as CarPlayVideoOverlayAction.UpdateRadiusCandidate
        assertEquals(layerUuid, radius.objectUuid)
        assertEquals(4.25, radius.radius, 0.0)

        val text = decode(function("updateLayer", layerUuid, mapOf("text" to "new")))
            as CarPlayVideoOverlayAction.UpdateTextCandidate
        assertEquals("new", text.text)

        assertOpaque(function("updateLayer", layerUuid, mapOf("text" to "new", "radius" to 4.25)))
        assertOpaque(function("updateLayer", layerUuid, mapOf("radius" to 4L)))
    }

    @Test
    fun insertsAndRemovesLayersWithUuidAndIntegerFields() {
        val insert = decode(
            function("insertLayer", layerUuid, mapOf("uuidOfSubLayer" to childUuid, "index" to 3)),
        ) as CarPlayVideoOverlayAction.InsertLayer
        assertEquals(layerUuid, insert.objectUuid)
        assertEquals(childUuid, insert.subLayerUuid)
        assertEquals(3, insert.index)

        val remove = decode(function("removeLayer", layerUuid)) as CarPlayVideoOverlayAction.RemoveLayer
        assertEquals(layerUuid, remove.objectUuid)

        assertOpaque(function("insertLayer", layerUuid, mapOf("uuidOfSubLayer" to "not-a-uuid", "index" to 3)))
        assertOpaque(function("insertLayer", layerUuid, mapOf("uuidOfSubLayer" to childUuid, "index" to 2147483648L)))
        assertOpaque(function("removeLayer", layerUuid, emptyMap<String, Any>()))
    }

    @Test
    fun onlyFloatOpacityStyleIsTyped() {
        val opacity = decode(function("style", layerUuid, mapOf("key" to "opacity", "value" to 0.5)))
            as CarPlayVideoOverlayAction.SetOpacity
        assertEquals(layerUuid, opacity.objectUuid)
        assertEquals(0.5, opacity.opacity, 0.0)

        listOf("origin", "size", "transform").forEach { key ->
            assertOpaque(function("style", layerUuid, mapOf("key" to key, "value" to mapOf("x" to 1.0))))
        }
        assertOpaque(function("style", layerUuid, mapOf("key" to "opacity", "value" to 1L)))
        assertOpaque(function("style", layerUuid, mapOf("key" to "opacity", "value" to Double.NaN)))
        assertOpaque(function("style", layerUuid, mapOf("key" to "opacity", "value" to Double.POSITIVE_INFINITY)))
    }

    @Test
    fun typedRealsPreserveFiniteFloat64PrecisionAndMagnitude() {
        val small = 1e-300
        val roundedByFloat32 = 0.1
        val ring = decode(
            function(
                "createLayer", layerUuid,
                mapOf("type" to "ring", "createArguments" to mapOf("radius" to small)),
            ),
        ) as CarPlayVideoOverlayAction.CreateRing
        assertEquals(small, ring.radius, 0.0)
        assertEquals(
            small,
            ((ring.rawMessage.field("arguments") as Map<*, *>)["createArguments"] as Map<*, *>)["radius"],
        )

        val update = decode(function("updateLayer", layerUuid, mapOf("radius" to roundedByFloat32)))
            as CarPlayVideoOverlayAction.UpdateRadiusCandidate
        assertEquals(roundedByFloat32, update.radius, 0.0)
        assertFalse(update.radius == roundedByFloat32.toFloat().toDouble())

        val opacity = decode(function("style", layerUuid, mapOf("key" to "opacity", "value" to small)))
            as CarPlayVideoOverlayAction.SetOpacity
        assertEquals(small, opacity.opacity, 0.0)

        val asset = decode(
            function(
                "createAsset",
                arguments = mapOf(
                    "name" to "precise", "data" to byteArrayOf(1),
                    "manifest" to mapOf(
                        "mimeType" to "image/png",
                        "sprite" to mapOf(
                            "width" to roundedByFloat32,
                            "height" to small,
                            "totalFrames" to 1,
                            "initialX" to -small,
                            "initialY" to Math.PI,
                        ),
                    ),
                ),
            ),
        ) as CarPlayVideoOverlayAction.CreateAsset
        assertEquals(
            CarPlayVideoOverlayAction.Sprite(roundedByFloat32, small, 1, -small, Math.PI),
            asset.sprite,
        )
    }

    @Test
    fun createsAndRemovesAssetsWithoutAnObjectUuid() {
        val bytes = byteArrayOf(1, 2, 3)
        val create = decode(
            function(
                "createAsset",
                arguments = mapOf(
                    "name" to "sprite.png",
                    "data" to bytes,
                    "manifest" to mapOf(
                        "mimeType" to "image/png",
                        "sprite" to mapOf(
                            "width" to 32.0, "height" to 24.0, "totalFrames" to 8,
                            "initialX" to 1.0, "initialY" to 2.0,
                        ),
                    ),
                ),
            ),
        ) as CarPlayVideoOverlayAction.CreateAsset
        bytes[0] = 9
        assertEquals("sprite.png", create.name)
        assertEquals("image/png", create.mimeType)
        assertEquals(CarPlayVideoOverlayAction.Sprite(32.0, 24.0, 8, 1.0, 2.0), create.sprite)
        assertArrayEquals(byteArrayOf(1, 2, 3), create.data)
        create.data[0] = 8
        assertArrayEquals(byteArrayOf(1, 2, 3), create.data)
        val rawData = (create.rawMessage.field("arguments") as Map<*, *>)["data"] as ByteArray
        rawData[0] = 7
        assertArrayEquals(byteArrayOf(1, 2, 3), create.data)
        assertFalse(create.toString().contains("sprite.png"))

        val video = decode(
            function(
                "createAsset",
                arguments = mapOf(
                    "name" to "frame", "data" to byteArrayOf(4),
                    "manifest" to mapOf("mimeType" to "video/JPEG"),
                ),
            ),
        ) as CarPlayVideoOverlayAction.CreateAsset
        assertEquals("video/JPEG", video.mimeType)
        assertNull(video.sprite)

        val remove = decode(function("removeAsset", arguments = mapOf("name" to "sprite.png")))
            as CarPlayVideoOverlayAction.RemoveAsset
        assertEquals("sprite.png", remove.name)
    }

    @Test
    fun extendedAndMalformedKnownShapesStayOpaqueWithAllFields() {
        val cases = listOf(
            function("createLayer", "bad-uuid", mapOf("type" to "ring", "createArguments" to mapOf("radius" to 1.0))),
            function("createLayer", layerUuid, mapOf("type" to "ring", "createArguments" to mapOf("radius" to "1"))),
            function("createLayer", layerUuid, mapOf("type" to "image", "createArguments" to mapOf("name" to "a"))),
            function("createLayer", layerUuid, mapOf("type" to "ring", "createArguments" to mapOf("radius" to 1.0, "future" to 2))),
            function("removeAsset", arguments = mapOf("name" to 1)),
            function("createAsset", arguments = mapOf("name" to "a", "data" to byteArrayOf(1), "manifest" to mapOf("mimeType" to "image/jpeg"))),
            function("createAsset", arguments = mapOf("name" to "a", "data" to byteArrayOf(1), "manifest" to mapOf("mimeType" to "image/png", "unknown" to true))),
            function("createAsset", layerUuid, mapOf("name" to "a", "data" to byteArrayOf(1), "manifest" to mapOf("mimeType" to "image/png"))),
            function("removeLayer", layerUuid).plus("messageID" to 9),
            function("removeLayer", layerUuid).plus("unexpected" to mapOf("preserve" to byteArrayOf(2))),
            function("removeLayer", layerUuid).plus("type" to 5),
        )
        cases.forEach(::assertOpaque)

        val unknown = function("futureAction", layerUuid, mapOf("opaque" to byteArrayOf(5, 6)))
        val result = decode(unknown) as CarPlayVideoOverlayAction.Opaque
        assertNull(result.knownAction)
        assertEquals("futureAction", result.rawMessage.field("action"))
        val arguments = result.rawMessage.field("arguments") as Map<*, *>
        assertArrayEquals(byteArrayOf(5, 6), arguments["opaque"] as ByteArray)
        assertFalse(result.toString().contains("futureAction"))
    }

    @Test
    fun malformedCarrierAndLocalBoundsFailBeforeClassification() {
        assertThrows(CarPlayVideoOverlayProtocolException::class.java) {
            codec.decodeFromPhone(BplistCodec.encode(listOf("removeLayer")))
        }
        assertThrows(CarPlayVideoOverlayProtocolException::class.java) {
            codec.decodeFromPhone(BplistCodec.encode(function("removeLayer", layerUuid)) + byteArrayOf(0))
        }
        val bounded = CarPlayVideoOverlayActionCodec(
            CarPlayVideoOverlayCodec(CarPlayVideoOverlayLimits(maximumDataBytes = 2)),
        )
        assertThrows(CarPlayVideoOverlayProtocolException::class.java) {
            bounded.decodeFromPhone(
                BplistCodec.encode(
                    function(
                        "createAsset", arguments = mapOf(
                            "name" to "x", "data" to byteArrayOf(1, 2, 3),
                            "manifest" to mapOf("mimeType" to "image/png"),
                        ),
                    ),
                ),
            )
        }
    }

    private fun decode(fields: Map<String, Any?>): CarPlayVideoOverlayAction =
        codec.decodeFromPhone(BplistCodec.encode(fields))

    private fun assertOpaque(fields: Map<String, Any?>) {
        val action = decode(fields)
        assertTrue("Expected opaque classification for ${fields["action"]}", action is CarPlayVideoOverlayAction.Opaque)
        assertEquals(fields.keys, action.rawMessage.fields.keys)
    }

    private fun function(
        action: String,
        objectUuid: String? = null,
        arguments: Map<String, Any?>? = null,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "type" to "function", "action" to action,
    ).apply {
        if (objectUuid != null) this["objectUUID"] = objectUuid
        if (arguments != null) this["arguments"] = arguments
    }
}
