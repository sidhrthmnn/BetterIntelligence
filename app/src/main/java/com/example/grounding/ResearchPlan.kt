package com.example.grounding

import java.time.LocalDate
import java.text.Normalizer

enum class ResearchIntent { FACT, RECENT, COMPARISON, EXPLANATION, DIRECT_URL, MULTI_PART }
data class ResearchFocus(val question: String, val terms: Set<String>)
data class ResearchPlan(val question: String, val intents: Set<ResearchIntent>, val entities: List<String>,
    val focuses: List<ResearchFocus>, val searches: List<String>, val explicitUrls: List<String>) {
    fun coverage(text: String): Set<Int> {
        val found = ResearchQuery.words(text)
        return focuses.indices.filter { index ->
            val terms = focuses[index].terms
            terms.isEmpty() || terms.count { it in found } >= minOf(2, terms.size)
        }.toSet()
    }
}

/** Bounded, deterministic planning. No model allocation, translation, or private
 * conversation text. Non-Latin text is retained verbatim in outgoing queries. */
object ResearchPlanner {
    fun plan(question: String, today: LocalDate = LocalDate.now()): ResearchPlan {
        val urls = Regex("https?://[^\\s<>]+").findAll(question).map { it.value.trimEnd('.', ',', ')', ']') }.distinct().toList()
        require(urls.all(GroundingPolicy::publicHttpsUrl)) { "Use a public HTTPS link" }
        val plain = Normalizer.normalize(question.replace(Regex("https?://\\S+"), " "), Normalizer.Form.NFC).trim().take(700)
        val query = ResearchQuery(plain, today)
        val comparison = Regex("(?i)\\b(vs\\.?|versus|compare|comparison|better than|difference between)\\b|തമ്മിൽ|താരതമ്യം").containsMatchIn(plain)
        val explanation = Regex("(?i)^(how|why|explain)\\b|എങ്ങനെ|എന്തുകൊണ്ട്").containsMatchIn(plain)
        val entities = entities(plain)
        val entityWords = entities.flatMap { ResearchQuery.words(it) }.toSet()
        val parts = plain.split(Regex("(?i)\\?\\s+(?=\\p{L})|;\\s*|\\band (?:also |what |how |when |where |does |is )|\\bas well as\\b|കൂടാതെ"))
            .map { it.trim(' ', '?', '.') }.filter { ResearchQuery(it, today).terms.isNotEmpty() }.take(3)
        val multipleTechnicalFacets = Regex("(?i)memory|RAM|മെമ്മറി").containsMatchIn(plain) &&
            Regex("(?i)benchmark|performance|latency|speed").containsMatchIn(plain) && Regex("(?i)android|model|inference").containsMatchIn(plain)
        val technicalComparison = (comparison || multipleTechnicalFacets) && Regex("(?i)android|local|llm|inference|model|മോഡൽ|ആൻഡ്രോയിഡ്").containsMatchIn(plain)
        val focuses = when {
            technicalComparison -> listOf(
                ResearchFocus("Android runtime and model compatibility", ResearchQuery.words("Android runtime compatibility format support")),
                ResearchFocus("Memory requirements and quantization", ResearchQuery.words("memory RAM quantization size GB")),
                ResearchFocus("Measured performance and benchmark conditions", ResearchQuery.words("benchmark performance latency speed tokens device")))
            parts.size > 1 -> parts.map { ResearchFocus(it, ResearchQuery(it, today).terms) }
            else -> listOf(ResearchFocus(plain, (query.terms - entityWords).ifEmpty { query.terms }))
        }
        val subject = if (plain.any { it.code > 127 }) query.searchText else entities.joinToString(" ").ifBlank { query.searchText }
        val searches = when {
            urls.isNotEmpty() -> emptyList()
            technicalComparison -> listOf("$subject Android runtime compatibility", "$subject memory RAM quantization", "$subject Android benchmark tokens latency")
            parts.size > 1 -> parts.map { part ->
                val q = ResearchQuery(part, today).searchText
                if (entities.isNotEmpty() && entities.none { q.contains(it, true) }) "$subject $q" else q
            }
            comparison -> listOf(query.searchText, "$subject specifications comparison")
            else -> listOf(query.searchText)
        }.map { it.trim().take(400) }.filter { it.isNotBlank() }.distinctBy { ResearchQuery.words(it).sorted() }.take(3)
        val intents = buildSet {
            add(ResearchIntent.FACT)
            if (query.relative) add(ResearchIntent.RECENT)
            if (comparison) add(ResearchIntent.COMPARISON)
            if (explanation) add(ResearchIntent.EXPLANATION)
            if (urls.isNotEmpty()) add(ResearchIntent.DIRECT_URL)
            if (focuses.size > 1) add(ResearchIntent.MULTI_PART)
        }
        return ResearchPlan(question, intents, entities, focuses, searches, urls.take(3))
    }

    fun entities(text: String): List<String> {
        val quoted = Regex("[\"“]([^\"”]{2,70})[\"”]").findAll(text).map { it.groupValues[1] }
        val compared = Regex("(?i)([\\p{L}][\\p{L}\\p{N}._-]*(?:\\s+\\d+(?:\\.\\d+)*)?)\\s+(?:vs\\.?|versus|better than)\\s+([\\p{L}][\\p{L}\\p{N}._-]*(?:\\s+\\d+(?:\\.\\d+)*)?)")
            .findAll(text).flatMap { sequenceOf(it.groupValues[1], it.groupValues[2]) }
        val andCompared = Regex("(?i)\\bcompare\\s+([\\p{L}][\\p{L}\\p{N}._-]*(?:\\s+\\d+(?:\\.\\d+)*)?)\\s+(?:and|with|to)\\s+([\\p{L}][\\p{L}\\p{N}._-]*(?:\\s+\\d+(?:\\.\\d+)*)?)")
            .findAll(text).flatMap { sequenceOf(it.groupValues[1], it.groupValues[2]) }
        val versions = Regex("\\b[\\p{L}][\\p{L}\\p{N}-]{1,25}\\s+\\d+(?:\\.\\d+)*(?:\\s+[A-Z]\\d+[A-Z]?)?\\b").findAll(text).map { it.value }
        val names = Regex("\\b[A-Z][\\p{L}\\p{N}-]+(?:\\s+[A-Z][\\p{L}\\p{N}-]+){0,3}\\b").findAll(text).map { it.value }
            .map { it.replace(Regex("^(?:Compare|Explain|What|When|Which|Is|Does|How|Why|Tell|Read)\\s+"), "") }
            .filter { it.lowercase() !in setOf("is", "are", "when", "what", "who", "where", "how", "why", "compare", "explain", "does", "can", "tell", "read", "which") }
        val locations = Regex("\\b(?:in|near|for)\\s+([A-Z][\\p{L}]+(?:[, ]+[A-Z][\\p{L}]+){0,2})").findAll(text).map { it.groupValues[1] }
        val pairs = (compared + andCompared).toList()
        // With explicit comparison subjects, generic platform names such as
        // "Android AI" must not qualify an unrelated third runtime's section.
        val extras = if (pairs.size >= 2) emptySequence() else names + locations
        val all = (quoted + pairs.asSequence() + versions + extras).distinct().toList()
        return all.filter { candidate -> all.none { it != candidate && it.contains(candidate) } }.take(6)
    }
}
