package com.assistant.model

/**
 * A simple vocabulary-based tokenizer that maps words to indices.
 * Built from a predefined vocabulary of common English + Czech words
 * that the tiny model was "trained" to recognize.
 */
class SimpleTokenizer {

    private val wordToIndex: MutableMap<String, Int> = mutableMapOf()
    private val indexToWord: MutableMap<Int, String> = mutableMapOf()
    private var nextIndex = 0
    private val maxSequenceLength = 20

    init {
        // Build the vocabulary
        val vocabulary = listOf(
            // Greetings
            "hello", "hi", "hey", "ahoj", "cau", "zdravim", "good", "morning", "afternoon",
            "evening", "dobry", "den", "dobry_den", "nazdar",

            // Farewells
            "bye", "goodbye", "farewell", "see", "you", "later", "cau_nazdar", "nashledanou",
            "night", "good_night", "exit", "quit", "end", "stop",

            // Name-related
            "name", "your", "whats", "what", "is", "jmenujes", "jmenuji", "kdo", "jsi",
            "are", "you", "call", "yourself",

            // Capabilities
            "what_can", "can", "you", "do", "capabilities", "features", "help", "umis",
            "co", "umi", "dokazes", "funkce", "moznosti",

            // How are you
            "how", "are", "you", "doing", "feeling", "jak_se_mas", "jak", "se", "mas",
            "howdy", "co_rikas",

            // Programming questions
            "code", "programming", "kotlin", "java", "function", "variable", "class",
            "how_to", "write", "create", "make", "use", "implement", "example", "kod",
            "programovani", "funkce", "promenna", "trida", "napsat", "vytvorit",

            // General info
            "who", "made", "created", "you", "creator", "kdo_te", "vytvoril", "author",
            "cim_jsi", "co_jsi_zac",

            // Default/common words
            "the", "a", "an", "in", "of", "to", "for", "with", "on", "at", "by",
            "please", "thanks", "thank", "diky", "dekuji", "prosim", "yes", "ano",
            "no", "ne", "maybe", "mozna", "not", "nice", "pekne", "super", "great",

            // Weather (dummy)
            "weather", "pocasi", "rain", "sunny", "cold", "hot", "temperature",
            "forecast", "dnes", "venku", "teplota",

            // Time
            "time", "date", "hodiny", "datum", "cas", "kolik_je",
            "today", "dneska", "zitra", "yesterday",

            // Sentiment words
            "happy", "sad", "angry", "tired", "bored", "confused", "lost",
            "stastny", "smutny", "unaveny", "ztraceny",

            // PAD token
            "[PAD]", "[UNK]"
        )

        vocabulary.forEach { word ->
            wordToIndex[word] = nextIndex
            indexToWord[nextIndex] = word
            nextIndex++
        }
    }

    fun vocabularySize(): Int = nextIndex

    /**
     * Tokenizes a text string into a fixed-length array of indices.
     * Pads or truncates to [maxSequenceLength].
     */
    fun tokenize(text: String): IntArray {
        val cleaned = text.lowercase().trim()
            .replace(Regex("[^a-z0-9_ ]+"), " ")
            .replace(Regex("\\s+"), " ").trim()

        val words = cleaned.split(" ")
        val tokens = mutableListOf<Int>()

        for (word in words) {
            // Handle multi-word entries like "good_morning", "dobry_den" etc.
            val mappedWord = mapToVocabulary(word)
            val index = wordToIndex[mappedWord] ?: wordToIndex["[UNK]"]!!
            tokens.add(index)
        }

        // Truncate or pad to maxSequenceLength
        val result = IntArray(maxSequenceLength) { wordToIndex["[PAD]"]!! }
        val actualTokens = tokens.take(maxSequenceLength)
        actualTokens.forEachIndexed { i, token ->
            result[i] = token
        }

        return result
    }

    /**
     * Maps a word to our vocabulary (handles common word forms).
     */
    private fun mapToVocabulary(word: String): String {
        // Direct match
        if (wordToIndex.containsKey(word)) return word

        // Try some simple mappings
        return when (word) {
            "goodbye" -> "bye"
            "hellot", "helo" -> "hello"
            "thnx", "thx", "ty" -> "thanks"
            "pls", "plz" -> "please"
            "wassup", "sup", "yo" -> "hi"
            "gonna", "wanna" -> "going"
            "lmk" -> "tell"
            else -> word // Will map to [UNK] if not in vocabulary
        }
    }

    fun getMaxSequenceLength(): Int = maxSequenceLength

    fun padTokenIndex(): Int = wordToIndex["[PAD]"]!!
}