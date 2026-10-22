package com.example.ui.screens

import com.example.data.ModelEntity
import com.example.engine.HardwareProfile
import com.example.engine.ModelLibrary
import com.example.engine.ModelMemoryPlanner
import java.util.Locale

/** Stable ordering depends on lifecycle, never progress, speed or changing free RAM. */
internal object ModelPresentation {
    fun hasDownload(model: ModelEntity) = model.isInstalled || model.downloadState in setOf("DOWNLOADING", "PAUSED", "ERROR")

    fun ordered(models: List<ModelEntity>, pinned: List<Long>, active: Long?, recommended: Long?): List<ModelEntity> =
        models.sortedWith(compareBy<ModelEntity> {
            when {
                it.id in pinned -> 0
                it.downloadState == "DOWNLOADING" -> 1
                it.isInstalled -> 2
                else -> 3
            }
        }.thenBy { pinned.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
            .thenByDescending { it.id == active }.thenByDescending { it.id == recommended }
            .thenBy { it.fileSizeMb }.thenBy { it.id })

    fun size(mb: Long): String = if (mb >= 1024) String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0) else "$mb MB"

    fun loadContext(model: ModelEntity, requested: Int): Int =
        if (model.filename.endsWith(".litertlm", true)) minOf(requested, model.contextLength) else 512

    fun memoryLabel(model: ModelEntity, profile: HardwareProfile?, selected: Boolean, context: Int = 512): String = when {
        selected -> "Running on this phone"
        profile == null -> "Checking available RAM…"
        profile.cpuAbi !in listOf("arm64-v8a", "x86_64") -> "Not supported on this phone"
        ModelLibrary.memoryEstimate(model, context).totalMb > ModelMemoryPlanner.budget(profile.totalRamMb, profile.totalRamMb) -> "Needs more RAM than this phone has"
        profile.isLowMemory || ModelLibrary.memoryEstimate(model, context).totalMb > profile.safeModelRamLimitMb -> "Free some RAM to use this model"
        else -> "Enough RAM to use this model"
    }

    fun status(model: ModelEntity): String = when {
        model.isInstalled -> "Downloaded · Ready to use"
        model.downloadState == "DOWNLOADING" && model.checksumVerificationStatus == "VERIFYING" -> "Checking file before installing…"
        model.downloadState == "DOWNLOADING" -> "Downloading ${(model.downloadProgress.coerceIn(0f, .99f) * 100).toInt()}%"
        model.downloadState == "PAUSED" -> "Paused · ${(model.downloadProgress.coerceIn(0f, .99f) * 100).toInt()}% saved"
        model.downloadState == "ERROR" -> "Download interrupted · Tap Retry"
        else -> "Available to download"
    }
}
