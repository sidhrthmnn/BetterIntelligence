package com.example.engine

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Explicitly unavailable until a real LiteRT dependency is integrated. */
class LiteRtEngine(private val context: Context) : LlmEngine {
    override val engineType = "LiteRT (unavailable in this build)"
    override val isLoaded = false
    override val currentBackend = BackendType.CPU
    override suspend fun initialize(modelPath: String, preferredBackend: BackendType): Result<Unit> =
        Result.failure(UnsupportedOperationException("LiteRT is not packaged. Select a GGUF model."))
    override fun generateStream(prompt: String, config: GenerationConfig): Flow<String> = flow {
        throw UnsupportedOperationException("LiteRT is not packaged. Select a GGUF model.")
    }
    override fun generateStreamWithCallback(prompt: String, config: GenerationConfig, listener: StreamTokenListener) {
        listener.onError(501, "LiteRT is not packaged. Select a GGUF model.")
    }
    override fun cancelGeneration() {}
    override fun release() {}
}
