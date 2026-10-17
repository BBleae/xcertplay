package com.shilapi.xcertplay.media

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityInstanceRegistryTest {
    @Test
    fun exactAndLegacyOwnersCannotRegisterTheSameInstance() {
        val registry = IdentityInstanceRegistry<Any>()
        val sharedSurface = Any()
        val exactOwner = registry.reserve(sharedSurface)

        assertThrows(IllegalArgumentException::class.java) {
            registry.reserve(sharedSurface)
        }
        assertFalse(registry.release(sharedSurface, Any()))
        assertTrue(registry.contains(sharedSurface))

        assertTrue(registry.release(sharedSurface, exactOwner))
        val legacyOwner = registry.reserve(sharedSurface)
        assertNotSame(exactOwner, legacyOwner)
    }

    @Test
    fun concurrentSinkReservationsHaveExactlyOneWinnerForTheSameInstance() {
        val registry = IdentityInstanceRegistry<Any>()
        val sharedSurface = Any()
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        val winners = ConcurrentLinkedQueue<Any>()
        val pool = Executors.newFixedThreadPool(8)
        repeat(16) {
            pool.execute {
                start.await()
                runCatching { registry.reserve(sharedSurface) }
                    .getOrNull()
                    ?.let(winners::add)
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(registry.contains(sharedSurface))
        assertTrue("winners=${winners.size}", winners.size == 1)
        assertTrue(registry.release(sharedSurface, winners.single()))
        pool.shutdownNow()
    }
}
