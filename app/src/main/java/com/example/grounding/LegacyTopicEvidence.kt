package com.example.grounding

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Compatibility for saved source cards only. No discovery/API networking. */
object LegacyTopicEvidence {
    const val providerName = "DuckDuckGo"
    fun summaryAllowed(question: String): Boolean = AnswerRoutingPolicy.decide(question).let {
        !it.requiresLive && !it.requiresVerification && !it.privacyBlocked && !AnswerRoutingPolicy.localOnly(question)
    }
    fun resultPage(topic: String) = "https://duckduckgo.com/".toHttpUrl().newBuilder()
        .addQueryParameter("q", topic.take(400)).build().toString()
}
