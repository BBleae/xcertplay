package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import java.io.IOException
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayUltraRendererOwnershipTest {
    @Test
    fun failedSessionOpenRetainsExactCafChildForExplicitCleanupRetry() {
        val cafChild = FailingCloseable(closeFailures = 1)
        val retired = AtomicInteger()
        val cleanup = AirPlayUltraSessionOpenCleanup(cafChild, null) {
            retired.incrementAndGet()
        }

        assertThrows(IllegalStateException::class.java) { cleanup.close() }
        assertEquals(1, cafChild.closeCalls.get())
        assertEquals(0, retired.get())
        cleanup.close()
        assertEquals(2, cafChild.closeCalls.get())
        assertEquals(1, retired.get())
        cleanup.close()
        assertEquals(2, cafChild.closeCalls.get())
        assertEquals(1, retired.get())
    }

    @Test
    fun failedSessionOpenRetainsExactUiSyncChildForExplicitCleanupRetry() {
        val uiSyncChild = FailingCloseable(closeFailures = 1)
        val retired = AtomicInteger()
        val cleanup = AirPlayUltraSessionOpenCleanup(null, uiSyncChild) {
            retired.incrementAndGet()
        }

        assertThrows(IllegalStateException::class.java) { cleanup.close() }
        assertEquals(1, uiSyncChild.closeCalls.get())
        assertEquals(0, retired.get())
        cleanup.close()
        assertEquals(2, uiSyncChild.closeCalls.get())
        assertEquals(1, retired.get())
        cleanup.close()
        assertEquals(2, uiSyncChild.closeCalls.get())
        assertEquals(1, retired.get())
    }

    @Test
    fun sessionCloseRetainsHandlerUntilDelegateCloseSucceeds() {
        val delegate = FailingHandler(closeFailures = 1)
        val runtime = AirPlayUltraRuntime(cafFactory = handlerFactory { delegate })
        val session = runtime.openSession()
        val stream = testStream()
        val descriptor = requireNotNull(
            session.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        )
        descriptor.create(stream)

        assertThrows(IllegalStateException::class.java) { session.close() }
        assertEquals(1, delegate.closeCalls.get())
        session.close()
        assertEquals(2, delegate.closeCalls.get())

        runtime.close()
        stream.close()
    }

    @Test
    fun closeRacingHandlerCreationRetainsRejectedHandlerForRetry() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = FailingHandler(closeFailures = 1)
        val runtime = AirPlayUltraRuntime(
            cafFactory = handlerFactory {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                delegate
            },
        )
        val session = runtime.openSession()
        val stream = testStream()
        val descriptor = requireNotNull(
            session.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        )
        val creationFailure = AtomicReference<Throwable?>()
        val creating = Thread {
            try {
                descriptor.create(stream)
            } catch (failure: Throwable) {
                creationFailure.set(failure)
            }
        }
        creating.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertThrows(IllegalStateException::class.java) { session.close() }
        release.countDown()
        creating.join(5_000)
        assertTrue(creationFailure.get() is IllegalStateException)
        assertEquals(1, delegate.closeCalls.get())

        session.close()
        assertEquals(2, delegate.closeCalls.get())
        runtime.close()
        stream.close()
    }

    @Test
    fun revokedLegacyHandlerRejectsForeignAndLateBusinessCallbacks() {
        val delegate = RecordingFailingHandler(closeFailures = 1)
        val runtime = AirPlayUltraRuntime(cafFactory = handlerFactory { delegate })
        val session = runtime.openSession()
        val stream = testStream()
        val foreign = testStream()
        val owned = requireNotNull(
            session.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        ).create(stream)
        val message = RcsMessage.comm(byteArrayOf(1))

        owned.onMessage(foreign, message)
        assertEquals(0, delegate.messages.get())
        owned.onStreamOpened(stream)
        owned.onMessage(stream, message)
        assertEquals(1, delegate.opens.get())
        assertEquals(1, delegate.messages.get())

        assertThrows(IllegalStateException::class.java) { session.close() }
        owned.onMessage(stream, message)
        owned.onStreamOpened(stream)
        owned.onStreamClosed(foreign, null)
        assertEquals(1, delegate.messages.get())
        assertEquals(1, delegate.opens.get())

        session.close()
        runtime.close()
        stream.close()
        foreign.close()
    }

    @Test
    fun closeNeverRunsDelegateConcurrentlyWithLegacyMessageCallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = BlockingHandler(entered, release)
        val runtime = AirPlayUltraRuntime(cafFactory = handlerFactory { delegate })
        val session = runtime.openSession()
        val stream = testStream()
        val owned = requireNotNull(
            session.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        ).create(stream)
        val callbackFailure = AtomicReference<Throwable?>()
        val callback = Thread {
            try {
                owned.onMessage(stream, RcsMessage.comm(byteArrayOf(2)))
            } catch (failure: Throwable) {
                callbackFailure.set(failure)
            }
        }
        callback.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertThrows(IllegalStateException::class.java) { session.close() }
        assertEquals(0, delegate.closeCalls.get())
        release.countDown()
        callback.join(5_000)

        assertFalse(callback.isAlive)
        assertEquals(null, callbackFailure.get())
        owned.onMessage(stream, RcsMessage.comm(byteArrayOf(3)))
        assertEquals(1, delegate.messages.get())

        session.close()
        assertEquals(1, delegate.closeCalls.get())
        runtime.close()
        stream.close()
    }

    @Test
    fun exactTerminalCallbackIsDeliveredOnceWithCauseBeforeRetirement() {
        val delegate = RecordingFailingHandler(closeFailures = 0)
        val runtime = AirPlayUltraRuntime(cafFactory = handlerFactory { delegate })
        val session = runtime.openSession()
        val stream = testStream()
        val foreign = testStream()
        val owned = requireNotNull(
            session.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        ).create(stream)
        val cause = IOException("synthetic peer EOF")

        owned.onStreamClosed(foreign, cause)
        owned.onStreamClosed(stream, cause)
        owned.onStreamClosed(stream, IOException("duplicate"))

        assertEquals(1, delegate.terminals.get())
        assertSame(cause, delegate.lastTerminalCause.get())
        owned.close()
        session.close()
        runtime.close()
        stream.close()
        foreign.close()
    }

    private fun handlerFactory(
        create: () -> RcsDataStreamHandler,
    ) = RcsDataStreamHandlerFactory { requested ->
        if (requested != RcsClientTypes.CAR_PLAY_PROTOCOL_DATA) {
            null
        } else {
            RcsDataStreamHandlerDescriptor.ready(
                requested,
                "test.retry-owned-handler",
            ) { create() }
        }
    }

    private fun testStream(): RcsDataStream = RcsDataStream.open(
        clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
        readKey = ByteArray(32) { 1 },
        writeKey = ByteArray(32) { 2 },
        bindAddress = InetAddress.getLoopbackAddress(),
    )

    private class FailingHandler(private val closeFailures: Int) : RcsDataStreamHandler {
        val closeCalls = AtomicInteger()
        override fun close() {
            val call = closeCalls.incrementAndGet()
            if (call <= closeFailures) throw IllegalStateException("retry handler close")
        }
    }

    private class FailingCloseable(private val closeFailures: Int) : Closeable {
        val closeCalls = AtomicInteger()

        override fun close() {
            if (closeCalls.incrementAndGet() <= closeFailures) {
                throw IllegalStateException("retry child close")
            }
        }
    }

    private class RecordingFailingHandler(
        private val closeFailures: Int,
    ) : RcsDataStreamHandler {
        val messages = AtomicInteger()
        val opens = AtomicInteger()
        val terminals = AtomicInteger()
        val lastTerminalCause = AtomicReference<Throwable?>()
        private val closeCalls = AtomicInteger()

        override fun onStreamOpened(stream: RcsDataStream) {
            opens.incrementAndGet()
        }

        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            messages.incrementAndGet()
        }

        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            terminals.incrementAndGet()
            lastTerminalCause.set(cause)
        }

        override fun close() {
            if (closeCalls.incrementAndGet() <= closeFailures) {
                throw IllegalStateException("retry handler close")
            }
        }
    }

    private class BlockingHandler(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : RcsDataStreamHandler {
        val messages = AtomicInteger()
        val closeCalls = AtomicInteger()

        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            messages.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        }

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }
}
