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
        repository.updatePolicy(ClientAppPolicyEntity("com.example.approved", "Test", rateLimitPerMin = 1))
        assertTrue(repository.checkSecurityAndRateLimit("com.example.approved").first)
        assertFalse(repository.checkSecurityAndRateLimit("com.example.approved").first)
        val model = ModelLibrary.read(context).first()
        val corrupt = File(context.cacheDir, "corrupt.gguf").apply { writeText("GGUF corrupted") }
        assertFalse(repository.downloadManager.verifyLocalFile(corrupt, model.sha256Checksum).isMatch)
        corrupt.delete()
        Unit
    }
}
