package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerReadiness
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CafRuntimeLifecycleTest {
    @Test
    fun everyControlSessionGetsFreshPluginAndChannelState() {
        val pluginSessions = CopyOnWriteArrayList<CafProtocolSession>()
        val provisioning = provisioning { session ->
            pluginSessions += session
            SchemaHandler(SyntheticCafTestFixture.tree())
        }
        val owner = provisioning.transferToRuntime()
        val first = owner.openSession()
        val second = owner.openSession()
        val firstStream = testStream(seed = 1)
        val secondStream = testStream(seed = 3)
        val firstChannel = requireNotNull(
            first.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        ).create(firstStream)
        val secondChannel = requireNotNull(
            second.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        ).create(secondStream)

        assertEquals(2, pluginSessions.size)
        assertNotSame(pluginSessions[0], pluginSessions[1])

        firstChannel.close()
        secondChannel.close()
        first.close()
        second.close()
        owner.close()
    }

    @Test
    fun staleProvisioningCloseCannotRevokeTransferredOwnerButOwnerCloseIsTerminal() {
        val provisioning = provisioning {
            SchemaHandler(SyntheticCafTestFixture.tree())
        }
        val owner = provisioning.transferToRuntime()

        assertThrows(IllegalStateException::class.java) { provisioning.transferToRuntime() }
        provisioning.close()
        assertTrue(owner.isLive())

        val child = owner.openSession()
        val staleDescriptor = requireNotNull(
            child.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        )
        val stream = testStream(seed = 5)
        val channel = staleDescriptor.create(stream) as CafProtocolSession

        owner.close()

        assertFalse(owner.isLive())
        assertEquals(RcsDataStreamHandlerReadiness.NOT_READY, staleDescriptor.probe().readiness)
        assertThrows(IllegalStateException::class.java) {
            channel.requestConfig(SyntheticCafTestFixture.PLUGIN_ID)
        }
        assertThrows(IllegalStateException::class.java) { owner.openSession() }
    }

    @Test
    fun failedPluginHandlerCloseRetainsExactChannelForRetry() {
        val closes = AtomicInteger()
        val provisioning = provisioning {
            object : CafSchemaBoundPluginHandler {
                override fun schemaSnapshot() = SyntheticCafTestFixture.tree()

                override fun close() {
                    if (closes.incrementAndGet() == 1) {
                        throw IllegalStateException("synthetic close failure")
                    }
                }
            }
        }
        val owner = provisioning.transferToRuntime()
        val child = owner.openSession()
        val stream = testStream(seed = 7)
        requireNotNull(child.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
            .create(stream)

        assertThrows(IllegalStateException::class.java) { child.close() }
        assertEquals(1, closes.get())
        assertFalse(child.isLive())

        child.close()
        assertEquals(2, closes.get())
        owner.close()
    }

    @Test
    fun constructorOrphanIsRetainedUntilItsHandlerActuallyCloses() {
        val tree = SyntheticCafTestFixture.tree()
        val closes = AtomicInteger()
        val registry = CafPluginRegistry.from(
            listOf(
                registration(pluginId = 101L, tree = tree) {
                    object : CafSchemaBoundPluginHandler {
                        override fun schemaSnapshot() = tree

                        override fun close() {
                            if (closes.incrementAndGet() <= 2) {
                                throw IllegalStateException("synthetic retained close failure")
                            }
                        }
                    }
                },
                registration(pluginId = 102L, tree = tree) {
                    throw IllegalStateException("synthetic second handler failure")
                },
            ),
        )
        val owner = CafRuntimeProvisioning.from(registry).transferToRuntime()
        val child = owner.openSession()
        val stream = testStream(seed = 9)

        assertThrows(CafProtocolSessionConstructionException::class.java) {
            requireNotNull(
                child.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
            ).create(stream)
        }
        // First attempt happened while the constructor unwound. The child owns the same cleanup.
        assertEquals(1, closes.get())
        assertThrows(IllegalStateException::class.java) { child.close() }
        assertEquals(2, closes.get())

        child.close()
        assertEquals(3, closes.get())
        owner.close()
        stream.close()
    }

    @Test
    fun closeRacingChannelCreationFailsClosedAndRetryRetiresTheCreatedHandler() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val provisioning = provisioning {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            object : CafSchemaBoundPluginHandler {
                override fun schemaSnapshot() = SyntheticCafTestFixture.tree()
                override fun close() {
                    closes.incrementAndGet()
                }
            }
        }
        val owner = provisioning.transferToRuntime()
        val child = owner.openSession()
        val stream = testStream(seed = 11)
        val descriptor = requireNotNull(
            child.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        )
        val creationFailure = AtomicReference<Throwable?>()
        val creating = Thread {
            try {
                descriptor.create(stream)
            } catch (failure: Throwable) {
                creationFailure.set(failure)
            }
        }
        creating.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertThrows(IllegalStateException::class.java) { owner.close() }
        release.countDown()
        creating.join(5_000)

        assertFalse(creating.isAlive)
        assertTrue(creationFailure.get() is IllegalStateException)
        assertEquals(1, closes.get())
        owner.close()
    }

    private fun provisioning(
        factory: (CafProtocolSession) -> CafPluginHandler,
    ): CafRuntimeProvisioning {
        val tree = SyntheticCafTestFixture.tree()
        return CafRuntimeProvisioning.from(
            CafPluginRegistry.from(
                listOf(
                    registration(
                        pluginId = SyntheticCafTestFixture.PLUGIN_ID,
                        tree = tree,
                        factory = factory,
                    ),
                ),
            ),
        )
    }

    private fun registration(
        pluginId: Long,
        tree: CafConfigTree,
        factory: (CafProtocolSession) -> CafPluginHandler,
    ) = CafPluginRegistration(
        pluginId = pluginId,
        protocolVersion = "1.0",
        clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
        handlerFactory = CafPluginHandlerFactory(factory),
        configTreeDecoder = CafConfigTreeDecoder { tree },
        schemaProvider = { tree },
        runtimeReady = { true },
    )

    private fun testStream(
        seed: Int,
        clientType: RcsClientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
    ): RcsDataStream = RcsDataStream.open(
        clientType = clientType,
        readKey = ByteArray(32) { (seed + it).toByte() },
        writeKey = ByteArray(32) { (seed + 64 + it).toByte() },
        bindAddress = InetAddress.getLoopbackAddress(),
    )

    private class SchemaHandler(
        private val tree: CafConfigTree,
    ) : CafSchemaBoundPluginHandler {
        override fun schemaSnapshot(): CafConfigTree = tree
    }
}
