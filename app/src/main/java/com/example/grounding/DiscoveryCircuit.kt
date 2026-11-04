package com.example.grounding

/** Provider availability only, never queries/history. Empty results are not outages. */
class DiscoveryCircuit(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var failures = 0
    private var until = 0L
    private var state = DiscoveryState.UNAVAILABLE
    @Synchronized fun unavailable(): DiscoveryState? = state.takeIf { now() < until }
    @Synchronized fun record(result: DiscoveryState) = record(DiscoveryResult(emptyList(), result))
    @Synchronized fun record(result: DiscoveryResult) {
        when (result.state) {
            DiscoveryState.FOUND, DiscoveryState.EMPTY -> { failures = 0; until = 0 }
            DiscoveryState.NETWORK_UNAVAILABLE, DiscoveryState.MALFORMED -> Unit // Network/parser failures aren't provider outages.
            else -> {
                state = result.state; failures++
                if (state in setOf(DiscoveryState.BLOCKED, DiscoveryState.RATE_LIMITED) || failures >= 2)
                    until = now() + when (state) {
                        DiscoveryState.RATE_LIMITED -> result.retryAfterMs.coerceIn(60_000, 86_400_000)
                        DiscoveryState.BLOCKED -> 300_000
                        else -> 60_000
                    }
            }
        }
    }
}

