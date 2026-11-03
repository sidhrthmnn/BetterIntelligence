package com.example

import com.example.grounding.*
import com.example.rustcore.ModelFailureKind
import com.example.rustcore.RustModelPolicy
import com.example.rustcore.RustIntelligence
import com.example.rustcore.IntelligenceMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class RustMigrationRegressionTest {
    @Test fun politeLocalTasksDoNotSearchBecauseTheirSubjectMentionsCurrentEvents() {
        for (q in listOf("Could you write a short story about today?", "Please write a poem about the latest news.",
            "Can you improve this sentence: what is the current price?", "Would you translate this text: weather today in London")) {
            assertEquals(q, AnswerPath.LOCAL, AnswerRoutingPolicy.decide(q).path)
        }
        assertEquals(AnswerPath.WEB_GROUNDED, AnswerRoutingPolicy.decide("Could you search for the latest Android release?").path)
        assertEquals(AnswerPath.WEB_GROUNDED, AnswerRoutingPolicy.decide("Please write a report using reliable sources about the latest Android release.").path)
    }
    @Test fun cancellationBusyAndContextErrorsAreDifferentFromResourceAndFileFailures() {
        assertEquals(ModelFailureKind.CANCELLED, ModelFailureKind.classify(kotlinx.coroutines.CancellationException()))
        assertEquals(ModelFailureKind.BUSY, ModelFailureKind.classify(IllegalStateException("Stop generation before switching models")))
        assertEquals(ModelFailureKind.CONTEXT_OVERFLOW, ModelFailureKind.classify(IllegalArgumentException("Prompt exceeds context")))
        assertEquals(ModelFailureKind.OUT_OF_MEMORY, ModelFailureKind.classify(IllegalStateException("Not enough memory")))
        assertEquals(ModelFailureKind.MODEL_MISSING, ModelFailureKind.classify(java.io.FileNotFoundException()))
        assertEquals(ModelFailureKind.DNS, ModelFailureKind.classify(java.net.UnknownHostException()))
        assertEquals(ModelFailureKind.TIMEOUT, ModelFailureKind.classify(java.net.SocketTimeoutException()))
        assertTrue(ModelFailureKind.classify(java.io.IOException()).transient)
        assertFalse(ModelFailureKind.classify(IllegalStateException("Invalid GGUF magic")).transient)
        assertTrue(RustModelPolicy.isBackendFailure("Vulkan GPU initialization failed"))
        assertTrue(RustModelPolicy.isBackendFailure("NPU runtime unavailable"))
        assertFalse(RustModelPolicy.isBackendFailure("Malformed input response"))
    }
    @Test fun unavailableNativeLibraryLeavesLocalRoutingAndContextFunctional() {
        // Robolectric runs on the host, which intentionally has no Android .so.
        assertEquals(IntelligenceMode.LEGACY, RustIntelligence.mode)
        assertNull(RustIntelligence.call("info"))
        assertEquals(AnswerPath.LOCAL, AnswerRoutingPolicy.decide("Explain recursion").path)
        assertEquals(AnswerPath.LOCAL, AnswerRoutingPolicy.decide("Do not search. What is the latest Android release?").path)
        val summary = "${com.example.engine.ConversationContext.summaryHeader}\nUser said: \"My project is Orion.\"\nUser said: \"Use green now.\""
        assertTrue(com.example.engine.ConversationContext.summaryWithinBudget(summary, 512, "What is my project?").contains("Orion"))
    }
}
