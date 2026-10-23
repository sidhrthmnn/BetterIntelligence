package com.example

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.data.ChatMessageEntity
import com.example.grounding.*
import com.example.ui.components.ChatMarkdown
import com.example.ui.components.MarkdownBlock
import com.example.ui.screens.ChatTranscript
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
class ChatRenderingTest {
    @get:Rule val compose = createComposeRule()
    @Test fun longUserTextFitsAt320DpWithLargeFonts() {
        val text = "https://example.com/" + "unbroken".repeat(50) + "\nMultiline text"
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) { MaterialTheme {
            ChatTranscript(listOf(ChatMessageEntity(id = 1, role = "user", content = text)), false, "", "Test", {}, Modifier.width(320.dp))
        } } }
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        assertTrue(node.boundsInRoot.left >= 0); assertTrue(node.boundsInRoot.right <= 320f)
    }
    @Test fun sourcesAreCompactAndExpandableWithOnlyStoredLinks() {
        val source = EvidenceSource(1, "Official source", "https://example.com/report", "Evidence", 1, "2026-10-23", "WEB_PAGE", "PUBLISHED")
        compose.setContent { MaterialTheme { ChatTranscript(listOf(ChatMessageEntity(id = 2, role = "assistant", content = "A fact [1]", sourcesJson = GroundingResult(sources = listOf(source)).json())), false, "", "Test", {}) } }
        compose.onNodeWithText("[1] Official source").assertDoesNotExist()
        compose.onNodeWithTag("answer_sources").performClick()
        compose.onNodeWithText("[1] Official source").assertIsDisplayed().performClick()
        compose.onNodeWithText("Page-reported publication: 2026-10-23").assertIsDisplayed()
        compose.onNodeWithText("Open source").assertIsDisplayed()
    }
    @Test fun markdownKeepsListsQuotesAndCodeSeparate() {
        val parsed = ChatMarkdown.parse("# Title\n\n**Paragraph**\n\n- First\n1. Second\n> Quoted\n```kotlin\nval tokens = \"<unused1>\"\n```")
        assertTrue(parsed.any { it is MarkdownBlock.Heading }); assertEquals(2, parsed.count { it is MarkdownBlock.ListEntry })
        assertTrue(parsed.any { it is MarkdownBlock.Quote }); assertTrue(parsed.any { it is MarkdownBlock.Code && it.text.contains("<unused1>") })
    }
}
