package com.shilapi.xcertplay.media

import java.util.IdentityHashMap

/** Global identity-based ownership index; equal objects never substitute for the exact instance. */
internal class IdentityInstanceRegistry<T : Any> {
    private val owners = IdentityHashMap<T, Any>()

    @Synchronized
    fun reserve(instance: T): Any {
        require(!owners.containsKey(instance)) {
            "Instance is already registered; reuse its existing ownership lease"
        }
        return Any().also { owners[instance] = it }
    }

    @Synchronized
    fun owner(instance: T): Any? = owners[instance]

    @Synchronized
    fun release(instance: T, owner: Any): Boolean =
        if (owners[instance] === owner) {
            owners.remove(instance)
            true
        } else {
            false
        }

    @Synchronized
    fun contains(instance: T): Boolean = owners.containsKey(instance)

    @Synchronized
    fun clear() = owners.clear()
}
