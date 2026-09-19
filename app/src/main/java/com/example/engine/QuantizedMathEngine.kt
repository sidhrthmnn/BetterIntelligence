package com.example.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * High-performance on-device tensor and quantization mathematics engine.
 * Emulates the GGML matrix-vector multiplications, block dequantizations (Q4_0, Q4_K, Q8_0),
 * RMSNorm, Softmax, and temperature/top-p nucleus sampling algorithms.
 */
object QuantizedMathEngine {

    /**
     * Converts a 16-bit half precision float (IEEE 754) to 32-bit single precision float.
     */
    fun fp16ToFp32(hbits: Short): Float {
        var mant = hbits.toInt() and 0x03ff
        var exp = hbits.toInt() and 0x7c00
        if (exp == 0x7c00) {
            exp = 0x3fc00
        } else if (exp != 0) {
            exp += 0x1c000
        } else if (mant != 0) {
            exp = 0x1c400
            do {
                mant = mant shl 1
                exp -= 0x400
            } while ((mant and 0x0400) == 0)
            mant = mant and 0x03ff
        }
        return Float.fromBits(((hbits.toInt() and 0x8000) shl 16) or ((exp or mant) shl 13))
    }

    /**
     * Dequantizes a standard GGUF Q4_0 block including the leading 2-byte FP16 scale factor.
     */
    fun dequantizeQ4_0Block(block: ByteArray, blockOffset: Int = 0, output: FloatArray, outOffset: Int = 0) {
        val scaleRaw = ((block[blockOffset].toInt() and 0xFF) or ((block[blockOffset + 1].toInt() and 0xFF) shl 8)).toShort()
        val scale = fp16ToFp32(scaleRaw)
        val blockBytes = block.copyOfRange(blockOffset + 2, blockOffset + 18)
        dequantizeQ4_0(blockBytes, scale, output, outOffset)
    }

    /**
     * Dequantizes a Q4_0 block (32 4-bit weights + 1 FP16 scale factor).
     */
    fun dequantizeQ4_0(blockBytes: ByteArray, scaleFp16: Float, outFloats: FloatArray, outOffset: Int) {
        // In Q4_0, each byte holds 2 4-bit nibbles centered around 8
        for (i in 0 until 16) {
            val byteVal = blockBytes[i].toInt() and 0xFF
            val lowNibble = (byteVal and 0x0F) - 8
            val highNibble = ((byteVal ushr 4) and 0x0F) - 8
            outFloats[outOffset + i] = lowNibble * scaleFp16
            outFloats[outOffset + i + 16] = highNibble * scaleFp16
        }
    }

    /**
     * Dequantizes a Q8_0 block (32 8-bit quantized weights + 1 FP16 scale factor).
     */
    fun dequantizeQ8_0(blockBytes: ByteArray, scaleFp16: Float, outFloats: FloatArray, outOffset: Int) {
        for (i in 0 until 32) {
            val signedByte = blockBytes[i].toInt()
            outFloats[outOffset + i] = signedByte * scaleFp16
        }
    }

    /**
     * Vector dot product with SIMD vectorization emulation.
     */
    fun dotProduct(a: FloatArray, b: FloatArray, length: Int = a.size): Float {
        var sum = 0.0f
        var i = 0
        // Unroll loop 4x for CPU pipelining
        val limit = length - 3
        while (i < limit) {
            sum += a[i] * b[i] + a[i + 1] * b[i + 1] + a[i + 2] * b[i + 2] + a[i + 3] * b[i + 3]
            i += 4
        }
        while (i < length) {
            sum += a[i] * b[i]
            i++
        }
        return sum
    }

    /**
     * Root Mean Square Normalization (RMSNorm) used in LLaMA / Qwen / Gemma models.
     */
    fun rmsNorm(input: FloatArray, weights: FloatArray, eps: Float = 1e-5f): FloatArray {
        var sumSq = 0.0f
        for (x in input) {
            sumSq += x * x
        }
        val meanSq = sumSq / input.size
        val scale = 1.0f / sqrt(meanSq + eps)
        val output = FloatArray(input.size)
        for (i in input.indices) {
            output[i] = input[i] * scale * weights[i]
        }
        return output
    }

    /**
     * Numerically stable Softmax function with temperature scaling.
     */
    fun softmax(logits: FloatArray, temperature: Float = 1.0f): FloatArray {
        val temp = max(0.01f, temperature)
        var maxLogit = Float.NEGATIVE_INFINITY
        for (x in logits) {
            if (x > maxLogit) maxLogit = x
        }

        val exps = FloatArray(logits.size)
        var sum = 0.0f
        for (i in logits.indices) {
            val e = exp((logits[i] - maxLogit) / temp)
            exps[i] = e
            sum += e
        }

        if (sum > 0f) {
            for (i in exps.indices) {
                exps[i] /= sum
            }
        }
        return exps
    }

    /**
     * Top-P (Nucleus) and Top-K sampling with temperature and repetition penalty.
     */
    fun sampleNextToken(
        logits: FloatArray,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repetitionPenalty: Float = 1.1f,
        previousTokenIds: List<Int> = emptyList()
    ): Int {
        // 1. Apply repetition penalty
        val modifiedLogits = logits.clone()
        for (prevId in previousTokenIds) {
            if (prevId in modifiedLogits.indices) {
                if (modifiedLogits[prevId] > 0) {
                    modifiedLogits[prevId] /= repetitionPenalty
                } else {
                    modifiedLogits[prevId] *= repetitionPenalty
                }
            }
        }

        // Greedy decoding if temperature is near zero
        if (temperature <= 0.05f) {
            var bestIdx = 0
            var bestVal = modifiedLogits[0]
            for (i in 1 until modifiedLogits.size) {
                if (modifiedLogits[i] > bestVal) {
                    bestVal = modifiedLogits[i]
                    bestIdx = i
                }
            }
            return bestIdx
        }

        // 2. Softmax probabilities
        val probs = softmax(modifiedLogits, temperature)

        // 3. Top-K filtering
        val indexedProbs = probs.mapIndexed { idx, prob -> Pair(idx, prob) }
            .sortedByDescending { it.second }
            .take(topK)

        // 4. Top-P (Nucleus) filtering
        var cumSum = 0.0f
        val nucleus = mutableListOf<Pair<Int, Float>>()
        for (item in indexedProbs) {
            nucleus.add(item)
            cumSum += item.second
            if (cumSum >= topP && nucleus.size > 1) {
                break
            }
        }

        // 5. Categorical random sample
        val randomVal = Random.nextFloat() * cumSum
        var runningSum = 0.0f
        for (item in nucleus) {
            runningSum += item.second
            if (randomVal <= runningSum) {
                return item.first
            }
        }

        return nucleus.firstOrNull()?.first ?: 0
    }

    /**
     * Computes Cosine Similarity between two semantic embedding vectors.
     */
    fun cosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.size != vecB.size || vecA.isEmpty()) return 0.0f
        var dot = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in vecA.indices) {
            dot += vecA[i] * vecB[i]
            normA += vecA[i] * vecA[i]
            normB += vecB[i] * vecB[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0.0f
    }
}
