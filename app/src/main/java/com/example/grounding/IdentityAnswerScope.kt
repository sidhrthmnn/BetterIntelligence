package com.example.grounding

/** Select the identity the question asks for, rather than any sentence sharing
 * its nouns. This ranks read text; it never supplies a name from app knowledge. */
object IdentityAnswerScope {
    fun ownerSubject(question: String): String? = Regex("(?i)^\\s*who (?:(?:is|are) (?:the )?owners? of|owns) (.{2,100}?)[?!. ]*$")
        .matchEntire(question)?.groupValues?.get(1)?.trim()
    fun worldRanking(question: String) = Regex("(?i)^\\s*who\\b.*\\brichest (?:person|man|woman)\\b.*\\bworld\\b").containsMatchIn(question)
    fun requested(question: String) = ownerSubject(question) != null || worldRanking(question)

    fun focusScore(text: String, question: String): Int {
        val body = text.split(Regex("\\n\\s*\\n")).joinToString(" ") { it.substringAfter('\n', it) }
        ownerSubject(question)?.let { subject ->
            val identity = ResearchQuery.words(subject).joinToString("[^\\p{L}\\p{N}]+") { Regex.escape(it) + if (it.length > 3) "s?" else "" }
            if (identity.isBlank()) return 0
            val relation = Regex("(?i)\\b$identity\\b(?:['’]s)?[, ]+(?:(?:is|are|a|an|the|privately|jointly|now|currently)\\s+){0,5}(?:owned|controlled) by\\b|\\b(?:owns?|co-owns?)\\s+$identity\\b|\\bowners? of $identity\\s+(?:is\\b|are\\b|include\\b|:)|\\b$identity(?:['’]s)?\\s+owners?\\s+(?:are\\b|include\\b|:)")
            return if (relation.containsMatchIn(body)) 100 else 0
        }
        if (!worldRanking(question)) return 0
        val named = Regex("\\b[A-Z][\\p{L}'’-]+(?:\\s+[A-Z][\\p{L}'’-]+){1,3}\\b").containsMatchIn(body)
        val global = Regex("(?i)\\b(?:richest (?:person|man|woman) in the world|world['’]s (?:richest|wealthiest) (?:person|man|woman)|top (?:position|spot).{0,50}global.{0,30}rankings?)\\b").containsMatchIn(body)
        return if (named && global) 100 else 0
    }

    fun recover(question: String, sources: List<EvidenceSource>, checked: CheckedAnswer): CheckedAnswer? {
        if (!requested(question) || checked.support == AnswerSupport.CITED && focusScore(checked.text, question) >= 65) return null
        val query = ResearchQuery(question)
        val focused = sources.filter { it.kind == "WEB_PAGE" && query.currentEnough(it.updatedAt) && focusScore(it.excerpt, question) >= 65 }
            .sortedByDescending { ResearchQuery.date(it.updatedAt) }.take(2)
        val brief = SourceBrief.build(question, focused, checked.claims) ?: return null
        val dated = focused.fold(brief.text) { answer, source ->
            val date = ResearchQuery.date(source.updatedAt)?.takeIf { source.dateKind in setOf("PUBLISHED", "MODIFIED") }
            if (date == null) answer else answer.replace("${ReadableSourceSummary.publisher(source.url)} reports", "${ReadableSourceSummary.publisher(source.url)} reports (page dated $date)")
        }
        return brief.copy(text = dated, recoveryAttempted = true)
    }
}
