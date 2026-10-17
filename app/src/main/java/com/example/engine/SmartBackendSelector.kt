package com.example.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class BackendMeasurement(
    val backend: ProcessingMode, val layers: Int, val threads: Int,
    val tokensPerSecond: Float, val firstTokenMs: Long, val memoryMb: Float,
    val initializationMs: Long, val thermalBefore: Int, val thermalAfter: Int,
    val batteryDeltaC: Float, val stable: Boolean = true
) {
    // Short interactive requests, with cold-load cost amortised over 20 requests.
    // Energy is deliberately absent: Android has no portable GPU/NPU energy meter.
    fun score(): Double = firstTokenMs + 16_000.0 / tokensPerSecond.coerceAtLeast(0.01f) + initializationMs / 20.0 +
        memoryMb * 0.05 + (thermalAfter - thermalBefore).coerceAtLeast(0) * 1000 + batteryDeltaC.coerceAtLeast(0f) * 100
}

/** Bounded persistent cache. Keys include firmware, model identity/quantisation,
 * pinned runtime, context, threads and GPU layers. No manufacturer preference.
 */
class SmartBackendSelector(context: Context? = null, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val RUNTIME = "llama-0.7.0-vulkan-policy-3"
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val FAILURE_COOLDOWN_MS = 24L * 60 * 60 * 1000
        fun key(vararg parts: Any): String = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun choose(measurements: List<BackendMeasurement>): BackendMeasurement? {
            val stable = measurements.filter { it.stable && it.tokensPerSecond > 0 && it.thermalAfter < 3 }
            val cpu = stable.firstOrNull { it.backend == ProcessingMode.CPU }
            val best = stable.minByOrNull { it.score() } ?: return null
            return if (cpu != null && best.backend != ProcessingMode.CPU && best.score() > cpu.score() * 0.9) cpu else best
        }
    }
    private val prefs = context?.getSharedPreferences("backend_decisions", Context.MODE_PRIVATE)
    private val cache = runCatching { JSONObject(prefs?.getString("cache", "{}") ?: "{}") }.getOrDefault(JSONObject())
    private val failures = runCatching { JSONObject(prefs?.getString("failures", "{}") ?: "{}") }.getOrDefault(JSONObject())
    init {
        // A process dying in GPU load/decode leaves this marker. Avoid a crash loop.
        prefs?.getString("pending", null)?.let { failed(it, "Previous GPU attempt did not finish") }
    }
    fun cached(key: String, gpuAvailable: Boolean): BackendMeasurement? = runCatching {
        val j = cache.optJSONObject(key) ?: return null
        if (now() - j.getLong("saved") !in 0..MAX_AGE_MS) return null
        val mode = ProcessingMode.valueOf(j.getString("backend"))
        if (mode == ProcessingMode.GPU && (!gpuAvailable || blocked(key))) return null
        BackendMeasurement(mode, j.getInt("layers"), j.getInt("threads"), j.getDouble("tps").toFloat(), j.getLong("ttft"),
            j.getDouble("memory").toFloat(), j.getLong("init"), 0, 0, 0f)
    }.getOrNull()
    fun save(key: String, result: BackendMeasurement, samples: List<BackendMeasurement>) {
        if (cache.length() >= 32 && !cache.has(key)) cache.remove(cache.keys().asSequence().minByOrNull { cache.optJSONObject(it)?.optLong("saved") ?: 0 } ?: "")
        cache.put(key, encode(result).put("saved", now()).put("measurements", JSONArray(samples.map { encode(it) })))
        prefs?.edit()?.putString("cache", cache.toString())?.apply()
    }
    fun hasValidatedGpu(key: String): Boolean {
        val saved = cache.optJSONObject(key) ?: return false
        if (blocked(key) || now() - saved.optLong("saved") !in 0..MAX_AGE_MS) return false
        val samples = saved.optJSONArray("measurements") ?: return false
        return (0 until samples.length()).any { index -> samples.optJSONObject(index)?.let {
            it.optString("backend") == ProcessingMode.GPU.name && it.optBoolean("stable") && it.optDouble("tps") > 0
        } == true }
    }
    private fun encode(m: BackendMeasurement) = JSONObject().put("backend", m.backend.name).put("layers", m.layers).put("threads", m.threads)
        .put("tps", m.tokensPerSecond.toDouble()).put("ttft", m.firstTokenMs).put("memory", m.memoryMb.toDouble())
        .put("init", m.initializationMs).put("thermalBefore", m.thermalBefore).put("thermalAfter", m.thermalAfter)
        .put("batteryDeltaC", m.batteryDeltaC.toDouble()).put("stable", m.stable)
    fun blocked(key: String): Boolean = failures.optJSONObject(key)?.let { now() - it.optLong("time") in 0..FAILURE_COOLDOWN_MS } ?: false
    fun beginGpuAttempt(key: String) { prefs?.edit()?.putString("pending", key)?.commit() }
    fun endGpuAttempt() { prefs?.edit()?.remove("pending")?.commit() }
    fun failed(key: String, reason: String) {
        if (failures.length() >= 32 && !failures.has(key)) failures.remove(failures.keys().next())
        failures.put(key, JSONObject().put("time", now()).put("reason", reason)); cache.remove(key)
        prefs?.edit()?.putString("cache", cache.toString())?.putString("failures", failures.toString())?.remove("pending")?.commit()
    }
}
