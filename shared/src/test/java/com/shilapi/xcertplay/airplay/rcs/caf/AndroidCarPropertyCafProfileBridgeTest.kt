package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.vehicle.CarPropertyValue
import com.shilapi.xcertplay.vehicle.ReadOnlyCarPropertyBackend
import com.shilapi.xcertplay.vehicle.ultra.AndroidCarPropertyCafBinding
import com.shilapi.xcertplay.vehicle.ultra.AndroidCarPropertyCafPollingPolicy
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateListener
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateRepository
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateUpdate
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCarPropertyCafProfileBridgeTest {
    @Test
    fun revokedAuthorizationRetiresBothOpenDataChannelsAndCannotRevive() {
        val backend = FakeBackend().apply {
            isConnected = true
            values[PROPERTY_TEMPERATURE to 0] = CarPropertyValue(21.5f, null)
            values[PROPERTY_HIGH to 2] = CarPropertyValue(7, null)
        }
        val granted = AtomicBoolean(true)
        val bridge = bridge(backend)
        bridge.bindRuntimeAuthorization(granted::get)
        val owner = bridge.provisioning.transferToRuntime()
        val child = owner.openSession()
        val data = openChannel(child, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 89)
        val data2 = openChannel(child, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, 90)
        try {
            assertTrue(owner.isLive())
            granted.set(false)
            assertFalse(owner.isLive())
            assertFalse(child.isLive())
            assertFalse(bridge.readOnlyInstrumentSource.isCurrent)
            assertTrue(data.awaitEof(2_000))
            assertTrue(data2.awaitEof(2_000))
            assertEquals(0, backend.reads.get())
            granted.set(true)
            assertFalse(owner.isLive())
            assertThrows(IllegalStateException::class.java) { owner.openSession() }
        } finally {
            data.close()
            data2.close()
            closeChildEventually(child)
            owner.close()
            bridge.close()
        }
    }

    @Test
    fun localInstrumentSourceStopsImmediatelyOnDisconnectWithoutWaitingForPoll() {
        val backend = FakeBackend().apply {
            isConnected = true
            values[PROPERTY_TEMPERATURE to 0] = CarPropertyValue(21.5f, null)
        }
        val bridge = bridge(backend)
        try {
            val source = bridge.readOnlyInstrumentSource
            assertTrue(source.isCurrent)
            assertEquals(21.5, source.read(setOf(TEMPERATURE_IID)).values[TEMPERATURE_IID])
            backend.isConnected = false
            assertFalse(source.isCurrent)
            assertThrows(IllegalStateException::class.java) { source.read(setOf(TEMPERATURE_IID)) }
            // CAF itself remains able to return the signed operationFailed on a disconnected car.
            assertTrue(bridge.provisioning.authorizationSnapshot() != null)
        } finally {
            bridge.close()
        }
    }

    @Test
    fun localInstrumentViewUsesSameReadOnlyRepositoryAndClosesWithBridge() {
        val backend = FakeBackend().apply {
            isConnected = true
            values[PROPERTY_TEMPERATURE to 0] = CarPropertyValue(21.5f, 1L)
        }
        val bridge = bridge(backend)
        val source = bridge.readOnlyInstrumentSource
        assertFalse(source is VehicleStateRepository)
        assertTrue(source.isCurrent)
        assertEquals(21.5, source.read(setOf(TEMPERATURE_IID)).values[TEMPERATURE_IID])
        val unavailable = AtomicInteger()
        val subscription = source.subscribe(setOf(TEMPERATURE_IID), object : VehicleStateListener {
            override fun onUpdate(update: VehicleStateUpdate) = Unit
            override fun onUnavailable(iids: Set<Long>) {
                if (TEMPERATURE_IID in iids) unavailable.incrementAndGet()
            }
        })
        bridge.close()
        assertFalse(source.isCurrent)
        assertTrue(unavailable.get() >= 1)
        assertThrows(IllegalStateException::class.java) {
            source.read(setOf(TEMPERATURE_IID))
        }
        assertThrows(IllegalStateException::class.java) {
            source.subscribe(setOf(TEMPERATURE_IID), VehicleStateListener { })
        }
        subscription.close()
    }

    @Test
    fun exactProfileBuildsIndependentDataChannelsAndSignedErrorsUntilVehicleConnects() {
        val backend = FakeBackend()
        val bridge = bridge(backend)
        val profileInfo = bridge.provisioning.vehicleStateProtocolInfo()
        val snapshot = requireNotNull(bridge.provisioning.authorizationSnapshot())
        assertEquals(PLUGIN_ID, CafUInt64.parse(profileInfo.pluginConfigs.single()["pluginID"], "id"))
        assertEquals("read-only-test", profileInfo.pluginConfigs.single()["fixtureProfile"])
        assertEquals(DECODER_ID, snapshot.decoderImplementationId)
        assertEquals(mapOf(PLUGIN_ID to HANDLER_ID), snapshot.handlerImplementationIds)
        assertFalse(bridge.isAndroidCarServiceConnected)

        val owner = bridge.provisioning.transferToRuntime()
        val child = owner.openSession()
        val data = openChannel(child, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 1)
        val data2 = openChannel(child, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, 2)
        try {
            assertNotSame(data.session, data2.session)
            assertTrue(owner.isLive()) // Protocol response readiness is not vehicle-value readiness.
            assertEquals(
                mapOf(TEMPERATURE_IID to ERRORS.operationFailed),
                data.exchange(CarAccessoryMessages.readRequest(PLUGIN_ID, 1L, listOf(TEMPERATURE_IID)))
                    .errors(),
            )
            assertEquals(0, backend.reads.get())

            backend.isConnected = true
            backend.values[PROPERTY_TEMPERATURE to 0] = CarPropertyValue(21.5f, null)
            backend.values[PROPERTY_ERROR to 0] = CarPropertyValue(0L, null)
            backend.values[PROPERTY_HIGH to 2] = CarPropertyValue(7, null)
            assertTrue(bridge.isAndroidCarServiceConnected)

            val lowRead = data.exchange(
                CarAccessoryMessages.readRequest(PLUGIN_ID, 2L, listOf(TEMPERATURE_IID, HIGH_IID)),
            )
            assertEquals(mapOf(TEMPERATURE_IID to 21.5), lowRead.valuesMap())
            assertEquals(mapOf(HIGH_IID to ERRORS.unknownIdentifier), lowRead.errors())

            val highRead = data2.exchange(
                CarAccessoryMessages.readRequest(PLUGIN_ID, 3L, listOf(HIGH_IID, TEMPERATURE_IID)),
            )
            assertEquals(mapOf(HIGH_IID to 7L), highRead.valuesMap())
            assertEquals(mapOf(TEMPERATURE_IID to ERRORS.unknownIdentifier), highRead.errors())
            assertEquals(listOf(PROPERTY_TEMPERATURE to 0, PROPERTY_HIGH to 2), backend.calls.toList())

            val mixedRegister = data.exchange(
                CarAccessoryMessages.registerRequest(
                    PLUGIN_ID,
                    4L,
                    CafRegistration.Identifiers(listOf(TEMPERATURE_IID, HIGH_IID)),
                ),
            )
            assertEquals(setOf(TEMPERATURE_IID), mixedRegister.valuesMap().keys)
            assertEquals(mapOf(HIGH_IID to ERRORS.unknownIdentifier), mixedRegister.errors())
            val lowRegister = data.exchange(CarAccessoryMessages.registerRequest(PLUGIN_ID, 5L))
            assertEquals(setOf(TEMPERATURE_IID, ERROR_IID), lowRegister.valuesMap().keys)
            val highRegister = data2.exchange(CarAccessoryMessages.registerRequest(PLUGIN_ID, 6L))
            assertEquals(setOf(HIGH_IID), highRegister.valuesMap().keys)

            backend.isConnected = false
            assertFalse(bridge.isAndroidCarServiceConnected)
            assertEquals(
                mapOf(HIGH_IID to ERRORS.operationFailed),
                data2.exchange(CarAccessoryMessages.readRequest(PLUGIN_ID, 7L, listOf(HIGH_IID)))
                    .errors(),
            )
            assertEquals(
                mapOf(TEMPERATURE_IID to ERRORS.operationFailed),
                data.exchange(CarAccessoryMessages.readRequest(PLUGIN_ID, 8L, listOf(TEMPERATURE_IID)))
                    .errors(),
            )
            data.close()
            backend.isConnected = true
            assertEquals(
                mapOf(HIGH_IID to 7L),
                data2.exchange(CarAccessoryMessages.readRequest(PLUGIN_ID, 9L, listOf(HIGH_IID)))
                    .valuesMap(),
            )
            assertEquals(0, backend.closeCalls.get())
        } finally {
            data.close()
            data2.close()
            closeChildEventually(child)
            owner.close()
            bridge.close()
        }
        assertEquals(1, backend.closeCalls.get())
    }

    @Test
    fun configAndReadOnlyMutationsNeverClaimControlSuccess() {
        val backend = FakeBackend().apply { isConnected = true }
        val bridge = bridge(backend)
        val owner = bridge.provisioning.transferToRuntime()
        val child = owner.openSession()
        val channel = openChannel(child, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 3)
        try {
            val config = channel.exchange(CarAccessoryMessages.configRequest(PLUGIN_ID, 10L))
            assertEquals(CafCommand.CONFIG_RESPONSE, config.command)
            assertEquals(readOnlyTree().root, config.configTree(CafFirmwareConfigTreeDecoder).root)

            val write = channel.exchange(
                CarAccessoryMessages.writeRequest(
                    PLUGIN_ID,
                    11L,
                    mapOf(TEMPERATURE_IID to 22.0),
                ),
            )
            assertEquals(CafCommand.WRITE_RESPONSE, write.command)
            assertEquals(mapOf(TEMPERATURE_IID to ERRORS.invalidValue), write.errors())

            val control = channel.exchange(
                CarAccessoryMessages.controlRequest(PLUGIN_ID, 12L, mapOf(0x777L to null)),
            )
            assertEquals(CafCommand.CONTROL_RESPONSE, control.command)
            assertEquals(mapOf(0x777L to ERRORS.unknownIdentifier), control.errors())
            assertEquals(0, backend.reads.get())
        } finally {
            channel.close()
            closeChildEventually(child)
            owner.close()
            bridge.close()
        }
    }

    @Test
    fun directOpackConfigRequestReceivesDirectOpackResponse() {
        val bridge = bridge(FakeBackend().apply { isConnected = true })
        val owner = bridge.provisioning.transferToRuntime()
        val child = owner.openSession()
        val channel = openChannel(child, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, 37)
        try {
            val codec = CafRcsCodec()
            val request = CarAccessoryMessages.configRequest(PLUGIN_ID, 92L)
            channel.peer.send(
                codec.encodeEnvelope(request.envelope, CafCarrierStyle.DIRECT_OPACK).asRcsMessage(),
            )
            val response = requireNotNull(channel.peer.receiveNext(2_000))
            val decoded = codec.decodeCarrier(response.body)
            assertEquals(CafCarrierStyle.DIRECT_OPACK, decoded.style)
            assertEquals(CafCommand.CONFIG_RESPONSE, decoded.envelope.message.command)
            assertEquals(92L, decoded.envelope.message.transactionId)
        } finally {
            channel.close()
            closeChildEventually(child)
            owner.close()
            bridge.close()
        }
    }

    @Test
    fun bridgeCloseRevokesValuesAndBackendFailureCanBeRetried() {
        val backend = FakeBackend().apply {
            isConnected = true
            failFirstClose = true
            values[PROPERTY_TEMPERATURE to 0] = CarPropertyValue(20.0f, null)
        }
        val bridge = bridge(backend)
        val owner = bridge.provisioning.transferToRuntime()
        val child = owner.openSession()
        val channel = openChannel(child, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 4)
        try {
            assertEquals(
                20.0,
                channel.exchange(CarAccessoryMessages.readRequest(PLUGIN_ID, 20L, listOf(TEMPERATURE_IID)))
                    .valuesMap()[TEMPERATURE_IID],
            )
            assertThrows(IllegalStateException::class.java) { bridge.close() }
            assertFalse(bridge.isAndroidCarServiceConnected)
            assertEquals(
                ERRORS.operationFailed,
                channel.exchange(CarAccessoryMessages.readRequest(PLUGIN_ID, 21L, listOf(TEMPERATURE_IID)))
                    .errors()[TEMPERATURE_IID],
            )
            bridge.close()
            assertEquals(2, backend.closeCalls.get())
        } finally {
            channel.close()
            closeChildEventually(child)
            owner.close()
            bridge.close()
        }
    }

    @Test
    fun publishedSubscriptionLosingCarServiceClosesRcsWithoutInventingAnInvalidValue() {
        val backend = FakeBackend()
        val bridge = bridge(backend, pollingMillis = 20)
        val owner = bridge.provisioning.transferToRuntime()
        val child = owner.openSession()
        val channel = openChannel(child, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 5)
        try {
            val pending = channel.exchange(CarAccessoryMessages.registerRequest(PLUGIN_ID, 30L))
            assertTrue(pending.valuesMap().isEmpty())
            assertEquals(
                mapOf(
                    TEMPERATURE_IID to ERRORS.operationFailed,
                    ERROR_IID to ERRORS.operationFailed,
                ),
                pending.errors(),
            )
            Thread.sleep(80) // Unavailable polls before any successful publication must not retire it.
            val stillPending = channel.exchange(
                CarAccessoryMessages.readRequest(PLUGIN_ID, 31L, listOf(TEMPERATURE_IID)),
            )
            assertEquals(ERRORS.operationFailed, stillPending.errors()[TEMPERATURE_IID])

            backend.values[PROPERTY_TEMPERATURE to 0] = CarPropertyValue(21.5f, null)
            backend.values[PROPERTY_ERROR to 0] = CarPropertyValue(0L, null)
            backend.isConnected = true
            val published = channel.exchange(CarAccessoryMessages.registerRequest(PLUGIN_ID, 32L))
            assertEquals(21.5, published.valuesMap()[TEMPERATURE_IID])

            backend.isConnected = false
            assertTrue("published CAF source loss must close the RCS channel", channel.awaitEof(2_000))
            backend.isConnected = true // A retired subscription must not resume polling later.
            Thread.sleep(80)
            val readsAfterRetirement = backend.reads.get()
            Thread.sleep(80)
            assertEquals(readsAfterRetirement, backend.reads.get())
            assertEquals(0, backend.closeCalls.get())
        } finally {
            channel.close()
            closeChildEventually(child)
            owner.close()
            bridge.close()
        }
        assertEquals(1, backend.closeCalls.get())
    }

    @Test
    fun onePublishedPropertyFailureRetiresOnlyItsPriorityChannel() {
        val backend = FakeBackend().apply {
            isConnected = true
            values[PROPERTY_TEMPERATURE to 0] = CarPropertyValue(20.0f, null)
            values[PROPERTY_ERROR to 0] = CarPropertyValue(0L, null)
            values[PROPERTY_HIGH to 2] = CarPropertyValue(7, null)
        }
        val bridge = bridge(backend, pollingMillis = 20)
        val owner = bridge.provisioning.transferToRuntime()
        val child = owner.openSession()
        val data = openChannel(child, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 6)
        val data2 = openChannel(child, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, 7)
        try {
            assertEquals(20.0,
                data.exchange(CarAccessoryMessages.registerRequest(PLUGIN_ID, 40L))
                    .valuesMap()[TEMPERATURE_IID])
            assertEquals(7L,
                data2.exchange(CarAccessoryMessages.registerRequest(PLUGIN_ID, 41L))
                    .valuesMap()[HIGH_IID])
            backend.values.remove(PROPERTY_TEMPERATURE to 0)

            assertTrue(data.awaitEof(2_000))
            assertEquals(7L,
                data2.exchange(CarAccessoryMessages.readRequest(PLUGIN_ID, 42L, listOf(HIGH_IID)))
                    .valuesMap()[HIGH_IID])
        } finally {
            data.close()
            data2.close()
            closeChildEventually(child)
            owner.close()
            bridge.close()
        }
    }

    @Test
    fun bridgeShutdownAfterPublicationAlsoRetiresTransferredRuntimeChannel() {
        val backend = FakeBackend().apply {
            isConnected = true
            values[PROPERTY_TEMPERATURE to 0] = CarPropertyValue(19.0f, null)
            values[PROPERTY_ERROR to 0] = CarPropertyValue(0L, null)
        }
        val bridge = bridge(backend, pollingMillis = 20)
        val owner = bridge.provisioning.transferToRuntime()
        val child = owner.openSession()
        val channel = openChannel(child, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 8)
        try {
            assertEquals(19.0,
                channel.exchange(CarAccessoryMessages.registerRequest(PLUGIN_ID, 50L))
                    .valuesMap()[TEMPERATURE_IID])
            bridge.close()
            assertTrue(channel.awaitEof(2_000))
            assertFalse(bridge.isAndroidCarServiceConnected)
            assertEquals(1, backend.closeCalls.get())
        } finally {
            channel.close()
            closeChildEventually(child)
            owner.close()
            bridge.close()
        }
    }

    @Test
    fun disconnectBetweenInitialReadAndRegisterPublicationCannotLeakLastValue() {
        val backend = FakeBackend().apply {
            isConnected = true
            values[PROPERTY_TEMPERATURE to 0] = CarPropertyValue(22.0f, null)
            values[PROPERTY_ERROR to 0] = CarPropertyValue(0L, null)
            afterRead = { count -> if (count == 3) isConnected = false }
        }
        val bridge = bridge(backend)
        val owner = bridge.provisioning.transferToRuntime()
        val child = owner.openSession()
        val channel = openChannel(child, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 9)
        try {
            channel.peer.send(CarAccessoryMessages.registerRequest(PLUGIN_ID, 60L).asRcsMessage())
            val received = mutableListOf<CafMessageReader>()
            assertTrue(channel.awaitEof(2_000) { received += it })
            assertTrue(received.none {
                it.command == CafCommand.REGISTER_RESPONSE && it.valuesMap().isNotEmpty()
            })
            assertEquals(3, backend.reads.get())
        } finally {
            channel.close()
            closeChildEventually(child)
            owner.close()
            bridge.close()
        }
    }

    @Test
    fun profileAndAuthorizationMismatchFailBeforeAdvertisingAndCloseBackend() {
        val rejectedBackend = FakeBackend()
        val mismatch = profile(decoderId = "test.decoder.not-installed")
        assertThrows(IllegalArgumentException::class.java) {
            AndroidCarPropertyCafProfileBridge.fromBackend(mismatch, rejectedBackend)
        }
        assertEquals(0, rejectedBackend.connectCalls.get())
        assertEquals(1, rejectedBackend.closeCalls.get())

        val notStarted = FakeBackend().apply { connectAccepted = false }
        assertThrows(IllegalStateException::class.java) {
            AndroidCarPropertyCafProfileBridge.fromBackend(profile(), notStarted)
        }
        assertEquals(1, notStarted.closeCalls.get())

        val pendingCleanup = FakeBackend().apply {
            connectAccepted = false
            failFirstClose = true
        }
        val cleanup = assertThrows(AndroidCarPropertyCafBridgeConstructionException::class.java) {
            AndroidCarPropertyCafProfileBridge.fromBackend(profile(), pendingCleanup)
        }
        cleanup.close()
        assertEquals(2, pendingCleanup.closeCalls.get())

        // The profile constructor itself rejects unidentified handler implementations.
        assertThrows(IllegalArgumentException::class.java) { profile(handlerId = "") }
    }

    @Test
    fun readOnlyProfileRejectsControlsWritesMissingBindingsAndMutableInputDrift() {
        assertThrows(IllegalArgumentException::class.java) {
            profile(tree = SyntheticCafTestFixture.tree()) // Test tree declares controls.
        }
        val writableRaw = readOnlyRaw()
        characteristicRows(writableRaw).first()["writable"] = true
        assertThrows(IllegalArgumentException::class.java) {
            profile(tree = CafConfigTree.decode(writableRaw))
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile(bindings = bindings().dropLast(1))
        }

        val config = linkedMapOf<String, Any?>(
            "pluginID" to PLUGIN_ID,
            "fixtureProfile" to "read-only-test",
        )
        val list = bindings().toMutableList()
        val profile = profile(pluginConfig = config, bindings = list)
        config["fixtureProfile"] = "mutated"
        list.clear()
        assertEquals("read-only-test", profile.wirePluginConfig["fixtureProfile"])
        assertEquals(3, profile.bindings.size)
    }

    private fun bridge(backend: FakeBackend, pollingMillis: Long = 5_000) =
        AndroidCarPropertyCafProfileBridge.fromBackend(
        profile(),
        backend,
        pollingPolicy = AndroidCarPropertyCafPollingPolicy(intervalMillis = pollingMillis),
    )

    private fun profile(
        tree: CafConfigTree = readOnlyTree(),
        bindings: List<AndroidCarPropertyCafBinding> = bindings(),
        pluginConfig: Map<String, Any?> = mapOf(
            "pluginID" to PLUGIN_ID,
            "fixtureProfile" to "read-only-test",
        ),
        decoderId: String = DECODER_ID,
        handlerId: String = HANDLER_ID,
    ) = AndroidCarPropertyCafProfile(
        profileConfigTree = tree,
        pluginId = PLUGIN_ID,
        protocolVersion = "1.0",
        pluginConfig = pluginConfig,
        bindings = bindings,
        errorPolicy = ERRORS,
        decoderImplementationId = decoderId,
        handlerImplementationId = handlerId,
        pluginName = "test-only-property-bridge",
    )

    private fun readOnlyTree(): CafConfigTree = CafConfigTree.decode(readOnlyRaw())

    private fun readOnlyRaw(): MutableMap<String, Any?> = SyntheticCafTestFixture.mutableRaw().also { raw ->
        service(raw)["controls"] = mutableListOf<MutableMap<String, Any?>>()
    }

    @Suppress("UNCHECKED_CAST")
    private fun service(raw: MutableMap<String, Any?>): MutableMap<String, Any?> =
        ((raw["accessories"] as MutableList<MutableMap<String, Any?>>).single()["services"]
            as MutableList<MutableMap<String, Any?>>).single()

    @Suppress("UNCHECKED_CAST")
    private fun characteristicRows(raw: MutableMap<String, Any?>): MutableList<MutableMap<String, Any?>> =
        service(raw)["characteristics"] as MutableList<MutableMap<String, Any?>>

    private fun bindings() = listOf(
        AndroidCarPropertyCafBinding(TEMPERATURE_IID, PROPERTY_TEMPERATURE, 0),
        AndroidCarPropertyCafBinding(ERROR_IID, PROPERTY_ERROR, 0),
        AndroidCarPropertyCafBinding(HIGH_IID, PROPERTY_HIGH, 2),
    )

    private fun closeChildEventually(child: CafProvisionedSession) {
        repeat(100) {
            try {
                child.close()
                return
            } catch (busy: IllegalStateException) {
                if (busy.message != "CAF channel close is already in flight" &&
                    busy.message != "CAF channel still has operations in flight"
                ) throw busy
                Thread.sleep(10)
            }
        }
        child.close()
    }

    private fun openChannel(
        child: CafProvisionedSession,
        type: RcsClientType,
        seed: Int,
    ): ConnectedChannel {
        val readKey = ByteArray(32) { (seed + it).toByte() }
        val writeKey = ByteArray(32) { (seed + it + 64).toByte() }
        val address = InetAddress.getLoopbackAddress()
        val stream = RcsDataStream.open(type, readKey, writeKey, address, address)
        val descriptor = requireNotNull(child.handlerFactory.descriptor(type))
        assertTrue(descriptor.probe().isReady)
        val handler = descriptor.create(stream) as CafProtocolSession
        val port = stream.listen(handler)
        val peer = RcsChannel.connect(InetSocketAddress(address, port), writeKey, readKey)
        return ConnectedChannel(stream, peer, handler)
    }

    private class ConnectedChannel(
        val stream: RcsDataStream,
        val peer: RcsChannel,
        val session: CafProtocolSession,
    ) : AutoCloseable {
        fun exchange(frame: CafRcsFrame): CafMessageReader {
            peer.send(frame.asRcsMessage())
            repeat(4) {
                val message = requireNotNull(peer.receiveNext(2_000))
                val reader = CarAccessoryMessages.reader(message.body)
                if (reader.command != CafCommand.UPDATE_NOTIFY) return reader
            }
            error("CAF response did not arrive")
        }

        fun awaitEof(
            timeoutMillis: Long,
            onMessage: (CafMessageReader) -> Unit = {},
        ): Boolean {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (System.nanoTime() < deadline) {
                peer.receiveNext(100)?.let { onMessage(CarAccessoryMessages.reader(it.body)) }
                if (peer.isPeerEnded) return true
            }
            return peer.isPeerEnded
        }

        override fun close() {
            peer.close()
            stream.close()
        }
    }

    private class FakeBackend : ReadOnlyCarPropertyBackend {
        @Volatile override var isConnected = false
        @Volatile var connectAccepted = true
        @Volatile var failFirstClose = false
        val values = ConcurrentHashMap<Pair<Int, Int>, CarPropertyValue>()
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val reads = AtomicInteger()
        val connectCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        @Volatile var afterRead: ((Int) -> Unit)? = null

        override fun connect(): Boolean {
            connectCalls.incrementAndGet()
            return connectAccepted
        }

        override fun read(propertyId: Int): CarPropertyValue? = read(propertyId, 0)

        override fun read(propertyId: Int, areaId: Int): CarPropertyValue? {
            val count = reads.incrementAndGet()
            calls += propertyId to areaId
            return values[propertyId to areaId].also { afterRead?.invoke(count) }
        }

        override fun close() {
            val attempt = closeCalls.incrementAndGet()
            isConnected = false
            if (failFirstClose && attempt == 1) throw IllegalStateException("synthetic close failure")
        }
    }

    private companion object {
        const val PLUGIN_ID = SyntheticCafTestFixture.PLUGIN_ID
        const val TEMPERATURE_IID = SyntheticCafTestFixture.TEMPERATURE_IID
        const val ERROR_IID = SyntheticCafTestFixture.ERROR_STATE_IID
        const val HIGH_IID = SyntheticCafTestFixture.HIGH_PRIORITY_IID
        const val PROPERTY_TEMPERATURE = 0x11600703
        const val PROPERTY_ERROR = 0x11400400
        const val PROPERTY_HIGH = 0x11400401
        const val DECODER_ID = "xcertplay.caf.decoder.firmware-v1"
        const val HANDLER_ID = "test.android-car-caf-read-only.v1"
        val ERRORS = CafErrorPolicy(-101L, -102L, -103L, -104L)
    }
}
