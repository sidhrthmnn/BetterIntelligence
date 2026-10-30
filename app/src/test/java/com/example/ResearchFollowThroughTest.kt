package com.example

import com.example.data.ChatMessageEntity
import com.example.grounding.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class ResearchFollowThroughTest {
    private fun user(text: String) = ChatMessageEntity(role = "user", content = text, conversationId = 9)
    private fun assistant(text: String) = ChatMessageEntity(role = "assistant", content = text, conversationId = 9)
    private fun page(title: String, body: String, url: String = "https://publisher.example/report") =
        EvidenceSource(1, title, url, "$title\n$body", 1, java.time.LocalDate.now().toString(), "WEB_PAGE", "PUBLISHED")

    @Test fun phoneLookupsResearchBeforeTheModelCanGuessOrRefuse() {
        for (q in listOf("who is the next james bond", "Is jubilee line status good in tfl", "Is jubilee line fine in tfl london",
            "Find status of the line from any websites", "Where is the Taj Mahal?", "Who wrote Pride and Prejudice?"))
            assertEquals(q, AnswerPath.WEB_GROUNDED, AnswerRoutingPolicy.decide(q).path)
        for (q in listOf("Explain how temperature is measured.", "Explain how weather forecasts work.", "Write a poem about rain today", "Should I take cold medicine today?", "Who am I?"))
            assertEquals(q, AnswerPath.LOCAL, AnswerRoutingPolicy.decide(q).path)
    }
    @Test fun raincoatLocationAndConfirmationKeepTheActualTaskWithoutInventingACity() {
        val task = "should i wear a raincoat today"
        val first = listOf(user(task), assistant("I need to know your current location and the weather forecast for today to tell you if you should wear a raincoat."))
        assertEquals(AnswerPath.CLARIFICATION, AnswerRoutingPolicy.decide(task).path)
        val resolved = ConversationalResearch.continuation("London", first)!!.first
        assertTrue(resolved.contains("raincoat")); assertEquals("London", ConversationalResearch.weatherCity(resolved))
        assertEquals(AnswerPath.WEB_GROUNDED, AnswerRoutingPolicy.decide(resolved).path)
        assertEquals(setOf("weather", "london"), ResearchQuery(resolved).terms)
        val chain = first + user("London") + assistant("I need to check the current weather forecast for London to tell you if you should wear a raincoat.")
        assertTrue(ConversationalResearch.continuation("Ok", chain)!!.first.contains("raincoat"))
        val guessed = listOf(user(task), assistant("I need to check the current weather forecast for Paris to answer."))
        val retry = ConversationalResearch.continuation("Ok", guessed)!!.first
        assertFalse(retry.contains("Paris")); assertEquals(AnswerPath.CLARIFICATION, AnswerRoutingPolicy.decide(retry).path)
        assertNull(ConversationalResearch.continuation("Write me a poem", first))
    }
    @Test fun explicitFindContinuationUsesTheNamedPublicTaskAndStillHonoursOfflinePrivacy() = runBlocking {
        val history = listOf(user("Is jubilee line status good in tfl"), assistant("I do not have the capability to browse the live internet."))
        val resumed = ConversationalResearch.continuation("Find status of the line from any websites", history)!!
        assertTrue(resumed.first.contains("jubilee")); assertTrue(resumed.second)
        var calls = 0
        val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to object : SearchProvider {
            override suspend fun search(question: String): List<EvidenceSource> { calls++; return listOf(page("Jubilee line status", "The Jubilee line has a reported service disruption.")) }
        }))
        assertTrue(coordinator.retrieve(resumed.first, true, GroundingMode.ON, SearchProviderId.WEB).sources.isNotEmpty())
        coordinator.retrieve(resumed.first, true, GroundingMode.OFF, SearchProviderId.WEB)
        coordinator.retrieve(resumed.first, false, GroundingMode.ON, SearchProviderId.WEB)
        assertEquals(1, calls)
        assertEquals(AnswerPath.LOCAL, AnswerRoutingPolicy.decide("Do not search. " + resumed.first, mode = GroundingMode.ON).path)
    }
    @Test fun properReportsProduceABriefWithoutConfirmingCastingOrQuotingBettingOdds() {
        val q = "who is the next james bond"
        val report = page("James Bond screen tests", "James Bond candidates Jack Lowden and Callum Turner will screen test this weekend, reports say. An actor has not yet been confirmed.")
        val odds = page("Next James Bond Kalshi Odds", "Jack Lowden has 58% odds in the next James Bond prediction market.", "https://kalshiview.com/bond")
        val result = EvidenceAnswer.resolve("Jack Lowden is the next James Bond [1].", listOf(report, odds.copy(id = 2)), q)
        assertTrue(result.text, result.sourceBrief); assertEquals(AnswerSupport.PARTIAL, result.support)
        assertTrue(result.text.contains("screen test")); assertTrue(result.text.contains("not yet been confirmed"))
        assertFalse(result.text.contains("58%")); assertFalse(result.text.contains("Kalshi"))
        assertEquals("What sources say · Answer remains incomplete", AnswerSourceStatus.label("SOURCE_BRIEF"))
    }
    @Test fun partialComparisonsCanShowEachReportedValueWithoutInventingAWinner() {
        val a = page("Orion memory", "Orion requires 4 GB of shared memory only on the tested device.")
        val b = page("Nova memory", "Nova requires 6 GB of shared memory only on the tested device.", "https://second.example/nova").copy(id = 2)
        val result = EvidenceAnswer.resolve("Orion is always better than Nova [1].", listOf(a, b), "Compare Orion vs Nova memory requirements")
        assertTrue(result.text, result.sourceBrief); assertTrue(result.text.contains("4 GB")); assertTrue(result.text.contains("6 GB"))
        assertTrue(result.text.contains("only on the tested device")); assertFalse(result.text.contains("always better"))
    }
    @Test fun noProperBodyStillCannotBecomeASourceBrief() {
        val q = "who is the next james bond"
        for (source in listOf(page("James Bond", "Loading..."), page("James Bond", "A wholly unrelated paragraph about gardening and flowering plants."),
            page("James Bond Kalshi odds", "James Bond has a speculative prediction market price of 58 percent.")))
            assertNull(SourceBrief.build(q, listOf(source), emptyList()))
        assertNull(SourceBrief.build(q, listOf(page("James Bond", "James Bond candidates will screen test this weekend.").copy(kind = "INSTANT_ANSWER")), emptyList()))
    }
    @Test fun shortOfficialStatusBeatsStationNavigation() {
        val q = "Is jubilee line status good in tfl"
        val source = page("Jubilee Underground line", "Jubilee Good service", "https://tfl.gov.uk/tube/route/jubilee/")
            .copy(excerpt = "Jubilee › Status\nJubilee Good service\n\nJubilee › No disruptions\nBaker Street Underground Station connects to other lines and stations.")
        val result = SourceBrief.build(q, listOf(source), emptyList())!!
        assertTrue(result.text.contains("Jubilee Good service")); assertFalse(result.text.contains("Baker Street"))
    }
    @Test fun actualReportParagraphsBeatRelatedStoryLinkLists() {
        val q = "who is the next james bond"
        val title = "James Bond screen tests, reports say"
        val html = "<title>$title</title><article><h1>$title</h1><ul><li><a href='/other'>The next James Bond questions fans want answered</a></li><li><a href='/more'>Search for James Bond officially kicks off</a></li></ul>" +
            "<p>Sources report that final screen test offers have been made. While unconfirmed, Jack Lowden and Callum Turner are expected to test.</p></article>"
        val source = WebPageExtractor.article(WebDocument("https://publisher.example/report", html, "text/html"), 1, q)!!
        val prepared = GroundingPolicy.prepareEvidence(q, listOf(source), 6000)
        val answer = EvidenceAnswer.resolve("A definitive actor is known.", prepared.sources, q)
        assertTrue(answer.text, answer.sourceBrief); assertTrue(answer.text.contains("Jack Lowden"))
        assertTrue(answer.text.contains("unconfirmed")); assertFalse(answer.text.contains("questions fans want answered"))
    }
    @Test fun weatherUsesTheForecastDayAndRecognisesExplicitUnitsWithoutADegreeGlyph() {
        assertTrue(EvidenceAnswer.hasTemperature("Temperatures will reach 16C around 2pm."))
        assertFalse(EvidenceAnswer.hasTemperature("The temperature will reach 16 around 2pm."))
        val today = java.time.LocalDate.of(2026, 10, 30)
        assertFalse(WeatherPageEvidence.requestedDay("London Weather Friday 6 Nov: Rain, Outfit & Plans", "Weather in London today", today))
        assertTrue(WeatherPageEvidence.requestedDay("London Weather 31 Oct", "Weather in London tomorrow", today))
    }
    @Test fun rejectedPublisherControlsCannotReturnThroughThePlainBodyFallback() {
        val html = "<title>James Bond casting</title><article><h1>James Bond casting</h1><p>Swipe for next article</p><p>Download our app</p><p>News · Sport · Culture · Lifestyle</p></article>"
        assertNull(WebPageExtractor.article(WebDocument("https://publisher.example/bond", html, "text/html"), 1, "who is the next james bond"))
    }
    @Test fun networkStatusMustBelongToTheNamedLineAndShortVisibleValuesAreKept() {
        val q = "Is jubilee line status good in tfl"
        val wrong = page("Tube, DLR and Tram status", "Good service on all other lines", "https://tfl.gov.uk/status")
            .copy(excerpt = "Status › Tram\nGood service on all other lines")
        assertFalse(FactualResearch.usable(wrong, q)); assertNull(SourceBrief.build(q, listOf(wrong), emptyList()))
        val empty = page("Jubilee Underground line", "There are no reported disruptions at any .", "https://tfl.gov.uk/tube/route/jubilee/")
        assertFalse(FactualResearch.usable(empty, q))
        val html = "<title>Jubilee Underground line</title><main><h1>Jubilee</h1><h2>Status</h2><span>Good service</span></main>"
        val source = WebPageExtractor.article(WebDocument("https://tfl.gov.uk/tube/route/jubilee/", html, "text/html"), 1, q)!!
        assertTrue(source.excerpt, PublicQuestionIntent.serviceEvidence(source, q))
        assertTrue(SourceBrief.build(q, listOf(source), emptyList())!!.text.contains("Good service"))
    }
}
