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
                architecture = item.getString("architecture"), quantization = "Q4_K_M",
                parameterCount = item.getString("parameters"), contextLength = 2048,
                fileSizeMb = (item.getLong("bytes") + 1048575) / 1048576,
                ramRequiredMb = item.getInt("ramMb"), isInstalled = false, isActive = false, isCustom = false,
                description = "4-bit instruction model. License: ${item.getString("license")}. Memory is an estimate; performance depends on your phone.",
                capabilities = "Chat, Text editing, Summarization", downloadUrl = item.getString("url"),
                huggingFaceRepo = item.getString("repo"), sha256Checksum = item.getString("sha256"),
                versionTag = item.getString("revision"), speedScoreTokSec = 0f,
                recommendedTier = if (item.getInt("ramMb") <= 1024) "ULTRA_LIGHT" else "BALANCED",
                isBaseModel = index == 0)
        }
    }

    fun fits(model: ModelEntity, profile: HardwareProfile): Boolean =
        model.filename.endsWith(".gguf", true) && profile.cpuAbi in listOf("arm64-v8a", "x86_64") &&
            !profile.isLowMemory && model.ramRequiredMb <= profile.safeModelRamLimitMb &&
            (model.isInstalled || model.fileSizeMb + 128 <= profile.internalStorageFreeGb * 1024)

    fun recommend(models: List<ModelEntity>, profile: HardwareProfile): ModelRecommendation? {
        val selected = models.filter { fits(it, profile) }.maxByOrNull { it.ramRequiredMb } ?: return null
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
