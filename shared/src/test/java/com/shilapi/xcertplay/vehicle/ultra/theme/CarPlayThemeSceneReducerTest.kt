package com.shilapi.xcertplay.vehicle.ultra.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayThemeSceneReducerTest {
    @Test
    fun isDisabledByDefaultAndDoesNotInterpretSelections() {
        val result = CarPlayThemeSceneReducer().reduce(
            display = parseDisplay(listOf(layout("main", emptyList()))),
            state = CarPlayThemeSceneState(
                activePrimarySelections = listOf(CarPlayThemePrimarySelection(99, 99, 99)),
            ),
        )

        assertSame(CarPlayThemeSceneReduction.Disabled, result)
    }

    @Test
    fun producesOnlyTheConfirmedStageOrderWithStableSourceOrdering() {
        val primarySlots = listOf(
            slot("late", 5, "inputStream"),
            slot("first-tie", -2, "punchThrough"),
            slot("second-tie", -2, "navigation", mapOf("future" to listOf(1, 2))),
            slot("vacant", 8),
        )
        val overlaySlots = listOf(
            slot("overlay-high", 10, "indicator"),
            slot("overlay-low", 0, "gauge"),
        )
        val firstZone = zone(
            id = "zone-a",
            components = listOf(
                component("primary-inactive", "primary", listOf(slot("unused", 0))),
                component("primary-active", "primary", primarySlots),
                component("overlay-a", "overlay", overlaySlots),
            ),
        )
        val secondZone = zone(
            id = "zone-b",
            components = listOf(component("overlay-b", "overlay", emptyList())),
        )
        val display = parseDisplay(
            listOf(
                layout("layout-a", listOf(firstZone, secondZone)),
                layout("layout-b", emptyList(), layoutType = "underlay"),
            ),
        )

        val result = diagnosticReducer().reduce(
            display,
            CarPlayThemeSceneState(
                activePrimarySelections = listOf(CarPlayThemePrimarySelection(0, 0, 1)),
                trackingOverlayPresent = true,
                appearanceOverlayPresent = true,
            ),
        ) as CarPlayThemeSceneReduction.DiagnosticPlan

        assertEquals(
            CarPlayThemeSceneCoordinateSpace.THEME_LOGICAL_PARENT_DOMAINS_RESOLVED,
            result.scene.coordinateSpace,
        )
        assertEquals(listOf("layout-a", "layout-b"), result.scene.layouts.map { it.address.layoutId })
        assertEquals(LayoutType.UNDERLAY, result.scene.layouts[1].layoutType)

        val plan = result.scene.layouts[0]
        assertEquals(CarPlayThemeSceneStage.entries, plan.stages.map { it.stage })
        assertEquals(1, plan.stage(CarPlayThemeSceneStage.LAYOUT_BACKGROUND).nodes.size)

        val platters = plan.stage(CarPlayThemeSceneStage.ZONE_PLATTER).nodes
            .map { it as CarPlayThemeZonePlatterNode }
        assertEquals(listOf("zone-a", "zone-b"), platters.map { it.zone.zoneId })

        val primary = plan.stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_COMPONENT).nodes
            .single() as CarPlayThemePrimaryComponentNode
        assertEquals("primary-active", primary.component.componentId)

        val primarySlotsInPlan = plan
            .stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER)
            .nodes
            .map { it as CarPlayThemeSlotNode }
        assertEquals(
            listOf("first-tie", "second-tie", "late", "vacant"),
            primarySlotsInPlan.map { it.slot.positionId },
        )
        assertEquals(listOf(1, 2, 0, 3), primarySlotsInPlan.map { it.slot.slotIndex })
        assertTrue(primarySlotsInPlan[0].source is CarPlayThemeSlotSource.PunchThrough)
        assertTrue(primarySlotsInPlan[1].source is CarPlayThemeSlotSource.LocalOpaqueInstrument)
        assertTrue(primarySlotsInPlan[2].source is CarPlayThemeSlotSource.InputStream)
        assertSame(CarPlayThemeSlotSource.Empty, primarySlotsInPlan[3].source)

        val overlayNodes = plan.stage(CarPlayThemeSceneStage.ZONE_OVERLAY_COMPONENT).nodes
        assertEquals(
            listOf("component:overlay-a", "slot:overlay-low", "slot:overlay-high", "component:overlay-b"),
            overlayNodes.map { node ->
                when (node) {
                    is CarPlayThemeOverlayComponentNode -> "component:${node.component.componentId}"
                    is CarPlayThemeSlotNode -> "slot:${node.slot.positionId}"
                    else -> error("unexpected overlay node")
                }
            },
        )
        assertEquals(1, plan.stage(CarPlayThemeSceneStage.TRACKING_OVERLAY).nodes.size)
        assertEquals(1, plan.stage(CarPlayThemeSceneStage.APPEARANCE_OVERLAY).nodes.size)
    }

    @Test
    fun keepsPhoneOutputGaugeAndMessageIndicatorDistinctFromLocalOpaqueSources() {
        fun gauge(local: Boolean) = mapOf("configuration" to mapOf(
            "style" to mapOf("spinner" to emptyMap<String, Any?>()),
            "rendersLocally" to local,
            "visibilityConfiguration" to emptyMap<String, Any?>(),
        ))
        fun indicator(style: IndicatorStyleCase) = mapOf("configuration" to mapOf(
            "dataIdentifier" to "indicator-${style.wireValue}",
            "style" to mapOf(style.wireValue to if (style == IndicatorStyleCase.MESSAGE) {
                emptyMap<String, Any?>()
            } else {
                mapOf("configuration" to emptyMap<String, Any?>())
            }),
            "presentationEdge" to "bottom",
            "containerInsets" to emptyMap<String, Any?>(),
        ))
        val slots = listOf(
            slot("gauge-native", 0, "gauge", gauge(true)),
            slot("gauge-phone", 1, "gauge", gauge(false)),
        ) + IndicatorStyleCase.entries.mapIndexed { index, style ->
            slot("indicator-${style.wireValue}", index + 2, "indicator", indicator(style))
        }
        val display = parseDisplay(listOf(layout("main", listOf(zone("zone", listOf(
            component("primary", "primary", slots),
        ))))))
        val nodes = ((diagnosticReducer().reduce(display, CarPlayThemeSceneState(
            listOf(CarPlayThemePrimarySelection(0, 0, 0)),
        )) as CarPlayThemeSceneReduction.DiagnosticPlan).scene.layouts.single()
            .stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER).nodes
            .map { it as CarPlayThemeSlotNode })

        assertTrue(nodes[0].source is CarPlayThemeSlotSource.LocalOpaqueInstrument)
        assertEquals(CarPlayThemeInstrumentOutputAffinity.NATIVE,
            (nodes[0].source as CarPlayThemeSlotSource.LocalOpaqueInstrument)
                .instrument.knownOutputAffinity)
        assertTrue(nodes[1].source is CarPlayThemeSlotSource.NonNativeUnresolvedInstrument)
        assertEquals(InstrumentKindCase.GAUGE,
            (nodes[1].source as CarPlayThemeSlotSource.NonNativeUnresolvedInstrument).kind)
        IndicatorStyleCase.entries.forEachIndexed { index, style ->
            val source = nodes[index + 2].source
            if (style == IndicatorStyleCase.MESSAGE) {
                assertTrue(source is CarPlayThemeSlotSource.NonNativeUnresolvedInstrument)
                assertEquals(InstrumentKindCase.INDICATOR,
                    (source as CarPlayThemeSlotSource.NonNativeUnresolvedInstrument).kind)
            } else {
                assertTrue(source is CarPlayThemeSlotSource.LocalOpaqueInstrument)
                assertEquals(CarPlayThemeInstrumentOutputAffinity.NATIVE,
                    (source as CarPlayThemeSlotSource.LocalOpaqueInstrument)
                        .instrument.knownOutputAffinity)
            }
        }
    }

    @Test
    fun requiresExplicitPrimarySelectionAndKeepsOverlayIndependent() {
        val display = parseDisplay(
            listOf(
                layout(
                    "main",
                    listOf(
                        zone(
                            "zone",
                            listOf(
                                component("only-primary", "primary", listOf(slot("p", 0))),
                                component("overlay", "overlay", listOf(slot("o", 0))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val plan = (diagnosticReducer().reduce(display) as
            CarPlayThemeSceneReduction.DiagnosticPlan).scene.layouts.single()

        assertTrue(plan.stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_COMPONENT).nodes.isEmpty())
        assertTrue(plan.stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER).nodes.isEmpty())
        assertEquals(2, plan.stage(CarPlayThemeSceneStage.ZONE_OVERLAY_COMPONENT).nodes.size)
    }

    @Test
    fun preservesLogicalGeometryAndOpaqueInstrumentPayloadsWithoutDecoding() {
        val mutableFrame = mutableListOf<Any?>(
            mutableListOf<Any?>(12.5, 7),
            mutableListOf<Any?>(20, 20),
        )
        val navigationPayload = linkedMapOf<String, Any?>(
            "unknownModel" to mutableListOf<Any?>("alpha", byteArrayOf(3, 4)),
        )
        val display = parseDisplay(
            listOf(
                layout(
                    "main",
                    listOf(
                        zone(
                            "zone",
                            listOf(
                                component(
                                    "primary",
                                    "primary",
                                    listOf(slot("local", 0, "navigation", navigationPayload, mutableFrame)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        (mutableFrame[0] as MutableList<Any?>)[0] = 999
        @Suppress("UNCHECKED_CAST")
        (navigationPayload.getValue("unknownModel") as MutableList<Any?>).add("mutated")

        val node = ((diagnosticReducer().reduce(
            display,
            CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))),
        ) as CarPlayThemeSceneReduction.DiagnosticPlan).scene.layouts.single()
            .stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER).nodes.single()
            as CarPlayThemeSlotNode)

        assertEquals(
            CarPlayThemeSceneCoordinateSpace.THEME_LOGICAL_OPAQUE,
            node.logicalFrame.coordinateSpace,
        )
        val frameRead = node.logicalFrame.value as List<*>
        assertEquals(listOf(12.5, 7), frameRead[0])
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (frameRead as MutableList<Any?>).add("densityScaled")
        }

        val source = node.source as CarPlayThemeSlotSource.LocalOpaqueInstrument
        assertEquals(InstrumentKindCase.NAVIGATION, source.kind)
        val payloadRead = source.instrument.kindPayload.value as Map<*, *>
        val unknownModel = payloadRead["unknownModel"] as List<*>
        assertEquals(listOf("alpha", byteArrayOf(3, 4).asList()), normaliseBytes(unknownModel))
    }

    @Test
    fun distinguishesBothPunchThroughVariantsAndPreservesKnownOpaqueLocalKinds() {
        val display = parseDisplay(
            listOf(
                layout(
                    "main",
                    listOf(
                        zone(
                            "zone",
                            listOf(
                                component(
                                    "primary",
                                    "primary",
                                    listOf(
                                        slot("input", 0, "inputStream"),
                                        slot("punch", 1, "punchThrough"),
                                        slot("typed-punch", 2, "punchThroughType"),
                                        slot("menu", 3, "menu", clipsToShape = true),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val nodes = ((diagnosticReducer().reduce(
            display,
            CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))),
        ) as CarPlayThemeSceneReduction.DiagnosticPlan).scene.layouts.single()
            .stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER).nodes
            .map { it as CarPlayThemeSlotNode })

        assertTrue(nodes[0].source is CarPlayThemeSlotSource.InputStream)
        val input = nodes[0].source as CarPlayThemeSlotSource.InputStream
        assertEquals("stream-input", input.streamIdentifier)
        assertEquals(
            "input",
            (input.visibilityConfiguration!!.value as Map<*, *>)["visibility"],
        )
        assertTrue(input.presentation!!.clipToBounds)

        val punch = nodes[1].source as CarPlayThemeSlotSource.PunchThrough.Reference
        assertEquals(
            InstrumentKindCase.PUNCH_THROUGH,
            punch.variant,
        )
        assertEquals("reference-punch", punch.reference)
        assertEquals(
            "punch",
            punch.instrumentDataIdentifier,
        )

        val typedPunch = nodes[2].source as CarPlayThemeSlotSource.PunchThrough.Typed
        assertEquals(
            InstrumentKindCase.PUNCH_THROUGH_TYPE,
            typedPunch.variant,
        )
        assertEquals(
            "typed-punch",
            typedPunch.punchThroughType,
        )
        assertEquals(
            "typed-punch",
            typedPunch.instrumentDataIdentifier,
        )
        assertTrue(typedPunch.presentation!!.clipToBounds)
        assertEquals(
            InstrumentKindCase.MENU,
            (nodes[3].source as CarPlayThemeSlotSource.LocalOpaqueInstrument).kind,
        )
        assertTrue(
            nodes[3].instrumentOuterClip is CarPlayThemeInstrumentOuterClipPlan.SlotShape,
        )
        assertSame(
            nodes[3].style,
            (nodes[3].instrumentOuterClip as CarPlayThemeInstrumentOuterClipPlan.SlotShape).slotStyle,
        )
    }

    @Test
    fun resolvesZoneComponentAndSlotParentCoordinatesWithoutDensityScaling() {
        val display = parseDisplay(
            listOf(
                layout(
                    "main",
                    listOf(
                        zone(
                            id = "zone",
                            frame = rect(10, 20, 100, 60),
                            components = listOf(
                                component(
                                    id = "primary",
                                    type = "primary",
                                    size = listOf(80, 40),
                                    slots = listOf(
                                        slot("local", 0, frame = rect(3, 4, 10, 11)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val plan = (diagnosticReducer().reduce(
            display,
            CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))),
        ) as CarPlayThemeSceneReduction.DiagnosticPlan).scene.layouts.single()

        val zone = plan.stage(CarPlayThemeSceneStage.ZONE_PLATTER).nodes.single()
            as CarPlayThemeZonePlatterNode
        assertEquals(CarPlayThemeSceneGeometryDomain.DISPLAY_LOGICAL, zone.logicalFrame.domain)
        assertEquals(logicalRect(10, 20, 100, 60), zone.frameInDisplayLogicalSpace)
        assertEquals(ZoneShapeCase.RECTANGULAR, zone.zoneShape.case)
        assertEquals(0.0, zone.zoneShape.cornerRadius!!, 0.0)
        assertTrue(zone.zoneShape.defaulted)

        val component = plan.stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_COMPONENT).nodes.single()
            as CarPlayThemePrimaryComponentNode
        assertEquals(CarPlayThemeSceneGeometryDomain.ZONE_LOCAL_LOGICAL, component.logicalSize.domain)
        assertEquals(CarPlayLogicalPoint(0.0, 0.0), component.originInZoneLogicalSpace)
        assertEquals(logicalRect(10, 20, 80, 40), component.frameInDisplayLogicalSpace)

        val slot = plan.stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER).nodes.single()
            as CarPlayThemeSlotNode
        assertEquals(CarPlayThemeSceneGeometryDomain.COMPONENT_LOCAL_LOGICAL, slot.logicalFrame.domain)
        assertEquals(logicalRect(3, 4, 10, 11), slot.frameInComponentLogicalSpace)
        assertEquals(logicalRect(3, 4, 10, 11), slot.frameInZoneLogicalSpace)
        assertEquals(logicalRect(13, 24, 10, 11), slot.frameInDisplayLogicalSpace)
    }

    @Test
    fun selectsClippingBehaviorByTransitionAndUsesTypedZoneShape() {
        val clipping = mapOf(
            "transitioning" to mapOf("zoneShape" to emptyMap<String, Any?>()),
            "notTransitioning" to mapOf("none" to emptyMap<String, Any?>()),
        )
        val display = parseDisplay(
            listOf(
                layout(
                    "main",
                    listOf(
                        zone(
                            "zone",
                            listOf(
                                component(
                                    "primary",
                                    "primary",
                                    listOf(slot("slot", 0)),
                                    clippingBehavior = clipping,
                                ),
                            ),
                            zoneShape = mapOf("circular" to emptyMap<String, Any?>()),
                        ),
                    ),
                ),
            ),
        )

        fun componentClip(transitionActive: Boolean): CarPlayThemeComponentClipPlan {
            val plan = (diagnosticReducer().reduce(
                display,
                CarPlayThemeSceneState(
                    activePrimarySelections = listOf(CarPlayThemePrimarySelection(0, 0, 0)),
                    transitionActive = transitionActive,
                ),
            ) as CarPlayThemeSceneReduction.DiagnosticPlan).scene.layouts.single()
            return (plan.stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_COMPONENT).nodes.single()
                as CarPlayThemePrimaryComponentNode).componentClip
        }

        assertSame(CarPlayThemeComponentClipPlan.None, componentClip(false))
        val transitioning = componentClip(true) as CarPlayThemeComponentClipPlan.ZoneShape
        assertEquals(ZoneShapeCase.CIRCULAR, transitioning.shape.case)
        assertNull(transitioning.shape.cornerRadius)
        assertFalse(transitioning.shape.defaulted)
        assertEquals(logicalRect(10, 20, 100, 60), transitioning.zoneFrameInDisplayLogicalSpace)
    }

    @Test
    fun preservesTypedCustomMaskWithExplicitlyUnknownFrameDomain() {
        val clipping = mapOf(
            "transitioning" to mapOf(
                "custom" to mapOf(
                    "mask" to mapOf(
                        "image" to mapOf(
                            "startingFrame" to rect(-2, 3, 20, 21),
                            "image" to mapOf("group" to "mask-image"),
                            "endingFrame" to rect(4, 5, 30, 31),
                            "animation" to mapOf("curve" to "opaque"),
                        ),
                    ),
                ),
            ),
            "notTransitioning" to mapOf("none" to emptyMap<String, Any?>()),
        )
        val display = parseDisplay(
            listOf(
                layout(
                    "main",
                    listOf(
                        zone(
                            "zone",
                            listOf(
                                component(
                                    "primary",
                                    "primary",
                                    listOf(slot("slot", 0)),
                                    clippingBehavior = clipping,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val plan = (diagnosticReducer().reduce(
            display,
            CarPlayThemeSceneState(
                activePrimarySelections = listOf(CarPlayThemePrimarySelection(0, 0, 0)),
                transitionActive = true,
            ),
        ) as CarPlayThemeSceneReduction.DiagnosticPlan).scene.layouts.single()
        val clip = (plan.stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_COMPONENT).nodes.single()
            as CarPlayThemePrimaryComponentNode).componentClip as CarPlayThemeComponentClipPlan.Custom

        assertEquals(CarPlayThemeCustomMaskFrameDomain.UNKNOWN, clip.mask.frameDomain)
        assertEquals(
            CarPlayThemeSceneGeometryDomain.CUSTOM_MASK_UNKNOWN_LOGICAL,
            clip.mask.startingFrame.domain,
        )
        assertEquals(logicalRect(-2, 3, 20, 21), clip.mask.startingLogicalRect)
        assertEquals(logicalRect(4, 5, 30, 31), clip.mask.endingLogicalRect)
        assertEquals(mapOf("group" to "mask-image"), clip.mask.imageConfiguration.value)
        assertEquals(mapOf("curve" to "opaque"), clip.mask.animation.value)
    }

    @Test
    fun keepsComponentInstrumentAndInputStreamClipsSeparateAndFailsClosedOnAmbiguousCombination() {
        val clipping = mapOf(
            "transitioning" to mapOf("zoneShape" to emptyMap<String, Any?>()),
            "notTransitioning" to mapOf("zoneShape" to emptyMap<String, Any?>()),
        )
        val presentation = mapOf(
            "_0" to "stream",
            "_2" to mapOf(
                "transform" to mapOf("scaleToFit" to mapOf("alignment" to 2)),
                "mask" to mapOf("opaqueMask" to true),
                "clipToBounds" to true,
            ),
        )
        val display = parseDisplay(
            listOf(
                layout(
                    "main",
                    listOf(
                        zone(
                            "zone",
                            listOf(
                                component(
                                    "primary",
                                    "primary",
                                    listOf(
                                        slot(
                                            "stream",
                                            0,
                                            "inputStream",
                                            presentation,
                                            clipsToShape = false,
                                        ),
                                    ),
                                    clippingBehavior = clipping,
                                ),
                            ),
                            zoneShape = mapOf("rectangular" to mapOf("_0" to 9)),
                        ),
                    ),
                ),
            ),
        )

        val scene = (diagnosticReducer().reduce(
            display,
            CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))),
        ) as CarPlayThemeSceneReduction.DiagnosticPlan).scene
        val node = scene.layouts.single()
            .stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER).nodes.single()
            as CarPlayThemeSlotNode

        val componentClip = node.componentClip as CarPlayThemeComponentClipPlan.ZoneShape
        assertEquals(9.0, componentClip.shape.cornerRadius!!, 0.0)
        assertEquals(false, node.instrumentClipsToSlotShape)
        assertSame(CarPlayThemeInstrumentOuterClipPlan.None, node.instrumentOuterClip)
        assertEquals(CarPlayThemeInputStreamCompositionOrder.OPAQUE, node.inputStreamPresentation!!.compositionOrder)
        assertTrue(node.inputStreamPresentation!!.mask != null)
        assertTrue(node.inputStreamPresentation!!.clipToBounds)
        assertFalse(node.inputStreamPresentation!!.supported)
        assertEquals(
            CarPlayThemeSceneUnsupportedReason.INPUT_STREAM_MASK_WITH_CLIP_TO_BOUNDS,
            node.unsupportedReason,
        )
        assertEquals(CarPlayThemeSceneExecutionStatus.UNSUPPORTED_FAIL_CLOSED, scene.executionStatus)
        assertEquals(1, scene.unsupportedDiagnostics.size)
        assertEquals("stream", scene.unsupportedDiagnostics.single().slot.positionId)
    }

    @Test
    fun doesNotInferOuterClipFromOpaqueNestedInstrumentConfiguration() {
        val display = parseDisplay(
            listOf(
                layout(
                    "main",
                    listOf(
                        zone(
                            "zone",
                            listOf(
                                component(
                                    "primary",
                                    "primary",
                                    listOf(
                                        slot(
                                            "instrument",
                                            0,
                                            "navigation",
                                            instrumentConfiguration = mapOf(
                                                "clipsToShape" to false,
                                                "future" to "preserved",
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val node = ((diagnosticReducer().reduce(
            display,
            CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))),
        ) as CarPlayThemeSceneReduction.DiagnosticPlan).scene.layouts.single()
            .stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER).nodes.single()
            as CarPlayThemeSlotNode)

        assertNull(node.instrumentClipsToSlotShape)
        val unknown = node.instrumentOuterClip as
            CarPlayThemeInstrumentOuterClipPlan.UnknownFromInstrumentConfiguration
        assertEquals(
            mapOf("clipsToShape" to false, "future" to "preserved"),
            unknown.opaqueConfiguration!!.value,
        )
        assertNull(unknown.legacyFlatProjection)
        val source = node.source as CarPlayThemeSlotSource.LocalOpaqueInstrument
        assertEquals(
            mapOf("clipsToShape" to false, "future" to "preserved"),
            source.instrument.configuration!!.value,
        )
    }

    @Test
    fun permitsNegativeAndOffParentOriginsAndOnlyResolvesParentTranslation() {
        val display = parseDisplay(
            listOf(
                layout(
                    "main",
                    listOf(
                        zone(
                            "zone",
                            listOf(
                                component(
                                    "primary",
                                    "primary",
                                    listOf(slot("slot", 0, frame = rect(-20, 100, 400, 300))),
                                    size = listOf(300, 200),
                                ),
                            ),
                            frame = rect(-50, 150, 100, 60),
                        ),
                    ),
                ),
            ),
        )

        val plan = (diagnosticReducer().reduce(
            display,
            CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))),
        ) as CarPlayThemeSceneReduction.DiagnosticPlan).scene.layouts.single()
        val component = plan.stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_COMPONENT).nodes.single()
            as CarPlayThemePrimaryComponentNode
        val slot = plan.stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER).nodes.single()
            as CarPlayThemeSlotNode

        assertEquals(logicalRect(-50, 150, 300, 200), component.frameInDisplayLogicalSpace)
        assertEquals(logicalRect(-20, 100, 400, 300), slot.frameInZoneLogicalSpace)
        assertEquals(logicalRect(-70, 250, 400, 300), slot.frameInDisplayLogicalSpace)
    }

    @Test
    fun preservesNegativeExtentsForAnExplicitDownstreamPolicy() {
        val customMask = mapOf(
            "transitioning" to mapOf("none" to emptyMap<String, Any?>()),
            "notTransitioning" to mapOf(
                "custom" to mapOf(
                    "mask" to mapOf(
                        "image" to mapOf(
                            "startingFrame" to rect(0, 0, -1, 1),
                            "image" to emptyMap<String, Any?>(),
                            "endingFrame" to rect(0, 0, 1, -2),
                            "animation" to emptyMap<String, Any?>(),
                        ),
                    ),
                ),
            ),
        )
        val display = parseDisplay(
            layouts = listOf(
                layout(
                    "main",
                    listOf(
                        zone(
                            "zone",
                            listOf(
                                component(
                                    "primary",
                                    "primary",
                                    listOf(slot("slot", 0, frame = rect(-20, 100, -400, -300))),
                                    size = listOf(-300, 200),
                                    clippingBehavior = customMask,
                                ),
                            ),
                            frame = rect(-50, 150, -100, 60),
                        ),
                    ),
                ),
            ),
            size = listOf(-200, 100),
        )

        val plan = (diagnosticReducer().reduce(
            display,
            CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))),
        ) as CarPlayThemeSceneReduction.DiagnosticPlan).scene.layouts.single()
        val zone = plan.stage(CarPlayThemeSceneStage.ZONE_PLATTER).nodes.single()
            as CarPlayThemeZonePlatterNode
        val component = plan.stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_COMPONENT).nodes.single()
            as CarPlayThemePrimaryComponentNode
        val slot = plan.stage(CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER).nodes.single()
            as CarPlayThemeSlotNode
        val clip = component.componentClip as CarPlayThemeComponentClipPlan.Custom

        assertEquals(logicalRect(-50, 150, -100, 60), zone.frameInDisplayLogicalSpace)
        assertEquals(logicalRect(-50, 150, -300, 200), component.frameInDisplayLogicalSpace)
        assertEquals(logicalRect(-70, 250, -400, -300), slot.frameInDisplayLogicalSpace)
        assertEquals(logicalRect(0, 0, -1, 1), clip.mask.startingLogicalRect)
        assertEquals(logicalRect(0, 0, 1, -2), clip.mask.endingLogicalRect)
    }

    @Test
    fun rejectsAmbiguousSelectionsAndEnforcesInputAndOutputBudgets() {
        val display = parseDisplay(
            listOf(
                layout(
                    "main",
                    listOf(
                        zone(
                            "zone",
                            listOf(
                                component("primary", "primary", listOf(slot("slot", 0))),
                                component("overlay", "overlay", emptyList()),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertThrows(CarPlayThemeSceneReductionException::class.java) {
            diagnosticReducer().reduce(
                display,
                CarPlayThemeSceneState(
                    listOf(
                        CarPlayThemePrimarySelection(0, 0, 0),
                        CarPlayThemePrimarySelection(0, 0, 0),
                    ),
                ),
            )
        }
        assertThrows(CarPlayThemeSceneReductionException::class.java) {
            diagnosticReducer().reduce(
                display,
                CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 1))),
            )
        }
        assertThrows(CarPlayThemeSceneReductionException::class.java) {
            CarPlayThemeSceneReducer(
                mode = CarPlayThemeSceneReducerMode.DIAGNOSTIC_ONLY,
                schemaLimits = CarPlayThemeSchemaLimits(maximumNodeCount = 2),
                maximumSceneNodes = 2,
            ).reduce(display)
        }
        assertThrows(CarPlayThemeSceneReductionException::class.java) {
            CarPlayThemeSceneReducer(
                mode = CarPlayThemeSceneReducerMode.DIAGNOSTIC_ONLY,
                maximumSceneNodes = 2,
            ).reduce(
                display,
                CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))),
            )
        }
    }

    @Test
    fun returnsDeeplyReadOnlyPlanCollections() {
        val display = parseDisplay(listOf(layout("main", emptyList())))
        val scene = (diagnosticReducer().reduce(display) as
            CarPlayThemeSceneReduction.DiagnosticPlan).scene

        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (scene.layouts as MutableList<CarPlayThemeLayoutScenePlan>).clear()
        }
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (scene.layouts.single().stages as MutableList<CarPlayThemeSceneStagePlan>).clear()
        }
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (scene.layouts.single()
                .stage(CarPlayThemeSceneStage.LAYOUT_BACKGROUND)
                .nodes as MutableList<CarPlayThemeSceneNode>).clear()
        }
    }

    private fun diagnosticReducer(): CarPlayThemeSceneReducer = CarPlayThemeSceneReducer(
        mode = CarPlayThemeSceneReducerMode.DIAGNOSTIC_ONLY,
    )
}

private fun parseDisplay(
    layouts: List<Map<String, Any?>>,
    size: Any? = listOf(200, 100),
): Display =
    CarPlayThemeSchema.parseDisplay(
        linkedMapOf(
            "id" to "cluster-display",
            "type" to "cluster",
            "layouts" to layouts,
            "size" to size,
            "density" to 2.0,
            "allowManufacturerAppIcon" to true,
            "requiresDarkAppearance" to false,
        ),
    )

private fun layout(
    id: String,
    zones: List<Map<String, Any?>>,
    layoutType: String = "primary",
): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "zones" to zones,
    "layoutType" to layoutType,
    "backgroundColor" to mapOf("opaqueColor" to id),
    "safeAreaInsets" to mapOf("opaqueInsets" to id),
    "driveModeThemeConfigurations" to emptyMap<String, Any?>(),
)

private fun zone(
    id: String,
    components: List<Map<String, Any?>>,
    frame: Any? = listOf(listOf(10, 20), listOf(100, 60)),
    zoneShape: Any? = null,
): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "frame" to frame,
    "components" to components,
    "type" to mapOf("single" to emptyMap<String, Any?>()),
    "platterConfiguration" to mapOf("opaquePlatter" to id),
).also { result ->
    if (zoneShape != null) result["zoneShape"] = zoneShape
}

private fun component(
    id: String,
    type: String,
    slots: List<Map<String, Any?>>,
    size: Any? = listOf(100, 60),
    clippingBehavior: Any? = null,
): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "type" to type,
    "size" to size,
    "slots" to slots,
).also { result ->
    if (clippingBehavior != null) result["clippingBehavior"] = clippingBehavior
}

private fun slot(
    positionId: String,
    zIndex: Int,
    instrumentKind: String? = null,
    kindPayload: Map<String, Any?>? = null,
    frame: Any? = listOf(listOf(0, 0), listOf(20, 20)),
    clipsToShape: Boolean? = null,
    instrumentConfiguration: Any? = null,
): Map<String, Any?> = linkedMapOf<String, Any?>(
    "style" to mapOf("rectangular" to mapOf("opaqueStyle" to positionId)),
    "frame" to frame,
    "zIndex" to zIndex,
    "positionId" to positionId,
).also { result ->
    if (instrumentKind != null) {
        result["instrument"] = linkedMapOf<String, Any?>(
            "kind" to mapOf(
                instrumentKind to (kindPayload ?: sceneInstrumentKindPayload(
                    instrumentKind,
                    positionId,
                )),
            ),
            "backgroundColor" to mapOf("opaqueColor" to instrumentKind),
            "futureInstrumentField" to mapOf("opaque" to true),
        ).also { instrument ->
            if (clipsToShape != null) instrument["clipsToShape"] = clipsToShape
            if (instrumentConfiguration != null) {
                instrument["configuration"] = instrumentConfiguration
            }
        }
    }
}

private fun sceneInstrumentKindPayload(
    instrumentKind: String,
    positionId: String,
): Map<String, Any?> = when (instrumentKind) {
    "inputStream" -> mapOf(
        "_0" to "stream-$positionId",
        "_1" to mapOf("visibility" to positionId),
        "_2" to mapOf("clipToBounds" to true),
    )
    "punchThrough" -> mapOf(
        "_0" to "reference-$positionId",
        "_1" to positionId,
    )
    "punchThroughType" -> mapOf(
        "_0" to positionId,
        "_1" to positionId,
        "_2" to mapOf("clipToBounds" to true),
    )
    "gauge" -> mapOf(
        "configuration" to mapOf(
            "style" to mapOf(
                "circular" to mapOf(
                    "config" to mapOf(
                        "dataIdentifier" to positionId,
                        "range" to listOf(0.0, 100.0),
                        "iconName" to "gauge",
                        "minLabel" to "0",
                        "minLabelColor" to "minimum",
                        "maxLabel" to "100",
                        "maxLabelColor" to "maximum",
                        "tintColors" to emptyList<Any?>(),
                        "backgroundTintColors" to emptyList<Any?>(),
                    ),
                ),
            ),
            "rendersLocally" to true,
            "visibilityConfiguration" to emptyMap<String, Any?>(),
        ),
    )
    "indicator" -> mapOf(
        "configuration" to mapOf(
            "dataIdentifier" to positionId,
            "style" to mapOf("message" to emptyMap<String, Any?>()),
            "presentationEdge" to "opaque-edge",
            "containerInsets" to emptyMap<String, Any?>(),
        ),
    )
    else -> emptyMap()
}

private fun normaliseBytes(values: List<*>): List<Any?> = values.map { value ->
    if (value is ByteArray) value.asList() else value
}

private fun rect(x: Number, y: Number, width: Number, height: Number): List<List<Number>> =
    listOf(listOf(x, y), listOf(width, height))

private fun logicalRect(
    x: Number,
    y: Number,
    width: Number,
    height: Number,
): CarPlayLogicalRect = CarPlayLogicalRect(
    CarPlayLogicalPoint(x.toDouble(), y.toDouble()),
    CarPlayLogicalSize(width.toDouble(), height.toDouble()),
)
