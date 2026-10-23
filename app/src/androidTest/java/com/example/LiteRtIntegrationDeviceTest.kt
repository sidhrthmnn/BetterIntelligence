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
        check(context.packageName.endsWith(".validation"))
        val offline = InstrumentationRegistry.getArguments().getString("offlineIntegration") == "true"
        if (offline) assertNull("Host must disable network connectivity for the offline fixture",
            context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork)
        val repository = AiCoreRepository.getInstance(context)
        val expected = ModelLibrary.read(context).map { it.filename }.toSet()
        // A persisted catalogue can arrive before startup has rechecked staged files.
        // Wait for both fixtures to be installed instead of retaining that stale snapshot.
        val models = withTimeout(300_000) { repository.allModels.first { rows ->
            rows.map { it.filename }.containsAll(expected) &&
                rows.any { it.filename == "gemma-4-E2B-it.litertlm" && it.isInstalled } &&
                rows.any { it.name.contains("SmolLM2") && it.isInstalled }
        } }
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
            val small = engine.generateSync("What is 17 multiplied by 6? Give only the number.",
                GenerationParams(maxTokens = 16, contextSize = 512, temperature = 0f))
            assertEquals("102", small.trim())
            assertEquals("The verified E2B export needs a 1K CPU allocation even for a 512 request",
                1024, engine.allocatedLiteRtContext)
            if (offline) {
                val coordinator = com.example.grounding.GroundingCoordinator(mapOf(
                    com.example.grounding.SearchProviderId.WEB to com.example.grounding.PublicWebResearch()))
                val result = coordinator.retrieve("Moon", true, com.example.grounding.GroundingMode.ON,
                    com.example.grounding.SearchProviderId.WEB)
                assertEquals("SEARCH_FAILED", result.status)
                assertTrue(result.sources.isEmpty())
            }
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
            withTimeout(30_000) { while (repository.isModelLoading) kotlinx.coroutines.delay(50) }
            assertEquals("READY", binder.serviceStatus.getString("status"))
            assertFalse(binder.serviceStatus.getBoolean("model_loading"))
            assertTrue(binder.isModelLoaded)
            assertEquals(model.name, binder.activeModelName)
            assertEquals(model.quantization, binder.activeModelQuantization)
            assertEquals("102", binder.generateTextSync("What is 17 multiplied by 6? Give only the number.", "", 0f, 16).trim())
            val previousParams = repository.preferencesRepository.generationPreferences.first()
            try {
                repository.preferencesRepository.saveGenerationPreferences(previousParams.copy(contextSize = 1024))
                val streamed = CompletableDeferred<String>()
                binder.generateStream("What is 17 multiplied by 6? Give only the number.", "", 0f, 1f, 16,
                    object : com.example.ipc.ILocalAiStreamCallback.Stub() {
                        override fun onToken(token: String?, tokenIndex: Int) {}
                        override fun onComplete(text: String?, latencyMs: Long, tokensPerSecond: Float, promptTokens: Int, completionTokens: Int) {
                            streamed.complete(text.orEmpty())
                        }
                        override fun onError(code: Int, message: String?) { streamed.completeExceptionally(IllegalStateException("$code: $message")) }
                    })
                assertEquals("102", withTimeout(30_000) { streamed.await() }.trim())
                withTimeout(5_000) { while (engine.telemetry.value.isRunning) kotlinx.coroutines.delay(10) }
                assertEquals("AIDL streaming must honor BI's context instead of allocating an unrequested 4K graph", 1024, engine.allocatedLiteRtContext)
            } finally { repository.preferencesRepository.saveGenerationPreferences(previousParams) }
            val polishBackend = InstrumentationRegistry.getArguments().getString("polishBackend") ?: "CPU"
            if (polishBackend == "GPU") {
                engine.setProcessingMode(ProcessingMode.AUTO)
                assertEquals("102", engine.generateSync("What is 17 multiplied by 6? Give only the number.",
                    GenerationParams(maxTokens = 16, contextSize = 2048, temperature = 0f)).trim())
                engine.setProcessingMode(ProcessingMode.GPU)
            }
            val polish = org.json.JSONArray()
            for ((input, expected) in listOf("i has 2 apples." to "I have 2 apples.",
                "Do not delete the backup." to "Do not delete the backup.",
                "Keep com.example.demo unchanged." to "Keep com.example.demo unchanged.",
                "Visit https://example.com at 09:15." to "Visit https://example.com at 09:15.",
                "Email dev@example.com, not user@example.com." to "Email dev@example.com, not user@example.com.")) {
                val result = binder.polishText(input)
                val row = org.json.JSONObject().put("input", input).put("expected", expected).put("status", result.getString("status"))
                    .put("output", result.getString("text")).put("latencyMs", result.getLong("latency_ms"))
                    .put("actualBackend", engine.telemetry.value.activeBackend)
                if (result.getString("status") != "SUCCESS") {
                    // Fixed synthetic text only: diagnose actual production-prompt failures without logging user text.
                    runCatching { engine.generateSync(com.example.ipc.PolishText.prompt(input),
                        com.example.ipc.PolishText.parameters(engine.activeContextLength)) }
                        .onSuccess { row.put("diagnosticRaw", it) }.onFailure { row.put("diagnosticFailure", it.toString()) }
                }
                polish.put(row)
                File(context.filesDir, "litert-integration-polish.json").writeText(polish.toString(2))
            }
            for (index in 0 until polish.length()) {
                val row = polish.getJSONObject(index)
                assertEquals(row.getString("input"), "SUCCESS", row.getString("status"))
                assertEquals(row.getString("input"), row.getString("expected"), row.getString("output"))
                assertEquals("Polish must execute on the requested qualified backend", polishBackend, row.getString("actualBackend"))
            }

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
