package com.shilapi.xcertplay.vehicle.ultra.profile

import com.shilapi.xcertplay.airplay.BplistCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Exact root artifacts observed in the iOS 27 CarPlay theme-asset package path. */
object UltraProfilePackageFiles {
    const val CARPLAY_PHONE_ASSETS = "CarPlayPhone.assets"
    const val CARPLAY_ACCESSORY_ASSETS = "CarPlayAccessory.assets.zip"
    const val CERTIFICATE_CHAIN = "Certificate.chain.pem"
    const val ACCESSORY_ASSETS_SHA256 = "CarPlayAccessory.assets.sha256"
    const val ACCESSORY_ADDITIONS = "AccessoryAdditions"
    const val INFO_PLIST = "Info.plist"

    internal val orderedRequiredFileNames = listOf(
        ACCESSORY_ADDITIONS,
        CARPLAY_ACCESSORY_ASSETS,
        ACCESSORY_ASSETS_SHA256,
        CERTIFICATE_CHAIN,
        INFO_PLIST,
    )

    const val CARPLAY_PHONE_ASSETS_DIRECTORY = "CarPlayPhone.assets/"
    const val CARPLAY_PHONE_PACKAGE_JSON = "CarPlayPhone.assets/package.json"
    const val CARPLAY_PHONE_THEME_JSON = "CarPlayPhone.assets/theme.json"
    const val CARPLAY_PHONE_INFO_PLIST = "CarPlayPhone.assets/Info.plist"

    internal val orderedRequiredPhoneAssetFileNames = listOf(
        CARPLAY_PHONE_INFO_PLIST,
        CARPLAY_PHONE_PACKAGE_JSON,
        CARPLAY_PHONE_THEME_JSON,
    )

    /** A fresh, read-only snapshot; callers cannot mutate the inspector's allowlist. */
    val requiredNames: Set<String>
        get() = Collections.unmodifiableSet(
            LinkedHashSet(orderedRequiredFileNames + CARPLAY_PHONE_ASSETS),
        )
}

/** Resource ceilings for parsing an untrusted, preinstalled Ultra profile candidate. */
data class UltraProfilePackageLimits(
    val maximumArchiveBytes: Long = 32L * 1024L * 1024L,
    val maximumTotalContentBytes: Long = 48L * 1024L * 1024L,
    val maximumFileBytes: Long = 32L * 1024L * 1024L,
    val maximumFileCount: Int = 4096,
    val maximumPathBytes: Int = 255,
    val maximumExtraBytes: Int = 4 * 1024,
    val maximumCompressionRatio: Int = 250,
    val maximumInfoPlistBytes: Int = 1024 * 1024,
    /**
     * Canonical packages use STORED entries, so they may be much larger than an accepted,
     * compressed provisioning package. This is deliberately independent from
     * [maximumArchiveBytes].
     */
    val maximumCanonicalArchiveBytes: Long = 64L * 1024L * 1024L,
) {
    init {
        require(maximumArchiveBytes in 1..HARD_MAXIMUM_ARCHIVE_BYTES)
        require(maximumCanonicalArchiveBytes in 1..HARD_MAXIMUM_CANONICAL_ARCHIVE_BYTES)
        require(maximumTotalContentBytes in 1..HARD_MAXIMUM_TOTAL_CONTENT_BYTES)
        require(maximumFileBytes in 1..HARD_MAXIMUM_FILE_BYTES)
        require(maximumFileCount in 1..HARD_MAXIMUM_FILE_COUNT)
        require(maximumPathBytes in 1..HARD_MAXIMUM_PATH_BYTES)
        require(maximumExtraBytes in 0..HARD_MAXIMUM_EXTRA_BYTES)
        require(maximumCompressionRatio in 1..HARD_MAXIMUM_COMPRESSION_RATIO)
        require(maximumInfoPlistBytes in 1..HARD_MAXIMUM_INFO_PLIST_BYTES)
        require(maximumFileBytes <= maximumTotalContentBytes)
        require(maximumInfoPlistBytes.toLong() <= maximumFileBytes)
    }

    private companion object {
        const val HARD_MAXIMUM_ARCHIVE_BYTES = 64L * 1024L * 1024L
        const val HARD_MAXIMUM_CANONICAL_ARCHIVE_BYTES = 128L * 1024L * 1024L
        const val HARD_MAXIMUM_TOTAL_CONTENT_BYTES = 96L * 1024L * 1024L
        const val HARD_MAXIMUM_FILE_BYTES = 64L * 1024L * 1024L
        const val HARD_MAXIMUM_FILE_COUNT = 8192
        const val HARD_MAXIMUM_PATH_BYTES = 1024
        const val HARD_MAXIMUM_EXTRA_BYTES = 64 * 1024
        const val HARD_MAXIMUM_COMPRESSION_RATIO = 1000
        const val HARD_MAXIMUM_INFO_PLIST_BYTES = 4 * 1024 * 1024
    }
}

/** Stable, non-sensitive reason suitable for diagnostics when a candidate remains quarantined. */
enum class UltraProfileQuarantineCode {
    ARCHIVE_LIMIT_EXCEEDED,
    CANONICAL_ARCHIVE_LIMIT_EXCEEDED,
    NON_CANONICAL_ARCHIVE,
    MALFORMED_ZIP,
    INVALID_ENTRY_PATH,
    DIRECTORY_ENTRY,
    NESTED_ENTRY,
    DUPLICATE_ENTRY,
    CASE_CONFLICT,
    UNDECLARED_ENTRY,
    MISSING_REQUIRED_ENTRY,
    EMPTY_REQUIRED_ENTRY,
    FILE_LIMIT_EXCEEDED,
    TOTAL_CONTENT_LIMIT_EXCEEDED,
    COMPRESSION_RATIO_EXCEEDED,
    UNSUPPORTED_ZIP_FEATURE,
    INVALID_INFO_PLIST,
    NO_TRUST_VERIFIER,
    TRUST_REJECTED,
    TRUST_VERIFIER_FAILED,
    TRUST_BINDING_MISMATCH,
}

data class QuarantinedUltraProfile(
    val code: UltraProfileQuarantineCode,
    val safeDetail: String,
)

sealed interface UltraProfileInspectionResult {
    data class Candidate(val profile: UltraProfileCandidate) : UltraProfileInspectionResult

    data class Quarantined(
        val quarantine: QuarantinedUltraProfile,
    ) : UltraProfileInspectionResult
}

/** Immutable SHA-256 value with content equality and defensive byte-array access. */
class UltraProfileDigest(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == SHA256_BYTES) { "Ultra profile digest must be SHA-256" }
    }

    fun bytes(): ByteArray = value.copyOf()

    fun hex(): String = buildString(value.size * 2) {
        value.forEach { byte -> append((byte.toInt() and 0xff).toString(16).padStart(2, '0')) }
    }

    override fun equals(other: Any?): Boolean =
        other is UltraProfileDigest && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "UltraProfileDigest(sha256=${hex()})"

    private companion object {
        const val SHA256_BYTES = 32
    }
}

data class UltraProfileEntryMetadata(
    val name: String,
    val sizeBytes: Long,
    val digest: UltraProfileDigest,
)

/** Digest and byte count produced while streaming a canonical package. */
data class UltraProfileCanonicalArchiveMetadata(
    val sizeBytes: Long,
    val digest: UltraProfileDigest,
)

/** Parsed Info.plist bytes and value tree. Every byte array and collection is copied on access. */
class UltraProfileManifest internal constructor(
    rawBytes: ByteArray,
    values: Map<String, Any?>,
) {
    private val storedRawBytes = rawBytes.copyOf()
    private val storedValues = deepCopyMap(values)

    val rawBytes: ByteArray
        get() = storedRawBytes.copyOf()

    val values: Map<String, Any?>
        get() = deepCopyMap(storedValues)

    val digest = UltraProfileDigest(sha256(storedRawBytes))
}

/**
 * Structurally valid but not trusted profile material.
 *
 * A candidate is deliberately not an activation capability. It stays quarantined until an
 * explicitly supplied [UltraProfileTrustVerifier] returns a proof bound to every expected field.
 */
class UltraProfileCandidate internal constructor(
    archiveDigest: UltraProfileDigest,
    files: Map<String, ByteArray>,
    directories: Set<String>,
    val manifest: UltraProfileManifest,
    private val maximumCanonicalArchiveBytes: Long,
) {
    private val originalArchiveDigest = archiveDigest
    private val storedFiles = LinkedHashMap<String, ByteArray>(files.size).also { destination ->
        // Inspector-created arrays have no external owner. Taking ownership avoids doubling the
        // entire decompressed package at the untrusted-input memory peak.
        files.forEach { (name, bytes) -> destination[name] = bytes }
    }
    private val storedDirectories = Collections.unmodifiableSet(LinkedHashSet(directories))

    val archiveDigest: UltraProfileDigest
        get() = originalArchiveDigest
    val contentDigest = UltraProfileDigest(canonicalContentDigest(storedFiles, storedDirectories))
    val canonicalArchiveMetadata: UltraProfileCanonicalArchiveMetadata =
        writeCanonicalArchive(
            storedFiles,
            storedDirectories,
            DISCARD_OUTPUT,
            maximumCanonicalArchiveBytes,
        )

    val fileNames: Set<String>
        get() = Collections.unmodifiableSet(LinkedHashSet(storedFiles.keys))

    val directoryNames: Set<String>
        get() = Collections.unmodifiableSet(LinkedHashSet(storedDirectories))

    val entries: List<UltraProfileEntryMetadata>
        get() = storedFiles.keys.sorted().map { name ->
            val bytes = storedFiles.getValue(name)
            UltraProfileEntryMetadata(name, bytes.size.toLong(), UltraProfileDigest(sha256(bytes)))
        }

    /**
     * Repackages only inspected allowlisted content. Raw attacker-controlled ZIP metadata is never
     * exposed to a verifier/store using a second parser with different path or central-dir rules.
     */
    /**
     * Streams the deterministic canonical package without constructing another package-sized
     * byte array. The caller retains ownership of [output], which is flushed but never closed.
     */
    fun writeCanonicalArchive(output: OutputStream): UltraProfileCanonicalArchiveMetadata {
        val written = writeCanonicalArchive(
            storedFiles,
            storedDirectories,
            output,
            maximumCanonicalArchiveBytes,
        )
        check(written == canonicalArchiveMetadata) { "Canonical Ultra profile output changed" }
        return written
    }

    fun content(name: String): ByteArray? = storedFiles[name]?.copyOf()
}

/** Opaque deployment identity and versions that a trusted verifier must attest together. */
data class UltraProfileTrustBinding(
    val vehicleId: String,
    val mfiCertificateIdentity: String,
    val assetIdentifier: String,
    val iosContentVersion: String,
    val accessoryContentVersion: String,
    val contentDigest: UltraProfileDigest,
) {
    init {
        validateOpaqueBindingValue(vehicleId, "vehicleId", 512)
        validateOpaqueBindingValue(mfiCertificateIdentity, "mfiCertificateIdentity", 1024)
        validateOpaqueBindingValue(assetIdentifier, "assetIdentifier", 512)
        validateOpaqueBindingValue(iosContentVersion, "iosContentVersion", 128)
        validateOpaqueBindingValue(accessoryContentVersion, "accessoryContentVersion", 128)
    }

    /** Never expose stable vehicle, certificate, asset, or version identifiers through logs. */
    override fun toString(): String = "UltraProfileTrustBinding(<redacted>)"
}

data class UltraProfileTrustRequest(
    val candidate: UltraProfileCandidate,
    val expectedBinding: UltraProfileTrustBinding,
)

/** Result emitted only by a deployment-provided OEM/MFi trust implementation. */
sealed interface UltraProfileTrustResult {
    class Trusted(val attestation: UltraProfileTrustAttestation) : UltraProfileTrustResult

    class Rejected : UltraProfileTrustResult
}

/** Verifier-produced proof bytes plus the complete identity/version/content binding they attest. */
class UltraProfileTrustAttestation(
    val binding: UltraProfileTrustBinding,
    proofBytes: ByteArray,
) {
    private val storedProof = proofBytes.copyOf()

    init {
        require(storedProof.isNotEmpty()) { "Ultra profile trust proof must not be empty" }
        require(storedProof.size <= MAXIMUM_PROOF_BYTES) { "Ultra profile trust proof is too large" }
    }

    fun proofBytes(): ByteArray = storedProof.copyOf()

    private companion object {
        const val MAXIMUM_PROOF_BYTES = 64 * 1024
    }
}

fun interface UltraProfileTrustVerifier {
    fun verify(request: UltraProfileTrustRequest): UltraProfileTrustResult
}

sealed interface UltraProfileVerificationResult {
    data class Verified(val profile: VerifiedUltraProfile) : UltraProfileVerificationResult

    data class Quarantined(
        val quarantine: QuarantinedUltraProfile,
    ) : UltraProfileVerificationResult
}

/**
 * Proof-carrying profile. This is intentionally not a data class and has no public factory/copy.
 * The hidden permit prevents even friend-module callers from constructing it from arbitrary parts.
 */
class VerifiedUltraProfile internal constructor(
    val candidate: UltraProfileCandidate,
    val binding: UltraProfileTrustBinding,
    attestation: UltraProfileTrustAttestation,
    permit: Any,
) {
    private val storedProof = attestation.proofBytes()

    init {
        require(permit === VERIFIED_PROFILE_PERMIT) { "Verified Ultra profile construction is gated" }
        require(binding == attestation.binding) { "Verified Ultra profile binding mismatch" }
        require(binding.contentDigest == candidate.contentDigest) {
            "Verified Ultra profile content mismatch"
        }
    }

    fun trustProofBytes(): ByteArray = storedProof.copyOf()
}

/**
 * Strict parser and trust gate for an externally provisioned, preinstalled Ultra profile package.
 *
 * It does not interpret Apple signature/checksum formats, install assets, advertise a profile, or
 * implement UpdateData. Those actions require an authorized verifier and a separate local policy.
 */
class UltraProfilePackageInspector(
    private val limits: UltraProfilePackageLimits = UltraProfilePackageLimits(),
) {
    /** Parses a bounded, potentially compressed, untrusted provisioning package. */
    fun inspect(input: InputStream): UltraProfileInspectionResult = inspect(
        input = input,
        maximumArchiveBytes = limits.maximumArchiveBytes,
        requireCanonicalEncoding = false,
    )

    /**
     * Parses persisted canonical packages under their separate (normally larger) archive bound
     * and rejects packages whose bytes are not exactly the deterministic canonical encoding.
     */
    fun inspectCanonical(input: InputStream): UltraProfileInspectionResult = inspect(
        input = input,
        maximumArchiveBytes = limits.maximumCanonicalArchiveBytes,
        requireCanonicalEncoding = true,
    )

    /** Allows a store to reject an incompatible candidate before publishing its active pointer. */
    fun acceptsCanonicalArchive(metadata: UltraProfileCanonicalArchiveMetadata): Boolean =
        metadata.sizeBytes in 1..limits.maximumCanonicalArchiveBytes

    private fun inspect(
        input: InputStream,
        maximumArchiveBytes: Long,
        requireCanonicalEncoding: Boolean,
    ): UltraProfileInspectionResult {
        val archive = try {
            readBounded(input, maximumArchiveBytes)
        } catch (_: LimitExceededException) {
            return quarantined(
                if (requireCanonicalEncoding) {
                    UltraProfileQuarantineCode.CANONICAL_ARCHIVE_LIMIT_EXCEEDED
                } else {
                    UltraProfileQuarantineCode.ARCHIVE_LIMIT_EXCEEDED
                },
                "Profile archive exceeds its configured byte limit",
            )
        } catch (_: IOException) {
            return quarantined(
                UltraProfileQuarantineCode.MALFORMED_ZIP,
                "Profile archive could not be read",
            )
        }

        val centralEntries = when (val central = inspectCentralDirectory(archive)) {
            CentralDirectoryInspection.Malformed -> {
                return quarantined(
                    UltraProfileQuarantineCode.MALFORMED_ZIP,
                    "Profile archive central directory is malformed",
                )
            }
            CentralDirectoryInspection.UnsupportedEntryType -> {
                return quarantined(
                    UltraProfileQuarantineCode.UNSUPPORTED_ZIP_FEATURE,
                    "Profile archive contains a link or unsupported entry type",
                )
            }
            is CentralDirectoryInspection.Valid -> central.entries
        }

        val files = LinkedHashMap<String, ByteArray>()
        val directories = LinkedHashSet<String>()
        val localEntries = ArrayList<ArchiveEntryIdentity>(centralEntries.size)
        val foldedNames = HashMap<String, String>()
        val foldedNodes = HashMap<String, Pair<String, Boolean>>()
        var totalBytes = 0L
        var fileCount = 0

        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    fileCount++
                    if (fileCount > limits.maximumFileCount) {
                        return quarantined(
                            UltraProfileQuarantineCode.FILE_LIMIT_EXCEEDED,
                            "Profile archive has too many entries",
                        )
                    }

                    validatePath(entry)?.let { return UltraProfileInspectionResult.Quarantined(it) }
                    validateZipFeatures(entry)?.let {
                        return UltraProfileInspectionResult.Quarantined(it)
                    }

                    val name = entry.name
                    val isDirectory = entry.isDirectory || name.endsWith('/')
                    localEntries += ArchiveEntryIdentity(name, isDirectory, entry.method)
                    val foldedName = name.lowercase(Locale.ROOT)
                    val previousSpelling = foldedNames[foldedName]
                    if (previousSpelling != null) {
                        val code = if (previousSpelling == name) {
                            UltraProfileQuarantineCode.DUPLICATE_ENTRY
                        } else {
                            UltraProfileQuarantineCode.CASE_CONFLICT
                        }
                        return quarantined(code, "Profile archive contains a conflicting entry")
                    }
                    val nodeName = name.removeSuffix("/")
                    val foldedNode = nodeName.lowercase(Locale.ROOT)
                    val previousNode = foldedNodes[foldedNode]
                    if (previousNode != null &&
                        (previousNode.first != nodeName || previousNode.second != isDirectory)
                    ) {
                        return quarantined(
                            UltraProfileQuarantineCode.CASE_CONFLICT,
                            "Profile archive contains a file/directory or case conflict",
                        )
                    }
                    foldedNames[foldedName] = name
                    foldedNodes[foldedNode] = nodeName to isDirectory
                    validateStructure(name, isDirectory)?.let {
                        return UltraProfileInspectionResult.Quarantined(it)
                    }

                    val declaredSize = entry.size
                    if (declaredSize < -1L) {
                        return quarantined(
                            UltraProfileQuarantineCode.MALFORMED_ZIP,
                            "Profile entry has an invalid declared size",
                        )
                    }
                    if (isDirectory && declaredSize > 0L) {
                        return quarantined(
                            UltraProfileQuarantineCode.MALFORMED_ZIP,
                            "Profile directory entry contains data",
                        )
                    }
                    if (declaredSize > limits.maximumFileBytes) {
                        return quarantined(
                            UltraProfileQuarantineCode.FILE_LIMIT_EXCEEDED,
                            "Profile entry exceeds the configured byte limit",
                        )
                    }
                    if (declaredSize >= 0L && declaredSize > limits.maximumTotalContentBytes - totalBytes) {
                        return quarantined(
                            UltraProfileQuarantineCode.TOTAL_CONTENT_LIMIT_EXCEEDED,
                            "Profile content exceeds the configured aggregate byte limit",
                        )
                    }

                    val remainingTotal = limits.maximumTotalContentBytes - totalBytes
                    if (isDirectory) {
                        val content = try {
                            readBounded(zip, 0)
                        } catch (_: LimitExceededException) {
                            return quarantined(
                                UltraProfileQuarantineCode.MALFORMED_ZIP,
                                "Profile directory entry contains data",
                            )
                        }
                        zip.closeEntry()
                        if (content.isNotEmpty() || entry.size != 0L) {
                            return quarantined(
                                UltraProfileQuarantineCode.MALFORMED_ZIP,
                                "Profile directory entry contains data",
                            )
                        }
                        directories += name
                        continue
                    }
                    val entrySpecificLimit = if (name == UltraProfilePackageFiles.INFO_PLIST) {
                        minOf(limits.maximumFileBytes, limits.maximumInfoPlistBytes.toLong())
                    } else {
                        limits.maximumFileBytes
                    }
                    val fileLimit = minOf(entrySpecificLimit, remainingTotal)
                    val content = try {
                        readBounded(zip, fileLimit)
                    } catch (_: LimitExceededException) {
                        val code = if (remainingTotal < entrySpecificLimit) {
                            UltraProfileQuarantineCode.TOTAL_CONTENT_LIMIT_EXCEEDED
                        } else {
                            UltraProfileQuarantineCode.FILE_LIMIT_EXCEEDED
                        }
                        return quarantined(code, "Profile content exceeds a configured byte limit")
                    }
                    zip.closeEntry()

                    if (content.isEmpty()) {
                        return quarantined(
                            UltraProfileQuarantineCode.EMPTY_REQUIRED_ENTRY,
                            "Profile archive contains an empty required file",
                        )
                    }
                    if (entry.size < 0L || entry.size != content.size.toLong()) {
                        return quarantined(
                            UltraProfileQuarantineCode.MALFORMED_ZIP,
                            "Profile entry size is inconsistent",
                        )
                    }
                    val compressedSize = entry.compressedSize
                    if (compressedSize <= 0L) {
                        return quarantined(
                            UltraProfileQuarantineCode.MALFORMED_ZIP,
                            "Profile entry compressed size is unavailable",
                        )
                    }
                    if (exceedsRatio(content.size.toLong(), compressedSize)) {
                        return quarantined(
                            UltraProfileQuarantineCode.COMPRESSION_RATIO_EXCEEDED,
                            "Profile entry exceeds the configured compression ratio",
                        )
                    }

                    totalBytes += content.size.toLong()
                    files[name] = content
                }
            }
        } catch (_: ZipException) {
            return quarantined(
                UltraProfileQuarantineCode.MALFORMED_ZIP,
                "Profile archive failed ZIP integrity validation",
            )
        } catch (_: IOException) {
            return quarantined(
                UltraProfileQuarantineCode.MALFORMED_ZIP,
                "Profile archive could not be decoded",
            )
        } catch (_: IllegalArgumentException) {
            return quarantined(
                UltraProfileQuarantineCode.MALFORMED_ZIP,
                "Profile archive contains invalid ZIP metadata",
            )
        }

        if (!sameEntryMultiset(localEntries, centralEntries)) {
            return quarantined(
                UltraProfileQuarantineCode.MALFORMED_ZIP,
                "Profile archive local entries do not match its central directory",
            )
        }

        val missingFile = UltraProfilePackageFiles.orderedRequiredFileNames.any { it !in files }
        val missingPhoneRootFile = UltraProfilePackageFiles.orderedRequiredPhoneAssetFileNames
            .any { it !in files }
        if (missingFile || missingPhoneRootFile) {
            return quarantined(
                UltraProfileQuarantineCode.MISSING_REQUIRED_ENTRY,
                "Profile archive is missing required iOS 27 evidence",
            )
        }

        val normalizedDirectories = normalizedDirectories(files.keys, directories)
        if (hasTreeCaseConflict(files.keys, normalizedDirectories) ||
            files.keys.any { file -> hasRegularFileAncestor(file, files.keys) } ||
            normalizedDirectories.any { directory ->
                val node = directory.removeSuffix("/")
                node in files || hasRegularFileAncestor(node, files.keys)
            }
        ) {
            return quarantined(
                UltraProfileQuarantineCode.CASE_CONFLICT,
                "Profile archive contains a regular file used as a directory",
            )
        }
        if (files.size + normalizedDirectories.size > limits.maximumFileCount) {
            return quarantined(
                UltraProfileQuarantineCode.FILE_LIMIT_EXCEEDED,
                "Canonical profile archive has too many entries",
            )
        }

        val infoBytes = files.getValue(UltraProfilePackageFiles.INFO_PLIST)
        if (infoBytes.size > limits.maximumInfoPlistBytes) {
            return quarantined(
                UltraProfileQuarantineCode.FILE_LIMIT_EXCEEDED,
                "Info.plist exceeds the configured byte limit",
            )
        }
        val manifest = try {
            val decoded = BplistCodec.decode(infoBytes)
            val dictionary = decoded as? Map<*, *>
                ?: return quarantined(
                    UltraProfileQuarantineCode.INVALID_INFO_PLIST,
                    "Info.plist root is not a dictionary",
                )
            val typed = LinkedHashMap<String, Any?>(dictionary.size)
            dictionary.forEach { (key, value) ->
                if (key !is String) {
                    return quarantined(
                        UltraProfileQuarantineCode.INVALID_INFO_PLIST,
                        "Info.plist contains a non-string dictionary key",
                    )
                }
                typed[key] = value
            }
            UltraProfileManifest(infoBytes, typed)
        } catch (_: IllegalArgumentException) {
            return quarantined(
                UltraProfileQuarantineCode.INVALID_INFO_PLIST,
                "Info.plist is not a supported bounded binary property list",
            )
        }

        val candidate = try {
            UltraProfileCandidate(
                archiveDigest = UltraProfileDigest(sha256(archive)),
                files = files,
                directories = normalizedDirectories,
                manifest = manifest,
                maximumCanonicalArchiveBytes = limits.maximumCanonicalArchiveBytes,
            )
        } catch (_: CanonicalArchiveLimitExceededException) {
            return quarantined(
                UltraProfileQuarantineCode.CANONICAL_ARCHIVE_LIMIT_EXCEEDED,
                "Canonical profile archive exceeds the configured byte limit",
            )
        } catch (_: IOException) {
            return quarantined(
                UltraProfileQuarantineCode.MALFORMED_ZIP,
                "Canonical profile archive could not be generated",
            )
        }
        if (requireCanonicalEncoding &&
            (candidate.canonicalArchiveMetadata.sizeBytes != archive.size.toLong() ||
                candidate.canonicalArchiveMetadata.digest != candidate.archiveDigest)
        ) {
            return quarantined(
                UltraProfileQuarantineCode.NON_CANONICAL_ARCHIVE,
                "Persisted profile is not in the canonical encoding",
            )
        }
        return UltraProfileInspectionResult.Candidate(candidate)
    }

    fun verify(
        candidate: UltraProfileCandidate,
        expectedBinding: UltraProfileTrustBinding,
        verifier: UltraProfileTrustVerifier? = null,
    ): UltraProfileVerificationResult {
        if (expectedBinding.contentDigest != candidate.contentDigest) {
            return verificationQuarantine(
                UltraProfileQuarantineCode.TRUST_BINDING_MISMATCH,
                "Expected trust binding does not match the candidate content",
            )
        }
        if (verifier == null) {
            return verificationQuarantine(
                UltraProfileQuarantineCode.NO_TRUST_VERIFIER,
                "No authorized Ultra profile trust verifier is configured",
            )
        }

        val result = try {
            verifier.verify(UltraProfileTrustRequest(candidate, expectedBinding))
        } catch (_: Exception) {
            return verificationQuarantine(
                UltraProfileQuarantineCode.TRUST_VERIFIER_FAILED,
                "The Ultra profile trust verifier failed closed",
            )
        }
        if (result is UltraProfileTrustResult.Rejected) {
            return verificationQuarantine(
                UltraProfileQuarantineCode.TRUST_REJECTED,
                "The Ultra profile trust verifier rejected the candidate",
            )
        }

        result as UltraProfileTrustResult.Trusted
        if (result.attestation.binding != expectedBinding ||
            result.attestation.binding.contentDigest != candidate.contentDigest
        ) {
            return verificationQuarantine(
                UltraProfileQuarantineCode.TRUST_BINDING_MISMATCH,
                "Verifier proof is not bound to the complete expected identity and content",
            )
        }
        return UltraProfileVerificationResult.Verified(
            VerifiedUltraProfile(
                candidate = candidate,
                binding = expectedBinding,
                attestation = result.attestation,
                permit = VERIFIED_PROFILE_PERMIT,
            ),
        )
    }

    private fun validatePath(entry: ZipEntry): QuarantinedUltraProfile? {
        val name = entry.name
        if (name.isEmpty() ||
            name.toByteArray(Charsets.UTF_8).size > limits.maximumPathBytes ||
            name.any { it == '\u0000' || it.isISOControl() } ||
            name.startsWith('/') ||
            name.startsWith("//") ||
            WINDOWS_DRIVE_PREFIX.matches(name.take(2)) ||
            '\\' in name ||
            "//" in name ||
            name.split('/').any { it == "." || it == ".." } ||
            !Normalizer.isNormalized(name, Normalizer.Form.NFC)
        ) {
            return QuarantinedUltraProfile(
                UltraProfileQuarantineCode.INVALID_ENTRY_PATH,
                "Profile archive contains an unsafe entry path",
            )
        }
        return null
    }

    private fun validateStructure(
        name: String,
        isDirectory: Boolean,
    ): QuarantinedUltraProfile? {
        val phoneRoot = UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY
        if (isDirectory) {
            if (name == phoneRoot || name.startsWith(phoneRoot)) return null
            return QuarantinedUltraProfile(
                UltraProfileQuarantineCode.DIRECTORY_ENTRY,
                "Only the CarPlayPhone.assets tree may contain directories",
            )
        }
        if (name == UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS) {
            return QuarantinedUltraProfile(
                UltraProfileQuarantineCode.DIRECTORY_ENTRY,
                "CarPlayPhone.assets must be a directory tree",
            )
        }
        if (name.startsWith(phoneRoot) && name.length > phoneRoot.length) return null
        if ('/' in name) {
            return QuarantinedUltraProfile(
                UltraProfileQuarantineCode.NESTED_ENTRY,
                "Nested files are only allowed inside CarPlayPhone.assets",
            )
        }
        if (name !in UltraProfilePackageFiles.orderedRequiredFileNames) {
            return QuarantinedUltraProfile(
                UltraProfileQuarantineCode.UNDECLARED_ENTRY,
                "Profile archive contains a file outside the iOS 27 evidence allowlist",
            )
        }
        return null
    }

    private fun validateZipFeatures(entry: ZipEntry): QuarantinedUltraProfile? {
        if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
            return QuarantinedUltraProfile(
                UltraProfileQuarantineCode.UNSUPPORTED_ZIP_FEATURE,
                "Profile entry uses an unsupported ZIP compression method",
            )
        }
        val extra = entry.extra ?: ByteArray(0)
        if (extra.size > limits.maximumExtraBytes || containsZip64Extra(extra)) {
            return QuarantinedUltraProfile(
                UltraProfileQuarantineCode.UNSUPPORTED_ZIP_FEATURE,
                "Profile entry uses unsupported ZIP metadata",
            )
        }
        return null
    }

    private fun containsZip64Extra(extra: ByteArray): Boolean {
        var offset = 0
        while (offset < extra.size) {
            if (extra.size - offset < 4) return true
            val id = (extra[offset].toInt() and 0xff) or
                ((extra[offset + 1].toInt() and 0xff) shl 8)
            val length = (extra[offset + 2].toInt() and 0xff) or
                ((extra[offset + 3].toInt() and 0xff) shl 8)
            offset += 4
            if (length > extra.size - offset) return true
            if (id == ZIP64_EXTRA_ID) return true
            offset += length
        }
        return false
    }

    private fun exceedsRatio(uncompressed: Long, compressed: Long): Boolean {
        val ratio = limits.maximumCompressionRatio.toLong()
        return compressed <= Long.MAX_VALUE / ratio && uncompressed > compressed * ratio
    }

    private fun quarantined(
        code: UltraProfileQuarantineCode,
        detail: String,
    ) = UltraProfileInspectionResult.Quarantined(QuarantinedUltraProfile(code, detail))

    private fun verificationQuarantine(
        code: UltraProfileQuarantineCode,
        detail: String,
    ) = UltraProfileVerificationResult.Quarantined(QuarantinedUltraProfile(code, detail))

    private companion object {
        val WINDOWS_DRIVE_PREFIX = Regex("[A-Za-z]:")
        const val ZIP64_EXTRA_ID = 0x0001
    }
}

private object VERIFIED_PROFILE_PERMIT

private class LimitExceededException : IOException()

private data class ArchiveEntryIdentity(
    val name: String,
    val isDirectory: Boolean,
    val method: Int,
)

private sealed interface CentralDirectoryInspection {
    data class Valid(val entries: List<ArchiveEntryIdentity>) : CentralDirectoryInspection

    object Malformed : CentralDirectoryInspection

    object UnsupportedEntryType : CentralDirectoryInspection
}

/**
 * ZipInputStream intentionally ignores central-directory Unix mode bits. Inspect them separately
 * so a symbolic link can never be normalized into a regular file by the canonical repackager.
 */
private fun inspectCentralDirectory(archive: ByteArray): CentralDirectoryInspection {
    val minimumEocdOffset = archive.size - ZIP_END_MINIMUM_BYTES
    if (minimumEocdOffset < 0) return CentralDirectoryInspection.Malformed
    val earliest = maxOf(0, archive.size - ZIP_END_MINIMUM_BYTES - ZIP_MAXIMUM_COMMENT_BYTES)
    var eocd = -1
    for (offset in minimumEocdOffset downTo earliest) {
        if (littleEndianUInt(archive, offset) == ZIP_END_SIGNATURE) {
            eocd = offset
            break
        }
    }
    if (eocd < 0 || eocd + ZIP_END_MINIMUM_BYTES > archive.size) {
        return CentralDirectoryInspection.Malformed
    }
    val commentLength = littleEndianUShort(archive, eocd + 20)
    if (eocd + ZIP_END_MINIMUM_BYTES + commentLength != archive.size) {
        return CentralDirectoryInspection.Malformed
    }
    val disk = littleEndianUShort(archive, eocd + 4)
    val centralDisk = littleEndianUShort(archive, eocd + 6)
    val entriesOnDisk = littleEndianUShort(archive, eocd + 8)
    val entryCount = littleEndianUShort(archive, eocd + 10)
    val centralSize = littleEndianUInt(archive, eocd + 12)
    val centralOffset = littleEndianUInt(archive, eocd + 16)
    if (disk != 0 || centralDisk != 0 || entriesOnDisk != entryCount ||
        entryCount == ZIP16_SENTINEL || centralSize == ZIP32_SENTINEL ||
        centralOffset == ZIP32_SENTINEL || centralOffset > Int.MAX_VALUE.toLong() ||
        centralSize > Int.MAX_VALUE.toLong()
    ) {
        return CentralDirectoryInspection.Malformed
    }
    val centralEnd = centralOffset + centralSize
    if (centralEnd != eocd.toLong() || centralEnd > archive.size.toLong()) {
        return CentralDirectoryInspection.Malformed
    }

    val entries = ArrayList<ArchiveEntryIdentity>(entryCount)
    var cursor = centralOffset.toInt()
    repeat(entryCount) {
        if (cursor < 0 || cursor + ZIP_CENTRAL_FIXED_BYTES > eocd ||
            littleEndianUInt(archive, cursor) != ZIP_CENTRAL_SIGNATURE
        ) {
            return CentralDirectoryInspection.Malformed
        }
        val platform = archive[cursor + 5].toInt() and 0xff
        val method = littleEndianUShort(archive, cursor + 10)
        val nameLength = littleEndianUShort(archive, cursor + 28)
        val extraLength = littleEndianUShort(archive, cursor + 30)
        val entryCommentLength = littleEndianUShort(archive, cursor + 32)
        val externalAttributes = littleEndianUInt(archive, cursor + 38)
        val variableLength = nameLength.toLong() + extraLength + entryCommentLength
        val next = cursor.toLong() + ZIP_CENTRAL_FIXED_BYTES + variableLength
        if (next > eocd.toLong() || next > Int.MAX_VALUE.toLong()) {
            return CentralDirectoryInspection.Malformed
        }
        val name = decodeZipName(archive, cursor + ZIP_CENTRAL_FIXED_BYTES, nameLength)
            ?: return CentralDirectoryInspection.Malformed
        val nameSaysDirectory = name.endsWith('/')
        val unixType = if (platform == ZIP_PLATFORM_UNIX) {
            ((externalAttributes ushr 16) and UNIX_FILE_TYPE_MASK).toInt()
        } else {
            0
        }
        if (unixType != 0 && unixType != UNIX_REGULAR_FILE && unixType != UNIX_DIRECTORY) {
            return CentralDirectoryInspection.UnsupportedEntryType
        }
        val metadataSaysDirectory = when {
            unixType == UNIX_DIRECTORY -> true
            unixType == UNIX_REGULAR_FILE -> false
            externalAttributes and DOS_DIRECTORY_ATTRIBUTE != 0L -> true
            else -> nameSaysDirectory
        }
        if (metadataSaysDirectory != nameSaysDirectory) {
            return CentralDirectoryInspection.Malformed
        }
        entries += ArchiveEntryIdentity(name, metadataSaysDirectory, method)
        cursor = next.toInt()
    }
    return if (cursor == eocd) {
        CentralDirectoryInspection.Valid(Collections.unmodifiableList(entries))
    } else {
        CentralDirectoryInspection.Malformed
    }
}

private fun decodeZipName(bytes: ByteArray, offset: Int, length: Int): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, offset, length))
        .toString()
} catch (_: IllegalArgumentException) {
    null
} catch (_: java.nio.charset.CharacterCodingException) {
    null
}

private fun sameEntryMultiset(
    local: List<ArchiveEntryIdentity>,
    central: List<ArchiveEntryIdentity>,
): Boolean {
    if (local.size != central.size) return false
    fun counts(entries: List<ArchiveEntryIdentity>): Map<ArchiveEntryIdentity, Int> {
        val result = HashMap<ArchiveEntryIdentity, Int>(entries.size)
        entries.forEach { entry -> result[entry] = (result[entry] ?: 0) + 1 }
        return result
    }
    return counts(local) == counts(central)
}

private fun littleEndianUShort(bytes: ByteArray, offset: Int): Int {
    if (offset < 0 || offset > bytes.size - 2) return -1
    return (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8)
}

private fun littleEndianUInt(bytes: ByteArray, offset: Int): Long {
    if (offset < 0 || offset > bytes.size - 4) return -1
    return (bytes[offset].toLong() and 0xffL) or
        ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xffL) shl 24)
}

private const val ZIP_END_SIGNATURE = 0x06054b50L
private const val ZIP_CENTRAL_SIGNATURE = 0x02014b50L
private const val ZIP_END_MINIMUM_BYTES = 22
private const val ZIP_MAXIMUM_COMMENT_BYTES = 65_535
private const val ZIP_CENTRAL_FIXED_BYTES = 46
private const val ZIP16_SENTINEL = 0xffff
private const val ZIP32_SENTINEL = 0xffff_ffffL
private const val ZIP_PLATFORM_UNIX = 3
private const val DOS_DIRECTORY_ATTRIBUTE = 0x10L
private const val UNIX_FILE_TYPE_MASK = 0xf000L
private const val UNIX_REGULAR_FILE = 0x8000
private const val UNIX_DIRECTORY = 0x4000

private fun readBounded(input: InputStream, maximumBytes: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) {
            val byte = input.read()
            if (byte < 0) break
            if (total >= maximumBytes) throw LimitExceededException()
            output.write(byte)
            total++
            continue
        }
        if (read.toLong() > maximumBytes - total) throw LimitExceededException()
        output.write(buffer, 0, read)
        total += read.toLong()
    }
    return output.toByteArray()
}

private fun canonicalContentDigest(
    files: Map<String, ByteArray>,
    directories: Set<String>,
): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("xcertplay-ultra-profile-content-v2\u0000".toByteArray(Charsets.UTF_8))
    canonicalEntryNames(files, directories).forEach { name ->
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val content = files[name]
        digest.update(if (content == null) DIRECTORY_DIGEST_MARKER else FILE_DIGEST_MARKER)
        digest.update(bigEndian(nameBytes.size.toLong(), 4))
        digest.update(nameBytes)
        digest.update(bigEndian(content?.size?.toLong() ?: 0L, 8))
        if (content != null) digest.update(content)
    }
    return digest.digest()
}

private val DIRECTORY_DIGEST_MARKER = byteArrayOf(0)
private val FILE_DIGEST_MARKER = byteArrayOf(1)

private fun bigEndian(value: Long, byteCount: Int): ByteArray {
    val result = ByteArray(byteCount)
    var current = value
    for (index in byteCount - 1 downTo 0) {
        result[index] = (current and 0xff).toByte()
        current = current ushr 8
    }
    return result
}

private fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

private fun validateOpaqueBindingValue(value: String, field: String, maximumChars: Int) {
    require(value.isNotBlank()) { "$field must not be blank" }
    require(value.length <= maximumChars) { "$field is too long" }
    require(value.none { it == '\u0000' || it.isISOControl() }) { "$field contains control data" }
}

private fun deepCopyMap(source: Map<String, Any?>): Map<String, Any?> =
    ManifestValueCopier().copyMap(source, depth = 0)

/** Preserves shared references and enforces work/output budgets while detaching plist values. */
private class ManifestValueCopier {
    private val completed = IdentityHashMap<Any, Any>()
    private val active = IdentityHashMap<Any, Unit>()
    private var nodes = 0
    private var edges = 0
    private var stringChars = 0L
    private var dataBytes = 0L

    fun copyMap(source: Map<*, *>, depth: Int): Map<String, Any?> {
        completed[source]?.let {
            @Suppress("UNCHECKED_CAST")
            return it as Map<String, Any?>
        }
        enter(source, depth, source.size)
        try {
            val result = LinkedHashMap<String, Any?>(source.size)
            source.forEach { (key, nested) ->
                require(key is String) { "Info.plist dictionary key is not a string" }
                consumeString(key)
                result[key] = copy(nested, depth + 1)
            }
            return Collections.unmodifiableMap(result).also { completed[source] = it }
        } finally {
            active.remove(source)
        }
    }

    private fun copy(value: Any?, depth: Int): Any? {
        require(depth <= MAX_DEPTH) { "Info.plist exceeds copy depth" }
        return when (value) {
            null, is Boolean, is Byte, is Short, is Int, is Long,
            is Float, is Double, is BigInteger -> consumeNode().let { value }
            is String -> consumeNode().let { consumeString(value); value }
            is ByteArray -> {
                completed[value]?.let { return it }
                consumeNode()
                dataBytes = Math.addExact(dataBytes, value.size.toLong())
                require(dataBytes <= MAX_DATA_BYTES) { "Info.plist copy data limit exceeded" }
                value.copyOf().also { completed[value] = it }
            }
            is List<*> -> copyList(value, depth)
            is Map<*, *> -> copyMap(value, depth)
            else -> throw IllegalArgumentException("Unsupported Info.plist value type")
        }
    }

    private fun copyList(source: List<*>, depth: Int): List<Any?> {
        completed[source]?.let {
            @Suppress("UNCHECKED_CAST")
            return it as List<Any?>
        }
        enter(source, depth, source.size)
        try {
            return Collections.unmodifiableList(source.map { copy(it, depth + 1) })
                .also { completed[source] = it }
        } finally {
            active.remove(source)
        }
    }

    private fun enter(container: Any, depth: Int, size: Int) {
        require(depth <= MAX_DEPTH) { "Info.plist exceeds copy depth" }
        require(active.put(container, Unit) == null) { "Info.plist contains a cycle" }
        consumeNode()
        require(size <= MAX_COLLECTION_ENTRIES) { "Info.plist collection is too large" }
        edges = Math.addExact(edges, size)
        require(edges <= MAX_EDGES) { "Info.plist copy edge limit exceeded" }
    }

    private fun consumeNode() {
        nodes++
        require(nodes <= MAX_UNIQUE_NODES) { "Info.plist copy node limit exceeded" }
    }

    private fun consumeString(value: String) {
        stringChars = Math.addExact(stringChars, value.length.toLong())
        require(value.length <= MAX_STRING_CHARS && stringChars <= MAX_TOTAL_STRING_CHARS) {
            "Info.plist copy string limit exceeded"
        }
    }

    private companion object {
        const val MAX_DEPTH = 64
        const val MAX_UNIQUE_NODES = 65_536
        const val MAX_EDGES = 262_144
        const val MAX_COLLECTION_ENTRIES = 65_536
        const val MAX_STRING_CHARS = 64 * 1024
        const val MAX_TOTAL_STRING_CHARS = 4L * 1024L * 1024L
        const val MAX_DATA_BYTES = 8L * 1024L * 1024L
    }
}

private fun writeCanonicalArchive(
    files: Map<String, ByteArray>,
    directories: Set<String>,
    output: OutputStream,
    maximumBytes: Long,
): UltraProfileCanonicalArchiveMetadata {
    val sink = DigestingBoundedOutputStream(output, maximumBytes)
    ZipOutputStream(NonClosingOutputStream(sink)).use { zip ->
        canonicalEntryNames(files, directories).forEach { name ->
            val bytes = files[name] ?: ByteArray(0)
            val crc = CRC32().apply { update(bytes) }
            val entry = ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
                time = 0L
            }
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return sink.metadata()
}

private fun canonicalEntryNames(
    files: Map<String, ByteArray>,
    directories: Set<String>,
): List<String> = (files.keys + directories).sorted()

private fun normalizedDirectories(
    fileNames: Set<String>,
    declaredDirectories: Set<String>,
): Set<String> {
    val result = LinkedHashSet<String>()
    result += declaredDirectories
    (fileNames + declaredDirectories).forEach { name ->
        val node = name.removeSuffix("/")
        var separator = node.lastIndexOf('/')
        while (separator >= 0) {
            result += node.substring(0, separator + 1)
            separator = node.lastIndexOf('/', separator - 1)
        }
    }
    return Collections.unmodifiableSet(LinkedHashSet(result.sorted()))
}

private fun hasRegularFileAncestor(name: String, fileNames: Set<String>): Boolean {
    var separator = name.lastIndexOf('/')
    while (separator >= 0) {
        if (name.substring(0, separator) in fileNames) return true
        separator = name.lastIndexOf('/', separator - 1)
    }
    return false
}

private fun hasTreeCaseConflict(
    fileNames: Set<String>,
    directories: Set<String>,
): Boolean {
    val spellings = HashMap<String, Pair<String, Boolean>>()
    val nodes = fileNames.map { it to false } + directories.map { it.removeSuffix("/") to true }
    nodes.forEach { (name, isDirectory) ->
        val folded = name.lowercase(Locale.ROOT)
        val previous = spellings[folded]
        if (previous != null && (previous.first != name || previous.second != isDirectory)) {
            return true
        }
        spellings[folded] = name to isDirectory
    }
    return false
}

private class DigestingBoundedOutputStream(
    private val delegate: OutputStream,
    private val maximumBytes: Long,
) : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var sizeBytes = 0L

    override fun write(value: Int) {
        reserve(1)
        delegate.write(value)
        digest.update(value.toByte())
        sizeBytes++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        reserve(length)
        delegate.write(bytes, offset, length)
        digest.update(bytes, offset, length)
        sizeBytes += length.toLong()
    }

    override fun flush() = delegate.flush()

    fun metadata(): UltraProfileCanonicalArchiveMetadata =
        UltraProfileCanonicalArchiveMetadata(sizeBytes, UltraProfileDigest(digest.digest()))

    private fun reserve(length: Int) {
        if (length.toLong() > maximumBytes - sizeBytes) {
            throw CanonicalArchiveLimitExceededException()
        }
    }
}

private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(value: Int) = delegate.write(value)

    override fun write(bytes: ByteArray, offset: Int, length: Int) =
        delegate.write(bytes, offset, length)

    override fun flush() = delegate.flush()

    override fun close() = delegate.flush()
}

private class CanonicalArchiveLimitExceededException : IOException()

private val DISCARD_OUTPUT = object : OutputStream() {
    override fun write(value: Int) = Unit

    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
}
