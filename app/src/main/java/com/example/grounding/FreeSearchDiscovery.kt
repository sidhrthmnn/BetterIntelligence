package com.example.grounding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Anonymous, key-free search only. Results discover URLs; snippets are never evidence.
 * No account, paid plan or automatic upgrade. Respect the anonymous IP quota. */
class MwmblDiscovery(private val fetcher: WebFetcher, private val permitted: suspend (String) -> Boolean) : DiscoveryProvider {
    companion object {
        private val requests = Mutex()
        private var nextRequest = 0L
        private var unavailableUntil = 0L
        private fun now() = System.nanoTime() / 1_000_000
        fun candidates(json: String): List<ResearchCandidate> {
            val rows = JSONObject(json).optJSONArray("results") ?: return emptyList()
            return (0 until minOf(rows.length(), 16)).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                val url = row.optString("url").takeIf(GroundingPolicy::publicHttpsUrl) ?: return@mapNotNull null
                ResearchCandidate(HtmlDiscovery.canonicalUrl(url), row.optString("title").take(200), row.optString("content").take(240))
            }.distinctBy { it.url }
        }
    }
    override suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult = requests.withLock {
        if (now() < unavailableUntil) return@withLock DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
        delay((nextRequest - now()).coerceAtLeast(0))
        nextRequest = now() + 1_100
        try {
            val url = "https://api.mwmbl.org/api/v2/search/".toHttpUrl().newBuilder().addQueryParameter("q", query.searchText).build().toString()
            val page = fetcher.fetchPage(url, permitted)
            if (!page.contentType.startsWith("application/json", true)) return@withLock DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
            val found = candidates(page.text)
            DiscoveryResult(found, if (found.isEmpty()) DiscoveryState.EMPTY else DiscoveryState.FOUND)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Back off after quota/denial; never rotate identities, retry payment or log the query.
            unavailableUntil = now() + if (Regex("HTTP (?:429|401|403)").containsMatchIn(e.message.orEmpty())) 900_000 else 60_000
            DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
        }
    }
}

class FreeSearchDiscovery(private val primary: DiscoveryProvider, private val backup: DiscoveryProvider,
    private val primaryCircuit: DiscoveryCircuit? = null, private val backupAllowed: suspend () -> Boolean) : DiscoveryProvider {
    override suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult {
        fun relevant(result: DiscoveryResult): DiscoveryResult {
            val found = result.candidates.filter { query.candidateRelevant(it.title, it.routingHint) &&
                !query.isPrediction(it.title) && !FactualResearch.speculativeCasting(query.question, it.title) &&
                (FactualResearch.capitalPlace(query.question) == null || !Regex("(?i)\\bcapital punishment\\b").containsMatchIn(it.title)) }
            return result.copy(candidates = found, state = if (result.state == DiscoveryState.FOUND && found.isEmpty()) DiscoveryState.EMPTY else result.state)
        }
        val paused = primaryCircuit?.unavailable()
        val first = if (paused != null) DiscoveryResult(emptyList(), paused) else try { withTimeoutOrNull(2_500) { relevant(primary.discover(query, broad)) }
            ?: DiscoveryResult(emptyList(), DiscoveryState.TIMEOUT) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE) }
        if (paused == null) primaryCircuit?.record(first.state)
        if (first.state == DiscoveryState.FOUND || !backupAllowed()) return first
        // A primary challenge ends requests to that service. The independent,
        // explicitly allowed backup does not solve or bypass its challenge.
        val second = relevant(backup.discover(query, broad))
        return if (second.state == DiscoveryState.FOUND) second else first
    }
}
