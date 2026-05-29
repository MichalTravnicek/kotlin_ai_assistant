package com.assistant.memory

import com.assistant.model.ClassificationResult
import com.assistant.model.IntentLabel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stores conversation history for multiple sessions.
 * Each session has a unique ID and keeps a list of exchange records.
 */
class ConversationMemory {

    private val sessions = mutableMapOf<String, MutableList<ExchangeRecord>>()
    private val mutex = Mutex()

    /**
     * Add a new exchange to a session.
     */
    suspend fun addExchange(sessionId: String, userMessage: String, modelResponse: String, classification: ClassificationResult) {
        mutex.withLock {
            val history = sessions.getOrPut(sessionId) { mutableListOf() }
            history.add(
                ExchangeRecord(
                    userMessage = userMessage,
                    modelResponse = modelResponse,
                    intentId = classification.intent.id,
                    confidence = classification.confidence
                )
            )
            // Keep only last 50 exchanges to avoid memory issues
            if (history.size > 50) {
                history.removeAt(0)
            }
        }
    }

    /**
     * Get conversation history for a session.
     */
    suspend fun getHistory(sessionId: String): List<ExchangeRecord> {
        mutex.withLock {
            return sessions[sessionId]?.toList() ?: emptyList()
        }
    }

    /**
     * Get last N exchanges for context-aware responses.
     */
    suspend fun getLastExchanges(sessionId: String, count: Int = 5): List<ExchangeRecord> {
        mutex.withLock {
            val history = sessions[sessionId] ?: return emptyList()
            return history.takeLast(count)
        }
    }

    /**
     * Get the most recent intent for context continuity.
     */
    suspend fun getLastIntent(sessionId: String): String? {
        mutex.withLock {
            return sessions[sessionId]?.lastOrNull()?.intentId
        }
    }

    /**
     * Check if this seems like a follow-up question to a previous exchange.
     */
    suspend fun isFollowUp(sessionId: String, message: String): Boolean {
        val history = getHistory(sessionId)
        if (history.isEmpty()) return false

        // If the message is short and lacks clear intent keywords, it's likely a follow-up
        val shortMessage = message.trim().split(" ").size <= 3
        val hasQuestionWords = listOf("what", "how", "why", "co", "jak", "proc", "kdy")
            .any { message.lowercase().contains(it) }

        return shortMessage || hasQuestionWords
    }

    /**
     * Clear session history.
     */
    suspend fun clearSession(sessionId: String) {
        mutex.withLock {
            sessions.remove(sessionId)
        }
    }
}

data class ExchangeRecord(
    val userMessage: String,
    val modelResponse: String,
    val intentId: String,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)