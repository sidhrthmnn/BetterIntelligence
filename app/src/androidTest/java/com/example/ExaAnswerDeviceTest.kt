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
class ExaAnswerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun gemmaAnswersTheFailedQuestionsFromActualReadPages(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("exaAnswers") == "true")
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
                File(context.filesDir, "exa-answers-device.json").writeText(rows.toString(2))
                verify(answer.messageState == "COMPLETE", "$question: incomplete ${answer.messageState}")
                verify(GroundingResult.sources(answer.sourcesJson).isNotEmpty(), "$question: no read sources")
                verify(answer.groundingStatus != "INSUFFICIENT_EVIDENCE", "$question: rejected ${answer.content}")
                return answer
            }
            val doom = ask("Who is acting as Doom in Avengers Doomsday?")
            verify(doom.content.contains("Robert Downey", true), "Actor missing: ${doom.content}")
            val election = ask("How is the election going on in israel?")
            verify(Regex("(?i)Israel|Netanyahu|Eisenkot").containsMatchIn(election.content), "Election subject lost: ${election.content}")
            verify(Regex("(?i)seats?|majority|votes?|count|bloc").containsMatchIn(election.content), "Election result missing: ${election.content}")
            verify(FactualResearch.answerFocusScore(election.content, "How is the election going on in Israel?") >= 65, "Election count/status not answered: ${election.content}")
            val movie = ask("How is Jailer 2 movie?")
            verify(movie.content.contains("Jailer", true), "Movie subject lost: ${movie.content}")
            verify(FactualResearch.answerFocusScore(movie.content, "How is Jailer 2 movie?") >= 65, "Critic assessment/rating missing: ${movie.content}")
            val price = ask("What is the price of a latte at Pret in the UK?")
            verify(Regex("£\\s*\\d").containsMatchIn(price.content), "Latte price missing: ${price.content}")
            val followUp = ask("Is that the latte price in the UK?", fresh = false)
            verify(Regex("£\\s*\\d").containsMatchIn(followUp.content), "Public price follow-up lost: ${followUp.content}")
            val weather = ask("What is the weather in London today?")
            verify(EvidenceAnswer.hasTemperature(weather.content), "Temperature missing: ${weather.content}")
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            repository.engine.unloadActiveModel()
            File(context.filesDir, "exa-answers-device.json").writeText(rows.toString(2))
        }
    }
}
