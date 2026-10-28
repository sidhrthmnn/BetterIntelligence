package com.example.grounding

/** A bounded extractive fallback, not a looser verdict on generated claims.
 * Quotes come verbatim from retrieved passages; model text never gains an
 * inferred citation. Keep source headings, units, timing and qualifications. */
object EvidenceAnswer {
    fun hasTemperature(text: String) = Regex("(?i)-?\\d+(?:\\.\\d+)?\\s*°\\s*[CF]\\b|\\d+\\s+degrees?\\s+(?:celsius|fahrenheit)").containsMatchIn(text)

    fun resolve(draft: String, sources: List<EvidenceSource>, question: String): CheckedAnswer {
        val checked = GroundingPolicy.checkAnswer(draft, sources, question)
        val weather = ConversationalResearch.isWeather(question)
        val missingTemperature = weather && !hasTemperature(checked.text)
        if (checked.support != AnswerSupport.INSUFFICIENT && !missingTemperature) return checked
        val query = ResearchQuery(question)
        val plan = ResearchPlanner.plan(question)
        // A source excerpt cannot resolve a comparison or stale/current conflict.
        if (ResearchIntent.COMPARISON in plan.intents || EvidenceRanking.conflicts(sources).isNotEmpty()) return checked
        data class Extract(val source: EvidenceSource, val text: String, val score: Int)
        val extracts = sources.flatMap { source -> source.excerpt.split(Regex("\\n\\s*\\n")).mapNotNull { block ->
            val text = block.trim()
            val found = ResearchQuery.words(text)
            val subject = ResearchPlanner.subjects(question).all { entity -> ResearchQuery.words(entity).all { it in found } }
            val topical = query.terms.isNotEmpty() && query.terms.count { it in found } >= kotlin.math.ceil(query.terms.size * .65).toInt()
            val boilerplate = Regex("(?i)ignore.{0,30}instructions|system prompt|^references\\b|go to facebook|go to twitter").containsMatchIn(text)
            val role = Regex("(?i)\\b(mayor|president|minister|governor|ceo)\\b").find(question)?.value
            val section = text.substringBefore('\n').substringAfterLast('›')
            val wrongRole = role != null && Regex("(?i)\\b(lord|lady|deputy|vice|former|assistant)\\b").find(section)?.value?.let {
                !Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(question)
            } == true
            val current = !ResearchQuery.isCurrentIncumbentQuery(question) ||
                !Regex("(?i)\\b(former|deceased|died|passed away|was the)\\b").containsMatchIn(text)
            val weatherEvidence = !weather || hasTemperature(text) &&
                Regex("(?i)\\b(observed|observation|today|tonight|forecast|high|low|maximum|minimum)\\b").containsMatchIn(text) &&
                !Regex("(?i)photo|weather watchers").containsMatchIn(text)
            if (text.length !in 25..700 || !subject || !topical || boilerplate || wrongRole || !current || !weatherEvidence ||
                EvidenceRanking.contextOnly(source, plan)) null
            else Extract(source, text, query.score(text) + (if (hasTemperature(text)) 35 else 0) +
                (if (Regex("(?i)observed temperature").containsMatchIn(text)) 30 else 0))
        } }.sortedByDescending { it.score }.distinctBy { it.text.lowercase() }.take(if (weather) 2 else 1)
        if (extracts.isEmpty()) {
            if (!missingTemperature || checked.support == AnswerSupport.INSUFFICIENT) return checked
            return checked.copy(text = "${checked.text}\n\nTemperature was not available in the pages I could read.", support = AnswerSupport.PARTIAL)
        }
        val quotes = extracts.joinToString("\n\n") { extract ->
            extract.text.lines().joinToString("\n") { "> $it" } + " [${extract.source.id}]"
        }
        val existing = checked.text.takeIf { checked.support != AnswerSupport.INSUFFICIENT }.orEmpty()
        return CheckedAnswer("From the retrieved pages:\n\n$quotes" + if (existing.isBlank()) "" else "\n\n$existing",
            AnswerSupport.PARTIAL, checked.claims, sourceExtract = true)
    }
}
