package com.example.grounding

/** Format already checked text without creating facts, citations or new values. */
object AnswerPresentation {
    private val citation = Regex("\\[\\d+(?:,\\s*\\d+)*]")
    fun paragraphs(text: String): String {
        val kept = mutableListOf<String>()
        for (part in text.split(Regex("\\n\\s*\\n")).map(String::trim).filter(String::isNotEmpty)) {
            fun key(value: String) = citation.replace(value, "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            val ids = citation.findAll(part).map { it.value }.toSet()
            val prior = kept.indexOfFirst { old ->
                ids.isNotEmpty() && citation.findAll(old).map { it.value }.toSet() == ids &&
                    (key(old) == key(part) || key(part).startsWith(key(old) + " "))
            }
            if (prior >= 0) kept[prior] = part else if (kept.none { key(it) == key(part) }) kept += part
        }
        return kept.joinToString("\n\n")
    }
}

/** A cinema release date never establishes a streaming release date. A useful
 * related source report remains visible, with the precise gap identified. */
object ReleaseAnswerScope {
    const val missingOtt = "The sources I read do not give an OTT or streaming release date."
    private val streaming = Regex("(?i)\\b(?:ott|streaming|digital release|digital premiere)\\b")
    fun apply(question: String, sources: List<EvidenceSource>, checked: CheckedAnswer): CheckedAnswer {
        if (!streaming.containsMatchIn(question) || !Regex("(?i)\\breleas\\w*|\\bwhen\\b").containsMatchIn(question)) return checked
        val hasTiming = ClaimVerification.sentences(checked.text).any { line ->
            streaming.containsMatchIn(line) && (CitationSupport.hasFactNumber(line) ||
                Regex("(?i)\\b(?:not (?:yet )?(?:announced|confirmed|scheduled)|no (?:confirmed|official) date)\\b").containsMatchIn(line)) &&
                Regex("\\[\\d").containsMatchIn(line)
        }
        if (hasTiming) return checked
        val useful = if (checked.support == AnswerSupport.INSUFFICIENT) SourceBrief.build(question, sources, checked.claims) ?: return checked else checked
        return useful.copy(text = useful.text.replace("The sources do not cover every requested detail.", "").trim() + "\n\n" + missingOtt,
            support = AnswerSupport.PARTIAL)
    }
}
