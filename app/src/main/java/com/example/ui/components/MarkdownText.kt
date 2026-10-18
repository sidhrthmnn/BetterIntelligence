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

sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
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
                else -> {
                    val paragraph = StringBuilder()
                    while (index < lines.size && !lines[index].startsWith("```") && !lines[index].startsWith("#") &&
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
fun MarkdownText(text: String) {
    val blocks = remember(text) { ChatMarkdown.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block -> when (block) {
            is MarkdownBlock.Heading -> Text(ChatMarkdown.inline(block.text), style = MaterialTheme.typography.titleLarge)
            is MarkdownBlock.Paragraph -> Text(ChatMarkdown.inline(block.text), style = MaterialTheme.typography.bodyLarge)
            is MarkdownBlock.Code -> Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState()).padding(16.dp)) {
                Text(block.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
            is MarkdownBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
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
