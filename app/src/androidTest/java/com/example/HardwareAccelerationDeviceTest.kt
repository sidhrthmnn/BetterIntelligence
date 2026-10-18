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
    @Test fun nativeCpuBenchmarkOptionalVulkanAndResourceCleanup(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation")) { "Native experiments require the isolated validation package" }
        val records = JSONArray()
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("samples", records)
        val reportFile = File(context.filesDir, "acceleration-benchmark.json")
        fun progress(stage: String) {
            report.put("stage", stage).put("status", "running")
            reportFile.writeText(report.toString(2))
        }
        fun record(value: JSONObject) { records.put(value); reportFile.writeText(report.toString(2)) }
        progress("repository initialization")
        val repository = AiCoreRepository.getInstance(context)
        val expected = ModelLibrary.read(context).map { it.filename }.toSet()
        kotlinx.coroutines.withTimeout(300_000) { repository.allModels.first { rows -> rows.map { it.filename }.containsAll(expected) } }
        repository.unloadModel() // Await startup restore before taking control of the shared engine.
        val engine = repository.engine
        try {
            val args = InstrumentationRegistry.getArguments()
            val names = args.getString("benchmarkModels")?.split(',') ?: listOf("smollm2-135m-instruct-q4_k_m.gguf", "qwen2.5-0.5b-instruct-q4_k_m.gguf")
            val repetitions = (args.getString("benchmarkRepetitions")?.toIntOrNull() ?: 3).coerceIn(1, 10)
            for (name in names) {
                require(File(name).name == name) { "Use a staged model filename" }
                val file = File(context.filesDir, "models/$name")
                assertTrue("Stage $name in files/models first", file.isFile)
                progress("load $name")
                engine.loadCustomGguf(file).getOrThrow()
                engine.setProcessingMode(ProcessingMode.CPU)
                for (threads in listOf(2, 4)) {
                  repeat(repetitions) { iteration ->
                    progress("$name CPU threads=$threads iteration=$iteration")
                    val output = engine.generateSync("Count from one to twenty, separated by commas.",
                        GenerationParams(maxTokens = 16, contextSize = 512, threads = threads, temperature = 0f))
                    assertTrue(output.isNotBlank())
                    val t = engine.telemetry.value
                    assertEquals("CPU", t.activeBackend); assertEquals(0, t.activeGpuLayers)
                    assertTrue(t.completionTokens >= 2); assertTrue(t.timeToFirstTokenMs > 0)
                    record(JSONObject(t.toJson()).put("filename", name).put("kind", "measured native CPU")
                        .put("iteration", iteration).put("response", output))
                  }
                }
                progress("$name native history handoff and isolation")
                val historyParams = GenerationParams(maxTokens = 4, contextSize = 512, threads = 4, temperature = 0f)
                assertTrue(engine.generateSync("Say hello.", historyParams).isNotBlank())
                val withoutHistory = engine.telemetry.value.promptTokens
                assertTrue(engine.generateSync("Say hello.", historyParams.copy(history = listOf(
                    ChatTurn("user", "Remember this synthetic code: FERN-63."), ChatTurn("assistant", "The code is FERN-63.")))).isNotBlank())
                val withHistory = engine.telemetry.value.promptTokens
                assertTrue("Role-separated history must reach the native tokenizer", withHistory > withoutHistory)
                assertTrue(engine.generateSync("Say hello.", historyParams).isNotBlank())
                assertEquals("Independent requests must not inherit earlier turns", withoutHistory, engine.telemetry.value.promptTokens)
                val checks = report.optJSONArray("historyChecks") ?: JSONArray().also { report.put("historyChecks", it) }
                checks.put(JSONObject().put("filename", name).put("promptTokensWithoutHistory", withoutHistory)
                    .put("promptTokensWithHistory", withHistory).put("independentPromptTokens", engine.telemetry.value.promptTokens))
                if (engine.hardware.capabilities.value.gpuAvailable && args.getString("benchmarkGpu") != "false") {
                    for (layers in listOf(8, -1)) {
                      repeat(repetitions) { iteration ->
                        progress("$name GPU layers=$layers iteration=$iteration")
                        engine.setProcessingMode(ProcessingMode.GPU, layers)
                        val output = engine.generateSync("Count from one to twenty, separated by commas.", GenerationParams(maxTokens = 16, contextSize = 512, temperature = 0f))
                        assertTrue(output.isNotBlank())
                        val telemetry = engine.telemetry.value
                        if (telemetry.activeGpuLayers > 0) assertEquals("GPU", telemetry.activeBackend)
                        else {
                            assertEquals("CPU", telemetry.activeBackend)
                            assertTrue("Fallback needs a diagnostic", telemetry.lastFallback.isNotBlank())
                        }
                        record(JSONObject(telemetry.toJson()).put("filename", name)
                            .put("requestedLayerLimit", layers).put("kind", if (telemetry.activeGpuLayers > 0) "measured hardware Vulkan" else "measured GPU fallback")
                            .put("iteration", iteration).put("response", output))
                      }
                    }
                } else {
                    Log.i("BetterAccelerationTest", "GPU UNVERIFIED/UNAVAILABLE: ${engine.hardware.capabilities.value.gpuReason}")
                }
                progress("$name AUTO")
                engine.setProcessingMode(ProcessingMode.AUTO, -1)
                assertTrue(engine.generateSync("Hi", GenerationParams(maxTokens = 2, contextSize = 512)).isNotBlank())
                engine.unloadActiveModel()
                assertFalse(engine.isReady()); assertEquals(0, engine.telemetry.value.activeGpuLayers)
                assertNoNativeWeightMapping(file)
            }
            // Same-file reload is shared rather than allocating a duplicate model.
            val file = File(context.filesDir, "models/smollm2-135m-instruct-q4_k_m.gguf")
            repeat(3) {
                progress("same-file reload $it")
                engine.loadCustomGguf(file).getOrThrow(); engine.loadCustomGguf(file).getOrThrow()
                assertTrue(engine.generateSync("Hi", GenerationParams(maxTokens = 2, contextSize = 512)).isNotBlank())
                engine.unloadActiveModel()
                assertNoNativeWeightMapping(file)
            }
            report.put("status", "complete").put("stage", "complete")
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            engine.unloadActiveModel()
            val result = report
                .put("gpuAvailable", engine.hardware.capabilities.value.gpuAvailable)
                .put("gpuName", engine.hardware.capabilities.value.gpuName)
                .put("gpuFreeBytes", engine.hardware.capabilities.value.gpuFreeBytes)
                .put("gpuTotalBytes", engine.hardware.capabilities.value.gpuTotalBytes)
                .put("cpuAbi", engine.hardware.capabilities.value.cpuAbi)
                .put("cpuBuild", engine.hardware.capabilities.value.cpuBuild)
                .put("gpuReason", engine.hardware.capabilities.value.gpuReason).put("samples", records)
            reportFile.writeText(result.toString(2))
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
