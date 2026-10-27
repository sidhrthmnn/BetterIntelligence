package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AiCoreRepository
import com.example.data.ClientAppPolicyEntity
import com.example.engine.HardwareAnalyzer
import com.example.engine.ModelLibrary
import com.example.engine.ProcessingMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit isolated two-app test fixture; never changes the production app. */
@RunWith(AndroidJUnit4::class)
class ClientFallbackSetupTest {
    @Test fun prepareRealModelsAndSyntheticClient() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("setupClientFallback") != "true") return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val repository = AiCoreRepository.getInstance(context)
        val rows = withTimeout(300_000) { repository.allModels.first { models ->
            models.size >= ModelLibrary.read(context).size &&
                models.any { it.filename == "gemma-4-E2B-it.litertlm" && it.isInstalled } &&
                models.any { it.filename == "smollm2-135m-instruct-q4_k_m.gguf" && it.isInstalled }
        } }
        withTimeout(300_000) { while (repository.isModelLoading) delay(50) }
        repository.unloadModel()
        val primary = rows.single { it.filename == "gemma-4-E2B-it.litertlm" }
        val secondary = rows.single { it.filename == "smollm2-135m-instruct-q4_k_m.gguf" }
        repository.preferencesRepository.savePrimaryModelId(primary.id)
        repository.preferencesRepository.saveSecondaryModelId(secondary.id)
        repository.preferencesRepository.saveAutoRamFallback(true)
        repository.engine.setProcessingMode(ProcessingMode.CPU)
        repository.updatePolicy(ClientAppPolicyEntity("com.aistudio.typerigh2.jkwpzq.validation", "TypeRight Validation",
            isWhitelisted = true, rateLimitPerMin = 60))
        val hardware = HardwareAnalyzer.analyze(context)
        File(context.filesDir, "client-fallback-setup.json").writeText(JSONObject()
            .put("primary", primary.name).put("secondary", secondary.name)
            .put("availableRamMb", hardware.availableRamMb).put("safeBudgetMb", hardware.safeModelRamLimitMb)
            .put("primaryRequiredMb", ModelLibrary.memoryEstimate(primary, 2048).totalMb)
            .put("secondaryRequiredMb", ModelLibrary.memoryEstimate(secondary, 2048).totalMb)
            .put("pressureSimulated", false).toString(2))
    }
}
