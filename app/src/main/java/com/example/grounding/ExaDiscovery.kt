package com.example.grounding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Anonymous Exa MCP link discovery only. Publisher text is read independently. */
class ExaDiscovery(private val fetcher: WebFetcher, private val allowed: suspend () -> Boolean = { true },
    private val timeouts: ResearchTimeouts = ResearchTimeouts(), private val jitter: () -> Long = { kotlin.random.Random.nextLong(150) }) : DiscoveryProvider {
    companion object {
        const val endpoint = "https://mcp.exa.ai/mcp"
        const val providerName = "Exa"
        fun searchText(query: ResearchQuery, broad: Boolean = false): String = when {
            broad -> query.topicSearchText
            query.publicTopic != null -> query.searchText
            query.searchText != ResearchQuery(query.question, query.today).searchText -> query.searchText
            else -> query.question
        }.trim().take(400)

        /** SSE data fields form an event, not one independent JSON object per line. */
        private fun envelopes(page: WebDocument): List<String> {
            val mime = page.contentType.substringBefore(';').trim().lowercase()
            if (mime == "application/json" || mime.endsWith("+json")) return listOf(page.text.trimStart('\uFEFF'))
            if (mime != "text/event-stream") return emptyList()
            val messages = mutableListOf<String>(); val data = mutableListOf<String>()
            fun flush() { if (data.isNotEmpty()) messages += data.joinToString("\n"); data.clear() }
            for (line in page.text.trimStart('\uFEFF').lineSequence()) {
                if (line.isEmpty()) flush()
                else if (line.startsWith("data:")) data += line.substring(5).removePrefix(" ")
                if (messages.size >= 64) break
            }
            flush(); return messages
        }
        internal fun responseReceived(text: String): Boolean = envelopes(WebDocument(endpoint, text, "text/event-stream"))
            .any { value -> runCatching { JSONObject(value).let { it.opt("id")?.toString() == "1" && (it.has("result") || it.has("error")) } }.getOrDefault(false) }
        fun candidates(page: WebDocument): DiscoveryResult {
            val reply = envelopes(page).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .firstOrNull { it.opt("id")?.toString() == "1" }
                ?: return DiscoveryResult(emptyList(), DiscoveryState.MALFORMED)
            val result = reply.optJSONObject("result")
            if (reply.has("error") || result == null || result.optBoolean("isError") || result.optJSONObject("_meta")?.optBoolean("ai.exa/rateLimited") == true) {
                val message = (reply.optJSONObject("error")?.optString("message").orEmpty() + " " + result?.optJSONArray("content").toString()).take(8000)
                val state = when {
                    result?.optJSONObject("_meta")?.optBoolean("ai.exa/rateLimited") == true -> DiscoveryState.RATE_LIMITED
                    Regex("(?i)rate.?limit|quota|too many requests").containsMatchIn(message) -> DiscoveryState.RATE_LIMITED
                    Regex("(?i)unauth|forbidden|permission|payment required|api.?key").containsMatchIn(message) -> DiscoveryState.BLOCKED
                    else -> DiscoveryState.UNAVAILABLE
                }
                return DiscoveryResult(emptyList(), state)
            }
            val links = mutableListOf<ResearchCandidate>()
            var recognized = false
            fun add(title: String, rawUrl: String) {
                if (links.size >= 32 || title.isBlank() || !GroundingPolicy.publicHttpsUrl(rawUrl)) return
                val url = runCatching { HtmlDiscovery.canonicalUrl(rawUrl) }.getOrNull() ?: return
                if (url.startsWith("https://exa.ai/library/") || url.startsWith("https://mcp.exa.ai/")) return
                links += ResearchCandidate(url, title.trim().take(200), provider = providerName)
            }
            fun structured(value: Any?, depth: Int = 0) {
                if (depth > 4) return
                when (value) {
                    is JSONArray -> { recognized = true; (0 until minOf(32, value.length())).forEach { structured(value.opt(it), depth + 1) } }
                    is JSONObject -> {
                        val url = value.optString("url").ifBlank { if (value.optString("type") == "resource_link") value.optString("uri") else "" }
                        if (url.isNotBlank()) { recognized = true; add(value.optString("title").ifBlank { value.optString("name") }, url) }
                        for (key in listOf("results", "searchResults", "search_results", "data", "result"))
                            if (value.has(key)) structured(value.opt(key), depth + 1)
                    }
                }
            }
            structured(result.opt("structuredContent"))
            val blocks = result.optJSONArray("content")
            if (blocks != null && blocks.length() == 0) recognized = true
            for (index in 0 until minOf(blocks?.length() ?: 0, 32)) {
                val block = blocks?.optJSONObject(index) ?: continue
                if (block.optString("type") == "resource_link") { structured(block); continue }
                if (block.optString("type") != "text") continue
                val text = block.optString("text").take(1_048_576)
                if (text.isBlank() || Regex("(?i)^\\s*(?:no (?:search )?results?(?: found)?[.!]?|\\[\\])\\s*$").matches(text)) recognized = true
                if (text.trimStart().startsWith("{")) runCatching { structured(JSONObject(text)) }
                if (text.trimStart().startsWith("[")) runCatching { structured(JSONArray(text)) }
                // Fields need not be adjacent; publication metadata can appear between title and URL.
                val titles = Regex("(?im)^Title:\\s*(.+)$").findAll(text).toList()
                for ((i, title) in titles.withIndex()) {
                    val end = titles.getOrNull(i + 1)?.range?.first ?: text.length
                    val section = text.substring(title.range.last + 1, end)
                    val url = Regex("(?im)^URL:\\s*(https://\\S+)").find(section)?.groupValues?.get(1)
                    if (url != null) { recognized = true; add(title.groupValues[1], url.trim()) }
                }
                // Some MCP versions serialize link-only results as Markdown.
                Regex("\\[([^]\\n]{1,200})]\\((https://[^\\s)]+)\\)").findAll(text).take(20).forEach {
                    recognized = true; add(it.groupValues[1], it.groupValues[2])
                }
            }
            val joined = (0 until minOf(blocks?.length() ?: 0, 32)).mapNotNull { i ->
                blocks?.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text")
            }.joinToString("\n")
            val headings = Regex("(?im)^Title:\\s*(.+)$").findAll(joined).toList()
            headings.forEachIndexed { i, heading ->
                val section = joined.substring(heading.range.last + 1, headings.getOrNull(i + 1)?.range?.first ?: joined.length)
                Regex("(?im)^URL:\\s*(https://\\S+)").find(section)?.let { match -> recognized = true; add(heading.groupValues[1], match.groupValues[1].trim()) }
            }
            val selected = links.distinctBy { it.url }.take(12)
            return DiscoveryResult(selected, if (selected.isNotEmpty()) DiscoveryState.FOUND else if (recognized) DiscoveryState.EMPTY else DiscoveryState.MALFORMED)
        }
    }
    override suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult {
        val started = System.nanoTime() / 1_000_000
        val deadline = currentCoroutineContext()[ResearchDeadline] ?: ResearchDeadline(timeouts.overallMs)
        val request = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
            .put("params", JSONObject().put("name", "web_search_exa").put("arguments", JSONObject()
                .put("query", searchText(query, broad)).put("numResults", 8)))
        var last = DiscoveryResult(emptyList(), DiscoveryState.TIMEOUT)
        for (attempt in 0..1) {
            if (!allowed()) return DiscoveryResult(emptyList(), DiscoveryState.BLOCKED)
            if (!deadline.permits()) break
            if (attempt > 0) {
                // At most one retry: exponential backoff (base * 2^0) and jitter.
                val wait = timeouts.backoffMs + jitter().coerceIn(0, 1000)
                if (deadline.remainingMs() < wait + 250) break
                delay(wait)
                if (!allowed() || !deadline.permits()) break
            }
            var retry = false
            var http = ""
            var retryAfter = 0L
            val result = withTimeoutOrNull(minOf(if (attempt == 0) timeouts.discoveryMs else timeouts.retryMs, deadline.remainingMs())) {
                try { candidates(fetcher.postJson(endpoint, request.toString()) { allowed() }).also { http = "2xx" } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    retry = ResearchErrors.retryable(e)
                    if (e is WebRequestException) { http = ResearchErrors.httpCategory(e.statusCode); retryAfter = e.retryAfterMs }
                    val failure = ResearchErrors.provider(e)
                    DiscoveryResult(emptyList(), when (failure) {
                        ResearchFailure.PROVIDER_TIMEOUT -> DiscoveryState.TIMEOUT
                        ResearchFailure.PROVIDER_RATE_LIMITED -> DiscoveryState.RATE_LIMITED
                        ResearchFailure.NETWORK_UNAVAILABLE -> DiscoveryState.NETWORK_UNAVAILABLE
                        else -> if (e is WebRequestException && e.statusCode in setOf(401, 403, 402)) DiscoveryState.BLOCKED else DiscoveryState.UNAVAILABLE
                    })
                }
            } ?: DiscoveryResult(emptyList(), DiscoveryState.TIMEOUT).also { retry = true }
            last = result.copy(attempts = attempt + 1, durationMs = System.nanoTime() / 1_000_000 - started,
                httpCategory = http, retryAfterMs = retryAfter)
            if (!retry || result.candidates.isNotEmpty() || result.state in setOf(DiscoveryState.RATE_LIMITED, DiscoveryState.BLOCKED)) return last
        }
        return last
    }
}
