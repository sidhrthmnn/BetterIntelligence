package com.example.grounding

/** Request shape and evidence requirements, never an embedded answer database. */
object FactualResearch {
    private val casting = Regex("(?i)\\b(?:who(?:['’]s|\\s+(?:is|was|will be|has been|was announced as))?\\s+(?:(?:currently|now|actually|really)\\s+)?(?:(?:going to|set to)\\s+)?(?:play(?:s|ed|ing)?|portray(?:s|ed|ing)?|voic(?:e|es|ed|ing)|cast)|(?:which|what)\\s+(?:actor|actress)|(?:actor|actress)\\s+(?:playing|portraying)|who\\s+(?:is|was)\\s+.*\\bcast\\s+as)\\b")
    private val speculative = Regex("(?i)\\b(?:fan theories|fan theory|theory|theories|rumou?rs?|speculat(?:ion|ive)|unconfirmed|fan[- ]?casting|predictions?|might|could|allegedly|reportedly)\\b")
    fun isCasting(question: String) = casting.containsMatchIn(question)
    fun speculationRequested(question: String) = Regex("(?i)\\b(?:theories|theory|rumou?rs?|speculation|fan[- ]?casting)\\b").containsMatchIn(question)
    fun speculativeCasting(question: String, text: String) = isCasting(question) && !speculationRequested(question) && speculative.containsMatchIn(text)
    fun definitionTopic(question: String): String? {
        val topic = Regex("(?i)^\\s*(?:what(?:\\s+(?:is|are)|['’]s)|define)\\s+(?:(?:a|an|the)\\s+)?([\\p{L}\\p{N}][\\p{L}\\p{N} /.'’-]{1,70})[?!.]*\\s*$")
            .matchEntire(question)?.groupValues?.get(1)?.trim(' ', '.', '?', '!') ?: return null
        if (Regex("(?i)\\b(?:of|in|for|from|my|our|your|it|this|that|there|today|tonight|now|current|latest|best|better)\\b|['’]s\\b").containsMatchIn(topic)) return null
        if (!question.trimStart().startsWith("define ", true) && Regex("(?i)\\b(?:weather|temperature|forecast|capital|price|cost|population|date|version)\\b").containsMatchIn(topic)) return null
        return topic
    }
    fun definitionLine(text: String, question: String) = definitionQuality(text, question) > 0
    fun definitionQuality(text: String, question: String): Int {
        val topic = definitionTopic(question) ?: return 0
        val plain = text.replace(Regex("[*`#>]"), "")
        if (Regex("(?i)(?:^|\\n+)(?:to summari[sz]e|in summary)[,:]?\\s+${Regex.escape(topic)}\\s+(?:is|are|uses?|converts?|transforms?|takes?|means|refers to|describes|consists|involves)\\b").containsMatchIn(plain)) return 100
        val definition = Regex("(?i)(?:^|[.!?]\\s+|\\n+)${Regex.escape(topic)}(?:\\s*\\([^)!?\\n]{0,120}\\))?(?:\\s*\\[\\d+(?:[,– -]\\d+)*])*\\s+(?:is|are|means|refers to)\\s+([^.!?\\n]+)")
            .find(plain)?.groupValues?.get(1)
        if (definition != null) return if (Regex("(?i)^(?:(?:really|very|especially)\\s+)?(?:important|essential|vital|critical|crucial|common|popular|widespread|responsible)\\b").containsMatchIn(definition)) 0 else 85
        val namedProcess = Regex("(?i)\\b(?:this|the) (?:process|method|reaction|phenomenon|technique) (?:is |are )?(?:called|known as|termed|named) ${Regex.escape(topic)}\\b").find(plain) ?: return 0
        val explanation = plain.substring(0, namedProcess.range.first).trimEnd()
        return if (explanation.lastOrNull() in listOf('.', '!') && explanation.split(Regex("\\s+")).size >= 8) 65 else 0
    }
    fun capitalPlace(question: String): String? = Regex("(?i)^\\s*what(?:['’]s|\\s+is)\\s+(?:the\\s+)?capital(?:\\s+city)?\\s+of\\s+([\\p{L}][\\p{L} .'-]{1,60})[?!.]*\\s*$")
        .matchEntire(question)?.groupValues?.get(1)?.trim(' ', '.', '?', '!')
    fun incomplete(text: String) = Regex("(?i)\\b(?:because|such as|as follows|the following)\\s*:?$|:\\s*$").containsMatchIn(text.trim())
    fun castingRelation(text: String) = Regex("(?i)\\b(?:play(?:s|ed|ing)?|portray(?:s|ed|ing)?|voic(?:e|es|ed|ing)|cast as|role (?:as|of)|star(?:s|ring)? as)\\b").containsMatchIn(text) ||
        Regex("\\b[A-Z][\\p{L}.'’-]+(?:\\s+[A-Z][\\p{L}.'’-]+){0,4}\\s+as\\s+[A-Z][\\p{L}.'’-]+").containsMatchIn(text)

    fun headlineOnly(text: String, title: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return true
        val words = ResearchQuery.words(clean)
        return title.isNotBlank() && words.isNotEmpty() && (words - ResearchQuery.words(title)).isEmpty()
    }
    fun usable(source: EvidenceSource, question: String): Boolean = !headlineOnly(source.excerpt, source.title) &&
        (capitalPlace(question) == null || !Regex("(?i)\\bcapital punishment\\b").containsMatchIn(source.title)) &&
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
