package com.assistant.routes.response

import kotlinx.serialization.Serializable

@Serializable
data class SqlQueryResponse(
    val sql: String,
    val explanation: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    val rowCount: Int,
    val executionTimeMs: Long
)

@Serializable
data class SqlSchemaResponse(
    val tables: List<SqlTableDef>
)

@Serializable
data class SqlTableDef(
    val name: String,
    val columns: List<SqlColumnDef>
)

@Serializable
data class SqlColumnDef(
    val name: String,
    val type: String
)

@Serializable
data class SqlPromptsResponse(
    val examples: List<String>
)
