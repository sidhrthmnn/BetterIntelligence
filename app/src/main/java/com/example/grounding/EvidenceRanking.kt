package com.example.grounding

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Lexical relevance and collection coverage, not neural semantic entailment.
 * Publisher suffixes (.gov/.edu) alone confer no authority. */
object EvidenceRanking {
    fun publisher(url: String): String = url.toHttpUrl().let { it.topPrivateDomain() ?: it.host }
    fun score(source: EvidenceSource, plan: ResearchPlan): Int {
        val text = source.title + " " + source.excerpt
        val q = ResearchQuery(plan.question)
        val found = ResearchQuery.words(text)
        val entityCoverage = plan.entities.count { entity -> ResearchQuery.words(entity).all { it in found } }
        val provenance = when {
            Regex("(?i)\\b(documentation|reference|release notes|security bulletin|research paper|abstract|doi|authors?)\\b").containsMatchIn(text) -> 3
            else -> 0
        }
        return q.score(text) + entityCoverage * 8 + plan.coverage(text).size * 5 + provenance +
            minOf(4, source.excerpt.length / 300)
    }
    fun acceptable(source: EvidenceSource, plan: ResearchPlan): Boolean {
        val q = ResearchQuery(plan.question)
        if (!q.currentEnough(source.updatedAt) || q.isPrediction(source.title)) return false
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
    fun select(sources: List<EvidenceSource>, plan: ResearchPlan, limit: Int = 3): List<EvidenceSource> {
        val remaining = sources.distinctBy { HtmlDiscovery.canonicalUrl(it.url) }.distinctBy {
            // Preserve even single digits/units: a token set that omits them
            // merged contradictory "4 GB" and "6 GB" reports in evaluation.
            it.excerpt.lowercase().replace(Regex("\\s+"), " ").trim()
        }.toMutableList()
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

    /** Narrow contradiction heuristic: identical subject/predicate and units,
     * same explicit date qualifier. Different products/years aren't conflicts.
     * This cannot detect arbitrary contradictory prose or judge which is true. */
    fun conflicts(sources: List<EvidenceSource>): List<String> {
        data class Claim(val source: Int, val publisher: String, val key: String, val value: String)
        val pattern = Regex("(?i)([\\p{L}][\\p{L}\\p{N} -]{2,60}?)\\s+(?:requires?|uses?|has|is|measured|was)\\s+(?:about\\s+)?(\\d+(?:\\.\\d+)?)\\s*(GB|MB|hours?|watts?|tokens/s|ms)\\b")
        val claims = sources.flatMap { source -> pattern.findAll(source.excerpt).map { match ->
            val prefix = match.groupValues[1].lowercase().trim()
            val qualifiers = Regex("\\b(?:19|20)\\d{2}\\b").findAll(match.value).joinToString { it.value }
            Claim(source.id, publisher(source.url), "$prefix ${match.groupValues[3].lowercase()} $qualifiers", match.groupValues[2])
        }.toList() }
        return claims.groupBy { it.key }.filterValues { group -> group.map { it.publisher }.distinct().size > 1 && group.map { it.value }.distinct().size > 1 }
            .entries.take(3).map { (key, values) -> "Conflicting reported values for $key in sources ${values.map { it.source }.distinct().joinToString()}; do not choose a winner without compatible measurement conditions." }
    }
}
