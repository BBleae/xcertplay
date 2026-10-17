package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlaySessionManagementInfo
import com.shilapi.xcertplay.airplay.AirPlayUltraConfiguration
import com.shilapi.xcertplay.airplay.AirPlayUltraRuntime
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.orchestration.CarPlayUltraAuthorizationDenialCode
import com.shilapi.xcertplay.orchestration.CarPlayUltraAuthorizationLifecycle
import com.shilapi.xcertplay.orchestration.CarPlayUltraPreparedRenewal
import com.shilapi.xcertplay.orchestration.CarPlayUltraAuthorizationRenewalException
import com.shilapi.xcertplay.orchestration.CarPlayUltraAuthorizationResult
import java.io.Closeable

internal sealed interface AuthorizedUltraCoordinatorResult<out T> {
    data class Granted<T>(val value: T) : AuthorizedUltraCoordinatorResult<T>

    data class Denied(
        val code: CarPlayUltraAuthorizationDenialCode,
    ) : AuthorizedUltraCoordinatorResult<Nothing>
}

internal interface AuthorizedUltraCoordinatorSessionHandle
internal interface AuthorizedUltraCoordinatorPreparationHandle
internal interface AuthorizedUltraCoordinatorLeaseHandle
internal interface AuthorizedUltraCoordinatorRuntimeHandle
internal interface AuthorizedUltraCoordinatorRenewalHandle

/** One bounded retirement task per revoked product session; rejection preserves its exact owner. */
internal fun interface UltraPolicyRetirementDispatcher {
    fun dispatch(task: Runnable): Boolean
}

private object ProductUltraPolicyRetirementDispatcher : UltraPolicyRetirementDispatcher {
    override fun dispatch(task: Runnable): Boolean = try {
        Thread(task, "xcertplay-ultra-policy-retirement").apply { isDaemon = true }.start()
        true
    } catch (_: Exception) {
        false
    }
}

internal class AuthorizedUltraCoordinatorRuntimeBinding(
    val handle: AuthorizedUltraCoordinatorRuntimeHandle,
    val runtime: AirPlayUltraRuntime,
    val configuration: AirPlayUltraConfiguration,
)

/** Testable transaction port; the product adapter below is the only runtime implementation. */
internal interface AuthorizedUltraProductCoordinatorPort : Closeable {
    /** Phase one revokes exact-child admission/permits without waiting on a Host ACK. */
    fun beginPolicyRevocation()

    /** Exact old physical child cover ACK; no successor/global output lookup is permitted. */
    fun policyCoverAck(): Boolean

    fun beginSession(
        vehicleId: String,
        receipt: MfiAuthenticationReceipt,
    ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorSessionHandle>

    fun prepare(
        token: AuthorizedUltraCoordinatorSessionHandle,
        receipt: MfiAuthenticationReceipt,
    ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorPreparationHandle>

    fun commit(
        preparation: AuthorizedUltraCoordinatorPreparationHandle,
        receipt: MfiAuthenticationReceipt,
    ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorLeaseHandle>

    fun openRuntime(
        lease: AuthorizedUltraCoordinatorLeaseHandle,
        sessionManagementInfo: AirPlaySessionManagementInfo?,
    ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorRuntimeBinding>

    fun prepareRenewal(
        token: AuthorizedUltraCoordinatorSessionHandle,
        runtime: AuthorizedUltraCoordinatorRuntimeHandle,
        predecessor: MfiAuthenticationReceipt,
        successor: MfiAuthenticationReceipt,
    ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorRenewalHandle>

    fun commitRenewal(
        renewal: AuthorizedUltraCoordinatorRenewalHandle,
    ): CarPlayUltraAuthorizationDenialCode?

    fun rollbackRenewal(
        renewal: AuthorizedUltraCoordinatorRenewalHandle,
    ): CarPlayUltraAuthorizationDenialCode?

    fun isRuntimeLive(handle: AuthorizedUltraCoordinatorRuntimeHandle): Boolean

    /** Optional narrow signed-asset match; absent providers deny without side effects. */
    fun allowsClusterAssetAcquisition(
        handle: AuthorizedUltraCoordinatorRuntimeHandle,
        assetId: String,
        authenticationIdentity: MfiAuthenticationIdentity,
    ): Boolean = false
}

/**
 * Sole product implementation of the controller-facing Ultra authorization lifecycle.
 *
 * The no-argument constructor is intentionally the only product construction path. The root
 * captures one immutable policy epoch before identity resolution; the coordinator receives the
 * same epoch instead of consulting the mutable composition root again. The caller cannot supply
 * a vehicle ID, provider, verifier, profile, or Store.
 */
internal class AuthorizedUltraProductSession private constructor(
    private val policyEpoch: UltraProfilePolicyEpoch,
    private val coordinatorFactory: (UltraProfilePolicyEpoch) -> AuthorizedUltraProductCoordinatorPort,
    private val retirementDispatcher: UltraPolicyRetirementDispatcher,
) : CarPlayUltraAuthorizationLifecycle {
    private class ActiveAuthorization(
        val coordinator: AuthorizedUltraProductCoordinatorPort,
        val token: AuthorizedUltraCoordinatorSessionHandle,
        val lease: AuthorizedUltraCoordinatorLeaseHandle,
        val runtimeLease: AuthorizedUltraCoordinatorRuntimeHandle,
        var receipt: MfiAuthenticationReceipt,
        val result: CarPlayUltraAuthorizationResult.Activated,
    )

    private class PreparedRenewal(
        private val session: AuthorizedUltraProductSession,
        internal val operation: Any,
        internal val current: ActiveAuthorization,
        internal val handle: AuthorizedUltraCoordinatorRenewalHandle,
        override val predecessor: MfiAuthenticationReceipt,
        override val successor: MfiAuthenticationReceipt,
    ) : CarPlayUltraPreparedRenewal {
        private enum class State { PREPARED, COMMITTED, ROLLED_BACK }

        private val stateLock = Any()
        private var state = State.PREPARED

        override val activation: CarPlayUltraAuthorizationResult.Activated
            get() = current.result

        override fun commit(): CarPlayUltraAuthorizationResult.Activated =
            synchronized(stateLock) {
                when (state) {
                    State.COMMITTED -> activation
                    State.ROLLED_BACK -> throw CarPlayUltraAuthorizationRenewalException(
                        CarPlayUltraAuthorizationDenialCode.STALE_GENERATION,
                    )
                    State.PREPARED -> session.commitPreparedRenewal(this).also {
                        state = State.COMMITTED
                    }
                }
            }

        override fun close() = synchronized(stateLock) {
            if (state != State.PREPARED) return
            session.rollbackPreparedRenewal(this)
            state = State.ROLLED_BACK
        }
    }

    constructor() : this(
        policyEpoch = UltraProfileCompositionRoot.capturePolicyEpoch(),
        coordinatorFactory = { epoch ->
            CoordinatorBackedAuthorizedUltraProductPort(
                UltraProfileActivationCoordinator.fromPolicyEpoch(epoch),
            )
        },
        retirementDispatcher = ProductUltraPolicyRetirementDispatcher,
    )

    private val stateLock = Object()
    private var active: ActiveAuthorization? = null
    private var activationOwner: AuthorizedUltraProductCoordinatorPort? = null
    private var pendingRetirement: AuthorizedUltraProductCoordinatorPort? = null
    private var activationOperation: Any? = null
    private var renewalOperation: Any? = null
    private var preparedRenewal: PreparedRenewal? = null
    private var closeInProgress = false
    private var closeRequested = false
    private var closed = false
    private var policyRetirementQueued = false
    /** A cover ACK may arrive while the previous worker is still unwinding a false ACK. */
    private var policyRetirementWakeup = false

    init {
        policyEpoch.registerParticipant(object : UltraProfilePolicyEpoch.Participant {
            override fun onEpochRevoked() {
                val owners = synchronized(stateLock) {
                    closeRequested = true
                    stateLock.notifyAll()
                    currentCoverOwnersLocked()
                }
                owners.forEach { it.beginPolicyRevocation() }
            }

            override fun coverAck(): Boolean {
                val provisional = synchronized(stateLock) { preparedRenewal }
                if (provisional != null) {
                    try { provisional.close() } catch (_: Exception) { return false }
                }
                val owners = synchronized(stateLock) {
                    if (activationOperation != null || renewalOperation != null) return false
                    currentCoverOwnersLocked()
                }
                return owners.all { it.policyCoverAck() }
            }

            override fun onCoverAcknowledged() = requestPolicyEpochRetirement()
        })
    }

    override fun activate(
        receipt: MfiAuthenticationReceipt,
        sessionManagementInfo: AirPlaySessionManagementInfo?,
    ): CarPlayUltraAuthorizationResult {
        if (!policyEpoch.isLive()) {
            requestPolicyEpochRetirement()
            return denied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
        }
        val operation = Any()
        synchronized(stateLock) {
            if (closed || closeRequested) return denied(CarPlayUltraAuthorizationDenialCode.CLOSED)
            if (pendingRetirement != null) {
                return denied(CarPlayUltraAuthorizationDenialCode.REVOCATION_FAILED)
            }
            if (activationOperation != null || renewalOperation != null) {
                return denied(CarPlayUltraAuthorizationDenialCode.BUSY)
            }
            if (active != null) return denied(CarPlayUltraAuthorizationDenialCode.ALREADY_ACTIVE)
            activationOperation = operation
        }

        val identity = try {
            policyEpoch.vehicleIdentityAuthority.resolve(receipt.authenticationIdentity)
        } catch (failure: Throwable) {
            finishUnownedActivation(operation)
            failure.rethrowIfError()
            return denied(CarPlayUltraAuthorizationDenialCode.VEHICLE_IDENTITY_FAILED)
        }
        if (!isUnownedActivationCurrent(operation)) {
            finishUnownedActivation(operation)
            return denied(CarPlayUltraAuthorizationDenialCode.CLOSED)
        }
        if (!policyEpoch.isLive()) {
            finishUnownedActivation(operation)
            requestPolicyEpochRetirement()
            return denied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
        }
        if (identity === TrustedUltraVehicleIdentityResult.Unavailable) {
            // No coordinator has been constructed and the AA05 receipt remains unconsumed.
            finishUnownedActivation(operation)
            return CarPlayUltraAuthorizationResult.Unavailable
        }
        val vehicleId = (identity as TrustedUltraVehicleIdentityResult.Resolved).vehicleId
        try {
            // Validate trusted authority output before the coordinator consumes the receipt.
            UltraRuntimeIdentity.from(vehicleId, receipt.authenticationIdentity)
        } catch (_: IllegalArgumentException) {
            finishUnownedActivation(operation)
            return denied(CarPlayUltraAuthorizationDenialCode.INVALID_REQUEST)
        }

        val coordinator = try {
            coordinatorFactory(policyEpoch)
        } catch (failure: Throwable) {
            finishUnownedActivation(operation)
            failure.rethrowIfError()
            return denied(CarPlayUltraAuthorizationDenialCode.PROVIDER_FAILED)
        }
        if (!attachActivationOwner(operation, coordinator)) {
            return retireUnpublishedAfterCloseRequest(operation, coordinator)
        }
        if (!policyEpoch.isLive()) {
            return denyAfterRetiring(
                operation, coordinator, CarPlayUltraAuthorizationDenialCode.STALE_GENERATION,
            )
        }

        val token = when (val started = try {
            coordinator.beginSession(vehicleId, receipt)
        } catch (failure: Throwable) {
            return denyAfterFailure(
                operation,
                coordinator,
                CarPlayUltraAuthorizationDenialCode.PROVIDER_FAILED,
                failure,
            )
        }) {
            is AuthorizedUltraCoordinatorResult.Granted -> started.value
            is AuthorizedUltraCoordinatorResult.Denied ->
                return denyAfterRetiring(operation, coordinator, started.code)
        }
        if (!isActivationOperationCurrent(operation, coordinator)) {
            return activationInterrupted(operation, coordinator)
        }
        if (!policyEpoch.isLive()) {
            return denyAfterRetiring(
                operation, coordinator, CarPlayUltraAuthorizationDenialCode.STALE_GENERATION,
            )
        }
        val preparation = when (val prepared = try {
            coordinator.prepare(token, receipt)
        } catch (failure: Throwable) {
            return denyAfterFailure(
                operation,
                coordinator,
                CarPlayUltraAuthorizationDenialCode.PROVIDER_FAILED,
                failure,
            )
        }) {
            is AuthorizedUltraCoordinatorResult.Granted -> prepared.value
            is AuthorizedUltraCoordinatorResult.Denied ->
                return denyAfterRetiring(operation, coordinator, prepared.code)
        }
        if (!isActivationOperationCurrent(operation, coordinator)) {
            return activationInterrupted(operation, coordinator)
        }
        if (!policyEpoch.isLive()) {
            return denyAfterRetiring(
                operation, coordinator, CarPlayUltraAuthorizationDenialCode.STALE_GENERATION,
            )
        }
        val lease = when (val committed = try {
            coordinator.commit(preparation, receipt)
        } catch (failure: Throwable) {
            return denyAfterFailure(
                operation,
                coordinator,
                CarPlayUltraAuthorizationDenialCode.COMMIT_FAILED,
                failure,
            )
        }) {
            is AuthorizedUltraCoordinatorResult.Granted -> committed.value
            is AuthorizedUltraCoordinatorResult.Denied ->
                return denyAfterRetiring(operation, coordinator, committed.code)
        }
        if (!isActivationOperationCurrent(operation, coordinator)) {
            return activationInterrupted(operation, coordinator)
        }
        if (!policyEpoch.isLive()) {
            return denyAfterRetiring(
                operation, coordinator, CarPlayUltraAuthorizationDenialCode.STALE_GENERATION,
            )
        }
        val opened = when (val result = try {
            coordinator.openRuntime(lease, sessionManagementInfo)
        } catch (failure: Throwable) {
            return denyAfterFailure(
                operation,
                coordinator,
                CarPlayUltraAuthorizationDenialCode.RUNTIME_CONSTRUCTION_FAILED,
                failure,
            )
        }) {
            is AuthorizedUltraCoordinatorResult.Granted -> result.value
            is AuthorizedUltraCoordinatorResult.Denied ->
                return denyAfterRetiring(operation, coordinator, result.code)
        }
        if (!isActivationOperationCurrent(operation, coordinator)) {
            return activationInterrupted(operation, coordinator)
        }
        if (!policyEpoch.isLive()) {
            return denyAfterRetiring(
                operation, coordinator, CarPlayUltraAuthorizationDenialCode.STALE_GENERATION,
            )
        }
        val runtimeLive = try {
            coordinator.isRuntimeLive(opened.handle)
        } catch (failure: Throwable) {
            return denyAfterFailure(
                operation,
                coordinator,
                CarPlayUltraAuthorizationDenialCode.LEASE_NOT_LIVE,
                failure,
            )
        }
        if (!receipt.isLiveForUltraActivation() || !runtimeLive || !policyEpoch.isLive()) {
            return denyAfterRetiring(
                operation,
                coordinator,
                CarPlayUltraAuthorizationDenialCode.LEASE_NOT_LIVE,
            )
        }

        val activated = CarPlayUltraAuthorizationResult.Activated(
            runtime = opened.runtime,
            configuration = opened.configuration,
        )
        val policyCurrentAtPublication = policyEpoch.isLive()
        val published = synchronized(stateLock) {
            if (activationOperation === operation && activationOwner === coordinator &&
                !closed && !closeRequested && active == null && pendingRetirement == null &&
                receipt.isLiveForUltraActivation() && policyCurrentAtPublication &&
                !policyEpoch.isRevoked()
            ) {
                active = ActiveAuthorization(
                    coordinator = coordinator,
                    token = token,
                    lease = lease,
                    runtimeLease = opened.handle,
                    receipt = receipt,
                    result = activated,
                )
                activationOwner = null
                activationOperation = null
                stateLock.notifyAll()
                true
            } else {
                false
            }
        }
        return if (published) activated else activationInterrupted(operation, coordinator)
    }

    override fun hasRenewableSession(predecessor: MfiAuthenticationReceipt): Boolean {
        if (!policyEpoch.isLive()) {
            requestPolicyEpochRetirement()
            return false
        }
        val current = synchronized(stateLock) {
            if (closed || closeRequested || pendingRetirement != null ||
                activationOperation != null || renewalOperation != null
            ) {
                return false
            }
            active?.takeIf { it.receipt === predecessor } ?: return false
        }
        val runtimeLive = try {
            current.coordinator.isRuntimeLive(current.runtimeLease)
        } catch (failure: Throwable) {
            failure.rethrowIfError()
            false
        }
        if (!predecessor.isLiveForUltraActivation() || !runtimeLive || !policyEpoch.isLive()) {
            if (!policyEpoch.isLive()) requestPolicyEpochRetirement()
            return false
        }
        return synchronized(stateLock) {
            !closed && !closeRequested && pendingRetirement == null &&
                activationOperation == null && renewalOperation == null && active === current &&
                current.receipt === predecessor && !policyEpoch.isRevoked() &&
                predecessor.isLiveForUltraActivation()
        }
    }

    override fun isCurrentActivation(
        activation: CarPlayUltraAuthorizationResult.Activated,
        receipt: MfiAuthenticationReceipt,
    ): Boolean {
        if (!policyEpoch.isLive()) {
            // Controller may call this while holding its admission gate. Deny synchronously and
            // retire the exact owner off-gate; never invoke coordinator.close() on that gate.
            requestPolicyEpochRetirement()
            return false
        }
        val current = synchronized(stateLock) {
            if (closed || closeRequested || pendingRetirement != null ||
                activationOperation != null || renewalOperation != null
            ) {
                return false
            }
            active?.takeIf { it.result === activation && it.receipt === receipt } ?: return false
        }
        val runtimeLive = try {
            current.coordinator.isRuntimeLive(current.runtimeLease)
        } catch (failure: Throwable) {
            failure.rethrowIfError()
            false
        }
        if (!receipt.isLiveForUltraActivation() || !runtimeLive || !policyEpoch.isLive()) {
            if (!policyEpoch.isLive()) requestPolicyEpochRetirement()
            return false
        }
        return synchronized(stateLock) {
            !closed && !closeRequested && pendingRetirement == null &&
                activationOperation == null && renewalOperation == null && active === current &&
                current.result === activation && current.receipt === receipt &&
                receipt.isLiveForUltraActivation() && !policyEpoch.isRevoked()
        }
    }

    override fun allowsClusterAssetAcquisition(
        assetId: String,
        authenticationIdentity: MfiAuthenticationIdentity,
    ): Boolean {
        if (assetId.isBlank() || !policyEpoch.ultraConsentSelected ||
            !policyEpoch.uiSyncConsentSelected || !policyEpoch.isLive()
        ) return false
        val current = synchronized(stateLock) {
            if (closed || closeRequested || pendingRetirement != null ||
                activationOperation != null || renewalOperation != null ||
                preparedRenewal != null
            ) return false
            active?.takeIf { selected ->
                selected.receipt.authenticationIdentity == authenticationIdentity &&
                    selected.result.configuration.isConfigured(AirPlayFeature.UI_SYNC)
            } ?: return false
        }
        val receipt = current.receipt
        if (!receipt.isLiveForUltraActivation()) return false
        val allowed = try {
            current.coordinator.allowsClusterAssetAcquisition(
                current.runtimeLease, assetId, authenticationIdentity,
            )
        } catch (_: Exception) {
            false
        }
        if (!allowed || !receipt.isLiveForUltraActivation() || !policyEpoch.isLive()) return false
        return synchronized(stateLock) {
            !closed && !closeRequested && pendingRetirement == null &&
                activationOperation == null && renewalOperation == null &&
                preparedRenewal == null && active === current &&
                current.receipt === receipt && receipt.isLiveForUltraActivation() &&
                !policyEpoch.isRevoked()
        }
    }

    override fun prepareRenewal(
        predecessor: MfiAuthenticationReceipt,
        successor: MfiAuthenticationReceipt,
    ): CarPlayUltraPreparedRenewal {
        if (!policyEpoch.isLive()) {
            requestPolicyEpochRetirement()
            renewalDenied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
        }
        if (predecessor === successor) {
            renewalDenied(CarPlayUltraAuthorizationDenialCode.INVALID_REQUEST)
        }
        val operation = Any()
        val current = synchronized(stateLock) {
            if (closed || closeRequested) {
                renewalDenied(CarPlayUltraAuthorizationDenialCode.CLOSED)
            }
            if (activationOperation != null || renewalOperation != null) {
                renewalDenied(CarPlayUltraAuthorizationDenialCode.BUSY)
            }
            val selected = active
                ?: renewalDenied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
            if (selected.receipt !== predecessor) {
                renewalDenied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
            }
            if (!predecessor.isLiveForUltraActivation()) {
                renewalDenied(CarPlayUltraAuthorizationDenialCode.STALE_RECEIPT)
            }
            renewalOperation = operation
            selected
        }

        val prepared = try {
            current.coordinator.prepareRenewal(
                current.token,
                current.runtimeLease,
                predecessor,
                successor,
            )
        } catch (failure: Throwable) {
            finishRenewal(operation)
            failure.rethrowIfError()
            throw CarPlayUltraAuthorizationRenewalException(
                CarPlayUltraAuthorizationDenialCode.PROVIDER_FAILED,
                failure,
            )
        }
        val handle = when (prepared) {
            is AuthorizedUltraCoordinatorResult.Granted -> prepared.value
            is AuthorizedUltraCoordinatorResult.Denied -> {
                finishRenewal(operation)
                renewalDenied(prepared.code)
            }
        }
        val token = PreparedRenewal(
            session = this,
            operation = operation,
            current = current,
            handle = handle,
            predecessor = predecessor,
            successor = successor,
        )
        val policyCurrentAtRenewalPublication = policyEpoch.isLive()
        val published = synchronized(stateLock) {
            if (renewalOperation === operation && active === current && preparedRenewal == null &&
                !closeRequested && policyCurrentAtRenewalPublication && !policyEpoch.isRevoked()
            ) {
                preparedRenewal = token
                true
            } else {
                false
            }
        }
        if (published) return token

        val rollback = try {
            current.coordinator.rollbackRenewal(handle)
        } finally {
            finishRenewal(operation)
        }
        renewalDenied(rollback ?: CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
    }

    @Deprecated("Use prepareRenewal and commit after the MFi handoff returns")
    override fun renewSession(
        predecessor: MfiAuthenticationReceipt,
        successor: MfiAuthenticationReceipt,
    ): CarPlayUltraAuthorizationResult.Activated = throw UnsupportedOperationException(
        "One-phase CarPlay Ultra renewal is unavailable",
    )

    private fun commitPreparedRenewal(
        token: PreparedRenewal,
    ): CarPlayUltraAuthorizationResult.Activated {
        if (!policyEpoch.isLive()) {
            requestPolicyEpochRetirement()
            renewalDenied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
        }
        val current = synchronized(stateLock) {
            if (preparedRenewal !== token || renewalOperation !== token.operation ||
                active !== token.current
            ) {
                renewalDenied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
            }
            token.current
        }
        val denial = current.coordinator.commitRenewal(token.handle)
        if (denial != null) renewalDenied(denial)
        return synchronized(stateLock) {
            if (preparedRenewal !== token || renewalOperation !== token.operation ||
                active !== current
            ) {
                renewalDenied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
            }
            current.receipt = token.successor
            preparedRenewal = null
            renewalOperation = null
            stateLock.notifyAll()
            current.result
        }
    }

    private fun rollbackPreparedRenewal(token: PreparedRenewal) {
        val current = synchronized(stateLock) {
            if (preparedRenewal !== token || renewalOperation !== token.operation ||
                active !== token.current
            ) {
                // The coordinator revocation callback may already have completed this exact
                // rollback; an already-cleared token close is therefore idempotent.
                return
            }
            token.current
        }
        val denial = current.coordinator.rollbackRenewal(token.handle)
        if (denial != null) renewalDenied(denial)
        synchronized(stateLock) {
            if (preparedRenewal === token) preparedRenewal = null
            if (renewalOperation === token.operation) renewalOperation = null
            stateLock.notifyAll()
        }
    }

    /**
     * The exact coordinator remains owned until its close succeeds. A failure never clears the
     * active generation or pending-retirement reference, so the next call retries that owner.
     */
    override fun close() {
        val owner = synchronized(stateLock) {
            if (closed && active == null && activationOwner == null &&
                pendingRetirement == null && activationOperation == null &&
                renewalOperation == null
            ) {
                return
            }
            closeRequested = true
            if (activationOperation != null || renewalOperation != null) {
                throw IllegalStateException(
                    "CarPlay Ultra authorization operation is still in flight; close remains retryable",
                )
            }
            check(!closeInProgress) { "CarPlay Ultra authorization close is already in progress" }
            val selected = active?.coordinator ?: pendingRetirement
            if (selected == null) {
                closed = true
                return
            }
            closeInProgress = true
            selected
        }
        try {
            owner.close()
        } catch (failure: Throwable) {
            synchronized(stateLock) {
                closeInProgress = false
                stateLock.notifyAll()
            }
            throw failure
        }
        synchronized(stateLock) {
            if (active?.coordinator === owner) active = null
            if (pendingRetirement === owner) pendingRetirement = null
            activationOwner = null
            closeInProgress = false
            closed = true
            stateLock.notifyAll()
        }
    }

    private fun denyAfterRetiring(
        operation: Any,
        coordinator: AuthorizedUltraProductCoordinatorPort,
        originalCode: CarPlayUltraAuthorizationDenialCode,
    ): CarPlayUltraAuthorizationResult.Denied {
        val ownsCoordinator = synchronized(stateLock) {
            activationOperation === operation && activationOwner === coordinator
        }
        if (!ownsCoordinator) {
            return denied(
                synchronized(stateLock) {
                    if (pendingRetirement === coordinator) {
                        CarPlayUltraAuthorizationDenialCode.REVOCATION_FAILED
                    } else {
                        CarPlayUltraAuthorizationDenialCode.CLOSED
                    }
                },
            )
        }
        return try {
            coordinator.close()
            val closeInterruptedActivation = synchronized(stateLock) {
                val interrupted = closeRequested || closed
                if (activationOperation === operation) activationOperation = null
                if (activationOwner === coordinator) activationOwner = null
                if (interrupted) closed = true
                stateLock.notifyAll()
                interrupted
            }
            denied(
                if (closeInterruptedActivation) {
                    CarPlayUltraAuthorizationDenialCode.CLOSED
                } else {
                    originalCode
                },
            )
        } catch (failure: Throwable) {
            synchronized(stateLock) {
                if (activationOperation === operation) activationOperation = null
                if (activationOwner === coordinator) activationOwner = null
                pendingRetirement = coordinator
                closed = false
                stateLock.notifyAll()
            }
            failure.rethrowIfError()
            denied(CarPlayUltraAuthorizationDenialCode.REVOCATION_FAILED)
        }
    }

    private fun denyAfterFailure(
        operation: Any,
        coordinator: AuthorizedUltraProductCoordinatorPort,
        code: CarPlayUltraAuthorizationDenialCode,
        failure: Throwable,
    ): CarPlayUltraAuthorizationResult.Denied {
        val denial = denyAfterRetiring(operation, coordinator, code)
        failure.rethrowIfError()
        return denial
    }

    private fun isActivationOperationCurrent(
        operation: Any,
        coordinator: AuthorizedUltraProductCoordinatorPort,
    ): Boolean = synchronized(stateLock) {
        activationOperation === operation && activationOwner === coordinator &&
            !closed && !closeRequested && active == null && pendingRetirement == null
    }

    private fun activationInterrupted(
        operation: Any,
        coordinator: AuthorizedUltraProductCoordinatorPort,
    ): CarPlayUltraAuthorizationResult.Denied = denyAfterRetiring(
        operation,
        coordinator,
        CarPlayUltraAuthorizationDenialCode.CLOSED,
    )

    private fun retireUnpublishedAfterCloseRequest(
        operation: Any,
        coordinator: AuthorizedUltraProductCoordinatorPort,
    ): CarPlayUltraAuthorizationResult.Denied = try {
        coordinator.close()
        synchronized(stateLock) {
            if (activationOperation === operation) activationOperation = null
            if (closeRequested) closed = true
            stateLock.notifyAll()
        }
        denied(CarPlayUltraAuthorizationDenialCode.CLOSED)
    } catch (failure: Throwable) {
        synchronized(stateLock) {
            if (activationOperation === operation) activationOperation = null
            closed = false
            closeRequested = true
            pendingRetirement = coordinator
            stateLock.notifyAll()
        }
        failure.rethrowIfError()
        denied(CarPlayUltraAuthorizationDenialCode.REVOCATION_FAILED)
    }

    private fun isUnownedActivationCurrent(operation: Any): Boolean =
        synchronized(stateLock) {
            activationOperation === operation && activationOwner == null &&
                !closed && !closeRequested
        }

    private fun attachActivationOwner(
        operation: Any,
        coordinator: AuthorizedUltraProductCoordinatorPort,
    ): Boolean = synchronized(stateLock) {
        if (activationOperation === operation && activationOwner == null &&
            !closed && !closeRequested
        ) {
            activationOwner = coordinator
            true
        } else {
            false
        }
    }

    private fun finishUnownedActivation(operation: Any) {
        synchronized(stateLock) {
            if (activationOperation === operation) activationOperation = null
            if (closeRequested && activationOwner == null && active == null &&
                pendingRetirement == null
            ) {
                closed = true
            }
            stateLock.notifyAll()
        }
    }

    private fun finishRenewal(operation: Any) {
        synchronized(stateLock) {
            if (renewalOperation === operation) renewalOperation = null
            stateLock.notifyAll()
        }
    }

    /** Caller holds [stateLock]; identity, never hash/equality, deduplicates retry owners. */
    private fun currentCoverOwnersLocked(): List<AuthorizedUltraProductCoordinatorPort> {
        val candidates = listOfNotNull(active?.coordinator, activationOwner, pendingRetirement)
        return candidates.filterIndexed { index, candidate ->
            candidates.take(index).none { it === candidate }
        }
    }

    private fun denied(
        code: CarPlayUltraAuthorizationDenialCode,
    ) = CarPlayUltraAuthorizationResult.Denied(code)

    /**
     * Revocation must deny new admission synchronously, but exact-owner close cannot run on the
     * Controller admission gate. Keep one queued task and retain the owner on any close failure.
     */
    private fun requestPolicyEpochRetirement() {
        val enqueue = synchronized(stateLock) {
            closeRequested = true
            if (closed) false else if (policyRetirementQueued) {
                policyRetirementWakeup = true
                false
            } else {
                policyRetirementQueued = true
                true
            }
        }
        if (!enqueue) return
        val accepted = try {
            retirementDispatcher.dispatch(Runnable {
                try {
                    retireRevokedEpoch()
                } catch (_: Exception) {
                    // close() retains the exact active or pending-retirement owner for retry.
                } finally {
                    val followUp = synchronized(stateLock) {
                        policyRetirementQueued = false
                        stateLock.notifyAll()
                        val retry = policyRetirementWakeup && !closed &&
                            policyEpoch.isRetirementReleased()
                        policyRetirementWakeup = false
                        retry
                    }
                    if (followUp) requestPolicyEpochRetirement()
                }
            })
        } catch (_: Exception) {
            false
        }
        if (!accepted) synchronized(stateLock) {
            policyRetirementQueued = false
            // Keep a raced release wakeup recorded. A later admission/teardown retries this
            // exact owner; do not recursively spin on a rejecting dispatcher.
            stateLock.notifyAll()
        }
    }

    private fun retireRevokedEpoch() {
        if (!policyEpoch.isRetirementReleased() && !policyEpoch.revoke()) return
        while (true) {
            val provisional = synchronized(stateLock) {
                while (activationOperation != null || closeInProgress ||
                    (renewalOperation != null && preparedRenewal == null)
                ) {
                    stateLock.wait()
                }
                preparedRenewal
            }
            if (provisional == null) break
            // An abandoned AA05 handoff must not keep a revoked physical owner alive forever.
            // This is the exact token's existing rollback path; failure retains it for retry.
            provisional.close()
        }
        close()
    }

    private fun renewalDenied(code: CarPlayUltraAuthorizationDenialCode): Nothing =
        throw CarPlayUltraAuthorizationRenewalException(code)

}

private class CoordinatorSessionHandle(
    val delegate: UltraProfileSessionToken,
) : AuthorizedUltraCoordinatorSessionHandle

private class CoordinatorPreparationHandle(
    val delegate: PreparedUltraProfileActivation,
) : AuthorizedUltraCoordinatorPreparationHandle

private class CoordinatorLeaseHandle(
    val delegate: AuthorizedUltraProfileLease,
) : AuthorizedUltraCoordinatorLeaseHandle

private class CoordinatorRuntimeHandle(
    val delegate: AuthorizedUltraRuntimeLease,
) : AuthorizedUltraCoordinatorRuntimeHandle

private class CoordinatorRenewalHandle(
    val delegate: PreparedUltraProfileSessionRenewal,
) : AuthorizedUltraCoordinatorRenewalHandle

/** Fixed adapter around the composition-root-bound activation coordinator. */
private class CoordinatorBackedAuthorizedUltraProductPort(
    private val coordinator: UltraProfileActivationCoordinator,
) : AuthorizedUltraProductCoordinatorPort {
    override fun beginPolicyRevocation() = coordinator.beginPolicyEpochRevocation()

    override fun policyCoverAck(): Boolean = coordinator.revokePhysicalOutputForPolicyEpoch()

    override fun beginSession(
        vehicleId: String,
        receipt: MfiAuthenticationReceipt,
    ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorSessionHandle> =
        when (val result = coordinator.beginSession(vehicleId, receipt)) {
            is UltraProfileSessionStartResult.Started ->
                AuthorizedUltraCoordinatorResult.Granted(CoordinatorSessionHandle(result.token))
            is UltraProfileSessionStartResult.Denied -> denied(result.code)
        }

    override fun prepare(
        token: AuthorizedUltraCoordinatorSessionHandle,
        receipt: MfiAuthenticationReceipt,
    ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorPreparationHandle> {
        val exact = token as? CoordinatorSessionHandle
            ?: return denied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
        return when (val result = coordinator.prepare(exact.delegate, receipt)) {
            is UltraProfilePrepareResult.Prepared ->
                AuthorizedUltraCoordinatorResult.Granted(
                    CoordinatorPreparationHandle(result.activation),
                )
            is UltraProfilePrepareResult.Denied -> denied(result.code)
        }
    }

    override fun commit(
        preparation: AuthorizedUltraCoordinatorPreparationHandle,
        receipt: MfiAuthenticationReceipt,
    ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorLeaseHandle> {
        val exact = preparation as? CoordinatorPreparationHandle
            ?: return denied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
        return when (val result = coordinator.commit(exact.delegate, receipt)) {
            is UltraProfileCommitResult.Activated ->
                AuthorizedUltraCoordinatorResult.Granted(CoordinatorLeaseHandle(result.lease))
            is UltraProfileCommitResult.Denied -> denied(result.code)
        }
    }

    override fun openRuntime(
        lease: AuthorizedUltraCoordinatorLeaseHandle,
        sessionManagementInfo: AirPlaySessionManagementInfo?,
    ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorRuntimeBinding> {
        val exact = lease as? CoordinatorLeaseHandle
            ?: return denied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
        return when (val result = exact.delegate.openRuntime()) {
            is UltraProfileProvisioningOpenResult.Opened -> {
                val runtimeLease = result.runtimeLease
                try {
                    // OPEN_PROVISIONING has already cleared its coordinator reservation. The
                    // exact candidate-owned physical authority must seal against this live AA05
                    // generation before product configuration can expose UI Sync readiness.
                    runtimeLease.sealPhysicalOutputAuthority()?.let { return denied(it) }
                    AuthorizedUltraCoordinatorResult.Granted(
                        AuthorizedUltraCoordinatorRuntimeBinding(
                            handle = CoordinatorRuntimeHandle(runtimeLease),
                            runtime = runtimeLease.runtimeForMediaEngine(),
                            configuration = runtimeLease.configuration(sessionManagementInfo),
                        ),
                    )
                } catch (failure: Throwable) {
                    failure.rethrowIfError()
                    denied(CarPlayUltraAuthorizationDenialCode.RUNTIME_CONSTRUCTION_FAILED)
                }
            }
            is UltraProfileProvisioningOpenResult.Denied -> denied(result.code)
        }
    }

    override fun prepareRenewal(
        token: AuthorizedUltraCoordinatorSessionHandle,
        runtime: AuthorizedUltraCoordinatorRuntimeHandle,
        predecessor: MfiAuthenticationReceipt,
        successor: MfiAuthenticationReceipt,
    ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorRenewalHandle> {
        val exact = token as? CoordinatorSessionHandle
            ?: return denied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
        val exactRuntime = runtime as? CoordinatorRuntimeHandle
            ?: return denied(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION)
        if (!exactRuntime.delegate.isLive) {
            return denied(CarPlayUltraAuthorizationDenialCode.LEASE_NOT_LIVE)
        }
        return try {
            val prepared = coordinator.prepareSessionRenewal(
                exact.delegate,
                predecessor,
                successor,
            )
            if (exactRuntime.delegate.isLive) {
                AuthorizedUltraCoordinatorResult.Granted(CoordinatorRenewalHandle(prepared))
            } else {
                throw UltraProfileSessionRenewalException(
                    UltraProfileActivationDenialCode.LEASE_NOT_LIVE,
                )
            }
        } catch (denied: UltraProfileSessionRenewalException) {
            denied(denied.code)
        }
    }

    override fun commitRenewal(
        renewal: AuthorizedUltraCoordinatorRenewalHandle,
    ): CarPlayUltraAuthorizationDenialCode? {
        val exact = renewal as? CoordinatorRenewalHandle
            ?: return CarPlayUltraAuthorizationDenialCode.STALE_GENERATION
        return try {
            exact.delegate.commit()
            null
        } catch (denied: UltraProfileSessionRenewalException) {
            denied.code.toProductCode()
        }
    }

    override fun rollbackRenewal(
        renewal: AuthorizedUltraCoordinatorRenewalHandle,
    ): CarPlayUltraAuthorizationDenialCode? {
        val exact = renewal as? CoordinatorRenewalHandle
            ?: return CarPlayUltraAuthorizationDenialCode.STALE_GENERATION
        return try {
            exact.delegate.close()
            null
        } catch (denied: UltraProfileSessionRenewalException) {
            denied.code.toProductCode()
        }
    }

    override fun isRuntimeLive(handle: AuthorizedUltraCoordinatorRuntimeHandle): Boolean =
        (handle as? CoordinatorRuntimeHandle)?.delegate?.isLive == true

    override fun allowsClusterAssetAcquisition(
        handle: AuthorizedUltraCoordinatorRuntimeHandle,
        assetId: String,
        authenticationIdentity: MfiAuthenticationIdentity,
    ): Boolean = (handle as? CoordinatorRuntimeHandle)?.delegate
        ?.allowsClusterAssetAcquisition(assetId, authenticationIdentity) == true

    override fun close() = coordinator.close()

    private fun denied(
        code: UltraProfileActivationDenialCode,
    ): AuthorizedUltraCoordinatorResult.Denied = denied(code.toProductCode())

    private fun denied(
        code: CarPlayUltraAuthorizationDenialCode,
    ): AuthorizedUltraCoordinatorResult.Denied = AuthorizedUltraCoordinatorResult.Denied(code)
}

private fun UltraProfileActivationDenialCode.toProductCode():
    CarPlayUltraAuthorizationDenialCode = when (this) {
    UltraProfileActivationDenialCode.INVALID_REQUEST ->
        CarPlayUltraAuthorizationDenialCode.INVALID_REQUEST
    UltraProfileActivationDenialCode.STALE_RECEIPT ->
        CarPlayUltraAuthorizationDenialCode.STALE_RECEIPT
    UltraProfileActivationDenialCode.STALE_GENERATION ->
        CarPlayUltraAuthorizationDenialCode.STALE_GENERATION
    UltraProfileActivationDenialCode.BUSY -> CarPlayUltraAuthorizationDenialCode.BUSY
    UltraProfileActivationDenialCode.ALREADY_PREPARED ->
        CarPlayUltraAuthorizationDenialCode.ALREADY_PREPARED
    UltraProfileActivationDenialCode.ALREADY_ACTIVE ->
        CarPlayUltraAuthorizationDenialCode.ALREADY_ACTIVE
    UltraProfileActivationDenialCode.PROVIDER_UNAVAILABLE ->
        CarPlayUltraAuthorizationDenialCode.PROVIDER_UNAVAILABLE
    UltraProfileActivationDenialCode.PROVIDER_FAILED ->
        CarPlayUltraAuthorizationDenialCode.PROVIDER_FAILED
    UltraProfileActivationDenialCode.PROFILE_BINDING_MISMATCH ->
        CarPlayUltraAuthorizationDenialCode.PROFILE_BINDING_MISMATCH
    UltraProfileActivationDenialCode.RUNTIME_MANIFEST_MISMATCH ->
        CarPlayUltraAuthorizationDenialCode.RUNTIME_MANIFEST_MISMATCH
    UltraProfileActivationDenialCode.RUNTIME_CONSTRUCTION_FAILED ->
        CarPlayUltraAuthorizationDenialCode.RUNTIME_CONSTRUCTION_FAILED
    UltraProfileActivationDenialCode.COMMIT_FAILED ->
        CarPlayUltraAuthorizationDenialCode.COMMIT_FAILED
    UltraProfileActivationDenialCode.LEASE_NOT_LIVE ->
        CarPlayUltraAuthorizationDenialCode.LEASE_NOT_LIVE
    UltraProfileActivationDenialCode.NO_PROVISIONING ->
        CarPlayUltraAuthorizationDenialCode.NO_PROVISIONING
    UltraProfileActivationDenialCode.REVOCATION_FAILED ->
        CarPlayUltraAuthorizationDenialCode.REVOCATION_FAILED
    UltraProfileActivationDenialCode.CLOSED -> CarPlayUltraAuthorizationDenialCode.CLOSED
}

private fun Throwable.rethrowIfError() {
    if (this is Error) throw this
}
