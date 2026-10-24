package com.example.grounding

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

data class ResearchCandidate(val url: String, val title: String)

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

    fun links(page: WebDocument): List<ResearchCandidate> {
        if (!page.contentType.startsWith("text/html", true)) return emptyList()
        val base = runCatching { page.url.toHttpUrl() }.getOrNull() ?: return emptyList()
        if (base.host !in setOf("html.duckduckgo.com", "duckduckgo.com")) return emptyList()
        val doc = Jsoup.parse(page.text, page.url)
        if (blocked(page)) return emptyList()
        return doc.select(".result:not(.result--ad) a.result__a").mapNotNull { link ->
            val href = base.resolve(link.attr("href")) ?: return@mapNotNull null
            val target = if (href.host in setOf("duckduckgo.com", "html.duckduckgo.com") && href.encodedPath == "/l/")
                href.queryParameter("uddg") ?: return@mapNotNull null else href.toString()
            if (!GroundingPolicy.publicHttpsUrl(target)) return@mapNotNull null
            val url = canonicalUrl(target)
            if (url.toHttpUrl().host.endsWith("duckduckgo.com")) return@mapNotNull null
            ResearchCandidate(url, link.text().trim().take(200))
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
