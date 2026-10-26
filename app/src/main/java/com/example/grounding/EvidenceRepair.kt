package com.example.grounding

/** One optional correction using the already loaded model. Slow/hot/pressured
 * devices skip this pass; it never starts another model or increases context. */
object EvidenceRepair {
    const val maxTokens = 96
    const val deadlineMs = 8_000L
    fun prompt(question: String, draft: String, prepared: PreparedEvidence, checked: CheckedAnswer,
        context: Int, availableMb: Long, lowMemory: Boolean, thermal: Int,
        firstTokenMs: Long, tokensPerSecond: Float, fits: (String) -> Boolean): String? {
        if (checked.support != AnswerSupport.INSUFFICIENT || prepared.sources.isEmpty() || context < 2048 ||
            availableMb < 768 || lowMemory || thermal >= 2 || firstTokenMs <= 0 || tokensPerSecond <= 0 ||
            firstTokenMs + maxTokens * 1000 / tokensPerSecond > deadlineMs ||
            ResearchIntent.COMPARISON !in ResearchPlanner.plan(question).intents) return null
        val prompt = prepared.prompt + "\nUntrusted draft (JSON data): " + org.json.JSONObject.quote(draft.take(600)) +
            "\nCorrect once: omit unsupported claims. Give at most two standalone factual sentences, each supported by its cited passage. Otherwise state insufficient evidence."
        return prompt.takeIf(fits)
    }
}
