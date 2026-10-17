package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiP2pRetirementPolicyTest {
    @Test
    fun acceptedGroupNeedsExactCredentialsAndFingerprint() {
        assertEquals(
            WifiP2pRetirementGroupState.EXACT,
            classifyWifiP2pRetirementGroup(EXPECTED, ACCEPTED, ACCEPTED),
        )
        assertEquals(
            WifiP2pRetirementGroupState.AMBIGUOUS,
            classifyWifiP2pRetirementGroup(EXPECTED, null, ACCEPTED),
        )
        assertEquals(
            WifiP2pRetirementGroupState.AMBIGUOUS,
            classifyWifiP2pRetirementGroup(EXPECTED, ACCEPTED, ACCEPTED.copy(passphrase = null)),
        )
        assertEquals(
            WifiP2pRetirementGroupState.AMBIGUOUS,
            classifyWifiP2pRetirementGroup(EXPECTED, ACCEPTED, ACCEPTED.copy(passphrase = "")),
        )
        assertEquals(
            WifiP2pRetirementGroupState.AMBIGUOUS,
            classifyWifiP2pRetirementGroup(
                EXPECTED,
                ACCEPTED,
                ACCEPTED.copy(ownerDeviceAddress = "02:00:00:00:00:99"),
            ),
        )
        assertEquals(
            WifiP2pRetirementGroupState.AMBIGUOUS,
            classifyWifiP2pRetirementGroup(
                EXPECTED,
                ACCEPTED,
                ACCEPTED.copy(interfaceName = "p2p-other"),
            ),
        )
        assertEquals(
            WifiP2pRetirementGroupState.AMBIGUOUS,
            classifyWifiP2pRetirementGroup(
                EXPECTED,
                ACCEPTED.copy(ownerDeviceAddress = null),
                ACCEPTED,
            ),
        )
        assertEquals(
            WifiP2pRetirementGroupState.AMBIGUOUS,
            classifyWifiP2pRetirementGroup(
                EXPECTED,
                ACCEPTED.copy(networkId = 7),
                ACCEPTED.copy(networkId = 8),
            ),
        )
    }

    @Test
    fun absentOrForeignGroupIsNotRemoved() {
        for (observed in listOf(
            null,
            ACCEPTED.copy(networkName = "DIRECT-foreign"),
            ACCEPTED.copy(isGroupOwner = false),
        )) {
            assertEquals(
                WifiP2pRetirementGroupState.ABSENT,
                classifyWifiP2pRetirementGroup(EXPECTED, ACCEPTED, observed),
            )
        }
        assertEquals(
            WifiP2pRetirementGroupState.AMBIGUOUS,
            classifyWifiP2pRetirementGroup(
                EXPECTED,
                ACCEPTED,
                ACCEPTED.copy(passphrase = "foreign-secret"),
            ),
        )
    }

    @Test
    fun createPendingRetainsEvenWhenNoGroupIsYetObserved() {
        assertEquals(
            WifiP2pRetirementDecision.RETAIN,
            decideWifiP2pRetirement(
                WifiP2pRetirementCreateOutcome.PENDING,
                WifiP2pRetirementGroupState.ABSENT,
            ),
        )
    }

    @Test
    fun lateCreateFailureCanRetireOnlyAfterGroupAbsence() {
        assertEquals(
            WifiP2pRetirementDecision.RETIRED,
            decideWifiP2pRetirement(
                WifiP2pRetirementCreateOutcome.FAILED,
                WifiP2pRetirementGroupState.ABSENT,
            ),
        )
        assertEquals(
            WifiP2pRetirementDecision.RETAIN,
            decideWifiP2pRetirement(
                WifiP2pRetirementCreateOutcome.FAILED,
                WifiP2pRetirementGroupState.EXACT,
            ),
        )
    }

    @Test
    fun lateCreateSuccessRequiresExactGroupRemoval() {
        assertEquals(
            WifiP2pRetirementDecision.REMOVE_EXACT_GROUP,
            decideWifiP2pRetirement(
                WifiP2pRetirementCreateOutcome.SUCCEEDED,
                WifiP2pRetirementGroupState.EXACT,
            ),
        )
        assertEquals(
            WifiP2pRetirementDecision.RETAIN,
            decideWifiP2pRetirement(
                WifiP2pRetirementCreateOutcome.SUCCEEDED,
                WifiP2pRetirementGroupState.AMBIGUOUS,
            ),
        )
        assertEquals(
            WifiP2pRetirementDecision.RETAIN,
            decideWifiP2pRetirement(
                WifiP2pRetirementCreateOutcome.SUCCEEDED,
                WifiP2pRetirementGroupState.ABSENT,
            ),
        )
        assertEquals(
            WifiP2pRetirementDecision.RETIRED,
            decideWifiP2pRetirement(
                WifiP2pRetirementCreateOutcome.SUCCEEDED,
                WifiP2pRetirementGroupState.ABSENT,
                acceptedExactGroupEverObserved = true,
            ),
        )
    }

    @Test
    fun removalCallbacksCannotRetireStillObservedGroup() {
        for (outcome in listOf(
            WifiP2pRetirementRemovalOutcome.SUCCEEDED,
            WifiP2pRetirementRemovalOutcome.FAILED,
            WifiP2pRetirementRemovalOutcome.TIMED_OUT,
        )) {
            assertEquals(
                WifiP2pRetirementDecision.REMOVE_EXACT_GROUP,
                decideWifiP2pRetirement(
                    WifiP2pRetirementCreateOutcome.SUCCEEDED,
                    WifiP2pRetirementGroupState.EXACT,
                    lastRemoval = outcome,
                ),
            )
        }
    }

    @Test
    fun removalSuccessPlusFreshAbsenceCanRetire() {
        assertEquals(
            WifiP2pRetirementDecision.RETIRED,
            decideWifiP2pRetirement(
                WifiP2pRetirementCreateOutcome.SUCCEEDED,
                WifiP2pRetirementGroupState.ABSENT,
                lastRemoval = WifiP2pRetirementRemovalOutcome.SUCCEEDED,
            ),
        )
        for (outcome in listOf(
            WifiP2pRetirementRemovalOutcome.FAILED,
            WifiP2pRetirementRemovalOutcome.TIMED_OUT,
        )) {
            assertEquals(
                WifiP2pRetirementDecision.RETAIN,
                decideWifiP2pRetirement(
                    WifiP2pRetirementCreateOutcome.SUCCEEDED,
                    WifiP2pRetirementGroupState.ABSENT,
                    lastRemoval = outcome,
                ),
            )
        }
    }

    private companion object {
        val EXPECTED = WifiP2pCredentials("DIRECT-xcertplay1234", "deadbeef")
        val ACCEPTED = WifiP2pObservedGroup(
            isGroupOwner = true,
            networkName = EXPECTED.ssid,
            passphrase = EXPECTED.passphrase,
            ownerDeviceAddress = "02:00:00:00:00:01",
            interfaceName = "p2p0",
            networkId = -1,
        )
    }
}
