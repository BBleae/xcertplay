package com.shilapi.xcertplay.airplay.video

import com.shilapi.xcertplay.airplay.BplistCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayVideoOverlayTransactionSceneTest {
    private val codec = CarPlayVideoOverlayActionCodec()
    private val a = "01234567-89ab-cdef-0123-456789abcdef"
    private val b = "fedcba98-7654-3210-fedc-ba9876543210"
    private val c = "00112233-4455-6677-8899-aabbccddeeff"

    @Test
    fun exactTransactionEnvelopeClassifiesOnlyKnownChildren() {
        val fields = transaction(
            function("createLayer", a, mapOf("type" to "ring", "createArguments" to mapOf("radius" to 2.0))),
            function("createLayer", b, mapOf("type" to "text", "createArguments" to mapOf("text" to "READY"))),
            function("insertLayer", a, mapOf("uuidOfSubLayer" to b, "index" to 0)),
        )
        val parsed = decode(fields) as CarPlayVideoOverlayAction.Transaction
        assertEquals(setOf("type", "action", "arguments"), parsed.rawMessage.fields.keys)
        assertEquals(3, parsed.children.size)
        assertTrue(parsed.children[0] is CarPlayVideoOverlayAction.CreateRing)
        assertTrue(parsed.children[1] is CarPlayVideoOverlayAction.CreateText)
        assertTrue(parsed.children[2] is CarPlayVideoOverlayAction.InsertLayer)
        assertFalse(parsed.toString().contains("READY"))
        assertFalse(parsed.toString().contains(a))
        assertTrue((parsed.rawMessage.field("arguments") as Map<*, *>)["functions"] is List<*>)

        assertOpaque(fields + ("objectUUID" to a))
        assertOpaque(fields + ("messageID" to 1))
        assertOpaque(fields + ("future" to true))
        assertOpaque(fields + ("arguments" to mapOf("functions" to emptyList<Any>(), "future" to true)))
    }

    @Test
    fun nestedUnknownMalformedExtendedAndOverLimitChildrenMakeWholeTransactionOpaque() {
        val known = function("removeLayer", a)
        val rejectedChildren = listOf(
            function("futureAction", a),
            function("createLayer", a, mapOf("type" to "ring", "createArguments" to mapOf("radius" to "2"))),
            function("removeLayer", a) + ("extra" to true),
            transaction(known),
            mapOf("notFunction" to true),
        )
        rejectedChildren.forEach { child ->
            val result = decode(transaction(known, child))
            assertTrue(result is CarPlayVideoOverlayAction.Opaque)
            assertEquals("transaction", result.rawMessage.field("action"))
        }
        assertOpaque(mapOf("type" to "function", "action" to "transaction", "arguments" to mapOf("functions" to "wrong")))
        val narrow = CarPlayVideoOverlayActionCodec(maximumTypedTransactionChildren = 2)
        assertTrue(
            narrow.decodeFromPhone(BplistCodec.encode(transaction(known, known, known)))
                is CarPlayVideoOverlayAction.Opaque,
        )
    }

    @Test
    fun transactionCommitsAsOneRevisionWithOrderedParentChildrenAndOwnedAssets() {
        val data = byteArrayOf(1, 2, 3)
        val scene = CarPlayVideoOverlaySceneSession()
        val action = decode(
            transaction(
                function("createLayer", a, mapOf("type" to "ring", "createArguments" to mapOf("radius" to 7.0))),
                function("createLayer", b, mapOf("type" to "text", "createArguments" to mapOf("text" to "A"))),
                function("createLayer", c, mapOf("type" to "text", "createArguments" to mapOf("text" to "B"))),
                function("insertLayer", a, mapOf("uuidOfSubLayer" to c, "index" to 0)),
                function("insertLayer", a, mapOf("uuidOfSubLayer" to b, "index" to 0)),
                function("style", b, mapOf("key" to "opacity", "value" to 0.5)),
                function("createAsset", arguments = asset("icon", data)),
            ),
        )
        assertTrue(scene.apply(action))
        data[0] = 9
        val snapshot = scene.snapshot()
        assertEquals(1, snapshot.revision)
        assertFalse(snapshot.terminal)
        assertEquals(listOf(b, c), snapshot.layers[a]?.children)
        assertEquals(a, snapshot.layers[b]?.parentUuid)
        assertEquals("A", snapshot.layers[b]?.text)
        assertEquals(0.5, snapshot.layers[b]!!.opacity!!, 0.0)
        assertArrayEquals(byteArrayOf(1, 2, 3), snapshot.assets["icon"]!!.data)
        snapshot.assets["icon"]!!.data[0] = 8
        assertArrayEquals(byteArrayOf(1, 2, 3), scene.snapshot().assets["icon"]!!.data)
    }

    @Test
    fun duplicateUuidCycleDanglingInvalidIndexAndSubtypeMismatchRollBackEntireBundle() {
        val scene = CarPlayVideoOverlaySceneSession()
        val createA = function("createLayer", a, mapOf("type" to "ring", "createArguments" to mapOf("radius" to 1.0)))
        val createB = function("createLayer", b, mapOf("type" to "text", "createArguments" to mapOf("text" to "b")))
        assertTrue(scene.apply(decode(transaction(createA, createB))))
        val baseline = scene.snapshot()
        val cases = listOf(
            transaction(
                function("updateLayer", a, mapOf("radius" to 2.0)),
                function("createLayer", a.uppercase(), mapOf("type" to "ring", "createArguments" to mapOf("radius" to 3.0))),
            ),
            transaction(
                function("insertLayer", a, mapOf("uuidOfSubLayer" to b, "index" to 0)),
                function("insertLayer", b, mapOf("uuidOfSubLayer" to a, "index" to 0)),
            ),
            transaction(function("insertLayer", a, mapOf("uuidOfSubLayer" to c, "index" to 0))),
            transaction(function("insertLayer", a, mapOf("uuidOfSubLayer" to b, "index" to 1))),
            transaction(function("updateLayer", a, mapOf("text" to "wrong subtype"))),
            transaction(function("style", a, mapOf("key" to "opacity", "value" to 2.0))),
        )
        cases.forEach { fields ->
            assertFalse(scene.apply(decode(fields)))
            assertEquals(baseline.revision, scene.snapshot().revision)
            assertEquals(1.0, scene.snapshot().layers[a]!!.radius!!, 0.0)
            assertTrue(scene.snapshot().layers[a]!!.children.isEmpty())
        }
    }

    @Test
    fun layerAndAssetLimitsAndRemovalDoNotGuessCascadeSemantics() {
        val scene = CarPlayVideoOverlaySceneSession(
            CarPlayVideoOverlaySceneLimits(
                maximumLayers = 2, maximumAssets = 2, maximumAssetBytes = 3,
                maximumChildrenPerLayer = 1, maximumTransactionChildren = 3,
            ),
        )
        val createA = function("createLayer", a, mapOf("type" to "ring", "createArguments" to mapOf("radius" to 1.0)))
        val createB = function("createLayer", b, mapOf("type" to "text", "createArguments" to mapOf("text" to "b")))
        assertTrue(scene.apply(decode(transaction(createA, createB))))
        val overLayerLimit = decode(
            function("createLayer", c, mapOf("type" to "text", "createArguments" to mapOf("text" to "c"))),
        )
        assertFalse(scene.apply(overLayerLimit))
        assertFalse(scene.apply(decode(transaction(
            function("updateLayer", a, mapOf("radius" to 3.0)), createB,
            function("updateLayer", b, mapOf("text" to "x")), createA,
        ))))
        assertTrue(scene.apply(decode(function("insertLayer", a, mapOf("uuidOfSubLayer" to b, "index" to 0)))))
        assertFalse(scene.apply(decode(function("removeLayer", a))))
        assertTrue(scene.apply(decode(function("removeLayer", b))))
        assertTrue(scene.snapshot().layers[a]!!.children.isEmpty())
        assertTrue(scene.apply(decode(function("createAsset", arguments = asset("one", byteArrayOf(1, 2))))))
        assertFalse(scene.apply(decode(function("createAsset", arguments = asset("two", byteArrayOf(3, 4))))))
        assertFalse(scene.apply(decode(function("createAsset", arguments = asset("one", byteArrayOf(3))))))
        assertTrue(scene.apply(decode(function("removeAsset", arguments = mapOf("name" to "one")))))
        assertTrue(scene.apply(decode(function("createAsset", arguments = asset("two", byteArrayOf(3, 4))))))
        val missingAssetRemoval = decode(function("removeAsset", arguments = mapOf("name" to "missing")))
        assertFalse(scene.apply(missingAssetRemoval))
    }

    @Test
    fun cumulativeTextBudgetIsChargedAndRolledBackAtomically() {
        val scene = CarPlayVideoOverlaySceneSession(
            CarPlayVideoOverlaySceneLimits(maximumTextBytes = 8),
        )
        val createA = function("createLayer", a, mapOf("type" to "text", "createArguments" to mapOf("text" to "AB")))
        val createB = function("createLayer", b, mapOf("type" to "text", "createArguments" to mapOf("text" to "CD")))
        assertTrue(scene.apply(decode(transaction(createA, createB))))
        assertEquals(1, scene.snapshot().revision)

        val overBudgetUpdate = function("updateLayer", a, mapOf("text" to "ABCDE"))
        assertFalse(scene.apply(decode(overBudgetUpdate)))
        assertFalse(scene.apply(decode(transaction(
            function("removeLayer", b), overBudgetUpdate,
        ))))
        assertEquals(1, scene.snapshot().revision)
        assertEquals("AB", scene.snapshot().layers[a]!!.text)
        assertEquals("CD", scene.snapshot().layers[b]!!.text)

        assertTrue(scene.apply(decode(transaction(
            function("removeLayer", b),
            function("updateLayer", a, mapOf("text" to "ABC")),
        ))))
        assertEquals(2, scene.snapshot().revision)
        assertEquals("ABC", scene.snapshot().layers[a]!!.text)
        val createBAgain = function("createLayer", b, mapOf("type" to "text", "createArguments" to mapOf("text" to "E")))
        assertTrue(scene.apply(decode(createBAgain)))
        assertFalse(scene.apply(decode(createB)))
    }

    @Test
    fun terminalRevocationErasesStateAndRejectsStaleConcurrentApply() {
        val scene = CarPlayVideoOverlaySceneSession()
        val first = decode(transaction(
            function("createLayer", a, mapOf("type" to "ring", "createArguments" to mapOf("radius" to 1.0))),
            function("createAsset", arguments = asset("secret", byteArrayOf(1, 2, 3))),
        ))
        assertTrue(scene.apply(first))
        val start = CountDownLatch(1)
        val done = CountDownLatch(1)
        val stale = decode(function("updateLayer", a, mapOf("radius" to 2.0)))
        val worker = Thread {
            start.await()
            repeat(1000) { scene.apply(stale) }
            done.countDown()
        }
        worker.start()
        start.countDown()
        scene.revoke()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val snapshot = scene.snapshot()
        assertTrue(snapshot.terminal)
        assertTrue(snapshot.layers.isEmpty())
        assertTrue(snapshot.assets.isEmpty())
        assertFalse(scene.apply(first))
        assertFalse(scene.apply(stale))
        val revision = snapshot.revision
        scene.close()
        assertEquals(revision, scene.snapshot().revision)
    }

    private fun asset(name: String, data: ByteArray): Map<String, Any?> = mapOf(
        "name" to name, "data" to data, "manifest" to mapOf("mimeType" to "image/png"),
    )

    private fun decode(fields: Map<String, Any?>): CarPlayVideoOverlayAction =
        codec.decodeFromPhone(BplistCodec.encode(fields))

    private fun assertOpaque(fields: Map<String, Any?>) {
        assertTrue(decode(fields) is CarPlayVideoOverlayAction.Opaque)
    }

    private fun transaction(vararg functions: Map<String, Any?>): Map<String, Any?> = mapOf(
        "type" to "function", "action" to "transaction",
        "arguments" to mapOf("functions" to functions.toList()),
    )

    private fun function(
        action: String,
        objectUuid: String? = null,
        arguments: Map<String, Any?>? = null,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "type" to "function", "action" to action,
    ).apply {
        if (objectUuid != null) this["objectUUID"] = objectUuid
        if (arguments != null) this["arguments"] = arguments
    }
}
