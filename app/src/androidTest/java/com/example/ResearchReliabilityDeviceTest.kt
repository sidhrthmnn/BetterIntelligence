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
        val failures = mutableListOf<String>()
        fun verify(value: Boolean, message: String) { if (!value) failures += message }
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
                verify(answer.messageState == "COMPLETE", "Incomplete response to $question: ${answer.content}")
                return answer
            }
            val weather = ask("What is the weather in London today?")
            verify(EvidenceAnswer.hasTemperature(weather.content), "Missing weather temperature: ${weather.content}")
            if (GroundingResult.sources(weather.sourcesJson).any { it.excerpt.contains("Observed temperature:") })
                verify(weather.content.contains("Observed temperature:"), "Observation lost: ${weather.content}")
            verify(weather.groundingStatus != "INSUFFICIENT_EVIDENCE", "Weather was rejected")
            val mayor = ask("Who is the mayor there?")
            verify(mayor.content.contains("Khan", true), "Wrong/missing mayor: ${mayor.content}")
            verify(!mayor.content.contains("Susan Langley"), "Different mayor role: ${mayor.content}")
            val temperature = ask("What is the temperature there?")
            verify(EvidenceAnswer.hasTemperature(temperature.content), "Lost temperature follow-up: ${temperature.content}")
            val domains = ask("Can example domains be registered or transferred? Read https://www.iana.org/help/example-domains")
            verify(Regex("(?i)not available|cannot|can't|not .*register|not .*transfer").containsMatchIn(domains.content), "Wrong IANA restriction: ${domains.content}")
            verify(domains.groundingStatus != "INSUFFICIENT_EVIDENCE", "IANA answer was rejected")
            val uk = ask("Who is the PM of UK?")
            verify(uk.content.contains("Andy Burnham", true), "Wrong/missing UK PM: ${uk.content}")
            val kerala = ask("Who is the CM of Kerala?")
            verify(kerala.content.contains("Satheesan", true), "Wrong/missing Kerala CM: ${kerala.content}")
            val price = ask("What does iPhone Duo cost in the UK? Read https://www.apple.com/uk/shop/buy-iphone")
            verify(Regex("£\\s*1,?999\\b").containsMatchIn(price.content), "Wrong/missing starting price: ${price.content}")
            val news = ask("Give me news update")
            verify(news.content == ConversationalResearch.askNewsTopic && news.completionTokens == 0, "News topic was not clarified: ${news.content}")
            val calculation = ask("What is 15% of 240?")
            verify(Regex("\\b36\\b").containsMatchIn(calculation.content), "Wrong arithmetic: ${calculation.content}")
            verify(GroundingResult.sources(calculation.sourcesJson).isEmpty(), "Arithmetic incorrectly used web research")
            val unknown = ask("What is the unannounced launch date of the fictional Orion device? Read https://www.iana.org/help/example-domains")
            verify(unknown.groundingStatus == "INSUFFICIENT_EVIDENCE" && unknown.content == GroundingPolicy.unavailableAnswer, "Unrelated evidence produced an answer: ${unknown.content}")
            val before = vm.conversationId.value
            compose.runOnUiThread { vm.newConversation() }
            compose.waitUntil(15_000) { vm.conversationId.value != before && vm.conversationId.value != 0L }
            compose.runOnUiThread { vm.setGroundingMode(GroundingMode.AUTO) }
            val missingPlace = ask("Who is the mayor there?")
            verify(missingPlace.content == ConversationalResearch.askPlace && missingPlace.completionTokens == 0 &&
                GroundingResult.sources(missingPlace.sourcesJson).isEmpty(), "New conversation inherited a place: ${missingPlace.content}")
            val clarified = ask("London")
            verify(clarified.content.contains("Khan", true), "Location clarification failed: ${clarified.content}")
            val old = vm.conversationId.value
            compose.runOnUiThread { vm.newConversation() }
            compose.waitUntil(15_000) { vm.conversationId.value != old && vm.conversationId.value != 0L }
            compose.runOnUiThread { vm.setGroundingMode(GroundingMode.OFF) }
            ask("Let's play a harmless guessing game. The item is a snake. Answer my next questions with only Yes or No.")
            val mammal = ask("Is it a mammal?")
            verify(Regex("(?i)^no\\b").containsMatchIn(mammal.content.trim()), "Game clue contradicted the item: ${mammal.content}")
            val reptile = ask("Is it a reptile?")
            verify(Regex("(?i)^yes\\b").containsMatchIn(reptile.content.trim()), "Reptile clue/context failed: ${reptile.content}")
            val item = ask("What was our game item?")
            verify(item.content.contains("snake", true), "Game context lost: ${item.content}")
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            repository.engine.unloadActiveModel()
            File(context.filesDir, "research-reliability-phone.json").writeText(report.toString(2))
        }
    }
}
