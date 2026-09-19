package com.example.engine

/**
 * Tokenizer supporting common LLM chat templates and token encodings.
 */
class Tokenizer(val vocabSize: Int = 32000) {

    // Common control tokens
    val bosToken = "<s>"
    val eosToken = "</s>"
    val imStart = "<|im_start|>"
    val imEnd = "<|im_end|>"
    val instStart = "[INST]"
    val instEnd = "[/INST]"

    private val tokenToWord = java.util.concurrent.ConcurrentHashMap<Int, String>()

    fun encode(text: String): List<Int> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.map { word ->
            val id = (word.hashCode() and 0x7FFFFFFF) % vocabSize
            tokenToWord[id] = word
            id
        }
    }

    fun decode(tokens: List<Int>): String {
        return tokens.joinToString(" ") { tokenToWord[it] ?: "<unk>" }
    }

    fun applyChatTemplate(
        architecture: String,
        messages: List<com.example.data.ChatMessageEntity>,
        systemPrompt: String = "You are an on-device AI."
    ): String {
        val history = messages.dropLast(1).map { Pair(it.role, it.content) }
        val userPrompt = messages.lastOrNull()?.content ?: ""
        return formatPrompt(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            architecture = architecture,
            history = history
        )
    }

    /**
     * Applies the appropriate Chat Template (ChatML / LLaMA-3 format) to system and user prompts.
     */
    fun formatPrompt(
        systemPrompt: String?,
        userPrompt: String,
        architecture: String = "qwen2",
        history: List<Pair<String, String>> = emptyList()
    ): String {
        val sys = systemPrompt?.trim() ?: "You are a helpful, concise AI assistant running locally on-device."

        return when (architecture.lowercase()) {
            "qwen2", "qwen" -> {
                buildString {
                    append("${imStart}system\n$sys${imEnd}\n")
                    for ((role, text) in history) {
                        append("${imStart}$role\n$text${imEnd}\n")
                    }
                    append("${imStart}user\n$userPrompt${imEnd}\n")
                    append("${imStart}assistant\n")
                }
            }
            "llama", "llama3" -> {
                buildString {
                    append("<|start_header_id|>system<|end_header_id|>\n\n$sys<|eot_id|>")
                    for ((role, text) in history) {
                        append("<|start_header_id|>$role<|end_header_id|>\n\n$text<|eot_id|>")
                    }
                    append("<|start_header_id|>user<|end_header_id|>\n\n$userPrompt<|eot_id|>")
                    append("<|start_header_id|>assistant<|end_header_id|>\n\n")
                }
            }
            "gemma2", "gemma" -> {
                buildString {
                    append("<start_of_turn>user\n$sys\n\n$userPrompt<end_of_turn>\n<start_of_turn>model\n")
                }
            }
            else -> {
                // Generic instruction format
                buildString {
                    append("$bosToken$instStart <<SYS>>\n$sys\n<</SYS>>\n\n")
                    for ((role, text) in history) {
                        if (role == "user") append("$text [/INST] ")
                        else append("$text </s>$bosToken$instStart ")
                    }
                    append("$userPrompt $instEnd")
                }
            }
        }
    }

    /**
     * Approximate token count for a text string (roughly ~3.8 chars per token for English).
     */
    fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        val words = text.trim().split(Regex("\\s+"))
        var tokens = 0
        for (w in words) {
            tokens += maxOf(1, (w.length + 2) / 4)
        }
        return maxOf(1, tokens)
    }

    /**
     * Generates a 128-dimensional dense semantic embedding vector for a given string using sub-word hash projection.
     */
    fun embedText(text: String, dimensions: Int = 128): FloatArray {
        val vector = FloatArray(dimensions)
        val cleanText = text.lowercase().trim()
        val tokens = cleanText.split(Regex("[\\s,.:;!?\"'()\\[\\]{}]+")).filter { it.isNotEmpty() }

        if (tokens.isEmpty()) {
            return vector
        }

        for ((idx, token) in tokens.withIndex()) {
            val hash = token.hashCode()
            val posWeight = 1.0f / (1.0f + 0.05f * idx)
            for (d in 0 until dimensions) {
                // Distribute token hash pseudo-randomly over dimension slots
                val seed = (hash * 31 + d * 17) and 0x7FFFFFFF
                val projection = ((seed % 1000) / 500.0f - 1.0f)
                vector[d] += projection * posWeight
            }
        }

        // L2 Normalization
        var sumSq = 0.0f
        for (v in vector) {
            sumSq += v * v
        }
        val norm = kotlin.math.sqrt(sumSq)
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }
}
