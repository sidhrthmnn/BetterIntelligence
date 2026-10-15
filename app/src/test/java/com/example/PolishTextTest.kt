package com.example

import com.example.ipc.PolishText
import org.junit.Assert.assertEquals
import org.junit.Test

class PolishTextTest {
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
