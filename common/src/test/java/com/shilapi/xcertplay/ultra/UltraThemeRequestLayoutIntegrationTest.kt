package com.shilapi.xcertplay.ultra

import android.graphics.Canvas
import com.shilapi.xcertplay.AuthorizedUltraDisplayLeaseTestFixture
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncAccessoryCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEvent
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestPort
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestState
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundReadyGeneration
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundSceneGuard
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundSceneUseSource
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalFirstFrameReceipt
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBatch
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/** Exercises the real signed controller using its existing deterministic JVM scene fixture. */
class UltraThemeRequestLayoutIntegrationTest {
    @Test fun queuedSceneBeforeItsTicketIsPresentedDoesNotRequestLayout() {
        FixtureBridge().use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.onSubmit {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            val failure = AtomicReference<Throwable?>()
            val activator = Thread {
                runCatching { fixture.activate() }.exceptionOrNull()?.let(failure::set)
            }.apply { isDaemon = true; start() }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertEquals(1, fixture.submissionCount())
                assertFalse(port.awaitCount(1, 100L))
            } finally {
                release.countDown()
                activator.join(3_000L)
            }
            assertFalse("Activation did not return", activator.isAlive)
            failure.get()?.let { throw AssertionError("Signed activation failed", it) }
            assertTrue(port.awaitCount(1))
        }
    }

    @Test fun exactOpaqueMainPresentationRequestsOnlyItsSignedLayout() {
        FixtureBridge().use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            assertTrue(port.awaitCount(1))
            fixture.awaitForegroundReceipt()
            assertEquals("main-ui", port.requests.single().first)
            assertEquals("main-a", port.requests.single().second.layoutID)
            assertTrue(port.requests.single().second.activeComponents.isEmpty())

            fixture.send(fixture.mainId, "main-b", "wrong-palette")
            assertEquals(1, port.requests.size)
            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            assertFalse("CLUSTER-only replacement must not re-request MAIN",
                port.awaitCount(2, 300L))

            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertTrue(port.awaitCount(2))
            assertEquals("main-b", port.requests.last().second.layoutID)
            assertEquals(2, port.requests.size)
        }
    }

    @Test fun v3CarouselRequestsExactSignedMapOnlyAfterItsOwnMainEglReceipt() {
        FixtureBridge(localCanvas = true, carousel = true, painter = { _, _ -> },
            proveLocalCanvas = true).use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            val mapProof = fixture.awaitLocalProofAfter(-1L)
            assertTrue(port.awaitCount(1))
            assertEquals("main-a", port.requests[0].second.layoutID)
            assertEquals(mapOf("main-carousel-zone" to "map"),
                port.requests[0].second.activeComponents)

            fixture.send(fixture.mainId, "main-a", "palette-a",
                mapOf("main-carousel-zone" to "media"))
            val mediaProof = fixture.awaitLocalProofAfter(mapProof.revision)
            assertTrue(mediaProof.revision > mapProof.revision)
            assertTrue(port.awaitCount(2))
            assertEquals(mapOf("main-carousel-zone" to "media"),
                port.requests[1].second.activeComponents)

            fixture.send(fixture.mainId, "main-a", "palette-b",
                mapOf("main-carousel-zone" to "map"))
            val alternatePaletteProof = fixture.awaitLocalProofAfter(mediaProof.revision)
            assertTrue(alternatePaletteProof.revision > mediaProof.revision)
            assertTrue(port.awaitCount(3))
            assertEquals(mapOf("main-carousel-zone" to "map"),
                port.requests[2].second.activeComponents)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test fun uncertainWriteWaitsForNewMainProofButDoesNotSuppressItsRequest() {
        FixtureBridge().use { fixture ->
            val port = RecordingPort(firstState = UiSyncForegroundRequestState.WRITE_UNCERTAIN)
            fixture.install(port)
            fixture.activate()
            assertTrue(port.awaitCount(1))
            fixture.awaitForegroundReceipt()
            val firstRevision = fixture.controller.stateSnapshot().lastSubmittedRevision
            assertFalse("An uncertain same-scene write must not be blindly replayed",
                port.awaitCount(2, 200L))

            // CLUSTER selection submits a distinct complete scene, including a newly
            // presented MAIN. The old uncertain write may have emitted zero bytes.
            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            assertTrue(port.awaitCount(2))
            assertTrue(fixture.controller.stateSnapshot().lastSubmittedRevision != firstRevision)
            assertEquals("main-a", port.requests[1].second.layoutID)
        }
    }

    @Test fun oldReadyChannelSentReceiptCannotSuppressFreshChannelMainScene() {
        FixtureBridge().use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            assertTrue(port.awaitCount(1))
            fixture.awaitForegroundReceipt()

            // The first raw5 was written on the old ClusterControl READY epoch. Re-attaching
            // the channel changes its opaque token even though renderer and batch stay live.
            port.replaceReadyGeneration()
            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            assertTrue(port.awaitCount(2))
            assertEquals("main-a", port.requests[1].second.layoutID)
        }
    }

    @Test fun transientParentBusyDefersRatherThanRevokesForegroundRequest() {
        FixtureBridge().use { fixture ->
            val guardEntered = CountDownLatch(1)
            val guardRelease = CountDownLatch(1)
            val probeEntered = CountDownLatch(1)
            val probeRelease = CountDownLatch(1)
            val port = RecordingPort(guardEntered, guardRelease)
            fixture.install(port)
            fixture.activate()
            assertTrue(guardEntered.await(2, TimeUnit.SECONDS))
            fixture.lease.setNextParentProbeHook {
                probeEntered.countDown()
                check(probeRelease.await(3, TimeUnit.SECONDS))
            }
            val probeFailure = AtomicReference<Throwable?>()
            val probe = Thread {
                runCatching { fixture.lease.probeParentLease() }
                    .exceptionOrNull()?.let(probeFailure::set)
            }.apply { isDaemon = true; start() }
            try {
                assertTrue(probeEntered.await(2, TimeUnit.SECONDS))
                guardRelease.countDown()
                assertFalse("Request escaped during a transient AA05 parent operation",
                    port.awaitCount(1, 150L))
                assertFalse(fixture.controller.stateSnapshot().failed)
            } finally {
                probeRelease.countDown()
                guardRelease.countDown()
                probe.join(3_000L)
            }
            assertFalse(probe.isAlive)
            probeFailure.get()?.let { throw AssertionError("Parent probe failed", it) }
            assertTrue(port.awaitCount(1))
        }
    }

    @Test fun revokedAa05BeforeOutboundRequestNeverSends() {
        FixtureBridge().use { fixture ->
            val guardEntered = CountDownLatch(1)
            val guardRelease = CountDownLatch(1)
            val port = RecordingPort(guardEntered, guardRelease)
            fixture.install(port)
            fixture.activate()
            try {
                assertTrue(guardEntered.await(2, TimeUnit.SECONDS))
                fixture.lease.revoke()
            } finally {
                guardRelease.countDown()
            }
            assertFalse(port.awaitCount(1, 300L))
        }
    }

    @Test fun unsupportedLatestTypeThreeRevokesQueuedOldRawFiveUntilFreshSignedProof() {
        FixtureBridge(localCanvas = true, painter = { _, _ -> },
            proveLocalCanvas = true).use { fixture ->
            val guardEntered = CountDownLatch(1)
            val guardRelease = CountDownLatch(1)
            val port = RecordingPort(guardEntered, guardRelease)
            fixture.install(port)
            fixture.activate()
            val originalProof = fixture.awaitLocalProofAfter(-1L)
            try {
                assertTrue("Old raw5 did not reach its controlled pre-write gate",
                    guardEntered.await(3, TimeUnit.SECONDS))
                val submitted = fixture.submissionCount()
                fixture.send(fixture.mainId, "main-a", "palette-a",
                    mapOf("not-issued-zone" to "unknown"))
                assertEquals("Unsupported phone intent must not replace safe old pixels",
                    submitted, fixture.submissionCount())
            } finally {
                guardRelease.countDown()
            }
            assertFalse("Old raw5 escaped after the phone changed to an unsigned intent",
                port.awaitCount(1, 300L))
            assertFalse(fixture.controller.stateSnapshot().failed)

            fixture.send(fixture.mainId, "main-a", "palette-a")
            val replacementProof = fixture.awaitLocalProofAfter(originalProof.revision)
            assertTrue(replacementProof.revision > originalProof.revision)
            assertTrue("A later exact signed intent with fresh EGL proof did not recover raw5",
                port.awaitCount(1))
            assertEquals("main-a", port.requests.single().second.layoutID)
            assertTrue(port.requests.single().second.activeComponents.isEmpty())
        }
    }

    @Test fun localCanvasPostWithoutNewRevisionEglProofNeverRequestsLayout() {
        FixtureBridge(localCanvas = true, painter = { _, _ -> }).use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            assertFalse(port.awaitCount(1, 300L))
            assertEquals(0, port.requests.size)
            assertFalse(fixture.controller.stateSnapshot().activated)
        }
    }

    @Test fun signedMainLocalCanvasRequestsOnlyAfterNewRevisionDrawReceipt() {
        FixtureBridge(localCanvas = true, painter = { _, _ -> },
            proveLocalCanvas = true).use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (fixture.controller.stateSnapshot().localFirstFrameReceipt == null &&
                System.nanoTime() < deadline
            ) Thread.sleep(10)
            val proof = fixture.controller.stateSnapshot().localFirstFrameReceipt
            assertTrue("MAIN Local Canvas never received its EGL draw receipt", proof != null)
            assertTrue(proof!!.requiredDraws.any { it.destination == DisplayDestination.MAIN })
            assertTrue(port.awaitCount(1))
            fixture.awaitForegroundReceipt()
            assertEquals("main-a", port.requests.single().second.layoutID)
        }
    }

    @Test fun authorizedDebugCustomMainCanvasCanRequestAfterItsExactEglProof() {
        FixtureBridge(localCanvas = true, painter = { _, _ -> },
            proveLocalCanvas = true, diagnosticFallbackEnabled = true).use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (fixture.controller.stateSnapshot().localFirstFrameReceipt == null &&
                System.nanoTime() < deadline
            ) Thread.sleep(10)
            val receipt = fixture.controller.stateSnapshot().localFirstFrameReceipt
            assertTrue(receipt?.requiredDraws?.any {
                it.destination == DisplayDestination.MAIN
            } == true)
            assertTrue(port.awaitCount(1))
            assertEquals("main-a", port.requests.single().second.layoutID)
        }
    }

    @Test fun syntheticDebugFallbackAndOpaqueDebugSceneNeverRequestLayout() {
        FixtureBridge(localCanvas = true, proveLocalCanvas = true,
            diagnosticFallbackEnabled = true).use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (fixture.controller.stateSnapshot().localFirstFrameReceipt == null &&
                System.nanoTime() < deadline
            ) Thread.sleep(10)
            assertTrue(fixture.controller.stateSnapshot().localFirstFrameReceipt != null)
            assertFalse("A synthesized missing-painter frame is not a foreground proof",
                port.awaitCount(1, 300L))
        }
        FixtureBridge(diagnosticFallbackEnabled = true).use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            assertFalse("Opaque Debug fallback must remain inert", port.awaitCount(1, 300L))
        }
        FixtureBridge(video = true, diagnosticFallbackEnabled = true).use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            assertFalse("Video-only Debug MAIN has no local foreground proof",
                port.awaitCount(1, 300L))
        }
    }

    @Test fun latePainterCannotLaunderCarriedSyntheticMainButFreshRepaintCanRequest() {
        val customDraws = AtomicInteger()
        // The initial producer has no custom painter and posts only the diagnostic frame.
        FixtureBridge(localCanvas = true,
            proveLocalCanvas = true, diagnosticFallbackEnabled = true).use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            val synthetic = fixture.awaitLocalProofAfter(-1L)
            assertFalse(port.awaitCount(1, 150L))
            assertEquals(0, customDraws.get())

            fixture.enableCustomDrawPort()
            fixture.replacePainterProvider { contentId ->
                if (contentId.value == "canvas.main.a" ||
                    contentId.value == "canvas.main.b"
                ) ({ _: Canvas, _: PixelSize -> customDraws.incrementAndGet(); Unit }) else null
            }
            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            val carried = fixture.awaitLocalProofAfter(synthetic.revision)
            assertTrue(carried.revision > synthetic.revision)
            assertEquals("Carrying the old post must not invoke a newly registered painter",
                0, customDraws.get())
            assertFalse("A synthetic carried post cannot authorize raw5",
                port.awaitCount(1, 200L))

            // MAIN-B has a distinct signed content ID, so this revision must post through the
            // now-registered real painter before its new EGL receipt can authorize raw5.
            fixture.send(fixture.mainId, "main-b", "palette-b")
            fixture.awaitLocalProofAfter(carried.revision)
            assertTrue(customDraws.get() > 0)
            assertTrue(port.awaitCount(1))
            assertEquals("main-b", port.requests.single().second.layoutID)
        }
    }

    @Test fun localMainProofCannotEscapeAfterItsPhysicalBatchRetires() {
        FixtureBridge(localCanvas = true, painter = { _, _ -> },
            proveLocalCanvas = true, diagnosticFallbackEnabled = true).use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val port = RecordingPort(entered, release)
            fixture.install(port)
            fixture.activate()
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                fixture.retirePhysicalBatch()
            } finally {
                release.countDown()
            }
            assertFalse(port.awaitCount(1, 300L))
        }
    }

    @Test fun aReceiptWithoutTheActualMainDrawCannotAuthorizeRequestLayout() {
        FixtureBridge(localCanvas = true, painter = { _, _ -> },
            proveLocalCanvas = true).use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val port = RecordingPort(entered, release)
            fixture.install(port)
            fixture.activate()
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                fixture.replaceLocalReceiptWithMissingMainDraw()
            } finally {
                release.countDown()
            }
            assertFalse(port.awaitCount(1, 300L))
        }
    }

    @Test fun remoteVideoMainPlaceholderAndDiagnosticPathNeverRequestLayout() {
        FixtureBridge(video = true).use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            assertFalse(port.awaitCount(1, 300L))
        }
        FixtureBridge(diagnostic = true).use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertFalse(port.awaitCount(1, 300L))
        }
    }

    @Test fun retiredPhysicalBatchAndClosedControllerRejectLaterIntent() {
        FixtureBridge().use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            assertTrue(port.awaitCount(1))
            fixture.awaitForegroundReceipt()
            fixture.retirePhysicalBatch()
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertFalse(port.awaitCount(2, 300L))
        }
    }

    @Test fun twoRendererChildrenNeverBorrowEachOthersForegroundPort() {
        val firstPort = RecordingPort()
        FixtureBridge().use { first ->
            first.install(firstPort)
            first.activate()
            assertTrue(firstPort.awaitCount(1))
            first.awaitForegroundReceipt()
        }
        val secondPort = RecordingPort()
        FixtureBridge().use { second ->
            second.install(secondPort)
            second.activate()
            assertTrue(secondPort.awaitCount(1))
            second.awaitForegroundReceipt()
            second.send(second.mainId, "main-b", "palette-b")
            assertTrue(secondPort.awaitCount(2))
            assertEquals("main-b", secondPort.requests.last().second.layoutID)
        }
        assertEquals(1, firstPort.requests.size)
    }

    @Test fun cancelledPreReadyRequestRequeuesFreshGuardForSameSceneOnReconnect() {
        FixtureBridge().use { fixture ->
            val port = RecordingPort(firstState = UiSyncForegroundRequestState.QUEUED)
            fixture.install(port)
            fixture.activate()
            assertTrue(port.awaitCount(1))
            fixture.awaitForegroundReceipt()
            assertEquals(UiSyncForegroundRequestState.QUEUED, port.receipts[0].state)

            // Shared outbox sets CANCELLED on channel detach before READY. The exact scene
            // remains live, but the old queued row must not be replayed into a new channel.
            port.setReceiptState(0, UiSyncForegroundRequestState.CANCELLED)
            assertTrue(port.awaitCount(2, 3_000L))
            assertTrue(port.guards[0] !== port.guards[1])
            assertEquals("main-a", port.requests[1].second.layoutID)
            port.setReceiptState(1, UiSyncForegroundRequestState.SENT_UNCONFIRMED)
            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            assertFalse(port.awaitCount(3, 300L))
        }
    }

    @Test fun standalonePaletteDivergenceSuspendsUntilFreshSignedLayoutSelection() {
        FixtureBridge().use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            assertTrue(port.awaitCount(1))
            fixture.awaitForegroundReceipt()

            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.PALETTE_CHANGE,
                mapOf("layoutIdentifier" to "main-a", "paletteIdentifier" to "unsworn"))
            assertFalse(fixture.controller.stateSnapshot().activated)
            assertFalse(port.awaitCount(2, 150L))

            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertTrue(port.awaitCount(2))
            assertEquals("main-b", port.requests.last().second.layoutID)
        }
    }

    @Test fun appearanceTransitionSuspendsUntilLaterSignedLayoutSelection() {
        FixtureBridge().use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            assertTrue(port.awaitCount(1))
            fixture.awaitForegroundReceipt()

            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START,
                mapOf("targetAppearance" to 1))
            assertFalse(fixture.controller.stateSnapshot().activated)
            assertFalse(port.awaitCount(2, 150L))

            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END,
                mapOf("targetAppearance" to 1))
            assertFalse(port.awaitCount(2, 150L))

            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertTrue(port.awaitCount(2))
            assertEquals("main-b", port.requests.last().second.layoutID)
        }
    }

    @Test fun transitionStartRevokesAnInFlightMainRequestAndEndCannotRestoreIt() {
        FixtureBridge().use { fixture ->
            val guardEntered = CountDownLatch(1)
            val guardRelease = CountDownLatch(1)
            val port = RecordingPort(guardEntered, guardRelease)
            fixture.install(port)
            fixture.activate()
            try {
                assertTrue(guardEntered.await(2, TimeUnit.SECONDS))
                fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.TRANSITION_START,
                    mapOf("layoutID" to "main-b",
                        "activeComponents" to emptyMap<String, String>(),
                        "paletteID" to "palette-b"))
                fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.TRANSITION_END,
                    mapOf("regions" to emptyList<Map<String, Any>>(),
                        "current_ntp_time" to 1L))
            } finally {
                guardRelease.countDown()
            }
            assertFalse("A layout-transition start kept the old scene guard",
                port.awaitCount(1, 300L))
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test fun transitionNeedsFreshSignedMainSelectionAndNewEglReceiptForRawFive() {
        FixtureBridge(localCanvas = true, painter = { _, _ -> },
            proveLocalCanvas = true).use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            val initial = fixture.awaitLocalProofAfter(-1L)
            assertTrue(port.awaitCount(1))
            fixture.awaitForegroundReceipt()

            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.TRANSITION_START,
                emptyMap())
            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            val carriedDuringTransition = fixture.awaitLocalProofAfter(initial.revision)
            assertTrue(carriedDuringTransition.revision > initial.revision)
            assertFalse("A newer CLUSTER replay cannot restore the suspended MAIN source",
                port.awaitCount(2, 200L))

            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.TRANSITION_END,
                mapOf("regions" to emptyList<Map<String, Any>>(),
                    "current_ntp_time" to 2L))
            assertFalse("Type-4 is not an Android first-frame receipt",
                port.awaitCount(2, 200L))

            // Even the same signed MAIN selection must enqueue a new scene revision. The
            // prior custom post may be carried, but only the new EGL draw/swap/finish qualifies.
            fixture.send(fixture.mainId, "main-a", "palette-a")
            val restored = fixture.awaitLocalProofAfter(carriedDuringTransition.revision)
            assertTrue(restored.revision > carriedDuringTransition.revision)
            assertTrue(port.awaitCount(2))
            assertEquals("main-a", port.requests.last().second.layoutID)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test fun coalescedTransitionOrderAndOtherDisplayStayFailClosed() {
        FixtureBridge().use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            assertTrue(port.awaitCount(1))
            fixture.awaitForegroundReceipt()

            // A type-3 before type-1 in the same accepted history cannot clear the gate.
            fixture.send(fixture.mainId, "main-a", "palette-a", emit = false)
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.TRANSITION_START,
                emptyMap(), emit = false)
            fixture.replay(fixture.mainId)
            assertFalse(port.awaitCount(2, 200L))

            // A foreign display's transition is not allowed to revoke MAIN. Its own
            // subscription and signed layout choice remain independent.
            fixture.sendRaw(fixture.clusterId, CarPlayUiSyncCommandType.TRANSITION_START,
                emptyMap())
            assertFalse(port.awaitCount(2, 200L))

            // A later exact signed MAIN type-3, not type-4 or a stale type-3, restores it.
            fixture.send(fixture.mainId, "main-a", "palette-a")
            assertTrue(port.awaitCount(2))
            assertEquals("main-a", port.requests.last().second.layoutID)

            // The reverse order in one coalesced replay is a fresh signed selection.
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.TRANSITION_START,
                emptyMap(), emit = false)
            fixture.send(fixture.mainId, "main-a", "palette-a", emit = false)
            fixture.replay(fixture.mainId)
            assertTrue(port.awaitCount(3))
            assertEquals("main-a", port.requests.last().second.layoutID)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
        FixtureBridge().use { unrelated ->
            val port = RecordingPort()
            unrelated.install(port)
            unrelated.activate()
            assertTrue(port.awaitCount(1))
            unrelated.awaitForegroundReceipt()
            unrelated.sendRaw(unrelated.clusterId,
                CarPlayUiSyncCommandType.TRANSITION_START, emptyMap())
            unrelated.send(unrelated.mainId, "main-b", "palette-b")
            assertTrue("CLUSTER type-1 must not suspend this session's MAIN",
                port.awaitCount(2))
            assertEquals("main-b", port.requests.last().second.layoutID)
        }
    }

    @Test fun transitionFromAnotherRendererSessionCannotRevokeThisSessionsMain() {
        FixtureBridge().use { first ->
            FixtureBridge().use { second ->
                val firstPort = RecordingPort()
                val secondPort = RecordingPort()
                first.install(firstPort)
                second.install(secondPort)
                first.activate()
                second.activate()
                assertTrue(firstPort.awaitCount(1))
                assertTrue(secondPort.awaitCount(1))
                first.awaitForegroundReceipt()
                second.awaitForegroundReceipt()

                second.sendRaw(second.mainId, CarPlayUiSyncCommandType.TRANSITION_START,
                    emptyMap())
                first.send(first.mainId, "main-b", "palette-b")
                assertTrue("A foreign renderer child's transition revoked MAIN",
                    firstPort.awaitCount(2))
                assertEquals("main-b", firstPort.requests.last().second.layoutID)
                assertFalse(secondPort.awaitCount(2, 200L))
                assertFalse(first.controller.stateSnapshot().failed)
                assertFalse(second.controller.stateSnapshot().failed)
            }
        }
    }

    @Test fun mismatchedAppearanceEndCannotReleaseTheOriginalTransition() {
        FixtureBridge().use { fixture ->
            val port = RecordingPort()
            fixture.install(port)
            fixture.activate()
            assertTrue(port.awaitCount(1))
            fixture.awaitForegroundReceipt()

            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START,
                mapOf("targetAppearance" to 1))
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END,
                mapOf("targetAppearance" to 0))
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertFalse(port.awaitCount(2, 200L))

            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END,
                mapOf("targetAppearance" to 1))
            fixture.send(fixture.mainId, "main-a", "palette-a")
            assertTrue(port.awaitCount(2))
            assertEquals("main-a", port.requests.last().second.layoutID)
        }
    }

    /** The legacy fixture is private; reflection shares its deterministic issuer and fake EGL. */
    private class FixtureBridge(
        video: Boolean = false,
        localCanvas: Boolean = false,
        painter: ((Canvas, PixelSize) -> Unit)? = null,
        private val diagnostic: Boolean = false,
        proveLocalCanvas: Boolean = false,
        diagnosticFallbackEnabled: Boolean = false,
        carousel: Boolean = false,
    ) : Closeable {
        private val raw: Any = run {
            val fixture = Class.forName(
                "com.shilapi.xcertplay.ultra.UltraThemeLayoutIntentControllerTest\$Fixture",
            )
            // The controller test fixture may append optional foreground and semantic-cover
            // callbacks. Select by the stable leading parameters, not Kotlin's default-mask
            // synthetic constructor or a brittle exact parameter count.
            val constructor = fixture.declaredConstructors.single { candidate ->
                val types = candidate.parameterTypes
                types.size in 14..18 && types[0] == Int::class.javaPrimitiveType &&
                    types[13] == Boolean::class.javaPrimitiveType &&
                    when (types.size) {
                        14 -> true
                        18 -> types[14] == Boolean::class.javaPrimitiveType &&
                            UiSyncForegroundRequestPort::class.java
                                .isAssignableFrom(types[15])
                        else -> UiSyncForegroundRequestPort::class.java
                            .isAssignableFrom(types[14])
                    }
            }.apply { isAccessible = true }
            val arguments = arrayOf<Any?>(128, null, video, localCanvas, false, false,
                !diagnostic, true, painter, diagnosticFallbackEnabled,
                proveLocalCanvas, null, null, carousel)
            // This integration fixture has no Host MAIN gate. Supply a deterministic exact-
            // cover ACK for the controller seam; Authority/Gate tests prove the actual target.
            val beginCover: (UiSyncPhysicalOutputBatch, UltraUiSyncDisplayId, Long) ->
                MainSemanticPhysicalCoverTicket? = { _, _, _ ->
                MainSemanticPhysicalCoverTicket { true }
            }
            val resumeCover: (UiSyncPhysicalOutputBatch, UltraUiSyncDisplayId, Long) ->
                Boolean = { _, _, _ -> true }
            constructor.newInstance(*Array<Any?>(constructor.parameterCount) { index ->
                if (constructor.parameterCount == 18) when (index) {
                    14 -> false
                    16 -> beginCover
                    17 -> resumeCover
                    else -> arguments.getOrNull(index)
                } else when (index) {
                    15 -> beginCover
                    16 -> resumeCover
                    else -> arguments.getOrNull(index)
                }
            })
        }

        val controller: UltraThemeLayoutIntentController = getter("getController")
        val lease: AuthorizedUltraDisplayLeaseTestFixture = getter("getLease")
        val mainId: UltraUiSyncDisplayId = getter("getMainId")
        val clusterId: UltraUiSyncDisplayId = getter("getClusterId")

        @Suppress("UNCHECKED_CAST")
        private fun <T> getter(name: String): T = raw.javaClass.getMethod(name).invoke(raw) as T

        fun install(port: UiSyncForegroundRequestPort) {
            UltraThemeLayoutIntentController::class.java.getDeclaredField("foregroundRequests")
                .apply { isAccessible = true }.set(controller, port)
        }

        fun replacePainterProvider(
            provider: (UltraContentId) -> ((Canvas, PixelSize) -> Unit)?,
        ) {
            val type = UltraThemeLayoutIntentController::class.java
            val lock = checkNotNull(type.getDeclaredField("lock")
                .apply { isAccessible = true }.get(controller))
            synchronized(lock) {
                type.getDeclaredField("localCanvasPainter")
                    .apply { isAccessible = true }.set(controller, provider)
            }
        }

        fun enableCustomDrawPort() {
            val owner = checkNotNull(raw.javaClass.getDeclaredField("canvasOwner")
                .apply { isAccessible = true }.get(raw)) as UltraLocalCanvasSlotOwner
            val replacement = object : UltraLocalCanvasDrawPort {
                override fun draw(
                    surface: android.view.Surface?,
                    sizePixels: PixelSize,
                    painter: (Canvas, PixelSize) -> Unit,
                ) {
                    val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
                        .apply { isAccessible = true }.get(null) as Unsafe
                    val canvas = unsafe.allocateInstance(Canvas::class.java) as Canvas
                    painter(canvas, sizePixels)
                }

                override fun drawWithPostFence(
                    surface: android.view.Surface?,
                    sizePixels: PixelSize,
                    painter: (Canvas, PixelSize) -> Unit,
                    finalPost: (clear: () -> Unit, post: () -> Unit) -> Unit,
                ) {
                    draw(surface, sizePixels, painter)
                    finalPost({}, {})
                }
            }
            UltraLocalCanvasSlotOwner::class.java.getDeclaredField("drawPort")
                .apply { isAccessible = true }.set(owner, replacement)
        }

        fun awaitLocalProofAfter(priorRevision: Long): UltraEglLocalFirstFrameReceipt {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                val proof = controller.stateSnapshot().localFirstFrameReceipt
                if (proof != null && proof.revision > priorRevision) return proof
                Thread.sleep(10)
            }
            throw AssertionError("No Local Canvas EGL proof newer than $priorRevision")
        }

        fun onSubmit(callback: () -> Unit) {
            val backend = raw.javaClass.getMethod("getBackend").invoke(raw)
            backend.javaClass.getMethod("setOnSubmit", kotlin.jvm.functions.Function0::class.java)
                .invoke(backend, callback)
        }

        fun submissionCount(): Int {
            val backend = raw.javaClass.getMethod("getBackend").invoke(raw)
            return (backend.javaClass.getMethod("getSubmissions").invoke(backend) as List<*>).size
        }

        fun activate() {
            if (!diagnostic) raw.javaClass.getMethod("activateArmed").invoke(raw)
        }

        fun send(id: UltraUiSyncDisplayId, layout: String, palette: String,
            components: Map<String, String> = emptyMap(),
            emit: Boolean = true,
        ) {
            raw.javaClass.getMethod("send", UltraUiSyncDisplayId::class.java,
                String::class.java, String::class.java, Map::class.java,
                java.lang.Boolean.TYPE).invoke(raw, id, layout, palette, components, emit)
        }

        fun sendRaw(
            id: UltraUiSyncDisplayId,
            type: CarPlayUiSyncCommandType,
            data: Map<String, Any>,
            emit: Boolean = true,
        ) {
            val source = raw.javaClass.getMethod("getSource").invoke(raw)
            val hub = source.javaClass.getDeclaredField("hub")
                .apply { isAccessible = true }.get(source) as UltraPresentationHub
            hub.sink(id).onPresentationEvent(
                CarPlayUiSyncPresentationEvent.KnownCommand(type, data),
            )
            if (emit) replay(id)
        }

        fun replay(id: UltraUiSyncDisplayId) {
            val source = raw.javaClass.getMethod("getSource").invoke(raw)
            val hub = source.javaClass.getDeclaredField("hub")
                .apply { isAccessible = true }.get(source) as UltraPresentationHub
            source.javaClass.getMethod("replay", UltraUiSyncDisplayId::class.java,
                UltraPresentationSnapshot::class.java).invoke(source, id, hub.snapshot(id))
        }

        fun retirePhysicalBatch() =
            raw.javaClass.getMethod("retirePhysicalBatch").invoke(raw).let { Unit }

        fun replaceLocalReceiptWithMissingMainDraw() {
            val controllerType = UltraThemeLayoutIntentController::class.java
            val field = controllerType.getDeclaredField("lastLocalFirstFrameReceipt")
                .apply { isAccessible = true }
            val lock = checkNotNull(controllerType.getDeclaredField("lock")
                .apply { isAccessible = true }.get(controller))
            synchronized(lock) {
                val original = field.get(controller) as UltraEglLocalFirstFrameReceipt
                val missingMain = UltraEglLocalFirstFrameReceipt(
                    original.revision, original.graphRevision,
                    original.physicalBatchGeneration, original.outputBatchGeneration,
                    original.requiredDraws.filterTo(LinkedHashSet()) {
                        it.destination != DisplayDestination.MAIN
                    }, original.postSequences, original.ingressGenerations,
                    original.frameSignals, original.frameUpdates,
                )
                field.set(controller, missingMain)
            }
        }

        fun awaitForegroundReceipt() {
            val field = UltraThemeLayoutIntentController::class.java
                .getDeclaredField("lastForegroundRequest").apply { isAccessible = true }
            val lock = checkNotNull(UltraThemeLayoutIntentController::class.java
                .getDeclaredField("lock").apply { isAccessible = true }.get(controller))
            fun recorded(): Boolean = synchronized(lock) { field.get(controller) != null }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!recorded() && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue("Request result was not retained by the exact scene controller",
                recorded())
        }

        override fun close() = (raw as Closeable).close()
    }

    private class RecordingPort(
        private val guardEntered: CountDownLatch? = null,
        private val guardRelease: CountDownLatch? = null,
        private val firstState: UiSyncForegroundRequestState =
            UiSyncForegroundRequestState.SENT_UNCONFIRMED,
    ) : UiSyncForegroundRequestPort {
        @Volatile
        private var readyGeneration = newReadyGeneration()
        val requests = CopyOnWriteArrayList<Pair<String, CarPlayUiSyncAccessoryCommand.RequestLayout>>()
        val guards = CopyOnWriteArrayList<UiSyncForegroundSceneGuard>()
        val receipts = CopyOnWriteArrayList<UiSyncForegroundRequestReceipt>()

        override fun currentReadyGeneration(displayId: String):
            UiSyncForegroundReadyGeneration = readyGeneration

        fun replaceReadyGeneration() {
            readyGeneration = newReadyGeneration()
        }
        override fun guardScene(
            displayId: String,
            source: UiSyncForegroundSceneUseSource,
        ): UiSyncForegroundSceneGuard? {
            val use = source.acquireUse { } ?: return null
            use.close()
            guardEntered?.countDown()
            guardRelease?.let { check(it.await(3, TimeUnit.SECONDS)) }
            return (UiSyncForegroundSceneGuard::class.java.declaredConstructors
                .single { it.parameterCount == 3 }.apply { isAccessible = true }
                .newInstance(this, displayId, source) as UiSyncForegroundSceneGuard)
                .also { guards.add(it) }
        }

        override fun requestLayout(
            displayId: String,
            command: CarPlayUiSyncAccessoryCommand.RequestLayout,
            scene: UiSyncForegroundSceneGuard,
        ): UiSyncForegroundRequestReceipt {
            requests += displayId to command
            return (UiSyncForegroundRequestReceipt::class.java.declaredConstructors
                .single { it.parameterCount == 1 }.apply { isAccessible = true }
                .newInstance(if (receipts.isEmpty()) firstState else
                    UiSyncForegroundRequestState.QUEUED) as UiSyncForegroundRequestReceipt)
                .also {
                    if (it.state == UiSyncForegroundRequestState.SENT_UNCONFIRMED)
                        setSentGeneration(it, readyGeneration)
                    receipts.add(it)
                }
        }

        fun setReceiptState(index: Int, state: UiSyncForegroundRequestState) {
            UiSyncForegroundRequestReceipt::class.java.declaredMethods.single {
                it.name.startsWith("setState") && it.parameterCount == 1
            }.apply { isAccessible = true }.invoke(receipts[index], state)
            if (state == UiSyncForegroundRequestState.SENT_UNCONFIRMED) {
                setSentGeneration(receipts[index], readyGeneration)
            }
        }

        private fun setSentGeneration(
            receipt: UiSyncForegroundRequestReceipt,
            generation: UiSyncForegroundReadyGeneration,
        ) {
            UiSyncForegroundRequestReceipt::class.java.declaredMethods.single {
                it.name.startsWith("setSentGeneration") && it.parameterCount == 1
            }.apply { isAccessible = true }.invoke(receipt, generation)
        }

        private fun newReadyGeneration(): UiSyncForegroundReadyGeneration =
            UiSyncForegroundReadyGeneration::class.java.declaredConstructors
                .single { it.parameterCount == 0 }.apply { isAccessible = true }
                .newInstance() as UiSyncForegroundReadyGeneration

        fun awaitCount(expected: Int, timeoutMillis: Long = 2_000L): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (System.nanoTime() < deadline) {
                if (requests.size >= expected) return true
                Thread.sleep(10)
            }
            return requests.size >= expected
        }
    }
}
