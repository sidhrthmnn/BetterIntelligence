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

/** Public synthetic queries only. Records actual publisher reads, never snippets as evidence. */
class MwmblOnlyDeviceTest {
    @Test fun researchUsesOnlyMwmblAndReadsPublisherPages() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mwmblOnly") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val rows = JSONArray()
        val output = File(context.filesDir, "mwmbl-only-device.json")
        for (question in listOf("Who is playing Doctor Doom in Avengers Doomsday?",
            "Search for Kotlin coroutines documentation", "Search for Vulkan Android documentation")) {
            val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
            val transport = SearchHttp(onRequest = { requests += it })
            val robots = ResearchRobots(transport)
            val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to PublicWebResearch(transport,
                MwmblDiscovery(transport) { robots.allowed(it) })))
            val start = android.os.SystemClock.elapsedRealtime()
            val result = coordinator.retrieve(question, true, GroundingMode.AUTO, SearchProviderId.WEB)
            val row = JSONObject().put("question", question).put("status", result.status).put("failure", result.failure?.name)
                .put("wallMs", android.os.SystemClock.elapsedRealtime() - start).put("requests", JSONArray(requests.toList()))
                .put("sources", JSONArray(result.json()))
            rows.put(row); output.writeText(rows.toString(2))
            assertFalse("No retired search-provider requests", requests.any { "duckduckgo.com" in it || "bing.com" in it })
            assertTrue("Mwmbl discovery must be attempted", requests.any { it.startsWith("https://api.mwmbl.org/") })
            assertTrue(result.sources.all { it.kind == "WEB_PAGE" && !it.url.contains("mwmbl.org") })
            if (result.sources.isEmpty()) assertEquals("SEARCH_FAILED", result.status)
        }
        assertTrue("At least one actual publisher page must be readable on this connection",
            (0 until rows.length()).any { rows.getJSONObject(it).getJSONArray("sources").length() > 0 })
    }
}
