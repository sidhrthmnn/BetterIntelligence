package com.example.grounding

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element

/** Read visible observation/forecast cards, never an API or embedded script.
 * Keep timestamps and forecast labels. Photo temperatures are not observations. */
object WeatherPageEvidence {
    fun current(text: String, today: java.time.LocalDate): Boolean {
        if (!EvidenceAnswer.hasTemperature(text)) return false
        if (Regex("(?i)\\btoday forecast: high\\b").containsMatchIn(text)) return true
        val month = today.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        return Regex("(?is)observed at[^\\n]{0,160}\\b${today.dayOfMonth}(?:st|nd|rd|th)?\\s+${Regex.escape(month)}\\b").containsMatchIn(text)
    }
    fun blocks(root: Element, title: String, url: String): List<String> {
        if (url.toHttpUrl().host !in setOf("www.bbc.co.uk", "www.bbc.com", "bbc.co.uk", "bbc.com")) return emptyList()
        val result = mutableListOf<String>()
        root.selectFirst("[class*=WeatherObservationsContainer]")?.let { observation ->
            val text = observation.text()
            val c = Regex("Temperature\\s*(-?\\d+(?:\\.\\d+)?)\\s*°\\s*Celsius", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            val f = Regex("Temperature\\s*(-?\\d+(?:\\.\\d+)?)\\s*°\\s*Fahrenheit", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            val timing = observation.selectFirst("[class*=ObservationsContainerText]")?.text().orEmpty()
            if (c != null && Regex("(?i)observed at").containsMatchIn(timing))
                result += "$title\n$timing\nObserved temperature: $c °C${f?.let { " ($it °F)" }.orEmpty()}."
        }
        root.selectFirst("#daylink-0")?.takeIf { it.text().startsWith("Today", true) }?.let { today ->
            fun temperature(selector: String) = today.selectFirst("$selector .wr-value--temperature--c")?.text()
                ?.let { Regex("^(-?\\d+(?:\\.\\d+)?)°$").matchEntire(it)?.groupValues?.get(1) }
            val high = temperature(".wr-day-temperature__high")
            val low = temperature(".wr-day-temperature__low")
            if (high != null && low != null) result += "$title\nToday forecast: high $high °C; low $low °C."
        }
        return result
    }
}
