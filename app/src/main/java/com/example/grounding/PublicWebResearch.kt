package com.example.grounding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.net.UnknownHostException

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

data class WebDocument(val url: String, val text: String, val contentType: String, val robotsTag: String = "")
fun interface WebFetcher {
    suspend fun fetch(url: String): WebDocument
    // Production transport invokes the guard BEFORE every redirect target is read.
    suspend fun fetchPage(url: String, permitted: suspend (String) -> Boolean): WebDocument {
        check(permitted(url)) { "Publisher does not permit this read" }
        return fetch(url).also { check(it.url == url || permitted(it.url)) }
    }
}

/** Bounded public-page research, implemented locally. No search/grounding APIs,
 * proxy, account, scripts, cookies, forms, sign-in or background crawling.
 */
class PublicWebResearch(private val fetcher: WebFetcher = SearchHttp()) : SearchProvider {
    private val robots = ResearchRobots(fetcher)
    companion object {
        fun candidates(html: String): List<String> = HtmlDiscovery.links(WebDocument(
            "https://html.duckduckgo.com/html/", html, "text/html")).map { it.url }
        fun article(page: WebDocument, id: Int, question: String): EvidenceSource? = WebPageExtractor.article(page, id, question)
    }

    override suspend fun search(question: String): List<EvidenceSource> = withContext(Dispatchers.IO) {
        require(!GroundingPolicy.containsCredential(question)) { "Do not search for credentials" }
        val supplied = Regex("https?://[^\\s<>]+").findAll(question)
            .map { it.value.trimEnd('.', ',', ')', ']') }.toList()
        // Never turn a rejected private/local URL into a public search query.
        require(supplied.all(GroundingPolicy::publicHttpsUrl)) { "Use a public HTTPS link" }
        val explicit = supplied.distinct().take(3)
        val query = ResearchQuery(question)
        suspend fun discover(broad: Boolean): List<ResearchCandidate>? = withTimeoutOrNull(7_000) {
            try {
                val page = fetcher.fetchPage(HtmlDiscovery.searchUrl(query, broad), robots::allowed)
                if (HtmlDiscovery.blocked(page)) return@withTimeoutOrNull null
                HtmlDiscovery.links(page)
                    .filter { query.candidateRelevant(it.title) && !query.isPrediction(it.title) }
                    .sortedByDescending { query.score(it.title) * 10 + primaryScore(it.url) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }

        val links = if (explicit.isNotEmpty()) explicit.map { ResearchCandidate(it, "") } else {
            val narrow = discover(false)
            // At most one broader retry if the date-filtered public page found
            // no candidates. Never retry or bypass a CAPTCHA/blocked interface.
            if (narrow != null && narrow.isEmpty() && query.freshnessFilter != null) discover(true).orEmpty() else narrow.orEmpty()
        }
        val seen = mutableSetOf<String>()
        val domains = mutableMapOf<String, Int>()
        val ordered = links.distinctBy { HtmlDiscovery.canonicalUrl(it.url) }
            .sortedWith(compareBy<ResearchCandidate> { candidate ->
                // Prefer the first result from each publisher before a second.
                links.indexOfFirst { domain(it.url) == domain(candidate.url) } != links.indexOf(candidate)
            }.thenByDescending { query.score(it.title) * 10 + primaryScore(it.url) })
            .filter { domains.merge(domain(it.url), 1, Int::plus)!! <= 2 }.take(8)
        val reads = Semaphore(2)
        val sources = mutableListOf<EvidenceSource>()
        // Read in waves: stop after enough evidence instead of fetching every result.
        for (wave in ordered.chunked(4)) {
            val fetched = wave.map { candidate -> async {
                reads.withPermit {
                    withTimeoutOrNull(9_000) {
                        try {
                            val page = fetcher.fetchPage(candidate.url, robots::allowed)
                            val source = article(page, 0, question)
                            fun acceptable(value: EvidenceSource) = (explicit.isNotEmpty() || query.relevant(value.title + " " + value.excerpt)) && query.currentEnough(value.updatedAt)
                            if (source != null && acceptable(source)) source
                            else if (explicit.isNotEmpty()) {
                                // A supplied landing page may link to the actual article.
                                val child = WebPageExtractor.relevantLinks(page, question).firstOrNull()
                                child?.let { article(fetcher.fetchPage(it.url, robots::allowed), 0, question)?.takeIf(::acceptable) }
                            } else null
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    }
                }
            } }.awaitAll().filterNotNull()
            fetched.forEach { if (seen.add(it.url)) sources += it }
            if (sources.size >= 3) break
        }
        sources.distinctBy { it.excerpt.lowercase().replace(Regex("\\W+"), " ").take(300) }
            .sortedWith(compareByDescending<EvidenceSource> { query.score(it.title + " " + it.excerpt) * 10 + primaryScore(it.url) }
                .thenByDescending { ResearchQuery.date(it.updatedAt) ?: java.time.LocalDate.MIN })
            .take(3).mapIndexed { index, source -> source.copy(id = index + 1) }
    }

    private fun domain(url: String): String = url.toHttpUrl().topPrivateDomain() ?: url.toHttpUrl().host
    private fun primaryScore(url: String): Int {
        val host = url.toHttpUrl().host
        return if (host.endsWith(".gov") || host.endsWith(".gov.uk") || host.endsWith(".edu") || host.endsWith(".ac.uk")) 2 else 0
    }
}
