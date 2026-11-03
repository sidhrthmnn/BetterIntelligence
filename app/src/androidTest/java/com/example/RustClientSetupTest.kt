package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.*
import com.example.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

/** Explicit opt-in setup modifies only this task's isolated validation DB. */
class RustClientSetupTest {
    @Test fun configureIsolatedClient(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rustClientSetup") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val repository = AiCoreRepository.getInstance(context)
        withTimeout(30_000) { repository.allModels.first { it.isNotEmpty() } }
        while (repository.isModelLoading) delay(100)
        val file = File(context.filesDir, "models/SmolLM2-135M-Instruct-Q8_0.gguf")
        check(file.isFile)
        val model = repository.registerImportedGguf(file, GgufParser().parseFromFile(file).getOrThrow())
        repository.engine.setProcessingMode(ProcessingMode.CPU)
        repository.switchActiveModel(model)
        repository.updatePolicy(ClientAppPolicyEntity(packageName = "com.example.betterclient.rustvalidation",
            appName = "Isolated Rust compatibility client", isWhitelisted = true, allowInference = true,
            allowReadMemory = false, allowWriteMemory = false, rateLimitPerMin = 60))
        repository.engine.unloadActiveModel()
    }
}
