package com.example

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.ChatMessageEntity
import com.example.ui.screens.ChatTranscript
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatTranscriptTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ordinaryGrowthAndSmallScrollNeverShowTheJumpArrow() {
        val text = mutableStateOf("A short answer")
        lateinit var state: LazyListState
        compose.setContent { state = rememberLazyListState(); MaterialTheme {
            ChatTranscript(emptyList(), true, text.value, "Test", {}, Modifier.height(360.dp), state)
        } }
        compose.waitForIdle(); compose.onNodeWithTag("jump_to_latest").assertDoesNotExist()
        repeat(3) {
            compose.runOnIdle { text.value += "\nA longer streaming line".repeat(20) }
            compose.waitForIdle(); compose.onNodeWithTag("jump_to_latest").assertDoesNotExist()
            compose.runOnIdle { assertFalse(state.canScrollForward) }
        }
        compose.onNodeWithTag("chat_transcript").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -60f) }
        compose.waitForIdle(); compose.onNodeWithTag("jump_to_latest").assertDoesNotExist()
    }

    @Test fun persistedStreamingItemKeepsItsKeyAndNeverDuplicatesTheAnswer() {
        val messages = mutableStateOf(listOf(ChatMessageEntity(id = 1, role = "assistant", content = "", messageState = "STREAMING")))
        val generating = mutableStateOf(true)
        compose.setContent { MaterialTheme { ChatTranscript(messages.value, generating.value, "A complete answer", "Test", {}, Modifier.height(360.dp), pendingReplyId = 1) } }
        compose.onAllNodesWithText("A complete answer").assertCountEquals(1)
        compose.runOnIdle { messages.value = listOf(messages.value.single().copy(content = "A complete answer", messageState = "COMPLETE")) }
        compose.waitForIdle(); compose.onAllNodesWithText("A complete answer").assertCountEquals(1)
        compose.runOnIdle { generating.value = false }
        compose.waitForIdle(); compose.onAllNodesWithText("A complete answer").assertCountEquals(1)
    }

    @Test fun accessibilityScrollIsNotPulledBackDuringStreaming() {
        val text = mutableStateOf("Streaming line\n".repeat(120))
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            MaterialTheme { ChatTranscript(emptyList(), true, text.value, "Test model", {}, Modifier.height(360.dp), state) }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(state.canScrollForward) }
        compose.onNodeWithTag("chat_transcript").performSemanticsAction(SemanticsActions.ScrollBy) { action ->
            assertTrue(action(0f, -500f))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("jump_to_latest").assertIsDisplayed()
        var index = 0; var offset = 0
        compose.runOnIdle {
            index = state.firstVisibleItemIndex; offset = state.firstVisibleItemScrollOffset
            text.value += "Additional streamed line\n".repeat(30)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(index, state.firstVisibleItemIndex); assertEquals(offset, state.firstVisibleItemScrollOffset) }
        compose.onNodeWithTag("jump_to_latest").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(state.canScrollForward) }
    }

    @Test fun longStreamingAnswerFollowsBottomWithoutPullingReaderAwayFromHistory() {
        val text = mutableStateOf("First line\n")
        val generating = mutableStateOf(true)
        val messages = mutableStateOf((1L..6L).map {
            ChatMessageEntity(id = it, role = if (it % 2L == 0L) "assistant" else "user",
                content = "Message $it\n".repeat(8), modelName = "Test model")
        })
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            MaterialTheme {
                ChatTranscript(messages.value, generating.value, text.value, "Test model", {},
                    Modifier.height(360.dp), state)
            }
        }
        compose.runOnIdle { text.value = "A long answer\n".repeat(120) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(state.canScrollForward)
            assertTrue(state.canScrollBackward)
        }
        // Scroll within the long streaming bubble, not only to a separate earlier message.
        compose.onNodeWithTag("chat_transcript").performTouchInput { swipeDown() }
        compose.onNodeWithTag("jump_to_latest").assertIsDisplayed()
        compose.onRoot().captureRoboImage(filePath = "build/outputs/verification/chat-reading.png")
        var readingIndex = -1
        var readingOffset = -1
        compose.runOnIdle {
            readingIndex = state.firstVisibleItemIndex
            readingOffset = state.firstVisibleItemScrollOffset
            text.value += "More streamed text\n".repeat(80)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(readingIndex, state.firstVisibleItemIndex)
            assertEquals(readingOffset, state.firstVisibleItemScrollOffset)
        }
        compose.runOnIdle {
            messages.value += ChatMessageEntity(id = 7, role = "assistant", content = text.value, modelName = "Test model")
            generating.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(readingIndex, state.firstVisibleItemIndex)
            assertEquals(readingOffset, state.firstVisibleItemScrollOffset)
        }
        compose.onNodeWithTag("jump_to_latest").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(state.canScrollForward) }
        compose.onNodeWithTag("jump_to_latest").assertDoesNotExist()
        compose.onRoot().captureRoboImage(filePath = "build/outputs/verification/chat-latest.png")
        compose.onNodeWithTag("chat_transcript").performTouchInput { swipeDown() }
        compose.onNodeWithTag("jump_to_latest").assertIsDisplayed()
        compose.runOnIdle {
            messages.value += ChatMessageEntity(id = 8, role = "user", content = "Next question", modelName = "Test model")
            generating.value = true
            text.value = ""
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(state.canScrollForward) }
    }
}
