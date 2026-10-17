package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataCodec
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataLimits
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataMessage
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataQuarantineEntryKind
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataQuarantineQuota
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataQuarantineRootOwner
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A pre-profile UpdateData transfer through an admitted AirPlay TCP connection and encrypted
 * type-130 RCS socket. This proves durable *untrusted* receipt, not theme authentication or Ultra
 * authorization. The loopback peer is a test phone with test-only Pair Verify state.
 */
class CarPlayUpdateDataOverlayIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val limits = CarPlayUpdateDataLimits(
        maxRcsBodyBytes = 1024,
        maxChunkBytes = 128,
        maxMetadataBytes = 16,
        maxArchiveBytes = 128,
        maxChunkCount = 4,
    )
    private val codec = CarPlayUpdateDataCodec(limits)

    @Test
    fun exactReservedHandlerReceivesEncryptedOpackAndSendsAckAfterQuarantineCommit() {
        val owner = owner(maxEntries = 1)
        val lease = requireNotNull(owner.tryReserveSession())
        val failures = AtomicInteger()
        val peerEof = CountDownLatch(1)
        val seed = "61"
        val opened = requireNotNull(CarPlayDataStreamFactory().open(
            context = CarPlayDataStreamConnectionContext(
                sharedSecret = SHARED_SECRET,
                bindAddress = loopback,
                expectedPeerAddress = loopback,
                traceLog = { if (it.contains("peer closed stream")) peerEof.countDown() },
                onTransportFailure = { failures.incrementAndGet() },
            ),
            clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            seed = seed,
            handlers = CarPlayDataStreamHandlers(null, lease, {}),
        ))
        try {
            assertTrue(opened.commit())
            RcsChannel.connect(
                InetSocketAddress(loopback, opened.port),
                dataStreamKey(seed, "DataStream-Input-Encryption-Key"),
                dataStreamKey(seed, "DataStream-Output-Encryption-Key"),
            ).use { phone ->
                val sessionId = UUID.randomUUID().toString()
                val data = byteArrayOf(1, 2, 3, 4)
                phone.send(codec.encode(CarPlayUpdateDataMessage.Header(sessionId, 9, 1)))
                phone.send(codec.encode(CarPlayUpdateDataMessage.Chunk(
                    sessionId, 0, data, sha256(data),
                )))
                assertEquals(
                    CarPlayUpdateDataMessage.Ack(0),
                    codec.decode(requireNotNull(phone.receiveNext(3_000))),
                )
                val record = owner.inventory().entries.single()
                assertEquals(
                    CarPlayUpdateDataQuarantineEntryKind.RECORD_UNTRUSTED_ACK_UNKNOWN,
                    record.kind,
                )
                assertArrayEquals(data, Files.readAllBytes(record.directory.toPath().resolve("archive.bin")))
            }
            assertTrue(peerEof.await(3, TimeUnit.SECONDS))
            assertTrue(owner.awaitDrained(3_000))
            assertEquals(0, failures.get())
        } finally {
            retireOpened(opened)
            lease.close()
            owner.close()
            assertTrue(owner.awaitDrained(3_000))
        }
    }

    @Test
    fun encryptedBadChecksumGetsRejectionAndLeavesNoCommittedRecord() {
        val owner = owner(maxEntries = 1)
        val lease = requireNotNull(owner.tryReserveSession())
        val seed = "62"
        val opened = requireNotNull(CarPlayDataStreamFactory().open(
            context = CarPlayDataStreamConnectionContext(
                sharedSecret = SHARED_SECRET,
                bindAddress = loopback,
                expectedPeerAddress = loopback,
            ),
            clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            seed = seed,
            handlers = CarPlayDataStreamHandlers(null, lease, {}),
        ))
        try {
            assertTrue(opened.commit())
            RcsChannel.connect(
                InetSocketAddress(loopback, opened.port),
                dataStreamKey(seed, "DataStream-Input-Encryption-Key"),
                dataStreamKey(seed, "DataStream-Output-Encryption-Key"),
            ).use { phone ->
                val sessionId = UUID.randomUUID().toString()
                phone.send(codec.encode(CarPlayUpdateDataMessage.Header(sessionId, 1, 1)))
                phone.send(codec.encode(CarPlayUpdateDataMessage.Chunk(
                    sessionId, 0, byteArrayOf(1, 2, 3), "0".repeat(64),
                )))
                assertEquals(
                    CarPlayUpdateDataMessage.Ack(1),
                    codec.decode(requireNotNull(phone.receiveNext(3_000))),
                )
            }
            assertTrue(owner.awaitDrained(3_000))
            assertTrue(owner.inventory().entries.isEmpty())
        } finally {
            retireOpened(opened)
            lease.close()
            owner.close()
            assertTrue(owner.awaitDrained(3_000))
        }
    }

    @Test
    fun encryptedPreProfileTransferCommitsUntrustedRecordAndNextConnectionGetsFreshCapability() {
        val owner = owner(maxEntries = 2)
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val base = baseConfig()
        assertNotNull(binding.resolve(generation, base))
        val overlays = CarPlayUpdateDataSessionOverlayFactory(owner)
        try {
            val first = requireNotNull(binding.withResolvedAdmission(generation) {
                TestControlConnection(it, binding, generation, overlays)
            })
            try {
                assertNull(first.session.resolvedConfig.ultra)
                assertNull(base.ultra) // No installed Ultra profile was supplied.
                assertEquals(0, owner.activeTransferCount) // An idle socket reserves nothing.
                // An authenticated info-first response intentionally freezes ordinary-only
                // scope; negotiate first so this transfer session can publish its sidecar.
                assertEquals(listOf("fileTransfer"), first.negotiate(1, listOf("fileTransfer")))
                assertTrue(first.session.resolvedConfig.ultra != null)
                assertEquals(1, owner.activeTransferCount)
                val infoAfter = first.info(2)
                assertEquals(emptyMap<String, Any?>(), infoAfter["fileTransferInfo"])
                assertArrayEquals(BplistCodec.encode(infoAfter), BplistCodec.encode(first.info(3)))

                val secondWhileReserved = requireNotNull(binding.withResolvedAdmission(generation) {
                    overlays.open(it)
                })
                try {
                    assertTrue(secondWhileReserved.config === base)
                    assertNull(secondWhileReserved.extraRcsFactory)
                } finally {
                    secondWhileReserved.close()
                }

                val dataPort = first.openUpdateDataStream(4, 71L)
                RcsChannel.connect(
                    remote = InetSocketAddress(loopback, dataPort),
                    readKey = dataStreamKey("71", "DataStream-Input-Encryption-Key"),
                    writeKey = dataStreamKey("71", "DataStream-Output-Encryption-Key"),
                ).use { phone ->
                    val sessionId = UUID.randomUUID().toString()
                    val archive = "untrusted-theme-bytes".toByteArray()
                    val sha256 = sha256(archive)
                    phone.send(codec.encode(CarPlayUpdateDataMessage.Header(
                        sessionId = sessionId,
                        version = 1L,
                        chunkCount = 2,
                        certificate = byteArrayOf(1, 2, 3),
                        signature = byteArrayOf(4, 5),
                    )))
                    phone.send(codec.encode(CarPlayUpdateDataMessage.Chunk(
                        sessionId, 0, archive.copyOfRange(0, 8),
                    )))
                    phone.send(codec.encode(CarPlayUpdateDataMessage.Chunk(
                        sessionId, 1, archive.copyOfRange(8, archive.size), sha256,
                    )))
                    assertEquals(
                        CarPlayUpdateDataMessage.Ack(0),
                        codec.decode(requireNotNull(phone.receiveNext(3_000))),
                    )
                    val records = owner.inventory().entries
                    assertEquals(1, records.size)
                    assertEquals(
                        CarPlayUpdateDataQuarantineEntryKind.RECORD_UNTRUSTED_ACK_UNKNOWN,
                        records.single().kind,
                    )
                    assertArrayEquals(
                        archive,
                        Files.readAllBytes(records.single().directory.toPath().resolve("archive.bin")),
                    )
                    assertTrue(records.single().fileNames.containsAll(
                        setOf("header.bin", "archive.bin", "receipt.bin"),
                    ))
                    assertTrue(owner.awaitDrained(3_000))
                }

                // A one-shot UpdateData peer closes after its terminal ACK. The AirPlay control
                // session must remain usable; a clean EOF is not a whole-transport failure.
                assertTrue(first.awaitRcsPeerEof(3_000))
                assertEquals(emptyMap<String, Any?>(), first.info(5)["fileTransferInfo"])
                assertFalse(first.session.isClosed)
                assertEquals(0, first.transportFailureCount.get())
            } finally {
                first.close()
            }
            assertTrue(owner.awaitDrained(3_000))

            val next = requireNotNull(binding.withResolvedAdmission(generation) {
                TestControlConnection(it, binding, generation, overlays)
            })
            try {
                assertTrue(next.session.resolvedConfig === base)
                assertEquals(0, owner.activeTransferCount)
                assertEquals(listOf("fileTransfer"), next.negotiate(1, listOf("fileTransfer")))
                assertTrue(next.session.resolvedConfig !== base)
                assertEquals(1, owner.activeTransferCount)
                assertEquals(emptyMap<String, Any?>(), next.info(2)["fileTransferInfo"])
            } finally {
                next.close()
            }
            assertTrue(owner.awaitDrained(3_000))
        } finally {
            binding.revoke(generation)
            owner.close()
            assertTrue(owner.awaitDrained(3_000))
        }
    }

    @Test
    fun retainedRecordFillsEntryQuotaAndLaterConnectionFallsBackWithoutAdvertisingTransfer() {
        val owner = owner(maxEntries = 1)
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val base = baseConfig()
        assertNotNull(binding.resolve(generation, base))
        val overlays = CarPlayUpdateDataSessionOverlayFactory(owner)
        try {
            val first = requireNotNull(binding.withResolvedAdmission(generation) {
                TestControlConnection(it, binding, generation, overlays)
            })
            try {
                assertEquals(listOf("fileTransfer"), first.negotiate(1, listOf("fileTransfer")))
                val dataPort = first.openUpdateDataStream(2, 81L)
                RcsChannel.connect(
                    InetSocketAddress(loopback, dataPort),
                    dataStreamKey("81", "DataStream-Input-Encryption-Key"),
                    dataStreamKey("81", "DataStream-Output-Encryption-Key"),
                ).use { phone ->
                    val sessionId = UUID.randomUUID().toString()
                    val data = byteArrayOf(10, 11, 12)
                    phone.send(codec.encode(CarPlayUpdateDataMessage.Header(sessionId, 1, 1)))
                    phone.send(codec.encode(CarPlayUpdateDataMessage.Chunk(
                        sessionId, 0, data, sha256(data),
                    )))
                    assertEquals(
                        CarPlayUpdateDataMessage.Ack(0),
                        codec.decode(requireNotNull(phone.receiveNext(3_000))),
                    )
                }
            } finally {
                first.close()
            }
            assertTrue(owner.awaitDrained(3_000))
            assertEquals(1, owner.inventory().entryCount)

            val later = requireNotNull(binding.withResolvedAdmission(generation) {
                TestControlConnection(it, binding, generation, overlays)
            })
            try {
                assertTrue(later.session.resolvedConfig === base)
                assertFalse(later.info(1).containsKey("fileTransferInfo"))
                assertTrue(later.negotiate(2, listOf("fileTransfer")).isEmpty())
                assertNull(later.session.connectionOverlay?.extraRcsFactory)
            } finally {
                later.close()
            }
        } finally {
            binding.revoke(generation)
            owner.close()
            assertTrue(owner.awaitDrained(3_000))
        }
    }

    private fun owner(maxEntries: Int) = CarPlayUpdateDataQuarantineRootOwner(
        root = temporaryFolder.newFolder("update-quarantine"),
        quota = CarPlayUpdateDataQuarantineQuota(maxBytes = 9_000, maxEntries = maxEntries),
        limits = limits,
        maxConcurrentTransfers = 1,
    )

    private fun baseConfig() = AirPlayConfig(
        deviceName = "pre-profile-update-test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(1280, 720),
        wirelessAudio = true,
    )

    private fun dataStreamKey(seed: String, label: String): ByteArray = AirPlayCrypto.hkdfSha512(
        SHARED_SECRET,
        "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
        label.toByteArray(Charsets.US_ASCII),
        32,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private inner class TestControlConnection(
        snapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot,
        binding: AirPlaySessionRuntimeBinding,
        generation: AirPlaySessionRuntimeBinding.Generation,
        overlays: CarPlayUpdateDataSessionOverlayFactory,
    ) : Closeable {
        private val listener = ServerSocket(0, 1, loopback)
        private val client = Socket(loopback, listener.localPort).apply { soTimeout = 3_000 }
        private val server = listener.accept()
        private val phoneCipher = ControlCipher(
            readKey = SERVER_WRITE_KEY,
            writeKey = SERVER_READ_KEY,
        )
        val transportFailureCount = AtomicInteger()
        private val media = UpdateDataTestMediaHandler(transportFailureCount)
        val session: AirPlaySession = try {
            AirPlaySession(
                socket = server,
                config = snapshot.config,
                identity = AirPlayIdentity.generate(),
                pairings = PairingStore(),
                mfi = null,
                listener = object : AirPlaySessionListener {},
                media = media,
                sessionTransport = AirPlaySessionTransport.WIRELESS_LOCAL_NETWORK,
                runtimeSnapshot = snapshot,
                connectionOverlayAdmission = { control ->
                    binding.withResolvedAdmission(generation) { admitted ->
                        control.admitConnectionOverlay(admitted, overlays)
                    } == true
                },
            ).also { admitted ->
                setPrivate(admitted.pairVerify, "sharedSecret", SHARED_SECRET.copyOf())
                setPrivate(admitted.pairVerify, "verified", true)
                setPrivate(admitted.pairVerify, "keys", PairVerify.ControlKeys(
                    SERVER_READ_KEY, SERVER_WRITE_KEY,
                ))
                admitted.cipher = ControlCipher(SERVER_READ_KEY, SERVER_WRITE_KEY)
                admitted.start()
            }
        } catch (failure: Throwable) {
            client.close()
            server.close()
            listener.close()
            throw failure
        }

        init { listener.close() }

        fun info(cseq: Int): Map<String, Any?> = bodyMap(request(
            cseq, "POST", "/info", BplistCodec.encode(emptyMap<String, Any?>()),
        ))

        fun negotiate(cseq: Int, features: List<String>): List<String> {
            val body = BplistCodec.encode(mapOf(
                "name" to "test-phone",
                "deviceID" to "02:00:00:00:00:03",
                "features" to features,
                "timingPort" to 0,
            ))
            @Suppress("UNCHECKED_CAST")
            return bodyMap(request(cseq, "SETUP", "rtsp://localhost/carplay", body))
                .get("enabledFeatures") as? List<String> ?: emptyList()
        }

        fun openUpdateDataStream(cseq: Int, connectionId: Long): Int {
            val body = BplistCodec.encode(mapOf("streams" to listOf(mapOf(
                "type" to 130,
                "streamConnectionID" to connectionId,
                "clientTypeUUID" to RcsClientTypes.CAR_PLAY_UPDATE_DATA.uuid.toString().uppercase(),
                "seed" to connectionId,
            ))))
            val response = bodyMap(request(cseq, "SETUP", "rtsp://localhost/carplay", body))
            val streams = response["streams"] as List<*>
            assertEquals(1, streams.size)
            val stream = streams.single() as Map<*, *>
            assertEquals(130L, (stream["type"] as Number).toLong())
            return (stream["dataPort"] as Number).toInt()
        }

        fun awaitRcsPeerEof(timeoutMillis: Long): Boolean = media.awaitPeerEof(timeoutMillis)

        private fun request(cseq: Int, method: String, path: String, body: ByteArray): RtspMessage.Request {
            val plaintext = (
                "$method $path RTSP/1.0\r\n" +
                    "CSeq: $cseq\r\n" +
                    "Content-Type: application/x-apple-binary-plist\r\n" +
                    "Content-Length: ${body.size}\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII) + body
            client.getOutputStream().apply {
                write(phoneCipher.encrypt(plaintext))
                flush()
            }
            val header = readExactly(client.getInputStream(), 2)
            val length = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8)
            val frame = header + readExactly(client.getInputStream(), length + 16)
            val response = RtspMessage.parseMessages(phoneCipher.decrypt(frame).data).messages.single()
            assertEquals("200", response.path)
            return response
        }

        private fun bodyMap(response: RtspMessage.Request): Map<String, Any?> {
            val decoded = BplistCodec.decode(response.body) as Map<*, *>
            return decoded.entries.associate { it.key.toString() to it.value }
        }

        override fun close() {
            session.close()
            client.close()
            server.close()
            listener.close()
        }
    }

    private class UpdateDataTestMediaHandler(
        private val transportFailures: AtomicInteger,
    ) : AirPlayMediaHandler {
        private val active = ConcurrentHashMap<AirPlayStreamIdentity, OpenedCarPlayDataStream>()
        private val eofObserved = java.util.concurrent.CountDownLatch(1)

        override fun isRcsReady(
            session: AirPlaySession,
            clientTypes: Collection<RcsClientType>,
        ): Boolean = clientTypes.all { clientType ->
            factory(session)?.descriptor(clientType)?.takeIf { it.clientType == clientType }
                ?.probe()?.isReady == true
        }

        override fun prepareDataStream(
            session: AirPlaySession,
            identity: AirPlayStreamIdentity,
            clientType: RcsClientType,
            stream: Map<String, Any?>,
        ): AirPlayPreparedMediaStream? {
            val seed = (stream["seed"] as? Number)?.toLong()?.toString() ?: return null
            val shared = session.sharedSecret ?: return null
            val local = session.localAddress ?: return null
            val remote = session.remoteAddress ?: return null
            val opened = CarPlayDataStreamFactory().open(
                context = CarPlayDataStreamConnectionContext(
                    sharedSecret = shared,
                    bindAddress = local,
                    expectedPeerAddress = remote,
                    traceLog = { if (it.contains("peer closed stream")) eofObserved.countDown() },
                    onTransportFailure = {
                        transportFailures.incrementAndGet()
                        runCatching { session.close() }
                    },
                ),
                clientType = clientType,
                seed = seed,
                handlers = CarPlayDataStreamHandlers(null, factory(session), {}),
            ) ?: return null
            active[identity] = opened
            return AirPlayPreparedMediaStream(
                identity = identity,
                response = mapOf("type" to 130, "dataPort" to opened.port),
                commitHook = opened::commit,
                rollbackHook = {
                    active.remove(identity)
                    retireOpened(opened)
                },
            )
        }

        override fun onTeardown(session: AirPlaySession, identity: AirPlayStreamIdentity) {
            active.remove(identity)?.let(::retireOpened)
        }

        override fun onSessionClosed(session: AirPlaySession) {
            active.values.toList().forEach(::retireOpened)
            active.clear()
        }

        fun awaitPeerEof(timeoutMillis: Long): Boolean =
            eofObserved.await(timeoutMillis, TimeUnit.MILLISECONDS)

        private fun factory(session: AirPlaySession): RcsDataStreamHandlerFactory? =
            composeSessionRcsHandlerFactories(null, session.connectionOverlay?.extraRcsFactory)
    }

    private fun setPrivate(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            check(count >= 0) { "stream ended after $offset/$size bytes" }
            offset += count
        }
        return bytes
    }

    private companion object {
        /** Peer EOF can be notifying its handler while session teardown closes the same stream. */
        fun retireOpened(opened: OpenedCarPlayDataStream) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (true) {
                try {
                    opened.rollback()
                    return
                } catch (busy: IllegalStateException) {
                    if (busy.message != "RCS handler close is already in flight" ||
                        System.nanoTime() >= deadline
                    ) throw busy
                    Thread.sleep(5)
                }
            }
        }

        val SHARED_SECRET = ByteArray(32) { 0x73 }
        val SERVER_READ_KEY = ByteArray(32) { 0x31 }
        val SERVER_WRITE_KEY = ByteArray(32) { 0x52 }
    }
}
