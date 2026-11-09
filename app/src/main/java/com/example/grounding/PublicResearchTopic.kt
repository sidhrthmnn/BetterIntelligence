package com.example.grounding

import com.example.data.ChatMessageEntity
import java.text.Normalizer

enum class PublicEntityType { FILM, PRODUCT }
enum class PublicTopicIntent { REVIEW, RATING, CAST, RELEASE, SEQUEL, INFORMATION }

/** Derived from bounded USER turns in one chat, never assistant text or sources.
 * The original message remains in Room; this contains only a public identity. */
data class PublicResearchTopic(val subject: String, val type: PublicEntityType, val language: String = "",
    val intent: PublicTopicIntent, val conversationId: Long = 0, val userMessageId: Long = 0, val releaseChannel: String = "", val currentCasting: Boolean = false) {
    val identity get() = listOf(subject, language, if (type == PublicEntityType.FILM) "film" else "").filter(String::isNotBlank).joinToString(" ")
    fun question() = when (intent) {
        PublicTopicIntent.REVIEW -> "$identity reviews audience reception"
        PublicTopicIntent.RATING -> "What is the rating of $identity?"
        PublicTopicIntent.CAST -> if (currentCasting) "Who is being cast in $identity?" else "Who starred in $identity?"
        PublicTopicIntent.RELEASE -> if (releaseChannel == "OTT") "When is $identity releasing on streaming services (OTT)?" else "When was $identity released?"
        PublicTopicIntent.SEQUEL -> "Is there a sequel to $identity?"
        PublicTopicIntent.INFORMATION -> "Information about $identity"
    }
    fun subjectMatches(text: String): Boolean = ExactIdentity.accepts(subject, text) && ResearchQuery.words(subject).let { it.isNotEmpty() && ResearchQuery.words(text).containsAll(it) }
    fun sourceMatches(title: String, body: String, url: String, knownLanguage: String = ""): Boolean {
        if (type == PublicEntityType.FILM && language.isNotBlank() && knownLanguage.isNotBlank() && !language.equals(knownLanguage, true)) return false
        if (!subjectMatches("$title $body")) return false
        if (!subjectMatches("$title $url")) {
            fun canonical(value: String) = value.lowercase().replace('’', '\'').replace(Regex("\\s+"), " ")
            if (!Regex("(?<![\\p{L}\\p{N}])${Regex.escape(canonical(subject))}(?![\\p{L}\\p{N}])").containsMatchIn(canonical(body))) return false
        }
        if (type != PublicEntityType.FILM) return true
        val text = "$title $body"
        if (knownLanguage.isBlank() && !Regex("(?i)\\b(?:films?|movies?|cinema|screenplay|director|starring|cast|verdict)\\b").containsMatchIn(text)) return false
        if (knownLanguage.isNotBlank()) return true // Full-page identity survives excerpt shrinking.
        // Missing language metadata is not a conflict. An explicit different
        // language for the same title must not silently identify another film.
        val languagePattern = Regex("(?i)\\b(tamil|hindi|malayalam|telugu|kannada|bengali|marathi|korean|japanese)\\s+(?:language\\s+)?(?:film|movie)\\b")
        val bodyLanguages = languagePattern.findAll(body.substringAfter('\n', body)).map { it.groupValues[1].lowercase() }.toSet()
        val languages = bodyLanguages.ifEmpty { languagePattern.findAll(title).map { it.groupValues[1].lowercase() }.toSet() }
        return language.isBlank() || languages.isEmpty() || language.lowercase() in languages
    }
    fun searches(): List<String> {
        val facet = when (intent) {
            PublicTopicIntent.REVIEW -> "reviews audience reception"
            PublicTopicIntent.RATING -> "rating review"
            PublicTopicIntent.CAST -> if (currentCasting) "upcoming announced cast actors" else "cast actors"
            PublicTopicIntent.RELEASE -> if (releaseChannel == "OTT") "OTT streaming digital release date" else "release date"
            PublicTopicIntent.SEQUEL -> "sequel announcement"
            else -> "information"
        }
        val unaccented = Normalizer.normalize(subject, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        // Keep the supplied title. Variations change phrasing/diacritics only,
        // never guess a different film or use an assistant-invented title.
        return listOf("$identity $facet", "\"$subject\" $language ${if (type == PublicEntityType.FILM) "movie" else ""} $facet",
            "$unaccented $language ${if (type == PublicEntityType.FILM) "cinema" else "product"} $facet")
            .mapIndexed { index, query -> if (intent == PublicTopicIntent.REVIEW && index == 0) "$identity critic reviews" else query }
            .map { it.replace(Regex("\\s+"), " ").trim().take(300) }.distinct().take(3)
    }
}

object PublicTopics {
    fun validLanguage(value: String) = Regex("(?i)tamil|hindi|malayalam|telugu|kannada|bengali|marathi|english|korean|japanese").matches(value)
    /** Same-name remakes are distinct subjects. When the read evidence explicitly
     * identifies the requested edition, do not mix in an unidentified edition.
     * Older sources without metadata remain usable when no explicit match exists. */
    fun alignedSources(question: String, sources: List<EvidenceSource>): List<EvidenceSource> {
        val topic = parse(question) ?: return sources
        val aligned = sources.filter { topic.sourceMatches(it.title, it.excerpt, it.url, it.publicLanguage) }
        return if (topic.type == PublicEntityType.FILM && topic.language.isNotBlank() &&
            aligned.any { it.publicLanguage.equals(topic.language, true) })
            aligned.filter { it.publicLanguage.equals(topic.language, true) } else aligned
    }
    /** Explicit publisher language description, captured BEFORE passage ranking
     * or prompt shrinking. Never infer a film's language from an actor's name. */
    fun primaryLanguage(title: String, lead: String): String {
        val pattern = Regex("(?i)\\b(tamil|hindi|malayalam|telugu|kannada|bengali|marathi|english|korean|japanese)(?:[- ]language)?[- ]+(?:(?:crime|action|fantasy|romantic|comedy|drama)[- ]+){0,3}(?:film|movie|thriller|sequel|version|remake|adaptation|franchise)\\b")
        val original = Regex("(?i)\\b(?:original|originally (?:made|filmed|released) in) (tamil|hindi|malayalam|telugu|kannada|bengali|marathi|english|korean|japanese)\\b")
        val body = lead.take(4000)
        return (pattern.find(title) ?: listOfNotNull(pattern.find(body), original.find(body)).minByOrNull { it.range.first })
            ?.groupValues?.get(1)?.lowercase().orEmpty()
    }
    /** A near spelling from discovery is a clarification candidate only, never
     * evidence or an automatic identity substitution. No actor/rating is inferred. */
    fun possibleSpelling(topic: PublicResearchTopic, title: String): String? {
        if (topic.type != PublicEntityType.FILM || topic.subject.length !in 4..60) return null
        val before = title.substringBefore(Regex("(?i)\\b(?:movie\\s+|film\\s+)?review\\b").find(title)?.value ?: return null)
            .trim(' ', '\'', '"', '‘', '’', '“', '”', '-', ':')
            .replace(Regex("(?i)\\s+(?:tamil|hindi|malayalam|telugu|kannada)\\s*$"), "").trim()
        if (before.length !in 4..60 || !Regex("[\\p{L}\\p{M}][\\p{L}\\p{M} '-]*").matches(before) || !ConversationalResearch.validPublicQuery(before)) return null
        fun folded(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+|[^\\p{L}]"), "")
        val a = folded(topic.subject); val b = folded(before)
        if (a == b || kotlin.math.abs(a.length - b.length) > 2) return null
        var row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
            row = next
        }
        return before.takeIf { row.last() <= 2 }
    }
    fun normalize(text: String) = text.let { value ->
        // Reviewed alternate transliteration, not a guessed film or an answer.
        if (Regex("(?i)\\b(?:film|movie|ott|streaming)\\b").containsMatchIn(value))
            value.replace(Regex("(?i)\\bdhrishyam\\b"), "Drishyam") else value
    }.replace(Regex("(?i)\\b(how|what|who|when)['’]s\\b"), "$1 is")
        .replace(Regex("(?i)^\\s*(?:(?:can|could|would) you\\s+|please\\s+)+"), "").trim()
    private val language = Regex("(?i)\\b(tamil|hindi|malayalam|telugu|kannada|bengali|marathi|english|korean|japanese)\\b")
    private val generic = Regex("(?i)^\\s*(?:(?:please|can you|could you)\\s+)?(?:(?:find|search(?: for)?|show(?: me)?|give me|check|look up)\\s+)?(?:(?:a|the|its|some)\\s+)?(?:reviews?|ratings?|sources?|search|tell me more|more information)\\s*[.!?]*$")
    private val reference = Regex("(?i)^(?:who (?:acted|starred|stars|made|directed|owns).{0,35}\\b(?:it|this|that)\\b|what.{0,25}\\b(?:its rating|rating of it|rating|release date)\\b|when (?:is|was|did) (?:it|this|that).{0,25}releas\\w*|is (?:it|this|that) (?:any )?good|(?:what|how) about (?:the )?sequel|(?:does|did|will) (?:it|this|that).{0,30}sequel)\\b")
    private fun description(text: String) = Regex("(?i)^it[’']?s (?:a |the )?(?:recent |new |latest )?(?:malayal(?:am|m)|hindi|tamil|telugu|kannada) (?:movie|film)\\b").containsMatchIn(normalize(text))
    fun followUp(text: String) = generic.matches(normalize(text)) || reference.containsMatchIn(normalize(text)) ||
        description(text) || Regex("(?i)^when (?:will|does) it\\b").containsMatchIn(normalize(text))
    fun missingSubject(text: String) = !text.contains("(Public source topic:") && parse(text) == null && (followUp(text) ||
        Regex("(?i)^(?:how (?:is|was) |review (?:of )?)?(?:(?:the|tamil|hindi|malayalam|telugu)\\s+)*(?:movie|film)\\s*[?!]*$").matches(normalize(text)))
    fun intent(text: String): PublicTopicIntent {
        val q = normalize(text)
        return when {
            Regex("(?i)\\b(?:rat(?:ing|ed)|stars? out of|score)\\b").containsMatchIn(q) -> PublicTopicIntent.RATING
            Regex("(?i)\\b(?:who (?:acted|starred|stars|act|acts)|cast|actors?|actresses)\\b").containsMatchIn(q) -> PublicTopicIntent.CAST
            Regex("(?i)\\bsequel\\b").containsMatchIn(q) -> PublicTopicIntent.SEQUEL
            Regex("(?i)\\breleas(?:e|ed|ing)\\b").containsMatchIn(q) -> PublicTopicIntent.RELEASE
            Regex("(?i)\\breviews?|reception|verdict|is .{0,90}good|how (?:is|was)\\b").containsMatchIn(q) -> PublicTopicIntent.REVIEW
            else -> PublicTopicIntent.INFORMATION
        }
    }
    fun parse(text: String, conversationId: Long = 0, userMessageId: Long = 0): PublicResearchTopic? {
        val q = normalize(text.substringBefore("(Public source topic:")).trim(' ', '?', '.', '!')
        if (q.length !in 3..240 || !ConversationalResearch.validPublicQuery(q) ||
            Regex("(?i)\\b(?:my|our|your|password|secret|token|project|email|medical)\\b|^(?:write|draft|translate|explain|improve|polish|brainstorm)\\b").containsMatchIn(q)) return null
        val film = Regex("(?i)\\b(?:movie|film)\\b").find(q)
        val impliedFilm = film == null && language.containsMatchIn(q) && intent(q) == PublicTopicIntent.RELEASE &&
            Regex("(?i)\\b(?:ott|streaming)\\b").containsMatchIn(q)
        val product = Regex("(?i)\\b(?:iphone|pixel|galaxy|oneplus|macbook|ipad|snapdragon)\\b").find(q)
        if (film == null && !impliedFilm && (product == null || intent(q) !in setOf(PublicTopicIntent.REVIEW, PublicTopicIntent.RATING))) return null
        val type = if (film != null || impliedFilm) PublicEntityType.FILM else PublicEntityType.PRODUCT
        var name = if (film != null) q.take(film.range.first) else q
        val lang = if (type == PublicEntityType.FILM) language.find(q)?.value.orEmpty() else ""
        if (film != null && description(q)) return null
        val scaffold = ResearchQuery.words("who what when which how is are was were will be being cast actors actresses acted stars starred in of for to a the plot rating review reviews release date good new latest tell me about find search show give check look up")
        if (film != null && ResearchQuery.words(name.replace(language, " ")).all { it in scaffold }) {
            val after = q.substring(film.range.last + 1).trim()
            if (ResearchQuery.words(after).any { it !in scaffold }) name = after
        }
        // A title may follow the film label: "Malayalam movie I'm Game".
        val prefix = name.replace(language, " ").replace(Regex("(?i)^(?:when (?:is|was)|what is|the)\\s*"), "").trim()
        if (film != null && prefix.isBlank()) name = q.substring(film.range.last + 1)
        name = name.replace(Regex("(?i)^(?:is there (?:a )?sequel to|what is (?:the )?rating of|what (?:is|was) (?:the )?release date of|when (?:is|was)|who is (?:being )?cast in|who will star in|who (?:acted|starred|stars) in|who (?:is|was) in|(?:find |search for |show me )?(?:reviews?|rating|cast|release date|information)(?: of| for| about)?|tell me about|how (?:is|was)|is|was)\\s+"), "")
            .replace(language, " ").replace(Regex("(?i)\\s+(?:releas(?:e|ed|ing)|available|coming)\\b.*$"), "")
            .replace(Regex("(?i)\\s+(?:a |the )?(?:good|new|latest)$|\\s+(?:released|releasing)$|\\s+reviews?$"), "")
            .trim(' ', '"', '“', '”', ':', '?', '.', '!').replace(Regex("\\s+"), " ")
        if (name.length !in 2..90 || !Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} &'’.,:()_-]*").matches(name) ||
            name.lowercase() in setOf("it", "this", "that", "the", "a", "review", "reviews", "rating", "tamil", "good")) return null
        return PublicResearchTopic(name, type, lang, intent(q), conversationId, userMessageId,
            if (Regex("(?i)\\b(?:ott|streaming|digital)\\b").containsMatchIn(q)) "OTT" else "",
            Regex("(?i)\\b(?:being cast|upcoming|will star)\\b").containsMatchIn(q))
    }
    fun resolve(text: String, history: List<ChatMessageEntity>, conversationId: Long? = null): PublicResearchTopic? {
        val chat = conversationId ?: history.lastOrNull()?.conversationId ?: 0
        parse(text, chat)?.let { named ->
            if (named.intent == PublicTopicIntent.INFORMATION && history.lastOrNull { it.conversationId == chat }?.groundingStatus == "NEEDS_SUBJECT") {
                val prior = history.asReversed().filter { it.conversationId == chat && it.role == "user" }.take(12)
                    .firstNotNullOfOrNull { parse(it.content, chat, it.id) }
                if (prior != null) return named.copy(intent = prior.intent)
            }
            return named
        }
        if (!followUp(text)) return null
        for (turn in history.asReversed().asSequence().filter { it.conversationId == chat && it.role == "user" }.take(12)) {
            parse(turn.content, chat, turn.id)?.let { return it.copy(intent = intent(text).takeUnless { it == PublicTopicIntent.INFORMATION || description(text) } ?: it.intent,
                releaseChannel = if (Regex("(?i)\\b(?:ott|streaming|digital)\\b").containsMatchIn(text)) "OTT" else it.releaseChannel) }
            // An explicit new local/other task ends this topic. Never revive an
            // old film across a poem, a private request or an unrelated subject.
            if (!followUp(turn.content) && !ConversationalResearch.isRecheck(turn.content)) return null
        }
        return null
    }
}
