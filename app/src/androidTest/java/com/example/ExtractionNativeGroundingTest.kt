package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import com.example.grounding.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Controlled publisher facts through the actual tokenizer, native model,
 * evidence packet and verifier. Never presented as real-world facts. */
class ExtractionNativeGroundingTest {
    @Test fun actualLocalModelReceivesCompleteBoundedEvidence(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val core = InferenceEngine(context)
        val filename = "Qwen3.5-2B-Q4_K_M.gguf"
        val model = ModelLibrary.read(context).single { it.filename == filename }
        val rows = JSONArray()
        try {
            core.setProcessingMode(ProcessingMode.CPU)
            core.loadCustomGguf(File(context.filesDir, "models/$filename"), model.sha256Checksum.orEmpty()).getOrThrow()
            for (question in listOf("What RAM does Orion require?", "Does Orion support image input?")) {
                val page = WebDocument("https://publisher.example/orion", "<title>Orion requirements</title><main><h1>Orion requirements</h1>" +
                    (1..400).joinToString("") { "<p>Unrelated archive gardening entry $it.</p>" } +
                    "<p>Orion requires 4 GB of shared RAM only with the vendor SDK. Orion does not support image input.</p></main>", "text/html")
                val source = WebPageExtractor.article(page, 1, question)!!
                val prepared = GroundingPolicy.prepareEvidence(question, listOf(source), 6000, 1200, core::contextTokenCount)
                assertTrue(core.contextTokenCount(prepared.prompt) <= 1200)
                val raw = core.generateSync(prepared.prompt, GenerationParams(contextSize = 2048, maxTokens = 128, temperature = 0f, threads = 4))
                val checked = EvidenceAnswer.resolve(raw, prepared.sources, question)
                val citationValid = GroundingPolicy.validateCitations(checked.text, prepared.sources).second
                rows.put(JSONObject().put("question", question).put("rawAnswer", raw).put("finalAnswer", checked.text)
                    .put("support", checked.support.name).put("citationValid", citationValid).put("promptTokens", core.contextTokenCount(prepared.prompt))
                    .put("nativeFirstTokenMs", core.telemetry.value.timeToFirstTokenMs).put("nativeTokensPerSecond", core.telemetry.value.tokensPerSecond))
                assertNotEquals(checked.text, AnswerSupport.INSUFFICIENT, checked.support)
                assertTrue(checked.text, citationValid)
                if (question.contains("RAM")) assertTrue(checked.text, checked.text.contains("4 GB"))
                else assertTrue(checked.text, Regex("(?i)not|no|doesn't").containsMatchIn(checked.text))
            }
        } finally {
            core.unloadActiveModel()
            File(context.filesDir, "extraction-native-grounding.json").writeText(rows.toString(2))
        }
    }

    @Test fun comparisonClausesKeepSeparateCitationAndQuantityBindings() {
        val sources = listOf(
            EvidenceSource(1, "Orion memory", "https://orion.example/specs", "Orion requires 4 GB RAM.", 1),
            EvidenceSource(2, "Nova memory", "https://nova.example/specs", "Nova requires 6 GB RAM.", 1))
        val question = "Compare Orion and Nova memory requirements"
        val good = ClaimVerification.check("Orion requires 4 GB RAM [1], while Nova requires 6 GB RAM [2].", sources, question)
        assertEquals(good.text, AnswerSupport.CITED, good.support)
        val wrong = ClaimVerification.check("Orion requires 6 GB RAM [1], while Nova requires 4 GB RAM [2].", sources, question)
        assertEquals(AnswerSupport.INSUFFICIENT, wrong.support)
    }

    @Test fun structuredFieldsKeepTrailingConditions() {
        val condition = "Orion requires " + "shared ".repeat(190) + "RAM only with the vendor SDK."
        val body = JSONObject().put("model", "Orion").put("requirements", condition).toString()
        val normalized = DocumentEvidence.normalize(WebDocument("https://publisher.example/a", body, "application/json"))!!
        assertTrue(normalized.text, normalized.text.contains(condition))
    }
}
