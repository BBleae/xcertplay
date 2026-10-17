package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPreparedScreenBinding
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenCandidate
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import java.io.Closeable
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias ScreenLifecycle = ScreenStreamLifecycleStateMachine.Entry<
    CarPlayMediaEngine.StreamKey,
    Closeable,
    AirPlayVideoStreamHandle,
>

class CarPlayMediaEngineRendererLifecycleTest {
    @Test
    fun promotionPublishesGraphBeforeSinkActiveAndTeardownRetiresBeforeInactive() {
        val events = mutableListOf<String>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(handle: AirPlayVideoStreamHandle, active: Boolean) {
                events += if (active) "sink-active" else "sink-inactive"
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val session = testSession()
        val installed = install(engine, session, events)

        assertTrue(promote(engine, installed.lifecycle))
        assertEquals(listOf("candidate-publish", "sink-active"), events)

        engine.onTeardown(session, installed.key.identity)
        assertEquals(
            listOf(
                "candidate-publish",
                "sink-active",
                "transport-close",
                "candidate-close-1",
                "sink-inactive",
            ),
            events,
        )
        session.close()
    }

    @Test
    fun promotionFailureRetiresAlreadyPublishedCandidate() {
        val events = mutableListOf<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = testSession()
        val installed = install(engine, session, events)
        mutablePendingStreams(engine).remove(installed.key)

        assertFalse(promote(engine, installed.lifecycle))
        assertEquals(listOf("candidate-publish", "candidate-close-1"), events)

        engine.close()
        session.close()
    }

    @Test
    fun engineCloseRetriesSameBindingBeforeDeliveringInactive() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val sink = object : MediaSink {
            override fun onScreenStreamActive(handle: AirPlayVideoStreamHandle, active: Boolean) {
                events += if (active) "sink-active" else "sink-inactive"
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val session = testSession()
        val installed = install(engine, session, events, failFirstClose = true)
        assertTrue(promote(engine, installed.lifecycle))
        events.clear()

        assertThrows(IllegalStateException::class.java) { engine.close() }
        assertEquals(listOf("transport-close", "candidate-close-1"), events)

        engine.close()
        assertEquals(
            listOf(
                "transport-close",
                "candidate-close-1",
                "candidate-close-2",
                "sink-inactive",
            ),
            events,
        )
        session.close()
    }

    @Test
    fun throwingInactiveCallbackRetainsExactNotificationAndRetriesOnlyUnfinishedStep() {
        val events = mutableListOf<String>()
        val inactiveCalls = AtomicInteger()
        val sink = object : MediaSink {
            override fun onVideoRecoveryHandler(
                handle: AirPlayVideoStreamHandle,
                requestKeyFrame: (() -> Boolean)?,
            ) {
                if (requestKeyFrame == null) events += "recovery-clear"
            }

            override fun onScreenStreamActive(handle: AirPlayVideoStreamHandle, active: Boolean) {
                if (!active) {
                    val call = inactiveCalls.incrementAndGet()
                    events += "sink-inactive-$call"
                    if (call == 1) throw IllegalStateException("retry sink inactive")
                }
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val session = testSession()
        val installed = install(engine, session, events)
        assertTrue(promote(engine, installed.lifecycle))
        events.clear()

        assertThrows(IllegalStateException::class.java) { engine.close() }
        assertEquals(
            listOf(
                "transport-close",
                "candidate-close-1",
                "recovery-clear",
                "sink-inactive-1",
            ),
            events,
        )

        engine.close()
        assertEquals(
            listOf(
                "transport-close",
                "candidate-close-1",
                "recovery-clear",
                "sink-inactive-1",
                "sink-inactive-2",
            ),
            events,
        )
        engine.close()
        assertEquals(2, inactiveCalls.get())
        session.close()
    }

    @Test
    fun failedExplicitTeardownBlocksSameIdentityReplacementUntilRetried() {
        val events = mutableListOf<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(handle: AirPlayVideoStreamHandle, active: Boolean) {
                events += if (active) "sink-active" else "sink-inactive"
            }
        })
        val session = testSession()
        val installed = install(engine, session, events, failFirstClose = true)
        assertTrue(promote(engine, installed.lifecycle))

        assertThrows(IllegalStateException::class.java) {
            engine.onTeardown(session, installed.key.identity)
        }
        assertFalse(events.contains("sink-inactive"))

        val replacementEvents = mutableListOf<String>()
        val replacement = createLifecycle(engine, session, installed.key.identity, replacementEvents)
        assertFalse(installLifecycle(engine, replacement.lifecycle, replacement.binding))
        replacement.binding.close()

        engine.close()
        assertTrue(events.indexOf("candidate-close-2") < events.indexOf("sink-inactive"))
        session.close()
    }

    @Test
    fun wholeCloseStopsScreenTransportThenDataHandlerBeforeRendererIngress() {
        val events = mutableListOf<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(handle: AirPlayVideoStreamHandle, active: Boolean) {
                if (!active) events += "sink-inactive"
            }
        })
        val session = testSession()
        val installed = install(engine, session, events)
        assertTrue(promote(engine, installed.lifecycle))
        events.clear()
        mutableStreams(engine)[
            CarPlayMediaEngine.StreamKey(
                session,
                AirPlayStreamIdentity(
                    130,
                    "data-before-renderer",
                    com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
                        .CAR_PLAY_CLUSTER_CONTROL.uuid.toString(),
                ),
            )
        ] = Closeable { events += "data-close" }

        engine.close()

        assertEquals(
            listOf(
                "transport-close",
                "data-close",
                "candidate-close-1",
                "sink-inactive",
            ),
            events,
        )
        session.close()
    }

    @Test
    fun naturalListenerCloseStagesTransportBeforeNestedSessionRendererRetirement() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val ended = CountDownLatch(1)
        val endedBeforeInactive = AtomicBoolean(false)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onScreenStreamActive(handle: AirPlayVideoStreamHandle, active: Boolean) {
                events += if (active) "sink-active" else "sink-inactive"
            }
        })
        val session = testSession(media = engine, onEnded = {
            endedBeforeInactive.set("sink-inactive" !in events)
            ended.countDown()
        })
        val installed = install(engine, session, events)
        assertTrue(promote(engine, installed.lifecycle))
        events.clear()
        val listenerClose = CarPlayMediaEngine::class.java.declaredMethods.single {
            it.name == "closeScreenFromListener"
        }.apply { isAccessible = true }

        listenerClose.invoke(engine, installed.lifecycle)

        assertTrue(session.isClosed)
        assertEquals(
            listOf(
                "transport-close",
                "candidate-close-1",
                "sink-inactive",
            ),
            events,
        )
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        assertFalse("session end preceded staged screen retirement", endedBeforeInactive.get())
        engine.close()
    }

    private data class Installed(
        val key: CarPlayMediaEngine.StreamKey,
        val lifecycle: ScreenLifecycle,
        val binding: UiSyncPreparedScreenBinding,
    )

    private fun install(
        engine: CarPlayMediaEngine,
        session: AirPlaySession,
        events: MutableList<String>,
        failFirstClose: Boolean = false,
    ): Installed {
        val created = createLifecycle(
            engine,
            session,
            AirPlayStreamIdentity(111, "renderer-lifecycle", null),
            events,
            failFirstClose,
        )
        assertTrue(installLifecycle(engine, created.lifecycle, created.binding))
        return created
    }

    private fun createLifecycle(
        engine: CarPlayMediaEngine,
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        events: MutableList<String>,
        failFirstClose: Boolean = false,
    ): Installed {
        val key = CarPlayMediaEngine.StreamKey(session, identity)
        val handle = AirPlayVideoStreamHandle(identity, AirPlayVideoSessionEpoch())
        val lifecycle = screenState(engine).create(
            key,
            Closeable { events += "transport-close" },
            handle,
        )
        val delegate = RecordingCandidate(handle, events, failFirstClose)
        val binding = UiSyncPreparedScreenBinding(
            handle,
            delegate.ingressId,
            delegate,
            sessionLive = { true },
            onRetired = {},
        )
        return Installed(key, lifecycle, binding)
    }

    private fun installLifecycle(
        engine: CarPlayMediaEngine,
        lifecycle: ScreenLifecycle,
        binding: UiSyncPreparedScreenBinding,
    ): Boolean = CarPlayMediaEngine::class.java.declaredMethods.single {
        it.name == "installPendingScreenWithRenderer"
    }.run {
        isAccessible = true
        invoke(engine, lifecycle, binding) as Boolean
    }

    private fun promote(engine: CarPlayMediaEngine, lifecycle: ScreenLifecycle): Boolean =
        CarPlayMediaEngine::class.java.declaredMethods.single {
            it.name == "promotePendingScreen"
        }.run {
            isAccessible = true
            invoke(engine, lifecycle) as Boolean
        }

    @Suppress("UNCHECKED_CAST")
    private fun screenState(
        engine: CarPlayMediaEngine,
    ): ScreenStreamLifecycleStateMachine<
        CarPlayMediaEngine.StreamKey,
        Closeable,
        AirPlayVideoStreamHandle,
    > = CarPlayMediaEngine::class.java.getDeclaredField("screenLifecycles").run {
        isAccessible = true
        get(engine) as ScreenStreamLifecycleStateMachine<
            CarPlayMediaEngine.StreamKey,
            Closeable,
            AirPlayVideoStreamHandle
        >
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutablePendingStreams(
        engine: CarPlayMediaEngine,
    ): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("pendingStreams").run {
            isAccessible = true
            get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        }

    @Suppress("UNCHECKED_CAST")
    private fun mutableStreams(
        engine: CarPlayMediaEngine,
    ): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").run {
            isAccessible = true
            get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        }

    private class RecordingCandidate(
        override val stream: AirPlayVideoStreamHandle,
        private val events: MutableList<String>,
        private val failFirstClose: Boolean,
    ) : UiSyncRendererScreenCandidate {
        override val ingressId = UltraIngressId("renderer-ingress")
        private val closes = AtomicInteger()

        override fun publish(): Boolean {
            events += "candidate-publish"
            return true
        }

        override fun close() {
            val call = closes.incrementAndGet()
            events += "candidate-close-$call"
            if (failFirstClose && call == 1) throw IllegalStateException("retry retirement")
        }
    }

    private fun testSession(
        media: AirPlayMediaHandler = object : AirPlayMediaHandler {},
        onEnded: () -> Unit = {},
    ): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(800, 480),
            cluster = AirPlayDisplayConfig(800, 240),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {
            override fun onSessionEnded(session: AirPlaySession) = onEnded()
        },
        media = media,
    )
}
