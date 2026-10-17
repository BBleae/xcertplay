package com.shilapi.xcertplay.airplay.rcs.update

import com.shilapi.xcertplay.airplay.AirPlayReadiness
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CarPlayUpdateDataQuarantineRootOwnerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val limits = CarPlayUpdateDataLimits(
        maxRcsBodyBytes = 1024,
        maxChunkBytes = 128,
        maxMetadataBytes = 16,
        maxArchiveBytes = 128,
        maxChunkCount = 4,
    )
    private val codec = CarPlayUpdateDataCodec(limits)
    private val reservation = 128L + 3L * 16L + 4096L

    @Test
    fun reservesTwoConcurrentStreamsAndRetainsOneStableDescriptor() {
        val root = temporaryFolder.newFolder("quarantine")
        val owner = owner(root, CarPlayUpdateDataQuarantineQuota(reservation * 3, 3))
        try {
            val descriptor = owner.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)!!
            assertTrue(descriptor === owner.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA))
            assertTrue(owner.ownsDescriptor(descriptor))
            assertTrue(descriptor.probe().isReady)
            owner.consumerReadinessDescriptor(descriptor)
            val forged = RcsDataStreamHandlerDescriptor(
                RcsClientTypes.CAR_PLAY_UPDATE_DATA,
                descriptor.implementationId,
                RcsDataStreamHandlerProbe::ready,
            ) { throw IOException("not a quarantine consumer") }
            assertThrows(IllegalArgumentException::class.java) {
                owner.consumerReadinessDescriptor(forged)
            }

            val first = owner.openUnboundSessionForTest()
            val second = owner.openUnboundSessionForTest()
            assertEquals(2, owner.activeTransferCount)
            assertEquals(reservation * 2, owner.reservedBytesInFlight)
            assertThrows(IOException::class.java) { owner.openUnboundSessionForTest() }

            first.close()
            second.close()
            assertTrue(owner.awaitDrained(2_000))
            assertEquals(0, owner.activeTransferCount)
            assertTrue(owner.inventory().entries.isEmpty())
        } finally {
            owner.close()
        }
    }

    @Test
    fun liveStagingIsCountedByItsReservationNotTwice() {
        val root = temporaryFolder.newFolder("quarantine")
        val owner = owner(root, CarPlayUpdateDataQuarantineQuota(reservation * 2, 2))
        try {
            val first = owner.openUnboundSessionForTest()
            first.onMessage(codec.encode(header()))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (owner.inventory().entryCount == 0 && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(1, owner.inventory().entryCount)

            val second = owner.openUnboundSessionForTest()
            assertEquals(2, owner.activeTransferCount)
            first.close()
            second.close()
            assertTrue(owner.awaitDrained(2_000))
        } finally {
            owner.close()
        }
    }

    @Test
    fun refusesSecondOwnerOfTheSamePhysicalRootUntilFirstCloses() {
        val root = temporaryFolder.newFolder("quarantine")
        val first = owner(root, CarPlayUpdateDataQuarantineQuota(reservation, 1))
        try {
            assertThrows(IOException::class.java) {
                owner(root, CarPlayUpdateDataQuarantineQuota(reservation, 1))
            }
        } finally {
            first.close()
        }
        owner(root, CarPlayUpdateDataQuarantineQuota(reservation, 1)).close()
    }

    @Test
    fun sessionCapabilityReservesBeforeReadinessAndUnusedCloseReleases() {
        val root = temporaryFolder.newFolder("quarantine")
        val owner = owner(root, CarPlayUpdateDataQuarantineQuota(reservation, 1))
        try {
            val raw = owner.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)!!
            assertEquals(
                AirPlayReadiness.UNAVAILABLE,
                owner.consumerReadinessDescriptor(raw).boundedQuarantineConsumer,
            )
            val first = owner.tryReserveSession()!!
            assertEquals(1, owner.activeTransferCount)
            assertFalse(raw.probe().isReady)
            assertEquals(null, owner.tryReserveSession())
            val exact = first.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)!!
            assertTrue(exact === first.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA))
            assertTrue(exact.probe().isReady)
            assertEquals(
                AirPlayReadiness.READY,
                first.consumerReadinessDescriptor(exact).boundedQuarantineConsumer,
            )
            assertEquals(
                AirPlayReadiness.UNAVAILABLE,
                first.consumerReadinessDescriptor(exact).boundedQuarantineConsumer,
            )
            val forged = RcsDataStreamHandlerDescriptor(
                RcsClientTypes.CAR_PLAY_UPDATE_DATA,
                exact.implementationId,
                RcsDataStreamHandlerProbe::ready,
            ) { throw IOException("not this reserved stream") }
            assertThrows(IllegalArgumentException::class.java) {
                first.consumerReadinessDescriptor(forged)
            }

            first.close()
            assertTrue(owner.awaitDrained(2_000))
            assertFalse(exact.probe().isReady)
            owner.tryReserveSession()!!.close()
            assertTrue(owner.awaitDrained(2_000))
        } finally {
            owner.close()
        }
    }

    @Test
    fun concurrentSessionReadinessReservationsCannotOvercommitQuota() {
        val root = temporaryFolder.newFolder("quarantine")
        val owner = owner(root, CarPlayUpdateDataQuarantineQuota(reservation, 1))
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val capabilities = Collections.synchronizedList(
            mutableListOf<CarPlayUpdateDataQuarantineRootOwner.CarPlayUpdateDataQuarantineSessionLease>(),
        )
        try {
            repeat(2) {
                Thread {
                    try {
                        start.await()
                        owner.tryReserveSession()?.let(capabilities::add)
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(1, capabilities.size)
            assertEquals(1, owner.activeTransferCount)
            capabilities.single().close()
            assertTrue(owner.awaitDrained(2_000))
        } finally {
            capabilities.forEach { it.close() }
            owner.close()
        }
    }

    @Test
    fun transferredSessionLeaseIsSingleUseAndWaitsForSinkCleanup() {
        val root = temporaryFolder.newFolder("quarantine")
        val sink = BlockingCommitSink()
        val owner = CarPlayUpdateDataQuarantineRootOwner.forTesting(
            root, CarPlayUpdateDataQuarantineQuota(reservation, 1), limits, 1,
        ) { _, _ -> sink }
        try {
            val capability = owner.tryReserveSession()!!
            val descriptor = capability.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)!!
            assertTrue(descriptor.probe().isReady)
            val session = capability.openUnboundSessionForTest()
            assertFalse(descriptor.probe().isReady)
            assertThrows(IllegalStateException::class.java) {
                capability.openUnboundSessionForTest()
            }
            session.onMessage(codec.encode(header()))
            session.onMessage(codec.encode(chunk()))
            assertTrue(sink.commitEntered.await(2, TimeUnit.SECONDS))
            capability.close()
            assertEquals(1, owner.activeTransferCount)
            assertFalse(owner.awaitDrained(50))

            sink.releaseCommit.countDown()
            assertTrue(owner.awaitDrained(2_000))
            assertTrue(session.awaitCleanup(2_000))
        } finally {
            sink.releaseCommit.countDown()
            owner.close()
        }
    }

    @Test
    fun ownerCloseReleasesUnusedSessionReservation() {
        val root = temporaryFolder.newFolder("quarantine")
        val owner = owner(root, CarPlayUpdateDataQuarantineQuota(reservation, 1))
        val capability = owner.tryReserveSession()!!
        assertEquals(1, owner.activeTransferCount)
        owner.close()
        assertTrue(owner.awaitDrained(2_000))
        assertFalse(capability.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)!!.probe().isReady)
    }

    @Test
    fun ownerCloseDuringHandlerConstructionCannotReleaseBeforeWorkerCleanup() {
        val root = temporaryFolder.newFolder("quarantine")
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val abortEntered = CountDownLatch(1)
        val releaseAbort = CountDownLatch(1)
        val sink = object : CarPlayUpdateDataQuarantineSink {
            override fun begin(header: CarPlayUpdateDataMessage.Header) = Unit
            override fun append(bytes: ByteArray) = Unit
            override fun commit(receipt: UntrustedCarPlayUpdateDataReceipt) = false
            override fun abort() {
                abortEntered.countDown()
                awaitUninterruptibly(releaseAbort)
            }
        }
        val owner = CarPlayUpdateDataQuarantineRootOwner.forTesting(
            root, CarPlayUpdateDataQuarantineQuota(reservation, 1), limits, 1,
        ) { _, _ ->
            factoryEntered.countDown()
            awaitUninterruptibly(releaseFactory)
            sink
        }
        val capability = owner.tryReserveSession()!!
        val constructionReturned = CountDownLatch(1)
        val constructor = Thread {
            runCatching { capability.openUnboundSessionForTest() }
            constructionReturned.countDown()
        }
        try {
            constructor.start()
            assertTrue(factoryEntered.await(2, TimeUnit.SECONDS))
            owner.close()
            assertEquals(1, owner.activeTransferCount)

            releaseFactory.countDown()
            assertTrue(constructionReturned.await(2, TimeUnit.SECONDS))
            assertTrue(abortEntered.await(2, TimeUnit.SECONDS))
            capability.close()
            assertEquals(1, owner.activeTransferCount)
            assertFalse(owner.awaitDrained(50))

            releaseAbort.countDown()
            assertTrue(owner.awaitDrained(2_000))
        } finally {
            releaseFactory.countDown()
            releaseAbort.countDown()
            constructor.join(2_000)
            owner.close()
        }
    }

    @Test
    fun ownerCloseAfterHandlerRegistrationNeverPublishesADeadHandler() {
        val root = temporaryFolder.newFolder("quarantine")
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val owner = CarPlayUpdateDataQuarantineRootOwner.forTesting(
            root,
            CarPlayUpdateDataQuarantineQuota(reservation, 1),
            limits,
            1,
            beforeSessionResourcePublish = {
                publishEntered.countDown()
                awaitUninterruptibly(releasePublish)
            },
        ) { diskRoot, configuredLimits ->
            CarPlayUpdateDataFileQuarantineSink(diskRoot, configuredLimits)
        }
        val capability = owner.tryReserveSession()!!
        val completed = CountDownLatch(1)
        val failure = Collections.synchronizedList(mutableListOf<Throwable?>())
        val opening = Thread {
            failure += runCatching { capability.openUnboundSessionForTest() }.exceptionOrNull()
            completed.countDown()
        }
        try {
            opening.start()
            assertTrue(publishEntered.await(2, TimeUnit.SECONDS))
            owner.close() // Controller has been replaced by the handler, but lease has not published it.
            releasePublish.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertTrue(failure.single() is IOException)
            assertTrue(owner.awaitDrained(2_000))
        } finally {
            releasePublish.countDown()
            opening.join(2_000)
            owner.close()
        }
    }

    @Test
    fun concurrentQuotaAdmissionIsAtomic() {
        val root = temporaryFolder.newFolder("quarantine")
        val owner = owner(root, CarPlayUpdateDataQuarantineQuota(reservation, 1))
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val opened = Collections.synchronizedList(mutableListOf<CarPlayUpdateDataSerialSession>())
        val failed = AtomicInteger()
        try {
            repeat(2) {
                Thread {
                    try {
                        start.await()
                        opened += owner.openUnboundSessionForTest()
                    } catch (_: IOException) {
                        failed.incrementAndGet()
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(1, opened.size)
            assertEquals(1, failed.get())
            assertEquals(1, owner.activeTransferCount)
            opened.single().close()
            assertTrue(owner.awaitDrained(2_000))
            assertTrue(owner.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)!!.probe().isReady)
        } finally {
            opened.forEach { it.close() }
            owner.close()
        }
    }

    @Test
    fun closeDuringBlockedDurableCommitKeepsReservationUntilWorkerDrain() {
        val root = temporaryFolder.newFolder("quarantine")
        val sink = BlockingCommitSink()
        val owner = CarPlayUpdateDataQuarantineRootOwner.forTesting(
            root, CarPlayUpdateDataQuarantineQuota(reservation, 1), limits, 1,
        ) { _, _ -> sink }
        val acks = Collections.synchronizedList(mutableListOf<Int>())
        try {
            val session = owner.openUnboundSessionForTest(ackCollector(acks))
            session.onMessage(codec.encode(header()))
            session.onMessage(codec.encode(chunk()))
            assertTrue(sink.commitEntered.await(2, TimeUnit.SECONDS))

            session.close() // close is only a signal, not a durability/cleanup barrier.
            owner.close()
            assertEquals(1, owner.activeTransferCount)
            assertFalse(owner.awaitDrained(50))
            assertEquals(reservation, owner.reservedBytesInFlight)

            sink.releaseCommit.countDown()
            assertTrue(owner.awaitDrained(2_000))
            assertTrue(session.awaitCleanup(2_000))
            assertTrue("closed stream must not receive ACK 0", acks.isEmpty())
        } finally {
            sink.releaseCommit.countDown()
            owner.close()
        }
    }

    @Test
    fun closeDoesNotWaitForAlreadyAdmittedBlockedAckSend() {
        val root = temporaryFolder.newFolder("quarantine")
        val owner = owner(root, CarPlayUpdateDataQuarantineQuota(reservation, 1))
        val sendEntered = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        try {
            val session = owner.openUnboundSessionForTest {
                sendEntered.countDown()
                while (releaseSend.count != 0L) {
                    try {
                        releaseSend.await()
                    } catch (_: InterruptedException) {
                        // Model a socket send already blocked after ACK admission.
                    }
                }
            }
            session.onMessage(codec.encode(header()))
            session.onMessage(codec.encode(chunk()))
            assertTrue(sendEntered.await(2, TimeUnit.SECONDS))

            Thread {
                owner.close()
                closeReturned.countDown()
            }.start()
            assertTrue("close must not wait for socket backpressure", closeReturned.await(2, TimeUnit.SECONDS))
            assertEquals(1, owner.activeTransferCount)
            assertFalse(owner.awaitDrained(50))

            releaseSend.countDown()
            assertTrue(owner.awaitDrained(2_000))
            assertTrue(session.awaitCleanup(2_000))
        } finally {
            releaseSend.countDown()
            owner.close()
        }
    }

    @Test
    fun failedAbortRetainsReservationUntilExplicitRetry() {
        val root = temporaryFolder.newFolder("quarantine")
        val sink = FailingAbortSink()
        val owner = CarPlayUpdateDataQuarantineRootOwner.forTesting(
            root, CarPlayUpdateDataQuarantineQuota(reservation, 1), limits, 1,
        ) { _, _ -> sink }
        try {
            val session = owner.openUnboundSessionForTest()
            session.onMessage(codec.encode(header()))
            assertTrue(sink.beginCalled.await(2, TimeUnit.SECONDS))
            session.close()
            assertFalse(session.awaitCleanup(2_000))
            assertEquals(CarPlayUpdateDataCleanupState.FAILED, session.cleanupStateSnapshot)
            assertEquals(1, owner.activeTransferCount)
            assertFalse(owner.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)!!.probe().isReady)

            assertEquals(1, owner.retryFailedCleanups())
            assertTrue(owner.awaitDrained(2_000))
            assertEquals(3, sink.abortCalls.get())
            assertTrue(sink.cleaned)
        } finally {
            owner.close()
        }
    }

    @Test
    fun doesNotAutoDeleteOldStagingAndRefusesReplacedRoot() {
        val root = temporaryFolder.newFolder("quarantine")
        val old = File(root, "staging-${UUID.randomUUID()}")
        assertTrue(old.mkdir())
        File(old, "header.bin").writeBytes(byteArrayOf(7))
        val owner = owner(root, CarPlayUpdateDataQuarantineQuota(reservation * 3, 3))
        try {
            assertTrue(owner.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)!!.probe().isReady)
            val session = owner.openUnboundSessionForTest()
            session.close()
            assertTrue(owner.awaitDrained(2_000))
            assertTrue("inventory/admission must not sweep staging", old.isDirectory)
            assertEquals(1, owner.inventory().entryCount)

            val moved = root.toPath().resolveSibling("quarantine-moved")
            Files.move(root.toPath(), moved)
            Files.createDirectory(root.toPath())
            assertFalse(owner.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA)!!.probe().isReady)
            assertThrows(IOException::class.java) { owner.openUnboundSessionForTest() }
            assertTrue(Files.isDirectory(moved.resolve(old.name)))
        } finally {
            owner.close()
        }
    }

    @Test
    fun committedArchiveIsOnlyAnUntrustedRetainedRecord() {
        val root = temporaryFolder.newFolder("quarantine")
        val owner = owner(root, CarPlayUpdateDataQuarantineQuota(reservation * 2, 2))
        val acks = Collections.synchronizedList(mutableListOf<Int>())
        try {
            val session = owner.openUnboundSessionForTest(ackCollector(acks))
            session.onMessage(codec.encode(header()))
            session.onMessage(codec.encode(chunk()))
            assertTrue(session.awaitCleanup(2_000))
            assertTrue(owner.awaitDrained(2_000))
            assertEquals(listOf(0), acks)
            val inventory = owner.inventory()
            assertEquals(1, inventory.entryCount)
            assertEquals(
                CarPlayUpdateDataQuarantineEntryKind.RECORD_UNTRUSTED_ACK_UNKNOWN,
                inventory.entries.single().kind,
            )
            assertTrue(inventory.entries.single().directory.isDirectory)
        } finally {
            owner.close()
        }
    }

    private fun owner(root: File, quota: CarPlayUpdateDataQuarantineQuota) =
        CarPlayUpdateDataQuarantineRootOwner(root, quota, limits)

    private fun header() = CarPlayUpdateDataMessage.Header(SESSION_ID, 27, 1)

    private fun chunk() = CarPlayUpdateDataMessage.Chunk(SESSION_ID, 0, byteArrayOf(8), sha256(byteArrayOf(8)))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ackCollector(acks: MutableList<Int>): (RcsMessage) -> Unit = { message ->
        acks += (codec.decode(message) as CarPlayUpdateDataMessage.Ack).accepted
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (latch.count != 0L) {
            try {
                latch.await()
            } catch (_: InterruptedException) {
                // Some filesystem operations complete after close interrupts the worker.
            }
        }
    }

    private class BlockingCommitSink : CarPlayUpdateDataQuarantineSink {
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)

        override fun begin(header: CarPlayUpdateDataMessage.Header) = Unit
        override fun append(bytes: ByteArray) = Unit
        override fun commit(receipt: UntrustedCarPlayUpdateDataReceipt): Boolean {
            commitEntered.countDown()
            while (releaseCommit.count != 0L) {
                try {
                    releaseCommit.await()
                } catch (_: InterruptedException) {
                    // Model a blocking filesystem force() that finishes after close is signaled.
                }
            }
            return true
        }
        override fun abort() = Unit
    }

    private class FailingAbortSink : CarPlayUpdateDataQuarantineSink {
        val beginCalled = CountDownLatch(1)
        val abortCalls = AtomicInteger()
        @Volatile var cleaned = false

        override fun begin(header: CarPlayUpdateDataMessage.Header) { beginCalled.countDown() }
        override fun append(bytes: ByteArray) = Unit
        override fun commit(receipt: UntrustedCarPlayUpdateDataReceipt): Boolean = false
        override fun abort() {
            if (abortCalls.incrementAndGet() <= 2) throw IOException("injected abort failure")
            cleaned = true
        }
    }

    companion object {
        private const val SESSION_ID = "b4e9ae2d-3964-4a5d-bb6e-9acb5d3067ee"
    }
}
