package com.example.engine

import android.content.Context
import android.os.Build
import com.example.BuildConfig
import org.json.JSONObject

/** Hardware discovery is preliminary. Only a successful model decode validates acceleration. */
data class DeviceIntelligenceSnapshot(val profile: HardwareProfile, val capabilities: AccelerationCapabilities,
    val identity: String, val checkedAt: Long = System.currentTimeMillis()) {
    val canBenchmark get() = !profile.isLowMemory && profile.availableRamMb >= 768 && !profile.isThermalThrottling &&
        (profile.isCharging || profile.batteryPercent >= 20)
    fun json() = JSONObject().put("identity", identity).put("checkedAt", checkedAt)
        .put("device", profile.deviceModel).put("android", profile.androidVersion).put("abi", profile.cpuAbi)
        .put("cores", profile.cpuCores).put("neon", profile.supportsNeon).put("totalRamMb", profile.totalRamMb)
        .put("availableRamMb", profile.availableRamMb).put("budgetMb", profile.safeModelRamLimitMb)
        .put("storageFreeGb", profile.internalStorageFreeGb).put("battery", profile.batteryPercent)
        .put("charging", profile.isCharging).put("thermal", profile.thermalStatus)
        .put("vulkanProbe", capabilities.gpuAvailable).put("gpu", capabilities.gpuName)
        .put("npuValidated", capabilities.npuAvailable).put("npuReason", capabilities.npuReason)
}
class DeviceIntelligenceChecker(private val context: Context) {
    fun check(engine: InferenceEngine): DeviceIntelligenceSnapshot {
        engine.refreshHardware()
        val profile = HardwareAnalyzer.analyze(context)
        val identity = listOf(Build.FINGERPRINT, SmartBackendSelector.RUNTIME, BuildConfig.VERSION_CODE,
            profile.cpuAbi, profile.totalRamMb, profile.safeModelRamLimitMb / 512,
            engine.hardware.capabilities.value.gpuAvailable).joinToString(":")
        return DeviceIntelligenceSnapshot(profile, engine.hardware.capabilities.value, identity).also {
            context.getSharedPreferences("device_intelligence", Context.MODE_PRIVATE).edit().putString("snapshot", it.json().toString()).apply()
        }
    }
}
