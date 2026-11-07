package com.example.grounding
object V29GroundingPolicy {
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
