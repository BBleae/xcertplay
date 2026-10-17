package com.shilapi.xcertplay.ultra.egl

import java.util.IdentityHashMap

/** Small referential index used for session handles and bottom Surface reservations. */
internal class IdentityReservationIndex<K : Any, V : Any> {
    private val values = IdentityHashMap<K, V>()

    fun reserve(key: K, value: V) {
        require(!values.containsKey(key)) { "Identity is already reserved" }
        values[key] = value
    }

    operator fun get(key: K): V? = values[key]
    fun contains(key: K): Boolean = values.containsKey(key)

    fun release(key: K, expected: V): Boolean {
        if (values[key] !== expected) return false
        values.remove(key)
        return true
    }

    fun valuesSnapshot(): List<V> = values.values.toList()
    fun clear() = values.clear()
}
