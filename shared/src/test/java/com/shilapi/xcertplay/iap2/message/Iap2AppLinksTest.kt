package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Iap2AppLinksTest {
    @Test
    fun identificationClaimsOnlyImplementedAppLinksSubsetWhenOptedIn() {
        for (wireless in listOf(false, true)) {
            val base = identification(wireless)
            val disabled = Iap2BodyReader.of(Iap2IdentificationClient.identificationInformation(base))
            val enabled = Iap2BodyReader.of(Iap2IdentificationClient.identificationInformation(base.copy(appLinksEnabled = true)))
            assertFalse(disabled.u16List(6).contains(Iap2AppLinksMessages.START_ID))
            assertFalse(disabled.u16List(7).contains(Iap2AppLinksMessages.UPDATE_ID))
            assertTrue(enabled.u16List(6).containsAll(listOf(Iap2AppLinksMessages.START_ID, Iap2AppLinksMessages.STOP_ID)))
            assertTrue(enabled.u16List(7).contains(Iap2AppLinksMessages.UPDATE_ID))
            assertFalse(enabled.u16List(6).contains(0xad03))
            assertFalse(enabled.u16List(7).contains(0xad04))
        }
    }

    @Test
    fun startUsesConfirmedCategoryGroupAndOptionalIconSizeWithoutUnknownRootOne() {
        val frame = Iap2AppLinksMessages.start(setOf(12, 1, 5), preferredIconSize = 128)
        val root = Iap2BodyReader.of(frame)
        assertEquals(Iap2AppLinksMessages.START_ID, frame.messageId)
        assertEquals(listOf(0, 2), root.list().map { it.id })
        assertEquals(listOf(1, 5, 12), root.group(0).list().map { it.id })
        root.group(0).list().forEach { assertTrue(it.payload.isEmpty()) }
        assertEquals(128, root.u16(2))
        assertNull(Iap2BodyReader.of(Iap2AppLinksMessages.start(setOf(3))).optionalU16(2))
        assertTrue(Iap2AppLinksMessages.stop().payload.isEmpty())
    }

    @Test
    fun startRejectsEmptyUnknownAndOversizeCategoriesOrIcon() {
        listOf(emptySet(), setOf(0), setOf(6), setOf(13), setOf(65_535)).forEach { categories ->
            expectIllegalArgument { Iap2AppLinksMessages.start(categories) }
        }
        expectIllegalArgument { Iap2AppLinksMessages.start(setOf(1), 0) }
        expectIllegalArgument { Iap2AppLinksMessages.start(setOf(1), 1_025) }
    }

    @Test
    fun updateParsesKnownEntryFieldsAndBoundsUnknownOptionalFields() {
        val frame = updateFrame {
            u8(0, 2)
            group(1) {
                u16(0, 7)
                string(1, "com.example.navigation")
                string(2, "Map")
                u16(3, 3)
                bytes(99, byteArrayOf(1, 2, 3))
            }
            group(1) {
                u16(0, 8)
                string(1, "com.example.future")
                string(2, "Future")
                u16(3, 42)
            }
            u16(2, 20)
            bytes(90, byteArrayOf(4))
        }
        val update = Iap2AppLinksMessages.update(frame)
        assertEquals(2, update.state)
        assertEquals(20, update.reportedCount)
        assertEquals(2, update.entries.size)
        assertEquals(Iap2AppLinkEntry(7, "com.example.navigation", "Map", 3), update.entries[0])
        assertEquals(42, update.entries[1].categoryId)
    }

    @Test
    fun updateParsesOptionalSha256_16IconHashWithoutExposingMutableBytes() {
        val original = ByteArray(16) { it.toByte() }
        val frame = updateFrame {
            u8(0, 2)
            group(1) {
                u16(0, 7)
                string(1, "com.example.navigation")
                string(2, "Map")
                u16(3, 3)
                bytes(4, original)
            }
        }
        val hash = requireNotNull(Iap2AppLinksMessages.update(frame).entries.single().iconHash)
        original[0] = 99
        assertEquals(0, hash.bytes()[0].toInt())
        hash.bytes()[1] = 99
        assertEquals(1, hash.bytes()[1].toInt())
        assertEquals(hash, Iap2AppLinksIconHash.of(ByteArray(16) { it.toByte() }))
        assertFalse(hash.toString().contains("010203"))

        expectMalformed(updateFrame {
            u8(0, 2)
            group(1) {
                u16(0, 7)
                string(1, "com.example.navigation")
                string(2, "Map")
                u16(3, 3)
                bytes(4, ByteArray(15))
            }
        })
        expectMalformed(updateFrame {
            u8(0, 2)
            group(1) {
                u16(0, 7)
                string(1, "com.example.navigation")
                string(2, "Map")
                u16(3, 3)
                bytes(4, ByteArray(16))
                bytes(4, ByteArray(16))
            }
        })
    }

    @Test
    fun iconRequestEncodesSeparateCarPlayAndExternalAccessorySubfeatures() {
        val frame = Iap2AppLinksMessages.requestIcons(
            carPlayBundleIdentifiers = listOf("com.example.maps", "com.example.audio"),
            externalAccessoryBundleIdentifiers = listOf("com.example.accessory"),
        )
        assertEquals(Iap2AppLinksMessages.ICON_REQUEST_ID, frame.messageId)
        assertEquals(listOf(0, 0, 1), Iap2BodyReader.of(frame).list().map { it.id })
        assertEquals(
            Iap2AppLinksIconRequest(
                listOf("com.example.maps", "com.example.audio"),
                listOf("com.example.accessory"),
            ),
            Iap2AppLinksMessages.iconRequest(frame),
        )
        assertFalse(Iap2AppLinksMessages.iconRequest(frame).toString().contains("com.example.maps"))
    }

    @Test
    fun iconRequestRejectsEmptyDuplicateMalformedUnknownAndExcessiveFields() {
        expectIllegalArgument { Iap2AppLinksMessages.requestIcons(emptyList()) }
        expectIllegalArgument {
            Iap2AppLinksMessages.requestIcons(listOf("com.example.same", "com.example.same"))
        }
        expectIllegalArgument { Iap2AppLinksMessages.requestIcons(listOf("x".repeat(257))) }
        expectIllegalArgument { Iap2AppLinksMessages.requestIcons(listOf("com.example.\u202eunsafe")) }
        expectIllegalArgument {
            Iap2AppLinksMessages.requestIcons(List(Iap2AppLinksMessages.MAX_ICON_REQUEST_ENTRIES + 1) {
                "com.example.app$it"
            })
        }
        expectMalformedIconRequest(Iap2Messages.buildRaw(0xad03) {})
        expectMalformedIconRequest(Iap2Messages.buildRaw(0xad03) {
            string(0, "com.example.same")
            string(0, "com.example.same")
        })
        expectMalformedIconRequest(Iap2Messages.buildRaw(0xad03) { string(2, "com.example.unknown") })
        expectMalformedIconRequest(Iap2Messages.buildRaw(0xad03) { bytes(0, byteArrayOf(0xc3.toByte(), 0x28, 0)) })
        expectMalformedIconRequest(Iap2Frame(0xad03, byteArrayOf(0, 5, 0)))
        expectMalformedIconRequest(Iap2Messages.buildRaw(0xad03) {
            repeat(Iap2AppLinksMessages.MAX_ICON_REQUEST_ENTRIES + 1) {
                string(0, "com.example.app$it")
            }
        })
    }

    @Test
    fun iconResponseRequiresStatusConditionedTransferIdAndHash() {
        val absent = Iap2AppLinksMessages.iconResponse(iconResponseFrame {
            string(0, "com.example.maps")
            u8(1, 0)
        })
        assertEquals(0, absent.status)
        assertNull(absent.fileTransferBufferId)
        assertNull(absent.iconHash)

        val present = Iap2AppLinksMessages.iconResponse(iconResponseFrame {
            string(0, "com.example.maps")
            u8(1, 1)
            u8(2, 8)
            bytes(3, ByteArray(16) { (it + 16).toByte() })
        })
        assertEquals("com.example.maps", present.bundleIdentifier)
        assertEquals(1, present.status)
        assertEquals(8, present.fileTransferBufferId)
        assertEquals(Iap2AppLinksIconHash.of(ByteArray(16) { (it + 16).toByte() }), present.iconHash)
        assertFalse(present.toString().contains("com.example.maps"))
    }

    @Test
    fun iconResponseRejectsTruncatedDuplicateUnknownAndMismatchedFields() {
        expectMalformedIconResponse(Iap2Frame(0xad04, byteArrayOf(0, 5, 0)))
        expectMalformedIconResponse(iconResponseFrame { u8(1, 0) })
        expectMalformedIconResponse(iconResponseFrame { string(0, "com.example.maps") })
        expectMalformedIconResponse(iconResponseFrame { string(0, "com.example.maps"); u16(1, 1) })
        expectMalformedIconResponse(iconResponseFrame { string(0, "com.example.maps"); u8(1, 2) })
        expectMalformedIconResponse(iconResponseFrame {
            string(0, "com.example.maps")
            string(0, "com.example.maps")
            u8(1, 0)
        })
        expectMalformedIconResponse(iconResponseFrame {
            string(0, "com.example.maps")
            u8(1, 0)
            u8(2, 8)
        })
        expectMalformedIconResponse(iconResponseFrame {
            string(0, "com.example.maps")
            u8(1, 1)
            bytes(3, ByteArray(16))
        })
        expectMalformedIconResponse(iconResponseFrame {
            string(0, "com.example.maps")
            u8(1, 1)
            u8(2, 8)
            bytes(3, ByteArray(15))
        })
        expectMalformedIconResponse(iconResponseFrame {
            string(0, "com.example.maps")
            u8(1, 1)
            u8(2, 8)
            bytes(3, ByteArray(16))
            bytes(3, ByteArray(16))
        })
        expectMalformedIconResponse(iconResponseFrame {
            string(0, "com.example.maps")
            u8(1, 0)
            u8(4, 99)
        })
    }

    @Test
    fun iconMessageTracesRedactBundleIdsAndHashBytes() {
        val request = Iap2AppLinksMessages.requestIcons(listOf("com.example.secret"))
        val response = iconResponseFrame {
            string(0, "com.example.secret")
            u8(1, 1)
            u8(2, 7)
            bytes(3, "sensitiveHash123".encodeToByteArray())
        }
        listOf(request, response).forEach { frame ->
            val trace = Iap2FrameFormatter.format(Iap2TraceDirection.RX, "wireless", frame)
            assertTrue(trace.contains("<redacted"))
            assertFalse(trace.contains("com.example.secret"))
            assertFalse(trace.contains("sensitiveHash123"))
            assertFalse(Regex("(?i)73\\s+65\\s+6e\\s+73").containsMatchIn(trace))
        }
    }

    @Test
    fun appLinksTraceNeverIncludesInstalledAppNamesOrBundleIdsEvenAsShortHex() {
        val frame = updateFrame {
            u8(0, 2)
            group(1) {
                u16(0, 1)
                string(1, "com.example.zen")
                string(2, "Zen")
                u16(3, 1)
            }
        }
        val trace = Iap2FrameFormatter.format(Iap2TraceDirection.RX, "wireless", frame)
        assertTrue(trace.contains("0xad01"))
        assertTrue(trace.contains("<redacted"))
        assertFalse(trace.contains("com.example.zen"))
        assertFalse(trace.contains("Zen"))
        assertFalse(Regex("(?i)5a\\s+65\\s+6e").containsMatchIn(trace))
    }

    @Test
    fun updateRejectsMalformedScalarsDuplicatesBadTextAndOversizePayload() {
        expectMalformed(updateFrame { u16(0, 2) })
        expectMalformed(updateFrame { u8(0, 2); u8(0, 2) })
        expectMalformed(updateFrame { u8(0, 3) })
        expectMalformed(updateFrame { u8(0, 1); u16(2, 4) })
        expectMalformed(updateFrame { u8(0, 2); bytes(90, ByteArray(1_025)) })
        expectMalformed(updateFrame { u8(0, 2); bytes(90, ByteArray(Iap2AppLinksMessages.MAX_UPDATE_BODY_BYTES)) })
        expectMalformed(updateFrame {
            u8(0, 2)
            group(1) {
                u16(0, 1)
                bytes(1, byteArrayOf(0xc3.toByte(), 0x28, 0))
                string(2, "Name")
                u16(3, 1)
            }
        })
        expectMalformed(updateFrame {
            u8(0, 2)
            group(1) {
                u16(0, 1)
                string(1, "com.example.navigation")
                string(2, "Map\u202Eunsafe")
                u16(3, 3)
            }
        })
        expectMalformed(updateFrame {
            u8(0, 2)
            repeat(2) {
                group(1) {
                    u16(0, 1)
                    string(1, "com.example.same")
                    string(2, "Same")
                    u16(3, 1)
                }
            }
        })
        expectMalformed(updateFrame {
            u8(0, 2)
            repeat(Iap2AppLinksMessages.MAX_APP_ENTRIES + 1) {
                group(1) {
                    u16(0, it)
                    string(1, "com.example.app")
                    string(2, "App")
                    u16(3, 1)
                }
            }
        })
    }

    @Test
    fun lifecycleDoesNotAutoStartAndStopsOnlyAnAuthenticatedStartedSession() {
        val sent = mutableListOf<Iap2Frame>()
        var authorized = false
        val lifecycle = Iap2AppLinksLifecycle({ sent += it }, { authorized })
        assertTrue(sent.isEmpty())
        assertFalse(lifecycle.start(setOf(1)))
        assertFalse(lifecycle.stop())
        assertTrue(sent.isEmpty())
        authorized = true
        assertTrue(lifecycle.start(setOf(1, 3)))
        assertFalse(lifecycle.start(setOf(3)))
        assertTrue(lifecycle.isStarted)
        assertEquals(listOf(Iap2AppLinksMessages.START_ID), sent.map { it.messageId })
        lifecycle.close()
        assertEquals(listOf(Iap2AppLinksMessages.START_ID, Iap2AppLinksMessages.STOP_ID), sent.map { it.messageId })
        assertFalse(lifecycle.start(setOf(1)))
        assertFalse(lifecycle.stop())
        lifecycle.close()
        assertEquals(2, sent.size)
    }

    @Test
    fun lifecycleDoesNotSendStopAfterAuthenticationRevocation() {
        val sent = mutableListOf<Iap2Frame>()
        var authorized = true
        val lifecycle = Iap2AppLinksLifecycle({ sent += it }, { authorized })
        assertTrue(lifecycle.start(setOf(5)))
        authorized = false
        lifecycle.close()
        assertEquals(listOf(Iap2AppLinksMessages.START_ID), sent.map { it.messageId })
    }

    @Test
    fun failedStopCannotDeliverUpdatesRestartOrBlindlyRetryOnClose() {
        val sent = mutableListOf<Iap2Frame>()
        var failStop = true
        val lifecycle = Iap2AppLinksLifecycle(
            send = { frame ->
                sent += frame
                if (frame.messageId == Iap2AppLinksMessages.STOP_ID && failStop) {
                    throw IllegalStateException("write may have been partial")
                }
            },
            isAuthorized = { true },
        )
        assertTrue(lifecycle.start(setOf(1)))
        val generation = requireNotNull(lifecycle.activeGeneration())
        try {
            lifecycle.stop()
            fail("Expected an uncertain Stop write")
        } catch (_: IllegalStateException) {
            // The transport cannot prove whether AD02 reached the phone.
        }
        assertTrue(lifecycle.isStopUncertain)
        assertFalse(lifecycle.isStarted)
        assertNull(lifecycle.activeGeneration())
        assertFalse(lifecycle.deliverIfStarted(Iap2AppLinksUpdate(2, emptyList(), 0), generation) {
            fail("AD01 after requested Stop")
        })
        failStop = false
        assertFalse(lifecycle.stop())
        assertFalse(lifecycle.start(setOf(1)))
        lifecycle.close()
        assertEquals(
            listOf(Iap2AppLinksMessages.START_ID, Iap2AppLinksMessages.STOP_ID),
            sent.map { it.messageId },
        )
    }

    @Test
    fun authorizationLostDuringStopDoesNotPermitOldSessionRestart() {
        var authorized = true
        val sent = mutableListOf<Iap2Frame>()
        val lifecycle = Iap2AppLinksLifecycle({ sent += it }, { authorized })
        assertTrue(lifecycle.start(setOf(1)))
        authorized = false
        assertFalse(lifecycle.stop())
        authorized = true
        assertTrue(lifecycle.isStopUncertain)
        assertFalse(lifecycle.start(setOf(1)))
        lifecycle.close()
        assertEquals(listOf(Iap2AppLinksMessages.START_ID), sent.map { it.messageId })
    }

    @Test
    fun parsedUpdateCannotDeliverAfterStopOrReconnect() {
        val update = Iap2AppLinksUpdate(2, emptyList(), 0)
        val old = Iap2AppLinksLifecycle({}, { true })
        assertTrue(old.start(setOf(1)))
        val oldGeneration = requireNotNull(old.activeGeneration())
        assertTrue(old.stop())
        var delivered = 0
        assertFalse(old.deliverIfStarted(update, oldGeneration) { delivered++ })
        assertTrue(old.start(setOf(1)))
        val restartedGeneration = requireNotNull(old.activeGeneration())
        assertFalse(old.deliverIfStarted(update, oldGeneration) { delivered++ })
        assertTrue(old.deliverIfStarted(update, restartedGeneration) { delivered++ })
        old.close()

        val replacement = Iap2AppLinksLifecycle({}, { true })
        assertTrue(replacement.start(setOf(3)))
        assertFalse(old.deliverIfStarted(update, restartedGeneration) { delivered++ })
        assertTrue(replacement.deliverIfStarted(
            update, requireNotNull(replacement.activeGeneration()),
        ) { delivered++ })
        assertEquals(2, delivered)
    }

    @Test
    fun stopCannotCompleteWhileAnUpdateCallbackIsBeingDelivered() {
        val update = Iap2AppLinksUpdate(2, emptyList(), 0)
        val lifecycle = Iap2AppLinksLifecycle({}, { true })
        assertTrue(lifecycle.start(setOf(1)))
        val generation = requireNotNull(lifecycle.activeGeneration())
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val stopAttempting = CountDownLatch(1)
        val stopCompleted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val delivery = Thread {
            try {
                assertTrue(lifecycle.deliverIfStarted(update, generation) {
                    callbackEntered.countDown()
                    assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
                    assertEquals(1L, stopCompleted.count)
                })
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        val stopper = Thread {
            try {
                stopAttempting.countDown()
                assertTrue(lifecycle.stop())
                stopCompleted.countDown()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            delivery.start()
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            stopper.start()
            assertTrue(stopAttempting.await(5, TimeUnit.SECONDS))
            assertFalse(stopCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseCallback.countDown()
        }
        delivery.join(5_000)
        stopper.join(5_000)
        assertFalse(delivery.isAlive)
        assertFalse(stopper.isAlive)
        failure.get()?.let { throw AssertionError("Concurrent AppLinks lifecycle failed", it) }
        assertEquals(0L, stopCompleted.count)
        assertFalse(lifecycle.deliverIfStarted(update, generation) { fail("stale callback") })
    }

    private fun identification(wireless: Boolean) = Iap2IdentificationConfig(
        name = "bench",
        modelIdentifier = "bench",
        manufacturer = "bench",
        serialNumber = "serial",
        firmwareVersion = "1",
        hardwareVersion = "1",
        carPlayUsbInterfaceNumber = 4,
        wireless = if (wireless) Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "bench") else null,
    )

    private fun updateFrame(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit): Iap2Frame =
        Iap2Messages.buildRaw(Iap2AppLinksMessages.UPDATE_ID, block)

    private fun iconResponseFrame(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit): Iap2Frame =
        Iap2Messages.buildRaw(Iap2AppLinksMessages.ICON_RESPONSE_ID, block)

    private fun expectMalformed(frame: Iap2Frame) {
        try {
            Iap2AppLinksMessages.update(frame)
            fail("Expected malformed AppLinksUpdate")
        } catch (_: Iap2ProtocolException) {
            // Expected.
        }
    }

    private fun expectMalformedIconRequest(frame: Iap2Frame) {
        try {
            Iap2AppLinksMessages.iconRequest(frame)
            fail("Expected malformed AppLinks icon request")
        } catch (_: Iap2ProtocolException) {
            // Expected.
        }
    }

    private fun expectMalformedIconResponse(frame: Iap2Frame) {
        try {
            Iap2AppLinksMessages.iconResponse(frame)
            fail("Expected malformed AppLinks icon response")
        } catch (_: Iap2ProtocolException) {
            // Expected.
        }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid AppLinks Start")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
