package com.example

import com.example.data.ChatMessageEntity
import com.example.engine.ChatAnswerPolicy
import com.example.engine.ChatTurn
import com.example.engine.LocalCalculation
import com.example.grounding.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerQualityTest {
    private fun source(id: Int = 1, text: String = "Orion requires 4 GB RAM. Orion does not support Vulkan.") =
        EvidenceSource(id, "Orion runtime", "https://docs.example/orion$id", text, 1)

    @Test fun automaticRoutingDistinguishesChangingFactsFromLocalTasks() {
        val cases = linkedMapOf(
            "What is the population of Tokyo?" to true, "Who is the CEO of Microsoft?" to true,
            "What is the price of the Pixel 10?" to true, "Which Android version is currently supported?" to true,
            "Who won the 2026 Nobel Prize in Physics?" to true, "Is Gemma 4 better than Qwen 3.5?" to true,
            "Compare the latest LiteRT and llama.cpp features." to true, "Current UK Prime Minister" to true,
            "Today's London weather" to true, "Current phone price" to true,
            "Compare recent Android AI frameworks" to true, "Check again" to true,
            "കേരളത്തിന്റെ ഇപ്പോഴത്തെ മുഖ്യമന്ത്രി ആരാണ്?" to true, "Verify the 1969 Moon landing" to true,
            "Read https://docs.example/orion" to true, "Does LiteRT-LM support GPU?" to true,
            "Explain recursion" to true, "Write a birthday message" to false, "What is 15% of 240?" to false,
            "Summarise supplied text: Today's price was wrong." to false, "Who was UK prime minister in 1945?" to true,
            "Difference between RAM and storage" to true, "Explain electric current flow" to true,
            "Write a poem about today" to false, "Translate this weather sentence into Malayalam" to false,
            "Explain Android's activity lifecycle" to true, "Who won the 1901 Nobel Prize in Physics?" to true,
            "Create JSON with name and age" to false)
        val rows = JSONArray(); var oldPass = 0
        // Frozen latest-main AUTO rule, used only for this before/after report.
        fun baseline(question: String): Boolean {
            val text = question.lowercase()
            if (Regex("(?i)^(write|draft|compose|imagine|brainstorm|translate|rewrite|polish)\\b").containsMatchIn(question.trim())) return false
            if ("(public source topic:" in text || ConversationalResearch.isRecheck(question)) return true
            if (Regex("(?i)\\b(compare|versus|vs|better than|difference between)\\b|താരതമ്യം|തമ്മിൽ").containsMatchIn(question)) return true
            return listOf("latest", "today", "yesterday", "this week", "this month", "current", "recent", "weather", "forecast", "verify", "fact check", "check again", "search again", "are you sure", "source", "who is", "who are", "who won", "when did", "is it true", "chief minister", "prime minister", "president of", "ഇന്ന്", "ഇപ്പോൾ", "സത്യമാണോ").any { it in text } || Regex("\\b(19|20)\\d{2}\\b|https?://").containsMatchIn(text)
        }
        cases.forEach { (question, expected) ->
            val actual = ResearchRouting.decide(question, LocalDate.of(2026, 10, 26))
            assertEquals(question, expected, actual.search)
            assertFalse(GroundingPolicy.shouldSearch(false, GroundingMode.ON, question))
            assertFalse(GroundingPolicy.shouldSearch(true, GroundingMode.OFF, question))
            val old = baseline(question); if (old == expected) oldPass++
            rows.put(JSONObject().put("question", question).put("expectedSearch", expected).put("legacySearch", old).put("actualSearch", actual.search).put("reason", actual.reason))
        }
        assertFalse(GroundingPolicy.shouldSearch(true, GroundingMode.ON, "Write a birthday message offline without search"))
        assertFalse(GroundingPolicy.shouldSearch(true, GroundingMode.AUTO, "Summarise supplied text: The source says today's CEO sold shares for 2000."))
        assertFalse(GroundingPolicy.shouldSearch(true, GroundingMode.AUTO, "Translate the source text: the current weather is sunny"))
        assertFalse(GroundingPolicy.shouldSearch(true, GroundingMode.ON, "Work offline without search. Extract this as JSON: identifier 001."))
        assertFalse(GroundingPolicy.shouldSearch(true, GroundingMode.ON, "Do not search. Who is the current prime minister?"))
        assertFalse(GroundingPolicy.shouldSearch(true, GroundingMode.ON, "What is 15% of 240? Return the result briefly."))
        val output = File(System.getProperty("bi.routing.report", "build/reports/research-routing.json"))
        output.parentFile.mkdirs(); output.writeText(JSONObject().put("baselineRef", "b90955b").put("legacyPass", oldPass).put("newPass", cases.size).put("cases", rows).toString(2))
    }

    @Test fun checksPolarityUnitsSubjectsRelationshipsAndInventedLinksPerClaim() {
        val a = source()
        assertEquals(AnswerSupport.CITED, GroundingPolicy.checkAnswer("Orion requires 4 GB RAM [1].", listOf(a), "Orion RAM?").support)
        assertEquals(AnswerSupport.CITED, GroundingPolicy.checkAnswer("Orion requires 4 GB RAM [ID:1].", listOf(a), "Orion RAM?").support)
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Orion requires 4 GB RAM [ID:99].", listOf(a), "Orion RAM?").support)
        assertEquals(AnswerSupport.CITED, GroundingPolicy.checkAnswer("Orion does not support Vulkan [1].", listOf(a)).support)
        val bad = listOf("Orion supports Vulkan [1].", "Orion requires 4 MB RAM [1].", "Orion requires 6 GB RAM [1].",
            "Orion requires 4 GB RAM [99].", "Orion requires 4 GB RAM https://fabricated.example/proof [1].",
            "Vega requires 4 GB RAM [1].")
        bad.forEach { assertEquals(it, AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer(it, listOf(a), "Compare Orion vs Vega RAM").support) }
        val b = source(2, "Vega requires 6 GB RAM.")
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Orion requires 6 GB RAM [1, 2].", listOf(a, b), "Compare Orion vs Vega RAM").support)
        val relation = source(text = "Orion is faster than Vega on the tested device.")
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Vega is faster than Orion [1].", listOf(relation), "Compare Vega vs Orion").support)
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Orion is faster than Vega [1].", listOf(a), "Compare Orion vs Vega").support)
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Orion requires 4 GB RAM on Mars [1].", listOf(a), "Orion RAM?").support)
        val scoped = source(text = "Orion does not support OpenCL but supports Vulkan.")
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Orion does not support Vulkan [1].", listOf(scoped), "Orion GPU?").support)
        assertEquals(AnswerSupport.CITED, GroundingPolicy.checkAnswer("Orion supports Vulkan [1].", listOf(scoped), "Orion GPU?").support)
    }

    @Test fun preservesSupportedSentencesAndLabelsOmissionsWithoutCallingThemVerified() {
        val answer = "Orion requires 4 GB RAM [1].\n\nOrion supports Vulkan [1]."
        val checked = GroundingPolicy.checkAnswer(answer, listOf(source()), "Orion RAM and Vulkan?")
        assertEquals(AnswerSupport.PARTIAL, checked.support)
        assertTrue(checked.text.contains("4 GB")); assertFalse(checked.text.contains("supports Vulkan"))
        assertTrue(checked.text.contains("omitted")); assertFalse(checked.text.contains("verified"))
        assertFalse(GroundingPolicy.checkAnswer("[1]", listOf(source())).support == AnswerSupport.CITED)
        assertEquals(AnswerSupport.CITED, GroundingPolicy.checkAnswer("Orion requires 4 GB RAM. [1]", listOf(source())).support)
    }

    @Test fun comparisonHeadingsPreserveSubjectAndOrphanInstructionsCannotSurviveOmission() {
        val sources = listOf(source(), source(2, "Vega requires 6 GB RAM."))
        val checked = GroundingPolicy.checkAnswer("**Orion:**\nRequires 4 GB RAM [1].\n\n**Vega:**\nRequires 6 GB RAM [2].", sources, "Compare Orion vs Vega")
        assertEquals(AnswerSupport.CITED, checked.support)
        assertTrue(checked.text.contains("Orion — Requires 4 GB")); assertTrue(checked.text.contains("Vega — Requires 6 GB"))
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("**Unknown model:**\nRequires 4 GB RAM [1].", sources, "Compare Orion vs Vega").support)
        val steps = source(text = "Orion requires the following native libraries inside the application tag.")
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Orion requires the following native libraries inside the application tag [1].", listOf(steps)).support)
        val gpu = source(text = "Backend: GPU | Peak CPU Memory: 676 MB.")
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("**CPU:**\nUses 676 MB Peak CPU Memory [1].", listOf(gpu), "Compare CPU vs GPU").support)
    }

    @Test fun numericalValuesAreNotEnoughAndDatesCannotComeFromMetadata() {
        val s = source(text = "Orion requires 4 GB RAM. Vega reaches 40 tokens/s.").copy(updatedAt = "2026-10-26")
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Orion reaches 40 tokens/s [1].", listOf(s), "Orion performance?").support)
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Orion was published 2026-10-26 [1].", listOf(s)).support)
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Orion requires 4 GB RAM and never overheats [1].", listOf(s)).support)
    }

    @Test fun evidenceCompressionKeepsCompleteQualificationsAndProvenance() {
        val phrase = "Orion supports GPU only with a separately installed vendor SDK and matching compiled model."
        assertEquals("", EvidencePacket.completeExcerpt(phrase, 25))
        assertEquals(phrase, EvidencePacket.completeExcerpt(phrase, 200))
        assertEquals(AnswerSupport.INSUFFICIENT, GroundingPolicy.checkAnswer("Orion supports GPU [1].", listOf(source(text = phrase)), "Orion GPU?").support)
        val packet = EvidencePacket.from(source(text = "Orion memory requires 4 GB RAM with 1024 context."), ResearchPlanner.plan("What memory does Orion require?"))
        assertEquals(1, packet.source.id); assertTrue(packet.numericalText.single().contains("1024"))
        assertEquals(ResearchQuery.words("Gemma 4 E2B Qwen 3.5"), ResearchQuery.words("Gemma4-E2B Qwen3.5"))
    }

    @Test fun localInstructionsAreCompactLanguageAwareAndDoNotInventLiveAccess() {
        val local = ChatAnswerPolicy.system("Current London weather?", false, 2048)
        assertTrue(local.contains("has not been checked"))
        assertTrue(ChatAnswerPolicy.system("JSON only with identifier 001", false, 2048).contains("valid JSON only"))
        assertTrue(ChatAnswerPolicy.system("Manglish please", false, 2048).contains("Latin letters"))
        assertTrue(ChatAnswerPolicy.system("മലയാളത്തിൽ വിശദീകരിക്കുക", false, 2048).contains("Reply in Malayalam"))
        assertTrue(ChatAnswerPolicy.history(listOf(ChatTurn("assistant", "old evidence repeated")), true).isEmpty())
        assertEquals(1, ChatAnswerPolicy.history(listOf(ChatTurn("user", "My project")), false).size)
        assertEquals(ChatAnswerPolicy.liveUnavailable, ChatAnswerPolicy.finishLocal("Who is the current UK Prime Minister?", "A stale person is the current Prime Minister."))
        assertEquals(ChatAnswerPolicy.liveUnavailable, ChatAnswerPolicy.finishLocal("Today's London weather", "It is sunny and 24 degrees."))
        assertEquals("Helpful recursion explanation", ChatAnswerPolicy.finishLocal("Explain recursion", "Helpful recursion explanation"))
        assertEquals("Historical answer", ChatAnswerPolicy.finishLocal("Who was prime minister in 1945?", "Historical answer"))
    }

    @Test fun localDecimalArithmeticRespectsPrecedenceNegationAndLimits() {
        assertEquals("36", LocalCalculation.evaluate("What is 15% of 240? Return only the number."))
        assertEquals("14", LocalCalculation.evaluate("Calculate 2 + 3 * 4"))
        assertEquals("-10", LocalCalculation.evaluate("Compute -(2 + 3) * 2"))
        assertEquals("0.3", LocalCalculation.evaluate("0.1 + 0.2"))
        for (text in listOf("2026-10-26", "id 001", "1/0", "1+System.exit(0)", "(".repeat(20) + "1+1" + ")".repeat(20)))
            assertNull(text, LocalCalculation.evaluate(text))
    }

    @Test fun followUpsUseOnlyPublicSubjectsAndRespectSessionBoundaries() {
        val sources = listOf(source().copy(title = "Orion documentation"), source(2).copy(title = "Vega documentation"))
        val history = listOf(ChatMessageEntity(role = "user", content = "My confidential salary is 90000", conversationId = 1),
            ChatMessageEntity(role = "assistant", content = "secret", conversationId = 1, sourcesJson = GroundingResult(sources = sources).json()))
        val question = ConversationalResearch.question("What about memory usage?", history)
        assertTrue(question.contains("Orion")); assertTrue(question.contains("Vega")); assertFalse(question.contains("90000"))
        assertEquals("What about memory usage?", ConversationalResearch.question("What about memory usage?", history + ChatMessageEntity(role = "user", content = "New chat", conversationId = 2)))
    }

    @Test fun correctionIsBoundedOptionalAndNeverUsedOnSlowHotOrSimpleCases() {
        val prepared = PreparedEvidence("evidence", listOf(source()))
        val insufficient = CheckedAnswer("", AnswerSupport.INSUFFICIENT, emptyList())
        fun repair(first: Long = 100, speed: Float = 40f, thermal: Int = 0, memory: Long = 2000, question: String = "Compare Orion vs Vega Android") =
            EvidenceRepair.prompt(question, "bad draft", prepared, insufficient, 2048, memory, false, thermal, first, speed) { true }
        assertNotNull(repair()); assertNull(repair(first = 15000)); assertNull(repair(thermal = 3)); assertNull(repair(memory = 200)); assertNull(repair(question = "Orion RAM?"))
    }

    @Test fun clearingCacheDuringAnActiveReadDoesNotRepopulateIt() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>(); var calls = 0
        val provider = object : SearchProvider { override suspend fun search(question: String): List<EvidenceSource> {
            calls++; if (calls == 1) { started.complete(Unit); finish.await() }; return listOf(source())
        } }
        val coordinator = GroundingCoordinator(mapOf(SearchProviderId.WEB to provider))
        val request = async { coordinator.retrieve("Orion?", true, GroundingMode.ON, SearchProviderId.WEB) }
        started.await(); coordinator.clearTemporaryData(); finish.complete(Unit); request.await()
        coordinator.retrieve("Orion?", true, GroundingMode.ON, SearchProviderId.WEB)
        assertEquals(2, calls)
    }
}
