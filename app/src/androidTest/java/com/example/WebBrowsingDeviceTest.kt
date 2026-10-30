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

/** Opt-in public research only. Never reads production chats or allocates an LLM. */
class WebBrowsingDeviceTest {
    @Test fun actualFailedQuestionsAndOtherTopicsUseAndroidTransportAndReadSources() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("webBrowsing") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val rows = JSONArray()
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to PublicWebResearch(SearchHttp(onRequest = { requests += it }))))
        val questions = listOf("Who is acting as Doom in Avengers Doomsday", "How is the election going on in Israel",
            "How is Jailer 2 movie?", "When is Ramayana movie releasing?", "What is the price of a latte at Pret in the UK?",
            "What is the weather in London today?", "Search Android Vulkan documentation", "Search Kotlin coroutines documentation")
        for (question in questions) {
            requests.clear()
            val start = android.os.SystemClock.elapsedRealtime()
            val result = coordinator.retrieve(question, true, GroundingMode.AUTO, SearchProviderId.WEB)
            rows.put(JSONObject().put("question", question).put("route", AnswerRoutingPolicy.decide(question).path.name)
                .put("status", result.status).put("failure", result.failure?.name)
                .put("wallMs", android.os.SystemClock.elapsedRealtime() - start)
                .put("requests", JSONArray(requests.toList())).put("sources", JSONArray(result.json())))
            File(context.filesDir, "web-browsing-device.json").writeText(rows.toString(2))
            assertEquals(question, AnswerPath.WEB_GROUNDED, AnswerRoutingPolicy.decide(question).path)
            assertTrue(result.sources.all { it.kind == "WEB_PAGE" && it.discoveryProvider == "Exa" })
            assertTrue("No readable evidence for $question: ${result.failure}", result.sources.isNotEmpty())
            assertFalse(requests.any { "mwmbl.org" in it || "api.duckduckgo.com" in it || "html.duckduckgo.com" in it || "lite.duckduckgo.com" in it })
        }
        val doom = rows.getJSONObject(0).getJSONArray("sources").toString()
        assertTrue("Retrieve an actual actor passage, not search snippets: $doom", doom.contains("Robert Downey", true))
        assertTrue("Retrieve current election coverage", rows.getJSONObject(1).getJSONArray("sources").length() > 0)
    }
}
