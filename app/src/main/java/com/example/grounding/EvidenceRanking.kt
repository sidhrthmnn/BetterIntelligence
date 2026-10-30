package com.example.grounding

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Lexical relevance and collection coverage, not neural semantic entailment.
 * Publisher suffixes (.gov/.edu) alone confer no authority. */
object EvidenceRanking {
    fun weatherPlaceScore(title: String, question: String): Int {
        if (!ConversationalResearch.isWeather(question)) return 0
        val place = ConversationalResearch.weatherCity(question) ?: return 0
        val required = ResearchQuery.words(place)
        return if (required.isNotEmpty() && ResearchQuery.words(title).containsAll(required)) 60 else 0
    }
    /** An explicit locale path can scope a page, but never supplies a value. */
    fun localeWords(url: String): Set<String> = url.toHttpUrl().pathSegments.firstOrNull()
        ?.takeIf { Regex("[a-zA-Z]{2}(?:-[a-zA-Z]{2})?").matches(it) }?.let { ResearchQuery.words(it) }.orEmpty()
    fun publisher(url: String): String = url.toHttpUrl().let { it.topPrivateDomain() ?: it.host }
    fun primaryRelevance(url: String, question: String): Int {
        val parsed = url.toHttpUrl()
        val q = question.lowercase()
        val primary = when {
            FactualResearch.isCasting(q) && Regex("\\b(?:marvel|avengers)\\b").containsMatchIn(q) &&
                parsed.host in setOf("www.marvel.com", "marvel.com", "thewaltdisneycompany.com", "www.thewaltdisneycompany.com", "d23.com", "movies.disney.com") -> true
            "litert" in q && parsed.host in setOf("ai.google.dev", "developers.google.com") && "litert" in parsed.encodedPath -> true
            "litert" in q && parsed.host == "github.com" && parsed.encodedPath.startsWith("/google-ai-edge/LiteRT", true) -> true
            "llama.cpp" in q && parsed.host == "github.com" && Regex("(?i)^/(?:ggml-org|ggerganov)/llama\\.cpp(?:/|$)").containsMatchIn(parsed.encodedPath) -> true
            "android" in q && parsed.host in setOf("developer.android.com", "source.android.com") -> true
            "android" in q && parsed.host in setOf("ai.google.dev", "developers.google.com") && parsed.encodedPath.startsWith("/edge/litert") -> true
            else -> false
        }
        return if (primary) 8 else 0
    }
    fun score(source: EvidenceSource, plan: ResearchPlan): Int {
        val text = source.title + " " + source.excerpt
        val q = ResearchQuery(plan.question)
        val found = ResearchQuery.words(text)
        val entityCoverage = plan.entities.count { entity -> ResearchQuery.words(entity).all { it in found } }
        val provenance = when {
            Regex("(?i)\\b(documentation|reference|release notes|security bulletin|research paper|abstract|doi|authors?)\\b").containsMatchIn(text) -> 3
            else -> 0
        }
        val weatherQuality = if (ConversationalResearch.isWeather(plan.question)) when {
            WeatherPageEvidence.current(source.excerpt, q.today) -> 45
            Regex("(?i)historical average|monthly average|climate average").containsMatchIn(source.excerpt) -> -30
            else -> 0
        } else 0
        val incumbentBonus = if (ResearchQuery.isCurrentIncumbentQuery(plan.question)) {
            val hasPresent = Regex("(?i)\\b(incumbent|present(?!\\s*-\\s*day)|till\\s+date|to\\s+date|current(?!\\s+(?:affairs|gk|general\\s+knowledge))|currently|assumed\\s+office|serving\\s+since|in\\s+office|is\\s+the\\s+(?:current\\s+|present\\s+|\\d+(?:st|nd|rd|th)\\s+and\\s+current\\s+)?(?:chief\\s+minister|prime\\s+minister|president|governor|mayor|ceo|cm|pm))\\b").containsMatchIn(text)
            val hasPastOnly = !hasPresent && Regex("(?i)\\b(passed\\s+away|died|former|was\\s+the\\s+\\d+(?:st|nd|rd|th)?)\\b").containsMatchIn(text)
            if (hasPresent) 15 else if (hasPastOnly) -10 else 0
        } else 0
        val directAnswer = if (FactualResearch.isCasting(plan.question) && FactualResearch.usable(source, plan.question)) 25 else 0
        val definition = if (FactualResearch.definitionLine(source.excerpt, plan.question)) 25 else 0
        return q.score(text) + entityCoverage * 8 + plan.coverage(text).size * 5 + provenance + incumbentBonus + weatherQuality + directAnswer + definition + primaryRelevance(source.url, plan.question) +
            minOf(4, source.excerpt.length / 300) + FactualResearch.answerFocusScore(source.excerpt, plan.question) / 2 + weatherPlaceScore(source.title, plan.question)
    }
    fun acceptable(source: EvidenceSource, plan: ResearchPlan): Boolean {
        if (!WeatherPageEvidence.requestedDay(source.title, plan.question)) return false
        if (SourceBrief.predictionMarket(source.title, source.url, plan.question)) return false
        if (source.kind == "INSTANT_ANSWER" && (source.discoveryProvider != LegacyTopicEvidence.providerName ||
            !LegacyTopicEvidence.summaryAllowed(plan.question))) return false
        if (!FactualResearch.usable(source, plan.question) && !SourceBrief.reportedRoleContext(source, plan.question)) return false
        val q = ResearchQuery(plan.question)
        val currentWeather = ConversationalResearch.isWeather(plan.question) && WeatherPageEvidence.current(source.excerpt, q.today) &&
            ResearchQuery.date(source.updatedAt)?.isAfter(q.today.plusDays(1)) != true
        val explicitIncumbent = source.updatedAt.isBlank() && plan.explicitUrls.isNotEmpty() && ResearchQuery.isCurrentIncumbentQuery(plan.question) &&
            Regex("(?i)\\b(?:current role holder|current (?:prime minister|ceo)|incumbent|is (?:the )?(?:chairman and )?chief executive officer)\\b").containsMatchIn(source.excerpt)
        // An undated, explicitly requested role page may identify its current
        // holder. Keep the date unknown; never invent a fresh publication date.
        if ((!q.currentEnough(source.updatedAt) && !explicitIncumbent && !currentWeather && !contextOnly(source, plan)) || q.isPrediction(source.title)) return false
        if (plan.explicitUrls.isNotEmpty()) return true
        val found = ResearchQuery.words(source.title + " " + source.excerpt)
        if (q.years.isNotEmpty() && !found.containsAll(q.years)) return false
        // A comparison source can cover one side/facet. Requiring every term
        // from the original question excluded useful primary documentation.
        val subjectMatch = if (plan.entities.isNotEmpty()) plan.entities.any { entity ->
            val words = ResearchQuery.words(entity)
            words.isNotEmpty() && words.count { it in found } >= minOf(2, words.size)
        } else q.terms.count { it in found } >= minOf(2, q.terms.size)
        return subjectMatch && plan.coverage(source.title + " " + source.excerpt).isNotEmpty()
    }
    /** A maintained reference can supply background even if its update is older
     * than a relative news window. It must not establish a recent release/event. */
    fun contextOnly(source: EvidenceSource, plan: ResearchPlan): Boolean {
        val query = ResearchQuery(plan.question)
        if (query.earliest == null || query.currentEnough(source.updatedAt)) return false
        if (ResearchQuery.date(source.updatedAt)?.isAfter(query.today.plusDays(1)) == true) return false
        if (ResearchIntent.TECHNICAL !in plan.intents || primaryRelevance(source.url, plan.question) == 0) return false
        if (Regex("(?i)\\b(today|yesterday|this (?:week|month)|last (?:week|month)|since|published|released|announced|news)\\b").containsMatchIn(plan.question)) return false
        return Regex("(?i)\\b(documentation|reference|overview|guide|get started|API)\\b").containsMatchIn(source.title)
    }
    fun select(sources: List<EvidenceSource>, plan: ResearchPlan, limit: Int = 3): List<EvidenceSource> {
        val unique = sources.distinctBy { HtmlDiscovery.canonicalUrl(it.url) }.distinctBy {
            // Preserve even single digits/units: a token set that omits them
            // merged contradictory "4 GB" and "6 GB" reports in evaluation.
            it.excerpt.lowercase().replace(Regex("\\s+"), " ").trim()
        }
        val remaining = mutableListOf<EvidenceSource>()
        unique.sortedByDescending { score(it, plan) }.forEach { candidate ->
            if (remaining.none { syndicated(it.excerpt, candidate.excerpt) }) remaining += candidate
        }
        val selected = mutableListOf<EvidenceSource>()
        val covered = mutableSetOf<Int>()
        while (remaining.isNotEmpty() && selected.size < limit) {
            val next = remaining.maxBy { source ->
                score(source, plan) + (plan.coverage(source.excerpt) - covered).size * 25 +
                    if (selected.none { publisher(it.url) == publisher(source.url) }) 10 else -10
            }
            remaining.remove(next); selected += next; covered += plan.coverage(next.excerpt)
        }
        return selected.mapIndexed { i, source -> source.copy(id = i + 1) }
    }

    internal fun syndicated(a: String, b: String): Boolean {
        fun numbers(text: String) = Regex("\\d+(?:\\.\\d+)?").findAll(text).map { it.value }.toSet()
        if (numbers(a) != numbers(b) || ResearchPlanner.entities(a).toSet() != ResearchPlanner.entities(b).toSet()) return false
        fun shingles(text: String): Set<String> = text.lowercase().split(Regex("\\W+")).filter(String::isNotBlank)
            .windowed(5).map { it.joinToString(" ") }.toSet()
        val x = shingles(a); val y = shingles(b)
        return x.size >= 15 && y.size >= 15 && (x intersect y).size.toDouble() / (x union y).size > .9
    }

    /** Narrow contradiction heuristic: identical subject/predicate and units,
     * same explicit date qualifier. Different products/years aren't conflicts.
     * This cannot detect arbitrary contradictory prose or judge which is true. */
    fun conflicts(sources: List<EvidenceSource>): List<String> {
        data class Claim(val source: Int, val publisher: String, val key: String, val value: String, val conditions: String, val date: String)
        val pattern = Regex("(?i)([\\p{L}][\\p{L}\\p{N} -]{2,60}?)\\s+(?:requires?|uses?|has|is|measured|was)\\s+(?:about\\s+)?(\\d+(?:\\.\\d+)?)\\s*(GB|MB|hours?|watts?|tokens/s|ms)\\b")
        val claims = sources.flatMap { source -> ClaimVerification.sentences(source.excerpt).flatMap { sentence -> pattern.findAll(sentence).map { match ->
            val prefix = match.groupValues[1].lowercase().trim()
            val qualifiers = Regex("(?i)\\b(?:(?:19|20)\\d{2}|Q[2-8](?:_[A-Z0-9]+)*|\\d+\\s*(?:context|threads|tokens context)|(?:context|threads)\\s*\\d+|Pixel\\s*\\d+|SM\\d+)\\b")
                .findAll(sentence).map { it.value.lowercase() }.sorted().joinToString(" ")
            Claim(source.id, publisher(source.url), "$prefix ${match.groupValues[3].lowercase()}", match.groupValues[2], qualifiers, source.updatedAt.ifBlank { "unknown date" })
        }.toList() } }
        return claims.groupBy { it.key }.filterValues { group -> group.map { it.publisher }.distinct().size > 1 && group.map { it.value }.distinct().size > 1 }
            .entries.take(3).map { (key, values) ->
                val incomparable = values.map { it.conditions }.distinct().size > 1
                val reports = values.distinctBy { it.source }.joinToString("; ") { "[${it.source}] ${it.value} ($key; ${it.conditions.ifBlank { "conditions unstated" }}; reported date ${it.date})" }
                "${if (incomparable) "Different stated measurement conditions" else "Conflicting reported values"}: $reports. Explain the difference; neither recency nor publisher count establishes a winner."
            }
    }
}
