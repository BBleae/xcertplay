package com.shilapi.xcertplay.airplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AirPlaySessionCloseTest {
    @Test
    fun stoppingCoverRunsWhileEarlierCommandCallbackHoldsDispatchLock() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val covered = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val session = session(
            socket = TrackingSocket(),
            media = object : AirPlayMediaHandler {
                override fun onSessionStopping(session: AirPlaySession) {
                    assertTrue(session.isClosed)
                    covered.countDown()
                }
            },
            listener = object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) {
                    ended.countDown()
                }
            },
        )
        val dispatchLock = requireNotNull(
            AirPlaySession::class.java.getDeclaredField("commandDispatchLock")
                .apply { isAccessible = true }.get(session),
        )
        val callback = Thread {
            synchronized(dispatchLock) {
                callbackEntered.countDown()
                releaseCallback.await(5, TimeUnit.SECONDS)
            }
        }
        callback.start()
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        val closer = Thread { session.close() }
        closer.start()
        assertTrue("STOPPING cover waited for a blocked callback", covered.await(1, TimeUnit.SECONDS))
        assertFalse(ended.await(50, TimeUnit.MILLISECONDS))
        releaseCallback.countDown()
        callback.join(5_000)
        closer.join(5_000)
        assertFalse(closer.isAlive)
        assertTrue(ended.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun failedStoppingCoverRetainsExactSessionAndRetriesBeforeMediaClose() {
        val coverCalls = AtomicInteger()
        val allowCover = AtomicBoolean(false)
        val mediaClosed = AtomicInteger()
        val ended = CountDownLatch(1)
        val session = session(
            socket = TrackingSocket(),
            media = object : AirPlayMediaHandler {
                override fun onSessionStopping(session: AirPlaySession) {
                    coverCalls.incrementAndGet()
                    if (!allowCover.get()) throw IOException("MAIN cover pending")
                }
                override fun onSessionClosed(session: AirPlaySession) {
                    mediaClosed.incrementAndGet()
                }
            },
            listener = object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) {
                    ended.countDown()
                }
            },
        )
        expectIOException("MAIN cover pending") { session.close() }
        assertEquals(0, mediaClosed.get())
        assertFalse(ended.await(50, TimeUnit.MILLISECONDS))
        allowCover.set(true)
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertTrue(coverCalls.get() >= 2)
        assertEquals(1, mediaClosed.get())
    }

    @Test
    fun mediaFailureRetainsCallbackStageAndClearsSuccessfulSocketOnlyOnce() {
        val socket = TrackingSocket()
        val mediaCalls = AtomicInteger()
        val allowMediaRetirement = AtomicBoolean(false)
        val ended = CountDownLatch(1)
        val media = object : AirPlayMediaHandler {
            override fun onSessionClosed(session: AirPlaySession) {
                mediaCalls.incrementAndGet()
                if (!allowMediaRetirement.get()) throw IOException("media close pending")
            }
        }
        val session = session(
            socket = socket,
            media = media,
            listener = object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) {
                    ended.countDown()
                }
            },
        )

        expectIOException("media close pending") { session.close() }
        assertTrue(session.isClosed)
        assertEquals(1, socket.closeCalls)
        assertTrue(mediaCalls.get() >= 1)
        assertFalse(ended.await(50, TimeUnit.MILLISECONDS))

        allowMediaRetirement.set(true)
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        session.close()

        assertEquals(1, socket.closeCalls)
        assertTrue(mediaCalls.get() >= 2)
    }

    @Test
    fun socketFailureRetriesTheSameExactOwnerBeforeMediaAndNotification() {
        val socket = GatedCloseSocket()
        val mediaCalls = AtomicInteger()
        val ended = CountDownLatch(1)
        val session = session(
            socket = socket,
            media = object : AirPlayMediaHandler {
                override fun onSessionClosed(session: AirPlaySession) {
                    mediaCalls.incrementAndGet()
                }
            },
            listener = object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) {
                    ended.countDown()
                }
            },
        )

        expectIOException("socket close pending") { session.close() }
        assertTrue(socket.closeCalls >= 1)
        assertEquals(0, mediaCalls.get())
        assertFalse(ended.await(50, TimeUnit.MILLISECONDS))

        socket.allowClose.set(true)
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        session.close()

        assertSame(socket, socket.closedOwner)
        assertTrue(socket.closeCalls >= 2)
        assertEquals(1, mediaCalls.get())
    }

    @Test
    fun nonSessionMediaWorkerFailureSchedulesRetryAfterCallbackStackExits() {
        val mediaWorker = AtomicReference<Thread?>()
        val synchronousFailure = AtomicReference<Throwable?>()
        val mediaCalls = AtomicInteger()
        val endedCalls = AtomicInteger()
        val ended = CountDownLatch(1)
        val session = session(
            socket = TrackingSocket(),
            media = object : AirPlayMediaHandler {
                override fun onSessionClosed(session: AirPlaySession) {
                    mediaCalls.incrementAndGet()
                    if (mediaWorker.get()?.isAlive == true) {
                        throw IOException("screen worker retirement pending")
                    }
                }
            },
            listener = object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) {
                    endedCalls.incrementAndGet()
                    ended.countDown()
                }
            },
        )
        val worker = Thread(
            {
                try {
                    session.close()
                } catch (failure: Throwable) {
                    synchronousFailure.set(failure)
                }
            },
            "synthetic-screen-worker",
        )
        mediaWorker.set(worker)

        worker.start()
        worker.join(1_000)

        assertFalse(worker.isAlive)
        assertTrue(synchronousFailure.get() is IOException)
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        session.close()
        assertTrue(mediaCalls.get() >= 2)
        assertEquals(1, endedCalls.get())
    }

    @Test
    fun keepAliveBindFailureRetainsExactSocketForSessionRetry() {
        val keepAlive = GatedBindDatagramSocket()
        val session = session(
            socket = TrackingSocket(),
            keepAliveSocketFactory = { keepAlive },
        )

        expectPrivateIOException(session, "openKeepAlive", "keepalive bind failed")
        assertEquals(1, keepAlive.closeCalls)

        keepAlive.allowClose.set(true)
        session.close()

        assertEquals(2, keepAlive.closeCalls)
        assertSame(keepAlive, keepAlive.closedOwner)
    }

    @Test
    fun eventBindFailureRetainsExactServerForSessionRetry() {
        val eventServer = GatedBindServerSocket()
        val session = session(
            socket = TrackingSocket(),
            eventServerFactory = { eventServer },
        )

        expectPrivateIOException(session, "openEvent", "event bind failed")
        assertEquals(1, eventServer.closeCalls)

        eventServer.allowClose.set(true)
        session.close()

        assertEquals(2, eventServer.closeCalls)
        assertSame(eventServer, eventServer.closedOwner)
    }

    @Test
    fun ownedControlThreadDefersNotificationUntilItHasActuallyTerminated() {
        val ended = CountDownLatch(1)
        val callbackSawNoControlOwner = AtomicInteger()
        lateinit var session: AirPlaySession
        session = session(
            socket = TrackingSocket(input = ByteArrayInputStream(ByteArray(0))),
            listener = object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) {
                    val field = AirPlaySession::class.java.getDeclaredField("controlThread")
                        .apply { isAccessible = true }
                    if (field.get(session) == null) callbackSawNoControlOwner.incrementAndGet()
                    // Re-entry is safe because retirement proof was published before notification.
                    session.close()
                    ended.countDown()
                }
            },
        )

        session.start()

        assertTrue("deferred retirement did not finish", ended.await(3, TimeUnit.SECONDS))
        assertEquals(1, callbackSawNoControlOwner.get())
        try {
            session.start()
            fail("a retired session must not restart")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    fun ownedWorkerCloseThrowsDeferredInsteadOfLookingLikeServiceRemovalProof() {
        val closeFailure = AtomicReference<Throwable?>()
        val closeAttempted = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        lateinit var session: AirPlaySession
        val input = object : InputStream() {
            override fun read(): Int {
                if (firstRead.compareAndSet(true, false)) {
                    try {
                        session.close()
                    } catch (failure: Throwable) {
                        closeFailure.set(failure)
                    } finally {
                        closeAttempted.countDown()
                    }
                }
                return -1
            }
        }
        session = session(
            socket = TrackingSocket(input),
            listener = object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) {
                    ended.countDown()
                }
            },
        )

        session.start()

        assertTrue(closeAttempted.await(1, TimeUnit.SECONDS))
        assertTrue(closeFailure.get() is IOException)
        assertTrue(
            closeFailure.get()?.message.orEmpty().contains("retirement was deferred"),
        )
        assertTrue(ended.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun controlWorkerFinalCloseDuringCoordinatorCoverDoesNotEscapeTheWorker() {
        // Live 2026-10-17: phone stopSession -> control-thread close defers to the coordinator,
        // whose close holds the STOPPING cover while runControl's finally closes again.
        val coordinatorInCover = CountDownLatch(1)
        val releaseCover = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val uncaught = AtomicReference<Throwable?>()
        val firstRead = AtomicBoolean(true)
        lateinit var session: AirPlaySession
        val input = object : InputStream() {
            override fun read(): Int {
                if (firstRead.compareAndSet(true, false)) {
                    try {
                        session.close()
                    } catch (_: IOException) {
                        // Owned-worker deferral; the coordinator now owns retirement.
                    }
                    assertTrue(coordinatorInCover.await(5, TimeUnit.SECONDS))
                }
                return -1
            }
        }
        session = session(
            socket = TrackingSocket(input),
            media = object : AirPlayMediaHandler {
                override fun onSessionStopping(session: AirPlaySession) {
                    if (Thread.currentThread().name == "airplay-retirement") {
                        coordinatorInCover.countDown()
                        releaseCover.await(5, TimeUnit.SECONDS)
                    }
                }
            },
            listener = object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) {
                    ended.countDown()
                }
            },
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            if (thread.name == "airplay-control") uncaught.set(failure)
        }
        try {
            session.start()
            val control = requireNotNull(
                AirPlaySession::class.java.getDeclaredField("controlThread")
                    .apply { isAccessible = true }.get(session) as Thread?,
            )
            control.join(5_000)
            assertFalse(control.isAlive)
            releaseCover.countDown()
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            assertNull("control worker crashed: ${uncaught.get()}", uncaught.get())
        } finally {
            releaseCover.countDown()
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun joinTimeoutKeepsSessionUnprovenUntilTheSameWorkerExits() {
        val input = BlockingInputStream()
        val ended = CountDownLatch(1)
        val socket = TrackingSocket(input = input)
        val session = session(
            socket = socket,
            listener = object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) {
                    ended.countDown()
                }
            },
            retirementJoinTimeoutMillis = 40,
        )
        session.start()
        assertTrue(input.entered.await(1, TimeUnit.SECONDS))

        expectIOException("Timed out retiring AirPlay worker airplay-control") { session.close() }
        assertFalse("retirement must not be published after a join timeout", ended.await(50, TimeUnit.MILLISECONDS))

        input.release.countDown()
        assertTrue("later retry did not prove retirement", ended.await(3, TimeUnit.SECONDS))
        session.close()
        assertEquals(1, socket.closeCalls)
    }

    @Test
    fun successfulRetirementRetryWipesPairVerifyAndEveryCipherOnce() {
        val allowMediaRetirement = AtomicBoolean(false)
        val session = session(
            socket = TrackingSocket(),
            media = object : AirPlayMediaHandler {
                override fun onSessionClosed(session: AirPlaySession) {
                    if (!allowMediaRetirement.get()) throw IOException("media close pending")
                }
            },
        )
        val shared = ByteArray(32) { 0x25 }
        val pairKeys = PairVerify.ControlKeys(ByteArray(32) { 0x31 }, ByteArray(32) { 0x52 })
        setPrivate(session.pairVerify, "sharedSecret", shared)
        setPrivate(session.pairVerify, "keys", pairKeys)
        setPrivate(session.pairVerify, "verified", true)
        val controlCipher = ControlCipher(pairKeys.readKey, pairKeys.writeKey)
        val eventCipher = ControlCipher(ByteArray(32) { 0x17 }, ByteArray(32) { 0x28 })
        val controlRead = privateBytes(controlCipher, "readKey")
        val controlWrite = privateBytes(controlCipher, "writeKey")
        val eventRead = privateBytes(eventCipher, "readKey")
        val eventWrite = privateBytes(eventCipher, "writeKey")
        session.cipher = controlCipher
        @Suppress("UNCHECKED_CAST")
        val retainedEvents = privateValue(session, "ownedEventCiphers") as MutableList<ControlCipher>
        retainedEvents.add(eventCipher)
        // A failed event write or candidate disconnect can clear this slot before close().
        assertNull(privateValue(session, "eventCipher"))
        assertTrue(session.pairVerify.deriveFreshPairTlsPsk() != null)

        expectIOException("media close pending") { session.close() }
        assertTrue(shared.any { it != 0.toByte() })
        assertTrue(controlRead.any { it != 0.toByte() })
        assertTrue(eventRead.any { it != 0.toByte() })

        allowMediaRetirement.set(true)
        session.close()
        session.close()

        assertNull(session.pairVerify.deriveFreshPairTlsPsk())
        assertNull(session.pairVerify.shared)
        assertNull(session.pairVerify.controlKeys)
        assertNull(session.cipher)
        assertFalse(session.isAuthenticatedControl)
        assertTrue(shared.all { it == 0.toByte() })
        assertTrue(pairKeys.readKey.all { it == 0.toByte() })
        assertTrue(pairKeys.writeKey.all { it == 0.toByte() })
        assertTrue(controlRead.all { it == 0.toByte() })
        assertTrue(controlWrite.all { it == 0.toByte() })
        assertTrue(eventRead.all { it == 0.toByte() })
        assertTrue(eventWrite.all { it == 0.toByte() })
        assertTrue(retainedEvents.isEmpty())
    }

    private fun setPrivate(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun privateValue(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(target)
        }

    private fun privateBytes(target: Any, name: String): ByteArray =
        privateValue(target, name) as ByteArray

    private fun session(
        socket: Socket,
        listener: AirPlaySessionListener = object : AirPlaySessionListener {},
        media: AirPlayMediaHandler = object : AirPlayMediaHandler {},
        retirementJoinTimeoutMillis: Long = 2_000,
        keepAliveSocketFactory: () -> DatagramSocket = { DatagramSocket(null) },
        eventServerFactory: () -> ServerSocket = { ServerSocket() },
    ): AirPlaySession = AirPlaySession(
        socket = socket,
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = listener,
        media = media,
        retirementJoinTimeoutMillis = retirementJoinTimeoutMillis,
        keepAliveSocketFactory = keepAliveSocketFactory,
        eventServerFactory = eventServerFactory,
    )

    private fun expectPrivateIOException(target: Any, methodName: String, message: String) {
        try {
            target.javaClass.getDeclaredMethod(methodName).apply { isAccessible = true }.invoke(target)
            fail("expected IOException containing '$message'")
        } catch (failure: InvocationTargetException) {
            val cause = failure.cause
            assertTrue("unexpected cause: $cause", cause is IOException)
            assertTrue(cause?.message.orEmpty().contains(message))
        }
    }

    private fun expectIOException(message: String, block: () -> Unit) {
        try {
            block()
            fail("expected IOException containing '$message'")
        } catch (error: IOException) {
            assertTrue(
                "unexpected message: ${error.message}",
                error.message.orEmpty().contains(message),
            )
        }
    }

    private open class TrackingSocket(
        private val input: InputStream = ByteArrayInputStream(ByteArray(0)),
    ) : Socket() {
        private val output = ByteArrayOutputStream()
        var closeCalls = 0
            protected set
        var closedOwner: Socket? = null
            protected set

        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()

        override fun close() {
            closeCalls++
            closedOwner = this
            closeUnderlying()
        }

        protected fun closeUnderlying() = super.close()
    }

    private class GatedCloseSocket : TrackingSocket() {
        val allowClose = AtomicBoolean(false)

        override fun close() {
            closeCalls++
            if (!allowClose.get()) throw IOException("socket close pending")
            closedOwner = this
            closeUnderlying()
        }
    }

    private class GatedBindDatagramSocket : DatagramSocket(null as SocketAddress?) {
        val allowClose = AtomicBoolean(false)
        var closeCalls = 0
        var closedOwner: DatagramSocket? = null

        override fun bind(addr: SocketAddress?) {
            throw IOException("keepalive bind failed")
        }

        override fun close() {
            closeCalls++
            if (!allowClose.get()) throw IOException("keepalive close pending")
            closedOwner = this
            super.close()
        }
    }

    private class GatedBindServerSocket : ServerSocket() {
        val allowClose = AtomicBoolean(false)
        var closeCalls = 0
        var closedOwner: ServerSocket? = null

        override fun bind(endpoint: SocketAddress?, backlog: Int) {
            throw IOException("event bind failed")
        }

        override fun close() {
            closeCalls++
            if (!allowClose.get()) throw IOException("event close pending")
            closedOwner = this
            super.close()
        }
    }

    private class BlockingInputStream : InputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun read(): Int {
            entered.countDown()
            while (true) {
                try {
                    release.await()
                    return -1
                } catch (_: InterruptedException) {
                    // Deliberately model a broken transport that ignores interruption and close.
                }
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()
    }
}
