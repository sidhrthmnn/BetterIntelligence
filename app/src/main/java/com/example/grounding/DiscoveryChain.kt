package com.example.grounding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull

/** Installation-supplied permitted providers, never arbitrary endpoints derived
 * from page text. No key, browser scraping or public proxy is bundled here. */
data class ConfiguredDiscovery(val name: String, val provider: DiscoveryProvider) {
    init { require(name.matches(Regex("[A-Za-z][A-Za-z0-9 _-]{0,39}"))) }
}

class DiscoveryChain(private val providers: List<ConfiguredDiscovery>, private val timeouts: ResearchTimeouts,
    private val circuits: MutableMap<String, DiscoveryCircuit> = mutableMapOf(),
    private val allowed: suspend () -> Boolean = { true }) : DiscoveryProvider {
    val used = linkedSetOf<String>()
    private val cache = mutableMapOf<Pair<String, String>, DiscoveryResult>()
    private var calls = 0
    override suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult {
        val deadline = currentCoroutineContext()[ResearchDeadline] ?: ResearchDeadline(timeouts.overallMs)
        val found = mutableListOf<ResearchCandidate>()
        var last = DiscoveryResult(emptyList(), DiscoveryState.EMPTY)
        var attempts = 0
        for ((index, configured) in providers.distinctBy { it.name }.take(3).withIndex()) {
            if (!allowed()) return DiscoveryResult(emptyList(), DiscoveryState.BLOCKED)
            if (!deadline.permits(500) || calls >= 6) break
            val circuit = synchronized(circuits) { circuits.getOrPut(configured.name) { DiscoveryCircuit() } }
            val key = configured.name to ExaDiscovery.searchText(query, broad).lowercase(java.util.Locale.ROOT)
            val result = cache[key] ?: run {
                circuit.unavailable()?.let { return@run DiscoveryResult(emptyList(), it, attempts = 0) }
                calls++; used += configured.name
                // Leave a share of the same deadline for another configured
                // provider and publisher reads; a hung primary can't consume it.
                val budget = if (index < providers.lastIndex) minOf(timeouts.discoveryMs, deadline.remainingMs() / 2)
                    else minOf(timeouts.discoveryMs + timeouts.retryMs + timeouts.backoffMs + 1000, deadline.remainingMs())
                val response = withTimeoutOrNull(budget.coerceAtLeast(1)) {
                    try { configured.provider.discover(query, broad) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE) }
                } ?: DiscoveryResult(emptyList(), DiscoveryState.TIMEOUT)
                circuit.record(response)
                response.also { cache[key] = it }
            }
            last = result; attempts += result.attempts
            found += result.candidates.filter { GroundingPolicy.publicHttpsUrl(it.url) }.map {
                it.copy(provider = it.provider.ifBlank { configured.name })
            }
            // Retain useful partial results even if a provider reported a
            // trailing failure. Empty/failed primaries do not suppress fallback.
            if (found.isNotEmpty()) break
            // One provider's DNS/network failure does not establish that another
            // configured host is unreachable. The shared deadline bounds both.
        }
        return last.copy(candidates = found.distinctBy { HtmlDiscovery.canonicalUrl(it.url) }.take(12),
            state = if (found.isNotEmpty()) DiscoveryState.FOUND else last.state, attempts = attempts.coerceAtLeast(1))
    }
}
