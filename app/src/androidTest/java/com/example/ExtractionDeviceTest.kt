package com.example

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.grounding.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExtractionDeviceTest {
    @Test fun fixtureComparisonAndLivePublisherExtraction(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val fixture = JSONArray(InstrumentationRegistry.getInstrumentation().context.assets.open("extraction-corpus.json").bufferedReader().use { it.readText() })
        val rows = JSONArray(); val report = JSONObject().put("scope", "Fixture extraction comparison plus same-response live publisher extraction. Not native LLM answer quality; process PSS sampled after each extraction, not exact transient allocation.").put("rows", rows)
        val output = File(context.filesDir, "extraction-device.json")
        fun compare(id: String, question: String, page: WebDocument, expected: String, live: Boolean) {
            for (trial in 1..2) for (old in if (trial == 1) listOf(true, false) else listOf(false, true)) {
                val start = SystemClock.elapsedRealtime()
                val source = if (old) Baseline28Extractor.article(page, 1, question) else WebPageExtractor.article(page, 1, question)
                val extractionMs = SystemClock.elapsedRealtime() - start
                val answer = source?.let { EvidenceAnswer.resolve("I cannot answer from memory.", listOf(it), question) }
                val supported = answer != null && answer.support != AnswerSupport.INSUFFICIENT
                val cited = answer?.let { GroundingPolicy.validateCitations(it.text, listOf(source!!)).second } == true
                rows.put(JSONObject().put("case", id).put("live", live).put("trial", trial).put("baseline", old)
                    .put("elapsedMs", extractionMs).put("supported", supported).put("citationValid", cited).put("sampledPssMb", Debug.getPss() / 1024)
                    .put("extracted", source != null).put("expectedFact", expected.isNotBlank() && source?.excerpt?.contains(expected) == true)
                    .put("characters", source?.excerpt?.length ?: 0))
                if (!old && expected.isNotEmpty()) assertTrue(id, source?.excerpt?.contains(expected) == true)
                if (!old && expected.isBlank() && !live) assertNull(id, source)
            }
        }
        try {
            for (i in 0 until fixture.length()) {
                val c = fixture.getJSONObject(i)
                compare(c.getString("id"), c.getString("question"), WebDocument("https://publisher.example/reference", c.getString("body"), c.getString("contentType")), c.getString("expected"), false)
            }
            val transport = SearchHttp(); val robots = ResearchRobots(transport)
            for ((url, question) in listOf(
                "https://developer.android.com/ndk/guides/graphics/getting-started" to "Android Vulkan initialization requirements",
                "https://en.wikipedia.org/wiki/Paris" to "What is the capital of France?",
                "https://www.bbc.com/weather/2643743" to "Weather in London today")) {
                val start = SystemClock.elapsedRealtime()
                try {
                    val page = transport.fetchPage(url) { robots.allowed(it) }
                    compare(url.substringAfterLast('/'), question, page, "", true)
                    rows.put(JSONObject().put("case", "publisher_fetch").put("live", true).put("success", true).put("elapsedMs", SystemClock.elapsedRealtime() - start))
                } catch (e: Exception) {
                    rows.put(JSONObject().put("case", "publisher_fetch").put("live", true).put("success", false).put("category", ResearchErrors.publisher(e).name))
                }
            }
        } finally { output.writeText(report.toString(2)) }
        assertTrue(rows.length() >= fixture.length() * 4)
    }
}
