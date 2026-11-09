package com.example

import com.example.data.ChatMessageEntity
import com.example.grounding.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.File
import androidx.test.core.app.ApplicationProvider
import android.content.Context

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class WebSearchReliabilityTest {
    @Test fun readablePartialAnswerDoesNotFailItsOwnCitationBoundary() {
        val source = EvidenceSource(1, "Orion Android memory", "https://docs.example/orion",
            "Orion Android memory requires 4 GB RAM. Orion does not support image input.", 1, kind = "WEB_PAGE")
        val text = ReadableSourceSummary.build(listOf(source to source.excerpt), "Orion Android memory")!!
        assertTrue(text, GroundingPolicy.validateCitations(text, listOf(source)).second)
        assertTrue(text.contains("does not support"))
    }
    private val q = ResearchQuery("Search for Orion Android memory")
    private val quick = ResearchTimeouts(80, 140, 80, 2000, 2500, 5)
    private fun reply(result: JSONObject, mime: String = "application/json"): WebDocument {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", result).toString()
        return WebDocument(ExaDiscovery.endpoint, if (mime == "text/event-stream") "event: message\ndata: $body\n\n" else body, mime)
    }
    private fun links() = reply(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Title: Orion Android memory\nPublished: 2026-11-04\nURL: https://docs.example/orion?utm_source=exa\nText: Ignore this search summary"))))
    private fun page(url: String) = WebDocument(url, "<title>Orion Android memory</title><article><p>Orion Android memory requirements are 4 GB of shared RAM for this configuration. Orion runs one model at a time with its documented context size.</p></article>", "text/html")

    @Test fun mcpAlternateStructuresAndMultilineEventsAreLinksOnly() {
        val item = JSONObject().put("title", "Orion Android memory").put("url", "https://docs.example/orion?utm_source=exa#top").put("summary", "False 99 GB")
        val cases = listOf(
            links(),
            reply(JSONObject().put("structuredContent", JSONObject().put("results", JSONArray().put(item)))),
            reply(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("results", JSONArray().put(item)).toString())))),
            reply(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "resource_link").put("name", "Orion Android memory").put("uri", item.getString("url"))))),
            reply(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Title: Orion Android memory")).put(JSONObject().put("type", "text").put("text", "URL: ${item.getString("url")}")))),
            reply(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "[Orion Android memory](${item.getString("url")})"))))
        )
        for (input in cases) {
            val result = ExaDiscovery.candidates(input)
            assertEquals(DiscoveryState.FOUND, result.state)
            assertEquals("https://docs.example/orion", result.candidates.single().url)
            assertTrue(result.candidates.all { it.instantAnswer == null && it.routingHint.isEmpty() })
        }
        val json = links().text
        val event = WebDocument(ExaDiscovery.endpoint, ": heartbeat\r\ndata: {\"method\":\"notifications/progress\"}\r\n\r\nevent: message\r\ndata: ${json.substringBefore("\"result\"")}\r\ndata: \"result\"${json.substringAfter("\"result\"")}\r\n\r\n", "text/event-stream; charset=utf-8")
        assertEquals(DiscoveryState.FOUND, ExaDiscovery.candidates(event).state)
    }
    @Test fun emptyMalformedProviderAndRateLimitResponsesAreDistinct() {
        assertEquals(DiscoveryState.EMPTY, ExaDiscovery.candidates(reply(JSONObject().put("content", JSONArray()))).state)
        assertEquals(DiscoveryState.EMPTY, ExaDiscovery.candidates(reply(JSONObject().put("structuredContent", JSONObject().put("results", JSONArray())))).state)
        assertEquals(DiscoveryState.MALFORMED, ExaDiscovery.candidates(WebDocument(ExaDiscovery.endpoint, "not JSON", "application/json")).state)
        val error = WebDocument(ExaDiscovery.endpoint, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"Rate limit reached\"}}", "application/json")
        assertEquals(DiscoveryState.RATE_LIMITED, ExaDiscovery.candidates(error).state)
        assertEquals(DiscoveryState.BLOCKED, ExaDiscovery.candidates(reply(JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Unauthorized permission"))))).state)
    }
    @Test fun slowHealthyExaCanUseTheLargerInitialBudget() = runBlocking {
        val started = System.nanoTime()
        val result = ExaDiscovery(WebFetcher { delay(8500); links() }).discover(q, false)
        assertEquals(DiscoveryState.FOUND, result.state); assertEquals(1, result.attempts)
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 8500)
    }
    @Test fun transientTimeoutAnd503RetryOnceBut429AndPermissionNeverRetry() = runBlocking {
        for (failure in listOf(SocketTimeoutException("not logged"), WebRequestException(503))) {
            var calls = 0
            val result = ExaDiscovery(WebFetcher { if (++calls == 1) throw failure else links() }, timeouts = quick, jitter = { 0 }).discover(q, false)
            assertEquals(DiscoveryState.FOUND, result.state); assertEquals(2, calls); assertEquals(2, result.attempts)
        }
        for (code in listOf(401, 403, 429)) {
            var calls = 0
            val result = ExaDiscovery(WebFetcher { calls++; throw WebRequestException(code, 120_000) }, timeouts = quick).discover(q, false)
            assertEquals(1, calls); assertEquals(120_000L, result.retryAfterMs)
            assertEquals(if (code == 429) DiscoveryState.RATE_LIMITED else DiscoveryState.BLOCKED, result.state)
        }
        var calls = 0
        val timed = ExaDiscovery(WebFetcher { if (++calls == 1) delay(200); links() }, timeouts = quick, jitter = { 0 }).discover(q, false)
        assertEquals(DiscoveryState.FOUND, timed.state); assertEquals(2, calls)
    }
    @Test fun retryAfterAndCircuitRespectCooldownAndIgnoreEmptyNetworkAndParserFailures() {
        var now = 1000L
        val circuit = DiscoveryCircuit { now }
        repeat(5) { circuit.record(DiscoveryState.EMPTY); circuit.record(DiscoveryState.MALFORMED); circuit.record(DiscoveryState.NETWORK_UNAVAILABLE) }
        assertNull(circuit.unavailable())
        circuit.record(DiscoveryResult(emptyList(), DiscoveryState.RATE_LIMITED, retryAfterMs = 120_000))
        now += 119_999; assertEquals(DiscoveryState.RATE_LIMITED, circuit.unavailable())
        now++; assertNull(circuit.unavailable())
        assertEquals(120_000L, SearchHttp.retryAfter("120"))
        assertEquals(120_000L, SearchHttp.retryAfter("Wed, 04 Nov 2026 12:02:00 GMT", java.time.Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()))
    }
    @Test fun deadlineStopsAdmissionAndCancellationStopsDiscoveryAndPublisherReads() = runBlocking {
        var now = 0L; val deadline = ResearchDeadline(1000) { now }; now = 1000
        var calls = 0
        withContext(deadline) { assertEquals(DiscoveryState.TIMEOUT, ExaDiscovery(WebFetcher { calls++; links() }).discover(q, false).state) }
        assertEquals(0, calls)
        for (stage in listOf("discovery", "publisher")) {
            val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
            val reader = PublicWebResearch(WebFetcher { url ->
                if (url.endsWith("robots.txt")) WebDocument(url, "User-agent: *\nAllow: /", "text/plain")
                else if (stage == "publisher" && url == ExaDiscovery.endpoint) links()
                else { started.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } }
            }, biFallback = false)
            val job = launch { reader.research(q.question) {} }
            started.await(); withTimeout(1000) { job.cancelAndJoin(); stopped.await() }
            assertTrue(job.isCancelled)
        }
    }
    @Test fun publisherFailuresRetainUsefulEvidenceAndClassifyTheActualStage() = runBlocking {
        fun reader(blocked: Boolean, withGood: Boolean) = PublicWebResearch(WebFetcher { url ->
            if (url.endsWith("robots.txt")) WebDocument(url, "User-agent: *\nAllow: /", "text/plain")
            else if (url.contains("good")) page(url)
            else if (blocked) throw WebRequestException(403) else { delay(500); page(url) }
        }, DiscoveryProvider { _, _ -> DiscoveryResult((if (withGood) listOf(ResearchCandidate("https://good.example/orion", "Orion Android memory")) else emptyList()) + ResearchCandidate("https://slow.example/orion", "Orion Android memory"), DiscoveryState.FOUND) }, timeouts = quick)
        val partial = reader(false, true).research(q.question) {}
        assertNull(partial.failure); assertEquals(1, partial.sources.size); assertNull(partial.metrics.failure)
        assertEquals(1, partial.metrics.publisherAccess["PUBLISHER_TIMEOUT"])
        assertEquals(ResearchFailure.PUBLISHER_FORBIDDEN, reader(true, false).research(q.question) {}.failure)
        assertEquals(ResearchFailure.PUBLISHER_TIMEOUT, reader(false, false).research(q.question) {}.failure)
    }
    @Test fun emptyResultsAndInsufficientTextDoNotBecomeProviderOutages() = runBlocking {
        val empty = PublicWebResearch(WebFetcher { error("No publisher expected") }, DiscoveryProvider { _, _ -> DiscoveryResult(emptyList(), DiscoveryState.EMPTY) }).research(q.question) {}
        assertEquals(ResearchFailure.NO_SEARCH_RESULTS, empty.failure)
        val insufficient = PublicWebResearch(WebFetcher { url -> if (url.endsWith("robots.txt")) WebDocument(url, "", "text/plain") else WebDocument(url, "<title>Gardening</title><p>Flowers and gardening</p>", "text/html") }, DiscoveryProvider { _, _ -> DiscoveryResult(listOf(ResearchCandidate("https://docs.example/orion", "Orion Android memory")), DiscoveryState.FOUND) }).research(q.question) {}
        assertEquals(ResearchFailure.EMPTY_EXTRACTION, insufficient.failure)
    }
    @Test fun generalMixedAndCurrentFallbacksHaveDifferentSafetyRequirements() {
        val general = AnswerRoutingPolicy.recover(AnswerRoutingPolicy.decide("How does a neural network work?", mode = GroundingMode.ON), ResearchFailure.NO_SEARCH_RESULTS)
        assertEquals("How does a neural network work?", general.localQuestion); assertEquals("LOCAL_UNVERIFIED", general.status)
        val current = AnswerRoutingPolicy.recover(AnswerRoutingPolicy.decide("What's the current Jubilee line status?", mode = GroundingMode.ON), ResearchFailure.PROVIDER_TIMEOUT)
        assertNull(current.localQuestion); assertEquals("PROVIDER_TIMEOUT", current.status)
        val mixed = AnswerRoutingPolicy.recover(AnswerRoutingPolicy.decide("Explain inflation and tell me the latest UK inflation rate.", mode = GroundingMode.ON), ResearchFailure.PROVIDER_RATE_LIMITED)
        assertEquals("Explain inflation", mixed.localQuestion); assertEquals("LOCAL_WITH_WEB_GAP", mixed.status)
        val verified = AnswerRoutingPolicy.recover(AnswerRoutingPolicy.decide("Explain neural networks with official sources", mode = GroundingMode.ON), ResearchFailure.NO_SEARCH_RESULTS)
        assertNull(verified.localQuestion)
        for (q in listOf("Explain my private notes", "Write a poem about current weather", "Offline: explain inflation", "15% of 240")) assertEquals(AnswerPath.LOCAL, AnswerRoutingPolicy.decide(q, mode = GroundingMode.ON).path)
        for (f in ResearchFailure.entries) assertTrue("Missing status ${f.name}", AnswerSourceStatus.label(f.name).isNotBlank())
        for (legacy in listOf("SEARCH_FAILED", "WEB_UNAVAILABLE", "SOURCE_EXCERPTS", "LOCAL_WEB_UNAVAILABLE")) assertTrue(AnswerSourceStatus.label(legacy).isNotBlank())
        assertFalse(AnswerSourceStatus.label("NO_SEARCH_RESULTS").contains("unavailable", true))
    }
    @Test fun progressDoesNotFlickerBackwardsAndShowsASlowIndicatorOnlyWhenNeeded() {
        var now = 0L; val progress = ResearchProgress { now }
        assertEquals("Searching the web", progress.label())
        now += 8000; assertTrue(progress.label().contains("Taking a little longer"))
        progress.advance(ResearchStage.READING); assertEquals("Reading sources", progress.label())
        progress.advance(ResearchStage.SEARCHING); assertEquals("Reading sources", progress.label())
    }
    @Test fun diagnosticsExportNeverIncludesQuestionsIdsSecretsOrPublisherText() {
        val context = ApplicationProvider.getApplicationContext<Context>(); ResearchDiagnostics.clear(context)
        val route = AnswerRoutingPolicy.decide("Search for Orion Android memory")
        ResearchDiagnostics.record(context, 88, 99, "my salary secret@example.com", route,
            GroundingResult("PROVIDER_TIMEOUT", failure = ResearchFailure.PROVIDER_TIMEOUT, metrics = ResearchMetrics("Exa", 12000, retries = 1, timeoutStage = "PROVIDER")), null, false)
        val report = ResearchDiagnostics.export(context)
        assertTrue(report.contains("PROVIDER_TIMEOUT")); assertTrue(report.contains("12000"))
        for (private in listOf("salary", "secret@", "Orion", "conversationId", "responseId", "excerpt", "originalQuery")) assertFalse(private, report.contains(private))
        val fence = ResearchDiagnostics.generation(); ResearchDiagnostics.clear(context)
        ResearchDiagnostics.record(context, 88, 99, "Public Orion", route, GroundingResult(), null, false, fence)
        assertEquals(0, JSONObject(ResearchDiagnostics.export(context)).getJSONArray("reports").length())
    }
    @Test fun biSourceIndexUsesOnlyPermittedPublicPagesAndPreservesTheSubject() = runBlocking {
        val requests = mutableListOf<String>()
        val fetcher = WebFetcher { url -> requests += url
            when {
                url.endsWith("robots.txt") -> WebDocument(url, "User-agent: *\nDisallow: /private", "text/plain")
                else -> WebDocument(url, "<main><a href='/orion?utm_source=x'>Orion Android memory reference</a><a href='https://other.example/orion'>Orion other</a><a href='http://127.0.0.1/'>Orion unsafe</a><a href='/login'>Orion login</a></main>", "text/html")
            }
        }
        val index = BiDiscovery(fetcher, ResearchRobots(fetcher), { true }, directory = listOf(BiDiscovery.Seed("https://docs.example/index", "Android source directory", setOf("android"))))
        val result = index.discover(q, false)
        assertTrue(result.candidates.any { it.url == "https://docs.example/orion" })
        assertTrue(result.candidates.none { "other.example" in it.url || "127.0.0.1" in it.url || "/login" in it.url })
        assertTrue(requests.none { it.startsWith(ExaDiscovery.endpoint) || "query=" in it })
        assertTrue(result.candidates.all { it.provider == BiDiscovery.providerName && it.instantAnswer == null })
        val prior = requests.size
        val denied = BiDiscovery(fetcher, ResearchRobots(fetcher), { false }).discover(q, false)
        assertEquals(prior, requests.size); assertTrue(denied.candidates.isEmpty())
        val categories = BiDiscovery(fetcher, ResearchRobots(fetcher), { true }, directory = listOf(
            BiDiscovery.Seed("https://docs.example/index", "News and film sources", setOf("headlines", "reviews"))))
        assertTrue(categories.supports(ResearchQuery("Latest news headlines")))
        assertTrue(categories.supports(ResearchQuery("Tamil movie reviews")))
    }
    @Test fun actualReaderCanRecoverThroughBiWithoutReplayingRateLimitedExa() = runBlocking {
        var exa = 0
        val reader = PublicWebResearch(WebFetcher { url -> when {
            url == ExaDiscovery.endpoint -> { exa++; throw WebRequestException(429, 120_000) }
            url.endsWith("robots.txt") -> WebDocument(url, "", "text/plain")
            url == "https://developer.android.com/ndk/guides/graphics/getting-started" -> WebDocument(url, "<main><a href='/orion'>Orion Android memory</a></main>", "text/html")
            else -> page(url)
        } })
        val outcome = reader.research(q.question) {}
        assertEquals(1, exa); assertNull(outcome.failure)
        assertTrue(outcome.sources.any { it.discoveryProvider == BiDiscovery.providerName })
        assertTrue(outcome.sources.all { it.kind == "WEB_PAGE" })
        assertEquals("Exa + BI source index", outcome.metrics.provider)
    }

    @Test fun productivityTipsAndContinueRouteCleanlyWithoutFalseEntitiesOrSearchPollution() {
        val tips = "Give me 5 daily productivity tips"
        assertTrue(ExactIdentity.labels(tips).isEmpty())
        assertTrue(ResearchPlanner.entities(tips).none { it.equals("me 5", true) || it.equals("Give", true) })
        assertEquals(AnswerPath.LOCAL, AnswerRoutingPolicy.decide(tips, mode = GroundingMode.ON).path)

        val bootSource = EvidenceSource(
            1,
            "The 6 Best Winter Boots of 2026 | OutdoorGearLab",
            "https://www.outdoorgearlab.com/topics/shoes-and-boots/best-winter-boots",
            "Sorel Caribou and Kamik Nation Plus are top-rated insulated snow boots with waterproof rubber shells.",
            1,
            kind = "WEB_PAGE"
        )
        val history = listOf(
            ChatMessageEntity(id = 1, role = "user", content = "Find good boot for snow", conversationId = 1),
            ChatMessageEntity(
                id = 2,
                role = "assistant",
                content = "Sorel Caribou and Kamik Nation Plus are top-rated insulated snow boots. [1]",
                conversationId = 1,
                groundingStatus = "WEB_SEARCH",
                sourcesJson = GroundingResult(sources = listOf(bootSource)).json()
            )
        )
        val cont = AnswerRoutingPolicy.decide("continue", mode = GroundingMode.ON, history = history, conversationId = 1)
        assertEquals(AnswerPath.WEB_GROUNDED, cont.path)
        assertEquals("Find good boot for snow", cont.question)
        assertEquals(listOf(bootSource), cont.reusedSources)
    }

    @Test fun unannouncedSequelRecoversPredecessorEvidenceAndExplainsClearly() = runBlocking {
        val question = "When will jailer 3 be released?"
        assertEquals("jailer 3" to "When will jailer 2 be released?", ExactIdentity.sequelFallback(question))
        assertFalse(ExactIdentity.accepts("Jailer 3", "Jailer 1 received 3 stars"))
        assertTrue(ExactIdentity.urlConflicts(question, "https://www.indiamasala.com/title/jailer-2-133255153"))
        assertFalse(ExactIdentity.urlConflicts("When will jailer 2 be released?", "https://www.indiamasala.com/title/jailer-2-133255153"))

        val reader = PublicWebResearch(
            WebFetcher { url ->
                if (url.endsWith("robots.txt")) WebDocument(url, "User-agent: *\nAllow: /", "text/plain")
                else WebDocument(
                    url,
                    "<title>Jailer 2 release update</title><article><p>Jailer 2 directed by Nelson Dilipkumar starring Rajinikanth is scheduled for theatrical release in 2026.</p></article>",
                    "text/html"
                )
            },
            DiscoveryProvider { query, _ ->
                if ("jailer 3" in query.question.lowercase()) DiscoveryResult(emptyList(), DiscoveryState.EMPTY)
                else DiscoveryResult(listOf(ResearchCandidate("https://cinema.example/jailer-2-release", "Jailer 2 release update")), DiscoveryState.FOUND)
            }
        )
        val outcome = reader.research(question) {}
        assertNull(outcome.failure)
        assertEquals(1, outcome.sources.size)
        val prepared = GroundingPolicy.prepareEvidence(question, outcome.sources, 2400)
        assertTrue(prepared.prompt.isNotEmpty())
        val resolved = EvidenceAnswer.resolve("Jailer 3 has not been announced yet.", outcome.sources, question)
        assertTrue(resolved.text, resolved.text.contains("The sources do not contain an official release announcement for the requested sequel."))
        assertTrue(resolved.text, resolved.text.contains("Jailer 2"))
        assertTrue(resolved.text, GroundingPolicy.validateCitations(resolved.text, outcome.sources).second)
    }

    @Test fun filmAwardsAndSubsequenceBulletsAreVerifiedAndDeduplicated() {
        val question = "Manichitrathazhu, how many awards did it win?"
        val wikiPage = WebPageExtractor.article(
            WebDocument(
                "https://en.wikipedia.org/wiki/Manichitrathazhu",
                "<title>Manichitrathazhu - Wikipedia</title><header class='mw-body-header'><h1 id='firstHeading'>Manichitrathazhu</h1></header><div id='mw-content-text'><p>Manichitrathazhu received widespread critical acclaim upon release and is considered by many critics as one of the best films in Malayalam cinema. The film won the National Film Award for Best Popular Film Providing Wholesome Entertainment. The film also won three Kerala state awards -- Best Film with Popular Appeal and Aesthetic Value (1994), Best Actress and Best Make-up Artist (PN Mani).</p></div>",
                "text/html"
            ),
            2,
            question
        )!!
        assertTrue(wikiPage.excerpt, wikiPage.excerpt.contains("three Kerala state awards"))
        val resolved = EvidenceAnswer.resolve(
            "Manichitrathazhu won the National Film Award for Best Popular Film Providing Wholesome Entertainment. [2]",
            listOf(wikiPage),
            question
        )
        assertFalse(resolved.text, resolved.text.contains("couldn't verify", ignoreCase = true))
        assertTrue(resolved.text, resolved.text.contains("National Film Award"))
        assertTrue(resolved.text, resolved.text.contains("three Kerala state awards"))

        val bullets = "- The O2 in Greenwich has 6 upcoming events. [1]\n\n- The O2 has 6 upcoming events. [1]\n\n- Pete Tong Ibiza Classics performs on 4 December 2026. [1]"
        val paragraphs = AnswerPresentation.paragraphs(bullets)
        assertTrue(paragraphs, paragraphs.contains("The O2 in Greenwich has 6 upcoming events. [1]"))
        assertFalse(paragraphs, paragraphs.contains("- The O2 has 6 upcoming events. [1]"))
    }

    @Test fun biDiscoveryResolvesEncyclopedicAndDirectorySeedsWithoutQueryStrings() = runBlocking {
        val requests = mutableListOf<String>()
        val fetcher = WebFetcher { url ->
            requests += url
            when {
                url.endsWith("robots.txt") -> WebDocument(url, "User-agent: *\nAllow: /", "text/plain")
                url == "https://en.wikipedia.org/wiki/Manichitrathazhu" -> WebDocument(
                    url,
                    "<title>Manichitrathazhu - Wikipedia</title><main><p>Manichitrathazhu is a 1993 Indian Malayalam-language psychological thriller film that won 2 National Film Awards.</p></main>",
                    "text/html"
                )
                else -> WebDocument(url, "<title>Index</title><main></main>", "text/html")
            }
        }
        val bi = BiDiscovery(fetcher, ResearchRobots(fetcher), { true })
        val wiki = bi.discover(ResearchQuery("Manichitrathazhu, how many awards did it win?"), false)
        assertEquals(DiscoveryState.FOUND, wiki.state)
        assertTrue(wiki.candidates.any { it.url == "https://en.wikipedia.org/wiki/Manichitrathazhu" })
        assertTrue(requests.none { "?" in it })
        assertTrue(bi.matchesDirectory(ResearchQuery("When is the next event in O2 Greenwich?")))
        assertTrue(bi.matchesDirectory(ResearchQuery("Find good boot for snow")))
    }
}
