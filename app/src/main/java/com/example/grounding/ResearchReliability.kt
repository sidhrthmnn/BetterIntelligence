package com.example.grounding

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One monotonic clock, inherited by discovery, backoff, robots and publisher reads. */
class ResearchDeadline(budgetMs: Long, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResearchDeadline>
    private val end = now() + budgetMs.coerceIn(1, 60_000)
    fun remainingMs(): Long = (end - now()).coerceAtLeast(0)
    fun permits(minimumMs: Long = 250): Boolean = remainingMs() >= minimumMs
}

data class ResearchTimeouts(val discoveryMs: Long = 12_000, val retryMs: Long = 18_000,
    val publisherMs: Long = 8_000, val overallMs: Long = 55_000,
    val absoluteMs: Long = 60_000, val backoffMs: Long = 450) {
    init { require(discoveryMs > 0 && retryMs > 0 && publisherMs > 0 && overallMs in 1..60_000 && absoluteMs in overallMs..60_000 && backoffMs >= 0) }
}

/** Numeric/categorical data only. No URLs, queries, responses or exception strings. */
data class ResearchMetrics(val provider: String = "", val durationMs: Long = 0,
    val discoveredResults: Int = 0, val readPublishers: Int = 0, val attemptedPublishers: Int = 0,
    val retries: Int = 0, val httpCategory: String = "", val timeoutStage: String = "",
    val failure: ResearchFailure? = null, val extraction: Map<String, Int> = emptyMap())

class WebRequestException(val statusCode: Int, val retryAfterMs: Long = 0) : IOException("Website HTTP $statusCode")
class PublisherPermissionException : IOException("Publisher does not permit this read")

class ResearchProgress(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var stage = ResearchStage.SEARCHING
    private var since = now()
    @Synchronized fun advance(next: ResearchStage) {
        if (next.ordinal > stage.ordinal) { stage = next; since = now() }
    }
    @Synchronized fun label(): String = stage.label + if (now() - since >= 8_000) " · Taking a little longer" else ""
}

object ResearchErrors {
    fun provider(error: Exception): ResearchFailure = when (error) {
        is WebRequestException -> when (error.statusCode) {
            429 -> ResearchFailure.PROVIDER_RATE_LIMITED
            else -> ResearchFailure.PROVIDER_UNAVAILABLE
        }
        is SocketTimeoutException, is java.io.InterruptedIOException -> ResearchFailure.PROVIDER_TIMEOUT
        is UnknownHostException, is NoRouteToHostException, is ConnectException -> ResearchFailure.NETWORK_UNAVAILABLE
        else -> ResearchFailure.PROVIDER_UNAVAILABLE
    }
    fun publisher(error: Exception): ResearchFailure = when (error) {
        is SocketTimeoutException, is java.io.InterruptedIOException -> ResearchFailure.PUBLISHER_TIMEOUT
        is UnknownHostException, is NoRouteToHostException, is ConnectException -> ResearchFailure.NETWORK_UNAVAILABLE
        is PublisherPermissionException -> ResearchFailure.PUBLISHER_BLOCKED
        is WebRequestException -> if (error.statusCode in setOf(401, 403, 429, 451)) ResearchFailure.PUBLISHER_BLOCKED else ResearchFailure.PUBLISHER_UNAVAILABLE
        else -> ResearchFailure.PUBLISHER_UNAVAILABLE
    }
    fun retryable(error: Exception): Boolean = error !is CancellationException && when (error) {
        is WebRequestException -> error.statusCode in setOf(408, 500, 502, 503, 504)
        is javax.net.ssl.SSLException, is UnknownHostException, is NoRouteToHostException -> false
        is IOException -> true
        else -> false
    }
    fun httpCategory(code: Int): String = if (code in 100..599) "${code / 100}xx" else ""
}
