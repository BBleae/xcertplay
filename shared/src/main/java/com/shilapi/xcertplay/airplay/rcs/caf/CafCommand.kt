package com.shilapi.xcertplay.airplay.rcs.caf

enum class CafTransactionRule {
    REQUIRED,
    OPTIONAL,
    FORBIDDEN,
}

enum class CafValueRule {
    NONE,
    WILDCARD_OR_IID_LIST,
    IID_LIST,
    IID_VALUE_MAP,
    CONFIG_TREE,
}

enum class CafErrorRule {
    NONE,
    IID_ERROR_MAP,
    OS_STATUS,
}

/** Firmware-observed CAF command names and their exact wire shapes. */
enum class CafCommand(
    val wireName: String,
    val transactionRule: CafTransactionRule,
    val valueRule: CafValueRule,
    val errorRule: CafErrorRule,
) {
    CONFIG_NOTIFY("configNotify", CafTransactionRule.FORBIDDEN, CafValueRule.CONFIG_TREE, CafErrorRule.NONE),
    CONFIG_REQUEST("configRequest", CafTransactionRule.REQUIRED, CafValueRule.NONE, CafErrorRule.NONE),
    CONFIG_RESPONSE("configResponse", CafTransactionRule.REQUIRED, CafValueRule.CONFIG_TREE, CafErrorRule.NONE),
    REGISTER_REQUEST("registerRequest", CafTransactionRule.REQUIRED, CafValueRule.WILDCARD_OR_IID_LIST, CafErrorRule.NONE),
    REGISTER_RESPONSE("registerResponse", CafTransactionRule.REQUIRED, CafValueRule.IID_VALUE_MAP, CafErrorRule.IID_ERROR_MAP),
    UNREGISTER_REQUEST("unregisterRequest", CafTransactionRule.REQUIRED, CafValueRule.IID_LIST, CafErrorRule.NONE),
    UNREGISTER_RESPONSE("unregisterResponse", CafTransactionRule.REQUIRED, CafValueRule.NONE, CafErrorRule.NONE),
    READ_REQUEST("readRequest", CafTransactionRule.REQUIRED, CafValueRule.IID_LIST, CafErrorRule.NONE),
    READ_RESPONSE("readResponse", CafTransactionRule.REQUIRED, CafValueRule.IID_VALUE_MAP, CafErrorRule.IID_ERROR_MAP),
    WRITE_REQUEST("writeRequest", CafTransactionRule.REQUIRED, CafValueRule.IID_VALUE_MAP, CafErrorRule.NONE),
    WRITE_RESPONSE("writeResponse", CafTransactionRule.REQUIRED, CafValueRule.NONE, CafErrorRule.IID_ERROR_MAP),
    CONTROL_NOTIFY("controlNotify", CafTransactionRule.FORBIDDEN, CafValueRule.IID_VALUE_MAP, CafErrorRule.NONE),
    CONTROL_REQUEST("controlRequest", CafTransactionRule.REQUIRED, CafValueRule.IID_VALUE_MAP, CafErrorRule.NONE),
    CONTROL_RESPONSE("controlResponse", CafTransactionRule.REQUIRED, CafValueRule.IID_VALUE_MAP, CafErrorRule.IID_ERROR_MAP),
    UPDATE_NOTIFY("updateNotify", CafTransactionRule.FORBIDDEN, CafValueRule.IID_VALUE_MAP, CafErrorRule.NONE),
    GENERAL_ERROR("generalError", CafTransactionRule.OPTIONAL, CafValueRule.NONE, CafErrorRule.OS_STATUS),
    ;

    val isRequest: Boolean
        get() = this in REQUESTS

    val isResponse: Boolean
        get() = this in RESPONSES || this == GENERAL_ERROR

    companion object {
        val REQUESTS: Set<CafCommand> = setOf(
            CONFIG_REQUEST,
            REGISTER_REQUEST,
            UNREGISTER_REQUEST,
            READ_REQUEST,
            WRITE_REQUEST,
            CONTROL_REQUEST,
        )
        val RESPONSES: Set<CafCommand> = setOf(
            CONFIG_RESPONSE,
            REGISTER_RESPONSE,
            UNREGISTER_RESPONSE,
            READ_RESPONSE,
            WRITE_RESPONSE,
            CONTROL_RESPONSE,
        )
        val NOTIFICATIONS: Set<CafCommand> = setOf(
            CONFIG_NOTIFY,
            CONTROL_NOTIFY,
            UPDATE_NOTIFY,
        )

        private val byWireName = entries.associateBy(CafCommand::wireName)

        fun find(wireName: String): CafCommand? = byWireName[wireName]

        fun require(wireName: String): CafCommand =
            requireNotNull(find(wireName)) { "Unknown CAF command '$wireName'" }
    }
}

sealed interface CafRegistration {
    data object Wildcard : CafRegistration

    data class Identifiers(val values: List<Long>) : CafRegistration {
        init {
            require(values.isNotEmpty()) { "register identifiers must not be empty" }
            require(values.distinct().size == values.size) { "register identifiers must be unique" }
        }
    }
}
