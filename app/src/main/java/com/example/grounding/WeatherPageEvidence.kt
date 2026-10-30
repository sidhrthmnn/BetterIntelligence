package com.example.grounding

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element

/** Read visible observation/forecast cards, never an API or embedded script.
 * Keep timestamps and forecast labels. Photo temperatures are not observations. */
object WeatherPageEvidence {
    fun current(text: String, today: java.time.LocalDate,
        now: java.time.ZonedDateTime = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)): Boolean {
        if (!EvidenceAnswer.hasTemperature(text)) return false
        if (Regex("(?i)\\b(?:today|tonight) forecast: (?:high|low)\\b").containsMatchIn(text)) return true
        val month = today.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        if (Regex("(?is)observed at[^\\n]{0,160}\\b${today.dayOfMonth}(?:st|nd|rd|th)?\\s+${Regex.escape(month)}\\b").containsMatchIn(text)) return true
        // A recent observation does not become unusable at midnight. Require
        // an explicit previous date AND GMT/UTC time, at most six hours old.
        // Its actual reported time stays visible; never relabel it as today's.
        if (now.toLocalDate() != today) return false
        val previous = today.minusDays(1)
        val previousMonth = previous.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        val line = text.lines().firstOrNull { Regex("(?i)observed at").containsMatchIn(it) } ?: return false
        if (!Regex("(?i)\\b${previous.dayOfMonth}(?:st|nd|rd|th)?\\s+${Regex.escape(previousMonth)}\\b").containsMatchIn(line)) return false
        val time = Regex("(?i)observed at[, :]*(\\d{1,2}):(\\d{2})\\s*\\(?GMT\\)?|observed at[, :]*(\\d{1,2}):(\\d{2})\\s*\\(?UTC\\)?").find(line) ?: return false
        val hour = time.groupValues[1].ifBlank { time.groupValues[3] }.toIntOrNull() ?: return false
        val minute = time.groupValues[2].ifBlank { time.groupValues[4] }.toIntOrNull() ?: return false
        val observed = runCatching { previous.atTime(hour, minute).atZone(java.time.ZoneOffset.UTC) }.getOrNull() ?: return false
        return java.time.Duration.between(observed, now).toMinutes() in 0..360
    }
    fun blocks(root: Element, title: String, url: String): List<String> {
        if (url.toHttpUrl().host !in setOf("www.bbc.co.uk", "www.bbc.com", "bbc.co.uk", "bbc.com")) return emptyList()
        val result = mutableListOf<String>()
        root.selectFirst("[class*=WeatherObservationsContainer]")?.let { observation ->
            val text = observation.text()
            // Publisher text may lose its decorative degree glyph during
            // encoding. Require the explicit unit name; never infer units from
            // an unlabeled number or a nearby photo/gallery temperature.
            val c = Regex("Temperature\\s*(-?\\d+(?:\\.\\d+)?)\\s*(?:[°\uFFFD]\\s*)?Celsius", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            val f = Regex("Temperature\\s*(-?\\d+(?:\\.\\d+)?)\\s*(?:[°\uFFFD]\\s*)?Fahrenheit", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            val timing = observation.selectFirst("[class*=ObservationsContainerText]")?.text().orEmpty()
                .replace(Regex("(?i)(?<=\\p{L})(?=Observed at:)"), " ")
            if (c != null && Regex("(?i)observed at").containsMatchIn(timing))
                result += "$title\n$timing\nObserved temperature: $c °C${f?.let { " ($it °F)" }.orEmpty()}."
        }
        root.selectFirst("#daylink-0")?.takeIf { it.text().startsWith("Today", true) || it.text().startsWith("Tonight", true) }?.let { today ->
            fun temperature(selector: String) = today.selectFirst("$selector .wr-value--temperature--c")?.text()
                ?.let { Regex("^(-?\\d+(?:\\.\\d+)?)[°\uFFFD]$").matchEntire(it)?.groupValues?.get(1) }
            val high = temperature(".wr-day-temperature__high")
            val low = temperature(".wr-day-temperature__low")
            if (high != null && low != null) result += "$title\nToday forecast: high $high °C; low $low °C."
            else if (today.text().startsWith("Tonight", true) && low != null) result += "$title\nTonight forecast: low $low °C."
        }
        return result
    }
}
