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
        var heading = ""
        val blocks = if (type == "text/plain") page.text.split(Regex("\\n\\s*\\n|\\n")) else root.select("p, h1, h2, h3, h4, li, pre, table, dd, dt")
            .filter { node -> node.parents().takeWhile { it != root }.none { it.tagName() in setOf("li", "pre", "table", "p") } }
            .flatMap { node ->
                when {
                    node.tagName().matches(Regex("h[1-4]")) -> { heading = node.text(); emptyList() }
                    node.tagName() == "table" -> {
                        val header = node.select("tr").firstOrNull { it.select("th").isNotEmpty() }?.select("th, td")?.joinToString(" | ") { it.text() }.orEmpty()
                        val caption = node.selectFirst("caption")?.text().orEmpty()
                        node.select("tr").filter { it.select("td").isNotEmpty() }.take(80).map { row ->
                            listOf(heading, caption, header, row.select("th, td").joinToString(" | ") { it.text() })
                                .filter { it.isNotBlank() }.distinct().joinToString("\n")
                        }
                    }
                    else -> listOf(listOf(heading, node.text()).filter { it.isNotBlank() }.distinct().joinToString("\n"))
                }
            }
        val clean = blocks.map { it.replace(Regex("[\\t ]+"), " ").trim().take(1800) }
            .filter { it.length >= 12 }.distinct().take(350)
        val usable = clean.ifEmpty { listOf(body.trim().take(2200)) }
        val text = focused(usable, dateLines, question, minOf(2200, snippetLimit)).trim()
        if (text.length < 80) return null
        return EvidenceSource(id, title, HtmlDiscovery.canonicalUrl(page.url), text, System.currentTimeMillis(), date.first, "WEB_PAGE", date.second)
    }

    /** Re-focus a cached excerpt when shrinking it for a small model context.
     * Never add inferred facts or silently drop a table's header/unit. */
    fun focusExcerpt(text: String, question: String, budget: Int): String = focused(
        text.split(Regex("\\n\\s*\\n")).let { blocks -> if (blocks.size > 1) blocks else text.lines().chunked(2).map { it.joinToString("\n") } },
        text.lines().filter { it.length <= 220 && visibleDate.containsMatchIn(it) }.take(2), question, budget)

    private fun focused(blocks: List<String>, dates: List<String>, question: String, budget: Int): String {
        val plan = ResearchPlanner.plan(question)
        val query = ResearchQuery(question)
        val candidates = blocks.filter { it.isNotBlank() }.distinct().take(350)
        val selected = sortedSetOf<Int>()
        val covered = mutableSetOf<Int>()
        val subjects = mutableSetOf<Int>()
        fun mentioned(text: String): Set<Int> {
            val words = ResearchQuery.words(text)
            return plan.entities.indices.filter { index -> ResearchQuery.words(plan.entities[index]).all { it in words } }.toSet()
        }
        var size = dates.joinToString("\n").length
        val remaining = candidates.indices.toMutableSet()
        while (remaining.isNotEmpty() && selected.size < 7) {
            val index = remaining.maxBy { i -> query.score(candidates[i]) + (plan.coverage(candidates[i]) - covered).size * 15 +
                mentioned(candidates[i]).size * 6 + (mentioned(candidates[i]) - subjects).size * 20 }
            remaining.remove(index)
            val block = candidates[index]
            if (size + block.length + 2 > budget && selected.isNotEmpty()) continue
            selected += index; covered += plan.coverage(block); subjects += mentioned(block); size += block.length + 2
            // Nearby blocks preserve subject/pronoun qualifications. Only add
            // a neighbour after covering the requested facets where possible.
            if (covered.size == plan.focuses.size && selected.size >= 3 && subjects.size == plan.entities.size) break
        }
        return (dates + selected.map { candidates[it] }).distinct().joinToString("\n\n").take(budget)
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
