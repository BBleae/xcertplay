package com.shilapi.xcertplay.airplay.rcs.update

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/**
 * A durable quarantine record, not an installed or authenticated CarPlay theme. Every byte in the
 * directory, including the purported certificate and signature, remains untrusted input.
 */
data class UntrustedCarPlayUpdateDataQuarantineRecord(
    val directory: File,
    val receipt: UntrustedCarPlayUpdateDataReceipt,
)

/**
 * Stores one UpdateData archive in a caller-owned, existing, private, absolute quarantine directory.
 * No sender-controlled value becomes a path component. The binary `header.bin` retains the exact
 * optional certificate, signature and additions bytes; `receipt.bin` records the checksum result.
 * Neither file is an authorization decision, and this class never activates a profile.
 *
 * A successful commit requires file and directory fsync plus an atomic rename. Filesystems which
 * do not provide those operations fail closed: they cannot produce ACK 0 through this sink. The
 * caller must keep the root private from other writers and separately manage crash-left staging
 * directories; this sink never sweeps unrelated entries in the root.
 */
class CarPlayUpdateDataFileQuarantineSink internal constructor(
    root: File,
    private val limits: CarPlayUpdateDataLimits,
    private val syncDirectory: (Path) -> Unit,
    private val atomicMove: (Path, Path) -> Unit,
) : CarPlayUpdateDataQuarantineSink {
    constructor(
        root: File,
        limits: CarPlayUpdateDataLimits = CarPlayUpdateDataLimits(),
    ) : this(root, limits, ::fsyncDirectory, ::moveDirectoryAtomically)

    private val rootPath: Path
    private val rootFileKey: Any?
    private var active: Active? = null
    private var committedRecordValue: UntrustedCarPlayUpdateDataQuarantineRecord? = null
    @Volatile private var ownedEntryTokenValue: String? = null

    /** Random sink-owned basename suffix, used only to avoid double-counting a live reservation. */
    internal val ownedEntryToken: String? get() = ownedEntryTokenValue

    /** The last committed record is still untrusted and must not be rendered or installed. */
    val committedRecord: UntrustedCarPlayUpdateDataQuarantineRecord?
        @Synchronized get() = committedRecordValue

    init {
        require(root.isAbsolute) { "Quarantine root must be absolute" }
        val path = root.toPath()
        require(path == path.normalize()) { "Quarantine root must not contain dot segments" }
        val attributes = directoryAttributes(path)
        rootPath = path
        rootFileKey = attributes.fileKey()
    }

    @Synchronized
    override fun begin(header: CarPlayUpdateDataMessage.Header) {
        check(active == null) { "A quarantine transfer is already active" }
        checkRoot()
        require(UUID_PATTERN.matches(header.sessionId)) { "Invalid UpdateData session ID" }
        require(header.version in 0..limits.maxVersion) { "Invalid UpdateData version" }
        require(header.chunkCount in 1..limits.maxChunkCount) { "Invalid UpdateData chunk count" }
        require(header.chunkCount.toLong() <= limits.maxArchiveBytes) {
            "UpdateData chunk count exceeds archive budget"
        }
        val certificate = checkedMetadata(header.certificate)
        val signature = checkedMetadata(header.signature)
        val additions = checkedMetadata(header.additions)
        val encodedHeader = encodeHeader(header, certificate, signature, additions)
        val token = UUID.randomUUID().toString()
        val staging = rootPath.resolve("staging-$token")
        val destination = rootPath.resolve("record-$token")
        Files.createDirectory(staging)
        val stagingFileKey = try {
            directoryAttributes(staging).fileKey()
        } catch (error: Exception) {
            Files.deleteIfExists(staging)
            throw error
        }
        val record = Active(
            staging = staging,
            destination = destination,
            location = staging,
            directoryFileKey = stagingFileKey,
            sessionId = header.sessionId,
            version = header.version,
            chunkCount = header.chunkCount,
            encodedHeader = encodedHeader,
            certificate = digestMetadata(certificate),
            signature = digestMetadata(signature),
            additions = digestMetadata(additions),
        )
        active = record // An exception below leaves a retryable abort target.
        ownedEntryTokenValue = token
        committedRecordValue = null
        writeNewSyncedFile(staging.resolve(HEADER_FILE), encodedHeader)
        record.archiveChannel = openNewFile(staging.resolve(ARCHIVE_FILE))
    }

    @Synchronized
    override fun append(bytes: ByteArray) {
        val record = requireActive()
        check(!record.poisoned) { "Quarantine transfer failed" }
        checkDirectory(record)
        if (bytes.isEmpty() || bytes.size > limits.maxChunkBytes ||
            record.appendCount >= record.chunkCount ||
            bytes.size.toLong() > limits.maxArchiveBytes - record.archiveBytes
        ) {
            record.poisoned = true
            throw IOException("UpdateData archive bounds exceeded")
        }
        val channel = record.archiveChannel ?: throw IOException("Archive is not open")
        val copy = bytes.copyOf()
        try {
            writeAll(channel, copy)
            record.archiveDigest.update(copy)
            record.archiveBytes += copy.size
            record.appendCount++
        } catch (error: Exception) {
            record.poisoned = true
            throw error
        }
    }

    /** False or an exception means no success ACK may be sent; the caller must invoke abort. */
    @Synchronized
    override fun commit(receipt: UntrustedCarPlayUpdateDataReceipt): Boolean {
        val record = requireActive()
        if (record.poisoned || record.appendCount != record.chunkCount) return false
        checkDirectory(record)
        val channel = record.archiveChannel ?: return false
        channel.force(true)
        if (channel.size() != record.archiveBytes || !matchesHeader(record)) {
            record.poisoned = true
            return false
        }
        val streamedDigest = record.archiveDigest.digest().toLowerHex()
        val diskDigest = digestFile(record.location.resolve(ARCHIVE_FILE), limits.maxArchiveBytes)
        if (diskDigest.first != record.archiveBytes || diskDigest.second != streamedDigest ||
            receipt.sessionId != record.sessionId || receipt.version != record.version ||
            receipt.chunkCount != record.chunkCount || receipt.archiveBytes != record.archiveBytes ||
            receipt.archiveSha256 != diskDigest.second || receipt.certificate != record.certificate ||
            receipt.signature != record.signature || receipt.additions != record.additions
        ) {
            record.poisoned = true
            return false
        }

        writeNewSyncedFile(record.location.resolve(RECEIPT_FILE), encodeReceipt(receipt))
        channel.close()
        record.archiveChannel = null
        syncDirectory(record.location)
        checkRoot()
        checkDirectory(record)
        if (Files.exists(record.destination, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Quarantine destination already exists")
        }
        try {
            atomicMove(record.staging, record.destination)
        } catch (error: IOException) {
            // An injected/provider failure can occur after the rename. Only an inode match lets
            // abort treat that destination as its own; otherwise leave it untouched for recovery.
            if (record.directoryFileKey != null &&
                try {
                    directoryAttributes(record.destination).fileKey() == record.directoryFileKey
                } catch (_: IOException) {
                    false
                }
            ) record.location = record.destination
            if (error is AtomicMoveNotSupportedException) {
                throw IOException("Atomic quarantine rename is unavailable", error)
            }
            throw error
        }
        record.location = record.destination
        syncDirectory(rootPath)
        committedRecordValue = UntrustedCarPlayUpdateDataQuarantineRecord(
            record.destination.toFile(), receipt,
        )
        active = null
        return true
    }

    /** Only this instance's random staging/failed-commit directory and its named files are removed. */
    @Synchronized
    override fun abort() {
        val record = active ?: return
        val channel = record.archiveChannel
        if (channel != null) {
            channel.close()
            record.archiveChannel = null
        }
        checkRoot()
        val location = record.location
        if (Files.exists(location, LinkOption.NOFOLLOW_LINKS)) {
            checkDirectory(record)
            deleteRegularFileIfPresent(location.resolve(RECEIPT_FILE))
            deleteRegularFileIfPresent(location.resolve(ARCHIVE_FILE))
            deleteRegularFileIfPresent(location.resolve(HEADER_FILE))
            Files.delete(location) // Unknown entries are never swept or followed.
        }
        syncDirectory(rootPath)
        active = null
    }

    private fun requireActive(): Active = active ?: throw IllegalStateException("No active transfer")

    private fun checkRoot() {
        val attributes = directoryAttributes(rootPath)
        if (rootFileKey != null && attributes.fileKey() != null &&
            rootFileKey != attributes.fileKey()
        ) throw IOException("Quarantine root was replaced")
    }

    private fun checkDirectory(record: Active) {
        checkRoot()
        if (record.location.parent != rootPath ||
            (record.location != record.staging && record.location != record.destination)
        ) throw IOException("Quarantine path escaped its root")
        val attributes = directoryAttributes(record.location)
        if (record.directoryFileKey != null && attributes.fileKey() != null &&
            record.directoryFileKey != attributes.fileKey()
        ) throw IOException("Quarantine directory was replaced")
    }

    private fun matchesHeader(record: Active): Boolean {
        val path = record.location.resolve(HEADER_FILE)
        val attributes = regularFileAttributes(path)
        if (attributes.size() != record.encodedHeader.size.toLong()) return false
        val actual = ByteArray(record.encodedHeader.size)
        FileChannel.open(path, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use {
            channel ->
            if (channel.size() != actual.size.toLong()) return false
            val buffer = ByteBuffer.wrap(actual)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) return false
            }
        }
        return MessageDigest.isEqual(actual, record.encodedHeader)
    }

    private fun checkedMetadata(bytes: ByteArray?): ByteArray? = bytes?.also {
        require(it.isNotEmpty() && it.size <= limits.maxMetadataBytes) {
            "UpdateData metadata exceeds bounds"
        }
    }

    private class Active(
        val staging: Path,
        val destination: Path,
        var location: Path,
        val directoryFileKey: Any?,
        val sessionId: String,
        val version: Long,
        val chunkCount: Int,
        val encodedHeader: ByteArray,
        val certificate: CarPlayUpdateDataMetadataDigest?,
        val signature: CarPlayUpdateDataMetadataDigest?,
        val additions: CarPlayUpdateDataMetadataDigest?,
    ) {
        var archiveChannel: FileChannel? = null
        val archiveDigest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var appendCount = 0
        var archiveBytes = 0L
        var poisoned = false
    }

    companion object {
        private const val HEADER_FILE = "header.bin"
        private const val ARCHIVE_FILE = "archive.bin"
        private const val RECEIPT_FILE = "receipt.bin"
        private val UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
    }
}

private fun encodeHeader(
    header: CarPlayUpdateDataMessage.Header,
    certificate: ByteArray?,
    signature: ByteArray?,
    additions: ByteArray?,
): ByteArray = ByteArrayOutputStream().also { buffer ->
    DataOutputStream(buffer).use { output ->
        output.writeInt(0x58435048)
        output.writeInt(1)
        output.writeUTF(header.sessionId)
        output.writeLong(header.version)
        output.writeInt(header.chunkCount)
        for (metadata in arrayOf(certificate, signature, additions)) {
            output.writeInt(metadata?.size ?: -1)
            metadata?.let { output.write(it) }
        }
    }
}.toByteArray()

private fun encodeReceipt(receipt: UntrustedCarPlayUpdateDataReceipt): ByteArray =
    ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(0x58435052)
            output.writeInt(1)
            output.writeUTF(receipt.sessionId)
            output.writeLong(receipt.version)
            output.writeInt(receipt.chunkCount)
            output.writeLong(receipt.archiveBytes)
            output.writeUTF(receipt.archiveSha256)
            for (metadata in arrayOf(receipt.certificate, receipt.signature, receipt.additions)) {
                output.writeInt(metadata?.byteCount ?: -1)
                metadata?.let { output.writeUTF(it.sha256) }
            }
        }
    }.toByteArray()

private fun digestMetadata(bytes: ByteArray?): CarPlayUpdateDataMetadataDigest? = bytes?.let {
    CarPlayUpdateDataMetadataDigest(it.size, MessageDigest.getInstance("SHA-256").digest(it).toLowerHex())
}

private fun digestFile(path: Path, maxBytes: Long): Pair<Long, String> {
    regularFileAttributes(path)
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    FileChannel.open(path, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use {
        channel ->
        val buffer = ByteBuffer.allocate(64 * 1024)
        while (true) {
            val read = channel.read(buffer)
            if (read == -1) break
            if (read == 0) throw IOException("Quarantine read made no progress")
            buffer.flip()
            size += buffer.remaining()
            if (size > maxBytes) throw IOException("Quarantine archive exceeded byte budget")
            digest.update(buffer)
            buffer.clear()
        }
    }
    return size to digest.digest().toLowerHex()
}

private fun ByteArray.toLowerHex(): String = joinToString("") { byte ->
    val value = byte.toInt() and 0xff
    "0123456789abcdef"[value ushr 4].toString() + "0123456789abcdef"[value and 0x0f]
}

private fun writeNewSyncedFile(path: Path, bytes: ByteArray) {
    openNewFile(path).use { channel ->
        writeAll(channel, bytes)
        channel.force(true)
    }
}

private fun writeAll(channel: FileChannel, bytes: ByteArray) {
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) {
        if (channel.write(buffer) <= 0) throw IOException("Quarantine write made no progress")
    }
}

private fun openNewFile(path: Path): FileChannel = FileChannel.open(
    path, setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
)

private fun directoryAttributes(path: Path): BasicFileAttributes =
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
        if (!it.isDirectory || it.isSymbolicLink) throw IOException("Quarantine path is not a directory")
    }

private fun regularFileAttributes(path: Path): BasicFileAttributes =
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
        if (!it.isRegularFile || it.isSymbolicLink) throw IOException("Quarantine path is not a regular file")
    }

private fun deleteRegularFileIfPresent(path: Path) {
    try {
        regularFileAttributes(path)
    } catch (_: NoSuchFileException) {
        return
    }
    Files.delete(path)
}

private fun fsyncDirectory(path: Path) {
    FileChannel.open(path, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use {
        it.force(true)
    }
}

private fun moveDirectoryAtomically(source: Path, destination: Path) {
    Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
}
