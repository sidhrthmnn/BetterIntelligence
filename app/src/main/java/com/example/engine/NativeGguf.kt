package com.example.engine

import androidx.annotation.Keep
import kotlinx.coroutines.Job

@Keep
class NativeObserver(private val job: Job?, private val resources: () -> Int = { 1 },
    private val performance: (Double, Double, Int, Int) -> Unit = { _, _, _, _ -> },
    private val update: (ByteArray, Int, Int) -> Unit) {
    @Keep fun isCancelled(): Boolean = job?.isActive == false
    @Keep fun onText(text: ByteArray, promptTokens: Int, completionTokens: Int) = update(text, promptTokens, completionTokens)
    @Keep fun resourceThreads(): Int = resources()
    @Keep fun onPerformance(promptMs: Double, decodeMs: Double, promptTokens: Int, decodedTokens: Int) = performance(promptMs, decodeMs, promptTokens, decodedTokens)
}

@Keep
internal object NativeGguf {
    private val loadError = try { System.loadLibrary("better_gguf"); null } catch (e: LinkageError) { e }
    fun ensureAvailable() { check(loadError == null) { "GGUF runtime requires an arm64-v8a or x86_64 device: $loadError" } }
    external fun runtimeInfo(allowGpuProbe: Boolean): ByteArray
    external fun prepare(path: ByteArray, gpuLayers: Int, threads: Int, contextSize: Int): Int
    external fun release()
    external fun generate(prompt: ByteArray, systemPrompt: ByteArray, observer: NativeObserver, maxTokens: Int, threads: Int,
                          contextSize: Int, temperature: Float, topP: Float, topK: Int, timeoutMs: Int,
                          historyRoles: Array<ByteArray>, historyContents: Array<ByteArray>): ByteArray
}

/** Injectable boundary for testing failure recovery without pretending to execute hardware. */
internal interface GgufRuntime {
    fun info(allowGpuProbe: Boolean): String
    fun prepare(file: java.io.File, layers: Int, threads: Int, context: Int): Int
    fun release()
    fun generate(prompt: String, system: String, observer: NativeObserver, params: GenerationParams, timeoutMs: Int): String
}

internal object JniGgufRuntime : GgufRuntime {
    override fun info(allowGpuProbe: Boolean): String { NativeGguf.ensureAvailable(); return NativeGguf.runtimeInfo(allowGpuProbe).toString(Charsets.UTF_8) }
    override fun prepare(file: java.io.File, layers: Int, threads: Int, context: Int): Int {
        NativeGguf.ensureAvailable()
        return NativeGguf.prepare(file.absolutePath.toByteArray(Charsets.UTF_8), layers, threads, context)
    }
    override fun release() = NativeGguf.release()
    override fun generate(prompt: String, system: String, observer: NativeObserver, params: GenerationParams, timeoutMs: Int): String =
        NativeGguf.generate(prompt.toByteArray(Charsets.UTF_8), system.toByteArray(Charsets.UTF_8), observer,
            params.maxTokens.coerceIn(1, 1024), params.threads.coerceIn(1, 8), params.contextSize.coerceIn(512, 4096),
            params.temperature.coerceIn(0f, 2f), params.topP.coerceIn(0.1f, 1f), params.topK.coerceIn(1, 100), timeoutMs,
            params.history.map { it.role.toByteArray(Charsets.UTF_8) }.toTypedArray(),
            params.history.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray()).toString(Charsets.UTF_8)
}
