package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.vehicle.ultra.VehicleControlResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateListener
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateRepository
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateSubscription
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateUpdate
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleStateCafPluginHandlerTest {
    @Test
    fun subscribeRepositoryCannotMutateCommittedIids() {
        val repository = FakeRepository(SyntheticCafTestFixture.tree())
        val received = mutableListOf<Set<Long>>()
        val mutationRejected = mutableListOf<Boolean>()
        repository.subscribeResult = { iids, _ ->
            received += iids.toSet()
            mutationRejected += runCatching {
                @Suppress("UNCHECKED_CAST")
                (iids as MutableSet<Long>).clear()
            }.isFailure
            VehicleStateSubscription { }
        }
        val captured = mutableListOf<RcsMessage>()
        val stream = testStream()
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = vehicleRegistry(repository),
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        try {
            session.onMessage(
                stream,
                CarAccessoryMessages.registerRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 90,
                ).asRcsMessage(),
            )
            session.onMessage(
                stream,
                CarAccessoryMessages.unregisterRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 91,
                    identifiers = listOf(SyntheticCafTestFixture.TEMPERATURE_IID),
                ).asRcsMessage(),
            )

            assertEquals(
                listOf(
                    setOf(
                        SyntheticCafTestFixture.TEMPERATURE_IID,
                        SyntheticCafTestFixture.ERROR_STATE_IID,
                    ),
                    setOf(SyntheticCafTestFixture.ERROR_STATE_IID),
                ),
                received,
            )
            assertEquals(listOf(true, true), mutationRejected)
        } finally {
            session.close()
        }
    }

    @Test
    fun replacementFailureClosesNewlyCreatedSubscription() {
        val registryRepository = FakeRepository(SyntheticCafTestFixture.tree())
        val repository = FakeRepository(SyntheticCafTestFixture.tree())
        val captured = mutableListOf<RcsMessage>()
        val stream = testStream()
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = vehicleRegistry(registryRepository),
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        var newSubscriptionCloses = 0
        lateinit var handler: VehicleStateCafPluginHandler
        repository.subscribeResult = { _, _ ->
            handler.close()
            VehicleStateSubscription { newSubscriptionCloses++ }
        }
        handler = VehicleStateCafPluginHandler(
            session = session,
            pluginId = SyntheticCafTestFixture.PLUGIN_ID,
            repository = repository,
            errorPolicy = CafErrorPolicy(-1, -2, -3, -4),
        )
        try {
            handler.onRegisterRequest(
                session,
                CarAccessoryMessages.reader(
                    CarAccessoryMessages.registerRequest(
                        SyntheticCafTestFixture.PLUGIN_ID,
                        transactionId = 92,
                    ).rcsBody,
                ),
            )

            assertEquals(1, newSubscriptionCloses)
            val response = CarAccessoryMessages.reader(captured.single().body)
            assertEquals(
                setOf(
                    SyntheticCafTestFixture.TEMPERATURE_IID,
                    SyntheticCafTestFixture.ERROR_STATE_IID,
                ).associateWith { -3L },
                response.errors(),
            )
        } finally {
            handler.close()
            session.close()
        }
    }

    @Test
    fun constructorFailureShutsDownProvidedScheduler() {
        val registryRepository = FakeRepository(SyntheticCafTestFixture.tree())
        val raw = SyntheticCafTestFixture.mutableRaw()
        @Suppress("UNCHECKED_CAST")
        val service = (((raw["accessories"] as MutableList<MutableMap<String, Any?>>)
            .single()["services"] as MutableList<MutableMap<String, Any?>>).single())
        @Suppress("UNCHECKED_CAST")
        val characteristics = service["characteristics"] as MutableList<MutableMap<String, Any?>>
        characteristics.removeAll { it["priority"] != 1L }
        @Suppress("UNCHECKED_CAST")
        (service["controls"] as MutableList<MutableMap<String, Any?>>).clear()
        val unroutedRepository = FakeRepository(CafConfigTree.decode(raw))
        val stream = testStream()
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = vehicleRegistry(registryRepository),
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
        )
        val scheduler = ScheduledThreadPoolExecutor(1)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                VehicleStateCafPluginHandler(
                    session = session,
                    pluginId = SyntheticCafTestFixture.PLUGIN_ID,
                    repository = unroutedRepository,
                    errorPolicy = CafErrorPolicy(-1, -2, -3, -4),
                    scheduler = scheduler,
                )
            }
            assertTrue(scheduler.isShutdown)
        } finally {
            scheduler.shutdownNow()
            session.close()
        }
    }

    @Test
    fun readRepositoryCannotMutateIidsOrCoverageSnapshot() {
        val repository = FakeRepository(SyntheticCafTestFixture.tree())
        var mutationRejected = false
        repository.readResult = { iids ->
            mutationRejected = runCatching {
                @Suppress("UNCHECKED_CAST")
                (iids as MutableSet<Long>).clear()
            }.isFailure
            VehicleStateResult()
        }
        val captured = mutableListOf<RcsMessage>()
        val stream = testStream()
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = vehicleRegistry(repository),
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        val requested = listOf(
            SyntheticCafTestFixture.TEMPERATURE_IID,
            SyntheticCafTestFixture.ERROR_STATE_IID,
        )
        try {
            session.onMessage(
                stream,
                CarAccessoryMessages.readRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 100,
                    identifiers = requested,
                ).asRcsMessage(),
            )
            val response = CarAccessoryMessages.reader(captured.single().body)
            assertTrue(mutationRejected)
            assertEquals(requested.toSet().associateWith { -3L }, response.errors())
        } finally {
            session.close()
        }
    }

    @Test
    fun writeRepositoryCannotMutateRequestOrCoverageSnapshot() {
        val repository = FakeRepository(SyntheticCafTestFixture.constrainedWritableTree())
        var mutationRejected = false
        repository.writeResult = { values ->
            mutationRejected = runCatching {
                @Suppress("UNCHECKED_CAST")
                (values as MutableMap<Long, Any?>).clear()
            }.isFailure
            VehicleStateResult()
        }
        val captured = mutableListOf<RcsMessage>()
        val stream = testStream()
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = vehicleRegistry(repository),
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        val requested = linkedMapOf<Long, Any?>(
            SyntheticCafTestFixture.TEMPERATURE_IID to 20.0,
            SyntheticCafTestFixture.STRING_SETTING_IID to "AUTO",
        )
        try {
            session.onMessage(
                stream,
                CarAccessoryMessages.writeRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 101,
                    values = requested,
                ).asRcsMessage(),
            )
            val response = CarAccessoryMessages.reader(captured.single().body)
            assertTrue(mutationRejected)
            assertEquals(requested.keys.associateWith { -3L }, response.errors())
        } finally {
            session.close()
        }
    }

    @Test
    fun controlRepositoryCannotMutateNestedRequestOrCoverageSnapshot() {
        val repository = FakeRepository(SyntheticCafTestFixture.tree())
        var mapMutationRejected = false
        var payloadMutationRejected = false
        repository.controlResult = { values ->
            @Suppress("UNCHECKED_CAST")
            val payload = values.getValue(SyntheticCafTestFixture.CONTROL_IID)
                as MutableMap<String, Any?>
            mapMutationRejected = runCatching {
                @Suppress("UNCHECKED_CAST")
                (values as MutableMap<Long, Any?>).clear()
            }.isFailure
            payloadMutationRejected = runCatching { payload.clear() }.isFailure
            VehicleControlResult()
        }
        val captured = mutableListOf<RcsMessage>()
        val stream = testStream()
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = vehicleRegistry(repository),
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        try {
            session.onMessage(
                stream,
                CarAccessoryMessages.controlRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 102,
                    values = mapOf(
                        SyntheticCafTestFixture.CONTROL_IID to mapOf("reason" to "driver"),
                    ),
                ).asRcsMessage(),
            )
            val response = CarAccessoryMessages.reader(captured.single().body)
            assertTrue(mapMutationRejected)
            assertTrue(payloadMutationRejected)
            assertEquals(-3L, response.errors()[SyntheticCafTestFixture.CONTROL_IID])
        } finally {
            session.close()
        }
    }

    @Test
    fun registerReadCoalescedUpdateAndUnregisterAreChannelScoped() {
        val repository = FakeRepository(SyntheticCafTestFixture.tree())
        val captured = Collections.synchronizedList(mutableListOf<RcsMessage>())
        val stream = testStream()
        val registry = vehicleRegistry(repository)
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = registry,
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        try {
            session.onMessage(
                stream,
                CarAccessoryMessages.registerRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 1,
                    registration = CafRegistration.Wildcard,
                ).asRcsMessage(),
            )
            val register = CarAccessoryMessages.reader(captured.single().body)
            assertEquals(CafCommand.REGISTER_RESPONSE, register.command)
            assertEquals(20.0, register.valuesMap()[SyntheticCafTestFixture.TEMPERATURE_IID])
            assertEquals(
                setOf(SyntheticCafTestFixture.TEMPERATURE_IID, SyntheticCafTestFixture.ERROR_STATE_IID),
                repository.subscribed,
            )

            repository.emit(mapOf(SyntheticCafTestFixture.TEMPERATURE_IID to 21.0))
            repository.emit(mapOf(SyntheticCafTestFixture.TEMPERATURE_IID to 22.0))
            waitForMessages(captured, 2)
            val update = CarAccessoryMessages.reader(captured[1].body)
            assertEquals(CafCommand.UPDATE_NOTIFY, update.command)
            assertEquals(22.0, update.valuesMap()[SyntheticCafTestFixture.TEMPERATURE_IID])

            session.onMessage(
                stream,
                CarAccessoryMessages.unregisterRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 2,
                    identifiers = listOf(SyntheticCafTestFixture.TEMPERATURE_IID),
                ).asRcsMessage(),
            )
            assertEquals(
                setOf(SyntheticCafTestFixture.ERROR_STATE_IID),
                repository.subscribed,
            )
            val countAfterUnregister = captured.size
            repository.emit(mapOf(SyntheticCafTestFixture.TEMPERATURE_IID to 23.0))
            Thread.sleep(80)
            assertEquals(countAfterUnregister, captured.size)
        } finally {
            session.close()
        }
        assertTrue(repository.subscribed.isEmpty())
    }

    @Test
    fun unsupportedWriteReturnsErrorAndNeverReportsEmptySuccess() {
        val repository = FakeRepository(SyntheticCafTestFixture.tree())
        val captured = mutableListOf<RcsMessage>()
        val stream = testStream()
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = vehicleRegistry(repository),
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        try {
            session.onMessage(
                stream,
                CarAccessoryMessages.writeRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 3,
                    values = mapOf(SyntheticCafTestFixture.TEMPERATURE_IID to 25.0),
                ).asRcsMessage(),
            )
            val response = CarAccessoryMessages.reader(captured.single().body)
            assertEquals(CafCommand.WRITE_RESPONSE, response.command)
            assertEquals(-2L, response.errors()[SyntheticCafTestFixture.TEMPERATURE_IID])
            assertEquals(0, repository.writeCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun malformedAndWrongDirectionControlsAreRejectedBeforeRepository() {
        val repository = FakeRepository(SyntheticCafTestFixture.tree())
        val captured = mutableListOf<RcsMessage>()
        val stream = testStream()
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = vehicleRegistry(repository),
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        try {
            session.onMessage(
                stream,
                CarAccessoryMessages.controlRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 4,
                    values = linkedMapOf(
                        SyntheticCafTestFixture.CONTROL_IID to mapOf("wrongField" to "x"),
                        SyntheticCafTestFixture.ACCESSORY_CONTROL_IID to null,
                    ),
                ).asRcsMessage(),
            )
            val response = CarAccessoryMessages.reader(captured.single().body)
            assertEquals(CafCommand.CONTROL_RESPONSE, response.command)
            assertTrue(response.valuesMap().isEmpty())
            assertEquals(-2L, response.errors()[SyntheticCafTestFixture.CONTROL_IID])
            assertEquals(-2L, response.errors()[SyntheticCafTestFixture.ACCESSORY_CONTROL_IID])
            assertEquals(0, repository.controlCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun controlRequestAndRepositoryResponseMustMatchDeclaredParameterSchemas() {
        val repository = FakeRepository(SyntheticCafTestFixture.tree())
        repository.controlResult = {
            VehicleControlResult(
                values = mapOf(
                    SyntheticCafTestFixture.CONTROL_IID to mapOf("accepted" to "not-a-bool"),
                ),
                succeededIids = setOf(SyntheticCafTestFixture.CONTROL_IID),
            )
        }
        val captured = mutableListOf<RcsMessage>()
        val stream = testStream()
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = vehicleRegistry(repository),
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        try {
            val validRequest = mapOf<Long, Any?>(
                SyntheticCafTestFixture.CONTROL_IID to mapOf("reason" to "driver"),
            )
            session.onMessage(
                stream,
                CarAccessoryMessages.controlRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 5,
                    values = validRequest,
                ).asRcsMessage(),
            )
            val malformedResponse = CarAccessoryMessages.reader(captured.removeAt(0).body)
            assertTrue(malformedResponse.valuesMap().isEmpty())
            assertEquals(-2L, malformedResponse.errors()[SyntheticCafTestFixture.CONTROL_IID])
            assertEquals(1, repository.controlCalls)

            repository.controlResult = {
                VehicleControlResult(
                    values = mapOf(
                        SyntheticCafTestFixture.CONTROL_IID to mapOf("accepted" to true),
                    ),
                    succeededIids = setOf(SyntheticCafTestFixture.CONTROL_IID),
                )
            }
            session.onMessage(
                stream,
                CarAccessoryMessages.controlRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 6,
                    values = validRequest,
                ).asRcsMessage(),
            )
            val validResponse = CarAccessoryMessages.reader(captured.single().body)
            assertEquals(
                mapOf("accepted" to true),
                validResponse.valuesMap()[SyntheticCafTestFixture.CONTROL_IID],
            )
            assertTrue(validResponse.errors().isEmpty())
            assertEquals(2, repository.controlCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun constraintViolationsNeverReachRepositoryWrite() {
        val repository = FakeRepository(SyntheticCafTestFixture.constrainedWritableTree())
        val captured = mutableListOf<RcsMessage>()
        val stream = testStream()
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = vehicleRegistry(repository),
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        try {
            session.onMessage(
                stream,
                CarAccessoryMessages.writeRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 7,
                    values = linkedMapOf(
                        SyntheticCafTestFixture.TEMPERATURE_IID to 20.25,
                        SyntheticCafTestFixture.STRING_SETTING_IID to "SPORT",
                    ),
                ).asRcsMessage(),
            )
            val rangeAndLength = CarAccessoryMessages.reader(captured.removeAt(0).body)
            assertEquals(-2L, rangeAndLength.errors()[SyntheticCafTestFixture.TEMPERATURE_IID])
            assertEquals(-2L, rangeAndLength.errors()[SyntheticCafTestFixture.STRING_SETTING_IID])
            assertEquals(0, repository.writeCalls)

            session.onMessage(
                stream,
                CarAccessoryMessages.writeRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 8,
                    values = mapOf(SyntheticCafTestFixture.STRING_SETTING_IID to "MAN"),
                ).asRcsMessage(),
            )
            val invalidEnum = CarAccessoryMessages.reader(captured.single().body)
            assertEquals(-2L, invalidEnum.errors()[SyntheticCafTestFixture.STRING_SETTING_IID])
            assertEquals(0, repository.writeCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun writeRequiresExactExplicitSuccessOrErrorCoverage() {
        val repository = FakeRepository(SyntheticCafTestFixture.constrainedWritableTree())
        val captured = mutableListOf<RcsMessage>()
        val stream = testStream()
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = vehicleRegistry(repository),
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        val requested = linkedMapOf<Long, Any?>(
            SyntheticCafTestFixture.TEMPERATURE_IID to 20.0,
            SyntheticCafTestFixture.STRING_SETTING_IID to "AUTO",
        )
        try {
            repository.writeResult = { VehicleStateResult() }
            session.onMessage(
                stream,
                CarAccessoryMessages.writeRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 9,
                    values = requested,
                ).asRcsMessage(),
            )
            assertEquals(
                requested.keys.associateWith { -3L },
                CarAccessoryMessages.reader(captured.removeAt(0).body).errors(),
            )

            repository.writeResult = {
                VehicleStateResult(
                    succeededIids = setOf(SyntheticCafTestFixture.TEMPERATURE_IID, 999L),
                )
            }
            session.onMessage(
                stream,
                CarAccessoryMessages.writeRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 10,
                    values = requested,
                ).asRcsMessage(),
            )
            assertEquals(
                requested.keys.associateWith { -3L },
                CarAccessoryMessages.reader(captured.removeAt(0).body).errors(),
            )

            repository.writeResult = { values ->
                VehicleStateResult(succeededIids = values.keys)
            }
            session.onMessage(
                stream,
                CarAccessoryMessages.writeRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 11,
                    values = requested,
                ).asRcsMessage(),
            )
            assertTrue(CarAccessoryMessages.reader(captured.single().body).errors().isEmpty())
        } finally {
            session.close()
        }
    }

    @Test
    fun noResponseControlStillRequiresExplicitSuccessAndExactCoverage() {
        val raw = SyntheticCafTestFixture.mutableRaw()
        @Suppress("UNCHECKED_CAST")
        val controls = ((((raw["accessories"] as MutableList<MutableMap<String, Any?>>)
            .single()["services"] as MutableList<MutableMap<String, Any?>>)
            .single()["controls"] as MutableList<MutableMap<String, Any?>>))
        controls.first { it["iid"] == SyntheticCafTestFixture.ACCESSORY_CONTROL_IID }["sender"] = "device"
        val repository = FakeRepository(CafConfigTree.decode(raw))
        val captured = mutableListOf<RcsMessage>()
        val stream = testStream()
        val session = CafProtocolSession(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            stream = stream,
            plugins = vehicleRegistry(repository),
            decoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        val request = mapOf<Long, Any?>(SyntheticCafTestFixture.ACCESSORY_CONTROL_IID to null)
        try {
            repository.controlResult = { VehicleControlResult() }
            session.onMessage(
                stream,
                CarAccessoryMessages.controlRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 12,
                    values = request,
                ).asRcsMessage(),
            )
            assertEquals(
                -3L,
                CarAccessoryMessages.reader(captured.removeAt(0).body)
                    .errors()[SyntheticCafTestFixture.ACCESSORY_CONTROL_IID],
            )

            repository.controlResult = {
                VehicleControlResult(
                    succeededIids = setOf(SyntheticCafTestFixture.ACCESSORY_CONTROL_IID, 999L),
                )
            }
            session.onMessage(
                stream,
                CarAccessoryMessages.controlRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 13,
                    values = request,
                ).asRcsMessage(),
            )
            assertEquals(
                -3L,
                CarAccessoryMessages.reader(captured.removeAt(0).body)
                    .errors()[SyntheticCafTestFixture.ACCESSORY_CONTROL_IID],
            )

            repository.controlResult = { values ->
                VehicleControlResult(succeededIids = values.keys)
            }
            session.onMessage(
                stream,
                CarAccessoryMessages.controlRequest(
                    SyntheticCafTestFixture.PLUGIN_ID,
                    transactionId = 14,
                    values = request,
                ).asRcsMessage(),
            )
            val success = CarAccessoryMessages.reader(captured.single().body)
            assertTrue(success.valuesMap().isEmpty())
            assertTrue(success.errors().isEmpty())
        } finally {
            session.close()
        }
    }

    @Test
    fun optInMutationReplayReturnsExactWriteResponseWithoutSecondSideEffect() {
        val repository = FakeRepository(SyntheticCafTestFixture.constrainedWritableTree())
        repository.writeResult = { VehicleStateResult(succeededIids = it.keys) }
        val captured = mutableListOf<RcsMessage>()
        val stream = testStream()
        val session = CafProtocolSession(
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, stream,
            vehicleRegistry(repository, CafMutationReplayPolicy(maxTransactions = 1)),
            ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        try {
            val first = CarAccessoryMessages.writeRequest(
                SyntheticCafTestFixture.PLUGIN_ID, 44L,
                mapOf(SyntheticCafTestFixture.TEMPERATURE_IID to 20.0),
            ).asRcsMessage()
            session.onMessage(stream, first)
            session.onMessage(stream, first)
            assertEquals(1, repository.writeCalls)
            assertTrue(captured[0].body.contentEquals(captured[1].body))
            assertTrue(CarAccessoryMessages.reader(captured[1].body).errors().isEmpty())

            session.onMessage(stream, CarAccessoryMessages.writeRequest(
                SyntheticCafTestFixture.PLUGIN_ID, 44L,
                mapOf(SyntheticCafTestFixture.TEMPERATURE_IID to 21.0),
            ).asRcsMessage())
            assertEquals(CafCommand.GENERAL_ERROR,
                CarAccessoryMessages.reader(captured[2].body).command)
            assertEquals(-2L, CarAccessoryMessages.reader(captured[2].body).requireError())
            assertEquals(1, repository.writeCalls)

            session.onMessage(stream, CarAccessoryMessages.writeRequest(
                SyntheticCafTestFixture.PLUGIN_ID, 45L,
                mapOf(SyntheticCafTestFixture.TEMPERATURE_IID to 20.0),
            ).asRcsMessage())
            assertEquals(-3L, CarAccessoryMessages.reader(captured[3].body).requireError())
            assertEquals(1, repository.writeCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun optInMutationReplayCachesControlFailureAndResetsOnNewChannelSession() {
        val repository = FakeRepository(SyntheticCafTestFixture.tree())
        repository.controlResult = { throw IllegalStateException("unknown outcome") }
        val registry = vehicleRegistry(repository, CafMutationReplayPolicy())
        fun channel(): Triple<CafProtocolSession, RcsDataStream, MutableList<RcsMessage>> {
            val captured = mutableListOf<RcsMessage>()
            val stream = testStream()
            return Triple(CafProtocolSession(
                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, stream, registry,
                ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
                outboundSender = captured::add,
            ), stream, captured)
        }
        val request = CarAccessoryMessages.controlRequest(
            SyntheticCafTestFixture.PLUGIN_ID, 91L,
            mapOf(SyntheticCafTestFixture.CONTROL_IID to mapOf("reason" to "driver")),
        ).asRcsMessage()
        val (first, firstStream, firstResponses) = channel()
        try {
            first.onMessage(firstStream, request)
            first.onMessage(firstStream, request)
            assertEquals(1, repository.controlCalls)
            assertTrue(firstResponses[0].body.contentEquals(firstResponses[1].body))
            assertEquals(-3L, CarAccessoryMessages.reader(firstResponses[0].body)
                .errors()[SyntheticCafTestFixture.CONTROL_IID])
        } finally {
            first.close()
        }
        val (next, nextStream, _) = channel()
        try {
            next.onMessage(nextStream, request)
            assertEquals(2, repository.controlCalls)
        } finally {
            next.close()
        }
    }

    @Test
    fun concurrentIdenticalMutationRequestsShareOneRecordedOutcome() {
        val repository = FakeRepository(SyntheticCafTestFixture.constrainedWritableTree())
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        repository.writeResult = { values ->
            entered.countDown()
            assertTrue(proceed.await(2, TimeUnit.SECONDS))
            VehicleStateResult(succeededIids = values.keys)
        }
        val captured = Collections.synchronizedList(mutableListOf<RcsMessage>())
        val stream = testStream()
        val session = CafProtocolSession(
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, stream,
            vehicleRegistry(repository, CafMutationReplayPolicy()),
            ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = captured::add,
        )
        val request = CarAccessoryMessages.writeRequest(
            SyntheticCafTestFixture.PLUGIN_ID, 123L,
            mapOf(SyntheticCafTestFixture.TEMPERATURE_IID to 20.0),
        ).asRcsMessage()
        val first = Thread { session.onMessage(stream, request) }
        val second = Thread { session.onMessage(stream, request) }
        try {
            first.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            second.start()
            proceed.countDown()
            first.join(2_000)
            second.join(2_000)
            assertTrue(!first.isAlive && !second.isAlive)
            assertEquals(1, repository.writeCalls)
            assertEquals(2, captured.size)
            assertTrue(captured[0].body.contentEquals(captured[1].body))
        } finally {
            proceed.countDown()
            first.join(2_000)
            second.join(2_000)
            session.close()
        }
    }

    @Test
    fun failedFirstResponseSendCannotReexecuteUnknownMutation() {
        val repository = FakeRepository(SyntheticCafTestFixture.constrainedWritableTree())
        repository.writeResult = { VehicleStateResult(succeededIids = it.keys) }
        val captured = mutableListOf<RcsMessage>()
        var failFirst = true
        val stream = testStream()
        val session = CafProtocolSession(
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, stream,
            vehicleRegistry(repository, CafMutationReplayPolicy()),
            ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            outboundSender = { message ->
                if (failFirst) {
                    failFirst = false
                    throw IllegalStateException("test transport lost first response")
                }
                captured += message
            },
        )
        val request = CarAccessoryMessages.writeRequest(
            SyntheticCafTestFixture.PLUGIN_ID, 124L,
            mapOf(SyntheticCafTestFixture.TEMPERATURE_IID to 20.0),
        ).asRcsMessage()
        try {
            assertThrows(IllegalStateException::class.java) {
                session.onMessage(stream, request)
            }
            assertEquals(1, repository.writeCalls)
            session.onMessage(stream, request)
            assertEquals(1, repository.writeCalls)
            assertTrue(CarAccessoryMessages.reader(captured.single().body).errors().isEmpty())
        } finally {
            session.close()
        }
    }

    private fun vehicleRegistry(
        repository: FakeRepository,
        replayPolicy: CafMutationReplayPolicy? = null,
    ): CafPluginRegistry =
        CafPluginRegistry.from(
            listOf(
                CafPluginRegistration(
                    pluginId = SyntheticCafTestFixture.PLUGIN_ID,
                    protocolVersion = "1.0",
                    clientTypes = setOf(
                        RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                        RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                    ),
                    configTreeDecoder = CafConfigTreeDecoder { repository.configTree },
                    schemaProvider = { repository.configTree },
                    runtimeReady = { true },
                    handlerFactory = CafPluginHandlerFactory { session ->
                        VehicleStateCafPluginHandler(
                            session = session,
                            pluginId = SyntheticCafTestFixture.PLUGIN_ID,
                            repository = repository,
                            errorPolicy = CafErrorPolicy(
                                unknownIdentifier = -1,
                                invalidValue = -2,
                                operationFailed = -3,
                                generalFailure = -4,
                            ),
                            updatePolicy = CafUpdatePolicy(
                                coalesceMillis = 20,
                                maxSubscribedIids = 8,
                                maxIidsPerNotification = 8,
                            ),
                            mutationReplayPolicy = replayPolicy,
                        )
                    },
                ),
            ),
        )

    private fun testStream(): RcsDataStream = RcsDataStream.open(
        clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
        readKey = ByteArray(32) { 1 },
        writeKey = ByteArray(32) { 2 },
        bindAddress = InetAddress.getLoopbackAddress(),
    )

    private fun waitForMessages(messages: List<RcsMessage>, count: Int) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (messages.size < count && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("expected $count messages, got ${messages.size}", messages.size >= count)
    }

    private class FakeRepository(override val configTree: CafConfigTree) : VehicleStateRepository {
        @Volatile var subscribed: Set<Long> = emptySet()
        @Volatile var writeCalls = 0
        @Volatile var controlCalls = 0
        @Volatile var writeResult: (Map<Long, Any?>) -> VehicleStateResult = { values ->
            VehicleStateResult(errors = values.keys.associateWith { -99L })
        }
        @Volatile var controlResult: (Map<Long, Any?>) -> VehicleControlResult = { values ->
            VehicleControlResult(errors = values.keys.associateWith { -99L })
        }
        @Volatile var readResult: ((Set<Long>) -> VehicleStateResult)? = null
        @Volatile var subscribeResult:
            ((Set<Long>, VehicleStateListener) -> VehicleStateSubscription)? = null
        @Volatile private var listener: VehicleStateListener? = null

        override fun read(iids: Set<Long>): VehicleStateResult {
            readResult?.let { return it(iids) }
            val values = buildMap<Long, Any?> {
                if (SyntheticCafTestFixture.TEMPERATURE_IID in iids) {
                    put(SyntheticCafTestFixture.TEMPERATURE_IID, 20.0)
                }
                if (SyntheticCafTestFixture.ERROR_STATE_IID in iids) {
                    put(SyntheticCafTestFixture.ERROR_STATE_IID, 0L)
                }
            }
            return VehicleStateResult(values)
        }

        override fun subscribe(
            iids: Set<Long>,
            listener: VehicleStateListener,
        ): VehicleStateSubscription {
            subscribeResult?.let { return it(iids, listener) }
            subscribed = iids.toSet()
            this.listener = listener
            return VehicleStateSubscription {
                if (this.listener === listener) {
                    this.listener = null
                    subscribed = emptySet()
                }
            }
        }

        override fun write(values: Map<Long, Any?>): VehicleStateResult {
            writeCalls++
            return writeResult(values)
        }

        override fun control(values: Map<Long, Any?>): VehicleControlResult {
            controlCalls++
            return controlResult(values)
        }

        fun emit(values: Map<Long, Any?>) {
            listener?.onUpdate(VehicleStateUpdate(values))
        }
    }
}
