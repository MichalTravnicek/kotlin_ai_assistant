package com.assistant.routes.response

import kotlinx.serialization.Serializable

@Serializable
data class StatusResponse(
    val status: String,
    val sessionCount: Int,
    val modelType: String,
    val vocabularySize: Int
)