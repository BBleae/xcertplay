package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncChannelDescriptor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpoint
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEventSink
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestination
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestinationRole
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAssignment
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputPreflightContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRequirement
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRoute
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalTargetVersion
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayUltraSessionIntegrationTest {
    @Test
    fun authenticatedInfoFirstFallsBackToOrdinaryFeaturesWithoutContradictingSidecars() {
        val logs = CopyOnWriteArrayList<String>()
        TestControlConnection(ultraConfig(), RecordingMediaHandler(), logs = logs).use { connection ->
            fun info(cseq: Int) = connection.request(
                cseq = cseq,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            ).bodyMap()

            val first = info(1)
            assertFalse(first.containsKey("vehicleStateProtocolInfo"))
            assertFalse(first.containsKey("uiSyncInfo"))
            assertNull(getPrivate(connection.session, "eventServer"))
            assertEquals(
                listOf("uiContext", "altScreen"),
                connection.negotiate(listOf("uiContext", "altScreen", "uiSync"), cseq = 2),
            )
            assertArrayEquals(BplistCodec.encode(first), BplistCodec.encode(info(3)))
            assertTrue(logs.any {
                it.contains("/info order=info-first") &&
                    it.contains("ultraSidecars=vsp:false,uiSync:false")
            })
        }
    }

    @Test
    fun infoFirstNeverPublishesUnnegotiatedUltraSidecars() {
        data class Case(
            val config: AirPlayConfig,
            val media: RecordingMediaHandler,
            val authenticated: Boolean = true,
            val expectedVsp: Boolean = false,
            val expectedUiSync: Boolean = false,
        )
        val uiSyncOnlyMedia = RecordingMediaHandler().apply {
            readyRcsUuids = setOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid.toString().uppercase())
        }
        val cases = listOf(
            Case(ultraConfig(), RecordingMediaHandler(), authenticated = false),
            Case(ultraConfig(), RecordingMediaHandler()),
            Case(ultraConfig(), uiSyncOnlyMedia),
            Case(baseConfig(wirelessAudio = true), RecordingMediaHandler()),
        )
        cases.forEach { case ->
            TestControlConnection(
                case.config,
                case.media,
                authenticated = case.authenticated,
            ).use { connection ->
                val info = connection.request(
                    cseq = 1,
                    body = BplistCodec.encode(emptyMap<String, Any?>()),
                    method = "POST",
                    path = "/info",
                ).bodyMap()
                assertEquals(case.expectedVsp, "vehicleStateProtocolInfo" in info)
                assertEquals(case.expectedUiSync, "uiSyncInfo" in info)
            }
        }

        // Marking Pair Verify as complete is insufficient if this request was plaintext.
        TestControlConnection(
            ultraConfig(),
            RecordingMediaHandler(),
            authenticated = true,
            startEncrypted = false,
            installControlKeys = false,
        ).use { connection ->
            val info = connection.request(
                cseq = 1,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            )
            assertEquals(403, info.status())
        }

        TestControlConnection(
            ultraConfig(),
            RecordingMediaHandler(),
            sessionTransport = AirPlaySessionTransport.WIRED_NCM,
        ).use { connection ->
            val info = connection.request(
                cseq = 1,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            ).bodyMap()
            assertFalse("vehicleStateProtocolInfo" in info)
            assertFalse("uiSyncInfo" in info)
        }
    }

    @Test
    fun discoveryInfoDoesNotFreezeTheLaterAuthenticatedUltraScope() {
        TestControlConnection(
            ultraConfig(),
            RecordingMediaHandler(),
            authenticated = false,
        ).use { connection ->
            val discovery = connection.request(
                cseq = 1,
                body = BplistCodec.encode(
                    mapOf("altScreenURLs" to listOf("app://pre-auth-cluster")),
                ),
                method = "POST",
                path = "/info",
            ).bodyMap()
            assertFalse("vehicleStateProtocolInfo" in discovery)
            assertFalse("uiSyncInfo" in discovery)

            val control = checkNotNull(getPrivate(connection.session, "displayControl"))
            assertEquals(listOf("app://pre-auth-cluster"), getPrivate(control, "altUrls"))
            val exchange = getPrivate(control, "featureExchange") as AirPlayFeatureExchange
            val transport = AirPlayTransportReadiness(
                    secureControlChannel = true,
                    eventChannel = true,
                    alternateScreenSink = true,
                    readyRcsClientTypes = setOf(
                        RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                        RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                        RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
                    ),
                    wirelessSession = true,
            )
            assertEquals(listOf("uiContext", "altScreen", "vehicleStateProtocol", "uiSync"),
                exchange.negotiate(
                    listOf("uiContext", "altScreen", "vehicleStateProtocol", "uiSync"),
                    transport,
                )
                    .enabledFeatures)
            val authenticatedScope = exchange.infoScope(transport)
            assertNotNull(authenticatedScope.sidecar(AirPlayFeature.VEHICLE_STATE_PROTOCOL))
            assertNotNull(authenticatedScope.sidecar(AirPlayFeature.UI_SYNC))
        }
    }

    @Test
    fun setupFirstInfoCarriesOnlyPhoneRequestedUltraSidecars() {
        val logs = CopyOnWriteArrayList<String>()
        TestControlConnection(ultraConfig(), RecordingMediaHandler(), logs = logs).use { connection ->
            assertEquals(
                listOf("vehicleStateProtocol"),
                connection.negotiate(listOf("vehicleStateProtocol")),
            )
            val info = connection.request(
                cseq = 2,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            ).bodyMap()
            assertTrue("vehicleStateProtocolInfo" in info)
            assertFalse("uiSyncInfo" in info)
            assertTrue(logs.any {
                it.contains("/info order=setup-first") &&
                    it.contains("ultraSidecars=vsp:true,uiSync:false")
            })
        }
    }

    @Test
    fun mainDdpUiSyncSetupInfoAndClusterControlShareOneEncryptedControlSession() {
        // ultraConfig, physical-panel liveness, DDP output and returned dataPort are test
        // fixtures. This proves the encrypted RTSP control path, not an actual Host panel.
        val config = mainDdpUiSyncConfig()
        val media = RecordingMediaHandler()
        TestControlConnection(config, media).use { connection ->
            val setup = connection.negotiateResponse(listOf("uiContext", "uiSync"))
            assertEquals(200, setup.status())
            assertEquals("1", setup.headers["cseq"])
            assertEquals(listOf("uiContext", "uiSync"), setup.bodyMap()["enabledFeatures"])

            val infoResponse = connection.request(
                cseq = 2,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            )
            assertEquals(200, infoResponse.status())
            assertEquals("2", infoResponse.headers["cseq"])
            val info = infoResponse.bodyMap()
            assertEquals(config.ultra!!.uiSyncInfo!!.toWireMap(), info["uiSyncInfo"])
            val displays = (info["displays"] as List<*>).map { it as Map<*, *> }
            assertEquals(listOf(AirPlayInfoPlist.MAIN_UUID), displays.map { it["uuid"] })
            val panel = (info["displayPanels"] as List<*>).single() as Map<*, *>
            assertEquals(listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY), panel["properties"])
            assertEquals(800L, panel["widthPixels"])
            assertEquals(480L, panel["heightPixels"])
            val initial = (panel["initialVideoStreams"] as List<*>).single() as Map<*, *>
            assertEquals(AirPlayInfoPlist.MAIN_UUID, initial["uuid"])
            val plugin = (panel["displayPlugins"] as List<*>).single() as Map<*, *>
            assertEquals(AirPlayInfoPlist.MAIN_UUID, plugin["uid"])
            val managed = (plugin["managedVideoStreams"] as List<*>).single() as Map<*, *>
            assertEquals(AirPlayInfoPlist.MAIN_UUID, managed["uuid"])

            val clusterControl = dataStream(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 203)
            val identity = AirPlayStreamIdentity.from(130, clusterControl)
            val streamSetup = connection.request(3, streamsBody(clusterControl))
            assertEquals(200, streamSetup.status())
            assertEquals("3", streamSetup.headers["cseq"])
            assertEquals(1, streamSetup.streams().size)
            await { identity in media.committed }
            assertTrue(identity in connection.session.activeStreams)

            // The fixture accepts and retires type-130 via RTSP but does not open an RCS data
            // socket. RESET/READY belongs to the separate Debug E2E fixture, not this test.
            val teardown = connection.teardown(4, clusterControl)
            assertEquals(200, teardown.status())
            assertEquals("4", teardown.headers["cseq"])
            await { identity in media.tornDown }
            assertFalse(identity in connection.session.activeStreams)
            assertEquals(0, connection.sessionEndedCount.get())
        }
    }

    @Test
    fun mainDdpUiSyncIsNotClaimedWhenUnconfiguredOrClusterControlHandlerUnavailable() {
        val configured = mainDdpUiSyncConfig()
        val unavailable = RecordingMediaHandler().apply { readyRcsUuids = emptySet() }
        listOf(
            configured.copy(ultra = null) to RecordingMediaHandler(),
            configured to unavailable,
        ).forEach { (config, media) ->
            TestControlConnection(config, media).use { connection ->
                val setup = connection.negotiateResponse(listOf("uiContext", "uiSync"))
                assertEquals(200, setup.status())
                assertFalse("uiSync" in (setup.bodyMap()["enabledFeatures"] as List<*>))
                val infoResponse = connection.request(
                    cseq = 2,
                    body = BplistCodec.encode(emptyMap<String, Any?>()),
                    method = "POST",
                    path = "/info",
                )
                assertEquals(200, infoResponse.status())
                val info = infoResponse.bodyMap()
                assertFalse("uiSyncInfo" in info)
                assertFalse("displayPanels" in info)
                val refused = connection.request(
                    3,
                    streamsBody(dataStream(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 204)),
                )
                assertEquals(200, refused.status())
                assertEquals(emptyList<Any?>(), refused.streams())
                assertTrue(media.prepared.isEmpty())
                assertTrue(media.committed.isEmpty())
                assertTrue(connection.session.activeStreams.isEmpty())
            }
        }
    }

    @Test
    fun diagnosticMainDdpMetadataDoesNotMasqueradeAsNegotiatedUiSync() {
        val configured = mainDdpUiSyncConfig()
        val diagnostic = configured.copy(
            ultra = null,
            requireMainUiSyncForDisplayPanels = false,
        )
        val media = RecordingMediaHandler()
        TestControlConnection(diagnostic, media).use { connection ->
            val setup = connection.negotiateResponse(listOf("uiContext", "uiSync"))
            assertEquals(200, setup.status())
            assertFalse("uiSync" in (setup.bodyMap()["enabledFeatures"] as List<*>))
            val infoResponse = connection.request(
                cseq = 2,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            )
            assertEquals(200, infoResponse.status())
            val info = infoResponse.bodyMap()
            assertEquals(1, (info["displayPanels"] as List<*>).size)
            assertFalse("uiSyncInfo" in info)
            val refused = connection.request(
                3,
                streamsBody(dataStream(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 205)),
            )
            assertEquals(200, refused.status())
            assertEquals(
                emptyList<Any?>(),
                refused.streams(),
            )
            assertTrue(media.prepared.isEmpty())
            assertTrue(media.committed.isEmpty())
            assertTrue(connection.session.activeStreams.isEmpty())
        }
    }

    @Test
    fun setupFirstPhysicalRevocationRejectsInfoAndRetiresControlSession() {
        val physicalCurrent = AtomicBoolean(true)
        TestControlConnection(
            ultraConfig(currentProbe = physicalCurrent::get),
            RecordingMediaHandler(),
        ).use { connection ->
            assertEquals(
                listOf("uiContext", "altScreen", "uiSync"),
                connection.negotiate(listOf("uiContext", "altScreen", "uiSync")),
            )
            physicalCurrent.set(false)
            val rejected = connection.request(
                cseq = 2,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            )
            assertEquals(409, rejected.status())
            await { connection.sessionEndedCount.get() == 1 }
        }
    }

    @Test
    fun changedRepeatedFeatureSetupReturns409AndRetiresOldUltraAdmission() {
        TestControlConnection(ultraConfig(), RecordingMediaHandler()).use { connection ->
            assertEquals(
                listOf("uiContext", "altScreen", "uiSync"),
                connection.negotiate(listOf("uiContext", "altScreen", "uiSync")),
            )
            val rejected = connection.negotiateResponse(emptyList(), cseq = 2)
            assertEquals(409, rejected.status())
            await { connection.sessionEndedCount.get() == 1 }
            assertTrue(connection.session.isClosed)
        }
    }

    @Test
    fun repeatedFeatureSetupAfterRcsReadinessLossRetiresOldUltraAdmission() {
        val media = RecordingMediaHandler()
        TestControlConnection(ultraConfig(), media).use { connection ->
            val requested = listOf("uiContext", "altScreen", "uiSync")
            assertEquals(requested, connection.negotiate(requested))
            media.readyRcsUuids = emptySet()
            val rejected = connection.negotiateResponse(requested, cseq = 2)
            assertEquals(409, rejected.status())
            await { connection.sessionEndedCount.get() == 1 }
            assertTrue(connection.session.isClosed)
        }
    }

    @Test
    fun identicalRepeatedFeatureSetupStillCannotAllocateAnotherTimingAndEventPair() {
        TestControlConnection(ultraConfig(), RecordingMediaHandler()).use { connection ->
            val requested = listOf("uiContext", "altScreen", "uiSync")
            assertEquals(requested, connection.negotiate(requested))
            assertEquals(409, connection.negotiateResponse(requested, cseq = 2).status())
            await { connection.sessionEndedCount.get() == 1 }
        }
    }

    @Test
    fun frozenInfoScopeCannotOutliveItsUiSyncPhysicalPreflight() {
        val physicalCurrent = AtomicBoolean(true)
        TestControlConnection(
            ultraConfig(currentProbe = physicalCurrent::get),
            RecordingMediaHandler(),
        ).use { connection ->
            assertEquals(
                listOf("uiContext", "altScreen", "uiSync"),
                connection.negotiate(listOf("uiContext", "altScreen", "uiSync")),
            )
            assertNotNull(connection.request(
                cseq = 2,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            ).bodyMap()["uiSyncInfo"])
            physicalCurrent.set(false)
            val rejected = connection.request(
                cseq = 3,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            )
            assertEquals(409, rejected.status())
            await { connection.sessionEndedCount.get() == 1 }
        }
    }

    @Test
    fun setupFirstPhysicalPanelWithdrawalRejectsOldDdpInfoAndRetiresControlSession() {
        val panelCurrent = AtomicBoolean(true)
        val mainStream = AirPlayPhysicalVideoStream(AirPlayInfoPlist.MAIN_UUID, 0, 0, 0)
        val panels = AirPlayPhysicalDisplayPanels(listOf(AirPlayPhysicalDisplayPanel(
            widthPixels = 800,
            heightPixels = 480,
            widthPhysical = 200.0,
            heightPhysical = 120.0,
            initialVideoStreams = listOf(mainStream),
            displayPlugins = listOf(AirPlayPhysicalDisplayPlugin("main", listOf(mainStream))),
            properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
        )))
        val config = ultraConfig().copy(
            displayPanels = panels,
            displayPanelsLiveness = AirPlayPhysicalDisplayPanelsLiveness(
                panels, panelCurrent::get, { Closeable {} },
            ),
        )
        TestControlConnection(config, RecordingMediaHandler()).use { connection ->
            assertEquals(
                listOf("uiContext", "altScreen", "uiSync"),
                connection.negotiate(listOf("uiContext", "altScreen", "uiSync")),
            )
            panelCurrent.set(false)
            val rejected = connection.request(
                cseq = 2,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            )
            assertEquals(409, rejected.status())
            await { connection.sessionEndedCount.get() == 1 }
        }
    }

    @Test
    fun activePhysicalPanelWithdrawalRetiresExactSessionWithoutAnotherRequest() {
        val panelCurrent = AtomicBoolean(true)
        val changeListener = AtomicReference<(() -> Unit)?>(null)
        val mainStream = AirPlayPhysicalVideoStream(AirPlayInfoPlist.MAIN_UUID, 0, 0, 0)
        val panels = AirPlayPhysicalDisplayPanels(listOf(AirPlayPhysicalDisplayPanel(
            widthPixels = 800,
            heightPixels = 480,
            widthPhysical = 200.0,
            heightPhysical = 120.0,
            initialVideoStreams = listOf(mainStream),
            displayPlugins = listOf(AirPlayPhysicalDisplayPlugin("main", listOf(mainStream))),
            properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
        )))
        val proof = AirPlayPhysicalDisplayPanelsLiveness(panels, panelCurrent::get) { changed ->
            changeListener.set(changed)
            Closeable { changeListener.compareAndSet(changed, null) }
        }
        val config = ultraConfig().copy(displayPanels = panels, displayPanelsLiveness = proof)
        TestControlConnection(config, RecordingMediaHandler()).use { connection ->
            assertEquals(
                listOf("uiContext", "altScreen", "uiSync"),
                connection.negotiate(listOf("uiContext", "altScreen", "uiSync")),
            )
            val info = connection.request(
                cseq = 2,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            ).bodyMap()
            assertNotNull(info["uiSyncInfo"])
            val notifyChanged = requireNotNull(changeListener.get())

            panelCurrent.set(false)
            notifyChanged()
            await { connection.sessionEndedCount.get() == 1 }
            assertNull(changeListener.get())
            notifyChanged() // A late callback for this retired subscription cannot revive it.
            assertEquals(1, connection.sessionEndedCount.get())
        }
    }

    @Test
    fun synchronousPanelWithdrawalDuringSubscriptionRetiresWithoutDeadlock() {
        val panelCurrent = AtomicBoolean(true)
        val stream = AirPlayPhysicalVideoStream(AirPlayInfoPlist.MAIN_UUID, 0, 0, 0)
        val panels = AirPlayPhysicalDisplayPanels(listOf(AirPlayPhysicalDisplayPanel(
            widthPixels = 800,
            heightPixels = 480,
            widthPhysical = 200.0,
            heightPhysical = 120.0,
            initialVideoStreams = listOf(stream),
            displayPlugins = listOf(AirPlayPhysicalDisplayPlugin("main", listOf(stream))),
            properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
        )))
        val proof = AirPlayPhysicalDisplayPanelsLiveness(panels, panelCurrent::get) { changed ->
            panelCurrent.set(false)
            changed()
            Closeable {}
        }
        TestControlConnection(
            baseConfig(wirelessAudio = true).copy(
                displayPanels = panels,
                displayPanelsLiveness = proof,
            ),
            RecordingMediaHandler(),
        ).use { connection ->
            await { connection.sessionEndedCount.get() == 1 }
        }
    }

    @Test
    fun supersededPanelProofCallbackCannotRetireOverlayReplacement() {
        fun panels() : AirPlayPhysicalDisplayPanels {
            val stream = AirPlayPhysicalVideoStream(AirPlayInfoPlist.MAIN_UUID, 0, 0, 0)
            return AirPlayPhysicalDisplayPanels(listOf(AirPlayPhysicalDisplayPanel(
                widthPixels = 800,
                heightPixels = 480,
                widthPhysical = 200.0,
                heightPhysical = 120.0,
                initialVideoStreams = listOf(stream),
                displayPlugins = listOf(AirPlayPhysicalDisplayPlugin("main", listOf(stream))),
                properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
            )))
        }
        fun liveConfig(
            panels: AirPlayPhysicalDisplayPanels,
            current: AtomicBoolean,
            listener: AtomicReference<(() -> Unit)?>,
        ): AirPlayConfig = baseConfig(wirelessAudio = true).copy(
            displayPanels = panels,
            displayPanelsLiveness = AirPlayPhysicalDisplayPanelsLiveness(
                panels, current::get,
            ) { changed ->
                listener.set(changed)
                Closeable { listener.compareAndSet(changed, null) }
            },
        )

        val oldCurrent = AtomicBoolean(true)
        val newCurrent = AtomicBoolean(true)
        val oldListener = AtomicReference<(() -> Unit)?>(null)
        val newListener = AtomicReference<(() -> Unit)?>(null)
        val oldConfig = liveConfig(panels(), oldCurrent, oldListener)
        val newConfig = liveConfig(panels(), newCurrent, newListener)
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        val snapshot = requireNotNull(binding.resolve(generation, oldConfig))
        val overlayFactory = AirPlaySessionCapabilityOverlayFactory {
            AirPlaySessionCapabilityOverlay(newConfig)
        }
        TestControlConnection(
            oldConfig,
            RecordingMediaHandler(),
            runtimeSnapshot = snapshot,
            connectionOverlayAdmission = { it.admitConnectionOverlay(snapshot, overlayFactory) },
        ).use { connection ->
            await { oldListener.get() != null }
            val staleCallback = requireNotNull(oldListener.get())
            assertEquals(200, connection.request(
                cseq = 1,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            ).status())
            assertNull(oldListener.get())
            assertNotNull(newListener.get())

            oldCurrent.set(false)
            staleCallback()
            await {
                Thread.getAllStackTraces().keys.none {
                    it.name == "airplay-physical-panel-retirement" && it.isAlive
                }
            }
            assertEquals(200, connection.request(
                cseq = 2,
                body = BplistCodec.encode(emptyMap<String, Any?>()),
                method = "POST",
                path = "/info",
            ).status())
            assertEquals(0, connection.sessionEndedCount.get())

            newCurrent.set(false)
            requireNotNull(newListener.get()).invoke()
            await { connection.sessionEndedCount.get() == 1 }
        }
    }

    @Test
    fun setupGatesRcsTransportPerFeatureInsteadOfCrossDisablingUiSync() {
        val media = RecordingMediaHandler().apply {
            readyRcsUuids = setOf(
                RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid.toString().uppercase(),
            )
        }
        TestControlConnection(ultraConfig(), media).use { connection ->
            assertEquals(
                listOf("uiContext", "altScreen", "uiSync"),
                connection.negotiate(
                    listOf("uiContext", "altScreen", "vehicleStateProtocol", "uiSync"),
                ),
            )
        }
    }

    @Test
    fun type130RequiresAuthenticationNegotiationAndConcreteCreation() {
        val unauthenticatedMedia = RecordingMediaHandler()
        TestControlConnection(ultraConfig(), unauthenticatedMedia, authenticated = false).use { connection ->
            val response = connection.request(
                cseq = 1,
                body = streamsBody(dataStream(RcsClientTypes.IAP_CHANNEL, 1)),
            )
            assertEquals(403, response.status())
            assertTrue(unauthenticatedMedia.prepared.isEmpty())
        }

        val ultraOffMedia = RecordingMediaHandler()
        TestControlConnection(baseConfig(), ultraOffMedia).use { connection ->
            val enabled = connection.negotiate(
                listOf("viewAreas", "iAPChannel", "vehicleStateProtocol"),
            )
            assertEquals(listOf("viewAreas", "iAPChannel"), enabled)
            val response = connection.request(
                cseq = 2,
                body = streamsBody(dataStream(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 2)),
            )
            assertEquals(emptyList<Any?>(), response.streams())
            assertTrue(ultraOffMedia.prepared.isEmpty())
        }

        val createNullMedia = RecordingMediaHandler().apply {
            createNullFor += RcsClientTypes.CAR_PLAY_PROTOCOL_DATA.uuid.toString().uppercase()
        }
        TestControlConnection(ultraConfig(), createNullMedia).use { connection ->
            connection.negotiate(listOf("vehicleStateProtocol"))
            val response = connection.request(
                cseq = 3,
                body = streamsBody(dataStream(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 3)),
            )
            assertEquals(emptyList<Any?>(), response.streams())
            assertTrue(createNullMedia.committed.isEmpty())
        }
    }

    @Test
    fun fourDataChannelsAndOrdinaryAlternateScreenCoexistAndTeardownIsTargeted() {
        val media = RecordingMediaHandler()
        val connection = TestControlConnection(ultraConfig(), media)
        connection.use {
            val enabled = connection.negotiate(
                listOf(
                    "uiContext",
                    "altScreen",
                    "vehicleStateProtocol",
                    "uiSync",
                    "iAPChannel",
                ),
            )
            assertEquals(
                listOf(
                    "uiContext",
                    "altScreen",
                    "vehicleStateProtocol",
                    "uiSync",
                    "iAPChannel",
                ),
                enabled,
            )

            val requested = listOf(
                screenStream(type = 111, connectionId = 10),
                dataStream(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 11),
                dataStream(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, 12),
                dataStream(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 13),
                dataStream(RcsClientTypes.IAP_CHANNEL, 14),
            )
            val response = connection.request(10, streamsBody(*requested.toTypedArray()))
            assertEquals(5, response.streams().size)
            await { media.committed.size == 5 }
            assertEquals(4, media.committed.count { it.type == 130 })
            assertEquals(1, media.committed.count { it.type == 111 })

            val data2 = AirPlayStreamIdentity.from(
                130,
                dataStream(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, 12),
            )
            connection.teardown(11, dataStream(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, 12))
            await { data2 in media.tornDown }
            assertEquals(listOf(data2), media.tornDown.filter { it.type == 130 })

            connection.teardown(12, linkedMapOf("type" to 130))
            await { media.tornDown.count { it.type == 130 } == 4 }
            assertEquals(4, media.tornDown.filter { it.type == 130 }.distinct().size)
            assertFalse(media.tornDown.any { it.type == 111 })
        }

        // A new control session owns a new active-identity set; the same tuple is valid again.
        TestControlConnection(ultraConfig(), media).use { reconnect ->
            reconnect.negotiate(listOf("iAPChannel"))
            val response = reconnect.request(
                13,
                streamsBody(dataStream(RcsClientTypes.IAP_CHANNEL, 14)),
            )
            assertEquals(1, response.streams().size)
        }
    }

    @Test
    fun mainTeardownKeepsExactSelectorRetryableAcrossCoverAndRetirementFailure() {
        val coverAllowed = AtomicBoolean(false)
        val retirementAllowed = AtomicBoolean(false)
        val covers = AtomicInteger()
        val retirements = AtomicInteger()
        val stream = screenStream(type = 110, connectionId = 240)
        val identity = AirPlayStreamIdentity.from(110, stream)
        val media = object : AirPlayMediaHandler {
            override fun prepareScreen(
                session: AirPlaySession,
                identity: AirPlayStreamIdentity,
                stream: Map<String, Any?>,
            ) = AirPlayPreparedMediaStream(
                identity,
                linkedMapOf("type" to identity.type, "dataPort" to 20_240),
            )

            override fun beforeScreenTeardown(
                session: AirPlaySession,
                identity: AirPlayStreamIdentity,
            ): Boolean {
                assertEquals(110, identity.type)
                assertTrue(identity in session.activeStreams)
                covers.incrementAndGet()
                return coverAllowed.get()
            }

            override fun onTeardown(session: AirPlaySession, identity: AirPlayStreamIdentity) {
                assertTrue(identity in session.activeStreams)
                retirements.incrementAndGet()
                if (!retirementAllowed.get()) throw IOException("decoder retirement pending")
            }
        }
        TestControlConnection(ultraConfig(), media).use { connection ->
            connection.negotiate(listOf("uiContext"))
            assertEquals(1, connection.request(240, streamsBody(stream)).streams().size)
            assertTrue(identity in connection.session.activeStreams)

            val teardown = streamsBody(stream)
            assertEquals(503, connection.request(241, teardown, method = "TEARDOWN").status())
            assertTrue(identity in connection.session.activeStreams)
            assertEquals(0, retirements.get())

            coverAllowed.set(true)
            assertEquals(503, connection.request(242, teardown, method = "TEARDOWN").status())
            assertTrue(identity in connection.session.activeStreams)
            assertEquals(1, retirements.get())

            retirementAllowed.set(true)
            assertEquals(200, connection.request(243, teardown, method = "TEARDOWN").status())
            assertFalse(identity in connection.session.activeStreams)
            assertEquals(2, retirements.get())
            assertEquals(3, covers.get())
            assertEquals(0, connection.sessionEndedCount.get())
            assertEquals(200, connection.request(
                244, ByteArray(0), method = "GET", path = "/ping",
            ).status())
        }
    }

    @Test
    fun batchedSetupsCommitAgainstTheirOwnCseqAndCloseRollsBackPendingListener() {
        val media = RecordingMediaHandler()
        val logs = CopyOnWriteArrayList<String>()
        TestControlConnection(ultraConfig(), media, logs = logs).use { connection ->
            connection.negotiate(
                listOf("uiContext", "altScreen", "vehicleStateProtocol", "uiSync"),
            )
            val first = dataStream(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 21)
            val second = dataStream(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, 22)
            connection.sendBatch(
                requestBytes(20, streamsBody(first)),
                requestBytes(21, streamsBody(second)),
            )
            assertEquals(1, connection.readResponse().streams().size)
            assertEquals(1, connection.readResponse().streams().size)
            await { media.committed.size == 2 }
            assertEquals(
                listOf(
                    AirPlayStreamIdentity.from(130, first),
                    AirPlayStreamIdentity.from(130, second),
                ),
                media.committed.toList(),
            )
            await {
                logs.any { it.contains("SETUP committed cseq=20 streams=1") } &&
                    logs.any { it.contains("SETUP committed cseq=21 streams=1") }
            }

            val pending = dataStream(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, 23)
            media.blockNextPrepare = true
            connection.sendOnly(requestBytes(22, streamsBody(pending)))
            assertTrue(media.prepareEntered.await(3, TimeUnit.SECONDS))
            connection.session.close()
            media.releasePrepare.countDown()
            val pendingIdentity = AirPlayStreamIdentity.from(130, pending)
            await { pendingIdentity in media.rolledBack }
            assertFalse(pendingIdentity in media.committed)
        }
    }

    @Test
    fun repeatedActiveIdentityIsRejectedWithoutClosingOtherStreams() {
        val media = RecordingMediaHandler()
        TestControlConnection(ultraConfig(), media).use { connection ->
            connection.negotiate(listOf("vehicleStateProtocol"))
            val data = dataStream(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 31)
            assertEquals(1, connection.request(30, streamsBody(data)).streams().size)
            assertEquals(0, connection.request(31, streamsBody(data)).streams().size)
            assertEquals(1, media.prepared.count { it == AirPlayStreamIdentity.from(130, data) })
            assertTrue(media.tornDown.isEmpty())
        }
    }

    @Test
    fun plaintextProtectedRequestsStayForbiddenAfterPairVerifyStateIsSet() {
        val media = RecordingMediaHandler()
        TestControlConnection(
            ultraConfig(),
            media,
            authenticated = true,
            startEncrypted = false,
            installControlKeys = false,
        ).use { connection ->
            val empty = ByteArray(0)
            assertEquals(403, connection.request(40, empty, method = "RECORD").status())
            assertEquals(
                403,
                connection.request(
                    401,
                    BplistCodec.encode(linkedMapOf("features" to listOf("iAPChannel"))),
                ).status(),
            )
            assertEquals(null, getPrivate(connection.session, "eventServer"))
            assertEquals(
                403,
                connection.request(
                    41,
                    streamsBody(dataStream(RcsClientTypes.IAP_CHANNEL, 41)),
                ).status(),
            )
            assertEquals(403, connection.request(42, empty, method = "TEARDOWN").status())
            assertEquals(
                403,
                connection.request(
                    43,
                    BplistCodec.encode(linkedMapOf("type" to "requestUI")),
                    method = "POST",
                    path = "/command",
                ).status(),
            )
            assertEquals(
                403,
                connection.request(44, empty, method = "POST", path = "/feedback").status(),
            )
            assertEquals(0, connection.sessionActiveCount.get())
            assertEquals(0, connection.commandCount.get())
            assertTrue(media.prepared.isEmpty())
            assertTrue(media.tornDown.isEmpty())
        }
    }

    @Test
    fun plaintextPipelineAfterPairVerifyResponseIsDroppedWithoutExecution() {
        val media = RecordingMediaHandler()
        TestControlConnection(
            ultraConfig(),
            media,
            authenticated = false,
        ).use { connection ->
            val controllerId = "pipeline-test-controller".toByteArray(Charsets.UTF_8)
            val controllerKeys = AirPlayCrypto.ed25519Generate()
            connection.saveControllerPairing(controllerId.toString(Charsets.UTF_8), controllerKeys.publicKey)
            val clientEphemeral = AirPlayCrypto.x25519Generate()
            val m1 = Tlv8Codec.encode(listOf(
                Tlv8Item(0x06, byteArrayOf(1)),
                Tlv8Item(0x03, clientEphemeral.publicKey),
            ))
            val m2 = Tlv8Codec.decode(connection.request(
                49,
                m1,
                method = "POST",
                path = "/pair-verify",
            ).body)
            assertArrayEquals(byteArrayOf(2), m2[0x06])
            val serverEphemeral = checkNotNull(m2[0x03])
            val shared = AirPlayCrypto.x25519Shared(clientEphemeral.privateKey, serverEphemeral)
            clientEphemeral.privateKey.fill(0)
            val verifyKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Pair-Verify-Encrypt-Salt".asciiBytes(),
                "Pair-Verify-Encrypt-Info".asciiBytes(),
            )
            shared.fill(0)
            val controllerSignature = AirPlayCrypto.ed25519Sign(
                controllerKeys.privateKey,
                concatBytes(clientEphemeral.publicKey, controllerId, serverEphemeral),
            )
            val m3Inner = Tlv8Codec.encode(listOf(
                Tlv8Item(0x01, controllerId),
                Tlv8Item(0x0a, controllerSignature),
            ))
            val m3Encrypted = AirPlayCrypto.chachaSeal(
                verifyKey,
                AirPlayCrypto.nonceLabel("PV-Msg03"),
                m3Inner,
            )
            verifyKey.fill(0)
            val m3 = Tlv8Codec.encode(listOf(
                Tlv8Item(0x06, byteArrayOf(3)),
                Tlv8Item(0x05, m3Encrypted),
            ))
            connection.sendBatch(
                requestBytes(50, m3, method = "POST", path = "/pair-verify"),
                requestBytes(51, ByteArray(0), method = "RECORD"),
            )
            // The final pair-verify response itself remains plaintext.
            assertEquals(200, connection.readResponse().status())
            await { connection.sessionEndedCount.get() == 1 }
            assertEquals(0, connection.sessionActiveCount.get())
            assertEquals(-1, connection.rawInput().read())
        }
    }

    @Test
    fun encryptedControlRejectsRePairWithoutChangingItsVerifiedGeneration() {
        TestControlConnection(ultraConfig(), RecordingMediaHandler()).use { connection ->
            val pairVerify = connection.session.pairVerify
            setPrivate(pairVerify, "controllerId", "existing-controller")
            val cipher = checkNotNull(connection.session.cipher)
            val shared = checkNotNull(pairVerify.shared)
            val psk = checkNotNull(pairVerify.deriveFreshPairTlsPsk())
            val controlRead = checkNotNull(pairVerify.controlKeys).readKey
            val newClientEphemeral = AirPlayCrypto.x25519Generate()
            val m1 = Tlv8Codec.encode(listOf(
                Tlv8Item(0x06, byteArrayOf(1)),
                Tlv8Item(0x03, newClientEphemeral.publicKey),
            ))
            newClientEphemeral.privateKey.fill(0)

            assertEquals(403, connection.request(52, m1, method = "POST", path = "/pair-verify").status())
            assertEquals(403, connection.request(53, m1, method = "POST", path = "/pair-setup").status())
            assertSame(cipher, connection.session.cipher)
            assertTrue(pairVerify.isVerified)
            assertEquals("existing-controller", pairVerify.verifiedControllerId)
            assertArrayEquals(shared, pairVerify.shared)
            assertArrayEquals(psk, pairVerify.deriveFreshPairTlsPsk())
            assertArrayEquals(controlRead, pairVerify.controlKeys!!.readKey)
            assertNull(getPrivate(connection.session.pairSetup, "srp"))
        }
    }

    @Test
    fun setupAndSessionLimitsPreventListenerFanoutAndUuidRotation() {
        val oversizedMedia = RecordingMediaHandler()
        TestControlConnection(ultraConfig(), oversizedMedia).use { connection ->
            connection.negotiate(listOf("iAPChannel"))
            val oversized = (1L..17L).map { screenStream(110, it) }
            val response = connection.request(60, streamsBody(*oversized.toTypedArray()))
            assertEquals(400, response.status())
            assertTrue(oversizedMedia.prepared.isEmpty())
        }

        val uuidMedia = RecordingMediaHandler()
        TestControlConnection(ultraConfig(), uuidMedia).use { connection ->
            connection.negotiate(listOf("iAPChannel"))
            val first = dataStream(RcsClientTypes.IAP_CHANNEL, 61)
            val rotated = dataStream(RcsClientTypes.IAP_CHANNEL, 62)
            val response = connection.request(61, streamsBody(first, rotated))
            assertEquals(1, response.streams().size)
            assertEquals(1, uuidMedia.prepared.count { it.type == 130 })
            assertEquals(0, connection.request(62, streamsBody(rotated)).streams().size)
            assertEquals(1, uuidMedia.prepared.count { it.type == 130 })
        }

        val totalMedia = RecordingMediaHandler()
        TestControlConnection(ultraConfig(), totalMedia).use { connection ->
            connection.negotiate(emptyList())
            val maximum = (1L..16L).map { screenStream(110, 100 + it) }
            assertEquals(
                16,
                connection.request(63, streamsBody(*maximum.toTypedArray())).streams().size,
            )
            assertEquals(
                0,
                connection.request(64, streamsBody(screenStream(110, 999))).streams().size,
            )
            assertEquals(16, totalMedia.prepared.size)
        }
    }

    @Test
    fun eventChannelUsesExactBindAndRetriesOnlyBeforeAuthentication() {
        val media = RecordingMediaHandler()
        TestControlConnection(ultraConfig(), media).use { connection ->
            val setup = connection.negotiateResponse(emptyList())
            val eventPort = (setup.bodyMap()["eventPort"] as Number).toInt()
            val eventServer = getPrivate(connection.session, "eventServer") as ServerSocket
            assertEquals(connection.session.localAddress, eventServer.inetAddress)

            Socket(connection.session.localAddress, eventPort).use { invalid ->
                invalid.getOutputStream().apply {
                    // One complete but unauthenticated ControlCipher frame.
                    write(byteArrayOf(1, 0) + ByteArray(17) { 0x55 })
                    flush()
                }
            }

            Socket(connection.session.localAddress, eventPort).use { valid ->
                valid.soTimeout = 3_000
                val eventCipher = eventClientCipher()
                val command = BplistCodec.encode(linkedMapOf("type" to "requestUI"))
                val request = requestBytes(
                    70,
                    command,
                    method = "POST",
                    path = "/command",
                )
                valid.getOutputStream().apply {
                    write(eventCipher.encrypt(request))
                    flush()
                }
                val response = readEncryptedRtsp(valid.getInputStream(), eventCipher)
                assertEquals(200, response.status())
                await { connection.commandCount.get() == 1 }
                assertEquals(1, connection.hostUiRequestCount.get())
            }
            await { connection.sessionEndedCount.get() == 1 }
            assertTrue(connection.session.isClosed)
        }
    }

    @Test
    fun disconnectedEventCandidateRemainsOwnedForFinalKeyWipe() {
        TestControlConnection(ultraConfig(), RecordingMediaHandler()).use { connection ->
            val setup = connection.negotiateResponse(emptyList())
            val eventPort = (setup.bodyMap()["eventPort"] as Number).toInt()
            val event = Socket(connection.session.localAddress, eventPort)
            val candidate = try {
                await { getPrivate(connection.session, "eventCipher") != null }
                getPrivate(connection.session, "eventCipher") as ControlCipher
            } finally {
                event.close()
            }
            val readKey = getPrivate(candidate, "readKey") as ByteArray
            val writeKey = getPrivate(candidate, "writeKey") as ByteArray
            await { getPrivate(connection.session, "eventCipher") == null }
            assertTrue(readKey.any { it != 0.toByte() })
            assertTrue(writeKey.any { it != 0.toByte() })

            connection.session.close()

            assertTrue(readKey.all { it == 0.toByte() })
            assertTrue(writeKey.all { it == 0.toByte() })
        }
    }

    @Test
    fun validThenTamperedEventFramesInOneBatchTerminateTheWholeSession() {
        val media = RecordingMediaHandler()
        TestControlConnection(ultraConfig(), media).use { connection ->
            val setup = connection.negotiateResponse(emptyList())
            val eventPort = (setup.bodyMap()["eventPort"] as Number).toInt()
            Socket(connection.session.localAddress, eventPort).use { event ->
                val cipher = eventClientCipher()
                val valid = cipher.encrypt(
                    requestBytes(71, ByteArray(0), method = "GET", path = "/ping"),
                )
                val tampered = cipher.encrypt(
                    requestBytes(72, ByteArray(0), method = "GET", path = "/ping"),
                ).also { frame ->
                    frame[frame.lastIndex] = (frame.last().toInt() xor 0x01).toByte()
                }
                event.getOutputStream().apply {
                    // One write exercises the exact regression: the first frame consumes nonce
                    // zero successfully before the second frame fails authentication.
                    write(valid + tampered)
                    flush()
                }

                await { connection.sessionEndedCount.get() == 1 }
            }
            assertTrue(connection.session.isClosed)
            assertEquals(0, connection.commandCount.get())
        }
    }

    @Test
    fun authenticatedEventDisconnectTerminatesSessionInsteadOfReusingCipherNonce() {
        val media = RecordingMediaHandler()
        TestControlConnection(ultraConfig(), media).use { connection ->
            val setup = connection.negotiateResponse(emptyList())
            val eventPort = (setup.bodyMap()["eventPort"] as Number).toInt()
            Socket(connection.session.localAddress, eventPort).use { event ->
                event.soTimeout = 3_000
                val cipher = eventClientCipher()
                val command = BplistCodec.encode(linkedMapOf("type" to "requestUI"))
                event.getOutputStream().apply {
                    write(cipher.encrypt(requestBytes(75, command, method = "POST", path = "/command")))
                    flush()
                }
                assertEquals(200, readEncryptedRtsp(event.getInputStream(), cipher).status())
            }

            await { connection.sessionEndedCount.get() == 1 }
            assertTrue(connection.session.isClosed)
            assertEquals(1, connection.commandCount.get())

            val reconnect = runCatching {
                Socket().use { event ->
                    event.connect(
                        java.net.InetSocketAddress(connection.session.localAddress, eventPort),
                        500,
                    )
                    event.soTimeout = 500
                    val reusedCipher = eventClientCipher()
                    val command = BplistCodec.encode(linkedMapOf("type" to "requestUI"))
                    event.getOutputStream().apply {
                        write(
                            reusedCipher.encrypt(
                                requestBytes(76, command, method = "POST", path = "/command"),
                            ),
                        )
                        flush()
                    }
                    readEncryptedRtsp(event.getInputStream(), reusedCipher)
                }
            }
            assertTrue("an authenticated event channel must not accept a same-key reconnect", reconnect.isFailure)
            assertEquals(1, connection.commandCount.get())
        }
    }

    @Test
    fun controlStopSessionFlushesSuccessThenClosesOnceAndDropsPipelinedCommands() {
        val media = RecordingMediaHandler()
        TestControlConnection(
            sessionManagementConfig(),
            media,
            abortiveServerClose = true,
        ).use { connection ->
            assertEquals(
                listOf("sessionManagement"),
                connection.negotiate(listOf("sessionManagement")),
            )
            connection.sendBatch(
                requestBytes(
                    80,
                    stopSessionBody(1),
                    method = "POST",
                    path = "/command",
                ),
                requestBytes(
                    81,
                    BplistCodec.encode(linkedMapOf("type" to "requestUI")),
                    method = "POST",
                    path = "/command",
                ),
            )

            // The successful response is observable before the command-triggered session close.
            assertEquals(200, connection.readResponse().status())
            await { connection.sessionEndedCount.get() == 1 }
            assertTrue(connection.session.isClosed)
            assertEquals(1, media.sessionClosedCount.get())
            assertEquals(0, connection.commandCount.get())
            assertEquals(0, connection.hostUiRequestCount.get())
            assertEquals(-1, connection.rawInput().read())

            connection.session.close()
            connection.session.close()
            assertEquals(1, connection.sessionEndedCount.get())
            assertEquals(1, media.sessionClosedCount.get())
        }
    }

    @Test
    fun stoppingStateRejectsCommandsAcrossControlAndAuthenticatedEventChannels() {
        val media = RecordingMediaHandler()
        TestControlConnection(sessionManagementConfig(), media).use { connection ->
            val setup = connection.negotiateResponse(listOf("sessionManagement"))
            val eventPort = (setup.bodyMap()["eventPort"] as Number).toInt()
            Socket(connection.session.localAddress, eventPort).use { event ->
                event.soTimeout = 3_000
                val eventCipher = eventClientCipher()

                // Authenticate the event candidate without dispatching an application command.
                event.getOutputStream().apply {
                    write(
                        eventCipher.encrypt(
                            requestBytes(85, ByteArray(0), method = "GET", path = "/ping"),
                        ),
                    )
                    flush()
                }
                assertEquals(200, readEncryptedRtsp(event.getInputStream(), eventCipher).status())

                val stopRequest = RtspMessage.parseMessages(
                    requestBytes(
                        86,
                        stopSessionBody(1),
                        method = "POST",
                        path = "/command",
                    ),
                ).messages.single()
                val pendingStop = invokePrivate(
                    connection.session,
                    "handleCommand",
                    RtspMessage.Request::class.java,
                    stopRequest,
                )
                val stopResponse = getPrivate(pendingStop, "response") as RtspMessage.Response
                assertEquals(200, stopResponse.status ?: 200)
                assertTrue(connection.session.isClosed)

                val command = BplistCodec.encode(linkedMapOf("type" to "requestUI"))
                assertEquals(
                    409,
                    connection.request(87, command, method = "POST", path = "/command").status(),
                )
                event.getOutputStream().apply {
                    write(
                        eventCipher.encrypt(
                            requestBytes(88, command, method = "POST", path = "/command"),
                        ),
                    )
                    flush()
                }
                assertEquals(409, readEncryptedRtsp(event.getInputStream(), eventCipher).status())
                assertEquals(0, connection.commandCount.get())
                assertEquals(0, connection.hostUiRequestCount.get())

                invokePrivateNoArgs(pendingStop, "responseFlushed")
            }
            await { connection.sessionEndedCount.get() == 1 }
            assertEquals(1, media.sessionClosedCount.get())
        }
    }

    @Test
    fun stopSessionRejectsUnnegotiatedWiredAndMalformedCommandsWithoutClosing() {
        val media = RecordingMediaHandler()
        TestControlConnection(sessionManagementConfig(), media).use { connection ->
            assertTrue(connection.negotiate(emptyList()).isEmpty())
            assertEquals(
                400,
                connection.request(
                    90,
                    stopSessionBody(1),
                    method = "POST",
                    path = "/command",
                ).status(),
            )
            assertFalse(connection.session.isClosed)
            assertEquals(0, connection.sessionEndedCount.get())
        }

        TestControlConnection(sessionManagementConfig(wireless = false), media).use { connection ->
            assertTrue(connection.negotiate(listOf("sessionManagement")).isEmpty())
            assertEquals(
                400,
                connection.request(
                    91,
                    stopSessionBody(1),
                    method = "POST",
                    path = "/command",
                ).status(),
            )
            assertFalse(connection.session.isClosed)
        }

        TestControlConnection(sessionManagementConfig(), media).use { connection ->
            assertEquals(
                listOf("sessionManagement"),
                connection.negotiate(listOf("sessionManagement")),
            )
            val invalidBodies = listOf(
                BplistCodec.encode(linkedMapOf("type" to "stopSession")),
                BplistCodec.encode(
                    linkedMapOf("type" to "stopSession", "params" to listOf(1)),
                ),
                stopSessionBody(1, extraParam = true),
                stopSessionBody(1.0),
                stopSessionBody(0),
                stopSessionBody(3),
                BplistCodec.encode(
                    linkedMapOf(
                        "type" to "stopSession",
                        "params" to linkedMapOf("disconnectReason" to 1),
                        "extra" to true,
                    ),
                ),
            )
            invalidBodies.forEachIndexed { index, body ->
                assertEquals(
                    400,
                    connection.request(
                        100 + index,
                        body,
                        method = "POST",
                        path = "/command",
                    ).status(),
                )
                assertFalse(connection.session.isClosed)
            }
            assertEquals(
                200,
                connection.request(
                    110,
                    BplistCodec.encode(linkedMapOf("type" to "requestUI")),
                    method = "POST",
                    path = "/command",
                ).status(),
            )
            assertEquals(1, connection.commandCount.get())
            assertEquals(0, connection.sessionEndedCount.get())
        }
    }

    @Test
    fun wirelessOnlyFeaturesUseAttachmentProvenanceNotAudioConfiguration() {
        val media = RecordingMediaHandler()
        val spoofedWirelessAudio = sessionManagementConfig(wireless = true)
        TestControlConnection(
            spoofedWirelessAudio,
            media,
            sessionTransport = AirPlaySessionTransport.WIRED_NCM,
        ).use { connection ->
            assertTrue(connection.negotiate(listOf("sessionManagement")).isEmpty())
            assertEquals(
                400,
                connection.request(
                    115,
                    stopSessionBody(1),
                    method = "POST",
                    path = "/command",
                ).status(),
            )
            assertFalse(connection.session.isClosed)
        }

        val wirelessAttachment = sessionManagementConfig(wireless = false)
        TestControlConnection(
            wirelessAttachment,
            media,
            sessionTransport = AirPlaySessionTransport.WIRELESS_LOCAL_NETWORK,
        ).use { connection ->
            assertEquals(
                listOf("sessionManagement"),
                connection.negotiate(listOf("sessionManagement")),
            )
        }
    }

    @Test
    fun timedPresentationRequiresThisSessionToNegotiateUiSync() {
        val media = RecordingMediaHandler()
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { timingPeer ->
            TestControlConnection(ultraConfig(), media).use { connection ->
                val response = connection.negotiateResponse(
                    emptyList(),
                    timingPort = timingPeer.localPort,
                )
                assertTrue((response.bodyMap()["enabledFeatures"] as List<*>).isEmpty())
                completeSelectedTimingExchange(connection.session, timingPeer, response)
                assertNull(
                    connection.session.presentationMonotonicNanos(
                        matchingScreenTiming(connection.session),
                    ),
                )
            }
        }

        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { timingPeer ->
            TestControlConnection(ultraConfig(), media).use { connection ->
                val response = connection.negotiateResponse(
                    listOf("uiContext", "altScreen", "uiSync"),
                    timingPort = timingPeer.localPort,
                )
                assertEquals(
                    listOf("uiContext", "altScreen", "uiSync"),
                    response.bodyMap()["enabledFeatures"],
                )
                assertNull(
                    connection.session.presentationMonotonicNanos(
                        matchingScreenTiming(connection.session),
                    ),
                )
                completeSelectedTimingExchange(connection.session, timingPeer, response)
                assertNotNull(
                    connection.session.presentationMonotonicNanos(
                        matchingScreenTiming(connection.session),
                    ),
                )
            }
        }
    }

    @Test
    fun stoppingSessionRollsBackPreparedSetupWithoutCommitOrSuccessCallback() {
        val media = RecordingMediaHandler()
        TestControlConnection(sessionManagementConfig(), media).use { connection ->
            assertEquals(
                listOf("sessionManagement"),
                connection.negotiate(listOf("sessionManagement")),
            )
            val stopRequest = RtspMessage.parseMessages(
                requestBytes(
                    117,
                    stopSessionBody(1),
                    method = "POST",
                    path = "/command",
                ),
            ).messages.single()
            val pendingStop = invokePrivate(
                connection.session,
                "handleCommand",
                RtspMessage.Request::class.java,
                stopRequest,
            )
            assertTrue(connection.session.isClosed)

            val commits = AtomicInteger()
            val rollbacks = AtomicInteger()
            val identity = AirPlayStreamIdentity(110, "117", null)
            val prepared = AirPlayPreparedMediaStream(
                identity = identity,
                response = linkedMapOf("type" to 110, "dataPort" to 20_117),
                commitHook = {
                    commits.incrementAndGet()
                    true
                },
                rollbackHook = { rollbacks.incrementAndGet() },
            )
            invokePrivateTwoArgs(
                connection.session,
                "commitSetup",
                "117",
                listOf(prepared),
            )

            assertEquals(0, commits.get())
            assertEquals(1, rollbacks.get())
            assertEquals(0, media.setupResponseSentCount.get())
            assertFalse(identity in connection.session.activeStreams)

            invokePrivateNoArgs(pendingStop, "responseFlushed")
            await { connection.sessionEndedCount.get() == 1 }
        }
    }

    @Test
    fun stopEnteringAtOneCommitPointPreventsEveryLaterCommitAndCallback() {
        val media = RecordingMediaHandler()
        TestControlConnection(sessionManagementConfig(), media).use { connection ->
            connection.negotiate(listOf("sessionManagement"))
            val stopRequest = RtspMessage.parseMessages(
                requestBytes(
                    118,
                    stopSessionBody(2),
                    method = "POST",
                    path = "/command",
                ),
            ).messages.single()
            var pendingStop: Any? = null
            val firstCommits = AtomicInteger()
            val secondCommits = AtomicInteger()
            val secondRollbacks = AtomicInteger()
            val firstIdentity = AirPlayStreamIdentity(110, "118", null)
            val secondIdentity = AirPlayStreamIdentity(110, "119", null)
            val prepared = listOf(
                AirPlayPreparedMediaStream(
                    firstIdentity,
                    linkedMapOf("type" to 110, "dataPort" to 20_118),
                    commitHook = {
                        firstCommits.incrementAndGet()
                        // The monitor is reentrant. This deterministically models STOPPING becoming
                        // visible exactly at a commit boundary, without scheduler-dependent races.
                        pendingStop = invokePrivate(
                            connection.session,
                            "handleCommand",
                            RtspMessage.Request::class.java,
                            stopRequest,
                        )
                        true
                    },
                ),
                AirPlayPreparedMediaStream(
                    secondIdentity,
                    linkedMapOf("type" to 110, "dataPort" to 20_119),
                    commitHook = {
                        secondCommits.incrementAndGet()
                        true
                    },
                    rollbackHook = { secondRollbacks.incrementAndGet() },
                ),
            )

            invokePrivateTwoArgs(connection.session, "commitSetup", "118", prepared)

            assertEquals(1, firstCommits.get())
            assertEquals(0, secondCommits.get())
            assertEquals(1, secondRollbacks.get())
            assertEquals(0, media.setupResponseSentCount.get())
            assertTrue(firstIdentity in media.tornDown)
            assertFalse(firstIdentity in connection.session.activeStreams)
            assertFalse(secondIdentity in connection.session.activeStreams)

            invokePrivateNoArgs(checkNotNull(pendingStop), "responseFlushed")
            await { connection.sessionEndedCount.get() == 1 }
        }
    }

    @Test
    fun eventStopSessionUsesSameValidationAndFlushBeforeCloseContract() {
        val media = RecordingMediaHandler()
        TestControlConnection(sessionManagementConfig(), media).use { connection ->
            val setup = connection.negotiateResponse(listOf("sessionManagement"))
            assertEquals(
                listOf("sessionManagement"),
                setup.bodyMap()["enabledFeatures"],
            )
            val eventPort = (setup.bodyMap()["eventPort"] as Number).toInt()
            Socket(connection.session.localAddress, eventPort).use { event ->
                event.soTimeout = 3_000
                val eventCipher = eventClientCipher()

                event.getOutputStream().apply {
                    write(
                        eventCipher.encrypt(
                            requestBytes(
                                120,
                                stopSessionBody(9),
                                method = "POST",
                                path = "/command",
                            ),
                        ),
                    )
                    flush()
                }
                assertEquals(400, readEncryptedRtsp(event.getInputStream(), eventCipher).status())
                assertFalse(connection.session.isClosed)

                event.getOutputStream().apply {
                    write(
                        eventCipher.encrypt(
                            requestBytes(
                                121,
                                stopSessionBody(2),
                                method = "POST",
                                path = "/command",
                            ),
                        ),
                    )
                    flush()
                }
                assertEquals(200, readEncryptedRtsp(event.getInputStream(), eventCipher).status())
            }
            await { connection.sessionEndedCount.get() == 1 }
            assertEquals(1, media.sessionClosedCount.get())
            assertEquals(0, connection.commandCount.get())
        }
    }

    private class RecordingMediaHandler : AirPlayMediaHandler {
        val prepared = CopyOnWriteArrayList<AirPlayStreamIdentity>()
        val committed = CopyOnWriteArrayList<AirPlayStreamIdentity>()
        val rolledBack = CopyOnWriteArrayList<AirPlayStreamIdentity>()
        val tornDown = CopyOnWriteArrayList<AirPlayStreamIdentity>()
        val createNullFor = CopyOnWriteArrayList<String>()
        val prepareEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val sessionClosedCount = AtomicInteger()
        val setupResponseSentCount = AtomicInteger()
        private val nextPort = AtomicInteger(20_000)
        @Volatile var blockNextPrepare = false
        @Volatile var readyRcsUuids: Set<String>? = null

        override fun isRcsReady(
            session: AirPlaySession,
            clientTypes: Collection<RcsClientType>,
        ): Boolean = readyRcsUuids?.let { ready ->
            clientTypes.all { it.uuid.toString().uppercase() in ready }
        } ?: true

        override fun prepareScreen(
            session: AirPlaySession,
            identity: AirPlayStreamIdentity,
            stream: Map<String, Any?>,
        ): AirPlayPreparedMediaStream = prepared(identity)

        override fun prepareDataStream(
            session: AirPlaySession,
            identity: AirPlayStreamIdentity,
            clientType: RcsClientType,
            stream: Map<String, Any?>,
        ): AirPlayPreparedMediaStream? {
            if (clientType.uuid.toString().uppercase() in createNullFor) return null
            if (blockNextPrepare) {
                blockNextPrepare = false
                prepareEntered.countDown()
                releasePrepare.await(3, TimeUnit.SECONDS)
            }
            return prepared(identity)
        }

        override fun onTeardown(session: AirPlaySession, identity: AirPlayStreamIdentity) {
            tornDown += identity
        }

        override fun onSessionClosed(session: AirPlaySession) {
            sessionClosedCount.incrementAndGet()
        }

        override fun onSetupResponseSent(session: AirPlaySession) {
            setupResponseSentCount.incrementAndGet()
        }

        private fun prepared(identity: AirPlayStreamIdentity): AirPlayPreparedMediaStream {
            prepared += identity
            return AirPlayPreparedMediaStream(
                identity = identity,
                response = linkedMapOf("type" to identity.type, "dataPort" to nextPort.incrementAndGet()),
                commitHook = {
                    committed += identity
                    true
                },
                rollbackHook = { rolledBack += identity },
            )
        }
    }

    private class TestControlConnection(
        config: AirPlayConfig,
        media: AirPlayMediaHandler,
        authenticated: Boolean = true,
        startEncrypted: Boolean = authenticated,
        installControlKeys: Boolean = startEncrypted,
        private val logs: MutableList<String> = CopyOnWriteArrayList(),
        private val sessionTransport: AirPlaySessionTransport = if (config.wirelessAudio) {
            AirPlaySessionTransport.WIRELESS_LOCAL_NETWORK
        } else {
            AirPlaySessionTransport.WIRED_NCM
        },
        abortiveServerClose: Boolean = false,
        runtimeSnapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot? = null,
        connectionOverlayAdmission: ((AirPlaySession) -> Boolean)? = null,
    ) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort).apply {
            soTimeout = 3_000
        }
        private val server = listener.accept().apply {
            if (abortiveServerClose) setSoLinger(true, 0)
        }
        private val input = client.getInputStream()
        private val clientCipher: ControlCipher?
        val sessionActiveCount = AtomicInteger()
        val sessionEndedCount = AtomicInteger()
        val commandCount = AtomicInteger()
        val hostUiRequestCount = AtomicInteger()
        private val pairings = PairingStore()
        val session = AirPlaySession(
            socket = server,
            config = config,
            identity = AirPlayIdentity.generate(),
            pairings = pairings,
            mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) {
                    sessionActiveCount.incrementAndGet()
                }

                override fun onSessionEnded(session: AirPlaySession) {
                    sessionEndedCount.incrementAndGet()
                }

                override fun onHostUiRequested(session: AirPlaySession) {
                    hostUiRequestCount.incrementAndGet()
                }

                override fun onCommand(
                    session: AirPlaySession,
                    type: String,
                    params: Map<String, Any?>,
                ) {
                    commandCount.incrementAndGet()
                }

                override fun onDebugLog(message: String) {
                    logs += message
                }
            },
            media = media,
            sessionTransport = sessionTransport,
            runtimeSnapshot = runtimeSnapshot,
            connectionOverlayAdmission = connectionOverlayAdmission,
        )

        init {
            listener.close()
            if (authenticated) {
                val serverRead = ByteArray(32) { 0x31 }
                val serverWrite = ByteArray(32) { 0x52 }
                setPrivate(session.pairVerify, "sharedSecret", TEST_SHARED_SECRET.copyOf())
                setPrivate(session.pairVerify, "verified", true)
                if (installControlKeys) {
                    setPrivate(
                        session.pairVerify,
                        "keys",
                        PairVerify.ControlKeys(serverRead, serverWrite),
                    )
                }
                if (startEncrypted) {
                    session.cipher = ControlCipher(serverRead, serverWrite)
                    clientCipher = ControlCipher(serverWrite, serverRead)
                } else {
                    clientCipher = null
                }
            } else {
                clientCipher = null
            }
            session.start()
        }

        fun saveControllerPairing(identifier: String, publicKey: ByteArray) {
            pairings.save(identifier, publicKey)
        }

        fun negotiate(features: List<String>, cseq: Int = 1): List<String> {
            val response = negotiateResponse(features, cseq)
            @Suppress("UNCHECKED_CAST")
            return response.bodyMap()["enabledFeatures"] as? List<String> ?: emptyList()
        }

        fun negotiateResponse(
            features: List<String>,
            cseq: Int = 1,
            timingPort: Int = 0,
        ): RtspMessage.Request =
            request(
                cseq = cseq,
                body = BplistCodec.encode(
                    linkedMapOf<String, Any?>(
                        "name" to "test-phone",
                        "deviceID" to "02:00:00:00:00:03",
                        "features" to features,
                        "timingPort" to timingPort,
                    ),
                ),
            )

        fun request(
            cseq: Int,
            body: ByteArray,
            method: String = "SETUP",
            path: String = "rtsp://localhost/carplay",
        ): RtspMessage.Request {
            sendOnly(requestBytes(cseq, body, method, path))
            return readResponse()
        }

        fun teardown(cseq: Int, vararg streams: Map<String, Any?>): RtspMessage.Request {
            sendOnly(requestBytes(cseq, streamsBody(*streams), method = "TEARDOWN"))
            return readResponse()
        }

        fun sendBatch(vararg requests: ByteArray) {
            val wire = requests.fold(ByteArray(0)) { bytes, request ->
                bytes + (clientCipher?.encrypt(request) ?: request)
            }
            client.getOutputStream().apply {
                write(wire)
                flush()
            }
        }

        fun sendOnly(request: ByteArray) {
            val wire = clientCipher?.encrypt(request) ?: request
            client.getOutputStream().apply {
                write(wire)
                flush()
            }
        }

        fun readResponse(): RtspMessage.Request {
            val plaintext = if (clientCipher == null) readPlainRtsp(input) else {
                val header = readExactly(input, 2)
                val length = (header[0].toInt() and 0xff) or
                    ((header[1].toInt() and 0xff) shl 8)
                val frame = header + readExactly(input, length + 16)
                clientCipher.decrypt(frame).data
            }
            return RtspMessage.parseMessages(plaintext).messages.single()
        }

        fun rawInput(): InputStream = input

        override fun close() {
            session.close()
            runCatching { client.close() }
            runCatching { server.close() }
            runCatching { listener.close() }
        }
    }

    private companion object {
        fun baseConfig(
            ultra: AirPlayUltraConfiguration? = null,
            wirelessAudio: Boolean = false,
        ) = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            cluster = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 240),
            ultra = ultra,
            wirelessAudio = wirelessAudio,
        )

        fun ultraConfig(
            currentProbe: () -> Boolean = { true },
            mainDdp: Boolean = false,
        ): AirPlayConfig {
            fun ready(clientType: RcsClientType) = AirPlayRcsHandlerReadinessDescriptor(
                clientType = clientType,
                implementationId = "test.${clientType.name}",
                handler = AirPlayReadiness.READY,
                carrier = AirPlayReadiness.READY,
                consumer = AirPlayReadiness.READY,
            )
            val data = ready(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA)
            val data2 = ready(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2)
            val displayId = if (mainDdp) AirPlayInfoPlist.MAIN_UUID else AirPlayInfoPlist.ALT_UUID
            val schemaFingerprint = UiSyncSchemaFingerprint(ByteArray(32) { 2 })
            val bindingFingerprint = UiSyncBindingFingerprint(ByteArray(32) { 3 })
            val endpoint = CarPlayUiSyncEndpoint(
                descriptor = CarPlayUiSyncChannelDescriptor(displayId, "integration-vehicle"),
                mode = CarPlayUiSyncMode.PASSIVE,
                presentationEventSink = CarPlayUiSyncPresentationEventSink { },
            )
            val clusterFactory = RcsDataStreamHandlerFactory { clientType ->
                if (clientType != RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL) {
                    return@RcsDataStreamHandlerFactory null
                }
                RcsDataStreamHandlerDescriptor(
                    clientType = clientType,
                    implementationId = "test.cluster-control",
                    readinessProbe = { RcsDataStreamHandlerProbe.ready() },
                    handlerFactory = { object : RcsDataStreamHandler {} },
                )
            }
            val output = UltraDisplayGraphOutput(
                topologyDisplayId = UltraTopologyDisplayId(
                    if (mainDdp) java.util.UUID.fromString(displayId) else
                        java.util.UUID.nameUUIDFromBytes("integration-topology".toByteArray()),
                ),
                outputId = UltraDisplayOutputId.Custom("cluster-output"),
                uiSyncDisplayId = UltraUiSyncDisplayId(displayId),
            )
            val plan = UiSyncRendererStaticPlan(listOf(output), emptyList())
            val receiptContext = UiSyncCompleteRendererBindingContext(
                UiSyncRendererBindingContext(
                    staticPlan = plan,
                    schemaFingerprint = schemaFingerprint,
                    bindingFingerprint = bindingFingerprint,
                    displayIds = setOf(displayId),
                ),
            )
            val receipt = receiptContext.issueReadinessReceipt(
                semanticThemeProgramFingerprint = UiSyncSemanticThemeProgramFingerprint(
                    ByteArray(32) { 4 },
                ),
                subscribedDisplayIds = setOf(displayId),
                boundOutputIds = setOf(output.outputId),
                semanticCompilerActive = true,
                themeCompilerActive = true,
                backendLive = true,
            )
            val physicalRequirement = UiSyncPhysicalOutputRequirement(
                UltraProfileDigest(ByteArray(32) { 8 }),
                listOf(UiSyncPhysicalOutputAssignment(
                    output,
                    UiSyncPhysicalDestination(
                        if (mainDdp) UiSyncPhysicalDestinationRole.MAIN else
                            UiSyncPhysicalDestinationRole.CLUSTER,
                    ),
                )),
            )
            val physicalPreflight = UiSyncPhysicalOutputPreflightContext(
                physicalRequirement, plan, schemaFingerprint, bindingFingerprint,
            ).issueAvailability(
                listOf(UiSyncPhysicalOutputRoute(
                    physicalRequirement.assignments.single(),
                    UiSyncPhysicalTargetVersion("test-cluster-target", 1),
                )),
                currentProbe = currentProbe,
            )
            val provisionedUiSync = AirPlayUltraConfiguration.fromRuntimeProvisioning(
                configuredFeatures = setOf(AirPlayFeature.UI_SYNC),
                uiSyncInfo = AirPlayUiSyncInfo(
                    if (mainDdp) mapOf("renderer" to "test-main-ddp") else emptyMap(),
                ),
                rcsFactory = clusterFactory,
                uiSyncEndpoints = listOf(endpoint),
                uiSyncRendererReadiness = AirPlayUiSyncRendererReadinessDescriptor.ready(
                    "test.complete-renderer",
                    physicalPreflight,
                ),
                uiSyncSchemaFingerprint = schemaFingerprint,
                uiSyncBindingFingerprint = bindingFingerprint,
                uiSyncProvisionedDisplayIds = setOf(displayId),
                uiSyncRendererReceipt = receipt,
            )
            return baseConfig(
                provisionedUiSync.copy(
                    configuredFeatures = setOf(
                        AirPlayFeature.VEHICLE_STATE_PROTOCOL,
                        AirPlayFeature.UI_SYNC,
                    ),
                    vehicleStateProtocolInfo = AirPlayVehicleStateProtocolInfo(
                        pluginConfigs = listOf(AirPlayVehicleStateProtocolPlugin(1)),
                    ),
                    readiness = provisionedUiSync.readiness.copy(
                        rcsHandlers = listOf(data, data2) +
                            provisionedUiSync.readiness.rcsHandlers,
                    ),
                ),
                wirelessAudio = true,
            ).let { if (mainDdp) it.copy(cluster = null) else it }
        }

        fun mainDdpUiSyncConfig(): AirPlayConfig {
            val stream = AirPlayPhysicalVideoStream(AirPlayInfoPlist.MAIN_UUID, 0, 0, 0)
            val panels = AirPlayPhysicalDisplayPanels(listOf(
                AirPlayPhysicalDisplayPanel(
                    widthPixels = 800,
                    heightPixels = 480,
                    widthPhysical = 200.0,
                    heightPhysical = 120.0,
                    initialVideoStreams = listOf(stream),
                    displayPlugins = listOf(AirPlayPhysicalDisplayPlugin(
                        AirPlayInfoPlist.MAIN_UUID, listOf(stream),
                    )),
                    properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
                ),
            ))
            return ultraConfig(mainDdp = true).copy(
                displayPanels = panels,
                displayPanelsLiveness = AirPlayPhysicalDisplayPanelsLiveness(
                    panels, { true }, { Closeable {} },
                ),
                requireMainUiSyncForDisplayPanels = true,
            )
        }

        fun sessionManagementConfig(wireless: Boolean = true): AirPlayConfig = baseConfig(
            ultra = AirPlayUltraConfiguration.fromRuntimeDescriptors(
                configuredFeatures = setOf(AirPlayFeature.SESSION_MANAGEMENT),
                sessionManagementInfo = AirPlaySessionManagementInfo(
                    stopSession = 1,
                    stopSessionReasons = listOf(1, 2),
                ),
            ),
            wirelessAudio = wireless,
        )

        fun stopSessionBody(
            disconnectReason: Any?,
            extraParam: Boolean = false,
        ): ByteArray = BplistCodec.encode(
            linkedMapOf(
                "type" to "stopSession",
                "params" to linkedMapOf<String, Any?>(
                    "disconnectReason" to disconnectReason,
                ).apply {
                    if (extraParam) put("extra", true)
                },
            ),
        )

        fun dataStream(clientType: RcsClientType, connectionId: Long): Map<String, Any?> =
            linkedMapOf(
                "type" to 130,
                "streamConnectionID" to connectionId,
                "clientTypeUUID" to clientType.uuid.toString().uppercase(),
                "seed" to connectionId,
            )

        fun screenStream(type: Int, connectionId: Long): Map<String, Any?> = linkedMapOf(
            "type" to type,
            "streamConnectionID" to connectionId,
        )

        fun streamsBody(vararg streams: Map<String, Any?>): ByteArray =
            BplistCodec.encode(linkedMapOf("streams" to streams.toList()))

        fun requestBytes(
            cseq: Int,
            body: ByteArray,
            method: String = "SETUP",
            path: String = "rtsp://localhost/carplay",
        ): ByteArray = (
            "$method $path RTSP/1.0\r\n" +
                "CSeq: $cseq\r\n" +
                "Content-Type: application/x-apple-binary-plist\r\n" +
                "Content-Length: ${body.size}\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII) + body

        fun RtspMessage.Request.bodyMap(): Map<String, Any?> {
            val decoded = BplistCodec.decode(body) as Map<*, *>
            return decoded.entries.associate { it.key.toString() to it.value }
        }

        fun RtspMessage.Request.streams(): List<Any?> = bodyMap()["streams"] as? List<*>
            ?: emptyList<Any?>()

        fun RtspMessage.Request.status(): Int = path.toInt()

        fun eventClientCipher(): ControlCipher {
            val serverWrite = AirPlayCrypto.hkdfSha512(
                TEST_SHARED_SECRET,
                "Events-Salt".asciiBytes(),
                "Events-Write-Encryption-Key".asciiBytes(),
                32,
            )
            val serverRead = AirPlayCrypto.hkdfSha512(
                TEST_SHARED_SECRET,
                "Events-Salt".asciiBytes(),
                "Events-Read-Encryption-Key".asciiBytes(),
                32,
            )
            return ControlCipher(readKey = serverWrite, writeKey = serverRead)
        }

        fun readEncryptedRtsp(input: InputStream, cipher: ControlCipher): RtspMessage.Request {
            val header = readExactly(input, 2)
            val length = (header[0].toInt() and 0xff) or
                ((header[1].toInt() and 0xff) shl 8)
            val frame = header + readExactly(input, length + 16)
            return RtspMessage.parseMessages(cipher.decrypt(frame).data).messages.single()
        }

        fun readPlainRtsp(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            var match = 0
            val delimiter = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
            while (match < delimiter.size) {
                val value = input.read()
                check(value >= 0) { "RTSP response ended before headers" }
                output.write(value)
                match = if (value.toByte() == delimiter[match]) match + 1 else 0
            }
            val header = output.toByteArray()
            val text = header.toString(Charsets.US_ASCII)
            val contentLength = Regex("(?i)Content-Length: (\\d+)")
                .find(text)?.groupValues?.get(1)?.toInt() ?: 0
            output.write(readExactly(input, contentLength))
            return output.toByteArray()
        }

        fun readExactly(input: InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(bytes, offset, count - offset)
                check(read >= 0) { "stream ended after $offset/$count bytes" }
                offset += read
            }
            return bytes
        }

        fun setPrivate(target: Any, name: String, value: Any) {
            target.javaClass.getDeclaredField(name).apply {
                isAccessible = true
                set(target, value)
            }
        }

        fun invokePrivate(
            target: Any,
            name: String,
            parameterType: Class<*>,
            argument: Any,
        ): Any = target.javaClass.getDeclaredMethod(name, parameterType).let { method ->
            method.isAccessible = true
            checkNotNull(method.invoke(target, argument))
        }

        fun invokePrivateNoArgs(target: Any, name: String) {
            target.javaClass.getDeclaredMethod(name).apply {
                isAccessible = true
                invoke(target)
            }
        }

        fun invokePrivateTwoArgs(
            target: Any,
            name: String,
            first: Any,
            second: Any,
        ) {
            target.javaClass.declaredMethods.single {
                it.name == name && it.parameterCount == 2
            }.apply {
                isAccessible = true
                invoke(target, first, second)
            }
        }

        fun getPrivate(target: Any, name: String): Any? =
            target.javaClass.getDeclaredField(name).let { field ->
                field.isAccessible = true
                field.get(target)
            }

        fun await(timeoutMillis: Long = 3_000, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "condition was not met in ${timeoutMillis}ms" }
                Thread.sleep(10)
            }
        }

        fun completeSelectedTimingExchange(
            session: AirPlaySession,
            timingPeer: DatagramSocket,
            setupResponse: RtspMessage.Request,
        ) {
            val clock = getPrivate(session, "ntp") as NtpClock
            val accessoryPort = (setupResponse.bodyMap()["timingPort"] as Number).toInt()
            check(accessoryPort > 0)
            timingPeer.soTimeout = 2_000
            repeat(4) { index ->
                if (index != 0) {
                    NtpClock::class.java.getDeclaredMethod("sendRequest")
                        .apply { isAccessible = true }
                        .invoke(clock)
                }
                val request = DatagramPacket(ByteArray(64), 64)
                timingPeer.receive(request)
                check(request.length >= 32 && (request.data[1].toInt() and 0xff) == 210)
                val reply = ByteArray(32).also { packet ->
                    packet[0] = 0x80.toByte()
                    packet[1] = 211.toByte()
                    packet[3] = 7
                    request.data.copyInto(packet, 8, 24, 32)
                    request.data.copyInto(packet, 16, 24, 32)
                    request.data.copyInto(packet, 24, 24, 32)
                }
                timingPeer.send(DatagramPacket(
                    reply,
                    reply.size,
                    InetAddress.getLoopbackAddress(),
                    accessoryPort,
                ))
                if (index == 0) {
                    await { clock.latestVideoClockMeasurement()?.sampleGeneration == 1L }
                } else if (index == 1) {
                    await { clock.latestVideoClockMeasurement() == null }
                } else if (index == 2) {
                    await { clock.latestVideoClockMeasurement()?.sampleGeneration == 3L }
                } else {
                    await { clock.videoClockLease() != null }
                }
            }
        }

        fun matchingScreenTiming(session: AirPlaySession): ScreenFrameTiming {
            val rawTimestamp = session.syncedNtp()
                .subtract(UNIX_TO_NTP_TICKS)
                .and(UINT64_MASK)
                .toLong()
            return ScreenFrameTiming(rawTimestamp, System.nanoTime())
        }

        val TEST_SHARED_SECRET = ByteArray(32) { 0x73 }
        val UNIX_TO_NTP_TICKS: BigInteger =
            BigInteger.valueOf(2_208_988_800L).shiftLeft(32)
        val UINT64_MASK: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    }
}
