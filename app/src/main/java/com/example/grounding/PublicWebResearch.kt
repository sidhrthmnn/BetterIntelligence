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
    private val discoveryAllowed: suspend () -> Boolean = { true }, private val researchBudgetMs: Long = 55_000,
    private val timeouts: ResearchTimeouts = ResearchTimeouts(), private val biFallback: Boolean = true) : SearchProvider {
    private val robots = ResearchRobots(fetcher)
    private val discoveryCircuit = DiscoveryCircuit()
    private val biIndex = BiDiscovery(fetcher, robots, discoveryAllowed, timeouts)
    override fun clearTemporaryData() { biIndex.clear() }
    companion object {
        fun article(page: WebDocument, id: Int, question: String): EvidenceSource? = WebPageExtractor.article(page, id, question)
    }

    override suspend fun search(question: String): List<EvidenceSource> = research(question) {}.sources

    override suspend fun research(question: String, progress: (ResearchStage) -> Unit): ResearchOutcome {
        val inherited = kotlinx.coroutines.currentCoroutineContext()[ResearchDeadline]
        return withContext(Dispatchers.IO + (inherited ?: ResearchDeadline(minOf(researchBudgetMs, timeouts.overallMs)))) {
        progress(ResearchStage.UNDERSTANDING)
        // Finish with the evidence already read before the outer deadline.
        // Previously a slow final page could discard all successful earlier reads.
        val started = System.nanoTime() / 1_000_000
        val deadline = checkNotNull(kotlinx.coroutines.currentCoroutineContext()[ResearchDeadline])
        fun remaining() = deadline.remainingMs()
        require(!GroundingPolicy.containsCredential(question)) { "Do not search for credentials" }
        if (ConversationalResearch.needsSubject(question)) return@withContext ResearchOutcome(emptyList(), ResearchFailure.INSUFFICIENT)
        val plan = ResearchPlanner.plan(question)
        val transport = fetcher.session(24)
        val permitted: suspend (String) -> Boolean = { discoveryAllowed() && robots.allowed(it, transport) }
        val explicit = plan.explicitUrls
        val query = ResearchQuery(question)
        val possibleSpellings = linkedSetOf<String>()
        var providerCalls = 0
        val discovered = mutableMapOf<String, DiscoveryResult>()
        fun bounded(provider: DiscoveryProvider): DiscoveryProvider = DiscoveryProvider { request, broad ->
            val key = ExaDiscovery.searchText(request, broad).lowercase(java.util.Locale.ROOT)
            discovered[key] ?: if (providerCalls >= 3) DiscoveryResult(emptyList(), DiscoveryState.EMPTY) else {
                providerCalls++
                provider.discover(request, broad).also { discovered[key] = it }
            }
        }
        val discovery = bounded(discoveryOverride ?: ExaDiscovery(transport, discoveryAllowed, timeouts))
        var providerFailure: ResearchFailure? = null
        var discoveredCount = 0
        var retryCount = 0
        var httpCategory = ""
        var fallbackUsed = false
        val publisherFailures = java.util.Collections.synchronizedList(mutableListOf<ResearchFailure>())
        var discoveryFailed = false
        var blocked = false
        var discoveryTimedOut = false
        val readTimeouts = java.util.concurrent.atomic.AtomicInteger()
        val failedReads = java.util.concurrent.atomic.AtomicInteger()
        var rounds = 0
        fun eligible(candidates: List<ResearchCandidate>) = candidates.filter { WeatherPageEvidence.requestedDay(it.title, question) && !SourceBrief.predictionMarket(it.title, it.url, question) && query.candidateRelevant("${it.title} ${it.url}", it.routingHint) && !query.isPrediction(it.title) &&
                (!FactualResearch.speculativeCasting(question, it.title) || Regex("(?i)screen[- ]?tests?|auditions?|shortlist|not (?:yet )?(?:confirmed|announced)").containsMatchIn(it.title)) }
        suspend fun discover(search: String, broad: Boolean): List<ResearchCandidate>? {
            if (discoveryOverride == null && !discoveryAllowed()) { discoveryFailed = true; providerFailure = ResearchFailure.PROVIDER_ACCESS_DENIED; return null }
            if (rounds++ >= 3) return emptyList()
            if (!deadline.permits()) { discoveryTimedOut = true; providerFailure = ResearchFailure.PROVIDER_TIMEOUT; return null }
            val paused = discoveryCircuit.unavailable()
            val result = if (paused != null) DiscoveryResult(emptyList(), paused) else withTimeoutOrNull(minOf(timeouts.discoveryMs + timeouts.retryMs + timeouts.backoffMs + 1000, remaining())) {
                try { discovery.discover(ResearchQuery(question, searchOverride = search), broad) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE) }
            } ?: DiscoveryResult(emptyList(), DiscoveryState.TIMEOUT)
            if (paused == null) discoveryCircuit.record(result)
            discoveredCount += result.candidates.size
            retryCount += (result.attempts - 1).coerceAtLeast(0)
            if (result.httpCategory.isNotEmpty()) httpCategory = result.httpCategory
            if (result.failure != null) providerFailure = result.failure
            if (result.state == DiscoveryState.TIMEOUT) { discoveryTimedOut = true; return null }
            if (result.state in setOf(DiscoveryState.BLOCKED, DiscoveryState.RATE_LIMITED, DiscoveryState.UNAVAILABLE, DiscoveryState.NETWORK_UNAVAILABLE, DiscoveryState.MALFORMED)) {
                discoveryFailed = true; blocked = result.state in setOf(DiscoveryState.BLOCKED, DiscoveryState.RATE_LIMITED); return null
            }
            query.publicTopic?.let { topic -> result.candidates.forEach { candidate ->
                PublicTopics.possibleSpelling(topic, candidate.title)?.let(possibleSpellings::add)
            } }
            return eligible(result.candidates)
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
            if (candidates.isEmpty() && query.publicTopic == null && !blocked && !discoveryFailed && searches < 3) {
                candidates += discover(plan.searches.first(), true).orEmpty()
            }
            candidates
        }
        val seen = mutableSetOf<String>()
        val attempted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val domains = mutableMapOf<String, Int>()
        val reads = Semaphore(2)
        val childReads = java.util.concurrent.atomic.AtomicInteger(2)
        val sources = mutableListOf<EvidenceSource>()
        val extraction = java.util.concurrent.ConcurrentHashMap<String, Int>()
        fun extract(page: WebDocument, provider: String): EvidenceSource? {
            val result = WebPageExtractor.extract(page, 0, question)
            extraction.merge(result.category, 1, Int::plus)
            extraction.merge("PASSAGES", result.passages, Int::plus)
            return result.source?.copy(discoveryProvider = provider)
        }
        var readCount = 0
        fun enough(): Boolean {
            val selected = EvidenceRanking.select(sources, plan)
            val covered = selected.flatMap { plan.coverage(it.excerpt) }.toSet()
            return (!FactualResearch.needsAnswerFocus(question) || selected.any { FactualResearch.answerFocusScore(it.excerpt, question) >= 65 }) &&
                (query.publicTopic?.intent != PublicTopicIntent.REVIEW || selected.any { FactualResearch.reviewKind(it.title, it.url) == "CRITIC" }) &&
                covered.size == plan.focuses.size && (selected.size >= 2 || selected.size == 1 &&
                (explicit.isNotEmpty() || EvidenceRanking.primaryRelevance(selected.single().url, question) > 0)) && EvidenceRanking.conflicts(selected).isEmpty()
        }
        suspend fun readCandidates(candidates: List<ResearchCandidate>) {
        val unique = candidates.distinctBy { HtmlDiscovery.canonicalUrl(it.url) }.filter { HtmlDiscovery.canonicalUrl(it.url) !in attempted }
        val ordered = unique
            .sortedWith(compareBy<ResearchCandidate> { candidate ->
                // Prefer the first result from each publisher before a second.
                unique.indexOfFirst { domain(it.url) == domain(candidate.url) } != unique.indexOf(candidate)
                }.thenByDescending {
                val combined = "${it.title} ${it.routingHint}".trim()
                plan.coverage(combined).size * 15 + query.score(it.title) * 2 + query.score(combined) + EvidenceRanking.primaryRelevance(it.url, question) + EvidenceRanking.weatherPlaceScore(it.title, question) + FactualResearch.reviewPriority(it.title, it.url, question)
            })
            .filter { domains.getOrDefault(domain(it.url), 0) < 2 }
            // Reserve reads for one focused reformulation when broad election
            // coverage has not yet supplied the count/status the question needs.
            .take(minOf((8 - readCount).coerceAtLeast(0), if (readCount == 0 && FactualResearch.needsAnswerFocus(question) && plan.searches.size > 1) 4 else 8))
        if (ordered.isNotEmpty()) progress(ResearchStage.READING)
        // Read in waves: stop after enough evidence instead of fetching every result.
        for (wave in ordered.chunked(2)) {
            if (!deadline.permits()) break
            val admitted = wave.filter { attempted.add(HtmlDiscovery.canonicalUrl(it.url)) && domains.merge(domain(it.url), 1, Int::plus)!! <= 2 }
            readCount += admitted.size
            val fetched = admitted.map { candidate -> async {
                reads.withPermit {
                    var finished = false
                    val result = withTimeoutOrNull(minOf(timeouts.publisherMs, remaining())) {
                        try {
                            val page = transport.fetchPage(candidate.url, permitted)
                            extraction.merge("FETCH_2XX", 1, Int::plus)
                            val source = extract(page, candidate.provider)
                            fun acceptable(value: EvidenceSource): Boolean = EvidenceRanking.acceptable(value, plan).also { if (!it) extraction.merge("REJECTED_RELEVANCE_OR_DATE", 1, Int::plus) }
                            if (source != null && acceptable(source)) source
                            else {
                                // A landing/hub page may link to the actual article or benchmark sub-page.
                                val child = WebPageExtractor.relevantLinks(page, question).firstOrNull()
                                // Do not mark an unread child as attempted when
                                // its quota is exhausted: it may be a later result.
                                if (child != null && childReads.getAndDecrement() > 0 && attempted.add(HtmlDiscovery.canonicalUrl(child.url)))
                                    extract(transport.fetchPage(child.url, permitted), candidate.provider)?.takeIf(::acceptable)
                                else null
                            }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { extraction.merge(if (e is WebRequestException) "FETCH_${e.statusCode / 100}XX" else "FETCH_ERROR", 1, Int::plus); failedReads.incrementAndGet(); publisherFailures += ResearchErrors.publisher(e); null }
                        .also { finished = true }
                    }
                    if (!finished) { readTimeouts.incrementAndGet(); publisherFailures += ResearchFailure.PUBLISHER_TIMEOUT }
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
                if (readCount >= 8 || !deadline.permits()) break
                progress(ResearchStage.SEARCHING)
                readCandidates(discover(search, false).orEmpty())
                if (enough() || blocked || discoveryFailed || discoveryTimedOut) break
            }
        }
        if (explicit.isEmpty() && biFallback && discoveryOverride == null && sources.isEmpty() && biIndex.supports(query) && readCount < 8 &&
            deadline.permits(1000) && discoveryAllowed() && providerFailure != ResearchFailure.NETWORK_UNAVAILABLE) {
            fallbackUsed = true
            progress(ResearchStage.READING)
            val own = biIndex.session(transport).discover(query, false)
            discoveredCount += own.candidates.size
            readCandidates(eligible(own.candidates))
        }
        progress(ResearchStage.COMPARING)
        val selected = EvidenceRanking.select(sources, plan)
        val failure = when {
            selected.isNotEmpty() -> null
            readCount == 0 && providerFailure != null && providerFailure != ResearchFailure.NO_SEARCH_RESULTS -> providerFailure
            readTimeouts.get() > 0 -> ResearchFailure.PUBLISHER_TIMEOUT
            readCount > 0 && publisherFailures.contains(ResearchFailure.PUBLISHER_BLOCKED) -> ResearchFailure.PUBLISHER_BLOCKED
            readCount > 0 && publisherFailures.contains(ResearchFailure.NETWORK_UNAVAILABLE) -> ResearchFailure.NETWORK_UNAVAILABLE
            readCount > 0 && failedReads.get() >= readCount -> ResearchFailure.PUBLISHER_UNAVAILABLE
            discoveredCount == 0 && explicit.isEmpty() -> ResearchFailure.NO_SEARCH_RESULTS
            else -> ResearchFailure.INSUFFICIENT_EVIDENCE
        }
        val metrics = ResearchMetrics(provider = if (explicit.isNotEmpty()) "Direct website" else if (fallbackUsed) "Exa + BI source index" else ExaDiscovery.providerName,
            durationMs = System.nanoTime() / 1_000_000 - started, discoveredResults = discoveredCount,
            readPublishers = sources.size, attemptedPublishers = readCount, retries = retryCount, httpCategory = httpCategory,
            timeoutStage = if (readTimeouts.get() > 0) "PUBLISHER" else if (discoveryTimedOut) "PROVIDER" else "",
            extraction = extraction.toMap(), failure = failure ?: publisherFailures.firstOrNull() ?: providerFailure?.takeUnless { it == ResearchFailure.NO_SEARCH_RESULTS })
        ResearchOutcome(selected, failure, EvidenceRanking.conflicts(selected), if (selected.isEmpty() && possibleSpellings.size == 1)
            "Search found a possible spelling: **${possibleSpellings.single()}**${query.publicTopic?.language?.takeIf(String::isNotBlank)?.let { " ($it film)" }.orEmpty()}. Is that the film you mean?"
        else "", metrics)
        }
    }

    private fun domain(url: String): String = url.toHttpUrl().topPrivateDomain() ?: url.toHttpUrl().host
}
