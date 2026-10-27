package com.example

import com.example.engine.ClientModelPreparation
import com.example.engine.ClientModelState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ClientModelPreparationTest {
    @Test fun concurrentClientsShareOneLoadAndKeepAdmittedFallbackContext() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var loads = 0
        val controller = ClientModelPreparation(this, { false }, { false }) { progress ->
            loads++; progress(ClientModelState("LOADING_MODEL", "Small model", "SECONDARY_FALLBACK"))
            entered.complete(Unit); finish.await()
            ClientModelState("READY", "Small model", "SECONDARY_FALLBACK", 512)
        }
        assertEquals("CHECKING_MEMORY", controller.request().phase)
        entered.await()
        repeat(8) { assertEquals("LOADING_MODEL", controller.request().phase) }
        assertEquals(1, loads); assertTrue(controller.isPreparing)
        finish.complete(Unit); yield()
        assertEquals(512, controller.state.value.context)
        assertEquals("SECONDARY_FALLBACK", controller.state.value.role)
        assertFalse(controller.isPreparing)
        controller.invalidate(); assertEquals("IDLE", controller.state.value.phase)
    }

    @Test fun anotherGenerationAndStartupNeverStartADuplicateModel() = runBlocking {
        var generating = true; var loading = false; var calls = 0
        val controller = ClientModelPreparation(this, { generating }, { loading }) {
            calls++; ClientModelState("READY", "Model", context = 1024)
        }
        assertEquals("OTHER_REQUEST", controller.request().phase); assertEquals(0, calls)
        generating = false; loading = true
        assertEquals("LOADING_MODEL", controller.request().phase); assertEquals(0, calls)
        loading = false; controller.request(); yield()
        assertEquals(1, calls); assertEquals("READY", controller.state.value.phase)
    }

    @Test fun lowMemoryAndLoadFailureCannotBeReportedReadyAndExplicitRetryCanRecover() = runBlocking {
        var attempt = 0
        val controller = ClientModelPreparation(this, { false }, { false }) {
            when (++attempt) {
                1 -> ClientModelState("LOW_MEMORY", error = "Free memory")
                2 -> error("Private native path or exception must not escape")
                else -> ClientModelState("READY", "Small", context = 512)
            }
        }
        controller.request(); yield()
        assertTrue(controller.state.value.failed); assertEquals("LOW_MEMORY", controller.state.value.phase)
        controller.request(); yield()
        assertEquals("PREPARATION_FAILED", controller.state.value.phase)
        assertFalse(controller.state.value.error.contains("Private"))
        assertFalse(controller.isPreparing)
        controller.request(); yield()
        assertEquals("READY", controller.state.value.phase)
    }
}
