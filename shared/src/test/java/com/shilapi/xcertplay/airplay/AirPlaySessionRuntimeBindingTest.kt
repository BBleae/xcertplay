package com.shilapi.xcertplay.airplay

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlaySessionRuntimeBindingTest {
    @Test
    fun pendingGenerationCannotBeAcquired() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()

        assertNull(binding.acquire(generation))
    }

    @Test
    fun resolvePublishesOneExactSnapshotAndExactObjectIdentities() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val runtime = AirPlayUltraRuntime()
        try {
            val config = config("resolved").copy(ultra = AirPlayUltraConfiguration())
            val snapshot = binding.resolve(generation, config, runtime)!!

            assertSame(generation, snapshot.generation)
            assertSame(config, snapshot.config)
            assertSame(runtime, snapshot.ultraRuntime)
            assertSame(snapshot, binding.acquire(generation))
            assertSame(snapshot, binding.acquire(generation))

            assertNull(binding.resolve(generation, config("replacement"), null))
            assertSame(snapshot, binding.acquire(generation))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun ordinaryGenerationPublishesAnExactNullRuntime() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val config = config("ordinary")

        val snapshot = binding.resolve(generation, config, ultraRuntime = null)!!

        assertSame(generation, snapshot.generation)
        assertSame(config, snapshot.config)
        assertNull(snapshot.ultraRuntime)
        assertSame(snapshot, binding.acquire(generation))
    }

    @Test
    fun beginningAnotherGenerationMakesPreviousGenerationStale() {
        val binding = AirPlaySessionRuntimeBinding()
        val stale = binding.beginGeneration()
        assertTrue(binding.revoke(stale))
        val current = binding.beginGeneration()

        assertNull(binding.resolve(stale, config("stale")))
        assertNull(binding.acquire(stale))
        assertFalse(binding.revoke(stale))
        assertNull(binding.acquire(current))

        val published = binding.resolve(current, config("current"))!!
        assertSame(published, binding.acquire(current))
    }

    @Test
    fun nextGenerationRequiresExplicitRevocationOfPendingOrResolvedState() {
        val binding = AirPlaySessionRuntimeBinding()
        val pending = binding.beginGeneration()

        assertThrows(IllegalStateException::class.java) { binding.beginGeneration() }
        assertTrue(binding.revoke(pending))

        val resolved = binding.beginGeneration()
        val resolvedSnapshot = binding.resolve(resolved, config("resolved"))!!
        assertThrows(IllegalStateException::class.java) { binding.beginGeneration() }
        assertSame(resolvedSnapshot, binding.acquire(resolved))
        assertTrue(binding.revoke(resolved))

        val successor = binding.beginGeneration()
        assertNull(binding.acquire(resolved))
        assertFalse(binding.revoke(resolved))
        assertNull(binding.acquire(successor))
    }

    @Test
    fun revokeSealsPendingAndResolvedGenerations() {
        val binding = AirPlaySessionRuntimeBinding()
        val pending = binding.beginGeneration()

        assertTrue(binding.revoke(pending))
        assertFalse(binding.revoke(pending))
        assertNull(binding.resolve(pending, config("late-pending")))
        assertNull(binding.acquire(pending))

        val resolved = binding.beginGeneration()
        binding.resolve(resolved, config("resolved"))!!
        assertTrue(binding.revoke(resolved))
        assertFalse(binding.revoke(resolved))
        assertNull(binding.acquire(resolved))
        assertNull(binding.resolve(resolved, config("late-resolved")))
    }

    @Test
    fun generationFromAnotherBindingIsRejectedEvenWhenNumbersMatch() {
        val first = AirPlaySessionRuntimeBinding()
        val second = AirPlaySessionRuntimeBinding()
        val foreign = first.beginGeneration()
        val local = second.beginGeneration()

        assertThrows(IllegalArgumentException::class.java) {
            second.resolve(foreign, config("foreign"))
        }
        assertThrows(IllegalArgumentException::class.java) { second.acquire(foreign) }
        assertThrows(IllegalArgumentException::class.java) { second.revoke(foreign) }

        val snapshot = second.resolve(local, config("local"))!!
        assertSame(snapshot, second.acquire(local))
    }

    @Test
    fun ultraConfigurationAndRuntimeMustBeSuppliedTogether() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val ordinary = config("ordinary")
        val ultra = ordinary.copy(ultra = AirPlayUltraConfiguration())
        val runtime = AirPlayUltraRuntime()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                binding.resolve(generation, ultra, ultraRuntime = null)
            }
            assertThrows(IllegalArgumentException::class.java) {
                binding.resolve(generation, ordinary, runtime)
            }

            val snapshot = binding.resolve(generation, ordinary, ultraRuntime = null)!!
            assertSame(snapshot, binding.acquire(generation))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun concurrentResolvePublishesExactlyOneUntornSnapshot() {
        val pool = Executors.newFixedThreadPool(2)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        try {
            repeat(200) { iteration ->
                val binding = AirPlaySessionRuntimeBinding()
                val generation = binding.beginGeneration()
                val firstConfig = config("first-$iteration")
                val secondConfig = config("second-$iteration")
                val start = CountDownLatch(1)

                val first = pool.submit<AirPlaySessionRuntimeBinding.ResolvedSnapshot?> {
                    start.await()
                    binding.resolve(generation, firstConfig)
                }
                val second = pool.submit<AirPlaySessionRuntimeBinding.ResolvedSnapshot?> {
                    start.await()
                    binding.resolve(generation, secondConfig)
                }
                start.countDown()

                try {
                    val outcomes = listOf(
                        first.get(5, TimeUnit.SECONDS),
                        second.get(5, TimeUnit.SECONDS),
                    )
                    val winner = outcomes.filterNotNull().single()
                    assertSame(generation, winner.generation)
                    assertTrue(winner.config === firstConfig || winner.config === secondConfig)
                    assertSame(winner, binding.acquire(generation))
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
        } finally {
            pool.shutdownNow()
        }

        assertTrue(failures.toString(), failures.isEmpty())
    }

    @Test
    fun concurrentResolveAndRevokeNeverPublishAfterRevocation() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(200) { iteration ->
                val binding = AirPlaySessionRuntimeBinding()
                val generation = binding.beginGeneration()
                val config = config("resolve-revoke-$iteration")
                val start = CountDownLatch(1)
                val resolve = pool.submit<AirPlaySessionRuntimeBinding.ResolvedSnapshot?> {
                    start.await()
                    binding.resolve(generation, config)
                }
                val revoke = pool.submit<Boolean> {
                    start.await()
                    binding.revoke(generation)
                }
                start.countDown()

                val resolved = resolve.get(5, TimeUnit.SECONDS)
                assertTrue(revoke.get(5, TimeUnit.SECONDS))
                resolved?.let {
                    assertSame(generation, it.generation)
                    assertSame(config, it.config)
                }
                assertNull(binding.acquire(generation))
                assertNull(binding.resolve(generation, config))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun concurrentAcquireAndRevokeAreLinearizedAtOneSnapshot() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val config = config("race")
        val snapshot = binding.resolve(generation, config)!!
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        val observed = Collections.synchronizedList(
            mutableListOf<AirPlaySessionRuntimeBinding.ResolvedSnapshot?>(),
        )
        try {
            val readers = (0 until 5).map {
                pool.submit {
                    start.await()
                    repeat(500) {
                        observed += binding.acquire(generation)
                    }
                }
            }
            val revoker = pool.submit<Boolean> {
                start.await()
                binding.revoke(generation)
            }
            start.countDown()

            readers.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(revoker.get(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        observed.filterNotNull().forEach {
            assertSame(snapshot, it)
            assertSame(generation, it.generation)
            assertSame(config, it.config)
        }
        assertNull(binding.acquire(generation))
    }

    @Test
    fun resolvedAdmissionAndRevokeAreMutuallyExclusive() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val config = config("admission")
        val snapshot = binding.resolve(generation, config)!!
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val revokeStarted = CountDownLatch(1)
        val revokeReturned = CountDownLatch(1)
        val resultIdentity = Any()
        val durableOwners = Collections.synchronizedList(mutableListOf<Any>())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val admission = pool.submit<Any?> {
                binding.withResolvedAdmission(generation) { admitted ->
                    assertSame(snapshot, admitted)
                    assertSame(config, admitted.config)
                    actionEntered.countDown()
                    assertTrue(releaseAction.await(5, TimeUnit.SECONDS))
                    durableOwners += resultIdentity
                    resultIdentity
                }
            }
            assertTrue(actionEntered.await(5, TimeUnit.SECONDS))

            val revoke = pool.submit<Boolean> {
                revokeStarted.countDown()
                binding.revoke(generation).also { revokeReturned.countDown() }
            }
            assertTrue(revokeStarted.await(5, TimeUnit.SECONDS))
            assertFalse(revokeReturned.await(100, TimeUnit.MILLISECONDS))

            releaseAction.countDown()
            assertSame(resultIdentity, admission.get(5, TimeUnit.SECONDS))
            assertTrue(revoke.get(5, TimeUnit.SECONDS))
            assertTrue(revokeReturned.await(5, TimeUnit.SECONDS))
            assertSame(resultIdentity, durableOwners.single())
            assertNull(binding.acquire(generation))
        } finally {
            releaseAction.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun revokeThatLinearizesFirstPreventsAdmissionAction() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val validatorRan = AtomicBoolean(false)
        binding.resolve(
            generation,
            config("revoked-first"),
            admissionValidator = {
                validatorRan.set(true)
                true
            },
        )!!
        val revoked = CountDownLatch(1)
        val actionRan = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val revoke = pool.submit {
                assertTrue(binding.revoke(generation))
                revoked.countDown()
            }
            val admission = pool.submit<Any?> {
                assertTrue(revoked.await(5, TimeUnit.SECONDS))
                binding.withResolvedAdmission(generation) {
                    actionRan.set(true)
                    Any()
                }
            }

            revoke.get(5, TimeUnit.SECONDS)
            assertNull(admission.get(5, TimeUnit.SECONDS))
            assertFalse(validatorRan.get())
            assertFalse(actionRan.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun admissionActionFailurePropagatesWithoutChangingPublication() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val snapshot = binding.resolve(generation, config("failure"))!!
        val failure = IllegalStateException("admission failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            binding.withResolvedAdmission(generation) { throw failure }
        }

        assertSame(failure, thrown)
        assertSame(snapshot, binding.acquire(generation))
        assertSame(snapshot, binding.withResolvedAdmission(generation) { it })
    }

    @Test
    fun admissionActionCannotReenterBindingOnTheSameThread() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val snapshot = binding.resolve(generation, config("reentry"))!!

        val admitted = binding.withResolvedAdmission(generation) { exact ->
            assertThrows(IllegalStateException::class.java) { binding.acquire(generation) }
            assertThrows(IllegalStateException::class.java) {
                binding.resolve(generation, config("replacement"))
            }
            assertThrows(IllegalStateException::class.java) { binding.revoke(generation) }
            assertThrows(IllegalStateException::class.java) { binding.beginGeneration() }
            assertThrows(IllegalStateException::class.java) {
                binding.withResolvedAdmission(generation) { Any() }
            }
            exact
        }

        assertSame(snapshot, admitted)
        assertSame(snapshot, binding.acquire(generation))
        assertTrue(binding.revoke(generation))
    }

    @Test
    fun pendingAndStaleGenerationsDoNotRunAdmissionAction() {
        val binding = AirPlaySessionRuntimeBinding()
        val stale = binding.beginGeneration()
        val actionRan = AtomicBoolean(false)

        assertNull(binding.withResolvedAdmission(stale) {
            actionRan.set(true)
            Any()
        })
        assertTrue(binding.revoke(stale))
        val pending = binding.beginGeneration()
        assertNull(binding.withResolvedAdmission(stale) {
            actionRan.set(true)
            Any()
        })
        assertNull(binding.withResolvedAdmission(pending) {
            actionRan.set(true)
            Any()
        })
        assertFalse(actionRan.get())
    }

    @Test
    fun validatorAndActionReceiveTheExactPublishedSnapshot() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val config = config("validated")
        var validatedSnapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot? = null
        var admittedSnapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot? = null
        val published = binding.resolve(
            generation,
            config,
            admissionValidator = {
                validatedSnapshot = it
                true
            },
        )!!

        val result = binding.withResolvedAdmission(generation) {
            admittedSnapshot = it
            Any()
        }

        assertTrue(result != null)
        assertSame(published, validatedSnapshot)
        assertSame(published, admittedSnapshot)
        assertSame(config, validatedSnapshot!!.config)
    }

    @Test
    fun livenessLossRevokesGenerationAndCannotBeRevived() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val live = AtomicBoolean(true)
        val actionRan = AtomicBoolean(false)
        binding.resolve(
            generation,
            config("liveness"),
            admissionValidator = { live.get() },
        )!!

        assertTrue(binding.withResolvedAdmission(generation) { Any() } != null)
        live.set(false)
        assertNull(binding.withResolvedAdmission(generation) {
            actionRan.set(true)
            Any()
        })
        live.set(true)

        assertNull(binding.withResolvedAdmission(generation) {
            actionRan.set(true)
            Any()
        })
        assertNull(binding.acquire(generation))
        assertFalse(binding.revoke(generation))
        assertFalse(actionRan.get())
        assertTrue(binding.beginGeneration().value > generation.value)
    }

    @Test
    fun validatorOperationalExceptionFailsClosedWithoutRunningAction() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val actionRan = AtomicBoolean(false)
        binding.resolve(
            generation,
            config("validator-exception"),
            admissionValidator = { throw IllegalStateException("not live") },
        )!!

        assertNull(binding.withResolvedAdmission(generation) {
            actionRan.set(true)
            Any()
        })

        assertFalse(actionRan.get())
        assertNull(binding.acquire(generation))
        assertFalse(binding.revoke(generation))
    }

    @Test
    fun validatorErrorPropagatesExactlyAfterFailClosedRevocation() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val failure = AssertionError("validator invariant")
        val actionRan = AtomicBoolean(false)
        binding.resolve(
            generation,
            config("validator-error"),
            admissionValidator = { throw failure },
        )!!

        val thrown = assertThrows(AssertionError::class.java) {
            binding.withResolvedAdmission(generation) {
                actionRan.set(true)
                Any()
            }
        }

        assertSame(failure, thrown)
        assertFalse(actionRan.get())
        assertNull(binding.acquire(generation))
        assertFalse(binding.revoke(generation))
    }

    @Test
    fun validatorCannotReenterBindingAndFailsClosed() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val actionRan = AtomicBoolean(false)
        binding.resolve(
            generation,
            config("validator-reentry"),
            admissionValidator = {
                binding.acquire(generation)
                true
            },
        )!!

        assertNull(binding.withResolvedAdmission(generation) {
            actionRan.set(true)
            Any()
        })

        assertFalse(actionRan.get())
        assertNull(binding.acquire(generation))
        assertFalse(binding.revoke(generation))
    }

    @Test
    fun validatorAndConcurrentRevokeAreMutuallyExclusive() {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val validatorEntered = CountDownLatch(1)
        val releaseValidator = CountDownLatch(1)
        val revokeStarted = CountDownLatch(1)
        val revokeReturned = CountDownLatch(1)
        val actionRan = AtomicBoolean(false)
        binding.resolve(
            generation,
            config("validator-race"),
            admissionValidator = {
                validatorEntered.countDown()
                assertTrue(releaseValidator.await(5, TimeUnit.SECONDS))
                false
            },
        )!!
        val pool = Executors.newFixedThreadPool(2)
        try {
            val admission = pool.submit<Any?> {
                binding.withResolvedAdmission(generation) {
                    actionRan.set(true)
                    Any()
                }
            }
            assertTrue(validatorEntered.await(5, TimeUnit.SECONDS))
            val revoke = pool.submit<Boolean> {
                revokeStarted.countDown()
                binding.revoke(generation).also { revokeReturned.countDown() }
            }
            assertTrue(revokeStarted.await(5, TimeUnit.SECONDS))
            assertFalse(revokeReturned.await(100, TimeUnit.MILLISECONDS))

            releaseValidator.countDown()
            assertNull(admission.get(5, TimeUnit.SECONDS))
            assertFalse(revoke.get(5, TimeUnit.SECONDS))
            assertTrue(revokeReturned.await(5, TimeUnit.SECONDS))
            assertFalse(actionRan.get())
            assertNull(binding.acquire(generation))
        } finally {
            releaseValidator.countDown()
            pool.shutdownNow()
        }
    }

    private fun config(name: String): AirPlayConfig = AirPlayConfig(
        deviceName = name,
        deviceId = "00:11:22:33:44:55",
        btMac = "00:11:22:33:44:66",
        sourceVersion = "1.0",
        main = AirPlayDisplayConfig(
            widthPixels = 800,
            heightPixels = 480,
        ),
    )
}
