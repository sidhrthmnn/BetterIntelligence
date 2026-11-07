package com.example.grounding

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/** Bounded document collection before query selection. Publisher data is untrusted,
 * including JSON-LD; normal subject/date/value/citation checks still apply. */
object DocumentEvidence {
    const val MAX_CHARS = 64_000
    const val MAX_BLOCKS = 1024
    private fun json(text: String): Any? {
        if (text.length > 262_144) return null
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 32) return null }
                '}', ']' -> depth--
            }
        }
        return runCatching { if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text) }.getOrNull()
    }
    fun collect(blocks: List<String>): List<String> {
        var remaining = MAX_CHARS
        return blocks.asSequence().filter { it.isNotBlank() }.distinct().take(MAX_BLOCKS).mapNotNull {
            // Keep complete sentence units, not a clipped prefix of a large block.
            val value = if (it.length <= remaining) it else EvidencePacket.completeExcerpt(it, remaining)
            if (value.isBlank()) null else value.also { remaining -= it.length.coerceAtMost(remaining) }
        }.toList()
    }

    fun normalize(page: WebDocument): WebDocument? {
        val type = page.contentType.substringBefore(';').trim().lowercase()
        if (type == "application/json" || type.endsWith("+json")) {
            if (page.text.length > 1_048_576) return null
            val value = json(page.text) ?: return null
            val rows = mutableListOf<String>()
            fun visit(item: Any?, label: String, depth: Int) {
                if (depth > 6 || rows.size >= 256) return
                when (item) {
                    is JSONObject -> item.keys().asSequence().take(64).forEach { key ->
                        if (key.length <= 120 && !Regex("(?i)token|password|secret|cookie|script|instruction|prompt").containsMatchIn(key)) visit(item.opt(key), "$label $key".trim(), depth + 1)
                    }
                    is JSONArray -> (0 until minOf(item.length(), 32)).forEach { visit(item.opt(it), label, depth + 1) }
                    null, JSONObject.NULL -> Unit
                    else -> {
                        // Keep a whole field before passage selection. A prefix can
                        // omit a trailing condition and change the publisher's claim.
                        val row = "$label: $item"
                        if (row.length <= MAX_CHARS) rows += row
                    }
                }
            }
            val identity = (value as? JSONObject)?.let { it.optString("name").ifBlank { it.optString("model") } }.orEmpty().take(180)
            visit(value, identity, 0)
            return page.copy(text = collect(rows).joinToString("\n"), contentType = "text/plain")
        }
        return page
    }

    fun structured(doc: Document): List<String> {
        val rows = mutableListOf<String>()
        var nodes = 0
        fun visit(value: Any?, depth: Int) {
            if (depth > 5 || ++nodes > 128) return
            when (value) {
                is JSONArray -> (0 until minOf(value.length(), 32)).forEach { visit(value.opt(it), depth + 1) }
                is JSONObject -> {
                    val type = value.optString("@type")
                    // Do not turn arbitrary site configuration, breadcrumbs or
                    // executable scripts into evidence. Never execute JSON-LD.
                    if (type in setOf("Article", "NewsArticle", "BlogPosting", "TechArticle", "Product", "Review", "Dataset", "FAQPage")) {
                        val name = value.optString("headline").ifBlank { value.optString("name") }.take(180)
                        for (key in listOf("articleBody", "description")) value.optString(key).takeIf { it.length in 40..MAX_CHARS }?.let { rows += "$name\n$it" }
                        val properties = value.optJSONArray("additionalProperty")
                        for (i in 0 until minOf(properties?.length() ?: 0, 64)) {
                            val property = properties?.optJSONObject(i) ?: continue
                            val label = property.optString("name")
                            val number = property.optString("value")
                            val unit = property.optString("unitText")
                            if (label.isNotBlank() && label.length <= 120 && number.isNotBlank() && number.length <= 1200 && unit.length <= 60) rows += "$name\n$label: $number $unit"
                        }
                    }
                    for (key in listOf("@graph", "mainEntity", "mainEntityOfPage", "itemReviewed")) visit(value.opt(key), depth + 1)
                }
            }
        }
        doc.select("script[type=application/ld+json]").take(8).forEach { script ->
            if (script.data().length <= MAX_CHARS) runCatching {
                visit(json(script.data()), 0)
            }
        }
        return collect(rows)
    }
}
