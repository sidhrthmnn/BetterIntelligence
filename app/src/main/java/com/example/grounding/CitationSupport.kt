package com.example.grounding

/** Deterministic guard for fabricated numerical claims. This is deliberately not
 * a claim of semantic fact checking: a cited publisher can still be wrong.
 */
object CitationSupport {
    private val marker = Regex("\\[(\\d+(?:,\\s*\\d+)*)]")
    private val number = Regex("(?<![\\p{L}\\p{N}])[-−]?\\d+(?:[,.]\\d+)*(?:%|°)?(?![\\p{L}\\p{N}])")
    private val date = Regex("(?i)\\b(?:\\d{4}-\\d{2}-\\d{2}|[A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4}|\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4})\\b")
    private fun values(text: String): Set<String> = number.findAll(date.replace(text) { match ->
        ResearchQuery.date(match.value)?.let { "${it.year} ${it.monthValue} ${it.dayOfMonth}" } ?: match.value
    }).map {
        val normalized = it.value.replace("−", "-").replace(Regex(",(?=\\d{3}(?:\\D|$))"), "").trimEnd('%', '°')
        runCatching { java.math.BigDecimal(normalized).stripTrailingZeros().toPlainString() }.getOrDefault(normalized)
    }.toSet()

    fun supportsNumbers(text: String, sources: List<EvidenceSource>): Boolean {
        val prose = Regex("```[\\s\\S]*?(?:```|$)|`[^`\\n]*`").replace(text, "")
        return prose.split(Regex("\\n\\s*\\n")).all { paragraph ->
            val ids = marker.findAll(paragraph).flatMap { it.groupValues[1].split(',').asSequence().map(String::trim) }.toSet()
            val claimed = values(marker.replace(paragraph, ""))
            if (claimed.isEmpty()) true else {
                val cited = sources.filter { it.id.toString() in ids }
                val supplied = values(cited.joinToString("\n") { it.title + "\n" + it.excerpt + "\n" + it.updatedAt })
                cited.isNotEmpty() && supplied.containsAll(claimed)
            }
        }
    }
}
