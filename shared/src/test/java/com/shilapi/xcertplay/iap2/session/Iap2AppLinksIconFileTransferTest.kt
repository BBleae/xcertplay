package com.shilapi.xcertplay.iap2.session

import com.shilapi.xcertplay.iap2.message.Iap2AppLinksIconHash
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksIconResponse
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2AppLinksIconFileTransferTest {
    private val session = Any()
    private var clock = 1_000L
    private val handler = Iap2FileTransferHandler({ clock }, session)
    private val png = png(1, 1)

    @Test
    fun artworkTypeTwoStillUsesOriginalPath() {
        assertArrayEquals(byteArrayOf(3, 1), handler.handle(setup(3, 2, 3)).response)
        val result = handler.handle(data(3, 0xc0, byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(3, 5), result.response)
        assertArrayEquals(byteArrayOf(1, 2, 3), result.artwork?.bytes)
        assertNull(result.icon)
    }

    @Test
    fun grantBeforeSetupReceivesOnlyVerifiedImage() {
        val token = token()
        assertTrue(handler.prepareIconRequest(token))
        assertTrue(handler.grantIconTransfer(token, response(7, png), { true }).accepted)
        assertArrayEquals(byteArrayOf(7, 1), handler.handle(setup(7, 8, png.size)).response)
        val result = handler.handle(data(7, 0xc0, png))
        assertArrayEquals(byteArrayOf(7, 5), result.response)
        assertNull(result.artwork)
        assertEquals("image/png", result.icon?.mimeType)
        assertEquals(1, result.icon?.width)
        assertEquals(1, result.icon?.height)
        assertArrayEquals(png, result.icon?.bytes)
        assertArrayEquals(byteArrayOf(7, 6), handler.handle(setup(7, 8, png.size)).response)
    }

    @Test
    fun earlySetupWaitsWithoutStartThenGrantUnlocksSameId() {
        val token = token()
        assertTrue(handler.prepareIconRequest(token))
        assertNull(handler.handle(setup(7, 8, png.size)).response)
        val grant = handler.grantIconTransfer(token, response(7, png), { true })
        assertTrue(grant.accepted)
        assertArrayEquals(byteArrayOf(7, 1), grant.response)
        assertEquals("image/png", handler.handle(data(7, 0xc0, png)).icon?.mimeType)
    }

    @Test
    fun earlySetupTimesOutAndCannotBeResurrectedByLateAd04() {
        val token = token()
        assertTrue(handler.prepareIconRequest(token))
        assertNull(handler.handle(setup(7, 8, png.size)).response)
        clock += 1_001
        assertEquals(listOf(7), handler.expire())
        assertFalse(handler.grantIconTransfer(token, response(7, png), { true }).accepted)
        assertArrayEquals(byteArrayOf(7, 6), handler.handle(setup(7, 8, png.size)).response)
    }

    @Test
    fun statusZeroWrongSessionEpochAndBundleNeverGrant() {
        assertFalse(handler.prepareIconRequest(Iap2AppLinksIconRequestToken(Any(), 1, 1, BUNDLE)))
        val token = token()
        assertTrue(handler.prepareIconRequest(token))
        val older = Iap2AppLinksIconRequestToken(session, 1, 1, BUNDLE)
        assertFalse(handler.grantIconTransfer(older, response(7, png), { true }).accepted)
        assertFalse(handler.grantIconTransfer(
            token,
            Iap2AppLinksIconResponse(BUNDLE, 0, null, null),
            { true },
        ).accepted)
        assertArrayEquals(byteArrayOf(7, 6), handler.handle(setup(7, 8, png.size)).response)
        assertTrue(handler.prepareIconRequest(Iap2AppLinksIconRequestToken(session, 2, 1, BUNDLE)))
        val newer = Iap2AppLinksIconRequestToken(session, 2, 2, BUNDLE)
        handler.revokeIconRequest(Iap2AppLinksIconRequestToken(session, 2, 1, BUNDLE))
        // A token object is an exact identity, not just matching fields.
        assertFalse(handler.prepareIconRequest(newer))
    }

    @Test
    fun wrongBundleAndStaleCurrentFailClosed() {
        val token = token()
        assertTrue(handler.prepareIconRequest(token))
        val wrongBundle = response(7, png).copy(bundleIdentifier = "other.bundle")
        assertFalse(handler.grantIconTransfer(token, wrongBundle, { true }).accepted)
        assertArrayEquals(byteArrayOf(7, 6), handler.handle(setup(7, 8, png.size)).response)

        val next = Iap2AppLinksIconRequestToken(session, 1, 2, BUNDLE)
        assertTrue(handler.prepareIconRequest(next))
        assertFalse(handler.grantIconTransfer(next, response(7, png), { false }).accepted)
        assertArrayEquals(byteArrayOf(7, 6), handler.handle(setup(7, 8, png.size)).response)
    }

    @Test
    fun wrongHashMimeDimensionsAndLengthFailWithoutIcon() {
        val wrongHash = ByteArray(16) { 0x44 }
        val wide = png(4_096, 1)
        val cases = listOf(
            Triple(png, Iap2AppLinksIconHash.of(wrongHash), png.size),
            Triple(ByteArray(45) { 0x11 }, hash(ByteArray(45) { 0x11 }), 45),
            Triple(wide, hash(wide), wide.size),
            Triple(png, hash(png), png.size + 1),
        )
        cases.forEachIndexed { index, (body, hash, length) ->
            val fixture = Iap2FileTransferHandler({ clock }, session)
            val token = Iap2AppLinksIconRequestToken(session, 1, index.toLong() + 1, BUNDLE)
            assertTrue(fixture.prepareIconRequest(token))
            assertTrue(fixture.grantIconTransfer(token, Iap2AppLinksIconResponse(BUNDLE, 1, 7, hash), { true }).accepted)
            assertArrayEquals(byteArrayOf(7, 1), fixture.handle(setup(7, 8, length)).response)
            val result = fixture.handle(data(7, 0xc0, body))
            assertArrayEquals(byteArrayOf(7, 6), result.response)
            assertNull(result.icon)
            assertArrayEquals(byteArrayOf(7, 6), fixture.handle(setup(7, 8, length)).response)
        }
    }

    @Test
    fun sizeLimitConflictAndRevokeRejectReuse() {
        val token = token()
        assertTrue(handler.prepareIconRequest(token))
        assertArrayEquals(byteArrayOf(7, 6), handler.handle(setup(7, 8, 2 * 1_048_576 + 1)).response)
        assertTrue(handler.grantIconTransfer(token, response(7, png), { true }).accepted)
        assertArrayEquals(byteArrayOf(7, 1), handler.handle(setup(7, 8, png.size)).response)
        assertArrayEquals(byteArrayOf(7, 6), handler.handle(setup(7, 8, png.size)).response)
        assertNull(handler.handle(data(7, 0xc0, png)).response)

        val next = Iap2AppLinksIconRequestToken(session, 1, 2, BUNDLE)
        assertTrue(handler.prepareIconRequest(next))
        assertTrue(handler.grantIconTransfer(next, response(7, png), { true }).accepted)
        assertArrayEquals(byteArrayOf(7, 1), handler.handle(setup(7, 8, png.size)).response)
        assertArrayEquals(byteArrayOf(7, 6), handler.revokeIconRequest(next))
        assertNull(handler.handle(data(7, 0xc0, png)).response)
    }

    @Test
    fun aggregateBudgetCountsArtworkAndIconWithoutDiscardingHealthyTransfer() {
        val fixture = Iap2FileTransferHandler(
            nowMillis = { clock }, sessionIdentity = session,
            maxAggregateInFlightBytes = png.size.toLong() + 1L,
        )
        val token = token()
        assertTrue(fixture.prepareIconRequest(token))
        assertTrue(fixture.grantIconTransfer(token, response(7, png), { true }).accepted)
        assertArrayEquals(byteArrayOf(7, 1), fixture.handle(setup(7, 8, png.size)).response)
        assertNull(fixture.handle(data(7, 0x80, png.copyOfRange(0, png.size - 1))).response)
        assertArrayEquals(byteArrayOf(9, 1), fixture.handle(setup(9, 2, 3)).response)
        assertArrayEquals(
            byteArrayOf(9, 6),
            fixture.handle(data(9, 0xc0, byteArrayOf(1, 2, 3))).response,
        )
        assertEquals("image/png", fixture.handle(data(7, 0x40, png.copyOfRange(png.size - 1, png.size))).icon?.mimeType)
    }

    @Test
    fun receiverThreadUsesSameSessionTwelveConsumerAndDeliversOnlyAfterGrant() {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val outgoing = LinkedBlockingQueue<ByteArray>()
        val icons = LinkedBlockingQueue<Iap2TransferredAppLinksIcon>()
        val receiver = Iap2FileTransferReceiver(
            receiveFileTransfer = { timeout -> incoming.poll(timeout, TimeUnit.MILLISECONDS) },
            sendFileTransfer = { bytes, _ -> outgoing.add(bytes) },
            isSessionClosed = { false },
            sessionIdentity = session,
            onIcon = { icon -> icons.add(icon) },
        )
        try {
            receiver.start()
            val token = token()
            assertTrue(receiver.prepareIconRequest(token))
            incoming.add(setup(7, 8, png.size))
            assertNull(outgoing.poll(100, TimeUnit.MILLISECONDS))
            assertTrue(receiver.grantIconTransfer(token, response(7, png), { true }))
            assertArrayEquals(byteArrayOf(7, 1), outgoing.poll(2, TimeUnit.SECONDS))
            incoming.add(data(7, 0xc0, png))
            assertArrayEquals(byteArrayOf(7, 5), outgoing.poll(2, TimeUnit.SECONDS))
            assertArrayEquals(png, icons.poll(2, TimeUnit.SECONDS)?.bytes)
            assertNull(icons.poll(50, TimeUnit.MILLISECONDS))
        } finally {
            receiver.close()
        }
    }

    @Test
    fun blockedStartCannotBeOvertakenByQueuedIconDataOrSuccess() {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val outgoing = LinkedBlockingQueue<ByteArray>()
        val icons = LinkedBlockingQueue<Iap2TransferredAppLinksIcon>()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val grantFailure = AtomicReference<Throwable?>()
        val receiver = blockedStartReceiver(incoming, outgoing, icons, startEntered, releaseStart)
        var grantThread: Thread? = null
        try {
            receiver.start()
            val token = token()
            assertTrue(receiver.prepareIconRequest(token))
            incoming.add(setup(7, 8, png.size))
            awaitPendingIconSetup(receiver)
            grantThread = Thread {
                try {
                    assertTrue(receiver.grantIconTransfer(token, response(7, png), { true }))
                } catch (failure: Throwable) {
                    grantFailure.set(failure)
                }
            }.also(Thread::start)
            assertTrue(startEntered.await(2, TimeUnit.SECONDS))
            incoming.add(data(7, 0xc0, png))
            assertNull(outgoing.poll(100, TimeUnit.MILLISECONDS))
            assertNull(icons.poll(100, TimeUnit.MILLISECONDS))
            releaseStart.countDown()
            grantThread.join(2_000)
            assertFalse(grantThread.isAlive)
            assertNull(grantFailure.get())
            assertArrayEquals(byteArrayOf(7, 1), outgoing.poll(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(7, 5), outgoing.poll(2, TimeUnit.SECONDS))
            assertArrayEquals(png, icons.poll(2, TimeUnit.SECONDS)?.bytes)
        } finally {
            releaseStart.countDown()
            grantThread?.join(2_000)
            receiver.close()
        }
    }

    @Test
    fun revokeDuringBlockedStartNeverEmitsFailureBeforeStartOrDeliversIcon() {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val outgoing = LinkedBlockingQueue<ByteArray>()
        val icons = LinkedBlockingQueue<Iap2TransferredAppLinksIcon>()
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val grantFailure = AtomicReference<Throwable?>()
        val revokeFailure = AtomicReference<Throwable?>()
        val receiver = blockedStartReceiver(incoming, outgoing, icons, startEntered, releaseStart)
        var grantThread: Thread? = null
        var revokeThread: Thread? = null
        try {
            receiver.start()
            val token = token()
            assertTrue(receiver.prepareIconRequest(token))
            incoming.add(setup(7, 8, png.size))
            awaitPendingIconSetup(receiver)
            grantThread = Thread {
                try {
                    receiver.grantIconTransfer(token, response(7, png), { true })
                } catch (failure: Throwable) {
                    grantFailure.set(failure)
                }
            }.also(Thread::start)
            assertTrue(startEntered.await(2, TimeUnit.SECONDS))
            revokeThread = Thread {
                try {
                    receiver.revokeIconRequest(token)
                } catch (failure: Throwable) {
                    revokeFailure.set(failure)
                }
            }.also(Thread::start)
            awaitCondition { token.revoked }
            incoming.add(data(7, 0xc0, png))
            assertNull(outgoing.poll(100, TimeUnit.MILLISECONDS))
            releaseStart.countDown()
            grantThread.join(2_000)
            revokeThread.join(2_000)
            assertFalse(grantThread.isAlive)
            assertFalse(revokeThread.isAlive)
            assertNull(grantFailure.get())
            assertNull(revokeFailure.get())
            assertArrayEquals(byteArrayOf(7, 1), outgoing.poll(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(7, 6), outgoing.poll(2, TimeUnit.SECONDS))
            assertNull(outgoing.poll(100, TimeUnit.MILLISECONDS))
            assertNull(icons.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseStart.countDown()
            grantThread?.join(2_000)
            revokeThread?.join(2_000)
            receiver.close()
        }
    }

    @Test
    fun alternateAllowedSignaturesHaveBoundedGeometry() {
        val jpeg = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(),
            0, 7, 8, 0, 2, 0, 3, 0xff.toByte(), 0xd9.toByte(),
        )
        assertEquals(Iap2AppLinksIconImageInspector.Geometry("image/jpeg", 3, 2),
            Iap2AppLinksIconImageInspector.inspect(jpeg))
        val webp = byteArrayOf(
            82, 73, 70, 70, 22, 0, 0, 0, 87, 69, 66, 80,
            86, 80, 56, 88, 10, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0,
        )
        assertEquals(Iap2AppLinksIconImageInspector.Geometry("image/webp", 1, 1),
            Iap2AppLinksIconImageInspector.inspect(webp))
        assertNull(Iap2AppLinksIconImageInspector.inspect(png(0, 1)))
        assertNull(Iap2AppLinksIconImageInspector.inspect(png.copyOf(png.size - 1)))
    }

    private fun token() = Iap2AppLinksIconRequestToken(session, 1, 1, BUNDLE)

    private fun response(id: Int, body: ByteArray) =
        Iap2AppLinksIconResponse(BUNDLE, 1, id, hash(body))

    private fun hash(body: ByteArray) =
        Iap2AppLinksIconHash.of(MessageDigest.getInstance("SHA-256").digest(body).copyOf(16))

    private fun setup(id: Int, fileType: Int, size: Int): ByteArray = byteArrayOf(
        id.toByte(), 4, 0, 0, 0, 0,
        ((size.toLong() ushr 24) and 0xff).toByte(),
        ((size ushr 16) and 0xff).toByte(),
        ((size ushr 8) and 0xff).toByte(), size.toByte(),
        0, fileType.toByte(),
    )

    private fun data(id: Int, command: Int, payload: ByteArray): ByteArray =
        byteArrayOf(id.toByte(), command.toByte()) + payload

    private fun blockedStartReceiver(
        incoming: LinkedBlockingQueue<ByteArray>,
        outgoing: LinkedBlockingQueue<ByteArray>,
        icons: LinkedBlockingQueue<Iap2TransferredAppLinksIcon>,
        startEntered: CountDownLatch,
        releaseStart: CountDownLatch,
    ) = Iap2FileTransferReceiver(
        receiveFileTransfer = { timeout -> incoming.poll(timeout, TimeUnit.MILLISECONDS) },
        sendFileTransfer = { bytes, _ ->
            if (bytes[1].toInt() == 1) {
                startEntered.countDown()
                check(releaseStart.await(3, TimeUnit.SECONDS)) { "test START send was not released" }
            }
            outgoing.add(bytes)
        },
        isSessionClosed = { false },
        sessionIdentity = session,
        onIcon = { icon -> icons.add(icon) },
    )

    private fun awaitPendingIconSetup(receiver: Iap2FileTransferReceiver) {
        val handlerField = Iap2FileTransferReceiver::class.java.getDeclaredField("handler")
            .apply { isAccessible = true }
        val handler = handlerField.get(receiver)
        val pendingField = Iap2FileTransferHandler::class.java.getDeclaredField("pendingIconSetup")
            .apply { isAccessible = true }
        awaitCondition { pendingField.get(handler) != null }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "test condition was not reached" }
            Thread.sleep(1)
        }
    }

    private fun png(width: Int, height: Int): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==",
    ).apply {
        this[16] = (width ushr 24).toByte()
        this[17] = (width ushr 16).toByte()
        this[18] = (width ushr 8).toByte()
        this[19] = width.toByte()
        this[20] = (height ushr 24).toByte()
        this[21] = (height ushr 16).toByte()
        this[22] = (height ushr 8).toByte()
        this[23] = height.toByte()
    }

    private companion object { const val BUNDLE = "com.example.app" }
}
