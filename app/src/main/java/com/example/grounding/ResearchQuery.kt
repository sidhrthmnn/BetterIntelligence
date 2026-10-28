package com.example.grounding

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.math.ln

/** Small deterministic query plan: no second model, private history or credentials. */
class ResearchQuery(val question: String, val today: LocalDate = LocalDate.now(), searchOverride: String? = null) {
    companion object {
        private val stop = ("a an and are as at be been by can could did do does for from give has have how " +
            "i in into is it me of on or please say should tell than that the their there these they this to " +
            "us was were what when where which who why will with would you your won win winner winners " +
            "latest recent recently current currently today yesterday now new news source sources cite " +
            "verify check fact summarize summary about answer only read find search public topic context require requires required requirement requirements " +
            "again all also sure really wrong correct right still incumbent present " +
            "ഇന്ന് ഇപ്പോൾ എന്താണ് ആരാണ്").split(' ').toSet()
        private val phraseAbbreviations = listOf(
            "cm" to listOf("chief", "minister"),
            "pm" to listOf("prime", "minister"),
            "ceo" to listOf("chief", "executive", "officer"),
            "uk" to listOf("united", "kingdom"),
            "usa" to listOf("united", "states"),
            "uae" to listOf("united", "arab", "emirates")
        )
        private val wordPattern = Regex("[\\p{L}\\p{M}][\\p{L}\\p{M}\\p{N}]*|\\p{N}+(?:\\.\\p{N}+)*")
        private val englishWord = Regex("[a-z]{5,}")
        private val modelVersion = Regex("(?i)\\b(gemma|qwen)(\\d+(?:\\.\\d+)*)\\b")
        private fun normalizedModels(text: String) = modelVersion.replace(text, "$1 $2")
        // Mild English inflection matching, without damaging non-Latin words.
        private fun undouble(value: String) = if (value.length > 3 && value.last() in "bdfgmnprt" && value.last() == value[value.lastIndex - 1]) value.dropLast(1) else value
        private fun stem(value: String) = if (value == "registration") "register" else if (value.matches(englishWord)) when {
            value.endsWith("ies") -> value.dropLast(3) + "y"
            value.endsWith("ing") && value.length >= 7 -> undouble(value.dropLast(3))
            value.endsWith("ed") && value.length >= 6 && !value.endsWith("eed") -> undouble(value.dropLast(2))
            value.endsWith("s") && !value.endsWith("ss") -> value.dropLast(1)
            else -> value
        } else value
        private val weatherIndicators = setOf(
            "temperature", "celsiu", "fahrenheit", "forecast", "sunny", "cloudy", "overcast", "humidity", "precipitation", "drizzle", "thunderstorm"
        )
        fun words(text: String): Set<String> {
            val base = wordPattern.findAll(normalizedModels(text.lowercase())).map { stem(it.value) }.toMutableSet()
            for ((abbr, expanded) in phraseAbbreviations) {
                if (abbr in base) base.addAll(expanded)
                else if (expanded.all { it in base }) base.add(abbr)
            }
            if (base.any { it in weatherIndicators } || Regex("\\b\\d{1,2}\\s*°\\s*[cf]?\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                base.add("weather")
            }
            return base
        }
        fun wordFrequencies(text: String): Map<String, Int> {
            val counts = mutableMapOf<String, Int>()
            wordPattern.findAll(normalizedModels(text.lowercase())).forEach { match ->
                val token = stem(match.value)
                counts[token] = (counts[token] ?: 0) + 1
            }
            for ((abbr, expanded) in phraseAbbreviations) {
                val abbrCount = counts[abbr] ?: 0
                val minExpanded = expanded.minOf { counts[it] ?: 0 }
                if (abbrCount > 0) {
                    for (part in expanded) counts[part] = (counts[part] ?: 0) + abbrCount
                } else if (minExpanded > 0) {
                    counts[abbr] = minExpanded
                }
            }
            if ((counts["weather"] ?: 0) == 0 && (counts.keys.any { it in weatherIndicators } || Regex("\\b\\d{1,2}\\s*°\\s*[cf]?\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))) {
                counts["weather"] = 1
            }
            return counts
        }
        fun isCurrentIncumbentQuery(question: String): Boolean {
            val q = question.lowercase()
            if (Regex("\\b(who\\s+was|former|previous|first|second|third|\\d+(?:st|nd|rd|th)|19\\d{2}|20[01]\\d)\\b").containsMatchIn(q)) return false
            if (Regex("പ്രധാനമന്ത്രി|മുഖ്യമന്ത്രി|പ്രസിഡന്റ്").containsMatchIn(q) &&
                Regex("ഇപ്പോൾ|ഇപ്പോഴ|നിലവിലെ|ആരാണ്").containsMatchIn(q) && !Regex("മുൻ|ആയിരുന്നു").containsMatchIn(q)) return true
            return Regex("\\b(?:who(?:\\s+is|['’]s)|what\\s+is|current|incumbent|present)\\b").containsMatchIn(q) &&
                Regex("\\b(cm|pm|ceo|chief\\s+ministers?|prime\\s+ministers?|presidents?|governors?|mayors?|leader|chancellor|king|queen|monarch|head|minister|secretary|director|chair(?:man|person)?)\\b").containsMatchIn(q)
        }
        private val normalizedStop = words(stop.joinToString(" "))
        private val writtenDates = listOf("MMMM d, uuuu", "MMM d, uuuu", "d MMMM uuuu", "d MMM uuuu").map {
            DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH)
        }
        fun date(value: String): LocalDate? = runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate()
        }.getOrNull() ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull() ?: writtenDates.firstNotNullOfOrNull { format ->
            runCatching { LocalDate.parse(value.trim(), format) }.getOrNull()
        }
    }
    private val plain = question.replace(Regex("(?i)\\bwho['’]s\\b"), "who is").replace(Regex("https?://\\S+"), " ")
    private val userPlain = plain.substringBefore("(Public source topic:")
    val years = Regex("\\b(?:19|20)\\d{2}\\b").findAll(userPlain).map { it.value }.toSet()
    private val rawTerms = wordPattern.findAll(normalizedModels(plain.lowercase())).map { it.value }
        .filter { stem(it) !in normalizedStop && it !in years &&
            (!FactualResearch.isCasting(userPlain) || it !in setOf("playing", "plays", "played", "play", "portraying", "portrays", "portrayed", "voicing", "voices", "voiced")) }.take(14).toList()
    private val expandedSearchTerms = rawTerms.flatMap { token ->
        phraseAbbreviations.firstOrNull { it.first == token }?.second ?: listOf(token)
    } + if (FactualResearch.capitalPlace(userPlain) != null) listOf("city") else emptyList()
    val terms = rawTerms.map(::stem).toSet()
    private val lower = plain.lowercase()
    private fun has(phrase: String) = Regex("(?<![\\p{L}\\p{M}])${Regex.escape(phrase)}(?![\\p{L}\\p{M}])").containsMatchIn(lower)
    val relative = listOf("latest", "recent", "recently", "current", "currently", "today", "yesterday", "this week", "this month", "now", "who won", "ഇന്ന്", "ഇപ്പോൾ").any(::has)
    // A maintained office-holder page may have been published years ago.
    // Ask for the incumbent, not exclusively for news published this month.
    private val currentRoleLookup = (isCurrentIncumbentQuery(userPlain) || FactualResearch.isCasting(userPlain)) &&
        !Regex("(?i)\\b(news|announced|appointed|became|election|elected|resigned)\\b").containsMatchIn(userPlain)
    val useNews = relative || years.any { it.toInt() >= today.year - 1 }
    val searchText = searchOverride ?: (expandedSearchTerms + years +
        (if (FactualResearch.isCasting(userPlain)) listOf("actor", "cast") else emptyList()) +
        if (relative && years.isEmpty() && !currentRoleLookup) setOf(today.year.toString()) else emptySet()).distinct().joinToString(" ").ifBlank { plain.trim() }.take(400)
    val earliest: LocalDate? = when {
        currentRoleLookup -> null
        has("today") || has("ഇന്ന്") -> today
        has("yesterday") -> today.minusDays(1)
        listOf("latest", "recent", "recently", "current", "currently", "now", "this week", "this month", "ഇപ്പോൾ").any(::has) -> today.minusDays(30)
        else -> null
    }
    val freshnessFilter: String? = when {
        currentRoleLookup -> null
        // Forecast pages roll forward without publishing a new news article.
        ConversationalResearch.isWeather(userPlain) -> null
        has("today") || has("yesterday") || has("ഇന്ന്") -> "d"
        has("this week") -> "w"
        earliest != null -> "m"
        else -> null
    }
    fun relevant(text: String): Boolean {
        val found = words(text)
        if (years.isNotEmpty() && !found.containsAll(years)) return false
        val matches = terms.count { it in found }
        return terms.isEmpty() || matches >= if (terms.size <= 3) terms.size else kotlin.math.ceil(terms.size * .65).toInt()
    }
    fun candidateRelevant(text: String, routingHint: String = ""): Boolean {
        val titleWords = words(text)
        val combined = if (routingHint.isBlank()) titleWords else words("$text $routingHint")
        val reportedYears = titleWords.filter { it.matches(Regex("(?:19|20)\\d{2}")) }.toSet()
        if (years.isNotEmpty() && reportedYears.isNotEmpty() && !reportedYears.containsAll(years)) return false
        return terms.isEmpty() || terms.any { it in combined }
    }
    fun currentEnough(date: String): Boolean {
        val parsed = ResearchQuery.date(date) ?: return earliest == null
        return parsed <= today.plusDays(1) && (earliest == null || parsed >= earliest)
    }
    fun score(text: String): Int {
        val found = words(text)
        return terms.count { it in found } * 3 + years.count { it in found } * 2
    }
    /** Deterministic BM25F-style passage score with IDF across candidate blocks and bigram proximity bonus. */
    fun bm25Score(text: String, docFreqs: Map<String, Int>, totalDocs: Int, avgWords: Double): Int {
        if (terms.isEmpty() && years.isEmpty()) return 0
        val freqs = wordFrequencies(text)
        val docLen = freqs.values.sum().coerceAtLeast(1)
        val k1 = 1.2
        val b = 0.75
        var total = 0.0
        for (term in terms + years) {
            val tf = freqs[term] ?: 0
            if (tf == 0) continue
            val df = (docFreqs[term] ?: 1).coerceAtLeast(1)
            val idf = ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5)).coerceAtLeast(0.4)
            val normTf = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * (docLen / avgWords.coerceAtLeast(1.0))))
            total += idf * normTf * 4.0
        }
        return total.toInt() + score(text)
    }
    fun isPrediction(text: String): Boolean = ("won" in lower || "winner" in lower || "result" in lower) &&
        listOf("who will win", "predictions", "what to expect", "could win", "may win").any { it in text.lowercase() }
}
