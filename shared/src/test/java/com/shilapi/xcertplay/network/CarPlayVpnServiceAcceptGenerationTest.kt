package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlaySessionRuntimeBinding
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayVpnServiceAcceptGenerationTest {
    @Test
    fun liveEndpointQueryRejectsStaleAuthorityAndDeadPrimaryWithImmutableSnapshot() {
        val primary = InetAddress.getByName("::1")
        val secondary = InetAddress.getByName("127.0.0.1")
        val current = CarPlayVpnService.AttachmentToken(61)
        val stale = CarPlayVpnService.AttachmentToken(60)
        val authority = CarPlayVpnService.AttachmentAuthority()
        val liveOwners = mutableListOf(primary, secondary)

        val snapshot = requireNotNull(
            liveAirPlayAddressesForToken(
                token = current,
                currentGeneration = 61,
                active = true,
                authority = authority,
                primaryAddress = primary,
                liveAddresses = liveOwners,
            ),
        )
        assertEquals(listOf(primary, secondary), snapshot)
        liveOwners.clear()
        assertEquals(listOf(primary, secondary), snapshot)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot as MutableList<InetAddress>).clear()
        }
        assertNull(
            liveAirPlayAddressesForToken(
                stale, 61, true, authority, primary, listOf(primary, secondary),
            ),
        )
        assertNull(
            liveAirPlayAddressesForToken(
                current, 61, false, authority, primary, listOf(primary, secondary),
            ),
        )
        assertNull(
            liveAirPlayAddressesForToken(
                current, 61, true, authority, primary, listOf(secondary),
            ),
        )
        assertEquals(
            listOf(primary),
            liveAirPlayAddressesForToken(current, 61, true, authority, primary, listOf(primary)),
        )
        authority.cancel()
        assertNull(
            liveAirPlayAddressesForToken(
                current, 61, true, authority, primary, listOf(primary, secondary),
            ),
        )
    }

    @Test
    fun closedPrimaryIsImmediatelyAbsentFromLiveListenerQuery() {
        val primary = InetAddress.getByName("::1")
        val authority = CarPlayVpnService.AttachmentAuthority()
        val token = CarPlayVpnService.AttachmentToken(62)
        val listeners = ExactAirPlayListenerSet(Any()) { _, server ->
            try {
                server.accept().close()
            } catch (error: SocketException) {
                if (!server.isClosed) throw error
            }
        }
        try {
            assertTrue(listeners.listen(62, primary, 0, optional = false))
            assertTrue(listeners.boundAddresses(61).isEmpty())
            assertTrue(listeners.activeSockets(61).isEmpty())
            assertEquals(
                listOf(primary),
                liveAirPlayAddressesForToken(
                    token, 62, true, authority, primary, listeners.boundAddresses(62),
                ),
            )
            listeners.activeSockets().single().close()
            assertNull(
                liveAirPlayAddressesForToken(
                    token, 62, true, authority, primary, listeners.boundAddresses(62),
                ),
            )
        } finally {
            listeners.closeSockets()
            listeners.retireWorkers(2_000)
        }
        assertFalse(listeners.hasOwners())
    }

    @Test
    fun terminatedPrimaryWorkerIsNotAdvertisedEvenIfSocketRemainsBound() {
        val primary = InetAddress.getByName("::1")
        val workers = mutableListOf<Thread>()
        val listeners = ExactAirPlayListenerSet(
            admissionLock = Any(),
            threadFactory = { body, name -> Thread(body, name).also { workers.add(it) } },
            acceptLoop = { _, _ -> },
        )
        try {
            assertTrue(listeners.listen(63, primary, 0, optional = false))
            workers.single().join(2_000)
            assertFalse(workers.single().isAlive)
            assertTrue(listeners.boundAddresses().isEmpty())
            assertTrue(listeners.activeSockets().isEmpty())
            assertNull(
                liveAirPlayAddressesForToken(
                    CarPlayVpnService.AttachmentToken(63),
                    63,
                    true,
                    CarPlayVpnService.AttachmentAuthority(),
                    primary,
                    listeners.boundAddresses(),
                ),
            )
        } finally {
            listeners.closeSockets()
            listeners.retireWorkers(2_000)
        }
        assertFalse(listeners.hasOwners())
    }

    @Test
    fun exactDualListenersAcceptBothAddressesAndRetireBothWorkers() {
        val accepted = Collections.synchronizedList(mutableListOf<InetAddress>())
        val acceptedTwice = CountDownLatch(2)
        val listeners = ExactAirPlayListenerSet(Any()) { generation, server ->
            assertEquals(31, generation)
            try {
                server.accept().use {
                    accepted.add(server.inetAddress)
                    acceptedTwice.countDown()
                }
            } catch (error: SocketException) {
                if (!server.isClosed) throw error
            }
        }
        val primary = InetAddress.getByName("::1")
        val secondary = InetAddress.getByName("127.0.0.1")
        try {
            assertTrue(listeners.listen(31, primary, 0, optional = false))
            assertTrue(listeners.listen(31, secondary, 0, optional = true))
            assertEquals(listOf(primary, secondary), listeners.boundAddresses())
            val servers = listeners.activeSockets()
            assertEquals(2, servers.size)
            servers.forEach { server ->
                Socket().use { client ->
                    client.connect(InetSocketAddress(server.inetAddress, server.localPort), 2_000)
                }
            }
            assertTrue(acceptedTwice.await(2, TimeUnit.SECONDS))
            assertEquals(setOf(primary, secondary), accepted.toSet())

            assertEquals(
                "current",
                selectAttachmentForAcceptedSocket(
                    acceptGeneration = 31,
                    currentGeneration = 31,
                    acceptingServer = servers[1],
                    currentServers = servers,
                    active = true,
                    currentAttachment = "current",
                ),
            )
        } finally {
            listeners.closeSockets()
            listeners.retireWorkers(2_000)
        }
        assertTrue(listeners.activeSockets().isEmpty())
        assertFalse(listeners.hasOwners())
    }

    @Test
    fun optionalSecondaryBindFailureLeavesPrimaryListeningAndUnadvertisedSecondary() {
        val listeners = ExactAirPlayListenerSet(Any()) { _, server ->
            try {
                server.accept().close()
            } catch (error: SocketException) {
                if (!server.isClosed) throw error
            }
        }
        val primary = InetAddress.getByName("127.0.0.1")
        try {
            assertTrue(listeners.listen(41, primary, 0, optional = false))
            val original = listeners.activeSockets().single()
            // A second exact bind to the same address and port fails without closing the first.
            assertFalse(listeners.listen(41, primary, original.localPort, optional = true))
            assertEquals(listOf(primary), listeners.boundAddresses())
            assertSame(original, listeners.activeSockets().single())
            Socket().use { client ->
                client.connect(InetSocketAddress(primary, original.localPort), 2_000)
            }
        } finally {
            listeners.closeSockets()
            listeners.retireWorkers(2_000)
        }
        assertFalse(listeners.hasOwners())
    }

    @Test
    fun dualListenerAdmissionRejectsOldGenerationAndUntrackedSocketIdentity() {
        val listeners = ExactAirPlayListenerSet(Any()) { _, server ->
            try {
                server.accept().close()
            } catch (error: SocketException) {
                if (!server.isClosed) throw error
            }
        }
        val primary = InetAddress.getByName("::1")
        val secondary = InetAddress.getByName("127.0.0.1")
        try {
            assertTrue(listeners.listen(51, primary, 0, optional = false))
            assertTrue(listeners.listen(51, secondary, 0, optional = true))
            val exactSockets = listeners.activeSockets()
            ServerSocket().use { stranger ->
                assertNull(
                    selectAttachmentForAcceptedSocket(
                        acceptGeneration = 50,
                        currentGeneration = 51,
                        acceptingServer = exactSockets[0],
                        currentServers = exactSockets,
                        active = true,
                        currentAttachment = "replacement",
                    ),
                )
                assertNull(
                    selectAttachmentForAcceptedSocket(
                        acceptGeneration = 51,
                        currentGeneration = 51,
                        acceptingServer = stranger,
                        currentServers = exactSockets,
                        active = true,
                        currentAttachment = "replacement",
                    ),
                )
            }
        } finally {
            listeners.closeSockets()
            listeners.retireWorkers(2_000)
        }
        assertFalse(listeners.hasOwners())
    }

    @Test
    fun controlSessionAdmissionBoundsWorkersWithoutEvictingExistingSessions() {
        fun allowed(live: Int, unauthenticated: Int) = canAdmitAirPlayControlSession(
            liveSessions = live,
            unauthenticatedSessions = unauthenticated,
            maxLiveSessions = 16,
            maxUnauthenticatedSessions = 4,
        )

        assertTrue(allowed(live = 0, unauthenticated = 0))
        assertTrue(allowed(live = 15, unauthenticated = 3))
        assertFalse(allowed(live = 4, unauthenticated = 4))
        assertFalse(allowed(live = 16, unauthenticated = 0))
        // An authenticated completion frees a pre-auth slot, but not a total worker slot.
        assertTrue(allowed(live = 4, unauthenticated = 3))
        assertThrows(IllegalArgumentException::class.java) { allowed(3, 4) }
    }

    @Test
    fun retirementLedgerDistinguishesCleanOldGenerationFromUnknownToken() {
        val ledger = AttachmentRetirementLedger()
        ledger.activate(7)
        assertEquals(
            CarPlayVpnService.AttachmentRetirementProof.RETIREMENT_UNPROVEN,
            ledger.proof(7),
        )
        ledger.clean(7)
        ledger.activate(8)

        assertEquals(
            CarPlayVpnService.AttachmentRetirementProof.RETIRED_CLEAN,
            ledger.proof(7),
        )
        assertEquals(
            CarPlayVpnService.AttachmentRetirementProof.RETIREMENT_UNPROVEN,
            ledger.proof(8),
        )
        assertEquals(
            CarPlayVpnService.AttachmentRetirementProof.RETIREMENT_UNPROVEN,
            ledger.proof(999),
        )
    }

    @Test
    fun unprovenGenerationCanNeverBeEvictedAsCleanHistoryGrows() {
        val ledger = AttachmentRetirementLedger()
        ledger.activate(1)
        repeat(140) { offset ->
            val generation = offset + 2
            ledger.activate(generation)
            ledger.clean(generation)
        }

        assertEquals(
            CarPlayVpnService.AttachmentRetirementProof.RETIREMENT_UNPROVEN,
            ledger.proof(1),
        )
        assertEquals(
            CarPlayVpnService.AttachmentRetirementProof.RETIRED_CLEAN,
            ledger.proof(141),
        )
    }

    @Test
    fun attachmentAuthorityAndTokenFailClosedAcrossControllerTeardown() {
        val authority = CarPlayVpnService.AttachmentAuthority()
        val current = CarPlayVpnService.AttachmentToken(12)
        val stale = CarPlayVpnService.AttachmentToken(11)

        assertTrue(authority.isValid())
        assertTrue(ownsAttachment(12, active = true, token = current))
        assertFalse(ownsAttachment(12, active = true, token = stale))
        assertFalse(ownsAttachment(12, active = false, token = current))

        authority.cancel()
        assertFalse(authority.isValid())
    }

    @Test
    fun runtimeBindingAndGenerationMustBeSuppliedAsOneExactPair() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()

        assertThrows(IllegalArgumentException::class.java) {
            withAirPlaySessionRuntimeAdmission(binding, null) { "unexpected" }
        }
        assertThrows(IllegalArgumentException::class.java) {
            withAirPlaySessionRuntimeAdmission(null, generation) { "unexpected" }
        }
    }

    @Test
    fun revokedRuntimeGenerationRejectsEveryNewSessionAdmission() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        var admissions = 0

        assertNull(
            withAirPlaySessionRuntimeAdmission(binding, generation) {
                admissions++
                "pending-must-not-run"
            },
        )
        val snapshot = binding.resolve(generation, runtimeConfig("resolved"))!!
        assertEquals(
            "admitted",
            withAirPlaySessionRuntimeAdmission(binding, generation) { exact ->
                admissions++
                assertSame(snapshot, exact)
                "admitted"
            },
        )

        assertTrue(binding.revoke(generation))
        assertNull(
            withAirPlaySessionRuntimeAdmission(binding, generation) {
                admissions++
                "revoked-must-not-run"
            },
        )
        assertEquals(1, admissions)
    }

    @Test
    fun staleAcceptedSocketCannotInheritReplacementAttachment() {
        ServerSocket().use { oldServer ->
            ServerSocket().use { newServer ->
                val replacementTransport = "wireless-replacement"

                assertNull(
                    selectAttachmentForAcceptedSocket(
                        acceptGeneration = 7,
                        currentGeneration = 9,
                        acceptingServer = oldServer,
                        currentServer = newServer,
                        active = true,
                        currentAttachment = replacementTransport,
                    ),
                )
                assertNull(
                    selectAttachmentForAcceptedSocket(
                        acceptGeneration = 9,
                        currentGeneration = 9,
                        acceptingServer = oldServer,
                        currentServer = newServer,
                        active = true,
                        currentAttachment = replacementTransport,
                    ),
                )
                assertEquals(
                    replacementTransport,
                    selectAttachmentForAcceptedSocket(
                        acceptGeneration = 9,
                        currentGeneration = 9,
                        acceptingServer = newServer,
                        currentServer = newServer,
                        active = true,
                        currentAttachment = replacementTransport,
                    ),
                )
            }
        }
    }

    @Test
    fun inactiveOrDetachedListenerCannotSelectAttachment() {
        ServerSocket().use { server ->
            assertNull(
                selectAttachmentForAcceptedSocket(
                    acceptGeneration = 3,
                    currentGeneration = 3,
                    acceptingServer = server,
                    currentServer = server,
                    active = false,
                    currentAttachment = "wired",
                ),
            )
            assertNull(
                selectAttachmentForAcceptedSocket<String>(
                    acceptGeneration = 3,
                    currentGeneration = 3,
                    acceptingServer = server,
                    currentServer = server,
                    active = true,
                    currentAttachment = null,
                ),
            )
        }
    }

    @Test
    fun duplicateTransportErrorsShareOneClaimAndRetryBeforeSingleNotification() {
        val attempts = AtomicInteger()
        val notifications = AtomicInteger()
        val retired = CountDownLatch(1)
        val coordinator = RetryableGenerationTeardown<String>(
            attemptRetirement = { generation ->
                assertEquals(17, generation)
                if (attempts.incrementAndGet() == 1) throw IOException("first release failed")
                RetryableGenerationTeardown.Outcome.RETIRED
            },
            onRetired = { target ->
                assertEquals("first-error", target)
                notifications.incrementAndGet()
                retired.countDown()
            },
            initialRetryMillis = 5,
            maxRetryMillis = 10,
        )

        assertTrue(coordinator.request(17, "first-error"))
        assertFalse(coordinator.request(17, "duplicate-error"))

        assertTrue(retired.await(2, TimeUnit.SECONDS))
        assertTrue(attempts.get() >= 2)
        assertEquals(1, notifications.get())
        assertFalse(coordinator.request(17, "late-duplicate"))
    }

    @Test
    fun workerStartErrorRemovesOnlyUnstartedClaimAndAllowsExactGenerationRetry() {
        val creations = AtomicInteger()
        val retired = CountDownLatch(1)
        val coordinator = RetryableGenerationTeardown<String>(
            attemptRetirement = { RetryableGenerationTeardown.Outcome.RETIRED },
            onRetired = { retired.countDown() },
            initialRetryMillis = 5,
            maxRetryMillis = 10,
            threadFactory = { body, name ->
                if (creations.incrementAndGet() == 1) {
                    object : Thread(body, name) {
                        override fun start() {
                            throw AssertionError("worker start failed")
                        }
                    }
                } else {
                    Thread(body, name)
                }
            },
        )

        try {
            coordinator.request(23, "first")
            throw AssertionError("start Error expected")
        } catch (failure: AssertionError) {
            assertTrue(failure.message.orEmpty().contains("worker start failed"))
        }

        assertTrue(coordinator.request(23, "retry"))
        assertTrue(retired.await(2, TimeUnit.SECONDS))
        assertEquals(2, creations.get())
    }

    private fun runtimeConfig(name: String): AirPlayConfig = AirPlayConfig(
        deviceName = name,
        deviceId = "00:11:22:33:44:55",
        btMac = "00:11:22:33:44:66",
        sourceVersion = "1.0",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
    )
}
