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
            assertTrue(result.confidence > 0.8f, "\"$greeting\" confidence should be > 80%, was ${result.confidence}")
        }
    }

    @Test
    fun `jak se mas returns how_are_you with high confidence`() {
        val tokenizer = SimpleTokenizer()
        val model = TinyNeuralNetwork.buildPretrained(tokenizer)

        val tokens = tokenizer.tokenize("jak se mas")
        val result = model.predict(tokens)

        assertEquals("how_are_you", result.intent.id, "\"jak se mas\" should be how_are_you, was ${result.intent.id}")
        assertTrue(result.confidence > 0.9f, "\"jak se mas\" confidence should be > 90%, was ${result.confidence}")
    }

    @Test
    fun `co umis returns capabilities with high confidence`() {
        val tokenizer = SimpleTokenizer()
        val model = TinyNeuralNetwork.buildPretrained(tokenizer)

        val tokens = tokenizer.tokenize("co umis")
        val result = model.predict(tokens)

        assertEquals("capabilities", result.intent.id, "\"co umis\" should be capabilities, was ${result.intent.id}")
        assertTrue(result.confidence > 0.9f, "\"co umis\" confidence should be > 90%, was ${result.confidence}")
    }

    @Test
    fun `kdo te stvoril returns who_made_you with high confidence`() {
        val tokenizer = SimpleTokenizer()
        val model = TinyNeuralNetwork.buildPretrained(tokenizer)

        val tokens = tokenizer.tokenize("kdo te stvoril")
        val result = model.predict(tokens)

        assertEquals(
            "who_made_you",
            result.intent.id,
            "\"kdo te stvoril\" should be who_made_you, was ${result.intent.id}"
        )
        assertTrue(result.confidence > 0.9f, "\"kdo te stvoril\" confidence should be > 90%, was ${result.confidence}")
    }
}
