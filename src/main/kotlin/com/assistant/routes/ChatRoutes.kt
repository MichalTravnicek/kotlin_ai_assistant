package com.assistant.routes

import com.assistant.memory.ConversationMemory
import com.assistant.model.SimpleTokenizer
import com.assistant.model.TinyNeuralNetwork
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ChatRequest(
    val message: String,
    val sessionId: String = "default"
)

@Serializable
data class ChatResponse(
    val reply: String,
    val sessionId: String,
    val intent: String,
    val confidence: Float,
    val historyCount: Int,
    val modelInfo: String = "TinyNeuralNetwork v1.0 (pure Kotlin, ~320 parameters)"
)

@Serializable
data class HistoryRequest(
    val sessionId: String = "default"
)

@Serializable
data class ClearRequest(
    val sessionId: String = "default"
)

@Serializable
data class StatusResponse(
    val status: String,
    val sessionCount: Int,
    val modelType: String,
    val vocabularySize: Int
)

fun Route.chatRoutes(model: TinyNeuralNetwork, tokenizer: SimpleTokenizer) {
    val memory = ConversationMemory()

    // POST /chat — send a message to the assistant
    post("/chat") {
        val request = call.receive<ChatRequest>()
        val sessionId = request.sessionId
        val message = request.message.trim()

        if (message.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ChatResponse(
                reply = "Message cannot be empty.",
                sessionId = sessionId,
                intent = "error",
                confidence = 1.0f,
                historyCount = 0
            ))
            return@post
        }

        // Tokenize and classify
        val tokens = tokenizer.tokenize(message)
        val classification = model.predict(tokens)

        // Check for follow-up context
        val lastIntent = memory.getLastIntent(sessionId)
        val isFollowUp = memory.isFollowUp(sessionId, message)

        // Generate response with context awareness
        val responseText = if (isFollowUp && lastIntent != null && classification.confidence < 0.6f) {
            // Low confidence + follow-up → continue previous context
            val history = memory.getLastExchanges(sessionId, 2)
            if (history.isNotEmpty()) {
                val lastResponse = history.last().modelResponse
                "Ve vazbě na předchozí otázku: $lastResponse\n\n" +
                "Můžete mi prosím upřesnit, co přesně vás zajímá?"
            } else {
                classification.intent.response
            }
        } else {
            classification.intent.response
        }

        // Add confidence feedback for low-confidence results
        val finalResponse = if (classification.confidence < 0.3f) {
            "$responseText\n\n(Poznámka: rozpoznal jsem váš dotaz s nízkou jistotou ${"%.0f".format(classification.confidence * 100)}%. Zkuste být konkrétnější.)"
        } else {
            responseText
        }

        // Store in memory
        memory.addExchange(sessionId, message, finalResponse, classification)

        // Get updated history count
        val historyCount = memory.getHistory(sessionId).size

        // Response
        call.respond(ChatResponse(
            reply = finalResponse,
            sessionId = sessionId,
            intent = classification.intent.id,
            confidence = classification.confidence,
            historyCount = historyCount
        ))
    }

    // GET /chat/history — get conversation history
    get("/chat/history") {
        val sessionId = call.request.queryParameters["sessionId"] ?: "default"
        val history = memory.getHistory(sessionId)
        call.respond(mapOf(
            "sessionId" to sessionId,
            "history" to history.map { record ->
                mapOf(
                    "user" to record.userMessage,
                    "assistant" to record.modelResponse,
                    "intent" to record.intentId,
                    "confidence" to record.confidence
                )
            }
        ))
    }

    // POST /chat/clear — clear session history
    post("/chat/clear") {
        val request = call.receive<ClearRequest>()
        memory.clearSession(request.sessionId)
        call.respond(mapOf("status" to "cleared", "sessionId" to request.sessionId))
    }

    // GET /status — model info
    get("/status") {
        val allSessions = try {
            // We'd need introspection, but let's just return status
            0
        } catch (e: Exception) { 0 }
        call.respond(StatusResponse(
            status = "ready",
            sessionCount = 0,
            modelType = "TinyNeuralNetwork (embed=${
                try { 16 } catch (e: Exception) { 0 }
            })",
            vocabularySize = tokenizer.vocabularySize()
        ))
    }
}