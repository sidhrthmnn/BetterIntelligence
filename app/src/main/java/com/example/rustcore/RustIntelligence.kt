package com.example.rustcore

import androidx.annotation.Keep
import com.example.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class IntelligenceMode { LEGACY, RUST }

@Keep
internal object NativeIntelligence {
    val available = try { System.loadLibrary("bi_intelligence"); true } catch (_: LinkageError) { false } catch (_: SecurityException) { false }
    external fun dispatch(request: ByteArray): ByteArray
}

/** Process-local policy only. No weights, database, HTTP client, worker thread,
 * user text logging or persistent JNI references. Android remains the adapter. */
object RustIntelligence {
    private val failed = AtomicBoolean(false)
    @Volatile internal var forceLegacyForTest = false
    private val calls = AtomicLong()
    private val failures = AtomicLong()
    val mode: IntelligenceMode get() = if (BuildConfig.RUST_CORE_ENABLED && !forceLegacyForTest &&
        !failed.get() && NativeIntelligence.available) IntelligenceMode.RUST else IntelligenceMode.LEGACY

    fun call(op: String, args: JSONObject = JSONObject()): JSONObject? {
        if (mode != IntelligenceMode.RUST) return null
        val payload = JSONObject().put("version", 1).put("op", op).put("args", args).toString().toByteArray(Charsets.UTF_8)
        if (payload.size > 262144) return null
        return try {
            val reply = JSONObject(NativeIntelligence.dispatch(payload).toString(Charsets.UTF_8))
            check(reply.optInt("version") == 1)
            calls.incrementAndGet()
            if (reply.optBoolean("ok")) reply.getJSONObject("result") else {
                failures.incrementAndGet()
                if (reply.optString("error") in setOf("STATE_UNAVAILABLE", "INTERNAL_FAILURE", "UNSUPPORTED_VERSION")) failed.set(true)
                null
            }
        } catch (_: LinkageError) { failed.set(true); failures.incrementAndGet(); null }
        catch (_: Exception) { failed.set(true); failures.incrementAndGet(); null }
    }
    fun diagnostics(): JSONObject = JSONObject().put("mode", mode.name).put("apiVersion", 1)
        .put("calls", calls.get()).put("errors", failures.get())
        .put("native", call("info") ?: JSONObject())

    fun queryRoute(permitted: Boolean, requested: Boolean, privateInput: Boolean, offline: Boolean,
        calculation: Boolean, transformation: Boolean, forceWeb: Boolean): Boolean? =
        if (!BuildConfig.RUST_QUERY_ROUTER) null else call("route", JSONObject()
            .put("permitted", permitted).put("requested", requested).put("private_input", privateInput)
            .put("explicit_offline", offline).put("calculation", calculation).put("transformation", transformation)
            .put("force_web", forceWeb))?.optString("route")?.let { it == "WEB_SEARCH" }

    /** Metadata-only context selection: no conversation content crosses JNI. */
    fun selectContext(costs: List<Int>, scores: List<Int>, budget: Int, anchor: Int? = null, maxItems: Int = 64): List<Int>? {
        if (!BuildConfig.RUST_CONTEXT || costs.size > 256 || costs.size != scores.size) return null
        val items = JSONArray().apply { costs.indices.forEach { put(JSONObject().put("cost", costs[it]).put("score", scores[it])) } }
        return call("select_context", JSONObject().put("items", items).put("budget", budget.coerceIn(0, 65536))
            .put("anchor", anchor).put("max_items", maxItems.coerceIn(0, 64)))?.getJSONArray("indices")?.indices(costs.size)
    }
    fun rankPassages(passages: JSONArray, budget: Int, initial: Int, goals: Int, subjects: Int): List<Int>? =
        if (!BuildConfig.RUST_PASSAGE_RANKING) null else call("rank_passages", JSONObject().put("passages", passages)
            .put("budget", budget.coerceIn(0, 65536)).put("initial_cost", initial.coerceAtLeast(0))
            .put("coverage_goals", goals).put("subject_goals", subjects))?.getJSONArray("indices")?.indices(passages.length())

    private fun JSONArray.indices(size: Int): List<Int>? = (0 until length()).map { getInt(it) }
        .takeIf { list -> list.distinct().size == list.size && list.all { it in 0 until size } }
}

/** Kotlin's lifecycle lock remains the native-weight owner. Rust adds a second
 * process-wide admission lease and an ordered cancellation fence, not another
 * model instance. A lease stays in its original mode until it is released. */
internal class IntelligenceRequestGate {
    companion object { private val owners = AtomicLong() }
    private val owner = owners.incrementAndGet()
    private val local = AtomicBoolean(false)
    private var lease = 0L
    private var sequence = 0L
    private val cancelled = AtomicBoolean(false)
    fun get() = local.get()
    @Synchronized fun compareAndSet(expected: Boolean, value: Boolean): Boolean {
        check(!expected && value)
        if (!local.compareAndSet(false, true)) return false
        cancelled.set(false); sequence = 0
        if (BuildConfig.RUST_REQUEST_GATE) {
            val result = RustIntelligence.call("acquire", JSONObject().put("owner", owner))
            if (result != null) {
                lease = result.optLong("lease", 0)
                if (lease == 0L) { local.set(false); return false }
            }
        }
        return true
    }
    @Synchronized fun allowToken(): Boolean {
        if (!local.get() || cancelled.get()) return false
        if (lease == 0L) return true
        return RustIntelligence.call("accept", JSONObject().put("owner", owner).put("lease", lease)
            .put("sequence", sequence++))?.optBoolean("accepted") ?: true
    }
    @Synchronized fun cancel() {
        cancelled.set(true)
        if (lease != 0L) RustIntelligence.call("cancel", JSONObject().put("owner", owner).put("lease", lease))
    }
    @Synchronized fun set(value: Boolean) {
        check(!value)
        if (lease != 0L) RustIntelligence.call("finish", JSONObject().put("owner", owner).put("lease", lease))
        lease = 0; local.set(false)
    }
}
