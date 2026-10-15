package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only setup for testing sample-client across real Android UIDs. */
@RunWith(AndroidJUnit4::class)
class ServiceIntegrationSetupTest {
    @Test fun prepare() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = AiCoreRepository.getInstance(context)
        val models = kotlinx.coroutines.withTimeout(60_000) {
            repository.allModels.first { list ->
                list.count { !it.isCustom } == com.example.engine.ModelLibrary.read(context).size &&
                    list.any { it.name == "Qwen 2.5 0.5B Instruct" && it.isInstalled }
            }
        }
        repository.switchActiveModel(models.first { it.name == "Qwen 2.5 0.5B Instruct" })
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("approveSample") == "true") {
            repository.updatePolicy(ClientAppPolicyEntity("com.example.betterclient", "Example AI client", isWhitelisted = true,
                isBlocked = arguments.getString("blockSample") == "true",
                allowInference = arguments.getString("disableInference") != "true",
                rateLimitPerMin = arguments.getString("requestLimit")?.toIntOrNull() ?: 60))
        } else {
            repository.deletePolicy("com.example.betterclient")
        }
        Unit
    }
}
