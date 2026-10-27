package com.example

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.example.engine.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccelerationTest {
    private val base = File("../models/smollm2-135m-instruct-q4_k_m.gguf")
    private fun sample(mode: ProcessingMode, speed: Float, ttft: Long = 100, thermal: Int = 0) =
        BackendMeasurement(mode, if (mode == ProcessingMode.GPU) 16 else 0, 2, speed, ttft, 512f, 100, 0, thermal, 0f)

    @Test fun autoUsesMeasuredLatencyAndStabilityRatherThanBackendRanking() {
        val cpu = sample(ProcessingMode.CPU, 20f)
        assertEquals(ProcessingMode.CPU, SmartBackendSelector.choose(listOf(cpu, sample(ProcessingMode.GPU, 22f, 500)))!!.backend)
        assertEquals(ProcessingMode.GPU, SmartBackendSelector.choose(listOf(cpu, sample(ProcessingMode.GPU, 45f)))!!.backend)
        assertEquals(cpu, SmartBackendSelector.choose(listOf(cpu, sample(ProcessingMode.GPU, 100f, thermal = 3))))
        assertEquals(cpu, SmartBackendSelector.choose(listOf(cpu, sample(ProcessingMode.GPU, 100f).copy(stable = false))))
    }

    @Test fun aSecondFacadeDoesNotMistakeALiveGpuAttemptForAPreviousProcessCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("backend_decisions", 0)
        preferences.edit().clear().commit()
        val first = SmartBackendSelector(context)
        first.beginGpuAttempt("live")
        assertFalse(SmartBackendSelector(context).blocked("live"))
        first.endGpuAttempt()
        preferences.edit().putString("pending", "interrupted").putString("pending_process", "previous-process").commit()
        val restarted = SmartBackendSelector(context)
        assertTrue(restarted.blocked("interrupted"))
        assertEquals("Previous GPU attempt did not finish", restarted.failureReason("interrupted"))
        restarted.reset()
    }

    @Test fun resourcePolicyBoundsThreadsAndAccountsForSharedRam() {
        assertEquals(8, ResourcePolicy.performanceCores(List(6) { 792L } + List(2) { 1024L }, emptyList()))
        assertEquals(4, ResourcePolicy.performanceCores(List(4) { 480L } + List(4) { 1024L }, emptyList()))
        assertEquals(2, ResourcePolicy.performanceCores(emptyList(), listOf(1800, 1800, 2800, 2800)))
        assertEquals(0, ResourcePolicy.performanceCores(emptyList(), emptyList()))
        assertEquals(4, ResourcePolicy.threads(8, 8, 0, ResourceState()))
        assertEquals(2, ResourcePolicy.threads(8, 8, 0, ResourceState(thermal = 3)))
        assertEquals(4096, ModelMemoryPlanner.liteRtAllocationContext(512, 4096, false))
        assertEquals(512, ModelMemoryPlanner.liteRtAllocationContext(512, 32000, true))
        assertEquals(1280, ModelMemoryPlanner.liteRtAllocationContext(2048, 1280, true))
        assertEquals(2048, ModelMemoryPlanner.liteRtAllocationContext(512, 0, false))
        assertEquals(1, ResourcePolicy.threads(1, 0, 8, ResourceState()))
        assertEquals(4, ResourcePolicy.threads(12, 6, 0, ResourceState()))
        assertEquals(2, ResourcePolicy.threads(12, 6, 0, ResourceState(thermal = 3)))
        assertEquals(2, ResourcePolicy.threads(12, 6, 0, ResourceState(powerSave = true)))
        val caps = AccelerationCapabilities(gpuAvailable = true, gpuFreeBytes = 8L shl 30)
        assertEquals(0, ResourcePolicy.gpuLayers(2L shl 30, 32, caps, ResourceState(availableMb = 600), -1))
        assertEquals(0, ResourcePolicy.gpuLayers(2L shl 30, 32, caps, ResourceState(lowMemory = true), -1))
        assertEquals(0, ResourcePolicy.gpuLayers(2L shl 30, 32, caps, ResourceState(thermal = 3), -1))
        val partial = ResourcePolicy.gpuLayers(4L shl 30, 32, caps, ResourceState(availableMb = 2500), -1)
        assertTrue(partial in 1..31)
        assertTrue(ResourcePolicy.gpuLayers(2L shl 30, 32, caps, ResourceState(), 8) <= 8)
        assertEquals(0, ResourcePolicy.gpuLayers(100, 32, caps.copy(gpuAvailable = false), ResourceState(), -1))
        assertEquals(0, ResourcePolicy.gpuLayers(100, 32, caps.copy(gpuFreeBytes = 0, gpuTotalBytes = 8L shl 30), ResourceState(), -1))
    }

    @Test fun cacheInvalidatesForFirmwareModelQuantisationRuntimeAndConfiguration() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("backend_decisions", 0).edit().clear().commit()
        var clock = 1000L
        var selector = SmartBackendSelector(context) { clock }
        val key = SmartBackendSelector.key("device", "model", "Q4", "runtime", 2048, 4, 16)
        val result = sample(ProcessingMode.GPU, 40f)
        selector.save(key, result, listOf(result))
        selector = SmartBackendSelector(context) { clock }
        assertEquals(16, selector.cached(key, true)!!.layers)
        assertNull(selector.cached(key, false))
        for (parts in listOf(arrayOf<Any>("firmware", "model", "Q4", "runtime", 2048, 4, 16),
            arrayOf<Any>("device", "other model", "Q4", "runtime", 2048, 4, 16),
            arrayOf<Any>("device", "model", "Q8", "runtime", 2048, 4, 16),
            arrayOf<Any>("device", "model", "Q4", "new runtime", 2048, 4, 16),
            arrayOf<Any>("device", "model", "Q4", "runtime", 4096, 4, 16))) assertNull(selector.cached(SmartBackendSelector.key(*parts), true))
        clock += SmartBackendSelector.MAX_AGE_MS + 1
        assertNull(selector.cached(key, true))
    }

    @Test fun failedAndInterruptedGpuAttemptsHavePersistentCooldown() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("backend_decisions", 0).edit().clear().commit()
        var clock = 1000L
        var selector = SmartBackendSelector(ctx) { clock }
        selector.beginGpuAttempt("crashed")
        ctx.getSharedPreferences("backend_decisions", 0).edit().putString("pending_process", "previous-process").commit()
        selector = SmartBackendSelector(ctx) { clock }
        assertTrue(selector.blocked("crashed"))
        selector.failed("failed", "Driver rejected the model")
        assertTrue(SmartBackendSelector(ctx) { clock }.blocked("failed"))
        clock += SmartBackendSelector.FAILURE_COOLDOWN_MS + 1
        assertFalse(selector.blocked("failed"))
    }

    @Test fun npuIsUnavailableForGgufAndNoRuntimeIsInvented() {
        val hw = HardwareAccelerationManager()
        assertEquals(ProcessingMode.AUTO, hw.mode.value)
        assertFalse(hw.capabilities.value.npuAvailable)
        assertTrue(runCatching { hw.setMode(ProcessingMode.NPU) }.isFailure)
    }

    @Test fun transientGpuMemoryFailuresWaitForActualHeadroomAndResetAfterValidation() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("backend_decisions", 0).edit().clear().commit()
        var clock = 1000L
        val selector = SmartBackendSelector(ctx) { clock }
        val cpu = sample(ProcessingMode.CPU, 20f)
        selector.failed("memory", "Memory pressure", 5000)
        selector.save("memory", cpu, listOf(cpu))
        clock += 6 * 60 * 1000
        assertTrue(selector.blocked("memory", 4000)); assertFalse(selector.blocked("memory", 6000))
        assertNotNull(selector.cached("memory", true, 4000))
        assertNull(selector.cached("memory", true, 6000)) // Retest when measured headroom recovers.
        val gpu = sample(ProcessingMode.GPU, 40f)
        selector.save("memory", gpu, listOf(cpu, gpu))
        assertEquals(0L, selector.minimumAvailableMb("memory")); assertTrue(selector.hasValidatedGpu("memory"))
        selector.failed("driver", "Driver failed")
        selector.reset()
        assertFalse(selector.blocked("driver")); assertNull(selector.cached("memory", true))
    }

    @Test fun cpuMappedWeightsAreNotCountedAsAdditionalAvailableRam() {
        val meta = GgufParser().parseFromFile(base).getOrThrow()
        assertEquals(0L, ModelMemoryPlanner.releasableGpuWeightsMb(meta, 0))
        val partial = ModelMemoryPlanner.releasableGpuWeightsMb(meta, 8)
        assertTrue(partial > 0 && partial < meta.totalSizeMb)
        assertTrue(ModelMemoryPlanner.releasableGpuWeightsMb(meta, 999) <= meta.totalSizeMb)
    }

    @Test fun gpuLayersReachLoadingAndFailedInitialisationRecoversUsingOneCpuModel() = runBlocking {
        val runtime = FakeRuntime(gpu = true, gpuFails = true)
        val engine = InferenceEngine(null, runtime)
        try {
            engine.loadCustomGguf(base).getOrThrow()
            engine.setProcessingMode(ProcessingMode.GPU, 8)
            assertEquals("answer", engine.generateSync("Hi", GenerationParams(maxTokens = 2)))
            assertTrue(runtime.loads.contains(8))
            assertEquals(0, runtime.loads.last())
            assertEquals("CPU", engine.telemetry.value.activeBackend)
            assertEquals(0, engine.telemetry.value.activeGpuLayers)
            assertEquals(1, engine.telemetry.value.fallbackCount)
            assertTrue(engine.telemetry.value.backendDecision.contains("using CPU"))
            assertEquals(1, runtime.maxModels)
        } finally { engine.unloadActiveModel() }
        assertEquals(0, runtime.models)
    }

    @Test fun unsupportedVulkanAndGpuExecutionFailureBothKeepCpuFunctional() = runBlocking {
        for (gpu in listOf(false, true)) {
            val runtime = FakeRuntime(gpu = gpu, executionFails = gpu)
            val engine = InferenceEngine(null, runtime)
            try {
                engine.loadCustomGguf(base).getOrThrow()
                if (gpu) engine.setProcessingMode(ProcessingMode.GPU, 8)
                engine.generateSync("Hi", GenerationParams(maxTokens = 2))
                assertEquals("CPU", engine.telemetry.value.activeBackend)
                assertTrue(engine.isReady())
                assertEquals(1, runtime.models)
            } finally { engine.unloadActiveModel() }
        }
    }

    @Test fun automaticTuningDoesNotRunAgainForEveryRequest() = runBlocking {
        val runtime = FakeRuntime(gpu = true)
        val engine = InferenceEngine(null, runtime)
        try {
            engine.loadCustomGguf(base).getOrThrow()
            engine.generateSync("Hi", GenerationParams(maxTokens = 2))
            val calls = runtime.generations
            assertEquals(5, calls) // CPU+GPU warmups/trials, then the user's answer.
            assertTrue("User-visible TTFT includes initial tuning", engine.telemetry.value.timeToFirstTokenMs >= 40)
            engine.generateSync("Hello again", GenerationParams(maxTokens = 2))
            assertEquals(calls + 1, runtime.generations)
        } finally { engine.unloadActiveModel() }
    }

    @Test fun manualGpuModeReusesSuccessfulValidationAfterCpuSwitch() = runBlocking {
        val runtime = FakeRuntime(gpu = true)
        val engine = InferenceEngine(null, runtime)
        try {
            engine.loadCustomGguf(base).getOrThrow()
            engine.setProcessingMode(ProcessingMode.GPU, 8)
            engine.generateSync("First", GenerationParams(maxTokens = 2))
            assertEquals(5, runtime.generations)
            assertEquals("GPU", engine.telemetry.value.activeBackend)
            engine.setProcessingMode(ProcessingMode.CPU)
            engine.generateSync("Second", GenerationParams(maxTokens = 2))
            val calls = runtime.generations
            engine.setProcessingMode(ProcessingMode.GPU)
            engine.generateSync("Third", GenerationParams(maxTokens = 2))
            assertEquals(calls + 1, runtime.generations)
            assertEquals(8, engine.telemetry.value.activeGpuLayers)
            assertTrue(engine.telemetry.value.backendDecision.contains("cached"))
            assertEquals(1, runtime.maxModels)
        } finally { engine.unloadActiveModel() }
    }

    @Test fun modelSwitchAndUnloadCannotRaceActiveGenerationAndCancellationIsStructured() = runBlocking {
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        val runtime = FakeRuntime(gpu = false, entered = entered, proceed = proceed)
        val engine = InferenceEngine(null, runtime)
        engine.loadCustomGguf(base).getOrThrow()
        try {
            val generation = async(Dispatchers.Default) { engine.generateSync("Hi") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(engine.loadCustomGguf(base).isFailure)
            assertTrue(runCatching { engine.unloadActiveModel() }.isFailure)
            assertTrue(runCatching { engine.generateSync("Concurrent Binder/client request") }.isFailure)
            engine.setProcessingMode(ProcessingMode.CPU)
            generation.cancel(); proceed.countDown(); generation.cancelAndJoin()
            assertFalse(engine.telemetry.value.isRunning)
            assertTrue(engine.loadCustomGguf(base).isSuccess)
            assertEquals(1, runtime.models)
        } finally { proceed.countDown(); engine.unloadActiveModel() }
    }

    @Test fun incompatibleFormatFailsBeforeNativeLoading() {
        val runtime = FakeRuntime(false)
        val engine = InferenceEngine(null, runtime)
        val file = File.createTempFile("bad-format", ".tflite").apply { writeText("incompatible LiteRT model, not GGUF") }
        try { assertTrue(engine.loadCustomGguf(file).isFailure); assertTrue(runtime.loads.isEmpty()) }
        finally { file.delete() }
    }

    @Test fun unchangedModelIsReusedButChangedIdentityReleasesOldNativeWeights() {
        val runtime = FakeRuntime(false)
        val engine = InferenceEngine(null, runtime)
        try {
            engine.loadCustomGguf(base, "original checksum").getOrThrow()
            val releases = runtime.releases
            engine.loadCustomGguf(base, "original checksum").getOrThrow()
            assertEquals(releases, runtime.releases)
            engine.loadCustomGguf(base, "replacement checksum").getOrThrow()
            assertEquals(releases + 1, runtime.releases)
            assertEquals(2, runtime.loads.size)
            assertEquals(1, runtime.models)
        } finally { engine.unloadActiveModel() }
    }

    private class FakeRuntime(val gpu: Boolean, val gpuFails: Boolean = false, val executionFails: Boolean = false,
        val entered: CountDownLatch? = null, val proceed: CountDownLatch? = null) : GgufRuntime {
        val loads = mutableListOf<Int>(); var models = 0; var maxModels = 0; var layers = 0; var generations = 0; var releases = 0
        override fun info(allowGpuProbe: Boolean) = """{"gpuAvailable":$gpu,"gpuName":"Test double","gpuFreeBytes":8589934592,"gpuReason":"Test"}"""
        override fun prepare(file: File, layers: Int, threads: Int, context: Int): Int {
            loads += layers; models = 0
            if (layers > 0 && gpuFails) error("Vulkan initialisation failed")
            this.layers = layers; models = 1; maxModels = maxOf(maxModels, models)
            return layers
        }
        override fun release() { models = 0; releases++ }
        override fun generate(prompt: String, system: String, observer: NativeObserver, params: GenerationParams, timeoutMs: Int): String {
            generations++
            if (layers > 0 && executionFails) error("Vulkan execution failed")
            entered?.countDown(); proceed?.await(5, TimeUnit.SECONDS)
            if (observer.isCancelled()) error("Inference cancelled")
            SystemClock.sleep(5); observer.onText("a".toByteArray(), 10, 1)
            SystemClock.sleep(5); observer.onText("answer".toByteArray(), 10, 2)
            return "answer"
        }
    }
}
