package com.example.ui.screens

import com.example.data.ModelEntity
import com.example.engine.HardwareProfile
import com.example.engine.ModelLibrary
import com.example.engine.ModelMemoryPlanner
import com.example.tasks.CapabilitySettings
import com.example.tasks.TaskModels
import com.example.tasks.TaskType
import java.util.Locale

/** Stable ordering depends on lifecycle, never progress, speed or changing free RAM. */
internal object ModelPresentation {
    const val ALL = "All"
    private val transferStates = setOf("DOWNLOADING", "QUEUED", "PAUSED", "ERROR")
    private val activeTransferStates = setOf("DOWNLOADING", "QUEUED")

    fun byRam(models: List<ModelEntity>, requestedContext: Int, filter: String = ALL): List<ModelEntity> = models.sortedWith(
        compareBy<ModelEntity> { estimatedRam(it, requestedContext, filter) }
            .thenBy { it.name }.thenBy { it.id })

    fun hasTransfer(model: ModelEntity) = !model.isInstalled && model.downloadState in transferStates
    fun transferring(model: ModelEntity) = !model.isInstalled && model.downloadState in activeTransferStates

    fun transfers(models: List<ModelEntity>, recent: List<Long>): List<ModelEntity> = models
        .filter { hasTransfer(it) || it.isInstalled && it.id in recent }
        .sortedWith(compareBy<ModelEntity> { if (it.isInstalled) 1 else 0 }
            .thenBy { recent.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
            .thenBy { it.id })

    fun label(type: TaskType): String = if (type == TaskType.EMBEDDINGS) type.label else type.filter

    fun matches(model: ModelEntity, filter: String): Boolean = filter == ALL ||
        TaskModels.capabilities(model).any { label(it) == filter }

    fun estimatedRam(model: ModelEntity, requestedContext: Int, filter: String = ALL): Long {
        val types = TaskModels.capabilities(model)
        val task = types.firstOrNull { filter != ALL && label(it) == filter } ?: types.firstOrNull()
        return when (task) {
            in TaskModels.mediaTypes, TaskType.EMBEDDINGS -> TaskModels.estimateMb(model, checkNotNull(task))
            else -> ModelLibrary.memoryEstimate(model, loadContext(model, requestedContext)).totalMb
        }
    }

    data class Assignment(val type: TaskType, val fallback: Boolean, val enabled: Boolean)

    fun assignments(model: ModelEntity, settings: Map<TaskType, CapabilitySettings>): List<Assignment> =
        TaskType.entries.filter { it in TaskModels.capabilities(model) }.mapNotNull { type ->
            val setting = settings[type] ?: return@mapNotNull null
            when (model.id) {
                setting.primaryId -> Assignment(type, fallback = false, enabled = setting.enabled)
                setting.fallbackId -> Assignment(type, fallback = true, enabled = setting.enabled)
                else -> null
            }
        }

    fun size(mb: Long): String = if (mb >= 1024) String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0) else "$mb MB"

    fun loadContext(model: ModelEntity, requested: Int): Int =
        if (model.filename.endsWith(".litertlm", true)) minOf(requested, model.contextLength) else 512

    fun memoryLabel(requiredMb: Long, profile: HardwareProfile?, selected: Boolean): String = when {
        selected -> "In use · Ready"
        profile == null -> "Checking available RAM…"
        profile.cpuAbi !in listOf("arm64-v8a", "x86_64") -> "Can't run · Needs a supported 64-bit device"
        requiredMb > ModelMemoryPlanner.budget(profile.totalRamMb, profile.totalRamMb) -> "Can't run · Needs more RAM than this phone has"
        profile.isLowMemory || requiredMb > profile.safeModelRamLimitMb -> "Can't run now · Close other apps to free RAM"
        else -> "Can run · Fits available RAM"
    }

    fun status(model: ModelEntity): String = when {
        model.isInstalled -> "Downloaded · Ready to use"
        model.downloadState == "DOWNLOADING" && model.checksumVerificationStatus == "VERIFYING" -> "Checking file before installing…"
        model.downloadState == "DOWNLOADING" -> "Downloading ${(model.downloadProgress.coerceIn(0f, .99f) * 100).toInt()}%"
        model.downloadState == "QUEUED" -> "Queued · ${(model.downloadProgress.coerceIn(0f, .99f) * 100).toInt()}% saved"
        model.downloadState == "PAUSED" -> "Paused · ${(model.downloadProgress.coerceIn(0f, .99f) * 100).toInt()}% saved"
        model.downloadState == "ERROR" -> "Download interrupted · Tap Retry"
        else -> "Available to download"
    }
}
