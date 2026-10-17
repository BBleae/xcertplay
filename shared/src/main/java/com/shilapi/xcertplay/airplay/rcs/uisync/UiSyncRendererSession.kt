package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import java.io.Closeable
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.IdentityHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Profile-authored mapping to one renderer ingress namespace. A UUID-bound selector permits the
 * phone to choose `streamConnectionID` at SETUP, but the UUID must be the exact `/info` display
 * UUID validated on the session-bound handle. Without [setupDisplayUuid], the former exact wire
 * identity behavior is preserved.
 */
data class UiSyncRendererIngressSpec(
    val id: UltraIngressId,
    val airPlayStreamIdentity: UltraAirPlayStreamIdentity,
    val setupDisplayUuid: String? = null,
) {
    init {
        require(
            airPlayStreamIdentity.streamConnectionId != null ||
                airPlayStreamIdentity.clientTypeUuid != null || setupDisplayUuid != null,
        ) { "UI Sync renderer ingress selectors must not be type-only" }
        setupDisplayUuid?.let { uuid ->
            require(airPlayStreamIdentity.clientTypeUuid == null && (
                airPlayStreamIdentity.type == 110 && uuid == AirPlayInfoPlist.MAIN_UUID ||
                    airPlayStreamIdentity.type == 111 && uuid == AirPlayInfoPlist.ALT_UUID
                )
            ) { "UUID-bound video ingress must name its type's advertised display UUID" }
        }
    }

    /** Matches only a session-bound handle with a validated display UUID, when one is required. */
    fun matches(handle: AirPlayVideoStreamHandle): Boolean {
        val actual = handle.identity.toUltraIdentity()
        val uuid = setupDisplayUuid ?: return airPlayStreamIdentity == actual
        return actual.type == airPlayStreamIdentity.type &&
            actual.clientTypeUuid == null &&
            handle.validatedDisplayUuid == uuid &&
            (airPlayStreamIdentity.streamConnectionId == null ||
                airPlayStreamIdentity.streamConnectionId == actual.streamConnectionId)
    }

    internal fun overlaps(other: UiSyncRendererIngressSpec): Boolean {
        if (airPlayStreamIdentity.type != other.airPlayStreamIdentity.type) return false
        val firstUuid = setupDisplayUuid
        val secondUuid = other.setupDisplayUuid
        if (firstUuid == null && secondUuid == null) {
            return airPlayStreamIdentity == other.airPlayStreamIdentity
        }
        if (firstUuid != null && secondUuid != null && firstUuid != secondUuid) return false
        val exact = if (firstUuid == null) this else if (secondUuid == null) other else null
        if (exact != null && exact.airPlayStreamIdentity.clientTypeUuid != null) return false
        val firstId = airPlayStreamIdentity.streamConnectionId
        val secondId = other.airPlayStreamIdentity.streamConnectionId
        return firstId == null || secondId == null || firstId == secondId
    }
}

/**
 * Static V2 renderer resources. Output and ingress cardinalities are independent: outputs may be
 * local-only, an ingress may fan out through scenes to several outputs, and no output is forced to
 * have a remote stream. UI Sync coverage is validated by provisioning, not by string joins.
 */
class UiSyncRendererStaticPlan(
    outputs: Collection<UltraDisplayGraphOutput>,
    ingressSpecs: Collection<UiSyncRendererIngressSpec>,
) {
    val outputs: List<UltraDisplayGraphOutput> = boundedSnapshot(
        outputs,
        MAXIMUM_OUTPUTS,
        "UI Sync renderer outputs",
    )
    val ingressSpecs: List<UiSyncRendererIngressSpec> = boundedSnapshot(
        ingressSpecs,
        MAXIMUM_INGRESSES,
        "UI Sync renderer ingress specs",
    )

    init {
        require(this.outputs.map(UltraDisplayGraphOutput::outputId).toSet().size == this.outputs.size) {
            "UI Sync renderer plan contains duplicate output IDs"
        }
        require(
            this.outputs.map(UltraDisplayGraphOutput::topologyDisplayId).toSet().size ==
                this.outputs.size,
        ) { "UI Sync renderer plan contains duplicate topology display IDs" }
        val uiSyncIds = this.outputs.mapNotNull(UltraDisplayGraphOutput::uiSyncDisplayId)
        require(uiSyncIds.toSet().size == uiSyncIds.size) {
            "UI Sync renderer plan contains duplicate UI Sync display IDs"
        }
        require(this.ingressSpecs.map(UiSyncRendererIngressSpec::id).toSet().size ==
            this.ingressSpecs.size
        ) { "UI Sync renderer plan contains duplicate ingress IDs" }
        this.ingressSpecs.forEachIndexed { index, first ->
            for (otherIndex in index + 1 until this.ingressSpecs.size) {
                require(!first.overlaps(this.ingressSpecs[otherIndex])) {
                    "UI Sync renderer plan contains ambiguous AirPlay ingress selectors"
                }
            }
        }
    }

    fun ingressFor(handle: AirPlayVideoStreamHandle): UiSyncRendererIngressSpec? =
        ingressSpecs.firstOrNull { it.matches(handle) }

    private companion object {
        const val MAXIMUM_OUTPUTS = 64
        const val MAXIMUM_INGRESSES = 128

        fun <T> boundedSnapshot(values: Collection<T>, maximum: Int, label: String): List<T> {
            val result = ArrayList<T>()
            val iterator = values.iterator()
            while (iterator.hasNext()) {
                require(result.size < maximum) { "$label exceed $maximum entries" }
                result += iterator.next()
            }
            return Collections.unmodifiableList(result)
        }
    }
}

/**
 * Static, screen-free inputs used to arm a renderer factory at provisioning time.
 *
 * There is deliberately no presentation source or video handle here. A capability acquired from
 * this context can only prove that it is able to create isolated renderer sessions later.
 */
class UiSyncRendererBindingContext internal constructor(
    val staticPlan: UiSyncRendererStaticPlan,
    val schemaFingerprint: UiSyncSchemaFingerprint,
    val bindingFingerprint: UiSyncBindingFingerprint,
    displayIds: Collection<String>,
) {
    val displayIds: Set<String> = boundedRendererDisplayIds(
        displayIds,
        "UI Sync renderer binding display IDs",
    )
}

/** Destination namespace for one manifest-assigned, independently physical output. */
enum class UiSyncPhysicalDestinationRole { MAIN, CLUSTER, HUD, AUXILIARY }

data class UiSyncPhysicalDestination(
    val role: UiSyncPhysicalDestinationRole,
    val instanceId: String = "default",
) {
    init {
        require(instanceId.isNotBlank() && instanceId.length <= 128 &&
            instanceId.none(Char::isISOControl)) {
            "UI Sync physical destination instance ID is invalid"
        }
    }
}

/** Exact signed-manifest output row; opaque identifier text is never used to infer a route. */
data class UiSyncPhysicalOutputAssignment(
    val output: UltraDisplayGraphOutput,
    val destination: UiSyncPhysicalDestination,
)

/** A Host callback's exact target version, not just a logical output or preview window. */
data class UiSyncPhysicalTargetVersion(
    val targetId: String,
    val generation: Long,
) {
    init {
        require(targetId.isNotBlank() && targetId.length <= 128 &&
            targetId.none(Char::isISOControl) && generation > 0) {
            "UI Sync physical target version is invalid"
        }
    }
}

data class UiSyncPhysicalOutputRoute(
    val assignment: UiSyncPhysicalOutputAssignment,
    val target: UiSyncPhysicalTargetVersion,
)

/** Canonical hash of one verified Profile identity and its typed physical-output assignments. */
class UiSyncPhysicalAssignmentFingerprint internal constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init { require(value.size == 32) }

    fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is UiSyncPhysicalAssignmentFingerprint &&
            MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "UiSyncPhysicalAssignmentFingerprint(<redacted>)"
}

/** Expected verified Profile digest; the product manifest issuer must match it independently. */
class UiSyncPhysicalOutputRequirement(
    val verifiedProfileDigest: UltraProfileDigest,
    assignments: Collection<UiSyncPhysicalOutputAssignment>,
) {
    val assignments: List<UiSyncPhysicalOutputAssignment> =
        boundedPhysicalSnapshot(assignments, "UI Sync physical assignments")
    val assignmentFingerprint: UiSyncPhysicalAssignmentFingerprint

    init {
        require(this.assignments.isNotEmpty()) { "UI Sync physical assignments are empty" }
        require(this.assignments.map { it.output.outputId }.toSet().size ==
            this.assignments.size &&
            this.assignments.map { it.destination }.toSet().size == this.assignments.size
        ) { "UI Sync physical assignments contain duplicate outputs or destinations" }
        assignmentFingerprint = fingerprintPhysicalAssignments(
            verifiedProfileDigest, this.assignments,
        )
    }

    internal fun matchesPlan(plan: UiSyncRendererStaticPlan): Boolean {
        if (assignments.size != plan.outputs.size) return false
        val byId = plan.outputs.associateBy { it.outputId }
        return assignments.all { byId[it.output.outputId] === it.output }
    }

    internal fun matchesRoutes(routes: List<UiSyncPhysicalOutputRoute>): Boolean {
        if (routes.size != assignments.size ||
            routes.map { it.target }.toSet().size != routes.size ||
            routes.map { it.assignment.output.outputId }.toSet().size != routes.size
        ) return false
        val expected = assignments.associateBy { it.output.outputId }
        return routes.all { route ->
            expected[route.assignment.output.outputId]?.let { row ->
                route.assignment.output === row.output &&
                    route.assignment.destination == row.destination
            } == true
        }
    }

    override fun toString(): String =
        "UiSyncPhysicalOutputRequirement(<redacted>, outputs=${assignments.size})"
}

private fun fingerprintPhysicalAssignments(
    profileDigest: UltraProfileDigest,
    assignments: List<UiSyncPhysicalOutputAssignment>,
): UiSyncPhysicalAssignmentFingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(bytes: ByteArray) {
        val length = bytes.size
        digest.update(byteArrayOf(
            (length ushr 24).toByte(), (length ushr 16).toByte(),
            (length ushr 8).toByte(), length.toByte(),
        ))
        digest.update(bytes)
    }
    fun field(value: String) = field(value.toByteArray(StandardCharsets.UTF_8))
    field("xcertplay-ui-sync-physical-assignments-v1")
    field(profileDigest.bytes())
    field(assignments.size.toString())
    assignments.sortedWith(compareBy(
        { if (it.output.outputId is UltraDisplayOutputId.Android) 0 else 1 },
        { it.output.outputId.value },
    )).forEach { row ->
        field(if (row.output.outputId is UltraDisplayOutputId.Android) "android" else "custom")
        field(row.output.outputId.value)
        field(row.output.topologyDisplayId.value.toString())
        field(if (row.output.uiSyncDisplayId == null) "absent" else "present")
        row.output.uiSyncDisplayId?.let { field(it.value) }
        field(row.destination.role.name)
        field(row.destination.instanceId)
    }
    return UiSyncPhysicalAssignmentFingerprint(digest.digest())
}

/** The exact physical generation is usable, temporarily changing, or permanently retired. */
enum class UiSyncPhysicalGenerationStatus { CURRENT, BUSY, REVOKED }

/** Platform batch owner must re-evaluate EGL/router generation; close is retry-safe. */
interface UiSyncPhysicalOutputBackendLease : Closeable {
    val generation: Long
    /** True only while the exact Coordinator/EGL/router batch is current and not uncertain. */
    val isCurrent: Boolean
    /** Old implementations remain fail-closed; only an explicit backend may report BUSY. */
    val generationStatus: UiSyncPhysicalGenerationStatus
        get() = if (isCurrent) UiSyncPhysicalGenerationStatus.CURRENT
        else UiSyncPhysicalGenerationStatus.REVOKED
}

/** Live Host lease for one actual physical target version, never an in-window preview. */
interface UiSyncPhysicalTargetLease {
    val version: UiSyncPhysicalTargetVersion
    val isCurrent: Boolean
}

/** Exact process-wide ownership; neither a backend nor Host target lease may span live sessions. */
private object UiSyncPhysicalLeaseIdentityRegistry {
    private val lock = Any()
    private val backends = IdentityHashMap<UiSyncPhysicalOutputBackendLease, UiSyncPhysicalOutputBatch>()
    private val targets = IdentityHashMap<UiSyncPhysicalTargetLease, UiSyncPhysicalOutputBatch>()
    private val targetVersions = HashMap<UiSyncPhysicalTargetVersion, UiSyncPhysicalOutputBatch>()

    fun claim(
        batch: UiSyncPhysicalOutputBatch,
        backend: UiSyncPhysicalOutputBackendLease,
        targetLeases: List<UiSyncPhysicalTargetLease>,
        routes: List<UiSyncPhysicalOutputRoute>,
    ) = synchronized(lock) {
        require(!backends.containsKey(backend)) {
            "This exact UI Sync physical backend lease is already owned"
        }
        require(targetLeases.none { targets.containsKey(it) }) {
            "An exact UI Sync Host target lease is already owned"
        }
        require(routes.none { targetVersions.containsKey(it.target) }) {
            "A UI Sync Host target version is already owned"
        }
        backends[backend] = batch
        targetLeases.forEach { targets[it] = batch }
        routes.forEach { targetVersions[it.target] = batch }
    }

    fun release(
        batch: UiSyncPhysicalOutputBatch,
        backend: UiSyncPhysicalOutputBackendLease,
        targetLeases: List<UiSyncPhysicalTargetLease>,
        routes: List<UiSyncPhysicalOutputRoute>,
    ) = synchronized(lock) {
        check(backends[backend] === batch && targetLeases.all { targets[it] === batch } &&
            routes.all { targetVersions[it.target] === batch }
        ) {
            "UI Sync physical batch identity claim changed before retirement"
        }
        backends.remove(backend)
        targetLeases.forEach(targets::remove)
        routes.forEach { targetVersions.remove(it.target) }
    }
}

/** Availability only: the Host has usable physical targets; no output is attached yet. */
class UiSyncPhysicalOutputAvailability private constructor(
    internal val challenge: Any,
    internal val requirement: UiSyncPhysicalOutputRequirement,
    val routes: List<UiSyncPhysicalOutputRoute>,
    private val currentProbe: () -> Boolean,
) {
    val isCurrent: Boolean
        get() = runCatching { currentProbe() }.getOrDefault(false)

    internal companion object {
        fun issue(
            challenge: Any,
            requirement: UiSyncPhysicalOutputRequirement,
            routes: Collection<UiSyncPhysicalOutputRoute>,
            currentProbe: () -> Boolean,
        ): UiSyncPhysicalOutputAvailability {
            val frozen = boundedPhysicalSnapshot(routes, "UI Sync physical preflight routes")
            require(requirement.matchesRoutes(frozen)) {
                "UI Sync physical preflight does not cover exact manifest assignments"
            }
            return UiSyncPhysicalOutputAvailability(
                challenge, requirement, frozen, currentProbe,
            )
        }
    }
}

/** A fresh session owns the exact batch lease; it is never issued by a renderer receipt. */
class UiSyncPhysicalOutputBatch private constructor(
    internal val challenge: Any,
    internal val requirement: UiSyncPhysicalOutputRequirement,
    val routes: List<UiSyncPhysicalOutputRoute>,
    private val backend: UiSyncPhysicalOutputBackendLease,
    private val targets: List<UiSyncPhysicalTargetLease>,
    val generation: Long,
) : Closeable {
    private val closeRequested = AtomicBoolean(false)
    private val closeLock = Any()
    private var closeInFlight = false
    private var closeSucceeded = false
    val generationStatus: UiSyncPhysicalGenerationStatus
        get() {
            if (closeRequested.get()) return UiSyncPhysicalGenerationStatus.REVOKED
            // Host target retirement is terminal even while the EGL/backend operation is BUSY.
            return runCatching {
                if (backend.generation != generation || targets.indices.any { index ->
                        !targets[index].isCurrent || targets[index].version != routes[index].target
                    }
                ) UiSyncPhysicalGenerationStatus.REVOKED
                else backend.generationStatus
            }.getOrDefault(UiSyncPhysicalGenerationStatus.REVOKED)
        }

    val isCurrent: Boolean
        get() = generationStatus == UiSyncPhysicalGenerationStatus.CURRENT

    override fun close() {
        synchronized(closeLock) {
            if (closeSucceeded) return
            check(!closeInFlight) { "UI Sync physical batch retirement is already in flight" }
            closeInFlight = true
        }
        closeRequested.set(true)
        val retirement = runCatching {
            backend.close()
            UiSyncPhysicalLeaseIdentityRegistry.release(this, backend, targets, routes)
        }
        synchronized(closeLock) {
            closeInFlight = false
            if (retirement.isSuccess) closeSucceeded = true
        }
        retirement.getOrThrow()
    }

    internal companion object {
        fun issue(
            challenge: Any,
            requirement: UiSyncPhysicalOutputRequirement,
            backend: UiSyncPhysicalOutputBackendLease,
            routes: Collection<UiSyncPhysicalOutputRoute>,
            targetLeases: Collection<UiSyncPhysicalTargetLease>,
        ): UiSyncPhysicalOutputBatch {
            val frozen = boundedPhysicalSnapshot(routes, "UI Sync physical batch routes")
            val frozenTargets = boundedPhysicalSnapshot(
                targetLeases, "UI Sync physical target leases",
            )
            val capturedGeneration = backend.generation
            require(requirement.matchesRoutes(frozen) && capturedGeneration > 0) {
                "UI Sync physical batch does not cover exact manifest assignments"
            }
            require(frozenTargets.size == frozen.size &&
                frozenTargets.indices.all { index ->
                    frozenTargets[index].version == frozen[index].target
                }
            ) { "UI Sync physical target leases do not match the batch routes" }
            val identities = IdentityHashMap<UiSyncPhysicalTargetLease, Unit>()
            require(frozenTargets.all { identities.put(it, Unit) == null }) {
                "UI Sync physical target lease was reused for multiple outputs"
            }
            val batch = UiSyncPhysicalOutputBatch(
                challenge, requirement, frozen, backend, frozenTargets, capturedGeneration,
            )
            UiSyncPhysicalLeaseIdentityRegistry.claim(
                batch, backend, frozenTargets, frozen,
            )
            return batch
        }
    }
}

/** Context is created by provisioning, not the renderer, for one static Host preflight. */
class UiSyncPhysicalOutputPreflightContext internal constructor(
    val requirement: UiSyncPhysicalOutputRequirement,
    val staticPlan: UiSyncRendererStaticPlan,
    val schemaFingerprint: UiSyncSchemaFingerprint,
    val bindingFingerprint: UiSyncBindingFingerprint,
) {
    private val challenge = Any()

    fun issueAvailability(
        routes: Collection<UiSyncPhysicalOutputRoute>,
        currentProbe: () -> Boolean,
    ): UiSyncPhysicalOutputAvailability = UiSyncPhysicalOutputAvailability.issue(
        challenge, requirement, routes, currentProbe,
    )

    internal fun issued(value: UiSyncPhysicalOutputAvailability?): Boolean =
        value?.challenge === challenge && value.requirement === requirement
}

/**
 * Fresh one-session challenge and the exact renderer child validated for this session.
 *
 * [rendererSession] is evidence plumbing, not a new trust root. The fixed product authority must
 * compare it by reference (`===`) with its own per-provisioning renderer registry before routing
 * an output. It must never accept matching fingerprints, a receipt, or this context alone as a
 * substitute for that independent ownership check.
 */
class UiSyncPhysicalOutputSessionContext internal constructor(
    val requirement: UiSyncPhysicalOutputRequirement,
    val staticPlan: UiSyncRendererStaticPlan,
    val schemaFingerprint: UiSyncSchemaFingerprint,
    val bindingFingerprint: UiSyncBindingFingerprint,
    val rendererSession: UiSyncCompleteRendererSessionLease,
) {
    private val challenge = Any()

    fun issueActiveBatch(
        backend: UiSyncPhysicalOutputBackendLease,
        routes: Collection<UiSyncPhysicalOutputRoute>,
        targetLeases: Collection<UiSyncPhysicalTargetLease>,
    ): UiSyncPhysicalOutputBatch = UiSyncPhysicalOutputBatch.issue(
        challenge, requirement, backend, routes, targetLeases,
    )

    internal fun issued(value: UiSyncPhysicalOutputBatch?): Boolean =
        value?.challenge === challenge && value.requirement === requirement
}

/** Result of one Host cover attempt for the exact CLUSTER phone epoch, not a frame receipt. */
enum class UiSyncClusterPhoneEpochCoverResult {
    ACKNOWLEDGED,
    RETRY,
    SUPERSEDED,
    REVOKED,
}

/** Phase 1 has already revoked old permits; this ticket alone may request its exact Host ACK. */
interface UiSyncClusterPhoneEpochCoverTicket {
    fun coverExactClusterUi(): UiSyncClusterPhoneEpochCoverResult
}

/**
 * Product wiring supplies only a fixed manifest-checked Host+EGL implementation; null stays off.
 * This public shared-module interface is evidence plumbing, not an in-process security boundary:
 * a caller-created fake authority must never be installed by the product composition root.
 * If [bindSession] throws after touching resources, the authority retains their retryable cleanup;
 * a successfully returned batch transfers exactly one ownership claim to the provisioned session.
 */
interface UiSyncPhysicalOutputAuthority {
    fun preflight(context: UiSyncPhysicalOutputPreflightContext):
        UiSyncPhysicalOutputAvailability?

    fun bindSession(context: UiSyncPhysicalOutputSessionContext): UiSyncPhysicalOutputBatch?

    /**
     * Nonblocking phase 1 for a phone Reset on one physical CLUSTER display. The authority must
     * synchronously revoke that display's old reveal permits before returning its exact, frozen
     * Host-cover ticket. A null result fails closed. The ticket is completed off the RCS callback
     * and must never retire the batch, MAIN, or the recoverable external UI cover on success.
     */
    fun beginClusterPhoneEpochCover(
        context: UiSyncPhysicalOutputSessionContext,
        batch: UiSyncPhysicalOutputBatch,
        displayId: String,
    ): UiSyncClusterPhoneEpochCoverTicket? = null

    /**
     * Nonblocking phase 1 of an authenticated ClusterControl stream stop. Revoke all previously
     * issued MAIN/CLUSTER reveal permits for this exact batch before any asynchronous Host cover.
     * A physical authority without this capability fails closed at channel termination.
     */
    fun revokeBoundSessionForStop(
        context: UiSyncPhysicalOutputSessionContext,
        batch: UiSyncPhysicalOutputBatch,
    ): Boolean = false

    /**
     * Synchronously request UI-layer cover for type-111 external CLUSTER targets in this exact
     * bound session and issued batch before child quiescence can block. True acknowledges that
     * the Host applied every required external cover, or that this batch has no external target.
     * It does not prove MAIN visual cover, optical obscuration, or vehicle hardware blanking.
     * An authority without this capability denies the request.
     */
    fun coverBoundSessionForClose(
        context: UiSyncPhysicalOutputSessionContext,
        batch: UiSyncPhysicalOutputBatch,
    ): Boolean = false

    /**
     * Cover MAIN for one exact type-110 stream without retiring or revoking the UI Sync batch.
     * A fresh signed scene revision and EGL producer timestamp are required before reveal.
     */
    fun coverMainForExactScreenTeardown(
        context: UiSyncPhysicalOutputSessionContext,
        batch: UiSyncPhysicalOutputBatch,
        stream: AirPlayVideoStreamHandle,
    ): Boolean = false

    /**
     * Starts the exact renderer child only after its physical batch has been transferred to the
     * provisioned session. The authority must independently verify its own child/batch registry;
     * the caller's context and challenge are evidence, not authorization by themselves.
     *
     * A pending input stream is normal before AirPlay SETUP. Throwing leaves the provisioned
     * session as the sole retryable cleanup owner, with renderer retirement before batch release.
     */
    fun activateBoundSession(
        context: UiSyncPhysicalOutputSessionContext,
        batch: UiSyncPhysicalOutputBatch,
    ): UiSyncPhysicalActivationResult =
        throw IllegalStateException("UI Sync physical output authority cannot activate a bound session")
}

/** A submitted scene or a valid session awaiting exact inputs/evidence for a signed scene. */
enum class UiSyncPhysicalActivationResult {
    SUBMITTED,
    PENDING_INPUT_STREAM,
    PENDING_LOCAL_CANVAS,
}

private fun <T> boundedPhysicalSnapshot(values: Collection<T>, label: String): List<T> {
    val result = ArrayList<T>()
    val iterator = values.iterator()
    while (iterator.hasNext()) {
        require(result.size < 64) { "$label exceed 64 entries" }
        result += iterator.next()
    }
    return Collections.unmodifiableList(result)
}

/**
 * Immutable, challenge-bound evidence for one exact complete renderer instance.
 *
 * The constructor is private. A receipt can only be issued by one of the complete contexts below,
 * which fixes the schema, binding and display identities and contributes an opaque freshness
 * challenge. The renderer supplies only the facts which can vary with its actual implementation.
 */
class RendererReadinessReceipt private constructor(
    internal val challenge: Any,
    val schemaFingerprint: UiSyncSchemaFingerprint,
    val bindingFingerprint: UiSyncBindingFingerprint,
    val semanticThemeProgramFingerprint: UiSyncSemanticThemeProgramFingerprint,
    displayIds: Collection<String>,
    subscribedDisplayIds: Collection<String>,
    boundOutputIds: Collection<UltraDisplayOutputId>,
    val semanticCompilerActive: Boolean,
    val themeCompilerActive: Boolean,
    val backendLive: Boolean,
    val phase: Phase,
) {
    enum class Phase { STATIC_FACTORY, SESSION_IMPLEMENTATION }
    val displayIds: Set<String> = boundedUniqueSet(
        displayIds,
        MAXIMUM_DISPLAY_IDS,
        "renderer receipt display IDs",
    )
    val subscribedDisplayIds: Set<String> =
        boundedUniqueSet(
            subscribedDisplayIds,
            MAXIMUM_DISPLAY_IDS,
            "renderer receipt subscribed display IDs",
        )
    val boundOutputIds: Set<UltraDisplayOutputId> = boundedUniqueSet(
        boundOutputIds,
        MAXIMUM_OUTPUT_IDS,
        "renderer receipt bound output IDs",
    )

    /** Challenge-bearing issuer available only to an explicitly complete factory implementation. */
    class CompleteBindingContext internal constructor(
        val rendererContext: UiSyncRendererBindingContext,
    ) {
        private val readinessChallenge = Any()

        val staticPlan: UiSyncRendererStaticPlan
            get() = rendererContext.staticPlan
        val schemaFingerprint: UiSyncSchemaFingerprint
            get() = rendererContext.schemaFingerprint
        val bindingFingerprint: UiSyncBindingFingerprint
            get() = rendererContext.bindingFingerprint
        val displayIds: Set<String>
            get() = rendererContext.displayIds

        /** Issues static implementation evidence; legacy dynamic arguments are ignored. */
        fun issueReadinessReceipt(
            semanticThemeProgramFingerprint: UiSyncSemanticThemeProgramFingerprint,
            @Suppress("UNUSED_PARAMETER")
            subscribedDisplayIds: Collection<String>,
            @Suppress("UNUSED_PARAMETER")
            boundOutputIds: Collection<UltraDisplayOutputId>,
            semanticCompilerActive: Boolean,
            themeCompilerActive: Boolean,
            backendLive: Boolean,
        ): RendererReadinessReceipt = RendererReadinessReceipt(
            challenge = readinessChallenge,
            schemaFingerprint = schemaFingerprint,
            bindingFingerprint = bindingFingerprint,
            semanticThemeProgramFingerprint = semanticThemeProgramFingerprint,
            displayIds = displayIds,
            // No presentation source or physical output exists at factory acquisition. These
            // source-compatible legacy arguments cannot upgrade a static proof into ACTIVE.
            subscribedDisplayIds = emptySet(),
            boundOutputIds = emptySet(),
            semanticCompilerActive = semanticCompilerActive,
            themeCompilerActive = themeCompilerActive,
            backendLive = backendLive,
            phase = Phase.STATIC_FACTORY,
        )

        internal fun accepts(receipt: RendererReadinessReceipt?): Boolean =
            receipt?.challenge === readinessChallenge
    }

    /** Fresh challenge-bearing issuer for one exact renderer-session generation. */
    class CompleteSessionBindingContext internal constructor(
        val rendererContext: UiSyncRendererSessionBindingContext,
    ) {
        private val readinessChallenge = Any()

        val presentationSource: UiSyncPresentationSource
            get() = rendererContext.presentationSource
        val staticPlan: UiSyncRendererStaticPlan
            get() = rendererContext.staticPlan
        val schemaFingerprint: UiSyncSchemaFingerprint
            get() = rendererContext.schemaFingerprint
        val bindingFingerprint: UiSyncBindingFingerprint
            get() = rendererContext.bindingFingerprint
        val displayIds: Set<String>
            get() = rendererContext.displayIds

        /** An exact-session, typed outbox; no request is made by merely opening this context. */
        val foregroundRequests: UiSyncForegroundRequestPort
            get() = rendererContext.foregroundRequests

        /** Issues implementation evidence; ACTIVE is checked by observed Hub and Host leases. */
        fun issueReadinessReceipt(
            semanticThemeProgramFingerprint: UiSyncSemanticThemeProgramFingerprint,
            @Suppress("UNUSED_PARAMETER")
            subscribedDisplayIds: Collection<String>,
            @Suppress("UNUSED_PARAMETER")
            boundOutputIds: Collection<UltraDisplayOutputId>,
            semanticCompilerActive: Boolean,
            themeCompilerActive: Boolean,
            backendLive: Boolean,
        ): RendererReadinessReceipt = RendererReadinessReceipt(
            challenge = readinessChallenge,
            schemaFingerprint = schemaFingerprint,
            bindingFingerprint = bindingFingerprint,
            semanticThemeProgramFingerprint = semanticThemeProgramFingerprint,
            displayIds = displayIds,
            subscribedDisplayIds = emptySet(),
            boundOutputIds = emptySet(),
            semanticCompilerActive = semanticCompilerActive,
            themeCompilerActive = themeCompilerActive,
            backendLive = backendLive,
            phase = Phase.SESSION_IMPLEMENTATION,
        )

        internal fun accepts(receipt: RendererReadinessReceipt?): Boolean =
            receipt?.challenge === readinessChallenge
    }

    internal companion object {
        private const val MAXIMUM_DISPLAY_IDS = 64
        private const val MAXIMUM_OUTPUT_IDS = 64

        private fun <T> boundedUniqueSet(
            values: Collection<T>,
            maximum: Int,
            label: String,
        ): Set<T> {
            val result = LinkedHashSet<T>()
            val iterator = values.iterator()
            while (iterator.hasNext()) {
                require(result.size < maximum) { "$label exceed $maximum entries" }
                require(result.add(iterator.next())) { "$label contain a duplicate entry" }
            }
            return Collections.unmodifiableSet(result)
        }

    }
}

internal fun RendererReadinessReceipt?.matchesExactly(
    expected: RendererReadinessReceipt?,
): Boolean {
    if (this == null || expected == null) return this == null && expected == null
    return this === expected &&
        challenge === expected.challenge &&
        schemaFingerprint == expected.schemaFingerprint &&
        bindingFingerprint == expected.bindingFingerprint &&
        semanticThemeProgramFingerprint == expected.semanticThemeProgramFingerprint &&
        displayIds == expected.displayIds &&
        subscribedDisplayIds == expected.subscribedDisplayIds &&
        boundOutputIds == expected.boundOutputIds &&
        semanticCompilerActive == expected.semanticCompilerActive &&
        themeCompilerActive == expected.themeCompilerActive &&
        backendLive == expected.backendLive &&
        phase == expected.phase
}

/** Complete factory context; a base resource context deliberately has no receipt issuer. */
typealias UiSyncCompleteRendererBindingContext =
    RendererReadinessReceipt.CompleteBindingContext

/** Fresh presentation inputs for exactly one AirPlay control-session generation. */
class UiSyncRendererSessionBindingContext internal constructor(
    val presentationSource: UiSyncPresentationSource,
    val staticPlan: UiSyncRendererStaticPlan,
    val schemaFingerprint: UiSyncSchemaFingerprint,
    val bindingFingerprint: UiSyncBindingFingerprint,
    val foregroundRequests: UiSyncForegroundRequestPort,
    displayIds: Collection<String>,
) {
    /** Diagnostic/test compatibility path; only provisioning supplies a live outbox. */
    internal constructor(
        presentationSource: UiSyncPresentationSource,
        staticPlan: UiSyncRendererStaticPlan,
        schemaFingerprint: UiSyncSchemaFingerprint,
        bindingFingerprint: UiSyncBindingFingerprint,
        displayIds: Collection<String>,
    ) : this(
        presentationSource,
        staticPlan,
        schemaFingerprint,
        bindingFingerprint,
        REJECTING_FOREGROUND_REQUEST_PORT,
        displayIds,
    )

    val displayIds: Set<String> = boundedRendererDisplayIds(
        displayIds,
        "UI Sync renderer session display IDs",
    )
}

private val REJECTING_FOREGROUND_REQUEST_PORT = object : UiSyncForegroundRequestPort {
    override fun guardScene(
        displayId: String,
        source: UiSyncForegroundSceneUseSource,
    ): UiSyncForegroundSceneGuard? = null

    override fun requestLayout(
        displayId: String,
        command: CarPlayUiSyncAccessoryCommand.RequestLayout,
        scene: UiSyncForegroundSceneGuard,
    ): UiSyncForegroundRequestReceipt =
        UiSyncForegroundRequestReceipt(UiSyncForegroundRequestState.REJECTED)
}

/** Records real live subscriptions from the runtime-owned hub, never renderer-supplied IDs. */
internal class UiSyncObservedPresentationSource(
    private val delegate: UiSyncPresentationSource,
    displayIds: Set<String>,
) : UiSyncPresentationSource {
    private val expectedDisplayIds = displayIds.toSet()
    private val lock = java.lang.Object()
    private val active = LinkedHashMap<Any, String>()
    /** Rejected delegates stay reachable until their exact close succeeds. */
    private val pendingRejected = IdentityHashMap<Closeable, Unit>()
    private val callbackThreads = IdentityHashMap<Thread, Int>()
    private val subscriptionThreads = IdentityHashMap<Thread, Int>()
    private var callbacksInFlight = 0
    private var subscriptionsInFlight = 0
    private var rejectedRetirementInFlight = false
    private var revoked = false

    override fun subscribeWithReplay(
        displayId: String,
        observer: UltraPresentationObserver,
    ): UiSyncPresentationSubscription {
        require(displayId in expectedDisplayIds) {
            "UI Sync renderer subscribed an undeclared display"
        }
        val subscriptionThread = Thread.currentThread()
        synchronized(lock) {
            check(!revoked) { "UI Sync presentation source is revoked" }
            check(pendingRejected.isEmpty()) {
                "UI Sync rejected observer retirement is pending"
            }
            subscriptionsInFlight = Math.addExact(subscriptionsInFlight, 1)
            subscriptionThreads[subscriptionThread] =
                (subscriptionThreads[subscriptionThread] ?: 0) + 1
        }
        try {
            return subscribeAdmitted(displayId, observer)
        } finally {
            synchronized(lock) {
                subscriptionsInFlight -= 1
                val remaining = checkNotNull(subscriptionThreads[subscriptionThread]) - 1
                if (remaining == 0) subscriptionThreads.remove(subscriptionThread)
                else subscriptionThreads[subscriptionThread] = remaining
                lock.notifyAll()
            }
        }
    }

    private fun subscribeAdmitted(
        displayId: String,
        observer: UltraPresentationObserver,
    ): UiSyncPresentationSubscription {
        val token = Any()
        val observerFailed = AtomicBoolean(false)
        val observed = UltraPresentationObserver { snapshot ->
            val callbackThread = Thread.currentThread()
            val admitted = synchronized(lock) {
                if (revoked || observerFailed.get()) false else {
                    callbacksInFlight = Math.addExact(callbacksInFlight, 1)
                    callbackThreads[callbackThread] =
                        (callbackThreads[callbackThread] ?: 0) + 1
                    true
                }
            }
            if (!admitted) return@UltraPresentationObserver
            try {
                observer.onPresentationSnapshot(snapshot)
            } catch (failure: Throwable) {
                observerFailed.set(true)
                synchronized(lock) { active.remove(token) }
                throw failure
            } finally {
                synchronized(lock) {
                    callbacksInFlight -= 1
                    val remaining = checkNotNull(callbackThreads[callbackThread]) - 1
                    if (remaining == 0) callbackThreads.remove(callbackThread)
                    else callbackThreads[callbackThread] = remaining
                    lock.notifyAll()
                }
            }
        }
        val delegateSubscription = try {
            delegate.subscribeWithReplay(displayId, observed)
        } catch (retained: UiSyncRetainedPresentationObserverConstructionException) {
            synchronized(lock) { pendingRejected[retained.retainedObserverLease] = Unit }
            throw retained.cause ?: retained
        }
        if (delegateSubscription.initialSnapshot.closed) {
            rejectSubscription(
                delegateSubscription,
                "UI Sync renderer subscribed a closed presentation source",
            )
        }
        val accepted = synchronized(lock) {
            if (revoked || observerFailed.get()) false else {
                active[token] = displayId
                true
            }
        }
        if (!accepted) {
            rejectSubscription(delegateSubscription, "UI Sync presentation source was revoked")
        }
        return UiSyncPresentationSubscription(
            delegateSubscription.initialSnapshot,
            Closeable {
                synchronized(lock) { active.remove(token) }
                delegateSubscription.close()
            },
        )
    }

    private fun rejectSubscription(
        subscription: UiSyncPresentationSubscription,
        reason: String,
    ): Nothing {
        val rejection = IllegalStateException(reason)
        synchronized(lock) { pendingRejected[subscription] = Unit }
        try {
            subscription.close()
            synchronized(lock) { pendingRejected.remove(subscription) }
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== rejection) rejection.addSuppressed(cleanupFailure)
        }
        throw rejection
    }

    override fun snapshot(displayId: String): UltraPresentationSnapshot =
        delegate.snapshot(displayId)

    fun hasEveryLiveSubscription(): Boolean = synchronized(lock) {
        !revoked && subscriptionsInFlight == 0 && pendingRejected.isEmpty() &&
            active.values.toSet() == expectedDisplayIds
    }

    fun hasPendingRejectedCleanup(): Boolean = synchronized(lock) { pendingRejected.isNotEmpty() }

    /** Seal callbacks/construction and retry any exact rejected observer still owned here. */
    fun sealAndAwaitQuiescence() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val rejected = synchronized(lock) {
            revoked = true
            active.clear()
            check(callbackThreads[Thread.currentThread()] == null) {
                "UI Sync renderer callback cannot synchronously retire its own session"
            }
            check(subscriptionThreads[Thread.currentThread()] == null) {
                "UI Sync renderer subscription cannot synchronously retire its own session"
            }
            check(!rejectedRetirementInFlight) {
                "UI Sync rejected subscription retirement is already in flight"
            }
            rejectedRetirementInFlight = true
            try {
                while (callbacksInFlight != 0 || subscriptionsInFlight != 0) {
                    val remaining = deadline - System.nanoTime()
                    check(remaining > 0) {
                        "UI Sync renderer callback/subscription quiescence was not proven"
                    }
                    try {
                        lock.wait(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
                    } catch (failure: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IllegalStateException(
                            "UI Sync renderer callback/subscription quiescence was interrupted",
                            failure,
                        )
                    }
                }
                pendingRejected.keys.toList()
            } catch (failure: Throwable) {
                rejectedRetirementInFlight = false
                throw failure
            }
        }
        var failure: Throwable? = null
        rejected.forEach { subscription ->
            try {
                subscription.close()
                synchronized(lock) { pendingRejected.remove(subscription) }
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
            }
        }
        synchronized(lock) { rejectedRetirementInFlight = false }
        failure?.let { throw it }
    }
}

/** Complete session context with a fresh, non-exported receipt challenge. */
typealias UiSyncCompleteRendererSessionBindingContext =
    RendererReadinessReceipt.CompleteSessionBindingContext

/**
 * Process-wide exact-identity reservation for externally supplied renderer leases.
 *
 * Factory claims live until the exact factory closes successfully. Session identities use weak,
 * referential tombstones: a factory that can return the same object still strongly retains it and
 * is rejected, while otherwise unreachable closed sessions can be collected without linear growth.
 */
internal object UiSyncRendererLeaseIdentityRegistry {
    private class SessionIdentityReference(
        session: UiSyncCompleteRendererSessionLease,
        queue: ReferenceQueue<UiSyncCompleteRendererSessionLease>? = null,
    ) : WeakReference<UiSyncCompleteRendererSessionLease>(session, queue) {
        private val identityHash = System.identityHashCode(session)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SessionIdentityReference || identityHash != other.identityHash) {
                return false
            }
            val ours = get() ?: return false
            return ours === other.get()
        }
    }

    private class FactoryEntry(
        val token: Any,
        val sessions: MutableSet<SessionIdentityReference> = HashSet(),
    )

    private val lock = Any()
    private val factories = IdentityHashMap<UiSyncCompleteRendererFactoryLease, FactoryEntry>()
    private val sessionQueue = ReferenceQueue<UiSyncCompleteRendererSessionLease>()
    private val sessions = HashMap<SessionIdentityReference, FactoryEntry>()

    fun claimFactory(
        factory: UiSyncCompleteRendererFactoryLease,
    ): UiSyncRendererFactoryIdentityClaim = synchronized(lock) {
        require(!factories.containsKey(factory)) {
            "This exact UI Sync renderer factory lease is already owned"
        }
        val token = Any()
        factories[factory] = FactoryEntry(token)
        UiSyncRendererFactoryIdentityClaim(factory, token)
    }

    fun claimSession(
        factory: UiSyncCompleteRendererFactoryLease,
        token: Any,
        session: UiSyncCompleteRendererSessionLease,
    ) = synchronized(lock) {
        drainCollectedSessionsLocked()
        val entry = factories[factory]
        check(entry != null && entry.token === token) {
            "UI Sync renderer factory identity claim is not active"
        }
        val lookup = SessionIdentityReference(session)
        require(!sessions.containsKey(lookup)) {
            "This exact UI Sync renderer session lease is already owned by a factory lifetime"
        }
        val retained = SessionIdentityReference(session, sessionQueue)
        entry.sessions += retained
        sessions[retained] = entry
    }

    fun releaseFactory(
        factory: UiSyncCompleteRendererFactoryLease,
        token: Any,
    ): Unit = synchronized(lock) {
        drainCollectedSessionsLocked()
        val entry = factories[factory]
        check(entry != null && entry.token === token) {
            "UI Sync renderer factory identity claim is not active"
        }
        entry.sessions.forEach { session ->
            if (sessions[session] === entry) sessions.remove(session)
        }
        entry.sessions.clear()
        factories.remove(factory)
        Unit
    }

    internal fun sessionTombstoneCountForTests(): Int = synchronized(lock) {
        drainCollectedSessionsLocked()
        sessions.size
    }

    private fun drainCollectedSessionsLocked() {
        while (true) {
            val reference = sessionQueue.poll() as? SessionIdentityReference ?: return
            val entry = sessions.remove(reference) ?: continue
            entry.sessions.remove(reference)
        }
    }
}

internal class UiSyncRendererFactoryIdentityClaim internal constructor(
    private val factory: UiSyncCompleteRendererFactoryLease,
    private val token: Any,
) {
    private val lock = Any()
    private var released = false

    fun claimSession(session: UiSyncCompleteRendererSessionLease) {
        synchronized(lock) {
            check(!released) { "UI Sync renderer factory identity claim is released" }
            UiSyncRendererLeaseIdentityRegistry.claimSession(factory, token, session)
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            UiSyncRendererLeaseIdentityRegistry.releaseFactory(factory, token)
            released = true
        }
    }
}

/**
 * One armed renderer factory. It owns no screen handle and must create a new child for every
 * AirPlay control session. A successful [close] is the only proof that its resources retired;
 * implementations must leave the same instance retryable when close throws.
 */
interface UiSyncRendererFactoryLease : Closeable {
    val isLive: Boolean
    val schemaFingerprint: UiSyncSchemaFingerprint
    val bindingFingerprint: UiSyncBindingFingerprint
    val displayIds: Set<String>
    val implementationId: String

    fun openSession(context: UiSyncRendererSessionBindingContext): UiSyncRendererSessionLease
}

/**
 * Factory subtype required by the static eligibility path. A plain resource factory cannot
 * upgrade its lifetime/resource evidence into feature readiness.
 */
interface UiSyncCompleteRendererFactoryLease : UiSyncRendererFactoryLease {
    /** Must return the same immutable issuance for the whole unchanged live lease. */
    val readinessReceipt: RendererReadinessReceipt?

    fun openSession(
        context: UiSyncCompleteRendererSessionBindingContext,
    ): UiSyncCompleteRendererSessionLease

    override fun openSession(
        context: UiSyncRendererSessionBindingContext,
    ): UiSyncRendererSessionLease = throw UnsupportedOperationException(
        "A complete renderer session requires its challenge-bearing context",
    )
}

/** Source-compatible name for callers compiled against the earlier factory-shaped lease. */
typealias UiSyncRendererLease = UiSyncRendererFactoryLease

/** Explicit inputs for creating a private, not-yet-published remote-video ingress candidate. */
class UiSyncRendererScreenContext internal constructor(
    val stream: AirPlayVideoStreamHandle,
    val ingress: UltraDisplayGraphIngress,
) {
    val ingressId: UltraIngressId
        get() = ingress.id
}

/**
 * Renderer-owned candidate. [publish] may expose it to the renderer graph and is called only from
 * the SETUP response-flush commit hook. [close] must retire both unpublished and published state.
 */
interface UiSyncRendererScreenCandidate : Closeable {
    val stream: AirPlayVideoStreamHandle
    val ingressId: UltraIngressId
    fun publish(): Boolean
}

/**
 * Renderer state for exactly one AirPlay control session.
 *
 * The profile resolves an ingress in [UiSyncRendererStaticPlan] from a complete wire identity. The
 * runtime never guesses from stream type, display text, output text, or a partial identity.
 * A successful [close] is a quiescence fence for all renderer-owned semantic callbacks and draw
 * work: no worker may access a physical output after it returns. If that cannot be proven, close
 * must throw and leave the same session retryable; the output batch remains attached meanwhile.
 */
interface UiSyncRendererSessionLease : Closeable {
    val isLive: Boolean
    val schemaFingerprint: UiSyncSchemaFingerprint
    val bindingFingerprint: UiSyncBindingFingerprint
    val displayIds: Set<String>
    val implementationId: String

    fun prepareScreen(context: UiSyncRendererScreenContext): UiSyncRendererScreenCandidate?

    /** One bounded backend-wide retirement proof for every screen candidate in this session. */
    fun retireScreens(): Boolean
}

/** Session implementation proof; ACTIVE also needs observed subscriptions and physical leases. */
interface UiSyncCompleteRendererSessionLease : UiSyncRendererSessionLease {
    /** Must return the same immutable issuance for the whole unchanged live lease. */
    val readinessReceipt: RendererReadinessReceipt?
}

/** A mandatory synchronous phase-1 invalidation for a renderer with a physical UI Sync scene. */
fun interface UiSyncPhoneEpochObserver {
    /** Returns only after old scene permits have been revoked; Host covering may finish later. */
    fun invalidatePhoneEpoch(displayId: String, presentationRevisionFloor: Long): Boolean
}

/**
 * Transactional candidate presented to the media engine. External renderer calls never run while
 * this object's lock is held. A close failure leaves the exact same candidate available to retry.
 */
class UiSyncPreparedScreenBinding internal constructor(
    val stream: AirPlayVideoStreamHandle,
    val ingressId: UltraIngressId,
    private val delegate: UiSyncRendererScreenCandidate,
    private val sessionLive: () -> Boolean,
    private val onRetired: (UiSyncPreparedScreenBinding) -> Unit,
) : Closeable {
    private enum class Phase { PREPARED, PUBLISHED, RETIRED }

    private val lock = Any()
    private var phase = Phase.PREPARED
    private var operationInFlight = false
    private var closeRequested = false

    val isPublished: Boolean
        get() = synchronized(lock) { phase == Phase.PUBLISHED }

    fun commit(): Boolean {
        val alreadyPublished = synchronized(lock) {
            if (phase == Phase.RETIRED || closeRequested) return false
            if (phase == Phase.PUBLISHED) return@synchronized true
            check(!operationInFlight) { "UI Sync screen binding operation is already in flight" }
            operationInFlight = true
            false
        }
        if (alreadyPublished) {
            if (runCatching { sessionLive() }.getOrDefault(false)) return true
            close()
            return false
        }

        // The SETUP flush can be arbitrarily later than preparation. Do not publish a candidate
        // whose exact session, Host target, subscription, or backend batch was revoked meanwhile.
        val wasLiveBefore = runCatching { sessionLive() }.getOrDefault(false)
        val publication = if (wasLiveBefore) runCatching { delegate.publish() }
        else Result.success(false)
        val remainsLiveAfter = publication.getOrDefault(false) &&
            runCatching { sessionLive() }.getOrDefault(false)
        val mustRetire = synchronized(lock) {
            operationInFlight = false
            if (publication.getOrDefault(false)) phase = Phase.PUBLISHED
            closeRequested || !remainsLiveAfter
        }

        if (mustRetire) {
            try {
                close()
            } catch (closeFailure: Throwable) {
                publication.exceptionOrNull()?.let { failure ->
                    if (closeFailure !== failure) failure.addSuppressed(closeFailure)
                    throw failure
                }
                throw closeFailure
            }
        }
        publication.exceptionOrNull()?.let { throw it }
        return remainsLiveAfter && !mustRetire
    }

    override fun close() {
        synchronized(lock) {
            if (phase == Phase.RETIRED) return
            closeRequested = true
            check(!operationInFlight) {
                "UI Sync screen binding retirement raced an in-flight renderer operation"
            }
            operationInFlight = true
        }

        val retirement = runCatching { delegate.close() }
        val notify = synchronized(lock) {
            operationInFlight = false
            if (retirement.isSuccess) {
                phase = Phase.RETIRED
                true
            } else {
                false
            }
        }
        if (notify) onRetired(this)
        retirement.getOrThrow()
    }

    /** Consumes a successful session-wide renderer retirement proof without another external call. */
    internal fun retireFromSessionProof() {
        val notify = synchronized(lock) {
            if (phase == Phase.RETIRED) return
            closeRequested = true
            check(!operationInFlight) {
                "UI Sync screen binding proof raced an in-flight renderer operation"
            }
            phase = Phase.RETIRED
            true
        }
        if (notify) onRetired(this)
    }
}

/**
 * Runtime-owned UI Sync resources for one AirPlay session. No endpoint, event, observer, screen
 * binding, or renderer child is shared with another instance.
 */
internal class UiSyncProvisionedSession(
    private val presentationHub: UltraPresentationHub,
    internal val endpointRegistry: CarPlayUiSyncEndpointRegistry,
    internal val foregroundRequestOutbox: UiSyncForegroundRequestOutbox,
    private val staticPlan: UiSyncRendererStaticPlan?,
    private val schemaFingerprint: UiSyncSchemaFingerprint,
    private val bindingFingerprint: UiSyncBindingFingerprint,
    private val renderer: UiSyncCompleteRendererSessionLease?,
    rendererReadinessReceipt: RendererReadinessReceipt?,
    private val observedPresentationSource: UiSyncObservedPresentationSource?,
    private val requiresPhysicalBatch: Boolean,
    private val parentLive: () -> Boolean,
    private val onClosed: (UiSyncProvisionedSession) -> Unit,
) : Closeable, UiSyncForegroundRequestSessionAccess {
    private val lock = Any()
    /** Serializes Authority phase 1 and child invalidation without holding the session lock. */
    private val phoneEpochTransitionLock = Any()
    private val candidates = IdentityHashMap<UiSyncPreparedScreenBinding, Unit>()
    private val coveredMainScreenTeardowns = IdentityHashMap<AirPlayVideoStreamHandle, Unit>()
    private val handleReservations = IdentityHashMap<AirPlayVideoStreamHandle, Any>()
    private val ingressReservations = LinkedHashMap<UltraIngressId, Any>()
    private var activeUses = 0
    private var preparationsInFlight = 0
    private var closeRequested = false
    private var closeInFlight = false
    private var physicalBindInFlight = false
    private var hubClosed = false
    private var rendererClosed = renderer == null
    private var resourcesClosed = false
    private var emergencyCoverScheduled = false
    private var emergencyPermitsRevoked = false
    /** Only the latest exact Reset ticket for each CLUSTER may trigger retry or fallback. */
    private val clusterPhoneEpochCovers = LinkedHashMap<String, UiSyncClusterPhoneEpochCoverTicket>()
    private var clusterPhoneEpochCoverExecutor: ScheduledThreadPoolExecutor? = null
    private var rendererReadinessReceipt = rendererReadinessReceipt
    private var rendererReadinessArmed = renderer == null || rendererReadinessReceipt != null
    private var physicalBatch: UiSyncPhysicalOutputBatch? = null
    private var physicalBatchClosed = true
    private var physicalOutputAuthority: UiSyncPhysicalOutputAuthority? = null
    private var physicalOutputContext: UiSyncPhysicalOutputSessionContext? = null
    /** Optional browser observer; never part of observedPresentationSource readiness. */
    private var readOnlyPresentationBinding: Closeable? = null
    private var readOnlyPresentationBindingClosed = true

    override fun isLive(): Boolean =
        foregroundUseStatus() == UiSyncForegroundUseStatus.CURRENT

    /** Carrier callbacks remain valid through an armed batch's temporary backend BUSY state. */
    internal fun isCarrierLive(): Boolean {
        val bound = synchronized(lock) {
            !closeRequested && !resourcesClosed &&
                (renderer == null || (rendererReadinessArmed && physicalBatch != null))
        }
        return bound && foregroundUseStatus() != UiSyncForegroundUseStatus.REVOKED
    }

    override fun foregroundUseStatus(): UiSyncForegroundUseStatus {
        val rendererState = synchronized(lock) {
            if (closeRequested || resourcesClosed) return UiSyncForegroundUseStatus.REVOKED
            Pair(
                Triple(renderer, rendererReadinessReceipt, physicalBatch),
                rendererReadinessArmed,
            )
        }
        val child = rendererState.first.first
        val externalStatus = try {
            if (!parentLive()) UiSyncForegroundUseStatus.REVOKED
            else if (child == null) UiSyncForegroundUseStatus.CURRENT
            else if (!rendererState.second) UiSyncForegroundUseStatus.BUSY
            else if (!child.isLive ||
                !child.readinessReceipt.matchesExactly(rendererState.first.second) ||
                observedPresentationSource?.hasEveryLiveSubscription() != true
            ) UiSyncForegroundUseStatus.REVOKED
            else when (rendererState.first.third?.generationStatus) {
                UiSyncPhysicalGenerationStatus.CURRENT -> UiSyncForegroundUseStatus.CURRENT
                UiSyncPhysicalGenerationStatus.BUSY -> UiSyncForegroundUseStatus.BUSY
                UiSyncPhysicalGenerationStatus.REVOKED -> UiSyncForegroundUseStatus.REVOKED
                null -> if (requiresPhysicalBatch) UiSyncForegroundUseStatus.BUSY
                    else UiSyncForegroundUseStatus.REVOKED
            }
        } catch (_: Throwable) {
            UiSyncForegroundUseStatus.REVOKED
        }
        return if (synchronized(lock) { closeRequested || resourcesClosed }) {
            UiSyncForegroundUseStatus.REVOKED
        } else externalStatus
    }

    internal fun armRendererReadiness(receipt: RendererReadinessReceipt?) {
        synchronized(lock) {
            check(renderer != null) { "A protocol-only UI Sync session has no renderer receipt" }
            check(!rendererReadinessArmed) { "UI Sync renderer readiness was already armed" }
            rendererReadinessReceipt = receipt
            rendererReadinessArmed = true
        }
    }

    internal fun armPhysicalBatch(
        batch: UiSyncPhysicalOutputBatch,
        authority: UiSyncPhysicalOutputAuthority,
        context: UiSyncPhysicalOutputSessionContext,
    ) {
        synchronized(lock) {
            check(requiresPhysicalBatch && renderer != null &&
                (physicalBindInFlight || !closeRequested) &&
                context.rendererSession === renderer && context.issued(batch) &&
                physicalBatch == null && !resourcesClosed
            ) {
                "UI Sync physical output batch was already assigned"
            }
            physicalBatch = batch
            physicalBatchClosed = false
            physicalOutputAuthority = authority
            physicalOutputContext = context
            physicalBindInFlight = false
        }
    }

    internal fun beginPhysicalBind() = synchronized(lock) {
        check(!closeRequested && !resourcesClosed && !physicalBindInFlight) {
            "UI Sync physical output cannot bind after session stop"
        }
        physicalBindInFlight = true
    }

    internal fun abortPhysicalBind() = synchronized(lock) {
        physicalBindInFlight = false
    }

    internal val isStopCoverRequested: Boolean
        get() = synchronized(lock) { closeRequested }

    internal val isRetired: Boolean
        get() = synchronized(lock) { resourcesClosed }

    /** The session is STOPPING; cover the exact issued batch before any handler can block. */
    internal fun coverForSessionStop(): Boolean {
        val physical = synchronized(lock) {
            closeRequested = true
            if (physicalBindInFlight) return false
            if (physicalBatchClosed) return true
            Triple(
                checkNotNull(physicalOutputAuthority),
                checkNotNull(physicalOutputContext),
                checkNotNull(physicalBatch),
            )
        }
        return physical.first.coverBoundSessionForClose(physical.second, physical.third)
    }

    /** A type-110 RTSP teardown is nonterminal for all other UI Sync destinations. */
    internal fun coverMainForExactScreenTeardown(stream: AirPlayVideoStreamHandle): Boolean {
        val physical = synchronized(lock) {
            if (closeRequested || resourcesClosed || stream.type != 110) return false
            if (physicalBatchClosed) return true
            if ((candidates.keys.none { it.stream === stream } &&
                !coveredMainScreenTeardowns.containsKey(stream)) ||
                checkNotNull(staticPlan).ingressFor(stream) == null
            ) return false
            Triple(
                checkNotNull(physicalOutputAuthority),
                checkNotNull(physicalOutputContext),
                checkNotNull(physicalBatch),
            )
        }
        val covered = physical.first.coverMainForExactScreenTeardown(
            physical.second, physical.third, stream,
        )
        if (covered) synchronized(lock) { coveredMainScreenTeardowns[stream] = Unit }
        return covered
    }

    internal fun finishMainScreenTeardown(stream: AirPlayVideoStreamHandle) {
        synchronized(lock) { coveredMainScreenTeardowns.remove(stream) }
    }

    internal fun ownReadOnlyPresentationBinding(binding: Closeable) {
        synchronized(lock) {
            check(requiresPhysicalBatch && physicalBatch != null && !closeRequested &&
                !resourcesClosed && readOnlyPresentationBinding == null
            ) { "A live physical UI Sync session is required for the read-only binding" }
            readOnlyPresentationBinding = binding
            readOnlyPresentationBindingClosed = false
        }
    }

    override fun acquireUse(): Closeable? {
        val token = Any()
        synchronized(lock) {
            if (closeRequested || resourcesClosed) return null
            check(activeUses < MAXIMUM_ACTIVE_USES) {
                "UI Sync session has too many active operations"
            }
            activeUses = Math.addExact(activeUses, 1)
        }
        if (foregroundUseStatus() == UiSyncForegroundUseStatus.REVOKED) {
            releaseUse(token)
            return null
        }
        var released = false
        return Closeable {
            synchronized(token) {
                if (!released) {
                    released = true
                    releaseUse(token)
                }
            }
        }
    }

    fun prepareScreen(handle: AirPlayVideoStreamHandle): UiSyncPreparedScreenBinding? {
        val selected = synchronized(lock) {
            check(!closeRequested && !resourcesClosed) { "UI Sync renderer session is closed" }
            val child = renderer ?: return null
            val spec = checkNotNull(staticPlan).ingressFor(handle) ?: return null
            check(!handleReservations.containsKey(handle)) {
                "UI Sync renderer already has a candidate for this exact stream handle"
            }
            check(!ingressReservations.containsKey(spec.id)) {
                "UI Sync renderer ingress is still active or retirement is unproven"
            }
            val token = Any()
            handleReservations[handle] = token
            ingressReservations[spec.id] = token
            preparationsInFlight = Math.addExact(preparationsInFlight, 1)
            Triple(child, spec, token)
        }
        val rendererSnapshot = selected.first
        val spec = selected.second
        val reservation = selected.third
        try {
            check(isLive()) { "UI Sync renderer session is not live" }
        } catch (failure: Throwable) {
            releasePreparation(handle, spec.id, reservation)
            throw failure
        }

        val ingress = UltraDisplayGraphIngress(spec.id, handle)
        val delegate = try {
            rendererSnapshot.prepareScreen(
                UiSyncRendererScreenContext(handle, ingress),
            ) ?: throw IllegalStateException("UI Sync renderer refused the exact stream handle")
        } catch (failure: Throwable) {
            releasePreparation(handle, spec.id, reservation)
            throw failure
        }

        lateinit var candidate: UiSyncPreparedScreenBinding
        candidate = UiSyncPreparedScreenBinding(handle, spec.id, delegate, ::isLive) { retired ->
            synchronized(lock) {
                candidates.remove(retired)
                if (handleReservations[handle] === reservation) {
                    handleReservations.remove(handle)
                }
                if (ingressReservations[spec.id] === reservation) {
                    ingressReservations.remove(spec.id)
                }
            }
        }
        synchronized(lock) {
            // The session owns every external candidate from the instant it is returned. Receipt
            // validation below may reject it, but failed cleanup can never orphan the delegate.
            candidates[candidate] = Unit
        }

        try {
            require(delegate.stream === handle) {
                "UI Sync renderer candidate refers to another session-bound stream handle"
            }
            require(delegate.ingressId == spec.id) {
                "UI Sync renderer candidate changed the explicitly resolved ingress ID"
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                preparationsInFlight -= 1
                closeRequested = true
            }
            runCatching { candidate.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }

        val accepted = synchronized(lock) {
            preparationsInFlight -= 1
            // Once the external factory returned a candidate this session owns it, even if a
            // concurrent close makes publication ineligible. Register before cleanup so a failed
            // candidate.close() remains reachable by the next session close attempt.
            candidates[candidate] = Unit
            !closeRequested && !resourcesClosed && renderer === rendererSnapshot &&
                handleReservations[handle] === reservation &&
                ingressReservations[spec.id] === reservation
        }
        if (!accepted) {
            candidate.close()
            throw IllegalStateException("UI Sync renderer session closed during screen preparation")
        }
        return candidate
    }

    fun snapshot(displayId: String): UltraPresentationSnapshot =
        presentationHub.snapshot(displayId)

    /** Bound to this exact AirPlay child; a missing physical renderer observer fails closed. */
    internal fun invalidatePhoneEpoch(displayId: String): Boolean {
        val (child, clusterPhysical) = synchronized(lock) {
            if (closeRequested || resourcesClosed || displayId !in endpointRegistry.displayIds)
                return false
            renderer to (requiresPhysicalBatch && physicalBatch?.routes?.any { route ->
                route.assignment.output.uiSyncDisplayId?.value == displayId &&
                    route.assignment.destination.role == UiSyncPhysicalDestinationRole.CLUSTER
            } == true)
        }
        if (child == null) return !requiresPhysicalBatch
        if (!clusterPhysical) {
            val floor = presentationHub.snapshot(displayId).revision
            return (child as? UiSyncPhoneEpochObserver)
                ?.invalidatePhoneEpoch(displayId, floor) == true
        }
        synchronized(phoneEpochTransitionLock) {
            val physical = synchronized(lock) {
                if (closeRequested || resourcesClosed || physicalBindInFlight ||
                    physicalBatchClosed || renderer !== child
                ) null else Triple(
                    checkNotNull(physicalOutputAuthority),
                    checkNotNull(physicalOutputContext),
                    checkNotNull(physicalBatch),
                )
            } ?: return failClusterPhoneEpoch()
            // Authority phase 1 is a pure permit revocation, before the controller observer
            // can inspect or wait for EGL. No Host/View cover runs on this RCS callback.
            val ticket = runCatching {
                physical.first.beginClusterPhoneEpochCover(
                    physical.second, physical.third, displayId,
                )
            }.getOrNull() ?: return failClusterPhoneEpoch()
            val floor = runCatching { presentationHub.snapshot(displayId).revision }.getOrNull()
                ?: return failClusterPhoneEpoch()
            if (runCatching {
                    (child as? UiSyncPhoneEpochObserver)?.invalidatePhoneEpoch(
                        displayId, floor,
                    ) == true
                }.getOrDefault(false) != true
            ) return failClusterPhoneEpoch()
            if (!runCatching { scheduleClusterPhoneEpochCover(displayId, ticket) }
                    .getOrDefault(false))
                return failClusterPhoneEpoch()
            return true
        }
    }

    private fun failClusterPhoneEpoch(): Boolean {
        // Never send the phone a recoverable Reset reply when the exact physical cover could
        // not be owned. The existing terminal path revokes MAIN as well before channel close.
        beginTerminalUiSyncChannelStop()
        return false
    }

    private fun scheduleClusterPhoneEpochCover(
        displayId: String,
        ticket: UiSyncClusterPhoneEpochCoverTicket,
    ): Boolean = synchronized(lock) {
        if (closeRequested || resourcesClosed) return@synchronized false
        val executor = clusterPhoneEpochCoverExecutor ?: ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "xcertplay-ui-sync-cluster-cover").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }.also { clusterPhoneEpochCoverExecutor = it }
        clusterPhoneEpochCovers[displayId] = ticket
        val scheduled = runCatching {
            executor.execute { completeClusterPhoneEpochCover(displayId, ticket, 1) }
        }.isSuccess
        if (!scheduled && clusterPhoneEpochCovers[displayId] === ticket)
            clusterPhoneEpochCovers.remove(displayId)
        scheduled
    }

    private fun completeClusterPhoneEpochCover(
        displayId: String,
        ticket: UiSyncClusterPhoneEpochCoverTicket,
        attempt: Int,
    ) {
        if (!isPendingClusterPhoneEpochCover(displayId, ticket)) return
        val result = runCatching { ticket.coverExactClusterUi() }
            .getOrDefault(UiSyncClusterPhoneEpochCoverResult.RETRY)
        if (!isPendingClusterPhoneEpochCover(displayId, ticket)) return
        when (result) {
            UiSyncClusterPhoneEpochCoverResult.ACKNOWLEDGED,
            UiSyncClusterPhoneEpochCoverResult.SUPERSEDED -> synchronized(lock) {
                if (clusterPhoneEpochCovers[displayId] === ticket)
                    clusterPhoneEpochCovers.remove(displayId)
            }
            UiSyncClusterPhoneEpochCoverResult.REVOKED ->
                failPendingClusterPhoneEpoch(displayId, ticket)
            UiSyncClusterPhoneEpochCoverResult.RETRY -> {
                if (attempt >= MAXIMUM_CLUSTER_PHONE_EPOCH_COVER_ATTEMPTS) {
                    failPendingClusterPhoneEpoch(displayId, ticket)
                    return
                }
                val scheduled = synchronized(lock) {
                    if (closeRequested || resourcesClosed ||
                        clusterPhoneEpochCovers[displayId] !== ticket
                    ) true else runCatching {
                        checkNotNull(clusterPhoneEpochCoverExecutor).schedule({
                            completeClusterPhoneEpochCover(displayId, ticket, attempt + 1)
                        }, 20L, TimeUnit.MILLISECONDS)
                    }.isSuccess
                }
                if (!scheduled && isPendingClusterPhoneEpochCover(displayId, ticket))
                    failPendingClusterPhoneEpoch(displayId, ticket)
            }
        }
    }

    private fun failPendingClusterPhoneEpoch(
        displayId: String,
        ticket: UiSyncClusterPhoneEpochCoverTicket,
    ) {
        // A second Reset may have begun phase 1 but not yet published its replacement ticket.
        // It owns this transition lock, so an old retry cannot terminalize its new epoch.
        synchronized(phoneEpochTransitionLock) {
            if (isPendingClusterPhoneEpochCover(displayId, ticket)) failClusterPhoneEpoch()
        }
    }

    private fun isPendingClusterPhoneEpochCover(
        displayId: String,
        ticket: UiSyncClusterPhoneEpochCoverTicket,
    ): Boolean = synchronized(lock) {
        !closeRequested && !resourcesClosed && clusterPhoneEpochCovers[displayId] === ticket
    }

    private fun stopClusterPhoneEpochCoversLocked() {
        clusterPhoneEpochCovers.clear()
        clusterPhoneEpochCoverExecutor?.shutdown()
        clusterPhoneEpochCoverExecutor = null
    }

    /**
     * A missing/failed epoch observer cannot leave the old physical scene eligible. Seal this
     * session now; ask the exact Host owner for cover on one bounded worker, never on the RCS
     * callback lock. A successful cover leaves this exact STOPPING child owned for the outer
     * runtime's normal teardown; a failed cover gets one additional close-time cover attempt.
     */
    internal fun beginTerminalUiSyncChannelStop(): Boolean {
        if (!requiresPhysicalBatch) return true // Detached diagnostic session owns no Host batch.
        val physical = synchronized(lock) {
            closeRequested = true
            stopClusterPhoneEpochCoversLocked()
            if (resourcesClosed) return true
            if (emergencyCoverScheduled) return emergencyPermitsRevoked
            if (physicalBindInFlight) return false
            if (physicalBatchClosed) return true
            emergencyCoverScheduled = true
            Triple(
                checkNotNull(physicalOutputAuthority),
                checkNotNull(physicalOutputContext),
                checkNotNull(physicalBatch),
            )
        }
        val revoked = runCatching {
            physical.first.revokeBoundSessionForStop(physical.second, physical.third)
        }.getOrDefault(false)
        synchronized(lock) { emergencyPermitsRevoked = revoked }
        return try {
            Thread({
                repeat(3) { attempt ->
                    if (runCatching { coverForSessionStop() }.getOrDefault(false)) return@Thread
                    if (attempt < 2) try {
                        Thread.sleep(20L)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
                runCatching { close() } // One final exact-cover attempt; failed owners stay retained.
            }, "xcertplay-ui-sync-epoch-cover").apply { isDaemon = true }.start()
            revoked
        } catch (failure: Throwable) {
            synchronized(lock) { emergencyCoverScheduled = false }
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            false
        }
    }

    override fun close() {
        // Mark the child dead before any external call; a cover failure retains this exact batch
        // for retry while preventing new renderer, carrier, or foreground operations.
        val closeState = synchronized(lock) {
            closeRequested = true
            stopClusterPhoneEpochCoversLocked()
            if (resourcesClosed) return
            check(!physicalBindInFlight) {
                "UI Sync physical bind remains in flight"
            }
            check(!closeInFlight) { "UI Sync renderer session close is already in flight" }
            closeInFlight = true
            Pair(candidates.keys.toList(), if (physicalBatchClosed) null else Triple(
                checkNotNull(physicalOutputAuthority),
                checkNotNull(physicalOutputContext),
                checkNotNull(physicalBatch),
            ))
        }
        val children = closeState.first

        var failure: Throwable? = null
        closeState.second?.let { (authority, context, batch) ->
            try {
                check(authority.coverBoundSessionForClose(context, batch)) {
                    "UI Sync physical output cover was not acknowledged"
                }
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
            }
        }
        // Revoke queued requests even when the Host has not yet acknowledged cover.
        try {
            foregroundRequestOutbox.close()
        } catch (problem: Throwable) {
            failure = combineFailures(failure, problem)
        }
        if (failure == null) {
            try {
                observedPresentationSource?.sealAndAwaitQuiescence()
                synchronized(lock) {
                    check(activeUses == 0) { "UI Sync session still has active operations" }
                    check(preparationsInFlight == 0) {
                        "UI Sync screen preparation is still in flight"
                    }
                    check(handleReservations.size == candidates.size &&
                        ingressReservations.size == candidates.size
                    ) { "UI Sync screen preparation is still in flight" }
                }
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
            }
        }
        if (failure == null && !synchronized(lock) { readOnlyPresentationBindingClosed }) {
            try {
                // Revoke browser publication and detach its observer before closing the hub.
                // A failed detach retains this exact binding for the next close attempt.
                checkNotNull(readOnlyPresentationBinding).close()
                synchronized(lock) {
                    readOnlyPresentationBindingClosed = true
                    readOnlyPresentationBinding = null
                }
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
            }
        }
        if (failure == null && !hubClosed) {
            try {
                // Hub close can queue a terminal callback; the observed source drops it after seal.
                presentationHub.close()
                synchronized(lock) { hubClosed = true }
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
            }
        }
        if (failure == null && children.isNotEmpty()) {
            val retired = try {
                checkNotNull(renderer).retireScreens()
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
                false
            }
            if (!retired && failure == null) {
                failure = IllegalStateException(
                    "UI Sync renderer did not prove session-wide screen retirement",
                )
            }
            if (retired) {
                children.forEach { child ->
                    try {
                        child.retireFromSessionProof()
                    } catch (problem: Throwable) {
                        failure = combineFailures(failure, problem)
                    }
                }
            }
        }

        if (failure == null && !rendererClosed) {
            try {
                // A successful renderer close is the render-work quiescence fence. Keep the
                // physical batch attached until no renderer-owned work can target it.
                checkNotNull(renderer).close()
                synchronized(lock) { rendererClosed = true }
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
            }
        }
        if (failure == null && !synchronized(lock) { physicalBatchClosed }) {
            try {
                checkNotNull(physicalBatch).close()
                synchronized(lock) { physicalBatchClosed = true }
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
            }
        }

        val completed = synchronized(lock) {
            closeInFlight = false
            if (failure == null && candidates.isEmpty() && physicalBatchClosed && hubClosed &&
                rendererClosed && readOnlyPresentationBindingClosed
            ) {
                resourcesClosed = true
                true
            } else {
                false
            }
        }
        if (completed) onClosed(this)
        failure?.let { throw it }
    }

    private fun releaseUse(@Suppress("UNUSED_PARAMETER") token: Any) {
        synchronized(lock) {
            check(activeUses > 0) { "UI Sync session use underflow" }
            activeUses -= 1
        }
    }

    private fun releasePreparation(
        handle: AirPlayVideoStreamHandle,
        ingressId: UltraIngressId,
        token: Any,
    ) {
        synchronized(lock) {
            preparationsInFlight -= 1
            if (handleReservations[handle] === token) handleReservations.remove(handle)
            if (ingressReservations[ingressId] === token) ingressReservations.remove(ingressId)
        }
    }

    private companion object {
        const val MAXIMUM_ACTIVE_USES = 65_536
        const val MAXIMUM_CLUSTER_PHONE_EPOCH_COVER_ATTEMPTS = 3
    }
}

/** Exact observer acquired, but initial replay construction failed and retirement is retryable. */
internal class UiSyncRetainedPresentationObserverConstructionException(
    cause: Throwable,
    val retainedObserverLease: Closeable,
) : IllegalStateException("UI Sync initial presentation replay cleanup is pending", cause)

internal fun presentationSource(hub: UltraPresentationHub): UiSyncPresentationSource =
    presentationSource(
        observe = { displayId, observer -> hub.observe(displayId, observer) },
        snapshot = hub::snapshot,
    )

/** Injectable acquisition seam for fault tests; production passes the exact runtime-owned hub. */
internal fun presentationSource(
    observe: (String, UltraPresentationObserver) -> Closeable,
    snapshot: (String) -> UltraPresentationSnapshot,
): UiSyncPresentationSource =
    object : UiSyncPresentationSource {
        override fun subscribeWithReplay(
            displayId: String,
            observer: UltraPresentationObserver,
        ): UiSyncPresentationSubscription {
            val observerLease = try {
                observe(displayId, observer)
            } catch (failure: Throwable) {
                val terminal = runCatching { snapshot(displayId) }.getOrNull()
                if (terminal?.closed == true) {
                    return UiSyncPresentationSubscription(terminal, Closeable { })
                }
                throw failure
            }
            return try {
                UiSyncPresentationSubscription(snapshot(displayId), observerLease)
            } catch (failure: Throwable) {
                val cleanupFailure = runCatching { observerLease.close() }.exceptionOrNull()
                if (cleanupFailure == null) throw failure
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                throw UiSyncRetainedPresentationObserverConstructionException(
                    failure, observerLease,
                )
            }
        }

        override fun snapshot(displayId: String): UltraPresentationSnapshot =
            snapshot(displayId)
    }

private fun AirPlayStreamIdentity.toUltraIdentity() = UltraAirPlayStreamIdentity(
    type = type,
    streamConnectionId = streamConnectionId,
    clientTypeUuid = clientTypeUuid,
)

private fun boundedRendererDisplayIds(
    displayIds: Collection<String>,
    label: String,
): Set<String> {
    val result = LinkedHashSet<String>()
    val iterator = displayIds.iterator()
    while (iterator.hasNext()) {
        require(result.size < 64) { "$label exceed 64 entries" }
        require(result.add(iterator.next())) { "$label contain a duplicate entry" }
    }
    return Collections.unmodifiableSet(result)
}

internal fun combineFailures(current: Throwable?, next: Throwable): Throwable =
    current?.also { if (next !== it) it.addSuppressed(next) } ?: next
