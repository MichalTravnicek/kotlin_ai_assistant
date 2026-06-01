package com.assistant.model

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TinyNeuralNetworkTest {

    @Test
    fun `hello world test - model loads and responds to greeting`() {
        val tokenizer = SimpleTokenizer()
        val model = TinyNeuralNetwork.buildPretrained(tokenizer)

        assertNotNull(model, "Model should be created")
    }

    @Test
    fun `greeting ahoj returns high confidence greeting`() {
        val tokenizer = SimpleTokenizer()
        val model = TinyNeuralNetwork.buildPretrained(tokenizer)

        val tokens = tokenizer.tokenize("Ahoj")
        val result = model.predict(tokens)

        assertEquals("greeting", result.intent.id, "\"Ahoj\" should be classified as greeting")
        assertTrue(result.confidence > 0.5f, "\"Ahoj\" confidence should be > 50%, was ${result.confidence}")
    }

    @Test
    fun `farewell nashledanou returns high confidence farewell`() {
        val tokenizer = SimpleTokenizer()
        val model = TinyNeuralNetwork.buildPretrained(tokenizer)

        val tokens = tokenizer.tokenize("nashledanou")
        val result = model.predict(tokens)

        assertEquals("farewell", result.intent.id, "\"nashledanou\" should be classified as farewell")
        assertTrue(result.confidence > 0.5f, "\"nashledanou\" confidence should be > 50%, was ${result.confidence}")
    }

    @Test
    fun `unknown text returns unknown intent`() {
        val tokenizer = SimpleTokenizer()
        val model = TinyNeuralNetwork.buildPretrained(tokenizer)

        val tokens = tokenizer.tokenize("xyzzy flurbo garble")
        val result = model.predict(tokens)

        assertEquals("unknown", result.intent.id, "Gibberish should be classified as unknown")
    }

    @Test
    fun `multiple greetings all classified correctly`() {
        val tokenizer = SimpleTokenizer()
        val model = TinyNeuralNetwork.buildPretrained(tokenizer)

        val greetings = listOf("hello", "hi", "ahoj", "cau", "nazdar", "zdravim", "dobry den")

        for (greeting in greetings) {
            val tokens = tokenizer.tokenize(greeting)
            val result = model.predict(tokens)
            assertEquals("greeting", result.intent.id, "\"$greeting\" should be greeting, was ${result.intent.id}")
            assertTrue(result.confidence > 0.5f, "\"$greeting\" confidence should be > 50%, was ${result.confidence}")
        }
    }
}