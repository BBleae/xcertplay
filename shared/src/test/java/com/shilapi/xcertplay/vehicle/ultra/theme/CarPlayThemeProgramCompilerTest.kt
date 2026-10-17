package com.shilapi.xcertplay.vehicle.ultra.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayThemeProgramCompilerTest {
    @Test
    fun canonicalFingerprintIsStableAcrossEquivalentParsedInputsAndDefensive() {
        val first = compiler().compile(display(zoneFrame = rect(0.0, 20, 100, 60)))
        val second = compiler().compile(display(zoneFrame = rect(-0.0, 20, 100, 60)))

        assertEquals(first.structuralProgram.structuralFingerprint,
            second.structuralProgram.structuralFingerprint)
        assertEquals(64, first.structuralProgram.structuralFingerprint.hex().length)
        val returnedBytes = first.structuralProgram.structuralFingerprint.bytes()
        returnedBytes[0] = (returnedBytes[0].toInt() xor 0xff).toByte()
        assertEquals(first.structuralProgram.structuralFingerprint,
            second.structuralProgram.structuralFingerprint)
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (first.structuralProgram.instructions as MutableList<CarPlayThemeProgramInstruction>).clear()
        }
        assertFalse(first.rendererReady)
        assertTrue(first.issues.any { it.code == CarPlayThemeProgramIssueCode.OPAQUE_LAYOUT_BACKGROUND })
    }

    @Test
    fun confirmedLayoutBackgroundCompilesAsExecutableColorInstruction() {
        val background = compiler().compile(display(backgroundColor = "#1aF8"))
        assertTrue(background.structuralProgram.instructions.none {
            it is CarPlayThemeProgramInstruction.FillLayoutBackground
        })
        assertTrue(background.issues.any {
            it.code == CarPlayThemeProgramIssueCode.OPAQUE_LAYOUT_BACKGROUND &&
                it.path == "layouts[0].backgroundColor"
        })

        val compiled = compiler().compile(display(backgroundColor = "#1aF"))
        val fill = compiled.structuralProgram.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>().single()
        assertEquals(CarPlayThemeSceneStage.LAYOUT_BACKGROUND, fill.stage)
        assertEquals(17.0 / 255.0, fill.color.red, 0.0)
        assertEquals(170.0 / 255.0, fill.color.green, 0.0)
        assertEquals(1.0, fill.color.blue, 0.0)
        assertEquals(1.0, fill.color.alpha, 0.0)
        assertFalse(compiled.issues.any { it.code == CarPlayThemeProgramIssueCode.OPAQUE_LAYOUT_BACKGROUND })
        assertFalse(compiled.rendererReady)
    }

    @Test
    fun layoutBackgroundColorParticipatesInCanonicalFingerprint() {
        val red = compiler().compile(display(backgroundColor = "#ff0000"))
        val sameRed = compiler().compile(display(backgroundColor = listOf(1, 0, 0, 1)))
        val halfRed = compiler().compile(display(backgroundColor = "#ff000080"))
        val blue = compiler().compile(display(backgroundColor = "#0000ff"))
        assertEquals(red.structuralProgram.structuralFingerprint,
            sameRed.structuralProgram.structuralFingerprint)
        assertNotEquals(red.structuralProgram.structuralFingerprint,
            halfRed.structuralProgram.structuralFingerprint)
        assertNotEquals(red.structuralProgram.structuralFingerprint,
            blue.structuralProgram.structuralFingerprint)
        assertFalse(red.rendererReady)
        assertFalse(halfRed.rendererReady)
    }

    @Test
    fun mapsConfirmedGeometryAndSourceWithoutAndroidDensityScaling() {
        val compilation = compiler().compile(
            display(
                zoneFrame = rect(10, 20, 100, 60),
                components = listOf(component("primary", slots = listOf(
                    slot("input", 3, rect(3, 4, 10, 11), instrument("inputStream",
                        mapOf("_0" to "camera-front")),
                    ),
                ))),
            ),
            CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))),
        )

        val program = compilation.structuralProgram
        assertEquals(CarPlayLogicalSize(400.0, 200.0), program.logicalSize)
        assertEquals(3.0, program.assetDensity, 0.0)
        assertEquals(
            listOf(
                CarPlayThemeSceneStage.LAYOUT_BACKGROUND,
                CarPlayThemeSceneStage.ZONE_PLATTER,
                CarPlayThemeSceneStage.ACTIVE_PRIMARY_COMPONENT,
                CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER,
            ),
            program.instructions.map { it.stage },
        )
        val zone = program.instructions[1] as CarPlayThemeProgramInstruction.Zone
        assertEquals(CarPlayLogicalRect(CarPlayLogicalPoint(10.0, 20.0),
            CarPlayLogicalSize(100.0, 60.0)), zone.frameInDisplayLogicalSpace)
        assertEquals(ZoneTypeCase.SINGLE, zone.type)
        val slot = program.instructions.last() as CarPlayThemeProgramInstruction.Slot
        assertEquals(CarPlayLogicalRect(CarPlayLogicalPoint(3.0, 4.0),
            CarPlayLogicalSize(10.0, 11.0)), slot.frameInComponentLogicalSpace)
        assertEquals(CarPlayLogicalRect(CarPlayLogicalPoint(13.0, 24.0),
            CarPlayLogicalSize(10.0, 11.0)), slot.frameInDisplayLogicalSpace)
        assertEquals(CarPlayThemeStructuralSource.InputStream("camera-front"), slot.source)
        assertTrue(compilation.issues.any {
            it.code == CarPlayThemeProgramIssueCode.OPAQUE_INSTRUMENT_CONTENT &&
                it.path.endsWith("slots[0]")
        })
    }

    @Test
    fun rejectsOpaqueActiveNodesButDoesNotExecuteInactivePrimary() {
        val inactive = component("inactive", slots = listOf(slot("bad", 0, rect(0, 0, 4, 4),
            instrument("navigation", emptyMap()))))
        val active = component("active", slots = listOf(slot("local", 0, rect(0, 0, 4, 4),
            instrument("navigation", emptyMap()))))
        val overlay = component("overlay", type = "overlay", slots = listOf(slot("none", 0,
            rect(0, 0, 4, 4), null, extra = mapOf("futureBehavior" to true))))
        val compilation = compiler().compile(
            display(components = listOf(inactive, active, overlay)),
            CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 1))),
        )

        assertTrue(compilation.issues.any {
            it.code == CarPlayThemeProgramIssueCode.OPAQUE_INSTRUMENT_CONTENT &&
                it.path.contains("components[1]")
        })
        assertTrue(compilation.issues.any {
            it.code == CarPlayThemeProgramIssueCode.OPAQUE_SLOT_FIELD &&
                it.path.contains("components[2]")
        })
        assertFalse(compilation.issues.any { it.path.contains("components[0]") })
        val slots = compilation.structuralProgram.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
        assertEquals(2, slots.size)
        assertEquals(CarPlayThemeStructuralSource.LocalUnresolved(InstrumentKindCase.NAVIGATION),
            slots.first().source)
    }

    @Test
    fun reportsInvalidExtentsAndInsetsInsteadOfNormalizingThem() {
        val invalid = compiler().compile(display(
            zoneFrame = rect(-20, 300, -100, 60),
            safeArea = listOf(101, 0, 100, 0),
            components = listOf(component("primary", slots = listOf(slot("empty", 0,
                rect(0, 0, -4, 4), null)), size = listOf(-5, 20))),
        ), CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))))
        assertTrue(invalid.issues.any { it.code == CarPlayThemeProgramIssueCode.INVALID_SAFE_AREA })
        assertTrue(invalid.issues.count { it.code == CarPlayThemeProgramIssueCode.INVALID_RENDER_BOUNDS } >= 3)
        assertFalse(invalid.rendererReady)

        val offParent = compiler().compile(display(zoneFrame = rect(-10, 300, 100, 60)))
        assertFalse(offParent.issues.any { it.code == CarPlayThemeProgramIssueCode.INVALID_RENDER_BOUNDS })

        val enormous = compiler().compile(display(
            zoneFrame = rect(0, 0, 1e300, 1e300),
            displaySize = listOf(1e300, 1e300),
            density = 1e300,
        ))
        assertTrue(enormous.issues.any {
            it.code == CarPlayThemeProgramIssueCode.INVALID_RENDER_BOUNDS && it.path == "display"
        })
        assertTrue(enormous.issues.any {
            it.code == CarPlayThemeProgramIssueCode.INVALID_RENDER_BOUNDS &&
                it.path == "layouts[0].zones[0]"
        })
    }

    @Test
    fun activeRuntimeSlotIdIsNotSilentlyOmitted() {
        val parsed = display(components = listOf(component("primary", slots = listOf(
            slot("runtime", 0, rect(0, 0, 4, 4), null, extra = mapOf(
                "id" to "d14f0e49-7903-4d4f-b8b1-c9db53e3a152",
            )),
        ))))
        val compilation = compiler().compile(parsed,
            CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))))
        assertTrue(compilation.issues.any {
            it.code == CarPlayThemeProgramIssueCode.UNMODELED_SLOT_ID &&
                it.path.endsWith("slots[0]")
        })
    }

    @Test(timeout = 10_000)
    fun largeAncestorIdWithManySlotsHashesInBoundedWork() {
        val longId = "L".repeat(1_000_000)
        val parsed = display(
            layoutId = longId,
            components = listOf(component("primary", slots = (0 until 500).map { index ->
                slot("s$index", index, rect(0, 0, 1, 1), null)
            })),
        )
        val compilation = compiler().compile(parsed,
            CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))))
        assertEquals(503, compilation.structuralProgram.instructions.size)
        assertEquals(64, compilation.structuralProgram.structuralFingerprint.hex().length)
    }

    @Test
    fun sceneRevisionChangesOnlyWhenUnderstoodActiveStructureChanges() {
        val parsed = display(components = listOf(
            component("first", slots = listOf(slot("a", 0, rect(0, 0, 4, 4), null))),
            component("second", slots = listOf(slot("b", 0, rect(1, 1, 5, 5), null))),
        ))
        val firstState = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        val secondState = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 1)))
        val first = compiler().compile(parsed, firstState).structuralProgram.structuralFingerprint
        val repeat = compiler().compile(parsed, firstState).structuralProgram.structuralFingerprint
        val changed = compiler().compile(parsed, secondState).structuralProgram.structuralFingerprint
        assertEquals(first, repeat)
        assertNotEquals(first, changed)
        assertNotEquals(first, compiler().compile(display(
            zoneFrame = rect(11, 20, 100, 60),
            components = listOf(
                component("first", slots = listOf(slot("a", 0, rect(0, 0, 4, 4), null))),
                component("second", slots = listOf(slot("b", 0, rect(1, 1, 5, 5), null))),
            ),
        ),
            firstState).structuralProgram.structuralFingerprint)
    }

    @Test
    fun unknownCustomMaskAndDynamicOverlaysRemainExplicitlyUnsupported() {
        val customClip = mapOf(
            "transitioning" to mapOf("none" to emptyMap<String, Any?>()),
            "notTransitioning" to mapOf("custom" to mapOf("mask" to mapOf("image" to mapOf(
                "startingFrame" to rect(0, 0, 10, 10),
                "image" to emptyMap<String, Any?>(),
                "endingFrame" to rect(0, 0, 10, 10),
                "animation" to emptyMap<String, Any?>(),
            )))),
        )
        val parsed = display(components = listOf(component("primary", slots = emptyList(),
            clippingBehavior = customClip)))
        val compilation = compiler().compile(parsed, CarPlayThemeSceneState(
            activePrimarySelections = listOf(CarPlayThemePrimarySelection(0, 0, 0)),
            trackingOverlayPresent = true,
        ))
        assertTrue(compilation.issues.any { it.code == CarPlayThemeProgramIssueCode.CUSTOM_MASK_UNRESOLVED })
        assertTrue(compilation.issues.any { it.code == CarPlayThemeProgramIssueCode.DYNAMIC_OVERLAY_UNRESOLVED })
    }

    @Test
    fun staticProofAcceptsOnlySelectedPlainVideoAndIsImmutable() {
        val mutableSelections = mutableListOf(CarPlayThemePrimarySelection(0, 0, 0))
        val state = CarPlayThemeSceneState(mutableSelections)
        val parsed = display(backgroundColor = "#223344", components = listOf(component(
            "primary", slots = listOf(slot("video", 3, rect(3, 4, 10, 11),
                instrument("inputStream", mapOf("_0" to "camera-front"),
                    backgroundColor = "#00000000", clipsToShape = false))),
        )))
        val result = compiler().assessExecutableStaticSubset(parsed, 2, 0, state)
        val proof = requireNotNull(result.proof)
        assertTrue(result.issues.isEmpty())
        assertEquals(result.structuralFingerprint, proof.structuralFingerprint)
        assertEquals(2, proof.displayIndex)
        assertEquals(CarPlayThemeSceneLayoutAddress(0, "main"), proof.layoutAddress)
        assertEquals("camera-front", proof.videoSlots.single().streamIdentifier)
        assertEquals(rectLogical(13.0, 24.0, 10.0, 11.0),
            proof.videoSlots.single().frameInDisplayLogicalSpace)
        assertTrue(proof.matches(2, 0, state, result.structuralFingerprint))
        mutableSelections.clear()
        assertEquals(1, proof.selectedPrimaryComponents.size)
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (result.issues as MutableList<CarPlayThemeProgramIssue>).add(
                CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.STATIC_SOURCE_UNSUPPORTED, "bad"))
        }
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (proof.videoSlots as MutableList<CarPlayThemeExecutableVideoSlot>).clear()
        }
        assertFalse(proof.matches(1, 0, state, result.structuralFingerprint))
        assertFalse(proof.matches(2, 1, state, result.structuralFingerprint))
        assertFalse(result.structuralProgram.instructions.isEmpty())
    }

    @Test
    fun staticProofBindsTwoExplicitlySelectedCarouselZonesAndSceneRevision() {
        fun video(stream: String) = slot(stream, 0, rect(0, 0, 40, 30),
            instrument("inputStream", mapOf("_0" to stream),
                backgroundColor = "#00000000", clipsToShape = false))
        val zoneClip = mapOf(
            "transitioning" to mapOf("none" to emptyMap<String, Any?>()),
            "notTransitioning" to mapOf("zoneShape" to emptyMap<String, Any?>()),
        )
        val parsed = display(backgroundColor = "#223344", zones = listOf(
            themeZone("maps", "mapsMedia", rect(10, 20, 100, 60), listOf(
                component("map-other", slots = listOf(video("map-other-stream"))),
                component("map-active", slots = listOf(video("map-active-stream")),
                    clippingBehavior = zoneClip),
            )),
            themeZone("media", "dca", rect(130, 20, 100, 60), listOf(
                component("media-active", slots = listOf(video("media-stream"))),
                component("media-other", slots = listOf(video("media-other-stream"))),
            )),
            themeZone("fixed", "single", rect(250, 20, 100, 60), emptyList()),
        ))
        fun state(mapComponent: String) = CarPlayThemeSceneState(
            CarPlayThemeActiveComponentCrosswalk.primarySelections(parsed, 0,
                mapOf("maps" to mapComponent, "media" to "media-active")),
        )
        val selected = state("map-active")
        val result = compiler().assessExecutableStaticSubset(parsed, 3, 0, selected)
        val proof = requireNotNull(result.proof)
        assertTrue(result.issues.isEmpty())
        assertEquals(listOf("map-active-stream", "media-stream"),
            proof.videoSlots.map { it.streamIdentifier })
        assertEquals(rectLogical(10.0, 20.0, 100.0, 60.0),
            proof.videoSlots.first().clipInDisplayLogicalSpace)
        assertEquals(listOf(CarPlayThemePrimarySelection(0, 0, 1),
            CarPlayThemePrimarySelection(0, 1, 0)), proof.selectedPrimaryComponents)
        assertTrue(proof.matches(3, 0, selected, result.structuralFingerprint))

        val changedState = state("map-other")
        val changed = compiler().assessExecutableStaticSubset(parsed, 3, 0, changedState)
        assertTrue(changed.issues.isEmpty())
        assertEquals("map-other-stream", requireNotNull(changed.proof).videoSlots.first()
            .streamIdentifier)
        assertNotEquals(result.structuralFingerprint, changed.structuralFingerprint)
        assertFalse(proof.matches(3, 0, changedState, result.structuralFingerprint))
        assertFalse(proof.matches(3, 0, selected, changed.structuralFingerprint))
    }

    @Test
    fun staticProofRejectsMissingCarouselSelectionOpaquePayloadAndDeck() {
        val zones = listOf(
            themeZone("maps", "mapsMedia", rect(10, 20, 100, 60),
                listOf(component("map-active", slots = emptyList()))),
            themeZone("media", "dca", rect(130, 20, 100, 60),
                listOf(component("media-active", slots = emptyList()))),
        )
        val oneSelection = CarPlayThemeSceneState(
            listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        val missing = compiler().assessExecutableStaticSubset(
            display(backgroundColor = "#223344", zones = zones), 0, 0, oneSelection)
        assertTrue(missing.proof == null)
        assertTrue(missing.issues.any {
            it.code == CarPlayThemeProgramIssueCode.STATIC_ZONE_UNSUPPORTED &&
                it.path == "layouts[0].zones[1]"
        })

        val opaque = compiler().assessExecutableStaticSubset(display(backgroundColor = "#223344",
            zones = listOf(themeZone("maps", "mapsMedia", rect(10, 20, 100, 60),
                listOf(component("map-active", slots = emptyList())),
                payload = mapOf("futureMode" to true)))), 0, 0, oneSelection)
        assertTrue(opaque.proof == null)
        assertTrue(opaque.issues.any {
            it.code == CarPlayThemeProgramIssueCode.OPAQUE_ZONE_FIELD &&
                it.path == "layouts[0].zones[0]"
        })

        val deck = compiler().assessExecutableStaticSubset(display(backgroundColor = "#223344",
            zones = listOf(themeZone("stack", "deck", rect(10, 20, 100, 60),
                listOf(component("active", slots = emptyList()))))), 0, 0, oneSelection)
        assertTrue(deck.proof == null)
        assertTrue(deck.issues.any { it.code == CarPlayThemeProgramIssueCode.STATIC_ZONE_UNSUPPORTED })
    }

    @Test
    fun staticProofBindsSelectedRectangularZoneClipToVideoWithoutLosingCustomGeometry() {
        val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        val clipToZone = mapOf(
            "transitioning" to mapOf("none" to emptyMap<String, Any?>()),
            "notTransitioning" to mapOf("zoneShape" to emptyMap<String, Any?>()),
        )
        fun assess(zoneX: Int, clip: Any?) = compiler().assessExecutableStaticSubset(
            display(backgroundColor = "#223344", zoneFrame = rect(zoneX, 20, 100, 60),
                components = listOf(component("primary", slots = listOf(slot(
                    "video", 0, rect(90, 0, 30, 20),
                    instrument("inputStream", mapOf("_0" to "camera-front"),
                        backgroundColor = "#00000000", clipsToShape = false),
                )), clippingBehavior = clip))),
            0, 0, state,
        )

        val clipped = assess(10, clipToZone)
        assertTrue(clipped.issues.isEmpty())
        val clippedSlot = requireNotNull(clipped.proof).videoSlots.single()
        assertEquals(rectLogical(100.0, 20.0, 30.0, 20.0),
            clippedSlot.frameInDisplayLogicalSpace)
        assertEquals(rectLogical(10.0, 20.0, 100.0, 60.0),
            clippedSlot.clipInDisplayLogicalSpace)
        assertTrue(requireNotNull(clipped.proof).matches(0, 0, state,
            clipped.structuralFingerprint))

        val unclipped = assess(10, null)
        assertTrue(unclipped.issues.isEmpty())
        assertEquals(null, requireNotNull(unclipped.proof).videoSlots.single()
            .clipInDisplayLogicalSpace)
        assertNotEquals(unclipped.structuralFingerprint, clipped.structuralFingerprint)

        val moved = assess(11, clipToZone)
        assertEquals(rectLogical(11.0, 20.0, 100.0, 60.0),
            requireNotNull(moved.proof).videoSlots.single().clipInDisplayLogicalSpace)
        assertNotEquals(clipped.structuralFingerprint, moved.structuralFingerprint)
        assertFalse(requireNotNull(clipped.proof).matches(0, 0, state,
            moved.structuralFingerprint))
    }

    @Test
    fun staticProofRejectsZoneClipWhenItsShapeGeometryOrStyleIsUnproved() {
        val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        fun clipping(style: Map<String, Any?>) = mapOf(
            "transitioning" to mapOf("none" to emptyMap<String, Any?>()),
            "notTransitioning" to style,
        )
        val zoneClip = clipping(mapOf("zoneShape" to emptyMap<String, Any?>()))
        fun assess(
            zoneFrame: Any? = rect(10, 20, 100, 60),
            zoneShape: Any? = null,
            clip: Any? = zoneClip,
            transition: Boolean = false,
        ) = compiler().assessExecutableStaticSubset(display(
            backgroundColor = "#223344", zoneFrame = zoneFrame, zoneShape = zoneShape,
            components = listOf(component("primary", slots = listOf(slot("video", 0,
                rect(0, 0, 10, 10), instrument("inputStream", mapOf("_0" to "front"),
                    backgroundColor = "#00000000", clipsToShape = false))),
                clippingBehavior = clip)),
        ), 0, 0, CarPlayThemeSceneState(state.activePrimarySelections,
            transitionActive = transition))
        fun assertRejected(result: CarPlayThemeExecutableStaticSubsetAssessment,
            code: CarPlayThemeProgramIssueCode) {
            assertTrue(result.proof == null)
            assertTrue(result.issues.any { it.code == code })
        }

        assertRejected(assess(zoneShape = mapOf("rectangular" to mapOf("_0" to 4))),
            CarPlayThemeProgramIssueCode.STATIC_ZONE_UNSUPPORTED)
        assertRejected(assess(zoneShape = mapOf("circular" to emptyMap<String, Any?>())),
            CarPlayThemeProgramIssueCode.STATIC_ZONE_UNSUPPORTED)
        assertRejected(assess(zoneFrame = rect(390, 20, 100, 60)),
            CarPlayThemeProgramIssueCode.STATIC_COMPONENT_UNSUPPORTED)
        assertRejected(assess(clip = clipping(mapOf("zoneShape" to mapOf("future" to true)))),
            CarPlayThemeProgramIssueCode.OPAQUE_COMPONENT_FIELD)
        assertRejected(assess(clip = clipping(mapOf("custom" to mapOf("mask" to mapOf(
            "image" to mapOf(
                "startingFrame" to rect(0, 0, 10, 10),
                "image" to emptyMap<String, Any?>(),
                "endingFrame" to rect(0, 0, 10, 10),
                "animation" to emptyMap<String, Any?>(),
            ),
        ))))), CarPlayThemeProgramIssueCode.CUSTOM_MASK_UNRESOLVED)
        assertRejected(assess(transition = true),
            CarPlayThemeProgramIssueCode.STATIC_TRANSITION_UNSUPPORTED)
    }

    @Test
    fun staticProofRejectsBackgroundLayoutZoneComponentAndSlotBranches() {
        val selected = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        fun codes(display: Display, state: CarPlayThemeSceneState = CarPlayThemeSceneState()) =
            compiler().assessExecutableStaticSubset(display, 0, 0, state).also {
                assertTrue(it.proof == null)
            }.issues.map { it.code }.toSet()
        assertTrue(CarPlayThemeProgramIssueCode.STATIC_BACKGROUND_MISSING in
            codes(display()))
        assertTrue(CarPlayThemeProgramIssueCode.OPAQUE_LAYOUT_BACKGROUND in
            codes(display()))
        assertEquals(setOf(CarPlayThemeProgramIssueCode.STATIC_BACKGROUND_NOT_OPAQUE),
            codes(display(backgroundColor = "#11223344")))
        assertEquals(setOf(CarPlayThemeProgramIssueCode.STATIC_LAYOUT_NOT_PRIMARY),
            codes(display(backgroundColor = "#112233", layoutType = "underlay")))
        assertEquals(setOf(CarPlayThemeProgramIssueCode.STATIC_LAYOUT_SAFE_AREA),
            codes(display(backgroundColor = "#112233", safeArea = listOf(1, 0, 0, 0))))
        assertEquals(setOf(CarPlayThemeProgramIssueCode.STATIC_ZONE_UNSUPPORTED),
            codes(display(backgroundColor = "#112233", zoneType = mapOf(
                "dca" to emptyMap<String, Any?>()))))
        assertEquals(setOf(CarPlayThemeProgramIssueCode.STATIC_ZONE_UNSUPPORTED),
            codes(display(backgroundColor = "#112233", zoneShape = mapOf(
                "circular" to emptyMap<String, Any?>()))))
        assertEquals(setOf(CarPlayThemeProgramIssueCode.STATIC_COMPONENT_UNSUPPORTED),
            codes(display(backgroundColor = "#112233", components = listOf(component(
                "primary", slots = emptyList(), extra = mapOf("trackable" to true)))), selected))
        fun slotVariant(extra: Map<String, Any?>): Display = display(
            backgroundColor = "#112233",
            components = listOf(component("primary", slots = listOf(
                slot("empty", 0, rect(0, 0, 4, 4), null, extra = extra),
            ))),
        )
        assertEquals(setOf(CarPlayThemeProgramIssueCode.STATIC_SLOT_UNSUPPORTED),
            codes(slotVariant(mapOf("style" to mapOf(
                "circular" to emptyMap<String, Any?>(),
            ))), selected))
        assertEquals(setOf(CarPlayThemeProgramIssueCode.STATIC_SLOT_UNSUPPORTED),
            codes(slotVariant(mapOf("safeAreaInsets" to listOf(0, 1, 0, 0))), selected))
    }

    @Test
    fun staticProofRejectsGaugePunchThroughMaskOverlayUnknownFieldsAndBounds() {
        val selected = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        fun assess(slot: Map<String, Any?>, state: CarPlayThemeSceneState = selected) =
            compiler().assessExecutableStaticSubset(display(backgroundColor = "#112233",
                components = listOf(component("primary", slots = listOf(slot)))), 0, 0, state)
        val gauge = assess(slot("gauge", 0, rect(0, 0, 4, 4), instrument("gauge", mapOf(
            "configuration" to mapOf(
                "style" to mapOf("spinner" to emptyMap<String, Any?>()),
                "rendersLocally" to true,
                "visibilityConfiguration" to mapOf("visible" to true),
            ),
        ))))
        assertTrue(gauge.proof == null)
        assertTrue(gauge.issues.map { it.code }.containsAll(listOf(
            CarPlayThemeProgramIssueCode.OPAQUE_INSTRUMENT_CONTENT,
            CarPlayThemeProgramIssueCode.STATIC_SOURCE_UNSUPPORTED,
        )))
        val punch = assess(slot("punch", 0, rect(0, 0, 4, 4), instrument(
            "punchThrough", mapOf("_0" to "reference", "_1" to "speed"))))
        assertTrue(punch.proof == null)
        assertTrue(punch.issues.any { it.code == CarPlayThemeProgramIssueCode.STATIC_SOURCE_UNSUPPORTED })
        val outOfBounds = assess(slot("video", 0, rect(390, 0, 20, 4),
            instrument("inputStream", mapOf("_0" to "front"),
                backgroundColor = "#00000000", clipsToShape = false)))
        assertEquals(listOf(CarPlayThemeProgramIssueCode.STATIC_VIDEO_OUTSIDE_LOGICAL_VIEWPORT),
            outOfBounds.issues.map { it.code })
        val transformed = assess(slot("video", 0, rect(0, 0, 4, 4),
            instrument("inputStream", mapOf(
                "_0" to "front",
                "_2" to mapOf("clipToBounds" to false,
                    "transform" to mapOf("scaleToFit" to mapOf("alignment" to 2))),
            ), backgroundColor = "#00000000", clipsToShape = false)))
        assertTrue(transformed.proof == null)
        assertTrue(transformed.issues.any {
            it.code == CarPlayThemeProgramIssueCode.OPAQUE_INSTRUMENT_CONTENT
        })
        val unknown = assess(slot("video", 0, rect(0, 0, 4, 4),
            instrument("inputStream", mapOf("_0" to "front"),
                backgroundColor = "#00000000", clipsToShape = false),
            extra = mapOf("future" to true)))
        assertTrue(unknown.issues.any { it.code == CarPlayThemeProgramIssueCode.OPAQUE_SLOT_FIELD })
        assertTrue(unknown.proof == null)
        val mask = mapOf(
            "transitioning" to mapOf("none" to emptyMap<String, Any?>()),
            "notTransitioning" to mapOf("custom" to mapOf("mask" to mapOf("image" to mapOf(
                "startingFrame" to rect(0, 0, 10, 10),
                "image" to emptyMap<String, Any?>(),
                "endingFrame" to rect(0, 0, 10, 10),
                "animation" to emptyMap<String, Any?>(),
            )))),
        )
        val masked = compiler().assessExecutableStaticSubset(display(backgroundColor = "#112233",
            components = listOf(component("primary", slots = emptyList(),
                clippingBehavior = mask))), 0, 0, selected)
        assertTrue(masked.proof == null)
        assertTrue(masked.issues.map { it.code }.containsAll(listOf(
            CarPlayThemeProgramIssueCode.CUSTOM_MASK_UNRESOLVED,
            CarPlayThemeProgramIssueCode.STATIC_COMPONENT_UNSUPPORTED,
        )))
        val overlay = assess(slot("empty", 0, rect(0, 0, 4, 4), null),
            CarPlayThemeSceneState(selected.activePrimarySelections,
                trackingOverlayPresent = true, appearanceOverlayPresent = true))
        assertTrue(overlay.proof == null)
        assertEquals(2, overlay.issues.count {
            it.code == CarPlayThemeProgramIssueCode.STATIC_DYNAMIC_OVERLAY
        })
        val transition = assess(slot("empty", 0, rect(0, 0, 4, 4), null),
            CarPlayThemeSceneState(selected.activePrimarySelections, transitionActive = true))
        assertTrue(transition.issues.any {
            it.code == CarPlayThemeProgramIssueCode.STATIC_TRANSITION_UNSUPPORTED
        })
    }

    @Test
    fun phoneOutputGaugeAndIndicatorHaveDistinctFingerprintsAndRemainNonExecutable() {
        val selected = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        fun assess(kind: String, payload: Map<String, Any?>) =
            compiler().assessExecutableStaticSubset(display(
                backgroundColor = "#112233",
                components = listOf(component("primary", slots = listOf(slot(
                    "unresolved", 0, rect(0, 0, 4, 4), instrument(kind, payload),
                )))),
            ), 0, 0, selected)
        fun gauge(local: Boolean) = mapOf("configuration" to mapOf(
            "style" to mapOf("spinner" to emptyMap<String, Any?>()),
            "rendersLocally" to local,
            "visibilityConfiguration" to emptyMap<String, Any?>(),
        ))
        fun indicator(style: IndicatorStyleCase) = mapOf("configuration" to mapOf(
            "dataIdentifier" to "indicator",
            "style" to mapOf(style.wireValue to if (style == IndicatorStyleCase.MESSAGE) {
                emptyMap<String, Any?>()
            } else {
                mapOf("configuration" to emptyMap<String, Any?>())
            }),
            "presentationEdge" to "bottom",
            "containerInsets" to emptyMap<String, Any?>(),
        ))
        fun source(assessment: CarPlayThemeExecutableStaticSubsetAssessment) =
            assessment.structuralProgram.instructions
                .filterIsInstance<CarPlayThemeProgramInstruction.Slot>().single().source
        fun assertRejected(assessment: CarPlayThemeExecutableStaticSubsetAssessment) {
            assertTrue(assessment.proof == null)
            assertTrue(assessment.issues.any {
                it.code == CarPlayThemeProgramIssueCode.STATIC_SOURCE_UNSUPPORTED
            })
            assertTrue(assessment.issues.any {
                it.code == CarPlayThemeProgramIssueCode.OPAQUE_INSTRUMENT_CONTENT
            })
        }

        val nativeGauge = assess("gauge", gauge(true))
        val phoneGauge = assess("gauge", gauge(false))
        assertEquals(CarPlayThemeStructuralSource.LocalUnresolved(InstrumentKindCase.GAUGE),
            source(nativeGauge))
        assertEquals(CarPlayThemeStructuralSource.NonNativeUnresolved(InstrumentKindCase.GAUGE),
            source(phoneGauge))
        assertNotEquals(nativeGauge.structuralFingerprint, phoneGauge.structuralFingerprint)
        assertRejected(nativeGauge)
        assertRejected(phoneGauge)

        val indicators = IndicatorStyleCase.entries.associateWith { style ->
            assess("indicator", indicator(style))
        }
        IndicatorStyleCase.entries.forEach { style ->
            val assessment = indicators.getValue(style)
            val expected = if (style == IndicatorStyleCase.MESSAGE) {
                CarPlayThemeStructuralSource.NonNativeUnresolved(InstrumentKindCase.INDICATOR)
            } else {
                CarPlayThemeStructuralSource.LocalUnresolved(InstrumentKindCase.INDICATOR)
            }
            assertEquals(expected, source(assessment))
            assertRejected(assessment)
        }
        assertNotEquals(indicators.getValue(IndicatorStyleCase.CRITICAL).structuralFingerprint,
            indicators.getValue(IndicatorStyleCase.MESSAGE).structuralFingerprint)
    }

    @Test
    fun staticProofIssuesAreSelectedAndStructuralFingerprintStillBindsProgram() {
        val parsed = display(backgroundColor = "#112233", components = listOf(component(
            "active", slots = listOf(slot("video", 0, rect(0, 0, 4, 4),
                instrument("inputStream", mapOf("_0" to "front"),
                    backgroundColor = "#00000000", clipsToShape = false))),
        )))
        val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        val accepted = compiler().assessExecutableStaticSubset(parsed, 0, 0, state)
        assertTrue(accepted.issues.isEmpty())
        val missing = compiler().assessExecutableStaticSubset(parsed, 0, 8, state)
        assertEquals(listOf(CarPlayThemeProgramIssueCode.STATIC_LAYOUT_ABSENT,
            CarPlayThemeProgramIssueCode.STATIC_BACKGROUND_MISSING), missing.issues.map { it.code })
        assertEquals(accepted.structuralFingerprint, missing.structuralFingerprint)
        assertFalse(requireNotNull(accepted.proof).matches(0, 0, state,
            compiler().compile(display(backgroundColor = "#223344", components = listOf(
                component("active", slots = emptyList()))), state)
                .structuralProgram.structuralFingerprint))
    }

    @Test
    fun staticProofRejectsUnknownActiveFieldsAtEverySchemaLevel() {
        val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        fun assess(
            displayExtra: Map<String, Any?> = emptyMap(),
            layoutExtra: Map<String, Any?> = emptyMap(),
            zoneExtra: Map<String, Any?> = emptyMap(),
            componentExtra: Map<String, Any?> = emptyMap(),
            slotExtra: Map<String, Any?> = emptyMap(),
        ) = compiler().assessExecutableStaticSubset(display(
            backgroundColor = "#112233",
            displayExtra = displayExtra,
            layoutExtra = layoutExtra,
            zoneExtra = zoneExtra,
            components = listOf(component("primary", slots = listOf(slot(
                "video", 0, rect(0, 0, 4, 4), instrument("inputStream",
                    mapOf("_0" to "front"), "#00000000", false), extra = slotExtra,
            )), extra = componentExtra)),
        ), 0, 0, state)
        val cases = listOf(
            assess(displayExtra = mapOf("futureDisplayField" to true)) to
                CarPlayThemeProgramIssueCode.OPAQUE_DISPLAY_FIELD,
            assess(layoutExtra = mapOf("futureLayoutField" to true)) to
                CarPlayThemeProgramIssueCode.OPAQUE_LAYOUT_FIELD,
            assess(zoneExtra = mapOf("futureZoneField" to true)) to
                CarPlayThemeProgramIssueCode.OPAQUE_ZONE_FIELD,
            assess(componentExtra = mapOf("futureComponentField" to true)) to
                CarPlayThemeProgramIssueCode.OPAQUE_COMPONENT_FIELD,
            assess(slotExtra = mapOf("futureSlotField" to true)) to
                CarPlayThemeProgramIssueCode.OPAQUE_SLOT_FIELD,
        )
        cases.forEach { (assessment, code) ->
            assertTrue(assessment.proof == null)
            assertTrue(assessment.issues.any { it.code == code })
        }
        val gradient = compiler().assessExecutableStaticSubset(display(
            backgroundColor = mapOf("gradient" to listOf("red", "blue"))), 0, 0)
        assertTrue(gradient.proof == null)
        assertTrue(gradient.issues.any {
            it.code == CarPlayThemeProgramIssueCode.OPAQUE_LAYOUT_BACKGROUND
        })
    }

    @Test
    fun layoutStyleMetadataStillFailsClosedWithoutVerifiedPhoneWitness() {
        val parsed = display(
            backgroundColor = "#101820",
            layoutExtra = mapOf("layoutStyleID" to "bench-style"),
        )
        val assessment = compiler().assessExecutableStaticSubset(parsed, 0, 0)
        assertTrue(assessment.proof == null)
        assertTrue(assessment.issues.any {
            it.code == CarPlayThemeProgramIssueCode.OPAQUE_LAYOUT_FIELD &&
                it.path == "layouts[0]"
        })
    }

    @Test
    fun staticProofRejectsSameStageCrossComponentVideoOverlapWithoutGuessingZOrder() {
        fun video(position: String, z: Int, frame: Any?) = slot(
            position, z, frame, instrument("inputStream", mapOf("_0" to position),
                backgroundColor = "#00000000", clipsToShape = false),
        )
        fun overlay(id: String, z: Int, frame: Any?) = component(
            id, type = "overlay", slots = listOf(video("$id-video", z, frame)),
        )
        fun assess(components: List<Map<String, Any?>>) =
            compiler().assessExecutableStaticSubset(display(
                backgroundColor = "#112233", components = components,
            ), 0, 0)

        // Both overlay slots are in ZONE_OVERLAY_COMPONENT. Sorting each component's z-index is
        // not evidence for the paint order between their overlapping rectangles.
        val overlap = assess(listOf(
            overlay("first", 100, rect(0, 0, 20, 20)),
            overlay("second", -100, rect(10, 10, 20, 20)),
        ))
        assertTrue(overlap.proof == null)
        val ambiguous = overlap.issues.filter {
            it.code == CarPlayThemeProgramIssueCode.STATIC_CROSS_COMPONENT_VIDEO_OVERLAP
        }
        assertEquals(1, ambiguous.size)
        assertTrue(ambiguous.single().path.contains("components[0].slots[0]"))
        assertTrue(ambiguous.single().path.contains("components[1].slots[0]"))

        val touchingOnly = assess(listOf(
            overlay("first", 100, rect(0, 0, 20, 20)),
            overlay("second", -100, rect(20, 0, 20, 20)),
        ))
        assertTrue(touchingOnly.issues.isEmpty())
        assertEquals(2, requireNotNull(touchingOnly.proof).videoSlots.size)

        val sameComponent = assess(listOf(component("one", type = "overlay", slots = listOf(
            video("high", 3, rect(0, 0, 20, 20)),
            video("low", 1, rect(10, 10, 20, 20)),
        ))))
        assertTrue(sameComponent.issues.isEmpty())
        assertEquals(listOf(1, 3), requireNotNull(sameComponent.proof).videoSlots.map { it.zIndex })
    }

    private fun compiler() = CarPlayThemeProgramCompiler()
}

private fun display(
    zoneFrame: Any? = rect(10, 20, 100, 60),
    components: List<Map<String, Any?>> = emptyList(),
    safeArea: Any? = listOf(0, 0, 0, 0),
    displaySize: Any? = listOf(400, 200),
    density: Any? = 3,
    layoutId: String = "main",
    backgroundColor: Any? = mapOf("unresolved" to true),
    layoutType: String = "primary",
    zoneType: Any? = mapOf("single" to emptyMap<String, Any?>()),
    zoneShape: Any? = null,
    zones: List<Map<String, Any?>>? = null,
    displayExtra: Map<String, Any?> = emptyMap(),
    layoutExtra: Map<String, Any?> = emptyMap(),
    zoneExtra: Map<String, Any?> = emptyMap(),
): Display = CarPlayThemeSchema.parseDisplay(mapOf(
    "id" to "cluster",
    "type" to "cluster",
    "size" to displaySize,
    "density" to density,
    "allowManufacturerAppIcon" to false,
    "requiresDarkAppearance" to false,
    "layouts" to listOf(mapOf(
        "id" to layoutId,
        "zones" to (zones ?: listOf(mapOf(
            "id" to "zone",
            "frame" to zoneFrame,
            "components" to components,
            "type" to zoneType,
        ).let { if (zoneShape != null) it + ("zoneShape" to zoneShape) else it }
            .plus(zoneExtra))),
        "layoutType" to layoutType,
        "backgroundColor" to backgroundColor,
        "safeAreaInsets" to safeArea,
        "driveModeThemeConfigurations" to emptyMap<String, Any?>(),
    ).plus(layoutExtra)),
).plus(displayExtra))

private fun themeZone(
    id: String,
    type: String,
    frame: Any?,
    components: List<Map<String, Any?>>,
    payload: Map<String, Any?> = emptyMap(),
): Map<String, Any?> = mapOf(
    "id" to id,
    "frame" to frame,
    "components" to components,
    "type" to mapOf(type to payload),
)

private fun component(
    id: String,
    type: String = "primary",
    slots: List<Map<String, Any?>>,
    size: Any? = listOf(100, 60),
    clippingBehavior: Any? = null,
    extra: Map<String, Any?> = emptyMap(),
): Map<String, Any?> = mutableMapOf<String, Any?>(
    "id" to id,
    "type" to type,
    "size" to size,
    "slots" to slots,
).also {
    if (clippingBehavior != null) it["clippingBehavior"] = clippingBehavior
    it.putAll(extra)
}

private fun slot(
    id: String,
    zIndex: Int,
    frame: Any?,
    instrument: Map<String, Any?>?,
    extra: Map<String, Any?> = emptyMap(),
): Map<String, Any?> = mutableMapOf<String, Any?>(
    "style" to mapOf("rectangular" to emptyMap<String, Any?>()),
    "frame" to frame,
    "zIndex" to zIndex,
    "positionId" to id,
).also { result ->
    if (instrument != null) result["instrument"] = instrument
    result.putAll(extra)
}

private fun instrument(
    kind: String,
    payload: Map<String, Any?>,
    backgroundColor: Any? = mapOf("unresolved" to true),
    clipsToShape: Boolean? = null,
): Map<String, Any?> = mapOf(
    "kind" to mapOf(kind to payload),
    "backgroundColor" to backgroundColor,
).let { if (clipsToShape == null) it else it + ("clipsToShape" to clipsToShape) }

private fun rectLogical(x: Double, y: Double, width: Double, height: Double) =
    CarPlayLogicalRect(CarPlayLogicalPoint(x, y), CarPlayLogicalSize(width, height))

private fun rect(x: Number, y: Number, width: Number, height: Number): List<List<Number>> =
    listOf(listOf(x, y), listOf(width, height))
