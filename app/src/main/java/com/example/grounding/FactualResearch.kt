package com.example.grounding

/** Request shape and evidence requirements, never an embedded answer database. */
object FactualResearch {
    private val casting = Regex("(?i)\\b(?:who(?:['’]s|\\s+(?:is|was|will be|has been|was announced as))?\\s+(?:(?:currently|now|actually|really)\\s+)?(?:(?:going to|set to)\\s+)?(?:play(?:s|ed|ing)?|portray(?:s|ed|ing)?|voic(?:e|es|ed|ing)|cast)|(?:which|what)\\s+(?:actor|actress)|(?:actor|actress)\\s+(?:playing|portraying)|who\\s+(?:is|was)\\s+.*\\bcast\\s+as)\\b")
    private val speculative = Regex("(?i)\\b(?:fan theories|fan theory|theory|theories|rumou?rs?|speculat(?:ion|ive)|unconfirmed|fan[- ]?casting|predictions?|might|could|allegedly|reportedly)\\b")
    fun isCasting(question: String) = casting.containsMatchIn(question)
    fun speculationRequested(question: String) = Regex("(?i)\\b(?:theories|theory|rumou?rs?|speculation|fan[- ]?casting)\\b").containsMatchIn(question)
    fun speculativeCasting(question: String, text: String) = isCasting(question) && !speculationRequested(question) && speculative.containsMatchIn(text)
    fun castingRelation(text: String) = Regex("(?i)\\b(?:play(?:s|ed|ing)?|portray(?:s|ed|ing)?|voic(?:e|es|ed|ing)|cast as|role (?:as|of)|star(?:s|ring)? as)\\b").containsMatchIn(text) ||
        Regex("\\b[A-Z][\\p{L}.'’-]+(?:\\s+[A-Z][\\p{L}.'’-]+){0,4}\\s+as\\s+[A-Z][\\p{L}.'’-]+").containsMatchIn(text)

    fun headlineOnly(text: String, title: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return true
        val words = ResearchQuery.words(clean)
        return title.isNotBlank() && words.isNotEmpty() && (words - ResearchQuery.words(title)).isEmpty()
    }
    fun usable(source: EvidenceSource, question: String): Boolean = !headlineOnly(source.excerpt, source.title) &&
        !speculativeCasting(question, source.title) && (!isCasting(question) || source.excerpt.split(Regex("\\n\\s*\\n")).any { block ->
            val body = if ('\n' in block) block.substringAfter('\n') else block
            !headlineOnly(body, source.title) && castingRelation(body) && !speculativeCasting(question, body)
        })

    fun publicLookup(question: String): Boolean {
        // Context-only requests, games and supplied personal information stay local.
        if (Regex("(?i)\\b(?:my|our|we|your|you|I)\\b|\\b(?:our game|game item|guessing game)\\b").containsMatchIn(question)) return false
        if (Regex("(?i)\\b(?:it|its|him|her|them|their|this|that)\\b").containsMatchIn(question) &&
            !question.contains("(Public source topic:") && ResearchPlanner.subjects(question).isEmpty()) return false
        return Regex("(?i)^\\s*(?:who(?:['’]s)?|what(?:['’]s)?|when|where|which|why|how|is|are|was|were|does|do|did|can|could|explain|define|describe|list|difference between)\\b").containsMatchIn(question) ||
            Regex("(?i)^\\s*(?:tell me about|give me (?:information|facts) (?:about|on))\\b").containsMatchIn(question) || isCasting(question)
    }
}
