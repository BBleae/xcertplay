package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugUltraCompositionTest {
    @Test fun benchIsExplicitlyDoubleGatedAndDefaultCompositionIsOff() {
        assertFalse(debugBenchSelected(false, false))
        assertFalse(debugBenchSelected(false, true))
        assertFalse(debugBenchSelected(true, false))
        assertTrue(debugBenchSelected(true, true))
        assertSame(DisabledUltraProfileDeploymentProvider,
            UltraProfileCompositionRoot.deploymentProvider())
        assertSame(UnavailableTrustedUltraVehicleIdentityAuthority,
            UltraProfileCompositionRoot.vehicleIdentityAuthority())
    }
}
