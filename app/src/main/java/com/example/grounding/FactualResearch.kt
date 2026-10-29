package com.example.grounding

/** Request shape and evidence requirements, never an embedded answer database. */
object FactualResearch {
    fun isMovieReview(question: String) = Regex("(?i)\\b(?:reviews?|how (?:is|was) .{1,70}(?:movie|film))\\b").containsMatchIn(question)

    /** Rank the requested answer facet in actual body text, never in a headline. */
    fun answerFocusScore(text: String, question: String): Int {
        val body = text.split(Regex("\\n\\s*\\n")).joinToString(" ") { if ('\n' in it) it.substringAfter('\n') else it }
        if (isMovieReview(question)) return when {
            Regex("(?i)\\b(?:\\d(?:\\.\\d)?\\s*(?:/\\s*5|stars?)|rat(?:ed|ing)\\s*:?\\s*\\d)").containsMatchIn(body) -> 100
            Regex("(?i)\\b(?:verdict|screenplay|uneven|prais\\w*|criticis\\w*|criticiz\\w*|disappoint\\w*|rousing|compelling)\\b").containsMatchIn(body) -> 65
            else -> 0
        }
        if (NewsIntent.isLiveRequest(question) && Regex("(?i)\\belections?\\b").containsMatchIn(question)) return when {
            Regex("(?i)(?:\\d[^.!?\\n]{0,45}\\b(?:seats?|counted|percent)|\\b(?:seats?|counted|tally)\\b[^.!?\\n]{0,45}\\d)").containsMatchIn(body) -> 100
            Regex("(?i)\\b(?:no clear winner|deadlock\\w*|hung parliament|no (?:party|bloc|coalition).{0,35}majority|(?:vote|ballot) counting|coalition talks)\\b").containsMatchIn(body) -> 65
            else -> 0
        }
        return 0
    }
    fun needsAnswerFocus(question: String) = isMovieReview(question) ||
        NewsIntent.isLiveRequest(question) && Regex("(?i)\\belections?\\b").containsMatchIn(question)
    private val casting = Regex("(?i)\\b(?:who(?:['’]s|\\s+(?:is|was|will be|has been|was announced as))?\\s+(?:(?:currently|now|actually|really)\\s+)?(?:(?:going to|set to)\\s+)?(?:play(?:s|ed|ing)?|portray(?:s|ed|ing)?|voic(?:e|es|ed|ing)|cast)|(?:which|what)\\s+(?:actor|actress)|(?:actor|actress)\\s+(?:playing|portraying)|who\\s+(?:is|was)\\s+.*\\bcast\\s+as)\\b")
    private val speculative = Regex("(?i)\\b(?:fan theories|fan theory|theory|theories|rumou?rs?|speculat(?:ion|ive)|unconfirmed|fan[- ]?casting|predictions?|might|could|allegedly|reportedly)\\b")
    fun isCasting(question: String) = casting.containsMatchIn(question) ||
        Regex("(?i)\\bwho (?:is |was )?(?:acting|acts?) as\\b").containsMatchIn(question)
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
        // Context fitting can leave only a hierarchical heading when its body
        // does not fit. A longer section heading is still not article evidence.
        if ('\n' !in clean && " › " in clean) return true
        val utility = Regex("(?i)^(?:you(?: are)? logged in|loading[. …]*|you (?:do not|don't|don’t) have(?: any)?(?: active)? subscription[.!]?|save|share|listen(?:\\s*\\([^)]*\\))?|news\\s*[|›].*|(?:subscribe|sign in|log in)(?: to.*)?)$")
        val bodies = clean.split(Regex("\\n\\s*\\n")).map { block ->
            block.lines().dropWhile { it.trim() == title.trim() || it.trim().startsWith("${title.trim()} ›") }
                .map(String::trim).filter { it.isNotBlank() && !utility.matches(it) }.joinToString(" ")
        }.filter(String::isNotBlank)
        if (bodies.isEmpty()) return true
        val words = ResearchQuery.words(clean)
        return title.isNotBlank() && words.isNotEmpty() && (words - ResearchQuery.words(title)).isEmpty()
    }
    fun usable(source: EvidenceSource, question: String): Boolean = !headlineOnly(source.excerpt, source.title) &&
        (capitalPlace(question) == null || !Regex("(?i)\\bcapital punishment\\b").containsMatchIn(source.title)) &&
        !speculativeCasting(question, source.title) && (!isCasting(question) || source.excerpt.split(Regex("\\n\\s*\\n")).any { block ->
            val body = if ('\n' in block) block.substringAfter('\n') else block
            !headlineOnly(body, source.title) && castingRelation(body) && !speculativeCasting(question, body)
        })

    /** Compatibility helper; question words alone are never a research trigger. */
    fun publicLookup(question: String): Boolean = AnswerRoutingPolicy.decide(question).path != AnswerPath.LOCAL
}
