package com.example.grounding

import com.example.data.ChatMessageEntity

/** Only a public source title from this conversation can expand a follow-up.
 * Stored private user/assistant messages are never copied into a search query.
 */
object ConversationalResearch {
    fun validPublicPlace(place: String) = Regex("^[\\p{L}][\\p{L} ,.'()-]{1,69}$").matches(place) &&
        !GroundingPolicy.containsCredential(place) && !Regex("(?i)ignore|instructions|system prompt").containsMatchIn(place)
    /** Bind only a place in this public request that the actual pages mention.
     * This survives generic page titles without copying private chat history. */
    fun bindPublicPlace(question: String, sources: List<EvidenceSource>): List<EvidenceSource> {
        val plain = question.substringBefore("(Public source topic:")
        val place = (if (isWeather(plain)) weatherCity(plain) else
            Regex("(?i)\\b(?:mayor|governor)\\s+(?:in|of|for)\\s+([\\p{L}][\\p{L} ,.'-]{1,69})")
                .find(plain)?.groupValues?.get(1)?.trim(' ', '.', ',', '?'))?.takeIf(::validPublicPlace) ?: return sources
        val required = ResearchQuery.words(place)
        return sources.map { source ->
            val mentioned = ResearchQuery.words(source.title + " " + source.excerpt + " " + source.url)
            if (required.all { it in mentioned }) source.copy(publicPlace = place) else source
        }
    }
    private val followUpPattern = Regex(
        "(?i)\\b(he|him|his|she|her|they|them|their|there|that|those|this|it|its|second|third|first|driver|winner|current|incumbent|now|present|still|again|sure|wrong|incorrect|right|really|verify|recheck)\\b|" +
            "^\\s*(?:please\\s+)?(?:check|search|verify|try|look)\\b|അത്|അവൻ|അവർ|അവയുടെ|അതിന്റെ"
    )
    private val recheckPattern = Regex(
        "(?i)^\\s*(?:please\\s+)?(?:check|search|verify|try|look|are\\s+you\\s+sure|that'?s\\s+wrong|wrong(?:\\s+info)?)(?:\\s+(?:it|that|this|again|now|once\\s+more|properly|carefully|the\\s+web|online))*\\s*[.?!]*$"
    )
    fun isRecheck(question: String): Boolean = recheckPattern.matches(question.trim())
    fun needsSubject(question: String): Boolean = Regex("(?i)^\\s*(?:who (?:owns|developed|made)|whose is|what is the owner of)\\s+(?:it|this|that)\\s*[?!.]*$").matches(question)
    const val askSubject = "Which product, organisation or item do you mean? Please name it so I can check the right subject."
    const val askPlace = "Which city or place do you mean? I don't have access to your location."
    const val askNewsTopic = "Which topic, country or event would you like news about?"
    fun needsNewsTopic(question: String) = Regex("(?i)^\\s*(?:(?:please |give me |show me |tell me |the |latest |some |a |an )*)(?:news|headlines)(?: updates?| today| now)?[?!.]*\\s*$").matches(question)
    fun needsPlace(question: String) = Regex("(?i)\\bthere\\b").containsMatchIn(question) &&
        Regex("(?i)\\b(mayor|governor)\\b").containsMatchIn(question)
    fun question(question: String, history: List<ChatMessageEntity>): String {
        if (history.lastOrNull()?.content == askNewsTopic && Regex("^[\\p{L}\\p{N} ,.'-]{2,100}$").matches(question)) return "Latest news about $question"
        if (history.lastOrNull()?.content == askPlace && Regex("^[\\p{L} ,.'-]{2,70}$").matches(question)) {
            val role = Regex("(?i)\\b(mayor|governor)\\b").find(history.lastOrNull { it.role == "user" }?.content.orEmpty())?.value
            if (role != null) return "Who is the $role in $question?"
        }
        if (!followUpPattern.containsMatchIn(question) && !Regex("(?i)^\\s*(?:what|how) about\\b").containsMatchIn(question)) return question
        val conversation = history.lastOrNull()?.conversationId
        val sources = history.filter { it.conversationId == conversation }.takeLast(4).lastOrNull { it.role == "assistant" && it.sourcesJson != "[]" }
            ?.let { GroundingResult.sources(it.sourcesJson) }.orEmpty()
        if (sources.isEmpty()) return question
        // Resolve a public location before planning a different question about it.
        // Do not send private chat text, or the old weather intent, to discovery.
        if (Regex("(?i)\\bthere\\b").containsMatchIn(question)) {
            val places = sources.mapNotNull { source ->
                source.publicPlace.takeIf(::validPublicPlace) ?: source.excerpt.lines().filter { '›' in it }
                    .flatMap { it.split('›') }.firstNotNullOfOrNull { section ->
                        Regex("(?i)^(?:the )?(?:mayor|governor) of ([\\p{L}][\\p{L} .'-]{1,69}?)(?:,.*)?$")
                            .matchEntire(section.trim())?.groupValues?.get(1)?.trim()
                    } ?: Regex("(?i)^(?:weather|temperature|forecast)\\s+(?:in|for|at)\\s+(.{2,70}?)(?=\\s+(?:in|for)\\s+(?:20\\d{2}|January|February|March|April|May|June|July|August|September|October|November|December)|\\s*[-–—|]|$)")
                    .find(source.title)?.groupValues?.get(1)?.trim()
                    ?: Regex("(?i)^(.{2,70}?)\\s*(?:[-–—|]\\s*(?:BBC\\s+)?(?:weather|forecast)\\b|\\s+(?:weather|forecast)\\b)")
                    .find(source.title)?.groupValues?.get(1)?.trim()
                    ?: Regex("(?i)^(?:the )?(?:mayor|governor) of ([\\p{L}][\\p{L} .'-]{1,60}?)(?=\\s*[-–—|,]|$)")
                        .find(source.title)?.groupValues?.get(1)?.trim()
            }.filter { place -> place.any(Char::isLetter) && !GroundingPolicy.containsCredential(place) &&
                !Regex("(?i)ignore|instructions|https?://|[\\p{Cc}]").containsMatchIn(place) }
            val place = places.minByOrNull { ResearchQuery.words(it).size }?.takeIf { first -> places.all { other ->
                val a = ResearchQuery.words(first); val b = ResearchQuery.words(other)
                a.containsAll(b) || b.containsAll(a)
            } }
            if (place != null) return question.replace(Regex("(?i)\\b(?:in\\s+)?there\\b"), "in $place")
        }
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
    fun isWeather(question: String) = Regex("(?i)\\b(weather|temperature|forecast)\\b")
        .containsMatchIn(question.substringBefore("(Public source topic:"))
    fun weatherCity(question: String): String? = Regex("(?i)\\b(?:in|for|at)\\s+([\\p{L}][\\p{L} ,.'-]{1,70})").find(question)
        ?.groupValues?.get(1)?.replace(Regex("(?i)\\b(today|now|currently|please)\\b.*"), "")?.trim(' ', '.', '?', ',')?.takeIf { it.length > 1 }
    const val askCity = "Which city and country should I check the weather for? I don't have access to your location."
}
