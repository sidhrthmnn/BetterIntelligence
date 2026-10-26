package com.example.grounding

import com.example.data.ChatMessageEntity

/** Only a public source title from this conversation can expand a follow-up.
 * Stored private user/assistant messages are never copied into a search query.
 */
object ConversationalResearch {
    private val followUpPattern = Regex(
        "(?i)\\b(he|him|his|she|her|they|them|their|that|those|this|it|its|second|third|first|driver|winner|current|incumbent|now|present|still|again|sure|wrong|incorrect|right|really|verify|recheck)\\b|" +
            "^\\s*(?:please\\s+)?(?:check|search|verify|try|look)\\b|അത്|അവൻ|അവർ|അവയുടെ|അതിന്റെ"
    )
    private val recheckPattern = Regex(
        "(?i)^\\s*(?:please\\s+)?(?:check|search|verify|try|look|are\\s+you\\s+sure|that'?s\\s+wrong|wrong(?:\\s+info)?)(?:\\s+(?:it|that|this|again|now|once\\s+more|properly|carefully|the\\s+web|online))*\\s*[.?!]*$"
    )
    fun isRecheck(question: String): Boolean = recheckPattern.matches(question.trim())
    fun needsSubject(question: String): Boolean = Regex("(?i)^\\s*(?:who (?:owns|developed|made)|whose is|what is the owner of)\\s+(?:it|this|that)\\s*[?!.]*$").matches(question)
    const val askSubject = "Which product, organisation or item do you mean? Please name it so I can check the right subject."
    fun question(question: String, history: List<ChatMessageEntity>): String {
        if (!followUpPattern.containsMatchIn(question) && !Regex("(?i)^\\s*(?:what|how) about\\b").containsMatchIn(question)) return question
        val conversation = history.lastOrNull()?.conversationId
        val sources = history.filter { it.conversationId == conversation }.takeLast(4).lastOrNull { it.role == "assistant" && it.sourcesJson != "[]" }
            ?.let { GroundingResult.sources(it.sourcesJson) }.orEmpty()
        if (sources.isEmpty()) return question
        // Source titles themselves are untrusted: no secrets/URLs/control text
        // from a publisher is allowed to become a follow-up search query.
        val selected = if (Regex("(?i)compare|other one|memory usage|GPU").containsMatchIn(question)) sources.take(2) else sources.take(1)
        val rawTitle = selected.joinToString(" / ") { it.title.replace(Regex("https?://\\S+|[\\p{Cc}]"), " ").take(80) }.take(165)
        if (GroundingPolicy.containsCredential(rawTitle) || Regex("(?i)ignore.{0,20}instructions|system prompt|api[_ -]?key").containsMatchIn(rawTitle)) return question
        val cleanTitle = rawTitle
            .replace(Regex("\\(\\s*(?:18|19|20)\\d{2}\\s*[-–—]\\s*(?:(?:18|19|20)\\d{2}|present|incumbent|now)\\s*\\)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(?i)^\\s*list\\s+of\\s+(?:all\\s+)?"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { rawTitle.trim() }
        return if (isRecheck(question)) {
            "Current $cleanTitle (Public source topic: $cleanTitle)"
        } else {
            "$question (Public source topic: $cleanTitle)"
        }
    }
    fun isWeather(question: String) = Regex("(?i)\\b(weather|temperature|forecast)\\b").containsMatchIn(question)
    fun weatherCity(question: String): String? = Regex("(?i)\\b(?:in|for|at)\\s+([\\p{L}][\\p{L} ,.'-]{1,70})").find(question)
        ?.groupValues?.get(1)?.replace(Regex("(?i)\\b(today|now|currently|please)\\b.*"), "")?.trim(' ', '.', '?', ',')?.takeIf { it.length > 1 }
    const val askCity = "Which city and country should I check the weather for? I don't have access to your location."
}
