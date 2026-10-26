package com.example.grounding

import org.jsoup.nodes.Element

/** Resolve bounded HTML cell spans before attaching column labels. Never infer
 * values from whitespace, or shift a GPU row into the device/CPU columns. */
object HtmlEvidenceTable {
    private data class Span(val text: String, val left: Int)
    fun rows(table: Element): List<String> {
        val pending = mutableMapOf<Int, Span>()
        val resolved = mutableListOf<Pair<List<String>, Boolean>>()
        val sourceRows = table.select("tr").filter { it.parents().firstOrNull { p -> p.tagName() == "table" } == table }.take(84)
        sourceRows.forEach { row ->
            val values = sortedMapOf<Int, String>()
            pending.toMap().forEach { (column, span) ->
                values[column] = span.text
                if (span.left <= 1) pending.remove(column) else pending[column] = span.copy(left = span.left - 1)
            }
            var column = 0
            val cells = row.children().filter { it.tagName() in setOf("th", "td") }
            cells.take(20).forEach { cell ->
                while (column in values && column < 20) column++
                val width = (cell.attr("colspan").toIntOrNull() ?: 1).coerceIn(1, 20)
                val height = (cell.attr("rowspan").toIntOrNull() ?: 1).coerceIn(1, 80)
                repeat(minOf(width, (20 - column).coerceAtLeast(0))) {
                    val text = cell.text().trim().take(300)
                    values[column] = text
                    if (height > 1) pending[column] = Span(text, height - 1)
                    column++
                }
            }
            val heading = row.parents().any { it.tagName() == "thead" } || cells.isNotEmpty() && cells.all { it.tagName() == "th" }
            resolved += (0..(values.keys.maxOrNull() ?: -1)).map { values[it].orEmpty() } to heading
        }
        if (resolved.isEmpty()) return emptyList()
        var headerCount = resolved.takeWhile { it.second }.size.coerceAtMost(4)
        if (headerCount == 0 && Regex("(?i)\\b(?:model|ram|memory|device|platform|backend|prefill|decode|latency|name|minister|tenure|party|version|price)\\b").containsMatchIn(resolved.first().first.joinToString(" ")))
            headerCount = 1
        val columns = resolved.maxOf { it.first.size }
        val headers = (0 until columns).map { column -> resolved.take(headerCount).mapNotNull { it.first.getOrNull(column) }.filter(String::isNotBlank).distinct().joinToString(" / ") }
        return resolved.drop(headerCount).take(80).map { (values, _) ->
            values.mapIndexed { column, value ->
                val header = headers.getOrNull(column).orEmpty()
                val unit = Regex("(?i)\\((GB|GiB|MB|MiB|ms|seconds?|tk/s|tokens/s)\\)").find(header)?.groupValues?.get(1)
                val suffix = if (unit != null && Regex("-?\\d+(?:\\.\\d+)?").matches(value)) " $unit" else ""
                if (header.isBlank()) value else "$header: $value$suffix"
            }.joinToString(" | ")
        }
    }
}
