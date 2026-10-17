package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBufferedPcm16IngressTest {
    @Test
    fun authenticatedApapTcpLoopbackPassesOnlyExactBigEndianPcmToTheIndependentOutput() {
        val identity = MainBufferedIngressIdentity.create(70L)
        val output = FakeOutput()
        val binding = prepare(identity, output, MainBufferedPcm16ByteOrder.BIG_ENDIAN)
        val key = ByteArray(32) { (it * 3 + 1).toByte() }
        val limits = MainBufferedPcm16Ingress.limitsFor(format())
        val writer = EncryptedApapWriter(
            key,
            maxEncryptedBytes = limits.maximumFrameBytes - BufferedApapTcpCodec.PREFIX_BYTES,
        )
        val stream = ScriptedStream().apply {
            enqueue(frame(writer, limits, 10, 100, byteArrayOf(0x12, 0x34, 0x80.toByte(), 0x00,
                0x00, 0x01, 0xff.toByte(), 0xfe.toByte())))
            enqueue(frame(writer, limits, 11, 102, byteArrayOf(0x01, 0x02, 0x03, 0x04,
                0x05, 0x06, 0x07, 0x08)))
            enqueueEof()
        }
        val runtime = MainBufferedIngressRuntime(
            identity = identity,
            transport = stream,
            key = key,
            packetConsumer = binding,
            limits = limits,
        )

        runtime.start()

        assertTrue(runtime.awaitClosed(TIMEOUT_MILLIS))
        assertEquals(MainBufferedIngressTerminationReason.CLEAN_EOF,
            runtime.snapshot().terminationReason)
        assertEquals(2, output.accepted.size)
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x00, 0x80.toByte(),
            0x01, 0x00, 0xfe.toByte(), 0xff.toByte()), output.accepted[0])
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x04, 0x03,
            0x06, 0x05, 0x08, 0x07), output.accepted[1])
        assertEquals(16L, binding.snapshot().admittedPcmBytes)
        assertTrue(binding.snapshot().outputReady)
        binding.close()
        writer.close()
    }

    @Test
    fun explicitLittleEndianSelectionDoesNotSwapOrStripTwelveRtpBytes() {
        val identity = MainBufferedIngressIdentity.create(71L)
        val output = FakeOutput()
        val binding = prepare(identity, output, MainBufferedPcm16ByteOrder.LITTLE_ENDIAN)
        val payload = byteArrayOf(0x34, 0x12, 0x78, 0x56, 0xbc.toByte(), 0x9a.toByte(), 0xf0.toByte(), 0xde.toByte())
        binding.onPacket(Session(identity), packet(7, 20, payload))

        assertEquals(1, output.accepted.size)
        assertArrayEquals(payload, output.accepted.single())
        binding.close()
    }

    @Test
    fun rejectsUnknownFormatAndUnreadyOutputBeforeAnyMediaCanBeAdmitted() {
        val identity = MainBufferedIngressIdentity.create(72L)
        var opens = 0
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedPcm16Ingress.prepare(
                identity,
                3L,
                MainBufferedV27AcceptedFormat(0, 2, MainBufferedAudioFormatBits.AAC_LC_48000_STEREO),
                MainBufferedPcm16ByteOrder.BIG_ENDIAN,
                MainBufferedPcm16OutputFactory { opens++; FakeOutput() },
            )
        }
        assertEquals(0, opens)
        val wrongContentType = MainBufferedV27AcceptedFormat(
            0L,
            2L,
            MainBufferedAudioFormatBits.PCM_48000_16_STEREO,
        )
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedPcm16Ingress.prepare(
                identity,
                3L,
                wrongContentType,
                MainBufferedPcm16ByteOrder.BIG_ENDIAN,
                MainBufferedPcm16OutputFactory { opens++; FakeOutput() },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedPcm16Ingress.limitsFor(wrongContentType)
        }
        assertEquals(0, opens)
        val unready = FakeOutput(ready = false)
        assertThrows(IllegalStateException::class.java) {
            prepare(identity, unready, MainBufferedPcm16ByteOrder.BIG_ENDIAN)
        }
        assertEquals(1, unready.closes)
        assertFalse(unready.isReady)
    }

    @Test
    fun malformedPcmPayloadAndUnverifiedExtensionsFailClosed() {
        val identity = MainBufferedIngressIdentity.create(73L)
        val output = FakeOutput()
        val binding = prepare(identity, output, MainBufferedPcm16ByteOrder.BIG_ENDIAN)
        val short = packet(0, 0, byteArrayOf(1, 2, 3, 4))
        assertThrows(ApapProtocolException::class.java) {
            binding.onPacket(Session(identity), short)
        }
        assertEquals(MainBufferedPcm16IngressState.FAILED, binding.snapshot().state)
        assertTrue(output.accepted.isEmpty())
        assertEquals(1, output.flushes)
        assertEquals(1, output.closes)
        binding.close()

        val second = prepare(MainBufferedIngressIdentity.create(74L), FakeOutput(),
            MainBufferedPcm16ByteOrder.BIG_ENDIAN)
        assertThrows(IllegalStateException::class.java) {
            second.onPacket(Session(MainBufferedIngressIdentity.create(74L)),
                packet(0, 0, ByteArray(8)))
        }
        // A different identity is rejected before inspecting even a syntactically valid packet.
        second.close()

        val thirdIdentity = MainBufferedIngressIdentity.create(75L)
        val thirdOutput = FakeOutput()
        val third = prepare(thirdIdentity, thirdOutput, MainBufferedPcm16ByteOrder.BIG_ENDIAN)
        assertThrows(ApapProtocolException::class.java) {
            third.onPacket(Session(thirdIdentity), ApapPacket(
                ApapMediaHeader(0, 48_000, 0),
                listOf(ApapExtension(1L, byteArrayOf(1))),
                ByteArray(8),
            ))
        }
        assertTrue(thirdOutput.accepted.isEmpty())
        assertEquals(1, thirdOutput.flushes)
        assertEquals(1, thirdOutput.closes)
        third.close()
    }

    @Test
    fun flushClearsOldQueuedPcmAndResetsSequenceOnlyForExactIdentityAndStream() {
        val identity = MainBufferedIngressIdentity.create(76L)
        val output = FakeOutput()
        val binding = prepare(identity, output, MainBufferedPcm16ByteOrder.LITTLE_ENDIAN)
        binding.onPacket(Session(identity), packet(0x00ff_ffff, 100, ByteArray(8) { 1 }))
        binding.onPacket(Session(identity), packet(0, 102, ByteArray(8) { 2 }))
        assertEquals(2, output.accepted.size)

        assertThrows(IllegalStateException::class.java) {
            binding.flush(MainBufferedIngressIdentity.create(76L), 3L)
        }
        assertThrows(IllegalStateException::class.java) { binding.flush(identity, 4L) }
        assertEquals(0, output.flushes)
        binding.flush(identity, 3L)
        assertTrue(output.accepted.isEmpty())
        assertEquals(1, output.flushes)
        assertEquals(1L, binding.snapshot().flushGeneration)

        // The post-flush first packet is admitted only after explicit output retirement.
        binding.onPacket(Session(identity), packet(1000, 9000, ByteArray(8) { 3 }))
        assertEquals(1, output.accepted.size)
        binding.close()
        assertEquals(MainBufferedPcm16IngressState.CLOSED, binding.snapshot().state)
        assertThrows(IllegalStateException::class.java) {
            binding.onPacket(Session(identity), packet(1001, 9002, ByteArray(8)))
        }
    }

    @Test
    fun sequenceTimeBackpressureAndCloseFailureCannotMasqueradeAsReadiness() {
        val identity = MainBufferedIngressIdentity.create(77L)
        val output = FakeOutput(closeFailures = 1)
        val binding = prepare(identity, output, MainBufferedPcm16ByteOrder.LITTLE_ENDIAN)
        binding.onPacket(Session(identity), packet(10, 100, ByteArray(8)))
        val failure = assertThrows(ApapProtocolException::class.java) {
            binding.onPacket(Session(identity), packet(12, 102, ByteArray(8)))
        }
        assertTrue(output.accepted.isEmpty())
        assertEquals(1, output.flushes)
        assertEquals(1, output.closes)
        assertTrue(failure.suppressed.any { it is IOException })
        assertFalse(binding.snapshot().outputReady)
        binding.close()
        assertEquals(2, output.closes)
        assertEquals(MainBufferedPcm16IngressState.CLOSED, binding.snapshot().state)

        val anotherIdentity = MainBufferedIngressIdentity.create(78L)
        val backpressure = FakeOutput(accept = false)
        val another = prepare(anotherIdentity, backpressure, MainBufferedPcm16ByteOrder.BIG_ENDIAN)
        assertThrows(IllegalStateException::class.java) {
            another.onPacket(Session(anotherIdentity), packet(1, 1, ByteArray(8)))
        }
        assertEquals(0L, another.snapshot().admittedPackets)
        assertEquals(MainBufferedPcm16IngressState.FAILED, another.snapshot().state)
        assertEquals(1, backpressure.flushes)
        assertEquals(1, backpressure.closes)
        another.close()
    }

    @Test
    fun exactPcmLimitsBoundWireMemoryAndForbidExtensionData() {
        val limits = MainBufferedPcm16Ingress.limitsFor(format())
        assertEquals(2 * 4 + ApapPacketCodec.MIN_PACKET_BYTES +
            BufferedApapTcpCodec.PREFIX_BYTES + EncryptedApapPacketCodec.OVERHEAD_BYTES,
            limits.maximumFrameBytes)
        assertEquals(0, limits.packetLimits.maxExtensions)
        assertEquals(0, limits.packetLimits.maxTotalExtensionBytes)
        assertEquals(limits.maximumFrameBytes * 8, limits.maximumQueuedBytes)
    }

    private fun prepare(
        identity: MainBufferedIngressIdentity,
        output: FakeOutput,
        byteOrder: MainBufferedPcm16ByteOrder,
    ): MainBufferedPcm16Ingress = MainBufferedPcm16Ingress.prepare(
        identity,
        3L,
        format(),
        byteOrder,
        MainBufferedPcm16OutputFactory { output },
    )

    private fun format() = MainBufferedV27AcceptedFormat(
        contentType = 0x6c70636dL, // 'lpcm' remains an explicit caller-selected ct, not inferred.
        samplesPerFrame = 2L,
        audioFormat = MainBufferedAudioFormatBits.PCM_48000_16_STEREO,
    )

    private fun packet(sequence: Int, mediaTime: Long, payload: ByteArray): ApapPacket =
        ApapPacket(ApapMediaHeader(mediaTime, 48_000L, sequence), emptyList(), payload)

    private fun frame(
        writer: EncryptedApapWriter,
        limits: MainBufferedIngressLimits,
        sequence: Int,
        mediaTime: Long,
        payload: ByteArray,
    ): ByteArray = BufferedApapTcpCodec.encode(
        writer.encrypt(packet(sequence, mediaTime, payload), limits.packetLimits),
        limits.maximumFrameBytes,
    )

    private class FakeOutput(
        ready: Boolean = true,
        private val accept: Boolean = true,
        private var closeFailures: Int = 0,
    ) : MainBufferedPcm16Output {
        override var isReady = ready
            private set
        override var playbackEpoch = 0L
            private set
        override val outputInstance: Any = Any()
        override val firstQueuedMediaTimeValue: Long?
            get() = mediaTimes.firstOrNull()
        val accepted = ArrayList<ByteArray>()
        private val mediaTimes = ArrayList<Long>()
        var flushes = 0
        var closes = 0

        override fun offerPcmLittleEndian(pcm: ByteArray, mediaTimeValue: Long): Boolean {
            if (!accept || !isReady) return false
            accepted += pcm.copyOf()
            mediaTimes += mediaTimeValue
            return true
        }

        override fun pausePlayback() {
            check(isReady)
            accepted.clear()
            mediaTimes.clear()
            playbackEpoch++
        }

        override fun startPlayback(anchor: MainBufferedPcm16VerifiedAnchor): Boolean = false

        override fun flush() {
            check(isReady)
            accepted.clear()
            mediaTimes.clear()
            flushes++
            playbackEpoch++
        }

        override fun close() {
            closes++
            if (closeFailures-- > 0) throw IOException("injected output close failure")
            isReady = false
        }
    }

    private class Session(override val identity: MainBufferedIngressIdentity) :
        MainBufferedIngressSession {
        override fun snapshot(): MainBufferedIngressSnapshot = error("not needed")
        override fun awaitTerminal(timeoutMillis: Long): Boolean = error("not needed")
        override fun awaitClosed(timeoutMillis: Long): Boolean = error("not needed")
        override fun close() = Unit
    }

    private class ScriptedStream : BlockingDuplexByteStream {
        private val receiveQueue = LinkedBlockingQueue<ByteArray>()

        fun enqueue(bytes: ByteArray) { receiveQueue.put(bytes.copyOf()) }

        fun enqueueEof() { receiveQueue.put(ByteArray(0)) }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            receiveQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS)

        override fun send(data: ByteArray) = error("detached APAP ingress must never send")

        override fun close() { receiveQueue.offer(ByteArray(0)) }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
