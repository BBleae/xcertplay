package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeAuthorizationSnapshot
import com.shilapi.xcertplay.airplay.rcs.caf.CafSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import com.shilapi.xcertplay.airplay.rcs.caf.CafUInt64
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestinationRole
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRequirement
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeAuthorizationSnapshot
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleSpec
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundle
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalSemantics
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeActiveComponentCrosswalk
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeExecutableStaticSubsetProof
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemePrimarySelection
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeResolvedStyleWitness
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneState
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneSlotAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralFingerprint
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalRect
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalSize
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramInstruction
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralSource
import com.shilapi.xcertplay.vehicle.ultra.theme.SlotStyleCase
import com.shilapi.xcertplay.vehicle.ultra.theme.DisplayType
import com.shilapi.xcertplay.vehicle.ultra.theme.decodeThemeJson
import com.shilapi.xcertplay.ultra.UltraContentId
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/** Deterministic SHA-256 identity of one complete, profile-compiled runtime declaration. */
internal class UltraCompiledRuntimeManifestDigest(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == SHA256_BYTES)
    }

    fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is UltraCompiledRuntimeManifestDigest &&
            MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "UltraCompiledRuntimeManifestDigest(<redacted>)"

    private companion object {
        const val SHA256_BYTES = 32
    }
}

/** Digest of the fixed verifier roots, algorithms, EKU/policy and normalization rules. */
internal class UltraTrustPolicyFingerprint(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == SHA256_BYTES)
    }

    fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is UltraTrustPolicyFingerprint && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "UltraTrustPolicyFingerprint(<redacted>)"

    private companion object {
        const val SHA256_BYTES = 32
    }
}

/** Issuer-derived, selected, issue-free static theme proof; never supplied by a caller. */
private class CompiledUiSyncExecutableSelectionProof(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init { require(value.size == 32) }

    fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CompiledUiSyncExecutableSelectionProof &&
            MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "CompiledUiSyncExecutableSelectionProof(<redacted>)"
}

/** Canonical, bounded selection of one structural scene program from a package display. */
internal class CompiledUiSyncThemeProgramSelection(
    val layoutIndex: Int,
    state: CarPlayThemeSceneState,
    val structuralFingerprint: CarPlayThemeStructuralFingerprint,
) {
    val activePrimarySelections: List<CarPlayThemePrimarySelection> =
        canonicalPrimarySelections(state.activePrimarySelections)
    val trackingOverlayPresent: Boolean = state.trackingOverlayPresent
    val appearanceOverlayPresent: Boolean = state.appearanceOverlayPresent
    val transitionActive: Boolean = state.transitionActive

    init {
        require(layoutIndex >= 0) { "Compiled theme layout index must be non-negative" }
        require(!trackingOverlayPresent && !appearanceOverlayPresent && !transitionActive) {
            "Dynamic theme state is not an executable compiled selection"
        }
    }

    fun stateSnapshot(): CarPlayThemeSceneState = CarPlayThemeSceneState(
        activePrimarySelections, trackingOverlayPresent, appearanceOverlayPresent,
        transitionActive,
    )

    fun matches(
        layoutIndex: Int,
        state: CarPlayThemeSceneState,
        fingerprint: CarPlayThemeStructuralFingerprint,
    ): Boolean {
        if (this.layoutIndex != layoutIndex || structuralFingerprint != fingerprint ||
            trackingOverlayPresent != state.trackingOverlayPresent ||
            appearanceOverlayPresent != state.appearanceOverlayPresent ||
            transitionActive != state.transitionActive
        ) return false
        val selections = try {
            canonicalPrimarySelections(state.activePrimarySelections)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return activePrimarySelections == selections
    }

    override fun equals(other: Any?): Boolean =
        other is CompiledUiSyncThemeProgramSelection &&
            layoutIndex == other.layoutIndex &&
            activePrimarySelections == other.activePrimarySelections &&
            trackingOverlayPresent == other.trackingOverlayPresent &&
            appearanceOverlayPresent == other.appearanceOverlayPresent &&
            transitionActive == other.transitionActive &&
            structuralFingerprint == other.structuralFingerprint

    override fun hashCode(): Int {
        var result = layoutIndex
        result = 31 * result + activePrimarySelections.hashCode()
        result = 31 * result + trackingOverlayPresent.hashCode()
        result = 31 * result + appearanceOverlayPresent.hashCode()
        result = 31 * result + transitionActive.hashCode()
        return 31 * result + structuralFingerprint.hashCode()
    }

    override fun toString(): String = "CompiledUiSyncThemeProgramSelection(<redacted>)"
}

/** One selected Profile InputStream slot to one complete, typed renderer ingress selector. */
internal class CompiledUiSyncSlotIngressAssignment(
    val slot: CarPlayThemeSceneSlotAddress,
    val streamIdentifier: String,
    val ingressSpec: UiSyncRendererIngressSpec,
) {
    init {
        require(streamIdentifier.isNotBlank() && streamIdentifier.length <= 512 &&
            streamIdentifier.none(Char::isISOControl)
        ) { "Compiled slot stream identifier is invalid" }
    }

    override fun equals(other: Any?): Boolean = other is CompiledUiSyncSlotIngressAssignment &&
        slot == other.slot && streamIdentifier == other.streamIdentifier &&
        ingressSpec == other.ingressSpec

    override fun hashCode(): Int {
        var result = slot.hashCode()
        result = 31 * result + streamIdentifier.hashCode()
        return 31 * result + ingressSpec.hashCode()
    }

    override fun toString(): String = "CompiledUiSyncSlotIngressAssignment(<redacted>)"
}

/** One signed Debug content identity on an active, otherwise empty Profile rectangle. */
internal class CompiledUiSyncLocalCanvasAssignment(
    val slot: CarPlayThemeSceneSlotAddress,
    val contentId: String,
) {
    init { UltraContentId(contentId) }

    override fun equals(other: Any?): Boolean = other is CompiledUiSyncLocalCanvasAssignment &&
        slot == other.slot && contentId == other.contentId

    override fun hashCode(): Int = 31 * slot.hashCode() + contentId.hashCode()

    override fun toString(): String = "CompiledUiSyncLocalCanvasAssignment(<redacted>)"
}

/** One selected signed CLUSTER Gauge/Indicator crosswalk, not an OEM native-pixel renderer. */
internal class CompiledUiSyncInstrumentReferenceAssignment(
    val slot: CarPlayThemeSceneSlotAddress,
    val spec: InstrumentSignalBundleSpec,
) {
    private val specFingerprint = instrumentSpecFingerprint(spec)

    init { require(slot == spec.address.slot) { "Instrument reference slot differs from crosswalk" } }

    override fun equals(other: Any?): Boolean =
        other is CompiledUiSyncInstrumentReferenceAssignment && slot == other.slot &&
            MessageDigest.isEqual(specFingerprint, other.specFingerprint)

    override fun hashCode(): Int = 31 * slot.hashCode() + specFingerprint.contentHashCode()

    override fun toString(): String = "CompiledUiSyncInstrumentReferenceAssignment(<redacted>)"
}

/** Compare complete immutable declarations, not just a slot address or theme data identifier. */
internal fun sameInstrumentSignalSpec(
    first: InstrumentSignalBundleSpec,
    second: InstrumentSignalBundleSpec,
): Boolean = MessageDigest.isEqual(instrumentSpecFingerprint(first), instrumentSpecFingerprint(second))

/**
 * Exact type-3 wire intent. Palette IDs are opaque signed tags, not inferred theme semantics.
 * An empty activeComponents map only means no explicit wire entries; it does not establish the
 * iPhone's default primary-component selection. Signed v3 catalogs can bind a nonempty map to
 * exact carousel components, while v1/v2 retain their empty-map-only policy.
 */
internal class CompiledUiSyncThemeIntent(
    val layoutID: String,
    activeComponents: Map<String, String>,
    val paletteID: String,
) {
    val activeComponents: Map<String, String> = canonicalIntentComponents(activeComponents)

    init {
        requireValidIntentString(layoutID, "layout ID")
        requireValidIntentString(paletteID, "palette ID")
    }

    /** Legacy v1/v2 static-intent policy; v3 authority is established by the signed catalog. */
    fun isSupportedStaticIntent(): Boolean = activeComponents.isEmpty()

    override fun equals(other: Any?): Boolean = other is CompiledUiSyncThemeIntent &&
        layoutID == other.layoutID && activeComponents == other.activeComponents &&
        paletteID == other.paletteID

    override fun hashCode(): Int =
        31 * (31 * layoutID.hashCode() + activeComponents.hashCode()) + paletteID.hashCode()

    override fun toString(): String = "CompiledUiSyncThemeIntent(<redacted>)"
}

/** Additional, explicitly selected static layout; no implicit catalog sweep is authorized. */
internal class CompiledUiSyncThemeVariant(
    val intent: CompiledUiSyncThemeIntent,
    val programSelection: CompiledUiSyncThemeProgramSelection,
    slotIngressAssignments: Collection<CompiledUiSyncSlotIngressAssignment>,
    localCanvasAssignments: Collection<CompiledUiSyncLocalCanvasAssignment> = emptyList(),
    instrumentReferenceAssignments: Collection<CompiledUiSyncInstrumentReferenceAssignment> =
        emptyList(),
) {
    val slotIngressAssignments: List<CompiledUiSyncSlotIngressAssignment> =
        canonicalSlotIngressAssignments(slotIngressAssignments, programSelection.layoutIndex)
    val localCanvasAssignments: List<CompiledUiSyncLocalCanvasAssignment> =
        canonicalLocalCanvasAssignments(localCanvasAssignments, programSelection.layoutIndex)
    val instrumentReferenceAssignments: List<CompiledUiSyncInstrumentReferenceAssignment> =
        canonicalInstrumentReferenceAssignments(instrumentReferenceAssignments,
            programSelection.layoutIndex)

    override fun equals(other: Any?): Boolean = other is CompiledUiSyncThemeVariant &&
        intent == other.intent && programSelection == other.programSelection &&
        slotIngressAssignments == other.slotIngressAssignments &&
        localCanvasAssignments == other.localCanvasAssignments &&
        instrumentReferenceAssignments == other.instrumentReferenceAssignments

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * intent.hashCode() + programSelection.hashCode()) +
            slotIngressAssignments.hashCode()) + localCanvasAssignments.hashCode()) +
            instrumentReferenceAssignments.hashCode()

    override fun toString(): String = "CompiledUiSyncThemeVariant(<redacted>)"
}

/** Revalidated exact selection, not evidence of presentation or a renderer ACK. */
internal class CompiledUiSyncSelectedThemeVariant internal constructor(
    val assignment: CompiledUiSyncDisplayAssignment,
    val intent: CompiledUiSyncThemeIntent,
    val programSelection: CompiledUiSyncThemeProgramSelection,
    val slotIngressAssignments: List<CompiledUiSyncSlotIngressAssignment>,
    val localCanvasAssignments: List<CompiledUiSyncLocalCanvasAssignment>,
    val instrumentReferenceAssignments: List<CompiledUiSyncInstrumentReferenceAssignment>,
    /** The very same crosswalk assignment objects returned by instrumentAssignmentsFor. */
    instrumentSignalAssignments: List<CompiledInstrumentSignalDisplayAssignment>,
) {
    val instrumentSignalAssignments: List<CompiledInstrumentSignalDisplayAssignment> =
        Collections.unmodifiableList(ArrayList(instrumentSignalAssignments))

    init {
        require(this.instrumentSignalAssignments.size == instrumentReferenceAssignments.size &&
            this.instrumentSignalAssignments.indices.all { index ->
                this.instrumentSignalAssignments[index].spec.address.slot ==
                    instrumentReferenceAssignments[index].slot &&
                    sameInstrumentSignalSpec(this.instrumentSignalAssignments[index].spec,
                        instrumentReferenceAssignments[index].spec)
            }) { "Selected native references differ from exact issuer crosswalk objects" }
    }
}

/** One compiler-selected route and program across a verified package display and three IDs. */
internal class CompiledUiSyncDisplayAssignment(
    val phonePackageDigest: UltraProfileDigest,
    val profileDisplayIndex: Int,
    val profileDisplayId: String?,
    val profileDisplayType: DisplayType,
    val destination: DisplayDestination,
    val topologyDisplayId: UltraTopologyDisplayId,
    val outputId: UltraDisplayOutputId,
    val uiSyncDisplayId: UltraUiSyncDisplayId,
    val programSelection: CompiledUiSyncThemeProgramSelection,
    slotIngressAssignments: Collection<CompiledUiSyncSlotIngressAssignment>,
    val primaryIntent: CompiledUiSyncThemeIntent? = null,
    additionalVariants: Collection<CompiledUiSyncThemeVariant> = emptyList(),
    localCanvasAssignments: Collection<CompiledUiSyncLocalCanvasAssignment> = emptyList(),
    instrumentReferenceAssignments: Collection<CompiledUiSyncInstrumentReferenceAssignment> =
        emptyList(),
) {
    val slotIngressAssignments: List<CompiledUiSyncSlotIngressAssignment> =
        canonicalSlotIngressAssignments(slotIngressAssignments, programSelection.layoutIndex)
    val localCanvasAssignments: List<CompiledUiSyncLocalCanvasAssignment> =
        canonicalLocalCanvasAssignments(localCanvasAssignments, programSelection.layoutIndex)
    val instrumentReferenceAssignments: List<CompiledUiSyncInstrumentReferenceAssignment> =
        canonicalInstrumentReferenceAssignments(instrumentReferenceAssignments,
            programSelection.layoutIndex)
    val additionalVariants: List<CompiledUiSyncThemeVariant> = Collections.unmodifiableList(
        boundedList(additionalVariants, MAXIMUM_THEME_VARIANTS_PER_DISPLAY - 1,
            "additional compiled theme variants")
            .sortedWith(compareBy({ it.programSelection.layoutIndex }, { it.intent.layoutID },
                { canonicalIntentSortToken(it.intent) }, { it.intent.paletteID })),
    )

    internal val selectedVariantCount: Int get() = 1 + additionalVariants.size

    init {
        require(profileDisplayIndex >= 0) { "Profile display index must be non-negative" }
        require(profileDisplayId == null ||
            (profileDisplayId.isNotBlank() && profileDisplayId.length <= 512 &&
                profileDisplayId.none(Char::isISOControl))
        ) { "Profile display ID is invalid" }
        require(this.additionalVariants.isEmpty() || primaryIntent != null) {
            "A multi-variant theme catalog needs an exact primary intent"
        }
        require(selectedVariants().groupBy { it.programSelection.layoutIndex }.values.all { rows ->
            rows.size == 1 || (rows.all { it.intent != null } &&
                rows.map { it.intent }.toSet().size == rows.size)
        }) { "Repeated compiled layout variants require distinct exact wire intents" }
        require((listOfNotNull(primaryIntent) + this.additionalVariants.map { it.intent })
            .toSet().size == (if (primaryIntent == null) 0 else 1) +
            this.additionalVariants.size
        ) { "Compiled theme catalog contains a duplicate intent" }
        require((this.slotIngressAssignments + this.additionalVariants.flatMap {
            it.slotIngressAssignments
        }).groupBy { it.streamIdentifier }.values.all { rows ->
            rows.map { it.ingressSpec }.toSet().size == 1
        }) { "One Profile InputStream identifier cannot select different ingress specs" }
        require((this.localCanvasAssignments + this.additionalVariants.flatMap {
            it.localCanvasAssignments
        }).map { it.contentId }.toSet().size ==
            this.localCanvasAssignments.size + this.additionalVariants.sumOf {
                it.localCanvasAssignments.size
            }
        ) { "Local Canvas content IDs must be unique across selected layouts" }
        require(selectedVariants().all { variant ->
            val video = variant.slotIngressAssignments.map { it.slot }.toSet()
            val canvas = variant.localCanvasAssignments.map { it.slot }.toSet()
            val instruments = variant.instrumentReferenceAssignments.map { it.slot }.toSet()
            video.intersect(canvas).isEmpty() && video.intersect(instruments).isEmpty() &&
                canvas.intersect(instruments).isEmpty()
        }) { "A selected Profile slot cannot have multiple rendering source assignments" }
    }

    fun graphOutput(): UltraDisplayGraphOutput = UltraDisplayGraphOutput(
        topologyDisplayId, outputId, uiSyncDisplayId,
    )

    override fun equals(other: Any?): Boolean = other is CompiledUiSyncDisplayAssignment &&
        phonePackageDigest == other.phonePackageDigest &&
        profileDisplayIndex == other.profileDisplayIndex &&
        profileDisplayId == other.profileDisplayId &&
        profileDisplayType == other.profileDisplayType &&
        destination == other.destination &&
        topologyDisplayId == other.topologyDisplayId &&
        outputId == other.outputId &&
        uiSyncDisplayId == other.uiSyncDisplayId &&
        programSelection == other.programSelection &&
        slotIngressAssignments == other.slotIngressAssignments &&
        localCanvasAssignments == other.localCanvasAssignments &&
        instrumentReferenceAssignments == other.instrumentReferenceAssignments &&
        primaryIntent == other.primaryIntent && additionalVariants == other.additionalVariants

    override fun hashCode(): Int {
        var result = phonePackageDigest.hashCode()
        result = 31 * result + profileDisplayIndex
        result = 31 * result + (profileDisplayId?.hashCode() ?: 0)
        result = 31 * result + profileDisplayType.hashCode()
        result = 31 * result + destination.hashCode()
        result = 31 * result + topologyDisplayId.hashCode()
        result = 31 * result + outputId.hashCode()
        result = 31 * result + uiSyncDisplayId.hashCode()
        result = 31 * result + programSelection.hashCode()
        result = 31 * result + slotIngressAssignments.hashCode()
        result = 31 * result + localCanvasAssignments.hashCode()
        result = 31 * result + instrumentReferenceAssignments.hashCode()
        result = 31 * result + (primaryIntent?.hashCode() ?: 0)
        return 31 * result + additionalVariants.hashCode()
    }

    override fun toString(): String = "CompiledUiSyncDisplayAssignment(<redacted>)"
}

/** Exact static UI Sync compiler output expected from every child provisioning. */
internal class CompiledUiSyncRuntimeContract(
    val schemaFingerprint: UiSyncSchemaFingerprint,
    val bindingFingerprint: UiSyncBindingFingerprint,
    val semanticThemeProgramFingerprint: UiSyncSemanticThemeProgramFingerprint,
    val rendererImplementationId: String,
    displayIds: Collection<String>,
    outputs: Collection<UltraDisplayGraphOutput>,
    ingressSpecs: Collection<UiSyncRendererIngressSpec>,
    displayAssignments: Collection<CompiledUiSyncDisplayAssignment>,
    val physicalOutputRequirement: UiSyncPhysicalOutputRequirement? = null,
) {
    val displayIds: Set<String> = boundedUniqueSet(
        displayIds,
        MAXIMUM_UI_SYNC_DISPLAYS,
        "compiled UI Sync display IDs",
    )
    val outputs: List<UltraDisplayGraphOutput> = canonicalOutputs(
        boundedList(
            outputs,
            MAXIMUM_UI_SYNC_OUTPUTS,
            "compiled UI Sync outputs",
        ),
    )
    val ingressSpecs: List<UiSyncRendererIngressSpec> = canonicalIngresses(
        boundedList(
            ingressSpecs,
            MAXIMUM_UI_SYNC_INGRESSES,
            "compiled UI Sync ingress specs",
        ),
    )
    val displayAssignments: List<CompiledUiSyncDisplayAssignment> =
        Collections.unmodifiableList(boundedList(
            displayAssignments, MAXIMUM_UI_SYNC_DISPLAYS, "compiled profile display assignments",
        ).sortedBy(CompiledUiSyncDisplayAssignment::profileDisplayIndex))

    init {
        require(rendererImplementationId.isNotBlank() &&
            rendererImplementationId.length <= MAXIMUM_IMPLEMENTATION_ID_CHARS &&
            rendererImplementationId.none(Char::isISOControl)
        ) { "Compiled UI Sync renderer implementation ID is invalid" }
        require(this.displayIds.isNotEmpty()) {
            "Compiled UI Sync contract requires at least one display"
        }
        this.displayIds.forEach { UltraUiSyncDisplayId(it) }
        require(this.outputs.isNotEmpty()) {
            "Compiled UI Sync contract requires at least one output"
        }
        require(this.outputs.map(UltraDisplayGraphOutput::outputId).toSet().size ==
            this.outputs.size
        ) { "Compiled UI Sync contract contains duplicate output IDs" }
        require(this.outputs.map(UltraDisplayGraphOutput::topologyDisplayId).toSet().size ==
            this.outputs.size
        ) { "Compiled UI Sync contract contains duplicate topology display IDs" }
        val mappedDisplayIds = this.outputs.mapNotNull { it.uiSyncDisplayId?.value }
        require(mappedDisplayIds.size == mappedDisplayIds.toSet().size) {
            "Compiled UI Sync contract contains duplicate UI Sync display IDs"
        }
        require(mappedDisplayIds.toSet() == this.displayIds) {
            "Compiled UI Sync outputs must cover every UI Sync display exactly"
        }
        require(this.displayAssignments.size == this.outputs.size &&
            this.displayAssignments.map { it.profileDisplayIndex }.toSet().size ==
                this.displayAssignments.size &&
            this.displayAssignments.map { it.destination }.toSet().size ==
                this.displayAssignments.size &&
            this.displayAssignments.map { it.graphOutput() }.toSet() == this.outputs.toSet() &&
            this.displayAssignments.map { it.phonePackageDigest }.toSet().size == 1
        ) { "Compiled profile display assignments must exactly cover distinct graph outputs" }
        require(this.displayAssignments.sumOf { it.selectedVariantCount } <=
            MAXIMUM_TOTAL_THEME_VARIANTS
        ) { "Compiled theme catalog exceeds the total variant limit" }
        require(this.displayAssignments.flatMap { assignment ->
            assignment.selectedVariants().flatMap { it.slotIngressAssignments }
        }.all {
            it.ingressSpec in this.ingressSpecs
        }) { "Compiled slot ingress selector is absent from the renderer static plan" }
        val localContentIds = this.displayAssignments.flatMap { assignment ->
            assignment.selectedVariants().flatMap { variant ->
                variant.localCanvasAssignments.map { it.contentId }
            }
        }
        require(localContentIds.size == localContentIds.toSet().size) {
            "Local Canvas content IDs must be unique across all outputs and layouts"
        }
        require(this.ingressSpecs.map(UiSyncRendererIngressSpec::id).toSet().size ==
            this.ingressSpecs.size
        ) { "Compiled UI Sync contract contains duplicate ingress IDs" }
        // The shared static plan checks pairwise selector overlap, including an exact legacy
        // identity competing with a UUID-bound selector whose connection ID is learned at SETUP.
        UiSyncRendererStaticPlan(this.outputs, this.ingressSpecs)
        physicalOutputRequirement?.let { requirement ->
            requirePhysicalAssignments(this.outputs, this.displayAssignments, requirement)
        }
    }

    fun matches(snapshot: UiSyncRuntimeAuthorizationSnapshot?): Boolean =
        snapshot != null &&
            snapshot.schemaFingerprint == schemaFingerprint &&
            snapshot.bindingFingerprint == bindingFingerprint &&
            snapshot.semanticThemeProgramFingerprint == semanticThemeProgramFingerprint &&
            snapshot.rendererImplementationId == rendererImplementationId &&
            snapshot.displayIds == displayIds &&
            canonicalOutputs(snapshot.outputs) == outputs &&
            canonicalIngresses(snapshot.ingressSpecs) == ingressSpecs &&
            physicalRequirementMatches(snapshot.physicalOutputRequirement)

    private fun physicalRequirementMatches(
        candidate: UiSyncPhysicalOutputRequirement?,
    ): Boolean {
        val expected = physicalOutputRequirement ?: return candidate == null
        if (candidate == null ||
            candidate.verifiedProfileDigest != expected.verifiedProfileDigest ||
            candidate.assignmentFingerprint != expected.assignmentFingerprint
        ) return false
        return runCatching {
            // The candidate snapshot itself already requires each assignment's output to be the
            // exact object in its static plan. A fresh compiler instance may produce equal typed
            // rows, so compare those rows structurally across the two provisionings.
            requirePhysicalAssignments(
                outputs, displayAssignments, candidate, requireExactOutputInstances = false,
            )
        }.isSuccess
    }

    override fun toString(): String = "CompiledUiSyncRuntimeContract(<redacted>)"

    internal companion object {
        fun from(
            snapshot: UiSyncRuntimeAuthorizationSnapshot,
            displayAssignments: Collection<CompiledUiSyncDisplayAssignment>,
        ) = CompiledUiSyncRuntimeContract(
            schemaFingerprint = snapshot.schemaFingerprint,
            bindingFingerprint = snapshot.bindingFingerprint,
            semanticThemeProgramFingerprint = snapshot.semanticThemeProgramFingerprint,
            rendererImplementationId = snapshot.rendererImplementationId,
            displayIds = snapshot.displayIds,
            outputs = snapshot.outputs,
            ingressSpecs = snapshot.ingressSpecs,
            displayAssignments = displayAssignments,
            physicalOutputRequirement = snapshot.physicalOutputRequirement,
        )
    }
}

/** Exact static CAF compiler/handler identities expected from every child provisioning. */
internal class CompiledCafRuntimeContract(
    val schemaFingerprint: CafSchemaFingerprint,
    val decoderImplementationId: String,
    handlerImplementationIds: Map<Long, String>,
) {
    val handlerImplementationIds: Map<Long, String> = boundedImplementationMap(
        handlerImplementationIds,
    )

    init {
        requireValidImplementationId(decoderImplementationId, "compiled CAF decoder")
        require(this.handlerImplementationIds.isNotEmpty()) {
            "Compiled CAF contract requires at least one handler"
        }
    }

    fun matches(snapshot: CafRuntimeAuthorizationSnapshot?): Boolean =
        snapshot != null &&
            snapshot.schemaFingerprint == schemaFingerprint &&
            snapshot.decoderImplementationId == decoderImplementationId &&
            snapshot.handlerImplementationIds == handlerImplementationIds

    override fun toString(): String = "CompiledCafRuntimeContract(<redacted>)"

    internal companion object {
        fun from(snapshot: CafRuntimeAuthorizationSnapshot) = CompiledCafRuntimeContract(
            schemaFingerprint = snapshot.schemaFingerprint,
            decoderImplementationId = snapshot.decoderImplementationId,
            handlerImplementationIds = snapshot.handlerImplementationIds,
        )
    }
}

/** A signed Profile display ordinal and the exact validated Theme-slot/CAF source declaration. */
internal class CompiledInstrumentSignalDisplayAssignment(
    val profileDisplayIndex: Int,
    val profileDisplayId: String?,
    val spec: InstrumentSignalBundleSpec,
) {
    init {
        require(profileDisplayIndex in 0..4095)
        require(profileDisplayId == null ||
            (profileDisplayId.isNotBlank() && profileDisplayId.length <= 512 &&
                profileDisplayId.none(Char::isISOControl)))
    }
}

/**
 * An optional signed Debug crosswalk is identity evidence only. It does not authorize a Gauge
 * renderer, start an instrument repository, or add an advertised AirPlay feature.
 */
internal class CompiledInstrumentSignalRuntimeContract(
    private val exactVerifiedProfile: VerifiedUltraProfile,
    val crosswalkDigest: UltraProfileDigest,
    val phonePackageDigest: UltraProfileDigest,
    val cafSchemaFingerprint: CafSchemaFingerprint,
    assignments: Collection<CompiledInstrumentSignalDisplayAssignment>,
) {
    val assignments: List<CompiledInstrumentSignalDisplayAssignment> =
        Collections.unmodifiableList(boundedList(assignments, MAXIMUM_INSTRUMENT_ASSIGNMENTS,
            "instrument crosswalk assignments").sortedWith(compareBy(
            { it.profileDisplayIndex },
            { it.spec.address.slot.component.zone.layout.layoutIndex },
            { it.spec.address.slot.component.zone.zoneIndex },
            { it.spec.address.slot.component.componentIndex },
            { it.spec.address.slot.slotIndex },
        )))

    init {
        require(this.assignments.isNotEmpty())
        require(this.assignments.map { it.profileDisplayIndex to it.spec.address.slot }
            .toSet().size == this.assignments.size) {
            "Instrument crosswalk repeats a Profile display slot"
        }
        require(matchesProfile(exactVerifiedProfile)) {
            "Instrument crosswalk is not from the exact verified Profile"
        }
    }

    fun matchesProfile(profile: VerifiedUltraProfile): Boolean =
        profile === exactVerifiedProfile &&
            profile.binding.contentDigest == profile.candidate.contentDigest &&
            profile.candidate.entries.singleOrNull {
                it.name == INSTRUMENT_CROSSWALK_SPEC_PATH
            }?.digest == crosswalkDigest &&
            profile.candidate.content(INSTRUMENT_CROSSWALK_SPEC_PATH)?.let {
                UltraProfileDigest(sha256(it)) == crosswalkDigest
            } == true &&
            profile.candidate.entries.singleOrNull {
                it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
            }?.digest == phonePackageDigest &&
            signedInstrumentAssignmentsMatch(profile, assignments) &&
            signedInstrumentDeclarationsMatch(profile, assignments)

    fun matchesCaf(contract: CompiledCafRuntimeContract?): Boolean =
        contract?.schemaFingerprint == cafSchemaFingerprint

    override fun toString(): String = "CompiledInstrumentSignalRuntimeContract(<redacted>)"
}

private const val MAXIMUM_INSTRUMENT_ASSIGNMENTS = 16
private const val INSTRUMENT_CROSSWALK_SPEC_PATH =
    UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY +
        "xcertplay-instrument-signals-v1.json"

/**
 * Immutable output of one fixed, authorized OEM profile compiler.
 *
 * The deterministic digest is an identity, not an authorization token. [issuerChallenge] is the
 * in-process provenance which prevents a structurally valid caller-created profile or renderer
 * from entering the product activation path.
 */
internal class AuthorizedUltraCompiledRuntimeManifest private constructor(
    val verificationIdentity: UltraProfileVerificationIdentity,
    val trustPolicyFingerprint: UltraTrustPolicyFingerprint,
    private val runtimeIdentity: UltraRuntimeIdentity,
    private val authenticationIdentity: MfiAuthenticationIdentity,
    val profileContentDigest: UltraProfileDigest,
    val profileManifestDigest: UltraProfileDigest,
    val phoneThemeDigest: UltraProfileDigest,
    val phonePackageDigest: UltraProfileDigest,
    val phoneInfoDigest: UltraProfileDigest,
    private val exactVerifiedProfile: VerifiedUltraProfile,
    trustProofDigest: ByteArray,
    executableStaticSelectionProofs: List<CompiledUiSyncExecutableSelectionProof>,
    validatedThemeVariants: List<ValidatedThemeVariant>,
    val uiSync: CompiledUiSyncRuntimeContract?,
    val caf: CompiledCafRuntimeContract?,
    val instrumentCrosswalk: CompiledInstrumentSignalRuntimeContract?,
    val manifestDigest: UltraCompiledRuntimeManifestDigest,
    private val issuerChallenge: Any,
    private val themeValidationPassObserver: (() -> Unit)?,
) {
    private val storedTrustProofDigest = trustProofDigest.copyOf()
    private val storedExecutableStaticSelectionProofs =
        Collections.unmodifiableList(ArrayList(executableStaticSelectionProofs))
    private val issuedThemeVariantIndex = IssuedThemeVariantIndex(
        exactVerifiedProfile, manifestDigest, uiSync?.semanticThemeProgramFingerprint,
        uiSync, instrumentCrosswalk, validatedThemeVariants,
    )
    private val hasSignedV3UiSyncCatalog = uiSync != null &&
        signedLocalCanvasRows(exactVerifiedProfile)?.version == SignedUiSyncCatalogVersion.V3

    val authorizedFeatures: Set<AirPlayFeature> = Collections.unmodifiableSet(
        linkedSetOf<AirPlayFeature>().apply {
            if (uiSync != null) add(AirPlayFeature.UI_SYNC)
            if (caf != null) add(AirPlayFeature.VEHICLE_STATE_PROTOCOL)
        },
    )

    /** Exact signed identifier only; neither phone installation nor catalog admission is implied. */
    internal fun matchesClusterAssetAcquisition(
        assetId: String,
        candidateAuthenticationIdentity: MfiAuthenticationIdentity,
    ): Boolean = uiSync != null && assetId.isNotBlank() &&
        authenticationIdentity == candidateAuthenticationIdentity &&
        exactVerifiedProfile.binding.assetIdentifier == assetId

    init {
        require(storedTrustProofDigest.size == SHA256_BYTES)
        require(uiSync != null || caf != null) {
            "An authorized Ultra manifest must compile at least one runtime"
        }
        require(storedExecutableStaticSelectionProofs.size ==
            (uiSync?.displayAssignments?.sumOf { it.selectedVariantCount } ?: 0)
        ) { "Every UI Sync theme variant requires one selected executable proof" }
        require(validatedThemeVariants.map { it.executableProof } ==
            storedExecutableStaticSelectionProofs
        ) { "Issued theme index differs from the selected executable proofs" }
    }

    fun trustProofDigest(): ByteArray = storedTrustProofDigest.copyOf()

    /** One complete SHA/package parse/scene compile pass; the type-3 lookup never calls this. */
    private fun validateThemeSource(
        profile: VerifiedUltraProfile,
        assignments: List<CompiledUiSyncDisplayAssignment>,
    ): List<ValidatedThemeVariant> {
        themeValidationPassObserver?.invoke()
        return validateProfileDisplayAssignments(profile, assignments)
    }

    private fun matchesIssuedDigest(
        profile: VerifiedUltraProfile,
        executableProofs: List<CompiledUiSyncExecutableSelectionProof>,
    ): Boolean = runCatching {
        val evidence = profileEvidence(profile)
        MessageDigest.isEqual(storedTrustProofDigest, evidence.trustProofDigest) &&
            (instrumentCrosswalk?.matchesProfile(profile) ?: !hasInstrumentCrosswalk(profile)) &&
            (instrumentCrosswalk?.matchesCaf(caf) ?: true) &&
            instrumentReferenceBindingsMatch(uiSync, instrumentCrosswalk) &&
            (uiSync == null || uiSync.semanticThemeProgramFingerprint ==
                fingerprintVerifiedUiSyncThemeProgram(
                    profile, uiSync.displayAssignments, executableProofs,
                )) &&
            MessageDigest.isEqual(manifestDigest.bytes(), digestManifest(
                verificationIdentity, trustPolicyFingerprint, runtimeIdentity,
                authenticationIdentity, profile, evidence, executableProofs, uiSync, caf,
                instrumentCrosswalk,
            ))
    }.getOrDefault(false)

    /** Read-only comparison for an exact live coordinator token; does not disclose identity data. */
    internal fun matchesGenerationIdentity(
        candidateRuntime: UltraRuntimeIdentity,
        candidateAuthentication: MfiAuthenticationIdentity,
    ): Boolean = runtimeIdentity == candidateRuntime &&
        authenticationIdentity == candidateAuthentication

    internal fun displayAssignmentsFor(profile: VerifiedUltraProfile):
        List<CompiledUiSyncDisplayAssignment> {
        require(profile === exactVerifiedProfile) {
            "Theme source is not the exact manifest-verified profile"
        }
        val contract = requireNotNull(uiSync) {
            "The compiled runtime has no UI Sync display assignments"
        }
        val proofs = validateThemeSource(profile, contract.displayAssignments)
            .map { it.executableProof }
        require(proofs == storedExecutableStaticSelectionProofs &&
            matchesIssuedDigest(profile, proofs)
        ) { "The signed executable theme catalog changed" }
        return contract.displayAssignments
    }

    /** Exact signed data-slot declarations; no sensor or rendering authority is implied. */
    internal fun instrumentAssignmentsFor(profile: VerifiedUltraProfile):
        List<CompiledInstrumentSignalDisplayAssignment> {
        require(profile === exactVerifiedProfile) {
            "Instrument source is not the exact manifest-verified Profile"
        }
        val contract = requireNotNull(instrumentCrosswalk) {
            "The compiled runtime has no signed instrument crosswalk"
        }
        require(contract.matchesProfile(profile) && contract.matchesCaf(caf) &&
            matchesIssuedDigest(profile, storedExecutableStaticSelectionProofs)
        ) { "Signed instrument assignments changed after manifest issuance" }
        return contract.assignments
    }

    /** An exact issued intent lookup; the live provisioning lease gates each caller separately. */
    internal fun selectThemeVariantFor(
        profile: VerifiedUltraProfile,
        displayId: UltraUiSyncDisplayId,
        intent: CompiledUiSyncThemeIntent,
    ): CompiledUiSyncSelectedThemeVariant? {
        if (profile !== exactVerifiedProfile) return null
        val contract = uiSync ?: return null
        return issuedThemeVariantIndex.select(
            profile, manifestDigest, contract.semanticThemeProgramFingerprint, displayId, intent,
        )
    }

    /** Exact wire-to-catalog projection; malformed or unsupported intent never selects a scene. */
    internal fun selectThemeVariantFor(
        profile: VerifiedUltraProfile,
        displayId: UltraUiSyncDisplayId,
        command: CarPlayUiSyncSemanticCommand.LayoutChange,
    ): CompiledUiSyncSelectedThemeVariant? = runCatching {
        selectThemeVariantFor(profile, displayId, CompiledUiSyncThemeIntent(
            command.layoutID, command.activeComponents, command.paletteID,
        ))
    }.getOrNull()

    /**
     * A standalone type-19 appearance preference never selects another catalog row: the v3
     * catalog has no per-appearance variants. An issued executable selection is static by
     * construction (static layout background, no appearance/tracking overlay or transition, no
     * `requiresDarkAppearance`, single-color palette witness), so the exact current variant is
     * returned only when it is still issued and carries none of those dynamic states.
     */
    internal fun selectAppearanceInvariantVariantFor(
        profile: VerifiedUltraProfile,
        displayId: UltraUiSyncDisplayId,
        currentIntent: CompiledUiSyncThemeIntent,
    ): CompiledUiSyncSelectedThemeVariant? = runCatching {
        selectThemeVariantFor(profile, displayId, currentIntent)?.takeIf { selected ->
            selected.intent == currentIntent &&
                !selected.programSelection.appearanceOverlayPresent &&
                !selected.programSelection.trackingOverlayPresent &&
                !selected.programSelection.transitionActive
        }
    }.getOrNull()

    /**
     * A type-16 palette change retains the exact currently issued layout and component mapping.
     * It never constructs a type-3 phone command or selects from an unsigned theme row.
     */
    internal fun selectPaletteVariantFor(
        profile: VerifiedUltraProfile,
        displayId: UltraUiSyncDisplayId,
        currentIntent: CompiledUiSyncThemeIntent,
        command: CarPlayUiSyncSemanticCommand.PaletteChange,
    ): CompiledUiSyncSelectedThemeVariant? = runCatching {
        if (!hasSignedV3UiSyncCatalog || command.layoutIdentifier != currentIntent.layoutID)
            return null
        val current = selectThemeVariantFor(profile, displayId, currentIntent) ?: return null
        val requested = CompiledUiSyncThemeIntent(
            currentIntent.layoutID, currentIntent.activeComponents, command.paletteIdentifier,
        )
        selectThemeVariantFor(profile, displayId, requested)?.takeIf { selected ->
            selected.assignment === current.assignment &&
                selected.programSelection.layoutIndex == current.programSelection.layoutIndex
        }
    }.getOrNull()

    fun matchesProfile(
        request: UltraProfileActivationRequest,
        profile: VerifiedUltraProfile,
        expectedVerificationIdentity: UltraProfileVerificationIdentity,
    ): Boolean {
        val evidence = runCatching { profileEvidence(profile) }.getOrNull() ?: return false
        val executableProofs = uiSync?.let { contract -> runCatching {
            validateThemeSource(profile, contract.displayAssignments)
                .map { it.executableProof }
        }.getOrNull() ?: return false } ?: emptyList()
        return runtimeIdentity == request.runtimeIdentity &&
            authenticationIdentity == request.authenticationIdentity &&
            verificationIdentity == expectedVerificationIdentity &&
            verificationIdentity == UltraProfileVerificationIdentity.from(
                request.runtimeIdentity,
                request.authenticationIdentity,
                profile,
            ) &&
            profileContentDigest == profile.candidate.contentDigest &&
            profileManifestDigest == profile.candidate.manifest.digest &&
            (uiSync == null || profile === exactVerifiedProfile) &&
            (instrumentCrosswalk?.matchesProfile(profile) ?: !hasInstrumentCrosswalk(profile)) &&
            (instrumentCrosswalk?.matchesCaf(caf) ?: true) &&
            phoneThemeDigest == evidence.phoneThemeDigest &&
            phonePackageDigest == evidence.phonePackageDigest &&
            phoneInfoDigest == evidence.phoneInfoDigest &&
            (uiSync?.physicalOutputRequirement?.let {
                it.verifiedProfileDigest == profile.candidate.contentDigest
            } ?: true) &&
            executableProofs == storedExecutableStaticSelectionProofs &&
            MessageDigest.isEqual(storedTrustProofDigest, evidence.trustProofDigest) &&
            matchesIssuedDigest(profile, executableProofs)
    }

    fun matchesProvisioning(
        uiSyncSnapshot: UiSyncRuntimeAuthorizationSnapshot?,
        cafSnapshot: CafRuntimeAuthorizationSnapshot?,
    ): Boolean {
        val executableProofs = uiSync?.let { contract -> runCatching {
            validateThemeSource(exactVerifiedProfile, contract.displayAssignments)
                .map { it.executableProof }
        }.getOrNull() ?: return false } ?: emptyList()
        return executableProofs == storedExecutableStaticSelectionProofs &&
            matchesIssuedDigest(exactVerifiedProfile, executableProofs) &&
            (uiSync != null) == (uiSyncSnapshot != null) &&
            (caf != null) == (cafSnapshot != null) &&
            (uiSync?.matches(uiSyncSnapshot) ?: true) &&
            (caf?.matches(cafSnapshot) ?: true)
    }

    internal fun issuedBy(issuer: AuthorizedUltraCompiledRuntimeManifestIssuer): Boolean =
        issuer.accepts(issuerChallenge)

    override fun toString(): String = "AuthorizedUltraCompiledRuntimeManifest(<redacted>)"

    internal companion object {
        private const val SHA256_BYTES = 32

        fun issue(
            request: UltraProfileActivationRequest,
            profile: VerifiedUltraProfile,
            uiSync: CompiledUiSyncRuntimeContract?,
            caf: CompiledCafRuntimeContract?,
            instrumentCrosswalk: CompiledInstrumentSignalRuntimeContract?,
            issuerChallenge: Any,
            issuerPolicyFingerprint: UltraTrustPolicyFingerprint,
            themeValidationPassObserver: (() -> Unit)?,
        ): AuthorizedUltraCompiledRuntimeManifest {
            require(uiSync != null || caf != null) {
                "An authorized Ultra manifest must compile at least one runtime"
            }
            require(instrumentCrosswalk?.matchesProfile(profile) ?: !hasInstrumentCrosswalk(profile)) {
                "Signed instrument crosswalk and verified Profile disagree"
            }
            require(instrumentCrosswalk?.matchesCaf(caf) ?: true) {
                "Instrument crosswalk and CAF schema fingerprint disagree"
            }
            require(instrumentReferenceBindingsMatch(uiSync, instrumentCrosswalk)) {
                "Selected native references are not the exact signed CLUSTER crosswalk"
            }
            val verificationIdentity = UltraProfileVerificationIdentity.from(
                request.runtimeIdentity,
                request.authenticationIdentity,
                profile,
            )
            val evidence = profileEvidence(profile)
            val validatedVariants = uiSync?.let {
                themeValidationPassObserver?.invoke()
                validateProfileDisplayAssignments(profile, it.displayAssignments)
            } ?: emptyList()
            val executableProofs = validatedVariants.map { it.executableProof }
            uiSync?.let { contract ->
                require(contract.semanticThemeProgramFingerprint ==
                    fingerprintVerifiedUiSyncThemeProgram(
                        profile, contract.displayAssignments, executableProofs,
                    )
                ) { "UI Sync semantic/theme program fingerprint is not issuer-derived" }
                if (signedLocalCanvasRows(profile) != null) {
                    val selectedIds = contract.displayAssignments.flatMap { assignment ->
                        assignment.selectedVariants().flatMap { variant ->
                            variant.slotIngressAssignments.map { it.ingressSpec.id }
                        }
                    }.toSet()
                    require(selectedIds == contract.ingressSpecs.map { it.id }.toSet()) {
                        "Signed v2 UI Sync catalog does not cover every renderer ingress"
                    }
                }
            }
            uiSync?.physicalOutputRequirement?.let { requirement ->
                require(requirement.verifiedProfileDigest == profile.candidate.contentDigest) {
                    "Compiled physical outputs are bound to another verified Profile"
                }
            }
            val digest = digestManifest(
                verificationIdentity = verificationIdentity,
                trustPolicyFingerprint = issuerPolicyFingerprint,
                runtimeIdentity = request.runtimeIdentity,
                authenticationIdentity = request.authenticationIdentity,
                profile = profile,
                evidence = evidence,
                executableStaticSelectionProofs = executableProofs,
                uiSync = uiSync,
                caf = caf,
                instrumentCrosswalk = instrumentCrosswalk,
            )
            return AuthorizedUltraCompiledRuntimeManifest(
                verificationIdentity = verificationIdentity,
                trustPolicyFingerprint = issuerPolicyFingerprint,
                runtimeIdentity = request.runtimeIdentity,
                authenticationIdentity = request.authenticationIdentity,
                profileContentDigest = profile.candidate.contentDigest,
                profileManifestDigest = profile.candidate.manifest.digest,
                phoneThemeDigest = evidence.phoneThemeDigest,
                phonePackageDigest = evidence.phonePackageDigest,
                phoneInfoDigest = evidence.phoneInfoDigest,
                exactVerifiedProfile = profile,
                trustProofDigest = evidence.trustProofDigest,
                executableStaticSelectionProofs = executableProofs,
                validatedThemeVariants = validatedVariants,
                uiSync = uiSync,
                caf = caf,
                instrumentCrosswalk = instrumentCrosswalk,
                manifestDigest = UltraCompiledRuntimeManifestDigest(digest),
                issuerChallenge = issuerChallenge,
                themeValidationPassObserver = themeValidationPassObserver,
            )
        }
    }
}

/** Private composition-root authority; persisted manifest bytes never recreate this capability. */
internal class AuthorizedUltraCompiledRuntimeManifestIssuer(
    policyFingerprint: ByteArray,
    /** Test-only pass observer; cannot influence validation or add a catalog row. */
    private val themeValidationPassObserver: (() -> Unit)? = null,
) {
    private val challenge = Any()
    private val trustPolicyFingerprint = UltraTrustPolicyFingerprint(policyFingerprint)

    fun issue(
        request: UltraProfileActivationRequest,
        profile: VerifiedUltraProfile,
        uiSync: CompiledUiSyncRuntimeContract?,
        caf: CompiledCafRuntimeContract?,
        instrumentCrosswalk: CompiledInstrumentSignalRuntimeContract? = null,
    ): AuthorizedUltraCompiledRuntimeManifest = AuthorizedUltraCompiledRuntimeManifest.issue(
        request = request,
        profile = profile,
        uiSync = uiSync,
        caf = caf,
        instrumentCrosswalk = instrumentCrosswalk,
        issuerChallenge = challenge,
        issuerPolicyFingerprint = trustPolicyFingerprint,
        themeValidationPassObserver = themeValidationPassObserver,
    )

    internal fun accepts(candidate: Any): Boolean = candidate === challenge
}

private data class ProfileEvidence(
    val phoneThemeDigest: UltraProfileDigest,
    val phonePackageDigest: UltraProfileDigest,
    val phoneInfoDigest: UltraProfileDigest,
    val trustProofDigest: ByteArray,
)

private fun hasInstrumentCrosswalk(profile: VerifiedUltraProfile): Boolean =
    profile.candidate.entries.any { it.name == INSTRUMENT_CROSSWALK_SPEC_PATH }

/** The selected slot is never inferred from Empty/Canvas or an unselected crosswalk row. */
private fun instrumentReferenceBindingsMatch(
    uiSync: CompiledUiSyncRuntimeContract?,
    crosswalk: CompiledInstrumentSignalRuntimeContract?,
): Boolean {
    val references = uiSync?.displayAssignments?.flatMap { assignment ->
        assignment.selectedVariants().flatMap { variant ->
            variant.instrumentReferenceAssignments.map { assignment to it }
        }
    }.orEmpty()
    if (references.isEmpty()) return true
    val signed = crosswalk?.assignments ?: return false
    return references.all { (display, reference) ->
        display.profileDisplayType == DisplayType.CLUSTER &&
            display.destination.role == DisplayDestinationRole.CLUSTER &&
            signed.singleOrNull {
                it.profileDisplayIndex == display.profileDisplayIndex &&
                    it.profileDisplayId == display.profileDisplayId &&
                    it.spec.address.slot == reference.slot &&
                    sameInstrumentSignalSpec(it.spec, reference.spec)
            } != null
    }
}

/** Re-derive every compiled field from the signed file; a genuine digest cannot bless forged rows. */
private fun signedInstrumentAssignmentsMatch(
    profile: VerifiedUltraProfile,
    assignments: List<CompiledInstrumentSignalDisplayAssignment>,
): Boolean = runCatching {
    val bytes = requireNotNull(profile.candidate.content(INSTRUMENT_CROSSWALK_SPEC_PATH))
    require(bytes.size in 1..64 * 1024)
    val root = instrumentObject(decodeThemeJson(bytes))
    require(root.keys == setOf("format", "bindings") &&
        root["format"] == "xcertplay-instrument-signals-v1")
    val rows = (root["bindings"] as? List<*>)?.also {
        require(it.size == assignments.size && it.size in 1..MAXIMUM_INSTRUMENT_ASSIGNMENTS)
    }?.map(::instrumentObject)?.toMutableList() ?: return@runCatching false
    assignments.forEach { assignment ->
        val index = rows.indexOfFirst { signedInstrumentBindingMatches(it, assignment) }
        require(index >= 0) { "Compiled instrument assignment differs from signed crosswalk" }
        rows.removeAt(index)
    }
    rows.isEmpty()
}.getOrDefault(false)

/** Independent issuer-side reparse: signed rows must still describe the signed Theme and CAF. */
private fun signedInstrumentDeclarationsMatch(
    profile: VerifiedUltraProfile,
    assignments: List<CompiledInstrumentSignalDisplayAssignment>,
): Boolean = runCatching {
    val packagePath = UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
    val packageBytes = requireNotNull(profile.candidate.content(packagePath))
    require(UltraProfileDigest(sha256(packageBytes)) ==
        profile.candidate.entries.single { it.name == packagePath }.digest)
    val arc = CarPlayThemeSchema.parseArcPackageJson(packageBytes)
    val assets = UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY
    val cafV1 = profile.candidate.content(assets + "xcertplay-bench-caf-v1.json")
    val cafV2 = profile.candidate.content(assets + "xcertplay-bench-caf-v2.json")
    require((cafV1 == null) != (cafV2 == null))
    val cafBytes = requireNotNull(cafV2 ?: cafV1)
    require(cafBytes.size in 1..256 * 1024)
    val cafPath = assets + if (cafV2 != null) "xcertplay-bench-caf-v2.json"
        else "xcertplay-bench-caf-v1.json"
    require(UltraProfileDigest(sha256(cafBytes)) ==
        profile.candidate.entries.single { it.name == cafPath }.digest)
    val cafRoot = instrumentObject(decodeThemeJson(cafBytes))
    require(cafRoot["format"] == if (cafV2 != null) "xcertplay-debug-caf-v2"
        else "xcertplay-debug-caf-v1")
    val tree = CafConfigTree.decode(instrumentObject(cafRoot["configTree"]))
    assignments.all { assignment ->
        val display = arc.displays.getOrNull(assignment.profileDisplayIndex)
            ?: return@all false
        if (display.id != assignment.profileDisplayId) return@all false
        InstrumentSignalBundle.validateDeclaration(display, assignment.spec, tree)
        true
    }
}.getOrDefault(false)

private fun signedInstrumentBindingMatches(
    row: Map<String, Any?>,
    assignment: CompiledInstrumentSignalDisplayAssignment,
): Boolean = runCatching {
    require(row.keys == setOf(
        "profileDisplayIndex", "profileDisplayId", "layoutIndex", "layoutId",
        "zoneIndex", "zoneId", "zoneRegion", "componentIndex", "componentId",
        "slotIndex", "positionId", "dataIdentifier", "shape", "maximumReadAgeMillis",
        "sources",
    ))
    val spec = assignment.spec
    val address = spec.address
    val slot = address.slot
    instrumentLong(row["profileDisplayIndex"]) == assignment.profileDisplayIndex.toLong() &&
        row["profileDisplayId"] == assignment.profileDisplayId &&
        instrumentLong(row["layoutIndex"]) == slot.component.zone.layout.layoutIndex.toLong() &&
        row["layoutId"] == slot.component.zone.layout.layoutId &&
        instrumentLong(row["zoneIndex"]) == slot.component.zone.zoneIndex.toLong() &&
        row["zoneId"] == slot.component.zone.zoneId &&
        row["zoneRegion"] == address.zoneRegion?.wireValue &&
        instrumentLong(row["componentIndex"]) == slot.component.componentIndex.toLong() &&
        row["componentId"] == slot.component.componentId &&
        instrumentLong(row["slotIndex"]) == slot.slotIndex.toLong() &&
        row["positionId"] == slot.positionId &&
        row["dataIdentifier"] == address.dataIdentifier &&
        row["shape"] == spec.shape.name &&
        instrumentLong(row["maximumReadAgeMillis"]) == spec.maximumReadAgeMillis &&
        (row["sources"] as? List<*>)?.let { rawSources ->
            rawSources.size == spec.sources.size &&
                rawSources.map(::instrumentObject).let { sourceRows ->
                    val remaining = sourceRows.toMutableList()
                    spec.sources.all { source ->
                        val index = remaining.indexOfFirst { signedInstrumentSourceMatches(it, source) }
                        if (index < 0) false else { remaining.removeAt(index); true }
                    } && remaining.isEmpty()
                }
        } == true
}.getOrDefault(false)

private fun signedInstrumentSourceMatches(
    row: Map<String, Any?>,
    source: com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalSource,
): Boolean = runCatching {
    require(row.keys == setOf(
        "role", "serviceIid", "serviceType", "characteristicIid",
        "characteristicType", "format", "semantics",
    ))
    val pin = source.pin
    val semantics = instrumentObject(row["semantics"])
    val meaningMatches = when (val typed = source.semantics) {
        is InstrumentSignalSemantics.Numeric ->
            semantics.keys == setOf("kind", "meaning", "exactCafUnits") &&
                semantics["kind"] == "numeric" &&
                semantics["meaning"] == typed.meaning.name &&
                semantics["exactCafUnits"] == typed.exactCafUnits
        is InstrumentSignalSemantics.State ->
            semantics.keys == setOf("kind", "codes") &&
                semantics["kind"] == "state" &&
                (semantics["codes"] as? List<*>)?.let { codes ->
                    codes.size == typed.codes.size && codes.indices.all { index ->
                        val code = instrumentObject(codes[index])
                        code.keys == setOf("raw", "name") &&
                            code["name"] == typed.codes[index].name &&
                            instrumentStateCode(code["raw"]) ==
                            instrumentStateCode(typed.codes[index].raw)
                    }
                } == true
    }
    row["role"] == source.role.name &&
        CafUInt64.parse(row["serviceIid"], "instrument service IID") == pin.serviceIid &&
        CafUInt64.parse(row["serviceType"], "instrument service type") == pin.serviceType &&
        CafUInt64.parse(row["characteristicIid"], "instrument characteristic IID") ==
            pin.characteristicIid &&
        CafUInt64.parse(row["characteristicType"], "instrument characteristic type") ==
            pin.characteristicType &&
        CafCharacteristicFormat.fromWireValue(row["format"]) == pin.characteristicFormat &&
        meaningMatches
}.getOrDefault(false)

private fun instrumentObject(value: Any?): Map<String, Any?> {
    require(value is Map<*, *> && value.keys.all { it is String })
    @Suppress("UNCHECKED_CAST")
    return value as Map<String, Any?>
}

private fun instrumentLong(value: Any?): Long = when (value) {
    is BigInteger -> {
        require(value.bitLength() <= 63) { "Signed instrument integer is out of range" }
        value.toLong()
    }
    is java.math.BigDecimal -> instrumentLong(value.toBigIntegerExact())
    is Byte, is Short, is Int, is Long -> (value as Number).toLong()
    else -> throw IllegalArgumentException("Signed instrument integer is invalid")
}

private fun instrumentStateCode(value: Any?): Any = when (value) {
    is Boolean, is String -> value
    is BigInteger -> value
    is java.math.BigDecimal -> value.toBigIntegerExact()
    is Byte, is Short, is Int, is Long -> BigInteger.valueOf((value as Number).toLong())
    else -> throw IllegalArgumentException("Signed instrument state code is invalid")
}

private fun profileEvidence(profile: VerifiedUltraProfile): ProfileEvidence {
    val entries = profile.candidate.entries.associateBy { it.name }
    fun required(name: String): UltraProfileDigest =
        requireNotNull(entries[name]) { "Verified Ultra profile is missing $name" }.digest
    return ProfileEvidence(
        phoneThemeDigest = required(UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON),
        phonePackageDigest = required(UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON),
        phoneInfoDigest = required(UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST),
        trustProofDigest = sha256(profile.trustProofBytes()),
    )
}

private class SelectedThemeVariant(
    val intent: CompiledUiSyncThemeIntent?,
    val programSelection: CompiledUiSyncThemeProgramSelection,
    val slotIngressAssignments: List<CompiledUiSyncSlotIngressAssignment>,
    val localCanvasAssignments: List<CompiledUiSyncLocalCanvasAssignment>,
    val instrumentReferenceAssignments: List<CompiledUiSyncInstrumentReferenceAssignment>,
)

private fun CompiledUiSyncDisplayAssignment.selectedVariants(): List<SelectedThemeVariant> =
    listOf(SelectedThemeVariant(primaryIntent, programSelection, slotIngressAssignments,
        localCanvasAssignments, instrumentReferenceAssignments)) +
        additionalVariants.map {
            SelectedThemeVariant(it.intent, it.programSelection, it.slotIngressAssignments,
                it.localCanvasAssignments, it.instrumentReferenceAssignments)
        }

private class ValidatedThemeVariant(
    val assignment: CompiledUiSyncDisplayAssignment,
    val intent: CompiledUiSyncThemeIntent?,
    val programSelection: CompiledUiSyncThemeProgramSelection,
    val slotIngressAssignments: List<CompiledUiSyncSlotIngressAssignment>,
    val localCanvasAssignments: List<CompiledUiSyncLocalCanvasAssignment>,
    val instrumentReferenceAssignments: List<CompiledUiSyncInstrumentReferenceAssignment>,
    val executableProof: CompiledUiSyncExecutableSelectionProof,
)

private data class IssuedThemeVariantKey(
    val displayId: UltraUiSyncDisplayId,
    val intent: CompiledUiSyncThemeIntent,
)

/**
 * An issuer-only projection of the fully validated signed catalog. This is owned by one manifest,
 * not shared by digest with another Profile or provisioning. Neither wire intent nor a digest may
 * create a row here; every value is the exact compiled assignment and crosswalk from issuance.
 */
private class IssuedThemeVariantIndex(
    private val exactProfile: VerifiedUltraProfile,
    private val issuedManifestDigest: UltraCompiledRuntimeManifestDigest,
    private val issuedProgramFingerprint: UiSyncSemanticThemeProgramFingerprint?,
    compiledContract: CompiledUiSyncRuntimeContract?,
    crosswalk: CompiledInstrumentSignalRuntimeContract?,
    validated: List<ValidatedThemeVariant>,
) {
    private val byKey: Map<IssuedThemeVariantKey, CompiledUiSyncSelectedThemeVariant> =
        LinkedHashMap<IssuedThemeVariantKey, CompiledUiSyncSelectedThemeVariant>().also { rows ->
            val assignments = compiledContract?.displayAssignments.orEmpty()
            require(validated.size == assignments.sumOf { it.selectedVariantCount }) {
                "Issued theme index does not cover the compiled variant catalog"
            }
            validated.forEach { variant ->
                require(assignments.any { it === variant.assignment }) {
                    "Issued theme index contains a foreign compiled display assignment"
                }
                val intent = variant.intent ?: return@forEach
                val instrumentAssignments = variant.instrumentReferenceAssignments.map { reference ->
                    requireNotNull(crosswalk?.assignments?.singleOrNull {
                        it.profileDisplayIndex == variant.assignment.profileDisplayIndex &&
                            it.profileDisplayId == variant.assignment.profileDisplayId &&
                            it.spec.address.slot == reference.slot &&
                            sameInstrumentSignalSpec(it.spec, reference.spec)
                    }) { "Selected native reference has no exact issuer-backed crosswalk" }
                }
                val key = IssuedThemeVariantKey(variant.assignment.uiSyncDisplayId, intent)
                require(rows.put(key, CompiledUiSyncSelectedThemeVariant(
                    variant.assignment, intent, variant.programSelection,
                    variant.slotIngressAssignments, variant.localCanvasAssignments,
                    variant.instrumentReferenceAssignments, instrumentAssignments,
                )) == null) { "Issued theme index contains an ambiguous exact wire intent" }
            }
        }.let { Collections.unmodifiableMap(it) }

    fun select(
        profile: VerifiedUltraProfile,
        manifestDigest: UltraCompiledRuntimeManifestDigest,
        programFingerprint: UiSyncSemanticThemeProgramFingerprint?,
        displayId: UltraUiSyncDisplayId,
        intent: CompiledUiSyncThemeIntent,
    ): CompiledUiSyncSelectedThemeVariant? {
        if (profile !== exactProfile || manifestDigest != issuedManifestDigest ||
            programFingerprint != issuedProgramFingerprint
        ) return null
        return byKey[IssuedThemeVariantKey(displayId, intent)]
    }
}

/**
 * Deterministic identity of the exact verified Profile's selected, executable UI Sync catalog.
 * This is a hash for compiler/factory equality, not proof of OEM trust or renderer readiness.
 * Callers may calculate the expectation before constructing their renderer, but cannot choose a
 * different value and still obtain an issued UI Sync manifest.
 */
internal fun deriveVerifiedUiSyncSemanticThemeProgramFingerprint(
    profile: VerifiedUltraProfile,
    assignments: Collection<CompiledUiSyncDisplayAssignment>,
): UiSyncSemanticThemeProgramFingerprint {
    val canonical = canonicalThemeFingerprintAssignments(assignments)
    val proofs = validateProfileDisplayAssignments(profile, canonical).map {
        it.executableProof
    }
    return fingerprintVerifiedUiSyncThemeProgram(profile, canonical, proofs)
}

private fun canonicalThemeFingerprintAssignments(
    assignments: Collection<CompiledUiSyncDisplayAssignment>,
): List<CompiledUiSyncDisplayAssignment> {
    val rows = boundedList(assignments, MAXIMUM_UI_SYNC_DISPLAYS,
        "verified UI Sync theme assignments").sortedBy { it.profileDisplayIndex }
    require(rows.isNotEmpty() &&
        rows.map { it.profileDisplayIndex }.toSet().size == rows.size &&
        rows.map { it.uiSyncDisplayId }.toSet().size == rows.size &&
        rows.sumOf { it.selectedVariantCount } <= MAXIMUM_TOTAL_THEME_VARIANTS
    ) { "Verified UI Sync theme assignments are ambiguous or exceed the catalog limit" }
    return rows
}

private fun fingerprintVerifiedUiSyncThemeProgram(
    profile: VerifiedUltraProfile,
    assignments: List<CompiledUiSyncDisplayAssignment>,
    executableProofs: List<CompiledUiSyncExecutableSelectionProof>,
): UiSyncSemanticThemeProgramFingerprint {
    val rows = canonicalThemeFingerprintAssignments(assignments)
    require(executableProofs.size == rows.sumOf { it.selectedVariantCount }) {
        "Every selected theme variant needs one exact executable proof"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("xcertplay-verified-uisync-semantic-theme-program-v3\u0000".toByteArray(
        StandardCharsets.US_ASCII,
    ))
    digestField(digest, profile.candidate.contentDigest.bytes())
    digestField(digest, profile.candidate.manifest.digest.bytes())
    digestField(digest, intBytes(rows.size))
    var proofIndex = 0
    rows.forEach { assignment ->
        digestField(digest, assignment.phonePackageDigest.bytes())
        digestField(digest, intBytes(assignment.profileDisplayIndex))
        digest.update(if (assignment.profileDisplayId == null) 0 else 1)
        assignment.profileDisplayId?.let {
            digestField(digest, it.toByteArray(StandardCharsets.UTF_8))
        }
        digestField(digest, assignment.profileDisplayType.wireValue.toByteArray(
            StandardCharsets.US_ASCII,
        ))
        digestField(digest, assignment.destination.role.name.toByteArray(StandardCharsets.US_ASCII))
        digestField(digest, assignment.destination.instanceId.value.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, assignment.topologyDisplayId.value.toString().toByteArray(
            StandardCharsets.US_ASCII,
        ))
        digestField(digest, outputIdWire(assignment.outputId))
        digestField(digest, assignment.uiSyncDisplayId.value.toByteArray(StandardCharsets.UTF_8))
        val variants = assignment.selectedVariants()
        digestField(digest, intBytes(variants.size))
        variants.forEachIndexed { index, variant ->
            // Primacy is signed semantics: the primary row is the initial local program.
            digest.update(if (index == 0) 1 else 0)
            val intent = variant.intent
            digest.update(if (intent == null) 0 else 1)
            intent?.let {
                digestField(digest, it.layoutID.toByteArray(StandardCharsets.UTF_8))
                digestField(digest, intBytes(it.activeComponents.size))
                it.activeComponents.forEach { (zone, component) ->
                    digestField(digest, zone.toByteArray(StandardCharsets.UTF_8))
                    digestField(digest, component.toByteArray(StandardCharsets.UTF_8))
                }
                digestField(digest, it.paletteID.toByteArray(StandardCharsets.UTF_8))
            }
            digestThemeVariant(digest, variant)
            digestField(digest, executableProofs[proofIndex++].bytes())
        }
    }
    check(proofIndex == executableProofs.size)
    return UiSyncSemanticThemeProgramFingerprint(digest.digest())
}

/** The compiler's row is accepted only if the exact verified package contains that display. */
private fun validateProfileDisplayAssignments(
    profile: VerifiedUltraProfile,
    assignments: List<CompiledUiSyncDisplayAssignment>,
): List<ValidatedThemeVariant> {
    val packageEntry = requireNotNull(profile.candidate.entries.singleOrNull {
        it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
    }) { "Verified profile has no unique phone package" }
    val bytes = requireNotNull(profile.candidate.content(
        UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
    )) { "Verified profile has no phone package bytes" }
    require(UltraProfileDigest(sha256(bytes)) == packageEntry.digest) {
        "Verified phone package bytes changed"
    }
    val arc = CarPlayThemeSchema.parseArcPackageJson(bytes)
    require(arc.extensions.isEmpty() && arc.driveModeDynamicAssignmentConfiguration == null &&
        arc.vehicleType == null && arc.vehicleTypes == null
    ) { "Active ArcPackage semantics are not executable" }
    val displays = arc.displays
    require(displays.mapNotNull { it.id }.toSet().size ==
        displays.count { it.id != null }) {
        "Verified Profile contains duplicate nonempty display IDs"
    }
    require(assignments.isNotEmpty() && assignments.size <= displays.size) {
        "Compiled assignments exceed verified profile displays"
    }
    require(assignments.sumOf { it.selectedVariantCount } <= MAXIMUM_TOTAL_THEME_VARIANTS) {
        "Compiled theme catalog exceeds the total variant limit"
    }
    val signedCatalog = signedLocalCanvasRows(profile)
    val compiler = CarPlayThemeProgramCompiler()
    // One layout may have several signed inert palettes. Reusing a witness across palette
    // selections would let an otherwise valid structural program bless the wrong type-3 intent.
    val styleWitnesses = HashMap<Triple<Int, Int, String>, CarPlayThemeResolvedStyleWitness>()
    val validated = assignments.flatMap { assignment ->
        require(assignment.phonePackageDigest == packageEntry.digest) {
            "Compiled display assignment has another phone package digest"
        }
        if (signedCatalog?.version == SignedUiSyncCatalogVersion.V3) {
            require(assignment.primaryIntent ==
                signedCatalog.primaryIntents[assignment.profileDisplayIndex]) {
                "Compiled v3 primary intent differs from the first signed display layout row"
            }
        } else {
            val selected = assignment.selectedVariants()
            require(selected.map { it.programSelection.layoutIndex }.toSet().size ==
                selected.size) {
                "A v1/v2 theme catalog cannot select one layout more than once"
            }
        }
        val display = requireNotNull(displays.getOrNull(assignment.profileDisplayIndex)) {
            "Compiled display assignment index is absent from the verified package"
        }
        require(display.id == assignment.profileDisplayId &&
            display.type == assignment.profileDisplayType
        ) { "Compiled display assignment differs from the verified package display" }
        require(display.layouts.map { it.id }.toSet().size == display.layouts.size) {
            "Verified Profile display has ambiguous duplicate layout IDs"
        }
        assignment.selectedVariants().mapIndexed { variantOrdinal, variant ->
            val program = variant.programSelection
            val layout = requireNotNull(display.layouts.getOrNull(program.layoutIndex)) {
                "Compiled theme layout index is absent from the verified package display"
            }
            val intent = variant.intent
            if (signedCatalog?.version == SignedUiSyncCatalogVersion.V3) {
                val v3Intent = requireNotNull(intent) {
                    "Signed v3 layout needs an exact wire intent"
                }
                require(CarPlayThemeActiveComponentCrosswalk.primarySelections(
                    display, program.layoutIndex, v3Intent.activeComponents,
                ) == program.activePrimarySelections) {
                    "Signed v3 activeComponents differ from exact Profile primary selections"
                }
            } else {
                require(intent == null || intent.isSupportedStaticIntent()) {
                    "Nonempty activeComponents require an exact signed v3 Profile crosswalk"
                }
            }
            require(intent == null || intent.layoutID == layout.id) {
                "Compiled theme intent layout ID differs from the verified package layout"
            }
            val state = program.stateSnapshot()
            // The issuer independently repeats the exact signed-phone-file check. A style
            // witness only applies to its own reparsed Display and selected layout, leaving
            // every other layout on the compiler's default fail-closed path.
            val styleWitness = layout.layoutStyleID?.let {
                val selectedPaletteID = requireNotNull(intent?.paletteID) {
                    "A styled Profile layout requires an explicit signed palette intent"
                }
                styleWitnesses.getOrPut(Triple(
                    assignment.profileDisplayIndex, program.layoutIndex, selectedPaletteID,
                )) {
                    CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(
                        profile, assignment.profileDisplayIndex, program.layoutIndex,
                        selectedPaletteID,
                    )
                }.also { witness ->
                    require(witness.phonePackageDigest == packageEntry.digest &&
                        witness.phoneThemeDigest == profile.candidate.entries.singleOrNull {
                            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON
                        }?.digest &&
                        witness.profileContentDigest == profile.candidate.contentDigest &&
                        witness.displayIndex == assignment.profileDisplayIndex &&
                        witness.layoutIndex == program.layoutIndex &&
                        witness.styleID == it &&
                        selectedPaletteID == witness.selectedPaletteID
                    ) { "Verified phone style witness differs from selected Profile layout" }
                    // The primary row may render from the first raw layout frame before the
                    // phone sends type-3, which is the only wire source of paletteID. Never
                    // display a nondefault palette as that implicit initial selection.
                    if (variantOrdinal == 0) {
                        require(selectedPaletteID == witness.defaultPaletteID) {
                            "Styled primary theme intent differs from the signed default palette"
                        }
                    }
                }
            }
            val assessment = compiler.assessExecutableStaticSubset(
                styleWitness?.display ?: display,
                assignment.profileDisplayIndex, program.layoutIndex, state,
                variant.instrumentReferenceAssignments.map { it.spec },
                styleWitness,
            )
            val proof = requireNotNull(assessment.proof) {
                "Selected verified Profile layout has unresolved active drawing or static executor issues: " +
                    assessment.issues.joinToString(limit = 8) { it.code.name }
            }
            val compiledProgram = assessment.structuralProgram
            require(compiledProgram.structuralFingerprint == program.structuralFingerprint) {
                "Compiled theme program differs from the verified package and canonical state"
            }
            require(proof.displayIndex == assignment.profileDisplayIndex &&
                proof.displayId == assignment.profileDisplayId &&
                proof.displayType == assignment.profileDisplayType &&
                proof.layoutAddress.layoutId == layout.id &&
                proof.matches(assignment.profileDisplayIndex, program.layoutIndex, state,
                    program.structuralFingerprint)
            ) { "Selected static executable proof differs from the signed Profile display row" }
            val activeInputSlots = proof.videoSlots.map { it.address to it.streamIdentifier }
            require(activeInputSlots.size == variant.slotIngressAssignments.size &&
                activeInputSlots.toSet() == variant.slotIngressAssignments.map {
                    it.slot to it.streamIdentifier
                }.toSet()
            ) { "Compiled slot ingress assignments do not cover the selected Profile program" }
            val activeInstruments = proof.instrumentReferenceSlots.map {
                it.address to it.dataIdentifier
            }
            require(activeInstruments.size == variant.instrumentReferenceAssignments.size &&
                activeInstruments.toSet() == variant.instrumentReferenceAssignments.map {
                    it.slot to it.spec.address.dataIdentifier
                }.toSet()
            ) { "Compiled native references do not cover the selected CLUSTER program" }
            val activeEmptySlots = assessment.structuralProgram.instructions
                .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                .filter { it.address.component.zone.layout.layoutIndex == program.layoutIndex &&
                    it.source == CarPlayThemeStructuralSource.Empty &&
                    it.style == SlotStyleCase.RECTANGULAR &&
                    (it.safeAreaInsets?.let { insets -> insets.top == 0.0 &&
                        insets.leading == 0.0 && insets.bottom == 0.0 &&
                        insets.trailing == 0.0 } != false) &&
                    localCanvasFitsViewport(assessment.structuralProgram.logicalSize,
                        it.frameInDisplayLogicalSpace)
                }
                .map { it.address }.toSet()
            require(variant.localCanvasAssignments.all { it.slot in activeEmptySlots }) {
                "Compiled local Canvas assignment is not an active Empty Profile rectangle"
            }
            val localAddresses = variant.localCanvasAssignments.map { it.slot }.toSet()
            val drawableSlots = assessment.structuralProgram.instructions
                .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                .filter { it.address.component.zone.layout.layoutIndex == program.layoutIndex &&
                    (it.source is CarPlayThemeStructuralSource.InputStream ||
                        it.address in localAddresses)
                }
            drawableSlots.forEachIndexed { position, later ->
                require(drawableSlots.subList(0, position).none { earlier ->
                    (later.address in localAddresses || earlier.address in localAddresses) &&
                        later.address.component != earlier.address.component &&
                        localCanvasRectsOverlap(later.frameInDisplayLogicalSpace,
                            earlier.frameInDisplayLogicalSpace)
                }) { "Local Canvas crosses an active drawable in another component" }
            }
            ValidatedThemeVariant(
                assignment, intent, program, variant.slotIngressAssignments,
                variant.localCanvasAssignments, variant.instrumentReferenceAssignments,
                fingerprintExecutableSelection(proof),
            )
        }
    }
    if (signedCatalog == null) {
        require(validated.all { it.localCanvasAssignments.isEmpty() }) {
            "Local Canvas assignment requires a signed v2 UI Sync catalog"
        }
    } else {
        val compiled = validated.associate { row ->
            val intent = requireNotNull(row.intent) {
                "Signed UI Sync layout needs an exact wire intent"
            }
            require(when (row.assignment.profileDisplayType) {
                DisplayType.CENTER_CONSOLE ->
                    row.assignment.destination.role == DisplayDestinationRole.MAIN
                DisplayType.CLUSTER ->
                    row.assignment.destination.role == DisplayDestinationRole.CLUSTER
                DisplayType.PASSENGER, DisplayType.SECONDARY_CLUSTER -> false
            }) { "Signed v2 destination role differs from the verified Profile display type" }
            val screenId = when (row.assignment.destination.role) {
                DisplayDestinationRole.MAIN -> AirPlayInfoPlist.MAIN_UUID
                DisplayDestinationRole.CLUSTER -> AirPlayInfoPlist.ALT_UUID
                else -> throw IllegalArgumentException(
                    "Signed v2 UI Sync destination role is unsupported")
            }
            val streamType = if (row.assignment.destination.role ==
                DisplayDestinationRole.MAIN) 110 else 111
            require(row.slotIngressAssignments.all { slot ->
                val identity = slot.ingressSpec.airPlayStreamIdentity
                identity.type == streamType && identity.streamConnectionId == null &&
                    identity.clientTypeUuid == null &&
                    slot.ingressSpec.setupDisplayUuid == screenId
            }) { "Compiled v2 ingress selector differs from its fixed Host ScreenID" }
            (row.assignment.profileDisplayIndex to intent) to
                SignedLocalCanvasVariant(
                    SignedUiSyncRoute(
                        row.assignment.profileDisplayId, screenId,
                        row.assignment.destination.role,
                        row.assignment.destination.instanceId.value,
                        row.assignment.topologyDisplayId.value.toString(),
                        row.assignment.outputId, row.assignment.uiSyncDisplayId.value,
                    ),
                    row.programSelection.layoutIndex,
                    intent,
                    row.programSelection.activePrimarySelections.map {
                        it.zoneIndex to it.componentIndex
                    },
                    row.slotIngressAssignments.map { slot ->
                        SignedUiSyncSlotIngress(
                            slot.slot.component.zone.zoneIndex,
                            slot.slot.component.componentIndex, slot.slot.slotIndex,
                            slot.streamIdentifier, slot.ingressSpec.id.value,
                        )
                    },
                    row.localCanvasAssignments.map {
                        SignedLocalCanvasSlot(it.slot.component.zone.zoneIndex,
                            it.slot.component.componentIndex, it.slot.slotIndex, it.contentId)
                    },
                )
        }
        require(compiled.size == validated.size && compiled == signedCatalog.variants) {
            when (signedCatalog.version) {
                SignedUiSyncCatalogVersion.V1 ->
                    "Compiled UI Sync route, intent or ingress rows differ from signed v1 catalog"
                SignedUiSyncCatalogVersion.V2 ->
                    "Compiled UI Sync route, ingress or local Canvas rows differ from signed v2 catalog"
                SignedUiSyncCatalogVersion.V3 ->
                    "Compiled UI Sync route, intent, ingress or local Canvas rows differ from signed v3 catalog"
            }
        }
    }
    return validated
}

private data class SignedLocalCanvasSlot(
    val zoneIndex: Int,
    val componentIndex: Int,
    val slotIndex: Int,
    val contentId: String,
)

private data class SignedUiSyncSlotIngress(
    val zoneIndex: Int,
    val componentIndex: Int,
    val slotIndex: Int,
    val streamIdentifier: String,
    val ingressId: String,
)

private data class SignedUiSyncRoute(
    val profileDisplayId: String?,
    val advertisedScreenId: String,
    val destinationRole: DisplayDestinationRole,
    val destinationInstanceId: String,
    val topologyDisplayId: String,
    val outputId: UltraDisplayOutputId,
    val uiSyncDisplayId: String,
)

private data class SignedLocalCanvasVariant(
    val route: SignedUiSyncRoute,
    val layoutIndex: Int,
    val intent: CompiledUiSyncThemeIntent,
    val primarySelections: List<Pair<Int, Int>>,
    val slotIngresses: List<SignedUiSyncSlotIngress>,
    val slots: List<SignedLocalCanvasSlot>,
)

private enum class SignedUiSyncCatalogVersion { V1, V2, V3 }

private data class SignedUiSyncCatalog(
    val version: SignedUiSyncCatalogVersion,
    val variants: Map<Pair<Int, CompiledUiSyncThemeIntent>, SignedLocalCanvasVariant>,
    /** v3's first signed row per display is the initial program, not just a set member. */
    val primaryIntents: Map<Int, CompiledUiSyncThemeIntent>,
)

/** Independently re-read signed v1/v2/v3 bytes; caller-created rows are insufficient. */
private fun signedLocalCanvasRows(
    profile: VerifiedUltraProfile,
): SignedUiSyncCatalog? {
    val assets = UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY
    val v1 = profile.candidate.content(assets + "xcertplay-bench-uisync-v1.json")
    val v2 = profile.candidate.content(assets + "xcertplay-bench-uisync-v2.json")
    val v3 = profile.candidate.content(assets + "xcertplay-bench-uisync-v3.json")
    require(listOfNotNull(v1, v2, v3).size <= 1) {
        "Signed UI Sync catalog has competing versions"
    }
    val bytes = v3 ?: v2 ?: v1 ?: return null
    val version = when {
        v3 != null -> SignedUiSyncCatalogVersion.V3
        v2 != null -> SignedUiSyncCatalogVersion.V2
        else -> SignedUiSyncCatalogVersion.V1
    }
    val suffix = when (version) {
        SignedUiSyncCatalogVersion.V1 -> "v1"
        SignedUiSyncCatalogVersion.V2 -> "v2"
        SignedUiSyncCatalogVersion.V3 -> "v3"
    }
    val path = assets + "xcertplay-bench-uisync-$suffix.json"
    require(bytes.size in 1..256 * 1024 &&
        profile.candidate.entries.singleOrNull { it.name == path }?.digest ==
            UltraProfileDigest(sha256(bytes))
    ) { "Signed UI Sync catalog bytes differ from the verified Profile entry" }
    val root = signedCanvasObject(decodeThemeJson(bytes), "signed UI Sync catalog")
    val expectedFormat = "xcertplay-debug-uisync-$suffix"
    require(root.keys == setOf("format", "displays") &&
        root["format"] == expectedFormat) {
        "Signed UI Sync catalog has the wrong format"
    }
    val displays = signedCanvasList(root["displays"], "displays", 64)
    require(displays.isNotEmpty()) { "Signed v2 UI Sync catalog has no displays" }
    val result = LinkedHashMap<Pair<Int, CompiledUiSyncThemeIntent>, SignedLocalCanvasVariant>()
    val primaryIntents = LinkedHashMap<Int, CompiledUiSyncThemeIntent>()
    val v2DisplayLayouts = HashSet<Pair<Int, Int>>()
    val signedDisplayIndices = HashSet<Int>()
    val contentIds = HashSet<String>()
    var totalVariants = 0
    var totalSlots = 0
    displays.forEach { displayValue ->
        val display = signedCanvasObject(displayValue, "display")
        require(display.keys == setOf("profileDisplayIndex", "profileDisplayId",
            "advertisedScreenId", "destinationRole", "destinationInstanceId",
            "topologyDisplayId", "outputId", "uiSyncDisplayId", "layouts")) {
            "Signed v2 UI Sync display has missing or unknown fields"
        }
        val displayIndex = signedCanvasIndex(display["profileDisplayIndex"],
            "profileDisplayIndex")
        require(signedDisplayIndices.add(displayIndex)) {
            "Signed UI Sync catalog repeats a Profile display block"
        }
        val displayId = display["profileDisplayId"]?.let {
            signedCanvasIdentifier(it, "profileDisplayId")
        }
        val role = when (signedCanvasIdentifier(display["destinationRole"],
            "destinationRole")) {
            "MAIN" -> DisplayDestinationRole.MAIN
            "CLUSTER" -> DisplayDestinationRole.CLUSTER
            else -> throw IllegalArgumentException("Signed v2 destination role is unsupported")
        }
        val screenId = signedCanvasIdentifier(display["advertisedScreenId"],
            "advertisedScreenId")
        require(screenId == if (role == DisplayDestinationRole.MAIN)
            AirPlayInfoPlist.MAIN_UUID else AirPlayInfoPlist.ALT_UUID
        ) { "Signed v2 ScreenID differs from its Host destination role" }
        val topologyId = signedCanvasIdentifier(display["topologyDisplayId"],
            "topologyDisplayId")
        require(UUID.fromString(topologyId).toString() == topologyId) {
            "Signed v2 topology display ID is not canonical"
        }
        val output = signedCanvasObject(display["outputId"], "outputId")
        require(output.keys == setOf("kind", "value")) {
            "Signed v2 output ID has missing or unknown fields"
        }
        val outputValue = signedCanvasIdentifier(output["value"], "outputId.value")
        val outputId = when (output["kind"]) {
            "android" -> UltraDisplayOutputId.Android(outputValue)
            "custom" -> UltraDisplayOutputId.Custom(outputValue)
            else -> throw IllegalArgumentException("Signed v2 output ID kind is unsupported")
        }
        val route = SignedUiSyncRoute(
            displayId, screenId, role,
            signedCanvasIdentifier(display["destinationInstanceId"],
                "destinationInstanceId"),
            topologyId, outputId,
            signedCanvasIdentifier(display["uiSyncDisplayId"], "uiSyncDisplayId"),
        )
        val layouts = signedCanvasList(display["layouts"], "layouts", 16)
        require(layouts.isNotEmpty()) { "Signed v2 UI Sync display has no layouts" }
        totalVariants += layouts.size
        require(totalVariants <= MAXIMUM_TOTAL_THEME_VARIANTS) {
            "Signed v2 UI Sync catalog has too many variants"
        }
        layouts.forEachIndexed { layoutOrdinal, layoutValue ->
            val layout = signedCanvasObject(layoutValue, "layout")
            val expectedKeys = setOf("layoutIndex", "layoutID", "paletteID",
                "activeComponents", "activePrimarySelections", "slotIngresses") +
                if (version == SignedUiSyncCatalogVersion.V1) emptySet() else
                    setOf("localCanvasSlots")
            require(layout.keys == expectedKeys) {
                "Signed UI Sync layout has missing or unknown fields"
            }
            val layoutIndex = signedCanvasIndex(layout["layoutIndex"], "layoutIndex")
            val layoutId = signedCanvasIdentifier(layout["layoutID"], "layoutID")
            val paletteId = signedCanvasIdentifier(layout["paletteID"], "paletteID")
            val components = signedCanvasObject(layout["activeComponents"], "activeComponents")
            require(components.size <= MAXIMUM_THEME_ACTIVE_COMPONENTS) {
                "Signed activeComponents exceed the limit"
            }
            val activeComponents = components.map { (zone, component) ->
                signedCanvasIdentifier(zone, "activeComponents zone") to
                    signedCanvasIdentifier(component, "activeComponents component")
            }.toMap()
            require(version == SignedUiSyncCatalogVersion.V3 || activeComponents.isEmpty()) {
                "Signed v2 activeComponents are not statically executable"
            }
            val intent = CompiledUiSyncThemeIntent(layoutId, activeComponents, paletteId)
            if (version == SignedUiSyncCatalogVersion.V3 && layoutOrdinal == 0) {
                require(primaryIntents.putIfAbsent(displayIndex, intent) == null) {
                    "Signed v3 UI Sync catalog repeats a display block"
                }
            }
            val primarySelections = signedCanvasList(layout["activePrimarySelections"],
                "activePrimarySelections", MAXIMUM_THEME_PRIMARY_SELECTIONS).map { value ->
                val selection = signedCanvasObject(value, "active primary selection")
                require(selection.keys == setOf("zoneIndex", "componentIndex")) {
                    "Signed primary selection has missing or unknown fields"
                }
                signedCanvasIndex(selection["zoneIndex"], "zoneIndex") to
                    signedCanvasIndex(selection["componentIndex"], "componentIndex")
            }.sortedWith(compareBy({ it.first }, { it.second }))
            require(primarySelections.map { it.first }.toSet().size ==
                primarySelections.size) {
                "Signed v2 primary selections repeat a zone"
            }
            val ingressSlots = signedCanvasList(layout["slotIngresses"], "slotIngresses",
                MAXIMUM_THEME_SLOT_INGRESSES).map { slotValue ->
                val slot = signedCanvasObject(slotValue, "slot ingress")
                require(slot.keys == setOf("zoneIndex", "componentIndex", "slotIndex",
                    "streamIdentifier", "ingressId")) {
                    "Signed v2 slot ingress has missing or unknown fields"
                }
                SignedUiSyncSlotIngress(
                    signedCanvasIndex(slot["zoneIndex"], "zoneIndex"),
                    signedCanvasIndex(slot["componentIndex"], "componentIndex"),
                    signedCanvasIndex(slot["slotIndex"], "slotIndex"),
                    signedCanvasIdentifier(slot["streamIdentifier"], "streamIdentifier"),
                    signedCanvasIdentifier(slot["ingressId"], "ingressId"),
                )
            }.sortedWith(compareBy({ it.zoneIndex }, { it.componentIndex }, { it.slotIndex }))
            require(ingressSlots.map { Triple(it.zoneIndex, it.componentIndex, it.slotIndex) }
                .toSet().size == ingressSlots.size) {
                "Signed v2 slot ingress repeats an active slot"
            }
            val localCanvasRows: List<*> = if (version == SignedUiSyncCatalogVersion.V1)
                emptyList<Any?>() else
                signedCanvasList(layout["localCanvasSlots"], "localCanvasSlots",
                    MAXIMUM_THEME_SLOT_INGRESSES)
            val slots = localCanvasRows.map { slotValue ->
                val slot = signedCanvasObject(slotValue, "local Canvas slot")
                require(slot.keys == setOf("zoneIndex", "componentIndex", "slotIndex",
                    "contentId")) { "Signed local Canvas slot has missing or unknown fields" }
                val contentId = signedCanvasIdentifier(slot["contentId"], "contentId")
                UltraContentId(contentId)
                require(contentIds.add(contentId)) {
                    "Signed local Canvas content ID is reused across selected layouts"
                }
                SignedLocalCanvasSlot(
                    signedCanvasIndex(slot["zoneIndex"], "zoneIndex"),
                    signedCanvasIndex(slot["componentIndex"], "componentIndex"),
                    signedCanvasIndex(slot["slotIndex"], "slotIndex"), contentId,
                )
            }
            totalSlots += slots.size
            require(totalSlots <= MAXIMUM_THEME_SLOT_INGRESSES &&
                slots.map { Triple(it.zoneIndex, it.componentIndex, it.slotIndex) }
                    .toSet().size == slots.size
            ) { "Signed local Canvas slots exceed limit or repeat a selected slot" }
            require(version == SignedUiSyncCatalogVersion.V3 ||
                v2DisplayLayouts.add(displayIndex to layoutIndex)) {
                "Signed v2 UI Sync catalog repeats a display/layout"
            }
            require(result.putIfAbsent(displayIndex to intent,
                SignedLocalCanvasVariant(route, layoutIndex, intent,
                    primarySelections, ingressSlots, slots.sortedWith(compareBy(
                        { it.zoneIndex }, { it.componentIndex }, { it.slotIndex },
                    )))) == null) { "Signed UI Sync catalog repeats an exact display/intent" }
        }
    }
    return SignedUiSyncCatalog(version, Collections.unmodifiableMap(result),
        Collections.unmodifiableMap(primaryIntents))
}

private fun signedCanvasObject(value: Any?, label: String): Map<String, Any?> {
    require(value is Map<*, *> && value.keys.all { it is String }) {
        "$label must be an object"
    }
    @Suppress("UNCHECKED_CAST")
    return value as Map<String, Any?>
}

private fun signedCanvasList(value: Any?, label: String, maximum: Int): List<*> {
    require(value is List<*> && value.size <= maximum) { "$label has invalid cardinality" }
    return value
}

private fun signedCanvasIdentifier(value: Any?, label: String): String {
    require(value is String && value.isNotBlank() && value.length <= 512 &&
        value.none(Char::isISOControl)) { "$label is invalid" }
    return value
}

private fun signedCanvasIndex(value: Any?, label: String): Int {
    val number = when (value) {
        is Long -> value
        is BigInteger -> {
            require(value.signum() >= 0 && value <= BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
                "$label is out of range"
            }
            value.toLong()
        }
        else -> throw IllegalArgumentException("$label must be an integer")
    }
    require(number in 0..Int.MAX_VALUE) { "$label is out of range" }
    return number.toInt()
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

private fun fingerprintExecutableSelection(
    proof: CarPlayThemeExecutableStaticSubsetProof,
): CompiledUiSyncExecutableSelectionProof {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("xcertplay-verified-static-theme-selection-v2\u0000".toByteArray(
        StandardCharsets.US_ASCII,
    ))
    digestField(digest, intBytes(proof.displayIndex))
    digestField(digest, proof.displayType.wireValue.toByteArray(StandardCharsets.US_ASCII))
    digest.update(if (proof.displayId == null) 0 else 1)
    proof.displayId?.let { digestField(digest, it.toByteArray(StandardCharsets.UTF_8)) }
    digestField(digest, intBytes(proof.layoutAddress.layoutIndex))
    digestField(digest, proof.layoutAddress.layoutId.toByteArray(StandardCharsets.UTF_8))
    digestField(digest, intBytes(proof.selectedPrimaryComponents.size))
    proof.selectedPrimaryComponents.forEach { selection ->
        digestField(digest, intBytes(selection.layoutIndex))
        digestField(digest, intBytes(selection.zoneIndex))
        digestField(digest, intBytes(selection.componentIndex))
    }
    digestField(digest, proof.structuralFingerprint.bytes())
    val color = proof.backgroundColor
    listOf(color.red, color.green, color.blue, color.alpha).forEach { channel ->
        digestField(digest, longBytes(java.lang.Double.doubleToLongBits(channel)))
    }
    require(proof.videoSlots.size <= MAXIMUM_THEME_SLOT_INGRESSES) {
        "Selected static executable proof has too many video slots"
    }
    digestField(digest, intBytes(proof.videoSlots.size))
    proof.videoSlots.forEach { video ->
        val slot = video.address
        digestField(digest, intBytes(slot.component.zone.layout.layoutIndex))
        digestField(digest, slot.component.zone.layout.layoutId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(slot.component.zone.zoneIndex))
        digestField(digest, slot.component.zone.zoneId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(slot.component.componentIndex))
        digestField(digest, slot.component.componentId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(slot.slotIndex))
        digestField(digest, slot.positionId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, video.streamIdentifier.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, video.stage.name.toByteArray(StandardCharsets.US_ASCII))
        digestField(digest, intBytes(video.zIndex))
        val frame = video.frameInDisplayLogicalSpace
        listOf(frame.origin.x, frame.origin.y, frame.size.width, frame.size.height).forEach {
            digestField(digest, longBytes(java.lang.Double.doubleToLongBits(it)))
        }
    }
    require(proof.instrumentReferenceSlots.size <= 1) {
        "Selected static proof has more than one native reference"
    }
    digestField(digest, intBytes(proof.instrumentReferenceSlots.size))
    proof.instrumentReferenceSlots.forEach { reference ->
        val slot = reference.address
        digestField(digest, intBytes(slot.component.zone.layout.layoutIndex))
        digestField(digest, slot.component.zone.layout.layoutId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(slot.component.zone.zoneIndex))
        digestField(digest, slot.component.zone.zoneId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(slot.component.componentIndex))
        digestField(digest, slot.component.componentId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(slot.slotIndex))
        digestField(digest, slot.positionId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, reference.kind.wireValue.toByteArray(StandardCharsets.US_ASCII))
        digestField(digest, reference.dataIdentifier.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, reference.stage.name.toByteArray(StandardCharsets.US_ASCII))
        digestField(digest, intBytes(reference.zIndex))
        val frame = reference.frameInDisplayLogicalSpace
        listOf(frame.origin.x, frame.origin.y, frame.size.width, frame.size.height).forEach {
            digestField(digest, longBytes(java.lang.Double.doubleToLongBits(it)))
        }
    }
    return CompiledUiSyncExecutableSelectionProof(digest.digest())
}

/** A physical preflight may target only the signed rows and the exact static-plan output objects. */
private fun requirePhysicalAssignments(
    outputs: List<UltraDisplayGraphOutput>,
    assignments: List<CompiledUiSyncDisplayAssignment>,
    requirement: UiSyncPhysicalOutputRequirement,
    requireExactOutputInstances: Boolean = true,
) {
    require(requirement.assignments.size == assignments.size &&
        requirement.assignments.size == outputs.size
    ) { "Compiled physical assignments do not cover every signed output" }
    val compiledByOutput = assignments.associateBy { it.outputId }
    require(requirement.assignments.all { physical ->
        val output = physical.output
        val compiled = compiledByOutput[output.outputId]
        compiled != null &&
            outputs.any { if (requireExactOutputInstances) it === output else it == output } &&
            compiled.graphOutput() == output &&
            compiled.destination.role.toPhysicalRole() == physical.destination.role &&
            compiled.destination.instanceId.value == physical.destination.instanceId
    }) { "Compiled physical assignments differ from signed destinations or static outputs" }
}

private fun DisplayDestinationRole.toPhysicalRole(): UiSyncPhysicalDestinationRole = when (this) {
    DisplayDestinationRole.MAIN -> UiSyncPhysicalDestinationRole.MAIN
    DisplayDestinationRole.CLUSTER -> UiSyncPhysicalDestinationRole.CLUSTER
    DisplayDestinationRole.HUD -> UiSyncPhysicalDestinationRole.HUD
    DisplayDestinationRole.AUXILIARY -> UiSyncPhysicalDestinationRole.AUXILIARY
}

private fun digestManifest(
    verificationIdentity: UltraProfileVerificationIdentity,
    trustPolicyFingerprint: UltraTrustPolicyFingerprint,
    runtimeIdentity: UltraRuntimeIdentity,
    authenticationIdentity: MfiAuthenticationIdentity,
    profile: VerifiedUltraProfile,
    evidence: ProfileEvidence,
    executableStaticSelectionProofs: List<CompiledUiSyncExecutableSelectionProof>,
    uiSync: CompiledUiSyncRuntimeContract?,
    caf: CompiledCafRuntimeContract?,
    instrumentCrosswalk: CompiledInstrumentSignalRuntimeContract?,
): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("xcertplay-authorized-ultra-compiled-runtime-v12\u0000".toByteArray(
        StandardCharsets.US_ASCII,
    ))
    digestField(digest, verificationIdentity.bytes())
    digestField(digest, trustPolicyFingerprint.bytes())
    digestField(digest, runtimeIdentity.vehicleId.toByteArray(StandardCharsets.UTF_8))
    digestField(digest, runtimeIdentity.authenticationKind.name.toByteArray(StandardCharsets.US_ASCII))
    digestField(digest, runtimeIdentity.authenticationIdentitySha256)
    digestField(digest, authenticationIdentity.certificateType.name.toByteArray(StandardCharsets.US_ASCII))
    digestField(digest, authenticationIdentity.sha256Bytes())
    val binding = profile.binding
    listOf(
        binding.vehicleId,
        binding.mfiCertificateIdentity,
        binding.assetIdentifier,
        binding.iosContentVersion,
        binding.accessoryContentVersion,
    ).forEach { digestField(digest, it.toByteArray(StandardCharsets.UTF_8)) }
    digestField(digest, profile.candidate.contentDigest.bytes())
    digestField(digest, profile.candidate.manifest.digest.bytes())
    digestField(digest, evidence.phoneThemeDigest.bytes())
    digestField(digest, evidence.phonePackageDigest.bytes())
    digestField(digest, evidence.phoneInfoDigest.bytes())
    digestField(digest, evidence.trustProofDigest)

    digest.update(if (uiSync == null) 0 else 1)
    uiSync?.let { contract ->
        digestField(digest, contract.schemaFingerprint.bytes())
        digestField(digest, contract.bindingFingerprint.bytes())
        digestField(digest, contract.semanticThemeProgramFingerprint.bytes())
        digestField(digest, contract.rendererImplementationId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(contract.displayIds.size))
        contract.displayIds.sorted().forEach {
            digestField(digest, it.toByteArray(StandardCharsets.UTF_8))
        }
        digestField(digest, intBytes(contract.outputs.size))
        contract.outputs.forEach { output ->
            digestField(digest, output.topologyDisplayId.value.toString().toByteArray(StandardCharsets.US_ASCII))
            digestField(digest, outputIdWire(output.outputId))
            digestField(
                digest,
                output.uiSyncDisplayId?.value?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0),
            )
        }
        digestField(digest, intBytes(contract.displayAssignments.size))
        contract.displayAssignments.forEach { assignment ->
            digestField(digest, assignment.phonePackageDigest.bytes())
            digestField(digest, intBytes(assignment.profileDisplayIndex))
            digest.update(if (assignment.profileDisplayId == null) 0 else 1)
            assignment.profileDisplayId?.let {
                digestField(digest, it.toByteArray(StandardCharsets.UTF_8))
            }
            digestField(digest, assignment.profileDisplayType.wireValue.toByteArray(
                StandardCharsets.US_ASCII,
            ))
            digestField(digest, assignment.destination.role.name.toByteArray(
                StandardCharsets.US_ASCII,
            ))
            digestField(digest, assignment.destination.instanceId.value.toByteArray(
                StandardCharsets.UTF_8,
            ))
            digestField(digest, assignment.topologyDisplayId.value.toString().toByteArray(
                StandardCharsets.US_ASCII,
            ))
            digestField(digest, outputIdWire(assignment.outputId))
            digestField(digest, assignment.uiSyncDisplayId.value.toByteArray(
                StandardCharsets.UTF_8,
            ))
            val variants = assignment.selectedVariants()
            digestField(digest, intBytes(variants.size))
            variants.forEach { variant ->
                val intent = variant.intent
                digest.update(if (intent == null) 0 else 1)
                intent?.let {
                    digestField(digest, it.layoutID.toByteArray(StandardCharsets.UTF_8))
                    digestField(digest, intBytes(it.activeComponents.size))
                    it.activeComponents.forEach { (zone, component) ->
                        digestField(digest, zone.toByteArray(StandardCharsets.UTF_8))
                        digestField(digest, component.toByteArray(StandardCharsets.UTF_8))
                    }
                    digestField(digest, it.paletteID.toByteArray(StandardCharsets.UTF_8))
                }
                digestThemeVariant(digest, variant)
            }
        }
        digestField(digest, intBytes(contract.ingressSpecs.size))
        contract.ingressSpecs.forEach { spec ->
            digestField(digest, spec.id.value.toByteArray(StandardCharsets.UTF_8))
            digestField(digest, intBytes(spec.airPlayStreamIdentity.type))
            digestField(
                digest,
                spec.airPlayStreamIdentity.streamConnectionId
                    ?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0),
            )
            digestField(
                digest,
                spec.airPlayStreamIdentity.clientTypeUuid
                    ?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0),
            )
            digestField(
                digest,
                spec.setupDisplayUuid
                    ?.toByteArray(StandardCharsets.US_ASCII) ?: ByteArray(0),
            )
        }
        val physical = contract.physicalOutputRequirement
        digest.update(if (physical == null) 0 else 1)
        physical?.let { requirement ->
            digestField(digest, requirement.verifiedProfileDigest.bytes())
            digestField(digest, requirement.assignmentFingerprint.bytes())
            digestField(digest, intBytes(requirement.assignments.size))
            val byOutput = requirement.assignments.associateBy { it.output.outputId }
            contract.outputs.forEach { output ->
                val row = checkNotNull(byOutput[output.outputId])
                digestField(digest, outputIdWire(row.output.outputId))
                digestField(digest, row.output.topologyDisplayId.value.toString().toByteArray(
                    StandardCharsets.US_ASCII,
                ))
                digestField(digest, requireNotNull(row.output.uiSyncDisplayId).value.toByteArray(
                    StandardCharsets.UTF_8,
                ))
                digestField(digest, row.destination.role.name.toByteArray(
                    StandardCharsets.US_ASCII,
                ))
                digestField(digest, row.destination.instanceId.toByteArray(
                    StandardCharsets.UTF_8,
                ))
            }
        }
        digestField(digest, intBytes(executableStaticSelectionProofs.size))
        executableStaticSelectionProofs.forEach { proof ->
            digestField(digest, proof.bytes())
        }
    }
    digest.update(if (caf == null) 0 else 1)
    caf?.let { contract ->
        digestField(digest, contract.schemaFingerprint.bytes())
        digestField(digest, contract.decoderImplementationId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(contract.handlerImplementationIds.size))
        contract.handlerImplementationIds.entries
            .sortedWith { left, right -> java.lang.Long.compareUnsigned(left.key, right.key) }
            .forEach { (pluginId, implementationId) ->
                digestField(digest, longBytes(pluginId))
                digestField(digest, implementationId.toByteArray(StandardCharsets.UTF_8))
            }
    }
    digest.update(if (instrumentCrosswalk == null) 0 else 1)
    instrumentCrosswalk?.let { contract ->
        digestField(digest, contract.crosswalkDigest.bytes())
        digestField(digest, contract.phonePackageDigest.bytes())
        digestField(digest, contract.cafSchemaFingerprint.bytes())
        digestField(digest, intBytes(contract.assignments.size))
        contract.assignments.forEach { assignment ->
            digestField(digest, intBytes(assignment.profileDisplayIndex))
            digest.update(if (assignment.profileDisplayId == null) 0 else 1)
            assignment.profileDisplayId?.let { digestField(digest, it.toByteArray(StandardCharsets.UTF_8)) }
            digestInstrumentSpec(digest, assignment.spec)
        }
    }
    return digest.digest()
}

private fun digestInstrumentSpec(digest: MessageDigest, spec: InstrumentSignalBundleSpec) {
    val address = spec.address
    val slot = address.slot
    digestField(digest, address.dataIdentifier.toByteArray(StandardCharsets.UTF_8))
    digest.update(if (address.zoneRegion == null) 0 else 1)
    address.zoneRegion?.let { digestField(digest, it.wireValue.toByteArray(StandardCharsets.UTF_8)) }
    digestField(digest, intBytes(slot.component.zone.layout.layoutIndex))
    digestField(digest, slot.component.zone.layout.layoutId.toByteArray(StandardCharsets.UTF_8))
    digestField(digest, intBytes(slot.component.zone.zoneIndex))
    digestField(digest, slot.component.zone.zoneId.toByteArray(StandardCharsets.UTF_8))
    digestField(digest, intBytes(slot.component.componentIndex))
    digestField(digest, slot.component.componentId.toByteArray(StandardCharsets.UTF_8))
    digestField(digest, intBytes(slot.slotIndex))
    digestField(digest, slot.positionId.toByteArray(StandardCharsets.UTF_8))
    digestField(digest, spec.shape.name.toByteArray(StandardCharsets.US_ASCII))
    digestField(digest, longBytes(spec.maximumReadAgeMillis))
    val sources = spec.sources.sortedBy { it.role.ordinal }
    digestField(digest, intBytes(sources.size))
    sources.forEach { source ->
        digestField(digest, source.role.name.toByteArray(StandardCharsets.US_ASCII))
        digestField(digest, longBytes(source.pin.serviceIid))
        digestField(digest, longBytes(source.pin.serviceType))
        digestField(digest, longBytes(source.pin.characteristicIid))
        digestField(digest, longBytes(source.pin.characteristicType))
        digestField(digest, longBytes(source.pin.characteristicFormat.wireValue))
        when (val semantics = source.semantics) {
            is InstrumentSignalSemantics.Numeric -> {
                digest.update(0)
                digestField(digest, semantics.meaning.name.toByteArray(StandardCharsets.US_ASCII))
                digestField(digest, semantics.exactCafUnits.toByteArray(StandardCharsets.UTF_8))
            }
            is InstrumentSignalSemantics.State -> {
                digest.update(1)
                digestField(digest, intBytes(semantics.codes.size))
                semantics.codes.forEach { code ->
                    val raw = code.raw
                    when (raw) {
                        is Boolean -> { digest.update(0); digest.update(if (raw) 1 else 0) }
                        is String -> {
                            digest.update(1)
                            digestField(digest, raw.toByteArray(StandardCharsets.UTF_8))
                        }
                        is Number -> {
                            digest.update(2)
                            digestField(digest, BigInteger(raw.toString()).toByteArray())
                        }
                        else -> error("Unsupported compiled instrument state code")
                    }
                    digestField(digest, code.name.toByteArray(StandardCharsets.UTF_8))
                }
            }
        }
    }
}

private fun instrumentSpecFingerprint(spec: InstrumentSignalBundleSpec): ByteArray =
    MessageDigest.getInstance("SHA-256").let { digest ->
        digest.update("xcertplay-instrument-spec-v1\u0000".toByteArray(StandardCharsets.US_ASCII))
        digestInstrumentSpec(digest, spec)
        digest.digest()
    }

private fun digestThemeVariant(digest: MessageDigest, variant: SelectedThemeVariant) {
    val program = variant.programSelection
    digestField(digest, intBytes(program.layoutIndex))
    digestField(digest, intBytes(program.activePrimarySelections.size))
    program.activePrimarySelections.forEach { selection ->
        digestField(digest, intBytes(selection.layoutIndex))
        digestField(digest, intBytes(selection.zoneIndex))
        digestField(digest, intBytes(selection.componentIndex))
    }
    digest.update(if (program.trackingOverlayPresent) 1 else 0)
    digest.update(if (program.appearanceOverlayPresent) 1 else 0)
    digest.update(if (program.transitionActive) 1 else 0)
    digestField(digest, program.structuralFingerprint.bytes())
    digestField(digest, intBytes(variant.slotIngressAssignments.size))
    variant.slotIngressAssignments.forEach { slotIngress ->
        val slot = slotIngress.slot
        digestField(digest, intBytes(slot.component.zone.layout.layoutIndex))
        digestField(digest, slot.component.zone.layout.layoutId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(slot.component.zone.zoneIndex))
        digestField(digest, slot.component.zone.zoneId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(slot.component.componentIndex))
        digestField(digest, slot.component.componentId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(slot.slotIndex))
        digestField(digest, slot.positionId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, slotIngress.streamIdentifier.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, slotIngress.ingressSpec.id.value.toByteArray(StandardCharsets.UTF_8))
        val identity = slotIngress.ingressSpec.airPlayStreamIdentity
        digestField(digest, intBytes(identity.type))
        digestField(digest, identity.streamConnectionId
            ?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0))
        digestField(digest, identity.clientTypeUuid
            ?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0))
        digestField(digest, slotIngress.ingressSpec.setupDisplayUuid
            ?.toByteArray(StandardCharsets.US_ASCII) ?: ByteArray(0))
    }
    digestField(digest, intBytes(variant.localCanvasAssignments.size))
    variant.localCanvasAssignments.forEach { canvas ->
        val slot = canvas.slot
        digestField(digest, intBytes(slot.component.zone.layout.layoutIndex))
        digestField(digest, slot.component.zone.layout.layoutId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(slot.component.zone.zoneIndex))
        digestField(digest, slot.component.zone.zoneId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(slot.component.componentIndex))
        digestField(digest, slot.component.componentId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, intBytes(slot.slotIndex))
        digestField(digest, slot.positionId.toByteArray(StandardCharsets.UTF_8))
        digestField(digest, canvas.contentId.toByteArray(StandardCharsets.UTF_8))
    }
    digestField(digest, intBytes(variant.instrumentReferenceAssignments.size))
    variant.instrumentReferenceAssignments.forEach { reference ->
        digestInstrumentSpec(digest, reference.spec)
    }
}

private fun outputIdWire(outputId: UltraDisplayOutputId): ByteArray = when (outputId) {
    is UltraDisplayOutputId.Android ->
        "android\u0000${outputId.value}".toByteArray(StandardCharsets.UTF_8)
    is UltraDisplayOutputId.Custom ->
        "custom\u0000${outputId.value}".toByteArray(StandardCharsets.UTF_8)
}

private fun digestField(digest: MessageDigest, value: ByteArray) {
    digest.update(intBytes(value.size))
    digest.update(value)
}

private fun intBytes(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte(),
)

private fun longBytes(value: Long): ByteArray = ByteArray(Long.SIZE_BYTES) { index ->
    (value ushr ((Long.SIZE_BYTES - 1 - index) * 8)).toByte()
}

private fun sha256(value: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(value)

private fun canonicalOutputs(
    values: Collection<UltraDisplayGraphOutput>,
): List<UltraDisplayGraphOutput> = Collections.unmodifiableList(
    values.sortedWith(
        compareBy<UltraDisplayGraphOutput>(
            { output ->
                when (output.outputId) {
                    is UltraDisplayOutputId.Android -> 0
                    is UltraDisplayOutputId.Custom -> 1
                }
            },
            { it.outputId.value },
            { it.topologyDisplayId.value.toString() },
            { it.uiSyncDisplayId?.value.orEmpty() },
        ),
    ),
)

private fun canonicalIngresses(
    values: Collection<UiSyncRendererIngressSpec>,
): List<UiSyncRendererIngressSpec> = Collections.unmodifiableList(
    values.sortedWith(
        compareBy<UiSyncRendererIngressSpec>(
            { it.id.value },
            { it.airPlayStreamIdentity.type },
            { it.airPlayStreamIdentity.streamConnectionId.orEmpty() },
            { it.airPlayStreamIdentity.clientTypeUuid.orEmpty() },
            { it.setupDisplayUuid.orEmpty() },
        ),
    ),
)

private fun canonicalSlotIngressAssignments(
    values: Collection<CompiledUiSyncSlotIngressAssignment>,
    layoutIndex: Int,
): List<CompiledUiSyncSlotIngressAssignment> {
    val rows = boundedList(values, MAXIMUM_THEME_SLOT_INGRESSES,
        "compiled theme slot ingress assignments")
    require(rows.map { it.slot }.toSet().size == rows.size && rows.all {
        it.slot.component.zone.layout.layoutIndex == layoutIndex
    }) { "Compiled slot ingress assignments must be unique in the selected layout" }
    require(rows.groupBy { it.streamIdentifier }.values.all { group ->
        group.map { it.ingressSpec }.toSet().size == 1
    }) { "One Profile InputStream identifier cannot select different ingress specs" }
    return Collections.unmodifiableList(rows.sortedWith(compareBy(
        { it.slot.component.zone.layout.layoutIndex },
        { it.slot.component.zone.zoneIndex },
        { it.slot.component.componentIndex },
        { it.slot.slotIndex },
    )))
}

private fun canonicalLocalCanvasAssignments(
    values: Collection<CompiledUiSyncLocalCanvasAssignment>,
    layoutIndex: Int,
): List<CompiledUiSyncLocalCanvasAssignment> {
    val rows = boundedList(values, MAXIMUM_THEME_SLOT_INGRESSES,
        "compiled local Canvas assignments")
    require(rows.map { it.slot }.toSet().size == rows.size && rows.all {
        it.slot.component.zone.layout.layoutIndex == layoutIndex
    } && rows.map { it.contentId }.toSet().size == rows.size) {
        "Local Canvas slots and content IDs must be unique in the selected layout"
    }
    return Collections.unmodifiableList(rows.sortedWith(compareBy(
        { it.slot.component.zone.layout.layoutIndex },
        { it.slot.component.zone.zoneIndex },
        { it.slot.component.componentIndex },
        { it.slot.slotIndex },
    )))
}

private fun canonicalInstrumentReferenceAssignments(
    values: Collection<CompiledUiSyncInstrumentReferenceAssignment>,
    layoutIndex: Int,
): List<CompiledUiSyncInstrumentReferenceAssignment> {
    val rows = boundedList(values, 1, "compiled native instrument reference assignments")
    require(rows.all { it.slot.component.zone.layout.layoutIndex == layoutIndex } &&
        rows.map { it.slot }.toSet().size == rows.size
    ) { "Instrument references must select one unique slot in the selected layout" }
    return Collections.unmodifiableList(rows)
}

private fun canonicalPrimarySelections(
    values: Collection<CarPlayThemePrimarySelection>,
): List<CarPlayThemePrimarySelection> {
    val selected = boundedList(values, MAXIMUM_THEME_PRIMARY_SELECTIONS,
        "compiled theme primary selections")
    require(selected.map { it.layoutIndex to it.zoneIndex }.toSet().size == selected.size) {
        "Compiled theme state selects one primary component per layout zone"
    }
    return Collections.unmodifiableList(selected.sortedWith(compareBy(
        { it.layoutIndex }, { it.zoneIndex }, { it.componentIndex },
    )))
}

private fun <T> boundedList(values: Collection<T>, maximum: Int, label: String): List<T> {
    val result = ArrayList<T>()
    val iterator = values.iterator()
    while (iterator.hasNext()) {
        require(result.size < maximum) { "$label exceed $maximum entries" }
        result += iterator.next()
    }
    return Collections.unmodifiableList(result)
}

private fun <T> boundedUniqueSet(values: Collection<T>, maximum: Int, label: String): Set<T> {
    val result = LinkedHashSet<T>()
    val iterator = values.iterator()
    while (iterator.hasNext()) {
        require(result.size < maximum) { "$label exceed $maximum entries" }
        require(result.add(iterator.next())) { "$label contain a duplicate entry" }
    }
    return Collections.unmodifiableSet(result)
}

private fun boundedImplementationMap(values: Map<Long, String>): Map<Long, String> {
    val result = LinkedHashMap<Long, String>()
    val iterator = values.entries.iterator()
    while (iterator.hasNext()) {
        require(result.size < MAXIMUM_CAF_HANDLERS) {
            "Compiled CAF handler map exceeds $MAXIMUM_CAF_HANDLERS entries"
        }
        val entry = iterator.next()
        requireValidImplementationId(entry.value, "compiled CAF handler")
        require(result.put(entry.key, entry.value) == null) {
            "Compiled CAF handler map contains a duplicate plugin ID"
        }
    }
    return Collections.unmodifiableMap(result)
}

private fun requireValidImplementationId(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAXIMUM_IMPLEMENTATION_ID_CHARS &&
        value.none(Char::isISOControl)
    ) { "$label implementation ID is invalid" }
}

private fun requireValidIntentString(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAXIMUM_THEME_INTENT_STRING_CHARS &&
        value.none(Char::isISOControl)
    ) { "Compiled theme $label is invalid" }
}

private fun canonicalIntentComponents(values: Map<String, String>): Map<String, String> {
    val copy = LinkedHashMap<String, String>()
    values.forEach { (zone, component) ->
        require(copy.size < MAXIMUM_THEME_ACTIVE_COMPONENTS) {
            "Compiled theme intent has too many active components"
        }
        requireValidIntentString(zone, "active component zone")
        requireValidIntentString(component, "active component ID")
        require(copy.put(zone, component) == null) {
            "Compiled theme intent repeats an active component zone"
        }
    }
    return Collections.unmodifiableMap(LinkedHashMap(copy.toSortedMap()))
}

/** NUL is forbidden in intent IDs, so this gives stable ordering for same-layout v3 variants. */
private fun canonicalIntentSortToken(intent: CompiledUiSyncThemeIntent): String =
    intent.activeComponents.entries.joinToString("\u0000") { (zone, component) ->
        "$zone\u0000$component"
    }

private const val MAXIMUM_UI_SYNC_DISPLAYS = 64
private const val MAXIMUM_UI_SYNC_OUTPUTS = 64
private const val MAXIMUM_UI_SYNC_INGRESSES = 128
private const val MAXIMUM_THEME_PRIMARY_SELECTIONS = 4096
private const val MAXIMUM_THEME_SLOT_INGRESSES = 4096
private const val MAXIMUM_THEME_VARIANTS_PER_DISPLAY = 16
private const val MAXIMUM_TOTAL_THEME_VARIANTS = 256
private const val MAXIMUM_THEME_ACTIVE_COMPONENTS = 64
private const val MAXIMUM_THEME_INTENT_STRING_CHARS = 512
private const val MAXIMUM_CAF_HANDLERS = 256
private const val MAXIMUM_IMPLEMENTATION_ID_CHARS = 512
