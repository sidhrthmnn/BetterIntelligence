package com.example

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.*
import com.example.data.AiCoreRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File

/** Real native execution. GPU results are only recorded if a HARDWARE Vulkan
 * device passes native preflight, layer placement and a decode. SwiftShader
 * is explicitly excluded by production code, never counted as a GPU pass.
 */
@RunWith(AndroidJUnit4::class)
class HardwareAccelerationDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun nativeCpuBenchmarkOptionalVulkanAndResourceCleanup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AiCoreRepository.getInstance(context)
        kotlinx.coroutines.withTimeout(300_000) { repository.allModels.first { it.size == ModelLibrary.read(context).size } }
        repository.unloadModel() // Await startup restore before taking control of the shared engine.
        val engine = repository.engine
        val records = JSONArray()
        try {
            val args = InstrumentationRegistry.getArguments()
            val names = args.getString("benchmarkModels")?.split(',') ?: listOf("smollm2-135m-instruct-q4_k_m.gguf", "qwen2.5-0.5b-instruct-q4_k_m.gguf")
            val repetitions = (args.getString("benchmarkRepetitions")?.toIntOrNull() ?: 3).coerceIn(1, 10)
            for (name in names) {
                require(File(name).name == name) { "Use a staged model filename" }
                val file = File(context.filesDir, "models/$name")
                assertTrue("Stage $name in files/models first", file.isFile)
                engine.loadCustomGguf(file).getOrThrow()
                engine.setProcessingMode(ProcessingMode.CPU)
                for (threads in listOf(2, 4)) {
                  repeat(repetitions) { iteration ->
                    val output = engine.generateSync("Count from one to twenty, separated by commas.",
                        GenerationParams(maxTokens = 16, contextSize = 512, threads = threads, temperature = 0f))
                    assertTrue(output.isNotBlank())
                    val t = engine.telemetry.value
                    assertEquals("CPU", t.activeBackend); assertEquals(0, t.activeGpuLayers)
                    assertTrue(t.completionTokens >= 2); assertTrue(t.timeToFirstTokenMs > 0)
                    records.put(JSONObject(t.toJson()).put("filename", name).put("kind", "measured native CPU")
                        .put("iteration", iteration).put("response", output))
                  }
                }
                if (engine.hardware.capabilities.value.gpuAvailable) {
                    for (layers in listOf(8, -1)) {
                      repeat(repetitions) { iteration ->
                        engine.setProcessingMode(ProcessingMode.GPU, layers)
                        val output = engine.generateSync("Count from one to twenty, separated by commas.", GenerationParams(maxTokens = 16, contextSize = 512, temperature = 0f))
                        assertTrue(output.isNotBlank())
                        val telemetry = engine.telemetry.value
                        if (telemetry.activeGpuLayers > 0) assertEquals("GPU", telemetry.activeBackend)
                        else {
                            assertEquals("CPU", telemetry.activeBackend)
                            assertTrue("Fallback needs a diagnostic", telemetry.lastFallback.isNotBlank())
                        }
                        records.put(JSONObject(telemetry.toJson()).put("filename", name)
                            .put("requestedLayerLimit", layers).put("kind", if (telemetry.activeGpuLayers > 0) "measured hardware Vulkan" else "measured GPU fallback")
                            .put("iteration", iteration).put("response", output))
                      }
                    }
                } else {
                    Log.i("BetterAccelerationTest", "GPU UNVERIFIED/UNAVAILABLE: ${engine.hardware.capabilities.value.gpuReason}")
                }
                engine.setProcessingMode(ProcessingMode.AUTO, -1)
                assertTrue(engine.generateSync("Hi", GenerationParams(maxTokens = 2, contextSize = 512)).isNotBlank())
                engine.unloadActiveModel()
                assertFalse(engine.isReady()); assertEquals(0, engine.telemetry.value.activeGpuLayers)
                assertNoNativeWeightMapping(file)
            }
            // Same-file reload is shared rather than allocating a duplicate model.
            val file = File(context.filesDir, "models/smollm2-135m-instruct-q4_k_m.gguf")
            repeat(3) {
                engine.loadCustomGguf(file).getOrThrow(); engine.loadCustomGguf(file).getOrThrow()
                assertTrue(engine.generateSync("Hi", GenerationParams(maxTokens = 2, contextSize = 512)).isNotBlank())
                engine.unloadActiveModel()
                assertNoNativeWeightMapping(file)
            }
        } finally {
            engine.unloadActiveModel()
            val result = JSONObject().put("device", android.os.Build.FINGERPRINT)
                .put("gpuAvailable", engine.hardware.capabilities.value.gpuAvailable)
                .put("gpuName", engine.hardware.capabilities.value.gpuName)
                .put("gpuFreeBytes", engine.hardware.capabilities.value.gpuFreeBytes)
                .put("gpuTotalBytes", engine.hardware.capabilities.value.gpuTotalBytes)
                .put("cpuAbi", engine.hardware.capabilities.value.cpuAbi)
                .put("cpuBuild", engine.hardware.capabilities.value.cpuBuild)
                .put("gpuReason", engine.hardware.capabilities.value.gpuReason).put("samples", records)
            File(context.filesDir, "acceleration-benchmark.json").writeText(result.toString(2))
            Log.i("BetterAccelerationTest", result.toString())
        }
    }

    private fun assertNoNativeWeightMapping(file: File) {
        // GgufParser's Java metadata mapping is at most 32 MiB and is GC-managed.
        // Native CPU weights map the model body and must be unmapped on release.
        val retained = File("/proc/self/maps").useLines { lines -> lines.any { line ->
            if (!line.endsWith("/${file.name}")) false else {
                val range = line.substringBefore(' ').split('-')
                range.size == 2 && range[1].toLong(16) - range[0].toLong(16) > 32L * 1024 * 1024
            }
        } }
        assertFalse("Native model body remains mapped after unload: ${file.name}", retained)
    }
}
