package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable SHA-256 identity for the exact CAF sidecar plus every routed config tree. */
class CafSchemaFingerprint internal constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == SHA256_BYTES)
    }

    fun bytes(): ByteArray = value.copyOf()

    fun hex(): String = value.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    override fun equals(other: Any?): Boolean =
        other is CafSchemaFingerprint && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "CafSchemaFingerprint(sha256=${hex()})"

    private companion object {
        const val SHA256_BYTES = 32
    }
}

/** Static implementation identities selected by the authorized profile compiler. */
class CafRuntimeAuthorizationExpectation(
    val decoderImplementationId: String,
    handlerImplementationIds: Map<Long, String>,
) {
    val handlerImplementationIds: Map<Long, String> = boundedImplementationIds(
        handlerImplementationIds,
    )

    init {
        requireImplementationId(decoderImplementationId, "CAF decoder implementation ID")
        require(this.handlerImplementationIds.isNotEmpty()) {
            "Authorized CAF provisioning requires at least one handler implementation"
        }
    }
}

/** Dynamically live CAF evidence compared against the compiled OEM runtime manifest. */
class CafRuntimeAuthorizationSnapshot internal constructor(
    val schemaFingerprint: CafSchemaFingerprint,
    val decoderImplementationId: String,
    handlerImplementationIds: Map<Long, String>,
) {
    val handlerImplementationIds: Map<Long, String> = boundedImplementationIds(
        handlerImplementationIds,
    )
}

private fun boundedImplementationIds(values: Map<Long, String>): Map<Long, String> {
    val result = LinkedHashMap<Long, String>()
    val iterator = values.entries.iterator()
    while (iterator.hasNext()) {
        require(result.size < MAXIMUM_AUTHORIZED_CAF_PLUGINS) {
            "Authorized CAF implementation map exceeds $MAXIMUM_AUTHORIZED_CAF_PLUGINS entries"
        }
        val entry = iterator.next()
        requireImplementationId(entry.value, "CAF handler implementation ID")
        require(result.put(entry.key, entry.value) == null) {
            "Authorized CAF implementation map contains a duplicate plugin ID"
        }
    }
    return Collections.unmodifiableMap(result)
}

private fun requireImplementationId(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAXIMUM_CAF_IMPLEMENTATION_ID_CHARS) {
        "$label is invalid"
    }
    require(value.none(Char::isISOControl)) { "$label contains a control character" }
}

private const val MAXIMUM_AUTHORIZED_CAF_PLUGINS = 256
private const val MAXIMUM_CAF_IMPLEMENTATION_ID_CHARS = 512

/**
 * Proof that AirPlay Vehicle State Protocol metadata and runtime handlers share one frozen source.
 *
 * Callers cannot inject a separate sidecar. Plugin configs and config roots are detached before
 * readiness is probed, and all later channel descriptors are created from that frozen registry.
 */
class CafRuntimeProvisioning private constructor(
    private val registry: CafPluginRegistry,
    private val protocolInfoWire: Map<String, Any?>,
    val schemaFingerprint: CafSchemaFingerprint,
    private val decoderFactory: CafConfigTreeDecoderFactory,
    private val authorizationExpectation: CafRuntimeAuthorizationExpectation?,
    private val currentAuthorization: (() -> Boolean)?,
) : Closeable {
    private val lifecycleLock = Any()
    private val factoryLease = CafProvisionedFactoryLease(registry, decoderFactory)
    private val authorizationRevoked = AtomicBoolean(false)
    private var runtimeOwner: Any? = null
    private var diagnosticUsed = false
    private var closeRequested = false
    private var closeInFlight = false
    private var resourcesClosed = false
    private val diagnosticSession = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        check(diagnosticIsLive()) { "CAF diagnostic factory is not live" }
        factoryLease.openSession(::diagnosticIsLive)
    }

    /**
     * Compatibility-only factory view for direct protocol diagnostics. Authorized runtimes use
     * [transferToRuntime] and receive a fresh child for every AirPlay control session instead.
     */
    internal val handlerFactory: RcsDataStreamHandlerFactory =
        RcsDataStreamHandlerFactory { clientType ->
            val admitted = synchronized(lifecycleLock) {
                if (runtimeOwner != null || closeRequested || resourcesClosed) {
                    false
                } else {
                    diagnosticUsed = true
                    true
                }
            }
            if (!admitted) null else diagnosticSession.value.handlerFactory.descriptor(clientType)
        }

    fun vehicleStateProtocolInfo(): CafVehicleStateProtocolInfo {
        requireUnclosed()
        return detachedProtocolInfo()
    }

    /**
     * Returns evidence only for the explicit profile-compiled construction path. It is invalid as
     * soon as ownership transfers to a runtime; the runtime then owns all subsequent liveness
     * checks and revocation.
     */
    fun authorizationSnapshot(): CafRuntimeAuthorizationSnapshot? {
        if (!authorizationIsCurrent()) return null
        val expectation = synchronized(lifecycleLock) {
            if (runtimeOwner != null || diagnosticUsed || closeRequested || resourcesClosed) {
                return null
            }
            authorizationExpectation ?: return null
        }
        val live = try {
            factoryLease.isLive && authorizationImplementationsMatch(expectation)
        } catch (_: Throwable) {
            false
        }
        if (!live) return null
        val result = CafRuntimeAuthorizationSnapshot(
            schemaFingerprint = schemaFingerprint,
            decoderImplementationId = expectation.decoderImplementationId,
            handlerImplementationIds = expectation.handlerImplementationIds,
        )
        val stillAuthorized = authorizationIsCurrent()
        return synchronized(lifecycleLock) {
            if (stillAuthorized && runtimeOwner == null && !diagnosticUsed &&
                !closeRequested && !resourcesClosed &&
                authorizationExpectation === expectation
            ) {
                result
            } else {
                null
            }
        }
    }

    /** Transfers the frozen CAF factory tree to exactly one [AirPlayUltraRuntime] owner. */
    internal fun transferToRuntime(): CafRuntimeProvisioningOwner {
        check(authorizationIsCurrent()) { "CAF runtime authorization was revoked" }
        val token = Any()
        synchronized(lifecycleLock) {
            check(!closeRequested && !resourcesClosed) { "CAF provisioning is closed" }
            check(runtimeOwner == null) { "CAF provisioning ownership was already transferred" }
            check(!diagnosticUsed) {
                "CAF provisioning diagnostic factory was already used"
            }
            runtimeOwner = token
        }
        return CafRuntimeProvisioningOwner(this, token)
    }

    internal fun ownerInfo(token: Any): CafVehicleStateProtocolInfo {
        requireOwnerLive(token)
        return detachedProtocolInfo()
    }

    internal fun ownerIsLive(token: Any): Boolean {
        synchronized(lifecycleLock) {
            if (runtimeOwner !== token || closeRequested || resourcesClosed) return false
        }
        if (!authorizationIsCurrent()) return false
        val factoryLive = try {
            factoryLease.isLive && (
                authorizationExpectation == null ||
                    authorizationImplementationsMatch(authorizationExpectation)
                )
        } catch (_: Throwable) {
            false
        }
        return factoryLive && authorizationIsCurrent() && synchronized(lifecycleLock) {
            runtimeOwner === token && !closeRequested && !resourcesClosed
        }
    }

    internal fun ownerDescriptor(token: Any, clientType: RcsClientType):
        RcsDataStreamHandlerDescriptor? {
        requireOwnerLive(token)
        if (!factoryLease.supports(clientType)) return null
        return RcsDataStreamHandlerDescriptor(
            clientType = clientType,
            implementationId = CAF_IMPLEMENTATION_ID,
            readinessProbe = {
                if (ownerIsLive(token)) RcsDataStreamHandlerProbe.ready()
                else RcsDataStreamHandlerProbe.notReady("caf_factory_unavailable")
            },
            handlerFactory = {
                throw IllegalStateException(
                    "CAF handler creation requires a per-control-session child",
                )
            },
        )
    }

    internal fun openSession(token: Any): CafProvisionedSession {
        requireOwnerLive(token)
        return factoryLease.openSession { ownerIsLive(token) }
    }

    internal fun ownerClose(token: Any) = closeOwned(token)

    /** Once transferred, the runtime owner is the only closer; stale profile closes are harmless. */
    override fun close() {
        val transferred = synchronized(lifecycleLock) { runtimeOwner != null }
        if (transferred) return
        closeOwned(null)
    }

    private fun closeOwned(owner: Any?) {
        synchronized(lifecycleLock) {
            if (owner == null) {
                if (runtimeOwner != null) return
            } else {
                check(runtimeOwner === owner) { "Stale CAF provisioning owner" }
            }
            closeRequested = true
            if (resourcesClosed) return
            check(!closeInFlight) { "CAF provisioning close is already in flight" }
            closeInFlight = true
        }

        val retirement = runCatching { factoryLease.close() }
        synchronized(lifecycleLock) {
            closeInFlight = false
            if (retirement.isSuccess) resourcesClosed = true
        }
        retirement.getOrThrow()
    }

    private fun detachedProtocolInfo(): CafVehicleStateProtocolInfo =
        CafVehicleStateProtocolInfo.decode(freezeCafWireMap(protocolInfoWire))

    private fun requireUnclosed() {
        check(authorizationIsCurrent() && synchronized(lifecycleLock) {
            !closeRequested && !resourcesClosed
        }) {
            "CAF provisioning is closed"
        }
    }

    private fun requireOwnerLive(token: Any) {
        check(ownerIsLive(token)) { "CAF provisioning owner is not live" }
    }

    private fun diagnosticIsLive(): Boolean = authorizationIsCurrent() &&
        synchronized(lifecycleLock) {
            runtimeOwner == null && !closeRequested && !resourcesClosed
        }

    /** Allows an external, lifecycle-owned monitor to observe the frozen runtime's live gate. */
    internal fun observeCurrentAuthorization(): Boolean = authorizationIsCurrent()

    /** Dynamic denial can only retire a previously frozen authorization, never revive it. */
    private fun authorizationIsCurrent(): Boolean {
        val predicate = currentAuthorization ?: return true
        if (authorizationRevoked.get()) return false
        val allowed = runCatching(predicate).getOrDefault(false)
        if (!allowed && authorizationRevoked.compareAndSet(false, true)) {
            val streams = factoryLease.revokeAndSnapshotStreams()
            if (streams.isNotEmpty()) {
                // A source read may hold an RCS callback lock. Close physical streams outside
                // every CAF owner lock and never block the revocation observer behind that read.
                Thread({ streams.forEach { runCatching { it.close() } } },
                    "caf-authorization-revocation").apply { isDaemon = true }.start()
            }
        }
        return allowed && !authorizationRevoked.get()
    }

    private fun authorizationImplementationsMatch(
        expectation: CafRuntimeAuthorizationExpectation,
    ): Boolean = try {
        decoderFactory.implementationId == expectation.decoderImplementationId &&
            registry.allRegistrations().associate { registration ->
                registration.pluginId to registration.handlerFactory.implementationId
            } == expectation.handlerImplementationIds
    } catch (_: Throwable) {
        false
    }

    companion object {
        fun from(
            source: CafPluginRegistry,
            decoderFactory: CafConfigTreeDecoderFactory =
                ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
        ): CafRuntimeProvisioning = build(source, decoderFactory, null, null)

        /**
         * Product authorization path. Implementation identities originate in the signed-profile
         * compiler and are later compared with the same compiled manifest by the coordinator.
         */
        fun fromAuthorizedProfile(
            source: CafPluginRegistry,
            authorizationExpectation: CafRuntimeAuthorizationExpectation,
            decoderFactory: CafConfigTreeDecoderFactory =
                ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            currentAuthorization: (() -> Boolean)? = null,
        ): CafRuntimeProvisioning = build(source, decoderFactory,
            authorizationExpectation, currentAuthorization)

        private fun build(
            source: CafPluginRegistry,
            decoderFactory: CafConfigTreeDecoderFactory,
            authorizationExpectation: CafRuntimeAuthorizationExpectation?,
            currentAuthorization: (() -> Boolean)?,
        ): CafRuntimeProvisioning {
            authorizationExpectation?.let { expectation ->
                require(decoderFactory.implementationId == expectation.decoderImplementationId) {
                    "CAF decoder implementation does not match the authorized profile"
                }
            }
            val validationRegistrations = source.allRegistrations().map { registration ->
                val frozenClientTypes = Collections.unmodifiableSet(
                    LinkedHashSet(registration.clientTypes),
                )
                val unsupportedClientTypes = frozenClientTypes - CONFIRMED_CLIENT_TYPES
                if (unsupportedClientTypes.isNotEmpty()) {
                    throw CafProtocolException(
                        "CAF plugin ${CafUInt64.toString(registration.pluginId)} declares " +
                            "an unsupported client type",
                    )
                }
                val schema = try {
                    registration.schemaProvider()
                } catch (error: Throwable) {
                    throw CafProtocolException(
                        "CAF plugin ${CafUInt64.toString(registration.pluginId)} schema snapshot failed",
                        error,
                    )
                } ?: throw CafProtocolException(
                    "CAF plugin ${CafUInt64.toString(registration.pluginId)} has no schema to provision",
                )
                val frozenSchema = CafConfigTree.decode(schema.root)
                val frozenPluginConfig = freezeCafWireMap(registration.wirePluginConfig())
                val expectedSchemaWire = schemaWire(registration.pluginId, frozenSchema)
                val pluginId = registration.pluginId
                val expectedHandlerImplementationId = authorizationExpectation
                    ?.handlerImplementationIds
                    ?.get(pluginId)
                if (authorizationExpectation != null) {
                    require(
                        registration.handlerFactory.implementationId ==
                            expectedHandlerImplementationId,
                    ) {
                        "CAF handler factory does not match the authorized profile"
                    }
                }
                registration.copy(
                    clientTypes = frozenClientTypes,
                    handlerFactory = object : CafPluginHandlerFactory {
                        override val implementationId: String =
                            registration.handlerFactory.implementationId

                        override fun create(session: CafProtocolSession): CafPluginHandler {
                            val handler = registration.handlerFactory.create(session)
                            try {
                                val bound = handler as? CafSchemaBoundPluginHandler
                                    ?: throw CafProtocolException(
                                        "CAF plugin ${CafUInt64.toString(pluginId)} " +
                                            "handler does not expose its schema snapshot",
                                    )
                                if (expectedHandlerImplementationId != null &&
                                    bound.implementationId != expectedHandlerImplementationId
                                ) {
                                    throw CafProtocolException(
                                        "CAF plugin ${CafUInt64.toString(pluginId)} " +
                                            "handler implementation changed",
                                    )
                                }
                                val actualSchemaWire = schemaWire(pluginId, bound.schemaSnapshot())
                                if (!MessageDigest.isEqual(expectedSchemaWire, actualSchemaWire)) {
                                    throw CafProtocolException(
                                        "CAF plugin ${CafUInt64.toString(pluginId)} " +
                                            "handler schema does not match its provisioning snapshot",
                                    )
                                }
                                return handler
                            } catch (failure: Throwable) {
                                val protocolFailure = if (failure is CafProtocolException) {
                                    failure
                                } else {
                                    CafProtocolException(
                                        "CAF plugin ${CafUInt64.toString(pluginId)} " +
                                            "handler schema validation failed",
                                        failure,
                                    )
                                }
                                val cleanupFailure = runCatching { handler.close() }.exceptionOrNull()
                                if (cleanupFailure != null) {
                                    if (cleanupFailure !== protocolFailure) {
                                        protocolFailure.addSuppressed(cleanupFailure)
                                    }
                                    throw CafPluginConstructionException(protocolFailure, handler)
                                }
                                throw protocolFailure
                            }
                        }
                    },
                    pluginConfig = frozenPluginConfig,
                    configTreeDecoder = CafConfigTreeDecoder {
                        CafConfigTree.decode(frozenSchema.root)
                    },
                    schemaProvider = { CafConfigTree.decode(frozenSchema.root) },
                )
            }
            val validationRegistry = CafPluginRegistry.from(validationRegistrations)
            val validatedPluginIds = LinkedHashSet<Long>()
            CONFIRMED_CLIENT_TYPES.forEach { clientType ->
                validationRegistry.validateFor(clientType, decoderFactory)
                validationRegistry.registrationsFor(clientType).forEach { registration ->
                    validatedPluginIds += registration.pluginId
                }
            }
            val unvalidatedRegistrations = validationRegistrations.filterNot { registration ->
                registration.pluginId in validatedPluginIds
            }
            if (unvalidatedRegistrations.isNotEmpty()) {
                throw CafProtocolException("CAF provisioning contains an unvalidated registration")
            }
            authorizationExpectation?.let { expectation ->
                val pluginIds = validationRegistrations.map(CafPluginRegistration::pluginId).toSet()
                require(expectation.handlerImplementationIds.keys == pluginIds) {
                    "Authorized CAF handler identities must cover every plugin exactly once"
                }
            }

            // Runtime readiness is validated above against the source callback, then made stable.
            // A channel open can still fail closed if its factory or schema binding is no longer
            // usable, but advertisement/probe results cannot drift after successful provisioning.
            val registry = CafPluginRegistry.from(
                validationRegistrations.map { registration ->
                    registration.copy(runtimeReady = { true })
                },
            )
            val info = registry.vehicleStateProtocolInfo()
            return CafRuntimeProvisioning(
                registry = registry,
                protocolInfoWire = freezeCafWireMap(info.asWireMap()),
                schemaFingerprint = fingerprint(info, registry),
                decoderFactory = decoderFactory,
                authorizationExpectation = authorizationExpectation,
                currentAuthorization = currentAuthorization,
            )
        }

        private val CONFIRMED_CLIENT_TYPES: Set<RcsClientType> =
            Collections.unmodifiableSet(
                linkedSetOf(
                    RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                    RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                ),
            )

        private fun fingerprint(
            info: CafVehicleStateProtocolInfo,
            registry: CafPluginRegistry,
        ): CafSchemaFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("xcertplay-caf-provisioning-v1\u0000".toByteArray(Charsets.UTF_8))
            updateLengthPrefixed(digest, BplistCodec.encode(info.asWireMap()))
            registry.allRegistrations()
                .sortedWith { left, right ->
                    CafUInt64.toBigInteger(left.pluginId)
                        .compareTo(CafUInt64.toBigInteger(right.pluginId))
                }
                .forEach { registration ->
                    val schema = requireNotNull(registration.schemaProvider())
                    updateLengthPrefixed(
                        digest,
                        schemaWire(registration.pluginId, schema),
                    )
                }
            return CafSchemaFingerprint(digest.digest())
        }

        private fun updateLengthPrefixed(digest: MessageDigest, bytes: ByteArray) {
            val length = bytes.size
            digest.update(
                byteArrayOf(
                    (length ushr 24).toByte(),
                    (length ushr 16).toByte(),
                    (length ushr 8).toByte(),
                    length.toByte(),
                ),
            )
            digest.update(bytes)
        }

        private fun schemaWire(pluginId: Long, schema: CafConfigTree): ByteArray =
            CarAccessoryMessages.configResponse(
                pluginId,
                transactionId = 1L,
                values = schema.root,
            ).rcsBody
    }
}

/** Sole post-transfer CAF owner held by [AirPlayUltraRuntime]. */
internal class CafRuntimeProvisioningOwner(
    private val provisioning: CafRuntimeProvisioning,
    private val token: Any,
) : Closeable {
    val schemaFingerprint: CafSchemaFingerprint
        get() = provisioning.schemaFingerprint

    val handlerFactory: RcsDataStreamHandlerFactory =
        RcsDataStreamHandlerFactory { clientType -> provisioning.ownerDescriptor(token, clientType) }

    fun vehicleStateProtocolInfo(): CafVehicleStateProtocolInfo = provisioning.ownerInfo(token)

    fun isLive(): Boolean = provisioning.ownerIsLive(token)

    fun openSession(): CafProvisionedSession = provisioning.openSession(token)

    override fun close() = provisioning.ownerClose(token)
}

/** Factory-level owner for every per-control-session CAF child. */
private class CafProvisionedFactoryLease(
    private val registry: CafPluginRegistry,
    private val decoderFactory: CafConfigTreeDecoderFactory,
) : Closeable {
    private val lock = Any()
    private val sessions = IdentityHashMap<CafProvisionedSession, Unit>()
    private var opensInFlight = 0
    private var closeRequested = false
    private var closeInFlight = false
    private var resourcesClosed = false

    val isLive: Boolean
        get() = synchronized(lock) { !closeRequested && !resourcesClosed }

    fun supports(clientType: RcsClientType): Boolean =
        isLive && registry.supports(clientType)

    /** Latch denial before returning stream owners; physical close runs off the observer thread. */
    fun revokeAndSnapshotStreams(): List<RcsDataStream> {
        val children = synchronized(lock) {
            closeRequested = true
            sessions.keys.toList()
        }
        return children.flatMap(CafProvisionedSession::revokeAndSnapshotStreams)
    }

    fun openSession(parentLive: () -> Boolean): CafProvisionedSession {
        synchronized(lock) {
            check(!closeRequested && !resourcesClosed) { "CAF factory is closed" }
            check(opensInFlight < MAXIMUM_SESSION_OPENS) {
                "CAF factory has too many session opens in flight"
            }
            opensInFlight = Math.addExact(opensInFlight, 1)
        }

        val candidate = CafProvisionedSession(
            registry = registry,
            decoderFactory = decoderFactory,
            parentLive = parentLive,
            factoryLive = { isLive },
            onClosed = { child -> synchronized(lock) { sessions.remove(child) } },
        )
        val accepted = synchronized(lock) {
            opensInFlight -= 1
            sessions[candidate] = Unit
            !closeRequested && !resourcesClosed
        }
        val parentAccepted = try {
            parentLive()
        } catch (_: Throwable) {
            false
        }
        if (!accepted || !parentAccepted) {
            try {
                candidate.close()
            } catch (cleanup: Throwable) {
                throw IllegalStateException("CAF factory closed during session creation", cleanup)
            }
            throw IllegalStateException("CAF factory closed during session creation")
        }
        return candidate
    }

    override fun close() {
        val children = synchronized(lock) {
            closeRequested = true
            if (resourcesClosed) return
            check(!closeInFlight) { "CAF factory close is already in flight" }
            check(opensInFlight == 0) { "CAF session creation is still in flight" }
            closeInFlight = true
            sessions.keys.toList()
        }

        var failure: Throwable? = null
        children.forEach { child ->
            try {
                child.close()
            } catch (problem: Throwable) {
                failure = combineCafFailures(failure, problem)
            }
        }
        synchronized(lock) {
            closeInFlight = false
            if (failure == null && sessions.isEmpty()) resourcesClosed = true
        }
        failure?.let { throw it }
    }

    private companion object {
        const val MAXIMUM_SESSION_OPENS = 1_024
    }
}

/** Fresh CAF state for exactly one AirPlay control session. */
internal class CafProvisionedSession(
    private val registry: CafPluginRegistry,
    private val decoderFactory: CafConfigTreeDecoderFactory,
    private val parentLive: () -> Boolean,
    private val factoryLive: () -> Boolean,
    private val onClosed: (CafProvisionedSession) -> Unit,
) : Closeable {
    private val lock = Any()
    private val handlers = IdentityHashMap<CafProtocolSession, Unit>()
    private val constructionOrphans = IdentityHashMap<CafProtocolSessionConstructionException, Unit>()
    private var activeUses = 0
    private var creationsInFlight = 0
    private var closeRequested = false
    private var closeInFlight = false
    private var resourcesClosed = false

    val handlerFactory: RcsDataStreamHandlerFactory = RcsDataStreamHandlerFactory { clientType ->
        if (!registry.supports(clientType) || !isLive()) {
            null
        } else {
            RcsDataStreamHandlerDescriptor(
                clientType = clientType,
                implementationId = CAF_IMPLEMENTATION_ID,
                readinessProbe = {
                    if (isLive()) RcsDataStreamHandlerProbe.ready()
                    else RcsDataStreamHandlerProbe.notReady("caf_session_unavailable")
                },
                handlerFactory = { stream -> createHandler(clientType, stream) },
            )
        }
    }

    fun isLive(): Boolean {
        synchronized(lock) {
            if (closeRequested || resourcesClosed) return false
        }
        val ancestorsLive = try {
            parentLive() && factoryLive()
        } catch (_: Throwable) {
            false
        }
        return ancestorsLive && synchronized(lock) { !closeRequested && !resourcesClosed }
    }

    fun supports(clientType: RcsClientType): Boolean =
        isLive() && registry.supports(clientType)

    /** Immediately gates channel service; [close] remains the retryable retirement operation. */
    internal fun revoke() {
        synchronized(lock) { closeRequested = true }
    }

    internal fun revokeAndSnapshotStreams(): List<RcsDataStream> = synchronized(lock) {
        closeRequested = true
        handlers.keys.map(CafProtocolSession::stream)
    }

    internal fun acquireUse(): Closeable? {
        synchronized(lock) {
            if (closeRequested || resourcesClosed) return null
            check(activeUses < MAXIMUM_ACTIVE_USES) { "CAF control session has too many active uses" }
            activeUses = Math.addExact(activeUses, 1)
        }
        val ancestorsLive = try {
            parentLive() && factoryLive()
        } catch (_: Throwable) {
            false
        }
        if (!ancestorsLive || synchronized(lock) { closeRequested || resourcesClosed }) {
            releaseUse()
            return null
        }
        val token = Any()
        var released = false
        return Closeable {
            synchronized(token) {
                if (!released) {
                    released = true
                    releaseUse()
                }
            }
        }
    }

    override fun close() {
        val snapshot = synchronized(lock) {
            closeRequested = true
            if (resourcesClosed) return
            check(!closeInFlight) { "CAF control session close is already in flight" }
            check(activeUses == 0) { "CAF control session still has active operations" }
            check(creationsInFlight == 0) { "CAF channel creation is still in flight" }
            closeInFlight = true
            handlers.keys.toList() to constructionOrphans.keys.toList()
        }

        var failure: Throwable? = null
        snapshot.first.forEach { handler ->
            try {
                handler.close()
            } catch (problem: Throwable) {
                failure = combineCafFailures(failure, problem)
            }
        }
        snapshot.second.forEach { orphan ->
            try {
                orphan.close()
                synchronized(lock) { constructionOrphans.remove(orphan) }
            } catch (problem: Throwable) {
                failure = combineCafFailures(failure, problem)
            }
        }

        val completed = synchronized(lock) {
            closeInFlight = false
            if (failure == null && handlers.isEmpty() && constructionOrphans.isEmpty()) {
                resourcesClosed = true
                true
            } else {
                false
            }
        }
        if (completed) onClosed(this)
        failure?.let { throw it }
    }

    private fun createHandler(
        clientType: RcsClientType,
        stream: RcsDataStream,
    ): RcsDataStreamHandler {
        synchronized(lock) {
            check(!closeRequested && !resourcesClosed) { "CAF control session is closed" }
            creationsInFlight = Math.addExact(creationsInFlight, 1)
        }

        val candidate = try {
            CafProtocolSession(
                clientType = clientType,
                stream = stream,
                plugins = registry,
                decoderFactory = decoderFactory,
                ownerUse = ::acquireUse,
                onClosed = { retired -> synchronized(lock) { handlers.remove(retired) } },
            )
        } catch (failure: Throwable) {
            synchronized(lock) {
                creationsInFlight -= 1
                if (failure is CafProtocolSessionConstructionException) {
                    constructionOrphans[failure] = Unit
                    closeRequested = true
                }
            }
            throw failure
        }

        val accepted = synchronized(lock) {
            creationsInFlight -= 1
            // Register before admission so a failed rejected close remains reachable for retry.
            handlers[candidate] = Unit
            !closeRequested && !resourcesClosed
        } && isLive()
        if (!accepted) {
            try {
                candidate.close()
            } catch (cleanup: Throwable) {
                throw IllegalStateException(
                    "CAF control session closed during channel creation",
                    cleanup,
                )
            }
            throw IllegalStateException("CAF control session closed during channel creation")
        }
        return candidate
    }

    private fun releaseUse() {
        synchronized(lock) {
            check(activeUses > 0) { "CAF control session use underflow" }
            activeUses -= 1
        }
    }

    private companion object {
        const val MAXIMUM_ACTIVE_USES = 65_536
    }
}

internal fun combineCafFailures(current: Throwable?, next: Throwable): Throwable =
    current?.also { if (next !== it) it.addSuppressed(next) } ?: next

internal const val CAF_IMPLEMENTATION_ID = "xcertplay.caf.v1"
