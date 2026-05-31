package com.assistant.routes.response

import kotlinx.serialization.Serializable

@Serializable
data class ClearResponse(
    val status: String,
    val sessionId: String
)