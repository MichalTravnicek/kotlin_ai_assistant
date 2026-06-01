package com.assistant.routes.response

import kotlinx.serialization.Serializable

@Serializable
data class HistoryResponse(
    val sessionId: String,
    val history: List<HistoryEntry>
)