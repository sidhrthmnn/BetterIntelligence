package com.example.grounding

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded app-private cache, not Logcat or analytics. No response text, source
 * passages, secrets, or conversation history. Clearing search data deletes it. */
object ResearchDiagnostics {
    private var generation = 0L
    @Synchronized fun generation() = generation
    private fun file(context: Context) = File(context.cacheDir, "research-diagnostics.json")
    private fun safeQuery(value: String): String = value.takeIf { ConversationalResearch.validPublicQuery(it) &&
        !Regex("(?i)\\b(?:my|our)\\b|@|\\b(?:password|secret|token|salary|medical|address)\\b").containsMatchIn(it) }?.take(240).orEmpty()
    @Synchronized fun clear(context: Context) { generation++; file(context).delete() }
    @Synchronized fun removeConversation(context: Context, conversation: Long) {
        generation++
        runCatching {
            val target = file(context)
            if (!target.isFile || target.length() > 131072) { target.delete(); return }
            val rows = JSONArray(target.readText())
            val remaining = JSONArray()
            (0 until rows.length()).forEach { if (rows.getJSONObject(it).optLong("conversationId") != conversation) remaining.put(rows.get(it)) }
            target.writeText(remaining.toString())
        }
    }
    @Synchronized fun record(context: Context, conversation: Long, responseId: Long, original: String,
        route: AnswerRoute?, evidence: GroundingResult, checked: CheckedAnswer?, failed: Boolean, expectedGeneration: Long? = null) {
        if (route == null || expectedGeneration != null && expectedGeneration != generation) return
        runCatching {
            val target = file(context)
            val rows = if (target.isFile && target.length() <= 131072) runCatching { JSONArray(target.readText()) }.getOrDefault(JSONArray()) else JSONArray()
            val publicQueryAllowed = route.researchAllowed && !route.privacyBlocked
            val row = JSONObject().put("conversationId", conversation).put("responseId", responseId).put("time", System.currentTimeMillis())
                .put("originalQuery", "")
                .put("resolvedPublicQuery", "")
                .put("route", route.path.name).put("routingReason", route.reason).put("retrievalStatus", evidence.status)
                .put("verification", checked?.support?.name ?: "NOT_APPLICABLE")
                .put("fallbackReason", if (failed) "REQUEST_FAILED" else evidence.failure?.name ?: if (checked?.sourceBrief == true || checked?.sourceExtract == true) "PARTIAL_SOURCE_SUMMARY" else "")
                .put("research", JSONObject().put("provider", evidence.metrics.provider)
                    .put("extraction", JSONObject(evidence.metrics.extraction))
                    .put("contextSources", evidence.sources.size)
                    .put("durationMs", evidence.metrics.durationMs).put("httpCategory", evidence.metrics.httpCategory)
                    .put("discoveredResults", evidence.metrics.discoveredResults).put("readPublishers", evidence.metrics.readPublishers)
                    .put("attemptedPublishers", evidence.metrics.attemptedPublishers).put("retryCount", evidence.metrics.retries)
                    .put("timeoutStage", evidence.metrics.timeoutStage)
                    .put("failure", (evidence.failure ?: evidence.metrics.failure ?: ResearchFailure.GROUNDING_VERIFICATION_FAILED.takeIf {
                        checked?.support == AnswerSupport.INSUFFICIENT })?.name.orEmpty()))
                .put("selectedSources", JSONArray().apply { evidence.sources.take(4).forEach { source ->
                    put(JSONObject().put("id", source.id).put("relevanceScore", ResearchQuery(route.question).score(source.title + " " + source.excerpt)))
                } })
            val bounded = JSONArray()
            (maxOf(0, rows.length() - 47) until rows.length()).forEach { bounded.put(rows.get(it)) }
            bounded.put(row)
            target.writeText(bounded.toString())
        }
    }
    /** Strict allowlist, including when an old/corrupt cache contains arbitrary text.
     * No questions, conversation/source IDs, URLs, bodies, keys or exception text. */
    @Synchronized fun export(context: Context): String {
        val target = file(context)
        val input = if (target.isFile && target.length() <= 131072) runCatching { JSONArray(target.readText()) }.getOrDefault(JSONArray()) else JSONArray()
        val output = JSONArray()
        (maxOf(0, input.length() - 48) until input.length()).forEach { index ->
            val row = input.optJSONObject(index) ?: return@forEach
            val data = row.optJSONObject("research") ?: JSONObject()
            val clean = JSONObject().put("time", row.optLong("time").coerceAtLeast(0))
            fun category(key: String, accepted: Set<String>) { clean.put(key, data.optString(key).takeIf { it in accepted }.orEmpty()) }
            category("provider", setOf("Exa", "Direct website", "Exa + BI source index", "BI source index"))
            category("httpCategory", setOf("1xx", "2xx", "3xx", "4xx", "5xx"))
            category("timeoutStage", setOf("PROVIDER", "PUBLISHER"))
            category("failure", ResearchFailure.entries.map { it.name }.toSet())
            for (key in listOf("durationMs", "discoveredResults", "readPublishers", "attemptedPublishers", "retryCount"))
                clean.put(key, data.optLong(key).coerceIn(0, if (key == "durationMs") 60000L else 100L))
            val extraction = JSONObject()
            val counters = data.optJSONObject("extraction") ?: JSONObject()
            for (key in listOf("FETCH_2XX", "FETCH_4XX", "FETCH_5XX", "FETCH_ERROR", "HTML", "TEXT", "JSON", "PASSAGES", "UNSUPPORTED_TYPE", "EMPTY", "ACCESS_RESTRICTED", "JAVASCRIPT_REQUIRED", "JSON_INVALID_OR_NO_EVIDENCE", "NO_USABLE_PASSAGES", "REJECTED_RELEVANCE_OR_DATE"))
                extraction.put(key, counters.optInt(key).coerceIn(0, 1024))
            clean.put("extraction", extraction).put("contextSources", data.optInt("contextSources").coerceIn(0, 4))
            clean.put("verification", row.optString("verification").takeIf { it in AnswerSupport.entries.map { s -> s.name } }.orEmpty())
            clean.put("fallback", row.optString("fallbackReason").takeIf { it in ResearchFailure.entries.map { f -> f.name } + listOf("REQUEST_FAILED", "PARTIAL_SOURCE_SUMMARY") }.orEmpty())
            output.put(clean)
        }
        return JSONObject().put("format", 1).put("reports", output).toString(2)
    }
}
