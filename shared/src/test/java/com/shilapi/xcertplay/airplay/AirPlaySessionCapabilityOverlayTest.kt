package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AirPlaySessionCapabilityOverlayTest {
    @Test
    fun twoIdleDiscoveryClientsCreateNoOverlayOrReservation() {
        val baseline = config()
        val (binding, generation) = resolvedBinding(baseline)
        val opened = AtomicInteger()
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            opened.incrementAndGet()
            AirPlaySessionCapabilityOverlay(snapshot.config)
        }
        connection(baseline, binding, generation, factory).use { first ->
            connection(baseline, binding, generation, factory).use { second ->
                val discovery = mapOf("altScreenURLs" to listOf("app://cluster"))
                assertNull(info(first.session, authenticated = false, discovery)["fileTransferInfo"])
                assertNull(info(second.session, authenticated = false, discovery)["fileTransferInfo"])
                assertEquals(0, opened.get())
                assertNull(first.session.connectionOverlay)
                assertNull(second.session.connectionOverlay)
            }
        }
        assertEquals(0, opened.get())
    }

    @Test
    fun firstAuthenticatedInfoAcquiresOnceAndRebasesSameDisplayControl() {
        val baseline = config()
        val (binding, generation) = resolvedBinding(baseline)
        val opened = AtomicInteger()
        val retired = AtomicInteger()
        val ready = readyFileTransferConfiguration()
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            assertSame(baseline, snapshot.config)
            opened.incrementAndGet()
            AirPlaySessionCapabilityOverlay(
                baseline.copy(ultra = ready),
                extraRcsFactory = factory(descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)),
                owner = Closeable { retired.incrementAndGet() },
            )
        }
        val media = object : AirPlayMediaHandler {
            override fun isRcsReady(
                session: AirPlaySession,
                clientTypes: Collection<RcsClientType>,
            ) = clientTypes.all { it == RcsClientTypes.CAR_PLAY_UPDATE_DATA }
        }
        connection(baseline, binding, generation, factory, media).use { connection ->
            val control = checkNotNull(privateField(connection.session, "displayControl"))
            info(
                connection.session,
                authenticated = false,
                requestInfo = mapOf("altScreenURLs" to listOf("app://pre-auth-cluster")),
            )
            assertEquals(0, opened.get())
            authenticate(connection.session)

            val first = info(connection.session, authenticated = true)
            val second = info(connection.session, authenticated = true)
            assertFalse("fileTransferInfo" in first)
            assertFalse("fileTransferInfo" in second)
            assertEquals(1, opened.get())
            assertSame(control, privateField(connection.session, "displayControl"))
            assertSame(ready, connection.session.resolvedConfig.ultra)
        }
        assertEquals(1, retired.get())
    }

    @Test
    fun setupFirstRebaseKeepsPreAuthDiscoveryUrls() {
        val baseline = config().copy(cluster = AirPlayDisplayConfig(480, 320))
        val (binding, generation) = resolvedBinding(baseline)
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            AirPlaySessionCapabilityOverlay(snapshot.config.copy(ultra = AirPlayUltraConfiguration()))
        }
        connection(baseline, binding, generation, factory).use { connection ->
            info(
                connection.session,
                authenticated = false,
                requestInfo = mapOf("altScreenURLs" to listOf("app://pre-auth-cluster")),
            )
            val control = checkNotNull(privateField(connection.session, "displayControl"))
            authenticate(connection.session)
            val setup = request(
                connection.session,
                method = "SETUP",
                path = "rtsp://localhost/carplay",
                body = BplistCodec.encode(mapOf(
                    "features" to listOf("altScreen"),
                    "timingPort" to 0,
                )),
                encrypted = true,
            )
            assertEquals(200, setup.status ?: 200)
            assertSame(control, privateField(connection.session, "displayControl"))
            assertEquals(
                listOf("app://pre-auth-cluster"),
                privateField(control, "altUrls"),
            )
            assertNull(privateField(connection.session, "infoOnlyReservationDeadlineNanos"))
        }
    }

    @Test
    fun infoOnlyReservationDeadlineIsAbsoluteUntilSetup() {
        val baseline = config()
        val (binding, generation) = resolvedBinding(baseline)
        val now = AtomicLong(1_000_000_000L)
        val retired = AtomicInteger()
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            AirPlaySessionCapabilityOverlay(
                snapshot.config.copy(ultra = readyFileTransferConfiguration()),
                extraRcsFactory = factory(descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)),
                owner = Closeable { retired.incrementAndGet() },
            )
        }
        val media = object : AirPlayMediaHandler {
            override fun isRcsReady(
                session: AirPlaySession,
                clientTypes: Collection<RcsClientType>,
            ) = true
        }
        connection(
            baseline, binding, generation, factory, media,
            clock = now::get,
            infoOnlyReservationLifetimeMillis = 1_000L,
        ).use { connection ->
            authenticate(connection.session)
            assertFalse("fileTransferInfo" in info(connection.session, authenticated = true))
            val firstDeadline = privateField(
                connection.session, "infoOnlyReservationDeadlineNanos",
            ) as Long
            assertEquals(now.get() + 1_000_000_000L, firstDeadline)
            assertEquals(30_000, (privateField(connection.session, "socket") as Socket).soTimeout)

            now.addAndGet(500_000_000L)
            assertFalse("fileTransferInfo" in info(connection.session, authenticated = true))
            assertEquals(
                firstDeadline,
                privateField(connection.session, "infoOnlyReservationDeadlineNanos"),
            )
            now.addAndGet(500_000_001L)
            assertThrows(SocketTimeoutException::class.java) {
                invokePrivate(connection.session, "checkInfoOnlyReservationDeadline")
            }
            assertEquals(0, retired.get())
        }
        assertEquals(1, retired.get())
    }

    @Test
    fun hiddenSidecarStillBoundsAReservedButTransportUnreadyLease() {
        val baseline = config()
        val (binding, generation) = resolvedBinding(baseline)
        val retired = AtomicInteger()
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            AirPlaySessionCapabilityOverlay(
                snapshot.config.copy(ultra = readyFileTransferConfiguration()),
                extraRcsFactory = factory(descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)),
                owner = Closeable { retired.incrementAndGet() },
            )
        }
        connection(baseline, binding, generation, factory).use { connection ->
            authenticate(connection.session)
            assertFalse("fileTransferInfo" in info(connection.session, authenticated = true))
            assertTrue(
                privateField(connection.session, "infoOnlyReservationDeadlineNanos") is Long,
            )
        }
        assertEquals(1, retired.get())
    }

    @Test
    fun validSetupCancelsInfoOnlyDeadlineWithoutClosingLongCarPlaySession() {
        val baseline = config()
        val (binding, generation) = resolvedBinding(baseline)
        val now = AtomicLong(1_000_000_000L)
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            AirPlaySessionCapabilityOverlay(
                snapshot.config.copy(ultra = readyFileTransferConfiguration()),
                extraRcsFactory = factory(descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)),
            )
        }
        val media = object : AirPlayMediaHandler {
            override fun isRcsReady(
                session: AirPlaySession,
                clientTypes: Collection<RcsClientType>,
            ) = true
        }
        connection(
            baseline, binding, generation, factory, media,
            clock = now::get,
            infoOnlyReservationLifetimeMillis = 1_000L,
        ).use { connection ->
            authenticate(connection.session)
            assertFalse("fileTransferInfo" in info(connection.session, authenticated = true))
            assertEquals(emptyList<String>(), setupFileTransfer(connection.session)["enabledFeatures"])
            assertNull(privateField(connection.session, "infoOnlyReservationDeadlineNanos"))
            assertEquals(0, (privateField(connection.session, "socket") as Socket).soTimeout)
            now.addAndGet(10_000_000_000L)
            invokePrivate(connection.session, "checkInfoOnlyReservationDeadline")
            assertFalse(connection.session.isClosed)
        }
    }

    @Test
    fun infoOnlySocketExpiryRetiresTheReservationOwner() {
        val baseline = config()
        val (binding, generation) = resolvedBinding(baseline)
        val retired = AtomicInteger()
        val ended = CountDownLatch(1)
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            AirPlaySessionCapabilityOverlay(
                snapshot.config.copy(ultra = readyFileTransferConfiguration()),
                extraRcsFactory = factory(descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)),
                owner = Closeable { retired.incrementAndGet() },
            )
        }
        val media = object : AirPlayMediaHandler {
            override fun isRcsReady(
                session: AirPlaySession,
                clientTypes: Collection<RcsClientType>,
            ) = true
        }
        connection(
            baseline, binding, generation, factory, media,
            infoOnlyReservationLifetimeMillis = 80L,
            infoOnlyReservationPollMillis = 20,
            onEnded = ended::countDown,
        ).use { connection ->
            authenticate(connection.session)
            assertFalse("fileTransferInfo" in info(connection.session, authenticated = true))
            connection.session.start()
            assertTrue("info-only control session did not retire", ended.await(5, TimeUnit.SECONDS))
            assertTrue(connection.session.isClosed)
            assertEquals(1, retired.get())
        }
        assertEquals(1, retired.get())
    }

    @Test
    fun reconnectCreatesFreshOverlayAndCannotReuseFirstSidecar() {
        val baseline = config()
        val (binding, generation) = resolvedBinding(baseline)
        val opened = AtomicInteger()
        val retired = AtomicInteger()
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            val number = opened.incrementAndGet()
            AirPlaySessionCapabilityOverlay(
                snapshot.config.copy(
                    ultra = if (number == 1) readyFileTransferConfiguration() else null,
                ),
                owner = Closeable { retired.incrementAndGet() },
            )
        }
        val media = object : AirPlayMediaHandler {
            override fun isRcsReady(
                session: AirPlaySession,
                clientTypes: Collection<RcsClientType>,
            ) = true
        }
        val firstOverlay = connection(baseline, binding, generation, factory, media).use { first ->
            authenticate(first.session)
            assertEquals(listOf("fileTransfer"), setupFileTransfer(first.session)["enabledFeatures"])
            assertTrue("fileTransferInfo" in info(first.session, authenticated = true))
            checkNotNull(first.session.connectionOverlay)
        }
        connection(baseline, binding, generation, factory, media).use { second ->
            authenticate(second.session)
            assertEquals(emptyList<String>(), setupFileTransfer(second.session)["enabledFeatures"])
            assertFalse("fileTransferInfo" in info(second.session, authenticated = true))
            assertNotSame(firstOverlay, second.session.connectionOverlay)
        }
        assertEquals(2, opened.get())
        assertEquals(2, retired.get())
    }

    @Test
    fun revokedGenerationRejectsAuthenticatedRequestWithoutOpeningOverlay() {
        val baseline = config()
        val (binding, generation) = resolvedBinding(baseline)
        val opened = AtomicInteger()
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            opened.incrementAndGet()
            AirPlaySessionCapabilityOverlay(snapshot.config)
        }
        connection(baseline, binding, generation, factory).use { connection ->
            authenticate(connection.session)
            assertTrue(binding.revoke(generation))
            assertEquals(409, request(
                connection.session,
                method = "POST",
                path = "/info",
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                encrypted = true,
            ).status)
            assertNull(connection.session.connectionOverlay)
        }
        assertEquals(0, opened.get())
    }

    @Test
    fun failedRebaseRetainsOwnerUntilSessionClose() {
        val baseline = config()
        val (binding, generation) = resolvedBinding(baseline)
        val retired = AtomicInteger()
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            AirPlaySessionCapabilityOverlay(
                snapshot.config.copy(deviceId = "02:00:00:00:00:03"),
                owner = Closeable { retired.incrementAndGet() },
            )
        }
        connection(baseline, binding, generation, factory).use { connection ->
            authenticate(connection.session)
            assertEquals(409, request(
                connection.session,
                method = "POST", path = "/info",
                body = BplistCodec.encode(emptyMap<String, Any?>()), encrypted = true,
            ).status)
            assertNull(connection.session.connectionOverlay)
            assertEquals(0, retired.get())
        }
        assertEquals(1, retired.get())
    }

    @Test
    fun closeCoversWhileAdmissionIsBlockedAndRetiresExactOwnerOnce() {
        val baseline = config()
        val (binding, generation) = resolvedBinding(baseline)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = AtomicInteger()
        val covered = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val responseStatus = AtomicReference<Int?>()
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            entered.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            AirPlaySessionCapabilityOverlay(
                snapshot.config,
                owner = Closeable { retired.incrementAndGet() },
            )
        }
        val media = object : AirPlayMediaHandler {
            override fun onSessionStopping(session: AirPlaySession) {
                covered.countDown()
            }
        }
        connection(baseline, binding, generation, factory, media).use { connection ->
            authenticate(connection.session)
            val requestWorker = Thread {
                runCatching {
                    responseStatus.set(request(
                        connection.session, "POST", "/info",
                        BplistCodec.encode(emptyMap<String, Any?>()), true,
                    ).status ?: 200)
                }
                    .exceptionOrNull()?.let(failure::set)
            }
            requestWorker.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val closeWorker = Thread { runCatching { connection.session.close() }
                .exceptionOrNull()?.let(failure::set) }
            closeWorker.start()
            assertTrue("STOPPING cover waited for overlay factory", covered.await(1, TimeUnit.SECONDS))
            assertEquals(0, retired.get())
            release.countDown()
            requestWorker.join(3_000)
            closeWorker.join(3_000)
            assertFalse(requestWorker.isAlive)
            assertFalse(closeWorker.isAlive)
            assertNull(failure.get())
            assertEquals(409, responseStatus.get())
        }
        assertEquals(1, retired.get())
    }

    @Test
    fun baseAndExtraFactoriesComposeForDistinctTypesAndRejectCollision() {
        val baseDescriptor = descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA)
        val updateDescriptor = descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)
        val combined = requireNotNull(composeSessionRcsHandlerFactories(
            factory(baseDescriptor), factory(updateDescriptor),
        ))
        assertSame(baseDescriptor, combined.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
        assertSame(updateDescriptor, combined.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA))
        assertNull(combined.descriptor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL))
        val duplicate = requireNotNull(composeSessionRcsHandlerFactories(
            factory(baseDescriptor), factory(baseDescriptor),
        ))
        assertTrue(runCatching {
            duplicate.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA)
        }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun info(
        session: AirPlaySession,
        authenticated: Boolean,
        requestInfo: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val response = request(
            session, "POST", "/info", BplistCodec.encode(requestInfo), authenticated,
        )
        assertEquals(200, response.status ?: 200)
        @Suppress("UNCHECKED_CAST")
        return BplistCodec.decode(response.body) as Map<String, Any?>
    }

    private fun setupFileTransfer(session: AirPlaySession): Map<String, Any?> {
        val response = request(
            session,
            method = "SETUP",
            path = "rtsp://localhost/carplay",
            body = BplistCodec.encode(mapOf(
                "features" to listOf("fileTransfer"),
                "timingPort" to 0,
            )),
            encrypted = true,
        )
        assertEquals(200, response.status ?: 200)
        @Suppress("UNCHECKED_CAST")
        return BplistCodec.decode(response.body) as Map<String, Any?>
    }

    private fun request(
        session: AirPlaySession,
        method: String,
        path: String,
        body: ByteArray,
        encrypted: Boolean,
    ): RtspMessage.Response {
        val request = RtspMessage.Request(method, path, "RTSP/1.0", emptyMap(), body)
        val handler = AirPlaySession::class.java.getDeclaredMethod(
            "handle", RtspMessage.Request::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val handled = try {
            handler.invoke(session, request, encrypted)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
        return privateField(handled, "response") as RtspMessage.Response
    }

    private fun authenticate(session: AirPlaySession) {
        PairVerify::class.java.getDeclaredField("verified").apply { isAccessible = true }
            .set(session.pairVerify, true)
        session.cipher = ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 })
    }

    private fun privateField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun invokePrivate(target: Any, name: String) {
        val method = target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }
        try {
            method.invoke(target)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private class Connection(val session: AirPlaySession, private val client: Socket) : Closeable {
        override fun close() {
            session.close()
            client.close()
        }
    }

    private fun connection(
        baseline: AirPlayConfig,
        binding: AirPlaySessionRuntimeBinding,
        generation: AirPlaySessionRuntimeBinding.Generation,
        factory: AirPlaySessionCapabilityOverlayFactory,
        media: AirPlayMediaHandler = object : AirPlayMediaHandler {},
        clock: () -> Long = System::nanoTime,
        infoOnlyReservationLifetimeMillis: Long = 180_000L,
        infoOnlyReservationPollMillis: Int = 30_000,
        onEnded: (() -> Unit)? = null,
    ): Connection {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
        val server = listener.accept()
        listener.close()
        val snapshot = binding.acquire(generation)!!
        val session = AirPlaySession(
            socket = server,
            config = baseline,
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) {
                    onEnded?.invoke()
                }
            },
            media = media,
            runtimeSnapshot = snapshot,
            monotonicNanos = clock,
            infoOnlyReservationLifetimeMillis = infoOnlyReservationLifetimeMillis,
            infoOnlyReservationPollMillis = infoOnlyReservationPollMillis,
            connectionOverlayAdmission = { candidate ->
                binding.withResolvedAdmission(generation) { exact ->
                    candidate.admitConnectionOverlay(exact, factory)
                } == true
            },
        )
        return Connection(session, client)
    }

    private fun resolvedBinding(config: AirPlayConfig): Pair<
        AirPlaySessionRuntimeBinding,
        AirPlaySessionRuntimeBinding.Generation,
    > = AirPlaySessionRuntimeBinding().let { binding ->
        val generation = binding.beginGeneration()
        binding.resolve(generation, config)!!
        binding to generation
    }

    private fun descriptor(type: RcsClientType): RcsDataStreamHandlerDescriptor =
        RcsDataStreamHandlerDescriptor.ready(type, "test.${type.name}") {
            object : RcsDataStreamHandler {}
        }

    private fun factory(descriptor: RcsDataStreamHandlerDescriptor) =
        RcsDataStreamHandlerFactory { type -> descriptor.takeIf { it.clientType == type } }

    private fun readyFileTransferConfiguration(): AirPlayUltraConfiguration {
        val registration = descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)
        return AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
            fileTransferInfo = AirPlayFileTransferInfo(),
            rcsFactory = factory(registration),
            updateDataConsumerReadiness = AirPlayUpdateDataConsumerReadinessDescriptor.capture(
                registration, AirPlayReadiness.READY,
            ),
        )
    }

    private fun config() = AirPlayConfig(
        deviceName = "overlay-test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "1.0",
        main = AirPlayDisplayConfig(800, 480),
    )
}
