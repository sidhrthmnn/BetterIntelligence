package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Validates hardware environment prerequisites before loading heavy LLMs into memory.
 * Ensures 64-bit ARM (arm64-v8a) architecture and sufficient available physical RAM.
 */
object HardwareValidator {

    const val MIN_RECOMMENDED_RAM_MB = 6000L // 6 GB minimum recommended for LLMs
    const val MIN_FREE_RAM_MB = 1500L        // 1.5 GB minimum free memory

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
        // 1. Architecture Check (LLMs require 64-bit ARM registers & NEON/DotProd SIMD)
        val supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray()
        val is64BitArm = supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
        
        // For development/emulator environments, allow x86_64 with fallback notice
        val is64Bit = is64BitArm || supportedAbis.any { it.contains("64") }

        // 2. RAM Pre-flight Check via ActivityManager.MemoryInfo
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availRamMb = memInfo.availMem / (1024 * 1024)

        if (!is64Bit) {
            return ValidationResult(
                isSupported = false,
                reason = "Incompatible CPU ABI (${supportedAbis.firstOrNull() ?: "unknown"}). 64-bit ARM (arm64-v8a) is required for LLM tensor execution.",
                isArm64 = false,
                availableRamMb = availRamMb,
                totalRamMb = totalRamMb,
                isLowMemory = memInfo.lowMemory
            )
        }

        if (totalRamMb < MIN_RECOMMENDED_RAM_MB) {
            return ValidationResult(
                isSupported = false,
                reason = "Device has ${totalRamMb}MB total RAM. At least 6GB is recommended for stable on-device LLM inference without OOM risk.",
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
