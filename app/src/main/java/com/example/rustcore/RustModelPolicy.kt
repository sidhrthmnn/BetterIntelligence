package com.example.rustcore

import android.os.SystemClock
import com.example.BuildConfig
import com.example.data.ModelEntity
import com.example.engine.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class ModelFailureKind(val transient: Boolean = false) {
    NETWORK(true), DNS(true), AUTHENTICATION, RATE_LIMIT(true), LOAD_TRANSIENT(true), MODEL_MISSING,
    CONTEXT_OVERFLOW, TIMEOUT(true), OUT_OF_MEMORY, PROVIDER(true), UNSUPPORTED, MALFORMED, MALFORMED_RESPONSE(true), CANCELLED, BUSY;
    val neutral get() = this in setOf(CANCELLED, BUSY, CONTEXT_OVERFLOW)
    companion object {
        fun classify(error: Throwable): ModelFailureKind {
            val message = error.message.orEmpty().lowercase()
            return when {
                error is CancellationException -> CANCELLED
                error is java.net.UnknownHostException -> DNS
                error is java.net.SocketTimeoutException -> TIMEOUT
                error is InvalidModelOutputException -> MALFORMED_RESPONSE
                "timed out" in message || "timeout" in message || "time limit" in message -> TIMEOUT
                error is java.io.FileNotFoundException || "file is missing" in message || "file does not exist" in message -> MODEL_MISSING
                "busy" in message || "stop generation" in message -> BUSY
                "exceeds context" in message || "too long" in message -> CONTEXT_OVERFLOW
                "memory" in message || "bad_alloc" in message || " ram" in message -> OUT_OF_MEMORY
                "unsupported" in message || "not support" in message || "no cpu" in message -> UNSUPPORTED
                "checksum" in message || "invalid gguf" in message || "magic" in message || "corrupt" in message -> MALFORMED
                error is java.io.IOException -> LOAD_TRANSIENT
                else -> LOAD_TRANSIENT
            }
        }
    }
}

object RustModelPolicy {
    fun key(model: ModelEntity) = "${model.id}:${model.sha256Checksum.orEmpty().take(64)}:${model.fileSizeMb}:${model.contextLength}"
    fun reset(model: ModelEntity) { RustIntelligence.call("model_reset", JSONObject().put("id", key(model))) }
    fun loading(model: ModelEntity) { RustIntelligence.call("model_loading", JSONObject().put("id", key(model)).put("now_ms", SystemClock.elapsedRealtime())) }
    fun outcome(model: ModelEntity, error: Throwable? = null, budget: Long = 0) {
        RustIntelligence.call("model_outcome", JSONObject().put("id", key(model)).put("now_ms", SystemClock.elapsedRealtime())
            .put("success", error == null).put("failure", error?.let { ModelFailureKind.classify(it).name }).put("budget_mb", budget.coerceAtLeast(0)))
    }
    fun runtimeFailure(key: String, error: Throwable, budget: Long) {
        if (key.isBlank() || ModelFailureKind.classify(error).neutral) return
        // Native GPU/CPU recovery owns backend errors. They do not blacklist
        // a working model merely because one accelerator failed.
        if (Regex("(?i)GPU|Vulkan|NPU").containsMatchIn(error.message.orEmpty())) return
        RustIntelligence.call("model_outcome", JSONObject().put("id", key).put("now_ms", SystemClock.elapsedRealtime())
            .put("success", false).put("failure", ModelFailureKind.classify(error).name).put("budget_mb", budget.coerceAtLeast(0)))
    }

    fun choose(primary: ModelEntity?, secondary: ModelEntity?, current: ModelEntity?, profile: HardwareProfile,
        context: Int, autoFallback: Boolean, preferLoaded: Boolean, busy: Boolean, excluded: Set<Long> = emptySet()): ModelTierDecision {
        val legacy = ModelMemoryPlanner.resolveTier(primary?.takeIf { it.id !in excluded }, secondary?.takeIf { it.id !in excluded }, profile, context, autoFallback)
        if (!BuildConfig.RUST_MODEL_ROUTER) return legacy
        val candidates = listOfNotNull(
            primary?.takeIf { it.id !in excluded }?.let { ModelMemoryPlanner.resolveTier(it, null, profile, context, false) },
            secondary?.takeIf { autoFallback && it.id !in excluded && it.id != primary?.id }?.let { ModelMemoryPlanner.resolveTier(null, it, profile, context, true) }
        ).filter { it.role != ModelTierRole.NONE && it.model != null }
        val items = JSONArray().apply { candidates.forEach { put(JSONObject().put("id", key(it.model!!))
            .put("role", it.role.name).put("context", it.effectiveContext).put("memory_mb", it.estimatedRamMb)) } }
        val result = RustIntelligence.call("plan_model", JSONObject().put("primary_id", primary?.let(::key).orEmpty())
            .put("current_id", current?.let(::key).orEmpty()).put("candidates", items).put("budget_mb", profile.safeModelRamLimitMb.coerceAtLeast(0))
            .put("now_ms", SystemClock.elapsedRealtime()).put("prefer_loaded", preferLoaded).put("busy", busy)
            .put("low_memory", profile.availableRamMb < 512)) ?: return legacy
        val chosen = result.optJSONObject("candidate")
        val reason = result.optString("reason")
        if (chosen == null) return legacy.copy(role = ModelTierRole.NONE,
            reason = when (reason) {
                "ACTIVE_REQUEST" -> "Another answer is running. Wait for it to finish before loading a model."
                "NO_USABLE_MODEL" -> if (candidates.isEmpty()) legacy.reason else "The primary model is recovering after a load failure. Try again shortly or select another downloaded model."
                else -> legacy.reason.ifBlank { "There is not enough free RAM to safely restore the primary model yet." }
            }, failureCode = if (candidates.isEmpty()) "LOW_MEMORY" else reason)
        val decision = candidates.firstOrNull { key(it.model!!) == chosen.optString("id") && it.effectiveContext == chosen.optInt("context") } ?: return legacy
        return decision.copy(reason = when (reason) {
            "PRIMARY_RECOVERY" -> "Restoring the primary model now that it can run safely."
            "PRIMARY_COOLDOWN" -> "Using the secondary model while the primary recovers from a loading failure."
            "RECOVERY_HEADROOM" -> "Keeping the secondary model until there is enough free RAM to restore the primary safely."
            else -> decision.reason
        }, failureCode = "")
    }
}
