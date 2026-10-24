package com.example.grounding

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** Small deterministic query plan: no second model, private history or credentials. */
class ResearchQuery(val question: String, val today: LocalDate = LocalDate.now()) {
    companion object {
        private val stop = ("a an and are as at be been by can could did do does for from give has have how " +
            "i in into is it me of on or please say should tell than that the their there these they this to " +
            "us was were what when where which who why will with would you your won win winner winners " +
            "latest recent recently current currently today yesterday now new news source sources cite " +
            "verify check fact summarize summary about answer only read find search public topic context " +
            "ഇന്ന് ഇപ്പോൾ എന്താണ് ആരാണ്").split(' ').toSet()
        private val wordPattern = Regex("[\\p{L}\\p{M}\\p{N}]{2,}")
        private val englishWord = Regex("[a-z]{5,}")
        // Mild English inflection matching, without damaging non-Latin words.
        private fun stem(value: String) = if (value.matches(englishWord)) when {
            value.endsWith("ies") -> value.dropLast(3) + "y"
            value.endsWith("s") && !value.endsWith("ss") -> value.dropLast(1)
            else -> value
        } else value
        fun words(text: String) = wordPattern.findAll(text.lowercase()).map { stem(it.value) }.toSet()
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
    private val plain = question.replace(Regex("https?://\\S+"), " ")
    val years = Regex("\\b(?:19|20)\\d{2}\\b").findAll(plain).map { it.value }.toSet()
    private val rawTerms = wordPattern.findAll(plain.lowercase()).map { it.value }
        .filter { stem(it) !in normalizedStop && it !in years }.take(14).toList()
    val terms = rawTerms.map(::stem).toSet()
    private val lower = plain.lowercase()
    private fun has(phrase: String) = Regex("(?<![\\p{L}\\p{M}])${Regex.escape(phrase)}(?![\\p{L}\\p{M}])").containsMatchIn(lower)
    val relative = listOf("latest", "recent", "recently", "current", "currently", "today", "yesterday", "this week", "this month", "now", "who won", "ഇന്ന്", "ഇപ്പോൾ").any(::has)
    val useNews = relative || years.any { it.toInt() >= today.year - 1 }
    val searchText = (rawTerms + years + if (relative && years.isEmpty()) setOf(today.year.toString()) else emptySet()).distinct().joinToString(" ").ifBlank { plain.trim() }.take(400)
    val earliest: LocalDate? = when {
        has("today") || has("ഇന്ന്") -> today
        has("yesterday") -> today.minusDays(1)
        listOf("latest", "recent", "recently", "current", "currently", "now", "this week", "this month", "ഇപ്പോൾ").any(::has) -> today.minusDays(30)
        else -> null
    }
    val freshnessFilter: String? = when {
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
    fun candidateRelevant(text: String): Boolean {
        val found = words(text)
        val reportedYears = found.filter { it.matches(Regex("(?:19|20)\\d{2}")) }.toSet()
        if (years.isNotEmpty() && reportedYears.isNotEmpty() && !reportedYears.containsAll(years)) return false
        return terms.isEmpty() || terms.any { it in found }
    }
    fun currentEnough(date: String): Boolean {
        val parsed = ResearchQuery.date(date) ?: return earliest == null
        return parsed <= today.plusDays(1) && (earliest == null || parsed >= earliest)
    }
    fun score(text: String): Int {
        val found = words(text)
        return terms.count { it in found } * 3 + years.count { it in found } * 2
    }
    fun isPrediction(text: String): Boolean = ("won" in lower || "winner" in lower || "result" in lower) &&
        listOf("who will win", "predictions", "what to expect", "could win", "may win").any { it in text.lowercase() }
}
