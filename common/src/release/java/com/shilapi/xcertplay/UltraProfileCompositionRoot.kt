package com.shilapi.xcertplay

import android.content.Context
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/** Release policy is fixed Disabled/Unavailable and has no reference to the debug bench path. */
internal object UltraProfileCompositionRoot {
    private val compiledManifestIssuer = AuthorizedUltraCompiledRuntimeManifestIssuer(
        MessageDigest.getInstance("SHA-256").digest(
            "xcertplay-disabled-ultra-trust-policy-v1\u0000".toByteArray(StandardCharsets.US_ASCII),
        ),
    )

    fun configureHost(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") ultraEnabled: Boolean,
        @Suppress("UNUSED_PARAMETER") inputs: HostUltraCompositionInputs,
    ): Boolean = false

    /**
     * Release has no production authorization provider, so a session can never become Ultra and
     * identification must never claim theme assets (that could record an iPhone Ultra opt-out).
     * A future production root must return true only when its provider reports availability.
     */
    fun themeAssetsAdvertisable(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") ultraEnabled: Boolean,
    ): Boolean = false

    fun deploymentProvider(): UltraProfileDeploymentProvider = DisabledUltraProfileDeploymentProvider

    fun vehicleIdentityAuthority(): TrustedUltraVehicleIdentityAuthority =
        UnavailableTrustedUltraVehicleIdentityAuthority

    /** Release has no active Ultra physical owner; the policy remains Disabled. */
    fun beginConsentMutation(): Closeable = Closeable { }

    /** Release has no enrolled OEM authority; its fixed epoch cannot authorize Ultra. */
    fun capturePolicyEpoch(): UltraProfilePolicyEpoch = UltraProfilePolicyEpoch(
        vehicleIdentityAuthority = UnavailableTrustedUltraVehicleIdentityAuthority,
        deploymentProvider = DisabledUltraProfileDeploymentProvider,
        manifestIssuer = compiledManifestIssuer,
        ultraConsentSelected = false,
        uiSyncConsentSelected = false,
        currentSelection = { true },
    )

    /** Production external trust needs a fixed, independently verified hardware provider. */
    fun externalPhysicalDisplayTrustProvider(): ExternalPhysicalDisplayTrustProvider =
        ExternalPhysicalDisplayTrustProvider.DENY_ALL

    /** A persisted DDP probe preference cannot bypass production MAIN UI Sync readiness. */
    fun allowDiagnosticPhysicalPanelsWithoutUiSync(): Boolean = false

    internal fun manifestIssuer(): AuthorizedUltraCompiledRuntimeManifestIssuer =
        compiledManifestIssuer

    fun storeRoot(noBackupFilesDirectory: Path): Path =
        ultraProfileStoreRoot(noBackupFilesDirectory)
}
