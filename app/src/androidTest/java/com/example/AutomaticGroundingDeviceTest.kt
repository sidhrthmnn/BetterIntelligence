package com.example

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AiCoreRepository
import com.example.engine.GenerationParams
import com.example.engine.ProcessingMode
import com.example.grounding.*
import com.example.ui.AiCoreViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Public/synthetic questions only. References stay out of prompts and production code. */
class AutomaticGroundingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun automaticFactLookupAndHonestAnswers(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("automaticResearch") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val repository = AiCoreRepository.getInstance(context)
        val report = JSONArray(); val failures = mutableListOf<String>()
        fun verify(value: Boolean, message: String) { if (!value) failures += message }
        try {
            val model = withTimeout(120_000) { repository.allModels.first { rows -> rows.any { it.filename == "gemma-4-E2B-it.litertlm" && it.isInstalled } } }
                .single { it.filename == "gemma-4-E2B-it.litertlm" }
            repository.engine.setProcessingMode(ProcessingMode.CPU)
            repository.switchActiveModel(model)
            repository.preferencesRepository.saveWebAllowed(true)
            val vm = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
            compose.waitUntil(20_000) { vm.webAllowed.value && vm.conversationId.value != 0L && !repository.isModelLoading }
            compose.runOnUiThread {
                vm.toggleForegroundService(false)
                vm.updateGenerationParams(GenerationParams(contextSize = 2048, maxTokens = 192, temperature = 0f, threads = 4))
            }
            // An existing Off conversation must now research automatically.
            val legacy = repository.createConversation()
            repository.saveGroundingMode(legacy, "OFF")
            compose.runOnUiThread { vm.openConversation(legacy) }
            compose.waitUntil(20_000) { vm.conversationId.value == legacy }
            compose.waitForIdle()
            assertEquals(GroundingMode.AUTO, vm.groundingMode.value)
            compose.onNodeWithTag("chat_grounding").assertTextContains("Research: Automatic")

            suspend fun ask(question: String, expected: Regex? = null, research: Boolean? = true): com.example.data.ChatMessageEntity {
                val count = repository.messages(vm.conversationId.value).first().size
                compose.waitForIdle()
                compose.onNodeWithTag("chat_input_field").performTextReplacement(question)
                compose.onNodeWithTag("send_message_btn").performClick()
                compose.waitUntil(240_000) { !vm.isChatGenerating.value && vm.chatMessages.value.size > count }
                val answer = repository.messages(vm.conversationId.value).first().last { it.role == "assistant" }
                val sources = GroundingResult.sources(answer.sourcesJson)
                report.put(JSONObject().put("question", question).put("answer", answer.content).put("status", answer.groundingStatus)
                    .put("state", answer.messageState).put("latencyMs", answer.latencyMs).put("sources", JSONArray(answer.sourcesJson)))
                File(context.filesDir, "automatic-grounding-evaluation.json").writeText(report.toString(2))
                verify(answer.messageState == "COMPLETE", "Incomplete: $question")
                verify(expected == null || expected.containsMatchIn(answer.content), "$question: ${answer.content}")
                if (research == true) verify(sources.isNotEmpty() && answer.groundingStatus !in setOf("LOCAL", "INSUFFICIENT_EVIDENCE"), "Research missed: $question (${answer.groundingStatus})")
                else if (research == false) verify(sources.isEmpty(), "Unexpected web use: $question")
                return answer
            }
            val casting = ask("Who is playing doom in avengers doomsday", Regex("(?i)Robert Downey"))
            verify(!casting.content.contains("hiding in plain sight", true), "Theory headline passed as casting")
            ask("Who plays Doctor Doom in Avengers: Doomsday?", Regex("(?i)Robert Downey"))
            ask("Who wrote Pride and Prejudice?", Regex("(?i)Jane Austen"))
            ask("Who directed Inception?", Regex("(?i)Christopher Nolan"))
            ask("What is photosynthesis?", Regex("(?i)light|sunlight|solar"))
            ask("Can example domains be registered or transferred? Read https://www.iana.org/help/example-domains", Regex("(?i)not available|cannot|can't|not .*register|not .*transfer"))
            val weather = ask("What is the weather in London today?")
            verify(EvidenceAnswer.hasTemperature(weather.content), "Missing weather temperature")
            ask("What is 15% of 240?", Regex("\\b36\\b"), false)
            ask("Remember our test project is Cobalt Lantern", research = false)
            ask("What was our test project?", Regex("(?i)Cobalt Lantern"), false)
            val old = vm.conversationId.value
            compose.runOnUiThread { vm.newConversation() }
            compose.waitUntil(20_000) { vm.conversationId.value != old && vm.conversationId.value != 0L }
            assertEquals(GroundingMode.AUTO, vm.groundingMode.value)
            ask("What is the capital of France?", Regex("(?i)Paris"))
            val unknown = ask("What is the unannounced launch date of fictional Zephrin? Read https://www.iana.org/help/example-domains", research = null)
            // The requested page is retrieved but must not substantiate an unrelated fact.
            verify(unknown.groundingStatus == "INSUFFICIENT_EVIDENCE", "Unrelated evidence gave an answer")
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            repository.engine.unloadActiveModel()
            File(context.filesDir, "automatic-grounding-evaluation.json").writeText(report.toString(2))
        }
    }
}
