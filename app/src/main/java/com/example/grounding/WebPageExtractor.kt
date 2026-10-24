package com.example.grounding

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Extracts short, contiguous passages from actual page bodies. Metadata and
 * discovery snippets never substitute for a page's evidence text.
 */
object WebPageExtractor {
    private val visibleDate = Regex("(?i)(published|updated|last updated|posted)(?:\\s+on)?\\s*[:|]?\\s*((?:[A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4})|(?:\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4})|(?:\\d{4}-\\d{2}-\\d{2}))")
    private val blocked = Regex("(?i)verify (?:that )?you are human|enable javascript and cookies|just a moment|access denied|sign in to continue|subscribe to (?:continue|read)|captcha")
    private val boilerplate = "script, style, noscript, iframe, form, nav, footer, aside, [hidden], [inert], [aria-hidden=true], [role=banner], [role=navigation], [role=dialog], [class*=cookie], [class*=advert], [id*=cookie], [class*=newsletter], [class*=social-share], [style*=display:none], [style*='display: none'], [style*=visibility:hidden]"

    fun article(page: WebDocument, id: Int, question: String): EvidenceSource? {
        if (!GroundingPolicy.publicHttpsUrl(page.url)) return null
        val type = page.contentType.substringBefore(';').trim().lowercase()
        if (type !in setOf("text/html", "application/xhtml+xml", "text/plain")) return null
        if (snippetDenied(page.robotsTag)) return null
        val doc = Jsoup.parse(if (type == "text/plain") "" else page.text, page.url)
        val policies = listOf(page.robotsTag) + doc.select("meta[name=robots], meta[name=betterintelligence]").map { it.attr("content") }
        if (policies.any(::snippetDenied)) return null
        val snippetLimit = policies.flatMap { policy -> Regex("(?i)max-snippet\\s*:\\s*(\\d+)").findAll(policy).map { it.groupValues[1].toIntOrNull() ?: 0 }.toList() }.minOrNull() ?: 2200
        val query = ResearchQuery(question)
        val metadataDate = reportedDate(doc, query)
        doc.select(boilerplate).remove()
        doc.select("[class*=devsite-banner], [class*=site-banner]").remove()
        // Keep article headers: their subject/date often disambiguates the answer.
        doc.select("body > header").remove()
        val articles = doc.select("article, #mw-content-text, .markdown-body").filter { it.text().length >= 200 }
        val root = articles.maxByOrNull { contentScore(it) }
            ?: doc.select("main, [role=main]").maxByOrNull { contentScore(it) } ?: doc.body()
        // Some publishers put bylines in a bare <em>/<time> after malformed <p>
        // markup. Keep these visible dates, rather than substituting URL dates
        // or global site banners for the article's publication/update line.
        val dateLines = root.select("p, em, time, [class*=byline]").map { it.text().trim() }
            .filter { it.length <= 220 && visibleDate.containsMatchIn(it) }.distinct().take(3)
        val visible = dateLines.flatMap { line -> visibleDate.findAll(line).mapNotNull { match ->
            ResearchQuery.date(match.groupValues[2])?.takeIf { it <= query.today.plusDays(1) }?.let {
                it.toString() to if (match.groupValues[1].contains("updat", true)) "MODIFIED" else "PUBLISHED"
            }
        }.toList() }
        val dates = (visible + listOf(metadataDate)).filter { ResearchQuery.date(it.first) != null }
        val date = (if (query.earliest != null) dates.maxByOrNull { ResearchQuery.date(it.first)!! }
            else dates.firstOrNull { it.second == "PUBLISHED" } ?: dates.firstOrNull()) ?: ("" to "")
        val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title()).ifBlank { root.selectFirst("h1")?.text() ?: page.url.toHttpUrl().host }.take(200)
        val body = if (type == "text/plain") page.text else root.text()
        if (blocked.containsMatchIn(title) || (body.length < 1500 && blocked.containsMatchIn(body))) return null
        val blocks = if (type == "text/plain") page.text.split(Regex("\\n\\s*\\n|\\n")) else root.select("p, h1, h2, h3, h4, li, pre, tr, dd, dt")
            .filter { node -> node.parents().takeWhile { it != root }.none { it.tagName() in setOf("li", "pre", "tr") } }
            .map { it.text() }
        val clean = blocks.map { it.replace(Regex("[\\t ]+"), " ").trim().take(1800) }
            .filter { it.length >= 12 }.distinct().take(350)
        val usable = clean.ifEmpty { listOf(body.trim().take(2200)) }
        // Score windows, not isolated sentences; preserve nearby names, negatives,
        // units and table headers in the same order as the publisher wrote them.
        val windows = usable.indices.map { start ->
            val end = minOf(start + 3, usable.size)
            start until end to query.score(usable.subList(start, end).joinToString(" "))
        }.sortedWith(compareByDescending<Pair<IntRange, Int>> { it.second }.thenBy { it.first.first })
        val selected = sortedSetOf<Int>()
        var size = 0
        for ((window, _) in windows) {
            val extra = window.filter { it !in selected }
            val bytes = extra.sumOf { usable[it].length + 1 }
            if (selected.isNotEmpty() && (size + bytes > 2200 || selected.size + extra.size > 8)) continue
            selected.addAll(extra); size += bytes
            if (selected.size >= 7 || size >= 1800) break
        }
        val text = (dateLines + selected.map { usable[it] }).distinct().joinToString("\n").take(minOf(2200, snippetLimit)).trim()
        if (text.length < 80) return null
        return EvidenceSource(id, title, HtmlDiscovery.canonicalUrl(page.url), text, System.currentTimeMillis(), date.first, "WEB_PAGE", date.second)
    }

    /** At most one useful same-origin child may be followed by the caller. */
    fun relevantLinks(page: WebDocument, question: String): List<ResearchCandidate> {
        val query = ResearchQuery(question)
        val base = page.url.toHttpUrl()
        val doc = Jsoup.parse(page.text, page.url)
        doc.select(boilerplate).remove()
        return doc.select("main a[href], article a[href], [role=main] a[href]").mapNotNull { link ->
            val url = base.resolve(link.attr("href")) ?: return@mapNotNull null
            if (url.host != base.host || !GroundingPolicy.publicHttpsUrl(url.toString()) || url.encodedPath == base.encodedPath ||
                Regex("(?i)\\.(?:pdf|zip|png|jpg|mp4|xml)$").containsMatchIn(url.encodedPath)) return@mapNotNull null
            val title = link.text().trim()
            if (!query.relevant(title) || title.length < 12) return@mapNotNull null
            ResearchCandidate(HtmlDiscovery.canonicalUrl(url.toString()), title)
        }.distinctBy { it.url }.sortedByDescending { query.score(it.title) }.take(2)
    }

    private fun contentScore(node: Element): Int = node.select("p, pre, tr").sumOf { it.text().length } - node.select("a").sumOf { it.text().length / 2 }
    private fun snippetDenied(value: String): Boolean = Regex("(?i)(?:^|[,;\\s])(?:nosnippet|none|max-snippet\\s*:\\s*0)(?:$|[,;\\s])").containsMatchIn(value)

    private fun reportedDate(doc: Document, query: ResearchQuery): Pair<String, String> {
        val dates = mutableListOf<Pair<String, String>>()
        for ((key, kind) in listOf("article:published_time" to "PUBLISHED", "datePublished" to "PUBLISHED", "article:modified_time" to "MODIFIED", "dateModified" to "MODIFIED"))
            doc.select("meta[property=$key], meta[name=$key], time[itemprop=$key]").forEach {
                dates += it.attr("content").ifBlank { it.attr("datetime") } to kind
            }
        var nodes = 0
        fun collect(value: Any?, depth: Int = 0) {
            if (depth > 5 || ++nodes > 100) return
            when (value) {
                is JSONObject -> {
                    for ((key, kind) in listOf("datePublished" to "PUBLISHED", "dateModified" to "MODIFIED"))
                        value.optString(key).takeIf { ResearchQuery.date(it) != null }?.let { dates += it to kind }
                    collect(value.opt("@graph"), depth + 1); collect(value.opt("mainEntity"), depth + 1)
                }
                is JSONArray -> (0 until minOf(value.length(), 20)).forEach { collect(value.opt(it), depth + 1) }
            }
        }
        doc.select("script[type=application/ld+json]").take(6).forEach {
            val json = it.data().take(50_000)
            runCatching { collect(if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json)) }
        }
        val valid = dates.filter { ResearchQuery.date(it.first)?.let { d -> d <= query.today.plusDays(1) } == true }
        return (if (query.earliest != null) valid.maxByOrNull { ResearchQuery.date(it.first)!! }
            else valid.firstOrNull { it.second == "PUBLISHED" } ?: valid.firstOrNull()) ?: ("" to "")
    }
}
