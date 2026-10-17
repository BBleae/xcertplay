package com.shilapi.xcertplay.airplay.rcs.update

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CarPlayUpdateDataFileQuarantineSinkTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val codec = CarPlayUpdateDataCodec()

    @Test
    fun commitsArchiveAndExactOptionalMetadataOnlyAfterAtomicDurableRename() {
        val root = temporaryFolder.newFolder("quarantine")
        val sink = CarPlayUpdateDataFileQuarantineSink(root)
        val receiver = CarPlayUpdateDataReceiver(sink)
        val archive = byteArrayOf(1, 2, 3, 4, 5)
        val certificate = byteArrayOf(0, 8, -1)
        val signature = byteArrayOf(1, 0, 2)
        val additions = byteArrayOf(0, -2, 7, 0)
        val header = header(2, certificate, signature, additions)

        assertNull(sink.committedRecord)
        receiver.receive(codec.encode(header))
        assertTrue(onlyChild(root).name.startsWith("staging-"))
        receiver.receive(codec.encode(chunk(0, byteArrayOf(1, 2))))
        val pending = receiver.receive(codec.encode(chunk(1, byteArrayOf(3, 4, 5), sha256(archive))))
            as CarPlayUpdateDataReceiveResult.AwaitingDurableCommit
        assertNull(sink.committedRecord)
        assertEquals(0, receiver.confirmDurableQuarantineCommit(pending.receipt).accepted)

        val record = requireNotNull(sink.committedRecord)
        assertEquals(pending.receipt, record.receipt)
        assertTrue(record.directory.name.startsWith("record-"))
        assertEquals(root.canonicalFile, requireNotNull(record.directory.parentFile).canonicalFile)
        assertEquals(record.directory, onlyChild(root))
        assertArrayEquals(archive, File(record.directory, "archive.bin").readBytes())
        DataInputStream(File(record.directory, "header.bin").inputStream()).use { input ->
            assertEquals(0x58435048, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(SESSION_ID, input.readUTF())
            assertEquals(27L, input.readLong())
            assertEquals(2, input.readInt())
            assertArrayEquals(certificate, input.readMetadata())
            assertArrayEquals(signature, input.readMetadata())
            assertArrayEquals(additions, input.readMetadata())
            assertEquals(-1, input.read())
        }
        DataInputStream(File(record.directory, "receipt.bin").inputStream()).use { input ->
            assertEquals(0x58435052, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(SESSION_ID, input.readUTF())
            assertEquals(27L, input.readLong())
            assertEquals(2, input.readInt())
            assertEquals(archive.size.toLong(), input.readLong())
            assertEquals(sha256(archive), input.readUTF())
            assertEquals(certificate.size, input.readInt())
            assertEquals(sha256(certificate), input.readUTF())
            assertEquals(signature.size, input.readInt())
            assertEquals(sha256(signature), input.readUTF())
            assertEquals(additions.size, input.readInt())
            assertEquals(sha256(additions), input.readUTF())
            assertEquals(-1, input.read())
        }
        sink.abort() // A successful commit must never be deleted by a late abort.
        assertTrue(record.directory.isDirectory)
    }

    @Test
    fun absentMetadataIsRetainedAsAbsentAndNotConflatedWithEmptyBytes() {
        val root = temporaryFolder.newFolder("quarantine")
        val sink = CarPlayUpdateDataFileQuarantineSink(root)
        val receiver = CarPlayUpdateDataReceiver(sink)
        receiver.receive(codec.encode(header(1)))
        val pending = receiver.receive(codec.encode(chunk(0, byteArrayOf(42), sha256(byteArrayOf(42)))))
            as CarPlayUpdateDataReceiveResult.AwaitingDurableCommit
        assertEquals(0, receiver.confirmDurableQuarantineCommit(pending.receipt).accepted)
        assertNull(pending.receipt.certificate)
        assertNull(pending.receipt.signature)
        assertNull(pending.receipt.additions)
        DataInputStream(File(onlyChild(root), "header.bin").inputStream()).use { input ->
            input.readInt()
            input.readInt()
            input.readUTF()
            input.readLong()
            input.readInt()
            assertEquals(-1, input.readInt())
            assertEquals(-1, input.readInt())
            assertEquals(-1, input.readInt())
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun rejectsMismatchedReceiptDiskTamperingAndExcessChunkThenRetriesAfterAbort() {
        val root = temporaryFolder.newFolder("quarantine")
        val sink = CarPlayUpdateDataFileQuarantineSink(root)
        sink.begin(header(1))
        sink.append(byteArrayOf(4))
        assertFalse(sink.commit(receipt(byteArrayOf(5))))
        assertNull(sink.committedRecord)
        sink.abort()
        assertTrue(root.listFiles().isNullOrEmpty())

        sink.begin(header(1))
        sink.append(byteArrayOf(4))
        assertThrows(IOException::class.java) { sink.append(byteArrayOf(5)) }
        assertFalse(sink.commit(receipt(byteArrayOf(4))))
        sink.abort()
        assertTrue(root.listFiles().isNullOrEmpty())

        sink.begin(header(1))
        sink.append(byteArrayOf(4))
        File(onlyChild(root), "archive.bin").writeBytes(byteArrayOf(5))
        assertFalse(sink.commit(receipt(byteArrayOf(4))))
        sink.abort()
        assertTrue(root.listFiles().isNullOrEmpty())

        sink.begin(header(1))
        sink.append(byteArrayOf(4))
        assertTrue(sink.commit(receipt(byteArrayOf(4))))
        assertNotNull(sink.committedRecord)
    }

    @Test
    fun refusesRelativeOrSymlinkRootAndNeverFollowsReplacedArchive() {
        val root = temporaryFolder.newFolder("quarantine")
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUpdateDataFileQuarantineSink(File("relative-quarantine"))
        }
        val link = root.toPath().parent.resolve("linked-quarantine")
        Files.createSymbolicLink(link, root.toPath())
        assertThrows(IOException::class.java) { CarPlayUpdateDataFileQuarantineSink(link.toFile()) }

        val outside = temporaryFolder.newFile("outside.txt")
        outside.writeText("untouched")
        val sink = CarPlayUpdateDataFileQuarantineSink(root)
        sink.begin(header(1))
        sink.append(byteArrayOf(4))
        val archivePath = onlyChild(root).toPath().resolve("archive.bin")
        Files.delete(archivePath)
        Files.createSymbolicLink(archivePath, outside.toPath())
        assertThrows(IOException::class.java) { sink.commit(receipt(byteArrayOf(4))) }
        assertThrows(IOException::class.java) { sink.abort() }
        assertEquals("untouched", outside.readText())
        Files.delete(archivePath)
        sink.abort()
        sink.abort() // Retryable and idempotent.
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun failedParentFsyncReturnsNonzeroAckRemovesOnlyOwnRecordAndAllowsRetry() {
        val root = temporaryFolder.newFolder("quarantine")
        var failOnce = true
        val sink = CarPlayUpdateDataFileQuarantineSink(
            root,
            CarPlayUpdateDataLimits(),
            syncDirectory = { path ->
                if (path == root.toPath() && failOnce) {
                    failOnce = false
                    throw IOException("simulated parent fsync failure")
                }
                fsync(path)
            },
            atomicMove = { source, destination ->
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
            },
        )
        val first = CarPlayUpdateDataReceiver(sink)
        first.receive(codec.encode(header(1)))
        val pending = first.receive(codec.encode(chunk(0, byteArrayOf(7), sha256(byteArrayOf(7)))))
            as CarPlayUpdateDataReceiveResult.AwaitingDurableCommit
        assertEquals(1, first.confirmDurableQuarantineCommit(pending.receipt).accepted)
        assertNull(sink.committedRecord)
        assertTrue(root.listFiles().isNullOrEmpty())

        val second = CarPlayUpdateDataReceiver(sink)
        second.receive(codec.encode(header(1)))
        val retry = second.receive(codec.encode(chunk(0, byteArrayOf(8), sha256(byteArrayOf(8)))))
            as CarPlayUpdateDataReceiveResult.AwaitingDurableCommit
        assertEquals(0, second.confirmDurableQuarantineCommit(retry.receipt).accepted)
        assertNotNull(sink.committedRecord)
    }

    private fun header(
        count: Int,
        certificate: ByteArray? = null,
        signature: ByteArray? = null,
        additions: ByteArray? = null,
    ) = CarPlayUpdateDataMessage.Header(
        SESSION_ID, 27, count, certificate, signature, additions,
    )

    private fun chunk(index: Int, bytes: ByteArray, checksum: String? = null) =
        CarPlayUpdateDataMessage.Chunk(SESSION_ID, index, bytes, checksum)

    private fun receipt(bytes: ByteArray) = UntrustedCarPlayUpdateDataReceipt(
        SESSION_ID, 27, 1, bytes.size.toLong(), sha256(bytes), null, null, null,
    )

    private fun onlyChild(root: File): File = root.listFiles()!!.single()

    private fun DataInputStream.readMetadata(): ByteArray? {
        val count = readInt()
        return if (count == -1) null else ByteArray(count).also { readFully(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fsync(path: Path) {
        FileChannel.open(path, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
            .use { it.force(true) }
    }

    companion object {
        private const val SESSION_ID = "C0DE1234-5678-4ABC-9DEF-0123456789AB"
    }
}
