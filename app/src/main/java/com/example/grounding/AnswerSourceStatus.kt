package com.example.grounding

/** Persisted status IDs stay compatible with existing chats. No false verification. */
object AnswerSourceStatus {
    fun label(status: String): String = when (status) {
        "EVIDENCE_CITED" -> "Web supported · Check sources"
        "PARTIALLY_SUPPORTED" -> "Partially verified · Unsupported details omitted"
        "SOURCE_EXCERPTS" -> "Source excerpt · Check reported time"
        "CITATIONS_MISSING" -> "Sources retrieved · Answer not verified"
        "SEARCH_FAILED", "WEB_UNAVAILABLE" -> "Web unavailable"
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
