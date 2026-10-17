package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlaySessionCapabilityOverlay
import com.shilapi.xcertplay.airplay.AirPlaySessionCapabilityOverlayFactory
import com.shilapi.xcertplay.airplay.AirPlaySessionManagementInfo
import com.shilapi.xcertplay.airplay.AirPlaySessionRuntimeBinding
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CarPlayControllerSessionOverlayWiringTest {
    @Test
    fun wiredAndWirelessAdmissionsForwardTheSameBorrowedFactoryAndRuntimeReferences() {
        val authorization = authorization()
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            AirPlaySessionCapabilityOverlay(snapshot.config)
        }
        try {
            // Both controller attach paths consume one immutable attachment admission, including
            // reconnects; neither path receives a copy or takes ownership of the factory.
            val admission = ControllerAirPlayAttachmentAdmission(authorization, factory)
            val wired = admission.forwardToAttachment { binding, generation, overlay ->
                Triple(binding, generation, overlay)
            }
            val wireless = admission.forwardToAttachment { binding, generation, overlay ->
                Triple(binding, generation, overlay)
            }
            for (arguments in listOf(wired, wireless)) {
                assertSame(authorization.binding, arguments.first)
                assertSame(authorization.generation, arguments.second)
                assertSame(factory, arguments.third)
            }
        } finally {
            authorization.close()
        }
    }

    @Test
    fun suppliedFactoryCannotBeAttachedWithoutControllerAuthorization() {
        val factory = AirPlaySessionCapabilityOverlayFactory { snapshot ->
            AirPlaySessionCapabilityOverlay(snapshot.config)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControllerAirPlayAttachmentAdmission(null, factory)
        }
    }

    @Test
    fun ordinaryControllerDefaultsToNoAdmissionOrOverlay() {
        val admission = ControllerAirPlayAttachmentAdmission(null, null)
        admission.forwardToAttachment { binding, generation, overlay ->
            assertNull(binding)
            assertNull(generation)
            assertNull(overlay)
        }
    }

    private fun authorization(): ControllerUltraAuthorizationRuntime {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        return ControllerUltraAuthorizationRuntime(
            lifecycle = object : CarPlayUltraAuthorizationLifecycle {
                override fun activate(
                    receipt: MfiAuthenticationReceipt,
                    sessionManagementInfo: AirPlaySessionManagementInfo?,
                ) = CarPlayUltraAuthorizationResult.Unavailable

                override fun hasRenewableSession(predecessor: MfiAuthenticationReceipt) = false

                override fun isCurrentActivation(
                    activation: CarPlayUltraAuthorizationResult.Activated,
                    receipt: MfiAuthenticationReceipt,
                ) = false

                override fun prepareRenewal(
                    predecessor: MfiAuthenticationReceipt,
                    successor: MfiAuthenticationReceipt,
                ): CarPlayUltraPreparedRenewal = error("No active authorization")

                override fun close() = Unit
            },
            binding = binding,
            generation = generation,
            baselineConfig = AirPlayConfig(
                deviceName = "controller-overlay-test",
                deviceId = "00:11:22:33:44:55",
                btMac = "00:11:22:33:44:66",
                sourceVersion = "1.0",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            fallbackRuntime = null,
        )
    }
}
