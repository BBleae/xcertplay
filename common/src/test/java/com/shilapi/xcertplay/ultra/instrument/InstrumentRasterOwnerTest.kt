package com.shilapi.xcertplay.ultra.instrument

import com.shilapi.xcertplay.AuthorizedInstrumentSignalSession
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus
import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import com.shilapi.xcertplay.airplay.rcs.caf.CafFormatValue
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.InstrumentEglRasterPort
import com.shilapi.xcertplay.ultra.InstrumentRasterEglOperations
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraPixelRect
import com.shilapi.xcertplay.ultra.egl.UltraEglInstrumentUploadReceipt
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentCafCharacteristicPin
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentNumericMeaning
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleShape
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleSpec
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleState
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalReading
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalRole
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalSemantics
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalSource
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalUnavailableReason
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentStateCode
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentThemeSignalAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneComponentAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneLayoutAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneSlotAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneZoneAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.ZoneRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentRasterOwnerTest {
    private val readAt = 1_000_000_000L
    private val output = PixelSize(320, 160)

    @Test fun committedContentTracksExactClearSequenceAndInvalidatesOnBusyNumeric() {
        val spec = speedSpec()
        val port = FakePort(output)
        val owner = InstrumentRasterOwner(plan(spec), port)
        assertEquals(InstrumentCommittedContent.Unknown, owner.committedContentSnapshot())
        assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED, owner.presentUnavailable())
        val initial = owner.committedContentSnapshot() as InstrumentCommittedContent.SafetyClear
        assertEquals(1L, initial.frameSequence)

        port.nextResult = InstrumentFrameCommitResult.BUSY_UNCONFIRMED
        assertEquals(InstrumentRasterOutcome.COMMIT_BUSY,
            owner.presentForTest(FakeView(spec, speedAvailable(readAt)), readAt))
        assertEquals(InstrumentCommittedContent.Unknown, owner.committedContentSnapshot())

        assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
            owner.presentForTest(FakeView(spec, InstrumentSignalBundleState.Unavailable(
                InstrumentSignalUnavailableReason.STALE)), readAt))
        val cleared = owner.committedContentSnapshot() as InstrumentCommittedContent.SafetyClear
        assertTrue(cleared.frameSequence > initial.frameSequence)
        owner.close()
    }

    @Test fun prolongedBusyDefersEveryLargeSnapshotUntilExactGenerationBecomesCurrent() {
        val spec = speedSpec()
        val operations = BusyOperations(output).apply {
            status = UiSyncPhysicalGenerationStatus.BUSY
        }
        val port = InstrumentEglRasterPort(operations)
        var captures = 0
        val owner = InstrumentRasterOwner(plan(spec), port) { raster ->
            captures++
            OwnedOpaqueArgbFrame.capture(raster)
        }
        val view = FakeView(spec, speedAvailable(readAt))
        repeat(40) { tick ->
            val now = readAt + tick * 50_000_000L
            view.state = speedAvailable(now, kmh = 42.0 + tick)
            assertEquals(InstrumentRasterOutcome.COMMIT_BUSY, owner.presentForTest(view, now))
        }
        assertEquals(0, captures)
        assertEquals(0, operations.uploads)
        assertEquals(0, operations.retireCalls)

        operations.status = UiSyncPhysicalGenerationStatus.CURRENT
        val currentNow = readAt + 40 * 50_000_000L
        view.state = speedAvailable(currentNow, kmh = 82.0)
        assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE,
            owner.presentForTest(view, currentNow))
        assertEquals(1, captures)
        assertEquals(1, operations.uploads)
        owner.close()
        port.requestPhysicalRetirement()
        port.close()
    }

    @Test fun staleAvailableWhileBusyRetiresOldNumericOutputWithoutSnapshotAllocation() {
        val spec = speedSpec()
        val operations = BusyOperations(output)
        val port = InstrumentEglRasterPort(operations)
        var captures = 0
        val owner = InstrumentRasterOwner(plan(spec), port) { raster ->
            captures++
            OwnedOpaqueArgbFrame.capture(raster)
        }
        val view = FakeView(spec, speedAvailable(readAt, kmh = 88.0))
        port.noteAvailable()
        assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE,
            owner.presentForTest(view, readAt))
        assertEquals(1, captures)
        assertEquals(1, operations.uploads)

        operations.status = UiSyncPhysicalGenerationStatus.BUSY
        assertEquals(InstrumentRasterOutcome.REVOKED_CLEARED,
            owner.presentForTest(view, readAt + 1_100_000_000L))
        assertEquals(1, captures)
        assertEquals(1, operations.uploads)
        assertEquals(1, operations.retireCalls)
        owner.close()
        port.close()
    }

    @Test fun generationTurningBusyAfterSnapshotStillRejectsUploadAndRetiresNeededClear() {
        val spec = speedSpec()
        val operations = BusyOperations(output)
        val port = InstrumentEglRasterPort(operations)
        val owner = InstrumentRasterOwner(plan(spec), port) { raster ->
            operations.status = UiSyncPhysicalGenerationStatus.BUSY
            OwnedOpaqueArgbFrame.capture(raster)
        }
        val view = FakeView(spec, speedAvailable(readAt))
        assertEquals(InstrumentRasterOutcome.COMMIT_BUSY,
            owner.presentForTest(view, readAt))
        assertEquals(0, operations.uploads)
        view.state = InstrumentSignalBundleState.Unavailable(
            InstrumentSignalUnavailableReason.STALE)
        operations.status = UiSyncPhysicalGenerationStatus.CURRENT
        assertEquals(InstrumentRasterOutcome.REVOKED_CLEARED,
            owner.presentForTest(view, readAt + 50_000_000L))
        assertEquals(0, operations.uploads)
        assertEquals(1, operations.retireCalls)
        owner.close()
        port.close()
    }

    @Test fun sourceExpiringDuringOwnedCopyWithBusyGenerationRetiresWithoutStaleUpload() {
        val spec = speedSpec()
        val operations = BusyOperations(output)
        val port = InstrumentEglRasterPort(operations)
        var captures = 0
        val owner = InstrumentRasterOwner(plan(spec), port) { raster ->
            captures++
            operations.status = UiSyncPhysicalGenerationStatus.BUSY
            OwnedOpaqueArgbFrame.capture(raster)
        }
        val view = FakeView(spec, speedAvailable(readAt, kmh = 88.0))
        var clockCalls = 0
        assertEquals(InstrumentRasterOutcome.REVOKED_CLEARED,
            owner.presentForTestWithClock(view) {
                clockCalls++
                if (clockCalls < 3) readAt else readAt + 1_100_000_000L
            })
        assertEquals(1, captures)
        assertEquals(0, operations.uploads)
        assertEquals(1, operations.retireCalls)
        owner.close()
        port.close()
    }

    @Test fun authorityRevokedDuringOwnedCopyCannotUploadCapturedNumericFrame() {
        val spec = speedSpec()
        val operations = BusyOperations(output)
        val port = InstrumentEglRasterPort(operations)
        val view = FakeView(spec, speedAvailable(readAt, kmh = 88.0))
        val owner = InstrumentRasterOwner(plan(spec), port) { raster ->
            view.live = false
            operations.status = UiSyncPhysicalGenerationStatus.BUSY
            OwnedOpaqueArgbFrame.capture(raster)
        }
        assertEquals(InstrumentRasterOutcome.REVOKED_CLEARED,
            owner.presentForTest(view, readAt))
        assertEquals(0, operations.uploads)
        assertEquals(1, operations.retireCalls)
        owner.close()
        port.close()
    }

    @Test fun ownerTransfersOnePrivateOpaqueSnapshotInsteadOfCallingRawRasterApi() {
        var submitted: IntArray? = null
        val port = object : InstrumentDedicatedRasterPort {
            override val destination = DisplayDestination.CLUSTER
            override val viewportPixels = output
            override val isCurrent = true
            override fun replaceCompleteOpaqueFrame(argbPixels: IntArray,
                safetyMarker: String): InstrumentFrameCommitResult =
                error("owner must use the one-shot owned frame path")

            override fun replaceOwnedCompleteOpaqueFrame(frame: OwnedOpaqueArgbFrame,
                safetyMarker: String): InstrumentFrameCommitResult {
                submitted = requireNotNull(frame.take())
                assertEquals("BENCH / NOT VEHICLE SAFE", safetyMarker)
                return InstrumentFrameCommitResult.COMMITTED_CURRENT
            }
        }
        val owner = InstrumentRasterOwner(plan(speedSpec()), port)
        assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED, owner.presentUnavailable())
        assertEquals(output.widthPixels * output.heightPixels, submitted?.size)
        assertTrue(requireNotNull(submitted).all { it ushr 24 == 0xff })
    }

    @Test fun initialUnavailableFrameNeedsNoSourceAndCannotRetainAnEarlierValue() {
        val spec = speedSpec()
        val port = FakePort(output)
        val owner = InstrumentRasterOwner(plan(spec), port)
        assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
            owner.presentUnavailable())
        assertEquals(1, port.submissions)
        assertEquals("BENCH / NOT VEHICLE SAFE", port.marker)
        assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })

        val view = FakeView(spec, speedAvailable(readAt, kmh = 42.0))
        assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE,
            owner.presentForTest(view, readAt))
        assertTrue(port.last!!.any { it == CARD_BACKGROUND })
        assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
            owner.presentUnavailable())
        assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        owner.close()
        assertEquals(InstrumentRasterOutcome.PORT_REJECTED, owner.presentUnavailable())
    }

    @Test fun authorizedEntryHasNoCallerClockAndStaleOrRevokedCannotReuseValue() {
        val productEntry = InstrumentRasterOwner::class.java.declaredMethods.single {
            it.name == "present" &&
                it.parameterTypes.firstOrNull() == AuthorizedInstrumentSignalSession::class.java
        }
        assertEquals(listOf(AuthorizedInstrumentSignalSession::class.java),
            productEntry.parameterTypes.toList())

        val spec = speedSpec()
        val port = FakePort(output)
        val view = FakeView(spec, speedAvailable(readAt, kmh = 88.0))
        InstrumentRasterOwner(plan(spec), port).use { owner ->
            assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE,
                owner.presentForTest(view, readAt))
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentForTest(view, readAt + 1_000_000_001L))
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
            view.live = false
            assertEquals(InstrumentRasterOutcome.REVOKED_CLEARED,
                owner.presentForTest(view, readAt)) // Even a forged old test time cannot revive it.
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        }
    }

    @Test fun sampleExpiringDuringRasterOrSubmitIsClearedBeforeReturn() {
        val spec = speedSpec()
        val view = FakeView(spec, speedAvailable(readAt, kmh = 88.0))
        val port = FakePort(output)
        InstrumentRasterOwner(plan(spec), port).use { owner ->
            var calls = 0
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentForTestWithClock(view) {
                    calls++
                    if (calls == 1) readAt else readAt + 1_000_000_001L
                })
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })

            calls = 0
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentForTestWithClock(view) {
                    calls++
                    if (calls < 3) readAt else readAt + 1_000_000_001L
                })
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
            assertEquals(2, port.submissions) // Post-copy stale numeric is discarded before upload.
        }
    }

    @Test fun freshTypedSpeedDrawsMarkedReferenceAndNeverConvertsProfileSpeed() {
        val spec = speedSpec()
        val port = FakePort(output)
        val view = FakeView(spec, speedAvailable(readAt, kmh = 42.0, profileSpeed = 400.0))
        InstrumentRasterOwner(plan(spec), port).use { owner ->
            assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE,
                owner.presentForTest(view, readAt + 10_000_000L))
            assertEquals("BENCH / NOT VEHICLE SAFE", port.marker)
            assertTrue(port.last!!.any { it == CARD_BACKGROUND })
            assertTrue((49..55).any { y ->
                (8..120).any { x -> port.last!![y * output.widthPixels + x] == -1 }
            }) // Numeric text remains visible below the always-on warning banner.
        }
    }

    @Test fun nativeMetresPerSecondRangeDoesNotRejectValidKmhButOutlierAndWrongUnitClear() {
        val spec = speedSpec() // Its PROFILE_SPEED source is signed as m/s.
        val port = FakePort(output)
        val view = FakeView(spec, speedAvailable(readAt, kmh = 100.0,
            profileSpeed = 27.8))
        InstrumentRasterOwner(plan(spec, nativeMaximum = 70.0), port).use { owner ->
            assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE,
                owner.presentForTest(view, readAt))
            assertTrue(port.last!!.any { it == CARD_BACKGROUND })

            view.state = speedAvailable(readAt, kmh = 501.0)
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentForTest(view, readAt))
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })

            val correct = speedAvailable(readAt, kmh = 100.0)
            view.state = InstrumentSignalBundleState.Available(correct.values +
                (InstrumentSignalRole.SPEED_KMH to InstrumentSignalReading.Numeric(
                    CafFormatValue.Floating(100.0), InstrumentNumericMeaning.MILES_PER_HOUR)),
                readAt, correct.sourceAgeMillisByRole)
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentForTest(view, readAt))
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        }
    }

    @Test fun stoppedRpmWithNonzeroValueNeverDrawsNumericPixels() {
        val spec = rpmSpec()
        val port = FakePort(output)
        val view = FakeView(spec, rpmAvailable(readAt, 2_400.0, "stopped"))
        InstrumentRasterOwner(plan(spec, InstrumentReferenceVisual.RPM, 7_000.0), port).use { owner ->
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentForTest(view, readAt))
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
            view.state = rpmAvailable(readAt, 2_400.0, "running")
            assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE,
                owner.presentForTest(view, readAt))
            assertTrue(port.last!!.any { it == CARD_BACKGROUND })
            view.state = rpmAvailable(readAt, 2_400.0, "stopped")
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentForTest(view, readAt))
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        }
    }

    @Test fun highRateAvailableCoalescesButUnavailableAndNearExpiryNeverDo() {
        val spec = speedSpec()
        val port = FakePort(output)
        val view = FakeView(spec, speedAvailable(readAt, kmh = 40.0))
        InstrumentRasterOwner(plan(spec), port).use { owner ->
            assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE,
                owner.presentForTest(view, readAt))
            assertEquals(1, port.submissions)
            view.state = speedAvailable(readAt + 50_000_000L, kmh = 41.0)
            assertEquals(InstrumentRasterOutcome.COALESCED_AVAILABLE,
                owner.presentForTest(view, readAt + 50_000_000L))
            assertEquals(1, port.submissions)
            view.state = InstrumentSignalBundleState.Unavailable(
                InstrumentSignalUnavailableReason.STALE)
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentForTest(view, readAt + 60_000_000L))
            assertEquals(2, port.submissions)
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })

            view.state = speedAvailable(readAt + 100_000_000L, kmh = 42.0,
                sourceAge = 900)
            assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE,
                owner.presentForTest(view, readAt + 100_000_000L))
            view.state = speedAvailable(readAt + 150_000_000L, kmh = 43.0)
            assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE,
                owner.presentForTest(view, readAt + 150_000_000L))
            assertEquals(4, port.submissions) // Old sample would expire before 5 Hz retry.
        }
    }

    @Test fun uncertainAndRetiredFrameCommitsNeverReportDisplayedNumericOrClear() {
        val spec = speedSpec()
        val port = FakePort(output)
        val view = FakeView(spec, speedAvailable(readAt))
        InstrumentRasterOwner(plan(spec), port).use { owner ->
            port.nextResult = InstrumentFrameCommitResult.BUSY_UNCONFIRMED
            assertEquals(InstrumentRasterOutcome.COMMIT_BUSY,
                owner.presentForTest(view, readAt))
            view.state = InstrumentSignalBundleState.Unavailable(
                InstrumentSignalUnavailableReason.STALE)
            port.nextResult = InstrumentFrameCommitResult.RETIREMENT_PENDING
            assertEquals(InstrumentRasterOutcome.RETIREMENT_PENDING,
                owner.presentForTest(view, readAt))
            port.nextResult = InstrumentFrameCommitResult.REJECTED_NO_COMMIT
            assertEquals(InstrumentRasterOutcome.PORT_REJECTED,
                owner.presentUnavailable())
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentUnavailable())
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        }
    }

    @Test fun staleAndUnavailableNeverRetainPreviousValuePixels() {
        val spec = speedSpec()
        val port = FakePort(output)
        val view = FakeView(spec, speedAvailable(readAt, kmh = 42.0))
        val owner = InstrumentRasterOwner(plan(spec), port)
        assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE,
            owner.presentForTest(view, readAt))
        val populated = port.last!!.copyOf()
        assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
            owner.presentForTest(view, readAt + 1_100_000_000L))
        assertNotEquals(populated.toList(), port.last!!.toList())
        assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        view.state = InstrumentSignalBundleState.Unavailable(
            InstrumentSignalUnavailableReason.REPOSITORY_UNAVAILABLE)
        assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
            owner.presentForTest(view, readAt + 1_200_000_000L))
        assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        owner.close()
    }

    @Test fun sourceAcquisitionAgeIsAddedToReadAge() {
        val spec = speedSpec()
        val port = FakePort(output)
        val view = FakeView(spec, speedAvailable(readAt, kmh = 7.0, sourceAge = 900))
        InstrumentRasterOwner(plan(spec), port).use { owner ->
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentForTest(view, readAt + 200_000_000L))
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        }
    }

    @Test fun revocationAndCloseReplaceWithClearedFrame() {
        val spec = speedSpec()
        val port = FakePort(output)
        val view = FakeView(spec, speedAvailable(readAt, kmh = 7.0))
        val owner = InstrumentRasterOwner(plan(spec), port)
        assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE, owner.presentForTest(view, readAt))
        view.live = false
        assertEquals(InstrumentRasterOutcome.REVOKED_CLEARED, owner.presentForTest(view, readAt))
        assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        view.live = true
        owner.presentForTest(view, readAt)
        owner.close()
        assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        assertEquals("BENCH / NOT VEHICLE SAFE", port.marker)
    }

    @Test fun sourceReplacementDuringFrameBuildOrSubmitClearsInsteadOfCommittingOldValue() {
        val spec = speedSpec()
        val port = FakePort(output)
        val view = FakeView(spec, speedAvailable(readAt, kmh = 7.0))
        view.afterFirstSnapshot = {
            view.state = InstrumentSignalBundleState.Unavailable(
                InstrumentSignalUnavailableReason.CONFIG_CHANGED)
        }
        InstrumentRasterOwner(plan(spec), port).use { owner ->
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentForTest(view, readAt))
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        }
        view.state = speedAvailable(readAt, kmh = 7.0)
        view.afterFirstSnapshot = null
        view.live = true
        port.submissions = 0
        port.afterFirstSubmit = { view.live = false }
        InstrumentRasterOwner(plan(spec), port).use { owner ->
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentForTest(view, readAt))
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        }
    }

    @Test fun foreignSourceAndWrongOutputCannotPaint() {
        val spec = speedSpec()
        val foreign = speedSpec()
        val port = FakePort(output)
        val owner = InstrumentRasterOwner(plan(spec), port)
        assertEquals(InstrumentRasterOutcome.REVOKED_CLEARED,
            owner.presentForTest(FakeView(foreign, speedAvailable(readAt)), readAt))
        assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        owner.close()
        assertThrows(IllegalArgumentException::class.java) {
            InstrumentRasterOwner(plan(spec), FakePort(PixelSize(321, 160)))
        }
    }

    @Test fun retiredPortRejectsPixelsWithoutKeepingNumericScratch() {
        val spec = speedSpec()
        val port = FakePort(output).also { it.current = false }
        InstrumentRasterOwner(plan(spec), port).use { owner ->
            assertEquals(InstrumentRasterOutcome.REVOKED_CLEARED,
                owner.presentForTest(FakeView(spec, speedAvailable(readAt)), readAt))
            assertEquals(0, port.submissions)
        }
    }

    @Test fun indicatorUsesOnlyTypedStateAndClearsUnknownState() {
        val spec = indicatorSpec()
        val port = FakePort(output)
        val plan = InstrumentReferenceScenePlan(DisplayDestination.CLUSTER, output,
            UltraPixelRect(0, 0, 200, 80), spec,
            InstrumentReferenceVisual.INDICATOR_STATE, null, null)
        val state = InstrumentSignalBundleState.Available(
            mapOf(InstrumentSignalRole.INDICATOR_STATE to InstrumentSignalReading.State(
                CafFormatValue.Unsigned(java.math.BigInteger.ONE, 8), "on")),
            readAt,
            mapOf(InstrumentSignalRole.INDICATOR_STATE to 0L),
        )
        val view = FakeView(spec, state)
        InstrumentRasterOwner(plan, port).use { owner ->
            assertEquals(InstrumentRasterOutcome.AVAILABLE_REFERENCE,
                owner.presentForTest(view, readAt))
            assertTrue(port.last!!.any { it == CARD_BACKGROUND })
            view.state = InstrumentSignalBundleState.Available(
                mapOf(InstrumentSignalRole.INDICATOR_STATE to InstrumentSignalReading.Numeric(
                    CafFormatValue.Floating(1.0), InstrumentNumericMeaning.PROFILE_SPEED)),
                readAt,
                mapOf(InstrumentSignalRole.INDICATOR_STATE to 0L),
            )
            assertEquals(InstrumentRasterOutcome.UNAVAILABLE_CLEARED,
                owner.presentForTest(view, readAt))
            assertEquals(0, port.last!!.count { it == CARD_BACKGROUND })
        }
    }

    private fun plan(
        spec: InstrumentSignalBundleSpec,
        visual: InstrumentReferenceVisual = InstrumentReferenceVisual.SPEED_KMH,
        nativeMaximum: Double = 300.0,
    ) = InstrumentReferenceScenePlan(
        DisplayDestination.CLUSTER, output, UltraPixelRect(0, 0, 200, 80), spec,
        visual, 0.0, nativeMaximum,
    )

    private fun speedSpec() = InstrumentSignalBundleSpec(
        InstrumentThemeSignalAddress("speed", ZoneRegion.ZONE_1,
            CarPlayThemeSceneSlotAddress(
                CarPlayThemeSceneComponentAddress(
                    CarPlayThemeSceneZoneAddress(CarPlayThemeSceneLayoutAddress(0, "main"),
                        0, "zone"),
                    0, "component"),
                0, "dial")),
        InstrumentSignalBundleShape.SPEED_TRIPLET,
        listOf(
            numeric(InstrumentSignalRole.SPEED, 100, InstrumentNumericMeaning.PROFILE_SPEED,
                "m/s"),
            numeric(InstrumentSignalRole.SPEED_KMH, 101,
                InstrumentNumericMeaning.KILOMETRES_PER_HOUR, "km/h"),
            numeric(InstrumentSignalRole.SPEED_MPH, 102,
                InstrumentNumericMeaning.MILES_PER_HOUR, "mph"),
        ), 1_000)

    private fun indicatorSpec() = InstrumentSignalBundleSpec(
        InstrumentThemeSignalAddress("warning", ZoneRegion.ZONE_1,
            CarPlayThemeSceneSlotAddress(
                CarPlayThemeSceneComponentAddress(
                    CarPlayThemeSceneZoneAddress(CarPlayThemeSceneLayoutAddress(0, "main"),
                        0, "zone"),
                    0, "component"),
                0, "dial")),
        InstrumentSignalBundleShape.INDICATOR_STATE,
        listOf(InstrumentSignalSource(InstrumentSignalRole.INDICATOR_STATE,
            InstrumentCafCharacteristicPin(2, 200, 103, 1003, CafCharacteristicFormat.UINT8),
            InstrumentSignalSemantics.State(listOf(InstrumentStateCode(0L, "off"),
                InstrumentStateCode(1L, "on"))))), 1_000)

    private fun rpmSpec() = InstrumentSignalBundleSpec(
        InstrumentThemeSignalAddress("rpm", ZoneRegion.ZONE_1,
            CarPlayThemeSceneSlotAddress(
                CarPlayThemeSceneComponentAddress(
                    CarPlayThemeSceneZoneAddress(CarPlayThemeSceneLayoutAddress(0, "main"),
                        0, "zone"),
                    0, "component"),
                0, "dial")),
        InstrumentSignalBundleShape.RPM_VALUE_AND_STATE,
        listOf(
            numeric(InstrumentSignalRole.RPM_VALUE, 103,
                InstrumentNumericMeaning.REVOLUTIONS_PER_MINUTE, "rpm"),
            InstrumentSignalSource(InstrumentSignalRole.RPM_STATE,
                InstrumentCafCharacteristicPin(2, 200, 104, 1004,
                    CafCharacteristicFormat.UINT8),
                InstrumentSignalSemantics.State(listOf(InstrumentStateCode(0L, "stopped"),
                    InstrumentStateCode(1L, "running")))),
        ), 1_000)

    private fun rpmAvailable(readAtNanos: Long, rpm: Double, stateName: String) =
        InstrumentSignalBundleState.Available(mapOf(
            InstrumentSignalRole.RPM_VALUE to InstrumentSignalReading.Numeric(
                CafFormatValue.Floating(rpm), InstrumentNumericMeaning.REVOLUTIONS_PER_MINUTE),
            InstrumentSignalRole.RPM_STATE to InstrumentSignalReading.State(
                CafFormatValue.Unsigned(java.math.BigInteger.valueOf(
                    if (stateName == "running") 1L else 0L), 8), stateName),
        ), readAtNanos, mapOf(
            InstrumentSignalRole.RPM_VALUE to 0L,
            InstrumentSignalRole.RPM_STATE to 0L,
        ))

    private fun numeric(role: InstrumentSignalRole, iid: Long,
        meaning: InstrumentNumericMeaning, units: String) = InstrumentSignalSource(role,
        InstrumentCafCharacteristicPin(2, 200, iid, iid + 900,
            CafCharacteristicFormat.FLOAT),
        InstrumentSignalSemantics.Numeric(meaning, units))

    private fun speedAvailable(
        readAtNanos: Long,
        kmh: Double = 42.0,
        profileSpeed: Double = 10.0,
        sourceAge: Long = 0,
    ): InstrumentSignalBundleState.Available {
        val values = linkedMapOf(
            InstrumentSignalRole.SPEED to InstrumentSignalReading.Numeric(
                CafFormatValue.Floating(profileSpeed), InstrumentNumericMeaning.PROFILE_SPEED),
            InstrumentSignalRole.SPEED_KMH to InstrumentSignalReading.Numeric(
                CafFormatValue.Floating(kmh), InstrumentNumericMeaning.KILOMETRES_PER_HOUR),
            InstrumentSignalRole.SPEED_MPH to InstrumentSignalReading.Numeric(
                CafFormatValue.Floating(26.1), InstrumentNumericMeaning.MILES_PER_HOUR),
        )
        return InstrumentSignalBundleState.Available(values, readAtNanos,
            values.keys.associateWith { sourceAge })
    }

    private class FakeView(
        override val spec: InstrumentSignalBundleSpec,
        var state: InstrumentSignalBundleState,
    ) : InstrumentReferenceBundleView {
        var live = true
        var afterFirstSnapshot: (() -> Unit)? = null
        override val isLive: Boolean get() = live
        override fun snapshot(): InstrumentSignalBundleState = state.also {
            afterFirstSnapshot?.also { callback ->
                afterFirstSnapshot = null
                callback()
            }
        }
    }

    private class FakePort(override val viewportPixels: PixelSize) :
        InstrumentDedicatedRasterPort {
        override val destination = DisplayDestination.CLUSTER
        var current = true
        override val isCurrent: Boolean get() = current
        var marker: String? = null
        var last: IntArray? = null
        var submissions = 0
        private var frameSequence = 0L
        override val committedFrameSequence: Long? get() = frameSequence.takeIf { it > 0L }
        var afterFirstSubmit: (() -> Unit)? = null
        var nextResult: InstrumentFrameCommitResult? = null
        override fun replaceCompleteOpaqueFrame(
            argbPixels: IntArray,
            safetyMarker: String,
        ): InstrumentFrameCommitResult {
            if (!current) return InstrumentFrameCommitResult.OUTPUT_RETIRED
            submissions++
            val result = nextResult?.also { nextResult = null }
                ?: InstrumentFrameCommitResult.COMMITTED_CURRENT
            if (result == InstrumentFrameCommitResult.COMMITTED_CURRENT ||
                result == InstrumentFrameCommitResult.BUSY_UNCONFIRMED
            ) {
                frameSequence++
                marker = safetyMarker
                last = argbPixels.copyOf()
            }
            if (submissions == 1) {
                afterFirstSubmit?.also { afterFirstSubmit = null; it() }
            }
            return result
        }
    }

    private class BusyOperations(override val viewportPixels: PixelSize) :
        InstrumentRasterEglOperations {
        override val destination = DisplayDestination.CLUSTER
        var status = UiSyncPhysicalGenerationStatus.CURRENT
        var uploads = 0
        var retireCalls = 0
        override fun generationStatus() = status
        override fun upload(frame: IntArray): UltraEglInstrumentUploadReceipt {
            uploads++
            return UltraEglInstrumentUploadReceipt.COMMITTED
        }
        override fun sealFailure(reason: Throwable) = Unit
        override fun retireOutput(): Boolean { retireCalls++; return true }
        override fun releaseIngress() = Unit
    }

    private companion object {
        const val CARD_BACKGROUND = -0xd9d6d0
    }
}
