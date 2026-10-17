package com.shilapi.xcertplay.airplay.mainbuffered

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBufferedAacLcIngressTest {
    @Test
    fun aacFormatNeedsExplicitOneRawAccessUnitFixtureAndStrictSelectedFormat() {
        val raw = byteArrayOf(0x21, 0x43, 0x65)
        val fixture = packet(1, 0, raw)
        val contract = AacLcOneAuPerApapContract.fromExplicitLocalFixture(fixture, raw)
        assertThrows(IllegalArgumentException::class.java) {
            AacLcOneAuPerApapContract.fromExplicitLocalFixture(fixture, byteArrayOf(0x21))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AacLcOneAuPerApapContract.fromExplicitLocalFixture(
                packet(1, 0, byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x10)),
                byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x10),
            )
        }
        val identity = MainBufferedIngressIdentity.create(8101)
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedAacLcIngress.prepare(identity, STREAM_ID, identity.generation,
                format(48_000, spf = 960), contract, AacLcDecodePortFactory { FakeDecoder() })
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedAacLcIngress.prepare(identity, STREAM_ID, identity.generation,
                MainBufferedV27AcceptedFormat(0x6c70636dL, 1024,
                    MainBufferedAudioFormatBits.AAC_LC_48000_STEREO), contract,
                AacLcDecodePortFactory { FakeDecoder() })
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedAacLcIngress.prepare(identity, STREAM_ID, identity.generation + 1,
                format(48_000), contract, AacLcDecodePortFactory { FakeDecoder() })
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedAacLcIngress.prepare(identity, STREAM_ID, identity.generation,
                format(44_100), contract, AacLcDecodePortFactory { FakeDecoder() })
        }
        val limits = MainBufferedAacLcIngress.limitsFor(format(44_100))
        assertEquals(0, limits.packetLimits.maxExtensions)
        assertEquals(MainBufferedAacLcIngress.MAX_ACCESS_UNIT_BYTES +
            ApapPacketCodec.MIN_PACKET_BYTES, limits.packetLimits.maxPacketBytes)
    }

    @Test
    fun ascIsOnlyTwoByteAot2ExactRateStereoWithoutUnsupportedTailBits() {
        assertEquals(44_100, parseTwoByteAacLcAsc(byteArrayOf(0x12, 0x10)))
        assertEquals(48_000, parseTwoByteAacLcAsc(byteArrayOf(0x11, 0x90.toByte())))
        listOf(
            byteArrayOf(), byteArrayOf(0x12), byteArrayOf(0x12, 0x10, 0),
            byteArrayOf(0x12, 0x11), // frameLengthFlag / unsupported tail bit
            byteArrayOf(0x12, 0x08), // mono channel config
            byteArrayOf(0x13, 0x10), // wrong frequency index
            byteArrayOf(0x22, 0x10), // wrong AOT
            byteArrayOf(0x17, 0x90.toByte()), // explicit/unsupported rate index
        ).forEach { assertEquals(null, parseTwoByteAacLcAsc(it)) }
        val source = byteArrayOf(0x11, 0x90.toByte())
        val config = AacLcDecoderConfig(48_000, 2, 1024, 1, source)
        source[0] = 0
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), config.audioSpecificConfig)
        assertThrows(IllegalArgumentException::class.java) {
            AacLcDecoderConfig(44_100, 2, 1024, 1, config.audioSpecificConfig)
        }
        val auBytes = byteArrayOf(1, 2, 3)
        val au = AacLcAccessUnit(1, 0, 48_000, 1, auBytes)
        auBytes[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), au.consumePayload())
        assertThrows(IllegalStateException::class.java) { au.consumePayload() }
        assertThrows(IllegalStateException::class.java) { au.payload }
    }

    @Test
    fun noCookieOrWrongOwnerNeverDecodesAndAuthenticityCannotBeSkipped() {
        val identity = MainBufferedIngressIdentity.create(8102)
        val decoder = FakeDecoder()
        val ingress = prepare(identity, decoder)
        val other = MainBufferedIngressIdentity.create(identity.generation)
        assertThrows(IllegalStateException::class.java) {
            ingress.stampAuthenticatedPacket(other, packet(1, 0))
        }
        assertThrows(IllegalStateException::class.java) {
            ingress.onPacket(Session(identity), packet(1, 0))
        }
        assertThrows(IllegalStateException::class.java) {
            ingress.configureFromAuthenticatedControl(other, STREAM_ID, identity.generation,
                cookie(1, 48_000))
        }
        assertThrows(IllegalStateException::class.java) {
            ingress.configureFromAuthenticatedControl(identity, STREAM_ID + 1,
                identity.generation, cookie(1, 48_000))
        }
        assertThrows(IllegalStateException::class.java) {
            ingress.configureFromAuthenticatedControl(identity, STREAM_ID,
                identity.generation + 1, cookie(1, 48_000))
        }
        assertTrue(decoder.decoded.isEmpty())
        val early = packet(1, 0)
        assertThrows(IllegalStateException::class.java) {
            ingress.onStampedPacket(Session(identity), early,
                ingress.stampAuthenticatedPacket(identity, early))
        }
        assertEquals(MainBufferedAacLcState.FAILED, ingress.snapshot().state)
        assertTrue(decoder.closes > 0)
        ingress.close()
    }

    @Test
    fun initialMagcAdmitsOneBoundedRawAuAndFlushCannotRelabelQueuedOldAu() {
        val identity = MainBufferedIngressIdentity.create(8103)
        val decoder = FakeDecoder(initiallyReady = false)
        val ingress = prepare(identity, decoder)
        assertEquals(MainBufferedAacLcState.AWAITING_COOKIE, ingress.snapshot().state)
        assertFalse(ingress.snapshot().decoderReady)
        assertTrue(ingress.configureFromAuthenticatedControl(identity, STREAM_ID,
            identity.generation, cookie(1, 48_000)))
        assertTrue(ingress.snapshot().decoderReady)
        val session = Session(identity)
        val first = packet(1, 100)
        ingress.onStampedPacket(session, first, ingress.stampAuthenticatedPacket(identity, first))
        assertEquals(listOf(100L), decoder.decoded.map { it.mediaTimeValue })
        assertEquals(1L, decoder.decoded.single().epoch)
        val old = packet(2, 1124)
        val oldStamp = ingress.stampAuthenticatedPacket(identity, old)
        ingress.flushFromAuthenticatedControl(identity, STREAM_ID, identity.generation,
            MainBufferedFlush(STREAM_ID, null,
                MainBufferedFlushPoint(2, 0, 1124, 48_000)))
        ingress.onStampedPacket(session, old, oldStamp)
        val late = packet(3, 1124)
        ingress.onStampedPacket(session, late, ingress.stampAuthenticatedPacket(identity, late))
        assertTrue(decoder.decoded.isEmpty())
        assertEquals(2L, ingress.snapshot().staleAccessUnits)
        assertEquals(2L, ingress.snapshot().epoch)
        assertEquals(2L, decoder.configs.last().epoch)
        val next = packet(4, 2148)
        ingress.onStampedPacket(session, next, ingress.stampAuthenticatedPacket(identity, next))
        assertEquals(listOf(2148L), decoder.decoded.map { it.mediaTimeValue })
        ingress.close()
    }

    @Test
    fun malformedAuthenticatedAuFailsClosedRatherThanGuessingPacketization() {
        val variants = listOf(
            packet(1, 0, ByteArray(0)),
            packet(1, 0, ByteArray(MainBufferedAacLcIngress.MAX_ACCESS_UNIT_BYTES + 1)),
            packet(1, 0, byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x10)),
            ApapPacket(ApapMediaHeader(0, 48_000, 1),
                listOf(ApapExtension(1, byteArrayOf(1))), byteArrayOf(1)),
            packet(1, 0, rate = 44_100),
        )
        variants.forEachIndexed { index, malformed ->
            val identity = MainBufferedIngressIdentity.create(8200L + index)
            val decoder = FakeDecoder()
            val ingress = prepare(identity, decoder)
            ingress.configureFromAuthenticatedControl(identity, STREAM_ID, identity.generation,
                cookie(1, 48_000))
            assertThrows(RuntimeException::class.java) {
                ingress.onStampedPacket(Session(identity), malformed,
                    ingress.stampAuthenticatedPacket(identity, malformed))
            }
            assertEquals(MainBufferedAacLcState.FAILED, ingress.snapshot().state)
            assertTrue(decoder.decoded.isEmpty())
            assertTrue(decoder.closes > 0)
        }
    }

    @Test
    fun sequenceAndMediaTimeMustBeOneAuApartWithinCurrentEpoch() {
        val identity = MainBufferedIngressIdentity.create(8104)
        val decoder = FakeDecoder()
        val ingress = prepare(identity, decoder)
        ingress.configureFromAuthenticatedControl(identity, STREAM_ID, identity.generation,
            cookie(1, 48_000))
        val first = packet(0x00ff_ffff, 100)
        ingress.onStampedPacket(Session(identity), first,
            ingress.stampAuthenticatedPacket(identity, first))
        val wrapped = packet(0, 1124)
        ingress.onStampedPacket(Session(identity), wrapped,
            ingress.stampAuthenticatedPacket(identity, wrapped))
        assertEquals(2L, ingress.snapshot().admittedAccessUnits)
        val gap = packet(2, 2148)
        assertThrows(ApapProtocolException::class.java) {
            ingress.onStampedPacket(Session(identity), gap,
                ingress.stampAuthenticatedPacket(identity, gap))
        }
        assertEquals(MainBufferedAacLcState.FAILED, ingress.snapshot().state)

        val timeIdentity = MainBufferedIngressIdentity.create(8109)
        val timeIngress = prepare(timeIdentity, FakeDecoder())
        timeIngress.configureFromAuthenticatedControl(timeIdentity, STREAM_ID,
            timeIdentity.generation, cookie(1, 48_000))
        val timeFirst = packet(1, 100)
        timeIngress.onStampedPacket(Session(timeIdentity), timeFirst,
            timeIngress.stampAuthenticatedPacket(timeIdentity, timeFirst))
        val timeGap = packet(2, 1125)
        assertThrows(ApapProtocolException::class.java) {
            timeIngress.onStampedPacket(Session(timeIdentity), timeGap,
                timeIngress.stampAuthenticatedPacket(timeIdentity, timeGap))
        }
        assertEquals(MainBufferedAacLcState.FAILED, timeIngress.snapshot().state)
    }

    @Test
    fun cookieReconfigurationRetiresOldStreamAndRateZeroRetiresOldDecoderEpoch() {
        val identity = MainBufferedIngressIdentity.create(8105)
        val decoder = FakeDecoder()
        val ingress = prepare(identity, decoder)
        assertTrue(ingress.configureFromAuthenticatedControl(identity, STREAM_ID,
            identity.generation, cookie(1, 48_000)))
        val old = packet(1, 0)
        val stamp = ingress.stampAuthenticatedPacket(identity, old)
        ingress.pauseFromAuthenticatedControl(identity, STREAM_ID, identity.generation,
            MainBufferedSetRateAndAnchor(STREAM_ID, 0, null))
        ingress.onStampedPacket(Session(identity), old, stamp)
        assertEquals(2L, ingress.snapshot().epoch)
        assertTrue(decoder.decoded.isEmpty())
        assertEquals(2L, decoder.configs.last().epoch)
        assertTrue(ingress.configureFromAuthenticatedControl(identity, STREAM_ID,
            identity.generation, cookie(1, 48_000))) // exact retry is idempotent
        assertFalse(ingress.configureFromAuthenticatedControl(identity, STREAM_ID,
            identity.generation, cookie(2, 48_000)))
        assertEquals(MainBufferedAacLcState.REBIND_REQUIRED, ingress.snapshot().state)
        assertTrue(decoder.closes > 0)
        assertThrows(IllegalStateException::class.java) {
            ingress.stampAuthenticatedPacket(identity, packet(2, 1024))
        }
        ingress.close()
    }

    @Test
    fun rateOneNeedsDecodedQueueClockMappingAndNativePlayReceipt() {
        val identity = MainBufferedIngressIdentity.create(8106)
        val decoder = FakeDecoder(queuedPcmOnDecode = false)
        val ingress = prepare(identity, decoder)
        ingress.configureFromAuthenticatedControl(identity, STREAM_ID, identity.generation,
            cookie(1, 48_000))
        val first = packet(1, 100)
        ingress.onStampedPacket(Session(identity), first,
            ingress.stampAuthenticatedPacket(identity, first))
        val command = MainBufferedSetRateAndAnchor(STREAM_ID, 1,
            MainBufferedPlaybackAnchor(
                MainBufferedTimelinePoint(1, 100, 48_000),
                MainBufferedNetworkTime(1, 2, 3, 0),
                MainBufferedTimelinePoint(1, 100, 48_000),
            ))
        assertFalse(ingress.startFromAuthenticatedControl(identity, STREAM_ID,
            identity.generation, command))
        assertEquals(0, decoder.starts)
        decoder.firstQueuedMediaTimeValue = 100
        assertFalse(ingress.startFromAuthenticatedControl(identity, STREAM_ID,
            identity.generation, command)) // no independent mapping
        assertEquals(0, decoder.starts)
        val now = 1_000_000_000L
        val mapping = MainBufferedPcm16AudioClockMapping { output, anchor ->
            MainBufferedPcm16CalibratedPlayCall(output, checkNotNull(anchor.networkTime),
                now + 1_000_000, now + 1_000_000_000, 5_000_000)
        }
        assertFalse(ingress.startFromAuthenticatedControl(identity, STREAM_ID,
            identity.generation, command, mapping, now)) // native play returned false
        assertEquals(1, decoder.starts)
        assertEquals(MainBufferedAacLcState.STOPPED, ingress.snapshot().state)
        ingress.close()
    }

    @Test
    fun invalidMagicCookieCannotConfigureOrLeaveDecoderAudible() {
        val identity = MainBufferedIngressIdentity.create(8107)
        val decoder = FakeDecoder()
        val ingress = prepare(identity, decoder)
        assertThrows(ApapProtocolException::class.java) {
            ingress.configureFromAuthenticatedControl(identity, STREAM_ID,
                identity.generation, MainBufferedMagicCookie(1,
                    byteArrayOf(0x12, 0x10))) // 44.1k cookie on selected 48k stream
        }
        assertEquals(MainBufferedAacLcState.FAILED, ingress.snapshot().state)
        assertTrue(decoder.closes > 0)
        assertTrue(decoder.configs.isEmpty())
    }

    @Test
    fun lateNativeStartAfterAuthenticatedFlushFailsClosedInsteadOfLeavingAudioPlaying() {
        val identity = MainBufferedIngressIdentity.create(8108)
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val decoder = FakeDecoder(
            startEntered = startEntered,
            releaseStart = releaseStart,
            nativeStartResult = true,
        )
        val ingress = prepare(identity, decoder)
        ingress.configureFromAuthenticatedControl(identity, STREAM_ID, identity.generation,
            cookie(1, 48_000))
        val first = packet(1, 100)
        ingress.onStampedPacket(Session(identity), first,
            ingress.stampAuthenticatedPacket(identity, first))
        val command = MainBufferedSetRateAndAnchor(STREAM_ID, 1,
            MainBufferedPlaybackAnchor(
                MainBufferedTimelinePoint(1, 100, 48_000),
                MainBufferedNetworkTime(1, 2, 3, 0),
                MainBufferedTimelinePoint(1, 100, 48_000),
            ))
        val now = 1_000_000_000L
        val mapping = MainBufferedPcm16AudioClockMapping { output, anchor ->
            MainBufferedPcm16CalibratedPlayCall(output, checkNotNull(anchor.networkTime),
                now + 1_000_000, now + 1_000_000_000, 5_000_000)
        }
        val failure = AtomicReference<Throwable?>()
        val start = Thread {
            try {
                ingress.startFromAuthenticatedControl(identity, STREAM_ID,
                    identity.generation, command, mapping, now)
            } catch (problem: Throwable) {
                failure.set(problem)
            }
        }
        start.start()
        assertTrue(startEntered.await(2, TimeUnit.SECONDS))
        ingress.flushFromAuthenticatedControl(identity, STREAM_ID, identity.generation,
            MainBufferedFlush(STREAM_ID, null,
                MainBufferedFlushPoint(1, 0, 100, 48_000)))
        releaseStart.countDown()
        start.join(2_000)
        assertFalse(start.isAlive)
        assertTrue(failure.get() is IllegalStateException)
        assertEquals(MainBufferedAacLcState.FAILED, ingress.snapshot().state)
        assertTrue(decoder.closes > 0)
    }

    private fun prepare(identity: MainBufferedIngressIdentity, decoder: FakeDecoder):
        MainBufferedAacLcIngress {
        val au = byteArrayOf(1, 2, 3)
        val contract = AacLcOneAuPerApapContract.fromExplicitLocalFixture(packet(1, 0, au), au)
        return MainBufferedAacLcIngress.prepare(identity, STREAM_ID, identity.generation,
            format(48_000), contract, AacLcDecodePortFactory { decoder })
    }

    private fun format(rate: Int, spf: Long = 1024) = MainBufferedV27AcceptedFormat(
        MainBufferedAacLcIngress.AAC_LC_CONTENT_TYPE, spf,
        if (rate == 44_100) MainBufferedAudioFormatBits.AAC_LC_44100_STEREO
        else MainBufferedAudioFormatBits.AAC_LC_48000_STEREO,
    )

    private fun cookie(id: Long, rate: Int) = MainBufferedMagicCookie(
        id, if (rate == 44_100) byteArrayOf(0x12, 0x10)
        else byteArrayOf(0x11, 0x90.toByte()),
    )

    private fun packet(
        sequence: Int,
        mediaTime: Long,
        raw: ByteArray = byteArrayOf(1, 2, 3),
        rate: Long = 48_000,
    ) = ApapPacket(ApapMediaHeader(mediaTime, rate, sequence), emptyList(), raw)

    private class Session(override val identity: MainBufferedIngressIdentity) :
        MainBufferedIngressSession {
        override fun snapshot(): MainBufferedIngressSnapshot = error("unused")
        override fun awaitTerminal(timeoutMillis: Long): Boolean = error("unused")
        override fun awaitClosed(timeoutMillis: Long): Boolean = error("unused")
        override fun close() = Unit
    }

    private class FakeDecoder(
        private val queuedPcmOnDecode: Boolean = true,
        initiallyReady: Boolean = true,
        private val startEntered: CountDownLatch? = null,
        private val releaseStart: CountDownLatch? = null,
        private val nativeStartResult: Boolean = false,
    ) : AacLcDecodePort {
        override var isReady = initiallyReady
        override val outputInstance: Any = Any()
        override var playbackEpoch = 0L
        override var firstQueuedMediaTimeValue: Long? = null
        val configs = mutableListOf<AacLcDecoderConfig>()
        val decoded = mutableListOf<AacLcAccessUnit>()
        var starts = 0
        var closes = 0

        override fun configure(config: AacLcDecoderConfig): Boolean {
            configs += config
            decoded.clear()
            firstQueuedMediaTimeValue = null
            playbackEpoch++
            isReady = true
            return true
        }

        override fun decode(accessUnit: AacLcAccessUnit): AacLcDecodeReceipt {
            assertEquals(configs.last().epoch, accessUnit.epoch)
            val copied = accessUnit.consumePayload()
            copied.fill(0)
            decoded += accessUnit
            if (queuedPcmOnDecode && firstQueuedMediaTimeValue == null) {
                firstQueuedMediaTimeValue = accessUnit.mediaTimeValue
            }
            return AacLcDecodeReceipt(true, if (queuedPcmOnDecode) 1024 else 0,
                firstQueuedMediaTimeValue)
        }

        override fun pausePlayback() { playbackEpoch++ }
        override fun startPlayback(anchor: MainBufferedPcm16VerifiedAnchor): Boolean {
            starts++
            assertTrue(anchor.outputInstance === outputInstance)
            assertEquals(playbackEpoch, anchor.outputEpoch)
            startEntered?.countDown()
            releaseStart?.let { check(it.await(2, TimeUnit.SECONDS)) }
            return nativeStartResult
        }
        override fun flush() {
            decoded.clear()
            firstQueuedMediaTimeValue = null
            playbackEpoch++
        }
        override fun close() { closes++; isReady = false }
    }

    private companion object {
        const val STREAM_ID = 17L
    }
}
