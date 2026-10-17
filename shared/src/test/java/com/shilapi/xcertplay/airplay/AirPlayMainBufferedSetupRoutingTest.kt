package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedAudioFormatBits
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedIngressPacketConsumer
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27AcceptedFormat
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27PortMode
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27SetupSession
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27SetupState
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27SetupTransaction
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the latent type-103 AirPlay seam without enabling product feature negotiation. */
class AirPlayMainBufferedSetupRoutingTest {
    @Test
    fun unnegotiatedMainBufferedNeverCallsPreparerOrPublishesPort() {
        val media = RecordingMainBufferedMedia()
        Fixture(media).use { fixture ->
            val batch = prepare(fixture.session, request())
            assertTrue(batch.responses.isEmpty())
            assertTrue(batch.prepared.isEmpty())
            assertEquals(0, media.prepareCalls)
            assertTrue(media.transactions.isEmpty())
            assertFalse(AirPlayFeatureNegotiation.snapshot(fixture.config)
                .isReady(AirPlayFeature.MAIN_BUFFERED))
        }
    }

    @Test
    fun unboundMockTransactionCannotPublishAnM4BoundResponse() {
        val media = RecordingMainBufferedMedia()
        Fixture(media).use { fixture ->
            // Even when a test forcibly bypasses feature negotiation, an arbitrary handler's
            // detached transaction cannot stand in for an exact M4 child lease.
            forceNegotiatedForTest(fixture.session)
            val failure = assertThrows(InvocationTargetException::class.java) {
                prepare(fixture.session, request())
            }
            assertTrue(failure.cause is IllegalStateException)
            val transaction = media.transactions.single()
            assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
            assertTrue(fixture.session.activeStreams.isEmpty())
        }
    }

    @Test
    fun unboundMockFailureLeavesNoPreparedResponseToCommit() {
        val media = RecordingMainBufferedMedia()
        Fixture(media).use { fixture ->
            forceNegotiatedForTest(fixture.session)
            assertThrows(InvocationTargetException::class.java) {
                prepare(fixture.session, request())
            }
            val transaction = media.transactions.single()
            assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
            assertTrue(fixture.session.activeStreams.isEmpty())
            assertTrue(media.tornDown.isEmpty())
        }
    }

    @Test
    fun sessionCloseDoesNotRearmRejectedUnboundTransaction() {
        val media = RecordingMainBufferedMedia()
        Fixture(media).use { fixture ->
            forceNegotiatedForTest(fixture.session)
            assertThrows(InvocationTargetException::class.java) {
                prepare(fixture.session, request())
            }
            val transaction = media.transactions.single()
            fixture.session.close()
            assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
            assertTrue(fixture.session.activeStreams.isEmpty())
        }
    }

    @Test
    fun type103OwnerFailureRollsBackEarlierPreparedStreamsInSameBatch() {
        val media = RecordingMainBufferedMedia()
        Fixture(media).use { fixture ->
            forceNegotiatedForTest(fixture.session)
            val failure = assertThrows(InvocationTargetException::class.java) {
                prepare(
                    fixture.session,
                    listOf(mapOf("type" to 100L, "streamConnectionID" to 90L), request()),
                )
            }
            assertTrue(failure.cause is IllegalStateException)
            assertEquals(1, media.audioRollbacks)
            assertEquals(MainBufferedV27SetupState.CLOSED, media.transactions.single().state())
            assertTrue(fixture.session.activeStreams.isEmpty())
        }
    }

    private class RecordingMainBufferedMedia : AirPlayMediaHandler {
        var prepareCalls = 0
        var audioRollbacks = 0
        val transactions = ArrayList<MainBufferedV27SetupTransaction>()
        val tornDown = ArrayList<AirPlayStreamIdentity>()
        private val generation = AtomicLong(0)

        override fun prepareAudio(
            session: AirPlaySession,
            identity: AirPlayStreamIdentity,
            stream: Map<String, Any?>,
        ): AirPlayPreparedMediaStream = AirPlayPreparedMediaStream(
            identity = identity,
            response = mapOf("type" to identity.type, "streamConnectionID" to 90L),
            rollbackHook = { audioRollbacks++ },
        )

        override fun prepareMainBuffered(
            session: AirPlaySession,
            identity: AirPlayStreamIdentity,
            stream: Map<String, Any?>,
        ): AirPlayPreparedMainBufferedStream {
            prepareCalls++
            return MainBufferedV27SetupSession.create(
                generation = generation.incrementAndGet(),
                bindAddress = checkNotNull(session.localAddress),
                expectedPeer = checkNotNull(session.remoteAddress),
                allocatedStreamId = 9,
                acceptedFormat = MainBufferedV27AcceptedFormat(
                    contentType = CONTENT_TYPE,
                    samplesPerFrame = 1_024,
                    audioFormat = MainBufferedAudioFormatBits.PCM_48000_16_STEREO,
                ),
                audioBufferSize = 4_096,
                portMode = MainBufferedV27PortMode.DATA_PORT,
                packetConsumer = MainBufferedIngressPacketConsumer { _, _ -> },
            ).prepare(stream).also { transactions += it }
                .let(::AirPlayPreparedMainBufferedStream)
        }

        override fun onTeardown(session: AirPlaySession, identity: AirPlayStreamIdentity) {
            tornDown += identity
        }
    }

    private class Fixture(media: AirPlayMediaHandler) : Closeable {
        val config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        )
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
        private val server = listener.accept()
        val session = AirPlaySession(
            socket = server,
            config = config,
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {},
            media = media,
        )

        override fun close() {
            try {
                session.close()
            } finally {
                client.close()
                listener.close()
            }
        }
    }

    private data class PreparedBatch(
        val responses: List<Map<String, Any?>>,
        val prepared: List<AirPlayPreparedMediaStream>,
    )

    private fun prepare(session: AirPlaySession, stream: Map<String, Any?>): PreparedBatch {
        return prepare(session, listOf(stream))
    }

    private fun prepare(session: AirPlaySession, streams: List<Map<String, Any?>>): PreparedBatch {
        val method = session.javaClass.getDeclaredMethod("handleStreams", List::class.java)
            .apply { isAccessible = true }
        val batch = method.invoke(session, streams)
        @Suppress("UNCHECKED_CAST")
        val responses = batch.javaClass.getDeclaredField("responses").run {
            isAccessible = true
            get(batch) as List<Map<String, Any?>>
        }
        @Suppress("UNCHECKED_CAST")
        val prepared = batch.javaClass.getDeclaredField("prepared").run {
            isAccessible = true
            get(batch) as List<AirPlayPreparedMediaStream>
        }
        return PreparedBatch(responses, prepared)
    }

    private fun commit(session: AirPlaySession, batch: PreparedBatch) {
        session.javaClass.getDeclaredMethod("commitSetup", String::class.java, List::class.java)
            .apply { isAccessible = true }
            .invoke(session, "1", batch.prepared)
    }

    private fun teardown(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
    ): RtspMessage.Response {
        val selector = linkedMapOf<String, Any?>(
            "type" to identity.type,
            "streamConnectionID" to identity.streamConnectionId?.toLong(),
        )
        val request = RtspMessage.Request(
            method = "TEARDOWN",
            path = "/",
            protocol = "RTSP/1.0",
            headers = emptyMap(),
            body = BplistCodec.encode(mapOf("streams" to listOf(selector))),
        )
        return session.javaClass.getDeclaredMethod("handleTeardown", RtspMessage.Request::class.java)
            .apply { isAccessible = true }
            .invoke(session, request) as RtspMessage.Response
    }

    private fun forceNegotiatedForTest(session: AirPlaySession) {
        session.javaClass.getDeclaredField("negotiatedFeatures").apply { isAccessible = true }
            .set(session, setOf(AirPlayFeature.MAIN_BUFFERED))
    }

    private fun request(): Map<String, Any?> = linkedMapOf(
        "type" to 103L,
        "streamConnectionID" to 42L,
        "supportsDynamicStreamID" to true,
        "ct" to CONTENT_TYPE,
        "spf" to 1_024L,
        "audioFormat" to MainBufferedAudioFormatBits.PCM_48000_16_STEREO,
        "audioFormatIndex" to 15L,
        "isMedia" to true,
        "audioType" to "media",
        "packetFormatAPAP" to true,
        "shk" to ByteArray(32) { it.toByte() },
    )

    private companion object {
        const val CONTENT_TYPE = 0x6c70_636dL // 'lpcm'
    }
}
