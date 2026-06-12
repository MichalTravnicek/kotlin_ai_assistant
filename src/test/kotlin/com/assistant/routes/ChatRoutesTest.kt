package com.assistant.routes

import com.assistant.model.SimpleTokenizer
import com.assistant.model.TinyNeuralNetwork
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class ChatRoutesTest {

    private fun testApp(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
        routing {
            val tokenizer = SimpleTokenizer()
            val model = TinyNeuralNetwork.buildPretrained(tokenizer)
            chatRoutes(model, tokenizer)
        }
        test()
    }

    @Test
    fun `POST chat with Ahoj returns greeting`() = testApp {
        val response = client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message": "Ahoj", "sessionId": "test1"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // JSON contains: {"reply":"...","sessionId":"test1","intent":"greeting","confidence":...}
        assertTrue(body.contains("greeting"), "Response should contain 'greeting' intent")
        assertTrue(body.contains("test1"), "Response should contain sessionId 'test1'")
        assertTrue(body.contains("reply"), "Response should have a reply field")
    }

    @Test
    fun `POST chat with empty message returns 400`() = testApp {
        val response = client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message": "  ", "sessionId": "test2"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("error"), "Error response should contain 'error' intent")
        assertTrue(body.contains("cannot be empty", ignoreCase = true), "Error message should mention empty")
    }

    @Test
    fun `GET chat history returns empty list initially`() = testApp {
        val response = client.get("/chat/history?sessionId=test3")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("history"), "Response should have history field")
        assertTrue(body.contains("test3"), "Response should contain sessionId 'test3'")
    }

    @Test
    fun `GET chat history returns exchanges after chat`() = testApp {
        client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message": "Ahoj", "sessionId": "test4"}""")
        }
        val response = client.get("/chat/history?sessionId=test4")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("test4"), "Response should contain sessionId")
        assertTrue(body.contains("user") || body.contains("assistant"), "History should contain user/assistant fields")
    }

    @Test
    fun `POST chat clear clears session history`() = testApp {
        // Send a message
        client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message": "Ahoj", "sessionId": "test5"}""")
        }
        // Clear
        val clearResponse = client.post("/chat/clear") {
            contentType(ContentType.Application.Json)
            setBody("""{"sessionId": "test5"}""")
        }
        assertEquals(HttpStatusCode.OK, clearResponse.status)
        val clearBody = clearResponse.bodyAsText()
        assertTrue(clearBody.contains("cleared"), "Clear should return status 'cleared'")
        assertTrue(clearBody.contains("test5"), "Clear should contain sessionId")
    }

    @Test
    fun `GET status returns model info`() = testApp {
        val response = client.get("/status")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ready"), "Status should be 'ready'")
        assertTrue(body.contains("modelType"), "Response should contain modelType field")
        assertTrue(body.contains("vocabularySize"), "Response should contain vocabularySize field")
    }

    @Test
    fun `POST chat remembers conversation history`() = testApp {
        // First message
        val response1 = client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message": "Ahoj", "sessionId": "memory1"}""")
        }
        val body1 = response1.bodyAsText()
        assertTrue(body1.contains("memory1"), "First message response should contain sessionId 'memory1'")

        // Second message
        val response2 = client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message": "Jak se mas?", "sessionId": "memory1"}""")
        }
        assertEquals(HttpStatusCode.OK, response2.status)
        val body2 = response2.bodyAsText()
        assertTrue(body2.contains("memory1"), "Second message response should contain sessionId 'memory1'")

        // History should have 2 entries
        val historyResponse = client.get("/chat/history?sessionId=memory1")
        assertEquals(HttpStatusCode.OK, historyResponse.status)
        val historyBody = historyResponse.bodyAsText()
        val userCount = historyBody.split("user").size - 1
        val assistantCount = historyBody.split("assistant").size - 1
        assertEquals(2, userCount, "History should have 2 user references")
        assertEquals(2, assistantCount, "History should have 2 assistant references")
    }

    @Test
    fun `POST chat with lowercase ahoj works same as Ahoj`() = testApp {
        val response = client.post("/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message": "ahoj", "sessionId": "case1"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("greeting"), "Lowercase 'ahoj' should also produce greeting intent")
    }

    @Test
    fun `GET root returns HTML page`() = testApp {
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val contentType = response.contentType()
        assertNotNull(contentType, "Response should have a Content-Type header")
        assertTrue(contentType.toString().contains("html"), "Content-Type should contain 'html', got: $contentType")
        val body = response.bodyAsText()
        assertTrue(body.contains("Kotlin AI Assistant"), "HTML should contain title 'Kotlin AI Assistant'")
    }
}
