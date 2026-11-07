package com.example.grounding

import org.json.JSONObject

/** Fixed numeric allowlist only; never claims, prompts, passages or exception text. */
object VerificationDiagnostics {
    private val reasons = mapOf("Missing citation" to "MISSING_CITATION", "Unknown citation" to "INVALID_CITATION",
        "Lexical mismatch" to "LEXICAL_MISMATCH", "Entity mismatch" to "ENTITY_MISMATCH", "Polarity mismatch" to "POLARITY_MISMATCH",
        "Numerical mismatch" to "NUMERICAL_MISMATCH", "Qualification mismatch" to "QUALIFICATION_MISMATCH",
        "Relationship mismatch" to "RELATIONSHIP_MISMATCH", "Historical or recency mismatch" to "RECENCY_MISMATCH")
    val keys = listOf("generatedClaims", "acceptedClaims", "rejectedClaims", "assembledCitations", "recoveryAttempted", "recoveredClaims", "repairAttempted", "generationFailed", "UNSUPPORTED") + reasons.values
    fun counts(answer: CheckedAnswer?): JSONObject {
        val result = JSONObject(); keys.forEach { result.put(it, 0) }
        if (answer == null) return result
        val claims = answer.claims.filter { it.reason !in setOf("Heading", "Explicit uncertainty") }
        result.put("generatedClaims", claims.size).put("acceptedClaims", claims.count { it.supported })
            .put("rejectedClaims", claims.count { !it.supported }).put("assembledCitations", claims.count { it.citationAssembled })
            .put("recoveryAttempted", if (answer.recoveryAttempted) 1 else 0).put("recoveredClaims", answer.recoveredClaims)
            .put("repairAttempted", if (answer.repairAttempted) 1 else 0).put("generationFailed", if (answer.generationFailed) 1 else 0)
        claims.filter { !it.supported }.forEach { claim ->
            val key = reasons[claim.reason] ?: "UNSUPPORTED"
            result.put(key, result.getInt(key) + 1)
        }
        result.put("MISSING_CITATION", result.getInt("MISSING_CITATION") + claims.count { it.citationAssembled })
        return result
    }
}
