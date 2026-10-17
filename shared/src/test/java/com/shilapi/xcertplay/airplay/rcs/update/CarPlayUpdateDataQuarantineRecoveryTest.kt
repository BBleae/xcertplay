package com.shilapi.xcertplay.airplay.rcs.update

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CarPlayUpdateDataQuarantineRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun retainedRecordRemainsUntrustedAndAckUnknown() {
        val root = temporaryFolder.newFolder("quarantine")
        val record = directory(root, "record")
        write(record, "header.bin", 2)
        write(record, "archive.bin", 3)
        write(record, "receipt.bin", 1)

        val inventory = CarPlayUpdateDataQuarantineRecovery(root).inventory()
        assertEquals(1, inventory.entryCount)
        assertEquals(6L, inventory.bytes)
        assertEquals(record, inventory.entries.single().directory)
        assertEquals(
            CarPlayUpdateDataQuarantineEntryKind.RECORD_UNTRUSTED_ACK_UNKNOWN,
            inventory.entries.single().kind,
        )
        assertEquals(setOf("header.bin", "archive.bin", "receipt.bin"), inventory.entries.single().fileNames)
        assertTrue(record.isDirectory)
    }

    @Test
    fun rejectsMalformedNamesMissingCommittedFileAndUnknownChildren() {
        val malformedRoot = temporaryFolder.newFolder("malformed")
        File(malformedRoot, "staging-00000000-0000-0000-0000-000000000000").mkdir()
        assertThrows(IOException::class.java) {
            CarPlayUpdateDataQuarantineRecovery(malformedRoot).inventory()
        }

        val missingRoot = temporaryFolder.newFolder("missing")
        val incompleteRecord = directory(missingRoot, "record")
        write(incompleteRecord, "header.bin", 1)
        write(incompleteRecord, "archive.bin", 1)
        assertThrows(IOException::class.java) {
            CarPlayUpdateDataQuarantineRecovery(missingRoot).inventory()
        }

        val unknownRoot = temporaryFolder.newFolder("unknown")
        val staging = directory(unknownRoot, "staging")
        write(staging, "unexpected.bin", 1)
        assertThrows(IOException::class.java) {
            CarPlayUpdateDataQuarantineRecovery(unknownRoot).inventory()
        }
    }

    @Test
    fun refusesReplacedRootAndDuplicateStagingRecordToken() {
        val root = temporaryFolder.newFolder("quarantine")
        val recovery = CarPlayUpdateDataQuarantineRecovery(root)
        val moved = root.toPath().parent.resolve("quarantine-moved")
        Files.move(root.toPath(), moved)
        Files.createDirectory(root.toPath())
        assertThrows(IOException::class.java) { recovery.inventory() }

        val token = UUID.randomUUID().toString()
        assertTrue(File(root, "staging-$token").mkdir())
        val record = File(root, "record-$token")
        assertTrue(record.mkdir())
        write(record, "header.bin", 1)
        write(record, "archive.bin", 1)
        write(record, "receipt.bin", 1)
        assertThrows(IOException::class.java) {
            CarPlayUpdateDataQuarantineRecovery(root).inventory()
        }
    }

    @Test
    fun refusesRootEntryAndFileSymlinksWithoutReadingTargets() {
        val outside = temporaryFolder.newFile("outside")
        outside.writeText("keep")
        val root = temporaryFolder.newFolder("quarantine")
        val rootLink = root.toPath().parent.resolve("quarantine-link")
        Files.createSymbolicLink(rootLink, root.toPath())
        assertThrows(IOException::class.java) {
            CarPlayUpdateDataQuarantineRecovery(rootLink.toFile())
        }

        val childLink = root.toPath().resolve("staging-${UUID.randomUUID()}")
        Files.createSymbolicLink(childLink, outside.toPath())
        assertThrows(IOException::class.java) {
            CarPlayUpdateDataQuarantineRecovery(root).inventory()
        }
        Files.delete(childLink)

        val staging = directory(root, "staging")
        Files.createSymbolicLink(staging.toPath().resolve("archive.bin"), outside.toPath())
        assertThrows(IOException::class.java) {
            CarPlayUpdateDataQuarantineRecovery(root).inventory()
        }
        assertEquals("keep", outside.readText())
    }

    @Test
    fun quotaCountsStagingAndRecordAndChecksExactBoundary() {
        val root = temporaryFolder.newFolder("quarantine")
        val record = directory(root, "record")
        write(record, "header.bin", 2)
        write(record, "archive.bin", 3)
        write(record, "receipt.bin", 1)
        val staging = directory(root, "staging")
        write(staging, "header.bin", 1)
        val recovery = CarPlayUpdateDataQuarantineRecovery(root)

        assertEquals(
            7L,
            recovery.requireAdmission(3, CarPlayUpdateDataQuarantineQuota(10, 3)).bytes,
        )
        assertThrows(IOException::class.java) {
            recovery.requireAdmission(4, CarPlayUpdateDataQuarantineQuota(10, 3))
        }
        assertThrows(IOException::class.java) {
            recovery.requireAdmission(1, CarPlayUpdateDataQuarantineQuota(100, 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            recovery.requireAdmission(0, CarPlayUpdateDataQuarantineQuota(100, 3))
        }
        assertEquals(2, recovery.inventory().entryCount)
    }

    @Test
    fun cleanupRequiresGuardDeletesOnlyOldPartialStagingAndIsIdempotent() {
        val root = temporaryFolder.newFolder("quarantine")
        Files.newDirectoryStream(root.toPath()).use { assumeTrue(it is SecureDirectoryStream<*>) }
        val old = directory(root, "staging")
        write(old, "header.bin", 2)
        write(old, "archive.bin", 3)
        val fresh = directory(root, "staging")
        write(fresh, "header.bin", 4)
        val retained = directory(root, "record")
        write(retained, "header.bin", 1)
        write(retained, "archive.bin", 1)
        write(retained, "receipt.bin", 1)
        val cutoff = Instant.now().minusSeconds(60)
        makeOld(old, cutoff.minusSeconds(60))
        makeOld(retained, cutoff.minusSeconds(60))
        val recovery = CarPlayUpdateDataQuarantineRecovery(root)

        assertThrows(IOException::class.java) {
            recovery.cleanupAbandonedStaging(cutoff, 1, Guard(active = true))
        }
        assertTrue(old.isDirectory)
        assertEquals(listOf(old), recovery.cleanupAbandonedStaging(cutoff, 1, Guard()))
        assertFalse(old.exists())
        assertTrue(fresh.isDirectory)
        assertTrue(retained.isDirectory)
        assertTrue(recovery.cleanupAbandonedStaging(cutoff, 1, Guard()).isEmpty())
        assertEquals(2, recovery.inventory().entryCount)
    }

    @Test
    fun cleanupRefusesUnknownEntryAndSymlinkBeforeDeletingAnyFile() {
        val root = temporaryFolder.newFolder("quarantine")
        Files.newDirectoryStream(root.toPath()).use { assumeTrue(it is SecureDirectoryStream<*>) }
        val staging = directory(root, "staging")
        val header = write(staging, "header.bin", 2)
        val outside = temporaryFolder.newFile("outside")
        outside.writeText("keep")
        Files.createSymbolicLink(staging.toPath().resolve("archive.bin"), outside.toPath())
        val recovery = CarPlayUpdateDataQuarantineRecovery(root)

        assertThrows(IOException::class.java) {
            recovery.cleanupAbandonedStaging(Instant.now(), 1, Guard())
        }
        assertTrue(header.isFile)
        assertEquals("keep", outside.readText())
    }

    private fun directory(root: File, prefix: String): File =
        File(root, "$prefix-${UUID.randomUUID()}").also { assertTrue(it.mkdir()) }

    private fun write(directory: File, name: String, count: Int): File =
        File(directory, name).also { it.writeBytes(ByteArray(count) { 42 }) }

    private fun makeOld(directory: File, at: Instant) {
        val timestamp = FileTime.from(at)
        directory.listFiles()!!.forEach { Files.setLastModifiedTime(it.toPath(), timestamp) }
        Files.setLastModifiedTime(directory.toPath(), timestamp)
    }

    private class Guard(private val active: Boolean = false) : CarPlayUpdateDataNoActiveSinkGuard {
        override fun <T> whileNoSinkCanBeActive(block: () -> T): T {
            if (active) throw IOException("Active sink")
            return block()
        }
    }
}
