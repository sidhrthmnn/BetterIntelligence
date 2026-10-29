package com.example.grounding

import okhttp3.HttpUrl.Companion.toHttpUrl

data class ResearchCandidate(val url: String, val title: String, val routingHint: String = "", val provider: String = "",
    val instantAnswer: EvidenceSource? = null)
enum class DiscoveryState { FOUND, EMPTY, BLOCKED, UNAVAILABLE, TIMEOUT }
data class DiscoveryResult(val candidates: List<ResearchCandidate>, val state: DiscoveryState)
fun interface DiscoveryProvider {
    suspend fun discover(query: ResearchQuery, broad: Boolean): DiscoveryResult
}

/** Shared canonicalization; discovery is provided only by the official Instant Answer API. */
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
