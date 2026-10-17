package com.shilapi.xcertplay.vehicle.ultra.profile

import com.shilapi.xcertplay.airplay.BplistCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TEST_PHONE_ASSET_FILE = "CarPlayPhone.assets/layout.bin"

class UltraProfilePackageInspectorTest {
    @Test
    fun validPackageRecognizesOnlyExactIos27EvidenceNames() {
        val result = inspect(zip(validEntries()))
        val candidate = result.candidate()

        assertEquals(
            UltraProfilePackageFiles.requiredNames - UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS +
                setOf(
                    UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
                    UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON,
                    UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST,
                    TEST_PHONE_ASSET_FILE,
                ),
            candidate.fileNames,
        )
        assertTrue(
            UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY in candidate.directoryNames,
        )
        assertEquals("theme-opaque", candidate.manifest.values["OpaqueTestValue"])
        assertEquals(9, candidate.entries.size)
        assertTrue(candidate.content(TEST_PHONE_ASSET_FILE)!!.isNotEmpty())
    }

    @Test
    fun rejectsZipSlipAbsoluteBackslashNulNestedAndDirectoryPaths() {
        val cases = listOf(
            "../Info.plist" to UltraProfileQuarantineCode.INVALID_ENTRY_PATH,
            "/Info.plist" to UltraProfileQuarantineCode.INVALID_ENTRY_PATH,
            "C:Info.plist" to UltraProfileQuarantineCode.INVALID_ENTRY_PATH,
            "Info\\plist" to UltraProfileQuarantineCode.INVALID_ENTRY_PATH,
            "Info\u0000.plist" to UltraProfileQuarantineCode.INVALID_ENTRY_PATH,
            UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS to
                UltraProfileQuarantineCode.DIRECTORY_ENTRY,
            "dir/Info.plist" to UltraProfileQuarantineCode.NESTED_ENTRY,
            "dir/" to UltraProfileQuarantineCode.DIRECTORY_ENTRY,
        )

        cases.forEach { (name, expectedCode) ->
            val result = inspect(zip(listOf(name to byteArrayOf(1))))
            assertEquals("path=$name", expectedCode, result.quarantine().code)
        }
    }

    @Test
    fun nestedTreeIsAllowedOnlyBelowExactPhoneAssetsDirectory() {
        val nestedName = "CarPlayPhone.assets/Localized/en/layout.bin"
        val nested = validEntries().filterNot { it.first == TEST_PHONE_ASSET_FILE } +
            listOf(
                "CarPlayPhone.assets/Localized/" to ByteArray(0),
                "CarPlayPhone.assets/Localized/en/" to ByteArray(0),
                nestedName to byteArrayOf(1, 2, 3),
            )
        val candidate = inspect(zip(nested)).candidate()
        assertArrayEquals(byteArrayOf(1, 2, 3), candidate.content(nestedName))
        assertTrue("CarPlayPhone.assets/Localized/" in candidate.directoryNames)
        assertTrue("CarPlayPhone.assets/Localized/en/" in candidate.directoryNames)

        assertEquals(
            UltraProfileQuarantineCode.NESTED_ENTRY,
            inspect(zip(validEntries() + ("Other.assets/file" to byteArrayOf(1))))
                .quarantine()
                .code,
        )
        assertEquals(
            UltraProfileQuarantineCode.INVALID_ENTRY_PATH,
            inspect(zip(validEntries() + ("CarPlayPhone.assets/../escape" to byteArrayOf(1))))
                .quarantine()
                .code,
        )
    }

    @Test
    fun phoneAssetsRootDirectoryEntryMayBeImplicitAndCanonicalizerRestoresIt() {
        val withoutExplicitRoot = validEntries().filterNot {
            it.first == UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY
        }
        val candidate = inspect(zip(withoutExplicitRoot)).candidate()

        assertTrue(
            UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY in candidate.directoryNames,
        )
        val canonical = canonicalBytes(candidate)
        val reinspected = UltraProfilePackageInspector()
            .inspectCanonical(ByteArrayInputStream(canonical))
            .candidate()
        assertEquals(candidate.contentDigest, reinspected.contentDigest)
    }

    @Test
    fun rejectsExactDuplicatesAndCaseConflicts() {
        val withPatchableDuplicate = validEntries() +
            ("Xnfo.plist" to BplistCodec.encode(mapOf("Other" to true)))
        val exactDuplicate = zip(withPatchableDuplicate).also {
            replaceAsciiOccurrences(it, "Xnfo.plist", UltraProfilePackageFiles.INFO_PLIST)
        }
        assertEquals(
            UltraProfileQuarantineCode.DUPLICATE_ENTRY,
            inspect(exactDuplicate).quarantine().code,
        )

        val caseConflict = zip(
            validEntries() +
                ("info.plist" to BplistCodec.encode(mapOf("Other" to true))),
        )
        assertEquals(
            UltraProfileQuarantineCode.CASE_CONFLICT,
            inspect(caseConflict).quarantine().code,
        )
    }

    @Test
    fun malformedUtf8EntryNameIsQuarantinedInsteadOfEscapingParserException() {
        val archive = zip(listOf("Info.plist" to byteArrayOf(1)))
        corruptUtf8EntryName(archive, "Info.plist")

        assertEquals(
            UltraProfileQuarantineCode.MALFORMED_ZIP,
            inspect(archive).quarantine().code,
        )
    }

    @Test
    fun centralDirectoryNameCountDuplicateAndTypeMustMatchLocalEntries() {
        val nameMismatch = zip(validEntries())
        replaceCentralName(nameMismatch, TEST_PHONE_ASSET_FILE, "CarPlayPhone.assets/mayout.bin")
        assertEquals(
            UltraProfileQuarantineCode.MALFORMED_ZIP,
            inspect(nameMismatch).quarantine().code,
        )

        val countMismatch = zip(validEntries())
        decrementCentralEntryCounts(countMismatch)
        assertEquals(
            UltraProfileQuarantineCode.MALFORMED_ZIP,
            inspect(countMismatch).quarantine().code,
        )

        val duplicateMismatch = zip(
            validEntries() +
                listOf(
                    "CarPlayPhone.assets/a.bin" to byteArrayOf(1),
                    "CarPlayPhone.assets/b.bin" to byteArrayOf(2),
                ),
        )
        replaceCentralName(
            duplicateMismatch,
            "CarPlayPhone.assets/b.bin",
            "CarPlayPhone.assets/a.bin",
        )
        assertEquals(
            UltraProfileQuarantineCode.MALFORMED_ZIP,
            inspect(duplicateMismatch).quarantine().code,
        )

        val typeMismatch = zip(validEntries())
        markCentralEntryAsUnixType(typeMismatch, TEST_PHONE_ASSET_FILE, 0x4000)
        assertEquals(
            UltraProfileQuarantineCode.MALFORMED_ZIP,
            inspect(typeMismatch).quarantine().code,
        )
    }

    @Test
    fun unixSymlinkEntryIsRejectedBeforeCanonicalRepackaging() {
        val archive = zip(validEntries())
        markCentralEntryAsUnixType(archive, TEST_PHONE_ASSET_FILE, 0xa000)

        assertEquals(
            UltraProfileQuarantineCode.UNSUPPORTED_ZIP_FEATURE,
            inspect(archive).quarantine().code,
        )
    }

    @Test
    fun rejectsUndeclaredMissingEmptyAndBadPlistFiles() {
        assertEquals(
            UltraProfileQuarantineCode.UNDECLARED_ENTRY,
            inspect(zip(validEntries() + ("README" to byteArrayOf(1)))).quarantine().code,
        )

        val missing = validEntries().filterNot {
            it.first == UltraProfilePackageFiles.CERTIFICATE_CHAIN
        }
        assertEquals(
            UltraProfileQuarantineCode.MISSING_REQUIRED_ENTRY,
            inspect(zip(missing)).quarantine().code,
        )

        UltraProfilePackageFiles.orderedRequiredPhoneAssetFileNames.forEach { requiredPhoneFile ->
            val missingPhoneMetadata = validEntries().filterNot { it.first == requiredPhoneFile }
            assertEquals(
                "missing=$requiredPhoneFile",
                UltraProfileQuarantineCode.MISSING_REQUIRED_ENTRY,
                inspect(zip(missingPhoneMetadata)).quarantine().code,
            )
        }

        val empty = validEntries().map { (name, bytes) ->
            name to if (name == UltraProfilePackageFiles.ACCESSORY_ADDITIONS) ByteArray(0) else bytes
        }
        assertEquals(
            UltraProfileQuarantineCode.EMPTY_REQUIRED_ENTRY,
            inspect(zip(empty)).quarantine().code,
        )

        val malformed = validEntries().map { (name, bytes) ->
            name to if (name == UltraProfilePackageFiles.INFO_PLIST) {
                "not-a-bplist".encodeToByteArray()
            } else {
                bytes
            }
        }
        assertEquals(
            UltraProfileQuarantineCode.INVALID_INFO_PLIST,
            inspect(zip(malformed)).quarantine().code,
        )
    }

    @Test
    fun rejectsArchiveFileAggregateCountPathAndCompressionBombLimits() {
        val ordinary = zip(validEntries())
        assertEquals(
            UltraProfileQuarantineCode.ARCHIVE_LIMIT_EXCEEDED,
            inspect(
                ordinary,
                limits(maximumArchiveBytes = (ordinary.size - 1).toLong()),
            ).quarantine().code,
        )

        val oversizedFile = validEntries().map { (name, bytes) ->
            name to if (name == TEST_PHONE_ASSET_FILE) {
                ByteArray(513) { 1 }
            } else {
                bytes
            }
        }
        assertEquals(
            UltraProfileQuarantineCode.FILE_LIMIT_EXCEEDED,
            inspect(
                zip(oversizedFile),
                limits(maximumFileBytes = 512, maximumTotalContentBytes = 2048),
            ).quarantine().code,
        )

        val aggregate = validEntries().map { (name, bytes) ->
            name to if (
                name == TEST_PHONE_ASSET_FILE ||
                name == UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS
            ) {
                ByteArray(400) { 2 }
            } else {
                bytes
            }
        }
        assertEquals(
            UltraProfileQuarantineCode.TOTAL_CONTENT_LIMIT_EXCEEDED,
            inspect(
                zip(aggregate),
                limits(maximumFileBytes = 512, maximumTotalContentBytes = 700),
            ).quarantine().code,
        )

        assertEquals(
            UltraProfileQuarantineCode.FILE_LIMIT_EXCEEDED,
            inspect(
                ordinary,
                limits(maximumFileCount = 5),
            ).quarantine().code,
        )

        val longName = "A".repeat(40)
        assertEquals(
            UltraProfileQuarantineCode.INVALID_ENTRY_PATH,
            inspect(
                zip(listOf(longName to byteArrayOf(1))),
                limits(maximumPathBytes = 32),
            ).quarantine().code,
        )

        val compressedBomb = validEntries().map { (name, bytes) ->
            name to if (name == TEST_PHONE_ASSET_FILE) {
                ByteArray(32 * 1024)
            } else {
                bytes
            }
        }
        assertEquals(
            UltraProfileQuarantineCode.COMPRESSION_RATIO_EXCEEDED,
            inspect(
                zip(compressedBomb, compressionLevel = Deflater.BEST_COMPRESSION),
                limits(
                    maximumArchiveBytes = 128 * 1024,
                    maximumFileBytes = 64 * 1024,
                    maximumTotalContentBytes = 128 * 1024,
                    maximumCompressionRatio = 2,
                ),
            ).quarantine().code,
        )
    }

    @Test
    fun compressedProvisioningLimitIsIndependentFromLargerStoredCanonicalLimit() {
        val entries = validEntries().map { (name, bytes) ->
            name to if (name == TEST_PHONE_ASSET_FILE) {
                ByteArray(16 * 1024) { (it % 8).toByte() }
            } else {
                bytes
            }
        }
        val rawArchive = zip(entries, compressionLevel = Deflater.BEST_COMPRESSION)
        val permissive = UltraProfilePackageInspector(
            limits(
                maximumArchiveBytes = 64 * 1024,
                maximumCanonicalArchiveBytes = 64 * 1024,
                maximumFileBytes = 32 * 1024,
                maximumTotalContentBytes = 48 * 1024,
                maximumCompressionRatio = 1000,
            ),
        )
        val initial = permissive.inspect(ByteArrayInputStream(rawArchive)).candidate()
        val canonicalArchive = canonicalBytes(initial)
        assertTrue(canonicalArchive.size > rawArchive.size)

        val boundaryLimits = limits(
            maximumArchiveBytes = rawArchive.size.toLong(),
            maximumCanonicalArchiveBytes = canonicalArchive.size.toLong(),
            maximumFileBytes = 32 * 1024,
            maximumTotalContentBytes = 48 * 1024,
            maximumCompressionRatio = 1000,
        )
        val boundaryInspector = UltraProfilePackageInspector(boundaryLimits)
        val candidate = boundaryInspector.inspect(ByteArrayInputStream(rawArchive)).candidate()
        assertEquals(
            UltraProfileQuarantineCode.NON_CANONICAL_ARCHIVE,
            boundaryInspector.inspectCanonical(ByteArrayInputStream(rawArchive))
                .quarantine()
                .code,
        )
        assertEquals(
            candidate.contentDigest,
            boundaryInspector.inspectCanonical(ByteArrayInputStream(canonicalArchive))
                .candidate()
                .contentDigest,
        )

        val tooSmallCanonicalLimit = boundaryLimits.copy(
            maximumCanonicalArchiveBytes = canonicalArchive.size.toLong() - 1,
        )
        assertEquals(
            UltraProfileQuarantineCode.CANONICAL_ARCHIVE_LIMIT_EXCEEDED,
            UltraProfilePackageInspector(tooSmallCanonicalLimit)
                .inspectCanonical(ByteArrayInputStream(canonicalArchive))
                .quarantine()
                .code,
        )
    }

    @Test
    fun defaultMissingRejectedAndFailingVerifiersNeverProduceVerifiedProfile() {
        val candidate = inspect(zip(validEntries())).candidate()
        val binding = binding(candidate)
        val inspector = UltraProfilePackageInspector()

        assertEquals(
            UltraProfileQuarantineCode.NO_TRUST_VERIFIER,
            inspector.verify(candidate, binding).quarantine().code,
        )
        assertEquals(
            UltraProfileQuarantineCode.TRUST_REJECTED,
            inspector.verify(candidate, binding) { UltraProfileTrustResult.Rejected() }
                .quarantine().code,
        )
        assertEquals(
            UltraProfileQuarantineCode.TRUST_VERIFIER_FAILED,
            inspector.verify(candidate, binding) { error("verifier unavailable") }
                .quarantine().code,
        )
    }

    @Test
    fun verifierProofMustMatchEveryBindingFieldAndCandidateDigest() {
        val candidate = inspect(zip(validEntries())).candidate()
        val expected = binding(candidate)
        val mismatches = listOf(
            expected.copy(vehicleId = "vehicle-other"),
            expected.copy(mfiCertificateIdentity = "cert-other"),
            expected.copy(assetIdentifier = "asset-other"),
            expected.copy(iosContentVersion = "ios-other"),
            expected.copy(accessoryContentVersion = "accessory-other"),
            expected.copy(contentDigest = UltraProfileDigest(ByteArray(32) { 7 })),
        )

        mismatches.forEach { mismatch ->
            val result = UltraProfilePackageInspector().verify(candidate, expected) {
                UltraProfileTrustResult.Trusted(
                    UltraProfileTrustAttestation(mismatch, byteArrayOf(1)),
                )
            }
            assertEquals(
                UltraProfileQuarantineCode.TRUST_BINDING_MISMATCH,
                result.quarantine().code,
            )
        }

        val wrongExpected = expected.copy(contentDigest = UltraProfileDigest(ByteArray(32) { 3 }))
        var verifierCalled = false
        val result = UltraProfilePackageInspector().verify(candidate, wrongExpected) {
            verifierCalled = true
            UltraProfileTrustResult.Rejected()
        }
        assertEquals(
            UltraProfileQuarantineCode.TRUST_BINDING_MISMATCH,
            result.quarantine().code,
        )
        assertTrue(!verifierCalled)
    }

    @Test
    fun successfulProofIsGatedAndAllPackageManifestAndProofBytesAreDefensiveCopies() {
        val manifestBlob = byteArrayOf(1, 2, 3)
        val archive = zip(validEntries(info = mapOf(
            "OpaqueTestValue" to "theme-opaque",
            "Blob" to manifestBlob,
            "Nested" to listOf(mapOf("Data" to byteArrayOf(4, 5))),
        )))
        val candidate = inspect(archive).candidate()
        val binding = binding(candidate)
        val proof = byteArrayOf(9, 8, 7)
        val attestation = UltraProfileTrustAttestation(binding, proof)
        val expectedCanonicalArchive = canonicalBytes(candidate)

        archive.fill(0)
        manifestBlob.fill(0)
        proof.fill(0)

        val result = UltraProfilePackageInspector().verify(candidate, binding) { request ->
            assertSame(candidate, request.candidate)
            assertEquals(binding, request.expectedBinding)
            UltraProfileTrustResult.Trusted(attestation)
        }
        val verified = result.verified()
        assertEquals(binding, verified.binding)
        assertArrayEquals(byteArrayOf(9, 8, 7), verified.trustProofBytes())

        val archiveCopy = canonicalBytes(candidate)
        assertArrayEquals(expectedCanonicalArchive, archiveCopy)
        val reinspected = inspect(archiveCopy).candidate()
        assertEquals(candidate.contentDigest, reinspected.contentDigest)
        assertEquals(candidate.fileNames, reinspected.fileNames)
        archiveCopy.fill(0)
        assertArrayEquals(expectedCanonicalArchive, canonicalBytes(candidate))

        val content = candidate.content(TEST_PHONE_ASSET_FILE)!!
        val originalFirstByte = content[0]
        content[0] = (content[0].toInt() xor 0xff).toByte()
        assertEquals(
            originalFirstByte,
            candidate.content(TEST_PHONE_ASSET_FILE)!![0],
        )

        val manifestValues = candidate.manifest.values
        (manifestValues["Blob"] as ByteArray)[0] = 99
        ((manifestValues["Nested"] as List<*>).single() as Map<*, *>).let {
            (it["Data"] as ByteArray)[0] = 99
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), candidate.manifest.values["Blob"] as ByteArray)
        val nestedAgain = (candidate.manifest.values["Nested"] as List<*>).single() as Map<*, *>
        assertArrayEquals(byteArrayOf(4, 5), nestedAgain["Data"] as ByteArray)

        val rawManifest = candidate.manifest.rawBytes
        rawManifest[0] = 0
        assertTrue(candidate.manifest.rawBytes[0].toInt() != 0)

        val digestBytes = candidate.contentDigest.bytes()
        digestBytes.fill(0)
        assertTrue(UltraProfileDigest(ByteArray(32)) != candidate.contentDigest)

        val proofCopy = verified.trustProofBytes()
        proofCopy[0] = 0
        assertArrayEquals(byteArrayOf(9, 8, 7), verified.trustProofBytes())

        assertThrows(IllegalArgumentException::class.java) {
            VerifiedUltraProfile(candidate, binding, attestation, Any())
        }
    }

    @Test
    fun manifestCopyPreservesSharedDagWithoutExponentialExpansionAndRejectsCycles() {
        var shared: Map<String, Any?> = mapOf("leaf" to byteArrayOf(1, 2, 3))
        repeat(48) {
            shared = linkedMapOf("left" to shared, "right" to shared)
        }

        val manifest = UltraProfileManifest(
            rawBytes = byteArrayOf(1),
            values = mapOf("root" to shared),
        )
        var copied = manifest.values.getValue("root") as Map<*, *>
        repeat(48) {
            assertSame(copied["left"], copied["right"])
            copied = copied["left"] as Map<*, *>
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), copied["leaf"] as ByteArray)

        val cyclic = LinkedHashMap<String, Any?>()
        cyclic["self"] = cyclic
        assertThrows(IllegalArgumentException::class.java) {
            UltraProfileManifest(byteArrayOf(1), cyclic)
        }
    }

    @Test
    fun trustBindingStringRepresentationDoesNotLeakStableIdentifiers() {
        val candidate = inspect(zip(validEntries())).candidate()
        val binding = binding(candidate)
        val rendered = binding.toString()

        assertEquals("UltraProfileTrustBinding(<redacted>)", rendered)
        assertTrue(!rendered.contains(binding.vehicleId))
        assertTrue(!rendered.contains(binding.mfiCertificateIdentity))
        assertTrue(!rendered.contains(binding.assetIdentifier))
        assertTrue(!rendered.contains(binding.iosContentVersion))
        assertTrue(!rendered.contains(binding.accessoryContentVersion))
        assertTrue(!rendered.contains(binding.contentDigest.hex()))
    }

    private fun inspect(
        archive: ByteArray,
        limits: UltraProfilePackageLimits = UltraProfilePackageLimits(),
    ): UltraProfileInspectionResult =
        UltraProfilePackageInspector(limits).inspect(ByteArrayInputStream(archive))

    private fun validEntries(
        info: Map<String, Any?> = mapOf("OpaqueTestValue" to "theme-opaque"),
    ): List<Pair<String, ByteArray>> = listOf(
        UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to ByteArray(0),
        UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to "{}".encodeToByteArray(),
        UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".encodeToByteArray(),
        UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
            BplistCodec.encode(mapOf("Bundle" to "Phone")),
        TEST_PHONE_ASSET_FILE to "phone-assets".encodeToByteArray(),
        UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to "accessory-assets".encodeToByteArray(),
        UltraProfilePackageFiles.CERTIFICATE_CHAIN to "certificate-chain".encodeToByteArray(),
        UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to "opaque-sha256-file".encodeToByteArray(),
        UltraProfilePackageFiles.ACCESSORY_ADDITIONS to "accessory-additions".encodeToByteArray(),
        UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(info),
    )

    private fun binding(candidate: UltraProfileCandidate) = UltraProfileTrustBinding(
        vehicleId = "vehicle-opaque",
        mfiCertificateIdentity = "certificate-opaque",
        assetIdentifier = "asset-opaque",
        iosContentVersion = "ios-version-opaque",
        accessoryContentVersion = "accessory-version-opaque",
        contentDigest = candidate.contentDigest,
    )

    private fun limits(
        maximumArchiveBytes: Long = 1024 * 1024,
        maximumCanonicalArchiveBytes: Long = 2 * 1024 * 1024,
        maximumTotalContentBytes: Long = 1024 * 1024,
        maximumFileBytes: Long = 512 * 1024,
        maximumFileCount: Int = 16,
        maximumPathBytes: Int = 255,
        maximumCompressionRatio: Int = 250,
    ) = UltraProfilePackageLimits(
        maximumArchiveBytes = maximumArchiveBytes,
        maximumCanonicalArchiveBytes = maximumCanonicalArchiveBytes,
        maximumTotalContentBytes = maximumTotalContentBytes,
        maximumFileBytes = maximumFileBytes,
        maximumFileCount = maximumFileCount,
        maximumPathBytes = maximumPathBytes,
        maximumCompressionRatio = maximumCompressionRatio,
        maximumInfoPlistBytes = minOf(maximumFileBytes, 256 * 1024).toInt(),
    )

    private fun zip(
        entries: List<Pair<String, ByteArray>>,
        compressionLevel: Int = Deflater.DEFAULT_COMPRESSION,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setLevel(compressionLevel)
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun canonicalBytes(candidate: UltraProfileCandidate): ByteArray =
        ByteArrayOutputStream().also { output ->
            val metadata = candidate.writeCanonicalArchive(output)
            assertEquals(output.size().toLong(), metadata.sizeBytes)
            assertEquals(candidate.canonicalArchiveMetadata, metadata)
        }.toByteArray()

    private fun replaceAsciiOccurrences(target: ByteArray, from: String, to: String) {
        val fromBytes = from.encodeToByteArray()
        val toBytes = to.encodeToByteArray()
        require(fromBytes.size == toBytes.size)
        var replacements = 0
        for (offset in 0..target.size - fromBytes.size) {
            if (target.copyOfRange(offset, offset + fromBytes.size).contentEquals(fromBytes)) {
                toBytes.copyInto(target, offset)
                replacements++
            }
        }
        assertTrue("Expected local and central ZIP names to be patched", replacements >= 2)
    }

    private fun corruptUtf8EntryName(target: ByteArray, name: String) {
        val nameBytes = name.encodeToByteArray()
        var replacements = 0
        for (offset in 0..target.size - nameBytes.size) {
            if (target.copyOfRange(offset, offset + nameBytes.size).contentEquals(nameBytes)) {
                // 0xc3 must be followed by a UTF-8 continuation byte; ASCII '(' makes this invalid.
                target[offset] = 0xc3.toByte()
                target[offset + 1] = '('.code.toByte()
                replacements++
            }
        }
        assertTrue("Expected local and central ZIP names to be corrupted", replacements >= 2)
    }

    private fun replaceCentralName(target: ByteArray, from: String, to: String) {
        val fromBytes = from.encodeToByteArray()
        val toBytes = to.encodeToByteArray()
        require(fromBytes.size == toBytes.size)
        val offset = findCentralEntry(target, fromBytes)
        toBytes.copyInto(target, offset + 46)
    }

    private fun decrementCentralEntryCounts(target: ByteArray) {
        var eocd = -1
        for (offset in target.size - 22 downTo maxOf(0, target.size - 22 - 65_535)) {
            if (target[offset] == 0x50.toByte() &&
                target[offset + 1] == 0x4b.toByte() &&
                target[offset + 2] == 0x05.toByte() &&
                target[offset + 3] == 0x06.toByte()
            ) {
                eocd = offset
                break
            }
        }
        assertTrue("Expected ZIP end record", eocd >= 0)
        val count = readLittleEndianUShort(target, eocd + 10)
        assertTrue(count > 1)
        writeLittleEndianUShort(target, eocd + 8, count - 1)
        writeLittleEndianUShort(target, eocd + 10, count - 1)
    }

    private fun markCentralEntryAsUnixType(target: ByteArray, name: String, unixType: Int) {
        val nameBytes = name.encodeToByteArray()
        val offset = findCentralEntry(target, nameBytes)
        target[offset + 5] = 3 // "version made by" platform = Unix.
        val externalAttributes = unixType.toLong() shl 16
        repeat(4) { index ->
            target[offset + 38 + index] =
                ((externalAttributes ushr (index * 8)) and 0xff).toByte()
        }
    }

    private fun findCentralEntry(target: ByteArray, nameBytes: ByteArray): Int {
        for (offset in 0..target.size - 46 - nameBytes.size) {
            if (target[offset] == 0x50.toByte() &&
                target[offset + 1] == 0x4b.toByte() &&
                target[offset + 2] == 0x01.toByte() &&
                target[offset + 3] == 0x02.toByte() &&
                readLittleEndianUShort(target, offset + 28) == nameBytes.size &&
                target.copyOfRange(offset + 46, offset + 46 + nameBytes.size)
                    .contentEquals(nameBytes)
            ) {
                return offset
            }
        }
        throw AssertionError("Expected central directory entry")
    }

    private fun readLittleEndianUShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun writeLittleEndianUShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun UltraProfileInspectionResult.candidate(): UltraProfileCandidate =
        (this as UltraProfileInspectionResult.Candidate).profile

    private fun UltraProfileInspectionResult.quarantine(): QuarantinedUltraProfile =
        (this as UltraProfileInspectionResult.Quarantined).quarantine

    private fun UltraProfileVerificationResult.quarantine(): QuarantinedUltraProfile =
        (this as UltraProfileVerificationResult.Quarantined).quarantine

    private fun UltraProfileVerificationResult.verified(): VerifiedUltraProfile =
        (this as UltraProfileVerificationResult.Verified).profile
}
