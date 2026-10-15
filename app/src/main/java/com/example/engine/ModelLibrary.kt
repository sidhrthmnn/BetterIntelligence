package com.example.engine

import android.content.Context
import com.example.data.ModelEntity
import org.json.JSONArray

object ModelLibrary {
    fun read(context: Context): List<ModelEntity> {
        val list = JSONArray(context.assets.open("model-library.json").bufferedReader().use { it.readText() })
        return (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
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

    fun fits(model: ModelEntity, profile: HardwareProfile): Boolean =
        model.filename.endsWith(".gguf", true) && profile.cpuAbi in listOf("arm64-v8a", "x86_64") &&
            !profile.isLowMemory && model.ramRequiredMb <= profile.safeModelRamLimitMb &&
            (model.isInstalled || canDownload(model, profile))

    fun recommend(models: List<ModelEntity>, profile: HardwareProfile): ModelRecommendation? {
        val selected = models.filter { !it.isCustom && fits(it, profile) }
            .maxWithOrNull(compareBy<ModelEntity> { it.ramRequiredMb }.thenBy { it.fileSizeMb }) ?: return null
        return ModelRecommendation(selected.name, selected.filename, selected.quantization, 0f,
            profile.safeModelRamLimitMb - selected.ramRequiredMb,
            (selected.ramRequiredMb * 100L / profile.availableRamMb.coerceAtLeast(1)).toInt(),
            listOf("Available RAM: ${profile.availableRamMb} MB; model budget: ${profile.safeModelRamLimitMb} MB",
                "Estimated model memory including context: ${selected.ramRequiredMb} MB",
                "${profile.cpuAbi}; ${profile.cpuCores} CPU cores; CPU inference",
                "Free storage: ${profile.internalStorageFreeGb} GB. Speed must be measured on this phone."),
            profile.tier, true)
    }
}
