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
        return Regex("(?is)^(?:sorry[,!.]?\\s*|unfortunately[,!.]?\\s*|as an? (?:AI|language model)[^.!?]{0,70}[,.]\\s*)?(?:I (?:do not|don't|don’t|cannot|can't|can’t|could not|couldn't)\\s+(?:currently\\s+)?(?:know|have|access|provide|verify|confirm|find)|I(?:'m| am|’m) (?:not sure|uncertain|unaware)|my (?:knowledge|information).{0,50}(?:limited|outdated|cutoff)|I lack)\\b").containsMatchIn(text)
    }
    fun visible(text: String): String = if (marker.startsWith(text.trimStart())) "" else text.replace(marker, "").trimStart()
}
