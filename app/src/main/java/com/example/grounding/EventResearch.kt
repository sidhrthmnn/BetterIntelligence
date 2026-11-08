package com.example.grounding

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Event time is independent of the publication date of a calendar. No venue,
 * performer, event date or publisher endpoint is supplied by this planner. */
data class EventQuery(val subject: String, val constraint: String, val question: String) {
    val aliases: Set<String> = setOf(subject, subject.removePrefix("The ").removePrefix("the "))
    val requestedFields = listOf("name", "startDate", "location", "eventStatus")
    val searches: List<String> get() = listOf(
        "$subject $constraint events schedule",
        "$subject official upcoming calendar fixtures",
        "$subject venue address events start time"
    ).distinct().take(3)

    fun matches(event: ScheduledEvent): Boolean {
        val required = identity(subject)
        val found = identity("${event.name} ${event.venue} ${event.address} ${event.participants}")
        return required.isNotEmpty() && found.containsAll(required) &&
            (!Regex("(?i)\\bconcerts?\\b").containsMatchIn(question) || event.type == "MusicEvent" ||
                Regex("(?i)concert|music").containsMatchIn(event.name + " " + event.type))
    }

    fun window(now: Instant, zone: ZoneId): Pair<LocalDate, LocalDate?> {
        val today = now.atZone(zone).toLocalDate()
        return when (constraint) {
            "tomorrow" -> today.plusDays(1).let { it to it }
            "this weekend" -> {
                val saturday = today.plusDays(((6 - today.dayOfWeek.value).coerceAtLeast(0)).toLong())
                (if (today.dayOfWeek == DayOfWeek.SUNDAY) today else saturday) to
                    (if (today.dayOfWeek == DayOfWeek.SUNDAY) today else saturday.plusDays(1))
            }
            "next week" -> today.plusDays((8 - today.dayOfWeek.value).toLong()).let { it to it.plusDays(6) }
            "tonight", "later today", "today" -> today to today
            else -> today to null
        }
    }

    companion object {
        private val activity = Regex("(?i)\\b(?:events?|concerts?|performances?|fixtures?|matches|match|launch|launches|cinema|showtimes?|screenings?|shows?|gigs?|activities|happening)\\b")
        private val temporal = Regex("(?i)\\b(?:next|upcoming|scheduled|tomorrow|tonight|this weekend|next week|later today|today)\\b")
        private val generic = ResearchQuery.words("a an the at in on for near next upcoming scheduled tomorrow tonight this weekend week later today event events concert concerts performance fixture match launch cinema showtime screening show gig activity happening official calendar schedule")
        fun identity(text: String): Set<String> = ResearchQuery.words(text) - generic
        fun parse(question: String): EventQuery? {
            val q = question.substringBefore("(Public source topic:").trim().trimEnd('?', '.', '!')
            if (!activity.containsMatchIn(q) || !temporal.containsMatchIn(q)) return null
            val time = listOf("this weekend", "next week", "later today", "tomorrow", "tonight", "today", "next", "upcoming", "scheduled")
                .firstOrNull { Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(q) } ?: "upcoming"
            val tail = Regex("(?i)\\b(?:at|in|near)\\s+(.+)$").find(q)?.groupValues?.get(1)
            val subject = (tail ?: q.replace(Regex("(?i)^.*?\\b(?:next|upcoming|scheduled)\\s+"), ""))
                .replace(Regex("(?i)\\b(?:this weekend|next week|later today|tomorrow|tonight|today|next|upcoming|scheduled|events?|concerts?|performances?|fixtures?|matches|match|launches?|showtimes?|screenings?|happening)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim(' ', ',', ':')
            if (subject.length !in 2..120 || identity(subject).isEmpty()) return null
            return EventQuery(subject, time, q)
        }
    }
}

/** Only fields read from a permitted publisher page. Missing clock/zone fields
 * stay missing. sourceUrl is the page actually fetched, not a discovery snippet. */
data class ScheduledEvent(val name: String, val type: String, val venue: String, val address: String,
    val start: String, val end: String = "", val status: String = "", val zone: String = "",
    val sourceUrl: String, val provenance: String, val participants: String = "", val conflictingVenue: String = "") {
    fun date(): LocalDate? = runCatching { LocalDate.parse(start.take(10)) }.getOrNull()
    fun explicitZone(): ZoneId? = runCatching { ZoneId.of(zone) }.getOrNull()
    fun instant(): Instant? = runCatching { OffsetDateTime.parse(start).toInstant() }.getOrNull()
        ?: explicitZone()?.let { tz -> runCatching {
            val local = LocalDateTime.parse(start)
            // A DST gap/fold without an offset is ambiguous, not permission to
            // silently shift a start time or select one of two possible starts.
            val offsets = tz.rules.getValidOffsets(local)
            if (offsets.size == 1) local.toInstant(offsets.single()) else null
        }.getOrNull() }
    fun cancelled() = Regex("(?i)cancelled|canceled|postponed").containsMatchIn(status)
    fun fields(): String = listOf("Event: $name", "Type: $type", "Venue: $venue", "Location: $address",
        "Start: $start", date()?.let { "Date: ${it.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH))}" }.orEmpty(),
        conflictingVenue.takeIf(String::isNotBlank)?.let { "Conflicting structured venue: $it" }.orEmpty(),
        runCatching { java.time.OffsetDateTime.parse(start).toLocalTime() }.getOrNull()
            ?.let { "Local start time: $it" } ?: runCatching { LocalDateTime.parse(start).toLocalTime() }.getOrNull()?.let { "Local start time: $it" }.orEmpty(),
        end.takeIf(String::isNotBlank)?.let { "End: $it" }.orEmpty(),
        status.takeIf(String::isNotBlank)?.let { "Status: $it" }.orEmpty(),
        zone.takeIf(String::isNotBlank)?.let { "Timezone: $it" }.orEmpty()).filter { !it.endsWith(": ") && it.isNotBlank() }.joinToString("\n")
}

/** Deterministic temporal verification also serves as evidence-first recovery.
 * The LLM cannot change the event/venue/date mapping or invent missing times. */
object EventAnswer {
    data class Fact(val event: ScheduledEvent, val source: EvidenceSource, val contextIds: Set<Int> = emptySet())
    fun candidates(query: EventQuery, sources: List<EvidenceSource>, now: Instant): List<Fact> = sources.flatMap { source ->
        source.events.filter { it.sourceUrl == source.url && !it.cancelled() }.mapNotNull { event ->
            val context = if (query.matches(event)) null else sources.firstOrNull { VenueContext.corroborates(event, source, it, query) }
            if (!query.matches(event) && context == null) return@mapNotNull null
            val date = event.date() ?: return@mapNotNull null
            val zone = event.explicitZone() ?: runCatching { OffsetDateTime.parse(event.start).offset }.getOrNull() ?: ZoneOffset.UTC
            val (from, through) = query.window(now, zone)
            if (date < from || through != null && date > through) return@mapNotNull null
            val instant = event.instant()
            if (instant != null && instant <= now) return@mapNotNull null
            Fact(event, source, context?.let { setOf(it.id) }.orEmpty())
        }
    }.distinctBy { listOf(it.event.name.lowercase(), it.event.venue.lowercase(), it.event.start, it.source.id.toString()) }
        .sortedBy { it.event.instant() ?: it.event.date()!!.atStartOfDay(it.event.explicitZone() ?: ZoneOffset.UTC).toInstant() }

    fun conflicting(facts: List<Fact>): List<Fact>? = facts.groupBy {
        EventQuery.identity(it.event.name).sorted() to EventQuery.identity(it.event.venue).sorted()
    }.values.firstNotNullOfOrNull { group ->
        val byPublisher = group.groupBy { it.source.url }.values
        // Multiple dated performances on one calendar are not a date conflict.
        val oneEach = byPublisher.all { rows -> rows.map { it.event.instant()?.toString() ?: it.event.start }.distinct().size == 1 }
        val records = byPublisher.map { it.first() }
        val disagreement = records.any { a -> records.any { b ->
            val left = a.event; val right = b.event
            when {
                left.instant() != null && right.instant() != null -> left.instant() != right.instant()
                left.date() != right.date() -> true
                // A floating clock and the same explicit local clock are not a
                // contradiction. The former still has unknown timezone/support.
                else -> false
            }
        } }
        if (oneEach && byPublisher.size > 1 && disagreement) records.take(3) else null
    }

    fun conflictScope(query: EventQuery, facts: List<Fact>): List<Fact> =
        if (query.constraint == "next") facts.filter {
            val first = facts.firstOrNull()?.event
            it.event.date() == first?.date() || first != null && EventQuery.identity(it.event.name) == EventQuery.identity(first.name) &&
                EventQuery.identity(it.event.venue) == EventQuery.identity(first.venue)
        } else facts.take(4)

    fun resolve(question: String, sources: List<EvidenceSource>, now: Instant = Instant.now()): CheckedAnswer? {
        val query = EventQuery.parse(question) ?: return null
        val facts = candidates(query, sources, now)
        if (facts.isEmpty()) return CheckedAnswer(
            "I found event listings, but couldn't verify a matching upcoming start date and venue. Share an official event listing or try a more specific venue.",
            AnswerSupport.INSUFFICIENT, emptyList(), recoveryAttempted = true)
        // An unrelated later performance cannot invalidate the requested next
        // start. Keep contradictions that could change that earliest date.
        val conflicts = conflicting(conflictScope(query, facts))
        if (conflicts != null) {
            val text = conflicts.take(3).joinToString("\n\n") { "${it.event.name} at ${it.event.venue}: the listing reports ${it.event.start} [${it.source.id}]." } +
                "\n\nI can't establish which event starts next because these listings disagree on the start date/time."
            return CheckedAnswer(text, AnswerSupport.PARTIAL, emptyList(), sourceExtract = true, recoveryAttempted = true)
        }
        val chosen = if (query.constraint == "next" && facts.first().event.instant() == null) {
            // Keep a useful fully timed listing as well as the earlier/same-day
            // date-only lead. Never let an unknown time erase the known facts.
            listOfNotNull(facts.firstOrNull { it.event.instant() != null }, facts.first()).distinct()
        } else if (query.constraint == "next") facts.take(1) else facts.distinctBy { it.event.name to it.event.start }.take(4)
        val formatter = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH)
        val checks = chosen.map { (event, source, contextIds) ->
            val clock = runCatching { OffsetDateTime.parse(event.start).format(DateTimeFormatter.ofPattern("HH:mm XXX")) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(event.start).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()?.let {
                    "$it (${event.zone.ifBlank { "timezone not stated" }})"
                }
            val line = "**${event.name}**${event.venue.takeIf(String::isNotBlank)?.let { " at $it" }.orEmpty()} on ${event.date()!!.format(formatter)}" +
                (clock?.let { " at $it" } ?: " (start time not stated)") + " " + (setOf(source.id) + contextIds).joinToString(" ") { "[$it]" } + "."
            ClaimCheck(line, setOf(source.id) + contextIds, true, "Publisher event fields and temporal checks passed", citationAssembled = true)
        }
        // Date-only starts today cannot be classified as future. Even a known
        // event tomorrow cannot beat an earlier date-only listing with certainty.
        val uncertain = chosen.any { it.event.instant() == null || it.event.conflictingVenue.isNotBlank() }
        val heading = if (uncertain) "### Dated listings\n\n" else if (query.constraint == "next") "### Earliest verified listing\n\n" else "### Upcoming verified listings\n\n"
        val hallConflicts = chosen.filter { it.event.conflictingVenue.isNotBlank() }.joinToString("\n\n") {
            "The visible listing names ${it.event.venue}; the page's structured metadata names ${it.event.conflictingVenue}. Confirm the hall with the publisher [${it.source.id}]."
        }
        val text = heading + checks.joinToString("\n\n") { it.text } +
            (if (hallConflicts.isNotBlank()) "\n\n$hallConflicts" else "") +
            if (chosen.any { it.event.instant() == null }) "\n\nI can't confirm the next starting event precisely because the available time/timezone is incomplete." else ""
        return CheckedAnswer(text, if (uncertain) AnswerSupport.PARTIAL else AnswerSupport.CITED, checks,
            sourceExtract = true, recoveryAttempted = true, recoveredClaims = checks.size)
    }
}
