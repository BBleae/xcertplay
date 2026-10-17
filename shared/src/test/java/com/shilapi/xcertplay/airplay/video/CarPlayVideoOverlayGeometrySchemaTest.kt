package com.shilapi.xcertplay.airplay.video

import com.shilapi.xcertplay.airplay.BplistCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayVideoOverlayGeometrySchemaTest {
    private val codec = CarPlayVideoOverlayActionCodec()
    private val ringUuid = "01234567-89ab-cdef-0123-456789abcdef"
    private val textUuid = "fedcba98-7654-3210-fedc-ba9876543210"

    @Test
    fun exactAbsoluteFractionalAndAxisSpecificAnchorsAreTypedWithoutPixelConversion() {
        val origin = mapOf(
            "x" to axis("absolutePoint", 120.25f),
            "y" to axis("fractional", 0.5f),
        )
        val action = decode(style(ringUuid, "origin", origin)) as CarPlayVideoOverlayAction.SetOrigin
        assertEquals(
            CarPlayVideoOverlayGeometrySchema.Point(
                CarPlayVideoOverlayGeometrySchema.AxisPosition.AbsolutePoint(120.25),
                CarPlayVideoOverlayGeometrySchema.AxisPosition.Fractional(0.5),
            ),
            action.origin,
        )

        val anchored = mapOf(
            "x" to axis("anchorPoint", mapOf("edge" to "leading", "offset" to -3.0f)),
            "y" to axis("anchorPoint", mapOf("edge" to "bottom", "offset" to 5.0f)),
        )
        val anchoredAction = decode(style(ringUuid, "origin", anchored)) as CarPlayVideoOverlayAction.SetOrigin
        assertEquals(
            CarPlayVideoOverlayGeometrySchema.AxisPosition.AnchorPoint("leading", -3.0),
            anchoredAction.origin.x,
        )
        assertEquals(
            CarPlayVideoOverlayGeometrySchema.AxisPosition.AnchorPoint("bottom", 5.0),
            anchoredAction.origin.y,
        )
        listOf("trailing", "left", "right").forEach { edge ->
            assertTrue(CarPlayVideoOverlayGeometrySchema.point(mapOf(
                "x" to axis("anchorPoint", mapOf("edge" to edge, "offset" to 0.0f)),
                "y" to axis("absolutePoint", 0.0f),
            )) != null)
        }
        assertOpaque(style(ringUuid, "position", origin))
    }

    @Test
    fun sizeCenterAnchorAndAffineSixRealTransformAreTyped() {
        val size = mapOf(
            "width" to axis("absolutePoint", 320.0f),
            "height" to axis("fractional", 0.75f),
        )
        val sizeAction = decode(style(ringUuid, "size", size)) as CarPlayVideoOverlayAction.SetSize
        assertEquals(
            CarPlayVideoOverlayGeometrySchema.Size(
                CarPlayVideoOverlayGeometrySchema.Dimension.Absolute(320.0),
                CarPlayVideoOverlayGeometrySchema.Dimension.Fractional(0.75),
            ),
            sizeAction.size,
        )
        assertTrue(CarPlayVideoOverlayGeometrySchema.size(mapOf(
            "width" to axis("offset", -4.0f), "height" to axis("thatFits", 10.0f),
        )) != null)

        val center = decode(style(ringUuid, "centerAnchor", mapOf("x" to 0.5f, "y" to 1.0f)))
            as CarPlayVideoOverlayAction.SetCenterAnchor
        assertEquals(CarPlayVideoOverlayGeometrySchema.CenterAnchor(0.5, 1.0), center.centerAnchor)

        val affine = mapOf(
            "a" to 1.0f, "b" to 0.25f, "c" to -0.25f,
            "d" to 1.0f, "tx" to 12.0f, "ty" to -5.0f,
        )
        val transform = decode(style(ringUuid, "transform", affine))
            as CarPlayVideoOverlayAction.SetTransform
        assertEquals(CarPlayVideoOverlayGeometrySchema.Transform(1.0, 0.25, -0.25, 1.0, 12.0, -5.0), transform.transform)
    }

    @Test
    fun strictGeometryRejectsWrongAxisEnumsMalformedRealsAndUnknownExtensions() {
        val validOrigin = mapOf("x" to axis("absolutePoint", 1.0f), "y" to axis("absolutePoint", 2.0f))
        val invalidValues = listOf(
            validOrigin + ("extra" to true),
            mapOf("x" to axis("anchorPoint", mapOf("edge" to "top", "offset" to 0.0f)), "y" to axis("absolutePoint", 1.0f)),
            mapOf("x" to axis("absolutePoint", 1.0f), "y" to axis("anchorPoint", mapOf("edge" to "left", "offset" to 0.0f))),
            mapOf("x" to axis("anchorPoint", mapOf("edge" to "leading", "offset" to Float.NaN)), "y" to axis("fractional", 0.0f)),
            mapOf("x" to axis("absolutePoint", Float.POSITIVE_INFINITY), "y" to axis("fractional", 0.0f)),
            mapOf("x" to axis("absolutePoint", 1_000_001.0f), "y" to axis("fractional", 0.0f)),
            mapOf("x" to axis("absolutePoint", 1), "y" to axis("fractional", 0.0f)),
            mapOf("x" to (axis("absolutePoint", 1.0f) + ("extra" to true)), "y" to axis("fractional", 0.0f)),
        )
        invalidValues.forEach { assertOpaque(style(ringUuid, "origin", it)) }
        assertOpaque(style(ringUuid, "size", mapOf(
            "width" to axis("absolute", 5.0f), "height" to axis("fractional", 1.0f),
        )))
        assertOpaque(style(ringUuid, "size", mapOf(
            "width" to axis("absolutePoint", Double.NEGATIVE_INFINITY),
            "height" to axis("fractional", 1.0f),
        )))
        assertOpaque(style(ringUuid, "centerAnchor", mapOf("x" to 1.0f, "y" to Float.NaN)))
        assertOpaque(style(ringUuid, "transform", mapOf(
            "a" to 1.0f, "b" to 0.0f, "c" to 0.0f,
            "d" to 1.0f, "tx" to 0.0f, "ty" to Float.POSITIVE_INFINITY,
        )))
        assertOpaque(style(ringUuid, "transform", mapOf(
            "a" to 1.0f, "b" to 0.0f, "c" to 0.0f,
            "d" to 1.0f, "tx" to 0.0f, "ty" to 0.0f, "extra" to 1,
        )))
    }

    @Test
    fun canonicalAssetImageIsTypedAsMetadataWithoutRequiringAssetRegistration() {
        assertEquals(
            CarPlayVideoOverlayGeometrySchema.AssetReference("icon"),
            CarPlayVideoOverlayGeometrySchema.assetReference(mapOf("identifier" to "icon")),
        )
        assertNull(CarPlayVideoOverlayGeometrySchema.assetReference(mapOf("identifier" to "")))
        assertNull(CarPlayVideoOverlayGeometrySchema.assetReference(mapOf("identifier" to "x".repeat(257))))
        assertNull(CarPlayVideoOverlayGeometrySchema.assetReference(mapOf("identifier" to "icon", "systemNamed" to "car")))
        assertNull(CarPlayVideoOverlayGeometrySchema.assetReference(mapOf("systemNamed" to "car")))
        val canonicalImage = function(
            "createLayer", ringUuid,
            mapOf("type" to "image", "createArguments" to mapOf("identifier" to "icon")),
        )
        val image = decode(canonicalImage) as CarPlayVideoOverlayAction.CreateImageAsset
        assertEquals("icon", image.assetIdentifier)
        val scene = CarPlayVideoOverlaySceneSession()
        assertTrue(scene.apply(image))
        val beforeAsset = scene.snapshot()
        assertEquals(CarPlayVideoOverlaySceneSession.LayerKind.IMAGE, beforeAsset.layers[ringUuid]!!.kind)
        assertEquals("icon", beforeAsset.layers[ringUuid]!!.imageAssetIdentifier)
        assertFalse(beforeAsset.layers[ringUuid]!!.imageAssetPayloadPresent)
        assertTrue(beforeAsset.assets.isEmpty())
        assertFalse(scene.apply(decode(function("updateLayer", ringUuid, mapOf("radius" to 2.0f)))))
        assertEquals(beforeAsset.revision, scene.snapshot().revision)

        val createAsset = function("createAsset", arguments = mapOf(
            "name" to "icon", "data" to byteArrayOf(1),
            "manifest" to mapOf("mimeType" to "image/png"),
        ))
        assertTrue(scene.apply(decode(createAsset)))
        assertTrue(scene.snapshot().assets.containsKey("icon"))
        assertEquals("icon", scene.snapshot().layers[ringUuid]!!.imageAssetIdentifier)
        assertTrue(scene.snapshot().layers[ringUuid]!!.imageAssetPayloadPresent)
        assertTrue(scene.apply(decode(function("removeAsset", arguments = mapOf("name" to "icon")))))
        assertFalse(scene.snapshot().layers[ringUuid]!!.imageAssetPayloadPresent)
        assertEquals("icon", scene.snapshot().layers[ringUuid]!!.imageAssetIdentifier)

        val opaqueAlternatives = listOf(
            mapOf("content" to mapOf("identifier" to "icon")),
            mapOf("identifier" to "icon", "animations" to emptyList<Any>()),
            mapOf("systemNamed" to "car"),
            mapOf("url" to "https://example.invalid/image"),
            mapOf("identifier" to "icon", "future" to true),
            emptyMap<String, Any>(),
        )
        opaqueAlternatives.forEach { createArguments ->
            assertOpaque(function("createLayer", textUuid, mapOf(
                "type" to "image", "createArguments" to createArguments,
            )))
        }
    }

    @Test
    fun geometryStylesCommitInOneRevisionAndLayerSubtypeConflictRollsBack() {
        val scene = CarPlayVideoOverlaySceneSession()
        val createRing = function("createLayer", ringUuid, mapOf(
            "type" to "ring", "createArguments" to mapOf("radius" to 4.0f),
        ))
        val createText = function("createLayer", textUuid, mapOf(
            "type" to "text", "createArguments" to mapOf("text" to "ready"),
        ))
        assertTrue(scene.apply(decode(transaction(createRing, createText))))
        val origin = mapOf(
            "x" to axis("absolutePoint", 3.0f),
            "y" to axis("anchorPoint", mapOf("edge" to "top", "offset" to 2.0f)),
        )
        val size = mapOf("width" to axis("absolutePoint", 12.0f), "height" to axis("thatFits", 8.0f))
        val anchor = mapOf("x" to 0.5f, "y" to 0.5f)
        val transform = mapOf("a" to 1.0f, "b" to 0.0f, "c" to 0.0f, "d" to 1.0f, "tx" to 6.0f, "ty" to 7.0f)
        assertTrue(scene.apply(decode(transaction(
            style(ringUuid, "origin", origin),
            style(ringUuid, "size", size),
            style(ringUuid, "centerAnchor", anchor),
            style(ringUuid, "transform", transform),
        ))))
        val committed = scene.snapshot()
        assertEquals(2, committed.revision)
        assertTrue(committed.layers[ringUuid]!!.origin != null)
        assertTrue(committed.layers[ringUuid]!!.size != null)
        assertTrue(committed.layers[ringUuid]!!.centerAnchor != null)
        assertTrue(committed.layers[ringUuid]!!.transform != null)

        val conflicting = decode(transaction(
            style(ringUuid, "origin", mapOf("x" to axis("fractional", 0.75f), "y" to axis("fractional", 0.25f))),
            function("updateLayer", textUuid, mapOf("radius" to 9.0f)),
        ))
        assertFalse(scene.apply(conflicting))
        assertEquals(committed.revision, scene.snapshot().revision)
        assertEquals(committed.layers[ringUuid]!!.origin, scene.snapshot().layers[ringUuid]!!.origin)

        val unknownChild = decode(transaction(
            style(ringUuid, "origin", origin),
            style(ringUuid, "size", mapOf("width" to axis("future", 1.0f), "height" to axis("fractional", 1.0f))),
        ))
        assertTrue(unknownChild is CarPlayVideoOverlayAction.Opaque)
        assertFalse(scene.apply(unknownChild))
        assertEquals(committed.revision, scene.snapshot().revision)
    }

    private fun decode(fields: Map<String, Any?>): CarPlayVideoOverlayAction =
        codec.decodeFromPhone(BplistCodec.encode(fields))

    private fun assertOpaque(fields: Map<String, Any?>) {
        assertTrue(decode(fields) is CarPlayVideoOverlayAction.Opaque)
    }

    private fun axis(type: String, value: Any): Map<String, Any> = mapOf("type" to type, "value" to value)

    private fun style(uuid: String, key: String, value: Any): Map<String, Any?> =
        function("style", uuid, mapOf("key" to key, "value" to value))

    private fun transaction(vararg children: Map<String, Any?>): Map<String, Any?> = mapOf(
        "type" to "function", "action" to "transaction",
        "arguments" to mapOf("functions" to children.toList()),
    )

    private fun function(
        action: String,
        uuid: String? = null,
        arguments: Map<String, Any?>? = null,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "type" to "function", "action" to action,
    ).apply {
        if (uuid != null) this["objectUUID"] = uuid
        if (arguments != null) this["arguments"] = arguments
    }
}
