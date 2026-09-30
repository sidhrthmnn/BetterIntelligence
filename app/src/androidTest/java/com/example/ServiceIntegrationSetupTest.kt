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
        val models = repository.allModels.first { it.count { model -> !model.isCustom } == 4 }
        repository.switchActiveModel(models.first { it.name == "Qwen 2.5 0.5B Instruct" })
        if (InstrumentationRegistry.getArguments().getString("approveSample") == "true") {
            repository.updatePolicy(ClientAppPolicyEntity("com.example.betterclient", "Example AI client", isWhitelisted = true))
        } else {
            repository.deletePolicy("com.example.betterclient")
        }
        Unit
    }
}
