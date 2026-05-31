package com.assistant.routes.response

import kotlinx.serialization.Serializable

@Serializable
data class HistoryEntry(
    val user: String,
    val assistant: String,
    val intent: String,
    val confidence: Float
)