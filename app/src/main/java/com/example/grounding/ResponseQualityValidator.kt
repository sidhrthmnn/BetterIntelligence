package com.example.grounding

data class QualityValidation(
    val isValid: Boolean,
    val failureReason: String? = null
)

object ResponseQualityValidator {
    private val truncatedEndings = Regex("(?i)\\b(?:because the|due to|is currently at the|as well as the|and the|with the|such as|for example|because|and|or|but|with|at|to|from|for|in|on|of|the|a|an|that|which|is|are|was|were)\\s*[,.:;]?$")
    private val snippetArtifacts = Regex("(?i)^(?:search results?(?: for)?|showing results for|here are (?:the )?results|google search|web search results?|top results?)\\b|\\b(?:missing:\\s*\\w+|must include:\\s*\\w+)\\b")
    private val citationPattern = Regex("(?<![a-zA-Z0-9_\$])\\[(\\d+)]")

    fun validate(
        question: String,
        answer: String,
        sources: List<EvidenceSource> = emptyList(),
        isLocalOnly: Boolean = false
    ): QualityValidation {
        val trimmed = answer.trim()
        if (trimmed.isBlank()) {
            return QualityValidation(false, "Empty response")
        }

        // Empty or truncated responses
        if (trimmed.length < 4 && question.length > 8) {
            return QualityValidation(false, "Truncated or too short response")
        }
        if (truncatedEndings.containsMatchIn(trimmed) || (trimmed.endsWith("...") && trimmed.length < 30)) {
            return QualityValidation(false, "Incomplete or truncated response")
        }

        // Fabricated citations: [id] where id not in sources
        val allowedSourceIds = sources.map { it.id.toString() }.toSet()
        val foundCitationIds = citationPattern.findAll(trimmed).map { it.groupValues[1] }.toSet()
        if (foundCitationIds.isNotEmpty()) {
            if (isLocalOnly && sources.isEmpty()) {
                return QualityValidation(false, "Fabricated citations: local answer cannot contain citations $foundCitationIds")
            }
            val invalidCitations = foundCitationIds - allowedSourceIds
            if (invalidCitations.isNotEmpty() && !isLocalOnly) {
                return QualityValidation(false, "Fabricated citations: $invalidCitations")
            }
        }

        // Author names / citations without explanations
        val textWithoutCitations = citationPattern.replace(trimmed, "").trim()
        if (textWithoutCitations.isBlank()) {
            return QualityValidation(false, "Citations without explanation")
        }
        if (Regex("(?i)^(?:by\\s+)?[\\p{L} .'-]{2,30}$").matches(textWithoutCitations) &&
            sources.any { s -> s.excerpt.contains(textWithoutCitations, ignoreCase = true) || s.title.contains(textWithoutCitations, ignoreCase = true) } &&
            !Regex("(?i)\\b(?:is|was|are|released|announce|score|rating|winner|temperature)\\b").containsMatchIn(textWithoutCitations)) {
            return QualityValidation(false, "Author or entity name without explanation")
        }

        // Article titles presented as entire answers
        val bareTitle = textWithoutCitations.trim(' ', '"', '“', '”', '\'', '‘', '’')
        if (sources.any { s ->
            val cleanSourceTitle = s.title.substringBefore(" - ").substringBefore(" | ").substringBefore(" — ").trim(' ', '"', '“', '”', '\'', '‘', '’')
            s.title.equals(bareTitle, ignoreCase = true) ||
            s.title.trim().equals(bareTitle, ignoreCase = true) ||
            cleanSourceTitle.equals(bareTitle, ignoreCase = true)
        }) {
            return QualityValidation(false, "Article title presented as entire answer")
        }

        // Search-result snippets with no meaningful answer
        if (snippetArtifacts.containsMatchIn(trimmed)) {
            return QualityValidation(false, "Search-result snippet artifact")
        }

        // Unsupported current facts: numerical/date claims not supported by evidence sources
        if (sources.isNotEmpty() && !CitationSupport.supportsNumbers(trimmed, sources)) {
            return QualityValidation(false, "Unsupported current facts: numerical claims not supported by sources")
        }

        // Answers about the wrong entity
        val questionLabels = ExactIdentity.labels(question)
        if (questionLabels.isNotEmpty()) {
            val isExplainingUnconfirmedSequel = trimmed.contains("unconfirmed", ignoreCase = true) ||
                trimmed.contains("requested sequel", ignoreCase = true) ||
                trimmed.contains("not been officially confirmed", ignoreCase = true) ||
                trimmed.contains("no confirmed release date", ignoreCase = true)
            if (!isExplainingUnconfirmedSequel) {
                val answerLabels = ExactIdentity.labels(trimmed)
                if (answerLabels.isNotEmpty()) {
                    val matchesAnyQuestionLabel = questionLabels.any { qLabel ->
                        answerLabels.any { aLabel -> aLabel.equals(qLabel, ignoreCase = true) }
                    }
                    if (!matchesAnyQuestionLabel) {
                        return QualityValidation(false, "Answer is about wrong entity ($answerLabels vs $questionLabels)")
                    }
                }
            }
        }

        return QualityValidation(true, null)
    }
}
