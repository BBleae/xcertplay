package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.AuthorizedAndroidPhysicalOutputSealer
import com.shilapi.xcertplay.CompiledUiSyncRuntimeContract
import com.shilapi.xcertplay.CarPlayWebDisplayFactory
import com.shilapi.xcertplay.HostDisplaySurfaceKind
import com.shilapi.xcertplay.HostUltraCompositionInputs
import com.shilapi.xcertplay.UltraProfilePhysicalGenerationProof
import com.shilapi.xcertplay.UltraProfilePhysicalGenerationStatus
import com.shilapi.xcertplay.UltraProfileSessionToken
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.airplay.rcs.uisync.BoundedUiSyncPresentationExecutor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncChannelDescriptor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticDecodeResult
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticDecoder
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncDisplayRuntimeSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestination
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestinationRole
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAssignment
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRequirement
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSource
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSubscription
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncReadOnlySessionContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncReadOnlySessionStatus
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererProvisioningException
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeAuthorizationExpectation
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeProvisioning
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.ultra.AndroidMediaSinkPhysicalLegacyHandoffPort
import com.shilapi.xcertplay.ultra.AndroidUltraRendererSessionCoordinator
import com.shilapi.xcertplay.ultra.AuthorizedAndroidUiSyncPhysicalOutputAuthority
import com.shilapi.xcertplay.ultra.AuthorizedStaticThemeRendererCapability
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.StaticThemeRendererSession
import com.shilapi.xcertplay.ultra.egl.MatchedRemoteFrameConsumerReceipt
import com.shilapi.xcertplay.ultra.egl.RemoteFrameTimestampBasis
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.io.Closeable
import java.util.ArrayDeque
import java.util.UUID

/** A failed construction retains the exact EGL/provisioning owner for retry. */
internal class BenchUiSyncConstructionException(
    cause: Throwable,
    val pendingCleanup: Closeable,
) : IllegalStateException("Debug UI Sync bench construction cleanup remains pending", cause)

/**
 * One explicitly signed Debug bench runtime. The Profile is local test material, never an
 * Apple/OEM trust root. The Host and shared provisioning independently prove physical output
 * currentness; no Profile field can mint a Surface or a renderer-ready receipt by itself.
 */
internal class BenchAuthorizedUiSyncOwner private constructor(
    val provisioning: UiSyncRuntimeProvisioning,
    val contract: CompiledUiSyncRuntimeContract,
    val sealer: AuthorizedAndroidPhysicalOutputSealer,
    private val resources: Resources,
) : Closeable {
    override fun close() = resources.close()

    /** Only the exact authority installed during this owner's construction can answer this. */
    internal interface PhysicalCoverRetirement {
        fun revokeForPolicyEpoch(): Boolean
        fun retryPendingRetirement(): Boolean
    }

    internal class Resources internal constructor(
        private var physicalCoverRetirement: PhysicalCoverRetirement? = null,
    ) : Closeable {
        fun attachAuthority(authority: AuthorizedAndroidUiSyncPhysicalOutputAuthority) {
            check(!closed && physicalCoverRetirement == null) {
                "Debug UI Sync resources already have a physical authority"
            }
            physicalCoverRetirement = object : PhysicalCoverRetirement {
                override fun revokeForPolicyEpoch(): Boolean =
                    authority.revokeForPolicyEpoch()

                override fun retryPendingRetirement(): Boolean =
                    authority.retryPendingRetirement()
            }
        }

        var provisioning: Closeable? = null
        var provisionerCleanup: Closeable? = null
        var callbacks: BoundedUiSyncPresentationExecutor? = null
        var benchPainter: Closeable? = null
        private var provisioningClosed = false
        private var cleanupClosed = false
        private var callbacksClosed = false
        private var benchPainterClosed = false
        private var closed = false

        @Synchronized
        override fun close() {
            if (closed) return
            // A false/throwing ACK leaves this same owner and all of its workers intact. In
            // particular, a missing old MAIN or CLUSTER Host cover cannot be replaced by a
            // successor Surface, and a Unit revoke() result is not retirement evidence.
            check(physicalCoverRetirement?.revokeForPolicyEpoch() != false) {
                "Exact physical UI cover has not ACKed Debug UI Sync retirement"
            }
            if (!cleanupClosed) {
                provisionerCleanup?.close()
                cleanupClosed = true
            }
            if (!provisioningClosed) {
                provisioning?.close()
                provisioningClosed = true
            }
            if (!callbacksClosed) {
                callbacks?.close()
                callbacksClosed = true
            }
            if (!benchPainterClosed) {
                benchPainter?.close()
                benchPainterClosed = true
            }
            check(physicalCoverRetirement?.retryPendingRetirement() != false) {
                "Exact physical output retirement remains pending"
            }
            closed = true
        }
    }

    companion object {
        fun open(
            profile: VerifiedUltraProfile,
            host: HostUltraCompositionInputs,
            generationProof: (UltraProfileSessionToken) -> UltraProfilePhysicalGenerationProof?,
            generationStatus: (UltraProfileSessionToken) ->
                UltraProfilePhysicalGenerationStatus = { token ->
                    generationProof(token)?.let { UltraProfilePhysicalGenerationStatus.Current(it) }
                        ?: UltraProfilePhysicalGenerationStatus.Revoked
                },
        ): BenchAuthorizedUiSyncOwner {
            val resources = Resources()
            try {
                val sink = requireNotNull(host.currentMediaSink()) {
                    "The exact Host media sink has already retired"
                }
                val compiler = BenchUiSyncProfileCompiler()
                val declarations = compiler.declaredDisplays(profile)
                val hostOutputs = hostOutputs(host, declarations)
                val ingressSpecs = compiler.declaredIngresses(profile).map { ingress ->
                    UiSyncRendererIngressSpec(
                        ingress.id,
                        UltraAirPlayStreamIdentity(ingress.streamType),
                        setupDisplayUuid = ingress.advertisedScreenId,
                    )
                }
                val compiled = compiler.compile(profile, hostOutputs, ingressSpecs)
                val plan = UiSyncRendererStaticPlan(compiled.outputs, compiled.ingressSpecs)
                val requirement = UiSyncPhysicalOutputRequirement(
                    profile.candidate.contentDigest,
                    compiled.assignments.map { assignment ->
                        val output = compiled.outputs.single {
                            it.outputId == assignment.outputId
                        }
                        UiSyncPhysicalOutputAssignment(
                            output,
                            UiSyncPhysicalDestination(
                                when (assignment.destination.role) {
                                    DisplayDestinationRole.MAIN -> UiSyncPhysicalDestinationRole.MAIN
                                    DisplayDestinationRole.CLUSTER -> UiSyncPhysicalDestinationRole.CLUSTER
                                    else -> error("Unsupported Debug physical destination")
                                },
                                assignment.destination.instanceId.value,
                            ),
                        )
                    },
                )
                val displays = compiled.assignments.map { assignment ->
                    UiSyncDisplayRuntimeSpec(
                        descriptor = CarPlayUiSyncChannelDescriptor(
                            assignment.uiSyncDisplayId.value,
                            profile.binding.vehicleId,
                        ),
                        mode = CarPlayUiSyncMode.PASSIVE,
                    )
                }
                val inputs = UiSyncRuntimeProvisioning.prepareAuthorizedStaticInputs(
                    AirPlayUiSyncInfo(), displays, plan,
                )
                val routeTypes = compiled.assignments.associate { assignment ->
                    assignment.destination to when (assignment.destination.role) {
                        DisplayDestinationRole.MAIN -> 110
                        DisplayDestinationRole.CLUSTER -> 111
                        else -> error("Unsupported Debug physical destination")
                    }
                }
                val authority = AuthorizedAndroidUiSyncPhysicalOutputAuthority(
                    verifiedProfile = profile,
                    requirement = requirement,
                    staticPlan = plan,
                    schemaFingerprint = inputs.schemaFingerprint,
                    bindingFingerprint = inputs.bindingFingerprint,
                    surfaces = host.physicalSurfaces,
                    legacyHandoff = AndroidMediaSinkPhysicalLegacyHandoffPort(
                        sink, host::frozenExternalClusterRoute,
                    ),
                    generationProof = generationProof,
                    routeTypes = routeTypes,
                    generationStatusSource = generationStatus,
                )
                resources.attachAuthority(authority)
                val capability = AuthorizedStaticThemeRendererCapability(
                    profile, compiled.assignments, inputs, authority,
                    diagnosticFallbackEnabled = true,
                    videoFrameReleaseChannel = host.currentVideoFrameReleaseChannel(sink),
                ) { context ->
                    // The renderer factory also creates a disposable premanifest probe. Never
                    // give that foreign coordinator access to the later bound type-111 route.
                    lateinit var coordinator: AndroidUltraRendererSessionCoordinator
                    coordinator = AndroidUltraRendererSessionCoordinator(context, sink) {
                        authority.currentTransferredExternalRouteLease(coordinator)
                    }
                    coordinator
                }
                val callbacks = BoundedUiSyncPresentationExecutor(
                    parallelism = displays.size,
                    maximumPendingCallbacks = 64,
                )
                resources.callbacks = callbacks
                val clusterDisplayId = compiled.assignments.singleOrNull {
                    it.destination.role == DisplayDestinationRole.CLUSTER
                }?.uiSyncDisplayId?.value
                val readOnlyWebDisplayBinding: ((UiSyncPresentationSource) -> Closeable?)? =
                    clusterDisplayId?.let { exactDisplayId ->
                        { source ->
                            // Lookup is deferred until a real AirPlay/AA05 renderer session has
                            // passed physical batch activation. This exact Host sink may have no
                            // optional WebDisplay; absence is not renderer readiness evidence.
                            CarPlayWebDisplayFactory.find(sink)
                                ?.takeIf { it.owns(CarPlayWebDisplayFactory.CLUSTER) }
                                ?.attachClusterPresentation(source, exactDisplayId)
                        }
                    }
                val provisioning = UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
                    inputs = inputs,
                    rendererCapability = capability,
                    callbackExecutor = callbacks,
                    authorizationExpectation = UiSyncRuntimeAuthorizationExpectation(
                        compiled.semanticThemeProgramFingerprint,
                        AndroidUltraRendererSessionCoordinator.IMPLEMENTATION_ID,
                        requirement,
                    ),
                    physicalOutputAuthority = authority,
                    readOnlyPresentationBinding = readOnlyWebDisplayBinding,
                    readOnlySessionBinding = { session ->
                        BenchTimingCaptureBinding.open(
                            session, authority,
                            compiled.assignments.map { it.uiSyncDisplayId.value },
                            clusterDisplayId,
                        )
                    },
                )
                resources.provisioning = provisioning
                require(provisioning.usesPhysicalOutputAuthorityExactly(authority)) {
                    "Debug UI Sync preflight used another physical authority"
                }
                val contract = CompiledUiSyncRuntimeContract.from(
                    requireNotNull(provisioning.authorizationSnapshot()) {
                        "Debug UI Sync static renderer is not authorized for this Profile"
                    },
                    compiled.assignments,
                )
                if (BenchLocalCanvasPainter.selectedBy(contract.displayAssignments)) {
                    resources.benchPainter = BenchLocalCanvasPainter.installIfAbsent()
                }
                return BenchAuthorizedUiSyncOwner(
                    provisioning, contract, AuthorizedAndroidPhysicalOutputSealer(authority),
                    resources,
                )
            } catch (failure: Throwable) {
                if (failure is UiSyncRendererProvisioningException) {
                    resources.provisionerCleanup = failure
                }
                val cleanupFailure = runCatching { resources.close() }.exceptionOrNull()
                if (cleanupFailure != null) {
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                    throw BenchUiSyncConstructionException(failure, resources)
                }
                throw failure
            }
        }

        private fun hostOutputs(
            host: HostUltraCompositionInputs,
            declarations: List<BenchUiSyncDeclaredDisplay>,
        ): List<BenchUiSyncHostOutput> {
            val config = host.airPlayConfig
            val panels = requireNotNull(config.displayPanels) {
                "Signed UI Sync bench requires explicit DDP display panels"
            }
            require(config.displayPanelsLiveness?.isCurrentFor(panels) == true) {
                "The exact Host DDP panel declaration is no longer live"
            }
            return declarations.map { display ->
                val (legacyUuid, legacy, kind) = when (display.destination.role) {
                    DisplayDestinationRole.MAIN -> {
                        require(display.destination == DisplayDestination.MAIN) {
                            "A physical MAIN output must use the exact default Host destination"
                        }
                        Triple(AirPlayInfoPlist.MAIN_UUID, config.main,
                            HostDisplaySurfaceKind.PHYSICAL_MAIN)
                    }
                    DisplayDestinationRole.CLUSTER -> Triple(
                        AirPlayInfoPlist.ALT_UUID,
                        requireNotNull(config.cluster) {
                            "Signed cluster output has no configured legacy display"
                        },
                        HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                    )
                    else -> error("Debug UI Sync supports only MAIN/CLUSTER Host outputs")
                }
                require(display.advertisedScreenId == legacyUuid &&
                    display.output.topologyDisplayId.value == UUID.fromString(legacyUuid) &&
                    panels.pluginUidManagingLegacyStream(legacyUuid) ==
                        display.output.uiSyncDisplayId?.value
                ) { "Signed UI Sync display does not match the exact Host DDP plug-in" }
                val inspection = requireNotNull(
                    host.physicalSurfaces.inspectPhysicalTarget(display.destination),
                ) { "Signed UI Sync output has no independently physical Host target" }
                if (display.destination.role == DisplayDestinationRole.CLUSTER) {
                    val calibration = requireNotNull(inspection.externalCalibration) {
                        "Signed cluster output has no independently calibrated physical size"
                    }
                    require(host.matchesFrozenExternalCluster(inspection) &&
                        calibration.widthPhysicalMm == legacy.widthPhysicalMm &&
                        calibration.heightPhysicalMm == legacy.heightPhysicalMm
                    ) {
                        "Signed cluster instance or physical millimetres differ from frozen DDP"
                    }
                }
                require(inspection.version.kind == kind &&
                    inspection.version.sizePixels.widthPixels == legacy.widthPixels &&
                    inspection.version.sizePixels.heightPixels == legacy.heightPixels &&
                    host.physicalSurfaces.isSameLivePhysicalTarget(
                        inspection.version, inspection.surface,
                    )
                ) { "Signed UI Sync output differs from the current Host target pixels" }
                BenchUiSyncHostOutput(
                    display.profileDisplayIndex, legacyUuid,
                    display.destination, display.output,
                )
            }
        }
    }
}

/** The Debug page sees only the current exact-session, memory-bounded sanitized recorder. */
internal object BenchTimingCaptureCatalog {
    private val lock = Any()
    private var active: BenchTimingCaptureBinding? = null

    data class Summary(
        val capture: BenchTimingCapture.Snapshot,
        val droppedObserverSnapshots: Long,
    )

    fun summary(): Summary? = synchronized(lock) { active }?.let { binding ->
        val dropped = binding.droppedObserverSnapshots()
        binding.capture.noteObserverSnapshotDrops(binding.sessionToken(), dropped)
        Summary(binding.capture.snapshot(), dropped)
    }

    fun exportNdjson(): String? = synchronized(lock) { active }?.let { binding ->
        binding.capture.noteObserverSnapshotDrops(
            binding.sessionToken(), binding.droppedObserverSnapshots(),
        )
        binding.capture.exportNdjson().takeIf { it.isNotEmpty() }
    }

    internal fun publish(binding: BenchTimingCaptureBinding): Boolean = synchronized(lock) {
        if (active != null || binding.capture.snapshot().state != BenchTimingCapture.State.CURRENT) {
            false
        } else {
            active = binding
            true
        }
    }

    internal fun retire(binding: BenchTimingCaptureBinding) = synchronized(lock) {
        if (active === binding) active = null
    }
}

/** Bounded O(1) handoff from the renderer's shared presentation dispatcher. */
internal class BenchTimingSnapshotMailbox(
    private val capacity: Int = 4,
) : Closeable {
    data class Offer(
        val displayId: String,
        val token: Any,
        val snapshot: UltraPresentationSnapshot,
    )

    private val lock = Any()
    private val pending = ArrayDeque<Offer>()
    private var closed = false
    private var dropped = 0L

    init { require(capacity in 1..16) }

    fun offer(displayId: String, token: Any, snapshot: UltraPresentationSnapshot): Boolean =
        synchronized(lock) {
            if (closed) return@synchronized false
            if (pending.size == capacity) {
                pending.removeFirst()
                if (dropped < Long.MAX_VALUE) dropped++
            }
            pending.addLast(Offer(displayId, token, snapshot))
            true
        }

    fun drain(): List<Offer> = synchronized(lock) {
        ArrayList<Offer>(pending.size).also { batch ->
            while (pending.isNotEmpty()) batch += pending.removeFirst()
        }
    }

    fun clearPending() = synchronized(lock) {
        val discarded = pending.size.toLong()
        dropped = if (Long.MAX_VALUE - dropped < discarded) Long.MAX_VALUE
            else dropped + discarded
        pending.clear()
    }

    val droppedCount: Long get() = synchronized(lock) { dropped }

    override fun close() = synchronized(lock) {
        closed = true
        pending.clear()
    }
}

/**
 * Optional Debug timing observer. It is not registered with the renderer's counted subscription
 * source and cannot change UI Sync READY, scene, video scheduling, or physical output ownership.
 */
internal class BenchTimingCaptureBinding private constructor(
    private val session: UiSyncReadOnlySessionContext,
    private val child: StaticThemeRendererSession,
    private val authority: AuthorizedAndroidUiSyncPhysicalOutputAuthority,
    displayIds: List<String>,
    clusterDisplayId: String?,
) : Closeable {
    val capture = BenchTimingCapture(session.sessionIdentity)
    private val lock = Any()
    private val decoder = CarPlayUiSyncSemanticDecoder()
    private val displayTokens: Map<String, Any> = displayIds.associateWith { Any() }
    private val clusterToken: Any? = clusterDisplayId?.let(displayTokens::get)
    private val subscriptions = ArrayList<UiSyncPresentationSubscription>()
    private val lastObservedRevisions = HashMap<String, Long>()
    private val sourceDroppedByDisplay = HashMap<String, Long>()
    private val snapshotMailbox = BenchTimingSnapshotMailbox()
    private var videoObserver: Closeable? = null
    private var worker: Thread? = null
    private var closed = false
    private var observedStatus = UiSyncReadOnlySessionStatus.CURRENT
    /** Same monotonic domain as the accepted UI Sync and MediaCodec release receipts. */
    private var currentEpochStartNanos = System.nanoTime()
    private val knownReleases = LinkedHashMap<FrameIdentity, Unit>()

    private class FrameIdentity(
        val handle: Any,
        val surface: Any,
        val leaseGeneration: Long,
        val counter: Long,
    ) {
        override fun equals(other: Any?): Boolean = other is FrameIdentity &&
            handle === other.handle && surface === other.surface &&
            leaseGeneration == other.leaseGeneration && counter == other.counter

        override fun hashCode(): Int =
            31 * (31 * System.identityHashCode(handle) + System.identityHashCode(surface)) +
                31 * leaseGeneration.hashCode() + counter.hashCode()
    }

    private class PendingEgl(
        val receipt: MatchedRemoteFrameConsumerReceipt,
        val expiresAtNanos: Long,
    )

    fun droppedObserverSnapshots(): Long = snapshotMailbox.droppedCount

    fun sessionToken(): Any = session.sessionIdentity

    private fun start() {
        for ((displayId, token) in displayTokens) {
            val subscribed = session.presentationSource.subscribeWithReplay(displayId) {
                snapshot -> offerSnapshot(displayId, token, snapshot)
            }
            synchronized(lock) { subscriptions += subscribed }
            offerSnapshot(displayId, token, subscribed.initialSnapshot)
        }
        attachVideoObserverIfCurrent()
        worker = Thread(::drainMatchedFrames, "xcertplay-debug-timing-capture").apply {
            isDaemon = true
            start()
        }
    }

    /** Called on the shared per-display presentation dispatcher: no decode or Host probe here. */
    private fun offerSnapshot(displayId: String, token: Any, snapshot: UltraPresentationSnapshot) {
        snapshotMailbox.offer(displayId, token, snapshot)
    }

    /** BUSY is a pause, not proof of CURRENT; a terminal mismatch destroys all retained data. */
    private fun current(): Boolean {
        if (synchronized(lock) { closed }) return false
        val token = session.sessionIdentity
        return when (session.status) {
            UiSyncReadOnlySessionStatus.CURRENT -> {
                if (runCatching {
                        authority.ownsBoundBatch(child, session.physicalBatch)
                    }.getOrDefault(false)
                ) {
                    synchronized(lock) {
                        if (observedStatus != UiSyncReadOnlySessionStatus.CURRENT) {
                            currentEpochStartNanos = System.nanoTime()
                        }
                        observedStatus = UiSyncReadOnlySessionStatus.CURRENT
                    }
                    capture.resumeCurrentFromExternalAuthority(token)
                    true
                } else if (session.physicalBatch.generationStatus ==
                    com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus.BUSY
                ) {
                    pause(token)
                    false
                } else {
                    revoke(token)
                    false
                }
            }
            UiSyncReadOnlySessionStatus.BUSY -> {
                pause(token)
                false
            }
            UiSyncReadOnlySessionStatus.REVOKED -> {
                revoke(token)
                false
            }
        }
    }

    private fun pause(token: Any) {
        capture.markBusy(token)
        snapshotMailbox.clearPending()
        synchronized(lock) {
            observedStatus = UiSyncReadOnlySessionStatus.BUSY
            knownReleases.clear()
        }
    }

    private fun revoke(token: Any) {
        capture.revoke(token)
        snapshotMailbox.close()
        synchronized(lock) {
            observedStatus = UiSyncReadOnlySessionStatus.REVOKED
            knownReleases.clear()
        }
        BenchTimingCaptureCatalog.retire(this)
    }

    private fun acceptType2(displayId: String, token: Any, snapshot: UltraPresentationSnapshot) {
        if (snapshot.displayId.value != displayId) return
        val sourceDrops = synchronized(lock) {
            val prior = sourceDroppedByDisplay[displayId] ?: 0L
            if (snapshot.droppedEventCount > prior) {
                sourceDroppedByDisplay[displayId] = snapshot.droppedEventCount
            }
            sourceDroppedByDisplay.values.fold(0L) { total, count ->
                if (Long.MAX_VALUE - total < count) Long.MAX_VALUE else total + count
            }
        }
        capture.noteSourceRetainedEventDrops(session.sessionIdentity, sourceDrops)
        if (snapshot.closed || !current()) return
        for (event in snapshot.events) {
            val fresh = synchronized(lock) {
                if (closed || event.revision <= (lastObservedRevisions[displayId] ?: 0L)) {
                    false
                } else {
                    lastObservedRevisions[displayId] = event.revision
                    true
                }
            }
            if (!fresh) continue
            if (event.type != CarPlayUiSyncCommandType.TRANSITION_DATA.wireValue) continue
            val decoded = decoder.decode(event) as? CarPlayUiSyncSemanticDecodeResult.Decoded
                ?: continue
            val timing = decoded.command as? CarPlayUiSyncSemanticCommand.TransitionData
                ?: continue
            val acceptedAt = event.acceptedAtMonotonicNanos ?: continue
            if (!current() || acceptedAt < synchronized(lock) { currentEpochStartNanos }) {
                continue
            }
            // No regions, payload dictionaries, display IDs, vehicle IDs or signatures enter
            // the recorder; duplicate replay revisions are discarded by its exact-token ledger.
            capture.recordType2(
                session.sessionIdentity, token, event.revision,
                timing.presentationHostTimeBits, timing.presentationNtpTimeBits,
                timing.presentationNtpTimeOriginalBits, timing.currentNtpTimeBits,
                acceptedAt,
            )
        }
    }

    private fun attachVideoObserverIfCurrent() {
        if (clusterToken == null || !current() || synchronized(lock) {
                closed || videoObserver != null
            }) return
        val attached = child.attachReadOnlyVideoReleaseObserver(session.physicalBatch) { receipt ->
            val display = clusterToken
            if (display != null && current() &&
                receipt.releaseCalledAtMonotonicNanos >= synchronized(lock) {
                    currentEpochStartNanos
                } && capture.recordVideoRelease(
                    session.sessionIdentity, display, receipt.handle, receipt.surface,
                    receipt.surfaceLeaseGeneration, receipt.authenticatedFrameCounter,
                    receipt.presentationTimeNtp64Bits, receipt.codecOutputPresentationTimeUs,
                    receipt.releaseCalledAtMonotonicNanos,
                    receipt.releaseCompletedAtMonotonicNanos,
                    receipt.releaseTargetMonotonicNanos,
                    receipt.expectedSurfaceTextureTimestampNanos,
                    timelineIdRawBits = receipt.timelineIdRawBits,
                )
            ) {
                synchronized(lock) {
                    if (!closed) {
                        knownReleases[FrameIdentity(
                            receipt.handle, receipt.surface,
                            receipt.surfaceLeaseGeneration, receipt.authenticatedFrameCounter,
                        )] = Unit
                        while (knownReleases.size > MAXIMUM_KNOWN_RELEASES) {
                            knownReleases.remove(knownReleases.keys.first())
                        }
                    }
                }
            }
        } ?: return
        synchronized(lock) {
            if (!closed && videoObserver == null) videoObserver = attached
            else attached.close()
        }
    }

    private fun drainMatchedFrames() {
        val pending = ArrayDeque<PendingEgl>()
        while (!Thread.currentThread().isInterrupted) {
            if (synchronized(lock) { closed }) return
            val snapshots = snapshotMailbox.drain()
            snapshots.forEach { offer ->
                acceptType2(offer.displayId, offer.token, offer.snapshot)
            }
            if (current()) {
                attachVideoObserverIfCurrent()
                if (clusterToken != null) {
                    val captured = runCatching { child.coordinator.drainMatchedVideoFrames() }
                        .getOrDefault(emptyList())
                    val expires = System.nanoTime() + MATCH_WAIT_NANOS
                    captured.forEach { receipt ->
                        if (pending.size == MAXIMUM_PENDING_MATCHES) pending.removeFirst()
                        pending.addLast(PendingEgl(receipt, expires))
                    }
                    val count = pending.size
                    for (index in 0 until count) {
                        if (!current()) {
                            pending.clear()
                            break
                        }
                        val candidate = pending.removeFirst()
                        val receipt = candidate.receipt
                        val key = FrameIdentity(
                            receipt.handle, receipt.surface,
                            receipt.surfaceLeaseGeneration, receipt.authenticatedFrameCounter,
                        )
                        val releaseKnown = synchronized(lock) {
                            knownReleases.remove(key) != null
                        }
                        if (releaseKnown) {
                            capture.recordEglMatched(
                                session.sessionIdentity, clusterToken,
                                receipt.handle, receipt.surface,
                                receipt.surfaceLeaseGeneration,
                                receipt.eglIngressGeneration, receipt.eglUpdateSequence,
                                receipt.surfaceTextureTimestampNanos,
                                when (receipt.timestampBasis) {
                                    RemoteFrameTimestampBasis.CODEC_OUTPUT_PTS ->
                                        BenchTimingCapture.TimestampBasis.CODEC_OUTPUT_PTS
                                    RemoteFrameTimestampBasis.TIMED_CODEC_RELEASE ->
                                        BenchTimingCapture.TimestampBasis.TIMED_CODEC_RELEASE
                                },
                                receipt.authenticatedFrameCounter,
                                receipt.presentationTimeNtp64Bits,
                                receipt.codecOutputPresentationTimeUs,
                            )
                        } else if (System.nanoTime() < candidate.expiresAtNanos) {
                            pending.addLast(candidate)
                        }
                    }
                }
            } else {
                pending.clear()
                if (capture.snapshot().state == BenchTimingCapture.State.REVOKED) return
            }
            capture.noteObserverSnapshotDrops(
                session.sessionIdentity, snapshotMailbox.droppedCount,
            )
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    override fun close() {
        val captured = synchronized(lock) {
            closed = true
            knownReleases.clear()
            snapshotMailbox.close()
            worker?.interrupt()
            videoObserver.also { videoObserver = null }
        }
        BenchTimingCaptureCatalog.retire(this)
        capture.close()
        runCatching { captured?.close() }
        val owned = synchronized(lock) { subscriptions.toList() }
        owned.asReversed().forEach { subscription ->
            // A failed diagnostic detach must never block Host/session retirement. The hub
            // itself is closed immediately after this uncounted binding, and late callbacks
            // see closed=true and cannot write or re-publish the recorder.
            runCatching { subscription.close() }
        }
        synchronized(lock) { subscriptions.clear() }
    }

    companion object {
        private const val POLL_MILLIS = 50L
        private const val MATCH_WAIT_NANOS = 500_000_000L
        private const val MAXIMUM_PENDING_MATCHES = 64
        private const val MAXIMUM_KNOWN_RELEASES = 128

        fun open(
            session: UiSyncReadOnlySessionContext,
            authority: AuthorizedAndroidUiSyncPhysicalOutputAuthority,
            displayIds: List<String>,
            clusterDisplayId: String?,
        ): BenchTimingCaptureBinding? {
            val child = session.rendererChild as? StaticThemeRendererSession ?: return null
            if (session.status != UiSyncReadOnlySessionStatus.CURRENT ||
                !authority.ownsBoundBatch(child, session.physicalBatch) ||
                displayIds.isEmpty() || displayIds.size != displayIds.toSet().size ||
                clusterDisplayId != null && clusterDisplayId !in displayIds
            ) return null
            val created = BenchTimingCaptureBinding(
                session, child, authority, displayIds, clusterDisplayId,
            )
            try {
                created.start()
                if (!BenchTimingCaptureCatalog.publish(created)) {
                    created.close()
                    return null
                }
                return created
            } catch (_: Throwable) {
                runCatching { created.close() }
                return null
            }
        }
    }
}
