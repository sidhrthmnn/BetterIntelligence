package com.example.engine

/** Chat-only expansion. External polish/classification and benchmarks retain
 * their explicit hard limits. Never enlarge the allocated context or KV cache. */
object ChatResponseBudget {
    const val MAX_OUTPUT = 1024
    fun initial(requested: Int, context: Int): Int {
        val ceiling = minOf(MAX_OUTPUT, context / 2).coerceAtLeast(16)
        val normal = if (context >= 2048) 512 else if (context >= 1024) 256 else 128
        return maxOf(requested, normal).coerceIn(16, ceiling)
    }
    /** LiteRT-LM has no API to change a running Conversation's limit. Reserve
     * bounded headroom before starting it. UTF-8 byte count is a conservative
     * input bound, not a fabricated exact tokenizer measurement. */
    fun liteRtLimit(prompt: String, params: GenerationParams): Int {
        val initial = params.maxTokens.coerceIn(1, MAX_OUTPUT)
        if (!params.autoContinue) return initial
        val used = prompt.toByteArray().size.toLong() + params.systemPrompt.orEmpty().toByteArray().size + 128L +
            params.history.sumOf { it.content.toByteArray().size.toLong() + 24L }
        val room = (params.contextSize - used).coerceIn(0L, MAX_OUTPUT.toLong()).toInt()
        return maxOf(initial, room)
    }
}
