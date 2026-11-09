package com.example.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

data class ClientModelState(
    val phase: String = "IDLE",
    val model: String = "",
    val role: String = "",
    val context: Int = 0,
    val error: String = ""
) {
    val preparing get() = phase in setOf("CHECKING_MEMORY", "LOADING_MODEL")
    val failed get() = phase in setOf("LOW_MEMORY", "PREPARATION_FAILED", "MODEL_REQUIRED")
}

/** A shared, text-free preparation job. Status reads never start inference or
 * load weights. Explicit clients share one job, and never queue behind a chat. */
class ClientModelPreparation(
    private val scope: CoroutineScope,
    private val generating: () -> Boolean,
    private val loading: () -> Boolean,
    private val prepare: suspend ((ClientModelState) -> Unit) -> ClientModelState
) {
    private val running = AtomicBoolean(false)
    private val mutable = MutableStateFlow(ClientModelState())
    val state = mutable.asStateFlow()
    val isPreparing get() = running.get()

    fun request(): ClientModelState {
        if (running.get()) return mutable.value
        if (generating()) return ClientModelState("OTHER_REQUEST")
        if (!running.compareAndSet(false, true)) return mutable.value
        val initial = ClientModelState(if (loading()) "LOADING_MODEL" else "CHECKING_MEMORY")
        mutable.value = initial
        scope.launch {
            try {
                // An explicit preparation request must survive repository startup
                // or a controlled load. Status reads still never initiate work.
                val mayPrepare = withTimeoutOrNull(60_000) {
                    while (loading() && !generating()) delay(50)
                    !generating()
                }
                if (mayPrepare != true) {
                    mutable.value = if (generating()) ClientModelState("OTHER_REQUEST") else
                        ClientModelState("PREPARATION_FAILED", error = "Better Intelligence is still loading. Try preparation again shortly.")
                    return@launch
                }
                mutable.value = prepare { mutable.value = it }
            }
            catch (e: CancellationException) { mutable.value = ClientModelState(); throw e }
            catch (_: Exception) {
                // Never expose native exception strings, paths or client input.
                mutable.value = ClientModelState("PREPARATION_FAILED", error = "The selected model could not load. Open Better Intelligence to choose another downloaded model.")
            } finally { running.set(false) }
        }
        return initial
    }

    fun invalidate() { if (!running.get()) mutable.value = ClientModelState() }
}
