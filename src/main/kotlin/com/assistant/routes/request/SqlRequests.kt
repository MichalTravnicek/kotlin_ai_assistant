package com.assistant.routes.request

import kotlinx.serialization.Serializable

@Serializable
data class SqlQueryRequest(
    val prompt: String,
    val sessionId: String = "default"
)

@Serializable
data class SqlExecuteRequest(
    val sql: String
)
