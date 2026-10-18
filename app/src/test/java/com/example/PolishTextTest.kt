package com.example

import com.example.ipc.PolishText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PolishTextTest {
    @Test fun proofreadingPassageRoundTripsAsDataEvenWithQuotesAndNewlines() {
        val input = "Keep \"user_id\" unchanged.\nDo not delete 0042."
        assertEquals(input, org.json.JSONObject(PolishText.prompt(input)).getString("text"))
    }

    @Test fun externalPolishUsesBoundedContextAndLeavesSpaceForItsInput() {
        val mobile = PolishText.parameters(32003)
        assertEquals(2048, mobile.contextSize)
        assertEquals(1024, mobile.maxTokens)
        val small = PolishText.parameters(1280)
        assertEquals(1280, small.contextSize)
        assertEquals(640, small.maxTokens)
    }
    @Test fun acceptsGrammarEditsWhilePreservingEditorLiterals() {
        assertEquals("I have 2 apples. Email a@b.com or visit https://example.com.",
            PolishText.validateOutput("i has 2 apples. email a@b.com or visit https://example.com.",
                "I have 2 apples. Email a@b.com or visit https://example.com."))
    }

    @Test(expected = IllegalStateException::class) fun rejectsChangedNumbers() {
        PolishText.validateOutput("Pay 200 by Friday.", "Pay 300 by Friday.")
    }

    @Test(expected = IllegalStateException::class) fun rejectsChangedLinks() {
        PolishText.validateOutput("Visit https://example.com", "Visit https://other.com")
    }

    @Test(expected = IllegalStateException::class) fun rejectsEmptyOutput() {
        PolishText.validateOutput("Hello", "   ")
    }

    @Test(expected = IllegalStateException::class) fun rejectsModelReasoning() {
        PolishText.validateOutput("hello", "<think>Here is my reasoning</think>Hello")
    }
}
