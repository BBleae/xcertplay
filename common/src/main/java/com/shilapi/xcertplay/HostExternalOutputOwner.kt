package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.ultra.DisplayDestination
import java.io.Closeable
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A Presentation readiness notification is only a hint; the registry and exact sink decide. */
internal interface HostExternalOutputPresentation : Closeable {
    fun subscribeReady(onReady: (DisplayDestination) -> Unit): Closeable
}

internal fun interface HostExternalOutputScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): Closeable
}

private object ProcessHostExternalOutputExecution {
    /** Set before a closing owner can still be observed in the physical registry. */
    val closingOwners = AtomicInteger(0)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "host-external-output").apply { isDaemon = true }
    }

    val worker: Executor = executor
    private val emergency = Executors.newSingleThreadExecutor { task ->
        Thread(task, "host-external-output-emergency").apply { isDaemon = true }
    }

    fun dispatchEmergency(action: () -> Unit) {
        try {
            emergency.execute(action)
        } catch (_: Throwable) {
            // Never execute a dependent CompletableFuture callback in a registry/UI stack.
            Thread(action, "host-external-output-emergency-fallback")
                .apply { isDaemon = true }.start()
        }
    }

    val scheduler = HostExternalOutputScheduler { delayMillis, action ->
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        Closeable { future.cancel(false) }
    }
}

/**
 * Retains exact failed owners (including their sink route) until process teardown can retry.
 * All closes occur on a worker, never in a Presentation/registry callback or on the UI thread.
 */
internal class HostExternalOutputRetirementQueue(
    private val scheduler: HostExternalOutputScheduler,
    private val retryDelayMillis: Long = 250L,
) {
    private val lock = Any()
    private val pending = IdentityHashMap<Closeable, CompletableFuture<Unit>>()
    private val pendingOrder = ArrayList<Closeable>()
    private var scheduled = false
    private var running = false

    fun retire(resource: Closeable): CompletableFuture<Unit> {
        var shouldSchedule = false
        val completion = synchronized(lock) {
            pending[resource] ?: CompletableFuture<Unit>().also {
                pending[resource] = it
                pendingOrder += resource
                if (!scheduled) {
                    scheduled = true
                    shouldSchedule = true
                }
            }
        }
        if (shouldSchedule) schedule(0L)
        return completion
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** Also usable by process teardown when a previous timer could not be scheduled. */
    fun retryPendingNow() {
        val attempts = synchronized(lock) {
            if (running) return
            running = true
            pendingOrder.toList()
        }
        try {
            attempts.forEach { resource ->
                if (runCatching { resource.close() }.isSuccess) {
                    val completion = synchronized(lock) {
                        pendingOrder.removeAll { it === resource }
                        pending.remove(resource)
                    }
                    completion?.complete(Unit)
                }
            }
        } finally {
            val retry = synchronized(lock) {
                running = false
                scheduled = false
                if (pending.isEmpty()) false else {
                    scheduled = true
                    true
                }
            }
            if (retry) schedule(retryDelayMillis)
        }
    }

    private fun schedule(delayMillis: Long) {
        // Scheduling failure leaves the exact resource in [pending] for explicit teardown retry.
        if (runCatching { scheduler.schedule(delayMillis, ::retryPendingNow) }.isFailure) {
            synchronized(lock) { scheduled = false }
        }
    }
}

private val processHostExternalOutputRetirementQueue =
    HostExternalOutputRetirementQueue(ProcessHostExternalOutputExecution.scheduler)

/**
 * Owns one exact sink's Presentation path across an asynchronous pre-Controller Host bootstrap.
 * It never captures an Activity or View. A result does not freeze `/info`: the caller must
 * recheck [isCurrent] and its [HostUltraTwoPhaseConfig.Ticket] before finalizing the Controller.
 */
internal class HostExternalOutputOwner internal constructor(
    private val sink: AndroidMediaSink,
    private val registry: HostPhysicalDisplaySurfaceRegistry,
    val routeFactory: AndroidMediaSinkExternalClusterRouteFactory,
    private val presentation: HostExternalOutputPresentation,
    private val scheduler: HostExternalOutputScheduler = ProcessHostExternalOutputExecution.scheduler,
    private val worker: Executor = ProcessHostExternalOutputExecution.worker,
    private val retirementQueue: HostExternalOutputRetirementQueue =
        processHostExternalOutputRetirementQueue,
) : Closeable {
    internal sealed interface Outcome {
        class Ready(val target: HostPhysicalTargetInspection) : Outcome
        class Unavailable(val reason: Reason) : Outcome
    }

    internal enum class Reason {
        TIMED_OUT,
        WITHDRAWN,
        WRONG_SINK_ROUTE,
        CANCELLED,
        SUBSCRIPTION_FAILED,
    }

    private class Pending(
        val staged: HostUltraTwoPhaseConfig,
        val ticket: HostUltraTwoPhaseConfig.Ticket,
        val completion: CompletableFuture<Outcome>,
    ) {
        var subscription: Closeable? = null
        var deadline: Closeable? = null
    }

    private inner class ExactOwnerRetirement : Closeable {
        override fun close() = presentation.close()
    }

    private val lock = Any()
    private val ownerRetirement = ExactOwnerRetirement()
    private val retirementCompletion = CompletableFuture<Unit>()
    private var pending: Pending? = null
    private var readyTarget: HostPhysicalTargetInspection? = null
    private var awaited = false
    private var closing = false
    private var setupInFlight = false
    private var ownerRetirementEnqueued = false

    init {
        require(routeFactory.belongsTo(sink, registry)) {
            "External Presentation route must belong to this exact media sink and registry"
        }
    }

    /**
     * Returns immediately. The one-shot listener and deadline are removed on every terminal
     * result; an unavailable result also retires the whole external output generation.
     */
    fun awaitReady(
        staged: HostUltraTwoPhaseConfig,
        ticket: HostUltraTwoPhaseConfig.Ticket,
        timeoutMillis: Long = 2_500L,
    ): CompletableFuture<Outcome> {
        require(timeoutMillis in 1L..10_000L) { "External readiness must have a bounded deadline" }
        val wait = synchronized(lock) {
            check(!awaited) { "An external output owner supports only one Host construction" }
            check(!closing) { "The external output owner is already closing" }
            awaited = true
            setupInFlight = true
            Pending(staged, ticket, CompletableFuture()).also { pending = it }
        }
        try {
            if (isStale(wait)) {
                finish(wait, Outcome.Unavailable(Reason.CANCELLED), retireOutput = true)
                return wait.completion
            }
            val deadline = scheduler.schedule(timeoutMillis) {
                dispatch { onTimedOut(wait) }
            }
            install(wait, deadline, isDeadline = true)
            if (!isPending(wait)) return wait.completion
            val subscription = presentation.subscribeReady { destination ->
                dispatch { onReady(wait, destination) }
            }
            install(wait, subscription, isDeadline = false)
        } catch (_: Throwable) {
            finish(wait, Outcome.Unavailable(Reason.SUBSCRIPTION_FAILED), retireOutput = true)
        } finally {
            synchronized(lock) { setupInFlight = false }
            queueOwnerRetirementIfReady()
        }
        return wait.completion
    }

    /** Exact sink, registry version, connector topology and route must all still agree. */
    fun isCurrent(target: HostPhysicalTargetInspection): Boolean {
        val sameReadyTarget = synchronized(lock) {
            val ready = readyTarget
            !closing && ready != null && ready.version == target.version &&
                ready.surface === target.surface &&
                ready.externalTopologyGeneration == target.externalTopologyGeneration
        }
        val topology = target.externalTopologyGeneration ?: return false
        return sameReadyTarget && target.version.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL &&
            runCatching { registry.isUniqueLiveExternalPhysicalTarget(
                target.version,
                target.surface,
                topology,
            ) && routeFactory.leaseForFrozenPhysicalTarget(target) != null }.getOrDefault(false)
    }

    /** A successor must not inspect external registry state until this exact owner retires. */
    fun retirementReceipt(): CompletableFuture<Unit> = retirementCompletion

    /** For success-only resource callbacks such as AndroidMediaSink.onClosed. */
    fun closeAndRequireRetired() {
        close()
        check(retirementCompletion.isDone) {
            "Exact external Presentation retirement is still in flight; retry close"
        }
    }

    private fun onReady(wait: Pending, destination: DisplayDestination) {
        if (!isPending(wait)) return
        if (isStale(wait)) {
            finish(wait, Outcome.Unavailable(Reason.CANCELLED), retireOutput = true)
            return
        }
        val target = registry.inspectUniquePhysicalExternalClusterTarget()
        val outcome = when {
            target == null || target.version.destination != destination ->
                Outcome.Unavailable(Reason.WITHDRAWN)
            target.externalTopologyGeneration == null ||
                !registry.isUniqueLiveExternalPhysicalTarget(
                    target.version, target.surface, target.externalTopologyGeneration,
                ) -> Outcome.Unavailable(Reason.WITHDRAWN)
            routeFactory.leaseForFrozenPhysicalTarget(target) == null ->
                Outcome.Unavailable(Reason.WRONG_SINK_ROUTE)
            isStale(wait) -> Outcome.Unavailable(Reason.CANCELLED)
            else -> Outcome.Ready(target)
        }
        finish(wait, outcome, retireOutput = outcome is Outcome.Unavailable)
    }

    private fun onTimedOut(wait: Pending) {
        val reason = if (isStale(wait)) Reason.CANCELLED else Reason.TIMED_OUT
        finish(wait, Outcome.Unavailable(reason), retireOutput = true)
    }

    private fun isStale(wait: Pending): Boolean =
        !wait.staged.isCurrent(wait.ticket, wait.ticket.constructionGeneration)

    private fun isPending(wait: Pending): Boolean = synchronized(lock) {
        !closing && pending === wait
    }

    private fun install(wait: Pending, handle: Closeable, isDeadline: Boolean) {
        val retain = synchronized(lock) {
            if (closing || pending !== wait) false else {
                if (isDeadline) wait.deadline = handle else wait.subscription = handle
                true
            }
        }
        if (!retain) retirementQueue.retire(handle)
    }

    private fun finish(wait: Pending, outcome: Outcome, retireOutput: Boolean) {
        val handles = synchronized(lock) {
            if (pending !== wait) return
            pending = null
            readyTarget = (outcome as? Outcome.Ready)?.target
            listOfNotNull(wait.subscription, wait.deadline)
        }
        handles.forEach(retirementQueue::retire)
        if (retireOutput) close()
        // Completing on the worker avoids running a caller's dependent action in a UI callback.
        dispatch { wait.completion.complete(outcome) }
    }

    private fun dispatch(action: () -> Unit) {
        try {
            worker.execute(action)
        } catch (_: Throwable) {
            ProcessHostExternalOutputExecution.dispatchEmergency(action)
        }
    }

    /**
     * Cancellation invalidates the wait before callbacks or native teardown can run. This only
     * requests asynchronous exact retirement; a sink `onClosed` callback must require
     * [retirementReceipt] completion before reporting its own close as successful.
     */
    override fun close() {
        val abandoned = synchronized(lock) {
            if (closing) return
            closing = true
            ProcessHostExternalOutputExecution.closingOwners.incrementAndGet()
            readyTarget = null
            pending.also { pending = null }
        }
        abandoned?.let { wait ->
            listOfNotNull(wait.subscription, wait.deadline).forEach(retirementQueue::retire)
            dispatch { wait.completion.complete(Outcome.Unavailable(Reason.CANCELLED)) }
        }
        queueOwnerRetirementIfReady()
    }

    private fun queueOwnerRetirementIfReady() {
        val enqueue = synchronized(lock) {
            if (!closing || setupInFlight || ownerRetirementEnqueued) false else {
                ownerRetirementEnqueued = true
                true
            }
        }
        if (!enqueue) return
        retirementQueue.retire(ownerRetirement).whenComplete { _, failure ->
            if (failure == null) {
                ProcessHostExternalOutputExecution.closingOwners.decrementAndGet()
                retirementCompletion.complete(Unit)
            }
        }
    }

    companion object {
        /** Conservative process-level gate for another Host generation using this registry. */
        fun hasPendingProcessRetirement(): Boolean =
            ProcessHostExternalOutputExecution.closingOwners.get() != 0 ||
                processHostExternalOutputRetirementQueue.pendingCount() != 0

        /** Product wiring is fail-closed until an independent physical connector provider exists. */
        fun open(
            context: Context,
            sink: AndroidMediaSink,
            registry: HostPhysicalDisplaySurfaceRegistry,
            trustProvider: ExternalPhysicalDisplayTrustProvider = ExternalPhysicalDisplayTrustProvider.DENY_ALL,
        ): HostExternalOutputOwner {
            val routeFactory = AndroidMediaSinkExternalClusterRouteFactory(sink, registry)
            val host = ExternalClusterPresentationHost(
                context.applicationContext,
                trustProvider = trustProvider,
                registry = registry,
                routeFactory = routeFactory,
            )
            return HostExternalOutputOwner(
                sink, registry, routeFactory,
                object : HostExternalOutputPresentation {
                    override fun subscribeReady(onReady: (DisplayDestination) -> Unit): Closeable =
                        host.subscribeReady(onReady)

                    override fun close() = host.close()
                },
            )
        }
    }
}
