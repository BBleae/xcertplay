package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataQuarantineRootOwner

/**
 * Reserves theme-transfer quarantine capacity for one admitted TCP control connection.
 *
 * This is independent of an installed Ultra profile: a phone may need to send the theme before
 * one exists on the accessory. An unavailable reservation leaves that connection's ordinary
 * configuration untouched. Records received through this path remain untrusted and inactive.
 */
class CarPlayUpdateDataSessionOverlayFactory(
    private val quarantineOwner: CarPlayUpdateDataQuarantineRootOwner,
) : AirPlaySessionCapabilityOverlayFactory {
    override fun open(
        snapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot,
    ): AirPlaySessionCapabilityOverlay {
        val baseConfig = snapshot.config
        val baseUltra = baseConfig.ultra
        // An authorized runtime may eventually own UpdateData itself. Never reserve a second
        // quarantine slot or replace that exact handler on its connection.
        if (baseUltra?.isConfigured(AirPlayFeature.FILE_TRANSFER) == true ||
            baseUltra?.readiness?.handler(RcsClientTypes.CAR_PLAY_UPDATE_DATA) != null
        ) return AirPlaySessionCapabilityOverlay(baseConfig)
        val reservation = quarantineOwner.tryReserveSession()
            ?: return AirPlaySessionCapabilityOverlay(baseConfig)
        try {
            val descriptor = requireNotNull(
                reservation.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA),
            ) { "Reserved UpdateData session has no exact descriptor" }
            check(descriptor.probe().isReady) {
                "Reserved UpdateData descriptor became unavailable before configuration"
            }
            val proof = reservation.consumerReadinessDescriptor(descriptor)
            val updateHandler = AirPlayRcsHandlerReadinessDescriptor.capture(descriptor)
            val updateConsumer = requireNotNull(proof.boundTo(descriptor, updateHandler)) {
                "UpdateData consumer proof is not bound to its exact reservation"
            }
            val nextUltra = if (baseUltra == null) {
                AirPlayUltraConfiguration(
                    configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
                    fileTransferInfo = AirPlayFileTransferInfo(),
                    readiness = AirPlayUltraReadiness(
                        rcsHandlers = listOf(updateHandler),
                        updateDataConsumer = updateConsumer,
                    ),
                )
            } else {
                baseUltra.copy(
                    configuredFeatures = baseUltra.configuredFeatures + AirPlayFeature.FILE_TRANSFER,
                    fileTransferInfo = AirPlayFileTransferInfo(),
                    readiness = baseUltra.readiness.copy(
                        rcsHandlers = baseUltra.readiness.rcsHandlers + updateHandler,
                        updateDataConsumer = updateConsumer,
                    ),
                )
            }
            val config = baseConfig.copy(ultra = nextUltra)
            check(AirPlayFeatureNegotiation.snapshot(config).isReady(AirPlayFeature.FILE_TRANSFER)) {
                "Reserved UpdateData session did not produce a ready file-transfer capability"
            }
            return AirPlaySessionCapabilityOverlay(
                config = config,
                extraRcsFactory = reservation,
                owner = reservation,
            )
        } catch (failure: Throwable) {
            runCatching { reservation.close() }.exceptionOrNull()?.let { cleanup ->
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }
}
