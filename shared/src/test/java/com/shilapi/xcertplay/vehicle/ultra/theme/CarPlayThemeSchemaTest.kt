package com.shilapi.xcertplay.vehicle.ultra.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

class CarPlayThemeSchemaTest {
    @Test
    fun parsesMinimalArcPackageAndConfirmedDirectRelationships() {
        val parsed = CarPlayThemeSchema.parseArcPackage(minimalArcPackage())

        assertEquals("com.example.theme", parsed.identifier)
        assertEquals(1u, parsed.version)
        assertEquals(ArcBuildType.RELEASE, parsed.buildType)
        assertNull(parsed.iOSContentVersion)
        assertNull(parsed.vehicleTypes)
        assertNull(parsed.driveModeDisplayNames)
        assertFalse(parsed.driveModeLinkedByDefault)
        assertEquals(10, parsed.minimumCompatibilityVersion)

        val display = parsed.displays.single()
        assertEquals(DisplayType.CLUSTER, display.type)
        assertEquals(2.0, display.density, 0.0)
        assertTrue(display.allowManufacturerAppIcon)
        assertFalse(display.requiresDarkAppearance)

        val layout = display.layouts.single()
        assertEquals(LayoutType.PRIMARY, layout.layoutType)
        val zone = layout.zones.single()
        assertEquals(ZoneTypeCase.SINGLE, zone.zoneType.case)
        assertEquals(ZoneRegion.ZONE_1, zone.zoneRegion)
        val component = zone.components.single()
        assertEquals(ComponentType.PRIMARY, component.type)
        assertEquals(SlotStyleCase.CIRCULAR, component.slots.single().style.case)
    }

    @Test
    fun skipEncodeArcFieldsCanBeAbsentButOrdinaryRequiredFieldsCannot() {
        val parsed = CarPlayThemeSchema.parseArcPackage(minimalArcPackage())
        assertEquals(10, parsed.minimumCompatibilityVersion)

        val accessoryLayout = minimalLayout().toMutableMap().also { it.remove("wallpapers") }
        assertNull(CarPlayThemeSchema.parseLayout(accessoryLayout).wallpapers)

        val missing = minimalArcPackage().toMutableMap().also { it.remove("minimumSDKVersion") }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseArcPackage(missing)
        }
    }

    @Test
    fun preservesUnknownFieldsAndDefensivelyCopiesOpaqueData() {
        val bytes = byteArrayOf(1, 2, 3)
        val vendorMap = linkedMapOf<String, Any?>(
            "blob" to bytes,
            "nested" to mutableListOf<Any?>("first"),
        )
        val input = minimalArcPackage().toMutableMap().also { it["futureVendorField"] = vendorMap }

        val parsed = CarPlayThemeSchema.parseArcPackage(input)
        bytes[0] = 99
        @Suppress("UNCHECKED_CAST")
        (vendorMap.getValue("nested") as MutableList<Any?>).add("mutated")

        val firstRead = parsed.extensions.getValue("futureVendorField").value as Map<*, *>
        assertArrayEquals(byteArrayOf(1, 2, 3), firstRead["blob"] as ByteArray)
        assertEquals(listOf("first"), firstRead["nested"])

        (firstRead["blob"] as ByteArray)[0] = 42
        @Suppress("UNCHECKED_CAST")
        val mutableView = firstRead as MutableMap<String, Any?>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableView["new"] = true
        }

        val secondRead = parsed.extensions.getValue("futureVendorField").value as Map<*, *>
        assertArrayEquals(byteArrayOf(1, 2, 3), secondRead["blob"] as ByteArray)
        assertEquals(listOf("first"), secondRead["nested"])

        @Suppress("UNCHECKED_CAST")
        val displays = parsed.displays as MutableList<Display>
        assertThrows(UnsupportedOperationException::class.java) {
            displays.add(CarPlayThemeSchema.parseDisplay(minimalDisplay()))
        }
    }

    @Test
    fun acceptsOnlyConfirmedStringEnums() {
        ArcBuildType.entries.forEach { expected ->
            val parsed = CarPlayThemeSchema.parseArcPackage(
                minimalArcPackage().toMutableMap().also { it["buildType"] = expected.wireValue },
            )
            assertEquals(expected, parsed.buildType)
        }
        DisplayType.entries.forEach { expected ->
            val parsed = CarPlayThemeSchema.parseDisplay(
                minimalDisplay().toMutableMap().also { it["type"] = expected.wireValue },
            )
            assertEquals(expected, parsed.type)
        }
        LayoutType.entries.forEach { expected ->
            val parsed = CarPlayThemeSchema.parseLayout(
                minimalLayout().toMutableMap().also { it["layoutType"] = expected.wireValue },
            )
            assertEquals(expected, parsed.layoutType)
        }
        ZoneTypeCase.entries.forEach { expected ->
            val parsed = CarPlayThemeSchema.parseZoneType(
                mapOf(expected.wireValue to emptyMap<String, Any?>()),
            )
            assertEquals(expected, parsed.case)
        }
        ZoneRegion.entries.forEach { expected ->
            val parsed = CarPlayThemeSchema.parseZone(
                minimalZone().toMutableMap().also { it["zoneRegion"] = expected.wireValue },
            )
            assertEquals(expected, parsed.zoneRegion)
        }
        ZoneShapeCase.entries.forEach { expected ->
            val payload = when (expected) {
                ZoneShapeCase.CIRCULAR -> emptyMap<String, Any?>()
                ZoneShapeCase.RECTANGULAR -> mapOf("_0" to 12.5)
            }
            val parsed = CarPlayThemeSchema.parseZoneShape(mapOf(expected.wireValue to payload))
            assertEquals(expected, parsed.case)
        }
        ClippingStyleCase.entries.forEach { expected ->
            val payload = if (expected == ClippingStyleCase.CUSTOM) {
                mapOf("mask" to minimalCustomMask())
            } else {
                emptyMap<String, Any?>()
            }
            val parsed = CarPlayThemeSchema.parseClippingStyle(
                mapOf(expected.wireValue to payload),
            )
            assertEquals(expected, parsed.case)
        }
        ComponentType.entries.forEach { expected ->
            val parsed = CarPlayThemeSchema.parseComponent(
                minimalComponent().toMutableMap().also { it["type"] = expected.wireValue },
            )
            assertEquals(expected, parsed.type)
        }

        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseDisplay(
                minimalDisplay().toMutableMap().also { it["type"] = "dashboard" },
            )
        }

        val clippingBehavior = CarPlayThemeSchema.parseClippingBehavior(
            mapOf(
                "transitioning" to mapOf(
                    "custom" to mapOf("mask" to minimalCustomMask()),
                ),
                "notTransitioning" to mapOf("zoneShape" to emptyMap<String, Any?>()),
            ),
        )
        assertEquals(ClippingStyleCase.CUSTOM, clippingBehavior.transitioning.case)
        assertTrue(clippingBehavior.transitioning.customMask != null)
        assertEquals(ClippingStyleCase.ZONE_SHAPE, clippingBehavior.notTransitioning.case)
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseZoneType(mapOf("futureCase" to emptyMap<String, Any?>()))
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseClippingStyle(
                mapOf(
                    "none" to emptyMap<String, Any?>(),
                    "custom" to emptyMap<String, Any?>(),
                ),
            )
        }
    }

    @Test
    fun parsesTypedZoneShapesAndRejectsInvalidAssociatedValues() {
        val circular = CarPlayThemeSchema.parseZoneShape(
            mapOf("circular" to emptyMap<String, Any?>()),
        )
        assertEquals(ZoneShapeCase.CIRCULAR, circular.case)
        assertNull(circular.cornerRadius)

        val rectangular = CarPlayThemeSchema.parseZoneShape(
            mapOf("rectangular" to mapOf("_0" to 18.25)),
        )
        assertEquals(ZoneShapeCase.RECTANGULAR, rectangular.case)
        assertEquals(18.25, rectangular.cornerRadius!!, 0.0)

        val negativeRadius = CarPlayThemeSchema.parseZoneShape(
            mapOf("rectangular" to mapOf("_0" to -1)),
        )
        assertEquals(-1.0, negativeRadius.cornerRadius!!, 0.0)

        listOf(
            mapOf("rectangular" to emptyMap<String, Any?>()),
            mapOf("circular" to mapOf("_0" to 1)),
            mapOf("future" to emptyMap<String, Any?>()),
        ).forEach { invalid ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseZoneShape(invalid)
            }
        }
    }

    @Test
    fun exposesObservedClippingPresetsAndTypedCustomMaskTuple() {
        assertEquals(
            listOf(ClippingStyleCase.ZONE_SHAPE, ClippingStyleCase.ZONE_SHAPE),
            listOf(
                ClippingBehavior.clipToZone.transitioning.case,
                ClippingBehavior.clipToZone.notTransitioning.case,
            ),
        )
        assertEquals(
            listOf(ClippingStyleCase.ZONE_SHAPE, ClippingStyleCase.NONE),
            listOf(
                ClippingBehavior.clipToZoneDuringTransitions.transitioning.case,
                ClippingBehavior.clipToZoneDuringTransitions.notTransitioning.case,
            ),
        )

        val mask = CarPlayThemeSchema.parseCustomMask(minimalCustomMask()).image
        assertEquals(mapOf("opaque" to "image-group"), mask.imageConfiguration.value)
        assertEquals(
            CarPlayLogicalRect(CarPlayLogicalPoint(1.0, 2.0), CarPlayLogicalSize(3.0, 4.0)),
            mask.logicalStartingFrame(),
        )
        assertEquals(
            CarPlayLogicalRect(CarPlayLogicalPoint(5.0, 6.0), CarPlayLogicalSize(7.0, 8.0)),
            mask.logicalEndingFrame(),
        )
        assertEquals(mapOf("opaque" to "animation"), mask.animation.value)
    }

    @Test
    fun keepsLegacyFlatClipsToShapeNullableAndOpaque() {
        assertNull(CarPlayThemeSchema.parseInstrument(minimalInstrument()).clipsToShape)
        assertEquals(
            false,
            CarPlayThemeSchema.parseInstrument(
                minimalInstrument().toMutableMap().also { it["clipsToShape"] = false },
            ).clipsToShape!!.value,
        )
        assertEquals(
            mapOf("unknown" to true),
            CarPlayThemeSchema.parseInstrument(
                minimalInstrument().toMutableMap().also {
                    it["clipsToShape"] = mapOf("unknown" to true)
                },
            ).clipsToShape!!.value,
        )
    }

    @Test
    fun parsesEveryConfirmedInstrumentKindAndValidatesUuidWhenPresent() {
        InstrumentKindCase.entries.forEach { expected ->
            val input = minimalInstrument().toMutableMap().also {
                it["kind"] = mapOf(expected.wireValue to minimalInstrumentKindPayload(expected))
            }
            assertEquals(expected, CarPlayThemeSchema.parseInstrument(input).kind.case)
        }

        val expectedId = UUID.fromString(INSTRUMENT_ID)
        assertEquals(expectedId, CarPlayThemeSchema.parseInstrument(minimalInstrument()).id)
        assertNull(
            CarPlayThemeSchema.parseInstrument(
                minimalInstrument().toMutableMap().also { it.remove("id") },
            ).id,
        )

        listOf("not-a-uuid", "1-1-1-1-1").forEach { invalid ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseInstrument(
                    minimalInstrument().toMutableMap().also { it["id"] = invalid },
                )
            }
        }
    }

    @Test
    fun gaugeInstrumentKindUsesTheConfirmedConfigurationWrapperOnly() {
        val parsed = CarPlayThemeSchema.parseInstrumentKind(
            mapOf("gauge" to mapOf("configuration" to minimalGaugeConfiguration())),
        )
        val gauge = parsed.typedPayload as GaugeInstrumentKindPayload
        assertEquals(GaugeStyleCase.CIRCULAR, gauge.configuration.style.case)
        assertEquals(
            mapOf("configuration" to minimalGaugeConfiguration()),
            parsed.payload.value,
        )

        listOf(
            emptyMap<String, Any?>(),
            minimalGaugeConfiguration(),
            mapOf("_0" to minimalGaugeConfiguration()),
            mapOf("config" to minimalGaugeConfiguration()),
            mapOf("backgroundBlur" to true),
            mapOf("configuration" to minimalGaugeConfiguration(), "unknown" to true),
        ).forEach { wrongPayload ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseInstrumentKind(mapOf("gauge" to wrongPayload))
            }
        }
    }

    @Test
    fun parsesStrictEvidenceBackedInstrumentAssociatedValueTuples() {
        val visibility = linkedMapOf<String, Any?>("futureVisibility" to mutableListOf(1, 2))
        val maskBytes = byteArrayOf(3, 4)
        val inputPayload = linkedMapOf<String, Any?>(
            "_0" to "cluster-video",
            "_1" to visibility,
            "_2" to mapOf(
                "mask" to mapOf("bytes" to maskBytes),
                "clipToBounds" to true,
                "futurePresentationField" to "retained",
            ),
        )
        val inputKind = CarPlayThemeSchema.parseInstrumentKind(
            mapOf("inputStream" to inputPayload),
        )
        val input = inputKind.typedPayload as InputStreamInstrumentKindPayload
        assertEquals("cluster-video", input.streamIdentifier)
        assertTrue(input.presentation!!.clipToBounds)
        assertEquals(
            "retained",
            input.presentation!!.extensions.getValue("futurePresentationField").value,
        )

        @Suppress("UNCHECKED_CAST")
        (visibility.getValue("futureVisibility") as MutableList<Int>).add(99)
        maskBytes[0] = 99
        assertEquals(
            listOf(1, 2),
            (input.visibilityConfiguration!!.value as Map<*, *>)["futureVisibility"],
        )
        assertArrayEquals(
            byteArrayOf(3, 4),
            ((input.presentation!!.mask!!.value as Map<*, *>)["bytes"] as ByteArray),
        )
        assertEquals(inputPayload.keys, (inputKind.payload.value as Map<*, *>).keys)

        val punch = CarPlayThemeSchema.parseInstrumentKind(
            mapOf(
                "punchThrough" to mapOf(
                    "_0" to "vehicle-native-card",
                    "_1" to "speed",
                ),
            ),
        ).typedPayload as PunchThroughInstrumentKindPayload
        assertEquals("vehicle-native-card", punch.reference)
        assertEquals("speed", punch.instrumentDataIdentifier)

        val typedPunch = CarPlayThemeSchema.parseInstrumentKind(
            mapOf(
                "punchThroughType" to mapOf(
                    "_0" to "future-type-case",
                ),
            ),
        ).typedPayload as PunchThroughTypeInstrumentKindPayload
        assertNull(typedPunch.instrumentDataIdentifier)
        assertNull(typedPunch.presentation)
        assertEquals("future-type-case", typedPunch.punchThroughType)

        val omittedInputOptionals = CarPlayThemeSchema.parseInstrumentKind(
            mapOf("inputStream" to mapOf("_0" to "minimal-stream")),
        ).typedPayload as InputStreamInstrumentKindPayload
        assertNull(omittedInputOptionals.visibilityConfiguration)
        assertNull(omittedInputOptionals.presentation)

        val explicitNullOptionals = CarPlayThemeSchema.parseInstrumentKind(
            mapOf(
                "punchThroughType" to mapOf(
                    "_0" to "unknown-raw-value",
                    "_1" to null,
                    "_2" to null,
                ),
            ),
        ).typedPayload as PunchThroughTypeInstrumentKindPayload
        assertNull(explicitNullOptionals.instrumentDataIdentifier)
        assertNull(explicitNullOptionals.presentation)

        @Suppress("UNCHECKED_CAST")
        val mutableVisibilityRead = input.visibilityConfiguration!!.value as MutableMap<String, Any?>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableVisibilityRead["new"] = true
        }
        assertEquals(
            listOf(1, 2),
            (input.visibilityConfiguration!!.value as Map<*, *>)["futureVisibility"],
        )
    }

    @Test
    fun rejectsMalformedOrExtendedInstrumentTupleContainers() {
        listOf(
            mapOf("inputStream" to emptyMap<String, Any?>()),
            mapOf("inputStream" to mapOf("_0" to 7)),
            mapOf("inputStream" to mapOf("_0" to "stream", "_3" to "unknown")),
            mapOf("inputStream" to mapOf("_0" to "stream", "_2" to emptyMap<String, Any?>())),
            mapOf("punchThrough" to mapOf("_0" to "reference")),
            mapOf("punchThrough" to mapOf("_0" to 7, "_1" to "identifier")),
            mapOf("punchThrough" to mapOf("_0" to "reference", "_1" to mapOf("bad" to true))),
            mapOf("punchThrough" to mapOf("_0" to "reference", "_1" to null)),
            mapOf(
                "punchThrough" to mapOf(
                    "_0" to "reference",
                    "_1" to "identifier",
                    "_2" to "unknown",
                ),
            ),
            mapOf("punchThroughType" to emptyMap<String, Any?>()),
            mapOf("punchThroughType" to mapOf("_0" to null)),
            mapOf("punchThroughType" to mapOf("_0" to mapOf("bad" to true))),
            mapOf("punchThroughType" to mapOf("_0" to "type", "_1" to 3)),
            mapOf("punchThroughType" to mapOf("_0" to "opaque", "future" to true)),
        ).forEach { malformed ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseInstrumentKind(malformed)
            }
        }
    }

    @Test
    fun parsesAllThirteenGaugeStyleCasesWithoutClaimingUnsupportedRendering() {
        assertEquals(13, GaugeStyleCase.entries.size)
        GaugeStyleCase.entries.forEach { expected ->
            val gauge = CarPlayThemeSchema.parseGaugeConfiguration(
                minimalGaugeConfiguration(expected),
            )
            assertEquals(expected, gauge.style.case)
            assertTrue(gauge.rendersLocally)
            assertEquals(mapOf("visible" to true), gauge.visibilityConfiguration.value)
            @Suppress("UNCHECKED_CAST")
            val originalPayload = (minimalGaugeConfiguration(expected)["style"] as Map<String, Any?>)
                .getValue(expected.wireValue)
            assertEquals(originalPayload, gauge.style.payload.value)
            when (expected) {
                GaugeStyleCase.CIRCULAR ->
                    assertTrue(gauge.style.typedPayload is CircularGaugeStylePayload)
                GaugeStyleCase.TEXT_ELEMENT ->
                    assertTrue(gauge.style.typedPayload is TextElementGaugeStylePayload)
                GaugeStyleCase.SHAPE_LAYER ->
                    assertTrue(gauge.style.typedPayload is ShapeLayerGaugeStylePayload)
                GaugeStyleCase.IMAGE_GROUP ->
                    assertTrue(gauge.style.typedPayload is ImageGroupGaugeStylePayload)
                GaugeStyleCase.COLOR ->
                    assertTrue(gauge.style.typedPayload is ColorGaugeStylePayload)
                GaugeStyleCase.PROGRESS_BAR ->
                    assertTrue(gauge.style.typedPayload is ProgressBarGaugeStylePayload)
                else -> assertNull(gauge.style.typedPayload)
            }
        }
    }

    @Test
    fun gaugeOutputAffinityUsesRequiredRendersLocallyValueExactly() {
        listOf(true, false).forEach { local ->
            val gauge = CarPlayThemeSchema.parseGaugeConfiguration(
                minimalGaugeConfiguration().toMutableMap().also {
                    it["rendersLocally"] = local
                },
            )
            val expected = if (local) CarPlayThemeInstrumentOutputAffinity.NATIVE
            else CarPlayThemeInstrumentOutputAffinity.PHONE
            assertEquals(local, gauge.rendersLocally)
            assertEquals(expected, gauge.outputAffinity)
            val kind = CarPlayThemeSchema.parseInstrumentKind(mapOf("gauge" to mapOf(
                "configuration" to minimalGaugeConfiguration().toMutableMap().also {
                    it["rendersLocally"] = local
                },
            )))
            assertEquals(expected, kind.knownOutputAffinity)
            assertEquals(local, kind.isVisible(CarPlayThemeLayoutOutput.NATIVE))
            assertEquals(!local, kind.isVisible(CarPlayThemeLayoutOutput.PHONE))
        }
        val unknown = CarPlayThemeSchema.parseInstrumentKind(
            mapOf("navigation" to emptyMap<String, Any?>()),
        )
        assertNull(unknown.knownOutputAffinity)
        assertNull(unknown.isVisible(CarPlayThemeLayoutOutput.ALL))
    }

    @Test
    fun gaugeCircularFieldsAndRangeAreStrictButUnknownNestedValuesStayOpaque() {
        val bytes = byteArrayOf(1, 2, 3)
        val minLabel = linkedMapOf<String, Any?>("futureLabel" to mutableListOf(bytes))
        val circular = minimalCircularGaugeConfiguration().toMutableMap().also {
            it["minLabel"] = minLabel
            it["futureCircularField"] = mapOf("retained" to true)
        }
        val style = mapOf("circular" to mapOf("config" to circular))
        val gauge = CarPlayThemeSchema.parseGaugeConfiguration(
            minimalGaugeConfiguration().toMutableMap().also { it["style"] = style },
        )
        val typed = (gauge.style.typedPayload as CircularGaugeStylePayload).config
        assertEquals(-10.5, typed.range.lowerBound, 0.0)
        assertEquals(100.0, typed.range.upperBound, 0.0)
        assertEquals("speed", typed.dataIdentifier.value)
        assertEquals(mapOf("retained" to true), typed.extensions.getValue("futureCircularField").value)
        bytes[0] = 99
        @Suppress("UNCHECKED_CAST")
        (minLabel.getValue("futureLabel") as MutableList<ByteArray>).add(byteArrayOf(9))
        val frozen = (typed.minLabel.value as Map<*, *>)["futureLabel"] as List<*>
        assertEquals(1, frozen.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), frozen.single() as ByteArray)
        (frozen.single() as ByteArray)[0] = 42
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            ((typed.minLabel.value as Map<*, *>)["futureLabel"] as List<*>).single()
                as ByteArray,
        )
    }

    @Test
    fun gaugeRejectsMissingConfirmedFieldsAndIncorrectAssociatedValueWrappers() {
        listOf("style", "rendersLocally", "visibilityConfiguration").forEach { key ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseGaugeConfiguration(
                    minimalGaugeConfiguration().toMutableMap().also { it.remove(key) },
                )
            }
        }
        val base = minimalGaugeConfiguration()
        val malformedStyles = listOf(
            emptyMap<String, Any?>(),
            mapOf("circular" to emptyMap<String, Any?>()),
            mapOf("circular" to mapOf("configuration" to minimalCircularGaugeConfiguration())),
            mapOf("circular" to mapOf("config" to minimalCircularGaugeConfiguration(), "extra" to 1)),
            mapOf("progressBar" to mapOf("configuration" to emptyMap<String, Any?>())),
            mapOf("textElement" to mapOf("config" to mapOf("dataType" to "speed"))),
            mapOf("textElement" to mapOf("configuration" to emptyMap<String, Any?>())),
            mapOf("shapeLayer" to mapOf("configuration" to emptyMap<String, Any?>())),
            mapOf("imageGroup" to mapOf("configuration" to emptyMap<String, Any?>())),
            mapOf("color" to mapOf("configuration" to emptyMap<String, Any?>())),
            mapOf("color" to mapOf("configuration" to mapOf("colorID" to "speed"))),
            mapOf("color" to mapOf("configuration" to mapOf(
                "colorID" to mapOf("single" to mapOf("value" to "speed")),
                "extra" to true,
            ))),
            mapOf("future" to emptyMap<String, Any?>()),
        )
        malformedStyles.forEach { style ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseGaugeConfiguration(base.toMutableMap().also {
                    it["style"] = style
                })
            }
        }
        minimalCircularGaugeConfiguration().keys.forEach { key ->
            assertThrows("missing $key", CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseGaugeConfiguration(
                    base.toMutableMap().also { gauge ->
                        gauge["style"] = mapOf("circular" to mapOf(
                            "config" to minimalCircularGaugeConfiguration().toMutableMap()
                                .also { it.remove(key) },
                        ))
                    },
                )
            }
        }
    }

    @Test
    fun gaugeClosedRangeRejectsMalformedAndNonFiniteBounds() {
        val invalidRanges = listOf<Any?>(
            emptyList<Double>(),
            listOf(0.0),
            listOf(0.0, 1.0, 2.0),
            listOf(2.0, 1.0),
            listOf("0", 1),
            listOf(Double.NaN, 1.0),
            listOf(0.0, Double.POSITIVE_INFINITY),
            listOf(0.0, BigDecimal("1E9999")),
            null,
        )
        invalidRanges.forEach { range ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseGaugeConfiguration(
                    minimalGaugeConfiguration().toMutableMap().also {
                        it["style"] = mapOf("circular" to mapOf(
                            "config" to minimalCircularGaugeConfiguration().toMutableMap()
                                .also { circular -> circular["range"] = range },
                        ))
                    },
                )
            }
        }
    }

    @Test
    fun gaugeColorIdDynamicEnvelopeIsTypedButColorIdItselfRemainsOpaque() {
        val dynamic = CarPlayThemeSchema.parseGaugeConfiguration(
            minimalGaugeConfiguration(GaugeStyleCase.COLOR).toMutableMap().also {
                it["style"] = mapOf("color" to mapOf("configuration" to mapOf(
                    "colorID" to mapOf("dynamic" to mapOf("value" to mapOf(
                        "lightMode" to mapOf("futureColorCase" to "day"),
                        "darkMode" to mapOf("futureColorCase" to "night"),
                    ))),
                )))
            },
        )
        val color = dynamic.style.typedPayload as ColorGaugeStylePayload
        assertTrue(color.colorID is CarPlayThemeDynamic.Appearance)
        assertEquals(mapOf("futureColorCase" to "day"), color.colorID.resolve(1).value)
        assertEquals(mapOf("futureColorCase" to "night"), color.colorID.resolve(2).value)

        listOf(
            mapOf("dynamic" to mapOf("value" to mapOf("lightMode" to "day"))),
            mapOf("single" to mapOf("wrong" to "day")),
            mapOf("single" to mapOf("value" to null)),
            mapOf("future" to mapOf("value" to "day")),
        ).forEach { invalid ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseGaugeConfiguration(
                    minimalGaugeConfiguration(GaugeStyleCase.COLOR).toMutableMap().also {
                        it["style"] = mapOf("color" to mapOf("configuration" to mapOf(
                            "colorID" to invalid,
                        )))
                    },
                )
            }
        }
    }

    @Test
    fun gaugeShapeAndImageViewsExposeOnlyConfirmedOptionalFields() {
        val shapeConfiguration = linkedMapOf<String, Any?>(
            "pathElements" to listOf(mapOf("unknownPath" to 1)),
            "lineCap" to "future-cap",
            "fillColorIDs" to listOf("fill"),
            "strokeColorIDs" to listOf("stroke"),
            "lineWidth" to 2.5,
            "dataSource" to mapOf("futureSource" to true),
            "unconfirmedField" to 7,
        )
        val shape = CarPlayThemeSchema.parseGaugeConfiguration(
            minimalGaugeConfiguration(GaugeStyleCase.SHAPE_LAYER).toMutableMap().also {
                it["style"] = mapOf("shapeLayer" to mapOf("configuration" to shapeConfiguration))
            },
        ).style.typedPayload as ShapeLayerGaugeStylePayload
        assertEquals(listOf(mapOf("unknownPath" to 1)), shape.pathElements.value)
        assertEquals("future-cap", shape.lineCap!!.value)
        assertEquals(listOf("fill"), shape.fillColorIDs!!.value)
        assertEquals(listOf("stroke"), shape.strokeColorIDs!!.value)
        assertEquals(2.5, shape.lineWidth!!.value)
        assertEquals(mapOf("futureSource" to true), shape.dataSource!!.value)
        assertEquals(7, (shape.configuration.value as Map<*, *>)["unconfirmedField"])

        val imageConfiguration = linkedMapOf<String, Any?>(
            "states" to mapOf("futureState" to "asset"),
            "dataIdentifier" to "gear",
            "criticalStateDataIdentifier" to null,
            "defaultState" to "park",
            "redactedState" to "hidden",
            "dataTransformationType" to mapOf("futureTransformation" to true),
        )
        val image = CarPlayThemeSchema.parseGaugeConfiguration(
            minimalGaugeConfiguration(GaugeStyleCase.IMAGE_GROUP).toMutableMap().also {
                it["style"] = mapOf("imageGroup" to mapOf("configuration" to imageConfiguration))
            },
        ).style.typedPayload as ImageGroupGaugeStylePayload
        assertEquals(mapOf("futureState" to "asset"), image.states.value)
        assertEquals("gear", image.dataIdentifier!!.value)
        assertNull(image.criticalStateDataIdentifier)
        assertEquals("park", image.defaultState!!.value)
        assertEquals("hidden", image.redactedState!!.value)
        assertEquals(mapOf("futureTransformation" to true), image.dataTransformationType!!.value)
    }

    @Test
    fun gaugeInputUsesGlobalDepthNodeAndByteBudgetsBeforeSemanticParsing() {
        val gauge = minimalGaugeConfiguration().toMutableMap().also {
            it["visibilityConfiguration"] = mapOf("nested" to listOf(mapOf("deeper" to true)))
        }
        listOf(
            CarPlayThemeSchemaLimits(maximumNestingDepth = 3),
            CarPlayThemeSchemaLimits(maximumNodeCount = 8),
            CarPlayThemeSchemaLimits(maximumStringBytes = 16),
            CarPlayThemeSchemaLimits(maximumCollectionEntries = 8),
        ).forEach { limits ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseGaugeConfiguration(gauge, limits)
            }
        }
    }

    @Test
    fun parsesSlotStylesAndDestinationOmittedSlotFields() {
        val withoutSkipEncodedFields = CarPlayThemeSchema.parseSlot(minimalSlot())
        assertNull(withoutSkipEncodedFields.id)
        assertNull(withoutSkipEncodedFields.safeAreaInsets)
        assertEquals(SlotStyleCase.CIRCULAR, withoutSkipEncodedFields.style.case)
        assertEquals(3, withoutSkipEncodedFields.zIndex)

        val cornerPayload = linkedMapOf<String, Any?>(
            "mask" to mapOf("opaqueMask" to true),
            "ringRadius" to 42.5,
            "ringCenterPoint" to mapOf("x" to 1, "y" to 2),
            "centralSlotPositionID" to "center",
            "futureCornerField" to byteArrayOf(4, 5),
        )
        val withCorner = minimalSlot().toMutableMap().also {
            it["id"] = INSTRUMENT_ID
            it["style"] = mapOf("corner" to cornerPayload)
            it["safeAreaInsets"] = mapOf("opaque" to true)
            it["instrument"] = minimalInstrument().toMutableMap().also { instrument ->
                instrument.remove("id")
            }
        }
        val parsed = CarPlayThemeSchema.parseSlot(withCorner)
        assertEquals(UUID.fromString(INSTRUMENT_ID), parsed.id)
        assertEquals(SlotStyleCase.CORNER, parsed.style.case)
        assertEquals(42.5, parsed.style.corner!!.ringRadius, 0.0)
        assertEquals("center", parsed.style.corner!!.centralSlotPositionID)
        assertTrue("futureCornerField" in parsed.style.corner!!.extensions)
        assertNull(parsed.instrument!!.id)

        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseSlotStyle(
                mapOf("corner" to cornerPayload.toMutableMap().also { it.remove("mask") }),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseSlotStyle(mapOf("future" to emptyMap<String, Any?>()))
        }
    }

    @Test
    fun parsesInputStreamTransformCasesAndIntegerAlignments() {
        InputStreamTransformKind.entries.forEach { transformKind ->
            InputStreamAlignment.entries.forEach { alignment ->
                val presentation = CarPlayThemeSchema.parseInputStreamPresentation(
                    mapOf(
                        "transform" to mapOf(
                            transformKind.wireValue to mapOf(
                                "alignment" to alignment.wireValue,
                                "frame" to mapOf("opaqueRect" to true),
                            ),
                        ),
                        "clipToBounds" to true,
                    ),
                )
                assertEquals(transformKind, presentation.transform!!.kind)
                assertEquals(alignment, presentation.transform!!.alignment)
                assertTrue(presentation.clipToBounds)
            }
        }

        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseInputStreamPresentation(emptyMap<String, Any?>())
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseInputStreamTransform(
                mapOf("scaleToFit" to mapOf("alignment" to 0)),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseInputStreamTransform(
                mapOf("scaleToFit" to mapOf("alignment" to 1.0)),
            )
        }
    }

    @Test
    fun enforcesUIntAndIntRangesWithoutFloatingPointCoercion() {
        val maximum = minimalArcPackage().toMutableMap().also {
            it["version"] = BigInteger("4294967295")
            it["minimumCompatibilityVersion"] = Int.MIN_VALUE
        }
        val parsed = CarPlayThemeSchema.parseArcPackage(maximum)
        assertEquals(UInt.MAX_VALUE, parsed.version)
        assertEquals(Int.MIN_VALUE, parsed.minimumCompatibilityVersion)

        listOf<Any>(-1, BigInteger("4294967296"), 1.0).forEach { invalid ->
            assertThrows(CarPlayThemeSchemaException::class.java) {
                CarPlayThemeSchema.parseArcPackage(
                    minimalArcPackage().toMutableMap().also { it["version"] = invalid },
                )
            }
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseArcPackage(
                minimalArcPackage().toMutableMap().also {
                    it["minimumCompatibilityVersion"] = BigInteger("2147483648")
                },
            )
        }
    }

    @Test
    fun rejectsNonStringAndDuplicateDictionaryKeys() {
        val nonString = linkedMapOf<Any?, Any?>(1 to "value")
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseArcPackage(nonString)
        }

        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseArcPackage(DuplicateKeyMap())
        }
    }

    @Test
    fun rejectsCyclesAndEveryConfiguredResourceBudget() {
        val cycle = linkedMapOf<String, Any?>()
        cycle["self"] = cycle
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseArcPackage(cycle)
        }

        val cyclicAnimation = linkedMapOf<String, Any?>()
        cyclicAnimation["self"] = cyclicAnimation
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseCustomMask(
                mapOf(
                    "image" to mapOf(
                        "startingFrame" to listOf(listOf(0, 0), listOf(1, 1)),
                        "image" to emptyMap<String, Any?>(),
                        "endingFrame" to listOf(listOf(0, 0), listOf(1, 1)),
                        "animation" to cyclicAnimation,
                    ),
                ),
            )
        }

        val nested = mapOf("none" to mapOf("a" to mapOf("b" to emptyMap<String, Any?>())))
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseClippingStyle(
                nested,
                CarPlayThemeSchemaLimits(maximumNestingDepth = 2),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseClippingStyle(
                mapOf("none" to mapOf("a" to 1)),
                CarPlayThemeSchemaLimits(maximumNodeCount = 2),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseClippingStyle(
                mapOf("none" to mapOf("a" to 1, "b" to 2)),
                CarPlayThemeSchemaLimits(maximumCollectionEntries = 2),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseClippingStyle(
                mapOf("none" to mapOf("longString" to "too long")),
                CarPlayThemeSchemaLimits(maximumStringBytes = 8),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseClippingStyle(
                mapOf("none" to mapOf("blob" to ByteArray(5))),
                CarPlayThemeSchemaLimits(maximumDataBytes = 4),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseClippingStyle(
                mapOf("none" to mapOf("huge" to BigInteger.ONE.shiftLeft(4096))),
                CarPlayThemeSchemaLimits(maximumNumberBytes = 8),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseClippingStyle(
                mapOf("none" to mapOf("scale" to BigDecimal(BigInteger.ONE, Int.MAX_VALUE))),
            )
        }
    }

    @Test
    fun luaLookingStringsRemainInertOpaqueData() {
        val lua = "os.execute('touch /tmp/this-must-never-run')"
        val parsed = CarPlayThemeSchema.parseArcPackage(
            minimalArcPackage().toMutableMap().also {
                it["driveModeDynamicAssignmentConfiguration"] = lua
            },
        )

        assertEquals(lua, parsed.driveModeDynamicAssignmentConfiguration!!.value)
    }

    @Test
    fun rejectsNullRequiredOpaqueValuesAndUnsupportedObjects() {
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseDisplay(
                minimalDisplay().toMutableMap().also { it["size"] = null },
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayThemeSchema.parseArcPackage(
                minimalArcPackage().toMutableMap().also { it["vendor"] = StringBuilder("mutable") },
            )
        }
    }
}

private const val INSTRUMENT_ID = "12345678-1234-5678-9abc-def012345678"

private fun minimalArcPackage(): Map<String, Any?> = linkedMapOf(
    "identifier" to "com.example.theme",
    "version" to 1,
    "minimumSDKVersion" to "27.0",
    "buildType" to "release",
    "partnerID" to "example-partner",
    "driveModeLinkedByDefault" to false,
    "displays" to listOf(minimalDisplay()),
)

private fun minimalDisplay(): Map<String, Any?> = linkedMapOf(
    "type" to "cluster",
    "layouts" to listOf(minimalLayout()),
    "size" to mapOf("width" to 1920, "height" to 720),
    "density" to 2.0,
    "allowManufacturerAppIcon" to true,
    "requiresDarkAppearance" to false,
)

private fun minimalLayout(): Map<String, Any?> = linkedMapOf(
    "id" to "main",
    "zones" to listOf(minimalZone()),
    "layoutType" to "primary",
    "backgroundColor" to mapOf("opaque" to true),
    "safeAreaInsets" to mapOf("opaque" to true),
    "wallpapers" to emptyList<Any?>(),
    "driveModeThemeConfigurations" to emptyMap<String, Any?>(),
)

private fun minimalZone(): Map<String, Any?> = linkedMapOf(
    "id" to "cluster-main",
    "frame" to mapOf("opaque" to true),
    "components" to listOf(minimalComponent()),
    "type" to mapOf("single" to emptyMap<String, Any?>()),
    "zoneRegion" to "zone1",
)

private fun minimalComponent(): Map<String, Any?> = linkedMapOf(
    "id" to "primary-component",
    "type" to "primary",
    "size" to mapOf("opaque" to true),
    "slots" to listOf(minimalSlot()),
)

private fun minimalInstrument(): Map<String, Any?> = linkedMapOf(
    "id" to INSTRUMENT_ID,
    "kind" to mapOf("emptyView" to emptyMap<String, Any?>()),
    "backgroundColor" to mapOf("opaque" to true),
)

private fun minimalInstrumentKindPayload(kind: InstrumentKindCase): Map<String, Any?> = when (kind) {
    InstrumentKindCase.INPUT_STREAM -> mapOf("_0" to "minimal-stream")
    InstrumentKindCase.PUNCH_THROUGH -> mapOf(
        "_0" to "minimal-reference",
        "_1" to "unknown-identifier",
    )
    InstrumentKindCase.PUNCH_THROUGH_TYPE -> mapOf(
        "_0" to "unknown-type",
    )
    InstrumentKindCase.GAUGE -> mapOf("configuration" to minimalGaugeConfiguration())
    InstrumentKindCase.INDICATOR -> mapOf(
        "configuration" to mapOf(
            "dataIdentifier" to "opaque-indicator-identifier",
            "style" to mapOf("message" to emptyMap<String, Any?>()),
            "presentationEdge" to "opaque-edge",
            "containerInsets" to mapOf("opaqueInsets" to true),
        ),
    )
    else -> emptyMap()
}

private fun minimalGaugeConfiguration(
    style: GaugeStyleCase = GaugeStyleCase.CIRCULAR,
): Map<String, Any?> = linkedMapOf(
    "style" to mapOf(style.wireValue to when (style) {
        GaugeStyleCase.CIRCULAR -> mapOf("config" to minimalCircularGaugeConfiguration())
        GaugeStyleCase.TEXT_ELEMENT -> mapOf(
            "configuration" to mapOf("dataType" to "future-data-type"),
        )
        GaugeStyleCase.SHAPE_LAYER -> mapOf(
            "configuration" to mapOf("pathElements" to emptyList<Any?>()),
        )
        GaugeStyleCase.IMAGE_GROUP -> mapOf(
            "configuration" to mapOf("states" to emptyList<Any?>()),
        )
        GaugeStyleCase.COLOR -> mapOf(
            "configuration" to mapOf(
                "colorID" to mapOf("single" to mapOf("value" to "future-color-id")),
            ),
        )
        GaugeStyleCase.PROGRESS_BAR -> mapOf(
            "type" to mapOf("future-progress-bar-type" to mapOf("opaque" to true)),
        )
        else -> mapOf("diagnosticCase" to style.wireValue)
    }),
    "rendersLocally" to true,
    "visibilityConfiguration" to mapOf("visible" to true),
)

private fun minimalCircularGaugeConfiguration(): Map<String, Any?> = linkedMapOf(
    "dataIdentifier" to "speed",
    "range" to listOf(-10.5, 100.0),
    "iconName" to "speed",
    "minLabel" to "0",
    "minLabelColor" to "minimum",
    "maxLabel" to "100",
    "maxLabelColor" to "maximum",
    "tintColors" to listOf("primary"),
    "backgroundTintColors" to listOf("background"),
)

private fun minimalSlot(): Map<String, Any?> = linkedMapOf(
    "style" to mapOf("circular" to emptyMap<String, Any?>()),
    "frame" to mapOf("opaque" to true),
    "zIndex" to 3,
    "positionId" to "center",
)

private fun minimalCustomMask(): Map<String, Any?> = mapOf(
    "image" to mapOf(
        "startingFrame" to listOf(listOf(1, 2), listOf(3, 4)),
        "image" to mapOf("opaque" to "image-group"),
        "endingFrame" to listOf(listOf(5, 6), listOf(7, 8)),
        "animation" to mapOf("opaque" to "animation"),
    ),
)

/** Deliberately violates Map's uniqueness contract so the snapshotter's duplicate guard is tested. */
private class DuplicateKeyMap : Map<String, Any?> {
    private val duplicateEntries = listOf<Map.Entry<String, Any?>>(
        TestEntry("identifier", "first"),
        TestEntry("identifier", "second"),
    )

    override val entries: Set<Map.Entry<String, Any?>>
        get() = object : AbstractSet<Map.Entry<String, Any?>>() {
            override val size: Int = duplicateEntries.size
            override fun iterator(): Iterator<Map.Entry<String, Any?>> = duplicateEntries.iterator()
        }
    override val keys: Set<String> get() = setOf("identifier")
    override val size: Int get() = duplicateEntries.size
    override val values: Collection<Any?> get() = duplicateEntries.map(Map.Entry<String, Any?>::value)
    override fun containsKey(key: String): Boolean = key == "identifier"
    override fun containsValue(value: Any?): Boolean = duplicateEntries.any { it.value == value }
    override fun get(key: String): Any? = duplicateEntries.firstOrNull { it.key == key }?.value
    override fun isEmpty(): Boolean = false
}

private data class TestEntry(
    override val key: String,
    override val value: Any?,
) : Map.Entry<String, Any?>
