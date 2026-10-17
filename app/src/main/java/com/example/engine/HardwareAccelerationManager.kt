package com.example.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

enum class ProcessingMode(val title: String) {
    AUTO("Automatic (Recommended)"), CPU("CPU Only"), GPU("GPU Acceleration"), NPU("NPU Acceleration")
}

data class AccelerationCapabilities(
    val cpuAbi: String = Build.SUPPORTED_ABIS?.firstOrNull().orEmpty(),
    val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    val cpuInstructions: String = "", val cpuBuild: String = "",
    val gpuAvailable: Boolean = false, val gpuName: String = "",
    val gpuReason: String = "Checking packaged Vulkan runtime…",
    val gpuFreeBytes: Long = 0, val gpuTotalBytes: Long = 0,
    val npuAvailable: Boolean = false,
    val npuReason: String = "GGUF is not an NPU format. A compatible LiteRT-LM runtime, vendor driver and compiled model are not packaged."
)

data class ResourceState(
    val totalMb: Long = 0, val availableMb: Long = 8192, val lowMemory: Boolean = false,
    val thermal: Int = 0, val thermalLabel: String = "Unavailable", val batteryC: Float = 0f, val powerSave: Boolean = false
)

/** Only native registration + logical device creation establishes GPU availability.
 * Model compatibility additionally requires a real load/decode in InferenceEngine.
 */
class HardwareAccelerationManager(private val context: Context? = null) {
    private val preferences = context?.getSharedPreferences("hardware_acceleration", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(runCatching { ProcessingMode.valueOf(preferences?.getString("mode", null) ?: "AUTO") }.getOrDefault(ProcessingMode.AUTO))
    val mode = _mode.asStateFlow()
    private val _layerLimit = MutableStateFlow(preferences?.getInt("gpu_layers", -1) ?: -1)
    val layerLimit = _layerLimit.asStateFlow()
    private val _capabilities = MutableStateFlow(AccelerationCapabilities())
    val capabilities = _capabilities.asStateFlow()
    private val performanceCores: Int = detectPerformanceCores()
    internal fun setMode(mode: ProcessingMode, layers: Int = layerLimit.value) {
        require(mode != ProcessingMode.NPU) { capabilities.value.npuReason }
        require(mode != ProcessingMode.GPU || capabilities.value.gpuAvailable) { capabilities.value.gpuReason }
        _mode.value = mode; _layerLimit.value = layers.coerceIn(-1, 999)
        preferences?.edit()?.putString("mode", mode.name)?.putInt("gpu_layers", _layerLimit.value)?.apply()
    }
    internal fun updateCapabilities(json: String) {
        val j = JSONObject(json)
        val flags = runCatching { File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("Features") || it.startsWith("flags") }?.substringAfter(':')?.trim().orEmpty()
        } }.getOrDefault("")
        _capabilities.value = AccelerationCapabilities(cpuInstructions = flags, cpuBuild = j.optString("cpuBuild"),
            gpuAvailable = j.optBoolean("gpuAvailable"), gpuName = j.optString("gpuName"), gpuReason = j.optString("gpuReason"),
            gpuFreeBytes = j.optLong("gpuFreeBytes"), gpuTotalBytes = j.optLong("gpuTotalBytes"))
    }
    internal fun markUnavailable(reason: String) { _capabilities.value = _capabilities.value.copy(gpuAvailable = false, gpuReason = reason) }
    fun resources(): ResourceState {
        val ctx = context ?: return ResourceState()
        val ram = HardwareAnalyzer.detectRam(ctx)
        val thermal = HardwareAnalyzer.detectThermalStatus(ctx)
        return ResourceState(ram.totalRamMb, ram.availableRamMb, ram.isLowMemory, thermal.statusCode, thermal.status,
            thermal.batteryTemperatureC, (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true)
    }
    fun threads(requested: Int = 0, state: ResourceState = resources()): Int =
        ResourcePolicy.threads(capabilities.value.cores, performanceCores, requested, state)

    private fun detectPerformanceCores(): Int {
        val frequencies = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { core ->
            runCatching { File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        val max = frequencies.maxOrNull() ?: return 0
        return if (frequencies.distinct().size > 1) frequencies.count { it >= max * 0.85 } else 0
    }
}

object ResourcePolicy {
    fun threads(cores: Int, performanceCores: Int, requested: Int, state: ResourceState): Int {
        val automatic = (if (performanceCores > 0) performanceCores else ceil(cores / 2.0).toInt()).coerceIn(1, 4)
        val normal = (if (requested > 0) requested else automatic).coerceIn(1, minOf(8, cores.coerceAtLeast(1)))
        return if (state.thermal >= 3 || state.powerSave) maxOf(1, normal / 2) else normal
    }
    fun gpuLayers(bytes: Long, blocks: Int, capabilities: AccelerationCapabilities, state: ResourceState, limit: Int): Int {
        if (!capabilities.gpuAvailable || state.lowMemory || state.availableMb < 768 || state.thermal >= 3 || bytes <= 0) return 0
        // Vulkan heaps on phones are usually shared RAM, NOT extra dedicated VRAM.
        val heap = when {
            capabilities.gpuFreeBytes > 0 -> capabilities.gpuFreeBytes
            capabilities.gpuTotalBytes > 0 -> 0L // Measured exhausted heap, not an unknown budget.
            else -> state.availableMb * 1024 * 1024
        }
        val budget = minOf((heap * 0.55).toLong(), ((state.availableMb - 512).coerceAtLeast(0) * 1024 * 1024 * 0.65).toLong())
        val total = (blocks + 1).coerceAtLeast(1)
        val perLayer = (bytes * 1.2 / total).toLong().coerceAtLeast(1)
        val layers = ((budget - 128 * 1024 * 1024).coerceAtLeast(0) / perLayer).coerceAtMost(total.toLong()).toInt()
        val bounded = minOf(layers, if (limit < 0) total else limit)
        // Avoid re-benchmarking for a one-layer fluctuation in reported free RAM.
        return if (bounded < total && bounded >= 4) bounded / 4 * 4 else bounded
    }
}
