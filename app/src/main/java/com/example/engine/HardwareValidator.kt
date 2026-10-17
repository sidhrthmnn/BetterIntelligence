package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Validates hardware environment prerequisites before loading heavy LLMs into memory.
 * Ensures 64-bit ARM (arm64-v8a) architecture and sufficient available physical RAM.
 */
object HardwareValidator {

    const val MIN_RECOMMENDED_RAM_MB = 1024L // Runtime baseline, not a model-size restriction.
    const val MIN_FREE_RAM_MB = 512L        // Exact model/context checks are in ModelMemoryPlanner.

    data class ValidationResult(
        val isSupported: Boolean,
        val reason: String? = null,
        val isArm64: Boolean = false,
        val availableRamMb: Long = 0,
        val totalRamMb: Long = 0,
        val isLowMemory: Boolean = false
    ) {
        val isCompatible: Boolean get() = isSupported
        val hasSufficientRam: Boolean get() = totalRamMb >= MIN_RECOMMENDED_RAM_MB
        val abi: String get() = if (isArm64) "arm64-v8a" else (Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown")
    }

    fun validateEnvironment(context: Context): ValidationResult {
        // Packaged CPU runtimes support the Android ARM64 and x86_64 baselines.
        val supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray()
        val is64BitArm = supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
        
        // For development/emulator environments, allow x86_64 with fallback notice
        val is64Bit = is64BitArm || supportedAbis.any { it == "x86_64" }

        // 2. RAM Pre-flight Check via ActivityManager.MemoryInfo
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availRamMb = memInfo.availMem / (1024 * 1024)

        if (!is64Bit) {
            return ValidationResult(
                isSupported = false,
                reason = "Incompatible CPU ABI (${supportedAbis.firstOrNull() ?: "unknown"}). The runtime supports arm64-v8a and x86_64.",
                isArm64 = false,
                availableRamMb = availRamMb,
                totalRamMb = totalRamMb,
                isLowMemory = memInfo.lowMemory
            )
        }

        if (totalRamMb < MIN_RECOMMENDED_RAM_MB) {
            return ValidationResult(
                isSupported = false,
                reason = "Device has ${totalRamMb}MB total RAM. At least ${MIN_RECOMMENDED_RAM_MB} MB is needed for the runtime; models require additional memory.",
                isArm64 = is64BitArm,
                availableRamMb = availRamMb,
                totalRamMb = totalRamMb,
                isLowMemory = memInfo.lowMemory
            )
        }

        if (memInfo.lowMemory || availRamMb < MIN_FREE_RAM_MB) {
            return ValidationResult(
                isSupported = false,
                reason = "System is in a low-memory state (${availRamMb}MB free). Close background apps to free at least ${MIN_FREE_RAM_MB}MB before loading model weights.",
                isArm64 = is64BitArm,
                availableRamMb = availRamMb,
                totalRamMb = totalRamMb,
                isLowMemory = true
            )
        }

        return ValidationResult(
            isSupported = true,
            reason = null,
            isArm64 = is64BitArm,
            availableRamMb = availRamMb,
            totalRamMb = totalRamMb,
            isLowMemory = false
        )
    }
}
