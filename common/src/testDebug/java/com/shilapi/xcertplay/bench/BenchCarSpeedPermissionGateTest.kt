package com.shilapi.xcertplay.bench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals

class BenchCarSpeedPermissionGateTest {
    private val realSpeed = BenchCafProfileCompiler.Compiled(
        null, BenchCafProfileCompiler.Compiled.Source.ANDROID_CAR, emptyMap(),
        requiresCarSpeedPermission = true,
    )
    private val synthetic = BenchCafProfileCompiler.Compiled(
        null, BenchCafProfileCompiler.Compiled.Source.SYNTHETIC, emptyMap(),
    )

    @Test fun realSpeedRequiresTheCurrentGrantAndFailsClosedOnCheckerError() {
        assertFalse(BenchCarSpeedPermissionGate.unavailable().allows(realSpeed))
        assertFalse(BenchCarSpeedPermissionGate { throw SecurityException("revoked") }
            .allows(realSpeed))

        var granted = true
        val gate = BenchCarSpeedPermissionGate { granted }
        assertTrue(gate.allows(realSpeed))
        granted = false
        assertFalse(gate.allows(realSpeed))
    }

    @Test fun syntheticFixtureDoesNotAskForCarSpeedPermission() {
        var checked = false
        val gate = BenchCarSpeedPermissionGate {
            checked = true
            false
        }
        assertTrue(gate.allows(synthetic))
        assertFalse(checked)
    }

    @Test fun idleBridgeMonitorObservesDenialAndStopsWithoutReviving() {
        val granted = AtomicBoolean(true)
        val denied = CountDownLatch(1)
        val checks = AtomicInteger()
        val monitor = BenchCarSpeedPermissionMonitor({
            checks.incrementAndGet()
            granted.get().also { if (!it) denied.countDown() }
        }, intervalMillis = 20L)
        try {
            granted.set(false)
            assertTrue(denied.await(2, TimeUnit.SECONDS))
            val countAfterDenial = checks.get()
            granted.set(true)
            Thread.sleep(80)
            assertEquals(countAfterDenial, checks.get())
        } finally {
            monitor.close()
        }
    }
}
