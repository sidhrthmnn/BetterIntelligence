package com.example.grounding

/** Public lookups and changing service conditions should not depend on the
 * model admitting uncertainty. Local task/privacy/offline rules still win. */
object PublicQuestionIntent {
    fun serviceStatus(q: String) = Regex("(?i)\\b(?:line|train|tube|tfl|flight|airport|road|service|website|server)\\b.{0,55}\\b(?:status|delays?|disruptions?|running|fine|good|cancelled|open|closed|working)\\b|\\b(?:status|delays?|disruptions?)\\b.{0,55}\\b(?:line|train|tube|tfl|flight|airport|road|service|website|server)\\b").containsMatchIn(q)
    fun lookup(q: String) = !Regex("(?i)\\b(?:it|this|that|he|she|my|our|you|your|previous example)\\b").containsMatchIn(q) &&
        (Regex("(?i)^\\s*(?:who|where|when|which)\\b").containsMatchIn(q) ||
            Regex("(?i)^\\s*what\\b.{0,100}\\b(?:capital|address|opening hours|schedule|release date|cast|owner)\\b").containsMatchIn(q))
    fun externalRequest(q: String) = Regex("(?i)^\\s*(?:please\\s+)?(?:find|check|get|show|tell me)\\b.{0,120}\\b(?:online|websites?|internet|public sources|from the web)\\b").containsMatchIn(q)
    fun upcoming(q: String) = Regex("(?i)\\b(?:upcoming|scheduled)\\b").containsMatchIn(q) ||
        lookup(q) && Regex("(?i)\\bnext\\b").containsMatchIn(q)
}
