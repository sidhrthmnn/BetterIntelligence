package com.example

import com.example.grounding.*
import com.example.data.SettingsPreferencesRepository
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchRecoveryTest {
    @Test fun questionFramingAndSearchVerbsDoNotBecomeEvidenceRequirements() {
        val question = "What's happening in France now"
        val source = EvidenceSource(1, "France talks resume", "https://publisher.example/france-talks",
            "France talks resumed on Thursday. Representatives met in Paris to discuss the proposals and agreed to meet again.",
            System.currentTimeMillis(), java.time.LocalDate.now().toString(), "WEB_PAGE", "PUBLISHED")
        assertEquals(setOf("france"), ResearchQuery(question).terms)
        assertTrue(EvidenceRanking.acceptable(source, ResearchPlanner.plan(question)))
        val definition = source.copy(title = "Photosynthesis", excerpt = "Photosynthesis is the process by which plants convert light energy into chemical energy stored in sugars.")
        assertTrue(EvidenceRanking.acceptable(definition, ResearchPlanner.plan("Search for photosynthesis")))
        assertFalse(EvidenceRanking.acceptable(source.copy(updatedAt = java.time.LocalDate.now().minusDays(20).toString()), ResearchPlanner.plan(question)))
    }

    @Test fun articleFollowUpDoesNotWasteItsBudgetOnAuthorProfilesOrEmptyLinkLists() = runBlocking {
        val requests = mutableListOf<String>()
        val today = java.time.LocalDate.now()
        val fetcher = WebFetcher { url ->
            requests += url
            val body = when {
                url.endsWith("robots.txt") -> "User-agent: *\nAllow: /"
                url.endsWith("old") -> "<html><head><title>France report</title><meta property='article:published_time' content='${today.minusDays(20)}'></head><body><main><p>France talks took place in Paris. Representatives discussed earlier proposals and planned future meetings about the same issues.</p><a href='/author/france'>France reporter</a></main></body></html>"
                url.endsWith("hub") -> "<html><head><title>France news</title></head><body><main><p>France news and reports from our correspondents, with the latest coverage and developments from across the country.</p><a href='/france/new'>France talks resume in Paris</a></main></body></html>"
                url.endsWith("new") -> "<html><head><title>France talks resume in Paris</title><meta property='article:published_time' content='$today'></head><body><article><p>France talks resumed in Paris today. Representatives met to discuss the proposals and agreed to another meeting on Thursday.</p></article></body></html>"
                else -> error("Unexpected publisher request")
            }
            WebDocument(url, body, if (url.endsWith("robots.txt")) "text/plain" else "text/html")
        }
        val discovery = DiscoveryProvider { _, _ -> DiscoveryResult(listOf(
            ResearchCandidate("https://publisher.example/france/old", "France report"),
            ResearchCandidate("https://publisher.example/france/hub", "France news")), DiscoveryState.FOUND) }
        val outcome = PublicWebResearch(fetcher, discovery).research("What's happening in France now") {}
        assertTrue(outcome.sources.any { it.url.endsWith("/new") })
        assertFalse(requests.any { "/author/" in it })
    }

    @Test fun franceContractionsAndImplicitCurrentEventsResearchWithoutTurningConceptsIntoNews() {
        for (q in listOf("What's happening in france now", "What is happening in France now", "What's happening in France?",
            "Whats happening in France", "What's going on in Iran?", "What is new in Android?")) {
            val route = AnswerRoutingPolicy.decide(q)
            assertEquals(q, AnswerPath.WEB_GROUNDED, route.path)
            assertTrue(q, route.requiresLive)
        }
        for (q in listOf("Explain recursion", "What is happening in photosynthesis?", "What happened in France in 1789?",
            "Explain what is happening in my current code", "Write a story about France today"))
            assertEquals(q, AnswerPath.LOCAL, AnswerRoutingPolicy.decide(q).path)
        assertEquals(LocalDate.of(2026, 10, 27), ResearchQuery("What's happening in France?", LocalDate.of(2026, 10, 29)).earliest)
    }



    @Test fun modelCurrentDataRefusalCanTriggerOnlyOneEligiblePublicResearchPass() {
        val refusal = "I do not have real-time access to current events. Check a news source."
        assertTrue(AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(AnswerRoutingPolicy.decide("Who developed OrionHub?"), refusal))
        for (q in listOf("What was my project idea?", "Write a story", "Offline: who developed OrionHub?", "What is 15% of 240?", "What is my password: abcdef?"))
            assertFalse(q, AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(AnswerRoutingPolicy.decide(q), refusal))
        assertFalse(AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(AnswerRoutingPolicy.decide("Who developed OrionHub?", false), refusal))
        assertFalse(AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(AnswerRoutingPolicy.decide("What's happening in France now"), refusal))
        assertFalse(AnswerRoutingPolicy.shouldResearchAfterLocalAnswer(AnswerRoutingPolicy.decide("Who developed OrionHub?"), "OrionHub was developed by Example."))
        assertEquals("WEB_DISABLED", AnswerRoutingPolicy.recover(AnswerRoutingPolicy.decide("Weather in London today", false)).status)
    }





    @Test fun cancellationIsNotSwallowedByDiscovery() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val provider = DuckDuckGoDiscovery(WebFetcher { started.complete(Unit); awaitCancellation() })
        val job = launch { provider.discover(ResearchQuery("Current France news"), false) }
        started.await(); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }

    @Test fun updatedConsentAndPersistedProviderMetadataStayHonest() {
        assertFalse(SettingsPreferencesRepository.webConsentAccepted("true", "3"))
        assertFalse(SettingsPreferencesRepository.webConsentAccepted("true", "2"))
        assertFalse(SettingsPreferencesRepository.webConsentAccepted("false", "5"))
        assertFalse(SettingsPreferencesRepository.webConsentAccepted("true", "4"))
        assertTrue(SettingsPreferencesRepository.webConsentAccepted("true", "5"))
        val source = EvidenceSource(1, "Publisher", "https://publisher.example/article", "Read page text", 1, discoveryProvider = "DuckDuckGo")
        assertEquals(source, GroundingResult.sources(GroundingResult(sources = listOf(source)).json()).single())
        assertEquals("", GroundingResult.sources("""[{"id":1,"title":"Old","url":"https://publisher.example/old"}]""").single().discoveryProvider)
    }
}
