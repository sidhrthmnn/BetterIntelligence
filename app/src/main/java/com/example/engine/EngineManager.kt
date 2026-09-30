package com.example.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SupportedEngine(val displayName: String, val formatBadge: String, val description: String) {
    LLAMA_CPP(
        displayName = "llama.cpp (Native NDK)",
        formatBadge = ".GGUF",
        description = "Native llama.cpp CPU runtime for GGUF instruction models. ARM64 and x86_64."
    ),
    LITERT(
        displayName = "LiteRT (Google GenAI)",
        formatBadge = ".TFLITE / .BIN",
        description = "Unavailable in this build. Select a GGUF model to use the packaged llama.cpp runtime."
    )
}

/**
 * Manages the active on-device inference engine and allows dynamic switching
 * between llama.cpp (GGUF) and LiteRT (TFLite/GenAI).
 */
class EngineManager(
    private val context: Context,
    val llamaCppEngine: LlamaCppEngine = LlamaCppEngine(context),
    val liteRtEngine: LiteRtEngine = LiteRtEngine(context)
) {
    private val _selectedEngineType = MutableStateFlow(SupportedEngine.LLAMA_CPP)
    val selectedEngineType: StateFlow<SupportedEngine> = _selectedEngineType.asStateFlow()

    val currentEngine: LlmEngine
        get() = when (_selectedEngineType.value) {
            SupportedEngine.LLAMA_CPP -> llamaCppEngine
            SupportedEngine.LITERT -> liteRtEngine
        }

    fun selectEngine(engine: SupportedEngine) {
        if (engine == SupportedEngine.LITERT) return // No LiteRT runtime is packaged in this build.
        if (_selectedEngineType.value != engine) {
            currentEngine.cancelGeneration()
            _selectedEngineType.value = engine
        }
    }

    suspend fun initializeActiveEngine(
        modelPath: String = "",
        preferredBackend: BackendType = BackendType.GPU
    ): Result<Unit> {
        return currentEngine.initialize(modelPath, preferredBackend)
    }

    fun cancelGeneration() {
        llamaCppEngine.cancelGeneration()
        liteRtEngine.cancelGeneration()
    }

    fun release() {
        llamaCppEngine.release()
        liteRtEngine.release()
    }
}
