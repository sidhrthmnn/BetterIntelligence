package com.example

import com.example.engine.*
import org.junit.Assert.*
import org.junit.Test

class ModelOutputValidatorTest {
    @Test fun fragmentedControlStreamIsRejectedBeforeDisplay() {
        val stream = ModelOutputValidator.Stream(); val shown = StringBuilder()
        val failure = runCatching { "?? <unused15><unused26><unused28><unused0>[multimodal]".chunked(3).forEach { shown.append(stream.append(it)) } }.exceptionOrNull()
        assertTrue(failure is InvalidModelOutputException)
        assertFalse(shown.contains("unused")); assertTrue(shown.length < 10)
    }
    @Test fun validStreamingAndFinalTextAreIdentical() {
        val text = "A readable response with Malayalam: നാളെ ഓഫീസിൽ വരാം.\n\n**Done**."
        val stream = ModelOutputValidator.Stream(); val shown = StringBuilder()
        text.chunked(2).forEach { shown.append(stream.append(it)) }
        shown.append(stream.finish()); assertEquals(text, shown.toString())
    }
    @Test fun literalCodeAndQuotedExamplesStayIntact() {
        for (text in listOf("Use `<unused15><unused26><unused28>` as a literal example.",
            "```text\n<unused1><unused2><unused3>\n```", "The example is \"<unused1><unused2><unused3>\".",
            "In tokenizers, <unused15> is a reserved token. It is not an ordinary word.")) {
            ModelOutputValidator.validate(text)
            val stream = ModelOutputValidator.Stream()
            assertEquals(text, stream.append(text) + stream.finish())
        }
    }
    @Test fun excessiveReplacementAndLongRepetitionFailButNormalPunctuationDoesNot() {
        assertTrue(runCatching { ModelOutputValidator.validate("�".repeat(20)) }.exceptionOrNull() is InvalidModelOutputException)
        assertTrue(runCatching { ModelOutputValidator.validate("broken unit ".repeat(20)) }.exceptionOrNull() is InvalidModelOutputException)
        ModelOutputValidator.validate("Wait... okay! 🙂 நன்றி. 1, 2, 3, 4, 5.")
    }
    @Test fun finalOnlyCorruptionIsRejectedToo() {
        assertTrue(runCatching { ModelOutputValidator.Stream().finish("<unused1><unused2><unused3>") }.isFailure)
    }
    @Test fun explicitlyRequestedLiteralTokensAndLongFencedExamplesArePreserved() {
        val example = "<unused1><unused2><unused3>"
        val stream = ModelOutputValidator.Stream("Print exactly $example")
        assertEquals(example, stream.append(example) + stream.finish())
        ModelOutputValidator.validate("```text\n" + "example line\n".repeat(600) + example + "\n```")
        assertTrue(runCatching { ModelOutputValidator.validate(example.repeat(20), "Explain <unused1>") }.isFailure)
    }
}
