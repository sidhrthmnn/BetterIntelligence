package com.example.grounding

import java.util.Locale

/** Implicit current-event questions, without turning conceptual explanations into news. */
object NewsIntent {
    private val countries by lazy {
        (Locale.getISOCountries().map { Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH) } +
            listOf("UK", "USA", "UAE", "Gaza", "Palestine", "Britain", "United States"))
            .filter { it.isNotBlank() }.joinToString("|", "(?i)\\b(?:", ")\\b") { Regex.escape(it) }.toRegex()
    }
    fun isLiveRequest(question: String): Boolean {
        val direct = Regex("(?i)^\\s*what(?:['’]s|s| is)\\s+(?:happening|going on)\\b").containsMatchIn(question)
        val unfolding = Regex("(?i)\\b(?:how (?:is|are|was).{0,55}(?:elections?|vote|war|conflict|talks)|(?:elections?|vote|war|conflict|talks).{0,40}(?:going|ongoing|progress|results|updates|status))\\b").containsMatchIn(question) &&
            !Regex("(?i)\\b(?:history|historical|how (?:does|do)|what (?:is|are) an? |19\\d{2}|20[01]\\d)\\b").containsMatchIn(question)
        return direct && countries.containsMatchIn(question) || unfolding ||
            Regex("(?i)^\\s*what(?:['’]s|s| is) new (?:in|with|for)\\b").containsMatchIn(question)
    }
    fun isNewsSearch(question: String) = isLiveRequest(question) ||
        Regex("(?i)\\b(?:news|headlines)\\b|വാർത്ത").containsMatchIn(question)

    /** The request's framing is not a fact that an article must repeat. */
    fun topicQuestion(question: String): String {
        if (!isLiveRequest(question)) return question
        val plain = question.replace(Regex("(?i)^\\s*what(?:['’]s|s| is)\\s+(?:(?:happening|going on)\\s+(?:in|with|to)|new\\s+(?:in|with|for))\\s+"), "")
        return if (Regex("(?i)^\\s*how (?:is|are)\\b").containsMatchIn(plain)) plain
            .replace(Regex("(?i)^\\s*how (?:is|are)\\s+(?:the\\s+)?"), "")
            .replace(Regex("(?i)\\b(?:going(?:\\s+on)?|ongoing|progressing|unfolding)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim() else plain
    }
}
