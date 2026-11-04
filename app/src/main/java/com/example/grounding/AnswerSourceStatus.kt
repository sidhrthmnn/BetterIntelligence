package com.example.grounding

/** Persisted status IDs stay compatible with existing chats. No false verification. */
object AnswerSourceStatus {
    fun forEvidence(status: String, sources: List<EvidenceSource>): String = if (
        status in setOf("EVIDENCE_CITED", "PARTIALLY_SUPPORTED", "SOURCE_EXCERPTS", "SOURCE_BRIEF") &&
        sources.any { it.kind == "INSTANT_ANSWER" }) "API_SUMMARY" else status
    fun label(status: String): String = when (status) {
        "API_SUMMARY" -> "DuckDuckGo summary · Not independently verified"
        "EVIDENCE_CITED" -> "Web supported · Check sources"
        "PARTIALLY_SUPPORTED" -> "Partially verified · Unsupported details omitted"
        "SOURCE_EXCERPTS", "SOURCE_BRIEF" -> "Web sources · Partial answer"
        "CITATIONS_MISSING" -> "Sources retrieved · Answer not verified"
        "SEARCH_FAILED", "WEB_UNAVAILABLE" -> "Web check incomplete"
        "PROVIDER_TIMEOUT" -> "Search timed out"
        "PROVIDER_RATE_LIMITED" -> "Search temporarily limited"
        "PROVIDER_UNAVAILABLE" -> "Web search temporarily unavailable"
        "PROVIDER_ACCESS_DENIED" -> "Search access denied"
        "PROVIDER_RESPONSE_INVALID" -> "Search response couldn't be read"
        "NETWORK_UNAVAILABLE" -> "Network couldn't be reached"
        "NO_SEARCH_RESULTS" -> "No relevant sources found"
        "PUBLISHER_BLOCKED" -> "Source access restricted"
        "PUBLISHER_TIMEOUT" -> "Sources took too long to open"
        "PUBLISHER_UNAVAILABLE" -> "Sources couldn't be read"
        "GROUNDING_VERIFICATION_FAILED" -> "Sources read · Answer not verified"
        "USER_CANCELLED" -> "Stopped"
        "WEB_DISABLED" -> "Web research is off"
        "LOCAL_WEB_UNAVAILABLE" -> "On-device · Live sources not checked"
        "LOCAL_WITH_WEB_GAP" -> "On-device · Current information unavailable"
        "UNVERIFIED_PARTIAL" -> "Partial draft · Not verified"
        "INSUFFICIENT_EVIDENCE" -> "Not enough web evidence"
        "PRIVACY_BLOCKED" -> "On-device · Private input kept local"
        "LOCAL" -> "On-device"
        "LOCAL_UNVERIFIED" -> "On-device · Live facts not checked"
        else -> ""
    }
}
