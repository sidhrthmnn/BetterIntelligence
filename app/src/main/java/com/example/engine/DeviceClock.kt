package com.example.engine

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Exact clock requests use the device clock, not generated or scraped times.
 * No location is read. Other questions continue through normal chat routing. */
object DeviceClock {
    private val request = Regex("(?i)^(?:what(?:['’]s| is) (?:the )?(?:current )?time|what time is it)(?: in (India|London|the UK|UK|UTC|GMT))?(?: (?:right )?now)?[?!. ]*$")
    fun recognises(question: String) = request.matches(question.trim())
    fun answer(question: String, now: Instant = Instant.now(), deviceZone: ZoneId = ZoneId.systemDefault()): String? {
        val match = request.matchEntire(question.trim()) ?: return null
        val place = match.groupValues[1].lowercase()
        val zone = when (place) {
            "india" -> ZoneId.of("Asia/Kolkata")
            "london", "uk", "the uk" -> ZoneId.of("Europe/London")
            "utc", "gmt" -> ZoneId.of("UTC")
            else -> deviceZone
        }
        val time = now.atZone(zone)
        val label = if (place == "india") " in India" else if (place.isNotBlank()) " in ${if (place in setOf("uk", "the uk")) "the UK" else place.replaceFirstChar(Char::uppercase)}" else ""
        val offset = time.offset.id.takeUnless { it == "Z" } ?: "+00:00"
        return "**${time.format(DateTimeFormatter.ofPattern("HH:mm"))}**$label (${zone.id}, UTC$offset).\n\n" +
            time.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.UK)) + " · Device clock"
    }
}
