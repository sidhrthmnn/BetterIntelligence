package com.example.grounding

/** Publisher controls are not evidence for an unrelated question. Keep them
 * available when the user actually asks about licensing or those controls. */
object PublisherBoilerplate {
    // Strip long invisible watermark runs, retaining individual joiners used
    // legitimately within Indic words.
    fun readable(text: String) = text.replace(Regex("\\p{Cf}{3,}"), "").replace(Regex("[\\u200B\\uFEFF]"), "")
    fun unrelatedFooter(text: String, question: String): Boolean =
        (text.substringAfter('\n', text).let { body -> body.length < 180 && Regex("(?i)^(?:Part Of All the news|All the news about|Related Roundup:|Buyer's Guide:|A closeup of|[^.]{1,65}\\. Image:)").containsMatchIn(body) }) ||
        !Regex("(?i)copyright|reprint|syndication|crossword").containsMatchIn(question) &&
            text.length < 900 && Regex("(?i)for reprint rights|copyright\\s*©|©\\s*20\\d{2}|all rights reserved|daily crossword|more from etimes|changes recorded by our crawls and availability feeds|observation dates may differ from platform release|checking other sources|seen it somewhere we['’]re missing|headlines and links from the publications that reviewed it")
                .containsMatchIn(text.substringAfter('\n', text))
}
