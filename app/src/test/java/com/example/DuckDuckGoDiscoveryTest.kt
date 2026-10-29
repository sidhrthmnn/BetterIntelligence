package com.example

import com.example.grounding.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuckDuckGoDiscoveryTest {
    private fun apiResearch(fetcher: WebFetcher) = PublicWebResearch(fetcher, DuckDuckGoDiscovery(fetcher))
    private fun document(json: String, mime: String = "application/x-javascript") =
        WebDocument(DuckDuckGoDiscovery.endpoint, json, mime)

    @Test fun parsesActualFieldsNestedTopicsAndOfficialSitesWithoutFollowingSearchOrBangLinks() {
        val result = DuckDuckGoDiscovery.candidates(document("""{
            "Heading":"Orion", "AbstractURL":"https://publisher.example/orion?utm_source=ddg",
            "AbstractText":"Fabricated search text: Orion needs 99 GB", "DefinitionURL":"http://dictionary.example/orion",
            "Results":[{"FirstURL":"https://publisher.example/orion","Text":"Duplicate"},
                {"FirstURL":"https://official.example/orion","Text":"Official site"}],
            "RelatedTopics":[{"Name":"Examples","Topics":[
                {"FirstURL":"https://duckduckgo.com/Orion","Text":"Search page"},
                {"FirstURL":"https://127.0.0.1/private","Text":"Private"},
                {"FirstURL":"https://user:password@unsafe.example/","Text":"Credentials"},
                {"FirstURL":"https://related.example/orion","Text":"Orion reference"}]}],
            "Redirect":"https://redirect.example/unrequested", "Answer":"99 GB"
        }"""))
        assertEquals(DiscoveryState.FOUND, result.state)
        assertEquals(listOf("https://publisher.example/orion", "https://dictionary.example/orion",
            "https://official.example/orion", "https://related.example/orion"), result.candidates.map { it.url })
        assertTrue(result.candidates.all { it.provider == "DuckDuckGo" })
        assertTrue(result.candidates.none { "99" in it.routingHint || "Fabricated" in it.title })
    }

    @Test fun rejectsHtmlJsonpMalformedAndAnswerOnlyPayloadsWithoutInventingSources() {
        for (page in listOf(document("<html>captcha</html>", "text/html"), document("callback({})"), document("{")))
            assertEquals(DiscoveryState.UNAVAILABLE, DuckDuckGoDiscovery.candidates(page).state)
        for (mime in listOf("application/json; charset=utf-8", "application/x-javascript", "text/javascript"))
            assertEquals(DiscoveryState.EMPTY, DuckDuckGoDiscovery.candidates(document("""{"Answer":"42","Type":"E"}""", mime)).state)
    }

    @Test fun topicReformulationIsFocusedAndDoesNotAddInventedYearsOrCastingSynonyms() {
        assertEquals("photosynthesis", DuckDuckGoDiscovery.searchText(ResearchQuery("Search for photosynthesis")))
        assertEquals("france", DuckDuckGoDiscovery.searchText(ResearchQuery("What's happening in France now")))
        assertEquals("doctor doom avengers doomsday", DuckDuckGoDiscovery.searchText(ResearchQuery("Who is playing Doctor Doom in Avengers Doomsday?")))
        assertEquals("Avengers Doomsday", DuckDuckGoDiscovery.searchText(ResearchQuery("Who is playing Doctor Doom in Avengers Doomsday?"), true))
    }

    @Test fun liveNewsDoesNotTreatAnEncyclopediaAbstractAsCurrentReporting() {
        val page = document("""{"Heading":"France","Type":"A","AbstractURL":"https://en.wikipedia.org/wiki/France","AbstractText":"France is a country."}""")
        assertEquals(DiscoveryState.FOUND, DuckDuckGoDiscovery.candidates(page, "Search for France").state)
        assertEquals(DiscoveryState.EMPTY, DuckDuckGoDiscovery.candidates(page, "What's happening in France now").state)
        assertEquals(DiscoveryState.EMPTY, DuckDuckGoDiscovery.candidates(document("""{"Heading":"Paris","Type":"D","AbstractURL":"https://en.wikipedia.org/wiki/Paris_(disambiguation)"}""")).state)
    }

    @Test fun apiResearchPrefersActualPublisherText() = runBlocking {
        val requests = mutableListOf<String>()
        val sources = apiResearch(WebFetcher { url ->
            requests += url
            when {
                url.startsWith(DuckDuckGoDiscovery.endpoint) -> document("""{"Heading":"Orion memory","AbstractURL":"https://publisher.example/orion","AbstractText":"Orion needs 99 GB for its documented configuration. The summary describes a different configuration and is not the actual publisher page.","Answer":"99 GB"}""")
                url.endsWith("robots.txt") -> WebDocument(url, "User-agent: *\nAllow: /", "text/plain")
                else -> WebDocument(url, "<title>Orion memory requirements</title><article><p>Orion memory requirements are 4 GB for the documented configuration. Orion supports one active model and uses shared memory for local inference on compatible devices.</p></article>", "text/html")
            }
        }).search("Search for Orion memory")
        assertEquals(1, sources.size)
        assertEquals("DuckDuckGo", sources.single().discoveryProvider)
        assertEquals("WEB_PAGE", sources.single().kind)
        assertTrue(sources.single().excerpt.contains("4 GB"))
        assertFalse(sources.single().excerpt.contains("99 GB"))
        assertTrue(requests.all { it.startsWith(DuckDuckGoDiscovery.endpoint) || it.startsWith("https://publisher.example/") })
        assertFalse(requests.any { it == "https://api.duckduckgo.com/robots.txt" })
    }

    @Test fun emptyLegacyApiPayloadProvidesNoSource() {
        assertTrue(DuckDuckGoDiscovery.candidates(document("{}")).candidates.isEmpty())
    }

    @Test fun stableTopicSummaryRemainsExplicitApiEvidenceWhenPublisherCannotBeRead() = runBlocking {
        val sources = apiResearch(WebFetcher { url ->
            if (url.startsWith(DuckDuckGoDiscovery.endpoint)) document("""{"Heading":"Photosynthesis","AbstractURL":"https://publisher.example/photosynthesis","AbstractSource":"Science reference","AbstractText":"Photosynthesis is a process in which plants convert light energy into chemical energy stored in sugars. Plants use chlorophyll to absorb light."}""")
            else error("HTTP 403")
        }).search("Search for photosynthesis")
        val source = sources.single()
        assertEquals("INSTANT_ANSWER", source.kind)
        assertEquals("", source.updatedAt)
        assertEquals("API_SUMMARY", source.dateKind)
        assertTrue(GroundingPolicy.prepareEvidence("Search for photosynthesis", sources, 4000).prompt.contains("not independently"))
        assertEquals("API_SUMMARY", AnswerSourceStatus.forEvidence("EVIDENCE_CITED", sources))
        assertEquals("UNVERIFIED_PARTIAL", AnswerSourceStatus.forEvidence("UNVERIFIED_PARTIAL", sources))
        assertEquals(source, GroundingResult.sources(GroundingResult(sources = sources).json()).single())
    }

    @Test fun apiSummariesCannotEstablishLiveOrExplicitlyVerifiedFacts() {
        for (question in listOf("What's happening in France now", "Weather in London today", "Who is the current UK prime minister?",
            "Who is playing Doctor Doom in Avengers Doomsday?", "Verify this using official sources: photosynthesis uses light")) {
            assertFalse(question, DuckDuckGoDiscovery.summaryAllowed(question))
            val source = EvidenceSource(1, question, "https://publisher.example/reference", question.repeat(4), 1,
                java.time.LocalDate.now().toString(), "INSTANT_ANSWER", discoveryProvider = "DuckDuckGo")
            assertFalse(question, EvidenceRanking.acceptable(source, ResearchPlanner.plan(question)))
        }
    }
}
