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
    @Test fun weatherUsesOnlyTheSamePageProviderAndNeverAnExternalApi() = runBlocking {
        val questions = mutableListOf<String>()
        val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to object : SearchProvider {
            override suspend fun search(question: String): List<EvidenceSource> {
                questions += question
                return listOf(EvidenceSource(1, "London weather", "https://weather.example/london", "Current weather", 1))
            }
        }))
        assertEquals("SOURCES_RETRIEVED", coordinator.retrieve("Weather in London today", true, GroundingMode.ON, SearchProviderId.WEB).status)
        assertEquals(listOf("Weather in London today"), questions)
        assertEquals("LOCAL", coordinator.retrieve("Weather in London today", false, GroundingMode.ON, SearchProviderId.WEB).status)
        assertEquals(1, questions.size)
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
