package com.example

import com.example.grounding.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup

/** Parses historical synthetic HTML fixtures only; never packaged or used for network access. */
object HtmlFixtureParser {
    fun blocked(page: WebDocument): Boolean = Jsoup.parse(page.text, page.url)
        .selectFirst("#challenge-form, .anomaly-modal, form[action*=anomaly]") != null
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
