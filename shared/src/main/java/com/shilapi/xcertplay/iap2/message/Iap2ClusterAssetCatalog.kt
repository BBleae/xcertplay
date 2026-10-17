package com.shilapi.xcertplay.iap2.message

/**
 * Local evidence state for an opaque CarPlay cluster-asset identifier/version pair.
 *
 * Neither the identifier nor the version is interpreted here. In particular, the version is not
 * used for ordering and this type does not imply that an asset can be downloaded or updated.
 */
enum class Iap2ClusterAssetEvidence {
    UNVERIFIED,
    EVIDENCE_BACKED,
}

/** Whether the exact asset represented by a profile is already present on the accessory. */
enum class Iap2ClusterAssetInstallation {
    UNKNOWN,
    PREINSTALLED,
}

/** Whether local policy permits advertising the exact asset to the connected phone. */
enum class Iap2ClusterAssetAuthorization {
    NOT_AUTHORIZED,
    AUTHORIZED,
}

/** Exact opaque metadata used to select a profile from an [Iap2ClusterAssetCatalog]. */
data class Iap2ClusterAssetKey(
    val assetId: String,
    val assetVersion: Long,
) {
    init {
        validateClusterAssetMetadata(assetId, assetVersion)
    }
}

/**
 * Immutable local metadata for one opaque cluster-asset selection.
 *
 * Safe defaults deliberately make a newly constructed profile ineligible for advertisement. A
 * caller must independently mark the metadata as evidence-backed, preinstalled and authorized.
 */
data class Iap2ClusterAssetProfile(
    val assetId: String,
    val assetVersion: Long,
    val evidence: Iap2ClusterAssetEvidence = Iap2ClusterAssetEvidence.UNVERIFIED,
    val installation: Iap2ClusterAssetInstallation = Iap2ClusterAssetInstallation.UNKNOWN,
    val authorization: Iap2ClusterAssetAuthorization =
        Iap2ClusterAssetAuthorization.NOT_AUTHORIZED,
) {
    init {
        validateClusterAssetMetadata(assetId, assetVersion)
    }

    internal val isAdvertisementEligible: Boolean
        get() = evidence == Iap2ClusterAssetEvidence.EVIDENCE_BACKED &&
            installation == Iap2ClusterAssetInstallation.PREINSTALLED &&
            authorization == Iap2ClusterAssetAuthorization.AUTHORIZED
}

/**
 * Immutable allow-list for cluster-asset metadata used by CarPlayStartSession (0x4301).
 *
 * This catalog only gates selection metadata. It intentionally provides no asset bytes, transfer
 * protocol, update acknowledgement, or claim that a phone has accepted an asset.
 */
class Iap2ClusterAssetCatalog private constructor(profiles: List<Iap2ClusterAssetProfile>) {
    private data class Key(val assetId: String, val assetVersion: Long)

    private val registeredProfiles = profiles.toList()
    private val profilesByKey = registeredProfiles.associateBy { Key(it.assetId, it.assetVersion) }

    init {
        require(profilesByKey.size == registeredProfiles.size) {
            "cluster asset catalog must not contain duplicate ID/version pairs"
        }
    }

    /** A defensive snapshot; mutating the input collection cannot change this catalog. */
    val profiles: List<Iap2ClusterAssetProfile>
        get() = registeredProfiles.toList()

    /**
     * Returns wire metadata only for an exact, explicitly eligible catalog entry.
     *
     * A missing, unverified, not-confirmed-preinstalled or unauthorized profile resolves to null,
     * which keeps parameter group 7 absent from 0x4301.
     */
    fun assetOrNull(assetId: String, assetVersion: Long): Iap2ClusterAsset? {
        val profile = profilesByKey[Key(assetId, assetVersion)] ?: return null
        if (!profile.isAdvertisementEligible) return null
        return Iap2ClusterAsset(profile.assetId, profile.assetVersion)
    }

    fun assetOrNull(key: Iap2ClusterAssetKey?): Iap2ClusterAsset? =
        key?.let { assetOrNull(it.assetId, it.assetVersion) }

    companion object {
        /** Production-safe default: there is no cluster asset to advertise. */
        val EMPTY: Iap2ClusterAssetCatalog = Iap2ClusterAssetCatalog(emptyList())

        fun of(vararg profiles: Iap2ClusterAssetProfile): Iap2ClusterAssetCatalog =
            if (profiles.isEmpty()) EMPTY else Iap2ClusterAssetCatalog(profiles.asList())
    }
}

private fun validateClusterAssetMetadata(assetId: String, assetVersion: Long) {
    require(assetId.isNotBlank()) { "cluster asset ID must not be blank" }
    require('\u0000' !in assetId) { "cluster asset ID must not contain U+0000" }
    require(assetVersion in 0..0xffff_ffffL) { "cluster asset version must fit in u32" }
}
