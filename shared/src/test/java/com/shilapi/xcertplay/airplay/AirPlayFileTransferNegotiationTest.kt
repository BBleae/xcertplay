package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayFileTransferNegotiationTest {
    @Test
    fun exactReadyHandlerAndBoundedConsumerRequireAuthenticatedRcsTransport() {
        val registration = updateRegistration()
        val proof = consumerProof(registration)
        val config = config(
            AirPlayUltraConfiguration.fromRuntimeDescriptors(
                configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
                fileTransferInfo = AirPlayFileTransferInfo(),
                rcsFactory = RcsDataStreamHandlerFactory { type ->
                    registration.takeIf { type == RcsClientTypes.CAR_PLAY_UPDATE_DATA }
                },
                updateDataConsumerReadiness = proof,
            ),
        )
        val local = AirPlayFeatureNegotiation.snapshot(config)
        assertTrue(local.isReady(AirPlayFeature.FILE_TRANSFER))
        assertEquals(emptyMap<String, Any?>(), AirPlayFileTransferInfo().toWireMap())

        val ready = transport()
        assertEquals(
            listOf("fileTransfer"),
            AirPlayFeatureNegotiation.propose(local, listOf("fileTransfer"), ready).enabledFeatures,
        )
        assertEquals(
            listOf("fileTransfer"),
            AirPlayFeatureNegotiation.propose(
                local,
                listOf("fileTransfer"),
                ready.copy(wirelessSession = false),
            ).enabledFeatures,
        )
        listOf(
            ready.copy(secureControlChannel = false),
            ready.copy(readyRcsClientTypes = emptySet()),
            ready.copy(readyRcsClientTypes = setOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)),
        ).forEach { weak ->
            assertFalse(
                AirPlayFeatureNegotiation.propose(
                    local,
                    listOf("fileTransfer"),
                    weak,
                ).enabledFeatureSet.contains(AirPlayFeature.FILE_TRANSFER),
            )
            assertFalse(
                AirPlayInfoPlist.build(config, AirPlayFeatureNegotiation.exchange(config).infoScope(weak))
                    .containsKey("fileTransferInfo"),
            )
        }
    }

    @Test
    fun setupBeforeInfoContainsOnlyNegotiatedFileTransferSidecar() {
        val config = readyConfig()
        val exchange = AirPlayFeatureNegotiation.exchange(config)
        exchange.negotiate(listOf("viewAreas", "fileTransfer"), transport())
        val info = AirPlayInfoPlist.build(config, exchange.infoScope())
        assertEquals(emptyMap<String, Any?>(), info["fileTransferInfo"])
        assertFalse(info.containsKey("logTransferInfo"))

        val unrequested = AirPlayFeatureNegotiation.exchange(config)
        unrequested.negotiate(listOf("viewAreas"), transport())
        assertFalse(AirPlayInfoPlist.build(config, unrequested.infoScope()).containsKey("fileTransferInfo"))
    }

    @Test
    fun infoBeforeSetupFreezesOrdinaryScopeEvenWhenTransportIsReady() {
        val config = readyConfig()
        val exchange = AirPlayFeatureNegotiation.exchange(config)
        val info = AirPlayInfoPlist.build(config, exchange.infoScope(transport()))
        assertFalse("fileTransferInfo" in info)
        assertTrue(exchange.negotiate(listOf("fileTransfer"), transport()).enabledFeatures.isEmpty())
        assertArrayEquals(
            BplistCodec.encode(info),
            BplistCodec.encode(AirPlayInfoPlist.build(config, exchange.infoScope(transport()))),
        )

        val weakFirst = AirPlayFeatureNegotiation.exchange(config)
        val ordinary = AirPlayInfoPlist.build(
            config,
            weakFirst.infoScope(transport().copy(secureControlChannel = false)),
        )
        assertFalse(ordinary.containsKey("fileTransferInfo"))
        assertFalse(
            weakFirst.negotiate(listOf("fileTransfer"), transport()).enabledFeatures
                .contains("fileTransfer"),
        )
    }

    @Test
    fun defaultProductAndUnboundConsumerKeepOrdinaryInfoUnchanged() {
        val ordinary = config(null)
        val regularInfo = AirPlayInfoPlist.build(ordinary)
        assertFalse(regularInfo.containsKey("fileTransferInfo"))
        val defaultReadyInfo = AirPlayInfoPlist.build(readyConfig())
        assertEquals(regularInfo.keys, defaultReadyInfo.keys)
        assertFalse(defaultReadyInfo.containsKey("fileTransferInfo"))

        val registration = updateRegistration()
        val withoutConsumer = AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
            fileTransferInfo = AirPlayFileTransferInfo(),
            rcsFactory = RcsDataStreamHandlerFactory { registration },
        )
        assertFalse(AirPlayFeatureNegotiation.snapshot(config(withoutConsumer))
            .isReady(AirPlayFeature.FILE_TRANSFER))
        assertFalse(AirPlayInfoPlist.build(config(withoutConsumer)).containsKey("fileTransferInfo"))

        val withoutSidecar = readyConfig().ultra!!.copy(fileTransferInfo = null)
        assertFalse(AirPlayFeatureNegotiation.snapshot(config(withoutSidecar))
            .isReady(AirPlayFeature.FILE_TRANSFER))
    }

    @Test
    fun missingWrongOrClonedRegistrationCannotUseConsumerProof() {
        val registration = updateRegistration()
        val proof = consumerProof(registration)
        val withoutHandler = AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
            fileTransferInfo = AirPlayFileTransferInfo(),
            rcsFactory = RcsDataStreamHandlerFactory { null },
            updateDataConsumerReadiness = proof,
        )
        assertFalse(AirPlayFeatureNegotiation.snapshot(config(withoutHandler))
            .isReady(AirPlayFeature.FILE_TRANSFER))

        val unboundManualReadiness = AirPlayUltraConfiguration(
            configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
            fileTransferInfo = AirPlayFileTransferInfo(),
            readiness = AirPlayUltraReadiness(
                rcsHandlers = listOf(AirPlayRcsHandlerReadinessDescriptor.capture(registration)),
                updateDataConsumer = proof,
            ),
        )
        assertFalse(AirPlayFeatureNegotiation.snapshot(config(unboundManualReadiness))
            .isReady(AirPlayFeature.FILE_TRANSFER))

        val clone = updateRegistration()
        val cloned = AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
            fileTransferInfo = AirPlayFileTransferInfo(),
            rcsFactory = RcsDataStreamHandlerFactory { clone },
            updateDataConsumerReadiness = proof,
        )
        assertFalse(AirPlayFeatureNegotiation.snapshot(config(cloned))
            .isReady(AirPlayFeature.FILE_TRANSFER))

        val wrongType = RcsDataStreamHandlerDescriptor.ready(
            RcsClientTypes.CAR_PLAY_LOGGING_DATA,
            "test.update-data",
        ) { object : RcsDataStreamHandler {} }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayUpdateDataConsumerReadinessDescriptor.capture(
                wrongType,
                AirPlayReadiness.READY,
            )
        }
        val wrongFactory = AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
            fileTransferInfo = AirPlayFileTransferInfo(),
            rcsFactory = RcsDataStreamHandlerFactory { wrongType },
            updateDataConsumerReadiness = proof,
        )
        assertFalse(AirPlayFeatureNegotiation.snapshot(config(wrongFactory))
            .isReady(AirPlayFeature.FILE_TRANSFER))
    }

    @Test
    fun oneCapacityProofCannotAuthorizeTwoFrozenConfigurations() {
        val registration = updateRegistration()
        val proof = consumerProof(registration)
        fun build() = AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
            fileTransferInfo = AirPlayFileTransferInfo(),
            rcsFactory = RcsDataStreamHandlerFactory { type ->
                registration.takeIf { type == RcsClientTypes.CAR_PLAY_UPDATE_DATA }
            },
            updateDataConsumerReadiness = proof,
        )

        assertTrue(AirPlayFeatureNegotiation.snapshot(config(build()))
            .isReady(AirPlayFeature.FILE_TRANSFER))
        assertFalse(AirPlayFeatureNegotiation.snapshot(config(build()))
            .isReady(AirPlayFeature.FILE_TRANSFER))
    }

    @Test
    fun consumerAdmissionMustRemainReady() {
        val registration = updateRegistration()
        val config = config(
            AirPlayUltraConfiguration.fromRuntimeDescriptors(
                configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
                fileTransferInfo = AirPlayFileTransferInfo(),
                rcsFactory = RcsDataStreamHandlerFactory { registration },
                updateDataConsumerReadiness = AirPlayUpdateDataConsumerReadinessDescriptor.capture(
                    registration,
                    AirPlayReadiness.UNAVAILABLE,
                ),
            ),
        )
        assertFalse(AirPlayFeatureNegotiation.snapshot(config)
            .isReady(AirPlayFeature.FILE_TRANSFER))

        val unreadyRegistration = RcsDataStreamHandlerDescriptor(
            RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            "test.update-data-not-ready",
            { RcsDataStreamHandlerProbe.notReady("quarantine_full") },
            { object : RcsDataStreamHandler {} },
        )
        val unready = AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
            fileTransferInfo = AirPlayFileTransferInfo(),
            rcsFactory = RcsDataStreamHandlerFactory { unreadyRegistration },
            updateDataConsumerReadiness = consumerProof(unreadyRegistration),
        )
        assertFalse(AirPlayFeatureNegotiation.snapshot(config(unready))
            .isReady(AirPlayFeature.FILE_TRANSFER))
    }

    private fun readyConfig(): AirPlayConfig {
        val registration = updateRegistration()
        return config(
            AirPlayUltraConfiguration.fromRuntimeDescriptors(
                configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
                fileTransferInfo = AirPlayFileTransferInfo(),
                rcsFactory = RcsDataStreamHandlerFactory { type ->
                    registration.takeIf { type == RcsClientTypes.CAR_PLAY_UPDATE_DATA }
                },
                updateDataConsumerReadiness = consumerProof(registration),
            ),
        )
    }

    private fun updateRegistration() = RcsDataStreamHandlerDescriptor.ready(
        RcsClientTypes.CAR_PLAY_UPDATE_DATA,
        "test.update-data",
    ) { object : RcsDataStreamHandler {} }

    private fun consumerProof(registration: RcsDataStreamHandlerDescriptor) =
        AirPlayUpdateDataConsumerReadinessDescriptor.capture(registration, AirPlayReadiness.READY)

    private fun config(ultra: AirPlayUltraConfiguration?): AirPlayConfig = AirPlayConfig(
        deviceName = "file-transfer-test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(1280, 720),
        ultra = ultra,
    )

    private fun transport() = AirPlayTransportReadiness(
        secureControlChannel = true,
        eventChannel = true,
        alternateScreenSink = false,
        readyRcsClientTypes = setOf(RcsClientTypes.CAR_PLAY_UPDATE_DATA),
        wirelessSession = true,
    )
}
