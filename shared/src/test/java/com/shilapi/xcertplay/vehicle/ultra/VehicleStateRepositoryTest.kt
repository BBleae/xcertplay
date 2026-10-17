package com.shilapi.xcertplay.vehicle.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.SyntheticCafTestFixture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VehicleStateRepositoryTest {
    @Test
    fun readOnlyRepositoryReturnsExplicitOemSuppliedErrors() {
        val repository = object : ReadOnlyVehicleStateRepository(
            SyntheticCafTestFixture.tree(),
            unsupportedMutationError = -1234L,
        ) {
            override fun read(iids: Set<Long>) = VehicleStateResult(
                values = iids.associateWith { 1L },
            )

            override fun subscribe(
                iids: Set<Long>,
                listener: VehicleStateListener,
            ) = VehicleStateSubscription { }
        }

        assertEquals(mapOf(3L to -1234L), repository.write(mapOf(3L to 22.0)).errors)
        assertEquals(mapOf(5L to -1234L), repository.control(mapOf(5L to "x")).errors)
    }

    @Test
    fun repositoryResultsAndUpdatesDetachNestedMutableValues() {
        val bytes = byteArrayOf(1, 2)
        val nested = mutableListOf<Any?>(bytes)
        val source = linkedMapOf<Long, Any?>(7L to linkedMapOf("items" to nested))

        val result = VehicleStateResult(values = source)
        val update = VehicleStateUpdate(source)
        bytes.fill(9)
        nested.clear()
        source.clear()

        fun bytesFrom(values: Map<Long, Any?>): ByteArray {
            val map = values.getValue(7L) as Map<*, *>
            val list = map["items"] as List<*>
            return list.single() as ByteArray
        }
        assertArrayEquals(byteArrayOf(1, 2), bytesFrom(result.values))
        assertArrayEquals(byteArrayOf(1, 2), bytesFrom(update.values))
        bytesFrom(result.values)[0] = 99
        bytesFrom(update.values)[0] = 99
        assertArrayEquals(byteArrayOf(1, 2), bytesFrom(result.values))
        assertArrayEquals(byteArrayOf(1, 2), bytesFrom(update.values))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (result.values as MutableMap<Long, Any?>)[8L] = true
        }
    }

    @Test
    fun repositoryResultRejectsCyclesAndUnsupportedMutableTypes() {
        val cycle = mutableListOf<Any?>()
        cycle.add(cycle)
        assertThrows(IllegalArgumentException::class.java) {
            VehicleStateResult(values = mapOf(1L to cycle))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VehicleControlResult(values = mapOf(1L to StringBuilder("mutable")))
        }
    }
}
