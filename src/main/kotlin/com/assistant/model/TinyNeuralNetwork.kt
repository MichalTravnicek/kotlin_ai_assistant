package com.assistant.model

import kotlin.math.exp

/**
 * A real feedforward neural network for intent classification.
 *
 * Architecture:
 *   vocab → Embedding(vocabSize × embedDim) → Flatten(maxSeqLen * embedDim)
 *   → Dense(hiddenSize, ReLU) → Dense(numIntents, softmax)
 *
 * Weights are precomputed ("pretrained") so known keywords produce
 * strong activation in the correct output neuron.
 */
class TinyNeuralNetwork private constructor(
    private val vocabSize: Int,
    private val embedDim: Int,
    private val maxSeqLen: Int,
    private val hiddenSize: Int,
    private val numIntents: Int,
    private val intents: List<IntentLabel>,
    private val embedding: Array<FloatArray>,    // vocabSize × embedDim
    private val w1: Array<FloatArray>,            // (maxSeqLen*embedDim) × hiddenSize
    private val b1: FloatArray,                   // hiddenSize
    private val w2: Array<FloatArray>,            // hiddenSize × numIntents
    private val b2: FloatArray                    // numIntents
) {
    fun predict(tokens: IntArray): ClassificationResult {
        // 1. Embedding lookup: (maxSeqLen) → (maxSeqLen × embedDim)
        val embedded = Array(maxSeqLen) { p ->
            val idx = tokens[p].coerceIn(0, vocabSize - 1)
            embedding[idx]
        }

        // 2. Flatten: (maxSeqLen × embedDim) → (maxSeqLen * embedDim)
        val flatSize = maxSeqLen * embedDim
        val flatInput = FloatArray(flatSize) { i ->
            val p = i / embedDim
            val d = i % embedDim
            embedded[p][d]
        }

        // 3. Hidden layer: ReLU(flatInput @ w1 + b1)
        val hidden = FloatArray(hiddenSize) { h ->
            var sum = b1[h]
            for (i in 0 until flatSize) {
                sum += flatInput[i] * w1[i][h]
            }
            if (sum > 0) sum else 0f // ReLU
        }

        // 4. Output layer: hidden @ w2 + b2
        val logits = FloatArray(numIntents) { o ->
            var sum = b2[o]
            for (h in 0 until hiddenSize) {
                sum += hidden[h] * w2[h][o]
            }
            sum
        }

        // 5. Unknown-word guard: if no keyword was activated, boost unknown intent
        val hiddenEnergy = hidden.sum()
        if (hiddenEnergy < 0.1f) {
            logits[numIntents - 1] += 5.0f
            for (i in 0 until numIntents - 1) {
                logits[i] -= 0.5f
            }
        }

        // 6. Softmax
        val probs = softmax(logits)

        // 7. Find best intent
        var bestIdx = 0
        var bestProb = probs[0]
        for (i in 1 until probs.size) {
            if (probs[i] > bestProb) {
                bestProb = probs[i]
                bestIdx = i
            }
        }

        val intent = if (bestIdx < intents.size) intents[bestIdx] else intents.last()
        return ClassificationResult(intent, bestProb)
    }

    private fun softmax(x: FloatArray): FloatArray {
        val maxVal = x.max()
        val expX = FloatArray(x.size) { i -> exp((x[i] - maxVal).toDouble()).toFloat() }
        val sumExp = expX.sum()
        return FloatArray(x.size) { i -> expX[i] / sumExp }
    }

    companion object {
        /**
         * Builds a pretrained neural network with hand-crafted weights.
         *
         * Each known word gets an embedding vector where the dimension matching
         * its intent index carries the vote strength. The weight matrices w1 and w2
         * are structured so that these signals propagate to the correct output.
         */
        fun buildPretrained(tokenizer: SimpleTokenizer): TinyNeuralNetwork {
            val vocabSize = tokenizer.vocabularySize()
            val embedDim = 16
            val maxSeqLen = tokenizer.getMaxSequenceLength()
            val hiddenSize = 32
            val numIntents = 10

            val intents = listOf(
                IntentLabel("greeting", "Pozdrav", "Ahoj! Jak vám mohu pomoci? 🇨🇿"),
                IntentLabel("farewell", "Rozloučení", "Na shledanou! Mějte se hezky."),
                IntentLabel(
                    "ask_name",
                    "Otázka na jméno",
                    "Jsem Kotlin AI Assistant, váš jednoduchý lokální asistent."
                ),
                IntentLabel(
                    "capabilities",
                    "Schopnosti",
                    "Umím odpovídat na základní otázky, zdravit, říct své jméno, a reagovat na jednoduché konverzační podněty. Jsem malý model přímo v Kotlin!"
                ),
                IntentLabel("how_are_you", "Jak se máš", "Jsem jen program, ale funguji skvěle! Děkuji za optání."),
                IntentLabel(
                    "programming",
                    "Programování",
                    "Kotlin je skvělý jazyk pro JVM a Android. Pokud chcete něco napsat v Kotlinu, rád pomohu!"
                ),
                IntentLabel(
                    "who_made_you",
                    "Kdo tě stvořil",
                    "Byl jsem vytvořen jako ukázkový projekt AI asistenta v Kotlinu s využitím Ktor frameworku."
                ),
                IntentLabel(
                    "weather",
                    "Počasí",
                    "Omlouvám se, nemám přístup k aktuálním datům o počasí. Jsem malý lokální model."
                ),
                IntentLabel(
                    "time",
                    "Čas/Datum",
                    "Pro aktuální čas a datum se podívejte na systémové hodiny. Já k nim nemám přímý přístup."
                ),
                IntentLabel(
                    "unknown",
                    "Neznámý",
                    "Omlouvám se, nerozumím vašemu dotazu. Zkuste to prosím jinak nebo použijte angličtinu / češtinu."
                )
            )

            val GREETING = 0; val FAREWELL = 1; val ASK_NAME = 2
            val CAPABILITIES = 3; val HOW_ARE_YOU = 4; val PROGRAMMING = 5
            val WHO_MADE_YOU = 6; val WEATHER = 7; val TIME = 8; val UNKNOWN = 9

            // ---- Embeddings ----
            // For each word w with intent i and strength s:
            //   embedding[w][i % embedDim] = s     (i in 0..9 → dims 0..9)
            //   all other dims = 0
            // For unknown words: all zeros
            val embedding = Array(vocabSize) { FloatArray(embedDim) { 0f } }
            val unkIdx = tokenizer.vocabularySize() - 1
            val padIdx = tokenizer.padTokenIndex()

            fun setWord(word: String, intentIdx: Int, strength: Float) {
                val tokens = tokenizer.tokenize(word)
                for (token in tokens) {
                    if (token != padIdx && token != unkIdx) {
                        // Put vote into the embedding dimension matching the intent
                        embedding[token][intentIdx % embedDim] = strength
                    }
                }
            }

            // Greeting
            setWord("hello", GREETING, 2.0f); setWord("hi", GREETING, 2.0f)
            setWord("hey", GREETING, 2.0f); setWord("ahoj", GREETING, 3.0f)
            setWord("cau", GREETING, 2.5f); setWord("nazdar", GREETING, 2.5f)
            setWord("zdravim", GREETING, 2.0f); setWord("good", GREETING, 1.5f)
            setWord("morning", GREETING, 1.5f); setWord("evening", GREETING, 1.5f)
            setWord("dobry", GREETING, 1.5f); setWord("den", GREETING, 1.0f)

            // Farewell
            setWord("bye", FAREWELL, 2.5f); setWord("goodbye", FAREWELL, 2.5f)
            setWord("farewell", FAREWELL, 2.0f); setWord("nashledanou", FAREWELL, 3.0f)
            setWord("night", FAREWELL, 1.5f); setWord("later", FAREWELL, 1.5f)
            setWord("see", FAREWELL, 1.0f)

            // Ask name
            setWord("name", ASK_NAME, 2.5f); setWord("jmenujes", ASK_NAME, 3.0f)
            setWord("whats", ASK_NAME, 2.0f); setWord("call", ASK_NAME, 1.5f)
            setWord("kdo", ASK_NAME, 1.5f); setWord("jsi", ASK_NAME, 1.0f)

            // Capabilities
            setWord("capabilities", CAPABILITIES, 2.5f); setWord("features", CAPABILITIES, 2.0f)
            setWord("dokazes", CAPABILITIES, 2.5f); setWord("umis", CAPABILITIES, 3.0f)
            setWord("umi", CAPABILITIES, 2.0f); setWord("moznosti", CAPABILITIES, 2.0f)
            setWord("funkce", CAPABILITIES, 1.5f); setWord("help", CAPABILITIES, 1.5f)

            // How are you
            setWord("jak_se_mas", HOW_ARE_YOU, 3.0f); setWord("howdy", HOW_ARE_YOU, 2.5f)
            setWord("co_rikas", HOW_ARE_YOU, 2.5f); setWord("doing", HOW_ARE_YOU, 2.0f)
            setWord("mas", HOW_ARE_YOU, 2.0f)

            // Programming
            setWord("kotlin", PROGRAMMING, 3.0f); setWord("programming", PROGRAMMING, 2.5f)
            setWord("java", PROGRAMMING, 2.5f); setWord("function", PROGRAMMING, 2.5f)
            setWord("programovani", PROGRAMMING, 3.0f); setWord("kod", PROGRAMMING, 2.5f)
            setWord("napsat", PROGRAMMING, 2.0f); setWord("variable", PROGRAMMING, 2.0f)
            setWord("class", PROGRAMMING, 2.0f); setWord("code", PROGRAMMING, 2.0f)
            setWord("how_to", PROGRAMMING, 2.0f); setWord("promenna", PROGRAMMING, 2.0f)
            setWord("trida", PROGRAMMING, 2.0f); setWord("vytvorit", PROGRAMMING, 1.5f)

            // Who made you
            setWord("who_made_you", WHO_MADE_YOU, 3.0f); setWord("kdo_te", WHO_MADE_YOU, 3.0f)
            setWord("vytvoril", WHO_MADE_YOU, 2.5f); setWord("creator", WHO_MADE_YOU, 2.5f)
            setWord("author", WHO_MADE_YOU, 2.0f); setWord("cim_jsi", WHO_MADE_YOU, 2.0f)
            setWord("made", WHO_MADE_YOU, 2.0f); setWord("created", WHO_MADE_YOU, 2.0f)

            // Weather
            setWord("weather", WEATHER, 2.5f); setWord("pocasi", WEATHER, 3.0f)
            setWord("temperature", WEATHER, 2.0f); setWord("forecast", WEATHER, 2.5f)
            setWord("teplota", WEATHER, 2.0f); setWord("rain", WEATHER, 2.0f)
            setWord("sunny", WEATHER, 2.0f); setWord("cold", WEATHER, 1.5f)
            setWord("hot", WEATHER, 1.5f)

            // Time
            setWord("time", TIME, 2.0f); setWord("date", TIME, 2.0f)
            setWord("datum", TIME, 2.5f); setWord("cas", TIME, 2.5f)
            setWord("hodiny", TIME, 2.5f); setWord("kolik_je", TIME, 3.0f)
            setWord("today", TIME, 1.5f); setWord("dneska", TIME, 1.5f)
            setWord("zitra", TIME, 1.5f)

            // ---- Hidden weights w1: (maxSeqLen*embedDim) × hiddenSize ----
            // Hidden h (0..9) collects all flat-input values from embedding
            // dimension h (the dimension that matches intent h)
            // Hidden h (10..31) are unused (remain zero)
            val flatSize = maxSeqLen * embedDim
            val w1 = Array(flatSize) { i -> FloatArray(hiddenSize) { 0f } }
            for (flatIdx in 0 until flatSize) {
                val embedDimIdx = flatIdx % embedDim
                for (h in 0 until numIntents) {
                    // Hidden neuron h listens to embed dimension h
                    if (embedDimIdx == h) {
                        w1[flatIdx][h] = 1.0f
                    }
                }
            }
            val b1 = FloatArray(hiddenSize) { 0f }

            // ---- Output weights w2: hiddenSize × numIntents ----
            // Amplify matched signals: w2 = 2.0 for identity path
            val w2 = Array(hiddenSize) { h ->
                FloatArray(numIntents) { o ->
                    if (h < numIntents && h == o) 2.0f else 0.0f
                }
            }
            val b2 = FloatArray(numIntents) { 0f }

            return TinyNeuralNetwork(
                vocabSize, embedDim, maxSeqLen, hiddenSize, numIntents,
                intents, embedding, w1, b1, w2, b2
            )
        }
    }
}

data class IntentLabel(
    val id: String,
    val label: String,
    val response: String
)

data class ClassificationResult(
    val intent: IntentLabel,
    val confidence: Float
)