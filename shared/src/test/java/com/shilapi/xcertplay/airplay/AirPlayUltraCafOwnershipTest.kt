package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTreeDecoder
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistration
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistry
import com.shilapi.xcertplay.airplay.rcs.caf.CafProtocolSession
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.caf.CafSchemaBoundPluginHandler
import com.shilapi.xcertplay.airplay.rcs.caf.SyntheticCafTestFixture
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AirPlayUltraCafOwnershipTest {
    @Test
    fun authorizedProvisioningCreatesFreshCafChildForEveryControlSession() {
        val pluginSessions = CopyOnWriteArrayList<CafProtocolSession>()
        val runtime = AirPlayUltraRuntime(
            cafProvisioning = provisioning { session ->
                pluginSessions += session
                schemaHandler()
            },
        )
        val first = runtime.openSession()
        val second = runtime.openSession()
        val firstDescriptor = requireNotNull(
            first.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        )
        val secondDescriptor = requireNotNull(
            second.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        )
        firstDescriptor.create(testStream(1))
        secondDescriptor.create(testStream(3))

        assertEquals(2, pluginSessions.size)
        assertNotSame(pluginSessions[0], pluginSessions[1])

        runtime.close()
        assertFalse(firstDescriptor.probe().isReady)
        assertFalse(secondDescriptor.probe().isReady)
    }

    @Test
    fun runtimeCloseRetriesCafHandlersBeforeRetiringTheProvisionedFactory() {
        val closes = AtomicInteger()
        val channel = java.util.concurrent.atomic.AtomicReference<CafProtocolSession>()
        val runtime = AirPlayUltraRuntime(
            cafProvisioning = provisioning { session ->
                channel.set(session)
                object : CafSchemaBoundPluginHandler {
                    override fun schemaSnapshot() = SyntheticCafTestFixture.tree()

                    override fun close() {
                        if (closes.incrementAndGet() == 1) {
                            throw IllegalStateException("synthetic CAF close failure")
                        }
                    }
                }
            },
        )
        val session = runtime.openSession()
        requireNotNull(
            session.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        ).create(testStream(5))

        assertThrows(IllegalStateException::class.java) { runtime.close() }
        assertEquals(1, closes.get())
        assertThrows(IllegalStateException::class.java) {
            requireNotNull(channel.get()).requestConfig(SyntheticCafTestFixture.PLUGIN_ID)
        }

        runtime.close()
        assertEquals(2, closes.get())
        assertThrows(IllegalStateException::class.java) { runtime.openSession() }
    }

    @Test
    fun failedSessionRuntimeConstructionRetainsCafChildUntilRuntimeCloseRetriesIt() {
        val closes = AtomicInteger()
        val stream = testStream(9)
        val runtime = AirPlayUltraRuntime.forTest(
            sessionRuntimeFactory = AirPlayUltraSessionRuntimeFactory {
                    _,
                    _,
                    cafSession,
                    _,
                    _,
                ->
                val child = requireNotNull(cafSession)
                requireNotNull(
                    child.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
                ).create(stream)
                throw IllegalStateException("synthetic session-runtime construction failure")
            },
            cafProvisioning = provisioning {
                object : CafSchemaBoundPluginHandler {
                    override fun schemaSnapshot() = SyntheticCafTestFixture.tree()

                    override fun close() {
                        if (closes.incrementAndGet() == 1) {
                            throw IllegalStateException("synthetic CAF close failure")
                        }
                    }
                }
            },
        )

        assertThrows(IllegalStateException::class.java) { runtime.openSession() }
        assertEquals(1, closes.get())
        assertThrows(IllegalStateException::class.java) { runtime.openSession() }
        assertEquals(1, closes.get())

        runtime.close()
        assertEquals(2, closes.get())
        assertThrows(IllegalStateException::class.java) { runtime.openSession() }
        stream.close()
    }

    private fun provisioning(
        handlerFactory: (CafProtocolSession) -> CafSchemaBoundPluginHandler,
    ): CafRuntimeProvisioning {
        val tree = SyntheticCafTestFixture.tree()
        return CafRuntimeProvisioning.from(
            CafPluginRegistry.from(
                listOf(
                    CafPluginRegistration(
                        pluginId = SyntheticCafTestFixture.PLUGIN_ID,
                        protocolVersion = "1.0",
                        clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                        handlerFactory = CafPluginHandlerFactory(handlerFactory),
                        configTreeDecoder = CafConfigTreeDecoder { tree },
                        schemaProvider = { tree },
                        runtimeReady = { true },
                    ),
                ),
            ),
        )
    }

    private fun schemaHandler() = object : CafSchemaBoundPluginHandler {
        override fun schemaSnapshot() = SyntheticCafTestFixture.tree()
    }

    private fun testStream(seed: Int): RcsDataStream = RcsDataStream.open(
        clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
        readKey = ByteArray(32) { (seed + it).toByte() },
        writeKey = ByteArray(32) { (seed + 64 + it).toByte() },
        bindAddress = InetAddress.getLoopbackAddress(),
    )
}
