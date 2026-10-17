package com.shilapi.xcertplay.airplay

import android.view.Surface
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.video.MatchPointAndroidSurfaceRoute
import com.shilapi.xcertplay.airplay.video.MatchPointHttpTransport
import com.shilapi.xcertplay.airplay.video.MatchPointItemContentAuthority
import com.shilapi.xcertplay.airplay.video.MatchPointItemReference
import com.shilapi.xcertplay.airplay.video.MatchPointParkPlaybackAuthority
import com.shilapi.xcertplay.airplay.video.MatchPointPeerUrlPolicy
import com.shilapi.xcertplay.airplay.video.MatchPointPicAuthorizer
import com.shilapi.xcertplay.airplay.video.MatchPointPlaybackPort
import com.shilapi.xcertplay.airplay.video.MatchPointProductAuthorityBundle
import com.shilapi.xcertplay.airplay.video.MatchPointProductAuthorityProvider
import com.shilapi.xcertplay.airplay.video.MatchPointProductPlayerPortFactory
import com.shilapi.xcertplay.airplay.video.MatchPointProductSessionFactory
import com.shilapi.xcertplay.airplay.video.MatchPointProductSessionOwner
import com.shilapi.xcertplay.airplay.video.MatchPointQueueItem
import com.shilapi.xcertplay.airplay.video.MatchPointRuntime
import com.shilapi.xcertplay.airplay.video.MatchPointSessionIdentity
import com.shilapi.xcertplay.airplay.video.MatchPointSessionNetworkSocketAuthority
import com.shilapi.xcertplay.airplay.video.MatchPointVerifiedPairTlsRoute
import com.shilapi.xcertplay.airplay.video.MatchPointAuthorizeItemResponse
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MatchPointProductSessionFactoryTest {
    private val peer = InetAddress.getByName("127.0.0.1")

    @Test
    fun absentAndPartialGrantsDenyWithoutClaimingPairKeyOrPlayer() {
        val fixture = fixture()
        authenticate(fixture)
        val playerCalls = AtomicInteger()
        val factory = factory(
            MatchPointProductAuthorityProvider { _, identity ->
                MatchPointProductAuthorityBundle(picIssuer = pic(identity))
            },
            playerCalls,
        )
        val absent = factory(MatchPointProductAuthorityProvider { _, _ -> null }, playerCalls)
            .open(fixture.session)
        val partial = factory.open(fixture.session)
        try {
            assertFalse(descriptorReady(absent))
            assertFalse(descriptorReady(partial))
            assertEquals(0, playerCalls.get())
            // Neither incomplete probe consumed the exact Pair Verify generation.
            assertTrue(fixture.session.claimPairTlsPskLease() != null)
        } finally {
            absent.close()
            partial.close()
            fixture.session.close()
        }
    }

    @Test
    fun completeFakeAuthoritiesStillWaitForM4ResponseFlush() {
        val fixture = fixture()
        val output = FakeOutput()
        val factory = factory(fullBundle(output), AtomicInteger())
        val beforeM4 = factory.open(fixture.session)
        assertFalse(descriptorReady(beforeM4))

        finishPairVerify(fixture)
        val beforeFlush = factory.open(fixture.session)
        assertFalse(descriptorReady(beforeFlush))
        assertNull(fixture.session.claimPairTlsPskLease())

        markControlAuthenticated(fixture.session)
        val afterFlush = factory.open(fixture.session)
        try {
            assertTrue(descriptorReady(afterFlush))
            // A probe may report complete fake grants, but it cannot claim key material.
            val unclaimed = requireNotNull(fixture.session.claimPairTlsPskLease())
            unclaimed.close()
        } finally {
            beforeM4.close()
            beforeFlush.close()
            afterFlush.close()
            fixture.session.close()
        }
    }

    @Test
    fun ownerCreatesRealPskRuntimeAndRetiresWithExactSession() {
        val output = FakeOutput()
        val playerCalls = AtomicInteger()
        val playerCloses = AtomicInteger()
        val identities = mutableListOf<MatchPointSessionIdentity>()
        val complete = fullBundle(output)
        val provider = MatchPointProductAuthorityProvider { session, identity ->
            identities += identity
            complete.forSession(session, identity)
        }
        val factory = MatchPointProductSessionFactory(provider,
            MatchPointProductPlayerPortFactory { _, _, transport ->
                assertTrue(transport.isCurrent())
                playerCalls.incrementAndGet()
                object : MatchPointPlaybackPort {
                    override fun dispatch(action: com.shilapi.xcertplay.airplay.video.MatchPointPlaybackAction) = Unit
                    override fun close() { playerCloses.incrementAndGet() }
                }
            })
        val engine = CarPlayMediaEngine(object : MediaSink {}, matchPointSessionFactory = factory)
        val first = fixture(engine)
        authenticate(first)
        assertFalse(engine.isRcsReady(first.session, listOf(RcsClientTypes.CAR_PLAY_MATCH_POINT)))
        assertFalse(engine.isRcsReady(first.session, listOf(RcsClientTypes.CAR_PLAY_UPDATE_DATA)))
        assertNull(engine.prepareDataStream(first.session,
            AirPlayStreamIdentity(130, "1", RcsClientTypes.CAR_PLAY_MATCH_POINT.uuid.toString()),
            RcsClientTypes.CAR_PLAY_MATCH_POINT, mapOf("seed" to 1L)))

        val second = fixture(engine)
        authenticate(second)
        assertFalse(engine.isRcsReady(second.session, listOf(RcsClientTypes.CAR_PLAY_MATCH_POINT)))
        assertEquals(0, identities.size) // Disabled feature never even asks for grants.
        first.session.close()
        assertFalse(engine.isRcsReady(first.session, listOf(RcsClientTypes.CAR_PLAY_MATCH_POINT)))
        assertFalse(engine.isRcsReady(second.session, listOf(RcsClientTypes.CAR_PLAY_MATCH_POINT)))
        assertEquals(0, output.covers.get())
        second.session.close()
        engine.close()
        assertEquals(0, playerCalls.get()) // No RCS stream was actually negotiated.
        assertEquals(0, playerCloses.get())
    }

    @Test
    fun completeFakeBundleBuildsRealRuntimeAndClosesItsPlayerAndTransport() {
        val fixture = fixture()
        authenticate(fixture)
        val output = FakeOutput()
        val playerCloses = AtomicInteger()
        val owner = MatchPointProductSessionFactory(
            fullBundle(output),
            MatchPointProductPlayerPortFactory { _, _, transport ->
                assertTrue(transport.isCurrent())
                object : MatchPointPlaybackPort {
                    override fun dispatch(
                        action: com.shilapi.xcertplay.airplay.video.MatchPointPlaybackAction,
                    ) = Unit
                    override fun close() { playerCloses.incrementAndGet() }
                }
            },
            featureEligible = { true }, // Test-only future feature admission.
        ).open(fixture.session)
        val stream = RcsDataStream.open(
            RcsClientTypes.CAR_PLAY_MATCH_POINT,
            ByteArray(32) { 1 }, ByteArray(32) { 2 }, peer, peer,
        )
        try {
            val descriptor = requireNotNull(
                owner.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_MATCH_POINT))
            assertTrue(descriptor.probe().isReady)
            assertTrue(descriptor.create(stream) is MatchPointRuntime)
            assertFalse(descriptor.probe().isReady)
            owner.close()
            assertFalse(descriptor.probe().isReady)
            assertEquals(1, playerCloses.get())
            assertTrue(output.covers.get() > 0)
        } finally {
            owner.close()
            stream.close()
            fixture.session.close()
        }
    }

    @Test
    fun failedOwnerCloseStaysInEngineUntilExactSessionRetrySucceeds() {
        val output = FakeOutput().apply { failuresRemaining.set(1) }
        val factory = MatchPointProductSessionFactory(
            fullBundle(output),
            MatchPointProductPlayerPortFactory { _, _, _ -> object : MatchPointPlaybackPort {
                override fun dispatch(
                    action: com.shilapi.xcertplay.airplay.video.MatchPointPlaybackAction,
                ) = Unit
            } },
            featureEligible = { true }, // Exercise future path without enabling product feature.
        )
        val engine = CarPlayMediaEngine(object : MediaSink {}, matchPointSessionFactory = factory)
        val fixture = fixture(engine)
        authenticate(fixture)
        val session = fixture.session
        // An unrelated RCS probe initializes the engine's exact-session owner in this test.
        assertFalse(engine.isRcsReady(session, listOf(RcsClientTypes.CAR_PLAY_UPDATE_DATA)))
        val runtimeMap = engine.javaClass.getDeclaredField("sessionDataRuntimes").run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(engine) as java.util.concurrent.ConcurrentHashMap<AirPlaySession, Any>
        }
        val holder = requireNotNull(runtimeMap[session])
        val owner = holder.javaClass.getDeclaredField("matchPoint").run {
            isAccessible = true
            get(holder) as MatchPointProductSessionOwner
        }
        val stream = RcsDataStream.open(RcsClientTypes.CAR_PLAY_MATCH_POINT,
            ByteArray(32) { 1 }, ByteArray(32) { 2 }, peer, peer)
        try {
            val descriptor = requireNotNull(
                owner.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_MATCH_POINT))
            assertTrue(descriptor.probe().isReady)
            descriptor.create(stream)
            assertThrows(IOException::class.java) { engine.onSessionClosed(session) }
            assertTrue(runtimeMap[session] === holder)
            engine.onSessionClosed(session)
            assertNull(runtimeMap[session])
            assertEquals(2, output.covers.get())
        } finally {
            stream.close()
            session.close()
            engine.close()
        }
    }

    @Test
    fun lazySessionConstructionFencesEngineCloseUntilOwnerIsPublishedAndRetired() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = FakeOutput()
        val complete = fullBundle(output)
        val provider = MatchPointProductAuthorityProvider { session, identity ->
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            complete.forSession(session, identity)
        }
        val factory = MatchPointProductSessionFactory(provider,
            MatchPointProductPlayerPortFactory { _, _, _ -> error("no player") },
            featureEligible = { true })
        val engine = CarPlayMediaEngine(object : MediaSink {}, matchPointSessionFactory = factory)
        val fixture = fixture(engine)
        authenticate(fixture)
        val result = AtomicReference<Throwable?>()
        val probe = Thread({
            try { engine.isRcsReady(fixture.session, listOf(RcsClientTypes.CAR_PLAY_UPDATE_DATA)) }
            catch (failure: Throwable) { result.set(failure) }
        }, "matchpoint-lazy-session-race")
        try {
            probe.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { engine.close() }
        } finally {
            release.countDown()
            probe.join(3_000)
        }
        assertFalse(probe.isAlive)
        assertNull(result.get())
        engine.close()
        val runtimeMap = engine.javaClass.getDeclaredField("sessionDataRuntimes").run {
            isAccessible = true
            get(engine) as java.util.concurrent.ConcurrentHashMap<*, *>
        }
        assertTrue(runtimeMap.isEmpty())
        assertEquals(0, output.covers.get())
        fixture.session.close()
    }

    @Test
    fun reentrantFactoryCloseRetainsLatePortWhenItsFirstRetirementFails() {
        val output = FakeOutput()
        val mayRetirePort = AtomicBoolean(false)
        val portCloseCalls = AtomicInteger()
        val nestedClose = AtomicReference<Throwable?>()
        val ended = CountDownLatch(1)
        lateinit var session: AirPlaySession
        val factory = MatchPointProductSessionFactory(
            fullBundle(output),
            MatchPointProductPlayerPortFactory { _, _, _ ->
                nestedClose.set(runCatching { session.close() }.exceptionOrNull())
                object : MatchPointPlaybackPort {
                    override fun dispatch(
                        action: com.shilapi.xcertplay.airplay.video.MatchPointPlaybackAction,
                    ) = Unit
                    override fun close() {
                        portCloseCalls.incrementAndGet()
                        if (!mayRetirePort.get()) throw IOException("player release pending")
                    }
                }
            },
            featureEligible = { true },
        )
        val engine = CarPlayMediaEngine(object : MediaSink {}, matchPointSessionFactory = factory)
        val fixture = fixture(engine) { ended.countDown() }
        session = fixture.session
        authenticate(fixture)
        engine.isRcsReady(session, listOf(RcsClientTypes.CAR_PLAY_UPDATE_DATA))
        val (runtimeMap, owner) = ownerFromEngine(engine, session)
        val stream = matchPointStream()
        try {
            val descriptor = requireNotNull(owner.handlerFactory.descriptor(
                RcsClientTypes.CAR_PLAY_MATCH_POINT))
            assertThrows(IOException::class.java) { descriptor.create(stream) }
            assertTrue(nestedClose.get() is IOException)
            assertTrue(output.covers.get() > 0)
            assertTrue(runtimeMap.containsKey(session))
            assertFalse(ended.await(50, TimeUnit.MILLISECONDS))
            assertTrue(portCloseCalls.get() > 0)
            mayRetirePort.set(true)
            awaitCondition { runtimeMap[session] == null && ended.count == 0L }
            assertTrue(portCloseCalls.get() >= 2)
        } finally {
            mayRetirePort.set(true)
            stream.close()
            runCatching { session.close() }
            runCatching { engine.close() }
        }
    }

    @Test
    fun stoppingCoversWhilePlayerFactoryIsBlockedOutsideOwnerLock() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val output = FakeOutput()
        val factory = MatchPointProductSessionFactory(
            fullBundle(output),
            MatchPointProductPlayerPortFactory { _, _, _ ->
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                object : MatchPointPlaybackPort {
                    override fun dispatch(
                        action: com.shilapi.xcertplay.airplay.video.MatchPointPlaybackAction,
                    ) = Unit
                }
            },
            featureEligible = { true },
        )
        val engine = CarPlayMediaEngine(object : MediaSink {}, matchPointSessionFactory = factory)
        val fixture = fixture(engine) { ended.countDown() }
        authenticate(fixture)
        val session = fixture.session
        engine.isRcsReady(session, listOf(RcsClientTypes.CAR_PLAY_UPDATE_DATA))
        val (runtimeMap, owner) = ownerFromEngine(engine, session)
        val stream = matchPointStream()
        val creationFailure = AtomicReference<Throwable?>()
        val creator = Thread({
            try { owner.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_MATCH_POINT)!!
                .create(stream) }
            catch (failure: Throwable) { creationFailure.set(failure) }
        }, "matchpoint-blocked-player-create")
        try {
            creator.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { session.close() }
            assertTrue("cover must not wait for Media3 construction", output.covers.get() > 0)
            assertTrue(runtimeMap.containsKey(session))
            assertFalse(ended.await(50, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            creator.join(3_000)
        }
        assertFalse(creator.isAlive)
        assertTrue(creationFailure.get() is IOException)
        awaitCondition { runtimeMap[session] == null && ended.count == 0L }
        stream.close()
        engine.close()
    }

    @Test
    fun stoppingCoverIsNotBlockedBySlowExternalReadinessProbe() {
        val probeEntered = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val blockNextProbe = AtomicBoolean(false)
        val output = FakeOutput {
            if (blockNextProbe.compareAndSet(true, false)) {
                probeEntered.countDown()
                check(releaseProbe.await(3, TimeUnit.SECONDS))
            }
            true
        }
        val factory = MatchPointProductSessionFactory(
            fullBundle(output),
            MatchPointProductPlayerPortFactory { _, _, _ -> object : MatchPointPlaybackPort {
                override fun dispatch(
                    action: com.shilapi.xcertplay.airplay.video.MatchPointPlaybackAction,
                ) = Unit
            } },
            featureEligible = { true },
        )
        val engine = CarPlayMediaEngine(object : MediaSink {}, matchPointSessionFactory = factory)
        val fixture = fixture(engine)
        authenticate(fixture)
        val session = fixture.session
        engine.isRcsReady(session, listOf(RcsClientTypes.CAR_PLAY_UPDATE_DATA))
        val (_, owner) = ownerFromEngine(engine, session)
        blockNextProbe.set(true)
        val stream = matchPointStream()
        val probe = Thread({
            owner.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_MATCH_POINT)!!.probe()
        }, "matchpoint-slow-readiness-probe")
        try {
            probe.start()
            assertTrue(probeEntered.await(3, TimeUnit.SECONDS))
            // The second liveness call is not blocked, so construction can start while the
            // first descriptor probe is still waiting in an external callback.
            owner.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_MATCH_POINT)!!.create(stream)
            session.close()
            assertTrue("physical cover waited behind probe", output.covers.get() > 0)
        } finally {
            releaseProbe.countDown()
            probe.join(3_000)
            stream.close()
            runCatching { session.close() }
            engine.close()
        }
        assertFalse(probe.isAlive)
    }

    @Test
    fun sessionCloseWaitsForBlockedAuthorityProviderBeforePublishingEnd() {
        val providerEntered = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val complete = fullBundle(FakeOutput())
        val factory = MatchPointProductSessionFactory(
            MatchPointProductAuthorityProvider { session, identity ->
                providerEntered.countDown()
                check(releaseProvider.await(3, TimeUnit.SECONDS))
                complete.forSession(session, identity)
            },
            MatchPointProductPlayerPortFactory { _, _, _ -> error("no player") },
            featureEligible = { true },
        )
        val engine = CarPlayMediaEngine(object : MediaSink {}, matchPointSessionFactory = factory)
        val fixture = fixture(engine) { ended.countDown() }
        authenticate(fixture)
        val session = fixture.session
        val probe = Thread({
            engine.isRcsReady(session, listOf(RcsClientTypes.CAR_PLAY_UPDATE_DATA))
        }, "matchpoint-blocked-authority-provider")
        try {
            probe.start()
            assertTrue(providerEntered.await(3, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { session.close() }
            assertFalse(ended.await(50, TimeUnit.MILLISECONDS))
        } finally {
            releaseProvider.countDown()
            probe.join(3_000)
        }
        assertFalse(probe.isAlive)
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        engine.close()
    }

    @Test
    fun reentrantStopCoverCannotRetireSessionBeforeOuterCoverReturns() {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val nestedFailure = AtomicReference<Throwable?>()
        lateinit var session: AirPlaySession
        val media = object : AirPlayMediaHandler {
            override fun onSessionStopping(session: AirPlaySession) {
                events += "cover-enter"
                nestedFailure.set(runCatching { session.close() }.exceptionOrNull())
                events += "cover-exit"
            }
            override fun onSessionClosed(session: AirPlaySession) { events += "media-closed" }
        }
        val fixture = fixture(media)
        session = fixture.session
        session.close()
        assertTrue(nestedFailure.get() is IOException)
        assertTrue(events.indexOf("cover-exit") < events.indexOf("media-closed"))
    }

    @Test
    fun fatalCoverFailureSchedulesExactSessionRetryBeforeAnyTlsCleanup() {
        val mayCover = AtomicBoolean(false)
        val tlsCloses = AtomicInteger()
        val ended = CountDownLatch(1)
        val media = object : AirPlayMediaHandler {
            override fun onSessionStopping(session: AirPlaySession) {
                if (!mayCover.get()) throw AssertionError("physical cover failed")
            }
        }
        val fixture = fixture(media) { ended.countDown() }
        authenticate(fixture)
        val session = fixture.session
        val lease = requireNotNull(session.claimPairTlsPskLease())
        requireNotNull(lease.openOwnedTransportFor(session) { _ -> Closeable {
            tlsCloses.incrementAndGet()
        } })
        assertThrows(AssertionError::class.java) { session.close() }
        assertEquals(0, tlsCloses.get())
        assertFalse(ended.await(50, TimeUnit.MILLISECONDS))
        mayCover.set(true)
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        assertEquals(1, tlsCloses.get())
    }

    @Test
    fun failedConstructionJournalRetainsEveryOwnerUntilCloseRetrySucceeds() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val ended = CountDownLatch(1)
        val fixture = fixture(engine) { ended.countDown() }
        val session = fixture.session
        val mayRetire = AtomicBoolean(false)
        val closeCalls = AtomicInteger()
        val journalType = engine.javaClass.declaredClasses.single {
            it.simpleName == "PendingSessionRuntimeConstructionRetirement"
        }
        val journal = journalType.getDeclaredConstructor().run {
            isAccessible = true
            newInstance()
        }
        val add = journalType.getDeclaredMethod("add", Closeable::class.java).apply {
            isAccessible = true
        }
        repeat(2) {
            add.invoke(journal, Closeable {
                closeCalls.incrementAndGet()
                if (!mayRetire.get()) throw IOException("construction cleanup pending")
            })
        }
        val pending = engine.javaClass.getDeclaredField("failedSessionRuntimeConstructions").run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(engine) as java.util.concurrent.ConcurrentHashMap<AirPlaySession, Any>
        }
        pending[session] = journal
        assertThrows(IOException::class.java) { session.close() }
        assertTrue(pending.containsKey(session))
        assertFalse(ended.await(50, TimeUnit.MILLISECONDS))
        mayRetire.set(true)
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        assertNull(pending[session])
        assertTrue(closeCalls.get() >= 4)
        engine.close()
    }

    private fun descriptorReady(owner: MatchPointProductSessionOwner) =
        owner.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_MATCH_POINT)?.probe()?.isReady == true

    private fun matchPointStream() = RcsDataStream.open(
        RcsClientTypes.CAR_PLAY_MATCH_POINT,
        ByteArray(32) { 1 }, ByteArray(32) { 2 }, peer, peer,
    )

    private fun ownerFromEngine(
        engine: CarPlayMediaEngine,
        session: AirPlaySession,
    ): Pair<java.util.concurrent.ConcurrentHashMap<AirPlaySession, Any>, MatchPointProductSessionOwner> {
        val runtimeMap = engine.javaClass.getDeclaredField("sessionDataRuntimes").run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            get(engine) as java.util.concurrent.ConcurrentHashMap<AirPlaySession, Any>
        }
        val holder = requireNotNull(runtimeMap[session])
        val owner = holder.javaClass.getDeclaredField("matchPoint").run {
            isAccessible = true
            get(holder) as MatchPointProductSessionOwner
        }
        return runtimeMap to owner
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    private fun factory(
        provider: MatchPointProductAuthorityProvider,
        playerCalls: AtomicInteger,
    ) = MatchPointProductSessionFactory(provider, MatchPointProductPlayerPortFactory { _, _, _ ->
        playerCalls.incrementAndGet()
        error("No MatchPoint player should be created by a readiness probe")
    }, featureEligible = { true })

    private fun fullBundle(output: FakeOutput) = MatchPointProductAuthorityProvider { _, identity ->
        MatchPointProductAuthorityBundle(
            picIssuer = pic(identity),
            content = object : MatchPointItemContentAuthority {
                override val identity = identity
                override fun isAuthorized(item: MatchPointQueueItem) = true
                override fun authorizedRawRouteRoot(item: MatchPointQueueItem) = "/1/server"
                override fun onRevoked(closePlayer: () -> Unit) = Closeable {}
            },
            certifiedPark = object : MatchPointParkPlaybackAuthority {
                override val identity = identity
                override fun isCurrent() = true
                override fun onRevoked(closePlayer: () -> Unit) = Closeable {}
            },
            physicalOutput = output.forIdentity(identity),
            networkSockets = object : MatchPointSessionNetworkSocketAuthority {
                override val identity = identity
                override fun isCurrent() = true
                override fun createSocket() = Socket()
            },
            verifiedPairTlsRoute = object : MatchPointVerifiedPairTlsRoute {
                override val identity = identity
                override val policy = MatchPointPeerUrlPolicy.forNumericPeer(
                    MatchPointHttpTransport.PAIR_TLS_PSK, "127.0.0.1", 443)
                override fun isCurrent() = true
                override fun takeVerifiedPskIdentity() = byteArrayOf(7)
                override fun isAuthorized(uri: URI) = uri.rawPath.startsWith("/1/")
            },
        )
    }

    private fun pic(identity: MatchPointSessionIdentity) = object : MatchPointPicAuthorizer {
        override val identity = identity
        override fun authorize(
            item: MatchPointItemReference,
            picRequest: ByteArray,
        ): MatchPointAuthorizeItemResponse = error("Fake PIC must not authorize playback")
    }

    private class FakeOutput(private val liveness: () -> Boolean = { true }) {
        val covers = AtomicInteger()
        val failuresRemaining = AtomicInteger()
        fun forIdentity(sessionIdentity: MatchPointSessionIdentity) = object : MatchPointAndroidSurfaceRoute {
            override val identity = sessionIdentity
            override val surface: Surface get() = error("No fake Surface is installed")
            override fun isCurrent() = liveness()
            override fun coverImmediately() {
                covers.incrementAndGet()
                if (failuresRemaining.getAndUpdate { remaining -> (remaining - 1).coerceAtLeast(0) } > 0) {
                    throw IOException("physical cover retirement pending")
                }
            }
            override fun onRevoked(closePlayer: () -> Unit) = Closeable {}
        }
    }

    private data class Fixture(
        val session: AirPlaySession,
        val controller: AirPlayCrypto.Ed25519KeyPair,
    )

    private fun fixture(
        media: AirPlayMediaHandler = object : AirPlayMediaHandler {},
        onEnded: () -> Unit = {},
    ): Fixture {
        val identity = AirPlayIdentity.generate()
        val controller = AirPlayCrypto.ed25519Generate()
        val pairings = PairingStore().apply { save(CONTROLLER_ID, controller.publicKey) }
        val socket = object : Socket() {
            override fun getInetAddress(): InetAddress = peer
            override fun getLocalAddress(): InetAddress = peer
            override fun getRemoteSocketAddress() = InetSocketAddress(peer, 49152)
        }
        return Fixture(AirPlaySession(
            socket = socket,
            config = AirPlayConfig(
                deviceName = "matchpoint-test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0",
                main = AirPlayDisplayConfig(800, 480),
            ),
            identity = identity,
            pairings = pairings,
            mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) = onEnded()
            },
            media = media,
        ), controller)
    }

    private fun authenticate(fixture: Fixture) {
        finishPairVerify(fixture)
        markControlAuthenticated(fixture.session)
    }

    private fun finishPairVerify(fixture: Fixture) {
        val client = AirPlayCrypto.x25519Generate()
        val m1 = Tlv8Codec.encode(listOf(
            Tlv8Item(TYPE_STATE, byteArrayOf(1)),
            Tlv8Item(TYPE_PUBLIC_KEY, client.publicKey),
        ))
        val m2 = Tlv8Codec.decode(fixture.session.pairVerify.handle(m1))
        val serverPublic = requireNotNull(m2[TYPE_PUBLIC_KEY])
        val shared = AirPlayCrypto.x25519Shared(client.privateKey, serverPublic)
        client.privateKey.fill(0)
        val verifyKey = AirPlayCrypto.hkdfSha512(
            shared, "Pair-Verify-Encrypt-Salt".toByteArray(),
            "Pair-Verify-Encrypt-Info".toByteArray(),
        )
        shared.fill(0)
        val controllerId = CONTROLLER_ID.toByteArray()
        val signature = AirPlayCrypto.ed25519Sign(fixture.controller.privateKey,
            concatBytes(client.publicKey, controllerId, serverPublic))
        val inner = Tlv8Codec.encode(listOf(
            Tlv8Item(TYPE_IDENTIFIER, controllerId),
            Tlv8Item(TYPE_SIGNATURE, signature),
        ))
        val m3 = Tlv8Codec.encode(listOf(
            Tlv8Item(TYPE_STATE, byteArrayOf(3)),
            Tlv8Item(TYPE_ENCRYPTED_DATA,
                AirPlayCrypto.chachaSeal(verifyKey, AirPlayCrypto.nonceLabel("PV-Msg03"), inner)),
        ))
        verifyKey.fill(0)
        assertArrayEquals(byteArrayOf(4),
            Tlv8Codec.decode(fixture.session.pairVerify.handle(m3))[TYPE_STATE])
    }

    private fun markControlAuthenticated(session: AirPlaySession) {
        val keys = requireNotNull(session.pairVerify.controlKeys)
        try { session.cipher = ControlCipher(keys.readKey, keys.writeKey) }
        finally { keys.readKey.fill(0); keys.writeKey.fill(0) }
        session.javaClass.getDeclaredField("authenticatedControl").run {
            isAccessible = true
            (get(session) as AtomicBoolean).set(true)
        }
        session.javaClass.getDeclaredField("authenticatedPairVerifyGeneration").run {
            isAccessible = true
            set(session, requireNotNull(session.pairVerify.verifiedGeneration))
        }
    }

    private companion object {
        const val CONTROLLER_ID = "matchpoint-controller"
        const val TYPE_IDENTIFIER = 0x01
        const val TYPE_PUBLIC_KEY = 0x03
        const val TYPE_ENCRYPTED_DATA = 0x05
        const val TYPE_STATE = 0x06
        const val TYPE_SIGNATURE = 0x0a
    }
}
