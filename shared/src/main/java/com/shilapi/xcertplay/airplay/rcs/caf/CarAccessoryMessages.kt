package com.shilapi.xcertplay.airplay.rcs.caf

/** Semantic CAF message factories. Returned frames contain a complete RCS comm body. */
object CarAccessoryMessages {
    fun configRequest(pluginId: Long, transactionId: Long): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.CONFIG_REQUEST, transactionId),
    )

    fun configResponse(
        pluginId: Long,
        transactionId: Long,
        values: Map<*, *>,
    ): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.CONFIG_RESPONSE, transactionId, values),
    )

    fun configNotify(pluginId: Long, values: Map<*, *>): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.CONFIG_NOTIFY, values = values),
    )

    fun registerRequest(
        pluginId: Long,
        transactionId: Long,
        registration: CafRegistration = CafRegistration.Wildcard,
    ): CafRcsFrame = frame(
        pluginId,
        CafMessage(
            command = CafCommand.REGISTER_REQUEST,
            transactionId = transactionId,
            values = when (registration) {
                CafRegistration.Wildcard -> "*"
                is CafRegistration.Identifiers -> registration.values
            },
        ),
    )

    fun registerResponse(
        pluginId: Long,
        transactionId: Long,
        values: Map<Long, Any?>,
        errors: Map<Long, Long>,
    ): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.REGISTER_RESPONSE, transactionId, values, errors),
    )

    fun unregisterRequest(
        pluginId: Long,
        transactionId: Long,
        identifiers: List<Long>,
    ): CafRcsFrame {
        require(identifiers.isNotEmpty()) { "identifiers must not be empty" }
        return frame(
            pluginId,
            CafMessage(CafCommand.UNREGISTER_REQUEST, transactionId, identifiers),
        )
    }

    fun unregisterResponse(pluginId: Long, transactionId: Long): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.UNREGISTER_RESPONSE, transactionId),
    )

    fun readRequest(
        pluginId: Long,
        transactionId: Long,
        identifiers: List<Long>,
    ): CafRcsFrame {
        require(identifiers.isNotEmpty()) { "identifiers must not be empty" }
        return frame(pluginId, CafMessage(CafCommand.READ_REQUEST, transactionId, identifiers))
    }

    fun readResponse(
        pluginId: Long,
        transactionId: Long,
        values: Map<Long, Any?>,
        errors: Map<Long, Long>,
    ): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.READ_RESPONSE, transactionId, values, errors),
    )

    fun writeRequest(
        pluginId: Long,
        transactionId: Long,
        values: Map<Long, Any?>,
    ): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.WRITE_REQUEST, transactionId, values),
    )

    fun writeResponse(
        pluginId: Long,
        transactionId: Long,
        errors: Map<Long, Long>,
    ): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.WRITE_RESPONSE, transactionId, errors = errors),
    )

    fun controlRequest(
        pluginId: Long,
        transactionId: Long,
        values: Map<Long, Any?>,
    ): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.CONTROL_REQUEST, transactionId, values),
    )

    fun controlResponse(
        pluginId: Long,
        transactionId: Long,
        values: Map<Long, Any?>,
        errors: Map<Long, Long>,
    ): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.CONTROL_RESPONSE, transactionId, values, errors),
    )

    fun controlNotify(pluginId: Long, values: Map<Long, Any?>): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.CONTROL_NOTIFY, values = values),
    )

    fun updateNotify(pluginId: Long, values: Map<Long, Any?>): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.UPDATE_NOTIFY, values = values),
    )

    fun generalError(
        pluginId: Long,
        error: Long,
        transactionId: Long? = null,
    ): CafRcsFrame = frame(
        pluginId,
        CafMessage(CafCommand.GENERAL_ERROR, transactionId, error = error),
    )

    fun reader(rcsBody: ByteArray): CafMessageReader = CafMessageReader(CafRcsCodec.decode(rcsBody))

    private fun frame(pluginId: Long, message: CafMessage): CafRcsFrame =
        CafRcsCodec.encode(CafEnvelope(pluginId, message))
}
