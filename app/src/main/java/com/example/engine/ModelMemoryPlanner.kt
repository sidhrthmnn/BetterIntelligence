package com.example.engine

import com.example.data.ModelEntity
import kotlin.math.ceil

data class ModelMemoryEstimate(val weightsMb: Long, val kvMb: Long, val overheadMb: Long, val gpuOverheadMb: Long) {
    val totalMb get() = weightsMb + kvMb + overheadMb + gpuOverheadMb
}

enum class ModelTierRole { PRIMARY, PRIMARY_STEPPED_DOWN, SECONDARY_FALLBACK, NONE }

data class ModelTierDecision(
    val role: ModelTierRole,
    val model: ModelEntity?,
    val effectiveContext: Int,
    val estimatedRamMb: Long,
    val safeBudgetMb: Long,
    val reason: String = ""
)

/** Estimates, not allocation guarantees. Quantisation is already reflected in
 * GGUF byte size. F16 K/V sizing uses the model's actual attention dimensions.
 * Offloading moves weights within shared RAM; it does not create more memory.
 */
object ModelMemoryPlanner {
    fun liteRtConfigurationKey(mode: ProcessingMode, threads: Int, context: Int) =
        "$mode:${if (mode == ProcessingMode.CPU) threads else 0}:$context"
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

    /** Picks the smallest installed non-experimental model as a secondary fallback candidate. */
    fun pickDefaultSecondary(models: List<ModelEntity>, primary: ModelEntity?): ModelEntity? {
        val primaryRam = primary?.let { ModelLibrary.memoryEstimate(it, 512).totalMb } ?: Long.MAX_VALUE
        return models.filter {
            it.isInstalled && it.id != primary?.id && ModelLibrary.category(it) != "EXPERIMENTAL" &&
                ModelLibrary.memoryEstimate(it, 512).totalMb < primaryRam
        }.minWithOrNull(
            compareBy<ModelEntity> {
                when (ModelLibrary.category(it)) {
                    "FAST" -> 0
                    "BALANCED" -> 1
                    else -> 2
                }
            }.thenBy { ModelLibrary.memoryEstimate(it, 512).totalMb }.thenBy { it.fileSizeMb }
        )
    }

    /** Resolves whether to run the Primary model at full context, step down its context, or fail over to the Secondary model. */
    fun resolveTier(
        primary: ModelEntity?,
        secondary: ModelEntity?,
        profile: HardwareProfile,
        requestedContext: Int = 2048,
        autoFallback: Boolean = true
    ): ModelTierDecision {
        val targetContext = requestedContext.coerceIn(512, 4096)
        if (primary != null && primary.isInstalled) {
            val configured = ModelLibrary.configuredContext(primary, targetContext)
            if (ModelLibrary.fits(primary, profile, configured)) {
                val est = ModelLibrary.memoryEstimate(primary, configured).totalMb
                return ModelTierDecision(ModelTierRole.PRIMARY, primary, configured, est, profile.safeModelRamLimitMb)
            }
            val steppedContexts = listOf(1024, 512).map { ModelLibrary.configuredContext(primary, it) }.filter { it < configured }.distinct()
            for (stepped in steppedContexts) {
                if (ModelLibrary.fits(primary, profile, stepped)) {
                    val est = ModelLibrary.memoryEstimate(primary, stepped).totalMb
                    return ModelTierDecision(
                        ModelTierRole.PRIMARY_STEPPED_DOWN,
                        primary,
                        stepped,
                        est,
                        profile.safeModelRamLimitMb,
                        "Context stepped down to $stepped tokens to fit ${profile.safeModelRamLimitMb} MB available RAM budget."
                    )
                }
            }
        }
        if (autoFallback && secondary != null && secondary.isInstalled && secondary.id != primary?.id) {
            val candidateContexts = listOf(targetContext, 1024, 512).map { ModelLibrary.configuredContext(secondary, it) }.distinct().sortedDescending()
            for (ctx in candidateContexts) {
                if (ModelLibrary.fits(secondary, profile, ctx)) {
                    val est = ModelLibrary.memoryEstimate(secondary, ctx).totalMb
                    val shortName = secondary.name.substringBefore(" · ")
                    return ModelTierDecision(
                        ModelTierRole.SECONDARY_FALLBACK,
                        secondary,
                        ctx,
                        est,
                        profile.safeModelRamLimitMb,
                        "Switched to secondary model ($shortName) for current RAM (${profile.availableRamMb} MB free)."
                    )
                }
            }
        }
        val fallbackTarget = primary ?: secondary
        val reason = fallbackTarget?.let { ModelLibrary.incompatibilityReason(it, profile, 512).orEmpty() }
            ?: "Select a downloaded model in Models first."
        val est = fallbackTarget?.let { ModelLibrary.memoryEstimate(it, 512).totalMb } ?: 0L
        return ModelTierDecision(ModelTierRole.NONE, fallbackTarget, 512, est, profile.safeModelRamLimitMb, reason)
    }
}
