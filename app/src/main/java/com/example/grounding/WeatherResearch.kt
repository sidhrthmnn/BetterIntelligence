package com.example.grounding

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Public structured data, only for an explicitly typed city. Never accesses GPS.
 * Open-Meteo data attribution is included in the answer and source card.
 */
class WeatherResearch(private val fetcher: WebFetcher = SearchHttp()) {
    suspend fun current(city: String): GroundingResult {
        require(!GroundingPolicy.containsCredential(city))
        val search = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", city).addQueryParameter("count", "1").addQueryParameter("language", "en").build()
        val places = JSONObject(fetcher.fetch(search.toString()).text).optJSONArray("results")
        val place = places?.optJSONObject(0) ?: return GroundingResult("NEEDS_LOCATION", notice = "I couldn't find that city. Please give a city and country.")
        val lat = place.getDouble("latitude"); val lon = place.getDouble("longitude")
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", lat.toString()).addQueryParameter("longitude", lon.toString())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,precipitation,wind_speed_10m")
            .addQueryParameter("timezone", "auto").addQueryParameter("timeformat", "unixtime").addQueryParameter("forecast_days", "1").build().toString()
        val json = JSONObject(fetcher.fetch(url).text)
        val data = json.getJSONObject("current")
        val timestamp = data.getLong("time") * 1000
        check(kotlin.math.abs(System.currentTimeMillis() - timestamp) < 3 * 60 * 60 * 1000L) { "Weather data is not current" }
        val units = json.getJSONObject("current_units")
        fun value(key: String): String {
            val number = data.getDouble(key); check(number.isFinite())
            return "$number ${units.getString(key)}"
        }
        val location = listOf(place.getString("name"), place.optString("admin1"), place.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", ")
        val answer = "Weather for $location: ${value("temperature_2m")} (feels like ${value("apparent_temperature")}), " +
            "precipitation ${value("precipitation")}, wind ${value("wind_speed_10m")}. [1]\n\n" +
            "Open-Meteo modelled current conditions, ${java.time.Instant.ofEpochMilli(timestamp)}. This is not a direct measurement at your location."
        return GroundingResult("STRUCTURED_ANSWER", listOf(EvidenceSource(1, "Open-Meteo weather for $location", url,
            answer, System.currentTimeMillis(), java.time.Instant.ofEpochMilli(timestamp).toString(), "STRUCTURED_DATA", "MODIFIED")), answer)
    }
}
