package com.example.engine

/**
 * Information about an individual tensor in a GGUF file.
 */
data class GgufTensorInfo(
    val name: String,
    val dimensions: LongArray,
    val type: QuantizationType,
    val offset: Long,
    val sizeBytes: Long
) {
    val dimensionString: String
        get() = dimensions.joinToString(" × ")

    val readableSize: String
        get() {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) String.format("%.2f MB", mb)
            else String.format("%.1f KB", sizeBytes / 1024.0)
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GgufTensorInfo
        return name == other.name && dimensions.contentEquals(other.dimensions) && type == other.type && offset == other.offset
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + dimensions.contentHashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + offset.hashCode()
        return result
    }
}

/**
 * Parsed header and KV metadata from a GGUF file.
 */
data class GgufMetadata(
    val version: Int,
    val tensorCount: Long,
    val kvCount: Long,
    val architecture: String,
    val modelName: String,
    val contextLength: Int,
    val embeddingLength: Int,
    val blockCount: Int,
    val headCount: Int,
    val headCountKv: Int,
    val vocabSize: Int,
    val quantizationVersion: Int,
    val primaryQuantization: QuantizationType,
    val ropeFreqBase: Float,
    val feedForwardLength: Int,
    val customKvPairs: Map<String, String>,
    val tensors: List<GgufTensorInfo> = emptyList(),
    val totalSizeBytes: Long = 0L
) {
    val totalSizeMb: Float
        get() = (totalSizeBytes / (1024f * 1024f))

    val ramFootprintEstimateMb: Float
        get() = totalSizeMb * 1.15f + (contextLength * embeddingLength * 4f / (1024f * 1024f))

    val formattedArchitecture: String
        get() = when (architecture.lowercase()) {
            "llama" -> "LLaMA 3 / 2 (Meta)"
            "qwen2" -> "Qwen 2.5 / 2 (Alibaba)"
            "phi3" -> "Phi-3.5 / 3 (Microsoft)"
            "gemma2", "gemma" -> "Gemma 2 / 1 (Google)"
            "tinyllama" -> "TinyLlama (Compact)"
            "mistral" -> "Mistral / Mixtral"
            else -> architecture.replaceFirstChar { it.uppercase() }
        }
}
