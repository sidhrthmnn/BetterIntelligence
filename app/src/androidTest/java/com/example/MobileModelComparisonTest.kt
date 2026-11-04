package com.example

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Same short synthetic corpus, CPU/2K/4 threads, two repetitions. This is a
 * narrow comparison, not a general reasoning benchmark or tool-execution agent. */
class MobileModelComparisonTest {
    private data class Task(val id: String, val prompt: String, val expected: String, val jsonRoute: Boolean = false)
    private val tasks = listOf(
        Task("grammar", "Correct the grammar. Return only the corrected sentence: She have two book.", "(?i)she has two books"),
        Task("arithmetic_reasoning", "A shop has 12 apples, sells 3, then sells 4 more. How many apples remain? Reply with only the number.", "(?i)^\\s*(?:5|five)[.!]?\\s*$"),
        Task("constraint_reasoning", "Lena is older than Mira. Mira is older than Noor. Who is youngest? Reply only with the name.", "(?i)^\\s*Noor[.!]?\\s*$"),
        Task("public_tool_route", "Choose a route for the user's task. Output JSON only, exactly {\"route\":\"web\"} or {\"route\":\"local\"}. Web is permitted. Current public facts require web; writing and private notes stay local. User: What is the current Jubilee line status?", "web", true),
        Task("private_tool_route", "Choose a route for the user's task. Output JSON only, exactly {\"route\":\"web\"} or {\"route\":\"local\"}. Writing and private notes must stay local. User: Rewrite my private notes clearly.", "local", true),
        Task("evidence_accuracy", "Use only this synthetic source: Orion requires 4 GB RAM and does not support image input. What RAM does Orion require, and does it support images? Answer in one sentence.", "(?is)(?=.*4\\s*GB)(?=.*(?:not|no|doesn't)).*"),
        Task("context_followup", "What is the app name I just gave you? Reply only with the name.", "(?i)^\\s*Aurora[.!]?\\s*$")
    )
    @Test fun compareStagedCpuModels(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mobileComparison") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val requested = InstrumentationRegistry.getArguments().getString("comparisonModel")
        val catalog = ModelLibrary.read(context)
        val models = listOf("LFM3-1.7B-QAD-Q4_0.gguf", "Qwen3.5-2B-Q4_K_M.gguf", "Qwen3.5-4B-Q4_K_M.gguf", "gemma-4-E2B_q4_0-it.gguf")
            .filter { requested == null || it == requested }.map { name -> catalog.single { it.filename == name } }
        val report = JSONObject().put("device", Build.MODEL).put("android", Build.VERSION.RELEASE)
            .put("runtime", SmartBackendSelector.RUNTIME).put("context", 2048).put("threads", 4)
            .put("temperature", 0).put("maxOutputTokens", 128).put("scope", "Synthetic text-only comparison. JSON routing is scored but never executed. No vision/audio/NPU validation. Timings include the normal BI generation path.")
        val results = JSONArray(); report.put("models", results)
        val output = File(context.filesDir, "mobile-comparison${requested?.let { "-$it" }.orEmpty()}.json")
        val manager = context.getSystemService(ActivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        var loaded = 0
        for (model in models) {
            val core = InferenceEngine(context)
            val row = JSONObject().put("model", model.name).put("filename", model.filename).put("sha256", model.sha256Checksum)
            val samples = JSONArray(); row.put("samples", samples); results.put(row)
            val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            val estimate = ModelLibrary.memoryEstimate(model, 2048)
            if (memory.lowMemory || estimate.totalMb + 512 > memory.availMem / 1048576) {
                row.put("status", "SKIPPED_LOW_MEMORY").put("availableMb", memory.availMem / 1048576).put("estimatedModelMb", estimate.totalMb)
                output.writeText(report.toString(2)); continue
            }
            try {
                val start = SystemClock.elapsedRealtime()
                core.setProcessingMode(ProcessingMode.CPU)
                core.loadCustomGguf(File(context.filesDir, "models/${model.filename}"), model.sha256Checksum.orEmpty()).getOrThrow()
                row.put("loadMs", SystemClock.elapsedRealtime() - start).put("estimatedModelMb", estimate.totalMb)
                loaded++
                // Warm-up is separate from measured samples.
                core.generateSync("Reply with OK.", GenerationParams(contextSize = 2048, maxTokens = 8, temperature = 0f, threads = 4))
                for (trial in 1..2) for (task in tasks) {
                    val peak = AtomicLong(Debug.getPss() / 1024)
                    val monitor = launch(Dispatchers.IO) {
                        while (isActive) { peak.updateAndGet { maxOf(it, Debug.getPss() / 1024) }; delay(250) }
                    }
                    val began = SystemClock.elapsedRealtime()
                    val answer = try { withTimeout(90_000) { core.generateSync(task.prompt, GenerationParams(contextSize = 2048,
                        maxTokens = 128, temperature = 0f, threads = 4,
                        history = if (task.id == "context_followup") listOf(ChatTurn("user", "My app is named Aurora."), ChatTurn("assistant", "I will remember the app name.")) else emptyList())) } }
                    finally { monitor.cancelAndJoin() }
                    val data = core.telemetry.value
                    val matched = if (task.jsonRoute) runCatching {
                        val json = Regex("\\{[^{}]+}").find(answer)?.value ?: ""
                        JSONObject(json).optString("route") == task.expected
                    }.getOrDefault(false) else Regex(task.expected).containsMatchIn(answer)
                    samples.put(JSONObject().put("trial", trial).put("task", task.id).put("answer", answer).put("criterionMatched", matched)
                        .put("elapsedMs", SystemClock.elapsedRealtime() - began).put("firstTokenMs", data.timeToFirstTokenMs)
                        .put("tokensPerSecond", data.tokensPerSecond).put("outputTokens", data.completionTokens)
                        .put("peakSampledPssMb", peak.get()).put("thermalStatus", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1))
                    output.writeText(report.toString(2))
                    assertTrue("${model.name} returned no visible text", answer.isNotBlank())
                    assertFalse("${model.name} emitted raw reserved tokens", Regex("<unused\\d+>").containsMatchIn(answer))
                }
                row.put("status", "COMPLETE")
            } catch (error: Exception) {
                row.put("status", "FAILED").put("errorType", error.javaClass.simpleName)
                throw error
            } finally { core.unloadActiveModel(); output.writeText(report.toString(2)) }
        }
        assertTrue("No model could be loaded with the available RAM", loaded > 0)
    }
}
