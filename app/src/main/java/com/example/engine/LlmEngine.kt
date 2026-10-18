package com.example.engine

import kotlinx.coroutines.flow.Flow

/**
 * Common configuration for LLM generation requests across all backends.
 */
data class GenerationConfig(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val maxTokens: Int = 1024,
    val systemPrompt: String? = null,
    val threads: Int = 0,
    val gpuLayers: Int = 0,
    val stopSequences: List<String> = listOf("</s>", "<|im_end|>", "<|eot_id|>", "[/INST]"),
    val contextSize: Int = 4096,
    val history: List<ChatTurn> = emptyList()
)

data class ChatTurn(val role: String, val content: String) {
    init { require(role == "user" || role == "assistant") }
}

/**
 * Hardware accelerator options for the inference backend.
 */
enum class BackendType {
    GPU,       // Packaged llama.cpp Vulkan backend, if verified compatible.
    CPU        // Packaged ggml CPU backend, baseline NEON on arm64-v8a.
}

/**
 * Common abstraction implemented by LlamaCppEngine (.gguf) and LiteRtEngine (.litertlm).
 */
interface LlmEngine {
    val engineType: String
    val isLoaded: Boolean
    val currentBackend: BackendType

    /**
     * Initializes and memory-maps the model weights asynchronously.
     */
    suspend fun initialize(
        modelPath: String,
        preferredBackend: BackendType = BackendType.CPU
    ): Result<Unit>

    /**
     * Streams generated tokens asynchronously via Kotlin Flow.
     */
    fun generateStream(
        prompt: String,
        config: GenerationConfig = GenerationConfig()
    ): Flow<String>

    /**
     * Streams generated tokens with full callback metadata (latency, tok/s, token indices).
     */
    fun generateStreamWithCallback(
        prompt: String,
        config: GenerationConfig = GenerationConfig(),
        listener: StreamTokenListener
    ): kotlinx.coroutines.Job?

    /**
     * Cancels any in-flight token generation.
     */
    fun cancelGeneration()

    /**
     * Releases native memory handles, delegates, and cached KV buffers.
     */
    fun release()
}
