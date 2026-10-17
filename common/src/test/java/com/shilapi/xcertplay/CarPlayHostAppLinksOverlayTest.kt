package com.shilapi.xcertplay

import com.shilapi.xcertplay.iap2.message.Iap2AppLinkEntry
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksIconHash
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksUpdate
import com.shilapi.xcertplay.orchestration.ControllerAppLinksSnapshot
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayHostAppLinksOverlayTest {
    @Test
    fun slowDecodeIsOffCallerThreadAndLateResultIsDroppedAfterDetach() {
        val worker = ArrayDeque<() -> Unit>()
        val ui = ArrayDeque<() -> Unit>()
        val old = Any()
        val replacement = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = mutableListOf<String>()
        var decodeCalls = 0
        val gate = HostAppLinksIconDecodeGate<Any, String>(
            submit = { task -> worker.addLast(task); Closeable {} },
            postToUi = { task -> ui.addLast(task); true },
            decode = { key ->
                decodeCalls++
                if (key === old) {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    "old"
                } else "replacement"
            },
            onResult = { _, _, result -> delivered += result ?: "failed" },
        )
        assertTrue(gate.request(1, old))
        assertEquals(0, decodeCalls) // No BitmapFactory pass on the caller/UI thread.
        val oldWorker = Thread { worker.removeFirst().invoke() }
        oldWorker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            gate.clear() // Activity detach while native decoding is still in flight.
            assertTrue(gate.request(1, replacement))
        } finally {
            release.countDown()
            oldWorker.join(2_000L)
        }
        assertFalse(oldWorker.isAlive)
        worker.removeFirst().invoke()
        while (ui.isNotEmpty()) ui.removeFirst().invoke()
        assertEquals(listOf("replacement"), delivered)
        gate.close()
    }

    @Test
    fun malformedDecoderAndOutOfMemoryResultBecomeNullWithoutCrashingUi() {
        val worker = ArrayDeque<() -> Unit>()
        val ui = ArrayDeque<() -> Unit>()
        val received = mutableListOf<String?>()
        val gate = HostAppLinksIconDecodeGate<Any, String>(
            submit = { task -> worker.addLast(task); Closeable {} },
            postToUi = { task -> ui.addLast(task); true },
            decode = { key ->
                if (key === badImage) throw IllegalArgumentException("malformed decoder input")
                throw OutOfMemoryError("large image")
            },
            onResult = { _, _, result -> received += result },
        )
        assertTrue(gate.request(1, badImage))
        worker.removeFirst().invoke()
        ui.removeFirst().invoke()
        assertTrue(gate.request(2, hugeImage))
        worker.removeFirst().invoke()
        ui.removeFirst().invoke()
        assertEquals(listOf(null, null), received)
        gate.close()
    }

    @Test
    fun oldPostedDecodeCannotReviveSameKeyAfterDetachReattachAba() {
        val worker = ArrayDeque<() -> Unit>()
        val ui = ArrayDeque<() -> Unit>()
        val icon = Any()
        var decodeOrdinal = 0
        val delivered = mutableListOf<Int>()
        val gate = HostAppLinksIconDecodeGate<Any, Int>(
            submit = { task -> worker.addLast(task); Closeable {} },
            postToUi = { task -> ui.addLast(task); true },
            decode = { ++decodeOrdinal },
            onResult = { _, _, result -> result?.let(delivered::add) },
        )
        assertTrue(gate.request(1, icon))
        worker.removeFirst().invoke() // First result has already posted to UI.
        gate.clear()
        assertTrue(gate.request(1, icon)) // Same revision and same object after reattach.
        worker.removeFirst().invoke()
        assertEquals(2, ui.size)
        ui.removeFirst().invoke()
        assertTrue(delivered.isEmpty())
        ui.removeFirst().invoke()
        assertEquals(listOf(2), delivered)
        gate.close()
    }

    private val badImage = Any()
    private val hugeImage = Any()

    @Test
    fun iconSizeSelectorHasOnlyNoneAndExplicit64Pixels() {
        assertEquals(HostAppLinksPreferredIconSize.NONE, HostAppLinksPreferredIconSize.entries.first())
        assertNull(HostAppLinksPreferredIconSize.NONE.pixels)
        assertEquals(64, HostAppLinksPreferredIconSize.PIXELS_64.pixels)
        assertEquals(2, HostAppLinksPreferredIconSize.entries.size)
    }

    @Test
    fun passiveIconSummaryShowsPresenceWithoutHashOrImageClaim() {
        val entry = Iap2AppLinkEntry(
            index = 1,
            bundleIdentifier = "com.example.secret",
            localizedName = "Navigation",
            categoryId = 3,
            iconHash = Iap2AppLinksIconHash.of(ByteArray(16) { 0x4f }),
        )
        val summary = hostAppLinksEntrySummary(entry)

        assertTrue(summary.contains("icon hash metadata present"))
        assertFalse(summary.contains("com.example.secret"))
        assertFalse(summary.contains("4f4f"))
        assertFalse(summary.contains("image"))
    }

    @Test
    fun requestButtonRequiresExplicit64pxActiveState2CurrentHashAndUnusedChannel() {
        val entry = Iap2AppLinkEntry(
            index = 7,
            bundleIdentifier = "com.example.secret",
            localizedName = "Navigation",
            categoryId = 3,
            iconHash = Iap2AppLinksIconHash.of(ByteArray(16) { 0x4f }),
        )
        val ready = ControllerAppLinksSnapshot(
            available = true,
            subscribed = true,
            preferredIconSize = 64,
            iconFeatureAvailable = true,
            latestUpdate = Iap2AppLinksUpdate(2, listOf(entry), 1),
        )
        assertTrue(hostCanRequestAppLinksIcon(ready, entry))
        assertFalse(hostCanRequestAppLinksIcon(ready.copy(preferredIconSize = null), entry))
        assertFalse(hostCanRequestAppLinksIcon(ready.copy(iconFeatureAvailable = false), entry))
        assertFalse(hostCanRequestAppLinksIcon(ready.copy(subscribed = false), entry))
        assertFalse(hostCanRequestAppLinksIcon(ready.copy(iconRequestAttempted = true), entry))
        assertFalse(hostCanRequestAppLinksIcon(
            ready.copy(latestUpdate = Iap2AppLinksUpdate(1, listOf(entry), null)), entry,
        ))
        assertFalse(hostCanRequestAppLinksIcon(
            ready.copy(latestUpdate = Iap2AppLinksUpdate(2, listOf(entry.copy(iconHash = null)), 1)),
            entry,
        ))
    }

    @Test
    fun debugUltraControlRemainsReachableWhileVideoIsStreaming() {
        assertTrue(hostAppLinksOverlayVisible(
            debuggable = true,
            ultraEnabled = true,
            streamActive = true,
            menuOpen = false,
            safeAreaEditorActive = false,
        ))
    }

    @Test
    fun releaseOrdinarySettingsAndEditorDoNotExposeExperimentalControl() {
        val ready = listOf(
            hostAppLinksOverlayVisible(false, true, true, false, false),
            hostAppLinksOverlayVisible(true, false, true, false, false),
            hostAppLinksOverlayVisible(true, true, false, false, false),
            hostAppLinksOverlayVisible(true, true, true, true, false),
            hostAppLinksOverlayVisible(true, true, true, false, true),
        )
        assertTrue(ready.all { !it })
        assertFalse(hostAppLinksOverlayVisible(false, false, false, false, false))
    }
}
