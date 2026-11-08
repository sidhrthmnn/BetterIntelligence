package com.example.grounding

/** Related financing reports can help without being misrepresented as the
 * company's current drawn debt. Never sum facilities or turn funding into debt. */
object FinancialAnswerScope {
    private val request = Regex("(?i)\\bhow much\\b.*\\b(?:loans?|debt|owes?|borrow(?:ed|ing)?)\\b")
    private val money = Regex("(?i)(?:[£$€₹]\\s*\\d|\\b(?:USD|GBP|EUR|INR)\\s*\\d|\\d[\\d,. ]*\\s*(?:million|billion|trillion)?\\s*(?:dollars?|pounds?|euros?|rupees?)\\b)")
    private val financing = Regex("(?i)\\b(?:loans?|debt|credit (?:line|facility)|revolving credit|borrow(?:ed|ing)?)\\b")
    fun requested(question: String) = request.containsMatchIn(question)
    fun borrower(question: String) = Regex("(?i)\\bdoes (.+?) (?:have|owe)\\b").find(question)?.groupValues?.get(1)
        ?: ResearchPlanner.subjects(question).singleOrNull()

    /** A real body amount must belong to the requested borrower, not an
     * investor borrowing money to buy a stake in that company. */
    private fun bound(body: String, heading: String, subject: String, url: String): Boolean {
        val monetary = ClaimVerification.sentences(body).filter { money.containsMatchIn(it) && financing.containsMatchIn(it) }
        return if (monetary.isNotEmpty()) monetary.any { boundUnit(it, heading, subject, url) } else boundUnit(body, heading, subject, url)
    }
    private fun boundUnit(body: String, heading: String, subject: String, url: String): Boolean {
        val words = ResearchQuery.words(subject)
        val entity = words.joinToString("[^\\p{L}\\p{N}]+") { Regex.escape(it) + if (it.length > 3) "s?" else "" }
        if (entity.isBlank()) return false
        val recipient = Regex("(?i)\\b(?:handed|extended|provided|granted|lent|offered)(?: to)? (?!a\\b|an\\b|the\\b|new\\b|credit\\b|loan\\b)([\\p{L}][\\p{L}\\p{N} &+.-]{0,70}?)(?=\\s+(?:a|an|the|new|its)\\b|\\s*[£$€₹])")
            .find(body)?.groupValues?.get(1)?.let(ResearchQuery::words)
        if (recipient != null && !recipient.containsAll(words)) return false
        val actor = ClaimLanguage.financialActor(body)
        if (actor?.containsAll(words) == true) return true
        if (Regex("(?i)\\b(?:handed|extended|provided|granted|lent|offered)(?: to)? $entity\\b|\\b(?:loan|credit|facility) to $entity\\b").containsMatchIn(body)) return true
        val ownSite = url.isNotBlank() && runCatching { EvidenceRanking.publisher(url).substringBefore('.') == words.joinToString("") }.getOrDefault(false)
        if (ownSite && Regex("(?i)\\bwe (?:have |had )?(?:established|secured|obtained|announced|borrowed)\\b").containsMatchIn(body)) return true
        val startsWithSubject = Regex("(?i)^\\s*$entity\\b").containsMatchIn(heading)
        return startsWithSubject && actor == null
    }

    fun focusScore(text: String, question: String, url: String = ""): Int {
        if (!requested(question)) return 0
        val subject = borrower(question) ?: return 0
        return if (text.split(Regex("\\n\\s*\\n")).any { block ->
            val body = block.substringAfter('\n', block)
            money.containsMatchIn(body) && financing.containsMatchIn(body) &&
                bound(body, if ('\n' in block) block.substringBefore('\n') else "", subject, url) && !ClaimVerification.financeExample(body)
        }) 100 else 0
    }

    fun recover(question: String, sources: List<EvidenceSource>, checked: CheckedAnswer): CheckedAnswer? {
        if (!requested(question)) return null
        if (checked.support == AnswerSupport.CITED && money.containsMatchIn(checked.text) && !Regex("(?i)\\bcredit (?:line|facility)|revolving credit").containsMatchIn(checked.text) &&
            ClaimLanguage.answerScopeMatches(question, checked.text)) return null
        val subject = borrower(question) ?: return null
        val words = ResearchQuery.words(subject)
        if (words.isEmpty()) return null
        val query = ResearchQuery(question)
        val reports = sources.filter { it.kind == "WEB_PAGE" && query.currentEnough(it.updatedAt) && GroundingPolicy.publicHttpsUrl(it.url) }
            .mapNotNull { source ->
                val passage = source.excerpt.split(Regex("\\n\\s*\\n")).map { it.substringAfter('\n', it).trim() }.firstOrNull { body ->
                    body.length in 20..600 && money.containsMatchIn(body) && financing.containsMatchIn(body) &&
                        bound(body, source.title, subject, source.url) && !PublisherBoilerplate.unrelatedFooter(body, question) &&
                        !FactualResearch.headlineOnly(body, source.title) && !ClaimVerification.financeExample(body) && !FactualResearch.incomplete(body)
                } ?: return@mapNotNull null
                val units = ClaimVerification.sentences(passage)
                val index = units.indexOfFirst { money.containsMatchIn(it) && financing.containsMatchIn(it) && boundUnit(it, source.title, subject, source.url) }
                val concise = if (index >= 0) listOfNotNull(units[index], units.getOrNull(index + 1)?.takeIf {
                    Regex("(?i)\\b(?:undrawn|not|only|unless|provided|repay\\w*|matur\\w*|closing|interest|rate)\\b").containsMatchIn(it) &&
                        (Regex("(?i)^(?:The (?:facility|loan|balance)|This (?:facility|loan)|It)\\b").containsMatchIn(it) || ResearchQuery.words(it).containsAll(words))
                }).joinToString(" ") else passage
                ReadableSourceSummary.sentence(source, concise, question)?.let { summary ->
                    val date = ResearchQuery.date(source.updatedAt)?.takeIf { source.dateKind in setOf("PUBLISHED", "MODIFIED") }
                    if (date == null) summary else summary.replaceFirst(" reports", " reports (page dated $date)")
                }
            }.distinct().take(2)
        val period = if (query.years.isEmpty()) "current total outstanding debt" else "total outstanding debt for the requested period"
        if (reports.isEmpty()) {
            val terms = sources.filter { it.kind == "WEB_PAGE" && query.currentEnough(it.updatedAt) && GroundingPolicy.publicHttpsUrl(it.url) }.mapNotNull { source ->
                val body = source.excerpt.split(Regex("\\n\\s*\\n")).map { it.substringAfter('\n', it).trim() }.firstOrNull {
                    it.length in 20..600 && financing.containsMatchIn(it) && bound(it, source.title, subject, source.url) &&
                        Regex("(?i)\\b(?:unsecured|maturity|repayment|undrawn|tapped|drawn)\\b").containsMatchIn(it) && !FactualResearch.headlineOnly(it, source.title) &&
                        !ClaimVerification.financeExample(it) && !PublisherBoilerplate.unrelatedFooter(it, question)
                } ?: return@mapNotNull null
                ReadableSourceSummary.sentence(source, body, question)
            }.distinct().take(1)
            return checked.copy(text = terms.joinToString("\n\n", postfix = if (terms.isNotEmpty()) "\n\n" else "") +
                "The sources do not establish $subject's $period or give a verified amount borrowed.",
                support = if (terms.isEmpty()) AnswerSupport.INSUFFICIENT else AnswerSupport.PARTIAL,
                sourceBrief = terms.isNotEmpty(), sourceExtract = terms.isNotEmpty(), recoveryAttempted = true)
        }
        return checked.copy(text = reports.joinToString("\n\n") + "\n\nThe sources do not establish the $period or the amount actually drawn from these credit facilities.",
            support = AnswerSupport.PARTIAL, sourceBrief = true, sourceExtract = true, recoveryAttempted = true)
    }
}
