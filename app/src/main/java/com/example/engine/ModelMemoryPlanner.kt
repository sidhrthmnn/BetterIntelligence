package com.example.engine

import kotlin.math.ceil

data class ModelMemoryEstimate(val weightsMb: Long, val kvMb: Long, val overheadMb: Long, val gpuOverheadMb: Long) {
    val totalMb get() = weightsMb + kvMb + overheadMb + gpuOverheadMb
}

/** Estimates, not allocation guarantees. Quantisation is already reflected in
 * GGUF byte size. F16 K/V sizing uses the model's actual attention dimensions.
 * Offloading moves weights within shared RAM; it does not create more memory.
 */
object ModelMemoryPlanner {
    fun liteRtAllocationContext(requested: Int, maximum: Int, dynamic: Boolean): Int =
        if (dynamic) minOf(requested, maximum.takeIf { it > 0 } ?: requested)
        else maximum.takeIf { it > 0 } ?: maxOf(requested, 2048)
    // Android's available-memory signal already includes reclaimable file-backed
    // CPU mmap pages. Only estimate credit for GPU weights that will be released.
    fun releasableGpuWeightsMb(meta: GgufMetadata, layers: Int): Long =
        (meta.totalSizeBytes / 1048576.0 * layers.coerceIn(0, meta.blockCount + 1) / (meta.blockCount + 1).coerceAtLeast(1)).toLong()
    fun estimate(bytes: Long, context: Int, kvBytesPerToken: Long, gpuLayers: Int = 0, blocks: Int = 0): ModelMemoryEstimate {
        val weights = ceil(bytes / 1048576.0).toLong()
        val kv = ceil(kvBytesPerToken.coerceAtLeast(0) * context.coerceIn(512, 131072) / 1048576.0).toLong()
        val graphAndRuntime = 256L + (weights * 0.08).toLong()
        val fraction = if (blocks > 0) gpuLayers.coerceIn(0, blocks + 1).toDouble() / (blocks + 1) else 0.0
        val gpu = if (gpuLayers > 0) 128 + ceil(weights * fraction * 0.15).toLong() else 0
        return ModelMemoryEstimate(weights, kv, graphAndRuntime, gpu)
    }
    fun kvBytesPerToken(meta: GgufMetadata): Long {
        val keyLength = meta.customKvPairs["${meta.architecture}.attention.key_length"]?.toLongOrNull()
            ?: (meta.embeddingLength / meta.headCount.coerceAtLeast(1)).toLong()
        val valueLength = meta.customKvPairs["${meta.architecture}.attention.value_length"]?.toLongOrNull() ?: keyLength
        return meta.blockCount.toLong() * meta.headCountKv.coerceAtLeast(1) * (keyLength + valueLength) * 2 // F16
    }
    fun estimate(meta: GgufMetadata, context: Int, gpuLayers: Int = 0) =
        estimate(meta.totalSizeBytes, context, kvBytesPerToken(meta), gpuLayers, meta.blockCount)
    fun budget(totalMb: Long, availableMb: Long): Long =
        minOf((totalMb - maxOf(512, (totalMb * 0.12).toLong())).coerceAtLeast(0), (availableMb - 512).coerceAtLeast(0))
}
