package com.example.engine

import android.content.Context
import android.os.SystemClock
import android.os.Debug
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

internal data class LiteRtResult(val text: String, val inputTokens: Int, val outputTokens: Int,
    val firstTokenMs: Long, val tokensPerSecond: Float, val prefillMs: Long)

/** Owned exclusively by InferenceEngine's process-wide admission/lifecycle gate.
 * A conversation always finishes cancellation before its engine can be released.
 * CPU and GPU share Android RAM; OpenCL provides no additional memory budget.
 */
@OptIn(ExperimentalApi::class)
internal class LiteRtSession(private val context: Context, val file: File,
    private val reuseAttentionExecutor: Boolean = false, identity: String = "") : AutoCloseable {
    val supportsGpu: Boolean
    val gpuReason: String
    val maximumContext: Int
    val minimumContext = ModelLibrary.liteRtMinimumContext(identity)
    val dynamicContext: Boolean
    val supportsThinking: Boolean
    val needsThoughtBudget: Boolean get() = supportsThinking && !reuseAttentionExecutor
    fun configurationContext(requested: Int) = requested.coerceIn(minimumContext, maximumContext)
    fun allocationContext(requested: Int) = ModelMemoryPlanner.liteRtAllocationContext(configurationContext(requested), maximumContext, dynamicContext)
    private var engine: Engine? = null
    val isInitialized: Boolean get() = engine?.isInitialized() == true
    var backend = ProcessingMode.CPU; private set
    private var configuration = ""
    private var usedForConversation = false
    var initializationMs = 0L; private set
    var allocatedContext = 0; private set
    var configuredContext = 0; private set
    var allocatedThreads = 0; private set
    fun prepared(mode: ProcessingMode, threads: Int, context: Int) = isInitialized && configuration == "$mode:$threads:${configurationContext(context)}"
    private var privateBaselineMb = 0L
    // Credit only model-attributable private dirty allocations on unload.
    // Clean mmap pages are already reclaimable in Android's available RAM.
    val releasablePrivateMb: Long get() = if (isInitialized) (privateDirtyMb() - privateBaselineMb).coerceAtLeast(0) else 0
    private fun privateDirtyMb(): Long = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPrivateDirty / 1024L

    init {
        ModelInfo.from(file.absolutePath).use { info ->
            require(info is ModelInfo.Llm) { "Select a LiteRT-LM text-generation model, not an embedding model" }
            val backends = info.supportedBackends(Modality.TEXT)
            require(com.google.ai.edge.litertlm.BackendType.CPU in backends) {
                "This LiteRT model has no CPU path. Select the standard CPU/GPU .litertlm file for safe fallback."
            }
            val restriction = ModelLibrary.liteRtGpuRestriction(identity)
            supportsGpu = com.google.ai.edge.litertlm.BackendType.GPU in backends && restriction.isEmpty()
            gpuReason = restriction.ifEmpty { if (!supportsGpu) "This model package contains no LiteRT GPU graph" else "GPU graph requires runtime validation" }
            maximumContext = info.maxContextTokens().takeIf { it > 0 } ?: 2048
            dynamicContext = info.isDynamicContext()
            supportsThinking = info.supportsThinking()
        }
    }

    fun prepare(mode: ProcessingMode, threads: Int, contextSize: Int) {
        require(mode == ProcessingMode.CPU || mode == ProcessingMode.GPU)
        require(mode != ProcessingMode.GPU || supportsGpu) { gpuReason }
        val graphContext = configurationContext(contextSize)
        val key = "$mode:$threads:$graphContext"
        if (engine?.isInitialized() == true && key == configuration) return
        close()
        privateBaselineMb = privateDirtyMb()
        val start = SystemClock.elapsedRealtime()
        ExperimentalFlags.enableBenchmark = true
        val candidate = Engine(EngineConfig(modelPath = file.absolutePath,
            backend = if (mode == ProcessingMode.GPU) Backend.GPU() else Backend.CPU(threads),
            maxNumTokens = graphContext,
            cacheDir = File(context.cacheDir, "litert-lm").apply { mkdirs() }.absolutePath))
        try {
            candidate.initialize()
            engine = candidate; backend = mode; configuration = key
            allocatedContext = allocationContext(graphContext); configuredContext = graphContext; allocatedThreads = threads
            initializationMs = SystemClock.elapsedRealtime() - start
        } catch (failure: Throwable) {
            if (candidate.isInitialized()) candidate.close()
            throw failure
        }
    }

    suspend fun generate(prompt: String, params: GenerationParams,
        onText: (String, Int) -> Unit = { _, _ -> }): LiteRtResult {
        val started = SystemClock.elapsedRealtime()
        if (usedForConversation && !reuseAttentionExecutor) {
            // LiteRT-LM #3165: rewinding a new Conversation does not reliably reset
            // recurrent state. Dispose before creating its replacement, under core admission.
            val mode = backend; val threads = allocatedThreads; val size = configuredContext
            close(); prepare(mode, threads, size)
        }
        usedForConversation = true
        val loaded = checkNotNull(engine) { "LiteRT model is not initialized" }
        val conversation = loaded.createConversation(ConversationConfig(
            initialMessages = params.history.map { if (it.role == "user") Message.user(it.content) else Message.model(it.content) },
            systemInstruction = params.systemPrompt?.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
            samplerConfig = SamplerConfig(topK = params.topK.coerceIn(1, 100),
                topP = params.topP.coerceIn(.1f, 1f).toDouble(), temperature = params.temperature.coerceIn(0f, 2f).toDouble()),
            maxOutputToken = params.maxTokens.coerceIn(1, 1024), thinkingConfig = ThinkingConfig(enableThinking = false)))
        val completion = CompletableDeferred<Unit>()
        val nativeFinished = CompletableDeferred<Unit>()
        val text = StringBuilder()
        var first = 0L
        var chunks = 0
        var submitted = false
        try {
            conversation.sendMessageAsync(prompt, object : MessageCallback {
                override fun onMessage(message: Message) {
                    val delta = message.toString()
                    if (delta.isNotEmpty()) {
                        if (first == 0L) first = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                        text.append(delta)
                        try { onText(delta, chunks++) } // Chunk index, never reported as a token count.
                        catch (failure: Exception) { completion.completeExceptionally(failure) }
                    }
                }
                override fun onDone() { nativeFinished.complete(Unit); completion.complete(Unit) }
                override fun onError(throwable: Throwable) { nativeFinished.complete(Unit); completion.completeExceptionally(throwable) }
            })
            submitted = true
            withTimeout(180_000) { completion.await() }
            val metrics = conversation.getBenchmarkInfo()
            return LiteRtResult(text.toString(), metrics.lastPrefillTokenCount, metrics.lastDecodeTokenCount,
                first, metrics.lastDecodeTokensPerSecond.toFloat(),
                if (metrics.lastPrefillTokensPerSecond > 0) (1000 * metrics.lastPrefillTokenCount / metrics.lastPrefillTokensPerSecond).toLong() else 0)
        } finally {
            if (submitted && !nativeFinished.isCompleted) {
                conversation.cancelProcess()
                // Releasing a context before native onDone/onError risks a use-after-free.
                // Keep admission closed until the runtime acknowledges cancellation.
                withContext(NonCancellable) { nativeFinished.join() }
            }
            conversation.close()
        }
    }

    override fun close() {
        engine?.let { if (it.isInitialized()) it.close() }
        engine = null; configuration = ""; allocatedContext = 0; configuredContext = 0; allocatedThreads = 0; usedForConversation = false
    }
}
