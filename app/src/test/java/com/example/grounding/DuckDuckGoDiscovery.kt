package com.example.grounding

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Official key-free Instant Answer API only. Stable topic summaries retain
 * explicit API provenance; they never masquerade as read pages or live facts. */
class DuckDuckGoDiscovery(private val fetcher: WebFetcher,
    private val allowed: suspend () -> Boolean = { true }) : DiscoveryProvider {
    companion object {
        const val providerName = "DuckDuckGo"
        const val endpoint = "https://api.duckduckgo.com/"
        fun summaryAllowed(question: String): Boolean = AnswerRoutingPolicy.decide(question).let {
            !it.requiresLive && !it.requiresVerification && !it.privacyBlocked && !AnswerRoutingPolicy.localOnly(question)
        }
        fun searchText(query: ResearchQuery, broad: Boolean = false): String {
            val topic = query.topicSearchText.ifBlank { query.question }
            val subject = if (broad) ResearchPlanner.subjects(query.question).maxByOrNull { it.length } else null
            return (subject ?: topic).trim().take(400)
        }
        fun searchUrl(query: ResearchQuery, broad: Boolean = false) = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("q", searchText(query, broad)).addQueryParameter("format", "json")
            .addQueryParameter("no_html", "1").addQueryParameter("no_redirect", "1")
            .addQueryParameter("skip_disambig", "1").addQueryParameter("t", "betterintelligence").build().toString()
        fun resultPage(topic: String) = "https://duckduckgo.com/".toHttpUrl().newBuilder()
            .addQueryParameter("q", topic.take(400)).build().toString()

        fun candidates(page: WebDocument, question: String = ""): DiscoveryResult {
            val mime = page.contentType.substringBefore(';').trim().lowercase()
            if (mime !in setOf("application/json", "application/x-javascript", "application/javascript", "text/javascript"))
                return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
            val json = runCatching { JSONObject(page.text) }.getOrNull()
                ?: return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
            fun field(row: JSONObject, key: String) = (row.opt(key) as? String).orEmpty().trim()
            val heading = field(json, "Heading").take(200)
            val found = mutableListOf<ResearchCandidate>()
            fun add(raw: String, label: String, summary: String = "", sourceName: String = "") {
                val value = if (raw.startsWith("http://", true)) "https://" + raw.drop(7) else raw
                if (!GroundingPolicy.publicHttpsUrl(value)) return
                val url = value.toHttpUrl()
                // RelatedTopics often points back to DuckDuckGo search pages.
                // Never scrape them, follow !bang redirects or infer publisher URLs.
                if (url.host == "duckduckgo.com" || url.host.endsWith(".duckduckgo.com")) return
                val title = label.ifBlank { heading }.ifBlank { url.host }.take(200)
                val canonical = HtmlDiscovery.canonicalUrl(value)
                val text = EvidencePacket.completeExcerpt(org.jsoup.Jsoup.parseBodyFragment(summary).text(), 1000)
                val api = text.takeIf { it.length >= 80 }?.let { EvidenceSource(0,
                    listOf(title, sourceName.take(80)).filter(String::isNotBlank).distinct().joinToString(" · ").take(200),
                    canonical, it, System.currentTimeMillis(), kind = "INSTANT_ANSWER", dateKind = "API_SUMMARY", discoveryProvider = providerName) }
                found += ResearchCandidate(canonical, title,
                    url.pathSegments.joinToString(" ") { it.replace('-', ' ').replace('_', ' ') }.take(240), providerName, api)
            }
            val liveNews = NewsIntent.isNewsSearch(question) && ResearchQuery(question).relative
            // A country/subject encyclopedia abstract does not establish today's news.
            if (!liveNews && field(json, "Type") != "D") {
                add(field(json, "AbstractURL"), heading, field(json, "AbstractText"), field(json, "AbstractSource"))
                add(field(json, "DefinitionURL"), heading, field(json, "Definition"), field(json, "DefinitionSource"))
            }
            var examined = 0
            fun rows(key: String, parent: JSONObject, depth: Int = 0) {
                if (depth > 2) return
                val array = parent.optJSONArray(key) ?: return
                for (i in 0 until minOf(array.length(), 32)) {
                    if (examined++ >= 64) return
                    val row = array.optJSONObject(i) ?: continue
                    val text = field(row, "Text")
                    add(field(row, "FirstURL"), listOf(text, heading).filter { it.isNotBlank() }.distinct().joinToString(" · "))
                    rows("Topics", row, depth + 1)
                }
            }
            rows("Results", json); rows("RelatedTopics", json)
            val links = found.distinctBy { it.url }.take(12)
            return DiscoveryResult(links, if (links.isEmpty()) DiscoveryState.EMPTY else DiscoveryState.FOUND)
        }
    }
    override suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult {
        if (!allowed()) return DiscoveryResult(emptyList(), DiscoveryState.UNAVAILABLE)
        return try {
            // This documented API call is not an HTML crawler. Publisher robots
            // rules still apply to every subsequent page/redirect read.
            val result = candidates(fetcher.fetch(searchUrl(query, broad)), query.question)
            val relevant = result.candidates.filter { query.candidateRelevant(it.title, it.routingHint) &&
                !query.isPrediction(it.title) && !FactualResearch.speculativeCasting(query.question, it.title) &&
                (FactualResearch.capitalPlace(query.question) == null ||
                    !Regex("(?i)\\bcapital punishment\\b").containsMatchIn(it.title)) }
            result.copy(candidates = relevant, state = if (result.state == DiscoveryState.FOUND && relevant.isEmpty()) DiscoveryState.EMPTY else result.state)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { DiscoveryResult(emptyList(), if (Regex("HTTP (?:401|403|429)").containsMatchIn(e.message.orEmpty()))
            DiscoveryState.BLOCKED else DiscoveryState.UNAVAILABLE) }
    }
}
