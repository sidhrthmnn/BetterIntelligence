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
    fun session(maxRequests: Int): WebFetcher = this
    // Production transport invokes the guard BEFORE every redirect target is read.
    suspend fun fetchPage(url: String, permitted: suspend (String) -> Boolean): WebDocument {
        check(permitted(url)) { "Publisher does not permit this read" }
        return fetch(url).also { check(it.url == url || permitted(it.url)) }
    }
}

/** Bounded local page reading with HTML and optional anonymous link discovery.
 * No cloud model, API key, proxy, account, scripts, cookies or background crawling.
 */
class PublicWebResearch(private val fetcher: WebFetcher = SearchHttp(), private val discoveryOverride: DiscoveryProvider? = null,
    private val backupAllowed: suspend () -> Boolean = { false }) : SearchProvider {
    private val robots = ResearchRobots(fetcher)
    companion object {
        fun candidates(html: String): List<String> = HtmlDiscovery.links(WebDocument(
            "https://html.duckduckgo.com/html/", html, "text/html")).map { it.url }
        fun article(page: WebDocument, id: Int, question: String): EvidenceSource? = WebPageExtractor.article(page, id, question)
    }

    override suspend fun search(question: String): List<EvidenceSource> = research(question) {}.sources

    override suspend fun research(question: String, progress: (ResearchStage) -> Unit): ResearchOutcome = withContext(Dispatchers.IO) {
        progress(ResearchStage.UNDERSTANDING)
        require(!GroundingPolicy.containsCredential(question)) { "Do not search for credentials" }
        val plan = ResearchPlanner.plan(question)
        val transport = fetcher.session(24)
        val permitted: suspend (String) -> Boolean = { robots.allowed(it, transport) }
        val explicit = plan.explicitUrls
        val query = ResearchQuery(question)
        val discovery = discoveryOverride ?: FreeSearchDiscovery(HtmlSearchDiscovery(transport, permitted), MwmblDiscovery(transport, permitted), backupAllowed)
        var discoveryFailed = false
        var blocked = false
        var discoveryTimedOut = false
        val readTimeouts = java.util.concurrent.atomic.AtomicInteger()
        val failedReads = java.util.concurrent.atomic.AtomicInteger()
        suspend fun discover(search: String, broad: Boolean): List<ResearchCandidate>? {
            val result = withTimeoutOrNull(5_000) {
                try { discovery.discover(ResearchQuery(question, searchOverride = search), broad) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE) }
            } ?: run { discoveryTimedOut = true; return null }
            if (result.state in setOf(DiscoveryState.BLOCKED, DiscoveryState.UNAVAILABLE)) {
                discoveryFailed = true; blocked = true; return null
            }
            return result.candidates.filter { query.candidateRelevant(it.title, it.routingHint) && !query.isPrediction(it.title) &&
                !FactualResearch.speculativeCasting(question, it.title) }
        }

        val links = if (explicit.isNotEmpty()) explicit.map { ResearchCandidate(it, "") } else {
            progress(ResearchStage.SEARCHING)
            val candidates = mutableListOf<ResearchCandidate>()
            // At most three discovery pages in total, including reformulation.
            // Sequential discovery stops immediately on a challenge/denial.
            var searches = 0
            for (search in plan.searches) {
                val result = discover(search, false); searches++
                candidates += result.orEmpty()
                if (result == null || blocked) break
            }
            if (candidates.isEmpty() && !blocked && !discoveryFailed && searches < 3) {
                val reformulated = if (query.freshnessFilter != null) plan.searches.first()
                    else if (FactualResearch.isCasting(question)) "${plan.searches.first()} official cast" else "${plan.searches.first()} documentation"
                candidates += discover(reformulated, query.freshnessFilter != null).orEmpty()
            }
            candidates
        }
        val seen = mutableSetOf<String>()
        val domains = mutableMapOf<String, Int>()
        val unique = links.distinctBy { HtmlDiscovery.canonicalUrl(it.url) }
        val ordered = unique
            .sortedWith(compareBy<ResearchCandidate> { candidate ->
                // Prefer the first result from each publisher before a second.
                unique.indexOfFirst { domain(it.url) == domain(candidate.url) } != unique.indexOf(candidate)
            }.thenByDescending {
                val combined = "${it.title} ${it.routingHint}".trim()
                plan.coverage(combined).size * 15 + query.score(it.title) * 2 + query.score(combined) + EvidenceRanking.primaryRelevance(it.url, question)
            })
            .filter { domains.merge(domain(it.url), 1, Int::plus)!! <= 2 }.take(8)
        val reads = Semaphore(2)
        val childReads = java.util.concurrent.atomic.AtomicInteger(1)
        val sources = mutableListOf<EvidenceSource>()
        if (ordered.isNotEmpty()) progress(ResearchStage.READING)
        // Read in waves: stop after enough evidence instead of fetching every result.
        for (wave in ordered.chunked(4)) {
            val fetched = wave.map { candidate -> async {
                reads.withPermit {
                    var finished = false
                    val result = withTimeoutOrNull(7_000) {
                        try {
                            val page = transport.fetchPage(candidate.url, permitted)
                            val source = article(page, 0, question)
                            fun acceptable(value: EvidenceSource) = EvidenceRanking.acceptable(value, plan)
                            if (source != null && acceptable(source)) source
                            else if (childReads.getAndDecrement() > 0) {
                                // A landing/hub page may link to the actual article or benchmark sub-page.
                                val child = WebPageExtractor.relevantLinks(page, question).firstOrNull()
                                child?.let { article(transport.fetchPage(it.url, permitted), 0, question)?.takeIf(::acceptable) }
                            } else null
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { failedReads.incrementAndGet(); null }
                        .also { finished = true }
                    }
                    if (!finished) readTimeouts.incrementAndGet()
                    result
                }
            } }.awaitAll().filterNotNull()
            fetched.forEach { if (seen.add(it.url)) sources += it }
            val selected = EvidenceRanking.select(sources, plan)
            val covered = selected.flatMap { plan.coverage(it.excerpt) }.toSet()
            val simple = plan.focuses.size == 1 && ResearchIntent.COMPARISON !in plan.intents
            if ((selected.size >= 3 || simple && selected.size >= 1 && explicit.isNotEmpty()) && covered.size == plan.focuses.size) break
        }
        progress(ResearchStage.COMPARING)
        val selected = EvidenceRanking.select(sources, plan)
        ResearchOutcome(selected, when {
            selected.isNotEmpty() -> null
            discoveryTimedOut || readTimeouts.get() > 0 -> ResearchFailure.TIMEOUT
            ordered.isEmpty() && discoveryFailed -> ResearchFailure.DISCOVERY_UNAVAILABLE
            ordered.isNotEmpty() && failedReads.get() >= ordered.size -> ResearchFailure.INACCESSIBLE
            else -> ResearchFailure.INSUFFICIENT
        }, EvidenceRanking.conflicts(selected))
    }

    private fun domain(url: String): String = url.toHttpUrl().topPrivateDomain() ?: url.toHttpUrl().host
}
