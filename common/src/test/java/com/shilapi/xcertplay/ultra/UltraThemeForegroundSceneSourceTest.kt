package com.shilapi.xcertplay.ultra

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraThemeForegroundSceneSourceTest {
    @Test fun aRetiredSceneRevokesAnInFlightUseAndRejectsNewUses() {
        val live = AtomicBoolean(true)
        val callback = CountDownLatch(1)
        val source = UltraThemeForegroundSceneSource(live::get)
        val use = source.acquireUse(callback::countDown)
        assertNotNull(use)
        assertTrue(use!!.isCurrent())

        live.set(false)
        assertFalse(use.isCurrent())
        assertTrue(callback.await(2, TimeUnit.SECONDS))
        assertNull(source.acquireUse { })
        use.close()
    }

    @Test fun explicitRevisionReplacementRevokesOnlyUnclosedUses() {
        val callback = CountDownLatch(1)
        val source = UltraThemeForegroundSceneSource { true }
        source.acquireUse { error("Closed lease callback must not run") }!!.close()
        val active = source.acquireUse(callback::countDown)!!

        source.revoke()
        assertFalse(active.isCurrent())
        assertTrue(callback.await(2, TimeUnit.SECONDS))
        assertNull(source.acquireUse { })
        active.close()
    }
}
