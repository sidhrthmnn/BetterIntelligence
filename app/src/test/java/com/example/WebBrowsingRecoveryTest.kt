package com.example

import com.example.grounding.*
import com.example.data.ChatMessageEntity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class WebBrowsingRecoveryTest {
    @Test fun actualFailedQuestionsResearchWithoutRequiringLatestOrSearchKeywords() {
        for (q in listOf("Who is acting as doom in avengers doomsday", "Who acts as Doom in Doomsday?",
            "How is the election going on in israel", "How is jailer 2 movie?", "When is ramayana mobive releasing",
            "what is the price of latte in pret a manger", "In uk what's the price")) {
            assertEquals(q, AnswerPath.WEB_GROUNDED, AnswerRoutingPolicy.decide(q).path)
        }
        for (q in listOf("How do elections work?", "Explain the history of the Israeli election in 1996",
            "Write a movie review of my draft", "Explain movie release strategies", "What is price elasticity?"))
            assertEquals(q, AnswerPath.LOCAL, AnswerRoutingPolicy.decide(q).path)
    }

    @Test fun uncertaintyTriggersOnceForEligiblePublicTasksAndNeverOverridesPrivacy() {
        for (q in listOf("Who developed OrionHub?", "Explain the OrionHub protocol", "How is OrionHub different?")) {
            val route = AnswerRoutingPolicy.decide(q)
            for (answer in listOf("I do not have specific information about OrionHub.", "I don't know who developed it.",
                "I'm not sure about this topic.", "[WEB_NEEDED] This needs a source.",
                "I do not have specific, real-time reviews or detailed plot summaries for every movie."))
                assertTrue("$q: $answer", AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(route, answer))
            for (answer in listOf("OrionHub was developed by Example.", "Some details are unknown, but here is the explanation.",
                "The phrase 'I don't know' expresses uncertainty."))
                assertFalse(answer, AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(route, answer))
        }
        for (q in listOf("Write a story", "Explain my private code", "Offline: Who made OrionHub?", "What is 15% of 240?",
            "What is my password: abcde?"))
            assertFalse(q, AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(AnswerRoutingPolicy.decide(q), "[WEB_NEEDED] Unknown"))
        assertFalse(AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(AnswerRoutingPolicy.decide("Who made OrionHub?", false), "I don't know."))
        assertFalse(AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(AnswerRoutingPolicy.decide("Search for OrionHub"), "I don't know."))
        assertEquals("", LocalAnswerUncertainty.visible("[WEB_"))
        assertEquals("Unknown", LocalAnswerUncertainty.visible("[WEB_NEEDED] Unknown"))
    }

    private fun feed(body: String, mime: String = "application/json"): WebDocument {
        val reply = org.json.JSONObject().put("id", 1).put("result", org.json.JSONObject().put("content", org.json.JSONArray()
            .put(org.json.JSONObject().put("type", "text").put("text", body)))).toString()
        return WebDocument(ExaDiscovery.endpoint, if (mime == "text/event-stream") "event: message\ndata: $reply\n\n" else reply, mime)
    }
    private fun item(url: String, title: String, snippet: String = "") = "Title: $title\nURL: $url\nHighlights: $snippet\n---\n"

    @Test fun mcpProvidesOnlyValidatedPublisherLinksNotHighlightsOrGeneratedWeatherPages() {
        val result = ExaDiscovery.candidates(feed(item("https://publisher.example/a?utm_source=feed", "Orion Android", "Fabricated 99 GB") +
            item("https://publisher.example/a", "Duplicate") + item("https://127.0.0.1/private", "Unsafe") +
            item("https://user:pass@publisher.example/a", "Credential") + item("https://exa.ai/library/weather/london", "Generated weather") +
            item("https://news.example/a", "News"), "text/event-stream"))
        assertEquals(listOf("https://publisher.example/a", "https://news.example/a"), result.candidates.map { it.url })
        assertTrue(result.candidates.all { it.provider == "Exa" && it.instantAnswer == null && it.routingHint.isEmpty() })
        assertEquals(DiscoveryState.UNAVAILABLE, ExaDiscovery.candidates(feed("<html>Human check</html>", "text/html")).state)
        assertEquals(DiscoveryState.EMPTY, ExaDiscovery.candidates(feed("")).state)
        assertEquals(DiscoveryState.UNAVAILABLE, ExaDiscovery.candidates(WebDocument(ExaDiscovery.endpoint,
            "{\"id\":1,\"result\":{\"isError\":true}}", "application/json")).state)
    }

    @Test fun productionReaderUsesActualPageInsteadOfFabricatedSearchDescription() = runBlocking {
        val requests = mutableListOf<String>()
        val sources = PublicWebResearch(WebFetcher { url ->
            requests += url
            when {
                url == ExaDiscovery.endpoint -> feed(item("https://publisher.example/orion", "Orion memory", "Orion needs 99 GB"))
                url.endsWith("robots.txt") -> WebDocument(url, "User-agent: *\nAllow: /", "text/plain")
                else -> WebDocument(url, "<title>Orion memory</title><article><p>Orion memory requirements are 4 GB of shared RAM for this configuration. Orion runs one model at a time with its documented context size.</p></article>", "text/html")
            }
        }).search("Search for Orion memory")
        assertEquals("Exa", sources.single().discoveryProvider)
        assertEquals("WEB_PAGE", sources.single().kind)
        assertTrue(sources.single().excerpt.contains("4 GB"))
        assertFalse(sources.single().excerpt.contains("99 GB"))
        assertFalse(requests.any { "api.duckduckgo.com" in it })
    }

    @Test fun deniedDiscoveryBacksOffAndNeverUsesAnotherEngine() = runBlocking {
        val requests = mutableListOf<String>()
        val reader = PublicWebResearch(WebFetcher { requests += it; error("HTTP 429") })
        repeat(2) { assertTrue(reader.search("How is the election going on in Israel").isEmpty()) }
        assertEquals(1, requests.size)
        assertTrue(requests.single() == ExaDiscovery.endpoint)
        var calls = 0
        assertTrue(PublicWebResearch(WebFetcher { calls++; error("Unexpected") }, discoveryAllowed = { false }).search("Search for Orion").isEmpty())
        assertEquals(0, calls)
        val started = CompletableDeferred<Unit>()
        val job = launch { ExaDiscovery(WebFetcher { started.complete(Unit); awaitCancellation() }).discover(ResearchQuery("Search for Orion"), false) }
        started.await(); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }

    @Test fun followupsReuseOnlyAnAlreadyPublicTopicFromTheSameSession() {
        val source = EvidenceSource(1, "Pret menu", "https://publisher.example/pret", "Menu text", 1,
            discoveryProvider = "Exa", publicQuery = "price latte pret manger")
        val history = listOf(ChatMessageEntity(role = "user", content = "Private unsent project note", conversationId = 9),
            ChatMessageEntity(role = "assistant", content = "Background", conversationId = 9, sourcesJson = GroundingResult(sources = listOf(source)).json()))
        val question = ConversationalResearch.question("In uk what's the price", history)
        assertTrue(question.contains("price latte pret manger")); assertFalse(question.contains("Private"))
        assertEquals(AnswerPath.WEB_GROUNDED, AnswerRoutingPolicy.decide("In uk what's the price", history = history).path)
        assertEquals(source, GroundingResult.sources(GroundingResult(sources = listOf(source)).json()).single())
        assertEquals("In uk what's the price", ConversationalResearch.question("In uk what's the price", emptyList()))
        assertFalse(ConversationalResearch.validPublicQuery("api_key: private-value"))
        assertEquals("How is Jailer 2 movie?", ExaDiscovery.searchText(ResearchQuery("How is Jailer 2 movie?")))
    }

    @Test fun slowLaterPageCannotDiscardAlreadyReadEvidence() = runBlocking {
        val reader = PublicWebResearch(WebFetcher { url ->
            when {
                url == ExaDiscovery.endpoint -> feed(item("https://first.example/orion", "Orion memory") + item("https://slow.example/orion", "Orion memory"))
                url.endsWith("robots.txt") -> WebDocument(url, "User-agent: *\nAllow: /", "text/plain")
                url.contains("slow.example") -> { delay(5_000); error("Late page") }
                else -> WebDocument(url, "<title>Orion memory</title><article><p>Orion memory requirements are 4 GB for this tested configuration. The Orion model uses shared memory and retains one context at a time.</p></article>", "text/html")
            }
        }, researchBudgetMs = 2_000)
        val result = withTimeout(4_000) { reader.research("Search for Orion memory") {} }
        assertEquals("https://first.example/orion", result.sources.single().url)
        assertNull(result.failure)
    }

    @Test fun explicitlyLabelledWeatherSurvivesMissingDegreeGlyphAndNightForecast() {
        val day = java.time.LocalDate.now()
        val month = day.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        for (glyph in listOf("°", "\uFFFD", "")) {
            val html = "<title>London - BBC Weather</title><main><div class='WeatherObservationsContainer'><div class='ObservationsContainerText'>Observed at, 20:00 GMT ${day.dayOfMonth} $month</div><span>Temperature 13$glyph Celsius</span><span>Temperature 56$glyph Fahrenheit</span></div></main>"
            val source = WebPageExtractor.article(WebDocument("https://www.bbc.com/weather/2643743", html, "text/html"), 1, "Weather in London today")!!
            assertTrue(source.excerpt, source.excerpt.contains("Observed temperature: 13 °C (56 °F)."))
            assertTrue(EvidenceRanking.acceptable(source, ResearchPlanner.plan("Weather in London today")))
            assertTrue(EvidenceAnswer.resolve("The weather is mild [1].", listOf(source), "Weather in London today").text.contains("13 °C"))
        }
        val night = org.jsoup.Jsoup.parse("<a id='daylink-0'>Tonight<span class='wr-day-temperature__low'><span class='wr-value--temperature--c'>8°</span></span></a>")
        val text = WeatherPageEvidence.blocks(night, "London - BBC Weather", "https://www.bbc.com/weather/2643743").single()
        assertTrue(text.contains("Tonight forecast: low 8 °C.")); assertFalse(text.contains("Observed temperature"))
        assertTrue(WeatherPageEvidence.current(text, day))
        val ambiguous = org.jsoup.Jsoup.parse("<div class='WeatherObservationsContainer'><div class='ObservationsContainerText'>Observed at, 20:00 GMT</div>Temperature 13</div>")
        assertTrue(WeatherPageEvidence.blocks(ambiguous, "London", "https://www.bbc.com/weather/2643743").isEmpty())
    }

    @Test fun conversationalFramingCannotRejectOtherwiseRelevantReadPages() {
        val q = "How is the election going on in Israel"
        assertEquals(setOf("election", "israel"), ResearchQuery(q).terms)
        val election = EvidenceSource(1, "Israel election results", "https://publisher.example/israel", 
            "Israel election results show that no bloc has enough seats to form a majority. Vote counting and coalition talks continue.", 1,
            java.time.LocalDate.now().toString(), "WEB_PAGE", "PUBLISHED")
        assertTrue(EvidenceRanking.acceptable(election, ResearchPlanner.plan(q)))
        val technical = "Search Kotlin coroutines documentation"
        assertEquals(listOf("Kotlin"), ResearchPlanner.subjects(technical))
        val docs = election.copy(title = "Coroutines | Kotlin Documentation", url = "https://kotlinlang.org/docs/coroutines-overview.html",
            excerpt = "Kotlin coroutines simplify asynchronous programming. A coroutine can suspend execution and resume later without blocking a thread.", updatedAt = "")
        assertTrue(EvidenceRanking.acceptable(docs, ResearchPlanner.plan(technical)))
    }
}
