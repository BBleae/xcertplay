package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTreeDecoder
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistration
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistry
import com.shilapi.xcertplay.airplay.rcs.caf.CafProtocolException
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.caf.CafSchemaBoundPluginHandler
import com.shilapi.xcertplay.airplay.rcs.caf.SyntheticCafTestFixture
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncChannelDescriptor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpoint
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpointRegistry
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPhoneRoleSimulator
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEventSink
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRouterActions
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRoutingDiagnostic
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRoutingDiagnosticKind
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCodec
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessageType
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestOutbox
import com.shilapi.xcertplay.airplay.rcs.transport.RcsFrameCodec
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayUltraRuntimeTest {
    @Test
    fun atomicCafProvisioningDerivesTheOnlyAllowedVehicleSidecar() {
        val provisioning = cafProvisioningFor(0L)
        val runtime = AirPlayUltraRuntime(cafProvisioning = provisioning)

        val ultra = runtime.configuration(
            configuredFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
        )

        assertEquals(
            listOf(0L),
            ultra.vehicleStateProtocolInfo?.pluginConfigs?.map { it.pluginId },
        )
        assertTrue(
            AirPlayFeatureNegotiation.snapshot(config(ultra))
                .isReady(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
        )
        assertThrows(IllegalArgumentException::class.java) {
            runtime.configuration(
                configuredFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
                vehicleStateProtocolInfo = AirPlayVehicleStateProtocolInfo(
                    pluginConfigs = listOf(AirPlayVehicleStateProtocolPlugin(999)),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayUltraRuntime(
                cafFactory = provisioning.handlerFactory,
                cafProvisioning = provisioning,
            )
        }
    }

    @Test
    fun liveCafAdvertisementAcceptsExactlyIndexedPluginIds() {
        val runtime = AirPlayUltraRuntime(cafProvisioning = cafProvisioningFor(1L, 0L))
        try {
            val ultra = runtime.configuration(
                configuredFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
            )

            assertEquals(
                listOf(1L, 0L),
                ultra.vehicleStateProtocolInfo?.pluginConfigs?.map { it.pluginId },
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun liveCafAdvertisementRejectsPluginSevenWithCountOne() {
        val runtime = AirPlayUltraRuntime(cafProvisioning = cafProvisioningFor(7L))
        try {
            val failure = assertThrows(CafProtocolException::class.java) {
                runtime.configuration(
                    configuredFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
                )
            }
            assertTrue(failure.message.orEmpty().contains("pluginID 0"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun callerSuppliedVehicleSidecarAlsoRequiresIndexedPluginIds() {
        val runtime = AirPlayUltraRuntime()
        try {
            assertThrows(CafProtocolException::class.java) {
                runtime.configuration(
                    configuredFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
                    vehicleStateProtocolInfo = AirPlayVehicleStateProtocolInfo(
                        pluginConfigs = listOf(AirPlayVehicleStateProtocolPlugin(7L)),
                    ),
                )
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun runtimeConfigurationCapturesBuiltInSessionManagementConsumer() {
        val runtime = AirPlayUltraRuntime()
        val sidecar = AirPlaySessionManagementInfo(
            stopSession = 1,
            stopSessionReasons = listOf(1, 2),
        )
        val ultra = runtime.configuration(
            configuredFeatures = setOf(AirPlayFeature.SESSION_MANAGEMENT),
            sessionManagementInfo = sidecar,
        )

        assertTrue(requireNotNull(ultra.readiness.sessionManagement).isComplete)
        assertEquals(sidecar.toWireMap(), ultra.sessionManagementInfo?.toWireMap())
        assertTrue(
            AirPlayFeatureNegotiation.snapshot(config(ultra))
                .isReady(AirPlayFeature.SESSION_MANAGEMENT),
        )
    }

    @Test
    fun sessionControlExposesCommandsAndBoundedDiagnosticsOnlyWhileAttached() {
        val control = AirPlayUiSyncSessionControl()
        assertFalse(
            control.sendCommand(
                "instrument-cluster",
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
            ),
        )
        var sent = 0
        val attachment = control.attach { _, _, _, _ ->
            sent += 1
            CarPlayUiSyncRouterActions(outboundCarriers = listOf(byteArrayOf(1)))
        }

        assertTrue(
            control.sendCommand(
                "instrument-cluster",
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
            ),
        )
        assertEquals(1, sent)
        control.record(
            List(70) {
                CarPlayUiSyncRoutingDiagnostic(
                    CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR,
                )
            },
        )
        assertEquals(64, control.drainDiagnostics().size)

        attachment.close()
        assertFalse(
            control.sendCommand(
                "instrument-cluster",
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
            ),
        )
    }

    @Test
    fun sessionControlContainsTransportFailureAndCloseIsTerminal() {
        val control = AirPlayUiSyncSessionControl()
        control.attach { _, _, _, _ -> throw IOException("transport closed") }

        assertFalse(
            control.sendCommand(
                "instrument-cluster",
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
            ),
        )
        assertEquals(
            listOf(CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR),
            control.drainDiagnostics().map(CarPlayUiSyncRoutingDiagnostic::kind),
        )

        control.close()
        control.close()
        var lateSenderCalled = false
        val lateAttachment = control.attach { _, _, _, _ ->
            lateSenderCalled = true
            CarPlayUiSyncRouterActions(outboundCarriers = listOf(byteArrayOf(1)))
        }
        control.record(
            listOf(
                CarPlayUiSyncRoutingDiagnostic(
                    CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR,
                ),
            ),
        )

        assertFalse(
            control.sendCommand(
                "instrument-cluster",
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
            ),
        )
        assertFalse(lateSenderCalled)
        assertTrue(control.drainDiagnostics().isEmpty())
        lateAttachment.close()
    }

    @Test
    fun serializedOutboundKeepsAllocatedPacketSequenceOnWireOrder() {
        val displayId = "instrument-cluster"
        val emitted = CopyOnWriteArrayList<ByteArray>()
        val blockNext = AtomicBoolean(false)
        val firstWriteEntered = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val dispatcher = SerializedUiSyncOutbound(
            CarPlayUiSyncEndpointRegistry(listOf(endpoint(displayId))),
        ) { carrier ->
            if (blockNext.compareAndSet(true, false)) {
                firstWriteEntered.countDown()
                releaseFirstWrite.await(5, TimeUnit.SECONDS)
            }
            emitted += carrier
        }
        val remote = CarPlayUiSyncPhoneRoleSimulator(displayId)
        assertTrue(dispatcher.open().outboundCarriers.isEmpty())
        remote.handshake { dispatcher.receive(it).outboundCarriers }
        emitted.clear()

        val remoteCommand = remote.sendCommand(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
        )
        val failures = CopyOnWriteArrayList<Throwable>()
        val secondStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        blockNext.set(true)
        val first = Thread {
            runCatching {
                dispatcher.sendCommand(displayId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE)
            }.onFailure(failures::add)
        }
        first.start()
        assertTrue(firstWriteEntered.await(5, TimeUnit.SECONDS))

        val second = Thread {
            secondStarted.countDown()
            runCatching { dispatcher.receive(remoteCommand) }
                .onFailure(failures::add)
            secondFinished.countDown()
        }
        second.start()
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
        assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))
        releaseFirstWrite.countDown()
        first.join(5_000)
        second.join(5_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertTrue(failures.isEmpty())
        assertEquals(2, emitted.size)
        val messages = emitted.map { CarPlayUiSyncCodec.decode(it).message }
        assertEquals(CarPlayUiSyncMessageType.COMMAND, messages[0].type)
        assertEquals(CarPlayUiSyncMessageType.COMMAND_ACK, messages[1].type)
        assertEquals(
            messages[0].packetSequenceNumber + 1,
            messages[1].packetSequenceNumber,
        )

        dispatcher.close()
    }

    @Test
    fun readyPhoneResetInvalidatesOnlyItsDisplayBeforeNewReadyCommands() {
        val first = "main-ui"
        val second = "cluster-ui"
        val invalidations = CopyOnWriteArrayList<String>()
        val dispatcher = SerializedUiSyncOutbound(
            registry = CarPlayUiSyncEndpointRegistry(listOf(endpoint(first), endpoint(second))),
            onDisplayEpochInvalidated = invalidations::add,
            sendCarrier = { },
        )
        val mainPhone = CarPlayUiSyncPhoneRoleSimulator(first)
        val clusterPhone = CarPlayUiSyncPhoneRoleSimulator(second)
        assertTrue(dispatcher.open().outboundCarriers.isEmpty())
        mainPhone.handshake { dispatcher.receive(it).outboundCarriers }
        clusterPhone.handshake { dispatcher.receive(it).outboundCarriers }
        assertTrue(invalidations.isEmpty()) // First Reset is initial negotiation, not loss.

        val accessoryReset = dispatcher.receive(mainPhone.open()).outboundCarriers.single()
        assertEquals(listOf(first), invalidations)
        assertEquals(setOf(second), dispatcher.readyUiSyncDisplayIds())
        val phoneComplete = mainPhone.receive(accessoryReset).single()
        val accessoryComplete = dispatcher.receive(phoneComplete).outboundCarriers.single()
        assertTrue(mainPhone.receive(accessoryComplete).isEmpty())
        assertEquals(setOf(first, second), dispatcher.readyUiSyncDisplayIds())
        assertEquals(listOf(first), invalidations) // READY never resurrects old presentation.

        val clusterCommand = clusterPhone.sendCommand(
            CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 1L),
        )
        assertTrue(dispatcher.receive(clusterCommand).diagnostics.isEmpty())
        assertEquals(listOf(first), invalidations)
        dispatcher.close()
    }

    @Test
    fun blockedResetReplyCannotDelayPhysicalEpochInvalidation() {
        val displayId = "main-ui"
        val revoked = AtomicBoolean(false)
        val blockNextReset = AtomicBoolean(false)
        val resetWriteEntered = CountDownLatch(1)
        val releaseResetWrite = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        val dispatcher = SerializedUiSyncOutbound(
            registry = CarPlayUiSyncEndpointRegistry(listOf(endpoint(displayId))),
            onDisplayEpochInvalidated = { revoked.set(true) },
            sendCarrier = { bytes ->
                if (CarPlayUiSyncCodec.decode(bytes).message.type ==
                    CarPlayUiSyncMessageType.RESET &&
                    blockNextReset.compareAndSet(true, false)
                ) {
                    check(revoked.get()) { "Reset response began before old MAIN permit revoked" }
                    resetWriteEntered.countDown()
                    check(releaseResetWrite.await(3L, TimeUnit.SECONDS))
                }
            },
        )
        val phone = CarPlayUiSyncPhoneRoleSimulator(displayId)
        assertTrue(dispatcher.open().outboundCarriers.isEmpty())
        phone.handshake { dispatcher.receive(it).outboundCarriers }
        blockNextReset.set(true)
        val receiving = Thread {
            runCatching { dispatcher.receive(phone.open()) }.onFailure(failures::add)
        }
        receiving.start()
        try {
            assertTrue(resetWriteEntered.await(2L, TimeUnit.SECONDS))
            assertTrue(revoked.get())
        } finally {
            releaseResetWrite.countDown()
        }
        receiving.join(5_000L)
        assertFalse(receiving.isAlive)
        assertTrue(failures.isEmpty())
        dispatcher.close()
    }

    @Test
    fun mainResetInvalidationDoesNotPoisonIndependentClusterCommand() {
        val mainId = "main-ui"
        val clusterId = "cluster-ui"
        val invalidationEntered = CountDownLatch(1)
        val releaseInvalidation = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        val dispatcher = SerializedUiSyncOutbound(
            registry = CarPlayUiSyncEndpointRegistry(
                listOf(endpoint(mainId), endpoint(clusterId))),
            onDisplayEpochInvalidated = { id ->
                check(id == mainId)
                invalidationEntered.countDown()
                check(releaseInvalidation.await(3L, TimeUnit.SECONDS))
            },
            sendCarrier = { },
        )
        val mainPhone = CarPlayUiSyncPhoneRoleSimulator(mainId)
        val clusterPhone = CarPlayUiSyncPhoneRoleSimulator(clusterId)
        assertTrue(dispatcher.open().outboundCarriers.isEmpty())
        mainPhone.handshake { dispatcher.receive(it).outboundCarriers }
        clusterPhone.handshake { dispatcher.receive(it).outboundCarriers }

        val resetting = Thread {
            runCatching { dispatcher.receive(mainPhone.open()) }.onFailure(failures::add)
        }
        resetting.start()
        try {
            assertTrue(invalidationEntered.await(2L, TimeUnit.SECONDS))
            assertEquals(setOf(clusterId), dispatcher.readyUiSyncDisplayIds())
            val clusterRaw5 = dispatcher.sendCommand(
                clusterId, CarPlayUiSyncCommandType.REQUEST_LAYOUT,
                mapOf("layoutID" to "issued-cluster"),
            )
            assertTrue(clusterRaw5.diagnostics.isEmpty())
            assertEquals(1, clusterRaw5.outboundCarriers.size)
            assertEquals(1, clusterPhone.receive(clusterRaw5.outboundCarriers.single()).size)
        } finally {
            releaseInvalidation.countDown()
        }
        resetting.join(5_000L)
        assertFalse(resetting.isAlive)
        assertTrue(failures.isEmpty())
        dispatcher.close()
    }

    @Test
    fun closingDuringResetInvalidationCannotWriteDeferredResetReply() {
        val displayId = "main-ui"
        val invalidationEntered = CountDownLatch(1)
        val releaseInvalidation = CountDownLatch(1)
        val sentResets = java.util.concurrent.atomic.AtomicInteger()
        val failures = CopyOnWriteArrayList<Throwable>()
        val dispatcher = SerializedUiSyncOutbound(
            registry = CarPlayUiSyncEndpointRegistry(listOf(endpoint(displayId))),
            onDisplayEpochInvalidated = {
                invalidationEntered.countDown()
                check(releaseInvalidation.await(3L, TimeUnit.SECONDS))
            },
            sendCarrier = { bytes ->
                if (CarPlayUiSyncCodec.decode(bytes).message.type ==
                    CarPlayUiSyncMessageType.RESET) sentResets.incrementAndGet()
            },
        )
        val phone = CarPlayUiSyncPhoneRoleSimulator(displayId)
        assertTrue(dispatcher.open().outboundCarriers.isEmpty())
        phone.handshake { dispatcher.receive(it).outboundCarriers }
        assertEquals(1, sentResets.get())

        val resetting = Thread {
            runCatching { dispatcher.receive(phone.open()) }.onFailure(failures::add)
        }
        resetting.start()
        try {
            assertTrue(invalidationEntered.await(2L, TimeUnit.SECONDS))
            dispatcher.close() // Its lock is not held by the renderer callback.
            assertTrue(dispatcher.open().outboundCarriers.isEmpty())
        } finally {
            releaseInvalidation.countDown()
        }
        resetting.join(5_000L)
        assertFalse(resetting.isAlive)
        assertEquals(1, sentResets.get())
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IllegalStateException)
        dispatcher.close()
    }

    @Test
    fun authenticatedOpenAttachmentFailureStillRunsTerminalCoverButPreauthCloseDoesNot() {
        val displayId = "main-ui"
        val registry = CarPlayUiSyncEndpointRegistry(listOf(endpoint(displayId)))
        val terminalStops = java.util.concurrent.atomic.AtomicInteger()
        val invalidations = CopyOnWriteArrayList<String>()
        val stream = RcsDataStream.open(
            clientType = RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
            readKey = ByteArray(32) { 1 }, writeKey = ByteArray(32) { 2 },
            bindAddress = LOOPBACK,
        )
        val failedOutbox = UiSyncForegroundRequestOutbox(listOf(displayId)).also { it.close() }
        try {
            fun handler(outbox: UiSyncForegroundRequestOutbox?): UiSyncRcsHandler =
                UiSyncRcsHandler(
                    ownStream = stream, registry = registry, control = null,
                    isLive = { true }, acquireUse = { java.io.Closeable { } },
                    foregroundRequestOutbox = outbox, canSendForeground = { true },
                    debugStatus = { _, _ -> }, debugCarrierShape = { },
                    onDisplayEpochInvalidated = { id -> invalidations += id; true },
                    onTerminalChannelClose = { terminalStops.incrementAndGet(); true },
                )
            val preauth = handler(null)
            preauth.onStreamClosed(stream, null)
            assertEquals(0, terminalStops.get())
            assertTrue(invalidations.isEmpty())

            val authenticated = handler(failedOutbox)
            assertThrows(IllegalStateException::class.java) {
                // This callback is only issued after RCS peer authentication in production.
                authenticated.onStreamOpened(stream)
            }
            authenticated.onStreamClosed(stream, null)
            assertEquals(listOf(displayId), invalidations)
            assertEquals(1, terminalStops.get())
        } finally {
            stream.close()
        }
    }

    @Test
    fun terminalBatchPermitRevocationPrecedesBlockedDisplayObserver() {
        val displayId = "external-cluster"
        val terminalRevoked = AtomicBoolean(false)
        val observerEntered = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        val stream = RcsDataStream.open(
            clientType = RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
            readKey = ByteArray(32) { 1 }, writeKey = ByteArray(32) { 2 },
            bindAddress = LOOPBACK,
        )
        try {
            val handler = UiSyncRcsHandler(
                ownStream = stream,
                registry = CarPlayUiSyncEndpointRegistry(listOf(endpoint(displayId))),
                control = null, isLive = { true },
                acquireUse = { java.io.Closeable { } },
                foregroundRequestOutbox = null, canSendForeground = { true },
                debugStatus = { _, _ -> }, debugCarrierShape = { },
                onDisplayEpochInvalidated = {
                    observerEntered.countDown()
                    check(releaseObserver.await(3L, TimeUnit.SECONDS))
                    true
                },
                onTerminalChannelClose = { terminalRevoked.set(true); true },
            )
            handler.onStreamOpened(stream) // Authenticated callback entry in production.
            val closing = Thread {
                runCatching { handler.onStreamClosed(stream, null) }.onFailure(failures::add)
            }
            closing.start()
            try {
                assertTrue(observerEntered.await(2L, TimeUnit.SECONDS))
                assertTrue(terminalRevoked.get())
            } finally {
                releaseObserver.countDown()
            }
            closing.join(5_000L)
            assertFalse(closing.isAlive)
            assertTrue(failures.isEmpty())
        } finally {
            releaseObserver.countDown()
            stream.close()
        }
    }

    @Test
    fun configurationAndAllSessionsShareOneImmutableDescriptorSnapshot() {
        val ready = AtomicBoolean(true)
        val descriptor = RcsDataStreamHandlerDescriptor(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            implementationId = "test.mutable-probe",
            readinessProbe = {
                if (ready.get()) RcsDataStreamHandlerProbe.ready()
                else RcsDataStreamHandlerProbe.notReady("disabled")
            },
            handlerFactory = { object : com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler {} },
        )
        val runtime = AirPlayUltraRuntime(
            cafFactory = RcsDataStreamHandlerFactory { clientType ->
                descriptor.takeIf { clientType == descriptor.clientType }
            },
        )

        val firstSession = runtime.openSession()
        assertTrue(firstSession.supports(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
        assertEquals(
            "test.mutable-probe",
            runtime.configuration(
                configuredFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
            ).readiness.handler(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA)?.implementationId,
        )

        ready.set(false)
        assertTrue(firstSession.supports(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
        assertTrue(
            requireNotNull(
                firstSession.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
            ).probe().isReady,
        )
        assertTrue(runtime.openSession().supports(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA))
        assertEquals(
            AirPlayReadiness.READY,
            runtime.configuration(
                configuredFeatures = setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
            ).readiness.handler(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA)?.handler,
        )
    }

    @Test
    fun closedSessionRuntimeCannotExposeOrCreateHandlers() {
        val runtime = AirPlayUltraRuntime(uiSyncEndpoint = endpoint("instrument-cluster"))
        val session = runtime.openSession()
        assertTrue(session.supports(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL))

        session.close()

        assertFalse(session.supports(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL))
        assertEquals(
            null,
            session.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL),
        )
        assertFalse(
            session.sendUiSyncCommand(
                "instrument-cluster",
                CarPlayUiSyncCommandType.REQUEST_LAYOUT,
            ),
        )
    }

    @Test
    fun nestedUpdateDataHandlerPreservesSentTerminalAckAcrossCleanPeerEof() {
        assertNestedRcsClose(
            clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            sendTerminalAck = true,
            expectTransportFailure = false,
        )
    }

    @Test
    fun nestedUpdateDataHandlerWithFalseEvidenceStillFailsOnPeerEof() {
        assertNestedRcsClose(
            clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            sendTerminalAck = false,
            expectTransportFailure = true,
        )
    }

    @Test
    fun sessionOwnershipDoesNotInventTerminalAckEvidenceForOtherHandlers() {
        val cases = listOf(
            RcsClientTypes.CAR_PLAY_UPDATE_DATA to (object : RcsDataStreamHandler {}),
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA to NestedTerminalAckHandler(
                sendTerminalAck = true,
                failOnStreamClosed = false,
            ),
        )
        cases.forEach { (clientType, delegate) ->
            val descriptor = RcsDataStreamHandlerDescriptor.ready(
                clientType,
                "no-invented-ack-evidence",
            ) { delegate }
            val runtime = AirPlayUltraRuntime(
                cafFactory = RcsDataStreamHandlerFactory { requested ->
                    descriptor.takeIf { requested == clientType }
                },
            )
            val session = runtime.openSession()
            val stream = RcsDataStream.open(
                clientType = clientType,
                readKey = ByteArray(32) { 1 },
                writeKey = ByteArray(32) { 2 },
                bindAddress = LOOPBACK,
            )
            try {
                val owned = requireNotNull(session.handlerFactory.descriptor(clientType))
                    .create(stream)
                assertFalse(owned is CarPlayUpdateDataTerminalAckEvidence)
                owned.close()
            } finally {
                stream.close()
                session.close()
                runtime.close()
            }
        }
    }

    @Test
    fun nestedOtherClientCannotInheritUpdateDataTerminalAckExemption() {
        assertNestedRcsClose(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            sendTerminalAck = true,
            expectTransportFailure = true,
        )
    }

    @Test
    fun nestedUpdateDataCloseCallbackFailureStillFailsAfterSentAck() {
        assertNestedRcsClose(
            clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            sendTerminalAck = true,
            failOnStreamClosed = true,
            expectTransportFailure = true,
        )
    }

    @Test
    fun nestedUpdateDataAbnormalTransportCloseStillFailsAfterSentAck() {
        val fixture = openNestedRcs(
            RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            sendTerminalAck = true,
        )
        val codec = RcsFrameCodec.duplex(
            fixture.context.deriveKey(fixture.seed, DATASTREAM_INPUT_KEY),
            fixture.context.deriveKey(fixture.seed, DATASTREAM_OUTPUT_KEY),
        )
        val socket = Socket(LOOPBACK, fixture.opened.port)
        try {
            socket.getOutputStream().apply {
                write(codec.encrypt(RcsMessage.comm(byteArrayOf(1)).encoded()))
                flush()
            }
            assertTrue(fixture.delegate.messageReceived.await(2, TimeUnit.SECONDS))
            assertTrue(fixture.delegate.terminalAckSent)
            val tampered = codec.encrypt(RcsMessage.comm(byteArrayOf(2)).encoded())
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
            socket.getOutputStream().apply {
                write(tampered)
                flush()
            }
            assertTrue(fixture.failureSignal.await(2, TimeUnit.SECONDS))
            assertTrue(fixture.delegate.handlerClosed.await(2, TimeUnit.SECONDS))
            assertTrue(fixture.failures.single() is
                com.shilapi.xcertplay.airplay.rcs.RcsProtocolException)
        } finally {
            socket.close()
            fixture.close()
        }
    }

    @Test
    fun closingSharedRuntimeClosesEveryPublishedSessionRuntime() {
        val runtime = AirPlayUltraRuntime(uiSyncEndpoint = endpoint("instrument-cluster"))
        val first = runtime.openSession()
        val second = runtime.openSession()
        val stale = requireNotNull(
            first.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL),
        )

        runtime.close()

        assertFalse(first.supports(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL))
        assertFalse(second.supports(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL))
        assertFalse(stale.probe().isReady)
        assertThrows(IllegalStateException::class.java) { runtime.openSession() }
    }

    @Test
    fun slowPresentationConsumerRunsAfterAckAndDoesNotHoldOutboundSequencer() {
        val displayId = "instrument-cluster"
        val sinkEntered = CountDownLatch(1)
        val releaseSink = CountDownLatch(1)
        val emitted = CopyOnWriteArrayList<ByteArray>()
        val dispatcher = SerializedUiSyncOutbound(
            CarPlayUiSyncEndpointRegistry(
                listOf(
                    endpoint(
                        displayId,
                        CarPlayUiSyncPresentationEventSink {
                            sinkEntered.countDown()
                            releaseSink.await(5, TimeUnit.SECONDS)
                        },
                    ),
                ),
            ),
            emitted::add,
        )
        val remote = CarPlayUiSyncPhoneRoleSimulator(displayId)
        assertTrue(dispatcher.open().outboundCarriers.isEmpty())
        remote.handshake { dispatcher.receive(it).outboundCarriers }
        emitted.clear()

        val inboundCommand = remote.sendCommand(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
        )
        val receiveFinished = CountDownLatch(1)
        val commandFinished = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        val receiver = Thread {
            runCatching { dispatcher.receive(inboundCommand) }.onFailure(failures::add)
            receiveFinished.countDown()
        }
        receiver.start()
        assertTrue(sinkEntered.await(5, TimeUnit.SECONDS))

        val commandSender = Thread {
            runCatching {
                dispatcher.sendCommand(displayId, CarPlayUiSyncCommandType.END_LAYOUT_CHANGE)
            }.onFailure(failures::add)
            commandFinished.countDown()
        }
        commandSender.start()
        try {
            assertTrue(commandFinished.await(2, TimeUnit.SECONDS))
            assertFalse(receiveFinished.await(100, TimeUnit.MILLISECONDS))
            val messageTypes = emitted.map { CarPlayUiSyncCodec.decode(it).message.type }
            assertEquals(
                listOf(CarPlayUiSyncMessageType.COMMAND_ACK, CarPlayUiSyncMessageType.COMMAND),
                messageTypes,
            )
        } finally {
            releaseSink.countDown()
        }
        receiver.join(5_000)
        commandSender.join(5_000)
        assertFalse(receiver.isAlive)
        assertFalse(commandSender.isAlive)
        assertTrue(failures.isEmpty())

        dispatcher.close()
    }

    @Test
    fun emptyUiSyncRegistryHasNoClusterControlDescriptorAndCannotAdvertise() {
        val runtime = AirPlayUltraRuntime(uiSyncEndpoints = emptyList())

        assertFalse(runtime.openSession().supports(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL))
        val ultra = runtime.configuration(
            configuredFeatures = setOf(AirPlayFeature.UI_SYNC),
            uiSyncInfo = AirPlayUiSyncInfo(),
        )
        assertEquals(null, ultra.readiness.uiSync)
        assertFalse(AirPlayFeatureNegotiation.snapshot(config(ultra)).isReady(AirPlayFeature.UI_SYNC))
    }

    @Test
    fun singleEndpointCompatibilityCapturesExactAdvertisedDisplay() {
        val endpoint = endpoint(AirPlayInfoPlist.ALT_UUID)
        val runtime = AirPlayUltraRuntime(uiSyncEndpoint = endpoint)

        assertSame(endpoint, runtime.uiSyncEndpoint)
        assertEquals(listOf(endpoint), runtime.uiSyncEndpoints)
        assertTrue(runtime.openSession().supports(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL))
        val ultra = runtime.configuration(
            configuredFeatures = setOf(AirPlayFeature.UI_SYNC),
            uiSyncInfo = AirPlayUiSyncInfo(),
        )
        assertEquals(setOf(AirPlayInfoPlist.ALT_UUID), ultra.readiness.uiSync?.displayIds)
        assertEquals(
            AirPlayReadiness.DIAGNOSTIC_ONLY,
            ultra.readiness.uiSync?.renderer?.outputBackend,
        )
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(config(ultra)).isReady(AirPlayFeature.UI_SYNC),
        )
    }

    @Test
    fun endpointRegistryIsSnapshottedRejectsDuplicatesAndDoesNotAdvertiseExtraDisplays() {
        val endpoints = mutableListOf(
            endpoint(AirPlayInfoPlist.ALT_UUID),
            endpoint("passenger-display"),
        )
        val runtime = AirPlayUltraRuntime(uiSyncEndpoints = endpoints)
        endpoints.clear()

        assertEquals(2, runtime.uiSyncEndpoints.size)
        assertEquals(null, runtime.uiSyncEndpoint)
        val ultra = runtime.configuration(
            configuredFeatures = setOf(AirPlayFeature.UI_SYNC),
            uiSyncInfo = AirPlayUiSyncInfo(),
        )
        assertEquals(
            setOf(AirPlayInfoPlist.ALT_UUID, "passenger-display"),
            ultra.readiness.uiSync?.displayIds,
        )
        assertFalse(AirPlayFeatureNegotiation.snapshot(config(ultra)).isReady(AirPlayFeature.UI_SYNC))

        assertThrows(IllegalArgumentException::class.java) {
            AirPlayUltraRuntime(
                uiSyncEndpoints = listOf(endpoint("duplicate"), endpoint("duplicate")),
            )
        }
    }

    @Test
    fun legacyEndpointSnapshotDoesNotTrustCollectionSizeAndCapsTheCombinedInput() {
        val expected = endpoint("hostile-size-display")
        val hostileSize = object : AbstractCollection<CarPlayUiSyncEndpoint>() {
            override val size: Int
                get() = throw AssertionError("external Collection.size must not be read")

            override fun iterator(): Iterator<CarPlayUiSyncEndpoint> =
                listOf(expected).iterator()
        }
        val runtime = AirPlayUltraRuntime(uiSyncEndpoints = hostileSize)

        assertEquals(listOf(expected), runtime.uiSyncEndpoints)
        runtime.close()

        assertThrows(IllegalArgumentException::class.java) {
            AirPlayUltraRuntime(
                uiSyncEndpoint = endpoint("primary"),
                uiSyncEndpoints = List(64) { index -> endpoint("extra-$index") },
            )
        }
    }

    private fun assertNestedRcsClose(
        clientType: RcsClientType,
        sendTerminalAck: Boolean,
        expectTransportFailure: Boolean,
        failOnStreamClosed: Boolean = false,
    ) {
        val fixture = openNestedRcs(
            clientType,
            sendTerminalAck,
            failOnStreamClosed,
        )
        val client = RcsChannel.connect(
            remote = InetSocketAddress(LOOPBACK, fixture.opened.port),
            readKey = fixture.context.deriveKey(fixture.seed, DATASTREAM_INPUT_KEY),
            writeKey = fixture.context.deriveKey(fixture.seed, DATASTREAM_OUTPUT_KEY),
        )
        try {
            client.sendComm(byteArrayOf(1))
            assertTrue(fixture.delegate.messageReceived.await(2, TimeUnit.SECONDS))
            if (sendTerminalAck) {
                assertEquals(5, client.receiveNext(2_000)?.body?.single()?.toInt())
                assertTrue(fixture.delegate.terminalAckSent)
            } else {
                assertFalse(fixture.delegate.terminalAckSent)
            }
            client.close()
            assertTrue(fixture.delegate.handlerClosed.await(2, TimeUnit.SECONDS))
            if (expectTransportFailure) {
                assertTrue(fixture.failureSignal.await(2, TimeUnit.SECONDS))
                assertTrue(fixture.failures.single() is java.io.EOFException)
            } else {
                assertFalse(fixture.failureSignal.await(200, TimeUnit.MILLISECONDS))
                assertTrue(fixture.failures.isEmpty())
            }
        } finally {
            client.close()
            fixture.close()
        }
    }

    private fun openNestedRcs(
        clientType: RcsClientType,
        sendTerminalAck: Boolean,
        failOnStreamClosed: Boolean = false,
    ): NestedRcsFixture {
        val delegate = NestedTerminalAckHandler(sendTerminalAck, failOnStreamClosed)
        val descriptor = RcsDataStreamHandlerDescriptor.ready(
            clientType = clientType,
            implementationId = "nested-terminal-ack-test",
            handlerFactory = { delegate },
        )
        val runtime = AirPlayUltraRuntime(
            cafFactory = RcsDataStreamHandlerFactory { requested ->
                descriptor.takeIf { requested == clientType }
            },
        )
        val session = runtime.openSession()
        val seed = "nested-terminal-ack"
        val failures = CopyOnWriteArrayList<Throwable?>()
        val failureSignal = CountDownLatch(1)
        val context = CarPlayDataStreamConnectionContext(
            sharedSecret = ByteArray(32) { 37 },
            bindAddress = LOOPBACK,
            expectedPeerAddress = LOOPBACK,
            onTransportFailure = { failure ->
                failures += failure
                failureSignal.countDown()
            },
        )
        val opened = requireNotNull(
            CarPlayDataStreamFactory().open(
                context = context,
                clientType = clientType,
                seed = seed,
                handlers = CarPlayDataStreamHandlers(
                    iap = null,
                    rcs = session.handlerFactory,
                    onIapFallback = {},
                ),
            ),
        )
        assertTrue(opened.commit())
        return NestedRcsFixture(
            context,
            seed,
            delegate,
            failures,
            failureSignal,
            opened,
            session,
            runtime,
        )
    }

    private class NestedTerminalAckHandler(
        private val sendTerminalAck: Boolean,
        private val failOnStreamClosed: Boolean,
    ) : RcsDataStreamHandler, CarPlayUpdateDataTerminalAckEvidence {
        val messageReceived = CountDownLatch(1)
        val handlerClosed = CountDownLatch(1)

        @Volatile
        override var terminalAckSent = false
            private set

        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            if (sendTerminalAck) {
                stream.send(RcsMessage.comm(byteArrayOf(5)))
                terminalAckSent = true
            }
            messageReceived.countDown()
        }

        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            if (failOnStreamClosed) throw IOException("delegate close callback failed")
        }

        override fun close() {
            handlerClosed.countDown()
        }
    }

    private class NestedRcsFixture(
        val context: CarPlayDataStreamConnectionContext,
        val seed: String,
        val delegate: NestedTerminalAckHandler,
        val failures: CopyOnWriteArrayList<Throwable?>,
        val failureSignal: CountDownLatch,
        val opened: OpenedCarPlayDataStream,
        val session: AirPlayUltraSessionRuntime,
        val runtime: AirPlayUltraRuntime,
    ) {
        fun close() {
            // The delegate's close latch fires before the two ownership wrappers finish their
            // retirement bookkeeping. Rollback is retryable by contract; wait only for these
            // exact concurrent-retirement states, never for an arbitrary cleanup failure.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                try {
                    opened.rollback()
                    break
                } catch (failure: IllegalStateException) {
                    if (failure.message !in RETRYABLE_RETIREMENT_MESSAGES ||
                        System.nanoTime() >= deadline
                    ) {
                        throw failure
                    }
                    Thread.sleep(1)
                }
            }
            session.close()
            runtime.close()
        }
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        val RETRYABLE_RETIREMENT_MESSAGES = setOf(
            "RCS handler close is already in flight",
            "RCS resource close is already in flight",
            "Session-owned RCS handler close is already in flight",
            "Session-owned RCS handler still has callbacks in flight",
        )
    }

    private fun endpoint(
        displayId: String,
        sink: CarPlayUiSyncPresentationEventSink = CarPlayUiSyncPresentationEventSink { },
    ) = CarPlayUiSyncEndpoint(
        descriptor = CarPlayUiSyncChannelDescriptor(
            displayId = displayId,
            vehicleId = "test-vehicle",
        ),
        mode = CarPlayUiSyncMode.PASSIVE,
        presentationEventSink = sink,
    )

    private fun cafProvisioningFor(vararg pluginIds: Long): CafRuntimeProvisioning {
        val tree = SyntheticCafTestFixture.tree()
        return CafRuntimeProvisioning.from(
            CafPluginRegistry.from(
                pluginIds.map { pluginId ->
                    CafPluginRegistration(
                        pluginId = pluginId,
                        protocolVersion = "1.0",
                        clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                        handlerFactory = CafPluginHandlerFactory {
                            object : CafSchemaBoundPluginHandler {
                                override fun schemaSnapshot() = tree
                            }
                        },
                        configTreeDecoder = CafConfigTreeDecoder { tree },
                        schemaProvider = { tree },
                        runtimeReady = { true },
                    )
                },
            ),
        )
    }

    private fun config(ultra: AirPlayUltraConfiguration) = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "1.0",
        main = AirPlayDisplayConfig(800, 480),
        cluster = AirPlayDisplayConfig(800, 240),
        ultra = ultra,
    )
}
