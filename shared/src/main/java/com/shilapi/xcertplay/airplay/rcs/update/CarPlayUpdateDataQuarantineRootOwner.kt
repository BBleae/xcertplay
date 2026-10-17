package com.shilapi.xcertplay.airplay.rcs.update

import com.shilapi.xcertplay.airplay.AirPlayReadiness
import com.shilapi.xcertplay.airplay.AirPlayUpdateDataConsumerReadinessDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

/**
 * Owns an existing app-private UpdateData quarantine root across AirPlay control sessions.
 *
 * Admission, active worst-case reservations, and optional abandoned-staging cleanup share one
 * lock. Once a stream is constructed, its reservation is released only by its serial worker after
 * its sink has closed successfully. A failed abort retains the reservation for explicit retry. A success
 * ACK means durable receipt of *untrusted* bytes, never theme/profile authorization or activation.
 *
 * The caller must create the root under private app storage and retain this one owner for every
 * connection using it. A second in-process owner for the same directory is refused. This class
 * is not a cross-process lock; the root must not be shared with another process. It never creates,
 * deletes, or automatically sweeps root entries.
 */
class CarPlayUpdateDataQuarantineRootOwner private constructor(
    private val root: File,
    private val quota: CarPlayUpdateDataQuarantineQuota,
    private val limits: CarPlayUpdateDataLimits,
    private val maxConcurrentTransfers: Int,
    private val sinkFactory: (File, CarPlayUpdateDataLimits) -> CarPlayUpdateDataQuarantineSink,
    private val beforeSessionResourcePublish: () -> Unit,
) : RcsDataStreamHandlerFactory, CarPlayUpdateDataNoActiveSinkGuard, Closeable {
    constructor(
        root: File,
        quota: CarPlayUpdateDataQuarantineQuota,
        limits: CarPlayUpdateDataLimits = CarPlayUpdateDataLimits(),
        maxConcurrentTransfers: Int = 2,
    ) : this(root, quota, limits, maxConcurrentTransfers, ::CarPlayUpdateDataFileQuarantineSink, {})

    init {
        require(maxConcurrentTransfers in 1..MAX_CONCURRENT_TRANSFERS)
    }

    private val recovery = CarPlayUpdateDataQuarantineRecovery(root)
    private val reservedBytesPerTransfer = calculateReservation(limits)
    private val lock = Object()
    private val leases = IdentityHashMap<Lease, Unit>()
    private var closeRequested = false
    private var rootClaimReleased = false

    /** This exact descriptor object is stable for this owner; its ID alone is not a proof. */
    private val updateDescriptor = RcsDataStreamHandlerDescriptor(
        clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA,
        implementationId = "carplay-update-data-private-quarantine-v1",
        readinessProbe = ::probe,
        handlerFactory = ::openAuthenticatedStream,
    )

    init {
        RootOwnershipRegistry.claim(root.toPath())
    }

    override fun descriptor(clientType: RcsClientType): RcsDataStreamHandlerDescriptor? =
        updateDescriptor.takeIf { clientType == RcsClientTypes.CAR_PLAY_UPDATE_DATA }

    /** Exact descriptor identity prevents a same-ID factory from claiming this disk consumer. */
    fun ownsDescriptor(descriptor: RcsDataStreamHandlerDescriptor): Boolean =
        descriptor === updateDescriptor

    /**
     * The raw owner registration has no pre-SETUP slot reservation, so it can only provide
     * diagnostic consumer evidence. Use [tryReserveSession] for a READY feature snapshot.
     */
    fun consumerReadinessDescriptor(
        handlerDescriptor: RcsDataStreamHandlerDescriptor,
    ): AirPlayUpdateDataConsumerReadinessDescriptor {
        require(ownsDescriptor(handlerDescriptor)) {
            "UpdateData consumer proof requires this owner's exact descriptor"
        }
        return AirPlayUpdateDataConsumerReadinessDescriptor.capture(
            handlerDescriptor,
            AirPlayReadiness.UNAVAILABLE,
        )
    }

    /**
     * Atomically reserves one worst-case transfer before a control session snapshots readiness.
     * The returned exact descriptor can create at most one stream. Closing an unused lease releases
     * it immediately; after a stream is made, only its serial cleanup callback releases quota.
     */
    fun tryReserveSession(): CarPlayUpdateDataQuarantineSessionLease? {
        val lease = try {
            reserveLease()
        } catch (_: IOException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        }
        return try {
            CarPlayUpdateDataQuarantineSessionLease(lease).also { capability ->
                val alreadyClosed = synchronized(lock) {
                    lease.controller = Controller(capability::close, { false }) {
                        CarPlayUpdateDataCleanupState.RUNNING
                    }
                    closeRequested
                }
                if (alreadyClosed) {
                    capability.close()
                    throw IOException("UpdateData quarantine owner closed during reservation")
                }
            }
        } catch (error: Throwable) {
            release(lease)
            if (error is IOException) null else throw error
        }
    }

    /** One exact, quota-backed registration for one AirPlay control session. */
    inner class CarPlayUpdateDataQuarantineSessionLease internal constructor(
        private val lease: Lease,
    ) : RcsDataStreamHandlerFactory, Closeable {
        private val stateLock = Object()
        private var closeRequested = false
        private var creating = false
        private var readinessProofMinted = false
        private var resource: Closeable? = null
        private val sessionDescriptor = RcsDataStreamHandlerDescriptor(
            clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            implementationId = "carplay-update-data-reserved-quarantine-v1",
            readinessProbe = ::probe,
            handlerFactory = ::openAuthenticatedStream,
        )

        override fun descriptor(clientType: RcsClientType): RcsDataStreamHandlerDescriptor? =
            sessionDescriptor.takeIf { clientType == RcsClientTypes.CAR_PLAY_UPDATE_DATA }

        fun consumerReadinessDescriptor(
            handlerDescriptor: RcsDataStreamHandlerDescriptor,
        ): AirPlayUpdateDataConsumerReadinessDescriptor {
            require(handlerDescriptor === sessionDescriptor) {
                "UpdateData session proof requires this reservation's exact descriptor"
            }
            val readiness = synchronized(stateLock) {
                if (readinessProofMinted || closeRequested || creating || resource != null) {
                    AirPlayReadiness.UNAVAILABLE
                } else {
                    synchronized(lock) {
                        val rootReady = !this@CarPlayUpdateDataQuarantineRootOwner.closeRequested &&
                            leases.containsKey(lease) && runCatching { recovery.inventory() }.isSuccess
                        if (rootReady) {
                            readinessProofMinted = true
                            AirPlayReadiness.READY
                        } else {
                            AirPlayReadiness.UNAVAILABLE
                        }
                    }
                }
            }
            return AirPlayUpdateDataConsumerReadinessDescriptor.capture(
                handlerDescriptor,
                readiness,
            )
        }

        private fun probe(): RcsDataStreamHandlerProbe {
            if (synchronized(stateLock) { closeRequested || creating || resource != null }) {
                return RcsDataStreamHandlerProbe.notReady("session_consumed")
            }
            return synchronized(lock) {
                if (this@CarPlayUpdateDataQuarantineRootOwner.closeRequested ||
                    !leases.containsKey(lease)
                ) {
                    RcsDataStreamHandlerProbe.notReady("reservation_lost")
                } else {
                    try {
                        recovery.inventory() // verifies the exact root identity
                        RcsDataStreamHandlerProbe.ready()
                    } catch (_: Exception) {
                        RcsDataStreamHandlerProbe.notReady("quarantine_unavailable")
                    }
                }
            }
        }

        private fun openAuthenticatedStream(stream: RcsDataStream): CarPlayUpdateDataRcsHandler =
            consume {
                allocateWithLease(
                    lease,
                    create = { sink, finished ->
                        CarPlayUpdateDataRcsHandler(stream, sink, limits, finished)
                    },
                    controller = { handler ->
                        Controller(handler::close, handler::retryFailedCleanup) {
                            handler.cleanupStateSnapshot
                        }
                    },
                )
            }

        /** Test seam exercises exactly the same one-use reservation without a socket. */
        internal fun openUnboundSessionForTest(send: (RcsMessage) -> Unit = {}):
            CarPlayUpdateDataSerialSession = consume {
                allocateWithLease(
                    lease,
                    create = { sink, finished ->
                        CarPlayUpdateDataSerialSession(sink, send, limits, onFinished = finished)
                    },
                    controller = { session ->
                        Controller(session::close, session::retryFailedCleanup) {
                            session.cleanupStateSnapshot
                        }
                    },
                )
            }

        private fun <T : Closeable> consume(create: () -> T): T {
            synchronized(stateLock) {
                check(!closeRequested && !creating && resource == null) {
                    "UpdateData session reservation is closed or already consumed"
                }
                creating = true
            }
            val created = try {
                create()
            } catch (error: Throwable) {
                synchronized(stateLock) { creating = false }
                // allocateWithLease releases a pre-construction failure itself. A constructed
                // worker remains owned by the root and will release only after successful drain.
                throw error
            }
            try {
                beforeSessionResourcePublish()
            } catch (error: Throwable) {
                synchronized(stateLock) { creating = false }
                runCatching { created.close() }
                throw error
            }
            val localClose = synchronized(stateLock) {
                resource = created
                creating = false
                closeRequested
            }
            val ownerClosed = synchronized(lock) {
                this@CarPlayUpdateDataQuarantineRootOwner.closeRequested ||
                    !leases.containsKey(lease)
            }
            if (localClose || ownerClosed) {
                created.close()
                throw IOException("UpdateData session closed during stream creation")
            }
            return created
        }

        override fun close() {
            val active = synchronized(stateLock) {
                closeRequested = true
                if (creating) return
                resource
            }
            if (active != null) {
                active.close()
            } else {
                // A handler constructor may have succeeded just as owner shutdown began, then
                // failed to return it to this capability. Its serial worker still owns the sink.
                val transferStarted = synchronized(lock) {
                    leases.containsKey(lease) && lease.streamCreationStarted
                }
                if (!transferStarted) release(lease)
            }
        }
    }

    val activeTransferCount: Int get() = synchronized(lock) { leases.size }

    val reservedBytesInFlight: Long get() = synchronized(lock) {
        reservedBytesPerTransfer * leases.size
    }

    /** A bounded inventory; all returned records remain untrusted and ACK status is unknown. */
    fun inventory(): CarPlayUpdateDataQuarantineInventory = synchronized(lock) {
        recovery.inventory()
    }

    /** Explicit, guarded cleanup only. No startup or admission path deletes staging data. */
    fun cleanupAbandonedStaging(olderThan: Instant, maxDirectories: Int): List<File> =
        recovery.cleanupAbandonedStaging(olderThan, maxDirectories, this)

    override fun <T> whileNoSinkCanBeActive(block: () -> T): T = synchronized(lock) {
        if (closeRequested || leases.isNotEmpty()) {
            throw IOException("UpdateData quarantine root has active or closing sinks")
        }
        block()
    }

    /** Payloads reach this handler only after the RCS transport authenticates its peer. */
    private fun openAuthenticatedStream(stream: RcsDataStream): CarPlayUpdateDataRcsHandler =
        allocate(
            create = { sink, finished ->
                CarPlayUpdateDataRcsHandler(stream, sink, limits, finished)
            },
            controller = { handler ->
                Controller(handler::close, handler::retryFailedCleanup) {
                    handler.cleanupStateSnapshot
                }
            },
        )

    /** Deterministic seam for testing admission and worker cleanup without an RCS socket. */
    internal fun openUnboundSessionForTest(send: (RcsMessage) -> Unit = {}):
        CarPlayUpdateDataSerialSession = allocate(
        create = { sink, finished ->
            CarPlayUpdateDataSerialSession(sink, send, limits, onFinished = finished)
        },
        controller = { session ->
            Controller(session::close, session::retryFailedCleanup) {
                session.cleanupStateSnapshot
            }
        },
    )

    /** Retry retained failed cleanups off the RCS callback thread; never delete other records. */
    fun retryFailedCleanups(): Int {
        val failed = synchronized(lock) {
            leases.keys.mapNotNull { lease -> lease.controller }
                .filter { it.state() == CarPlayUpdateDataCleanupState.FAILED }
        }
        return failed.count { runCatching { it.retryCleanup() }.getOrDefault(false) }
    }

    /** True only once every worker and its sink cleanup callback has drained. */
    fun awaitDrained(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        val started = System.nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        synchronized(lock) {
            while (leases.isNotEmpty()) {
                val remaining = budget - (System.nanoTime() - started)
                if (remaining <= 0) return false
                TimeUnit.NANOSECONDS.timedWait(lock, remaining)
            }
            return true
        }
    }

    /** Signals handlers without waiting for fsync; reservations survive any failed cleanup. */
    override fun close() {
        val controllers = synchronized(lock) {
            closeRequested = true
            maybeReleaseRootClaimLocked()
            leases.keys.mapNotNull { it.controller }
        }
        var failure: Throwable? = null
        controllers.forEach { controller ->
            runCatching { controller.close() }.exceptionOrNull()?.let { error ->
                if (failure == null) failure = error else if (failure !== error) {
                    failure?.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun probe(): RcsDataStreamHandlerProbe = synchronized(lock) {
        if (closeRequested) return@synchronized RcsDataStreamHandlerProbe.notReady("owner_closed")
        try {
            checkAdmissionLocked()
            RcsDataStreamHandlerProbe.ready()
        } catch (_: Exception) {
            RcsDataStreamHandlerProbe.notReady("quarantine_unavailable")
        }
    }

    private fun reserveLease(): Lease = synchronized(lock) {
        check(!closeRequested) { "UpdateData quarantine owner is closed" }
        checkAdmissionLocked()
        Lease().also { leases[it] = Unit }
    }

    private fun <T : Closeable> allocate(
        create: (CarPlayUpdateDataQuarantineSink, () -> Unit) -> T,
        controller: (T) -> Controller,
    ): T = allocateWithLease(reserveLease(), create, controller)

    private fun <T : Closeable> allocateWithLease(
        lease: Lease,
        create: (CarPlayUpdateDataQuarantineSink, () -> Unit) -> T,
        controller: (T) -> Controller,
    ): T {
        var created: T? = null
        try {
            synchronized(lock) {
                check(!closeRequested && leases.containsKey(lease)) {
                    "UpdateData reservation is closed"
                }
                check(!lease.streamCreationStarted) {
                    "UpdateData reservation has already been transferred to a stream"
                }
                lease.streamCreationStarted = true
            }
            val sink = sinkFactory(root, limits)
            lease.sink = sink
            // The sink and the inventory must refer to the exact directory claimed at owner
            // construction; replacement between the initial gate and sink creation fails closed.
            recovery.inventory()
            val result = create(sink) { release(lease) }
            created = result
            val wasClosed = synchronized(lock) {
                lease.controller = controller(result)
                closeRequested
            }
            if (wasClosed) {
                // The caller receives a closed handler, or its session capability turns this
                // into a failure. Either way only its worker may release the reservation.
                runCatching { result.close() }
            }
            return result
        } catch (error: Throwable) {
            if (created == null) release(lease)
            // A constructed serial worker owns the sink. Its asynchronous callback alone may
            // release the reservation, even if its handler could not be returned to the caller.
            throw error
        }
    }

    private fun checkAdmissionLocked() {
        if (leases.size >= maxConcurrentTransfers) {
            throw IOException("Too many concurrent UpdateData transfers")
        }
        val activeReserved = try {
            Math.multiplyExact(reservedBytesPerTransfer, leases.size.toLong())
        } catch (_: ArithmeticException) {
            throw IOException("UpdateData reservation overflow")
        }
        val requested = try {
            Math.addExact(activeReserved, reservedBytesPerTransfer)
        } catch (_: ArithmeticException) {
            throw IOException("UpdateData reservation overflow")
        }
        val inventory = recovery.inventory()
        val ownedTokens = leases.keys.mapNotNullTo(HashSet()) { lease ->
            (lease.sink as? CarPlayUpdateDataFileQuarantineSink)?.ownedEntryToken
        }
        val ownedEntries = inventory.entries.filter { entry ->
            entry.directory.name.substringAfter('-', "") in ownedTokens
        }
        val ownedBytes = ownedEntries.sumOf { it.bytes }
        val outsideBytes = inventory.bytes - ownedBytes
        // The file sink's random token identifies at most its own staging/record entry. A token
        // not yet published, or an injected sink without one, stays counted conservatively.
        if (outsideBytes > quota.maxBytes || requested > quota.maxBytes - outsideBytes) {
            throw IOException("UpdateData quarantine byte quota exceeded")
        }
        val outsideEntries = inventory.entryCount - ownedEntries.size
        if (outsideEntries + leases.size >= quota.maxEntries) {
            throw IOException("UpdateData quarantine entry quota exceeded")
        }
    }

    private fun release(lease: Lease) = synchronized(lock) {
        if (leases.remove(lease) == null) return@synchronized
        maybeReleaseRootClaimLocked()
        lock.notifyAll()
    }

    private fun maybeReleaseRootClaimLocked() {
        if (closeRequested && leases.isEmpty() && !rootClaimReleased) {
            RootOwnershipRegistry.release(root.toPath())
            rootClaimReleased = true
        }
    }

    internal class Lease(
        @Volatile var sink: CarPlayUpdateDataQuarantineSink? = null,
        var controller: Controller? = null,
        var streamCreationStarted: Boolean = false,
    )

    internal class Controller(
        val close: () -> Unit,
        val retryCleanup: () -> Boolean,
        val state: () -> CarPlayUpdateDataCleanupState,
    )

    companion object {
        private const val MAX_CONCURRENT_TRANSFERS = 16

        internal fun forTesting(
            root: File,
            quota: CarPlayUpdateDataQuarantineQuota,
            limits: CarPlayUpdateDataLimits,
            maxConcurrentTransfers: Int,
            beforeSessionResourcePublish: () -> Unit = {},
            sinkFactory: (File, CarPlayUpdateDataLimits) -> CarPlayUpdateDataQuarantineSink,
        ): CarPlayUpdateDataQuarantineRootOwner = CarPlayUpdateDataQuarantineRootOwner(
            root, quota, limits, maxConcurrentTransfers, sinkFactory,
            beforeSessionResourcePublish,
        )

        private fun calculateReservation(limits: CarPlayUpdateDataLimits): Long = try {
            // header.bin contains at most three bounded metadata fields; 4 KiB covers its fixed
            // fields, session UUID, receipt.bin, hashes and binary encoding overhead.
            val metadata = Math.multiplyExact(limits.maxMetadataBytes.toLong(), 3L)
            Math.addExact(Math.addExact(limits.maxArchiveBytes, metadata), 4_096L)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("UpdateData bounds cannot be reserved")
        }
    }
}

/** Process-local fail-closed ownership of one physical quarantine directory. */
private object RootOwnershipRegistry {
    private val roots = LinkedHashSet<Path>()

    @Synchronized
    fun claim(path: Path) {
        if (roots.any { existing ->
                existing == path || runCatching { Files.isSameFile(existing, path) }
                    .getOrDefault(false)
            }
        ) throw IOException("UpdateData quarantine root already has an owner")
        roots.add(path)
    }

    @Synchronized
    fun release(path: Path) {
        roots.remove(path)
    }
}
