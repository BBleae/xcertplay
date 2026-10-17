package com.shilapi.xcertplay.airplay

/**
 * Atomically publishes the configuration and optional Ultra runtime for one AirPlay session
 * generation.
 *
 * A generation starts pending and therefore cannot be acquired. [resolve] is the sole publication
 * point: it installs one reference-immutable [ResolvedSnapshot] containing the exact
 * [AirPlayConfig], exact [AirPlayUltraRuntime] instance (when present), and exact generation token
 * in one critical section. A generation must be explicitly revoked before its successor can begin.
 *
 * This binding deliberately does not open transports, negotiate features, or own either object.
 * The caller that supplied a runtime remains responsible for retiring it after [revoke]. Exact
 * authorization provenance and runtime-liveness evidence are supplied by the authorized lifecycle;
 * this class captures its validator for admission but does not recreate that authority or deep-copy
 * the exact configuration.
 */
class AirPlaySessionRuntimeBinding {
    /** Opaque capability for one generation allocated by one exact binding. */
    class Generation internal constructor(
        internal val owner: Any,
        val value: Long,
    ) {
        override fun toString(): String = "AirPlaySessionGeneration(value=$value)"
    }

    /**
     * One reference-immutable, atomically published configuration/runtime pair.
     *
     * The references are intentionally not copied: downstream session construction must consume
     * the exact objects that were resolved together. Repeated successful acquisitions return this
     * exact snapshot instance. The generation's admission validator is held strongly and privately
     * by this snapshot so it cannot drift independently from the published pair.
     */
    class ResolvedSnapshot internal constructor(
        val generation: Generation,
        val config: AirPlayConfig,
        val ultraRuntime: AirPlayUltraRuntime?,
        private val admissionValidator: (ResolvedSnapshot) -> Boolean,
    ) {
        internal fun validateAdmission(): Boolean = admissionValidator(this)
    }

    private sealed interface State {
        val generation: Generation
    }

    private class Pending(
        override val generation: Generation,
    ) : State

    private class Resolved(
        val snapshot: ResolvedSnapshot,
    ) : State {
        override val generation: Generation
            get() = snapshot.generation
    }

    private class Revoked(
        override val generation: Generation,
    ) : State

    private val lock = Any()
    private val owner = Any()
    private var nextGeneration = 0L
    private var state: State? = null
    private var admissionThread: Thread? = null

    /**
     * Begins a new pending generation.
     *
     * An existing pending or resolved generation must be explicitly [revoke]d first. This keeps a
     * caller-owned runtime from becoming unreachable without a retirement decision.
     */
    fun beginGeneration(): Generation = synchronized(lock) {
        checkNotAdmissionReentryLocked()
        check(state == null || state is Revoked) {
            "Current AirPlay session generation must be revoked before replacement"
        }
        check(nextGeneration < Long.MAX_VALUE) { "AirPlay session generation exhausted" }
        val generation = Generation(owner, nextGeneration + 1L)
        nextGeneration = generation.value
        state = Pending(generation)
        generation
    }

    /**
     * Atomically resolves and publishes [config], [ultraRuntime], and [admissionValidator] for
     * [generation].
     *
     * Publication succeeds exactly once while [generation] is the current pending generation.
     * A stale, already-resolved, or revoked generation returns `null` without changing state. The
     * validator is invoked for every [withResolvedAdmission] under the binding lock; the authorized
     * lifecycle should use it to revalidate the exact generation's current liveness.
     */
    fun resolve(
        generation: Generation,
        config: AirPlayConfig,
        ultraRuntime: AirPlayUltraRuntime? = null,
        admissionValidator: (ResolvedSnapshot) -> Boolean = { true },
    ): ResolvedSnapshot? = synchronized(lock) {
        checkNotAdmissionReentryLocked()
        requireOwned(generation)
        val pending = state as? Pending ?: return@synchronized null
        if (pending.generation !== generation) return@synchronized null
        require((config.ultra == null) == (ultraRuntime == null)) {
            "AirPlay Ultra configuration and runtime must be supplied together"
        }

        ResolvedSnapshot(generation, config, ultraRuntime, admissionValidator).also { snapshot ->
            state = Resolved(snapshot)
        }
    }

    /**
     * Observes the exact published snapshot for [generation].
     *
     * Pending, stale, and revoked generations return `null`. When this races [revoke], a returned
     * snapshot linearizes before revocation; every acquisition after revocation returns `null`.
     * This observation does not run the admission validator and must not be used to construct,
     * publish, or start a session. Product admission must use [withResolvedAdmission].
     */
    fun acquire(generation: Generation): ResolvedSnapshot? = synchronized(lock) {
        checkNotAdmissionReentryLocked()
        requireOwned(generation)
        val resolved = state as? Resolved ?: return@synchronized null
        resolved.snapshot.takeIf { it.generation === generation }
    }

    /**
     * Executes one trusted session-admission [action] against the exact current resolved snapshot.
     *
     * Pending, stale, and revoked generations return `null` without invoking [action]. The exact
     * snapshot's admission validator and the complete action execute under the same binding lock.
     * A validator result of `false`, or an operational [Exception] from it, atomically revokes the
     * generation and returns `null` without running [action]. Any other validator failure is
     * propagated unchanged after the generation is revoked. This fail-closed revocation is
     * irreversible; later liveness cannot revive the old publication.
     *
     * The Boolean validator is a linearization probe, not a general external-authority use lease.
     * It is safe only when one integration owner exclusively controls the authorization result,
     * receipt, runtime, and every invalidation path. Every path capable of invalidating its proof or
     * closing [AirPlayUltraRuntime] must first call [revoke] and wait for this gate, then retire
     * already-published sessions, and only then invalidate the authority/runtime. An integration
     * that cannot prove that ownership and ordering must supply its own use permit spanning [action]
     * rather than relying on this probe. This binding alone provides no cross-authority guarantee.
     *
     * If admission linearizes first, a concurrent [revoke] waits until the validator and action
     * finish; the outer teardown barrier must then retire any session the action published before
     * it closes the root runtime. If revocation linearizes first, neither validator nor action runs.
     * An exception from [action] is propagated unchanged and does not alter binding state. Before
     * returning, the action must durably publish the exact cleanup owner for every admitted
     * resource. It must roll back or retain an exact cleanup owner for any partially published side
     * effects before throwing; this binding cannot do that on its behalf. A resolved generation may
     * admit more than one session, with multiplicity owned by the outer session registry.
     *
     * [action] is trusted lifecycle code. Same-thread re-entry into any binding operation fails
     * fast, while operations from other threads wait for the action to finish. The action must be
     * bounded. Lock order is binding before downstream session registries: teardown must revoke the
     * binding before taking downstream locks.
     */
    fun <T : Any> withResolvedAdmission(
        generation: Generation,
        action: (ResolvedSnapshot) -> T,
    ): T? = synchronized(lock) {
        checkNotAdmissionReentryLocked()
        requireOwned(generation)
        val resolved = state as? Resolved ?: return@synchronized null
        if (resolved.snapshot.generation !== generation) return@synchronized null
        admissionThread = Thread.currentThread()
        try {
            val admissionLive = try {
                resolved.snapshot.validateAdmission()
            } catch (failure: Exception) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                state = Revoked(generation)
                return@synchronized null
            } catch (failure: Throwable) {
                state = Revoked(generation)
                throw failure
            }
            if (!admissionLive) {
                state = Revoked(generation)
                return@synchronized null
            }
            action(resolved.snapshot)
        } finally {
            admissionThread = null
        }
    }

    /**
     * Revokes the current pending or resolved [generation].
     *
     * Stale and already-revoked generations return `false`. Revocation only seals admission; it
     * does not close caller-owned configuration or runtime resources.
     */
    fun revoke(generation: Generation): Boolean = synchronized(lock) {
        checkNotAdmissionReentryLocked()
        requireOwned(generation)
        val current = state ?: return@synchronized false
        if (current.generation !== generation || current is Revoked) return@synchronized false
        state = Revoked(generation)
        true
    }

    private fun checkNotAdmissionReentryLocked() {
        check(admissionThread !== Thread.currentThread()) {
            "AirPlay session admission action must not re-enter its runtime binding"
        }
    }

    private fun requireOwned(generation: Generation) {
        require(generation.owner === owner) {
            "AirPlay session generation belongs to another runtime binding"
        }
    }
}
