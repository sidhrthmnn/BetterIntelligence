package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.engine.*
import com.example.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GgufInferenceTest {
    @Test fun newMobileCatalogModelsRunOnNativeCpuBackend() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = InferenceEngine()
        val filenames = setOf("gemma-3-270m-it-Q4_K_M.gguf", "qwen2.5-1.5b-instruct-q4_k_m.gguf", "smollm2-1.7b-instruct-q4_k_m.gguf")
        val requested = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mobileModelFilename")
        require(requested == null || requested in filenames) { "Unknown mobile catalog test model" }
        try {
            for (model in ModelLibrary.read(context).filter { it.filename in filenames && (requested == null || it.filename == requested) }) {
                val file = File(context.filesDir, "models/${model.filename}")
                assertTrue("Push ${model.filename} into files/models first", file.isFile)
                engine.loadCustomGguf(file).getOrThrow()
                val output = engine.generateSync("Write a short greeting.", GenerationParams(maxTokens = 16, contextSize = 2048, temperature = 0f))
                assertTrue("No native output from ${model.name}", output.isNotBlank())
                assertTrue(engine.telemetry.value.completionTokens > 0)
                android.util.Log.i("BetterGgufTest", "${model.name}: $output")
                engine.unloadActiveModel()
            }
        } finally { engine.unloadActiveModel() }
    }

    @Test fun realOfflineInferenceAndModelSwitching() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = InferenceEngine()
        val models = ModelLibrary.read(context)
        val selected = listOf(models[0], models[2], models[3])
        for (model in selected) {
            val file = File(context.filesDir, "models/${model.filename}")
            assertTrue("Push GGUF test files to files/models first", file.isFile)
            assertTrue(engine.loadCustomGguf(file).isSuccess)
            val output = engine.generateSync("What is two plus two? Answer briefly.", GenerationParams(maxTokens = 24, temperature = 0f))
            assertTrue(output.isNotBlank())
            assertTrue("Expected actual arithmetic response from ${model.name}: $output", output.contains("4") || output.contains("four", true))
            assertTrue(engine.telemetry.value.completionTokens > 0)
            android.util.Log.i("BetterGgufTest", "${model.name}: $output")
        }
        engine.unloadActiveModel()
        assertFalse(engine.isReady())
    }

    @Test fun unknownAppsNeedApprovalAndChecksumsStayTrusted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = AiCoreRepository.getInstance(context)
        assertFalse(repository.checkSecurityAndRateLimit("com.example.unapproved").first)
        repository.updatePolicy(ClientAppPolicyEntity("com.example.approved", "Test", isWhitelisted = true, rateLimitPerMin = 1))
        assertTrue(repository.checkSecurityAndRateLimit("com.example.approved").first)
        assertFalse(repository.checkSecurityAndRateLimit("com.example.approved").first)
        val model = ModelLibrary.read(context).first()
        val corrupt = File(context.cacheDir, "corrupt.gguf").apply { writeText("GGUF corrupted") }
        assertFalse(repository.downloadManager.verifyLocalFile(corrupt, model.sha256Checksum).isMatch)
        corrupt.delete()
        Unit
    }
}
