package com.example.engine

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generation execution parameters for the llama.cpp mobile runtime.
 */
data class GenerationParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repetitionPenalty: Float = 1.1f,
    val maxTokens: Int = 512,
    val threads: Int = 4,
    val gpuLayers: Int = 16,
    val batchSize: Int = 512,
    val contextSize: Int = 4096,
    val kvCacheEnabled: Boolean = true,
    val accelerationMode: String = "GPU Acceleration (Vulkan/OpenCL)", // "CPU Only", "GPU Acceleration (Vulkan/OpenCL)", "Maximum GPU"
    val systemPrompt: String? = null,
    val stopSequences: List<String> = listOf("</s>", "<|im_end|>", "<|eot_id|>", "[/INST]"),
    val enableSimd: Boolean = true,
    val isAdvancedMode: Boolean = false
)

/**
 * Benchmark result for a specific device and model combination.
 */
data class BenchmarkResult(
    val modelName: String,
    val promptProcessingTokSec: Float,
    val generationTokSec: Float,
    val ramUsageMb: Float,
    val promptLatencyMs: Long,
    val recommendedGpuLayers: Int,
    val recommendedThreads: Int,
    val recommendedContext: Int,
    val testedThreads: Map<Int, Float> = emptyMap(), // Thread count -> Generation tok/s
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Real-time Hardware Telemetry snapshot.
 */
data class HardwareTelemetry(
    val tokensPerSecond: Float = 0f,
    val timeToFirstTokenMs: Long = 0,
    val memoryUsageMb: Float = 0f,
    val totalRamMb: Float = 0f,
    val activeThreads: Int = 4,
    val activeGpuLayers: Int = 16,
    val accelerationMode: String = "CPU (llama.cpp)",
    val isRunning: Boolean = false,
    val promptEvaluationTimeMs: Long = 0,
    val completionTokens: Int = 0,
    val promptTokens: Int = 0,
    val kvCacheTokens: Int = 0,
    val kvCacheRamMb: Float = 64f,
    val temperatureCelsius: Float = 37.2f,
    val thermalState: String = "Normal", // "Normal", "Warm", "Hot - Throttled"
    val isModelLoaded: Boolean = false
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("tokensPerSecond", tokensPerSecond.toDouble())
            put("timeToFirstTokenMs", timeToFirstTokenMs)
            put("memoryUsageMb", memoryUsageMb.toDouble())
            put("totalRamMb", totalRamMb.toDouble())
            put("activeThreads", activeThreads)
            put("activeGpuLayers", activeGpuLayers)
            put("accelerationMode", accelerationMode)
            put("isRunning", isRunning)
            put("promptEvaluationTimeMs", promptEvaluationTimeMs)
            put("completionTokens", completionTokens)
            put("promptTokens", promptTokens)
            put("kvCacheTokens", kvCacheTokens)
            put("temperatureCelsius", temperatureCelsius.toDouble())
            put("thermalState", thermalState)
            put("isModelLoaded", isModelLoaded)
        }.toString()
    }
}

/**
 * Callback for streaming token generation.
 */
interface StreamTokenListener {
    fun onToken(token: String, tokenIndex: Int)
    fun onComplete(fullText: String, latencyMs: Long, tokensPerSec: Float, promptTokens: Int, completionTokens: Int)
    fun onError(errorCode: Int, errorMessage: String)
}

/**
 * Core On-Device Inference Engine supporting GGUF quantized models and SIMD acceleration.
 */
class InferenceEngine {
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val busy = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var activeJob: Job? = null
    private val _activeMetadata = MutableStateFlow<GgufMetadata?>(null)
    val activeMetadata: StateFlow<GgufMetadata?> = _activeMetadata.asStateFlow()
    private val _telemetry = MutableStateFlow(HardwareTelemetry())
    val telemetry: StateFlow<HardwareTelemetry> = _telemetry.asStateFlow()

    fun loadDefaultModel(modelFile: File? = null) {
        requireNotNull(modelFile) { "Download and select a GGUF model in Models first" }
        loadCustomGguf(modelFile).getOrThrow()
    }

    fun loadCustomGguf(file: File): Result<GgufMetadata> = runCatching {
        synchronized(lifecycleLock) {
            check(!busy.get()) { "Stop generation before switching models" }
            require(file.isFile && file.length() > 24) { "GGUF file is missing or empty" }
            val meta = GgufParser().parseFromFile(file).getOrThrow()
            NativeGguf.ensureAvailable()
            _activeMetadata.value = null
            _telemetry.value = HardwareTelemetry(isModelLoaded = false)
            NativeGguf.prepare(file.absolutePath.toByteArray(Charsets.UTF_8))
            _activeMetadata.value = meta
            _telemetry.value = HardwareTelemetry(isModelLoaded = true, memoryUsageMb = memoryMb(),
                accelerationMode = "CPU (llama.cpp)")
            meta
        }
    }

    fun unloadActiveModel() {
        synchronized(lifecycleLock) {
            check(!busy.get()) { "Stop generation before unloading the model" }
            NativeGguf.ensureAvailable()
            NativeGguf.release()
            _activeMetadata.value = null
            _telemetry.value = HardwareTelemetry(isModelLoaded = false)
        }
    }

    fun cancelGeneration() { activeJob?.cancel() }
    fun isReady(): Boolean = _activeMetadata.value != null
    private fun memoryMb(): Float = android.os.Debug.getPss() / 1024f

    private suspend fun infer(prompt: String, params: GenerationParams, listener: StreamTokenListener?): String {
        synchronized(lifecycleLock) {
            check(isReady()) { "Download and select a GGUF model first" }
            check(busy.compareAndSet(false, true)) { "AI Core is busy; retry after the current request" }
        }
        activeJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        val started = SystemClock.elapsedRealtime()
        var emitted = ""
        var promptCount = 0
        var completionCount = 0
        var firstTokenMs = 0L
        _telemetry.value = _telemetry.value.copy(isRunning = true, completionTokens = 0,
            activeThreads = params.threads.coerceIn(1, 8), activeGpuLayers = 0)
        try {
            val task = listOfNotNull(params.systemPrompt?.takeIf { it.isNotBlank() }, prompt).joinToString("\n\n")
            require(task.length <= 65536) { "Prompt is too long" }
            val observer = NativeObserver(kotlinx.coroutines.currentCoroutineContext()[Job]) { bytes, inputTokens, outputTokens ->
                val full = bytes.toString(Charsets.UTF_8)
                val delta = full.removePrefix(emitted)
                emitted = full; promptCount = inputTokens; completionCount = outputTokens
                val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                if (firstTokenMs == 0L) firstTokenMs = elapsed
                _telemetry.value = _telemetry.value.copy(tokensPerSecond = outputTokens * 1000f / elapsed,
                    timeToFirstTokenMs = firstTokenMs, completionTokens = outputTokens, promptTokens = inputTokens,
                    memoryUsageMb = memoryMb(), promptEvaluationTimeMs = firstTokenMs)
                if (delta.isNotEmpty()) listener?.onToken(delta, outputTokens - 1)
            }
            val result = NativeGguf.generate(task.toByteArray(Charsets.UTF_8), observer,
                params.maxTokens.coerceIn(1, 1024), params.threads.coerceIn(1, 8),
                params.contextSize.coerceIn(512, 4096), params.temperature.coerceIn(0f, 2f),
                params.topP.coerceIn(0.1f, 1f), params.topK.coerceIn(1, 100)).toString(Charsets.UTF_8)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
            listener?.onComplete(result, elapsed, completionCount * 1000f / elapsed, promptCount, completionCount)
            return result
        } finally {
            busy.set(false)
            _telemetry.value = _telemetry.value.copy(isRunning = false, memoryUsageMb = memoryMb())
        }
    }

    fun generateStream(prompt: String, params: GenerationParams = GenerationParams(), listener: StreamTokenListener): Job {
        val job = engineScope.launch {
            try { infer(prompt, params, listener) }
            catch (e: CancellationException) { listener.onError(499, "Inference cancelled") }
            catch (e: Exception) { listener.onError(500, e.message ?: "Inference failed") }
        }
        return job
    }

    suspend fun generateSync(prompt: String, params: GenerationParams = GenerationParams()): String =
        withContext(Dispatchers.Default) { infer(prompt, params, null) }

    fun computeEmbeddings(text: String): FloatArray =
        throw UnsupportedOperationException("This text-generation library does not provide embedding models")

    suspend fun summarize(text: String, maxWords: Int = 60): String = generateSync(text,
        GenerationParams(systemPrompt = "Summarize in at most ${maxWords.coerceIn(1, 500)} words. Return only the summary."))

    suspend fun classify(text: String, candidateLabels: Array<String>): String {
        require(candidateLabels.isNotEmpty() && candidateLabels.size <= 100)
        return generateSync(text, GenerationParams(systemPrompt = "Choose exactly one label: ${candidateLabels.joinToString(", ")}", maxTokens = 32, temperature = 0f)).trim()
    }

    suspend fun runBenchmark(contextLength: Int = 2048, gpuLayers: Int = 0): BenchmarkResult {
        val speeds = mutableMapOf<Int, Float>()
        for (threads in listOf(2, 4)) {
            generateSync("Count from one to twenty.", GenerationParams(maxTokens = 32, threads = threads,
                contextSize = contextLength, temperature = 0f))
            speeds[threads] = telemetry.value.tokensPerSecond
        }
        val best = speeds.maxBy { it.value }
        val t = telemetry.value
        return BenchmarkResult(activeMetadata.value!!.modelName,
            t.promptTokens * 1000f / t.promptEvaluationTimeMs.coerceAtLeast(1), best.value,
            t.memoryUsageMb, t.timeToFirstTokenMs, 0, best.key, contextLength.coerceIn(512, 4096), speeds)
    }
}
