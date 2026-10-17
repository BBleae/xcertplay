package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBatch
import com.shilapi.xcertplay.ultra.AuthorizedAndroidUiSyncPhysicalOutputAuthority
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile

/** Fixed product adapter for the exact authority installed into UI Sync preflight. */
internal class AuthorizedAndroidPhysicalOutputSealer(
    override val authority: AuthorizedAndroidUiSyncPhysicalOutputAuthority,
) : AuthorizedUltraPhysicalOutputSealer {
    override fun authorizesInstrumentSource(
        batch: UiSyncPhysicalOutputBatch,
        provisioning: AuthorizedUltraProfileProvisioning,
        profile: VerifiedUltraProfile,
    ): Boolean = authority.authorizesInstrumentSource(batch, provisioning, profile)

    override fun seal(
        manifest: AuthorizedUltraCompiledRuntimeManifest,
        issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
        token: UltraProfileSessionToken,
        proof: UltraProfilePhysicalGenerationProof,
        provisioning: AuthorizedUltraProfileProvisioning,
    ) {
        require(proof.matchesToken(token)) { "Physical seal token differs from AA05 lineage" }
        require(provisioning.parent.sessionToken === token &&
            provisioning.manifestDigest == manifest.manifestDigest
        ) { "Physical seal provisioning differs from the published AA05 child" }
        // The authority obtains its own fresh coordinator proof and checks this exact manifest,
        // token and physical requirement. A caller-provided snapshot cannot make it live.
        authority.seal(manifest, issuer, token, provisioning)
    }
}
