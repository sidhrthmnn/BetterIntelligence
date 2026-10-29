package com.example.engine

import android.content.Context
import android.os.SystemClock
import android.os.Debug
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Content
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
import kotlinx.coroutines.ensureActive
import java.io.File

internal data class LiteRtResult(val text: String, val inputTokens: Int, val outputTokens: Int,
    val firstTokenMs: Long, val tokensPerSecond: Float, val prefillMs: Long)

/** Owned exclusively by InferenceEngine's process-wide admission/lifecycle gate.
 * A conversation always finishes cancellation before its engine can be released.
 * CPU and GPU share Android RAM; OpenCL provides no additional memory budget.
 */
@OptIn(ExperimentalApi::class)
internal class LiteRtSession(private val context: Context, val file: File,
    private val reuseAttentionExecutor: Boolean = false, identity: String = "",
    private val experimentalMtp: Boolean = false) : AutoCloseable {
    val supportsGpu: Boolean
    val gpuReason: String
    val supportsCpu: Boolean
    val supportsNpu: Boolean
    val npuReason: String
    val maximumContext: Int
    val minimumContext = ModelLibrary.liteRtMinimumContext(identity)
    private val float32Gpu = ModelLibrary.liteRtFloat32Gpu(identity)
    val dynamicContext: Boolean
    val supportsThinking: Boolean
    val supportsMtp: Boolean
    private var mtpQuarantined = false
    var lastMtpUsed = false; private set
    val needsThoughtBudget: Boolean get() = supportsThinking && !reuseAttentionExecutor
    fun configurationContext(requested: Int) = requested.coerceIn(minimumContext, maximumContext)
    fun allocationContext(requested: Int) = ModelMemoryPlanner.liteRtAllocationContext(configurationContext(requested), maximumContext, dynamicContext)
    private var engine: Engine? = null
    val isInitialized: Boolean get() = engine?.isInitialized() == true
    var backend = ProcessingMode.CPU; private set
    private var configuration = ""
    private var usedForConversation = false
    private var resetRequired = false
    var initializationMs = 0L; private set
    var allocatedContext = 0; private set
    var configuredContext = 0; private set
    var allocatedThreads = 0; private set
    fun prepared(mode: ProcessingMode, threads: Int, context: Int) = isInitialized && configuration == ModelMemoryPlanner.liteRtConfigurationKey(mode, threads, configurationContext(context))
    private var privateBaselineMb = 0L
    // Credit only model-attributable private dirty allocations on unload.
    // Clean mmap pages are already reclaimable in Android's available RAM.
    val releasablePrivateMb: Long get() = if (isInitialized) (privateDirtyMb() - privateBaselineMb).coerceAtLeast(0) else 0
    private fun privateDirtyMb(): Long = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPrivateDirty / 1024L

    init {
        ModelInfo.from(file.absolutePath).use { info ->
            require(info is ModelInfo.Llm) { "Select a LiteRT-LM text-generation model, not an embedding model" }
            val backends = info.supportedBackends(Modality.TEXT)
            supportsCpu = com.google.ai.edge.litertlm.BackendType.CPU in backends
            val npu = QualcommNpuSupport.inspect(context, info)
            supportsNpu = npu.compatible; npuReason = npu.reason
            require(supportsCpu || supportsNpu) {
                "This LiteRT model has no CPU fallback. ${npu.reason} Select the standard CPU/GPU file instead."
            }
            val restriction = ModelLibrary.liteRtGpuRestriction(identity)
            supportsGpu = com.google.ai.edge.litertlm.BackendType.GPU in backends && restriction.isEmpty()
            gpuReason = restriction.ifEmpty { if (!supportsGpu) "This model package contains no LiteRT GPU graph" else "GPU graph requires runtime validation" }
            maximumContext = info.maxContextTokens().takeIf { it > 0 } ?: 2048
            dynamicContext = info.isDynamicContext()
            supportsThinking = info.supportsThinking()
            supportsMtp = info.hasSpeculativeDecodingSupport()
        }
    }

    fun prepare(mode: ProcessingMode, threads: Int, contextSize: Int) {
        require(mode in setOf(ProcessingMode.CPU, ProcessingMode.GPU, ProcessingMode.NPU))
        require(mode != ProcessingMode.CPU || supportsCpu) { "This compiled NPU file has no CPU graph. Use the standard CPU/GPU model for fallback." }
        require(mode != ProcessingMode.GPU || supportsGpu) { gpuReason }
        require(mode != ProcessingMode.NPU || supportsNpu) { npuReason }
        val graphContext = configurationContext(contextSize)
        val key = ModelMemoryPlanner.liteRtConfigurationKey(mode, threads, graphContext)
        if (engine?.isInitialized() == true && key == configuration) return
        close()
        privateBaselineMb = privateDirtyMb()
        val start = SystemClock.elapsedRealtime()
        ExperimentalFlags.enableBenchmark = true
        // Never inherit an export's experimental speculative-decoding default.
        // MTP needs its own compatible drafter and physical validation.
        ExperimentalFlags.enableSpeculativeDecoding = false
        if (mode == ProcessingMode.NPU) QualcommNpuSupport.configureDspSearchPath(context)
        val candidate = Engine(EngineConfig(modelPath = file.absolutePath,
            backend = when (mode) {
                ProcessingMode.GPU -> Backend.GPU()
                ProcessingMode.NPU -> Backend.NPU(nativeLibraryDir = this.context.applicationInfo.nativeLibraryDir)
                else -> Backend.CPU(threads)
            },
            maxNumTokens = graphContext,
            // Gemma 4 has upstream FP16 GPU correctness reports (#4001). Use
            // the public 0.18 FP32 override for these exact verified packages,
            // with separate compiled cache and additional memory headroom.
            activationDataType = if (mode == ProcessingMode.GPU && float32Gpu) ActivationDataType.FLOAT32 else null,
            cacheDir = File(context.cacheDir, if (float32Gpu) "litert-lm-f32" else "litert-lm").apply { mkdirs() }.absolutePath))
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
        // Test-only explicit opt-in. Production constructors leave this false.
        // Lazy drafter allocations need extra shared-memory headroom, not an
        // assumption that the standard GPU graph also includes a drafter.
        val mtpEligible = experimentalMtp && supportsMtp && !mtpQuarantined && backend == ProcessingMode.GPU
        val memory = if (mtpEligible) android.app.ActivityManager.MemoryInfo().also {
            context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
        } else null
        val thermal = if (mtpEligible && android.os.Build.VERSION.SDK_INT >= 29)
            context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus else 0
        val mtp = mtpEligible && memory != null && !memory.lowMemory && memory.availMem >= 1024L * 1048576 && thermal < 2
        lastMtpUsed = mtp
        var visible = false
        return try { generateOnce(prompt, params, mtp) { delta, index -> visible = true; onText(delta, index) } }
        catch (failure: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!mtp || visible) throw failure
            // generateOnce has already joined cancellation and closed its
            // Conversation. Replay only before any visible text, once, normally.
            mtpQuarantined = true; lastMtpUsed = false
            generateOnce(prompt, params, false, onText)
        }
    }

    private suspend fun generateOnce(prompt: String, params: GenerationParams, mtp: Boolean,
        onText: (String, Int) -> Unit): LiteRtResult {
        val started = SystemClock.elapsedRealtime()
        if (resetRequired || (usedForConversation && !reuseAttentionExecutor)) {
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
            maxOutputToken = params.maxTokens.coerceIn(1, 1024), thinkingConfig = ThinkingConfig(enableThinking = false), enableSpeculativeDecoding = mtp))
        val completion = CompletableDeferred<Unit>()
        val nativeFinished = CompletableDeferred<Unit>()
        val text = StringBuilder()
        val validation = ModelOutputValidator.Stream(prompt)
        var first = 0L
        var chunks = 0
        var submitted = false
        try {
            conversation.sendMessageAsync(prompt, object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (completion.isCompleted) return
                    // v0.18.0 Message.toString joins all content types. Read only
                    // typed text; thinking channels/tools/multimodal data are not answers.
                    val delta = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (delta.isNotEmpty()) {
                        if (first == 0L) first = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                        text.append(delta)
                        try { validation.append(delta).takeIf { it.isNotEmpty() }?.let { onText(it, chunks++) } }
                        catch (failure: Exception) { resetRequired = true; completion.completeExceptionally(failure) }
                    }
                }
                override fun onDone() { nativeFinished.complete(Unit); completion.complete(Unit) }
                override fun onError(throwable: Throwable) { nativeFinished.complete(Unit); completion.completeExceptionally(throwable) }
            })
            submitted = true
            withTimeout(180_000) { completion.await() }
            validation.finish().takeIf { it.isNotEmpty() }?.let { onText(it, chunks++) }
            val metrics = conversation.getBenchmarkInfo()
            return LiteRtResult(text.toString(), metrics.lastPrefillTokenCount, metrics.lastDecodeTokenCount,
                first, metrics.lastDecodeTokensPerSecond.toFloat(),
                if (metrics.lastPrefillTokensPerSecond > 0) (1000 * metrics.lastPrefillTokenCount / metrics.lastPrefillTokensPerSecond).toLong() else 0)
        } catch (failure: Throwable) {
            resetRequired = true
            throw failure
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
        engine = null; configuration = ""; allocatedContext = 0; configuredContext = 0; allocatedThreads = 0; usedForConversation = false; resetRequired = false
    }
}
