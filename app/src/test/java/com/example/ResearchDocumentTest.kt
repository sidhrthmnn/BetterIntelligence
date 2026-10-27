package com.example

import com.example.grounding.*
import com.example.engine.ChatAnswerPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchDocumentTest {
    @Test fun tablesInsideListsPreserveHeaderUnitsAndInheritedDeviceCells() {
        val html = """<title>Orion mobile benchmark</title><main><h1>Orion</h1><ul><li>Measured benchmark conditions<table>
            <thead><tr><th>Device</th><th>Backend</th><th>Prefill (tk/s)</th><th>Peak CPU Memory (MB)</th></tr></thead>
            <tr><td rowspan="2">Android S26 Ultra</td><td>CPU</td><td>557</td><td>1733</td></tr>
            <tr><td>GPU</td><td>3808</td><td>676</td></tr></table></li></ul></main>"""
        val source = WebPageExtractor.article(WebDocument("https://docs.example/orion", html, "text/html"), 1, "Orion Android CPU GPU memory benchmark")!!
        assertTrue(source.excerpt, source.excerpt.contains("Prefill (tk/s): 557 tk/s"))
        assertTrue(source.excerpt.contains("Peak CPU Memory (MB): 1733 MB"))
        assertTrue(source.excerpt.contains("Device: Android S26 Ultra | Backend: GPU"))
        assertFalse(source.excerpt.contains("Device: GPU"))
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Orion uses 557 MB memory [1].", listOf(source), "Orion memory?").support)
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Orion uses 676 MB memory on CPU [1].", listOf(source), "Orion memory?").support)
        val table = org.jsoup.Jsoup.parse("<table><tr><td>Model</td><td>RAM (GB)</td></tr><tr><td>Orion</td><td>4</td></tr></table>").selectFirst("table")!!
        assertTrue(HtmlEvidenceTable.rows(table).single().contains("RAM (GB): 4 GB"))
    }

    @Test fun datedSignaturesAreDocumentDatesAndCannotBecomePublicationClaims() {
        val html = """<title>Orion Annual Report</title><main><h1>Orion Annual Report</h1>
            <p>The annual report describes the organisation and its leadership, financial statements and strategy for the year.</p>
            <p><strong>Ada Example</strong><br>Chairman and Chief Executive Officer<br>October 14, 2026</p></main>"""
        val source = WebPageExtractor.article(WebDocument("https://company.example/report", html, "text/html"), 1, "Who is Orion CEO?")!!
        assertEquals("2026-10-14", source.updatedAt); assertEquals("DOCUMENT_REPORTED", source.dateKind)
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("The annual report was published October 14, 2026 [1].", listOf(source)).support)
        val bio = html.replace("Chief Executive Officer<br>October", "Chief Executive Officer born<br>October")
        assertEquals("", WebPageExtractor.article(WebDocument("https://company.example/bio", bio, "text/html"), 1, "Who is Orion CEO?")!!.updatedAt)
    }

    @Test fun olderPrimaryDocumentationIsBackgroundWithVisibleAgeCautionAndJsonFencesAreRemoved() {
        val old = LocalDate.now().minusDays(70).toString()
        val source = EvidenceSource(1, "LiteRT-LM Android reference", "https://developers.google.com/edge/litert-lm/android",
            "LiteRT-LM supports GPU acceleration on Android.", 1, old, "WEB_PAGE", "MODIFIED")
        val question = "What recent Android GPU acceleration support is documented?"
        assertTrue(EvidenceRanking.acceptable(source, ResearchPlanner.plan(question)))
        assertFalse(EvidenceRanking.acceptable(source, ResearchPlanner.plan("What Android features were released today?")))
        val checked = GroundingPolicy.checkAnswer("LiteRT-LM supports GPU acceleration on Android [1].", listOf(source), question)
        assertEquals(AnswerSupport.PARTIAL, checked.support); assertTrue(checked.text.contains("older or undated"))
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("LiteRT-LM recently added GPU acceleration on Android [1].", listOf(source), question).support)
        val json = ChatAnswerPolicy.finishLocal("Extract JSON only", "```json\n{\"id\":\"001\",\"enabled\":false}\n```")
        assertEquals("001", org.json.JSONObject(json).getString("id")); assertFalse(json.contains("```"))
        assertEquals("```json\nnot json\n```", ChatAnswerPolicy.finishLocal("JSON only", "```json\nnot json\n```"))
    }
}
