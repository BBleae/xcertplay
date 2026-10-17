package com.shilapi.xcertplay.airplay.mainbuffered

import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The two observed v27 response carriers, selected by the session's frozen capability. */
internal enum class MainBufferedV27PortMode {
    /** SupportsAudioStreamConnectionSetup=false: `dataPort` is in the stream entry. */
    DATA_PORT,

    /** SupportsAudioStreamConnectionSetup=true: the APAP stream-connection dictionary is used. */
    STREAM_CONNECTIONS_APAP,
}

/** Exactly one format that the downstream decoder and sink explicitly agree to accept. */
internal data class MainBufferedV27AcceptedFormat(
    val contentType: Long,
    val samplesPerFrame: Long,
    val audioFormat: Long,
) {
    init {
        require(contentType in 0..UINT32_MAX) { "accepted ct must be a UInt32" }
        require(samplesPerFrame in 1..UINT32_MAX) { "accepted spf must be a positive UInt32" }
        MainBufferedAudioFormatBits.requireSingleBit(audioFormat, "accepted audioFormat")
    }
}

internal enum class MainBufferedV27SetupState {
    PREPARED,
    FLUSHING,
    COMMITTED,
    RETIRING,
    CLOSED,
}

/**
 * One authenticated AirPlay session generation's *detached* type-103 SETUP ownership.
 *
 * The caller must supply the actual authenticated peer, the address on that session's transport,
 * a receiver-allocated stream ID, the frozen SupportsAudioStreamConnectionSetup mode, and a
 * buffer size reported by its real downstream sink. `audioBufferSize` deliberately has no assumed
 * unit here. This scope neither asserts that a decoder/sink exists nor advertises mainBuffered.
 * A session teardown or generation replacement must close this scope; it cannot be re-armed.
 */
internal class MainBufferedV27SetupSession private constructor(
    val generation: Long,
    /** Exact ingress identity injectable before PCM output and mctl control owners are built. */
    val ingressIdentity: MainBufferedIngressIdentity,
    private val bindAddress: InetAddress,
    private val expectedPeer: InetAddress,
    private val allocatedStreamId: Long,
    private val acceptedFormat: MainBufferedV27AcceptedFormat,
    private val audioBufferSize: Long,
    private val portMode: MainBufferedV27PortMode,
    private val packetConsumer: MainBufferedIngressPacketConsumer,
    private val ingressLimits: MainBufferedIngressLimits,
    private val tcpLimits: MainBufferedTcpIngressLimits,
    private val tcpIngressHooks: MainBufferedTcpIngressHooks,
    private val mctlPortPreparer: MainBufferedMctlPortPreparer?,
    private val playbackOwner: MainBufferedPcm16PlaybackEpochOwner?,
    private val lifecycleRetirement: Closeable?,
    private val claimedLease: MainBufferedM4FlushedSessionLease?,
    private val claimedRequestMarker: Any?,
) : Closeable {
    internal val lock = Any()
    private val playbackRetirementLock = Any()
    private val lifecycleRetirementLock = Any()
    private val preparationRetirementLock = Any()
    private val identity = ingressIdentity
    private var retired = false
    private var transaction: MainBufferedV27SetupTransaction? = null
    private var preparation: PreparationSlot? = null
    private var pendingPreparationListener: MainBufferedTcpIngressListener? = null
    private var pendingPreparationMctlPort: MainBufferedPreparedMctlPort? = null
    private var pendingPreparationListenerRollbackAttempted = false
    private var playbackOwnerRetired = playbackOwner == null
    private var lifecycleRetired = lifecycleRetirement == null

    /** A close may cancel preparation without waiting behind either socket bind. */
    private class PreparationSlot {
        val thread: Thread = Thread.currentThread()
        val complete = CountDownLatch(1)
        var cancelled = false // owner.lock
    }

    /**
     * Snapshot and validate one sender request, then reserve APAP and, only if requested, an
     * independent MediaDataControl port. Neither transport admits a peer before the complete
     * SETUP response is flushed and both deferred commits have succeeded.
     */
    fun prepare(request: Any?): MainBufferedV27SetupTransaction =
        MainBufferedV27RequestTicket.freeze(request).use(::prepare)

    /** Uses the same request snapshot that was admitted by the M4 child-lease ledger. */
    fun prepare(ticket: MainBufferedV27RequestTicket): MainBufferedV27SetupTransaction =
        ticket.withRawKey { parsed, rawKey ->
            val mctlSeedBits: Long?
            val slot = synchronized(lock) {
                check(!retired) { "type-103 SETUP session has retired" }
                check(transaction == null && preparation == null) {
                    "type-103 SETUP is one-shot for this session generation"
                }
                check(claimedLease == null ||
                    (claimedRequestMarker != null && ticket.isBoundTo(claimedRequestMarker))) {
                    "type-103 SETUP request changed after its M4 lease was claimed"
                }
                require(parsed.supportsDynamicStreamId) {
                    "type-103 SETUP sender must support a receiver-allocated streamID"
                }
                require(parsed.packetFormatApap) { "type-103 SETUP must select APAP packet format" }
                require(parsed.contentType == acceptedFormat.contentType) {
                    "type-103 SETUP ct does not match the accepted downstream format"
                }
                require(parsed.samplesPerFrame == acceptedFormat.samplesPerFrame) {
                    "type-103 SETUP spf does not match the accepted downstream format"
                }
                require(parsed.audioFormat == acceptedFormat.audioFormat) {
                    "type-103 SETUP audioFormat does not match the accepted downstream format"
                }
                mctlSeedBits = validateRequestConnections(parsed)
                require(parsed.streamConnectionId == ticket.key.connectionIdBits &&
                    mctlSeedBits == ticket.key.mctlSeedBits
                ) { "type-103 SETUP request changed after its M4 lease was claimed" }
                require(mctlSeedBits == null || mctlPortPreparer != null) {
                    "type-103 SETUP requested MediaDataControl without a prepared transport"
                }

                PreparationSlot().also { preparation = it }
            }
            var listener: MainBufferedTcpIngressListener? = null
            var mctlPort: MainBufferedPreparedMctlPort? = null
            try {
                // The unique PREPARING slot is visible to close(), but neither socket bind nor
                // rollback runs under owner.lock. Cancellation is checked between both binds.
                requirePreparationCurrent(slot)
                listener = MainBufferedTcpIngressListener.prepare(
                    bindAddress = bindAddress,
                    expectedPeer = expectedPeer,
                    identity = identity,
                    sessionKey = rawKey,
                    packetConsumer = packetConsumer,
                    ingressLimits = ingressLimits,
                    tcpLimits = tcpLimits,
                    hooks = tcpIngressHooks,
                )
                requirePreparationCurrent(slot)
                mctlPort = if (mctlSeedBits != null) {
                    checkNotNull(mctlPortPreparer).prepare(
                        MainBufferedMctlPortRequest(
                            generation = generation,
                            bindAddress = bindAddress,
                            expectedPeer = expectedPeer,
                            streamConnectionIdBits = parsed.streamConnectionId,
                            encryptionSeedBits = mctlSeedBits,
                        ),
                    )
                } else {
                    null
                }
                requirePreparationCurrent(slot)
                if (mctlPort != null) {
                    require(mctlPort.localAddress.address == bindAddress &&
                        mctlPort.localAddress.port in 1..65535 &&
                        mctlPort.localAddress.port != listener.localAddress.port
                    ) { "type-103 SETUP MediaDataControl must reserve a distinct valid port" }
                    require(mctlPort.generation == generation &&
                        mctlPort.expectedPeer == expectedPeer
                    ) { "type-103 SETUP MediaDataControl lease does not match the session peer" }
                }
                val announcedMctlPort = mctlPort?.localAddress?.port
                val prepared = MainBufferedV27SetupTransaction(
                    owner = this,
                    listener = listener,
                    mctlPort = mctlPort,
                    announcedMctlPort = announcedMctlPort,
                    responseBody = responseBody(
                        parsed.streamConnectionId,
                        listener.localAddress.port,
                        announcedMctlPort,
                    ),
                )
                requirePreparationCurrent(slot)
                synchronized(lock) {
                    check(!retired && preparation === slot && !slot.cancelled) {
                        "type-103 SETUP preparation was cancelled before publication"
                    }
                    transaction = prepared
                    preparation = null
                    slot.complete.countDown()
                }
                prepared
            } catch (problem: Throwable) {
                synchronized(lock) {
                    retired = true
                    slot.cancelled = true
                }
                try { retirePlaybackOwner() } catch (closeProblem: Throwable) {
                    if (closeProblem !== problem) problem.addSuppressed(closeProblem)
                }
                try {
                    synchronized(preparationRetirementLock) {
                        pendingPreparationListener = listener
                        pendingPreparationMctlPort = mctlPort
                        retirePendingPreparationPorts()
                    }
                } catch (closeProblem: Throwable) {
                    if (closeProblem !== problem) problem.addSuppressed(closeProblem)
                }
                try { retireLifecycleOwner() } catch (closeProblem: Throwable) {
                    if (closeProblem !== problem) problem.addSuppressed(closeProblem)
                }
                throw problem
            } finally {
                synchronized(lock) {
                    if (preparation === slot) {
                        preparation = null
                        slot.complete.countDown()
                    }
                }
            }
        }

    /** Never call an external session-liveness callback while holding owner.lock. */
    private fun requirePreparationCurrent(slot: PreparationSlot) {
        check(claimedLease?.isCurrent(generation) != false) {
            "type-103 SETUP M4 lease retired during port preparation"
        }
        synchronized(lock) {
            check(!retired && preparation === slot && !slot.cancelled) {
                "type-103 SETUP preparation was cancelled"
            }
        }
    }

    private fun validateRequestConnections(request: MainBufferedSetupRequest): Long? {
        val connections = request.optionalValues["streamConnections"]
        return when (portMode) {
            MainBufferedV27PortMode.DATA_PORT -> {
                require(connections == null) {
                    "type-103 SETUP streamConnections contradicts the frozen dataPort mode"
                }
                null
            }
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP -> {
                val entries = connections as? Map<*, *>
                    ?: throw IllegalArgumentException(
                        "type-103 SETUP streamConnections must be a dictionary in APAP mode",
                    )
                require(entries["streamConnectionTypeAPAP"] is Map<*, *>) {
                    "type-103 SETUP streamConnectionTypeAPAP must be a dictionary"
                }
                require(entries.keys == setOf("streamConnectionTypeAPAP") ||
                    entries.keys == setOf(
                        "streamConnectionTypeAPAP",
                        "streamConnectionTypeMediaDataControl",
                    )
                ) {
                    "type-103 SETUP requests a connection type without a prepared listener"
                }
                // The APAP inner dictionary may carry key-holder seed/selection metadata;
                // neither value is interchangeable with the raw 32-byte `shk`. This detached
                // APAP ingress preserves them opaquely and uses only `shk` for its data key.
                if (!entries.containsKey("streamConnectionTypeMediaDataControl")) {
                    null
                } else {
                    val mctl = entries["streamConnectionTypeMediaDataControl"] as? Map<*, *>
                        ?: throw IllegalArgumentException(
                            "type-103 SETUP MediaDataControl must be a dictionary",
                        )
                    require(mctl.keys == setOf("streamConnectionKeyEncryptionSeed")) {
                        "type-103 SETUP MediaDataControl must provide only its UInt64 seed"
                    }
                    rawUInt64Bits(
                        mctl["streamConnectionKeyEncryptionSeed"],
                        "type-103 SETUP MediaDataControl encryption seed",
                    )
                }
            }
        }
    }

    private fun responseBody(connectionId: Long, port: Int, mctlPort: Int?): Map<String, Any?> {
        val stream = linkedMapOf<String, Any?>(
            "type" to MainBufferedSetupRequest.STREAM_TYPE,
            "streamID" to allocatedStreamId,
            "streamConnectionID" to wireUInt64(connectionId),
            "audioBufferSize" to audioBufferSize,
        )
        when (portMode) {
            MainBufferedV27PortMode.DATA_PORT -> stream["dataPort"] = port.toLong()
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP -> {
                val connections = linkedMapOf<String, Any?>(
                    "streamConnectionTypeAPAP" to Collections.unmodifiableMap(
                        linkedMapOf<String, Any?>("streamConnectionKeyPort" to port.toLong()),
                    ),
                )
                if (mctlPort != null) {
                    connections["streamConnectionTypeMediaDataControl"] = Collections.unmodifiableMap(
                        linkedMapOf<String, Any?>("streamConnectionKeyPort" to mctlPort.toLong()),
                    )
                }
                stream["streamConnections"] = Collections.unmodifiableMap(connections)
            }
        }
        return Collections.unmodifiableMap(
            linkedMapOf<String, Any?>(
                "streams" to Collections.unmodifiableList(
                    listOf(Collections.unmodifiableMap(stream)),
                ),
            ),
        )
    }

    internal fun requireCurrent(candidate: MainBufferedV27SetupTransaction) {
        check(!retired && transaction === candidate) {
            "type-103 SETUP belongs to a retired or different session generation"
        }
    }

    internal fun isBoundTo(
        candidate: MainBufferedV27SetupTransaction,
        lease: MainBufferedM4FlushedSessionLease,
        marker: Any,
    ): Boolean = transaction === candidate && claimedLease === lease &&
        claimedRequestMarker === marker

    internal fun requireCurrentMctlBinding(port: MainBufferedPreparedMctlPort, announcedPort: Int) {
        check(port.generation == generation && port.expectedPeer == expectedPeer &&
            port.localAddress.address == bindAddress && port.localAddress.port == announcedPort
        ) { "type-103 SETUP MediaDataControl binding changed after publication" }
    }

    /** Called with [lock] held, atomically ruling out a concurrent flush commit. */
    internal fun markRetired(candidate: MainBufferedV27SetupTransaction) {
        requireCurrent(candidate)
        retired = true
    }

    override fun close() {
        val (owned, pending) = synchronized(lock) {
            retired = true
            preparation?.cancelled = true
            transaction to preparation
        }
        if (owned != null) {
            owned.retireOwnedListener()
        } else {
            var failure: Throwable? = null
            try { retirePlaybackOwner() } catch (problem: Throwable) { failure = problem }
            if (pending != null) {
                val pendingFailure = try {
                    when {
                        pending.thread === Thread.currentThread() -> IOException(
                            "type-103 SETUP preparation cannot retire itself reentrantly",
                        )
                        !pending.complete.await(tcpLimits.closeJoinMillis, TimeUnit.MILLISECONDS) ->
                            IOException("type-103 SETUP preparation is still pending cleanup")
                        else -> null
                    }
                } catch (problem: InterruptedException) {
                    Thread.currentThread().interrupt()
                    IOException("interrupted while retiring type-103 SETUP preparation", problem)
                }
                if (pendingFailure != null) {
                    if (failure == null) failure = pendingFailure
                    else failure.addSuppressed(pendingFailure)
                }
            }
            if (pending == null || pending.complete.count == 0L) {
                try {
                    synchronized(preparationRetirementLock) { retirePendingPreparationPorts() }
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                }
                // The session's M4 lease is itself the in-flight preparation fence. Releasing
                // it before the binder thread has completed would let session teardown miss
                // a still-running bind even though this close correctly reports pending.
                try { retireLifecycleOwner() } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                }
            }
            failure?.let { throw it }
        }
    }

    /** A failed close stays retryable. Run under preparationRetirementLock, never owner.lock. */
    private fun retirePendingPreparationPorts() {
        var failure: Throwable? = null
        pendingPreparationMctlPort?.let { port ->
            try {
                port.rollback()
                pendingPreparationMctlPort = null
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
        }
        pendingPreparationListener?.let { listener ->
            try {
                if (pendingPreparationListenerRollbackAttempted) {
                    listener.close()
                } else {
                    pendingPreparationListenerRollbackAttempted = true
                    listener.rollback()
                }
                pendingPreparationListener = null
                pendingPreparationListenerRollbackAttempted = false
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }

    /** Exact PCM output retirement is retryable and part of every SETUP teardown path. */
    internal fun retirePlaybackOwner() = synchronized(playbackRetirementLock) {
        val exactOwner = synchronized(lock) {
            if (playbackOwnerRetired) null else playbackOwner
        }
        if (exactOwner != null) {
            // Closing may wait for an in-flight authenticated mctl side effect. Never hold
            // SETUP owner.lock across that wait: its handler may itself inspect SETUP state.
            exactOwner.close()
            synchronized(lock) { playbackOwnerRetired = true }
        }
    }

    /** The product lease is retired on rollback as well as normal close; failures remain retryable. */
    internal fun retireLifecycleOwner() = synchronized(lifecycleRetirementLock) {
        if (!lifecycleRetired) {
            lifecycleRetirement?.close()
            lifecycleRetired = true
        }
    }

    companion object {
        fun create(
            generation: Long,
            bindAddress: InetAddress,
            expectedPeer: InetAddress,
            allocatedStreamId: Long,
            acceptedFormat: MainBufferedV27AcceptedFormat,
            audioBufferSize: Long,
            portMode: MainBufferedV27PortMode,
            packetConsumer: MainBufferedIngressPacketConsumer,
            ingressLimits: MainBufferedIngressLimits = MainBufferedIngressLimits(),
            tcpLimits: MainBufferedTcpIngressLimits = MainBufferedTcpIngressLimits(),
            tcpIngressHooks: MainBufferedTcpIngressHooks = MainBufferedTcpIngressHooks(),
            mctlPortPreparer: MainBufferedMctlPortPreparer? = null,
            ingressIdentity: MainBufferedIngressIdentity = MainBufferedIngressIdentity.create(generation),
            playbackOwner: MainBufferedPcm16PlaybackEpochOwner? = null,
            lifecycleRetirement: Closeable? = null,
            claimedLease: MainBufferedM4FlushedSessionLease? = null,
            claimedRequestMarker: Any? = null,
        ): MainBufferedV27SetupSession {
            require(generation > 0L) { "type-103 SETUP generation must be positive" }
            require(ingressIdentity.generation == generation) {
                "type-103 SETUP ingress identity generation does not match the verified session"
            }
            require(playbackOwner == null || playbackOwner.isOwnedBy(
                ingressIdentity, allocatedStreamId, generation,
            )) { "type-103 SETUP PCM owner must match identity, stream, and PairVerify generation" }
            require(playbackOwner == null || packetConsumer === playbackOwner) {
                "type-103 SETUP PCM owner must be the exact authenticated APAP packet consumer"
            }
            require(playbackOwner == null || mctlPortPreparer == null ||
                (mctlPortPreparer is MainBufferedAuthenticatedMctlPortPreparer &&
                    mctlPortPreparer.isExactPlaybackControlFor(
                        playbackOwner, generation, bindAddress, expectedPeer,
                    ))
            ) {
                "type-103 SETUP mctl preparer must carry the exact PCM owner's control binding"
            }
            require(allocatedStreamId in 1..UINT32_MAX) {
                "receiver-allocated streamID must be a positive UInt32"
            }
            require(audioBufferSize in 1..UINT32_MAX) {
                "downstream audioBufferSize must be a positive UInt32"
            }
            require((claimedLease == null) == (claimedRequestMarker == null)) {
                "type-103 SETUP must bind both the exact M4 lease and frozen request marker"
            }
            require(claimedLease == null || (claimedLease.generation == generation &&
                claimedLease.allocatedStreamId == allocatedStreamId)) {
                "type-103 SETUP M4 lease does not own its generation and receiver streamID"
            }
            return MainBufferedV27SetupSession(
                generation,
                ingressIdentity,
                bindAddress,
                expectedPeer,
                allocatedStreamId,
                acceptedFormat,
                audioBufferSize,
                portMode,
                packetConsumer,
                ingressLimits,
                tcpLimits,
                tcpIngressHooks,
                mctlPortPreparer,
                playbackOwner,
                lifecycleRetirement,
                claimedLease,
                claimedRequestMarker,
            )
        }
    }
}

/**
 * The prepared response plus its exact, session-owned listening port.
 *
 * The RTSP writer must serialize [responseBody], flush the complete response successfully, and
 * only then call [commitAfterResponseFlushed]. Any write/flush failure must call [rollback] (or
 * close the owning session). A sent but uncommitted response cannot admit an APAP stream.
 */
internal class MainBufferedV27SetupTransaction internal constructor(
    private val owner: MainBufferedV27SetupSession,
    private val listener: MainBufferedTcpIngressListener,
    private val mctlPort: MainBufferedPreparedMctlPort?,
    private val announcedMctlPort: Int?,
    val responseBody: Map<String, Any?>,
) : Closeable {
    private val retirementLock = Any()
    private val admissionGate = MainBufferedAdmissionGate()
    private var state = MainBufferedV27SetupState.PREPARED
    private var mctlCommitted = false
    private var mctlRetireWithRollback: Boolean? = null
    private var mctlRetired = mctlPort == null
    private var listenerRetired = false
    private var listenerRollbackAttempted = false

    init {
        mctlPort?.onTerminalFailure { failure -> retireAfterTransportTerminal(failure) }
        listener.onTerminal { reason, failure ->
            retireAfterTransportTerminal(
                failure ?: IOException("main-buffered APAP stream ended: $reason"),
            )
        }
    }

    fun state(): MainBufferedV27SetupState = synchronized(owner.lock) { state }

    internal fun isBoundTo(
        lease: MainBufferedM4FlushedSessionLease,
        marker: Any,
    ): Boolean = owner.isBoundTo(this, lease, marker)

    fun tcpSnapshot(): MainBufferedTcpIngressSnapshot = listener.snapshot()

    /**
     * Preferred send boundary: [sendAndFlush] must return only after the complete RTSP response
     * has reached the transport. An exception automatically rolls back the unsent transaction.
     */
    fun flushResponseAndCommit(sendAndFlush: (Map<String, Any?>) -> Unit) {
        synchronized(owner.lock) {
            owner.requireCurrent(this)
            check(state == MainBufferedV27SetupState.PREPARED) {
                "type-103 SETUP response can be flushed exactly once"
            }
            state = MainBufferedV27SetupState.FLUSHING
        }
        try {
            sendAndFlush(responseBody)
        } catch (problem: Throwable) {
            try {
                rollback()
            } catch (closeProblem: Throwable) {
                problem.addSuppressed(closeProblem)
            }
            throw problem
        }
        commitAfterResponseFlushed(MainBufferedV27SetupState.FLUSHING)
    }

    /** Valid only after the caller has confirmed a successful complete RTSP response flush. */
    fun commitAfterResponseFlushed() = commitAfterResponseFlushed(MainBufferedV27SetupState.PREPARED)

    private fun commitAfterResponseFlushed(expectedState: MainBufferedV27SetupState) {
        var commitFailure: Throwable? = null
        var awaitDualPortReadiness = false
        synchronized(owner.lock) {
            owner.requireCurrent(this)
            check(state == expectedState) {
                "type-103 SETUP response can be committed exactly once"
            }
            try {
                if (mctlPort == null) {
                    listener.commit()
                } else {
                    owner.requireCurrentMctlBinding(mctlPort, checkNotNull(announcedMctlPort))
                    listener.commitDeferred()
                    mctlPort.commitDeferred()
                    mctlCommitted = true
                    listener.activateWhen(admissionGate)
                    mctlPort.activateWhen(admissionGate)
                    // Both workers are now running toward a *closed* barrier. No APAP peer can
                    // be accepted and no mctl package can dispatch until each exact worker has
                    // reported READY and this transaction performs the one-shot release below.
                    awaitDualPortReadiness = true
                }
                if (!awaitDualPortReadiness) state = MainBufferedV27SetupState.COMMITTED
            } catch (problem: Throwable) {
                // No later flush acknowledgment may reuse this generation after a start failure.
                state = MainBufferedV27SetupState.RETIRING
                commitFailure = problem
            }
        }
        commitFailure?.let { problem ->
            try {
                owner.close()
            } catch (closeProblem: Throwable) {
                problem.addSuppressed(closeProblem)
            }
            throw problem
        }
        if (!awaitDualPortReadiness) return

        // Wait for both workers at the same *closed* gate. Neither readiness receipt is an
        // admission permit. A concurrent teardown can cancel this wait without waiting behind
        // owner.lock, and every pre-release failure leaves both ports free of side effects.
        try {
            val port = checkNotNull(mctlPort)
            check(listener.awaitReadiness(admissionGate, MCTL_ADMISSION_RECEIPT_TIMEOUT_MILLIS)) {
                "type-103 APAP worker did not reach the closed admission gate"
            }
            val receipt = port.awaitAdmissionReceipt(MCTL_ADMISSION_RECEIPT_TIMEOUT_MILLIS)
                ?: throw IllegalStateException("type-103 MediaDataControl worker did not reach READY")
            synchronized(owner.lock) {
                owner.requireCurrent(this)
                check(state == expectedState) {
                    "type-103 SETUP retired before dual-port readiness release"
                }
                owner.requireCurrentMctlBinding(port, checkNotNull(announcedMctlPort))
                check(receipt.generation == port.generation &&
                    receipt.localAddress == port.localAddress &&
                    receipt.expectedPeer == port.expectedPeer &&
                    receipt.gate === admissionGate && receipt.workerAlive
                ) { "type-103 MediaDataControl admission receipt is not exact" }
                check(listener.hasCurrentReadiness(admissionGate)) {
                    "type-103 APAP readiness retired before dual-port release"
                }
                check(port.commitIfAdmissionCurrent(receipt) {
                    check(listener.hasCurrentReadiness(admissionGate)) {
                        "type-103 APAP readiness retired while mctl was current"
                    }
                    state = MainBufferedV27SetupState.COMMITTED
                    admissionGate.openWhenReady(listener, port)
                }) { "type-103 MediaDataControl worker retired before dual-port release" }
            }
        } catch (problem: Throwable) {
            synchronized(owner.lock) {
                if (state != MainBufferedV27SetupState.CLOSED) {
                    state = MainBufferedV27SetupState.RETIRING
                }
            }
            try {
                owner.close()
            } catch (closeProblem: Throwable) {
                problem.addSuppressed(closeProblem)
            }
            throw problem
        }
    }

    private fun retireAfterTransportTerminal(failure: Throwable) {
        val shouldRetire = synchronized(owner.lock) {
            if (state == MainBufferedV27SetupState.CLOSED ||
                state == MainBufferedV27SetupState.RETIRING
            ) false else {
                state = MainBufferedV27SetupState.RETIRING
                admissionGate.close()
                true
            }
        }
        if (!shouldRetire) return
        // Mute the exact native output before waiting for either transport to retire. This
        // callback can run on the mctl *or* APAP worker, so cleanup must join on another thread.
        try {
            owner.retirePlaybackOwner()
        } catch (closeProblem: Throwable) {
            failure.addSuppressed(closeProblem)
        }
        val retireWorker = Thread({
            try {
                owner.close()
            } catch (closeProblem: Throwable) {
                failure.addSuppressed(closeProblem)
            }
        }, "main-buffered-paired-retire")
        retireWorker.isDaemon = true
        try {
            retireWorker.start()
        } catch (startProblem: Throwable) {
            failure.addSuppressed(startProblem)
            // Even without a retirement worker, stop both transports now; self-join failures
            // stay attached to the exact owner for a later explicit close retry.
            try { listener.close() } catch (closeProblem: Throwable) {
                failure.addSuppressed(closeProblem)
            }
            try {
                if (mctlCommitted) mctlPort?.close() else mctlPort?.rollback()
            } catch (closeProblem: Throwable) {
                failure.addSuppressed(closeProblem)
            }
        }
    }

    /** Failure or cancellation before successful flush releases the reserved port and key. */
    fun rollback() {
        synchronized(owner.lock) {
            owner.requireCurrent(this)
            check(state == MainBufferedV27SetupState.PREPARED ||
                state == MainBufferedV27SetupState.FLUSHING
            ) {
                "type-103 SETUP can roll back only before response flush"
            }
            owner.markRetired(this)
        }
        retireOwnedListener()
    }

    fun awaitAuthenticatedSession(timeoutMillis: Long): MainBufferedIngressSession? {
        synchronized(owner.lock) {
            owner.requireCurrent(this)
            check(state == MainBufferedV27SetupState.COMMITTED) {
                "type-103 APAP ingress is not committed"
            }
        }
        val admitted = listener.awaitSession(timeoutMillis)
        return synchronized(owner.lock) {
            if (state == MainBufferedV27SetupState.COMMITTED) {
                try {
                    owner.requireCurrent(this)
                    admitted
                } catch (_: IllegalStateException) {
                    null
                }
            } else {
                null
            }
        }
    }

    internal fun retireOwnedListener() {
        synchronized(retirementLock) {
            synchronized(owner.lock) {
                if (state == MainBufferedV27SetupState.CLOSED) return
                state = MainBufferedV27SetupState.RETIRING
                if (mctlRetireWithRollback == null) mctlRetireWithRollback = !mctlCommitted
            }
            admissionGate.close()
            var failure: Throwable? = null
            try {
                owner.retirePlaybackOwner()
            } catch (problem: Throwable) {
                failure = problem
            }
            if (!listenerRetired) {
                try {
                    if (!listenerRollbackAttempted &&
                        listener.snapshot().state == MainBufferedTcpIngressState.PREPARED
                    ) {
                        listenerRollbackAttempted = true
                        listener.rollback()
                    } else {
                        listener.close()
                    }
                    listenerRetired = true
                } catch (problem: Throwable) {
                    failure = problem
                }
            }
            if (!mctlRetired) {
                try {
                    if (mctlRetireWithRollback == true) mctlPort?.rollback() else mctlPort?.close()
                    mctlRetired = true
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure!!.addSuppressed(problem)
                }
            }
            try {
                owner.retireLifecycleOwner()
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
            if (failure == null) {
                synchronized(owner.lock) { state = MainBufferedV27SetupState.CLOSED }
            } else {
                throw checkNotNull(failure)
            }
        }
    }

    override fun close() = owner.close()

    private companion object {
        const val MCTL_ADMISSION_RECEIPT_TIMEOUT_MILLIS = 1_000L
    }
}

private fun wireUInt64(bits: Long): Number = if (bits >= 0L) {
    bits
} else {
    BigInteger.valueOf(bits).add(BigInteger.ONE.shiftLeft(64))
}

private fun rawUInt64Bits(value: Any?, label: String): Long = when (value) {
    is Byte -> value.toLong().also { require(it >= 0L) { "$label must be a UInt64 integer" } }
    is Short -> value.toLong().also { require(it >= 0L) { "$label must be a UInt64 integer" } }
    is Int -> value.toLong().also { require(it >= 0L) { "$label must be a UInt64 integer" } }
    is Long -> value
    is BigInteger -> {
        require(value.signum() >= 0 && value <= UINT64_MAX) {
            "$label must be a UInt64 integer"
        }
        value.toLong()
    }
    else -> throw IllegalArgumentException("$label must be a UInt64 integer")
}

private const val UINT32_MAX = 0xffff_ffffL
private val UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
