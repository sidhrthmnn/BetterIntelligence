package com.example.engine

/** Known synthetic arithmetic checks, not answers to user questions. Floating-point
 * backends may choose different correct wording; byte equality is not correctness. */
object BackendValidation {
    data class Probe(val a: Long, val b: Long, val multiply: Boolean) {
        val answer get() = if (multiply) a * b else a + b
        val prompt get() = "What is $a ${if (multiply) "multiplied by" else "plus"} $b? Give only the number."
    }
    val probes = listOf(Probe(17, 6, true), Probe(23, 19, false), Probe(9, 7, true))
    fun correct(probe: Probe, text: String): Boolean {
        if (text.isBlank() || text.length > 600 || Regex("(?i)\\b(?:not|incorrect|wrong|maybe|perhaps|possibly|unable|cannot)\\b|<unused|<think").containsMatchIn(text)) return false
        val wrongOperation = if (probe.multiply) "(?i)\\b(?:plus|minus|divided|subtract|addition|division)\\b|[+/]" else
            "(?i)\\b(?:multiplied|times|minus|divided|subtract|multiplication|division)\\b|[×*/]"
        if (Regex(wrongOperation).containsMatchIn(text)) return false
        val numbers = Regex("(?<![\\p{L}\\d])[-+]?\\d+(?:\\.\\d+)?").findAll(text).map {
            runCatching { it.value.toBigDecimal().stripTrailingZeros().toPlainString() }.getOrDefault("")
        }.toList()
        val allowed = setOf(probe.a.toString(), probe.b.toString(), probe.answer.toString())
        return probe.answer.toString() in numbers && numbers.all { it in allowed } && numbers.groupingBy { it }.eachCount().values.all { it <= 3 }
    }
}
