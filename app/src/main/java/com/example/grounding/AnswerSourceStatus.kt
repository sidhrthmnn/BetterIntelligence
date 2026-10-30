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
        "SOURCE_EXCERPTS" -> "Source excerpt · Check reported time"
        "SOURCE_BRIEF" -> "What sources say · Answer remains incomplete"
        "CITATIONS_MISSING" -> "Sources retrieved · Answer not verified"
        "SEARCH_FAILED", "WEB_UNAVAILABLE" -> "Web unavailable"
        "WEB_DISABLED" -> "Web research is off"
        "LOCAL_WEB_UNAVAILABLE" -> "On-device · Web unavailable"
        "LOCAL_WITH_WEB_GAP" -> "On-device · Current information unavailable"
        "UNVERIFIED_PARTIAL" -> "Partial draft · Not verified"
        "INSUFFICIENT_EVIDENCE" -> "Not enough web evidence"
        "PRIVACY_BLOCKED" -> "On-device · Private input kept local"
        "LOCAL" -> "On-device"
        "LOCAL_UNVERIFIED" -> "On-device · Live facts not checked"
        else -> ""
    }
}
