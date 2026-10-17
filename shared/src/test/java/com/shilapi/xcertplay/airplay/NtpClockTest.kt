package com.shilapi.xcertplay.airplay

import java.io.IOException
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class NtpClockTest {
    @Test
    fun ntpTickConversionsRejectPositiveAndNegativeLongOverflow() {
        assertEquals(1_000_000_000L, convertTicks("ntpTicksToNanosCeiling", TWO32))
        assertEquals(-1_000_000_000L, convertTicks("ntpTicksToNanosRounded", TWO32.negate()))
        val beyondLong = BigInteger.valueOf(Long.MAX_VALUE)
            .multiply(TWO32)
            .divide(BigInteger.valueOf(1_000_000_000L))
            .add(TWO32)
        assertNull(convertTicks("ntpTicksToNanosCeiling", beyondLong))
        assertNull(convertTicks("ntpTicksToNanosRounded", beyondLong))
        assertNull(convertTicks("ntpTicksToNanosRounded", beyondLong.negate()))
    }

    @Test
    fun provisionalWallClockEstimateCannotScheduleFramesBeforeTimingSync() {
        NtpClock().use { clock ->
            // Choose a raw screen timestamp matching the clock's initial wall-clock estimate. It
            // would map successfully without the explicit real-sync gate.
            val rawScreenTimestamp = clock.syncedNtp()
                .subtract(UNIX_TO_NTP_TICKS)
                .and(UINT64_MASK)
                .toLong()

            assertNull(
                clock.presentationMonotonicNanos(
                    presentationTimeNtp64Bits = rawScreenTimestamp,
                    sampleMonotonicNanos = System.nanoTime(),
                ),
            )
            assertNull(clock.latestVideoClockMeasurement())
            assertNull(clock.videoClockLease())
        }
    }

    @Test
    fun preStartTimingRequestsAreNotAnsweredAndDoNotCreateMeasurement() {
        val loopback = InetAddress.getByName("::1")
        DatagramSocket(0, loopback).use { phone ->
            phone.soTimeout = 150
            NtpClock().use { clock ->
                val timingPort = clock.listen()
                val request = ByteArray(32).also {
                    it[0] = 0x80.toByte()
                    it[1] = 210.toByte()
                    it[3] = 7
                }
                phone.send(DatagramPacket(request, request.size, loopback, timingPort))
                try {
                    receivePacket(phone)
                    fail("pre-start PT210 must not receive PT211")
                } catch (_: SocketTimeoutException) {
                    // No peer has been pinned yet.
                }
                awaitCondition {
                    clock.timingPeerDiagnostics().beforeStartOrAfterStopPackets == 1L
                }
                assertNull(clock.latestVideoClockMeasurement())
                assertNull(clock.videoClockLease())
            }
        }
    }

    @Test
    fun responseRequiresExactAddressAndPortAndIsSingleUse() {
        val loopback = InetAddress.getByName("::1")
        DatagramSocket(0, loopback).use { phone ->
            DatagramSocket(0, loopback).use { wrongPort ->
                phone.soTimeout = 2_000
                NtpClock().use { clock ->
                    val timingPort = clock.listen()
                    clock.start(loopback, phone.localPort)
                    val request = receivePacket(phone)
                    val response = responseTo(request, remoteOffsetMillis = 20)

                    wrongPort.send(DatagramPacket(response, response.size, loopback, timingPort))
                    injectPacket(clock, response, InetSocketAddress(InetAddress.getByName("::2"), phone.localPort))
                    forceNextRequest(clock)
                    val nextRequest = receivePacket(phone)
                    val nextResponse = responseTo(nextRequest, remoteOffsetMillis = 20)
                    wrongPort.send(DatagramPacket(nextResponse, nextResponse.size, loopback, timingPort))
                    injectPacket(
                        clock,
                        nextResponse,
                        InetSocketAddress(InetAddress.getByName("::2"), phone.localPort),
                    )
                    awaitCondition {
                        val stats = clock.timingPeerDiagnostics()
                        stats.wrongPortPackets == 2L && stats.wrongAddressPackets == 2L
                    }
                    val diagnostics = clock.timingPeerDiagnostics()
                    assertEquals(2L, diagnostics.wrongPortPackets)
                    assertEquals(2L, diagnostics.wrongAddressPackets)
                    assertEquals(InetSocketAddress(loopback, phone.localPort), diagnostics.configuredPeer)
                    assertNull(clock.latestVideoClockMeasurement())
                    assertNull(clock.videoClockLease())
                    val raw = clock.syncedNtp().subtract(UNIX_TO_NTP_TICKS)
                        .and(UINT64_MASK).toLong()
                    assertNull(clock.presentationMonotonicNanos(raw))

                    phone.send(DatagramPacket(nextResponse, nextResponse.size, loopback, timingPort))
                    val accepted = awaitMeasurement(clock)
                    assertEquals(InetSocketAddress(loopback, phone.localPort), accepted.peer)
                    assertEquals(1L, accepted.sampleGeneration)
                    assertEquals(readNtp64(nextRequest, 24), accepted.originateNtp)
                    assertTrue(accepted.localReceiveMonotonicNanos >= accepted.localSendMonotonicNanos)
                    assertTrue(accepted.roundTripNanos >= 0)
                    val observedElapsed = accepted.localReceiveMonotonicNanos -
                        accepted.localSendMonotonicNanos
                    assertTrue(kotlin.math.abs(accepted.roundTripNanos - observedElapsed) <= 2)
                    assertEquals(
                        accepted.roundTripNanos / 2 + accepted.roundTripNanos % 2 + 1,
                        accepted.errorBoundNanos,
                    )
                    assertTrue(kotlin.math.abs(accepted.offsetNanos) < 1_000_000_000L)
                    assertNull(clock.videoClockLease()) // One accepted reply is not a selected sync.

                    phone.send(DatagramPacket(nextResponse, nextResponse.size, loopback, timingPort))
                    awaitCondition {
                        clock.timingPeerDiagnostics().unmatchedResponsePackets == 1L
                    }
                    assertEquals(1L, clock.latestVideoClockMeasurement()?.sampleGeneration)
                    assertEquals(1L, clock.timingPeerDiagnostics().unmatchedResponsePackets)
                }
            }
        }
    }

    @Test
    fun videoLeaseExpiresAndRevokesOnClockStepAndClose() {
        val loopback = InetAddress.getByName("::1")
        DatagramSocket(0, loopback).use { phone ->
            phone.soTimeout = 2_000
            val clock = NtpClock()
            try {
                val timingPort = clock.listen()
                clock.start(loopback, phone.localPort)
                repeat(2) { index ->
                    if (index != 0) forceNextRequest(clock)
                    val request = receivePacket(phone)
                    val response = responseTo(request, remoteOffsetMillis = 20)
                    phone.send(DatagramPacket(response, response.size, loopback, timingPort))
                    if (index == 0) {
                        awaitMeasurement(clock)
                    } else {
                        awaitCondition { clock.latestVideoClockMeasurement() == null }
                    }
                }
                // The second accepted probe selects the first sample and steps the provisional
                // wall-clock estimate. That step invalidates both pre-step measurements.
                awaitCondition { clock.latestVideoClockMeasurement() == null }
                assertNull(clock.videoClockLease())

                forceNextRequest(clock)
                val request = receivePacket(phone)
                val response = responseTo(request, remoteOffsetMillis = 20)
                phone.send(DatagramPacket(response, response.size, loopback, timingPort))
                awaitMeasurement(clock, generation = 3L)
                assertNull(clock.videoClockLease()) // Accepted does not mean selected/applied.

                forceNextRequest(clock)
                val nextRequest = receivePacket(phone)
                val nextResponse = responseTo(nextRequest, remoteOffsetMillis = 20)
                phone.send(DatagramPacket(nextResponse, nextResponse.size, loopback, timingPort))
                val lease = awaitLease(clock)
                assertTrue(lease.measurement.sampleGeneration in 3L..4L)
                assertEquals(1L, lease.measurement.clockEraGeneration)
                assertTrue(lease.ageNanos >= 0)
                val rawScreenTimestamp = clock.syncedNtp().subtract(UNIX_TO_NTP_TICKS)
                    .and(UINT64_MASK).toLong()
                assertNotNull(clock.presentationMonotonicNanos(rawScreenTimestamp))
                assertNull(clock.videoClockLease(maxAgeNanos = 0))
                assertNull(clock.latestVideoClockMeasurement(maxAgeNanos = 0))
                Thread.sleep(5)
                assertNull(clock.videoClockLease(maxAgeNanos = 1_000_000))
                assertNull(clock.latestVideoClockMeasurement(maxAgeNanos = 1_000_000))

                forceClockStep(clock)
                assertNull(clock.videoClockLease())
                assertNull(clock.latestVideoClockMeasurement())
                assertNull(clock.presentationMonotonicNanos(rawScreenTimestamp))
                clock.close()
                assertNull(clock.videoClockLease())
                assertNull(clock.latestVideoClockMeasurement())
                assertNull(clock.presentationMonotonicNanos(rawScreenTimestamp))
            } finally {
                clock.close()
            }
        }
    }

    @Test
    fun unappliedSlewAndDiscontinuityCannotGrantStaleVideoLease() {
        val loopback = InetAddress.getByName("::1")
        DatagramSocket(0, loopback).use { phone ->
            phone.soTimeout = 2_000
            NtpClock().use { clock ->
                val timingPort = clock.listen()
                clock.start(loopback, phone.localPort)
                sendReply(clock, phone, timingPort, loopback, 0, force = false)
                awaitMeasurement(clock, generation = 1L)
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitCondition { clock.latestVideoClockMeasurement() == null }

                sendReply(clock, phone, timingPort, loopback, 120, force = true, delayMillis = 30)
                awaitMeasurement(clock, generation = 3L)
                sendReply(clock, phone, timingPort, loopback, 120, force = true, delayMillis = 30)
                awaitMeasurement(clock, generation = 4L)
                // The picker slewed only 1/8 of ~120 ms. Raw RTT precision must not be
                // advertised as the local video clock's effective precision.
                assertNull(clock.videoClockLease())
                val raw = clock.syncedNtp().subtract(UNIX_TO_NTP_TICKS)
                    .and(UINT64_MASK).toLong()
                assertNull(clock.presentationMonotonicNanos(raw))

                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitMeasurement(clock, generation = 5L)
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                assertNotNull(awaitLease(clock))

                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitMeasurement(clock, generation = 7L)
                // Higher RTT keeps the prior zero-offset probe as picker candidate. A newly
                // accepted >128 ms jump must invalidate it instead of re-granting its lease.
                forceNextRequest(clock)
                val jumpRequest = receivePacket(phone)
                Thread.sleep(30)
                val jumpResponse = responseTo(jumpRequest, remoteOffsetMillis = 250)
                phone.send(DatagramPacket(jumpResponse, jumpResponse.size, loopback, timingPort))
                awaitMeasurement(clock, generation = 8L)
                assertNull(clock.videoClockLease())
                assertNull(clock.presentationMonotonicNanos(raw))
            }
        }
    }

    @Test
    fun belowStepButOutOfToleranceReplyImmediatelyRevokesSelectedLease() {
        val loopback = InetAddress.getByName("::1")
        DatagramSocket(0, loopback).use { phone ->
            phone.soTimeout = 2_000
            NtpClock().use { clock ->
                val timingPort = clock.listen()
                clock.start(loopback, phone.localPort)
                sendReply(clock, phone, timingPort, loopback, 0, force = false)
                awaitMeasurement(clock, generation = 1L)
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitCondition { clock.latestVideoClockMeasurement() == null }
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitMeasurement(clock, generation = 3L)
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                assertNotNull(awaitLease(clock))

                sendReply(clock, phone, timingPort, loopback, 100, force = true)
                awaitMeasurement(clock, generation = 5L)
                assertNull(clock.videoClockLease())
                val raw = clock.syncedNtp().subtract(UNIX_TO_NTP_TICKS)
                    .and(UINT64_MASK).toLong()
                assertNull(clock.presentationMonotonicNanos(raw))
            }
        }
    }

    @Test
    fun newTwentyMillisecondObservationWidensOldSelectedErrorUntilNextSelection() {
        val loopback = InetAddress.getByName("::1")
        DatagramSocket(0, loopback).use { phone ->
            phone.soTimeout = 2_000
            NtpClock().use { clock ->
                val timingPort = clock.listen()
                clock.start(loopback, phone.localPort)
                sendReply(clock, phone, timingPort, loopback, 0, force = false)
                awaitMeasurement(clock, generation = 1L)
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitCondition { clock.latestVideoClockMeasurement() == null }
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitMeasurement(clock, generation = 3L)
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                val oldLease = awaitLease(clock)
                assertTrue(oldLease.effectiveErrorBoundNanos < 10_000_000L)

                forceNextRequest(clock)
                val request = receivePacket(phone)
                val response = responseTo(request, remoteOffsetMillis = 20)
                val receiveMonotonicNanos = pendingSendMonotonicNanos(clock) + 2_000_000L
                while (System.nanoTime() < receiveMonotonicNanos) Thread.sleep(1)
                injectPacket(
                    clock,
                    response,
                    InetSocketAddress(loopback, phone.localPort),
                    receiveMonotonicNanos,
                )
                awaitMeasurement(clock, generation = 5L)
                val widened = requireNotNull(clock.videoClockLease())
                assertEquals(oldLease.measurement.sampleGeneration, widened.measurement.sampleGeneration)
                assertTrue(widened.effectiveErrorBoundNanos > oldLease.effectiveErrorBoundNanos)
                assertTrue(widened.effectiveErrorBoundNanos >= 10_000_000L)
                assertTrue(widened.effectiveErrorBoundNanos <= 30_000_000L)
            }
        }
    }

    @Test
    fun exactHalfEraResponseNeverPublishesOrPerturbsExistingVideoLease() {
        val loopback = InetAddress.getByName("::1")
        DatagramSocket(0, loopback).use { phone ->
            phone.soTimeout = 2_000
            NtpClock().use { clock ->
                val timingPort = clock.listen()
                clock.start(loopback, phone.localPort)

                val firstRequest = receivePacket(phone)
                val ambiguous = ambiguousHalfEraResponse(firstRequest)
                phone.send(DatagramPacket(ambiguous, ambiguous.size, loopback, timingPort))
                awaitCondition { clock.timingPeerDiagnostics().invalidResponsePackets == 1L }
                assertEquals(0L, clock.timingPeerDiagnostics().acceptedResponseGeneration)
                assertEquals(
                    "ambiguous-half-era-delta",
                    clock.timingPeerDiagnostics().lastInvalidResponseReason,
                )
                assertNull(clock.latestVideoClockMeasurement())
                assertNull(clock.videoClockLease())

                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitMeasurement(clock, generation = 1L)
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitCondition { clock.latestVideoClockMeasurement() == null }
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitMeasurement(clock, generation = 3L)
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                val selected = awaitLease(clock)

                forceNextRequest(clock)
                val laterRequest = receivePacket(phone)
                val laterAmbiguous = ambiguousHalfEraResponse(laterRequest)
                phone.send(DatagramPacket(laterAmbiguous, laterAmbiguous.size, loopback, timingPort))
                awaitCondition { clock.timingPeerDiagnostics().invalidResponsePackets == 2L }
                val after = requireNotNull(clock.videoClockLease())
                assertEquals(4L, clock.timingPeerDiagnostics().acceptedResponseGeneration)
                assertEquals(selected.measurement.sampleGeneration, after.measurement.sampleGeneration)
                assertEquals(selected.effectiveErrorBoundNanos, after.effectiveErrorBoundNanos)
                assertEquals(4L, clock.latestVideoClockMeasurement()?.sampleGeneration)

                forceNextRequest(clock)
                val processingRequest = receivePacket(phone)
                val processingAmbiguous = responseTo(processingRequest, 0)
                val t1 = readNtp64(processingRequest, 24)
                writeNtp64(processingAmbiguous, 16, t1)
                writeNtp64(processingAmbiguous, 24, t1.add(NTP64_HALF_RANGE))
                phone.send(DatagramPacket(
                    processingAmbiguous,
                    processingAmbiguous.size,
                    loopback,
                    timingPort,
                ))
                awaitCondition { clock.timingPeerDiagnostics().invalidResponsePackets == 3L }
                assertEquals(4L, clock.timingPeerDiagnostics().acceptedResponseGeneration)
                assertEquals(selected.effectiveErrorBoundNanos,
                    clock.videoClockLease()?.effectiveErrorBoundNanos)
            }
        }
    }

    @Test
    fun pickerCannotForgetNewerHigherRttTwentyMillisecondContradiction() {
        val loopback = InetAddress.getByName("::1")
        DatagramSocket(0, loopback).use { phone ->
            phone.soTimeout = 2_000
            NtpClock().use { clock ->
                val timingPort = clock.listen()
                clock.start(loopback, phone.localPort)
                sendReply(clock, phone, timingPort, loopback, 0, force = false)
                awaitMeasurement(clock, generation = 1L)
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitCondition { clock.latestVideoClockMeasurement() == null }
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitMeasurement(clock, generation = 3L)
                sendReply(clock, phone, timingPort, loopback, 0, force = true)
                awaitLease(clock)

                forceNextRequest(clock)
                val lowerRttRequest = receivePacket(phone)
                injectPacket(
                    clock,
                    responseTo(lowerRttRequest, 0),
                    InetSocketAddress(loopback, phone.localPort),
                    pendingSendMonotonicNanos(clock) + 1L,
                )
                awaitMeasurement(clock, generation = 5L)

                forceNextRequest(clock)
                val newerRequest = receivePacket(phone)
                val newerReceive = pendingSendMonotonicNanos(clock) + 10_000_000L
                while (System.nanoTime() < newerReceive) Thread.sleep(1)
                injectPacket(
                    clock,
                    responseTo(newerRequest, 20),
                    InetSocketAddress(loopback, phone.localPort),
                    newerReceive,
                )
                awaitMeasurement(clock, generation = 6L)
                val lease = requireNotNull(clock.videoClockLease())
                assertEquals(5L, lease.measurement.sampleGeneration) // lower RTT A selected
                assertTrue(lease.effectiveErrorBoundNanos >= 18_000_000L)
                assertTrue(lease.effectiveErrorBoundNanos <= 30_000_000L)
            }
        }
    }

    @Test
    fun ntp64EchoAndRemoteDeltasSurviveEraWrapWithoutSignedOverflow() {
        val loopback = InetAddress.getByName("::1")
        data class EraCase(
            val localNtpNanos: Long,
            val remoteOffsetMillis: Long,
            val remoteReceiveBeforeOriginate: Boolean,
            val expectedOffsetPositive: Boolean,
        )
        val cases = listOf(
            // Local t1 is already in the next era: the wire echo is its low 64 bits.
            EraCase(NTP_ERA_WRAP_NANOS + 5_000_000_000L, 10, false, true),
            // Remote t2/t3 cross 2036 while local t1/t4 are still in the prior era.
            EraCase(NTP_ERA_WRAP_NANOS - 5_000_000_000L, 6_000, true, true),
            // A negative remote delta crosses backwards into the previous era.
            EraCase(NTP_ERA_WRAP_NANOS + 5_000_000_000L, -6_000, false, false),
        )
        cases.forEach { scenario ->
            DatagramSocket(0, loopback).use { phone ->
                phone.soTimeout = 2_000
                NtpClock().use { clock ->
                    val timingPort = clock.listen()
                    setClockNtpNanos(clock, scenario.localNtpNanos)
                    clock.start(loopback, phone.localPort)
                    val request = receivePacket(phone)
                    val response = responseTo(request, scenario.remoteOffsetMillis)
                    phone.send(DatagramPacket(response, response.size, loopback, timingPort))
                    val sample = awaitMeasurement(clock)
                    assertEquals(readNtp64(request, 24), sample.originateNtp)
                    assertEquals(
                        scenario.remoteReceiveBeforeOriginate,
                        sample.remoteReceiveNtp < sample.originateNtp,
                    )
                    assertEquals(scenario.expectedOffsetPositive, sample.offsetNanos > 0)
                    assertTrue(sample.roundTripNanos >= 0)
                    assertTrue(sample.roundTripNanos < 2_000_000_000L)
                    if (scenario.remoteOffsetMillis == 10L) {
                        sendReply(clock, phone, timingPort, loopback, 10, force = true)
                        awaitCondition { clock.latestVideoClockMeasurement() == null }
                        sendReply(clock, phone, timingPort, loopback, 10, force = true)
                        awaitMeasurement(clock, generation = 3L)
                        sendReply(clock, phone, timingPort, loopback, 10, force = true)
                        assertNotNull(awaitLease(clock))
                        val raw = clock.syncedNtp().subtract(UNIX_TO_NTP_TICKS)
                            .and(UINT64_MASK).toLong()
                        assertNotNull(clock.presentationMonotonicNanos(raw))
                    }
                }
            }
        }
    }

    @Test
    fun ntpEraRollover2036HandlesBothLocalAndRemoteIntervalsAcrossZero() {
        val loopback = InetAddress.getByName("::1")
        DatagramSocket(0, loopback).use { phone ->
            phone.soTimeout = 2_000
            NtpClock().use { clock ->
                val timingPort = clock.listen()
                setClockNtpNanos(clock, NTP_ERA_WRAP_NANOS - 10_000_000_000L)
                clock.start(loopback, phone.localPort)
                val request = receivePacket(phone)
                val t1 = readNtp64(request, 24)
                val t2 = t1.add(ntpMillis(8_000)).and(UINT64_MASK)
                val t3 = t2.add(ntpMillis(4_000)).and(UINT64_MASK)
                val reply = responseTo(request, 0)
                writeNtp64(reply, 16, t2)
                writeNtp64(reply, 24, t3)
                assertEquals(t2, readNtp64(reply, 16))
                assertEquals(t3, readNtp64(reply, 24))
                val probeSendMonotonic = pendingSendMonotonicNanos(clock)
                val syntheticReceiveMonotonic = probeSendMonotonic + 15_000_000_000L
                injectPacket(
                    clock,
                    reply,
                    InetSocketAddress(loopback, phone.localPort),
                    syntheticReceiveMonotonic,
                )
                assertEquals(1L, clock.timingPeerDiagnostics().acceptedResponseGeneration)
                val sample = lastMeasurementWithoutAgeCheck(clock)
                assertTrue(t1 < t2)
                assertTrue(t2 > t3)
                assertTrue(sample.localReceiveNtp < t1)
                assertTrue(sample.roundTripNanos in 10_500_000_000L..11_500_000_000L)
                assertTrue(sample.offsetNanos in 2_000_000_000L..3_000_000_000L)
            }
        }
    }

    @Test
    fun negativeRemoteProcessingAndLocalNanosecondOverflowCannotPublishMeasurement() {
        val loopback = InetAddress.getByName("::1")
        DatagramSocket(0, loopback).use { phone ->
            phone.soTimeout = 2_000
            NtpClock().use { clock ->
                val timingPort = clock.listen()
                setClockNtpNanos(clock, NTP_ERA_WRAP_NANOS + 5_000_000_000L)
                clock.start(loopback, phone.localPort)
                val request = receivePacket(phone)
                val invalid = responseTo(request, 0)
                val t2 = readNtp64(invalid, 16)
                writeNtp64(invalid, 24, t2.subtract(TWO32.divide(BigInteger.valueOf(1_000L))))
                phone.send(DatagramPacket(invalid, invalid.size, loopback, timingPort))
                awaitCondition { pendingProbeIsClear(clock) }
                assertEquals(0L, clock.timingPeerDiagnostics().acceptedResponseGeneration)
                assertEquals(
                    "negative-processing-or-round-trip",
                    clock.timingPeerDiagnostics().lastInvalidResponseReason,
                )
                assertNull(clock.latestVideoClockMeasurement())
                assertNull(clock.videoClockLease())

                setClockOffsetNanos(clock, Long.MAX_VALUE)
                val provisional = clock.syncedNtp()
                assertTrue(provisional.signum() >= 0 && provisional <= UINT64_MASK)
                assertNull(clock.videoClockLease())
                forceNextRequest(clock) // Checked addition rejects before creating a pending t1.
                assertTrue(pendingProbeIsClear(clock))
                assertNull(clock.latestVideoClockMeasurement())
            }
        }
    }

    @Test
    fun bindFailureRetainsExactSocketUntilCloseRetrySucceeds() {
        val socket = FailingBindSocket()
        val clock = NtpClock { socket }

        try {
            clock.listen()
            fail("bind failure expected")
        } catch (failure: IOException) {
            assertTrue(failure.message.orEmpty().contains("bind failed"))
            assertEquals(1, failure.suppressed.size)
        }
        assertEquals(1, socket.closeCalls)

        socket.allowClose.set(true)
        clock.close()

        assertEquals(2, socket.closeCalls)
        assertSame(socket, socket.closedOwner)
    }

    private class FailingBindSocket : DatagramSocket(null as SocketAddress?) {
        val allowClose = AtomicBoolean(false)
        var closeCalls = 0
        var closedOwner: DatagramSocket? = null

        override fun bind(addr: SocketAddress?) {
            throw IOException("bind failed")
        }

        override fun close() {
            closeCalls++
            if (!allowClose.get()) throw IOException("close pending")
            closedOwner = this
            super.close()
        }
    }

    private fun receivePacket(socket: DatagramSocket): ByteArray {
        val packet = DatagramPacket(ByteArray(128), 128)
        socket.receive(packet)
        return packet.data.copyOf(packet.length)
    }

    private fun responseTo(request: ByteArray, remoteOffsetMillis: Long): ByteArray {
        assertEquals(32, request.size)
        assertEquals(210, request[1].toInt() and 0xff)
        val originate = readNtp64(request, 24)
        val remote = originate.add(
            BigInteger.valueOf(remoteOffsetMillis).multiply(TWO32)
                .divide(BigInteger.valueOf(1_000L)),
        )
        return ByteArray(32).also { response ->
            response[0] = 0x80.toByte()
            response[1] = 211.toByte()
            response[3] = 7
            writeNtp64(response, 8, originate)
            writeNtp64(response, 16, remote)
            writeNtp64(response, 24, remote)
        }
    }

    private fun ambiguousHalfEraResponse(request: ByteArray): ByteArray {
        val t1 = readNtp64(request, 24)
        val ambiguous = t1.add(NTP64_HALF_RANGE).and(UINT64_MASK)
        return responseTo(request, 0).also { response ->
            writeNtp64(response, 16, ambiguous)
            writeNtp64(response, 24, ambiguous)
        }
    }

    private fun sendReply(
        clock: NtpClock,
        phone: DatagramSocket,
        timingPort: Int,
        loopback: InetAddress,
        remoteOffsetMillis: Long,
        force: Boolean,
        delayMillis: Long = 0,
    ) {
        if (force) forceNextRequest(clock)
        val request = receivePacket(phone)
        if (delayMillis > 0) Thread.sleep(delayMillis)
        val response = responseTo(request, remoteOffsetMillis)
        phone.send(DatagramPacket(response, response.size, loopback, timingPort))
    }

    private fun readNtp64(packet: ByteArray, offset: Int): BigInteger =
        BigInteger(1, packet.copyOfRange(offset, offset + 8))

    private fun writeNtp64(packet: ByteArray, offset: Int, value: BigInteger) {
        val source = value.and(UINT64_MASK).toByteArray().takeLast(8).toByteArray()
        java.util.Arrays.fill(packet, offset, offset + 8, 0.toByte())
        source.copyInto(packet, offset + 8 - source.size)
    }

    private fun awaitMeasurement(
        clock: NtpClock,
        generation: Long = 1L,
    ): NtpClock.VideoClockMeasurement {
        var found: NtpClock.VideoClockMeasurement? = null
        awaitCondition {
            found = clock.latestVideoClockMeasurement()
            found?.sampleGeneration == generation
        }
        return assertNotNull(found).let { found!! }
    }

    private fun awaitLease(clock: NtpClock): NtpClock.VideoClockLease {
        var found: NtpClock.VideoClockLease? = null
        awaitCondition {
            found = clock.videoClockLease()
            found != null
        }
        return assertNotNull(found).let { found!! }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("timing condition not observed")
    }

    private fun forceNextRequest(clock: NtpClock) {
        NtpClock::class.java.getDeclaredMethod("sendRequest").apply { isAccessible = true }
            .invoke(clock)
    }

    private fun injectPacket(clock: NtpClock, packet: ByteArray, source: InetSocketAddress) {
        injectPacket(clock, packet, source, System.nanoTime())
    }

    private fun injectPacket(
        clock: NtpClock,
        packet: ByteArray,
        source: InetSocketAddress,
        receiveMonotonicNanos: Long,
    ) {
        NtpClock::class.java.getDeclaredMethod(
            "handleMessage",
            ByteArray::class.java,
            InetSocketAddress::class.java,
            java.lang.Long.TYPE,
        ).apply { isAccessible = true }.invoke(clock, packet, source, receiveMonotonicNanos)
    }

    private fun pendingProbeIsClear(clock: NtpClock): Boolean =
        NtpClock::class.java.getDeclaredField("pendingProbe")
            .apply { isAccessible = true }.get(clock) == null

    private fun pendingSendMonotonicNanos(clock: NtpClock): Long {
        val probe = NtpClock::class.java.getDeclaredField("pendingProbe")
            .apply { isAccessible = true }.get(clock)!!
        return probe.javaClass.getDeclaredField("sendMonotonicNanos")
            .apply { isAccessible = true }.getLong(probe)
    }

    private fun lastMeasurementWithoutAgeCheck(clock: NtpClock): NtpClock.VideoClockMeasurement =
        NtpClock::class.java.getDeclaredField("lastVideoMeasurement")
            .apply { isAccessible = true }.get(clock) as NtpClock.VideoClockMeasurement

    private fun ntpMillis(value: Long): BigInteger = BigInteger.valueOf(value)
        .multiply(TWO32).divide(BigInteger.valueOf(1_000L))

    private fun convertTicks(methodName: String, ticks: BigInteger): Long? =
        Class.forName("com.shilapi.xcertplay.airplay.NtpClockKt")
            .getDeclaredMethod(methodName, BigInteger::class.java)
            .apply { isAccessible = true }
            .invoke(null, ticks) as Long?

    private fun forceClockStep(clock: NtpClock) {
        val lock = NtpClock::class.java.getDeclaredField("clockLock").apply { isAccessible = true }
            .get(clock)!!
        synchronized(lock) {
            NtpClock::class.java.getDeclaredMethod("applyOffset", java.lang.Double.TYPE)
                .apply { isAccessible = true }.invoke(clock, 1.0)
        }
    }

    private fun setClockNtpNanos(clock: NtpClock, desiredNtpNanos: Long) {
        setClockOffsetNanos(clock, Math.subtractExact(desiredNtpNanos, System.nanoTime()))
    }

    private fun setClockOffsetNanos(clock: NtpClock, offsetNanos: Long) {
        val lock = NtpClock::class.java.getDeclaredField("clockLock").apply { isAccessible = true }
            .get(clock)!!
        synchronized(lock) {
            NtpClock::class.java.getDeclaredField("clockOffsetNs")
                .apply { isAccessible = true }
                .setLong(clock, offsetNanos)
        }
    }


    private companion object {
        val UNIX_TO_NTP_TICKS: BigInteger =
            BigInteger.valueOf(2_208_988_800L).shiftLeft(32)
        val UINT64_MASK: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        val TWO32: BigInteger = BigInteger.ONE.shiftLeft(32)
        val NTP64_HALF_RANGE: BigInteger = BigInteger.ONE.shiftLeft(63)
        val NTP_ERA_WRAP_NANOS: Long = BigInteger.ONE.shiftLeft(32)
            .multiply(BigInteger.valueOf(1_000_000_000L)).longValueExact()
    }
}
