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
                .put("originalQuery", if (publicQueryAllowed) safeQuery(original) else "")
                .put("resolvedPublicQuery", if (publicQueryAllowed) safeQuery(ResearchQuery(route.question).searchText) else "")
                .put("route", route.path.name).put("routingReason", route.reason).put("retrievalStatus", evidence.status)
                .put("verification", checked?.support?.name ?: "NOT_APPLICABLE")
                .put("fallbackReason", if (failed) "REQUEST_FAILED" else evidence.failure?.name ?: if (checked?.sourceBrief == true || checked?.sourceExtract == true) "PARTIAL_SOURCE_SUMMARY" else "")
                .put("selectedSources", JSONArray().apply { evidence.sources.take(4).forEach { source ->
                    put(JSONObject().put("id", source.id).put("relevanceScore", ResearchQuery(route.question).score(source.title + " " + source.excerpt)))
                } })
            val bounded = JSONArray()
            (maxOf(0, rows.length() - 47) until rows.length()).forEach { bounded.put(rows.get(it)) }
            bounded.put(row)
            target.writeText(bounded.toString())
        }
    }
}
