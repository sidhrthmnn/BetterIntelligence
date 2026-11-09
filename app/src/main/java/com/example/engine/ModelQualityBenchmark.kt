package com.example.engine

import android.os.Build
import android.os.SystemClock
import com.example.data.ModelEntity
import org.json.JSONObject
import java.security.MessageDigest
import kotlinx.coroutines.withTimeout

data class QualityProbe(val id: String, val prompt: String, val weight: Int, val accepts: (String) -> Boolean)
data class ModelQualityReport(val modelId: Long, val key: String, val quality: Int, val passed: Boolean,
    val loadMs: Long, val firstTokenMs: Long, val tokensPerSecond: Float, val promptTokensPerSecond: Float,
    val peakMb: Long, val backend: String, val thermal: String, val durationMs: Long, val checks: Map<String, Boolean>) {
    val score: Double get() = quality * .85 + (tokensPerSecond.coerceIn(0f, 40f) / 40 * 10) + (if (passed) 5 else 0)
    fun json() = JSONObject().put("modelId", modelId).put("key", key).put("quality", quality).put("passed", passed)
        .put("loadMs", loadMs).put("firstTokenMs", firstTokenMs).put("tokensPerSecond", tokensPerSecond)
        .put("promptTokensPerSecond", promptTokensPerSecond).put("peakMb", peakMb).put("backend", backend)
        .put("thermal", thermal).put("durationMs", durationMs).put("checks", JSONObject(checks))
    companion object { fun from(j: JSONObject) = ModelQualityReport(j.getLong("modelId"), j.getString("key"), j.getInt("quality"), j.getBoolean("passed"),
        j.getLong("loadMs"), j.getLong("firstTokenMs"), j.getDouble("tokensPerSecond").toFloat(), j.optDouble("promptTokensPerSecond", 0.0).toFloat(),
        j.getLong("peakMb"), j.getString("backend"), j.getString("thermal"), j.getLong("durationMs"),
        j.getJSONObject("checks").let { o -> o.keys().asSequence().associateWith { o.getBoolean(it) } }) }
}
object ModelQualityBenchmark {
    const val VERSION = "quality-2"
    fun stable(first: String, repeated: String): Boolean = first.isNotBlank() && repeated.isNotBlank() &&
        (first.trim().replace(Regex("\\s+"), " ") == repeated.trim().replace(Regex("\\s+"), " ") ||
            probes.first { it.id == "reasoning" }.let { it.accepts(first) && it.accepts(repeated) })
    fun qualifies(quality: Int, checks: Map<String, Boolean>) = quality >= 65 &&
        listOf("fact", "reasoning", "evidence", "stability").all { checks[it] == true }
    fun key(model: ModelEntity, context: Int = 1024): String = MessageDigest.getInstance("SHA-256").digest(
        listOf(Build.FINGERPRINT, model.sha256Checksum, model.quantization, SmartBackendSelector.RUNTIME, SmartBackendSelector.LITERT_RUNTIME, QualcommNpuSupport.RUNTIME, VERSION, context).joinToString(":").toByteArray())
        .joinToString("") { "%02x".format(it) }
    val probes = listOf(
        QualityProbe("fact", "Which gas do plants absorb during photosynthesis? Answer briefly.", 15) {
            Regex("(?i)carbon dioxide|CO2|CO₂").containsMatchIn(it) &&
                !Regex("(?i)(?:not|never|rather than|instead of)\\s+(?:absorb\\s+)?(?:carbon dioxide|CO2|CO₂)|(?:carbon dioxide|CO2|CO₂)\\s+is\\s+not\\s+absorbed").containsMatchIn(it) },
        QualityProbe("instruction", "Reply with exactly: blue", 10) { it.trim().equals("blue", true) },
        QualityProbe("reasoning", "There are 3 boxes with 4 red balls each. Two red balls are removed. How many red balls remain? Reply with the number.", 15) { it.trim().trimEnd('.') == "10" },
        QualityProbe("json", "Return only a JSON object with key ok and boolean value true.", 10) {
            val json = it.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            json.startsWith("{") && runCatching { JSONObject(json).getBoolean("ok") }.getOrDefault(false) },
        QualityProbe("summary", "Summarise in one short sentence: The train leaves at 09:10. It arrives at 10:20. Tickets cost 8 euros. Preserve the ticket price.", 10) { Regex("(?i)8\\s*(?:euros?|€)|€\\s*8").containsMatchIn(it) && it.length < 350 },
        QualityProbe("evidence", "Source [1]: The fictional Riverton museum opens at 11:30 on Mondays. On Tuesdays it is closed. Using only this source, when does it open on Monday? Cite [1].", 15) { "11:30" in it && "[1]" in it },
        QualityProbe("unknown", "What is the exact temperature in my kitchen right now? You have no sensor, location or readings. Do not guess.", 10) {
            Regex("(?i)cannot|can't|don['’]t (?:know|have|remember)|do not (?:know|have)|no (?:access|sensor)|unable|not (?:have|available)").containsMatchIn(it) &&
                !Regex("(?i)[0-9]+(?:\\.[0-9]+)?\\s*(?:°|degrees?|celsius|fahrenheit|kelvin|[cfk]\\b)").containsMatchIn(it) },
        QualityProbe("context", "Remember the fictional access label ORBIT-CEDAR. Ignore the following filler: "+"green leaf ".repeat(180)+" What was the access label? Reply only with that label.", 10) { "ORBIT-CEDAR" in it },
        QualityProbe("continuity", "Conversation: User: My fictional pet is named Nori. Assistant: I understand. User: What is my pet called? Answer only the name.", 5) {
            it.length < 150 && Regex("(?i)\\bNori\\b").containsMatchIn(it) &&
                !Regex("(?i)\\b(?:not|isn't|isn’t|maybe|might|could|unknown|or|cannot)\\b|don['’]t know").containsMatchIn(it) }
    )
    suspend fun run(engine: InferenceEngine, model: ModelEntity, loadMs: Long, ready: () -> Boolean, progress: (String) -> Unit): ModelQualityReport {
        val start = SystemClock.elapsedRealtime(); val checks = linkedMapOf<String, Boolean>(); val samples = mutableListOf<HardwareTelemetry>()
        var firstReasoning = ""
        for (probe in probes + probes.filter { it.id == "reasoning" }.map { it.copy(id = "stability") }) {
            check(ready()) { "Benchmark deferred: battery, memory or thermal conditions changed" }
            progress("Checking ${model.name}: ${probe.id}")
            val response = withTimeout(45_000) { engine.generateSync(probe.prompt, GenerationParams(contextSize = ModelLibrary.configuredContext(model, 1024),
                maxTokens = 96, temperature = 0f, topK = 1, topP = 1f, history = emptyList(), autoContinue = false)) }
            if (probe.id == "reasoning") firstReasoning = response
            checks[probe.id] = if (probe.id == "stability") engine.isReady() && stable(firstReasoning, response) else probe.accepts(response)
            samples += engine.telemetry.value
        }
        val quality = probes.sumOf { if (checks[it.id] == true) it.weight else 0 }
        val configuration = listOf(engine.hardware.mode.value, engine.hardware.layerLimit.value, samples.last().activeBackend,
            samples.last().activeThreads, samples.last().activeGpuLayers).joinToString(":")
        return ModelQualityReport(model.id, key(model, ModelLibrary.configuredContext(model, 1024)) + ":" + configuration, quality,
            qualifies(quality, checks) && samples.none { it.lastFallback.isNotBlank() && !it.isModelLoaded }, loadMs,
            samples.map { it.timeToFirstTokenMs }.average().toLong(), samples.map { it.tokensPerSecond }.average().toFloat(),
            samples.map { if (it.promptEvaluationTimeMs > 0) it.promptTokens * 1000f / it.promptEvaluationTimeMs else 0f }.average().toFloat(),
            samples.maxOf { maxOf(it.memoryUsageMb, it.peakMemoryUsageMb).toLong() }, samples.last().activeBackend,
            samples.last().thermalState, SystemClock.elapsedRealtime() - start, checks)
    }
}
