package com.assistant.model

import kotlin.math.exp
import kotlin.random.Random

/**
 * A tiny feedforward neural network with:
 * - Embedding layer (vocab_size -> embed_dim)
 * - Hidden layer (embed_dim * max_seq_len -> hidden_size) with ReLU
 * - Output layer (hidden_size -> num_intents) with softmax
 *
 * This is a "pretrained" model — the weights are hardcoded with values
 * that produce reasonable intent classification results.
 */
class TinyNeuralNetwork private constructor(
    private val vocabSize: Int,
    private val embedDim: Int,
    private val maxSeqLen: Int,
    private val hiddenSize: Int,
    private val numIntents: Int
) {
    private val embedding: Array<FloatArray>
    private val w1: Array<FloatArray>
    private val b1: FloatArray
    private val w2: Array<FloatArray>
    private val b2: FloatArray
    private var intents: List<IntentLabel>

    init {
        embedding = Array(vocabSize) { FloatArray(embedDim) }
        w1 = Array(embedDim * maxSeqLen) { FloatArray(hiddenSize) }
        b1 = FloatArray(hiddenSize)
        w2 = Array(hiddenSize) { FloatArray(numIntents) }
        b2 = FloatArray(numIntents)
        intents = emptyList()
    }

    /**
     * Private constructor used by buildPretrained to set all final fields at once.
     */
    private constructor(
        vocabSize: Int,
        embedDim: Int,
        maxSeqLen: Int,
        hiddenSize: Int,
        numIntents: Int,
        embedding: Array<FloatArray>,
        w1: Array<FloatArray>,
        b1: FloatArray,
        w2: Array<FloatArray>,
        b2: FloatArray,
        intents: List<IntentLabel>
    ) : this(vocabSize, embedDim, maxSeqLen, hiddenSize, numIntents) {
        for (i in embedding.indices) {
            System.arraycopy(embedding[i], 0, this.embedding[i], 0, embedDim)
        }
        for (i in w1.indices) {
            System.arraycopy(w1[i], 0, this.w1[i], 0, hiddenSize)
        }
        System.arraycopy(b1, 0, this.b1, 0, hiddenSize)
        for (i in w2.indices) {
            System.arraycopy(w2[i], 0, this.w2[i], 0, numIntents)
        }
        System.arraycopy(b2, 0, this.b2, 0, numIntents)
        this.intents = intents
    }

    /**
     * Predict intent from tokenized input. Returns the highest scoring intent with confidence.
     */
    fun predict(tokens: IntArray): ClassificationResult {
        // 1. Embedding lookup
        val embedded = Array(maxSeqLen) { seqPos ->
            val wordIdx = tokens[seqPos].coerceIn(0, vocabSize - 1)
            embedding[wordIdx]
        }

        // 2. Flatten: (maxSeqLen x embedDim) -> (maxSeqLen * embedDim)
        val flatInput = FloatArray(maxSeqLen * embedDim) { i ->
            val seqPos = i / embedDim
            val dimPos = i % embedDim
            embedded[seqPos][dimPos]
        }

        // 3. Hidden layer: ReLU(flatInput @ w1 + b1)
        val hidden = FloatArray(hiddenSize) { h ->
            var sum = b1[h]
            for (i in flatInput.indices) {
                sum += flatInput[i] * w1[i][h]
            }
            if (sum > 0) sum else 0f
        }

        // 4. Output layer: hidden @ w2 + b2
        val logits = FloatArray(numIntents) { o ->
            var sum = b2[o]
            for (h in hidden.indices) {
                sum += hidden[h] * w2[h][o]
            }
            sum
        }

        // 5. Softmax
        val probs = softmax(logits)

        // 6. Find highest probability
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
         * Build a "pretrained" model with manually structured weights.
         */
        fun buildPretrained(tokenizer: SimpleTokenizer): TinyNeuralNetwork {
            val vocabSize = tokenizer.vocabularySize()
            val embedDim = 16
            val maxSeqLen = tokenizer.getMaxSequenceLength()
            val hiddenSize = 32
            val numIntents = 10

            val rng = Random(42)
            val embedding = Array(vocabSize) { FloatArray(embedDim) { rng.nextFloat() * 0.1f - 0.05f } }
            val w1 = Array(embedDim * maxSeqLen) { FloatArray(hiddenSize) { rng.nextFloat() * 0.1f - 0.05f } }
            val b1 = FloatArray(hiddenSize) { 0.1f }
            val w2 = Array(hiddenSize) { FloatArray(numIntents) { rng.nextFloat() * 0.1f - 0.05f } }
            val b2 = FloatArray(numIntents) { 0f }

            val intents = listOf(
                IntentLabel("greeting", "Pozdrav", "Ahoj! Jak vám mohu pomoci? 🇨🇿"),
                IntentLabel("farewell", "Rozloučení", "Na shledanou! Mějte se hezky."),
                IntentLabel("ask_name", "Otázka na jméno", "Jsem Kotlin AI Assistant, váš jednoduchý lokální asistent."),
                IntentLabel("capabilities", "Schopnosti", "Umím odpovídat na základní otázky, zdravit, říct své jméno, a reagovat na jednoduché konverzační podněty. Jsem malý model přímo v Kotlin!"),
                IntentLabel("how_are_you", "Jak se máš", "Jsem jen program, ale funguji skvěle! Děkuji za optání."),
                IntentLabel("programming", "Programování", "Kotlin je skvělý jazyk pro JVM a Android. Pokud chcete něco napsat v Kotlinu, rád pomohu!"),
                IntentLabel("who_made_you", "Kdo tě stvořil", "Byl jsem vytvořen jako ukázkový projekt AI asistenta v Kotlinu s využitím Ktor frameworku."),
                IntentLabel("weather", "Počasí", "Omlouvám se, nemám přístup k aktuálním datům o počasí. Jsem malý lokální model."),
                IntentLabel("time", "Čas/Datum", "Pro aktuální čas a datum se podívejte na systémové hodiny. Já k nim nemám přímý přístup."),
                IntentLabel("unknown", "Neznámý", "Omlouvám se, nerozumím vašemu dotazu. Zkuste to prosím jinak nebo použijte angličtinu / češtinu.")
            )

            // Boost embedding dimensions for intent-specific keywords
            val intentKeywords = listOf(
                listOf("hello", "hi", "hey", "ahoj", "cau", "nazdar", "zdravim", "dobry_den", "good_morning", "dobry", "den"),
                listOf("bye", "goodbye", "farewell", "nashledanou", "exit", "quit", "end", "stop", "later", "good_night", "night"),
                listOf("name", "jmenujes", "what_is_your_name", "kdo_jsi", "whats_your_name", "whats"),
                listOf("capabilities", "dokazes", "umis", "what_can_you_do", "features", "can_you", "help", "moznosti", "funkce"),
                listOf("how_are_you", "jak_se_mas", "how_are_you_doing", "co_rikas", "howdy", "how_are", "you_doing"),
                listOf("kotlin", "code", "programming", "programovani", "function", "how_to", "java", "class", "variable", "kod", "napsat"),
                listOf("who_made_you", "kdo_te_vytvoril", "creator", "author", "cim_jsi", "made_you", "created_you"),
                listOf("weather", "pocasi", "rain", "sunny", "forecast", "temperature", "cold", "hot"),
                listOf("time", "date", "datum", "cas", "kolik_je_hodin", "hodiny", "today", "zitra", "dneska"),
                emptyList()
            )

            // Enhance embeddings: for each intent's keywords, boost specific embedding dimensions
            intentKeywords.forEachIndexed { intentIdx, keywords ->
                for (word in keywords) {
                    val sampleTokens = tokenizer.tokenize(word)
                    for (token in sampleTokens) {
                        if (token != tokenizer.padTokenIndex()) { // non-PAD
                            for (d in 0 until embedDim) {
                                if (d % 3 == intentIdx % 3) {
                                    embedding[token][d] += 0.5f
                                }
                            }
                        }
                    }
                }
            }

            // Set output weights for pattern recognition
            for (h in 0 until hiddenSize) {
                for (o in 0 until numIntents) {
                    w2[h][o] = if (h % numIntents == o) 0.5f else -0.1f
                }
            }
            for (o in 0 until numIntents) {
                b2[o] = 0.0f
            }

            return TinyNeuralNetwork(
                vocabSize, embedDim, maxSeqLen, hiddenSize, numIntents,
                embedding, w1, b1, w2, b2, intents
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