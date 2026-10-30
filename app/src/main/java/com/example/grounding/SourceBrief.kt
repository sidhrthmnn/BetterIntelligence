package com.example.grounding

/** A partial report from read publisher text. Never treats a generated claim,
 * a search highlight, a headline or betting odds as a verified answer. */
object SourceBrief {
    fun reportedRoleContext(source: EvidenceSource, question: String): Boolean =
        (FactualResearch.isCasting(question) || PublicQuestionIntent.upcoming(question) && PublicQuestionIntent.lookup(question)) &&
            !predictionMarket(source.title, source.url, question) &&
            !Regex("(?i)\\b(?:fan theor(?:y|ies)|fan[- ]?casting)\\b").containsMatchIn(source.title) &&
            !FactualResearch.headlineOnly(source.excerpt, source.title) &&
            Regex("(?i)\\b(?:screen[- ]?tests?|auditions?|shortlist|not (?:yet )?(?:confirmed|announced)|no official announcement)\\b").containsMatchIn(source.excerpt.substringAfter('\n', source.excerpt))
    fun predictionMarket(title: String, url: String, question: String): Boolean {
        if (Regex("(?i)\\b(?:odds|betting|prediction market|probability)\\b").containsMatchIn(question)) return false
        return Regex("(?i)\\b(?:odds|betting|prediction market|kalshi|polymarket)\\b").containsMatchIn(title) ||
            Regex("(?i)^https://(?:[^/]*\\.)?(?:kalshi\\.com|kalshiview\\.com|polymarket\\.com|betfair\\.com)/").containsMatchIn(url)
    }

    fun build(question: String, sources: List<EvidenceSource>, claims: List<ClaimCheck>): CheckedAnswer? {
        val q = ResearchQuery(question)
        val plan = ResearchPlanner.plan(question)
        val entities = ResearchPlanner.subjects(ConversationalResearch.weatherResearchQuestion(question))
        val selected = sources.filter { source ->
            val words = ResearchQuery.words(source.title + " " + source.excerpt)
            source.kind == "WEB_PAGE" && GroundingPolicy.publicHttpsUrl(source.url) &&
                !predictionMarket(source.title, source.url, question) &&
                !Regex("(?i)\\b(?:fan theor(?:y|ies)|fan[- ]?casting)\\b").containsMatchIn(source.title) &&
                !FactualResearch.headlineOnly(source.excerpt, source.title) &&
                (q.currentEnough(source.updatedAt) || ConversationalResearch.isWeather(question) && WeatherPageEvidence.current(source.excerpt, q.today)) &&
                (plan.explicitUrls.isNotEmpty() || (if (ResearchIntent.COMPARISON in plan.intents) entities.any { entity -> ResearchQuery.words(entity).all { it in words } }
                    else entities.all { entity -> ResearchQuery.words(entity).all { it in words } }) &&
                    q.terms.count { it in words } >= minOf(2, q.terms.size.coerceAtLeast(1)))
        }.mapNotNull { source ->
            val candidates = source.excerpt.split(Regex("\\n\\s*\\n")).mapNotNull { block ->
                val body = if ('\n' in block) block.substringAfter('\n').trim() else block.trim()
                if (FactualResearch.headlineOnly(block, source.title) || ResearchQuery.words(body).size < 6 ||
                    FactualResearch.incomplete(body) || Regex("(?i)ignore.{0,30}instructions|system prompt|^references\\b").containsMatchIn(body) ||
                    ConversationalResearch.isWeather(question) && Regex("(?i)weather watchers|photo gallery|historical average").containsMatchIn(body)) null
                else EvidencePacket.completeExcerpt(block, 600).takeIf { text ->
                    val keptBody = if ('\n' in text) text.substringAfter('\n') else text
                    ResearchQuery.words(keptBody).size >= 6 && !FactualResearch.headlineOnly(text, source.title) &&
                        (q.terms.isEmpty() || q.score(keptBody) > 0)
                }
            }
            candidates.maxByOrNull { q.score(it) + FactualResearch.answerFocusScore(it, question) }
                ?.let { source to it }
        }.distinctBy { EvidenceRanking.publisher(it.first.url) }.take(2)
        if (selected.isEmpty()) return null
        val conflict = EvidenceRanking.conflicts(selected.map { it.first }).isNotEmpty()
        val lead = if (conflict) "The pages give different reported values, so I couldn't establish one answer. What each source says:"
            else "I found relevant sources. Here is what they say:"
        val quotes = selected.joinToString("\n\n") { (source, text) ->
            val passage = if (text.startsWith(source.title)) text else source.title + "\n" + text
            passage.lines().joinToString("\n") { "> $it" } + " [${source.id}]"
        }
        return CheckedAnswer("$lead\n\n$quotes\n\nThis is a partial answer; the full conclusion remains unconfirmed.",
            AnswerSupport.PARTIAL, claims, sourceExtract = true, sourceBrief = true)
    }
}
