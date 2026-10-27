package com.example.engine

import android.os.Build
import android.os.Debug
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Runs under the same process-wide model admission as CPU/GPU and Binder clients.
 * Never creates a second model. A compiled NPU-only file has no invented CPU path.
 */
internal class QualcommNpuController(private val hardware: HardwareAccelerationManager,
    private val selector: SmartBackendSelector) {
    fun key(identity: String, context: Int, threads: Int) = SmartBackendSelector.key(
        Build.FINGERPRINT, QualcommNpuSupport.RUNTIME, identity, context, threads)

    suspend fun select(session: LiteRtSession, identity: String, params: GenerationParams,
        status: (String) -> Unit, fallback: (String) -> Unit): ProcessingMode? {
        val key = key(identity, params.contextSize, params.threads)
        val state = hardware.resources()
        val available = (state.availableMb + session.releasablePrivateMb).coerceAtMost(state.totalMb)
        val needsReload = !session.prepared(ProcessingMode.NPU, params.threads, params.contextSize)
        val required = QualcommNpuSupport.memoryMb(session.file.length(), session.allocationContext(params.contextSize))
        if (state.lowMemory || state.thermal >= 3 || (needsReload && required > ModelMemoryPlanner.budget(state.totalMb, available))) {
            hardware.npuStatus(false, "Qualcomm NPU needs about $required MB of shared RAM and a cool device.")
            check(session.supportsCpu) { "NPU model has no CPU graph and cannot fit safely now. Free memory, cool the device, or select a standard CPU/GPU model." }
            fallback("Qualcomm NPU memory/thermal headroom is insufficient; using the model's CPU/GPU path")
            return null
        }
        if (selector.blocked(key, available)) {
            hardware.npuStatus(false, "NPU retry paused: ${selector.failureReason(key)}")
            check(session.supportsCpu) { "NPU retry paused after a failed/interrupted attempt: ${selector.failureReason(key)}. Select a standard CPU/GPU model or reset backend measurements to retry." }
            return null
        }
        selector.cached(key, session.supportsGpu, available, session.supportsNpu)?.let { cached ->
            hardware.npuStatus(selector.hasValidatedNpu(key), "Cached Qualcomm runtime/decode validation for this exact model and configuration")
            val chosen = if (hardware.mode.value == ProcessingMode.NPU && selector.hasValidatedNpu(key)) ProcessingMode.NPU else cached.backend
            status("${chosen.name} selected from cached CPU/GPU/NPU measurements")
            return chosen.takeIf { it == ProcessingMode.NPU }
        }
        status("Checking Qualcomm NPU correctness and performance…")
        val samples = mutableListOf<BackendMeasurement>()
        val modes = buildList {
            if (session.supportsCpu) add(ProcessingMode.CPU)
            if (session.supportsGpu) add(ProcessingMode.GPU)
            add(ProcessingMode.NPU)
        }
        for (mode in modes) {
            if (hardware.resources().let { it.lowMemory || it.thermal >= 3 }) break
            try {
                if (mode == ProcessingMode.NPU) selector.beginNpuAttempt(key)
                else if (mode == ProcessingMode.GPU) selector.beginGpuAttempt(key)
                val before = hardware.resources()
                session.prepare(mode, params.threads, params.contextSize)
                val initialized = hardware.resources()
                check(!initialized.lowMemory && initialized.availableMb >= 512 && initialized.thermal < 3) { "Memory or thermal pressure after $mode initialization" }
                val probeParams = params.copy(maxTokens = 16, temperature = 0f, history = emptyList(), systemPrompt = null)
                val short = session.generate("What is 17 multiplied by 6? Give only the number.", probeParams)
                check(correct(short.text)) { "$mode short-answer correctness check failed" }
                // Exercise multiple prefill chunks: some NPU exports decode short
                // prompts correctly but corrupt state once input exceeds 128 tokens.
                val long = session.generate("Read these notes before the final question. " +
                    "The notes describe a routine local inference validation. ".repeat(24) +
                    "Final question: what is 17 multiplied by 6? Give only the number.", probeParams)
                check(correct(long.text)) { "$mode multi-chunk prefill correctness check failed" }
                val after = hardware.resources()
                samples += BackendMeasurement(mode, 0, params.threads,
                    (short.tokensPerSecond + long.tokensPerSecond) / 2,
                    (short.firstTokenMs + long.firstTokenMs) / 2, Debug.getPss() / 1024f,
                    session.initializationMs, before.thermal, after.thermal,
                    if (before.batteryC > 0 && after.batteryC > 0) after.batteryC - before.batteryC else 0f,
                    short.outputTokens > 0 && long.outputTokens > 0 && !after.lowMemory && after.availableMb >= 512)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (mode == ProcessingMode.NPU) {
                    selector.failed(key, failure.message.orEmpty())
                    hardware.npuStatus(false, "Qualcomm NPU validation failed: ${failure.message.orEmpty().take(180)}")
                }
                fallback("$mode validation failed: ${failure.message.orEmpty().take(180)}")
            } finally { selector.endGpuAttempt() }
        }
        val best = SmartBackendSelector.choose(samples)
        if (best != null) selector.save(key, best, samples)
        val npuPassed = samples.any { it.backend == ProcessingMode.NPU && it.stable && it.thermalAfter < 3 && it.tokensPerSecond > 0 }
        hardware.npuStatus(npuPassed, if (npuPassed) "Qualcomm QNN initialization, short decode and multi-chunk prefill checks passed"
            else "Qualcomm NPU did not pass runtime validation; CPU/GPU remain available where this model includes them")
        check(best != null || session.supportsCpu) { "Qualcomm NPU could not execute this NPU-only model safely. Select the standard CPU/GPU model. ${selector.failureReason(key)}" }
        val chosen = if (hardware.mode.value == ProcessingMode.NPU && npuPassed) ProcessingMode.NPU else best?.backend
        status("${chosen?.name ?: "CPU"} selected after measured correctness, latency, memory and thermal checks")
        return chosen.takeIf { it == ProcessingMode.NPU }
    }

    companion object {
        internal fun correct(text: String): Boolean = text.trim().removeSurrounding("**").removeSuffix(".") == "102"
    }
}
