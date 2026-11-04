package com.example

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.engine.*
import com.example.grounding.*
import com.example.ui.AiCoreViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt in with webReliability=true, in the separate validation installation.
 * Synthetic publisher facts are never represented as a real web benchmark. */
class WebReliabilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val repo get() = AiCoreRepository.getInstance(context)
    private val vm get() = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
    private val rows = JSONArray()
    private fun admit() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("webReliability") == "true")
        check(context.packageName.endsWith(".validation"))
    }
    private suspend fun fresh() {
        val before = vm.conversationId.value
        compose.runOnUiThread { vm.newConversation() }
        compose.waitUntil(15_000) { vm.conversationId.value != 0L && vm.conversationId.value != before }
    }
    private suspend fun ask(question: String): ChatMessageEntity {
        val count = repo.messages(vm.conversationId.value).first().size
        compose.onNodeWithTag("chat_input_field").performTextReplacement(question)
        compose.onNodeWithTag("send_message_btn").performClick()
        compose.waitUntil(180_000) { !vm.isChatGenerating.value && vm.chatMessages.value.size > count }
        return repo.messages(vm.conversationId.value).first().last { it.role == "assistant" }.also {
            rows.put(JSONObject().put("question", question).put("status", it.groundingStatus).put("state", it.messageState)
                .put("answer", it.content).put("sources", JSONArray(it.sourcesJson)).put("model", it.modelName))
        }
    }
    @Test fun realNativeModelRecoversGeneralAndMixedTasksKeepsLiveFactsUnverifiedAndCancelsCleanly(): Unit = runBlocking {
        admit()
        val filename = InstrumentationRegistry.getArguments().getString("reliabilityModel") ?: "smollm2-135m-instruct-q4_k_m.gguf"
        val model = withTimeout(90_000) { repo.allModels.first { models -> models.any { it.filename == filename && it.isInstalled } } }
            .single { it.filename == filename }
        repo.engine.setProcessingMode(ProcessingMode.CPU)
        repo.switchActiveModel(model)
        repo.preferencesRepository.saveWebAllowed(true)
        compose.waitUntil(20_000) { vm.webAllowed.value && vm.conversationId.value != 0L && !repo.isModelLoading }
        compose.runOnUiThread {
            vm.toggleForegroundService(false)
            vm.updateGenerationParams(GenerationParams(contextSize = 2048, maxTokens = 192, temperature = 0f, threads = 4))
            vm.useSearchProviderForTesting(object : SearchProvider {
                override suspend fun search(question: String): List<EvidenceSource> = throw WebRequestException(429, 120_000)
            })
        }
        try {
            fresh()
            val general = ask("How do birds fly?")
            assertEquals("LOCAL_UNVERIFIED", general.groundingStatus)
            assertTrue(general.content, general.content.contains("on-device knowledge"))
            assertTrue(general.content, general.content.length > 100)
            assertTrue(GroundingResult.sources(general.sourcesJson).isEmpty())
            fresh()
            val live = ask("What's the current Jubilee line status?")
            assertEquals("PROVIDER_RATE_LIMITED", live.groundingStatus)
            assertFalse(live.content, Regex("(?i)good service|no delays|running normally").containsMatchIn(live.content))
            fresh()
            val mixed = ask("Explain inflation and tell me the latest UK inflation rate.")
            assertEquals("LOCAL_WITH_WEB_GAP", mixed.groundingStatus)
            assertTrue(mixed.content, mixed.content.contains("couldn't check"))
            assertTrue(mixed.content, mixed.content.contains("inflation", true))
            fresh()
            val started = CompletableDeferred<Unit>()
            compose.runOnUiThread { vm.useSearchProviderForTesting(object : SearchProvider {
                override suspend fun search(question: String): List<EvidenceSource> { started.complete(Unit); awaitCancellation() }
            }) }
            compose.onNodeWithTag("chat_input_field").performTextReplacement("Search for Orion Android memory")
            compose.onNodeWithTag("send_message_btn").performClick()
            withTimeout(15_000) { started.await() }
            compose.runOnUiThread { vm.cancelGeneration() }
            compose.waitUntil(5000) { !vm.isChatGenerating.value }
            val stopped = repo.messages(vm.conversationId.value).first().last { it.role == "assistant" }
            assertEquals("STOPPED", stopped.messageState); assertEquals("USER_CANCELLED", stopped.groundingStatus)
            assertEquals("Response stopped.", stopped.content)
            rows.put(JSONObject().put("question", "Cancellation during discovery").put("state", stopped.messageState).put("status", stopped.groundingStatus))
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("setting_Web & grounding"))
            compose.onNodeWithTag("setting_Web & grounding").performClick()
            compose.onNodeWithTag("web_settings_list").performScrollToNode(hasTestTag("research_details"))
            compose.onNodeWithTag("research_details").performClick()
            compose.onNodeWithTag("web_settings_list").performScrollToNode(hasTestTag("view_research_diagnostics"))
            compose.onNodeWithTag("view_research_diagnostics").performClick()
            compose.waitUntil(5000) { vm.researchDiagnosticReport.value != null }
            compose.onNodeWithTag("research_diagnostics_report").assertExists()
            val export = vm.researchDiagnosticReport.value!!
            assertTrue(export.contains("PROVIDER_RATE_LIMITED"))
            for (secret in listOf("Orion", "inflation", "conversationId", "originalQuery", "excerpt")) assertFalse(secret, export.contains(secret))
        } finally {
            repo.engine.unloadActiveModel()
            File(context.filesDir, "web-reliability-native.json").writeText(rows.toString(2))
        }
    }
    @Test fun livePublicSourcesAndOnDeviceFallbackUseTheSameReaderAndCitationChecks(): Unit = runBlocking {
        admit()
        val questions = listOf("How does Android Vulkan graphics initialization work?", "Weather in London today", "Who is acting as Doom in Avengers Doomsday?")
        var successful = 0
        for (question in questions) {
            val start = android.os.SystemClock.elapsedRealtime()
            val outcome = PublicWebResearch().research(question) {}
            val answer = if (outcome.sources.isEmpty()) null else EvidenceAnswer.resolve("I cannot answer from memory.", outcome.sources, question)
            if (answer != null && answer.support != AnswerSupport.INSUFFICIENT) {
                assertTrue("$question: ${answer.text}", GroundingPolicy.validateCitations(answer.text, outcome.sources).second); successful++
            } else assertNotNull("A failed read needs a classified reason", outcome.failure)
            rows.put(JSONObject().put("question", question).put("scope", "Live phone retrieval plus deterministic synthesis; no model run")
                .put("failure", outcome.failure?.name.orEmpty()).put("readSources", outcome.sources.size)
                .put("supported", answer?.support?.name.orEmpty()).put("elapsedMs", android.os.SystemClock.elapsedRealtime() - start))
            assertTrue(android.os.SystemClock.elapsedRealtime() - start < 65_000)
        }
        // Force only Exa to fail; actual public source fetches remain live.
        val actual = SearchHttp()
        val fallback = PublicWebResearch(object : WebFetcher {
            override suspend fun fetch(url: String) = actual.fetch(url)
            override suspend fun fetchPage(url: String, permitted: suspend (String) -> Boolean) = actual.fetchPage(url, permitted)
            override suspend fun postJson(url: String, json: String, permitted: suspend (String) -> Boolean): WebDocument = throw WebRequestException(429, 120_000)
        }).research(questions.first()) {}
        assertTrue("BI fallback did not read its public Android source: ${fallback.failure}", fallback.sources.isNotEmpty())
        assertTrue(fallback.sources.any { it.discoveryProvider == BiDiscovery.providerName })
        rows.put(JSONObject().put("question", questions.first()).put("scope", "Simulated Exa rate limit; real publisher requests through BI fallback")
            .put("provider", fallback.metrics.provider).put("readSources", fallback.sources.size))
        File(context.filesDir, "web-reliability-live.json").writeText(rows.toString(2))
        assertTrue("None of the live phone questions had readable supported sources", successful > 0)
    }
}
