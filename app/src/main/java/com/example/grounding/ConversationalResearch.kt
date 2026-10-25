package com.example.grounding

import com.example.data.ChatMessageEntity

/** Only a public source title from this conversation can expand a follow-up.
 * Stored private user/assistant messages are never copied into a search query.
 */
object ConversationalResearch {
    fun question(question: String, history: List<ChatMessageEntity>): String {
        if (!Regex("(?i)\\b(he|him|she|they|their|that|those|it|second|driver|winner)\\b|അത്|അവൻ|അവർ|അവയുടെ|അതിന്റെ").containsMatchIn(question)) return question
        val sources = history.takeLast(4).lastOrNull { it.role == "assistant" && it.sourcesJson != "[]" }
            ?.let { GroundingResult.sources(it.sourcesJson) }.orEmpty()
        val source = sources.firstOrNull() ?: return question
        // Source titles themselves are untrusted: no secrets/URLs/control text
        // from a publisher is allowed to become a follow-up search query.
        val title = source.title.replace(Regex("https?://\\S+|[\\p{Cc}]"), " ").take(100)
        if (GroundingPolicy.containsCredential(title) || Regex("(?i)ignore.{0,20}instructions|system prompt|api[_ -]?key").containsMatchIn(title)) return question
        return "$question (Public source topic: $title)"
    }
    fun isWeather(question: String) = Regex("(?i)\\b(weather|temperature|forecast)\\b").containsMatchIn(question)
    fun weatherCity(question: String): String? = Regex("(?i)\\b(?:in|for|at)\\s+([\\p{L}][\\p{L} ,.'-]{1,70})").find(question)
        ?.groupValues?.get(1)?.replace(Regex("(?i)\\b(today|now|currently|please)\\b.*"), "")?.trim(' ', '.', '?', ',')?.takeIf { it.length > 1 }
    const val askCity = "Which city and country should I check the weather for? I don't have access to your location."
}
