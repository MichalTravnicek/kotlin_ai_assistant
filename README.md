# Kotlin AI Assistant  (built with help from AI)

A lightweight AI assistant built entirely in Kotlin with a **tiny neural network model** running directly in the JVM — no external AI APIs, no TensorFlow, no Python dependencies.

## Features

- **Tiny Neural Network** — a feedforward NN with embedding → hidden → softmax layers (~320 parameters)
- **Intent Classification** — recognizes 10 intent types in Czech and English
- **Conversation Memory** — in-memory session history with context-aware follow-up detection
- **REST API** — Ktor server with JSON endpoints

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin (JVM) |
| Web Server | Ktor (Netty) |
| Serialization | kotlinx-serialization-json |
| Model | Pure Kotlin Neural Network (no external AI libs) |

## Quick Start

```bash
# Build and run
./gradlew run

# Server starts at http://localhost:8080
```

## API Endpoints

### `GET /` — Web UI
Open `http://localhost:8080` in your browser for an interactive chat interface.

### `POST /chat` — Send a message
```json
// Request
{
  "message": "Ahoj, jak se máš?",
  "sessionId": "user123"
}

// Response
{
  "reply": "Jsem jen program, ale funguji skvěle! Děkuji za optání.",
  "sessionId": "user123",
  "intent": "how_are_you",
  "confidence": 0.87,
  "historyCount": 1,
  "modelInfo": "TinyNeuralNetwork v1.0 (pure Kotlin, ~320 parameters)"
}
```

### `GET /chat/history` — Get conversation history
```
GET /chat/history?sessionId=user123
```

### `POST /chat/clear` — Clear session history
```json
{
  "sessionId": "user123"
}
```

### `GET /status` — Model information
```json
{
  "status": "ready",
  "sessionCount": 0,
  "modelType": "TinyNeuralNetwork (embed=16)",
  "vocabularySize": 128
}
```

## Example Queries

| Czech Query | English Query | Expected Intent |
|-------------|---------------|-----------------|
| Ahoj | Hello | greeting |
| Nashledanou | Goodbye | farewell |
| Jak se jmenuješ? | What's your name? | ask_name |
| Co umíš? | What can you do? | capabilities |
| Jak se máš? | How are you? | how_are_you |
| Napiš funkci v Kotlinu | Write a function in Kotlin | programming |
| Kdo tě vytvořil? | Who made you? | who_made_you |
| Jaké je počasí? | What's the weather? | weather |
| Kolik je hodin? | What time is it? | time |

## Project Structure

```
kotlin_ai_assistant/
├── build.gradle.kts              # Gradle build with Ktor + dependencies
├── settings.gradle.kts           # Project settings
├── gradlew / gradlew.bat         # Gradle wrapper
├── README.md
├── src/
│   └── main/
│       ├── kotlin/com/assistant/
│       │   ├── Application.kt                    # Server entry point
│       │   ├── model/
│       │   │   ├── SimpleTokenizer.kt            # CZ/EN vocabulary tokenizer
│       │   │   └── TinyNeuralNetwork.kt          # Neural network (embed → hidden → output)
│       │   ├── memory/
│       │   │   └── ConversationMemory.kt         # Session-based conversation history
│       │   └── routes/
│       │       └── ChatRoutes.kt                 # REST API + Web UI routes
│       └── resources/
│           └── logback.xml                       # Logging config
```

## How the Model Works

1. **Tokenization**: Input text is split into words and mapped to vocabulary indices
2. **Embedding**: Each word index is converted to a 16-dimensional vector
3. **Hidden Layer**: Flattened embeddings pass through 32 ReLU neurons
4. **Output Layer**: Produces scores for 10 intents, converted to probabilities via softmax

The model runs entirely in-process — no network calls, no external dependencies.