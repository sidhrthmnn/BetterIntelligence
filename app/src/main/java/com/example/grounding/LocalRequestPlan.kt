package com.example.grounding

import com.example.engine.*
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Numbered identities are adjacent phrases, never an unordered bag of words. */
object ExactIdentity {
    private val numbered = Regex("(?i)\\b([\\p{L}][\\p{L}\\p{N}._-]{1,30}\\s+\\d+(?:\\.\\d+)*(?:\\s+(?:pro|max|ultra|mini|e\\d+b|\\d+(?:\\.\\d+)?b))?)\\b")
    private val generic = setOf("the", "a", "in", "on", "at", "has", "have", "is", "was", "for", "with", "by", "and", "version", "year", "from", "of", "to",
        "give", "list", "show", "provide", "find", "compare", "top", "first", "last", "next", "past", "only", "these", "those", "about", "around", "under", "over", "than", "between",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    fun labels(question: String): List<String> = numbered.findAll(normalize(question)).map { it.value }
        .filter { it.substringBefore(' ').lowercase() !in generic }.distinct().take(8).toList()
    private fun normalize(value: String) = ResearchQuery.normalizedModels(value.lowercase().replace(Regex("[-_/]"), " ")).replace(Regex("\\s+"), " ")
    private fun matches(label: String, text: String) = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(normalize(label))}(?![\\p{L}\\p{N}]|\\.\\d)").containsMatchIn(normalize(text))
    fun accepts(question: String, text: String): Boolean = labels(question).let { ids -> ids.isEmpty() || ids.any { matches(it, text) } }
    fun acceptsAll(question: String, text: String): Boolean = labels(question).all { matches(it, text) }

}
data class LocalRequestPlan(val intent: String, val entities: List<String>, val required: List<String>, val queries: List<String>) {
    companion object {
        fun validate(question: String, text: String): LocalRequestPlan? = runCatching {
            if (text.length > 2400 || GroundingPolicy.containsCredential(text)) return null
            val json = JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            fun strings(key: String, max: Int): List<String> = json.optJSONArray(key)?.let { a -> (0 until minOf(a.length(), max)).map { a.getString(it).trim() } }.orEmpty()
            val intent = json.getString("intent").takeIf { it in setOf("fact", "comparison", "explanation", "current", "troubleshooting", "multi_part") } ?: return null
            val entities = strings("entities", 6).filter { it.length in 2..90 && question.contains(it, true) }
            val required = strings("required", 4).filter { it.length in 2..100 && !Regex("(?i)ignore|system prompt|assistant:").containsMatchIn(it) }
            val deterministic = ResearchPlanner.subjects(question) + ExactIdentity.labels(question)
            val queries = strings("queries", 3).filter { q -> q.length in 3..300 && ConversationalResearch.validPublicQuery(q) &&
                ExactIdentity.acceptsAll(question, q) && deterministic.all { e -> ResearchQuery.words(q).containsAll(ResearchQuery.words(e)) } }
            LocalRequestPlan(intent, entities, required, queries)
        }.getOrNull()
    }
}
class LocalPlanContext(val plan: LocalRequestPlan) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalPlanContext>
}
object OnDeviceRequestPlanner {
    fun useful(question: String) = question.length in 35..450 &&
        Regex("(?i)\\bcompare|\\bversus|\\band (?:how|what|when|why|which)|\\btroubleshoot").containsMatchIn(question)
    suspend fun plan(engine: InferenceEngine, question: String, context: Int): LocalRequestPlan? {
        if (!useful(question) || GroundingPolicy.containsCredential(question)) return null
        return try {
            withTimeoutOrNull(6_000) {
                val output = engine.generateSync("Plan this public research request as JSON only. Fields: intent (fact/comparison/explanation/current/troubleshooting/multi_part), entities (exact names copied from request), required (up to four facts), queries (up to three focused web queries). Preserve every version/sequel number. No answer. Request: $question",
                    GenerationParams(contextSize = context, maxTokens = 160, temperature = 0f, topK = 1, history = emptyList(), autoContinue = false, internalPlanning = true))
                LocalRequestPlan.validate(question, output)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}

/** Bounded lexical fallback. This is BM25-style ranking, not an embedding/entailment claim. */
object HybridPassageRanking {
    fun score(question: String, text: String, collection: List<String>): Double {
        if (!ExactIdentity.accepts(question, text)) return -1000.0
        val tokens = ResearchQuery.words(text); val terms = ResearchQuery.words(question)
        val average = collection.map { ResearchQuery.words(it).size }.average().takeIf { it > 0 } ?: 1.0
        return terms.sumOf { term ->
            val count = Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])").findAll(text).take(30).count().toDouble()
            val docs = collection.count { term in ResearchQuery.words(it) }
            val idf = kotlin.math.ln(1 + (collection.size - docs + .5) / (docs + .5))
            idf * count * 2.2 / (count + 1.2 * (.25 + .75 * tokens.size / average))
        }
    }
}
