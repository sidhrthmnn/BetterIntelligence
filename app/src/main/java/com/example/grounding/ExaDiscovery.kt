package com.example.grounding

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Official anonymous search MCP, retrieval only. No agent, cloud answer or paid key. */
class ExaDiscovery(private val fetcher: WebFetcher, private val permitted: suspend (String) -> Boolean) : DiscoveryProvider {
    companion object {
        const val endpoint = "https://mcp.exa.ai/mcp"
        fun candidates(document: WebDocument): DiscoveryResult {
            val envelopes = when {
                document.contentType.startsWith("application/json", true) -> listOf(document.text)
                document.contentType.startsWith("text/event-stream", true) -> document.text.lineSequence()
                    .filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.toList()
                else -> return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
            }
            val reply = envelopes.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .firstOrNull { it.optInt("id", -1) == 1 } ?: return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
            val result = reply.optJSONObject("result") ?: return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
            if (result.optBoolean("isError")) return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
            val blocks = result.optJSONArray("content") ?: return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
            val links = (0 until blocks.length()).flatMap { index ->
                val item = blocks.optJSONObject(index)
                if (item?.optString("type") != "text") emptyList() else item.optString("text")
                    .split(Regex("(?m)^---\\s*$")).mapNotNull { section ->
                        val lines = section.trim().lineSequence().take(3).toList()
                        val title = lines.firstOrNull()?.takeIf { it.startsWith("Title:") }?.removePrefix("Title:")?.trim()
                        val url = lines.getOrNull(1)?.takeIf { it.startsWith("URL:") }?.removePrefix("URL:")?.trim()
                        if (title.isNullOrBlank() || url == null || !GroundingPolicy.publicHttpsUrl(url)) null else
                            ResearchCandidate(HtmlDiscovery.canonicalUrl(url), title.take(200), provider = "Exa")
                    }
            }.distinctBy { it.url }.take(12)
            // Highlights, summaries and search dates are deliberately not page evidence.
            return DiscoveryResult(links, if (links.isEmpty()) DiscoveryState.EMPTY else DiscoveryState.FOUND)
        }
    }
    override suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult = try {
        val objective = "Find relevant factual publisher pages for this request. Prefer original sources and the request's language. " +
            "Requested facets: ${query.searchText}. " +
            if (query.relative) "Prioritise recent reporting as of ${query.today}; exclude stale news and speculation." else "Exclude unrelated topics and speculation."
        val request = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
            .put("params", JSONObject().put("name", "web_search_exa").put("arguments", JSONObject()
                .put("query", query.question.take(400)).put("objective", objective.take(1600)).put("numResults", 6)))
        candidates(fetcher.postJson(endpoint, request.toString(), permitted))
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { DiscoveryResult(emptyList(), if (Regex("HTTP (?:401|403|429)").containsMatchIn(e.message.orEmpty()))
        DiscoveryState.BLOCKED else DiscoveryState.UNAVAILABLE) }
}
