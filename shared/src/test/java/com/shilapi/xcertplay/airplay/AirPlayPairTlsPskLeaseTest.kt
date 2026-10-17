package com.shilapi.xcertplay.airplay

import java.io.IOException
import java.io.Closeable
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayPairTlsPskLeaseTest {
    @Test
    fun validM4RequiresFlushedControlGenerationAndLendsOnlyTemporarySixteenBytes() {
        val fixture = fixture()
        try {
            assertNull(fixture.session.claimPairTlsPskLease())
            assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
            assertNull("M4 alone is not a flushed authenticated control channel", fixture.session.claimPairTlsPskLease())
            markControlAuthenticated(fixture.session)

            val expected = fixture.session.pairVerify.deriveFreshPairTlsPsk()!!
            val lease = fixture.session.claimPairTlsPskLease()!!
            assertNull("one exact session can have only one active owner", fixture.session.claimPairTlsPskLease())
            lateinit var borrowed: ByteArray
            val length = lease.withPskFor(fixture.session) { psk ->
                borrowed = psk
                assertArrayEquals(expected, psk)
                psk.size
            }
            assertEquals(16, length)
            assertTrue(borrowed.all { it == 0.toByte() })
            expected.fill(0)
            assertTrue(lease.toString().contains("psk=<redacted>"))

            lease.close()
            lease.close()
            assertNull(lease.withPskFor(fixture.session) { it.size })
            assertTrue(fixture.session.claimPairTlsPskLease() != null)
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun badM4CannotCreateLeaseEvenWhenControlFlagsAreIncorrectlySet() {
        val fixture = fixture()
        try {
            val bad = beginVerify(fixture, corruptSignature = true)
            val result = Tlv8Codec.decode(fixture.session.pairVerify.handle(bad.m3))
            assertArrayEquals(byteArrayOf(4), result[TYPE_STATE])
            assertArrayEquals(byteArrayOf(2), result[TYPE_ERROR])
            forceControlFlags(fixture.session, Any())
            assertNull(fixture.session.claimPairTlsPskLease())
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun newM1InvalidatesOldLeaseImmediatelyAndNewM4CannotUseOldControlCipher() {
        val fixture = fixture()
        try {
            assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
            markControlAuthenticated(fixture.session)
            val oldLease = fixture.session.claimPairTlsPskLease()!!

            val second = beginVerify(fixture)
            assertNull(oldLease.withPskFor(fixture.session) { it.size })
            assertNull(fixture.session.claimPairTlsPskLease())
            assertM4(fixture.session.pairVerify.handle(second.m3))
            assertNull(oldLease.withPskFor(fixture.session) { it.size })
            assertNull("the old cipher is not authenticated for the new M4", fixture.session.claimPairTlsPskLease())
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun leaseCannotBeRedeemedForAnotherControlSession() {
        val first = fixture()
        val second = fixture()
        try {
            assertM4(first.session.pairVerify.handle(beginVerify(first).m3))
            assertM4(second.session.pairVerify.handle(beginVerify(second).m3))
            markControlAuthenticated(first.session)
            markControlAuthenticated(second.session)
            val firstLease = first.session.claimPairTlsPskLease()!!
            assertNull(firstLease.withPskFor(second.session) { it.size })
            assertEquals(16, firstLease.withPskFor(first.session) { it.size })
        } finally {
            first.session.close()
            second.session.close()
        }
    }

    @Test
    fun transportConstructionFailureWipesBorrowedArrayAndAllowsOwnedRetry() {
        val fixture = fixture()
        try {
            assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
            markControlAuthenticated(fixture.session)
            val lease = fixture.session.claimPairTlsPskLease()!!
            lateinit var borrowed: ByteArray
            val failure = assertThrows(IOException::class.java) {
                lease.withPskFor(fixture.session) { psk ->
                    borrowed = psk
                    throw IOException("TLS engine construction failed")
                }
            }
            assertEquals("TLS engine construction failed", failure.message)
            assertTrue(borrowed.all { it == 0.toByte() })
            assertEquals(16, lease.withPskFor(fixture.session) { it.size })
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun closeFromBorrowCallbackDefersRetirementUntilBorrowUnwinds() {
        val fixture = fixture()
        assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
        markControlAuthenticated(fixture.session)
        val lease = fixture.session.claimPairTlsPskLease()!!
        lateinit var borrowed: ByteArray
        lease.withPskFor(fixture.session) { psk ->
            borrowed = psk
            assertThrows(IOException::class.java) { fixture.session.close() }
            assertTrue("in-flight key remains owned until callback exit", psk.any { it != 0.toByte() })
            assertNull(lease.withPskFor(fixture.session) { it.size })
        }
        assertTrue(borrowed.all { it == 0.toByte() })
        fixture.session.close()
        assertFalse(fixture.session.pairVerify.isVerified)
    }

    @Test
    fun closeWaitsForBorrowThenRevokesLeaseWithoutLeakingTemporaryMaterial() {
        val fixture = fixture()
        assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
        markControlAuthenticated(fixture.session)
        val lease = fixture.session.claimPairTlsPskLease()!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val borrowed = AtomicReference<ByteArray>()
        val borrowFailure = AtomicReference<Throwable>()
        val closeFailure = AtomicReference<Throwable>()
        val borrower = Thread({
            try {
                lease.withPskFor(fixture.session) { psk ->
                    borrowed.set(psk)
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
            } catch (error: Throwable) {
                borrowFailure.set(error)
            }
        }, "psk-lease-borrower")
        val closer = Thread({
            try {
                closeEntered.countDown()
                fixture.session.close()
            } catch (error: Throwable) {
                closeFailure.set(error)
            }
        }, "psk-lease-closer")
        try {
            borrower.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            closer.start()
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS))
            await { fixture.session.isClosed }
            closer.join(50)
            assertTrue("close must wait for the temporary key owner", closer.isAlive)
        } finally {
            release.countDown()
            borrower.join(3_000)
            closer.join(3_000)
        }
        assertFalse(borrower.isAlive)
        assertFalse(closer.isAlive)
        assertNull(borrowFailure.get())
        assertNull(closeFailure.get())
        assertTrue(borrowed.get().all { it == 0.toByte() })
        assertNull(lease.withPskFor(fixture.session) { it.size })
        assertNull(fixture.session.claimPairTlsPskLease())
    }

    @Test
    fun timedOutBorrowRetirementKeepsExactSecretOwnerForLaterRetry() {
        val fixture = fixture(retirementJoinTimeoutMillis = 30)
        assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
        markControlAuthenticated(fixture.session)
        val lease = fixture.session.claimPairTlsPskLease()!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val borrowed = AtomicReference<ByteArray>()
        val borrower = Thread({
            lease.withPskFor(fixture.session) { psk ->
                borrowed.set(psk)
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
        }, "psk-borrow-timeout")
        try {
            borrower.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val failure = assertThrows(IOException::class.java) { fixture.session.close() }
            assertTrue(failure.message.orEmpty().contains("Pair-TLS-PSK borrow"))
            assertTrue(fixture.session.pairVerify.isVerified)
            assertNull(lease.withPskFor(fixture.session) { it.size })
        } finally {
            release.countDown()
            borrower.join(3_000)
            fixture.session.close()
        }
        assertFalse(borrower.isAlive)
        assertTrue(borrowed.get().all { it == 0.toByte() })
        assertFalse(fixture.session.pairVerify.isVerified)
    }

    @Test
    fun stoppingCoverAcknowledgesBeforeWaitingForActivePskBorrow() {
        val covered = CountDownLatch(1)
        val fixture = fixture(media = object : AirPlayMediaHandler {
            override fun onSessionStopping(session: AirPlaySession) {
                covered.countDown()
            }
        })
        assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
        markControlAuthenticated(fixture.session)
        val lease = fixture.session.claimPairTlsPskLease()!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val borrower = Thread {
            lease.withPskFor(fixture.session) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val closeFailure = AtomicReference<Throwable?>()
        borrower.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closer = Thread {
            closeFailure.set(runCatching { fixture.session.close() }.exceptionOrNull())
        }
        closer.start()
        assertTrue("physical cover waited for PSK borrow", covered.await(1, TimeUnit.SECONDS))
        assertTrue("PSK borrow should still hold close", closer.isAlive)
        release.countDown()
        borrower.join(5_000)
        closer.join(5_000)
        assertFalse(borrower.isAlive)
        assertFalse(closer.isAlive)
        assertNull(closeFailure.get())
    }

    @Test
    fun newM1WaitsForActiveBorrowThenRevokesItsGeneration() {
        val fixture = fixture()
        assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
        markControlAuthenticated(fixture.session)
        val lease = fixture.session.claimPairTlsPskLease()!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rePairStarted = CountDownLatch(1)
        val nextM1Finished = CountDownLatch(1)
        val rePairFailure = AtomicReference<Throwable>()
        val borrower = Thread({
            lease.withPskFor(fixture.session) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
        }, "psk-before-new-m1")
        val reparer = Thread({
            try {
                rePairStarted.countDown()
                beginVerify(fixture)
            } catch (error: Throwable) {
                rePairFailure.set(error)
            } finally {
                nextM1Finished.countDown()
            }
        }, "new-pair-verify-m1")
        try {
            borrower.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            reparer.start()
            assertTrue(rePairStarted.await(1, TimeUnit.SECONDS))
            assertFalse(nextM1Finished.await(50, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            borrower.join(3_000)
            reparer.join(3_000)
        }
        try {
            assertFalse(borrower.isAlive)
            assertFalse(reparer.isAlive)
            assertNull(rePairFailure.get())
            assertEquals(0L, nextM1Finished.count)
            assertNull(lease.withPskFor(fixture.session) { it.size })
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun terminalSecretRetirementWinsOverConcurrentNewM1WaitingForBorrow() {
        val fixture = fixture()
        assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
        markControlAuthenticated(fixture.session)
        val lease = fixture.session.claimPairTlsPskLease()!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retirementDone = CountDownLatch(1)
        val m1Failure = AtomicReference<Throwable>()
        val borrower = Thread({
            lease.withPskFor(fixture.session) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
        }, "psk-terminal-borrow")
        val retire = Thread({
            fixture.session.pairVerify.clearSessionSecrets()
            retirementDone.countDown()
        }, "psk-terminal-clear")
        val reparer = Thread({
            try {
                beginVerify(fixture)
            } catch (error: Throwable) {
                m1Failure.set(error)
            }
        }, "psk-terminal-m1")
        try {
            borrower.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            retire.start()
            await { privateField<Boolean>(fixture.session.pairVerify, "sessionSecretsRetired") }
            reparer.start()
            assertFalse(retirementDone.await(50, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            borrower.join(3_000)
            retire.join(3_000)
            reparer.join(3_000)
        }
        try {
            assertFalse(borrower.isAlive)
            assertFalse(retire.isAlive)
            assertFalse(reparer.isAlive)
            assertTrue(m1Failure.get() is IllegalStateException)
            assertNull(fixture.session.pairVerify.shared)
            assertNull(fixture.session.pairVerify.verifiedGeneration)
            assertNull(lease.withPskFor(fixture.session) { it.size })
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun directTerminalClearFromBorrowerDefersWipeUntilItsFinallyBlock() {
        val fixture = fixture()
        try {
            assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
            markControlAuthenticated(fixture.session)
            val lease = fixture.session.claimPairTlsPskLease()!!
            lateinit var borrowed: ByteArray
            lease.withPskFor(fixture.session) { psk ->
                borrowed = psk
                assertThrows(IllegalStateException::class.java) {
                    fixture.session.pairVerify.clearSessionSecrets()
                }
                assertNull(fixture.session.pairVerify.shared)
            }
            assertTrue(borrowed.all { it == 0.toByte() })
            assertNull(privateField<ByteArray?>(fixture.session.pairVerify, "sharedSecret"))
            assertNull(lease.withPskFor(fixture.session) { it.size })
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun ownedTransportIsRegisteredBeforeBorrowEndsAndLeaseCloseWipesItsCopy() {
        val fixture = fixture()
        try {
            assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
            markControlAuthenticated(fixture.session)
            val lease = fixture.session.claimPairTlsPskLease()!!
            lateinit var borrowed: ByteArray
            val transport = lease.openOwnedTransportFor(fixture.session) { psk ->
                borrowed = psk
                TrackingTlsTransport(psk)
            }!!
            assertTrue(borrowed.all { it == 0.toByte() })
            assertTrue(transport.ownedPsk.any { it != 0.toByte() })
            assertNull("one session cannot provision another PSK transport", lease.withPskFor(fixture.session) { it.size })
            assertNull(fixture.session.claimPairTlsPskLease())

            lease.close()
            lease.close()
            assertEquals(1, transport.closeCalls.get())
            assertTrue(transport.ownedPsk.all { it == 0.toByte() })
            assertTrue(fixture.session.claimPairTlsPskLease() != null)
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun ownedTransportCloseFailureKeepsExactOwnerAndSecretsUntilRetry() {
        val fixture = fixture()
        val allowClose = AtomicBoolean(false)
        assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
        markControlAuthenticated(fixture.session)
        val lease = fixture.session.claimPairTlsPskLease()!!
        val transport = lease.openOwnedTransportFor(fixture.session) {
            TrackingTlsTransport(it, allowClose)
        }!!
        try {
            assertThrows(IOException::class.java) { fixture.session.close() }
            assertTrue(fixture.session.pairVerify.isVerified)
            assertTrue(transport.ownedPsk.any { it != 0.toByte() })
            assertTrue(transport.closeCalls.get() >= 1)
            assertNull(lease.withPskFor(fixture.session) { it.size })
            assertNull(fixture.session.claimPairTlsPskLease())
        } finally {
            allowClose.set(true)
            fixture.session.close()
        }
        assertTrue(transport.ownedPsk.all { it == 0.toByte() })
        assertFalse(fixture.session.pairVerify.isVerified)
    }

    @Test
    fun concurrentSessionCloseRetiresTransportCreatedAfterStoppingBegins() {
        val fixture = fixture()
        assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
        markControlAuthenticated(fixture.session)
        val lease = fixture.session.claimPairTlsPskLease()!!
        val constructing = CountDownLatch(1)
        val finishConstruction = CountDownLatch(1)
        val transport = AtomicReference<TrackingTlsTransport>()
        val opened = AtomicReference<TrackingTlsTransport?>()
        val openerFailure = AtomicReference<Throwable>()
        val closerFailure = AtomicReference<Throwable>()
        val opener = Thread({
            try {
                opened.set(lease.openOwnedTransportFor(fixture.session) { psk ->
                    constructing.countDown()
                    check(finishConstruction.await(3, TimeUnit.SECONDS))
                    TrackingTlsTransport(psk).also(transport::set)
                })
            } catch (error: Throwable) {
                openerFailure.set(error)
            }
        }, "psk-transport-construction")
        val closer = Thread({
            try {
                fixture.session.close()
            } catch (error: Throwable) {
                closerFailure.set(error)
            }
        }, "psk-transport-close")
        try {
            opener.start()
            assertTrue(constructing.await(1, TimeUnit.SECONDS))
            closer.start()
            await { fixture.session.isClosed }
        } finally {
            finishConstruction.countDown()
            opener.join(3_000)
            closer.join(3_000)
        }
        assertFalse(opener.isAlive)
        assertFalse(closer.isAlive)
        assertNull(openerFailure.get())
        assertNull(closerFailure.get())
        assertNull("a transport born after STOPPING is never published", opened.get())
        assertEquals(1, transport.get().closeCalls.get())
        assertTrue(transport.get().ownedPsk.all { it == 0.toByte() })
        assertNull(lease.withPskFor(fixture.session) { it.size })
    }

    @Test
    fun transportCloseMayReenterSessionAndLeaseCloseWithoutRetirementRecursion() {
        val fixture = fixture()
        assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
        markControlAuthenticated(fixture.session)
        val lease = fixture.session.claimPairTlsPskLease()!!
        val closeCalls = AtomicInteger()
        lateinit var ownedKey: ByteArray
        lease.openOwnedTransportFor(fixture.session) { psk ->
            ownedKey = psk.copyOf()
            Closeable {
                closeCalls.incrementAndGet()
                fixture.session.close()
                lease.close()
                ownedKey.fill(0)
            }
        }

        fixture.session.close()
        assertEquals(1, closeCalls.get())
        assertTrue(ownedKey.all { it == 0.toByte() })
        assertFalse(fixture.session.pairVerify.isVerified)
    }

    @Test
    fun blockedTransportCloseTimesOutButRetriesSameWorkerAndOwner() {
        val fixture = fixture(retirementJoinTimeoutMillis = 30)
        assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
        markControlAuthenticated(fixture.session)
        val lease = fixture.session.claimPairTlsPskLease()!!
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        lateinit var ownedKey: ByteArray
        lease.openOwnedTransportFor(fixture.session) { psk ->
            ownedKey = psk.copyOf()
            Closeable {
                closeCalls.incrementAndGet()
                closeEntered.countDown()
                check(releaseClose.await(3, TimeUnit.SECONDS))
                ownedKey.fill(0)
            }
        }
        try {
            val first = assertThrows(IOException::class.java) { fixture.session.close() }
            assertTrue(first.message.orEmpty().contains("Pair-TLS-PSK transport"))
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS))
            assertEquals(1, closeCalls.get())
            assertTrue(fixture.session.pairVerify.isVerified)
            assertThrows(IOException::class.java) { fixture.session.close() }
            assertEquals("retry must not start a second close worker", 1, closeCalls.get())
        } finally {
            releaseClose.countDown()
            fixture.session.close()
        }
        assertEquals(1, closeCalls.get())
        assertTrue(ownedKey.all { it == 0.toByte() })
        assertFalse(fixture.session.pairVerify.isVerified)
    }

    @Test
    fun stoppingAfterMediaRetirementFailureBlocksLeaseWhileSecretsRemainRetryOwned() {
        val allowMediaClose = AtomicBoolean(false)
        val fixture = fixture(object : AirPlayMediaHandler {
            override fun onSessionClosed(session: AirPlaySession) {
                if (!allowMediaClose.get()) throw IOException("media retirement pending")
            }
        })
        assertM4(fixture.session.pairVerify.handle(beginVerify(fixture).m3))
        markControlAuthenticated(fixture.session)
        val lease = fixture.session.claimPairTlsPskLease()!!
        try {
            assertThrows(IOException::class.java) { fixture.session.close() }
            assertTrue("the existing teardown contract retains secrets for retry", fixture.session.pairVerify.isVerified)
            assertNull(lease.withPskFor(fixture.session) { it.size })
            assertNull(fixture.session.claimPairTlsPskLease())
        } finally {
            allowMediaClose.set(true)
            fixture.session.close()
        }
        assertFalse(fixture.session.pairVerify.isVerified)
    }

    @Test
    fun addingLeaseDoesNotConfigureVideoPlayback() {
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayUltraConfiguration(configuredFeatures = setOf(AirPlayFeature.VIDEO_PLAYBACK))
        }
    }

    private data class Fixture(
        val session: AirPlaySession,
        val identity: AirPlayIdentity,
        val controllerKeys: AirPlayCrypto.Ed25519KeyPair,
    )

    private data class PendingVerify(val m3: ByteArray)

    private fun fixture(
        media: AirPlayMediaHandler = object : AirPlayMediaHandler {},
        retirementJoinTimeoutMillis: Long = 2_000,
    ): Fixture {
        val identity = AirPlayIdentity.generate()
        val controllerKeys = AirPlayCrypto.ed25519Generate()
        val pairings = PairingStore().apply { save(CONTROLLER_ID, controllerKeys.publicKey) }
        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(
                deviceName = "lease-test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = identity,
            pairings = pairings,
            mfi = null,
            listener = object : AirPlaySessionListener {},
            media = media,
            retirementJoinTimeoutMillis = retirementJoinTimeoutMillis,
        )
        return Fixture(session, identity, controllerKeys)
    }

    private fun beginVerify(fixture: Fixture, corruptSignature: Boolean = false): PendingVerify {
        val client = AirPlayCrypto.x25519Generate()
        val m1 = Tlv8Codec.encode(listOf(
            Tlv8Item(TYPE_STATE, byteArrayOf(1)),
            Tlv8Item(TYPE_PUBLIC_KEY, client.publicKey),
        ))
        val m2 = Tlv8Codec.decode(fixture.session.pairVerify.handle(m1))
        assertArrayEquals(byteArrayOf(2), m2[TYPE_STATE])
        val serverPublic = checkNotNull(m2[TYPE_PUBLIC_KEY])
        val rawEcdh = AirPlayCrypto.x25519Shared(client.privateKey, serverPublic)
        client.privateKey.fill(0)
        val verifyKey = AirPlayCrypto.hkdfSha512(
            rawEcdh,
            "Pair-Verify-Encrypt-Salt".toByteArray(Charsets.US_ASCII),
            "Pair-Verify-Encrypt-Info".toByteArray(Charsets.US_ASCII),
        )
        rawEcdh.fill(0)
        val controllerId = CONTROLLER_ID.toByteArray(Charsets.UTF_8)
        val signature = AirPlayCrypto.ed25519Sign(
            fixture.controllerKeys.privateKey,
            concatBytes(client.publicKey, controllerId, serverPublic),
        )
        if (corruptSignature) signature[0] = (signature[0].toInt() xor 1).toByte()
        val sub = Tlv8Codec.encode(listOf(
            Tlv8Item(TYPE_IDENTIFIER, controllerId),
            Tlv8Item(TYPE_SIGNATURE, signature),
        ))
        val sealed = AirPlayCrypto.chachaSeal(verifyKey, AirPlayCrypto.nonceLabel("PV-Msg03"), sub)
        verifyKey.fill(0)
        return PendingVerify(Tlv8Codec.encode(listOf(
            Tlv8Item(TYPE_STATE, byteArrayOf(3)),
            Tlv8Item(TYPE_ENCRYPTED_DATA, sealed),
        )))
    }

    private fun markControlAuthenticated(session: AirPlaySession) {
        val keys = checkNotNull(session.pairVerify.controlKeys)
        try {
            session.cipher = ControlCipher(keys.readKey, keys.writeKey)
        } finally {
            keys.readKey.fill(0)
            keys.writeKey.fill(0)
        }
        forceControlFlags(session, checkNotNull(session.pairVerify.verifiedGeneration))
    }

    private fun forceControlFlags(session: AirPlaySession, generation: Any) {
        if (session.cipher == null) {
            session.cipher = ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        }
        privateField<AtomicBoolean>(session, "authenticatedControl").set(true)
        session.javaClass.getDeclaredField("authenticatedPairVerifyGeneration").run {
            isAccessible = true
            set(session, generation)
        }
    }

    private fun assertM4(response: ByteArray) {
        val result = Tlv8Codec.decode(response)
        assertArrayEquals(byteArrayOf(4), result[TYPE_STATE])
        assertNull(result[TYPE_ERROR])
    }

    private inline fun <reified T> privateField(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(target) as T
        }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(condition())
    }

    private class TrackingTlsTransport(
        psk: ByteArray,
        private val allowClose: AtomicBoolean = AtomicBoolean(true),
    ) : Closeable {
        val ownedPsk = psk.copyOf()
        val closeCalls = AtomicInteger()

        override fun close() {
            closeCalls.incrementAndGet()
            if (!allowClose.get()) throw IOException("TLS owner close pending")
            ownedPsk.fill(0)
        }
    }

    private companion object {
        const val CONTROLLER_ID = "lease-controller"
        const val TYPE_IDENTIFIER = 0x01
        const val TYPE_PUBLIC_KEY = 0x03
        const val TYPE_ENCRYPTED_DATA = 0x05
        const val TYPE_STATE = 0x06
        const val TYPE_ERROR = 0x07
        const val TYPE_SIGNATURE = 0x0a
    }
}
