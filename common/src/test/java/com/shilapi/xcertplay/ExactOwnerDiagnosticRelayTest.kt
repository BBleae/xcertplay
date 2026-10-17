package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactOwnerDiagnosticRelayTest {
    @Test fun foreignSinkCannotSubscribeOrReuseAnExactOwnerRelay() {
        val owner = Any()
        val foreign = Any()
        val relay = ExactOwnerDiagnosticRelay<Any, Int>()
        relay.bindOwner(owner)
        assertTrue(relay.belongsTo(owner))
        assertFalse(relay.belongsTo(foreign))
        assertThrows(IllegalStateException::class.java) {
            relay.attach(foreign, Any()) { }
        }
        assertThrows(IllegalStateException::class.java) { relay.bindOwner(foreign) }
        relay.close()
    }

    @Test fun retiredChildCannotClearOrReceiveTheNextSessionCallback() {
        val owner = Any()
        val relay = ExactOwnerDiagnosticRelay<Any, Int>()
        relay.bindOwner(owner)
        val first = ArrayList<Int>()
        val second = ArrayList<Int>()
        val retiredChild = relay.attach(owner, Any(), first::add)
        relay.emit(1)
        assertThrows(IllegalStateException::class.java) {
            relay.attach(owner, Any(), second::add)
        }
        retiredChild.close()
        val currentChild = relay.attach(owner, Any(), second::add)
        retiredChild.close() // A delayed retirement is not allowed to detach the successor.
        relay.emit(2)
        assertEquals(listOf(1), first)
        assertEquals(listOf(2), second)
        currentChild.close()
        relay.emit(3)
        assertEquals(listOf(2), second)
    }

    @Test fun sinkRetirementRevokesEveryFutureCallbackAndCannotBeRebound() {
        val owner = Any()
        val relay = ExactOwnerDiagnosticRelay<Any, Int>()
        relay.bindOwner(owner)
        val seen = ArrayList<Int>()
        val attachment = relay.attach(owner, Any(), seen::add)
        relay.emit(1)
        relay.close()
        relay.emit(2)
        attachment.close()
        assertFalse(relay.belongsTo(owner))
        assertEquals(listOf(1), seen)
        assertThrows(IllegalStateException::class.java) {
            relay.attach(owner, Any(), seen::add)
        }
        assertThrows(IllegalStateException::class.java) { relay.bindOwner(owner) }
    }
}
