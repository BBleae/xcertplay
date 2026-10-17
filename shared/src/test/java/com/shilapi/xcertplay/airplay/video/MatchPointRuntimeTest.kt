package com.shilapi.xcertplay.airplay.video

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.IOException
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPointRuntimeTest {
    @Test
    fun opaqueSessionIdentityCanBindExactlyOneRuntimeGeneration() {
        val identity = MatchPointSessionIdentity.create(19)
        val firstStream = newStream()
        val secondStream = newStream()
        val first = MatchPointRuntime(identity, firstStream, RecordingBackend(), outboundSender = {})
        try {
            assertThrows(IllegalArgumentException::class.java) {
                MatchPointRuntime(identity, secondStream, RecordingBackend(), outboundSender = {})
            }
            assertTrue(identity.toString().contains("generation=19"))
            assertTrue(identity.toString().contains("identity=<redacted>"))
        } finally {
            first.close()
            secondStream.close()
        }
    }

    @Test
    fun capturedCommModelRoundTripsThroughBackendAndResponse() {
        val sent = mutableListOf<RcsMessage>()
        val requests = mutableListOf<MatchPointInboundRequest>()
        val backend = object : MatchPointPlayerBackend {
            override fun onRequest(
                session: MatchPointPlayerSession,
                request: MatchPointInboundRequest,
            ) {
                requests += request
                session.respondSuccess(
                    request,
                    MatchPointPlaybackInfoResponse(mapOf("rate" to 1.0, "state" to "ready")),
                )
            }
        }
        val stream = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(7),
            stream,
            backend,
            outboundSender = sent::add,
        )
        try {
            runtime.onStreamOpened(stream)
            runtime.onMessage(
                stream,
                carrier(MatchPointMessage.request(41, MatchPointPlaybackInfoRequest)),
            )

            assertEquals(1, requests.size)
            assertSame(runtime.identity, requests.single().identity)
            val decoded = MatchPointCarrierCodec.decode(sent.single()).message
            assertEquals(MatchPointMessageKind.RESPONSE, decoded.kind)
            assertEquals(41L, decoded.messageId)
            assertEquals(MatchPointTypes.PLAYBACK_INFO, decoded.type)
            assertEquals("ready", (decoded.payload as MatchPointPlaybackInfoResponse).infoMap()["state"])
            assertEquals(MatchPointRuntimeState.OPEN, runtime.snapshot().state)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun exactStreamBindingRejectsAnotherStreamAndClosesTheBoundRuntime() {
        val bound = newStream()
        val other = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(1),
            bound,
            RecordingBackend(),
            outboundSender = {},
        )
        try {
            runtime.onStreamOpened(bound)
            val failure = assertThrows(MatchPointRuntimeException::class.java) {
                runtime.onMessage(other, carrier(MatchPointMessage.oneWay(MatchPointStop)))
            }
            assertEquals(MatchPointRuntimeCloseReason.WRONG_STREAM, failure.reason)
            assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
            assertEquals(MatchPointRuntimeCloseReason.WRONG_STREAM, runtime.snapshot().terminalReason)
        } finally {
            runtime.close()
            other.close()
        }
    }

    @Test
    fun inboundReplyCapabilityCannotCrossSessionGeneration() {
        lateinit var firstRequest: MatchPointInboundRequest
        val firstStream = newStream()
        val secondStream = newStream()
        val first = MatchPointRuntime(
            MatchPointSessionIdentity.create(20),
            firstStream,
            object : MatchPointPlayerBackend {
                override fun onRequest(
                    session: MatchPointPlayerSession,
                    request: MatchPointInboundRequest,
                ) {
                    firstRequest = request
                }
            },
            outboundSender = {},
        )
        val second = MatchPointRuntime(
            MatchPointSessionIdentity.create(21),
            secondStream,
            RecordingBackend(),
            outboundSender = {},
        )
        try {
            first.onStreamOpened(firstStream)
            second.onStreamOpened(secondStream)
            first.onMessage(
                firstStream,
                carrier(MatchPointMessage.request(7, MatchPointPropertyRequest("rate"))),
            )

            val failure = assertThrows(MatchPointRuntimeException::class.java) {
                second.respondSuccess(firstRequest, MatchPointPropertyResponse(1.0))
            }
            assertEquals(MatchPointRuntimeCloseReason.WRONG_STREAM, failure.reason)
            assertEquals(MatchPointRuntimeState.OPEN, first.snapshot().state)
            assertEquals(MatchPointRuntimeState.FAILED, second.snapshot().state)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun openingTwiceFailsClosedAndNotifiesBackendOnce() {
        val backend = RecordingBackend()
        val stream = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(2),
            stream,
            backend,
            outboundSender = {},
        )
        try {
            runtime.onStreamOpened(stream)
            val failure = assertThrows(MatchPointRuntimeException::class.java) {
                runtime.onStreamOpened(stream)
            }
            assertEquals(MatchPointRuntimeCloseReason.OPEN_TWICE, failure.reason)
            assertEquals(1, backend.opened)
            assertTrue(backend.closedSignal.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(MatchPointRuntimeCloseReason.OPEN_TWICE), backend.closed)
            assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun inboundStreamingKeyRequestIsCorrelatedToOneTypedBackendResponse() {
        val sent = mutableListOf<RcsMessage>()
        val backend = object : MatchPointPlayerBackend {
            override fun onRequest(
                session: MatchPointPlayerSession,
                request: MatchPointInboundRequest,
            ) {
                assertTrue(request.message.payload is MatchPointStreamingKeyRequest)
                session.respondSuccess(
                    request,
                    MatchPointStreamingKeyResponse(byteArrayOf(1, 2)),
                )
            }
        }
        val stream = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(3),
            stream,
            backend,
            outboundSender = sent::add,
        )
        try {
            runtime.onStreamOpened(stream)
            runtime.onMessage(
                stream,
                carrier(
                    MatchPointMessage.request(
                        41,
                        MatchPointStreamingKeyRequest(mapOf("purpose" to "playback")),
                    ),
                ),
            )

            val response = MatchPointCarrierCodec.decode(sent.single()).message
            assertEquals(MatchPointMessageKind.RESPONSE, response.kind)
            assertEquals(41L, response.messageId)
            assertEquals(MatchPointTypes.STREAMING_KEY, response.type)
            assertEquals(0, runtime.snapshot().pendingInboundRequests)
            assertEquals(MatchPointRuntimeState.OPEN, runtime.snapshot().state)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun inboundStreamingKeyResponseIsWrongDirectionAndFailsClosed() {
        val stream = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(4),
            stream,
            RecordingBackend(),
            outboundSender = {},
        )
        runtime.onStreamOpened(stream)
        val failure = assertThrows(MatchPointRuntimeException::class.java) {
            runtime.onMessage(
                stream,
                carrier(
                    MatchPointMessage.response(
                        9,
                        MatchPointStreamingKeyResponse(byteArrayOf(7)),
                    ),
                ),
            )
        }
        assertEquals(MatchPointRuntimeCloseReason.WRONG_DIRECTION, failure.reason)
        assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
        runtime.close()
    }

    @Test
    fun closeCancelsPendingAndRejectsLateBackendReply() {
        lateinit var captured: MatchPointInboundRequest
        lateinit var backendSession: MatchPointPlayerSession
        val sent = mutableListOf<RcsMessage>()
        val backend = object : MatchPointPlayerBackend {
            override fun onRequest(
                session: MatchPointPlayerSession,
                request: MatchPointInboundRequest,
            ) {
                backendSession = session
                captured = request
            }
        }
        val stream = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(5),
            stream,
            backend,
            outboundSender = sent::add,
        )
        runtime.onStreamOpened(stream)
        runtime.onMessage(
            stream,
            carrier(MatchPointMessage.request(17, MatchPointPropertyRequest("duration"))),
        )
        assertEquals(1, runtime.snapshot().pendingInboundRequests)

        runtime.close()

        assertEquals(0, runtime.snapshot().pendingInboundRequests)
        assertEquals(MatchPointRuntimeState.CLOSED, runtime.snapshot().state)
        assertThrows(MatchPointRuntimeException::class.java) {
            backendSession.respondSuccess(captured, MatchPointPropertyResponse(12.0))
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun backendCanReenterDuringOpenWithoutRuntimeLockOrSendDeadlock() {
        val sent = mutableListOf<RcsMessage>()
        val backend = object : MatchPointPlayerBackend {
            override fun onOpened(session: MatchPointPlayerSession) {
                assertEquals(MatchPointRuntimeState.OPEN, session.snapshot().state)
                session.publishPlaybackState(MatchPointPlaybackState(MatchPointPlaybackStateName.PAUSED))
                session.publishNotification()
            }
        }
        val stream = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(6),
            stream,
            backend,
            outboundSender = sent::add,
        )
        try {
            runtime.onStreamOpened(stream)
            assertEquals(
                listOf(MatchPointTypes.PLAYBACK_STATE, MatchPointTypes.NOTIFICATION),
                sent.map { MatchPointCarrierCodec.decode(it).message.type },
            )
            assertEquals(MatchPointRuntimeState.OPEN, runtime.snapshot().state)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun terminalClosePublishesStateAndReturnsWhileBackendCallbackIsBlocked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val backend = object : MatchPointPlayerBackend {
            override fun onCommand(session: MatchPointPlayerSession, message: MatchPointMessage) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }

            override fun onClosed(
                identity: MatchPointSessionIdentity,
                reason: MatchPointRuntimeCloseReason,
            ) {
                closed.countDown()
            }
        }
        val stream = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(15),
            stream,
            backend,
            outboundSender = {},
        )
        runtime.onStreamOpened(stream)
        val receiveThread = Thread {
            runtime.onMessage(stream, carrier(MatchPointMessage.oneWay(MatchPointStop)))
        }
        receiveThread.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val closeThread = Thread(runtime::close)
        closeThread.start()
        closeThread.join(1_000)
        assertFalse("close waited for arbitrary backend code", closeThread.isAlive)
        assertEquals(MatchPointRuntimeState.CLOSED, runtime.snapshot().state)
        assertEquals(2, runtime.snapshot().terminalTasksStarted)
        assertFalse(closed.await(100, TimeUnit.MILLISECONDS))

        release.countDown()
        receiveThread.join(2_000)
        assertFalse(receiveThread.isAlive)
        assertTrue(closed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun closeFromBackendCallbackDefersOnClosedUntilCallbackReturns() {
        lateinit var runtime: MatchPointRuntime
        val events = mutableListOf<String>()
        val closed = CountDownLatch(1)
        val backend = object : MatchPointPlayerBackend {
            override fun onCommand(session: MatchPointPlayerSession, message: MatchPointMessage) {
                synchronized(events) { events += "command-begin" }
                runtime.close()
                synchronized(events) { events += "command-end" }
            }

            override fun onClosed(
                identity: MatchPointSessionIdentity,
                reason: MatchPointRuntimeCloseReason,
            ) {
                synchronized(events) { events += "closed" }
                closed.countDown()
            }
        }
        val stream = newStream()
        runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(16),
            stream,
            backend,
            outboundSender = {},
        )
        runtime.onStreamOpened(stream)
        runtime.onMessage(stream, carrier(MatchPointMessage.oneWay(MatchPointStop)))
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("command-begin", "command-end", "closed"), synchronized(events) { events.toList() })
        assertEquals(MatchPointRuntimeState.CLOSED, runtime.snapshot().state)
    }

    @Test
    fun transportCloseFailureIsRecordedWithoutPayloadOrFalseCompletionClaim() {
        val stream = newStream()
        val runtime = MatchPointRuntime(
            identity = MatchPointSessionIdentity.create(25),
            stream = stream,
            backend = RecordingBackend(),
            outboundSender = {},
            transportCloser = { throw IOException("private transport details") },
        )
        runtime.onStreamOpened(stream)
        runtime.close()

        val closeState = awaitTransportClose(runtime)
        val snapshot = runtime.snapshot()
        assertEquals(MatchPointRuntimeState.CLOSED, snapshot.state)
        assertEquals(MatchPointTransportCloseState.FAILED, closeState)
        assertTrue(snapshot.diagnostics.any { it.code == "transport_close_failed" })
        assertFalse(snapshot.diagnostics.toString().contains("private transport details"))
        stream.close()
    }

    @Test
    fun inboundReentryIsQueuedAndBounded() {
        lateinit var runtime: MatchPointRuntime
        lateinit var stream: RcsDataStream
        var callbacks = 0
        val backend = object : MatchPointPlayerBackend {
            override fun onCommand(session: MatchPointPlayerSession, message: MatchPointMessage) {
                if (callbacks++ == 0) {
                    runtime.onMessage(stream, carrier(MatchPointMessage.oneWay(MatchPointStop)))
                    runtime.onMessage(stream, carrier(MatchPointMessage.oneWay(MatchPointStop)))
                }
            }
        }
        stream = newStream()
        runtime = MatchPointRuntime(
            identity = MatchPointSessionIdentity.create(8),
            stream = stream,
            backend = backend,
            runtimeLimits = MatchPointRuntimeLimits(maximumInboundQueueMessages = 1),
            outboundSender = {},
        )
        try {
            assertThrows(MatchPointRuntimeException::class.java) {
                runtime.onStreamOpened(stream)
                runtime.onMessage(stream, carrier(MatchPointMessage.oneWay(MatchPointStop)))
            }
            assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
            assertEquals(
                MatchPointRuntimeCloseReason.INBOUND_QUEUE_LIMIT,
                runtime.snapshot().terminalReason,
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun outboundReentryIsQueuedAndBounded() {
        lateinit var runtime: MatchPointRuntime
        var sends = 0
        val stream = newStream()
        runtime = MatchPointRuntime(
            identity = MatchPointSessionIdentity.create(9),
            stream = stream,
            backend = RecordingBackend(),
            runtimeLimits = MatchPointRuntimeLimits(maximumOutboundQueueMessages = 1),
            outboundSender = {
                if (sends++ == 0) {
                    runtime.publishNotification()
                    runtime.publishNotification()
                }
            },
        )
        try {
            runtime.onStreamOpened(stream)
            assertThrows(MatchPointRuntimeException::class.java) {
                runtime.publishNotification()
            }
            assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
            assertEquals(
                MatchPointRuntimeCloseReason.OUTBOUND_QUEUE_LIMIT,
                runtime.snapshot().terminalReason,
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun unknownMessageAndWrongNotificationDirectionFailClosed() {
        val unknownStream = newStream()
        val unknownRuntime = MatchPointRuntime(
            MatchPointSessionIdentity.create(10),
            unknownStream,
            RecordingBackend(),
            outboundSender = {},
        )
        unknownRuntime.onStreamOpened(unknownStream)
        val unknownInner = BplistCodec.encode(
            mapOf("type" to "futureCommand", "opaque" to byteArrayOf(1, 2, 3)),
        )
        val unknownCarrier = RcsMessage.comm(
            BplistCodec.encode(mapOf("params" to mapOf("data" to unknownInner))),
        )
        val unknown = assertThrows(MatchPointRuntimeException::class.java) {
            unknownRuntime.onMessage(unknownStream, unknownCarrier)
        }
        assertEquals(MatchPointRuntimeCloseReason.UNKNOWN_MESSAGE, unknown.reason)
        assertEquals(MatchPointRuntimeState.FAILED, unknownRuntime.snapshot().state)

        val directionStream = newStream()
        val directionRuntime = MatchPointRuntime(
            MatchPointSessionIdentity.create(11),
            directionStream,
            RecordingBackend(),
            outboundSender = {},
        )
        directionRuntime.onStreamOpened(directionStream)
        val direction = assertThrows(MatchPointRuntimeException::class.java) {
            directionRuntime.onMessage(
                directionStream,
                carrier(MatchPointMessage.oneWay(MatchPointNotification)),
            )
        }
        assertEquals(MatchPointRuntimeCloseReason.WRONG_DIRECTION, direction.reason)
        assertEquals(MatchPointRuntimeState.FAILED, directionRuntime.snapshot().state)
        unknownRuntime.close()
        directionRuntime.close()
    }

    @Test
    fun nonCommTransportPackageIsRejectedBeforeBackendDispatch() {
        val backend = RecordingBackend()
        val stream = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(24),
            stream,
            backend,
            outboundSender = {},
        )
        runtime.onStreamOpened(stream)
        val validBody = MatchPointCarrierCodec.encode(
            MatchPointMessage.oneWay(MatchPointStop),
        ).rcsBody
        val failure = assertThrows(MatchPointRuntimeException::class.java) {
            runtime.onMessage(stream, RcsMessage(0x6e6f7065, validBody))
        }
        assertEquals(MatchPointRuntimeCloseReason.INVALID_CARRIER, failure.reason)
        assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
        assertTrue(backend.commands.isEmpty())
        runtime.close()
    }

    @Test
    fun picAuthorizationRequestIsExplicitlyOutsideTheDiagnosticRuntime() {
        val backend = RecordingBackend()
        val stream = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(17),
            stream,
            backend,
            outboundSender = {},
        )
        runtime.onStreamOpened(stream)
        val failure = assertThrows(MatchPointRuntimeException::class.java) {
            runtime.onMessage(
                stream,
                carrier(
                    MatchPointMessage.request(
                        1,
                        MatchPointAuthorizeItem(
                            MatchPointItemReference("item-without-pic-runtime"),
                            byteArrayOf(1, 2, 3),
                        ),
                    ),
                ),
            )
        }
        assertEquals(MatchPointRuntimeCloseReason.UNSUPPORTED_AUTHORIZATION, failure.reason)
        assertTrue(backend.requests.isEmpty())
        assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
        runtime.close()
    }

    @Test
    fun picIssuerMustBindExactSessionIdentityBeforeRuntimeClaimsIt() {
        val identity = MatchPointSessionIdentity.create(171)
        val foreign = MatchPointSessionIdentity.create(171)
        val stream = newStream()
        try {
            val issuer = object : MatchPointPicAuthorizer {
                override val identity = foreign
                override fun authorize(
                    item: MatchPointItemReference,
                    picRequest: ByteArray,
                ) = MatchPointAuthorizeItemResponse(picData = byteArrayOf(1))
            }
            assertThrows(IllegalArgumentException::class.java) {
                MatchPointRuntime(identity, stream, RecordingBackend(), picAuthorizer = issuer)
            }
            // The rejected issuer did not consume this generation's single runtime claim.
            MatchPointRuntime(identity, stream, RecordingBackend(), outboundSender = {}).close()
        } finally {
            stream.close()
        }
    }

    @Test
    fun explicitSessionBoundPicIssuerRepliesOnlyAfterAuthorizationAndCorrelatesRequest() {
        val identity = MatchPointSessionIdentity.create(172)
        val backend = RecordingBackend()
        val sent = mutableListOf<RcsMessage>()
        val requests = mutableListOf<Pair<String, ByteArray>>()
        lateinit var borrowedPicRequest: ByteArray
        val issuer = object : MatchPointPicAuthorizer {
            override val identity = identity
            override fun authorize(
                item: MatchPointItemReference,
                picRequest: ByteArray,
            ): MatchPointAuthorizeItemResponse {
                borrowedPicRequest = picRequest
                requests += item.uuid to picRequest.copyOf()
                return MatchPointAuthorizeItemResponse(picData = byteArrayOf(9, 8, 7))
            }
        }
        val stream = newStream()
        val runtime = MatchPointRuntime(
            identity,
            stream,
            backend,
            picAuthorizer = issuer,
            outboundSender = sent::add,
        )
        try {
            runtime.onStreamOpened(stream)
            runtime.onMessage(
                stream,
                carrier(MatchPointMessage.request(
                    81,
                    MatchPointAuthorizeItem(
                        MatchPointItemReference("pic-item"),
                        byteArrayOf(1, 2, 3),
                    ),
                )),
            )
            assertEquals(1, requests.size)
            assertEquals("pic-item", requests.single().first)
            assertTrue(requests.single().second.contentEquals(byteArrayOf(1, 2, 3)))
            assertTrue("borrowed PIC bytes must be wiped", borrowedPicRequest.all { it == 0.toByte() })
            assertTrue(backend.requests.isEmpty())
            val response = MatchPointCarrierCodec.decode(sent.single()).message
            assertEquals(81L, response.messageId)
            assertEquals(MatchPointTypes.AUTHORIZE_ITEM, response.type)
            assertTrue(
                (response.payload as MatchPointAuthorizeItemResponse).picData!!
                    .contentEquals(byteArrayOf(9, 8, 7)),
            )
            assertEquals(0, runtime.snapshot().pendingInboundRequests)
            assertEquals(MatchPointRuntimeState.OPEN, runtime.snapshot().state)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun failedPicAuthorizationClosesRuntimeWithoutSendingAClaimOfSuccess() {
        val identity = MatchPointSessionIdentity.create(173)
        val sent = mutableListOf<RcsMessage>()
        val issuer = object : MatchPointPicAuthorizer {
            override val identity = identity
            override fun authorize(
                item: MatchPointItemReference,
                picRequest: ByteArray,
            ): MatchPointAuthorizeItemResponse = error("private PIC failure")
        }
        val stream = newStream()
        val runtime = MatchPointRuntime(
            identity,
            stream,
            RecordingBackend(),
            picAuthorizer = issuer,
            outboundSender = sent::add,
        )
        try {
            runtime.onStreamOpened(stream)
            val failure = assertThrows(MatchPointRuntimeException::class.java) {
                runtime.onMessage(
                    stream,
                    carrier(MatchPointMessage.request(
                        82,
                        MatchPointAuthorizeItem(
                            MatchPointItemReference("pic-item"),
                            byteArrayOf(1),
                        ),
                    )),
                )
            }
            assertEquals(MatchPointRuntimeCloseReason.BACKEND_FAILED, failure.reason)
            assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
            assertTrue(sent.isEmpty())
            assertTrue(runtime.snapshot().diagnostics.none { it.toString().contains("private PIC") })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun picIssuerRebindingDuringCallbackCannotAuthorizeTheOldSession() {
        val identity = MatchPointSessionIdentity.create(176)
        val foreign = MatchPointSessionIdentity.create(176)
        val sent = mutableListOf<RcsMessage>()
        val issuer = object : MatchPointPicAuthorizer {
            override var identity = identity
            override fun authorize(
                item: MatchPointItemReference,
                picRequest: ByteArray,
            ): MatchPointAuthorizeItemResponse {
                this.identity = foreign
                return MatchPointAuthorizeItemResponse(picData = byteArrayOf(1))
            }
        }
        val stream = newStream()
        val runtime = MatchPointRuntime(
            identity,
            stream,
            RecordingBackend(),
            picAuthorizer = issuer,
            outboundSender = sent::add,
        )
        try {
            runtime.onStreamOpened(stream)
            val failure = assertThrows(MatchPointRuntimeException::class.java) {
                runtime.onMessage(
                    stream,
                    carrier(MatchPointMessage.request(
                        85,
                        MatchPointAuthorizeItem(
                            MatchPointItemReference("pic-item"),
                            byteArrayOf(1),
                        ),
                    )),
                )
            }
            assertEquals(MatchPointRuntimeCloseReason.BACKEND_FAILED, failure.reason)
            assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
            assertTrue(sent.isEmpty())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun closingDuringBlockedPicIssuerCannotSendALateAuthorization() {
        val identity = MatchPointSessionIdentity.create(174)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sent = Collections.synchronizedList(mutableListOf<RcsMessage>())
        val issuer = object : MatchPointPicAuthorizer {
            override val identity = identity
            override fun authorize(
                item: MatchPointItemReference,
                picRequest: ByteArray,
            ): MatchPointAuthorizeItemResponse {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return MatchPointAuthorizeItemResponse(picData = byteArrayOf(1))
            }
        }
        val stream = newStream()
        val runtime = MatchPointRuntime(
            identity,
            stream,
            RecordingBackend(),
            picAuthorizer = issuer,
            outboundSender = sent::add,
        )
        val receiveFailure = AtomicReference<Throwable?>()
        runtime.onStreamOpened(stream)
        val receiver = Thread {
            try {
                runtime.onMessage(
                    stream,
                    carrier(MatchPointMessage.request(
                        83,
                        MatchPointAuthorizeItem(
                            MatchPointItemReference("pic-item"),
                            byteArrayOf(1),
                        ),
                    )),
                )
            } catch (failure: Throwable) {
                receiveFailure.set(failure)
            }
        }
        try {
            receiver.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            runtime.close()
            release.countDown()
            receiver.join(5_000)
            assertFalse(receiver.isAlive)
            assertTrue(receiveFailure.get() is MatchPointRuntimeException)
            assertEquals(MatchPointRuntimeState.CLOSED, runtime.snapshot().state)
            assertTrue(sent.isEmpty())
        } finally {
            release.countDown()
            runtime.close()
        }
    }

    @Test
    fun closedRuntimeRejectsLateCarrierBeforeDecodingIt() {
        val stream = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(18),
            stream,
            RecordingBackend(),
            outboundSender = {},
        )
        runtime.onStreamOpened(stream)
        runtime.close()
        val failure = assertThrows(MatchPointRuntimeException::class.java) {
            runtime.onMessage(stream, RcsMessage(0x12345678, byteArrayOf(1, 2, 3)))
        }
        assertEquals(MatchPointRuntimeCloseReason.LOCAL_CLOSE, failure.reason)
        assertEquals(MatchPointRuntimeCloseReason.LOCAL_CLOSE, runtime.snapshot().terminalReason)
    }

    @Test
    fun duplicateInboundRequestFailsClosedAndCannotReachBackendTwice() {
        val backend = RecordingBackend()
        val stream = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(12),
            stream,
            backend,
            outboundSender = {},
        )
        try {
            runtime.onStreamOpened(stream)
            val request = carrier(MatchPointMessage.request(99, MatchPointPlaybackInfoRequest))
            runtime.onMessage(stream, request)
            val failure = assertThrows(MatchPointRuntimeException::class.java) {
                runtime.onMessage(stream, request)
            }
            assertEquals(MatchPointRuntimeCloseReason.DUPLICATE_REQUEST, failure.reason)
            assertEquals(1, backend.requests.size)
            assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun inboundRequestIdBudgetNeverEvictsReplayProtection() {
        val backend = object : MatchPointPlayerBackend {
            override fun onRequest(
                session: MatchPointPlayerSession,
                request: MatchPointInboundRequest,
            ) {
                session.respondSuccess(request, MatchPointPropertyResponse(1.0))
            }
        }
        val stream = newStream()
        val runtime = MatchPointRuntime(
            identity = MatchPointSessionIdentity.create(22),
            stream = stream,
            backend = backend,
            runtimeLimits = MatchPointRuntimeLimits(
                maximumPendingInboundRequests = 1,
                maximumInboundRequestIds = 1,
            ),
            outboundSender = {},
        )
        try {
            runtime.onStreamOpened(stream)
            runtime.onMessage(
                stream,
                carrier(MatchPointMessage.request(1, MatchPointPropertyRequest("rate"))),
            )
            assertEquals(0, runtime.snapshot().pendingInboundRequests)
            assertEquals(1, runtime.snapshot().observedInboundRequestIds)

            val failure = assertThrows(MatchPointRuntimeException::class.java) {
                runtime.onMessage(
                    stream,
                    carrier(MatchPointMessage.request(2, MatchPointPropertyRequest("duration"))),
                )
            }
            assertEquals(MatchPointRuntimeCloseReason.INBOUND_REQUEST_ID_LIMIT, failure.reason)
            assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun diagnosticsAreBoundedAndDoNotExposeIdentifiersOrUrls() {
        val secretItem = "item-secret-123"
        val secretUrl = "https://private.example.test/$secretItem.m3u8"
        val stream = newStream()
        val runtime = MatchPointRuntime(
            identity = MatchPointSessionIdentity.create(13),
            stream = stream,
            backend = RecordingBackend(),
            runtimeLimits = MatchPointRuntimeLimits(maximumDiagnostics = 2),
            outboundSender = {},
        )
        try {
            runtime.onStreamOpened(stream)
            runtime.onMessage(
                stream,
                carrier(
                    MatchPointMessage.oneWay(
                        MatchPointInsertPlayQueueItem(
                            MatchPointQueueItem(secretItem, contentLocation = secretUrl),
                        ),
                    ),
                ),
            )
            runtime.publishNotification()
            val snapshot = runtime.snapshot()
            val rendered = snapshot.diagnostics.toString() + runtime.identity.toString()
            assertEquals(2, snapshot.diagnostics.size)
            assertTrue(snapshot.droppedDiagnostics >= 1)
            assertFalse(rendered.contains(secretItem))
            assertFalse(rendered.contains(secretUrl))
            assertTrue(rendered.contains("identity=<redacted>"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun inboundRequestTimeoutFailsClosed() {
        var now = 5_000L
        val stream = newStream()
        val runtime = MatchPointRuntime(
            identity = MatchPointSessionIdentity.create(14),
            stream = stream,
            backend = RecordingBackend(),
            runtimeLimits = MatchPointRuntimeLimits(
                inboundRequestTimeoutMillis = 1,
                timeoutPollMillis = 300_000,
            ),
            nowNanos = { now },
            outboundSender = {},
        )
        try {
            runtime.onStreamOpened(stream)
            runtime.onMessage(
                stream,
                carrier(MatchPointMessage.request(23, MatchPointPropertyRequest("rate"))),
            )
            now += 1_000_001L
            val failure = assertThrows(MatchPointRuntimeException::class.java) {
                runtime.expireTimedOutRequests()
            }
            assertEquals(MatchPointRuntimeCloseReason.INBOUND_REQUEST_TIMEOUT, failure.reason)
            assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun lateResponseIsRejectedAtTheSendFenceEvenBeforeTimeoutPoll() {
        var now = 8_000L
        lateinit var capturedRequest: MatchPointInboundRequest
        lateinit var capturedSession: MatchPointPlayerSession
        val sent = mutableListOf<RcsMessage>()
        val backend = object : MatchPointPlayerBackend {
            override fun onRequest(
                session: MatchPointPlayerSession,
                request: MatchPointInboundRequest,
            ) {
                capturedSession = session
                capturedRequest = request
            }
        }
        val stream = newStream()
        val runtime = MatchPointRuntime(
            MatchPointSessionIdentity.create(175),
            stream,
            backend,
            runtimeLimits = MatchPointRuntimeLimits(
                inboundRequestTimeoutMillis = 1,
                timeoutPollMillis = 300_000,
            ),
            nowNanos = { now },
            outboundSender = sent::add,
        )
        try {
            runtime.onStreamOpened(stream)
            runtime.onMessage(
                stream,
                carrier(MatchPointMessage.request(84, MatchPointPlaybackInfoRequest)),
            )
            now += 1_000_000L
            val failure = assertThrows(MatchPointRuntimeException::class.java) {
                capturedSession.respondSuccess(
                    capturedRequest,
                    MatchPointPlaybackInfoResponse(mapOf("rate" to 1.0)),
                )
            }
            assertEquals(MatchPointRuntimeCloseReason.INBOUND_REQUEST_TIMEOUT, failure.reason)
            assertEquals(MatchPointRuntimeState.FAILED, runtime.snapshot().state)
            assertTrue(sent.isEmpty())
        } finally {
            runtime.close()
        }
    }

    private class RecordingBackend : MatchPointPlayerBackend {
        var opened = 0
        val requests = mutableListOf<MatchPointInboundRequest>()
        val commands = mutableListOf<MatchPointMessage>()
        val closed = mutableListOf<MatchPointRuntimeCloseReason>()
        val closedSignal = CountDownLatch(1)

        override fun onOpened(session: MatchPointPlayerSession) {
            opened++
        }

        override fun onRequest(
            session: MatchPointPlayerSession,
            request: MatchPointInboundRequest,
        ) {
            requests += request
        }

        override fun onCommand(session: MatchPointPlayerSession, message: MatchPointMessage) {
            commands += message
        }

        override fun onClosed(
            identity: MatchPointSessionIdentity,
            reason: MatchPointRuntimeCloseReason,
        ) {
            closed += reason
            closedSignal.countDown()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun MatchPointPlaybackInfoResponse.infoMap(): Map<String, Any> =
        info as Map<String, Any>

    private fun carrier(message: MatchPointMessage): RcsMessage =
        MatchPointCarrierCodec.encode(message).asRcsMessage()

    private fun awaitTransportClose(runtime: MatchPointRuntime): MatchPointTransportCloseState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            val state = runtime.snapshot().transportCloseState
            if (state != MatchPointTransportCloseState.PENDING) return state
            if (System.nanoTime() - deadline >= 0L) return state
            Thread.sleep(1)
        }
    }

    private fun newStream(): RcsDataStream = RcsDataStream.open(
        clientType = RcsClientTypes.CAR_PLAY_MATCH_POINT,
        readKey = ByteArray(32) { 1 },
        writeKey = ByteArray(32) { 2 },
        bindAddress = InetAddress.getLoopbackAddress(),
    )
}
