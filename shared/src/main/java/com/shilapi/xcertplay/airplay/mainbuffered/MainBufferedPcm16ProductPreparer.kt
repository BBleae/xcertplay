package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import java.io.Closeable
import java.net.InetAddress
import java.util.IdentityHashMap

/**
 * Trusted-session seam minted by AirPlaySession only after its M4 response was completely flushed.
 * The session must atomically revoke
 * this generation before STOPPING, a new M1, or PairVerify-secret retirement. A copy returned by
 * [borrowVerifiedSharedSecret] must transfer a closeable raw X25519 secret scope, not a PSK.
 * No implementation is installed in the app; a peer address or PairVerify.isVerified alone is
 * not sufficient evidence for this lease.
 */
internal interface MainBufferedM4FlushedSessionLease : Closeable {
    val session: AirPlaySession
    val generation: Long
    val bindAddress: InetAddress
    val expectedPeer: InetAddress
    val allocatedStreamId: Long
    val sourceByteOrder: MainBufferedPcm16ByteOrder?

    fun isCurrent(requestedGeneration: Long): Boolean
    fun borrowVerifiedSharedSecret(requestedGeneration: Long): MainBufferedVerifiedSecretBorrow?
    /** Completes the session's in-flight-prepare fence, including on every rejected path. */
    fun finishPreparation() {}
}

internal fun interface MainBufferedM4FlushedLeaseProvider {
    /** A fresh child lease for this exact frozen request or null; never synthesize one. */
    fun borrow(
        session: AirPlaySession,
        ticket: MainBufferedV27RequestTicket,
    ): MainBufferedM4FlushedSessionLease?
}

/** `audioBufferSize` must be reported by this exact output, in the sender's observed units. */
internal class MainBufferedPcm16ProductOutput(
    val output: MainBufferedPcm16Output,
    val audioBufferSize: Long,
) {
    init { require(audioBufferSize in 1L..0xffff_ffffL) }
}

internal fun interface MainBufferedPcm16ProductOutputFactory {
    fun open(): MainBufferedPcm16ProductOutput
}

/**
 * Detached type-103 PCM16 product composition. The default CarPlayMediaEngine does not inject
 * this preparer and feature negotiation still withholds MAIN_BUFFERED. In particular, the source
 * byte order, actual output buffer size, and M4-flushed secret lease are never guessed here.
 * An authenticated but uncalibrated `srat` rate-one remains rejected by the playback owner.
 */
internal class MainBufferedPcm16ProductPreparer(
    private val leaseProvider: MainBufferedM4FlushedLeaseProvider,
    private val outputFactory: MainBufferedPcm16ProductOutputFactory,
    private val samplesPerFrame: Long,
    private val audioClockMapping: MainBufferedPcm16AudioClockMapping? = null,
    private val tcpLimits: MainBufferedTcpIngressLimits = MainBufferedTcpIngressLimits(),
    private val mctlLimits: MainBufferedMctlTcpLimits = MainBufferedMctlTcpLimits(),
) {
    private val retirementLock = Any()
    private val pendingRetirements = IdentityHashMap<AirPlaySession, MutableList<PendingRetirement>>()

    private class PendingRetirement(resources: List<Closeable>) : Closeable {
        private val resources = resources.toList()
        private val retired = BooleanArray(resources.size)

        @Synchronized
        override fun close() {
            var failure: Throwable? = null
            resources.forEachIndexed { index, resource ->
                if (!retired[index]) try {
                    resource.close()
                    retired[index] = true
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure!!.addSuppressed(problem)
                }
            }
            failure?.let { throw it }
        }

        @Synchronized
        fun isComplete(): Boolean = retired.all { it }
    }

    /** Retry exact construction owners whose first close failed, including native-output close. */
    fun retireFailedPreparations(session: AirPlaySession? = null): Throwable? {
        val entries = synchronized(retirementLock) {
            pendingRetirements.entries
                .filter { session == null || it.key === session }
                .flatMap { entry -> entry.value.map { entry.key to it } }
        }
        var failure: Throwable? = null
        entries.forEach { (owner, retirement) ->
            try { retirement.close() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
            if (retirement.isComplete()) synchronized(retirementLock) {
                pendingRetirements[owner]?.let { list ->
                    list.remove(retirement)
                    if (list.isEmpty()) pendingRetirements.remove(owner)
                }
            }
        }
        return failure
    }

    /** Read-only part of the exact product-owner retirement receipt. */
    fun hasPendingRetirements(session: AirPlaySession): Boolean = synchronized(retirementLock) {
        pendingRetirements[session]?.isNotEmpty() == true
    }

    private fun retireOrRetain(session: AirPlaySession, resources: List<Closeable>): Throwable? {
        val exact = PendingRetirement(resources)
        synchronized(retirementLock) {
            pendingRetirements.getOrPut(session) { ArrayList() }.add(exact)
        }
        var failure: Throwable? = null
        try { exact.close() } catch (problem: Throwable) { failure = problem }
        if (exact.isComplete()) synchronized(retirementLock) {
            pendingRetirements[session]?.let { list ->
                list.remove(exact)
                if (list.isEmpty()) pendingRetirements.remove(session)
            }
        }
        return failure
    }

    private fun denyAndRetireLease(
        session: AirPlaySession,
        lease: MainBufferedM4FlushedSessionLease,
    ): MainBufferedV27SetupTransaction? {
        // Even a close failure is retained for the exact session; no port or response is made.
        retireOrRetain(session, listOf(lease))
        return null
    }

    private val acceptedFormat = MainBufferedV27AcceptedFormat(
        MainBufferedPcm16Ingress.LPCM_CONTENT_TYPE,
        samplesPerFrame,
        MainBufferedAudioFormatBits.PCM_48000_16_STEREO,
    )

    init {
        require(samplesPerFrame in 1L..MainBufferedPcm16Ingress.MAX_SAMPLES_PER_PACKET.toLong())
    }

    fun prepare(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        request: Map<String, Any?>,
    ): MainBufferedV27SetupTransaction? = MainBufferedV27RequestTicket.freeze(request).use { ticket ->
        prepareFrozen(session, identity, ticket)
    }

    private fun prepareFrozen(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        ticket: MainBufferedV27RequestTicket,
    ): MainBufferedV27SetupTransaction? {
        if (identity.type != MainBufferedSetupRequest.STREAM_TYPE.toInt() ||
            identity.streamConnectionId !=
                java.lang.Long.toUnsignedString(ticket.key.connectionIdBits) ||
            identity.clientTypeUuid != null || session.isClosed ||
            !session.isWireless
        ) return null
        val lease = leaseProvider.borrow(session, ticket) ?: return null
        var opened: MainBufferedPcm16ProductOutput? = null
        var ingress: MainBufferedPcm16Ingress? = null
        var playback: MainBufferedPcm16PlaybackEpochOwner? = null
        var retirement: ProductLeaseRetirement? = null
        var setup: MainBufferedV27SetupSession? = null
        try {
            if (!leaseMatchesSession(lease, session)) {
                return denyAndRetireLease(session, lease)
            }
            val exactMarker = (lease as? AirPlayMainBufferedM4Lease)?.requestMarker
            if (lease is AirPlayMainBufferedM4Lease &&
                (exactMarker == null || !ticket.isBoundTo(exactMarker))
            ) return denyAndRetireLease(session, lease)
            // A playable PCM product needs the independently authenticated control port. The
            // detached v27 parser also understands APAP-only and legacy dataPort, but this
            // product does not claim readiness for those incomplete playback paths.
            val connections = ticket.request.optionalValues["streamConnections"] as? Map<*, *> ?: run {
                return denyAndRetireLease(session, lease)
            }
            if (connections.keys != setOf(
                    "streamConnectionTypeAPAP", "streamConnectionTypeMediaDataControl",
                )
            ) {
                return denyAndRetireLease(session, lease)
            }
            val byteOrder = lease.sourceByteOrder ?: run {
                return denyAndRetireLease(session, lease)
            }
            opened = outputFactory.open()
            val selectedOutput = checkNotNull(opened)
            val ingressIdentity = MainBufferedIngressIdentity.create(lease.generation)
            ingress = MainBufferedPcm16Ingress.prepare(
                ingressIdentity,
                lease.allocatedStreamId,
                acceptedFormat,
                byteOrder,
                MainBufferedPcm16OutputFactory { selectedOutput.output },
            )
            opened = null // The ingress now owns this exact native output.
            playback = MainBufferedPcm16PlaybackEpochOwner.create(
                ingressIdentity, lease.allocatedStreamId, lease.generation, checkNotNull(ingress),
            )
            ingress = null // The playback epoch now owns the ingress.
            val selectedPlayback = checkNotNull(playback)
            val controlBinding = selectedPlayback.authenticatedMctlBinding(
                ingressIdentity, lease.generation, audioClockMapping,
            )
            val mctl = MainBufferedAuthenticatedMctlPortPreparer(
                sessionGeneration = lease.generation,
                bindAddress = lease.bindAddress,
                expectedPeer = lease.expectedPeer,
                borrowVerifiedSharedSecret = lease::borrowVerifiedSharedSecret,
                isSessionCurrent = { generation ->
                    !session.isClosed && lease.isCurrent(generation) &&
                        leaseMatchesAddresses(lease, session)
                },
                dispatcher = controlBinding.dispatcher,
                limits = mctlLimits,
                playbackControlBinding = controlBinding,
            )
            retirement = ProductLeaseRetirement(mctl, lease)
            setup = MainBufferedV27SetupSession.create(
                generation = lease.generation,
                bindAddress = lease.bindAddress,
                expectedPeer = lease.expectedPeer,
                allocatedStreamId = lease.allocatedStreamId,
                acceptedFormat = acceptedFormat,
                audioBufferSize = selectedOutput.audioBufferSize,
                portMode = MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
                packetConsumer = selectedPlayback,
                ingressLimits = MainBufferedPcm16Ingress.limitsFor(acceptedFormat),
                tcpLimits = tcpLimits,
                mctlPortPreparer = mctl,
                ingressIdentity = ingressIdentity,
                playbackOwner = selectedPlayback,
                lifecycleRetirement = retirement,
                claimedLease = if (exactMarker != null) lease else null,
                claimedRequestMarker = exactMarker,
            )
            playback = null // The SETUP transaction now owns the playback epoch.
            // Keep the idempotent retirement reference until prepare returns, so a failure
            // during port allocation can retry revocation even if SETUP close itself fails.
            val transaction = checkNotNull(setup).prepare(ticket)
            check(leaseMatchesSession(lease, session)) {
                "type-103 lease retired while reserving APAP/mctl ports"
            }
            return transaction
        } catch (problem: Throwable) {
            val resources = buildList<Closeable> {
                setup?.let(::add)
                playback?.let(::add)
                ingress?.let(::add)
                opened?.output?.let(::add)
                add(retirement ?: lease)
            }
            retireOrRetain(session, resources)?.let { cleanupFailure ->
                if (cleanupFailure !== problem) problem.addSuppressed(cleanupFailure)
            }
            throw problem
        } finally {
            lease.finishPreparation()
        }
    }

    private fun leaseMatchesSession(
        lease: MainBufferedM4FlushedSessionLease,
        session: AirPlaySession,
    ): Boolean = lease.session === session && lease.generation > 0L &&
        lease.allocatedStreamId in 1L..0xffff_ffffL &&
        !session.isClosed && lease.isCurrent(lease.generation) &&
        leaseMatchesAddresses(lease, session)

    private fun leaseMatchesAddresses(
        lease: MainBufferedM4FlushedSessionLease,
        session: AirPlaySession,
    ): Boolean = lease.bindAddress == session.localAddress &&
        !lease.bindAddress.isAnyLocalAddress && !lease.bindAddress.isMulticastAddress &&
        lease.expectedPeer == session.remoteAddress &&
        !lease.expectedPeer.isAnyLocalAddress && !lease.expectedPeer.isMulticastAddress

    private class ProductLeaseRetirement(
        private val mctl: MainBufferedAuthenticatedMctlPortPreparer,
        private val lease: MainBufferedM4FlushedSessionLease,
    ) : Closeable {
        private var mctlRetired = false
        private var leaseRetired = false

        @Synchronized
        override fun close() {
            var failure: Throwable? = null
            if (!mctlRetired) try {
                mctl.close()
                mctlRetired = true
            } catch (problem: Throwable) { failure = problem }
            if (!leaseRetired) try {
                lease.close()
                leaseRetired = true
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
            failure?.let { throw it }
        }
    }
}
