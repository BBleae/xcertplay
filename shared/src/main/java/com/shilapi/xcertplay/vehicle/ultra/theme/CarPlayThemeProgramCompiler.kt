package com.shilapi.xcertplay.vehicle.ultra.theme

import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentNumericMeaning
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleShape
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleSpec
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalRole
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalSemantics
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Collections

/**
 * A deliberately narrow, executable *structural* scene program. Its one supported paint
 * operation is a solid layout background; it must never be used as a UI Sync renderer-completeness
 * or OEM-authorization receipt. Other typed instructions provide deterministic scene selection,
 * geometry, and source routing while the remaining Apple theme drawing schema is established.
 */
class CarPlayThemeStructuralProgram internal constructor(
    val displayType: DisplayType,
    val displayId: String?,
    val logicalSize: CarPlayLogicalSize,
    /** Resource scale metadata; logical geometry is never multiplied by this value. */
    val assetDensity: Double,
    instructions: List<CarPlayThemeProgramInstruction>,
) {
    val instructions: List<CarPlayThemeProgramInstruction> =
        Collections.unmodifiableList(ArrayList(instructions))

    /**
     * Domain-separated SHA-256 of understood active structure, not opaque resources or
     * presentation-only metadata such as Layout.displayName.
     */
    val structuralFingerprint: CarPlayThemeStructuralFingerprint =
        CarPlayThemeStructuralFingerprint(canonicalStructuralDigest(this))
}

class CarPlayThemeStructuralFingerprint(bytes: ByteArray) {
    private val stored = bytes.copyOf()

    init {
        require(stored.size == 32) { "A structural fingerprint must contain 32 bytes" }
    }

    fun bytes(): ByteArray = stored.copyOf()
    fun hex(): String = stored.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    override fun equals(other: Any?): Boolean =
        other is CarPlayThemeStructuralFingerprint && MessageDigest.isEqual(stored, other.stored)

    override fun hashCode(): Int = stored.contentHashCode()
    override fun toString(): String = "CarPlayThemeStructuralFingerprint(${hex()})"
}

sealed interface CarPlayThemeProgramInstruction {
    val stage: CarPlayThemeSceneStage

    data class Layout(
        val address: CarPlayThemeSceneLayoutAddress,
        val type: LayoutType,
        val safeAreaInsets: CarPlayLogicalEdgeInsets,
    ) : CarPlayThemeProgramInstruction {
        override val stage = CarPlayThemeSceneStage.LAYOUT_BACKGROUND
    }

    /** Fills the complete logical display with the layout's confirmed fixed background color. */
    data class FillLayoutBackground(
        val address: CarPlayThemeSceneLayoutAddress,
        val color: CarPlayThemeColor,
    ) : CarPlayThemeProgramInstruction {
        override val stage = CarPlayThemeSceneStage.LAYOUT_BACKGROUND
    }

    data class Zone(
        val address: CarPlayThemeSceneZoneAddress,
        val type: ZoneTypeCase,
        val region: ZoneRegion?,
        val frameInDisplayLogicalSpace: CarPlayLogicalRect,
        val shape: ZoneShapeCase,
        val cornerRadius: Double?,
    ) : CarPlayThemeProgramInstruction {
        override val stage = CarPlayThemeSceneStage.ZONE_PLATTER
    }

    data class Component(
        val address: CarPlayThemeSceneComponentAddress,
        override val stage: CarPlayThemeSceneStage,
        val frameInDisplayLogicalSpace: CarPlayLogicalRect,
        val clip: CarPlayThemeStructuralClip,
        val trackable: Boolean?,
    ) : CarPlayThemeProgramInstruction

    data class Slot(
        val address: CarPlayThemeSceneSlotAddress,
        override val stage: CarPlayThemeSceneStage,
        val role: CarPlayThemeSceneSlotRole,
        val zIndex: Int,
        val frameInComponentLogicalSpace: CarPlayLogicalRect,
        val frameInDisplayLogicalSpace: CarPlayLogicalRect,
        val safeAreaInsets: CarPlayLogicalEdgeInsets?,
        val style: SlotStyleCase,
        val source: CarPlayThemeStructuralSource,
    ) : CarPlayThemeProgramInstruction

    data class DynamicOverlay(
        val address: CarPlayThemeSceneLayoutAddress,
        override val stage: CarPlayThemeSceneStage,
    ) : CarPlayThemeProgramInstruction
}

enum class CarPlayThemeStructuralClip { NONE, ZONE_SHAPE, CUSTOM_UNRESOLVED }

sealed interface CarPlayThemeStructuralSource {
    data object Empty : CarPlayThemeStructuralSource
    data class InputStream(val streamIdentifier: String) : CarPlayThemeStructuralSource
    data class PunchThrough(val reference: String, val dataIdentifier: String) :
        CarPlayThemeStructuralSource
    data class PunchThroughType(val type: String, val dataIdentifier: String?) :
        CarPlayThemeStructuralSource
    /** Historical tag for confirmed native Gauge/Indicator or unclassified opaque kinds. */
    data class LocalUnresolved(val kind: InstrumentKindCase) : CarPlayThemeStructuralSource
    /** Confirmed phone-output Gauge/Indicator, with no inferred video or paint operation. */
    data class NonNativeUnresolved(val kind: InstrumentKindCase) : CarPlayThemeStructuralSource
}

enum class CarPlayThemeProgramIssueCode {
    /** The active background has no supported fixed-color representation. */
    OPAQUE_LAYOUT_BACKGROUND,
    OPAQUE_DISPLAY_FIELD,
    OPAQUE_LAYOUT_FIELD,
    OPAQUE_ZONE_FIELD,
    OPAQUE_COMPONENT_FIELD,
    OPAQUE_SLOT_FIELD,
    UNMODELED_SLOT_ID,
    OPAQUE_INSTRUMENT_CONTENT,
    OPAQUE_SLOT_STYLE,
    CUSTOM_MASK_UNRESOLVED,
    DYNAMIC_OVERLAY_UNRESOLVED,
    INVALID_RENDER_BOUNDS,
    INVALID_SAFE_AREA,
    REDUCER_UNSUPPORTED,
    STATIC_TRANSITION_UNSUPPORTED,
    STATIC_LAYOUT_ABSENT,
    STATIC_LAYOUT_NOT_PRIMARY,
    STATIC_LAYOUT_SAFE_AREA,
    STATIC_BACKGROUND_MISSING,
    STATIC_BACKGROUND_NOT_OPAQUE,
    STATIC_ZONE_UNSUPPORTED,
    STATIC_COMPONENT_UNSUPPORTED,
    STATIC_SLOT_UNSUPPORTED,
    STATIC_SOURCE_UNSUPPORTED,
    STATIC_INSTRUMENT_REFERENCE_UNSUPPORTED,
    STATIC_DYNAMIC_OVERLAY,
    STATIC_VIDEO_OUTSIDE_LOGICAL_VIEWPORT,
    STATIC_VIDEO_LAYER_LIMIT,
    STATIC_CROSS_COMPONENT_VIDEO_OVERLAP,
}

data class CarPlayThemeProgramIssue(
    val code: CarPlayThemeProgramIssueCode,
    /** Index-based path: duplicate user-supplied IDs cannot make diagnostics ambiguous. */
    val path: String,
)

/** No raster-executable program or product fingerprint is issued from this compilation result. */
class CarPlayThemeProgramCompilation internal constructor(
    val structuralProgram: CarPlayThemeStructuralProgram,
    issues: List<CarPlayThemeProgramIssue>,
) {
    val issues: List<CarPlayThemeProgramIssue> = Collections.unmodifiableList(ArrayList(issues))

    /** Structural fidelity does not establish theme painting, data rendering or display safety. */
    val rendererReady: Boolean = false
}

/**
 * Opt-in evidence for one layoutStyleID and one exact paletteID in the phone files of a
 * verified local Profile.
 * The witnessed Display is parsed from those bytes and must be passed by identity to the
 * compiler. This admits only inert style metadata; it is not a theme renderer, an Apple/OEM
 * authorization, or evidence that an iPhone accepted the phone assets.
 */
class CarPlayThemeResolvedStyleWitness private constructor(
    val profileContentDigest: UltraProfileDigest,
    val phonePackageDigest: UltraProfileDigest,
    val phoneThemeDigest: UltraProfileDigest,
    val displayIndex: Int,
    val layoutIndex: Int,
    val styleID: String,
    val defaultPaletteID: String,
    val selectedPaletteID: String,
    val display: Display,
) {
    internal fun allows(display: Display, layoutIndex: Int, styleID: String): Boolean =
        this.display === display && this.layoutIndex == layoutIndex && this.styleID == styleID

    companion object {
        /**
         * Fails closed unless the selected color set and palette are inert, with no selected
         * color overrides or extension drawing semantics and no global wallpaper/extensions.
         * The selected palette is an exact, case-sensitive ID from the selected style's signed
         * color sets; its sole opaque color must equal the selected layout's already supported
         * background. Unselected palettes are neither interpreted nor used as recolors.
         */
        fun fromVerifiedProfile(
            profile: VerifiedUltraProfile,
            displayIndex: Int,
            layoutIndex: Int,
            paletteID: String? = null,
        ): CarPlayThemeResolvedStyleWitness {
            require(displayIndex >= 0 && layoutIndex >= 0) { "Negative phone style selection" }
            val candidate = profile.candidate
            require(profile.binding.contentDigest == candidate.contentDigest) {
                "Phone style witness has a foreign Profile binding"
            }
            fun signedFile(path: String): Pair<ByteArray, UltraProfileDigest> {
                val bytes = requireNotNull(candidate.content(path)) {
                    "Phone style witness is missing $path"
                }
                val entry = requireNotNull(candidate.entries.singleOrNull { it.name == path }) {
                    "Phone style witness has no unique $path entry"
                }
                require(entry.sizeBytes == bytes.size.toLong() &&
                    entry.digest == UltraProfileDigest(MessageDigest.getInstance("SHA-256").digest(bytes))
                ) { "Phone style witness file digest differs from verified Profile" }
                return bytes to entry.digest
            }
            val (packageBytes, packageDigest) = signedFile(
                UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
            )
            val (themeBytes, themeDigest) = signedFile(
                UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON,
            )
            val display = requireNotNull(
                CarPlayThemeSchema.parseArcPackageJson(packageBytes).displays.getOrNull(displayIndex),
            ) { "Phone style witness display is absent" }
            val layout = requireNotNull(display.layouts.getOrNull(layoutIndex)) {
                "Phone style witness layout is absent"
            }
            val styleID = requireNotNull(layout.layoutStyleID) {
                "Phone style witness layout has no style ID"
            }
            require(styleID.isNotBlank()) { "Phone style witness has a blank style ID" }
            val theme = CarPlayResolvedThemeSchema.parseJson(themeBytes)
            val colorSets = requireNotNull(theme.layoutStyles[styleID]) {
                "Phone style witness references an unknown resolved style"
            }
            val defaultPaletteID = requireNotNull(theme.defaultPaletteIDs[styleID]) {
                "Phone style witness has no default palette"
            }
            require(defaultPaletteID.isNotBlank()) {
                "Phone style witness has a blank default palette ID"
            }
            val selectedPaletteID = paletteID ?: defaultPaletteID
            require(selectedPaletteID.isNotBlank() && colorSets.containsKey(selectedPaletteID)) {
                "Phone style witness selected palette is absent from the signed style"
            }
            val palette = requireNotNull(theme.palettes[selectedPaletteID]) {
                "Phone style witness selected palette is absent"
            }
            val signedPaletteIDs = theme.layoutStyles.values.flatMap { it.keys }.toSet()
            val selectedColor = if (palette.representedColorScheme.case ==
                RepresentedColorSchemeCase.SINGLE
            ) CarPlayThemeColor.parse(
                (palette.representedColorScheme.payload.value as? Map<*, *>)?.get("color")
            ) else null
            require(theme.extensions.isEmpty() &&
                theme.layoutStyles.values.all { it.isNotEmpty() } &&
                theme.defaultPaletteIDs.keys == theme.layoutStyles.keys &&
                theme.defaultPaletteIDs.all { (id, defaultID) ->
                    theme.layoutStyles[id]?.containsKey(defaultID) == true
                } &&
                theme.palettes.keys == signedPaletteIDs &&
                theme.palettes.all { (id, signedPalette) -> signedPalette.id == id } &&
                colorSets.getValue(selectedPaletteID).isEmpty() &&
                palette.extensions.isEmpty() &&
                palette.colors.value == emptyMap<String, Any?>() &&
                palette.backgroundOverrideColor == null && palette.accentColor == null &&
                selectedColor?.alpha == 1.0 &&
                selectedColor == CarPlayThemeColor.parse(layout.backgroundColor.value) &&
                theme.defaultWallpaperIDs.isEmpty() &&
                theme.excludedWallpaperIDs.isNullOrEmpty()
            ) { "Phone style witness contains unsupported resolved drawing semantics" }
            return CarPlayThemeResolvedStyleWitness(
                candidate.contentDigest, packageDigest, themeDigest,
                displayIndex, layoutIndex, styleID, defaultPaletteID, selectedPaletteID, display,
            )
        }
    }
}

/**
 * Compiles the parts of a parsed theme that have confirmed semantics. Unsupported *active* fields
 * are reported, never treated as harmless defaults. Inactive primary components are not executed.
 * The caller must still verify package provenance; parsing and this compiler are not trust checks.
 */
class CarPlayThemeProgramCompiler(
    private val reducer: CarPlayThemeSceneReducer = CarPlayThemeSceneReducer(
        mode = CarPlayThemeSceneReducerMode.DIAGNOSTIC_ONLY,
    ),
) {
    /**
     * Proves only the static semantics accepted by UltraThemeBoundSceneExecutor.compileOutput:
     * one primary layout with zero insets and an opaque fixed fill; single rectangular zones or
     * explicitly selected mapsMedia/dca rectangular zones with empty type payload and zero radius;
     * untracked components with no clip or a selected rectangular zone-shape clip;
     * rectangular empty or plain InputStream slots without slot insets; and no unresolved active
     * compiler diagnostics or dynamic overlays. Active video frames and any selected zone clip
     * must fit the logical viewport. The common executor remains responsible for the *physical*
     * affine, integer quantization, exact clip intersection, graph, ingress, and output lifetime.
     *
     * [displayIndex] is an explicit ordinal supplied by the verified-package caller. This method
     * does not itself verify an archive, package digest, Profile signature, or authorization.
     */
    fun assessExecutableStaticSubset(
        display: Display,
        displayIndex: Int,
        layoutIndex: Int,
        state: CarPlayThemeSceneState = CarPlayThemeSceneState(),
        instrumentReferences: Collection<InstrumentSignalBundleSpec> = emptyList(),
        resolvedStyleWitness: CarPlayThemeResolvedStyleWitness? = null,
    ): CarPlayThemeExecutableStaticSubsetAssessment {
        require(displayIndex >= 0) { "Selected display index must be non-negative" }
        require(layoutIndex >= 0) { "Selected layout index must be non-negative" }
        require(instrumentReferences.size <= 16) { "Too many instrument reference declarations" }
        val compilation = compile(display, state,
            resolvedStyleWitness?.takeIf { it.displayIndex == displayIndex })
        val program = compilation.structuralProgram
        val prefix = "layouts[$layoutIndex]"
        val issues = LinkedHashSet<CarPlayThemeProgramIssue>()
        issues += compilation.issues.filter {
            it.path == "display" || it.path == prefix || it.path.startsWith("$prefix.")
        }
        if (state.transitionActive) {
            issues += CarPlayThemeProgramIssue(
                CarPlayThemeProgramIssueCode.STATIC_TRANSITION_UNSUPPORTED, prefix,
            )
        }
        val selectedLayout = program.instructions.filterIsInstance<CarPlayThemeProgramInstruction.Layout>()
            .filter { it.address.layoutIndex == layoutIndex }
        if (selectedLayout.size != 1) {
            issues += CarPlayThemeProgramIssue(
                CarPlayThemeProgramIssueCode.STATIC_LAYOUT_ABSENT, prefix,
            )
        }
        val layout = selectedLayout.singleOrNull()
        if (layout != null) {
            if (layout.type != LayoutType.PRIMARY) {
                issues += CarPlayThemeProgramIssue(
                    CarPlayThemeProgramIssueCode.STATIC_LAYOUT_NOT_PRIMARY, prefix,
                )
            }
            if (!zeroInsets(layout.safeAreaInsets)) {
                issues += CarPlayThemeProgramIssue(
                    CarPlayThemeProgramIssueCode.STATIC_LAYOUT_SAFE_AREA, "$prefix.safeAreaInsets",
                )
            }
        }
        val selected = program.instructions.filter { instruction -> when (instruction) {
            is CarPlayThemeProgramInstruction.Layout ->
                instruction.address.layoutIndex == layoutIndex
            is CarPlayThemeProgramInstruction.FillLayoutBackground ->
                instruction.address.layoutIndex == layoutIndex
            is CarPlayThemeProgramInstruction.Zone ->
                instruction.address.layout.layoutIndex == layoutIndex
            is CarPlayThemeProgramInstruction.Component ->
                instruction.address.zone.layout.layoutIndex == layoutIndex
            is CarPlayThemeProgramInstruction.Slot ->
                instruction.address.component.zone.layout.layoutIndex == layoutIndex
            is CarPlayThemeProgramInstruction.DynamicOverlay ->
                instruction.address.layoutIndex == layoutIndex
        } }
        val backgrounds = selected.filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>()
        if (backgrounds.size != 1) {
            issues += CarPlayThemeProgramIssue(
                CarPlayThemeProgramIssueCode.STATIC_BACKGROUND_MISSING, "$prefix.backgroundColor",
            )
        } else if (backgrounds.single().color.alpha != 1.0) {
            issues += CarPlayThemeProgramIssue(
                CarPlayThemeProgramIssueCode.STATIC_BACKGROUND_NOT_OPAQUE,
                "$prefix.backgroundColor",
            )
        }
        val videoSlots = ArrayList<CarPlayThemeExecutableVideoSlot>()
        val instrumentSlots = ArrayList<CarPlayThemeExecutableInstrumentReferenceSlot>()
        val zoneByAddress = selected.filterIsInstance<CarPlayThemeProgramInstruction.Zone>()
            .associateBy { it.address }
        val componentByAddress = selected.filterIsInstance<CarPlayThemeProgramInstruction.Component>()
            .associateBy { it.address }
        val selectedReferences = instrumentReferences.filter {
            it.address.slot.component.zone.layout.layoutIndex == layoutIndex
        }
        if (selectedReferences.size > 1 ||
            selectedReferences.map { it.address.slot }.toSet().size != selectedReferences.size
        ) {
            issues += CarPlayThemeProgramIssue(
                CarPlayThemeProgramIssueCode.STATIC_INSTRUMENT_REFERENCE_UNSUPPORTED, prefix,
            )
        }
        val referenceBySlot = selectedReferences.associateBy { it.address.slot }
        selected.forEach { instruction -> when (instruction) {
            is CarPlayThemeProgramInstruction.Layout,
            is CarPlayThemeProgramInstruction.FillLayoutBackground -> Unit
            is CarPlayThemeProgramInstruction.Zone -> {
                val explicitCarouselSelection = state.activePrimarySelections.any {
                    it.layoutIndex == layoutIndex && it.zoneIndex == instruction.address.zoneIndex
                }
                val supportedType = when (instruction.type) {
                    ZoneTypeCase.SINGLE -> true
                    ZoneTypeCase.MAPS_MEDIA, ZoneTypeCase.DCA -> explicitCarouselSelection
                    ZoneTypeCase.DECK -> false
                }
                if (!supportedType ||
                    instruction.shape != ZoneShapeCase.RECTANGULAR ||
                    instruction.cornerRadius != 0.0
                ) {
                    issues += CarPlayThemeProgramIssue(
                        CarPlayThemeProgramIssueCode.STATIC_ZONE_UNSUPPORTED,
                        zonePath(instruction.address),
                    )
                }
            }
            is CarPlayThemeProgramInstruction.Component -> {
                val zone = zoneByAddress[instruction.address.zone]
                val supportedClip = when (instruction.clip) {
                    CarPlayThemeStructuralClip.NONE -> true
                    CarPlayThemeStructuralClip.ZONE_SHAPE -> zone != null &&
                        zone.type != ZoneTypeCase.DECK &&
                        zone.shape == ZoneShapeCase.RECTANGULAR &&
                        zone.cornerRadius == 0.0 &&
                        logicalViewportContains(program.logicalSize,
                            zone.frameInDisplayLogicalSpace)
                    CarPlayThemeStructuralClip.CUSTOM_UNRESOLVED -> false
                }
                if (!supportedClip || instruction.trackable == true) {
                    issues += CarPlayThemeProgramIssue(
                        CarPlayThemeProgramIssueCode.STATIC_COMPONENT_UNSUPPORTED,
                        componentPath(instruction.address),
                    )
                }
            }
            is CarPlayThemeProgramInstruction.Slot -> {
                val path = slotPath(instruction.address)
                val reference = referenceBySlot[instruction.address]
                val admittedInstrument = reference?.let {
                    validateInstrumentReferenceSemantics(display, instruction, it)
                }
                if ((admittedInstrument == null && instruction.style != SlotStyleCase.RECTANGULAR) ||
                    (instruction.safeAreaInsets?.let(::zeroInsets) == false)
                ) {
                    issues += CarPlayThemeProgramIssue(
                        CarPlayThemeProgramIssueCode.STATIC_SLOT_UNSUPPORTED, path,
                    )
                }
                when (val source = instruction.source) {
                    CarPlayThemeStructuralSource.Empty -> Unit
                    is CarPlayThemeStructuralSource.InputStream -> {
                        if (!logicalViewportContains(program.logicalSize,
                                instruction.frameInDisplayLogicalSpace)) {
                            issues += CarPlayThemeProgramIssue(
                                CarPlayThemeProgramIssueCode.STATIC_VIDEO_OUTSIDE_LOGICAL_VIEWPORT,
                                path,
                            )
                        }
                        val component = componentByAddress[instruction.address.component]
                        if (component == null) {
                            issues += CarPlayThemeProgramIssue(
                                CarPlayThemeProgramIssueCode.STATIC_COMPONENT_UNSUPPORTED,
                                componentPath(instruction.address.component),
                            )
                        }
                        val zoneClip = if (component?.clip == CarPlayThemeStructuralClip.ZONE_SHAPE)
                            zoneByAddress[instruction.address.component.zone]
                                ?.frameInDisplayLogicalSpace
                        else null
                        videoSlots += CarPlayThemeExecutableVideoSlot(
                            instruction.address, source.streamIdentifier, instruction.stage,
                            instruction.zIndex, instruction.frameInDisplayLogicalSpace,
                            zoneClip,
                        )
                    }
                    is CarPlayThemeStructuralSource.LocalUnresolved -> {
                        if (admittedInstrument == null) {
                            issues += CarPlayThemeProgramIssue(
                                CarPlayThemeProgramIssueCode.STATIC_SOURCE_UNSUPPORTED, path,
                            )
                        } else {
                            // Only this exact selected native slot's opaque-content diagnostic
                            // is discharged. Unknown presentation/clip or sibling diagnostics
                            // remain fatal; no global OPAQUE_INSTRUMENT_CONTENT bypass exists.
                            issues.remove(CarPlayThemeProgramIssue(
                                CarPlayThemeProgramIssueCode.OPAQUE_INSTRUMENT_CONTENT, path,
                            ))
                            instrumentSlots += admittedInstrument
                        }
                    }
                    else -> issues += CarPlayThemeProgramIssue(
                        CarPlayThemeProgramIssueCode.STATIC_SOURCE_UNSUPPORTED, path,
                    )
                }
            }
            is CarPlayThemeProgramInstruction.DynamicOverlay -> {
                issues += CarPlayThemeProgramIssue(
                    CarPlayThemeProgramIssueCode.STATIC_DYNAMIC_OVERLAY,
                    "$prefix.${instruction.stage}",
                )
            }
        } }
        if (instrumentSlots.size != selectedReferences.size) {
            issues += CarPlayThemeProgramIssue(
                CarPlayThemeProgramIssueCode.STATIC_INSTRUMENT_REFERENCE_UNSUPPORTED, prefix,
            )
        }
        // The reducer establishes z-index order inside one Component but does not establish how
        // two Components' slot z-indices interleave within the same stage. The common executor
        // currently flattens these slots in program order; issuing a proof for cross-component
        // overlap would silently choose paint precedence that the theme model has not confirmed.
        // A positive-area intersection matters; touching edges do not paint the same pixel.
        if (videoSlots.size > MAX_STATIC_VIDEO_LAYERS) {
            issues += CarPlayThemeProgramIssue(
                CarPlayThemeProgramIssueCode.STATIC_VIDEO_LAYER_LIMIT, prefix,
            )
        } else {
            videoSlots.forEachIndexed { index, later ->
                val earlier = videoSlots.subList(0, index).firstOrNull { prior ->
                    prior.stage == later.stage &&
                        prior.address.component != later.address.component &&
                        positiveAreaIntersection(prior.frameInDisplayLogicalSpace,
                            later.frameInDisplayLogicalSpace)
                }
                if (earlier != null) {
                    issues += CarPlayThemeProgramIssue(
                        CarPlayThemeProgramIssueCode.STATIC_CROSS_COMPONENT_VIDEO_OVERLAP,
                        "${slotPath(later.address)} overlaps ${slotPath(earlier.address)}",
                    )
                }
            }
        }
        val proof = if (issues.isEmpty()) {
            CarPlayThemeExecutableStaticSubsetProof(
                displayIndex, program.displayType, program.displayId,
                requireNotNull(layout).address,
                state.activePrimarySelections, program.structuralFingerprint,
                backgrounds.single().color, videoSlots, instrumentSlots,
            )
        } else null
        return CarPlayThemeExecutableStaticSubsetAssessment(program, issues, proof)
    }

    fun compile(
        display: Display,
        state: CarPlayThemeSceneState = CarPlayThemeSceneState(),
        resolvedStyleWitness: CarPlayThemeResolvedStyleWitness? = null,
    ): CarPlayThemeProgramCompilation {
        val reduction = reducer.reduce(display, state) as? CarPlayThemeSceneReduction.DiagnosticPlan
            ?: throw CarPlayThemeSceneReductionException("Theme program compiler requires an enabled scene reducer")
        val scene = reduction.scene
        val issues = ArrayList<CarPlayThemeProgramIssue>()
        val instructions = ArrayList<CarPlayThemeProgramInstruction>()
        val size = display.logicalSize()
        if (!validSize(size) || !validAssetRasterScale(size, display.density)) {
            issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.INVALID_RENDER_BOUNDS, "display")
        }
        if (display.extensions.isNotEmpty() || display.contentCornerRadius != null ||
            display.allowManufacturerAppIcon || display.requiresDarkAppearance ||
            display.reversesDynamicContentDPadScrolling != null ||
            display.sendsButtonPressOnTouchDown != null || listOf(
                display.localNotifications, display.shadows, display.vectorFontManager,
                display.localizationManager, display.iPhoneVideoPosition, display.iPhoneVideoMask,
                display.backgroundColor, display.contentCornerColor, display.pairingLogoAnimation,
                display.driveModeLayoutIDs, display.assetBundle, display.remoteUIAdditionalPhases,
                display.wallpaperOverlay, display.wallpaperGlassMask, display.background,
                display.shapeMask, display.interior, display.wheelOcclusion,
                display.manifestEntries, display.visibilityRules,
            ).any { it != null }
        ) {
            issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.OPAQUE_DISPLAY_FIELD, "display")
        }

        scene.layouts.forEach { sceneLayout ->
            val address = sceneLayout.address
            val layout = display.layouts[address.layoutIndex]
            val path = "layouts[${address.layoutIndex}]"
            val safeArea = layout.logicalSafeAreaInsets()
            if (!validInsets(safeArea, size)) {
                issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.INVALID_SAFE_AREA, path)
            }
            instructions += CarPlayThemeProgramInstruction.Layout(address, layout.layoutType, safeArea)
            val backgroundColor = CarPlayThemeColor.parse(layout.backgroundColor.value)
            if (backgroundColor == null) {
                issues += CarPlayThemeProgramIssue(
                    CarPlayThemeProgramIssueCode.OPAQUE_LAYOUT_BACKGROUND, "$path.backgroundColor",
                )
            } else {
                instructions += CarPlayThemeProgramInstruction.FillLayoutBackground(address, backgroundColor)
            }
            if (layout.extensions.isNotEmpty() || layout.systemUILayout != null ||
                layout.preview != null || layout.wallpapers != null ||
                (layout.layoutStyleID != null &&
                    resolvedStyleWitness?.allows(display, address.layoutIndex,
                        layout.layoutStyleID) != true) ||
                layout.driveModeThemeConfigurations.value != emptyMap<String, Any?>()
            ) {
                issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.OPAQUE_LAYOUT_FIELD, path)
            }

            CarPlayThemeSceneStage.entries.drop(1).forEach { stage ->
                sceneLayout.stage(stage).nodes.forEach { node ->
                    when (node) {
                        is CarPlayThemeZonePlatterNode -> {
                            val zoneAddress = node.zone
                            val zone = layout.zones[zoneAddress.zoneIndex]
                            val nodePath = "$path.zones[${zoneAddress.zoneIndex}]"
                            val rect = node.frameInDisplayLogicalSpace
                            if (!validRect(rect) || (node.zoneShape.cornerRadius != null &&
                                    (node.zoneShape.cornerRadius < 0.0 ||
                                        node.zoneShape.cornerRadius >
                                        minOf(rect.size.width, rect.size.height) / 2.0))) {
                                issues += CarPlayThemeProgramIssue(
                                    CarPlayThemeProgramIssueCode.INVALID_RENDER_BOUNDS, nodePath,
                                )
                            }
                            instructions += CarPlayThemeProgramInstruction.Zone(
                                zoneAddress, zone.zoneType.case, zone.zoneRegion, rect,
                                node.zoneShape.case, node.zoneShape.cornerRadius,
                            )
                            if (zone.extensions.isNotEmpty() || zone.verifierIdentifier != null ||
                                zone.zoneType.payload.value != emptyMap<String, Any?>() ||
                                listOf(zone.platterConfiguration, zone.padding, zone.visibilityConfiguration).any { it != null }
                            ) {
                                issues += CarPlayThemeProgramIssue(
                                    CarPlayThemeProgramIssueCode.OPAQUE_ZONE_FIELD, nodePath,
                                )
                            }
                        }
                        is CarPlayThemePrimaryComponentNode -> {
                            compileComponent(node.component, node.stage, node.frameInDisplayLogicalSpace,
                                node.componentClip, display, "$path.zones[${node.component.zone.zoneIndex}]" ,
                                state.transitionActive,
                                instructions, issues)
                        }
                        is CarPlayThemeOverlayComponentNode -> {
                            compileComponent(node.component, node.stage, node.frameInDisplayLogicalSpace,
                                node.componentClip, display, "$path.zones[${node.component.zone.zoneIndex}]" ,
                                state.transitionActive,
                                instructions, issues)
                        }
                        is CarPlayThemeSlotNode -> compileSlot(node, display, instructions, issues)
                        is CarPlayThemeTrackingOverlayNode,
                        is CarPlayThemeAppearanceOverlayNode -> {
                            instructions += CarPlayThemeProgramInstruction.DynamicOverlay(address, stage)
                            issues += CarPlayThemeProgramIssue(
                                CarPlayThemeProgramIssueCode.DYNAMIC_OVERLAY_UNRESOLVED, "$path.$stage",
                            )
                        }
                        is CarPlayThemeLayoutBackgroundNode -> error("Layout background was emitted twice")
                    }
                }
            }
        }
        scene.unsupportedDiagnostics.forEach { diagnostic ->
            issues += CarPlayThemeProgramIssue(
                CarPlayThemeProgramIssueCode.REDUCER_UNSUPPORTED,
                slotPath(diagnostic.slot) + ".${diagnostic.reason}",
            )
        }
        return CarPlayThemeProgramCompilation(
            CarPlayThemeStructuralProgram(scene.displayType, scene.displayId, size,
                display.density, instructions),
            issues,
        )
    }

    private fun compileComponent(
        address: CarPlayThemeSceneComponentAddress,
        stage: CarPlayThemeSceneStage,
        frame: CarPlayLogicalRect,
        clip: CarPlayThemeComponentClipPlan,
        display: Display,
        zonePath: String,
        transitionActive: Boolean,
        instructions: MutableList<CarPlayThemeProgramInstruction>,
        issues: MutableList<CarPlayThemeProgramIssue>,
    ) {
        val component = display.layouts[address.zone.layout.layoutIndex]
            .zones[address.zone.zoneIndex].components[address.componentIndex]
        val path = "$zonePath.components[${address.componentIndex}]"
        if (!validRect(frame)) {
            issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.INVALID_RENDER_BOUNDS, path)
        }
        val structuralClip = when (clip) {
            CarPlayThemeComponentClipPlan.None -> CarPlayThemeStructuralClip.NONE
            is CarPlayThemeComponentClipPlan.ZoneShape -> CarPlayThemeStructuralClip.ZONE_SHAPE
            is CarPlayThemeComponentClipPlan.Custom -> {
                issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.CUSTOM_MASK_UNRESOLVED, path)
                CarPlayThemeStructuralClip.CUSTOM_UNRESOLVED
            }
        }
        instructions += CarPlayThemeProgramInstruction.Component(
            address, stage, frame, structuralClip, component.trackable,
        )
        val clipping = component.clippingBehavior
        val selectedStyle = if (transitionActive) clipping?.transitioning else clipping?.notTransitioning
        if (component.extensions.isNotEmpty() || clipping?.extensions?.isNotEmpty() == true ||
            (selectedStyle != null && selectedStyle.payload.value != emptyMap<String, Any?>())) {
            issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.OPAQUE_COMPONENT_FIELD, path)
        }
    }

    private fun compileSlot(
        node: CarPlayThemeSlotNode,
        display: Display,
        instructions: MutableList<CarPlayThemeProgramInstruction>,
        issues: MutableList<CarPlayThemeProgramIssue>,
    ) {
        val address = node.slot
        val component = display.layouts[address.component.zone.layout.layoutIndex]
            .zones[address.component.zone.zoneIndex].components[address.component.componentIndex]
        val slot = component.slots[address.slotIndex]
        val path = slotPath(address)
        val safeArea = slot.logicalSafeAreaInsets()
        if (!validRect(node.frameInComponentLogicalSpace) ||
            !validRect(node.frameInDisplayLogicalSpace)) {
            issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.INVALID_RENDER_BOUNDS, path)
        }
        if (safeArea != null && !validInsets(safeArea, node.frameInComponentLogicalSpace.size)) {
            issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.INVALID_SAFE_AREA, path)
        }
        val source = when (val symbolic = node.source) {
            CarPlayThemeSlotSource.Empty -> CarPlayThemeStructuralSource.Empty
            is CarPlayThemeSlotSource.InputStream ->
                CarPlayThemeStructuralSource.InputStream(symbolic.streamIdentifier)
            is CarPlayThemeSlotSource.PunchThrough.Reference ->
                CarPlayThemeStructuralSource.PunchThrough(symbolic.reference,
                    symbolic.instrumentDataIdentifier)
            is CarPlayThemeSlotSource.PunchThrough.Typed ->
                CarPlayThemeStructuralSource.PunchThroughType(symbolic.punchThroughType,
                    symbolic.instrumentDataIdentifier)
            is CarPlayThemeSlotSource.LocalOpaqueInstrument ->
                CarPlayThemeStructuralSource.LocalUnresolved(symbolic.kind)
            is CarPlayThemeSlotSource.NonNativeUnresolvedInstrument ->
                CarPlayThemeStructuralSource.NonNativeUnresolved(symbolic.kind)
        }
        instructions += CarPlayThemeProgramInstruction.Slot(
            address, node.stage, node.role, node.zIndex, node.frameInComponentLogicalSpace,
            node.frameInDisplayLogicalSpace, safeArea, node.style.case, source,
        )
        if (slot.id != null) {
            issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.UNMODELED_SLOT_ID, path)
        }
        if (slot.extensions.isNotEmpty() || slot.requirement != null || slot.overrides != null) {
            issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.OPAQUE_SLOT_FIELD, path)
        }
        if (node.style.payload.value != emptyMap<String, Any?>()) {
            issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.OPAQUE_SLOT_STYLE, path)
        }
        if (slot.instrument != null && !isPlainTransparentInputStream(slot.instrument, node)) {
            issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.OPAQUE_INSTRUMENT_CONTENT, path)
        }
        if (node.instrumentOuterClip is CarPlayThemeInstrumentOuterClipPlan.UnknownFromInstrumentConfiguration ||
            node.inputStreamPresentation != null) {
            issues += CarPlayThemeProgramIssue(CarPlayThemeProgramIssueCode.OPAQUE_INSTRUMENT_CONTENT,
                "$path.presentation")
        }
    }

    /**
     * The only instrument whose visible behavior is fully represented by a rectangular video
     * layer. A required but transparent instrument background contributes no omitted pixels.
     * Every other field remains an active, unresolved drawing or visibility instruction.
     */
    private fun isPlainTransparentInputStream(
        instrument: Instrument,
        node: CarPlayThemeSlotNode,
    ): Boolean {
        val payload = instrument.kind.typedPayload as? InputStreamInstrumentKindPayload
            ?: return false
        val background = CarPlayThemeColor.parse(instrument.backgroundColor.value) ?: return false
        return instrument.kind.case == InstrumentKindCase.INPUT_STREAM &&
            payload.visibilityConfiguration == null && payload.presentation == null &&
            background.alpha == 0.0 && instrument.id == null &&
            instrument.verifierIdentifier == null && instrument.shadowID == null &&
            instrument.persistent == null && instrument.blendMode == null &&
            instrument.layoutConfiguration == null && instrument.configuration == null &&
            instrument.backgroundContent == null && instrument.clipsToShape?.value == false &&
            instrument.extensions.isEmpty() &&
            node.instrumentOuterClip == CarPlayThemeInstrumentOuterClipPlan.None &&
            node.inputStreamPresentation == null
    }
}

/**
 * A semantic admission for a *diagnostic reference*, never an implementation of Apple's native
 * Gauge/Indicator pixels. The signed crosswalk and CAF tree are checked again by the issuer; this
 * method can only prove that the one selected Theme source has the narrow known shape. Physical
 * quantization is intentionally deferred until a real output viewport exists.
 */
private fun validateInstrumentReferenceSemantics(
    display: Display,
    slotInstruction: CarPlayThemeProgramInstruction.Slot,
    spec: InstrumentSignalBundleSpec,
): CarPlayThemeExecutableInstrumentReferenceSlot? {
    val address = slotInstruction.address
    if (display.type != DisplayType.CLUSTER || spec.address.slot != address ||
        slotInstruction.stage != CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER ||
        slotInstruction.zIndex != 0 || slotInstruction.safeAreaInsets != null ||
        !logicalViewportContains(display.logicalSize(), slotInstruction.frameInDisplayLogicalSpace)
    ) return null
    val layout = display.layouts.getOrNull(address.component.zone.layout.layoutIndex)
        ?: return null
    val zone = layout.zones.getOrNull(address.component.zone.zoneIndex) ?: return null
    val component = zone.components.getOrNull(address.component.componentIndex) ?: return null
    val slot = component.slots.getOrNull(address.slotIndex) ?: return null
    if (layout.id != address.component.zone.layout.layoutId ||
        layout.layoutType != LayoutType.PRIMARY || layout.zones.size != 1 ||
        zone.id != address.component.zone.zoneId || zone.zoneRegion != spec.address.zoneRegion ||
        zone.zoneType.case != ZoneTypeCase.SINGLE || zone.components.size != 1 ||
        component.id != address.component.componentId ||
        component.type != ComponentType.PRIMARY || component.slots.size != 1 ||
        component.clippingBehavior != null || slot.positionId != address.positionId ||
        slot.zIndex != 0 || slot.style.payload.value != emptyMap<String, Any?>() ||
        slot.safeAreaInsets != null || slot.requirement != null || slot.overrides != null ||
        slot.extensions.isNotEmpty()
    ) return null
    val instrument = slot.instrument ?: return null
    if (instrument.id != null || instrument.verifierIdentifier != null ||
        instrument.shadowID != null || instrument.persistent != null ||
        instrument.blendMode != null || instrument.layoutConfiguration != null ||
        instrument.configuration != null || instrument.backgroundContent != null ||
        instrument.clipsToShape?.value != false || instrument.extensions.isNotEmpty() ||
        CarPlayThemeColor.parse(instrument.backgroundColor.value)?.alpha != 1.0
    ) return null
    val kind = instrument.kind.case
    if (slotInstruction.source != CarPlayThemeStructuralSource.LocalUnresolved(kind)) return null
    val supported = when (val typed = instrument.kind.typedPayload) {
        is GaugeInstrumentKindPayload -> {
            val gauge = typed.configuration
            val circular = gauge.style.typedPayload as? CircularGaugeStylePayload
            val config = circular?.config
            kind == InstrumentKindCase.GAUGE &&
                gauge.rendersLocally && gauge.extensions.isEmpty() &&
                gauge.visibilityConfiguration.value == emptyMap<String, Any?>() &&
                gauge.style.case == GaugeStyleCase.CIRCULAR &&
                slot.style.case == SlotStyleCase.CIRCULAR && config != null &&
                config.dataIdentifier.value == spec.address.dataIdentifier &&
                config.range.lowerBound.isFinite() && config.range.upperBound.isFinite() &&
                config.range.lowerBound < config.range.upperBound &&
                config.extensions.isEmpty() &&
                config.iconName.value is String && config.minLabel.value is String &&
                config.maxLabel.value is String && config.minLabelColor.value is String &&
                config.maxLabelColor.value is String &&
                config.tintColors.value == emptyList<Any?>() &&
                config.backgroundTintColors.value == emptyList<Any?>() &&
                when (spec.shape) {
                    InstrumentSignalBundleShape.SPEED_TRIPLET ->
                        spec.numericMeaning(InstrumentSignalRole.SPEED)?.first ==
                            InstrumentNumericMeaning.PROFILE_SPEED &&
                            spec.numericMeaning(InstrumentSignalRole.SPEED_KMH) ==
                            (InstrumentNumericMeaning.KILOMETRES_PER_HOUR to "km/h") &&
                            spec.numericMeaning(InstrumentSignalRole.SPEED_MPH) ==
                            (InstrumentNumericMeaning.MILES_PER_HOUR to "mph")
                    InstrumentSignalBundleShape.RPM_VALUE_AND_STATE ->
                        spec.numericMeaning(InstrumentSignalRole.RPM_VALUE) ==
                            (InstrumentNumericMeaning.REVOLUTIONS_PER_MINUTE to "rpm") &&
                            spec.sources.singleOrNull { it.role == InstrumentSignalRole.RPM_STATE }
                                ?.semantics is InstrumentSignalSemantics.State
                    InstrumentSignalBundleShape.INDICATOR_STATE -> false
                }
        }
        is IndicatorInstrumentKindPayload -> {
            val config = typed.configuration
            kind == InstrumentKindCase.INDICATOR &&
                spec.shape == InstrumentSignalBundleShape.INDICATOR_STATE &&
                slot.style.case == SlotStyleCase.RECTANGULAR &&
                config.dataIdentifier.value == spec.address.dataIdentifier &&
                config.style.case == IndicatorStyleCase.CRITICAL &&
                config.style.rendersLocally &&
                config.style.associatedConfiguration?.value == emptyMap<String, Any?>() &&
                config.presentationEdge.value == "bottom" &&
                config.containerInsets.value == emptyMap<String, Any?>() &&
                config.vehicleLanguageIdentifier == null && config.extensions.isEmpty() &&
                spec.sources.singleOrNull()?.semantics is InstrumentSignalSemantics.State
        }
        else -> false
    }
    return if (supported) CarPlayThemeExecutableInstrumentReferenceSlot(
        address, kind, spec.address.dataIdentifier, slotInstruction.stage,
        slotInstruction.zIndex, slotInstruction.frameInDisplayLogicalSpace,
    ) else null
}

private fun InstrumentSignalBundleSpec.numericMeaning(role: InstrumentSignalRole):
    Pair<InstrumentNumericMeaning, String>? =
    (sources.singleOrNull { it.role == role }?.semantics as?
        InstrumentSignalSemantics.Numeric)?.let { it.meaning to it.exactCafUnits }

private fun slotPath(address: CarPlayThemeSceneSlotAddress): String =
    "layouts[${address.component.zone.layout.layoutIndex}].zones[${address.component.zone.zoneIndex}]" +
        ".components[${address.component.componentIndex}].slots[${address.slotIndex}]"

private fun componentPath(address: CarPlayThemeSceneComponentAddress): String =
    "layouts[${address.zone.layout.layoutIndex}].zones[${address.zone.zoneIndex}]" +
        ".components[${address.componentIndex}]"

private fun zonePath(address: CarPlayThemeSceneZoneAddress): String =
    "layouts[${address.layout.layoutIndex}].zones[${address.zoneIndex}]"

private fun zeroInsets(value: CarPlayLogicalEdgeInsets): Boolean =
    value.top == 0.0 && value.leading == 0.0 &&
        value.bottom == 0.0 && value.trailing == 0.0

private fun logicalViewportContains(size: CarPlayLogicalSize, rect: CarPlayLogicalRect): Boolean =
    validSize(size) && validRect(rect) && rect.origin.x >= 0.0 && rect.origin.y >= 0.0 &&
        rect.origin.x + rect.size.width <= size.width &&
        rect.origin.y + rect.size.height <= size.height

private const val MAX_STATIC_VIDEO_LAYERS = 4096

private fun positiveAreaIntersection(
    first: CarPlayLogicalRect,
    second: CarPlayLogicalRect,
): Boolean {
    if (!validRect(first) || !validRect(second)) return false
    return first.origin.x < second.origin.x + second.size.width &&
        second.origin.x < first.origin.x + first.size.width &&
        first.origin.y < second.origin.y + second.size.height &&
        second.origin.y < first.origin.y + first.size.height
}

private const val MAX_LOGICAL_COORDINATE = 1_000_000.0
private const val MAX_RESOURCE_RASTER_EDGE = 16_384.0
private const val MAX_RESOURCE_RASTER_PIXELS = 64_000_000.0

private fun validSize(size: CarPlayLogicalSize): Boolean =
    size.width.isFinite() && size.height.isFinite() &&
        size.width > 0.0 && size.height > 0.0 &&
        size.width <= MAX_LOGICAL_COORDINATE && size.height <= MAX_LOGICAL_COORDINATE &&
        (size.width * size.height).isFinite() &&
        size.width * size.height <= MAX_RESOURCE_RASTER_PIXELS

/** Resource-scale budget only; physical output mapping never uses Android DisplayMetrics density. */
private fun validAssetRasterScale(size: CarPlayLogicalSize, assetDensity: Double): Boolean {
    if (!validSize(size) || !assetDensity.isFinite() || assetDensity <= 0.0) return false
    val width = size.width * assetDensity
    val height = size.height * assetDensity
    return width.isFinite() && height.isFinite() &&
        width <= MAX_RESOURCE_RASTER_EDGE && height <= MAX_RESOURCE_RASTER_EDGE &&
        (width * height).isFinite() && width * height <= MAX_RESOURCE_RASTER_PIXELS
}

private fun validRect(rect: CarPlayLogicalRect): Boolean =
    rect.origin.x.isFinite() && rect.origin.y.isFinite() &&
        kotlin.math.abs(rect.origin.x) <= MAX_LOGICAL_COORDINATE &&
        kotlin.math.abs(rect.origin.y) <= MAX_LOGICAL_COORDINATE && validSize(rect.size) &&
        (rect.origin.x + rect.size.width).isFinite() &&
        (rect.origin.y + rect.size.height).isFinite() &&
        kotlin.math.abs(rect.origin.x + rect.size.width) <= MAX_LOGICAL_COORDINATE &&
        kotlin.math.abs(rect.origin.y + rect.size.height) <= MAX_LOGICAL_COORDINATE

private fun validInsets(insets: CarPlayLogicalEdgeInsets, size: CarPlayLogicalSize): Boolean =
    listOf(insets.top, insets.leading, insets.bottom, insets.trailing).all {
        it.isFinite() && it >= 0.0
    } && insets.top + insets.bottom <= size.height &&
        insets.leading + insets.trailing <= size.width

private fun canonicalStructuralDigest(program: CarPlayThemeStructuralProgram): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val output = DataOutputStream(object : OutputStream() {
        override fun write(value: Int) = digest.update(value.toByte())
        override fun write(bytes: ByteArray, offset: Int, length: Int) =
            digest.update(bytes, offset, length)
    })
    fun text(value: String?) {
        if (value == null) {
            output.writeInt(-1)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
        }
    }
    fun number(value: Double) = output.writeLong(java.lang.Double.doubleToLongBits(
        if (value == 0.0) 0.0 else value,
    ))
    fun point(value: CarPlayLogicalPoint) { number(value.x); number(value.y) }
    fun size(value: CarPlayLogicalSize) { number(value.width); number(value.height) }
    fun rect(value: CarPlayLogicalRect) { point(value.origin); size(value.size) }
    fun insets(value: CarPlayLogicalEdgeInsets?) {
        output.writeBoolean(value != null)
        if (value != null) {
            number(value.top); number(value.leading); number(value.bottom); number(value.trailing)
        }
    }
    // Ancestor strings appear only in their own declaration. Repeating a large layout ID in
    // every descendant would turn a bounded 2 MiB schema input into unbounded digest work.
    fun layoutIndex(address: CarPlayThemeSceneLayoutAddress) = output.writeInt(address.layoutIndex)
    fun zoneIndices(address: CarPlayThemeSceneZoneAddress) {
        layoutIndex(address.layout); output.writeInt(address.zoneIndex)
    }
    fun componentIndices(address: CarPlayThemeSceneComponentAddress) {
        zoneIndices(address.zone); output.writeInt(address.componentIndex)
    }
    fun slotIndices(address: CarPlayThemeSceneSlotAddress) {
        componentIndices(address.component); output.writeInt(address.slotIndex)
    }
    // Preserve fingerprints for the already-supported static subset. The new source tag 5
    // separates confirmed phone-output unresolved instruments from tag 4 local/unknown ones.
    text("xcertplay:theme-structural-program:v2")
    text(program.displayType.wireValue); text(program.displayId)
    size(program.logicalSize); number(program.assetDensity)
    output.writeInt(program.instructions.size)
    program.instructions.forEach { instruction ->
        when (instruction) {
            is CarPlayThemeProgramInstruction.Layout -> {
                output.writeByte(1); layoutIndex(instruction.address); text(instruction.address.layoutId)
                text(instruction.type.wireValue)
                insets(instruction.safeAreaInsets)
            }
            is CarPlayThemeProgramInstruction.FillLayoutBackground -> {
                output.writeByte(6); layoutIndex(instruction.address)
                number(instruction.color.red); number(instruction.color.green)
                number(instruction.color.blue); number(instruction.color.alpha)
            }
            is CarPlayThemeProgramInstruction.Zone -> {
                output.writeByte(2); zoneIndices(instruction.address); text(instruction.address.zoneId)
                text(instruction.type.wireValue)
                text(instruction.region?.wireValue); rect(instruction.frameInDisplayLogicalSpace)
                text(instruction.shape.wireValue)
                output.writeBoolean(instruction.cornerRadius != null)
                instruction.cornerRadius?.let(::number)
            }
            is CarPlayThemeProgramInstruction.Component -> {
                output.writeByte(3); componentIndices(instruction.address)
                text(instruction.address.componentId); text(instruction.stage.name)
                rect(instruction.frameInDisplayLogicalSpace); text(instruction.clip.name)
                output.writeBoolean(instruction.trackable != null)
                instruction.trackable?.let(output::writeBoolean)
            }
            is CarPlayThemeProgramInstruction.Slot -> {
                output.writeByte(4); slotIndices(instruction.address)
                text(instruction.address.positionId); text(instruction.stage.name)
                text(instruction.role.name); output.writeInt(instruction.zIndex)
                rect(instruction.frameInComponentLogicalSpace)
                rect(instruction.frameInDisplayLogicalSpace)
                insets(instruction.safeAreaInsets); text(instruction.style.wireValue)
                when (val source = instruction.source) {
                    CarPlayThemeStructuralSource.Empty -> output.writeByte(0)
                    is CarPlayThemeStructuralSource.InputStream -> {
                        output.writeByte(1); text(source.streamIdentifier)
                    }
                    is CarPlayThemeStructuralSource.PunchThrough -> {
                        output.writeByte(2); text(source.reference); text(source.dataIdentifier)
                    }
                    is CarPlayThemeStructuralSource.PunchThroughType -> {
                        output.writeByte(3); text(source.type); text(source.dataIdentifier)
                    }
                    is CarPlayThemeStructuralSource.LocalUnresolved -> {
                        output.writeByte(4); text(source.kind.wireValue)
                    }
                    is CarPlayThemeStructuralSource.NonNativeUnresolved -> {
                        output.writeByte(5); text(source.kind.wireValue)
                    }
                }
            }
            is CarPlayThemeProgramInstruction.DynamicOverlay -> {
                output.writeByte(5); layoutIndex(instruction.address); text(instruction.stage.name)
            }
        }
    }
    output.flush()
    return digest.digest()
}
