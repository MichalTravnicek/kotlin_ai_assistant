package com.assistant.routes

import com.assistant.database.DatabaseManager
import com.assistant.database.SqlPromptInterpreter
import com.assistant.routes.request.SqlExecuteRequest
import com.assistant.routes.request.SqlQueryRequest
import com.assistant.routes.response.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

fun Route.sqlRoutes(db: DatabaseManager, interpreter: SqlPromptInterpreter) {
    val log = LoggerFactory.getLogger("com.assistant.routes.SqlRoutes")

    // POST /sql/query — natural language → SQL → results
    post("/sql/query") {
        val request = call.receive<SqlQueryRequest>()
        val prompt = request.prompt.trim()

        log.info("SQL | prompt='{}'", prompt)

        if (prompt.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Prompt cannot be empty"))
            return@post
        }

        val startTime = System.currentTimeMillis()

        try {
            val interpretation = interpreter.interpret(prompt)
            if (interpretation.sql.isBlank()) {
                // Help / examples case
                call.respond(
                    SqlQueryResponse(
                        sql = "",
                        explanation = interpretation.explanation,
                        columns = emptyList(),
                        rows = emptyList(),
                        rowCount = 0,
                        executionTimeMs = 0
                    )
                )
                return@post
            }

            val (columns, rows, rowCount) = db.executeQuery(interpretation.sql)
            val elapsed = System.currentTimeMillis() - startTime

            log.info("SQL | OK | rows={} time={}ms | sql='{}'", rowCount, elapsed, interpretation.sql.take(80))

            call.respond(
                SqlQueryResponse(
                    sql = interpretation.sql,
                    explanation = interpretation.explanation,
                    columns = columns,
                    rows = rows,
                    rowCount = rowCount,
                    executionTimeMs = elapsed
                )
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            log.error("SQL | ERROR | time={}ms | {}", elapsed, e.message)
            call.respond(
                HttpStatusCode.BadRequest,
                SqlQueryResponse(
                    sql = "",
                    explanation = "Error: ${e.message ?: "unknown error"}",
                    columns = emptyList(),
                    rows = emptyList(),
                    rowCount = 0,
                    executionTimeMs = elapsed
                )
            )
        }
    }

    // POST /sql/execute — raw SQL execution (power user mode)
    post("/sql/execute") {
        val request = call.receive<SqlExecuteRequest>()
        val sql = request.sql.trim()

        log.info("SQL RAW | sql='{}'", sql.take(120))

        if (sql.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "SQL cannot be empty"))
            return@post
        }

        val startTime = System.currentTimeMillis()
        try {
            val (columns, rows, rowCount) = db.executeQuery(sql)
            val elapsed = System.currentTimeMillis() - startTime
            log.info("SQL RAW | OK | rows={} time={}ms", rowCount, elapsed)
            call.respond(
                SqlQueryResponse(
                    sql = sql,
                    explanation = "Raw SQL execution",
                    columns = columns,
                    rows = rows,
                    rowCount = rowCount,
                    executionTimeMs = elapsed
                )
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            log.error("SQL RAW | ERROR | time={}ms | {}", elapsed, e.message)
            call.respond(
                HttpStatusCode.BadRequest,
                SqlQueryResponse(
                    sql = sql,
                    explanation = "Error: ${e.message ?: "unknown error"}",
                    columns = emptyList(),
                    rows = emptyList(),
                    rowCount = 0,
                    executionTimeMs = elapsed
                )
            )
        }
    }

    // GET /sql/schema — database schema
    get("/sql/schema") {
        val tables = db.getSchema().map { table ->
            SqlTableDef(
                name = table.name,
                columns = table.columns.map { SqlColumnDef(it.name, it.type) }
            )
        }
        call.respond(SqlSchemaResponse(tables = tables))
    }

    // GET /sql/prompts — example prompts
    get("/sql/prompts") {
        call.respond(SqlPromptsResponse(examples = db.examplePrompts()))
    }
}
