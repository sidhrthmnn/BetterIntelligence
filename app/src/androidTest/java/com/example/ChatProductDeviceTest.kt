package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.engine.*
import com.example.grounding.GroundingMode
import com.example.grounding.GroundingResult
import com.example.ui.AiCoreViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real model, real chat coordinator and public page reading; opt-in synthetic data only. */
@RunWith(AndroidJUnit4::class)
class ChatProductDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun historyEditingRegenerationCancellationAndGrounding() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("chatProduct") != "true") return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val repository = AiCoreRepository.getInstance(context)
        val models = withTimeout(300_000) { repository.allModels.first { list -> list.any { it.filename == "gemma-4-E2B-it.litertlm" && it.isInstalled } } }
        repository.switchActiveModel(models.single { it.filename == "gemma-4-E2B-it.litertlm" })
        repository.engine.setProcessingMode(ProcessingMode.CPU)
        val vm = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
        val results = JSONArray()
        fun record(name: String, answer: ChatMessageEntity) { results.put(JSONObject().put("scenario", name)
            .put("text", answer.content).put("state", answer.messageState).put("grounding", answer.groundingStatus)
            .put("sources", JSONArray(answer.sourcesJson))) }
        fun send(text: String): ChatMessageEntity {
            val previousReply = runBlocking { repository.messages(vm.conversationId.value).first().lastOrNull { it.role == "assistant" }?.id ?: 0 }
            compose.onNodeWithTag("chat_input_field").performTextReplacement(text)
            compose.onNodeWithTag("send_message_btn").performClick()
            compose.waitUntil(180_000) { !vm.isChatGenerating.value && vm.chatMessages.value.any { it.role == "assistant" && it.id > previousReply } }
            compose.waitForIdle()
            return runBlocking { repository.messages(vm.conversationId.value).first().last { it.role == "assistant" } }
        }
        fun newChat() {
            val old = vm.conversationId.value
            compose.onNodeWithTag("new_chat").performClick()
            compose.waitUntil(15_000) { vm.conversationId.value != old && vm.conversationId.value != 0L }
        }
        try {
            compose.waitUntil(30_000) { vm.conversationId.value != 0L }
            compose.runOnUiThread { vm.setWebAllowed(false); vm.updateGenerationParams(GenerationParams(contextSize = 2048, maxTokens = 128, temperature = 0f)) }
            newChat()
            val original = vm.conversationId.value
            record("remember", send("Remember my synthetic test code: MARIGOLD-47. Reply with OK."))
            val recall = send("What is my test code? Return just the code.")
            record("same-conversation-recall", recall)
            assertTrue("History did not reach the model: ${recall.content}", recall.content.contains("MARIGOLD-47"))
            assertEquals("COMPLETE", recall.messageState)
            screenshot(context, "chat-history-recall.png")
            // Regeneration must preserve an unsent draft and replace later replies, not duplicate turns.
            compose.runOnUiThread { vm.updateDraft("Synthetic unsent draft"); vm.regenerateResponse() }
            compose.waitUntil(180_000) { !vm.isChatGenerating.value }
            assertEquals("Synthetic unsent draft", vm.draft.value)
            assertEquals(4, repository.messages(original).first().size)
            val user = repository.messages(original).first().first { it.role == "user" }
            compose.runOnUiThread { vm.editMessage(user) }
            val edited = send("What is 17 multiplied by 6? Give only the number.")
            record("edit-and-resend", edited)
            assertTrue(edited.content.contains("102"))
            assertEquals(2, repository.messages(original).first().size)
            newChat()
            val isolated = send("What synthetic test code did I tell you in this conversation? If none, say unknown.")
            record("new-conversation-isolation", isolated)
            assertFalse("Cross-conversation leak", isolated.content.contains("MARIGOLD-47"))
            // Visible streaming can be cancelled; partial text must remain in history.
            compose.onNodeWithTag("chat_input_field").performTextReplacement("Count from one to five hundred, separated by commas.")
            compose.onNodeWithTag("send_message_btn").performClick()
            compose.waitUntil(30_000) { vm.streamingChatText.value.isNotBlank() }
            compose.onNodeWithTag("stop_generation_btn").performClick()
            compose.waitUntil(30_000) { !vm.isChatGenerating.value }
            val stopped = repository.messages(vm.conversationId.value).first().last()
            record("cancelled-stream", stopped)
            assertEquals("STOPPED", stopped.messageState)
            assertTrue(stopped.content.isNotBlank())
            assertFalse(repository.engine.telemetry.value.isRunning)
            // A separate request, through the shared client-facing core, receives no chat history.
            val localClient = repository.engine.generateSync("What synthetic test code did I tell you? If none, say unknown.",
                GenerationParams(contextSize = 2048, maxTokens = 64, temperature = 0f))
            assertFalse(localClient.contains("MARIGOLD-47"))
            // Real online retrieval AND actual local generation. Merely retrieving pages is insufficient.
            repository.preferencesRepository.saveWebAllowed(true)
            newChat()
            compose.runOnUiThread { vm.setGroundingMode(GroundingMode.ON) }
            val grounded = send("Summarize https://www.iana.org/help/example-domains")
            record("web-researched-answer", grounded)
            assertTrue(grounded.messageState in setOf("COMPLETE", "LENGTH_LIMIT"))
            assertTrue("No actual page text retrieved", GroundingResult.sources(grounded.sourcesJson).isNotEmpty())
            assertTrue(grounded.groundingStatus in setOf("EVIDENCE_CITED", "CITATIONS_MISSING"))
            assertFalse(Regex("https?://").containsMatchIn(grounded.content))
            screenshot(context, "chat-web-sources.png")
            // A rejected local URL must not be sent to search or called grounded.
            run {
                newChat(); compose.runOnUiThread { vm.setGroundingMode(GroundingMode.ON) }
                val unavailable = send("Read https://127.0.0.1/private")
                record("private-url-rejected", unavailable)
                assertEquals("INSUFFICIENT_EVIDENCE", unavailable.groundingStatus)
                assertTrue(GroundingResult.sources(unavailable.sourcesJson).isEmpty())
                screenshot(context, "chat-search-unavailable.png")
            }
            compose.runOnUiThread { vm.setWebAllowed(false) }
            repository.preferencesRepository.saveWebAllowed(false)
            newChat()
            val offline = send("What is 8 plus 9? Give only the number.")
            record("web-off", offline)
            assertEquals("LOCAL", offline.groundingStatus); assertEquals("[]", offline.sourcesJson)
            assertTrue(offline.content.contains("17"))
            compose.activityRule.scenario.recreate()
            val restored = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
            compose.waitUntil(30_000) { restored.chatMessages.value.any { it.content == offline.content } }
            compose.onNodeWithTag("open_history").performClick()
            screenshot(context, "history-with-conversations.png")
        } finally {
            repository.preferencesRepository.saveWebAllowed(false)
            repository.engine.setProcessingMode(ProcessingMode.AUTO)
            File(context.filesDir, "chat-product-results.json").writeText(results.toString(2))
        }
    }

    private fun screenshot(context: Context, name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(1_000) // Let the real IME/window transition reach its final frame.
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(context.filesDir, "ui-2.0").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
