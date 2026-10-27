package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QualcommNpuTest {
    private fun compatible(enabled: Boolean = true, sdk: Int = 36, abi: String = "arm64-v8a",
        device: String = "SM8750", model: String = "sm8750", npu: Boolean = true,
        qualcomm: Boolean = true, libraries: Boolean = true) =
        QualcommNpuSupport.compatibility(enabled, sdk, abi, device, model, npu, qualcomm, libraries)

    @Test fun requiresTheRealBuildLibrariesAndMatchingCompiledGraph() {
        assertTrue(compatible().compatible)
        assertFalse(compatible(enabled = false).compatible)
        assertFalse(compatible(sdk = 30).compatible)
        assertFalse(compatible(abi = "x86_64").compatible)
        assertFalse(compatible(npu = false).compatible) // GGUF / standard CPU/GPU exports
        assertFalse(compatible(qualcomm = false).compatible)
        assertFalse(compatible(libraries = false).compatible)
        assertFalse(compatible(device = "SM8650", model = "SM8650").compatible) // no v75 runtime packaged
        assertFalse(compatible(model = "SM8850").compatible)
        assertFalse(compatible(model = "").compatible)
        assertTrue(compatible(model = "SM8850").reason.contains("SM8850"))
    }

    @Test fun cpuAndNpuShareMemoryAndBadProbeOutputsNeverPass() {
        assertTrue(QualcommNpuSupport.memoryMb(700L shl 20, 1280) > 700 + 1280 / 2)
        assertTrue(QualcommNpuSupport.memoryMb(700L shl 20, 2048) > QualcommNpuSupport.memoryMb(700L shl 20, 1280))
        assertTrue(QualcommNpuController.correct("102"))
        assertTrue(QualcommNpuController.correct(" 102. "))
        assertFalse(QualcommNpuController.correct("<unused15>102"))
        assertFalse(QualcommNpuController.correct("The answer is 102"))
        assertFalse(QualcommNpuController.correct("102 102 102"))
    }

    @Test fun npuCacheRequiresValidatedRuntimeAndChangesWithQnnModelAndContext() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("backend_decisions", 0).edit().clear().commit()
        val selector = SmartBackendSelector(context)
        val key = SmartBackendSelector.key("firmware", "sha", QualcommNpuSupport.RUNTIME, 1280)
        val npu = BackendMeasurement(ProcessingMode.NPU, 0, 2, 40f, 100, 1000f, 500, 0, 0, 0f)
        selector.save(key, npu, listOf(npu))
        assertNull(selector.cached(key, true))
        assertEquals(ProcessingMode.NPU, selector.cached(key, false, npuAvailable = true)?.backend)
        assertTrue(selector.hasValidatedNpu(key))
        assertFalse(selector.hasValidatedGpu(key))
        selector.save("too-hot", npu, listOf(npu.copy(thermalAfter = 3)))
        assertFalse(selector.hasValidatedNpu("too-hot"))
        assertNull(selector.cached("too-hot", false, npuAvailable = true))
        assertNull(selector.cached(SmartBackendSelector.key("firmware", "sha", "other QNN", 1280), false, npuAvailable = true))
        assertNull(selector.cached(SmartBackendSelector.key("firmware", "sha", QualcommNpuSupport.RUNTIME, 2048), false, npuAvailable = true))
        selector.failed(key, "QNN context initialization failed")
        assertNull(selector.cached(key, false, npuAvailable = true))
        selector.reset()
    }

    @Test fun interruptedNpuAttemptIsQuarantinedAndNotMislabelledAsGpu() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("backend_decisions", 0)
        prefs.edit().clear().commit()
        val selector = SmartBackendSelector(context)
        selector.beginNpuAttempt("npu-crash")
        prefs.edit().putString("pending_process", "previous-process").commit()
        val next = SmartBackendSelector(context)
        assertTrue(next.blocked("npu-crash"))
        assertEquals("Previous NPU attempt did not finish", next.failureReason("npu-crash"))
        next.reset()
    }

    @Test fun automaticCanPreferCpuOrGpuOverNpu() {
        val npu = BackendMeasurement(ProcessingMode.NPU, 0, 2, 40f, 5000, 1000f, 3000, 0, 0, 0f)
        val cpu = npu.copy(backend = ProcessingMode.CPU, tokensPerSecond = 20f, firstTokenMs = 100)
        assertEquals(ProcessingMode.CPU, SmartBackendSelector.choose(listOf(cpu, npu))?.backend)
        assertEquals(ProcessingMode.NPU, SmartBackendSelector.choose(listOf(cpu, npu.copy(firstTokenMs = 50, tokensPerSecond = 100f)))?.backend)
        assertEquals(cpu, SmartBackendSelector.choose(listOf(cpu, npu.copy(thermalAfter = 3))))
    }
}
