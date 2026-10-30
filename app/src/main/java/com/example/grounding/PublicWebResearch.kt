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
    suspend fun postJson(url: String, json: String, permitted: suspend (String) -> Boolean): WebDocument {
        check(permitted(url)) { "Search permission is off" }
        return fetch(url)
    }
    fun session(maxRequests: Int): WebFetcher = this
    // Production transport invokes the guard BEFORE every redirect target is read.
    suspend fun fetchPage(url: String, permitted: suspend (String) -> Boolean): WebDocument {
        check(permitted(url)) { "Publisher does not permit this read" }
        return fetch(url).also { check(it.url == url || permitted(it.url)) }
    }
}

/** Bounded local page reading with anonymous discovery and real publisher evidence.
 * No cloud model, API key, proxy, account, scripts, cookies or background crawling.
 */
class PublicWebResearch(private val fetcher: WebFetcher = SearchHttp(), private val discoveryOverride: DiscoveryProvider? = null,
    private val discoveryAllowed: suspend () -> Boolean = { true }, private val researchBudgetMs: Long = 26_000) : SearchProvider {
    private val robots = ResearchRobots(fetcher)
    private val discoveryCircuit = DiscoveryCircuit()
    companion object {
        fun article(page: WebDocument, id: Int, question: String): EvidenceSource? = WebPageExtractor.article(page, id, question)
    }

    override suspend fun search(question: String): List<EvidenceSource> = research(question) {}.sources

    override suspend fun research(question: String, progress: (ResearchStage) -> Unit): ResearchOutcome = withContext(Dispatchers.IO) {
        progress(ResearchStage.UNDERSTANDING)
        // Finish with the evidence already read before the outer deadline.
        // Previously a slow final page could discard all successful earlier reads.
        val deadline = System.nanoTime() / 1_000_000 + researchBudgetMs.coerceIn(2_000, 26_000)
        fun remaining() = (deadline - System.nanoTime() / 1_000_000).coerceAtLeast(1)
        require(!GroundingPolicy.containsCredential(question)) { "Do not search for credentials" }
        val plan = ResearchPlanner.plan(question)
        val transport = fetcher.session(24)
        val permitted: suspend (String) -> Boolean = { robots.allowed(it, transport) }
        val explicit = plan.explicitUrls
        val query = ResearchQuery(question)
        var providerCalls = 0
        val discovered = mutableMapOf<String, DiscoveryResult>()
        fun bounded(provider: DiscoveryProvider): DiscoveryProvider = DiscoveryProvider { request, broad ->
            val key = ExaDiscovery.searchText(request, broad).lowercase(java.util.Locale.ROOT)
            discovered[key] ?: if (providerCalls >= 3) DiscoveryResult(emptyList(), DiscoveryState.EMPTY) else {
                providerCalls++
                provider.discover(request, broad).also { discovered[key] = it }
            }
        }
        val discovery = discoveryOverride ?: bounded(ExaDiscovery(transport, discoveryAllowed))
        var discoveryFailed = false
        var blocked = false
        var discoveryTimedOut = false
        val readTimeouts = java.util.concurrent.atomic.AtomicInteger()
        val failedReads = java.util.concurrent.atomic.AtomicInteger()
        var rounds = 0
        suspend fun discover(search: String, broad: Boolean): List<ResearchCandidate>? {
            if (discoveryOverride == null && !discoveryAllowed()) { discoveryFailed = true; return null }
            if (rounds++ >= 3) return emptyList()
            if (remaining() < 1000) { discoveryTimedOut = true; return null }
            val paused = discoveryCircuit.unavailable()
            val result = if (paused != null) DiscoveryResult(emptyList(), paused) else withTimeoutOrNull(minOf(8_000, remaining())) {
                try { discovery.discover(ResearchQuery(question, searchOverride = search), broad) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE) }
            } ?: DiscoveryResult(emptyList(), DiscoveryState.TIMEOUT)
            if (paused == null) discoveryCircuit.record(result.state)
            if (result.state == DiscoveryState.TIMEOUT) { discoveryTimedOut = true; return null }
            if (result.state in setOf(DiscoveryState.BLOCKED, DiscoveryState.UNAVAILABLE)) {
                discoveryFailed = true; blocked = result.state == DiscoveryState.BLOCKED; return null
            }
            return result.candidates.filter { WeatherPageEvidence.requestedDay(it.title, question) && !SourceBrief.predictionMarket(it.title, it.url, question) && query.candidateRelevant(it.title, it.routingHint) && !query.isPrediction(it.title) &&
                (!FactualResearch.speculativeCasting(question, it.title) || Regex("(?i)screen[- ]?tests?|auditions?|shortlist|not (?:yet )?(?:confirmed|announced)").containsMatchIn(it.title)) }
        }

        val links = if (explicit.isNotEmpty()) explicit.map { ResearchCandidate(it, "") } else {
            progress(ResearchStage.SEARCHING)
            val candidates = mutableListOf<ResearchCandidate>()
            // At most three discovery pages in total, including reformulation.
            // Sequential discovery stops immediately on a challenge/denial.
            var searches = 0
            for (search in plan.searches.take(1)) {
                val result = discover(search, false); searches++
                candidates += result.orEmpty()
                if (result == null || blocked) break
            }
            if (candidates.isEmpty() && !blocked && !discoveryFailed && searches < 3) {
                candidates += discover(plan.searches.first(), true).orEmpty()
            }
            candidates
        }
        val seen = mutableSetOf<String>()
        val attempted = mutableSetOf<String>()
        val domains = mutableMapOf<String, Int>()
        val reads = Semaphore(2)
        val childReads = java.util.concurrent.atomic.AtomicInteger(2)
        val sources = mutableListOf<EvidenceSource>()
        var readCount = 0
        fun enough(): Boolean {
            val selected = EvidenceRanking.select(sources, plan)
            val covered = selected.flatMap { plan.coverage(it.excerpt) }.toSet()
            return (!FactualResearch.needsAnswerFocus(question) || selected.any { FactualResearch.answerFocusScore(it.excerpt, question) >= 65 }) &&
                covered.size == plan.focuses.size && (selected.size >= 2 || selected.size == 1 &&
                (explicit.isNotEmpty() || EvidenceRanking.primaryRelevance(selected.single().url, question) > 0)) && EvidenceRanking.conflicts(selected).isEmpty()
        }
        suspend fun readCandidates(candidates: List<ResearchCandidate>) {
        val unique = candidates.distinctBy { HtmlDiscovery.canonicalUrl(it.url) }.filter { it.url !in attempted }
        val ordered = unique
            .sortedWith(compareBy<ResearchCandidate> { candidate ->
                // Prefer the first result from each publisher before a second.
                unique.indexOfFirst { domain(it.url) == domain(candidate.url) } != unique.indexOf(candidate)
                }.thenByDescending {
                val combined = "${it.title} ${it.routingHint}".trim()
                plan.coverage(combined).size * 15 + query.score(it.title) * 2 + query.score(combined) + EvidenceRanking.primaryRelevance(it.url, question) + EvidenceRanking.weatherPlaceScore(it.title, question)
            })
            .filter { domains.getOrDefault(domain(it.url), 0) < 2 }
            // Reserve reads for one focused reformulation when broad election
            // coverage has not yet supplied the count/status the question needs.
            .take(minOf((8 - readCount).coerceAtLeast(0), if (readCount == 0 && FactualResearch.needsAnswerFocus(question) && plan.searches.size > 1) 4 else 8))
        if (ordered.isNotEmpty()) progress(ResearchStage.READING)
        // Read in waves: stop after enough evidence instead of fetching every result.
        for (wave in ordered.chunked(2)) {
            if (remaining() < 1000) break
            val admitted = wave.filter { attempted.add(it.url) && domains.merge(domain(it.url), 1, Int::plus)!! <= 2 }
            readCount += admitted.size
            val fetched = admitted.map { candidate -> async {
                reads.withPermit {
                    var finished = false
                    val result = withTimeoutOrNull(minOf(6_000, remaining())) {
                        try {
                            val page = transport.fetchPage(candidate.url, permitted)
                            val source = article(page, 0, question)?.copy(discoveryProvider = candidate.provider)
                            fun acceptable(value: EvidenceSource) = EvidenceRanking.acceptable(value, plan)
                            if (source != null && acceptable(source)) source
                            else {
                                // A landing/hub page may link to the actual article or benchmark sub-page.
                                val child = WebPageExtractor.relevantLinks(page, question).firstOrNull()
                                if (child != null && childReads.getAndDecrement() > 0)
                                    article(transport.fetchPage(child.url, permitted), 0, question)
                                        ?.copy(discoveryProvider = candidate.provider)?.takeIf(::acceptable)
                                else null
                            }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { failedReads.incrementAndGet(); null }
                        .also { finished = true }
                    }
                    if (!finished) readTimeouts.incrementAndGet()
                    result ?: candidate.instantAnswer?.takeIf {
                        LegacyTopicEvidence.summaryAllowed(question) && EvidenceRanking.acceptable(it, plan)
                    }
                }
            } }.awaitAll().filterNotNull()
            fetched.forEach { if (seen.add(it.url)) sources += it }
            if (enough()) break
        }
        }
        readCandidates(links)
        // Expand only after actual passages fail to cover the requested facets.
        if (explicit.isEmpty() && !enough() && !blocked && !discoveryFailed && !discoveryTimedOut) {
            for (search in plan.searches.drop(1)) {
                if (readCount >= 8 || remaining() < 1000) break
                progress(ResearchStage.SEARCHING)
                readCandidates(discover(search, false).orEmpty())
                if (enough() || blocked || discoveryFailed || discoveryTimedOut) break
            }
        }
        progress(ResearchStage.COMPARING)
        val selected = EvidenceRanking.select(sources, plan)
        ResearchOutcome(selected, when {
            selected.isNotEmpty() -> null
            discoveryTimedOut || readTimeouts.get() > 0 -> ResearchFailure.TIMEOUT
            readCount == 0 && blocked -> ResearchFailure.BLOCKED
            readCount == 0 && discoveryFailed -> ResearchFailure.DISCOVERY_UNAVAILABLE
            readCount > 0 && failedReads.get() >= readCount -> ResearchFailure.INACCESSIBLE
            else -> ResearchFailure.INSUFFICIENT
        }, EvidenceRanking.conflicts(selected))
    }

    private fun domain(url: String): String = url.toHttpUrl().topPrivateDomain() ?: url.toHttpUrl().host
}
