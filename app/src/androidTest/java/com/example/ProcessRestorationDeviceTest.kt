package com.example

import android.content.Context
import android.os.Process
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.engine.ModelLibrary
import com.example.engine.ProcessingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Two host-orchestrated phases, separated by am force-stop of validation only.
 * A different PID is required: Activity recreation cannot satisfy this test.
 */
@RunWith(AndroidJUnit4::class)
class ProcessRestorationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun draftsPartialRepliesApprovalsAndSelectedModelSurviveProcessDeath() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("processRestoration") ?: return@runBlocking
        require(phase in setOf("prepare", "restore"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val repository = AiCoreRepository.getInstance(context)
        val preferences = repository.preferencesRepository
        val models = withTimeout(300_000) { repository.allModels.first { it.size >= ModelLibrary.read(context).size } }
        val checkpoint = File(context.filesDir, "process-restoration-checkpoint.json")
        val client = "com.example.synthetic.restart"
        if (phase == "prepare") {
            check(!checkpoint.exists()) { "Finish or explicitly clean the previous synthetic restoration fixture first" }
            repository.unloadModel()
            val id = repository.createConversation()
            val state = JSONObject().put("preparedPid", Process.myPid()).put("conversationId", id)
                .put("previousConversation", preferences.activeConversation.first())
                .put("previousAppearance", preferences.appearance.first())
            checkpoint.writeText(state.toString(2))
            val contextId = repository.createConversation()
            state.put("contextConversationId", contextId)
            checkpoint.writeText(state.toString(2))
            for (turn in 1..9) {
                repository.insertChatMessage(ChatMessageEntity(conversationId = contextId, role = "user",
                    content = if (turn == 1) "My game is Colour Circle" else "Synthetic session detail $turn"))
                repository.insertChatMessage(ChatMessageEntity(conversationId = contextId, role = "assistant", content = "Synthetic reply $turn"))
            }
            val summary = repository.refreshConversationContext(contextId, repository.messages(contextId).first())
            assertTrue(summary.contains("Colour Circle"))
            state.put("expectedContext", summary)
            repository.insertChatMessage(ChatMessageEntity(conversationId = id, role = "user", content = "Synthetic restart marker 73."))
            repository.insertChatMessage(ChatMessageEntity(conversationId = id, role = "assistant", content = "Synthetic preserved partial 73", messageState = "STREAMING"))
            repository.saveDraft(id, "Synthetic draft after process restart 73")
            repository.updatePolicy(ClientAppPolicyEntity(packageName = client, appName = "Synthetic restart client", isWhitelisted = true))
            preferences.saveActiveConversation(id)
            preferences.saveAppearance("DARK")
            repository.engine.setProcessingMode(ProcessingMode.CPU)
            repository.switchActiveModel(models.single { it.filename == "smollm2-135m-instruct-q4_k_m.gguf" })
            state.put("status", "prepared").put("model", repository.engine.activeModelName)
            checkpoint.writeText(state.toString(2))
            return@runBlocking
        }
        val state = JSONObject(checkpoint.readText())
        assertNotEquals("Host must actually restart the process", state.getInt("preparedPid"), Process.myPid())
        val id = state.getLong("conversationId")
        try {
            val session = repository.conversation(state.getLong("contextConversationId"))!!
            assertEquals(state.getString("expectedContext"), session.contextSummary)
            assertTrue(session.contextSummary.contains("Colour Circle"))
            assertFalse(repository.conversation(id)!!.contextSummary.contains("Colour Circle"))
            ActivityScenario.launch(MainActivity::class.java).use {
                val messages = withTimeout(30_000) { repository.messages(id).first { rows -> rows.any { it.messageState == "STOPPED" } } }
                assertEquals("Synthetic preserved partial 73", messages.single { it.role == "assistant" }.content)
                assertEquals("Synthetic draft after process restart 73", repository.conversation(id)!!.draft)
                assertEquals(id, preferences.activeConversation.first())
                assertEquals("DARK", preferences.appearance.first())
                assertTrue(repository.clientPolicies.first().single { it.packageName == client }.isWhitelisted)
                withTimeout(60_000) { while (!repository.engine.isReady()) kotlinx.coroutines.delay(100) }
                assertEquals(state.getString("model"), repository.engine.activeModelName)
                compose.waitForIdle()
                compose.onNodeWithTag("chat_input_field").assertTextContains("Synthetic draft after process restart 73")
                state.put("status", "complete").put("restoredPid", Process.myPid())
                File(context.filesDir, "process-restoration-result.json").writeText(state.toString(2))
            }
        } finally {
            repository.unloadModel()
            preferences.saveActiveConversation(state.getLong("previousConversation"))
            preferences.saveAppearance(state.getString("previousAppearance"))
            repository.engine.setProcessingMode(ProcessingMode.AUTO)
            repository.deleteConversation(id)
            repository.deleteConversation(state.getLong("contextConversationId"))
            repository.deletePolicy(client)
            checkpoint.delete()
        }
    }
}
