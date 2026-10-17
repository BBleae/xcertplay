package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.airplay.ScreenFrameTiming
import com.shilapi.xcertplay.airplay.parseScreenFrameTiming
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSyncType2FrameCoordinationGateTest {
    @Test
    fun exactAppliedTypeTwoAndAuthenticatedType111PairOnceWithTwoCalibratedProjections() {
        Fixture().use { fixture ->
            val scope = Scope(fixture.reducer)
            val observation = fixture.transition()
            assertNull(scope.observe(observation))

            val result = scope.pair(observation)
            assertEquals(
                UiSyncType2FrameCoordinationResult.Paired(
                    transitionRevision = 1L,
                    authenticatedFrameCounter = 7L,
                    frameTargetMonotonicNanos = FRAME_TARGET,
                ),
                result,
            )
            assertRejected(
                UiSyncType2FrameCoordinationFailure.STALE_OR_OUT_OF_ORDER,
                scope.pair(observation),
            )

            val next = fixture.transition()
            assertNull(scope.observe(next))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.UNAUTHENTICATED_OR_REPLAYED_FRAME,
                scope.pair(next, frame = frame(counter = 7L)),
            )
            val third = fixture.transition()
            assertNull(scope.observe(third))
            assertTrue(scope.pair(
                third,
                frame = frame(counter = 8L),
                geometry = scope.geometry(third).apply { frameCounter = 8L },
            ) is
                UiSyncType2FrameCoordinationResult.Paired)
        }
    }

    @Test
    fun wrongSessionStreamAndDisplayAreNeverPaired() {
        Fixture().use { fixture ->
            val scope = Scope(fixture.reducer)
            val observation = fixture.transition()
            assertEquals(
                UiSyncType2FrameCoordinationFailure.WRONG_SCOPE,
                scope.gate.observeTransition(
                    Any(), observation.applied, observation.snapshot, OBSERVE_AT,
                ),
            )
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.WRONG_SCOPE,
                scope.gate.pairFrame(
                    Any(), observation.applied.state, scope.stream, frame(),
                    scope.clock(), scope.geometry(observation), PAIR_AT,
                ),
            )
            assertNull(scope.observe(fixture.transition()))
            val differentSessionStream = stream()
            assertRejected(
                UiSyncType2FrameCoordinationFailure.WRONG_SCOPE,
                scope.pair(fixture.latest, stream = differentSessionStream),
            )
        }
        Fixture(displayId = "another-cluster").use { fixture ->
            val scope = Scope(CarPlayUiSyncSemanticReducer(DISPLAY))
            assertEquals(
                UiSyncType2FrameCoordinationFailure.WRONG_SCOPE,
                scope.observe(fixture.transition()),
            )
        }
    }

    @Test
    fun staleTypeTwoCannotBePromotedAfterNewerSemanticEventOrClosedEpoch() {
        Fixture().use { fixture ->
            val scope = Scope(fixture.reducer)
            val first = fixture.transition()
            assertNull(scope.observe(first))
            fixture.otherEvent()
            val newer = fixture.reduce()
            assertEquals(
                UiSyncType2FrameCoordinationFailure.UNPROVEN_TRANSITION,
                scope.observe(newer),
            )
            assertRejected(
                UiSyncType2FrameCoordinationFailure.STALE_OR_OUT_OF_ORDER,
                scope.pair(first),
            )
            assertEquals(
                UiSyncType2FrameCoordinationFailure.STALE_OR_OUT_OF_ORDER,
                scope.observe(first),
            )
            val latest = fixture.transition()
            assertNull(scope.observe(latest))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.STALE_OR_OUT_OF_ORDER,
                scope.pair(latest, state = first.applied.state),
            )
            assertTrue(scope.gate.retire(scope.session))
            assertEquals(
                UiSyncType2FrameCoordinationFailure.RETIRED,
                scope.observe(fixture.transition()),
            )
            assertRejected(
                UiSyncType2FrameCoordinationFailure.RETIRED,
                scope.pair(latest),
            )
            assertFalse(scope.gate.retire(Any()))
        }
    }

    @Test
    fun reducerAdvancingWithoutNotifyingGateStillInvalidatesOldTypeTwo() {
        Fixture().use { fixture ->
            val scope = Scope(fixture.reducer)
            val first = fixture.transition()
            assertNull(scope.observe(first))
            fixture.otherEvent()
            fixture.reduce()
            assertRejected(
                UiSyncType2FrameCoordinationFailure.STALE_OR_OUT_OF_ORDER,
                scope.pair(first),
            )
        }
    }

    @Test
    fun uncalibratedOrCrossScopeClockCannotCompareRawNtpValues() {
        Fixture().use { fixture ->
            val scope = Scope(fixture.reducer)
            var observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK,
                scope.pair(observation, clock = null),
            )
            observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK,
                scope.pair(observation, clock = scope.clock().apply {
                    sessionEpoch = Any()
                }),
            )
            observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK,
                scope.pair(observation, clock = scope.clock().apply {
                    type2Target = null
                }),
            )
            observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK,
                scope.pair(observation, clock = scope.clock().apply {
                    calibrationEpoch = Any()
                    onFrameProjection = { calibrationEpoch = Any() }
                }),
            )
        }
    }

    @Test
    fun staleCalibrationExcessDriftAndSeparatedTargetsFailClosed() {
        Fixture().use { fixture ->
            val scope = Scope(fixture.reducer)
            var observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK,
                scope.pair(observation, clock = scope.clock().apply {
                    sampledAtMonotonicNanos = 1L
                }),
            )
            observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK,
                scope.pair(observation, clock = scope.clock().apply {
                    maximumDriftNanos = 20_000_000L
                }),
            )
            observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.OUTSIDE_BOUNDED_WINDOW,
                scope.pair(observation, clock = scope.clock().apply {
                    frameTarget = TYPE2_TARGET + 100_000_000L
                }),
            )
            observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.OUTSIDE_BOUNDED_WINDOW,
                scope.pair(observation, clock = scope.clock().apply {
                    frameTarget = Long.MAX_VALUE
                }),
            )
        }
    }

    @Test
    fun unknownRegionTransformOpacityOrFrameSpecificGeometryRejects() {
        val variants = listOf(
            mapOf("identifier" to "primary", "opacity" to 0.9,
                "transform2D" to IDENTITY_TRANSFORM),
            mapOf("identifier" to "primary", "opacity" to 1.0,
                "transform2D" to listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
            mapOf("identifier" to "primary", "opacity" to 1.0,
                "transform2D" to IDENTITY_TRANSFORM, "unknownClip" to true),
            mapOf("identifier" to "primary", "opacity" to 1.0),
        )
        variants.forEach { region ->
            Fixture().use { fixture ->
                val scope = Scope(fixture.reducer)
                val observation = fixture.transition(region)
                assertNull(scope.observe(observation))
                assertRejected(
                    UiSyncType2FrameCoordinationFailure.UNPROVEN_OPAQUE_TRANSFORM,
                    scope.pair(observation),
                )
            }
        }
        Fixture().use { fixture ->
            val scope = Scope(fixture.reducer)
            var observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.UNPROVEN_OPAQUE_TRANSFORM,
                scope.pair(observation, geometry = null),
            )
            observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.UNPROVEN_OPAQUE_TRANSFORM,
                scope.pair(observation, geometry = scope.geometry(observation).apply {
                    outputCoordinateSpace = Any()
                }),
            )
            observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.UNPROVEN_OPAQUE_TRANSFORM,
                scope.pair(observation, geometry = scope.geometry(observation).apply {
                    frameCounter = 8L
                }),
            )
        }
    }

    @Test
    fun unauthenticatedOrMutatedFrameTimingAndExpiredObservationsFailClosed() {
        Fixture().use { fixture ->
            val scope = Scope(fixture.reducer)
            var observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.UNAUTHENTICATED_OR_REPLAYED_FRAME,
                scope.pair(observation, frame = ScreenFrameTiming(FRAME_NTP, FRAME_ARRIVAL)),
            )
            observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.UNAUTHENTICATED_OR_REPLAYED_FRAME,
                scope.pair(observation, frame = frame().copy(
                    presentationTimeNtp64Bits = TYPE2_NTP,
                )),
            )
            observation = fixture.transition()
            assertNull(scope.observe(observation))
            assertRejected(
                UiSyncType2FrameCoordinationFailure.OUTSIDE_BOUNDED_WINDOW,
                scope.pair(observation, frame = frame(arrival = 1L)),
            )
            observation = fixture.transition()
            assertEquals(
                UiSyncType2FrameCoordinationFailure.OUTSIDE_BOUNDED_WINDOW,
                scope.gate.observeTransition(
                    scope.session, observation.applied, observation.snapshot, Long.MAX_VALUE,
                ),
            )
        }
    }

    private class Scope(reducer: CarPlayUiSyncSemanticReducer) {
        val session = Any()
        val stream = stream()
        private val coordinateSpace = Any()
        val gate = UiSyncType2FrameCoordinationGate(
            session, DISPLAY, stream, coordinateSpace, reducer,
        )

        fun observe(observation: Observation): UiSyncType2FrameCoordinationFailure? =
            gate.observeTransition(session, observation.applied, observation.snapshot, OBSERVE_AT)

        fun pair(
            observation: Observation,
            frame: ScreenFrameTiming = frame(),
            state: CarPlayUiSyncSemanticState = observation.applied.state,
            stream: AirPlayVideoStreamHandle = this.stream,
            clock: TestClock? = clock(),
            geometry: TestGeometry? = geometry(observation),
        ): UiSyncType2FrameCoordinationResult = gate.pairFrame(
            session, state, stream, frame, clock, geometry, PAIR_AT,
        )

        fun clock(): TestClock = TestClock(session, DISPLAY, stream)

        fun geometry(observation: Observation): TestGeometry = TestGeometry(
            session, DISPLAY, stream,
            observation.applied.state.lastTransitionData!!,
            observation.applied.state.revision,
            coordinateSpace,
        )
    }

    private data class Observation(
        val applied: CarPlayUiSyncSemanticReduceResult.Applied,
        val snapshot: UltraPresentationSnapshot,
    )

    private class Fixture(
        private val displayId: String = DISPLAY.value,
    ) : AutoCloseable {
        private val hub = UltraPresentationHub(Executor(Runnable::run))
        private val sink = hub.sink(displayId)
        val reducer = CarPlayUiSyncSemanticReducer(displayId)
        lateinit var latest: Observation
            private set

        fun transition(region: Map<String, Any?> = REGION): Observation {
            sink.onPresentationEvent(CarPlayUiSyncPresentationEvent.KnownCommand(
                CarPlayUiSyncCommandType.TRANSITION_DATA,
                mapOf(
                    "regions" to listOf(region),
                    "presentation_host_time" to 1L,
                    "presentation_ntp_time" to TYPE2_NTP,
                    "current_ntp_time" to TYPE2_NTP,
                ),
                acceptedAtMonotonicNanos = TRANSITION_ACCEPTED,
            ))
            return reduce()
        }

        fun otherEvent() {
            sink.onPresentationEvent(CarPlayUiSyncPresentationEvent.KnownCommand(
                CarPlayUiSyncCommandType.LAYOUT_CHANGE,
                mapOf(
                    "layoutID" to "new-layout",
                    "activeComponents" to emptyMap<String, String>(),
                    "paletteID" to "palette",
                ),
                acceptedAtMonotonicNanos = TRANSITION_ACCEPTED,
            ))
        }

        fun reduce(): Observation {
            val snapshot = hub.snapshot(displayId)
            val applied = reducer.reduce(snapshot) as CarPlayUiSyncSemanticReduceResult.Applied
            return Observation(applied, snapshot).also { latest = it }
        }

        override fun close() = hub.close()
    }

    private class TestClock(
        override var sessionEpoch: Any,
        override val displayId: UltraUiSyncDisplayId,
        override val videoStream: AirPlayVideoStreamHandle,
    ) : UiSyncType2SharedClockCalibration {
        override var calibrationEpoch: Any = Any()
        override var sampledAtMonotonicNanos = PAIR_AT - 1_000_000L
        override var maximumErrorNanos = 1_000_000L
        override var maximumDriftNanos = 1_000_000L
        override var isLive = true
        var type2Target: Long? = TYPE2_TARGET
        var frameTarget: Long? = FRAME_TARGET
        var onFrameProjection: (() -> Unit)? = null

        override fun projectType2NtpToMonotonicNanos(rawNtp64Bits: Long): Long? =
            type2Target.takeIf { rawNtp64Bits == TYPE2_NTP }

        override fun projectAuthenticatedFrameNtpToMonotonicNanos(rawNtp64Bits: Long): Long? {
            onFrameProjection?.invoke()
            return frameTarget.takeIf { rawNtp64Bits == FRAME_NTP }
        }
    }

    private class TestGeometry(
        override val sessionEpoch: Any,
        override val displayId: UltraUiSyncDisplayId,
        override val videoStream: AirPlayVideoStreamHandle,
        override val transition: CarPlayUiSyncSemanticCommand.TransitionData,
        override val transitionRevision: Long,
        override var outputCoordinateSpace: Any,
    ) : UiSyncType2OpaqueRegionTransformProof {
        override var frameCounter = 7L
        override val regionIdentifier = "primary"
        override val transform2D = IDENTITY_TRANSFORM
        override var isFullyOpaqueAndComplete = true
        override var isLive = true
    }

    private companion object {
        val DISPLAY = UltraUiSyncDisplayId("cluster-primary")
        val IDENTITY_TRANSFORM = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        val REGION = mapOf<String, Any?>(
            "identifier" to "primary",
            "opacity" to 1.0,
            "transform2D" to IDENTITY_TRANSFORM,
        )
        const val TYPE2_NTP = 0x0102030405060708L
        const val FRAME_NTP = 0x1112131415161718L
        const val TRANSITION_ACCEPTED = 1_000_000_000L
        const val OBSERVE_AT = 1_005_000_000L
        const val FRAME_ARRIVAL = 1_013_000_000L
        const val PAIR_AT = 1_020_000_000L
        const val TYPE2_TARGET = 1_010_000_000L
        const val FRAME_TARGET = 1_012_000_000L

        fun stream() = AirPlayVideoStreamHandle(
            AirPlayStreamIdentity(111, "cluster-stream", null),
            AirPlayVideoSessionEpoch(),
            AirPlayInfoPlist.ALT_UUID,
        )

        fun frame(counter: Long = 7L, arrival: Long = FRAME_ARRIVAL): ScreenFrameTiming {
            val header = ByteArray(128)
            repeat(Long.SIZE_BYTES) { index ->
                header[8 + index] = (FRAME_NTP ushr (index * 8)).toByte()
            }
            return parseScreenFrameTiming(header, arrival, authenticatedFrameCounter = counter)
        }

        fun assertRejected(
            expected: UiSyncType2FrameCoordinationFailure,
            actual: UiSyncType2FrameCoordinationResult,
        ) = assertEquals(UiSyncType2FrameCoordinationResult.Rejected(expected), actual)
    }
}
