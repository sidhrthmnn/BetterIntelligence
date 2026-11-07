package com.example.grounding
object V29GroundingPolicy {
    fun validateCitations(text: String, sources: List<EvidenceSource>): Pair<String, Boolean> {
        val ids = sources.map { it.id.toString() }.toSet()
        val code = mutableListOf<String>()
        val masked = Regex("```[\\s\\S]*?(?:```|$)|`[^`\\n]*`").replace(text.replace("\u0000", "")) { match ->
            code += match.value; "\u0000CODE${code.lastIndex}\u0000"
        }
        var cited = false
        var invented = false
        // Source links are supplied by the app. Model-authored Markdown links never establish citations.
        var clean = Regex("\\[([^]\\n]+)]\\([^)]*\\)").replace(masked, "$1")
        // Small models sometimes spell out the supplied source ID. Normalize
        // that explicit attribution, never guess a citation for uncited prose.
        clean = Regex("(?i)\\bsource\\s+(?:id\\s*|#\\s*)?(\\d{1,3})\\b").replace(clean) { match ->
            if (match.groupValues[1] !in ids) invented = true
            match.groupValues[1].takeIf { it in ids }?.let { "[$it]" } ?: match.value
        }
        clean = Regex("(?<![\\w])\\[(\\d{1,9}(?:\\s*,\\s*\\d{1,9})*)]").replace(clean) { match ->
            if (match.groupValues[1].split(',').any { it.trim() !in ids }) invented = true
            val verified = match.groupValues[1].split(',').map(String::trim).filter { it in ids }.distinct()
            if (verified.isNotEmpty()) { cited = true; verified.joinToString(", ", "[", "]") } else ""
        }
        // The UI provides trusted source links separately. Strip model-authored links in grounded responses.
        clean = Regex("https?://\\S+").replace(clean, "")
        code.forEachIndexed { index, value -> clean = clean.replace("\u0000CODE$index\u0000", value) }
        // Marker-only output is not an answer. Do not turn it into a cited response.
        if (cited && !Regex("\\[\\d+(?:,\\s*\\d+)*]").replace(clean, "").any { it.isLetterOrDigit() })
            return unavailableAnswer to false
        if (invented || (cited && (!V29CitationSupport.supportsNumbers(clean, sources) || !V29CitationSupport.citedParagraphs(clean)))) return unavailableAnswer to false
        return clean to cited
    }

 const val unavailableAnswer = GroundingPolicy.unavailableAnswer
    fun checkAnswer(text: String, sources: List<EvidenceSource>, question: String = ""): CheckedAnswer {
        // Normalize explicit source-ID spelling; never invent missing citations.
        val explicitIds = Regex("(?i)\\[ID\\s*:\\s*(\\d+(?:\\s*,\\s*(?:ID\\s*:\\s*)?\\d+)*)]").replace(text) { match ->
            Regex("\\d+").findAll(match.groupValues[1]).joinToString(", ", "[", "]") { it.value }
        }
        val normalized = Regex("(?i)\\bsource\\s+(?:id\\s*|#\\s*)?(\\d{1,3})\\b").replace(explicitIds) { "[${it.groupValues[1]}]" }
        val checked = V29ClaimVerification.check(normalized, sources.filter { FactualResearch.usable(it, question) }, question)
        if (checked.support == AnswerSupport.INSUFFICIENT || question.isBlank()) return checked
        val plan = ResearchPlanner.plan(question)
        return if (sources.any { EvidenceRanking.contextOnly(it, plan) }) checked.copy(support = AnswerSupport.PARTIAL,
            text = checked.text + "\n\nSome documentation is older or undated; it does not establish when these features were added.") else checked
    }

}
