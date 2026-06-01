package com.assistant.routes.response

import kotlinx.serialization.Serializable

@Serializable
data class ChatResponse(
    val reply: String,
    val sessionId: String,
    val intent: String,
    val confidence: Float,
    val historyCount: Int,
    val modelInfo: String = "TinyNeuralNetwork v1.0 (pure Kotlin, ~320 parameters)"
)