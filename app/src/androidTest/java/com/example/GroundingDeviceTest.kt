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
    @Test fun livePublicWebResearch() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("groundingNetwork") != "true") return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(context.packageName.endsWith(".validation"))
        val research = PublicWebResearch()
        val results = research.search("https://www.iana.org/help/example-domains")
        assertTrue("The public page must provide actual source text", results.isNotEmpty())
        assertTrue(results.all { it.excerpt.isNotBlank() && GroundingPolicy.publicHttpsUrl(it.url) })
        val discovered = research.search("Android Vulkan graphics")
        assertTrue("Search must discover readable public pages", discovered.isNotEmpty())
        File(context.filesDir, "grounding-live-synthetic.json").writeText(org.json.JSONObject()
            .put("english", org.json.JSONArray(GroundingResult(sources = results).json()))
            .put("discovered", org.json.JSONArray(GroundingResult(sources = discovered).json())).toString(2))
    }
}
