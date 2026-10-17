package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedCafRuntimeProvisioningTest {
    @Test
    fun ordinaryProvisioningNeverProducesAuthorizationEvidence() {
        val provisioning = CafRuntimeProvisioning.from(registry())

        assertNull(provisioning.authorizationSnapshot())

        provisioning.close()
    }

    @Test
    fun authorizedProvisioningRequiresExactDecoderAndHandlerFactoryIdentities() {
        val decoderMismatch = object : CafConfigTreeDecoderFactory {
            override val implementationId: String = "test.decoder.other"
            override fun create(protocolVersion: String, pluginConfig: Any?) =
                CafConfigTreeDecoder { SyntheticCafTestFixture.tree() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CafRuntimeProvisioning.fromAuthorizedProfile(
                source = registry(),
                authorizationExpectation = expectation(),
                decoderFactory = decoderMismatch,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            CafRuntimeProvisioning.fromAuthorizedProfile(
                source = registry(handlerFactoryImplementationId = "test.handler.other"),
                authorizationExpectation = expectation(),
            )
        }
    }

    @Test
    fun authorizedHandlerIdentityMapMustCoverThePluginSetExactly() {
        val extraPluginExpectation = CafRuntimeAuthorizationExpectation(
            decoderImplementationId = DECODER_IMPLEMENTATION,
            handlerImplementationIds = mapOf(
                SyntheticCafTestFixture.PLUGIN_ID to HANDLER_IMPLEMENTATION,
                999L to "test.handler.unused",
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            CafRuntimeProvisioning.fromAuthorizedProfile(
                source = registry(),
                authorizationExpectation = extraPluginExpectation,
            )
        }
    }

    @Test
    fun createdHandlerImplementationMismatchFailsClosedAndClosesHandler() {
        val closeCalls = AtomicInteger()
        val provisioning = CafRuntimeProvisioning.fromAuthorizedProfile(
            source = registry(createdHandlerImplementationId = "test.handler.changed") {
                closeCalls.incrementAndGet()
            },
            authorizationExpectation = expectation(),
        )
        val owner = provisioning.transferToRuntime()
        val child = owner.openSession()
        val stream = testStream()
        try {
            assertThrows(CafProtocolException::class.java) {
                requireNotNull(
                    child.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
                ).create(stream)
            }
            assertEquals(1, closeCalls.get())
        } finally {
            child.close()
            owner.close()
            stream.close()
        }
    }

    @Test
    fun snapshotIsImmutableAndBecomesUnavailableAfterTransfer() {
        val provisioning = CafRuntimeProvisioning.fromAuthorizedProfile(
            source = registry(),
            authorizationExpectation = expectation(),
        )

        val snapshot = requireNotNull(provisioning.authorizationSnapshot())
        assertEquals(DECODER_IMPLEMENTATION, snapshot.decoderImplementationId)
        assertEquals(
            mapOf(SyntheticCafTestFixture.PLUGIN_ID to HANDLER_IMPLEMENTATION),
            snapshot.handlerImplementationIds,
        )
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.handlerImplementationIds as MutableMap<Long, String>).clear()
        }

        val owner = provisioning.transferToRuntime()
        assertNull(provisioning.authorizationSnapshot())
        owner.close()
    }

    @Test
    fun authorizationExpectationDoesNotTrustMapSize() {
        val hostileMap = object : kotlin.collections.AbstractMap<Long, String>() {
            override val entries: Set<Map.Entry<Long, String>> =
                object : kotlin.collections.AbstractSet<Map.Entry<Long, String>>() {
                    override val size: Int
                        get() = throw AssertionError("authorization must not read Map.size")

                    override fun iterator(): Iterator<Map.Entry<Long, String>> =
                        listOf(
                            object : Map.Entry<Long, String> {
                                override val key: Long = SyntheticCafTestFixture.PLUGIN_ID
                                override val value: String = HANDLER_IMPLEMENTATION
                            },
                        ).iterator()
                }
        }

        val expectation = CafRuntimeAuthorizationExpectation(
            decoderImplementationId = DECODER_IMPLEMENTATION,
            handlerImplementationIds = hostileMap,
        )
        val provisioning = CafRuntimeProvisioning.fromAuthorizedProfile(
            source = registry(),
            authorizationExpectation = expectation,
        )

        assertTrue(provisioning.authorizationSnapshot() != null)
        provisioning.close()
    }

    private fun expectation() = CafRuntimeAuthorizationExpectation(
        decoderImplementationId = DECODER_IMPLEMENTATION,
        handlerImplementationIds = mapOf(
            SyntheticCafTestFixture.PLUGIN_ID to HANDLER_IMPLEMENTATION,
        ),
    )

    private fun registry(
        handlerFactoryImplementationId: String = HANDLER_IMPLEMENTATION,
        createdHandlerImplementationId: String = HANDLER_IMPLEMENTATION,
        onHandlerClose: () -> Unit = {},
    ): CafPluginRegistry {
        val tree = SyntheticCafTestFixture.tree()
        return CafPluginRegistry.from(
            listOf(
                CafPluginRegistration(
                    pluginId = SyntheticCafTestFixture.PLUGIN_ID,
                    protocolVersion = "1.0",
                    clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                    handlerFactory = object : CafPluginHandlerFactory {
                        override val implementationId: String = handlerFactoryImplementationId

                        override fun create(session: CafProtocolSession): CafPluginHandler =
                            object : CafSchemaBoundPluginHandler {
                                override val implementationId: String =
                                    createdHandlerImplementationId

                                override fun schemaSnapshot(): CafConfigTree = tree

                                override fun close() = onHandlerClose()
                            }
                    },
                    configTreeDecoder = CafConfigTreeDecoder { tree },
                    schemaProvider = { tree },
                    runtimeReady = { true },
                ),
            ),
        )
    }

    private fun testStream(): RcsDataStream = RcsDataStream.open(
        clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
        readKey = ByteArray(32) { (it + 1).toByte() },
        writeKey = ByteArray(32) { (it + 65).toByte() },
        bindAddress = InetAddress.getLoopbackAddress(),
    )

    private companion object {
        const val DECODER_IMPLEMENTATION = "xcertplay.caf.decoder.firmware-v1"
        const val HANDLER_IMPLEMENTATION = "test.handler.authorized.v1"
    }
}
