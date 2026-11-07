package com.example.grounding

/** A bounded extractive fallback, not a looser verdict on generated claims.
 * Quotes come verbatim from retrieved passages; model text never gains an
 * inferred citation. Keep source headings, units, timing and qualifications. */
object V29EvidenceAnswer {
    fun hasTemperature(text: String) = Regex("(?i)(?<![\\p{L}\\p{N}])-?\\d+(?:\\.\\d+)?\\s*(?:°\\s*)?[CF]\\b|\\d+\\s+degrees?\\s+(?:celsius|fahrenheit)").containsMatchIn(text)

    fun resolve(draft: String, sources: List<EvidenceSource>, question: String): CheckedAnswer {
        val aligned = sources.filter { PublicTopics.parse(question)?.sourceMatches(it.title, it.excerpt, it.url) != false }
        if (aligned.any { SourceBrief.reportedRoleContext(it, question) })
            SourceBrief.build(question, aligned, emptyList())?.let { return it }
        val prose = draft.replace(Regex("(?im)^\\s*(?:From the retrieved pages|Untrusted source evidence|Here are the retrieved excerpts)\\s*:\\s*"), "")
            .replace(Regex("(?m)^\\s*>\\s?"), "")
        val checked = resolveStrict(prose, aligned, question)
        val resolved = if (checked.support == AnswerSupport.INSUFFICIENT)
            MovieRatingEvidence.summary(question, aligned, checked.claims) ?: SourceBrief.build(question, aligned, checked.claims) ?: checked else checked
        return focusReview(resolved, question)
    }

    /** Keep verified review conclusions ahead of tangential production facts.
     * The prefix is the user's subject, not a new sourced factual assertion. */
    private fun focusReview(checked: CheckedAnswer, question: String): CheckedAnswer {
        val topic = PublicTopics.parse(question) ?: return checked
        if (topic.intent !in setOf(PublicTopicIntent.REVIEW, PublicTopicIntent.RATING) || checked.sourceExtract || checked.sourceBrief) return checked
        val facts = checked.claims.filter { it.supported && it.reason == "Passage checks passed" && FactualResearch.answerFocusScore(it.text, question) >= 65 }
            .sortedByDescending { FactualResearch.answerFocusScore(it.text, question) + if (Regex("(?i)\\b(?:critic|review|praised|criticised|criticized)\\b").containsMatchIn(it.text)) 50 else 0 }
            .take(3)
        if (facts.isEmpty()) return checked
        val text = facts.joinToString("\n\n") { it.text }
        return checked.copy(text = if (topic.subjectMatches(text)) text else "${topic.subject} — $text")
    }

    private fun resolveStrict(draft: String, sources: List<EvidenceSource>, question: String): CheckedAnswer {
        val checked = V29GroundingPolicy.checkAnswer(draft, sources, question)
        val weather = ConversationalResearch.isWeather(question)
        val missingTemperature = weather && !hasTemperature(checked.text)
        val labelledWeather = weather && sources.any { Regex("(?i)observed temperature:|(?:today|tonight) forecast: (?:high|low)").containsMatchIn(it.excerpt) }
        val definitionMissing = FactualResearch.definitionTopic(question) != null && !FactualResearch.definitionLine(checked.text, question)
        val focusMissing = sources.any { FactualResearch.answerFocusScore(it.excerpt, question) >= 65 } && FactualResearch.answerFocusScore(checked.text, question) < 65
        if (checked.support != AnswerSupport.INSUFFICIENT && !missingTemperature && !labelledWeather && !definitionMissing && !focusMissing) return checked
        val query = ResearchQuery(question)
        val plan = ResearchPlanner.plan(question)
        val office = ResearchQuery.isCurrentIncumbentQuery(question)
        // A source excerpt cannot resolve a comparison or stale/current conflict.
        if (ResearchIntent.COMPARISON in plan.intents || EvidenceRanking.conflicts(sources).isNotEmpty()) return checked
        data class Extract(val source: EvidenceSource, val text: String, val score: Int)
        val extracts = sources.flatMap { source -> source.excerpt.split(Regex("\\n\\s*\\n")).mapNotNull { block ->
            val text = block.trim()
            val body = if ('\n' in text) text.substringAfter('\n') else text
            if (FactualResearch.headlineOnly(text, source.title) || FactualResearch.speculativeCasting(question, source.title) ||
                FactualResearch.incomplete(body) || definitionMissing && !FactualResearch.definitionLine(body, question) ||
                focusMissing && FactualResearch.answerFocusScore(text, question) < 65 ||
                FactualResearch.isCasting(question) && (!FactualResearch.castingRelation(body) || FactualResearch.speculativeCasting(question, body))) return@mapNotNull null
            val found = ResearchQuery.words(text)
            val activeOffice = Regex("(?i)\\b(incumbent|current|present|serving|in office|leads|is (?:the )?(?:chief minister|prime minister|president|governor|mayor|ceo))\\b").containsMatchIn(text)
            val titleWords = ResearchQuery.words(source.title)
            val roleContext = office && activeOffice && ResearchPlanner.subjects(question).all { entity -> ResearchQuery.words(entity).all { it in titleWords } }
            // A transcript can discuss the count without repeating the country
            // in every paragraph. Preserve the read article's title as visible
            // quotation context for one requested news subject, never as a value.
            val requestedSubjects = ResearchPlanner.subjects(question).ifEmpty { if (NewsIntent.isLiveRequest(question)) NewsIntent.countriesIn(question) else emptyList() }
            val newsContext = NewsIntent.isLiveRequest(question) && requestedSubjects.size == 1 &&
                FactualResearch.answerFocusScore(text, question) >= 65 &&
                requestedSubjects.all { entity -> ResearchQuery.words(entity).all { it in titleWords } }
            val contextual = (if (roleContext || newsContext) found + titleWords else found) + EvidenceRanking.localeWords(source.url)
            val subject = ResearchPlanner.subjects(question).all { entity -> ResearchQuery.words(entity).all { it in contextual } }
            val directSummary = plan.explicitUrls.isNotEmpty() && query.terms.isEmpty() && ResearchQuery.words(body).size >= 6
            val topical = directSummary || query.terms.isNotEmpty() && query.terms.count { it in contextual } >= kotlin.math.ceil(query.terms.size * .65).toInt()
            val boilerplate = Regex("(?i)ignore.{0,30}instructions|system prompt|^references\\b|go to facebook|go to twitter").containsMatchIn(text) || V29ClaimVerification.financeExample(text)
            val role = Regex("(?i)\\b(mayor|president|minister|governor|ceo)\\b").find(question)?.value
            val section = text.substringBefore('\n').substringAfterLast('›')
            val wrongRole = role != null && Regex("(?i)\\b(lord|lady|deputy|vice|former|assistant)\\b").find(section)?.value?.let {
                !Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(question)
            } == true
            val current = !office || activeOffice && !Regex("(?i)\\b(former|deceased|died|passed away|was the)\\b").containsMatchIn(text)
            val weatherEvidence = !weather || hasTemperature(text) &&
                Regex("(?i)\\b(observed|observation|today|tonight|forecast|high|low|maximum|minimum)\\b").containsMatchIn(text) &&
                !Regex("(?i)photo|weather watchers|historical average|upcoming days|monthly average").containsMatchIn(text) &&
                (!labelledWeather || Regex("(?i)observed temperature:|(?:today|tonight) forecast: (?:high|low)").containsMatchIn(text))
            if (text.length !in 25..700 || !subject || !topical || boilerplate || wrongRole || !current || !weatherEvidence ||
                EvidenceRanking.contextOnly(source, plan)) null
            else Extract(source, if (newsContext && !text.contains(source.title)) "${source.title}\n$text" else text,
                query.score(text) + (if (directSummary) minOf(30, body.length / 20) else 0) + FactualResearch.definitionQuality(body, question) + (if (hasTemperature(text)) 35 else 0) +
                (if (Regex("(?i)observed temperature").containsMatchIn(text)) 30 else 0) + FactualResearch.answerFocusScore(text, question))
        } }.sortedByDescending { it.score }.distinctBy { it.text.lowercase() }.take(if (weather) 2 else 1)
        if (extracts.isEmpty()) {
            if (definitionMissing) return CheckedAnswer(V29GroundingPolicy.unavailableAnswer, AnswerSupport.INSUFFICIENT, checked.claims)
            if (!missingTemperature || checked.support == AnswerSupport.INSUFFICIENT) return checked
            return checked.copy(text = "${checked.text}\n\nTemperature was not available in the pages I could read.", support = AnswerSupport.PARTIAL)
        }
        val summary = ReadableSourceSummary.build(extracts.map { it.source to it.text }, question) ?: return checked
        val existing = checked.text.takeIf { checked.support != AnswerSupport.INSUFFICIENT && !labelledWeather && !definitionMissing && !focusMissing }.orEmpty()
        return CheckedAnswer(summary + if (existing.isBlank()) "" else "\n\n$existing",
            AnswerSupport.PARTIAL, checked.claims, sourceExtract = true)
    }
}
