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
    private val timeouts: ResearchTimeouts = ResearchTimeouts(), private val biFallback: Boolean = true,
    private val alternativeProviders: List<ConfiguredDiscovery> = emptyList(),
    private val clock: java.time.Clock = java.time.Clock.systemDefaultZone()) : SearchProvider {
    private val robots = ResearchRobots(fetcher)
    private val discoveryCircuits = mutableMapOf<String, DiscoveryCircuit>()
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
        val plan = ResearchPlanner.plan(question, java.time.LocalDate.now(clock))
        val transport = fetcher.session(24)
        val permitted: suspend (String) -> Boolean = { discoveryAllowed() && robots.requireAllowed(it, transport) }
        val explicit = plan.explicitUrls
        val query = ResearchQuery(question, java.time.LocalDate.now(clock))
        val possibleSpellings = linkedSetOf<String>()
        val discovery = DiscoveryChain(listOf(ConfiguredDiscovery("Exa", discoveryOverride ?: ExaDiscovery(transport, discoveryAllowed, timeouts))) +
            alternativeProviders, timeouts, discoveryCircuits, discoveryAllowed)
        var providerFailure: ResearchFailure? = null
        var discoveredCount = 0
        var retryCount = 0
        var httpCategory = ""
        var fallbackUsed = false
        val publisherFailures = java.util.Collections.synchronizedList(mutableListOf<ResearchFailure>())
        val access = java.util.concurrent.ConcurrentHashMap<String, Int>()
        var discoveryFailed = false
        var blocked = false
        var discoveryTimedOut = false
        val readTimeouts = java.util.concurrent.atomic.AtomicInteger()
        val failedReads = java.util.concurrent.atomic.AtomicInteger()
        var rounds = 0
        var recoveryAttempts = 0
        fun eligible(candidates: List<ResearchCandidate>) = candidates.filter { WeatherPageEvidence.requestedDay(it.title, question) && !SourceBrief.predictionMarket(it.title, it.url, question) && query.candidateRelevant("${it.title} ${it.url}", it.routingHint) && !query.isPrediction(it.title) &&
                (!FactualResearch.speculativeCasting(question, it.title) || Regex("(?i)screen[- ]?tests?|auditions?|shortlist|not (?:yet )?(?:confirmed|announced)").containsMatchIn(it.title)) }
        suspend fun discover(search: String, broad: Boolean): List<ResearchCandidate>? {
            if (!discoveryAllowed()) { discoveryFailed = true; providerFailure = ResearchFailure.PROVIDER_ACCESS_DENIED; return null }
            if (rounds++ >= 3) return emptyList()
            if (!deadline.permits()) { discoveryTimedOut = true; providerFailure = ResearchFailure.PROVIDER_TIMEOUT; return null }
            val result = withTimeoutOrNull(remaining().coerceAtLeast(1)) {
                try { discovery.discover(ResearchQuery(question, java.time.LocalDate.now(clock), searchOverride = search), broad) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE) }
            } ?: DiscoveryResult(emptyList(), DiscoveryState.TIMEOUT)
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
                if (plan.event == null && plan.searches.isNotEmpty()) candidates += discover(plan.searches.first(), true).orEmpty()
            }
            candidates
        }
        val seen = mutableSetOf<String>()
        val attempted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val domains = mutableMapOf<String, Int>()
        val reads = Semaphore(2)
        val childReads = java.util.concurrent.atomic.AtomicInteger(2)
        fun admitChild(url: String, reserveVenueProfile: Boolean = false): Boolean = synchronized(childReads) {
            val reserve = if (reserveVenueProfile && plan.event != null) 1 else 0
            if (childReads.get() <= reserve || !attempted.add(HtmlDiscovery.canonicalUrl(url))) false
            else { childReads.decrementAndGet(); true }
        }
        val sources = mutableListOf<EvidenceSource>()
        val venueProfiles = java.util.concurrent.ConcurrentLinkedQueue<EvidenceSource>()
        val extraReads = java.util.concurrent.atomic.AtomicInteger()
        val extraction = java.util.concurrent.ConcurrentHashMap<String, Int>()
        fun extract(page: WebDocument, provider: String): EvidenceSource? {
            val result = WebPageExtractor.extract(page, 0, question)
            extraction.merge(result.category, 1, Int::plus)
            extraction.merge("PASSAGES", result.passages, Int::plus)
            if (result.source == null) {
                val reason = when (result.category) {
                    "ACCESS_RESTRICTED", "JAVASCRIPT_REQUIRED" -> ResearchFailure.PUBLISHER_CHALLENGE
                    "UNSUPPORTED_TYPE" -> ResearchFailure.UNSUPPORTED_CONTENT
                    else -> ResearchFailure.EMPTY_EXTRACTION
                }
                publisherFailures += reason; access.merge(reason.name, 1, Int::plus)
            }
            return result.source?.copy(discoveryProvider = provider)
        }
        var readCount = 0
        fun enough(): Boolean {
            val selected = EvidenceRanking.select(sources, plan)
            if (plan.event != null) return EventAnswer.resolve(question, selected, clock.instant())?.support == AnswerSupport.CITED
            val covered = selected.flatMap { plan.coverage(it.excerpt) }.toSet()
            val financingAmount = selected.any { FinancialAnswerScope.focusScore(it.excerpt, question, it.url) >= 100 }
            return (!FactualResearch.needsAnswerFocus(question) || selected.any { FactualResearch.answerFocusScore(it.excerpt, question, it.url) >= 65 }) &&
                (query.publicTopic?.intent != PublicTopicIntent.REVIEW || selected.any { FactualResearch.reviewKind(it.title, it.url) == "CRITIC" }) &&
                (covered.size == plan.focuses.size || financingAmount) && (selected.size >= 2 || selected.size == 1 &&
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
            .take(minOf((8 - readCount).coerceAtLeast(0), if (readCount == 0 && (FactualResearch.needsAnswerFocus(question) || plan.event != null) && plan.searches.size > 1) 4 else 8))
        if (ordered.isNotEmpty()) progress(ResearchStage.READING)
        // Read in waves: stop after enough evidence instead of fetching every result.
        for (wave in ordered.chunked(2)) {
            if (!deadline.permits()) break
            val admitted = wave.filter { attempted.add(HtmlDiscovery.canonicalUrl(it.url)) && domains.merge(domain(it.url), 1, Int::plus)!! <= 2 }
            readCount += admitted.size
            val fetched = admitted.map { candidate -> async {
                reads.withPermit {
                    var finished = false
                    var retained: EvidenceSource? = null
                    val result = withTimeoutOrNull(minOf(timeouts.publisherMs, remaining())) {
                        try {
                            val page = transport.fetchPage(candidate.url, permitted)
                            extraction.merge("FETCH_2XX", 1, Int::plus)
                            val source = extract(page, candidate.provider)
                            fun acceptable(value: EvidenceSource): Boolean = EvidenceRanking.acceptable(value, plan).also { if (!it) {
                                    extraction.merge("REJECTED_RELEVANCE_OR_DATE", 1, Int::plus)
                                    publisherFailures += if (plan.event != null) ResearchFailure.EVENT_DATES_UNVERIFIED else ResearchFailure.RELEVANCE_REJECTED
                                } }
                            if (source != null && acceptable(source)) {
                                retained = source
                                val event = plan.event
                                if (event != null && source.events.isNotEmpty() && source.events.none { event.matches(it) }) {
                                    val profile = WebPageExtractor.venueLinks(page).firstOrNull { link ->
                                        VenueContext.publisherHint(source) > 0 || Regex("(?i)getting (?:here|to)|directions|find us|venue (?:information|address)").containsMatchIn(link.title)
                                    }
                                    if (profile != null && admitChild(profile.url)) {
                                        extraReads.incrementAndGet()
                                        try {
                                            withTimeoutOrNull(minOf(3000, remaining().coerceAtLeast(1))) {
                                                extract(transport.fetchPage(profile.url, permitted), candidate.provider)?.takeIf { VenueContext.profile(it, event) }?.let(venueProfiles::add)
                                            }
                                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                            access.merge(ResearchErrors.publisher(e).name, 1, Int::plus)
                                        }
                                    }
                                }
                                source
                            }
                            else {
                                // A landing/hub page may link to the actual article or benchmark sub-page.
                                val child = WebPageExtractor.relevantLinks(page, question).firstOrNull()
                                // Do not mark an unread child as attempted when
                                // its quota is exhausted: it may be a later result.
                                if (child != null && admitChild(child.url, reserveVenueProfile = true)) {
                                    extraReads.incrementAndGet()
                                    extract(transport.fetchPage(child.url, permitted), candidate.provider)?.takeIf(::acceptable)
                                }
                                else null
                            }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            extraction.merge(if (e is WebRequestException) "FETCH_${e.statusCode / 100}XX" else "FETCH_ERROR", 1, Int::plus)
                            failedReads.incrementAndGet(); val reason = ResearchErrors.publisher(e)
                            publisherFailures += reason; access.merge(reason.name, 1, Int::plus); null
                        }
                        .also { finished = true }
                    }
                    if (!finished) { readTimeouts.incrementAndGet(); publisherFailures += ResearchFailure.PUBLISHER_TIMEOUT; access.merge("PUBLISHER_TIMEOUT", 1, Int::plus) }
                    result ?: retained ?: candidate.instantAnswer?.takeIf {
                        LegacyTopicEvidence.summaryAllowed(question) && EvidenceRanking.acceptable(it, plan)
                    }
                }
            } }.awaitAll().filterNotNull()
            fetched.forEach { if (seen.add(it.url)) sources += it }
            while (true) { val profile = venueProfiles.poll() ?: break; if (seen.add(profile.url)) sources += profile }
            if (enough()) break
        }
        }
        readCandidates(links)
        // Expand only after actual passages fail to cover the requested facets.
        if (explicit.isEmpty() && !enough() && !blocked && providerFailure != ResearchFailure.NETWORK_UNAVAILABLE) {
            for (search in plan.searches.drop(1)) {
                if (readCount >= 8 || !deadline.permits()) break
                progress(ResearchStage.SEARCHING)
                recoveryAttempts++
                readCandidates(discover(search, false).orEmpty())
                if (enough() || blocked) break
            }
        }
        if (explicit.isEmpty() && biFallback && discoveryOverride == null && sources.isEmpty() && biIndex.supports(query) && readCount < 8 &&
            deadline.permits(1000) && discoveryAllowed() && providerFailure != ResearchFailure.NETWORK_UNAVAILABLE) {
            fallbackUsed = true
            recoveryAttempts++
            progress(ResearchStage.READING)
            val own = biIndex.session(transport).discover(query, false)
            discoveredCount += own.candidates.size
            readCandidates(eligible(own.candidates))
        }
        progress(ResearchStage.COMPARING)
        val ranked = EvidenceRanking.select(sources, plan)
        val selected = if (plan.event != null && EventAnswer.candidates(plan.event, ranked, clock.instant()).isEmpty()) emptyList() else ranked
        val failure = when {
            selected.isNotEmpty() -> null
            plan.event != null && sources.isNotEmpty() -> ResearchFailure.EVENT_DATES_UNVERIFIED
            readCount == 0 && providerFailure != null && providerFailure != ResearchFailure.NO_SEARCH_RESULTS -> providerFailure
            readTimeouts.get() > 0 -> ResearchFailure.PUBLISHER_TIMEOUT
            readCount > 0 && publisherFailures.contains(ResearchFailure.EVENT_DATES_UNVERIFIED) -> ResearchFailure.EVENT_DATES_UNVERIFIED
            readCount > 0 && publisherFailures.contains(ResearchFailure.NETWORK_UNAVAILABLE) -> ResearchFailure.NETWORK_UNAVAILABLE
            readCount > 0 && publisherFailures.isNotEmpty() -> publisherFailures.first()
            discoveredCount == 0 && explicit.isEmpty() -> ResearchFailure.NO_SEARCH_RESULTS
            else -> ResearchFailure.INSUFFICIENT_EVIDENCE
        }
        val metrics = ResearchMetrics(provider = if (explicit.isNotEmpty()) "Direct website" else if (fallbackUsed) "Exa + BI source index" else discovery.used.joinToString(" + ").ifBlank { ExaDiscovery.providerName },
            durationMs = System.nanoTime() / 1_000_000 - started, discoveredResults = discoveredCount,
            readPublishers = sources.size, attemptedPublishers = readCount + extraReads.get(), retries = retryCount, httpCategory = httpCategory,
            timeoutStage = if (readTimeouts.get() > 0) "PUBLISHER" else if (discoveryTimedOut) "PROVIDER" else "",
            extraction = extraction.toMap(), failure = failure,
            recoveryAttempts = recoveryAttempts, providers = discovery.used.toSet(), publisherAccess = access.toMap())
        ResearchOutcome(selected, failure, EvidenceRanking.conflicts(selected), if (selected.isEmpty() && possibleSpellings.size == 1)
            "Search found a possible spelling: **${possibleSpellings.single()}**${query.publicTopic?.language?.takeIf(String::isNotBlank)?.let { " ($it film)" }.orEmpty()}. Is that the film you mean?"
        else "", metrics)
        }
    }

    private fun domain(url: String): String = url.toHttpUrl().topPrivateDomain() ?: url.toHttpUrl().host
}
