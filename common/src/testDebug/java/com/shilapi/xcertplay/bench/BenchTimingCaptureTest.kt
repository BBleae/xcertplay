package com.shilapi.xcertplay.bench

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchTimingCaptureTest {
    private val session = Any()
    private val display = Any()
    private val handle = Any()
    private val surface = Any()

    @Test fun type2PreservesUnsignedBitsWithoutClockConversionOrPayloadNames() {
        val capture = BenchTimingCapture(session)
        val privateDisplayToken = object {
            override fun toString() = "VIN_AND_REGION_SECRET"
        }
        assertTrue(capture.recordType2(session, privateDisplayToken, 1L,
            -1L, Long.MIN_VALUE, null, 0L, 123L))
        assertTrue(capture.recordType2(session, privateDisplayToken, 2L,
            -1L, Long.MIN_VALUE, -2L, 0L, 124L))
        val first = capture.exportNdjson()
        assertEquals(first, capture.exportNdjson())
        assertEquals(1, Regex("\"schema\":\"xcertplay-timing-v2\"").findAll(first).count())
        assertFalse(first.contains("\"schema\":\"xcertplay-timing-v1\""))
        assertTrue(first.contains("\"display\":\"display#1\""))
        assertTrue(first.contains("0xffffffffffffffff"))
        assertTrue(first.contains("0x8000000000000000"))
        assertTrue(first.contains("0xfffffffffffffffe"))
        assertTrue(first.contains("\"presentation_ntp_time_original\":null"))
        assertTrue(first.contains("\"domain\":\"phone_host_ticks_raw_u64_unmapped\""))
        assertTrue(first.contains(
            "\"domain\":\"phone_boot_relative_ntp32_32_raw_u64_unmapped\""))
        assertTrue(first.contains("\"domain\":\"android_monotonic_nanos\""))
        assertFalse(first.contains("VIN_AND_REGION_SECRET"))
        assertFalse(first.contains("offset"))
        assertEquals(2, capture.snapshot().retainedRecords)
    }

    @Test fun type2BootRelativeNtpAndVideoNetworkClockNtpRemainDistinctRawDomains() {
        val capture = BenchTimingCapture(session)
        assertTrue(capture.recordType2(session, display, 1L, -1L, -2L, -3L,
            -4L, 123L))
        assertTrue(release(capture, 1L))
        assertTrue(match(capture, 1L))
        val rows = capture.exportNdjson().lineSequence().filter { it.isNotEmpty() }.toList()
        val type2 = rows.single { it.contains("\"kind\":\"ui_sync_type2\"") }
        val release = rows.single { it.contains("\"kind\":\"video_release\"") }
        val egl = rows.single { it.contains("\"kind\":\"egl_matched\"") }
        val bootDomain = "phone_boot_relative_ntp32_32_raw_u64_unmapped"
        val videoDomain = "video_network_clock_ntp64_raw_u64_unmapped"
        assertTrue(type2.contains("phone_host_ticks_raw_u64_unmapped"))
        assertTrue(type2.contains(bootDomain))
        assertFalse(type2.contains(videoDomain))
        assertTrue(release.contains(videoDomain))
        assertTrue(egl.contains(videoDomain))
        assertFalse(release.contains(bootDomain))
        assertFalse(egl.contains(bootDomain))
        assertTrue(type2.contains("0xffffffffffffffff"))
        assertTrue(type2.contains("0xfffffffffffffffe"))
        assertTrue(type2.contains("0xfffffffffffffffd"))
        assertTrue(type2.contains("0xfffffffffffffffc"))
        assertTrue(release.contains("0x0000000000000011"))
        assertTrue(egl.contains("0x0000000000000011"))
    }

    @Test fun duplicateRevisionAndCrossSessionAreRejectedWithoutChangingRows() {
        val capture = BenchTimingCapture(session)
        assertTrue(type2(capture, revision = 7L))
        val firstRows = capture.exportNdjson().lines().drop(1)
        assertFalse(type2(capture, revision = 7L))
        assertFalse(type2(capture, revision = 6L))
        assertFalse(capture.recordType2(Any(), display, 8L, 1L, 2L, 3L, 4L, 5L))
        assertEquals(firstRows, capture.exportNdjson().lines().drop(1))
        assertEquals(1, capture.snapshot().retainedRecords)
        assertEquals(3L, capture.snapshot().rejectedRecords)
        assertTrue(type2(capture, revision = 8L))
        assertEquals(2, capture.snapshot().retainedRecords)
    }

    @Test fun type111TimelineIdIsOptionalUnsignedRawBitsAndNeverUsedForMatching() {
        val capture = BenchTimingCapture(session)
        assertTrue(release(capture, 1L, timelineIdRawBits = -1L))
        assertTrue(match(capture, 1L))
        assertTrue(release(capture, 2L))
        val rows = capture.exportNdjson().lineSequence()
            .filter { it.contains("\"kind\":\"video_release\"") }.toList()
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains(
            "\"timeline_id\":{\"domain\":\"video_timeline_id_raw_u64\"," +
                "\"bits\":\"0xffffffffffffffff\"}"))
        assertTrue(rows[1].contains("\"timeline_id\":null"))
        assertFalse(capture.exportNdjson().contains("auxiliary_network_time"))
        assertFalse(capture.exportNdjson().contains("auxiliary_offset"))
    }

    @Test fun exactReleaseAndEglMatchUseOnlySameHandleSurfaceLeaseFrameAndGeneration() {
        val capture = BenchTimingCapture(session)
        assertTrue(release(capture, 5L))
        assertFalse(match(capture, 5L, handleToken = Any()))
        assertFalse(match(capture, 5L, surfaceToken = Any()))
        assertFalse(match(capture, 5L, surfaceLeaseGeneration = 2L))
        assertFalse(match(capture, 5L, ntpBits = 0x12L))
        assertFalse(match(capture, 5L, textureTimestampNanos = 8_000L))
        assertFalse(match(capture, 5L,
            basis = BenchTimingCapture.TimestampBasis.TIMED_CODEC_RELEASE))
        assertTrue(match(capture, 5L))
        assertFalse(match(capture, 5L))
        assertTrue(release(capture, 6L))
        assertFalse(match(capture, 6L, ingressGeneration = 10L))
        assertTrue(match(capture, 6L, updateSequence = 2L))
        val exported = capture.exportNdjson()
        assertTrue(exported.contains("\"route\":\"route#1\""))
        assertTrue(exported.contains("\"ingress\":\"ingress#1\""))
        assertTrue(exported.contains("\"kind\":\"video_release\""))
        assertTrue(exported.contains("\"kind\":\"egl_matched\""))
        assertFalse(exported.contains("987654321")) // Raw lease and ingress generations.
    }

    @Test fun busyRetainsSanitizedRowsButForgetsPendingJoinAndNeverClaimsCurrent() {
        val capture = BenchTimingCapture(session)
        assertTrue(type2(capture, 1L))
        assertTrue(release(capture, 1L))
        assertTrue(capture.markBusy(session))
        assertEquals(BenchTimingCapture.State.BUSY, capture.snapshot().state)
        assertEquals(2, capture.snapshot().retainedRecords)
        assertTrue(capture.exportNdjson().contains("\"state\":\"BUSY\""))
        assertFalse(type2(capture, 2L))
        assertFalse(match(capture, 1L))
        assertFalse(capture.resumeCurrentFromExternalAuthority(Any()))
        assertTrue(capture.resumeCurrentFromExternalAuthority(session))
        assertFalse(match(capture, 1L))
        assertTrue(type2(capture, 2L))
        assertEquals(BenchTimingCapture.State.CURRENT, capture.snapshot().state)
    }

    @Test fun boundedRingAndExportDoNotGrowPastConfiguredLimits() {
        val capture = BenchTimingCapture(session, maximumRecords = 2,
            maximumExportBytes = 2_048)
        repeat(128) { index -> assertTrue(type2(capture, index + 1L)) }
        val summary = capture.snapshot()
        assertTrue(summary.retainedRecords in 1..2)
        assertTrue(summary.droppedOverflow > 0L)
        assertTrue(capture.exportNdjson().toByteArray(Charsets.UTF_8).size <= 2_048)
        assertFalse(type2(capture, 128L)) // Eviction does not reset revision monotonicity.
        assertEquals(1L, capture.snapshot().rejectedRecords)
    }

    @Test fun observerAndSourceLossAreExportedSeparatelyFromRingOverflowEvenDuringBusy() {
        val capture = BenchTimingCapture(session)
        assertTrue(type2(capture, 1L))
        assertTrue(capture.noteObserverSnapshotDrops(session, 3L))
        assertTrue(capture.noteSourceRetainedEventDrops(session, 7L))
        assertTrue(capture.markBusy(session))
        assertTrue(capture.noteObserverSnapshotDrops(session, 5L))
        assertTrue(capture.noteSourceRetainedEventDrops(session, 9L))
        assertFalse(capture.noteObserverSnapshotDrops(session, 4L))
        assertFalse(capture.noteObserverSnapshotDrops(Any(), 6L))
        assertFalse(capture.noteObserverSnapshotDrops(session, -1L))
        assertFalse(capture.noteSourceRetainedEventDrops(session, 8L))
        assertFalse(capture.noteSourceRetainedEventDrops(Any(), 10L))
        assertFalse(capture.noteSourceRetainedEventDrops(session, -1L))
        val summary = capture.snapshot()
        assertEquals(BenchTimingCapture.State.BUSY, summary.state)
        assertEquals(5L, summary.observerSnapshotDrops)
        assertEquals(9L, summary.sourceRetainedEventDrops)
        assertEquals(0L, summary.droppedOverflow)
        val header = capture.exportNdjson().lineSequence().first()
        assertTrue(header.contains("\"observer_snapshot_drops\":5"))
        assertTrue(header.contains("\"source_retained_event_drops\":9"))
        assertTrue(header.contains("\"dropped_overflow\":0"))
        assertTrue(capture.revoke(session))
        assertEquals(0L, capture.snapshot().observerSnapshotDrops)
        assertEquals(0L, capture.snapshot().sourceRetainedEventDrops)
        assertEquals("", capture.exportNdjson())
        assertFalse(capture.noteObserverSnapshotDrops(session, 7L))
        assertFalse(capture.noteSourceRetainedEventDrops(session, 11L))
    }

    @Test fun releaseGenerationRotationRejectsStaleLeaseAndErasesOldPendingJoin() {
        val capture = BenchTimingCapture(session)
        assertTrue(release(capture, 1L))
        assertTrue(release(capture, 2L, surfaceToken = Any(),
            surfaceLeaseGeneration = 2L))
        assertFalse(match(capture, 1L))
        assertFalse(release(capture, 3L))
        assertEquals(2, capture.snapshot().retainedRecords)
    }

    @Test fun revokeAndCloseEraseRecordsAndIdentityEvenDuringConcurrentExport() {
        val capture = BenchTimingCapture(session)
        assertTrue(type2(capture, 1L))
        val start = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val exporter = Thread {
            try {
                start.await()
                repeat(200) {
                    val output = capture.exportNdjson()
                    assertTrue(output.isEmpty() || output.startsWith("{\"kind\":\"capture\""))
                    assertTrue(output.isEmpty() || output.endsWith("\n"))
                }
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                finished.countDown()
            }
        }
        exporter.start()
        start.countDown()
        capture.close()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertNull(error.get())
        assertEquals(BenchTimingCapture.State.REVOKED, capture.snapshot().state)
        assertNull(capture.snapshot().captureId)
        assertEquals(0, capture.snapshot().retainedRecords)
        assertEquals("", capture.exportNdjson())
        assertFalse(type2(capture, 2L))
        assertFalse(capture.revoke(session))
        assertFalse(capture.resumeCurrentFromExternalAuthority(session))
    }

    private fun type2(capture: BenchTimingCapture, revision: Long): Boolean =
        capture.recordType2(session, display, revision, 1L, 2L, 3L, 4L, 5L)

    private fun release(
        capture: BenchTimingCapture,
        counter: Long,
        handleToken: Any = handle,
        surfaceToken: Any = surface,
        surfaceLeaseGeneration: Long = 1L,
        timelineIdRawBits: Long? = null,
    ): Boolean = capture.recordVideoRelease(
        session, display, handleToken, surfaceToken, surfaceLeaseGeneration,
        counter, 0x11L, 7L, 100L, 101L, null, 7_000L,
        timelineIdRawBits)

    private fun match(
        capture: BenchTimingCapture,
        counter: Long,
        handleToken: Any = handle,
        surfaceToken: Any = surface,
        surfaceLeaseGeneration: Long = 1L,
        ingressGeneration: Long = 987654321L,
        updateSequence: Long = 1L,
        textureTimestampNanos: Long = 7_000L,
        ntpBits: Long = 0x11L,
        basis: BenchTimingCapture.TimestampBasis =
            BenchTimingCapture.TimestampBasis.CODEC_OUTPUT_PTS,
    ): Boolean = capture.recordEglMatched(
        session, display, handleToken, surfaceToken, surfaceLeaseGeneration,
        ingressGeneration, updateSequence, textureTimestampNanos, basis,
        counter, ntpBits, 7L)
}
