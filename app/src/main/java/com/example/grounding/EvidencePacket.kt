package com.example.grounding

/** A derived view of the exact evidence supplied to the model, not a second
 * generated summary. Numerical text is preserved, including its qualifications. */
data class EvidencePacket(val source: EvidenceSource, val objectives: List<String>, val numericalText: List<String>) {
    companion object {
        fun from(source: EvidenceSource, plan: ResearchPlan) = EvidencePacket(source,
            plan.coverage(source.excerpt).map { plan.focuses[it].question },
            Regex("[^.!?\\n]*\\d[^!?\\n]*").findAll(source.excerpt).map { it.value.trim() }.take(8).toList())

        /** Drop whole sentences/rows instead of cutting before a qualifier such
         * as 'only with the vendor SDK'. An oversized first sentence is omitted. */
        fun completeExcerpt(text: String, bytes: Int): String {
            if (text.toByteArray().size <= bytes) return text
            val output = StringBuilder()
            for (line in text.split(Regex("(?<=[.!?])\\s+(?=\\p{L})|\\n+"))) {
                if ((output.toString() + "\n" + line).toByteArray().size > bytes) break
                if (output.isNotEmpty()) output.append('\n')
                output.append(line)
            }
            return output.toString()
        }
    }
}
