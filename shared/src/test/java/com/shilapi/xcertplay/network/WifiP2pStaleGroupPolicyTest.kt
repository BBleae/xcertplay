package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiP2pStaleGroupPolicyTest {
    @Test
    fun createsOnlyAfterCompletedEmptyInspection() {
        assertEquals(
            WifiP2pStaleGroupDecision.PROCEED_WITH_CREATE,
            decideWifiP2pStaleGroupRecovery(
                WifiP2pGroupInspection.completed(group = null),
                startActive = true,
            ),
        )
    }

    @Test
    fun preservesEveryPreexistingGroupEvenWhenCredentialsMatch() {
        for (group in listOf(
            observed(EXPECTED_NETWORK_NAME, EXPECTED_PASSPHRASE),
            observed(EXPECTED_NETWORK_NAME, null),
            observed(EXPECTED_NETWORK_NAME, "different-secret"),
            observed("DIRECT-other", EXPECTED_PASSPHRASE),
            observed(EXPECTED_NETWORK_NAME, EXPECTED_PASSPHRASE).copy(isGroupOwner = false),
        )) {
            assertEquals(
                WifiP2pStaleGroupDecision.PRESERVE_PREEXISTING_GROUP,
                decideWifiP2pStaleGroupRecovery(
                    WifiP2pGroupInspection.completed(group),
                    startActive = true,
                ),
            )
        }
    }

    @Test
    fun timeoutCannotAuthorizeCreateOrDestructiveRecovery() {
        assertEquals(
            WifiP2pStaleGroupDecision.INSPECTION_UNPROVEN,
            decideWifiP2pStaleGroupRecovery(
                WifiP2pGroupInspection.timedOut(),
                startActive = true,
            ),
        )
    }

    @Test
    fun cancelledStartOverridesAllInspections() {
        for (inspection in listOf(
            WifiP2pGroupInspection.completed(group = null),
            WifiP2pGroupInspection.completed(observed(EXPECTED_NETWORK_NAME, EXPECTED_PASSPHRASE)),
            WifiP2pGroupInspection.timedOut(),
        )) {
            assertEquals(
                WifiP2pStaleGroupDecision.CANCEL_START,
                decideWifiP2pStaleGroupRecovery(inspection, startActive = false),
            )
        }
    }

    private fun observed(networkName: String, passphrase: String?): WifiP2pObservedGroup =
        WifiP2pObservedGroup(
            isGroupOwner = true,
            networkName = networkName,
            passphrase = passphrase,
        )

    private companion object {
        const val EXPECTED_NETWORK_NAME = "DIRECT-xcertplay1234"
        const val EXPECTED_PASSPHRASE = "deadbeef"
    }
}
