package com.shilapi.xcertplay.airplay.rcs.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayVideoSettingsPropertySchemaTest {
    private val schema = CarPlayVideoSettingsPropertySchema

    @Test
    fun projectsEachObservedPropertyTypeWithoutRestrictingUnknownNumbers() {
        val response = CarPlayVideoSettingsMessage.PropertiesResponse(
            21L,
            linkedMapOf(
                schema.DND_BUTTON_STATE to 1234L,
                schema.CLOSED_CAPTIONS_ENABLED to -2,
                schema.SELECTED_CAPTION_STYLE to "com.apple.caption-style",
                schema.CUSTOM_CAPTION_STYLES to listOf(emptyMap<String, Any>()),
            ),
        )
        val values = schema.project(response)

        assertEquals(
            1234L,
            (values[schema.DND_BUTTON_STATE] as
                CarPlayVideoSettingsPropertyValue.DndButtonState).number.raw,
        )
        assertTrue(
            (values[schema.CLOSED_CAPTIONS_ENABLED] as
                CarPlayVideoSettingsPropertyValue.ClosedCaptionsEnabled).enabled,
        )
        assertEquals(
            "com.apple.caption-style",
            (values[schema.SELECTED_CAPTION_STYLE] as
                CarPlayVideoSettingsPropertyValue.SelectedCaptionStyle).style,
        )
        assertTrue(
            (values[schema.CUSTOM_CAPTION_STYLES] as
                CarPlayVideoSettingsPropertyValue.CustomCaptionStyles).styles.single().knownFields.isEmpty(),
        )
    }

    @Test
    fun projectsValuesFromAValidatedPhonePropertyResponse() {
        val codec = CarPlayVideoSettingsCodec()
        val wire = codec.encodeFromPhone(
            CarPlayVideoSettingsMessage.PropertiesResponse(
                7L,
                mapOf(
                    schema.CLOSED_CAPTIONS_ENABLED to 2,
                    schema.CUSTOM_CAPTION_STYLES to listOf(mapOf("uniqueId" to "custom")),
                    "future" to byteArrayOf(4),
                ),
            ),
        )
        val decoded = codec.decodeFromPhone(wire) as
            CarPlayVideoSettingsMessage.PropertiesResponse
        val values = schema.project(decoded)

        assertTrue((values[schema.CLOSED_CAPTIONS_ENABLED] as
            CarPlayVideoSettingsPropertyValue.ClosedCaptionsEnabled).enabled)
        val style = (values[schema.CUSTOM_CAPTION_STYLES] as
            CarPlayVideoSettingsPropertyValue.CustomCaptionStyles).styles.single()
        assertEquals("custom", (style.knownFields[CarPlayVideoCustomStyleField.UNIQUE_ID] as
            CarPlayVideoCustomStyleValue.Text).value)
        assertArrayEquals(byteArrayOf(4), (values["future"] as
            CarPlayVideoSettingsPropertyValue.Opaque).raw as ByteArray)
    }

    @Test
    fun captionsEnabledUsesNSNumberBoolValueRatherThanZeroOrOneValidation() {
        val falseValues = listOf(false, 0, 0L, -0.0)
        val trueValues = listOf(true, 2, -1L, 0.25)
        falseValues.forEach { raw ->
            val projected = schema.project(mapOf(schema.CLOSED_CAPTIONS_ENABLED to raw))
            val field = projected[schema.CLOSED_CAPTIONS_ENABLED] as
                CarPlayVideoSettingsPropertyValue.ClosedCaptionsEnabled
            assertFalse(field.enabled)
            assertEquals(raw, field.number.raw)
        }
        trueValues.forEach { raw ->
            val projected = schema.project(mapOf(schema.CLOSED_CAPTIONS_ENABLED to raw))
            val field = projected[schema.CLOSED_CAPTIONS_ENABLED] as
                CarPlayVideoSettingsPropertyValue.ClosedCaptionsEnabled
            assertTrue(field.enabled)
            assertEquals(raw, field.number.raw)
        }
    }

    @Test
    fun customStylesTypeAllNineteenOptionalFieldsAndKeepUnknownFields() {
        val style = linkedMapOf<String, Any>()
        CarPlayVideoCustomStyleField.entries.forEach { field ->
            style[field.wireKey] = when (field.kind) {
                CarPlayVideoCustomStyleField.Kind.TEXT -> field.wireKey
                CarPlayVideoCustomStyleField.Kind.NUMBER -> -9000L
                CarPlayVideoCustomStyleField.Kind.BOXED_BOOLEAN -> true
            }
        }
        style["futureBlob"] = byteArrayOf(5, 6)
        val projected = schema.project(mapOf(schema.CUSTOM_CAPTION_STYLES to listOf(style)))
        val custom = (projected[schema.CUSTOM_CAPTION_STYLES] as
            CarPlayVideoSettingsPropertyValue.CustomCaptionStyles).styles.single()

        assertEquals(19, CarPlayVideoCustomStyleField.entries.size)
        assertEquals(19, custom.knownFields.size)
        CarPlayVideoCustomStyleField.entries.forEach { field ->
            val value = custom.knownFields[field]
            when (field.kind) {
                CarPlayVideoCustomStyleField.Kind.TEXT ->
                    assertEquals(field.wireKey, (value as CarPlayVideoCustomStyleValue.Text).value)
                CarPlayVideoCustomStyleField.Kind.NUMBER ->
                    assertEquals(-9000L, (value as CarPlayVideoCustomStyleValue.Number).number.raw)
                CarPlayVideoCustomStyleField.Kind.BOXED_BOOLEAN -> {
                    value as CarPlayVideoCustomStyleValue.BoxedBoolean
                    assertTrue(value.value)
                }
            }
        }
        assertArrayEquals(byteArrayOf(5, 6), custom.rawFields["futureBlob"] as ByteArray)
    }

    @Test
    fun optionalCustomStyleFieldsKeepBadlyTypedKnownFieldsOpaque() {
        val style = linkedMapOf<String, Any>(
            "uniqueId" to "my-style",
            "size" to "very large",
            "font" to true,
            "videoShouldOverrideFont" to byteArrayOf(9),
            "videoShouldOverrideSize" to 2,
            "newField" to mapOf("nested" to listOf(1, 2)),
        )
        val projected = schema.project(mapOf(schema.CUSTOM_CAPTION_STYLES to listOf(style)))
        val custom = (projected[schema.CUSTOM_CAPTION_STYLES] as
            CarPlayVideoSettingsPropertyValue.CustomCaptionStyles).styles.single()

        assertEquals("my-style", (custom.knownFields[CarPlayVideoCustomStyleField.UNIQUE_ID] as
            CarPlayVideoCustomStyleValue.Text).value)
        assertNull(custom.knownFields[CarPlayVideoCustomStyleField.SIZE])
        assertNull(custom.knownFields[CarPlayVideoCustomStyleField.FONT])
        assertNull(custom.knownFields[CarPlayVideoCustomStyleField.VIDEO_SHOULD_OVERRIDE_FONT])
        assertNull(custom.knownFields[CarPlayVideoCustomStyleField.VIDEO_SHOULD_OVERRIDE_SIZE])
        assertEquals("very large", custom.rawFields["size"])
        assertEquals(true, custom.rawFields["font"])
        assertArrayEquals(byteArrayOf(9), custom.rawFields["videoShouldOverrideFont"] as ByteArray)
        assertEquals(2, custom.rawFields["videoShouldOverrideSize"])
        assertEquals(mapOf("nested" to listOf(1, 2)), custom.rawFields["newField"])
    }

    @Test
    fun initialRelayHasOnlyTheTwoObservedOptionalPropertyNames() {
        assertEquals(
            setOf(schema.CLOSED_CAPTIONS_ENABLED, schema.SELECTED_CAPTION_STYLE),
            schema.initialRelayKnownPropertyNames,
        )
        val initial = schema.project(
            mapOf(
                schema.CLOSED_CAPTIONS_ENABLED to true,
                schema.SELECTED_CAPTION_STYLE to "style-id",
            ),
        )
        assertEquals(schema.initialRelayKnownPropertyNames, initial.values.keys)

        val absentStyle = schema.project(mapOf(schema.CLOSED_CAPTIONS_ENABLED to false))
        assertNull(absentStyle[schema.SELECTED_CAPTION_STYLE])
        assertEquals(1, absentStyle.values.size)
    }

    @Test
    fun wrongKnownTypesAndUnknownPropertiesRemainOpaque() {
        val values = schema.project(
            linkedMapOf(
                schema.DND_BUTTON_STATE to "active",
                schema.CLOSED_CAPTIONS_ENABLED to "yes",
                schema.SELECTED_CAPTION_STYLE to 12,
                schema.CUSTOM_CAPTION_STYLES to listOf(mapOf("uniqueId" to "a"), "bad entry"),
                "future_property" to mapOf("payload" to byteArrayOf(3, 4)),
            ),
        )
        assertEquals("active", (values[schema.DND_BUTTON_STATE] as
            CarPlayVideoSettingsPropertyValue.Opaque).raw)
        assertEquals("yes", (values[schema.CLOSED_CAPTIONS_ENABLED] as
            CarPlayVideoSettingsPropertyValue.Opaque).raw)
        assertEquals(12, (values[schema.SELECTED_CAPTION_STYLE] as
            CarPlayVideoSettingsPropertyValue.Opaque).raw)
        assertTrue(values[schema.CUSTOM_CAPTION_STYLES] is CarPlayVideoSettingsPropertyValue.Opaque)
        val future = (values["future_property"] as CarPlayVideoSettingsPropertyValue.Opaque).raw
        assertArrayEquals(byteArrayOf(3, 4), (future as Map<*, *>)["payload"] as ByteArray)
    }

    @Test
    fun snapshotAndOpaqueAccessorsDefensivelyCopyMutableValues() {
        val sourceBytes = byteArrayOf(1, 2)
        val sourceStyle = linkedMapOf<String, Any>("extra" to sourceBytes)
        val source = linkedMapOf<String, Any>(
            schema.CUSTOM_CAPTION_STYLES to mutableListOf(sourceStyle),
            "future" to mutableListOf(sourceBytes),
        )
        val snapshot = schema.project(source)
        sourceBytes[0] = 9
        sourceStyle["extra"] = byteArrayOf(7)
        source["future"] = "changed"

        val custom = (snapshot[schema.CUSTOM_CAPTION_STYLES] as
            CarPlayVideoSettingsPropertyValue.CustomCaptionStyles).styles.single()
        val firstRead = custom.rawFields["extra"] as ByteArray
        assertArrayEquals(byteArrayOf(1, 2), firstRead)
        firstRead[0] = 8
        assertArrayEquals(byteArrayOf(1, 2), custom.rawFields["extra"] as ByteArray)

        val opaque = snapshot["future"] as CarPlayVideoSettingsPropertyValue.Opaque
        val firstOpaqueRead = opaque.raw as List<*>
        (firstOpaqueRead.single() as ByteArray)[0] = 8
        assertArrayEquals(byteArrayOf(1, 2), (opaque.raw as List<*>).single() as ByteArray)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.values as MutableMap<String, CarPlayVideoSettingsPropertyValue>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (custom.knownFields as MutableMap<CarPlayVideoCustomStyleField, CarPlayVideoCustomStyleValue>)
                .clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            ((snapshot[schema.CUSTOM_CAPTION_STYLES] as
                CarPlayVideoSettingsPropertyValue.CustomCaptionStyles).styles as
                MutableList<CarPlayVideoCustomCaptionStyle>).clear()
        }
    }

    @Test
    fun setterCanBeInspectedWithoutGeneratingAnyReply() {
        val request = CarPlayVideoSettingsMessage.SetProperty(schema.CLOSED_CAPTIONS_ENABLED, 3)
        val projected = schema.project(request) as
            CarPlayVideoSettingsPropertyValue.ClosedCaptionsEnabled
        assertTrue(projected.enabled)
        assertEquals(3, projected.number.raw)
    }

    @Test
    fun rawInputRetainsTheCodecBounds() {
        val limits = CarPlayVideoSettingsLimits(maxCollectionEntries = 1)
        assertThrows(CarPlayVideoSettingsProtocolException::class.java) {
            schema.project(mapOf("a" to 1, "b" to 2), limits)
        }
        assertThrows(CarPlayVideoSettingsProtocolException::class.java) {
            schema.project(mapOf("a" to null))
        }
    }
}
