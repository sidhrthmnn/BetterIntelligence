package com.example.ui.screens

import com.example.data.ModelEntity
import com.example.engine.HardwareProfile
import com.example.engine.ModelLibrary
import com.example.engine.ModelMemoryPlanner
import java.util.Locale

/** Stable ordering depends on lifecycle, never progress, speed or changing free RAM. */
internal object ModelPresentation {
    fun byRam(models: List<ModelEntity>, requestedContext: Int, pinned: List<Long> = emptyList()): List<ModelEntity> = models.sortedWith(
        compareBy<ModelEntity> { if (it.id in pinned || it.downloadState == "DOWNLOADING") 0 else 1 }
            .thenBy { pinned.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
            .thenBy { ModelLibrary.memoryEstimate(it, loadContext(it, requestedContext)).totalMb }
            .thenBy { it.name }.thenBy { it.id })
    fun hasDownload(model: ModelEntity) = model.isInstalled || model.downloadState in setOf("DOWNLOADING", "PAUSED", "ERROR")

    fun size(mb: Long): String = if (mb >= 1024) String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0) else "$mb MB"

    fun loadContext(model: ModelEntity, requested: Int): Int =
        if (model.filename.endsWith(".litertlm", true)) minOf(requested, model.contextLength) else 512

    fun memoryLabel(model: ModelEntity, profile: HardwareProfile?, selected: Boolean, context: Int = 512): String = when {
        selected -> "In use · Ready"
        profile == null -> "Checking available RAM…"
        profile.cpuAbi !in listOf("arm64-v8a", "x86_64") -> "Can't run · Needs a supported 64-bit device"
        ModelLibrary.memoryEstimate(model, context).totalMb > ModelMemoryPlanner.budget(profile.totalRamMb, profile.totalRamMb) -> "Can't run · Needs more RAM than this phone has"
        profile.isLowMemory || ModelLibrary.memoryEstimate(model, context).totalMb > profile.safeModelRamLimitMb -> "Can't run now · Close other apps to free RAM"
        else -> "Can run · Fits available RAM"
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
