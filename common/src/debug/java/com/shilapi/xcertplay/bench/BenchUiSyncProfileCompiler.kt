package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.CompiledUiSyncDisplayAssignment
import com.shilapi.xcertplay.CompiledUiSyncLocalCanvasAssignment
import com.shilapi.xcertplay.CompiledUiSyncInstrumentReferenceAssignment
import com.shilapi.xcertplay.CompiledUiSyncSlotIngressAssignment
import com.shilapi.xcertplay.CompiledUiSyncThemeIntent
import com.shilapi.xcertplay.CompiledUiSyncThemeProgramSelection
import com.shilapi.xcertplay.CompiledUiSyncThemeVariant
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.deriveVerifiedUiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationId
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemePrimarySelection
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeActiveComponentCrosswalk
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeExecutableVideoSlot
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalRect
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalSize
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramInstruction
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeResolvedStyleWitness
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneState
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralSource
import com.shilapi.xcertplay.vehicle.ultra.theme.SlotStyleCase
import com.shilapi.xcertplay.vehicle.ultra.theme.Display
import com.shilapi.xcertplay.vehicle.ultra.theme.DisplayType
import com.shilapi.xcertplay.vehicle.ultra.theme.decodeThemeJson
import java.math.BigInteger
import java.util.Collections
import java.util.UUID

/**
 * A fixed Host declaration, not a physical-target lease. The caller must separately prove that
 * its advertised ScreenID really belongs to a current, independently physical output Surface.
 */
internal data class BenchUiSyncHostOutput(
    val profileDisplayIndex: Int,
    val advertisedScreenId: String,
    val destination: DisplayDestination,
    val output: UltraDisplayGraphOutput,
)

/** Parsed signed identifiers only; this row does not prove a current Host target exists. */
internal data class BenchUiSyncDeclaredDisplay(
    val profileDisplayIndex: Int,
    val profileDisplayId: String?,
    val advertisedScreenId: String,
    val destination: DisplayDestination,
    val output: UltraDisplayGraphOutput,
)

/** This fixed UUID-bound selector has no signed, guessed, or phone-chosen connection ID. */
internal data class BenchUiSyncDeclaredIngress(
    val id: UltraIngressId,
    val advertisedScreenId: String,
    val streamType: Int,
)

/**
 * Compiles only an explicitly selected, signed Debug bench theme catalog. The JSON lives inside
 * the inspector-verified Profile's content digest; its local signature is not Apple/OEM trust.
 * This never issues an authorization, physical-output proof, or renderer-readiness receipt.
 */
internal class BenchUiSyncProfileCompiler {
    class Compiled internal constructor(
        assignments: List<CompiledUiSyncDisplayAssignment>,
        outputs: List<UltraDisplayGraphOutput>,
        ingressSpecs: List<UiSyncRendererIngressSpec>,
        val semanticThemeProgramFingerprint: UiSyncSemanticThemeProgramFingerprint,
    ) {
        val assignments: List<CompiledUiSyncDisplayAssignment> =
            Collections.unmodifiableList(ArrayList(assignments))
        val outputs: List<UltraDisplayGraphOutput> = Collections.unmodifiableList(ArrayList(outputs))
        val ingressSpecs: List<UiSyncRendererIngressSpec> =
            Collections.unmodifiableList(ArrayList(ingressSpecs))
    }

    /** Signed identifiers for Host lookup only; no caller/output or live physical proof is made. */
    fun declaredDisplays(profile: VerifiedUltraProfile): List<BenchUiSyncDeclaredDisplay> =
        Collections.unmodifiableList(parseCatalog(profile).rows.map { it.declaration })

    /** One required fixed Host selector per signed active slot ingress namespace. */
    fun declaredIngresses(profile: VerifiedUltraProfile): List<BenchUiSyncDeclaredIngress> =
        parseCatalog(profile).ingresses

    private class ParsedDisplay(
        val profileDisplay: Display,
        val declaration: BenchUiSyncDeclaredDisplay,
        val layouts: List<ParsedLayout>,
    )

    private data class ParsedSlot(
        val video: CarPlayThemeExecutableVideoSlot,
        val streamIdentifier: String,
        val ingressId: UltraIngressId,
    )

    private class ParsedLayout(
        val intent: CompiledUiSyncThemeIntent,
        val program: CompiledUiSyncThemeProgramSelection,
        val slots: List<ParsedSlot>,
        val localCanvas: List<CompiledUiSyncLocalCanvasAssignment>,
        val instrumentReferences: List<CompiledUiSyncInstrumentReferenceAssignment>,
    )

    private class ParsedCatalog(
        val phonePackageDigest: UltraProfileDigest,
        val rows: List<ParsedDisplay>,
        val ingresses: List<BenchUiSyncDeclaredIngress>,
    )

    fun compile(
        profile: VerifiedUltraProfile,
        hostOutputs: Collection<BenchUiSyncHostOutput>,
        ingressSpecs: Collection<UiSyncRendererIngressSpec>,
    ): Compiled {
        val catalog = parseCatalog(profile)
        val selected = catalog.rows
        val hosts = boundedCollection(hostOutputs, MAX_DISPLAYS, "Host outputs")
        val ingresses = boundedCollection(ingressSpecs, MAX_INGRESSES, "Host ingress specs",
            nonempty = false)
        val declaredIngresses = catalog.ingresses.associateBy { it.id }
        require(hosts.size == selected.size &&
            hosts.map { it.profileDisplayIndex }.toSet().size == hosts.size &&
            hosts.map { it.destination }.toSet().size == hosts.size &&
            hosts.map { it.advertisedScreenId }.toSet().size == hosts.size
        ) { "Host output rows do not exactly cover distinct Profile displays" }
        UiSyncRendererStaticPlan(hosts.map { it.output }, ingresses)
        val hostByIndex = hosts.associateBy { it.profileDisplayIndex }
        val ingressById = ingresses.associateBy { it.id }
        require(ingressById.keys == declaredIngresses.keys && ingresses.all { spec ->
            val declared = declaredIngresses[spec.id] ?: return@all false
            spec.airPlayStreamIdentity.type == declared.streamType &&
                spec.airPlayStreamIdentity.streamConnectionId == null &&
                spec.airPlayStreamIdentity.clientTypeUuid == null &&
                spec.setupDisplayUuid == declared.advertisedScreenId
        }) { "Host ingress selectors differ from signed fixed ScreenID bindings" }

        val usedIngressIds = LinkedHashSet<UltraIngressId>()
        val streamBindings = LinkedHashMap<Pair<Int, String>, UiSyncRendererIngressSpec>()
        val assignments = selected.map { parsed ->
            val declaration = parsed.declaration
            val index = declaration.profileDisplayIndex
            val host = requireNotNull(hostByIndex[index]) {
                "Signed Profile display has no exact Host output"
            }
            val display = parsed.profileDisplay
            require(host.advertisedScreenId == declaration.advertisedScreenId &&
                host.destination == declaration.destination &&
                host.output == declaration.output
            ) { "Signed Profile identities differ from the exact Host output row" }

            val variants = parsed.layouts.map { signed ->
                val mapped = signed.slots.map { slot ->
                    val ingress = requireNotNull(ingressById[slot.ingressId]) {
                        "Signed slot references a foreign Host ingress"
                    }
                    usedIngressIds += slot.ingressId
                    val previous = streamBindings.putIfAbsent(
                        index to slot.streamIdentifier, ingress,
                    )
                    require(previous == null || previous == ingress) {
                        "One Profile display stream identifier maps to different Host ingresses"
                    }
                    CompiledUiSyncSlotIngressAssignment(
                        slot.video.address, slot.streamIdentifier, ingress,
                    )
                }
                Variant(signed.intent, signed.program, mapped, signed.localCanvas,
                    signed.instrumentReferences)
            }
            CompiledUiSyncDisplayAssignment(
                phonePackageDigest = catalog.phonePackageDigest,
                profileDisplayIndex = index,
                profileDisplayId = declaration.profileDisplayId,
                profileDisplayType = display.type,
                destination = host.destination,
                topologyDisplayId = host.output.topologyDisplayId,
                outputId = host.output.outputId,
                uiSyncDisplayId = requireNotNull(host.output.uiSyncDisplayId),
                programSelection = variants.first().program,
                slotIngressAssignments = variants.first().slots,
                primaryIntent = variants.first().intent,
                additionalVariants = variants.drop(1).map { variant ->
                    CompiledUiSyncThemeVariant(variant.intent, variant.program, variant.slots,
                        variant.localCanvas, variant.instrumentReferences)
                },
                localCanvasAssignments = variants.first().localCanvas,
                instrumentReferenceAssignments = variants.first().instrumentReferences,
            )
        }
        require(assignments.map { it.profileDisplayIndex }.toSet().size == assignments.size &&
            assignments.map { it.uiSyncDisplayId }.toSet().size == assignments.size &&
            assignments.map { it.topologyDisplayId }.toSet().size == assignments.size &&
            assignments.map { it.outputId }.toSet().size == assignments.size &&
            assignments.map { it.destination }.toSet().size == assignments.size &&
            usedIngressIds == ingressById.keys
        ) { "Signed catalog contains duplicate outputs or uncovered Host ingresses" }
        val sorted = assignments.sortedBy { it.profileDisplayIndex }
        return Compiled(
            sorted,
            sorted.map { assignment ->
                hosts.single { it.profileDisplayIndex == assignment.profileDisplayIndex }.output
            },
            ingresses,
            deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, sorted),
        )
    }

    private fun parseCatalog(profile: VerifiedUltraProfile): ParsedCatalog {
        val v1Bytes = profile.candidate.content(SPEC_PATH)
        val v2Bytes = profile.candidate.content(SPEC_PATH_V2)
        val v3Bytes = profile.candidate.content(SPEC_PATH_V3)
        require(listOf(v1Bytes, v2Bytes, v3Bytes).count { it != null } == 1) {
            "Signed bench Profile needs exactly one UI Sync catalog version"
        }
        val v2 = v2Bytes != null
        val v3 = v3Bytes != null
        val specBytes = requireNotNull(v3Bytes ?: v2Bytes ?: v1Bytes)
        require(specBytes.size in 1..MAX_SPEC_BYTES) { "Signed bench UI Sync catalog size is invalid" }
        val root = objectMap(decodeThemeJson(specBytes), "bench UI Sync catalog")
        require(root.keys == setOf("format", "displays") &&
            root["format"] == when {
                v3 -> "xcertplay-debug-uisync-v3"
                v2 -> "xcertplay-debug-uisync-v2"
                else -> "xcertplay-debug-uisync-v1"
            }
        ) { "Unsupported or ambiguous bench UI Sync catalog" }

        val packageBytes = requireNotNull(profile.candidate.content(
            UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
        )) { "Verified Profile has no phone package" }
        val packageEntry = requireNotNull(profile.candidate.entries.singleOrNull {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }) { "Verified Profile has no unique phone package entry" }
        val arc = CarPlayThemeSchema.parseArcPackageJson(packageBytes)
        require(arc.extensions.isEmpty() && arc.driveModeDynamicAssignmentConfiguration == null &&
            arc.vehicleType == null && arc.vehicleTypes == null
        ) { "Active ArcPackage semantics are not executable" }
        require(arc.displays.mapNotNull { it.id }.toSet().size ==
            arc.displays.count { it.id != null }
        ) { "Phone package contains duplicate display IDs" }

        var totalVariants = 0
        var totalSelections = 0
        var totalSlots = 0
        var totalLocalCanvasSlots = 0
        val instrumentBindings = BenchInstrumentSignalProfileCompiler().compile(profile)
            ?.also { it.requireSameProfile(profile) }?.bindings.orEmpty()
        val localContentIds = HashSet<String>()
        val themeCompiler = CarPlayThemeProgramCompiler()
        val styleWitnesses = HashMap<Pair<Int, Int>, CarPlayThemeResolvedStyleWitness>()
        val rows = boundedList(root["displays"], "displays", MAX_DISPLAYS,
            nonempty = true).map { item ->
            val row = objectMap(item, "display")
            require(row.keys == DISPLAY_KEYS) { "Bench UI Sync display has missing or unknown fields" }
            val displayIndex = index(row["profileDisplayIndex"], "profileDisplayIndex")
            val display = requireNotNull(arc.displays.getOrNull(displayIndex)) {
                "Signed display index is absent from the phone package"
            }
            val signedDisplayId = nullableIdentifier(row["profileDisplayId"], "profileDisplayId")
            require(display.id == signedDisplayId) {
                "Signed display ID differs from the phone package"
            }
            val role = when (identifier(row["destinationRole"], "destinationRole")) {
                "MAIN" -> DisplayDestinationRole.MAIN
                "CLUSTER" -> DisplayDestinationRole.CLUSTER
                else -> throw IllegalArgumentException("Unsupported bench UI Sync destination role")
            }
            val supportedRole = when (display.type) {
                DisplayType.CENTER_CONSOLE -> DisplayDestinationRole.MAIN
                DisplayType.CLUSTER -> DisplayDestinationRole.CLUSTER
                DisplayType.PASSENGER, DisplayType.SECONDARY_CLUSTER -> null
            }
            require(role == supportedRole) {
                "Signed theme display type has no supported Host destination role"
            }
            val screenId = identifier(row["advertisedScreenId"], "advertisedScreenId")
            require(screenId == when (role) {
                DisplayDestinationRole.MAIN -> AirPlayInfoPlist.MAIN_UUID
                DisplayDestinationRole.CLUSTER -> AirPlayInfoPlist.ALT_UUID
                else -> error("Unsupported role")
            }) { "Signed ScreenID is not this role's advertised Host screen" }
            val declaration = BenchUiSyncDeclaredDisplay(
                profileDisplayIndex = displayIndex,
                profileDisplayId = signedDisplayId,
                advertisedScreenId = screenId,
                destination = DisplayDestination(role, DisplayDestinationId(identifier(
                    row["destinationInstanceId"], "destinationInstanceId",
                ))),
                output = UltraDisplayGraphOutput(
                    UltraTopologyDisplayId(canonicalUuid(row["topologyDisplayId"],
                        "topologyDisplayId")),
                    outputId(row["outputId"]),
                    UltraUiSyncDisplayId(identifier(row["uiSyncDisplayId"],
                        "uiSyncDisplayId")),
                ),
            )
            require(display.layouts.map { it.id }.toSet().size == display.layouts.size) {
                "Phone package has duplicate layout IDs"
            }
            val layoutRows = boundedList(row["layouts"], "layouts", MAX_VARIANTS_PER_DISPLAY,
                nonempty = true)
            totalVariants += layoutRows.size
            require(totalVariants <= MAX_TOTAL_VARIANTS) {
                "Bench UI Sync catalog has too many variants"
            }
            val layouts = layoutRows.map { layoutItem ->
                val layoutRow = objectMap(layoutItem, "layout")
                require(layoutRow.keys == if (v2 || v3) LAYOUT_KEYS_V2 else LAYOUT_KEYS) {
                    "Bench UI Sync layout has missing or unknown fields"
                }
                val layoutIndex = index(layoutRow["layoutIndex"], "layoutIndex")
                val layout = requireNotNull(display.layouts.getOrNull(layoutIndex)) {
                    "Signed layout index is absent from the phone package"
                }
                val requestedComponents = objectMap(layoutRow["activeComponents"],
                    "activeComponents")
                require(requestedComponents.size <= MAX_SELECTED_PRIMARY) {
                    "Bench UI Sync layout has too many active components"
                }
                val activeComponents = if (v3) requestedComponents.map { (zone, component) ->
                    identifier(zone, "activeComponents zone") to
                        identifier(component, "activeComponents component")
                }.toMap(LinkedHashMap()) else {
                    require(requestedComponents.isEmpty()) {
                        "Active component wire semantics require a v3 bench catalog"
                    }
                    emptyMap()
                }
                val intent = CompiledUiSyncThemeIntent(
                    layoutID = identifier(layoutRow["layoutID"], "layoutID"),
                    activeComponents = activeComponents,
                    paletteID = identifier(layoutRow["paletteID"], "paletteID"),
                )
                require(intent.layoutID == layout.id) {
                    "Signed layout ID differs from the phone package"
                }
                val selections = boundedList(layoutRow["activePrimarySelections"],
                    "activePrimarySelections", MAX_SELECTED_PRIMARY).map { selectionItem ->
                    val selection = objectMap(selectionItem, "primary selection")
                    require(selection.keys == setOf("zoneIndex", "componentIndex"))
                    CarPlayThemePrimarySelection(
                        layoutIndex,
                        index(selection["zoneIndex"], "zoneIndex"),
                        index(selection["componentIndex"], "componentIndex"),
                    )
                }
                totalSelections += selections.size
                require(totalSelections <= MAX_SELECTED_PRIMARY &&
                    selections.map { it.zoneIndex }.toSet().size == selections.size
                ) { "Bench UI Sync layout has too many or duplicate primary selections" }
                val canonicalSelections = if (v3) {
                    val resolved = CarPlayThemeActiveComponentCrosswalk.primarySelections(
                        display, layoutIndex, activeComponents,
                    )
                    require(selections.size == resolved.size &&
                        selections.toSet() == resolved.toSet()) {
                        "Signed primary selections differ from exact active-component crosswalk"
                    }
                    // UI Sync does not define array order as paint order. The identifier
                    // crosswalk supplies stable Zone order for structural proof generation.
                    resolved
                } else selections
                val state = CarPlayThemeSceneState(canonicalSelections)
                val selectedInstruments = instrumentBindings.filter {
                    it.profileDisplayIndex == displayIndex &&
                        it.spec.address.slot.component.zone.layout.layoutIndex == layoutIndex
                }
                // Only a selected layout with phone style metadata requests this opt-in. The
                // witness reparses the exact verified package/theme bytes and owns the Display
                // instance accepted by the compiler, so a same-named foreign layout cannot
                // borrow the metadata exception.
                val styleWitness = layout.layoutStyleID?.let {
                    styleWitnesses.getOrPut(displayIndex to layoutIndex) {
                        CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(
                            profile, displayIndex, layoutIndex,
                        )
                    }.also { witness ->
                        require(witness.phonePackageDigest == packageEntry.digest &&
                            witness.profileContentDigest == profile.candidate.contentDigest &&
                            witness.styleID == it &&
                            intent.paletteID == witness.defaultPaletteID
                        ) { "Phone style witness differs from the selected signed layout" }
                    }
                }
                val assessment = themeCompiler.assessExecutableStaticSubset(
                    styleWitness?.display ?: display, displayIndex, layoutIndex, state,
                    selectedInstruments.map { it.spec },
                    styleWitness,
                )
                val proof = requireNotNull(assessment.proof) {
                    "Selected signed layout has unsupported active theme semantics: " +
                        assessment.issues.joinToString(limit = 8) { it.code.name }
                }
                val slotRows = boundedList(layoutRow["slotIngresses"], "slotIngresses",
                    MAX_SLOT_BINDINGS)
                totalSlots += slotRows.size
                require(totalSlots <= MAX_SLOT_BINDINGS) {
                    "Bench UI Sync catalog has too many slot bindings"
                }
                val slotByAddress = proof.videoSlots.associateBy { video ->
                    Triple(video.address.component.zone.zoneIndex,
                        video.address.component.componentIndex, video.address.slotIndex)
                }
                require(slotByAddress.size == proof.videoSlots.size &&
                    slotRows.size == proof.videoSlots.size
                ) { "Signed slot ingress rows must exactly cover active video slots" }
                val signedSlots = slotRows.map { slotItem ->
                    val slot = objectMap(slotItem, "slot ingress")
                    require(slot.keys == SLOT_KEYS) {
                        "Bench UI Sync slot ingress has missing or unknown fields"
                    }
                    val address = Triple(
                        index(slot["zoneIndex"], "zoneIndex"),
                        index(slot["componentIndex"], "componentIndex"),
                        index(slot["slotIndex"], "slotIndex"),
                    )
                    val video = requireNotNull(slotByAddress[address]) {
                        "Signed slot ingress does not name an active Profile video slot"
                    }
                    val stream = identifier(slot["streamIdentifier"], "streamIdentifier")
                    require(video.streamIdentifier == stream) {
                        "Signed stream identifier differs from the Profile slot"
                    }
                    ParsedSlot(video, stream, UltraIngressId(identifier(
                        slot["ingressId"], "ingressId",
                    )))
                }
                require(signedSlots.map { it.video.address }.toSet().size == signedSlots.size) {
                    "Signed slot ingress repeats an active video slot"
                }
                val instrumentByAddress = selectedInstruments.associateBy { it.spec.address.slot }
                require(instrumentByAddress.size == selectedInstruments.size &&
                    proof.instrumentReferenceSlots.size == selectedInstruments.size &&
                    proof.instrumentReferenceSlots.all { reference ->
                        instrumentByAddress[reference.address]?.spec?.address?.dataIdentifier ==
                            reference.dataIdentifier
                    }
                ) { "Signed CLUSTER instrument crosswalk does not cover exact active references" }
                val instrumentReferences = proof.instrumentReferenceSlots.map {
                    reference -> CompiledUiSyncInstrumentReferenceAssignment(
                        reference.address, requireNotNull(instrumentByAddress[reference.address]).spec,
                    )
                }
                val localRows = if (v2 || v3) boundedList(layoutRow["localCanvasSlots"],
                    "localCanvasSlots", MAX_SLOT_BINDINGS) else emptyList<Any>()
                totalLocalCanvasSlots += localRows.size
                require(totalLocalCanvasSlots <= MAX_SLOT_BINDINGS &&
                    totalSlots + totalLocalCanvasSlots <= MAX_SLOT_BINDINGS) {
                    "Bench UI Sync catalog has too many combined video and local slots"
                }
                val emptyByAddress = assessment.structuralProgram.instructions
                    .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                    .filter { it.address.component.zone.layout.layoutIndex == layoutIndex &&
                        it.source == CarPlayThemeStructuralSource.Empty &&
                        it.style == SlotStyleCase.RECTANGULAR &&
                        (it.safeAreaInsets?.let { insets -> insets.top == 0.0 &&
                            insets.leading == 0.0 && insets.bottom == 0.0 &&
                            insets.trailing == 0.0 } != false) &&
                        localCanvasFitsViewport(assessment.structuralProgram.logicalSize,
                            it.frameInDisplayLogicalSpace)
                    }
                    .associateBy { Triple(it.address.component.zone.zoneIndex,
                        it.address.component.componentIndex, it.address.slotIndex) }
                val localCanvas = localRows.map { canvasItem ->
                    val canvas = objectMap(canvasItem, "local Canvas slot")
                    require(canvas.keys == LOCAL_CANVAS_KEYS) {
                        "Bench local Canvas slot has missing or unknown fields"
                    }
                    val address = Triple(index(canvas["zoneIndex"], "zoneIndex"),
                        index(canvas["componentIndex"], "componentIndex"),
                        index(canvas["slotIndex"], "slotIndex"))
                    val empty = requireNotNull(emptyByAddress[address]) {
                        "Signed local Canvas row does not name an active Empty Profile rectangle"
                    }
                    val contentId = identifier(canvas["contentId"], "contentId")
                    require(contentId.length <= 256 && localContentIds.add(contentId)) {
                        "Signed local Canvas content ID is invalid or reused"
                    }
                    CompiledUiSyncLocalCanvasAssignment(empty.address, contentId)
                }
                require(localCanvas.map { it.slot }.toSet().size == localCanvas.size) {
                    "Signed local Canvas row repeats an active Empty slot"
                }
                val localAddresses = localCanvas.map { it.slot }.toSet()
                val drawableSlots = assessment.structuralProgram.instructions
                    .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                    .filter { it.address.component.zone.layout.layoutIndex == layoutIndex &&
                        (it.source is CarPlayThemeStructuralSource.InputStream ||
                            it.address in localAddresses ||
                            it.address in instrumentReferences.map { row -> row.slot })
                    }
                drawableSlots.forEachIndexed { position, later ->
                    require(drawableSlots.subList(0, position).none { earlier ->
                        (later.address in localAddresses || earlier.address in localAddresses) &&
                            later.address.component != earlier.address.component &&
                            localCanvasRectsOverlap(later.frameInDisplayLogicalSpace,
                                earlier.frameInDisplayLogicalSpace)
                    }) { "Signed local Canvas crosses an active drawable in another component" }
                }
                ParsedLayout(intent, CompiledUiSyncThemeProgramSelection(
                    layoutIndex, state, assessment.structuralFingerprint,
                ), signedSlots, localCanvas, instrumentReferences)
            }
            require((v3 || layouts.map { it.program.layoutIndex }.toSet().size == layouts.size) &&
                layouts.map { it.intent }.toSet().size == layouts.size
            ) { "Bench UI Sync display repeats a layout or wire intent" }
            ParsedDisplay(display, declaration, layouts)
        }
        val declarations = rows.map { it.declaration }
        require(declarations.map { it.profileDisplayIndex }.toSet().size == rows.size &&
            declarations.map { it.advertisedScreenId }.toSet().size == rows.size &&
            declarations.map { it.destination }.toSet().size == rows.size &&
            declarations.map { it.output.topologyDisplayId }.toSet().size == rows.size &&
            declarations.map { it.output.outputId }.toSet().size == rows.size &&
            declarations.map { it.output.uiSyncDisplayId }.toSet().size == rows.size
        ) { "Signed bench UI Sync catalog repeats a display or output identifier" }
        val declaredIngresses = LinkedHashMap<UltraIngressId, BenchUiSyncDeclaredIngress>()
        val streamBindings = LinkedHashMap<Pair<Int, String>, UltraIngressId>()
        rows.forEach { selected ->
            val profileDisplayIndex = selected.declaration.profileDisplayIndex
            val screenId = selected.declaration.advertisedScreenId
            val streamType = when (screenId) {
                AirPlayInfoPlist.MAIN_UUID -> 110
                AirPlayInfoPlist.ALT_UUID -> 111
                else -> error("Unsupported signed ScreenID")
            }
            selected.layouts.forEach { layout ->
                layout.slots.forEach { slot ->
                    val earlierIngress = streamBindings.putIfAbsent(
                        profileDisplayIndex to slot.streamIdentifier, slot.ingressId,
                    )
                    require(earlierIngress == null || earlierIngress == slot.ingressId) {
                        "One signed Profile display stream cannot map to different ingresses"
                    }
                    val declared = BenchUiSyncDeclaredIngress(slot.ingressId, screenId, streamType)
                    val previous = declaredIngresses.putIfAbsent(slot.ingressId, declared)
                    require(previous == null || previous == declared) {
                        "One signed ingress ID cannot select different Host screens"
                    }
                }
            }
        }
        require(declaredIngresses.size <= MAX_INGRESSES) {
            "Signed bench UI Sync catalog has too many distinct ingresses"
        }
        return ParsedCatalog(
            packageEntry.digest,
            Collections.unmodifiableList(rows),
            Collections.unmodifiableList(declaredIngresses.values.sortedBy { it.id.value }),
        )
    }

    private data class Variant(
        val intent: CompiledUiSyncThemeIntent,
        val program: CompiledUiSyncThemeProgramSelection,
        val slots: List<CompiledUiSyncSlotIngressAssignment>,
        val localCanvas: List<CompiledUiSyncLocalCanvasAssignment>,
        val instrumentReferences: List<CompiledUiSyncInstrumentReferenceAssignment>,
    )

    private fun objectMap(value: Any?, label: String): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String }) {
            "$label must be an object"
        }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun boundedList(
        value: Any?,
        label: String,
        maximum: Int,
        nonempty: Boolean = false,
    ): List<*> {
        require(value is List<*> && value.size <= maximum && (!nonempty || value.isNotEmpty())) {
            "$label has invalid cardinality"
        }
        return value
    }

    private fun <T> boundedCollection(
        value: Collection<T>, maximum: Int, label: String, nonempty: Boolean = true,
    ): List<T> {
        val result = ArrayList<T>()
        value.forEach { row ->
            require(result.size < maximum) { "$label exceed $maximum entries" }
            result += row
        }
        require(!nonempty || result.isNotEmpty()) { "$label must not be empty" }
        return Collections.unmodifiableList(result)
    }

    private fun identifier(value: Any?, label: String): String {
        require(value is String && value.isNotBlank() && value.length <= 512 &&
            value.none(Char::isISOControl)
        ) { "$label is invalid" }
        return value
    }

    private fun nullableIdentifier(value: Any?, label: String): String? =
        if (value == null) null else identifier(value, label)

    private fun index(value: Any?, label: String): Int {
        val number = when (value) {
            is Long -> value
            is BigInteger -> {
                require(value.signum() >= 0 && value <= BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
                    "$label is out of range"
                }
                value.toLong()
            }
            else -> throw IllegalArgumentException("$label must be a JSON integer")
        }
        require(number in 0..Int.MAX_VALUE) { "$label is out of range" }
        return number.toInt()
    }

    private fun canonicalUuid(value: Any?, label: String): UUID {
        val source = identifier(value, label)
        val uuid = UUID.fromString(source)
        require(uuid.toString() == source) { "$label must be a canonical UUID" }
        return uuid
    }

    private fun outputId(value: Any?): UltraDisplayOutputId {
        val row = objectMap(value, "outputId")
        require(row.keys == setOf("kind", "value")) { "Signed outputId shape is invalid" }
        val id = identifier(row["value"], "outputId.value")
        return when (row["kind"]) {
            "android" -> UltraDisplayOutputId.Android(id)
            "custom" -> UltraDisplayOutputId.Custom(id)
            else -> throw IllegalArgumentException("Signed outputId kind is unsupported")
        }
    }

    private fun localCanvasFitsViewport(size: CarPlayLogicalSize, rect: CarPlayLogicalRect): Boolean =
        size.width.isFinite() && size.height.isFinite() &&
            rect.origin.x.isFinite() && rect.origin.y.isFinite() &&
            rect.size.width.isFinite() && rect.size.height.isFinite() &&
            size.width > 0.0 && size.height > 0.0 &&
            rect.size.width > 0.0 && rect.size.height > 0.0 &&
            rect.origin.x >= 0.0 && rect.origin.y >= 0.0 &&
            rect.origin.x + rect.size.width <= size.width &&
            rect.origin.y + rect.size.height <= size.height

    private fun localCanvasRectsOverlap(first: CarPlayLogicalRect, second: CarPlayLogicalRect): Boolean =
        first.origin.x < second.origin.x + second.size.width &&
            second.origin.x < first.origin.x + first.size.width &&
            first.origin.y < second.origin.y + second.size.height &&
            second.origin.y < first.origin.y + first.size.height

    companion object {
        const val SPEC_PATH = UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY +
            "xcertplay-bench-uisync-v1.json"
        const val SPEC_PATH_V2 = UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY +
            "xcertplay-bench-uisync-v2.json"
        const val SPEC_PATH_V3 = UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY +
            "xcertplay-bench-uisync-v3.json"

        fun hasCatalog(profile: VerifiedUltraProfile): Boolean =
            profile.candidate.content(SPEC_PATH) != null ||
                profile.candidate.content(SPEC_PATH_V2) != null ||
                profile.candidate.content(SPEC_PATH_V3) != null

        private const val MAX_SPEC_BYTES = 256 * 1024
        private const val MAX_DISPLAYS = 64
        private const val MAX_INGRESSES = 128
        private const val MAX_VARIANTS_PER_DISPLAY = 16
        private const val MAX_TOTAL_VARIANTS = 256
        private const val MAX_SELECTED_PRIMARY = 4096
        private const val MAX_SLOT_BINDINGS = 4096
        private val DISPLAY_KEYS = setOf(
            "profileDisplayIndex", "profileDisplayId", "advertisedScreenId",
            "destinationRole", "destinationInstanceId", "topologyDisplayId", "outputId",
            "uiSyncDisplayId", "layouts",
        )
        private val LAYOUT_KEYS = setOf(
            "layoutIndex", "layoutID", "paletteID", "activeComponents",
            "activePrimarySelections", "slotIngresses",
        )
        private val LAYOUT_KEYS_V2 = LAYOUT_KEYS + "localCanvasSlots"
        private val SLOT_KEYS = setOf(
            "zoneIndex", "componentIndex", "slotIndex", "streamIdentifier", "ingressId",
        )
        private val LOCAL_CANVAS_KEYS = setOf(
            "zoneIndex", "componentIndex", "slotIndex", "contentId",
        )
    }
}
