package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AiCoreRepository
import com.example.engine.*
import com.example.grounding.*
import com.example.ui.AiCoreViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real chat ViewModel, live websites and local Qwen inference. Synthetic questions only. */
class GroundingRecentAnswerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recentQuestionsUseRetrievedFacts() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("recentGrounding") != "true") return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val phase = if (args.getString("groundingPhase") == "baseline") "baseline" else "improved"
        val repository = AiCoreRepository.getInstance(context)
        val models = withTimeout(120_000) { repository.allModels.first { list ->
            list.any { it.filename == "qwen2.5-0.5b-instruct-q4_k_m.gguf" && it.isInstalled }
        } }
        repository.engine.setProcessingMode(ProcessingMode.CPU)
        repository.switchActiveModel(models.single { it.filename == "qwen2.5-0.5b-instruct-q4_k_m.gguf" })
        val vm = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
        val report = JSONArray()
        try {
            repository.preferencesRepository.saveWebAllowed(true)
            compose.waitUntil(15_000) { vm.webAllowed.value && !repository.isModelLoading }
            compose.waitUntil(30_000) { vm.conversationId.value != 0L }
            compose.runOnUiThread {
                vm.updateGenerationParams(GenerationParams(contextSize = 2048, maxTokens = 64, temperature = 0f, threads = 4))
                vm.toggleForegroundService(false)
            }
            val cases = listOf(
                "Who won the 2026 Nobel Prize in Physics?" to listOf("Francis", "Halzen"),
                "Who won the 2026 Nobel Prize in Chemistry?" to listOf("Kagan", "Soai"),
                "Who won the 2026 physics Nobel? Read https://icecube.wisc.edu/news/awards/2026/10/francis-halzen-icecube-principal-investigator-wins-2026-physics-nobel-prize/" to listOf("Halzen")
            )
            var factualAnswers = 0
            for ((question, expected) in cases) {
                val old = vm.conversationId.value
                compose.runOnUiThread { vm.newConversation() }
                compose.waitUntil(15_000) { vm.conversationId.value != old && vm.conversationId.value != 0L }
                compose.waitForIdle()
                compose.runOnUiThread { vm.setGroundingMode(GroundingMode.ON) }
                compose.onNodeWithTag("chat_input_field").performTextReplacement(question)
                compose.onNodeWithTag("send_message_btn").performClick()
                compose.waitUntil(300_000) { !vm.isChatGenerating.value && vm.chatMessages.value.any { it.role == "assistant" } }
                val answer = repository.messages(vm.conversationId.value).first().last { it.role == "assistant" }
                val correct = expected.all { answer.content.contains(it, ignoreCase = true) }
                report.put(JSONObject().put("question", question).put("expectedNames", JSONArray(expected))
                    .put("installedVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                    .put("model", repository.engine.activeModelName)
                    .put("answer", answer.content).put("namesMatch", correct).put("state", answer.messageState)
                    .put("promptTokens", answer.promptTokens).put("completionTokens", answer.completionTokens)
                    .put("latencyMs", answer.latencyMs).put("tokensPerSecond", answer.tokensPerSec)
                    .put("firstTokenMs", repository.engine.telemetry.value.timeToFirstTokenMs)
                    .put("threads", repository.engine.telemetry.value.activeThreads)
                    .put("grounding", answer.groundingStatus).put("sources", JSONArray(answer.sourcesJson)))
                File(context.filesDir, "grounding-recent-$phase.json").writeText(report.toString(2))
                if (phase == "improved") {
                    if (correct) factualAnswers++ else {
                        // A tiny local model may decline even when pages were read.
                        // An explicit unavailable reply is allowed; an incorrect factual reply is not.
                        assertEquals("Incorrect factual answer for $question", GroundingPolicy.unavailableAnswer, answer.content)
                        assertNotEquals("EVIDENCE_CITED", answer.groundingStatus)
                    }
                    assertEquals("COMPLETE", answer.messageState)
                    val hasInlineCitation = GroundingPolicy.validateCitations(answer.content, GroundingResult.sources(answer.sourcesJson)).second
                    // Source cards remain available when a small model omits markers.
                    // Never describe that answer as having valid inline citations.
                    assertEquals(if (hasInlineCitation) "EVIDENCE_CITED" else "CITATIONS_MISSING", answer.groundingStatus)
                    assertTrue(GroundingResult.sources(answer.sourcesJson).isNotEmpty())
                }
            }
            if (phase == "improved") {
                assertTrue("At least one complete recent factual answer is required", factualAnswers > 0)
                val old = vm.conversationId.value
                compose.runOnUiThread { vm.newConversation() }
                compose.waitUntil(15_000) { vm.conversationId.value != old && vm.conversationId.value != 0L }
                compose.waitForIdle()
                compose.runOnUiThread { vm.setGroundingMode(GroundingMode.ON) }
                compose.onNodeWithTag("chat_input_field").performTextReplacement("Read https://127.0.0.1/private and tell me today's winner")
                compose.onNodeWithTag("send_message_btn").performClick()
                compose.waitUntil(30_000) { !vm.isChatGenerating.value && vm.chatMessages.value.any { it.role == "assistant" } }
                val refused = repository.messages(vm.conversationId.value).first().last { it.role == "assistant" }
                assertEquals("INSUFFICIENT_EVIDENCE", refused.groundingStatus)
                assertEquals(GroundingPolicy.unavailableAnswer, refused.content)
                assertEquals(0, refused.completionTokens)
                assertTrue(GroundingResult.sources(refused.sourcesJson).isEmpty())
                report.put(JSONObject().put("scenario", "unavailable-source-refusal").put("answer", refused.content)
                    .put("grounding", refused.groundingStatus).put("completionTokens", refused.completionTokens))
            }
        } finally {
            repository.preferencesRepository.saveWebAllowed(false)
            File(context.filesDir, "grounding-recent-$phase.json").writeText(report.toString(2))
        }
        Unit
    }
}
