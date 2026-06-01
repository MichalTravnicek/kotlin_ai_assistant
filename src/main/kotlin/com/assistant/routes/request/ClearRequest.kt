package com.assistant.routes.request

import kotlinx.serialization.Serializable

@Serializable
data class ClearRequest(
    val sessionId: String = "default"
)