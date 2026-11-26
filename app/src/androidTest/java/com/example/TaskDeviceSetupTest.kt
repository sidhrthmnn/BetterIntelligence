package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AiCoreRepository
import com.example.data.ClientAppPolicyEntity
import com.example.engine.ProcessingMode
import com.example.tasks.TaskAccess
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class TaskDeviceSetupTest {
    @Test fun allowOnlyTestClient() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("taskSetup") == "true")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName.endsWith(".validation"))
        val repo = AiCoreRepository.getInstance(context)
        repo.awaitModelInitialization()
        repo.engine.setProcessingMode(ProcessingMode.CPU)
        if (InstrumentationRegistry.getArguments().getString("legacyModel") == "true") {
            val model = repo.allModels.first().first { it.isInstalled && it.filename == "smollm2-135m-instruct-q4_k_m.gguf" }
            repo.switchActiveModel(model)
            repo.engine.unloadActiveModel()
        }
        val scopes = if (InstrumentationRegistry.getArguments().getString("textOnly") == "true") 0 else
            listOf(TaskAccess.VISION, TaskAccess.AUDIO, TaskAccess.VOICE, TaskAccess.INTENSIVE,
                TaskAccess.DOWNLOAD).fold(0) { bits, access -> bits or access.bit }
        repo.updatePolicy(ClientAppPolicyEntity(packageName = "com.example.betterclient.rustvalidation",
            appName = "Isolated task validation", isWhitelisted = true, allowInference = true,
            taskScopes = scopes))
        Unit
    }
}
