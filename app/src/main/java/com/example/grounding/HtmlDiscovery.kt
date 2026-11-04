package com.example.grounding

import okhttp3.HttpUrl.Companion.toHttpUrl

data class ResearchCandidate(val url: String, val title: String, val routingHint: String = "", val provider: String = "",
    val instantAnswer: EvidenceSource? = null)
enum class DiscoveryState { FOUND, EMPTY, BLOCKED, UNAVAILABLE, TIMEOUT, RATE_LIMITED, NETWORK_UNAVAILABLE, MALFORMED }
data class DiscoveryResult(val candidates: List<ResearchCandidate>, val state: DiscoveryState,
    val attempts: Int = 1, val durationMs: Long = 0, val httpCategory: String = "", val retryAfterMs: Long = 0) {
    val failure: ResearchFailure? get() = when (state) {
        DiscoveryState.FOUND -> null
        DiscoveryState.EMPTY -> ResearchFailure.NO_SEARCH_RESULTS
        DiscoveryState.TIMEOUT -> ResearchFailure.PROVIDER_TIMEOUT
        DiscoveryState.RATE_LIMITED -> ResearchFailure.PROVIDER_RATE_LIMITED
        DiscoveryState.NETWORK_UNAVAILABLE -> ResearchFailure.NETWORK_UNAVAILABLE
        DiscoveryState.MALFORMED -> ResearchFailure.PROVIDER_RESPONSE_INVALID
        DiscoveryState.BLOCKED -> ResearchFailure.PROVIDER_ACCESS_DENIED
        else -> ResearchFailure.PROVIDER_UNAVAILABLE
    }
}
fun interface DiscoveryProvider {
    suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult
}

/** Shared URL canonicalization for discovery and publisher reading. */
object HtmlDiscovery {
    /** Only remove well-known tracking keys. Article IDs and pagination remain intact. */
    fun canonicalUrl(value: String): String {
        val url = value.toHttpUrl()
        return url.newBuilder().fragment(null).apply {
            url.queryParameterNames.filter { it.startsWith("utm_", true) || it.lowercase() in setOf("fbclid", "gclid", "msclkid") }
                .forEach { removeAllQueryParameters(it) }
        }.build().toString()
    }
}
