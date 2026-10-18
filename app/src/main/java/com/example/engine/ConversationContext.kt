package com.example.engine

/** Bounded complete turns, never shared between conversations or external clients.
 * This is an admission estimate; the runtime still enforces its actual token limit.
 */
object ConversationContext {
    fun history(turns: List<ChatTurn>, prompt: String, system: String, context: Int, output: Int): List<ChatTurn> {
        var budget = ((context - output - 96).coerceAtLeast(0) * 2) -
            prompt.toByteArray(Charsets.UTF_8).size - system.toByteArray(Charsets.UTF_8).size
        require(budget >= 0) { "This message is too long for the selected context. Increase context in Advanced settings or send a shorter message." }
        val chosen = mutableListOf<ChatTurn>()
        val pairs = turns.chunked(2).filter { it.size == 2 && it[0].role == "user" && it[1].role == "assistant" }
        for (pair in pairs.takeLast(12).asReversed()) {
            val size = pair.sumOf { it.content.toByteArray(Charsets.UTF_8).size + 24 }
            if (size > budget) break
            chosen.addAll(0, pair); budget -= size
        }
        return chosen
    }
}
