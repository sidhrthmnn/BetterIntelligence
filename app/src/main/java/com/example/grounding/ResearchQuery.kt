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
            "ഇന്ന് ഇപ്പോൾ എന്താണ് ആരാണ്").split(' ').toSet()
        private val wordPattern = Regex("[\\p{L}\\p{M}][\\p{L}\\p{M}\\p{N}]*|\\p{N}+(?:\\.\\p{N}+)*")
        private val englishWord = Regex("[a-z]{5,}")
        // Mild English inflection matching, without damaging non-Latin words.
        private fun stem(value: String) = if (value.matches(englishWord)) when {
            value.endsWith("ies") -> value.dropLast(3) + "y"
            value.endsWith("ing") && value.length >= 7 -> value.dropLast(3)
            value.endsWith("ed") && value.length >= 6 && !value.endsWith("eed") -> value.dropLast(2)
            value.endsWith("s") && !value.endsWith("ss") -> value.dropLast(1)
            else -> value
        } else value
        fun words(text: String) = wordPattern.findAll(text.lowercase()).map { stem(it.value) }.toSet()
        fun wordFrequencies(text: String): Map<String, Int> {
            val counts = mutableMapOf<String, Int>()
            wordPattern.findAll(text.lowercase()).forEach { match ->
                val token = stem(match.value)
                counts[token] = (counts[token] ?: 0) + 1
            }
            return counts
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
    private val plain = question.replace(Regex("https?://\\S+"), " ")
    val years = Regex("\\b(?:19|20)\\d{2}\\b").findAll(plain).map { it.value }.toSet()
    private val rawTerms = wordPattern.findAll(plain.lowercase()).map { it.value }
        .filter { stem(it) !in normalizedStop && it !in years }.take(14).toList()
    val terms = rawTerms.map(::stem).toSet()
    private val lower = plain.lowercase()
    private fun has(phrase: String) = Regex("(?<![\\p{L}\\p{M}])${Regex.escape(phrase)}(?![\\p{L}\\p{M}])").containsMatchIn(lower)
    val relative = listOf("latest", "recent", "recently", "current", "currently", "today", "yesterday", "this week", "this month", "now", "who won", "ഇന്ന്", "ഇപ്പോൾ").any(::has)
    val useNews = relative || years.any { it.toInt() >= today.year - 1 }
    val searchText = searchOverride ?: (rawTerms + years + if (relative && years.isEmpty()) setOf(today.year.toString()) else emptySet()).distinct().joinToString(" ").ifBlank { plain.trim() }.take(400)
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
