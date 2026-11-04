package com.example.grounding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

/** BI's on-device source directory and bounded link index, NOT a global web index.
 * No query is sent to another search engine. Titles/links are ranked locally; the
 * regular reader must fetch acceptable publisher text before any answer is cited. */
class BiDiscovery(private val fetcher: WebFetcher, private val robots: ResearchRobots,
    private val allowed: suspend () -> Boolean, private val timeouts: ResearchTimeouts = ResearchTimeouts(),
    private val directory: List<Seed> = seeds,
    private val index: LinkedHashMap<String, Pair<Long, ResearchCandidate>> = LinkedHashMap(),
    private val epoch: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong()) : DiscoveryProvider {
    data class Seed(val url: String, val title: String, val topics: Set<String>) {
        val matchingTerms = topics.flatMap { ResearchQuery.words(it) }.toSet()
    }
    companion object {
        const val providerName = "BI source index"
        // Source addresses/categories only, never answers. New topics require a
        // reviewed source entry; no invented endpoint or guessed article URL.
        val seeds = listOf(
            Seed("https://tfl.gov.uk/tube-dlr-overground/status/", "TfL London Underground line service status", setOf("tfl", "jubilee", "underground", "tube")),
            Seed("https://www.bbc.com/weather/2643743", "London BBC Weather", setOf("london")),
            Seed("https://developer.android.com/ndk/guides/graphics/getting-started", "Android Vulkan graphics initialization and setup guide", setOf("vulkan", "ndk", "android")),
            Seed("https://ai.google.dev/edge/litert-lm", "Google AI Edge LiteRT-LM documentation", setOf("litert", "litertlm", "inference")),
            Seed("https://kotlinlang.org/docs/home.html", "Kotlin language documentation", setOf("kotlin", "coroutines")),
            Seed("https://doc.rust-lang.org/book/", "The Rust programming language", setOf("rust")),
            Seed("https://en.wikipedia.org/wiki/Neural_network_(machine_learning)", "Neural network machine learning", setOf("neural", "network", "learning")),
            Seed("https://www.ons.gov.uk/economy/inflationandpriceindices", "UK inflation and price indices ONS", setOf("inflation", "cpi", "ons")),
            Seed("https://www.marvel.com/movies", "Marvel movies films and cast", setOf("marvel", "avengers", "doomsday")),
            Seed("https://www.theguardian.com/film+tone/reviews", "Film movie reviews The Guardian", setOf("film", "movie", "reviews")),
            Seed("https://indianexpress.com/section/entertainment/movie-review/", "Tamil Indian film movie reviews Indian Express", setOf("tamil", "film", "movie", "reviews")),
            Seed("https://www.bbc.com/news", "BBC current news headlines", setOf("news", "headlines", "election")),
            Seed("https://www.reuters.com/world/", "Reuters world news", setOf("news", "headlines", "election")),
            Seed("https://store.google.com/category/phones", "Google Pixel phones specifications", setOf("pixel")),
            Seed("https://www.apple.com/iphone/compare/", "Apple iPhone specifications comparison", setOf("iphone"))
        )
    }
    fun clear() = synchronized(index) { epoch.incrementAndGet(); index.clear() }
    fun supports(query: ResearchQuery): Boolean = directory.any { seed -> seed.matchingTerms.any { it in ResearchQuery.words(query.question) } }
    fun session(transport: WebFetcher) = BiDiscovery(transport, robots, allowed, timeouts, directory, index, epoch)
    override suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult = coroutineScope {
        if (!allowed()) return@coroutineScope DiscoveryResult(emptyList(), DiscoveryState.BLOCKED)
        val generation = epoch.get()
        val deadline = currentCoroutineContext()[ResearchDeadline] ?: ResearchDeadline(timeouts.overallMs)
        val terms = ResearchQuery.words(query.question)
        val selected = directory.filter { seed ->
            seed.matchingTerms.any { it in terms } && ("weather" !in seed.url || ConversationalResearch.isWeather(query.question))
        }.sortedByDescending { seed -> seed.matchingTerms.count { it in terms } + query.score(seed.title) }.take(3)
        val found = mutableListOf<ResearchCandidate>()
        synchronized(index) {
            index.entries.removeAll { System.currentTimeMillis() - it.value.first !in 0..900_000 }
            found += index.values.map { it.second }.filter { query.candidateRelevant(it.title + " " + it.url, "") }
        }
        // Directory pages are valid candidates, not evidence. Publisher admission
        // and relevance are still applied by PublicWebResearch.
        found += selected.map { ResearchCandidate(it.url, it.title, provider = providerName) }
        val inaccessible = java.util.concurrent.atomic.AtomicBoolean()
        for (wave in selected.chunked(2)) {
            if (!deadline.permits(500)) break
            val discovered = wave.map { seed -> async {
                withTimeoutOrNull(minOf(timeouts.publisherMs, deadline.remainingMs())) {
                    try {
                        if (!allowed()) return@withTimeoutOrNull emptyList<ResearchCandidate>()
                        val page = fetcher.fetchPage(seed.url) { allowed() && robots.allowed(it, fetcher) }
                        val host = seed.url.toHttpUrl().host
                        val doc = Jsoup.parse(page.text, page.url)
                        doc.select("script, style, nav, footer, form, [hidden], [class*=advert]").remove()
                        doc.select("a[href]").take(600).mapNotNull { link ->
                            val url = link.absUrl("href")
                            val title = link.text().trim().take(200)
                            if (title.length < 6 || !GroundingPolicy.publicHttpsUrl(url)) null
                            else {
                                val parsed = url.toHttpUrl()
                                if (parsed.host != host || parsed.queryParameterNames.any { it.lowercase() in setOf("token", "key", "email", "password", "auth") } ||
                                    Regex("(?i)/(?:login|sign-in|subscribe|account|search)(?:/|$)").containsMatchIn(parsed.encodedPath) ||
                                    !query.candidateRelevant(title + " " + parsed.encodedPath, "")) null
                                else ResearchCandidate(HtmlDiscovery.canonicalUrl(url), title, provider = providerName)
                            }
                        }.distinctBy { it.url }.sortedByDescending { query.score(it.title) }.take(8)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { inaccessible.set(true); emptyList() }
                }.orEmpty()
            } }.awaitAll().flatten()
            found += discovered
        }
        val candidates = found.distinctBy { HtmlDiscovery.canonicalUrl(it.url) }.sortedByDescending { query.score(it.title) }.take(12)
        synchronized(index) {
            candidates.takeIf { epoch.get() == generation }?.forEach { candidate ->
                if (index.size >= 96) index.remove(index.keys.first())
                index[candidate.url] = System.currentTimeMillis() to candidate
            }
        }
        DiscoveryResult(candidates, if (candidates.isNotEmpty()) DiscoveryState.FOUND else if (inaccessible.get()) DiscoveryState.UNAVAILABLE else DiscoveryState.EMPTY)
    }
}
