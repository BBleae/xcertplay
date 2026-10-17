package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaEngineSessionOpenCleanupTest {
    @Test
    fun typedFailedOpenKeepsExactSessionEndingBlockedUntilCleanupSucceeds() {
        val closeCalls = AtomicInteger()
        val thirdCloseEntered = CountDownLatch(1)
        val finishThirdClose = CountDownLatch(1)
        val cleanup = Closeable {
            when (closeCalls.incrementAndGet()) {
                1, 2 -> throw IOException("exact child retirement pending")
                else -> {
                    thirdCloseEntered.countDown()
                    if (!finishThirdClose.await(5, TimeUnit.SECONDS)) {
                        throw IOException("exact child retirement was not released")
                    }
                }
            }
        }
        val openCalls = AtomicInteger()
        val runtime = failingRuntime(openCalls, cleanup)
        val engine = CarPlayMediaEngine(object : MediaSink {}, ultraRuntime = runtime)
        val ended = CountDownLatch(1)
        val session = testSession(engine) { ended.countDown() }

        assertFalse(engine.isRcsReady(session, listOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)))
        assertEquals(1, openCalls.get())
        assertNull(publishedRuntimes(engine)[session])
        assertTrue(failedConstructions(engine).containsKey(session))

        assertThrows(IOException::class.java) { session.close() }
        assertTrue(thirdCloseEntered.await(5, TimeUnit.SECONDS))
        assertEquals(1L, ended.count)
        assertTrue(failedConstructions(engine).containsKey(session))

        finishThirdClose.countDown()
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(3, closeCalls.get())
        assertNull(failedConstructions(engine)[session])
        assertEquals(1, openCalls.get())
        engine.close()
    }

    @Test
    fun engineCloseRetriesTypedFailedOpenBeforeRetiringUltraRoot() {
        val closeCalls = AtomicInteger()
        val runtime = failingRuntime(AtomicInteger(), Closeable {
            if (closeCalls.incrementAndGet() <= 2) {
                throw IOException("exact child retirement pending")
            }
        })
        val engine = CarPlayMediaEngine(object : MediaSink {}, ultraRuntime = runtime)
        val ended = AtomicInteger()
        val session = testSession(engine) { ended.incrementAndGet() }

        assertFalse(engine.isRcsReady(session, listOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)))
        assertThrows(IOException::class.java) { engine.close() }
        assertThrows(IOException::class.java) { engine.close() }
        assertEquals(0, ended.get())
        assertTrue(failedConstructions(engine).containsKey(session))
        assertNull(publishedRuntimes(engine)[session])

        engine.close()
        assertEquals(3, closeCalls.get())
        assertNull(failedConstructions(engine)[session])
        session.close()
        assertEquals(1, ended.get())
    }

    @Test
    fun absentUltraAndOrdinaryOpenFailureHaveNoFailedConstructionOwner() {
        val absentEngine = CarPlayMediaEngine(object : MediaSink {})
        val absentEnded = AtomicInteger()
        val absentSession = testSession(absentEngine) { absentEnded.incrementAndGet() }
        assertFalse(absentEngine.isRcsReady(
            absentSession,
            listOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL),
        ))
        assertTrue(failedConstructions(absentEngine).isEmpty())
        absentSession.close()
        assertEquals(1, absentEnded.get())
        absentEngine.close()

        val openCalls = AtomicInteger()
        val runtime = AirPlayUltraRuntime.forTest(
            sessionRuntimeFactory = AirPlayUltraSessionRuntimeFactory { _, _, _, _, _ ->
                openCalls.incrementAndGet()
                throw IllegalStateException("ordinary child construction failure")
            },
        )
        val engine = CarPlayMediaEngine(object : MediaSink {}, ultraRuntime = runtime)
        val ended = AtomicInteger()
        val session = testSession(engine) { ended.incrementAndGet() }
        assertFalse(engine.isRcsReady(session, listOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)))
        assertTrue(failedConstructions(engine).isEmpty())
        assertNull(publishedRuntimes(engine)[session])
        session.close()
        assertEquals(1, ended.get())
        assertEquals(1, openCalls.get())
        engine.close()
    }

    private fun failingRuntime(openCalls: AtomicInteger, cleanup: Closeable) =
        AirPlayUltraRuntime.forTest(
            sessionRuntimeFactory = AirPlayUltraSessionRuntimeFactory { _, _, _, _, _ ->
                openCalls.incrementAndGet()
                throw AirPlayUltraSessionOpenCleanupException(
                    IllegalStateException("synthetic post-activation failure"),
                    cleanup,
                )
            },
        )

    private fun testSession(
        media: AirPlayMediaHandler,
        onEnded: () -> Unit,
    ) = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(800, 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {
            override fun onSessionEnded(session: AirPlaySession) = onEnded()
        },
        media = media,
    )

    @Suppress("UNCHECKED_CAST")
    private fun failedConstructions(engine: CarPlayMediaEngine) =
        engine.javaClass.getDeclaredField("failedSessionRuntimeConstructions").run {
            isAccessible = true
            get(engine) as ConcurrentHashMap<AirPlaySession, *>
        }

    @Suppress("UNCHECKED_CAST")
    private fun publishedRuntimes(engine: CarPlayMediaEngine) =
        engine.javaClass.getDeclaredField("sessionDataRuntimes").run {
            isAccessible = true
            get(engine) as ConcurrentHashMap<AirPlaySession, *>
        }
}
