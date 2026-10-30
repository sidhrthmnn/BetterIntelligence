package com.example.grounding

/** Public lookups and changing service conditions should not depend on the
 * model admitting uncertainty. Local task/privacy/offline rules still win. */
object PublicQuestionIntent {
    fun statusPlaceholder(text: String) = Regex("(?i)no reported disruptions at any\\s*[.]").containsMatchIn(text)
    fun statusValue(text: String, q: String) = serviceStatus(q) && !statusPlaceholder(text) &&
        Regex("(?i)\\b(?:good service|no disruptions|no reported disruptions|minor delays|severe delays|part suspended|suspended|planned closure|service closed)\\b").containsMatchIn(text.substringAfter('\n', text))
    /** A multi-line network page must not lend another line's status to the
     * requested line. Match a named line in the passage's own heading/body. */
    fun lineSubjects(q: String): Set<String> = if (!serviceStatus(q)) emptySet() else
        Regex("(?i)\\b([\\p{L}\\p{N}][\\p{L}\\p{N}'-]*)\\s+(?:underground\\s+)?line\\b").findAll(q)
            .map { it.groupValues[1].lowercase() }.filter { it !in setOf("the", "this", "that", "a", "any", "which", "of", "on", "train") }.toSet()
    fun serviceScope(text: String, q: String): Boolean = lineSubjects(q).let { names ->
        names.isEmpty() || names.any { it in ResearchQuery.words(text) }
    }
    fun serviceEvidence(source: EvidenceSource, q: String): Boolean = lineSubjects(q).isEmpty() ||
        source.excerpt.split(Regex("\\n\\s*\\n")).any { block ->
            statusValue(block, q) && serviceScope(source.title + " " + source.url + " " + block, q)
        }
    fun serviceStatus(q: String) = Regex("(?i)\\b(?:line|train|tube|tfl|flight|airport|road|service|website|server)\\b.{0,55}\\b(?:status|delays?|disruptions?|running|fine|good|cancelled|open|closed|working)\\b|\\b(?:status|delays?|disruptions?)\\b.{0,55}\\b(?:line|train|tube|tfl|flight|airport|road|service|website|server)\\b").containsMatchIn(q)
    fun lookup(q: String) = !Regex("(?i)\\b(?:it|this|that|he|she|my|our|you|your|previous example)\\b").containsMatchIn(q) &&
        (Regex("(?i)^\\s*(?:who|where|when|which)\\b").containsMatchIn(q) ||
            Regex("(?i)^\\s*what\\b.{0,100}\\b(?:capital|address|opening hours|schedule|release date|cast|owner)\\b").containsMatchIn(q))
    fun externalRequest(q: String) = Regex("(?i)^\\s*(?:please\\s+)?(?:find|check|get|show|tell me)\\b.{0,120}\\b(?:online|websites?|internet|public sources|from the web)\\b").containsMatchIn(q)
    fun upcoming(q: String) = Regex("(?i)\\b(?:upcoming|scheduled)\\b").containsMatchIn(q) ||
        lookup(q) && Regex("(?i)\\bnext\\b").containsMatchIn(q)
}
