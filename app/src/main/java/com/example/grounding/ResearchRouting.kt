package com.example.grounding

import java.time.LocalDate

/** Testable intent rules, not an estimate of the model's confidence. */
object ResearchRouting {
    data class Decision(val search: Boolean, val reason: String)
    private val transformation = Regex("(?i)^\\s*(?:please\\s+)?(?:write|draft|compose|imagine|brainstorm|translate|rewrite|polish|proofread|summari[sz]e|format|extract|convert)\\b|^(?:എഴുതുക|വിവർത്തനം)")
    private val verification = Regex("(?i)\\b(?:verify|fact[- ]?check|sources?|citations?|official (?:documents?|documentation|specifications?)|prove|evidence|search|look up|check online)\\b|സത്യമാണോ|തെളിവ്")
    private val changing = Regex("(?i)\\b(?:population|prices?|costs?|weather|temperature|forecast|exchange rate|stock price|ceo|chief (?:executive|minister)|prime minister|president|king|queen|monarch|mayor|chancellor|incumbent|supported versions?|support end|security update)\\b|കാലാവസ്ഥ|ജനസംഖ്യ|പ്രധാനമന്ത്രി|മുഖ്യമന്ത്രി")
    private val freshness = Regex("(?i)\\b(?:news|headlines|latest|today|tonight|yesterday|currently|recent|this (?:week|month|year)|right now)\\b|\\bcurrent\\s+(?!flow|electric|in a circuit)|ഇന്ന്|ഇപ്പോൾ|നിലവിലെ|ഏറ്റവും പുതിയ")
    private val comparison = Regex("(?i)\\b(?:compare|versus|vs\\.?|better than|difference between)\\b|താരതമ്യം|തമ്മിൽ")
    private val technical = Regex("(?i)\\b(?:android|phones?|pixel|iphone|litert(?:-lm)?|llama\\.cpp|gguf|qwen|gemma|frameworks?|runtimes?|gpu|npu|models?)\\b")
    fun decide(question: String, today: LocalDate = LocalDate.now()): Decision {
        val q = question.trim()
        val explicit = verification.containsMatchIn(q) || Regex("https?://").containsMatchIn(q)
        if (transformation.containsMatchIn(q)) {
            val supplied = Regex("(?i)\\b(?:supplied|following|this)\\s+(?:text|passage|paragraph|document)\\b").containsMatchIn(q)
            val requestedResearch = Regex("(?i)\\b(?:search the web|look up|verify online|research|find sources|(?:with|using|from) (?:cited |official )?sources)\\b").containsMatchIn(q.substringBefore('\n').substringBefore(": "))
            if (supplied || !requestedResearch && !Regex("https?://").containsMatchIn(q)) return Decision(false, "Creative or supplied-text task")
        }
        if (explicit) return Decision(true, "Requested source or verification")
        if ("(Public source topic:" in q || ConversationalResearch.isRecheck(q)) return Decision(true, "Public research follow-up")
        if (Regex("(?i)^\\s*(?:how (?:long|many years) did|when did|what year did)\\b").containsMatchIn(q) &&
            !Regex("(?i)\\b(?:I|we|my|our)\\b").containsMatchIn(q)) return Decision(true, "Historical dates or duration need sources")
        if (Regex("(?i)^\\s*(?:why|how|explain)\\b.*\\b(?:evolution|genetics?|ancestry|fittest|skin colou?r|human variation)\\b").containsMatchIn(q) &&
            !Regex("(?i)\\b(?:my|our)\\b").containsMatchIn(q)) return Decision(true, "Scientific explanation benefits from evidence")
        val historic = Regex("(?i)\\b(?:who was|historical|history of|first ever)\\b").containsMatchIn(q) ||
            Regex("\\b(?:18|19|20)\\d{2}\\b").findAll(q).any { it.value.toInt() < today.year - 1 }
        if (historic && !freshness.containsMatchIn(q)) return Decision(false, "Historical background")
        if (!freshness.containsMatchIn(q) && !comparison.containsMatchIn(q) &&
            (!changing.containsMatchIn(q) || Regex("(?i)stand for|concept|forecasting|definition|\\brole\\b").containsMatchIn(q)) &&
            Regex("(?i)^(?:explain|define|what does .{1,60} stand for|what (?:is|are) (?:a|an) )\\b").containsMatchIn(q))
            return Decision(false, "Definition or general explanation")
        if (freshness.containsMatchIn(q) || changing.containsMatchIn(q) || ResearchQuery.isCurrentIncumbentQuery(q)) return Decision(true, "Changing factual information")
        if (Regex("(?i)\\b(?:who won|winner of|prize|award)\\b").containsMatchIn(q))
            return Decision(true, "Recent event result")
        if (comparison.containsMatchIn(q) && technical.containsMatchIn(q)) return Decision(true, "Technical comparison")
        if (technical.containsMatchIn(q) && Regex("(?i)\\b(?:compatible|compatibility|supports?|available|stable version|requirements?)\\b").containsMatchIn(q))
            return Decision(true, "Runtime or product compatibility")
        return Decision(false, "Static explanation, reasoning or ordinary conversation")
    }
    fun explicitlyLocalTransformation(question: String): Boolean = transformation.containsMatchIn(question) &&
        Regex("(?i)\\b(?:offline|local(?:ly)?|without (?:web|search)|do not search|don't search)\\b").containsMatchIn(question)
    fun localOnly(question: String): Boolean = Regex("(?i)^\\s*(?:please\\s+)?(?:(?:work|answer|respond|stay)\\s+)?(?:offline|locally|without (?:web|search)|do not search|don't search)\\b").containsMatchIn(question)
}
