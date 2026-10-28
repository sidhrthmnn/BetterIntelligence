package com.example.grounding

enum class AnswerSupport { CITED, PARTIAL, INSUFFICIENT }
data class ClaimCheck(val text: String, val sourceIds: Set<Int>, val supported: Boolean, val reason: String)
data class CheckedAnswer(val text: String, val support: AnswerSupport, val claims: List<ClaimCheck>, val sourceExtract: Boolean = false)

/** Conservative, deterministic evidence checks, NOT general semantic entailment
 * or a guarantee that a publisher is correct. Each surviving claim must match
 * one cited passage; values cannot be pooled across unrelated sources/subjects. */
object ClaimVerification {
    private val marker = Regex("\\[(\\d+(?:,\\s*\\d+)*)]")
    private val url = Regex("(?i)https?://[^\\s)>]+")
    private val negative = Regex("(?i)\\b(?:not|no|never|cannot|can't|doesn't|don't|unavailable|unsupported|without|neither)\\b")
    private val comparator = Regex("(?i)\\b(?:faster|slower|better|worse|higher|lower|more|less)\\s+(?:\\w+\\s+){0,2}than\\b")
    private val ignored = ResearchQuery.words("a an the is are was were be been being it its this that these those they their for of on in with and or to from by as at can could does do has have use uses used require requires required support supports supported source sources according also only about both however therefore information available unavailable not no cannot which who what when where how than")
    private val uncertainty = Regex("(?i)^(?:I (?:cannot|can't|couldn't)|(?:The )?(?:sources?|evidence) (?:do not|does not|don't|doesn't)|There (?:is|are) (?:not enough|insufficient))")
    private fun terms(text: String): Set<String> = ResearchQuery.words(marker.replace(text, "")) - ignored
    private fun prose(text: String) = text.replace(Regex("(?m)^\\s*(?:[-*]|\\d{1,2}[.)])\\s+"), "").trim()
    fun sentences(text: String): List<String> = text
        .replace(Regex("(?m)^\\s*\\d{1,2}[.)]\\s+"), "")
        .replace(Regex("([.!?])\\s+(\\[\\d+(?:,\\s*\\d+)*])"), "$2$1")
        .split(Regex("\\n+|(?<=])(?:\\s*;\\s*|,?\\s+(?:and|but|whereas)\\s+)|(?<=[.!?])\\s+(?=[\\p{L}\\p{N}*\\[])"))
        .map(String::trim).filter(String::isNotBlank).take(40)

    fun check(text: String, sources: List<EvidenceSource>, question: String = ""): CheckedAnswer {
        val byId = sources.associateBy { it.id }
        val entities = ResearchPlanner.entities(question)
        val plan = ResearchPlanner.plan(question)
        val contextOnly = sources.filter { EvidenceRanking.contextOnly(it, plan) }.map { it.id }.toSet()
        val comparison = ResearchIntent.COMPARISON in plan.intents && entities.size >= 2
        var sectionSubject: String? = null
        val claims = sentences(text.replace("\u0000", "")).map { line ->
            val ids = marker.findAll(line).flatMap { it.groupValues[1].split(',').asSequence().map(String::trim).mapNotNull(String::toIntOrNull) }.toSet()
            val plain = prose(marker.replace(line, ""))
            val isHeading = line.startsWith('#') || plain.endsWith(':') || Regex("^\\*\\*[^*\\n]{1,120}:\\*\\*$").matches(plain)
            if (isHeading) {
                val headingWords = ResearchQuery.words(plain)
                // Inherit only an exact requested subject, never a model's
                // extra qualifier, assertion or ambiguous multi-product label.
                sectionSubject = if (comparison) entities.singleOrNull { ResearchQuery.words(it) == headingWords } else null
            }
            val missingSubject = comparison && entities.none { entity -> ResearchQuery.words(entity).all { it in ResearchQuery.words(plain) } }
            val inherited = !isHeading && missingSubject && sectionSubject != null
            val body = if (inherited) "$sectionSubject — $plain" else plain
            val rendered = if (inherited) "$sectionSubject — ${prose(line)}" else line
            val reason = when {
                body.none { it.isLetterOrDigit() } -> "Empty claim"
                isHeading -> "Heading"
                uncertainty.containsMatchIn(body) -> "Explicit uncertainty"
                ids.isEmpty() -> "Missing citation"
                ids.any { it !in byId } -> "Unknown citation"
                ResearchQuery.isCurrentIncumbentQuery(question) && Regex("(?i)\\b(?:former|previous|deceased|died|passed away|was\\s+(?:the\\s+)?(?:\\d+(?:st|nd|rd|th)\\s+)?(?:chief minister|prime minister|president|governor|mayor|ceo))\\b").containsMatchIn(body) -> "Historical role does not answer the current office-holder question"
                comparison && missingSubject && !inherited -> "Comparison subject missing"
                Regex("(?i)\\b(?:the following|as follows|listed below|two parts)\\b").containsMatchIn(body) && !body.substringAfter(':', "").any { it.isLetterOrDigit() } -> "Incomplete referential claim"
                Regex("(?i)\\bI (?:have |just )?(?:accessed|opened|downloaded|sent|installed|contacted|browsed)\\b").containsMatchIn(body) -> "Unperformed tool action"
                url.findAll(line).any { match -> sources.none { it.id in ids && it.url == match.value.trimEnd('.', ',') } } -> "Unprovided URL"
                ids.mapNotNull(byId::get).none { source ->
                    val found = ResearchQuery.words(source.title + " " + source.excerpt) + EvidenceRanking.localeWords(source.url)
                    val requestedSubject = comparison || ResearchPlanner.subjects(question).all { entity -> ResearchQuery.words(entity).all { it in found } }
                    requestedSubject && supports(body, source, entities, ResearchQuery.isCurrentIncumbentQuery(question), source.id in contextOnly)
                } -> "Passage does not substantiate subject, wording, polarity or values"
                else -> "Passage checks passed"
            }
            ClaimCheck(rendered, ids, reason in setOf("Passage checks passed", "Explicit uncertainty", "Heading"), reason)
        }
        val factual = claims.filter { it.reason !in setOf("Heading", "Explicit uncertainty") }
        val accepted = factual.filter { it.supported }
        if (accepted.isEmpty()) return CheckedAnswer(GroundingPolicy.unavailableAnswer, AnswerSupport.INSUFFICIENT, claims)
        val rejected = claims.any { !it.supported }
        // Keep self-contained supported sentences; a pronoun-only survivor could
        // refer to the removed claim and reverse its meaning. Omit it as well.
        val kept = claims.filter { it.supported && it.reason != "Heading" &&
            (!rejected || !Regex("(?i)^(?:it|they|this|that|therefore|thus|however)\\b").containsMatchIn(prose(it.text))) }
        if (kept.none { it.reason == "Passage checks passed" })
            return CheckedAnswer(GroundingPolicy.unavailableAnswer, AnswerSupport.INSUFFICIENT, claims)
        val answer = kept.joinToString("\n\n") { it.text }
        return CheckedAnswer(if (rejected) "$answer\n\nSome details were omitted because the supplied evidence did not support them." else answer,
            if (rejected) AnswerSupport.PARTIAL else AnswerSupport.CITED, claims)
    }

    private fun supports(claim: String, source: EvidenceSource, entities: List<String>, currentOffice: Boolean, contextOnly: Boolean): Boolean {
        if (!CitationSupport.supportsNumbers("$claim [${source.id}]", listOf(source))) return false
        val claimWords = ResearchQuery.words(claim)
        val candidates = entities.ifEmpty { ResearchPlanner.entities(claim) }
        val subjects = candidates.filter { entity -> ResearchQuery.words(entity).all { it in claimWords } }.sortedBy { claim.indexOf(it, ignoreCase = true) }
        val units = quantities(claim)
        val passages = source.excerpt.split(Regex("\\n\\s*\\n")).flatMap { block ->
            val lines = block.lines()
            val exampleContext = financeExample(block)
            val heading = lines.firstOrNull()?.takeIf { lines.size > 1 && it.length < 150 && !it.endsWith('.') &&
                entities.count { entity -> ResearchQuery.words(entity).all { word -> word in ResearchQuery.words(it) } } <= 1 }
            sentences(if (heading != null) lines.drop(1).joinToString(" ") else block).flatMap { sentence ->
                val subject = Regex("^([\\p{L}][\\p{L}\\p{N} ._-]{0,45}?)\\s+(?i:does|is|has|supports?|requires?)\\b").find(sentence)?.groupValues?.get(1).orEmpty()
                sentence.split(Regex("(?i)\\s+(?:but|whereas|although)\\s+|;\\s*")).mapIndexed { index, clause ->
                    (heading?.plus("\n") ?: "") + (if (exampleContext) "Representative example\n" else "") +
                        (if (index > 0 && subject.isNotEmpty()) "$subject " else "") + clause
                }
            }
        }.filter { passage ->
            val scoped = ResearchQuery.words(passage + " " + source.title) + EvidenceRanking.localeWords(source.url)
            subjects.all { entity -> ResearchQuery.words(entity).all { it in scoped } }
        }
        return passages.any { passage ->
            val evidence = terms(passage) + EvidenceRanking.localeWords(source.url)
            val requested = terms(claim)
            val lexical = requested.isNotEmpty() && requested.count { it in evidence }.toDouble() / requested.size >= .65
            val scopedWords = ResearchQuery.words(passage) + EvidenceRanking.localeWords(source.url)
            val named = ResearchPlanner.entities(claim).all { entity -> ResearchQuery.words(entity).all { it in scopedWords } }
            val namesPresent = subjects.all { entity ->
                // A multi-product page title must not bind a number from another
                // product's row. Require subjects in the actual passage.
                ResearchQuery.words(entity).all { it in scopedWords }
            }
            val polarity = negative.containsMatchIn(claim) == negative.containsMatchIn(passage)
            val quantitiesMatch = quantities(passage).containsAll(units)
            val numbersMatch = CitationSupport.supportsNumbers("$claim [${source.id}]", listOf(source.copy(title = "", excerpt = passage)))
            val qualified = Regex("(?i)\\b(?:only (?:when|with|on|for|if)|provided that|except|unless|on the tested device)\\b").containsMatchIn(passage)
            val qualifications = !qualified || Regex("(?i)\\b(?:only|when|with|provided|except|unless|tested device)\\b").containsMatchIn(claim)
            val illustrative = exampleContextInPassage(passage, source.excerpt)
            val amountPattern = Regex("(?i)purchase amount\\s*[:|]?\\s*([£$€₹]\\s*\\d[\\d,.]*)")
            val exampleAmount = amountPattern.find(passage)?.groupValues?.get(1) ?: amountPattern.findAll(source.excerpt)
                .map { it.groupValues[1] }.distinct().toList().singleOrNull()
            val exampleMatches = !illustrative || Regex("(?i)\\b(?:example|illustrative|representative)\\b").containsMatchIn(claim) &&
                exampleAmount != null && CitationSupport.supportsNumbers("$exampleAmount [${source.id}]", listOf(source.copy(title = "", excerpt = claim)))
            val universal = !Regex("(?i)\\b(?:always|every|all devices|any device|guaranteed)\\b").containsMatchIn(claim) ||
                Regex("(?i)\\b(?:always|every|all devices|any device|guaranteed)\\b").containsMatchIn(passage)
            val incumbent = !currentOffice || !Regex("(?i)\\b(?:former|deceased|died|passed away|was the)\\b").containsMatchIn(passage)
            val execution = Regex("(?i)\\b(?:on|using|with)\\s+(CPU|GPU)\\b|\\b(CPU|GPU)\\s+(?:backend|inference|prefill|decode|mode)\\b|\\b(CPU|GPU)\\s*—").find(claim)
                ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            val statedBackend = Regex("(?i)\\bBackend:\\s*(CPU|GPU)\\b").find(passage)?.groupValues?.get(1)
            val backendMatches = execution == null || statedBackend == null || execution.equals(statedBackend, ignoreCase = true)
            val recency = !contextOnly || !Regex("(?i)\\b(?:recent(?:ly)?|latest|newly|current|today|this (?:week|month))\\b").containsMatchIn(claim)
            val datedAction = if (!Regex("\\d{4}|\\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\\b", RegexOption.IGNORE_CASE).containsMatchIn(claim)) true else
                listOf("publish|publication", "updat|modif", "releas|launch", "announc", "sign", "became|appoint|sworn").all { group ->
                    val predicate = Regex("(?i)\\b(?:$group)\\w*")
                    !predicate.containsMatchIn(claim) || predicate.containsMatchIn(passage)
                }
            val comparison = comparator.find(claim)?.let { relation ->
                val normalized = relation.value.lowercase()
                val sameRelation = passage.lowercase().contains(normalized)
                val order = subjects.size < 2 || subjects.map { passage.indexOf(it, ignoreCase = true) }.let { it == it.sorted() }
                sameRelation && order
            } ?: true
            lexical && named && namesPresent && polarity && quantitiesMatch && numbersMatch && comparison && qualifications && exampleMatches && universal && incumbent && recency && datedAction && backendMatches
        }
    }
    internal fun financeExample(text: String) = Regex("(?i)representative example|illustrative examples?|example purchase").containsMatchIn(text) &&
        Regex("(?i)purchase amount|\\b(?:APR|interest|monthly payment|finance|financing|loan|repayment)\\b").containsMatchIn(text)
    private fun exampleContextInPassage(passage: String, source: String) = financeExample(passage) ||
        passage.contains("Representative example\n") && financeExample(source)
    private fun quantities(text: String): Set<String> = Regex("(?i)(-?\\d+(?:\\.\\d+)?)\\s*(GB|GiB|MB|MiB|KB|ms|milliseconds?|seconds?|tk/s|tokens/(?:s|second)|%|°\\s*[CF])(?!\\p{L})")
        .findAll(text).map { match ->
            val value = runCatching { java.math.BigDecimal(match.groupValues[1]).stripTrailingZeros().toPlainString() }.getOrDefault(match.groupValues[1])
            val unit = match.groupValues[2].lowercase().replace("milliseconds", "ms").replace("millisecond", "ms").replace("tk/s", "tokens/s").replace(" ", "")
            "$value:$unit"
        }.toSet()
}
