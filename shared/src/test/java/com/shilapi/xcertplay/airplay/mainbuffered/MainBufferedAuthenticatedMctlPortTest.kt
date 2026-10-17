package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.ControlCipher
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBufferedAuthenticatedMctlPortTest {
    @Test
    fun generationRevokedAfterEarlyLivenessCheckCannotDispatchControlSideEffect() {
        val beforeLease = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = Fixture(hooks = MainBufferedMctlTcpHooks(
            beforeExecutionLeaseAcquire = {
                beforeLease.countDown()
                release.await(2, TimeUnit.SECONDS)
            },
        ))
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        val clientCipher = fixture.clientCipher()
        try {
            port.commitDeferred()
            port.activateWhen(gate)
            gate.open()
            Socket(LOCAL, port.localAddress.port).use { socket ->
                socket.getOutputStream().write(clientCipher.encrypt(request()))
                socket.getOutputStream().flush()
                assertTrue(beforeLease.await(2, TimeUnit.SECONDS))
                fixture.preparer.revokePairVerifyGeneration()
                release.countDown()
                awaitState(port, MainBufferedMctlTcpState.FAILED)
                assertEquals(0, fixture.calls.get())
            }
        } finally {
            release.countDown()
            port.close()
            clientCipher.close()
            gate.close()
        }
    }

    @Test
    fun pairVerifyRevocationWaitsForPriorLeasedSideEffectAndRejectsEveryLaterOne() {
        val lease = MainBufferedPairVerifyExecutionLease(7L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val effects = AtomicInteger()
        val live = AtomicBoolean(true)
        val action = Thread {
            lease.execute(7L, { live.get() }) {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                effects.incrementAndGet()
            }
        }
        action.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val retire = Thread {
            lease.revokeAndRetire { live.set(false) }
            revoked.countDown()
        }
        retire.start()
        assertFalse(revoked.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        action.join(2_000)
        retire.join(2_000)
        assertFalse(action.isAlive)
        assertFalse(retire.isAlive)
        assertEquals(1, effects.get())
        assertFalse(live.get())
        assertThrows(IllegalStateException::class.java) {
            lease.execute(7L, { live.get() }) { effects.incrementAndGet() }
        }
        assertEquals(1, effects.get())
    }

    @Test
    fun v27SetupPublishesIndependentPortAndOpensOnlyAfterCompleteResponseFlush() {
        val fixture = Fixture(seedBits = -1L)
        val owner = setupOwner(fixture)
        val clientCipher = fixture.clientCipher()
        try {
            val transaction = owner.prepare(setupRequest(
                java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE),
            ))
            val stream = (transaction.responseBody["streams"] as List<*>).single() as Map<*, *>
            val connections = stream["streamConnections"] as Map<*, *>
            val mctl = connections["streamConnectionTypeMediaDataControl"] as Map<*, *>
            val apap = connections["streamConnectionTypeAPAP"] as Map<*, *>
            val mctlPort = (mctl["streamConnectionKeyPort"] as Long).toInt()
            assertTrue(mctlPort != (apap["streamConnectionKeyPort"] as Long).toInt())
            Socket(LOCAL, mctlPort).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(clientCipher.encrypt(request()))
                socket.getOutputStream().flush()
                assertFalse(fixture.dispatched.await(100, TimeUnit.MILLISECONDS))
                transaction.flushResponseAndCommit { body ->
                    assertTrue(body === transaction.responseBody)
                    assertEquals(MainBufferedV27SetupState.FLUSHING, transaction.state())
                    assertEquals(0, fixture.calls.get())
                }
                val reply = checkNotNull(clientCipher.decryptFrame(readFrame(socket))).data
                assertEquals("rply", MainBufferedApTransportControlHeader.view(
                    ApTransportPackageCodec.decode(reply),
                ).packageTypeFourCcOrNull())
                assertEquals(1, fixture.calls.get())
            }
        } finally {
            owner.close()
            fixture.preparer.close()
            clientCipher.close()
        }
    }

    @Test
    fun interruptedMctlWorkerCannotProduceCommittedDualPortResponse() {
        val fixture = Fixture(hooks = MainBufferedMctlTcpHooks(
            workerStarter = { worker -> worker.start(); worker.interrupt() },
        ))
        val owner = setupOwner(fixture)
        try {
            val transaction = owner.prepare(setupRequest(17L))
            assertThrows(IllegalStateException::class.java) {
                transaction.flushResponseAndCommit { }
            }
            assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
            assertTrue(transaction.tcpSnapshot().serverClosed)
            assertEquals(0, fixture.calls.get())
        } finally {
            owner.close()
            fixture.preparer.close()
        }
    }

    @Test
    fun mctlAuthenticationFailureAfterCommitRetiresPairedApapPort() {
        val fixture = Fixture()
        val owner = setupOwner(fixture)
        val clientCipher = fixture.clientCipher()
        try {
            val transaction = owner.prepare(setupRequest(17L))
            val stream = (transaction.responseBody["streams"] as List<*>).single() as Map<*, *>
            val connections = stream["streamConnections"] as Map<*, *>
            val mctl = connections["streamConnectionTypeMediaDataControl"] as Map<*, *>
            val mctlPort = (mctl["streamConnectionKeyPort"] as Long).toInt()
            transaction.flushResponseAndCommit { }
            assertEquals(MainBufferedV27SetupState.COMMITTED, transaction.state())
            Socket(LOCAL, mctlPort).use { socket ->
                val bad = clientCipher.encrypt(request())
                bad[bad.lastIndex] = (bad.last().toInt() xor 1).toByte()
                socket.getOutputStream().write(bad)
                socket.getOutputStream().flush()
                awaitTransactionState(transaction, MainBufferedV27SetupState.CLOSED)
                assertTrue(transaction.tcpSnapshot().serverClosed)
                assertEquals(0, fixture.calls.get())
            }
        } finally {
            owner.close()
            fixture.preparer.close()
            clientCipher.close()
        }
    }

    @Test
    fun closeWhileDispatchLivenessCheckIsBlockedPreventsHandlerSideEffect() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = Fixture(hooks = MainBufferedMctlTcpHooks(
            beforeDispatchLivenessCheck = {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            },
        ))
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        val clientCipher = fixture.clientCipher()
        val closeFailure = AtomicReference<Throwable?>()
        try {
            port.commitDeferred()
            port.activateWhen(gate)
            gate.open()
            Socket(LOCAL, port.localAddress.port).use { socket ->
                socket.getOutputStream().write(clientCipher.encrypt(request()))
                socket.getOutputStream().flush()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val closer = Thread({
                    try { port.close() } catch (problem: Throwable) { closeFailure.set(problem) }
                }, "mctl-test-close")
                closer.start()
                awaitState(port, MainBufferedMctlTcpState.CLOSING)
                release.countDown()
                closer.join(2_000)
                assertFalse(closer.isAlive)
                assertEquals(null, closeFailure.get())
                assertEquals(0, fixture.calls.get())
                assertEquals(MainBufferedMctlTcpState.CLOSED, port.snapshot().state)
            }
        } finally {
            release.countDown()
            port.close()
            clientCipher.close()
            gate.close()
        }
    }

    @Test
    fun successfulCloseWaitsForAlreadyClaimedTerminalCallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        val callbackCalls = AtomicInteger()
        val closeFailure = AtomicReference<Throwable?>()
        val fixture = Fixture()
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        val clientCipher = fixture.clientCipher()
        port.onTerminalFailure {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            callbackCalls.incrementAndGet()
        }
        try {
            port.commitDeferred()
            port.activateWhen(gate)
            gate.open()
            Socket(LOCAL, port.localAddress.port).use { socket ->
                val bad = clientCipher.encrypt(request())
                bad[bad.lastIndex] = (bad.last().toInt() xor 1).toByte()
                socket.getOutputStream().write(bad)
                socket.getOutputStream().flush()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val closer = Thread({
                    try { port.close() } catch (problem: Throwable) { closeFailure.set(problem) }
                    finally { closeDone.countDown() }
                }, "mctl-test-close-after-fail")
                closer.start()
                assertFalse(closeDone.await(100, TimeUnit.MILLISECONDS))
                release.countDown()
                assertTrue(closeDone.await(2, TimeUnit.SECONDS))
                closer.join(2_000)
                assertEquals(null, closeFailure.get())
                assertEquals(1, callbackCalls.get())
                assertEquals(MainBufferedMctlTcpState.CLOSED, port.snapshot().state)
            }
        } finally {
            release.countDown()
            port.close()
            clientCipher.close()
            gate.close()
        }
        assertEquals(1, callbackCalls.get())
    }

    @Test
    fun unsignedSeedIsIndependentOfApapAndGateDefersAuthenticatedDispatch() {
        val fixture = Fixture(seedBits = -1L)
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        assertTrue(checkNotNull(fixture.suppliedSecretCopy).all { it == 0.toByte() })
        // Independent OpenSSL HKDF-SHA512 vector for secret 01..20 and UINT64_MAX seed.
        // This checks salt/label/direction implementation, not an on-device iPhone capture.
        val clientCipher = ControlCipher(
            readKey = hex("3c874ea519cfe32b1c74eafceaaeb8880c1d05e5de97f7def386949a678b8876"),
            writeKey = hex("e77b37ef6054c0d1ffc2ec5ad848331632327daf9b412c5e2ebfe3f8fe1957ff"),
        )
        try {
            Socket(LOCAL, port.localAddress.port).use { socket ->
                socket.soTimeout = 2_000
                port.commitDeferred()
                port.activateWhen(gate)
                val request = request(groupId = 17L, replyToken = -1L)
                socket.getOutputStream().write(clientCipher.encrypt(request))
                socket.getOutputStream().flush()
                assertFalse("mctl must not dispatch before complete SETUP flush",
                    fixture.dispatched.await(100, TimeUnit.MILLISECONDS))
                assertEquals(0, port.snapshot().authenticatedPackages)

                gate.open()
                val encryptedReply = readFrame(socket)
                val reply = checkNotNull(clientCipher.decryptFrame(encryptedReply)).data
                val packageValue = ApTransportPackageCodec.decode(reply)
                val header = MainBufferedApTransportControlHeader.view(packageValue)
                assertTrue(fixture.dispatched.await(1, TimeUnit.SECONDS))
                assertEquals("rply", header.packageTypeFourCcOrNull())
                assertEquals("loud", header.messageTypeFourCcOrNull())
                assertEquals(17L, header.groupId)
                assertEquals(-1L, header.replyToken)
                assertEquals(emptyMap<String, Any>(), BplistCodec.decode(packageValue.body))
                assertEquals(1, fixture.calls.get())
            }
        } finally {
            port.close()
            clientCipher.close()
            gate.close()
        }
        assertEquals(MainBufferedMctlTcpState.CLOSED, port.snapshot().state)
    }

    @Test
    fun fragmentedCiphertextAndPackageAcrossTwoAuthenticatedFramesHaveOneReply() {
        val fixture = Fixture()
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        val clientCipher = fixture.clientCipher()
        try {
            port.commitDeferred()
            port.activateWhen(gate)
            gate.open()
            Socket(LOCAL, port.localAddress.port).use { socket ->
                socket.soTimeout = 2_000
                val packageBytes = request()
                val split = packageBytes.size / 2
                val first = clientCipher.encrypt(packageBytes.copyOfRange(0, split))
                val second = clientCipher.encrypt(packageBytes.copyOfRange(split, packageBytes.size))
                socket.getOutputStream().write(first, 0, 1)
                socket.getOutputStream().flush()
                socket.getOutputStream().write(first, 1, first.size - 1)
                socket.getOutputStream().write(second)
                socket.getOutputStream().flush()

                val reply = checkNotNull(clientCipher.decryptFrame(readFrame(socket))).data
                assertEquals("rply", MainBufferedApTransportControlHeader.view(
                    ApTransportPackageCodec.decode(reply),
                ).packageTypeFourCcOrNull())
                assertEquals(1, fixture.calls.get())
                assertEquals(1, port.snapshot().authenticatedPackages)
            }
        } finally {
            port.close()
            clientCipher.close()
            gate.close()
        }
    }

    @Test
    fun validControlChannelMayRemainIdleLongerThanPartialFrameDeadline() {
        val fixture = Fixture(limits = MainBufferedMctlTcpLimits(
            frameDeadlineMillis = 100,
            readPollMillis = 20,
        ))
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        val clientCipher = fixture.clientCipher()
        try {
            port.commitDeferred()
            port.activateWhen(gate)
            gate.open()
            Socket(LOCAL, port.localAddress.port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(clientCipher.encrypt(request(replyToken = 1L)))
                socket.getOutputStream().flush()
                checkNotNull(clientCipher.decryptFrame(readFrame(socket)))

                Thread.sleep(250)
                assertEquals(MainBufferedMctlTcpState.ADMITTED, port.snapshot().state)
                socket.getOutputStream().write(clientCipher.encrypt(request(replyToken = 2L)))
                socket.getOutputStream().flush()
                val second = checkNotNull(clientCipher.decryptFrame(readFrame(socket))).data
                assertEquals(2L, MainBufferedApTransportControlHeader.view(
                    ApTransportPackageCodec.decode(second),
                ).replyToken)
                assertEquals(2, fixture.calls.get())
            }
        } finally {
            port.close()
            clientCipher.close()
            gate.close()
        }
    }

    @Test
    fun tamperedCiphertextNeverDispatchesAndRetiresPort() {
        val fixture = Fixture()
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        val clientCipher = fixture.clientCipher()
        try {
            port.commitDeferred()
            port.activateWhen(gate)
            gate.open()
            val receipt = checkNotNull(port.awaitAdmissionReceipt(1_000))
            Socket(LOCAL, port.localAddress.port).use { socket ->
                val bad = clientCipher.encrypt(request())
                bad[bad.lastIndex] = (bad.last().toInt() xor 1).toByte()
                socket.getOutputStream().write(bad)
                socket.getOutputStream().flush()
                awaitState(port, MainBufferedMctlTcpState.FAILED)
                assertEquals(0, fixture.calls.get())
                assertTrue(port.snapshot().serverClosed)
                assertFalse(port.commitIfAdmissionCurrent(receipt) {
                    throw AssertionError("failed mctl must not commit")
                })
            }
        } finally {
            port.close()
            clientCipher.close()
            gate.close()
        }
    }

    @Test
    fun authenticatedButInvalidCommandNeverReachesHandler() {
        val fixture = Fixture()
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        val clientCipher = fixture.clientCipher()
        try {
            port.commitDeferred()
            port.activateWhen(gate)
            gate.open()
            Socket(LOCAL, port.localAddress.port).use { socket ->
                val invalid = request().copyOf().also { encoded ->
                    // APTransport message type is at header offset 16; the AEAD remains valid.
                    putU32Be(encoded, 16, fourCc("zzzz"))
                }
                socket.getOutputStream().write(clientCipher.encrypt(invalid))
                socket.getOutputStream().flush()
                awaitState(port, MainBufferedMctlTcpState.FAILED)
                assertEquals(0, fixture.calls.get())
                assertEquals(0, port.snapshot().authenticatedPackages)
            }
        } finally {
            port.close()
            clientCipher.close()
            gate.close()
        }
    }

    @Test
    fun cryptorKeyUpdateFailsClosedWithoutDedicatedRotationHandler() {
        val fixture = Fixture()
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        val clientCipher = fixture.clientCipher()
        try {
            port.commitDeferred()
            port.activateWhen(gate)
            gate.open()
            Socket(LOCAL, port.localAddress.port).use { socket ->
                socket.getOutputStream().write(clientCipher.encrypt(cryptorUpdate()))
                socket.getOutputStream().flush()
                awaitState(port, MainBufferedMctlTcpState.FAILED)
                assertEquals(0, fixture.calls.get())
                assertEquals(0, port.snapshot().authenticatedPackages)
            }
        } finally {
            port.close()
            clientCipher.close()
            gate.close()
        }
    }

    @Test
    fun cryptorKeyUpdateUsesOnlyDeliberatelySuppliedRotationHandler() {
        val rotations = AtomicInteger()
        val fixture = Fixture(rotationHandler = MainBufferedMctlCryptorRotationHandler {
            rotations.incrementAndGet()
            MainBufferedControlDispatchReply(mapOf("status" to 0L))
        })
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        val clientCipher = fixture.clientCipher()
        try {
            port.commitDeferred()
            port.activateWhen(gate)
            gate.open()
            Socket(LOCAL, port.localAddress.port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(clientCipher.encrypt(cryptorUpdate()))
                socket.getOutputStream().flush()
                val reply = checkNotNull(clientCipher.decryptFrame(readFrame(socket))).data
                assertEquals("upck", MainBufferedApTransportControlHeader.view(
                    ApTransportPackageCodec.decode(reply),
                ).messageTypeFourCcOrNull())
                assertEquals(1, rotations.get())
                assertEquals(0, fixture.calls.get())
            }
        } finally {
            port.close()
            clientCipher.close()
            gate.close()
        }
    }

    @Test
    fun wrongPeerExhaustsCandidateLimitWithoutUsingKey() {
        val fixture = Fixture(
            expectedPeer = InetAddress.getByName("127.0.0.2"),
            limits = MainBufferedMctlTcpLimits(maximumCandidates = 1),
        )
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        try {
            port.commitDeferred()
            port.activateWhen(gate)
            gate.open()
            Socket(LOCAL, port.localAddress.port).use { socket ->
                socket.getOutputStream().write(byteArrayOf(0, 0))
                socket.getOutputStream().flush()
            }
            awaitState(port, MainBufferedMctlTcpState.FAILED)
            assertEquals(1, port.snapshot().candidateCount)
            assertEquals(0, fixture.calls.get())
        } finally {
            port.close()
            gate.close()
        }
    }

    @Test
    fun staleSessionCannotPrepareAndRevocationStopsCommittedAdmission() {
        val live = AtomicBoolean(false)
        val fixture = Fixture(live = live)
        assertThrows(IllegalStateException::class.java) { fixture.prepare() }
        assertThrows(IllegalStateException::class.java) { fixture.prepare() }

        live.set(true)
        val second = Fixture(live = live)
        val gate = MainBufferedAdmissionGate()
        val port = second.prepare()
        try {
            port.commitDeferred()
            port.activateWhen(gate)
            live.set(false)
            gate.open()
            awaitState(port, MainBufferedMctlTcpState.FAILED)
            assertEquals(0, second.calls.get())
        } finally {
            port.close()
            gate.close()
        }
    }

    @Test
    fun exactGenerationPeerAndBindCannotBeSubstituted() {
        val fixture = Fixture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.preparer.prepare(
                MainBufferedMctlPortRequest(
                    generation = 8L,
                    bindAddress = LOCAL,
                    expectedPeer = LOCAL,
                    streamConnectionIdBits = 1L,
                    encryptionSeedBits = 1L,
                ),
            )
        }
        assertThrows(IllegalStateException::class.java) { fixture.prepare() }
    }

    @Test
    fun incompleteFirstPackageTimesOutAfterSocketAcceptance() {
        val fixture = Fixture(limits = MainBufferedMctlTcpLimits(
            firstPackageDeadlineMillis = 150,
            readPollMillis = 20,
        ))
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        val cipher = fixture.clientCipher()
        try {
            port.commitDeferred()
            port.activateWhen(gate)
            gate.open()
            Socket(LOCAL, port.localAddress.port).use { socket ->
                val incomplete = cipher.encrypt(request().copyOfRange(0, 10))
                socket.getOutputStream().write(incomplete)
                socket.getOutputStream().flush()
                awaitState(port, MainBufferedMctlTcpState.FAILED)
                assertEquals(0, fixture.calls.get())
            }
        } finally {
            port.close()
            cipher.close()
            gate.close()
        }
    }

    @Test
    fun rollbackAfterDeferredCommitNeverAdmitsCandidate() {
        val fixture = Fixture()
        val gate = MainBufferedAdmissionGate()
        val port = fixture.prepare()
        port.commitDeferred()
        port.activateWhen(gate)
        port.rollback()
        assertEquals(MainBufferedMctlTcpState.CLOSED, port.snapshot().state)
        assertThrows(IllegalStateException::class.java) { gate.open() }
        assertEquals(0, fixture.calls.get())
    }

    private fun setupOwner(fixture: Fixture): MainBufferedV27SetupSession =
        MainBufferedV27SetupSession.create(
            generation = 7L,
            bindAddress = LOCAL,
            expectedPeer = LOCAL,
            allocatedStreamId = 77L,
            acceptedFormat = MainBufferedV27AcceptedFormat(
                contentType = fourCc("alac").toLong(),
                samplesPerFrame = 352L,
                audioFormat = MainBufferedAudioFormatBits.ALAC_44100_16_STEREO,
            ),
            audioBufferSize = 4096L,
            portMode = MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            packetConsumer = MainBufferedIngressPacketConsumer { _, _ -> },
            mctlPortPreparer = fixture.preparer,
        )

    private fun setupRequest(seed: Any): Map<String, Any?> = linkedMapOf(
        "type" to 103L,
        "streamConnectionID" to 1L,
        "supportsDynamicStreamID" to true,
        "ct" to "alac",
        "spf" to 352L,
        "audioFormat" to MainBufferedAudioFormatBits.ALAC_44100_16_STEREO,
        "audioFormatIndex" to 18L,
        "isMedia" to true,
        "audioType" to "media",
        "packetFormatAPAP" to true,
        "shk" to ByteArray(32) { 0x55.toByte() },
        "streamConnections" to mapOf(
            "streamConnectionTypeAPAP" to emptyMap<String, Any>(),
            "streamConnectionTypeMediaDataControl" to mapOf(
                "streamConnectionKeyEncryptionSeed" to seed,
            ),
        ),
    )

    private fun awaitTransactionState(
        transaction: MainBufferedV27SetupTransaction,
        wanted: MainBufferedV27SetupState,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (transaction.state() != wanted && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(wanted, transaction.state())
    }

    private class Fixture(
        private val seedBits: Long = 17L,
        expectedPeer: InetAddress = LOCAL,
        val live: AtomicBoolean = AtomicBoolean(true),
        limits: MainBufferedMctlTcpLimits = MainBufferedMctlTcpLimits(),
        hooks: MainBufferedMctlTcpHooks = MainBufferedMctlTcpHooks(),
        rotationHandler: MainBufferedMctlCryptorRotationHandler? = null,
    ) {
        val calls = AtomicInteger()
        val dispatched = CountDownLatch(1)
        private val secret = ByteArray(32) { (it + 1).toByte() }
        var suppliedSecretCopy: ByteArray? = null
            private set
        val preparer = MainBufferedAuthenticatedMctlPortPreparer(
            sessionGeneration = 7L,
            bindAddress = LOCAL,
            expectedPeer = expectedPeer,
            borrowVerifiedSharedSecret = { generation ->
                if (generation == 7L) {
                    secret.copyOf().also { suppliedSecretCopy = it }.let { disposable ->
                        MainBufferedVerifiedSecretBorrow.takeOwnership(generation, disposable)
                    }
                } else null
            },
            isSessionCurrent = { generation -> generation == 7L && live.get() },
            dispatcher = MainBufferedApTransportControlDispatcher(
                MainBufferedControlHandler {
                    calls.incrementAndGet()
                    dispatched.countDown()
                    MainBufferedControlDispatchReply(emptyMap())
                },
            ),
            limits = limits,
            hooks = hooks,
            cryptorRotationHandler = rotationHandler,
        )
        private val peer = expectedPeer

        fun prepare(): MainBufferedAuthenticatedMctlPort = preparer.prepare(
            MainBufferedMctlPortRequest(
                generation = 7L,
                bindAddress = LOCAL,
                expectedPeer = peer,
                streamConnectionIdBits = 1L,
                encryptionSeedBits = seedBits,
            ),
        ) as MainBufferedAuthenticatedMctlPort

        fun clientCipher(): ControlCipher {
            val salt = ("DataStream-Salt" + java.lang.Long.toUnsignedString(seedBits))
                .toByteArray(Charsets.US_ASCII)
            return ControlCipher(
                readKey = AirPlayCrypto.hkdfSha512(secret, salt,
                    "DataStream-Input-Encryption-Key".toByteArray(Charsets.US_ASCII), 32),
                writeKey = AirPlayCrypto.hkdfSha512(secret, salt,
                    "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII), 32),
            )
        }
    }

    private fun request(groupId: Long = 1L, replyToken: Long = 2L): ByteArray {
        val template = ByteArray(32)
        putU32Be(template, 4, MainBufferedApTransportControlHeader.PACKAGE_TYPE_SYNC)
        putU64Be(template, 8, groupId)
        putU64Be(template, 20, replyToken)
        return ApTransportPackageCodec.create(
            messageType = fourCc("loud"),
            body = BplistCodec.encode(mapOf("loudnessNormalizationEnabled" to true)),
            uninterpretedHeader = template,
        ).encoded()
    }

    private fun cryptorUpdate(): ByteArray = ApTransportPackageCodec.create(
        messageType = fourCc("upck"),
        body = BplistCodec.encode(mapOf(
            "cryptorID" to 1L,
            "pkdEncryptionKey" to ByteArray(32) { 0x42.toByte() },
        )),
        uninterpretedHeader = ByteArray(32).also { header ->
            putU32Be(header, 4, MainBufferedApTransportControlHeader.PACKAGE_TYPE_SYNC)
        },
    ).encoded()

    private fun readFrame(socket: Socket): ByteArray {
        val header = ByteArray(2)
        readExactly(socket, header)
        val length = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8)
        val rest = ByteArray(length + 16)
        readExactly(socket, rest)
        return header + rest
    }

    private fun readExactly(socket: Socket, target: ByteArray) {
        var received = 0
        while (received < target.size) {
            val n = socket.getInputStream().read(target, received, target.size - received)
            if (n < 0) throw AssertionError("unexpected EOF")
            received += n
        }
    }

    private fun awaitState(
        port: MainBufferedAuthenticatedMctlPort,
        wanted: MainBufferedMctlTcpState,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (port.snapshot().state != wanted && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(wanted, port.snapshot().state)
    }

    private fun fourCc(value: String): Int = value.toByteArray(Charsets.US_ASCII)
        .fold(0) { result, byte -> result shl 8 or (byte.toInt() and 0xff) }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) target[offset + index] =
            (value ushr (24 - index * 8)).toByte()
    }

    private fun putU64Be(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) target[offset + index] =
            (value ushr (56 - index * 8)).toByte()
    }

    private companion object {
        val LOCAL: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
