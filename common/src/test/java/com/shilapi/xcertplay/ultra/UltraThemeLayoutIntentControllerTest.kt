package com.shilapi.xcertplay.ultra

import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.AuthorizedUltraDisplayLeaseTestFixture
import com.shilapi.xcertplay.CompiledUiSyncDisplayAssignment
import com.shilapi.xcertplay.CompiledUiSyncLocalCanvasAssignment
import com.shilapi.xcertplay.CompiledUiSyncThemeIntent
import com.shilapi.xcertplay.CompiledUiSyncThemeProgramSelection
import com.shilapi.xcertplay.CompiledUiSyncThemeVariant
import com.shilapi.xcertplay.CompiledUiSyncSlotIngressAssignment
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncAcknowledgedRequestEcho
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncAccessoryCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEvent
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestPort
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestState
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundReadyGeneration
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundSceneGuard
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundSceneUseSource
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSource
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSubscription
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenCandidate
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestination
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestinationRole
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAssignment
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBackendLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBatch
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRequirement
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRoute
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputSessionContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalTargetLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalTargetVersion
import com.shilapi.xcertplay.media.AndroidVideoSurfaceRetirementResult
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendPhase
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendRetirementResult
import com.shilapi.xcertplay.ultra.egl.UltraEglIngressKey
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalFirstFrameExpectation
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalFirstFrameReceipt
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalLayerDraw
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderResult
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import com.shilapi.xcertplay.ultra.egl.UltraEglResolvedSubmission
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHubLimits
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramInstruction
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemePrimarySelection
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneState
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralSource
import com.shilapi.xcertplay.vehicle.ultra.theme.DisplayType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import sun.misc.Unsafe

class UltraThemeLayoutIntentControllerTest {
    @Test
    fun firstSignedSceneUsesPositiveRevisionThenSelectionsStayMonotonic() {
        Fixture(armBeforeOutputs = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertEquals(1L, fixture.controller.stateSnapshot().lastSubmittedRevision)
            assertEquals(1L, fixture.backend.submissions.single().revision)
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(2L, fixture.controller.stateSnapshot().lastSubmittedRevision)
            assertEquals(listOf(1L, 2L), fixture.backend.submissions.map { it.revision })
            // Re-selecting the already submitted signed intent must not reuse a later revision.
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(2L, fixture.controller.stateSnapshot().lastSubmittedRevision)
        }
    }

    @Test
    fun signedRevisionOverflowRejectsBeforeAnySceneSubmission() {
        Fixture(armBeforeOutputs = true).use { fixture ->
            AndroidUltraRendererSessionCoordinator::class.java
                .getDeclaredField("lastSubmittedRenderRevision").apply {
                    isAccessible = true
                }.setLong(fixture.coordinator, Long.MAX_VALUE)
            assertThrows(IllegalStateException::class.java) { fixture.activateArmed() }
            assertTrue(fixture.backend.submissions.isEmpty())
        }
    }

    @Test
    fun standaloneMainPaletteMismatchWithoutForegroundSceneCoversBeforeReturnAndReproves() {
        val begins = AtomicInteger()
        val covers = AtomicInteger()
        val resumes = AtomicInteger()
        Fixture(armBeforeOutputs = true,
            beginSemanticCover = { _, displayId, _ ->
                assertEquals(UltraUiSyncDisplayId("main-ui"), displayId)
                begins.incrementAndGet()
                MainSemanticPhysicalCoverTicket {
                    covers.incrementAndGet()
                    true
                }
            },
            resumeSemanticCover = { _, displayId, _ ->
                assertEquals(UltraUiSyncDisplayId("main-ui"), displayId)
                resumes.incrementAndGet()
                true
            },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            val firstRevision = requireNotNull(fixture.controller.stateSnapshot()
                .lastSubmittedRevision)
            assertEquals(1, fixture.backend.submissions.size)
            // No foreground port was installed; raw5 scene absence must not retain MAIN UI.
            fixture.sendPalette(fixture.mainId, "main-a", "palette-b")
            assertEquals(1, begins.get())
            assertEquals(1, covers.get())
            assertFalse(fixture.controller.stateSnapshot().activated)
            assertEquals(1, fixture.backend.submissions.size)
            assertEquals(UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                fixture.activateArmed())
            assertEquals(1, fixture.backend.submissions.size)

            // Type-2 alone cannot select an alternate. Even the same signed type-3 A must
            // produce a new EGL revision before the Authority may permit a new MAIN frame.
            fixture.send(fixture.mainId, "main-a", "palette-a")
            assertEquals(2, fixture.backend.submissions.size)
            assertTrue(requireNotNull(fixture.controller.stateSnapshot()
                .lastSubmittedRevision) > firstRevision)
            assertEquals(1, resumes.get())
            assertTrue(fixture.controller.stateSnapshot().activated)
        }
    }

    @Test
    fun mainPaletteEventWithoutSignedInitialIntentCoversOnlyAfterPhysicalSubmission() {
        val covers = AtomicInteger()
        Fixture(armBeforeOutputs = true, noMainPrimaryIntent = true,
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket {
                    covers.incrementAndGet()
                    true
                }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertEquals(1, fixture.backend.submissions.size)
            fixture.sendPalette(fixture.mainId, "main-a", "palette-b")
            assertEquals(1, covers.get())
            assertFalse(fixture.controller.stateSnapshot().activated)
            assertEquals(1, fixture.backend.submissions.size)
        }
        val absentCovers = AtomicInteger()
        Fixture(armBeforeOutputs = true, noMainPrimaryIntent = true,
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket {
                    absentCovers.incrementAndGet()
                    true
                }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            fixture.sendPalette(fixture.mainId, "main-a", "palette-b")
            assertEquals(0, absentCovers.get())
            assertEquals(0, fixture.backend.submissions.size)
        }
        val clusterCovers = AtomicInteger()
        Fixture(armBeforeOutputs = true, noMainPrimaryIntent = true,
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket {
                    clusterCovers.incrementAndGet()
                    true
                }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.sendPalette(fixture.clusterId, "cluster-a", "palette-b")
            assertEquals(0, clusterCovers.get())
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun unrelatedPaletteEventsDoNotCoverMainPhysicalTarget() {
        val covers = AtomicInteger()
        Fixture(armBeforeOutputs = true,
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket {
                    covers.incrementAndGet()
                    true
                }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.sendPalette(fixture.clusterId, "cluster-a", "palette-b")
            fixture.sendPalette(fixture.mainId, "main-b", "palette-b")
            fixture.sendPalette(fixture.mainId, "main-a", "palette-a")
            assertEquals(0, covers.get())
            assertTrue(fixture.controller.stateSnapshot().activated)
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun paletteBoundaryDoesNotSubmitQueuedType3UntilExactMainCoverAcknowledges() {
        val coverEntered = CountDownLatch(1)
        val releaseCover = CountDownLatch(1)
        val phaseOne = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)
        try {
            Fixture(armBeforeOutputs = true,
                beginSemanticCover = { _, _, _ ->
                    phaseOne.set(true)
                    MainSemanticPhysicalCoverTicket {
                        coverEntered.countDown()
                        check(releaseCover.await(3, TimeUnit.SECONDS))
                        true
                    }
                },
                resumeSemanticCover = { _, _, _ -> true },
            ).use { fixture ->
                assertEquals(UltraThemeLayoutActivationResult.SUBMITTED,
                    fixture.activateArmed())
                val firstRevision = requireNotNull(fixture.controller.stateSnapshot()
                    .lastSubmittedRevision)
                val boundary = pool.submit {
                    fixture.sendPalette(fixture.mainId, "main-a", "palette-b")
                }
                assertTrue(coverEntered.await(2, TimeUnit.SECONDS))
                assertTrue(phaseOne.get())
                assertFalse(fixture.controller.stateSnapshot().activated)
                assertEquals(1, fixture.backend.submissions.size)
                val queued = pool.submit {
                    fixture.send(fixture.mainId, "main-a", "palette-a")
                }
                assertEquals(1, fixture.backend.submissions.size)
                assertFalse(boundary.isDone)
                releaseCover.countDown()
                boundary.get(3, TimeUnit.SECONDS)
                queued.get(3, TimeUnit.SECONDS)
                assertEquals(2, fixture.backend.submissions.size)
                assertTrue(requireNotNull(fixture.controller.stateSnapshot()
                    .lastSubmittedRevision) > firstRevision)
            }
        } finally {
            releaseCover.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun unacknowledgedMainPaletteCoverFailsClosedWithoutNewSceneOrRawFive() {
        val attempts = AtomicInteger()
        Fixture(armBeforeOutputs = true,
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket {
                    attempts.incrementAndGet()
                    false
                }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.sendPalette(fixture.mainId, "main-a", "palette-b")
            assertEquals(3, attempts.get())
            assertTrue(fixture.controller.stateSnapshot().failed)
            assertFalse(fixture.controller.stateSnapshot().activated)
            assertEquals(1, fixture.backend.submissions.size)
            fixture.send(fixture.mainId, "main-a", "palette-a")
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun rejectedReplayAfterMainPaletteBoundaryStillCompletesExactCover() {
        val covers = AtomicInteger()
        Fixture(armBeforeOutputs = true,
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket {
                    covers.incrementAndGet()
                    true
                }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.sendPalette(fixture.mainId, "main-a", "palette-b", emit = false)
            fixture.source.sendMalformed(fixture.mainId)
            assertTrue(fixture.controller.stateSnapshot().failed)
            assertEquals(1, covers.get())
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun malformedOnlyMainReplayCoversExactTargetBeforeTerminalCallbackReturns() {
        val covers = AtomicInteger()
        Fixture(armBeforeOutputs = true,
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket {
                    covers.incrementAndGet()
                    true
                }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.source.sendMalformed(fixture.mainId)
            assertTrue(fixture.controller.stateSnapshot().failed)
            assertEquals(1, covers.get())
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun focusOnlyMainEventDoesNotCoverPhysicalScene() {
        val covers = AtomicInteger()
        Fixture(armBeforeOutputs = true,
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket {
                    covers.incrementAndGet()
                    true
                }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.GIVE_FOCUS,
                mapOf("tokenID" to 4L))
            assertEquals(0, covers.get())
            assertTrue(fixture.controller.stateSnapshot().activated)
        }
    }

    @Test
    fun validMainType3SwitchCoversBeforeReplacementEglWhileSameChoiceAndClusterDoNot() {
        val covers = AtomicInteger()
        val eglEntered = CountDownLatch(1)
        val releaseEgl = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            Fixture(armBeforeOutputs = true,
                beginSemanticCover = { _, _, _ ->
                    MainSemanticPhysicalCoverTicket {
                        covers.incrementAndGet()
                        true
                    }
                },
                resumeSemanticCover = { _, _, _ -> true },
            ).use { fixture ->
                assertEquals(UltraThemeLayoutActivationResult.SUBMITTED,
                    fixture.activateArmed())
                fixture.backend.onSubmit = {
                    if (fixture.backend.submissions.size == 2) {
                        eglEntered.countDown()
                        check(releaseEgl.await(5, TimeUnit.SECONDS))
                    }
                }
                val switched = pool.submit {
                    fixture.send(fixture.mainId, "main-b", "palette-b")
                }
                assertTrue(eglEntered.await(2, TimeUnit.SECONDS))
                assertEquals("MAIN cover must ACK before B enters EGL", 1, covers.get())
                assertFalse(switched.isDone)
                releaseEgl.countDown()
                switched.get(3, TimeUnit.SECONDS)
                assertEquals(2, fixture.backend.submissions.size)
                fixture.send(fixture.mainId, "main-b", "palette-b")
                fixture.send(fixture.clusterId, "cluster-b", "palette-b")
                assertEquals(1, covers.get())
            }
        } finally {
            releaseEgl.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun busyParentProbeCoversReceivedMainChoiceBeforeDeferringReplay() {
        val covers = AtomicInteger()
        Fixture(armBeforeOutputs = true,
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket {
                    covers.incrementAndGet()
                    true
                }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            val enteredProbe = CountDownLatch(1)
            val releaseProbe = CountDownLatch(1)
            val retriedSubmit = CountDownLatch(1)
            val probeFailure = AtomicReference<Throwable?>()
            fixture.backend.onSubmit = { retriedSubmit.countDown() }
            fixture.lease.setNextParentProbeHook {
                enteredProbe.countDown()
                check(releaseProbe.await(5, TimeUnit.SECONDS))
            }
            val probe = Thread {
                try {
                    check(fixture.lease.probeParentLease())
                } catch (problem: Throwable) {
                    probeFailure.set(problem)
                }
            }.apply { isDaemon = true; start() }
            try {
                assertTrue(enteredProbe.await(5, TimeUnit.SECONDS))
                fixture.send(fixture.clusterId, "cluster-b", "palette-b")
                assertEquals(0, covers.get())
                fixture.send(fixture.mainId, "main-b", "palette-b")
                assertEquals(1, covers.get())
                assertFalse(fixture.controller.stateSnapshot().activated)
                assertEquals(1, fixture.backend.submissions.size)
            } finally {
                releaseProbe.countDown()
            }
            probe.join(5_000)
            assertFalse(probe.isAlive)
            probeFailure.get()?.let { throw AssertionError("Parent probe failed", it) }
            assertTrue(retriedSubmit.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (fixture.controller.stateSnapshot().selectedLayoutIndices[fixture.mainId] != 1 &&
                System.nanoTime() < deadline
            ) Thread.sleep(5L)
            assertTrue(fixture.backend.submissions.size >= 2)
            fixture.assertSubmittedLayouts(1, 1)
        }
    }

    @Test
    fun closeDuringExactSemanticCoverCannotSkipItsPendingHostAck() {
        val enteredCover = CountDownLatch(1)
        val releaseCover = CountDownLatch(1)
        val covers = AtomicInteger()
        val pool = Executors.newSingleThreadExecutor()
        try {
            Fixture(armBeforeOutputs = true,
                beginSemanticCover = { _, _, _ ->
                    MainSemanticPhysicalCoverTicket {
                        enteredCover.countDown()
                        check(releaseCover.await(10, TimeUnit.SECONDS))
                        covers.incrementAndGet()
                        true
                    }
                },
                resumeSemanticCover = { _, _, _ -> true },
            ).use { fixture ->
                assertEquals(UltraThemeLayoutActivationResult.SUBMITTED,
                    fixture.activateArmed())
                val pending = pool.submit {
                    fixture.sendPalette(fixture.mainId, "main-a", "palette-b")
                }
                assertTrue(enteredCover.await(2, TimeUnit.SECONDS))
                val closeWhileCallbackInFlight = runCatching {
                    fixture.controller.close()
                }
                assertTrue(closeWhileCallbackInFlight.isFailure)
                assertFalse(pending.isDone)
                releaseCover.countDown()
                pending.get(3, TimeUnit.SECONDS)
                assertEquals(1, covers.get())
                assertTrue(fixture.controller.stateSnapshot().closed)
                assertEquals(1, fixture.backend.submissions.size)
            }
        } finally {
            releaseCover.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun opaqueMainSemanticEventsCoverWithoutForegroundSceneAndAwaitSignedType3() {
        val cases: List<Pair<CarPlayUiSyncCommandType, Map<String, Any?>>> = listOf(
            CarPlayUiSyncCommandType.TRANSITION_START to emptyMap(),
            CarPlayUiSyncCommandType.TRANSITION_DATA to
                mapOf("regions" to emptyList<Any>(),
                    "presentation_host_time" to 1L,
                    "presentation_ntp_time" to 2L,
                    "current_ntp_time" to 3L),
            CarPlayUiSyncCommandType.TRANSITION_END to
                mapOf("regions" to emptyList<Any>(), "current_ntp_time" to 3L),
            CarPlayUiSyncCommandType.END_LAYOUT_CHANGE to emptyMap(),
            CarPlayUiSyncCommandType.METADATA_TRANSFER to
                mapOf("isTransitioning" to true),
            CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE to
                mapOf("preference" to 1),
            CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START to
                mapOf("targetAppearance" to 1),
            CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END to
                mapOf("targetAppearance" to 1),
            CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE to
                mapOf("configurations" to mapOf(
                    "sport" to mapOf("colorPaletteId" to "palette-b"),
                ), "linked" to false),
        )
        cases.forEach { (type, data) ->
            val covers = AtomicInteger()
            Fixture(armBeforeOutputs = true,
                beginSemanticCover = { _, _, _ ->
                    MainSemanticPhysicalCoverTicket {
                        covers.incrementAndGet()
                        true
                    }
                },
                resumeSemanticCover = { _, _, _ -> true },
            ).use { fixture ->
                assertEquals(type.name, UltraThemeLayoutActivationResult.SUBMITTED,
                    fixture.activateArmed())
                fixture.sendRaw(fixture.mainId, type, data)
                assertEquals(type.name, 1, covers.get())
                assertFalse(type.name, fixture.controller.stateSnapshot().activated)
                assertEquals(type.name, 1, fixture.backend.submissions.size)
                assertEquals(type.name,
                    UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                    fixture.activateArmed())
                assertEquals(type.name, 1, fixture.backend.submissions.size)
            }
        }
    }

    @Test
    fun unsupportedMainType3CoversOldSceneUntilNewIssuedChoice() {
        val covers = AtomicInteger()
        Fixture(armBeforeOutputs = true,
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket {
                    covers.incrementAndGet()
                    true
                }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            val oldRevision = requireNotNull(fixture.controller.stateSnapshot()
                .lastSubmittedRevision)
            fixture.send(fixture.mainId, "unsigned-layout", "palette-b")
            assertEquals(1, covers.get())
            assertFalse(fixture.controller.stateSnapshot().activated)
            assertEquals(1, fixture.backend.submissions.size)
            assertEquals(UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                fixture.activateArmed())
            fixture.send(fixture.mainId, "main-a", "palette-a")
            assertEquals(2, fixture.backend.submissions.size)
            assertTrue(requireNotNull(fixture.controller.stateSnapshot()
                .lastSubmittedRevision) > oldRevision)
        }
    }

    @Test
    fun appearanceStartAndEndNeedLaterSignedType3NotTheEndPacketAlone() {
        val covers = AtomicInteger()
        Fixture(armBeforeOutputs = true,
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket {
                    covers.incrementAndGet()
                    true
                }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.sendRaw(fixture.mainId,
                CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START,
                mapOf("targetAppearance" to 1))
            fixture.send(fixture.mainId, "main-a", "palette-a")
            assertEquals(1, fixture.backend.submissions.size)
            fixture.sendRaw(fixture.mainId,
                CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END,
                mapOf("targetAppearance" to 1))
            assertEquals(1, fixture.backend.submissions.size)
            fixture.send(fixture.mainId, "main-a", "palette-a")
            assertEquals(2, fixture.backend.submissions.size)
            assertEquals(2, covers.get())
        }
    }

    @Test
    fun clusterOpaqueSemanticEventsNeverCoverMain() {
        val cases: List<Pair<CarPlayUiSyncCommandType, Map<String, Any?>>> = listOf(
            CarPlayUiSyncCommandType.TRANSITION_START to emptyMap(),
            CarPlayUiSyncCommandType.TRANSITION_DATA to
                mapOf("regions" to emptyList<Any>(),
                    "presentation_host_time" to 1L,
                    "presentation_ntp_time" to 2L,
                    "current_ntp_time" to 3L),
            CarPlayUiSyncCommandType.TRANSITION_END to
                mapOf("regions" to emptyList<Any>(), "current_ntp_time" to 3L),
            CarPlayUiSyncCommandType.END_LAYOUT_CHANGE to emptyMap(),
            CarPlayUiSyncCommandType.METADATA_TRANSFER to
                mapOf("isTransitioning" to true),
            CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE to
                mapOf("preference" to 1),
            CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START to
                mapOf("targetAppearance" to 1),
            CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE to
                mapOf("configurations" to mapOf(
                    "sport" to mapOf("colorPaletteId" to "palette-b"),
                ), "linked" to false),
        )
        cases.forEach { (type, data) ->
            val covers = AtomicInteger()
            Fixture(armBeforeOutputs = true,
                beginSemanticCover = { _, _, _ ->
                    MainSemanticPhysicalCoverTicket {
                        covers.incrementAndGet()
                        true
                    }
                },
                resumeSemanticCover = { _, _, _ -> true },
            ).use { fixture ->
                assertEquals(UltraThemeLayoutActivationResult.SUBMITTED,
                    fixture.activateArmed())
                fixture.sendRaw(fixture.clusterId, type, data)
                assertEquals(type.name, 0, covers.get())
                assertEquals(type.name, 1, fixture.backend.submissions.size)
            }
        }
    }

    @Test
    fun initialSignedLocalCanvasFrameUsesRegisteredPainterInsteadOfDiagnosticFallback() {
        val painted = CountDownLatch(1)
        val custom: (Canvas, PixelSize) -> Unit = { _, _ -> painted.countDown() }
        Fixture(withLocalCanvasSlots = true, armBeforeOutputs = true,
            customLocalCanvasPainter = custom,
            diagnosticFallbackEnabled = false).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue("The signed custom painter was not invoked for the first frame",
                painted.await(5, TimeUnit.SECONDS))
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun earlyCustomDrawIsNotExposedUntilInitialLocalFrameIsProven() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            Fixture(withLocalCanvasSlots = true, armBeforeOutputs = true,
                proveLocalCanvas = true, firstPostEntered = entered,
                firstPostRelease = release).use { fixture ->
                assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertNull(fixture.controller.currentSignedLocalCanvasHandle(
                    UltraContentId("canvas.main.a")))
                assertFalse(fixture.controller.stateSnapshot().failed)
                release.countDown()
                fixture.awaitLocalProof(1L)
                assertTrue(fixture.controller.currentSignedLocalCanvasHandle(
                    UltraContentId("canvas.main.a")) != null)
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun provenMainCarriesAcrossClusterLayoutSwitchWithoutNewMainPost() {
        Fixture(withLocalCanvasSlots = true, armBeforeOutputs = true,
            proveLocalCanvas = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            val first = fixture.awaitLocalProof(1L)
            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            val second = fixture.awaitLocalProof(2L)
            assertEquals(2, fixture.backend.submissions.size)
            assertEquals(first.postSequences[UltraEglIngressKey.Local(
                UltraContentId("canvas.main.a"))], second.postSequences[UltraEglIngressKey.Local(
                UltraContentId("canvas.main.a"))])
            assertEquals(1, fixture.controller.stateSnapshot()
                .selectedLayoutIndices.getValue(fixture.clusterId))
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun v3SameLayoutVariantsBindExactComponentPaletteAndRetireStalePainter() {
        Fixture(withLocalCanvasSlots = true, withCarouselVariants = true,
            armBeforeOutputs = true, proveLocalCanvas = true,
            customLocalCanvasPainter = { _, _ -> }).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.awaitLocalProof(1L)
            val first = checkNotNull(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.bench")))
            assertTrue(first.admitted())

            fixture.send(fixture.mainId, "main-a", "palette-a",
                mapOf("main-carousel-zone" to "media"))
            fixture.awaitLocalProof(2L)
            assertFalse("Old component painter retained scene authority", first.admitted())
            assertNull(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.bench")))
            assertTrue(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.carousel.media")) != null)
            assertEquals(0, fixture.controller.stateSnapshot()
                .selectedLayoutIndices.getValue(fixture.mainId))

            fixture.send(fixture.mainId, "main-a", "palette-b",
                mapOf("main-carousel-zone" to "map"))
            fixture.awaitLocalProof(3L)
            assertNull(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.carousel.media")))
            assertTrue(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.carousel.map.alt")) != null)
            val lastRevision = fixture.controller.stateSnapshot().lastSubmittedRevision
            fixture.send(fixture.mainId, "main-a", "palette-a",
                mapOf("main-carousel-zone" to "not-issued"))
            fixture.send(fixture.mainId, "main-a", "not-issued",
                mapOf("main-carousel-zone" to "map"))
            assertEquals(lastRevision, fixture.controller.stateSnapshot().lastSubmittedRevision)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun standaloneType16SelectsOnlyIssuedV3PaletteAfterExactMainCover() {
        val covers = AtomicInteger()
        val resumes = AtomicInteger()
        val port = CountingForegroundPort()
        Fixture(withLocalCanvasSlots = true, withCarouselVariants = true,
            armBeforeOutputs = true, proveLocalCanvas = true,
            customLocalCanvasPainter = { _, _ -> }, foregroundRequests = port,
            beginSemanticCover = { _, id, _ ->
                assertEquals(UltraUiSyncDisplayId("main-ui"), id)
                MainSemanticPhysicalCoverTicket {
                    covers.incrementAndGet()
                    true
                }
            },
            resumeSemanticCover = { _, id, _ ->
                assertEquals(UltraUiSyncDisplayId("main-ui"), id)
                assertEquals(resumes.get() + 1, covers.get())
                resumes.incrementAndGet()
                true
            },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.awaitLocalProof(1L)
            assertTrue(port.awaitRequests(1))
            val prior = requireNotNull(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.bench")))

            // The phone sends only type-16. No type-3 is synthesized to pick palette-b.
            fixture.sendPalette(fixture.mainId, "main-a", "palette-b")
            assertEquals(1, covers.get())
            assertEquals(1, resumes.get())
            assertEquals(2, fixture.backend.submissions.size)
            fixture.awaitLocalProof(2L)
            assertFalse(prior.admitted())
            assertNull(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.bench")))
            assertTrue(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.carousel.map.alt")) != null)
            assertTrue(fixture.controller.stateSnapshot().activated)
            assertEquals(1, port.requestCount.get()) // Palette event is not a raw-5 receipt.
            assertEquals(0, fixture.controller.stateSnapshot()
                .selectedLayoutIndices.getValue(fixture.clusterId))

            // A later real type-3 supersedes the palette-only provenance and can again issue
            // its own foreground request after a new covered, signed scene is proven.
            fixture.send(fixture.mainId, "main-a", "palette-a",
                mapOf("main-carousel-zone" to "map"))
            fixture.awaitLocalProof(3L)
            assertEquals(2, covers.get())
            assertEquals(2, resumes.get())
            assertTrue(port.awaitRequests(2))
            assertTrue(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.bench")) != null)
        }
    }

    @Test
    fun standaloneType16BeforeAnySubmittedMainCannotSelectImplicitInitialLayout() {
        Fixture(withLocalCanvasSlots = true, withCarouselVariants = true,
            armBeforeOutputs = true, proveLocalCanvas = true,
            customLocalCanvasPainter = { _, _ -> }).use { fixture ->
            fixture.sendPalette(fixture.mainId, "main-a", "palette-b")
            assertEquals(UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                fixture.activateArmed())
            assertTrue(fixture.backend.submissions.isEmpty())
            assertFalse(fixture.controller.stateSnapshot().activated)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun standaloneType16CannotBorrowPaletteVariantForDifferentActiveComponents() {
        val covers = AtomicInteger()
        Fixture(withLocalCanvasSlots = true, withCarouselVariants = true,
            armBeforeOutputs = true, proveLocalCanvas = true,
            customLocalCanvasPainter = { _, _ -> },
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket { covers.incrementAndGet(); true }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.awaitLocalProof(1L)
            fixture.send(fixture.mainId, "main-a", "palette-a",
                mapOf("main-carousel-zone" to "media"))
            fixture.awaitLocalProof(2L)
            val before = fixture.backend.submissions.size
            val priorCovers = covers.get()

            // Only map+palette-b was signed; type-16 cannot silently change media into map.
            fixture.sendPalette(fixture.mainId, "main-a", "palette-b")
            assertEquals(priorCovers + 1, covers.get())
            assertEquals(before, fixture.backend.submissions.size)
            assertFalse(fixture.controller.stateSnapshot().activated)
            assertEquals(UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                fixture.activateArmed())
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun standaloneType16WaitsForHostCoverAckBeforeSubmittingSignedPalette() {
        val coverEntered = CountDownLatch(1)
        val releaseCover = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            Fixture(withLocalCanvasSlots = true, withCarouselVariants = true,
                armBeforeOutputs = true, proveLocalCanvas = true,
                customLocalCanvasPainter = { _, _ -> },
                beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                    coverEntered.countDown()
                    check(releaseCover.await(3L, TimeUnit.SECONDS))
                    true
                } },
                resumeSemanticCover = { _, _, _ -> true },
            ).use { fixture ->
                assertEquals(UltraThemeLayoutActivationResult.SUBMITTED,
                    fixture.activateArmed())
                fixture.awaitLocalProof(1L)
                val change = pool.submit {
                    fixture.sendPalette(fixture.mainId, "main-a", "palette-b")
                }
                assertTrue(coverEntered.await(2L, TimeUnit.SECONDS))
                assertEquals(1, fixture.backend.submissions.size)
                assertFalse(fixture.controller.stateSnapshot().activated)
                releaseCover.countDown()
                change.get(3L, TimeUnit.SECONDS)
                assertEquals(2, fixture.backend.submissions.size)
                fixture.awaitLocalProof(2L)
            }
        } finally {
            releaseCover.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun coalescedType16ChoicesNeverSubmitStalePaletteOrAffectOtherDisplay() {
        val covers = AtomicInteger()
        Fixture(withLocalCanvasSlots = true, withCarouselVariants = true,
            armBeforeOutputs = true, proveLocalCanvas = true,
            customLocalCanvasPainter = { _, _ -> },
            beginSemanticCover = { _, _, _ ->
                MainSemanticPhysicalCoverTicket { covers.incrementAndGet(); true }
            },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.awaitLocalProof(1L)
            fixture.sendPalette(fixture.clusterId, "cluster-a", "palette-b")
            assertEquals(0, covers.get())
            assertEquals(1, fixture.backend.submissions.size)

            // The first palette is superseded before replay. It must not get a transient
            // signed scene or a foreground request, even though that variant is issued.
            fixture.sendPalette(fixture.mainId, "main-a", "palette-b", emit = false)
            fixture.sendPalette(fixture.mainId, "main-a", "palette-a")
            fixture.awaitLocalProof(2L)
            assertEquals(1, covers.get())
            assertEquals(2, fixture.backend.submissions.size)
            assertTrue(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.bench")) != null)
            assertNull(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.carousel.map.alt")))
            assertEquals(0, fixture.controller.stateSnapshot()
                .selectedLayoutIndices.getValue(fixture.clusterId))

            fixture.controller.close()
            fixture.sendPalette(fixture.mainId, "main-a", "palette-b")
            assertEquals(2, fixture.backend.submissions.size)
            assertTrue(fixture.controller.stateSnapshot().closed)
        }
    }

    @Test
    fun phoneEpochResetCoversOldPaletteAndNeedsFreshType3NotOldReplayOrType16() {
        val covers = AtomicInteger()
        val port = CountingForegroundPort()
        Fixture(withLocalCanvasSlots = true, withCarouselVariants = true,
            armBeforeOutputs = true, proveLocalCanvas = true,
            customLocalCanvasPainter = { _, _ -> }, foregroundRequests = port,
            beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                covers.incrementAndGet()
                true
            } },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.awaitLocalProof(1L)
            assertTrue(port.awaitRequests(1))
            fixture.sendPalette(fixture.mainId, "main-a", "palette-b")
            fixture.awaitLocalProof(2L)
            val oldSnapshot = fixture.source.snapshot(fixture.mainId.value)
            val previousSubmissions = fixture.backend.submissions.size

            assertTrue(fixture.controller.invalidatePhoneEpoch(
                fixture.mainId, oldSnapshot.revision))
            assertFalse(fixture.controller.stateSnapshot().activated)
            fixture.source.replay(fixture.mainId, oldSnapshot)
            fixture.sendPalette(fixture.mainId, "main-a", "palette-a")
            assertEquals(previousSubmissions, fixture.backend.submissions.size)
            assertEquals(1, port.requestCount.get())
            assertEquals(UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                fixture.activateArmed())
            assertTrue(covers.get() >= 2) // Palette switch and reset had exact cover tickets.

            fixture.send(fixture.mainId, "main-a", "palette-a",
                mapOf("main-carousel-zone" to "map"))
            fixture.awaitLocalProof(3L)
            assertEquals(previousSubmissions + 1, fixture.backend.submissions.size)
            assertTrue(port.awaitRequests(2))
            assertTrue(fixture.controller.stateSnapshot().activated)
        }
    }

    private fun awaitCondition(timeoutMillis: Long = 3_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5L)
        return condition()
    }

    @Test
    fun attestedAssetlessRequestEchoActsAsTheRequestedSignedType3() {
        val covers = AtomicInteger()
        val resumes = AtomicInteger()
        val port = CountingForegroundPort()
        Fixture(armBeforeOutputs = true, diagnosticFallbackEnabled = false,
            foregroundRequests = port,
            beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                covers.incrementAndGet()
                true
            } },
            resumeSemanticCover = { _, _, _ -> resumes.incrementAndGet(); true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue("Initial signed MAIN did not request its layout", port.awaitRequests(1))
            val request = requireNotNull(port.lastCommand.get())
            assertEquals("main-a", request.layoutID)
            val firstRevision = fixture.backend.submissions.single().revision
            port.awaitReceiptReturned()

            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                request.wireData(), echoesAcknowledgedRequestLayout = true)
            // Same downstream path as the matching type 3: a fresh scene revision and the exact
            // resume hook for that new revision. An unchanged intent needs no cover, unless a
            // concurrent foreground probe made the replay BUSY (then it covers exactly like an
            // unverified type 3 and the new revision lifts it).
            assertTrue(awaitCondition { fixture.backend.submissions.size == 2 &&
                resumes.get() == 1 })
            assertTrue(fixture.backend.submissions.last().revision > firstRevision)
            fixture.assertSubmittedLayouts(0, 0)
            assertTrue(covers.get() <= 1)
            assertTrue(fixture.controller.stateSnapshot().activated)
            assertFalse("The echo's own scene must not issue another raw5",
                port.awaitRequests(2, 250L))
            val coversBeforeRepeat = covers.get()

            // An attested echo of the already-applied signed intent (e.g. the second of two
            // overlapping identical raw5s) is a no-op: no cover, no authority, no new scene.
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                request.wireData(), echoesAcknowledgedRequestLayout = true)
            Thread.sleep(150L)
            assertEquals(coversBeforeRepeat, covers.get())
            assertEquals(2, fixture.backend.submissions.size)
            assertTrue(fixture.controller.stateSnapshot().activated)
            // An unattested repeat is still an opaque, covering type 6.
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                request.wireData())
            assertTrue(awaitCondition { covers.get() == coversBeforeRepeat + 1 })
            assertEquals(2, fixture.backend.submissions.size)
            assertEquals(UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                fixture.activateArmed())
        }
    }

    @Test
    fun phoneResetClosesAnOpenType21SoTheNewEpochEchoRecoversMain() {
        val covers = AtomicInteger()
        val resumes = AtomicInteger()
        val port = CountingForegroundPort()
        Fixture(armBeforeOutputs = true, diagnosticFallbackEnabled = false,
            foregroundRequests = port,
            beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                covers.incrementAndGet()
                true
            } },
            resumeSemanticCover = { _, _, _ -> resumes.incrementAndGet(); true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue(port.awaitRequests(1))
            val request = requireNotNull(port.lastCommand.get())
            port.awaitReceiptReturned()
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                request.wireData(), echoesAcknowledgedRequestLayout = true)
            assertTrue(awaitCondition { fixture.backend.submissions.size == 2 &&
                resumes.get() == 1 })
            // Type 21 opens; the phone then reopens its session and never sends that 22.
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START,
                mapOf("targetAppearance" to 1))
            assertTrue(awaitCondition { covers.get() >= 1 })
            assertTrue(fixture.controller.invalidatePhoneEpoch(fixture.mainId,
                fixture.source.snapshot(fixture.mainId.value).revision))
            assertTrue("No raw5 re-request after Reset with an open type 21",
                port.awaitRequests(2))
            port.awaitReceiptReturned()
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                request.wireData(), echoesAcknowledgedRequestLayout = true)
            assertTrue(awaitCondition { resumes.get() == 2 })
            assertTrue(fixture.controller.stateSnapshot().activated)
        }
    }

    @Test
    fun unauthorizedRequestEchoesStayOpaqueAndKeepMainCovered() {
        val exact = CarPlayUiSyncAccessoryCommand.RequestLayout(layoutID = "main-a").wireData()
        val cases: List<Triple<String, Map<String, Any?>, Boolean>> = listOf(
            Triple("unattested", exact, false),
            Triple("changed value", LinkedHashMap(exact).apply {
                put("fadeInNewLayout", false) }, true),
            Triple("extra key", LinkedHashMap(exact).apply {
                put("paletteID", "palette-a") }, true),
            Triple("missing key", LinkedHashMap(exact).apply {
                remove("fadeOutOldLayout") }, true),
            Triple("timed form", mapOf("regions" to emptyList<Any>(),
                "current_ntp_time" to 3L), true),
            Triple("another request", CarPlayUiSyncAccessoryCommand.RequestLayout(
                layoutID = "main-b").wireData(), true),
        )
        cases.forEach { (name, data, attested) ->
            val covers = AtomicInteger()
            val port = CountingForegroundPort()
            Fixture(armBeforeOutputs = true, diagnosticFallbackEnabled = false,
                foregroundRequests = port,
                beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                    covers.incrementAndGet()
                    true
                } },
                resumeSemanticCover = { _, _, _ -> true },
            ).use { fixture ->
                assertEquals(name, UltraThemeLayoutActivationResult.SUBMITTED,
                    fixture.activateArmed())
                assertTrue(name, port.awaitRequests(1))
                assertEquals(name, exact, requireNotNull(port.lastCommand.get()).wireData())
                port.awaitReceiptReturned()
                fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                    data, echoesAcknowledgedRequestLayout = attested)
                assertTrue(name, awaitCondition { covers.get() == 1 })
                Thread.sleep(100L)
                assertEquals(name, 1, covers.get())
                assertEquals(name, 1, fixture.backend.submissions.size)
                assertEquals(name, UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                    fixture.activateArmed())
            }
        }
    }

    @Test
    fun requestEchoWithoutOwnRequestOrAfterPhoneResetGrantsNothing() {
        val exact = CarPlayUiSyncAccessoryCommand.RequestLayout(layoutID = "main-a").wireData()
        // No foreground port: this controller never issued a raw5 for the echo to answer.
        val covers = AtomicInteger()
        Fixture(armBeforeOutputs = true, diagnosticFallbackEnabled = false,
            beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                covers.incrementAndGet()
                true
            } },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                exact, echoesAcknowledgedRequestLayout = true)
            assertEquals(1, covers.get())
            assertEquals(1, fixture.backend.submissions.size)
        }

        // A phone Reset ends the epoch of the issued request. With no new-epoch raw5 issued
        // (the port refuses a new scene guard), its late echo is not authority.
        val resetCovers = AtomicInteger()
        val resetResumes = AtomicInteger()
        val port = CountingForegroundPort()
        Fixture(armBeforeOutputs = true, diagnosticFallbackEnabled = false,
            foregroundRequests = port,
            beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                resetCovers.incrementAndGet()
                true
            } },
            resumeSemanticCover = { _, _, _ -> resetResumes.incrementAndGet(); true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue(port.awaitRequests(1))
            assertEquals(exact, requireNotNull(port.lastCommand.get()).wireData())
            port.awaitReceiptReturned()
            port.refuseGuards.set(true)
            assertTrue(fixture.controller.invalidatePhoneEpoch(fixture.mainId,
                fixture.source.snapshot(fixture.mainId.value).revision))
            assertTrue(awaitCondition { resetCovers.get() == 1 })
            // The covered Reset re-request scene is rendered, but no raw5 left the port.
            assertTrue(awaitCondition { fixture.backend.submissions.size == 2 })
            assertTrue(awaitCondition { port.guardAttempts.get() >= 1 })
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                exact, echoesAcknowledgedRequestLayout = true)
            Thread.sleep(150L)
            assertEquals(1, port.requestCount.get())
            assertEquals(2, fixture.backend.submissions.size)
            assertEquals(0, resetResumes.get())
            assertEquals(UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                fixture.activateArmed())
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun phoneResetReRequestsSignedLayoutAndNewEpochEchoRecoversMain() {
        val covers = AtomicInteger()
        val resumes = AtomicInteger()
        val port = CountingForegroundPort()
        Fixture(armBeforeOutputs = true, diagnosticFallbackEnabled = false,
            foregroundRequests = port,
            beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                covers.incrementAndGet()
                true
            } },
            resumeSemanticCover = { _, _, _ -> resumes.incrementAndGet(); true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue(port.awaitRequests(1))
            val request = requireNotNull(port.lastCommand.get())
            port.awaitReceiptReturned()
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                request.wireData(), echoesAcknowledgedRequestLayout = true)
            assertTrue(awaitCondition { fixture.backend.submissions.size == 2 &&
                resumes.get() == 1 })

            val coversBefore = covers.get()
            assertTrue(fixture.controller.invalidatePhoneEpoch(fixture.mainId,
                fixture.source.snapshot(fixture.mainId.value).revision))
            assertTrue(awaitCondition { covers.get() == coversBefore + 1 })
            // New epoch: a covered fresh revision of the same signed scene issues raw5 again.
            assertTrue("No raw5 re-request after Reset", port.awaitRequests(2))
            assertEquals(request.wireData(), requireNotNull(port.lastCommand.get()).wireData())
            assertEquals(3, fixture.backend.submissions.size)
            assertEquals("The re-request scene must stay covered", 1, resumes.get())
            port.awaitReceiptReturned()

            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                request.wireData(), echoesAcknowledgedRequestLayout = true)
            assertTrue(awaitCondition { fixture.backend.submissions.size == 4 &&
                resumes.get() == 2 })
            fixture.assertSubmittedLayouts(0, 0)
            assertTrue(fixture.controller.stateSnapshot().activated)
            assertFalse(port.awaitRequests(3, 250L))
        }
    }

    @Test
    fun standaloneType19ReprovesSameSignedSceneOnlyAfterAuthorizedLayout() {
        val covers = AtomicInteger()
        val resumes = AtomicInteger()
        val port = CountingForegroundPort()
        Fixture(armBeforeOutputs = true, diagnosticFallbackEnabled = false,
            foregroundRequests = port,
            beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                covers.incrementAndGet()
                true
            } },
            resumeSemanticCover = { _, _, _ -> resumes.incrementAndGet(); true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue(port.awaitRequests(1))
            val request = requireNotNull(port.lastCommand.get())
            port.awaitReceiptReturned()
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                request.wireData(), echoesAcknowledgedRequestLayout = true)
            assertTrue(awaitCondition { fixture.backend.submissions.size == 2 &&
                resumes.get() == 1 })
            val signedRevision = fixture.backend.submissions.last().revision

            listOf(1, 0).forEachIndexed { index, preference ->
                val coversBefore = covers.get()
                fixture.sendRaw(fixture.mainId,
                    CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
                    mapOf("preference" to preference))
                // Covered first, then the same signed scene as a fresh revision and resume.
                assertTrue(awaitCondition { covers.get() == coversBefore + 1 })
                assertTrue(awaitCondition { fixture.backend.submissions.size == 3 + index &&
                    resumes.get() == 2 + index })
                assertTrue(fixture.backend.submissions.last().revision > signedRevision)
                fixture.assertSubmittedLayouts(0, 0)
                assertTrue(fixture.controller.stateSnapshot().activated)
            }
            assertFalse("A type 19 never re-issues raw5", port.awaitRequests(2, 250L))
        }
    }

    @Test
    fun type19WithoutAnyAuthorizedSignedLayoutStaysCovered() {
        val covers = AtomicInteger()
        val resumes = AtomicInteger()
        val port = CountingForegroundPort()
        Fixture(armBeforeOutputs = true, diagnosticFallbackEnabled = false,
            foregroundRequests = port,
            beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                covers.incrementAndGet()
                true
            } },
            resumeSemanticCover = { _, _, _ -> resumes.incrementAndGet(); true },
        ).use { fixture ->
            // Initial primary scene and its raw5, but no echo or type 3 from the phone.
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue(port.awaitRequests(1))
            port.awaitReceiptReturned()
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
                mapOf("preference" to 1))
            assertTrue(awaitCondition { covers.get() == 1 })
            Thread.sleep(150L)
            assertEquals(1, fixture.backend.submissions.size)
            assertEquals(0, resumes.get())
            assertEquals(UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                fixture.activateArmed())
        }
    }

    @Test
    fun phoneResetBeforeAnySubmittedMainNeverRendersAReRequest() {
        val port = CountingForegroundPort()
        Fixture(withVideoSlots = true, armBeforeOutputs = true, publishVideoInitially = false,
            diagnosticFallbackEnabled = false, foregroundRequests = port,
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.PENDING_INPUT_STREAM,
                fixture.activateArmed())
            assertTrue(fixture.controller.invalidatePhoneEpoch(fixture.mainId,
                fixture.source.snapshot(fixture.mainId.value).revision))
            Thread.sleep(150L)
            assertEquals(0, fixture.backend.submissions.size)
            fixture.publishVideos()
            // No Authority cover was ever taken for this MAIN: it stays pending, never shown.
            assertEquals(UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                fixture.activateArmed())
            Thread.sleep(150L)
            assertEquals(0, fixture.backend.submissions.size)
            assertEquals(0, port.requestCount.get())
        }
    }

    @Test
    fun coalescedEchoAndType19ReproveTheEchoAuthorizedScene() {
        val covers = AtomicInteger()
        val resumes = AtomicInteger()
        val port = CountingForegroundPort()
        Fixture(armBeforeOutputs = true, diagnosticFallbackEnabled = false,
            foregroundRequests = port,
            beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                covers.incrementAndGet()
                true
            } },
            resumeSemanticCover = { _, _, _ -> resumes.incrementAndGet(); true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue(port.awaitRequests(1))
            val request = requireNotNull(port.lastCommand.get())
            port.awaitReceiptReturned()
            // One replay carries the echo and the type 19 that DashBoard sends right after it.
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                request.wireData(), echoesAcknowledgedRequestLayout = true, emit = false)
            fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
                mapOf("preference" to 1))
            assertTrue(awaitCondition { covers.get() == 1 })
            assertTrue(awaitCondition { fixture.backend.submissions.size == 2 &&
                resumes.get() == 1 })
            fixture.assertSubmittedLayouts(0, 0)
            assertTrue(fixture.controller.stateSnapshot().activated)
            assertFalse(port.awaitRequests(2, 250L))
        }
    }

    @Test
    fun standaloneType19FailsClosedForUnsupportedValueOverrideOrOpenTransition() {
        val cases: List<Pair<String, List<Pair<CarPlayUiSyncCommandType, Map<String, Any?>>>>> =
            listOf(
                "unsupported preference" to listOf(
                    CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE to
                        mapOf("preference" to 7)),
                "override" to listOf(
                    CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE to
                        mapOf("preference" to 1, "override" to 2)),
                "open type 21" to listOf(
                    CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START to
                        mapOf("targetAppearance" to 1),
                    CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE to
                        mapOf("preference" to 1)),
                "closed 21/22 since the layout" to listOf(
                    CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START to
                        mapOf("targetAppearance" to 1),
                    CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END to
                        mapOf("targetAppearance" to 1),
                    CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE to
                        mapOf("preference" to 1)),
            )
        cases.forEach { (name, events) ->
            val resumes = AtomicInteger()
            val port = CountingForegroundPort()
            Fixture(armBeforeOutputs = true, diagnosticFallbackEnabled = false,
                foregroundRequests = port,
                resumeSemanticCover = { _, _, _ -> resumes.incrementAndGet(); true },
            ).use { fixture ->
                assertEquals(name, UltraThemeLayoutActivationResult.SUBMITTED,
                    fixture.activateArmed())
                assertTrue(name, port.awaitRequests(1))
                val request = requireNotNull(port.lastCommand.get())
                port.awaitReceiptReturned()
                fixture.sendRaw(fixture.mainId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                    request.wireData(), echoesAcknowledgedRequestLayout = true)
                assertTrue(name, awaitCondition { fixture.backend.submissions.size == 2 &&
                    resumes.get() == 1 })
                events.forEach { (type, data) -> fixture.sendRaw(fixture.mainId, type, data) }
                Thread.sleep(150L)
                assertEquals(name, 2, fixture.backend.submissions.size)
                assertEquals(name, 1, resumes.get())
                assertFalse(name, fixture.controller.stateSnapshot().activated)
                assertEquals(name, UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                    fixture.activateArmed())
            }
        }
    }

    @Test
    fun otherDisplayPhoneEpochResetDoesNotCoverOrReplaceMain() {
        val mainCovers = AtomicInteger()
        Fixture(armBeforeOutputs = true,
            beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                mainCovers.incrementAndGet()
                true
            } },
            resumeSemanticCover = { _, _, _ -> true },
        ).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue(fixture.controller.invalidatePhoneEpoch(
                fixture.clusterId, fixture.source.snapshot(fixture.clusterId.value).revision))
            assertEquals(0, mainCovers.get())
            assertEquals(1, fixture.backend.submissions.size)
            assertTrue(fixture.controller.stateSnapshot().activated)
        }
    }

    @Test
    fun clusterEpochWitnessRejectsOldSceneAndPaletteUntilNewSignedTypeThreeIsSubmitted() {
        Fixture(armBeforeOutputs = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            assertEquals(2, fixture.backend.submissions.size)
            val oldRevision = requireNotNull(
                fixture.controller.stateSnapshot().lastSubmittedRevision)
            assertTrue(fixture.controller.clusterRecoveryWitness(fixture.clusterId, oldRevision))
            val oldSnapshot = fixture.source.snapshot(fixture.clusterId.value)

            assertTrue(fixture.controller.invalidatePhoneEpoch(
                fixture.clusterId, oldSnapshot.revision))
            assertFalse(fixture.controller.clusterRecoveryWitness(fixture.clusterId, oldRevision))
            assertFalse(fixture.controller.clusterRecoveryWitness(fixture.mainId, oldRevision))
            fixture.source.replay(fixture.clusterId, oldSnapshot)
            fixture.sendPalette(fixture.clusterId, "cluster-b", "palette-a")
            assertEquals(2, fixture.backend.submissions.size)
            assertFalse(fixture.controller.clusterRecoveryWitness(fixture.clusterId, oldRevision))

            fixture.send(fixture.clusterId, "cluster-a", "palette-a")
            assertEquals(3, fixture.backend.submissions.size)
            val freshRevision = requireNotNull(
                fixture.controller.stateSnapshot().lastSubmittedRevision)
            assertTrue(freshRevision > oldRevision)
            assertFalse(fixture.controller.clusterRecoveryWitness(fixture.clusterId, oldRevision))
            assertTrue(fixture.controller.clusterRecoveryWitness(fixture.clusterId, freshRevision))
            fixture.assertSubmittedLayouts(0, 0)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun clusterEpochSameSignedLayoutStillNeedsNewSceneRevision() {
        Fixture(armBeforeOutputs = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            val oldRevision = requireNotNull(
                fixture.controller.stateSnapshot().lastSubmittedRevision)
            assertTrue(fixture.controller.invalidatePhoneEpoch(fixture.clusterId,
                fixture.source.snapshot(fixture.clusterId.value).revision))
            fixture.send(fixture.clusterId, "cluster-a", "palette-a")
            assertEquals(2, fixture.backend.submissions.size)
            val freshRevision = requireNotNull(
                fixture.controller.stateSnapshot().lastSubmittedRevision)
            assertTrue(freshRevision > oldRevision)
            assertTrue(fixture.controller.clusterRecoveryWitness(fixture.clusterId, freshRevision))
            assertFalse(fixture.controller.clusterRecoveryWitness(fixture.clusterId, oldRevision))
            fixture.assertSubmittedLayouts(0, 0)
        }
    }

    @Test
    fun phoneEpochResetRacingControllerCloseNeverRevealsStaleMain() {
        val coverEntered = CountDownLatch(1)
        val releaseCover = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            Fixture(armBeforeOutputs = true,
                beginSemanticCover = { _, _, _ -> MainSemanticPhysicalCoverTicket {
                    coverEntered.countDown()
                    check(releaseCover.await(3L, TimeUnit.SECONDS))
                    true
                } },
                resumeSemanticCover = { _, _, _ -> true },
            ).use { fixture ->
                assertEquals(UltraThemeLayoutActivationResult.SUBMITTED,
                    fixture.activateArmed())
                val floor = fixture.source.snapshot(fixture.mainId.value).revision
                assertTrue(fixture.controller.invalidatePhoneEpoch(fixture.mainId, floor))
                assertTrue(coverEntered.await(2L, TimeUnit.SECONDS))
                assertFalse(fixture.controller.stateSnapshot().activated)
                val closing = pool.submit { fixture.controller.close() }
                assertEquals(1, fixture.backend.submissions.size)
                releaseCover.countDown()
                closing.get(3L, TimeUnit.SECONDS)
                fixture.send(fixture.mainId, "main-b", "palette-b")
                assertEquals(1, fixture.backend.submissions.size)
                assertTrue(fixture.controller.stateSnapshot().closed)
            }
        } finally {
            releaseCover.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun customUpdateThenLayoutSwitchStaysPendingWithoutKillingProvenScene() {
        Fixture(withLocalCanvasSlots = true, armBeforeOutputs = true,
            proveLocalCanvas = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.awaitLocalProof(1L)
            fixture.drawSelectedLocalCanvas("canvas.main.a")
            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            assertEquals(1, fixture.backend.submissions.size)
            assertEquals(UltraThemeLayoutActivationResult.PENDING_LOCAL_CANVAS,
                fixture.activateArmed())
            val state = fixture.controller.stateSnapshot()
            assertTrue(state.pendingLocalCanvas)
            assertFalse(state.failed)
            assertEquals(1L, state.lastSubmittedRevision)
            assertEquals(0, state.selectedLayoutIndices.getValue(fixture.clusterId))
            assertTrue(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.a")) != null)
        }
    }

    @Test
    fun nondiagnosticLocalCanvasWithoutRegisteredPainterFailsBeforeSceneSubmission() {
        Fixture(withLocalCanvasSlots = true, armBeforeOutputs = true,
            diagnosticFallbackEnabled = false).use { fixture ->
            assertThrows(IllegalStateException::class.java) { fixture.activateArmed() }
            assertEquals(0, fixture.backend.submissions.size)
        }
    }

    @Test
    fun signedEmptySlotsNeverExposeCustomDrawBeforeRealLocalFrameProof() {
        Fixture(withLocalCanvasSlots = true, armBeforeOutputs = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertNull(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.a")))
            assertEquals(UltraThemeLayoutActivationResult.ALREADY_ACTIVE, fixture.activateArmed())
            assertNull(fixture.controller.currentSignedLocalCanvasHandle(
                UltraContentId("canvas.main.a")))
            val first = requireNotNull(fixture.backend.submissions.last()
                .scene(DisplayDestination.MAIN)).layers.single()
            assertEquals(UltraSceneSource.Local(UltraContentId("canvas.main.a")), first.source)
            assertEquals(UltraPixelRect(10, 10, 100, 60), first.framePixels)
            assertEquals(first.framePixels, first.clipPixels)

            fixture.send(fixture.mainId, "main-b", "palette-b")
            val second = requireNotNull(fixture.backend.submissions.last()
                .scene(DisplayDestination.MAIN)).layers.single()
            assertEquals(UltraSceneSource.Local(UltraContentId("canvas.main.b")), second.source)
            assertEquals(UltraPixelRect(10, 10, 100, 60), second.framePixels)
            fixture.assertLocalCanvasesRetained(listOf(
                UltraContentId("canvas.main.a"), UltraContentId("canvas.main.b"),
            ))
            assertNull(fixture.controller.stateSnapshot().localFirstFrameReceipt)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun missingLocalFirstFrameProofTimesOutTerminallyInsteadOfRemainingActive() {
        Fixture(withLocalCanvasSlots = true, armBeforeOutputs = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertFalse(fixture.controller.stateSnapshot().activated)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (!fixture.controller.stateSnapshot().failed && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            assertTrue("Unproven Local Canvas first frame did not fail the renderer child",
                fixture.controller.stateSnapshot().failed)
            assertNull(fixture.controller.stateSnapshot().localFirstFrameReceipt)
            val priorSubmissions = fixture.backend.submissions.size
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(priorSubmissions, fixture.backend.submissions.size)
        }
    }

    @Test
    fun staleGraphBeforeSubmissionIsTypedAndNewGraphCanStillSubmit() {
        Fixture(withVideoSlots = true, armBeforeOutputs = true,
            publishVideoInitially = false).use { fixture ->
            val staleGraph = fixture.coordinator.graphSnapshot()
            fixture.publishVideos()
            assertThrows(StaleThemeGraphBeforeSubmissionException::class.java) {
                fixture.activateArmedWithGraph(staleGraph)
            }
            assertEquals(0, fixture.backend.submissions.size)
            assertFalse(fixture.controller.stateSnapshot().failed)
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun graphPublishedAfterFirstValidationButBeforeSceneBuildRetriesWithoutEglSubmit() {
        Fixture(withVideoSlots = true, armBeforeOutputs = true,
            publishVideoInitially = false).use { fixture ->
            // The admission's exact parent liveness probe runs after the first graph check
            // and before activateLocked's second check. Publish both InputStreams there.
            fixture.lease.setNextParentProbeHook { fixture.publishVideos() }
            assertThrows(StaleThemeGraphBeforeSubmissionException::class.java) {
                fixture.activateArmed()
            }
            assertEquals(0, fixture.backend.submissions.size)
            assertFalse(fixture.controller.stateSnapshot().failed)
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun typeThreeRaceWithGraphPublishStaysPendingRatherThanPoisoningController() {
        Fixture(withVideoSlots = true, armBeforeOutputs = true,
            publishVideoInitially = false).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.PENDING_INPUT_STREAM,
                fixture.activateArmed())
            fixture.lease.setNextParentProbeHook { fixture.publishVideos() }
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertFalse(fixture.controller.stateSnapshot().failed)
            assertFalse(fixture.controller.stateSnapshot().activated)
            assertEquals(0, fixture.backend.submissions.size)
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.assertSubmittedLayouts(1, 0)
        }
    }

    @Test
    fun initialReplaySelectsSignedLayoutBeforeOneCompleteFirstSubmission() {
        Fixture(initialMainLayout = "main-b").use { fixture ->
            assertEquals(1, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(1, 0)
            assertEquals(mapOf(fixture.mainId to 1, fixture.clusterId to 0),
                fixture.controller.stateSnapshot().selectedLayoutIndices)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun startupWaitsForOrdinaryParentProbeThenSubmitsOneReplaySelectedScene() {
        Fixture(initialMainLayout = "main-b", holdParentProbeAcrossStartup = true).use { fixture ->
            assertEquals(1, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(1, 0)
            assertEquals(mapOf(fixture.mainId to 1, fixture.clusterId to 0),
                fixture.controller.stateSnapshot().selectedLayoutIndices)
            assertFalse(fixture.controller.stateSnapshot().failed)
            assertEquals(0, fixture.coordinator.stateSnapshot().operationCount)
        }
    }

    @Test
    fun signedTypeThreeSwitchesOnlySelectedDisplayAndSubmitsCompleteMultiOutputScenes() {
        Fixture().use { fixture ->
            assertEquals(1, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(0, 0)
            assertEquals(mapOf(fixture.mainId to 0, fixture.clusterId to 0),
                fixture.controller.stateSnapshot().selectedLayoutIndices)

            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(2, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(1, 0)
            assertEquals(mapOf(fixture.mainId to 1, fixture.clusterId to 0),
                fixture.controller.stateSnapshot().selectedLayoutIndices)

            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            assertEquals(3, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(1, 1)
            assertEquals(mapOf(fixture.mainId to 1, fixture.clusterId to 1),
                fixture.controller.stateSnapshot().selectedLayoutIndices)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun alternateSignedLayoutRebindsItsOwnPublishedInputStreamSlot() {
        Fixture(withVideoSlots = true).use { fixture ->
            val first = requireNotNull(fixture.backend.submissions.last()
                .scene(DisplayDestination.MAIN)).layers.single()
            assertEquals(UltraIngressId("video-a-ingress"),
                (first.source as UltraSceneSource.Remote).ingressId)

            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(2, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(1, 0)
            val second = requireNotNull(fixture.backend.submissions.last()
                .scene(DisplayDestination.MAIN)).layers.single()
            assertEquals(UltraIngressId("video-b-ingress"),
                (second.source as UltraSceneSource.Remote).ingressId)
            assertEquals(0, requireNotNull(fixture.backend.submissions.last()
                .scene(DisplayDestination.CLUSTER)).layers.size)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun wrongPaletteLayoutOrNonemptyComponentsNeverSelectAnUnsignedScene() {
        Fixture().use { fixture ->
            fixture.send(fixture.mainId, "main-b", "wrong-palette")
            fixture.send(fixture.mainId, "unsigned-layout", "palette-b")
            fixture.send(fixture.mainId, "main-b", "palette-b",
                mapOf("zone" to "component"))
            assertEquals(1, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(0, 0)
            assertFalse(fixture.controller.stateSnapshot().failed)

            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(2, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(1, 0)
        }
    }

    @Test
    fun exactDuplicateAndOlderReplayDoNotEnqueueAgain() {
        Fixture().use { fixture ->
            val old = fixture.source.snapshot(fixture.mainId.value)
            fixture.send(fixture.mainId, "main-b", "palette-b")
            val changed = fixture.source.snapshot(fixture.mainId.value)
            assertEquals(2, fixture.backend.submissions.size)

            fixture.source.replay(fixture.mainId, changed)
            fixture.source.replay(fixture.mainId, old)
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(2, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(1, 0)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun typeThreeCallbackReenteringBackendSubmitIsDeferredUntilFirstSceneFinishes() {
        Fixture().use { fixture ->
            var injected = false
            fixture.backend.onSubmit = {
                if (!injected) {
                    injected = true
                    fixture.send(fixture.mainId, "main-a", "palette-a")
                }
            }
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertTrue(injected)
            assertEquals(3, fixture.backend.submissions.size)
            assertEquals(1, fixture.backend.maximumSubmitDepth)
            assertEquals(fixture.backend.submissions.map { it.revision }.distinct().size,
                fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(0, 0)
            assertEquals(0, fixture.controller.stateSnapshot()
                .selectedLayoutIndices.getValue(fixture.mainId))
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun busyParentProbeDefersTypeThreeAndRetriesOnlyLatestSignedIntent() {
        Fixture().use { fixture ->
            val enteredProbe = CountDownLatch(1)
            val releaseProbe = CountDownLatch(1)
            val retriedSubmit = CountDownLatch(1)
            val probeFailure = AtomicReference<Throwable?>()
            fixture.backend.onSubmit = { retriedSubmit.countDown() }
            fixture.lease.setNextParentProbeHook {
                enteredProbe.countDown()
                check(releaseProbe.await(5, TimeUnit.SECONDS)) {
                    "Test parent probe was not released"
                }
            }
            val probe = Thread {
                try {
                    check(fixture.lease.probeParentLease())
                } catch (failure: Throwable) {
                    probeFailure.set(failure)
                }
            }.apply { isDaemon = true; start() }
            try {
                assertTrue(enteredProbe.await(5, TimeUnit.SECONDS))
                fixture.send(fixture.mainId, "main-b", "palette-b")
                fixture.send(fixture.mainId, "main-a", "palette-a")
                fixture.send(fixture.mainId, "main-b", "palette-b")
                assertEquals(1, fixture.backend.submissions.size)
                assertFalse(fixture.controller.stateSnapshot().failed)
                assertTrue(fixture.controller.stateSnapshot().activated)
            } finally {
                releaseProbe.countDown()
            }
            probe.join(5_000)
            assertFalse(probe.isAlive)
            probeFailure.get()?.let { throw AssertionError("Normal parent probe failed", it) }
            assertTrue(retriedSubmit.await(5, TimeUnit.SECONDS))
            assertEquals(2, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(1, 0)
            assertEquals(1, fixture.controller.stateSnapshot()
                .selectedLayoutIndices.getValue(fixture.mainId))
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun crossDisplayCallbackCannotBorrowAnotherSubscriptionProvenance() {
        Fixture().use { fixture ->
            fixture.source.replay(fixture.mainId,
                fixture.source.snapshot(fixture.clusterId.value))
            assertTrue(fixture.controller.stateSnapshot().failed)
            assertFalse(fixture.coordinator.isLive)
            assertTrue(fixture.source.awaitObserverRetirement())
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(1, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(0, 0)
        }
    }

    @Test
    fun lostPresentationHistorySealsControllerWithoutCommittingItsLatestIntent() {
        Fixture(maximumRetainedEvents = 1).use { fixture ->
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(2, fixture.backend.submissions.size)
            fixture.send(fixture.mainId, "main-a", "palette-a", emit = false)
            fixture.send(fixture.mainId, "main-b", "palette-b", emit = false)
            fixture.source.replay(fixture.mainId, fixture.source.snapshot(fixture.mainId.value))
            assertTrue(fixture.controller.stateSnapshot().failed)
            assertFalse(fixture.coordinator.isLive)
            assertEquals(2, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(1, 0)
            assertTrue(fixture.source.awaitObserverRetirement())
            assertEquals(0, fixture.coordinator.stateSnapshot().presentationSubscriptionCount)
            assertTrue(fixture.controller.stateSnapshot().closed)

            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            assertEquals(2, fixture.backend.submissions.size)
        }
    }

    @Test
    fun malformedTypeThreeRetiresBothSessionOwnedPresentationSubscriptions() {
        Fixture().use { fixture ->
            fixture.source.sendMalformed(fixture.mainId)
            assertTrue(fixture.controller.stateSnapshot().failed)
            assertFalse(fixture.coordinator.isLive)
            assertTrue(fixture.source.awaitObserverRetirement())
            assertEquals(0, fixture.source.observerCount)
            assertEquals(0, fixture.coordinator.stateSnapshot().presentationSubscriptionCount)
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun failedPresentationSubscriptionCloseIsRetriedWithoutLeakingItsObserver() {
        Fixture(failFirstPresentationClose = true).use { fixture ->
            fixture.source.sendMalformed(fixture.mainId)
            assertTrue(fixture.controller.stateSnapshot().failed)
            assertFalse(fixture.coordinator.isLive)
            val retired = fixture.source.awaitObserverRetirement()
            assertTrue("close attempts=${fixture.source.closeAttemptCount}, " +
                "observers=${fixture.source.observerCount}, " +
                "coordinatorSubscriptions=${fixture.coordinator.stateSnapshot().presentationSubscriptionCount}",
                retired)
            assertEquals(0, fixture.source.observerCount)
            assertEquals(0, fixture.coordinator.stateSnapshot().presentationSubscriptionCount)
            assertTrue(fixture.source.closeAttemptCount >= 3)
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun revocationAndCloseStopTypeThreeFromSubmittingFurtherFrames() {
        Fixture().use { fixture ->
            fixture.lease.revoke()
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(1, fixture.backend.submissions.size)
            assertTrue(fixture.controller.stateSnapshot().failed)
        }
        Fixture().use { fixture ->
            fixture.controller.close()
            assertTrue(fixture.controller.stateSnapshot().closed)
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun armBeforeAnyOutputAttachmentOnlySubscribesAndRetainsLatestIntent() {
        Fixture(armBeforeOutputs = true).use { fixture ->
            assertEquals(0, fixture.backend.submissions.size)
            assertEquals(2, fixture.coordinator.stateSnapshot().presentationSubscriptionCount)
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(0, fixture.backend.submissions.size)
            assertFalse(fixture.controller.stateSnapshot().activated)
            assertEquals(emptyMap<UltraUiSyncDisplayId, Int>(),
                fixture.controller.stateSnapshot().selectedLayoutIndices)

            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertEquals(1, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(1, 0)
            assertEquals(UltraThemeLayoutActivationResult.ALREADY_ACTIVE,
                fixture.activateArmed())
            assertEquals(1, fixture.backend.submissions.size)
            assertTrue(fixture.controller.stateSnapshot().activated)
        }
    }

    @Test
    fun armedSignedThenUnsupportedHistoryMatchesLiveSelection() {
        Fixture(armBeforeOutputs = true).use { fixture ->
            fixture.send(fixture.mainId, "main-b", "palette-b", emit = false)
            fixture.send(fixture.mainId, "unsigned-layout", "palette-b", emit = false)
            fixture.source.replay(fixture.mainId,
                fixture.source.snapshot(fixture.mainId.value))
            assertEquals(0, fixture.backend.submissions.size)
            assertEquals(UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE,
                fixture.activateArmed())
            assertEquals(0, fixture.backend.submissions.size)
            fixture.send(fixture.mainId, "main-b", "palette-b")
            fixture.assertSubmittedLayouts(1, 0)
            assertEquals(1, fixture.backend.submissions.size)
        }
        Fixture().use { fixture ->
            fixture.send(fixture.mainId, "main-b", "palette-b", emit = false)
            fixture.send(fixture.mainId, "unsigned-layout", "palette-b", emit = false)
            fixture.source.replay(fixture.mainId,
                fixture.source.snapshot(fixture.mainId.value))
            fixture.assertSubmittedLayouts(1, 0)
            assertEquals(2, fixture.backend.submissions.size)
        }
    }

    @Test
    fun armIntentHistoryOverflowAndReplayGapFailClosedBeforeAnyScene() {
        Fixture(armBeforeOutputs = true, maximumRetainedEvents = 300).use { fixture ->
            repeat(257) { index ->
                fixture.send(fixture.mainId,
                    if (index % 2 == 0) "main-b" else "unsigned-layout", "palette-b")
            }
            assertTrue(fixture.controller.stateSnapshot().failed)
            assertFalse(fixture.coordinator.isLive)
            assertEquals(0, fixture.backend.submissions.size)
        }
        Fixture(armBeforeOutputs = true, maximumRetainedEvents = 1).use { fixture ->
            fixture.send(fixture.mainId, "main-b", "palette-b", emit = false)
            fixture.send(fixture.mainId, "unsigned-layout", "palette-b", emit = false)
            fixture.source.replay(fixture.mainId,
                fixture.source.snapshot(fixture.mainId.value))
            assertTrue(fixture.controller.stateSnapshot().failed)
            assertFalse(fixture.coordinator.isLive)
            assertEquals(0, fixture.backend.submissions.size)
        }
    }

    @Test
    fun diagnosticSnapshotDropsCurrentQueueClaimAfterRendererFailure() {
        Fixture().use { fixture ->
            assertTrue(fixture.controller.stateSnapshot().activated)
            fixture.coordinator.sealSemanticRendererFailure(
                IllegalStateException("Test renderer failure"))
            assertFalse(fixture.controller.stateSnapshot().activated)
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun productBatchPathDropsCurrentQueueClaimAfterBatchRetirementOrAa05Revocation() {
        Fixture(armBeforeOutputs = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue(fixture.controller.stateSnapshot().activated)
            fixture.retirePhysicalBatch()
            assertFalse(fixture.controller.stateSnapshot().activated)
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(1, fixture.backend.submissions.size)
        }
        Fixture(armBeforeOutputs = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            fixture.lease.revoke()
            assertFalse(fixture.controller.stateSnapshot().activated)
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun signedActiveVideoAbsentAtActivationWaitsThenPublishesCompleteScene() {
        Fixture(withVideoSlots = true, armBeforeOutputs = true,
            publishVideoInitially = false).use { fixture ->
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(UltraThemeLayoutActivationResult.PENDING_INPUT_STREAM,
                fixture.activateArmed())
            assertTrue(fixture.controller.stateSnapshot().pendingInputStream)
            assertFalse(fixture.controller.stateSnapshot().activated)
            assertEquals(0, fixture.backend.submissions.size)

            fixture.publishVideos()
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertEquals(1, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(1, 0)
            assertEquals(1, requireNotNull(fixture.backend.submissions.single()
                .scene(DisplayDestination.MAIN)).layers.size)
            assertFalse(fixture.controller.stateSnapshot().pendingInputStream)
        }
    }

    @Test
    fun graphGenerationReplacementInvalidatesOldExecutorBeforeReactivation() {
        Fixture(withVideoSlots = true, armBeforeOutputs = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertEquals(1, fixture.backend.submissions.size)
            val firstRevision = fixture.backend.submissions.single().revision
            fixture.replaceVideoGraphGeneration()
            assertFalse(fixture.controller.stateSnapshot().activated)
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(1, fixture.backend.submissions.size)
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertEquals(2, fixture.backend.submissions.size)
            assertTrue(fixture.backend.submissions.last().revision > firstRevision)
            fixture.assertSubmittedLayouts(1, 0)
            assertTrue(fixture.controller.stateSnapshot().activated)
        }
    }

    @Test
    fun missedMainFrameReproofSubmitsFreshRevisionOfSameSignedScene() {
        Fixture(armBeforeOutputs = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            val first = fixture.backend.submissions.single()
            val mainPresentationRevision = fixture.source.snapshot(fixture.mainId.value).revision

            assertEquals(UltraThemeMainReproofResult.SUBMITTED,
                fixture.reproveMainAfterMiss(first.revision))
            assertEquals(2, fixture.backend.submissions.size)
            val replacement = fixture.backend.submissions.last()
            assertTrue(replacement.revision > first.revision)
            fixture.assertSubmittedLayouts(0, 0)
            assertEquals(mainPresentationRevision,
                fixture.source.snapshot(fixture.mainId.value).revision)
            assertEquals(first.scene(DisplayDestination.MAIN)?.layers?.map { it.id },
                replacement.scene(DisplayDestination.MAIN)?.layers?.map { it.id })
            assertEquals(UltraThemeMainReproofResult.REVOKED,
                fixture.reproveMainAfterMiss(first.revision))
            assertEquals(2, fixture.backend.submissions.size)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun missedMainFrameReproofDoesNotSendAnotherRequestLayout() {
        val port = CountingForegroundPort()
        Fixture(initialMainLayout = "main-b", armBeforeOutputs = true,
            diagnosticFallbackEnabled = false, foregroundRequests = port).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue("Initial signed MAIN did not request its layout",
                port.awaitRequests(1))
            val firstRevision = fixture.backend.submissions.single().revision

            assertEquals(UltraThemeMainReproofResult.SUBMITTED,
                fixture.awaitSubmittedMainReproof(firstRevision))
            assertEquals(2, fixture.backend.submissions.size)
            assertFalse("Reproof sent a new raw5 request", port.awaitRequests(2, 250L))
            assertEquals(1, port.requestCount.get())
        }
    }

    @Test
    fun closeDrainsForegroundRequestWithoutInterruptingItsWorker() {
        val delegate = CountingForegroundPort()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val port = object : UiSyncForegroundRequestPort by delegate {
            override fun requestLayout(
                displayId: String,
                command: CarPlayUiSyncAccessoryCommand.RequestLayout,
                scene: UiSyncForegroundSceneGuard,
            ): UiSyncForegroundRequestReceipt {
                entered.countDown()
                try {
                    check(release.await(2, TimeUnit.SECONDS))
                } catch (failure: InterruptedException) {
                    interrupted.set(true)
                    throw failure
                }
                return delegate.requestLayout(displayId, command, scene)
            }
        }
        val closer = Executors.newSingleThreadExecutor()
        try {
            Fixture(initialMainLayout = "main-b", armBeforeOutputs = true,
                diagnosticFallbackEnabled = false, foregroundRequests = port).use { fixture ->
                assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val closed = closer.submit<Throwable?> {
                    runCatching { fixture.controller.close() }.exceptionOrNull()
                }
                Thread.sleep(75L)
                assertFalse("Close must wait for the exact foreground worker", closed.isDone)
                assertFalse("Close must not interrupt an in-flight request", interrupted.get())
                release.countDown()
                assertNull(closed.get(2, TimeUnit.SECONDS))
                assertFalse(interrupted.get())
            }
        } finally {
            release.countDown()
            closer.shutdownNow()
        }
    }

    @Test
    fun foregroundWorkerCannotWaitForItsOwnRetirement() {
        val delegate = CountingForegroundPort()
        val attempted = CountDownLatch(1)
        val workerCloseFailure = AtomicReference<Throwable?>()
        lateinit var fixture: Fixture
        val port = object : UiSyncForegroundRequestPort by delegate {
            override fun requestLayout(
                displayId: String,
                command: CarPlayUiSyncAccessoryCommand.RequestLayout,
                scene: UiSyncForegroundSceneGuard,
            ): UiSyncForegroundRequestReceipt {
                workerCloseFailure.set(
                    runCatching { fixture.controller.close() }.exceptionOrNull())
                attempted.countDown()
                return delegate.requestLayout(displayId, command, scene)
            }
        }
        fixture = Fixture(initialMainLayout = "main-b", armBeforeOutputs = true,
            diagnosticFallbackEnabled = false, foregroundRequests = port)
        fixture.use {
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, it.activateArmed())
            assertTrue(attempted.await(2, TimeUnit.SECONDS))
            assertTrue(workerCloseFailure.get() is IllegalStateException)
            assertTrue(workerCloseFailure.get()?.message.orEmpty().contains("cannot retire itself"))
            it.controller.close() // The owner retries after the worker leaves its own close call.
        }
    }

    @Test
    fun missedMainFrameReproofWaitsForOriginalRequestLocalWrite() {
        val port = CountingForegroundPort(UiSyncForegroundRequestState.QUEUED)
        Fixture(initialMainLayout = "main-b", armBeforeOutputs = true,
            diagnosticFallbackEnabled = false, foregroundRequests = port).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue("Initial signed MAIN did not queue its request",
                port.awaitRequests(1))
            val firstRevision = fixture.backend.submissions.single().revision

            assertEquals(UltraThemeMainReproofResult.BUSY_RETRY,
                fixture.reproveMainAfterMiss(firstRevision))
            assertEquals(1, fixture.backend.submissions.size)
            assertEquals(1, port.requestCount.get())

            port.markFirstRequestSent()
            assertEquals(UltraThemeMainReproofResult.SUBMITTED,
                fixture.awaitSubmittedMainReproof(firstRevision))
            assertEquals(2, fixture.backend.submissions.size)
            assertFalse("Reproof sent another raw5 request", port.awaitRequests(2, 250L))
            assertEquals(1, port.requestCount.get())
        }
    }

    @Test
    fun missedMainFrameReproofAcceptsSuppressedRequestAfterClusterRedraw() {
        val port = CountingForegroundPort()
        Fixture(initialMainLayout = "main-b", armBeforeOutputs = true,
            diagnosticFallbackEnabled = false, foregroundRequests = port).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue("Initial signed MAIN did not request its layout",
                port.awaitRequests(1))
            assertFalse(port.awaitRequests(2, 100L))

            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            assertEquals(2, fixture.backend.submissions.size)
            fixture.assertSubmittedLayouts(1, 1)
            assertFalse("Unchanged MAIN issued raw5 after CLUSTER redraw",
                port.awaitRequests(2, 250L))
            val clusterRedrawRevision = fixture.backend.submissions.last().revision

            assertEquals(UltraThemeMainReproofResult.SUBMITTED,
                fixture.awaitSubmittedMainReproof(clusterRedrawRevision))
            assertEquals(3, fixture.backend.submissions.size)
            assertFalse("Reproof issued raw5 after CLUSTER redraw",
                port.awaitRequests(2, 250L))
            assertEquals(1, port.requestCount.get())
        }
    }

    @Test
    fun missedMainFrameReproofCarriesOnlyProvenLocalCanvasPost() {
        Fixture(withLocalCanvasSlots = true, armBeforeOutputs = true,
            proveLocalCanvas = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            val firstRevision = fixture.backend.submissions.single().revision
            val firstProof = fixture.awaitLocalProof(firstRevision)

            assertEquals(UltraThemeMainReproofResult.SUBMITTED,
                fixture.reproveMainAfterMiss(firstRevision))
            val secondRevision = fixture.backend.submissions.last().revision
            assertTrue(secondRevision > firstRevision)
            val secondProof = fixture.awaitLocalProof(secondRevision)
            val local = UltraEglIngressKey.Local(UltraContentId("canvas.main.a"))
            assertEquals(firstProof.postSequences[local], secondProof.postSequences[local])
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun missedMainFrameReproofWaitsWhenLocalCanvasPostChanged() {
        Fixture(withLocalCanvasSlots = true, armBeforeOutputs = true,
            proveLocalCanvas = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            val firstRevision = fixture.backend.submissions.single().revision
            fixture.awaitLocalProof(firstRevision)
            fixture.drawSelectedLocalCanvas("canvas.main.a")

            assertEquals(UltraThemeMainReproofResult.BUSY_RETRY,
                fixture.reproveMainAfterMiss(firstRevision))
            assertEquals(1, fixture.backend.submissions.size)
            assertTrue(fixture.controller.stateSnapshot().activated)
            assertFalse(fixture.controller.stateSnapshot().failed)
        }
    }

    @Test
    fun missedMainFrameReproofRejectsRetiredPhysicalBatch() {
        Fixture(armBeforeOutputs = true).use { fixture ->
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            val firstRevision = fixture.backend.submissions.single().revision
            fixture.retirePhysicalBatch()
            assertEquals(UltraThemeMainReproofResult.REVOKED,
                fixture.reproveMainAfterMiss(firstRevision))
            assertEquals(1, fixture.backend.submissions.size)
        }
    }

    @Test
    fun activationDefersReentrantTypeThreeUntilFirstCompleteQueueHandoff() {
        Fixture(armBeforeOutputs = true).use { fixture ->
            var injected = false
            fixture.backend.onSubmit = {
                if (!injected) {
                    injected = true
                    fixture.send(fixture.mainId, "main-b", "palette-b")
                }
            }
            assertEquals(UltraThemeLayoutActivationResult.SUBMITTED, fixture.activateArmed())
            assertTrue(injected)
            assertEquals(2, fixture.backend.submissions.size)
            assertEquals(1, fixture.backend.maximumSubmitDepth)
            fixture.assertSubmittedLayouts(1, 0)
            assertEquals(fixture.backend.submissions.map { it.revision }.distinct().size,
                fixture.backend.submissions.size)
        }
    }

    @Test
    fun armedControllerNeverSubmitsAfterRevocationOrLateCallback() {
        Fixture(armBeforeOutputs = true).use { fixture ->
            fixture.lease.revoke()
            fixture.send(fixture.mainId, "main-b", "palette-b")
            assertEquals(0, fixture.backend.submissions.size)
            assertTrue(runCatching { fixture.activateArmed() }.isFailure)
            fixture.send(fixture.clusterId, "cluster-b", "palette-b")
            assertEquals(0, fixture.backend.submissions.size)
            assertFalse(fixture.controller.stateSnapshot().activated)
        }
    }

    private class Fixture(
        maximumRetainedEvents: Int = 128,
        initialMainLayout: String? = null,
        withVideoSlots: Boolean = false,
        withLocalCanvasSlots: Boolean = false,
        failFirstPresentationClose: Boolean = false,
        holdParentProbeAcrossStartup: Boolean = false,
        armBeforeOutputs: Boolean = false,
        publishVideoInitially: Boolean = true,
        customLocalCanvasPainter: ((Canvas, PixelSize) -> Unit)? = null,
        diagnosticFallbackEnabled: Boolean = true,
        proveLocalCanvas: Boolean = false,
        firstPostEntered: CountDownLatch? = null,
        firstPostRelease: CountDownLatch? = null,
        withCarouselVariants: Boolean = false,
        noMainPrimaryIntent: Boolean = false,
        foregroundRequests: UiSyncForegroundRequestPort? = null,
        beginSemanticCover:
            ((UiSyncPhysicalOutputBatch, UltraUiSyncDisplayId, Long) ->
                MainSemanticPhysicalCoverTicket?)? = { _, _, _ ->
                MainSemanticPhysicalCoverTicket { true }
            },
        resumeSemanticCover:
            ((UiSyncPhysicalOutputBatch, UltraUiSyncDisplayId, Long) -> Boolean)? =
                { _, _, _ -> true },
    ) : Closeable {
        val mainId = UltraUiSyncDisplayId("main-ui")
        val clusterId = UltraUiSyncDisplayId("cluster-ui")
        val source = HubPresentationSource(maximumRetainedEvents,
            failFirstPresentationClose).apply {
            if (initialMainLayout != null) send(
                mainId, initialMainLayout, "palette-b", emptyMap(), emit = false,
            )
        }
        val backend = FakeBackend(proveLocalCanvas)
        val identity = identity()
        val programJson = when {
            withVideoSlots -> videoPackageJson
            withCarouselVariants -> carouselPackageJson
            withLocalCanvasSlots -> localCanvasPackageJson
            else -> packageJson
        }
        private val ingressSpecs = if (withVideoSlots) listOf(
            ingressSpec("video-a"), ingressSpec("video-b"),
        ) else emptyList()
        private val streams = ingressSpecs.associate { spec ->
            val identifier = requireNotNull(spec.airPlayStreamIdentity.streamConnectionId)
            identifier to syntheticHandle(AirPlayStreamIdentity(110, identifier, null))
        }
        val profile = profile(identity, programJson,
            if (withCarouselVariants) carouselCatalogSpec else if (withLocalCanvasSlots)
                localCanvasSpec else null, withCarouselVariants)
        val outputs = listOf(output("main", 1, mainId), output("cluster", 2, clusterId))
        val lease = AuthorizedUltraDisplayLeaseTestFixture.open(
            profile, identity, outputs,
            assignments(profile, outputs, programJson, ingressSpecs, withVideoSlots,
                withLocalCanvasSlots, withCarouselVariants, noMainPrimaryIntent),
        )
        private val batchBinder = if (proveLocalCanvas) TestBatchBinder() else null
        val coordinator = if (batchBinder == null) AndroidUltraRendererSessionCoordinator(
            sessionContext(source, outputs, ingressSpecs, lease.schemaFingerprint,
                lease.bindingFingerprint), backend,
        ) else AndroidUltraRendererSessionCoordinator(
            sessionContext(source, outputs, ingressSpecs, lease.schemaFingerprint,
                lease.bindingFingerprint), backend,
            { AndroidUltraBatchBinderResource(batchBinder, batchBinder) }, Unit,
        )
        private val canvasOwner = if (withLocalCanvasSlots) UltraLocalCanvasSlotOwner(
            UltraLocalCanvasIngressPort { contentId, pixels ->
                val opened = coordinator.openLocalIngress(contentId, pixels)
                UltraLocalCanvasIngress(
                    opened.key as UltraEglIngressKey.Local,
                    opened.producerSurface, opened, opened::close,
                )
            },
            object : UltraLocalCanvasDrawPort {
                private val first = AtomicBoolean(true)
                override fun draw(surface: Surface?, sizePixels: PixelSize,
                    painter: (Canvas, PixelSize) -> Unit,
                ) {
                    if (customLocalCanvasPainter != null) painter(canvas(), sizePixels)
                }
                override fun drawWithPostFence(surface: Surface?, sizePixels: PixelSize,
                    painter: (Canvas, PixelSize) -> Unit,
                    finalPost: (clear: () -> Unit, post: () -> Unit) -> Unit,
                ) {
                    if (firstPostRelease != null && first.compareAndSet(true, false)) {
                        firstPostEntered?.countDown()
                        check(firstPostRelease.await(5, TimeUnit.SECONDS))
                    }
                    draw(surface, sizePixels, painter)
                    finalPost({}, {})
                }
            },
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "test-local-canvas-producer").apply { isDaemon = true }
            },
        ) else null
        private val ingressCandidates = ArrayList<UiSyncRendererScreenCandidate>().apply {
            ingressSpecs.forEach { spec ->
            val identifier = requireNotNull(spec.airPlayStreamIdentity.streamConnectionId)
            val candidate = requireNotNull(coordinator.prepareScreen(
                screenContext(requireNotNull(streams[identifier]), spec.id),
            ))
            add(candidate)
            if (publishVideoInitially) check(candidate.publish())
            }
        }
        private val outputLeases = ArrayList<AndroidUltraOutputAttachment>()
        private var outputBatchLease: AndroidUltraOutputBatchLease? = null
        private fun attachOutputs() {
            if (batchBinder != null) {
                outputBatchLease = coordinator.replaceOutputBatch(listOf(
                    AndroidUltraOutputAttachmentRequest(
                        DisplayDestination.MAIN, outputs[0], target("main")),
                    AndroidUltraOutputAttachmentRequest(
                        DisplayDestination.CLUSTER, outputs[1], target("cluster")),
                ))
            } else {
                outputLeases += coordinator.attachOutput(
                    DisplayDestination.MAIN, outputs[0], target("main"))
                outputLeases += coordinator.attachOutput(
                    DisplayDestination.CLUSTER, outputs[1], target("cluster"))
            }
        }
        private val startupProbe = if (holdParentProbeAcrossStartup)
            StartupProbe(lease) else null
        private val sceneBindings = listOf(
            binding(profile, programJson, outputs[0], DisplayDestination.MAIN, 0,
                ingressSpecs, streams, withVideoSlots, withLocalCanvasSlots),
            binding(profile, programJson, outputs[1], DisplayDestination.CLUSTER, 1,
                ingressSpecs, streams, withVideoSlots, withLocalCanvasSlots),
        )
        val controller = if (armBeforeOutputs) {
            UltraThemeLayoutIntentController.arm(coordinator, listOf(mainId, clusterId),
                canvasOwner,
                localCanvasPainter = { id ->
                    customLocalCanvasPainter?.takeIf {
                        id == UltraContentId("canvas.main.a") ||
                            (withCarouselVariants && id.value.startsWith("canvas.main."))
                    }
                },
                diagnosticFallbackEnabled = diagnosticFallbackEnabled,
                foregroundRequests = foregroundRequests,
                beginMainSemanticCover = beginSemanticCover,
                resumeMainAfterSignedPhoneVisualChoice = resumeSemanticCover,
            ).also {
                check(backend.submissions.isEmpty() &&
                    coordinator.stateSnapshot().outputs.isEmpty())
                attachOutputs()
            }
        } else {
            attachOutputs()
            UltraThemeLayoutIntentController.open(
                coordinator, lease.runtimeLease, profile, coordinator.graphSnapshot(), sceneBindings,
            )
        }

        private var physicalBatch: UiSyncPhysicalOutputBatch? = null
        private val physicalVersionPrefix = UUID.randomUUID().toString()

        init { startupProbe?.awaitCompletion() }

        fun activateArmed(): UltraThemeLayoutActivationResult = controller.activate(
            lease.runtimeLease, profile, coordinator.graphSnapshot(), sceneBindings,
            physicalBatch ?: testPhysicalBatch().also { physicalBatch = it },
        )

        fun activateArmedWithGraph(graph: UltraDisplayGraphSnapshot):
            UltraThemeLayoutActivationResult = controller.activate(
            lease.runtimeLease, profile, graph, sceneBindings,
            physicalBatch ?: testPhysicalBatch().also { physicalBatch = it },
        )

        fun reproveMainAfterMiss(expectedRevision: Long): UltraThemeMainReproofResult =
            controller.reproveMainSceneAfterMiss(
                coordinator.graphSnapshot(), requireNotNull(physicalBatch), expectedRevision,
            )

        fun awaitSubmittedMainReproof(expectedRevision: Long): UltraThemeMainReproofResult {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var result = reproveMainAfterMiss(expectedRevision)
            while (result == UltraThemeMainReproofResult.BUSY_RETRY &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(5L)
                result = reproveMainAfterMiss(expectedRevision)
            }
            return result
        }

        fun awaitLocalProof(revision: Long): UltraEglLocalFirstFrameReceipt {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                controller.stateSnapshot().localFirstFrameReceipt?.takeIf {
                    it.revision == revision
                }?.let { return it }
                Thread.sleep(5)
            }
            error("Local Canvas revision $revision was not proven: " +
                "controller=${controller.stateSnapshot()}, " +
                "renderer=${coordinator.stateSnapshot()}, " +
                "prepareCalls=${backend.prepareCalls}, presentCalls=${backend.presentCalls}")
        }

        fun drawSelectedLocalCanvas(contentId: String) {
            val handle = checkNotNull(controller.currentSignedLocalCanvasHandle(
                UltraContentId(contentId)))
            checkNotNull(canvasOwner).draw(handle) { _, _ -> }.get(2, TimeUnit.SECONDS)
        }

        fun retirePhysicalBatch() {
            requireNotNull(physicalBatch).close()
        }

        fun publishVideos() {
            ingressCandidates.forEach { check(it.publish()) }
        }

        fun replaceVideoGraphGeneration() {
            ingressCandidates.forEach { it.close() }
            ingressCandidates.clear()
            ingressSpecs.forEach { spec ->
                val identifier = requireNotNull(spec.airPlayStreamIdentity.streamConnectionId)
                val handle = syntheticHandle(AirPlayStreamIdentity(110, identifier, null))
                val candidate = requireNotNull(coordinator.prepareScreen(
                    screenContext(handle, spec.id),
                ))
                ingressCandidates += candidate
                check(candidate.publish())
            }
        }

        private fun testPhysicalBatch(): UiSyncPhysicalOutputBatch {
            val requirement = UiSyncPhysicalOutputRequirement(profile.candidate.contentDigest,
                outputs.mapIndexed { index, output ->
                    UiSyncPhysicalOutputAssignment(output, UiSyncPhysicalDestination(
                        if (index == 0) UiSyncPhysicalDestinationRole.MAIN
                        else UiSyncPhysicalDestinationRole.CLUSTER,
                    ))
                })
            val child = object : UiSyncCompleteRendererSessionLease {
                override val isLive: Boolean get() = coordinator.isLive
                override val schemaFingerprint = lease.schemaFingerprint
                override val bindingFingerprint = lease.bindingFingerprint
                override val displayIds = coordinator.displayIds
                override val implementationId = "layout-test-child"
                override val readinessReceipt = null
                override fun prepareScreen(context: UiSyncRendererScreenContext):
                    UiSyncRendererScreenCandidate? = null
                override fun retireScreens(): Boolean = true
                override fun close() = Unit
            }
            val context = UiSyncPhysicalOutputSessionContext::class.java.declaredConstructors
                .single { it.parameterCount == 5 }.apply { isAccessible = true }
                .newInstance(requirement, UiSyncRendererStaticPlan(outputs, ingressSpecs),
                    lease.schemaFingerprint, lease.bindingFingerprint, child) as
                UiSyncPhysicalOutputSessionContext
            val routes = requirement.assignments.mapIndexed { index, row ->
                UiSyncPhysicalOutputRoute(row, UiSyncPhysicalTargetVersion(
                    "$physicalVersionPrefix-$index", 1L,
                ))
            }
            val backendLease = object : UiSyncPhysicalOutputBackendLease {
                override val generation = outputBatchLease?.generation ?: 1L
                override val isCurrent: Boolean get() = coordinator.isLive
                override fun close() = Unit
            }
            val targetLeases = routes.map { route ->
                object : UiSyncPhysicalTargetLease {
                    override val version = route.target
                    override val isCurrent = true
                }
            }
            return context.issueActiveBatch(backendLease, routes, targetLeases)
        }

        fun send(
            displayId: UltraUiSyncDisplayId,
            layoutId: String,
            paletteId: String,
            components: Map<String, String> = emptyMap(),
            emit: Boolean = true,
        ) = source.send(displayId, layoutId, paletteId, components, emit)

        fun sendPalette(
            displayId: UltraUiSyncDisplayId,
            layoutId: String,
            paletteId: String,
            emit: Boolean = true,
        ) = source.sendPalette(displayId, layoutId, paletteId, emit)

        fun sendRaw(
            displayId: UltraUiSyncDisplayId,
            type: CarPlayUiSyncCommandType,
            data: Map<String, Any?>,
            echoesAcknowledgedRequestLayout: Boolean = false,
            emit: Boolean = true,
        ) = source.sendRaw(displayId, type, data, echoesAcknowledgedRequestLayout, emit)

        fun assertSubmittedLayouts(main: Int, cluster: Int) {
            val scenes = backend.submissions.last()
            assertEquals(2, scenes.scenes.size)
            assertEquals(main, (scenes.scene(DisplayDestination.MAIN)?.background as
                UltraSceneBackground.OpaqueSrgb).layoutAddress.layoutIndex)
            assertEquals(cluster, (scenes.scene(DisplayDestination.CLUSTER)?.background as
                UltraSceneBackground.OpaqueSrgb).layoutAddress.layoutIndex)
        }

        fun assertLocalCanvasesRetained(expected: List<UltraContentId>) {
            assertEquals(expected.mapTo(LinkedHashSet()) { UltraEglIngressKey.Local(it) },
                coordinator.stateSnapshot().ownedIngresses.mapTo(LinkedHashSet()) { it.key })
        }

        override fun close() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                val attempt = runCatching { controller.close() }
                if (attempt.isSuccess) break
                val cause = checkNotNull(attempt.exceptionOrNull())
                if (!cause.message.orEmpty().contains("still in flight") ||
                    System.nanoTime() >= deadline) throw cause
                Thread.sleep(10)
            }
            startupProbe?.awaitCompletion()
            physicalBatch?.close()
            canvasOwner?.close()
            outputBatchLease?.close()
            outputLeases.forEach { it.close() }
            ingressCandidates.forEach { it.close() }
            coordinator.close()
            lease.close()
            source.close()
        }
    }

    private class CountingForegroundPort(
        private val initialState: UiSyncForegroundRequestState =
            UiSyncForegroundRequestState.SENT_UNCONFIRMED,
    ) : UiSyncForegroundRequestPort {
        val requestCount = AtomicInteger()
        val lastCommand = AtomicReference<CarPlayUiSyncAccessoryCommand.RequestLayout?>()
        val refuseGuards = AtomicBoolean(false)
        val guardAttempts = AtomicInteger()
        private val returnedCount = AtomicInteger()
        private val firstReceipt = AtomicReference<UiSyncForegroundRequestReceipt?>()
        private val readyGeneration = UiSyncForegroundReadyGeneration::class.java
            .declaredConstructors.single { it.parameterCount == 0 }
            .apply { isAccessible = true }.newInstance() as UiSyncForegroundReadyGeneration

        override fun currentReadyGeneration(displayId: String): UiSyncForegroundReadyGeneration =
            readyGeneration

        override fun guardScene(
            displayId: String,
            source: UiSyncForegroundSceneUseSource,
        ): UiSyncForegroundSceneGuard? {
            if (refuseGuards.get()) {
                guardAttempts.incrementAndGet()
                return null
            }
            val use = source.acquireUse { } ?: return null
            use.close()
            return UiSyncForegroundSceneGuard::class.java.declaredConstructors
                .single { it.parameterCount == 3 }.apply { isAccessible = true }
                .newInstance(this, displayId, source) as UiSyncForegroundSceneGuard
        }

        override fun requestLayout(
            displayId: String,
            command: CarPlayUiSyncAccessoryCommand.RequestLayout,
            scene: UiSyncForegroundSceneGuard,
        ): UiSyncForegroundRequestReceipt {
            val receipt = UiSyncForegroundRequestReceipt::class.java.declaredConstructors
                .single { it.parameterCount == 1 }.apply { isAccessible = true }
                .newInstance(initialState) as UiSyncForegroundRequestReceipt
            if (initialState == UiSyncForegroundRequestState.SENT_UNCONFIRMED)
                markSentGeneration(receipt)
            firstReceipt.compareAndSet(null, receipt)
            lastCommand.set(command)
            requestCount.incrementAndGet()
            return receipt.also { returnedCount.incrementAndGet() }
        }

        /** The controller records the receipt right after this returns; give it that turn. */
        fun awaitReceiptReturned() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (returnedCount.get() < requestCount.get() && System.nanoTime() < deadline) {
                Thread.sleep(5L)
            }
            Thread.sleep(50L)
        }

        fun markFirstRequestSent() {
            val receipt = requireNotNull(firstReceipt.get())
            UiSyncForegroundRequestReceipt::class.java.declaredMethods.single {
                it.name.startsWith("setState") && it.parameterCount == 1
            }.apply { isAccessible = true }.invoke(
                receipt, UiSyncForegroundRequestState.SENT_UNCONFIRMED,
            )
            markSentGeneration(receipt)
        }

        private fun markSentGeneration(receipt: UiSyncForegroundRequestReceipt) {
            UiSyncForegroundRequestReceipt::class.java.declaredMethods.single {
                it.name.startsWith("setSentGeneration") && it.parameterCount == 1
            }.apply { isAccessible = true }.invoke(receipt, readyGeneration)
        }

        fun awaitRequests(expected: Int, timeoutMillis: Long = 3_000L): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (requestCount.get() < expected && System.nanoTime() < deadline) {
                Thread.sleep(5L)
            }
            return requestCount.get() >= expected
        }
    }

    private class StartupProbe(
        lease: AuthorizedUltraDisplayLeaseTestFixture,
    ) {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val probe = Thread {
            try {
                check(lease.probeParentLease())
            } catch (cause: Throwable) {
                failure.compareAndSet(null, cause)
            }
        }.apply { isDaemon = true }
        private val releaser = Thread {
            try {
                Thread.sleep(50)
            } catch (cause: Throwable) {
                failure.compareAndSet(null, cause)
            } finally {
                release.countDown()
            }
        }.apply { isDaemon = true }

        init {
            lease.setNextParentProbeHook {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) {
                    "Startup probe was not released"
                }
            }
            probe.start()
            check(entered.await(5, TimeUnit.SECONDS)) {
                "Normal parent probe did not enter its external getter"
            }
            releaser.start()
        }

        fun awaitCompletion() {
            probe.join(5_000)
            releaser.join(5_000)
            check(!probe.isAlive && !releaser.isAlive) {
                "Startup parent probe helpers did not retire"
            }
            failure.get()?.let { throw AssertionError("Startup parent probe failed", it) }
        }
    }

    private class HubPresentationSource(
        maximumRetainedEvents: Int,
        failFirstClose: Boolean,
    ) :
        UiSyncPresentationSource, Closeable {
        private val hub = UltraPresentationHub(Runnable::run,
            UltraPresentationHubLimits(maximumRetainedEventsPerDisplay = maximumRetainedEvents))
        private val observers = ConcurrentHashMap<UltraUiSyncDisplayId, UltraPresentationObserver>()
        private val retired = CountDownLatch(2)
        private val failClose = AtomicBoolean(failFirstClose)
        private val closeAttempts = AtomicInteger()

        val observerCount: Int get() = observers.size
        val closeAttemptCount: Int get() = closeAttempts.get()

        fun awaitObserverRetirement(): Boolean = retired.await(5, TimeUnit.SECONDS)

        override fun subscribeWithReplay(
            displayId: String,
            observer: UltraPresentationObserver,
        ): UiSyncPresentationSubscription {
            val id = UltraUiSyncDisplayId(displayId)
            check(observers.putIfAbsent(id, observer) == null)
            return UiSyncPresentationSubscription(hub.snapshot(id), Closeable {
                closeAttempts.incrementAndGet()
                if (failClose.compareAndSet(true, false)) {
                    throw IllegalStateException("Injected one-shot presentation close failure")
                }
                if (observers.remove(id, observer)) retired.countDown()
            })
        }

        override fun snapshot(displayId: String): UltraPresentationSnapshot = hub.snapshot(displayId)

        fun send(
            id: UltraUiSyncDisplayId,
            layoutId: String,
            paletteId: String,
            components: Map<String, String>,
            emit: Boolean,
        ) {
            hub.sink(id).onPresentationEvent(CarPlayUiSyncPresentationEvent.KnownCommand(
                CarPlayUiSyncCommandType.LAYOUT_CHANGE,
                mapOf("layoutID" to layoutId, "activeComponents" to components,
                    "paletteID" to paletteId),
            ))
            if (emit) replay(id, hub.snapshot(id))
        }

        fun sendPalette(
            id: UltraUiSyncDisplayId,
            layoutId: String,
            paletteId: String,
            emit: Boolean = true,
        ) {
            hub.sink(id).onPresentationEvent(CarPlayUiSyncPresentationEvent.KnownCommand(
                CarPlayUiSyncCommandType.PALETTE_CHANGE,
                mapOf("layoutIdentifier" to layoutId, "paletteIdentifier" to paletteId),
            ))
            if (emit) replay(id, hub.snapshot(id))
        }

        /** [echoesAcknowledgedRequestLayout] stands in for the channel's ACK-first attestation. */
        fun sendRaw(
            id: UltraUiSyncDisplayId,
            type: CarPlayUiSyncCommandType,
            data: Map<String, Any?>,
            echoesAcknowledgedRequestLayout: Boolean = false,
            emit: Boolean = true,
        ) {
            hub.sink(id).onPresentationEvent(CarPlayUiSyncPresentationEvent.KnownCommand(
                type, data, acknowledgedRequestEcho = if (echoesAcknowledgedRequestLayout) {
                    // Only the shared channel can mint this; the test stands in for it.
                    CarPlayUiSyncAcknowledgedRequestEcho::class.java.declaredConstructors
                        .single().apply { isAccessible = true }
                        .newInstance(data) as CarPlayUiSyncAcknowledgedRequestEcho
                } else null,
            ))
            if (emit) replay(id, hub.snapshot(id))
        }

        fun replay(id: UltraUiSyncDisplayId, snapshot: UltraPresentationSnapshot) {
            observers[id]?.onPresentationSnapshot(snapshot)
        }

        fun sendMalformed(id: UltraUiSyncDisplayId) {
            hub.sink(id).onPresentationEvent(CarPlayUiSyncPresentationEvent.KnownCommand(
                CarPlayUiSyncCommandType.LAYOUT_CHANGE,
                mapOf("layoutID" to 1),
            ))
            replay(id, hub.snapshot(id))
        }

        override fun close() = hub.close()
    }

    private class TestBatchBinder : UltraDisplayBatchBinder, Closeable {
        private var live = false

        override fun prepare(plan: UltraDisplayBatchPlan): PreparedUltraDisplayBatch {
            check(plan.outputRoutes.size == 2 && !live)
            return object : PreparedUltraDisplayBatch {
                override val generation = plan.generation
                override fun commit(): UltraDisplayBatchLease {
                    check(!live)
                    live = true
                    return object : UltraDisplayBatchLease {
                        private var retired = false
                        private val fence = object : UltraDisplayRetirementFence {
                            override fun awaitRetirement() { retired = true }
                            override fun close() { check(retired) }
                        }
                        override fun beginRetirement(): UltraDisplayRetirementFence = fence
                        override fun close() {
                            check(retired)
                            live = false
                        }
                    }
                }
                override fun close() = Unit
            }
        }

        override fun close() { check(!live) }
    }

    private class FakeBackend(private val proveLocalCanvas: Boolean = false) :
        AndroidUltraRendererBackendPort {
        val submissions = ArrayList<UltraMultiOutputRenderSnapshot>()
        private var lastResolved: UltraEglResolvedSubmission? = null
        var prepareCalls = 0
        var presentCalls = 0
        var onSubmit: (() -> Unit)? = null
        var maximumSubmitDepth = 0
            private set
        private var submitDepth = 0

        override fun openRemoteIngress(ingress: UltraDisplayGraphIngress):
            AndroidUltraBackendIngress = AndroidUltraBackendIngress(
            UltraEglIngressKey.Remote(ingress.id), surface(),
        ) { AndroidVideoSurfaceRetirementResult.RETIRED }

        override fun openLocalIngress(contentId: UltraContentId, sizePixels: PixelSize):
            AndroidUltraBackendIngress = AndroidUltraBackendIngress(
                UltraEglIngressKey.Local(contentId), surface(),
            ) { AndroidVideoSurfaceRetirementResult.RETIRED }

        override fun openOverlayIngress(contentId: UltraContentId, sizePixels: PixelSize):
            AndroidUltraBackendIngress = error("No overlay ingress in static fixture")

        override fun attachOutput(
            destination: DisplayDestination,
            output: UltraDisplayGraphOutput,
            target: AndroidDisplaySurfaceTarget,
        ): AndroidUltraBackendOutput = AndroidUltraBackendOutput(destination) {}

        override fun resolve(plan: UltraResolvedMultiOutputRenderPlan): UltraEglResolvedSubmission =
            UltraEglResolvedSubmission(
                UltraMultiOutputRenderSnapshot(plan.renderRevision, plan.scenes.map { it.scene }),
                plan.graphRevision, emptyMap(), emptyMap(),
            )

        override fun submit(resolved: UltraEglResolvedSubmission): UltraEglRenderTicket {
            submitDepth += 1
            maximumSubmitDepth = maxOf(maximumSubmitDepth, submitDepth)
            try {
                submissions += resolved.scene
                lastResolved = resolved
                onSubmit?.invoke()
                return UltraEglRenderTicket(resolved.revision).also {
                    it.complete(UltraEglRenderResult(resolved.revision,
                        UltraEglRenderDisposition.PRESENTED))
                }
            } finally {
                submitDepth -= 1
            }
        }

        override fun prepareLocalFirstFrame(
            revision: Long,
            outputBatchGeneration: Long,
            localKeys: Set<UltraEglIngressKey.Local>,
            carriedFrom: UltraEglLocalFirstFrameReceipt?,
            carriedKeys: Set<UltraEglIngressKey.Local>,
        ): UltraEglLocalFirstFrameExpectation? {
            prepareCalls++
            if (!proveLocalCanvas) return null
            val submission = lastResolved?.takeIf { it.revision == revision } ?: return null
            val required = submission.scene.scenes.flatMap { scene ->
                scene.layers.mapIndexedNotNull { index, layer ->
                    val local = layer.source as? UltraSceneSource.Local
                        ?: return@mapIndexedNotNull null
                    UltraEglLocalLayerDraw(scene.destination,
                        UltraEglIngressKey.Local(local.contentId), index, layer)
                }
            }.toSet()
            return UltraEglLocalFirstFrameExpectation(
                submission, outputBatchGeneration, outputBatchGeneration, required,
                (localKeys - carriedKeys).associateWith { 0L }, carriedFrom, carriedKeys,
                carriedKeys.associateWith { checkNotNull(carriedFrom?.frameSignals?.get(it)) },
                carriedKeys.associateWith { checkNotNull(carriedFrom?.frameUpdates?.get(it)) },
            )
        }

        override fun tryPresentLocalFirstFrame(
            expectation: UltraEglLocalFirstFrameExpectation,
            postSequences: Map<UltraEglIngressKey.Local, Long>,
        ): UltraEglLocalFirstFrameReceipt? {
            presentCalls++
            if (!proveLocalCanvas || postSequences.keys !=
                expectation.frameSignalsBeforePost.keys + expectation.carriedKeys
            ) return null
            return UltraEglLocalFirstFrameReceipt(
                expectation.submission.revision, expectation.submission.graphRevision,
                expectation.physicalBatchGeneration, expectation.outputBatchGeneration,
                expectation.requiredDraws, postSequences,
                postSequences.keys.associateWith { 1L },
                postSequences.keys.associateWith { 1L },
                postSequences.keys.associateWith { 1L },
            )
        }

        override fun phase() = UltraEglBackendPhase.OPEN
        override fun retire(timeoutMillis: Long) = UltraEglBackendRetirementResult.RETIRED
    }

    private companion object {
        val packageJson = """{
          "identifier":"layout-intent-test","version":1,"minimumSDKVersion":"1",
          "buildType":"debug","partnerID":"test","driveModeLinkedByDefault":false,
          "displays":[
            {"id":"main","type":"centerConsole","size":[800,480],"density":1,
             "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
             "layouts":[
               {"id":"main-a","layoutType":"primary","zones":[],
                "backgroundColor":"#111111","safeAreaInsets":[0,0,0,0],
                "driveModeThemeConfigurations":{}},
               {"id":"main-b","layoutType":"primary","zones":[],
                "backgroundColor":"#222222","safeAreaInsets":[0,0,0,0],
                "driveModeThemeConfigurations":{}}]},
            {"id":"cluster","type":"cluster","size":[800,480],"density":1,
             "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
             "layouts":[
               {"id":"cluster-a","layoutType":"primary","zones":[],
                "backgroundColor":"#333333","safeAreaInsets":[0,0,0,0],
                "driveModeThemeConfigurations":{}},
               {"id":"cluster-b","layoutType":"primary","zones":[],
                "backgroundColor":"#444444","safeAreaInsets":[0,0,0,0],
                "driveModeThemeConfigurations":{}}]}
          ]
        }""".trimIndent().encodeToByteArray()

        val videoPackageJson = packageJson.decodeToString().let { base ->
            val first = base.replace(
                "\"id\":\"main-a\",\"layoutType\":\"primary\",\"zones\":[]",
                "\"id\":\"main-a\",\"layoutType\":\"primary\",\"zones\":[${videoZone("video-a")}]",
            )
            check(first != base)
            val second = first.replace(
                "\"id\":\"main-b\",\"layoutType\":\"primary\",\"zones\":[]",
                "\"id\":\"main-b\",\"layoutType\":\"primary\",\"zones\":[${videoZone("video-b")}]",
            )
            check(second != first)
            second.encodeToByteArray()
        }

        private val localCanvasZone = """{
            "id":"canvas-zone","frame":[[0,0],[800,480]],"type":{"single":{}},
            "components":[{"id":"primary","type":"primary","size":[800,480],
              "slots":[{"style":{"rectangular":{}},"frame":[[10,10],[100,60]],
                "zIndex":0,"positionId":"canvas"}]}]
        }""".trimIndent()

        val localCanvasPackageJson = packageJson.decodeToString().let { base ->
            val first = base.replace(
                "\"id\":\"main-a\",\"layoutType\":\"primary\",\"zones\":[]",
                "\"id\":\"main-a\",\"layoutType\":\"primary\",\"zones\":[${localCanvasZone}]",
            )
            check(first != base)
            val second = first.replace(
                "\"id\":\"main-b\",\"layoutType\":\"primary\",\"zones\":[]",
                "\"id\":\"main-b\",\"layoutType\":\"primary\",\"zones\":[${localCanvasZone}]",
            )
            check(second != first)
            second.encodeToByteArray()
        }

        val carouselPackageJson = packageJson.decodeToString().let { base ->
            val changed = base.replace(
                "\"id\":\"main-a\",\"layoutType\":\"primary\",\"zones\":[]",
                "\"id\":\"main-a\",\"layoutType\":\"primary\",\"zones\":[" +
                    """{"id":"main-carousel-zone","frame":[[0,0],[800,480]],
                      "type":{"mapsMedia":{}},"components":[
                        {"id":"map","type":"primary","size":[800,480],
                         "slots":[{"style":{"rectangular":{}},
                           "frame":[[0,0],[800,480]],"zIndex":0,"positionId":"map"}]},
                        {"id":"media","type":"primary","size":[800,480],
                         "slots":[{"style":{"rectangular":{}},
                           "frame":[[0,0],[800,480]],"zIndex":0,"positionId":"media"}]}]}""" +
                    "]",
            )
            check(changed != base)
            changed.encodeToByteArray()
        }

        private val localCanvasSpec = """{
          "format":"xcertplay-debug-uisync-v2","displays":[
            {"profileDisplayIndex":0,"profileDisplayId":"main",
             "advertisedScreenId":"${AirPlayInfoPlist.MAIN_UUID}","destinationRole":"MAIN",
             "destinationInstanceId":"default",
             "topologyDisplayId":"00000000-0000-0000-0000-000000000001",
             "outputId":{"kind":"android","value":"main"},"uiSyncDisplayId":"main-ui",
             "layouts":[
               ${localCanvasLayoutSpec(0, "main-a", "palette-a", "canvas.main.a")},
               ${localCanvasLayoutSpec(1, "main-b", "palette-b", "canvas.main.b")}]
            },
            {"profileDisplayIndex":1,"profileDisplayId":"cluster",
             "advertisedScreenId":"${AirPlayInfoPlist.ALT_UUID}","destinationRole":"CLUSTER",
             "destinationInstanceId":"default",
             "topologyDisplayId":"00000000-0000-0000-0000-000000000002",
             "outputId":{"kind":"android","value":"cluster"},"uiSyncDisplayId":"cluster-ui",
             "layouts":[
               ${localCanvasLayoutSpec(0, "cluster-a", "palette-a", null)},
               ${localCanvasLayoutSpec(1, "cluster-b", "palette-b", null)}]
            }
          ]
        }""".trimIndent()

        private val carouselCatalogSpec = """{
          "format":"xcertplay-debug-uisync-v3","displays":[
            {"profileDisplayIndex":0,"profileDisplayId":"main",
             "advertisedScreenId":"${AirPlayInfoPlist.MAIN_UUID}","destinationRole":"MAIN",
             "destinationInstanceId":"default",
             "topologyDisplayId":"00000000-0000-0000-0000-000000000001",
             "outputId":{"kind":"android","value":"main"},"uiSyncDisplayId":"main-ui",
             "layouts":[
               ${carouselVariantSpec(0, "map", "palette-a", "canvas.main.bench")},
               ${carouselVariantSpec(1, "media", "palette-a", "canvas.main.carousel.media")},
               ${carouselVariantSpec(0, "map", "palette-b", "canvas.main.carousel.map.alt")}]
            },
            {"profileDisplayIndex":1,"profileDisplayId":"cluster",
             "advertisedScreenId":"${AirPlayInfoPlist.ALT_UUID}","destinationRole":"CLUSTER",
             "destinationInstanceId":"default",
             "topologyDisplayId":"00000000-0000-0000-0000-000000000002",
             "outputId":{"kind":"android","value":"cluster"},"uiSyncDisplayId":"cluster-ui",
             "layouts":[
               ${localCanvasLayoutSpec(0, "cluster-a", "palette-a", null)},
               ${localCanvasLayoutSpec(1, "cluster-b", "palette-b", null)}]
            }
          ]
        }""".trimIndent()

        private fun carouselVariantSpec(componentIndex: Int, componentId: String,
            paletteId: String, contentId: String): String = """{
            "layoutIndex":0,"layoutID":"main-a","paletteID":"$paletteId",
            "activeComponents":{"main-carousel-zone":"$componentId"},
            "activePrimarySelections":[{"zoneIndex":0,"componentIndex":$componentIndex}],
            "slotIngresses":[],"localCanvasSlots":[
              {"zoneIndex":0,"componentIndex":$componentIndex,"slotIndex":0,
               "contentId":"$contentId"}]
          }""".trimIndent()

        private fun localCanvasLayoutSpec(index: Int, id: String, palette: String,
            contentId: String?): String = """{
            "layoutIndex":$index,"layoutID":"$id","paletteID":"$palette",
            "activeComponents":{},
            "activePrimarySelections":${if (contentId == null) "[]" else
                "[{\"zoneIndex\":0,\"componentIndex\":0}]"},
            "slotIngresses":[],
            "localCanvasSlots":${if (contentId == null) "[]" else
                "[{\"zoneIndex\":0,\"componentIndex\":0,\"slotIndex\":0," +
                    "\"contentId\":\"$contentId\"}]"}
          }""".trimIndent()

        fun videoZone(stream: String) = """{
            "id":"video-zone","frame":[[0,0],[800,480]],"type":{"single":{}},
            "components":[{"id":"primary","type":"primary","size":[800,480],
              "slots":[{"style":{"rectangular":{}},"frame":[[10,10],[100,60]],
                "zIndex":0,"positionId":"video","instrument":{
                  "kind":{"inputStream":{"_0":"$stream"}},
                  "backgroundColor":"#00000000","clipsToShape":false}}]}]
        }""".trimIndent()

        fun ingressSpec(stream: String) = UiSyncRendererIngressSpec(
            UltraIngressId("$stream-ingress"), UltraAirPlayStreamIdentity(110, stream, null),
        )

        fun identity(): MfiAuthenticationIdentity = MfiAuthenticationIdentity.capture(
            object : MfiAuthenticator {
                override val certificateType = MfiCertificateType.MFI
                override fun protocolMajor(): Int = 1
                override fun readCertificate(maximumOutputLength: Int): ByteArray =
                    "signed-layout-controller-test".encodeToByteArray()
                override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)
            },
        )

        fun profile(
            identity: MfiAuthenticationIdentity,
            programJson: ByteArray,
            signedLocalCatalog: String? = null,
            v3Catalog: Boolean = false,
        ): VerifiedUltraProfile {
            val bytes = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    (listOf(
                        UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to ByteArray(0),
                        UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to programJson,
                        UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".encodeToByteArray(),
                        UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                            BplistCodec.encode(mapOf("Bundle" to "Phone")),
                        "CarPlayPhone.assets/layout.bin" to byteArrayOf(1),
                        UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to byteArrayOf(2),
                        UltraProfilePackageFiles.CERTIFICATE_CHAIN to byteArrayOf(3),
                        UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to byteArrayOf(4),
                        UltraProfilePackageFiles.ACCESSORY_ADDITIONS to byteArrayOf(5),
                        UltraProfilePackageFiles.INFO_PLIST to
                            BplistCodec.encode(mapOf("Version" to "1")),
                    ) + listOfNotNull(signedLocalCatalog?.let {
                        (UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY +
                            if (v3Catalog) "xcertplay-bench-uisync-v3.json" else
                                "xcertplay-bench-uisync-v2.json") to it.encodeToByteArray()
                    })).forEach { (path, content) ->
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(content)
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
            val inspector = UltraProfilePackageInspector()
            val candidate = (inspector.inspect(ByteArrayInputStream(bytes)) as
                UltraProfileInspectionResult.Candidate).profile
            val trust = UltraProfileTrustBinding(
                "vehicle",
                "xcertplay-mfi-authentication-v1:${identity.certificateType.name}:${identity.sha256Hex()}",
                "asset", "ios", "accessory", candidate.contentDigest,
            )
            return (inspector.verify(candidate, trust) {
                UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(trust, byteArrayOf(1)))
            } as UltraProfileVerificationResult.Verified).profile
        }

        fun output(id: String, suffix: Int, uiSyncId: UltraUiSyncDisplayId) =
            UltraDisplayGraphOutput(
                UltraTopologyDisplayId(UUID(0L, suffix.toLong())),
                UltraDisplayOutputId.Android(id), uiSyncId,
            )

        fun assignments(
            profile: VerifiedUltraProfile,
            outputs: List<UltraDisplayGraphOutput>,
            programJson: ByteArray,
            ingressSpecs: List<UiSyncRendererIngressSpec>,
            withVideoSlots: Boolean,
            withLocalCanvasSlots: Boolean,
            withCarouselVariants: Boolean,
            noMainPrimaryIntent: Boolean,
        ): List<CompiledUiSyncDisplayAssignment> {
            val digest = profile.candidate.entries.single {
                it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
            }.digest
            val displays = CarPlayThemeSchema.parseArcPackageJson(programJson).displays
            return outputs.mapIndexed { index, output ->
                val display = displays[index]
                if (withCarouselVariants && index == 0) {
                    fun variant(componentIndex: Int, componentId: String, paletteId: String,
                        contentId: String): Triple<CompiledUiSyncThemeIntent,
                        CompiledUiSyncThemeProgramSelection,
                        CompiledUiSyncLocalCanvasAssignment> {
                        val state = CarPlayThemeSceneState(listOf(
                            CarPlayThemePrimarySelection(0, 0, componentIndex),
                        ))
                        val program = CarPlayThemeProgramCompiler().compile(display, state)
                            .structuralProgram
                        val slot = program.instructions
                            .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                            .single { it.address.component.zone.layout.layoutIndex == 0 }
                        return Triple(
                            CompiledUiSyncThemeIntent("main-a",
                                mapOf("main-carousel-zone" to componentId), paletteId),
                            CompiledUiSyncThemeProgramSelection(0, state,
                                program.structuralFingerprint),
                            CompiledUiSyncLocalCanvasAssignment(slot.address, contentId),
                        )
                    }
                    val mapA = variant(0, "map", "palette-a", "canvas.main.bench")
                    val mediaA = variant(1, "media", "palette-a",
                        "canvas.main.carousel.media")
                    val mapB = variant(0, "map", "palette-b",
                        "canvas.main.carousel.map.alt")
                    return@mapIndexed CompiledUiSyncDisplayAssignment(
                        digest, index, display.id, DisplayType.CENTER_CONSOLE,
                        DisplayDestination.MAIN, output.topologyDisplayId, output.outputId,
                        requireNotNull(output.uiSyncDisplayId), mapA.second, emptyList(),
                        primaryIntent = mapA.first,
                        additionalVariants = listOf(mediaA, mapB).map { variant ->
                            CompiledUiSyncThemeVariant(variant.first, variant.second,
                                emptyList(), listOf(variant.third))
                        },
                        localCanvasAssignments = listOf(mapA.third),
                    )
                }
                fun selection(layoutIndex: Int): CompiledUiSyncThemeProgramSelection {
                    val state = sceneState(index, layoutIndex, withVideoSlots,
                        withLocalCanvasSlots)
                    return CompiledUiSyncThemeProgramSelection(
                        layoutIndex, state,
                        CarPlayThemeProgramCompiler().compile(display, state)
                            .structuralProgram.structuralFingerprint,
                    )
                }
                fun slotAssignments(layoutIndex: Int): List<CompiledUiSyncSlotIngressAssignment> =
                    CarPlayThemeProgramCompiler().compile(display,
                        sceneState(index, layoutIndex, withVideoSlots, withLocalCanvasSlots))
                        .structuralProgram.instructions
                        .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                        .filter { it.address.component.zone.layout.layoutIndex == layoutIndex &&
                            it.source is CarPlayThemeStructuralSource.InputStream }
                        .map { slot ->
                            val stream = (slot.source as CarPlayThemeStructuralSource.InputStream)
                                .streamIdentifier
                            CompiledUiSyncSlotIngressAssignment(
                                slot.address, stream, ingressSpecs.single {
                                    it.airPlayStreamIdentity.streamConnectionId == stream
                                },
                            )
                        }
                fun localAssignments(layoutIndex: Int): List<CompiledUiSyncLocalCanvasAssignment> =
                    if (!withLocalCanvasSlots || index != 0) emptyList() else {
                        CarPlayThemeProgramCompiler().compile(display,
                            sceneState(index, layoutIndex, withVideoSlots,
                                withLocalCanvasSlots))
                            .structuralProgram.instructions
                            .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                            .filter { it.address.component.zone.layout.layoutIndex == layoutIndex &&
                                it.source == CarPlayThemeStructuralSource.Empty }
                            .map { slot -> CompiledUiSyncLocalCanvasAssignment(
                                slot.address,
                                if (layoutIndex == 0) "canvas.main.a" else "canvas.main.b",
                            ) }
                    }
                val role = if (index == 0) DisplayDestination.MAIN else DisplayDestination.CLUSTER
                CompiledUiSyncDisplayAssignment(
                    digest, index, display.id,
                    if (index == 0) DisplayType.CENTER_CONSOLE else DisplayType.CLUSTER,
                    role, output.topologyDisplayId, output.outputId,
                    requireNotNull(output.uiSyncDisplayId), selection(0), slotAssignments(0),
                    primaryIntent = if (index == 0 && noMainPrimaryIntent) null else
                        CompiledUiSyncThemeIntent(display.layouts[0].id,
                            emptyMap(), "palette-a"),
                    additionalVariants = if (index == 0 && noMainPrimaryIntent) emptyList()
                        else listOf(CompiledUiSyncThemeVariant(
                            CompiledUiSyncThemeIntent(display.layouts[1].id, emptyMap(),
                                "palette-b"),
                            selection(1), slotAssignments(1), localAssignments(1),
                        )),
                    localCanvasAssignments = localAssignments(0),
                )
            }
        }

        fun sessionContext(
            source: UiSyncPresentationSource,
            outputs: List<UltraDisplayGraphOutput>,
            ingressSpecs: List<UiSyncRendererIngressSpec>,
            schema: UiSyncSchemaFingerprint,
            binding: UiSyncBindingFingerprint,
        ): UiSyncRendererSessionBindingContext =
            UiSyncRendererSessionBindingContext::class.java.declaredConstructors
                .single { it.parameterCount == 5 }.apply { isAccessible = true }
                .newInstance(source, UiSyncRendererStaticPlan(outputs, ingressSpecs), schema,
                    binding, outputs.map { requireNotNull(it.uiSyncDisplayId).value }.toSet()) as
                UiSyncRendererSessionBindingContext

        fun binding(
            profile: VerifiedUltraProfile,
            programJson: ByteArray,
            output: UltraDisplayGraphOutput,
            destination: DisplayDestination,
            index: Int,
            ingressSpecs: List<UiSyncRendererIngressSpec>,
            streams: Map<String, AirPlayVideoStreamHandle>,
            withVideoSlots: Boolean,
            withLocalCanvasSlots: Boolean,
        ): UltraThemeOutputSceneBinding {
            val display = CarPlayThemeSchema.parseArcPackageJson(programJson).displays[index]
            val state = sceneState(index, 0, withVideoSlots, withLocalCanvasSlots)
            val program = CarPlayThemeProgramCompiler().compile(display, state).structuralProgram
            val fingerprint = program.structuralFingerprint
            val slots = program.instructions.filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                .filter { it.address.component.zone.layout.layoutIndex == 0 &&
                    it.source is CarPlayThemeStructuralSource.InputStream }
                .map { slot ->
                    val stream = (slot.source as CarPlayThemeStructuralSource.InputStream)
                        .streamIdentifier
                    val spec = ingressSpecs.single {
                        it.airPlayStreamIdentity.streamConnectionId == stream
                    }
                    UltraThemeSlotIngressBinding(slot.address, stream, spec.id,
                        requireNotNull(streams[stream]))
                }
            val digest = profile.candidate.entries.single {
                it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
            }.digest
            return UltraThemeOutputSceneBinding(
                UltraThemeVerifiedProgramSource(
                    profile, UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
                    digest, fingerprint, index, 0, state,
                ),
                destination, output.outputId, PixelSize(800, 480),
                CarPlayLogicalPixelMapper(
                    AxisAlignedPixelAffine(1.0, 1.0, 0.0, 0.0),
                    NegativeExtentPolicy.REJECT,
                    PixelEdgeQuantization.REQUIRE_INTEGRAL,
                    PixelEdgeQuantization.REQUIRE_INTEGRAL,
                ),
                slots,
            )
        }

        fun sceneState(
            displayIndex: Int,
            layoutIndex: Int,
            withVideoSlots: Boolean,
            withLocalCanvasSlots: Boolean,
        ) =
            if ((withVideoSlots || withLocalCanvasSlots) && displayIndex == 0) CarPlayThemeSceneState(
                listOf(CarPlayThemePrimarySelection(layoutIndex, 0, 0)),
            ) else CarPlayThemeSceneState()

        fun syntheticHandle(identity: AirPlayStreamIdentity): AirPlayVideoStreamHandle {
            val epoch = Class.forName("com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch")
                .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            return AirPlayVideoStreamHandle::class.java.declaredConstructors
                .single { it.parameterCount == 3 }.apply { isAccessible = true }
                .newInstance(identity, epoch, null) as AirPlayVideoStreamHandle
        }

        fun screenContext(stream: AirPlayVideoStreamHandle, ingressId: UltraIngressId):
            UiSyncRendererScreenContext = UiSyncRendererScreenContext::class.java
            .declaredConstructors.single { it.parameterCount == 2 }
            .apply { isAccessible = true }
            .newInstance(stream, UltraDisplayGraphIngress(ingressId, stream)) as
            UiSyncRendererScreenContext

        private class ValidSurface : Surface(null as SurfaceTexture?) {
            override fun isValid() = true
        }

        fun target(id: String) = AndroidDisplaySurfaceTarget(
            validSurface(), DisplaySurfaceDescriptor(DisplaySurfaceId(id), PixelSize(800, 480)),
        )

        private fun validSurface(): Surface {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return (field.get(null) as Unsafe).allocateInstance(ValidSurface::class.java) as Surface
        }

        fun surface(): Surface {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return (field.get(null) as Unsafe).allocateInstance(Surface::class.java) as Surface
        }

        fun canvas(): Canvas {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return (field.get(null) as Unsafe).allocateInstance(Canvas::class.java) as Canvas
        }
    }
}
