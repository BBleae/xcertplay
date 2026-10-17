package com.shilapi.xcertplay.airplay.video

import androidx.media3.datasource.DataSource
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPointAndroidPlaybackPortTest {
    @Test
    fun urlPolicyPinsSchemePeerAndEffectivePortWithoutDnsOrRedirects() {
        val http = MatchPointPeerUrlPolicy.forNumericPeer(
            MatchPointHttpTransport.HTTP, "192.168.49.2", 80,
        )
        http.requireAllowed(URI("http://192.168.49.2/live/master.m3u8"))
        http.requireAllowed(URI("http://192.168.49.2:80/live/segment.ts"))
        listOf(
            "https://192.168.49.2:80/live/master.m3u8",
            "http://192.168.49.3/live/master.m3u8",
            "http://phone.local/live/master.m3u8",
            "http://192.168.49.2:8080/live/master.m3u8",
            "http://user@192.168.49.2/live/master.m3u8",
            "http://192.168.49.2/live/master.m3u8#part",
        ).forEach { candidate ->
            assertThrows(IOException::class.java) { http.requireAllowed(URI(candidate)) }
        }
        val tls = MatchPointPeerUrlPolicy.forPinnedPairTlsHost(
            "phone.local", "192.168.49.2",
        )
        tls.requireAllowed(URI("https://phone.local/1/asset.m3u8"))
        assertEquals("192.168.49.2", tls.socketAddress().address.hostAddress)
        assertEquals(443, tls.socketAddress().port)
        assertThrows(IOException::class.java) {
            tls.requireAllowed(URI("https://other.local/1/asset.m3u8"))
        }
        listOf(
            "https://phone.local/2/asset.m3u8",
            "https://phone.local/1/../asset.m3u8",
            "https://phone.local/1/%2e%2e/asset.m3u8",
            "https://phone.local/1/%2fprivate/asset.m3u8",
        ).forEach { candidate ->
            assertThrows(IOException::class.java) { tls.requireAllowed(URI(candidate)) }
        }
        val singleServer = MatchPointPeerUrlPolicy.forPinnedPairTlsHost(
            "phone.local", "192.168.49.2", rawPathPrefix = "/1/server-id/",
        )
        singleServer.requireAllowed(URI("https://phone.local/1/server-id/part.ts"))
        assertThrows(IOException::class.java) {
            singleServer.requireAllowed(URI("https://phone.local/1/other-server/part.ts"))
        }
        assertThrows(IOException::class.java) {
            requireUnredirectedMatchPointLoad(
                URI("https://phone.local/1/master.m3u8"),
                URI("https://phone.local/1/redirected.m3u8"),
            )
        }
        assertThrows(IOException::class.java) {
            requireUnredirectedMatchPointLoad(URI("https://phone.local/1/master.m3u8"), null)
        }
        val item = URI("https://phone.local/1/server-id/master.m3u8")
        val root = matchPointPairTlsRouteRoot(item)
        assertEquals("/1/server-id", root)
        requireMatchPointRouteRoot(item, root)
        assertThrows(IOException::class.java) {
            requireMatchPointRouteRoot(URI("https://phone.local/1/other-server/key"), root)
        }
        assertThrows(IOException::class.java) { requireNarrowMatchPointRouteRoot("/") }
    }

    @Test
    fun noPicOrParkGrantMeansNoMediaSourceAndNoReadinessClaim() {
        val identity = MatchPointSessionIdentity.create(1001)
        val engine = FakeEngine()
        val route = FakeRoute(identity)
        val port = MatchPointAndroidPlaybackPort(identity, engine, route)
        assertThrows(IOException::class.java) { port.dispatch(insert("http://192.168.49.2/a.m3u8")) }
        assertTrue(engine.media.isEmpty())
        assertFalse(engine.ready)
        assertThrows(IOException::class.java) { port.dispatch(MatchPointPlaybackAction.Stop) }
        port.close()
        assertEquals(1, engine.closes)
        assertTrue(route.covers > 0)
    }

    @Test
    fun tlsFlagCannotFallBackToOrdinaryHttpsOrHttp() {
        val identity = MatchPointSessionIdentity.create(1002)
        val engine = FakeEngine()
        val route = FakeRoute(identity)
        val content = FakeContentAuthority(identity)
        val park = FakeParkAuthority(identity)
        val webPki = MatchPointDirectPeerHttpDataSourceFactory(
            identity,
            MatchPointPeerUrlPolicy.forNumericPeer(MatchPointHttpTransport.WEB_PKI_HTTPS,
                "192.168.49.2", 443),
            sessionIsCurrent = { true },
            verifiedSessionPeer = InetAddress.getByAddress(byteArrayOf(
                192.toByte(), 168.toByte(), 49, 2,
            )),
        )
        assertFalse(webPki.isCurrent()) // No exact Android Network was supplied.
        assertThrows(IllegalArgumentException::class.java) {
            MatchPointDirectPeerHttpDataSourceFactory(identity, webPki.policy, { true }, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MatchPointDirectPeerHttpDataSourceFactory(identity, webPki.policy, { true },
                InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 49, 3)))
        }
        val port = MatchPointAndroidPlaybackPort(identity, engine, route, content, park,
            transports = listOf(webPki))
        assertThrows(IOException::class.java) {
            port.dispatch(insert("https://192.168.49.2/1/server/master.m3u8", tls = true))
        }
        assertTrue(engine.media.isEmpty())
        port.close()

        val psk = FakePskFactory(identity, MatchPointPeerUrlPolicy.forPinnedPairTlsHost(
            "phone.local", "192.168.49.2"))
        val acceptedEngine = FakeEngine()
        val accepted = MatchPointAndroidPlaybackPort(identity, acceptedEngine,
            FakeRoute(identity), FakeContentAuthority(identity), FakeParkAuthority(identity),
            transports = listOf(psk))
        accepted.dispatch(insert("https://phone.local/1/server/master.m3u8", tls = true))
        assertEquals(1, acceptedEngine.media.size)
        assertFalse(acceptedEngine.ready) // accepted command != HLS or decoded-frame readiness
        accepted.close()
        assertTrue(psk.closed)
    }

    @Test
    fun orderedQueueSeekRateStopAndSessionRevocationAreBounded() {
        val identity = MatchPointSessionIdentity.create(1003)
        val engine = FakeEngine()
        val route = FakeRoute(identity)
        val content = FakeContentAuthority(identity)
        val park = FakeParkAuthority(identity)
        val current = AtomicBoolean(true)
        val transport = FakePskFactory(identity,
            MatchPointPeerUrlPolicy.forPinnedPairTlsHost("phone.local", "192.168.49.2"),
            { current.get() })
        val port = MatchPointAndroidPlaybackPort(identity, engine, route, content, park,
            listOf(transport), maximumItems = 2)
        port.dispatch(insert("https://phone.local/1/server/a.m3u8", id = "a", tls = true))
        port.dispatch(insert("https://phone.local/1/server/b.m3u8", id = "b", after = "a", tls = true))
        assertEquals(listOf(0, 1), engine.insertIndices)
        port.dispatch(MatchPointPlaybackAction.Seek(MatchPointItemReference("b"),
            MatchPointSeekPosition.Time(MatchPointCmTime(3, 2)), null))
        assertEquals(1 to 1_500L, engine.lastSeek)
        port.dispatch(MatchPointPlaybackAction.SetRate(1.25, null, null, null))
        assertEquals(1.25f, engine.lastRate)
        port.dispatch(MatchPointPlaybackAction.Stop)
        assertEquals(1, engine.stops)
        port.dispatch(MatchPointPlaybackAction.Remove(MatchPointItemReference("a")))
        assertEquals(listOf(0), engine.removeIndices)
        current.set(false)
        assertThrows(IOException::class.java) {
            port.dispatch(insert("https://phone.local/1/server/c.m3u8", id = "c", tls = true))
        }
        assertEquals(2, engine.media.size)
        port.close()
        assertEquals(1, engine.closes)
    }

    @Test
    fun revocationAndPlayerFailureCannotTurnIntoSuccess() {
        val identity = MatchPointSessionIdentity.create(1004)
        val route = FakeRoute(identity)
        val engine = FakeEngine().apply { failInsert = true }
        val port = MatchPointAndroidPlaybackPort(identity, engine, route,
            FakeContentAuthority(identity), FakeParkAuthority(identity),
            listOf(FakePskFactory(identity,
                MatchPointPeerUrlPolicy.forPinnedPairTlsHost("phone.local", "192.168.49.2"))))
        assertThrows(IOException::class.java) {
            port.dispatch(insert("https://phone.local/1/server/a.m3u8", tls = true))
        }
        assertFalse(engine.ready)
        assertThrows(IOException::class.java) { port.dispatch(MatchPointPlaybackAction.Stop) }
        assertTrue(eventually { engine.closes == 1 })
        assertTrue(route.covers > 0)
        assertTrue(engine.aborts > 0)
        port.close()

        val secondEngine = FakeEngine()
        val secondRoute = FakeRoute(identity)
        val second = MatchPointAndroidPlaybackPort(identity, secondEngine, secondRoute,
            FakeContentAuthority(identity), FakeParkAuthority(identity))
        secondRoute.revoke()
        assertTrue(eventually { secondEngine.closes == 1 })
        assertTrue(secondRoute.covers > 0)
        assertThrows(IOException::class.java) { second.dispatch(MatchPointPlaybackAction.Stop) }
    }

    @Test
    fun controlledLooperDropsQueuedCommandAfterCallerTimeout() {
        val queued = ArrayDeque<Runnable>()
        val posted = CountDownLatch(1)
        val mutations = AtomicInteger()
        val retireRequests = AtomicInteger()
        val result = AtomicReference<Throwable?>()
        val commands = MatchPointPlayerCommandQueue(
            post = { queued.addLast(it); posted.countDown(); true },
            isOwnerThread = { false },
            onTerminal = { retireRequests.incrementAndGet() },
            timeoutMillis = 30,
        )
        val caller = Thread {
            try { commands.call { mutations.incrementAndGet() } }
            catch (failure: Throwable) { result.set(failure) }
        }
        caller.start()
        assertTrue(posted.await(1, TimeUnit.SECONDS))
        caller.join(1_000)
        assertFalse(caller.isAlive)
        assertTrue(result.get() is IOException)
        assertEquals(1, retireRequests.get())
        queued.removeFirst().run() // The fake Looper finally drains the stale queued command.
        assertEquals(0, mutations.get())
    }

    @Test
    fun runningCommandCannotPerformSecondPlayerMutationAfterTimeout() {
        val queued = ArrayDeque<Runnable>()
        val posted = CountDownLatch(1)
        val firstMutation = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val secondMutations = AtomicInteger()
        val result = AtomicReference<Throwable?>()
        val commands = MatchPointPlayerCommandQueue(
            post = { queued.addLast(it); posted.countDown(); true },
            isOwnerThread = { false },
            onTerminal = { },
            timeoutMillis = 30,
        )
        val caller = Thread {
            try {
                commands.call {
                    firstMutation.countDown()
                    resume.await(1, TimeUnit.SECONDS)
                    commands.requireOpen()
                    secondMutations.incrementAndGet()
                }
            } catch (failure: Throwable) { result.set(failure) }
        }
        caller.start()
        assertTrue(posted.await(1, TimeUnit.SECONDS))
        val looper = Thread { queued.removeFirst().run() }
        looper.start()
        assertTrue(firstMutation.await(1, TimeUnit.SECONDS))
        caller.join(1_000)
        assertFalse(caller.isAlive)
        assertTrue(result.get() is IOException)
        resume.countDown()
        looper.join(1_000)
        assertEquals(0, secondMutations.get())
    }

    @Test
    fun synchronousRevocationDuringSubscriptionCoversAndClosesEveryCollectedOwner() {
        val identity = MatchPointSessionIdentity.create(1005)
        val route = FakeRoute(identity, revokeOnSubscribe = true)
        val engine = FakeEngine()
        val transport = FakePskFactory(identity,
            MatchPointPeerUrlPolicy.forPinnedPairTlsHost("phone.local", "192.168.49.2"))
        val port = MatchPointAndroidPlaybackPort(identity, engine, route,
            FakeContentAuthority(identity), FakeParkAuthority(identity), listOf(transport))
        assertTrue(eventually { engine.closes == 1 && transport.closed && route.subscriptionCloses == 1 })
        assertTrue(route.covers > 0)
        assertThrows(IOException::class.java) { port.dispatch(MatchPointPlaybackAction.Stop) }
    }

    @Test
    fun transportOnlyOrPicOnlyRevocationBlocksBufferedPlaybackBeforeRate() {
        val identity = MatchPointSessionIdentity.create(1006)
        val route = FakeRoute(identity)
        val content = FakeContentAuthority(identity)
        val engine = FakeEngine()
        val transportCurrent = AtomicBoolean(true)
        val transport = FakePskFactory(identity,
            MatchPointPeerUrlPolicy.forPinnedPairTlsHost("phone.local", "192.168.49.2"),
            { transportCurrent.get() })
        val port = MatchPointAndroidPlaybackPort(identity, engine, route, content,
            FakeParkAuthority(identity), listOf(transport))
        port.dispatch(insert("https://phone.local/1/server/master.m3u8", tls = true))
        transportCurrent.set(false)
        assertThrows(IOException::class.java) {
            port.dispatch(MatchPointPlaybackAction.SetRate(1.0, null, null, null))
        }
        assertEquals(0f, engine.lastRate)
        assertTrue(route.covers > 0)
        assertTrue(eventually { engine.closes == 1 })

        val secondContent = FakeContentAuthority(identity)
        val secondRoute = FakeRoute(identity)
        val secondEngine = FakeEngine()
        val second = MatchPointAndroidPlaybackPort(identity, secondEngine, secondRoute,
            secondContent, FakeParkAuthority(identity),
            listOf(FakePskFactory(identity,
                MatchPointPeerUrlPolicy.forPinnedPairTlsHost("phone.local", "192.168.49.2"))))
        second.dispatch(insert("https://phone.local/1/server/master.m3u8", tls = true))
        secondContent.revokedIds += "one" // Callback is deliberately delayed.
        assertThrows(IOException::class.java) {
            second.dispatch(MatchPointPlaybackAction.Seek(MatchPointItemReference("one"),
                MatchPointSeekPosition.Time(MatchPointCmTime(1, 1)), null))
        }
        assertEquals(null, secondEngine.lastSeek)
        assertTrue(secondRoute.covers > 0)
        assertTrue(eventually { secondEngine.closes == 1 })

        val thirdContent = FakeContentAuthority(identity)
        val thirdRoute = FakeRoute(identity)
        val thirdEngine = FakeEngine()
        val third = MatchPointAndroidPlaybackPort(identity, thirdEngine, thirdRoute,
            thirdContent, FakeParkAuthority(identity),
            listOf(FakePskFactory(identity,
                MatchPointPeerUrlPolicy.forPinnedPairTlsHost("phone.local", "192.168.49.2"))))
        third.dispatch(insert("https://phone.local/1/server/master.m3u8", tls = true))
        thirdContent.overrideRoot = "/1/other-server"
        assertThrows(IOException::class.java) {
            third.dispatch(MatchPointPlaybackAction.SetRate(1.0, null, null, null))
        }
        assertEquals(0f, thirdEngine.lastRate)
        assertTrue(thirdRoute.covers > 0)
        assertTrue(eventually { thirdEngine.closes == 1 })
    }

    @Test
    fun unconfirmedReleaseRemainsRetryableWhileOutputIsCovered() {
        val identity = MatchPointSessionIdentity.create(1007)
        val route = FakeRoute(identity)
        val engine = FakeEngine().apply { failCloseOnce = true }
        val port = MatchPointAndroidPlaybackPort(identity, engine, route)
        assertThrows(IOException::class.java) { port.close() }
        assertEquals(1, engine.closes)
        assertTrue(route.covers > 0)
        port.close()
        assertEquals(2, engine.closes)
        var released = false
        var quit = false
        assertThrows(IOException::class.java) {
            retireMatchPointOwnerLooper(
                release = { released = true; throw IOException("detach reported an error") },
                isReleased = { released },
                quit = { quit = true },
            )
        }
        assertTrue(quit)
    }

    @Test
    fun coverCallbackReenteringRevocationCannotRecurseOrDoubleClose() {
        val identity = MatchPointSessionIdentity.create(1008)
        val route = FakeRoute(identity, coverTriggersRevocation = true)
        val engine = FakeEngine()
        val port = MatchPointAndroidPlaybackPort(identity, engine, route)
        route.revoke()
        assertTrue(eventually { engine.closes == 1 })
        assertEquals(1, route.covers)
        port.close()
        assertEquals(1, engine.closes)
    }

    @Test
    fun asyncPlayerErrorOnlyRetiresExactLiveInstanceOnce() {
        val player = Any()
        val otherPlayer = Any()
        val terminal = AtomicBoolean(false)
        val covers = AtomicInteger()
        val gate = MatchPointPlayerAsyncFailureGate(player, { terminal.get() }) {
            covers.incrementAndGet()
            terminal.set(true)
        }
        gate.onError(otherPlayer)
        assertEquals(0, covers.get())
        gate.onError(player)
        gate.onError(player)
        assertEquals(1, covers.get())
        val stale = MatchPointPlayerAsyncFailureGate(player, { true }) { covers.incrementAndGet() }
        stale.onError(player)
        assertEquals(1, covers.get())
    }

    private fun insert(
        url: String,
        id: String = "one",
        after: String? = null,
        tls: Boolean? = null,
    ): MatchPointPlaybackAction.Insert = MatchPointPlaybackAction.Insert(
        MatchPointQueueItem(id, contentLocation = url, isTlsEnabled = tls),
        after?.let(::MatchPointItemReference), null, null,
    )

    private fun eventually(condition: () -> Boolean): Boolean {
        repeat(100) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private class FakeEngine : MatchPointLocalPlaybackEngine {
        val media = ArrayList<MatchPointLocalMedia>()
        val insertIndices = ArrayList<Int>()
        val removeIndices = ArrayList<Int>()
        var lastSeek: Pair<Int, Long>? = null
        var lastRate = 0f
        var stops = 0
        @Volatile var closes = 0
        @Volatile var aborts = 0
        var ready = false
        var failInsert = false
        var failCloseOnce = false
        private var admission: (() -> Boolean)? = null

        override fun bindLiveAdmission(isCurrent: () -> Boolean, onLost: () -> Unit) {
            admission = isCurrent
        }

        override fun abortPendingCommands() { aborts++ }

        override fun insert(index: Int, media: MatchPointLocalMedia) {
            if (admission?.invoke() != true) throw IOException("fake player admission denied")
            if (failInsert) throw IOException("fake player rejected media")
            this.media += media
            insertIndices += index
        }
        override fun remove(index: Int) {
            if (admission?.invoke() != true) throw IOException("fake player admission denied")
            removeIndices += index
        }
        override fun stop() { stops++ }
        override fun seek(index: Int, positionMillis: Long) {
            if (admission?.invoke() != true) throw IOException("fake player admission denied")
            lastSeek = index to positionMillis
        }
        override fun setRate(rate: Float) {
            if (rate > 0f && admission?.invoke() != true) throw IOException("fake player admission denied")
            lastRate = rate
        }
        override fun close() {
            closes++
            if (failCloseOnce) { failCloseOnce = false; throw IOException("release still pending") }
        }
    }

    private class FakeRoute(
        override val identity: MatchPointSessionIdentity,
        private val revokeOnSubscribe: Boolean = false,
        private val coverTriggersRevocation: Boolean = false,
    ) : MatchPointVideoOutputRoute {
        private val callbacks = ArrayList<() -> Unit>()
        private var current = true
        @Volatile var covers = 0
        @Volatile var subscriptionCloses = 0
        override fun isCurrent(): Boolean = current
        override fun coverImmediately() {
            covers++
            if (coverTriggersRevocation) callbacks.toList().forEach { it() }
        }
        override fun onRevoked(closePlayer: () -> Unit): Closeable {
            callbacks += closePlayer
            if (revokeOnSubscribe) { current = false; closePlayer() }
            return Closeable { callbacks.remove(closePlayer); subscriptionCloses++ }
        }
        fun revoke() { current = false; callbacks.toList().forEach { it() } }
    }

    private class FakeContentAuthority(override val identity: MatchPointSessionIdentity) :
        MatchPointItemContentAuthority {
        val revokedIds = HashSet<String>()
        @Volatile var overrideRoot: String? = null
        override fun isAuthorized(item: MatchPointQueueItem) = item.uuid !in revokedIds
        override fun authorizedRawRouteRoot(item: MatchPointQueueItem): String? =
            overrideRoot ?: if (item.isTlsEnabled == true) "/1/server" else "/session"
        override fun onRevoked(closePlayer: () -> Unit): Closeable = Closeable { }
    }

    private class FakeParkAuthority(override val identity: MatchPointSessionIdentity) :
        MatchPointParkPlaybackAuthority {
        override fun isCurrent() = true
        override fun onRevoked(closePlayer: () -> Unit): Closeable = Closeable { }
    }

    private class FakePskFactory(
        override val identity: MatchPointSessionIdentity,
        override val policy: MatchPointPeerUrlPolicy,
        private val sessionIsCurrent: () -> Boolean = { true },
    ) : MatchPointPairTlsPskDataSourceFactory {
        @Volatile var closed = false
        override fun isCurrent() = !closed && sessionIsCurrent()
        override fun createDataSource(): DataSource = throw AssertionError("No network access expected")
        override fun close() { closed = true }
    }
}
