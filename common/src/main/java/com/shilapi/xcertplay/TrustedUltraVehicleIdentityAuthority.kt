package com.shilapi.xcertplay

import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity

/** Result from the fixed, product-owned vehicle identity authority. */
internal sealed interface TrustedUltraVehicleIdentityResult {
    class Resolved internal constructor(
        val vehicleId: String,
    ) : TrustedUltraVehicleIdentityResult {
        override fun toString(): String = "TrustedUltraVehicleIdentityResult.Resolved(<redacted>)"
    }

    data object Unavailable : TrustedUltraVehicleIdentityResult
}

/**
 * Resolves the deployment vehicle identity without consulting user-editable CarPlay metadata.
 *
 * A positive authority must be statically paired with the fixed deployment provider and return
 * [TrustedUltraVehicleIdentityResult.Unavailable] unless that provider can begin acquisition.
 * This pre-admission rule keeps a missing deployment from consuming the AA05 receipt.
 */
internal fun interface TrustedUltraVehicleIdentityAuthority {
    fun resolve(
        authenticationIdentity: MfiAuthenticationIdentity,
    ): TrustedUltraVehicleIdentityResult
}

/** Open-source composition has neither an OEM vehicle identity nor an authorized deployment. */
internal object UnavailableTrustedUltraVehicleIdentityAuthority :
    TrustedUltraVehicleIdentityAuthority {
    override fun resolve(
        authenticationIdentity: MfiAuthenticationIdentity,
    ): TrustedUltraVehicleIdentityResult = TrustedUltraVehicleIdentityResult.Unavailable
}
