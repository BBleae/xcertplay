package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.math.BigInteger
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaEngineTest {
    @Test
    fun setupDisplayUuidIsOnlyTrustedWhenItMatchesConfiguredInfoDisplay() {
        val main = mapOf("uuid" to AirPlayInfoPlist.MAIN_UUID)
        val cluster = mapOf("uuid" to AirPlayInfoPlist.ALT_UUID)
        assertEquals(AirPlayInfoPlist.MAIN_UUID, validatedSetupDisplayUuid(110, main, false))
        assertEquals(AirPlayInfoPlist.ALT_UUID, validatedSetupDisplayUuid(111, cluster, true))
        assertNull(validatedSetupDisplayUuid(111, cluster, false))
        assertNull(validatedSetupDisplayUuid(110, cluster, true))
        assertNull(validatedSetupDisplayUuid(111, main, true))
        assertNull(validatedSetupDisplayUuid(111, emptyMap(), true))
        assertNull(validatedSetupDisplayUuid(111, mapOf("uuid" to 4), true))
        assertNull(validatedSetupDisplayUuid(111,
            mapOf("uuid" to AirPlayInfoPlist.ALT_UUID.uppercase()), true))
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayVideoStreamHandle(
                AirPlayStreamIdentity(110, "1", null),
                AirPlayVideoSessionEpoch(),
                AirPlayInfoPlist.ALT_UUID,
            )
        }
    }

    @Test
    fun screenCloseReservedDuringPromotionPublishesActiveThenInactiveOutsideLifecycleLock() {
        val lifecycleLock = Any()
        val terminal = AtomicBoolean(false)
        val state = ScreenStreamLifecycleStateMachine<String, Any, Any>(lifecycleLock) {
            terminal.get()
        }
        val entry = state.create("main", Any(), Any())
        val events = Collections.synchronizedList(mutableListOf<String>())
        val activeEntered = CountDownLatch(1)
        val releaseActive = CountDownLatch(1)
        val closeReserved = CountDownLatch(1)

        assertTrue(state.install(entry) { true })
        val promotion = FutureTask<Unit> {
            val notification = state.promote(
                entry,
                promoteStateLocked = { true },
                notifyActive = {
                    assertFalse(Thread.holdsLock(lifecycleLock))
                    events += "active-enter"
                    activeEntered.countDown()
                    assertTrue(releaseActive.await(5, TimeUnit.SECONDS))
                    events += "active-exit"
                },
            ) ?: throw AssertionError("pending screen was not promoted")
            notification.run()
            Unit
        }
        Thread(promotion, "screen-promotion").start()

        assertTrue(activeEntered.await(5, TimeUnit.SECONDS))
        val closing = FutureTask<Unit> {
            val notification = state.close(
                entry,
                retainClosed = false,
                closeStateLocked = { terminal.set(true) },
                notifyInactive = {
                    assertFalse(Thread.holdsLock(lifecycleLock))
                    events += "inactive"
                },
            ) ?: throw AssertionError("active screen was not closed")
            // The CLOSED transition is complete while ACTIVE notification is deliberately held.
            closeReserved.countDown()
            notification.run()
            Unit
        }
        Thread(closing, "screen-close").start()

        assertTrue(closeReserved.await(5, TimeUnit.SECONDS))
        assertEquals(ScreenStreamLifecycleStateMachine.Phase.CLOSED, entry.phase)
        releaseActive.countDown()
        promotion.get(5, TimeUnit.SECONDS)
        closing.get(5, TimeUnit.SECONDS)

        assertEquals(listOf("active-enter", "active-exit", "inactive"), events)
    }

    @Test
    fun screenClosedBeforePromotionCanNeverBecomeActive() {
        val lifecycleLock = Any()
        val state = ScreenStreamLifecycleStateMachine<String, Any, Any>(lifecycleLock) { false }
        val entry = state.create("main", Any(), Any())
        val events = mutableListOf<String>()

        assertTrue(state.install(entry) { true })
        val closed = state.close(
            entry,
            retainClosed = true,
            closeStateLocked = {},
            notifyInactive = {
                assertFalse(Thread.holdsLock(lifecycleLock))
                events += "inactive"
            },
        ) ?: throw AssertionError("pending screen was not closed")

        val promoted = state.promote(
            entry,
            promoteStateLocked = { true },
            notifyActive = { events += "active" },
        )
        closed.run()

        assertEquals(null, promoted)
        assertEquals(listOf("inactive"), events)
    }

    @Test
    fun screenTransportCloseMarksSessionClosedBeforeBlockingInactiveCallbackCompletes() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val sink = object : MediaSink {
            override fun onScreenStreamActive(
                handle: AirPlayVideoStreamHandle,
                active: Boolean,
            ) {
                if (active) return
                events += "inactive-enter"
                callbackEntered.countDown()
                assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
                events += "inactive-exit"
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val session = testSession(media = engine)
        val identity = AirPlayStreamIdentity(110, "close-before-callback", null)
        val key = CarPlayMediaEngine.StreamKey(session, identity)
        val handle = AirPlayVideoStreamHandle(identity, AirPlayVideoSessionEpoch())
        val resource = Closeable {}

        @Suppress("UNCHECKED_CAST")
        val state = CarPlayMediaEngine::class.java.getDeclaredField("screenLifecycles").run {
            isAccessible = true
            get(engine) as ScreenStreamLifecycleStateMachine<
                CarPlayMediaEngine.StreamKey,
                Closeable,
                AirPlayVideoStreamHandle
            >
        }
        val lifecycle = state.create(key, resource, handle)
        val install = CarPlayMediaEngine::class.java.declaredMethods.single {
            it.name == "installPendingScreen"
        }.apply { isAccessible = true }
        assertEquals(true, install.invoke(engine, lifecycle))
        val closeFromListener = CarPlayMediaEngine::class.java.declaredMethods.single {
            it.name == "closeScreenFromListener"
        }.apply { isAccessible = true }

        val closing = FutureTask<Unit> {
            closeFromListener.invoke(engine, lifecycle)
            Unit
        }
        Thread(closing, "screen-listener-close").start()

        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        assertTrue("AirPlaySession must be terminal before renderer retirement", session.isClosed)
        assertFalse("blocking renderer callback should still be in flight", closing.isDone)
        releaseCallback.countDown()
        closing.get(5, TimeUnit.SECONDS)

        assertEquals(listOf("inactive-enter", "inactive-exit"), events)
    }

    @Test
    fun identityAwareMediaSinkDefaultsPreserveTheLegacyTypeContract() {
        val calls = mutableListOf<String>()
        val sink = object : MediaSink {
            override fun onVideoCodec(type: Int, codec: VideoCodec) {
                calls += "codec:$type:$codec"
            }

            override fun onVideoRecoveryHandler(type: Int, requestKeyFrame: (() -> Boolean)?) {
                calls += "recovery:$type:${requestKeyFrame != null}"
            }

            override fun onVideoConfig(type: Int, codecData: ByteArray) {
                calls += "config:$type:${codecData.single()}"
            }

            override fun onVideoFrame(
                type: Int,
                naluBytes: ByteArray,
                timing: ScreenFrameTiming,
                presentationTargetMonotonicNanos: Long?,
            ) {
                calls += "frame:$type:${naluBytes.single()}:${timing.arrivalMonotonicNanos}:" +
                    presentationTargetMonotonicNanos
            }

            override fun onScreenStreamActive(type: Int, active: Boolean) {
                calls += "active:$type:$active"
            }
        }
        val identity = AirPlayStreamIdentity(110, "17", "CLIENT")
        val handle = AirPlayVideoStreamHandle(identity, AirPlayVideoSessionEpoch())
        val timing = ScreenFrameTiming(null, 123L)

        sink.onVideoCodec(handle, VideoCodec.H265)
        sink.onVideoRecoveryHandler(handle) { true }
        sink.onVideoConfig(handle, byteArrayOf(4))
        sink.onVideoFrame(handle, byteArrayOf(5), timing, 456L)
        sink.onScreenStreamActive(handle, true)

        assertEquals(
            listOf(
                "codec:110:H265",
                "recovery:110:true",
                "config:110:4",
                "frame:110:5:123:456",
                "active:110:true",
            ),
            calls,
        )
    }

    @Test
    fun closeOwnsAndClosesTheSharedUltraRuntime() {
        val runtime = AirPlayUltraRuntime()
        val engine = CarPlayMediaEngine(object : MediaSink {}, ultraRuntime = runtime)

        engine.close()
        engine.close()

        assertThrows(IllegalStateException::class.java) {
            runtime.configuration(emptySet())
        }
    }

    @Test
    fun boundSessionKeepsExactSnapshotConfigAndEngineClosesOnlyItsChildRuntime() {
        val runtime = AirPlayUltraRuntime()
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val snapshotConfig = testConfig("snapshot").copy(
            ultra = runtime.configuration(emptySet()),
        )
        val snapshot = binding.resolve(generation, snapshotConfig, runtime)!!
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = testSession(
            media = engine,
            config = testConfig("fallback-must-not-win"),
            runtimeSnapshot = snapshot,
        )
        try {
            assertSame(snapshot, session.runtimeSnapshot)
            assertSame(snapshotConfig, session.resolvedConfig)
            assertTrue(engine.isRcsReady(session, emptyList()))

            engine.close()

            // The engine owns the per-session child, while the binding/lifecycle retains the root.
            runtime.configuration(emptySet())
        } finally {
            runCatching { session.close() }
            runCatching { engine.close() }
            runtime.close()
        }
    }

    @Test
    fun ordinaryResolvedSnapshotNeverFallsBackToLegacyConstructorRuntime() {
        val legacyRuntime = AirPlayUltraRuntime()
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val ordinaryConfig = testConfig("resolved-ordinary")
        val snapshot = binding.resolve(generation, ordinaryConfig, ultraRuntime = null)!!
        val engine = CarPlayMediaEngine(
            sink = object : MediaSink {},
            ultraRuntime = legacyRuntime,
        )
        val session = testSession(
            media = engine,
            config = testConfig("fallback-must-not-win"),
            runtimeSnapshot = snapshot,
        )
        try {
            assertSame(ordinaryConfig, session.resolvedConfig)
            assertFalse(engine.isRcsReady(session, emptyList()))

            engine.close()

            // Constructor runtime ownership remains the legacy contract.
            assertThrows(IllegalStateException::class.java) {
                legacyRuntime.configuration(emptySet())
            }
        } finally {
            runCatching { session.close() }
            runCatching { engine.close() }
            runCatching { legacyRuntime.close() }
        }
    }

    @Test
    fun videoFrameTimingPropagatesIndependentlyForMainAndAlternateScreens() {
        data class Received(
            val type: Int,
            val bytes: ByteArray,
            val timing: ScreenFrameTiming,
        )

        val received = mutableListOf<Received>()
        val sink = object : MediaSink {
            override fun onVideoFrame(
                type: Int,
                naluBytes: ByteArray,
                timing: ScreenFrameTiming,
            ) {
                received += Received(type, naluBytes, timing)
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val mainTiming = ScreenFrameTiming(0x123456789abcdef0L, 1_500_000L)
        val alternateTiming = ScreenFrameTiming(Long.MIN_VALUE, 2_500_000L)

        engine.forwardVideoFrame(110, byteArrayOf(0x11), mainTiming)
        engine.forwardVideoFrame(111, byteArrayOf(0x22), alternateTiming)

        assertEquals(listOf(110, 111), received.map(Received::type))
        assertTrue(received[0].bytes.contentEquals(byteArrayOf(0x11)))
        assertTrue(received[1].bytes.contentEquals(byteArrayOf(0x22)))
        assertEquals(mainTiming, received[0].timing)
        assertEquals(alternateTiming, received[1].timing)
    }

    @Test
    fun mappedPresentationTargetUsesTheTargetAwareSinkOverload() {
        var receivedTarget: Long? = null
        var receivedTiming: ScreenFrameTiming? = null
        val sink = object : MediaSink {
            override fun onVideoFrame(
                type: Int,
                naluBytes: ByteArray,
                timing: ScreenFrameTiming,
                presentationTargetMonotonicNanos: Long?,
            ) {
                receivedTiming = timing
                receivedTarget = presentationTargetMonotonicNanos
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val timing = ScreenFrameTiming(0x1234L, 5_000L)

        engine.forwardVideoFrame(111, byteArrayOf(7), timing, 50_000L)

        assertEquals(timing, receivedTiming)
        assertEquals(50_000L, receivedTarget)
    }

    @Test
    fun mappedPresentationTargetPreservesTheSessionBoundStreamHandle() {
        val received = mutableListOf<AirPlayVideoStreamHandle>()
        val sink = object : MediaSink {
            override fun onVideoFrame(
                handle: AirPlayVideoStreamHandle,
                naluBytes: ByteArray,
                timing: ScreenFrameTiming,
                presentationTargetMonotonicNanos: Long?,
            ) {
                received += handle
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val epoch = AirPlayVideoSessionEpoch()
        val first = AirPlayVideoStreamHandle(AirPlayStreamIdentity(110, "41", null), epoch)
        val second = AirPlayVideoStreamHandle(AirPlayStreamIdentity(110, "42", null), epoch)

        engine.forwardVideoFrame(first, byteArrayOf(1), ScreenFrameTiming(null, 1L), null)
        engine.forwardVideoFrame(second, byteArrayOf(2), ScreenFrameTiming(null, 2L), null)

        assertEquals(listOf(first, second), received)
    }

    @Test
    fun alternateScreenKeepsAuthenticatedWireIdentityOnItsExactSessionHandle() {
        var receivedHandle: AirPlayVideoStreamHandle? = null
        var receivedTiming: ScreenFrameTiming? = null
        val sink = object : MediaSink {
            override fun onVideoFrame(
                handle: AirPlayVideoStreamHandle,
                naluBytes: ByteArray,
                timing: ScreenFrameTiming,
                presentationTargetMonotonicNanos: Long?,
            ) {
                receivedHandle = handle
                receivedTiming = timing
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val handle = AirPlayVideoStreamHandle(
            AirPlayStreamIdentity(111, "cluster", null),
            AirPlayVideoSessionEpoch(),
        )
        val header = ByteArray(128).also {
            it[8] = 0x7f
            it[0x28] = 0x66
        }
        val timing = parseScreenFrameTiming(header, 12_000L, authenticatedFrameCounter = 5)

        engine.forwardVideoFrame(handle, byteArrayOf(1), timing, null)

        assertSame(handle, receivedHandle)
        assertSame(timing.authenticatedFrameIdentity, receivedTiming?.authenticatedFrameIdentity)
        assertEquals(5L, receivedTiming?.authenticatedFrameCounter)
        assertEquals(0x7fL, receivedTiming?.presentationTimeNtp64Bits)
        assertEquals(0x66L, receivedTiming?.timelineIdRawBits)
        assertEquals(0x66L,
            receivedTiming?.authenticatedFrameIdentity?.timelineIdRawBits)
    }

    @Test
    fun twoSessionsReusingTheSameProtocolIdentityHaveIndependentHandles() {
        val inactive = mutableListOf<AirPlayVideoStreamHandle>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(handle: AirPlayVideoStreamHandle, active: Boolean) {
                if (!active) inactive += handle
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val firstSession = testSession()
        val secondSession = testSession()
        val identity = AirPlayStreamIdentity(110, "73", null)
        val firstKey = CarPlayMediaEngine.StreamKey(firstSession, identity)
        val secondKey = CarPlayMediaEngine.StreamKey(secondSession, identity)
        val firstHandle = AirPlayVideoStreamHandle(identity, AirPlayVideoSessionEpoch())
        val secondHandle = AirPlayVideoStreamHandle(identity, AirPlayVideoSessionEpoch())
        val streams = mutableStreams(engine)
        val handles = mutableVideoHandles(engine)
        streams[firstKey] = Closeable {}
        streams[secondKey] = Closeable {}
        handles[firstKey] = firstHandle
        handles[secondKey] = secondHandle

        engine.onTeardown(firstSession, identity)

        assertEquals(listOf(firstHandle), inactive)
        assertFalse(firstKey in streams)
        assertFalse(firstKey in handles)
        assertTrue(secondKey in streams)
        assertEquals(secondHandle, handles[secondKey])
        assertFalse(firstHandle === secondHandle)
        assertFalse(firstHandle.toString().contains("73"))
        assertFalse(firstHandle.toString().contains("CLIENT"))

        engine.onSessionClosed(secondSession)
        assertEquals(listOf(firstHandle, secondHandle), inactive)
        firstSession.close()
        secondSession.close()
    }

    @Test
    fun exactScreenTeardownRetryDoesNotConsumeAnotherPendingScreen() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val session = testSession()
        val main = AirPlayStreamIdentity(110, "main", null)
        val other = AirPlayStreamIdentity(111, "cluster", null)
        val mainKey = CarPlayMediaEngine.StreamKey(session, main)
        val otherKey = CarPlayMediaEngine.StreamKey(session, other)
        val mainAllowed = AtomicBoolean(false)
        val otherAllowed = AtomicBoolean(false)
        val mainCloses = AtomicInteger()
        val otherCloses = AtomicInteger()
        mutableStreams(engine)[mainKey] = Closeable {
            mainCloses.incrementAndGet()
            if (!mainAllowed.get()) throw IOException("MAIN decoder pending")
        }
        mutableStreams(engine)[otherKey] = Closeable {
            otherCloses.incrementAndGet()
            if (!otherAllowed.get()) throw IOException("CLUSTER decoder pending")
        }
        mutableVideoHandles(engine)[mainKey] =
            AirPlayVideoStreamHandle(main, AirPlayVideoSessionEpoch())
        mutableVideoHandles(engine)[otherKey] =
            AirPlayVideoStreamHandle(other, AirPlayVideoSessionEpoch())

        assertThrows(IOException::class.java) { engine.onTeardown(session, main) }
        assertThrows(IOException::class.java) { engine.onTeardown(session, other) }
        mainAllowed.set(true)
        engine.onTeardown(session, main)
        assertEquals(2, mainCloses.get())
        assertEquals(1, otherCloses.get())
        otherAllowed.set(true)
        engine.onTeardown(session, other)
        assertEquals(2, otherCloses.get())
        engine.close()
        session.close()
    }

    @Test
    fun streamConnectionIdUsesUnsignedDecimalForHkdfSalt() {
        assertEquals("18446744073709551615", unsignedPlistDecimal(-1L))
        assertEquals(
            BigInteger("18446744073709551615"),
            unsignedPlistInteger(-1L),
        )
    }

    @Test
    fun screenStreamTeardownReportsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val clearedRecovery = mutableListOf<Int>()
        val sink = object : MediaSink {
            override fun onVideoRecoveryHandler(type: Int, requestKeyFrame: (() -> Boolean)?) {
                if (requestKeyFrame == null) clearedRecovery += type
            }
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()

        try {
            val engine = CarPlayMediaEngine(sink)
            engine.onTeardown(session, 110)
            engine.onTeardown(session, 100)
        } finally {
            session.close()
        }

        assertEquals(listOf(110 to false), events)
        assertEquals(listOf(110), clearedRecovery)
    }

    @Test
    fun sessionCloseReportsAllScreenStreamsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        val mainKey = CarPlayMediaEngine.StreamKey(session, 110)
        val alternateKey = CarPlayMediaEngine.StreamKey(session, 111)
        streams[mainKey] = Closeable {}
        streams[alternateKey] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100)] = Closeable {}
        mutableVideoHandles(engine)[mainKey] = AirPlayVideoStreamHandle(
            mainKey.identity,
            AirPlayVideoSessionEpoch(),
        )
        mutableVideoHandles(engine)[alternateKey] = AirPlayVideoStreamHandle(
            alternateKey.identity,
            AirPlayVideoSessionEpoch(),
        )

        engine.onSessionClosed(session)
        session.close()

        assertEquals(setOf(110 to false, 111 to false), events.toSet())
        assertTrue(streams.isEmpty())
    }

    @Test
    fun teardownOfOneSameTypeStreamDoesNotCollapseTheOtherIdentity() {
        val inactive = mutableListOf<AirPlayStreamIdentity>()
        val clearedRecovery = mutableListOf<AirPlayStreamIdentity>()
        val sink = object : MediaSink {
            override fun onVideoRecoveryHandler(
                identity: AirPlayStreamIdentity,
                requestKeyFrame: (() -> Boolean)?,
            ) {
                if (requestKeyFrame == null) clearedRecovery += identity
            }

            override fun onScreenStreamActive(identity: AirPlayStreamIdentity, active: Boolean) {
                if (!active) inactive += identity
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val first = AirPlayStreamIdentity(110, "51", null)
        val second = AirPlayStreamIdentity(110, "52", null)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, first)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, second)] = Closeable {}
        mutableVideoHandles(engine)[CarPlayMediaEngine.StreamKey(session, first)] =
            AirPlayVideoStreamHandle(first, AirPlayVideoSessionEpoch())
        mutableVideoHandles(engine)[CarPlayMediaEngine.StreamKey(session, second)] =
            AirPlayVideoStreamHandle(second, AirPlayVideoSessionEpoch())

        engine.onTeardown(session, first)

        assertEquals(listOf(first), inactive)
        assertEquals(listOf(first), clearedRecovery)
        assertTrue(CarPlayMediaEngine.StreamKey(session, second) in streams)
        engine.close()
        session.close()
    }

    @Test
    fun sessionCloseReportsEverySameTypeIdentityIndependently() {
        val inactive = mutableListOf<AirPlayStreamIdentity>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(identity: AirPlayStreamIdentity, active: Boolean) {
                if (!active) inactive += identity
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val first = AirPlayStreamIdentity(110, "61", null)
        val second = AirPlayStreamIdentity(110, "62", null)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        val firstKey = CarPlayMediaEngine.StreamKey(session, first)
        val secondKey = CarPlayMediaEngine.StreamKey(session, second)
        streams[firstKey] = Closeable {}
        streams[secondKey] = Closeable {}
        mutableVideoHandles(engine)[firstKey] =
            AirPlayVideoStreamHandle(first, AirPlayVideoSessionEpoch())
        mutableVideoHandles(engine)[secondKey] =
            AirPlayVideoStreamHandle(second, AirPlayVideoSessionEpoch())

        engine.onSessionClosed(session)

        assertEquals(setOf(first, second), inactive.toSet())
        assertEquals(2, inactive.size)
        session.close()
    }

    @Test
    fun microphoneFollowsThePhonesInputPortForAnyMainAudioCategory() {
        val requested = mapOf("audioType" to "default", "dataPort" to 12345)
        assertEquals(12345, requestedMicrophonePort(true, 100, requested))
        assertEquals(
            12345,
            requestedMicrophonePort(true, 100, mapOf("audioType" to "compatibility", "dataPort" to 12345)),
        )
        assertEquals(null, requestedMicrophonePort(false, 100, requested))
        assertEquals(null, requestedMicrophonePort(true, 101, requested))
        assertEquals(null, requestedMicrophonePort(true, 100, mapOf("dataPort" to 0)))
        assertEquals(null, requestedMicrophonePort(true, 100, mapOf("dataPort" to 65536)))
        assertEquals(null, requestedMicrophonePort(true, 100, emptyMap()))
    }

    @Test
    fun opusMicrophoneRateFollowsTheSelectedFormatBit() {
        assertEquals(16_000, AudioStreamCodec.opusCaptureRate(0x10000000L))
        assertEquals(24_000, AudioStreamCodec.opusCaptureRate(0x20000000L))
        assertEquals(48_000, AudioStreamCodec.opusCaptureRate(0x40000000L))
    }

    @Test
    fun legacySetupCallbackDoesNotCrossAttachMicrophoneFromAnotherTransaction() {
        val events = mutableListOf<String>()
        val sink = object : MediaSink {
            override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
                events += "start:$type:${config.audioType}"
            }

            override fun onMicrophoneStopped(type: Int) {
                events += "stop:$type"
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)

        engine.onSetupResponseSent(session)
        engine.onSetupResponseSent(session)
        assertTrue(events.isEmpty())

        engine.onTeardown(session, 100)
        session.close()
        assertTrue(events.isEmpty())
    }

    @Test
    fun sessionCloseStopsAnActiveMicrophone() {
        val events = mutableListOf<String>()
        val sink = object : MediaSink {
            override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
                events += "start"
            }

            override fun onMicrophoneStopped(type: Int) {
                events += "stop"
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val startedField = CarPlayMediaEngine::class.java.getDeclaredField("startedMicrophone").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val started = startedField.get(engine) as MutableSet<CarPlayMediaEngine.StreamKey>
        val key = CarPlayMediaEngine.StreamKey(session, 100)
        started += key
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[key] = Closeable {}

        engine.onSessionClosed(session)
        session.close()

        assertEquals(listOf("stop"), events)
        assertTrue(started.isEmpty())
    }

    @Test
    fun terminalSessionCannotCreateOrReinsertADataRuntime() {
        val engine = CarPlayMediaEngine(
            object : MediaSink {},
            ultraRuntime = AirPlayUltraRuntime(),
        )
        val session = testSession()

        session.close()

        assertFalse(
            engine.isRcsReady(
                session,
                listOf(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
            ),
        )
        val runtimesField = CarPlayMediaEngine::class.java
            .getDeclaredField("sessionDataRuntimes")
            .apply { isAccessible = true }
        assertTrue((runtimesField.get(engine) as Map<*, *>).isEmpty())
    }

    private fun testSession(
        media: AirPlayMediaHandler = object : AirPlayMediaHandler {},
        config: AirPlayConfig = testConfig("test"),
        runtimeSnapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot? = null,
    ): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = config,
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = media,
        runtimeSnapshot = runtimeSnapshot,
    )

    private fun testConfig(name: String): AirPlayConfig = AirPlayConfig(
        deviceName = name,
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "1.0",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
    )

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
}
