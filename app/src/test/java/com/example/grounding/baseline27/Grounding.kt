// Test-only source snapshot of version 27 (22a2f72), not used in the APK.
package com.example.grounding.baseline27

import com.example.grounding.*

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public HTTPS only in production, with bounded bodies, redirects and cancellation. */
class SearchHttp internal constructor(
    private val client: OkHttpClient = OkHttpClient.Builder().dns(PublicWebDns)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
    private val allowTestUrls: Boolean = false,
    private val quota: java.util.concurrent.atomic.AtomicInteger? = null,
    private val onRequest: ((String) -> Unit)? = null
) : WebFetcher {
    override fun session(maxRequests: Int): WebFetcher = SearchHttp(client, allowTestUrls, java.util.concurrent.atomic.AtomicInteger(maxRequests), onRequest)
    private data class Reply(val code: Int, val location: String?, val page: WebDocument)
    override suspend fun fetch(url: String): WebDocument = fetchChecked(url, null)
    override suspend fun fetchPage(url: String, permitted: suspend (String) -> Boolean): WebDocument = fetchChecked(url, permitted)
    override suspend fun postJson(url: String, json: String, permitted: suspend (String) -> Boolean): WebDocument {
        require(allowTestUrls || GroundingPolicy.publicHttpsUrl(url)) { "A public HTTPS endpoint is required" }
        require(json.toByteArray(Charsets.UTF_8).size <= 16_384) { "Search request is too large" }
        check(permitted(url)) { "Search permission is off" }
        val reply = request(Request.Builder().url(url)
            .header("User-Agent", "BetterIntelligence/2.4 (+https://github.com/sidhrthmnn/BetterIntelligence)")
            .header("Accept", "application/json, text/event-stream")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build())
        // Never replay a question-bearing POST to a redirect destination.
        check(reply.code == 200) { "Search endpoint unavailable (HTTP ${reply.code})" }
        return reply.page
    }
    private suspend fun fetchChecked(url: String, permitted: (suspend (String) -> Boolean)?): WebDocument {
        var current = url
        repeat(4) { hop ->
            require(allowTestUrls || GroundingPolicy.publicHttpsUrl(current)) { "A public HTTPS website is required" }
            check(permitted?.invoke(current) != false) { "Publisher does not permit this read" }
            val reply = request(Request.Builder().url(current).header("User-Agent",
                "BetterIntelligence/2.2 (+https://github.com/sidhrthmnn/BetterIntelligence)")
                .header("Accept", "text/html, application/xhtml+xml, text/plain;q=0.8").build())
            if (reply.code in setOf(301, 302, 303, 307, 308)) {
                check(hop < 3) { "Too many website redirects" }
                current = current.toHttpUrl().resolve(checkNotNull(reply.location))?.toString()
                    ?: error("Invalid website redirect")
            } else {
                check(reply.code == 200) { "Website unavailable (HTTP ${reply.code})" }
                return reply.page
            }
        }
        error("Website unavailable")
    }
    // Used by transport regression tests; all production research uses fetch().
    internal suspend fun json(request: Request): JSONObject = JSONObject(fetch(request.url.toString()).text)
    private suspend fun request(request: Request): Reply {
        check(quota?.getAndDecrement()?.let { it > 0 } != false) { "Research request budget exhausted" }
        onRequest?.invoke(request.url.toString())
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Website could not be reached"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        val body = checkNotNull(it.body) { "Empty website response" }
                        val output = java.io.ByteArrayOutputStream()
                        if (it.code == 200) body.byteStream().use { stream ->
                            val buffer = ByteArray(8192)
                            while (output.size() <= 1024 * 1024) {
                                val count = stream.read(buffer, 0, minOf(buffer.size, 1024 * 1024 + 1 - output.size()))
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        check(output.size() <= 1024 * 1024) { "Search response is too large" }
                        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                        Reply(it.code, it.header("Location"), WebDocument(it.request.url.toString(),
                            String(output.toByteArray(), charset), body.contentType()?.toString().orEmpty(), it.headers.values("X-Robots-Tag").joinToString(",")))
                    } }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }
}

/** Chat-only, bounded public evidence cache; live observations expire quickly. */
class GroundingCoordinator(private val providers: Map<SearchProviderId, SearchProvider>, private val now: () -> Long = System::currentTimeMillis) {
    private val cache = LinkedHashMap<String, Pair<Long, List<EvidenceSource>>>()
    private var epoch = 0L
    @Synchronized fun clearTemporaryData() { epoch++; cache.clear() }
    suspend fun retrieve(question: String, allowed: Boolean, mode: GroundingMode, provider: SearchProviderId,
        progress: (ResearchStage) -> Unit = {}): GroundingResult {
        if (GroundingPolicy.containsCredential(question)) return GroundingResult("PRIVACY_BLOCKED", notice = "This question appears to contain credentials. Search was skipped; this answer is local-only.")
        if (!GroundingPolicy.shouldSearch(allowed, mode, question)) return GroundingResult()
        if (ConversationalResearch.needsSubject(question)) return GroundingResult("NEEDS_SUBJECT", notice = ConversationalResearch.askSubject)
        if (ConversationalResearch.needsNewsTopic(question)) return GroundingResult("NEEDS_SUBJECT", notice = ConversationalResearch.askNewsTopic)
        if (ConversationalResearch.needsPlace(question)) return GroundingResult("NEEDS_LOCATION", notice = ConversationalResearch.askPlace)
        if (!question.contains("https://") && !question.contains("http://") && ConversationalResearch.isWeather(question) && !Regex("(?i)\\b(tomorrow|week|month|yesterday|history|why|how)\\b").containsMatchIn(question)) {
            if (ConversationalResearch.weatherCity(question) == null)
                return GroundingResult("NEEDS_LOCATION", notice = ConversationalResearch.askCity)
            // Weather follows the same public-page reader. Never invoke a weather
            // API or synthesize numeric conditions from the model's memory.
        }
        val key = "$provider:${java.time.LocalDate.now()}:$question"
        val startedEpoch = synchronized(this) { epoch }
        val recheck = ConversationalResearch.isRecheck(question) || Regex("(?i)\\b(?:check|search|verify) again\\b").containsMatchIn(question)
        val lifetime = if (ConversationalResearch.isWeather(question)) 60_000L else if (AnswerRoutingPolicy.decide(question).requiresLive) 300_000L else 900_000L
        val cached = synchronized(this) { cache[key]?.takeIf { mode != GroundingMode.ON && !recheck && now() - it.first in 0 until lifetime }?.second }
        return try {
            val outcome = if (cached != null) ResearchOutcome(cached) else kotlinx.coroutines.withTimeoutOrNull(30_000) {
                checkNotNull(providers[provider]).research(question, progress)
            } ?: return GroundingResult("SEARCH_FAILED", notice = ResearchFailure.TIMEOUT.notice, failure = ResearchFailure.TIMEOUT)
            // Retain only the focused topic already used for public research.
            // Follow-ups can reuse it without sending unsent chat/history.
            val publicQuery = if (ResearchPlanner.plan(question).explicitUrls.isNotEmpty()) "" else
                ResearchQuery(question).topicSearchText.takeIf(ConversationalResearch::validPublicQuery).orEmpty()
            val eligible = outcome.sources.filter { source -> PublicTopics.parse(question)?.let { topic ->
                topic.sourceMatches(source.title, source.excerpt, source.url) && FactualResearch.usable(source, question)
            } != false }
            val sources = ConversationalResearch.bindPublicPlace(question, eligible).map { it.copy(publicQuery = publicQuery) }
            if (sources.isEmpty() && outcome.clarification.isNotBlank() && !GroundingPolicy.containsCredential(outcome.clarification))
                GroundingResult("NEEDS_SUBJECT", notice = outcome.clarification.take(400))
            else if (sources.isEmpty()) GroundingResult("SEARCH_FAILED", notice = (outcome.failure ?: ResearchFailure.INSUFFICIENT).notice, failure = outcome.failure ?: ResearchFailure.INSUFFICIENT)
            else {
                synchronized(this) {
                    if (epoch == startedEpoch && cached == null && mode != GroundingMode.ON) {
                        if (cache.size >= 8) cache.remove(cache.keys.first())
                        cache[key] = now() to sources
                    }
                }
                GroundingResult("SOURCES_RETRIEVED", sources)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Deliberately omit exception causes: HTTP exceptions can include private request URLs.
            GroundingResult("SEARCH_FAILED", notice = "Live sources unavailable. Check your connection or try a public HTTPS link.", failure = ResearchFailure.DISCOVERY_UNAVAILABLE)
        }
    }
}
