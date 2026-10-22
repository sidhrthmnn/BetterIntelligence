package com.example.grounding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.InetAddress
import java.net.UnknownHostException
import org.json.JSONArray
import org.json.JSONObject

/** Checked at DNS resolution time, including every manually followed redirect. */
object PublicWebDns : Dns {
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        return when (b.size) {
            4 -> b[0] !in setOf(0, 10, 127) && b[0] < 224 &&
                !(b[0] == 100 && b[1] in 64..127) && !(b[0] == 169 && b[1] == 254) &&
                !(b[0] == 172 && b[1] in 16..31) && !(b[0] == 192 && b[1] in setOf(0, 168)) &&
                !(b[0] == 198 && b[1] in 18..19)
            16 -> b[0] and 0xfe != 0xfc && !(b[0] == 0x20 && b[1] == 1 && b[2] == 0x0d && b[3] == 0xb8)
            else -> false
        }
    }
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw UnknownHostException("Public website required")
        return addresses
    }
}

data class WebDocument(val url: String, val text: String, val contentType: String)
fun interface WebFetcher { suspend fun fetch(url: String): WebDocument }

/** A bounded reader, not an autonomous browser: no scripts, cookies, forms or sign-in. */
class PublicWebResearch(private val fetcher: WebFetcher = SearchHttp()) : SearchProvider {
    private data class Candidate(val url: String, val title: String, val date: String)
    companion object {
        private fun feed(rss: String): List<Candidate> = Jsoup.parse(rss, "", Parser.xmlParser())
            .select("channel > item").mapNotNull { item ->
                val link = item.selectFirst("link")?.text()?.trim().orEmpty()
                val parsed = runCatching { link.toHttpUrl() }.getOrNull() ?: return@mapNotNull null
                // Read the actual publisher, never the HTTP news tracking wrapper.
                val target = if (parsed.host in setOf("www.bing.com", "bing.com") && parsed.encodedPath == "/news/apiclick.aspx")
                    parsed.queryParameter("url").orEmpty() else link
                target.takeIf(GroundingPolicy::publicHttpsUrl)?.let {
                    Candidate(it, item.selectFirst("title")?.text().orEmpty(), item.selectFirst("pubDate")?.text().orEmpty())
                }
            }.distinctBy { it.url }.take(12)
        fun candidates(rss: String): List<String> = feed(rss).map { it.url }

        private fun pageDate(doc: org.jsoup.nodes.Document): Pair<String, String> {
            val dates = mutableListOf<Pair<String, String>>()
            for ((key, kind) in listOf("article:published_time" to "PUBLISHED", "datePublished" to "PUBLISHED",
                "article:modified_time" to "MODIFIED", "dateModified" to "MODIFIED")) {
                doc.select("meta[property=$key], meta[name=$key], time[itemprop=$key]").forEach {
                    dates += (it.attr("content").ifBlank { it.attr("datetime") }) to kind
                }
            }
            fun collect(value: Any?, depth: Int = 0) {
                if (depth > 5) return
                when (value) {
                    is JSONObject -> {
                        for ((key, kind) in listOf("datePublished" to "PUBLISHED", "dateModified" to "MODIFIED"))
                            value.optString(key).takeIf { it.isNotBlank() }?.let { dates += it to kind }
                        collect(value.opt("@graph"), depth + 1)
                    }
                    is JSONArray -> (0 until minOf(value.length(), 20)).forEach { collect(value.opt(it), depth + 1) }
                }
            }
            doc.select("script[type=application/ld+json]").take(6).forEach {
                val json = it.data().take(50_000)
                runCatching { collect(if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json)) }
            }
            return dates.firstOrNull { it.second == "PUBLISHED" && ResearchQuery.date(it.first) != null }
                ?: dates.firstOrNull { ResearchQuery.date(it.first) != null } ?: ("" to "")
        }

        fun article(page: WebDocument, id: Int, question: String): EvidenceSource? {
            if (page.contentType.substringBefore(';').lowercase() !in setOf("text/html", "application/xhtml+xml", "text/plain")) return null
            val doc = Jsoup.parse(page.text, page.url)
            val date = pageDate(doc)
            // Boilerplate, embedded code and hidden UI are never prompt evidence.
            doc.select("script, style, noscript, iframe, form, nav, footer, header, aside, [hidden], [aria-hidden=true]").remove()
            val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
            val query = ResearchQuery(question)
            val paragraphs = root.select("p, h1, h2, h3, li").map { it.text().trim() }.filter { it.length >= 12 }
                .flatMap { it.split(Regex("(?<=[.!?])\\s+(?=[\\p{Lu}\\p{N}])")) }.distinct().take(300)
            // Highest-relevance sentences first, so a small context retains the answer.
            val ranked = paragraphs.withIndex().sortedWith(compareByDescending<IndexedValue<String>> {
                query.score(it.value)
            }.thenBy { it.index }).take(6).map { it.value }
            val text = (if (ranked.isEmpty()) root.text() else ranked.joinToString("\n")).trim().take(2200)
            if (text.length < 80 || text.contains("verify you are human", true) || text.contains("enable javascript and cookies", true)) return null
            return EvidenceSource(id, doc.title().ifBlank { page.url.toHttpUrl().host }.take(200),
                page.url, text, System.currentTimeMillis(), updatedAt = date.first, kind = "WEB_PAGE", dateKind = date.second)
        }
    }

    override suspend fun search(question: String): List<EvidenceSource> = withContext(Dispatchers.IO) {
        require(!GroundingPolicy.containsCredential(question)) { "Do not search for credentials" }
        val supplied = Regex("https?://[^\\s<>]+").findAll(question)
            .map { it.value.trimEnd('.', ',', ')', ']', '?') }.toList()
        // Never turn a rejected private/local URL into a public search query.
        require(supplied.all(GroundingPolicy::publicHttpsUrl)) { "Use a public HTTPS link" }
        val explicit = supplied.distinct().take(3)
        val query = ResearchQuery(question)
        suspend fun discover(news: Boolean): List<Candidate> {
            // Public search feed, no account/key or proxy server. This interface
            // may be rate limited or change; failure must remain visibly ungrounded.
            val search = (if (news) "https://www.bing.com/news/search" else "https://www.bing.com/search").toHttpUrl().newBuilder()
                .addQueryParameter("format", "rss").addQueryParameter("q", query.searchText).build()
            return withTimeoutOrNull(6_000) { try {
                feed(fetcher.fetch(search.toString()).text).filter {
                    (it.title.isBlank() || query.candidateRelevant(it.title)) && !query.isPrediction(it.title) &&
                        (!news || query.currentEnough(it.date))
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } } ?: emptyList()
        }
        // A feed can return many weak title matches while ignoring the rest of
        // the query. Compare both feeds before spending the bounded page budget.
        var links = if (explicit.isNotEmpty()) explicit.map { Candidate(it, "", "") } else {
            val preferred = async { discover(query.useNews) }
            val alternative = async { discover(!query.useNews) }
            (preferred.await() + alternative.await()).distinctBy { it.url }
                .sortedByDescending { query.score(it.title) }
        }
        links = links.distinctBy { it.url.toHttpUrl().host.removePrefix("www.") }.take(6)
        val reads = Semaphore(3)
        links.map { candidate -> async {
            try { reads.withPermit { withTimeoutOrNull(8_000) {
                article(fetcher.fetch(candidate.url), 0, question)?.let { source ->
                    val dated = if (source.updatedAt.isBlank() && candidate.date.isNotBlank())
                        source.copy(updatedAt = candidate.date, dateKind = "SEARCH_REPORTED") else source
                    if (explicit.isNotEmpty() || (query.relevant(dated.title + " " + dated.excerpt) &&
                        query.currentEnough(dated.updatedAt))) dated else null
                }
            } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null } // A blocked/unreadable page is never a citation.
        } }.awaitAll().filterNotNull().distinctBy { it.url }
            .sortedWith(compareByDescending<EvidenceSource> { query.score(it.title + " " + it.excerpt) }
                .thenByDescending { ResearchQuery.date(it.updatedAt) ?: java.time.LocalDate.MIN })
            .take(3).mapIndexed { index, source -> source.copy(id = index + 1) }
    }
}
