package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.*
import com.example.grounding.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DirectResearchDeviceTest {
    @Test fun recentPublisherDatesAreAnsweredByTheLocalModel() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("recentPublisherAnswer") != "true") return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(context.packageName.endsWith(".validation"))
        val question = "When was the October 2026 Android Security Bulletin published and updated? https://source.android.com/docs/security/bulletin/2026/2026-10-01?hl=en"
        val sources = PublicWebResearch().search(question)
        assertTrue("Actual recent publisher text is required", sources.isNotEmpty())
        val prepared = GroundingPolicy.prepareEvidence(question, sources, 2300)
        val engine = InferenceEngine(context)
        try {
            engine.setProcessingMode(ProcessingMode.CPU)
            engine.loadCustomGguf(File(context.filesDir, "models/Qwen_Qwen3-1.7B-Q4_K_M.gguf")).getOrThrow()
            val text = engine.generateSync(prepared.prompt, GenerationParams(maxTokens = 160, contextSize = 2048, temperature = 0f,
                systemPrompt = "Use only the supplied evidence. Copy dates exactly and cite the source IDs."))
            val checked = GroundingPolicy.validateCitations(text, prepared.sources)
            File(context.filesDir, "recent-publisher-answer.json").writeText(JSONObject().put("question", question)
                .put("raw", text).put("answer", checked.first).put("cited", checked.second)
                .put("sources", JSONArray(GroundingResult(sources = prepared.sources).json())).toString(2))
            // Fixed, independently read official bulletin: published October 5,
            // updated October 8, 2026. This is one measured case, not a general eval.
            assertTrue(text, checked.second)
            assertTrue(text, text.contains("2026") && Regex("\\b5\\b|10-05").containsMatchIn(text) && Regex("\\b8\\b|10-08").containsMatchIn(text))
        } finally { engine.unloadActiveModel() }
    }
    @Test fun actualHtmlDiscoveryAndPublisherExtraction() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("directResearchNetwork") != "true") return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(context.packageName.endsWith(".validation"))
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = SearchHttp()
        val recorder = object : WebFetcher {
            override suspend fun fetch(url: String): WebDocument { requests += url; return http.fetch(url) }
            override suspend fun fetchPage(url: String, permitted: suspend (String) -> Boolean): WebDocument {
                requests += url; return http.fetchPage(url, permitted)
            }
        }
        val reader = PublicWebResearch(recorder)
        val direct = reader.search("https://www.iana.org/help/example-domains")
        assertTrue("Direct publisher evidence must be readable", direct.any { it.excerpt.contains("documentation", true) })
        val discovered = reader.search("Android Vulkan graphics documentation")
        assertTrue("Public HTML search must yield actual readable publisher evidence", discovered.isNotEmpty())
        assertTrue(discovered.all { it.kind == "WEB_PAGE" && !it.url.contains("duckduckgo.com") })
        assertFalse(requests.any { "format=rss" in it || "api." in it || "open-meteo" in it || "bing.com" in it })
        File(context.filesDir, "direct-research-results.json").writeText(JSONObject()
            .put("direct", JSONArray(GroundingResult(sources = direct).json()))
            .put("discovered", JSONArray(GroundingResult(sources = discovered).json()))
            .put("requests", JSONArray(requests)).toString(2))
    }

    @Test fun pinnedNewGgufModelRunsOnRealCpu() = runBlocking {
        val requested = InstrumentationRegistry.getArguments().getString("newGgufFilename") ?: return@runBlocking
        require(requested in setOf("Qwen_Qwen3-1.7B-Q4_K_M.gguf", "SmolLM3-Q4_K_M.gguf"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        require(context.packageName.endsWith(".validation"))
        val model = ModelLibrary.read(context).single { it.filename == requested }
        val file = File(context.filesDir, "models/$requested")
        assertTrue("Stage verified model first", file.isFile)
        val checksum = java.security.MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input -> val buffer = ByteArray(1024 * 1024); while (true) {
                val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
            } }; digest.digest().joinToString("") { "%02x".format(it) }
        }
        assertEquals(model.sha256Checksum, checksum)
        val metadata = GgufParser().parseFromFile(file).getOrThrow()
        assertEquals(model.architecture, metadata.architecture)
        val engine = InferenceEngine(context)
        val samples = JSONArray()
        try {
            engine.setProcessingMode(ProcessingMode.CPU)
            engine.loadCustomGguf(file).getOrThrow()
            for (prompt in listOf("What is 17 multiplied by 6? Give only the number.", "Name the capital of France. Give only its name.")) {
                val text = engine.generateSync(prompt, GenerationParams(maxTokens = 128, contextSize = 2048, temperature = 0f, threads = 4))
                ModelOutputValidator.validate(text)
                samples.put(JSONObject().put("prompt", prompt).put("answer", text).put("telemetry", JSONObject(engine.telemetry.value.toJson())))
                assertTrue(text, if ("17" in prompt) text.contains("102") else text.contains("Paris", true))
                assertFalse("Visible output must not include a thought channel", text.contains("<think>"))
                assertEquals("CPU", engine.telemetry.value.activeBackend)
            }
        } finally {
            engine.unloadActiveModel()
            File(context.filesDir, "new-gguf-$requested.json").writeText(JSONObject().put("filename", requested)
                .put("sha256", checksum).put("kvBytesPerToken", ModelMemoryPlanner.kvBytesPerToken(metadata))
                .put("layers", metadata.blockCount).put("samples", samples).put("released", !engine.isReady()).toString(2))
        }
    }
}
