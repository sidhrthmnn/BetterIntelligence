package com.example

import com.example.grounding.*
import com.example.grounding.baseline27.PublicWebResearch as PreviousResearch
import com.example.grounding.baseline27.SearchHttp as PreviousHttp
import com.example.grounding.baseline27.ExaDiscovery as PreviousExa
import com.example.grounding.baseline27.EvidenceAnswer as PreviousAnswer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Opt-in live network: fixed public corpus, no native LLM quality claim.
 * BI_LIVE_RESEARCH=true; old/new order alternates between repeated trials. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class LiveWebReliabilityBenchmarkTest {
    private data class Case(val category: String, val question: String, val negative: Boolean = false)
    private val corpus = listOf(
        Case("news", "Latest UK news today"),
        Case("weather", "Weather in London today"),
        Case("transport", "What's the current Jubilee line status?"),
        Case("casting", "Who is acting as Doom in Avengers Doomsday?"),
        Case("film_review", "How's Maharaja Tamil movie?"),
        Case("product", "Google Pixel 9 RAM specifications"),
        Case("documentation", "How does Android Vulkan graphics initialization work?"),
        Case("complex", "Compare LiteRT-LM and llama.cpp Android compatibility and memory requirements"),
        Case("factual", "What is the capital of France?"),
        Case("previous_failure", "How's batha tamil movie?"),
        Case("no_results", "Find documentation for zxqvbi-nonexistent-73928461", true),
        Case("publisher_blocked", "Read https://httpstat.us/403", true)
    )
    private class Trace(private val transport: WebFetcher, private val previous: Boolean,
        private val discovery: MutableList<DiscoveryResult> = mutableListOf()) : WebFetcher {
        override fun session(maxRequests: Int): WebFetcher = Trace(transport.session(maxRequests), previous, discovery)
        override suspend fun fetch(url: String) = transport.fetch(url)
        override suspend fun fetchPage(url: String, permitted: suspend (String) -> Boolean) = transport.fetchPage(url, permitted)
        override suspend fun postJson(url: String, json: String, permitted: suspend (String) -> Boolean): WebDocument =
            transport.postJson(url, json, permitted).also { discovery += if (previous) PreviousExa.candidates(it) else ExaDiscovery.candidates(it) }
        fun found() = discovery.any { it.candidates.isNotEmpty() }
        fun count() = discovery.sumOf { it.candidates.size }
        fun rateLimited() = discovery.any { it.state == DiscoveryState.RATE_LIMITED || it.state == DiscoveryState.BLOCKED }
    }
    @Test fun repeatedPreviousAndRevisedPublicCorpus() = runBlocking {
        assumeTrue(System.getenv("BI_LIVE_RESEARCH") == "true")
        val rows = JSONArray()
        val output = File(System.getenv("BI_RELIABILITY_REPORT") ?: "build/reports/web-reliability-live.json")
        output.parentFile.mkdirs()
        for (trial in 1..2) for (case in corpus) for (previous in if (trial == 1) listOf(true, false) else listOf(false, true)) {
            val trace = Trace(if (previous) PreviousHttp() else SearchHttp(), previous)
            val reader: SearchProvider = if (previous) PreviousResearch(trace) else PublicWebResearch(trace)
            val start = System.nanoTime()
            val outcome = reader.research(case.question) {}
            val elapsed = (System.nanoTime() - start) / 1_000_000
            // This measures deterministic synthesis/verification, not an LLM run.
            val checked = if (outcome.sources.isEmpty()) null else if (previous) PreviousAnswer.resolve("I cannot answer from memory.", outcome.sources, case.question) else EvidenceAnswer.resolve("I cannot answer from memory.", outcome.sources, case.question)
            val usable = checked != null && checked.support != AnswerSupport.INSUFFICIENT
            val cited = checked?.text?.let { GroundingPolicy.validateCitations(it, outcome.sources).second } == true
            val route = AnswerRoutingPolicy.decide(case.question)
            // v27 recovery labelled all failed live/verification/topic tasks this way.
            val legacyOutage = outcome.sources.isEmpty() && (route.requiresLive || route.requiresVerification || PublicTopics.parse(route.question) != null)
            val failureStatus = if (previous && legacyOutage) "WEB_UNAVAILABLE" else AnswerRoutingPolicy.recover(route, outcome.failure).status
            val falseOutage = previous && legacyOutage && (trace.found() || outcome.failure in setOf(ResearchFailure.NO_SEARCH_RESULTS, ResearchFailure.INSUFFICIENT_EVIDENCE, ResearchFailure.PUBLISHER_BLOCKED, ResearchFailure.PUBLISHER_TIMEOUT, ResearchFailure.PUBLISHER_UNAVAILABLE))
            rows.put(JSONObject().put("implementation", if (previous) "v27" else "revised").put("trial", trial)
                .put("category", case.category).put("question", case.question).put("negativeControl", case.negative)
                .put("discoverySuccess", trace.found()).put("discoveredResults", trace.count())
                .put("readSources", outcome.sources.size).put("readSourceUrls", JSONArray(outcome.sources.map { it.url }))
                .put("evidenceAnswerSuccess", usable).put("citationCoverage", cited).put("elapsedMs", elapsed)
                .put("retryCount", outcome.metrics.retries).put("rateLimited", trace.rateLimited() || outcome.failure == ResearchFailure.PROVIDER_RATE_LIMITED)
                .put("failure", outcome.failure?.name.orEmpty()).put("reportedStatus", failureStatus)
                .put("falseOutage", falseOutage).put("provider", outcome.metrics.provider)
                .put("clarification", outcome.clarification.isNotBlank()))
            output.writeText(JSONObject().put("scope", "Desktop live network: discovery, accepted evidence and deterministic synthesis/verification; not native LLM quality or phone latency. Negative controls excluded from healthy-discovery denominator. Two interleaved trials per implementation. Bound by publisher/robots access. Legacy final outage mapping reconstructed from v27 recovery rules; transport/parser/reader/research/fallback summaries are actual v27 snapshots.")
                .put("baseline", "22a2f72").put("rows", rows).toString(2))
            println("RELIABILITY ${if(previous) "v27" else "revised"} $trial ${case.category} discovery=${trace.found()} sources=${outcome.sources.size} answer=$usable elapsed=$elapsed failure=${outcome.failure}")
            assertTrue("Research exceeded hard cap: $elapsed", elapsed <= 65_000)
        }
        assertEquals(48, rows.length())
    }
}
