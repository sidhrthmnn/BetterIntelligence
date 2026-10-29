package com.example.grounding

import java.time.LocalDate

/** Compatibility facade. Chat intent is implemented in AnswerRoutingPolicy. */
object ResearchRouting {
    data class Decision(val search: Boolean, val reason: String)
    fun decide(question: String, today: LocalDate = LocalDate.now()): Decision =
        AnswerRoutingPolicy.decide(question, today = today).let { Decision(it.path != AnswerPath.LOCAL, it.reason) }
    fun localOnly(question: String) = AnswerRoutingPolicy.localOnly(question)
    fun explicitlyLocalTransformation(question: String) = localOnly(question)
}
