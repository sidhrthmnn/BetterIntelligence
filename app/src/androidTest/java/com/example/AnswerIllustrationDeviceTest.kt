package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.grounding.EvidenceSource
import com.example.ui.components.MarkdownText
import com.example.ui.components.SourceImageCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AnswerIllustrationDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun citationNumbersOpenTheirOwnSourceAndImagesStayOptInAndRespectRevocation() {
        val allowed=mutableStateOf(true); var selected=0
        val source=EvidenceSource(1,"Illustration fixture","https://publisher.example/article","Fictional fixture, not a public discovery.",1,kind="WEB_PAGE",imageUrl="https://publisher.example/image.png")
        val text="Source fact [1, 2]"
        compose.setContent { MaterialTheme { Column(Modifier.padding(20.dp)) {
            MarkdownText("## From the sources\n\n$text\n\n| Item | Evidence |\n|---|---|\n| Example | [1] |",setOf(1,2),onCitation={selected=it})
            SourceImageCard(source,allowed.value) {selected=1}
        } } }
        compose.onNodeWithText("Show source image").assertIsDisplayed()
        val node=compose.onNodeWithText(text)
        val layouts=mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bounds=layouts.single().getBoundingBox(text.indexOf('2'))
        node.performTouchInput { click(bounds.center) }
        compose.runOnIdle {assertEquals(2,selected);allowed.value=false}
        compose.onNodeWithTag("source_image").assertDoesNotExist()
        compose.runOnIdle {allowed.value=true}
        compose.onNodeWithText("Show source image").assertIsDisplayed()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.filesDir,"answer-formatting.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
