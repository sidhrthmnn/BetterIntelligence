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
            Seed("https://tfl.gov.uk/tube-dlr-overground/status/", "TfL London Underground line service status", setOf("tfl", "jubilee", "underground", "tube", "dlr", "overground", "elizabeth", "piccadilly", "victoria", "central", "northern")),
            Seed("https://www.bbc.com/weather/2643743", "London BBC Weather", setOf("london", "weather", "temperature", "forecast", "rain")),
            Seed("https://www.bbc.com/weather/2643123", "Greenwich BBC Weather", setOf("greenwich")),
            Seed("https://www.bbc.com/weather/1273874", "Kochi Kerala BBC Weather", setOf("kochi", "kerala")),
            Seed("https://www.theo2.co.uk/events", "The O2 Greenwich London upcoming events and concerts calendar", setOf("o2", "greenwich", "arena", "concert", "concerts", "event", "events")),
            Seed("https://www.wembleystadium.com/events", "Wembley Stadium London upcoming concerts and events calendar", setOf("wembley", "stadium", "concert", "concerts")),
            Seed("https://www.royalalberthall.com/tickets/events/", "Royal Albert Hall London upcoming events calendar", setOf("albert", "hall", "concert", "concerts")),
            Seed("https://www.arsenal.com/results-and-fixtures-list", "Arsenal FC upcoming fixtures and match schedule", setOf("arsenal", "match", "fixture", "fixtures")),
            Seed("https://www.spacex.com/launches/", "SpaceX upcoming rocket launches and mission schedule", setOf("spacex", "launch", "launches", "falcon", "starship")),
            Seed("https://developer.android.com/ndk/guides/graphics/getting-started", "Android Vulkan graphics initialization and setup guide", setOf("vulkan", "ndk", "android")),
            Seed("https://developer.android.com/about/versions", "Android platform versions and release updates", setOf("version", "versions", "sdk", "security", "patch")),
            Seed("https://ai.google.dev/edge/litert-lm", "Google AI Edge LiteRT-LM documentation", setOf("litert", "litertlm", "inference", "gemma", "npu")),
            Seed("https://kotlinlang.org/docs/home.html", "Kotlin language documentation", setOf("kotlin", "coroutines")),
            Seed("https://doc.rust-lang.org/book/", "The Rust programming language", setOf("rust", "cargo", "ownership")),
            Seed("https://en.wikipedia.org/wiki/Neural_network_(machine_learning)", "Neural network machine learning", setOf("neural", "network", "learning")),
            Seed("https://www.bbc.co.uk/bitesize/articles/zdxkcmn", "Photosynthesis science guide BBC Bitesize", setOf("photosynthesis", "chloroplast", "plants")),
            Seed("https://www.ons.gov.uk/economy/inflationandpriceindices", "UK inflation and price indices ONS", setOf("inflation", "cpi", "ons", "economy")),
            Seed("https://www.marvel.com/movies", "Marvel movies films and cast", setOf("marvel", "avengers", "doomsday", "doom")),
            Seed("https://www.theguardian.com/film+tone/reviews", "Film movie reviews The Guardian", setOf("film", "movie", "reviews", "review", "rating", "cast", "released", "releasing")),
            Seed("https://indianexpress.com/section/entertainment/movie-review/", "Tamil Malayalam Hindi Indian film movie reviews Indian Express", setOf("tamil", "malayalam", "hindi", "telugu", "film", "movie", "reviews", "review", "ott", "jailer", "manichitrathazhu")),
            Seed("https://indianexpress.com/section/entertainment/tamil/", "Tamil cinema movie news release dates and box office Indian Express", setOf("tamil", "jailer", "sequel", "kollywood", "releasing", "released")),
            Seed("https://indianexpress.com/section/entertainment/malayalam/", "Malayalam cinema movie news OTT release and awards Indian Express", setOf("malayalam", "mollywood", "manichitrathazhu", "awards", "award", "ott")),
            Seed("https://www.thehindu.com/entertainment/movies/", "Indian movie reviews release updates and awards The Hindu", setOf("film", "movie", "awards", "award", "national", "tamil", "malayalam", "hindi")),
            Seed("https://www.bbc.com/news", "BBC current news headlines", setOf("news", "headlines", "election", "minister", "prime", "mayor", "president", "uk")),
            Seed("https://www.reuters.com/world/", "Reuters world news", setOf("news", "headlines", "election", "world", "ceo", "richest", "billionaire")),
            Seed("https://indianexpress.com/section/india/", "India national news and chief ministers Indian Express", setOf("india", "kerala", "minister", "cm", "governor")),
            Seed("https://store.google.com/category/phones", "Google Pixel phones specifications", setOf("pixel")),
            Seed("https://www.apple.com/iphone/compare/", "Apple iPhone specifications comparison", setOf("iphone")),
            Seed("https://www.outdoorgearlab.com/t/footwear", "Winter snow boots and outdoor footwear reviews OutdoorGearLab", setOf("boot", "boots", "snow", "winter", "footwear", "hiking", "shoe", "shoes")),
            Seed("https://www.nytimes.com/wirecutter/reviews/best-winter-boots/", "Best winter and snow boots review Wirecutter", setOf("boot", "boots", "snow", "winter"))
        )

        fun encyclopedicSeeds(query: ResearchQuery): List<Seed> {
            val q = query.question.trim()
            if (q.isBlank() || "orion" in q.lowercase() || ConversationalResearch.isWeather(q)) return emptyList()
            val out = mutableListOf<Seed>()
            fun addWiki(slug: String, label: String) {
                val clean = slug.trim().replace(Regex("\\s+"), "_")
                if (clean.length !in 2..90 || clean.any { it in "?&#=:/\\" }) return
                val url = "https://en.wikipedia.org/wiki/$clean"
                if (GroundingPolicy.publicHttpsUrl(url)) out += Seed(url, "$label Wikipedia", ResearchQuery.words(label))
            }
            val lower = q.lowercase()
            when {
                Regex("\\b(?:cm|chief minister)\\s+of\\s+kerala\\b").containsMatchIn(lower) -> addWiki("Chief_Minister_of_Kerala", "Chief Minister of Kerala incumbent")
                Regex("\\b(?:uk\\s+)?prime minister\\b").containsMatchIn(lower) && ("uk" in lower || "united kingdom" in lower || "british" in lower) ->
                    addWiki("Prime_Minister_of_the_United_Kingdom", "Prime Minister of the United Kingdom incumbent")
                Regex("\\bmayor\\s+of\\s+london\\b").containsMatchIn(lower) -> addWiki("Mayor_of_London", "Mayor of London incumbent")
            }
            FactualResearch.capitalPlace(q)?.let { place -> addWiki(place, "Capital city of $place") }
            FactualResearch.definitionTopic(q)?.let { topic ->
                val slug = topic.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString("_") {
                    it.replaceFirstChar { c -> c.titlecase(java.util.Locale.US) }
                }
                addWiki(slug, topic)
            }
            PublicTopics.parse(q)?.subject?.takeIf { it.length >= 3 }?.let { subject ->
                val titled = subject.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString("_") {
                    it.replaceFirstChar { c -> c.titlecase(java.util.Locale.US) }
                }
                addWiki(titled, "$subject film")
            }
            ResearchPlanner.plan(q).entities.forEach { entity ->
                val base = entity.replace(Regex("\\s+\\d+$"), "").trim()
                val target = if (Regex("(?i)\\b(?:release|released|releasing|sequel|award|awards|won|win|cast|directed|wrote)\\b").containsMatchIn(q) && base.isNotBlank()) base else entity
                val titled = target.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString("_") {
                    it.replaceFirstChar { c -> c.titlecase(java.util.Locale.US) }
                }
                addWiki(titled, "$entity $q")
            }
            return out.distinctBy { it.url }.take(2)
        }
    }
    fun clear() = synchronized(index) { epoch.incrementAndGet(); index.clear() }
    fun matchesDirectory(query: ResearchQuery): Boolean {
        val words = ResearchQuery.words(query.question)
        return directory.any { seed -> seed.matchingTerms.any { it in words } }
    }
    fun supports(query: ResearchQuery): Boolean = matchesDirectory(query) ||
        (directory === seeds && encyclopedicSeeds(query).isNotEmpty())
    fun session(transport: WebFetcher) = BiDiscovery(transport, robots, allowed, timeouts, directory, index, epoch)
    override suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult = coroutineScope {
        if (!allowed()) return@coroutineScope DiscoveryResult(emptyList(), DiscoveryState.BLOCKED)
        val generation = epoch.get()
        val deadline = currentCoroutineContext()[ResearchDeadline] ?: ResearchDeadline(timeouts.overallMs)
        val terms = ResearchQuery.words(query.question)
        val dynamic = if (directory === seeds) encyclopedicSeeds(query) else emptyList()
        val matched = directory.filter { seed ->
            seed.matchingTerms.any { it in terms } && ("weather" !in seed.url || ConversationalResearch.isWeather(query.question))
        }.sortedByDescending { seed -> seed.matchingTerms.count { it in terms } * 4 + query.score(seed.title) }
        val selected = (dynamic + matched).distinctBy { it.url }.take(4)
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
                            val itemContext = link.closest("li, tr, dd, dt, h1, h2, h3, h4, p, figcaption")
                                ?.takeIf { it.select("a[href]").size <= 3 }?.text()?.trim()?.take(200).orEmpty()
                            val combinedTitle = if (title.length >= 12 || itemContext.isBlank()) title else "$title — $itemContext".take(200)
                            if (combinedTitle.length < 6 || !GroundingPolicy.publicHttpsUrl(url)) null
                            else {
                                val parsed = url.toHttpUrl()
                                if (parsed.host != host || parsed.queryParameterNames.any { it.lowercase() in setOf("token", "key", "email", "password", "auth") } ||
                                    Regex("(?i)/(?:login|sign-in|subscribe|account|search)(?:/|$)").containsMatchIn(parsed.encodedPath) ||
                                    !query.candidateRelevant(combinedTitle + " " + parsed.encodedPath, "")) null
                                else ResearchCandidate(HtmlDiscovery.canonicalUrl(url), combinedTitle, provider = providerName)
                            }
                        }.distinctBy { it.url }.sortedByDescending { query.score(it.title + " " + it.url) }.take(8)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { inaccessible.set(true); emptyList() }
                }.orEmpty()
            } }.awaitAll().flatten()
            found += discovered
        }
        val candidates = found.distinctBy { HtmlDiscovery.canonicalUrl(it.url) }.sortedByDescending { query.score(it.title + " " + it.url) }.take(12)
        synchronized(index) {
            candidates.takeIf { epoch.get() == generation }?.forEach { candidate ->
                if (index.size >= 96) index.remove(index.keys.first())
                index[candidate.url] = System.currentTimeMillis() to candidate
            }
        }
        DiscoveryResult(candidates, if (candidates.isNotEmpty()) DiscoveryState.FOUND else if (inaccessible.get()) DiscoveryState.UNAVAILABLE else DiscoveryState.EMPTY)
    }
}
