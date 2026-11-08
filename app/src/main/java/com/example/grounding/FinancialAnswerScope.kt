package com.example.grounding

/** Related financing reports can help without being misrepresented as the
 * company's current drawn debt. Never sum facilities or turn funding into debt. */
object FinancialAnswerScope {
    private val request = Regex("(?i)\\bhow much\\b.*\\b(?:loans?|debt|owes?|borrow(?:ed|ing)?)\\b")
    private val money = Regex("(?i)(?:[£$€₹]\\s*\\d|\\b(?:USD|GBP|EUR|INR)\\s*\\d|\\d[\\d,. ]*\\s*(?:million|billion|trillion)?\\s*(?:dollars?|pounds?|euros?|rupees?)\\b)")
    private val financing = Regex("(?i)\\b(?:loans?|debt|credit (?:line|facility)|revolving credit|borrow(?:ed|ing)?)\\b")

    fun recover(question: String, sources: List<EvidenceSource>, checked: CheckedAnswer): CheckedAnswer? {
        if (!request.containsMatchIn(question)) return null
        if (checked.support == AnswerSupport.CITED && money.containsMatchIn(checked.text) &&
            ClaimLanguage.answerScopeMatches(question, checked.text)) return null
        val subject = Regex("(?i)\\bdoes (.+?) (?:have|owe)\\b").find(question)?.groupValues?.get(1)
            ?: ResearchPlanner.subjects(question).singleOrNull() ?: return null
        val words = ResearchQuery.words(subject)
        if (words.isEmpty()) return null
        val query = ResearchQuery(question)
        val reports = sources.filter { it.kind == "WEB_PAGE" && query.currentEnough(it.updatedAt) && GroundingPolicy.publicHttpsUrl(it.url) }
            .mapNotNull { source ->
                val passage = source.excerpt.split(Regex("\\n\\s*\\n")).map { it.substringAfter('\n', it).trim() }.firstOrNull { body ->
                    body.length in 20..600 && money.containsMatchIn(body) && financing.containsMatchIn(body) &&
                        ResearchQuery.words(body).containsAll(words) && !PublisherBoilerplate.unrelatedFooter(body, question) &&
                        !FactualResearch.headlineOnly(body, source.title) && !ClaimVerification.financeExample(body) && !FactualResearch.incomplete(body)
                } ?: return@mapNotNull null
                ReadableSourceSummary.sentence(source, passage, question)?.let { summary ->
                    val date = ResearchQuery.date(source.updatedAt)?.takeIf { source.dateKind in setOf("PUBLISHED", "MODIFIED") }
                    if (date == null) summary else summary.replaceFirst(" reports", " reports (page dated $date)")
                }
            }.distinct().take(2)
        if (reports.isEmpty()) return null
        val period = if (query.years.isEmpty()) "current total outstanding debt" else "total outstanding debt for the requested period"
        return checked.copy(text = reports.joinToString("\n\n") + "\n\nThe sources do not establish the $period or the amount actually drawn from these credit facilities.",
            support = AnswerSupport.PARTIAL, sourceBrief = true, sourceExtract = true, recoveryAttempted = true)
    }
}
