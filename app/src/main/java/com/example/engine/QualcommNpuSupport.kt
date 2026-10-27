package com.example.engine

import android.content.Context
import android.os.Build
import android.system.Os
import com.example.BuildConfig
import com.google.ai.edge.litertlm.BackendType
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.NpuBrand
import java.io.File

internal data class NpuCompatibility(val compatible: Boolean, val reason: String)

/** Compatibility is only eligibility for a real load/decode test, never a claim of acceleration. */
internal object QualcommNpuSupport {
    const val RUNTIME = "litertlm-0.18.0-qnn-2.44.0-v79-dispatch-9c039e24-policy-1"
    val libraries = listOf("libLiteRtDispatch_Qualcomm.so", "libQnnHtp.so", "libQnnSystem.so",
        "libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so")

    @Synchronized fun configureDspSearchPath(context: Context) {
        // FastRPC searches for the Hexagon skel separately from Android's linker.
        // Preserve OEM search locations; never replace LD_LIBRARY_PATH or affect
        // the existing CPU/OpenCL/Vulkan loaders.
        val directory = context.applicationInfo.nativeLibraryDir
        val existing = Os.getenv("ADSP_LIBRARY_PATH").orEmpty().split(';').filter { it.isNotBlank() }
        val defaults = listOf("/vendor/lib/rfsa/adsp", "/vendor/dsp/cdsp", "/vendor/dsp", "/system/lib/rfsa/adsp", "/dsp")
        Os.setenv("ADSP_LIBRARY_PATH", (listOf(directory) + existing + defaults).distinct().joinToString(";"), true)
    }

    fun compatibility(enabled: Boolean, sdk: Int, abi: String, deviceSoc: String, modelSoc: String,
        modelNpu: Boolean, qualcommModel: Boolean, librariesPresent: Boolean): NpuCompatibility {
        fun no(reason: String) = NpuCompatibility(false, reason)
        if (!enabled) return no("Qualcomm NPU validation build required; CPU/GPU remain available in this release.")
        if (sdk < 31 || abi != "arm64-v8a") return no("Qualcomm NPU requires a physical ARM64 Android 12+ device.")
        if (!modelNpu || !qualcommModel) return no("This file has no compiled Qualcomm NPU text graph. GGUF and standard CPU/GPU LiteRT files cannot use QNN automatically.")
        fun normalized(s: String) = s.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
        val device = normalized(deviceSoc); val model = normalized(modelSoc)
        if (device != "SM8750") return no("This QNN package targets SM8750/Hexagon v79; this device reports ${deviceSoc.ifBlank { "an unknown chipset" }}.")
        if (model != device) return no("NPU model targets ${modelSoc.ifBlank { "an unknown chipset" }}, but this device is $device. Import the matching compiled .litertlm file.")
        if (!librariesPresent) return no("Required Qualcomm QNN/HTP and LiteRT dispatch libraries are missing.")
        return NpuCompatibility(true, "Matching Qualcomm SM8750 graph and QNN libraries found; runtime decode validation is still required.")
    }

    fun inspect(context: Context, info: ModelInfo): NpuCompatibility {
        if (!BuildConfig.QUALCOMM_NPU_ENABLED) return NpuCompatibility(false,
            "Qualcomm NPU validation build required; CPU/GPU remain available in this release.")
        val directory = File(context.applicationInfo.nativeLibraryDir)
        val hasNpu = BackendType.NPU in info.supportedBackends(Modality.TEXT)
        val result = compatibility(BuildConfig.QUALCOMM_NPU_ENABLED, Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "",
            if (hasNpu) info.socName(Modality.TEXT).orEmpty() else "", hasNpu,
            hasNpu && info.npuBrand(Modality.TEXT) == NpuBrand.QUALCOMM,
            libraries.all { File(directory, it).isFile })
        if (!result.compatible) return result
        return try {
            // Check the actual OEM driver can be resolved, rather than a vendor name.
            System.loadLibrary("cdsprpc")
            result
        } catch (failure: LinkageError) {
            NpuCompatibility(false, "Qualcomm FastRPC driver cannot be loaded: ${failure.message.orEmpty().take(160)}")
        }
    }

    // Shared RAM also backs QNN/FastRPC buffers; an NPU creates no extra memory.
    fun memoryMb(bytes: Long, allocationContext: Int): Long =
        ModelMemoryPlanner.estimate(bytes, allocationContext, 524288L).totalMb + 128
}
