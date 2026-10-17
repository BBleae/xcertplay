package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.AuthorizedUltraProfileProvisioning
import com.shilapi.xcertplay.CompiledUiSyncDisplayAssignment
import com.shilapi.xcertplay.CompiledUiSyncLocalCanvasAssignment
import com.shilapi.xcertplay.CompiledUiSyncInstrumentReferenceAssignment
import com.shilapi.xcertplay.CompiledInstrumentSignalDisplayAssignment
import com.shilapi.xcertplay.sameInstrumentSignalSpec
import com.shilapi.xcertplay.CompiledUiSyncSlotIngressAssignment
import com.shilapi.xcertplay.CompiledUiSyncThemeProgramSelection
import com.shilapi.xcertplay.CompiledUiSyncThemeIntent
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderResult
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import com.shilapi.xcertplay.ultra.instrument.InstrumentReferenceScenePlan
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalEdgeInsets
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalPoint
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalRect
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramInstruction
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeExecutableStaticSubsetProof
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeExecutableVideoSlot
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeResolvedStyleWitness
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneComponentAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneLayoutAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneSlotAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneZoneAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneStage
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneState
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralFingerprint
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralProgram
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralSource
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralClip
import com.shilapi.xcertplay.vehicle.ultra.theme.LayoutType
import com.shilapi.xcertplay.vehicle.ultra.theme.SlotStyleCase
import com.shilapi.xcertplay.vehicle.ultra.theme.ZoneShapeCase
import com.shilapi.xcertplay.vehicle.ultra.theme.ZoneTypeCase
import java.util.Collections
import java.security.MessageDigest

/**
 * Explicit selection of a JSON package resource already inside an inspector-verified archive.
 * Neither the phone nor accessory side is assumed from its file name. A nested accessory ZIP is
 * not accepted as an ArcPackage until a separate, bounded inner-archive verifier exists.
 */
data class UltraThemeVerifiedProgramSource(
    val profile: VerifiedUltraProfile,
    val resourcePath: String,
    val resourceDigest: UltraProfileDigest,
    val expectedProgramFingerprint: CarPlayThemeStructuralFingerprint,
    val displayIndex: Int,
    val layoutIndex: Int,
    val state: CarPlayThemeSceneState = CarPlayThemeSceneState(),
    /** Exact issued wire choice, not a layout-index or fingerprint-only approximation. */
    val signedIntent: UltraThemeSignedLayoutIntent? = null,
)

/** Immutable wire identity for a signed theme scene; it grants no authority by itself. */
class UltraThemeSignedLayoutIntent(
    val layoutID: String,
    activeComponents: Map<String, String>,
    val paletteID: String,
) {
    val activeComponents: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(activeComponents))

    internal constructor(intent: CompiledUiSyncThemeIntent) : this(
        intent.layoutID, intent.activeComponents, intent.paletteID,
    )

    internal fun matches(intent: CompiledUiSyncThemeIntent): Boolean =
        layoutID == intent.layoutID && activeComponents == intent.activeComponents &&
            paletteID == intent.paletteID

    override fun equals(other: Any?): Boolean = other is UltraThemeSignedLayoutIntent &&
        layoutID == other.layoutID && activeComponents == other.activeComponents &&
        paletteID == other.paletteID

    override fun hashCode(): Int =
        31 * (31 * layoutID.hashCode() + activeComponents.hashCode()) + paletteID.hashCode()

    override fun toString(): String = "UltraThemeSignedLayoutIntent(<redacted>)"
}

/** One exact profile slot to one already-published video stream in this control session. */
data class UltraThemeSlotIngressBinding(
    val slot: CarPlayThemeSceneSlotAddress,
    val streamIdentifier: String,
    val ingressId: UltraIngressId,
    val stream: AirPlayVideoStreamHandle,
)

/** Caller-owned Canvas producer for one exact issuer-selected Empty Profile slot. */
data class UltraThemeLocalCanvasSlotBinding(
    val slot: CarPlayThemeSceneSlotAddress,
    val contentId: UltraContentId,
    val lease: AndroidUltraCustomIngressLease,
    val rasterPixels: PixelSize,
)

/** Product-issued, exact CLUSTER reference raster. Callers cannot construct this from a Canvas. */
class UltraThemeInstrumentReferenceBinding internal constructor(
    val slot: CarPlayThemeSceneSlotAddress,
    internal val lease: AndroidUltraInstrumentRasterLease,
    internal val plan: InstrumentReferenceScenePlan,
    internal val signalAssignment: CompiledInstrumentSignalDisplayAssignment,
)

/** Caller-owned, non-theme video composited at an explicitly selected confirmed scene stage. */
enum class UltraThemeExplicitLayerPlacement { BEFORE_THEME_STAGE, AFTER_THEME_STAGE }

data class UltraThemeExplicitVideoLayer(
    val id: UltraLayerId,
    val ingressId: UltraIngressId,
    val stream: AirPlayVideoStreamHandle,
    val framePixels: UltraPixelRect,
    val stage: CarPlayThemeSceneStage,
    val zIndex: Int,
    val placement: UltraThemeExplicitLayerPlacement =
        UltraThemeExplicitLayerPlacement.AFTER_THEME_STAGE,
)

data class UltraThemeOutputSceneBinding(
    val source: UltraThemeVerifiedProgramSource,
    val destination: DisplayDestination,
    val outputId: UltraDisplayOutputId,
    val viewportPixels: PixelSize,
    val pixelMapper: CarPlayLogicalPixelMapper,
    val slotIngresses: List<UltraThemeSlotIngressBinding>,
    val explicitVideoLayers: List<UltraThemeExplicitVideoLayer> = emptyList(),
    val localCanvasSlots: List<UltraThemeLocalCanvasSlotBinding> = emptyList(),
    val instrumentReference: UltraThemeInstrumentReferenceBinding? = null,
)

/**
 * A submitted frame is not a UI Sync ACK, renderer readiness receipt, or proof of OEM trust.
 * Lease checks reject a new submission after revocation; they are not a render-thread fence for
 * work already queued before revocation. An authorized product must retire/drain its EGL queue
 * before claiming that no old frame can still appear.
 */
class UltraThemeBoundSceneSubmission internal constructor(
    val snapshot: UltraMultiOutputRenderSnapshot,
    val ticket: UltraEglRenderTicket,
) {
    fun awaitPresented(timeoutMillis: Long = UltraEglRenderTicket.DEFAULT_RENDER_TIMEOUT_MILLIS):
        UltraEglRenderResult {
        val result = ticket.await(timeoutMillis)
        check(result.revision == snapshot.revision &&
            result.disposition == UltraEglRenderDisposition.PRESENTED
        ) { "The exact theme scene was not presented: ${result.disposition}" }
        return result
    }
}

/**
 * A deliberately small but real scene executor. It reads the selected program bytes from the
 * exact verified profile, compiles one fixed semantic state, and submits a complete multi-output
 * scene to the session-owned EGL coordinator. Any active drawing semantics outside solid opaque
 * layout clear and untransformed rectangular InputStream video are rejected, not omitted.
 *
 * A caller-supplied verifier can create [VerifiedUltraProfile] in a test harness; that alone is
 * not an OEM authorization. The public diagnostic constructor therefore accepts only one main
 * and one cluster output. The internal authorized constructor consumes the issuer-validated,
 * exact per-display assignment of a live profile provisioning lease; it may use distinct
 * instances of the same role. Neither path advertises UI_SYNC or issues readiness.
 */
class UltraThemeBoundSceneExecutor private constructor(
    private val coordinator: AndroidUltraRendererSessionCoordinator,
    private val verifiedProfile: VerifiedUltraProfile,
    expectedSchemaFingerprint: UiSyncSchemaFingerprint,
    expectedBindingFingerprint: UiSyncBindingFingerprint,
    private val graph: UltraDisplayGraphSnapshot,
    outputBindings: Collection<UltraThemeOutputSceneBinding>,
    private val authorization: AuthorizedUltraProfileProvisioning?,
) {
    constructor(
        coordinator: AndroidUltraRendererSessionCoordinator,
        verifiedProfile: VerifiedUltraProfile,
        expectedSchemaFingerprint: UiSyncSchemaFingerprint,
        expectedBindingFingerprint: UiSyncBindingFingerprint,
        graph: UltraDisplayGraphSnapshot,
        outputBindings: Collection<UltraThemeOutputSceneBinding>,
    ) : this(
        coordinator, verifiedProfile, expectedSchemaFingerprint, expectedBindingFingerprint,
        graph, outputBindings, null,
    )

    private data class CompiledOutput(
        val binding: UltraThemeOutputSceneBinding,
        val program: CarPlayThemeStructuralProgram,
        val layoutAddress: CarPlayThemeSceneLayoutAddress,
        val activeSlots: List<CarPlayThemeProgramInstruction.Slot>,
        val videoSlots: List<CarPlayThemeProgramInstruction.Slot>,
        val executableVideoSlots: List<CarPlayThemeExecutableVideoSlot>,
        val zoneClipPixelsBySlot: Map<CarPlayThemeSceneSlotAddress, UltraPixelRect>,
        val authorizedIngressBySlot: Map<CarPlayThemeSceneSlotAddress, UiSyncRendererIngressSpec>?,
        val authorizedLocalBySlot: Map<CarPlayThemeSceneSlotAddress, CompiledUiSyncLocalCanvasAssignment>?,
        val authorizedInstrument: CompiledUiSyncInstrumentReferenceAssignment?,
        val executableStaticProof: CarPlayThemeExecutableStaticSubsetProof?,
    )

    private data class IssuedProgramVariant(
        val intent: CompiledUiSyncThemeIntent?,
        val selection: CompiledUiSyncThemeProgramSelection,
        val slots: List<CompiledUiSyncSlotIngressAssignment>,
        val localCanvases: List<CompiledUiSyncLocalCanvasAssignment>,
        val instrumentReferences: List<CompiledUiSyncInstrumentReferenceAssignment>,
    )

    private val outputs: List<CompiledOutput>
    private val outputIds: Map<DisplayDestination, UltraDisplayOutputId>
    private val authorizedAssignments: List<CompiledUiSyncDisplayAssignment>?

    init {
        require(coordinator.schemaFingerprint == expectedSchemaFingerprint &&
            coordinator.bindingFingerprint == expectedBindingFingerprint
        ) { "Theme program and renderer session schema/binding identity differ" }
        if (coordinator.graphSnapshot() !== graph) {
            throw StaleThemeGraphBeforeSubmissionException()
        }
        val frozen = ArrayList<UltraThemeOutputSceneBinding>()
        outputBindings.forEach {
            require(frozen.size < 64) { "Too many theme output bindings" }
            frozen += it.copy(
                slotIngresses = Collections.unmodifiableList(ArrayList(it.slotIngresses)),
                explicitVideoLayers = Collections.unmodifiableList(ArrayList(it.explicitVideoLayers)),
                localCanvasSlots = Collections.unmodifiableList(ArrayList(it.localCanvasSlots)),
            )
        }
        require(frozen.isNotEmpty()) { "A theme scene needs an explicit output" }
        require(frozen.map { it.destination }.toSet().size == frozen.size &&
            frozen.map { it.outputId }.toSet().size == frozen.size
        ) { "Theme output bindings must be one-to-one" }
        authorizedAssignments = authorization?.requireSceneDisplayAssignments(
            verifiedProfile, coordinator.schemaFingerprint, coordinator.bindingFingerprint, graph,
        )
        if (authorizedAssignments == null) {
            require(frozen.map { it.destination.role }.toSet().size == frozen.size) {
                "Multiple outputs of one role need an authorized profile-display assignment"
            }
        } else {
            require(authorizedAssignments.size == frozen.size &&
                authorizedAssignments.map { it.graphOutput() }.toSet() == graph.outputs.toSet() &&
                authorizedAssignments.map { it.destination }.toSet() ==
                    frozen.map { it.destination }.toSet()
            ) { "Authorized profile display assignments do not cover the exact graph and scenes" }
            val byDestination = authorizedAssignments.associateBy { it.destination }
            frozen.forEach { binding ->
                val assignment = requireNotNull(byDestination[binding.destination])
                val output = requireNotNull(graph.output(binding.outputId))
                require(binding.source.profile === verifiedProfile &&
                    binding.source.displayIndex == assignment.profileDisplayIndex &&
                    binding.outputId == assignment.outputId &&
                    output == assignment.graphOutput() &&
                    binding.source.resourcePath ==
                        com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
                            .CARPLAY_PHONE_PACKAGE_JSON &&
                    binding.source.resourceDigest == assignment.phonePackageDigest &&
                    issuedProgramVariant(assignment, binding.source) != null
                ) { "Theme scene source/output differs from its authorized profile display row" }
            }
        }
        require(frozen.all { it.destination.role == DisplayDestinationRole.MAIN ||
            it.destination.role == DisplayDestinationRole.CLUSTER
        }) { "Only uniquely assigned main and cluster outputs have confirmed theme rendering" }
        require(graph.outputs.map { it.outputId }.toSet() == frozen.map { it.outputId }.toSet()) {
            "A theme submission must provide a scene for every attached graph output"
        }
        // The Host's initial binding predates a phone type-3. Bind that row to the issued
        // primary intent before compiling; a same-layout alternate palette must never be
        // selected by whichever matching program fingerprint happens to appear first.
        val intentBound = if (authorizedAssignments == null) frozen else {
            val byDestination = authorizedAssignments.associateBy { it.destination }
            frozen.map { binding ->
                if (binding.source.signedIntent != null) binding else {
                    val assignment = requireNotNull(byDestination[binding.destination])
                    val primary = assignment.primaryIntent
                    if (primary == null) binding else binding.copy(
                        source = binding.source.copy(
                            signedIntent = UltraThemeSignedLayoutIntent(primary),
                        ),
                    )
                }
            }
        }
        outputs = Collections.unmodifiableList(intentBound.map(::compileOutput))
        outputIds = Collections.unmodifiableMap(
            outputs.associateTo(LinkedHashMap()) { it.binding.destination to it.binding.outputId },
        )
    }

    /** Builds without committing; a UI Sync type-3 desired layout does not call this by itself. */
    fun snapshot(revision: Long): UltraMultiOutputRenderSnapshot {
        authorizedAssignments?.let { expected ->
            check(authorization?.requireSceneDisplayAssignments(
                verifiedProfile, coordinator.schemaFingerprint, coordinator.bindingFingerprint,
                graph,
            ) == expected) {
                "Authorized profile display assignment is no longer live"
            }
            requireAuthorizedProgramSelections(expected)
        }
        require(coordinator.isLive) { "Theme renderer session is stale" }
        if (coordinator.graphSnapshot() !== graph) {
            throw StaleThemeGraphBeforeSubmissionException()
        }
        val state = coordinator.stateSnapshot()
        if (state.graphRevision != graph.revision) {
            throw StaleThemeGraphBeforeSubmissionException()
        }
        val outputScenes = outputs.map { compiled ->
            val binding = compiled.binding
            val graphOutput = requireNotNull(graph.output(binding.outputId)) {
                "Theme output disappeared from the bound graph"
            }
            require(state.outputs.any { it.destination == binding.destination &&
                it.output === graphOutput && it.phase == AndroidUltraRendererSessionPhase.ACTIVE
            }) { "Theme output is not attached to this live renderer session" }
            binding.instrumentReference?.let { reference ->
                require(compiled.authorizedInstrument?.slot == reference.slot &&
                    coordinator.isCurrentInstrumentRaster(reference.lease,
                        binding.destination, binding.viewportPixels)
                ) { "Signed reference instrument raster is not this exact CLUSTER EGL generation" }
                return@map UltraOutputScene(
                    binding.destination, binding.viewportPixels,
                    listOf(UltraSceneLayer(
                        id = slotLayerId(reference.slot),
                        source = UltraSceneSource.Instrument(binding.destination),
                        framePixels = UltraPixelRect(0, 0,
                            binding.viewportPixels.widthPixels,
                            binding.viewportPixels.heightPixels),
                    )),
                    selectUltraOpaqueThemeBackground(
                        compiled.program, compiled.layoutAddress, binding.destination,
                        graph, binding.outputId, binding.viewportPixels,
                        fullViewportMappingConfirmed = true,
                    ),
                )
            }
            val layers = ArrayList<RankedLayer>()
            compiled.activeSlots.forEachIndexed { programOrder, slot ->
                val source = when (val slotSource = slot.source) {
                    is CarPlayThemeStructuralSource.InputStream -> {
                        val ingressBinding = binding.slotIngresses.single { it.slot == slot.address }
                        require(ingressBinding.streamIdentifier == slotSource.streamIdentifier) {
                            "Theme slot stream identifier changed"
                        }
                        val authorizedSpec = compiled.authorizedIngressBySlot?.let { specs ->
                            requireNotNull(specs[slot.address]) {
                                "Theme slot has no compiled ingress selector"
                            }
                        }
                        requireLiveIngress(ingressBinding.ingressId, ingressBinding.stream, state,
                            authorizedSpec)
                        UltraSceneSource.Remote(ingressBinding.ingressId)
                    }
                    CarPlayThemeStructuralSource.Empty -> {
                        val canvas = binding.localCanvasSlots.singleOrNull {
                            it.slot == slot.address
                        } ?: return@forEachIndexed
                        val issued = requireNotNull(compiled.authorizedLocalBySlot?.get(slot.address)) {
                            "Local Canvas slot has no selected issuer assignment"
                        }
                        require(canvas.contentId.value == issued.contentId) {
                            "Local Canvas content ID differs from the selected issuer assignment"
                        }
                        requireLiveLocalCanvas(canvas, state)
                        UltraSceneSource.Local(canvas.contentId)
                    }
                    else -> throw IllegalArgumentException(
                        "Selected theme slot source is not executable",
                    )
                }
                val frame = binding.pixelMapper.mapFrame(slot.frameInDisplayLogicalSpace)
                layers += RankedLayer(
                    stage = slot.stage,
                    group = 1,
                    orderWithinGroup = programOrder,
                    layer = UltraSceneLayer(
                        id = slotLayerId(slot.address),
                        source = source,
                        framePixels = frame,
                        // EGL effectiveClip always intersects this with the slot frame. For a
                        // Local Canvas, the old self-frame clip remains effective even when the
                        // selected component adds its exact rectangular zone-shape clip.
                        clipPixels = compiled.zoneClipPixelsBySlot[slot.address]
                            ?: if (source is UltraSceneSource.Local) frame else null,
                    ),
                )
            }
            binding.explicitVideoLayers.forEach { explicit ->
                requireLiveIngress(explicit.ingressId, explicit.stream, state)
                layers += RankedLayer(
                    stage = explicit.stage,
                    group = if (explicit.placement ==
                        UltraThemeExplicitLayerPlacement.BEFORE_THEME_STAGE) 0 else 2,
                    orderWithinGroup = explicit.zIndex,
                    layer = UltraSceneLayer(
                        explicit.id, UltraSceneSource.Remote(explicit.ingressId),
                        explicit.framePixels,
                    ),
                )
            }
            val rankedLayers = layers.withIndex().sortedWith(compareBy(
                { it.value.stage.ordinal }, { it.value.group },
                { it.value.orderWithinGroup }, { it.index },
            )).mapIndexed { index, entry -> entry.value.layer.copy(zIndex = index) }
            UltraOutputScene(
                binding.destination,
                binding.viewportPixels,
                rankedLayers,
                selectUltraOpaqueThemeBackground(
                    compiled.program, compiled.layoutAddress, binding.destination,
                    graph, binding.outputId, binding.viewportPixels,
                    fullViewportMappingConfirmed = true,
                ),
            )
        }
        return UltraMultiOutputRenderSnapshot(revision, outputScenes)
    }

    fun submit(revision: Long): UltraThemeBoundSceneSubmission {
        fun enqueue(): UltraThemeBoundSceneSubmission {
            val scene = snapshot(revision)
            authorizedAssignments?.let { expected ->
                check(authorization?.requireSceneDisplayAssignments(
                    verifiedProfile, coordinator.schemaFingerprint,
                    coordinator.bindingFingerprint, graph,
                ) == expected) {
                    "Authorized profile display assignment was revoked before submission"
                }
            }
            val localGuards = outputs.flatMap { compiled ->
                compiled.binding.localCanvasSlots.map { canvas ->
                    AndroidUltraLocalIngressExpectation(
                        canvas.lease, canvas.contentId, canvas.rasterPixels,
                    )
                }
            }
            val ticket = coordinator.resolveAndSubmitWithLocalIngresses(
                scene, outputIds, localGuards,
            )
            require(ticket.revision == scene.revision) {
                "EGL returned a ticket for another revision"
            }
            return UltraThemeBoundSceneSubmission(scene, ticket)
        }
        // The second liveness check above alone is not a submission barrier: AA05 can revoke
        // between that check and the backend queue call. The exact provisioning holds an admitted
        // use until resolveAndSubmit returns its ticket (or fails); revoke waits for that handoff.
        return authorization?.withSceneSubmissionUse(::enqueue) ?: enqueue()
    }

    /**
     * Rebinds exactly one display to a catalog-selected static layout. Other display choices
     * remain unchanged. A wire command is only intent; this method neither submits nor ACKs it.
     * The returned executor still rechecks the exact live Profile and every signed program proof
     * before a complete multi-output scene can be enqueued.
     */
    internal fun selectSignedLayoutIntent(
        displayId: UltraUiSyncDisplayId,
        command: CarPlayUiSyncSemanticCommand.LayoutChange,
        localCanvasSlots: List<UltraThemeLocalCanvasSlotBinding> = emptyList(),
        instrumentReference: UltraThemeInstrumentReferenceBinding? = null,
    ): UltraThemeBoundSceneExecutor? {
        val provisioning = authorization ?: return null
        val selected = provisioning.selectThemeVariantFor(
            verifiedProfile, displayId, command,
        ) ?: return null
        val current = outputs.singleOrNull {
            it.binding.destination == selected.assignment.destination
        } ?: return null
        check(current.binding.outputId == selected.assignment.outputId &&
            selected.assignment.uiSyncDisplayId == displayId
        ) { "Signed theme variant targets another output" }
        val oldSource = current.binding.source
        if (oldSource.signedIntent?.matches(selected.intent) == true &&
            selected.programSelection.matches(oldSource.layoutIndex, oldSource.state,
                oldSource.expectedProgramFingerprint)
        ) {
            if ((localCanvasSlots.isEmpty() ||
                localCanvasSlots == current.binding.localCanvasSlots) &&
                instrumentReference === current.binding.instrumentReference) return this
            return fromAuthorizedProvisioning(
                coordinator, provisioning, verifiedProfile, graph,
                outputs.map { compiled ->
                    if (compiled === current) compiled.binding.copy(
                        localCanvasSlots = localCanvasSlots,
                        instrumentReference = instrumentReference,
                    ) else compiled.binding
                },
            )
        }
        val replacement = current.binding.copy(
            source = oldSource.copy(
                layoutIndex = selected.programSelection.layoutIndex,
                state = selected.programSelection.stateSnapshot(),
                expectedProgramFingerprint = selected.programSelection.structuralFingerprint,
                signedIntent = UltraThemeSignedLayoutIntent(selected.intent),
            ),
            slotIngresses = selected.slotIngressAssignments.map { row ->
                val stream = requireNotNull(graph.ingress(row.ingressSpec.id)?.stream) {
                    "Signed theme variant ingress is not in the current graph"
                }
                require(row.ingressSpec.matches(stream)) {
                    "Signed theme variant ingress does not match the live stream handle"
                }
                UltraThemeSlotIngressBinding(row.slot, row.streamIdentifier,
                    row.ingressSpec.id, stream)
            },
            localCanvasSlots = localCanvasSlots,
            instrumentReference = instrumentReference,
        )
        return fromAuthorizedProvisioning(
            coordinator, provisioning, verifiedProfile, graph,
            outputs.map { if (it === current) replacement else it.binding },
        )
    }

    internal fun selectedLayoutIndices(): Map<UltraUiSyncDisplayId, Int> {
        val assignments = requireNotNull(authorizedAssignments)
        val byDestination = assignments.associateBy { it.destination }
        return Collections.unmodifiableMap(outputs.associateTo(LinkedHashMap()) { compiled ->
            requireNotNull(byDestination[compiled.binding.destination]).uiSyncDisplayId to
                compiled.binding.source.layoutIndex
        })
    }

    private fun compileOutput(binding: UltraThemeOutputSceneBinding): CompiledOutput {
        val source = binding.source
        require(source.profile === verifiedProfile) {
            "Theme output was compiled from another verified profile instance"
        }
        require(source.displayIndex >= 0 && source.layoutIndex >= 0) {
            "Theme display and layout indices must be explicit and non-negative"
        }
        require(!source.state.transitionActive) {
            "Transition animation semantics are not executable"
        }
        val entry = requireNotNull(source.profile.candidate.entries.singleOrNull {
            it.name == source.resourcePath
        }) { "Selected theme JSON is not a verified profile file" }
        require(entry.digest == source.resourceDigest) {
            "Selected theme JSON digest does not match the verified archive"
        }
        val bytes = requireNotNull(source.profile.candidate.content(source.resourcePath))
        require(UltraProfileDigest(MessageDigest.getInstance("SHA-256").digest(bytes)) == entry.digest) {
            "Selected theme bytes differ from the verified archive digest"
        }
        val arc = CarPlayThemeSchema.parseArcPackageJson(bytes)
        require(arc.extensions.isEmpty() && arc.driveModeDynamicAssignmentConfiguration == null &&
            arc.vehicleType == null && arc.vehicleTypes == null
        ) { "Active ArcPackage semantics are not executable" }
        val display = requireNotNull(arc.displays.getOrNull(source.displayIndex)) {
            "Selected theme display index is absent"
        }
        if (authorizedAssignments == null) {
            require(arc.displays.count { it.type == display.type } == 1) {
                "Multiple profile displays of one type need an authorized output assignment"
            }
        }
        authorizedAssignments?.let { assignments ->
            val assignment = requireNotNull(assignments.singleOrNull {
                it.destination == binding.destination
            })
            require(assignment.profileDisplayIndex == source.displayIndex &&
                assignment.profileDisplayId == display.id &&
                assignment.profileDisplayType == display.type &&
                issuedProgramVariant(assignment, source) != null
            ) { "Compiled scene display differs from its authorized profile display row" }
            val primaryIndex = assignment.programSelection.layoutIndex
            val primaryLayout = requireNotNull(display.layouts.getOrNull(primaryIndex)) {
                "Styled primary layout is absent from the verified phone package"
            }
            primaryLayout.layoutStyleID?.let { styleID ->
                // The initial Host frame exists before any phone type-3. Its palette is the
                // signed default, never the row's opaque primaryIntent merely because that
                // intent appears first in a catalog. Validate that default independently even
                // while compiling a later explicit type-3 alternate palette.
                requireInitialStyledPaletteWitness(
                    verifiedProfile, source.displayIndex, primaryIndex,
                    source.resourceDigest, styleID, primaryLayout.id,
                    assignment.primaryIntent,
                )
            }
        }
        require(source.layoutIndex < display.layouts.size) { "Selected theme layout index is absent" }
        val compiler = CarPlayThemeProgramCompiler()
        // Never accept a caller-supplied proof. Recompile the exact selected verified-package
        // display/state under the static executor policy before an authorized scene is created.
        val assessment = if (authorizedAssignments != null) {
            val references = requireNotNull(authorizedAssignments.singleOrNull {
                it.destination == binding.destination
            })
            val selectedVariant = requireNotNull(issuedProgramVariant(references, source))
            val selectedLayout = display.layouts[source.layoutIndex]
            // The issuer's style proof is not a caller-provided rendering override. Rebuild it
            // from the exact signed phone package and theme bytes here, then compile only the
            // inert style subset under its own reparsed Display identity. A different palette,
            // digest, or newly introduced drawing field must fail before the EGL scene exists.
            val styleWitness = selectedLayout.layoutStyleID?.let { styleID ->
                val selectedPaletteID = requireNotNull(selectedVariant.intent?.paletteID) {
                    "Styled theme scene requires an exact signed palette intent"
                }
                CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(
                    verifiedProfile, source.displayIndex, source.layoutIndex, selectedPaletteID,
                ).also { witness ->
                    require(witness.profileContentDigest == verifiedProfile.candidate.contentDigest &&
                        witness.phonePackageDigest == source.resourceDigest &&
                        witness.styleID == styleID &&
                        selectedPaletteID == witness.selectedPaletteID
                    ) { "Selected theme style differs from its signed Profile intent" }
                }
            }
            compiler.assessExecutableStaticSubset(
                styleWitness?.display ?: display,
                source.displayIndex, source.layoutIndex, source.state,
                selectedVariant.instrumentReferences.map { it.spec },
                styleWitness,
            )
        } else null
        val compilation = if (assessment == null) compiler.compile(display, source.state) else null
        val proof = assessment?.proof
        require(authorizedAssignments == null || proof != null) {
            "Selected verified Profile theme has unsupported active rendering semantics"
        }
        val program = assessment?.structuralProgram ?: requireNotNull(compilation).structuralProgram
        require(program.structuralFingerprint == source.expectedProgramFingerprint) {
            "Verified theme program fingerprint changed"
        }
        authorizedAssignments?.let { assignments ->
            val assignment = requireNotNull(assignments.singleOrNull {
                it.destination == binding.destination
            })
            require(program.structuralFingerprint ==
                requireNotNull(issuedProgramVariant(assignment, source)).selection
                    .structuralFingerprint
            ) { "Theme program differs from the issued compiled program" }
        }
        val activePrefix = "layouts[${source.layoutIndex}]"
        val compilationIssues = assessment?.issues ?: requireNotNull(compilation).issues
        require(compilationIssues.none { it.path == "display" ||
            it.path == activePrefix || it.path.startsWith("$activePrefix.")
        }) { "Selected theme layout has unresolved drawing or geometry fields" }
        val layouts = program.instructions.filterIsInstance<CarPlayThemeProgramInstruction.Layout>()
            .filter { it.address.layoutIndex == source.layoutIndex }
        require(layouts.size == 1 && layouts.single().type == LayoutType.PRIMARY &&
            zeroInsets(layouts.single().safeAreaInsets)
        ) { "Only a unique primary layout with zero safe-area transform is executable" }
        val address = layouts.single().address
        val selected = program.instructions.filter { instruction -> when (instruction) {
            is CarPlayThemeProgramInstruction.Layout -> instruction.address == address
            is CarPlayThemeProgramInstruction.FillLayoutBackground -> instruction.address == address
            is CarPlayThemeProgramInstruction.Zone -> instruction.address.layout == address
            is CarPlayThemeProgramInstruction.Component -> instruction.address.zone.layout == address
            is CarPlayThemeProgramInstruction.Slot -> instruction.address.component.zone.layout == address
            is CarPlayThemeProgramInstruction.DynamicOverlay -> instruction.address == address
        } }
        require(selected.count { it is CarPlayThemeProgramInstruction.FillLayoutBackground } == 1) {
            "Only a unique fixed solid layout background is executable"
        }
        selected.forEach { instruction -> when (instruction) {
            is CarPlayThemeProgramInstruction.Layout,
            is CarPlayThemeProgramInstruction.FillLayoutBackground -> Unit
            is CarPlayThemeProgramInstruction.Zone -> require(
                (instruction.type == ZoneTypeCase.SINGLE ||
                    ((instruction.type == ZoneTypeCase.MAPS_MEDIA ||
                        instruction.type == ZoneTypeCase.DCA) && proof != null &&
                        proof.selectedPrimaryComponents.count {
                            it.layoutIndex == source.layoutIndex &&
                                it.zoneIndex == instruction.address.zoneIndex
                        } == 1)) &&
                    instruction.shape == ZoneShapeCase.RECTANGULAR &&
                    instruction.cornerRadius == 0.0,
            ) { "Zone lacks an exact selected primary or has unsupported shape semantics" }
            is CarPlayThemeProgramInstruction.Component -> require(
                (instruction.clip == CarPlayThemeStructuralClip.NONE ||
                    instruction.clip == CarPlayThemeStructuralClip.ZONE_SHAPE) &&
                    instruction.trackable != true,
            ) { "Custom component clipping or dynamic tracking is not executable" }
            is CarPlayThemeProgramInstruction.Slot -> require(
                (instruction.style == SlotStyleCase.RECTANGULAR &&
                    (instruction.safeAreaInsets?.let(::zeroInsets) ?: true) &&
                    (instruction.source == CarPlayThemeStructuralSource.Empty ||
                        instruction.source is CarPlayThemeStructuralSource.InputStream)) ||
                    (proof?.instrumentReferenceSlots?.any {
                        it.address == instruction.address
                    } == true),
            ) { "Slot shape, safe-area transform, or instrument is not executable" }
            is CarPlayThemeProgramInstruction.DynamicOverlay ->
                throw IllegalArgumentException("Dynamic theme overlay is not executable")
        } }
        val activeSlots = selected.filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
        val selectedZones = selected.filterIsInstance<CarPlayThemeProgramInstruction.Zone>()
            .associateBy { it.address }
        val selectedComponents = selected
            .filterIsInstance<CarPlayThemeProgramInstruction.Component>()
            .associateBy { it.address }
        require(selectedZones.size == selected.count {
            it is CarPlayThemeProgramInstruction.Zone
        } && selectedComponents.size == selected.count {
            it is CarPlayThemeProgramInstruction.Component
        }) { "Selected theme zone/component addresses must be unique" }
        val authorizedInstrument = authorizedAssignments?.let { assignments ->
            val assignment = requireNotNull(assignments.singleOrNull {
                it.destination == binding.destination
            })
            val issued = requireNotNull(issuedProgramVariant(assignment, source))
            val references = issued.instrumentReferences
            require(references.size <= 1 &&
                proof?.instrumentReferenceSlots?.map { it.address } ==
                    references.map { it.slot }
            ) { "Selected native reference differs from the signed executable proof" }
            val reference = references.singleOrNull()
            if (reference == null) {
                require(binding.instrumentReference == null) {
                    "Unselected theme cannot borrow an instrument raster"
                }
            } else {
                require(selectedZones.values.all { it.type == ZoneTypeCase.SINGLE }) {
                    "Native CLUSTER instrument cannot enter carousel Zone semantics"
                }
                require(selectedComponents.values.none {
                    it.clip == CarPlayThemeStructuralClip.ZONE_SHAPE
                }) { "Native instrument reference cannot silently omit component clipping" }
                val selectedRaster = requireNotNull(binding.instrumentReference) {
                    "Signed instrument reference has no dedicated CLUSTER raster"
                }
                require(binding.destination.role == DisplayDestinationRole.CLUSTER &&
                    binding.explicitVideoLayers.isEmpty() &&
                    binding.localCanvasSlots.isEmpty() && binding.slotIngresses.isEmpty() &&
                    selectedRaster.slot == reference.slot &&
                    selectedRaster.plan.spec === selectedRaster.signalAssignment.spec &&
                    sameInstrumentSignalSpec(selectedRaster.plan.spec, reference.spec) &&
                    authorization?.instrumentAssignmentsFor(verifiedProfile)?.any {
                        it === selectedRaster.signalAssignment
                    } == true &&
                    selectedRaster.plan.destination == binding.destination &&
                    selectedRaster.plan.viewportPixels == binding.viewportPixels &&
                    coordinator.isCurrentInstrumentRaster(selectedRaster.lease,
                        binding.destination, binding.viewportPixels)
                ) { "Instrument raster is not the exact issued CLUSTER reference" }
            }
            reference
        }
        val videoSlots = activeSlots.filter {
            it.source is CarPlayThemeStructuralSource.InputStream
        }
        val executableVideoSlots = videoSlots.map { slot ->
            executableVideoSlot(slot, selectedComponents, selectedZones,
                proof?.selectedPrimaryComponents ?: emptyList())
        }
        proof?.let { staticProof ->
            require(staticProof.matches(
                source.displayIndex, source.layoutIndex, source.state,
                source.expectedProgramFingerprint,
            ) && staticProof.displayType == display.type &&
                staticProof.displayId == display.id && staticProof.layoutAddress == address &&
                staticProof.backgroundColor == selected
                    .filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>()
                    .single().color &&
                staticProof.videoSlots == executableVideoSlots
                && staticProof.selectedPrimaryComponents == source.state.activePrimarySelections
            ) { "Static theme proof differs from the verified selected scene" }
        }
        val proofVideoByAddress = proof?.videoSlots?.associateBy { it.address }
        val zoneClipPixelsBySlot = activeSlots.mapNotNull { slot ->
            val structuralClip = slotZoneClip(slot, selectedComponents, selectedZones,
                proof?.selectedPrimaryComponents ?: emptyList())
            val issuedClip = if (slot.source is CarPlayThemeStructuralSource.InputStream &&
                proofVideoByAddress != null) {
                requireNotNull(proofVideoByAddress[slot.address]) {
                    "Selected video slot has no exact static proof row"
                }.clipInDisplayLogicalSpace
            } else structuralClip
            require(issuedClip == structuralClip) {
                "Issued component clip differs from the selected verified zone"
            }
            issuedClip?.let { slot.address to exactZoneClipPixels(
                it, binding.pixelMapper, binding.viewportPixels,
            ) }
        }.toMap(LinkedHashMap())
        require(videoSlots.map { it.address }.toSet() == binding.slotIngresses.map { it.slot }.toSet() &&
            binding.slotIngresses.size == videoSlots.size
        ) { "Every active InputStream slot requires one exact ingress binding" }
        val authorizedIngressBySlot = authorizedAssignments?.let { assignments ->
            val assignment = requireNotNull(assignments.singleOrNull {
                it.destination == binding.destination
            })
            require(binding.explicitVideoLayers.isEmpty()) {
                "Authorized theme scenes need an issued per-output overlay policy"
            }
            val bySlot = requireNotNull(issuedProgramVariant(assignment, source))
                .slots.associateBy { it.slot }
            require(bySlot.size == videoSlots.size &&
                bySlot.keys == videoSlots.map { it.address }.toSet()
            ) { "Authorized theme slot ingress map differs from the selected program" }
            videoSlots.forEach { slot ->
                val sourceIdentifier = (slot.source as CarPlayThemeStructuralSource.InputStream)
                    .streamIdentifier
                val compiled = requireNotNull(bySlot[slot.address])
                val bindingIngress = binding.slotIngresses.single { it.slot == slot.address }
                require(compiled.streamIdentifier == sourceIdentifier &&
                    bindingIngress.streamIdentifier == sourceIdentifier &&
                    issuedIngressSelectorMatches(
                        bindingIngress.ingressId, bindingIngress.stream, compiled.ingressSpec,
                    )
                ) { "Theme slot ingress differs from the issued Profile-to-stream binding" }
            }
            Collections.unmodifiableMap(bySlot.mapValuesTo(LinkedHashMap()) {
                (_, row) -> row.ingressSpec
            })
        }
        val authorizedLocalBySlot = authorizedAssignments?.let { assignments ->
            val assignment = requireNotNull(assignments.singleOrNull {
                it.destination == binding.destination
            })
            val issued = requireNotNull(issuedProgramVariant(assignment, source))
            val bySlot = issued.localCanvases.associateBy { it.slot }
            require(bySlot.size == issued.localCanvases.size &&
                binding.localCanvasSlots.size == bySlot.size &&
                binding.localCanvasSlots.map { it.slot }.toSet() == bySlot.keys
            ) { "Local Canvas bindings differ from the selected issuer assignments" }
            binding.localCanvasSlots.forEach { canvas ->
                val row = requireNotNull(bySlot[canvas.slot])
                val slot = requireNotNull(activeSlots.singleOrNull {
                    it.address == canvas.slot
                }) { "Local Canvas slot is absent from the selected layout" }
                require(slot.source == CarPlayThemeStructuralSource.Empty &&
                    canvas.contentId.value == row.contentId &&
                    canvas.lease.key == com.shilapi.xcertplay.ultra.egl.UltraEglIngressKey.Local(
                        canvas.contentId,
                    ) && canvas.rasterPixels == localCanvasRasterSize(
                        slot, binding.pixelMapper, binding.viewportPixels,
                    ) && coordinator.isCurrentLocalIngress(
                        canvas.lease, canvas.contentId, canvas.rasterPixels,
                    )
                ) { "Local Canvas slot, raster, or owned lease differs from the issued selection" }
            }
            Collections.unmodifiableMap(LinkedHashMap(bySlot))
        }
        require(authorizedAssignments != null || binding.localCanvasSlots.isEmpty()) {
            "Diagnostic theme scenes cannot add Local Canvas layers"
        }
        require(binding.explicitVideoLayers.all {
            it.stage == CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER ||
                it.stage == CarPlayThemeSceneStage.ZONE_OVERLAY_COMPONENT
        }) { "Explicit video layers require a confirmed video stage" }
        require(binding.explicitVideoLayers.size + videoSlots.size +
            binding.localCanvasSlots.size <= 4096) {
            "Theme scene exceeds the per-output layer limit"
        }
        val fullDisplay = CarPlayLogicalRect(CarPlayLogicalPoint(0.0, 0.0), program.logicalSize)
        val mapped = binding.pixelMapper.mapSubpixel(fullDisplay)
        require(mapped == UltraSubpixelRect(
            0.0, 0.0,
            binding.viewportPixels.widthPixels.toDouble(),
            binding.viewportPixels.heightPixels.toDouble(),
        )) { "Explicit logical-to-pixel mapper does not cover the exact output viewport" }
        // The already selected color must be opaque before a scene can be produced.
        require(selected.filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>()
            .single().color.alpha == 1.0
        ) { "Theme layout background is not opaque" }
        return CompiledOutput(binding, program, address, activeSlots, videoSlots,
            executableVideoSlots, Collections.unmodifiableMap(zoneClipPixelsBySlot),
            authorizedIngressBySlot, authorizedLocalBySlot, authorizedInstrument, proof)
    }

    private fun requireLiveIngress(
        id: UltraIngressId,
        stream: AirPlayVideoStreamHandle,
        state: AndroidUltraRendererSessionSnapshot,
        authorizedSpec: UiSyncRendererIngressSpec? = null,
    ) {
        authorizedSpec?.let { spec ->
            require(issuedIngressSelectorMatches(id, stream, spec)) {
                "Theme video ingress is outside the issued slot selector"
            }
        }
        val graphIngress = requireNotNull(graph.ingress(id)) {
            "Theme video ingress is missing from the exact graph"
        }
        require(graphIngress.stream === stream && state.remoteIngresses.any {
            it.ingressId == id && it.stream === stream && it.published &&
                it.phase == AndroidUltraRendererSessionPhase.ACTIVE
        }) { "Theme video ingress is not the live session-bound stream" }
    }

    private fun requireLiveLocalCanvas(
        canvas: UltraThemeLocalCanvasSlotBinding,
        state: AndroidUltraRendererSessionSnapshot,
    ) {
        val record = requireNotNull(canvas.lease.recordFor(coordinator)) {
            "Local Canvas lease belongs to another renderer session"
        }
        require(state.ownedIngresses.any {
            it.key == canvas.lease.key && it.generation == record.generation &&
                it.phase == AndroidUltraRendererSessionPhase.ACTIVE
        } && coordinator.isCurrentLocalIngress(
            canvas.lease, canvas.contentId, canvas.rasterPixels,
        )) { "Local Canvas lease is not the exact active owned ingress generation" }
    }

    private fun issuedIngressSelectorMatches(
        id: UltraIngressId,
        stream: AirPlayVideoStreamHandle,
        spec: UiSyncRendererIngressSpec,
    ): Boolean = id == spec.id && spec.matches(stream)

    private fun requireAuthorizedProgramSelections(
        assignments: List<CompiledUiSyncDisplayAssignment>,
    ) {
        val byDestination = assignments.associateBy { it.destination }
        outputs.forEach { compiled ->
            val source = compiled.binding.source
            val variant = requireNotNull(issuedProgramVariant(
                requireNotNull(byDestination[compiled.binding.destination]), source,
            ))
            val selection = variant.selection
            check(selection.matches(source.layoutIndex, source.state,
                source.expectedProgramFingerprint) &&
                (variant.intent == null && source.signedIntent == null ||
                    variant.intent != null &&
                        source.signedIntent?.matches(variant.intent) == true) &&
                selection.structuralFingerprint == compiled.program.structuralFingerprint &&
                variant.localCanvases.associateBy { it.slot } ==
                    compiled.authorizedLocalBySlot &&
                variant.instrumentReferences.singleOrNull() == compiled.authorizedInstrument &&
                compiled.binding.source.profile === verifiedProfile &&
                compiled.executableStaticProof?.let { proof ->
                    proof.matches(source.displayIndex, source.layoutIndex, source.state,
                        selection.structuralFingerprint) &&
                        proof.displayType == compiled.program.displayType &&
                        proof.displayId == compiled.program.displayId &&
                        proof.layoutAddress == compiled.layoutAddress &&
                        proof.backgroundColor == compiled.program.instructions
                            .filterIsInstance<CarPlayThemeProgramInstruction.FillLayoutBackground>()
                            .single { it.address == compiled.layoutAddress }.color &&
                        proof.videoSlots == compiled.executableVideoSlots
                        && proof.selectedPrimaryComponents ==
                            selection.activePrimarySelections
                        && proof.instrumentReferenceSlots.map { it.address } ==
                            variant.instrumentReferences.map { it.slot }
                } == true
            ) { "Theme scene program is outside the issued compiled selection" }
        }
    }

    private fun issuedProgramVariant(
        assignment: CompiledUiSyncDisplayAssignment,
        source: UltraThemeVerifiedProgramSource,
    ): IssuedProgramVariant? {
        val intent = source.signedIntent
        if (assignment.programSelection.matches(source.layoutIndex, source.state,
                source.expectedProgramFingerprint) &&
            (intent == null || assignment.primaryIntent?.let(intent::matches) == true)
        ) return IssuedProgramVariant(assignment.primaryIntent, assignment.programSelection,
            assignment.slotIngressAssignments, assignment.localCanvasAssignments,
            assignment.instrumentReferenceAssignments)
        if (intent == null) return null
        return assignment.additionalVariants.singleOrNull { variant ->
            intent.matches(variant.intent) &&
                variant.programSelection.matches(source.layoutIndex, source.state,
                    source.expectedProgramFingerprint)
        }?.let { IssuedProgramVariant(it.intent, it.programSelection,
            it.slotIngressAssignments, it.localCanvasAssignments,
            it.instrumentReferenceAssignments) }
    }

    private fun executableVideoSlot(
        slot: CarPlayThemeProgramInstruction.Slot,
        components: Map<CarPlayThemeSceneComponentAddress,
            CarPlayThemeProgramInstruction.Component>,
        zones: Map<CarPlayThemeSceneZoneAddress, CarPlayThemeProgramInstruction.Zone>,
        selectedPrimaryComponents: List<com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemePrimarySelection>,
    ):
        CarPlayThemeExecutableVideoSlot = CarPlayThemeExecutableVideoSlot(
        slot.address,
        (slot.source as CarPlayThemeStructuralSource.InputStream).streamIdentifier,
        slot.stage, slot.zIndex, slot.frameInDisplayLogicalSpace,
        slotZoneClip(slot, components, zones, selectedPrimaryComponents),
    )

    private fun slotZoneClip(
        slot: CarPlayThemeProgramInstruction.Slot,
        components: Map<CarPlayThemeSceneComponentAddress,
            CarPlayThemeProgramInstruction.Component>,
        zones: Map<CarPlayThemeSceneZoneAddress, CarPlayThemeProgramInstruction.Zone>,
        selectedPrimaryComponents: List<com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemePrimarySelection>,
    ): CarPlayLogicalRect? {
        val component = requireNotNull(components[slot.address.component]) {
            "Theme slot has no selected component"
        }
        return when (component.clip) {
            CarPlayThemeStructuralClip.NONE -> null
            CarPlayThemeStructuralClip.ZONE_SHAPE -> {
                val zone = requireNotNull(zones[component.address.zone]) {
                    "Zone-shape clip has no selected zone"
                }
                val selectedCarousel = selectedPrimaryComponents.singleOrNull {
                    it.layoutIndex == component.address.zone.layout.layoutIndex &&
                        it.zoneIndex == component.address.zone.zoneIndex
                }
                require((zone.type == ZoneTypeCase.SINGLE ||
                    ((zone.type == ZoneTypeCase.MAPS_MEDIA || zone.type == ZoneTypeCase.DCA) &&
                        selectedCarousel?.componentIndex == component.address.componentIndex)) &&
                    zone.shape == ZoneShapeCase.RECTANGULAR && zone.cornerRadius == 0.0 &&
                    zone.address == slot.address.component.zone
                ) { "Zone-shape clip is not the selected zero-radius rectangular zone" }
                zone.frameInDisplayLogicalSpace
            }
            CarPlayThemeStructuralClip.CUSTOM_UNRESOLVED ->
                throw IllegalArgumentException("Custom component clip is not executable")
        }
    }

    private fun exactZoneClipPixels(
        logical: CarPlayLogicalRect,
        mapper: CarPlayLogicalPixelMapper,
        viewport: PixelSize,
    ): UltraPixelRect {
        require(logical.size.width > 0.0 && logical.size.height > 0.0) {
            "Zone-shape clip must have positive logical extent"
        }
        val subpixel = mapper.mapSubpixel(logical)
        require(subpixel.width > 0.0 && subpixel.height > 0.0 &&
            subpixel.left == Math.rint(subpixel.left) &&
            subpixel.top == Math.rint(subpixel.top) &&
            subpixel.right == Math.rint(subpixel.right) &&
            subpixel.bottom == Math.rint(subpixel.bottom)
        ) { "Zone-shape clip does not map to exact integral pixel edges" }
        val clip = requireNotNull(mapper.mapClip(logical))
        require(clip.xPixels.toDouble() == subpixel.left &&
            clip.yPixels.toDouble() == subpixel.top &&
            (clip.xPixels.toLong() + clip.widthPixels).toDouble() == subpixel.right &&
            (clip.yPixels.toLong() + clip.heightPixels).toDouble() == subpixel.bottom &&
            clip.widthPixels > 0 && clip.heightPixels > 0 &&
            clip.xPixels >= 0 && clip.yPixels >= 0 &&
            clip.xPixels.toLong() + clip.widthPixels <= viewport.widthPixels &&
            clip.yPixels.toLong() + clip.heightPixels <= viewport.heightPixels
        ) { "Zone-shape clip is not an exact in-viewport pixel rectangle" }
        return clip
    }

    private data class RankedLayer(
        val stage: CarPlayThemeSceneStage,
        val group: Int,
        val orderWithinGroup: Int,
        val layer: UltraSceneLayer,
    )

    private fun slotLayerId(address: CarPlayThemeSceneSlotAddress): UltraLayerId = UltraLayerId(
        "theme-${address.component.zone.layout.layoutIndex}-" +
            "${address.component.zone.zoneIndex}-${address.component.componentIndex}-${address.slotIndex}",
    )

    private fun zeroInsets(value: CarPlayLogicalEdgeInsets): Boolean =
        value.top == 0.0 && value.leading == 0.0 &&
            value.bottom == 0.0 && value.trailing == 0.0

    internal companion object {
        /** Requires an exact live coordinator-owned child, never a raw VerifiedUltraProfile. */
        fun fromAuthorizedProvisioning(
            coordinator: AndroidUltraRendererSessionCoordinator,
            provisioning: AuthorizedUltraProfileProvisioning,
            verifiedProfile: VerifiedUltraProfile,
            graph: UltraDisplayGraphSnapshot,
            outputBindings: Collection<UltraThemeOutputSceneBinding>,
        ): UltraThemeBoundSceneExecutor = UltraThemeBoundSceneExecutor(
            coordinator, verifiedProfile, coordinator.schemaFingerprint,
            coordinator.bindingFingerprint, graph, outputBindings, provisioning,
        )
    }
}

/** Repeated at execution even if an issuer already approved the initial styled catalog row. */
internal fun requireInitialStyledPaletteWitness(
    profile: VerifiedUltraProfile,
    displayIndex: Int,
    layoutIndex: Int,
    phonePackageDigest: UltraProfileDigest,
    styleID: String,
    layoutID: String,
    initialIntent: CompiledUiSyncThemeIntent?,
): CarPlayThemeResolvedStyleWitness {
    val witness = CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(
        profile, displayIndex, layoutIndex,
    )
    val exactIntent = requireNotNull(initialIntent) {
        "Initial styled theme has no signed palette intent"
    }
    require(witness.phonePackageDigest == phonePackageDigest &&
        witness.styleID == styleID &&
        witness.display.layouts[layoutIndex].id == layoutID &&
        exactIntent.layoutID == layoutID &&
        exactIntent.paletteID == witness.defaultPaletteID &&
        witness.selectedPaletteID == witness.defaultPaletteID
    ) { "Initial styled theme palette differs from signed phone default" }
    return witness
}

/**
 * Plans the exact producer raster before a child ingress is opened. This is only a sizing proof;
 * the executor still requires a live issuer lease and the exact coordinator-owned ingress.
 */
internal fun resolveAuthorizedLocalCanvasRasters(
    profile: VerifiedUltraProfile,
    assignment: CompiledUiSyncDisplayAssignment,
    selection: CompiledUiSyncThemeProgramSelection,
    localCanvasAssignments: List<CompiledUiSyncLocalCanvasAssignment>,
    pixelMapper: CarPlayLogicalPixelMapper,
    viewportPixels: PixelSize,
    selectedIntent: CompiledUiSyncThemeIntent? = null,
): List<Pair<CompiledUiSyncLocalCanvasAssignment, PixelSize>> {
    val matchingIntents = buildList {
        if (assignment.programSelection == selection &&
            assignment.localCanvasAssignments == localCanvasAssignments
        ) add(assignment.primaryIntent)
        assignment.additionalVariants.forEach { variant ->
            if (variant.programSelection == selection &&
                variant.localCanvasAssignments == localCanvasAssignments
            ) add(variant.intent)
        }
    }
    val issuedIntent = if (selectedIntent == null) matchingIntents.singleOrNull()
        else matchingIntents.singleOrNull { it == selectedIntent }
    require((selectedIntent == null && matchingIntents.size == 1) ||
        (selectedIntent != null && issuedIntent == selectedIntent)
    ) {
        "Local Canvas raster request is outside the selected compiled variant"
    }
    val path = com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
        .CARPLAY_PHONE_PACKAGE_JSON
    val entry = requireNotNull(profile.candidate.entries.singleOrNull { it.name == path }) {
        "Selected Profile package JSON is absent"
    }
    val bytes = requireNotNull(profile.candidate.content(path))
    require(entry.digest == assignment.phonePackageDigest &&
        UltraProfileDigest(MessageDigest.getInstance("SHA-256").digest(bytes)) == entry.digest
    ) { "Local Canvas package bytes differ from the issued Profile digest" }
    val arc = CarPlayThemeSchema.parseArcPackageJson(bytes)
    require(arc.extensions.isEmpty() && arc.driveModeDynamicAssignmentConfiguration == null &&
        arc.vehicleType == null && arc.vehicleTypes == null
    ) { "Local Canvas package has unexecutable active semantics" }
    val display = requireNotNull(arc.displays.getOrNull(assignment.profileDisplayIndex)) {
        "Selected Profile display is absent"
    }
    require(display.id == assignment.profileDisplayId &&
        display.type == assignment.profileDisplayType &&
        selection.layoutIndex in display.layouts.indices
    ) { "Local Canvas display or layout differs from the issued selection" }
    val styleWitness = display.layouts[selection.layoutIndex].layoutStyleID?.let { styleID ->
        val selectedPaletteID = requireNotNull(issuedIntent?.paletteID) {
            "Styled Local Canvas layout requires an exact signed palette intent"
        }
        CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(
            profile, assignment.profileDisplayIndex, selection.layoutIndex, selectedPaletteID,
        ).also { witness ->
            require(witness.phonePackageDigest == entry.digest &&
                witness.styleID == styleID &&
                witness.selectedPaletteID == selectedPaletteID
            ) { "Local Canvas style differs from the signed Profile selection" }
        }
    }
    val state = selection.stateSnapshot()
    val assessment = CarPlayThemeProgramCompiler().assessExecutableStaticSubset(
        styleWitness?.display ?: display,
        assignment.profileDisplayIndex, selection.layoutIndex, state,
        resolvedStyleWitness = styleWitness,
    )
    val proof = requireNotNull(assessment.proof) {
        "Selected Local Canvas layout has unsupported drawing semantics"
    }
    val program = assessment.structuralProgram
    require(proof.matches(assignment.profileDisplayIndex, selection.layoutIndex, state,
        selection.structuralFingerprint) &&
        program.structuralFingerprint == selection.structuralFingerprint
    ) { "Local Canvas layout is not the issued static program" }
    val fullDisplay = CarPlayLogicalRect(CarPlayLogicalPoint(0.0, 0.0), program.logicalSize)
    require(pixelMapper.mapSubpixel(fullDisplay) == UltraSubpixelRect(
        0.0, 0.0, viewportPixels.widthPixels.toDouble(),
        viewportPixels.heightPixels.toDouble(),
    )) { "Local Canvas mapper does not cover the exact output viewport" }
    val slots = program.instructions.filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
        .filter { it.address.component.zone.layout == proof.layoutAddress }
        .associateBy { it.address }
    require(localCanvasAssignments.size <= 4096 &&
        localCanvasAssignments.map { it.slot }.toSet().size == localCanvasAssignments.size
    ) { "Local Canvas selection contains duplicate or excessive slots" }
    return Collections.unmodifiableList(localCanvasAssignments.map { row ->
        val slot = requireNotNull(slots[row.slot]) {
            "Local Canvas slot is absent from the selected layout"
        }
        row to localCanvasRasterSize(slot, pixelMapper, viewportPixels)
    })
}

private fun localCanvasRasterSize(
    slot: CarPlayThemeProgramInstruction.Slot,
    pixelMapper: CarPlayLogicalPixelMapper,
    viewportPixels: PixelSize,
): PixelSize {
    require(slot.source == CarPlayThemeStructuralSource.Empty &&
        slot.style == SlotStyleCase.RECTANGULAR &&
        (slot.safeAreaInsets?.let {
            it.top == 0.0 && it.leading == 0.0 &&
                it.bottom == 0.0 && it.trailing == 0.0
        } ?: true)
    ) { "Local Canvas requires an empty, rectangular, zero-inset Profile slot" }
    val frame = pixelMapper.mapFrame(slot.frameInDisplayLogicalSpace)
    require(frame.widthPixels > 0 && frame.heightPixels > 0 &&
        frame.xPixels >= 0 && frame.yPixels >= 0 &&
        frame.xPixels.toLong() + frame.widthPixels <= viewportPixels.widthPixels &&
        frame.yPixels.toLong() + frame.heightPixels <= viewportPixels.heightPixels
    ) { "Local Canvas slot does not fit inside the exact physical viewport" }
    return PixelSize(frame.widthPixels, frame.heightPixels)
}
