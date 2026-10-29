package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AiCoreRepository
import com.example.engine.*
import com.example.grounding.*
import com.example.ui.AiCoreViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real chat composer with deterministic outages; no production app/data changes. */
class BalancedLocalDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun localChatSurvivesResearchFailuresAndKeepsSessionContext(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("balancedResearch") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val repository = AiCoreRepository.getInstance(context)
        val report = JSONArray(); val queries = mutableListOf<String>()
        try {
            val models = withTimeout(180_000) { repository.allModels.first { rows -> rows.any { it.filename == "gemma-4-E2B-it.litertlm" && it.isInstalled } } }
            val profile = HardwareAnalyzer.analyze(context)
            val gemma = models.single { it.filename == "gemma-4-E2B-it.litertlm" }
            val selected = if (ModelLibrary.fits(gemma, profile, 2048)) gemma else models.first { it.filename.startsWith("qwen3_0.6b") && it.isInstalled }
            repository.engine.setProcessingMode(ProcessingMode.CPU)
            repository.switchActiveModel(selected)
            repository.preferencesRepository.saveWebAllowed(true)
            val vm = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
            compose.waitUntil(20_000) { vm.webAllowed.value && vm.conversationId.value != 0L && !repository.isModelLoading }
            compose.runOnUiThread {
                vm.toggleForegroundService(false)
                vm.updateGenerationParams(GenerationParams(contextSize = 2048, maxTokens = 128, temperature = 0f, threads = 4))
                vm.useSearchProviderForTesting(object : SearchProvider {
                    override suspend fun search(question: String) = emptyList<EvidenceSource>()
                    override suspend fun research(question: String, progress: (ResearchStage) -> Unit): ResearchOutcome {
                        queries += question; return ResearchOutcome(emptyList(), ResearchFailure.TIMEOUT)
                    }
                })
            }
            suspend fun ask(q: String, expectedStatus: String, expected: Regex? = null) {
                val count = vm.chatMessages.value.size
                compose.onNodeWithTag("chat_input_field").performTextReplacement(q)
                compose.waitForIdle()
                compose.waitUntil(10_000) { compose.onNodeWithTag("send_message_btn").fetchSemanticsNode().boundsInRoot.width >= 40 }
                compose.onNodeWithTag("send_message_btn").performClick()
                compose.waitUntil(15_000) { vm.isChatGenerating.value || vm.chatMessages.value.size > count }
                compose.waitUntil(180_000) { !vm.isChatGenerating.value && vm.chatMessages.value.size > count }
                val a = repository.messages(vm.conversationId.value).first().last { it.role == "assistant" }
                report.put(JSONObject().put("question", q).put("answer", a.content).put("status", a.groundingStatus)
                    .put("state", a.messageState).put("model", a.modelName).put("latencyMs", a.latencyMs).put("providerCalls", queries.size))
                File(context.filesDir, "balanced-chat-device.json").writeText(report.toString(2))
                assertEquals(a.content, "COMPLETE", a.messageState)
                assertEquals(a.content, expectedStatus, a.groundingStatus)
                if (expected != null) assertTrue(a.content, expected.containsMatchIn(a.content))
            }
            ask("Explain recursion in one short sentence.", "LOCAL", Regex("(?i)itself|it.self|recursive"))
            ask("What is the capital of France? Give only the city.", "LOCAL", Regex("(?i)Paris"))
            assertEquals(0, queries.size)
            ask("What's happening in france now", "WEB_UNAVAILABLE", Regex("couldn't check"))
            ask("What's happening in France?", "WEB_UNAVAILABLE", Regex("couldn't check"))
            assertEquals(2, queries.size)
            ask("What is the current temperature in London?", "WEB_UNAVAILABLE", Regex("couldn't check"))
            ask("Verify this claim using official sources: Orion supports Vulkan.", "WEB_UNAVAILABLE", Regex("couldn't verify"))
            ask("How does Gemma 4 work, and what's its latest supported Android runtime?", "LOCAL_WITH_WEB_GAP", Regex("couldn't check the current"))
            ask("Search for recursion, then explain it in one sentence.", "LOCAL_WEB_UNAVAILABLE", Regex("(?i)itself|recursive"))
            ask("Remember our test project is Cobalt Lantern. Acknowledge briefly.", "LOCAL")
            ask("What was our test project?", "LOCAL", Regex("(?i)Cobalt Lantern"))
            assertTrue(queries.none { it.contains("Cobalt Lantern") })
            compose.runOnUiThread { vm.setWebAllowed(false) }
            compose.waitUntil(10_000) { !vm.webAllowed.value }
            val calls = queries.size
            ask("Write a one-line birthday greeting.", "LOCAL")
            ask("What's the weather in London today?", "WEB_DISABLED", Regex("Web research is off"))
            assertEquals(calls, queries.size)
            compose.runOnUiThread {
                vm.setWebAllowed(true)
                vm.useSearchProviderForTesting(object : SearchProvider {
                    override suspend fun search(question: String) = listOf(EvidenceSource(1, "Orion GPU inference", "https://docs.example/orion",
                        "Orion GPU inference uses parallel processors. Orion keeps weights in shared memory. Orion supports one active model. Orion reuses the loaded engine between requests. Orion uses CPU fallback if initialization fails. Orion checks thermal pressure and available RAM before generation.", System.currentTimeMillis()))
                })
            }
            compose.waitUntil(10_000) { vm.webAllowed.value }
            var firstPartial = ""
            val cancelOnText = launch(Dispatchers.Unconfined) {
                firstPartial = vm.streamingChatText.first { it.isNotBlank() }
                vm.cancelGeneration()
            }
            try {
                compose.onNodeWithTag("chat_input_field").performTextReplacement("Using sources, describe every Orion GPU inference fact in four paragraphs.")
                compose.onNodeWithTag("send_message_btn").performClick()
                withTimeout(60_000) { cancelOnText.join() }
                compose.waitUntil(30_000) { !vm.isChatGenerating.value }
                val interrupted = repository.messages(vm.conversationId.value).first().last { it.role == "assistant" }
                assertEquals("STOPPED", interrupted.messageState)
                assertEquals("UNVERIFIED_PARTIAL", interrupted.groundingStatus)
                assertTrue(interrupted.content, interrupted.content.startsWith(firstPartial))
                report.put(JSONObject().put("question", "Cancel researched response on first visible text")
                    .put("answer", interrupted.content).put("status", interrupted.groundingStatus).put("state", interrupted.messageState))
            } finally { cancelOnText.cancel() }
            ask("What is 15% of 240?", "LOCAL", Regex("\\b36\\b"))
        } finally {
            repository.engine.unloadActiveModel()
            File(context.filesDir, "balanced-chat-device.json").writeText(report.toString(2))
        }
    }
}
