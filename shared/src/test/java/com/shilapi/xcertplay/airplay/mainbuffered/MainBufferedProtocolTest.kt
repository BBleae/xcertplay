package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayUltraConfiguration
import java.lang.reflect.InvocationTargetException
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBufferedProtocolTest {
    @Test
    fun copiedDataIsWipedWhenSnapshotRegistrationItselfFails() {
        val source = ByteArray(32) { (it + 1).toByte() }
        val copierClass = checkNotNull(MainBufferedSetupRequest::class.java.classLoader).loadClass(
            "com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedWireSnapshot" + '$' + "Copier",
        )
        val constructor = copierClass.getDeclaredConstructor(Function1::class.java)
            .apply { isAccessible = true }
        val copier = constructor.newInstance(null)
        var strandedCopy: ByteArray? = null
        val failedRegistry = object : ArrayList<ByteArray>() {
            override fun add(element: ByteArray): Boolean {
                strandedCopy = element
                throw OutOfMemoryError("injected copied-Data registration failure")
            }
        }
        copierClass.getDeclaredField("copiedData").apply { isAccessible = true }
            .set(copier, failedRegistry)
        val copyRoot = copierClass.getDeclaredMethod(
            "copyMapRoot", Any::class.java, String::class.java,
        ).apply { isAccessible = true }
        val failure = assertThrows(InvocationTargetException::class.java) {
            copyRoot.invoke(copier, linkedMapOf("shk" to source), "type-103 SETUP")
        }
        assertTrue(failure.cause is OutOfMemoryError)
        assertTrue(checkNotNull(strandedCopy).all { it == 0.toByte() })
        assertTrue(source.any { it != 0.toByte() })
    }

    @Test
    fun callbackFailureAfterRegistrationWipesCopiedDataAndLeavesInputUnchanged() {
        val source = ByteArray(32) { (it + 2).toByte() }
        var copied: ByteArray? = null
        val request = validSetup() + mapOf("shk" to source)
        assertThrows(IllegalStateException::class.java) {
            MainBufferedV27RequestTicket.freeze(request) { data ->
                copied = data
                throw IllegalStateException("injected observer failure")
            }
        }
        assertTrue(checkNotNull(copied).all { it == 0.toByte() })
        assertTrue(source.any { it != 0.toByte() })
    }

    @Test
    fun ticketOwnsEveryOpaqueDataCopyWhenShkAliasesApapMetadata() {
        val callerRaw = ByteArray(32) { (it + 1).toByte() }
        val observed = ArrayList<ByteArray>()
        val request = validSetup() + mapOf(
            "shk" to callerRaw,
            "streamConnections" to mapOf(
                "streamConnectionTypeAPAP" to mapOf("opaqueFutureData" to callerRaw),
                "streamConnectionTypeMediaDataControl" to mapOf(
                    "streamConnectionKeyEncryptionSeed" to 19L,
                ),
            ),
        )
        val ticket = MainBufferedV27RequestTicket.freeze(request) { observed += it }
        assertTrue(observed.size >= 4)
        assertTrue(observed.all { it !== callerRaw && it.any { byte -> byte != 0.toByte() } })
        // These defensive getters and wire snapshots must use the same ticket-scoped owner.
        val optional = ticket.request.optionalValues
        val wire = ticket.request.toWireMap()
        assertTrue((optional["streamConnections"] as Map<*, *>).isNotEmpty())
        assertTrue((wire["streamConnections"] as Map<*, *>).isNotEmpty())
        ticket.close()
        ticket.close()
        assertTrue(observed.all { copy -> copy.all { it == 0.toByte() } })
        assertTrue(callerRaw.any { it != 0.toByte() })
        assertThrows(IllegalStateException::class.java) { ticket.request.optionalValues }
        assertThrows(IllegalStateException::class.java) { ticket.request.toWireMap() }
    }

    @Test
    fun ticketPostSnapshotParseFailureWipesAllAliasCopiesWithoutTouchingCallerData() {
        val callerRaw = ByteArray(32) { (it + 2).toByte() }
        val observed = ArrayList<ByteArray>()
        val bad = validSetup() + mapOf(
            "shk" to callerRaw,
            "audioFormatIndex" to 17L,
            "streamConnections" to mapOf(
                "streamConnectionTypeAPAP" to mapOf("opaqueFutureData" to callerRaw),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedV27RequestTicket.freeze(bad) { observed += it }
        }
        assertTrue(observed.size >= 2)
        assertTrue(observed.all { copy -> copy.all { it == 0.toByte() } })
        assertTrue(callerRaw.any { it != 0.toByte() })
    }

    @Test
    fun malformedEntryAfterShkWipesEveryPartialSnapshotDataCopy() {
        val raw = ByteArray(32) { (it + 1).toByte() }
        val copierClass = checkNotNull(MainBufferedSetupRequest::class.java.classLoader).loadClass(
            "com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedWireSnapshot" + '$' + "Copier",
        )
        val copier = copierClass.getDeclaredConstructor().apply { isAccessible = true }
            .newInstance()
        val copyRoot = copierClass.getDeclaredMethod(
            "copyMapRoot", Any::class.java, String::class.java,
        ).apply { isAccessible = true }
        val malformed = linkedMapOf<String, Any?>("shk" to raw, "laterBad" to Any())
        val failure = assertThrows(InvocationTargetException::class.java) {
            copyRoot.invoke(copier, malformed, "malformed type-103 SETUP")
        }
        assertTrue(failure.cause is IllegalArgumentException)
        @Suppress("UNCHECKED_CAST")
        val copies = copierClass.getDeclaredField("copiedData").apply { isAccessible = true }
            .get(copier) as List<ByteArray>
        assertEquals(1, copies.size)
        assertTrue(copies.single().all { it == 0.toByte() })
        assertTrue(raw.any { it != 0.toByte() })
    }

    @Test
    fun confirmedFormatBitsAndHighUInt64RoundTrip() {
        assertEquals(0x4L, MainBufferedAudioFormatBits.PCM_8000_16_MONO)
        assertEquals(0x40000L, MainBufferedAudioFormatBits.ALAC_44100_16_STEREO)
        assertEquals(0x400000L, MainBufferedAudioFormatBits.AAC_LC_44100_STEREO)
        assertEquals(0x40000000L, MainBufferedAudioFormatBits.OPUS_48000_MONO)
        assertTrue(MainBufferedAudioFormatBits.isSingleBit(Long.MIN_VALUE))
        assertFalse(MainBufferedAudioFormatBits.isSingleBit(0L))
        assertFalse(MainBufferedAudioFormatBits.isSingleBit(0x600000L))
        assertEquals(63, MainBufferedAudioFormatBits.indexOfSingleBit(Long.MIN_VALUE))

        val advertisement = MainBufferedAudioFormatAdvertisement(Long.MIN_VALUE)
        assertEquals(BigInteger.ONE.shiftLeft(63), advertisement.toWireMap()["audioOutputFormats"])
        val reparsed = MainBufferedAudioFormatAdvertisement.fromWireMap(advertisement.toWireMap())
        assertEquals(Long.MIN_VALUE, reparsed.audioOutputFormats)
    }

    @Test
    fun extendedFormatsAreBoundedOpaqueAndDefensivelyCopied() {
        val bytes = byteArrayOf(1, 2, 3)
        val nested = linkedMapOf<String, Any?>(
            "codec" to "future",
            "descriptor" to bytes,
            "attributes" to arrayListOf<Any?>(1L, true),
        )
        val source = arrayListOf<Any?>(nested)
        val advertisement = MainBufferedAudioFormatAdvertisement(
            audioOutputFormats = MainBufferedAudioFormatBits.ALAC_44100_16_STEREO,
            audioOutputFormatsExtended = source,
        )

        bytes[0] = 99
        nested["codec"] = "mutated"
        source.clear()

        val first = advertisement.toWireMap()["audioOutputFormatsExtended"] as List<*>
        val firstMap = first.single() as Map<*, *>
        assertEquals("future", firstMap["codec"])
        val firstBytes = firstMap["descriptor"] as ByteArray
        assertArrayEquals(byteArrayOf(1, 2, 3), firstBytes)
        firstBytes[0] = 42

        val second = advertisement.audioOutputFormatsExtended!!
        val secondBytes = (second.single() as Map<*, *>)["descriptor"] as ByteArray
        assertNotSame(firstBytes, secondBytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), secondBytes)

        val cycle = arrayListOf<Any?>()
        cycle.add(cycle)
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedAudioFormatAdvertisement(1L, cycle)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedAudioFormatAdvertisement(1L, listOf(ByteArray(512 * 1024 + 1)))
        }
    }

    @Test
    fun opaqueSnapshotsNeverTrustHostileCollectionSize() {
        val mapBacking = linkedMapOf<String, Any?>("codec" to "future")
        val throwingMap = object : Map<String, Any?> by mapBacking {
            override val size: Int
                get() = throw AssertionError("map.size must not be read")
        }
        val listBacking = listOf<Any?>(throwingMap)
        val lyingList = object : List<Any?> by listBacking {
            override val size: Int
                get() = Int.MAX_VALUE
        }

        val first = MainBufferedAudioFormatAdvertisement(1L, lyingList)
            .audioOutputFormatsExtended!!
        assertEquals("future", (first.single() as Map<*, *>)["codec"])

        val lyingMap = object : Map<String, Any?> by mapBacking {
            override val size: Int
                get() = Int.MAX_VALUE
        }
        val throwingList = object : List<Any?> by listOf<Any?>(lyingMap) {
            override val size: Int
                get() = throw AssertionError("list.size must not be read")
        }
        val second = MainBufferedAudioFormatAdvertisement(1L, throwingList)
            .audioOutputFormatsExtended!!
        assertEquals("future", (second.single() as Map<*, *>)["codec"])
    }

    @Test
    fun setupParsesExactRequiredAndConfirmedOptionalFields() {
        val sharedKey = byteArrayOf(7, 8, 9)
        val raw = validSetup().toMutableMap().apply {
            this["streamConnectionID"] = UINT64_MAX
            this["ct"] = "alac"
            this["audioSessionID"] = BigInteger.ONE.shiftLeft(63)
            this["audioSessionCategory"] = "media"
            this["audioSessionMode"] = "default"
            this["shk"] = sharedKey
            this["soundCheckMediaKind"] = mapOf("kind" to 3L)
            this["clientID"] = "client-1"
            this["multicastGroupInfo"] = mapOf("group" to listOf(1L, 2L))
            this["streamConnections"] = mapOf("primary" to byteArrayOf(4, 5))
        }

        val parsed = MainBufferedSetupRequest.parse(raw)
        sharedKey[0] = 0
        assertEquals(-1L, parsed.streamConnectionId)
        assertEquals("alac", parsed.contentTypeFourCcOrNull())
        assertEquals(352L, parsed.samplesPerFrame)
        assertEquals(18L, parsed.audioFormatIndex)
        assertTrue(parsed.isMedia)
        assertTrue(parsed.packetFormatApap)
        assertArrayEquals(byteArrayOf(7, 8, 9), parsed.optionalValues["shk"] as ByteArray)
        assertEquals(UINT64_MAX, parsed.toWireMap()["streamConnectionID"])

        val leaked = parsed.optionalValues["shk"] as ByteArray
        leaked[0] = 99
        assertArrayEquals(byteArrayOf(7, 8, 9), parsed.optionalValues["shk"] as ByteArray)
    }

    @Test
    fun setupRejectsMissingUnknownContradictoryAndInventedFields() {
        assertSetupRejected(validSetup() - "supportsDynamicStreamID")
        assertSetupRejected(validSetup() + ("unknown" to true))
        assertSetupRejected(validSetup() + ("audioMode" to "not-confirmed"))
        assertSetupRejected(validSetup() + ("type" to 100L))
        assertSetupRejected(validSetup() + ("isMedia" to false))
        assertSetupRejected(validSetup() + ("audioType" to "default"))
        assertSetupRejected(validSetup() + ("spf" to 0L))
        assertSetupRejected(validSetup() + ("audioFormat" to 0xC0000L))
        assertSetupRejected(validSetup() + ("audioFormatIndex" to 19L))
        assertSetupRejected(validSetup() + ("multicastGroupInfo" to emptyList<Any?>()))
        assertSetupRejected(validSetup() + ("audioSessionID" to 1.5))
    }

    @Test
    fun legacyResponseIsStrictAndExplicitlySeparate() {
        val response = ExperimentalLegacyMainBufferedSetupResponse.parse(
            mapOf(
                "type" to 103L,
                "streamID" to 0xffff_ffffL,
                "audioBufferSize" to 8L * 1024 * 1024,
                "dataPort" to 65535L,
            ),
        )
        assertEquals(0xffff_ffffL, response.streamId)
        assertEquals(103L, response.toWireMap()["type"])
        assertThrows(IllegalArgumentException::class.java) {
            ExperimentalLegacyMainBufferedSetupResponse.parse(
                response.toWireMap() + ("controlPort" to 7000L),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExperimentalLegacyMainBufferedSetupResponse(1L, 1L, 0)
        }
    }

    @Test
    fun rateCommandsEnforceZeroOrOneAndAtomicAnchorGroups() {
        val stopped = MainBufferedControlParser.parse("srat", mapOf("rate" to 0L))
            as MainBufferedSetRateAndAnchor
        assertEquals(0, stopped.rate)
        assertNull(stopped.anchor)

        val playing = MainBufferedControlParser.parse("srat", sratPlaying())
            as MainBufferedSetRateAndAnchor
        assertEquals(1, playing.rate)
        assertEquals(90_000L, playing.anchor!!.point.mediaTimeScale)
        assertEquals(-1L, playing.anchor!!.networkTime!!.fraction)
        assertEquals(0xffff_ffffL, playing.anchor!!.networkTime!!.flags)

        assertCommandRejected("srat", mapOf("rate" to 2L))
        assertCommandRejected("srat", mapOf("rate" to 0L, "rtpTime" to 1L))
        assertCommandRejected("srat", sratPlaying() - "firstAudibleMediaTimeScale")
        assertCommandRejected("srat", sratPlaying() + ("networkTimeFlags" to UINT32_OVERFLOW))

        val start = MainBufferedControlParser.parse(
            "strt",
            mapOf(
                "streamID" to 7L,
                "rate" to 1L,
                "rtpTime" to 0xffff_ffffL,
                "mediaTimeValue" to Long.MIN_VALUE,
                "mediaTimeScale" to Int.MAX_VALUE.toLong(),
            ),
        ) as MainBufferedSetRateReturnAnchor
        assertEquals(7L, start.streamId)
        assertEquals(Long.MIN_VALUE, start.point!!.mediaTimeValue)
        assertCommandRejected("strt", mapOf("rate" to 1L, "rtpTime" to 1L))
        assertCommandRejected(
            "strt",
            mapOf("rate" to 0L, "rtpTime" to 1L, "mediaTimeValue" to 1L, "mediaTimeScale" to 1L),
        )
    }

    @Test
    fun flushRequiresUntilQuartetAndAllOrNothingFromQuartet() {
        val valid = flushParams().toMutableMap().apply {
            this["streamID"] = 9L
            this["flushFromSeq"] = 0L
            this["flushFromTS"] = 0L
            this["flushFromMediaTimeValue"] = -1L
            this["flushFromMediaTimeScale"] = 1L
        }
        val command = MainBufferedControlParser.parse("fshb", valid) as MainBufferedFlush
        assertEquals(9L, command.streamId)
        assertEquals(0L, command.from!!.sequence)
        assertEquals(0xffff_ffffL, command.until.sequence)
        assertEquals(0xffff_ffffL, command.until.timestamp)

        assertCommandRejected("fshb", flushParams() - "flushUntilTS")
        assertCommandRejected("fshb", flushParams() + ("flushUntilSeq" to UINT32_OVERFLOW))
        assertCommandRejected("fshb", flushParams() + ("flushUntilTS" to UINT32_OVERFLOW))
        assertCommandRejected("fshb", flushParams() + ("flushUntilMediaTimeScale" to 0L))
        assertCommandRejected("fshb", flushParams() + ("flushFromSeq" to 1L))
    }

    @Test
    fun remainingTagsAcceptOnlyTheirConfirmedParameters() {
        assertEquals(
            0.25,
            (MainBufferedControlParser.parse(
                "apvf",
                mapOf("fadeType" to 1L, "fadeDuration" to 0.25),
            ) as MainBufferedAudioPlaybackVolumeFade).fadeDuration,
            0.0,
        )
        assertTrue(
            (MainBufferedControlParser.parse(
                "loud",
                mapOf("loudnessNormalizationEnabled" to true),
            ) as MainBufferedLoudnessNormalization).enabled,
        )
        assertFalse(
            (MainBufferedControlParser.parse(
                "mata",
                mapOf("matAtmosPlaybackEnabled" to false),
            ) as MainBufferedMatAtmosPlayback).enabled,
        )
        assertEquals(
            -1L,
            (MainBufferedControlParser.parse(
                "upck",
                mapOf("cryptorID" to UINT64_MAX, "pkdEncryptionKey" to byteArrayOf(1)),
            ) as MainBufferedCryptorKey).cryptorId,
        )
        assertEquals(
            listOf(1L, 3L),
            (MainBufferedControlParser.parse(
                "uppr",
                mapOf("appCertificate" to byteArrayOf(1), "protocolVersionList" to listOf(1L, 3L)),
            ) as MainBufferedProtocolUpdate).protocolVersionList,
        )
        assertCommandRejected("loud", mapOf("loudnessNormalizationEnabled" to 1L))
        assertCommandRejected("mata", mapOf("matAtmosPlaybackEnabled" to true, "status" to 0L))
        assertCommandRejected("uppr", mapOf("appCertificate" to byteArrayOf(1), "protocolVersionList" to emptyList<Long>()))
        assertCommandRejected("unknown", emptyMap<String, Any?>())
    }

    @Test
    fun responseSchemasAreDirectionalAndGrouped() {
        val anchor = currentAnchorResponse(status = 0)
        val response = MainBufferedControlResponseParser.parse("strt", anchor)
            as MainBufferedCurrentAnchorResponse
        assertEquals(0xffff_ffffL, response.anchor!!.point.rtpTime)
        assertEquals(MainBufferedControlTag.SET_RATE_RETURN_ANCHOR, response.tag)

        val failed = MainBufferedControlResponseParser.parse("anch", mapOf("status" to -1L))
            as MainBufferedCurrentAnchorResponse
        assertEquals(-1, failed.status)
        assertNull(failed.anchor)
        assertResponseRejected("anch", anchor - "mediaTimeScale")
        assertResponseRejected("anch", mapOf("status" to 0L))
        assertResponseRejected("anch", currentAnchorResponse(status = -1))

        val setRate = MainBufferedControlResponseParser.parse(
            "srat",
            mapOf(
                "firstAvailableSampleTS" to 10L,
                "firstAvailableMediaTimeValue" to 20L,
                "firstAvailableMediaTimeScale" to 48_000L,
                "renderStartNetworkTimeSecs" to -2L,
                "renderStartNetworkTimeFrac" to UINT64_MAX,
                "renderStartNetworkTimeTimelineID" to 3L,
                "renderStartNetworkTimeFlags" to 4L,
                "status" to 0L,
            ),
        ) as MainBufferedSetRateResponse
        assertEquals(10L, setRate.firstAvailable!!.rtpTime)
        assertEquals(-2, setRate.renderStartNetworkTime!!.seconds)
        assertResponseRejected(
            "srat",
            mapOf("firstAvailableSampleTS" to 1L, "status" to 0L),
        )

        assertTrue(
            MainBufferedControlResponseParser.parse("fshb", emptyMap<String, Any?>()) is
                MainBufferedEmptyResponse,
        )
        assertResponseRejected("fshb", mapOf("status" to 0L))
        assertEquals(
            7,
            (MainBufferedControlResponseParser.parse("magc", mapOf("status" to 7L)) as
                MainBufferedStatusResponse).status,
        )
    }

    @Test
    fun reducerCopiesCookieAndCommitsOnlySuccessfulMatchingResponses() {
        val sourceCookie = byteArrayOf(1, 2, 3)
        val cookie = MainBufferedControlParser.parse(
            "magc",
            mapOf("magicCookieID" to UINT64_MAX, "magicCookie" to sourceCookie),
        ) as MainBufferedMagicCookie
        sourceCookie[0] = 99
        val reducer = MainBufferedControlStateReducer(initialStreamId = 7L)
        val committed = reducer.reduce(
            cookie,
            MainBufferedControlResponseParser.parse("magc", mapOf("status" to 0L)),
        )
        assertEquals(1L, committed.magicCookieGeneration)
        assertEquals(-1L, committed.magicCookieId)
        assertArrayEquals(byteArrayOf(1, 2, 3), committed.magicCookie)

        val leaked = committed.magicCookie!!
        leaked[0] = 42
        assertArrayEquals(byteArrayOf(1, 2, 3), reducer.snapshot().magicCookie)

        val failedCookie = MainBufferedControlParser.parse(
            "magc",
            mapOf("magicCookieID" to 2L, "magicCookie" to byteArrayOf(9)),
        )
        assertThrows(IllegalStateException::class.java) {
            reducer.reduce(
                failedCookie,
                MainBufferedControlResponseParser.parse("magc", mapOf("status" to -50L)),
            )
        }
        val afterFailure = reducer.snapshot()
        assertEquals(1L, afterFailure.magicCookieGeneration)
        assertEquals(-1L, afterFailure.magicCookieId)
        assertArrayEquals(byteArrayOf(1, 2, 3), afterFailure.magicCookie)
    }

    @Test
    fun reducerRollsBackStreamMismatchAndAdvancesPlaybackAndFlushTransactionally() {
        val reducer = MainBufferedControlStateReducer(initialStreamId = 7L)
        val start = MainBufferedControlParser.parse(
            "strt",
            mapOf(
                "streamID" to 7L,
                "rate" to 1L,
                "rtpTime" to 1L,
                "mediaTimeValue" to 2L,
                "mediaTimeScale" to 48_000L,
            ),
        )
        val playing = reducer.reduce(
            start,
            MainBufferedControlResponseParser.parse("strt", currentAnchorResponse(0)),
        )
        assertEquals(MainBufferedPlaybackState.PLAYING, playing.playbackState)
        assertEquals(0xffff_ffffL, playing.anchor!!.point.rtpTime)

        val badFlush = MainBufferedControlParser.parse(
            "fshb",
            flushParams() + ("streamID" to 8L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            reducer.reduce(
                badFlush,
                MainBufferedControlResponseParser.parse("fshb", emptyMap<String, Any?>()),
            )
        }
        val afterMismatch = reducer.snapshot()
        assertEquals(7L, afterMismatch.streamId)
        assertEquals(0L, afterMismatch.flushGeneration)
        assertEquals(MainBufferedPlaybackState.PLAYING, afterMismatch.playbackState)

        val flush = MainBufferedControlParser.parse("fshb", flushParams() + ("streamID" to 7L))
        val afterFlush = reducer.reduce(
            flush,
            MainBufferedControlResponseParser.parse("fshb", emptyMap<String, Any?>()),
        )
        assertEquals(1L, afterFlush.flushGeneration)

        assertThrows(IllegalArgumentException::class.java) {
            reducer.reduce(
                MainBufferedControlParser.parse("anch", mapOf("streamID" to 7L)),
                MainBufferedControlResponseParser.parse("strt", currentAnchorResponse(0)),
            )
        }
        assertEquals(1L, reducer.snapshot().flushGeneration)
    }

    @Test
    fun evidenceModelsDoNotEnableMainBufferedFeature() {
        MainBufferedAudioFormatAdvertisement(MainBufferedAudioFormatBits.ALAC_44100_16_STEREO)
        MainBufferedSetupRequest.parse(validSetup())

        assertThrows(IllegalArgumentException::class.java) {
            AirPlayUltraConfiguration(
                configuredFeatures = setOf(AirPlayFeature.MAIN_BUFFERED),
            )
        }
    }

    private fun validSetup(): Map<String, Any?> = linkedMapOf(
        "type" to 103L,
        "streamConnectionID" to 1L,
        "supportsDynamicStreamID" to true,
        "ct" to 2L,
        "spf" to 352L,
        "audioFormat" to MainBufferedAudioFormatBits.ALAC_44100_16_STEREO,
        "audioFormatIndex" to 18L,
        "isMedia" to true,
        "audioType" to "media",
        "packetFormatAPAP" to true,
    )

    private fun sratPlaying(): Map<String, Any?> = linkedMapOf(
        "streamID" to 7L,
        "rate" to 1L,
        "rtpTime" to 10L,
        "mediaTimeValue" to -20L,
        "mediaTimeScale" to 90_000L,
        "firstAudibleRTPTime" to 30L,
        "firstAudibleMediaTimeValue" to 40L,
        "firstAudibleMediaTimeScale" to 90_000L,
        "networkTimeSecs" to Int.MIN_VALUE.toLong(),
        "networkTimeFrac" to UINT64_MAX,
        "networkTimeTimelineID" to UINT64_MAX,
        "networkTimeFlags" to 0xffff_ffffL,
    )

    private fun flushParams(): Map<String, Any?> = linkedMapOf(
        "flushUntilSeq" to 0xffff_ffffL,
        "flushUntilTS" to 0xffff_ffffL,
        "flushUntilMediaTimeValue" to Long.MAX_VALUE,
        "flushUntilMediaTimeScale" to Int.MAX_VALUE.toLong(),
    )

    private fun currentAnchorResponse(status: Int): Map<String, Any?> = linkedMapOf(
        "rtpTime" to 0xffff_ffffL,
        "mediaTimeValue" to -1L,
        "mediaTimeScale" to 48_000L,
        "networkTimeSecs" to -2L,
        "networkTimeFrac" to UINT64_MAX,
        "networkTimeTimelineID" to UINT64_MAX,
        "networkTimeFlags" to 0xffff_ffffL,
        "status" to status.toLong(),
    )

    private fun assertSetupRejected(value: Map<String, Any?>) {
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedSetupRequest.parse(value)
        }
    }

    private fun assertCommandRejected(tag: String, value: Map<String, Any?>) {
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedControlParser.parse(tag, value)
        }
    }

    private fun assertResponseRejected(tag: String, value: Map<String, Any?>) {
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedControlResponseParser.parse(tag, value)
        }
    }

    private companion object {
        val UINT64_MAX: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        val UINT32_OVERFLOW: BigInteger = BigInteger.ONE.shiftLeft(32)
    }
}
