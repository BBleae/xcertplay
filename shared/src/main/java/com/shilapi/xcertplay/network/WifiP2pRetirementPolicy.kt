package com.shilapi.xcertplay.network

/** The current group as seen by the owner of a single createGroup attempt. */
internal enum class WifiP2pRetirementGroupState {
    ABSENT,
    EXACT,
    AMBIGUOUS,
}

/**
 * Classifies a fresh group inspection without granting ownership based on an SSID alone.
 *
 * A group with a different SSID, passphrase, or GO role cannot be the accepted group and is
 * therefore absent from this attempt's perspective. A same-name group with incomplete or
 * contradictory identity is ambiguous: the caller must not delete it or claim clean teardown.
 */
internal fun classifyWifiP2pRetirementGroup(
    expected: WifiP2pCredentials,
    acceptedFingerprint: WifiP2pObservedGroup?,
    observed: WifiP2pObservedGroup?,
): WifiP2pRetirementGroupState {
    if (observed == null || !observed.isGroupOwner || observed.networkName != expected.ssid) {
        return WifiP2pRetirementGroupState.ABSENT
    }
    if (observed.passphrase.isNullOrBlank()) {
        return WifiP2pRetirementGroupState.AMBIGUOUS
    }
    if (observed.passphrase != expected.passphrase) {
        // The same SSID with a contradictory secret is not ours to delete, but neither does it
        // prove that a late createGroup callback left no process-owned group behind.
        return WifiP2pRetirementGroupState.AMBIGUOUS
    }
    if (acceptedFingerprint == null) return WifiP2pRetirementGroupState.AMBIGUOUS
    if (
        !acceptedFingerprint.isGroupOwner ||
        acceptedFingerprint.networkName != expected.ssid ||
        acceptedFingerprint.passphrase != expected.passphrase
    ) {
        return WifiP2pRetirementGroupState.AMBIGUOUS
    }

    // Temporary groups commonly have no useful networkId. Require both the owner device and
    // interface instead; if Android supplies a nonnegative networkId, it is additional evidence.
    val acceptedOwner = acceptedFingerprint.ownerDeviceAddress?.takeIf { it.isNotBlank() }
    val acceptedInterface = acceptedFingerprint.interfaceName?.takeIf { it.isNotBlank() }
    if (
        acceptedOwner == null ||
        acceptedInterface == null ||
        observed.ownerDeviceAddress != acceptedOwner ||
        observed.interfaceName != acceptedInterface
    ) {
        return WifiP2pRetirementGroupState.AMBIGUOUS
    }
    val acceptedNetworkId = acceptedFingerprint.networkId?.takeIf { it >= 0 }
    if (acceptedNetworkId != null && observed.networkId != acceptedNetworkId) {
        return WifiP2pRetirementGroupState.AMBIGUOUS
    }
    return WifiP2pRetirementGroupState.EXACT
}

internal enum class WifiP2pRetirementCreateOutcome {
    NOT_ISSUED,
    PENDING,
    SUCCEEDED,
    FAILED,
}

internal enum class WifiP2pRetirementRemovalOutcome {
    NOT_ATTEMPTED,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
}

internal enum class WifiP2pRetirementDecision {
    RETAIN,
    REMOVE_EXACT_GROUP,
    RETIRED,
}

/**
 * An operation callback is never teardown proof. A successful create with no group yet observed
 * remains pending because formation may lag the callback. Fresh absence is sufficient only after
 * a previously accepted exact group or a successful removal, and never after a probe timeout.
 */
internal fun decideWifiP2pRetirement(
    createOutcome: WifiP2pRetirementCreateOutcome,
    groupState: WifiP2pRetirementGroupState,
    acceptedExactGroupEverObserved: Boolean = false,
    lastRemoval: WifiP2pRetirementRemovalOutcome =
        WifiP2pRetirementRemovalOutcome.NOT_ATTEMPTED,
): WifiP2pRetirementDecision {
    if (createOutcome == WifiP2pRetirementCreateOutcome.NOT_ISSUED) {
        return WifiP2pRetirementDecision.RETIRED
    }
    if (createOutcome == WifiP2pRetirementCreateOutcome.PENDING) {
        return WifiP2pRetirementDecision.RETAIN
    }
    if (groupState == WifiP2pRetirementGroupState.ABSENT) {
        return if (
            createOutcome == WifiP2pRetirementCreateOutcome.FAILED ||
            acceptedExactGroupEverObserved ||
            lastRemoval == WifiP2pRetirementRemovalOutcome.SUCCEEDED
        ) {
            WifiP2pRetirementDecision.RETIRED
        } else {
            // createGroup success can be reported before requestGroupInfo sees the group.
            WifiP2pRetirementDecision.RETAIN
        }
    }
    if (
        createOutcome == WifiP2pRetirementCreateOutcome.SUCCEEDED &&
        groupState == WifiP2pRetirementGroupState.EXACT
    ) {
        // A successful remove callback does not prove absence; a failed/uncertain callback
        // likewise cannot retire a still-present group. All three require a later observation.
        return when (lastRemoval) {
            WifiP2pRetirementRemovalOutcome.NOT_ATTEMPTED,
            WifiP2pRetirementRemovalOutcome.SUCCEEDED,
            WifiP2pRetirementRemovalOutcome.FAILED,
            WifiP2pRetirementRemovalOutcome.TIMED_OUT ->
                WifiP2pRetirementDecision.REMOVE_EXACT_GROUP
        }
    }
    return WifiP2pRetirementDecision.RETAIN
}
