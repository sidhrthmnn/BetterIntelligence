package com.example.engine

import android.content.Context
import com.example.data.ModelEntity
import org.json.JSONArray

object ModelLibrary {
    private val shapes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()
    private val textWeights = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val identityShapes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()
    private val identityWeights = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val fixedContexts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val runtimeContexts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val categories = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun category(model: ModelEntity): String? = model.sha256Checksum?.let(categories::get)
    fun categoryLabel(model: ModelEntity): String? = when (category(model)) {
        "FAST" -> "Fast"
        "BALANCED" -> "Balanced"
        "ADVANCED" -> "Advanced"
        else -> null
    }
    fun recordMetadata(filename: String, metadata: GgufMetadata) {
        shapes[filename] = ModelMemoryPlanner.kvBytesPerToken(metadata) to metadata.blockCount
    }
    fun read(context: Context): List<ModelEntity> {
        val primary = readAsset(context, "model-library.json", true)
        readAsset(context, "model-research.json", false) // Memory profiles also apply to renamed verified imports.
        return primary
    }
    fun inferenceModel(context: Context, filename: String, sha256: String = ""): ModelEntity? {
        fun matches(model: ModelEntity) = if (sha256.isNotBlank()) model.sha256Checksum == sha256 else model.filename == filename
        return read(context).firstOrNull(::matches) ?: readAsset(context, "model-research.json", false).firstOrNull(::matches)
    }

    fun liteRtGpuRestriction(sha256: String): String = when (sha256) {
        // This exact mobile export's publisher reports a Qualcomm GPU hard reboot.
        // Keep its CPU graph usable; lifting this requires a fixed package/runtime
        // and actual Android validation, not a change of filename or device brand.
        // https://huggingface.co/litert-community/Qwen3.5-4B
        "7243c33c9c6192150ab6da7ac13a9efdc24ff1a653eabc93b2757de4b44b012a" ->
            "GPU is disabled for this Qwen 3.5 4B export on the packaged LiteRT-LM 0.18 runtime because its publisher reports a device reboot. CPU inference remains available."
        else -> ""
    }

    private fun readAsset(context: Context, asset: String, primary: Boolean): List<ModelEntity> {
        val list = JSONArray(context.assets.open(asset).bufferedReader().use { it.readText() })
        return (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            if (item.has("textWeightsBytes")) textWeights[item.getString("filename")] = item.getLong("textWeightsBytes")
            shapes[item.getString("filename")] = item.optLong("kvBytesPerToken", 131072L) to item.optInt("layers", 0)
            identityShapes[item.getString("sha256")] = shapes.getValue(item.getString("filename"))
            if (item.has("textWeightsBytes")) identityWeights[item.getString("sha256")] = item.getLong("textWeightsBytes")
            item.optInt("runtimeMaxContext", 0).takeIf { it > 0 }?.let { runtimeContexts[item.getString("sha256")] = it }
            if (primary) item.optString("recommendedMode").takeIf { it in setOf("FAST", "BALANCED", "ADVANCED") }
                ?.let { categories[item.getString("sha256")] = it }
            if (item.getString("filename").endsWith(".litertlm") && !item.optBoolean("dynamicContext", false))
                fixedContexts[item.getString("sha256")] = item.optInt("runtimeMaxContext", 0).takeIf { it > 0 } ?: 2048
            ModelEntity(name = item.getString("name"), filename = item.getString("filename"),
                architecture = item.getString("architecture"), quantization = item.optString("quantization", "Q4_K_M"),
                parameterCount = item.getString("parameters"), contextLength = item.optInt("context", 2048),
                fileSizeMb = (item.getLong("bytes") + 1048575) / 1048576,
                ramRequiredMb = item.getInt("ramMb"), isInstalled = false, isActive = false, isCustom = false,
                description = item.getString("description") + " License: ${item.getString("license")}. Memory is an estimate.",
                capabilities = "Chat, Text editing, Summarization", downloadUrl = item.getString("url"),
                huggingFaceRepo = item.getString("repo"), sha256Checksum = item.getString("sha256"),
                versionTag = item.getString("revision"), speedScoreTokSec = 0f,
                recommendedTier = item.getString("tier"),
                isBaseModel = primary && index == 0)
        }
    }

    fun canDownload(model: ModelEntity, profile: HardwareProfile): Boolean =
        (model.fileSizeMb * (1f - if (model.downloadState in setOf("PAUSED", "ERROR")) model.downloadProgress.coerceIn(0f, .99f) else 0f)).toLong() + 128 <= profile.internalStorageFreeGb * 1024

    fun memoryEstimate(model: ModelEntity, context: Int = 2048, gpuLayers: Int = 0): ModelMemoryEstimate {
        val liteRt = model.filename.endsWith(".litertlm", true)
        val identity = model.sha256Checksum?.takeIf { it.isNotBlank() }
        val shape = if (liteRt && identity != null) identityShapes[identity]
            else identity?.let(identityShapes::get) ?: shapes[model.filename]
        val weights = if (liteRt && identity != null) identityWeights[identity]
            else identity?.let(identityWeights::get) ?: textWeights[model.filename]
        val requested = identity?.let(runtimeContexts::get)?.let { minOf(context, it) } ?: context
        val allocation = maxOf(requested, identity?.let(fixedContexts::get) ?: if (liteRt && shape == null) 2048 else 0)
        // Unknown imports get a conservative KV estimate until their header is parsed on load.
        return ModelMemoryPlanner.estimate(weights ?: (model.fileSizeMb * 1048576), allocation,
            shape?.first ?: 524288L, gpuLayers, shape?.second ?: 0)
    }

    fun incompatibilityReason(model: ModelEntity, profile: HardwareProfile, context: Int = 2048): String? = when {
        !model.filename.endsWith(".gguf", true) && !model.filename.endsWith(".litertlm", true) -> "Select a GGUF or compatible LiteRT-LM instruction model. Raw TFLite and vendor NPU files are not supported."
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
            .minWithOrNull(compareBy<ModelEntity> { when (category(it)) { "BALANCED" -> 0; "FAST" -> 1; "ADVANCED" -> 2; else -> 3 } }
                .thenBy { it.fileSizeMb }) ?: return null
        return ModelRecommendation(selected.name, selected.filename, selected.quantization, 0f,
            profile.safeModelRamLimitMb - memoryEstimate(selected).totalMb,
            (memoryEstimate(selected).totalMb * 100L / profile.availableRamMb.coerceAtLeast(1)).toInt(),
            listOf("Available RAM: ${profile.availableRamMb} MB; model budget: ${profile.safeModelRamLimitMb} MB",
                "Suggested from measured package quality and resource needs; a larger download is not automatically better",
                "Estimated model memory at 2048 context: ${memoryEstimate(selected).totalMb} MB",
                "${profile.cpuAbi}; ${profile.cpuCores} CPU cores; acceleration is selected at runtime",
                "Free storage: ${profile.internalStorageFreeGb} GB. Speed must be measured on this phone."),
            profile.tier, true)
    }
}
