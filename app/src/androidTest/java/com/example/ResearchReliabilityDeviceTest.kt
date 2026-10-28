package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AiCoreRepository
import com.example.engine.GenerationParams
import com.example.engine.ProcessingMode
import com.example.grounding.*
import com.example.ui.AiCoreViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in real saved-chat evaluation using public websites and local Gemma.
 * Reference assertions are never included in prompts or production code. */
class ResearchReliabilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun savedAnswersUnderstandIntentAndPublicFollowUpContext(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("researchReliability") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val repository = AiCoreRepository.getInstance(context)
        val report = JSONArray()
        try {
            val model = withTimeout(120_000) { repository.allModels.first { models ->
                models.any { it.filename == "gemma-4-E2B-it.litertlm" && it.isInstalled }
            } }.single { it.filename == "gemma-4-E2B-it.litertlm" }
            repository.engine.setProcessingMode(ProcessingMode.CPU)
            repository.switchActiveModel(model)
            val vm = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
            repository.preferencesRepository.saveWebAllowed(true)
            compose.waitUntil(20_000) { vm.webAllowed.value && vm.conversationId.value != 0L && !repository.isModelLoading }
            compose.runOnUiThread {
                vm.toggleForegroundService(false)
                vm.updateGenerationParams(GenerationParams(contextSize = 2048, maxTokens = 384, temperature = 0f, threads = 4))
                vm.setGroundingMode(GroundingMode.AUTO)
            }
            suspend fun ask(question: String): com.example.data.ChatMessageEntity {
                val previous = repository.messages(vm.conversationId.value).first()
                val count = previous.size
                val draft = java.util.concurrent.atomic.AtomicReference("")
                val streaming = launch(Dispatchers.Default) { vm.streamingChatText.collect { if (it.isNotBlank()) draft.set(it) } }
                compose.runOnUiThread { vm.sendChatMessage(question) }
                compose.waitUntil(240_000) { !vm.isChatGenerating.value && vm.chatMessages.value.size > count }
                streaming.cancelAndJoin()
                val answer = repository.messages(vm.conversationId.value).first().last { it.role == "assistant" }
                report.put(JSONObject().put("question", question).put("answer", answer.content).put("status", answer.groundingStatus)
                    .put("resolvedQuestion", ConversationalResearch.question(question, previous)).put("draft", draft.get())
                    .put("state", answer.messageState).put("model", answer.modelName).put("sources", JSONArray(answer.sourcesJson))
                    .put("latencyMs", answer.latencyMs).put("completionTokens", answer.completionTokens))
                File(context.filesDir, "research-reliability-phone.json").writeText(report.toString(2))
                assertEquals(answer.content, "COMPLETE", answer.messageState)
                return answer
            }
            val weather = ask("What is the weather in London today?")
            assertTrue(weather.content, EvidenceAnswer.hasTemperature(weather.content))
            assertNotEquals("INSUFFICIENT_EVIDENCE", weather.groundingStatus)
            val mayor = ask("Who is the mayor there?")
            assertTrue(mayor.content, mayor.content.contains("Khan", true))
            assertFalse(mayor.content.contains("Susan Langley"))
            val temperature = ask("What is the temperature there?")
            assertTrue(temperature.content, EvidenceAnswer.hasTemperature(temperature.content))
            val domains = ask("Can example domains be registered or transferred? Read https://www.iana.org/help/example-domains")
            assertTrue(domains.content, Regex("(?i)not available|cannot|can't|not .*register|not .*transfer").containsMatchIn(domains.content))
            assertNotEquals("INSUFFICIENT_EVIDENCE", domains.groundingStatus)
            val calculation = ask("What is 15% of 240?")
            assertTrue(calculation.content, Regex("\\b36\\b").containsMatchIn(calculation.content))
            assertTrue(GroundingResult.sources(calculation.sourcesJson).isEmpty())
            val unknown = ask("What is the unannounced launch date of the fictional Orion device? Read https://www.iana.org/help/example-domains")
            assertEquals(unknown.content, "INSUFFICIENT_EVIDENCE", unknown.groundingStatus)
            assertEquals(GroundingPolicy.unavailableAnswer, unknown.content)
            val before = vm.conversationId.value
            compose.runOnUiThread { vm.newConversation() }
            compose.waitUntil(15_000) { vm.conversationId.value != before && vm.conversationId.value != 0L }
            compose.runOnUiThread { vm.setGroundingMode(GroundingMode.AUTO) }
            val missingPlace = ask("Who is the mayor there?")
            assertEquals(ConversationalResearch.askPlace, missingPlace.content)
            assertTrue(GroundingResult.sources(missingPlace.sourcesJson).isEmpty())
            assertEquals(0, missingPlace.completionTokens)
            val clarified = ask("London")
            assertTrue(clarified.content, clarified.content.contains("Khan", true))
        } finally {
            repository.engine.unloadActiveModel()
            File(context.filesDir, "research-reliability-phone.json").writeText(report.toString(2))
        }
    }
}
