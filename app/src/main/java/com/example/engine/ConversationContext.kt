package com.example.engine

/** Bounded complete turns, never shared between conversations or external clients.
 * This is an admission estimate; the runtime still enforces its actual token limit.
 */
object ConversationContext {
    fun history(turns: List<ChatTurn>, prompt: String, system: String, context: Int, output: Int,
        tokenCount: (String) -> Int = { (it.toByteArray(Charsets.UTF_8).size + 1) / 2 }): List<ChatTurn> {
        var budget = context - output - 96 - tokenCount(prompt) - tokenCount(system)
        require(budget >= 0) { "This message is too long for the selected context. Increase context in Advanced settings or send a shorter message." }
        val pairs = mutableListOf<List<ChatTurn>>()
        var user: ChatTurn? = null
        turns.forEach { turn ->
            if (turn.role == "user") user = turn
            else if (turn.role == "assistant" && user != null) { pairs += listOf(user!!, turn); user = null }
        }
        val chosen = sortedSetOf<Int>()
        fun include(index: Int): Boolean {
            val cost = pairs[index].sumOf { tokenCount(it.content) + 12 }
            if (cost > budget) return false
            chosen += index; budget -= cost; return true
        }
        val recentBudget = budget / 2
        for (index in pairs.indices.reversed()) {
            if (!include(index) || budget < recentBudget || chosen.size >= 6) break
        }
        val terms = Regex("[\\p{L}\\p{M}\\p{N}]{3,}").findAll(prompt.lowercase()).map { it.value }.toSet() -
            setOf("the", "and", "what", "how", "could", "would", "this", "that", "with", "from")
        pairs.indices.filter { it !in chosen }.sortedByDescending { index ->
            terms.count { word -> pairs[index].any { word in it.content.lowercase() } }
        }.forEach { index ->
            if (chosen.size < 12 && terms.any { word -> pairs[index].any { word in it.content.lowercase() } }) include(index)
        }
        return chosen.flatMap { pairs[it] }
    }

    fun summaryWithinBudget(summary: String, context: Int): String = summary.lines()
        .runningFold("") { result, line -> if (result.length + line.length + 1 <= minOf(1200, context / 2)) "$result$line\n" else result }
        .last().trim()
}
