package com.shilapi.xcertplay.vehicle.ultra.theme

import java.util.Collections

/**
 * A selected, provenance-neutral proof that the *theme semantics* fit the present static scene
 * executor. For mapsMedia/dca Zones the selected primary components are explicit caller state,
 * never inferred from the Zone or from a default component. It is issued only by
 * [CarPlayThemeProgramCompiler.assessExecutableStaticSubset].
 * The caller must separately bind the Display to verified package bytes and an authorized output.
 * This proves neither a physical pixel mapping, live video ingress, OEM trust, UI Sync readiness,
 * nor that an EGL frame was presented.
 */
class CarPlayThemeExecutableStaticSubsetProof internal constructor(
    val displayIndex: Int,
    val displayType: DisplayType,
    val displayId: String?,
    val layoutAddress: CarPlayThemeSceneLayoutAddress,
    selectedPrimaryComponents: List<CarPlayThemePrimarySelection>,
    val structuralFingerprint: CarPlayThemeStructuralFingerprint,
    val backgroundColor: CarPlayThemeColor,
    videoSlots: List<CarPlayThemeExecutableVideoSlot>,
    instrumentReferenceSlots: List<CarPlayThemeExecutableInstrumentReferenceSlot> = emptyList(),
) {
    val selectedPrimaryComponents: List<CarPlayThemePrimarySelection> =
        Collections.unmodifiableList(ArrayList(selectedPrimaryComponents))
    val videoSlots: List<CarPlayThemeExecutableVideoSlot> =
        Collections.unmodifiableList(ArrayList(videoSlots))
    /** Diagnostic native references only; a separate issuer must bind signed Theme/CAF data. */
    val instrumentReferenceSlots: List<CarPlayThemeExecutableInstrumentReferenceSlot> =
        Collections.unmodifiableList(ArrayList(instrumentReferenceSlots))

    /** The precise selection must still match the verified archive and live executor input. */
    fun matches(
        displayIndex: Int,
        layoutIndex: Int,
        state: CarPlayThemeSceneState,
        fingerprint: CarPlayThemeStructuralFingerprint,
    ): Boolean = this.displayIndex == displayIndex && layoutAddress.layoutIndex == layoutIndex &&
        !state.transitionActive && !state.trackingOverlayPresent &&
        !state.appearanceOverlayPresent &&
        selectedPrimaryComponents == state.activePrimarySelections &&
        structuralFingerprint == fingerprint
}

/**
 * One active video slot whose complete visible theme content is an untransformed rectangle,
 * optionally intersected with the selected component's zero-radius rectangular zone shape.
 * [clipInDisplayLogicalSpace] is null only when the component has no selected clip. The compiler
 * binds a non-null value to the exact Zone instruction in [structuralFingerprint]; a renderer
 * must not silently omit or widen it.
 */
data class CarPlayThemeExecutableVideoSlot(
    val address: CarPlayThemeSceneSlotAddress,
    val streamIdentifier: String,
    val stage: CarPlayThemeSceneStage,
    val zIndex: Int,
    val frameInDisplayLogicalSpace: CarPlayLogicalRect,
    val clipInDisplayLogicalSpace: CarPlayLogicalRect? = null,
)

/** One narrowly understood, native CLUSTER source; this does not prove OEM raster fidelity. */
data class CarPlayThemeExecutableInstrumentReferenceSlot(
    val address: CarPlayThemeSceneSlotAddress,
    val kind: InstrumentKindCase,
    val dataIdentifier: String,
    val stage: CarPlayThemeSceneStage,
    val zIndex: Int,
    val frameInDisplayLogicalSpace: CarPlayLogicalRect,
)

/**
 * Exact, immutable result for one selected Display/Layout/state. [issues] contains every active
 * compiler diagnostic in that selection plus every violated static executor condition. A positive
 * [proof] is issued only when that set is empty. Inactive layouts are not silently required to be
 * drawable, but their understood structure remains part of [structuralFingerprint].
 */
class CarPlayThemeExecutableStaticSubsetAssessment internal constructor(
    val structuralProgram: CarPlayThemeStructuralProgram,
    issues: Collection<CarPlayThemeProgramIssue>,
    val proof: CarPlayThemeExecutableStaticSubsetProof?,
) {
    val structuralFingerprint: CarPlayThemeStructuralFingerprint =
        structuralProgram.structuralFingerprint
    val issues: List<CarPlayThemeProgramIssue> =
        Collections.unmodifiableList(ArrayList(issues))

    init {
        require((proof != null) == this.issues.isEmpty()) {
            "A static executable proof exists exactly when no selected issue remains"
        }
        require(proof == null || proof.structuralFingerprint == structuralFingerprint) {
            "A static executable proof must bind the assessed structural program"
        }
    }
}
