package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.mainbuffered.AirPlayMainBufferedM4Lease
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedAudioFormatBits
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedM4FlushedLeaseProvider
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedPcm16ByteOrder
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedPcm16Output
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedPcm16ProductOutput
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedPcm16ProductOutputFactory
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedPcm16ProductPreparer
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedPcm16VerifiedAnchor
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27RequestTicket
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27SetupState
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedVerifiedSecretBorrow
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayMainBufferedM4LeaseTest {
    @Test
    fun realRtspM4WriteAndFlushPublishesOneExactLease() = Fixture().use { fixture ->
        fixture.session.start()
        networkPairVerifyM4(fixture)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        var lease = fixture.session.claimMainBufferedM4FlushedLease()
        while (lease == null && System.nanoTime() < deadline) {
            Thread.sleep(1)
            lease = fixture.session.claimMainBufferedM4FlushedLease()
        }
        val issued = checkNotNull(lease)
        assertNull(fixture.session.claimMainBufferedM4FlushedLease())
        issued.finishPreparation()
        issued.close()
    }

    @Test
    fun realRtspM4FlushFailureDoesNotPublishLease() = Fixture(failSecondOutputFlush = true).use {
        fixture ->
        fixture.session.start()
        // The test socket transmits M4 bytes but throws from the underlying second flush.
        // Even receipt of those bytes cannot authorize a lease without flush completion.
        networkPairVerifyM4(fixture)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (!fixture.session.isClosed && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(fixture.session.isClosed)
        assertNull("failed flush must never publish an M4 generation",
            fixture.session.javaClass.getDeclaredField("mainBufferedM4PairVerifyGeneration")
                .apply { isAccessible = true }.get(fixture.session))
        assertNull(fixture.session.claimMainBufferedM4FlushedLease())
    }

    private fun networkPairVerifyM4(fixture: Fixture) {
        val client = AirPlayCrypto.x25519Generate()
        val m1 = Tlv8Codec.encode(listOf(
            Tlv8Item(0x06, byteArrayOf(1)), Tlv8Item(0x03, client.publicKey),
        ))
        val m2 = Tlv8Codec.decode(fixture.requestPairVerify(1, m1).body)
        assertArrayEquals(byteArrayOf(2), m2[0x06])
        assertNull(fixture.session.claimMainBufferedM4FlushedLease())
        val serverPublic = checkNotNull(m2[0x03])
        val raw = AirPlayCrypto.x25519Shared(client.privateKey, serverPublic)
        client.privateKey.fill(0)
        val verifyKey = AirPlayCrypto.hkdfSha512(
            raw, "Pair-Verify-Encrypt-Salt".toByteArray(Charsets.US_ASCII),
            "Pair-Verify-Encrypt-Info".toByteArray(Charsets.US_ASCII),
        )
        raw.fill(0)
        val controllerId = CONTROLLER_ID.toByteArray(Charsets.UTF_8)
        val signature = AirPlayCrypto.ed25519Sign(
            fixture.controllerKeys.privateKey,
            concatBytes(client.publicKey, controllerId, serverPublic),
        )
        val sub = Tlv8Codec.encode(listOf(
            Tlv8Item(0x01, controllerId), Tlv8Item(0x0a, signature),
        ))
        val sealed = AirPlayCrypto.chachaSeal(
            verifyKey, AirPlayCrypto.nonceLabel("PV-Msg03"), sub,
        )
        verifyKey.fill(0)
        val m3 = Tlv8Codec.encode(listOf(
            Tlv8Item(0x06, byteArrayOf(3)), Tlv8Item(0x05, sealed),
        ))
        assertArrayEquals(byteArrayOf(4),
            Tlv8Codec.decode(fixture.requestPairVerify(2, m3).body)[0x06])
    }

    @Test
    fun verifiedM4AndEvenPublishedCipherCannotClaimBeforeResponseFlushMarker() = Fixture().use { fixture ->
        assertNull(fixture.session.claimMainBufferedM4FlushedLease())
        finishPairVerify(fixture)
        assertNull(fixture.session.claimMainBufferedM4FlushedLease())
        // This models an M4 output.flush() failure after PairVerify itself committed: the
        // cipher/control flags alone cannot supply the independent flushed-response marker.
        publishControlWithoutM4FlushMarker(fixture.session)
        assertNull(fixture.session.claimMainBufferedM4FlushedLease())
        assertNull(fixture.session.borrowMainBufferedVerifiedSharedSecret(
            AirPlayMainBufferedM4Lease(
                fixture.session, checkNotNull(fixture.session.pairVerify.verifiedGeneration),
                1L, checkNotNull(fixture.session.localAddress),
                checkNotNull(fixture.session.remoteAddress), 1L,
            ), 1L,
        ))
    }

    @Test
    fun exactOneShotLeaseHasUniqueReceiverStreamIdAndLendsOnlyRawTrackedCopy() {
        Fixture().use { first ->
            Fixture().use { second ->
                val raw = finishPairVerify(first)
                finishPairVerify(second)
                publishControlWithoutM4FlushMarker(first.session)
                publishControlWithoutM4FlushMarker(second.session)
                publishFlushedM4ForTest(first.session)
                publishFlushedM4ForTest(second.session)
                val lease = checkNotNull(first.session.claimMainBufferedM4FlushedLease())
                val other = checkNotNull(second.session.claimMainBufferedM4FlushedLease())
                try {
                    assertNull(first.session.claimMainBufferedM4FlushedLease())
                    assertTrue(lease.generation > 0L)
                    assertTrue(lease.allocatedStreamId in 1L..0xffff_ffffL)
                    assertNotEquals(lease.allocatedStreamId, other.allocatedStreamId)
                    assertEquals(first.session.localAddress, lease.bindAddress)
                    assertEquals(first.session.remoteAddress, lease.expectedPeer)
                    assertNull("M4 does not reveal PCM source byte order", lease.sourceByteOrder)
                    assertFalse(second.session.isMainBufferedM4LeaseCurrent(
                        lease as AirPlayMainBufferedM4Lease, lease.generation,
                    ))
                    assertNull(second.session.borrowMainBufferedVerifiedSharedSecret(lease, lease.generation))
                    assertFalse(lease.isCurrent(lease.generation + 1L))

                    lateinit var disposable: ByteArray
                    val borrowed = checkNotNull(lease.borrowVerifiedSharedSecret(lease.generation))
                    try {
                        borrowed.withRawSecret { bytes ->
                            disposable = bytes
                            assertEquals(32, bytes.size)
                            assertArrayEquals(raw, bytes)
                            assertFalse(bytes.contentEquals(
                                checkNotNull(first.session.pairVerify.deriveFreshPairTlsPsk()),
                            ))
                        }
                        assertNull("one tracked raw copy at a time", lease.borrowVerifiedSharedSecret(lease.generation))
                    } finally {
                        borrowed.close()
                    }
                    assertTrue(disposable.all { it == 0.toByte() })
                    assertTrue(borrowed.isReturnedAndZeroized())
                    assertTrue(lease.isCurrent(lease.generation))
                } finally {
                    raw.fill(0)
                    lease.finishPreparation()
                    other.finishPreparation()
                    lease.close()
                    other.close()
                }
                assertNull(first.session.claimMainBufferedM4FlushedLease())
                publishFlushedM4ForTest(first.session)
                assertNull("same M4 cannot re-arm after its one-shot lease closes",
                    first.session.claimMainBufferedM4FlushedLease())
                assertNull(lease.borrowVerifiedSharedSecret(lease.generation))
            }
        }
    }

    @Test
    fun stoppedSessionRevokesBeforeMediaCallbackAndDoesNotIssueAgain() {
        val observed = AtomicBoolean(false)
        lateinit var lease: AirPlayMainBufferedM4Lease
        val media = object : AirPlayMediaHandler {
            override fun onSessionStopping(session: AirPlaySession) {
                observed.set(!lease.isCurrent(lease.generation))
                lease.close()
            }
        }
        Fixture(media).use { fixture ->
            finishPairVerify(fixture)
            publishControlWithoutM4FlushMarker(fixture.session)
            publishFlushedM4ForTest(fixture.session)
            val replayHmacKey = fixture.session.javaClass
                .getDeclaredField("mainBufferedM4ReplayHmacKey")
                .apply { isAccessible = true }.get(fixture.session) as ByteArray
            lease = checkNotNull(fixture.session.claimMainBufferedM4FlushedLease())
                as AirPlayMainBufferedM4Lease
            lease.finishPreparation()
            fixture.session.close()
            assertTrue(observed.get())
            assertNull(fixture.session.claimMainBufferedM4FlushedLease())
            assertNull(lease.borrowVerifiedSharedSecret(lease.generation))
            assertFalse(fixture.session.pairVerify.isVerified)
            assertTrue(replayHmacKey.all { it == 0.toByte() })
        }
    }

    @Test
    fun replacementM1AndType103TeardownRevokeBeforeAnyControlEffect() {
        val observed = AtomicBoolean(false)
        lateinit var lease: AirPlayMainBufferedM4Lease
        val media = object : AirPlayMediaHandler {
            override fun onTeardown(session: AirPlaySession, identity: AirPlayStreamIdentity) {
                observed.set(!lease.isCurrent(lease.generation))
                lease.close()
            }
        }
        Fixture(media).use { fixture ->
            finishPairVerify(fixture)
            publishControlWithoutM4FlushMarker(fixture.session)
            publishFlushedM4ForTest(fixture.session)
            lease = checkNotNull(fixture.session.claimMainBufferedM4FlushedLease())
                as AirPlayMainBufferedM4Lease
            lease.finishPreparation()
            val stream = AirPlayStreamIdentity(103, "42", null)
            fixture.session.activeStreams += stream
            val request = RtspMessage.Request(
                method = "TEARDOWN", path = "/", protocol = "RTSP/1.0",
                headers = emptyMap(),
                body = BplistCodec.encode(mapOf("streams" to listOf(
                    mapOf("type" to 103L, "streamConnectionID" to 42L),
                ))),
            )
            val response = fixture.session.javaClass.getDeclaredMethod(
                "handleTeardown", RtspMessage.Request::class.java,
            ).apply { isAccessible = true }.invoke(fixture.session, request) as RtspMessage.Response
            assertEquals(200, response.status)
            assertTrue(observed.get())
            assertFalse(lease.isCurrent(lease.generation))

            // A second PairVerify M1 on this same PairVerify owner cannot resurrect a lease tied
            // to the old cipher, even if it later verifies another generation.
            val nextClient = AirPlayCrypto.x25519Generate()
            try {
                val m1 = Tlv8Codec.encode(listOf(
                    Tlv8Item(0x06, byteArrayOf(1)),
                    Tlv8Item(0x03, nextClient.publicKey),
                ))
                fixture.session.pairVerify.handle(m1)
                assertNull(fixture.session.claimMainBufferedM4FlushedLease())
            } finally { nextClient.privateKey.fill(0) }
        }
    }

    @Test
    fun activeRawBorrowKeepsSecretOwnedAcrossBoundedCloseFailureThenZeroizesOnRetry() {
        lateinit var lease: AirPlayMainBufferedM4Lease
        val media = object : AirPlayMediaHandler {
            override fun onSessionStopping(session: AirPlaySession) { lease.close() }
        }
        Fixture(media, joinMillis = 40L).use { fixture ->
            finishPairVerify(fixture)
            publishControlWithoutM4FlushMarker(fixture.session)
            publishFlushedM4ForTest(fixture.session)
            lease = checkNotNull(fixture.session.claimMainBufferedM4FlushedLease())
                as AirPlayMainBufferedM4Lease
            lease.finishPreparation()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val copied = AtomicReference<ByteArray>()
            val borrowerFailure = AtomicReference<Throwable?>()
            val borrower = Thread {
                try {
                    checkNotNull(lease.borrowVerifiedSharedSecret(lease.generation)).use { borrow ->
                        borrow.withRawSecret { bytes ->
                            copied.set(bytes)
                            entered.countDown()
                            check(release.await(3, TimeUnit.SECONDS))
                        }
                    }
                } catch (failure: Throwable) { borrowerFailure.set(failure) }
            }
            try {
                borrower.start()
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val failure = assertThrows(IOException::class.java) { fixture.session.close() }
                assertTrue(failure.message.orEmpty().contains("MainBuffered PairVerify secret"))
                assertFalse(fixture.session.pairVerify.isVerified)
                assertTrue(copied.get().any { it != 0.toByte() })
                assertNull(lease.borrowVerifiedSharedSecret(lease.generation))
            } finally {
                release.countDown()
                borrower.join(3_000)
            }
            assertNull(borrowerFailure.get())
            assertTrue(copied.get().all { it == 0.toByte() })
            fixture.session.close()
            assertNull(fixture.session.pairVerify.shared)
        }
    }

    @Test
    fun throwingBorrowCallbackStillZeroizesAndAllowsExactRetirement() = Fixture().use { fixture ->
        finishPairVerify(fixture)
        publishControlWithoutM4FlushMarker(fixture.session)
        publishFlushedM4ForTest(fixture.session)
        val lease = checkNotNull(fixture.session.claimMainBufferedM4FlushedLease())
        lease.finishPreparation()
        lateinit var copied: ByteArray
        val borrow: MainBufferedVerifiedSecretBorrow =
            checkNotNull(lease.borrowVerifiedSharedSecret(lease.generation))
        try {
            assertThrows(IOException::class.java) {
                borrow.withRawSecret { raw -> copied = raw; throw IOException("consumer failed") }
            }
        } finally { borrow.close() }
        assertTrue(copied.all { it == 0.toByte() })
        lease.close()
        fixture.session.close()
    }

    @Test
    fun uninstalledPreparationIsRegisteredBeforeMediaInstallAndBlocksSecretRetirement() =
        Fixture(joinMillis = 40L).use { fixture ->
            finishPairVerify(fixture).fill(0)
            publishControlWithoutM4FlushMarker(fixture.session)
            publishFlushedM4ForTest(fixture.session)
            val lease = checkNotNull(fixture.session.claimMainBufferedM4FlushedLease())
            val failure = AtomicReference<Throwable?>()
            val closer = Thread {
                failure.set(runCatching { fixture.session.close() }.exceptionOrNull())
            }
            closer.start()
            closer.join(2_000)
            assertFalse(closer.isAlive)
            assertTrue(failure.get() is IOException)
            assertTrue(failure.get()?.message.orEmpty().contains("MainBuffered preparation"))
            assertFalse(lease.isCurrent(lease.generation))
            assertTrue("failed retirement retains original PairVerify secret", fixture.session.pairVerify.isVerified)
            lease.finishPreparation()
            lease.close()
            fixture.session.close()
            assertNull(fixture.session.pairVerify.shared)
        }

    @Test
    fun replacementM1RevokesOldRawLeaseBeforeWaitingForBorrowToReturn() = Fixture().use { fixture ->
        finishPairVerify(fixture).fill(0)
        publishControlWithoutM4FlushMarker(fixture.session)
        publishFlushedM4ForTest(fixture.session)
        val lease = checkNotNull(fixture.session.claimMainBufferedM4FlushedLease())
        lease.finishPreparation()
        val borrowed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val borrowerFailure = AtomicReference<Throwable?>()
        val borrower = Thread {
            try {
                checkNotNull(lease.borrowVerifiedSharedSecret(lease.generation)).use { scope ->
                    scope.withRawSecret {
                        borrowed.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                    }
                }
            } catch (failure: Throwable) { borrowerFailure.set(failure) }
        }
        val replacement = AirPlayCrypto.x25519Generate()
        val m1 = Tlv8Codec.encode(listOf(
            Tlv8Item(0x06, byteArrayOf(1)), Tlv8Item(0x03, replacement.publicKey),
        ))
        val response = AtomicReference<ByteArray?>()
        val reparer = Thread { response.set(fixture.session.pairVerify.handle(m1)) }
        try {
            borrower.start()
            assertTrue(borrowed.await(1, TimeUnit.SECONDS))
            reparer.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (lease.isCurrent(lease.generation) && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertFalse("old generation must revoke before M1 waits", lease.isCurrent(lease.generation))
            assertTrue("replacement M1 still waits for registered raw copy", reparer.isAlive)
        } finally {
            release.countDown()
            borrower.join(3_000)
            reparer.join(3_000)
            replacement.privateKey.fill(0)
            lease.close()
        }
        assertNull(borrowerFailure.get())
        assertArrayEquals(byteArrayOf(2), Tlv8Codec.decode(checkNotNull(response.get()))[0x06])
        assertNull(fixture.session.claimMainBufferedM4FlushedLease())
    }

    @Test
    fun sameM4CleanTeardownAdmitsFreshTicketButRejectsEveryIndependentReplayField() {
        val claimed = ArrayList<AirPlayMainBufferedM4Lease>()
        val engine = testProductEngine(claimed)
        Fixture(media = engine).use { fixture ->
            finishPairVerify(fixture).fill(0)
            publishControlWithoutM4FlushMarker(fixture.session)
            publishFlushedM4ForTest(fixture.session)
            forceMainBufferedNegotiation(fixture.session)

            val first = prepareMainBufferedBatch(fixture.session, productRequest(7L, 17L, 1))
            val firstLease = claimed.single()
            assertEquals(1L, firstLease.issuanceOrdinal)
            flushPreparedBatch(fixture.session, first)
            assertEquals(MainBufferedV27SetupState.COMMITTED, first.transaction.state())
            assertEquals(200, teardownMainBuffered(fixture.session, 7L).status)
            assertEquals(MainBufferedV27SetupState.CLOSED, first.transaction.state())
            assertFalse(firstLease.isCurrent(firstLease.generation))

            listOf(
                productRequest(7L, 18L, 2), // reused connection ID alone
                productRequest(8L, 17L, 2), // reused mctl seed alone
                productRequest(8L, 18L, 1), // reused raw shk fingerprint alone
            ).forEach { replay ->
                MainBufferedV27RequestTicket.freeze(replay).use { ticket ->
                    assertNull(fixture.session.claimMainBufferedM4FlushedLease(ticket))
                }
            }

            val second = prepareMainBufferedBatch(fixture.session, productRequest(8L, 18L, 2))
            val secondLease = claimed.last()
            assertEquals(2L, secondLease.issuanceOrdinal)
            assertTrue(secondLease.allocatedStreamId > firstLease.allocatedStreamId)
            assertTrue(secondLease.isCurrent(secondLease.generation))
            firstLease.close() // An old async owner callback cannot revoke the new child.
            first.transaction.close()
            assertTrue(secondLease.isCurrent(secondLease.generation))
            flushPreparedBatch(fixture.session, second)
            assertEquals(200, teardownMainBuffered(fixture.session, 8L).status)
        }
        engine.close()
    }

    @Test
    fun highUInt64TicketUsesCanonicalBitsAndOriginalMutationCannotRetargetClaim() {
        val claimed = ArrayList<AirPlayMainBufferedM4Lease>()
        val engine = testProductEngine(claimed)
        lateinit var epochHmacKey: ByteArray
        Fixture(media = engine).use { fixture ->
            finishPairVerify(fixture).fill(0)
            publishControlWithoutM4FlushMarker(fixture.session)
            publishFlushedM4ForTest(fixture.session)
            epochHmacKey = checkNotNull(fixture.session.javaClass
                .getDeclaredField("mainBufferedM4ReplayHmacKey")
                .apply { isAccessible = true }.get(fixture.session) as ByteArray?)
            assertTrue(epochHmacKey.any { it != 0.toByte() })
            val high = BigInteger.ONE.shiftLeft(63).add(BigInteger.valueOf(5L))
            val mutableShk = ByteArray(32) { (it + 1).toByte() }
            val mutable = productRequest(high, high, 1).toMutableMap()
            mutable["shk"] = mutableShk
            lateinit var frozenRaw: ByteArray
            MainBufferedV27RequestTicket.freeze(mutable).use { ticket ->
                mutable["streamConnectionID"] = 9L
                mutableShk.fill(0)
                assertEquals(high.toLong(), ticket.key.connectionIdBits)
                assertEquals(high.toLong(), ticket.key.mctlSeedBits)
                val lease = checkNotNull(fixture.session.claimMainBufferedM4FlushedLease(ticket))
                    as AirPlayMainBufferedM4Lease
                @Suppress("UNCHECKED_CAST")
                val ledger = fixture.session.javaClass
                    .getDeclaredField("usedMainBufferedShkFingerprints")
                    .apply { isAccessible = true }.get(fixture.session) as Set<String>
                assertEquals(1, ledger.size)
                assertNotEquals(ticket.key.shkSha256, ledger.single())
                ticket.withRawKey { _, raw ->
                    frozenRaw = raw
                    assertEquals(1.toByte(), raw.first())
                }
                assertEquals(1L, lease.issuanceOrdinal)
                lease.finishPreparation()
                lease.close()
            }
            assertTrue(frozenRaw.all { it == 0.toByte() })
            assertNull(fixture.session.claimMainBufferedM4FlushedLease())
        }
        assertTrue(epochHmacKey.all { it == 0.toByte() })
        engine.close()
    }

    @Test
    fun ambiguousResponseWriteTombstonesM4EvenAfterTransactionCloses() {
        val claimed = ArrayList<AirPlayMainBufferedM4Lease>()
        val engine = testProductEngine(claimed)
        Fixture(media = engine).use { fixture ->
            finishPairVerify(fixture).fill(0)
            publishControlWithoutM4FlushMarker(fixture.session)
            publishFlushedM4ForTest(fixture.session)
            forceMainBufferedNegotiation(fixture.session)
            val batch = prepareMainBufferedBatch(fixture.session, productRequest(41L, 51L, 3))
            fixture.session.javaClass.getDeclaredMethod(
                "markMainBufferedResponseWriteStarted", List::class.java,
            ).apply { isAccessible = true }.invoke(fixture.session, batch.prepared)
            fixture.session.javaClass.getDeclaredMethod(
                "tombstoneMainBufferedResponse", List::class.java,
            ).apply { isAccessible = true }.invoke(fixture.session, batch.prepared)
            batch.prepared.single().rollback()
            assertEquals(MainBufferedV27SetupState.CLOSED, batch.transaction.state())
            MainBufferedV27RequestTicket.freeze(productRequest(42L, 52L, 4)).use { ticket ->
                assertNull(fixture.session.claimMainBufferedM4FlushedLease(ticket))
            }
        }
        engine.close()
    }

    @Test
    fun perM4ReplayLedgerHasFiniteIssuanceCap() = Fixture().use { fixture ->
        finishPairVerify(fixture).fill(0)
        publishControlWithoutM4FlushMarker(fixture.session)
        publishFlushedM4ForTest(fixture.session)
        fixture.session.javaClass.getDeclaredField("mainBufferedM4IssuedOrdinal")
            .apply { isAccessible = true }.setLong(fixture.session, 256L)
        MainBufferedV27RequestTicket.freeze(productRequest(91L, 101L, 7)).use { ticket ->
            assertNull(fixture.session.claimMainBufferedM4FlushedLease(ticket))
        }
        @Suppress("UNCHECKED_CAST")
        val ledger = fixture.session.javaClass
            .getDeclaredField("usedMainBufferedConnectionIds")
            .apply { isAccessible = true }.get(fixture.session) as Set<Long>
        assertTrue(ledger.isEmpty())
    }

    @Test
    fun outstandingSecretBorrowKeepsExactSelectorRetryableUntilReturned() {
        val claimed = ArrayList<AirPlayMainBufferedM4Lease>()
        val engine = testProductEngine(claimed)
        Fixture(media = engine, joinMillis = 40L).use { fixture ->
            finishPairVerify(fixture).fill(0)
            publishControlWithoutM4FlushMarker(fixture.session)
            publishFlushedM4ForTest(fixture.session)
            forceMainBufferedNegotiation(fixture.session)
            val batch = prepareMainBufferedBatch(fixture.session, productRequest(61L, 71L, 5))
            flushPreparedBatch(fixture.session, batch)
            val lease = claimed.single()
            val borrow = checkNotNull(lease.borrowVerifiedSharedSecret(lease.generation))
            try {
                assertEquals(503, teardownMainBuffered(fixture.session, 61L).status)
                assertEquals(MainBufferedV27SetupState.CLOSED, batch.transaction.state())
                assertTrue(AirPlayStreamIdentity(103, "61", null) in fixture.session.activeStreams)
                MainBufferedV27RequestTicket.freeze(productRequest(62L, 72L, 6)).use { ticket ->
                    assertNull(fixture.session.claimMainBufferedM4FlushedLease(ticket))
                }
            } finally {
                borrow.close()
            }
            assertEquals(200, teardownMainBuffered(fixture.session, 61L).status)
            assertFalse(AirPlayStreamIdentity(103, "61", null) in fixture.session.activeStreams)
            val fresh = prepareMainBufferedBatch(fixture.session, productRequest(62L, 72L, 6))
            val newLease = claimed.last()
            assertEquals(2L, newLease.issuanceOrdinal)
            lease.close()
            batch.transaction.close()
            assertTrue(newLease.isCurrent(newLease.generation))
            flushPreparedBatch(fixture.session, fresh)
            assertTrue(AirPlayStreamIdentity(103, "62", null) in fixture.session.activeStreams)
            assertEquals(200, teardownMainBuffered(fixture.session, 62L).status)
        }
        engine.close()
    }

    @Test
    fun failedNativeOutputCloseRetriesSameSelectorButKeepsM4ReplayTombstone() {
        val claimed = ArrayList<AirPlayMainBufferedM4Lease>()
        val engine = testProductEngine(claimed, outputCloseFailures = 1)
        Fixture(media = engine).use { fixture ->
            finishPairVerify(fixture).fill(0)
            publishControlWithoutM4FlushMarker(fixture.session)
            publishFlushedM4ForTest(fixture.session)
            forceMainBufferedNegotiation(fixture.session)
            val batch = prepareMainBufferedBatch(fixture.session, productRequest(63L, 73L, 5))
            flushPreparedBatch(fixture.session, batch)
            assertEquals(503, teardownMainBuffered(fixture.session, 63L).status)
            assertTrue(AirPlayStreamIdentity(103, "63", null) in fixture.session.activeStreams)
            assertEquals(MainBufferedV27SetupState.RETIRING, batch.transaction.state())
            assertEquals(200, teardownMainBuffered(fixture.session, 63L).status)
            assertFalse(AirPlayStreamIdentity(103, "63", null) in fixture.session.activeStreams)
            MainBufferedV27RequestTicket.freeze(productRequest(64L, 74L, 6)).use { ticket ->
                assertNull(fixture.session.claimMainBufferedM4FlushedLease(ticket))
            }
        }
        engine.close()
    }

    @Test
    fun successfulType103SetupWipesItsOwnedRtspBodyBeforeResponseFlush() {
        val claimed = ArrayList<AirPlayMainBufferedM4Lease>()
        val engine = testProductEngine(claimed)
        Fixture(media = engine).use { fixture ->
            finishPairVerify(fixture).fill(0)
            publishControlWithoutM4FlushMarker(fixture.session)
            publishFlushedM4ForTest(fixture.session)
            forceMainBufferedNegotiation(fixture.session)
            val body = BplistCodec.encode(mapOf("streams" to listOf(
                productRequest(81L, 91L, 7),
            )))
            val request = setupRequest(body)
            val handled = invokeHandleSetup(fixture.session, request)
            assertTrue(body.all { it == 0.toByte() })
            val response = handled.javaClass.getDeclaredField("response")
                .apply { isAccessible = true }.get(handled) as RtspMessage.Response
            assertEquals(200, response.status ?: 200)
            assertFalse(response.body.contentEquals(body))
            handled.javaClass.getDeclaredMethod("responseFailed")
                .apply { isAccessible = true }.invoke(handled)
        }
        engine.close()
    }

    @Test
    fun rejectedAndMalformedType103SetupsWipeDecoderDataAndRtspBody() {
        var decoderShk: ByteArray? = null
        val media = object : AirPlayMediaHandler {
            override fun prepareMainBuffered(
                session: AirPlaySession,
                identity: AirPlayStreamIdentity,
                stream: Map<String, Any?>,
            ): AirPlayPreparedMainBufferedStream? {
                decoderShk = stream["shk"] as? ByteArray
                return null
            }
        }
        Fixture(media).use { fixture ->
            finishPairVerify(fixture).fill(0)
            publishControlWithoutM4FlushMarker(fixture.session)
            publishFlushedM4ForTest(fixture.session)
            forceMainBufferedNegotiation(fixture.session)
            val rejectedBody = BplistCodec.encode(mapOf("streams" to listOf(
                productRequest(82L, 92L, 8),
            )))
            assertThrows(InvocationTargetException::class.java) {
                invokeHandleSetup(fixture.session, setupRequest(rejectedBody))
            }
            assertTrue(rejectedBody.all { it == 0.toByte() })
            assertTrue(checkNotNull(decoderShk).all { it == 0.toByte() })

            val malformedBody = byteArrayOf(0x62, 0x70, 0x6c, 0x69, 0x73, 0x74, 0x30, 0x30, 0x42)
            val handled = invokeHandleSetup(fixture.session, setupRequest(malformedBody))
            val response = handled.javaClass.getDeclaredField("response")
                .apply { isAccessible = true }.get(handled) as RtspMessage.Response
            assertEquals(400, response.status)
            assertTrue(malformedBody.all { it == 0.toByte() })
        }
    }

    @Test
    fun ordinaryStreamAliasIsDetachedBeforeType103RawKeyIsWiped() = Fixture().use { fixture ->
        val shared = ByteArray(32) { (it + 1).toByte() }
        val unrelated = byteArrayOf(9, 8, 7)
        val streams = listOf(
            mapOf("type" to 103L, "shk" to shared),
            mapOf("type" to 100L, "ekey" to shared, "metadata" to unrelated),
        )
        @Suppress("UNCHECKED_CAST")
        val detached = fixture.session.javaClass.getDeclaredMethod(
            "detachOtherStreamSecretAliases", List::class.java, List::class.java,
        ).apply { isAccessible = true }.invoke(fixture.session, streams, listOf(shared)) as List<Map<String, Any?>>
        val ordinary = detached[1]
        val ordinaryKey = ordinary["ekey"] as ByteArray
        assertFalse(ordinaryKey === shared)
        assertTrue(ordinary["metadata"] === unrelated)
        shared.fill(0)
        assertEquals(1.toByte(), ordinaryKey.first())
    }

    @Test
    fun rejectedWellFormedButNonArrayStreamsWipesDecodedDataCopies() = Fixture().use { fixture ->
        finishPairVerify(fixture).fill(0)
        publishControlWithoutM4FlushMarker(fixture.session)
        publishFlushedM4ForTest(fixture.session)
        val raw = ByteArray(32) { (it + 1).toByte() }
        val invalidStreamCarriers = listOf(
            mapOf("streams" to mapOf("shk" to raw)),
            mapOf("streams" to listOf(listOf(raw))),
        )
        invalidStreamCarriers.forEach { carrier ->
            val observed = ArrayList<ByteArray>()
            val body = BplistCodec.encode(carrier)
            val handled = invokeHandleSetupWithDecodedDataObserver(
                fixture.session, setupRequest(body), observed,
            )
            val response = handled.javaClass.getDeclaredField("response")
                .apply { isAccessible = true }.get(handled) as RtspMessage.Response
            assertEquals(400, response.status)
            assertTrue(observed.isNotEmpty())
            assertTrue(observed.all { copy -> copy.all { it == 0.toByte() } })
            assertTrue(body.all { it == 0.toByte() })
            assertTrue(raw.any { it != 0.toByte() })
        }
    }

    private fun setupRequest(body: ByteArray): RtspMessage.Request = RtspMessage.Request(
        method = "SETUP", path = "/", protocol = "RTSP/1.0",
        headers = mapOf("cseq" to "1"), body = body,
    )

    private fun invokeHandleSetup(session: AirPlaySession, request: RtspMessage.Request): Any =
        checkNotNull(session.javaClass.getDeclaredMethod(
            "handleSetup", RtspMessage.Request::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(session, request, true))

    private fun invokeHandleSetupWithDecodedDataObserver(
        session: AirPlaySession,
        request: RtspMessage.Request,
        observed: MutableList<ByteArray>,
    ): Any {
        val decode: (ByteArray) -> OwnedDecodedPlist = { source ->
            BplistCodec.decodeOwned(source, BplistDecodeLimits()) { observed += it }
        }
        return checkNotNull(session.javaClass.getDeclaredMethod(
            "handleSetup", RtspMessage.Request::class.java, Boolean::class.javaPrimitiveType,
            Function1::class.java,
        ).apply { isAccessible = true }.invoke(session, request, true, decode))
    }

    private data class PreparedMainBufferedBatch(
        val prepared: List<AirPlayPreparedMediaStream>,
        val transaction: com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27SetupTransaction,
    )

    private fun prepareMainBufferedBatch(
        session: AirPlaySession,
        request: Map<String, Any?>,
    ): PreparedMainBufferedBatch {
        val method = session.javaClass.getDeclaredMethod("handleStreams", List::class.java)
            .apply { isAccessible = true }
        val batch = method.invoke(session, listOf(request))
        @Suppress("UNCHECKED_CAST")
        val prepared = batch.javaClass.getDeclaredField("prepared").run {
            isAccessible = true
            get(batch) as List<AirPlayPreparedMediaStream>
        }
        val transaction = session.javaClass.getDeclaredField("mainBufferedTransactions").run {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (get(session) as Map<AirPlayStreamIdentity,
                com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27SetupTransaction>)
                .values.single()
        }
        assertEquals(1, prepared.size)
        return PreparedMainBufferedBatch(prepared, transaction)
    }

    private fun flushPreparedBatch(session: AirPlaySession, batch: PreparedMainBufferedBatch) {
        session.javaClass.getDeclaredMethod("markMainBufferedResponseWriteStarted", List::class.java)
            .apply { isAccessible = true }.invoke(session, batch.prepared)
        session.javaClass.getDeclaredMethod("commitSetup", String::class.java, List::class.java)
            .apply { isAccessible = true }.invoke(session, "test", batch.prepared)
        session.javaClass.getDeclaredMethod("markMainBufferedResponseFlushCompleted", List::class.java)
            .apply { isAccessible = true }.invoke(session, batch.prepared)
    }

    private fun teardownMainBuffered(session: AirPlaySession, connectionId: Any): RtspMessage.Response {
        val request = RtspMessage.Request(
            method = "TEARDOWN", path = "/", protocol = "RTSP/1.0",
            headers = emptyMap(),
            body = BplistCodec.encode(mapOf("streams" to listOf(
                mapOf("type" to 103L, "streamConnectionID" to connectionId),
            ))),
        )
        return session.javaClass.getDeclaredMethod("handleTeardown", RtspMessage.Request::class.java)
            .apply { isAccessible = true }.invoke(session, request) as RtspMessage.Response
    }

    private fun forceMainBufferedNegotiation(session: AirPlaySession) {
        session.javaClass.getDeclaredField("negotiatedFeatures").apply { isAccessible = true }
            .set(session, setOf(AirPlayFeature.MAIN_BUFFERED))
    }

    private fun productRequest(connectionId: Any, seed: Any, keyByte: Int): Map<String, Any?> =
        linkedMapOf(
            "type" to 103L,
            "streamConnectionID" to connectionId,
            "supportsDynamicStreamID" to true,
            "ct" to "lpcm",
            "spf" to 2L,
            "audioFormat" to MainBufferedAudioFormatBits.PCM_48000_16_STEREO,
            "audioFormatIndex" to 15L,
            "isMedia" to true,
            "audioType" to "media",
            "packetFormatAPAP" to true,
            "shk" to ByteArray(32) { (it + keyByte).toByte() },
            "streamConnections" to mapOf(
                "streamConnectionTypeAPAP" to emptyMap<String, Any>(),
                "streamConnectionTypeMediaDataControl" to mapOf(
                    "streamConnectionKeyEncryptionSeed" to seed,
                ),
            ),
        )

    private fun testProductEngine(
        claimed: MutableList<AirPlayMainBufferedM4Lease>,
        outputCloseFailures: Int = 0,
    ): CarPlayMediaEngine {
        val preparer = MainBufferedPcm16ProductPreparer(
            MainBufferedM4FlushedLeaseProvider { session, ticket ->
                (session.claimMainBufferedM4FlushedLease(ticket) as? AirPlayMainBufferedM4Lease)
                    ?.also { lease ->
                        // Test-only synthetic source evidence. Production issuer stays null.
                        lease.javaClass.getDeclaredField("sourceByteOrder")
                            .apply { isAccessible = true }
                            .set(lease, MainBufferedPcm16ByteOrder.LITTLE_ENDIAN)
                        claimed += lease
                    }
            },
            MainBufferedPcm16ProductOutputFactory {
                MainBufferedPcm16ProductOutput(
                    FakeMainBufferedOutput(outputCloseFailures), 4_096L,
                )
            },
            samplesPerFrame = 2L,
        )
        return CarPlayMediaEngine(object : MediaSink {}, preparer)
    }

    private class FakeMainBufferedOutput(private var closeFailures: Int = 0) : MainBufferedPcm16Output {
        override var isReady = true
        override var playbackEpoch = 0L
        override val outputInstance = Any()
        override var firstQueuedMediaTimeValue: Long? = null
        override fun offerPcmLittleEndian(pcm: ByteArray, mediaTimeValue: Long): Boolean = true
        override fun pausePlayback() { playbackEpoch++ }
        override fun startPlayback(anchor: MainBufferedPcm16VerifiedAnchor): Boolean = false
        override fun flush() { playbackEpoch++ }
        override fun close() {
            if (closeFailures > 0) {
                closeFailures--
                throw IOException("injected native output close failure")
            }
            isReady = false
        }
    }

    private class Fixture(
        media: AirPlayMediaHandler = object : AirPlayMediaHandler {},
        joinMillis: Long = 2_000L,
        failSecondOutputFlush: Boolean = false,
    ) : Closeable {
        val controllerKeys = AirPlayCrypto.ed25519Generate()
        private val pairings = PairingStore().apply {
            save(CONTROLLER_ID, controllerKeys.publicKey)
        }
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort).apply {
            soTimeout = 3_000
        }
        private val accepted = listener.accept()
        private val server: Socket = if (failSecondOutputFlush) FailSecondFlushSocket(accepted)
            else accepted
        val session = AirPlaySession(
            socket = server,
            config = AirPlayConfig(
                deviceName = "main-buffered-m4-lease-test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(),
            pairings = pairings,
            mfi = null,
            listener = object : AirPlaySessionListener {},
            media = media,
            sessionTransport = AirPlaySessionTransport.WIRELESS_LOCAL_NETWORK,
            retirementJoinTimeoutMillis = joinMillis,
        )

        fun requestPairVerify(cseq: Int, body: ByteArray): RtspMessage.Request {
            val request = (
                "POST /pair-verify RTSP/1.0\r\n" +
                    "CSeq: $cseq\r\n" +
                    "Content-Type: application/pairing+tlv8\r\n" +
                    "Content-Length: ${body.size}\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII) + body
            client.getOutputStream().apply { write(request); flush() }
            val response = ByteArrayOutputStream()
            val input = client.getInputStream()
            val delimiter = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
            var matched = 0
            while (matched < delimiter.size) {
                val next = input.read()
                check(next >= 0) { "M4 response ended before RTSP headers" }
                response.write(next)
                matched = if (next.toByte() == delimiter[matched]) matched + 1 else 0
            }
            val header = response.toByteArray().toString(Charsets.US_ASCII)
            val length = Regex("(?i)Content-Length: (\\d+)")
                .find(header)?.groupValues?.get(1)?.toInt() ?: 0
            repeat(length) {
                val next = input.read()
                check(next >= 0) { "M4 response ended before body" }
                response.write(next)
            }
            return RtspMessage.parseMessages(response.toByteArray()).messages.single()
        }

        override fun close() {
            try { session.close() } finally { client.close(); accepted.close(); listener.close() }
        }
    }

    private class FailSecondFlushSocket(private val delegate: Socket) : Socket() {
        private var wrappedOutput: OutputStream? = null

        override fun getInputStream(): InputStream = delegate.getInputStream()
        override fun getOutputStream(): OutputStream = wrappedOutput ?: object :
            FilterOutputStream(delegate.getOutputStream()) {
            private var flushes = 0
            override fun flush() {
                super.flush()
                flushes++
                if (flushes == 2) throw IOException("injected M4 output.flush failure")
            }
        }.also { wrappedOutput = it }
        override fun getInetAddress(): InetAddress = delegate.inetAddress
        override fun getLocalAddress(): InetAddress = delegate.localAddress
        override fun getRemoteSocketAddress(): SocketAddress = delegate.remoteSocketAddress
        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
        override fun setSoLinger(on: Boolean, linger: Int) { delegate.setSoLinger(on, linger) }
        override fun close() { delegate.close() }
    }

    private fun finishPairVerify(fixture: Fixture): ByteArray {
        val client = AirPlayCrypto.x25519Generate()
        val m1 = Tlv8Codec.encode(listOf(
            Tlv8Item(0x06, byteArrayOf(1)), Tlv8Item(0x03, client.publicKey),
        ))
        val m2 = Tlv8Codec.decode(fixture.session.pairVerify.handle(m1))
        assertArrayEquals(byteArrayOf(2), m2[0x06])
        val serverPublic = checkNotNull(m2[0x03])
        val raw = AirPlayCrypto.x25519Shared(client.privateKey, serverPublic)
        client.privateKey.fill(0)
        val verifyKey = AirPlayCrypto.hkdfSha512(
            raw, "Pair-Verify-Encrypt-Salt".toByteArray(Charsets.US_ASCII),
            "Pair-Verify-Encrypt-Info".toByteArray(Charsets.US_ASCII),
        )
        val controllerId = CONTROLLER_ID.toByteArray(Charsets.UTF_8)
        val signature = AirPlayCrypto.ed25519Sign(
            fixture.controllerKeys.privateKey,
            concatBytes(client.publicKey, controllerId, serverPublic),
        )
        val sub = Tlv8Codec.encode(listOf(
            Tlv8Item(0x01, controllerId), Tlv8Item(0x0a, signature),
        ))
        val sealed = AirPlayCrypto.chachaSeal(
            verifyKey, AirPlayCrypto.nonceLabel("PV-Msg03"), sub,
        )
        verifyKey.fill(0)
        val m3 = Tlv8Codec.encode(listOf(
            Tlv8Item(0x06, byteArrayOf(3)), Tlv8Item(0x05, sealed),
        ))
        val m4 = Tlv8Codec.decode(fixture.session.pairVerify.handle(m3))
        assertArrayEquals(byteArrayOf(4), m4[0x06])
        assertNull(m4[0x07])
        return raw
    }

    private fun publishControlWithoutM4FlushMarker(session: AirPlaySession) {
        val keys = checkNotNull(session.pairVerify.controlKeys)
        try { session.cipher = ControlCipher(keys.readKey, keys.writeKey) }
        finally { keys.readKey.fill(0); keys.writeKey.fill(0) }
        session.javaClass.getDeclaredField("authenticatedControl").apply { isAccessible = true }
            .get(session).let { it as AtomicBoolean }.set(true)
        session.javaClass.getDeclaredField("authenticatedPairVerifyGeneration").apply {
            isAccessible = true
        }.set(session, session.pairVerify.verifiedGeneration)
    }

    /** Test-only invocation models the method's actual caller after output.flush succeeds. */
    private fun publishFlushedM4ForTest(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("publishMainBufferedM4FlushedGeneration")
            .apply { isAccessible = true }.invoke(session)
    }

    private companion object { const val CONTROLLER_ID = "main-buffered-controller" }
}
