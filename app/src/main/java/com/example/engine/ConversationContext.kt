package com.example.engine

/** Bounded complete turns, never shared between conversations or external clients.
 * This is an admission estimate; the runtime still enforces its actual token limit.
 */
object ConversationContext {
    const val summaryHeader = "Session notes (quoted history; newer messages override):"
    private val important = Regex("(?i)\\b(my|i'm|i am|called|named|decided|prefer|remember|must|don't|do not)\\b")

    /** Bounded extractive notes, never a generated or cross-session biography.
     * Preserve early project anchors as well as later decisions/corrections.
     * Assistant proposals are labelled and retained only when a following user
     * refers to them; they are not promoted to independently verified facts.
     */
    fun summarize(turns: List<ChatTurn>): String {
        val pairs = mutableListOf<Pair<String, String>>()
        var user: String? = null
        turns.forEach { if (it.role == "user") user = it.content else if (user != null) {
            pairs += user!! to it.content; user = null
        } }
        val older = pairs.dropLast(6)
        if (older.isEmpty()) return ""
        val anchors = older.indices.filter { important.containsMatchIn(older[it].first) }
        val chosen = (anchors.take(3) + anchors.takeLast(4) + older.indices.toList().takeLast(6)).distinct().sorted()
        fun quote(text: String, limit: Int) = text.replace(Regex("\\s+"), " ").take(limit)
        return (listOf(summaryHeader) + chosen.flatMap { index ->
            val (input, response) = older[index]
            val reference = pairs.getOrNull(index + 1)?.first.orEmpty()
            val keepProposal = Regex("(?i)\\b(that|this|it|second|option|use|keep|yes|agreed|continue)\\b").containsMatchIn(reference)
            listOf("User said: \"${quote(input, 180)}\"") + if (keepProposal)
                listOf("Assistant proposed (unverified): \"${quote(response, 180)}\"") else emptyList()
        }).joinToString("\n").take(4000)
    }
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

    fun summaryWithinBudget(summary: String, context: Int, question: String = ""): String {
        val lines = summary.lines().filter { it.isNotBlank() && it != summaryHeader }
        val terms = Regex("[\\p{L}\\p{M}\\p{N}]{3,}").findAll(question.lowercase()).map { it.value }.toSet() -
            setOf("the", "and", "what", "how", "this", "that", "with", "from", "please")
        val ranked = lines.indices.sortedWith(compareByDescending<Int> { index ->
            terms.count { it in lines[index].lowercase() } * 8 + if (lines[index].startsWith("User")) 2 else 0
        }.thenByDescending { it })
        var budget = minOf(1200, context.coerceAtLeast(0) / 2)
        val chosen = sortedSetOf<Int>()
        fun include(index: Int) { if (index !in chosen && lines[index].length + 1 <= budget) {
            chosen += index; budget -= lines[index].length + 1
        } }
        // If the follow-up is only a pronoun, retain the original session subject.
        if (lines.isNotEmpty() && terms.none { term -> lines.any { term in it.lowercase() } } && lines[0].length < budget / 2) include(0)
        ranked.forEach(::include)
        return chosen.joinToString("\n") { lines[it] }
    }
}
