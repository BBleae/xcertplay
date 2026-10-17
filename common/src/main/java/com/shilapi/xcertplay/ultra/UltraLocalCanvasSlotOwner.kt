package com.shilapi.xcertplay.ultra

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.view.Surface
import com.shilapi.xcertplay.ultra.egl.UltraEglIngressKey
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneSlotAddress
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** The EGL coordinator owns this Surface. A Canvas producer may borrow it, never release it. */
internal class UltraLocalCanvasIngress(
    val key: UltraEglIngressKey.Local,
    val surface: Surface?,
    val lease: AndroidUltraCustomIngressLease?,
    private val retire: () -> Unit,
) : Closeable {
    override fun close() = retire()
}

internal fun interface UltraLocalCanvasIngressPort {
    fun open(contentId: UltraContentId, sizePixels: PixelSize): UltraLocalCanvasIngress
}

internal fun interface UltraLocalCanvasDrawPort {
    fun draw(
        surface: Surface?,
        sizePixels: PixelSize,
        painter: (Canvas, PixelSize) -> Unit,
    )

    /** Production overrides this so selection validation and Surface post share one owner lock. */
    fun drawWithPostFence(
        surface: Surface?,
        sizePixels: PixelSize,
        painter: (Canvas, PixelSize) -> Unit,
        finalPost: (clear: () -> Unit, post: () -> Unit) -> Unit,
    ) = draw(surface, sizePixels, painter)
}

/** Only the first successful Surface post on one freshly opened selected ingress can issue this. */
internal class UltraLocalCanvasPostReceipt internal constructor(
    val handle: UltraLocalCanvasSlotOwner.Handle,
    val selectionEpoch: Long,
    val postSequence: Long,
)

/** A short, per-handle producer freeze; it proves no second Canvas post was admitted. */
internal class UltraLocalCanvasCarryLease internal constructor(
    val receipt: UltraLocalCanvasPostReceipt,
    private val current: () -> Boolean,
    private val release: () -> Unit,
) : Closeable {
    fun isCurrent(): Boolean = current()
    override fun close() = release()
}

/**
 * One exact child owns all local Canvas input leases. The signed theme caller chooses the slot and
 * content ID; this class does not interpret UI Sync intent or assert OEM authorization. Its
 * admission callback must check the current signed selection, AA05 and exact physical batch. The
 * child must call [revoke] when that authority is retired, and [close] before coordinator.close().
 *
 * Draws are serialized on a dedicated producer thread. Neither a painter nor Canvas lock runs
 * under this owner's lock, a theme controller lock, or an authority lock. A boolean admission
 * check is not an atomic AA05 use barrier; revocation drains in-flight work before retirement.
 */
internal class UltraLocalCanvasSlotOwner internal constructor(
    private val ingressPort: UltraLocalCanvasIngressPort,
    private val drawPort: UltraLocalCanvasDrawPort,
    private val producer: ExecutorService,
    /** JVM race-test seam; product always uses the no-op default. */
    private val afterGuardBeforeReservation: () -> Unit = {},
) : Closeable {
    constructor(coordinator: AndroidUltraRendererSessionCoordinator) : this(
        UltraLocalCanvasIngressPort { contentId, sizePixels ->
            val lease = coordinator.openLocalIngress(contentId, sizePixels)
            val key = lease.key
            if (key !is UltraEglIngressKey.Local) {
                try { lease.close() } catch (_: Throwable) { /* coordinator retains uncertainty */ }
                throw IllegalStateException("Coordinator returned a non-local Canvas ingress")
            }
            UltraLocalCanvasIngress(key, lease.producerSurface, lease, lease::close)
        },
        AndroidCanvasDrawPort,
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "ultra-local-canvas-producer").apply { isDaemon = true }
        },
    )

    internal class Handle private constructor(
        val slot: CarPlayThemeSceneSlotAddress,
        val contentId: UltraContentId,
        val sizePixels: PixelSize,
        val generation: Long,
        private val ingress: UltraLocalCanvasIngress,
        private val admission: () -> Boolean,
        private val owner: UltraLocalCanvasSlotOwner,
    ) {
        /** Under the owner's lock. A clear/error post also consumes the only provable first post. */
        internal var successfulSurfacePosts = 0L
        internal var firstPostReceipt: UltraLocalCanvasPostReceipt? = null
        internal var pendingDraws = 0
        val key: UltraEglIngressKey.Local get() = ingress.key
        /** Product handles have a real coordinator lease; the nullable port exists for JVM tests. */
        val lease: AndroidUltraCustomIngressLease get() = checkNotNull(ingress.lease)
        val binding: UltraThemeLocalCanvasSlotBinding
            get() = UltraThemeLocalCanvasSlotBinding(slot, contentId, lease, sizePixels)
        internal fun admitted(): Boolean = admission()
        internal fun belongsTo(candidate: UltraLocalCanvasSlotOwner): Boolean = owner === candidate
        internal fun drawOn(
            port: UltraLocalCanvasDrawPort,
            painter: (Canvas, PixelSize) -> Unit,
            finalPost: (clear: () -> Unit, post: () -> Unit) -> Unit,
        ) = port.drawWithPostFence(ingress.surface, sizePixels, painter, finalPost)
        internal fun retire() = ingress.close()

        companion object {
            internal fun create(
                slot: CarPlayThemeSceneSlotAddress,
                contentId: UltraContentId,
                sizePixels: PixelSize,
                generation: Long,
                ingress: UltraLocalCanvasIngress,
                admission: () -> Boolean,
                owner: UltraLocalCanvasSlotOwner,
            ) = Handle(slot, contentId, sizePixels, generation, ingress, admission, owner)
        }
    }

    private data class SlotContent(
        val slot: CarPlayThemeSceneSlotAddress,
        val contentId: UltraContentId,
    )

    private val lock = Any()
    private val operations = Any()
    private val owned = LinkedHashMap<SlotContent, Handle>()
    private val tombstones = LinkedHashSet<Handle>()
    private val frozenHandles = LinkedHashMap<Handle, Any>()
    private var generation = 0L
    private var postSequence = 0L
    /** Changes on every selection handoff, including reuse of a previously opened handle. */
    private var selectionEpoch = 0L
    private var revoked = false
    private var closed = false
    private var producerThread: Thread? = null
    /** Includes the currently running draw; retirement tasks do not consume this quota. */
    private var pendingDraws = 0
    internal val isRevoked: Boolean get() = synchronized(lock) { revoked }

    /** Opens only after an external exact-authorization decision; duplicate selection reuses it. */
    fun ensure(
        slot: CarPlayThemeSceneSlotAddress,
        contentId: UltraContentId,
        sizePixels: PixelSize,
        admission: () -> Boolean,
    ): Handle = synchronized(operations) operation@{
        synchronized(lock) {
            check(!revoked && !closed) { "Local Canvas child has been revoked" }
            check(selectionEpoch < Long.MAX_VALUE) { "Local Canvas selection epoch exhausted" }
            selectionEpoch++
        }
        check(admission()) { "Local Canvas slot has no current signed physical admission" }
        val identity = SlotContent(slot, contentId)
        val existing = synchronized(lock) lookup@{
            check(!revoked && !closed) { "Local Canvas child has been revoked" }
            owned[identity]?.let { existing ->
                require(existing.sizePixels == sizePixels) {
                    "Local Canvas slot cannot change pixel size without a new content ID"
                }
                return@lookup existing
            }
            require(owned.values.none { it.contentId == contentId }) {
                "A local Canvas content ID cannot name a different signed slot"
            }
            require(tombstones.none { it.contentId == contentId }) {
                "A retired local Canvas content ID still has an uncertain lease"
            }
            null
        }
        if (existing != null) {
            check(admission() && existing.admitted()) {
                "Existing local Canvas slot is no longer admitted"
            }
            return@operation existing
        }

        val ingress = ingressPort.open(contentId, sizePixels)
        val expected = UltraEglIngressKey.Local(contentId)
        val admissionResult = runCatching { ingress.key == expected && admission() }
        val accepted = admissionResult.getOrDefault(false)
        val handle = synchronized(lock) {
            val created = Handle.create(slot, contentId, sizePixels, ++generation,
                ingress, admission, this)
            if (accepted && !revoked && !closed) owned[identity] = created
            else tombstones += created
            created
        }
        if (accepted && synchronized(lock) { owned[identity] === handle }) return@operation handle
        val failure = IllegalStateException(
            "Local Canvas ingress opened after selection or child authority changed",
        )
        admissionResult.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { retireTombstone(handle) }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }

    /** Schedules a complete raw-pixel frame; caller never receives the borrowed Surface. */
    fun draw(
        handle: Handle,
        painter: (Canvas, PixelSize) -> Unit,
    ): CompletableFuture<Unit> = drawWithPostReceipt(handle, painter).thenApply { Unit }

    /** A fake draw port that does not call the final Surface post callback yields no evidence. */
    internal fun drawWithPostReceipt(
        handle: Handle,
        painter: (Canvas, PixelSize) -> Unit,
    ): CompletableFuture<UltraLocalCanvasPostReceipt?> {
        val result = CompletableFuture<UltraLocalCanvasPostReceipt?>()
        var enqueuedSelectionEpoch = -1L
        val rejection = synchronized(lock) {
            when {
                !handle.belongsTo(this) || revoked || closed ||
                    owned[SlotContent(handle.slot, handle.contentId)] !== handle ||
                    handle in frozenHandles ->
                    IllegalStateException("Local Canvas handle is revoked")
                pendingDraws >= MAXIMUM_PENDING_DRAW_TASKS ->
                    RejectedExecutionException("Local Canvas pending draw capacity exhausted")
                else -> {
                    pendingDraws++
                    handle.pendingDraws++
                    enqueuedSelectionEpoch = selectionEpoch
                    null
                }
            }
        }
        if (rejection != null) {
            result.completeExceptionally(rejection)
            return result
        }
        try {
            producer.execute {
                synchronized(lock) { producerThread = Thread.currentThread() }
                var failure: Throwable? = null
                var completedReceipt: UltraLocalCanvasPostReceipt? = null
                try {
                    fun currentSelectionLocked(): Boolean =
                        !revoked && !closed && selectionEpoch == enqueuedSelectionEpoch &&
                            owned[SlotContent(handle.slot, handle.contentId)] === handle

                    fun requireCurrentSelection() {
                        val current = synchronized(lock) { currentSelectionLocked() }
                        check(current && handle.admitted()) {
                            "Local Canvas selection or physical admission changed after enqueue"
                        }
                    }
                    requireCurrentSelection()
                    var staleAtPost = false
                    var postReceipt: UltraLocalCanvasPostReceipt? = null
                    handle.drawOn(drawPort, { canvas, pixels ->
                        requireCurrentSelection()
                        painter(canvas, pixels)
                        // A selection changed during the custom painter must clear rather than
                        // publish its partial pixels. AndroidCanvasDrawPort does that on throw.
                        requireCurrentSelection()
                    }) { clear, post ->
                        val admitted = runCatching { handle.admitted() }.getOrDefault(false)
                        synchronized(lock) {
                            fun postAndRecord(): Pair<Boolean, Long> {
                                check(postSequence < Long.MAX_VALUE &&
                                    handle.successfulSurfacePosts < Long.MAX_VALUE
                                ) { "Local Canvas Surface post sequence exhausted" }
                                post()
                                val first = handle.successfulSurfacePosts == 0L
                                handle.successfulSurfacePosts++
                                return first to ++postSequence
                            }
                            if (!currentSelectionLocked() || !admitted) {
                                staleAtPost = true
                                try {
                                    clear()
                                } finally {
                                    postAndRecord()
                                }
                            } else {
                                val (first, sequence) = postAndRecord()
                                postReceipt = if (first) UltraLocalCanvasPostReceipt(
                                    handle, enqueuedSelectionEpoch, sequence,
                                ) else null
                                if (first) handle.firstPostReceipt = postReceipt
                            }
                        }
                    }
                    check(!staleAtPost) {
                        "Local Canvas selection changed before Surface post"
                    }
                    requireCurrentSelection()
                    completedReceipt = postReceipt
                } catch (problem: Throwable) {
                    failure = problem
                } finally {
                    synchronized(lock) {
                        pendingDraws--
                        handle.pendingDraws--
                    }
                }
                if (failure != null) result.completeExceptionally(failure)
                else result.complete(completedReceipt)
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                pendingDraws--
                handle.pendingDraws--
            }
            result.completeExceptionally(failure)
        }
        return result
    }

    internal fun acceptsPostReceipt(receipt: UltraLocalCanvasPostReceipt): Boolean =
        synchronized(lock) {
            !revoked && !closed && receipt.handle.belongsTo(this) &&
                receipt.selectionEpoch == selectionEpoch &&
                owned[SlotContent(receipt.handle.slot, receipt.handle.contentId)] === receipt.handle &&
                receipt.handle.successfulSurfacePosts == 1L &&
                receipt.handle.firstPostReceipt === receipt &&
                receipt.postSequence in 1L..postSequence
        } && receipt.handle.admitted()

    internal fun isFresh(handle: Handle): Boolean = synchronized(lock) {
        !revoked && !closed && handle.belongsTo(this) &&
            owned[SlotContent(handle.slot, handle.contentId)] === handle &&
            handle.successfulSurfacePosts == 0L && handle.firstPostReceipt == null &&
            handle !in frozenHandles && handle.pendingDraws == 0
    } && handle.admitted()

    /** Read-only preflight before a new layout changes the signed slot selection. */
    internal fun canCarryCandidate(
        handle: Handle,
        receipt: UltraLocalCanvasPostReceipt?,
    ): Boolean = synchronized(lock) {
        !revoked && !closed && receipt != null && handle.belongsTo(this) &&
            owned[SlotContent(handle.slot, handle.contentId)] === handle &&
            handle.firstPostReceipt === receipt && handle.successfulSurfacePosts == 1L &&
            handle.pendingDraws == 0 && handle !in frozenHandles
    } && handle.admitted()

    /** A carried texture cannot be reposted while the next exact scene is being proven. */
    internal fun acquireCarryLease(
        handle: Handle,
        receipt: UltraLocalCanvasPostReceipt,
    ): UltraLocalCanvasCarryLease? {
        val token = Any()
        val epoch = synchronized(lock) {
            if (revoked || closed || !handle.belongsTo(this) || handle in frozenHandles ||
                owned[SlotContent(handle.slot, handle.contentId)] !== handle ||
                handle.firstPostReceipt !== receipt || handle.successfulSurfacePosts != 1L ||
                handle.pendingDraws != 0
            ) null else selectionEpoch.also { frozenHandles[handle] = token }
        } ?: return null
        val lease = UltraLocalCanvasCarryLease(receipt,
            current = {
                synchronized(lock) {
                    !revoked && !closed && selectionEpoch == epoch &&
                        frozenHandles[handle] === token &&
                        owned[SlotContent(handle.slot, handle.contentId)] === handle &&
                        handle.firstPostReceipt === receipt &&
                        handle.successfulSurfacePosts == 1L && handle.pendingDraws == 0
                } && handle.admitted()
            },
            release = { synchronized(lock) {
                if (frozenHandles[handle] === token) frozenHandles.remove(handle)
            } },
        )
        if (!lease.isCurrent()) {
            lease.close()
            return null
        }
        return lease
    }

    /** Distinctive but non-vehicle-safe diagnostic pixels for a signed Debug bench slot. */
    fun drawBenchFrame(handle: Handle): CompletableFuture<Unit> =
        draw(handle, ::paintBenchFrame)

    internal fun drawBenchFrameWithPostReceipt(
        handle: Handle,
    ): CompletableFuture<UltraLocalCanvasPostReceipt?> =
        drawWithPostReceipt(handle, ::paintBenchFrame)

    private fun paintBenchFrame(canvas: Canvas, size: PixelSize) {
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 35, 48) }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(86, 215, 207)
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, minOf(size.widthPixels, size.heightPixels) / 120f)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = maxOf(14f, minOf(size.widthPixels, size.heightPixels) / 11f)
        }
        canvas.drawRect(0f, 0f, size.widthPixels.toFloat(), size.heightPixels.toFloat(),
            background)
        val inset = minOf(size.widthPixels, size.heightPixels) / 12f
        canvas.drawRect(inset, inset, size.widthPixels - inset, size.heightPixels - inset,
            accent)
        canvas.drawText("LOCAL CANVAS", inset * 1.5f,
            (size.heightPixels / 2f).coerceAtLeast(text.textSize), text)
    }

    /**
     * Never retires a previously submitted source until this exact replacement ticket reached
     * PRESENTED. A failed/coalesced/expired ticket leaves all leases owned for retry or close.
     */
    fun retireExceptAfterPresented(
        retained: Set<Handle>,
        ticket: UltraEglRenderTicket,
        guard: () -> Boolean,
    ): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        if (retained.any { !it.belongsTo(this) }) {
            result.completeExceptionally(IllegalArgumentException("Foreign local Canvas handle"))
            return result
        }
        // A handle admitted after this replacement scene was queued cannot be inferred to be
        // absent from a later scene. Only the leases already owned at this exact handoff may be
        // considered obsolete when the ticket eventually completes.
        val (handoffGeneration, handoffSelectionEpoch) = synchronized(lock) {
            generation to selectionEpoch
        }
        try {
            producer.execute {
                synchronized(lock) { producerThread = Thread.currentThread() }
                try {
                    val presented = ticket.await().let {
                        it.revision == ticket.revision &&
                            it.disposition == UltraEglRenderDisposition.PRESENTED
                    }
                    if (!presented || !guard()) {
                        result.complete(false)
                        return@execute
                    }
                    afterGuardBeforeReservation()
                    // Do not hold the operations lock while guard acquires controller/AA05
                    // authority. Once it returns, an ensure of even the SAME A handle bumps the
                    // epoch. Under operations no ensure can slip between this check and native
                    // retirement, so A→B→A cannot retire A after it was reselected.
                    val obsolete = synchronized(operations) {
                        synchronized(lock) {
                            if (revoked || closed ||
                                selectionEpoch != handoffSelectionEpoch
                            ) null else owned.filterValues {
                                it.generation <= handoffGeneration && it !in retained
                            }.toList().also { entries ->
                                entries.forEach { (identity, handle) ->
                                    owned.remove(identity)
                                    tombstones += handle
                                }
                            }.map { it.second }
                        }?.also { handles -> handles.forEach(::retireTombstone) }
                    }
                    result.complete(obsolete != null)
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
        return result
    }

    /** Stops new work and drains the producer; retained leases remain owned for retryable close. */
    fun revoke() {
        synchronized(lock) {
            if (closed) return
            revoked = true
            frozenHandles.clear()
            producer.shutdown()
        }
        check(Thread.currentThread() !== synchronized(lock) { producerThread }) {
            "Local Canvas owner cannot drain itself from a painter callback; retry close externally"
        }
        check(producer.awaitTermination(PRODUCER_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            "Local Canvas producer did not drain; retry child close before EGL retirement"
        }
    }

    override fun close() {
        revoke()
        synchronized(operations) {
            val retiring = synchronized(lock) {
                owned.values.forEach(tombstones::add)
                owned.clear()
                tombstones.toList()
            }
            var failure: Throwable? = null
            retiring.forEach { handle ->
                runCatching { retireTombstone(handle) }.exceptionOrNull()?.let { problem ->
                    if (failure == null) failure = problem else failure?.addSuppressed(problem)
                }
            }
            failure?.let { throw it }
            synchronized(lock) { closed = true }
        }
    }

    private fun retireTombstone(handle: Handle) {
        synchronized(lock) { check(handle in tombstones) }
        handle.retire()
        synchronized(lock) { tombstones.remove(handle) }
    }

    companion object {
        internal const val MAXIMUM_PENDING_DRAW_TASKS = 4
        private const val PRODUCER_DRAIN_TIMEOUT_MILLIS = 5_000L
    }
}

private object AndroidCanvasDrawPort : UltraLocalCanvasDrawPort {
    override fun draw(
        surface: Surface?,
        sizePixels: PixelSize,
        painter: (Canvas, PixelSize) -> Unit,
    ) = drawWithPostFence(surface, sizePixels, painter) { _, post -> post() }

    override fun drawWithPostFence(
        surface: Surface?,
        sizePixels: PixelSize,
        painter: (Canvas, PixelSize) -> Unit,
        finalPost: (clear: () -> Unit, post: () -> Unit) -> Unit,
    ) {
        val borrowed = checkNotNull(surface) { "Product local Canvas ingress has no Surface" }
        val canvas = borrowed.lockCanvas(null)
        var failure: Throwable? = null
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val restore = canvas.save()
            try {
                canvas.clipRect(0, 0, sizePixels.widthPixels, sizePixels.heightPixels)
                painter(canvas, sizePixels)
            } finally {
                canvas.restoreToCount(restore)
            }
        } catch (problem: Throwable) {
            failure = problem
            // Any failed draw operation, including a damaged Canvas save stack, must not post
            // partially rendered custom pixels. Preserve the original failure for the owner.
            runCatching { canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) }
                .exceptionOrNull()?.let(problem::addSuppressed)
        } finally {
            // The owner rechecks its selection epoch while holding its own lock across this
            // post. A later ensure cannot ABA-reselect an old handle during the final handoff.
            try {
                finalPost(
                    { canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) },
                    { borrowed.unlockCanvasAndPost(canvas) },
                )
            } catch (postFailure: Throwable) {
                if (failure == null) failure = postFailure
                else failure?.addSuppressed(postFailure)
            }
        }
        failure?.let { throw it }
    }
}
