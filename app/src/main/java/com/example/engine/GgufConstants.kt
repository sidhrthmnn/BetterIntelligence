package com.example.engine

/**
 * Constants and types defined by the GGUF specification and GGML quantization formats.
 */
object GgufConstants {
    // Magic constant "GGUF" in ASCII (0x46554747 in little-endian)
    const val GGUF_MAGIC = 0x46554747
    const val GGUF_VERSION_2 = 2
    const val GGUF_VERSION_3 = 3

    // Metadata value types
    const val GGUF_TYPE_UINT8 = 0
    const val GGUF_TYPE_INT8 = 1
    const val GGUF_TYPE_UINT16 = 2
    const val GGUF_TYPE_INT16 = 3
    const val GGUF_TYPE_UINT32 = 4
    const val GGUF_TYPE_INT32 = 5
    const val GGUF_TYPE_FLOAT32 = 6
    const val GGUF_TYPE_BOOL = 7
    const val GGUF_TYPE_STRING = 8
    const val GGUF_TYPE_ARRAY = 9
    const val GGUF_TYPE_UINT64 = 10
    const val GGUF_TYPE_INT64 = 11
    const val GGUF_TYPE_FLOAT64 = 12
}

/**
 * GGML Quantization types supported in modern GGUF models.
 */
enum class QuantizationType(
    val typeId: Int,
    val typeName: String,
    val bitsPerWeight: Float,
    val blockSize: Int,
    val description: String
) {
    F32(0, "F32", 32.0f, 1, "Full 32-bit floating point precision"),
    F16(1, "F16", 16.0f, 1, "Half 16-bit floating point precision"),
    Q4_0(2, "Q4_0", 4.5f, 32, "Standard 4-bit quantization with FP16 block scale"),
    Q4_1(3, "Q4_1", 5.0f, 32, "4-bit quantization with FP16 scale and min offset"),
    Q5_0(6, "Q5_0", 5.5f, 32, "5-bit quantization with FP16 block scale"),
    Q5_1(7, "Q5_1", 6.0f, 32, "5-bit quantization with FP16 scale and min offset"),
    Q8_0(8, "Q8_0", 8.5f, 32, "8-bit quantization with FP16 block scale (high accuracy)"),
    Q8_1(9, "Q8_1", 9.0f, 32, "8-bit quantization with scale and minimum offset"),
    Q2_K(10, "Q2_K", 2.56f, 256, "2-bit K-quantization for extreme low memory"),
    Q3_K_S(11, "Q3_K_S", 3.44f, 256, "3-bit K-quant small"),
    Q3_K_M(12, "Q3_K_M", 3.91f, 256, "3-bit K-quant medium"),
    Q4_K_S(14, "Q4_K_S", 4.5f, 256, "4-bit K-quant small - balanced speed/RAM"),
    Q4_K_M(15, "Q4_K_M", 4.85f, 256, "4-bit K-quant medium - optimal for mobile LLM"),
    Q5_K_M(17, "Q5_K_M", 5.5f, 256, "5-bit K-quant medium - near-lossless 16-bit"),
    Q6_K(18, "Q6_K", 6.56f, 256, "6-bit K-quant - pristine quality"),
    IQ3_XXS(19, "IQ3_XXS", 3.06f, 256, "3-bit Importance Matrix quantization"),
    UNKNOWN(-1, "UNKNOWN", 4.0f, 32, "Unknown or custom quantization");

    companion object {
        fun fromTypeId(id: Int): QuantizationType {
            return entries.find { it.typeId == id } ?: UNKNOWN
        }

        fun fromString(name: String): QuantizationType {
            val upper = name.uppercase()
            return entries.find { upper.contains(it.typeName) } ?: Q4_K_M
        }
    }
}
