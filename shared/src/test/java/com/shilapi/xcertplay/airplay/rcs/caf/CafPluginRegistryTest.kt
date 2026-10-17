package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerReadiness
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.net.InetAddress
import java.math.BigInteger
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CafPluginRegistryTest {
    @Test
    fun sidecarCountAndConfigsAreDerivedFromOneValidatedRegistry() {
        val tree = SyntheticCafTestFixture.tree()
        val registry = registry(tree)
        val sidecar = registry.vehicleStateProtocolInfo()
        val decoded = CafVehicleStateProtocolInfo.decode(sidecar.asWireMap())

        assertEquals("1.0", decoded.protocolVersion)
        assertEquals(1, decoded.pluginCount)
        assertEquals(SyntheticCafTestFixture.PLUGIN_ID, decoded.pluginConfigs.single()["pluginID"])
        assertEquals("kept", decoded.pluginConfigs.single()["oemPluginField"])
    }

    @Test
    fun advertisedPluginIdsMayBeOutOfOrderButMustCoverZeroThroughCountMinusOne() {
        val indexed = CafVehicleStateProtocolInfo(
            protocolVersion = "1.0",
            pluginConfigs = listOf(mapOf("pluginID" to 1L), mapOf("pluginID" to 0L)),
        )

        assertEquals(indexed, indexed.requireIndexedPluginIdsForAdvertisement())
        assertEquals(2, indexed.pluginCount)
        assertThrows(CafProtocolException::class.java) {
            CafVehicleStateProtocolInfo(
                protocolVersion = "1.0",
                pluginConfigs = listOf(mapOf("pluginID" to 0L), mapOf("pluginID" to 2L)),
            ).requireIndexedPluginIdsForAdvertisement()
        }
    }

    @Test
    fun arbitraryUInt64PluginIdsRemainDecodableButCannotBeAdvertisedAsIndexed() {
        val highUnsignedId = BigInteger("18446744073709551615")
        val diagnostic = CafVehicleStateProtocolInfo.decode(
            mapOf(
                "protocolVersion" to "1.0",
                "pluginCount" to 1L,
                "pluginConfigs" to listOf(mapOf("pluginID" to highUnsignedId)),
            ),
        )

        assertEquals(-1L, CafUInt64.parse(diagnostic.pluginConfigs.single()["pluginID"], "pluginID"))
        assertThrows(CafProtocolException::class.java) {
            diagnostic.requireIndexedPluginIdsForAdvertisement()
        }
    }

    @Test
    fun mismatchedPluginCountAndPluginIdAreRejected() {
        assertThrows(CafProtocolException::class.java) {
            CafVehicleStateProtocolInfo.decode(
                mapOf(
                    "protocolVersion" to "1.0",
                    "pluginCount" to 2L,
                    "pluginConfigs" to listOf(mapOf("pluginID" to 1L)),
                ),
            )
        }
        assertThrows(CafProtocolException::class.java) {
            CafPluginRegistration(
                pluginId = 7,
                protocolVersion = "1.0",
                clientTypes = setOf(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
                handlerFactory = CafPluginHandlerFactory { NoOpHandler },
                pluginConfig = mapOf("pluginID" to 8L),
            )
        }
    }

    @Test
    fun descriptorReadinessRequiresConcreteValidatedRegistration() {
        val registry = registry(SyntheticCafTestFixture.tree())
        val descriptor = CafProtocolSession.handlerFactory(registry)
            .descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA)!!

        assertEquals(RcsDataStreamHandlerReadiness.READY, descriptor.probe().readiness)
        assertEquals(
            null,
            CafProtocolSession.handlerFactory(registry)
                .descriptor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL),
        )
    }

    @Test
    fun runtimeReadinessIsDefaultDeny() {
        val tree = SyntheticCafTestFixture.tree()
        val registry = CafPluginRegistry.from(
            listOf(
                CafPluginRegistration(
                    pluginId = 1,
                    protocolVersion = "1.0",
                    clientTypes = setOf(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
                    handlerFactory = CafPluginHandlerFactory { NoOpHandler },
                    configTreeDecoder = CafConfigTreeDecoder { tree },
                    schemaProvider = { tree },
                ),
            ),
        )
        val probe = CafProtocolSession.handlerFactory(registry)
            .descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA)!!
            .probe()

        assertEquals(RcsDataStreamHandlerReadiness.NOT_READY, probe.readiness)
    }

    @Test
    fun readinessRejectsMissingAndUnprioritizedRoutesWithoutCreatingHandler() {
        var handlerCreations = 0
        fun probeFor(tree: CafConfigTree): RcsDataStreamHandlerReadiness {
            val registry = CafPluginRegistry.from(
                listOf(
                    CafPluginRegistration(
                        pluginId = 1,
                        protocolVersion = "1.0",
                        clientTypes = setOf(
                            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                        ),
                        handlerFactory = CafPluginHandlerFactory {
                            handlerCreations++
                            NoOpHandler
                        },
                        configTreeDecoder = CafConfigTreeDecoder { tree },
                        schemaProvider = { tree },
                        runtimeReady = { true },
                    ),
                ),
            )
            return CafProtocolSession.handlerFactory(registry)
                .descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA)!!
                .probe()
                .readiness
        }

        val noPriorityRaw = SyntheticCafTestFixture.mutableRaw()
        characteristics(noPriorityRaw).first().remove("priority")
        assertEquals(
            RcsDataStreamHandlerReadiness.NOT_READY,
            probeFor(CafConfigTree.decode(noPriorityRaw)),
        )

        val noHighRouteRaw = SyntheticCafTestFixture.mutableRaw()
        characteristics(noHighRouteRaw).removeAll {
            it["priority"] == 1L
        }
        assertEquals(
            RcsDataStreamHandlerReadiness.NOT_READY,
            probeFor(CafConfigTree.decode(noHighRouteRaw)),
        )
        assertEquals(0, handlerCreations)
    }

    @Test
    fun sessionConstructionFailureClosesEarlierHandlersTransactionsAndWorker() {
        val tree = SyntheticCafTestFixture.tree()
        var firstHandlerCloses = 0
        val registrations = listOf(
            CafPluginRegistration(
                pluginId = 101L,
                protocolVersion = "1.0",
                clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                handlerFactory = CafPluginHandlerFactory {
                    object : CafPluginHandler {
                        override fun close() {
                            firstHandlerCloses++
                        }
                    }
                },
                configTreeDecoder = CafConfigTreeDecoder { tree },
                schemaProvider = { tree },
                runtimeReady = { true },
            ),
            CafPluginRegistration(
                pluginId = 102L,
                protocolVersion = "1.0",
                clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                handlerFactory = CafPluginHandlerFactory {
                    throw IllegalStateException("synthetic handler creation failure")
                },
                configTreeDecoder = CafConfigTreeDecoder { tree },
                schemaProvider = { tree },
                runtimeReady = { true },
            ),
        )
        val tracker = CafTransactionTracker()
        val worker = ScheduledThreadPoolExecutor(1)
        val stream = testStream()
        try {
            assertThrows(IllegalStateException::class.java) {
                CafProtocolSession(
                    clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                    stream = stream,
                    plugins = CafPluginRegistry.from(registrations),
                    decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
                    transactions = tracker,
                    timeoutWorker = worker,
                )
            }

            assertEquals(1, firstHandlerCloses)
            assertTrue(worker.isShutdown)
            assertThrows(IllegalStateException::class.java) {
                tracker.begin(
                    pluginId = 101L,
                    requestCommand = CafCommand.CONFIG_REQUEST,
                    expectedResponses = setOf(CafCommand.CONFIG_RESPONSE),
                )
            }
        } finally {
            worker.shutdownNow()
            stream.close()
        }
    }

    @Test
    fun runtimeProvisioningFreezesOneSidecarRegistryAndSchemaFingerprint() {
        val mutableRoot = SyntheticCafTestFixture.mutableRaw()
        val decoded = CafConfigTree.decode(mutableRoot)
        val externallyBackedTree = decoded.copy(root = mutableRoot)
        val nested = linkedMapOf<String, Any?>("mode" to "original")
        val mutablePluginConfig = linkedMapOf<String, Any?>(
            "pluginID" to BigInteger("18446744073709551615"),
            "oem" to nested,
        )
        val registry = CafPluginRegistry.from(
            listOf(
                CafPluginRegistration(
                    pluginId = -1L,
                    protocolVersion = "1.0",
                    clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                    handlerFactory = CafPluginHandlerFactory { NoOpHandler },
                    pluginConfig = mutablePluginConfig,
                    schemaProvider = { externallyBackedTree },
                    runtimeReady = { true },
                ),
            ),
        )

        val provisioning = CafRuntimeProvisioning.from(registry)
        val fingerprint = provisioning.schemaFingerprint
        nested["mode"] = "mutated"
        mutablePluginConfig["late"] = true
        mutableRoot.clear()

        val info = provisioning.vehicleStateProtocolInfo()
        assertEquals(-1L, CafUInt64.parse(info.pluginConfigs.single()["pluginID"], "pluginID"))
        assertEquals(
            mapOf("mode" to "original"),
            info.pluginConfigs.single()["oem"],
        )
        assertEquals(fingerprint, provisioning.schemaFingerprint)
        CafPluginRegistry.DEFAULT_CLIENT_TYPES.forEach { clientType ->
            assertEquals(
                RcsDataStreamHandlerReadiness.READY,
                requireNotNull(provisioning.handlerFactory.descriptor(clientType))
                    .probe().readiness,
            )
        }
    }

    @Test
    fun runtimeProvisioningClosesHandlerWhenItsSchemaSnapshotThrows() {
        val tree = SyntheticCafTestFixture.tree()
        var handlerCloses = 0
        val registry = CafPluginRegistry.from(
            listOf(
                CafPluginRegistration(
                    pluginId = SyntheticCafTestFixture.PLUGIN_ID,
                    protocolVersion = "1.0",
                    clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                    handlerFactory = CafPluginHandlerFactory {
                        object : CafSchemaBoundPluginHandler {
                            override fun schemaSnapshot(): CafConfigTree =
                                throw IllegalStateException("synthetic schema failure")

                            override fun close() {
                                handlerCloses++
                            }
                        }
                    },
                    schemaProvider = { tree },
                    runtimeReady = { true },
                ),
            ),
        )
        val provisioning = CafRuntimeProvisioning.from(registry)
        val stream = testStream()
        try {
            val failure = assertThrows(CafProtocolException::class.java) {
                requireNotNull(
                    provisioning.handlerFactory.descriptor(
                        RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                    ),
                ).create(stream)
            }

            assertEquals("synthetic schema failure", failure.cause?.message)
            assertEquals(1, handlerCloses)
        } finally {
            stream.close()
        }
    }

    @Test
    fun runtimeProvisioningFreezesSuccessfulRuntimeReadiness() {
        val tree = SyntheticCafTestFixture.tree()
        val sourceReady = AtomicBoolean(true)
        val registry = CafPluginRegistry.from(
            listOf(
                CafPluginRegistration(
                    pluginId = SyntheticCafTestFixture.PLUGIN_ID,
                    protocolVersion = "1.0",
                    clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                    handlerFactory = CafPluginHandlerFactory {
                        object : CafSchemaBoundPluginHandler {
                            override fun schemaSnapshot(): CafConfigTree = tree
                        }
                    },
                    schemaProvider = { tree },
                    runtimeReady = sourceReady::get,
                ),
            ),
        )

        val provisioning = CafRuntimeProvisioning.from(registry)
        sourceReady.set(false)

        CafPluginRegistry.DEFAULT_CLIENT_TYPES.forEach { clientType ->
            assertEquals(
                RcsDataStreamHandlerReadiness.READY,
                requireNotNull(provisioning.handlerFactory.descriptor(clientType))
                    .probe().readiness,
            )
        }

        val stream = testStream()
        try {
            requireNotNull(
                provisioning.handlerFactory.descriptor(
                    RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                ),
            ).create(stream).close()
        } finally {
            stream.close()
        }
    }

    @Test
    fun runtimeProvisioningRejectsAnExtraCafClientBeforePublishingIt() {
        val tree = SyntheticCafTestFixture.tree()
        val extraClientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA.copy(
            name = "UnconfirmedCafClient",
            uuid = UUID.fromString("F60372D4-C39A-4CE7-8F1A-8D523A13413C"),
        )
        var readinessCalls = 0
        val registry = CafPluginRegistry.from(
            listOf(
                CafPluginRegistration(
                    pluginId = SyntheticCafTestFixture.PLUGIN_ID,
                    protocolVersion = "1.0",
                    clientTypes = setOf(extraClientType),
                    handlerFactory = CafPluginHandlerFactory { NoOpHandler },
                    schemaProvider = { tree },
                    runtimeReady = {
                        readinessCalls++
                        true
                    },
                ),
            ),
        )

        assertThrows(CafProtocolException::class.java) {
            CafRuntimeProvisioning.from(registry)
        }
        assertEquals(0, readinessCalls)
    }

    @Test
    fun defaultClientTypesCannotBeMutatedThroughAJvmMutableSetCast() {
        @Suppress("UNCHECKED_CAST")
        val mutableView = CafPluginRegistry.DEFAULT_CLIENT_TYPES as MutableSet<Any?>

        assertThrows(UnsupportedOperationException::class.java) {
            mutableView.clear()
        }
        assertEquals(
            setOf(
                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
            ),
            CafPluginRegistry.DEFAULT_CLIENT_TYPES,
        )
    }

    private fun registry(tree: CafConfigTree): CafPluginRegistry = CafPluginRegistry.from(
        listOf(
            CafPluginRegistration(
                pluginId = SyntheticCafTestFixture.PLUGIN_ID,
                protocolVersion = "1.0",
                clientTypes = setOf(
                    RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                    RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                ),
                handlerFactory = CafPluginHandlerFactory { NoOpHandler },
                pluginConfig = mapOf("pluginID" to SyntheticCafTestFixture.PLUGIN_ID, "oemPluginField" to "kept"),
                configTreeDecoder = CafConfigTreeDecoder { tree },
                schemaProvider = { tree },
                runtimeReady = { true },
            ),
        ),
    )

    private fun testStream(): RcsDataStream = RcsDataStream.open(
        clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
        readKey = ByteArray(32) { 1 },
        writeKey = ByteArray(32) { 2 },
        bindAddress = InetAddress.getLoopbackAddress(),
    )

    private data object NoOpHandler : CafPluginHandler {
        override fun onConfigRequest(session: CafProtocolSession, message: CafMessageReader) = Unit
    }

    @Suppress("UNCHECKED_CAST")
    private fun characteristics(root: MutableMap<String, Any?>): MutableList<MutableMap<String, Any?>> =
        (((root["accessories"] as MutableList<MutableMap<String, Any?>>)
            .single()["services"] as MutableList<MutableMap<String, Any?>>)
            .single()["characteristics"] as MutableList<MutableMap<String, Any?>>)
}
