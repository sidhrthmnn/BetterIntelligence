package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.grounding.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GroundingDeviceTest {
    @Test fun liveWikipediaAndMissingBraveCredentials() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("groundingNetwork") != "true") return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(context.packageName.endsWith(".validation"))
        val wiki = WikipediaSearch(SearchHttp())
        val results = wiki.search("Moon illusion")
        assertTrue("Wikipedia must return actual source text", results.isNotEmpty())
        assertTrue(results.all { it.excerpt.isNotBlank() && GroundingPolicy.publicHttpsUrl(it.url) })
        val malayalam = wiki.search("ചന്ദ്രൻ")
        assertTrue("Malayalam Wikipedia must return actual source text", malayalam.isNotEmpty())
        assertTrue(malayalam.any { "ml.wikipedia.org" in it.url })
        File(context.filesDir, "grounding-live-synthetic.json").writeText(org.json.JSONObject()
            .put("english", org.json.JSONArray(GroundingResult(sources = results).json()))
            .put("malayalam", org.json.JSONArray(GroundingResult(sources = malayalam).json())).toString(2))
        val credentials = ProviderCredentials(context)
        if (!credentials.hasBraveKey) {
            val coordinator = GroundingCoordinator(mapOf(SearchProviderId.BRAVE to BraveSearch(SearchHttp(), credentials)))
            assertEquals("SEARCH_FAILED", coordinator.retrieve("Moon", true, GroundingMode.ON, SearchProviderId.BRAVE).status)
        }
    }
}
