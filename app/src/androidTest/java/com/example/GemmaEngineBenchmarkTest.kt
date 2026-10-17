package com.example

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.NativeGguf
import com.example.engine.NativeObserver
import com.google.ai.edge.litertlm.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Opt-in physical-device experiment. Never runs unless a staged model is supplied.
 * Each invocation tests one runtime/backend in a fresh instrumentation process.
 * Raw output is retained for quality review; speed alone is not a correctness test.
 */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class GemmaEngineBenchmarkTest {
    @Test fun compareStagedModel() {
        val args = InstrumentationRegistry.getArguments()
        val filename = args.getString("gemmaModel") ?: return
        require(File(filename).name == filename)
        val runtime = args.getString("gemmaRuntime") ?: "litert"
        val backend = args.getString("gemmaBackend") ?: "CPU"
        val rounds = (args.getString("gemmaRounds")?.toIntOrNull() ?: 3).coerceIn(1, 5)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = File(context.filesDir, "models/$filename")
        require(model.isFile) { "Stage $filename in the validation app first" }
        val samples = JSONArray()
        val result = JSONObject().put("model", filename).put("runtime", runtime)
            .put("backendRequested", backend).put("device", Build.FINGERPRINT)
            .put("threads", 4).put("context", 2048).put("maxOutputTokens", 96)
            .put("temperature", 0).put("samples", samples)
        val report = File(context.filesDir, "gemma-$runtime-$backend-${filename.substringBefore('.')}.json")
        fun save() = report.writeText(result.toString(2))
        val monitor = AtomicBoolean(true)
        val peakPss = AtomicLong()
        val sampler = thread(name = "benchmark-memory") {
            while (monitor.get()) {
                peakPss.accumulateAndGet(Debug.getPss().toLong()) { a, b -> maxOf(a, b) }
                Thread.sleep(200)
            }
        }
        val power = context.getSystemService(PowerManager::class.java)
        fun thermal() = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
        var lite: Engine? = null
        var nativeLoaded = false
        val initStart = SystemClock.elapsedRealtimeNanos()
        try {
            result.put("thermalBefore", thermal())
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            result.put("availableRamBeforeBytes", memory.availMem).put("totalRamBytes", memory.totalMem)
            save() // Preserve setup even if a native driver terminates the process.
            if (runtime == "litert") {
                ModelInfo.from(model.absolutePath).use { info ->
                    result.put("modelBackends", JSONArray(info.supportedBackends(Modality.TEXT).map { it.name }))
                        .put("modelMaxContext", info.maxContextTokens()).put("minRuntime", info.minRuntimeVersion())
                    save()
                }
                ExperimentalFlags.enableBenchmark = true
                lite = Engine(EngineConfig(modelPath = model.absolutePath,
                    backend = if (backend == "GPU") Backend.GPU() else Backend.CPU(4),
                    maxNumTokens = 2048, cacheDir = File(context.cacheDir, "litert-benchmark").apply { mkdirs() }.absolutePath))
                lite.initialize()
            } else {
                NativeGguf.ensureAvailable()
                nativeLoaded = true // Release even if a driver fails part-way through prepare.
                val layers = NativeGguf.prepare(model.absolutePath.toByteArray(), if (backend == "GPU") 8 else 0, 4, 2048)
                result.put("actualGpuLayers", layers).put("nativeRuntime", JSONObject(NativeGguf.runtimeInfo(backend == "GPU").toString(Charsets.UTF_8)))
            }
            result.put("initializationMs", (SystemClock.elapsedRealtimeNanos() - initStart) / 1e6)
            save()
            val prompts = listOf(
                "Rewrite this message with correct grammar. Return only the rewritten message: i will be their in ten minute please wait for me",
                "What is 17 multiplied by 6? Give only the number.",
                "Correct the grammar without changing the language. Return only the corrected sentence: ഞാൻ നാളെ ഓഫീസിൽ വരില്ല കാരണം എനിക്ക് പനി ആണ്.",
                "Polish this Manglish message, keeping Malayalam written in Latin letters. Return only the message: njan innu late aavum nee wait cheyyamo",
                "Count from one to twenty, separated by commas."
            )
            repeat(rounds) { round ->
                for ((index, prompt) in prompts.withIndex()) {
                    val sample = JSONObject().put("round", round).put("promptIndex", index).put("prompt", prompt)
                        .put("thermalBefore", thermal()).put("status", "running")
                    samples.put(sample); save()
                    val start = SystemClock.elapsedRealtimeNanos()
                    var firstMs = 0.0
                    if (lite != null) {
                        lite.createConversation(ConversationConfig(
                            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                            maxOutputToken = 96, thinkingConfig = ThinkingConfig(enableThinking = false)
                        )).use { conversation ->
                            val done = CountDownLatch(1)
                            val output = StringBuilder()
                            var failure: Throwable? = null
                            conversation.sendMessageAsync(prompt, object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    if (firstMs == 0.0) firstMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                                    output.append(message.toString())
                                }
                                override fun onDone() { done.countDown() }
                                override fun onError(throwable: Throwable) { failure = throwable; done.countDown() }
                            })
                            if (!done.await(180, TimeUnit.SECONDS)) {
                                conversation.cancelProcess()
                                check(done.await(30, TimeUnit.SECONDS)) { "Cancellation did not finish; terminate validation process before another run" }
                                error("Generation exceeded 180 seconds")
                            }
                            failure?.let { throw it }
                            val metrics = conversation.getBenchmarkInfo()
                            sample.put("response", output.toString()).put("decodeTokens", metrics.lastDecodeTokenCount)
                                .put("prefillTokens", metrics.lastPrefillTokenCount)
                                .put("decodeTokensPerSecond", metrics.lastDecodeTokensPerSecond)
                                .put("runtimeTtftMs", metrics.timeToFirstTokenInSecond * 1000)
                        }
                    } else {
                        val output = NativeGguf.generate(prompt.toByteArray(), byteArrayOf(), NativeObserver(null, { 4 },
                            { prefillMs, decodeMs, input, count ->
                                sample.put("prefillMs", prefillMs).put("decodeMs", decodeMs)
                                    .put("prefillTokens", input).put("decodeTokens", count)
                                    .put("decodeTokensPerSecond", if (decodeMs > 0) count * 1000.0 / decodeMs else 0.0)
                            }, { _, _, _ -> if (firstMs == 0.0) firstMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6 }),
                            96, 4, 2048, 0f, 1f, 1, 180_000).toString(Charsets.UTF_8)
                        sample.put("response", output)
                    }
                    sample.put("wallMs", (SystemClock.elapsedRealtimeNanos() - start) / 1e6)
                        .put("firstCallbackMs", firstMs).put("peakProcessPssKb", peakPss.get())
                        .put("thermalAfter", thermal()).put("status", "complete")
                    save()
                }
            }
            result.put("status", "complete")
        } catch (failure: Throwable) {
            result.put("status", "failed").put("error", failure.stackTraceToString())
            throw failure
        } finally {
            if (lite?.isInitialized() == true) lite.close()
            if (nativeLoaded) NativeGguf.release()
            monitor.set(false); sampler.join(1000)
            result.put("peakProcessPssKb", peakPss.get()).put("thermalAfter", thermal())
            save()
        }
    }
}
