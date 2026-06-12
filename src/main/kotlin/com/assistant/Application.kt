package com.assistant

import com.assistant.database.DatabaseManager
import com.assistant.database.SqlPromptInterpreter
import com.assistant.model.TinyNeuralNetwork
import com.assistant.model.SimpleTokenizer
import com.assistant.routes.chatRoutes
import com.assistant.routes.sqlRoutes
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import kotlinx.serialization.json.Json

fun main() {
    println("Loading AI assistant model...")

    // Initialize the tiny neural network model
    val tokenizer = SimpleTokenizer()
    val model = TinyNeuralNetwork.buildPretrained(tokenizer)

    // Initialize the embedded database and SQL interpreter
    println("Initializing database...")
    val db = DatabaseManager()
    val sqlInterpreter = SqlPromptInterpreter { db.getSchema() }

    println("Starting Ktor server on http://localhost:8080")
    println("  Chat API:       POST http://localhost:8080/chat")
    println("  SQL prompt API:  POST http://localhost:8080/sql/query")
    println("  SQL raw API:     POST http://localhost:8080/sql/execute")
    println("  SQL schema:      GET  http://localhost:8080/sql/schema")
    println("  Web UI:          GET  http://localhost:8080")

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }

        routing {
            chatRoutes(model, tokenizer)
            sqlRoutes(db, sqlInterpreter)
        }
    }.start(wait = true)
}
