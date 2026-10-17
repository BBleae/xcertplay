package com.shilapi.xcertplay

import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize

/** Synthetic test provenance only; production never mints a token without a verified lease. */
internal fun externalCalibrationForTest(
    destination: DisplayDestination = DisplayDestination.CLUSTER,
    pixels: PixelSize = PixelSize(1920, 480),
    widthMm: Int = 221,
    heightMm: Int = 83,
    displayId: Int = 4,
    epoch: Long = 101L,
): ExternalPhysicalDisplayCalibration = ExternalPhysicalDisplayCalibration(
    stableConnectorIdentity = "bench-connector-7",
    hardwareIdentity = "pinned-panel-serial-7",
    displayId = displayId,
    displayEpoch = epoch,
    destination = destination,
    pixels = pixels,
    widthPhysicalMm = widthMm,
    heightPhysicalMm = heightMm,
)

