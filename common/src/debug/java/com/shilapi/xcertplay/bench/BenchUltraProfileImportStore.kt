package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.text.Normalizer
import java.util.UUID

/**
 * User-driven debug-only SAF staging. No source path/URI is persisted or accepted from an Intent.
 * A published generation has immutable archive, pin and vehicle ID; only its detached signature
 * can be atomically replaced. The runtime always re-inspects the archive and re-verifies the pin.
 */
internal class BenchUltraProfileImportStore(rootDirectory: Path) {
    private val root = rootDirectory.toAbsolutePath().normalize()
    private val inspector = UltraProfilePackageInspector()

    init { require(root.parent != null && root.parent != root) }

    data class Snapshot(
        val generation: String,
        val archive: ByteArray,
        val pin: ByteArray,
        val vehicleId: String,
        val detachedSignature: ByteArray?,
    )

    fun stageBase(archiveInput: InputStream, pinInput: InputStream, vehicleId: String): String {
        validateVehicleId(vehicleId)
        val archive = readBounded(archiveInput, MAX_ARCHIVE_BYTES)
        val pin = readBounded(pinInput, MAX_PIN_BYTES)
        require(inspector.inspect(archive.inputStream()) is UltraProfileInspectionResult.Candidate) {
            "Debug bench Profile ZIP was rejected by the package inspector"
        }
        BenchUltraProfileSignatureVerifier(pin)
        val generation = UUID.randomUUID().toString()
        // No 32 MiB staging copy is created until the old physical outputs are covered. A
        // refused ACK therefore preserves the active Profile without accumulating orphan ZIPs.
        // The observer is invoked outside this Store monitor before any new-generation write.
        BenchUltraProfileImportMutationFence.begin(root).use {
            synchronized(this) {
                ensureRoot()
                val directory = root.resolve("profile-$generation")
                var createdHere = false
                try {
                    Files.createDirectory(directory)
                    createdHere = true
                    writeNew(directory.resolve(ARCHIVE_FILE), archive)
                    writeNew(directory.resolve(PIN_FILE), pin)
                    writeNew(directory.resolve(VEHICLE_FILE),
                        vehicleId.toByteArray(StandardCharsets.UTF_8))
                    publishPointer(generation)
                } catch (failure: Throwable) {
                    // Only this freshly allocated, unpublished copy is disposable. A failed
                    // active-pointer read is ambiguous, so retain it instead of risking active
                    // or older user data. The original SAF source was never modified.
                    val active = runCatching { loadActive()?.generation }
                    if (createdHere && active.isSuccess && active.getOrNull() != generation) {
                        runCatching { discardExactUnpublishedCopy(directory) }
                            .exceptionOrNull()?.let(failure::addSuppressed)
                    }
                    throw failure
                }
            }
        }
        return generation
    }

    fun stageDetachedSignature(input: InputStream) {
        val signature = readBounded(input, MAX_SIGNATURE_BYTES)
        require(signature.isNotEmpty()) { "Detached bench signature is empty" }
        val generation = synchronized(this) {
            requireNotNull(loadActive()) { "Import Profile and pin first" }.generation
        }
        BenchUltraProfileImportMutationFence.begin(root).use {
            synchronized(this) {
                require(loadActive()?.generation == generation) {
                    "Debug bench active Profile changed before signature publication"
                }
                writeReplacing(directory(generation).resolve(SIGNATURE_FILE), signature)
            }
        }
    }

    @Synchronized
    /**
     * Cheap, read-only presence check for an active import with its detached signature. It reads
     * no archive and verifies nothing; activation still verifies the complete signed Profile.
     */
    fun hasActiveSignedGeneration(): Boolean = runCatching {
        val pointer = root.resolve(POINTER_FILE)
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) return@runCatching false
        val generation = String(readRegular(pointer, 64), StandardCharsets.US_ASCII).trimEnd('\n')
        if (!UUID_PATTERN.matches(generation)) return@runCatching false
        val directory = directory(generation)
        Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(directory) &&
            Files.isRegularFile(directory.resolve(ARCHIVE_FILE), LinkOption.NOFOLLOW_LINKS) &&
            Files.isRegularFile(directory.resolve(SIGNATURE_FILE), LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(false)

    fun loadActive(): Snapshot? {
        val pointer = root.resolve(POINTER_FILE)
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) return null
        val generation = String(readRegular(pointer, 64), StandardCharsets.US_ASCII).trimEnd('\n')
        require(UUID_PATTERN.matches(generation)) { "Debug bench import pointer is invalid" }
        val directory = directory(generation)
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(directory)) { "Debug bench import directory is unsafe" }
        val vehicleId = String(readRegular(directory.resolve(VEHICLE_FILE), 512), StandardCharsets.UTF_8)
        validateVehicleId(vehicleId)
        return Snapshot(
            generation = generation,
            archive = readRegular(directory.resolve(ARCHIVE_FILE), MAX_ARCHIVE_BYTES),
            pin = readRegular(directory.resolve(PIN_FILE), MAX_PIN_BYTES),
            vehicleId = vehicleId,
            detachedSignature = directory.resolve(SIGNATURE_FILE).let { path ->
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) readRegular(path, MAX_SIGNATURE_BYTES)
                else null
            },
        )
    }

    /** Produced only after current MFi certificate identity is observed, for external signing. */
    @Synchronized
    fun recordSigningRequest(generation: String, canonicalPayload: ByteArray) {
        require(canonicalPayload.size in 1..2_048)
        if (loadActive()?.generation != generation) return
        writeReplacing(directory(generation).resolve(REQUEST_FILE), canonicalPayload)
    }

    @Synchronized
    fun signingRequest(): ByteArray? {
        val generation = loadActive()?.generation ?: return null
        val path = directory(generation).resolve(REQUEST_FILE)
        return if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) readRegular(path, 2_048) else null
    }

    private fun publishPointer(generation: String) {
        val staged = root.resolve("active-${UUID.randomUUID()}.tmp")
        try {
            writeNew(staged, "$generation\n".toByteArray(StandardCharsets.US_ASCII))
            Files.move(staged, root.resolve(POINTER_FILE),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(staged) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun discardExactUnpublishedCopy(directory: Path) {
        require(directory.parent == root &&
            directory.fileName.toString().startsWith("profile-") &&
            !Files.isSymbolicLink(directory))
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
        listOf(ARCHIVE_FILE, PIN_FILE, VEHICLE_FILE).forEach { name ->
            Files.deleteIfExists(directory.resolve(name))
        }
        Files.deleteIfExists(directory)
    }

    private fun directory(generation: String): Path {
        require(UUID_PATTERN.matches(generation))
        return root.resolve("profile-$generation")
    }

    private fun ensureRoot() {
        val parent = root.parent ?: error("Missing import parent")
        require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent))
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(root)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root))
    }

    private fun writeNew(path: Path, bytes: ByteArray) {
        java.nio.channels.FileChannel.open(path,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun writeReplacing(path: Path, bytes: ByteArray) {
        val staged = path.parent.resolve("${path.fileName}-${UUID.randomUUID()}.tmp")
        writeNew(staged, bytes)
        Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }

    private fun readRegular(path: Path, maximum: Int): ByteArray {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "Debug bench import file is unsafe"
        }
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
            return readBounded(it, maximum)
        }
    }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maximum) { "Debug bench import exceeds byte limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun validateVehicleId(id: String) {
        require(id.isNotBlank() && id == Normalizer.normalize(id, Normalizer.Form.NFC) &&
            id.toByteArray(StandardCharsets.UTF_8).size <= 512 &&
            id.none(Char::isISOControl)) { "Debug bench vehicle ID is invalid" }
    }

    companion object {
        const val DIRECTORY = "ultra-bench-import-v1"
        private const val ARCHIVE_FILE = "profile.zip"
        private const val PIN_FILE = "p256-pin.der"
        private const val VEHICLE_FILE = "vehicle-id.txt"
        private const val SIGNATURE_FILE = "signature.der"
        private const val REQUEST_FILE = "signing-request.bin"
        private const val POINTER_FILE = "active"
        private const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
        private const val MAX_PIN_BYTES = 256
        private const val MAX_SIGNATURE_BYTES = 72
        private val UUID_PATTERN = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    }
}
