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
    @Test fun multilingualEditingSelectsOriginalLanguagePromptAndKeepsInputAsData() {
        for (text in listOf("ഞാൻ നാളെ ഓഫീസിൽ വരാം.", "Njan naale office-il varaam.", "Meeting നാളെ 09:15-ന് ആണ്.")) {
            assertEquals(text, org.json.JSONObject(PolishText.prompt(text)).getString("text"))
        }
        assertEquals(PolishText.SYSTEM_PROMPT, PolishText.parameters(2048, "i has 2 apples.").systemPrompt)
        assertEquals(PolishText.SYSTEM_PROMPT, PolishText.parameters(2048, "Njan naale office-il varaam.").systemPrompt)
        org.junit.Assert.assertTrue(PolishText.parameters(2048, "ഞാൻ നാളെ വരാം.").systemPrompt!!.contains("Never translate"))
    }
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
}
