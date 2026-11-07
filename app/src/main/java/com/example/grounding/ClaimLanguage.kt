package com.example.grounding

/** Small reviewed equivalences, not a similarity model or general entailment.
 * Entity, value, relationship, polarity and condition checks remain independent. */
object ClaimLanguage {
    private val aliases = mapOf("needs" to "require", "need" to "require", "needed" to "require", "requires" to "require", "required" to "require", "requirements" to "require",
        "gigabytes" to "gb", "gigabyte" to "gb", "megabytes" to "mb", "megabyte" to "mb", "memory" to "ram", "accepts" to "support", "accept" to "support", "supports" to "support", "supported" to "support", "supporting" to "support", "compatible" to "support",
        "pictures" to "image", "images" to "image", "picture" to "image", "photographs" to "image", "photo" to "image",
        "runs" to "run", "running" to "run", "operates" to "run", "operate" to "run",
        "purchased" to "buy", "purchase" to "buy", "bought" to "buy", "wrote" to "write", "written" to "write",
        "founded" to "found", "established" to "found", "faster" to "speed", "slower" to "speed")
    fun words(text: String): Set<String> = ResearchQuery.words(text.replace(Regex("(?i)\\b(?:is|are) compatible with\\b"), "supports")
        .replace(Regex("(?i)\\b(?:cannot|can't) (?:process|accept) (?:images?|pictures?)\\b"), "does not support image input"))
        .map { aliases[it] ?: it }.toSet()

    /** Planner version matching must not turn an attribute verb plus a
     * quantity ("needs 4") into an identity such as "Qwen 3". */
    fun entities(text: String): List<String> = ResearchPlanner.entities(text).filterNot {
        ResearchQuery.words(it).all { word -> word in setOf("gb", "gib", "mb", "mib", "kb", "ram") } ||
        Regex("(?i)^(?:needs?|requires?|required|uses?|has|have|is|are|costs?|at|of|with|supports?)\\s+\\d").containsMatchIn(it)
    }

    fun predicatesMatch(claim: String, passage: String): Boolean {
        val predicates = setOf("replace", "replaces", "replaced", "require", "support", "run", "found", "write", "buy", "cost", "costs", "release", "released", "launch", "launched", "announce", "announced", "win", "won", "lose", "lost", "appoint", "appointed", "resign", "resigned", "encrypt", "delete", "create", "sell", "send", "waterproof", "free", "private", "secure", "debt", "loan", "loans", "owe", "owes", "borrowed", "borrows")
        return words(passage).containsAll(words(claim).intersect(predicates))
    }

    fun financialActor(text: String): Set<String>? = Regex("(?i)^\\s*([^.!?\\n:]{1,70}?)\\s+(?:has|have|owes?|borrowed|borrows?|took out|holds?|secured|obtained|raised)\\b")
        .find(text.substringAfterLast('\n'))?.groupValues?.get(1)?.let(ResearchQuery::words)

    fun answerScopeMatches(question: String, claim: String): Boolean {
        if (!Regex("(?i)\\b(?:loans?|debt|owes?|borrow)\\b").containsMatchIn(question)) return true
        val asked = Regex("(?i)\\bdoes (.+?) (?:have|owe)\\b").find(question)?.groupValues?.get(1)
            ?: ResearchPlanner.subjects(question).singleOrNull() ?: return false
        return financialActor(claim)?.containsAll(ResearchQuery.words(asked)) == true
    }

    fun actorMatches(claim: String, passage: String): Boolean {
        if (!Regex("(?i)\\b(?:loans?|debt|owes?|borrow(?:ed|s)?)\\b").containsMatchIn(claim)) return true
        val actor = financialActor(claim) ?: return false
        return financialActor(passage)?.containsAll(actor) == true
    }

    fun periodsPresent(claim: String, passage: String): Boolean {
        val periods = setOf("daily", "weekly", "monthly", "quarterly", "annually", "annual")
        val a = words(claim).intersect(periods); val b = words(passage).intersect(periods)
        return if (Regex("(?i)\\b(?:users?|revenue|earnings?|active)\\b").containsMatchIn(claim)) a == b else b.containsAll(a)
    }

    fun conditionsPresent(claim: String, passage: String): Boolean {
        val condition = Regex("(?i)\\b(?:only (?:when|with|on|for|if)|provided that|except|unless|on the tested device)\\b[^.!?;\\n]*")
        val grammatical = ResearchQuery.words("only when with on for if provided that the a an is are it using")
        return condition.findAll(passage).all { part ->
            val required = words(part.value) - grammatical
            required.isNotEmpty() && words(claim).containsAll(required) &&
                Regex("(?i)\\b(?:only|when|provided|except|unless|tested device)\\b").containsMatchIn(claim)
        }
    }

    /** Bind simple explicit directed relationships; word overlap cannot reverse them. */
    fun relationshipMatches(claim: String, passage: String): Boolean {
        fun relations(text: String): Set<String> {
            val result = mutableSetOf<String>()
            for (m in Regex("(?i)\\b([\\p{L}][\\p{L}-]*) is (?:the )?capital of ([\\p{L}][\\p{L}-]*)").findAll(text))
                result += "capital:${m.groupValues[1].lowercase()}:${m.groupValues[2].lowercase()}"
            for (m in Regex("(?i)\\b(?:the )?capital of ([\\p{L}][\\p{L}-]*) is ([\\p{L}][\\p{L}-]*)").findAll(text))
                result += "capital:${m.groupValues[2].lowercase()}:${m.groupValues[1].lowercase()}"
            for (m in Regex("(?i)\\b([\\p{L}][\\p{L}\\p{N}-]*) is (faster|slower) than ([\\p{L}][\\p{L}\\p{N}-]*)").findAll(text)) {
                val a = m.groupValues[1].lowercase(); val b = m.groupValues[3].lowercase()
                result += if (m.groupValues[2].equals("faster", true)) "speed:$a:$b" else "speed:$b:$a"
            }
            return result
        }
        return relations(passage).containsAll(relations(claim))
    }
}
