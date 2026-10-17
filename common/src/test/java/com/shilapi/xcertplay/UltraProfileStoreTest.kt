package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileCandidate
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageLimits
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileQuarantineCode
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustVerifier
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TEST_PHONE_ASSET_FILE = "CarPlayPhone.assets/layout.bin"

class UltraProfileStoreTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installStoresCanonicalArchiveByContentDigestAndLoadRevalidatesIt() {
        val root = newRoot()
        val fixture = fixture("v1", proof = "proof-must-not-be-persisted".encodeToByteArray())
        val expectedArchive = canonicalBytes(fixture.profile.candidate)
        assertFalse(expectedArchive.contentEquals(fixture.archive))
        fixture.archive.fill(0)

        val store = UltraProfileStore(root)
        val installed = store.install(fixture.profile) as UltraProfileInstallResult.Installed
        assertEquals(fixture.binding.contentDigest, installed.contentDigest)

        val digestHex = fixture.binding.contentDigest.hex()
        val archivePath = archivePath(root, digestHex)
        assertArrayEquals(expectedArchive, Files.readAllBytes(archivePath))
        assertEquals(
            "$digestHex\n",
            String(Files.readAllBytes(activePath(root)), StandardCharsets.US_ASCII),
        )

        var verifierCalls = 0
        val loaded = store.loadActive(fixture.binding) { request ->
            verifierCalls++
            assertEquals(fixture.binding, request.expectedBinding)
            UltraProfileTrustResult.Trusted(
                UltraProfileTrustAttestation(
                    fixture.binding,
                    "fresh-load-proof".encodeToByteArray(),
                ),
            )
        } as UltraProfileLoadResult.Loaded

        assertEquals(1, verifierCalls)
        assertNotSame(fixture.profile, loaded.profile)
        assertArrayEquals(expectedArchive, canonicalBytes(loaded.profile.candidate))
        assertArrayEquals(
            "phone-assets-v1".encodeToByteArray(),
            loaded.profile.candidate.content(TEST_PHONE_ASSET_FILE),
        )

        val persisted = mutableListOf<ByteArray>()
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .forEach { persisted += Files.readAllBytes(it) }
        }
        assertFalse(
            persisted.any { bytes ->
                bytes.containsSubsequence("proof-must-not-be-persisted".encodeToByteArray()) ||
                    bytes.containsSubsequence("fresh-load-proof".encodeToByteArray())
            },
        )
    }

    @Test
    fun existingDigestDirectoryIsValidatedAndNeverOverwritten() {
        val root = newRoot()
        val fixture = fixture("immutable")
        val store = UltraProfileStore(root)
        store.install(fixture.profile).installed()
        val archivePath = archivePath(root, fixture.binding.contentDigest.hex())
        val archiveBefore = Files.readAllBytes(archivePath)
        val pointerBefore = Files.readAllBytes(activePath(root))

        // Reinstalling an existing digest must reuse it without entering an archive write path.
        failingStore(root, UltraProfileStoreStep.WRITE_ARCHIVE)
            .install(fixture.profile)
            .installed()
        assertArrayEquals(archiveBefore, Files.readAllBytes(archivePath))

        // Even a later active-pointer commit failure cannot mutate the reused digest directory.
        assertEquals(
            UltraProfileStoreCode.STORAGE_UNAVAILABLE,
            failingStore(root, UltraProfileStoreStep.MOVE_ACTIVE_POINTER)
                .install(fixture.profile)
                .failed()
                .code,
        )
        assertArrayEquals(archiveBefore, Files.readAllBytes(archivePath))
        assertArrayEquals(pointerBefore, Files.readAllBytes(activePath(root)))
    }

    @Test
    fun updateSwitchesDigestWhileExistingSessionRemainsAnImmutableSnapshot() {
        val root = newRoot()
        val first = fixture("v1")
        val second = fixture("v2")
        val store = UltraProfileStore(root)

        store.install(first.profile).installed()
        val firstSession = store.loadActive(first.binding, verifierFor(first.binding)).loaded()
        store.install(second.profile).installed()
        val secondSession = store.loadActive(second.binding, verifierFor(second.binding)).loaded()

        assertArrayEquals(
            "phone-assets-v1".encodeToByteArray(),
            firstSession.candidate.content(TEST_PHONE_ASSET_FILE),
        )
        assertArrayEquals(
            "phone-assets-v2".encodeToByteArray(),
            secondSession.candidate.content(TEST_PHONE_ASSET_FILE),
        )

        // Replacement on disk cannot mutate either already-returned capability object.
        Files.write(
            archivePath(root, second.binding.contentDigest.hex()),
            canonicalBytes(first.profile.candidate),
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        assertArrayEquals(
            "phone-assets-v2".encodeToByteArray(),
            secondSession.candidate.content(TEST_PHONE_ASSET_FILE),
        )
        assertEquals(
            UltraProfileStoreCode.ACTIVE_CONTENT_MISMATCH,
            store.loadActive(second.binding, verifierFor(second.binding)).quarantined().code,
        )
    }

    @Test
    fun archiveWriteAndPointerMoveFailuresBothPreservePreviousActiveProfile() {
        val root = newRoot()
        val first = fixture("v1")
        val writeFailureUpdate = fixture("write-failure")
        val publishFailureUpdate = fixture("publish-failure")
        val moveFailureUpdate = fixture("move-failure")
        val ordinaryStore = UltraProfileStore(root)
        ordinaryStore.install(first.profile).installed()
        val originalPointer = Files.readAllBytes(activePath(root))

        val writeFailureStore = failingStore(root, UltraProfileStoreStep.WRITE_ARCHIVE)
        assertEquals(
            UltraProfileStoreCode.STORAGE_UNAVAILABLE,
            writeFailureStore.install(writeFailureUpdate.profile).failed().code,
        )
        assertArrayEquals(originalPointer, Files.readAllBytes(activePath(root)))
        ordinaryStore.loadActive(first.binding, verifierFor(first.binding)).loaded()

        val publishFailureStore = failingStore(
            root,
            UltraProfileStoreStep.PUBLISH_DIGEST_DIRECTORY,
        )
        assertEquals(
            UltraProfileStoreCode.STORAGE_UNAVAILABLE,
            publishFailureStore.install(publishFailureUpdate.profile).failed().code,
        )
        assertArrayEquals(originalPointer, Files.readAllBytes(activePath(root)))
        assertFalse(
            Files.exists(
                root.resolve(UltraProfileStore.PROFILES_DIRECTORY_NAME)
                    .resolve(publishFailureUpdate.binding.contentDigest.hex()),
            ),
        )
        ordinaryStore.loadActive(first.binding, verifierFor(first.binding)).loaded()

        val moveFailureStore = failingStore(root, UltraProfileStoreStep.MOVE_ACTIVE_POINTER)
        assertEquals(
            UltraProfileStoreCode.STORAGE_UNAVAILABLE,
            moveFailureStore.install(moveFailureUpdate.profile).failed().code,
        )
        assertArrayEquals(originalPointer, Files.readAllBytes(activePath(root)))
        ordinaryStore.loadActive(first.binding, verifierFor(first.binding)).loaded()
    }

    @Test
    fun everyPreCommitFileAndDirectoryDurabilityFailurePreservesPreviousPointer() {
        val root = newRoot()
        val first = fixture("durable-v1")
        val ordinaryStore = UltraProfileStore(root)
        ordinaryStore.install(first.profile).installed()
        val originalPointer = Files.readAllBytes(activePath(root))
        val preCommitFailures = listOf(
            UltraProfileStoreStep.FORCE_ARCHIVE_FILE,
            UltraProfileStoreStep.FORCE_STAGING_DIRECTORY,
            UltraProfileStoreStep.FORCE_DIGEST_DIRECTORY,
            UltraProfileStoreStep.FORCE_PROFILES_DIRECTORY,
            UltraProfileStoreStep.FORCE_ACTIVE_POINTER_FILE,
        )

        preCommitFailures.forEachIndexed { index, step ->
            val update = fixture("durability-failure-$index")
            assertEquals(
                "step=$step",
                UltraProfileStoreCode.STORAGE_UNAVAILABLE,
                failingStore(root, step).install(update.profile).failed().code,
            )
            assertArrayEquals("step=$step", originalPointer, Files.readAllBytes(activePath(root)))
            ordinaryStore.loadActive(first.binding, verifierFor(first.binding)).loaded()
        }
    }

    @Test
    fun rootFsyncFailureAfterActiveRenameReportsCommittedButDurabilityUncertain() {
        val root = newRoot()
        val first = fixture("uncertain-v1")
        val second = fixture("uncertain-v2")
        val ordinaryStore = UltraProfileStore(root)
        ordinaryStore.install(first.profile).installed()

        val result = failingStore(root, UltraProfileStoreStep.FORCE_ROOT_DIRECTORY)
            .install(second.profile)
            .committedUncertain()
        assertEquals(second.binding.contentDigest, result.contentDigest)
        assertEquals(
            second.binding.contentDigest.hex() + "\n",
            String(Files.readAllBytes(activePath(root)), StandardCharsets.US_ASCII),
        )
        ordinaryStore.loadActive(second.binding, verifierFor(second.binding)).loaded()
    }

    @Test
    fun firstInstallParentFsyncFailureIsPreCommitAndRetryCanRecover() {
        val root = newAbsentRoot()
        val fixture = fixture("new-root-precommit")
        val failing = failingStore(
            root,
            UltraProfileStoreStep.FORCE_ROOT_PARENT_AFTER_CREATE,
        )

        assertEquals(
            UltraProfileStoreCode.STORAGE_UNAVAILABLE,
            failing.install(fixture.profile).failed().code,
        )
        assertTrue(Files.isDirectory(root))
        assertFalse(Files.exists(activePath(root)))

        // The root now exists, so a retry no longer enters the failed creation publication step.
        failing.install(fixture.profile).installed()
        failing.loadActive(fixture.binding, verifierFor(fixture.binding)).loaded()
    }

    @Test
    fun postCommitParentFsyncFailureOnFirstInstallReportsUncertainAndCanLoad() {
        val root = newAbsentRoot()
        val fixture = fixture("new-root-postcommit")
        val store = failingStore(
            root,
            UltraProfileStoreStep.FORCE_ROOT_PARENT_AFTER_COMMIT,
        )

        val result = store.install(fixture.profile).committedUncertain()
        assertEquals(fixture.binding.contentDigest, result.contentDigest)
        assertEquals(
            fixture.binding.contentDigest.hex() + "\n",
            String(Files.readAllBytes(activePath(root)), StandardCharsets.US_ASCII),
        )
        UltraProfileStore(root)
            .loadActive(fixture.binding, verifierFor(fixture.binding))
            .loaded()
    }

    @Test
    fun persistedCanonicalArchiveUsesIndependentLargerInspectionLimit() {
        val root = newRoot()
        val fixture = fixture(
            "canonical-boundary",
            phoneAssets = ByteArray(16 * 1024) { (it % 251).toByte() },
        )
        val rawSize = fixture.archive.size.toLong()
        val canonicalSize = fixture.profile.candidate.canonicalArchiveMetadata.sizeBytes
        assertTrue(canonicalSize > rawSize)
        val limits = UltraProfilePackageLimits(
            maximumArchiveBytes = rawSize,
            maximumCanonicalArchiveBytes = canonicalSize,
            maximumTotalContentBytes = 1024 * 1024,
            maximumFileBytes = 512 * 1024,
            maximumInfoPlistBytes = 256 * 1024,
        )
        val store = UltraProfileStore(root, UltraProfilePackageInspector(limits))

        store.install(fixture.profile).installed()
        store.loadActive(fixture.binding, verifierFor(fixture.binding)).loaded()
    }

    @Test
    fun malformedPointerCorruptArchiveAndDigestMismatchAreQuarantined() {
        val root = newRoot()
        val first = fixture("v1")
        val second = fixture("v2")
        val store = UltraProfileStore(root)
        store.install(first.profile).installed()

        val invalidPointers = listOf(
            first.binding.contentDigest.hex(),
            first.binding.contentDigest.hex().uppercase() + "\n",
            first.binding.contentDigest.hex() + "\n\n",
            "../${first.binding.contentDigest.hex()}\n",
        )
        invalidPointers.forEach { pointer ->
            Files.write(activePath(root), pointer.toByteArray(StandardCharsets.US_ASCII))
            assertEquals(
                UltraProfileStoreCode.INVALID_ACTIVE_POINTER,
                store.loadActive(first.binding, verifierFor(first.binding)).quarantined().code,
            )
        }

        Files.write(
            activePath(root),
            (first.binding.contentDigest.hex() + "\n").toByteArray(StandardCharsets.US_ASCII),
        )
        val pointerBeforeCorruption = Files.readAllBytes(activePath(root))
        Files.write(
            archivePath(root, first.binding.contentDigest.hex()),
            "not-a-profile".encodeToByteArray(),
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        val corrupt = store.loadActive(first.binding, verifierFor(first.binding)).quarantined()
        assertEquals(UltraProfileStoreCode.PROFILE_INSPECTION_REJECTED, corrupt.code)
        val corruptedBytes = Files.readAllBytes(archivePath(root, first.binding.contentDigest.hex()))
        assertEquals(
            UltraProfileStoreCode.DIGEST_DIRECTORY_MISMATCH,
            store.install(first.profile).failed().code,
        )
        assertArrayEquals(pointerBeforeCorruption, Files.readAllBytes(activePath(root)))
        assertArrayEquals(
            corruptedBytes,
            Files.readAllBytes(archivePath(root, first.binding.contentDigest.hex())),
        )

        val mismatchRoot = newRoot()
        val mismatchStore = UltraProfileStore(mismatchRoot)
        mismatchStore.install(first.profile).installed()
        Files.write(
            archivePath(mismatchRoot, first.binding.contentDigest.hex()),
            canonicalBytes(second.profile.candidate),
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        assertEquals(
            UltraProfileStoreCode.ACTIVE_CONTENT_MISMATCH,
            mismatchStore.loadActive(first.binding, verifierFor(first.binding)).quarantined().code,
        )
    }

    @Test
    fun pointerAndArchiveSymlinksAreNeverFollowed() {
        val root = newRoot()
        val fixture = fixture("v1")
        val store = UltraProfileStore(root)
        store.install(fixture.profile).installed()

        val externalPointer = temporaryFolder.newFile("external-pointer").toPath()
        Files.write(
            externalPointer,
            (fixture.binding.contentDigest.hex() + "\n")
                .toByteArray(StandardCharsets.US_ASCII),
        )
        try {
            Files.delete(activePath(root))
            Files.createSymbolicLink(activePath(root), externalPointer)
        } catch (failure: UnsupportedOperationException) {
            assumeNoException(failure)
        } catch (failure: IOException) {
            assumeNoException(failure)
        } catch (failure: SecurityException) {
            assumeNoException(failure)
        }
        assertEquals(
            UltraProfileStoreCode.UNSAFE_STORE_LAYOUT,
            store.loadActive(fixture.binding, verifierFor(fixture.binding)).quarantined().code,
        )
        val externalPointerBefore = Files.readAllBytes(externalPointer)
        assertEquals(
            UltraProfileStoreCode.UNSAFE_STORE_LAYOUT,
            store.install(fixture.profile).failed().code,
        )
        assertArrayEquals(externalPointerBefore, Files.readAllBytes(externalPointer))

        Files.delete(activePath(root))
        store.install(fixture.profile).installed()
        val archivePath = archivePath(root, fixture.binding.contentDigest.hex())
        val externalArchive = temporaryFolder.newFile("external-profile.zip").toPath()
        Files.write(externalArchive, canonicalBytes(fixture.profile.candidate))
        Files.delete(archivePath)
        Files.createSymbolicLink(archivePath, externalArchive)
        assertEquals(
            UltraProfileStoreCode.UNSAFE_STORE_LAYOUT,
            store.loadActive(fixture.binding, verifierFor(fixture.binding)).quarantined().code,
        )
    }

    @Test
    fun expectedBindingAndVerifierFailuresNeverReturnAProfile() {
        val root = newRoot()
        val fixture = fixture("v1")
        val store = UltraProfileStore(root)
        store.install(fixture.profile).installed()

        val wrongDigest = fixture("other").binding
        var verifierCalled = false
        assertEquals(
            UltraProfileStoreCode.ACTIVE_CONTENT_MISMATCH,
            store.loadActive(wrongDigest) {
                verifierCalled = true
                UltraProfileTrustResult.Rejected()
            }.quarantined().code,
        )
        assertFalse(verifierCalled)

        val rejected = store.loadActive(fixture.binding) {
            UltraProfileTrustResult.Rejected()
        }.quarantined()
        assertEquals(UltraProfileStoreCode.PROFILE_TRUST_REJECTED, rejected.code)
        assertEquals(UltraProfileQuarantineCode.TRUST_REJECTED, rejected.packageCode)

        val mismatchedBinding = fixture.binding.copy(vehicleId = "another-vehicle")
        val mismatch = store.loadActive(fixture.binding) {
            UltraProfileTrustResult.Trusted(
                UltraProfileTrustAttestation(mismatchedBinding, byteArrayOf(7)),
            )
        }.quarantined()
        assertEquals(UltraProfileQuarantineCode.TRUST_BINDING_MISMATCH, mismatch.packageCode)

        val failed = store.loadActive(fixture.binding) { error("verifier offline") }.quarantined()
        assertEquals(UltraProfileStoreCode.PROFILE_TRUST_REJECTED, failed.code)
        assertEquals(UltraProfileQuarantineCode.TRUST_VERIFIER_FAILED, failed.packageCode)
    }

    @Test
    fun absentStoreIsInactiveButUnsafeDigestDirectoryIsQuarantined() {
        val parent = temporaryFolder.newFolder("absent-parent").toPath()
        val absentRoot = parent.resolve("not-created")
        val fixture = fixture("v1")
        assertTrue(
            UltraProfileStore(absentRoot).loadActive(
                fixture.binding,
                verifierFor(fixture.binding),
            ) === UltraProfileLoadResult.Inactive,
        )

        val root = newRoot()
        val store = UltraProfileStore(root)
        store.install(fixture.profile).installed()
        val digestDirectory = root.resolve(UltraProfileStore.PROFILES_DIRECTORY_NAME)
            .resolve(fixture.binding.contentDigest.hex())
        val externalDirectory = temporaryFolder.newFolder("external-directory").toPath()
        Files.delete(archivePath(root, fixture.binding.contentDigest.hex()))
        Files.delete(digestDirectory)
        try {
            Files.createSymbolicLink(digestDirectory, externalDirectory)
        } catch (failure: UnsupportedOperationException) {
            assumeNoException(failure)
        } catch (failure: IOException) {
            assumeNoException(failure)
        } catch (failure: SecurityException) {
            assumeNoException(failure)
        }
        assertEquals(
            UltraProfileStoreCode.UNSAFE_STORE_LAYOUT,
            store.loadActive(fixture.binding, verifierFor(fixture.binding)).quarantined().code,
        )
    }

    private fun newRoot(): Path = temporaryFolder.newFolder("store-${System.nanoTime()}").toPath()

    private fun newAbsentRoot(): Path =
        temporaryFolder.newFolder("store-parent-${System.nanoTime()}")
            .toPath()
            .resolve("not-created")

    private fun failingStore(root: Path, step: UltraProfileStoreStep) = UltraProfileStore(
        root,
        UltraProfilePackageInspector(),
        UltraProfileStoreFaultInjector { current ->
            if (current == step) throw IOException("injected store failure")
        },
    )

    private fun fixture(
        version: String,
        proof: ByteArray = "proof-$version".encodeToByteArray(),
        phoneAssets: ByteArray = "phone-assets-$version".encodeToByteArray(),
    ): Fixture {
        val archive = zip(
            listOf(
                UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to ByteArray(0),
                UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to "{}".encodeToByteArray(),
                UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".encodeToByteArray(),
                UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                    BplistCodec.encode(mapOf("Bundle" to "Phone")),
                TEST_PHONE_ASSET_FILE to phoneAssets,
                UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to
                    "accessory-assets-$version".encodeToByteArray(),
                UltraProfilePackageFiles.CERTIFICATE_CHAIN to
                    "certificate-chain-$version".encodeToByteArray(),
                UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to
                    "opaque-sha256-$version".encodeToByteArray(),
                UltraProfilePackageFiles.ACCESSORY_ADDITIONS to
                    "accessory-additions-$version".encodeToByteArray(),
                UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(
                    mapOf("Version" to version),
                ),
            ),
        )
        val inspection = UltraProfilePackageInspector().inspect(ByteArrayInputStream(archive))
        val candidate = (inspection as UltraProfileInspectionResult.Candidate).profile
        val binding = binding(candidate, version)
        val verification = UltraProfilePackageInspector().verify(candidate, binding) {
            UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(binding, proof))
        }
        val profile = (verification as UltraProfileVerificationResult.Verified).profile
        return Fixture(archive, binding, profile)
    }

    private fun binding(candidate: UltraProfileCandidate, version: String) =
        UltraProfileTrustBinding(
            vehicleId = "vehicle-$version",
            mfiCertificateIdentity = "certificate-$version",
            assetIdentifier = "asset-$version",
            iosContentVersion = "ios-$version",
            accessoryContentVersion = "accessory-$version",
            contentDigest = candidate.contentDigest,
        )

    private fun verifierFor(binding: UltraProfileTrustBinding) = UltraProfileTrustVerifier {
        UltraProfileTrustResult.Trusted(
            UltraProfileTrustAttestation(binding, "load-proof".encodeToByteArray()),
        )
    }

    private fun archivePath(root: Path, digestHex: String): Path =
        root.resolve(UltraProfileStore.PROFILES_DIRECTORY_NAME)
            .resolve(digestHex)
            .resolve(UltraProfileStore.ARCHIVE_FILE_NAME)

    private fun activePath(root: Path): Path = root.resolve(UltraProfileStore.ACTIVE_POINTER_FILE_NAME)

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun canonicalBytes(candidate: UltraProfileCandidate): ByteArray =
        ByteArrayOutputStream().also { output -> candidate.writeCanonicalArchive(output) }
            .toByteArray()

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > size) return false
        return (0..size - needle.size).any { offset ->
            needle.indices.all { index -> this[offset + index] == needle[index] }
        }
    }

    private fun UltraProfileInstallResult.installed(): UltraProfileInstallResult.Installed =
        this as UltraProfileInstallResult.Installed

    private fun UltraProfileInstallResult.failed(): UltraProfileInstallResult.Failed =
        this as UltraProfileInstallResult.Failed

    private fun UltraProfileInstallResult.committedUncertain():
        UltraProfileInstallResult.CommittedButDurabilityUncertain =
        this as UltraProfileInstallResult.CommittedButDurabilityUncertain

    private fun UltraProfileLoadResult.loaded(): VerifiedUltraProfile =
        (this as UltraProfileLoadResult.Loaded).profile

    private fun UltraProfileLoadResult.quarantined(): UltraProfileLoadResult.Quarantined =
        this as UltraProfileLoadResult.Quarantined

    private data class Fixture(
        val archive: ByteArray,
        val binding: UltraProfileTrustBinding,
        val profile: VerifiedUltraProfile,
    )
}
