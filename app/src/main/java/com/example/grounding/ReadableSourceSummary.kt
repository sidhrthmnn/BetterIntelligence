package com.example.grounding

/** A short attributed explanation when model synthesis fails verification.
 * Only complete sentences already present in the read body are admitted; no
 * generated claim gains a citation and no headings/JSON/blocks reach the UI. */
object ReadableSourceSummary {
    fun publisher(url: String): String = when (val host = EvidenceRanking.publisher(url)) {
        "thehindu.com" -> "The Hindu"
        "indianexpress.com" -> "The Indian Express"
        "imdb.com" -> "IMDb"
        "rottentomatoes.com" -> "Rotten Tomatoes"
        "disney.com" -> "Disney"
        "marvel.com" -> "Marvel"
        "bbc.co.uk", "bbc.com" -> "BBC"
        else -> host
    }
    fun sentence(source: EvidenceSource, passage: String, question: String): String? {
        val lines = passage.lines()
        val first = lines.firstOrNull().orEmpty()
        val heading = lines.size > 1 && !Regex("[.!?]$|(?i)observed|forecast|temperature|\\d").containsMatchIn(first) &&
            ('›' in first || ResearchQuery.words(source.title).containsAll(ResearchQuery.words(first)) || first == "Read the Full Transcript")
        val body = lines.drop(if (heading) 1 else 0).filter { line -> line.isNotBlank() && line != source.title && '›' !in line &&
            !line.startsWith("Published ") && !line.startsWith("Updated ") && line != "Read the Full Transcript" }.joinToString(" ").trim()
        // Some publishers flatten JSON-LD headings directly against sentence
        // punctuation. Restore boundaries without changing names or values.
        val separated = body.replace(Regex("(?<=[.!?])(?=\\p{Lu}\\p{Ll}|A\\s)"), "\n")
        val allCandidates = ClaimVerification.sentences(separated).filter { text ->
            text.length in 8..650 && text.any(Char::isLetter) && !FactualResearch.headlineOnly(text, source.title) && !PublisherBoilerplate.unrelatedFooter(text, question) && !FactualResearch.incomplete(text) &&
                !Regex("(?i)ignore.{0,30}instructions|system prompt|subscribe|bookmark popover|share this|sign in|click here|https?://|^[{\\[]|^I (?:am|have|can)").containsMatchIn(text)
        }
        val candidates = allCandidates.filter { ReleaseAnswerScope.focusScore(it, question) > 0 }.ifEmpty { allCandidates }
        val q = ResearchQuery(question)
        val best = candidates.maxByOrNull { FactualResearch.answerFocusScore(it, question, source.url) + SourceBrief.roleReportScore(it, question) + q.score(it) } ?: return null
        // Keep a neighbouring complete sentence when it carries timing,
        // negation, attribution or a condition needed to interpret the value.
        val chosen = candidates.takeIf { it.size <= 3 && it.joinToString(" ").length <= 500 }?.joinToString(" ") ?: best
        val missing = ResearchPlanner.subjects(question).filter { subject ->
            !ResearchQuery.words(chosen).containsAll(ResearchQuery.words(subject)) &&
                ResearchQuery.words(source.title).containsAll(ResearchQuery.words(subject))
        }
        val scope = if (missing.isEmpty()) "" else " on ${missing.joinToString(" and ")}"
        val sentence = chosen + if (chosen.lastOrNull() in listOf('.', '!', '?')) "" else "."
        return "${publisher(source.url)} reports$scope: $sentence [${source.id}]"
    }
    fun build(selected: List<Pair<EvidenceSource, String>>, question: String, conflict: Boolean = false): String? {
        val lines = selected.mapNotNull { (source, text) -> sentence(source, text, question) }.distinct().take(2)
        if (lines.isEmpty()) return null
        val tail = if (conflict) "I couldn't establish a single conclusion because the reports differ."
            else ""
        return lines.joinToString("\n\n") + if (tail.isBlank()) "" else "\n\n$tail"
    }
}
