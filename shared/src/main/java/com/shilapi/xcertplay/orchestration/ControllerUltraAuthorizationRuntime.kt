package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayFeatureNegotiation
import com.shilapi.xcertplay.airplay.AirPlaySessionRuntimeBinding
import com.shilapi.xcertplay.airplay.AirPlayUltraRuntime
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import java.io.Closeable

/**
 * Exact controller-side owner for one optional authorized AirPlay runtime generation.
 *
 * The authorization lifecycle exclusively owns an authorized root runtime. [fallbackRuntime]
 * remains owned by the legacy media engine and is never closed here. New AirPlay admission is
 * sealed independently from root retirement so existing sessions can retire before [close].
 *
 * This public shared-layer seam is not authorization evidence and must not make a caller-supplied
 * lifecycle trusted. Product code may inject only the fixed common-layer product lifecycle whose
 * construction does not accept a provider, profile, verifier, vehicle ID, or Store path.
 * The owning controller must also be the exclusive authority for the MFi session and receipt: it
 * must revoke this binding and retire admitted AirPlay sessions before invalidating an attempt,
 * closing the MFi session, closing the lifecycle, or closing either root runtime.
 */
class ControllerUltraAuthorizationRuntime(
    private val lifecycle: CarPlayUltraAuthorizationLifecycle,
    val binding: AirPlaySessionRuntimeBinding,
    val generation: AirPlaySessionRuntimeBinding.Generation,
    val baselineConfig: AirPlayConfig,
    val fallbackRuntime: AirPlayUltraRuntime?,
    private val allowDiagnosticPhysicalPanelsWithoutUiSync: Boolean = false,
) : Closeable {
    enum class ReceiptPublication {
        PUBLISHED,
        RETIRED_AFTER_DENIAL,
        REJECTED,
    }

    sealed interface Resolution {
        val snapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot

        class Authorized internal constructor(
            val activation: CarPlayUltraAuthorizationResult.Activated,
            override val snapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot,
        ) : Resolution

        class Unavailable internal constructor(
            override val snapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot,
        ) : Resolution

        class Denied internal constructor(
            val denial: CarPlayUltraAuthorizationResult.Denied,
            override val snapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot,
        ) : Resolution
    }

    sealed interface TunnelAuthentication {
        data object Ordinary : TunnelAuthentication

        class AuthorizedHandoff internal constructor(
            val predecessor: MfiAuthenticationReceipt,
        ) : TunnelAuthentication
    }

    private sealed interface Publication {
        val config: AirPlayConfig
        val runtime: AirPlayUltraRuntime?
        val receipt: MfiAuthenticationReceipt?
    }

    private class AuthorizedPublication(
        override val config: AirPlayConfig,
        override val runtime: AirPlayUltraRuntime,
        override var receipt: MfiAuthenticationReceipt,
        val activation: CarPlayUltraAuthorizationResult.Activated,
    ) : Publication

    private class FallbackPublication(
        override val config: AirPlayConfig,
        override val runtime: AirPlayUltraRuntime?,
        override var receipt: MfiAuthenticationReceipt?,
        val result: CarPlayUltraAuthorizationResult,
    ) : Publication

    private val gate = Any()
    @Volatile private var sealRequested = false
    private var publication: Publication? = null
    private var activationOperation: Any? = null
    private var preparedRenewal: CarPlayUltraPreparedRenewal? = null
    private var admissionRevoked = false
    private var closeInProgress = false
    private var closeComplete = false

    init {
        require((baselineConfig.ultra == null) == (fallbackRuntime == null)) {
            "Fallback AirPlay Ultra configuration and runtime must be supplied together"
        }
        require(binding.acquire(generation) == null) {
            "Controller authorization requires an unresolved AirPlay session generation"
        }
    }

    /**
     * Resolves this exact pending generation after the first AA05 exchange.
     *
     * [publishReceipt] must durably install the exact receipt in the controller before the binding
     * becomes visible to an accept worker. Only [ReceiptPublication.RETIRED_AFTER_DENIAL] may
     * proceed without a controller receipt, and only for a denied fallback generation.
     */
    fun activateAndResolve(
        receipt: MfiAuthenticationReceipt,
        resolvedBaselineConfig: AirPlayConfig = baselineConfig,
        publishReceipt: (MfiAuthenticationReceipt) -> ReceiptPublication,
    ): Resolution {
        require(resolvedBaselineConfig.ultra === baselineConfig.ultra) {
            "Resolved AirPlay baseline must retain the configured fallback Ultra identity"
        }
        require((resolvedBaselineConfig.ultra == null) == (fallbackRuntime == null)) {
            "Resolved fallback AirPlay configuration and runtime must be supplied together"
        }
        val operation = Any()
        synchronized(gate) {
            check(!sealRequested && !closeComplete && !closeInProgress) {
                "Controller Ultra authorization is closing"
            }
            check(activationOperation == null && publication == null) {
                "Controller Ultra authorization was already resolved"
            }
            activationOperation = operation
        }

        var exactPublication: Publication? = null
        try {
            val result = lifecycle.activate(
                receipt,
                resolvedBaselineConfig.ultra?.sessionManagementInfo,
            )
            val next = when (result) {
                is CarPlayUltraAuthorizationResult.Activated -> {
                    check(lifecycle.isCurrentActivation(result, receipt)) {
                        "CarPlay Ultra activation is not the exact live authorization"
                    }
                    check(result.runtime !== fallbackRuntime) {
                        "Authorized and fallback runtimes must have different owners"
                    }
                    AuthorizedPublication(
                        config = guardedPhysicalPanelsConfig(
                            resolvedBaselineConfig.copy(ultra = result.configuration),
                        ),
                        runtime = result.runtime,
                        receipt = receipt,
                        activation = result,
                    )
                }
                CarPlayUltraAuthorizationResult.Unavailable,
                is CarPlayUltraAuthorizationResult.Denied,
                -> FallbackPublication(
                    config = guardedPhysicalPanelsConfig(resolvedBaselineConfig),
                    runtime = fallbackRuntime,
                    receipt = receipt,
                    result = result,
                )
            }
            exactPublication = next
            synchronized(gate) {
                check(activationOperation === operation && !sealRequested && !closeComplete) {
                    "Controller Ultra authorization activation became stale"
                }
                publication = next
            }
            val receiptPublication = publishReceipt(receipt)
            val acceptedWithoutReceipt =
                result is CarPlayUltraAuthorizationResult.Denied &&
                    receiptPublication == ReceiptPublication.RETIRED_AFTER_DENIAL &&
                    !receipt.isCurrentAuthenticationAttempt() &&
                    !receipt.isLiveForUltraActivation()
            check(
                receiptPublication == ReceiptPublication.PUBLISHED || acceptedWithoutReceipt,
            ) {
                "Controller rejected the exact AA05 receipt publication"
            }
            if (next is FallbackPublication && acceptedWithoutReceipt) {
                next.receipt = null
            }
            check(!sealRequested) { "Controller Ultra authorization admission was revoked" }

            val snapshot = binding.resolve(
                generation = generation,
                config = next.config,
                ultraRuntime = next.runtime,
                admissionValidator = { candidate -> validateAdmission(next, candidate) },
            ) ?: error("AirPlay session runtime generation could not be resolved")

            return synchronized(gate) {
                check(activationOperation === operation && publication === next && !sealRequested) {
                    "Controller Ultra authorization publication became stale"
                }
                if (next is AuthorizedPublication) {
                    check(lifecycle.isCurrentActivation(next.activation, receipt)) {
                        "CarPlay Ultra authorization lost liveness during publication"
                    }
                }
                activationOperation = null
                when (result) {
                    is CarPlayUltraAuthorizationResult.Activated ->
                        Resolution.Authorized(result, snapshot)
                    CarPlayUltraAuthorizationResult.Unavailable ->
                        Resolution.Unavailable(snapshot)
                    is CarPlayUltraAuthorizationResult.Denied ->
                        Resolution.Denied(result, snapshot)
                }
            }
        } catch (failure: Throwable) {
            synchronized(gate) {
                if (activationOperation === operation) activationOperation = null
                // Keep an exact publication record when the lifecycle may already own the receipt.
                if (publication == null && exactPublication != null) publication = exactPublication
            }
            sealAfterFailure(failure)
            throw failure
        }
    }

    /**
     * Chooses ordinary authentication only for an explicitly resolved fallback generation.
     * An authorized generation that is no longer renewable fails closed instead of superseding its
     * consumed receipt with an ordinary authentication attempt.
     */
    fun tunnelAuthentication(
        controllerReceipt: MfiAuthenticationReceipt?,
    ): TunnelAuthentication = synchronized(gate) {
        check(!sealRequested && !closeComplete && !closeInProgress) {
            "Controller Ultra authorization is closing"
        }
        when (val current = publication) {
            is FallbackPublication -> {
                check(current.receipt === controllerReceipt) {
                    "Controller fallback receipt is not the exact published receipt"
                }
                TunnelAuthentication.Ordinary
            }
            is AuthorizedPublication -> {
                val predecessor = checkNotNull(controllerReceipt) {
                    "Controller authorization receipt is unavailable"
                }
                check(current.receipt === predecessor) {
                    "Controller authorization receipt is stale"
                }
                check(lifecycle.hasRenewableSession(predecessor)) {
                    "Current CarPlay Ultra authorization is not renewable"
                }
                TunnelAuthentication.AuthorizedHandoff(predecessor)
            }
            null -> error("Controller Ultra authorization has not resolved AA05")
        }
    }

    /** Atomically advances an ordinary fallback receipt without changing its runtime snapshot. */
    fun promoteFallbackReceipt(
        predecessor: MfiAuthenticationReceipt?,
        successor: MfiAuthenticationReceipt,
        publishControllerReceipt: () -> Boolean,
    ) = synchronized(gate) {
        check(!sealRequested && !closeComplete && !closeInProgress) {
            "Controller Ultra authorization is closing"
        }
        val current = publication as? FallbackPublication
            ?: error("Controller has no fallback receipt to promote")
        check(current.receipt === predecessor) {
            "Controller fallback receipt predecessor is stale"
        }
        check(publishControllerReceipt()) {
            "Controller rejected the fallback successor receipt"
        }
        current.receipt = successor
    }

    /** Holds the exact binding admission gate across MFi commit and two-phase lifecycle renewal. */
    fun <T : Any> withExclusiveHandoff(action: () -> T): T {
        return binding.withResolvedAdmission(generation) { snapshot ->
            synchronized(gate) {
                val current = publication as? AuthorizedPublication
                    ?: error("Controller has no authorized runtime for handoff")
                check(!sealRequested && !closeComplete && !closeInProgress &&
                    preparedRenewal == null && snapshot.config === current.config &&
                    snapshot.ultraRuntime === current.runtime
                ) {
                    "Controller Ultra authorization cannot admit a handoff"
                }
            }
            action()
        } ?: error("Controller Ultra authorization binding rejected the handoff")
    }

    /** Consumes and reserves a provisional successor only inside the MFi promotion callback. */
    fun prepareRenewal(
        predecessor: MfiAuthenticationReceipt,
        successor: MfiAuthenticationReceipt,
    ): CarPlayUltraPreparedRenewal {
        var candidate: CarPlayUltraPreparedRenewal? = null
        try {
            return synchronized(gate) {
                check(!sealRequested && !closeComplete && !closeInProgress) {
                    "Controller Ultra authorization is closing"
                }
                val current = publication as? AuthorizedPublication
                    ?: error("Controller has no authorized runtime to renew")
                check(preparedRenewal == null) {
                    "Controller Ultra renewal is already prepared"
                }
                check(current.receipt === predecessor) {
                    "CarPlay Ultra renewal predecessor is stale"
                }
                lifecycle.prepareRenewal(predecessor, successor).also { prepared ->
                    candidate = prepared
                    check(prepared.activation === current.activation &&
                        prepared.predecessor === predecessor &&
                        prepared.successor === successor
                    ) {
                        "CarPlay Ultra renewal preparation changed exact ownership"
                    }
                    preparedRenewal = prepared
                }
            }
        } catch (failure: Throwable) {
            val cleanup = candidate
            if (cleanup != null) {
                try {
                    cleanup.close()
                } catch (cleanupFailure: Throwable) {
                    synchronized(gate) {
                        if (preparedRenewal == null) preparedRenewal = cleanup
                        sealRequested = true
                    }
                    if (cleanupFailure is Error) {
                        if (cleanupFailure !== failure) cleanupFailure.addSuppressed(failure)
                        throw cleanupFailure
                    }
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    /** Rolls a provisional successor back while the binding gate is still held. */
    fun rollbackRenewal(prepared: CarPlayUltraPreparedRenewal) = synchronized(gate) {
        if (preparedRenewal !== prepared) return@synchronized
        prepared.close()
        preparedRenewal = null
    }

    /**
     * Called only after MFi commit returned successfully. Token commit is followed solely by
     * infallible in-memory publication of the exact successor.
     */
    fun commitRenewal(
        prepared: CarPlayUltraPreparedRenewal,
    ): CarPlayUltraAuthorizationResult.Activated = synchronized(gate) {
        check(!sealRequested && !closeComplete && !closeInProgress) {
            "Controller Ultra authorization is closing"
        }
        val current = publication as? AuthorizedPublication
            ?: error("Controller has no authorized runtime to renew")
        check(preparedRenewal === prepared && prepared.activation === current.activation &&
            prepared.predecessor === current.receipt
        ) {
            "Controller Ultra renewal preparation is stale"
        }
        prepared.commit()
        current.receipt = prepared.successor
        preparedRenewal = null
        current.activation
    }

    /** Seals new AirPlay admissions without closing either root runtime. */
    fun revokeAdmission() {
        binding.revoke(generation)
        sealRequested = true
        synchronized(gate) { admissionRevoked = true }
    }

    /** Read-only send-time predicate; the lifecycle is never called while holding [gate]. */
    fun allowsClusterAssetAcquisition(
        assetId: String,
        authenticationIdentity: MfiAuthenticationIdentity,
    ): Boolean {
        if (assetId.isBlank()) return false
        val selected = synchronized(gate) {
            if (sealRequested || admissionRevoked || closeInProgress || closeComplete ||
                activationOperation != null || preparedRenewal != null
            ) return false
            val current = publication as? AuthorizedPublication ?: return false
            val receipt = current.receipt
            if (receipt.authenticationIdentity != authenticationIdentity) return false
            current to receipt
        }
        val (current, receipt) = selected
        val allowed = try {
            lifecycle.allowsClusterAssetAcquisition(assetId, authenticationIdentity)
        } catch (_: Exception) {
            false
        }
        if (!allowed) return false
        val unchanged = synchronized(gate) {
            !sealRequested && !admissionRevoked && !closeInProgress && !closeComplete &&
                activationOperation == null && preparedRenewal == null &&
                publication === current && current.receipt === receipt &&
                receipt.authenticationIdentity == authenticationIdentity
        }
        return unchanged
    }

    /**
     * Closes only the authorization lifecycle/root. The binding is sealed first and a throwing
     * lifecycle remains the exact retry owner for the next call.
     */
    override fun close() {
        revokeAdmission()
        synchronized(gate) {
            if (closeComplete) return
            check(activationOperation == null) {
                "Controller Ultra authorization activation is still in flight"
            }
            preparedRenewal?.let { pending ->
                pending.close()
                preparedRenewal = null
            }
            check(!closeInProgress) { "Controller Ultra authorization close is in progress" }
            closeInProgress = true
        }
        try {
            lifecycle.close()
        } catch (failure: Throwable) {
            synchronized(gate) { closeInProgress = false }
            throw failure
        }
        synchronized(gate) {
            closeInProgress = false
            closeComplete = true
        }
    }

    private fun validateAdmission(
        expected: Publication,
        snapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot,
    ): Boolean = synchronized(gate) {
        if (sealRequested || admissionRevoked || closeInProgress || closeComplete ||
            publication !== expected || snapshot.generation !== generation ||
            snapshot.config !== expected.config || snapshot.ultraRuntime !== expected.runtime
        ) {
            return@synchronized false
        }
        when (expected) {
            is AuthorizedPublication ->
                lifecycle.isCurrentActivation(expected.activation, expected.receipt)
            is FallbackPublication -> true
        }
    }

    /**
     * A physical DDP MAIN without a ready MAIN UI Sync path can lock the iPhone out of its
     * ordinary launch UI. Keep that metadata only for a complete local MAIN UI Sync preflight,
     * or for the explicitly enabled Debug diagnostic probe. Retained non-diagnostic panels carry
     * a second wire-time check because readiness can change before /info is serialized.
     */
    private fun guardedPhysicalPanelsConfig(candidate: AirPlayConfig): AirPlayConfig {
        if (candidate.displayPanels == null) return candidate
        if (allowDiagnosticPhysicalPanelsWithoutUiSync) {
            return if (candidate.requireMainUiSyncForDisplayPanels) {
                candidate.copy(requireMainUiSyncForDisplayPanels = false)
            } else {
                candidate
            }
        }
        val local = AirPlayFeatureNegotiation.snapshot(candidate)
        return if (local.mainDdpUiSyncReady && local.isReady(AirPlayFeature.UI_SYNC)) {
            candidate.copy(requireMainUiSyncForDisplayPanels = true)
        } else {
            candidate.copy(
                displayPanels = null,
                displayPanelsLiveness = null,
                requireMainUiSyncForDisplayPanels = true,
            )
        }
    }

    private fun sealAfterFailure(primary: Throwable) {
        try {
            revokeAdmission()
        } catch (sealingFailure: Throwable) {
            if (sealingFailure is Error) {
                if (sealingFailure !== primary) sealingFailure.addSuppressed(primary)
                throw sealingFailure
            }
            if (sealingFailure !== primary) primary.addSuppressed(sealingFailure)
        }
    }
}
