package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in real CPU/OpenCL release check with a small, verified mobile export. */
@RunWith(AndroidJUnit4::class)
class LiteRtReleaseDeviceTest {
    @Test fun cpuGpuCancellationAndModelSwitching(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("testLiteRtRelease") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val file = File(context.filesDir, "models/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm")
        check(file.isFile)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            var count = input.read(buffer)
            while (count >= 0) { hash.update(buffer, 0, count); count = input.read(buffer) }
        }
        val sha = hash.digest().joinToString("") { "%02x".format(it) }
        assertEquals(args.getString("liteRtSha256"), sha)
        val engine = InferenceEngine(context)
        val report = JSONObject().put("sha256", sha)
        try {
            engine.loadLiteRt(file, identity = sha, initialContext = 1280).getOrThrow()
            val params = GenerationParams(contextSize = 1280, temperature = 0f, maxTokens = 32)
            engine.setProcessingMode(ProcessingMode.CPU)
            val cpu = engine.generateSync("What is 17 multiplied by 6? Give only the number.", params)
            report.put("cpuOutput", cpu).put("cpu", JSONObject(engine.telemetry.value.toJson()))
            assertTrue("The small model must give the correct arithmetic answer: $cpu", Regex("\\b102\\b").containsMatchIn(cpu))
            assertEquals("CPU", engine.telemetry.value.activeBackend)
            engine.setProcessingMode(ProcessingMode.AUTO)
            val automatic = engine.generateSync("What is 17 multiplied by 6? Give only the number.", params)
            assertTrue("AUTO must preserve the correct arithmetic answer: $automatic", Regex("\\b102\\b").containsMatchIn(automatic))
            report.put("automatic", JSONObject(engine.telemetry.value.toJson()))
            if (engine.hardware.capabilities.value.gpuAvailable) {
                engine.setProcessingMode(ProcessingMode.GPU)
                val gpu = engine.generateSync("What is 17 multiplied by 6? Give only the number.", params)
                assertTrue("GPU must give the correct arithmetic answer: $gpu", Regex("\\b102\\b").containsMatchIn(gpu))
                assertEquals("GPU", engine.telemetry.value.activeBackend)
                report.put("gpu", JSONObject(engine.telemetry.value.toJson()))
            } else report.put("gpuUnavailableReason", engine.hardware.capabilities.value.gpuReason)
            val first = CompletableDeferred<Unit>()
            val generating = engine.generateStream("Count from one to five hundred, separated by commas.", params.copy(maxTokens = 512),
                object : StreamTokenListener {
                    override fun onToken(token: String, tokenIndex: Int) { first.complete(Unit) }
                    override fun onComplete(fullText: String, latencyMs: Long, tokensPerSec: Float, promptTokens: Int, completionTokens: Int) {}
                    override fun onError(errorCode: Int, errorMessage: String) { if (!first.isCompleted) first.completeExceptionally(IllegalStateException(errorMessage)) }
                })
            withTimeout(30_000) { first.await() }
            assertTrue(runCatching { engine.unloadActiveModel() }.isFailure)
            engine.cancelGeneration()
            withTimeout(30_000) { generating.join() }
            assertFalse(engine.telemetry.value.isRunning)
            engine.unloadActiveModel()
            assertFalse(engine.isReady())
            engine.loadCustomGguf(File(context.filesDir, "models/smollm2-135m-instruct-q4_k_m.gguf")).getOrThrow()
            engine.setProcessingMode(ProcessingMode.CPU)
            assertTrue(engine.generateSync("Hi", GenerationParams(maxTokens = 2, contextSize = 512)).isNotBlank())
            assertFalse(engine.isLiteRtLoaded())
            report.put("status", "passed")
        } catch (failure: Exception) {
            report.put("status", "failed").put("failure", failure.toString())
            throw failure
        } finally {
            engine.unloadActiveModel()
            File(context.filesDir, "litert-release-validation.json").writeText(report.toString(2))
        }
    }
}
