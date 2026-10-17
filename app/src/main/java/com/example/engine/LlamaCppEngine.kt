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
 * GGUF facade. The shared core owns hardware selection, weights and admission.
 */
class LlamaCppEngine(
    private val context: Context? = null,
    val coreInferenceEngine: InferenceEngine = InferenceEngine(context)
) : LlmEngine {

    companion object {
        private const val TAG = "LlamaCppEngine"
    }

    override val engineType: String = "llama.cpp (GGUF)"

    private val mutex = Mutex()
    override val isLoaded: Boolean
        get() = coreInferenceEngine.isReady()

    override val currentBackend: BackendType
        get() = if (coreInferenceEngine.telemetry.value.activeGpuLayers > 0) BackendType.GPU else BackendType.CPU

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

                if (modelPath.isNotBlank()) {
                    val file = File(modelPath)
                    if (file.exists() && file.length() > 0) {
                        val parseResult = coreInferenceEngine.loadCustomGguf(file)
                        if (parseResult.isFailure) {
                            return@withLock Result.failure(parseResult.exceptionOrNull() ?: Exception("Failed to load GGUF"))
                        }
                    }
                }
                check(coreInferenceEngine.isReady()) { "Select an installed GGUF model first" }
                Result.success(Unit)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize llama.cpp engine", e)
                Result.failure(e)
            }
        }
    }

    override fun generateStream(
        prompt: String,
        config: GenerationConfig
    ): Flow<String> = callbackFlow {
        val params = GenerationParams(
            temperature = config.temperature,
            topK = config.topK,
            topP = config.topP,
            maxTokens = config.maxTokens,
            systemPrompt = config.systemPrompt,
            threads = config.threads,
            gpuLayers = config.gpuLayers,
            contextSize = config.contextSize,
            accelerationMode = "Managed by Better Intelligence"
        )

        val requestJob = coreInferenceEngine.generateStream(
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
            requestJob.cancel()
        }
    }.flowOn(Dispatchers.Default)

    override fun generateStreamWithCallback(
        prompt: String,
        config: GenerationConfig,
        listener: StreamTokenListener
    ): Job {
        val params = GenerationParams(
            temperature = config.temperature,
            topK = config.topK,
            topP = config.topP,
            maxTokens = config.maxTokens,
            systemPrompt = config.systemPrompt,
            threads = config.threads,
            gpuLayers = config.gpuLayers,
            contextSize = config.contextSize,
            accelerationMode = "Managed by Better Intelligence"
        )

        return coreInferenceEngine.generateStream(
            prompt = prompt,
            params = params,
            listener = listener
        )
    }

    override fun cancelGeneration() {
        coreInferenceEngine.cancelGeneration()
    }

    override fun release() {
        check(!coreInferenceEngine.telemetry.value.isRunning) { "Stop generation and await completion before releasing the engine" }
        if (coreInferenceEngine.isReady()) coreInferenceEngine.unloadActiveModel()
        Log.i(TAG, "llama.cpp native memory and tensors released.")
    }
}
