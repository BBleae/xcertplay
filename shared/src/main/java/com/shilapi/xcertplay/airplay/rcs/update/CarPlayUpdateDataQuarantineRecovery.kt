package com.shilapi.xcertplay.airplay.rcs.update

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/** A disk record is never an authenticated theme, and a prior success ACK cannot be reconstructed. */
enum class CarPlayUpdateDataQuarantineEntryKind {
    STAGING_UNCONFIRMED,
    RECORD_UNTRUSTED_ACK_UNKNOWN,
}

data class CarPlayUpdateDataQuarantineEntry(
    val directory: File,
    val kind: CarPlayUpdateDataQuarantineEntryKind,
    val fileNames: Set<String>,
    val bytes: Long,
    val newestModifiedAt: Instant,
)

data class CarPlayUpdateDataQuarantineInventory(
    val entries: List<CarPlayUpdateDataQuarantineEntry>,
    /** Sum of the three permitted file lengths, not filesystem allocation or directory metadata. */
    val bytes: Long,
) {
    val entryCount: Int get() = entries.size
}

data class CarPlayUpdateDataQuarantineQuota(
    val maxBytes: Long,
    /** Both staging and retained records consume a slot. */
    val maxEntries: Int,
) {
    init {
        require(maxBytes > 0)
        require(maxEntries > 0)
    }
}

/**
 * The root owner must hold an exclusive lock across the whole callback and establish that no
 * [CarPlayUpdateDataFileQuarantineSink] can be active. A mere observation that no sink is active
 * before the callback is insufficient. This interface deliberately has no permissive default.
 */
interface CarPlayUpdateDataNoActiveSinkGuard {
    fun <T> whileNoSinkCanBeActive(block: () -> T): T
}

/**
 * Bounded, fail-closed inventory of an existing app-private UpdateData quarantine root. This
 * class neither loads records into a product session nor interprets certificate/signature bytes.
 * Call [requireAdmission] under the same exclusive root ownership used for beginning a transfer;
 * it is a quota gate, not a disk reservation. There is deliberately no automatic startup sweep.
 */
class CarPlayUpdateDataQuarantineRecovery(root: File) {
    private val rootPath: Path
    private val rootFileKey: Any

    init {
        require(root.isAbsolute) { "Quarantine root must be absolute" }
        val path = root.toPath()
        require(path == path.normalize()) { "Quarantine root must not contain dot segments" }
        val attributes = checkedDirectory(path)
        rootPath = path
        rootFileKey = attributes.fileKey()
            ?: throw IOException("Quarantine root identity is unavailable")
    }

    fun inventory(): CarPlayUpdateDataQuarantineInventory = inventoryFrom(scan())

    /**
     * Fails before a new transfer if it would exceed either aggregate budget. [reservedBytes]
     * must include the worst-case archive, header, and receipt bytes that this transfer may write.
     * The caller must serialize this check with all writers; this method does not reserve space.
     */
    fun requireAdmission(
        reservedBytes: Long,
        quota: CarPlayUpdateDataQuarantineQuota,
    ): CarPlayUpdateDataQuarantineInventory {
        require(reservedBytes > 0) { "A positive worst-case byte reservation is required" }
        val current = inventory()
        if (current.entryCount >= quota.maxEntries || current.bytes > quota.maxBytes ||
            reservedBytes > quota.maxBytes - current.bytes
        ) throw IOException("UpdateData quarantine quota exceeded")
        return current
    }

    /**
     * Remove at most [maxDirectories] old staging directories, never committed records. The guard
     * must prove no sink can become active throughout this call. A secure directory stream is
     * required; if the filesystem cannot provide one, cleanup fails without deleting anything.
     * A failed deletion can leave a partial staging directory, and a retry is safe.
     */
    fun cleanupAbandonedStaging(
        olderThan: Instant,
        maxDirectories: Int,
        guard: CarPlayUpdateDataNoActiveSinkGuard,
    ): List<File> {
        require(maxDirectories in 1..MAX_CLEANUP_DIRECTORIES)
        return guard.whileNoSinkCanBeActive {
            val candidates = scan()
                .filter { it.entry.kind == CarPlayUpdateDataQuarantineEntryKind.STAGING_UNCONFIRMED }
                .filter { it.entry.newestModifiedAt.isBefore(olderThan) }
                .sortedWith(compareBy({ it.entry.newestModifiedAt }, { it.entry.directory.name }))
                .take(maxDirectories)
            if (candidates.isEmpty()) return@whileNoSinkCanBeActive emptyList()
            openSecureRoot().use { secureRoot ->
                candidates.map { candidate ->
                    deleteStaging(secureRoot, candidate, olderThan)
                    candidate.entry.directory
                }
            }
        }
    }

    private fun scan(): List<ScannedEntry> {
        checkRoot()
        val tokens = HashSet<String>()
        val result = ArrayList<ScannedEntry>()
        Files.newDirectoryStream(rootPath).use { stream ->
            for (path in stream) {
                if (result.size >= MAX_INVENTORY_ENTRIES) {
                    throw IOException("Too many UpdateData quarantine entries to inventory")
                }
                val name = path.fileName.toString()
                val match = ENTRY_NAME.matchEntire(name)
                    ?: throw IOException("Unexpected UpdateData quarantine entry: $name")
                if (!tokens.add(match.groupValues[2])) {
                    throw IOException("Duplicate UpdateData quarantine token")
                }
                val kind = if (match.groupValues[1] == "staging") {
                    CarPlayUpdateDataQuarantineEntryKind.STAGING_UNCONFIRMED
                } else {
                    CarPlayUpdateDataQuarantineEntryKind.RECORD_UNTRUSTED_ACK_UNKNOWN
                }
                val directory = checkedDirectory(path)
                val files = HashSet<String>()
                var bytes = 0L
                var newest = directory.lastModifiedTime().toInstant()
                Files.newDirectoryStream(path).use { children ->
                    for (child in children) {
                        val childName = child.fileName.toString()
                        if (childName !in KNOWN_FILES || !files.add(childName)) {
                            throw IOException("Unexpected UpdateData quarantine file: $childName")
                        }
                        val attributes = checkedRegularFile(child)
                        if (attributes.size() > Long.MAX_VALUE - bytes) {
                            throw IOException("UpdateData quarantine size overflow")
                        }
                        bytes += attributes.size()
                        newest = maxOf(newest, attributes.lastModifiedTime().toInstant())
                    }
                }
                if (kind == CarPlayUpdateDataQuarantineEntryKind.RECORD_UNTRUSTED_ACK_UNKNOWN &&
                    files != KNOWN_FILES
                ) throw IOException("Incomplete committed UpdateData record")
                result += ScannedEntry(
                    CarPlayUpdateDataQuarantineEntry(
                        path.toFile(), kind, files.toSet(), bytes, newest,
                    ),
                    directory.fileKey(),
                )
            }
        }
        checkRoot()
        return result.sortedBy { it.entry.directory.name }
    }

    private fun inventoryFrom(scanned: List<ScannedEntry>): CarPlayUpdateDataQuarantineInventory {
        var bytes = 0L
        for (item in scanned) {
            if (item.entry.bytes > Long.MAX_VALUE - bytes) {
                throw IOException("UpdateData quarantine size overflow")
            }
            bytes += item.entry.bytes
        }
        return CarPlayUpdateDataQuarantineInventory(scanned.map { it.entry }, bytes)
    }

    private fun deleteStaging(
        secureRoot: SecureDirectoryStream<Path>,
        scanned: ScannedEntry,
        olderThan: Instant,
    ) {
        val name = scanned.entry.directory.name
        if (!STAGING_NAME.matches(name) || scanned.fileKey == null) {
            throw IOException("Unsafe staging directory identity")
        }
        val relative = rootPath.fileSystem.getPath(name)
        val rootEntry = checkedSecureDirectory(secureRoot, relative)
        if (rootEntry.fileKey() != scanned.fileKey) {
            throw IOException("Staging directory changed during cleanup")
        }
        secureRoot.newDirectoryStream(relative, LinkOption.NOFOLLOW_LINKS).use { directory ->
            val opened = checkedSecureDirectory(directory, null)
            if (opened.fileKey() != scanned.fileKey) {
                throw IOException("Staging directory changed during cleanup")
            }
            val names = HashSet<String>()
            var newest = opened.lastModifiedTime().toInstant()
            for (path in directory) {
                val childName = path.fileName.toString()
                if (childName !in KNOWN_FILES || !names.add(childName)) {
                    throw IOException("Unexpected staging entry during cleanup")
                }
                val attributes = checkedSecureRegularFile(
                    directory, rootPath.fileSystem.getPath(childName),
                )
                newest = maxOf(newest, attributes.lastModifiedTime().toInstant())
            }
            if (!newest.isBefore(olderThan) || names != scanned.entry.fileNames) {
                throw IOException("Staging directory changed or is too recent")
            }
            // Verify all entries before removing any. Deletions remain relative to the securely
            // opened directory, so an intermediate symlink cannot redirect them outside the root.
            for (fileName in DELETION_ORDER) {
                if (fileName in names) {
                    val file = rootPath.fileSystem.getPath(fileName)
                    checkedSecureRegularFile(directory, file)
                    directory.deleteFile(file)
                }
            }
        }
        if (checkedSecureDirectory(secureRoot, relative).fileKey() != scanned.fileKey) {
            throw IOException("Staging directory changed during cleanup")
        }
        secureRoot.deleteDirectory(relative)
    }

    private fun openSecureRoot(): SecureDirectoryStream<Path> {
        checkRoot()
        val stream: DirectoryStream<Path> = Files.newDirectoryStream(rootPath)
        if (stream !is SecureDirectoryStream<*>) {
            stream.close()
            throw IOException("Secure directory cleanup is unavailable")
        }
        @Suppress("UNCHECKED_CAST")
        val secure = stream as SecureDirectoryStream<Path>
        try {
            val opened = checkedSecureDirectory(secure, null)
            if (opened.fileKey() != rootFileKey) {
                throw IOException("Quarantine root identity is unavailable or changed")
            }
        } catch (error: Exception) {
            secure.close()
            throw error
        }
        return secure
    }

    private fun checkRoot() {
        val current = checkedDirectory(rootPath)
        if (current.fileKey() != rootFileKey) {
            throw IOException("Quarantine root was replaced")
        }
    }

    private data class ScannedEntry(
        val entry: CarPlayUpdateDataQuarantineEntry,
        val fileKey: Any?,
    )

    companion object {
        private const val MAX_INVENTORY_ENTRIES = 4_096
        private const val MAX_CLEANUP_DIRECTORIES = 100
        private val KNOWN_FILES = setOf("header.bin", "archive.bin", "receipt.bin")
        private val DELETION_ORDER = listOf("receipt.bin", "archive.bin", "header.bin")
        private val ENTRY_NAME = Regex(
            "(staging|record)-([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})",
        )
        private val STAGING_NAME = Regex(
            "staging-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        )
    }
}

private fun checkedDirectory(path: Path): BasicFileAttributes =
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
        if (!it.isDirectory || it.isSymbolicLink) throw IOException("Not a quarantine directory")
    }

private fun checkedRegularFile(path: Path): BasicFileAttributes =
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
        if (!it.isRegularFile || it.isSymbolicLink) throw IOException("Not a quarantine regular file")
    }

private fun checkedSecureDirectory(
    directory: SecureDirectoryStream<Path>,
    relative: Path?,
): BasicFileAttributes {
    val view = if (relative == null) {
        directory.getFileAttributeView(BasicFileAttributeView::class.java)
    } else {
        directory.getFileAttributeView(
            relative, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
        )
    } ?: throw IOException("Quarantine directory attributes unavailable")
    return view.readAttributes().also {
        if (!it.isDirectory || it.isSymbolicLink) throw IOException("Not a quarantine directory")
    }
}

private fun checkedSecureRegularFile(
    directory: SecureDirectoryStream<Path>,
    relative: Path,
): BasicFileAttributes {
    val view = directory.getFileAttributeView(
        relative, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS,
    ) ?: throw IOException("Quarantine file attributes unavailable")
    return view.readAttributes().also {
        if (!it.isRegularFile || it.isSymbolicLink) throw IOException("Not a quarantine regular file")
    }
}
