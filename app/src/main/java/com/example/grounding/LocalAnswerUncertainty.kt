package com.example.grounding

/** A recovery signal, not a confidence score or truth detector. The caller
 * decides privacy/offline/task eligibility BEFORE seeing model output. Never
 * use generated text as the outgoing query. No recursive retries. */
object LocalAnswerUncertainty {
    const val marker = "[WEB_NEEDED]"
    fun needsResearch(answer: String): Boolean {
        val text = answer.trim().take(1600)
        if (text.startsWith(marker)) return true
        // Only the model's own first-person admission, not "unknown" appearing
        // somewhere in an otherwise useful answer or a quoted user phrase.
        val opener = "(?:sorry[,!.]?\\s*|unfortunately[,!.]?\\s*|as an? (?:AI|language model)[^.!?]{0,70}[,.]\\s*|to (?:tell|answer|know|say|help|advise|determine)[^.!?]{0,120},\\s*)?"
        val admission = "(?:I (?:do not|don't|don’t|cannot|can't|can’t|could not|couldn't)\\s+(?:currently\\s+)?(?:know|have|access|provide|verify|confirm|find|check|browse|look up|see|tell)" +
            "|I(?:'m| am|’m) (?:not sure|uncertain|unaware|unable|not able)" +
            "|I(?: would|'d|’d)? need (?:to (?:know|check|look up|access|find|verify|see|get|have)|more information|your (?:current )?location|access)" +
            "|I (?:have no|lack) (?:access|way|real-time|live|current)" +
            "|(?:you (?:will|would|'ll|'d)? need to|please) (?:check|look up|consult|search)" +
            "|my (?:knowledge|information).{0,50}(?:limited|outdated|cutoff)|I lack)"
        return Regex("(?is)^$opener$admission\\b").containsMatchIn(text)
    }
    fun visible(text: String): String = if (marker.startsWith(text.trimStart())) "" else text.replace(marker, "").trimStart()
}
