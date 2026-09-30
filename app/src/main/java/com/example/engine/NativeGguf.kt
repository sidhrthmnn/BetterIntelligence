package com.example.engine

import androidx.annotation.Keep
import kotlinx.coroutines.Job

@Keep
class NativeObserver(private val job: Job?, private val update: (ByteArray, Int, Int) -> Unit) {
    @Keep fun isCancelled(): Boolean = job?.isActive == false
    @Keep fun onText(text: ByteArray, promptTokens: Int, completionTokens: Int) = update(text, promptTokens, completionTokens)
}

@Keep
internal object NativeGguf {
    private val loadError = try { System.loadLibrary("better_gguf"); null } catch (e: LinkageError) { e }
    fun ensureAvailable() { check(loadError == null) { "GGUF runtime requires an arm64-v8a or x86_64 device: $loadError" } }
    external fun prepare(path: ByteArray)
    external fun release()
    external fun generate(prompt: ByteArray, observer: NativeObserver, maxTokens: Int, threads: Int,
                          contextSize: Int, temperature: Float, topP: Float, topK: Int): ByteArray
}
