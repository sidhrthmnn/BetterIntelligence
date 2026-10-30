package com.example.grounding

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Exa's documented key-free MCP search, for links only. BI reads publishers
 * itself. No cloud agent/answer, authentication, payment, public proxy or
 * alternate search service. A denial stops discovery and backs off. */
class ExaDiscovery(private val fetcher: WebFetcher, private val allowed: suspend () -> Boolean = { true }) : DiscoveryProvider {
    companion object {
        const val endpoint = "https://mcp.exa.ai/mcp"
        const val providerName = "Exa"
        fun searchText(query: ResearchQuery, broad: Boolean = false): String = when {
            broad -> query.topicSearchText
            query.searchText != ResearchQuery(query.question, query.today).searchText -> query.searchText
            else -> query.question
        }.trim().take(400)

        fun candidates(page: WebDocument): DiscoveryResult {
            val mime = page.contentType.substringBefore(';').trim().lowercase()
            val envelopes = when (mime) {
                "application/json" -> listOf(page.text)
                "text/event-stream" -> page.text.lineSequence().filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }.toList()
                else -> return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
            }
            val reply = envelopes.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .firstOrNull { it.optInt("id", -1) == 1 } ?: return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
            val result = reply.optJSONObject("result") ?: return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
            if (result.optBoolean("isError")) return DiscoveryResult(emptyList(), if (
                Regex("(?i)rate.?limit|quota|too many requests|payment required").containsMatchIn(result.optJSONArray("content")?.toString().orEmpty()))
                DiscoveryState.BLOCKED else DiscoveryState.UNAVAILABLE)
            val blocks = result.optJSONArray("content") ?: return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
            val links = (0 until minOf(blocks.length(), 16)).flatMap { index ->
                val block = blocks.optJSONObject(index)
                if (block?.optString("type") != "text") emptyList() else block.optString("text")
                    .split(Regex("(?m)^---\\s*$")).take(20).mapNotNull { section ->
                        val lines = section.trim().lineSequence().take(2).toList()
                        val title = lines.firstOrNull()?.takeIf { it.startsWith("Title:") }?.removePrefix("Title:")?.trim()
                        val url = lines.getOrNull(1)?.takeIf { it.startsWith("URL:") }?.removePrefix("URL:")?.trim()
                        if (title.isNullOrBlank() || url == null || !GroundingPolicy.publicHttpsUrl(url) ||
                            url.startsWith("https://exa.ai/library/") || url.startsWith(endpoint)) null else
                            ResearchCandidate(HtmlDiscovery.canonicalUrl(url), title.take(200), provider = providerName)
                    }
            }.distinctBy { it.url }.take(12)
            // Highlights, summaries and dates in search results are NOT evidence.
            return DiscoveryResult(links, if (links.isEmpty()) DiscoveryState.EMPTY else DiscoveryState.FOUND)
        }
    }
    override suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult {
        if (!allowed()) return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
        val request = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
            .put("params", JSONObject().put("name", "web_search_exa").put("arguments", JSONObject()
                .put("query", searchText(query, broad)).put("numResults", 8)))
        return try { candidates(fetcher.postJson(endpoint, request.toString()) { allowed() }) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { DiscoveryResult(emptyList(), if (Regex("HTTP (401|403|429)").containsMatchIn(e.message.orEmpty()))
            DiscoveryState.BLOCKED else DiscoveryState.UNAVAILABLE) }
    }
}
