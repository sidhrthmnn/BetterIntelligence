package com.example.grounding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.util.Locale

/**
 * First-party, on-device discovery and grounding. This is not a general web index:
 * it reads public publisher feeds/homepages, follows topic-relevant links, and
 * extracts actual public pages. No search provider, search API, API key or proxy.
 *
 * The user's question is NEVER sent to a remote search endpoint. URLs in a
 * question are visited only after public HTTPS validation and robots checks.
 */
class IndependentWebResearch(
    private val fetcher: WebFetcher = SearchHttp(),
    private val seeds: List<PublisherSeed> = PublisherSeed.defaults
) : SearchProvider {
    private val robots = ResearchRobots(fetcher)

    data class Candidate(val url: String, val title: String, val published: String = "", val depth: Int = 0)

    override suspend fun search(question: String): List<EvidenceSource> = withContext(Dispatchers.IO) {
        require(!GroundingPolicy.containsCredential(question)) { "Do not research credentials" }
        val explicit = Regex("https?://[^\\s<>]+").findAll(question).map {
            it.value.trimEnd('.', ',', ')', ']', '?')
        }.distinct().toList()
        require(explicit.all(GroundingPolicy::publicHttpsUrl)) { "Only public HTTPS sources are permitted" }
        val query = ResearchQuery(question)
        val direct = explicit.isNotEmpty()
        val chosen = if (direct) emptyList() else chooseSeeds(question, seeds)

        // Strict per-question network budgets: mobile connections, battery, and
        // publisher servers must be treated respectfully. No background crawling.
        val frontier = ArrayDeque<Candidate>()
        val queued = HashSet<String>()
        fun enqueue(candidate: Candidate) {
            val normalized = candidate.url.substringBefore('#')
            if (candidate.depth > 2 || !GroundingPolicy.publicHttpsUrl(normalized)) return
            if (queued.add(normalized)) frontier.addLast(candidate.copy(url = normalized))
        }
        explicit.take(3).forEach { enqueue(Candidate(it, it)) }
        if (!direct) {
            for (seed in chosen) {
                val page = safeFetch(seed.url) ?: continue
                val found = discover(page, seed.url, query)
                found.sortedByDescending { candidateScore(it, query) }.take(12).forEach(::enqueue)
            }
        }

        val read = ArrayList<EvidenceSource>()
        val visitedHosts = HashMap<String, Int>()
        var attempts = 0
        while (frontier.isNotEmpty() && attempts < 12 && read.size < 4) {
            val candidate = frontier.removeFirst()
            val host = candidate.url.toHttpUrlOrNull()?.host.orEmpty()
            // Avoid filling the evidence budget with multiple near-identical pages
            // from the same publisher. Direct user-supplied URLs are exempt.
            if (!direct && (visitedHosts[host] ?: 0) >= 3) continue
            attempts++
            val page = safeFetch(candidate.url) ?: continue
            visitedHosts[host] = (visitedHosts[host] ?: 0) + 1
            val evidence = PublicWebResearch.article(page, read.size + 1, question)
            if (evidence != null) {
                val enriched = if (evidence.updatedAt.isBlank() && candidate.published.isNotBlank())
                    evidence.copy(updatedAt = candidate.published, dateKind = "PUBLISHER_FEED") else evidence
                val score = query.score(enriched.title + " " + enriched.excerpt)
                val enough = score >= if (query.terms.size <= 2) 3 else 6
                if (direct || (enough && query.currentEnough(enriched.updatedAt))) {
                    if (read.none { it.url == enriched.url || sameText(it.excerpt, enriched.excerpt) })
                        read.add(enriched)
                }
            }
            // Follow only same-host links, selected by their anchor text/path,
            // with an explicit hop limit. Do not treat search snippets as proof.
            if (candidate.depth < 2 && (read.isEmpty() || read.size < 3) &&
                page.contentType.lowercase().contains("html")) {
                discover(page, page.url, query)
                    .filter { it.url.toHttpUrlOrNull()?.host == host }
                    .sortedByDescending { candidateScore(it, query) }
                    .take(5).forEach { enqueue(it.copy(depth = candidate.depth + 1)) }
            }
        }

        read.sortedWith(compareByDescending<EvidenceSource> {
            query.score(it.title + " " + it.excerpt) +
                (if (it.dateKind == "PUBLISHED") 1 else 0)
        }).take(3).mapIndexed { index, source -> source.copy(id = index + 1) }
    }

    private suspend fun safeFetch(url: String): WebDocument? {
        if (!GroundingPolicy.publicHttpsUrl(url)) return null
        return try {
            withTimeoutOrNull(5_000) {
                if (!robots.allowed(url)) return@withTimeoutOrNull null
                val fetched = fetcher.fetch(url)
                // Redirect targets must obey their own publisher's robots policy.
                if (!GroundingPolicy.publicHttpsUrl(fetched.url) || !robots.allowed(fetched.url)) null
                else fetched
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun chooseSeeds(question: String, sources: List<PublisherSeed>): List<PublisherSeed> {
            val words = ResearchQuery.words(question)
            val ranked = sources.map { source ->
                source to source.topics.count { topic -> topic in words }
            }
            // At least one broadly relevant publisher, plus matching specialist
            // publications. Avoid contacting every publisher for every question.
            val specialist = ranked.filter { it.second > 0 }.sortedByDescending { it.second }
                .map { it.first }.take(3)
            val general = sources.firstOrNull { it.topics.isEmpty() }
            return (specialist + listOfNotNull(general))
                .distinctBy { it.url }.take(4)
        }

        fun discover(page: WebDocument, base: String, query: ResearchQuery): List<Candidate> {
            val mime = page.contentType.substringBefore(';').lowercase(Locale.ROOT)
            val xml = mime.contains("xml") || mime.contains("rss") || mime.contains("atom") ||
                page.text.take(200).contains("<rss") || page.text.take(200).contains("<feed")
            val doc = Jsoup.parse(page.text.take(1_000_000), base,
                if (xml) Parser.xmlParser() else Parser.htmlParser())
            val discovered = ArrayList<Candidate>()
            fun add(link: String, title: String, date: String = "") {
                val absolute = doc.baseUri().toHttpUrlOrNull()?.resolve(link)?.toString()
                    ?: link.takeIf(GroundingPolicy::publicHttpsUrl) ?: return
                if (!GroundingPolicy.publicHttpsUrl(absolute)) return
                val url = absolute.substringBefore('#')
                if (url.endsWith(".jpg", true) || url.endsWith(".png", true) ||
                    url.endsWith(".pdf", true) || url.endsWith(".zip", true)) return
                discovered.add(Candidate(url, title.take(240), date.take(100)))
            }
            if (xml) {
                doc.select("item, entry").take(250).forEach { entry ->
                    val link = entry.selectFirst("link[href]")?.attr("href")
                        ?: entry.selectFirst("link")?.text().orEmpty()
                    val title = entry.selectFirst("title")?.text().orEmpty()
                    val date = entry.selectFirst("pubDate, published, updated")?.text().orEmpty()
                    if (link.isNotBlank()) add(link, title, date)
                }
                doc.select("urlset > url").take(300).forEach { item ->
                    val link = item.selectFirst("loc")?.text().orEmpty()
                    if (link.isNotBlank()) add(link, link.substringAfterLast('/').replace('-', ' '),
                        item.selectFirst("lastmod")?.text().orEmpty())
                }
            } else {
                doc.select("main a[href], article a[href], a[href]").take(300).forEach { anchor ->
                    val label = anchor.text().trim().ifBlank { anchor.attr("href").substringAfterLast('/') }
                    if (label.length in 8..220) add(anchor.attr("href"), label)
                }
            }
            return discovered.distinctBy { it.url }
                .filter { candidateScore(it, query) > 0 }
                .sortedByDescending { candidateScore(it, query) }
                .take(30)
        }

        fun candidateScore(candidate: Candidate, query: ResearchQuery): Int {
            val label = candidate.title + " " + candidate.url.substringAfter("://")
                .substringAfter('/').replace('-', ' ').replace('_', ' ')
            val topic = query.score(label)
            // A matching title matters more than a generic sitemap page path.
            val titleScore = query.score(candidate.title)
            val fresh = if (query.relative && query.currentEnough(candidate.published) &&
                candidate.published.isNotBlank()) 2 else 0
            return topic + titleScore + fresh
        }

        private fun sameText(a: String, b: String): Boolean {
            fun normalized(s: String) = s.lowercase(Locale.ROOT).replace(Regex("\\W+"), " ").take(160)
            return normalized(a) == normalized(b)
        }
    }
}

/** Public publisher endpoints, not search engines. Users may extend the catalogue. */
data class PublisherSeed(val url: String, val topics: Set<String> = emptySet()) {
    companion object {
        val defaults = listOf(
            PublisherSeed("https://feeds.bbci.co.uk/news/rss.xml"),
            PublisherSeed("https://feeds.bbci.co.uk/news/world/rss.xml",
                setOf("world", "war", "government", "president", "election", "country", "international")),
            PublisherSeed("https://feeds.bbci.co.uk/news/business/rss.xml",
                setOf("business", "economy", "market", "stock", "finance", "company", "inflation")),
            PublisherSeed("https://feeds.bbci.co.uk/news/technology/rss.xml",
                setOf("technology", "ai", "model", "android", "google", "apple", "software", "phone")),
            PublisherSeed("https://www.theverge.com/rss/index.xml",
                setOf("technology", "ai", "model", "android", "phone", "computer", "gaming", "game")),
            PublisherSeed("https://www.nasa.gov/feed/",
                setOf("space", "nasa", "moon", "mars", "rocket", "astronomy", "planet")),
            PublisherSeed("https://www.sciencedaily.com/rss/all.xml",
                setOf("science", "research", "physics", "biology", "health", "medicine", "study")),
            PublisherSeed("https://www.nature.com/nature.rss",
                setOf("science", "research", "physics", "biology", "health", "medicine")),
            PublisherSeed("https://developer.android.com/",
                setOf("android", "kotlin", "jetpack", "compose", "gradle", "mobile", "app")),
            PublisherSeed("https://kotlinlang.org/docs/home.html",
                setOf("kotlin", "coroutine", "programming", "compiler")),
            PublisherSeed("https://www.gov.uk/",
                setOf("uk", "britain", "united", "kingdom", "visa", "law", "policy", "tax"))
        )
    }
}
