package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.AirPlayReadiness
import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.airplay.AirPlayUiSyncRendererReadinessDescriptor
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBinding
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBindingRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBindingSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import java.io.Closeable
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicBoolean

internal const val UI_SYNC_RCS_IMPLEMENTATION_ID = "xcertplay.ui-sync.router.v2"

private class UiSyncRetainedSessionConstructionException(
    cause: Throwable,
    val retainedSession: UiSyncProvisionedSession,
) : IllegalStateException("UI Sync renderer session validation failed and cleanup is pending", cause)

private class UiSyncRendererReadinessValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Construction failed after an external renderer resource was acquired and its first retirement
 * attempt failed. The exception itself is the retry owner; [close] retries the same child/factory.
 */
class UiSyncRendererProvisioningException internal constructor(
    cause: Throwable,
    private val cleanup: Closeable,
) : IllegalStateException("UI Sync renderer provisioning cleanup is pending", cause), Closeable {
    override fun close() = cleanup.close()
}

private class FailedUiSyncProvisioningCleanup(
    private val session: UiSyncProvisionedSession?,
    private val factory: UiSyncRendererFactoryLease,
    private val factoryIdentityClaim: UiSyncRendererFactoryIdentityClaim,
) : Closeable {
    private val lock = Any()
    private var closeInFlight = false
    private var sessionClosed = session == null
    private var factoryClosed = false

    override fun close() {
        synchronized(lock) {
            if (factoryClosed) return
            check(!closeInFlight) { "UI Sync failed-provisioning cleanup is already in flight" }
            closeInFlight = true
        }
        var failure: Throwable? = null
        if (!synchronized(lock) { sessionClosed }) {
            try {
                checkNotNull(session).close()
                synchronized(lock) { sessionClosed = true }
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
            }
        }
        if (failure == null && !synchronized(lock) { factoryClosed }) {
            try {
                factory.close()
                factoryIdentityClaim.release()
                synchronized(lock) { factoryClosed = true }
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
            }
        }
        synchronized(lock) { closeInFlight = false }
        failure?.let { throw it }
    }
}

/** Immutable SHA-256 identity for one exact UI Sync sidecar, endpoint, and binding schema. */
class UiSyncSchemaFingerprint internal constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == SHA256_BYTES)
    }

    fun bytes(): ByteArray = value.copyOf()

    fun hex(): String = value.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    override fun equals(other: Any?): Boolean =
        other is UiSyncSchemaFingerprint && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "UiSyncSchemaFingerprint(sha256=${hex()})"

    private companion object {
        const val SHA256_BYTES = 32
    }
}

/** Immutable SHA-256 identity for the exact display binding snapshot. */
class UiSyncBindingFingerprint internal constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == SHA256_BYTES)
    }

    fun bytes(): ByteArray = value.copyOf()

    fun hex(): String = value.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    override fun equals(other: Any?): Boolean =
        other is UiSyncBindingFingerprint && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "UiSyncBindingFingerprint(sha256=${hex()})"

    private companion object {
        const val SHA256_BYTES = 32
    }
}

/** Immutable SHA-256 identity for the exact semantic reducer plus theme-scene program. */
class UiSyncSemanticThemeProgramFingerprint(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == SHA256_BYTES) {
            "Semantic/theme program fingerprint must contain exactly $SHA256_BYTES bytes"
        }
    }

    fun bytes(): ByteArray = value.copyOf()

    fun hex(): String = value.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    override fun equals(other: Any?): Boolean =
        other is UiSyncSemanticThemeProgramFingerprint &&
            MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String =
        "UiSyncSemanticThemeProgramFingerprint(sha256=${hex()})"

    private companion object {
        const val SHA256_BYTES = 32
    }
}

/**
 * Profile-compiler expectation for UI Sync provisioning. A complete factory receipt may attest
 * only static program identity; it cannot choose its own semantic/theme program identity or
 * claim that a physical output has already been bound.
 */
class UiSyncRuntimeAuthorizationExpectation(
    val semanticThemeProgramFingerprint: UiSyncSemanticThemeProgramFingerprint,
    val rendererImplementationId: String,
    val physicalOutputRequirement: UiSyncPhysicalOutputRequirement? = null,
) {
    init {
        require(rendererImplementationId.isNotBlank()) {
            "Authorized UI Sync renderer implementation ID must not be blank"
        }
        require(rendererImplementationId.length <= MAXIMUM_IMPLEMENTATION_ID_CHARS) {
            "Authorized UI Sync renderer implementation ID is too long"
        }
        require(rendererImplementationId.none(Char::isISOControl)) {
            "Authorized UI Sync renderer implementation ID contains a control character"
        }
    }

    private companion object {
        const val MAXIMUM_IMPLEMENTATION_ID_CHARS = 512
    }
}

/**
 * Immutable, dynamically revalidated evidence offered to the fixed OEM profile coordinator.
 * This snapshot is descriptive rather than an authorization capability: only the product
 * composition root can issue the manifest which accepts it.
 */
class UiSyncRuntimeAuthorizationSnapshot internal constructor(
    val schemaFingerprint: UiSyncSchemaFingerprint,
    val bindingFingerprint: UiSyncBindingFingerprint,
    val semanticThemeProgramFingerprint: UiSyncSemanticThemeProgramFingerprint,
    val rendererImplementationId: String,
    displayIds: Collection<String>,
    outputs: Collection<UltraDisplayGraphOutput>,
    ingressSpecs: Collection<UiSyncRendererIngressSpec>,
    val physicalOutputRequirement: UiSyncPhysicalOutputRequirement? = null,
) {
    val displayIds: Set<String> = boundedAuthorizationSet(
        displayIds,
        MAXIMUM_DISPLAYS,
        "authorized UI Sync display IDs",
    )
    val outputs: List<UltraDisplayGraphOutput> = boundedAuthorizationSnapshot(
        outputs,
        MAXIMUM_OUTPUTS,
        "authorized UI Sync outputs",
    )
    val ingressSpecs: List<UiSyncRendererIngressSpec> = boundedAuthorizationSnapshot(
        ingressSpecs,
        MAXIMUM_INGRESSES,
        "authorized UI Sync ingress specs",
    )

    init {
        require(this.outputs.map(UltraDisplayGraphOutput::outputId).toSet().size ==
            this.outputs.size
        ) { "Authorized UI Sync outputs contain duplicate output IDs" }
        require(this.ingressSpecs.map(UiSyncRendererIngressSpec::id).toSet().size ==
            this.ingressSpecs.size
        ) { "Authorized UI Sync ingress specs contain duplicate ingress IDs" }
        require(physicalOutputRequirement == null ||
            physicalOutputRequirement.assignments.size == this.outputs.size &&
            physicalOutputRequirement.assignments.all { assigned ->
                this.outputs.any { it === assigned.output }
            }
        ) { "Authorized UI Sync physical assignments changed exact output instances" }
    }

    private companion object {
        const val MAXIMUM_OUTPUTS = 64
        const val MAXIMUM_INGRESSES = 128
        const val MAXIMUM_DISPLAYS = 64

        fun <T> boundedAuthorizationSnapshot(
            values: Collection<T>,
            maximum: Int,
            label: String,
        ): List<T> {
            val result = ArrayList<T>()
            val iterator = values.iterator()
            while (iterator.hasNext()) {
                require(result.size < maximum) { "$label exceed $maximum entries" }
                result += iterator.next()
            }
            return Collections.unmodifiableList(result)
        }

        fun <T> boundedAuthorizationSet(
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

/**
 * Frozen construction input for one display endpoint.
 *
 * The reset configuration is copied at this boundary and on every later use. Retaining or
 * mutating the caller's Map/List/ByteArray graph cannot change a future reset exchange.
 */
class UiSyncDisplayRuntimeSpec(
    val descriptor: CarPlayUiSyncChannelDescriptor,
    val mode: CarPlayUiSyncMode,
    val maximumVersion: CarPlayUiSyncProtocolVersion = CarPlayUiSyncProtocolVersion.V3,
    val limits: CarPlayUiSyncLimits = CarPlayUiSyncLimits(),
    outboundResetConfig: Any? = null,
) {
    private val frozenResetConfig = outboundResetConfig?.let {
        copyCarPlayUiSyncBoundedValue(it, limits)
    }

    fun outboundResetConfig(): Any? = frozenResetConfig?.let {
        copyCarPlayUiSyncBoundedValue(it, limits)
    }

    internal fun detached(): UiSyncDisplayRuntimeSpec = UiSyncDisplayRuntimeSpec(
        descriptor = descriptor.copy(),
        mode = mode,
        maximumVersion = maximumVersion,
        limits = limits.copy(),
        outboundResetConfig = outboundResetConfig(),
    )

    internal fun endpoint(hub: UltraPresentationHub): CarPlayUiSyncEndpoint =
        endpoint(hub.sink(descriptor.displayId))

    internal fun endpoint(
        presentationEventSink: CarPlayUiSyncPresentationEventSink,
    ): CarPlayUiSyncEndpoint =
        CarPlayUiSyncEndpoint(
            descriptor = descriptor.copy(),
            mode = mode,
            presentationEventSink = presentationEventSink,
            maximumVersion = maximumVersion,
            limits = limits.copy(),
            outboundResetConfig = outboundResetConfig(),
        )

    internal fun fingerprintWire(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "displayId" to descriptor.displayId,
        "vehicleId" to descriptor.vehicleId,
        "mode" to mode.name,
        "maximumVersion" to maximumVersion.wireValue,
        "limits" to linkedMapOf(
            "maximumCarrierBytes" to limits.maximumCarrierBytes,
            "maximumInnerMessageBytes" to limits.maximumInnerMessageBytes,
            "maximumValueBytes" to limits.maximumValueBytes,
            "maximumStringChars" to limits.maximumStringChars,
            "maximumIdentifierChars" to limits.maximumIdentifierChars,
            "maximumCollectionEntries" to limits.maximumCollectionEntries,
            "maximumNestingDepth" to limits.maximumNestingDepth,
            "maximumPendingAcknowledgements" to limits.maximumPendingAcknowledgements,
            "maximumRememberedFrames" to limits.maximumRememberedFrames,
            "maximumRememberedFrameBytes" to limits.maximumRememberedFrameBytes,
        ),
        "hasResetConfig" to (frozenResetConfig != null),
    ).apply {
        frozenResetConfig?.let { put("resetConfig", stableWireValue(it)) }
    }
}

/**
 * One frozen, screen-free input set shared by the signed manifest issuer and runtime builder.
 * Only [UiSyncRuntimeProvisioning.prepareAuthorizedStaticInputs] issues this value. The V2 plan
 * is already immutable and is deliberately retained by reference: physical assignments, the
 * renderer context, and the manifest must all refer to its exact output instances. Returned
 * sidecars and display specs are detached so callers cannot mutate the committed fingerprints.
 */
class UiSyncAuthorizedStaticInputs internal constructor(
    internal val frozenInfo: AirPlayUiSyncInfo,
    internal val frozenSpecs: List<UiSyncDisplayRuntimeSpec>,
    val staticPlan: UiSyncRendererStaticPlan,
    val schemaFingerprint: UiSyncSchemaFingerprint,
    val bindingFingerprint: UiSyncBindingFingerprint,
) {
    fun uiSyncInfo(): AirPlayUiSyncInfo = AirPlayUiSyncInfo(frozenInfo.toWireMap())

    fun displaySpecs(): List<UiSyncDisplayRuntimeSpec> =
        Collections.unmodifiableList(frozenSpecs.map(UiSyncDisplayRuntimeSpec::detached))
}

/**
 * One atomic subscription result. [initialSnapshot] was captured only after [observer] became
 * eligible for every later publication, so consumers never need a separate snapshot/register
 * sequence. Callback delivery may happen before [UiSyncPresentationSource.subscribeWithReplay]
 * returns. A failed delegate retirement remains owned here and can be retried; only a completed
 * delegate close makes [close] idempotent.
 */
class UiSyncPresentationSubscription(
    val initialSnapshot: UltraPresentationSnapshot,
    private val observerLease: Closeable,
) : Closeable {
    private val closeLock = Any()
    private var closeInFlight = false
    private var closed = false

    override fun close() {
        synchronized(closeLock) {
            if (closed) return
            check(!closeInFlight) { "UI Sync presentation subscription close is already in flight" }
            closeInFlight = true
        }
        try {
            observerLease.close()
            synchronized(closeLock) {
                closed = true
                closeInFlight = false
            }
        } catch (failure: Throwable) {
            synchronized(closeLock) { closeInFlight = false }
            throw failure
        }
    }
}

/** Read-only presentation stream; it deliberately does not expose the hub's close operation. */
interface UiSyncPresentationSource {
    /**
     * Atomically registers [observer] before capturing the returned complete replay snapshot.
     * Implementations must ensure every publication after that snapshot point is either represented
     * by [UiSyncPresentationSubscription.initialSnapshot] or offered to [observer].
     */
    fun subscribeWithReplay(
        displayId: String,
        observer: UltraPresentationObserver,
    ): UiSyncPresentationSubscription

    fun snapshot(displayId: String): UltraPresentationSnapshot
}

/**
 * An optional, uncounted diagnostic attachment to the exact active AirPlay child. Unlike the
 * factory's construction probe, this context exists only after the child has acquired its own
 * physical batch and passed the session readiness checks. Its status is deliberately dynamic:
 * a captured CURRENT value cannot authorize later observation through BUSY or retirement.
 * Neither this context nor its observer contributes to renderer or UI Sync readiness.
 */
class UiSyncReadOnlySessionContext internal constructor(
    val presentationSource: UiSyncPresentationSource,
    val rendererChild: UiSyncCompleteRendererSessionLease,
    val physicalBatch: UiSyncPhysicalOutputBatch,
    val sessionIdentity: Any,
    private val statusSource: () -> UiSyncReadOnlySessionStatus,
) {
    val status: UiSyncReadOnlySessionStatus
        get() = runCatching { statusSource() }
            .getOrDefault(UiSyncReadOnlySessionStatus.REVOKED)
}

enum class UiSyncReadOnlySessionStatus { CURRENT, BUSY, REVOKED }

/** Retry-safe ownership of optional, uncounted observers before the presentation hub closes. */
private class UiSyncReadOnlyBindingBundle : Closeable {
    private val attachments = ArrayList<Closeable>()

    fun add(attachment: Closeable?) {
        if (attachment != null) attachments += attachment
    }

    val isEmpty: Boolean get() = attachments.isEmpty()

    override fun close() {
        var failure: Throwable? = null
        for (index in attachments.indices.reversed()) {
            try {
                attachments[index].close()
                attachments.removeAt(index)
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
            }
        }
        failure?.let { throw it }
    }
}

/**
 * Concrete composition root for payload decoding, theme reduction, output binding, and backend.
 * Acquisition arms a screen-free factory; each AirPlay control session later receives a distinct
 * [UiSyncRendererSessionLease].
 */
fun interface UiSyncRendererCapability {
    fun acquire(context: UiSyncRendererBindingContext): UiSyncRendererFactoryLease
}

/**
 * Renderer subtype which may enter the READY path. The separate challenge-bearing context keeps a
 * base/resource-only capability from accidentally manufacturing completeness evidence.
 */
interface UiSyncCompleteRendererCapability : UiSyncRendererCapability {
    fun acquire(
        context: UiSyncCompleteRendererBindingContext,
    ): UiSyncCompleteRendererFactoryLease

    override fun acquire(
        context: UiSyncRendererBindingContext,
    ): UiSyncRendererFactoryLease = throw UnsupportedOperationException(
        "A complete renderer factory requires its challenge-bearing context",
    )
}

/** Typed evidence that callback admission is bounded and never deliberately runs inline. */
interface UiSyncBoundedPresentationExecutor : Executor {
    val maximumPendingCallbacks: Int
    val parallelism: Int
    val mayExecuteInline: Boolean
}

private class UiSyncPresentationDispatcherEvidence private constructor(
    val executor: Executor,
    val readiness: AirPlayReadiness,
    val maximumPendingCallbacks: Int?,
    val parallelism: Int?,
    val mayExecuteInline: Boolean,
) {
    companion object {
        fun diagnostic(executor: Executor) = UiSyncPresentationDispatcherEvidence(
            executor = executor,
            readiness = AirPlayReadiness.DIAGNOSTIC_ONLY,
            maximumPendingCallbacks = null,
            parallelism = null,
            mayExecuteInline = true,
        )

        fun boundedNonInline(
            executor: UiSyncBoundedPresentationExecutor,
        ): UiSyncPresentationDispatcherEvidence {
            require(executor.maximumPendingCallbacks in 1..65_536) {
                "UI Sync callback executor capacity is out of range"
            }
            require(!executor.mayExecuteInline) {
                "A READY UI Sync callback executor must not execute inline"
            }
            require(executor.parallelism in 1..256) {
                "UI Sync callback executor parallelism is out of range"
            }
            return UiSyncPresentationDispatcherEvidence(
                executor = executor,
                readiness = AirPlayReadiness.READY,
                maximumPendingCallbacks = executor.maximumPendingCallbacks,
                parallelism = executor.parallelism,
                mayExecuteInline = false,
            )
        }
    }
}

/**
 * Immutable UI Sync provisioning plus one screen-free renderer-factory lease.
 *
 * The public [close] owns this object only until [transferToRuntime] succeeds. Transfer is
 * one-shot: afterwards the returned owner is the sole closer and a stale profile-child close is a
 * harmless no-op. This prevents the profile child and [AirPlayUltraRuntime] from double-owning the
 * same factory/session tree.
 */
class UiSyncRuntimeProvisioning private constructor(
    private val infoWire: Map<String, Any?>,
    private val specs: List<UiSyncDisplayRuntimeSpec>,
    private val endpointTemplates: CarPlayUiSyncEndpointRegistry,
    /** Present only on the legacy diagnostic bridge; READY uses [staticPlan]. */
    val bindingSnapshot: UltraDisplayBindingSnapshot?,
    val staticPlan: UiSyncRendererStaticPlan?,
    val schemaFingerprint: UiSyncSchemaFingerprint,
    val bindingFingerprint: UiSyncBindingFingerprint,
    private val callbackExecutor: Executor,
    internal val rendererReadiness: AirPlayUiSyncRendererReadinessDescriptor,
    private val rendererFactory: UiSyncCompleteRendererFactoryLease?,
    private val rendererFactoryReadinessReceipt: RendererReadinessReceipt?,
    private val rendererFactoryIdentityClaim: UiSyncRendererFactoryIdentityClaim?,
    private val authorizationExpectation: UiSyncRuntimeAuthorizationExpectation?,
    private val physicalOutputAuthority: UiSyncPhysicalOutputAuthority?,
    private val physicalOutputPreflight: UiSyncPhysicalOutputAvailability?,
    private val sessionPresentationSourceFactory: (UltraPresentationHub) -> UiSyncPresentationSource,
    private val readOnlyPresentationBinding: ((UiSyncPresentationSource) -> Closeable?)?,
    private val readOnlySessionBinding: ((UiSyncReadOnlySessionContext) -> Closeable?)?,
) : Closeable {
    private val lifecycleLock = Any()
    private val sessions = IdentityHashMap<UiSyncProvisionedSession, Unit>()
    private var runtimeOwner: Any? = null
    private var opensInFlight = 0
    private var closeRequested = false
    private var closeInFlight = false
    private var factoryClosed = rendererFactory == null
    private var resourcesClosed = false

    fun uiSyncInfo(): AirPlayUiSyncInfo {
        requireUnclosed()
        return AirPlayUiSyncInfo(infoWire)
    }

    fun displaySpecs(): List<UiSyncDisplayRuntimeSpec> {
        requireUnclosed()
        return Collections.unmodifiableList(specs.map(UiSyncDisplayRuntimeSpec::detached))
    }

    /**
     * Descriptive identity check for a later product seal, not a grant of output authority.
     * A transferred runtime still owns its original preflight identity; retirement revokes it.
     * The authority reference itself is intentionally never returned to a caller.
     */
    fun usesPhysicalOutputAuthorityExactly(authority: UiSyncPhysicalOutputAuthority): Boolean =
        synchronized(lifecycleLock) {
            !closeRequested && !resourcesClosed && physicalOutputPreflight != null &&
                physicalOutputAuthority === authority
        }

    /**
     * Returns evidence only for the explicit profile-compiled construction path. Legacy and
     * merely complete diagnostic renderers cannot be upgraded into product authorization.
     */
    fun authorizationSnapshot(): UiSyncRuntimeAuthorizationSnapshot? {
        val captured = synchronized(lifecycleLock) {
            if (runtimeOwner != null || closeRequested || resourcesClosed) return null
            val expectation = authorizationExpectation ?: return null
            val factory = rendererFactory ?: return null
            val receipt = rendererFactoryReadinessReceipt ?: return null
            val plan = staticPlan ?: return null
            arrayOf<Any>(expectation, factory, receipt, plan)
        }
        val expectation = captured[0] as UiSyncRuntimeAuthorizationExpectation
        val factory = captured[1] as UiSyncCompleteRendererFactoryLease
        val receipt = captured[2] as RendererReadinessReceipt
        val plan = captured[3] as UiSyncRendererStaticPlan

        val valid = try {
            factory.isLive &&
                factory.implementationId == expectation.rendererImplementationId &&
                factory.readinessReceipt.matchesExactly(receipt) &&
                receipt.semanticThemeProgramFingerprint ==
                    expectation.semanticThemeProgramFingerprint &&
                receipt.semanticCompilerActive &&
                receipt.themeCompilerActive &&
                receipt.backendLive &&
                receipt.displayIds == endpointTemplates.displayIds &&
                receipt.phase == RendererReadinessReceipt.Phase.STATIC_FACTORY &&
                receipt.subscribedDisplayIds.isEmpty() &&
                receipt.boundOutputIds.isEmpty()
        } catch (_: Throwable) {
            false
        }
        if (!valid) return null

        val result = UiSyncRuntimeAuthorizationSnapshot(
            schemaFingerprint = schemaFingerprint,
            bindingFingerprint = bindingFingerprint,
            semanticThemeProgramFingerprint = receipt.semanticThemeProgramFingerprint,
            rendererImplementationId = expectation.rendererImplementationId,
            displayIds = endpointTemplates.displayIds,
            outputs = plan.outputs,
            ingressSpecs = plan.ingressSpecs,
            physicalOutputRequirement = expectation.physicalOutputRequirement,
        )
        return synchronized(lifecycleLock) {
            if (runtimeOwner == null && !closeRequested && !resourcesClosed &&
                authorizationExpectation === expectation && rendererFactory === factory &&
                rendererFactoryReadinessReceipt === receipt && staticPlan === plan
            ) {
                result
            } else {
                null
            }
        }
    }

    internal fun transferToRuntime(): UiSyncRuntimeProvisioningOwner {
        val token = Any()
        synchronized(lifecycleLock) {
            check(!closeRequested && !resourcesClosed) { "UI Sync provisioning is closed" }
            check(runtimeOwner == null) { "UI Sync provisioning ownership was already transferred" }
            runtimeOwner = token
        }
        return UiSyncRuntimeProvisioningOwner(this, token)
    }

    internal fun ownerInfo(token: Any): AirPlayUiSyncInfo {
        requireOwnerLive(token)
        return AirPlayUiSyncInfo(infoWire)
    }

    internal fun ownerEndpointTemplates(token: Any): CarPlayUiSyncEndpointRegistry {
        requireOwnerLive(token)
        return endpointTemplates
    }

    internal fun ownerDisplayIds(token: Any): Set<String> {
        requireOwnerLive(token)
        return endpointTemplates.displayIds
    }

    internal fun ownerRendererReadinessReceipt(token: Any): RendererReadinessReceipt? {
        requireOwnerLive(token)
        return rendererFactoryReadinessReceipt
    }

    internal fun ownerIsLive(token: Any): Boolean {
        val captured = synchronized(lifecycleLock) {
            if (runtimeOwner !== token || closeRequested || resourcesClosed) return false
            rendererFactory to authorizationExpectation
        }
        val factory = captured.first
        val expectation = captured.second
        val externalLive = try {
            factory == null || (
                factory.isLive && factory.readinessReceipt.matchesExactly(
                    rendererFactoryReadinessReceipt,
                ) && (expectation == null ||
                    factory.implementationId == expectation.rendererImplementationId)
            )
        } catch (_: Throwable) {
            false
        }
        return externalLive && synchronized(lifecycleLock) {
            runtimeOwner === token && !closeRequested && !resourcesClosed
        }
    }

    internal fun openSession(
        token: Any,
        onAcquired: (UiSyncProvisionedSession) -> Unit = {},
        beforePhysicalBind: (UiSyncProvisionedSession) -> Unit = {},
        onPhysicalCandidateRetired: (UiSyncProvisionedSession) -> Unit = {},
    ): UiSyncProvisionedSession {
        val factory = synchronized(lifecycleLock) {
            requireOwnerLiveLocked(token)
            check(opensInFlight < MAXIMUM_SESSION_OPENS) {
                "UI Sync provisioning has too many session opens in flight"
            }
            opensInFlight = Math.addExact(opensInFlight, 1)
            rendererFactory
        }

        var candidate: UiSyncProvisionedSession? = null
        var constructionFailure: Throwable? = null
        try {
            candidate = createSession(
                factory = factory,
                callbackExecutor = callbackExecutor,
                specs = specs,
                staticPlan = staticPlan,
                schemaFingerprint = schemaFingerprint,
                bindingFingerprint = bindingFingerprint,
                factoryReadinessReceipt = rendererFactoryReadinessReceipt,
                factoryIdentityClaim = rendererFactoryIdentityClaim,
                physicalOutputAuthority = physicalOutputAuthority,
                physicalOutputRequirement = authorizationExpectation?.physicalOutputRequirement,
                requirePhysicalActivation = physicalOutputPreflight != null,
                presentationSourceFactory = sessionPresentationSourceFactory,
                readOnlyPresentationBinding = readOnlyPresentationBinding,
                readOnlySessionBinding = readOnlySessionBinding,
                parentLive = { ownerIsLive(token) },
                onClosed = { child ->
                    synchronized(lifecycleLock) { sessions.remove(child) }
                    onPhysicalCandidateRetired(child)
                },
                beforePhysicalBind = beforePhysicalBind,
            )
        } catch (failure: Throwable) {
            if (failure is UiSyncRetainedSessionConstructionException) {
                candidate = failure.retainedSession
                constructionFailure = failure.cause ?: failure
            } else {
                constructionFailure = failure
            }
        }

        val accepted = synchronized(lifecycleLock) {
            opensInFlight -= 1
            val child = candidate
            if (child != null) sessions[child] = Unit
            if (constructionFailure == null && child != null && runtimeOwner === token &&
                !closeRequested && !resourcesClosed && rendererFactory === factory
            ) {
                true
            } else {
                if (
                    constructionFailure is UiSyncRendererReadinessValidationException ||
                    (constructionFailure != null && child != null)
                ) {
                    closeRequested = true
                }
                false
            }
        }
        // The caller must learn the exact child after it enters this owner's journal, even
        // when construction failed and the child's first close already failed. A failed
        // AirPlay session open can then retain its own retry handle for the same child.
        candidate?.let(onAcquired)
        if (constructionFailure != null) throw checkNotNull(constructionFailure)
        val opened = checkNotNull(candidate)
        if (!accepted) {
            try {
                opened.close()
            } catch (cleanup: Throwable) {
                throw IllegalStateException(
                    "UI Sync provisioning closed during session creation",
                    cleanup,
                )
            }
            throw IllegalStateException("UI Sync provisioning closed during session creation")
        }
        return opened
    }

    internal fun ownerClose(token: Any) = closeOwned(token)

    /** After ownership transfer only the runtime owner can close the resources. */
    override fun close() {
        val transferred = synchronized(lifecycleLock) { runtimeOwner != null }
        if (transferred) return
        closeOwned(null)
    }

    private fun closeOwned(owner: Any?) {
        val children = synchronized(lifecycleLock) {
            if (owner == null) {
                if (runtimeOwner != null) return
            } else {
                check(runtimeOwner === owner) { "Stale UI Sync provisioning owner" }
            }
            closeRequested = true
            if (resourcesClosed) return
            check(!closeInFlight) { "UI Sync provisioning close is already in flight" }
            check(opensInFlight == 0) { "UI Sync session creation is still in flight" }
            closeInFlight = true
            sessions.keys.toList()
        }

        var failure: Throwable? = null
        children.forEach { child ->
            try {
                child.close()
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
            }
        }
        if (failure == null && synchronized(lifecycleLock) { sessions.isEmpty() } &&
            !factoryClosed
        ) {
            try {
                checkNotNull(rendererFactory).close()
                checkNotNull(rendererFactoryIdentityClaim).release()
                synchronized(lifecycleLock) { factoryClosed = true }
            } catch (problem: Throwable) {
                failure = combineFailures(failure, problem)
            }
        }

        synchronized(lifecycleLock) {
            closeInFlight = false
            if (failure == null && sessions.isEmpty() && factoryClosed) resourcesClosed = true
        }
        failure?.let { throw it }
    }

    private fun requireUnclosed() {
        check(synchronized(lifecycleLock) { !closeRequested && !resourcesClosed }) {
            "UI Sync provisioning is closed"
        }
    }

    private fun requireOwnerLive(token: Any) {
        synchronized(lifecycleLock) { requireOwnerLiveLocked(token) }
    }

    private fun requireOwnerLiveLocked(token: Any) {
        check(runtimeOwner === token && !closeRequested && !resourcesClosed) {
            "UI Sync provisioning owner is not live"
        }
    }

    companion object {
        private const val MAXIMUM_SESSION_OPENS = 1_024
        private const val MAXIMUM_DISPLAY_ENDPOINTS = 64
        /** Bound for waiting out a transient physical BUSY state during one session open. */
        private val PHYSICAL_BUSY_SETTLE_NANOS = TimeUnit.SECONDS.toNanos(2L)
        private val PHYSICAL_BUSY_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1L)

        /** Atomically captures the registry revision used by every derived runtime artifact. */
        fun from(
            uiSyncInfo: AirPlayUiSyncInfo,
            displays: Collection<UiSyncDisplayRuntimeSpec>,
            bindingRegistry: UltraDisplayBindingRegistry,
            callbackExecutor: Executor,
        ): UiSyncRuntimeProvisioning = from(
            uiSyncInfo = uiSyncInfo,
            displays = displays,
            bindingSnapshot = bindingRegistry.snapshot(),
            callbackExecutor = callbackExecutor,
        )

        fun from(
            uiSyncInfo: AirPlayUiSyncInfo,
            displays: Collection<UiSyncDisplayRuntimeSpec>,
            bindingSnapshot: UltraDisplayBindingSnapshot,
            callbackExecutor: Executor,
        ): UiSyncRuntimeProvisioning = build(
            uiSyncInfo = uiSyncInfo,
            displays = displays,
            legacyBindingSnapshot = bindingSnapshot,
            staticPlan = null,
            rendererCapability = null,
            completeRenderer = false,
            authorizationExpectation = null,
            dispatcherEvidence = UiSyncPresentationDispatcherEvidence.diagnostic(
                callbackExecutor,
            ),
        )

        /**
         * Legacy one-to-one binding registries cannot represent the V2 renderer graph. This
         * overload remains source-compatible but is intentionally diagnostic-only.
         */
        @Suppress("UNUSED_PARAMETER")
        fun fromRendererCapability(
            uiSyncInfo: AirPlayUiSyncInfo,
            displays: Collection<UiSyncDisplayRuntimeSpec>,
            bindingRegistry: UltraDisplayBindingRegistry,
            rendererCapability: UiSyncRendererCapability,
            callbackExecutor: UiSyncBoundedPresentationExecutor,
        ): UiSyncRuntimeProvisioning = from(
            uiSyncInfo = uiSyncInfo,
            displays = displays,
            bindingSnapshot = bindingRegistry.snapshot(),
            callbackExecutor = callbackExecutor,
        )

        @Suppress("UNUSED_PARAMETER")
        fun fromRendererCapability(
            uiSyncInfo: AirPlayUiSyncInfo,
            displays: Collection<UiSyncDisplayRuntimeSpec>,
            bindingSnapshot: UltraDisplayBindingSnapshot,
            rendererCapability: UiSyncRendererCapability,
            callbackExecutor: UiSyncBoundedPresentationExecutor,
        ): UiSyncRuntimeProvisioning = from(
            uiSyncInfo = uiSyncInfo,
            displays = displays,
            bindingSnapshot = bindingSnapshot,
            callbackExecutor = callbackExecutor,
        )

        /**
         * Source-compatible V2 resource-capability overload. A base capability is intentionally
         * not acquired and cannot mint READY; only the complete subtype overload below may do so.
         */
        @Suppress("UNUSED_PARAMETER")
        fun fromRendererCapability(
            uiSyncInfo: AirPlayUiSyncInfo,
            displays: Collection<UiSyncDisplayRuntimeSpec>,
            staticPlan: UiSyncRendererStaticPlan,
            rendererCapability: UiSyncRendererCapability,
            callbackExecutor: UiSyncBoundedPresentationExecutor,
        ): UiSyncRuntimeProvisioning = build(
            uiSyncInfo = uiSyncInfo,
            displays = displays,
            legacyBindingSnapshot = null,
            staticPlan = staticPlan,
            rendererCapability = null,
            completeRenderer = false,
            authorizationExpectation = null,
            dispatcherEvidence = UiSyncPresentationDispatcherEvidence.diagnostic(
                callbackExecutor,
            ),
        )

        /**
         * Complete static renderer-capability path. Without an authorized signed assignment and
         * Host physical-output preflight this remains diagnostic-only and cannot offer UI Sync.
         */
        fun fromRendererCapability(
            uiSyncInfo: AirPlayUiSyncInfo,
            displays: Collection<UiSyncDisplayRuntimeSpec>,
            staticPlan: UiSyncRendererStaticPlan,
            rendererCapability: UiSyncCompleteRendererCapability,
            callbackExecutor: UiSyncBoundedPresentationExecutor,
        ): UiSyncRuntimeProvisioning = build(
            uiSyncInfo = uiSyncInfo,
            displays = displays,
            legacyBindingSnapshot = null,
            staticPlan = staticPlan,
            rendererCapability = rendererCapability,
            completeRenderer = true,
            authorizationExpectation = null,
            dispatcherEvidence = UiSyncPresentationDispatcherEvidence.boundedNonInline(
                callbackExecutor,
            ),
        )

        /** Internal fault-injection seam; the static factory probe still uses the real hub. */
        internal fun fromRendererCapability(
            uiSyncInfo: AirPlayUiSyncInfo,
            displays: Collection<UiSyncDisplayRuntimeSpec>,
            staticPlan: UiSyncRendererStaticPlan,
            rendererCapability: UiSyncCompleteRendererCapability,
            callbackExecutor: UiSyncBoundedPresentationExecutor,
            sessionPresentationSourceFactory: (UltraPresentationHub) -> UiSyncPresentationSource,
        ): UiSyncRuntimeProvisioning = build(
            uiSyncInfo = uiSyncInfo,
            displays = displays,
            legacyBindingSnapshot = null,
            staticPlan = staticPlan,
            rendererCapability = rendererCapability,
            completeRenderer = true,
            authorizationExpectation = null,
            dispatcherEvidence = UiSyncPresentationDispatcherEvidence.boundedNonInline(
                callbackExecutor,
            ),
            sessionPresentationSourceFactory = sessionPresentationSourceFactory,
        )

        /**
         * Product authorization path. The signed-profile compiler supplies the program and
         * implementation identities before the renderer is acquired; the renderer receipt must
         * match them exactly rather than defining them itself.
         */
        fun prepareAuthorizedStaticInputs(
            uiSyncInfo: AirPlayUiSyncInfo,
            displays: Collection<UiSyncDisplayRuntimeSpec>,
            staticPlan: UiSyncRendererStaticPlan,
        ): UiSyncAuthorizedStaticInputs {
            val frozenInfo = AirPlayUiSyncInfo(uiSyncInfo.toWireMap())
            val frozenSpecs = boundedDisplaySpecs(displays)
            require(frozenSpecs.isNotEmpty()) {
                "UI Sync provisioning requires at least one display endpoint"
            }
            validateStaticPlanCoverage(frozenSpecs, staticPlan)
            return UiSyncAuthorizedStaticInputs(
                frozenInfo = frozenInfo,
                frozenSpecs = frozenSpecs,
                staticPlan = staticPlan,
                schemaFingerprint = fingerprint(frozenInfo, frozenSpecs, staticPlan),
                bindingFingerprint = bindingFingerprint(staticPlan),
            )
        }

        fun fromAuthorizedRendererCapability(
            uiSyncInfo: AirPlayUiSyncInfo,
            displays: Collection<UiSyncDisplayRuntimeSpec>,
            staticPlan: UiSyncRendererStaticPlan,
            rendererCapability: UiSyncCompleteRendererCapability,
            callbackExecutor: UiSyncBoundedPresentationExecutor,
            authorizationExpectation: UiSyncRuntimeAuthorizationExpectation,
            physicalOutputAuthority: UiSyncPhysicalOutputAuthority? = null,
            readOnlyPresentationBinding: ((UiSyncPresentationSource) -> Closeable?)? = null,
            readOnlySessionBinding: ((UiSyncReadOnlySessionContext) -> Closeable?)? = null,
        ): UiSyncRuntimeProvisioning = fromAuthorizedRendererCapability(
            inputs = prepareAuthorizedStaticInputs(uiSyncInfo, displays, staticPlan),
            rendererCapability = rendererCapability,
            callbackExecutor = callbackExecutor,
            authorizationExpectation = authorizationExpectation,
            physicalOutputAuthority = physicalOutputAuthority,
            readOnlyPresentationBinding = readOnlyPresentationBinding,
            readOnlySessionBinding = readOnlySessionBinding,
        )

        /** Consumes the exact prepared inputs already committed by the signed manifest issuer. */
        fun fromAuthorizedRendererCapability(
            inputs: UiSyncAuthorizedStaticInputs,
            rendererCapability: UiSyncCompleteRendererCapability,
            callbackExecutor: UiSyncBoundedPresentationExecutor,
            authorizationExpectation: UiSyncRuntimeAuthorizationExpectation,
            physicalOutputAuthority: UiSyncPhysicalOutputAuthority? = null,
            readOnlyPresentationBinding: ((UiSyncPresentationSource) -> Closeable?)? = null,
            readOnlySessionBinding: ((UiSyncReadOnlySessionContext) -> Closeable?)? = null,
        ): UiSyncRuntimeProvisioning = build(
            uiSyncInfo = inputs.frozenInfo,
            displays = inputs.frozenSpecs,
            legacyBindingSnapshot = null,
            staticPlan = inputs.staticPlan,
            rendererCapability = rendererCapability,
            completeRenderer = true,
            authorizationExpectation = authorizationExpectation,
            physicalOutputAuthority = physicalOutputAuthority,
            readOnlyPresentationBinding = readOnlyPresentationBinding,
            readOnlySessionBinding = readOnlySessionBinding,
            dispatcherEvidence = UiSyncPresentationDispatcherEvidence.boundedNonInline(
                callbackExecutor,
            ),
            preparedInputs = inputs,
        )

        private fun build(
            uiSyncInfo: AirPlayUiSyncInfo,
            displays: Collection<UiSyncDisplayRuntimeSpec>,
            legacyBindingSnapshot: UltraDisplayBindingSnapshot?,
            staticPlan: UiSyncRendererStaticPlan?,
            rendererCapability: UiSyncCompleteRendererCapability?,
            completeRenderer: Boolean,
            authorizationExpectation: UiSyncRuntimeAuthorizationExpectation?,
            physicalOutputAuthority: UiSyncPhysicalOutputAuthority? = null,
            dispatcherEvidence: UiSyncPresentationDispatcherEvidence,
            preparedInputs: UiSyncAuthorizedStaticInputs? = null,
            sessionPresentationSourceFactory: (UltraPresentationHub) -> UiSyncPresentationSource =
                ::presentationSource,
            readOnlyPresentationBinding: ((UiSyncPresentationSource) -> Closeable?)? = null,
            readOnlySessionBinding: ((UiSyncReadOnlySessionContext) -> Closeable?)? = null,
        ): UiSyncRuntimeProvisioning {
            var rendererFactory: UiSyncCompleteRendererFactoryLease? = null
            var rendererFactoryReadinessReceipt: RendererReadinessReceipt? = null
            var rendererFactoryIdentityClaim: UiSyncRendererFactoryIdentityClaim? = null
            var retainedProbe: UiSyncProvisionedSession? = null
            var physicalOutputPreflight: UiSyncPhysicalOutputAvailability? = null
            try {
                val frozenInfo = preparedInputs?.frozenInfo
                    ?: AirPlayUiSyncInfo(uiSyncInfo.toWireMap())
                val frozenSpecs = preparedInputs?.frozenSpecs ?: boundedDisplaySpecs(displays)
                require(preparedInputs == null ||
                    preparedInputs.staticPlan === staticPlan &&
                    preparedInputs.frozenInfo === frozenInfo &&
                    preparedInputs.frozenSpecs === frozenSpecs
                ) { "Authorized UI Sync inputs changed after preparation" }
                require(frozenSpecs.isNotEmpty()) {
                    "UI Sync provisioning requires at least one display endpoint"
                }
                require(!completeRenderer || rendererCapability != null) {
                    "Complete renderer readiness requires a complete capability"
                }
                require(!completeRenderer || staticPlan != null) {
                    "Complete renderer readiness requires a V2 static plan"
                }
                require(authorizationExpectation == null || completeRenderer) {
                    "Authorized UI Sync provisioning requires a complete renderer"
                }
                if (staticPlan != null) {
                    validateStaticPlanCoverage(frozenSpecs, staticPlan)
                } else {
                    validateBindingCoverage(frozenSpecs, checkNotNull(legacyBindingSnapshot))
                }
                val bindingFingerprint = preparedInputs?.bindingFingerprint
                    ?: staticPlan?.let(::bindingFingerprint)
                    ?: bindingFingerprint(checkNotNull(legacyBindingSnapshot))
                val fingerprint = preparedInputs?.schemaFingerprint ?: staticPlan?.let {
                    fingerprint(frozenInfo, frozenSpecs, it)
                } ?: fingerprint(frozenInfo, frozenSpecs, checkNotNull(legacyBindingSnapshot))
                val registry = CarPlayUiSyncEndpointRegistry(
                    frozenSpecs.map { spec ->
                        spec.endpoint(CarPlayUiSyncPresentationEventSink { })
                    },
                )
                val plannedDisplayIds = staticPlan
                    ?.outputs
                    ?.mapNotNull { it.uiSyncDisplayId?.value }
                    ?.toSet()
                    ?: bindingDisplayIds(checkNotNull(legacyBindingSnapshot))
                require(registry.displayIds == plannedDisplayIds) {
                    "UI Sync endpoint and renderer-plan display IDs diverged"
                }
                require(registry.isProtocolRuntimeComplete) {
                    "UI Sync endpoint protocol runtime probe failed"
                }
                val physicalRequirement = authorizationExpectation?.physicalOutputRequirement
                if (physicalRequirement != null) {
                    require(physicalRequirement.matchesPlan(checkNotNull(staticPlan))) {
                        "Signed physical assignments do not cover every renderer-plan output"
                    }
                }
                if (physicalRequirement != null && physicalOutputAuthority != null) {
                    val preflightContext = UiSyncPhysicalOutputPreflightContext(
                        physicalRequirement,
                        checkNotNull(staticPlan),
                        fingerprint,
                        bindingFingerprint,
                    )
                    val offered = physicalOutputAuthority.preflight(preflightContext)
                    require(offered == null || preflightContext.issued(offered)
                    ) {
                        "Host physical output preflight belongs to another request"
                    }
                    physicalOutputPreflight = offered?.takeIf { it.isCurrent }
                }

                require(
                    dispatcherEvidence.readiness != AirPlayReadiness.READY ||
                        (dispatcherEvidence.parallelism ?: 0) >= frozenSpecs.size,
                ) {
                    "A READY UI Sync callback executor must provide one lane per display"
                }
                if (completeRenderer) {
                    val capability = requireNotNull(rendererCapability)
                    val completeContext = UiSyncCompleteRendererBindingContext(
                        UiSyncRendererBindingContext(
                            checkNotNull(staticPlan),
                            fingerprint,
                            bindingFingerprint,
                            registry.displayIds,
                        ),
                    )
                    val lease = capability.acquire(completeContext)
                    val identityClaim = try {
                        UiSyncRendererLeaseIdentityRegistry.claimFactory(lease)
                    } catch (failure: Throwable) {
                        throw UiSyncRendererReadinessValidationException(
                            "UI Sync renderer factory lease identity is already owned",
                            failure,
                        )
                    }
                    rendererFactory = lease
                    rendererFactoryIdentityClaim = identityClaim
                    val receipt = validateFactoryLease(
                        lease = lease,
                        completeContext = completeContext,
                        schemaFingerprint = fingerprint,
                        bindingFingerprint = bindingFingerprint,
                        displayIds = registry.displayIds,
                        authorizationExpectation = authorizationExpectation,
                    )
                    rendererFactoryReadinessReceipt = receipt

                    // The static factory must actually open and close a fresh session. This probe
                    // owns a private hub/registry but never attaches a physical output or claims
                    // that the later AirPlay session is ACTIVE.
                    val probe = createSession(
                        factory = lease,
                        callbackExecutor = dispatcherEvidence.executor,
                        specs = frozenSpecs,
                        staticPlan = staticPlan,
                        schemaFingerprint = fingerprint,
                        bindingFingerprint = bindingFingerprint,
                        factoryReadinessReceipt = receipt,
                        factoryIdentityClaim = identityClaim,
                        physicalOutputAuthority = null,
                        physicalOutputRequirement = null,
                        requirePhysicalActivation = false,
                        parentLive = {
                            lease.isLive && lease.readinessReceipt.matchesExactly(receipt)
                        },
                        onClosed = {},
                    )
                    retainedProbe = probe
                    probe.close()
                    retainedProbe = null
                    readinessCheck(lease.isLive) {
                        "UI Sync renderer factory died after session probe"
                    }
                    readinessCheck(lease.readinessReceipt.matchesExactly(receipt)) {
                        "UI Sync renderer factory readiness receipt changed after session probe"
                    }
                }
                val rendererReadiness = if (completeRenderer &&
                    physicalOutputPreflight != null
                ) {
                    AirPlayUiSyncRendererReadinessDescriptor.complete(
                        implementationId = checkNotNull(rendererFactory).implementationId,
                        callbackDispatcher = dispatcherEvidence.readiness,
                        maximumPendingCallbacks = dispatcherEvidence.maximumPendingCallbacks,
                        callbackParallelism = dispatcherEvidence.parallelism,
                        mayExecuteInline = dispatcherEvidence.mayExecuteInline,
                        physicalOfferPreflight = physicalOutputPreflight,
                    )
                } else {
                    AirPlayUiSyncRendererReadinessDescriptor.diagnosticOnly()
                }
                // A statically complete factory may be retained for signed manifest preparation;
                // absent a trusted Host physical preflight it must not advertise UI Sync.
                return UiSyncRuntimeProvisioning(
                    infoWire = frozenInfo.toWireMap(),
                    specs = Collections.unmodifiableList(frozenSpecs),
                    endpointTemplates = registry,
                    bindingSnapshot = legacyBindingSnapshot,
                    staticPlan = staticPlan,
                    schemaFingerprint = fingerprint,
                    bindingFingerprint = bindingFingerprint,
                    callbackExecutor = dispatcherEvidence.executor,
                    rendererReadiness = rendererReadiness,
                    rendererFactory = rendererFactory,
                    rendererFactoryReadinessReceipt = rendererFactoryReadinessReceipt,
                    rendererFactoryIdentityClaim = rendererFactoryIdentityClaim,
                    authorizationExpectation = authorizationExpectation,
                    physicalOutputAuthority = physicalOutputAuthority,
                    physicalOutputPreflight = physicalOutputPreflight,
                    sessionPresentationSourceFactory = sessionPresentationSourceFactory,
                    readOnlyPresentationBinding = readOnlyPresentationBinding,
                    readOnlySessionBinding = readOnlySessionBinding,
                )
            } catch (failure: Throwable) {
                val factory = rendererFactory ?: throw failure
                val retainedSession =
                    (failure as? UiSyncRetainedSessionConstructionException)?.retainedSession
                        ?: retainedProbe
                val primary = if (failure is UiSyncRetainedSessionConstructionException) {
                    failure.cause ?: failure
                } else {
                    failure
                }
                val cleanup = FailedUiSyncProvisioningCleanup(
                    retainedSession,
                    factory,
                    checkNotNull(rendererFactoryIdentityClaim),
                )
                val cleanupFailure = runCatching { cleanup.close() }.exceptionOrNull()
                if (cleanupFailure == null) throw primary
                if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
                throw UiSyncRendererProvisioningException(primary, cleanup)
            }
        }

        private fun validateFactoryLease(
            lease: UiSyncCompleteRendererFactoryLease,
            completeContext: UiSyncCompleteRendererBindingContext,
            schemaFingerprint: UiSyncSchemaFingerprint,
            bindingFingerprint: UiSyncBindingFingerprint,
            displayIds: Set<String>,
            authorizationExpectation: UiSyncRuntimeAuthorizationExpectation?,
        ): RendererReadinessReceipt {
            readinessCheck(lease.isLive) { "UI Sync renderer factory lease is not live" }
            readinessCheck(lease.schemaFingerprint == schemaFingerprint) {
                "UI Sync renderer factory refers to another provisioning fingerprint"
            }
            readinessCheck(lease.bindingFingerprint == bindingFingerprint) {
                "UI Sync renderer factory refers to another static-plan fingerprint"
            }
            val leaseDisplayIds = rendererLeaseDisplayIds(lease.displayIds, "factory")
            readinessCheck(leaseDisplayIds == displayIds) {
                "UI Sync renderer factory does not bind every UI Sync display"
            }
            readinessCheck(lease.implementationId.isNotBlank()) {
                "UI Sync renderer implementation ID must not be blank"
            }
            readinessCheck(
                authorizationExpectation == null ||
                    lease.implementationId == authorizationExpectation.rendererImplementationId,
            ) { "UI Sync renderer implementation does not match the authorized profile" }
            return validateReadinessReceipt(
                receipt = lease.readinessReceipt,
                challengeAccepted = completeContext::accepts,
                schemaFingerprint = schemaFingerprint,
                bindingFingerprint = bindingFingerprint,
                displayIds = displayIds,
                expectedProgramFingerprint =
                    authorizationExpectation?.semanticThemeProgramFingerprint,
                owner = "factory",
            )
        }

        private fun createSession(
            factory: UiSyncCompleteRendererFactoryLease?,
            callbackExecutor: Executor,
            specs: List<UiSyncDisplayRuntimeSpec>,
            staticPlan: UiSyncRendererStaticPlan?,
            schemaFingerprint: UiSyncSchemaFingerprint,
            bindingFingerprint: UiSyncBindingFingerprint,
            factoryReadinessReceipt: RendererReadinessReceipt?,
            factoryIdentityClaim: UiSyncRendererFactoryIdentityClaim?,
            physicalOutputAuthority: UiSyncPhysicalOutputAuthority?,
            physicalOutputRequirement: UiSyncPhysicalOutputRequirement?,
            requirePhysicalActivation: Boolean,
            presentationSourceFactory: (UltraPresentationHub) -> UiSyncPresentationSource =
                ::presentationSource,
            readOnlyPresentationBinding: ((UiSyncPresentationSource) -> Closeable?)? = null,
            readOnlySessionBinding: ((UiSyncReadOnlySessionContext) -> Closeable?)? = null,
            parentLive: () -> Boolean,
            onClosed: (UiSyncProvisionedSession) -> Unit,
            beforePhysicalBind: (UiSyncProvisionedSession) -> Unit = {},
        ): UiSyncProvisionedSession {
            val hub = UltraPresentationHub(callbackExecutor)
            var rendererSession: UiSyncCompleteRendererSessionLease? = null
            var ownedSession: UiSyncProvisionedSession? = null
            var observedSource: UiSyncObservedPresentationSource? = null
            var endpointRegistry: CarPlayUiSyncEndpointRegistry? = null
            var foregroundRequestOutbox: UiSyncForegroundRequestOutbox? = null
            var boundPhysicalBatch: UiSyncPhysicalOutputBatch? = null
            try {
                val registry = CarPlayUiSyncEndpointRegistry(specs.map { it.endpoint(hub) })
                    .also { endpointRegistry = it }
                require(registry.isProtocolRuntimeComplete) {
                    "UI Sync per-session protocol runtime probe failed"
                }
                // This exact outbox must exist before the renderer child receives its session
                // context; the later ClusterControl handler only attaches this same instance.
                val outbox = UiSyncForegroundRequestOutbox(registry.displayIds)
                    .also { foregroundRequestOutbox = it }
                if (factory != null) {
                    val factoryReceiptIsCurrent = try {
                        factory.isLive && factory.readinessReceipt.matchesExactly(
                            factoryReadinessReceipt,
                        )
                    } catch (failure: Throwable) {
                        throw UiSyncRendererReadinessValidationException(
                            "UI Sync renderer factory readiness receipt could not be read",
                            failure,
                        )
                    }
                    readinessCheck(factoryReceiptIsCurrent) {
                        "UI Sync renderer factory readiness receipt is missing, stale, or changed"
                    }
                }
                var completeContext: UiSyncCompleteRendererSessionBindingContext? = null
                if (factory != null) {
                    observedSource = UiSyncObservedPresentationSource(
                        presentationSourceFactory(hub), registry.displayIds,
                    )
                    completeContext = UiSyncCompleteRendererSessionBindingContext(
                        UiSyncRendererSessionBindingContext(
                            checkNotNull(observedSource),
                            checkNotNull(staticPlan),
                            schemaFingerprint,
                            bindingFingerprint,
                            outbox,
                            registry.displayIds,
                        ),
                    )
                    val openedSession = factory.openSession(completeContext)
                    try {
                        checkNotNull(factoryIdentityClaim).claimSession(openedSession)
                    } catch (failure: Throwable) {
                        throw UiSyncRendererReadinessValidationException(
                            "UI Sync renderer session lease identity was already used",
                            failure,
                        )
                    }
                    rendererSession = openedSession
                    readinessCheck(!requirePhysicalActivation ||
                        openedSession is UiSyncPhoneEpochObserver
                    ) {
                        "Physical UI Sync renderer has no phone-epoch invalidation observer"
                    }
                }
                val child = UiSyncProvisionedSession(
                    presentationHub = hub,
                    endpointRegistry = registry,
                    foregroundRequestOutbox = outbox,
                    staticPlan = staticPlan,
                    schemaFingerprint = schemaFingerprint,
                    bindingFingerprint = bindingFingerprint,
                    renderer = rendererSession,
                    rendererReadinessReceipt = null,
                    observedPresentationSource = observedSource,
                    requiresPhysicalBatch = requirePhysicalActivation,
                    parentLive = parentLive,
                    onClosed = onClosed,
                )
                ownedSession = child
                // From here on, every enqueue and physical write must reacquire this exact
                // session's liveness/use lease. Before this point the renderer port rejects.
                outbox.bindSession(child)
                if (factory != null) {
                    val validatedSession = checkNotNull(rendererSession)
                    val observedSessionReceipt = try {
                        validatedSession.readinessReceipt
                    } catch (failure: Throwable) {
                        throw UiSyncRendererReadinessValidationException(
                            "UI Sync renderer session readiness receipt could not be read",
                            failure,
                        )
                    }
                    child.armRendererReadiness(observedSessionReceipt)
                    validateRendererSession(
                        validatedSession,
                        factory,
                        checkNotNull(completeContext),
                        schemaFingerprint,
                        bindingFingerprint,
                        registry.displayIds,
                        checkNotNull(factoryReadinessReceipt).semanticThemeProgramFingerprint,
                        observedSessionReceipt,
                    )
                    if (requirePhysicalActivation) {
                        // Publish the exact candidate to its AirPlay stop-cover owner before
                        // entering a potentially blocking physical bind. No provisioning lock
                        // is held while the callback runs.
                        beforePhysicalBind(child)
                        readinessCheck(checkNotNull(observedSource).hasEveryLiveSubscription()) {
                            "UI Sync renderer did not establish every live presentation subscription"
                        }
                        val rendererStillReadyForBind = try {
                            validatedSession.isLive &&
                                validatedSession.readinessReceipt.matchesExactly(
                                    observedSessionReceipt,
                                )
                        } catch (failure: Throwable) {
                            throw UiSyncRendererReadinessValidationException(
                                "UI Sync renderer session readiness could not be read before physical bind",
                                failure,
                            )
                        }
                        readinessCheck(rendererStillReadyForBind) {
                            "UI Sync renderer session retired before physical bind"
                        }
                        val physicalContext = UiSyncPhysicalOutputSessionContext(
                            checkNotNull(physicalOutputRequirement),
                            checkNotNull(staticPlan),
                            schemaFingerprint,
                            bindingFingerprint,
                            validatedSession,
                        )
                        child.beginPhysicalBind()
                        val batch = try {
                            checkNotNull(physicalOutputAuthority).bindSession(physicalContext)
                                ?: throw UiSyncRendererReadinessValidationException(
                                    "UI Sync Host physical output batch was not installed",
                                )
                        } catch (failure: Throwable) {
                            child.abortPhysicalBind()
                            throw failure
                        }
                        try {
                            readinessCheck(physicalContext.issued(batch)) {
                                "UI Sync Host physical output batch belongs to another session"
                            }
                            // Only an exact-session batch transfers to this child. Closing a
                            // replayed foreign lease could tear down another live session.
                            child.armPhysicalBatch(
                                batch, checkNotNull(physicalOutputAuthority), physicalContext,
                            )
                        } catch (failure: Throwable) {
                            child.abortPhysicalBind()
                            throw failure
                        }
                        boundPhysicalBatch = batch
                        if (child.isStopCoverRequested) {
                            check(child.coverForSessionStop()) {
                                "Stopped UI Sync session physical cover was not acknowledged"
                            }
                            throw IllegalStateException("AirPlay stopped during UI Sync physical bind")
                        }
                        // BUSY is a transient AA05/backend reservation (for example another
                        // thread's read-only liveness probe), not retirement. Activation still
                        // starts only after a later CURRENT observation of this exact batch.
                        readinessCheck(awaitSettledPhysicalStatus(
                            UiSyncPhysicalGenerationStatus.BUSY,
                        ) { batch.generationStatus } == UiSyncPhysicalGenerationStatus.CURRENT) {
                            "UI Sync Host physical output batch became stale"
                        }
                        // The child owns the batch before any external activation callback. If
                        // it throws, the existing retained child is the only cleanup owner and
                        // retires renderer work before releasing physical targets.
                        when (checkNotNull(physicalOutputAuthority).activateBoundSession(
                            physicalContext,
                            batch,
                        )) {
                            UiSyncPhysicalActivationResult.SUBMITTED,
                            UiSyncPhysicalActivationResult.PENDING_INPUT_STREAM,
                            UiSyncPhysicalActivationResult.PENDING_LOCAL_CANVAS -> Unit
                        }
                        if (child.isStopCoverRequested) {
                            check(child.coverForSessionStop()) {
                                "Stopped UI Sync session physical cover was not acknowledged"
                            }
                            throw IllegalStateException("AirPlay stopped during UI Sync activation")
                        }
                    }
                    val sessionReceiptRemainsCurrent = try {
                        validatedSession.isLive &&
                            validatedSession.readinessReceipt.matchesExactly(
                                observedSessionReceipt,
                            )
                    } catch (failure: Throwable) {
                        throw UiSyncRendererReadinessValidationException(
                            "UI Sync renderer session readiness could not be revalidated",
                            failure,
                        )
                    }
                    readinessCheck(sessionReceiptRemainsCurrent) {
                        "UI Sync renderer session readiness changed during validation"
                    }
                    if (requirePhysicalActivation) {
                        // Same rule after submission: only CURRENT passes, REVOKED fails at
                        // once, and a BUSY that outlives the bounded window fails closed.
                        readinessCheck(awaitSettledPhysicalStatus(
                            UiSyncForegroundUseStatus.BUSY,
                        ) { child.foregroundUseStatus() } == UiSyncForegroundUseStatus.CURRENT) {
                            "UI Sync physical output or subscription retired during activation"
                        }
                        // These optional read-only observers cannot subscribe through
                        // observedSource and therefore cannot contribute to renderer readiness.
                        // The exact physical batch and child have both passed validation before
                        // this callback can run. Retire each attachment before closing the hub.
                        val readOnlyBindings = UiSyncReadOnlyBindingBundle()
                        try {
                            readOnlyBindings.add(readOnlyPresentationBinding?.invoke(
                                presentationSource(hub),
                            ))
                            if (readOnlySessionBinding != null) {
                                val activeBatch = checkNotNull(boundPhysicalBatch)
                                val diagnosticContext = UiSyncReadOnlySessionContext(
                                    presentationSource(hub), validatedSession,
                                    activeBatch, child,
                                    {
                                        when (child.foregroundUseStatus()) {
                                            UiSyncForegroundUseStatus.CURRENT ->
                                                UiSyncReadOnlySessionStatus.CURRENT
                                            UiSyncForegroundUseStatus.BUSY ->
                                                UiSyncReadOnlySessionStatus.BUSY
                                            UiSyncForegroundUseStatus.REVOKED ->
                                                UiSyncReadOnlySessionStatus.REVOKED
                                        }
                                    },
                                )
                                // Diagnostics are deliberately not a construction/readiness
                                // gate. The callback owns cleanup of any partially created
                                // resource if it fails before returning a Closeable.
                                readOnlyBindings.add(runCatching {
                                    readOnlySessionBinding.invoke(diagnosticContext)
                                }.getOrNull())
                            }
                            if (!readOnlyBindings.isEmpty) {
                                child.ownReadOnlyPresentationBinding(readOnlyBindings)
                            }
                        } catch (failure: Throwable) {
                            runCatching { readOnlyBindings.close() }.exceptionOrNull()
                                ?.let(failure::addSuppressed)
                            throw failure
                        }
                    }
                }
                readinessCheck(observedSource?.hasPendingRejectedCleanup() != true) {
                    "UI Sync rejected observer retirement is pending"
                }
                return child
            } catch (failure: Throwable) {
                ownedSession?.let { child ->
                    val cleanupFailure = runCatching { child.close() }.exceptionOrNull()
                    if (cleanupFailure != null) {
                        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                        throw UiSyncRetainedSessionConstructionException(failure, child)
                    }
                    throw failure
                }
                if (observedSource != null) {
                    // factory.openSession may fail while a subscription is still constructing,
                    // before any rejected lease reaches pendingRejected. This resource-only
                    // child first seals/waits that source, then retires its hub and renderer;
                    // a failed retirement follows the existing retained-session owner path.
                    val cleanupChild = UiSyncProvisionedSession(
                        presentationHub = hub,
                        endpointRegistry = checkNotNull(endpointRegistry),
                        foregroundRequestOutbox = checkNotNull(foregroundRequestOutbox),
                        staticPlan = staticPlan,
                        schemaFingerprint = schemaFingerprint,
                        bindingFingerprint = bindingFingerprint,
                        renderer = rendererSession,
                        rendererReadinessReceipt = null,
                        observedPresentationSource = observedSource,
                        requiresPhysicalBatch = requirePhysicalActivation,
                        parentLive = parentLive,
                        onClosed = onClosed,
                    )
                    val cleanupFailure = runCatching { cleanupChild.close() }.exceptionOrNull()
                    if (cleanupFailure != null) {
                        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                        throw UiSyncRetainedSessionConstructionException(failure, cleanupChild)
                    }
                    throw failure
                }
                runCatching { rendererSession?.close() }.exceptionOrNull()?.let {
                    if (it !== failure) failure.addSuppressed(it)
                }
                foregroundRequestOutbox?.close()
                runCatching { hub.close() }.exceptionOrNull()?.let {
                    if (it !== failure) failure.addSuppressed(it)
                }
                throw failure
            }
        }

        private fun validateRendererSession(
            session: UiSyncCompleteRendererSessionLease,
            factory: UiSyncCompleteRendererFactoryLease,
            completeContext: UiSyncCompleteRendererSessionBindingContext,
            schemaFingerprint: UiSyncSchemaFingerprint,
            bindingFingerprint: UiSyncBindingFingerprint,
            displayIds: Set<String>,
            expectedProgramFingerprint: UiSyncSemanticThemeProgramFingerprint,
            observedReceipt: RendererReadinessReceipt?,
        ): RendererReadinessReceipt {
            readinessCheck(session.isLive) { "UI Sync renderer session is not live" }
            readinessCheck(session.schemaFingerprint == schemaFingerprint) {
                "UI Sync renderer session refers to another provisioning fingerprint"
            }
            readinessCheck(session.bindingFingerprint == bindingFingerprint) {
                "UI Sync renderer session refers to another static-plan fingerprint"
            }
            val sessionDisplayIds = rendererLeaseDisplayIds(session.displayIds, "session")
            readinessCheck(sessionDisplayIds == displayIds) {
                "UI Sync renderer session does not bind every UI Sync display"
            }
            readinessCheck(session.implementationId == factory.implementationId) {
                "UI Sync renderer session implementation differs from its factory"
            }
            return validateReadinessReceipt(
                receipt = observedReceipt,
                challengeAccepted = completeContext::accepts,
                schemaFingerprint = schemaFingerprint,
                bindingFingerprint = bindingFingerprint,
                displayIds = displayIds,
                expectedProgramFingerprint = expectedProgramFingerprint,
                owner = "session",
            )
        }

        private fun validateReadinessReceipt(
            receipt: RendererReadinessReceipt?,
            challengeAccepted: (RendererReadinessReceipt?) -> Boolean,
            schemaFingerprint: UiSyncSchemaFingerprint,
            bindingFingerprint: UiSyncBindingFingerprint,
            displayIds: Set<String>,
            expectedProgramFingerprint: UiSyncSemanticThemeProgramFingerprint?,
            owner: String,
        ): RendererReadinessReceipt {
            val evidence = receipt ?: throw UiSyncRendererReadinessValidationException(
                "UI Sync renderer $owner readiness receipt is missing",
            )
            readinessCheck(challengeAccepted(evidence)) {
                "UI Sync renderer $owner readiness receipt is stale or belongs to another owner"
            }
            readinessCheck(evidence.schemaFingerprint == schemaFingerprint) {
                "UI Sync renderer $owner receipt refers to another provisioning fingerprint"
            }
            readinessCheck(evidence.bindingFingerprint == bindingFingerprint) {
                "UI Sync renderer $owner receipt refers to another static-plan fingerprint"
            }
            readinessCheck(evidence.displayIds == displayIds) {
                "UI Sync renderer $owner receipt does not bind every UI Sync display"
            }
            readinessCheck(evidence.phase == if (owner == "factory") {
                RendererReadinessReceipt.Phase.STATIC_FACTORY
            } else {
                RendererReadinessReceipt.Phase.SESSION_IMPLEMENTATION
            }) { "UI Sync renderer $owner receipt has the wrong proof phase" }
            readinessCheck(evidence.subscribedDisplayIds.isEmpty() &&
                evidence.boundOutputIds.isEmpty()
            ) { "UI Sync renderer $owner receipt cannot self-report active output binding" }
            readinessCheck(
                expectedProgramFingerprint == null ||
                    evidence.semanticThemeProgramFingerprint == expectedProgramFingerprint,
            ) { "UI Sync renderer $owner semantic/theme program fingerprint changed" }
            readinessCheck(evidence.semanticCompilerActive) {
                "UI Sync renderer $owner semantic compiler is not active"
            }
            readinessCheck(evidence.themeCompilerActive) {
                "UI Sync renderer $owner theme compiler is not active"
            }
            readinessCheck(evidence.backendLive) {
                "UI Sync renderer $owner backend is not live"
            }
            return evidence
        }

        private fun rendererLeaseDisplayIds(
            untrusted: Set<String>,
            owner: String,
        ): Set<String> {
            val snapshot = LinkedHashSet<String>()
            val iterator = try {
                untrusted.iterator()
            } catch (failure: Throwable) {
                throw UiSyncRendererReadinessValidationException(
                    "UI Sync renderer $owner display IDs could not be read",
                    failure,
                )
            }
            try {
                while (iterator.hasNext()) {
                    readinessCheck(snapshot.size < MAXIMUM_DISPLAY_ENDPOINTS) {
                        "UI Sync renderer $owner exposes too many display IDs"
                    }
                    readinessCheck(snapshot.add(iterator.next())) {
                        "UI Sync renderer $owner display iterator contains duplicates"
                    }
                }
            } catch (failure: UiSyncRendererReadinessValidationException) {
                throw failure
            } catch (failure: Throwable) {
                throw UiSyncRendererReadinessValidationException(
                    "UI Sync renderer $owner display IDs could not be read",
                    failure,
                )
            }
            return Collections.unmodifiableSet(snapshot)
        }

        private inline fun readinessCheck(
            condition: Boolean,
            lazyMessage: () -> String,
        ) {
            if (!condition) throw UiSyncRendererReadinessValidationException(lazyMessage())
        }

        /**
         * Re-reads [read] while it reports the transient [busy] value, for at most
         * [PHYSICAL_BUSY_SETTLE_NANOS]. It never converts BUSY into readiness: callers accept
         * only a later CURRENT value, and a BUSY that persists, or an interrupt, fails closed.
         */
        private inline fun <S> awaitSettledPhysicalStatus(busy: S, read: () -> S): S {
            val deadline = System.nanoTime() + PHYSICAL_BUSY_SETTLE_NANOS
            var status = read()
            while (status == busy && !Thread.currentThread().isInterrupted &&
                System.nanoTime() < deadline
            ) {
                LockSupport.parkNanos(PHYSICAL_BUSY_POLL_NANOS)
                status = read()
            }
            return status
        }

        private fun validateBindingCoverage(
            specs: List<UiSyncDisplayRuntimeSpec>,
            snapshot: UltraDisplayBindingSnapshot,
        ) {
            val endpointIds = specs.map { it.descriptor.displayId }
            require(endpointIds.toSet().size == endpointIds.size) {
                "UI Sync provisioning contains duplicate display IDs"
            }
            val resourceIds = bindingDisplayIds(snapshot)
            val rowIds = snapshot.bindings.map { it.uiSyncDisplayId.value }.toSet()
            require(endpointIds.toSet() == resourceIds && rowIds == resourceIds) {
                "UI Sync bindings must cover every endpoint display ID exactly once"
            }
            require(snapshot.bindings.size == endpointIds.size) {
                "UI Sync bindings must have the same cardinality as display endpoints"
            }
        }

        private fun boundedDisplaySpecs(
            displays: Collection<UiSyncDisplayRuntimeSpec>,
        ): List<UiSyncDisplayRuntimeSpec> {
            val frozen = ArrayList<UiSyncDisplayRuntimeSpec>()
            val iterator = displays.iterator()
            while (iterator.hasNext()) {
                require(frozen.size < MAXIMUM_DISPLAY_ENDPOINTS) {
                    "UI Sync provisioning exceeds $MAXIMUM_DISPLAY_ENDPOINTS display endpoints"
                }
                frozen += iterator.next().detached()
            }
            return Collections.unmodifiableList(frozen)
        }

        private fun bindingDisplayIds(snapshot: UltraDisplayBindingSnapshot): Set<String> =
            snapshot.resources.uiSyncDisplayIds.mapTo(linkedSetOf()) { it.value }

        private fun validateStaticPlanCoverage(
            specs: List<UiSyncDisplayRuntimeSpec>,
            plan: UiSyncRendererStaticPlan,
        ) {
            val endpointIds = specs.map { it.descriptor.displayId }
            require(endpointIds.toSet().size == endpointIds.size) {
                "UI Sync provisioning contains duplicate display IDs"
            }
            val plannedIds = plan.outputs.mapNotNull { it.uiSyncDisplayId?.value }
            require(plannedIds.toSet().size == plannedIds.size) {
                "UI Sync renderer plan contains duplicate display IDs"
            }
            require(endpointIds.toSet() == plannedIds.toSet()) {
                "UI Sync endpoints must exactly cover the plan's UI Sync outputs"
            }
        }

        private fun UiSyncRendererStaticPlan.outputIds(): Set<UltraDisplayOutputId> =
            outputs.mapTo(linkedSetOf()) { it.outputId }

        private fun fingerprint(
            info: AirPlayUiSyncInfo,
            specs: List<UiSyncDisplayRuntimeSpec>,
            plan: UiSyncRendererStaticPlan,
        ): UiSyncSchemaFingerprint {
            val wire = linkedMapOf<String, Any?>(
                "clusterControlRcsUuid" to
                    RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid.toString(),
                "uiSyncChannelUuid" to
                    CarPlayUiSyncChannelDescriptor.UI_SYNC_CHANNEL_UUID.toString(),
                "clusterControlImplementation" to UI_SYNC_RCS_IMPLEMENTATION_ID,
                "uiSyncInfo" to stableWireValue(info.toWireMap()),
                "displays" to specs
                    .sortedBy { it.descriptor.displayId }
                    .map(UiSyncDisplayRuntimeSpec::fingerprintWire),
                "rendererPlan" to rendererPlanWire(plan),
            )
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("xcertplay-ui-sync-provisioning-v2\u0000".toByteArray(Charsets.UTF_8))
            digest.update(BplistCodec.encode(wire))
            return UiSyncSchemaFingerprint(digest.digest())
        }

        private fun fingerprint(
            info: AirPlayUiSyncInfo,
            specs: List<UiSyncDisplayRuntimeSpec>,
            bindings: UltraDisplayBindingSnapshot,
        ): UiSyncSchemaFingerprint {
            val wire = linkedMapOf<String, Any?>(
                "clusterControlRcsUuid" to
                    RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid.toString(),
                "uiSyncChannelUuid" to
                    CarPlayUiSyncChannelDescriptor.UI_SYNC_CHANNEL_UUID.toString(),
                "clusterControlImplementation" to UI_SYNC_RCS_IMPLEMENTATION_ID,
                "uiSyncInfo" to stableWireValue(info.toWireMap()),
                "displays" to specs
                    .sortedBy { it.descriptor.displayId }
                    .map(UiSyncDisplayRuntimeSpec::fingerprintWire),
                "bindings" to bindings.bindings
                    .sortedBy { it.uiSyncDisplayId.value }
                    .map(::bindingWire),
            )
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("xcertplay-ui-sync-provisioning-v1\u0000".toByteArray(Charsets.UTF_8))
            digest.update(BplistCodec.encode(wire))
            return UiSyncSchemaFingerprint(digest.digest())
        }

        private fun bindingFingerprint(
            bindings: UltraDisplayBindingSnapshot,
        ): UiSyncBindingFingerprint {
            val wire = bindings.bindings
                .sortedBy { it.uiSyncDisplayId.value }
                .map(::bindingWire)
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("xcertplay-ui-sync-bindings-v1\u0000".toByteArray(Charsets.UTF_8))
            digest.update(BplistCodec.encode(wire))
            return UiSyncBindingFingerprint(digest.digest())
        }

        private fun bindingFingerprint(
            plan: UiSyncRendererStaticPlan,
        ): UiSyncBindingFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("xcertplay-ui-sync-renderer-plan-v3\u0000".toByteArray(Charsets.UTF_8))
            digest.update(BplistCodec.encode(rendererPlanWire(plan)))
            return UiSyncBindingFingerprint(digest.digest())
        }

        private fun rendererPlanWire(plan: UiSyncRendererStaticPlan): Map<String, Any?> =
            linkedMapOf(
                "outputs" to plan.outputs
                    .sortedWith(compareBy({ it.outputId.value }, { it.topologyDisplayId.value.toString() }))
                    .map { output ->
                        linkedMapOf<String, Any?>(
                            "topologyDisplayId" to output.topologyDisplayId.value.toString(),
                            "outputKind" to when (output.outputId) {
                                is UltraDisplayOutputId.Android -> "android"
                                is UltraDisplayOutputId.Custom -> "custom"
                            },
                            "outputId" to output.outputId.value,
                        ).apply {
                            output.uiSyncDisplayId?.let { put("uiSyncDisplayId", it.value) }
                        }
                    },
                "ingresses" to plan.ingressSpecs
                    .sortedBy { it.id.value }
                    .map { spec ->
                        linkedMapOf<String, Any?>(
                            "ingressId" to spec.id.value,
                            "streamType" to spec.airPlayStreamIdentity.type,
                        ).apply {
                            spec.airPlayStreamIdentity.streamConnectionId?.let {
                                put("streamConnectionId", it)
                            }
                            spec.airPlayStreamIdentity.clientTypeUuid?.let {
                                put("streamClientTypeUuid", it)
                            }
                            spec.setupDisplayUuid?.let {
                                put("setupDisplayUuid", it)
                            }
                        }
                    },
            )

        private fun bindingWire(binding: UltraDisplayBinding): Map<String, Any?> =
            linkedMapOf<String, Any?>(
                "uiSyncDisplayId" to binding.uiSyncDisplayId.value,
                "topologyDisplayId" to binding.topologyDisplayId.value.toString(),
                "streamType" to binding.airPlayStreamIdentity.type,
                "outputKind" to when (binding.outputId) {
                    is UltraDisplayOutputId.Android -> "android"
                    is UltraDisplayOutputId.Custom -> "custom"
                },
                "outputId" to binding.outputId.value,
            ).apply {
                binding.airPlayStreamIdentity.streamConnectionId?.let {
                    put("streamConnectionId", it)
                }
                binding.airPlayStreamIdentity.clientTypeUuid?.let {
                    put("streamClientTypeUuid", it)
                }
            }
    }
}

/** Sole post-transfer owner used by [AirPlayUltraRuntime]. */
internal class UiSyncRuntimeProvisioningOwner(
    private val provisioning: UiSyncRuntimeProvisioning,
    private val token: Any,
) : Closeable {
    val rendererReadiness: AirPlayUiSyncRendererReadinessDescriptor
        get() = provisioning.rendererReadiness
    val schemaFingerprint: UiSyncSchemaFingerprint
        get() = provisioning.schemaFingerprint
    val bindingFingerprint: UiSyncBindingFingerprint
        get() = provisioning.bindingFingerprint
    val displayIds: Set<String>
        get() = provisioning.ownerDisplayIds(token)
    val rendererReadinessReceipt: RendererReadinessReceipt?
        get() = provisioning.ownerRendererReadinessReceipt(token)

    fun uiSyncInfo(): AirPlayUiSyncInfo = provisioning.ownerInfo(token)

    fun endpointTemplates(): CarPlayUiSyncEndpointRegistry =
        provisioning.ownerEndpointTemplates(token)

    fun isLive(): Boolean = provisioning.ownerIsLive(token)

    fun openSession(
        onAcquired: (UiSyncProvisionedSession) -> Unit = {},
        beforePhysicalBind: (UiSyncProvisionedSession) -> Unit = {},
        onPhysicalCandidateRetired: (UiSyncProvisionedSession) -> Unit = {},
    ): UiSyncProvisionedSession = provisioning.openSession(
        token, onAcquired, beforePhysicalBind, onPhysicalCandidateRetired,
    )

    override fun close() = provisioning.ownerClose(token)
}

private fun stableWireValue(value: Any?): Any? = when (value) {
    is ByteArray -> value.copyOf()
    is List<*> -> value.map(::stableWireValue)
    is Map<*, *> -> value.entries
        .map { entry ->
            val key = entry.key as? String
                ?: throw IllegalArgumentException("UI Sync provisioning dictionary key is not a string")
            key to stableWireValue(entry.value)
        }
        .sortedBy { it.first }
        .associateTo(linkedMapOf()) { it }
    else -> value
}
