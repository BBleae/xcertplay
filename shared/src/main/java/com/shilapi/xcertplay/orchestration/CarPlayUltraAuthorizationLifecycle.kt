package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlaySessionManagementInfo
import com.shilapi.xcertplay.airplay.AirPlayUltraConfiguration
import com.shilapi.xcertplay.airplay.AirPlayUltraRuntime
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import java.io.Closeable

/** Stable, non-sensitive product authorization failure categories. */
enum class CarPlayUltraAuthorizationDenialCode {
    INVALID_REQUEST,
    STALE_RECEIPT,
    STALE_GENERATION,
    BUSY,
    ALREADY_PREPARED,
    ALREADY_ACTIVE,
    PROVIDER_UNAVAILABLE,
    PROVIDER_FAILED,
    PROFILE_BINDING_MISMATCH,
    RUNTIME_MANIFEST_MISMATCH,
    RUNTIME_CONSTRUCTION_FAILED,
    COMMIT_FAILED,
    LEASE_NOT_LIVE,
    NO_PROVISIONING,
    REVOCATION_FAILED,
    VEHICLE_IDENTITY_FAILED,
    CLOSED,
}

/** Result of attempting to activate Ultra for one exact AA05 receipt. */
sealed interface CarPlayUltraAuthorizationResult {
    /**
     * The fixed product deployment is not installed or is not ready.
     *
     * Implementations must return this before consuming [MfiAuthenticationReceipt]. Ordinary
     * CarPlay can therefore continue using the exact receipt and MFi session.
     */
    data object Unavailable : CarPlayUltraAuthorizationResult

    /** Authorization was attempted but could not be completed. */
    data class Denied(
        val code: CarPlayUltraAuthorizationDenialCode,
    ) : CarPlayUltraAuthorizationResult

    /**
     * Exact runtime and configuration produced by the same live authorized runtime lease.
     * Neither value is caller-assembled sidecar metadata.
     */
    class Activated(
        val runtime: AirPlayUltraRuntime,
        val configuration: AirPlayUltraConfiguration,
    ) : CarPlayUltraAuthorizationResult
}

/**
 * Product-owned Ultra authorization lifecycle used by the controller/runtime binding boundary.
 *
 * The interface deliberately accepts no vehicle ID, profile, trust verifier, Store path, or
 * deployment provider. Those authorities belong to the fixed product composition. A renewal must
 * run synchronously inside the exact [com.shilapi.xcertplay.mfi.MfiAuthenticationHandoff.commit]
 * promotion callback so a denied renewal rolls the successor receipt back.
 */
interface CarPlayUltraAuthorizationLifecycle : Closeable {
    fun activate(
        receipt: MfiAuthenticationReceipt,
        sessionManagementInfo: AirPlaySessionManagementInfo? = null,
    ): CarPlayUltraAuthorizationResult

    /** True only for the exact receipt currently owned by the live authorization generation. */
    fun hasRenewableSession(predecessor: MfiAuthenticationReceipt): Boolean

    /**
     * Full generation-bound publication probe for an exact result and receipt.
     *
     * Callers must revalidate this after acquiring a runtime/configuration binding. Constructed or
     * stale [CarPlayUltraAuthorizationResult.Activated] values never pass this identity check.
     */
    fun isCurrentActivation(
        activation: CarPlayUltraAuthorizationResult.Activated,
        receipt: MfiAuthenticationReceipt,
    ): Boolean

    /**
     * Narrow, read-only Debug catalog-probe admission for the exact active signed Profile.
     * This does not attest that the phone has the asset or authorize a nonzero asset version.
     * This must be read-only on the iAP2 control callback: never close or retire a runtime.
     * Product implementations without an explicit current Profile must deny by default.
     */
    fun allowsClusterAssetAcquisition(
        assetId: String,
        authenticationIdentity: MfiAuthenticationIdentity,
    ): Boolean = false

    /** Prepares a lineage-bound renewal inside the exact MFi handoff promotion callback. */
    fun prepareRenewal(
        predecessor: MfiAuthenticationReceipt,
        successor: MfiAuthenticationReceipt,
    ): CarPlayUltraPreparedRenewal

    /** Legacy one-phase renewal is deliberately unavailable; callers must use [prepareRenewal]. */
    @Deprecated("Use prepareRenewal inside MFi handoff and commit only after handoff success")
    fun renewSession(
        predecessor: MfiAuthenticationReceipt,
        successor: MfiAuthenticationReceipt,
    ): CarPlayUltraAuthorizationResult.Activated = throw UnsupportedOperationException(
        "One-phase CarPlay Ultra renewal is unavailable",
    )
}

/**
 * One exact provisional AA05 successor.
 *
 * Construct this only inside [com.shilapi.xcertplay.mfi.MfiAuthenticationHandoff.commit]'s
 * promotion callback. [commit] is called only after that outer handoff returns successfully;
 * [close] rolls the provisional lineage back after any handoff failure. Both operations are
 * identity-bound and idempotent. Neither path rebuilds the authorized runtime.
 */
interface CarPlayUltraPreparedRenewal : Closeable {
    val activation: CarPlayUltraAuthorizationResult.Activated
    val predecessor: MfiAuthenticationReceipt
    val successor: MfiAuthenticationReceipt

    fun commit(): CarPlayUltraAuthorizationResult.Activated
}

class CarPlayUltraAuthorizationRenewalException(
    val code: CarPlayUltraAuthorizationDenialCode,
    cause: Throwable? = null,
) : IllegalStateException("CarPlay Ultra authorization renewal denied: ${code.name}", cause)
