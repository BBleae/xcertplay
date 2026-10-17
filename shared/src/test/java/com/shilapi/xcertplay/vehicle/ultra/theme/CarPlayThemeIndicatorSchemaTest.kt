package com.shilapi.xcertplay.vehicle.ultra.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayThemeIndicatorSchemaTest {
    @Test
    fun projectsAllConfirmedIndicatorStylesWithoutInterpretingTheirConfigurations() {
        IndicatorStyleCase.entries.forEach { styleCase ->
            val input = indicatorConfiguration(styleCase)
            val parsed = CarPlayThemeSchema.parseIndicatorConfiguration(input)

            assertEquals(styleCase, parsed.style.case)
            assertEquals("future-data-id", parsed.dataIdentifier.value)
            assertEquals(mapOf("futureEdge" to "bottom"), parsed.presentationEdge.value)
            assertEquals(mapOf("futureInsets" to listOf(1, 2, 3, 4)), parsed.containerInsets.value)
            assertEquals("future-language-id", parsed.vehicleLanguageIdentifier!!.value)
            assertEquals("retained", parsed.extensions.getValue("futureField").value)
            if (styleCase == IndicatorStyleCase.MESSAGE) {
                assertNull(parsed.style.associatedConfiguration)
                assertEquals(emptyMap<String, Any?>(), parsed.style.payload.value)
            } else {
                assertEquals(
                    mapOf("unmodeled" to listOf(styleCase.wireValue)),
                    parsed.style.associatedConfiguration!!.value,
                )
                assertEquals(
                    mapOf("configuration" to mapOf("unmodeled" to listOf(styleCase.wireValue))),
                    parsed.style.payload.value,
                )
            }
        }
    }

    @Test
    fun indicatorNativePredicateAndLayoutOutputVisibilityFollowConfirmedBits() {
        assertEquals(1, CarPlayThemeLayoutOutput.BACKGROUND.flags)
        assertEquals(2, CarPlayThemeLayoutOutput.PHONE.flags)
        assertEquals(4, CarPlayThemeLayoutOutput.NATIVE.flags)
        assertEquals(7, CarPlayThemeLayoutOutput.ALL.flags)
        assertEquals(CarPlayThemeLayoutOutput.ALL, CarPlayThemeLayoutOutput.fromFlags(7))
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayThemeLayoutOutput.fromFlags(8)
        }

        IndicatorStyleCase.entries.forEach { styleCase ->
            val kindTree = mapOf("indicator" to mapOf(
                "configuration" to indicatorConfiguration(styleCase),
            ))
            val kind = CarPlayThemeSchema.parseInstrumentKind(kindTree)
            val instrument = CarPlayThemeSchema.parseInstrument(mapOf(
                "kind" to kindTree,
                "backgroundColor" to "#00000000",
            ))
            val style = (kind.typedPayload as IndicatorInstrumentKindPayload).configuration.style
            val native = styleCase != IndicatorStyleCase.MESSAGE
            assertEquals(native, style.rendersLocally)
            assertEquals(
                if (native) CarPlayThemeInstrumentOutputAffinity.NATIVE
                else CarPlayThemeInstrumentOutputAffinity.PHONE,
                style.outputAffinity,
            )
            assertEquals(style.outputAffinity, kind.knownOutputAffinity)
            assertEquals(style.outputAffinity, instrument.knownOutputAffinity)
            assertEquals(false, kind.isVisible(CarPlayThemeLayoutOutput.BACKGROUND))
            assertEquals(!native, kind.isVisible(CarPlayThemeLayoutOutput.PHONE))
            assertEquals(native, kind.isVisible(CarPlayThemeLayoutOutput.NATIVE))
            assertEquals(true, kind.isVisible(CarPlayThemeLayoutOutput.ALL))
            assertEquals(native, instrument.isVisible(CarPlayThemeLayoutOutput.NATIVE))
            assertEquals(!native, instrument.isVisible(CarPlayThemeLayoutOutput.PHONE))
            assertEquals(true, kind.isVisible(CarPlayThemeLayoutOutput.fromFlags(
                CarPlayThemeLayoutOutput.BACKGROUND.flags or
                    if (native) CarPlayThemeLayoutOutput.NATIVE.flags
                    else CarPlayThemeLayoutOutput.PHONE.flags,
            )))
        }
    }

    @Test
    fun indicatorKindRequiresTheObservedConfigurationEnvelopeAndRoundTripsOpaqueTree() {
        val source = indicatorConfiguration(IndicatorStyleCase.CRITICAL)
        val kind = CarPlayThemeSchema.parseInstrumentKind(
            mapOf("indicator" to mapOf("configuration" to source)),
        )
        val projection = kind.typedPayload as IndicatorInstrumentKindPayload
        assertEquals(IndicatorStyleCase.CRITICAL, projection.configuration.style.case)
        assertEquals(
            mapOf("configuration" to source),
            kind.payload.value,
        )

        val reparsed = CarPlayThemeSchema.parseInstrumentKind(
            mapOf("indicator" to kind.payload.value),
        )
        assertEquals(kind.case, reparsed.case)
        assertEquals(kind.payload.value, reparsed.payload.value)
        assertEquals(
            projection.configuration.style.associatedConfiguration!!.value,
            (reparsed.typedPayload as IndicatorInstrumentKindPayload)
                .configuration.style.associatedConfiguration!!.value,
        )
    }

    @Test
    fun indicatorUnknownFieldsRemainFrozenAndNeverBecomeExecutableValues() {
        val bytes = byteArrayOf(1, 2, 3)
        val nested = linkedMapOf<String, Any?>("unknownData" to bytes)
        val source = indicatorConfiguration(IndicatorStyleCase.SELECTOR).toMutableMap().also {
            it["dataIdentifier"] = nested
            it["style"] = mapOf(
                "selector" to mapOf("configuration" to mapOf("vendor" to nested)),
            )
        }

        val parsed = CarPlayThemeSchema.parseIndicatorConfiguration(source)
        bytes[0] = 99
        @Suppress("UNCHECKED_CAST")
        val first = parsed.dataIdentifier.value as MutableMap<String, Any?>
        assertArrayEquals(byteArrayOf(1, 2, 3), first["unknownData"] as ByteArray)
        (first["unknownData"] as ByteArray)[0] = 42
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            (parsed.dataIdentifier.value as Map<*, *>)["unknownData"] as ByteArray,
        )
        assertThrows(UnsupportedOperationException::class.java) {
            first["new"] = true
        }
        assertTrue(parsed.style.associatedConfiguration!!.value is Map<*, *>)
    }

    @Test
    fun rejectsMissingNullMalformedAndUnknownIndicatorEnvelopes() {
        val valid = indicatorConfiguration(IndicatorStyleCase.MESSAGE)
        listOf(
            emptyMap<String, Any?>(),
            valid,
            mapOf("_0" to valid),
            mapOf("configuration" to valid, "unknown" to true),
            mapOf("configuration" to null),
        ).forEach { malformed ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseInstrumentKind(mapOf("indicator" to malformed))
            }
        }

        listOf("dataIdentifier", "style", "presentationEdge", "containerInsets").forEach { key ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseIndicatorConfiguration(valid.toMutableMap().also {
                    it.remove(key)
                })
            }
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseIndicatorConfiguration(valid.toMutableMap().also {
                    it[key] = null
                })
            }
        }

        listOf(
            "unexpected",
            mapOf("future" to emptyMap<String, Any?>()),
            mapOf("message" to emptyMap<String, Any?>(), "critical" to emptyMap<String, Any?>()),
            mapOf("message" to mapOf("configuration" to emptyMap<String, Any?>())),
            mapOf("critical" to emptyMap<String, Any?>()),
            mapOf("critical" to mapOf("configuration" to null)),
            mapOf("critical" to mapOf("configuration" to "not-a-dictionary")),
            mapOf("animatedSymbol" to mapOf("configuration" to emptyMap<String, Any?>(), "extra" to true)),
            mapOf("selector" to mapOf("_0" to emptyMap<String, Any?>())),
        ).forEach { malformed ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseIndicatorConfiguration(valid.toMutableMap().also {
                    it["style"] = malformed
                })
            }
        }

        assertNull(
            CarPlayThemeSchema.parseIndicatorConfiguration(
                valid.toMutableMap().also { it["vehicleLanguageIdentifier"] = null },
            ).vehicleLanguageIdentifier,
        )
    }

    @Test
    fun indicatorProjectionUsesTheGlobalSnapshotBudgets() {
        val deep = indicatorConfiguration(IndicatorStyleCase.CRITICAL).toMutableMap().also {
            it["futureField"] = mapOf("nested" to listOf(mapOf("deeper" to true)))
        }
        listOf(
            CarPlayThemeSchemaLimits(maximumNestingDepth = 3),
            CarPlayThemeSchemaLimits(maximumNodeCount = 8),
            CarPlayThemeSchemaLimits(maximumCollectionEntries = 8),
            CarPlayThemeSchemaLimits(maximumStringBytes = 16),
        ).forEach { limits ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseIndicatorConfiguration(deep, limits)
            }
        }
    }
}

private fun indicatorConfiguration(styleCase: IndicatorStyleCase): Map<String, Any?> = linkedMapOf(
    "dataIdentifier" to "future-data-id",
    "style" to mapOf(styleCase.wireValue to when (styleCase) {
        IndicatorStyleCase.MESSAGE -> emptyMap<String, Any?>()
        else -> mapOf("configuration" to mapOf("unmodeled" to listOf(styleCase.wireValue)))
    }),
    "presentationEdge" to mapOf("futureEdge" to "bottom"),
    "containerInsets" to mapOf("futureInsets" to listOf(1, 2, 3, 4)),
    "vehicleLanguageIdentifier" to "future-language-id",
    "futureField" to "retained",
)
