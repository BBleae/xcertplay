package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay timing-port clock sync.
 *
 * The accessory drives a two-way RTCP-style exchange with the phone: PT_REQUEST (210) carries our
 * transmit timestamp and PT_RESPONSE (211) echoes it while adding the phone's receive and
 * transmit stamps. The resulting offset and round-trip time steer a local monotonic clock onto
 * the phone's media-clock domain, which /feedback reports.
 */
class NtpClock(
    private val socketFactory: () -> DatagramSocket = { DatagramSocket(null) },
) : Closeable {
    /**
     * One accepted PT211 reply to an outstanding PT210 probe. The error bound is the nominal
     * half-round-trip path-asymmetry bound plus NTP tick quantization; it cannot attest remote
     * timestamp quality, drift, optical presentation, or UI Sync clock-domain equivalence.
     */
    data class VideoClockMeasurement(
        val peer: InetSocketAddress,
        val localSendMonotonicNanos: Long,
        val localReceiveMonotonicNanos: Long,
        val originateNtp: BigInteger,
        val remoteReceiveNtp: BigInteger,
        val remoteTransmitNtp: BigInteger,
        val localReceiveNtp: BigInteger,
        val roundTripNanos: Long,
        val offsetNanos: Long,
        val errorBoundNanos: Long,
        val sampleGeneration: Long,
        val clockEraGeneration: Long,
    )

    /** A short-lived read-only observation, not proof of UI Sync type-2/type-111 clock alignment. */
    data class VideoClockLease(
        val measurement: VideoClockMeasurement,
        val ageNanos: Long,
        val unappliedOffsetNanos: Long,
        val effectiveErrorBoundNanos: Long,
    )

    /** Read-only source-pin counters for diagnosing an unverified iPhone timing source port. */
    data class TimingPeerDiagnostics(
        val configuredPeer: InetSocketAddress?,
        val lastConfiguredPeer: InetSocketAddress?,
        val lastRejectedSource: InetSocketAddress?,
        val beforeStartOrAfterStopPackets: Long,
        val wrongAddressPackets: Long,
        val wrongPortPackets: Long,
        val unmatchedResponsePackets: Long,
        val invalidResponsePackets: Long,
        val lastInvalidResponseReason: String?,
        val acceptedResponseGeneration: Long,
    )

    private data class SelectedVideoClockEvidence(
        val measurement: VideoClockMeasurement,
        val unappliedOffsetNanos: Long,
        val effectiveErrorBoundNanos: Long,
    )

    private data class PendingProbe(
        val peer: InetSocketAddress,
        val transmitNtp: BigInteger,
        val sendMonotonicNanos: Long,
        val clockEraGeneration: Long,
    )

    private val running = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val closeAttemptLock = Any()
    private val clockLock = Any()

    private var socket: DatagramSocket? = null
    private var receiver: Thread? = null
    private var sender: Thread? = null
    private var peer: InetSocketAddress? = null
    private var lastConfiguredPeer: InetSocketAddress? = null
    private var lastRejectedSource: InetSocketAddress? = null
    private var beforeStartOrAfterStopPackets = 0L
    private var wrongAddressPackets = 0L
    private var wrongPortPackets = 0L
    private var unmatchedResponsePackets = 0L
    private var invalidResponsePackets = 0L
    private var lastInvalidResponseReason: String? = null
    private var stopping = false
    private var retired = false

    private var clockOffsetNs = wallClockNtpOffsetNs(System.nanoTime())
    private val delays = DoubleArray(DELAY_WINDOW) { Double.POSITIVE_INFINITY }
    private var delayIndex = 0
    private var pickCount = PICK_COUNT
    private var pickRtt = Double.POSITIVE_INFINITY
    private var pickOffset = 0.0
    private var pickMeasurement: VideoClockMeasurement? = null
    private val pickWindowMeasurements = ArrayList<VideoClockMeasurement>(PICK_COUNT)
    private var pickingDiscontinuity = false
    private var pendingProbe: PendingProbe? = null
    private var lastVideoMeasurement: VideoClockMeasurement? = null
    private var lastSelectedVideoEvidence: SelectedVideoClockEvidence? = null
    private var sampleGeneration = 0L
    private var clockEraGeneration = 0L
    private var synced = false

    fun listen(): Int {
        val bound = synchronized(lifecycleLock) {
            check(!stopping && !retired) { "NtpClock is terminating" }
            check(!running.get()) { "NtpClock is already running" }
            check(socket == null && receiver == null) {
                "NtpClock retains an unretired listener owner"
            }
            // Publish ownership immediately after construction, before options, bind, thread
            // construction, or start can fail.
            socketFactory().also { socket = it }
        }
        try {
            bound.reuseAddress = true
            bound.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            val worker = Thread(::runReceiver, "airplay-ntp-rx").apply { isDaemon = true }
            synchronized(lifecycleLock) {
                check(!stopping && !retired && socket === bound) {
                    "NtpClock listener lost ownership during startup"
                }
                receiver = worker
                running.set(true)
                worker.start()
            }
            return bound.localPort
        } catch (failure: Throwable) {
            rollbackFailedListen(bound, failure)
            throw failure
        }
    }

    private fun rollbackFailedListen(owner: DatagramSocket, failure: Throwable) {
        running.set(false)
        val worker = synchronized(lifecycleLock) { receiver }
        worker?.interrupt()
        try {
            owner.close()
            synchronized(lifecycleLock) {
                if (socket === owner) socket = null
                if (receiver === worker && worker?.isAlive != true) receiver = null
            }
        } catch (cleanupFailure: Exception) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
    }

    fun start(peerAddress: InetAddress, port: Int) {
        require(port in 1..65535) { "timing port must be in 1..65535" }
        synchronized(lifecycleLock) {
            check(running.get() && !stopping && !retired) { "NtpClock is not listening" }
            check(sender == null) { "NtpClock sender is already running" }
            peer = InetSocketAddress(peerAddress, port)
            lastConfiguredPeer = peer
            synchronized(clockLock) {
                pendingProbe = null
                lastVideoMeasurement = null
                lastSelectedVideoEvidence = null
            }
            val worker = Thread(::runSender, "airplay-ntp-tx").apply { isDaemon = true }
            sender = worker
            try {
                worker.start()
            } catch (failure: Throwable) {
                if (sender === worker) sender = null
                peer = null
                synchronized(clockLock) {
                    pendingProbe = null
                    lastVideoMeasurement = null
                    lastSelectedVideoEvidence = null
                }
                throw failure
            }
        }
    }

    /** Unsigned NTP64 output; provisional wall-clock estimate until a timing sample is selected. */
    fun syncedNtp(): BigInteger = synchronized(clockLock) {
        val synchronizedNanos = try {
            Math.addExact(System.nanoTime(), clockOffsetNs)
        } catch (_: ArithmeticException) {
            synced = false
            lastSelectedVideoEvidence = null
            return@synchronized ntp64Now().and(NTP64_MASK)
        }
        ntpFromNanos(synchronizedNanos).and(NTP64_MASK)
    }

    /**
     * Returns a bounded, current-peer observation for video-clock diagnostics only. The initial
     * wall-clock estimate never creates a lease. This does not calibrate or authorize UI Sync.
     */
    fun latestVideoClockMeasurement(
        maxAgeNanos: Long = VIDEO_CLOCK_LEASE_MAX_AGE_NS,
    ): VideoClockMeasurement? {
        require(maxAgeNanos >= 0) { "maxAgeNanos must be nonnegative" }
        return synchronized(lifecycleLock) life@{
            val currentPeer = peer
            if (!running.get() || stopping || retired || currentPeer == null) {
                return@life null
            }
            synchronized(clockLock) sample@{
                val sample = lastVideoMeasurement ?: return@sample null
                if (sample.peer != currentPeer || sample.clockEraGeneration != clockEraGeneration) {
                    return@sample null
                }
                val age = System.nanoTime() - sample.localReceiveMonotonicNanos
                if (age < 0 || age > maxAgeNanos.coerceAtMost(VIDEO_CLOCK_LEASE_MAX_AGE_NS)) {
                    return@sample null
                }
                sample
            }
        }
    }

    fun timingPeerDiagnostics(): TimingPeerDiagnostics = synchronized(lifecycleLock) {
        synchronized(clockLock) {
            TimingPeerDiagnostics(
                configuredPeer = peer,
                lastConfiguredPeer = lastConfiguredPeer,
                lastRejectedSource = lastRejectedSource,
                beforeStartOrAfterStopPackets = beforeStartOrAfterStopPackets,
                wrongAddressPackets = wrongAddressPackets,
                wrongPortPackets = wrongPortPackets,
                unmatchedResponsePackets = unmatchedResponsePackets,
                invalidResponsePackets = invalidResponsePackets,
                lastInvalidResponseReason = lastInvalidResponseReason,
                acceptedResponseGeneration = sampleGeneration,
            )
        }
    }

    fun videoClockLease(maxAgeNanos: Long = VIDEO_CLOCK_LEASE_MAX_AGE_NS): VideoClockLease? {
        require(maxAgeNanos >= 0) { "maxAgeNanos must be nonnegative" }
        return synchronized(lifecycleLock) life@{
            val currentPeer = peer
            if (!running.get() || stopping || retired || currentPeer == null) return@life null
            synchronized(clockLock) {
                currentVideoClockLeaseLocked(currentPeer, maxAgeNanos, System.nanoTime())
            }
        }
    }

    /** Caller holds lifecycleLock and clockLock in that order. */
    private fun currentVideoClockLeaseLocked(
        currentPeer: InetSocketAddress,
        maxAgeNanos: Long,
        nowMonotonicNanos: Long,
    ): VideoClockLease? {
        if (!synced) return null
        val selected = lastSelectedVideoEvidence ?: return null
        val sample = selected.measurement
        if (sample.peer != currentPeer || sample.clockEraGeneration != clockEraGeneration ||
            selected.effectiveErrorBoundNanos > VIDEO_CLOCK_LEASE_MAX_ERROR_NS
        ) return null
        val age = nowMonotonicNanos - sample.localReceiveMonotonicNanos
        if (age < 0 || age > maxAgeNanos.coerceAtMost(VIDEO_CLOCK_LEASE_MAX_AGE_NS)) return null
        return VideoClockLease(
            measurement = sample,
            ageNanos = age,
            unappliedOffsetNanos = selected.unappliedOffsetNanos,
            effectiveErrorBoundNanos = selected.effectiveErrorBoundNanos,
        )
    }

    /**
     * Resolves one raw screen-frame presentation timestamp against a matching monotonic sample.
     * Values outside the bounded current clock era/window return null and must use arrival-time
     * rendering rather than holding a decoder buffer indefinitely.
     */
    fun presentationMonotonicNanos(
        presentationTimeNtp64Bits: Long,
        sampleMonotonicNanos: Long = System.nanoTime(),
    ): Long? = synchronized(lifecycleLock) life@{
        // A stale or retired clock falls back to arrival-time rendering, never to an unbounded
        // wall-clock guess. This is only the video decoder's release clock, not UI Sync proof.
        val currentPeer = peer
        if (!running.get() || stopping || retired || currentPeer == null) return@life null
        synchronized(clockLock) sample@{
            if (currentVideoClockLeaseLocked(
                    currentPeer,
                    VIDEO_CLOCK_LEASE_MAX_AGE_NS,
                    System.nanoTime(),
                ) == null
            ) return@sample null
            val synchronizedNanos = try {
                Math.addExact(sampleMonotonicNanos, clockOffsetNs)
            } catch (_: ArithmeticException) {
                return@sample null
            }
            ScreenPresentationClock.toMonotonicNanos(
                presentationTimeNtp64Bits = presentationTimeNtp64Bits,
                synchronizedNowNtp = ntpFromNanos(synchronizedNanos),
                sampleMonotonicNanos = sampleMonotonicNanos,
            )
        }
    }

    override fun close() {
        synchronized(closeAttemptLock) {
            synchronized(lifecycleLock) {
                if (retired) return
                stopping = true
                running.set(false)
                peer = null
                synchronized(clockLock) {
                    pendingProbe = null
                    lastVideoMeasurement = null
                    lastSelectedVideoEvidence = null
                }
            }

            var failure: Exception? = null
            synchronized(lifecycleLock) {
                socket?.let { owner ->
                    try {
                        owner.close()
                        if (socket === owner) socket = null
                    } catch (error: Exception) {
                        failure = combineNtpCloseFailure(failure, error)
                    }
                }
                sender?.interrupt()
                receiver?.interrupt()
            }

            val deadline = System.nanoTime() + CLOSE_JOIN_TIMEOUT_MILLIS * NANOS_PER_MILLI
            listOfNotNull(sender, receiver).forEach { worker ->
                if (worker === Thread.currentThread()) {
                    failure = combineNtpCloseFailure(
                        failure,
                        IOException("NTP worker cannot prove its own retirement: ${worker.name}"),
                    )
                    return@forEach
                }
                if (worker.state != Thread.State.NEW && worker.isAlive) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining > 0L) {
                        try {
                            worker.join((remaining / NANOS_PER_MILLI).coerceAtLeast(1L))
                        } catch (error: InterruptedException) {
                            Thread.currentThread().interrupt()
                            failure = combineNtpCloseFailure(
                                failure,
                                IOException("Interrupted while retiring ${worker.name}", error),
                            )
                        }
                    }
                }
                synchronized(lifecycleLock) {
                    if (worker.isAlive) {
                        failure = combineNtpCloseFailure(
                            failure,
                            IOException("Timed out retiring NTP worker ${worker.name}"),
                        )
                    } else {
                        if (sender === worker) sender = null
                        if (receiver === worker) receiver = null
                    }
                }
            }

            synchronized(lifecycleLock) {
                if (socket != null || sender != null || receiver != null) {
                    failure = combineNtpCloseFailure(
                        failure,
                        IOException("NTP retirement remains unproven"),
                    )
                }
                if (failure == null) retired = true
            }
            failure?.let { throw it }
        }
    }

    private fun runSender() {
        while (running.get()) {
            try {
                sendRequest()
                Thread.sleep(REQUEST_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                try {
                    Thread.sleep(REQUEST_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun sendRequest() {
        val destination: InetSocketAddress
        val owner: DatagramSocket
        val packet = ByteArray(NTP_PACKET_BYTES)
        packet[0] = 0x80.toByte()
        packet[1] = PT_REQUEST.toByte()
        writeU16Be(packet, 2, 7)
        synchronized(lifecycleLock) {
            if (!running.get() || stopping || retired) return
            destination = peer ?: return
            owner = socket ?: return
            synchronized(clockLock) {
                val sendMonotonicNanos = System.nanoTime()
                val synchronizedNanos = try {
                    Math.addExact(sendMonotonicNanos, clockOffsetNs)
                } catch (_: ArithmeticException) {
                    lastSelectedVideoEvidence = null
                    return
                }
                val t1 = ntpFromNanos(synchronizedNanos).and(NTP64_MASK)
                pendingProbe = PendingProbe(
                    destination,
                    t1,
                    sendMonotonicNanos,
                    clockEraGeneration,
                )
                ntpBytes(t1).copyInto(packet, NTP_TRANSMIT_OFFSET)
            }
        }
        try {
            owner.send(DatagramPacket(packet, packet.size, destination))
        } catch (failure: Exception) {
            synchronized(clockLock) {
                if (pendingProbe?.transmitNtp == readNtp(packet, NTP_TRANSMIT_OFFSET)) {
                    pendingProbe = null
                }
            }
            throw failure
        }
    }

    private fun runReceiver() {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (running.get()) {
            val sock = currentSocket() ?: return
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (_: Exception) {
                if (running.get()) continue else return
            }
            val receiveMonotonicNanos = System.nanoTime()
            val message = packet.data.copyOf(packet.length)
            val address = packet.address
            if (message.size >= NTP_PACKET_BYTES && address != null) {
                handleMessage(
                    message,
                    InetSocketAddress(address, packet.port),
                    receiveMonotonicNanos,
                )
            }
        }
    }

    private fun handleMessage(
        message: ByteArray,
        source: InetSocketAddress,
        receiveMonotonicNanos: Long,
    ) {
        // The socket itself is intentionally unconnected because it also answers PT210. Both
        // directions therefore need an explicit exact address+port check after start().
        if ((message[0].toInt() and 0xc0) != 0x80) return
        val type = message[1].toInt() and 0xff
        if (type != PT_REQUEST && type != PT_RESPONSE) return
        if (!isCurrentPeer(source)) return
        when (type) {
            PT_REQUEST -> respondToRequest(message, source)
            PT_RESPONSE -> handleResponse(message, source, receiveMonotonicNanos)
        }
    }

    private fun isCurrentPeer(source: InetSocketAddress): Boolean = synchronized(lifecycleLock) {
        val current = peer
        when {
            !running.get() || stopping || retired || current == null -> {
                beforeStartOrAfterStopPackets = saturatingIncrement(beforeStartOrAfterStopPackets)
                lastRejectedSource = source
                false
            }
            current.address != source.address -> {
                wrongAddressPackets = saturatingIncrement(wrongAddressPackets)
                lastRejectedSource = source
                false
            }
            current.port != source.port -> {
                wrongPortPackets = saturatingIncrement(wrongPortPackets)
                lastRejectedSource = source
                false
            }
            else -> true
        }
    }

    private fun respondToRequest(request: ByteArray, source: InetSocketAddress) {
        val response = ByteArray(NTP_PACKET_BYTES)
        response[0] = 0x80.toByte()
        response[1] = PT_RESPONSE.toByte()
        writeU16Be(response, 2, 7)
        request.copyInto(response, NTP_ORIGINATE_OFFSET, NTP_TRANSMIT_OFFSET, NTP_PACKET_BYTES)
        ntpBytes(syncedNtp()).copyInto(response, NTP_RECEIVE_OFFSET)
        ntpBytes(syncedNtp()).copyInto(response, NTP_TRANSMIT_OFFSET)
        val owner = synchronized(lifecycleLock) {
            if (running.get() && !stopping && !retired && peer == source) socket else null
        } ?: return
        try {
            // Never hold lifecycleLock across external socket IO: close() must be able to revoke
            // the peer and close this exact owner even if a platform send stalls.
            owner.send(DatagramPacket(response, response.size, source))
        } catch (_: IOException) {
            // A concurrent close or transient UDP failure must not kill the receiver loop.
        }
    }

    private fun handleResponse(
        response: ByteArray,
        source: InetSocketAddress,
        receiveMonotonicNanos: Long,
    ) {
        val t1 = readNtp(response, NTP_ORIGINATE_OFFSET)
        val t2 = readNtp(response, NTP_RECEIVE_OFFSET)
        val t3 = readNtp(response, NTP_TRANSMIT_OFFSET)
        synchronized(lifecycleLock) {
            if (!running.get() || stopping || retired || peer != source) return
            synchronized(clockLock) {
                val probe = pendingProbe ?: run {
                    unmatchedResponsePackets = saturatingIncrement(unmatchedResponsePackets)
                    return
                }
                if (probe.peer != source || probe.transmitNtp != t1 ||
                    probe.clockEraGeneration != clockEraGeneration
                ) {
                    unmatchedResponsePackets = saturatingIncrement(unmatchedResponsePackets)
                    return
                }
                pendingProbe = null // A valid originate is single-use, including malformed replies.
                if (receiveMonotonicNanos < probe.sendMonotonicNanos) {
                    recordInvalidResponse("receive-before-send")
                    return
                }
                val synchronizedNanos = try {
                    Math.addExact(receiveMonotonicNanos, clockOffsetNs)
                } catch (_: ArithmeticException) {
                    lastSelectedVideoEvidence = null
                    recordInvalidResponse("local-nanosecond-overflow")
                    return
                }
                val t4 = ntpFromNanos(synchronizedNanos).and(NTP64_MASK)
                val processingTicks = ntp64Delta(t3, t2)
                val localTicks = ntp64Delta(t4, t1)
                val remoteReceiveTicks = ntp64Delta(t2, t1)
                val remoteTransmitTicks = ntp64Delta(t3, t4)
                if (processingTicks == null || localTicks == null ||
                    remoteReceiveTicks == null || remoteTransmitTicks == null
                ) {
                    recordInvalidResponse("ambiguous-half-era-delta")
                    return
                }
                val rttTicks = localTicks.subtract(processingTicks)
                if (processingTicks.signum() < 0 || rttTicks.signum() < 0) {
                    recordInvalidResponse("negative-processing-or-round-trip")
                    return
                }
                val offsetTicks = remoteReceiveTicks.add(remoteTransmitTicks)
                val offset = offsetTicks.toDouble() * 0.5 / TWO32_DOUBLE
                val rtt = rttTicks.toDouble() / TWO32_DOUBLE
                val rttNanos = ntpTicksToNanosCeiling(rttTicks) ?: run {
                    recordInvalidResponse("round-trip-overflow")
                    return
                }
                val offsetNanos = ntpTicksToNanosRounded(offsetTicks.shiftRight(1)) ?: run {
                    recordInvalidResponse("offset-overflow")
                    return
                }
                if (!offset.isFinite() || !rtt.isFinite() ||
                    rttNanos > MAX_ACCEPTED_RTT_NS
                ) {
                    recordInvalidResponse("nonfinite-or-excessive-round-trip")
                    return
                }
                sampleGeneration++
                val measurement = VideoClockMeasurement(
                    peer = source,
                    localSendMonotonicNanos = probe.sendMonotonicNanos,
                    localReceiveMonotonicNanos = receiveMonotonicNanos,
                    originateNtp = t1,
                    remoteReceiveNtp = t2,
                    remoteTransmitNtp = t3,
                    localReceiveNtp = t4,
                    roundTripNanos = rttNanos,
                    offsetNanos = offsetNanos,
                    errorBoundNanos = (rttNanos / 2) + (rttNanos % 2) + NTP_QUANTIZATION_NS,
                    sampleGeneration = sampleGeneration,
                    clockEraGeneration = clockEraGeneration,
                )
                lastVideoMeasurement = measurement
                // Any accepted probe can contradict the residual claimed by the prior selected
                // sample. Preserve the old selected clock only with a monotonically widened
                // bound; a small bound can be reissued only after a new picker selection.
                lastSelectedVideoEvidence?.let { selected ->
                    val observedBound = try {
                        Math.addExact(Math.abs(offsetNanos), measurement.errorBoundNanos)
                    } catch (_: ArithmeticException) {
                        Long.MAX_VALUE
                    }
                    if (observedBound > selected.effectiveErrorBoundNanos) {
                        lastSelectedVideoEvidence = selected.copy(
                            effectiveErrorBoundNanos = observedBound,
                        )
                    }
                }
                // A new observation outside the video scheduling tolerance invalidates the
                // old lease immediately, even below the larger media-clock STEP threshold. The
                // picker must not mix a pre-change low-RTT probe with this newer observation.
                val observedErrorNanos = try {
                    Math.addExact(Math.abs(offsetNanos), measurement.errorBoundNanos)
                } catch (_: ArithmeticException) {
                    Long.MAX_VALUE
                }
                val discontinuity = synced &&
                    observedErrorNanos > VIDEO_CLOCK_LEASE_MAX_ERROR_NS
                if (discontinuity) {
                    lastSelectedVideoEvidence = null
                    if (!pickingDiscontinuity) {
                        resetPickWindow()
                        pickingDiscontinuity = true
                    }
                } else if (pickingDiscontinuity) {
                    resetPickWindow()
                    pickingDiscontinuity = false
                }
                pickWindowMeasurements += measurement
                if (rtt < pickRtt) {
                    pickRtt = rtt
                    pickOffset = offset
                    pickMeasurement = measurement
                }
                if (--pickCount > 0) return
                val selectedRtt = pickRtt
                val selectedOffset = pickOffset
                val selectedMeasurement = pickMeasurement
                val selectedWindow = pickWindowMeasurements.toList()
                resetPickWindow()
                val useSample = delays.all { selectedRtt <= it }
                delays[delayIndex] = selectedRtt
                delayIndex = (delayIndex + 1) % DELAY_WINDOW
                if (useSample) {
                    val appliedNanos = applyOffset(selectedOffset)
                    if (appliedNanos != null &&
                        selectedMeasurement?.clockEraGeneration == clockEraGeneration
                    ) {
                        val unapplied = try {
                            Math.subtractExact(selectedMeasurement.offsetNanos, appliedNanos)
                        } catch (_: ArithmeticException) {
                            Long.MAX_VALUE
                        }
                        // Lowest RTT chooses the offset, but *every* accepted probe in this
                        // picker window constrains what precision the applied clock can claim.
                        val effectiveError = selectedWindow.maxOf { observed ->
                            val residual = try {
                                Math.subtractExact(observed.offsetNanos, appliedNanos)
                            } catch (_: ArithmeticException) {
                                Long.MAX_VALUE
                            }
                            try {
                                Math.addExact(
                                    observed.errorBoundNanos,
                                    if (residual == Long.MIN_VALUE) Long.MAX_VALUE
                                    else Math.abs(residual),
                                )
                            } catch (_: ArithmeticException) {
                                Long.MAX_VALUE
                            }
                        }
                        lastSelectedVideoEvidence = SelectedVideoClockEvidence(
                            selectedMeasurement,
                            unapplied,
                            effectiveError,
                        )
                    }
                }
            }
        }
    }

    private fun resetPickWindow() {
        pickCount = PICK_COUNT
        pickRtt = Double.POSITIVE_INFINITY
        pickOffset = 0.0
        pickMeasurement = null
        pickWindowMeasurements.clear()
    }

    /** Caller holds lifecycleLock; the response path also holds clockLock. */
    private fun recordInvalidResponse(reason: String) {
        invalidResponsePackets = saturatingIncrement(invalidResponsePackets)
        lastInvalidResponseReason = reason
    }

    private fun applyOffset(offsetSec: Double): Long? {
        val stepping = !synced || Math.abs(offsetSec) > STEP_THRESHOLD_SEC
        val applied = if (stepping) offsetSec else offsetSec * SLEW_GAIN
        val appliedNanos = Math.round(applied * 1e9)
        val nextOffset = try {
            Math.addExact(clockOffsetNs, appliedNanos)
        } catch (_: ArithmeticException) {
            lastSelectedVideoEvidence = null
            return null
        }
        clockOffsetNs = nextOffset
        if (stepping) {
            delays.fill(Double.POSITIVE_INFINITY)
            delayIndex = 0
            resetPickWindow()
            pickingDiscontinuity = false
            pendingProbe = null
            lastVideoMeasurement = null
            lastSelectedVideoEvidence = null
            clockEraGeneration++
            synced = true
        }
        return appliedNanos
    }

    private fun currentSocket(): DatagramSocket? = synchronized(lifecycleLock) { socket }

    private companion object {
        const val PT_REQUEST = 210
        const val PT_RESPONSE = 211
        const val NTP_PACKET_BYTES = 32
        const val NTP_ORIGINATE_OFFSET = 8
        const val NTP_RECEIVE_OFFSET = 16
        const val NTP_TRANSMIT_OFFSET = 24
        const val REQUEST_INTERVAL_MS = 1_000L
        const val RECEIVE_BUFFER_BYTES = 2_048
        const val STEP_THRESHOLD_SEC = 0.128
        const val SLEW_GAIN = 1.0 / 8.0
        const val DELAY_WINDOW = 8
        const val PICK_COUNT = 2
        const val TWO32_DOUBLE = 0x1_0000_0000L.toDouble()
        const val CLOSE_JOIN_TIMEOUT_MILLIS = 2_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val VIDEO_CLOCK_LEASE_MAX_AGE_NS = 5_000_000_000L
        const val VIDEO_CLOCK_LEASE_MAX_ERROR_NS = 30_000_000L
        const val MAX_ACCEPTED_RTT_NS = 30_000_000_000L
        const val NTP_QUANTIZATION_NS = 1L
    }
}

private fun saturatingIncrement(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1

private fun ntpTicksToNanosCeiling(ticks: BigInteger): Long? =
    exactLongOrNull(ticks.multiply(NANOS_PER_SECOND)
        .add(TWO32.subtract(BigInteger.ONE))
        .divide(TWO32))

/** Nearest signed NTP64 delta; exact half-era separation has no unique direction. */
private fun ntp64Delta(later: BigInteger, earlier: BigInteger): BigInteger? {
    val modular = later.subtract(earlier).and(NTP64_MASK)
    if (modular == NTP64_HALF_RANGE) return null
    return if (modular > NTP64_HALF_RANGE) modular.subtract(NTP64_MODULUS) else modular
}

private fun ntpTicksToNanosRounded(ticks: BigInteger): Long? {
    val magnitude = ticks.abs().multiply(NANOS_PER_SECOND)
        .add(TWO32.shiftRight(1))
        .divide(TWO32)
    // Check the positive magnitude first. This preserves the prior null boundary for a
    // magnitude of 2^63 rather than overflowing while negating Long.MIN_VALUE.
    val bounded = exactLongOrNull(magnitude) ?: return null
    return if (ticks.signum() < 0) -bounded else bounded
}

/** BigInteger.longValueExact() is unavailable on the project's Android API 28 minimum. */
private fun exactLongOrNull(value: BigInteger): Long? =
    if (value < LONG_MIN_BIG_INTEGER || value > LONG_MAX_BIG_INTEGER) null else value.toLong()

private fun combineNtpCloseFailure(first: Exception?, next: Exception): Exception {
    if (first == null) return next
    if (first !== next && first.suppressed.size < 8) first.addSuppressed(next)
    return first
}

private const val NTP_EPOCH_OFFSET = 2_208_988_800L
private val TWO32 = BigInteger.ONE.shiftLeft(32)
private val NTP64_MODULUS = BigInteger.ONE.shiftLeft(64)
private val NTP64_MASK = NTP64_MODULUS.subtract(BigInteger.ONE)
private val NTP64_HALF_RANGE = BigInteger.ONE.shiftLeft(63)
private val LONG_MIN_BIG_INTEGER = BigInteger.valueOf(Long.MIN_VALUE)
private val LONG_MAX_BIG_INTEGER = BigInteger.valueOf(Long.MAX_VALUE)
private val NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L)

private fun wallClockNtpOffsetNs(monoNs: Long): Long {
    val ntpNs = ntp64Now()
        .and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))
        .multiply(NANOS_PER_SECOND)
        .shiftRight(32)
    return ntpNs.subtract(BigInteger.valueOf(monoNs)).toLong()
}

private fun ntp64Now(): BigInteger {
    val seconds = System.currentTimeMillis() / 1000.0 + NTP_EPOCH_OFFSET
    val whole = Math.floor(seconds).toLong()
    val fraction = Math.floor((seconds - whole) * TWO32.toDouble()).toLong()
    return BigInteger.valueOf(whole).shiftLeft(32).or(BigInteger.valueOf(fraction))
}

private fun ntpFromNanos(ns: Long): BigInteger {
    val seconds = BigInteger.valueOf(Math.floorDiv(ns, 1_000_000_000L))
    val nanos = BigInteger.valueOf(Math.floorMod(ns, 1_000_000_000L))
    return seconds.shiftLeft(32).or(nanos.shiftLeft(32).divide(NANOS_PER_SECOND))
}

private fun ntpBytes(value: BigInteger): ByteArray {
    val bytes = value.and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)).toByteArray()
    return ByteArray(8).also { target ->
        val source = if (bytes.size > 8) bytes.copyOfRange(bytes.size - 8, bytes.size) else bytes
        source.copyInto(target, target.size - source.size)
    }
}

private fun readNtp(value: ByteArray, offset: Int): BigInteger =
    BigInteger(1, value.copyOfRange(offset, offset + 8))

private fun writeU16Be(target: ByteArray, offset: Int, value: Int) {
    target[offset] = ((value ushr 8) and 0xff).toByte()
    target[offset + 1] = (value and 0xff).toByte()
}
