package com.example.grounding

/** Deterministic evidence-first recovery. It selects original complete claims,
 * verifies their source binding, and assembles IDs; it never rewrites values. */
object EvidenceRecovery {
    private val structural = ResearchQuery.words("compare comparison versus vs difference between specification specifications require support run tell describe explain much many about detail details")
    fun requested(question: String): Set<String> = ClaimLanguage.words(ResearchQuery(question).terms.joinToString(" ")) -
        ResearchPlanner.subjects(question).flatMap { ClaimLanguage.words(it) }.toSet() - structural

    private fun citedSourceTitles(line: String, sources: List<EvidenceSource>, singleSubject: Boolean): String {
        if (!singleSubject || sources.isEmpty()) return ""
        val ids = Regex("\\[(\\d+(?:,\\s*\\d+)*)]").findAll(line).flatMap {
            Regex("\\d+").findAll(it.value).mapNotNull { m -> m.value.toIntOrNull() }
        }.toSet()
        return sources.filter { it.id in ids }.joinToString(" ") { it.title }
    }

    fun complete(question: String, lines: List<String>, sources: List<EvidenceSource> = emptyList()): Boolean {
        val subjects = ResearchPlanner.subjects(question)
        val requested = requested(question)
        if (requested.isEmpty()) return false // Open-ended explanations are not proven complete by one quote.
        val needsAmount = Regex("(?i)how (?:much|many)\\b").containsMatchIn(question)
        if (needsAmount && !CitationSupport.hasFactNumber(lines.joinToString(" "))) return false
        val singleSubject = subjects.size == 1
        return (if (subjects.isEmpty()) listOf("") else subjects).all { subject ->
            val scoped = lines.filter { line ->
                val context = citedSourceTitles(line, sources, singleSubject)
                ClaimLanguage.words("$line $context").containsAll(ClaimLanguage.words(subject))
            }
            ClaimLanguage.words(scoped.joinToString(" ")).containsAll(requested)
        }
    }

    fun recover(question: String, sources: List<EvidenceSource>, checked: CheckedAnswer): CheckedAnswer? {
        val plan = ResearchPlanner.plan(question)
        if (ConversationalResearch.isWeather(question) || FactualResearch.isCasting(question) ||
            FactualResearch.isMovieReview(question) || EvidenceRanking.conflicts(sources).isNotEmpty()) return null
        val requested = requested(question)
        if (requested.isEmpty()) return null
        data class Candidate(val text: String, val score: Int)
        val candidates = sources.filter { FactualResearch.usable(it, question) && !EvidenceRanking.contextOnly(it, plan) }.flatMap { source ->
            val rawBlocks = source.excerpt.split(Regex("\\n\\s*\\n")).take(64)
            rawBlocks.flatMapIndexed { blockIdx, block ->
                val lines = block.lines()
                val heading = lines.firstOrNull()?.takeIf { lines.size > 1 && it.length < 150 && !it.endsWith('.') }
                val body = if (heading == null) block else lines.drop(1).joinToString(" ")
                val prevBlock = rawBlocks.getOrNull(blockIdx - 1)
                val prevLines = prevBlock?.lines().orEmpty()
                val prevHeading = prevLines.firstOrNull()?.takeIf { prevLines.size > 1 && it.length < 150 && !it.endsWith('.') }
                val prevBlockBody = if (prevBlock == null) null else if (prevHeading == null) prevBlock else prevLines.drop(1).joinToString(" ")
                val units = ClaimVerification.sentences(body)
                val needsAmount = Regex("(?i)how (?:much|many)\\b").containsMatchIn(question)
                val financial = Regex("(?i)\\b(?:loan|debt)\\b").containsMatchIn(question)
                val expandedUnits = units.flatMapIndexed { idx, unit ->
                    val prev = units.getOrNull(idx - 1) ?: prevBlockBody?.takeIf { prevHeading == heading }
                    if (prev != null && Regex("(?i)\\balso\\b").containsMatchIn(unit) &&
                        (prev.length + 1 + unit.length) <= 650 &&
                        ClaimLanguage.words(prev).intersect(requested).isNotEmpty()) listOf("$prev $unit", unit) else listOf(unit)
                }
                val choices = if (financial && body.length <= 650 && CitationSupport.hasFactNumber(body)) listOf(body) + expandedUnits else expandedUnits
                choices.distinct().mapNotNull { sentence ->
                    if (sentence.length !in 12..650 || PublisherBoilerplate.unrelatedFooter(block, question) || needsAmount && !CitationSupport.hasFactNumber(sentence) || FactualResearch.incomplete(sentence) ||
                        FactualResearch.headlineOnly(sentence, source.title) || ClaimVerification.financeExample(block) ||
                        Regex("(?i)ignore.{0,30}instructions|system prompt|https?://|subscribe|sign in|^references\\b").containsMatchIn(sentence)) return@mapNotNull null
                    val scoped = if (heading != null && ResearchPlanner.subjects(question).any { subject ->
                            ResearchQuery.words(heading).containsAll(ResearchQuery.words(subject)) &&
                                !ResearchQuery.words(sentence).containsAll(ResearchQuery.words(subject)) }) "$heading — $sentence" else sentence
                    if (!ClaimLanguage.answerScopeMatches(question, scoped)) return@mapNotNull null
                    val overlap = requested.count { it in ClaimLanguage.words(scoped) }
                    if (overlap == 0) return@mapNotNull null
                    val claim = "$scoped [${source.id}]"
                    val verified = GroundingPolicy.checkAnswer(claim, listOf(source), question)
                    if (verified.support != AnswerSupport.CITED) null else Candidate(claim, overlap * 10)
                }
            }
        }.sortedByDescending { it.score }.distinctBy { it.text }.take(16)
        val kept = checked.claims.filter { it.supported && it.reason == "Passage checks passed" && ClaimLanguage.words(it.text).intersect(requested).isNotEmpty() && ClaimLanguage.answerScopeMatches(question, it.text) && !Regex("(?i)^(?:it|they|this|that|therefore|thus|however)\\b").containsMatchIn(it.text.trim()) }.map { it.text }.toMutableList()
        var added = 0
        for (candidate in candidates) {
            if (kept.size >= 4 || complete(question, kept, sources)) break
            val candidateBody = candidate.text.substringBefore(" [").substringAfter(" — ")
            val candidateSentences = ClaimVerification.sentences(candidateBody)
            val overlapsExisting = kept.any { existing ->
                val cleanExisting = CitationSupport.normalize(existing).substringBefore(" [")
                cleanExisting.contains(candidateBody, true) ||
                    candidateSentences.any { s ->
                        val core = s.replace(Regex("(?i)^(?:the\\s+(?:film|movie|show|book|company|team)|it|they|he|she)\\s+"), "").trim()
                        core.length >= 20 && cleanExisting.contains(core, true)
                    }
            }
            if (!overlapsExisting) {
                kept += candidate.text; added++
            }
        }
        if (kept.isEmpty() || added == 0 && checked.support == AnswerSupport.INSUFFICIENT) return null
        val subjects = ResearchPlanner.subjects(question)
        val singleSubject = subjects.size == 1
        val complete = complete(question, kept, sources)
        val missingSubjects = subjects.filter { subject ->
            kept.none { line ->
                val context = citedSourceTitles(line, sources, singleSubject)
                ClaimLanguage.words("$line $context").containsAll(ClaimLanguage.words(subject))
            }
        }
        val tail = if (complete) "" else if (missingSubjects.isNotEmpty())
            "\n\nI couldn't verify the requested information for ${missingSubjects.joinToString(" and ")}." else
            "\n\nThe sources do not cover every requested detail."
        return checked.copy(text = kept.distinct().joinToString("\n\n") + tail,
            support = if (complete) AnswerSupport.CITED else AnswerSupport.PARTIAL,
            sourceExtract = added > 0, recoveryAttempted = true, recoveredClaims = added)
    }
}
