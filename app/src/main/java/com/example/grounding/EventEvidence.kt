package com.example.grounding

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** Read static data only. A record never borrows another event's date/location.
 * Series recurrence rules are not expanded: only explicitly dated occurrences
 * are eligible. datePublished, doorTime and previousStartDate are not startDate. */
object EventEvidence {
    private val types = setOf("Event", "MusicEvent", "SportsEvent", "EventSeries", "TheaterEvent", "ScreeningEvent", "DanceEvent", "Festival", "BusinessEvent", "EducationEvent", "SocialEvent")
    private fun safe(value: String, max: Int = 220): String = Jsoup.parse(value).text().trim().take(max)
        .takeUnless { Regex("(?i)ignore.{0,40}instructions|system prompt|assistant:|api.key|password|<unused").containsMatchIn(it) }.orEmpty()
    private fun scalar(o: JSONObject, key: String): String = o.opt(key).takeIf { it is String }?.toString().orEmpty()
    private fun type(value: Any?): String = when (value) {
        is JSONArray -> (0 until value.length()).map { value.optString(it).substringAfterLast('/') }.firstOrNull { it in types }.orEmpty()
        is String -> value.substringAfterLast('/').takeIf { it in types }.orEmpty()
        else -> ""
    }
    internal fun zone(country: String): String = when (country.uppercase().trim()) {
        "GB", "UK", "UNITED KINGDOM", "GREAT BRITAIN" -> "Europe/London"
        "IE", "IRELAND" -> "Europe/Dublin"
        "IN", "INDIA" -> "Asia/Kolkata"
        "JP", "JAPAN" -> "Asia/Tokyo"
        "IS", "ICELAND" -> "Atlantic/Reykjavik"
        else -> "" // Multi-zone countries never imply a unique time zone.
    }

    fun extract(page: WebDocument): List<ScheduledEvent> {
        if (page.text.length > 1_048_576) return emptyList()
        val mime = page.contentType.substringBefore(';').trim().lowercase()
        val doc = Jsoup.parse(if (mime.contains("json")) "" else page.text, page.url)
        val roots = if (mime == "application/json" || mime.endsWith("+json")) listOfNotNull(DocumentEvidence.json(page.text)) else
            doc.select("script[type=application/ld+json]").take(8).mapNotNull { DocumentEvidence.json(it.data()) }
        val objects = mutableListOf<JSONObject>()
        var nodes = 0
        fun collect(value: Any?, depth: Int) {
            if (depth > 12 || ++nodes > 512) return
            when (value) {
                is JSONArray -> (0 until minOf(value.length(), 64)).forEach { collect(value.opt(it), depth + 1) }
                is JSONObject -> {
                    objects += value
                    // Bounded container traversal, not evaluation of scripts.
                    listOf("@graph", "mainEntity", "itemListElement", "item", "subEvent", "subEvents", "events", "data", "results", "location", "address")
                        .forEach { collect(value.opt(it), depth + 1) }
                }
            }
        }
        roots.forEach { collect(it, 0) }
        val byId = objects.filter { scalar(it, "@id").isNotBlank() }.groupBy { scalar(it, "@id") }
            .mapValues { (_, definitions) -> definitions.maxBy { it.length() } }
        fun resolve(value: Any?): JSONObject? = when (value) {
            is JSONObject -> byId[scalar(value, "@id")]?.takeIf { it.length() > value.length() } ?: value
            is String -> byId[value]
            else -> null
        }
        val result = mutableListOf<ScheduledEvent>()
        objects.forEach { obj ->
            val eventType = type(obj.opt("@type"))
            val name = safe(scalar(obj, "name"))
            val start = scalar(obj, "startDate").take(80)
            if (eventType.isBlank() || name.isBlank() || !validStart(start)) return@forEach
            val location = resolve(obj.opt("location"))
            val address = resolve(location?.opt("address"))
            val venue = safe(location?.let { scalar(it, "name") }.orEmpty().ifBlank { obj.opt("location").takeIf { it is String && !it.startsWith("http") }?.toString().orEmpty() })
            val country = address?.opt("addressCountry").let { if (it is JSONObject) scalar(it, "name") else it?.toString().orEmpty() }
            val fullAddress = address?.let { a -> listOf("streetAddress", "addressLocality", "addressRegion", "postalCode")
                .map { scalar(a, it) }.plus(country).filter(String::isNotBlank).joinToString(", ") }
                ?: location?.opt("address")?.takeIf { it is String }?.toString().orEmpty()
            val participants = listOf("homeTeam", "awayTeam", "performer", "organizer").mapNotNull { key ->
                resolve(obj.opt(key))?.let { scalar(it, "name") }?.takeIf(String::isNotBlank)
            }.joinToString("; ")
            result += ScheduledEvent(name, eventType, venue, safe(fullAddress, 400), start,
                scalar(obj, "endDate").takeIf(::validStart).orEmpty(), safe(scalar(obj, "eventStatus"), 100),
                scalar(obj, "eventTimezone").ifBlank { location?.let { scalar(it, "timeZone") }.orEmpty() }
                    .ifBlank { zone(country) }.take(80), HtmlDiscovery.canonicalUrl(page.url), "JSON_LD", safe(participants))
        }
        // Microdata and accessible cards: each nearest event/card is processed
        // independently. <time> alone on a news article is NOT an event date.
        doc.select("script, style, nav, footer, aside, [hidden]").remove()
        val possibleCards = doc.select("[itemtype*=Event], article:has(time), [class*=event]:has(time), [class*=event]:has([class*=date])").take(384)
        val cards = possibleCards.filter { node -> possibleCards.none { it != node && node in it.parents() } }
            .filter { it.text().length in 10..2400 && it.select("[itemprop=startDate], time, [class*=date]").isNotEmpty() }
            .take(96)
        cards.forEach { card ->
            fun prop(key: String): String = card.selectFirst("[itemprop=$key]")?.let { el ->
                el.attr("content").ifBlank { el.attr("datetime") }.ifBlank { el.text() }
            }.orEmpty()
            val startNode = card.selectFirst("[itemprop=startDate], time[datetime]")
            val raw = prop("startDate").ifBlank { startNode?.attr("datetime").orEmpty() }
            val name = safe(prop("name").ifBlank { card.selectFirst("h2, h3, h4, [class*=title], [class*=name]")?.text().orEmpty() })
            val start = raw.takeIf(::validStart) ?: visibleStart(card) ?: return@forEach
            if (name.isBlank()) return@forEach
            val venue = safe(prop("location").ifBlank { card.selectFirst("[class*=venue], [class*=location]")?.text().orEmpty() })
            val address = safe(prop("address"), 400)
            val eventType = card.attr("itemtype").substringAfterLast('/').takeIf { it in types } ?: "Event"
            val status = prop("eventStatus").ifBlank {
                if (Regex("(?i)\\bcancelled|canceled\\b").containsMatchIn(card.text())) "EventCancelled" else ""
            }
            result += ScheduledEvent(name, eventType, venue, address, start, prop("endDate").takeIf(::validStart).orEmpty(),
                safe(status, 100), zone(prop("addressCountry")), HtmlDiscovery.canonicalUrl(page.url), "HTML_EVENT_CARD")
        }
        doc.select("table").take(12).forEach { table ->
            val headers = table.select("tr").firstOrNull()?.select("th")?.map { it.text().trim().lowercase() }.orEmpty()
            fun column(vararg names: String) = headers.indexOfFirst { it in names }
            val nameCol = column("event", "concert", "performance", "match", "fixture", "screening", "event name")
            val dateCol = column("start date", "start", "date", "start date and time")
            if (nameCol < 0 || dateCol < 0) return@forEach
            table.select("tr").drop(1).take(64).forEach rows@{ row ->
                val cells = row.select("td")
                fun cell(i: Int) = cells.getOrNull(i)?.text()?.trim().orEmpty()
                val start = cells.getOrNull(dateCol)?.selectFirst("time[datetime]")?.attr("datetime").orEmpty()
                    .ifBlank { cell(dateCol) }
                val name = safe(cell(nameCol))
                if (!validStart(start) || name.isBlank()) return@rows
                result += ScheduledEvent(name, if (headers[nameCol] == "concert") "MusicEvent" else "Event",
                    safe(cell(column("venue", "venue name"))), safe(cell(column("location", "address")), 400), start,
                    status = safe(cell(column("status")), 100).let { if (it.equals("cancelled", true) || it.equals("canceled", true)) "EventCancelled" else it },
                    zone = cell(column("timezone", "time zone")).take(80), sourceUrl = HtmlDiscovery.canonicalUrl(page.url), provenance = "HTML_EVENT_CARD")
            }
        }
        val reconciled = result.filter { it.provenance == "JSON_LD" }.map { structured ->
            val visible = result.firstOrNull { card -> card.provenance == "HTML_EVENT_CARD" && card.name == structured.name &&
                card.start.length >= 16 && structured.start.startsWith(card.start) && card.venue.isNotBlank() }
            if (visible != null && EventQuery.identity(visible.venue) != EventQuery.identity(structured.venue))
                structured.copy(venue = visible.venue, conflictingVenue = structured.venue) else structured
        }
        return (reconciled + result.filter { event -> event.provenance != "JSON_LD" && result.none { structured ->
            structured.provenance == "JSON_LD" && structured.name == event.name && structured.venue == event.venue && structured.start.startsWith(event.start)
        } && reconciled.none { it.name == event.name && it.venue == event.venue && it.start.startsWith(event.start) } })
            .distinctBy { listOf(it.name, it.venue, it.start) }.take(64)
    }

    private fun validStart(value: String): Boolean = value.length in 10..80 &&
        Regex("^\\d{4}-\\d{2}-\\d{2}(?:T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})?)?$").matches(value) &&
        runCatching {
            if (value.length == 10) LocalDate.parse(value)
            else if (Regex("(?:Z|[+-]\\d{2}:\\d{2})$").containsMatchIn(value)) java.time.OffsetDateTime.parse(value)
            else java.time.LocalDateTime.parse(value)
        }.isSuccess

    private fun visibleStart(card: Element): String? {
        val value = card.selectFirst("[class*=date], time")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        // A date range is not a single occurrence; don't invent repeated starts.
        if (Regex("\\d\\s*[-–]\\s*\\d").containsMatchIn(value)) return null
        val day = listOf("d MMM uuuu", "d MMMM uuuu", "MMM d, uuuu", "MMMM d, uuuu").firstNotNullOfOrNull { pattern ->
            runCatching { LocalDate.parse(value, DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH)) }.getOrNull()
        } ?: return null
        // Only a labelled event start is eligible. Doors opening != start time.
        val clock = Regex("(?i)\\b(?:event starts?|start time|starts at)\\s*:?\\s*(\\d{1,2}:\\d{2}\\s*(?:AM|PM)?)").find(card.text())?.groupValues?.get(1)
        val time = clock?.let { c -> listOf("h:mm a", "H:mm").firstNotNullOfOrNull { pattern ->
            runCatching { LocalTime.parse(c.uppercase().replace(Regex("\\s+"), " ").trim(), DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH)) }.getOrNull()
        } }
        return day.toString() + (time?.let { "T$it" } ?: "")
    }

    fun json(events: List<ScheduledEvent>): JSONArray = JSONArray().apply { events.take(64).forEach { e -> put(JSONObject()
        .put("name", e.name).put("type", e.type).put("venue", e.venue).put("address", e.address).put("start", e.start)
        .put("end", e.end).put("status", e.status).put("zone", e.zone).put("sourceUrl", e.sourceUrl)
        .put("provenance", e.provenance).put("conflictingVenue", e.conflictingVenue)
        .put("extractionConfidence", if (e.provenance == "JSON_LD") "STRUCTURED_FIELDS" else "VISIBLE_FIELDS")
        .put("participants", e.participants)) } }
    fun fromJson(array: JSONArray?, url: String): List<ScheduledEvent> = (0 until minOf(array?.length() ?: 0, 64)).mapNotNull { i ->
        val o = array?.optJSONObject(i) ?: return@mapNotNull null
        val name = safe(o.optString("name")); val start = o.optString("start")
        if (name.isBlank() || !validStart(start) || o.optString("sourceUrl") != url || o.optString("provenance") !in setOf("JSON_LD", "HTML_EVENT_CARD")) null
        else ScheduledEvent(name, safe(o.optString("type")), safe(o.optString("venue")), safe(o.optString("address"), 400), start,
            o.optString("end").takeIf(::validStart).orEmpty(), safe(o.optString("status"), 100), o.optString("zone").take(80), url,
            o.optString("provenance"), safe(o.optString("participants")), safe(o.optString("conflictingVenue")))
    }
}
