package com.example.engine

import com.example.data.ModelEntity

data class AutomaticModelPair(val primary: ModelEntity?, val secondary: ModelEntity?, val downloadMb: Long,
    val reason: String, val measured: Boolean = false)

/** No speed/quality measurements are invented from parameter count or device brand. */
object AutomaticModelSelector {
    fun billions(model: ModelEntity): Double {
        val value = model.parameterCount.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0.0
        return if (model.parameterCount.endsWith("M", true)) value / 1000.0 else value
    }
    fun candidates(models: List<ModelEntity>, profile: HardwareProfile): List<ModelEntity> = models.filter {
        val sha = it.sha256Checksum.orEmpty()
        sha.matches(Regex("[a-fA-F0-9]{64}")) && billions(it) >= 0.3 && ModelLibrary.automaticUseAllowed(it) &&
            ModelLibrary.category(it) != "EXPERIMENTAL" && !it.architecture.contains("bert", true) &&
            (it.isInstalled || !it.isCustom && it.downloadUrl?.startsWith("https://") == true && it.description.contains("License:")) &&
            ModelLibrary.fits(it, profile, ModelLibrary.configuredContext(it, 1024))
    }
    fun recommend(models: List<ModelEntity>, profile: HardwareProfile): AutomaticModelPair {
        val compatible = candidates(models, profile)
        // Installed useful models first: reuse them instead of downloading rivals for a speculative score.
        val primary = compatible.sortedWith(compareByDescending<ModelEntity> { it.isInstalled }
            .thenBy { when (ModelLibrary.category(it)) { "BALANCED" -> 0; "ADVANCED" -> 1; "FAST" -> 2; else -> 3 } }
            .thenByDescending { billions(it) }).firstOrNull()
        val secondary = compatible.filter { it.id != primary?.id && it.fileSizeMb < (primary?.fileSizeMb ?: Long.MAX_VALUE) &&
            // Tiny diagnostic models remain manually usable, but are not automatically promoted as useful backups.
            billions(it) >= 0.3 }
            .sortedWith(compareByDescending<ModelEntity> { it.isInstalled }.thenBy { ModelLibrary.memoryEstimate(it, 1024).totalMb }).firstOrNull()
        val selected = listOfNotNull(primary, secondary)
        return AutomaticModelPair(primary, secondary, selected.filterNot { it.isInstalled }.sumOf { it.fileSizeMb },
            if (primary == null) "No curated instruction model fits the current RAM, storage and runtime. Free memory and recheck, or choose manually."
            else "Preliminary: compatibility and memory estimates, not a measured quality ranking. Only these selected files will download. Models run one at a time.")
    }
    fun chooseMeasured(models: List<ModelEntity>, reports: List<ModelQualityReport>): AutomaticModelPair {
        val stable = reports.filter { it.passed && it.quality >= 65 }.sortedByDescending { it.score }
        val primary = models.find { it.id == stable.firstOrNull()?.modelId }
        val secondaryReport = stable.drop(1).filter { it.peakMb < (stable.firstOrNull()?.peakMb ?: Long.MAX_VALUE) }.maxByOrNull { it.score }
        return AutomaticModelPair(primary, models.find { it.id == secondaryReport?.modelId }, 0,
            "Selected from actual on-device constraint checks, stability, memory and speed. These checks are not a general intelligence certification.", true)
    }
}
