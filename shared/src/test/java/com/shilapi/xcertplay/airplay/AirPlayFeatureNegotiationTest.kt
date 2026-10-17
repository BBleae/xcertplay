package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.caf.CafVehicleStateProtocolInfo
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncChannelDescriptor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpoint
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEventSink
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestination
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestinationRole
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAssignment
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputPreflightContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRequirement
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRoute
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalTargetVersion
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import java.math.BigInteger
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayFeatureNegotiationTest {
    @Test
    fun ultraOffOrdinaryCapabilityGoldenSetIsUnchanged() {
        val config = config(cluster = cluster, hevc = true)
        val info = AirPlayInfoPlist.build(config)

        assertEquals(
            setOf(
                "sourceVersion", "features", "statusFlags", "model", "manufacturer",
                "deviceID", "bluetoothIDs", "name", "rightHandDrive", "keepAliveLowPower",
                "keepAliveSendStatsAsBody", "modes", "audioLatencies", "audioFormats",
                "extendedFeatures", "displays", "uiContextLastOnDisplayURLs",
                "uiContextNowOnDisplayURLs", "hidDevices", "hevcInfo",
            ),
            info.keys,
        )
        val displays = (info["displays"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf(110, 111), displays.map { it["type"] })
        assertEquals(listOf(AirPlayInfoPlist.MAIN_UUID, AirPlayInfoPlist.ALT_UUID), displays.map { it["uuid"] })
        assertFalse(info.containsKey("vehicleStateProtocolInfo"))
        assertFalse(info.containsKey("uiSyncInfo"))

        val proposal = AirPlayFeatureNegotiation.propose(
            config,
            listOf(
                "uiContext", "viewAreas", "altScreen", "hevc", "vehicleStateProtocol",
                "uiSync", "fileTransfer", "logTransfer", "sessionManagement",
                "videoPlayback", "mainBuffered", "iAPChannel",
            ),
            eventPortAvailable = true,
        )
        assertEquals(
            listOf("uiContext", "viewAreas", "altScreen", "hevc", "iAPChannel"),
            proposal.enabledFeatures,
        )
    }

    @Test
    fun requestedFeaturesAreTrimmedDeduplicatedAndUnknownNamesAreIgnored() {
        val requested = AirPlayFeatureNegotiation.requestedFeatures(
            listOf(" viewAreas ", "unknown", "viewAreas", 1, "", "hevc"),
        )
        assertEquals(listOf("viewAreas", "unknown", "hevc"), requested)

        val proposal = AirPlayFeatureNegotiation.propose(
            AirPlaySessionCapabilityInput(config(hevc = true), fullTransport(alternate = false)),
            requested,
        )
        assertEquals(listOf("viewAreas", "hevc"), proposal.enabledFeatures)
    }

    @Test
    fun repeatedFeatureSetupCannotSilentlyReuseAnEarlierWiderAnswer() {
        val exchange = AirPlayFeatureNegotiation.exchange(config(cluster = cluster))
        val requested = listOf("uiContext", "altScreen", "viewAreas")
        val first = exchange.negotiate(requested, fullTransport())

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (first.requestedFeatures as MutableList<String>).clear()
        }

        assertEquals(first, exchange.negotiate(requested.reversed(), fullTransport()))
        assertThrows(AirPlayFeatureScopeRevokedException::class.java) {
            exchange.negotiate(listOf("viewAreas"), fullTransport())
        }
        assertThrows(AirPlayFeatureScopeRevokedException::class.java) {
            exchange.infoScope(fullTransport())
        }
    }

    @Test
    fun repeatedFeatureSetupRequiresPreviouslyEnabledTransportStillReady() {
        val exchange = AirPlayFeatureNegotiation.exchange(config(cluster = cluster))
        val requested = listOf("uiContext", "altScreen")
        exchange.negotiate(requested, fullTransport())

        assertThrows(AirPlayFeatureScopeRevokedException::class.java) {
            exchange.negotiate(requested, fullTransport().copy(eventChannel = false))
        }
    }

    @Test
    fun ultraConfigurationAndReadinessSnapshotCallerOwnedCollections() {
        val handlerSource = mutableListOf(ready(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
        val readiness = AirPlayUltraReadiness(rcsHandlers = handlerSource)
        val featureSource = mutableSetOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL)
        val ultra = AirPlayUltraConfiguration(
            configuredFeatures = featureSource,
            readiness = readiness,
        )

        handlerSource.clear()
        featureSource.clear()

        assertEquals(1, readiness.rcsHandlers.size)
        assertNotNull(readiness.handler(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
        assertEquals(setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL), ultra.configuredFeatures)
        assertTrue(ultra.isConfigured(AirPlayFeature.VEHICLE_STATE_PROTOCOL))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (readiness.rcsHandlers as MutableList<AirPlayRcsHandlerReadinessDescriptor>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (ultra.configuredFeatures as MutableSet<AirPlayFeature>).clear()
        }
    }

    @Test
    fun runtimeDescriptorCaptureFreezesConfiguredFeaturesBeforeExternalProbes() {
        val featureSource = mutableSetOf(AirPlayFeature.UI_SYNC)
        val displayId = "opaque-runtime-display"
        val endpoint = CarPlayUiSyncEndpoint(
            descriptor = CarPlayUiSyncChannelDescriptor(displayId, "test-vehicle"),
            mode = CarPlayUiSyncMode.PASSIVE,
            presentationEventSink = CarPlayUiSyncPresentationEventSink { },
        )
        val factory = RcsDataStreamHandlerFactory { clientType ->
            featureSource.clear()
            RcsDataStreamHandlerDescriptor(
                clientType = clientType,
                implementationId = "test.${clientType.name}",
                readinessProbe = { RcsDataStreamHandlerProbe.ready() },
                handlerFactory = { object : RcsDataStreamHandler {} },
            )
        }

        val ultra = AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = featureSource,
            uiSyncInfo = AirPlayUiSyncInfo(),
            rcsFactory = factory,
            uiSyncEndpoints = listOf(endpoint),
        )

        assertTrue(ultra.isConfigured(AirPlayFeature.UI_SYNC))
        assertEquals(setOf(AirPlayFeature.UI_SYNC), ultra.configuredFeatures)
        assertEquals(setOf(displayId), ultra.readiness.uiSync?.displayIds)
        assertFalse(ultra.readiness.uiSync!!.isComplete)
    }

    @Test
    fun configuredFeatureSnapshotDoesNotTrustDeclaredCollectionSizeOrUnendingIteration() {
        val dishonestSize = object : AbstractSet<AirPlayFeature>() {
            override val size: Int
                get() = throw AssertionError("configured feature size must not be trusted")
            override fun iterator(): Iterator<AirPlayFeature> =
                listOf(AirPlayFeature.SESSION_MANAGEMENT).iterator()
        }
        val captured = AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = dishonestSize,
            sessionManagementInfo = AirPlaySessionManagementInfo(1, listOf(1)),
        )
        assertTrue(captured.isConfigured(AirPlayFeature.SESSION_MANAGEMENT))

        var sequence = 0
        val unending = object : AbstractSet<AirPlayFeature>() {
            override val size: Int = 0
            override fun iterator(): Iterator<AirPlayFeature> = object : Iterator<AirPlayFeature> {
                override fun hasNext(): Boolean = true
                override fun next(): AirPlayFeature = AirPlayFeature.entries[
                    sequence++.mod(AirPlayFeature.entries.size)
                ]
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayUltraConfiguration.fromRuntimeDescriptors(unending)
        }
        assertTrue(sequence <= AirPlayFeature.entries.size + 1)
    }

    @Test
    fun concreteHandlerProbeIsCapturedExactlyOnceForTheSessionSnapshot() {
        var probes = 0
        val concrete = RcsDataStreamHandlerDescriptor(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            implementationId = "test.caf-low",
            readinessProbe = {
                probes++
                RcsDataStreamHandlerProbe.ready()
            },
            handlerFactory = { object : RcsDataStreamHandler {} },
        )

        val captured = AirPlayRcsHandlerReadinessDescriptor.capture(concrete)
        assertEquals(1, probes)
        assertTrue(captured.isComplete)
        repeat(3) { assertTrue(captured.isComplete) }
        assertEquals(1, probes)
    }

    @Test
    fun runtimeDescriptorFactoryMustReturnReadyExactHandlersForBothVehicleChannels() {
        fun factory(notReady: RcsClientType? = null) = RcsDataStreamHandlerFactory { clientType ->
            RcsDataStreamHandlerDescriptor(
                clientType = clientType,
                implementationId = "test.${clientType.name}",
                readinessProbe = {
                    if (clientType == notReady) RcsDataStreamHandlerProbe.notReady("repository_offline")
                    else RcsDataStreamHandlerProbe.ready()
                },
                handlerFactory = { object : RcsDataStreamHandler {} },
            )
        }
        fun ultra(factory: RcsDataStreamHandlerFactory) = AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
            vehicleStateProtocolInfo = vehicleInfo(),
            rcsFactory = factory,
        )

        assertTrue(
            AirPlayFeatureNegotiation.snapshot(config(ultra = ultra(factory())))
                .isReady(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
        )
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(
                config(ultra = ultra(factory(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2))),
            ).isReady(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
        )
    }

    @Test
    fun alternateDisplayAndUiContextAreAcceptedOnlyAsAPair() {
        val local = AirPlayFeatureNegotiation.snapshot(config(cluster = cluster))
        val transport = fullTransport()

        assertEquals(
            emptyList<String>(),
            AirPlayFeatureNegotiation.propose(local, listOf("altScreen"), transport).enabledFeatures,
        )
        assertEquals(
            emptyList<String>(),
            AirPlayFeatureNegotiation.propose(local, listOf("uiContext"), transport).enabledFeatures,
        )
        assertEquals(
            listOf("uiContext", "altScreen"),
            AirPlayFeatureNegotiation.propose(
                local,
                listOf("uiContext", "altScreen"),
                transport,
            ).enabledFeatures,
        )
    }

    @Test
    fun vehicleStateRequiresOptInSidecarBothConcreteHandlersAndRcsTransport() {
        val complete = vehicleUltra()
        val requested = listOf("vehicleStateProtocol")

        assertFalse(
            AirPlayFeatureNegotiation.snapshot(
                config(ultra = complete.copy(configuredFeatures = emptySet())),
            ).isReady(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
        )
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(
                config(ultra = complete.copy(vehicleStateProtocolInfo = null)),
            ).isReady(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
        )
        RcsClientTypes.CAR_PLAY_PROTOCOL_DATA.let { data ->
            val onlyOne = complete.copy(
                readiness = complete.readiness.copy(
                    rcsHandlers = complete.readiness.rcsHandlers.filter { it.clientType == data },
                ),
            )
            assertFalse(
                AirPlayFeatureNegotiation.snapshot(config(ultra = onlyOne))
                    .isReady(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
            )
        }
        val incomplete = complete.copy(
            readiness = complete.readiness.copy(
                rcsHandlers = complete.readiness.rcsHandlers.mapIndexed { index, descriptor ->
                    if (index == 0) descriptor.copy(consumer = AirPlayReadiness.DIAGNOSTIC_ONLY)
                    else descriptor
                },
            ),
        )
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(config(ultra = incomplete))
                .isReady(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
        )

        val local = AirPlayFeatureNegotiation.snapshot(config(ultra = complete))
        assertTrue(local.isReady(AirPlayFeature.VEHICLE_STATE_PROTOCOL))
        assertTrue(
            AirPlayFeatureNegotiation.propose(local, requested, fullTransport()).enabledFeatures
                .contains("vehicleStateProtocol"),
        )
        assertFalse(
            AirPlayFeatureNegotiation.propose(
                local,
                requested,
                fullTransport().copy(readyRcsClientTypes = emptySet()),
            ).enabledFeatures.contains("vehicleStateProtocol"),
        )
        assertFalse(
            AirPlayFeatureNegotiation.propose(local, emptyList<String>(), fullTransport())
                .enabledFeatures.contains("vehicleStateProtocol"),
        )
    }

    @Test
    fun vehicleSidecarRejectsEmptyNonIntegerDuplicateAndUnknownMappings() {
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayVehicleStateProtocolInfo(pluginConfigs = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayVehicleStateProtocolPlugin.fromWireMap(mapOf("pluginID" to 1.0))
        }
        val plugin = AirPlayVehicleStateProtocolPlugin(7)
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayVehicleStateProtocolInfo(pluginConfigs = listOf(plugin, plugin))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayVehicleStateProtocolInfo(
                pluginConfigs = listOf(plugin),
                pluginMapping = mapOf("climate" to 8),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayVehicleStateProtocolInfo.fromWireMap(
                mapOf(
                    "protocolVersion" to "1.0",
                    "pluginCount" to 2,
                    "pluginConfigs" to listOf(mapOf("pluginID" to 7)),
                ),
            )
        }
    }

    @Test
    fun validatedCafSidecarAdaptsWithoutLosingUInt64PluginBits() {
        val caf = CafVehicleStateProtocolInfo(
            protocolVersion = "1.0",
            pluginConfigs = listOf(mapOf("pluginID" to -1L, "accessories" to emptyList<Any?>())),
        )

        val airPlay = AirPlayVehicleStateProtocolInfo.from(caf)
        val plugin = (airPlay.toWireMap()["pluginConfigs"] as List<*>).single() as Map<*, *>
        assertEquals(BigInteger("18446744073709551615"), plugin["pluginID"])
    }

    @Test
    fun vehicleSidecarPreservesUpperHalfUInt64PluginIdsAndMappings() {
        val signBit = Long.MIN_VALUE
        val maximum = -1L
        val info = AirPlayVehicleStateProtocolInfo(
            pluginConfigs = listOf(
                AirPlayVehicleStateProtocolPlugin(signBit),
                AirPlayVehicleStateProtocolPlugin(maximum),
            ),
            pluginMapping = mapOf("high" to signBit, "maximum" to maximum),
        )

        val wire = info.toWireMap()
        val configs = wire["pluginConfigs"] as List<*>
        assertEquals(BigInteger("9223372036854775808"), (configs[0] as Map<*, *>)["pluginID"])
        assertEquals(BigInteger("18446744073709551615"), (configs[1] as Map<*, *>)["pluginID"])
        val mapping = wire["pluginMapping"] as Map<*, *>
        assertEquals(BigInteger("9223372036854775808"), mapping["high"])
        assertEquals(BigInteger("18446744073709551615"), mapping["maximum"])

        val decoded = AirPlayVehicleStateProtocolInfo.fromWireMap(wire)
        assertEquals(listOf(signBit, maximum), decoded.pluginConfigs.map { it.pluginId })
        assertEquals(mapOf("high" to signBit, "maximum" to maximum), decoded.pluginMapping)
    }

    @Test
    fun ultraSidecarsDoNotExposeTheirNestedMutableSnapshots() {
        val bytes = byteArrayOf(1, 2)
        val nested = linkedMapOf<String, Any?>("bytes" to bytes)
        val plugin = AirPlayVehicleStateProtocolPlugin(7, mapOf("nested" to nested))
        val uiSync = AirPlayUiSyncInfo(mapOf("nested" to nested))
        bytes.fill(9)
        nested.clear()

        fun mutateAndReadAgain(values: () -> Map<String, Any?>): ByteArray {
            val first = values()
            val firstNested = first["nested"] as Map<*, *>
            (firstNested["bytes"] as ByteArray)[0] = 99
            val secondNested = values()["nested"] as Map<*, *>
            return secondNested["bytes"] as ByteArray
        }

        assertArrayEquals(byteArrayOf(1, 2), mutateAndReadAgain(plugin::toWireMap))
        assertArrayEquals(byteArrayOf(1, 2), mutateAndReadAgain(uiSync::toWireMap))
    }

    @Test
    fun uiSyncRequiresClusterControlCarrierConsumerMatchingDisplayAndDependencies() {
        val complete = uiSyncUltra()
        val requested = listOf("uiContext", "altScreen", "uiSync")
        val completeConfig = config(cluster = cluster, ultra = complete)
        val local = AirPlayFeatureNegotiation.snapshot(completeConfig)

        assertTrue(local.isReady(AirPlayFeature.UI_SYNC))
        assertEquals(
            requested,
            AirPlayFeatureNegotiation.propose(local, requested, fullTransport()).enabledFeatures,
        )
        assertFalse(
            AirPlayFeatureNegotiation.propose(local, listOf("uiSync"), fullTransport())
                .enabledFeatures.contains("uiSync"),
        )
        assertFalse(
            AirPlayFeatureNegotiation.propose(
                local,
                requested,
                fullTransport().copy(eventChannel = false),
            ).enabledFeatures.contains("uiSync"),
        )
        assertFalse(
            AirPlayFeatureNegotiation.propose(
                local,
                requested,
                fullTransport().copy(readyRcsClientTypes = emptySet()),
            ).enabledFeatures.contains("uiSync"),
        )
        assertFalse(
            AirPlayFeatureNegotiation.propose(
                local,
                requested,
                fullTransport(alternate = false),
            ).enabledFeatures.contains("uiSync"),
        )

        val noCluster = AirPlayFeatureNegotiation.snapshot(config(ultra = complete))
        assertFalse(noCluster.isReady(AirPlayFeature.UI_SYNC))
        val originalReadiness = requireNotNull(complete.readiness.uiSync)
        fun uiSyncReadiness(
            displayIds: Set<String>,
            outerCarrier: AirPlayReadiness = AirPlayReadiness.READY,
        ) = AirPlayUiSyncReadinessDescriptor(
            displayIds = displayIds,
            clusterControl = originalReadiness.clusterControl,
            endpointCount = displayIds.size,
            outerCarrier = outerCarrier,
            stateMachineConsumer = AirPlayReadiness.READY,
            renderer = originalReadiness.renderer,
            schemaFingerprint = originalReadiness.schemaFingerprint,
            bindingFingerprint = originalReadiness.bindingFingerprint,
        )
        val mismatched = complete.copy(
            readiness = complete.readiness.copy(
                uiSync = uiSyncReadiness(setOf("not-the-advertised-display")),
            ),
        )
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(config(cluster = cluster, ultra = mismatched))
                .isReady(AirPlayFeature.UI_SYNC),
        )
        val extraDisplay = complete.copy(
            readiness = complete.readiness.copy(
                uiSync = uiSyncReadiness(
                    setOf(AirPlayInfoPlist.ALT_UUID, "unadvertised-passenger-display"),
                ),
            ),
        )
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(config(cluster = cluster, ultra = extraDisplay))
                .isReady(AirPlayFeature.UI_SYNC),
        )
        val noCarrier = complete.copy(
            readiness = complete.readiness.copy(
                uiSync = uiSyncReadiness(
                    setOf(AirPlayInfoPlist.ALT_UUID),
                    AirPlayReadiness.DIAGNOSTIC_ONLY,
                ),
            ),
        )
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(config(cluster = cluster, ultra = noCarrier))
                .isReady(AirPlayFeature.UI_SYNC),
        )
    }

    @Test
    fun mainOnlyDdpCanNegotiateUiContextAndProvisionedUiSyncWithoutAlternateSink() {
        val ultra = mainUiSyncUltra()
        val mainOnly = config(ultra = ultra).copy(
            main = AirPlayDisplayConfig(1280, 720, initialUrl = "xcertplay://main/custom"),
        ).withLivePhysicalPanels(standaloneMainDdpPanel())
            .copy(requireMainUiSyncForDisplayPanels = true)
        val local = AirPlayFeatureNegotiation.snapshot(mainOnly)
        val transport = fullTransport(alternate = false)

        assertTrue(local.isReady(AirPlayFeature.UI_CONTEXT))
        assertFalse(local.isReady(AirPlayFeature.ALT_SCREEN))
        assertTrue(local.isReady(AirPlayFeature.UI_SYNC))
        assertEquals(
            listOf("uiContext", "uiSync"),
            AirPlayFeatureNegotiation.propose(
                local, listOf("uiContext", "altScreen", "uiSync"), transport,
            ).enabledFeatures,
        )
        assertEquals(
            listOf("uiContext"),
            AirPlayFeatureNegotiation.propose(local, listOf("uiContext"), transport).enabledFeatures,
        )
        // A configured but unaccepted legacy cluster must not be required by this exact
        // MAIN/DDP path, nor may its ALT feature slip through without a real alternate sink.
        val optionalCluster = AirPlayFeatureNegotiation.snapshot(mainOnly.copy(cluster = cluster))
        assertEquals(
            listOf("uiContext", "uiSync"),
            AirPlayFeatureNegotiation.propose(
                optionalCluster, listOf("uiContext", "altScreen", "uiSync"), transport,
            ).enabledFeatures,
        )
        assertFalse(
            AirPlayFeatureNegotiation.propose(local, listOf("uiSync"), transport)
                .enabledFeatures.contains("uiSync"),
        )

        val exchange = AirPlayFeatureNegotiation.exchange(mainOnly)
        val before = AirPlayInfoPlist.build(mainOnly, exchange.infoScope(transport))
        assertEquals(1, (before["displays"] as List<*>).size)
        assertFalse(before.containsKey("displayPanels"))
        assertFalse(before.containsKey("uiSyncInfo"))
        assertEquals(emptyList<String>(), before["uiContextLastOnDisplayURLs"])
        assertEquals(listOf("xcertplay://main/custom"), before["uiContextNowOnDisplayURLs"])
        assertEquals(
            listOf("uiContext"),
            exchange.negotiate(listOf("uiContext", "uiSync"), transport).enabledFeatures,
        )
        assertArrayEquals(
            BplistCodec.encode(before),
            BplistCodec.encode(AirPlayInfoPlist.build(mainOnly, exchange.infoScope())),
        )

        val setupFirst = AirPlayFeatureNegotiation.exchange(mainOnly)
        assertEquals(listOf("uiContext", "uiSync"),
            setupFirst.negotiate(listOf("uiContext", "uiSync"), transport).enabledFeatures)
        val negotiatedInfo = AirPlayInfoPlist.build(mainOnly, setupFirst.infoScope(transport))
        assertEquals(ultra.uiSyncInfo?.toWireMap(), negotiatedInfo["uiSyncInfo"])
        assertEquals(1, (negotiatedInfo["displayPanels"] as List<*>).size)

        val contextOnly = AirPlayFeatureNegotiation.exchange(mainOnly)
        assertEquals(listOf("uiContext"), contextOnly.negotiate(
            listOf("uiContext"), transport,
        ).enabledFeatures)
        val contextOnlyInfo = AirPlayInfoPlist.build(mainOnly, contextOnly.infoScope())
        assertFalse(contextOnlyInfo.containsKey("displayPanels"))
        assertFalse(contextOnlyInfo.containsKey("uiSyncInfo"))
    }

    @Test
    fun mainDdpMetadataAloneAndIncompleteRendererCannotMakeUiSyncReady() {
        val mainPanel = standaloneMainDdpPanel()
        val complete = mainUiSyncUltra()
        val withoutDdp = AirPlayFeatureNegotiation.snapshot(config(ultra = complete))
        assertFalse(withoutDdp.isReady(AirPlayFeature.UI_CONTEXT))
        assertFalse(withoutDdp.isReady(AirPlayFeature.UI_SYNC))

        val onlyMetadata = AirPlayFeatureNegotiation.snapshot(
            config().withLivePhysicalPanels(mainPanel),
        )
        assertFalse(onlyMetadata.isReady(AirPlayFeature.UI_CONTEXT))
        assertFalse(onlyMetadata.isReady(AirPlayFeature.UI_SYNC))
        assertFalse(AirPlayFeatureNegotiation.snapshot(
            config(ultra = complete).copy(displayPanels = mainPanel),
        ).isReady(AirPlayFeature.UI_SYNC))
        assertFalse(AirPlayFeatureNegotiation.snapshot(
            config(ultra = complete).copy(
                displayPanels = standaloneMainDdpPanel(),
                displayPanelsLiveness = AirPlayPhysicalDisplayPanelsLiveness(
                    mainPanel, { true }, { java.io.Closeable {} },
                ),
            ),
        ).isReady(AirPlayFeature.UI_SYNC))

        val incomplete = uiSyncUltra(
            displayIds = setOf(AirPlayInfoPlist.MAIN_UUID),
            receiptBackendLive = false,
            physicalDestinations = listOf(UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN)),
        )
        val unready = AirPlayFeatureNegotiation.snapshot(
            config(ultra = incomplete).withLivePhysicalPanels(mainPanel),
        )
        assertFalse(unready.isReady(AirPlayFeature.UI_CONTEXT))
        assertFalse(unready.isReady(AirPlayFeature.UI_SYNC))

        val stalePhysical = uiSyncUltra(
            displayIds = setOf(AirPlayInfoPlist.MAIN_UUID),
            physicalDestinations = listOf(UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN)),
            physicalPreflightProbe = { false },
        )
        assertFalse(AirPlayFeatureNegotiation.snapshot(
            config(ultra = stalePhysical).withLivePhysicalPanels(mainPanel),
        ).isReady(AirPlayFeature.UI_SYNC))

        val detachedSidecar = complete.copy(uiSyncInfo = AirPlayUiSyncInfo(
            requireNotNull(complete.uiSyncInfo).toWireMap(),
        ))
        assertFalse(AirPlayFeatureNegotiation.snapshot(
            config(ultra = detachedSidecar).withLivePhysicalPanels(mainPanel),
        ).isReady(AirPlayFeature.UI_SYNC))

        val missingHandler = complete.copy(readiness = complete.readiness.copy(
            rcsHandlers = emptyList(),
        ))
        assertFalse(AirPlayFeatureNegotiation.snapshot(
            config(ultra = missingHandler).withLivePhysicalPanels(mainPanel),
        ).isReady(AirPlayFeature.UI_SYNC))

        val exact = AirPlayFeatureNegotiation.snapshot(
            config(ultra = complete).withLivePhysicalPanels(mainPanel),
        )
        val withoutEvent = AirPlayFeatureNegotiation.propose(
            exact,
            listOf("uiContext", "uiSync"),
            fullTransport(alternate = false).copy(eventChannel = false),
        )
        assertFalse(withoutEvent.capabilities.supports(AirPlayFeature.UI_SYNC))
        assertTrue(withoutEvent.enabledFeatures.isEmpty())
    }

    @Test
    fun mainDdpPhysicalPreflightIsRecheckedAfterInfoBeforeSetup() {
        var physicalCurrent = true
        val ultra = uiSyncUltra(
            displayIds = setOf(AirPlayInfoPlist.MAIN_UUID),
            physicalDestinations = listOf(UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN)),
            physicalPreflightProbe = { physicalCurrent },
        )
        val config = config(ultra = ultra).withLivePhysicalPanels(standaloneMainDdpPanel())
        val exchange = AirPlayFeatureNegotiation.exchange(config)
        val transport = fullTransport(alternate = false)
        val before = AirPlayInfoPlist.build(config, exchange.infoScope(transport))
        assertFalse(before.containsKey("uiSyncInfo"))
        assertTrue(AirPlayFeatureNegotiation.snapshot(config).isReady(AirPlayFeature.UI_SYNC))

        physicalCurrent = false
        val proposal = exchange.negotiate(listOf("uiContext", "uiSync"), transport)
        assertFalse(proposal.capabilities.supports(AirPlayFeature.UI_SYNC))
        assertTrue(proposal.enabledFeatures.isEmpty())
        assertThrows(AirPlayFeatureScopeRevokedException::class.java) {
            exchange.infoScope(transport)
        }
    }

    @Test
    fun mainDdpPanelLivenessIsRecheckedAfterInfoBeforeSetup() {
        var panelCurrent = true
        val panels = standaloneMainDdpPanel()
        val config = config(ultra = mainUiSyncUltra()).withLivePhysicalPanels(panels) {
            panelCurrent
        }
        val exchange = AirPlayFeatureNegotiation.exchange(config)
        val transport = fullTransport(alternate = false)
        val before = AirPlayInfoPlist.build(config, exchange.infoScope(transport))
        assertFalse(before.containsKey("uiSyncInfo"))

        panelCurrent = false
        val proposal = exchange.negotiate(listOf("uiContext", "uiSync"), transport)
        assertTrue(proposal.enabledFeatures.isEmpty())
        assertFalse(proposal.capabilities.supports(AirPlayFeature.UI_SYNC))
    }

    @Test
    fun setupFirstRejectsInfoAfterMainPhysicalPreflightIsLost() {
        var physicalCurrent = true
        val ultra = uiSyncUltra(
            displayIds = setOf(AirPlayInfoPlist.MAIN_UUID),
            physicalDestinations = listOf(UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN)),
            physicalPreflightProbe = { physicalCurrent },
        )
        val config = config(ultra = ultra).withLivePhysicalPanels(standaloneMainDdpPanel())
        val exchange = AirPlayFeatureNegotiation.exchange(config)
        val transport = fullTransport(alternate = false)
        assertEquals(
            listOf("uiContext", "uiSync"),
            exchange.negotiate(listOf("uiContext", "uiSync"), transport).enabledFeatures,
        )

        physicalCurrent = false
        assertThrows(AirPlayFeatureScopeRevokedException::class.java) {
            exchange.infoScope(transport)
        }
        physicalCurrent = true
        assertThrows(AirPlayFeatureScopeRevokedException::class.java) {
            exchange.infoScope(transport)
        }
    }

    @Test
    fun legacyAltUiSyncAlsoRejectsPreflightLostAfterSnapshot() {
        var physicalCurrent = true
        val ultra = uiSyncUltra(physicalPreflightProbe = { physicalCurrent })
        val local = AirPlayFeatureNegotiation.snapshot(config(cluster = cluster, ultra = ultra))
        assertTrue(local.isReady(AirPlayFeature.UI_SYNC))

        physicalCurrent = false
        val proposal = AirPlayFeatureNegotiation.propose(
            local, listOf("uiContext", "altScreen", "uiSync"), fullTransport(),
        )
        assertEquals(listOf("uiContext", "altScreen"), proposal.enabledFeatures)
        assertFalse(proposal.capabilities.supports(AirPlayFeature.UI_SYNC))
    }

    @Test
    fun mainOnlyDdpRejectsMismatchedGeometryAndNonExactManagedStream() {
        val ultra = mainUiSyncUltra()
        val fullPanel = standaloneMainDdpPanel()
        val stream = AirPlayPhysicalVideoStream(AirPlayInfoPlist.MAIN_UUID, 0, 0, 0)
        val mismatchedGeometry = AirPlayPhysicalDisplayPanels(listOf(
            AirPlayPhysicalDisplayPanel(
                widthPixels = 1280,
                heightPixels = 720,
                widthPhysical = 201.0,
                heightPhysical = 113.0,
                initialVideoStreams = listOf(stream),
                displayPlugins = listOf(AirPlayPhysicalDisplayPlugin(
                    AirPlayInfoPlist.MAIN_UUID, listOf(stream),
                )),
                properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
            ),
        ))
        val mismatchedManaged = AirPlayPhysicalDisplayPanels(listOf(
            AirPlayPhysicalDisplayPanel(
                widthPixels = 1280,
                heightPixels = 720,
                widthPhysical = 200.0,
                heightPhysical = 113.0,
                initialVideoStreams = listOf(stream),
                displayPlugins = listOf(AirPlayPhysicalDisplayPlugin(
                    AirPlayInfoPlist.MAIN_UUID, listOf(stream.copy(originXPixels = 1)),
                )),
                properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
            ),
        ))
        listOf(mismatchedGeometry, mismatchedManaged).forEach { panels ->
            val local = AirPlayFeatureNegotiation.snapshot(
                config(ultra = ultra).withLivePhysicalPanels(panels),
            )
            assertFalse(local.isReady(AirPlayFeature.UI_SYNC))
        }
        assertTrue(
            AirPlayFeatureNegotiation.snapshot(config(ultra = ultra).withLivePhysicalPanels(fullPanel))
                .isReady(AirPlayFeature.UI_SYNC),
        )
    }

    @Test
    fun mainDdpCannotBorrowClusterOrAnUnadvertisedClusterPlugin() {
        val mainPanel = standaloneMainDdpPanel()
        val clusterRouted = uiSyncUltra(
            displayIds = setOf(AirPlayInfoPlist.MAIN_UUID),
            physicalDestinations = listOf(UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.CLUSTER)),
        )
        val twoOutputs = uiSyncUltra(
            displayIds = linkedSetOf(AirPlayInfoPlist.MAIN_UUID, AirPlayInfoPlist.ALT_UUID),
            physicalDestinations = listOf(
                UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN),
                UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.CLUSTER),
            ),
        )
        listOf(clusterRouted, twoOutputs).forEach { ultra ->
            assertTrue(requireNotNull(ultra.readiness.uiSync).isComplete)
            val mainOnly = AirPlayFeatureNegotiation.snapshot(
                config(ultra = ultra).withLivePhysicalPanels(mainPanel),
            )
            assertFalse(mainOnly.isReady(AirPlayFeature.UI_CONTEXT))
            assertFalse(mainOnly.isReady(AirPlayFeature.UI_SYNC))
        }

        // These same complete descriptors remain eligible for the established ALT+UI_CONTEXT
        // route when a real alternate sink is present; only the new MAIN-only exception is gated.
        val legacy = AirPlayFeatureNegotiation.snapshot(
            config(cluster = cluster, ultra = clusterRouted).withLivePhysicalPanels(mainPanel),
        )
        assertTrue(legacy.isReady(AirPlayFeature.UI_SYNC))
        assertEquals(
            listOf("uiContext", "altScreen", "uiSync"),
            AirPlayFeatureNegotiation.propose(
                legacy, listOf("uiContext", "altScreen", "uiSync"), fullTransport(),
            ).enabledFeatures,
        )
        assertFalse(
            AirPlayFeatureNegotiation.propose(
                legacy, listOf("uiContext", "uiSync"), fullTransport(alternate = false),
            ).enabledFeatures.contains("uiSync"),
        )
    }

    @Test
    fun exactMainAndClusterPhysicalPreflightKeepsDdpAndUiSyncUntilEitherTargetRetires() {
        var mainTargetCurrent = true
        var clusterTargetCurrent = true
        val ultra = uiSyncUltra(
            displayIds = linkedSetOf(AirPlayInfoPlist.MAIN_UUID, AirPlayInfoPlist.ALT_UUID),
            physicalDestinations = listOf(
                UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN),
                UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.CLUSTER),
            ),
            physicalPreflightProbe = { mainTargetCurrent && clusterTargetCurrent },
        )
        val config = config(cluster = cluster, ultra = ultra)
            .withLivePhysicalPanels(standaloneMainDdpPanel(includeCluster = true))
            .copy(requireMainUiSyncForDisplayPanels = true)
        val local = AirPlayFeatureNegotiation.snapshot(config)
        assertTrue(local.mainDdpUiSyncReady)
        assertTrue(local.isReady(AirPlayFeature.UI_SYNC))
        assertEquals(
            listOf("uiContext"),
            AirPlayFeatureNegotiation.propose(
                local, listOf("uiContext", "uiSync"), fullTransport(),
            ).enabledFeatures,
        )
        assertEquals(
            listOf("uiContext"),
            AirPlayFeatureNegotiation.propose(
                local, listOf("uiContext", "altScreen", "uiSync"),
                fullTransport(alternate = false),
            ).enabledFeatures,
        )
        val noAlt = AirPlayFeatureNegotiation.exchange(config)
        assertEquals(listOf("uiContext"), noAlt.negotiate(
            listOf("uiContext", "uiSync"), fullTransport(),
        ).enabledFeatures)
        val noAltInfo = AirPlayInfoPlist.build(config, noAlt.infoScope())
        assertFalse(noAltInfo.containsKey("displayPanels"))
        assertFalse(noAltInfo.containsKey("uiSyncInfo"))

        val setupFirst = AirPlayFeatureNegotiation.exchange(config)
        assertEquals(listOf("uiContext", "altScreen", "uiSync"), setupFirst.negotiate(
            listOf("uiContext", "altScreen", "uiSync"), fullTransport(),
        ).enabledFeatures)
        val acceptedScope = setupFirst.infoScope()
        val inconsistentScope = AirPlayInfoFeatureScope(
            setOf(AirPlayFeature.UI_CONTEXT, AirPlayFeature.UI_SYNC),
            mapOf(AirPlayFeature.UI_SYNC to requireNotNull(
                acceptedScope.sidecar(AirPlayFeature.UI_SYNC),
            )),
        )
        assertThrows(AirPlayFeatureScopeRevokedException::class.java) {
            AirPlayInfoPlist.build(config, inconsistentScope)
        }
        val completeInfo = AirPlayInfoPlist.build(config, acceptedScope)
        assertEquals(2, (completeInfo["displayPanels"] as List<*>).size)
        assertNotNull(completeInfo["uiSyncInfo"])
        assertEquals(2, (completeInfo["displays"] as List<*>).size)

        clusterTargetCurrent = false
        assertFalse(
            AirPlayFeatureNegotiation.propose(
                local, listOf("uiContext", "altScreen", "uiSync"), fullTransport(),
            ).capabilities.supports(AirPlayFeature.UI_SYNC),
        )
        assertFalse(AirPlayInfoPlist.build(config, acceptedScope).containsKey("displayPanels"))
        assertThrows(AirPlayFeatureScopeRevokedException::class.java) {
            setupFirst.infoScope()
        }
    }

    @Test
    fun mainDdpRejectsClusterOutputRelabeledMainAndNondefaultMainDestination() {
        val borrowedClusterOutput = uiSyncUltra(
            displayIds = setOf(AirPlayInfoPlist.ALT_UUID),
            physicalDestinations = listOf(UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN)),
        )
        val nondefaultMain = uiSyncUltra(
            displayIds = setOf(AirPlayInfoPlist.MAIN_UUID),
            physicalDestinations = listOf(UiSyncPhysicalDestination(
                UiSyncPhysicalDestinationRole.MAIN, "preview",
            )),
        )
        listOf(borrowedClusterOutput, nondefaultMain).forEach { ultra ->
            assertTrue(requireNotNull(ultra.readiness.uiSync).isComplete)
            val local = AirPlayFeatureNegotiation.snapshot(
                config(ultra = ultra).withLivePhysicalPanels(standaloneMainDdpPanel()),
            )
            assertFalse(local.mainDdpUiSyncReady)
            assertFalse(local.isReady(AirPlayFeature.UI_SYNC))
        }
    }

    @Test
    fun physicalAvailabilityRejectsRouteWithCopiedUnsignedOutputInstance() {
        val mainOutput = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID.fromString(AirPlayInfoPlist.MAIN_UUID)),
            UltraDisplayOutputId.Custom("main-output"),
            UltraUiSyncDisplayId(AirPlayInfoPlist.MAIN_UUID),
        )
        val clusterOutput = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID.fromString(AirPlayInfoPlist.ALT_UUID)),
            UltraDisplayOutputId.Custom("cluster-output"),
            UltraUiSyncDisplayId(AirPlayInfoPlist.ALT_UUID),
        )
        val assignments = listOf(
            UiSyncPhysicalOutputAssignment(mainOutput,
                UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN)),
            UiSyncPhysicalOutputAssignment(clusterOutput,
                UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.CLUSTER)),
        )
        val requirement = UiSyncPhysicalOutputRequirement(
            UltraProfileDigest(ByteArray(32) { 7 }), assignments,
        )
        val context = UiSyncPhysicalOutputPreflightContext(
            requirement,
            UiSyncRendererStaticPlan(listOf(mainOutput, clusterOutput), emptyList()),
            UiSyncSchemaFingerprint(ByteArray(32) { 1 }),
            UiSyncBindingFingerprint(ByteArray(32) { 2 }),
        )
        val forgedMain = assignments[0].copy(output = UltraDisplayGraphOutput(
            mainOutput.topologyDisplayId, mainOutput.outputId, mainOutput.uiSyncDisplayId,
        ))
        assertThrows(IllegalArgumentException::class.java) {
            context.issueAvailability(listOf(
                UiSyncPhysicalOutputRoute(forgedMain,
                    UiSyncPhysicalTargetVersion("main-target", 1)),
                UiSyncPhysicalOutputRoute(assignments[1],
                    UiSyncPhysicalTargetVersion("cluster-target", 1)),
            )) { true }
        }
    }

    @Test
    fun provisionedUiSyncAcceptsMultipleOpaqueDisplaysWithoutInferringAltUuid() {
        val displays = linkedSetOf("driver-cluster", "passenger-panel", "rear-seat.left")
        val ultra = uiSyncUltra(displays)

        assertEquals(displays, ultra.uiSyncInfo?.declaredDisplayIds)
        assertEquals(displays, ultra.readiness.uiSync?.displayIds)
        assertFalse(ultra.uiSyncInfo!!.toWireMap().containsKey("displayIds"))
        assertTrue(
            AirPlayFeatureNegotiation.snapshot(config(cluster = cluster, ultra = ultra))
                .isReady(AirPlayFeature.UI_SYNC),
        )
    }

    @Test
    fun callerCreatedSidecarAndLegacyCompleteLookingDescriptorCannotForgeReady() {
        val provisioned = uiSyncUltra(setOf("opaque-cluster"))
        val detachedSidecar = provisioned.copy(
            uiSyncInfo = AirPlayUiSyncInfo(provisioned.uiSyncInfo!!.toWireMap()),
        )
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(config(cluster = cluster, ultra = detachedSidecar))
                .isReady(AirPlayFeature.UI_SYNC),
        )

        val original = requireNotNull(provisioned.readiness.uiSync)
        val publicDescriptor = AirPlayUiSyncReadinessDescriptor(
            displayIds = original.displayIds,
            clusterControl = original.clusterControl,
            endpointCount = original.endpointCount,
            outerCarrier = original.outerCarrier,
            stateMachineConsumer = original.stateMachineConsumer,
            renderer = original.renderer,
            schemaFingerprint = original.schemaFingerprint,
            bindingFingerprint = original.bindingFingerprint,
        )
        val forged = provisioned.copy(
            readiness = provisioned.readiness.copy(uiSync = publicDescriptor),
        )
        assertFalse(publicDescriptor.isComplete)
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(config(cluster = cluster, ultra = forged))
                .isReady(AirPlayFeature.UI_SYNC),
        )
    }

    @Test
    fun staticFactoryReceiptMustMatchProvisioningDisplaysAndFingerprints() {
        val displays = linkedSetOf("cluster-a", "cluster-b")
        val wrongDisplay = uiSyncUltra(
            displayIds = displays,
            receiptDisplayIds = setOf("cluster-a", "foreign"),
        )
        val missingSubscription = uiSyncUltra(
            displayIds = displays,
            receiptSubscribedDisplayIds = setOf("cluster-a"),
        )
        val wrongSchema = uiSyncUltra(displays, receiptSchemaByte = 0x41)
        val wrongBinding = uiSyncUltra(displays, receiptBindingByte = 0x42)
        val incompleteBackend = uiSyncUltra(displays, receiptBackendLive = false)

        // Caller-populated subscription claims are deliberately ignored by the static factory
        // receipt. Only the session's observed subscriptions can prove ACTIVE later.
        assertTrue(requireNotNull(missingSubscription.readiness.uiSync).isComplete)
        listOf(wrongDisplay, wrongSchema, wrongBinding, incompleteBackend).forEach { ultra ->
            assertFalse(requireNotNull(ultra.readiness.uiSync).isComplete)
            assertFalse(
                AirPlayFeatureNegotiation.snapshot(config(cluster = cluster, ultra = ultra))
                    .isReady(AirPlayFeature.UI_SYNC),
            )
        }
    }

    @Test
    fun uiSyncDisplayDeclarationsAndEndpointRegistriesAreStrictlyBounded() {
        val maximum = (0 until MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS).map { "display-$it" }
        assertEquals(maximum.toSet(), uiSyncUltra(maximum.toSet()).uiSyncInfo?.declaredDisplayIds)
        val schemaFingerprint = UiSyncSchemaFingerprint(ByteArray(32) { 1 })
        val bindingFingerprint = UiSyncBindingFingerprint(ByteArray(32) { 2 })

        val dishonestSize = object : AbstractCollection<String>() {
            override val size: Int
                get() = throw AssertionError("display declaration size must not be trusted")
            override fun iterator(): Iterator<String> = listOf("opaque-display").iterator()
        }
        assertEquals(
            setOf("opaque-display"),
            AirPlayUiSyncInfo().withProvisionedDisplays(
                dishonestSize,
                Any(),
                schemaFingerprint,
                bindingFingerprint,
            ).declaredDisplayIds,
        )

        val excessive = (0..MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS).map { "display-$it" }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayUiSyncInfo().withProvisionedDisplays(
                excessive,
                Any(),
                schemaFingerprint,
                bindingFingerprint,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayUiSyncReadinessDescriptor(
                displayIds = excessive,
                clusterControl = ready(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL),
                endpointCount = excessive.size,
                outerCarrier = AirPlayReadiness.READY,
                stateMachineConsumer = AirPlayReadiness.READY,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayUiSyncInfo().withProvisionedDisplays(
                listOf("duplicate", "duplicate"),
                Any(),
                schemaFingerprint,
                bindingFingerprint,
            )
        }
    }

    @Test
    fun knownButUnimplementedFeaturesCannotBeConfiguredOrNegotiated() {
        listOf(
            AirPlayFeature.LOG_TRANSFER,
            AirPlayFeature.VIDEO_PLAYBACK,
            AirPlayFeature.MAIN_BUFFERED,
        ).forEach { feature ->
            assertThrows(IllegalArgumentException::class.java) {
                AirPlayUltraConfiguration(configuredFeatures = setOf(feature))
            }
        }

        val proposal = AirPlayFeatureNegotiation.propose(
            AirPlaySessionCapabilityInput(config(), fullTransport(alternate = false)),
            listOf("fileTransfer", "logTransfer", "sessionManagement", "videoPlayback", "mainBuffered"),
        )
        assertTrue(proposal.enabledFeatures.isEmpty())
    }

    @Test
    fun sessionManagementSidecarIsTypedAndRequiresBuiltInConsumerEvidence() {
        val info = AirPlaySessionManagementInfo(
            stopSession = 1,
            stopSessionReasons = listOf(2, 1),
        )
        assertEquals(
            linkedMapOf(
                "stopSession" to 1L,
                "stopSessionReasons" to listOf(2L, 1L),
            ),
            info.toWireMap(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AirPlaySessionManagementInfo(0, listOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlaySessionManagementInfo(1, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlaySessionManagementInfo(1, listOf(3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlaySessionManagementInfo.fromWireMap(
                mapOf("stopSession" to 1.0, "stopSessionReasons" to listOf(1)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlaySessionManagementInfo.fromWireMap(
                mapOf("stopSession" to 1, "stopSessionReasons" to listOf(1.0)),
            )
        }

        val noConsumer = AirPlayUltraConfiguration(
            configuredFeatures = setOf(AirPlayFeature.SESSION_MANAGEMENT),
            sessionManagementInfo = info,
        )
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(config(ultra = noConsumer))
                .isReady(AirPlayFeature.SESSION_MANAGEMENT),
        )

        val complete = AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = setOf(AirPlayFeature.SESSION_MANAGEMENT),
            sessionManagementInfo = info,
        )
        assertTrue(
            AirPlayFeatureNegotiation.snapshot(config(ultra = complete))
                .isReady(AirPlayFeature.SESSION_MANAGEMENT),
        )
        val incomplete = complete.copy(
            readiness = complete.readiness.copy(
                sessionManagement = AirPlaySessionManagementReadinessDescriptor(
                    implementationId =
                        AirPlaySessionManagementReadinessDescriptor.BUILT_IN_IMPLEMENTATION_ID,
                    commandConsumer = AirPlayReadiness.DIAGNOSTIC_ONLY,
                ),
            ),
        )
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(config(ultra = incomplete))
                .isReady(AirPlayFeature.SESSION_MANAGEMENT),
        )
    }

    @Test
    fun ultraCapabilitiesAreWirelessOnlyWithoutChangingOrdinaryFeatures() {
        val sessionInfo = AirPlaySessionManagementInfo(1, listOf(1, 2))
        val sessionUltra = AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = setOf(AirPlayFeature.SESSION_MANAGEMENT),
            sessionManagementInfo = sessionInfo,
        )
        val sessionLocal = AirPlayFeatureNegotiation.snapshot(
            config(cluster = cluster, hevc = true, ultra = sessionUltra),
        )
        val requestedOrdinary = listOf(
            "uiContext", "viewAreas", "altScreen", "hevc", "sessionManagement", "iAPChannel",
        )
        val wiredProposal = AirPlayFeatureNegotiation.propose(
            sessionLocal,
            requestedOrdinary,
            fullTransport().copy(wirelessSession = false),
        )
        assertEquals(
            listOf("uiContext", "viewAreas", "altScreen", "hevc", "iAPChannel"),
            wiredProposal.enabledFeatures,
        )
        assertEquals(
            requestedOrdinary,
            AirPlayFeatureNegotiation.propose(
                sessionLocal,
                requestedOrdinary,
                fullTransport(),
            ).enabledFeatures,
        )
        assertFalse(
            AirPlayFeatureNegotiation.propose(
                sessionLocal,
                listOf("sessionManagement"),
                fullTransport().copy(secureControlChannel = false),
            ).capabilities.supports(AirPlayFeature.SESSION_MANAGEMENT),
        )

        val ultraLocal = AirPlayFeatureNegotiation.snapshot(
            config(cluster = cluster, ultra = combinedUltra()),
        )
        val ultraWired = AirPlayFeatureNegotiation.propose(
            ultraLocal,
            listOf("uiContext", "altScreen", "vehicleStateProtocol", "uiSync"),
            fullTransport().copy(wirelessSession = false),
        )
        assertEquals(listOf("uiContext", "altScreen"), ultraWired.enabledFeatures)

        val exchange = AirPlayFeatureNegotiation.exchange(
            config(cluster = cluster, ultra = sessionUltra),
        )
        exchange.negotiate(listOf("sessionManagement"), fullTransport())
        assertEquals(
            sessionInfo.toWireMap(),
            AirPlayInfoPlist.build(
                config(cluster = cluster, ultra = sessionUltra),
                exchange.infoScope(),
            )["sessionManagementInfo"],
        )
    }

    @Test
    fun rcsTransportReadinessIsGatedPerFeatureWithoutCrossDisablingUiSync() {
        val local = AirPlayFeatureNegotiation.snapshot(
            config(cluster = cluster, ultra = combinedUltra()),
        )
        val readyTypes = linkedSetOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)
        val uiSyncOnlyTransport = fullTransport().copy(
            readyRcsClientTypes = readyTypes,
        )
        readyTypes.clear()
        val proposal = AirPlayFeatureNegotiation.propose(
            local,
            listOf("uiContext", "altScreen", "vehicleStateProtocol", "uiSync"),
            uiSyncOnlyTransport,
        )

        assertEquals(listOf("uiContext", "altScreen", "uiSync"), proposal.enabledFeatures)
        assertEquals(
            setOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid),
            uiSyncOnlyTransport.readyRcsClientTypeUuids,
        )
        assertTrue(proposal.capabilities.supports(AirPlayFeature.UI_SYNC))
        assertFalse(proposal.capabilities.supports(AirPlayFeature.VEHICLE_STATE_PROTOCOL))
    }

    @Test
    fun setupBeforeInfoEmitsExactlyNegotiatedReadySidecarsAndOneClusterDisplay() {
        val ultra = combinedUltra()
        val config = config(cluster = cluster, hevc = true, ultra = ultra)
        val exchange = AirPlayFeatureNegotiation.exchange(config)
        val proposal = exchange.negotiate(
            listOf(
                "uiContext", "viewAreas", "altScreen", "vehicleStateProtocol", "uiSync",
                "hevc", "iAPChannel",
            ),
            fullTransport(),
        )
        val info = AirPlayInfoPlist.build(config, exchange.infoScope())

        assertEquals(
            listOf(
                "uiContext", "viewAreas", "altScreen", "vehicleStateProtocol", "uiSync",
                "hevc", "iAPChannel",
            ),
            proposal.enabledFeatures,
        )
        assertNotNull(info["vehicleStateProtocolInfo"])
        assertNotNull(info["uiSyncInfo"])
        assertNotNull(info["hevcInfo"])
        val displays = (info["displays"] as List<*>).map { it as Map<*, *> }
        assertEquals(1, displays.count { it["type"] == 111 })
        assertEquals(cluster.widthPixels, displays.single { it["type"] == 111 }["widthPixels"])

        @Suppress("UNCHECKED_CAST")
        val leakedPlugins = (info["vehicleStateProtocolInfo"] as MutableMap<String, Any?>)
            .get("pluginConfigs") as MutableList<Any?>
        leakedPlugins.clear()
        val rebuilt = AirPlayInfoPlist.build(config, exchange.infoScope())
        assertEquals(
            1,
            ((rebuilt["vehicleStateProtocolInfo"] as Map<*, *>)["pluginConfigs"] as List<*>).size,
        )
    }

    @Test
    fun displayControlComposesOrdinaryUiAndUltraNegotiationWithoutReplacingEither() {
        val config = config(cluster = cluster, ultra = combinedUltra())
        val control = AirPlayDisplayControlFactory.create(config, { true }, {})
        val enabled = control.negotiate(
            listOf("uiContext", "viewAreas", "altScreen", "vehicleStateProtocol", "uiSync"),
            fullTransport(),
        )

        val info = control.info(
            mapOf("altScreenURLs" to listOf("xcertplay://cluster/custom")),
        )
        assertEquals(
            listOf("uiContext", "viewAreas", "altScreen", "vehicleStateProtocol", "uiSync"),
            enabled,
        )
        assertNotNull(info["vehicleStateProtocolInfo"])
        assertNotNull(info["uiSyncInfo"])
        assertTrue(info.containsKey("uiContextNowOnDisplayURLs"))
        val displays = (info["displays"] as List<*>).map { it as Map<*, *> }
        assertEquals(1, displays.count { it["type"] == 110 })
        assertEquals(1, displays.count { it["type"] == 111 })
    }

    @Test
    fun setupBeforeInfoOmitsReadyButUnrequestedSidecars() {
        val config = config(cluster = cluster, hevc = true, ultra = combinedUltra())
        val exchange = AirPlayFeatureNegotiation.exchange(config)
        exchange.negotiate(listOf("uiContext", "viewAreas", "altScreen", "iAPChannel"), fullTransport())

        val info = AirPlayInfoPlist.build(config, exchange.infoScope())
        assertFalse(info.containsKey("vehicleStateProtocolInfo"))
        assertFalse(info.containsKey("uiSyncInfo"))
        assertFalse(info.containsKey("hevcInfo"))
        assertEquals(2, (info["displays"] as List<*>).size)
    }

    @Test
    fun infoBeforeSetupFreezesOrdinaryOnlyScopeRegardlessOfProvisionalTransport() {
        val config = config(cluster = cluster, hevc = true, ultra = combinedUltra())
        val exchange = AirPlayFeatureNegotiation.exchange(config)
        val beforeScope = exchange.infoScope(fullTransport())
        val before = AirPlayInfoPlist.build(config, beforeScope)

        // The standalone compatibility builder has no authenticated transport evidence.
        assertFalse(AirPlayInfoPlist.build(config).containsKey("vehicleStateProtocolInfo"))
        assertFalse(AirPlayInfoPlist.build(config).containsKey("uiSyncInfo"))
        assertFalse(before.containsKey("vehicleStateProtocolInfo"))
        assertFalse(before.containsKey("uiSyncInfo"))
        // HEVC is an established ordinary-CarPlay capability and remains master-compatible.
        assertNotNull(before["hevcInfo"])
        assertEquals(2, (before["displays"] as List<*>).size)

        val proposal = exchange.negotiate(
            listOf("uiContext", "viewAreas", "altScreen", "uiSync", "hevc", "iAPChannel"),
            fullTransport(),
        )
        assertEquals(
            listOf("uiContext", "viewAreas", "altScreen", "hevc", "iAPChannel"),
            proposal.enabledFeatures,
        )
        assertFalse("vehicleStateProtocol" in proposal.enabledFeatures)
        assertArrayEquals(
            BplistCodec.encode(before),
            BplistCodec.encode(AirPlayInfoPlist.build(config, exchange.infoScope())),
        )
    }

    @Test
    fun infoFirstUltraSidecarsStayAbsentEvenIfTransportWasReady() {
        val config = config(cluster = cluster, ultra = combinedUltra())
        val requested = listOf("uiContext", "altScreen", "vehicleStateProtocol", "uiSync")
        val cases = listOf(
            null,
            fullTransport().copy(secureControlChannel = false),
            fullTransport().copy(wirelessSession = false),
            fullTransport().copy(eventChannel = false),
            fullTransport().copy(readyRcsClientTypes = setOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)),
            fullTransport(),
        )
        cases.forEach { initialTransport ->
            val exchange = AirPlayFeatureNegotiation.exchange(config)
            val first = AirPlayInfoPlist.build(config, exchange.infoScope(initialTransport))
            assertEquals(emptySet<String>(), first.keys.filterTo(linkedSetOf()) {
                it == "vehicleStateProtocolInfo" || it == "uiSyncInfo"
            })
            val negotiated = exchange.negotiate(requested, fullTransport())
            assertEquals(listOf("uiContext", "altScreen"), negotiated.enabledFeatures)
            assertArrayEquals(
                BplistCodec.encode(first),
                BplistCodec.encode(AirPlayInfoPlist.build(config, exchange.infoScope(fullTransport()))),
            )
        }

        val exchange = AirPlayFeatureNegotiation.exchange(config)
        val advertised = AirPlayInfoPlist.build(config, exchange.infoScope(fullTransport()))
        val transportDropped = exchange.negotiate(
            requested,
            fullTransport().copy(readyRcsClientTypes = emptySet()),
        )
        assertEquals(listOf("uiContext", "altScreen"), transportDropped.enabledFeatures)
        assertArrayEquals(
            BplistCodec.encode(advertised),
            BplistCodec.encode(AirPlayInfoPlist.build(config, exchange.infoScope())),
        )
    }

    @Test
    fun infoFirstSessionManagementCannotPrecedeItsNegotiatedSidecar() {
        val info = AirPlaySessionManagementInfo(1, listOf(1, 2))
        val ready = AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = setOf(AirPlayFeature.SESSION_MANAGEMENT),
            sessionManagementInfo = info,
        )
        val config = config(ultra = ready)
        val advertised = AirPlayInfoPlist.build(
            config,
            AirPlayFeatureNegotiation.exchange(config).infoScope(fullTransport()),
        )
        assertFalse("sessionManagementInfo" in advertised)

        val wired = AirPlayInfoPlist.build(
            config,
            AirPlayFeatureNegotiation.exchange(config).infoScope(
                fullTransport().copy(wirelessSession = false),
            ),
        )
        assertFalse("sessionManagementInfo" in wired)

        val unavailable = config(ultra = ready.copy(readiness = AirPlayUltraReadiness()))
        val unready = AirPlayInfoPlist.build(
            unavailable,
            AirPlayFeatureNegotiation.exchange(unavailable).infoScope(fullTransport()),
        )
        assertFalse("sessionManagementInfo" in unready)
    }

    @Test
    fun insecureControlChannelEnablesNothing() {
        val proposal = AirPlayFeatureNegotiation.propose(
            AirPlaySessionCapabilityInput(
                config(cluster = cluster, hevc = true, ultra = combinedUltra()),
                fullTransport().copy(secureControlChannel = false),
            ),
            AirPlayFeature.entries.map(AirPlayFeature::wireName),
        )
        assertTrue(proposal.enabledFeatures.isEmpty())
    }

    private fun config(
        cluster: AirPlayDisplayConfig? = null,
        hevc: Boolean = false,
        ultra: AirPlayUltraConfiguration? = null,
    ) = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(1280, 720),
        cluster = cluster,
        hevc = hevc,
        ultra = ultra,
    )

    private fun fullTransport(alternate: Boolean = true) = AirPlayTransportReadiness(
        secureControlChannel = true,
        eventChannel = true,
        alternateScreenSink = alternate,
        readyRcsClientTypes = setOf(
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
            RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
        ),
        wirelessSession = true,
    )

    private fun standaloneMainDdpPanel(includeCluster: Boolean = false): AirPlayPhysicalDisplayPanels {
        val mainStream = AirPlayPhysicalVideoStream(AirPlayInfoPlist.MAIN_UUID, 0, 0, 0)
        val panels = mutableListOf(
            AirPlayPhysicalDisplayPanel(
                widthPixels = 1280,
                heightPixels = 720,
                widthPhysical = 200.0,
                heightPhysical = 113.0,
                initialVideoStreams = listOf(mainStream),
                displayPlugins = listOf(AirPlayPhysicalDisplayPlugin(
                    AirPlayInfoPlist.MAIN_UUID, listOf(mainStream),
                )),
                properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
            ),
        )
        if (includeCluster) {
            val clusterStream = AirPlayPhysicalVideoStream(AirPlayInfoPlist.ALT_UUID, 0, 0, 0)
            panels += AirPlayPhysicalDisplayPanel(
                widthPixels = cluster.widthPixels,
                heightPixels = cluster.heightPixels,
                widthPhysical = 200.0,
                heightPhysical = 120.0,
                initialVideoStreams = listOf(clusterStream),
                displayPlugins = listOf(AirPlayPhysicalDisplayPlugin(
                    AirPlayInfoPlist.ALT_UUID, listOf(clusterStream),
                )),
                properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_INSTRUMENT_CLUSTER),
            )
        }
        return AirPlayPhysicalDisplayPanels(panels)
    }

    private fun AirPlayConfig.withLivePhysicalPanels(
        panels: AirPlayPhysicalDisplayPanels,
        currentProbe: () -> Boolean = { true },
    ): AirPlayConfig = copy(
        displayPanels = panels,
        displayPanelsLiveness = AirPlayPhysicalDisplayPanelsLiveness(
            panels, currentProbe, { java.io.Closeable {} },
        ),
    )

    private fun ready(clientType: RcsClientType) = AirPlayRcsHandlerReadinessDescriptor(
        clientType = clientType,
        implementationId = "test.${clientType.name}",
        handler = AirPlayReadiness.READY,
        carrier = AirPlayReadiness.READY,
        consumer = AirPlayReadiness.READY,
    )

    private fun vehicleInfo() = AirPlayVehicleStateProtocolInfo(
        pluginConfigs = listOf(
            AirPlayVehicleStateProtocolPlugin(
                pluginId = 7,
                fields = mapOf("accessories" to emptyList<Any?>()),
            ),
        ),
        pluginMapping = mapOf("vehicle" to 7),
    )

    private fun vehicleUltra() = AirPlayUltraConfiguration(
        configuredFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
        vehicleStateProtocolInfo = vehicleInfo(),
        readiness = AirPlayUltraReadiness(
            rcsHandlers = listOf(
                ready(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
                ready(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2),
            ),
        ),
    )

    private fun uiSyncUltra(
        displayIds: Set<String> = setOf(AirPlayInfoPlist.ALT_UUID),
        receiptDisplayIds: Set<String> = displayIds,
        receiptSubscribedDisplayIds: Set<String> = receiptDisplayIds,
        receiptSchemaByte: Int = 1,
        receiptBindingByte: Int = 2,
        receiptBackendLive: Boolean = true,
        physicalDestinations: List<UiSyncPhysicalDestination>? = null,
        physicalPreflightProbe: () -> Boolean = { true },
    ): AirPlayUltraConfiguration {
        val schemaFingerprint = UiSyncSchemaFingerprint(ByteArray(32) { 1 })
        val bindingFingerprint = UiSyncBindingFingerprint(ByteArray(32) { 2 })
        val endpoints = displayIds.map { displayId ->
            CarPlayUiSyncEndpoint(
                descriptor = CarPlayUiSyncChannelDescriptor(displayId, "test-vehicle"),
                mode = CarPlayUiSyncMode.PASSIVE,
                presentationEventSink = CarPlayUiSyncPresentationEventSink { },
            )
        }
        val rcsFactory = RcsDataStreamHandlerFactory { clientType ->
            if (clientType != RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL) return@RcsDataStreamHandlerFactory null
            RcsDataStreamHandlerDescriptor(
                clientType = clientType,
                implementationId = "test.cluster-control",
                readinessProbe = { RcsDataStreamHandlerProbe.ready() },
                handlerFactory = { object : RcsDataStreamHandler {} },
            )
        }
        val outputResources = displayIds.mapIndexed { index, displayId ->
            UltraDisplayGraphOutput(
                topologyDisplayId = UltraTopologyDisplayId(
                    UUID.nameUUIDFromBytes("topology-$displayId".toByteArray()),
                ),
                outputId = UltraDisplayOutputId.Custom("output-$index"),
                uiSyncDisplayId = UltraUiSyncDisplayId(displayId),
            )
        }
        val plan = UiSyncRendererStaticPlan(outputResources, emptyList())
        val receiptContext = UiSyncCompleteRendererBindingContext(
            UiSyncRendererBindingContext(
                staticPlan = plan,
                schemaFingerprint = UiSyncSchemaFingerprint(
                    ByteArray(32) { receiptSchemaByte.toByte() },
                ),
                bindingFingerprint = UiSyncBindingFingerprint(
                    ByteArray(32) { receiptBindingByte.toByte() },
                ),
                displayIds = receiptDisplayIds,
            ),
        )
        val receipt = receiptContext.issueReadinessReceipt(
            semanticThemeProgramFingerprint = UiSyncSemanticThemeProgramFingerprint(
                ByteArray(32) { 3 },
            ),
            subscribedDisplayIds = receiptSubscribedDisplayIds,
            boundOutputIds = outputResources.map(UltraDisplayGraphOutput::outputId),
            semanticCompilerActive = true,
            themeCompilerActive = true,
            backendLive = receiptBackendLive,
        )
        val physicalRequirement = UiSyncPhysicalOutputRequirement(
            UltraProfileDigest(ByteArray(32) { 7 }),
            outputResources.mapIndexed { index, output ->
                UiSyncPhysicalOutputAssignment(
                    output,
                    physicalDestinations?.get(index) ?:
                        UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.AUXILIARY, "test-$index"),
                )
            },
        )
        val physicalPreflight = UiSyncPhysicalOutputPreflightContext(
            physicalRequirement, plan, schemaFingerprint, bindingFingerprint,
        ).issueAvailability(
            physicalRequirement.assignments.mapIndexed { index, assignment ->
                UiSyncPhysicalOutputRoute(
                    assignment, UiSyncPhysicalTargetVersion("test-target-$index", 1),
                )
            },
            currentProbe = physicalPreflightProbe,
        )
        return AirPlayUltraConfiguration.fromRuntimeProvisioning(
            configuredFeatures = setOf(AirPlayFeature.UI_SYNC),
            uiSyncInfo = AirPlayUiSyncInfo(),
            rcsFactory = rcsFactory,
            uiSyncEndpoints = endpoints,
            uiSyncRendererReadiness = AirPlayUiSyncRendererReadinessDescriptor.ready(
                "test.complete-renderer",
                physicalPreflight,
            ),
            uiSyncSchemaFingerprint = schemaFingerprint,
            uiSyncBindingFingerprint = bindingFingerprint,
            uiSyncProvisionedDisplayIds = displayIds,
            uiSyncRendererReceipt = receipt,
        )
    }

    private fun mainUiSyncUltra() = uiSyncUltra(
        displayIds = setOf(AirPlayInfoPlist.MAIN_UUID),
        physicalDestinations = listOf(UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN)),
    )

    private fun combinedUltra(): AirPlayUltraConfiguration {
        val vehicle = vehicleUltra()
        val uiSync = uiSyncUltra()
        return AirPlayUltraConfiguration(
            configuredFeatures = setOf(
                AirPlayFeature.VEHICLE_STATE_PROTOCOL,
                AirPlayFeature.UI_SYNC,
            ),
            vehicleStateProtocolInfo = vehicle.vehicleStateProtocolInfo,
            uiSyncInfo = uiSync.uiSyncInfo,
            readiness = AirPlayUltraReadiness(
                rcsHandlers = vehicle.readiness.rcsHandlers + uiSync.readiness.rcsHandlers,
                uiSync = uiSync.readiness.uiSync,
            ),
        )
    }

    private companion object {
        val cluster = AirPlayDisplayConfig(800, 480)
    }
}
