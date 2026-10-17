package com.shilapi.xcertplay.airplay.rcs.caf

/** Synthetic protocol fixture. It is deliberately test-only and is not a production CAF profile. */
internal object SyntheticCafTestFixture {
    const val PLUGIN_ID = 7L
    const val ACCESSORY_IID = 1L
    const val SERVICE_IID = 2L
    const val TEMPERATURE_IID = 3L
    const val ERROR_STATE_IID = 4L
    const val CONTROL_IID = 5L
    const val HIGH_PRIORITY_IID = 6L
    const val ACCESSORY_CONTROL_IID = 7L
    const val STRING_SETTING_IID = 8L

    val raw: Map<String, Any?> = linkedMapOf(
        "accessories" to listOf(
            linkedMapOf(
                "type" to 0x0000000001000001L,
                "iid" to ACCESSORY_IID,
                "version" to "test-only-1",
                "services" to listOf(
                    linkedMapOf(
                        "type" to 0x0000000011000002L,
                        "iid" to SERVICE_IID,
                        "characteristics" to listOf(
                            linkedMapOf(
                                "type" to 0x0000000031000019L,
                                "iid" to TEMPERATURE_IID,
                                "format" to 9L,
                                "priority" to 0L,
                                "initialValue" to 21.5,
                                "iidError" to ERROR_STATE_IID,
                                "oemFixtureField" to "preserved",
                            ),
                            linkedMapOf(
                                "type" to 0x00000000FF000001L,
                                "iid" to ERROR_STATE_IID,
                                "format" to 8L,
                                "priority" to 0L,
                                "initialValue" to 0L,
                            ),
                            linkedMapOf(
                                "type" to 0x00000000FF000002L,
                                "iid" to HIGH_PRIORITY_IID,
                                "format" to 1L,
                                "priority" to 1L,
                                "initialValue" to 1L,
                            ),
                        ),
                        "controls" to listOf(
                            linkedMapOf(
                                "type" to 0x000000000F000032L,
                                "iid" to CONTROL_IID,
                                "sender" to "device",
                                "hasResponse" to true,
                                "priority" to 0L,
                                "requestParameters" to listOf(
                                    linkedMapOf(
                                        "name" to "reason",
                                        "format" to 10L,
                                        "supportsInvalid" to false,
                                    ),
                                ),
                                "responseParameters" to listOf(
                                    linkedMapOf(
                                        "name" to "accepted",
                                        "format" to 0L,
                                        "supportsInvalid" to false,
                                    ),
                                ),
                            ),
                            linkedMapOf(
                                "type" to 0x000000000F000033L,
                                "iid" to ACCESSORY_CONTROL_IID,
                                "sender" to "accessory",
                                "hasResponse" to false,
                                "priority" to 0L,
                            ),
                        ),
                    ),
                ),
            ),
        ),
        "oemRootField" to 42L,
    )

    fun tree(): CafConfigTree = CafConfigTree.decode(raw)

    @Suppress("UNCHECKED_CAST")
    fun constrainedWritableTree(): CafConfigTree {
        val root = mutableRaw()
        val characteristicValues = (((root["accessories"] as MutableList<MutableMap<String, Any?>>)
            .single()["services"] as MutableList<MutableMap<String, Any?>>)
            .single()["characteristics"] as MutableList<MutableMap<String, Any?>>)
        characteristicValues.first { it["iid"] == TEMPERATURE_IID }.apply {
            put("writable", true)
            put("initialValue", 20.0)
            put("minimumValue", 10.0)
            put("maximumValue", 30.0)
            put("stepValue", 0.5)
            put("validValues", listOf(10.0, 20.0, 30.0))
        }
        characteristicValues += linkedMapOf(
            "type" to 0x00000000FF000003L,
            "iid" to STRING_SETTING_IID,
            "format" to 10L,
            "priority" to 0L,
            "writable" to true,
            "initialValue" to "AUTO",
            "maximumLength" to 4L,
            "validValues" to listOf("AUTO", "ECO"),
        )
        return CafConfigTree.decode(root)
    }

    @Suppress("UNCHECKED_CAST")
    fun mutableRaw(): MutableMap<String, Any?> {
        fun copy(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.associateTo(linkedMapOf()) {
                it.key as String to copy(it.value)
            }
            is List<*> -> value.mapTo(mutableListOf(), ::copy)
            is ByteArray -> value.copyOf()
            else -> value
        }
        return copy(raw) as MutableMap<String, Any?>
    }
}
