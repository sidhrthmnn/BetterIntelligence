package com.example.engine

import android.content.Context
import com.example.data.ModelEntity
import org.json.JSONArray

object ModelLibrary {
    private val shapes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()
    fun recordMetadata(filename: String, metadata: GgufMetadata) {
        shapes[filename] = ModelMemoryPlanner.kvBytesPerToken(metadata) to metadata.blockCount
    }
    fun read(context: Context): List<ModelEntity> {
        val list = JSONArray(context.assets.open("model-library.json").bufferedReader().use { it.readText() })
        return (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            shapes[item.getString("filename")] = item.optLong("kvBytesPerToken", 131072L) to item.optInt("layers", 0)
            ModelEntity(name = item.getString("name"), filename = item.getString("filename"),
                architecture = item.getString("architecture"), quantization = item.optString("quantization", "Q4_K_M"),
                parameterCount = item.getString("parameters"), contextLength = 2048,
                fileSizeMb = (item.getLong("bytes") + 1048575) / 1048576,
                ramRequiredMb = item.getInt("ramMb"), isInstalled = false, isActive = false, isCustom = false,
                description = item.getString("description") + " License: ${item.getString("license")}. Memory is an estimate.",
                capabilities = "Chat, Text editing, Summarization", downloadUrl = item.getString("url"),
                huggingFaceRepo = item.getString("repo"), sha256Checksum = item.getString("sha256"),
                versionTag = item.getString("revision"), speedScoreTokSec = 0f,
                recommendedTier = item.getString("tier"),
                isBaseModel = index == 0)
        }
    }

    fun canDownload(model: ModelEntity, profile: HardwareProfile): Boolean =
        model.fileSizeMb + 128 <= profile.internalStorageFreeGb * 1024

    fun memoryEstimate(model: ModelEntity, context: Int = 2048, gpuLayers: Int = 0): ModelMemoryEstimate {
        val shape = shapes[model.filename]
        // Unknown imports get a conservative KV estimate until their header is parsed on load.
        return ModelMemoryPlanner.estimate(model.fileSizeMb * 1048576, context, shape?.first ?: 524288L, gpuLayers, shape?.second ?: 0)
    }

    fun incompatibilityReason(model: ModelEntity, profile: HardwareProfile, context: Int = 2048): String? = when {
        !model.filename.endsWith(".gguf", true) -> "This build requires a GGUF instruction model. LiteRT/NPU models are not supported."
        profile.cpuAbi !in listOf("arm64-v8a", "x86_64") -> "The packaged runtime requires arm64-v8a or x86_64; this device reports ${profile.cpuAbi}."
        profile.isLowMemory -> "Android reports low memory (${profile.availableRamMb} MB available). Close other apps and retry."
        memoryEstimate(model, context).totalMb > profile.safeModelRamLimitMb ->
            "Estimated ${memoryEstimate(model, context).totalMb} MB for ${model.quantization} at $context context; ${profile.safeModelRamLimitMb} MB safely available. Free memory or use a smaller context/model."
        !model.isInstalled && !canDownload(model, profile) -> "Not enough free storage for this download."
        else -> null
    }
    fun fits(model: ModelEntity, profile: HardwareProfile, context: Int = 2048): Boolean = incompatibilityReason(model, profile, context) == null

    fun recommend(models: List<ModelEntity>, profile: HardwareProfile): ModelRecommendation? {
        val selected = models.filter { !it.isCustom && fits(it, profile) }
            .maxWithOrNull(compareBy<ModelEntity> { it.fileSizeMb }) ?: return null
        return ModelRecommendation(selected.name, selected.filename, selected.quantization, 0f,
            profile.safeModelRamLimitMb - memoryEstimate(selected).totalMb,
            (memoryEstimate(selected).totalMb * 100L / profile.availableRamMb.coerceAtLeast(1)).toInt(),
            listOf("Available RAM: ${profile.availableRamMb} MB; model budget: ${profile.safeModelRamLimitMb} MB",
                "Estimated model memory at 2048 context: ${memoryEstimate(selected).totalMb} MB",
                "${profile.cpuAbi}; ${profile.cpuCores} CPU cores; acceleration is selected at runtime",
                "Free storage: ${profile.internalStorageFreeGb} GB. Speed must be measured on this phone."),
            profile.tier, true)
    }
}
