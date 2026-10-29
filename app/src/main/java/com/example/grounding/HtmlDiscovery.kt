package com.example.grounding

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

data class ResearchCandidate(val url: String, val title: String, val routingHint: String = "")
enum class DiscoveryState { FOUND, EMPTY, BLOCKED, UNAVAILABLE, TIMEOUT }
data class DiscoveryResult(val candidates: List<ResearchCandidate>, val state: DiscoveryState)
fun interface DiscoveryProvider {
    suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult
}

/** Only this adapter knows the public discovery site's URL/markup. */
class HtmlSearchDiscovery(
    private val fetcher: WebFetcher,
    private val permitted: suspend (String) -> Boolean,
    private val enableFallback: Boolean = true
) : DiscoveryProvider {
    override suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult {
        val primary = try { fetchDiscoveryPage(HtmlDiscovery.searchUrl(query, broad)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE) }
        if (primary.state == DiscoveryState.FOUND || primary.state == DiscoveryState.BLOCKED || !enableFallback) return primary
        val lite = try { fetchDiscoveryPage(HtmlDiscovery.liteSearchUrl(query, broad)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        if (lite != null && (lite.state == DiscoveryState.FOUND || lite.state == DiscoveryState.BLOCKED)) return lite
        return primary
    }

    private suspend fun fetchDiscoveryPage(url: String): DiscoveryResult {
        val page = fetcher.fetchPage(url, permitted)
        if (HtmlDiscovery.blocked(page)) return DiscoveryResult(emptyList(), DiscoveryState.BLOCKED)
        val links = HtmlDiscovery.links(page)
        val recognized = org.jsoup.Jsoup.parse(page.text).selectFirst(".result, .no-results, .result-link, #links, .mw-search-results") != null
        return DiscoveryResult(links, when {
            links.isNotEmpty() -> DiscoveryState.FOUND
            recognized -> DiscoveryState.EMPTY
            else -> DiscoveryState.UNAVAILABLE
        })
    }
}

/** Our parser for the public, non-JavaScript search page. No SDK, RSS or search API.
 * A challenge or markup change yields no links, never fabricated evidence.
 */
object HtmlDiscovery {
    fun blocked(page: WebDocument): Boolean = Jsoup.parse(page.text, page.url)
        .selectFirst("#challenge-form, .anomaly-modal, form[action*=anomaly]") != null
    fun searchUrl(query: ResearchQuery, broad: Boolean = false): String =
        "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.searchText)
            .apply { if (!broad) query.freshnessFilter?.let { addQueryParameter("df", it) } }
            .build().toString()

    fun liteSearchUrl(query: ResearchQuery, broad: Boolean = false): String =
        "https://lite.duckduckgo.com/lite/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.searchText)
            .apply { if (!broad) query.freshnessFilter?.let { addQueryParameter("df", it) } }
            .build().toString()

    fun links(page: WebDocument): List<ResearchCandidate> {
        if (!page.contentType.startsWith("text/html", true)) return emptyList()
        val base = runCatching { page.url.toHttpUrl() }.getOrNull() ?: return emptyList()
        val allowedHosts = setOf("html.duckduckgo.com", "duckduckgo.com", "lite.duckduckgo.com", "en.wikipedia.org")
        if (base.host !in allowedHosts) return emptyList()
        val doc = Jsoup.parse(page.text, page.url)
        if (blocked(page)) return emptyList()
        // Known HTML and lite layouts only: never harvest arbitrary page links
        // after an unknown markup change (navigation/ads aren't search results).
        return doc.select(".result:not(.result--ad) a.result__a, a.result-link, .mw-search-result-heading a").filter {
            it.parents().none { parent -> parent.hasClass("result--ad") || parent.hasClass("sponsored") } &&
                !it.attr("rel").contains("sponsored", true)
        }.mapNotNull { link ->
            val href = base.resolve(link.attr("href")) ?: return@mapNotNull null
            val target = if (href.host in setOf("duckduckgo.com", "html.duckduckgo.com", "lite.duckduckgo.com") && href.encodedPath == "/l/")
                href.queryParameter("uddg") ?: return@mapNotNull null else href.toString()
            if (!GroundingPolicy.publicHttpsUrl(target)) return@mapNotNull null
            val url = canonicalUrl(target)
            val parsedTarget = url.toHttpUrl()
            val host = parsedTarget.host
            if (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) return@mapNotNull null
            val containerSnippet = link.parents().firstOrNull { it.hasClass("result") || it.hasClass("mw-search-result") }
                ?.selectFirst(".result__snippet, .searchresult")?.text().orEmpty()
            val pathWords = parsedTarget.pathSegments.joinToString(" ") { it.replace('-', ' ').replace('_', ' ') }
            val hint = "$containerSnippet $pathWords".trim().take(240)
            ResearchCandidate(url, link.text().trim().take(200), hint)
        }.distinctBy { it.url }.take(16)
    }

    /** Only remove well-known tracking keys. Article IDs and pagination remain intact. */
    fun canonicalUrl(value: String): String {
        val url = value.toHttpUrl()
        return url.newBuilder().fragment(null).apply {
            url.queryParameterNames.filter { it.startsWith("utm_", true) || it.lowercase() in setOf("fbclid", "gclid", "msclkid") }
                .forEach { removeAllQueryParameters(it) }
        }.build().toString()
    }
}
