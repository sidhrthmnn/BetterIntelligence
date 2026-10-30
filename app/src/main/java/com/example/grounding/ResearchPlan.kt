package com.example.grounding

import java.time.LocalDate
import java.text.Normalizer

enum class ResearchIntent { FACT, RECENT, COMPARISON, EXPLANATION, DIRECT_URL, MULTI_PART, NUMERICAL, HISTORICAL, LOCATION, VERIFICATION, TECHNICAL, FOLLOW_UP }
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
    /** Capitalised capabilities are facets, not identities such as London/Orion. */
    fun subjects(text: String): List<String> {
        val facets = ResearchQuery.words("CPU GPU NPU RAM API weather temperature forecast Vulkan memory")
        return entities(text).map { entity ->
            entity.split(Regex("\\s+")).filter { token -> ResearchQuery.words(token).any { it !in facets } }.joinToString(" ")
        }.filter(String::isNotBlank).distinct()
    }
    fun plan(question: String, today: LocalDate = LocalDate.now()): ResearchPlan {
        val urls = Regex("https?://[^\\s<>]+").findAll(question).map { it.value.trimEnd('.', ',', ')', ']') }.distinct().toList()
        require(urls.all(GroundingPolicy::publicHttpsUrl)) { "Use a public HTTPS link" }
        val plain = Normalizer.normalize(ConversationalResearch.weatherResearchQuestion(question).replace(Regex("https?://\\S+"), " "), Normalizer.Form.NFC).trim().take(700)
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
                ResearchFocus("GPU acceleration compatibility and limitations", ResearchQuery.words("GPU Vulkan OpenCL delegate acceleration")),
                ResearchFocus("Measured inference performance", ResearchQuery.words("benchmark performance latency speed tokens")),
                ResearchFocus("Benchmark device and test conditions", ResearchQuery.words("device hardware context threads quantization measured conditions")))
            parts.size > 1 -> parts.map { ResearchFocus(it, ResearchQuery(it, today).terms) }
            else -> {
                // "Documentation" describes the requested kind of page. Its body
                // need not literally call itself documentation to explain the subject.
                val pageKind = if (Regex("(?i)\\b(?:documentation|docs|guide)\\b").containsMatchIn(plain)) ResearchQuery.words("documentation docs guide") else emptySet()
                listOf(ResearchFocus(plain, (query.terms - entityWords - pageKind).ifEmpty { query.terms - pageKind }.ifEmpty { query.terms }))
            }
        }
        val subject = if (plain.any { it.code > 127 }) query.searchText else entities.joinToString(" ").ifBlank { query.searchText }
        val searches = when {
            urls.isNotEmpty() -> emptyList()
            FactualResearch.isCasting(plain) -> listOf(query.searchText)
            NewsIntent.isLiveRequest(plain) && Regex("(?i)\\belections?\\b").containsMatchIn(plain) ->
                listOf(query.searchText, "${query.topicSearchText} election results vote count seats")
            technicalComparison -> listOf("$subject Android runtime GPU compatibility", "$subject memory RAM quantization", "$subject Android benchmark device context tokens latency")
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
            if (Regex("(?i)\\b(how (?:many|much)|population|percentage|price|statistics?|GB|MB|latency)\\b").containsMatchIn(plain)) add(ResearchIntent.NUMERICAL)
            if (Regex("(?i)\\b(history|historical|who was)\\b").containsMatchIn(plain) || query.years.any { it.toInt() < today.year - 1 }) add(ResearchIntent.HISTORICAL)
            if (ConversationalResearch.isWeather(plain) || Regex("(?i)\\b(?:near|location|city)\\b").containsMatchIn(plain)) add(ResearchIntent.LOCATION)
            if (Regex("(?i)\\b(verify|prove|sources?|official|evidence)\\b").containsMatchIn(plain)) add(ResearchIntent.VERIFICATION)
            if (Regex("(?i)\\b(Android|API|runtime|GPU|NPU|documentation)\\b").containsMatchIn(plain)) add(ResearchIntent.TECHNICAL)
            if ("(Public source topic:" in plain) add(ResearchIntent.FOLLOW_UP)
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
            .map { it.replace(Regex("(?i)^(?:Compare|Explain|What|When|Which|Who|Where|Is|Are|Does|Do|How|Why|Tell|Read|Current|Check|Verify|Search|Research|Find|Summarize|Summarise|Describe)\\s+"), "") }
            .map { candidate ->
                candidate.split(Regex("\\s+"))
                    .filter { token -> token.lowercase() !in setOf("of", "the", "in", "for", "to", "on", "at", "by", "with", "from", "is", "are", "who", "what", "where", "when", "why", "how", "cm", "pm", "ceo", "current", "incumbent", "list", "all") }
                    .joinToString(" ")
            }
            .filter { it.length >= 2 && it.lowercase() !in setOf("is", "are", "when", "what", "who", "where", "how", "why", "compare", "explain", "does", "can", "tell", "read", "search", "research", "find", "summarize", "summarise", "describe", "which", "public", "source", "topic") }
        val locations = Regex("\\b(?:in|near|for|of)\\s+([A-Z][\\p{L}]+(?:[, ]+[A-Z][\\p{L}]+){0,2})").findAll(text).map { it.groupValues[1] }
        val weatherLocations = if (Regex("(?i)\\b(weather|temperature|forecast|rain|wind|humidity)\\b").containsMatchIn(text)) {
            Regex("(?i)\\b(?:in|near|for|at)\\s+([\\p{L}][\\p{L}-]{2,}(?:\\s+[\\p{L}][\\p{L}-]{2,}){0,2}?)(?=\\s*(?:today|tonight|now|right now|currently|tomorrow|this week|\\?|$))")
                .findAll(text.substringBefore("(Public source topic:"))
                .map { it.groupValues[1].trim() }
                .filter { it.lowercase() !in setOf("the", "this", "that", "today", "now", "celsius", "fahrenheit") }
        } else emptySequence()
        val officeLocations = if (ResearchQuery.isCurrentIncumbentQuery(text)) {
            Regex("(?i)\\b(?:of|in|for)\\s+([\\p{L}][\\p{L}\\p{N}-]{2,}(?:\\s+[\\p{L}][\\p{L}\\p{N}-]{2,}){0,2})").findAll(text.substringBefore("(Public source topic:"))
                .map { it.groupValues[1].trim() }
                .filter { it.lowercase() !in setOf("the", "india", "this", "that") || text.lowercase().endsWith(it.lowercase()) }
        } else emptySequence()
        val pairs = (compared + andCompared).toList()
        // With explicit comparison subjects, generic platform names such as
        // "Android AI" must not qualify an unrelated third runtime's section.
        val extras = if (pairs.size >= 2) emptySequence() else names + locations + weatherLocations + officeLocations
        val all = (quoted + pairs.asSequence() + versions + extras).distinct().toList()
        return all.filter { candidate -> all.none { it != candidate && it.contains(candidate) } }.take(6)
    }
}
