package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaEngineOwnedResourceTest {
    @Test
    fun dataResourceFailurePreventsUltraCloseAndSecondEngineCloseRetries() {
        assertRetryableOwnedResource(type = 130, expectedAudioStops = 0)
    }

    @Test
    fun audioResourceFailureDefersAudioStopAndUltraCloseUntilRetry() {
        assertRetryableOwnedResource(type = 100, expectedAudioStops = 1)
    }

    @Test
    fun wholeCloseRetainsLegacyHandleUntilBothSinkCallbacksSucceed() {
        val recoveryClears = AtomicInteger()
        val inactiveCalls = AtomicInteger()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onVideoRecoveryHandler(
                handle: AirPlayVideoStreamHandle,
                requestKeyFrame: (() -> Boolean)?,
            ) {
                if (requestKeyFrame == null) recoveryClears.incrementAndGet()
            }

            override fun onScreenStreamActive(handle: AirPlayVideoStreamHandle, active: Boolean) {
                if (!active && inactiveCalls.incrementAndGet() == 1) {
                    throw IllegalStateException("retry inactive")
                }
            }
        })
        val session = testSession()
        val key = CarPlayMediaEngine.StreamKey(session, 110)
        mutableStreams(engine)[key] = Closeable {}
        mutableVideoHandles(engine)[key] = AirPlayVideoStreamHandle(
            key.identity,
            AirPlayVideoSessionEpoch(),
        )

        assertThrows(IllegalStateException::class.java) { engine.close() }
        assertEquals(1, recoveryClears.get())
        assertEquals(1, inactiveCalls.get())

        engine.close()
        engine.close()
        assertEquals(1, recoveryClears.get())
        assertEquals(2, inactiveCalls.get())
        session.close()
    }

    @Test
    fun wholeCloseRetainsAudioStopOwnerUntilSinkCallbackSucceeds() {
        val resourceCloses = AtomicInteger()
        val audioStops = AtomicInteger()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(type: Int) {
                if (audioStops.incrementAndGet() == 1) {
                    throw IllegalStateException("retry audio stop")
                }
            }
        })
        val session = testSession()
        mutableStreams(engine)[CarPlayMediaEngine.StreamKey(session, 100)] =
            Closeable { resourceCloses.incrementAndGet() }

        assertThrows(IllegalStateException::class.java) { engine.close() }
        assertEquals(1, resourceCloses.get())
        assertEquals(1, audioStops.get())

        engine.close()
        engine.close()
        assertEquals(1, resourceCloses.get())
        assertEquals(2, audioStops.get())
        session.close()
    }

    @Test
    fun wholeCloseRetainsExactMicrophoneStopOwnerUntilSinkCallbackSucceeds() {
        val microphoneStops = AtomicInteger()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onMicrophoneStopped(type: Int) {
                if (microphoneStops.incrementAndGet() == 1) {
                    throw IllegalStateException("retry microphone stop")
                }
            }
        })
        val session = testSession()
        val key = CarPlayMediaEngine.StreamKey(session, 100)
        mutableStartedMicrophones(engine) += key

        assertThrows(IllegalStateException::class.java) { engine.close() }
        assertTrue(key in mutableStartedMicrophones(engine))
        assertEquals(1, microphoneStops.get())

        engine.close()
        engine.close()
        assertTrue(key !in mutableStartedMicrophones(engine))
        assertEquals(2, microphoneStops.get())
        session.close()
    }

    @Test
    fun reentrantTeardownCannotDeliverTheSameExactMicrophoneStopTwice() {
        val microphoneStops = AtomicInteger()
        lateinit var engine: CarPlayMediaEngine
        lateinit var session: AirPlaySession
        val identity = AirPlayStreamIdentity(100, "reentrant-microphone", null)
        engine = CarPlayMediaEngine(object : MediaSink {
            override fun onMicrophoneStopped(type: Int) {
                microphoneStops.incrementAndGet()
                engine.onTeardown(session, identity)
            }
        })
        session = testSession()
        val key = CarPlayMediaEngine.StreamKey(session, identity)
        mutableStartedMicrophones(engine) += key

        engine.onTeardown(session, identity)

        assertEquals(1, microphoneStops.get())
        assertTrue(key !in mutableStartedMicrophones(engine))
        engine.close()
        session.close()
    }

    @Test
    fun closeCannotCompleteWhileSessionRetirementAdmissionIsStillInFlight() {
        val runtime = AirPlayUltraRuntime()
        val enteredClose = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val resourceCloses = AtomicInteger()
        val engine = CarPlayMediaEngine(object : MediaSink {}, ultraRuntime = runtime)
        val session = testSession()
        mutableStreams(engine)[CarPlayMediaEngine.StreamKey(session, 130)] = Closeable {
            resourceCloses.incrementAndGet()
            enteredClose.countDown()
            assertTrue(releaseClose.await(5, TimeUnit.SECONDS))
        }
        val callbackFailure = AtomicReference<Throwable?>()
        val callback = Thread {
            callbackFailure.set(runCatching { engine.onSessionClosed(session) }.exceptionOrNull())
        }.apply { start() }
        assertTrue(enteredClose.await(5, TimeUnit.SECONDS))

        assertThrows(IllegalStateException::class.java) { engine.close() }
        // The failed close attempt must not release shared runtime ownership early.
        runtime.configuration(emptySet())

        releaseClose.countDown()
        callback.join(5_000)
        assertTrue(!callback.isAlive)
        assertEquals(null, callbackFailure.get())
        assertEquals(1, resourceCloses.get())

        engine.close()
        assertThrows(IllegalStateException::class.java) { runtime.configuration(emptySet()) }
        session.close()
    }

    @Test
    fun closeCannotCompleteBeforeLateRejectedPrepareCleanupIsRegistered() {
        val runtime = AirPlayUltraRuntime()
        val engine = CarPlayMediaEngine(object : MediaSink {}, ultraRuntime = runtime)
        val session = testSession()
        val key = CarPlayMediaEngine.StreamKey(session, 111)
        val cleanup = FailingResource()
        val admissionEntered = CountDownLatch(1)
        val allowLateRegistration = CountDownLatch(1)
        val prepareFailure = AtomicReference<Throwable?>()
        val beginAdmission = CarPlayMediaEngine::class.java.declaredMethods.single {
            it.name == "beginOwnerOperation"
        }.apply { isAccessible = true }
        val endAdmission = CarPlayMediaEngine::class.java.declaredMethods.single {
            it.name == "endOwnerOperation"
        }.apply { isAccessible = true }
        val stageCleanup = CarPlayMediaEngine::class.java.declaredMethods.single {
            it.name == "stageAdditionalOwnedResourceRetirement"
        }.apply { isAccessible = true }
        val prepare = Thread {
            prepareFailure.set(
                runCatching {
                    assertEquals(true, beginAdmission.invoke(engine))
                    try {
                        admissionEntered.countDown()
                        assertTrue(allowLateRegistration.await(5, TimeUnit.SECONDS))
                        stageCleanup.invoke(engine, key, listOf(cleanup))
                    } finally {
                        endAdmission.invoke(engine)
                    }
                }.exceptionOrNull(),
            )
        }.apply { start() }
        assertTrue(admissionEntered.await(5, TimeUnit.SECONDS))

        assertThrows(IllegalStateException::class.java) { engine.close() }
        runtime.configuration(emptySet())
        allowLateRegistration.countDown()
        prepare.join(5_000)
        assertTrue(!prepare.isAlive)
        assertEquals(null, prepareFailure.get())

        assertThrows(IllegalStateException::class.java) { engine.close() }
        assertEquals(1, cleanup.closeCalls.get())
        runtime.configuration(emptySet())

        engine.close()
        assertEquals(2, cleanup.closeCalls.get())
        assertThrows(IllegalStateException::class.java) { runtime.configuration(emptySet()) }
        session.close()
    }

    @Test
    fun rejectedInstallCleanupDoesNotDetachTheExistingExactKeyStream() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = testSession()
        val key = CarPlayMediaEngine.StreamKey(session, 111)
        val existingCloses = AtomicInteger()
        val rejectedScreenCloses = AtomicInteger()
        val rejectedBindingCloses = AtomicInteger()
        val existing = Closeable { existingCloses.incrementAndGet() }
        mutableStreams(engine)[key] = existing

        CarPlayMediaEngine::class.java.declaredMethods.single {
            it.name == "stageAdditionalOwnedResourceRetirement"
        }.run {
            isAccessible = true
            invoke(
                engine,
                key,
                listOf(
                    Closeable { rejectedScreenCloses.incrementAndGet() },
                    Closeable { rejectedBindingCloses.incrementAndGet() },
                ),
            )
        }
        CarPlayMediaEngine::class.java.declaredMethods.single {
            it.name == "retireOwnedResources"
        }.run {
            isAccessible = true
            assertEquals(null, invoke(engine, session))
        }

        assertTrue(mutableStreams(engine)[key] === existing)
        assertEquals(0, existingCloses.get())
        assertEquals(1, rejectedScreenCloses.get())
        assertEquals(1, rejectedBindingCloses.get())

        engine.close()
        assertEquals(1, existingCloses.get())
        session.close()
    }

    @Test
    fun duplicateAudioPrepareClosesOnlyRejectedListenerAndKeepsExistingExactKeyOwner() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = testSession().also(::installSharedSecret)
        val identity = AirPlayStreamIdentity(100, "duplicate-audio", null)
        val key = CarPlayMediaEngine.StreamKey(session, identity)
        val existingCloses = AtomicInteger()
        val existing = Closeable { existingCloses.incrementAndGet() }
        mutableStreams(engine)[key] = existing

        val rejected = engine.prepareAudio(
            session,
            identity,
            mapOf(
                "streamConnectionID" to 41L,
                "audioFormat" to 0L,
                "audioType" to "default",
            ),
        )

        assertEquals(null, rejected)
        assertTrue(mutableStreams(engine)[key] === existing)
        assertEquals(0, existingCloses.get())

        engine.close()
        assertEquals(1, existingCloses.get())
        session.close()
    }

    private fun assertRetryableOwnedResource(type: Int, expectedAudioStops: Int) {
        val runtime = AirPlayUltraRuntime()
        val audioStops = AtomicInteger()
        val engine = CarPlayMediaEngine(
            sink = object : MediaSink {
                override fun onAudioStopped(type: Int) {
                    audioStops.incrementAndGet()
                }
            },
            ultraRuntime = runtime,
        )
        val session = testSession()
        val resource = FailingResource()
        mutableStreams(engine)[CarPlayMediaEngine.StreamKey(session, type)] = resource

        assertThrows(IllegalStateException::class.java) { engine.close() }
        assertEquals(1, resource.closeCalls.get())
        assertEquals(0, audioStops.get())
        // Shared Ultra ownership must remain live while any transport retirement is unproven.
        runtime.configuration(emptySet())

        engine.close()
        assertEquals(2, resource.closeCalls.get())
        assertEquals(expectedAudioStops, audioStops.get())
        assertThrows(IllegalStateException::class.java) { runtime.configuration(emptySet()) }
        session.close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableStreams(
        engine: CarPlayMediaEngine,
    ): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").run {
            isAccessible = true
            get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        }

    @Suppress("UNCHECKED_CAST")
    private fun mutableVideoHandles(
        engine: CarPlayMediaEngine,
    ): MutableMap<CarPlayMediaEngine.StreamKey, AirPlayVideoStreamHandle> =
        CarPlayMediaEngine::class.java.getDeclaredField("videoHandles").run {
            isAccessible = true
            get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, AirPlayVideoStreamHandle>
        }

    @Suppress("UNCHECKED_CAST")
    private fun mutableStartedMicrophones(
        engine: CarPlayMediaEngine,
    ): MutableSet<CarPlayMediaEngine.StreamKey> =
        CarPlayMediaEngine::class.java.getDeclaredField("startedMicrophone").run {
            isAccessible = true
            get(engine) as MutableSet<CarPlayMediaEngine.StreamKey>
        }

    private class FailingResource : Closeable {
        val closeCalls = AtomicInteger()
        override fun close() {
            if (closeCalls.incrementAndGet() == 1) {
                throw IllegalStateException("retry resource close")
            }
        }
    }

    private fun testSession(): AirPlaySession = AirPlaySession(
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
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )

    private fun installSharedSecret(session: AirPlaySession) {
        val pairVerify = AirPlaySession::class.java.getDeclaredField("pairVerify").run {
            isAccessible = true
            get(session)
        }
        pairVerify.javaClass.getDeclaredField("sharedSecret").run {
            isAccessible = true
            set(pairVerify, ByteArray(32) { 7 })
        }
    }
}
