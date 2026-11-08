package com.example

import com.example.engine.*
import org.junit.Assert.*
import org.junit.Test

class ChatResponseBudgetTest {
    @Test fun chatStartsWithUsefulHeadroomAndHonoursLargerRequestedLimits() {
        assertEquals(512, ChatResponseBudget.initial(256, 2048))
        assertEquals(768, ChatResponseBudget.initial(768, 2048))
        assertEquals(1024, ChatResponseBudget.initial(4096, 4096))
        assertEquals(256, ChatResponseBudget.initial(16, 1024))
        assertTrue(ChatResponseBudget.initial(512, 512) <= 256)
    }
    @Test fun externalRequestsAndBenchmarksKeepTheirHardBudgetByDefault() {
        val params = GenerationParams(maxTokens = 16, contextSize = 2048)
        assertFalse(params.autoContinue); assertFalse(GenerationConfig().autoContinue)
        assertEquals(16, ChatResponseBudget.liteRtLimit("Count to fifty", params))
        val observer = NativeObserver(null) { _, _, _ -> }
        assertFalse(observer.allowResponseExpansion())
    }
    @Test fun liteRtHeadroomAccountsForHistorySystemAndMultibyteText() {
        val params = GenerationParams(maxTokens = 128, contextSize = 2048, autoContinue = true)
        assertEquals(1024, ChatResponseBudget.liteRtLimit("Hello", params))
        val crowded = params.copy(history = listOf(ChatTurn("user", "界".repeat(600))), systemPrompt = "Rules ".repeat(30))
        assertEquals(128, ChatResponseBudget.liteRtLimit("Continue", crowded))
        val bounded = ChatResponseBudget.liteRtLimit("x".repeat(1200), params)
        assertTrue(bounded in 128..720)
    }
    @Test fun nativeCompletionDistinguishesAnExpandedEosFromARealHardStop() {
        var reached = true; var limit = 0
        val observer = NativeObserver(null, autoContinue = true, outputLimit = { stopped, cap -> reached = stopped; limit = cap }) { _, _, _ -> }
        assertTrue(observer.allowResponseExpansion())
        observer.onOutputLimit(false, 1024)
        assertFalse(reached); assertEquals(1024, limit)
        observer.onOutputLimit(true, 1024)
        assertTrue(reached)
    }
}
