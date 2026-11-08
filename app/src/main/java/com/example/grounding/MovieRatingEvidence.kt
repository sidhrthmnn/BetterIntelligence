package com.example.grounding

/** Visible labelled fields can answer a rating question even when a small
 * model fails synthesis. The value, scale and film must share one read page. */
object MovieRatingEvidence {
    private val field = Regex("(?i)\\b(?:rating|rated|score)\\s*:?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:out of|/)\\s*(5|10|100)\\b")
    fun summary(question: String, sources: List<EvidenceSource>, claims: List<ClaimCheck>): CheckedAnswer? {
        val topic = PublicTopics.parse(question) ?: return null
        if (topic.type != PublicEntityType.FILM || topic.intent !in setOf(PublicTopicIntent.REVIEW, PublicTopicIntent.RATING)) return null
        val lines = sources.filter { it.kind == "WEB_PAGE" && topic.sourceMatches(it.title, it.excerpt, it.url, it.publicLanguage) }.mapNotNull { source ->
            val values = source.excerpt.split(Regex("\\n\\s*\\n")).flatMap { block ->
                val heading = if ('\n' in block) block.substringBefore('\n') else source.title
                val body = block.substringAfter('\n', block)
                if (!topic.subjectMatches(heading) || Regex("(?i)related|other movies|also read|recommended").containsMatchIn(heading)) emptyList()
                else field.findAll(body).mapNotNull { match ->
                    val value = match.groupValues[1].toBigDecimalOrNull()
                    val scale = match.groupValues[2].toBigDecimal()
                    if (value == null || value.signum() < 0 || value > scale) null else match.groupValues[1] to match.groupValues[2]
                }.toList()
            }.distinct()
            // Multiple differently scoped scores need narrative context; never
            // pool them or pick a convenient value from an unrelated rating.
            values.singleOrNull()?.let { (value, scale) ->
                "${ReadableSourceSummary.publisher(source.url)} lists a rating of $value out of $scale for ${topic.subject} [${source.id}]."
            }
        }.distinct().take(2)
        if (lines.isEmpty()) return null
        return CheckedAnswer(lines.joinToString("\n\n") + if (topic.intent == PublicTopicIntent.REVIEW)
            "\n\nI couldn't verify a fuller critical assessment from the readable text." else "",
            AnswerSupport.PARTIAL, claims, sourceBrief = true)
    }
}
