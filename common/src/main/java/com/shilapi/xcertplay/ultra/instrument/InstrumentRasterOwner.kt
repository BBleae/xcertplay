package com.shilapi.xcertplay.ultra.instrument

import android.os.SystemClock
import com.shilapi.xcertplay.airplay.rcs.caf.CafFormatValue
import com.shilapi.xcertplay.AuthorizedInstrumentSignalSession
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraPixelRect
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentNumericMeaning
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleSpec
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleState
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalReading
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalRole
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The future physical-output adapter must own a dedicated, replace-only CLUSTER surface. This
 * port has no Canvas, painter, compositor layer, alpha, z-index, or overlay operation. A product
 * adapter must atomically replace an entire opaque frame and reject retired output generations.
 */
internal interface InstrumentDedicatedRasterPort {
    val destination: DisplayDestination
    val viewportPixels: PixelSize
    val isCurrent: Boolean
    /** Exact ingress upload ordinal; absent on test-only generic ports. */
    val committedFrameSequence: Long? get() = null

    /** Metadata-only gate before allocating an owned snapshot; READY is not a commit proof. */
    fun preflightSubmission(intent: InstrumentFrameIntent): InstrumentFramePreflightResult =
        InstrumentFramePreflightResult.READY

    fun replaceCompleteOpaqueFrame(
        argbPixels: IntArray,
        safetyMarker: String,
    ): InstrumentFrameCommitResult

    /** The product EGL port overrides this to preserve ownership through the render queue. */
    fun replaceOwnedCompleteOpaqueFrame(
        frame: OwnedOpaqueArgbFrame,
        safetyMarker: String,
    ): InstrumentFrameCommitResult = frame.take()?.let {
        replaceCompleteOpaqueFrame(it, safetyMarker)
    } ?: InstrumentFrameCommitResult.REJECTED_NO_COMMIT

    /** Preserves clear-vs-numeric intent through the post-copy generation recheck. */
    fun replaceOwnedCompleteOpaqueFrame(
        frame: OwnedOpaqueArgbFrame,
        safetyMarker: String,
        intent: InstrumentFrameIntent,
    ): InstrumentFrameCommitResult = replaceOwnedCompleteOpaqueFrame(frame, safetyMarker)
}

internal enum class InstrumentFrameIntent { NUMERIC_REFERENCE, SAFETY_CLEAR }

internal enum class InstrumentFramePreflightResult {
    READY,
    BUSY_UNCONFIRMED,
    RETIREMENT_PENDING,
    OUTPUT_RETIRED,
    REJECTED_NO_COMMIT,
}

/** Result of one full-frame swap; an upload attempt is not by itself display proof. */
internal enum class InstrumentFrameCommitResult {
    /** Complete swap finished while the exact physical generation remained current. */
    COMMITTED_CURRENT,
    /** Swap may have completed during a transient generation BUSY period; retry is required. */
    BUSY_UNCONFIRMED,
    /** Target has truly revoked; whole output retirement has been requested, not yet proved. */
    RETIREMENT_PENDING,
    /** Whole old physical output retirement is proved; its old pixels cannot remain visible. */
    OUTPUT_RETIRED,
    /** Validation rejected the frame before any upload; no new display proof exists. */
    REJECTED_NO_COMMIT,
}

/** Narrow read-only view; the product adapter must be an authorized instrument session. */
internal interface InstrumentReferenceBundleView {
    val spec: InstrumentSignalBundleSpec
    val isLive: Boolean
    fun snapshot(): InstrumentSignalBundleState
}

internal enum class InstrumentRasterOutcome {
    AVAILABLE_REFERENCE,
    /** A fresh value was observed but no new pixels were submitted inside the Debug 5 Hz gate. */
    COALESCED_AVAILABLE,
    COMMIT_BUSY,
    RETIREMENT_PENDING,
    UNAVAILABLE_CLEARED,
    REVOKED_CLEARED,
    PORT_REJECTED,
}

/** Actual latest full-frame texture content; an outcome enum is not presentation authority. */
internal sealed interface InstrumentCommittedContent {
    data object Unknown : InstrumentCommittedContent
    class SafetyClear(val frameSequence: Long) : InstrumentCommittedContent
    class Numeric(
        val frameSequence: Long,
        val session: AuthorizedInstrumentSignalSession,
        val sample: InstrumentSignalBundleState.Available,
        val expiresAtNanos: Long,
    ) : InstrumentCommittedContent
}

/**
 * Opaque Debug reference raster. It never reuses old numeric pixels: every call clears and draws
 * the complete frame, and checks source freshness and liveness again immediately before commit.
 * This is not an OEM/native gauge and is intentionally not wired to the generic Canvas path.
 */
internal class InstrumentRasterOwner(
    private val plan: InstrumentReferenceScenePlan,
    private val port: InstrumentDedicatedRasterPort,
    private val captureFrame: (IntArray) -> OwnedOpaqueArgbFrame? =
        OwnedOpaqueArgbFrame::capture,
) : AutoCloseable {
    private val width = plan.viewportPixels.widthPixels
    private val height = plan.viewportPixels.heightPixels
    private val pixels = IntArray(width * height)
    private var closed = false
    private var lastAvailableCommitNanos: Long? = null
    private var lastAvailableExpiryNanos: Long? = null
    @Volatile private var committedContent: InstrumentCommittedContent =
        InstrumentCommittedContent.Unknown

    internal fun committedContentSnapshot(): InstrumentCommittedContent = committedContent

    init {
        require(port.destination == plan.destination &&
            port.viewportPixels == plan.viewportPixels) {
            "Reference instrument requires its exact dedicated cluster output"
        }
    }

    /**
     * Product entry point. The caller cannot pick a timestamp: the same boot-relative clock used
     * by Android source-acquisition ages and the bundle's default clock is sampled here. If the
     * platform clock is unavailable, numeric pixels fail closed instead of using another epoch.
     */
    fun present(session: AuthorizedInstrumentSignalSession): InstrumentRasterOutcome =
        present(session) { true }

    /**
     * Same as [present], with an owner-side authorization (for example the exact phone epoch)
     * folded into liveness. Every pre/post-commit freshness check re-reads it, so an epoch that
     * ends mid-frame turns the numeric attempt into a safety clear; attribution stays Numeric.
     */
    internal fun present(
        session: AuthorizedInstrumentSignalSession,
        stillAuthorized: () -> Boolean,
    ): InstrumentRasterOutcome = present(session, stillAuthorized) {
        runCatching { SystemClock.elapsedRealtimeNanos() }.getOrNull()
    }

    /**
     * Owner seam: the boot-relative clock must be the bundle's own source-age clock. The product
     * owner passes Android's platform clock; only JVM tests pass the bundle's stub fallback.
     */
    internal fun present(
        session: AuthorizedInstrumentSignalSession,
        stillAuthorized: () -> Boolean,
        bootClockNanos: () -> Long?,
    ): InstrumentRasterOutcome =
        presentAt(object : InstrumentReferenceBundleView {
            override val spec: InstrumentSignalBundleSpec get() = session.spec
            override val isLive: Boolean get() =
                session.isLive && runCatching(stillAuthorized).getOrDefault(false)
            override fun snapshot(): InstrumentSignalBundleState = session.snapshot()
        }, { runCatching(bootClockNanos).getOrNull() }, session)

    /** Deterministic test seam only; product integrations must call [present] with a session. */
    internal fun presentForTest(
        view: InstrumentReferenceBundleView,
        nowNanos: Long,
    ): InstrumentRasterOutcome = presentAt(view, { nowNanos }, null)

    /** Deterministic advancing-clock seam for expiry during a frame submission. */
    internal fun presentForTestWithClock(
        view: InstrumentReferenceBundleView,
        clock: () -> Long?,
    ): InstrumentRasterOutcome = presentAt(view, clock, null)

    /**
     * Initial physical frame before opening/subscribing a data source. This never reads a source
     * or reuses a previous value, and uses the same whole-frame replacement as later updates.
     */
    @Synchronized
    fun presentUnavailable(): InstrumentRasterOutcome {
        if (closed) return InstrumentRasterOutcome.PORT_REJECTED
        committedContent = InstrumentCommittedContent.Unknown
        clearAvailableThrottle()
        pixels.fill(BACKGROUND)
        drawText("DATA UNAVAILABLE", 8, markerHeight() + 8, WARNING_TEXT)
        paintMarker()
        val committed = submit(InstrumentFrameIntent.SAFETY_CLEAR).commit
        if (committed != InstrumentFrameCommitResult.COMMITTED_CURRENT) {
            pixels.fill(BACKGROUND)
            return committed.nonCurrentOutcome()
        }
        committedContent = port.committedFrameSequence?.takeIf { it > 0L }?.let(
            InstrumentCommittedContent::SafetyClear) ?: InstrumentCommittedContent.Unknown
        return InstrumentRasterOutcome.UNAVAILABLE_CLEARED
    }

    @Synchronized
    private fun presentAt(
        view: InstrumentReferenceBundleView,
        clock: () -> Long?,
        authorizedSession: AuthorizedInstrumentSignalSession?,
    ): InstrumentRasterOutcome {
        val firstNow = clock()
        val state = if (!closed && view.spec === plan.spec && view.isLive) {
            runCatching { view.snapshot() }.getOrNull()
        } else null
        val value = (state as? InstrumentSignalBundleState.Available)?.let { available ->
            firstNow?.let { resolveFreshValue(available, it) }
        }
        if (value != null && firstNow != null &&
            shouldCoalesce(firstNow, state as InstrumentSignalBundleState.Available) &&
            view.isLive && view.spec === plan.spec &&
            runCatching { view.snapshot() === state }.getOrDefault(false)
        ) {
            if (runCatching { port.isCurrent }.getOrDefault(false)) {
                return InstrumentRasterOutcome.COALESCED_AVAILABLE
            }
        }
        committedContent = InstrumentCommittedContent.Unknown
        pixels.fill(BACKGROUND)
        val outcome = when {
            closed || view.spec !== plan.spec || !view.isLive -> {
                drawText("SOURCE REVOKED", 8, markerHeight() + 8, WARNING_TEXT)
                InstrumentRasterOutcome.REVOKED_CLEARED
            }
            value == null -> {
                drawText("DATA UNAVAILABLE", 8, markerHeight() + 8, WARNING_TEXT)
                InstrumentRasterOutcome.UNAVAILABLE_CLEARED
            }
            else -> {
                drawReferenceCard(value)
                InstrumentRasterOutcome.AVAILABLE_REFERENCE
            }
        }
        paintMarker()
        // A source can expire or be replaced while raster work is in progress. The bundle's
        // snapshot returns the same Available object until a new publication or expiry.
        val stillFresh = if (outcome == InstrumentRasterOutcome.AVAILABLE_REFERENCE) {
            val beforeCommitNow = clock()
            beforeCommitNow != null && view.isLive && view.spec === plan.spec &&
                runCatching { view.snapshot() === state }.getOrDefault(false) &&
                resolveFreshValue(state as InstrumentSignalBundleState.Available,
                    beforeCommitNow) != null
        } else true
        val finalOutcome = if (stillFresh) outcome else {
            pixels.fill(BACKGROUND)
            drawText("DATA UNAVAILABLE", 8, markerHeight() + 8, WARNING_TEXT)
            paintMarker()
            InstrumentRasterOutcome.UNAVAILABLE_CLEARED
        }
        val submission = submit(
            if (finalOutcome == InstrumentRasterOutcome.AVAILABLE_REFERENCE)
                InstrumentFrameIntent.NUMERIC_REFERENCE else InstrumentFrameIntent.SAFETY_CLEAR,
            numericStillCurrent = if (finalOutcome == InstrumentRasterOutcome.AVAILABLE_REFERENCE) {
                {
                    val now = clock()
                    now != null && view.isLive && view.spec === plan.spec &&
                        runCatching { view.snapshot() === state }.getOrDefault(false) &&
                        resolveFreshValue(state as InstrumentSignalBundleState.Available, now) != null
                }
            } else null,
        )
        val finalSubmittedOutcome = if (finalOutcome == InstrumentRasterOutcome.AVAILABLE_REFERENCE &&
            submission.intent == InstrumentFrameIntent.SAFETY_CLEAR
        ) InstrumentRasterOutcome.UNAVAILABLE_CLEARED else finalOutcome
        val committed = submission.commit
        if (committed != InstrumentFrameCommitResult.COMMITTED_CURRENT) {
            clearAvailableThrottle()
            pixels.fill(BACKGROUND)
            return committed.nonCurrentOutcome()
        }
        val afterCommitNow = if (finalSubmittedOutcome == InstrumentRasterOutcome.AVAILABLE_REFERENCE) {
            clock()
        } else null
        if (finalSubmittedOutcome == InstrumentRasterOutcome.AVAILABLE_REFERENCE &&
            (afterCommitNow?.let { now ->
                resolveFreshValue(state as InstrumentSignalBundleState.Available, now) != null
            } != true || !view.isLive ||
                runCatching { view.snapshot() === state }.getOrDefault(false).not())
        ) {
            clearAvailableThrottle()
            pixels.fill(BACKGROUND)
            drawText("DATA UNAVAILABLE", 8, markerHeight() + 8, WARNING_TEXT)
            paintMarker()
            val cleared = submit(InstrumentFrameIntent.SAFETY_CLEAR).commit
            if (cleared != InstrumentFrameCommitResult.COMMITTED_CURRENT) {
                pixels.fill(BACKGROUND)
                return cleared.nonCurrentOutcome()
            }
            committedContent = port.committedFrameSequence?.takeIf { it > 0L }?.let(
                InstrumentCommittedContent::SafetyClear) ?: InstrumentCommittedContent.Unknown
            return InstrumentRasterOutcome.UNAVAILABLE_CLEARED
        }
        if (finalSubmittedOutcome == InstrumentRasterOutcome.AVAILABLE_REFERENCE) {
            val available = state as InstrumentSignalBundleState.Available
            lastAvailableCommitNanos = afterCommitNow
            lastAvailableExpiryNanos = available.expiryNanos()
            val sequence = port.committedFrameSequence
            val expiry = available.expiryNanos()
            committedContent = if (authorizedSession != null && sequence != null &&
                sequence > 0L && expiry != null && afterCommitNow != null &&
                afterCommitNow <= expiry && view.isLive &&
                runCatching { view.snapshot() === available }.getOrDefault(false)
            ) InstrumentCommittedContent.Numeric(sequence, authorizedSession, available,
                expiry) else InstrumentCommittedContent.Unknown
        } else {
            clearAvailableThrottle()
            committedContent = port.committedFrameSequence?.takeIf { it > 0L }?.let(
                InstrumentCommittedContent::SafetyClear) ?: InstrumentCommittedContent.Unknown
        }
        return finalSubmittedOutcome
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        committedContent = InstrumentCommittedContent.Unknown
        clearAvailableThrottle()
        pixels.fill(BACKGROUND)
        drawText("SOURCE REVOKED", 8, markerHeight() + 8, WARNING_TEXT)
        paintMarker()
        if (submit(InstrumentFrameIntent.SAFETY_CLEAR).commit ==
            InstrumentFrameCommitResult.COMMITTED_CURRENT
        ) committedContent = port.committedFrameSequence?.takeIf { it > 0L }?.let(
            InstrumentCommittedContent::SafetyClear) ?: InstrumentCommittedContent.Unknown
        pixels.fill(BACKGROUND)
    }

    private data class Submission(
        val commit: InstrumentFrameCommitResult,
        val intent: InstrumentFrameIntent,
    )

    private fun submit(
        intent: InstrumentFrameIntent,
        numericStillCurrent: (() -> Boolean)? = null,
    ): Submission = runCatching {
        fun unavailableInstead(): Submission {
            pixels.fill(BACKGROUND)
            drawText("DATA UNAVAILABLE", 8, markerHeight() + 8, WARNING_TEXT)
            paintMarker()
            return submit(InstrumentFrameIntent.SAFETY_CLEAR)
        }
        // A BUSY physical generation can persist across many 50 ms source polls. Retry only this
        // typed metadata gate until CURRENT; never allocate doomed multi-megabyte frame copies.
        // A safety clear with no prior clear proof forces retirement instead of waiting behind
        // BUSY while old numeric pixels might remain on glass.
        when (port.preflightSubmission(intent)) {
            InstrumentFramePreflightResult.READY -> Unit
            InstrumentFramePreflightResult.BUSY_UNCONFIRMED -> {
                if (numericStillCurrent?.invoke() == false) return@runCatching unavailableInstead()
                return@runCatching Submission(InstrumentFrameCommitResult.BUSY_UNCONFIRMED, intent)
            }
            InstrumentFramePreflightResult.RETIREMENT_PENDING ->
                return@runCatching Submission(InstrumentFrameCommitResult.RETIREMENT_PENDING, intent)
            InstrumentFramePreflightResult.OUTPUT_RETIRED ->
                return@runCatching Submission(InstrumentFrameCommitResult.OUTPUT_RETIRED, intent)
            InstrumentFramePreflightResult.REJECTED_NO_COMMIT ->
                return@runCatching Submission(InstrumentFrameCommitResult.REJECTED_NO_COMMIT, intent)
        }
        val frame = captureFrame(pixels)
            ?: return@runCatching Submission(InstrumentFrameCommitResult.REJECTED_NO_COMMIT, intent)
        // The owned copy may take long enough for data or authorization to age out. Never hand
        // those numeric pixels to EGL once this exact post-copy check fails.
        if (numericStillCurrent?.invoke() == false) return@runCatching unavailableInstead()
        val committed = port.replaceOwnedCompleteOpaqueFrame(frame, plan.safetyMarker, intent)
        if (committed != InstrumentFrameCommitResult.COMMITTED_CURRENT &&
            numericStillCurrent?.invoke() == false
        ) return@runCatching unavailableInstead()
        Submission(committed, intent)
    }.getOrDefault(Submission(InstrumentFrameCommitResult.RETIREMENT_PENDING, intent))

    private fun InstrumentFrameCommitResult.nonCurrentOutcome(): InstrumentRasterOutcome = when (this) {
        InstrumentFrameCommitResult.COMMITTED_CURRENT -> error("Committed result is current")
        InstrumentFrameCommitResult.BUSY_UNCONFIRMED -> InstrumentRasterOutcome.COMMIT_BUSY
        InstrumentFrameCommitResult.RETIREMENT_PENDING -> InstrumentRasterOutcome.RETIREMENT_PENDING
        InstrumentFrameCommitResult.OUTPUT_RETIRED -> InstrumentRasterOutcome.REVOKED_CLEARED
        InstrumentFrameCommitResult.REJECTED_NO_COMMIT -> InstrumentRasterOutcome.PORT_REJECTED
    }

    private fun shouldCoalesce(nowNanos: Long, state: InstrumentSignalBundleState.Available): Boolean {
        val lastCommit = lastAvailableCommitNanos ?: return false
        val lastExpiry = lastAvailableExpiryNanos ?: return false
        val elapsed = nowNanos - lastCommit
        if (nowNanos < lastCommit || elapsed < 0L ||
            elapsed >= AVAILABLE_FRAME_INTERVAL_NANOS ||
            lastCommit > Long.MAX_VALUE - AVAILABLE_FRAME_INTERVAL_NANOS
        ) return false
        // The previously displayed sample must remain fresh until the next scheduled attempt.
        // If it might expire sooner, repaint the new sample immediately instead of suppressing it.
        val nextAttempt = lastCommit + AVAILABLE_FRAME_INTERVAL_NANOS
        return lastExpiry >= nextAttempt && state.expiryNanos()?.let { it >= nowNanos } == true
    }

    private fun InstrumentSignalBundleState.Available.expiryNanos(): Long? {
        val oldestSourceAge = sourceAgeMillisByRole.values.maxOrNull() ?: return null
        val remainingMillis = plan.spec.maximumReadAgeMillis - oldestSourceAge
        if (remainingMillis < 0L || readAtNanos < 0L) return null
        val remainingNanos = TimeUnit.MILLISECONDS.toNanos(remainingMillis)
        return if (readAtNanos <= Long.MAX_VALUE - remainingNanos) {
            readAtNanos + remainingNanos
        } else null
    }

    private fun clearAvailableThrottle() {
        lastAvailableCommitNanos = null
        lastAvailableExpiryNanos = null
    }

    private fun paintMarker() {
        fillRect(UltraPixelRect(0, 0, width, markerHeight()), WARNING_BACKGROUND)
        drawText(plan.safetyMarker, 8, 8, WHITE, markerScale())
    }

    private fun resolveFreshValue(
        state: InstrumentSignalBundleState.Available,
        nowNanos: Long,
    ): String? {
        if (state.readAtNanos < 0L || nowNanos < state.readAtNanos) return null
        val elapsed = nowNanos - state.readAtNanos
        val limit = TimeUnit.MILLISECONDS.toNanos(plan.spec.maximumReadAgeMillis)
        if (elapsed < 0L || elapsed > limit ||
            state.values.keys != plan.spec.shape.roles ||
            state.sourceAgeMillisByRole.keys != plan.spec.shape.roles ||
            state.sourceAgeMillisByRole.values.any { age ->
                age < 0L || age > plan.spec.maximumReadAgeMillis ||
                    TimeUnit.MILLISECONDS.toNanos(age) > limit - elapsed
            }
        ) return null
        val role = when (plan.visual) {
            InstrumentReferenceVisual.SPEED_KMH -> InstrumentSignalRole.SPEED_KMH
            InstrumentReferenceVisual.SPEED_MPH -> InstrumentSignalRole.SPEED_MPH
            InstrumentReferenceVisual.RPM -> InstrumentSignalRole.RPM_VALUE
            InstrumentReferenceVisual.INDICATOR_STATE -> InstrumentSignalRole.INDICATOR_STATE
        }
        if (plan.visual == InstrumentReferenceVisual.RPM &&
            (state.values[InstrumentSignalRole.RPM_STATE] as?
                InstrumentSignalReading.State)?.name != "running"
        ) return null
        val reading = state.values[role]
        if (plan.visual == InstrumentReferenceVisual.INDICATOR_STATE) {
            val name = (reading as? InstrumentSignalReading.State)?.name ?: return null
            return if (name.length in 1..24 && name.length + 6 <=
                (plan.slotPixels.widthPixels - 16) / 6 && name.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' ||
                    it == '_' || it == '-' || it == '.'
            }) "STATE ${name.uppercase(Locale.ROOT)}" else null
        }
        val numeric = reading as? InstrumentSignalReading.Numeric ?: return null
        val expectedMeaning = when (plan.visual) {
            InstrumentReferenceVisual.SPEED_KMH -> InstrumentNumericMeaning.KILOMETRES_PER_HOUR
            InstrumentReferenceVisual.SPEED_MPH -> InstrumentNumericMeaning.MILES_PER_HOUR
            InstrumentReferenceVisual.RPM -> InstrumentNumericMeaning.REVOLUTIONS_PER_MINUTE
            InstrumentReferenceVisual.INDICATOR_STATE -> return null
        }
        if (numeric.meaning != expectedMeaning) return null
        val number = when (val value = numeric.value) {
            is CafFormatValue.Floating -> value.value
            is CafFormatValue.Signed -> value.value.toDouble()
            is CafFormatValue.Unsigned -> value.value.toDouble()
            else -> return null
        }
        // These are bounded Debug reference text envelopes, NOT a comparison with the package's
        // native Circular Gauge range. The latter has no proved unit affinity to these roles.
        val referenceMaximum = when (plan.visual) {
            InstrumentReferenceVisual.SPEED_KMH -> 500.0
            InstrumentReferenceVisual.SPEED_MPH -> 310.7
            InstrumentReferenceVisual.RPM -> 20_000.0
            InstrumentReferenceVisual.INDICATOR_STATE -> return null
        }
        if (!number.isFinite() || number !in 0.0..referenceMaximum) return null
        val suffix = when (plan.visual) {
            InstrumentReferenceVisual.SPEED_KMH -> " KM/H"
            InstrumentReferenceVisual.SPEED_MPH -> " MPH"
            InstrumentReferenceVisual.RPM -> " RPM"
            else -> return null
        }
        val label = String.format(Locale.ROOT, "%.1f", number) + suffix
        return label.takeIf { it.length <= (plan.slotPixels.widthPixels - 16) / 6 }
    }

    private fun drawReferenceCard(value: String) {
        val frame = plan.slotPixels
        fillRect(frame, CARD_BACKGROUND)
        strokeRect(frame, CARD_BORDER)
        val labelY = maxOf(frame.yPixels + 8, markerHeight() + 4)
        drawText("REFERENCE", frame.xPixels + 8, labelY, CARD_BORDER)
        drawText(value, frame.xPixels + 8, labelY + 15, WHITE)
    }

    private fun markerScale(): Int = if (width >= 320) 2 else 1

    private fun markerHeight(): Int = 7 * markerScale() + 16

    private fun fillRect(rect: UltraPixelRect, color: Int) {
        val left = rect.xPixels.coerceIn(0, width)
        val top = rect.yPixels.coerceIn(0, height)
        val right = (rect.xPixels.toLong() + rect.widthPixels).coerceIn(0, width.toLong()).toInt()
        val bottom = (rect.yPixels.toLong() + rect.heightPixels).coerceIn(0, height.toLong()).toInt()
        for (y in top until bottom) {
            pixels.fill(color, y * width + left, y * width + right)
        }
    }

    private fun strokeRect(rect: UltraPixelRect, color: Int) {
        fillRect(UltraPixelRect(rect.xPixels, rect.yPixels, rect.widthPixels, 1), color)
        fillRect(UltraPixelRect(rect.xPixels, rect.yPixels + rect.heightPixels - 1,
            rect.widthPixels, 1), color)
        fillRect(UltraPixelRect(rect.xPixels, rect.yPixels, 1, rect.heightPixels), color)
        fillRect(UltraPixelRect(rect.xPixels + rect.widthPixels - 1, rect.yPixels,
            1, rect.heightPixels), color)
    }

    private fun drawText(text: String, x: Int, y: Int, color: Int, scale: Int = 1) {
        text.forEachIndexed { index, char ->
            val glyph = FONT[char.uppercaseChar()] ?: FONT.getValue(' ')
            glyph.forEachIndexed { row, bits ->
                for (column in 0..4) if (bits and (1 shl (4 - column)) != 0) {
                    val pixelX = x + (index * 6 + column) * scale
                    val pixelY = y + row * scale
                    fillRect(UltraPixelRect(pixelX, pixelY, scale, scale), color)
                }
            }
        }
    }

    private companion object {
        const val AVAILABLE_FRAME_INTERVAL_NANOS = 200_000_000L
        const val BACKGROUND = -0xeff0ee
        const val WARNING_BACKGROUND = -0x8d2e2e
        const val WARNING_TEXT = -0x1a66
        const val CARD_BACKGROUND = -0xd9d6d0
        const val CARD_BORDER = -0x68a7e
        const val WHITE = -0x1

        /** Bounded 5x7 reference font; unsupported text becomes a blank glyph, never a symbol. */
        val FONT = mapOf(
            ' ' to intArrayOf(0, 0, 0, 0, 0, 0, 0),
            '/' to intArrayOf(1, 1, 2, 4, 8, 16, 16),
            '-' to intArrayOf(0, 0, 0, 31, 0, 0, 0),
            '.' to intArrayOf(0, 0, 0, 0, 0, 12, 12),
            '_' to intArrayOf(0, 0, 0, 0, 0, 0, 31),
            '0' to intArrayOf(14, 17, 19, 21, 25, 17, 14),
            '1' to intArrayOf(4, 12, 4, 4, 4, 4, 14),
            '2' to intArrayOf(14, 17, 1, 2, 4, 8, 31),
            '3' to intArrayOf(30, 1, 1, 14, 1, 1, 30),
            '4' to intArrayOf(2, 6, 10, 18, 31, 2, 2),
            '5' to intArrayOf(31, 16, 30, 1, 1, 17, 14),
            '6' to intArrayOf(6, 8, 16, 30, 17, 17, 14),
            '7' to intArrayOf(31, 1, 2, 4, 8, 8, 8),
            '8' to intArrayOf(14, 17, 17, 14, 17, 17, 14),
            '9' to intArrayOf(14, 17, 17, 15, 1, 2, 12),
            'A' to intArrayOf(14, 17, 17, 31, 17, 17, 17),
            'B' to intArrayOf(30, 17, 17, 30, 17, 17, 30),
            'C' to intArrayOf(14, 17, 16, 16, 16, 17, 14),
            'D' to intArrayOf(30, 17, 17, 17, 17, 17, 30),
            'E' to intArrayOf(31, 16, 16, 30, 16, 16, 31),
            'F' to intArrayOf(31, 16, 16, 30, 16, 16, 16),
            'G' to intArrayOf(14, 17, 16, 23, 17, 17, 14),
            'H' to intArrayOf(17, 17, 17, 31, 17, 17, 17),
            'I' to intArrayOf(14, 4, 4, 4, 4, 4, 14),
            'J' to intArrayOf(7, 2, 2, 2, 18, 18, 12),
            'K' to intArrayOf(17, 18, 20, 24, 20, 18, 17),
            'L' to intArrayOf(16, 16, 16, 16, 16, 16, 31),
            'M' to intArrayOf(17, 27, 21, 21, 17, 17, 17),
            'N' to intArrayOf(17, 25, 21, 19, 17, 17, 17),
            'O' to intArrayOf(14, 17, 17, 17, 17, 17, 14),
            'P' to intArrayOf(30, 17, 17, 30, 16, 16, 16),
            'Q' to intArrayOf(14, 17, 17, 17, 21, 18, 13),
            'R' to intArrayOf(30, 17, 17, 30, 20, 18, 17),
            'S' to intArrayOf(15, 16, 16, 14, 1, 1, 30),
            'T' to intArrayOf(31, 4, 4, 4, 4, 4, 4),
            'U' to intArrayOf(17, 17, 17, 17, 17, 17, 14),
            'V' to intArrayOf(17, 17, 17, 17, 17, 10, 4),
            'W' to intArrayOf(17, 17, 17, 21, 21, 21, 10),
            'X' to intArrayOf(17, 17, 10, 4, 10, 17, 17),
            'Y' to intArrayOf(17, 17, 10, 4, 4, 4, 4),
            'Z' to intArrayOf(31, 1, 2, 4, 8, 16, 31),
        )
    }
}
