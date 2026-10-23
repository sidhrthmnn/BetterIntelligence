package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.style.TextDecoration

sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class ListEntry(val marker: String, val text: String) : MarkdownBlock
}

/** Bounded, text-only Markdown. HTML and model-authored URLs never execute. */
object ChatMarkdown {
    fun parse(text: String): List<MarkdownBlock> {
        val lines = text.take(100_000).lines(); val result = mutableListOf<MarkdownBlock>(); var index = 0
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.startsWith("```") -> {
                    val code = StringBuilder(); index++
                    while (index < lines.size && !lines[index].startsWith("```")) { code.append(lines[index++]).append('\n') }
                    result += MarkdownBlock.Code(code.toString().trimEnd()); if (index < lines.size) index++
                }
                line.trimStart().startsWith("|") && index + 1 < lines.size && Regex("[| :\\-]+").matches(lines[index + 1].trim()) -> {
                    val rows = mutableListOf<List<String>>()
                    while (index < lines.size && lines[index].trimStart().startsWith("|")) {
                        val row = lines[index++].trim().trim('|').split('|').take(12).map { it.trim() }
                        if (!row.all { Regex(":?-+:?").matches(it) }) rows += row
                    }
                    result += MarkdownBlock.Table(rows)
                }
                line.startsWith("#") -> { result += MarkdownBlock.Heading(line.trimStart('#', ' ')); index++ }
                line.startsWith("> ") -> { result += MarkdownBlock.Quote(line.removePrefix("> ")); index++ }
                Regex("^\\s*(?:[-*+] |\\d+[.)] ).+").matches(line) -> {
                    val entry = Regex("^\\s*([-*+]|\\d+[.)]) (.*)").find(line)!!
                    result += MarkdownBlock.ListEntry(if (entry.groupValues[1] in listOf("-", "*", "+")) "•" else entry.groupValues[1], entry.groupValues[2]); index++
                }
                line.isBlank() -> index++
                else -> {
                    val paragraph = StringBuilder()
                    while (index < lines.size && lines[index].isNotBlank() && !lines[index].startsWith("> ") &&
                        !Regex("^\\s*(?:[-*+] |\\d+[.)] ).+").matches(lines[index]) && !lines[index].startsWith("```") && !lines[index].startsWith("#") &&
                        !(lines[index].trimStart().startsWith("|") && index + 1 < lines.size && Regex("[| :\\-]+").matches(lines[index + 1].trim()))) {
                        paragraph.append(lines[index++]).append('\n')
                    }
                    if (paragraph.isNotBlank()) result += MarkdownBlock.Paragraph(paragraph.toString().trimEnd())
                }
            }
        }
        return result
    }
    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
        var start = 0
        pattern.findAll(text).forEach { match ->
            append(text.substring(start, match.range.first))
            withStyle(if (match.groupValues[1].isNotEmpty()) SpanStyle(fontWeight = FontWeight.SemiBold) else SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(match.groupValues[1].ifEmpty { match.groupValues[2] })
            }
            start = match.range.last + 1
        }
        append(text.substring(start))
    }
}

@Composable
fun MarkdownText(text: String, sourceIds: Set<Int> = emptySet(), onCitation: (Int) -> Unit = {}) {
    val blocks = remember(text) { ChatMarkdown.parse(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    @Composable fun prose(value: String, heading: Boolean = false) {
        val base = remember(value) { ChatMarkdown.inline(value) }
        val annotated = remember(base, sourceIds, linkColor) { buildAnnotatedString {
            append(base)
            Regex("(?<![\\p{L}\\p{N}_])\\[(\\d+)]").findAll(base.text).forEach { match ->
                val id = match.groupValues[1].toIntOrNull()
                if (id in sourceIds) {
                    addStringAnnotation("source", id.toString(), match.range.first, match.range.last + 1)
                    addStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline), match.range.first, match.range.last + 1)
                }
            }
        } }
        ClickableText(annotated, modifier = Modifier.fillMaxWidth(),
            style = (if (heading) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge).copy(color = MaterialTheme.colorScheme.onSurface),
            onClick = { offset -> annotated.getStringAnnotations("source", offset, offset).firstOrNull()?.item?.toIntOrNull()?.let(onCitation) })
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block -> when (block) {
            is MarkdownBlock.Heading -> prose(block.text, true)
            is MarkdownBlock.Paragraph -> prose(block.text)
            is MarkdownBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                Text("│", color = MaterialTheme.colorScheme.outline); Spacer(Modifier.width(12.dp)); Box(Modifier.weight(1f)) { prose(block.text) }
            }
            is MarkdownBlock.ListEntry -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(block.marker, style = MaterialTheme.typography.bodyLarge); Box(Modifier.weight(1f)) { prose(block.text) }
            }
            is MarkdownBlock.Code -> Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState()).padding(16.dp)) {
                Text(block.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
            is MarkdownBlock.Table -> Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                block.rows.forEachIndexed { index, row ->
                    Row(Modifier.background(if (index == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)) {
                        row.forEach { cell -> Text(ChatMarkdown.inline(cell), Modifier.width(168.dp).padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal) }
                    }
                    HorizontalDivider()
                }
            }
        } }
    }
}
