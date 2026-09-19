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
    val threads: Int = 4,
    val gpuLayers: Int = 16,
    val stopSequences: List<String> = listOf("</s>", "<|im_end|>", "<|eot_id|>", "[/INST]")
)

/**
 * Hardware accelerator options for the inference backend.
 */
enum class BackendType {
    GPU,       // OpenCL / Vulkan / Qualcomm Adreno Delegate
    CPU        // XNNPACK / ARM NEON SIMD
}

/**
 * Common abstraction implemented by both LlamaCppEngine (GGUF) and LiteRtEngine (.tflite/.bin/.task).
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
        preferredBackend: BackendType = BackendType.GPU
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
    )

    /**
     * Cancels any in-flight token generation.
     */
    fun cancelGeneration()

    /**
     * Releases native memory handles, delegates, and cached KV buffers.
     */
    fun release()
}
