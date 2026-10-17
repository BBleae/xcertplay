package com.shilapi.xcertplay

import com.shilapi.xcertplay.vehicle.ultra.profile.QuarantinedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileCanonicalArchiveMetadata
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileCandidate
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileQuarantineCode
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustVerifier
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.EnumSet

/** Stable, non-sensitive store failure classifications. */
internal enum class UltraProfileStoreCode {
    STORAGE_UNAVAILABLE,
    ATOMIC_COMMIT_UNAVAILABLE,
    UNSAFE_STORE_LAYOUT,
    INVALID_ACTIVE_POINTER,
    ACTIVE_PROFILE_MISSING,
    ACTIVE_CONTENT_MISMATCH,
    DIGEST_DIRECTORY_MISMATCH,
    PROFILE_INSPECTION_REJECTED,
    PROFILE_TRUST_REJECTED,
}

internal sealed interface UltraProfileInstallResult {
    data class Installed(val contentDigest: UltraProfileDigest) : UltraProfileInstallResult

    /** The active rename happened, but its directory durability could not be confirmed. */
    data class CommittedButDurabilityUncertain(
        val contentDigest: UltraProfileDigest,
        val code: UltraProfileStoreCode = UltraProfileStoreCode.STORAGE_UNAVAILABLE,
    ) : UltraProfileInstallResult

    data class Failed(val code: UltraProfileStoreCode) : UltraProfileInstallResult
}

internal sealed interface UltraProfileLoadResult {
    /** No active pointer has ever been committed. */
    object Inactive : UltraProfileLoadResult

    data class Loaded(val profile: VerifiedUltraProfile) : UltraProfileLoadResult

    /** The active profile is unusable and must never be advertised or opened by a session. */
    data class Quarantined(
        val code: UltraProfileStoreCode,
        val packageCode: UltraProfileQuarantineCode? = null,
    ) : UltraProfileLoadResult
}

internal enum class UltraProfileStoreStep {
    WRITE_ARCHIVE,
    FORCE_ARCHIVE_FILE,
    MOVE_ARCHIVE,
    FORCE_STAGING_DIRECTORY,
    PUBLISH_DIGEST_DIRECTORY,
    FORCE_DIGEST_DIRECTORY,
    FORCE_PROFILES_DIRECTORY,
    WRITE_ACTIVE_POINTER,
    FORCE_ACTIVE_POINTER_FILE,
    MOVE_ACTIVE_POINTER,
    FORCE_ROOT_DIRECTORY,
    FORCE_ROOT_PARENT_AFTER_CREATE,
    FORCE_ROOT_PARENT_AFTER_COMMIT,
}

internal fun interface UltraProfileStoreFaultInjector {
    @Throws(IOException::class)
    fun before(step: UltraProfileStoreStep)
}

/**
 * App-private persistence for an already verified Ultra profile package.
 *
 * Only a canonical repackaging of inspected allowlisted files is persisted. Raw ZIP metadata,
 * trust proofs, and decoded certificate/profile material are deliberately never written
 * separately. A digest pointer becomes visible only via an atomic same-filesystem rename after
 * the addressed archive is durable and re-readable.
 */
internal class UltraProfileStore(
    rootDirectory: Path,
    private val inspector: UltraProfilePackageInspector = UltraProfilePackageInspector(),
) {
    private val root = rootDirectory.toAbsolutePath().normalize()
    private var faultInjector: UltraProfileStoreFaultInjector = NO_FAULTS

    init {
        require(root.parent != null) { "Ultra profile store requires a private child directory" }
    }

    internal constructor(
        rootDirectory: Path,
        inspector: UltraProfilePackageInspector,
        faultInjector: UltraProfileStoreFaultInjector,
    ) : this(rootDirectory, inspector) {
        this.faultInjector = faultInjector
    }

    /**
     * Installs [profile]'s canonical archive and atomically switches the active digest pointer.
     * Published digest directories are immutable. A failed write or rename leaves both the
     * previously committed pointer and its addressed directory untouched.
     */
    @Synchronized
    fun install(profile: VerifiedUltraProfile): UltraProfileInstallResult {
        val digest = profile.candidate.contentDigest
        val digestHex = digest.hex()
        val canonicalMetadata = profile.candidate.canonicalArchiveMetadata

        if (!inspector.acceptsCanonicalArchive(canonicalMetadata)) {
            return UltraProfileInstallResult.Failed(
                UltraProfileStoreCode.PROFILE_INSPECTION_REJECTED,
            )
        }

        return try {
            val profiles = prepareStoreLayout()
            val digestDirectory = profiles.resolve(digestHex).normalize()
            if (digestDirectory.parent != profiles || !DIGEST_PATTERN.matches(digestHex)) {
                throw UnsafeStoreLayoutException()
            }
            ensureImmutableDigestDirectory(
                digestDirectory = digestDirectory,
                contentDigest = digest,
                candidate = profile.candidate,
                canonicalMetadata = canonicalMetadata,
            )

            val activePointer = root.resolve(ACTIVE_POINTER_FILE_NAME)
            rejectUnsafeExistingFile(activePointer)
            commitActivePointer(
                destination = activePointer,
                content = "$digestHex\n".toByteArray(StandardCharsets.US_ASCII),
            )
            UltraProfileInstallResult.Installed(digest)
        } catch (_: ActiveCommitDurabilityUncertainException) {
            UltraProfileInstallResult.CommittedButDurabilityUncertain(digest)
        } catch (_: AtomicMoveNotSupportedException) {
            UltraProfileInstallResult.Failed(UltraProfileStoreCode.ATOMIC_COMMIT_UNAVAILABLE)
        } catch (_: UnsafeStoreLayoutException) {
            UltraProfileInstallResult.Failed(UltraProfileStoreCode.UNSAFE_STORE_LAYOUT)
        } catch (_: DigestDirectoryMismatchException) {
            UltraProfileInstallResult.Failed(UltraProfileStoreCode.DIGEST_DIRECTORY_MISMATCH)
        } catch (_: SecurityException) {
            UltraProfileInstallResult.Failed(UltraProfileStoreCode.STORAGE_UNAVAILABLE)
        } catch (_: UnsupportedOperationException) {
            UltraProfileInstallResult.Failed(UltraProfileStoreCode.STORAGE_UNAVAILABLE)
        } catch (_: IOException) {
            UltraProfileInstallResult.Failed(UltraProfileStoreCode.STORAGE_UNAVAILABLE)
        }
    }

    /**
     * Re-opens, re-inspects, and re-verifies the active archive for this call's exact binding.
     * No persisted object is treated as a capability. Any path, pointer, or digest inconsistency
     * returns [UltraProfileLoadResult.Quarantined].
     */
    @Synchronized
    fun loadActive(
        expectedBinding: UltraProfileTrustBinding,
        verifier: UltraProfileTrustVerifier,
    ): UltraProfileLoadResult {
        return try {
            val rootAttributes = attributesOrNull(root) ?: return UltraProfileLoadResult.Inactive
            requireDirectory(root, rootAttributes)

            val pointerPath = root.resolve(ACTIVE_POINTER_FILE_NAME)
            val pointerAttributes = attributesOrNull(pointerPath)
                ?: return UltraProfileLoadResult.Inactive
            requireRegularFile(pointerPath, pointerAttributes)
            val pointerBytes = readSmallFileNoFollow(pointerPath, MAXIMUM_POINTER_BYTES)
            val digestHex = parsePointer(pointerBytes)
                ?: return quarantine(UltraProfileStoreCode.INVALID_ACTIVE_POINTER)

            if (expectedBinding.contentDigest.hex() != digestHex) {
                return quarantine(UltraProfileStoreCode.ACTIVE_CONTENT_MISMATCH)
            }

            val profiles = root.resolve(PROFILES_DIRECTORY_NAME)
            val profilesAttributes = attributesOrNull(profiles)
                ?: return quarantine(UltraProfileStoreCode.ACTIVE_PROFILE_MISSING)
            requireDirectory(profiles, profilesAttributes)

            val digestDirectory = profiles.resolve(digestHex).normalize()
            if (digestDirectory.parent != profiles || digestDirectory.fileName.toString() != digestHex) {
                return quarantine(UltraProfileStoreCode.INVALID_ACTIVE_POINTER)
            }
            val digestDirectoryAttributes = attributesOrNull(digestDirectory)
                ?: return quarantine(UltraProfileStoreCode.ACTIVE_PROFILE_MISSING)
            requireDirectory(digestDirectory, digestDirectoryAttributes)
            requireOnlyArchiveEntry(digestDirectory)

            val archivePath = digestDirectory.resolve(ARCHIVE_FILE_NAME)
            val archiveAttributes = attributesOrNull(archivePath)
                ?: return quarantine(UltraProfileStoreCode.ACTIVE_PROFILE_MISSING)
            requireRegularFile(archivePath, archiveAttributes)

            val inspection = inspectCanonicalArchive(archivePath)
            revalidateDirectory(profiles)
            revalidateDirectory(digestDirectory)
            revalidateRegularFile(archivePath, archiveAttributes)

            val candidate = when (inspection) {
                is UltraProfileInspectionResult.Candidate -> inspection.profile
                is UltraProfileInspectionResult.Quarantined -> {
                    return quarantine(
                        UltraProfileStoreCode.PROFILE_INSPECTION_REJECTED,
                        inspection.quarantine,
                    )
                }
            }
            if (candidate.contentDigest.hex() != digestHex) {
                return quarantine(UltraProfileStoreCode.ACTIVE_CONTENT_MISMATCH)
            }
            if (candidate.archiveDigest != candidate.canonicalArchiveMetadata.digest) {
                return quarantine(UltraProfileStoreCode.ACTIVE_CONTENT_MISMATCH)
            }

            when (val verification = inspector.verify(candidate, expectedBinding, verifier)) {
                is UltraProfileVerificationResult.Verified -> {
                    UltraProfileLoadResult.Loaded(verification.profile)
                }
                is UltraProfileVerificationResult.Quarantined -> {
                    quarantine(
                        UltraProfileStoreCode.PROFILE_TRUST_REJECTED,
                        verification.quarantine,
                    )
                }
            }
        } catch (_: UnsafeStoreLayoutException) {
            quarantine(UltraProfileStoreCode.UNSAFE_STORE_LAYOUT)
        } catch (_: DigestDirectoryMismatchException) {
            quarantine(UltraProfileStoreCode.ACTIVE_CONTENT_MISMATCH)
        } catch (_: SecurityException) {
            quarantine(UltraProfileStoreCode.STORAGE_UNAVAILABLE)
        } catch (_: UnsupportedOperationException) {
            quarantine(UltraProfileStoreCode.STORAGE_UNAVAILABLE)
        } catch (_: IOException) {
            quarantine(UltraProfileStoreCode.STORAGE_UNAVAILABLE)
        }
    }

    private fun prepareStoreLayout(): Path {
        if (attributesOrNull(root) == null) {
            val parent = root.parent ?: throw UnsafeStoreLayoutException()
            val parentAttributes = attributesOrNull(parent) ?: throw UnsafeStoreLayoutException()
            requireDirectory(parent, parentAttributes)
            Files.createDirectory(root)
            requireDirectory(root, readAttributes(root))
            forceDirectory(parent, UltraProfileStoreStep.FORCE_ROOT_PARENT_AFTER_CREATE)
        }
        requireDirectory(root, readAttributes(root))
        bestEffortRestrictDirectory(root)

        val profiles = root.resolve(PROFILES_DIRECTORY_NAME)
        ensureDirectory(profiles)
        return profiles
    }

    private fun ensureDirectory(path: Path) {
        val existing = attributesOrNull(path)
        if (existing == null) {
            Files.createDirectory(path)
        } else {
            requireDirectory(path, existing)
        }
        requireDirectory(path, readAttributes(path))
        bestEffortRestrictDirectory(path)
    }

    private fun ensureImmutableDigestDirectory(
        digestDirectory: Path,
        contentDigest: UltraProfileDigest,
        candidate: UltraProfileCandidate,
        canonicalMetadata: UltraProfileCanonicalArchiveMetadata,
    ) {
        val existing = attributesOrNull(digestDirectory)
        if (existing != null) {
            requireDirectory(digestDirectory, existing)
            validateDigestDirectory(digestDirectory, contentDigest, canonicalMetadata)
            forceDirectory(
                digestDirectory,
                UltraProfileStoreStep.FORCE_DIGEST_DIRECTORY,
            )
            forceDirectory(
                digestDirectory.parent ?: throw UnsafeStoreLayoutException(),
                UltraProfileStoreStep.FORCE_PROFILES_DIRECTORY,
            )
            return
        }

        val profiles = digestDirectory.parent ?: throw UnsafeStoreLayoutException()
        requireDirectory(profiles, readAttributes(profiles))
        var stagingDirectory: Path? = null
        try {
            stagingDirectory = Files.createTempDirectory(profiles, STAGING_DIRECTORY_PREFIX)
            requireDirectory(stagingDirectory, readAttributes(stagingDirectory))
            bestEffortRestrictDirectory(stagingDirectory)

            val stagingArchive = stagingDirectory.resolve(ARCHIVE_FILE_NAME)
            val writtenMetadata = writeCanonicalArchive(stagingArchive, candidate)
            if (writtenMetadata != canonicalMetadata) throw DigestDirectoryMismatchException()
            validateDigestDirectory(stagingDirectory, contentDigest, canonicalMetadata)
            forceDirectory(
                stagingDirectory,
                UltraProfileStoreStep.FORCE_STAGING_DIRECTORY,
            )

            faultInjector.before(UltraProfileStoreStep.PUBLISH_DIGEST_DIRECTORY)
            try {
                Files.move(
                    stagingDirectory,
                    digestDirectory,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                stagingDirectory = null
            } catch (_: FileAlreadyExistsException) {
                // A concurrent installer may have published the same immutable digest. It is
                // acceptable only if its canonical bytes independently validate as identical.
                validateDigestDirectory(digestDirectory, contentDigest, canonicalMetadata)
            } catch (failure: FileSystemException) {
                // ATOMIC_MOVE reports an existing non-empty target with provider-specific
                // subclasses (for example DirectoryNotEmptyException on Linux). Reuse only an
                // independently validated immutable winner; otherwise preserve the real failure.
                if (attributesOrNull(digestDirectory) == null) throw failure
                validateDigestDirectory(digestDirectory, contentDigest, canonicalMetadata)
            }
            requireDirectory(digestDirectory, readAttributes(digestDirectory))
            validateDigestDirectory(digestDirectory, contentDigest, canonicalMetadata)
            forceDirectory(
                digestDirectory,
                UltraProfileStoreStep.FORCE_DIGEST_DIRECTORY,
            )
            forceDirectory(profiles, UltraProfileStoreStep.FORCE_PROFILES_DIRECTORY)
        } finally {
            stagingDirectory?.let(::bestEffortDeleteStagingDirectory)
        }
    }

    private fun validateDigestDirectory(
        directory: Path,
        expectedDigest: UltraProfileDigest,
        expectedCanonicalArchive: UltraProfileCanonicalArchiveMetadata,
    ) {
        if (directory.fileName.toString() != expectedDigest.hex() &&
            !directory.fileName.toString().startsWith(STAGING_DIRECTORY_PREFIX)
        ) {
            throw DigestDirectoryMismatchException()
        }
        requireDirectory(directory, readAttributes(directory))
        requireOnlyArchiveEntry(directory)

        val archivePath = directory.resolve(ARCHIVE_FILE_NAME)
        val archiveAttributes = readAttributes(archivePath)
        requireRegularFile(archivePath, archiveAttributes)
        if (archiveAttributes.size() != expectedCanonicalArchive.sizeBytes ||
            fingerprintArchive(archivePath, archiveAttributes) != expectedCanonicalArchive
        ) {
            throw DigestDirectoryMismatchException()
        }
        revalidateRegularFile(archivePath, archiveAttributes)
        revalidateDirectory(directory)
    }

    private fun requireOnlyArchiveEntry(directory: Path) {
        val names = ArrayList<String>(2)
        Files.newDirectoryStream(directory).use { entries ->
            entries.forEach { entry -> names += entry.fileName.toString() }
        }
        if (names != listOf(ARCHIVE_FILE_NAME)) throw DigestDirectoryMismatchException()
    }

    private fun bestEffortDeleteStagingDirectory(directory: Path) {
        try {
            val archive = directory.resolve(ARCHIVE_FILE_NAME)
            if (attributesOrNull(archive)?.let { it.isRegularFile && !it.isSymbolicLink } == true) {
                Files.deleteIfExists(archive)
            }
            Files.deleteIfExists(directory)
        } catch (_: IOException) {
            // A hidden, unreferenced staging directory can never become active.
        } catch (_: SecurityException) {
            // Cleanup failure must not affect the previously committed active pointer.
        }
    }

    private fun rejectUnsafeExistingFile(path: Path) {
        val attributes = attributesOrNull(path) ?: return
        requireRegularFile(path, attributes)
    }

    private fun writeCanonicalArchive(
        destination: Path,
        candidate: UltraProfileCandidate,
    ): UltraProfileCanonicalArchiveMetadata {
        val directory = destination.parent ?: throw UnsafeStoreLayoutException()
        requireDirectory(directory, readAttributes(directory))
        var temporary: Path? = null
        try {
            temporary = Files.createTempFile(directory, TEMPORARY_FILE_PREFIX, TEMPORARY_FILE_SUFFIX)
            requireRegularFile(temporary, readAttributes(temporary))
            bestEffortRestrictFile(temporary)
            faultInjector.before(UltraProfileStoreStep.WRITE_ARCHIVE)
            val metadata = FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val written = candidate.writeCanonicalArchive(Channels.newOutputStream(channel))
                faultInjector.before(UltraProfileStoreStep.FORCE_ARCHIVE_FILE)
                channel.force(true)
                written
            }
            requireRegularFile(temporary, readAttributes(temporary))
            faultInjector.before(UltraProfileStoreStep.MOVE_ARCHIVE)
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            temporary = null
            requireRegularFile(destination, readAttributes(destination))
            bestEffortRestrictFile(destination)
            return metadata
        } finally {
            temporary?.let { candidate ->
                try {
                    Files.deleteIfExists(candidate)
                } catch (_: IOException) {
                    // A private, unreferenced temporary file is inert and may be cleaned later.
                } catch (_: SecurityException) {
                    // Cleanup failure must not risk changing the active pointer.
                }
            }
        }
    }

    private fun commitActivePointer(destination: Path, content: ByteArray) {
        val directory = destination.parent ?: throw UnsafeStoreLayoutException()
        requireDirectory(directory, readAttributes(directory))
        var temporary: Path? = null
        var committed = false
        try {
            temporary = Files.createTempFile(directory, TEMPORARY_FILE_PREFIX, TEMPORARY_FILE_SUFFIX)
            requireRegularFile(temporary, readAttributes(temporary))
            bestEffortRestrictFile(temporary)
            faultInjector.before(UltraProfileStoreStep.WRITE_ACTIVE_POINTER)
            FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                var offset = 0
                while (offset < content.size) {
                    offset += channel.write(
                        java.nio.ByteBuffer.wrap(content, offset, content.size - offset),
                    )
                }
                faultInjector.before(UltraProfileStoreStep.FORCE_ACTIVE_POINTER_FILE)
                channel.force(true)
            }
            requireRegularFile(temporary, readAttributes(temporary))
            faultInjector.before(UltraProfileStoreStep.MOVE_ACTIVE_POINTER)
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            committed = true
            temporary = null
            requireRegularFile(destination, readAttributes(destination))
            bestEffortRestrictFile(destination)
            forceDirectory(directory, UltraProfileStoreStep.FORCE_ROOT_DIRECTORY)
            forceDirectory(
                directory.parent ?: throw UnsafeStoreLayoutException(),
                UltraProfileStoreStep.FORCE_ROOT_PARENT_AFTER_COMMIT,
            )
        } catch (failure: IOException) {
            if (committed) throw ActiveCommitDurabilityUncertainException(failure)
            throw failure
        } catch (failure: SecurityException) {
            if (committed) throw ActiveCommitDurabilityUncertainException(failure)
            throw failure
        } catch (failure: UnsupportedOperationException) {
            if (committed) throw ActiveCommitDurabilityUncertainException(failure)
            throw failure
        } finally {
            temporary?.let { candidate ->
                try {
                    Files.deleteIfExists(candidate)
                } catch (_: IOException) {
                    // A private, unreferenced temporary file is inert and may be cleaned later.
                } catch (_: SecurityException) {
                    // Cleanup failure happens before the active linearization point.
                }
            }
        }
    }

    private fun inspectCanonicalArchive(path: Path): UltraProfileInspectionResult {
        val attributes = readAttributes(path)
        requireRegularFile(path, attributes)
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        val result = Files.newByteChannel(path, options).use { channel ->
            Channels.newInputStream(channel).use(inspector::inspectCanonical)
        }
        revalidateRegularFile(path, attributes)
        return result
    }

    /** Hashes the stored canonical file without allocating or decompressing package contents. */
    private fun fingerprintArchive(
        path: Path,
        before: BasicFileAttributes,
    ): UltraProfileCanonicalArchiveMetadata {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        Files.newByteChannel(path, options).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(DEFAULT_STREAM_BUFFER_BYTES)
            while (true) {
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total = Math.addExact(total, read.toLong())
                digest.update(buffer.array(), 0, read)
                buffer.clear()
            }
        }
        revalidateRegularFile(path, before)
        return UltraProfileCanonicalArchiveMetadata(total, UltraProfileDigest(digest.digest()))
    }

    private fun readSmallFileNoFollow(path: Path, maximumBytes: Int): ByteArray {
        val before = readAttributes(path)
        requireRegularFile(path, before)
        if (before.size() > maximumBytes.toLong()) return ByteArray(maximumBytes + 1)
        val output = ByteArrayOutputStream(minOf(before.size().toInt(), maximumBytes))
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        Files.newByteChannel(path, options).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(128)
            while (true) {
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (output.size() + read > maximumBytes) return ByteArray(maximumBytes + 1)
                output.write(buffer.array(), 0, read)
                buffer.clear()
            }
        }
        revalidateRegularFile(path, before)
        return output.toByteArray()
    }

    private fun parsePointer(bytes: ByteArray): String? {
        if (bytes.size != POINTER_BYTES) return null
        if (bytes.last() != '\n'.code.toByte()) return null
        for (index in 0 until DIGEST_HEX_CHARS) {
            val value = bytes[index].toInt() and 0xff
            if (value !in '0'.code..'9'.code && value !in 'a'.code..'f'.code) return null
        }
        return String(bytes, 0, DIGEST_HEX_CHARS, StandardCharsets.US_ASCII)
    }

    private fun revalidateDirectory(path: Path) {
        requireDirectory(path, readAttributes(path))
    }

    private fun revalidateRegularFile(path: Path, before: BasicFileAttributes) {
        val after = readAttributes(path)
        requireRegularFile(path, after)
        val beforeKey = before.fileKey()
        val afterKey = after.fileKey()
        if ((beforeKey != null && afterKey != null && beforeKey != afterKey) ||
            before.size() != after.size() ||
            before.lastModifiedTime() != after.lastModifiedTime()
        ) {
            throw UnsafeStoreLayoutException()
        }
    }

    private fun requireDirectory(path: Path, attributes: BasicFileAttributes) {
        if (attributes.isSymbolicLink || !attributes.isDirectory || Files.isSymbolicLink(path)) {
            throw UnsafeStoreLayoutException()
        }
    }

    private fun requireRegularFile(path: Path, attributes: BasicFileAttributes) {
        if (attributes.isSymbolicLink || !attributes.isRegularFile || Files.isSymbolicLink(path)) {
            throw UnsafeStoreLayoutException()
        }
    }

    private fun attributesOrNull(path: Path): BasicFileAttributes? = try {
        readAttributes(path)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun readAttributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    private fun bestEffortRestrictDirectory(path: Path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_DIRECTORY_PERMISSIONS)
            return
        } catch (_: UnsupportedOperationException) {
            // Fall through to java.io.File for non-POSIX app-private storage.
        } catch (_: IOException) {
            // Best effort only.
        } catch (_: SecurityException) {
            // Best effort only.
        }
        try {
            val file = path.toFile()
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
            file.setExecutable(true, true)
        } catch (_: RuntimeException) {
            // Permission hardening must not turn a valid atomic commit into a partial one.
        }
    }

    private fun bestEffortRestrictFile(path: Path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_FILE_PERMISSIONS)
            return
        } catch (_: UnsupportedOperationException) {
            // Fall through to java.io.File for non-POSIX app-private storage.
        } catch (_: IOException) {
            // Best effort only.
        } catch (_: SecurityException) {
            // Best effort only.
        }
        try {
            val file = path.toFile()
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        } catch (_: RuntimeException) {
            // Best effort only.
        }
    }

    private fun forceDirectory(path: Path, step: UltraProfileStoreStep) {
        requireDirectory(path, readAttributes(path))
        faultInjector.before(step)
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        requireDirectory(path, readAttributes(path))
    }

    private fun quarantine(
        code: UltraProfileStoreCode,
        packageQuarantine: QuarantinedUltraProfile? = null,
    ) = UltraProfileLoadResult.Quarantined(code, packageQuarantine?.code)

    private class UnsafeStoreLayoutException : IOException()
    private class DigestDirectoryMismatchException : IOException()
    private class ActiveCommitDurabilityUncertainException(cause: Throwable) : IOException(cause)

    internal companion object {
        const val PROFILES_DIRECTORY_NAME = "profiles"
        const val ARCHIVE_FILE_NAME = "profile.zip"
        const val ACTIVE_POINTER_FILE_NAME = "active"

        private const val TEMPORARY_FILE_PREFIX = ".ultra-profile-"
        private const val TEMPORARY_FILE_SUFFIX = ".tmp"
        private const val STAGING_DIRECTORY_PREFIX = ".ultra-profile-stage-"
        private const val DIGEST_HEX_CHARS = 64
        private const val POINTER_BYTES = DIGEST_HEX_CHARS + 1
        private const val MAXIMUM_POINTER_BYTES = POINTER_BYTES
        private const val DEFAULT_STREAM_BUFFER_BYTES = 32 * 1024
        private val DIGEST_PATTERN = Regex("[0-9a-f]{$DIGEST_HEX_CHARS}")
        private val OWNER_DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        private val OWNER_FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        private val NO_FAULTS = UltraProfileStoreFaultInjector { }
    }
}
