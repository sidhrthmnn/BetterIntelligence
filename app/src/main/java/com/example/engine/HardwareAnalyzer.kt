package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import java.io.File
import kotlin.math.roundToInt

data class HardwareProfile(
    val deviceModel: String,
    val manufacturer: String,
    val androidVersion: String,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val usedRamPercent: Int,
    val isLowMemory: Boolean = false,
    val safeModelRamLimitMb: Long,
    val osReservedRamMb: Long = 3000L,
    val cpuCores: Int,
    val cpuAbi: String,
    val supportsNeon: Boolean,
    val internalStorageFreeGb: Float,
    val internalStorageTotalGb: Float,
    val batteryPercent: Int,
    val batteryTemperatureC: Float = 0f,
    val isCharging: Boolean,
    val thermalStatus: String,
    val thermalStatusCode: Int = 0,
    val isThermalThrottling: Boolean = false,
    val aiCapabilityScore: Int, // 0 - 100
    val tier: DeviceTier
) {
    val availableRamGb: Float get() = availableRamMb / 1024f
    val totalRamGb: Float get() = totalRamMb / 1024f
    val usedRamMb: Long get() = (totalRamMb - availableRamMb).coerceAtLeast(0L)
}

data class RamStatusInfo(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val usedRamMb: Long,
    val usedRamPercent: Int,
    val isLowMemory: Boolean,
    val osReservedRamMb: Long,
    val safeModelRamLimitMb: Long
) {
    val availableRamGb: Float get() = availableRamMb / 1024f
    val totalRamGb: Float get() = totalRamMb / 1024f
}

data class CpuStatusInfo(
    val cores: Int,
    val abi: String,
    val isArm64: Boolean,
    val supportsNeon: Boolean,
    val topologySummary: String
)

data class ThermalStatusInfo(
    val status: String,
    val statusCode: Int,
    val isThrottling: Boolean,
    val description: String,
    val batteryTemperatureC: Float,
    val isCharging: Boolean
)

data class HardwareDiagnosticReport(
    val ram: RamStatusInfo,
    val cpu: CpuStatusInfo,
    val thermal: ThermalStatusInfo,
    val profile: HardwareProfile,
    val isReadyForInference: Boolean,
    val diagnosticBadge: String
)

data class DeviceRamTierGuide(
    val deviceRamRange: String,
    val recommendedModelRange: String,
    val recommendedQuant: String = "Q4_K_M",
    val maxSafeRamMb: Long,
    val description: String
)

val RAM_RECOMMENDATION_TABLE = listOf(
    DeviceRamTierGuide("4 GB", "1B–2B Q4", "Q4_K_M", 1800L, "Ultra-light models like Qwen 2.5 1.5B / Llama 3.2 1B"),
    DeviceRamTierGuide("6 GB", "2B–4B Q4", "Q4_K_M", 3200L, "Balanced models like Llama 3.2 3B / Phi-4-mini 3.8B"),
    DeviceRamTierGuide("8 GB", "3B–7B Q4", "Q4_K_M", 4800L, "Performance tier like Qwen 2.5 7B / DeepSeek R1 7B Q4"),
    DeviceRamTierGuide("12 GB", "7B–9B Q4", "Q4_K_M", 8200L, "High quality models like Gemma 2 9B / Llama 3.1 8B"),
    DeviceRamTierGuide("16 GB+", "9B–14B Q4", "Q4_K_M", 11500L, "Workstation models like Qwen 2.5 14B / Phi-4 14B")
)

enum class DeviceTier(val title: String, val maxParamRecommended: String, val badgeColorHex: Long) {
    ULTRA_LIGHT("Ultra-Compact (4GB)", "1B–2B Q4", 0xFF00E5FF),
    BALANCED("Balanced Mobile (6GB)", "2B–4B Q4", 0xFF10B981),
    PERFORMANCE("Performance AI (8GB)", "3B–7B Q4", 0xFF6366F1),
    FLAGSHIP("Flagship Workstation (12GB+)", "7B–14B Q4", 0xFFA855F7)
}

data class ModelRecommendation(
    val recommendedModelName: String,
    val recommendedFilename: String,
    val recommendedQuant: String,
    val estimatedSpeedTokSec: Float,
    val ramHeadroomMb: Long,
    val ramUsagePercent: Int,
    val justification: List<String>,
    val tier: DeviceTier,
    val isReadyToApply: Boolean
)

object HardwareAnalyzer {

    /**
     * Detects total and available system RAM in Megabytes.
     */
    fun detectRam(context: Context): RamStatusInfo {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val totalRamMb = (memInfo.totalMem / (1024 * 1024)).coerceAtLeast(2048L)
        val availRamMb = (memInfo.availMem / (1024 * 1024)).coerceAtLeast(1024L)
        val usedRamMb = (totalRamMb - availRamMb).coerceAtLeast(0L)
        val usedRamPercent = (((totalRamMb - availRamMb).toDouble() / totalRamMb) * 100).roundToInt().coerceIn(0, 100)

        val osReservedRamMb = (totalRamMb * 0.38).toLong().coerceIn(2000L, 3500L)
        val safeModelRamLimitMb = (totalRamMb - osReservedRamMb).coerceAtLeast(800L)

        return RamStatusInfo(
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            usedRamMb = usedRamMb,
            usedRamPercent = usedRamPercent,
            isLowMemory = memInfo.lowMemory,
            osReservedRamMb = osReservedRamMb,
            safeModelRamLimitMb = safeModelRamLimitMb
        )
    }

    /**
     * Detects CPU architecture, core counts, and SIMD/NEON capabilities.
     */
    fun detectCpu(): CpuStatusInfo {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a"
        val isArm64 = abi.contains("arm64") || abi.contains("aarch64") || abi.contains("x86_64")
        val supportsNeon = isArm64

        val topologySummary = when {
            cores >= 8 -> "Octa-Core Big.LITTLE"
            cores >= 6 -> "Hexa-Core Cluster"
            else -> "Quad-Core"
        }

        return CpuStatusInfo(
            cores = cores,
            abi = abi,
            isArm64 = isArm64,
            supportsNeon = supportsNeon,
            topologySummary = topologySummary
        )
    }

    /**
     * Detects system thermal state and throttling status via PowerManager and BatteryManager.
     */
    fun detectThermalStatus(context: Context): ThermalStatusInfo {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        var statusCode = 0
        var statusLabel = "Nominal / Cool"
        var description = "Thermal state is optimal for sustained on-device LLM inference."
        var isThrottling = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            statusCode = powerManager.currentThermalStatus
            when (statusCode) {
                PowerManager.THERMAL_STATUS_NONE -> {
                    statusLabel = "Nominal / Cool"
                    description = "Normal thermal conditions. Maximum compute clock speed."
                    isThrottling = false
                }
                PowerManager.THERMAL_STATUS_LIGHT -> {
                    statusLabel = "Light Warm"
                    description = "Slight thermal rise. Stable performance."
                    isThrottling = false
                }
                PowerManager.THERMAL_STATUS_MODERATE -> {
                    statusLabel = "Moderate Warm"
                    description = "Noticeable temperature. Mild CPU governor throttling may occur."
                    isThrottling = false
                }
                PowerManager.THERMAL_STATUS_SEVERE -> {
                    statusLabel = "Severe Throttling"
                    description = "High temperature. Thermal mitigation actively reducing clock frequencies."
                    isThrottling = true
                }
                PowerManager.THERMAL_STATUS_CRITICAL -> {
                    statusLabel = "Critical Thermal"
                    description = "Excessive heat. Device throttling to protect battery and SoC."
                    isThrottling = true
                }
                PowerManager.THERMAL_STATUS_EMERGENCY -> {
                    statusLabel = "Emergency Thermal"
                    description = "Emergency thermal state. Stop compute tasks immediately."
                    isThrottling = true
                }
                PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                    statusLabel = "Thermal Shutdown"
                    description = "Thermal shutdown threshold reached."
                    isThrottling = true
                }
                else -> {
                    statusLabel = "Nominal"
                    description = "Normal operating conditions."
                    isThrottling = false
                }
            }
        }

        // Battery temperature
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = context.registerReceiver(null, batteryFilter)
        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = if (tempRaw > 0) tempRaw / 10.0f else 32.0f
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatus == BatteryManager.BATTERY_STATUS_FULL

        return ThermalStatusInfo(
            status = statusLabel,
            statusCode = statusCode,
            isThrottling = isThrottling,
            description = description,
            batteryTemperatureC = tempC,
            isCharging = isCharging
        )
    }

    /**
     * Performs a full hardware analysis and returns a unified HardwareProfile.
     */
    fun analyze(context: Context): HardwareProfile {
        val ram = detectRam(context)
        val cpu = detectCpu()
        val thermal = detectThermalStatus(context)

        // Storage
        val dataDir = Environment.getDataDirectory()
        val stat = StatFs(dataDir.path)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        val totalBytes = stat.blockCountLong * stat.blockSizeLong
        val freeGb = (freeBytes.toDouble() / (1024 * 1024 * 1024)).toFloat()
        val totalGb = (totalBytes.toDouble() / (1024 * 1024 * 1024)).toFloat()

        // Battery level
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = context.registerReceiver(null, batteryFilter)
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 85
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val batteryPercent = if (level >= 0 && scale > 0) ((level.toFloat() / scale) * 100).roundToInt() else 85

        // Compute AI Capability Score (0-100)
        var score = 0
        // RAM (up to 40 pts)
        score += when {
            ram.totalRamMb >= 12000 -> 40
            ram.totalRamMb >= 8000 -> 34
            ram.totalRamMb >= 6000 -> 28
            ram.totalRamMb >= 4000 -> 20
            else -> 12
        }
        // CPU Cores (up to 30 pts)
        score += when {
            cpu.cores >= 8 -> 30
            cpu.cores >= 6 -> 24
            cpu.cores >= 4 -> 18
            else -> 10
        }
        // Storage Free (up to 15 pts)
        score += when {
            freeGb >= 32f -> 15
            freeGb >= 16f -> 12
            freeGb >= 8f -> 8
            freeGb >= 3f -> 4
            else -> 1
        }
        // ARM64 / SIMD (up to 15 pts)
        score += if (cpu.isArm64) 15 else 5

        val tier = when {
            ram.totalRamMb >= 11000 -> DeviceTier.FLAGSHIP
            ram.totalRamMb >= 7000 -> DeviceTier.PERFORMANCE
            ram.totalRamMb >= 4500 -> DeviceTier.BALANCED
            else -> DeviceTier.ULTRA_LIGHT
        }

        return HardwareProfile(
            deviceModel = "${Build.MANUFACTURER.capitalize()} ${Build.MODEL}",
            manufacturer = Build.MANUFACTURER,
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            totalRamMb = ram.totalRamMb,
            availableRamMb = ram.availableRamMb,
            usedRamPercent = ram.usedRamPercent,
            isLowMemory = ram.isLowMemory,
            safeModelRamLimitMb = ram.safeModelRamLimitMb,
            osReservedRamMb = ram.osReservedRamMb,
            cpuCores = cpu.cores,
            cpuAbi = cpu.abi,
            supportsNeon = cpu.supportsNeon,
            internalStorageFreeGb = freeGb,
            internalStorageTotalGb = totalGb,
            batteryPercent = batteryPercent,
            batteryTemperatureC = thermal.batteryTemperatureC,
            isCharging = thermal.isCharging,
            thermalStatus = thermal.status,
            thermalStatusCode = thermal.statusCode,
            isThermalThrottling = thermal.isThrottling,
            aiCapabilityScore = score.coerceIn(0, 100),
            tier = tier
        )
    }

    /**
     * Generates a comprehensive diagnostic report for display in the main UI.
     */
    fun getDiagnosticReport(context: Context): HardwareDiagnosticReport {
        val ram = detectRam(context)
        val cpu = detectCpu()
        val thermal = detectThermalStatus(context)
        val profile = analyze(context)

        val isReady = !ram.isLowMemory && ram.availableRamMb >= 1200L && cpu.isArm64 && !thermal.isThrottling
        val diagnosticBadge = when {
            !isReady && thermal.isThrottling -> "THERMAL THROTTLE"
            !isReady && ram.isLowMemory -> "LOW RAM"
            isReady -> "HEALTHY"
            else -> "OPTIMAL"
        }

        return HardwareDiagnosticReport(
            ram = ram,
            cpu = cpu,
            thermal = thermal,
            profile = profile,
            isReadyForInference = isReady,
            diagnosticBadge = diagnosticBadge
        )
    }

    fun getRecommendation(profile: HardwareProfile): ModelRecommendation {
        return when (profile.tier) {
            DeviceTier.ULTRA_LIGHT -> {
                val ramUsageMb = 480L
                ModelRecommendation(
                    recommendedModelName = "Qwen 2.5 0.5B Instruct",
                    recommendedFilename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                    recommendedQuant = "Q4_K_M",
                    estimatedSpeedTokSec = 38.5f,
                    ramHeadroomMb = (profile.availableRamMb - ramUsageMb).coerceAtLeast(100L),
                    ramUsagePercent = ((ramUsageMb.toDouble() / profile.totalRamMb) * 100).roundToInt(),
                    justification = listOf(
                        "Your device has ${profile.totalRamMb} MB total RAM with ~${profile.osReservedRamMb} MB reserved for OS breathing room.",
                        "0.5B parameters in Q4_K_M quantization fit easily in memory without triggering Android LowMemoryKiller.",
                        "Sub-30ms latency delivers instant streaming for apps and background tasks."
                    ),
                    tier = profile.tier,
                    isReadyToApply = true
                )
            }
            DeviceTier.BALANCED -> {
                val ramUsageMb = 1250L
                ModelRecommendation(
                    recommendedModelName = "Qwen 2.5 1.5B Instruct",
                    recommendedFilename = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                    recommendedQuant = "Q4_K_M",
                    estimatedSpeedTokSec = 26.0f,
                    ramHeadroomMb = (profile.availableRamMb - ramUsageMb).coerceAtLeast(300L),
                    ramUsagePercent = ((ramUsageMb.toDouble() / profile.totalRamMb) * 100).roundToInt(),
                    justification = listOf(
                        "Sweet spot for ${profile.totalRamMb} MB RAM: 1.5B model in Q4_K_M provides high coding & reasoning quality.",
                        "Leaves ${profile.totalRamMb - ramUsageMb} MB of RAM free for Android system daemons and active apps.",
                        "Generous 8K context window allows long documents and rich contextual memories."
                    ),
                    tier = profile.tier,
                    isReadyToApply = true
                )
            }
            DeviceTier.PERFORMANCE -> {
                val ramUsageMb = 2380L
                ModelRecommendation(
                    recommendedModelName = "Llama 3.2 3B Instruct",
                    recommendedFilename = "llama-3.2-3b-instruct-q4_k_m.gguf",
                    recommendedQuant = "Q4_K_M",
                    estimatedSpeedTokSec = 20.0f,
                    ramHeadroomMb = (profile.availableRamMb - ramUsageMb).coerceAtLeast(800L),
                    ramUsagePercent = ((ramUsageMb.toDouble() / profile.totalRamMb) * 100).roundToInt(),
                    justification = listOf(
                        "8GB RAM detected: 3B parameter model in Q4_K_M is the premier mobile choice for advanced multi-step logic.",
                        "Leaves ~${profile.totalRamMb - ramUsageMb} MB free for Android OS breathing room.",
                        "Meta LLaMA 3.2 architecture optimized with NEON vector instructions for ~20 tok/s."
                    ),
                    tier = profile.tier,
                    isReadyToApply = true
                )
            }
            DeviceTier.FLAGSHIP -> {
                val ramUsageMb = 2750L
                ModelRecommendation(
                    recommendedModelName = "Phi-4-mini 3.8B Instruct",
                    recommendedFilename = "phi-4-mini-instruct-q4_k_m.gguf",
                    recommendedQuant = "Q4_K_M",
                    estimatedSpeedTokSec = 17.5f,
                    ramHeadroomMb = (profile.availableRamMb - ramUsageMb).coerceAtLeast(2000L),
                    ramUsagePercent = ((ramUsageMb.toDouble() / profile.totalRamMb) * 100).roundToInt(),
                    justification = listOf(
                        "Flagship device with ${profile.totalRamMb} MB RAM safely runs state-of-the-art 3.8B models in Q4_K_M.",
                        "Microsoft Phi-4-mini excels in complex math, STEM reasoning, and code synthesis.",
                        "Well within the 1B to 4B mobile sweet spot, maintaining healthy OS memory headroom."
                    ),
                    tier = profile.tier,
                    isReadyToApply = true
                )
            }
        }
    }

    private fun String.capitalize(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

