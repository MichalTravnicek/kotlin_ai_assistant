package com.assistant.routes

import com.assistant.memory.ConversationMemory
import com.assistant.model.SimpleTokenizer
import com.assistant.model.TinyNeuralNetwork
import com.assistant.routes.request.ChatRequest
import com.assistant.routes.request.ClearRequest
import com.assistant.routes.response.ChatResponse
import com.assistant.routes.response.ClearResponse
import com.assistant.routes.response.HistoryEntry
import com.assistant.routes.response.HistoryResponse
import com.assistant.routes.response.StatusResponse
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

fun Route.chatRoutes(model: TinyNeuralNetwork, tokenizer: SimpleTokenizer) {
    val memory = ConversationMemory()
    val log = LoggerFactory.getLogger("com.assistant.routes.ChatRoutes")

    // POST /chat — send a message to the assistant
    post("/chat") {
        val request = call.receive<ChatRequest>()
        val sessionId = request.sessionId
        val message = request.message.trim()

        log.info(">>> [{}] REQUEST: message='{}'", sessionId, message)

        if (message.isBlank()) {
            log.warn("<<< [{}] ERROR: empty message", sessionId)
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

        log.info(">>> [{}] MODEL: intent='{}' confidence={}", sessionId, classification.intent.id, "%.2f".format(classification.confidence))

        // Check for follow-up context
        val lastIntent = memory.getLastIntent(sessionId)
        val isFollowUp = memory.isFollowUp(sessionId, message)

        log.debug(">>> [{}] CONTEXT: isFollowUp={} lastIntent={}", sessionId, isFollowUp, lastIntent)

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

        log.info("<<< [{}] RESPONSE: intent='{}' confidence={} historySize={} reply='{}'",
            sessionId, classification.intent.id, "%.2f".format(classification.confidence), historyCount,
            finalResponse.take(80).replace("\n", "\\n"))

        // Response
        call.respond(
            ChatResponse(
                reply = finalResponse,
                sessionId = sessionId,
                intent = classification.intent.id,
                confidence = classification.confidence,
                historyCount = historyCount
            )
        )
    }

    // GET /chat/history — get conversation history
    get("/chat/history") {
        val sessionId = call.request.queryParameters["sessionId"] ?: "default"
        log.info(">>> [{}] GET /chat/history", sessionId)
        val history = memory.getHistory(sessionId)
        log.info("<<< [{}] HISTORY: {} exchanges", sessionId, history.size)
        call.respond(
            HistoryResponse(
            sessionId = sessionId,
            history = history.map { record ->
                HistoryEntry(
                    user = record.userMessage,
                    assistant = record.modelResponse,
                    intent = record.intentId,
                    confidence = record.confidence
                )
            }
        ))
    }

    // POST /chat/clear — clear session history
    post("/chat/clear") {
        val request = call.receive<ClearRequest>()
        log.info(">>> [{}] POST /chat/clear", request.sessionId)
        memory.clearSession(request.sessionId)
        log.info("<<< [{}] CLEARED", request.sessionId)
        call.respond(ClearResponse(status = "cleared", sessionId = request.sessionId))
    }

    // GET /status — model info
    get("/status") {
        log.info(">>> GET /status")
        call.respond(
            StatusResponse(
                status = "ready",
                sessionCount = 0,
                modelType = "TinyNeuralNetwork (embed=16)",
                vocabularySize = tokenizer.vocabularySize()
            )
        )
    }
}