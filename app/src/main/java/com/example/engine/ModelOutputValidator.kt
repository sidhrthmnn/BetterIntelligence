package com.example.engine

/** Fail the request, never strip tokens to make a broken decode look successful. */
class InvalidModelOutputException : IllegalStateException(
    "The model produced an unreadable response. Retry, use CPU Only, or select another model. Your message is kept.")

object ModelOutputValidator {
    private val control = Regex("<unused\\d+>|\\[multimodal]|<\\|(?:image|audio|video|channel|end_of_turn)[^>]*>", RegexOption.IGNORE_CASE)
    private fun prose(text: String): String = text
        .replace(Regex("```[\\s\\S]*?(?:```|$)"), " ")
        .replace(Regex("`[^`\\n]*(?:`|$)"), " ")
        .replace(Regex("\"[^\"\\n]*\"|'[^'\\n]*'"), " ")

    fun validate(text: String, literalInput: String = "") {
        val sample = prose(text).takeLast(4096)
        val markers = control.findAll(sample).toList()
        val plain = control.replace(sample, "").count { it.isLetterOrDigit() }
        val requested = control.findAll(literalInput).map { it.value }.toList()
        val literalExample = requested.isNotEmpty() && markers.size <= requested.size && markers.all { it.value in requested }
        if (!literalExample && markers.size >= 3 && (markers.sumOf { it.value.length } > sample.length / 3 || plain < 40))
            throw InvalidModelOutputException()
        val replacements = sample.count { it == '\uFFFD' }
        if (replacements >= 5 && replacements * 12 > sample.length) throw InvalidModelOutputException()
        // A short repeated period/punctuation run is ordinary prose. Long repeated
        // generated units are not; ignore examples inside code and quotation marks.
        if (sample.length >= 96 && Regex("(.{3,80}?)\\1{11,}", RegexOption.DOT_MATCHES_ALL).containsMatchIn(sample))
            throw InvalidModelOutputException()
    }

    /** Normal text has at most 24 characters of look-ahead. Suspect token streams
     * are held for a bounded 256 characters, then rejected if they lack prose.
     * The caller stops native generation and awaits cancellation before reuse.
     */
    class Stream(private val literalInput: String = "") {
        private val output = StringBuilder()
        private var delivered = 0
        @Synchronized fun append(delta: String): String {
            output.append(delta)
            val text = output.toString()
            validate(text, literalInput)
            val tail = text.substring(delivered)
            val suspect = tail.indexOf('<').takeIf { it >= 0 &&
                Regex("<(?:unused\\d*|\\|[^>]*)(?:>|$)", RegexOption.IGNORE_CASE).containsMatchIn(tail.substring(it)) }
            val end = if (suspect != null) {
                if (tail.length > 256 && tail.count { it.isLetterOrDigit() } < 48) throw InvalidModelOutputException()
                if (tail.length <= 256) delivered + suspect else (text.length - 24).coerceAtLeast(delivered)
            } else (text.length - 24).coerceAtLeast(delivered)
            return text.substring(delivered, end).also { delivered = end }
        }
        @Synchronized fun finish(fullText: String = output.toString()): String {
            validate(fullText, literalInput)
            return fullText.substring(delivered.coerceAtMost(fullText.length)).also { delivered = fullText.length }
        }
    }
}
