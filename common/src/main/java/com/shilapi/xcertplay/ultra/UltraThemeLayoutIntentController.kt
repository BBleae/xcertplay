package com.shilapi.xcertplay.ultra

import android.graphics.Canvas
import com.shilapi.xcertplay.AuthorizedUltraProfileProvisioning
import com.shilapi.xcertplay.CompiledUiSyncDisplayAssignment
import com.shilapi.xcertplay.CompiledUiSyncLocalCanvasAssignment
import com.shilapi.xcertplay.CompiledUiSyncInstrumentReferenceAssignment
import com.shilapi.xcertplay.CompiledInstrumentSignalDisplayAssignment
import com.shilapi.xcertplay.sameInstrumentSignalSpec
import com.shilapi.xcertplay.CompiledUiSyncSelectedThemeVariant
import com.shilapi.xcertplay.CompiledUiSyncThemeIntent
import com.shilapi.xcertplay.CompiledUiSyncThemeProgramSelection
import com.shilapi.xcertplay.UltraProfileSceneUseAttempt
import com.shilapi.xcertplay.airplay.UltraRevealEvent
import com.shilapi.xcertplay.airplay.UltraRevealTrace
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncAccessoryCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRequestEchoLayoutAuthority
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticDecodeResult
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticDecoder
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticReduceResult
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticReducer
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBatch
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestPort
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestState
import com.shilapi.xcertplay.ultra.egl.UltraEglIngressKey
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalLayerDraw
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalFirstFrameExpectation
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalFirstFrameReceipt
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationEventSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A scene queue receipt, not a UI Sync ACK or evidence of physical presentation. */
internal data class UltraThemeLayoutIntentControllerSnapshot(
    val lastSubmittedRevision: Long?,
    val selectedLayoutIndices: Map<UltraUiSyncDisplayId, Int>,
    /** Neither armed nor activated is a renderer readiness or physical-presentation receipt. */
    val activated: Boolean,
    val pendingInputStream: Boolean,
    /** A desired signed layout needs pixels that cannot yet be carried or freshly posted. */
    val pendingLocalCanvas: Boolean,
    val closed: Boolean,
    val failed: Boolean,
    /** Post + EGL draw/swap/finish evidence for this exact revision; not optical scan-out. */
    val localFirstFrameReceipt: UltraEglLocalFirstFrameReceipt? = null,
)

internal enum class UltraThemeLayoutActivationResult {
    SUBMITTED,
    ALREADY_ACTIVE,
    PENDING_INPUT_STREAM,
    PENDING_LOCAL_CANVAS,
    /** MAIN is covered until a fresh issued phone visual choice and signed EGL revision. */
    PENDING_SIGNED_PALETTE,
}

/** A missed MAIN TextureView timestamp can only ask the same signed controller to resubmit. */
internal enum class UltraThemeMainReproofResult { SUBMITTED, BUSY_RETRY, REVOKED }

/** Exact CLUSTER swap/finish observation, still not an optical or phone acknowledgement. */
internal class UltraThemeClusterPresentedFrame internal constructor(
    val graph: UltraDisplayGraphSnapshot,
    val scene: UltraOutputScene,
    val revision: Long,
    val physicalBatch: UiSyncPhysicalOutputBatch,
    val ticket: UltraEglRenderTicket,
    val localFirstFrameReceipt: UltraEglLocalFirstFrameReceipt?,
)

/** Exact MAIN signed-scene observation; the authority must still verify the native batch. */
internal class UltraThemeMainPresentedFrame internal constructor(
    val graph: UltraDisplayGraphSnapshot,
    val scene: UltraOutputScene,
    val revision: Long,
    val physicalBatch: UiSyncPhysicalOutputBatch,
    val ticket: UltraEglRenderTicket,
    val localFirstFrameReceipt: UltraEglLocalFirstFrameReceipt?,
)

/** Both sources are rebound even when one category has no slots on this output. */
internal fun rebindSelectedThemeOutput(
    binding: UltraThemeOutputSceneBinding,
    localCanvases: List<UltraThemeLocalCanvasSlotBinding>,
    instrument: UltraThemeInstrumentReferenceBinding?,
): UltraThemeOutputSceneBinding = binding.copy(
    localCanvasSlots = localCanvases,
    instrumentReference = instrument,
)

/** Only a still-unsubmitted Local Canvas handoff may take this nonterminal path. */
private class LocalCanvasTransitionPendingException(message: String) :
    IllegalStateException(message)

/**
 * ARM subscribes and reduces type-3 history before a provisioning child or physical output may
 * exist. It retains a bounded, gap-checked type-3 history and cannot touch EGL. ACTIVATE
 * requires a live AA05 child, its issued catalog, the exact current graph, and a current physical
 * batch. Only a complete signed multi-output scene can be submitted. A missing active InputStream
 * stays pending until the caller retries activation with the newly published graph.
 *
 * The signed Debug composition installs this only after renderer authorization. It cannot
 * assert UI Sync readiness, send a protocol completion/ACK, or prove that queued EGL work reached
 * a panel.
 */
internal class UltraThemeLayoutIntentController private constructor(
    private val coordinator: AndroidUltraRendererSessionCoordinator,
    displayIds: Collection<UltraUiSyncDisplayId>,
    private val localCanvasOwner: UltraLocalCanvasSlotOwner?,
    private val instrumentOwner: UltraInstrumentReferenceSlotOwner?,
    private val localCanvasPainter:
        ((UltraContentId) -> ((Canvas, PixelSize) -> Unit)?)?,
    private val diagnosticFallbackEnabled: Boolean,
    private val foregroundRequests: UiSyncForegroundRequestPort?,
    private val onClusterPresented: ((UltraThemeClusterPresentedFrame) -> Unit)?,
    private val onMainPresented: ((UltraThemeMainPresentedFrame) -> Unit)?,
    private val beginMainSemanticCover:
        ((UiSyncPhysicalOutputBatch, UltraUiSyncDisplayId, Long) ->
            MainSemanticPhysicalCoverTicket?)?,
    private val resumeMainAfterSignedPhoneVisualChoice:
        ((UiSyncPhysicalOutputBatch, UltraUiSyncDisplayId, Long) -> Boolean)?,
) : Closeable {
    private val lock = Any()
    private val reducers: Map<UltraUiSyncDisplayId, CarPlayUiSyncSemanticReducer>
    private val subscriptions = ArrayList<AndroidUltraPresentationSubscription>()
    /** Latest complete replay per display while another callback/submit is on this stack. */
    private val deferredSnapshots = LinkedHashMap<UltraUiSyncDisplayId, UltraPresentationSnapshot>()
    /** Pending history is needed so B followed by unsupported C still selects B at activation. */
    private val queuedLayoutIntents = LinkedHashMap<UltraUiSyncDisplayId,
        ArrayList<CarPlayUiSyncSemanticCommand.LayoutChange>>()
    private var queuedIntentCount = 0
    private val semanticDecoder = CarPlayUiSyncSemanticDecoder()
    /** Only intents already checked against this exact issued catalog enter this map. */
    private val signedIntents = LinkedHashMap<UltraUiSyncDisplayId,
        CarPlayUiSyncSemanticCommand.LayoutChange>()
    /** A type-16 never becomes a synthetic type-3; it retains its own phone event revision. */
    private val signedPaletteSelections = LinkedHashMap<UltraUiSyncDisplayId,
        PhonePaletteSelection>()
    /** A channel Reset/close ends phone visual authority until a newer signed type-3 arrives. */
    private val protocolResetRevisionFloors = LinkedHashMap<UltraUiSyncDisplayId, Long>()
    private val protocolResetSuspendedDisplays = LinkedHashSet<UltraUiSyncDisplayId>()
    /**
     * The exact physical batch whose submitted MAIN this display's last Reset covered through
     * the Authority. Only such a MAIN may render a covered re-request; one that had no
     * submitted scene has no semantic cover, so it stays pending exactly as before.
     */
    private val resetCoveredMainBatches =
        LinkedHashMap<UltraUiSyncDisplayId, UiSyncPhysicalOutputBatch>()
    /** A CLUSTER Reset also requires a newly submitted scene, not just a new phone command. */
    private val clusterResetSceneRevisionFloors = LinkedHashMap<UltraUiSyncDisplayId, Long>()
    /** Standalone palette and appearance commands are not implied by a type-3 layout choice. */
    private val paletteSuspendedDisplays = LinkedHashSet<UltraUiSyncDisplayId>()
    private val appearanceSuspendedDisplays = LinkedHashSet<UltraUiSyncDisplayId>()
    /** Active drive-mode theme overrides have no confirmed static rendering crosswalk. */
    private val opaqueThemeSuspendedDisplays = LinkedHashSet<UltraUiSyncDisplayId>()
    /** Type-1 is a boundary, not a layout choice or proof that the new pixels are visible. */
    private val transitionSuspendedDisplays = LinkedHashSet<UltraUiSyncDisplayId>()
    private val latestTransitionStartEventRevision =
        LinkedHashMap<UltraUiSyncDisplayId, Long>()
    /** Even an identical post-transition signed choice needs a new scene/EGL revision. */
    private val transitionRefreshPendingDisplays = LinkedHashSet<UltraUiSyncDisplayId>()
    /** An unsupported phone choice may leave old pixels up, but never old raw5 authority. */
    private val unsupportedLayoutSuspendedDisplays = LinkedHashSet<UltraUiSyncDisplayId>()
    /** A later exact signed choice needs its own scene/EGL proof, even at the same layout. */
    private val foregroundRefreshPendingDisplays = LinkedHashSet<UltraUiSyncDisplayId>()
    private val appearanceTransitionOpen = LinkedHashMap<UltraUiSyncDisplayId, Int>()
    /** Last type 21/22 revision; a later standalone type 19 never reopens that boundary. */
    private val latestAppearanceTransitionEventRevision =
        LinkedHashMap<UltraUiSyncDisplayId, Long>()
    /** Latest received standalone type 19 per display in this epoch; validated at activation. */
    private val phoneAppearancePreferences = LinkedHashMap<UltraUiSyncDisplayId,
        PendingAppearancePreference>()
    /** A type 19 never becomes a type 3; it only re-proves the same signed scene. */
    private val signedAppearanceSelections = LinkedHashMap<UltraUiSyncDisplayId,
        PhoneAppearanceSelection>()
    private val latestPaletteEventRevision = LinkedHashMap<UltraUiSyncDisplayId, Long>()
    private val latestAppearanceEventRevision = LinkedHashMap<UltraUiSyncDisplayId, Long>()
    private val latestOpaqueThemeEventRevision = LinkedHashMap<UltraUiSyncDisplayId, Long>()
    /** A covered MAIN cannot resume from a stale phone choice or a CLUSTER-only redraw. */
    private val latestMainCoverEventRevision = LinkedHashMap<UltraUiSyncDisplayId, Long>()
    private val foregroundSemanticsEpoch = LinkedHashMap<UltraUiSyncDisplayId, Long>()
    private var activation: Activation? = null
    private var executor: UltraThemeBoundSceneExecutor? = null
    private var selectedLayoutIndices: Map<UltraUiSyncDisplayId, Int> = emptyMap()
    private var pendingInputStream = false
    private var pendingLocalCanvas = false
    private var activated = false
    private var submittedGraph: UltraDisplayGraphSnapshot? = null
    private var submittedInputBindings: List<UltraThemeOutputSceneBinding>? = null
    private var submittedSelections: Map<UltraUiSyncDisplayId, SelectedSceneKey> = emptyMap()
    private var submittedPhysicalBatch: UiSyncPhysicalOutputBatch? = null
    private var opening = true
    private var dispatching = false
    private var retryScheduler: ScheduledThreadPoolExecutor? = null
    /** Protocol writes may block; they must never starve local-frame proof or stale-scene checks. */
    private var foregroundExecutor: ExecutorService? = null
    @Volatile private var foregroundWorkerThread: Thread? = null
    private var busyRetryScheduled = false
    private var busyRetryDelayMillis = MINIMUM_RETRY_DELAY_MILLIS
    private var closeRetryScheduled = false
    private var closeRetryCount = 0
    private var closeInFlight = false
    private var closed = false
    private var failed = false
    private var failure: Throwable? = null
    private var lastSubmittedRevision: Long? = null
    private var lastLocalFirstFrameReceipt: UltraEglLocalFirstFrameReceipt? = null
    /** Only posts included in the immediately preceding EGL-proven revision may be carried. */
    private var lastProvenLocalPosts: Map<UltraLocalCanvasSlotOwner.Handle,
        UltraLocalCanvasPostReceipt> = emptyMap()
    /** Exact post receipts whose producer actually invoked a registered custom painter. */
    private var lastProvenCustomLocalPosts: Map<UltraLocalCanvasSlotOwner.Handle,
        UltraLocalCanvasPostReceipt> = emptyMap()
    private var currentCarryLeases: List<UltraLocalCanvasCarryLease> = emptyList()
    /** Previous sources stay owned until a replacement revision is physically presented by EGL. */
    private var activeLocalCanvasHandles: Set<UltraLocalCanvasSlotOwner.Handle> = emptySet()
    /** A request belongs to only this child, signed MAIN choice, exact graph and output batch. */
    private var foregroundScene: ForegroundScene? = null
    private var lastForegroundRequest: ForegroundRequestRecord? = null
    /**
     * The raw5s recently handed to the port (bounded, oldest first), recorded before each port
     * call so a fast phone echo cannot race the receipt. Each may be consumed by one echo.
     */
    private val issuedRequestLayouts = java.util.ArrayDeque<IssuedRequestLayout>()
    /**
     * Assetless request-echo decision per attested type-6 event revision, made once by the first
     * preflight that sees it (null = denied) so a BUSY-deferred replay can never re-decide it in
     * either direction. Pruned once the reducer has applied that revision.
     */
    private val requestEchoAuthorities = LinkedHashMap<UltraUiSyncDisplayId,
        LinkedHashMap<Long, CarPlayUiSyncRequestEchoLayoutAuthority?>>()
    /** Phase 1 already revoked old permits; phase 2 waits for exact Host cover ACK off-lock. */
    private var pendingMainSemanticCover: MainSemanticPhysicalCoverTicket? = null
    private var pendingMainSemanticDisplayId: UltraUiSyncDisplayId? = null
    private var mainSemanticCoverInFlight = false
    private var mainSemanticCoverCompletion: CountDownLatch? = null

    private fun isActivatedMainDisplayLocked(displayId: UltraUiSyncDisplayId): Boolean {
        val current = activation ?: return false
        return current.outputBindings.any { binding ->
            binding.destination.role == DisplayDestinationRole.MAIN &&
                current.graph.output(binding.outputId)?.uiSyncDisplayId == displayId
        }
    }

    /**
     * After a phone Reset the assetless phone never volunteers a type 3, so the HU must ask again.
     * True only for a MAIN whose submitted scene that Reset covered on this same physical batch
     * (so the Authority holds its semantic cover), now covered *solely* by that Reset, with a
     * raw5 port and no signed choice
     * of the new epoch yet: the primary signed scene is then rendered as a fresh revision under
     * the unchanged Host cover (no resume without a fresh phone choice above the Reset floor),
     * only so a presented foreground scene can re-issue raw5 in the new READY epoch.
     */
    private fun resetReRequestLocked(displayId: UltraUiSyncDisplayId): Boolean =
        foregroundRequests != null && !closed && !failed &&
            displayId in protocolResetSuspendedDisplays &&
            displayId !in paletteSuspendedDisplays &&
            displayId !in appearanceSuspendedDisplays &&
            displayId !in transitionSuspendedDisplays &&
            displayId !in unsupportedLayoutSuspendedDisplays &&
            displayId !in opaqueThemeSuspendedDisplays &&
            displayId !in appearanceTransitionOpen &&
            signedIntents[displayId] == null && signedPaletteSelections[displayId] == null &&
            activation?.physicalBatch?.isCurrent == true &&
            resetCoveredMainBatches[displayId] === activation?.physicalBatch &&
            isActivatedMainDisplayLocked(displayId)

    /** Off-lock activation retry for a Reset re-request that needed no new Host cover. */
    private fun scheduleReactivationLocked() {
        val resume = activation ?: return
        val scheduled = runCatching {
            retrySchedulerLocked().execute {
                if (synchronized(lock) { closed || failed || activation !== resume }) {
                    return@execute
                }
                // Not a phone-triggered terminal failure: a rotated batch, stale graph, or BUSY
                // timeout leaves MAIN under the Reset cover until the owner activates again.
                runCatching {
                    activateInternal(resume.provisioning, resume.profile, resume.graph,
                        resume.outputBindings, resume.physicalBatch)
                }.exceptionOrNull()?.let {
                    UltraRevealTrace.mark(UltraRevealEvent.REVEAL_SWALLOWED,
                        "where" to "RESET_REACTIVATION", "error" to it)
                }
            }
        }
        scheduled.exceptionOrNull()?.let { cause ->
            failLocked(IllegalStateException("Could not schedule the Reset re-request", cause))
        }
    }

    /** The desired type 3 only when it belongs to the current phone epoch (above the floor). */
    private fun currentEpochDesiredLayoutLocked(
        displayId: UltraUiSyncDisplayId,
    ): CarPlayUiSyncSemanticCommand.LayoutChange? {
        val state = checkNotNull(reducers[displayId]).state()
        val revision = state.lastLayoutChangeCommandRevision ?: return null
        return state.desiredLayoutChange?.takeIf {
            revision > (protocolResetRevisionFloors[displayId] ?: Long.MIN_VALUE)
        }
    }

    /**
     * A standalone type 19 is an appearance preference, not a layout choice (iOS 27 assetless
     * delegate: `sendAppearancePreference` right after the requestLayout echo, and no later
     * layout choice ever follows). It may re-prove, as a fresh covered revision, exactly the
     * signed scene the phone already authorized, never select another one. All must hold:
     * - this is the activated MAIN on the current physical batch, its signed scene submitted;
     * - the display has a current-epoch signed layout authority (received type 3 or the
     *   assetless request-echo authority) that predates the type 19, and no type 21/22 since;
     * - the type 19 is the latest appearance event, carries no override, and names a preference
     *   in [SUPPORTED_APPEARANCE_PREFERENCES];
     * - no transition, palette, unsupported, opaque, or Reset suspension and no open type 21;
     * - the issuer returns the exact current variant as appearance-invariant (the v3 catalog
     *   has no per-appearance rows, so there is no variant to select instead).
     * MAIN stays covered; reveal still needs the new revision's PRESENTED/first-frame proof and
     * the exact Host cover ACK through the unchanged Authority path.
     */
    private fun signedAppearanceSelectionLocked(
        current: Activation,
        displayId: UltraUiSyncDisplayId,
        pending: PendingAppearancePreference,
    ): PhoneAppearanceSelection? {
        val (selection, reason) = decideAppearanceSelectionLocked(current, displayId, pending)
        val key = pending.eventRevision to reason
        if (UltraRevealTrace.enabled && lastAppearanceTrace[displayId] != key) {
            lastAppearanceTrace[displayId] = key
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_APPEARANCE,
                "decision" to if (selection != null) "ACCEPTED" else "DENIED",
                "reason" to reason, "preference" to pending.command.preference,
                "hasOverride" to (pending.command.appearanceOverride != null))
        }
        return selection
    }

    /** Debug trace de-duplication only: (type-19 revision, reason) last emitted per display. */
    private val lastAppearanceTrace = LinkedHashMap<UltraUiSyncDisplayId, Pair<Long, String>>()

    private fun decideAppearanceSelectionLocked(
        current: Activation,
        displayId: UltraUiSyncDisplayId,
        pending: PendingAppearancePreference,
    ): Pair<PhoneAppearanceSelection?, String> {
        fun deny(reason: String): Pair<PhoneAppearanceSelection?, String> = null to reason
        if (current.physicalBatch?.isCurrent != true ||
            submittedPhysicalBatch !== current.physicalBatch ||
            submittedGraph !== current.graph
        ) return deny("NO_CURRENT_SUBMITTED_BATCH")
        if (!isActivatedMainDisplayLocked(displayId)) return deny("NOT_MAIN")
        val state = checkNotNull(reducers[displayId]).state()
        val command = pending.command
        if (command.appearanceOverride != null) return deny("HAS_OVERRIDE")
        if (command.preference !in SUPPORTED_APPEARANCE_PREFERENCES)
            return deny("UNSUPPORTED_PREFERENCE")
        if (state.lastAppearancePreferenceChange != command ||
            latestAppearanceEventRevision[displayId] != pending.eventRevision
        ) return deny("NOT_LATEST_APPEARANCE_EVENT")
        if (displayId in appearanceTransitionOpen) return deny("APPEARANCE_TRANSITION_OPEN")
        val suspension = when {
            displayId in protocolResetSuspendedDisplays -> "RESET_SUSPENDED"
            displayId in transitionSuspendedDisplays -> "TRANSITION_SUSPENDED"
            displayId in paletteSuspendedDisplays -> "PALETTE_SUSPENDED"
            displayId in unsupportedLayoutSuspendedDisplays -> "UNSUPPORTED_LAYOUT_SUSPENDED"
            displayId in opaqueThemeSuspendedDisplays -> "OPAQUE_SUSPENDED"
            queuedLayoutIntents[displayId]?.isNotEmpty() == true -> "LAYOUT_QUEUED"
            else -> null
        }
        if (suspension != null) return deny(suspension)
        val signed = signedIntents[displayId] ?: return deny("NO_SIGNED_LAYOUT_AUTHORITY")
        val layoutRevision = state.lastLayoutChangeCommandRevision
            ?: return deny("NO_SIGNED_LAYOUT_AUTHORITY")
        if (state.desiredLayoutChange != signed) return deny("DESIRED_NOT_SIGNED")
        if (layoutRevision <= (protocolResetRevisionFloors[displayId] ?: Long.MIN_VALUE))
            return deny("LAYOUT_BELOW_RESET_FLOOR")
        if (layoutRevision >= pending.eventRevision) return deny("LAYOUT_NOT_BEFORE_TYPE19")
        if (layoutRevision <= (latestAppearanceTransitionEventRevision[displayId]
                ?: Long.MIN_VALUE)
        ) return deny("APPEARANCE_TRANSITION_SINCE_LAYOUT")
        val intent = signedPaletteSelections[displayId]?.let { palette ->
            if (state.palette != palette.command ||
                latestPaletteEventRevision[displayId] != palette.eventRevision
            ) return deny("PALETTE_SELECTION_STALE")
            palette.selectedIssuedIntent
        } ?: CompiledUiSyncThemeIntent(signed.layoutID, signed.activeComponents, signed.paletteID)
        val selected = current.provisioning.selectAppearanceInvariantVariantFor(
            current.profile, displayId, intent,
        ) ?: return deny("NOT_APPEARANCE_INVARIANT")
        if (selected.intent != intent) return deny("NOT_APPEARANCE_INVARIANT")
        return PhoneAppearanceSelection(command, pending.eventRevision, intent) to
            "SAME_SIGNED_SCENE"
    }

    private fun mainSemanticSuspendedLocked(displayId: UltraUiSyncDisplayId): Boolean =
        displayId in paletteSuspendedDisplays ||
            displayId in protocolResetSuspendedDisplays ||
            displayId in appearanceSuspendedDisplays ||
            displayId in transitionSuspendedDisplays ||
            displayId in unsupportedLayoutSuspendedDisplays ||
            displayId in opaqueThemeSuspendedDisplays

    private class Activation(
        val provisioning: AuthorizedUltraProfileProvisioning,
        val profile: VerifiedUltraProfile,
        val graph: UltraDisplayGraphSnapshot,
        val outputBindings: List<UltraThemeOutputSceneBinding>,
        /** Null is confined to the old, detached diagnostic open path. */
        val physicalBatch: UiSyncPhysicalOutputBatch?,
    )

    private class PreparedOutput(
        val binding: UltraThemeOutputSceneBinding,
        val assignment: CompiledUiSyncDisplayAssignment,
        val program: com.shilapi.xcertplay.CompiledUiSyncThemeProgramSelection,
        val localCanvases: List<CompiledUiSyncLocalCanvasAssignment>,
        val instrumentReferences: List<CompiledUiSyncInstrumentReferenceAssignment>,
        val instrumentSignals: List<CompiledInstrumentSignalDisplayAssignment>,
        val intent: CompiledUiSyncThemeIntent?,
        val signedPhoneCommand: CarPlayUiSyncSemanticCommand.LayoutChange?,
        val signedPhonePalette: PhonePaletteSelection?,
        val signedPhoneAppearance: PhoneAppearanceSelection? = null,
    )

    private class PhonePaletteSelection(
        val command: CarPlayUiSyncSemanticCommand.PaletteChange,
        val eventRevision: Long,
        val previousIssuedIntent: CompiledUiSyncThemeIntent,
        val selectedIssuedIntent: CompiledUiSyncThemeIntent,
    )

    private class PendingAppearancePreference(
        val command: CarPlayUiSyncSemanticCommand.AppearancePreferenceChange,
        val eventRevision: Long,
    )

    /** The exact signed intent re-rendered for [command]; never a different catalog row. */
    private class PhoneAppearanceSelection(
        val command: CarPlayUiSyncSemanticCommand.AppearancePreferenceChange,
        val eventRevision: Long,
        val intent: CompiledUiSyncThemeIntent,
    )

    private data class SelectedSceneKey(
        val program: CompiledUiSyncThemeProgramSelection,
        val intent: CompiledUiSyncThemeIntent?,
        val localCanvases: List<CompiledUiSyncLocalCanvasAssignment>,
        val instruments: List<CompiledUiSyncInstrumentReferenceAssignment>,
        val streams: List<UltraThemeSlotIngressBinding>,
    )

    private class ForegroundScene(
        val activation: Activation,
        val revision: Long,
        val displayId: UltraUiSyncDisplayId,
        val intent: CompiledUiSyncThemeIntent,
        val semanticsEpoch: Long,
        val signedPhoneCommand: CarPlayUiSyncSemanticCommand.LayoutChange?,
        val mainScene: UltraOutputScene,
        val ticket: UltraEglRenderTicket,
        val mainLocalDraws: Set<UltraEglLocalLayerDraw>,
        val source: UltraThemeForegroundSceneSource,
        val opaquePresentationDeadlineNanos: Long,
    ) {
        var presented = false
        var requestScheduled = false
        var reconnectRetryInFlight = false
        var reconnectRetryDelayMillis = FOREGROUND_ADMISSION_RETRY_MILLIS
    }

    private class ForegroundRequestRecord(
        val scene: ForegroundScene,
        val receipt: UiSyncForegroundRequestReceipt,
    )

    /** Built from [scene]'s signed intent; [receipt] is null until the port call returns. */
    private class IssuedRequestLayout(
        val scene: ForegroundScene,
        val command: CarPlayUiSyncAccessoryCommand.RequestLayout,
    ) {
        var receipt: UiSyncForegroundRequestReceipt? = null
        var echoConsumed = false
    }

    init {
        val frozen = displayIds.toList()
        require(frozen.isNotEmpty() && frozen.size <= 64 && frozen.toSet().size == frozen.size &&
            frozen.map { it.value }.toSet() == coordinator.displayIds
        ) { "Issuer display IDs and renderer-session presentation IDs differ" }
        reducers = Collections.unmodifiableMap(frozen.associateWith(::CarPlayUiSyncSemanticReducer))
    }

    /**
     * Called once per exact ClusterControl display epoch loss. The RCS callback thread performs
     * only permit revocation here; its Host cover ticket is completed by this controller's worker.
     * A previously queued Hub snapshot may still arrive, so its entire revision range is fenced.
     */
    internal fun invalidatePhoneEpoch(
        displayId: UltraUiSyncDisplayId,
        presentationRevisionFloor: Long,
    ): Boolean {
        if (presentationRevisionFloor < 0L) return false
        val shouldCover = synchronized(lock) {
            if (closed || failed || displayId !in reducers) return false
            val floor = maxOf(presentationRevisionFloor,
                reducers.getValue(displayId).state().revision,
                protocolResetRevisionFloors[displayId] ?: 0L)
            protocolResetRevisionFloors[displayId] = floor
            protocolResetSuspendedDisplays += displayId
            val clusterDestination = activation?.outputBindings?.singleOrNull { binding ->
                binding.destination.role == DisplayDestinationRole.CLUSTER &&
                    activation?.graph?.output(binding.outputId)?.uiSyncDisplayId == displayId
            }?.destination
            if (clusterDestination != null) {
                val sceneFloor = maxOf(lastSubmittedRevision ?: 0L,
                    clusterResetSceneRevisionFloors[displayId] ?: 0L)
                clusterResetSceneRevisionFloors[displayId] = sceneFloor
                if (instrumentOwner?.invalidatePhoneEpoch(clusterDestination, sceneFloor) == false) {
                    failLocked(IllegalStateException(
                        "Phone UI Sync epoch ended without a new CLUSTER safety-clear owner"))
                    return false
                }
            }
            signedPaletteSelections.remove(displayId)
            signedIntents.remove(displayId)
            phoneAppearancePreferences.remove(displayId)
            signedAppearanceSelections.remove(displayId)
            // A raw5 issued before this Reset can never be answered in the new epoch.
            requestEchoAuthorities.remove(displayId)
            issuedRequestLayouts.removeAll { it.scene.displayId == displayId }
            queuedLayoutIntents.remove(displayId)?.let { queued ->
                queuedIntentCount -= queued.size
            }
            deferredSnapshots.remove(displayId)
            latestMainCoverEventRevision[displayId] = maxOf(
                latestMainCoverEventRevision[displayId] ?: 0L, floor,
            )
            foregroundSemanticsEpoch[displayId] = Math.addExact(
                foregroundSemanticsEpoch[displayId] ?: 0L, 1L,
            )
            foregroundScene?.takeIf { it.displayId == displayId }?.source?.revoke()
            val submittedMain = activation?.outputBindings?.any { binding ->
                binding.destination.role == DisplayDestinationRole.MAIN &&
                    activation?.graph?.output(binding.outputId)?.uiSyncDisplayId == displayId
            } == true && submittedSelections[displayId] != null &&
                submittedPhysicalBatch === activation?.physicalBatch
            if (submittedMain && !suspendSubmittedMainSemanticsLocked(displayId)) {
                failLocked(IllegalStateException(
                    "Phone UI Sync epoch ended without exact MAIN permit revocation"))
                return false
            }
            val coveredBatch = submittedPhysicalBatch.takeIf { submittedMain }
            if (coveredBatch != null) resetCoveredMainBatches[displayId] = coveredBatch
            else resetCoveredMainBatches.remove(displayId)
            if (coveredBatch != null) {
                // Every phone visual boundary received so far is at or below the new floor, so
                // any later signed choice above it would lift them anyway. Only the Reset itself
                // keeps this MAIN covered. A type 21 at or below the floor is closed too: the
                // phone reopened its session and never sends that old epoch's type 22.
                transitionSuspendedDisplays.remove(displayId)
                paletteSuspendedDisplays.remove(displayId)
                opaqueThemeSuspendedDisplays.remove(displayId)
                unsupportedLayoutSuspendedDisplays.remove(displayId)
                appearanceTransitionOpen.remove(displayId)
                appearanceSuspendedDisplays.remove(displayId)
            }
            val cover = pendingMainSemanticCover != null
            if (UltraRevealTrace.enabled) UltraRevealTrace.mark(UltraRevealEvent.REVEAL_RESET_REREQUEST,
                "mainCovered" to (coveredBatch != null), "coverPending" to cover,
                "eligible" to resetReRequestLocked(displayId), "floor" to floor)
            if (!cover && resetReRequestLocked(displayId)) scheduleReactivationLocked()
            cover
        }
        if (shouldCover) {
            val scheduled = runCatching {
                synchronized(lock) { retrySchedulerLocked() }.execute {
                    completePendingMainSemanticCover()
                }
            }
            if (scheduled.isFailure) {
                synchronized(lock) { if (!failed) failLocked(IllegalStateException(
                    "Phone UI Sync epoch cover could not be scheduled",
                    scheduled.exceptionOrNull(),
                )) }
                return false
            }
        }
        return true
    }

    /** Pure snapshot for the Authority's exact CLUSTER permit predicate; no external probes. */
    internal fun clusterRecoveryWitness(
        displayId: UltraUiSyncDisplayId,
        sceneRevision: Long,
    ): Boolean = synchronized(lock) {
        if (closed || failed || sceneRevision <= 0L ||
            lastSubmittedRevision != sceneRevision
        ) return@synchronized false
        val current = activation ?: return@synchronized false
        if (submittedGraph !== current.graph ||
            submittedPhysicalBatch !== current.physicalBatch ||
            submittedSelections[displayId] == null ||
            current.outputBindings.none { binding ->
                binding.destination.role == DisplayDestinationRole.CLUSTER &&
                    current.graph.output(binding.outputId)?.uiSyncDisplayId == displayId
            }
        ) return@synchronized false
        val resetFloor = protocolResetRevisionFloors[displayId]
            ?: return@synchronized true
        if (displayId in protocolResetSuspendedDisplays ||
            sceneRevision <= (clusterResetSceneRevisionFloors[displayId] ?: Long.MAX_VALUE)
        ) return@synchronized false
        val state = reducers[displayId]?.state() ?: return@synchronized false
        val type3Revision = state.lastLayoutChangeCommandRevision ?: return@synchronized false
        type3Revision > resetFloor &&
            state.desiredLayoutChange != null &&
            signedIntents[displayId] == state.desiredLayoutChange
    }

    private fun start() {
        try {
            reducers.keys.forEach { id ->
                val subscription = coordinator.subscribePresentation(id.value,
                    UltraPresentationObserver { snapshot -> onSnapshot(id, snapshot) })
                synchronized(lock) {
                    subscriptions += subscription
                    require(subscription.initialSnapshot.displayId == id) {
                        "Presentation replay came from another subscribed display"
                    }
                    dispatchSnapshotLocked(subscription.initialSnapshot)
                }
            }
            synchronized(lock) {
                if (failed) throw IllegalStateException(
                    "Signed theme replay was not admissible", failure,
                )
                check(!closed) { "Signed theme controller closed during startup" }
            }
        } catch (cause: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(cause::addSuppressed)
            throw cause
        } finally {
            synchronized(lock) {
                opening = false
                if (failed && !closed) scheduleCloseRetryLocked(0L)
            }
        }
    }

    /**
     * Product-facing second phase. The batch is an independently issued, current physical-output
     * lease, not a caller-created READY flag. Its exact route set must match the issued Profile.
     * The product owner must call again with the new exact graph after a video publish/rebind.
     */
    fun activate(
        provisioning: AuthorizedUltraProfileProvisioning,
        verifiedProfile: VerifiedUltraProfile,
        graph: UltraDisplayGraphSnapshot,
        outputBindings: Collection<UltraThemeOutputSceneBinding>,
        physicalBatch: UiSyncPhysicalOutputBatch,
    ): UltraThemeLayoutActivationResult = activateInternal(
        provisioning, verifiedProfile, graph, outputBindings, physicalBatch,
    )

    /** One bounded caller attempt; never waits for AA05 admission or sends foreground raw5. */
    internal fun reproveMainSceneAfterMiss(
        graph: UltraDisplayGraphSnapshot,
        physicalBatch: UiSyncPhysicalOutputBatch,
        expectedRevision: Long,
    ): UltraThemeMainReproofResult {
        val current = synchronized(lock) {
            activation?.takeIf {
                !closed && !failed && !opening && !dispatching && activated &&
                    it.graph === graph && it.physicalBatch === physicalBatch &&
                    submittedGraph === graph && submittedPhysicalBatch === physicalBatch &&
                    lastSubmittedRevision == expectedRevision && executor != null &&
                    queuedLayoutIntents.isEmpty() && transitionRefreshPendingDisplays.isEmpty() &&
                    foregroundRefreshPendingDisplays.isEmpty()
            }
        } ?: return UltraThemeMainReproofResult.REVOKED
        val admission = try {
            current.provisioning.tryWithSceneSubmissionUse {
                synchronized(lock) {
                    if (closed || failed || dispatching || activation !== current ||
                        !activated || lastSubmittedRevision != expectedRevision ||
                        submittedGraph !== graph || submittedPhysicalBatch !== physicalBatch ||
                        queuedLayoutIntents.isNotEmpty() ||
                        transitionRefreshPendingDisplays.isNotEmpty() ||
                        foregroundRefreshPendingDisplays.isNotEmpty() ||
                        coordinator.graphSnapshot() !== graph || !coordinator.isLive
                    ) return@synchronized UltraThemeMainReproofResult.REVOKED
                    when (physicalBatch.generationStatus) {
                        com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus.BUSY ->
                            return@synchronized UltraThemeMainReproofResult.BUSY_RETRY
                        com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus.REVOKED ->
                            return@synchronized UltraThemeMainReproofResult.REVOKED
                        com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus.CURRENT -> Unit
                    }
                    // A reproof does not issue raw5. Let the original exact foreground
                    // request finish its local write before replacing its revision/source.
                    // QUEUED, CANCELLED, REJECTED and uncertain writes stay covered while the
                    // authority's bounded retry waits; none is a confirmed local send.
                    foregroundScene?.let { scene ->
                        val request = lastForegroundRequest
                        if (request == null || request.receipt.state !=
                                UiSyncForegroundRequestState.SENT_UNCONFIRMED ||
                            (request.scene !== scene &&
                                !previousMainRequestSupersedesLocked(scene))
                        ) return@synchronized UltraThemeMainReproofResult.BUSY_RETRY
                    }
                    val assignments = current.provisioning.requireSceneDisplayAssignments(
                        current.profile, coordinator.schemaFingerprint,
                        coordinator.bindingFingerprint, graph,
                    )
                    dispatching = true
                    try {
                        when (activateLocked(current, assignments, reproof = true)) {
                            UltraThemeLayoutActivationResult.SUBMITTED ->
                                UltraThemeMainReproofResult.SUBMITTED
                            UltraThemeLayoutActivationResult.PENDING_LOCAL_CANVAS ->
                                UltraThemeMainReproofResult.BUSY_RETRY
                            else -> UltraThemeMainReproofResult.REVOKED
                        }
                    } finally {
                        dispatching = false
                    }
                }
            }
        } catch (_: RuntimeException) {
            return UltraThemeMainReproofResult.REVOKED
        }
        return when (admission) {
            is UltraProfileSceneUseAttempt.Admitted -> admission.value
            UltraProfileSceneUseAttempt.Busy -> UltraThemeMainReproofResult.BUSY_RETRY
            UltraProfileSceneUseAttempt.Revoked -> UltraThemeMainReproofResult.REVOKED
        }
    }

    /** Detached legacy test path only; never a physical authority or product readiness gate. */
    private fun activateForDiagnostics(
        provisioning: AuthorizedUltraProfileProvisioning,
        verifiedProfile: VerifiedUltraProfile,
        graph: UltraDisplayGraphSnapshot,
        outputBindings: Collection<UltraThemeOutputSceneBinding>,
    ): UltraThemeLayoutActivationResult = activateInternal(
        provisioning, verifiedProfile, graph, outputBindings, null,
    )

    private fun activateInternal(
        provisioning: AuthorizedUltraProfileProvisioning,
        verifiedProfile: VerifiedUltraProfile,
        graph: UltraDisplayGraphSnapshot,
        outputBindings: Collection<UltraThemeOutputSceneBinding>,
        physicalBatch: UiSyncPhysicalOutputBatch?,
    ): UltraThemeLayoutActivationResult {
        UltraRevealTrace.mark(UltraRevealEvent.REVEAL_CTRL_ACTIVATE, "phase" to "START",
            "physical" to (physicalBatch != null))
        return try {
            activateInternalTraced(provisioning, verifiedProfile, graph, outputBindings,
                physicalBatch).also { result ->
                UltraRevealTrace.mark(UltraRevealEvent.REVEAL_CTRL_ACTIVATE,
                    "phase" to "RESULT", "result" to result)
            }
        } catch (failure: Throwable) {
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_CTRL_ACTIVATE, "phase" to "FAIL",
                "error" to failure)
            throw failure
        }
    }

    private fun activateInternalTraced(
        provisioning: AuthorizedUltraProfileProvisioning,
        verifiedProfile: VerifiedUltraProfile,
        graph: UltraDisplayGraphSnapshot,
        outputBindings: Collection<UltraThemeOutputSceneBinding>,
        physicalBatch: UiSyncPhysicalOutputBatch?,
    ): UltraThemeLayoutActivationResult {
        val frozen = ArrayList<UltraThemeOutputSceneBinding>()
        outputBindings.forEach {
            require(frozen.size < 64) { "Too many signed theme output bindings" }
            frozen += it.copy(
                slotIngresses = Collections.unmodifiableList(ArrayList(it.slotIngresses)),
                explicitVideoLayers = Collections.unmodifiableList(ArrayList(it.explicitVideoLayers)),
            )
        }
        val candidate = Activation(provisioning, verifiedProfile, graph,
            Collections.unmodifiableList(frozen), physicalBatch)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        try {
        while (true) {
            when (val admitted = provisioning.tryWithSceneSubmissionUse {
                synchronized(lock) {
                    check(!closed && !failed && !opening && coordinator.isLive) {
                        "Signed theme controller or renderer session is no longer live"
                    }
                    val old = activation
                    require(old == null || (old.provisioning === provisioning &&
                        old.profile === verifiedProfile &&
                        (old.physicalBatch == null) == (physicalBatch == null))
                    ) { "A different AA05/Profile or authority cannot reuse an armed controller" }
                    if (old != null && (old.graph !== graph ||
                        old.physicalBatch !== physicalBatch)) {
                        foregroundScene?.source?.revoke()
                        executor = null
                        activated = false
                    }
                    if (coordinator.graphSnapshot() !== graph) {
                        foregroundScene?.source?.revoke()
                        throw StaleThemeGraphBeforeSubmissionException()
                    }
                    val assignments = provisioning.requireSceneDisplayAssignments(
                        verifiedProfile, coordinator.schemaFingerprint,
                        coordinator.bindingFingerprint, graph,
                    )
                    require(assignments.map { it.uiSyncDisplayId }.toSet() == reducers.keys &&
                        assignments.size == reducers.size
                    ) { "Issued theme catalog differs from the armed display subscriptions" }
                    physicalBatch?.let { requireCurrentPhysicalBatch(it, assignments) }
                    // An idempotent post-bind retry must not replace the Activation object:
                    // already-open Local Canvas leases retain an admission closure for it.
                    val current = old?.takeIf {
                        it.graph === graph && it.physicalBatch === physicalBatch &&
                            it.outputBindings == candidate.outputBindings
                    } ?: candidate
                    if (old != null && current !== old) foregroundScene?.source?.revoke()
                    activation = current
                    check(!dispatching) { "Theme activation cannot reenter a scene submission" }
                    dispatching = true
                    try {
                        val result = activateLocked(current, assignments)
                        drainDeferredLocked()
                        result
                    } finally {
                        dispatching = false
                    }
                }
            }) {
                is UltraProfileSceneUseAttempt.Admitted -> {
                    completePendingMainSemanticCover()
                    return synchronized(lock) {
                        if (failed || pendingMainSemanticCover != null ||
                            (!activated && reducers.keys.any { id ->
                                mainSemanticSuspendedLocked(id) &&
                                candidate.outputBindings.any { binding ->
                                    binding.destination.role == DisplayDestinationRole.MAIN &&
                                        candidate.graph.output(binding.outputId)
                                            ?.uiSyncDisplayId == id
                                }
                            })
                        ) UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE
                        else admitted.value
                    }
                }
                UltraProfileSceneUseAttempt.Busy -> {
                    val remaining = deadline - System.nanoTime()
                    check(remaining > 0L) {
                        "Timed out waiting for signed theme activation AA05 admission"
                    }
                    provisioning.awaitSceneOperationChange(
                        TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1L, 250L),
                    )
                }
                UltraProfileSceneUseAttempt.Revoked -> {
                    synchronized(lock) {
                        failLocked(IllegalStateException(
                            "Signed theme session was revoked before activation",
                        ))
                    }
                    throw IllegalStateException("Signed theme session was revoked before activation")
                }
            }
        }
        } finally {
            if (synchronized(lock) { failed && pendingMainSemanticCover != null })
                completePendingMainSemanticCover()
        }
    }

    private fun requireCurrentPhysicalBatch(
        batch: UiSyncPhysicalOutputBatch,
        assignments: List<CompiledUiSyncDisplayAssignment>,
    ) {
        check(batch.isCurrent) { "Physical UI Sync output batch is not current" }
        require(batch.routes.size == assignments.size && assignments.all { row ->
            batch.routes.count { route ->
                route.assignment.output == row.graphOutput() &&
                    route.assignment.destination.role.name == row.destination.role.name &&
                    route.assignment.destination.instanceId == row.destination.instanceId.value
            } == 1
        }) { "Physical batch routes differ from the signed theme output assignments" }
    }

    /** Caller holds [lock] and an admitted use for this exact provisioning. */
    private fun activateLocked(
        current: Activation,
        assignments: List<CompiledUiSyncDisplayAssignment>,
        reproof: Boolean = false,
    ): UltraThemeLayoutActivationResult {
        if (pendingMainSemanticCover != null)
            return UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE
        if (current.physicalBatch != null && !current.physicalBatch.isCurrent) {
            foregroundScene?.source?.revoke()
            throw IllegalStateException("Physical output changed before theme activation")
        }
        if (coordinator.graphSnapshot() !== current.graph) {
            foregroundScene?.source?.revoke()
            throw StaleThemeGraphBeforeSubmissionException()
        }
        val byDestination = assignments.associateBy { it.destination }
        require(byDestination.size == assignments.size &&
            current.outputBindings.map { it.destination }.toSet() == byDestination.keys &&
            current.outputBindings.size == assignments.size
        ) { "Theme output bindings do not cover the issued display assignments" }
        var independentSignedChoice = false
        queuedLayoutIntents.forEach { (id, commands) ->
            var intentBoundary = false
            commands.forEach { command ->
                if (signedIntents[id] != command) {
                    intentBoundary = true
                    foregroundScene?.takeIf { it.displayId == id }?.source?.revoke()
                }
                if (current.provisioning.selectThemeVariantFor(current.profile, id, command) != null) {
                    signedIntents[id] = command
                    val destinationRole = assignments.singleOrNull {
                        it.uiSyncDisplayId == id
                    }?.destination?.role
                    if (destinationRole != null &&
                        destinationRole != DisplayDestinationRole.MAIN
                    ) independentSignedChoice = true
                }
            }
            val state = checkNotNull(reducers[id]).state()
            signedPaletteSelections[id]?.takeIf { selection ->
                state.lastLayoutChangeCommandRevision?.let {
                    it > selection.eventRevision
                } == true
            }?.let { signedPaletteSelections.remove(id) }
            phoneAppearancePreferences[id]?.takeIf { pending ->
                (state.lastLayoutChangeCommandRevision ?: Long.MIN_VALUE) > pending.eventRevision
            }?.let {
                // The newer signed layout choice lifts the appearance boundary on its own.
                phoneAppearancePreferences.remove(id)
                signedAppearanceSelections.remove(id)
            }
            val signedLast = state.desiredLayoutChange != null &&
                signedIntents[id] == state.desiredLayoutChange
            val layoutRevision = state.lastLayoutChangeCommandRevision
            if (signedLast && layoutRevision != null) {
                if (layoutRevision > (protocolResetRevisionFloors[id] ?: Long.MIN_VALUE) &&
                    protocolResetSuspendedDisplays.remove(id)
                ) foregroundRefreshPendingDisplays += id
                val recoveringUnsupported = unsupportedLayoutSuspendedDisplays.remove(id)
                if (intentBoundary || recoveringUnsupported) {
                    foregroundRefreshPendingDisplays += id
                }
                if (layoutRevision >
                    (latestTransitionStartEventRevision[id] ?: Long.MAX_VALUE)
                ) {
                    transitionSuspendedDisplays.remove(id)
                    transitionRefreshPendingDisplays += id
                }
                if (id !in appearanceTransitionOpen &&
                    layoutRevision > (latestAppearanceEventRevision[id] ?: Long.MAX_VALUE) &&
                    appearanceSuspendedDisplays.remove(id)
                ) foregroundRefreshPendingDisplays += id
                if (layoutRevision > (latestPaletteEventRevision[id] ?: Long.MAX_VALUE) &&
                    paletteSuspendedDisplays.remove(id)
                ) foregroundRefreshPendingDisplays += id
                if (layoutRevision >
                    (latestOpaqueThemeEventRevision[id] ?: Long.MAX_VALUE) &&
                    opaqueThemeSuspendedDisplays.remove(id)
                ) foregroundRefreshPendingDisplays += id
            } else if (state.desiredLayoutChange != null) {
                unsupportedLayoutSuspendedDisplays += id
                foregroundRefreshPendingDisplays.remove(id)
                foregroundScene?.takeIf { it.displayId == id }?.source?.revoke()
            }
        }
        queuedLayoutIntents.clear()
        queuedIntentCount = 0
        // A palette packet received before any signed physical scene cannot authorize the
        // implicit initial layout. Keep that MAIN covered until a later exact phone choice.
        assignments.filter { it.destination.role == DisplayDestinationRole.MAIN }
            .forEach { assignment ->
                val id = assignment.uiSyncDisplayId
                val state = checkNotNull(reducers[id]).state()
                val palette = state.palette ?: return@forEach
                val paletteRevision = latestPaletteEventRevision[id] ?: return@forEach
                if ((state.lastLayoutChangeCommandRevision ?: Long.MIN_VALUE) >
                    paletteRevision) return@forEach
                val expected = signedIntents[id]?.let { command ->
                    CompiledUiSyncThemeIntent(command.layoutID, command.activeComponents,
                        command.paletteID)
                } ?: assignment.primaryIntent
                if (signedPaletteSelections[id] == null && expected != null &&
                    palette.layoutIdentifier == expected.layoutID &&
                    palette.paletteIdentifier != expected.paletteID
                ) paletteSuspendedDisplays += id
            }
        signedPaletteSelections.forEach { (id, selection) ->
            val currentScene = submittedSelections[id]
            val current = currentScene?.intent
            val state = checkNotNull(reducers[id]).state()
            if (current != null && current == selection.previousIssuedIntent &&
                submittedPhysicalBatch === activation?.physicalBatch &&
                state.palette == selection.command &&
                latestPaletteEventRevision[id] == selection.eventRevision &&
                id !in transitionSuspendedDisplays &&
                id !in appearanceSuspendedDisplays &&
                id !in appearanceTransitionOpen &&
                id !in unsupportedLayoutSuspendedDisplays &&
                id !in opaqueThemeSuspendedDisplays
            ) {
                if (paletteSuspendedDisplays.remove(id))
                    foregroundRefreshPendingDisplays += id
            }
        }
        phoneAppearancePreferences.forEach { (id, pending) ->
            val selection = signedAppearanceSelectionLocked(current, id, pending)
            if (selection == null) {
                signedAppearanceSelections.remove(id)
                return@forEach
            }
            signedAppearanceSelections[id] = selection
            if (appearanceSuspendedDisplays.remove(id)) foregroundRefreshPendingDisplays += id
        }
        if (current.physicalBatch != null && assignments.any {
            it.destination.role == DisplayDestinationRole.MAIN &&
                mainSemanticSuspendedLocked(it.uiSyncDisplayId) &&
                !resetReRequestLocked(it.uiSyncDisplayId)
        } && !(independentSignedChoice &&
                submittedPhysicalBatch === current.physicalBatch &&
                assignments.any {
                    it.destination.role == DisplayDestinationRole.MAIN &&
                        latestMainCoverEventRevision[it.uiSyncDisplayId] != null
                })
        ) return UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE
        val state = coordinator.stateSnapshot()
        var waitingForVideo = false
        val selectedIndices = LinkedHashMap<UltraUiSyncDisplayId, Int>()
        val prepared = current.outputBindings.map { binding ->
            val row = checkNotNull(byDestination[binding.destination])
            val paletteSelection = signedPaletteSelections[row.uiSyncDisplayId]
            val selected: CompiledUiSyncSelectedThemeVariant? = if (paletteSelection != null) {
                current.provisioning.selectPaletteVariantFor(
                    current.profile, row.uiSyncDisplayId,
                    paletteSelection.previousIssuedIntent, paletteSelection.command,
                )?.takeIf { it.intent == paletteSelection.selectedIssuedIntent }
            } else signedIntents[row.uiSyncDisplayId]?.let { command ->
                current.provisioning.selectThemeVariantFor(
                    current.profile, row.uiSyncDisplayId, command,
                )
            }
            check(paletteSelection == null || selected != null) {
                "Signed phone palette choice lost its exact issued variant"
            }
            check(signedIntents[row.uiSyncDisplayId] == null || selected != null) {
                "Previously selected signed theme intent lost its issued catalog entry"
            }
            val program = selected?.programSelection ?: row.programSelection
            val slots = selected?.slotIngressAssignments ?: row.slotIngressAssignments
            val localCanvases = selected?.localCanvasAssignments ?: row.localCanvasAssignments
            val references = selected?.instrumentReferenceAssignments ?:
                row.instrumentReferenceAssignments
            val exactIntent = selected?.intent ?: row.primaryIntent
            val signals = selected?.instrumentSignalAssignments ?: if (references.isEmpty()) {
                emptyList()
            } else {
                val issued = current.provisioning.instrumentAssignmentsFor(current.profile)
                references.map { reference ->
                    requireNotNull(issued.singleOrNull {
                        it.profileDisplayIndex == row.profileDisplayIndex &&
                            it.profileDisplayId == row.profileDisplayId &&
                            it.spec.address.slot == reference.slot &&
                            sameInstrumentSignalSpec(it.spec, reference.spec)
                    }) { "Primary reference instrument has no exact issued signal assignment" }
                }
            }
            selectedIndices[row.uiSyncDisplayId] = program.layoutIndex
            val streamBindings = slots.mapNotNull { slot ->
                val ingress: UltraDisplayGraphIngress? = current.graph.ingress(slot.ingressSpec.id)
                if (ingress == null || state.remoteIngresses.none { remote ->
                    remote.ingressId == slot.ingressSpec.id && remote.stream === ingress.stream &&
                        remote.published && remote.phase == AndroidUltraRendererSessionPhase.ACTIVE
                }) {
                    waitingForVideo = true
                    null
                } else {
                    require(slot.ingressSpec.matches(ingress.stream)) {
                        "Published video handle differs from the signed InputStream selector"
                    }
                    UltraThemeSlotIngressBinding(slot.slot, slot.streamIdentifier,
                        slot.ingressSpec.id, ingress.stream)
                }
            }
            PreparedOutput(binding.copy(
                source = binding.source.copy(
                    layoutIndex = program.layoutIndex,
                    state = program.stateSnapshot(),
                    expectedProgramFingerprint = program.structuralFingerprint,
                    signedIntent = exactIntent?.let(::UltraThemeSignedLayoutIntent),
                ),
                slotIngresses = streamBindings,
            ), row, program, localCanvases, references, signals,
                exactIntent,
                if (paletteSelection == null) signedIntents[row.uiSyncDisplayId] else null,
                paletteSelection,
                signedAppearanceSelections[row.uiSyncDisplayId]?.takeIf {
                    row.destination.role == DisplayDestinationRole.MAIN &&
                        it.intent == exactIntent
                })
        }
        if (waitingForVideo) {
            if (reproof) return UltraThemeLayoutActivationResult.PENDING_INPUT_STREAM
            foregroundScene?.source?.revoke()
            selectedLayoutIndices = Collections.unmodifiableMap(selectedIndices)
            executor = null
            activated = false
            pendingInputStream = true
            pendingLocalCanvas = false
            return UltraThemeLayoutActivationResult.PENDING_INPUT_STREAM
        }
        val old = executor
        val sceneKeys = prepared.associate { output ->
            output.assignment.uiSyncDisplayId to SelectedSceneKey(
                output.program, output.intent, output.localCanvases,
                output.instrumentReferences, output.binding.slotIngresses,
            )
        }
        if (reproof && (!activated || old == null ||
            old.selectedLayoutIndices() != selectedIndices ||
            submittedSelections != sceneKeys || submittedGraph !== current.graph ||
            submittedPhysicalBatch !== current.physicalBatch ||
            submittedInputBindings != current.outputBindings
        )) return UltraThemeLayoutActivationResult.ALREADY_ACTIVE
        if (activated && old != null && old.selectedLayoutIndices() == selectedIndices &&
            !reproof &&
            transitionRefreshPendingDisplays.isEmpty() &&
            foregroundRefreshPendingDisplays.isEmpty() &&
            submittedSelections == sceneKeys &&
            submittedGraph === current.graph &&
            submittedPhysicalBatch === current.physicalBatch &&
            submittedInputBindings == current.outputBindings
        ) return UltraThemeLayoutActivationResult.ALREADY_ACTIVE
        // A user-painted second Surface post is useful in the current scene, but its first-post
        // receipt cannot prove the next revision. Keep the proven old scene and signed desired
        // intent pending; never poison an otherwise healthy renderer just for this ambiguity.
        if (current.physicalBatch != null && activeLocalCanvasHandles.isNotEmpty()) {
            val child = localCanvasOwner
            val priorProof = lastLocalFirstFrameReceipt?.takeIf {
                it.revision == lastSubmittedRevision &&
                    submittedGraph === current.graph &&
                    submittedPhysicalBatch === current.physicalBatch
            }
            val reused = prepared.flatMap { output ->
                if (output.localCanvases.isEmpty()) emptyList() else
                    resolveAuthorizedLocalCanvasRasters(
                        current.profile, output.assignment, output.program,
                        output.localCanvases, output.binding.pixelMapper,
                        output.binding.viewportPixels,
                        selectedIntent = output.intent,
                    ).mapNotNull { (row, pixels) ->
                        activeLocalCanvasHandles.singleOrNull { handle ->
                            handle.slot == row.slot &&
                                handle.contentId == UltraContentId(row.contentId) &&
                                handle.sizePixels == pixels
                        }
                    }
            }
            if (reused.any { handle ->
                    priorProof == null || child == null ||
                        priorProof.postSequences[handle.key] !=
                            lastProvenLocalPosts[handle]?.postSequence ||
                        !child.canCarryCandidate(handle, lastProvenLocalPosts[handle])
                }
            ) {
                pendingLocalCanvas = true
                pendingInputStream = false
                return UltraThemeLayoutActivationResult.PENDING_LOCAL_CANVAS
            }
        }
        val previousSelectedIndices = selectedLayoutIndices
        selectedLayoutIndices = Collections.unmodifiableMap(selectedIndices)
        pendingLocalCanvas = false
        val selectedHandles = LinkedHashSet<UltraLocalCanvasSlotOwner.Handle>()
        val selectedInstrument = prepared.flatMap { output ->
            output.instrumentReferences.map { reference -> output to reference }
        }
        require(selectedInstrument.size <= 1) {
            "A Debug reference instrument owns one CLUSTER output only"
        }
        val instrumentHandle = selectedInstrument.singleOrNull()?.let { (output, reference) ->
            val physical = checkNotNull(current.physicalBatch) {
                "Reference instrument requires the exact current physical output batch"
            }
            val signal = requireNotNull(output.instrumentSignals.singleOrNull {
                it.spec.address.slot == reference.slot &&
                    sameInstrumentSignalSpec(it.spec, reference.spec)
            }) { "Reference slot has no exact selected signal assignment" }
            checkNotNull(instrumentOwner) {
                "Static renderer child has no dedicated instrument owner"
            }.ensure(current.provisioning, current.profile, signal, reference, physical,
                output.binding.destination, output.binding.viewportPixels)
        }
        val rebound = prepared.map { output ->
            val handles = if (output.localCanvases.isEmpty()) emptyList() else {
                check(current.physicalBatch != null) {
                    "Local Canvas needs the exact issued physical output batch"
                }
                val owner = checkNotNull(localCanvasOwner) {
                    "Signed Local Canvas has no child-owned Canvas producer"
                }
                val rasters = resolveAuthorizedLocalCanvasRasters(
                    current.profile, output.assignment, output.program, output.localCanvases,
                    output.binding.pixelMapper, output.binding.viewportPixels,
                    selectedIntent = output.intent,
                )
                rasters.map { (row, pixels) ->
                    owner.ensure(row.slot, UltraContentId(row.contentId), pixels) {
                        synchronized(lock) {
                            val live = activation
                            !closed && !failed && live != null &&
                                live.provisioning === current.provisioning &&
                                live.profile === current.profile &&
                                live.physicalBatch === current.physicalBatch &&
                                live.outputBindings == current.outputBindings &&
                                selectedLayoutIndices[output.assignment.uiSyncDisplayId] ==
                                    output.program.layoutIndex &&
                                selectedIntentStillCurrentLocked(
                                    output.assignment, output.intent) &&
                                ((output.intent == null &&
                                    output.binding.source.signedIntent == null) ||
                                    (output.intent != null &&
                                        output.binding.source.signedIntent?.matches(
                                            output.intent) == true)) &&
                                current.provisioning.isLive && coordinator.isLive &&
                                current.physicalBatch.isCurrent &&
                                coordinator.graphSnapshot() === live.graph
                        }
                    }
                }
            }
            selectedHandles.addAll(handles)
            rebindSelectedThemeOutput(output.binding,
                handles.map { it.binding },
                instrumentHandle?.takeIf {
                    it.plan.destination == output.binding.destination
                }?.binding,
            )
        }
        val selected = UltraThemeBoundSceneExecutor.fromAuthorizedProvisioning(
            coordinator, current.provisioning, current.profile, current.graph, rebound,
        )
        try {
            submitLocked(selected, selectedHandles, instrumentHandle, sceneKeys,
                prepared.singleOrNull {
                it.assignment.destination.role == DisplayDestinationRole.MAIN
            }, suppressForegroundRequest = reproof ||
                prepared.any { it.assignment.destination.role == DisplayDestinationRole.MAIN &&
                    (it.signedPhonePalette != null || it.signedPhoneAppearance != null) })
        } catch (_: LocalCanvasTransitionPendingException) {
            selectedLayoutIndices = previousSelectedIndices
            pendingLocalCanvas = true
            pendingInputStream = false
            return UltraThemeLayoutActivationResult.PENDING_LOCAL_CANVAS
        }
        return UltraThemeLayoutActivationResult.SUBMITTED
    }

    private fun onSnapshot(expectedDisplayId: UltraUiSyncDisplayId,
        snapshot: UltraPresentationSnapshot,
    ) {
        synchronized(lock) {
            if (closed || failed) return@synchronized
            if (snapshot.displayId != expectedDisplayId) {
                failLocked(IllegalStateException(
                    "Presentation callback came from another subscribed display",
                ))
                return@synchronized
            }
            runCatching { dispatchSnapshotLocked(snapshot) }.exceptionOrNull()?.let(
                ::handlePresentationFailureLocked,
            )
        }
        completePendingMainSemanticCover()
    }

    /** The old permit was revoked in phase 1; only this off-lock step may wait for Host ACK. */
    private fun completePendingMainSemanticCover() {
        val (ticket, completion) = synchronized(lock) {
            val pending = pendingMainSemanticCover ?: return
            if (mainSemanticCoverInFlight) {
                null to requireNotNull(mainSemanticCoverCompletion)
            } else {
                mainSemanticCoverInFlight = true
                pending to CountDownLatch(1).also {
                    mainSemanticCoverCompletion = it
                }
            }
        }
        if (ticket == null) {
            val acknowledged = runCatching {
                completion.await(5L, TimeUnit.SECONDS)
            }.getOrDefault(false)
            if (!acknowledged) synchronized(lock) {
                if (!failed) failLocked(IllegalStateException(
                    "Exact signed MAIN semantic cover remained in flight",
                ))
            }
            return
        }
        try {
            var acknowledged = false
            for (attempt in 0 until 3) {
                if (runCatching { ticket.coverExactMainUi() }.getOrDefault(false)) {
                    acknowledged = true
                    break
                }
                if (attempt < 2) runCatching { Thread.sleep(20L) }
            }
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_COVER,
                "phase" to if (acknowledged) "ACK" else "NACK")
            val resume = synchronized(lock) {
                mainSemanticCoverInFlight = false
                if (pendingMainSemanticCover !== ticket) return@synchronized null
                if (!acknowledged) {
                    failLocked(IllegalStateException(
                        "Exact signed MAIN semantic cover did not ACK",
                    ))
                    return@synchronized null
                }
                pendingMainSemanticCover = null
                pendingMainSemanticDisplayId = null
                activation?.takeIf { !closed && !failed &&
                    (queuedLayoutIntents.isNotEmpty() || signedPaletteSelections.isNotEmpty() ||
                        phoneAppearancePreferences.isNotEmpty() ||
                        reducers.keys.any(::resetReRequestLocked)) }
            }
            if (resume != null) runCatching {
                activateInternal(resume.provisioning, resume.profile, resume.graph,
                    resume.outputBindings, resume.physicalBatch)
            }.exceptionOrNull()?.let { problem ->
                synchronized(lock) {
                    if (!closed && !failed) handlePresentationFailureLocked(problem)
                }
            }
        } finally {
            completion.countDown()
        }
    }

    /** Reentrant backend/source callbacks cannot overwrite a scene while EGL enqueue is active. */
    private fun dispatchSnapshotLocked(snapshot: UltraPresentationSnapshot) {
        if (dispatching) {
            deferSnapshotLocked(snapshot)
            return
        }
        dispatching = true
        try {
            if (tryAcceptSnapshotLocked(snapshot)) drainDeferredLocked()
        } finally {
            dispatching = false
        }
    }

    private fun deferSnapshotLocked(snapshot: UltraPresentationSnapshot) {
        val previous = deferredSnapshots[snapshot.displayId]
        if (previous == null || snapshot.revision > previous.revision ||
            (snapshot.revision == previous.revision && snapshot.closed)
        ) deferredSnapshots[snapshot.displayId] = snapshot
    }

    /** ARM reduces without AA05. Once active, a competing exact operation defers the replay. */
    private fun tryAcceptSnapshotLocked(snapshot: UltraPresentationSnapshot): Boolean {
        if (snapshot.closed) {
            // Terminal source closure needs no AA05 admission. A Busy parent must not leave
            // the old MAIN visible while waiting to discover a close event it already has.
            failLocked(IllegalStateException("UI Sync presentation source closed"))
            return true
        }
        val current = activation
        if (current == null) {
            acceptSnapshotLocked(snapshot)
            return true
        }
        return when (current.provisioning.tryWithSceneSubmissionUse {
            acceptSnapshotLocked(snapshot)
        }) {
            is UltraProfileSceneUseAttempt.Admitted -> {
                busyRetryDelayMillis = MINIMUM_RETRY_DELAY_MILLIS
                true
            }
            UltraProfileSceneUseAttempt.Busy -> {
                // AA05 admission cannot be waited on while an already-revealed MAIN still
                // carries a now-received opaque semantic event. Revoke before deferring.
                preflightMainSemanticCoverLocked(snapshot, exactCatalogAvailable = false)
                deferSnapshotLocked(snapshot)
                scheduleBusyRetryLocked()
                false
            }
            UltraProfileSceneUseAttempt.Revoked -> {
                failLocked(IllegalStateException("Signed theme session was revoked"))
                true
            }
        }
    }

    private fun drainDeferredLocked() {
        while (!closed && !failed && deferredSnapshots.isNotEmpty()) {
            val first = deferredSnapshots.entries.iterator().next()
            val snapshot = first.value
            deferredSnapshots.remove(first.key)
            if (!tryAcceptSnapshotLocked(snapshot)) return
        }
        if (closed || failed) deferredSnapshots.clear()
    }

    private fun scheduleBusyRetryLocked() {
        if (busyRetryScheduled || closed || failed || activation == null) return
        busyRetryScheduled = true
        val delay = busyRetryDelayMillis
        busyRetryDelayMillis = (busyRetryDelayMillis * 2L).coerceAtMost(MAXIMUM_RETRY_DELAY_MILLIS)
        retrySchedulerLocked().schedule({
            synchronized(lock) {
                busyRetryScheduled = false
                if (closed || failed || deferredSnapshots.isEmpty()) return@synchronized
                if (dispatching) {
                    scheduleBusyRetryLocked()
                    return@synchronized
                }
                dispatching = true
                try {
                    // A phone intent may have changed while a coordinator probe held admission.
                    // Refresh the latest complete replay before rendering any deferred choice.
                    deferredSnapshots.keys.toList().forEach { id ->
                        val latest = coordinator.presentationSnapshot(id.value)
                        check(latest.displayId == id) {
                            "Deferred presentation replay came from another display"
                        }
                        deferSnapshotLocked(latest)
                    }
                    drainDeferredLocked()
                } catch (cause: Throwable) {
                    handlePresentationFailureLocked(cause)
                } finally {
                    dispatching = false
                }
            }
            completePendingMainSemanticCover()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun retrySchedulerLocked(): ScheduledThreadPoolExecutor =
        retryScheduler ?: ScheduledThreadPoolExecutor(1) { command ->
            Thread(command, "xcertplay-ultra-layout-intent").apply { isDaemon = true }
        }.also {
            it.removeOnCancelPolicy = true
            retryScheduler = it
        }

    private fun foregroundExecutorLocked(): ExecutorService =
        foregroundExecutor ?: Executors.newSingleThreadExecutor { command ->
            Thread(command, "xcertplay-ultra-foreground-request").apply {
                isDaemon = true
                foregroundWorkerThread = this
            }
        }.also { foregroundExecutor = it }

    /** A graph replacement before EGL enqueue awaits the exact owner's new-graph activation. */
    private fun handlePresentationFailureLocked(cause: Throwable) {
        if (cause is StaleThemeGraphBeforeSubmissionException) {
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_SWALLOWED,
                "where" to "STALE_GRAPH", "error" to cause)
            foregroundScene?.source?.revoke()
            executor = null
            activated = false
            return
        }
        failLocked(cause)
    }

    /** Caller holds [lock]. BUSY can pre-cover without consulting the unavailable catalog. */
    private fun preflightMainSemanticCoverLocked(
        snapshot: UltraPresentationSnapshot,
        exactCatalogAvailable: Boolean,
    ) {
        val previousRevision = reducers[snapshot.displayId]?.state()?.revision
            ?: throw IllegalArgumentException("Presentation snapshot targets another display")
        fun markBoundary(revision: Long) {
            latestMainCoverEventRevision[snapshot.displayId] = maxOf(
                latestMainCoverEventRevision[snapshot.displayId] ?: Long.MIN_VALUE, revision,
            )
        }
        // A rejected replay can still terminate the entire signed renderer. Cover first;
        // unnecessary black is safer than accepting a semantic event before permit revocation.
        val resetFloor = protocolResetRevisionFloors[snapshot.displayId] ?: 0L
        snapshot.events.asSequence().filter {
            it.revision > maxOf(previousRevision, resetFloor)
        }
            .forEach { event ->
                val command = layoutAuthorityOrCommandLocked(snapshot.displayId, event,
                    (semanticDecoder.decode(event) as?
                        CarPlayUiSyncSemanticDecodeResult.Decoded)?.command)
                if (command is CarPlayUiSyncSemanticCommand.EndLayoutChange.RequestEcho &&
                    redundantAttestedEchoLocked(snapshot.displayId, event, command)
                ) return@forEach
                when (command) {
                    is CarPlayUiSyncSemanticCommand.PaletteChange ->
                        if (suspendMainForPaletteMismatchLocked(snapshot.displayId, command))
                            markBoundary(event.revision)
                    is CarPlayUiSyncSemanticCommand.AppearancePreferenceChange,
                    is CarPlayUiSyncSemanticCommand.AppearanceChangeStart,
                    is CarPlayUiSyncSemanticCommand.AppearanceChangeEnd ->
                        if (suspendMainForAppearanceBoundaryLocked(snapshot.displayId))
                            markBoundary(event.revision)
                    is CarPlayUiSyncSemanticCommand.TransitionStart ->
                        if (suspendSubmittedMainSemanticsLocked(snapshot.displayId)) {
                            transitionSuspendedDisplays += snapshot.displayId
                            latestTransitionStartEventRevision[snapshot.displayId] =
                                event.revision
                            markBoundary(event.revision)
                        }
                    is CarPlayUiSyncSemanticCommand.LayoutChange -> {
                        val current = activation
                        val issued = if (current != null && exactCatalogAvailable)
                            current.provisioning.selectThemeVariantFor(
                                current.profile, snapshot.displayId, command,
                            ) else null
                        val previousIntent = submittedSelections[snapshot.displayId]?.intent
                        val changedOrUnknown = current != null &&
                            (!exactCatalogAvailable || issued == null ||
                                previousIntent != issued.intent)
                        if (changedOrUnknown &&
                            suspendSubmittedMainSemanticsLocked(snapshot.displayId)
                        ) {
                            if (!exactCatalogAvailable || issued == null)
                                unsupportedLayoutSuspendedDisplays += snapshot.displayId
                            // This exact *issued* type-3 may itself select the next scene.
                            // Unknown BUSY choices use the same floor, but are rechecked by
                            // the authorized catalog before any submission or resume.
                            markBoundary(if (issued == null && exactCatalogAvailable)
                                event.revision else event.revision - 1L)
                        }
                    }
                    is CarPlayUiSyncSemanticCommand.DriveModeThemeConfigurationOverrideChange ->
                        if (suspendSubmittedMainSemanticsLocked(snapshot.displayId)) {
                            opaqueThemeSuspendedDisplays += snapshot.displayId
                            markBoundary(event.revision)
                        }
                    is CarPlayUiSyncSemanticCommand.GiveFocus -> Unit
                    else -> if (suspendSubmittedMainSemanticsLocked(snapshot.displayId)) {
                        // Only focus-token delivery is known not to alter MAIN pixels.
                        // TransitionData/End, EndLayoutChange, MetadataTransfer, unknown and
                        // wrong-direction commands all keep the old panel covered.
                        opaqueThemeSuspendedDisplays += snapshot.displayId
                        latestOpaqueThemeEventRevision[snapshot.displayId] = event.revision
                        markBoundary(event.revision)
                    }
                }
            }
    }

    /**
     * Caller holds [lock]. An authorized assetless request echo stands for exactly the type-3
     * [CarPlayUiSyncSemanticCommand.LayoutChange] of the signed intent this controller requested;
     * every other command, including an unauthorized echo, is returned unchanged.
     */
    private fun layoutAuthorityOrCommandLocked(
        displayId: UltraUiSyncDisplayId,
        event: UltraPresentationEventSnapshot,
        command: CarPlayUiSyncSemanticCommand?,
    ): CarPlayUiSyncSemanticCommand? =
        if (command is CarPlayUiSyncSemanticCommand.EndLayoutChange.RequestEcho)
            requestEchoAuthorityLocked(displayId, event)?.layoutChange ?: command
        else command

    /**
     * Caller holds [lock]. The iOS 27 assetless delegate answers requestLayout only with a type-6
     * echo (never type 3). That echo is the phone's layout authority for this display when all of
     * the following hold, and otherwise stays an opaque, covering type 6:
     * - the channel attested it ([UltraPresentationEventSnapshot.echoesAcknowledgedRequestLayout]:
     *   the latest RequestLayout of the current sequence space, its psn acknowledged before the
     *   echo, no Reset in between, data equal in every key and value);
     * - it echoes exactly an [IssuedRequestLayout] this controller recently handed to the port
     *   for this display (the oldest unconsumed one with that data), built from that scene's signed intent (still the submitted MAIN intent), not
     *   yet consumed by another echo and not failed at the port call, in
     *   the same activation and unchanged semantics epoch (no Reset, cover, or opaque boundary
     *   since), after the display's Reset floor, and with no failed local write receipt;
     * - the shared [CarPlayUiSyncRequestEchoLayoutAuthority] admits it (request-echo form only).
     * The resulting type 3 carries that signed intent's layoutID, activeComponents and paletteID.
     */
    private fun requestEchoAuthorityLocked(
        displayId: UltraUiSyncDisplayId,
        event: UltraPresentationEventSnapshot,
    ): CarPlayUiSyncRequestEchoLayoutAuthority? {
        if (!event.echoesAcknowledgedRequestLayout) {
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_ECHO_AUTHORITY,
                "decision" to "DENIED", "reason" to "NOT_ATTESTED_BY_CHANNEL")
            return null
        }
        requestEchoAuthorities[displayId]?.let { decided ->
            if (decided.containsKey(event.revision)) return decided[event.revision]
        }
        val (authority, reason) = decideRequestEchoAuthorityLocked(displayId, event)
        UltraRevealTrace.mark(UltraRevealEvent.REVEAL_ECHO_AUTHORITY,
            "decision" to if (authority != null) "ACCEPTED" else "DENIED", "reason" to reason,
            "layout" to authority?.layoutChange?.layoutID)
        requestEchoAuthorities.getOrPut(displayId, ::LinkedHashMap)[event.revision] = authority
        return authority
    }

    /** Returns the authority, or null with the first failed condition as a fixed reason. */
    private fun decideRequestEchoAuthorityLocked(
        displayId: UltraUiSyncDisplayId,
        event: UltraPresentationEventSnapshot,
    ): Pair<CarPlayUiSyncRequestEchoLayoutAuthority?, String> {
        // Overlapping raw5s: the oldest still-unechoed request of this display with exactly the
        // echoed data answers it (the channel attested FIFO order and its ACK).
        var firstDenial: String? = null
        issuedRequestLayouts.forEach { issued ->
            if (issued.scene.displayId != displayId || issued.echoConsumed) return@forEach
            val (authority, reason) = decideIssuedRequestEchoLocked(displayId, event, issued)
            if (authority != null) return authority to reason
            if (firstDenial == null) firstDenial = reason
        }
        return null to (firstDenial ?: if (issuedRequestLayouts.any {
            it.scene.displayId == displayId
        }) "REQUEST_ALREADY_ECHOED" else "NO_ISSUED_REQUEST")
    }

    private fun decideIssuedRequestEchoLocked(
        displayId: UltraUiSyncDisplayId,
        event: UltraPresentationEventSnapshot,
        issued: IssuedRequestLayout,
    ): Pair<CarPlayUiSyncRequestEchoLayoutAuthority?, String> {
        val scene = issued.scene
        val receiptState = issued.receipt?.state
        // The requested intent must still be the submitted MAIN selection: RequestLayout has no
        // paletteID, so an older identical request can never confirm a different palette.
        val denial = when {
            submittedSelections[displayId]?.intent != scene.intent -> "INTENT_NOT_SUBMITTED"
            activation == null || scene.activation !== activation -> "OTHER_ACTIVATION"
            scene.semanticsEpoch != (foregroundSemanticsEpoch[displayId] ?: 0L) ->
                "SEMANTICS_EPOCH_CHANGED"
            event.revision <= (protocolResetRevisionFloors[displayId] ?: 0L) ->
                "AT_OR_BELOW_RESET_FLOOR"
            event.revision <= checkNotNull(reducers[displayId]).state().revision ->
                "ALREADY_REDUCED"
            receiptState == UiSyncForegroundRequestState.REJECTED ||
                receiptState == UiSyncForegroundRequestState.CANCELLED ||
                receiptState == UiSyncForegroundRequestState.WRITE_UNCERTAIN ->
                "RECEIPT_${receiptState.name}"
            issued.command.layoutID != scene.intent.layoutID ||
                issued.command.activeComponents != scene.intent.activeComponents ->
                "REQUEST_NOT_SIGNED_INTENT"
            else -> null
        }
        if (denial != null) return null to denial
        val authority = CarPlayUiSyncRequestEchoLayoutAuthority.authorize(
            event, issued.command, scene.intent.paletteID,
        ) ?: return null to "ECHO_NOT_EXACT_REQUEST_ECHO"
        issued.echoConsumed = true
        return authority to "EXACT_ACKED_ECHO"
    }

    /**
     * Caller holds [lock]. A channel-attested echo of exactly the RequestLayout for the signed
     * intent that is already this display's current-epoch authority and submitted scene (for
     * example the second of two overlapping identical raw5s) changes nothing. It is a no-op
     * instead of an opaque covering type 6; it never grants or extends any authority.
     */
    private fun redundantAttestedEchoLocked(
        displayId: UltraUiSyncDisplayId,
        event: UltraPresentationEventSnapshot,
        echo: CarPlayUiSyncSemanticCommand.EndLayoutChange.RequestEcho,
    ): Boolean {
        if (!event.echoesAcknowledgedRequestLayout) return false
        val signed = signedIntents[displayId] ?: return false
        if (currentEpochDesiredLayoutLocked(displayId) != signed ||
            signedPaletteSelections[displayId] != null ||
            submittedSelections[displayId]?.intent != CompiledUiSyncThemeIntent(
                signed.layoutID, signed.activeComponents, signed.paletteID,
            )
        ) return false
        val request = CarPlayUiSyncAccessoryCommand.RequestLayout(
            layoutID = signed.layoutID, activeComponents = signed.activeComponents,
        )
        val redundant = runCatching {
            CarPlayUiSyncRequestEchoLayoutAuthority.authorize(event, request, signed.paletteID)
        }.getOrNull()?.let { it.layoutChange == signed && it.echo == echo } == true
        if (redundant) UltraRevealTrace.mark(UltraRevealEvent.REVEAL_ECHO_AUTHORITY,
            "decision" to "NO_OP", "reason" to "ALREADY_APPLIED_SIGNED_INTENT",
            "layout" to signed.layoutID)
        return redundant
    }

    /** Caller holds [lock]. A rejected history never changes the selected scene. */
    private fun acceptSnapshotLocked(snapshot: UltraPresentationSnapshot) {
        if (closed || failed) return
        val reducer = reducers[snapshot.displayId]
            ?: throw IllegalArgumentException("Presentation snapshot targets another display")
        val previousRevision = reducer.state().revision
        preflightMainSemanticCoverLocked(snapshot, exactCatalogAvailable = true)
        // Only revisions this exact replay newly carries; a later one decided by a BUSY
        // preflight of a newer replay stays decided until that replay is reduced.
        val echoAuthorities = requestEchoAuthorities[snapshot.displayId]?.values
            ?.filterNotNull()?.filter {
                it.eventRevision > previousRevision && it.eventRevision <= snapshot.revision
            }.orEmpty()
        val reduced = reducer.reduce(snapshot, echoAuthorities)
        requestEchoAuthorities[snapshot.displayId]?.let { decided ->
            val applied = reducer.state().revision
            decided.keys.removeAll { it <= applied }
            if (decided.isEmpty()) requestEchoAuthorities.remove(snapshot.displayId)
        }
        when (val result = reduced) {
            is CarPlayUiSyncSemanticReduceResult.Unchanged -> Unit
            is CarPlayUiSyncSemanticReduceResult.Rejected -> failLocked(
                IllegalStateException("UI Sync semantic history was rejected: ${result.failure}"),
            )
            is CarPlayUiSyncSemanticReduceResult.Applied -> {
                if (result.state.closed) {
                    failLocked(IllegalStateException("UI Sync presentation source closed"))
                    return
                }
                val commands = ArrayList<CarPlayUiSyncSemanticCommand.LayoutChange>()
                var paletteRecovered = false
                var paletteChoiceQueued = false
                var appearanceChoiceQueued = false
                val resetFloor = protocolResetRevisionFloors[snapshot.displayId] ?: 0L
                snapshot.events.asSequence().filter {
                    it.revision > maxOf(previousRevision, resetFloor)
                }
                    .forEach { event ->
                        val decoded = semanticDecoder.decode(event)
                        check(decoded is CarPlayUiSyncSemanticDecodeResult.Decoded) {
                            "Accepted UI Sync history could not be decoded exactly"
                        }
                        // Exactly the authorities the reducer just applied; never re-decided here.
                        val command = (decoded.command as?
                            CarPlayUiSyncSemanticCommand.EndLayoutChange.RequestEcho)?.let {
                            echoAuthorities.singleOrNull { authority ->
                                authority.eventRevision == event.revision
                            }?.layoutChange
                        } ?: decoded.command
                        when (command) {
                            is CarPlayUiSyncSemanticCommand.LayoutChange -> commands += command
                            is CarPlayUiSyncSemanticCommand.TransitionStart -> {
                                val physicalMainCovered =
                                    suspendSubmittedMainSemanticsLocked(snapshot.displayId)
                                latestTransitionStartEventRevision[snapshot.displayId] =
                                    event.revision
                                transitionSuspendedDisplays += snapshot.displayId
                                transitionRefreshPendingDisplays.remove(snapshot.displayId)
                                // A received type-1 must invalidate the old raw5 source even
                                // when its region/timing fields are still opaque. Keep the
                                // other physical outputs' submitted scene and proof intact.
                                if (!physicalMainCovered) {
                                    foregroundSemanticsEpoch[snapshot.displayId] = Math.addExact(
                                        foregroundSemanticsEpoch[snapshot.displayId] ?: 0L, 1L)
                                    foregroundScene?.takeIf {
                                        it.displayId == snapshot.displayId
                                    }?.source?.revoke()
                                }
                            }
                            is CarPlayUiSyncSemanticCommand.TransitionEnd -> {
                                // Type-4 is not an Android presentation receipt. Only a later
                                // exact signed type-3 and a fresh EGL scene can restore raw5.
                            }
                            is CarPlayUiSyncSemanticCommand.PaletteChange -> {
                                latestPaletteEventRevision[snapshot.displayId] = event.revision
                                val covered = suspendMainForPaletteMismatchLocked(
                                        snapshot.displayId, command,
                                    )
                                if (selectSignedStandalonePaletteLocked(
                                        snapshot.displayId, command, event.revision,
                                    )) paletteChoiceQueued = true
                                if (!covered && !paletteChoiceQueued) {
                                    val active = foregroundScene?.takeIf {
                                        it.displayId == snapshot.displayId &&
                                            it.intent.layoutID == command.layoutIdentifier
                                    }
                                    if (active != null &&
                                        active.intent.paletteID != command.paletteIdentifier
                                    ) {
                                        paletteSuspendedDisplays += snapshot.displayId
                                        suspendForegroundDisplayLocked(snapshot.displayId)
                                    } else if (active != null &&
                                        activation?.physicalBatch == null &&
                                        paletteSuspendedDisplays.remove(snapshot.displayId)
                                    ) {
                                        activated = false
                                        executor = null
                                        paletteRecovered = true
                                    }
                                }
                            }
                            is CarPlayUiSyncSemanticCommand.AppearancePreferenceChange -> {
                                latestAppearanceEventRevision[snapshot.displayId] = event.revision
                                appearanceSuspendedDisplays += snapshot.displayId
                                if (!suspendMainForAppearanceBoundaryLocked(
                                        snapshot.displayId,
                                    )
                                ) suspendForegroundDisplayLocked(snapshot.displayId)
                                // Covered now; activation alone may re-prove the same signed
                                // scene for it (see signedAppearanceSelectionLocked).
                                signedAppearanceSelections.remove(snapshot.displayId)
                                if (isActivatedMainDisplayLocked(snapshot.displayId)) {
                                    phoneAppearancePreferences[snapshot.displayId] =
                                        PendingAppearancePreference(command, event.revision)
                                    appearanceChoiceQueued = true
                                } else phoneAppearancePreferences.remove(snapshot.displayId)
                            }
                            is CarPlayUiSyncSemanticCommand.AppearanceChangeStart -> {
                                appearanceTransitionOpen[snapshot.displayId] =
                                    command.targetAppearance
                                latestAppearanceTransitionEventRevision[snapshot.displayId] =
                                    event.revision
                                phoneAppearancePreferences.remove(snapshot.displayId)
                                signedAppearanceSelections.remove(snapshot.displayId)
                                latestAppearanceEventRevision[snapshot.displayId] = event.revision
                                appearanceSuspendedDisplays += snapshot.displayId
                                if (!suspendMainForAppearanceBoundaryLocked(
                                        snapshot.displayId,
                                    )
                                ) suspendForegroundDisplayLocked(snapshot.displayId)
                            }
                            is CarPlayUiSyncSemanticCommand.AppearanceChangeEnd -> {
                                if (appearanceTransitionOpen[snapshot.displayId] ==
                                    command.targetAppearance
                                ) appearanceTransitionOpen.remove(snapshot.displayId)
                                latestAppearanceTransitionEventRevision[snapshot.displayId] =
                                    event.revision
                                phoneAppearancePreferences.remove(snapshot.displayId)
                                signedAppearanceSelections.remove(snapshot.displayId)
                                latestAppearanceEventRevision[snapshot.displayId] = event.revision
                                appearanceSuspendedDisplays += snapshot.displayId
                                if (!suspendMainForAppearanceBoundaryLocked(
                                        snapshot.displayId,
                                    )
                                ) suspendForegroundDisplayLocked(snapshot.displayId)
                            }
                            is CarPlayUiSyncSemanticCommand.DriveModeThemeConfigurationOverrideChange -> {
                                latestOpaqueThemeEventRevision[snapshot.displayId] =
                                    event.revision
                                opaqueThemeSuspendedDisplays += snapshot.displayId
                                if (!suspendSubmittedMainSemanticsLocked(snapshot.displayId))
                                    suspendForegroundDisplayLocked(snapshot.displayId)
                            }
                            else -> Unit
                        }
                    }
                if (commands.isEmpty() && !paletteRecovered && !paletteChoiceQueued &&
                    !appearanceChoiceQueued) return
                if (commands.size > MAXIMUM_QUEUED_LAYOUT_INTENTS - queuedIntentCount) {
                    failLocked(IllegalStateException("Bounded signed theme intent history overflow"))
                    return
                }
                if (commands.isNotEmpty()) {
                    queuedLayoutIntents.getOrPut(snapshot.displayId, ::ArrayList)
                        .addAll(commands)
                    queuedIntentCount += commands.size
                }
                if (pendingMainSemanticCover != null) return
                val current = activation ?: return // ARM never constructs a graph-bound executor.
                if (coordinator.graphSnapshot() !== current.graph ||
                    (current.physicalBatch != null && !current.physicalBatch.isCurrent)
                ) {
                    foregroundScene?.source?.revoke()
                    // A fresh physical batch/graph must be supplied by the owner. Never submit
                    // the old executor against a replacement graph or revoked target.
                    executor = null
                    activated = false
                    return
                }
                activateLocked(current, current.provisioning.requireSceneDisplayAssignments(
                    current.profile, coordinator.schemaFingerprint,
                    coordinator.bindingFingerprint, current.graph,
                ))
            }
        }
    }

    /** A standalone type-2 can invalidate a visible MAIN even with no raw5 foreground scene. */
    private fun suspendMainForPaletteMismatchLocked(
        displayId: UltraUiSyncDisplayId,
        command: CarPlayUiSyncSemanticCommand.PaletteChange,
    ): Boolean {
        val previous = submittedSelections[displayId]?.intent
        if (previous != null &&
            (previous.layoutID != command.layoutIdentifier ||
                previous.paletteID == command.paletteIdentifier)
        ) return false
        // A null signed intent is not a known matching palette. Once a physical MAIN was
        // submitted, type-16 must conservatively cover that opaque initial scene.
        if (!suspendSubmittedMainSemanticsLocked(displayId)) return false
        paletteSuspendedDisplays += displayId
        return true
    }

    /**
     * Type-16 changes only the palette of an already-issued, already-submitted MAIN scene. Its
     * layout and active components come from that exact scene, never from a fabricated type-3.
     * An unknown or competing command leaves the physical output covered.
     */
    private fun selectSignedStandalonePaletteLocked(
        displayId: UltraUiSyncDisplayId,
        command: CarPlayUiSyncSemanticCommand.PaletteChange,
        eventRevision: Long,
    ): Boolean {
        val current = activation ?: return false
        val previous = submittedSelections[displayId]?.intent ?: return false
        if (command.layoutIdentifier != previous.layoutID) return false
        val isCurrentMain = current.physicalBatch?.isCurrent == true &&
            submittedPhysicalBatch === current.physicalBatch &&
            submittedGraph === current.graph &&
            current.outputBindings.any { binding ->
                binding.destination.role == DisplayDestinationRole.MAIN &&
                    current.graph.output(binding.outputId)?.uiSyncDisplayId == displayId
            }
        if (!isCurrentMain) return false
        val state = checkNotNull(reducers[displayId]).state()
        val competingVisualChoice = state.palette != command ||
            state.desiredLayoutChange != signedIntents[displayId] ||
            queuedLayoutIntents[displayId]?.isNotEmpty() == true ||
            displayId in transitionSuspendedDisplays ||
            displayId in appearanceSuspendedDisplays ||
            displayId in appearanceTransitionOpen ||
            displayId in protocolResetSuspendedDisplays ||
            displayId in unsupportedLayoutSuspendedDisplays ||
            displayId in opaqueThemeSuspendedDisplays
        if (competingVisualChoice ||
            (previous.paletteID != command.paletteIdentifier &&
                pendingMainSemanticCover == null)
        ) {
            signedPaletteSelections.remove(displayId)
            paletteSuspendedDisplays += displayId
            return false
        }
        if (previous.paletteID == command.paletteIdentifier &&
            displayId !in paletteSuspendedDisplays
        ) return false
        val issued = current.provisioning.selectPaletteVariantFor(
            current.profile, displayId, previous, command,
        )
        if (issued == null) {
            signedPaletteSelections.remove(displayId)
            paletteSuspendedDisplays += displayId
            return false
        }
        signedPaletteSelections[displayId] = PhonePaletteSelection(
            command, eventRevision, previous, issued.intent,
        )
        return true
    }

    /** An opaque appearance boundary cannot keep showing old signed MAIN pixels. */
    private fun suspendMainForAppearanceBoundaryLocked(
        displayId: UltraUiSyncDisplayId,
    ): Boolean {
        if (!suspendSubmittedMainSemanticsLocked(displayId)) return false
        appearanceSuspendedDisplays += displayId
        return true
    }

    /** Phase 1 has no Host/View wait; the exact ticket is completed only after this lock exits. */
    private fun suspendSubmittedMainSemanticsLocked(
        displayId: UltraUiSyncDisplayId,
    ): Boolean {
        val current = activation ?: return false
        val batch = submittedPhysicalBatch ?: return false
        if (current.physicalBatch !== batch || submittedGraph !== current.graph ||
            submittedSelections[displayId] == null ||
            current.outputBindings.none { binding ->
                binding.destination.role == DisplayDestinationRole.MAIN &&
                    current.graph.output(binding.outputId)?.uiSyncDisplayId == displayId
            }
        ) return false
        val revision = lastSubmittedRevision ?: return false
        val firstBoundary = pendingMainSemanticCover == null
        if (firstBoundary) {
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_COVER, "phase" to "BEGIN",
                "revision" to revision)
            pendingMainSemanticCover = requireNotNull(beginMainSemanticCover?.invoke(
                batch, displayId, revision,
            )) { "Signed MAIN palette boundary has no exact physical cover authority" }
            pendingMainSemanticDisplayId = displayId
        } else {
            check(pendingMainSemanticDisplayId == displayId) {
                "A different MAIN semantic cover is still pending"
            }
        }
        foregroundRefreshPendingDisplays += displayId
        if (firstBoundary) foregroundSemanticsEpoch[displayId] = Math.addExact(
            foregroundSemanticsEpoch[displayId] ?: 0L, 1L,
        )
        foregroundScene?.takeIf { it.displayId == displayId }?.source?.revoke()
        activated = false
        executor = null
        return true
    }

    private fun suspendForegroundDisplayLocked(displayId: UltraUiSyncDisplayId) {
        if (foregroundScene?.displayId != displayId) return
        foregroundSemanticsEpoch[displayId] = Math.addExact(
            foregroundSemanticsEpoch[displayId] ?: 0L, 1L)
        foregroundScene?.source?.revoke()
        activated = false
        executor = null
    }

    /** Caller holds [lock]. The lease admission extends through the exact EGL enqueue. */
    private fun submitLocked(
        selected: UltraThemeBoundSceneExecutor,
        retainedLocalCanvases: Set<UltraLocalCanvasSlotOwner.Handle>,
        retainedInstrument: UltraInstrumentReferenceSlotOwner.Handle?,
        selectionKeys: Map<UltraUiSyncDisplayId, SelectedSceneKey>,
        main: PreparedOutput?,
        suppressForegroundRequest: Boolean = false,
    ) {
        val current = checkNotNull(activation)
        check(!closed && !failed && current.provisioning.isLive && coordinator.isLive &&
            (current.physicalBatch == null || current.physicalBatch.isCurrent)
        ) {
            foregroundScene?.source?.revoke()
            "Signed theme session was revoked before scene submission"
        }
        if (coordinator.graphSnapshot() !== current.graph) {
            foregroundScene?.source?.revoke()
            throw StaleThemeGraphBeforeSubmissionException()
        }
        val coordinatorRevision = coordinator.stateSnapshot().lastSubmittedRenderRevision
        // The MAIN physical permit and cover-generation floor reserve revision zero as
        // "no signed scene". Start the first signed submission at one without changing the
        // coordinator's own pre-submission sentinel (-1) or any later monotonic ordering.
        val maximum = maxOf(0L, coordinatorRevision, lastSubmittedRevision ?: -1L)
        check(maximum < Long.MAX_VALUE) { "Signed theme scene revision exhausted" }
        val firstFrames = retainedLocalCanvases.map { handle ->
            handle to localCanvasPainter?.invoke(handle.contentId)
        }
        val revision = maximum + 1L
        val owner = localCanvasOwner
        val priorProof = lastLocalFirstFrameReceipt?.takeIf {
            it.revision == lastSubmittedRevision &&
                submittedGraph === current.graph &&
                submittedPhysicalBatch === current.physicalBatch &&
                current.physicalBatch?.isCurrent == true
        }
        val priorCustomPosts = if (priorProof != null) lastProvenCustomLocalPosts else emptyMap()
        val carryLeases = ArrayList<UltraLocalCanvasCarryLease>()
        try {
            if (retainedLocalCanvases.isNotEmpty()) {
                val child = checkNotNull(owner)
                retainedLocalCanvases.forEach { handle ->
                    val oldPost = lastProvenLocalPosts[handle]?.takeIf {
                        handle in activeLocalCanvasHandles && priorProof != null &&
                            priorProof.postSequences[handle.key] == it.postSequence
                    }
                    if (oldPost == null) {
                        if (!child.isFresh(handle)) {
                            if (handle in activeLocalCanvasHandles) {
                                throw LocalCanvasTransitionPendingException(
                                    "Previously active Local Canvas has no exact carry proof",
                                )
                            }
                            throw IllegalStateException(
                                "New Local Canvas ingress is not fresh before scene submission",
                            )
                        }
                    } else {
                        carryLeases += child.acquireCarryLease(handle, oldPost)
                            ?: throw LocalCanvasTransitionPendingException(
                                "Local Canvas carry source changed or has pending producer work",
                            )
                    }
                }
            }
        } catch (problem: Throwable) {
            carryLeases.asReversed().forEach { it.close() }
            throw problem
        }
        if (!diagnosticFallbackEnabled && firstFrames.any { (handle, painter) ->
                handle !in carryLeases.map { it.receipt.handle } && painter == null
            }
        ) {
            carryLeases.asReversed().forEach { it.close() }
            throw IllegalStateException(
                "Signed Local Canvas has no registered painter; refusing non-diagnostic scene",
            )
        }
        val submission = try {
            instrumentOwner?.markSceneReplacing(revision)
            selected.submit(revision)
        } catch (problem: Throwable) {
            carryLeases.asReversed().forEach { it.close() }
            throw problem
        }
        if (current.physicalBatch != null && main != null &&
            (main.signedPhoneCommand != null || main.signedPhonePalette != null ||
                main.signedPhoneAppearance != null) &&
            beginMainSemanticCover != null
        ) {
            val id = main.assignment.uiSyncDisplayId
            val latestBoundary = latestMainCoverEventRevision[id]
            val semanticState = checkNotNull(reducers[id]).state()
            val freshPhoneChoice = when (val palette = main.signedPhonePalette) {
                null -> latestBoundary == null ||
                    (semanticState.lastLayoutChangeCommandRevision?.let {
                        it > latestBoundary
                    } == true && signedIntents[id] == semanticState.desiredLayoutChange)
                else -> palette === signedPaletteSelections[id] &&
                    semanticState.palette == palette.command &&
                    latestPaletteEventRevision[id] == palette.eventRevision &&
                    (latestBoundary == null || palette.eventRevision >= latestBoundary) &&
                    id !in appearanceTransitionOpen &&
                    !mainSemanticSuspendedLocked(id)
            } || main.signedPhoneAppearance?.let { appearance ->
                appearance === signedAppearanceSelections[id] &&
                    appearance.intent == main.intent &&
                    semanticState.lastAppearancePreferenceChange == appearance.command &&
                    latestAppearanceEventRevision[id] == appearance.eventRevision &&
                    (latestBoundary == null || appearance.eventRevision >= latestBoundary) &&
                    id !in appearanceTransitionOpen &&
                    !mainSemanticSuspendedLocked(id)
            } == true
            if (UltraRevealTrace.enabled) UltraRevealTrace.mark(UltraRevealEvent.REVEAL_SCENE_SUBMITTED,
                "revision" to revision, "layout" to main.intent?.layoutID,
                "choice" to when {
                    main.signedPhoneAppearance != null -> "APPEARANCE"
                    main.signedPhonePalette != null -> "PALETTE"
                    checkNotNull(reducers[id]).state().desiredLayoutChangeFromRequestEcho ->
                        "ECHO"
                    else -> "TYPE3"
                }, "freshChoice" to freshPhoneChoice, "foreground" to !suppressForegroundRequest)
            if (freshPhoneChoice) {
                check(resumeMainAfterSignedPhoneVisualChoice?.invoke(
                    current.physicalBatch, id, revision,
                ) == true) {
                    "Fresh signed MAIN phone visual choice did not lift its exact semantic cover"
                }
            }
        } else UltraRevealTrace.mark(UltraRevealEvent.REVEAL_SCENE_SUBMITTED,
            "revision" to revision, "layout" to main?.intent?.layoutID, "choice" to "NONE",
            "physical" to (current.physicalBatch != null),
            "foreground" to !suppressForegroundRequest)
        // Ticket completion can wait on EGL. Never await it under this controller lock or the
        // admitted AA05 scene use; the child owner observes swap/finish asynchronously.
        retainedInstrument?.let {
            checkNotNull(instrumentOwner).observeFirstFrame(it, submission.ticket)
        }
        instrumentOwner?.retireExceptAfterPresented(retainedInstrument, submission.ticket)
        // Only a successful new enqueue supersedes the old revision's producer freeze.
        foregroundScene?.source?.revoke()
        foregroundScene = null
        if (currentCarryLeases.isNotEmpty()) releaseCarryLeasesLocked()
        // The ticket is intentionally not awaited or interpreted as a display/transition ACK.
        val localGuards = if (current.physicalBatch != null) retainedLocalCanvases.map { handle ->
            AndroidUltraLocalIngressExpectation(handle.lease, handle.contentId, handle.sizePixels)
        } else emptyList()
        var firstFramePreparationFailure: Throwable? = null
        val firstFrameExpectation: UltraEglLocalFirstFrameExpectation? =
            if (localGuards.isEmpty()) null else try {
                coordinator.prepareLocalFirstFrameFence(
                    revision, current.graph, localGuards,
                    priorProof?.takeIf { carryLeases.isNotEmpty() },
                    carryLeases.mapTo(LinkedHashSet()) { it.receipt.handle.key },
                )
            } catch (problem: Throwable) {
                firstFramePreparationFailure = problem
                null
            }
        executor = selected
        lastSubmittedRevision = revision
        lastLocalFirstFrameReceipt = null
        lastProvenLocalPosts = emptyMap()
        lastProvenCustomLocalPosts = emptyMap()
        currentCarryLeases = carryLeases
        val hadLocalCanvases = activeLocalCanvasHandles.isNotEmpty()
        activeLocalCanvasHandles = Collections.unmodifiableSet(
            LinkedHashSet(retainedLocalCanvases),
        )
        activated = true
        pendingInputStream = false
        submittedGraph = current.graph
        submittedInputBindings = current.outputBindings
        submittedSelections = Collections.unmodifiableMap(LinkedHashMap(selectionKeys))
        submittedPhysicalBatch = current.physicalBatch
        val notInstalled = if (suppressForegroundRequest) "SUPPRESSED"
            else installForegroundSceneLocked(current, submission, main)
        UltraRevealTrace.mark(UltraRevealEvent.REVEAL_RAW5_SCENE,
            "phase" to if (notInstalled == null) "INSTALLED" else "NOT_INSTALLED",
            "reason" to notInstalled, "revision" to revision)
        // The replacement has a new exact scene revision. A Local Canvas still cannot issue
        // raw5 until its own post plus EGL draw/swap/finish receipt for this revision arrives.
        transitionRefreshPendingDisplays.clear()
        foregroundRefreshPendingDisplays.clear()
        observePhysicalPresentationLocked(current, submission)
        if (localGuards.isNotEmpty()) {
            val timeoutFailure = firstFramePreparationFailure
            val scheduled = runCatching {
                retrySchedulerLocked().schedule({
                    synchronized(lock) {
                        if (!closed && !failed && lastSubmittedRevision == revision &&
                            activation === current &&
                            lastLocalFirstFrameReceipt?.revision != revision &&
                            current.provisioning.isLive && coordinator.isLive &&
                            coordinator.graphSnapshot() === current.graph &&
                            current.physicalBatch?.isCurrent == true
                        ) failLocked(IllegalStateException(
                            "Signed Local Canvas first frame was not proven by EGL within " +
                                "$LOCAL_FIRST_FRAME_TIMEOUT_MILLIS ms",
                            timeoutFailure,
                        ))
                    }
                }, LOCAL_FIRST_FRAME_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
            scheduled.exceptionOrNull()?.let { cause ->
                failLocked(IllegalStateException(
                    "Could not schedule the signed Local Canvas first-frame deadline", cause,
                ))
            }
        }
        owner?.takeIf { hadLocalCanvases || retainedLocalCanvases.isNotEmpty() }
            ?.let { owner ->
            val retained = activeLocalCanvasHandles
            val postReceipts = LinkedHashMap<UltraLocalCanvasSlotOwner.Handle,
                UltraLocalCanvasPostReceipt>()
            carryLeases.forEach { postReceipts[it.receipt.handle] = it.receipt }
            val carriedHandles = carryLeases.mapTo(LinkedHashSet()) { it.receipt.handle }
            val customPainterHandles = firstFrames.filter { it.second != null }
                .mapTo(LinkedHashSet()) { it.first }
            fun stillCurrent(): Boolean = synchronized(lock) {
                !closed && !failed && lastSubmittedRevision == revision &&
                    activeLocalCanvasHandles == retained && activation === current &&
                    current.provisioning.isLive && coordinator.isLive &&
                    coordinator.graphSnapshot() === current.graph &&
                    (current.physicalBatch == null || current.physicalBatch.isCurrent)
            }
            fun retireAfterPaint() {
                owner.retireExceptAfterPresented(retained, submission.ticket) {
                    stillCurrent()
                }.whenComplete { presented, failure ->
                    synchronized(lock) {
                        if (!closed && !failed && lastSubmittedRevision == revision &&
                            (failure != null || presented != true)
                        ) failLocked(IllegalStateException(
                            "Signed Local Canvas replacement was not presented by EGL", failure,
                        ))
                    }
                }
            }
            fun proveLocalFirstFrame() {
                val prepared = firstFrameExpectation ?: return
                if (postReceipts.size != retained.size) return
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    LOCAL_FIRST_FRAME_TIMEOUT_MILLIS)
                val sequences = postReceipts.values.associate { receipt ->
                    UltraEglIngressKey.Local(receipt.handle.contentId) to receipt.postSequence
                }
                fun attempt(delayMillis: Long) {
                    val scheduler = synchronized(lock) {
                        if (!stillCurrent()) return
                        retrySchedulerLocked()
                    }
                    runCatching {
                        scheduler.schedule({
                            if (!stillCurrent() || carryLeases.any { !it.isCurrent() } ||
                                postReceipts.any { (handle, receipt) ->
                                    handle !in carriedHandles && !owner.acceptsPostReceipt(receipt)
                                }
                            ) return@schedule
                            val receipt = runCatching {
                                coordinator.tryPresentLocalFirstFrame(
                                    prepared, current.graph, localGuards, sequences,
                                )
                            }.getOrNull()
                            if (receipt != null && System.nanoTime() <= deadline) {
                                val accepted = synchronized(lock) {
                                    if (!stillCurrent() || carryLeases.any { !it.isCurrent() } ||
                                        postReceipts.any { (handle, post) ->
                                            handle !in carriedHandles &&
                                                !owner.acceptsPostReceipt(post)
                                        }) false else {
                                        lastLocalFirstFrameReceipt = receipt
                                        UltraRevealTrace.mark(
                                            UltraRevealEvent.REVEAL_LOCAL_CANVAS,
                                            "phase" to "FIRST_FRAME", "revision" to revision,
                                            "carried" to carriedHandles.size,
                                            "posts" to postReceipts.size)
                                        lastProvenLocalPosts = Collections.unmodifiableMap(
                                            LinkedHashMap(postReceipts),
                                        )
                                        lastProvenCustomLocalPosts = Collections.unmodifiableMap(
                                            postReceipts.filter { (handle, post) ->
                                                if (handle in carriedHandles)
                                                    priorCustomPosts[handle] === post
                                                else handle in customPainterHandles
                                            },
                                        )
                                        releaseCarryLeasesLocked()
                                        foregroundScene?.takeIf { it.revision == revision }?.let {
                                            if (localMainReceiptProvesScene(it, receipt)) {
                                                UltraRevealTrace.mark(
                                                    UltraRevealEvent.REVEAL_RAW5_SCENE,
                                                    "phase" to "PRESENTED",
                                                    "revision" to revision)
                                                it.presented = true
                                                scheduleForegroundRequestLocked(it)
                                            }
                                        }
                                        true
                                    }
                                }
                                if (accepted) retireAfterPaint()
                            } else if (System.nanoTime() < deadline) {
                                attempt(MINIMUM_RETRY_DELAY_MILLIS)
                            } else UltraRevealTrace.mark(UltraRevealEvent.REVEAL_LOCAL_CANVAS,
                                "phase" to "FIRST_FRAME_TIMEOUT", "revision" to revision)
                        }, delayMillis, TimeUnit.MILLISECONDS)
                    }
                }
                attempt(0L)
            }
            // The owner has a bounded queue. Fresh sources post one complete frame; carried
            // sources stay frozen with no new post. Both are redrawn and swapped by EGL.
            fun postNext(pending: Iterator<Pair<UltraLocalCanvasSlotOwner.Handle,
                ((Canvas, PixelSize) -> Unit)?>>) {
                if (!pending.hasNext()) {
                    if (retained.isEmpty() || current.physicalBatch == null) retireAfterPaint()
                    else proveLocalFirstFrame()
                    return
                }
                val (handle, painter) = pending.next()
                // The callback itself is invoked only by the child-owned Canvas producer after
                // this stack unwinds; the lookup was frozen before the EGL scene was submitted.
                val posted = if (painter == null) owner.drawBenchFrameWithPostReceipt(handle)
                    else owner.drawWithPostReceipt(handle, painter)
                UltraRevealTrace.mark(UltraRevealEvent.REVEAL_LOCAL_CANVAS, "phase" to "POST",
                    "painter" to if (painter == null) "DIAGNOSTIC" else "CUSTOM",
                    "revision" to revision)
                posted.whenComplete { receipt, failure ->
                    if (failure != null) {
                        UltraRevealTrace.mark(UltraRevealEvent.REVEAL_LOCAL_CANVAS,
                            "phase" to "POST_FAILED", "revision" to revision, "error" to failure)
                        synchronized(lock) {
                            if (!closed && !failed && lastSubmittedRevision == revision) {
                                failLocked(IllegalStateException(
                                    "Signed Local Canvas could not post its initial frame",
                                    failure,
                                ))
                            }
                        }
                    } else {
                        if (receipt != null) postReceipts[handle] = receipt
                        postNext(pending)
                    }
                }
            }
            postNext(firstFrames.filterNot { it.first in carriedHandles }.iterator())
        }
    }

    /** Ticket and local ingress fences are observed outside the scene submission lock. */
    private fun observePhysicalPresentationLocked(
        current: Activation,
        submission: UltraThemeBoundSceneSubmission,
    ) {
        val batch = current.physicalBatch ?: return
        val scenes = submission.snapshot.scenes.filter {
            when (it.destination.role) {
                DisplayDestinationRole.MAIN -> onMainPresented != null
                DisplayDestinationRole.CLUSTER -> onClusterPresented != null
                else -> false
            }
        }
        if (scenes.isEmpty()) return
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
            LOCAL_FIRST_FRAME_TIMEOUT_MILLIS)
        scenes.forEach { scene ->
            val localDraws = scene.layers.mapIndexedNotNull { index, layer ->
                val source = layer.source as? UltraSceneSource.Local ?: return@mapIndexedNotNull null
                UltraEglLocalLayerDraw(
                    scene.destination, UltraEglIngressKey.Local(source.contentId), index, layer,
                )
            }.toSet()
            fun poll(delayMillis: Long) {
                runCatching { retrySchedulerLocked().schedule({
                    val stillCurrent = synchronized(lock) {
                        !closed && !failed && activated && activation === current &&
                            submittedGraph === current.graph && submittedPhysicalBatch === batch &&
                            lastSubmittedRevision == submission.snapshot.revision &&
                            coordinator.isLive && coordinator.graphSnapshot() === current.graph &&
                            batch.isCurrent
                    }
                    if (!stillCurrent) {
                        UltraRevealTrace.mark(UltraRevealEvent.REVEAL_SCENE_PRESENTED,
                            "role" to scene.destination.role, "disposition" to "SUPERSEDED",
                            "revision" to submission.snapshot.revision)
                        return@schedule
                    }
                    val ticketResult = runCatching { submission.ticket.await(0L) }.getOrNull()
                    val localReceipt = synchronized(lock) {
                        lastLocalFirstFrameReceipt?.takeIf {
                            it.revision == submission.snapshot.revision &&
                                it.graphRevision == current.graph.revision &&
                                it.physicalBatchGeneration == batch.generation &&
                                it.requiredDraws.containsAll(localDraws)
                        }
                    }
                    if (ticketResult?.revision == submission.snapshot.revision &&
                        ticketResult.disposition == UltraEglRenderDisposition.PRESENTED &&
                        (localDraws.isEmpty() || localReceipt != null)
                    ) {
                        if (synchronized(lock) {
                            !closed && !failed && activated && activation === current &&
                                submittedGraph === current.graph &&
                                submittedPhysicalBatch === batch &&
                                lastSubmittedRevision == submission.snapshot.revision &&
                                batch.isCurrent
                        }) runCatching {
                            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_SCENE_PRESENTED,
                                "role" to scene.destination.role, "disposition" to "PRESENTED",
                                "revision" to submission.snapshot.revision,
                                "localDraws" to localDraws.size)
                            when (scene.destination.role) {
                                DisplayDestinationRole.MAIN -> onMainPresented?.invoke(
                                    UltraThemeMainPresentedFrame(
                                        current.graph, scene, submission.snapshot.revision,
                                        batch, submission.ticket, localReceipt,
                                    ),
                                )
                                DisplayDestinationRole.CLUSTER -> onClusterPresented?.invoke(
                                    UltraThemeClusterPresentedFrame(
                                        current.graph, scene, submission.snapshot.revision,
                                        batch, submission.ticket, localReceipt,
                                    ),
                                )
                                else -> Unit
                            }
                        }.exceptionOrNull()?.let {
                            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_SWALLOWED,
                                "where" to "PRESENTED_CALLBACK", "role" to scene.destination.role,
                                "error" to it)
                        }
                    } else if (ticketResult?.disposition != UltraEglRenderDisposition.FAILED &&
                        System.nanoTime() < deadline
                    ) poll(20L)
                    else UltraRevealTrace.mark(UltraRevealEvent.REVEAL_SCENE_PRESENTED,
                        "role" to scene.destination.role,
                        "disposition" to if (ticketResult?.disposition ==
                            UltraEglRenderDisposition.FAILED) "FAILED" else "TIMEOUT",
                        "revision" to submission.snapshot.revision,
                        "ticketDone" to (ticketResult != null),
                        "localProof" to (localReceipt != null))
                }, delayMillis, TimeUnit.MILLISECONDS) }
            }
            poll(0L)
        }
    }

    /** Caller holds [lock]. A video-only MAIN has no locally proven foreground scene. */
    private fun installForegroundSceneLocked(
        current: Activation,
        submission: UltraThemeBoundSceneSubmission,
        main: PreparedOutput?,
    ): String? {
        if (foregroundRequests == null) return "NO_PORT"
        val batch = current.physicalBatch ?: return "NO_PHYSICAL_BATCH"
        val row = main ?: return "NO_MAIN"
        val intent = row.intent ?: return "NO_SIGNED_INTENT"
        if (row.binding.source.signedIntent?.matches(intent) != true ||
            submittedSelections[row.assignment.uiSyncDisplayId]?.intent != intent
        ) return "INTENT_MISMATCH"
        if (!batch.isCurrent ||
            row.assignment.uiSyncDisplayId in transitionSuspendedDisplays ||
            (row.assignment.uiSyncDisplayId in protocolResetSuspendedDisplays &&
                !resetReRequestLocked(row.assignment.uiSyncDisplayId)) ||
            row.assignment.uiSyncDisplayId in unsupportedLayoutSuspendedDisplays ||
            row.assignment.uiSyncDisplayId in appearanceSuspendedDisplays ||
            row.assignment.uiSyncDisplayId in opaqueThemeSuspendedDisplays ||
            row.assignment.uiSyncDisplayId in paletteSuspendedDisplays ||
            !paletteCompatibleLocked(row.assignment.uiSyncDisplayId, intent)
        ) return "MAIN_SUSPENDED"
        val scene = submission.snapshot.scene(row.binding.destination) ?: return "NO_MAIN_SCENE"
        val background = scene.background as? UltraSceneBackground.OpaqueSrgb
            ?: return "NOT_OPAQUE_BACKGROUND"
        if (background.layoutAddress.layoutIndex != row.program.layoutIndex ||
            background.graphSnapshot !== current.graph ||
            scene.layers.any { it.source !is UltraSceneSource.Local }
        ) return "NOT_LOCAL_ONLY_SCENE"
        val localDraws = scene.layers.mapIndexed { index, layer ->
            val source = layer.source as UltraSceneSource.Local
            UltraEglLocalLayerDraw(
                scene.destination, UltraEglIngressKey.Local(source.contentId), index, layer,
            )
        }.toSet()
        // Opaque Debug fallback has no custom MAIN pixels. A Local Debug scene remains a
        // candidate, but its later EGL receipt is useful for raw5 only when every exact post
        // came from an actually invoked custom painter, including carried posts.
        if (diagnosticFallbackEnabled && localDraws.isEmpty()) return "DIAGNOSTIC_OPAQUE_ONLY"
        lateinit var candidate: ForegroundScene
        val source = UltraThemeForegroundSceneSource {
            synchronized(lock) { candidate.presented && foregroundSceneCurrentLocked(candidate) }
        }
        candidate = ForegroundScene(current, submission.snapshot.revision,
            row.assignment.uiSyncDisplayId, intent,
            foregroundSemanticsEpoch[row.assignment.uiSyncDisplayId] ?: 0L,
            row.signedPhoneCommand, scene,
            submission.ticket, localDraws, source,
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                LOCAL_FIRST_FRAME_TIMEOUT_MILLIS),
        )
        foregroundScene = candidate
        // A plain opaque clear can use its exact scene ticket. Local Canvas must instead wait
        // for the new-revision draw/swap/finish receipt, including actual MAIN requiredDraws.
        if (localDraws.isEmpty()) pollOpaqueMainPresentationLocked(candidate, 0L)
        return null
    }

    private fun foregroundSceneCurrentLocked(scene: ForegroundScene): Boolean {
        val current = activation ?: return false
        val batch = current.physicalBatch ?: return false
        if (closed || failed || !activated || foregroundScene !== scene ||
            current !== scene.activation || submittedPhysicalBatch !== batch ||
            submittedGraph !== current.graph || lastSubmittedRevision != scene.revision
        ) return false
        val layout = (scene.mainScene.background as UltraSceneBackground.OpaqueSrgb)
            .layoutAddress.layoutIndex
        if (selectedLayoutIndices[scene.displayId] != layout ||
            signedIntents[scene.displayId] != scene.signedPhoneCommand ||
            currentEpochDesiredLayoutLocked(scene.displayId) != scene.signedPhoneCommand ||
            submittedSelections[scene.displayId]?.intent != scene.intent ||
            scene.displayId in transitionSuspendedDisplays ||
            scene.displayId in unsupportedLayoutSuspendedDisplays ||
            scene.displayId in appearanceSuspendedDisplays ||
            scene.displayId in opaqueThemeSuspendedDisplays ||
            scene.displayId in paletteSuspendedDisplays ||
            !paletteCompatibleLocked(scene.displayId, scene.intent)
        ) return false
        if (scene.mainLocalDraws.isNotEmpty() &&
            lastLocalFirstFrameReceipt?.let {
                localMainReceiptProvesScene(scene, it)
            } != true
        ) return false
        return when (val admitted = current.provisioning.tryWithSceneSubmissionUse {
            coordinator.isLive && batch.isCurrent &&
                coordinator.graphSnapshot() === current.graph &&
                coordinator.stateSnapshot().lastSubmittedRenderRevision == scene.revision
        }) {
            is UltraProfileSceneUseAttempt.Admitted -> admitted.value
            UltraProfileSceneUseAttempt.Busy -> true // unknown, never a permanent revocation
            UltraProfileSceneUseAttempt.Revoked -> false
        }
    }

    private fun localMainReceiptProvesScene(
        scene: ForegroundScene,
        receipt: UltraEglLocalFirstFrameReceipt,
    ): Boolean = scene.mainLocalDraws.isNotEmpty() &&
        receipt.revision == scene.revision &&
        receipt.graphRevision == scene.activation.graph.revision &&
        receipt.physicalBatchGeneration == scene.activation.physicalBatch?.generation &&
        receipt.outputBatchGeneration > 0L &&
        receipt.requiredDraws.filterTo(LinkedHashSet()) {
            it.destination == scene.mainScene.destination
        } == scene.mainLocalDraws &&
        (!diagnosticFallbackEnabled || scene.mainLocalDraws.all { draw ->
            val entry = lastProvenCustomLocalPosts.entries.singleOrNull {
                it.key.contentId == draw.key.contentId
            } ?: return@all false
            lastProvenLocalPosts[entry.key] === entry.value &&
                receipt.postSequences[entry.key.key] == entry.value.postSequence
        })

    /** Never wait on an EGL ticket under the controller, provisioning, or render-thread lock. */
    private fun pollOpaqueMainPresentationLocked(scene: ForegroundScene, delayMillis: Long) {
        retrySchedulerLocked().schedule({
            if (!synchronized(lock) { foregroundSceneCurrentLocked(scene) }) return@schedule
            val result = runCatching { scene.ticket.await(0L) }.getOrNull()
            synchronized(lock) {
                if (!foregroundSceneCurrentLocked(scene)) return@synchronized
                when {
                    result?.revision == scene.revision &&
                        result.disposition == UltraEglRenderDisposition.PRESENTED -> {
                        UltraRevealTrace.mark(UltraRevealEvent.REVEAL_RAW5_SCENE,
                            "phase" to "PRESENTED", "revision" to scene.revision)
                        scene.presented = true
                        scheduleForegroundRequestLocked(scene)
                    }
                    result == null && System.nanoTime() <
                        scene.opaquePresentationDeadlineNanos ->
                        pollOpaqueMainPresentationLocked(scene, MINIMUM_RETRY_DELAY_MILLIS)
                    else -> scene.source.revoke()
                }
            }

        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun paletteCompatibleLocked(
        displayId: UltraUiSyncDisplayId,
        intent: CompiledUiSyncThemeIntent,
    ): Boolean {
        val state = checkNotNull(reducers[displayId]).state()
        val palette = state.palette ?: return true
        if (palette.layoutIdentifier != intent.layoutID ||
            palette.paletteIdentifier == intent.paletteID
        ) return true
        val paletteRevision = latestPaletteEventRevision[displayId] ?: return false
        val layoutRevision = state.lastLayoutChangeCommandRevision ?: return false
        return layoutRevision > paletteRevision &&
            signedIntents[displayId] == state.desiredLayoutChange
    }

    /** A repeated signed type-3 for the primary variant may reuse its proven Canvas post. */
    private fun selectedIntentStillCurrentLocked(
        assignment: CompiledUiSyncDisplayAssignment,
        intent: CompiledUiSyncThemeIntent?,
    ): Boolean {
        // A Reset re-request keeps rendering its primary signed scene under the Host cover.
        if (assignment.uiSyncDisplayId in protocolResetSuspendedDisplays &&
            !resetReRequestLocked(assignment.uiSyncDisplayId)
        ) return false
        signedPaletteSelections[assignment.uiSyncDisplayId]?.let { palette ->
            val id = assignment.uiSyncDisplayId
            return intent == palette.selectedIssuedIntent &&
                reducers[id]?.state()?.palette == palette.command &&
                latestPaletteEventRevision[id] == palette.eventRevision
        }
        val command = signedIntents[assignment.uiSyncDisplayId]
            ?: return intent == assignment.primaryIntent
        return intent != null && intent.layoutID == command.layoutID &&
            intent.activeComponents == command.activeComponents &&
            intent.paletteID == command.paletteID
    }

    /** A successful request is only a local transport write, never a phone foreground ACK. */
    private fun scheduleForegroundRequestLocked(scene: ForegroundScene) {
        val port = foregroundRequests ?: return
        if (scene.requestScheduled || !foregroundSceneCurrentLocked(scene)) return
        scene.requestScheduled = true
        monitorForegroundSceneLocked(scene)
        foregroundExecutorLocked().execute {
            dispatchForegroundRequest(scene, port)
        }
    }

    /** BUSY is not revocation; wait without holding either controller or provisioning locks. */
    private fun awaitForegroundAdmission(scene: ForegroundScene): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
            FOREGROUND_ADMISSION_WAIT_MILLIS)
        while (scene.source.isCurrent()) {
            when (val admitted = scene.activation.provisioning.tryWithSceneSubmissionUse {
                synchronized(lock) { foregroundSceneCurrentLocked(scene) }
            }) {
                is UltraProfileSceneUseAttempt.Admitted -> {
                    if (!admitted.value) scene.source.revoke()
                    return admitted.value && scene.source.isCurrent()
                }
                UltraProfileSceneUseAttempt.Revoked -> {
                    scene.source.revoke()
                    return false
                }
                UltraProfileSceneUseAttempt.Busy -> {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) return false
                    scene.activation.provisioning.awaitSceneOperationChange(
                        TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1L, 250L),
                    )
                }
            }
        }
        return false
    }

    private fun dispatchForegroundRequest(
        scene: ForegroundScene,
        port: UiSyncForegroundRequestPort,
    ) {
        if (!scene.source.isCurrent() ||
            synchronized(lock) { previousMainRequestSupersedesLocked(scene) }
        ) {
            if (UltraRevealTrace.enabled) UltraRevealTrace.mark(UltraRevealEvent.REVEAL_RAW5_SCENE,
                "phase" to if (scene.source.isCurrent()) "SUPERSEDED" else "SOURCE_REVOKED",
                "revision" to scene.revision)
            return
        }
        if (!awaitForegroundAdmission(scene)) {
            retryForegroundAdmission(scene, port)
            return
        }
        val outcome = runCatching {
            val guard = port.guardScene(scene.displayId.value, scene.source)
                ?: run {
                    // Shared session liveness may be temporarily BUSY during an exact parent
                    // probe. A null guard does not distinguish that from permanent retirement.
                    retryForegroundAdmission(scene, port)
                    return@runCatching null
                }
            if (!awaitForegroundAdmission(scene)) {
                retryForegroundAdmission(scene, port)
                return@runCatching null
            }
            val command = CarPlayUiSyncAccessoryCommand.RequestLayout(
                layoutID = scene.intent.layoutID,
                activeComponents = scene.intent.activeComponents,
            )
            val issued = IssuedRequestLayout(scene, command)
            synchronized(lock) {
                if (issuedRequestLayouts.size >= MAXIMUM_ISSUED_REQUEST_LAYOUTS) {
                    issuedRequestLayouts.removeFirst()
                }
                issuedRequestLayouts.addLast(issued)
            }
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_RAW5_SCENE, "phase" to "DISPATCH",
                "revision" to scene.revision, "layout" to command.layoutID)
            try {
                port.requestLayout(scene.displayId.value, command, guard).also { receipt ->
                    synchronized(lock) {
                        issued.receipt = receipt
                        // No wire write: a BUSY/REJECTED retry must not evict a real
                        // outstanding request from the bounded issued queue.
                        if (receipt.state == UiSyncForegroundRequestState.REJECTED ||
                            receipt.state == UiSyncForegroundRequestState.CANCELLED
                        ) issuedRequestLayouts.remove(issued)
                    }
                }
            } catch (failure: Throwable) {
                // No receipt proves the write; an echo can never be credited to this attempt.
                synchronized(lock) {
                    issuedRequestLayouts.remove(issued)
                }
                throw failure
            }
        }
        outcome.onSuccess { receipt ->
            if (receipt != null) UltraRevealTrace.mark(UltraRevealEvent.REVEAL_RAW5_SCENE,
                "phase" to "RECEIPT", "state" to receipt.state, "revision" to scene.revision)
            if (receipt == null) {
                // A BUSY/null-guard retry is already scheduled, or the source was revoked.
                if (!scene.source.isCurrent()) scene.source.revoke()
            } else if (receipt.state == UiSyncForegroundRequestState.REJECTED) {
                // REJECTED proves no wire write. Recheck the exact scene after a bounded wait;
                // only its own AA05/graph/batch revocation may permanently revoke this source.
                retryForegroundAdmission(scene, port)
            } else synchronized(lock) {
                lastForegroundRequest = ForegroundRequestRecord(scene, receipt)
                scene.reconnectRetryInFlight = false
            }
        }.onFailure { failure ->
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_SWALLOWED,
                "where" to "RAW5_DISPATCH", "error" to failure)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            scene.source.revoke()
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
        }
    }

    private fun retryForegroundAdmission(
        scene: ForegroundScene,
        port: UiSyncForegroundRequestPort,
    ) {
        if (!scene.source.isCurrent()) return
        runCatching {
            synchronized(lock) {
                if (!closed && foregroundScene === scene) retrySchedulerLocked().schedule({
                    if (scene.source.isCurrent()) runCatching {
                        val writer = synchronized(lock) {
                            if (!closed && foregroundScene === scene)
                                foregroundExecutorLocked() else null
                        }
                        writer?.execute {
                            dispatchForegroundRequest(scene, port)
                        }
                    }
                }, FOREGROUND_ADMISSION_RETRY_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** A detached pre-READY channel proves no write; acquire a fresh guard after reconnect. */
    private fun retryCancelledForegroundRequestLocked(scene: ForegroundScene) {
        val previous = lastForegroundRequest ?: return
        if (previous.scene !== scene ||
            previous.receipt.state != UiSyncForegroundRequestState.CANCELLED ||
            scene.reconnectRetryInFlight || !foregroundSceneCurrentLocked(scene)
        ) return
        val port = foregroundRequests ?: return
        scene.reconnectRetryInFlight = true
        val delay = scene.reconnectRetryDelayMillis
        scene.reconnectRetryDelayMillis = (delay * 2L).coerceAtMost(
            MAXIMUM_FOREGROUND_RETRY_DELAY_MILLIS)
        retrySchedulerLocked().schedule({
            if (!scene.source.isCurrent()) return@schedule
            val writer = synchronized(lock) {
                if (!closed && foregroundScene === scene) foregroundExecutorLocked() else null
            }
            writer?.execute { dispatchForegroundRequest(scene, port) }
        }, delay, TimeUnit.MILLISECONDS)
    }

    /** Only a confirmed local write suppresses a fresh MAIN proof after CLUSTER redraw. */
    private fun previousMainRequestSupersedesLocked(scene: ForegroundScene): Boolean {
        val previous = lastForegroundRequest ?: return false
        if (previous.receipt.state != UiSyncForegroundRequestState.SENT_UNCONFIRMED) return false
        val sentGeneration = previous.receipt.sentGeneration ?: return false
        val readyGeneration = runCatching {
            foregroundRequests?.currentReadyGeneration(scene.displayId.value)
        }.getOrNull() ?: return false
        if (sentGeneration !== readyGeneration) return false
        val prior = previous.scene
        val oldBackground = prior.mainScene.background as UltraSceneBackground.OpaqueSrgb
        val newBackground = scene.mainScene.background as UltraSceneBackground.OpaqueSrgb
        return prior.displayId == scene.displayId && prior.intent == scene.intent &&
            prior.semanticsEpoch == scene.semanticsEpoch &&
            prior.activation.graph === scene.activation.graph &&
            prior.activation.physicalBatch === scene.activation.physicalBatch &&
            prior.mainScene.destination == scene.mainScene.destination &&
            prior.mainScene.viewportPixels == scene.mainScene.viewportPixels &&
            prior.mainScene.layers == scene.mainScene.layers &&
            oldBackground.layoutAddress == newBackground.layoutAddress &&
            oldBackground.color == newBackground.color
    }

    /** Includes graph/batch retirement even when no new type-3 callback is delivered. */
    private fun monitorForegroundSceneLocked(scene: ForegroundScene) {
        retrySchedulerLocked().schedule({
            if (scene.source.isCurrent()) synchronized(lock) {
                if (!closed && foregroundScene === scene) {
                    retryCancelledForegroundRequestLocked(scene)
                    monitorForegroundSceneLocked(scene)
                }
            }
        }, FOREGROUND_SCENE_POLL_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun releaseCarryLeasesLocked() {
        currentCarryLeases.asReversed().forEach { it.close() }
        currentCarryLeases = emptyList()
    }

    private fun failLocked(cause: Throwable) {
        if (!failed) {
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_CTRL_FAILED, "error" to cause,
                "cause" to cause.cause)
            // A terminal semantic/compiler failure invalidates *every* already-submitted
            // physical MAIN, including malformed-only replays with no recognized transition.
            // Phase 1 is short and synchronous; the Host ACK is queued off this lock below.
            runCatching {
                activation?.takeIf { it.physicalBatch != null }?.let { current ->
                    current.outputBindings.asSequence()
                        .filter { it.destination.role == DisplayDestinationRole.MAIN }
                        .mapNotNull { current.graph.output(it.outputId)?.uiSyncDisplayId }
                        .distinct()
                        .forEach { id -> suspendSubmittedMainSemanticsLocked(id) }
                }
            }.exceptionOrNull()?.let { if (it !== cause) cause.addSuppressed(it) }
            failed = true
            failure = cause
            foregroundScene?.source?.revoke()
            lastLocalFirstFrameReceipt = null
            lastProvenLocalPosts = emptyMap()
            lastProvenCustomLocalPosts = emptyMap()
            releaseCarryLeasesLocked()
            deferredSnapshots.clear()
            coordinator.sealSemanticRendererFailure(cause)
            if (!opening) {
                if (pendingMainSemanticCover != null) runCatching {
                    retrySchedulerLocked().execute { completePendingMainSemanticCover() }
                }.exceptionOrNull()?.let { if (it !== cause) cause.addSuppressed(it) }
                scheduleCloseRetryLocked(0L)
            }
        }
    }

    private fun scheduleCloseRetryLocked(delayMillis: Long) {
        if (closeRetryScheduled || closeInFlight || subscriptions.isEmpty() ||
            closeRetryCount >= MAXIMUM_CLOSE_RETRIES
        ) return
        closeRetryScheduled = true
        retrySchedulerLocked().schedule({
            synchronized(lock) {
                closeRetryScheduled = false
                closeRetryCount += 1
            }
            runCatching { close() }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    fun stateSnapshot(): UltraThemeLayoutIntentControllerSnapshot = synchronized(lock) {
        val current = activation
        val aa05NotRevoked = current?.provisioning?.tryWithSceneSubmissionUse { true }
            ?.let { it !== UltraProfileSceneUseAttempt.Revoked } == true
        // BUSY is a temporary parent operation, not evidence that this exact child was revoked.
        // The field remains only a queue-state diagnostic, never a physical presentation ACK.
        UltraThemeLayoutIntentControllerSnapshot(
            lastSubmittedRevision,
            selectedLayoutIndices,
            !closed && !failed && activated && current != null && aa05NotRevoked &&
                coordinator.isLive &&
                current.graph === coordinator.graphSnapshot() &&
                (current.physicalBatch == null || current.physicalBatch.isCurrent) &&
                (current.physicalBatch == null || activeLocalCanvasHandles.isEmpty() ||
                    lastLocalFirstFrameReceipt?.revision == lastSubmittedRevision),
            pendingInputStream,
            pendingLocalCanvas,
            closed,
            failed,
            lastLocalFirstFrameReceipt?.takeIf { receipt ->
                receipt.revision == lastSubmittedRevision && current?.physicalBatch?.isCurrent == true &&
                    current.graph === coordinator.graphSnapshot()
            },
        )
    }

    /** Returns only a currently selected, signed child handle; never exposes its Surface. */
    internal fun currentSignedLocalCanvasHandle(
        contentId: UltraContentId,
    ): UltraLocalCanvasSlotOwner.Handle? = synchronized(lock) {
        val current = activation ?: return@synchronized null
        if (closed || failed || !activated || localCanvasOwner == null ||
            !current.provisioning.isLive || !coordinator.isLive ||
            current.physicalBatch?.isCurrent != true ||
            coordinator.graphSnapshot() !== current.graph ||
            lastLocalFirstFrameReceipt?.revision != lastSubmittedRevision
        ) return@synchronized null
        activeLocalCanvasHandles.singleOrNull { handle ->
            handle.contentId == contentId && handle in lastProvenLocalPosts &&
                handle.admitted()
        }
    }

    override fun close() {
        val (retired, foreground) = synchronized(lock) {
            check(!closeInFlight) {
                "Signed theme subscription close is still in flight; retry after it settles"
            }
            closeInFlight = true
            closed = true
            foregroundScene?.source?.revoke()
            lastLocalFirstFrameReceipt = null
            lastProvenLocalPosts = emptyMap()
            lastProvenCustomLocalPosts = emptyMap()
            releaseCarryLeasesLocked()
            deferredSnapshots.clear()
            subscriptions.toList().asReversed() to foregroundExecutor?.also { it.shutdown() }
        }
        // STOPPING/failed semantic replay must still finish the exact pending Host cover.
        // The Authority's permit was already revoked in phase 1.
        try {
            completePendingMainSemanticCover()
            // A foreground request can be waiting on a synchronous EGL control operation.
            // shutdownNow() would interrupt that caller and turn a still-running operation into
            // retirement uncertainty. Keep the worker and its exact resources until it exits.
            if (foreground != null) {
                check(Thread.currentThread() !== foregroundWorkerThread) {
                    "Foreground request worker cannot retire itself; retry close from its owner"
                }
                val drained = try {
                    foreground.awaitTermination(
                        FOREGROUND_WORKER_RETIRE_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException(
                        "Interrupted while waiting for foreground request retirement", interrupted)
                }
                check(drained) { "Foreground request is still in flight; retry close" }
            }
            var closeFailure: Throwable? = null
            retired.forEach { subscription ->
                val result = runCatching { subscription.close() }
                if (result.isSuccess) synchronized(lock) { subscriptions.remove(subscription) }
                else result.exceptionOrNull()?.let { cause ->
                    if (closeFailure == null) closeFailure = cause
                    else if (closeFailure !== cause) closeFailure?.addSuppressed(cause)
                }
            }
            synchronized(lock) {
                closeInFlight = false
                if (subscriptions.isEmpty()) retryScheduler?.shutdown()
                else scheduleCloseRetryLocked(
                    (MINIMUM_RETRY_DELAY_MILLIS shl closeRetryCount.coerceAtMost(5))
                        .coerceAtMost(MAXIMUM_RETRY_DELAY_MILLIS),
                )
            }
            closeFailure?.let { throw it }
        } finally {
            synchronized(lock) { closeInFlight = false }
        }
    }

    internal companion object {
        private const val MINIMUM_RETRY_DELAY_MILLIS = 25L
        private const val MAXIMUM_RETRY_DELAY_MILLIS = 1_000L
        private const val MAXIMUM_CLOSE_RETRIES = 16
        private const val MAXIMUM_QUEUED_LAYOUT_INTENTS = 256
        private const val MAXIMUM_ISSUED_REQUEST_LAYOUTS = 4
        /**
         * Type-19 `preference` values admitted for a standalone re-proof: the evidenced CarPlay
         * appearance-mode domain (-1, 0, 1) used by requestLayout/targetAppearanceChange. The
         * preference enum itself is not wire-proven; anything else stays covered.
         */
        private val SUPPORTED_APPEARANCE_PREFERENCES = -1..1
        private const val LOCAL_FIRST_FRAME_TIMEOUT_MILLIS = 5_000L
        private const val FOREGROUND_SCENE_POLL_MILLIS = 50L
        private const val FOREGROUND_ADMISSION_WAIT_MILLIS = 2_000L
        private const val FOREGROUND_WORKER_RETIRE_WAIT_MILLIS = 500L
        private const val FOREGROUND_ADMISSION_RETRY_MILLIS = 250L
        private const val MAXIMUM_FOREGROUND_RETRY_DELAY_MILLIS = 2_000L

        fun open(
            coordinator: AndroidUltraRendererSessionCoordinator,
            provisioning: AuthorizedUltraProfileProvisioning,
            verifiedProfile: VerifiedUltraProfile,
            graph: UltraDisplayGraphSnapshot,
            outputBindings: Collection<UltraThemeOutputSceneBinding>,
        ): UltraThemeLayoutIntentController {
            // Diagnostic compatibility only: product ARM must pass issuer-validated IDs.
            // Do not query AA05 here; an ordinary parent probe may still own admission.
            val controller = arm(coordinator, coordinator.displayIds.map(::UltraUiSyncDisplayId))
            try {
                check(controller.activateForDiagnostics(provisioning, verifiedProfile, graph,
                    outputBindings) == UltraThemeLayoutActivationResult.SUBMITTED
                ) { "Diagnostic signed theme initial scene is not executable yet" }
                return controller
            } catch (cause: Throwable) {
                runCatching { controller.close() }.exceptionOrNull()?.let(cause::addSuppressed)
                throw cause
            }
        }

        /** Called at factory.openSession, before AA05 creates a provisioning child. */
        fun arm(
            coordinator: AndroidUltraRendererSessionCoordinator,
            issuerDisplayIds: Collection<UltraUiSyncDisplayId>,
            localCanvasOwner: UltraLocalCanvasSlotOwner? = null,
            instrumentOwner: UltraInstrumentReferenceSlotOwner? = null,
            localCanvasPainter:
                ((UltraContentId) -> ((Canvas, PixelSize) -> Unit)?)? = null,
            diagnosticFallbackEnabled: Boolean = false,
            foregroundRequests: UiSyncForegroundRequestPort? = null,
            onClusterPresented: ((UltraThemeClusterPresentedFrame) -> Unit)? = null,
            onMainPresented: ((UltraThemeMainPresentedFrame) -> Unit)? = null,
            beginMainSemanticCover:
                ((UiSyncPhysicalOutputBatch, UltraUiSyncDisplayId, Long) ->
                    MainSemanticPhysicalCoverTicket?)? = null,
            resumeMainAfterSignedPhoneVisualChoice:
                ((UiSyncPhysicalOutputBatch, UltraUiSyncDisplayId, Long) -> Boolean)? = null,
        ): UltraThemeLayoutIntentController = UltraThemeLayoutIntentController(
            coordinator, issuerDisplayIds, localCanvasOwner, instrumentOwner, localCanvasPainter,
            diagnosticFallbackEnabled, foregroundRequests, onClusterPresented,
            onMainPresented, beginMainSemanticCover, resumeMainAfterSignedPhoneVisualChoice,
        ).also {
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_CTRL_ACTIVATE, "phase" to "ARM",
                "displays" to issuerDisplayIds.size, "raw5Port" to (foregroundRequests != null),
                "localCanvas" to (localCanvasOwner != null),
                "diagnosticFallback" to diagnosticFallbackEnabled)
            it.start()
        }
    }
}
