package com.example.grounding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.InetAddress
import java.net.UnknownHostException

/** Checked at DNS resolution time, including every manually followed redirect. */
object PublicWebDns : Dns {
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        return when (b.size) {
            4 -> b[0] !in setOf(0, 10, 127) && b[0] < 224 &&
                !(b[0] == 100 && b[1] in 64..127) && !(b[0] == 169 && b[1] == 254) &&
                !(b[0] == 172 && b[1] in 16..31) && !(b[0] == 192 && b[1] in setOf(0, 168)) &&
                !(b[0] == 198 && b[1] in 18..19)
            16 -> b[0] and 0xfe != 0xfc && !(b[0] == 0x20 && b[1] == 1 && b[2] == 0x0d && b[3] == 0xb8)
            else -> false
        }
    }
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw UnknownHostException("Public website required")
        return addresses
    }
}

data class WebDocument(val url: String, val text: String, val contentType: String)
fun interface WebFetcher { suspend fun fetch(url: String): WebDocument }

/** A bounded reader, not an autonomous browser: no scripts, cookies, forms or sign-in. */
class PublicWebResearch(private val fetcher: WebFetcher = SearchHttp()) : SearchProvider {
    companion object {
        fun candidates(rss: String): List<String> = Jsoup.parse(rss, "", Parser.xmlParser())
            .select("channel > item > link").map { it.text().trim() }
            .filter(GroundingPolicy::publicHttpsUrl).distinct().take(6)

        fun article(page: WebDocument, id: Int, question: String): EvidenceSource? {
            if (page.contentType.substringBefore(';').lowercase() !in setOf("text/html", "application/xhtml+xml", "text/plain")) return null
            val doc = Jsoup.parse(page.text, page.url)
            // Boilerplate, embedded code and hidden UI are never prompt evidence.
            doc.select("script, style, noscript, iframe, form, nav, footer, header, aside, [hidden], [aria-hidden=true]").remove()
            val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
            val terms = Regex("[\\p{L}\\p{N}]{3,}").findAll(question.lowercase()).map { it.value }.toSet()
            val paragraphs = root.select("p, h1, h2, h3, li").map { it.text().trim() }.filter { it.length >= 35 }
                .distinct().take(300)
            val ranked = paragraphs.withIndex().sortedWith(compareByDescending<IndexedValue<String>> {
                val lower = it.value.lowercase(); terms.count(lower::contains)
            }.thenBy { it.index }).take(6).sortedBy { it.index }.map { it.value }
            val text = (if (ranked.isEmpty()) root.text() else ranked.joinToString("\n")).trim().take(1800)
            if (text.length < 80 || text.contains("verify you are human", true) || text.contains("enable javascript and cookies", true)) return null
            return EvidenceSource(id, doc.title().ifBlank { page.url.toHttpUrl().host }.take(200),
                page.url, text, System.currentTimeMillis(), kind = "WEB_PAGE")
        }
    }

    override suspend fun search(question: String): List<EvidenceSource> = coroutineScope {
        val supplied = Regex("https?://[^\\s<>]+").findAll(question)
            .map { it.value.trimEnd('.', ',', ')', ']', '?') }.toList()
        // Never turn a rejected private/local URL into a public search query.
        require(supplied.all(GroundingPolicy::publicHttpsUrl)) { "Use a public HTTPS link" }
        val explicit = supplied.distinct().take(3)
        val urls = if (explicit.isNotEmpty()) explicit else {
            // Public search feed, no account/key or proxy server. This interface
            // may be rate limited or change; failure must remain visibly ungrounded.
            val search = "https://www.bing.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("format", "rss").addQueryParameter("q", question.take(500)).build()
            candidates(fetcher.fetch(search.toString()).text)
                .distinctBy { it.toHttpUrl().host.removePrefix("www.") }.take(3)
        }
        urls.map { url -> async {
            try { article(fetcher.fetch(url), 0, question) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null } // A blocked/unreadable page is never a citation.
        } }.awaitAll().filterNotNull().distinctBy { it.url }.mapIndexed { index, source -> source.copy(id = index + 1) }
    }
}
