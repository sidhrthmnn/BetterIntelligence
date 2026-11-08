package com.example.grounding

/** Format already checked text without creating facts, citations or new values. */
object AnswerPresentation {
    private val citation = Regex("\\[\\d+(?:,\\s*\\d+)*]")
    fun paragraphs(text: String): String {
        val kept = mutableListOf<String>()
        for (part in text.split(Regex("\\n\\s*\\n")).map(String::trim).filter(String::isNotEmpty)) {
            fun key(value: String) = citation.replace(value, "").lowercase().replace(Regex("^the source (?:explicitly )?states that\\s+"), "")
                .replace(Regex("[^\\p{L}\\p{N}%°+−-]+"), " ").trim()
            val ids = citation.findAll(part).map { it.value }.toSet()
            val prior = kept.indexOfFirst { old ->
                ids.isNotEmpty() && citation.findAll(old).map { it.value }.toSet() == ids &&
                    (key(old) == key(part) || key(part).startsWith(key(old) + " "))
            }
            if (prior >= 0) kept[prior] = part else if (kept.none { key(it) == key(part) }) kept += part
        }
        return kept.joinToString("\n\n")
    }

    /** Presentation only: retain entire checked claims, qualifiers and IDs. */
    fun format(text: String, question: String, sources: List<EvidenceSource>): String {
        if ("```" in text || Regex("(?i)\\bjson(?:[- ]only)?\\b").containsMatchIn(question)) return paragraphs(text)
        val parts = paragraphs(text).split(Regex("\\n\\s*\\n")).map { raw ->
            val part = raw.replace(Regex("^\\s*[*•]\\s+"), "- ")
            if (!Regex("(?i)\\b(?:we|we've|our|I tested)\\b").containsMatchIn(part)) part else {
                val ids = citation.findAll(part).flatMap { Regex("\\d+").findAll(it.value).mapNotNull { n -> n.value.toIntOrNull() } }.toSet()
                val source = sources.singleOrNull { it.id in ids }
                if (ids.isEmpty() || Regex("(?i)reports.*:").containsMatchIn(part)) part else
                    (source?.let { ReadableSourceSummary.publisher(it.url).takeIf { name -> name.firstOrNull()?.isLetter() == true } }
                        ?: if (ids.size > 1) "The cited sources" else "The cited source") + " reports: " + part
            }
        }
        val facts = parts.filter { citation.containsMatchIn(it) && '\n' !in it && '|' !in it }
        if (facts.size < 2) return parts.joinToString("\n\n")
        val subjects = ResearchPlanner.subjects(question)
        if (ResearchIntent.COMPARISON in ResearchPlanner.plan(question).intents && subjects.size >= 2) {
            val rows = facts.map { fact -> subjects.singleOrNull { ResearchQuery.words(fact).containsAll(ResearchQuery.words(it)) } to fact }
            if (rows.all { it.first != null } && rows.map { it.first }.distinct().size >= 2) {
                val table = "| Item | What the sources report |\n|---|---|\n" + rows.joinToString("\n") { "| ${it.first} | ${it.second} |" }
                return table + parts.filter { it !in facts }.joinToString("\n\n", prefix = if (parts.size > facts.size) "\n\n" else "")
            }
        }
        return parts.joinToString("\n\n") { if (it in facts && !it.startsWith("- ") && !Regex("^\\d+[.)]\\s+").containsMatchIn(it)) "- $it" else it }
    }
}

/** A cinema release date never establishes a streaming release date. A useful
 * related source report remains visible, with the precise gap identified. */
object ReleaseAnswerScope {
    const val missingOtt = "The sources I read do not give an exact OTT or streaming release date."
    private val streaming = Regex("(?i)\\b(?:ott|streaming|digital release|digital premiere)\\b")
    private val unannounced = Regex("(?i)\\b(?:not (?:yet )?(?:been )?(?:officially )?(?:announced|confirmed|scheduled)|no (?:confirmed|official) (?:ott |streaming )?date)\\b")
    private val uncertain = Regex("(?i)\\b(?:could|may|might|expected|estimated|tentative|potentially|unconfirmed)\\b")
    private val month = "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"
    private val date = Regex("(?i)\\b(?:$month\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?|\\d{1,2}(?:st|nd|rd|th)?\\s+$month|20\\d{2}-\\d{2}-\\d{2}|\\d{1,2}[/.]\\d{1,2}[/.]20\\d{2})\\b")
    private fun requested(question: String) = streaming.containsMatchIn(question) && Regex("(?i)\\breleas\\w*|\\bwhen\\b").containsMatchIn(question)
    private fun qualified(text: String) = uncertain.containsMatchIn(text) || unannounced.containsMatchIn(text)
    fun focusScore(text: String, question: String): Int {
        val body = text.substringAfter('\n', text)
        return if (requested(question) && streaming.containsMatchIn(body) &&
            !Regex("(?i)\\b(?:theatrical|theatres?|theaters?|cinemas?)\\b").containsMatchIn(body)) 60 else 0
    }
    private fun timing(text: String) = streaming.containsMatchIn(text) && (unannounced.containsMatchIn(text) ||
        text.split(Regex("(?i)[,;]|\\b(?:whereas|while|after|following)\\b")).any { clause ->
            date.containsMatchIn(clause) && streaming.containsMatchIn(clause) &&
                !Regex("(?i)\\b(?:theatrical|theatres?|theaters?|cinemas?)\\b").containsMatchIn(clause)
        })

    /** Complete the requested release facet rather than requiring overlap with
     * every word of the question, then padding a correct date with page debris. */
    fun focus(question: String, sources: List<EvidenceSource>, checked: CheckedAnswer): CheckedAnswer? {
        if (!requested(question) || EvidenceRanking.conflicts(sources).isNotEmpty()) return null
        val contextual = mutableListOf<EvidenceSource>()
        fun lines(source: EvidenceSource) = source.excerpt.split(Regex("\\n\\s*\\n")).flatMap { block ->
            val body = if ('\n' in block) block.substringAfter('\n') else block
            // Some publisher markup joins a full stop directly to a quoted title.
            val units = ClaimVerification.sentences(body.replace(Regex("(?<=[.!?])(?=['‘“]\\p{Lu})"), "\n"))
            units.mapIndexed { index, sentence ->
                val previous = units.getOrNull(index - 1)
                if (previous != null && previous.length <= 300 && timing(sentence) &&
                    Regex("(?i)^(?:According to (?:the )?(?:platform|studio|makers?)|The platform|It (?:will|starts|begins))\\b").containsMatchIn(sentence) &&
                    Regex("(?i)\\b(?:announcement|announced|platform|studio)\\b").containsMatchIn(previous) && !PublisherBoilerplate.unrelatedFooter(previous, question)) {
                    val expanded = "$previous $sentence"
                    contextual += source.copy(excerpt = expanded)
                    expanded
                } else sentence
            }
        }.filter { it.length in 12..650 && !PublisherBoilerplate.unrelatedFooter(it, question) &&
            !FactualResearch.incomplete(it) && !FactualResearch.headlineOnly(it, source.title) && timing(it) }
        val passages = sources.associate { it.id to lines(it) }
        // Keep the actual antecedent (e.g. the named streamer) with "according
        // to the platform". Both sentences come from the same read paragraph.
        SourceBrief.build(question, contextual, checked.claims)?.let { return it.copy(recoveryAttempted = true) }
        val direct = checked.claims.filter { claim ->
            claim.supported && claim.reason == "Passage checks passed" && timing(claim.text) &&
                !PublisherBoilerplate.unrelatedFooter(claim.text, question) &&
                (qualified(claim.text) || claim.sourceIds.none { id -> passages[id].orEmpty().any { qualified(it) } })
        }.take(2).map { it.text }
        if (direct.isNotEmpty()) return checked.copy(text = direct.joinToString("\n\n"), support = AnswerSupport.CITED)
        val extracts = sources.flatMap { source -> passages[source.id].orEmpty().mapNotNull { text ->
            val line = "$text [${source.id}]"
            line.takeIf { GroundingPolicy.checkAnswer(it, listOf(source), question).support == AnswerSupport.CITED }
        } }.distinct().take(2)
        if (extracts.isNotEmpty()) return checked.copy(text = extracts.joinToString("\n\n"), support = AnswerSupport.CITED,
            sourceExtract = true, recoveryAttempted = true, recoveredClaims = extracts.size)
        val related = SourceBrief.build(question, sources, checked.claims) ?: return null
        if (timing(related.text)) return related.copy(recoveryAttempted = true)
        return related.copy(text = related.text + "\n\n" + missingOtt, support = AnswerSupport.PARTIAL, recoveryAttempted = true)
    }
    fun apply(question: String, sources: List<EvidenceSource>, checked: CheckedAnswer): CheckedAnswer {
        if (missingOtt in checked.text) return checked
        if (!streaming.containsMatchIn(question) || !Regex("(?i)\\breleas\\w*|\\bwhen\\b").containsMatchIn(question)) return checked
        val hasTiming = ClaimVerification.sentences(checked.text).any { line -> timing(line) && Regex("\\[\\d").containsMatchIn(line) }
        if (hasTiming) return checked
        val useful = if (checked.support == AnswerSupport.INSUFFICIENT) SourceBrief.build(question, sources, checked.claims) ?: return checked else checked
        return useful.copy(text = useful.text.replace("The sources do not cover every requested detail.", "").trim() + "\n\n" + missingOtt,
            support = AnswerSupport.PARTIAL)
    }
}
