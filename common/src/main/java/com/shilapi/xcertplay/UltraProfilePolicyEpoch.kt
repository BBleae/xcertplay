package com.shilapi.xcertplay

/**
 * One immutable product composition selection for an Ultra controller generation.
 *
 * The root captures the identity authority, deployment provider, compiled-manifest issuer, and
 * consent selection together. A later root reconfiguration or consent withdrawal revokes this
 * epoch; it never silently substitutes a new provider into an existing controller.
 */
internal class UltraProfilePolicyEpoch(
    val vehicleIdentityAuthority: TrustedUltraVehicleIdentityAuthority,
    val deploymentProvider: UltraProfileDeploymentProvider,
    val manifestIssuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
    val ultraConsentSelected: Boolean,
    val uiSyncConsentSelected: Boolean,
    private val currentSelection: () -> Boolean,
) {
    internal interface Participant {
        /** Synchronous admission revoke only; no coordinator/Host close here. */
        fun onEpochRevoked()

        /** Exact old-owner Host UI-layer cover ACK, called outside root/Store/coordinator locks. */
        fun coverAck(): Boolean

        /** The old owner may retire only after every participant returned an ACK. */
        fun onCoverAcknowledged()
    }

    private val revocationLock = Any()
    @Volatile private var revoked = false
    private var coverSatisfied = false
    private var retirementReleased = false
    private val participants = ArrayList<Participant>()

    /** Any failed root/consent probe is a revocation, not permission to keep using this epoch. */
    fun isLive(): Boolean = try {
        !revoked && currentSelection() && !revoked
    } catch (_: Exception) {
        false
    }

    /** Lock-free final guard for a product state-lock publication after a prior liveness probe. */
    fun isRevoked(): Boolean = revoked

    fun isRetirementReleased(): Boolean = synchronized(revocationLock) { retirementReleased }

    /** Root/import mutation calls this before changing any published selection. */
    fun markRevoked(): Boolean {
        val snapshot = synchronized(revocationLock) {
            revoked = true
            participants.toList()
        }
        var allRevoked = true
        snapshot.forEach { participant ->
            try { participant.onEpochRevoked() } catch (_: Exception) { allRevoked = false }
        }
        return allRevoked
    }

    /** Idempotent retry of the *same* frozen old owner; failure never releases retirement. */
    fun coverAck(): Boolean {
        val snapshot = synchronized(revocationLock) {
            if (!revoked) return true
            participants.toList()
        }
        val acknowledged = snapshot.all { participant ->
            try { participant.coverAck() } catch (_: Exception) { false }
        }
        if (!acknowledged) return false
        return synchronized(revocationLock) {
            if (participants.size != snapshot.size ||
                participants.indices.any { participants[it] !== snapshot[it] }
            ) false else {
                coverSatisfied = true
                true
            }
        }
    }

    fun releaseRetirementAfterCover() {
        val snapshot = synchronized(revocationLock) {
            check(revoked) { "Policy retirement was released before revocation" }
            check(coverSatisfied) { "Policy retirement was released before exact cover ACK" }
            if (retirementReleased) return
            retirementReleased = true
            participants.toList()
        }
        snapshot.forEach { participant -> participant.onCoverAcknowledged() }
    }

    /** Non-mutating revocation still denies immediately and retires only after cover ACK. */
    fun revoke(): Boolean {
        if (!markRevoked()) return false
        return coverAck().also { acknowledged ->
            if (acknowledged) releaseRetirementAfterCover()
        }
    }

    fun registerParticipant(participant: Participant) {
        val state = synchronized(revocationLock) {
            participants += participant
            coverSatisfied = false
            revoked to retirementReleased
        }
        if (state.first) {
            participant.onEpochRevoked()
            if (state.second) {
                participant.onCoverAcknowledged()
            }
        }
    }
}
