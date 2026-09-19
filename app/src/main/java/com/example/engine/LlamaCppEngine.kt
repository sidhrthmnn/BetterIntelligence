package com.example.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * llama.cpp On-Device LLM Inference Engine.
 * Supports GGUF quantized models (Q4_K_M, IQ4_XS, Q5_K_M, Q8_0) with ARM NEON / SVE2
 * SIMD and Vulkan GPU Layer Offloading.
 */
class LlamaCppEngine(
    private val context: Context? = null,
    val coreInferenceEngine: InferenceEngine = InferenceEngine()
) : LlmEngine {

    companion object {
        private const val TAG = "LlamaCppEngine"
    }

    override val engineType: String = "llama.cpp (GGUF)"

    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isGenerating = AtomicBoolean(false)
    private var activeJob: Job? = null

    private var _isLoaded: Boolean = true
    override val isLoaded: Boolean
        get() = _isLoaded && coreInferenceEngine.activeMetadata.value != null

    private var _currentBackend: BackendType = BackendType.GPU
    override val currentBackend: BackendType
        get() = _currentBackend

    override suspend fun initialize(
        modelPath: String,
        preferredBackend: BackendType
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                Log.i(TAG, "Initializing llama.cpp Engine for model at: $modelPath (Backend: $preferredBackend)")
                
                // Hardware Validation if context is available
                context?.let { ctx ->
                    val validation = HardwareValidator.validateEnvironment(ctx)
                    if (!validation.isSupported) {
                        Log.w(TAG, "Hardware validation notice: ${validation.reason}")
                    }
                }

                _currentBackend = preferredBackend
                if (modelPath.isNotBlank()) {
                    val file = File(modelPath)
                    if (file.exists() && file.length() > 0) {
                        val parseResult = coreInferenceEngine.loadCustomGguf(file)
                        if (parseResult.isFailure) {
                            return@withContext Result.failure(parseResult.exceptionOrNull() ?: Exception("Failed to parse GGUF"))
                        }
                    }
                }
                _isLoaded = true
                Result.success(Unit)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize llama.cpp engine", e)
                _isLoaded = false
                Result.failure(e)
            }
        }
    }

    override fun generateStream(
        prompt: String,
        config: GenerationConfig
    ): Flow<String> = callbackFlow {
        isGenerating.set(true)
        val params = GenerationParams(
            temperature = config.temperature,
            topK = config.topK,
            topP = config.topP,
            maxTokens = config.maxTokens,
            systemPrompt = config.systemPrompt,
            threads = config.threads,
            gpuLayers = config.gpuLayers,
            accelerationMode = if (_currentBackend == BackendType.GPU) "GPU (Vulkan NDK)" else "CPU Only (NEON SIMD)"
        )

        coreInferenceEngine.generateStream(
            prompt = prompt,
            params = params,
            listener = object : StreamTokenListener {
                override fun onToken(token: String, tokenIndex: Int) {
                    trySend(token)
                }

                override fun onComplete(
                    fullText: String,
                    latencyMs: Long,
                    tokensPerSec: Float,
                    promptTokens: Int,
                    completionTokens: Int
                ) {
                    close()
                }

                override fun onError(errorCode: Int, errorMessage: String) {
                    close(RuntimeException("llama.cpp error ($errorCode): $errorMessage"))
                }
            }
        )

        awaitClose {
            coreInferenceEngine.cancelGeneration()
            isGenerating.set(false)
        }
    }.flowOn(Dispatchers.Default)

    override fun generateStreamWithCallback(
        prompt: String,
        config: GenerationConfig,
        listener: StreamTokenListener
    ) {
        val params = GenerationParams(
            temperature = config.temperature,
            topK = config.topK,
            topP = config.topP,
            maxTokens = config.maxTokens,
            systemPrompt = config.systemPrompt,
            threads = config.threads,
            gpuLayers = config.gpuLayers,
            accelerationMode = if (_currentBackend == BackendType.GPU) "GPU (Vulkan NDK)" else "CPU Only (NEON SIMD)"
        )

        coreInferenceEngine.generateStream(
            prompt = prompt,
            params = params,
            listener = listener
        )
    }

    override fun cancelGeneration() {
        isGenerating.set(false)
        coreInferenceEngine.cancelGeneration()
    }

    override fun release() {
        cancelGeneration()
        coreInferenceEngine.unloadActiveModel()
        _isLoaded = false
        Log.i(TAG, "llama.cpp native memory and tensors released.")
    }
}
