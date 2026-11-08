package com.example.grounding

/** A public venue profile can establish a district alias missing from a calendar
 * address. Discovery titles/snippets and arbitrary URL words cannot do so.
 * Event facts still come only from the independently read event record. */
object VenueContext {
    /** Ranking hint only, never proof of ownership or factual correctness. */
    fun publisherHint(source: EvidenceSource): Int = source.events.maxOfOrNull { event ->
        val parent = Regex("(?i)\\s+at\\s+(.+)$").find(event.venue)?.groupValues?.get(1) ?: event.venue
        val name = (EventQuery.identity(parent) - setOf("arena", "venue", "stadium", "hall", "theatre", "theater")).sorted().joinToString("")
        val host = EvidenceRanking.publisher(source.url).substringBefore('.').replace("-", "")
        if (name.length >= 2 && host in setOf(name, "the$name")) 100 else 0
    } ?: 0
    private val location = Regex("(?i)\\b(?:located|situated|based|address|peninsula|district|borough|neighbourhood|neighborhood|getting (?:here|to)|find us)\\b")
    private val negative = Regex("(?i)\\b(?:not|never|formerly|previously|moved from|no longer)\\b|ignore.{0,40}instructions|system prompt|assistant:")
    fun passage(source: EvidenceSource, query: EventQuery): String? = source.excerpt.split(Regex("\\n\\s*\\n")).firstOrNull { block ->
            val found = EventQuery.identity(block)
            found.containsAll(EventQuery.identity(query.subject)) && location.containsMatchIn(block) && !negative.containsMatchIn(block)
        }
    fun profile(source: EvidenceSource, query: EventQuery): Boolean = source.events.isEmpty() && passage(source, query) != null
    fun potential(event: ScheduledEvent, query: EventQuery): Boolean = query.matches(event) ||
        (EventQuery.identity(event.venue + " " + event.name + " " + event.participants) intersect EventQuery.identity(query.subject)).isNotEmpty()
    fun corroborates(event: ScheduledEvent, eventSource: EvidenceSource, profile: EvidenceSource, query: EventQuery): Boolean {
        if (!profile(profile, query)) return false
        val required = EventQuery.identity(event.venue)
        if (required.isEmpty()) return false
        val samePublisher = EvidenceRanking.publisher(eventSource.url) == EvidenceRanking.publisher(profile.url)
        // A first-party venue page may call its main hall 'arena'; other sites
        // must state the full venue identity, not just a shared sponsor name.
        val parent = Regex("(?i)\\s+at\\s+(.+)$").find(event.venue)?.groupValues?.get(1)
        val venue = if (samePublisher) EventQuery.identity(parent ?: event.venue) - setOf("arena", "venue") else required
        return venue.isNotEmpty() && profile.excerpt.split(Regex("\\n\\s*\\n")).any {
            val words = EventQuery.identity(it)
            words.containsAll(venue) && words.containsAll(EventQuery.identity(query.subject)) && location.containsMatchIn(it) && !negative.containsMatchIn(it)
        }
    }
}
