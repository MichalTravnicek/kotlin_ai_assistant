package com.assistant

import com.assistant.model.TinyNeuralNetwork
import com.assistant.model.SimpleTokenizer
import com.assistant.routes.chatRoutes
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

    println("Starting Ktor server on http://localhost:8080")
    println("AI Assistant is ready. Send POST requests to http://localhost:8080/chat")

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
        }
    }.start(wait = true)
}