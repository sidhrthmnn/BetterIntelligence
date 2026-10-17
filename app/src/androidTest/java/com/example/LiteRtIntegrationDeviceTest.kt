package com.example

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AiCoreRepository
import com.example.engine.*
import com.example.ipc.ILocalAiCoreService
import com.example.ipc.LocalAiCoreService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses the real shared production path, including unchanged AIDL methods.
 * Opt-in because it requires the verified multi-GB E2B file on a physical phone.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtIntegrationDeviceTest {
    @Test fun sharedModelBinderAutoCancellationAndSwitching() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("gemmaIntegration") != "true") return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AiCoreRepository.getInstance(context)
        val models = withTimeout(300_000) { repository.allModels.first { it.size == ModelLibrary.read(context).size } }
        repository.unloadModel()
        val model = models.single { it.filename == "gemma-4-E2B-it.litertlm" }
        assertTrue("Stage the pinned E2B file first", model.isInstalled)
        val engine = repository.engine
        val serviceBinder = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { serviceBinder.complete(binder) }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        var bound = false
        try {
            repository.switchActiveModel(model)
            engine.setProcessingMode(ProcessingMode.CPU)
            val cpu = engine.generateSync("What is 17 multiplied by 6? Give only the number.",
                GenerationParams(maxTokens = 16, contextSize = 2048, temperature = 0f))
            assertEquals("102", cpu.trim())
            assertEquals("CPU", engine.telemetry.value.activeBackend)
            assertEquals(0, engine.telemetry.value.activeGpuLayers)
            assertEquals(model.name, engine.activeModelName)
            val originalLoad = engine.telemetry.value.modelLoadTimeMs
            repository.switchActiveModel(model)
            assertEquals(originalLoad, engine.telemetry.value.modelLoadTimeMs)
            assertEquals(SupportedEngine.LITERT, repository.engineManager.selectedEngineType.value)

            bound = context.bindService(Intent(context, LocalAiCoreService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            val binder = ILocalAiCoreService.Stub.asInterface(withTimeout(30_000) { serviceBinder.await() })
            assertTrue(binder.isModelLoaded)
            assertEquals(model.name, binder.activeModelName)
            assertEquals(model.quantization, binder.activeModelQuantization)
            assertEquals("102", binder.generateTextSync("What is 17 multiplied by 6? Give only the number.", "", 0f, 16).trim())

            engine.setProcessingMode(ProcessingMode.AUTO)
            assertEquals("102", engine.generateSync("What is 17 multiplied by 6? Give only the number.",
                GenerationParams(maxTokens = 16, contextSize = 2048, temperature = 0f)).trim())
            assertEquals(0, engine.telemetry.value.activeGpuLayers) // LiteRT offloads a graph, not llama.cpp layers.
            File(context.filesDir, "litert-integration-telemetry.json").writeText(engine.telemetry.value.toJson())

            val first = CompletableDeferred<Unit>()
            val partial = StringBuilder()
            val generation = engine.generateStream("Count from one to five hundred, separated by commas.",
                GenerationParams(maxTokens = 512, contextSize = 2048, temperature = 0f), object : StreamTokenListener {
                    override fun onToken(token: String, tokenIndex: Int) { partial.append(token); first.complete(Unit) }
                    override fun onComplete(fullText: String, latencyMs: Long, tokensPerSec: Float, promptTokens: Int, completionTokens: Int) {}
                    override fun onError(errorCode: Int, errorMessage: String) { if (!first.isCompleted) first.completeExceptionally(IllegalStateException(errorMessage)) }
                })
            withTimeout(30_000) { first.await() }
            assertTrue(runCatching { engine.unloadActiveModel() }.isFailure)
            assertTrue(engine.loadCustomGguf(File(context.filesDir, "models/smollm2-135m-instruct-q4_k_m.gguf")).isFailure)
            engine.cancelGeneration()
            withTimeout(30_000) { generation.join() }
            assertTrue(partial.isNotEmpty())
            assertFalse(engine.telemetry.value.isRunning)
            repository.unloadModel()
            assertFalse(engine.isReady())
            val retained = File("/proc/self/maps").useLines { lines -> lines.any { it.endsWith("/${model.filename}") } }
            assertFalse("LiteRT model mappings must be released", retained)
            val tiny = models.single { it.filename == "smollm2-135m-instruct-q4_k_m.gguf" }
            repository.switchActiveModel(tiny)
            engine.setProcessingMode(ProcessingMode.CPU)
            assertFalse(engine.isLiteRtLoaded())
            assertTrue(engine.generateSync("Hi", GenerationParams(maxTokens = 4, contextSize = 512)).isNotBlank())
            assertEquals(SupportedEngine.LLAMA_CPP, repository.engineManager.selectedEngineType.value)
        } finally {
            if (bound) context.unbindService(connection)
            repository.unloadModel()
        }
    }
}
