package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.grounding.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in public fixtures. Uses Android's real transport and publisher reader;
 * no model allocation or access to the installed application's conversations. */
class ResearchDiscoveryDeviceTest {
    @Test fun realDiscoveryAndPublisherEvidenceAcrossNineEnglishQuestions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("researchDiscovery") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val rows = JSONArray()
        val output = File(context.filesDir, "research-discovery-device.json")
        val questions = listOf("What's happening in France now", "What's happening in France?",
            "Who is playing Doctor Doom in Avengers Doomsday?", "Who is the current UK prime minister?",
            "What is the weather in London today?", "Search for Android Vulkan documentation",
            "Search for Kotlin coroutines documentation", "Search for photosynthesis", "What is the latest Android version?")
        for (question in questions) {
            val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
            val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to
                PublicWebResearch(SearchHttp(onRequest = { requests += it }))))
            val route = AnswerRoutingPolicy.decide(question)
            val started = android.os.SystemClock.elapsedRealtime()
            val result = coordinator.retrieve(question, true, GroundingMode.AUTO, SearchProviderId.WEB)
            rows.put(JSONObject().put("question", question).put("route", route.path.name)
                .put("status", result.status).put("failure", result.failure?.name)
                .put("wallMs", android.os.SystemClock.elapsedRealtime() - started)
                .put("requests", JSONArray(requests.toList())).put("sources", JSONArray(result.json())))
            output.writeText(rows.toString(2))
            assertEquals(question, AnswerPath.WEB_GROUNDED, route.path)
            assertTrue(result.sources.all { it.kind == "WEB_PAGE" && it.discoveryProvider in setOf("Exa", "Mwmbl") })
            assertTrue("Publisher text only", result.sources.all {
                !it.url.contains("mcp.exa.ai") && !it.url.contains("mwmbl.org") && it.excerpt.isNotBlank()
            })
            assertFalse("No HTML search scraping", requests.any {
                it.contains("duckduckgo.com") || it.contains("bing.com/search") || it.contains("google.com/search")
            })
        }
        assertTrue("Read actual publisher evidence, not just search hits", (0 until rows.length()).any {
            rows.getJSONObject(it).getJSONArray("sources").length() > 0
        })
    }
}
