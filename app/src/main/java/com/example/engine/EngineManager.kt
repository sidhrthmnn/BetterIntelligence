package com.example.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SupportedEngine(val displayName: String, val formatBadge: String, val description: String) {
    LLAMA_CPP(
        displayName = "llama.cpp (Native NDK)",
        formatBadge = ".GGUF",
        description = "Native GGUF inference on CPU or compatible Vulkan GPU. ARM64 and x86_64."
    ),
    LITERT(
        displayName = "LiteRT-LM (Google)",
        formatBadge = ".LITERTLM",
        description = "LiteRT-LM text inference on CPU or validated OpenCL GPU. Select a compatible .litertlm model."
    )
}

/**
 * Manages the active on-device inference engine and allows dynamic switching
 * between llama.cpp (GGUF) and LiteRT (TFLite/GenAI).
 */
class EngineManager(
    private val context: Context,
    val llamaCppEngine: LlamaCppEngine = LlamaCppEngine(context),
    val liteRtEngine: LiteRtEngine = LiteRtEngine(context, llamaCppEngine.coreInferenceEngine)
) {
    private val _selectedEngineType = MutableStateFlow(SupportedEngine.LLAMA_CPP)
    val selectedEngineType: StateFlow<SupportedEngine> = _selectedEngineType.asStateFlow()

    val currentEngine: LlmEngine
        get() = when (if (llamaCppEngine.isLoaded) {
            if (llamaCppEngine.coreInferenceEngine.isLiteRtLoaded()) SupportedEngine.LITERT else SupportedEngine.LLAMA_CPP
        } else _selectedEngineType.value) {
            SupportedEngine.LLAMA_CPP -> llamaCppEngine
            SupportedEngine.LITERT -> liteRtEngine
        }

    fun selectEngine(engine: SupportedEngine) {
        if (_selectedEngineType.value != engine) {
            _selectedEngineType.value = engine
        }
    }

    suspend fun initializeActiveEngine(
        modelPath: String = "",
        preferredBackend: BackendType = BackendType.CPU
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
