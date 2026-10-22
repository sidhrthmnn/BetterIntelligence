package com.example

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AiCoreRepository
import com.example.ui.AiCoreViewModel
import com.example.ui.AppTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Live download in the isolated package; never changes the Play app or its models. */
class ModelDownloadUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun downloadIsPromotedAndFinishedFileIsClearlyUsable() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("modelUi") != "true") return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName.endsWith(".validation"))
        val repository = AiCoreRepository.getInstance(context)
        val model = withTimeout(30_000) { repository.allModels.first { it.isNotEmpty() } }
            .single { it.filename == "smollm2-135m-instruct-q4_k_m.gguf" }
        val vm = ViewModelProvider(compose.activity)[AiCoreViewModel::class.java]
        compose.runOnUiThread { vm.selectTab(AppTab.MODELS) }
        compose.onNodeWithTag("model_ram_summary").assertExists()
        if (!model.isInstalled) {
            compose.onNodeWithTag("models_list").performScrollToNode(hasTestTag("download_model_${model.id}"))
            compose.onNodeWithTag("download_model_${model.id}").performClick()
            compose.waitUntil(15_000) { vm.allModels.value.any { it.id == model.id && (it.downloadState == "DOWNLOADING" || it.isInstalled) } }
            compose.waitForIdle()
            compose.onNodeWithTag("model_card_${model.id}").assertIsDisplayed()
            compose.waitUntil(180_000) { vm.allModels.value.any { it.id == model.id && it.isInstalled } }
        }
        compose.onNodeWithTag("models_list").performScrollToNode(hasTestTag("model_status_${model.id}"))
        compose.onNodeWithTag("model_status_${model.id}").assertTextEquals("Downloaded · Ready to use")
        compose.onNodeWithTag("load_model_${model.id}").assertExists()
        val installed = repository.allModels.first().single { it.id == model.id }
        assertEquals("DOWNLOADED", installed.downloadState)
        assertTrue(installed.isChecksumVerified)
        assertTrue(File(installed.localFilePath!!).length() > 100_000_000)
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, "model-download-ready.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        Unit
    }
}
