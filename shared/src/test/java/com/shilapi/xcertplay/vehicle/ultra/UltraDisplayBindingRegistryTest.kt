package com.shilapi.xcertplay.vehicle.ultra

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraDisplayBindingRegistryTest {
    @Test
    fun publishesOnlyExplicitRoutesAcrossStrongIdentifierNamespaces() {
        val sameText = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val uiId = UltraUiSyncDisplayId(sameText)
        val topologyId = UltraTopologyDisplayId(UUID.fromString(sameText))
        val streamId = UltraAirPlayStreamIdentity(
            type = 110,
            streamConnectionId = sameText,
            clientTypeUuid = null,
        )
        val outputId = UltraDisplayOutputId.Android(sameText)
        val binding = UltraDisplayBinding(uiId, topologyId, streamId, outputId)
        val registry = UltraDisplayBindingRegistry()

        val snapshot = registry.replace(
            resources = resources(uiId, topologyId, streamId, outputId),
            bindings = listOf(binding),
        )

        assertEquals(1L, snapshot.revision)
        assertEquals(binding, snapshot.bindingFor(uiId))
        assertEquals(binding, snapshot.bindingFor(topologyId))
        assertEquals(binding, snapshot.bindingFor(streamId))
        assertEquals(binding, snapshot.bindingFor(outputId))
        assertNull(snapshot.bindingFor(UltraUiSyncDisplayId(sameText.uppercase())))
        assertNull(snapshot.bindingFor(UltraDisplayOutputId.Custom(sameText)))
    }

    @Test
    fun resourcesBindingsAndSnapshotsDetachCallerCollections() {
        val binding = binding(1)
        val uiIds = mutableListOf(binding.uiSyncDisplayId)
        val topologyIds = mutableListOf(binding.topologyDisplayId)
        val streams = mutableListOf(binding.airPlayStreamIdentity)
        val outputs = mutableListOf(binding.outputId)
        val resources = UltraDisplayBindingResources(uiIds, topologyIds, streams, outputs)
        val bindings = mutableListOf(binding)
        val registry = UltraDisplayBindingRegistry()

        val published = registry.replace(resources, bindings)
        uiIds.clear()
        topologyIds.clear()
        streams.clear()
        outputs.clear()
        bindings.clear()

        assertEquals(listOf(binding), published.bindings)
        assertEquals(setOf(binding.uiSyncDisplayId), published.resources.uiSyncDisplayIds)
        assertEquals(listOf(binding), registry.snapshot().bindings)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (published.bindings as MutableList<UltraDisplayBinding>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (published.resources.outputIds as MutableSet<UltraDisplayOutputId>).clear()
        }
    }

    @Test
    fun invalidReferencesAndPartialCardinalityRollBackWholeReplacement() {
        val registry = UltraDisplayBindingRegistry()
        val original = binding(1)
        registry.replace(
            resources(original),
            listOf(original),
        )
        val before = registry.snapshot()

        val second = binding(2)
        val twoResources = resources(
            listOf(original, second),
        )
        assertThrows(IllegalArgumentException::class.java) {
            registry.replace(twoResources, listOf(original))
        }
        val dangling = second.copy(
            topologyDisplayId = UltraTopologyDisplayId(
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            registry.replace(twoResources, listOf(original, dangling))
        }

        val after = registry.snapshot()
        assertEquals(before.revision, after.revision)
        assertEquals(before.bindings, after.bindings)
        assertEquals(before.resources.outputIds, after.resources.outputIds)
    }

    @Test
    fun rejectsDuplicateRowsAndEveryManyToOneNamespaceMapping() {
        val registry = UltraDisplayBindingRegistry()
        val first = binding(1)
        val second = binding(2)
        val resources = resources(listOf(first, second))

        val invalid = listOf(
            listOf(first, first),
            listOf(first, second.copy(uiSyncDisplayId = first.uiSyncDisplayId)),
            listOf(first, second.copy(topologyDisplayId = first.topologyDisplayId)),
            listOf(first, second.copy(airPlayStreamIdentity = first.airPlayStreamIdentity)),
            listOf(first, second.copy(outputId = first.outputId)),
        )
        invalid.forEach { bindings ->
            assertThrows(IllegalArgumentException::class.java) {
                registry.replace(resources, bindings)
            }
            assertEquals(0L, registry.snapshot().revision)
            assertTrue(registry.snapshot().bindings.isEmpty())
        }
    }

    @Test
    fun resourceDeclarationRejectsDuplicatesAndUnequalCardinality() {
        val first = binding(1)
        val second = binding(2)
        assertThrows(IllegalArgumentException::class.java) {
            UltraDisplayBindingResources(
                uiSyncDisplayIds = listOf(first.uiSyncDisplayId, first.uiSyncDisplayId),
                topologyDisplayIds = listOf(first.topologyDisplayId, second.topologyDisplayId),
                airPlayStreamIdentities = listOf(
                    first.airPlayStreamIdentity,
                    second.airPlayStreamIdentity,
                ),
                outputIds = listOf(first.outputId, second.outputId),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraDisplayBindingResources(
                uiSyncDisplayIds = listOf(first.uiSyncDisplayId),
                topologyDisplayIds = listOf(first.topologyDisplayId, second.topologyDisplayId),
                airPlayStreamIdentities = listOf(first.airPlayStreamIdentity),
                outputIds = listOf(first.outputId),
            )
        }
        assertThrows(IllegalArgumentException::class.java) { UltraUiSyncDisplayId("   ") }
        assertThrows(IllegalArgumentException::class.java) {
            UltraDisplayOutputId.Custom("bad\noutput")
        }
    }

    @Test
    fun androidAndCustomOutputIdsWithSameOpaqueTextRemainDistinct() {
        val first = binding(1).copy(outputId = UltraDisplayOutputId.Android("shared-output"))
        val second = binding(2).copy(outputId = UltraDisplayOutputId.Custom("shared-output"))
        val snapshot = UltraDisplayBindingRegistry().replace(
            resources(listOf(first, second)),
            listOf(first, second),
        )

        assertEquals(first, snapshot.bindingFor(UltraDisplayOutputId.Android("shared-output")))
        assertEquals(second, snapshot.bindingFor(UltraDisplayOutputId.Custom("shared-output")))
    }

    @Test
    fun rejectsOversizedOrDishonestCollectionsBeforeUnboundedCopying() {
        val declaredHuge = object : AbstractCollection<UltraUiSyncDisplayId>() {
            override val size: Int = Int.MAX_VALUE
            override fun iterator(): Iterator<UltraUiSyncDisplayId> =
                throw AssertionError("oversized collection must be rejected before iteration")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraDisplayBindingResources(
                declaredHuge,
                emptyList(),
                emptyList(),
                emptyList(),
            )
        }

        var sequence = 0
        val dishonest = object : AbstractCollection<UltraUiSyncDisplayId>() {
            override val size: Int = 0
            override fun iterator(): Iterator<UltraUiSyncDisplayId> =
                object : Iterator<UltraUiSyncDisplayId> {
                    override fun hasNext(): Boolean = true
                    override fun next(): UltraUiSyncDisplayId =
                        UltraUiSyncDisplayId("generated-${sequence++}")
                }
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraDisplayBindingResources(
                dishonest,
                emptyList(),
                emptyList(),
                emptyList(),
            )
        }
    }

    private fun binding(index: Int): UltraDisplayBinding = UltraDisplayBinding(
        uiSyncDisplayId = UltraUiSyncDisplayId("ui-display-$index"),
        topologyDisplayId = UltraTopologyDisplayId(
            UUID.fromString("00000000-0000-0000-0000-%012d".format(index)),
        ),
        airPlayStreamIdentity = UltraAirPlayStreamIdentity(
            type = 110,
            streamConnectionId = "stream-$index",
            clientTypeUuid = "client-$index",
        ),
        outputId = UltraDisplayOutputId.Android("output-$index"),
    )

    private fun resources(binding: UltraDisplayBinding): UltraDisplayBindingResources =
        resources(
            binding.uiSyncDisplayId,
            binding.topologyDisplayId,
            binding.airPlayStreamIdentity,
            binding.outputId,
        )

    private fun resources(bindings: List<UltraDisplayBinding>): UltraDisplayBindingResources =
        UltraDisplayBindingResources(
            bindings.map(UltraDisplayBinding::uiSyncDisplayId),
            bindings.map(UltraDisplayBinding::topologyDisplayId),
            bindings.map(UltraDisplayBinding::airPlayStreamIdentity),
            bindings.map(UltraDisplayBinding::outputId),
        )

    private fun resources(
        uiId: UltraUiSyncDisplayId,
        topologyId: UltraTopologyDisplayId,
        streamId: UltraAirPlayStreamIdentity,
        outputId: UltraDisplayOutputId,
    ): UltraDisplayBindingResources = UltraDisplayBindingResources(
        listOf(uiId),
        listOf(topologyId),
        listOf(streamId),
        listOf(outputId),
    )
}
