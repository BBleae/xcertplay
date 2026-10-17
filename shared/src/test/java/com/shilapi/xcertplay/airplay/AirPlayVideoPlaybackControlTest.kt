package com.shilapi.xcertplay.airplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayVideoPlaybackControlTest {
    @Test
    fun outboundBuildersEncodeOnlyConfirmedShapes() {
        assertEquals(
            mapOf(
                "type" to "setVideoPlaybackAllowed",
                "params" to mapOf(
                    "videoPlaybackAllowed" to false,
                    "playbackStatus" to "video",
                ),
            ),
            AirPlayVideoPlaybackCommandBuilder.setVideoPlaybackAllowed(false, "video"),
        )
        assertEquals(
            mapOf(
                "type" to "setVideoPlaybackAllowed",
                "params" to mapOf("videoPlaybackAllowed" to true),
            ),
            AirPlayVideoPlaybackCommandBuilder.setVideoPlaybackAllowed(true),
        )
        assertEquals(
            mapOf("type" to "videoPlayerBackButtonEvent"),
            AirPlayVideoPlaybackCommandBuilder.videoPlayerBackButtonEvent(),
        )
        assertEquals(
            mapOf(
                "type" to "videoPlayerBackButtonEvent",
                "params" to mapOf("playbackStatus" to "audio"),
            ),
            AirPlayVideoPlaybackCommandBuilder.videoPlayerBackButtonEvent("audio"),
        )
    }

    @Test
    fun playbackStatusIsOptionalAndBounded() {
        val maximum = "x".repeat(AirPlayVideoPlaybackCommandBuilder.MAX_PLAYBACK_STATUS_CHARS)
        AirPlayVideoPlaybackCommandBuilder.setVideoPlaybackAllowed(false, maximum)
        AirPlayVideoPlaybackCommandBuilder.videoPlayerBackButtonEvent(maximum)

        val oversized = maximum + "x"
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayVideoPlaybackCommandBuilder.setVideoPlaybackAllowed(false, oversized)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayVideoPlaybackCommandBuilder.videoPlayerBackButtonEvent(oversized)
        }
        listOf("", " ", "video\u0000", "video\n", "e\u0301").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                AirPlayVideoPlaybackCommandBuilder.videoPlayerBackButtonEvent(invalid)
            }
        }
    }

    @Test
    fun sidecarInitialValueAndSuccessfulSetControlDeduplication() {
        val fixture = fixture(initialAllowed = true)

        assertEquals(
            AirPlayVideoPlaybackSendResult.SENT,
            fixture.control.setVideoPlaybackAllowed(false, "video"),
        )
        assertEquals(
            AirPlayVideoPlaybackSendResult.UNCHANGED,
            fixture.control.setVideoPlaybackAllowed(false, "audio"),
        )
        assertEquals(
            AirPlayVideoPlaybackSendResult.SENT,
            fixture.control.setVideoPlaybackAllowed(true),
        )
        assertEquals(
            listOf(
                mapOf(
                    "type" to "setVideoPlaybackAllowed",
                    "params" to mapOf(
                        "videoPlaybackAllowed" to false,
                        "playbackStatus" to "video",
                    ),
                ),
                mapOf(
                    "type" to "setVideoPlaybackAllowed",
                    "params" to mapOf("videoPlaybackAllowed" to true),
                ),
            ),
            fixture.commands,
        )

        val initiallyDisallowed = fixture(initialAllowed = false)
        assertEquals(
            AirPlayVideoPlaybackSendResult.UNCHANGED,
            initiallyDisallowed.control.setVideoPlaybackAllowed(false),
        )
        assertTrue(initiallyDisallowed.commands.isEmpty())
    }

    @Test
    fun concurrentSetIsDeduplicatedWhileExternalSenderIsLockFree() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sends = AtomicInteger()
        val reentrantResult = AtomicReference<AirPlayVideoPlaybackSendResult>()
        lateinit var control: AirPlayVideoPlaybackControl
        val sender = AirPlayVideoPlaybackCommandSender {
            sends.incrementAndGet()
            reentrantResult.set(control.setVideoPlaybackAllowed(false))
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        control = openControl(initialAllowed = true, sender = sender)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<AirPlayVideoPlaybackSendResult> {
                control.setVideoPlaybackAllowed(false, "video")
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val concurrent = executor.submit<AirPlayVideoPlaybackSendResult> {
                control.setVideoPlaybackAllowed(false, "audio")
            }
            assertEquals(
                AirPlayVideoPlaybackSendResult.IN_FLIGHT,
                concurrent.get(2, TimeUnit.SECONDS),
            )
            assertEquals(AirPlayVideoPlaybackSendResult.IN_FLIGHT, reentrantResult.get())
            assertEquals(1, sends.get())
            release.countDown()
            assertEquals(AirPlayVideoPlaybackSendResult.SENT, first.get(2, TimeUnit.SECONDS))
            assertEquals(
                AirPlayVideoPlaybackSendResult.UNCHANGED,
                control.setVideoPlaybackAllowed(false),
            )
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedSetRollsBackSoTheSameStateCanBeRetried() {
        var sends = 0
        val control = openControl(
            initialAllowed = true,
            sender = AirPlayVideoPlaybackCommandSender {
                sends++
                if (sends == 1) throw IllegalStateException("temporary")
            },
        )

        assertThrows(IllegalStateException::class.java) {
            control.setVideoPlaybackAllowed(false)
        }
        assertEquals(AirPlayVideoPlaybackSendResult.SENT, control.setVideoPlaybackAllowed(false))
        assertEquals(2, sends)
    }

    @Test
    fun everyBackButtonInvocationIsSent() {
        val fixture = fixture(initialAllowed = true)

        repeat(2) {
            assertEquals(
                AirPlayVideoPlaybackSendResult.SENT,
                fixture.control.videoPlayerBackButtonEvent(),
            )
        }
        assertEquals(
            AirPlayVideoPlaybackSendResult.SENT,
            fixture.control.videoPlayerBackButtonEvent("video"),
        )
        assertEquals(
            listOf(
                mapOf("type" to "videoPlayerBackButtonEvent"),
                mapOf("type" to "videoPlayerBackButtonEvent"),
                mapOf(
                    "type" to "videoPlayerBackButtonEvent",
                    "params" to mapOf("playbackStatus" to "video"),
                ),
            ),
            fixture.commands,
        )
    }

    @Test
    fun inboundAudioOnlyCommandAcceptsAbsentOrEmptyParams() {
        val backendCalls = AtomicInteger()
        val control = openControl(
            initialAllowed = true,
            backend = AirPlayVideoPlaybackBackend { backendCalls.incrementAndGet() },
        )

        assertEquals(
            AirPlayVideoPlaybackCommandResult.HANDLED,
            control.handleCommand(mapOf("type" to "changeVideoPlaybackToAudioOnly")),
        )
        assertEquals(
            AirPlayVideoPlaybackCommandResult.HANDLED,
            control.handleCommand(
                mapOf(
                    "type" to "changeVideoPlaybackToAudioOnly",
                    "params" to emptyMap<String, Any?>(),
                ),
            ),
        )
        assertEquals(2, backendCalls.get())
    }

    @Test
    fun inboundParserRejectsOutboundDirectionsAndAnyUnconfirmedShape() {
        val backendCalls = AtomicInteger()
        val control = openControl(
            initialAllowed = true,
            backend = AirPlayVideoPlaybackBackend { backendCalls.incrementAndGet() },
        )
        val rejected = listOf<Any?>(
            null,
            "changeVideoPlaybackToAudioOnly",
            emptyMap<String, Any?>(),
            mapOf("type" to "setVideoPlaybackAllowed"),
            mapOf("type" to "videoPlayerBackButtonEvent"),
            mapOf("type" to "changeVideoPlaybackToAudioOnly", "params" to null),
            mapOf(
                "type" to "changeVideoPlaybackToAudioOnly",
                "params" to mapOf("playbackStatus" to "video"),
            ),
            mapOf("type" to "changeVideoPlaybackToAudioOnly", "extra" to true),
            mapOf<Any?, Any?>(1 to "changeVideoPlaybackToAudioOnly"),
        )

        rejected.forEach { value ->
            assertEquals(
                AirPlayVideoPlaybackCommandResult.REJECTED,
                control.handleCommand(value),
            )
        }
        assertEquals(0, backendCalls.get())
    }

    @Test
    fun descriptorFailsClosedForProbeAndBothFactoriesAndRestoresInterrupt() {
        var senderFactories = 0
        var backendFactories = 0
        val unavailable = descriptor(
            readinessProbe = {
                AirPlayVideoPlaybackBackendReadiness.unavailable("not provisioned")
            },
            senderFactory = {
                senderFactories++
                AirPlayVideoPlaybackCommandSender {}
            },
            backendFactory = {
                backendFactories++
                AirPlayVideoPlaybackBackend {}
            },
        )
        assertNull(AirPlayVideoPlaybackControl.open(unavailable, AirPlayVideoPlaybackInfo(true)))
        assertEquals(0, senderFactories)
        assertEquals(0, backendFactories)

        val senderFailure = descriptor(senderFactory = { error("sender") })
        assertNull(AirPlayVideoPlaybackControl.open(senderFailure, AirPlayVideoPlaybackInfo(true)))
        val backendFailure = descriptor(backendFactory = { error("backend") })
        assertNull(AirPlayVideoPlaybackControl.open(backendFailure, AirPlayVideoPlaybackInfo(true)))

        val interrupted = descriptor(
            readinessProbe = { throw InterruptedException("probe interrupted") },
        )
        assertNull(AirPlayVideoPlaybackControl.open(interrupted, AirPlayVideoPlaybackInfo(true)))
        assertTrue(Thread.currentThread().isInterrupted)
        assertTrue(Thread.interrupted())
    }

    private fun fixture(initialAllowed: Boolean): Fixture {
        val commands = java.util.Collections.synchronizedList(
            mutableListOf<Map<String, Any?>>(),
        )
        return Fixture(
            control = openControl(
                initialAllowed,
                sender = AirPlayVideoPlaybackCommandSender { commands.add(it) },
            ),
            commands = commands,
        )
    }

    private fun openControl(
        initialAllowed: Boolean,
        sender: AirPlayVideoPlaybackCommandSender = AirPlayVideoPlaybackCommandSender {},
        backend: AirPlayVideoPlaybackBackend = AirPlayVideoPlaybackBackend {},
    ): AirPlayVideoPlaybackControl = requireNotNull(
        AirPlayVideoPlaybackControl.open(
            descriptor(
                senderFactory = { sender },
                backendFactory = { backend },
            ),
            AirPlayVideoPlaybackInfo(initialAllowed),
        ),
    )

    private fun descriptor(
        readinessProbe: () -> AirPlayVideoPlaybackBackendReadiness = {
            AirPlayVideoPlaybackBackendReadiness.ready()
        },
        senderFactory: () -> AirPlayVideoPlaybackCommandSender = {
            AirPlayVideoPlaybackCommandSender {}
        },
        backendFactory: () -> AirPlayVideoPlaybackBackend = {
            AirPlayVideoPlaybackBackend {}
        },
    ) = AirPlayVideoPlaybackBackendDescriptor(
        implementationId = "test.video-playback",
        readinessProbe = readinessProbe,
        commandSenderFactory = senderFactory,
        backendFactory = backendFactory,
    )

    private data class Fixture(
        val control: AirPlayVideoPlaybackControl,
        val commands: List<Map<String, Any?>>,
    )
}
