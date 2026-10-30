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
    private val boilerplate = "script, style, noscript, iframe, svg, canvas, form, nav, footer, aside:not(.infobox), [hidden], [inert], [role=banner], [role=navigation], [role=dialog], [class*=advert], [class*=newsletter], [class*=social-share], [style*=display:none], [style*='display: none'], [style*=visibility:hidden]"
    private val cookieOverlaySelector = "[class*=cookie], [id*=cookie], [class*=consent], [id*=consent], [class*=gdpr], [id*=gdpr], [class*=onetrust], [id*=onetrust], [class*=cmp-], [id*=cmp-], [class*=sp_message], [id*=sp_message], [class*=cybot], [id*=cybot], .fc-consent-root, #usercentrics-root"
    private val cookieTextPattern = Regex("(?i)\\b(we use cookies|accept(?: all)? cookies|manage cookies|cookie preferences|cookie settings|consent to cookies|reject non-essential|privacy and cookie policy|cookies on the bbc|take control of your data)\\b")
    private val utilityHeading = Regex("(?i)^(search(\\s+for\\s+a\\s+location)?|find\\s+a\\s+forecast|navigation|menu|accessibility(\\s+links)?|skip\\s+to.*|share|more|related|cookie.*)$")

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
        // A non-JavaScript reader should see the publisher's noscript fallback.
        // Apple and other catalogues put their actual product cards here.
        // This only retains static text/markup; scripts/iframes are still removed.
        doc.select("noscript").forEach { it.unwrap() }
        doc.select(boilerplate).remove()
        doc.select(".reflist, .mw-references-wrap, .references, [class*=weather-watchers], [class*=WeatherWatchers], [class*=socialShare]").remove()
        doc.select(cookieOverlaySelector).filter { el ->
            el.tagName() !in setOf("html", "body", "main", "article") &&
                el.selectFirst("main, [role=main], article, h1") == null
        }.forEach { it.remove() }
        doc.select("[class*=devsite-banner], [class*=site-banner]").remove()
        // Keep article headers: their subject/date often disambiguates the answer.
        doc.select("body > header").remove()
        val explicitContent = doc.selectFirst("#mw-content-text, .markdown-body")?.takeIf { it.text().length >= 200 }
        val articles = doc.select("article").filter { it.text().length >= 200 }
        val bestArticle = articles.maxByOrNull { contentScore(it) }
        val bestMain = doc.select("main, [role=main], #main-content, #content").filter { it.text().length >= 200 }.maxByOrNull { contentScore(it) }
        val root = explicitContent ?: when {
            bestArticle != null && bestMain != null ->
                if (articles.size == 1 && contentScore(bestArticle) >= contentScore(bestMain) * 0.55) bestArticle else bestMain
            bestMain != null -> bestMain
            bestArticle != null -> bestArticle
            else -> doc.body()
        }
        if (type != "text/plain") {
            root.select("img[alt]").forEach { img ->
                val alt = img.attr("alt").trim()
                if (alt.length in 3..60 && !Regex("(?i)^(icon|image|logo|photo|avatar|placeholder|banner|arrow|close)\\b").containsMatchIn(alt)) {
                    img.appendText(" $alt ")
                }
            }
        }
        // Some publishers put bylines in a bare <em>/<time> after malformed <p>
        // markup. Keep these visible dates, rather than substituting URL dates
        // or global site banners for the article's publication/update line.
        val dateLines = root.select("p, em, time, [class*=byline]").map { it.text().trim() }
            .filter { it.length <= 220 && visibleDate.containsMatchIn(it) }.distinct().take(3)
        // A signed letter/report date is document content, not publication time.
        // Keep its distinct provenance and reject biographical dates.
        val signatureDates = root.select("p:has(br)").map { it.text().trim() }.filter { line ->
            line.length <= 300 && Regex("(?i)chief executive officer|chairman|signed by|author").containsMatchIn(line) &&
                !Regex("(?i)\\b(?:born|died|since|appointed|joined)\\b").containsMatchIn(line)
        }.mapNotNull { line ->
            Regex("([A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4})\\s*$").find(line)?.value?.trim()?.let { value ->
                ResearchQuery.date(value)?.takeIf { it <= query.today.plusDays(1) }?.toString()?.let { it to "DOCUMENT_REPORTED" }
            }
        }.take(2)
        val visible = dateLines.flatMap { line -> visibleDate.findAll(line).mapNotNull { match ->
            ResearchQuery.date(match.groupValues[2])?.takeIf { it <= query.today.plusDays(1) }?.let {
                it.toString() to if (match.groupValues[1].contains("updat", true)) "MODIFIED" else "PUBLISHED"
            }
        }.toList() }
        val dates = (visible + listOf(metadataDate) + signatureDates).filter { ResearchQuery.date(it.first) != null }
        val date = (if (query.earliest != null) dates.maxByOrNull { ResearchQuery.date(it.first)!! }
            else dates.firstOrNull { it.second == "PUBLISHED" } ?: dates.firstOrNull()) ?: ("" to "")
        val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title()).ifBlank { root.selectFirst("h1")?.text() ?: page.url.toHttpUrl().host }.take(200)
        val body = if (type == "text/plain") page.text else root.text()
        if (blocked.containsMatchIn(title) || (body.length < 1500 && blocked.containsMatchIn(body))) return null
        val headingStack = sortedMapOf<Int, String>()
        root.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() && !utilityHeading.matches(it) }?.let { headingStack[1] = it }
        fun currentHeading(): String = headingStack.values.filter { it.isNotBlank() }.distinct().joinToString(" › ")
        var pendingTerm = ""
        val blockTags = setOf("p", "h1", "h2", "h3", "h4", "ul", "ol", "pre", "table", "dd", "dt")
        val blocks = if (type == "text/plain") page.text.split(Regex("\\n\\s*\\n|\\n")) else root.select("p, h1, h2, h3, h4, ul, ol, pre, table, dd, dt, div, section")
            .filter { node ->
                val tag = node.tagName()
                if (tag in setOf("div", "section")) {
                    val ownLen = node.text().trim().length
                    ownLen in 15..360 &&
                        node.parents().takeWhile { it != root }.none { it.tagName() in setOf("ul", "ol", "pre", "table", "p", "dd", "dt") } &&
                        node.selectFirst("p, h1, h2, h3, h4, ul, ol, pre, table, dd, dt") == null &&
                        node.children().none { child -> child.tagName() in setOf("div", "section") && child.text().trim().length >= 25 }
                } else {
                    node.parents().takeWhile { it != root }.none { it.tagName() in if (node.tagName() == "table") setOf("table") else setOf("ul", "ol", "pre", "table", "p") }
                }
            }
            .flatMap { node ->
                when {
                    node.tagName().matches(Regex("h[1-4]")) -> {
                        val level = node.tagName().drop(1).toIntOrNull() ?: 2
                        val hText = node.text().trim()
                        if (!utilityHeading.matches(hText)) {
                            headingStack.keys.filter { it >= level }.toList().forEach { headingStack.remove(it) }
                            headingStack[level] = hText
                        }
                        pendingTerm = ""
                        emptyList()
                    }
                    node.tagName() == "dt" -> {
                        pendingTerm = node.text().trim()
                        emptyList()
                    }
                    node.tagName() == "dd" -> {
                        val heading = currentHeading()
                        val combined = if (pendingTerm.isNotBlank()) "$pendingTerm: ${node.text().trim()}" else node.text().trim()
                        pendingTerm = ""
                        listOf(listOf(heading, combined).filter { it.isNotBlank() }.distinct().joinToString("\n"))
                    }
                    node.tagName() in setOf("ul", "ol") -> {
                        // Link lists are discovery/navigation, not article prose.
                        // Repeated related-story titles otherwise outrank the report.
                        val length = node.text().length.coerceAtLeast(1)
                        if (node.select("a[href]").sumOf { it.text().length }.toDouble() / length > 0.8) return@flatMap emptyList()
                        val heading = currentHeading()
                        val items = node.select("> li").ifEmpty { node.select("li") }
                            .map { item -> item.clone().also { it.select("table").remove() }.text().replace(Regex("[\\t ]+"), " ").trim() }
                            .filter { it.length >= 4 }
                        if (items.isEmpty()) emptyList()
                        else {
                            val grouped = mutableListOf<String>()
                            var current = StringBuilder()
                            for (item in items) {
                                if (current.isEmpty()) {
                                    current.append(item)
                                } else if (current.length + item.length + 3 <= 360 && item.length < 140) {
                                    current.append(" · ").append(item)
                                } else {
                                    grouped += listOf(heading, current.toString()).filter { it.isNotBlank() }.distinct().joinToString("\n")
                                    current = StringBuilder(item)
                                }
                            }
                            if (current.isNotEmpty()) {
                                grouped += listOf(heading, current.toString()).filter { it.isNotBlank() }.distinct().joinToString("\n")
                            }
                            grouped
                        }
                    }
                    node.tagName() == "table" -> {
                        val heading = currentHeading()
                        val caption = node.selectFirst("caption")?.text().orEmpty()
                        HtmlEvidenceTable.rows(node).map { row ->
                            listOf(heading, caption, row)
                                .filter { it.isNotBlank() }.distinct().joinToString("\n")
                        }
                    }
                    else -> {
                        val heading = currentHeading()
                        listOf(listOf(heading, node.text()).filter { it.isNotBlank() }.distinct().joinToString("\n"))
                    }
                }
            }
        val weatherBlocks = if (ConversationalResearch.isWeather(question)) WeatherPageEvidence.blocks(root, title, page.url) else emptyList()
        val priceRequest = Regex("(?i)\\b(?:prices?|costs?|how much)\\b").containsMatchIn(question)
        val financeRequest = Regex("(?i)\\b(?:finance|financing|loan|APR|interest|repayment|example|monthly)\\b").containsMatchIn(question)
        val prices = if (priceRequest) priceCards(root, title, question, page.url) else emptyList()
        val clean = (weatherBlocks + prices + blocks).flatMap { block ->
            val normalized = block.replace(Regex("[\\u200B\\uFEFF]"), "").replace(Regex("[\\t ]+"), " ").trim()
            if (normalized.length <= 1800) listOf(normalized) else ClaimVerification.sentences(normalized)
        }
            .filter { it.length >= 12 && !FactualResearch.headlineOnly(it, title) && !(it.length < 450 && cookieTextPattern.containsMatchIn(it)) &&
                (!priceRequest || financeRequest || !ClaimVerification.financeExample(it)) }
            .distinct().take(350)
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

    /** Bind short visible price fields to their nearest bounded product card.
     * Standalone prices and amounts in general finance examples have no owner. */
    private fun priceCards(root: Element, title: String, question: String, url: String): List<String> {
        val required = ResearchQuery(question).terms - ResearchQuery.words("price cost how much buy shop") - EvidenceRanking.localeWords(url)
        if (required.isEmpty()) return emptyList()
        val currency = Regex("[£$€₹]\\s*\\d")
        return root.select("[itemprop=price], [class*=price]").asSequence().filter { node ->
            node.text().length <= 180 && currency.containsMatchIn(node.text())
        }.mapNotNull { price ->
            generateSequence(price) { it.parent() }.take(5).map { it.text().trim() }.firstOrNull { text ->
                text.length in 20..800 && required.all { it in ResearchQuery.words(text) } && !ClaimVerification.financeExample(text)
            }?.let { "$title\n$it" }
        }.distinct().take(6).toList()
    }

    private fun focused(blocks: List<String>, dates: List<String>, question: String, budget: Int): String {
        val plan = ResearchPlanner.plan(question)
        val query = ResearchQuery(question)
        val candidates = blocks.filter { it.isNotBlank() && !FactualResearch.incomplete(it.substringAfter('\n', it)) &&
            !FactualResearch.headlineOnly(it, if ('\n' in it) it.substringBefore('\n') else "") }.distinct().take(350)
        if (candidates.isEmpty()) return dates.joinToString("\n").take(budget)
        val docFreqs = mutableMapOf<String, Int>()
        var totalWords = 0
        val candidateWords = candidates.map { block ->
            val w = ResearchQuery.words(block)
            totalWords += w.size
            w.forEach { token -> docFreqs[token] = (docFreqs[token] ?: 0) + 1 }
            w
        }
        val avgWords = (totalWords.toDouble() / candidates.size).coerceAtLeast(1.0)
        val selected = sortedSetOf<Int>()
        val covered = mutableSetOf<Int>()
        val subjects = mutableSetOf<Int>()
        fun mentioned(index: Int): Set<Int> {
            val words = candidateWords[index]
            return plan.entities.indices.filter { eIdx -> ResearchQuery.words(plan.entities[eIdx]).all { it in words } }.toSet()
        }
        var size = dates.joinToString("\n").length
        val isIncumbentQuery = ResearchQuery.isCurrentIncumbentQuery(question)
        val isWeatherQuery = ConversationalResearch.isWeather(question)
        val weatherDataPattern = Regex("(?i)(?:\\b\\d{1,2}\\s*°\\s*[CF]?\\b|\\b\\d{1,2}\\s+degrees?\\s+(?:celsius|fahrenheit)\\b|\\b(?:feels\\s+like|humidity|wind\\s+speed|precipitation|uv\\s+index|visibility|air\\s+quality|pressure|sunny|cloudy|overcast|clear|scattered\\s+clouds|light\\s+rain|heavy\\s+rain|showers|thunderstorm|drizzle|mist|fog)\\b)")
        val currentWeatherTimingPattern = Regex("(?i)\\b(today|tonight|now|current|currently|this\\s+morning|this\\s+afternoon|this\\s+evening|next\\s+hour|observed\\s+at|right\\s+now|minimum\\s+temperature|maximum\\s+temperature|daytime|nighttime)\\b")
        val presentPattern = Regex("(?i)\\b(incumbent|present(?!\\s*-\\s*day)|till\\s+date|to\\s+date|current(?!\\s+(?:affairs|gk|general\\s+knowledge))|currently|assumed\\s+office|serving\\s+since|in\\s+office|is\\s+the\\s+(?:current\\s+|present\\s+|\\d+(?:st|nd|rd|th)\\s+and\\s+current\\s+)?(?:chief\\s+minister|prime\\s+minister|president|governor|mayor|ceo|cm|pm))\\b")
        val pastObituaryPattern = Regex("(?i)\\b(passed\\s+away|died|former|was\\s+the\\s+\\d+(?:st|nd|rd|th)?|served\\s+a\\s+total\\s+of|\\b(?:19\\d{2}|20[0-2]\\d)\\s*[-–—]\\s*(?:19\\d{2}|20[01]\\d|202[0-5])\\b)\\b")
        val hasIncumbentCandidate = isIncumbentQuery && candidates.any { presentPattern.containsMatchIn(it) }
        val remaining = candidates.indices.filterNot { idx ->
            hasIncumbentCandidate && pastObituaryPattern.containsMatchIn(candidates[idx]) && !presentPattern.containsMatchIn(candidates[idx])
        }.toMutableSet()
        fun roleScore(block: String): Int {
            val role = Regex("(?i)\\b(mayor|president|minister|governor|ceo)\\b").find(question)?.value ?: return 0
            val section = block.substringBefore('\n').substringAfterLast('›')
            if (!Regex("(?i)\\b${Regex.escape(role)}\\b").containsMatchIn(section)) return 0
            val qualifier = Regex("(?i)\\b(lord|lady|deputy|vice|former|assistant)\\b").find(section)?.value
            return if (qualifier != null && !Regex("(?i)\\b${Regex.escape(qualifier)}\\b").containsMatchIn(question)) -60 else 25
        }
        while (remaining.isNotEmpty() && selected.size < 7) {
            val index = remaining.maxBy { i ->
                val blockText = candidates[i]
                val incumbentScore = if (isIncumbentQuery) {
                    val hasPresent = presentPattern.containsMatchIn(blockText)
                    val hasPast = pastObituaryPattern.containsMatchIn(blockText)
                    when {
                        hasPresent -> 28
                        hasPast -> -18
                        else -> 0
                    }
                } else 0
                val weatherScore = if (isWeatherQuery && weatherDataPattern.containsMatchIn(blockText)) {
                    val temperature = Regex("(?i)-?\\d+(?:\\.\\d+)?\\s*°\\s*[CF]\\b|\\d+\\s+degrees?\\s+(?:celsius|fahrenheit)").containsMatchIn(blockText)
                    (if (temperature) 70 else 10) + if (currentWeatherTimingPattern.containsMatchIn(blockText)) 30 else 0
                } else 0
                val castingBody = blockText.substringAfter('\n', blockText)
                val castingScore = if (FactualResearch.isCasting(question)) when {
                    FactualResearch.speculativeCasting(question, castingBody) -> -60
                    FactualResearch.castingRelation(castingBody) -> 60
                    else -> 0
                } else 0
                val definitionScore = if (FactualResearch.definitionTopic(question) != null) when {
                    FactualResearch.definitionLine(castingBody, question) -> FactualResearch.definitionQuality(castingBody, question)
                    Regex("(?i)\\b(?:important|importance|benefits?)\\b").containsMatchIn(castingBody) -> -25
                    else -> 0
                } else 0
                query.bm25Score(blockText, docFreqs, candidates.size, avgWords) +
                    (plan.coverage(blockText) - covered).size * 15 +
                    mentioned(i).size * 6 + (mentioned(i) - subjects).size * 20 +
                    incumbentScore + weatherScore + castingScore + definitionScore + roleScore(blockText) + FactualResearch.answerFocusScore(blockText, question) + SourceBrief.roleReportScore(blockText, question) +
                    if (PublicQuestionIntent.statusValue(blockText, question)) 100 else 0
            }
            remaining.remove(index)
            val block = candidates[index]
            if (size + block.length + 2 > budget && selected.isNotEmpty()) continue
            selected += index; covered += plan.coverage(block); subjects += mentioned(index); size += block.length + 2
            // Nearby blocks preserve subject/pronoun qualifications. Only add
            // a neighbour after covering the requested facets where possible.
            if (covered.size == plan.focuses.size && selected.size >= 3 && subjects.size == plan.entities.size && size >= minOf(650, budget / 2)) break
        }
        val orderedSelected = when {
            hasIncumbentCandidate -> selected.sortedWith(compareByDescending<Int> { roleScore(candidates[it]) }
                .thenByDescending { presentPattern.containsMatchIn(candidates[it]) }.thenBy { it })
            isWeatherQuery -> selected.sortedWith(compareByDescending<Int> {
                Regex("(?i)(?:observed temperature|today forecast: high)").containsMatchIn(candidates[it])
            }.thenBy { it })
            else -> selected.sortedWith(compareByDescending<Int> { FactualResearch.answerFocusScore(candidates[it], question) }.thenBy { it })
        }
        return EvidencePacket.completeExcerpt((dates + orderedSelected.map { candidates[it] }).distinct().joinToString("\n\n"), budget)
    }

    /** At most one useful same-origin child may be followed by the caller. */
    fun relevantLinks(page: WebDocument, question: String): List<ResearchCandidate> {
        val plan = ResearchPlanner.plan(question)
        val query = ResearchQuery(question)
        val base = page.url.toHttpUrl()
        val doc = Jsoup.parse(page.text, page.url)
        val pageTitle = doc.title()
        doc.select(boilerplate).remove()
        return doc.select("main a[href], article a[href], [role=main] a[href]").mapNotNull { link ->
            val url = base.resolve(link.attr("href")) ?: return@mapNotNull null
            if (url.host != base.host || !GroundingPolicy.publicHttpsUrl(url.toString()) || url.encodedPath == base.encodedPath ||
                Regex("(?i)\\.(?:pdf|zip|png|jpg|mp4|xml)$").containsMatchIn(url.encodedPath)) return@mapNotNull null
            // A matching country in an author/tag URL does not make it a news article.
            if (url.pathSegments.any { it.lowercase() in setOf("author", "authors", "tag", "tags", "topics", "category", "categories", "privacy", "contact") }) return@mapNotNull null
            val title = link.text().trim()
            if (title.length < 8) return@mapNotNull null
            val pathText = url.pathSegments.joinToString(" ") { it.replace('-', ' ').replace('_', ' ') }
            val combined = "$title $pathText"
            val coversFacet = plan.coverage(combined).isNotEmpty() &&
                (plan.entities.isEmpty() || plan.entities.any { entity -> ResearchQuery.words(entity).all { it in ResearchQuery.words("$combined $pageTitle") } })
            if ((!query.relevant(title) || title.length < 12) && !coversFacet) return@mapNotNull null
            ResearchCandidate(HtmlDiscovery.canonicalUrl(url.toString()), title, pathText)
        }.distinctBy { it.url }.sortedByDescending { plan.coverage("${it.title} ${it.routingHint}").size * 15 + query.score("${it.title} ${it.routingHint}") }.take(2)
    }

    private fun contentScore(node: Element): Int = node.select("p, pre, tr, dd").sumOf { it.text().length } - node.select("a").sumOf { it.text().length / 2 }
    private fun snippetDenied(value: String): Boolean = Regex("(?i)(?:^|[,;\\s])(?:nosnippet|none|max-snippet\\s*:\\s*0)(?:$|[,;\\s])").containsMatchIn(value)

    private fun reportedDate(doc: Document, query: ResearchQuery): Pair<String, String> {
        val dates = mutableListOf<Pair<String, String>>()
        doc.select("p, time").map { it.text().trim() }.filter { it.length <= 100 }.forEach { line ->
            visibleDate.find(line)?.let { match -> dates += match.groupValues[2] to
                if (match.groupValues[1].contains("updat", true)) "MODIFIED" else "PUBLISHED" }
        }
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
