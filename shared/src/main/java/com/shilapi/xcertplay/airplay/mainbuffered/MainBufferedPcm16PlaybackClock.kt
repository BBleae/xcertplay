package com.shilapi.xcertplay.airplay.mainbuffered

/**
 * A calibration from the sender's authenticated network-time anchor to the *native play call*.
 * The implementation must account for the Android output pipeline, including its measured
 * presentation timestamp and latency. An NTP sample alone is not an audio-output calibration.
 * No production implementation is installed until that measurement can be verified.
 */
internal fun interface MainBufferedPcm16AudioClockMapping {
    fun calibratedPlayCall(
        outputInstance: Any,
        anchor: MainBufferedPlaybackAnchor,
    ): MainBufferedPcm16CalibratedPlayCall?
}

internal data class MainBufferedPcm16CalibratedPlayCall(
    val outputInstance: Any,
    val networkTime: MainBufferedNetworkTime,
    val playCallAtMonotonicNanos: Long,
    val validUntilMonotonicNanos: Long,
    val maximumErrorNanos: Long,
)

/** Capability for one output epoch; a guessed `srat` or `strt` timestamp cannot construct it. */
internal class MainBufferedPcm16VerifiedAnchor private constructor(
    val outputInstance: Any,
    val outputEpoch: Long,
    val sampleRateHz: Int,
    val firstMediaTimeValue: Long,
    val playCallAtMonotonicNanos: Long,
    val validUntilMonotonicNanos: Long,
    val maximumErrorNanos: Long,
) {
    companion object {
        /** Returns null unless an independently calibrated audio/output clock is available. */
        fun verify(
            anchor: MainBufferedPlaybackAnchor,
            outputInstance: Any,
            outputEpoch: Long,
            mapping: MainBufferedPcm16AudioClockMapping?,
            nowMonotonicNanos: Long,
            sampleRateHz: Int = MainBufferedPcm16Ingress.SAMPLE_RATE,
        ): MainBufferedPcm16VerifiedAnchor? {
            val networkTime = anchor.networkTime ?: return null
            val audiblePoint = anchor.firstAudiblePoint ?: return null
            if (sampleRateHz != 44_100 && sampleRateHz != MainBufferedPcm16Ingress.SAMPLE_RATE ||
                outputEpoch < 0 || anchor.point.mediaTimeScale != sampleRateHz.toLong() ||
                audiblePoint.mediaTimeScale != sampleRateHz.toLong() ||
                audiblePoint.mediaTimeValue < anchor.point.mediaTimeValue
            ) return null
            val calibration = mapping?.calibratedPlayCall(outputInstance, anchor) ?: return null
            if (calibration.outputInstance !== outputInstance ||
                calibration.networkTime != networkTime ||
                calibration.maximumErrorNanos !in 0..MAXIMUM_CLOCK_ERROR_NANOS ||
                calibration.validUntilMonotonicNanos < calibration.playCallAtMonotonicNanos ||
                calibration.validUntilMonotonicNanos < nowMonotonicNanos
            ) return null
            val leadTime = try {
                Math.subtractExact(calibration.playCallAtMonotonicNanos, nowMonotonicNanos)
            } catch (_: ArithmeticException) {
                return null
            }
            if (leadTime !in -MAXIMUM_CLOCK_ERROR_NANOS..MAXIMUM_LEAD_NANOS) return null
            return MainBufferedPcm16VerifiedAnchor(
                outputInstance = outputInstance,
                outputEpoch = outputEpoch,
                sampleRateHz = sampleRateHz,
                firstMediaTimeValue = audiblePoint.mediaTimeValue,
                playCallAtMonotonicNanos = calibration.playCallAtMonotonicNanos,
                validUntilMonotonicNanos = calibration.validUntilMonotonicNanos,
                maximumErrorNanos = calibration.maximumErrorNanos,
            )
        }

        private const val MAXIMUM_CLOCK_ERROR_NANOS = 5_000_000L
        private const val MAXIMUM_LEAD_NANOS = 5_000_000_000L
    }
}
