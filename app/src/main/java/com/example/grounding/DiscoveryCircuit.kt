package com.example.grounding

/** Provider availability only, never queries/history. Empty results are not outages. */
class DiscoveryCircuit(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var failures = 0
    private var until = 0L
    private var state = DiscoveryState.UNAVAILABLE
    @Synchronized fun unavailable(): DiscoveryState? = state.takeIf { now() < until }
    @Synchronized fun record(result: DiscoveryState) {
        when (result) {
            DiscoveryState.FOUND, DiscoveryState.EMPTY -> { failures = 0; until = 0 }
            else -> {
                state = result; failures++
                if (result == DiscoveryState.BLOCKED || failures >= 2)
                    until = now() + if (result == DiscoveryState.BLOCKED) 300_000 else 60_000
            }
        }
    }
}

