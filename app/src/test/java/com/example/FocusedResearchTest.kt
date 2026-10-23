package com.example

import com.example.data.ChatMessageEntity
import com.example.grounding.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusedResearchTest {
    @Test fun followUpsUseOnlyPublicSourceTopicNotPrivateHistory() {
        val source = EvidenceSource(1, "Formula 1 race results in Austin", "https://example.com/f1", "Public race result", 1)
        val history = listOf(ChatMessageEntity(role = "user", content = "My private project budget is 500"),
            ChatMessageEntity(role = "assistant", content = "Private generated text", sourcesJson = GroundingResult(sources = listOf(source)).json()))
        val query = ConversationalResearch.question("What happened to the driver who finished second?", history)
        assertTrue(query.contains("Formula 1")); assertFalse(query.contains("500")); assertFalse(query.contains("Private"))
        assertEquals("Write a poem", ConversationalResearch.question("Write a poem", history))
        assertEquals("What happened to him?", ConversationalResearch.question("What happened to him?", emptyList()))
    }
    @Test fun autoModeDoesNotResearchCreativeRequestsAndWeatherNeedsExplicitCity() = runBlocking {
        assertFalse(GroundingPolicy.shouldSearch(true, GroundingMode.AUTO, "Write a poem about today"))
        assertTrue(GroundingPolicy.shouldSearch(true, GroundingMode.ON, "Write a poem about today"))
        assertEquals("London, UK", ConversationalResearch.weatherCity("Weather in London, UK today?"))
        var calls = 0
        val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to object : SearchProvider {
            override suspend fun search(question: String): List<EvidenceSource> { calls++; return emptyList() }
        }))
        val result = coordinator.retrieve("What is the weather today?", true, GroundingMode.AUTO, SearchProviderId.WEB)
        assertEquals("NEEDS_LOCATION", result.status); assertEquals(ConversationalResearch.askCity, result.notice); assertEquals(0, calls)
        assertEquals("LOCAL", coordinator.retrieve("Weather today", false, GroundingMode.ON, SearchProviderId.WEB).status)
    }
    @Test fun structuredWeatherUsesOnlyFetchedNumbersAndCannotAcceptStaleData() = runBlocking {
        var stale = false
        val service = WeatherResearch(WebFetcher { url -> WebDocument(url,
            if (url.contains("geocoding")) """{"results":[{"name":"London","country":"United Kingdom","latitude":51.5,"longitude":-0.1}]}"""
            else """{"current":{"time":${if (stale) 1 else System.currentTimeMillis()/1000},"temperature_2m":12.3,"apparent_temperature":11.2,"precipitation":0.1,"wind_speed_10m":9.2},"current_units":{"temperature_2m":"°C","apparent_temperature":"°C","precipitation":"mm","wind_speed_10m":"km/h"}}""", "application/json") })
        val result = service.current("London")
        assertEquals("STRUCTURED_ANSWER", result.status); assertTrue(result.notice.contains("12.3 °C")); assertTrue(result.notice.contains("[1]"))
        assertEquals("STRUCTURED_DATA", result.sources.single().kind)
        stale = true; assertTrue(runCatching { service.current("London") }.isFailure)
    }
    @Test fun robotsHonoursSpecificGroupsWildcardsAndAllowPrecedence() {
        val policy = "User-agent: *\nDisallow: /private\nAllow: /private/public\nDisallow: /*?secret=\n"
        assertFalse(ResearchRobots.permits(policy, "/private/page")); assertTrue(ResearchRobots.permits(policy, "/private/public/page"))
        assertFalse(ResearchRobots.permits(policy, "/article?secret=yes"))
        assertTrue(ResearchRobots.permits("User-agent: otherbot\nDisallow: /\n", "/article"))
        assertFalse(ResearchRobots.permits("User-agent: BetterIntelligence\nDisallow: /\n", "/article"))
    }
    @Test fun blockedRobotsPreventsArticleFetchAndCachesThePolicy() = runBlocking {
        val urls = mutableListOf<String>()
        val reader = PublicWebResearch(WebFetcher { url -> urls += url; WebDocument(url, "User-agent: *\nDisallow: /", "text/plain") })
        repeat(2) { assertTrue(reader.search("Read https://example.com/private").isEmpty()) }
        assertEquals(listOf("https://example.com/robots.txt"), urls)
    }
}
