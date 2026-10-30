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

/** Opt-in isolated saved-chat tests. Never accesses production chats/models. */
class ResearchFollowThroughDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun phoneFailuresResearchAndKeepUsefulPartialReports(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("researchFollowThrough") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val repository = AiCoreRepository.getInstance(context)
        val rows = JSONArray()
        val failures = mutableListOf<String>()
        fun verify(ok: Boolean, message: String) { if (!ok) failures += message }
        try {
            val model = withTimeout(120_000) { repository.allModels.first { list ->
                list.any { it.filename == "gemma-4-E2B-it.litertlm" && it.isInstalled }
            } }.single { it.filename == "gemma-4-E2B-it.litertlm" }
            repository.engine.setProcessingMode(ProcessingMode.CPU)
            repository.switchActiveModel(model)
            val vm = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
            repository.preferencesRepository.saveWebAllowed(true)
            compose.waitUntil(20_000) { vm.webAllowed.value && vm.conversationId.value != 0L && !repository.isModelLoading }
            compose.runOnUiThread {
                vm.toggleForegroundService(false)
                vm.updateGenerationParams(GenerationParams(contextSize = 2048, maxTokens = 384, temperature = 0f, threads = 4))
            }
            suspend fun ask(question: String, fresh: Boolean = true): com.example.data.ChatMessageEntity {
                if (fresh) {
                    val before = vm.conversationId.value
                    compose.runOnUiThread { vm.newConversation() }
                    compose.waitUntil(15_000) { vm.conversationId.value != 0L && vm.conversationId.value != before }
                }
                compose.waitForIdle()
                val count = repository.messages(vm.conversationId.value).first().size
                compose.onNodeWithTag("chat_input_field").performTextReplacement(question)
                compose.onNodeWithTag("send_message_btn").performClick()
                compose.waitUntil(240_000) { !vm.isChatGenerating.value && vm.chatMessages.value.size > count }
                val answer = repository.messages(vm.conversationId.value).first().last { it.role == "assistant" }
                rows.put(JSONObject().put("question", question).put("answer", answer.content).put("status", answer.groundingStatus)
                    .put("state", answer.messageState).put("model", answer.modelName).put("sources", JSONArray(answer.sourcesJson))
                    .put("firstTokenMs", answer.latencyMs).put("tokensPerSecond", answer.tokensPerSec))
                File(context.filesDir, "research-follow-through-device.json").writeText(rows.toString(2))
                verify(answer.messageState == "COMPLETE", "$question: incomplete ${answer.messageState}")
                verify(GroundingResult.sources(answer.sourcesJson).isNotEmpty(), "$question: no read sources")
                verify(answer.groundingStatus != "INSUFFICIENT_EVIDENCE", "$question: rejected ${answer.content}")
                return answer
            }
            val bond = ask("who is the next james bond")
            verify(bond.groundingStatus != "LOCAL", "Bond research was skipped")
            verify(GroundingResult.sources(bond.sourcesJson).none { SourceBrief.predictionMarket(it.title, it.url, "who is the next james bond") }, "Betting result used as casting evidence")
            val transit = ask("Is jubilee line status good in tfl")
            verify(transit.groundingStatus != "LOCAL", "Service status research was skipped")
            val transitFollow = ask("Find status of the line from any websites", fresh = false)
            verify(GroundingResult.sources(transitFollow.sourcesJson).any { it.excerpt.contains("Jubilee", true) || it.title.contains("Jubilee", true) }, "Named line lost in follow-up")
            // A saved conversation from an older build must recover when the user says OK.
            val oldConversation = vm.conversationId.value
            compose.runOnUiThread { vm.newConversation() }
            compose.waitUntil(15_000) { vm.conversationId.value != oldConversation && vm.conversationId.value != 0L }
            val id = vm.conversationId.value
            for ((role, content) in listOf("user" to "should i wear a raincoat today", "assistant" to "I need to know your current location and the weather forecast for today.",
                "user" to "London", "assistant" to "I need to check the current weather forecast for London to tell you if you should wear a raincoat."))
                repository.insertChatMessage(com.example.data.ChatMessageEntity(role = role, content = content, conversationId = id, messageState = "COMPLETE"))
            compose.waitUntil(15_000) { vm.chatMessages.value.size == 4 }
            val resumed = ask("Ok", fresh = false)
            verify(EvidenceAnswer.hasTemperature(resumed.content) || Regex("(?i)rain|dry|cloud|forecast").containsMatchIn(resumed.content), "Raincoat continuation lost weather evidence")
            val direct = ask("should i wear a raincoat today in London")
            verify(direct.groundingStatus != "LOCAL", "Weather decision research was skipped")
            val doom = ask("Who is acting as Doom in Avengers Doomsday?")
            verify(doom.content.contains("Robert Downey", true), "Confirmed actor regression: ${doom.content}")
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            repository.engine.unloadActiveModel()
            File(context.filesDir, "research-follow-through-device.json").writeText(rows.toString(2))
        }
    }
}
