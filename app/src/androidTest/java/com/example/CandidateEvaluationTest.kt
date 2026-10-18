package com.example

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.*
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Explicitly opt-in. Uses only synthetic test assets in a separate application identity. */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class CandidateEvaluationTest {
    @Test fun evaluateStagedCandidate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val name = arguments.getString("candidateModel") ?: return@runBlocking
        require(File(name).name == name)
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation")) { "Candidate experiments require the isolated validation package" }
        val backend = arguments.getString("candidateBackend") ?: "CPU"
        val contextSize = (arguments.getString("candidateContext")?.toIntOrNull() ?: 2048).coerceIn(512, 2048)
        val quality = arguments.getString("candidateQuality") == "true"
        val chatQuality = arguments.getString("candidateChatQuality") == "true"
        val outputLimit = arguments.getString("candidateMaxOutputTokens")?.toIntOrNull()?.coerceIn(1, 1024)
        val limitSuffix = outputLimit?.let { "-max$it" }.orEmpty()
        val phase = if (quality) "quality" else if (chatQuality) "chat" else "performance"
        val file = File(context.filesDir, "models/$name")
        require(file.isFile)
        val gguf = name.endsWith(".gguf", true)
        val report = JSONObject().put("model", name).put("device", Build.FINGERPRINT)
            .put("requestedBackend", backend).put("requestedContext", contextSize).put("runtime", if (gguf) "llama.cpp 0.7.0" else "LiteRT-LM 0.18.0")
            .put("quality", quality).put("chatQuality", chatQuality).put("chargingDuringTest", true)
            .put("backendPolicy", if (gguf) SmartBackendSelector.RUNTIME else SmartBackendSelector.LITERT_RUNTIME).put("harnessRevision", "2026-10-18-c")
        val output = File(context.filesDir, "candidate-$phase-$backend-$contextSize$limitSuffix-$name.json")
        report.put("outputTokenLimitOverride", outputLimit)
        val samples = JSONArray(); report.put("samples", samples)
        fun save() = output.writeText(report.toString(2))
        val am = context.getSystemService(ActivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        fun resources(): JSONObject {
            val memory = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val temperature = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            return JSONObject().put("availableRamBytes", memory.availMem).put("totalRamBytes", memory.totalMem).put("lowMemory", memory.lowMemory)
                .put("processPssKb", Debug.getPss()).put("thermalStatus", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1)
                .put("batteryTemperatureTenthsC", temperature).put("chargeCounterMicroAh", battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
        }
        fun driverMemory(): JSONObject {
            val text = instrumentation.uiAutomation.executeShellCommand("dumpsys meminfo").use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
            }
            val result = JSONObject().put("scope", "SYSTEM_WIDE_NOT_APP_ATTRIBUTABLE")
            listOf("GPU", "DMA-BUF").forEach { label ->
                Regex("(?m)^\\s*${Regex.escape(label)}:\\s*([\\d,]+)K").find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()?.let { result.put("${label}Kb", it) }
            }
            return result
        }
        report.put("before", resources()).put("driverBefore", driverMemory()).put("status", "initializing"); save()
        val peakPss = AtomicLong(Debug.getPss().toLong()); val monitor = AtomicBoolean(true)
        val sampler = thread(name = "candidate-memory") {
            while (monitor.get()) { peakPss.accumulateAndGet(Debug.getPss().toLong(), ::maxOf); Thread.sleep(250) }
        }
        var session: LiteRtSession? = null
        var core: InferenceEngine? = null
        try {
            if (!gguf) ModelInfo.from(file.absolutePath).use {
                report.put("declaredBackends", JSONArray(it.supportedBackends(Modality.TEXT).map { b -> b.name }))
                    .put("declaredMaxContext", it.maxContextTokens()).put("minimumRuntime", it.minRuntimeVersion())
                if (it is ModelInfo.Llm) report.put("dynamicContext", it.isDynamicContext()).put("supportsThinking", it.supportsThinking()).put("speculativeDecodingSupported", it.hasSpeculativeDecodingSupport())
            }
            val started = SystemClock.elapsedRealtime()
            var actualContext: Int
            if (backend == "AUTO" || gguf) {
                core = InferenceEngine(context)
                if (arguments.getString("candidateResetCache") == "true") core.resetBackendDecisions()
                val identity = arguments.getString("candidateSha256").orEmpty()
                if (gguf) core.loadCustomGguf(file, identity).getOrThrow()
                else core.loadLiteRt(file, identity = identity, initialContext = contextSize).getOrThrow()
                core.setProcessingMode(ProcessingMode.valueOf(backend))
                actualContext = minOf(contextSize, core.activeContextLength)
            } else {
                val attentionOnly = name.startsWith("gemma-4-") || name.startsWith("qwen3_0.6b_") || name.startsWith("Qwen2.5-") || name.startsWith("Phi-4-") || name.startsWith("gemma3-")
                session = LiteRtSession(context, file, attentionOnly, arguments.getString("candidateSha256").orEmpty())
                actualContext = minOf(contextSize, session.maximumContext)
                session.prepare(ProcessingMode.valueOf(backend), 4, actualContext)
            }
            report.put("initializationMs", SystemClock.elapsedRealtime() - started).put("configuredContext", actualContext)
                .put("allocationContextEstimate", session?.allocationContext(actualContext) ?: core?.allocatedLiteRtContext)
                .put("afterInitialization", resources()).put("driverAfterInitialization", driverMemory()); save()
            val tests = if (quality || chatQuality) {
                val asset = if (quality) "proofreading-cases.json" else "chat-quality-cases.json"
                val array = JSONArray(instrumentation.context.assets.open(asset).bufferedReader().use { it.readText() })
                (0 until array.length()).map { array.getJSONObject(it) }
            } else listOf(
                JSONObject().put("id", "math").put("prompt", "What is 17 multiplied by 6? Return only the number."),
                JSONObject().put("id", "polish").put("prompt", "Correct only spelling and grammar. Return only the corrected text: i will be their in ten minute please wait for me"),
                JSONObject().put("id", "decode").put("prompt", "Count from one to thirty, separated by commas.")
            )
            val rounds = if (quality || chatQuality) 1 else 2
            repeat(rounds) { round ->
                for (test in tests) {
                    val before = resources()
                    check(!before.getBoolean("lowMemory") && before.getLong("availableRamBytes") > 384L * 1024 * 1024) { "Memory pressure: stop experiment safely" }
                    check(before.getInt("thermalStatus") < 4) { "Severe thermal throttling: cool down before another run" }
                    val prompt = test.optString("prompt").ifBlank { "Proofread the following text. Correct only clear spelling, grammar and punctuation errors. Preserve language, names, numbers, meaning and negation. Keep Manglish in Latin letters. Leave correct text unchanged. Return only the corrected text, without quotes or explanations.\n\n" + test.getString("input") }
                    val sample = JSONObject().put("id", test.getString("id")).put("round", round).put("before", before).put("status", "running")
                    if (quality || chatQuality) sample.put("case", test)
                    samples.put(sample); save()
                    val start = SystemClock.elapsedRealtime()
                    val params = GenerationParams(temperature = 0f, topK = 1, topP = 1f,
                        maxTokens = outputLimit?.coerceAtMost(actualContext / 2) ?: if (chatQuality || (quality && test.optString("category") == "long")) minOf(192, actualContext / 3) else minOf(96, actualContext / 4), contextSize = actualContext, threads = 4)
                    if (session != null) {
                        val result = session.generate(prompt, params)
                        sample.put("response", result.text).put("firstTokenMs", result.firstTokenMs)
                            .put("tokensPerSecond", result.tokensPerSecond).put("inputTokens", result.inputTokens).put("outputTokens", result.outputTokens)
                            .put("actualBackend", session.backend.name)
                    } else {
                        val result = core!!.generateSync(prompt, params)
                        val telemetry = core.telemetry.value
                        sample.put("response", result).put("firstTokenMs", telemetry.timeToFirstTokenMs)
                            .put("tokensPerSecond", telemetry.tokensPerSecond).put("inputTokens", telemetry.promptTokens).put("outputTokens", telemetry.completionTokens)
                            .put("actualBackend", telemetry.activeBackend).put("fallback", telemetry.lastFallback)
                            .put("backendDecision", telemetry.backendDecision)
                    }
                    sample.put("durationMs", SystemClock.elapsedRealtime() - start).put("after", resources()).put("peakProcessPssKb", peakPss.get()).put("status", "complete")
                    save()
                }
            }
            val fellBack = backend == "GPU" && (0 until samples.length()).any { samples.getJSONObject(it).optString("actualBackend") != "GPU" }
            report.put("status", if (fellBack) "fallback" else "complete")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString()); throw failure
        } finally {
            session?.close(); core?.unloadActiveModel()
            monitor.set(false); sampler.join(2000)
            report.put("after", resources()).put("driverAfter", driverMemory()).put("peakProcessPssKb", peakPss.get())
                .put("modelMappingsRetainedAfterClose", File("/proc/self/maps").useLines { lines -> lines.any { it.endsWith("/$name") } })
            save()
        }
    }
}
